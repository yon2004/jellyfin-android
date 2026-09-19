package org.jellyfin.mobile.player.cast.proxy

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A minimal HTTP/1.1 reverse proxy bound to one local LAN address.
 *
 * The Chromecast connects here and every request is forwarded over whatever route the
 * phone already has to the Jellyfin server — typically a VPN tunnel the TV cannot use.
 *
 * Three things keep this from being an open relay into a private server:
 *  - the socket binds to a single LAN address, never 0.0.0.0, so it is not reachable
 *    over the VPN interface;
 *  - every URL carries a random path token generated per session;
 *  - when the receiver's address is known, connections from anything else are dropped.
 *
 * @param bindAddress the local address to listen on, from [LanAddressResolver].
 * @param allowedClient the Chromecast's address, when the Cast SDK exposes it. Null
 *   disables peer checking and leaves the path token as the only guard.
 */
class LocalStreamProxy(
    private val upstreamClient: OkHttpClient,
    private val jellyfinBaseUrl: String,
    private val bindAddress: InetAddress,
    private val allowedClient: InetAddress? = null,
) {
    private val hopByHopHeaders = setOf(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade",
    )

    /**
     * Request headers not to forward. A denylist rather than an allowlist: Jellyfin
     * accepts auth via several header names and relies on conditional-request headers
     * for caching, and an allowlist silently drops whichever ones it forgot.
     */
    private val blockedRequestHeaders = setOf(
        "host", "content-length", "expect",
    )

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    /**
     * Cached, not fixed-size: each connection blocks for the whole of its response, and
     * a Chromecast opens several at once (media, subtitles, artwork). A bounded pool
     * deadlocks as soon as every thread is parked on a stream.
     */
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "cast-relay-worker").apply { isDaemon = true }
    }

    @Volatile
    private var pathToken: String = ""

    private val streamingClient: OkHttpClient = upstreamClient.newBuilder()
        .connectTimeout(UPSTREAM_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(UPSTREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(UPSTREAM_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val isRunning: Boolean get() = running.get()

    /**
     * Binds the listener and returns the base URL to hand the receiver, including the
     * session token, e.g. "http://192.168.1.5:41000/a3f9...".
     */
    @Throws(IOException::class)
    fun start(): String {
        val token = generateToken()
        pathToken = token

        val socket = bindFreePort()
        serverSocket = socket
        running.set(true)

        acceptThread = Thread({ acceptLoop(socket) }, "cast-relay-accept").apply {
            isDaemon = true
            start()
        }

        val base = "http://${bindAddress.hostAddress}:${socket.localPort}/$token"
        Timber.tag(TAG).i("Relay listening on %s:%d", bindAddress.hostAddress, socket.localPort)
        return base
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // already closed
        }
        serverSocket = null
        pathToken = ""

        // shutdownNow, not shutdown: workers are parked on in-flight streams and will
        // never finish on their own while playback is running.
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Timber.tag(TAG).w("Relay workers did not terminate within timeout")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        Timber.tag(TAG).i("Relay stopped")
    }

    // -------------------------------------------------------------------------
    // Accept loop
    // -------------------------------------------------------------------------

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                if (!isAllowedPeer(client)) {
                    Timber.tag(TAG).w("Rejected connection from %s", client.inetAddress?.hostAddress)
                    closeQuietly(client)
                    continue
                }
                executor.submit { handleConnection(client) }
            } catch (e: SocketException) {
                if (running.get()) Timber.tag(TAG).w("Accept error: %s", e.message)
            } catch (e: IOException) {
                if (running.get()) Timber.tag(TAG).e(e, "Accept loop error")
            }
        }
    }

    private fun isAllowedPeer(client: Socket): Boolean {
        val expected = allowedClient ?: return true
        return client.inetAddress == expected
    }

    // -------------------------------------------------------------------------
    // Connection handling
    // -------------------------------------------------------------------------

    private fun handleConnection(clientSocket: Socket) {
        try {
            clientSocket.soTimeout = CLIENT_READ_TIMEOUT_MILLIS
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.trim().split(" ")
            if (parts.size < REQUEST_LINE_MIN_PARTS) {
                sendSimpleResponse(output, HTTP_BAD_REQUEST, "Bad Request")
                return
            }

            val method = parts[0]
            val rawPath = parts[1]
            val headers = readHeaders(input)

            val path = stripToken(rawPath)
            if (path == null) {
                Timber.tag(TAG).w("Rejected request with bad or missing token")
                sendSimpleResponse(output, HTTP_FORBIDDEN, "Forbidden")
                return
            }

            val bodyBytes = readRequestBody(input, method, headers)
            val upstreamUrl = buildUpstreamUrl(path)
            Timber.tag(TAG).d("%s %s", method, path)

            val upstreamRequest = buildUpstreamRequest(method, upstreamUrl, headers, bodyBytes)
            streamingClient.newCall(upstreamRequest).execute().use { response ->
                pipeResponse(response, output)
            }
        } catch (e: SocketException) {
            Timber.tag(TAG).d("Client disconnected: %s", e.message)
        } catch (e: IOException) {
            Timber.tag(TAG).w("Connection error: %s", e.message)
        } finally {
            closeQuietly(clientSocket)
        }
    }

    /**
     * Verifies and removes the session token prefix. Returns the remaining server path,
     * or null when the token is absent or wrong.
     */
    private fun stripToken(rawPath: String): String? {
        val token = pathToken
        if (token.isEmpty()) return null
        val prefix = "/$token"
        return when {
            rawPath == prefix -> "/"
            rawPath.startsWith("$prefix/") -> rawPath.substring(prefix.length)
            else -> null
        }
    }

    private fun readRequestBody(
        input: InputStream,
        method: String,
        headers: Map<String, String>,
    ): ByteArray? {
        if (method !in METHODS_WITH_BODY) return null

        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
        val transferEncoding = headers["transfer-encoding"]?.lowercase(Locale.ROOT).orEmpty()

        return when {
            contentLength > MAX_REQUEST_BODY_BYTES -> {
                Timber.tag(TAG).w("Refusing oversized request body of %d bytes", contentLength)
                null
            }
            contentLength > 0 -> readExactly(input, contentLength.toInt())
            transferEncoding.contains("chunked") -> readChunkedBody(input)
            else -> null
        }
    }

    private fun buildUpstreamRequest(
        method: String,
        url: String,
        incomingHeaders: Map<String, String>,
        body: ByteArray?,
    ): Request {
        val builder = Request.Builder().url(url)

        incomingHeaders.forEach { (key, value) ->
            val lower = key.lowercase(Locale.ROOT)
            if (lower !in hopByHopHeaders && lower !in blockedRequestHeaders) {
                builder.header(key, value)
            }
        }

        val requestBody = when {
            body != null -> {
                val contentType = incomingHeaders["content-type"] ?: DEFAULT_CONTENT_TYPE
                body.toRequestBody(contentType.toMediaTypeOrNull())
            }
            method in METHODS_REQUIRING_EMPTY_BODY -> ByteArray(0).toRequestBody(null)
            else -> null
        }

        builder.method(method, requestBody)
        return builder.build()
    }

    private fun pipeResponse(response: Response, output: OutputStream) {
        val statusText = response.message.ifEmpty { "OK" }
        output.write("HTTP/1.1 ${response.code} $statusText\r\n".toByteArray())

        var hasCorsOrigin = false
        response.headers.forEach { (name, value) ->
            val lower = name.lowercase(Locale.ROOT)
            if (lower in hopByHopHeaders) return@forEach
            output.write("$name: $value\r\n".toByteArray())
            if (lower == "access-control-allow-origin") hasCorsOrigin = true
        }

        // The receiver is a web app on a different origin to this proxy, so it needs CORS.
        if (!hasCorsOrigin) {
            output.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
        }
        output.write("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS, HEAD\r\n".toByteArray())
        output.write("Access-Control-Allow-Headers: Content-Type, Authorization, X-Emby-Authorization, Range\r\n".toByteArray())
        output.write("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n".toByteArray())

        // One request per connection. Transfer-Encoding was stripped as hop-by-hop, so
        // when upstream sent no Content-Length the close is the only end-of-body signal
        // the receiver gets — without this header it cannot tell complete from truncated.
        output.write("Connection: close\r\n".toByteArray())
        output.write("\r\n".toByteArray())
        output.flush()

        response.body?.byteStream()?.use { upstream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val bytesRead = upstream.read(buffer)
                if (bytesRead == -1) break
                output.write(buffer, 0, bytesRead)
            }
        }
        output.flush()
    }

    // -------------------------------------------------------------------------
    // HTTP parsing
    // -------------------------------------------------------------------------

    /** Reads one CRLF-terminated line, capped so a hostile peer cannot exhaust memory. */
    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        var previous = -1
        while (true) {
            val current = input.read()
            if (current == -1) return if (builder.isEmpty()) null else builder.toString()
            if (current == '\n'.code && previous == '\r'.code) return builder.dropLast(1).toString()
            if (builder.length >= MAX_LINE_LENGTH) throw IOException("Header line too long")
            builder.append(current.toChar())
            previous = current
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            if (headers.size >= MAX_HEADER_COUNT) throw IOException("Too many headers")
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] =
                    line.substring(colon + 1).trim()
            }
        }
        return headers
    }

    /** API-21-safe replacement for InputStream.readNBytes, which needs API 33. */
    @Throws(IOException::class)
    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read == -1) throw IOException("Unexpected end of stream: got $offset of $count bytes")
            offset += read
        }
        return buffer
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val result = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input)?.trim() ?: break
            val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(HEX_RADIX) ?: break
            if (chunkSize == 0) break
            if (result.size() + chunkSize > MAX_REQUEST_BODY_BYTES) throw IOException("Chunked body too large")
            result.write(readExactly(input, chunkSize))
            readLine(input) // trailing CRLF after the chunk data
        }
        return result.toByteArray()
    }

    private fun sendSimpleResponse(output: OutputStream, code: Int, reason: String) {
        val body = "$code $reason"
        output.write(
            (
                "HTTP/1.1 $code $reason\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n$body"
                ).toByteArray(),
        )
        output.flush()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildUpstreamUrl(path: String): String {
        val base = jellyfinBaseUrl.trimEnd('/')
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    @Throws(IOException::class)
    private fun bindFreePort(): ServerSocket {
        for (port in PORT_RANGE_START..PORT_RANGE_END) {
            try {
                // Three-arg constructor: binds this address only, so the listener is not
                // exposed on the VPN interface the way a 0.0.0.0 bind would be.
                return ServerSocket(port, SOCKET_BACKLOG, bindAddress)
            } catch (_: IOException) {
                // port taken, try the next
            }
        }
        throw IOException("No free port in range $PORT_RANGE_START-$PORT_RANGE_END")
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // nothing useful to do
        }
    }

    companion object {
        private const val TAG = "LocalStreamProxy"

        private const val BUFFER_SIZE = 32 * 1024
        private const val PORT_RANGE_START = 40000
        private const val PORT_RANGE_END = 49999
        private const val SOCKET_BACKLOG = 16
        private const val TOKEN_BYTES = 16
        private const val HEX_RADIX = 16

        private const val CLIENT_READ_TIMEOUT_MILLIS = 30_000
        private const val UPSTREAM_CONNECT_TIMEOUT_SECONDS = 15L
        private const val UPSTREAM_READ_TIMEOUT_SECONDS = 120L
        private const val UPSTREAM_WRITE_TIMEOUT_SECONDS = 30L
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L

        private const val MAX_LINE_LENGTH = 8 * 1024
        private const val MAX_HEADER_COUNT = 100
        private const val MAX_REQUEST_BODY_BYTES = 4 * 1024 * 1024

        private const val REQUEST_LINE_MIN_PARTS = 2
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_FORBIDDEN = 403
        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

        private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH")
        private val METHODS_REQUIRING_EMPTY_BODY = setOf("OPTIONS", "HEAD")
    }
}

package org.jellyfin.mobile.player.cast.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LocalStreamProxy
 *
 * Binds a lightweight HTTP/1.1 reverse proxy on the local Wi-Fi interface.
 * Chromecast connects to this proxy; all requests are forwarded over the
 * existing Tailscale / VPN tunnel to the remote Jellyfin server.
 *
 * Usage:
/ *   val proxy = LocalStreamProxy(context, okHttpClient, jellyfinBaseUrl)
 *   val proxyBaseUrl = proxy.start()   // e.g. "http://192.168.1.5:45123"
 *   // rewrite Cast receiver URL with proxyBaseUrl
 *   proxy.stop()
 */
class LocalStreamProxy(
    private val context: Context,
    private val upstreamClient: OkHttpClient,
    private val jellyfinBaseUrl: String,
) {
    private val hopByHopHeaders = setOf(
        "connection", "keep-alive", "proxy-authenticate",
        "proxy-authorization", "te", "trailers",
        "transfer-encoding", "upgrade",
    )

    private val passthroughRequestHeaders = setOf(
        "accept", "accept-encoding", "range", "content-type",
        "user-agent", "x-emby-authorization", "authorization",
        "cache-control", "origin", "access-control-request-method",
        "access-control-request-headers",
    )


    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newFixedThreadPool(MAX_WORKER_THREADS)
    private var acceptThread: Thread? = null

    private val streamingClient: OkHttpClient = upstreamClient.newBuilder()
        .connectTimeout(UPSTREAM_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(UPSTREAM_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val isRunning: Boolean get() = running.get()

    @Throws(IOException::class)
    fun start(): String {
        val lanIp = getLanIpAddress()
            ?: throw IOException("Unable to determine LAN IP. Is Wi-Fi connected?")

        val socket = bindFreePort()
        serverSocket = socket
        running.set(true)

        acceptThread = Thread({
            Timber.tag(TAG).i("Proxy listening on $lanIp:${socket.localPort}")
            while (running.get()) {
                try {
                    val client = socket.accept()
                    executor.submit { handleConnection(client) }
                } catch (e: SocketException) {
                    if (running.get()) Timber.tag(TAG).w("Accept error: ${e.message}")
                } catch (e: IOException) {
                    Timber.tag(TAG).e(e, "Accept loop error")
                }
            }
        }, "proxy-accept").also { it.isDaemon = true; it.start() }

        return "http://$lanIp:${socket.localPort}"
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        executor.shutdown()
        try { executor.awaitTermination(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        Timber.tag(TAG).i("Proxy stopped")
    }

    // -------------------------------------------------------------------------
    // Connection handling
    // -------------------------------------------------------------------------

    private fun handleConnection(clientSocket: Socket) {
        try {
            clientSocket.soTimeout = 30_000
            val input  = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) { sendBadRequest(output); return }

            val method = parts[0]
            val path   = parts[1]

            val headers = readHeaders(input)
            val upstreamUrl = buildUpstreamUrl(path)
            Timber.tag(TAG).d("$method $path → $upstreamUrl")

            val bodyBytes: ByteArray? = if (method in listOf("POST", "PUT", "PATCH")) {
                val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
                val transferEncoding = headers["transfer-encoding"]?.lowercase(Locale.ROOT) ?: ""
                when {
                    contentLength > 0 -> readExactly(input, contentLength.toInt())
                    transferEncoding.contains("chunked") -> readChunkedBody(input)
                    else -> null
                }
            } else null

            if (bodyBytes != null) {
                android.util.Log.d("JFProxy", "  ↳ $method body ${bodyBytes.size} bytes: ${String(bodyBytes).take(200)}")
            }

            val upstreamRequest = buildUpstreamRequest(method, upstreamUrl, headers, bodyBytes)

            streamingClient.newCall(upstreamRequest).execute().use { response ->
                pipeResponse(response, output)
            }

        } catch (e: SocketException) {
            Timber.tag(TAG).d("Client disconnected: ${e.message}")
        } catch (e: IOException) {
            Timber.tag(TAG).w("Connection error: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: IOException) {}
        }
    }

    private fun buildUpstreamRequest(
        method: String,
        url: String,
        incomingHeaders: Map<String, String>,
        body: ByteArray?,
    ): Request {
        val builder = Request.Builder().url(url)

        incomingHeaders.forEach { (k, v) ->
            if (k.lowercase(Locale.ROOT) in passthroughRequestHeaders) builder.header(k, v)
        }


        val requestBody = body?.let {
            val ct = incomingHeaders["content-type"] ?: "application/octet-stream"
            it.toRequestBody(ct.toMediaTypeOrNull())
        } ?: if (method == "OPTIONS" || method == "HEAD") {
            // OkHttp requires an explicit empty body for OPTIONS/HEAD
            ByteArray(0).toRequestBody(null)
        } else {
            null
        }

        builder.method(method, requestBody)
        return builder.build()
    }

    private fun pipeResponse(response: Response, output: OutputStream) {
        output.write("HTTP/1.1 ${response.code} ${response.message}\r\n".toByteArray())

        var hasCorsOrigin = false
        response.headers.forEach { (name, value) ->
            if (name.lowercase(Locale.ROOT) !in hopByHopHeaders) {
                output.write("$name: $value\r\n".toByteArray())
                if (name.lowercase(Locale.ROOT) == "access-control-allow-origin") hasCorsOrigin = true
            }
        }

        // Ensure CORS headers are present — the Chromecast receiver is a web app and
        // will reject responses without them since the proxy host differs from Jellyfin.
        if (!hasCorsOrigin) {
            output.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
        }
        output.write("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS, HEAD\r\n".toByteArray())
        output.write("Access-Control-Allow-Headers: Content-Type, Authorization, X-Emby-Authorization, Range\r\n".toByteArray())
        output.write("Access-Control-Expose-Headers: Content-Range, Content-Length\r\n".toByteArray())
        output.write("\r\n".toByteArray())
        output.flush()

        val body = response.body
        if (body != null) {
            body.byteStream().use { upstream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (upstream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        }
        output.flush()
    }

    // -------------------------------------------------------------------------
    // HTTP parsing
    // -------------------------------------------------------------------------

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code && prev == '\r'.code) return sb.dropLast(1).toString()
            sb.append(b.toChar())
            prev = b
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key   = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                val value = line.substring(colon + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    /** API-21-safe replacement for InputStream.readNBytes (added in API 33). */
    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read == -1) break
            offset += read
        }
        return buf
    }

    /**
     * Reads a chunked HTTP body (Transfer-Encoding: chunked).
     * Each chunk starts with its size in hex on a line, followed by the data.
     * A zero-size chunk signals the end.
     */
    private fun readChunkedBody(input: InputStream): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input)?.trim() ?: break
            val chunkSize = sizeLine.split(";")[0].trim().toIntOrNull(16) ?: break
            if (chunkSize == 0) break
            val chunk = readExactly(input, chunkSize)
            result.write(chunk)
            readLine(input) // consume trailing CRLF after chunk data
        }
        return result.toByteArray()
    }

    private fun sendBadRequest(output: OutputStream) {
        val body = "<html><body>400 Bad Request</body></html>"
        output.write(
            "HTTP/1.1 400 Bad Request\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                .toByteArray(),
        )
        output.flush()
    }

    // -------------------------------------------------------------------------
    // URL building
    // -------------------------------------------------------------------------

    private fun buildUpstreamUrl(path: String): String {
        val base = jellyfinBaseUrl.trimEnd('/')
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    // -------------------------------------------------------------------------
    // Network helpers — API 21 compatible
    // -------------------------------------------------------------------------

    private fun getLanIpAddress(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getLanIpAddressModern()
        } else {
            getLanIpAddressLegacy()
        }
    }

    @Suppress("DEPRECATION")
    private fun getLanIpAddressLegacy(): String? {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val ip = wm?.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return String.format(
            Locale.ROOT,
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8  and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff,
        )
    }

    private fun getLanIpAddressModern(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val caps    = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val linkProps = cm.getLinkProperties(network) ?: return null
        return linkProps.linkAddresses
            .map { it.address }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    @Throws(IOException::class)
    private fun bindFreePort(): ServerSocket {
        for (port in PORT_RANGE_START..PORT_RANGE_END) {
            try { return ServerSocket(port) } catch (_: IOException) { /* try next */ }
        }
        throw IOException("No free port available in range $PORT_RANGE_START–$PORT_RANGE_END")
    }

    companion object {
        private const val TAG = "LocalStreamProxy"
        private const val BUFFER_SIZE = 32 * 1024
        private const val PORT_RANGE_START = 40000
        private const val PORT_RANGE_END   = 49999
        private const val MAX_WORKER_THREADS = 8
        private const val UPSTREAM_CONNECT_TIMEOUT_S = 15L
        private const val UPSTREAM_READ_TIMEOUT_S    = 120L
    }
}

package org.jellyfin.mobile.player.cast.proxy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CastProxyManager
 *
 * High-level coordinator. Call [rewriteUrlForCast] with any Jellyfin media URL
 * and it will:
 *  1. Ensure the foreground proxy service is running
 *  2. Return the URL rewritten to go through the local proxy
 *
 * Call [teardown] when the Cast session ends.
 */
class CastProxyManager(
    private val context: Context,
    private val jellyfinBaseUrl: String,
    private val apiToken: String,
    private val proxyEnabled: Boolean = true,
) {
    companion object {
        private const val TAG = "CastProxyManager"
    }

    private var serviceBinder: ProxyForegroundService.ProxyBinder? = null
    private var serviceConnection: ServiceConnection? = null

    /**
     * Rewrites a Jellyfin media URL to go through the local proxy.
     * If proxy is disabled the original URL is returned unchanged.
     *
     * Suspends on the first call while the service binds; subsequent calls
     * return immediately using the already-bound binder.
     */
    suspend fun rewriteUrlForCast(originalUrl: String): String {
        if (!proxyEnabled) {
            Timber.tag(TAG).d("Proxy disabled, returning original URL")
            return originalUrl
        }

        val proxyBase = ensureProxyRunning()
        val rewritten = rewriteUrl(originalUrl, proxyBase)
        Timber.tag(TAG).d("Rewrote $originalUrl → $rewritten")
        return rewritten
    }

    /** Stop the proxy service and unbind. Call when the Cast session ends. */
    fun teardown() {
        serviceConnection?.let {
            try { context.unbindService(it) } catch (_: Exception) {}
        }
        serviceConnection = null
        serviceBinder = null
        ProxyForegroundService.stop(context)
        Timber.tag(TAG).i("Proxy torn down")
    }

    // -------------------------------------------------------------------------

    private suspend fun ensureProxyRunning(): String {
        // Already bound — return cached URL via the explicit getter (property is private)
        val cached = serviceBinder?.getProxyBaseUrl()
        if (cached != null) return cached

        return suspendCancellableCoroutine { cont ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val pb = binder as ProxyForegroundService.ProxyBinder
                    serviceBinder = pb
                    // Use getProxyBaseUrl() — proxyBaseUrl field is private inside ProxyBinder
                    val url = pb.getProxyBaseUrl()
                    if (url != null) {
                        cont.resume(url)
                    } else {
                        cont.resumeWithException(
                            IllegalStateException("Proxy bound but URL not available — service may have failed to start"),
                        )
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    serviceBinder = null
                    Timber.tag(TAG).w("Proxy service disconnected unexpectedly")
                }
            }
            serviceConnection = connection

            ProxyForegroundService.start(context, jellyfinBaseUrl)
            context.bindService(
                Intent(context, ProxyForegroundService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )

            cont.invokeOnCancellation { teardown() }
        }
    }

    /**
     * Replaces scheme + host + port of [originalUrl] with [proxyBase],
     * keeping path and query string intact.
     *
     * e.g.
     *   originalUrl = "https://jellyfin.tail1234.ts.net/Videos/abc123/stream.mp4?..."
     *   proxyBase   = "http://192.168.1.5:43210"
     *   result      = "http://192.168.1.5:43210/Videos/abc123/stream.mp4?..."
     */
    private fun rewriteUrl(originalUrl: String, proxyBase: String): String {
        return try {
            val uri      = Uri.parse(originalUrl)
            val proxyUri = Uri.parse(proxyBase)
            uri.buildUpon()
                .scheme(proxyUri.scheme)
                .authority(proxyUri.authority)
                .build()
                .toString()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to rewrite URL: $originalUrl")
            originalUrl  // fall back to original on any parse error
        }
    }
}

package org.jellyfin.mobile.player.cast.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.jellyfin.mobile.R
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ProxyForegroundService
 *
 * Keeps LocalStreamProxy alive while casting. A foreground service prevents
 * Android from killing the proxy mid-stream (required on API 26+).
 *
 * Start:  ProxyForegroundService.start(context, jellyfinBaseUrl)
 * Stop:   ProxyForegroundService.stop(context)
 * URL:    bind and call binder.getProxyBaseUrl()
 */
class ProxyForegroundService : Service() {

    companion object {
        private const val TAG             = "ProxyForegroundSvc"
        private const val CHANNEL_ID      = "jellyfin_cast_proxy"
        private const val NOTIFICATION_ID = 9001
        private const val ACTION_STOP     = "org.jellyfin.mobile.player.cast.proxy.STOP"

        const val EXTRA_BASE_URL  = "jellyfin_base_url"

        @JvmStatic
        fun start(context: Context, jellyfinBaseUrl: String) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                putExtra(EXTRA_BASE_URL, jellyfinBaseUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyForegroundService::class.java))
        }
    }

    class ProxyBinder : Binder() {
        private var proxyBaseUrl: String? = null

        // Explicit getter — avoids the private-property visibility error that
        // occurred when CastProxyManager accessed this as pb.proxyBaseUrl
        fun getProxyBaseUrl(): String? = proxyBaseUrl

        internal fun setProxyBaseUrl(url: String?) {
            proxyBaseUrl = url
        }
    }

    private val binder = ProxyBinder()

    // Separate backing field lets us capture a local val before nulling it,
    // which fixes "smart cast impossible on mutable property that could be mutated concurrently"
    private var proxyInstance: LocalStreamProxy? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting relay…"))

        val baseUrl = intent?.getStringExtra(EXTRA_BASE_URL)

        if (baseUrl.isNullOrBlank()) {
            Timber.tag(TAG).e("Missing base URL — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        startProxy(baseUrl)
        return START_STICKY
    }

    override fun onDestroy() {
        // Capture into a local val before nulling — satisfies the smart cast / concurrency lint
        val p = proxyInstance
        proxyInstance = null
        p?.stop()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------

    private fun startProxy(baseUrl: String) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val p = LocalStreamProxy(
            context         = applicationContext,
            upstreamClient  = client,
            jellyfinBaseUrl = baseUrl,
        )
        try {
            val url = p.start()
            binder.setProxyBaseUrl(url)
            proxyInstance = p
            updateNotification("Relay active · $url")
            Timber.tag(TAG).i("Proxy started at $url → $baseUrl")
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Failed to start proxy")
            updateNotification("Relay failed to start")
            stopSelf()
        }
    }

    // -------------------------------------------------------------------------
    // Notification helpers — API 21 compatible
    // -------------------------------------------------------------------------

    // getSystemService(Class) requires API 23; use the string constant for API 21 compat
    private fun getNotificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cast stream relay",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Active while relaying media to Chromecast" }
            getNotificationManager().createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, ProxyForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // TODO: replace the icon references below with real drawable resource names from your app.
        // Search your res/drawable folder for an existing notification icon and stop icon, e.g.:
        //   R.drawable.ic_notification   or   R.drawable.ic_logo
        //   R.drawable.ic_stop           or   R.drawable.ic_close
        // Using androidx.core built-ins as a safe placeholder that always resolves.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jellyfin Cast relay")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_subtitles_stateful_24dp,
                "Stop",
                stopPi,
            )
            .build()
    }

    private fun updateNotification(text: String) {
        getNotificationManager().notify(NOTIFICATION_ID, buildNotification(text))
    }
}

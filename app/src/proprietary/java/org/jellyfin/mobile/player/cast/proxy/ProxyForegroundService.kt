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
import okhttp3.OkHttpClient
import org.jellyfin.mobile.R
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Keeps [LocalStreamProxy] alive for the duration of a Cast session.
 *
 * Bind to it and read [ProxyBinder.proxyBaseUrl]; a null value once
 * [ProxyBinder.isResolved] is true means startup failed and the caller should surface
 * an error rather than wait. The old version had no way to distinguish "not ready yet"
 * from "never going to be ready", which turned every failure into a silent hang.
 */
class ProxyForegroundService : Service() {

    class ProxyBinder : Binder() {
        fun interface Listener {
            fun onProxyResolved(proxyBaseUrl: String?, failureReason: String?)
        }

        @Volatile
        var proxyBaseUrl: String? = null
            private set

        @Volatile
        var isResolved: Boolean = false
            private set

        @Volatile
        var failureReason: String? = null
            private set

        private var listener: Listener? = null

        /** Registers a listener. Fires immediately if resolution already happened. */
        fun setListener(newListener: Listener?) {
            listener = newListener
            if (isResolved) newListener?.onProxyResolved(proxyBaseUrl, failureReason)
        }

        internal fun publish(url: String?, reason: String?) {
            proxyBaseUrl = url
            failureReason = reason
            isResolved = true
            listener?.onProxyResolved(url, reason)
        }
    }

    private val binder = ProxyBinder()
    private var proxyInstance: LocalStreamProxy? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.cast_relay_starting)))

        val baseUrl = intent?.getStringExtra(EXTRA_BASE_URL)
        if (baseUrl.isNullOrBlank()) {
            fail("Missing Jellyfin base URL")
            return START_NOT_STICKY
        }

        val castDeviceAddress = intent.getStringExtra(EXTRA_CAST_DEVICE_ADDRESS)?.let { raw ->
            try {
                InetAddress.getByName(raw)
            } catch (_: UnknownHostException) {
                null
            }
        }

        startProxy(baseUrl, castDeviceAddress)

        // NOT START_STICKY: a restarted service would have no intent, no base URL and no
        // Cast session to serve, and would sit in the foreground doing nothing.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val proxy = proxyInstance
        proxyInstance = null
        proxy?.stop()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------

    private fun startProxy(baseUrl: String, castDeviceAddress: InetAddress?) {
        val bindAddress = LanAddressResolver(applicationContext).resolve(castDeviceAddress)
        if (bindAddress == null) {
            val available = LanAddressResolver(applicationContext).candidates()
            Timber.tag(TAG).e("No usable LAN address. Candidates were: %s", available)
            fail(
                if (castDeviceAddress != null) {
                    "Phone and Chromecast are not on the same network"
                } else {
                    "No Wi-Fi connection available for the relay"
                },
            )
            return
        }

        val client = OkHttpClient.Builder().build()
        val proxy = LocalStreamProxy(
            upstreamClient = client,
            jellyfinBaseUrl = baseUrl,
            bindAddress = bindAddress,
            allowedClient = castDeviceAddress,
        )

        try {
            val url = proxy.start()
            proxyInstance = proxy
            binder.publish(url, null)
            updateNotification(getString(R.string.cast_relay_active, bindAddress.hostAddress))
            Timber.tag(TAG).i("Relay started, forwarding to %s", baseUrl)
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Failed to start relay")
            fail(e.message ?: "Relay failed to start")
        }
    }

    private fun fail(reason: String) {
        updateNotification(getString(R.string.cast_relay_failed))
        binder.publish(null, reason)
        // no stopSelf here — it would destroy the instance before the client binds.
        // ChromecastConnection.stopLocalProxy() tears the service down.
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun getNotificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.cast_relay_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.cast_relay_channel_description)
        }
        getNotificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ProxyForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.cast_relay_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop_black_32dp, getString(R.string.cast_relay_stop), stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getNotificationManager().notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "ProxyForegroundSvc"
        private const val CHANNEL_ID = "jellyfin_cast_proxy"
        private const val NOTIFICATION_ID = 9001
        private const val ACTION_STOP = "org.jellyfin.mobile.player.cast.proxy.STOP"

        const val EXTRA_BASE_URL = "jellyfin_base_url"
        const val EXTRA_CAST_DEVICE_ADDRESS = "cast_device_address"

        @JvmStatic
        fun start(context: Context, jellyfinBaseUrl: String, castDeviceAddress: String?) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                putExtra(EXTRA_BASE_URL, jellyfinBaseUrl)
                putExtra(EXTRA_CAST_DEVICE_ADDRESS, castDeviceAddress)
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
}

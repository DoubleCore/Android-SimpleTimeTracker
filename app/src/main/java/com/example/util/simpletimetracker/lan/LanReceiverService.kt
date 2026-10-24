package com.example.util.simpletimetracker.lan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.util.simpletimetracker.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import org.doublecore.stt.cloud.LanAuth
import org.doublecore.stt.cloud.LanConnection
import org.doublecore.stt.cloud.LanHttpServer
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.Collections
import javax.inject.Inject

/**
 * User-visible foreground service that keeps the phone reachable for the watch over Wi-Fi.
 * Started and stopped explicitly from the TimeTrak settings page; remembered across reboots.
 */
@AndroidEntryPoint
class LanReceiverService : Service() {

    @Inject lateinit var applier: LanOperationApplier

    @Inject lateinit var snapshots: LanSnapshotBuilder

    private var server: LanHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (server?.isOpen() != true) {
            server?.close()
            server = try {
                LanHttpServer(LanConnection.PORT, { secret(this) }, ::route).also {
                    lastError = ""
                }
            } catch (e: Exception) {
                lastError = e.message ?: "端口 ${LanConnection.PORT} 不可用"
                null
            }
        }
        running = server != null
        if (server == null) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        server?.close()
        server = null
        running = false
        super.onDestroy()
    }

    private fun route(method: String, target: String, body: ByteArray, peer: String): LanHttpServer.Response = runBlocking {
        lastPeer = peer
        lastContactAt = System.currentTimeMillis()
        when {
            method == "GET" && target.startsWith("/v2/state") -> {
                val known = target.substringAfter("known=", "").substringBefore('&')
                val snapshot = snapshots.build()
                val json = if (known.isNotEmpty() && known == snapshot.rev) {
                    JSONObject().put("protocol", LanProtocol.VERSION).put("rev", snapshot.rev).put("unchanged", true)
                } else {
                    snapshot.json
                }
                LanHttpServer.Response.json(200, json.toString())
            }
            method == "POST" && target == "/v2/ops" -> {
                val ops = try {
                    LanProtocol.parseOps(String(body, StandardCharsets.UTF_8))
                } catch (e: Exception) {
                    return@runBlocking LanHttpServer.Response.json(400, """{"protocol":2,"error":"invalid_ops"}""")
                }
                val results = applier.apply(ops)
                val rev = snapshots.build().rev
                LanHttpServer.Response.json(200, LanProtocol.encodeResults(results, rev).toString())
            }
            else -> LanHttpServer.Response.json(404, """{"protocol":2,"error":"unknown_route"}""")
        }
    }

    private fun startInForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "手表 Wi-Fi 直连", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, LanHostActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LanReceiverService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("手表 Wi-Fi 直连已开启")
            .setContentText(addresses().joinToString { "$it:${LanConnection.PORT}" }.ifEmpty { "等待 Wi-Fi / 热点" })
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "TIMETRAK_LAN"
        private const val NOTIFICATION_ID = 52070
        private const val ACTION_STOP = "com.example.util.simpletimetracker.lan.STOP"
        private const val PREFS = "stt_lan_host_v1"

        @Volatile var running: Boolean = false
            private set

        @Volatile var lastError: String = ""
            private set

        @Volatile var lastPeer: String = ""
            private set

        @Volatile var lastContactAt: Long = 0L
            private set

        fun start(context: Context) {
            setEnabled(context, true)
            ContextCompat.startForegroundService(context, Intent(context, LanReceiverService::class.java))
        }

        fun stop(context: Context) {
            setEnabled(context, false)
            context.stopService(Intent(context, LanReceiverService::class.java))
        }

        fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

        /** Restores the receiver after process start if the user left it enabled. */
        fun restoreIfEnabled(context: Context) {
            if (!isEnabled(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, LanReceiverService::class.java))
            } catch (_: Exception) {
                // Background start not allowed right now; opening the app or the status page restarts it.
            }
        }

        fun secret(context: Context): String {
            val prefs = prefs(context)
            val saved = prefs.getString("secret", null)
            if (LanAuth.isValidSecret(saved)) return saved!!
            val created = LanAuth.newSecret()
            prefs.edit().putString("secret", created).apply()
            return created
        }

        fun resetSecret(context: Context): String {
            val created = LanAuth.newSecret()
            prefs(context).edit().putString("secret", created).apply()
            return created
        }

        fun addresses(): List<String> = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .filter { runCatching { LanConnection.checkIp(it) }.isSuccess }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }

        private fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean("enabled", enabled).apply()
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}

package com.hhvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.content.pm.ServiceInfo
import org.json.JSONObject
import libXray.DialerController
import libXray.LibXray
import java.io.IOException
import java.util.concurrent.Executors

class HHVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    private var tunnel: ParcelFileDescriptor? = null
    private var coreStarted = false
    private var dnsConfigured = false

    private val socketController = object : DialerController {
        override fun protectFd(fd: Long): Boolean = protect(fd.toInt())
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VPN connection", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                setForeground("Connecting")
                worker.execute { startTunnel(config) }
            }
            ACTION_STOP -> worker.execute {
                setForeground("Disconnecting")
                publishState("Disconnecting")
                val detail = stopTunnel()
                publishState("Disconnected", detail.orEmpty())
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        val detail = stopTunnel()
        publishState("Disconnected", detail.orEmpty())
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onRevoke()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        stopTunnel()?.let { publishState("Disconnected", it) }
        super.onDestroy()
    }

    private fun startTunnel(configText: String) {
        publishState("Connecting")
        try {
            val builder = Builder()
                .setSession("hh vpn")
                .setMtu(1500)
                .addAddress("10.111.222.1", 30)
                .addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
            tunnel = builder.establish() ?: throw IOException("Android could not establish the VPN interface.")

            LibXray.registerDialerController(socketController)
            LibXray.setDNS(socketController, "1.1.1.1:53")
            dnsConfigured = true
            val config = JSONObject(configText)
                .put("env", JSONObject().put("xray.tun.fd", tunnel!!.fd))
            val request = JSONObject()
                .put("apiVersion", 3)
                .put("method", "runXray")
                .put("payload", JSONObject().put("xrayJson", config.toString()))
            val response = JSONObject(LibXray.invoke(request.toString()))
            if (!response.optBoolean("success")) {
                throw IOException(response.optString("error", "Xray could not start."))
            }
            coreStarted = true
            setForeground("Connected")
            publishState("Connected")
        } catch (error: Exception) {
            val cleanupError = stopTunnel()
            val message = listOfNotNull(
                error.message?.take(180) ?: "Could not start the VPN.",
                cleanupError,
            ).joinToString(" · ").take(240)
            publishState("Disconnected", message)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @Synchronized
    private fun stopTunnel(): String? {
        val errors = mutableListOf<String>()
        if (coreStarted) {
            try {
                val stop = JSONObject()
                    .put("apiVersion", 3)
                    .put("method", "stopXray")
                    .put("payload", JSONObject())
                val response = JSONObject(LibXray.invoke(stop.toString()))
                if (!response.optBoolean("success")) {
                    throw IOException(response.optString("error", "Xray could not stop cleanly."))
                }
            } catch (error: Exception) {
                errors += "Xray shutdown failed: ${error.message ?: "unknown error"}"
            }
            coreStarted = false
        }
        if (dnsConfigured) {
            try {
                LibXray.resetDNS()
                dnsConfigured = false
            } catch (error: RuntimeException) {
                errors += "DNS reset failed: ${error.message ?: "unknown error"}"
            }
        }
        try {
            tunnel?.close()
        } catch (error: IOException) {
            errors += "VPN interface close failed: ${error.message ?: "unknown error"}"
        }
        tunnel = null
        return if (errors.isEmpty()) null else errors.joinToString(" · ")
    }

    private fun setForeground(state: String) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopService = PendingIntent.getService(
            this,
            1,
            Intent(this, HHVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("hh vpn")
                .setContentText(state)
                .setContentIntent(openApp)
                .setOngoing(state == "Connected" || state == "Connecting")
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopService)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("hh vpn")
                .setContentText(state)
                .setContentIntent(openApp)
                .setOngoing(state == "Connected" || state == "Connecting")
                .build()
        }
    }

    private fun publishState(state: String, detail: String = "") {
        currentState = state
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_DETAIL, detail),
        )
    }

    companion object {
        const val ACTION_STATE = "com.hhvpn.app.VPN_STATE"
        const val ACTION_START = "com.hhvpn.app.START_VPN"
        const val ACTION_STOP = "com.hhvpn.app.STOP_VPN"
        const val EXTRA_CONFIG = "xray_config"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"

        @Volatile
        var currentState = "Disconnected"
            private set

        private const val CHANNEL_ID = "hhvpn_connection"
        private const val NOTIFICATION_ID = 101
    }
}

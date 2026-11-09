package com.telemedia.phone

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import java.io.File

/**
 * Foreground service that hosts Node (the addon). Runs in the ":node" process
 * (see AndroidManifest) because node::Start() can only run once per
 * process: restarting Node means killing this process.
 *
 * While a video streams (to Stremio here or on another device), it holds a
 * partial wake lock and a Wi-Fi lock, so serving goes on with the screen off.
 */
class NodeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // "Stop" in the notification.
        if (intent?.action == ACTION_STOP) {
            AppConfig.setStopped(this, true)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!started) {
            started = true
            // Node's main thread wants a bigger stack than the Java default.
            Thread(null, ::runNode, "node-main", 8L * 1024 * 1024).start()
            Thread(::watchStreams, "stream-watch").apply { isDaemon = true }.start()
        }
        return START_STICKY
    }

    private fun runNode() {
        try {
            val project = NodeProject.ensureExtracted(this)
            val code = NodeBridge.startNodeWithArguments(
                arrayOf(
                    "node",
                    File(project, "main.js").absolutePath,
                    AppConfig.file(this).absolutePath,
                ),
            )
            Log.w(TAG, "node exited with code $code")
        } catch (t: Throwable) {
            Log.e(TAG, "node failed to start", t)
        }
        // Node can't be restarted in-process; let the system/UI restart us.
        Process.killProcess(Process.myPid())
    }

    // Stopped (Stop Telemedia): Node can't stop in-process, so end the ":node"
    // process. The service was stopped, not killed, so nothing restarts it.
    override fun onDestroy() {
        try {
            setAwake(false)
        } catch (e: Exception) {
            Log.w(TAG, "release locks", e)
        }
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    // Polls the bootstrap for active video transfers (lan.js gateway).
    private fun watchStreams() {
        while (true) {
            val active = ControlClient.status()?.optInt("activeStreams") ?: 0
            try {
                setAwake(active > 0)
            } catch (e: Exception) {
                Log.w(TAG, "wake lock", e)
            }
            Thread.sleep(5000)
        }
    }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF: still the one that works with the screen off
    private fun setAwake(on: Boolean) {
        if (on && wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Telemedia:stream")
                .apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Telemedia:stream")
                ?.apply { setReferenceCounted(false); acquire() }
            Log.i(TAG, "streaming: holding wake and Wi-Fi locks")
        } else if (!on && wakeLock?.isHeld == true) {
            wakeLock?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
            Log.i(TAG, "no streams: released locks")
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NodeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "NodeService"
        private const val CHANNEL = "addon"
        private const val NOTIFICATION_ID = 1
        const val CONTROL_PORT = 7001
        private const val ACTION_STOP = "com.telemedia.phone.STOP"

        // Per-process flag; only meaningful inside the ":node" process.
        @Volatile
        private var started = false

        fun start(ctx: Context) {
            val intent = Intent(ctx, NodeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /** "Stop Telemedia": stops the addon until the user starts it again. */
        fun stop(ctx: Context) {
            AppConfig.setStopped(ctx, true)
            ctx.stopService(Intent(ctx, NodeService::class.java))
        }

        /** Kill the ":node" process (if any) and start a fresh one. Call off the main thread. */
        fun restart(ctx: Context) {
            AppConfig.setStopped(ctx, false)
            ControlClient.post("/exit", "{}")
            Thread.sleep(800)
            val am = ctx.getSystemService(ActivityManager::class.java)
            am.runningAppProcesses
                ?.firstOrNull { it.processName == "${ctx.packageName}:node" }
                ?.let { Process.killProcess(it.pid) }
            Thread.sleep(700)
            start(ctx)
        }
    }
}

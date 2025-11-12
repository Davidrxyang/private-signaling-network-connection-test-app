package com.example.private_signaling_network_connection_test_app

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class NetworkTestService : Service() {

    companion object {
        private const val TAG = "NetworkTestService"
        private const val CHANNEL_ID = "net_test_channel"
        private const val NOTIF_ID = 42

        // Defaults used by both TCP and UDP clients
        private const val CONNECT_TIMEOUT_MS = 8000       // TCP only
        private const val READ_TIMEOUT_MS = 1500
        private const val PERIOD_MS = 1000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    // Locks to keep device radios/CPU awake during tests
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notif = baseNotification("Idle")
        startForeground(NOTIF_ID, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra("host") ?: return START_NOT_STICKY
        val port = intent.getIntExtra("port", 8000)
        val mode = (intent.getStringExtra("mode") ?: "tcp").lowercase()
        val udpDirectionStr = (intent.getStringExtra("udpDirection") ?: "both").lowercase()
        val udpDirection = when (udpDirectionStr) {
            "cts" -> UdpDirection.CLIENT_TO_SERVER
            "stc" -> UdpDirection.SERVER_TO_CLIENT
            else -> UdpDirection.BOTH
        }

        val periodSec = intent.getLongExtra("periodSec", PERIOD_MS / 1000L)
        val resetEverySec = intent.getLongExtra("resetEverySec", 0L)
        val resetDowntimeSec = intent.getLongExtra("resetDowntimeSec", 0L)
        val useWakeLock = intent.getBooleanExtra("useWakeLock", true)
        val useWifiLock = intent.getBooleanExtra("useWifiLock", true)
        val periodMs = max(100L, periodSec * 1000L)

        if (running.compareAndSet(false, true)) {
            // Acquire locks before we start network loops
            if (useWakeLock) acquireWakeLock()
            if (useWifiLock) acquireWifiLock()

            serviceScope.launch {
                updateNotification(
                    "Starting $mode • $host:$port • every ${periodMs}ms • reset=${resetEverySec}s/↓${resetDowntimeSec}s"
                )
                try {
                    when (mode) {
                        "udp" -> {
                            Log.i(TAG, "Mode=UDP; host=$host port=$port dir=$udpDirection")
                            runUdpClient(
                                host = host,
                                port = port,
                                periodMs = periodMs,
                                readTimeoutMs = READ_TIMEOUT_MS,
                                direction = udpDirection,
                                resetEverySec = resetEverySec,
                                resetDowntimeSec = resetDowntimeSec
                            )
                        }
                        else -> {
                            Log.i(TAG, "Mode=TCP; host=$host port=$port")
                            runTcpClient(
                                host = host,
                                port = port,
                                periodMs = periodMs,
                                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                                readTimeoutMs = READ_TIMEOUT_MS,
                                resetEverySec = resetEverySec,
                                resetDowntimeSec = resetDowntimeSec
                            )
                        }
                    }
                } catch (ce: CancellationException) {
                    // normal on stop
                } catch (t: Throwable) {
                    Log.e(TAG, "Client loop error: ${t.message}", t)
                    updateNotification("Error: ${t.message ?: "network"}")
                } finally {
                    releaseWifiLock()
                    releaseWakeLock()
                    running.set(false)
                    stopSelf()
                }
            }
        } else {
            Log.i(TAG, "Service already running; ignoring duplicate start")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        running.set(false)
        serviceScope.cancel()
        releaseWifiLock()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun baseNotification(sub: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Network Test")
            .setContentText(sub)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sub))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(sub: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, baseNotification(sub))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Network Test",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status of the TCP/UDP test connection"
            }
            nm.createNotificationChannel(ch)
        }
    }

    // --- Locks ---
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (wakeLock?.isHeld == true) return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetTest:Partial").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Partial WakeLock ACQUIRED")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to acquire WakeLock: ${t.message}", t)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (wl.isHeld) wl.release()
            }
            wakeLock = null
            Log.i(TAG, "Partial WakeLock RELEASED")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to release WakeLock: ${t.message}", t)
        }
    }

    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            if (wifiLock?.isHeld == true) return
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "NetTest:WifiHighPerf"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Wi‑Fi High Perf Lock ACQUIRED")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to acquire Wi‑Fi lock: ${t.message}", t)
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let { wl ->
                if (wl.isHeld) wl.release()
            }
            wifiLock = null
            Log.i(TAG, "Wi‑Fi High Perf Lock RELEASED")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to release Wi‑Fi lock: ${t.message}", t)
        }
    }
}

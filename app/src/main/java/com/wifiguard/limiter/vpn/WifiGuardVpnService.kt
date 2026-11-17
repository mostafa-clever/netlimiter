package com.wifiguard.limiter.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.wifiguard.limiter.MainActivity
import com.wifiguard.limiter.admin.WifiGuardDeviceAdminReceiver
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.formatDataSizeArabic
import com.wifiguard.limiter.network.LocalLanClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.nio.ByteBuffer

class WifiGuardVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: PreferencesManager
    private var tunInterface: ParcelFileDescriptor? = null
    private var drainJob: Job? = null

    private var currentBlockAll: Boolean = false
    private var currentWhitelist: Set<String> = emptySet()
    private var lastTrafficBytes: Long = -1L

    companion object {
        const val CHANNEL_ID = "wifiguard_vpn_channel"
        const val NOTIF_ID = 2001
        const val ACTION_STOP = "com.wifiguard.limiter.STOP_VPN"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _connectionState = MutableStateFlow(ConnectionState.NORMAL)
        val connectionState: StateFlow<ConnectionState> = _connectionState

        fun start(context: Context) {
            val intent = Intent(context, WifiGuardVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WifiGuardVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(applicationContext)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("جاري تشغيل حماية الاتصال...", ConnectionState.NORMAL))
        _isRunning.value = true
        establishTunnel(blockAll = false, whitelist = emptySet())
        startMonitoringLoop()
        startHeartbeatLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        _isRunning.value = false
        _connectionState.value = ConnectionState.PROTECTION_OFF
        serviceScope.launch {
            val role = prefs.roleFlow.first()
            if (role == AppRole.CLIENT) {
                val host = prefs.getSupervisorHost()
                val port = prefs.getSupervisorPort()
                val id = prefs.getOrCreateDeviceId()
                val name = prefs.getDeviceName()
                LocalLanClient.sendTamperAlert(
                    host,
                    port,
                    id,
                    name,
                    "تم إيقاف حماية الاتصال على جهاز $name"
                )
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        drainJob?.cancel()
        runCatching { tunInterface?.close() }
        tunInterface = null
        serviceScope.cancel()
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            var slowdownPulseCounter = 0
            while (isActive) {
                if (isOnWifi()) {
                    val currentTotal = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
                    if (lastTrafficBytes > 0L && currentTotal > lastTrafficBytes) {
                        val delta = currentTotal - lastTrafficBytes
                        // Sanity cap to avoid counter reset spikes
                        if (delta < 100L * 1024L * 1024L) {
                            prefs.addUsedBytesToday(delta)
                        }
                    }
                    lastTrafficBytes = currentTotal
                } else {
                    lastTrafficBytes = -1L
                }

                val (limitMb, slowdownThreshold, whitelist) = prefs.getLocalLimitConfig()
                val usedMb = prefs.getUsedMbToday()
                val percent = if (limitMb <= 0L) 100 else ((usedMb * 100) / limitMb).toInt()

                val targetState = when {
                    percent >= 100 -> ConnectionState.BLOCKED
                    percent >= slowdownThreshold -> ConnectionState.SLOWED
                    else -> ConnectionState.NORMAL
                }
                _connectionState.value = targetState

                val shouldBlockTunnel = when (targetState) {
                    ConnectionState.BLOCKED -> true
                    ConnectionState.SLOWED -> {
                        // Gentle throttling duty-cycle near limit: pause general traffic 2s out of every 4s
                        slowdownPulseCounter = (slowdownPulseCounter + 1) % 2
                        slowdownPulseCounter == 1
                    }
                    else -> false
                }

                if (shouldBlockTunnel != currentBlockAll || whitelist != currentWhitelist) {
                    establishTunnel(blockAll = shouldBlockTunnel, whitelist = whitelist)
                }

                val summary = "${formatDataSizeArabic(usedMb)} من ${formatDataSizeArabic(limitMb)}"
                updateNotification(summary, targetState)

                delay(2000L)
            }
        }
    }

    private fun startHeartbeatLoop() {
        serviceScope.launch {
            while (isActive) {
                val role = prefs.roleFlow.first()
                if (role == AppRole.CLIENT) {
                    val host = prefs.getSupervisorHost()
                    val port = prefs.getSupervisorPort()
                    if (host.isNotBlank()) {
                        val id = prefs.getOrCreateDeviceId()
                        val name = prefs.getDeviceName()
                        val usedMb = prefs.getUsedMbToday()
                        val adminActive = WifiGuardDeviceAdminReceiver.isActive(applicationContext)
                        val updated = LocalLanClient.sendHeartbeat(
                            host = host,
                            port = port,
                            deviceId = id,
                            deviceName = name,
                            usedMb = usedMb,
                            protectionActive = _isRunning.value,
                            uninstallProtectionActive = adminActive,
                            connectionState = _connectionState.value
                        )
                        if (updated != null) {
                            prefs.updateLocalLimits(
                                dailyLimitMb = updated.dailyLimitMb,
                                slowdownThresholdPercent = updated.slowdownThresholdPercent,
                                whitelist = updated.whitelistedPackages
                            )
                        }
                    }
                }
                delay(15_000L)
            }
        }
    }

    private fun establishTunnel(blockAll: Boolean, whitelist: Set<String>) {
        drainJob?.cancel()
        runCatching { tunInterface?.close() }
        currentBlockAll = blockAll
        currentWhitelist = whitelist

        val builder = Builder()
            .setSession("حارس الواي فاي")
            .addAddress("10.0.0.2", 32)
            .setMtu(1500)

        if (blockAll) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        } else {
            // Keep Always-On VPN connected with zero speed impact when under limit
            builder.addRoute("10.254.0.0", 16)
        }

        // Always exclude our own app so local Supervisor sync never drops
        runCatching { builder.addDisallowedApplication(packageName) }

        // Exclude whitelisted apps so they always keep Wi-Fi access
        whitelist.forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }
        }

        tunInterface = runCatching { builder.establish() }.getOrNull()

        val pfd = tunInterface
        if (pfd != null && blockAll) {
            drainJob = serviceScope.launch {
                val input = FileInputStream(pfd.fileDescriptor)
                val buffer = ByteBuffer.allocate(32767)
                while (isActive) {
                    val len = runCatching { input.read(buffer.array()) }.getOrElse { -1 }
                    if (len < 0) break
                    buffer.clear()
                }
            }
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "حماية الاتصال اليومية",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(text: String, state: ConnectionState): Notification {
        val title = when (state) {
            ConnectionState.NORMAL -> "حارس الواي فاي — يعمل بشكل طبيعي"
            ConnectionState.SLOWED -> "تم تهدئة السرعة — اقتربت من الحد اليومي"
            ConnectionState.BLOCKED -> "انتهى الرصيد اليومي — التطبيقات المستثناة فقط تعمل"
            ConnectionState.PROTECTION_OFF -> "الحماية متوقفة"
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String, state: ConnectionState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text, state))
    }
}

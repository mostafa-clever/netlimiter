package com.wifiguard.limiter.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wifiguard.limiter.MainActivity
import com.wifiguard.limiter.admin.WifiGuardDeviceAdminReceiver
import com.wifiguard.limiter.data.AppUsageStatsHelper
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.LimitPeriod
import com.wifiguard.limiter.model.SpeedPolicyConfig
import com.wifiguard.limiter.model.getInstalledLaunchableApps
import com.wifiguard.limiter.model.optCleanString
import com.wifiguard.limiter.model.sanitizeFinite
import com.wifiguard.limiter.vpn.WifiGuardVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

class ClientGuardService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private lateinit var prefs: PreferencesManager
    @Volatile private var controlSocket: ServerSocket? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var screenOnReceiver: android.content.BroadcastReceiver? = null
    private var wasVpnRunning: Boolean = false
    private var lastAppsSentMs: Long = 0L
    private var lastScreenOnSyncMs: Long = 0L
    private var lastNsdScanMs: Long = 0L
    private var lastSyncedConfig: SyncedLimitConfig? = null
    private val wakeSyncChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    companion object {
        const val CHANNEL_ID = "wifiguard_client_guard_channel"
        const val NOTIF_ID = 2002
        private const val MAX_HEADER_LINE_BYTES = 8192
        private const val MAX_HEADERS = 64
        private const val MAX_BODY_BYTES = 512 * 1024

        @Volatile var isSupervisorReachable: Boolean = false

        private val _isSupervisorReachableFlow = MutableStateFlow(false)
        val isSupervisorReachableFlow: StateFlow<Boolean> = _isSupervisorReachableFlow

        private val _isUnpairedFlow = MutableStateFlow(false)
        val isUnpairedFlow: StateFlow<Boolean> = _isUnpairedFlow

        private val _lastSyncMillisFlow = MutableStateFlow(0L)
        val lastSyncMillisFlow: StateFlow<Long> = _lastSyncMillisFlow

        fun start(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, ClientGuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.applicationContext.stopService(Intent(context.applicationContext, ClientGuardService::class.java)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(applicationContext)
        createNotificationChannel()
        val notif = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }
        startControlServer()
        registerWifiReconnectCallback()
        runCatching {
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val now = System.currentTimeMillis()
                    if (now - lastScreenOnSyncMs >= 60_000L) {
                        lastScreenOnSyncMs = now
                        wakeSyncChannel.trySend(Unit)
                    }
                }
            }
            screenOnReceiver = rx
            ContextCompat.registerReceiver(
                this,
                rx,
                IntentFilter(Intent.ACTION_SCREEN_ON),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        startPersistentHeartbeatAndVpnWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isSupervisorReachable = false
        _isSupervisorReachableFlow.value = false
        runCatching {
            wifiCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(it)
            }
        }
        wifiCallback = null
        runCatching { screenOnReceiver?.let { unregisterReceiver(it) } }
        screenOnReceiver = null
        runCatching { controlSocket?.close() }
        controlSocket = null
        serviceScope.cancel()
    }

    private fun startControlServer() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val server = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(LocalLanClient.CLIENT_PORT))
                    }
                    controlSocket = server
                    try {
                        while (isActive && !server.isClosed) {
                            val sock = runCatching { server.accept() }.getOrNull() ?: break
                            launch { handleSupervisorCommand(sock) }
                        }
                    } finally {
                        runCatching { server.close() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    delay(5_000L)
                }
            }
        }
    }

    private fun isTokenAuthorized(expectedToken: String, authHeader: String?): Boolean {
        if (expectedToken.isBlank()) return true
        if (authHeader.isNullOrBlank()) return false
        val provided = authHeader.removePrefix("Bearer ").trim()
        return MessageDigest.isEqual(
            expectedToken.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8)
        )
    }

    private suspend fun handleSupervisorCommand(sock: Socket) {
        sock.use { s ->
            runCatching {
                s.soTimeout = 5000
                val rawIn = s.getInputStream()
                fun readLine(): String? {
                    val lb = ByteArrayOutputStream()
                    while (lb.size() < MAX_HEADER_LINE_BYTES) {
                        val b = rawIn.read()
                        if (b == -1) return if (lb.size() == 0) null else lb.toString("UTF-8")
                        if (b == '\n'.code) return lb.toString("UTF-8")
                        if (b != '\r'.code) lb.write(b)
                    }
                    return null
                }

                val reqLine = readLine() ?: return
                val parts = reqLine.split(" ")
                val path = parts.getOrNull(1) ?: "/"
                var contentLen = 0
                var authHeader: String? = null
                var headerCount = 0
                while (headerCount < MAX_HEADERS) {
                    val h = readLine() ?: break
                    if (h.isEmpty()) break
                    headerCount++
                    val lower = h.lowercase()
                    when {
                        lower.startsWith("content-length:") -> {
                            contentLen = h.substringAfter(":").trim().toIntOrNull() ?: -1
                        }
                        lower.startsWith("authorization:") -> {
                            authHeader = h.substringAfter(":").trim()
                        }
                    }
                }

                if (contentLen < 0 || contentLen > MAX_BODY_BYTES) {
                    sendHttpJson(s, 413, "Payload Too Large", JSONObject().put("ok", false).put("error", "Payload too large"))
                    return
                }

                val expectedToken = prefs.getClientAuthToken()
                if (!isTokenAuthorized(expectedToken, authHeader)) {
                    sendHttpJson(s, 401, "Unauthorized", JSONObject().put("ok", false).put("error", "غير مصرح"))
                    return
                }

                val respJson = if (path == "/config" && contentLen > 0) {
                    val buf = ByteArray(contentLen)
                    var tot = 0
                    while (tot < contentLen) {
                        val r = rawIn.read(buf, tot, contentLen - tot)
                        if (r == -1) break
                        tot += r
                    }
                    val obj = JSONObject(String(buf, 0, tot, Charsets.UTF_8))
                    val arr = obj.optJSONArray("whitelistedPackages") ?: JSONArray()
                    val pkgs = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        if (!arr.isNull(i)) {
                            val p = arr.optString(i, "").trim()
                            if (p.isNotBlank() && p != "null") pkgs.add(p)
                        }
                    }
                    val period = runCatching {
                        LimitPeriod.valueOf(obj.optCleanString("limitPeriod", "DAILY"))
                    }.getOrDefault(LimitPeriod.DAILY)
                    prefs.updateLocalLimits(
                        limitMb = obj.optLong("dailyLimitMb", 1024L).coerceAtLeast(10L),
                        limitPeriod = period,
                        speedPolicy = SpeedPolicyConfig.fromJson(obj.optJSONObject("speedPolicy")),
                        whitelist = pkgs,
                        globalSpeedCapMbps = obj.optDouble("globalSpeedCapMbps", 0.0).sanitizeFinite(0.0, 0.0, 1000.0),
                        forceQuotaSaver = obj.optBoolean("forceQuotaSaver", false),
                        targetNetworkOnly = obj.optBoolean("targetNetworkOnly", false),
                        targetNetworkSig = obj.optCleanString("targetNetworkSig", ""),
                        isPaused = obj.optBoolean("isPaused", false)
                    )
                    WifiGuardVpnService.notifyConfigChanged()
                    JSONObject().put("ok", true)
                } else {
                    val vpnUp = WifiGuardVpnService.isRunning.value
                    JSONObject().apply {
                        put("ok", true)
                        put("usedMb", prefs.getUsedMbActivePeriod())
                        put("usedMbToday", prefs.getUsedMbToday())
                        put("savedMbToday", prefs.getSavedMbToday())
                        put("actualSpeedMbps", WifiGuardVpnService.liveSpeedMbps.value.sanitizeFinite(0.0, 0.0, 10_000.0))
                        val curPeriod = prefs.limitPeriodFlow.first()
                        val appMap = if (path.contains("full=1")) {
                            val allPkgs = getInstalledLaunchableApps(applicationContext).map { it.packageName }
                            val fresh = AppUsageStatsHelper.getWifiUsageMbByPackage(applicationContext, allPkgs, curPeriod)
                            if (fresh.isNotEmpty()) {
                                prefs.setAppUsedMbMap(fresh, curPeriod)
                                fresh
                            } else prefs.getAppUsedMbMap(curPeriod)
                        } else {
                            prefs.getAppUsedMbMap(curPeriod)
                        }
                        put("appUsageMb", JSONObject(appMap.mapValues { it.value }))
                        put("protectionActive", vpnUp)
                        put("uninstallProtectionActive", WifiGuardDeviceAdminReceiver.isActive(applicationContext))
                        put("connectionState", if (vpnUp) WifiGuardVpnService.connectionState.value.name else ConnectionState.PROTECTION_OFF.name)
                    }
                }

                sendHttpJson(s, 200, "OK", respJson)
            }
        }
    }

    private fun sendHttpJson(socket: Socket, code: Int, reason: String, json: JSONObject) {
        val respBytes = json.toString().toByteArray(Charsets.UTF_8)
        val hdr = ("HTTP/1.1 $code $reason\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n" +
            "Content-Length: ${respBytes.size}\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().apply {
            write(hdr)
            write(respBytes)
            flush()
        }
    }

    private fun registerWifiReconnectCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    serviceScope.launch {
                        for (waitMs in listOf(400L, 1500L, 3500L)) {
                            delay(waitMs)
                            if (syncWithSupervisorOnce()) break
                        }
                    }
                }

                override fun onLost(network: Network) {
                    isSupervisorReachable = false
                    _isSupervisorReachableFlow.value = false
                }
            }
            wifiCallback = cb
            cm.registerNetworkCallback(req, cb)
        }
    }

    private fun startPersistentHeartbeatAndVpnWatchdog() {
        serviceScope.launch {
            WifiGuardVpnService.isRunning.collect { vpnNow ->
                runCatching {
                    val role = prefs.roleFlow.first()
                    if (role == AppRole.CLIENT && wasVpnRunning && !vpnNow) {
                        val host = prefs.getSupervisorHost()
                        if (host.isNotBlank()) {
                            val port = prefs.getSupervisorPort()
                            val id = prefs.getOrCreateDeviceId()
                            val name = prefs.getDeviceName()
                            val token = prefs.getClientAuthToken()
                            LocalLanClient.sendTamperAlert(
                                host,
                                port,
                                id,
                                name,
                                "تحايل: قام جهاز $name بإيقاف الحماية (VPN) دون حذف التطبيق!",
                                authToken = token
                            )
                            syncWithSupervisorOnce()
                        }
                    }
                    wasVpnRunning = vpnNow
                }
            }
        }

        serviceScope.launch {
            // Brief startup grace so WifiGuardVpnService finishes establishing tunnel before first heartbeat
            delay(5_000L)
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            var failures = 0
            while (isActive) {
                runCatching {
                    val role = prefs.roleFlow.first()
                    if (role == AppRole.CLIENT) {
                        val ok = syncWithSupervisorOnce()
                        if (ok) {
                            failures = 0
                        } else if (!_isUnpairedFlow.value) {
                            failures++
                            val screenOnNow = pm?.isInteractive ?: true
                            val nowMs = System.currentTimeMillis()
                            // Never run multicast mDNS discovery while screen is off; rate-limit to once per 10 min when screen is on
                            if (screenOnNow && failures >= 3 && (nowMs - lastNsdScanMs >= 600_000L)) {
                                failures = 0
                                lastNsdScanMs = nowMs
                                val candidates = withTimeoutOrNull(4_000L) {
                                    LocalLanClient.discoverSupervisors(applicationContext).first { it.isNotEmpty() }
                                } ?: emptyList()
                                for (cand in candidates) {
                                    if (syncWithSupervisorOnce(overrideHost = cand.host, overridePort = cand.port)) {
                                        prefs.setSupervisorConnection(cand.host, cand.port, pairingCode = null)
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
                val interactive = pm?.isInteractive ?: true
                val waitMs = if (interactive) 180_000L else 900_000L
                withTimeoutOrNull(waitMs) { wakeSyncChannel.receive() }
            }
        }
    }

    private suspend fun syncWithSupervisorOnce(
        overrideHost: String? = null,
        overridePort: Int? = null
    ): Boolean {
        val host = overrideHost ?: prefs.getSupervisorHost()
        val port = overridePort ?: prefs.getSupervisorPort()
        if (host.isBlank()) return false
        val id = prefs.getOrCreateDeviceId()
        val name = prefs.getDeviceName()
        val token = prefs.getClientAuthToken()
        val usedMb = prefs.getUsedMbActivePeriod()
        val usedMbToday = prefs.getUsedMbToday()
        val savedMb = prefs.getSavedMbToday()
        val vpnUp = WifiGuardVpnService.isRunning.value
        val adminActive = WifiGuardDeviceAdminReceiver.isActive(applicationContext)
        val nowMs = System.currentTimeMillis()
        val shouldSendApps = lastAppsSentMs == 0L || (nowMs - lastAppsSentMs >= 30 * 60_000L)
        when (val outcome = LocalLanClient.sendHeartbeatOutcome(
            host = host,
            port = port,
            deviceId = id,
            deviceName = name,
            usedMb = usedMb,
            usedMbToday = usedMbToday,
            savedMbToday = savedMb,
            actualSpeedMbps = WifiGuardVpnService.liveSpeedMbps.value,
            appUsageMb = prefs.getAppUsedMbMap(prefs.limitPeriodFlow.first()),
            protectionActive = vpnUp,
            uninstallProtectionActive = adminActive,
            connectionState = if (vpnUp) WifiGuardVpnService.connectionState.value else ConnectionState.PROTECTION_OFF,
            installedApps = if (shouldSendApps) getInstalledLaunchableApps(applicationContext) else emptyList(),
            authToken = token
        )) {
            is HeartbeatOutcome.Synced -> {
                val updated = outcome.config
                isSupervisorReachable = true
                _isSupervisorReachableFlow.value = true
                _isUnpairedFlow.value = false
                _lastSyncMillisFlow.value = nowMs
                if (shouldSendApps) lastAppsSentMs = nowMs
                if (updated.authToken.isNotBlank() && token.isBlank()) {
                    prefs.setClientAuthToken(updated.authToken)
                }
                if (updated != lastSyncedConfig) {
                    lastSyncedConfig = updated
                    prefs.updateLocalLimits(
                        limitMb = updated.dailyLimitMb,
                        limitPeriod = updated.limitPeriod,
                        speedPolicy = updated.speedPolicy,
                        whitelist = updated.whitelistedPackages,
                        globalSpeedCapMbps = updated.globalSpeedCapMbps,
                        forceQuotaSaver = updated.forceQuotaSaver,
                        targetNetworkOnly = updated.targetNetworkOnly,
                        targetNetworkSig = updated.targetNetworkSig,
                        isPaused = updated.isPaused
                    )
                    WifiGuardVpnService.notifyConfigChanged()
                }
                return true
            }
            HeartbeatOutcome.Unpaired -> {
                if (overrideHost == null) {
                    isSupervisorReachable = false
                    _isSupervisorReachableFlow.value = false
                    _isUnpairedFlow.value = true
                }
                return false
            }
            HeartbeatOutcome.NetworkError -> {
                if (overrideHost == null) {
                    isSupervisorReachable = false
                    _isSupervisorReachableFlow.value = false
                }
                return false
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "مراقبة الاتصال بالمشرف", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("No Quota")
            .setContentText("متصل بجهاز المشرف")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}

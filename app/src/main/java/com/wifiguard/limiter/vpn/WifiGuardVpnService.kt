package com.wifiguard.limiter.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wifiguard.limiter.MainActivity
import com.wifiguard.limiter.admin.WifiGuardDeviceAdminReceiver
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.computeEffectiveSpeed
import com.wifiguard.limiter.model.getInstalledLaunchableApps
import com.wifiguard.limiter.model.formatDataSizeArabic
import com.wifiguard.limiter.network.LocalLanClient
import com.wifiguard.limiter.network.NetworkScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

class WifiGuardVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: PreferencesManager
    private lateinit var dnsHandler: DnsPacketHandler
    private val tokenBucket = TokenBucket()
    private var tunInterface: ParcelFileDescriptor? = null
    private var forwarder: Tun2NetForwarder? = null
    private var packetLoopJob: Job? = null
    private var clientControlSocket: java.net.ServerSocket? = null

    private var currentUseFullTunnel: Boolean = false
    private var currentWhitelist: Set<String> = emptySet()
    private var lastTrafficBytes: Long = -1L

    companion object {
        const val CHANNEL_ID = "wifiguard_vpn_channel"
        const val NOTIF_ID = 2001
        const val ACTION_STOP = "com.wifiguard.limiter.STOP_VPN"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _connectionState = MutableStateFlow(ConnectionState.SMART_SAVING)
        val connectionState: StateFlow<ConnectionState> = _connectionState

        private val _statusLabel = MutableStateFlow("التوفير الذكي نشط")
        val statusLabel: StateFlow<String> = _statusLabel

        private val _blockedAdsCount = MutableStateFlow(0L)
        val blockedAdsCount: StateFlow<Long> = _blockedAdsCount

        private val _isAlwaysOnActive = MutableStateFlow(false)
        val isAlwaysOnActive: StateFlow<Boolean> = _isAlwaysOnActive

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, WifiGuardVpnService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, WifiGuardVpnService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(applicationContext)
        dnsHandler = DnsPacketHandler(this)
        createNotificationChannel()
        val notif = buildNotification("جاري تشغيل حماية الاتصال...", "التوفير الذكي نشط")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }
        _isRunning.value = true
        serviceScope.launch {
            runCatching { establishTunnel(useFullTunnel = false, whitelist = emptySet()) }
        }
        startMonitoringLoop()
        startClientControlServer()
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
        _statusLabel.value = "الحماية متوقفة حالياً"
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
        packetLoopJob?.cancel()
        runCatching { forwarder?.close() }
        forwarder = null
        runCatching { clientControlSocket?.close() }
        runCatching { tunInterface?.close() }
        tunInterface = null
        serviceScope.cancel()
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            val recent60sBytes = ArrayDeque<Long>()
            var burstCooldownTicks = 0
            var screenOffSeconds = 0
            var lastAdsCount = 0L
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            while (isActive) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    _isAlwaysOnActive.value = runCatching { isAlwaysOn }.getOrDefault(false)
                }
                val cfg = prefs.getLocalLimitConfig()
                    dnsHandler.upstreamDnsIp = cfg.speedPolicy.upstreamDnsIp
                    val onWifi = isOnWifi()

                    val currentWifiSig = if (onWifi) NetworkScanner.getCurrentWifiSignature(applicationContext) else ""
                    val outsideTargetWifi = cfg.targetNetworkOnly &&
                        cfg.targetNetworkSig.isNotBlank() &&
                        onWifi &&
                        currentWifiSig.isNotBlank() &&
                        !matchesNetworkSignature(currentWifiSig, cfg.targetNetworkSig)

                    if (outsideTargetWifi) {
                        lastTrafficBytes = -1L
                        _connectionState.value = ConnectionState.OUTSIDE_TARGET_WIFI
                        _statusLabel.value = "خارج الشبكة المحددة — بلا قيود"
                        tokenBucket.setRateBytesPerSec(Long.MAX_VALUE)
                        if (currentUseFullTunnel || cfg.whitelist != currentWhitelist) {
                            establishTunnel(useFullTunnel = false, whitelist = cfg.whitelist)
                        }
                        updateNotification("متصل بشبكة أخرى", "خارج الشبكة المحددة — بلا قيود")
                    } else {
                        var intervalBytes = 0L
                        if (onWifi) {
                            val currentTotal = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
                            if (lastTrafficBytes > 0L && currentTotal > lastTrafficBytes) {
                                val delta = currentTotal - lastTrafficBytes
                                if (delta < 100L * 1024L * 1024L) {
                                    intervalBytes = delta
                                    prefs.addUsedBytes(delta)
                                }
                            }
                            lastTrafficBytes = currentTotal
                        } else {
                            lastTrafficBytes = -1L
                        }

                        if (!powerManager.isInteractive) {
                            screenOffSeconds++
                        } else {
                            screenOffSeconds = 0
                        }
                        val isScreenOffIdle = screenOffSeconds >= 60

                        val usedMb = prefs.getUsedMbActivePeriod()
                        dnsHandler.upstreamDnsIp = cfg.speedPolicy.upstreamDnsIp

                        recent60sBytes.addLast(intervalBytes)
                        if (recent60sBytes.size > 60) recent60sBytes.removeFirst()
                        val burstThresholdBytes = cfg.speedPolicy.burstThresholdMbPerMin.toLong() * 1024L * 1024L
                        if (recent60sBytes.sum() > burstThresholdBytes) {
                            burstCooldownTicks = 120
                        } else if (burstCooldownTicks > 0) {
                            burstCooldownTicks--
                        }

                        val effective = computeEffectiveSpeed(
                            usedMb = usedMb,
                            limitMb = cfg.limitMb,
                            policy = cfg.speedPolicy,
                            forcedBySupervisor = cfg.forceQuotaSaver,
                            globalSpeedCapMbPerSec = cfg.globalSpeedCapMbps,
                            burstDetected = false,
                            isScreenOffIdle = isScreenOffIdle
                        )

                        _connectionState.value = effective.state
                        _statusLabel.value = effective.statusLabelArabic
                        val curAds = dnsHandler.blockedRequestsCount.get()
                        val deltaAds = (curAds - lastAdsCount).coerceAtLeast(0L)
                        lastAdsCount = curAds
                        _blockedAdsCount.value = curAds

                        // Real-signal savings estimator (ads blocked + idle-screen freeze + ABR shaping)
                        var savedDelta = deltaAds * 120L * 1024L
                        if (effective.state == ConnectionState.IDLE_FROZEN) {
                            savedDelta += 15L * 1024L
                        } else if (effective.saverActive && effective.targetBytesPerSec < Long.MAX_VALUE && intervalBytes >= (effective.targetBytesPerSec * 5L) / 10L) {
                            savedDelta += (intervalBytes * 35L) / 100L
                        }
                        if (savedDelta > 0L) prefs.addSavedBytes(savedDelta)

                        tokenBucket.setRateBytesPerSec(effective.targetBytesPerSec)
                        val blockAllNow = effective.state == ConnectionState.BLOCKED || effective.state == ConnectionState.IDLE_FROZEN
                        val needsFullTunnel = blockAllNow || effective.targetBytesPerSec < Long.MAX_VALUE

                        forwarder?.burstGuardEnabled = effective.saverActive && cfg.speedPolicy.burstGuardEnabled
                        forwarder?.burstThresholdBytesPerMin = cfg.speedPolicy.burstThresholdMbPerMin.toLong() * 1024L * 1024L
                        forwarder?.burstCapMbps = cfg.speedPolicy.burstCapSpeedMbPerSec
                        forwarder?.flags = Tun2NetForwarder.PacketPolicyFlags(
                            blockAll = blockAllNow,
                            sinkholeEnabled = effective.saverActive && cfg.speedPolicy.dnsSinkholeEnabled,
                            cacheEnabled = effective.saverActive && cfg.speedPolicy.dnsCacheEnabled,
                            blockDohDot = effective.saverActive && cfg.speedPolicy.blockDohDotEnabled,
                            blockQuicUdp443 = effective.saverActive && cfg.speedPolicy.blockQuicUdp443Enabled
                        )

                        if (needsFullTunnel != currentUseFullTunnel || cfg.whitelist != currentWhitelist || tunInterface == null) {
                            establishTunnel(useFullTunnel = needsFullTunnel, whitelist = cfg.whitelist)
                        }

                        val summary = "${cfg.limitPeriod.arabicLabel}: ${formatDataSizeArabic(usedMb)} من ${formatDataSizeArabic(cfg.limitMb)}"
                        updateNotification(summary, effective.statusLabelArabic)
                    }
                }

                delay(1000L)
            }
        }
    }

    private fun matchesNetworkSignature(current: String, target: String): Boolean {
        val curGw = current.substringBefore("|")
        val curMac = current.substringAfter("|", "")
        val tgtGw = target.substringBefore("|")
        val tgtMac = target.substringAfter("|", "")
        if (curMac.isNotBlank() && tgtMac.isNotBlank()) {
            return curMac.equals(tgtMac, ignoreCase = true)
        }
        return curGw == tgtGw
    }

    private fun startClientControlServer() {
        serviceScope.launch {
            runCatching {
                clientControlSocket = java.net.ServerSocket(LocalLanClient.CLIENT_PORT)
                while (isActive) {
                    val sock = clientControlSocket?.accept() ?: break
                    launch {
                        sock.use { s ->
                            runCatching {
                                s.soTimeout = 4000
                                val rawIn = s.getInputStream()
                                fun readLine(): String? {
                                    val lb = java.io.ByteArrayOutputStream()
                                    while (true) {
                                        val b = rawIn.read()
                                        if (b == -1) return if (lb.size() == 0) null else lb.toString("UTF-8")
                                        if (b == '\n'.code) break
                                        if (b != '\r'.code) lb.write(b)
                                    }
                                    return lb.toString("UTF-8")
                                }
                                val reqLine = readLine() ?: return@runCatching
                                val parts = reqLine.split(" ")
                                val path = parts.getOrNull(1) ?: "/"
                                var contentLen = 0
                                while (true) {
                                    val h = readLine() ?: break
                                    if (h.isEmpty()) break
                                    if (h.lowercase().startsWith("content-length:")) {
                                        contentLen = h.substringAfter(":").trim().toIntOrNull() ?: 0
                                    }
                                }
                                val respJson = if (path == "/config" && contentLen > 0) {
                                    val buf = ByteArray(contentLen)
                                    var tot = 0
                                    while (tot < contentLen) {
                                        val r = rawIn.read(buf, tot, contentLen - tot)
                                        if (r == -1) break
                                        tot += r
                                    }
                                    val obj = org.json.JSONObject(String(buf, 0, tot, Charsets.UTF_8))
                                    val arr = obj.optJSONArray("whitelistedPackages") ?: org.json.JSONArray()
                                    val pkgs = mutableSetOf<String>()
                                    for (i in 0 until arr.length()) {
                                        val p = arr.optString(i)
                                        if (p.isNotBlank()) pkgs.add(p)
                                    }
                                    val period = runCatching {
                                        com.wifiguard.limiter.model.LimitPeriod.valueOf(obj.optString("limitPeriod", "DAILY"))
                                    }.getOrDefault(com.wifiguard.limiter.model.LimitPeriod.DAILY)
                                    prefs.updateLocalLimits(
                                        limitMb = obj.optLong("dailyLimitMb", 1024L),
                                        limitPeriod = period,
                                        speedPolicy = com.wifiguard.limiter.model.SpeedPolicyConfig.fromJson(obj.optJSONObject("speedPolicy")),
                                        whitelist = pkgs,
                                        globalSpeedCapMbps = obj.optDouble("globalSpeedCapMbps", 0.0),
                                        forceQuotaSaver = obj.optBoolean("forceQuotaSaver", false),
                                        targetNetworkOnly = obj.optBoolean("targetNetworkOnly", false),
                                        targetNetworkSig = obj.optString("targetNetworkSig", "")
                                    )
                                    org.json.JSONObject().put("ok", true)
                                } else {
                                    org.json.JSONObject().apply {
                                        put("ok", true)
                                        put("usedMb", prefs.getUsedMbActivePeriod())
                                        put("savedMbToday", prefs.getSavedMbToday())
                                        put("protectionActive", _isRunning.value)
                                        put("uninstallProtectionActive", WifiGuardDeviceAdminReceiver.isActive(applicationContext))
                                        put("connectionState", _connectionState.value.name)
                                    }
                                }
                                val respBytes = respJson.toString().toByteArray(Charsets.UTF_8)
                                val hdr = ("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: ${respBytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                                s.getOutputStream().apply {
                                    write(hdr)
                                    write(respBytes)
                                    flush()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startHeartbeatLoop() {
        serviceScope.launch {
            var sentAppsOnce = false
            var consecutiveFailures = 0
            while (isActive) {
                runCatching {
                    val role = prefs.roleFlow.first()
                    if (role == AppRole.CLIENT) {
                        val host = prefs.getSupervisorHost()
                        val port = prefs.getSupervisorPort()
                        if (host.isNotBlank()) {
                            val id = prefs.getOrCreateDeviceId()
                            val name = prefs.getDeviceName()
                            val usedMb = prefs.getUsedMbActivePeriod()
                            val savedMb = prefs.getSavedMbToday()
                            val adminActive = WifiGuardDeviceAdminReceiver.isActive(applicationContext)
                            val updated = LocalLanClient.sendHeartbeat(
                                host = host,
                                port = port,
                                deviceId = id,
                                deviceName = name,
                                usedMb = usedMb,
                                savedMbToday = savedMb,
                                protectionActive = _isRunning.value,
                                uninstallProtectionActive = adminActive,
                                connectionState = _connectionState.value,
                                installedApps = if (!sentAppsOnce) getInstalledLaunchableApps(applicationContext) else emptyList()
                            )
                            if (updated != null) {
                                sentAppsOnce = true
                                consecutiveFailures = 0
                                prefs.updateLocalLimits(
                                    limitMb = updated.dailyLimitMb,
                                    limitPeriod = updated.limitPeriod,
                                    speedPolicy = updated.speedPolicy,
                                    whitelist = updated.whitelistedPackages,
                                    globalSpeedCapMbps = updated.globalSpeedCapMbps,
                                    forceQuotaSaver = updated.forceQuotaSaver,
                                    targetNetworkOnly = updated.targetNetworkOnly,
                                    targetNetworkSig = updated.targetNetworkSig
                                )
                            } else {
                                consecutiveFailures++
                                if (consecutiveFailures >= 2) {
                                    consecutiveFailures = 0
                                    val found = withTimeoutOrNull(3500L) {
                                        LocalLanClient.discoverSupervisors(applicationContext).first { it.isNotEmpty() }
                                    }?.firstOrNull()
                                    if (found != null) {
                                        prefs.setSupervisorConnection(found.host, found.port, "")
                                    }
                                }
                            }
                        }
                    }
                }
                delay(60_000L)
            }
        }
    }

    private fun establishTunnel(useFullTunnel: Boolean, whitelist: Set<String>) {
        packetLoopJob?.cancel()
        runCatching { forwarder?.close() }
        forwarder = null
        runCatching { clientControlSocket?.close() }
        runCatching { tunInterface?.close() }
        currentUseFullTunnel = useFullTunnel
        currentWhitelist = whitelist

        val builder = Builder()
            .setSession("حارس الواي فاي")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.1")
            .setMtu(1400)

        if (useFullTunnel) {
            builder.addRoute("0.0.0.0", 0)
        } else {
            builder.addRoute("10.0.0.1", 32)
            builder.addRoute("10.254.0.0", 16)
        }

        runCatching { builder.addDisallowedApplication(packageName) }
        whitelist.forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }
        }

        tunInterface = runCatching { builder.establish() }.getOrNull()

        val pfd = tunInterface ?: return
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val fwd = runCatching { Tun2NetForwarder(this, dnsHandler, tokenBucket, output) }.getOrNull() ?: return
        forwarder = fwd

        packetLoopJob = serviceScope.launch {
            val buffer = ByteArray(32767)
            while (isActive) {
                val len = runCatching { input.read(buffer) }.getOrElse { -1 }
                if (len < 0) break
                if (len > 0) {
                    runCatching { fwd.processTunPacket(buffer, len) }
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
                    "حماية الاتصال",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(text: String, title: String): Notification {
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

    private fun updateNotification(text: String, title: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text, title))
        }
    }
}

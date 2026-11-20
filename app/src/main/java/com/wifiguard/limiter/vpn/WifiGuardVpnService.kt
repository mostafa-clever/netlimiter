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
import android.net.ProxyInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wifiguard.limiter.BuildConfig
import com.wifiguard.limiter.MainActivity
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.computeEffectiveSpeed
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
    private var proxyServer: LocalHttpProxyServer? = null
    private var legacyForwarder: Tun2NetForwarder? = null
    private var packetLoopJob: Job? = null

    private var currentBlockAll: Boolean = false
    private var currentProxyActive: Boolean = false
    private var currentUseLocalDns: Boolean = false
    private var currentWhitelist: Set<String> = emptySet()
    private var currentBlockedApps: Set<String> = emptySet()
    private var lastTrafficBytes: Long = -1L
    private val lastAppUidBytes = mutableMapOf<String, Long>()
    @Volatile private var cachedPolicy = com.wifiguard.limiter.model.SpeedPolicyConfig()
    private var pendingUsedBytes: Long = 0L
    private var pendingSavedBytes: Long = 0L
    private var lastFlushMs: Long = 0L
    private var lastNotifTitle: String = ""
    private var lastNotifText: String = "" 

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
            proxyServer = LocalHttpProxyServer(this@WifiGuardVpnService, tokenBucket).also { it.start() }
            runCatching {
                establishTunnel(
                    blockAll = false,
                    blockedApps = emptySet(),
                    enableProxy = true,
                    whitelist = emptySet()
                )
            }
        }
        startMonitoringLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            _isRunning.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        _isRunning.value = true
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
        runCatching { legacyForwarder?.close() }
        legacyForwarder = null
        runCatching { proxyServer?.stop() }
        proxyServer = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        val remUsed = pendingUsedBytes
        val remSaved = pendingSavedBytes
        pendingUsedBytes = 0L
        pendingSavedBytes = 0L
        if (remUsed > 0L || remSaved > 0L) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                if (remUsed > 0L) prefs.addUsedBytes(remUsed)
                if (remSaved > 0L) prefs.addSavedBytes(remSaved)
            }
        }
        serviceScope.cancel()
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            var screenOffSeconds = 0
            var lastAdsCount = 0L
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            while (isActive) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        _isAlwaysOnActive.value = runCatching { isAlwaysOn }.getOrDefault(false)
                    }
                    if (tunInterface != null) {
                        _isRunning.value = true
                    }

                    val cfg = prefs.getLocalLimitConfig()
                    cachedPolicy = cfg.speedPolicy
                    dnsHandler.upstreamDnsIp = cfg.speedPolicy.upstreamDnsIp
                    val onWifi = isOnWifi()

                    val currentWifiSig = if (onWifi) {
                        NetworkScanner.getCurrentWifiSignature(applicationContext)
                            .ifBlank { NetworkScanner.getCurrentWifiSignatureActive(applicationContext) }
                    } else ""

                    val outsideTargetWifi = cfg.targetNetworkOnly &&
                        cfg.targetNetworkSig.isNotBlank() &&
                        onWifi &&
                        currentWifiSig.isNotBlank() &&
                        !NetworkScanner.matchesNetworkSignature(currentWifiSig, cfg.targetNetworkSig)

                    if (cfg.isPaused) {
                        _connectionState.value = ConnectionState.PAUSED
                        _statusLabel.value = "تم إيقاف الإنترنت مؤقتاً بواسطة المشرف"
                        tokenBucket.setRateBytesPerSec(0L)
                        if (!currentBlockAll || cfg.whitelist != currentWhitelist || tunInterface == null) {
                            establishTunnel(blockAll = true, blockedApps = emptySet(), enableProxy = false, whitelist = cfg.whitelist)
                        }
                        updateNotification("إيقاف مؤقت", "تم إيقاف الإنترنت مؤقتاً بواسطة المشرف")
                    } else if (outsideTargetWifi) {
                        lastTrafficBytes = -1L
                        _connectionState.value = ConnectionState.OUTSIDE_TARGET_WIFI
                        _statusLabel.value = "خارج الشبكة المحددة — بلا قيود"
                        tokenBucket.setRateBytesPerSec(Long.MAX_VALUE)
                        if (currentBlockAll || currentBlockedApps.isNotEmpty() || currentProxyActive || cfg.whitelist != currentWhitelist) {
                            establishTunnel(
                                blockAll = false,
                                blockedApps = emptySet(),
                                enableProxy = false,
                                whitelist = cfg.whitelist
                            )
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
                                    pendingUsedBytes += delta
                                }
                            }
                            lastTrafficBytes = currentTotal
                        } else {
                            lastTrafficBytes = -1L
                        }

                        if (!powerManager.isInteractive) {
                            screenOffSeconds += 5
                        } else {
                            screenOffSeconds = 0
                        }
                        val isScreenOffIdle = screenOffSeconds >= 60

                        val usedMb = prefs.getUsedMbActivePeriod()

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

                        var savedDelta = deltaAds * 120L * 1024L
                        if (effective.state == ConnectionState.IDLE_FROZEN) {
                            savedDelta += 15L * 1024L
                        } else if (effective.saverActive &&
                            effective.targetBytesPerSec < Long.MAX_VALUE &&
                            intervalBytes >= (effective.targetBytesPerSec * 5L) / 10L
                        ) {
                            savedDelta += (intervalBytes * 35L) / 100L
                        }
                        if (savedDelta > 0L) pendingSavedBytes += savedDelta
                        val nowMs = System.currentTimeMillis()
                        if (pendingUsedBytes >= 1024L * 1024L || pendingSavedBytes >= 1024L * 1024L || (nowMs - lastFlushMs >= 15_000L && (pendingUsedBytes > 0L || pendingSavedBytes > 0L))) {
                            if (pendingUsedBytes > 0L) {
                                prefs.addUsedBytes(pendingUsedBytes)
                                pendingUsedBytes = 0L
                            }
                            if (pendingSavedBytes > 0L) {
                                prefs.addSavedBytes(pendingSavedBytes)
                                pendingSavedBytes = 0L
                            }
                            lastFlushMs = nowMs
                        }

                        // Per-app quota percentage tracking
                        val appQuotas = cfg.speedPolicy.appQuotaPercents
                        if (onWifi && appQuotas.isNotEmpty()) {
                            val appDeltas = mutableMapOf<String, Long>()
                            appQuotas.keys.forEach { pkg ->
                                val uid = runCatching { packageManager.getApplicationInfo(pkg, 0).uid }.getOrDefault(-1)
                                if (uid > 0) {
                                    val totalUid = TrafficStats.getUidRxBytes(uid) + TrafficStats.getUidTxBytes(uid)
                                    if (totalUid >= 0L) {
                                        val prev = lastAppUidBytes[pkg]
                                        if (prev != null && totalUid > prev) {
                                            val d = totalUid - prev
                                            if (d < 100L * 1024L * 1024L) appDeltas[pkg] = d
                                        }
                                        lastAppUidBytes[pkg] = totalUid
                                    }
                                }
                            }
                            if (appDeltas.isNotEmpty()) prefs.addAppUsedBytes(appDeltas, cfg.limitPeriod)
                        }
                        val appUsedMbMap = if (appQuotas.isNotEmpty()) prefs.getAppUsedMbMap(cfg.limitPeriod) else emptyMap()
                        val overQuotaApps = appQuotas.filter { (pkg, pct) ->
                            val allowedMb = (cfg.limitMb * pct) / 100L
                            (appUsedMbMap[pkg] ?: 0L) >= allowedMb && !cfg.whitelist.contains(pkg)
                        }.keys

                        tokenBucket.setRateBytesPerSec(effective.targetBytesPerSec)
                        val mediaSaverOn = effective.saverActive && cfg.speedPolicy.mediaCdnSaverEnabled
                        val blockUpdatesOn = effective.saverActive && cfg.speedPolicy.blockAppUpdatesInSaver
                        proxyServer?.updateMediaSaverConfig(mediaSaverOn, cfg.speedPolicy.mediaCdnSpeedMbps, blockUpdatesOn)
                        val blockAllNow = effective.state == ConnectionState.BLOCKED || effective.state == ConnectionState.IDLE_FROZEN
                        val proxyNeeded = !blockAllNow && overQuotaApps.isEmpty() && (effective.targetBytesPerSec < Long.MAX_VALUE || mediaSaverOn || blockUpdatesOn)
                        val useLocalDnsNow = BuildConfig.ENABLE_DNS_ADBLOCK && !isSystemPrivateDnsActive()

                        if (blockAllNow != currentBlockAll ||
                            overQuotaApps != currentBlockedApps ||
                            proxyNeeded != currentProxyActive ||
                            useLocalDnsNow != currentUseLocalDns ||
                            cfg.whitelist != currentWhitelist ||
                            tunInterface == null
                        ) {
                            establishTunnel(
                                blockAll = blockAllNow,
                                blockedApps = overQuotaApps,
                                enableProxy = proxyNeeded,
                                whitelist = cfg.whitelist
                            )
                        }

                        val summary = "${cfg.limitPeriod.arabicLabel}: ${formatDataSizeArabic(usedMb)} من ${formatDataSizeArabic(cfg.limitMb)}"
                        updateNotification(summary, effective.statusLabelArabic)
                    }
                }

                delay(if (powerManager.isInteractive) 1000L else 5000L)
            }
        }
    }

    private fun establishTunnel(
        blockAll: Boolean,
        blockedApps: Set<String>,
        enableProxy: Boolean,
        whitelist: Set<String>
    ) {
        packetLoopJob?.cancel()
        runCatching { legacyForwarder?.close() }
        legacyForwarder = null
        runCatching { tunInterface?.close() }
        currentBlockAll = blockAll
        currentBlockedApps = blockedApps
        currentProxyActive = enableProxy
        currentWhitelist = whitelist

        val useLegacyTunThrottle = enableProxy && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        val useLocalDns = (BuildConfig.ENABLE_DNS_ADBLOCK && !isSystemPrivateDnsActive()) || useLegacyTunThrottle
        currentUseLocalDns = useLocalDns

        val builder = Builder()
            .setSession("No Quota")
            .addAddress("10.0.0.2", 32)
            .setMtu(1400)
        if (useLocalDns) {
            builder.addDnsServer("10.0.0.1")
        }

        if (blockAll) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            runCatching { builder.addDisallowedApplication(packageName) }
            whitelist.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
        } else if (blockedApps.isNotEmpty()) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            blockedApps.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
        } else if (useLegacyTunThrottle) {
            // Android 8/9 (API < 29, e.g. Oppo A1k) does not support VpnService.setHttpProxy -> use Tun2NetForwarder + TokenBucket
            builder.addRoute("0.0.0.0", 0)
            runCatching { builder.addDisallowedApplication(packageName) }
            whitelist.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
        } else {
            if (useLocalDns) {
                builder.addRoute("10.0.0.1", 32)
            }
            builder.addRoute("10.254.0.0", 16)
            if (enableProxy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", LocalHttpProxyServer.PROXY_PORT))
                }
            }
            runCatching { builder.addDisallowedApplication(packageName) }
            whitelist.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
        }

        tunInterface = runCatching { builder.establish() }.getOrNull()

        val pfd = tunInterface ?: return
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)

        if (useLegacyTunThrottle) {
            val fwd = runCatching { Tun2NetForwarder(this, dnsHandler, tokenBucket, output) }.getOrNull()
            legacyForwarder = fwd
            if (fwd != null) {
                val policy = cachedPolicy
                fwd.flags = Tun2NetForwarder.PacketPolicyFlags(
                    blockAll = false,
                    sinkholeEnabled = BuildConfig.ENABLE_DNS_ADBLOCK && policy.dnsSinkholeEnabled,
                    cacheEnabled = policy.dnsCacheEnabled,
                    blockDohDot = false,
                    blockQuicUdp443 = true
                )
                packetLoopJob = serviceScope.launch {
                    val buffer = ByteArray(32767)
                    while (isActive) {
                        val len = runCatching { input.read(buffer) }.getOrElse { -1 }
                        if (len < 0) break
                        if (len > 0) runCatching { fwd.processTunPacket(buffer, len) }
                    }
                }
            }
            return
        }

        packetLoopJob = serviceScope.launch {
            val buffer = ByteArray(32767)
            while (isActive) {
                val len = runCatching { input.read(buffer) }.getOrElse { -1 }
                if (len < 0) break
                if (len > 0 && !blockAll && blockedApps.isEmpty() && useLocalDns) {
                    val policy = cachedPolicy
                    val pktCopy = buffer.copyOf(len)
                    launch {
                        val resp = runCatching {
                            dnsHandler.handlePacket(
                                packet = pktCopy,
                                length = len,
                                sinkholeEnabled = policy.dnsSinkholeEnabled,
                                cacheEnabled = policy.dnsCacheEnabled,
                                blockDohDot = policy.blockDohDotEnabled,
                                blockQuicUdp443 = false
                            )
                        }.getOrNull()
                        if (resp != null) {
                            synchronized(output) { runCatching { output.write(resp) } }
                        }
                    }
                }
            }
        }
    }

    private fun isSystemPrivateDnsActive(): Boolean {
        return runCatching {
            val mode = Settings.Global.getString(contentResolver, "private_dns_mode")
            mode.equals("hostname", ignoreCase = true)
        }.getOrDefault(false)
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
        if (text == lastNotifText && title == lastNotifTitle) return
        lastNotifText = text
        lastNotifTitle = title
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text, title))
        }
    }
}

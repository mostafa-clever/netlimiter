package com.wifiguard.limiter.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wifiguard.limiter.BuildConfig
import com.wifiguard.limiter.MainActivity
import com.wifiguard.limiter.data.AppUsageStatsHelper
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.LimitPeriod
import com.wifiguard.limiter.model.SpeedPolicyConfig
import com.wifiguard.limiter.model.computeEffectiveSpeed
import com.wifiguard.limiter.model.formatDataSizeArabic
import com.wifiguard.limiter.model.formatSpeedArabic
import com.wifiguard.limiter.model.getInstalledLaunchableApps
import com.wifiguard.limiter.network.ClientGuardService
import com.wifiguard.limiter.network.LocalLanClient
import com.wifiguard.limiter.network.NetworkScanner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class WifiGuardVpnService : VpnService() {

    private val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private lateinit var prefs: PreferencesManager
    private lateinit var dnsHandler: DnsPacketHandler
    private val tokenBucket = TokenBucket()
    @Volatile private var tunInterface: ParcelFileDescriptor? = null
    private var proxyServer: LocalHttpProxyServer? = null
    private var legacyForwarder: Tun2NetForwarder? = null
    private var packetLoopJob: Job? = null
    private var screenReceiver: BroadcastReceiver? = null
    private val forceRebuild = AtomicBoolean(false)

    // Exact screen-off timestamp so unlocking wakes internet in < 100ms instead of waiting 60s
    private val screenOffStartElapsedMs = AtomicLong(0L)

    private var currentBlockAll: Boolean = false
    private var currentProxyActive: Boolean = false
    private var currentUseLocalDns: Boolean = false
    private var currentMeteredMode: Boolean = false
    private var currentWhitelist: Set<String> = emptySet()
    private var currentBlockedApps: Set<String> = emptySet()
    private var lastWifiTrafficBytes: Long = -1L
    private var lastTrafficElapsedMs: Long = 0L
    @Volatile private var cachedPolicy = SpeedPolicyConfig()
    private val pendingUsedBytes = AtomicLong(0L)
    private val pendingSavedBytes = AtomicLong(0L)
    private var lastFlushMs: Long = 0L
    private var lastAppStatsQueryMs: Long = 0L
    private var lastFullAnalyticsQueryMs: Long = 0L
    private var cachedAppStatsPeriodKey: String = ""
    private var lastWifiSigCheckMs: Long = 0L
    private var cachedWifiSig: String = ""
    private var cachedAppUsedMbMap: Map<String, Long> = emptyMap()
    private var lastNotifTitle: String = ""
    private var lastNotifText: String = ""
    private var milestonePeriodKey: String = ""
    private val sentMilestones = mutableSetOf<String>()

    // Rolling 60-second samples for Burst Guard (works on ALL Android versions!)
    private val burstSamples = ArrayDeque<Pair<Long, Long>>()
    private var burstDetectedUntilElapsedMs: Long = 0L

    companion object {
        const val CHANNEL_ID = "wifiguard_vpn_channel"
        const val USAGE_ALERT_CHANNEL_ID = "wifiguard_usage_alerts_channel"
        const val NOTIF_ID = 2001
        const val ACTION_STOP = "com.wifiguard.limiter.STOP_VPN"
        const val ACTION_RESTART = "com.wifiguard.limiter.RESTART_VPN"

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

        private val _liveSpeedMbps = MutableStateFlow(0.0)
        val liveSpeedMbps: StateFlow<Double> = _liveSpeedMbps

        private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
        fun notifyConfigChanged() {
            wakeSignal.trySend(Unit)
        }

        fun start(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, WifiGuardVpnService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        fun restart(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, WifiGuardVpnService::class.java).apply {
                    action = ACTION_RESTART
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                appContext.stopService(Intent(appContext, WifiGuardVpnService::class.java))
            }
        }
    }

    private fun startForegroundSafe(title: String, text: String) {
        val notif = buildNotification(text, title)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(applicationContext)
        dnsHandler = DnsPacketHandler(this)
        NetworkScanner.ensureWifiInfoTracker(applicationContext)
        createNotificationChannel()
        startForegroundSafe("التوفير الذكي نشط", "جاري تشغيل حماية الاتصال...")

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) {
            screenOffStartElapsedMs.set(SystemClock.elapsedRealtime())
        }
        registerScreenReceiver()

        proxyServer = LocalHttpProxyServer(this@WifiGuardVpnService, tokenBucket).also { it.start() }
        startMonitoringLoop()
    }

    private fun registerScreenReceiver() {
        runCatching {
            val rx = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_ON -> {
                            screenOffStartElapsedMs.set(0L)
                            wakeSignal.trySend(Unit)
                        }
                        Intent.ACTION_SCREEN_OFF -> {
                            screenOffStartElapsedMs.compareAndSet(0L, SystemClock.elapsedRealtime())
                        }
                    }
                }
            }
            screenReceiver = rx
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(this, rx, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            _isRunning.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundSafe(
            lastNotifTitle.ifBlank { "التوفير الذكي نشط" },
            lastNotifText.ifBlank { "جاري تشغيل حماية الاتصال..." }
        )
        if (intent?.action == ACTION_RESTART) {
            forceRebuild.set(true)
            wakeSignal.trySend(Unit)
        }
        return START_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        _isRunning.value = false
        _connectionState.value = ConnectionState.PROTECTION_OFF
        _statusLabel.value = "الحماية متوقفة حالياً"
        serviceScope.launch {
            try {
                withTimeoutOrNull(3_000L) {
                    runCatching {
                        val role = prefs.roleFlow.first()
                        if (role == AppRole.CLIENT) {
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
                                    "تم إيقاف حماية الاتصال على جهاز $name",
                                    authToken = token
                                )
                            }
                        }
                    }
                }
            } finally {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        runCatching { screenReceiver?.let { unregisterReceiver(it) } }
        screenReceiver = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        packetLoopJob?.cancel()
        runCatching { legacyForwarder?.close() }
        legacyForwarder = null
        runCatching { proxyServer?.stop() }
        proxyServer = null

        val remUsed = pendingUsedBytes.getAndSet(0L)
        val remSaved = pendingSavedBytes.getAndSet(0L)
        if (remUsed > 0L || remSaved > 0L) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(2_000L) {
                        if (remUsed > 0L) prefs.addUsedBytes(remUsed)
                        if (remSaved > 0L) prefs.addSavedBytes(remSaved)
                    }
                }
            }
        }
        serviceScope.cancel()
    }

    private fun millisUntilNextMidnight(): Long {
        val now = LocalDateTime.now()
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val midMs = nextMidnight.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return (midMs - nowMs).coerceAtLeast(1_000L)
    }

    private fun readWifiOnlyTrafficBytes(): Long {
        val totalRx = TrafficStats.getTotalRxBytes()
        val totalTx = TrafficStats.getTotalTxBytes()
        if (totalRx == TrafficStats.UNSUPPORTED.toLong() || totalTx == TrafficStats.UNSUPPORTED.toLong()) {
            return -1L
        }
        val mobRx = TrafficStats.getMobileRxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
        val mobTx = TrafficStats.getMobileTxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
        return ((totalRx - mobRx).coerceAtLeast(0L)) + ((totalTx - mobTx).coerceAtLeast(0L))
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            var lastAdsCount = 0L
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager

            while (isActive) {
                runCatching {
                    // Roll over daily/weekly/monthly counters promptly at midnight even while idle/blocked
                    prefs.ensurePeriodRolledOverIfNeeded()

                    val nowElapsedMs = SystemClock.elapsedRealtime()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        _isAlwaysOnActive.value = runCatching { isAlwaysOn }.getOrDefault(false)
                    }

                    val cfg = prefs.getLocalLimitConfig()
                    cachedPolicy = cfg.speedPolicy
                    dnsHandler.upstreamDnsIp = cfg.speedPolicy.upstreamDnsIp
                    val onWifi = isOnWifi()
                    val isInteractive = powerManager?.isInteractive ?: true

                    if (isInteractive) {
                        screenOffStartElapsedMs.set(0L)
                    } else {
                        screenOffStartElapsedMs.compareAndSet(0L, nowElapsedMs)
                    }

                    val loopNowMs = System.currentTimeMillis()
                    val isClientOnHomeLan = ClientGuardService.isSupervisorReachable
                    val outsideTargetWifi = if (cfg.targetNetworkOnly && cfg.targetNetworkSig.isNotBlank() && onWifi && !isClientOnHomeLan) {
                        if (loopNowMs - lastWifiSigCheckMs >= 10_000L || cachedWifiSig.isBlank()) {
                            lastWifiSigCheckMs = loopNowMs
                            cachedWifiSig = NetworkScanner.getCurrentWifiSignature(applicationContext)
                                .ifBlank { NetworkScanner.getCurrentWifiSignatureActive(applicationContext) }
                        }
                        cachedWifiSig.isNotBlank() && !NetworkScanner.matchesNetworkSignature(cachedWifiSig, cfg.targetNetworkSig)
                    } else {
                        cachedWifiSig = ""
                        false
                    }

                    val mustRebuild = forceRebuild.getAndSet(false)

                    if (cfg.isPaused) {
                        _connectionState.value = ConnectionState.PAUSED
                        _statusLabel.value = "تم إيقاف الإنترنت مؤقتاً بواسطة المشرف"
                        _liveSpeedMbps.value = 0.0
                        tokenBucket.setRateBytesPerSec(0L)
                        proxyServer?.closeActiveConnections()
                        if (mustRebuild || !currentBlockAll || cfg.whitelist != currentWhitelist || tunInterface == null) {
                            establishTunnel(blockAll = true, blockedApps = emptySet(), enableProxy = false, whitelist = cfg.whitelist)
                        }
                        updateNotification("إيقاف مؤقت", "تم إيقاف الإنترنت مؤقتاً بواسطة المشرف")
                    } else if (outsideTargetWifi) {
                        lastWifiTrafficBytes = -1L
                        lastTrafficElapsedMs = 0L
                        _liveSpeedMbps.value = 0.0
                        _connectionState.value = ConnectionState.OUTSIDE_TARGET_WIFI
                        _statusLabel.value = "خارج الشبكة المحددة — بلا قيود"
                        tokenBucket.setUnlimited()
                        if (mustRebuild || currentBlockAll || currentBlockedApps.isNotEmpty() || currentProxyActive || cfg.whitelist != currentWhitelist || tunInterface == null) {
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
                            val currentWifiTotal = readWifiOnlyTrafficBytes()
                            if (currentWifiTotal >= 0L) {
                                if (lastWifiTrafficBytes >= 0L && lastTrafficElapsedMs > 0L) {
                                    val delta = (currentWifiTotal - lastWifiTrafficBytes).coerceAtLeast(0L)
                                    val elapsedSec = ((nowElapsedMs - lastTrafficElapsedMs).coerceAtLeast(1000L)) / 1000.0
                                    if (delta > 0L) {
                                        intervalBytes = delta
                                        pendingUsedBytes.addAndGet(delta)
                                        _liveSpeedMbps.value = (delta / 125_000.0) / elapsedSec
                                    } else {
                                        _liveSpeedMbps.value = 0.0
                                    }
                                }
                                lastWifiTrafficBytes = currentWifiTotal
                                lastTrafficElapsedMs = nowElapsedMs
                            }
                        } else {
                            lastWifiTrafficBytes = -1L
                            lastTrafficElapsedMs = 0L
                            _liveSpeedMbps.value = 0.0
                        }

                        // Real rolling 60-second Burst Guard across ALL Android versions
                        if (intervalBytes > 0L) {
                            burstSamples.addLast(nowElapsedMs to intervalBytes)
                        }
                        while (burstSamples.isNotEmpty() && nowElapsedMs - burstSamples.first().first > 60_000L) {
                            burstSamples.removeFirst()
                        }
                        val rollingMinuteBytes = burstSamples.sumOf { it.second }
                        if (cfg.speedPolicy.burstGuardEnabled &&
                            rollingMinuteBytes >= cfg.speedPolicy.burstThresholdMbPerMin * 1024L * 1024L
                        ) {
                            burstDetectedUntilElapsedMs = nowElapsedMs + 120_000L
                        }
                        val burstDetected = cfg.speedPolicy.burstGuardEnabled && nowElapsedMs < burstDetectedUntilElapsedMs

                        val offStart = screenOffStartElapsedMs.get()
                        val isScreenOffIdle = !isInteractive && offStart > 0L && (nowElapsedMs - offStart >= 60_000L)

                        // Include unflushed pendingUsedBytes so quota enforcement reacts immediately without lag
                        val pendingMb = pendingUsedBytes.get() / (1024L * 1024L)
                        val usedMb = prefs.getUsedMbActivePeriod() + pendingMb
                        val windowUsedMb = if (cfg.speedPolicy.windowPaceEnabled) {
                            prefs.getWindowUsedMb(cfg.speedPolicy.windowHours) + pendingMb
                        } else 0L

                        val effective = computeEffectiveSpeed(
                            usedMb = usedMb,
                            limitMb = cfg.limitMb,
                            policy = cfg.speedPolicy,
                            forcedBySupervisor = cfg.forceQuotaSaver,
                            globalSpeedCapMbPerSec = cfg.globalSpeedCapMbps,
                            burstDetected = burstDetected,
                            isScreenOffIdle = isScreenOffIdle,
                            windowUsedMb = windowUsedMb
                        )

                        checkAndNotifyUsageMilestones(usedMb, cfg.limitMb, cfg.limitPeriod, cfg.speedPolicy, windowUsedMb)

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
                        if (savedDelta > 0L) pendingSavedBytes.addAndGet(savedDelta)

                        val nowMs = System.currentTimeMillis()
                        val curPendingUsed = pendingUsedBytes.get()
                        val curPendingSaved = pendingSavedBytes.get()
                        if (curPendingUsed >= 1024L * 1024L ||
                            curPendingSaved >= 1024L * 1024L ||
                            (nowMs - lastFlushMs >= 15_000L && (curPendingUsed > 0L || curPendingSaved > 0L))
                        ) {
                            val flushUsed = pendingUsedBytes.getAndSet(0L)
                            val flushSaved = pendingSavedBytes.getAndSet(0L)
                            if (flushUsed > 0L) prefs.addUsedBytes(flushUsed)
                            if (flushSaved > 0L) prefs.addSavedBytes(flushSaved)
                            lastFlushMs = nowMs
                        }

                        // Clear cached per-app usage immediately on period rollover so blocked apps unblock at midnight/new period
                        val curPeriodKey = "${cfg.limitPeriod.name}:${computePeriodKey(cfg.limitPeriod)}"
                        if (cachedAppStatsPeriodKey != curPeriodKey) {
                            cachedAppStatsPeriodKey = curPeriodKey
                            cachedAppUsedMbMap = emptyMap()
                            lastFullAnalyticsQueryMs = 0L
                            lastAppStatsQueryMs = 0L
                        }

                        val appQuotas = cfg.speedPolicy.appQuotaPercents
                        if (onWifi && isInteractive) {
                            if (nowMs - lastFullAnalyticsQueryMs >= 300_000L || lastFullAnalyticsQueryMs == 0L) {
                                lastFullAnalyticsQueryMs = nowMs
                                lastAppStatsQueryMs = nowMs
                                val allPkgs = (appQuotas.keys + getInstalledLaunchableApps(applicationContext).map { it.packageName }).toSet()
                                val queried = AppUsageStatsHelper.getWifiUsageMbByPackage(applicationContext, allPkgs, cfg.limitPeriod)
                                cachedAppUsedMbMap = queried
                                prefs.setAppUsedMbMap(queried, cfg.limitPeriod)
                            } else if (appQuotas.isNotEmpty() && nowMs - lastAppStatsQueryMs >= 30_000L) {
                                lastAppStatsQueryMs = nowMs
                                val queried = AppUsageStatsHelper.getWifiUsageMbByPackage(applicationContext, appQuotas.keys, cfg.limitPeriod)
                                val updatedMap = cachedAppUsedMbMap.toMutableMap()
                                appQuotas.keys.forEach { pkg ->
                                    val v = queried[pkg]
                                    if (v != null && v > 0L) updatedMap[pkg] = v else updatedMap.remove(pkg)
                                }
                                cachedAppUsedMbMap = updatedMap
                                prefs.setAppUsedMbMap(updatedMap, cfg.limitPeriod)
                            }
                        }

                        val overQuotaApps = appQuotas.filter { (pkg, pct) ->
                            val allowedMb = ((cfg.limitMb.toDouble() / 100.0) * pct).toLong().coerceAtLeast(1L)
                            (cachedAppUsedMbMap[pkg] ?: 0L) >= allowedMb && !cfg.whitelist.contains(pkg)
                        }.keys

                        tokenBucket.setRateBytesPerSec(effective.targetBytesPerSec)
                        val mediaSaverOn = effective.saverActive && cfg.speedPolicy.mediaCdnSaverEnabled
                        val blockUpdatesOn = effective.saverActive && cfg.speedPolicy.blockAppUpdatesInSaver
                        val blockDohOn = effective.saverActive && cfg.speedPolicy.blockDohDotEnabled
                        proxyServer?.updateMediaSaverConfig(mediaSaverOn, cfg.speedPolicy.mediaCdnSpeedMbps, blockUpdatesOn, blockDohOn)

                        val blockAllNow = effective.state == ConnectionState.BLOCKED || effective.state == ConnectionState.IDLE_FROZEN
                        if (blockAllNow) {
                            proxyServer?.closeActiveConnections()
                        }
                        val proxyNeeded = !blockAllNow && overQuotaApps.isEmpty() && (effective.targetBytesPerSec < Long.MAX_VALUE || mediaSaverOn || blockUpdatesOn || blockDohOn)
                        val useLegacyNow = proxyNeeded && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                        val useLocalDnsNow = (BuildConfig.ENABLE_DNS_ADBLOCK && !isSystemPrivateDnsActive()) || useLegacyNow
                        val meteredNow = effective.saverActive && cfg.speedPolicy.meteredWifiSaverEnabled

                        legacyForwarder?.let { fwd ->
                            fwd.burstGuardEnabled = cfg.speedPolicy.burstGuardEnabled
                            fwd.burstThresholdBytesPerMin = cfg.speedPolicy.burstThresholdMbPerMin * 1024L * 1024L
                            fwd.burstCapMbps = cfg.speedPolicy.burstCapSpeedMbPerSec
                            fwd.flags = Tun2NetForwarder.PacketPolicyFlags(
                                blockAll = blockAllNow,
                                sinkholeEnabled = BuildConfig.ENABLE_DNS_ADBLOCK && cfg.speedPolicy.dnsSinkholeEnabled,
                                cacheEnabled = cfg.speedPolicy.dnsCacheEnabled,
                                blockDohDot = cfg.speedPolicy.blockDohDotEnabled,
                                blockQuicUdp443 = cfg.speedPolicy.blockQuicUdp443Enabled
                            )
                        }

                        if (mustRebuild ||
                            blockAllNow != currentBlockAll ||
                            overQuotaApps != currentBlockedApps ||
                            proxyNeeded != currentProxyActive ||
                            useLocalDnsNow != currentUseLocalDns ||
                            meteredNow != currentMeteredMode ||
                            cfg.whitelist != currentWhitelist ||
                            tunInterface == null
                        ) {
                            establishTunnel(
                                blockAll = blockAllNow,
                                blockedApps = overQuotaApps,
                                enableProxy = proxyNeeded,
                                meteredMode = meteredNow,
                                whitelist = cfg.whitelist
                            )
                        }

                        val summary = "${cfg.limitPeriod.arabicLabel}: ${formatDataSizeArabic(usedMb)} من ${formatDataSizeArabic(cfg.limitMb)}"
                        updateNotification(summary, effective.statusLabelArabic)
                    }
                }

                val interactive = powerManager?.isInteractive ?: true
                val baseSleepMs = if (interactive) 5_000L else 60_000L
                val sleepMs = minOf(baseSleepMs, millisUntilNextMidnight())
                withTimeoutOrNull(sleepMs) { wakeSignal.receive() }
            }
        }
    }

    private fun establishTunnel(
        blockAll: Boolean,
        blockedApps: Set<String>,
        enableProxy: Boolean,
        meteredMode: Boolean = false,
        whitelist: Set<String>
    ) {
        runCatching { tunInterface?.close() }
        tunInterface = null
        packetLoopJob?.cancel()
        runCatching { legacyForwarder?.close() }
        legacyForwarder = null

        currentBlockAll = blockAll
        currentBlockedApps = blockedApps
        currentProxyActive = enableProxy
        currentMeteredMode = meteredMode
        currentWhitelist = whitelist

        val lockdownOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { isLockdownEnabled }.getOrDefault(false)
        } else false
        val useLegacyTunThrottle = enableProxy && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        val useLocalDns = (BuildConfig.ENABLE_DNS_ADBLOCK && !isSystemPrivateDnsActive()) || useLegacyTunThrottle
        currentUseLocalDns = useLocalDns

        val builder = Builder()
            .setSession("No Quota")
            .addAddress("10.0.0.2", 32)
            .setMtu(1400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { builder.setMetered(meteredMode) }
        }
        if (useLocalDns) {
            builder.addDnsServer("10.0.0.1")
        }

        if (blockAll) {
            builder.addAddress("fd00:1:fd00::1", 128)
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            runCatching { builder.addDisallowedApplication(packageName) }
            if (!lockdownOn) { whitelist.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } } }
        } else if (blockedApps.isNotEmpty()) {
            builder.addAddress("fd00:1:fd00::1", 128)
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            blockedApps.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
        } else if (useLegacyTunThrottle) {
            builder.addAddress("fd00:1:fd00::1", 128)
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            runCatching { builder.addDisallowedApplication(packageName) }
            if (!lockdownOn) { whitelist.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } } }
        } else {
            if (useLocalDns) {
                builder.addRoute("10.0.0.1", 32)
            }
            builder.addRoute("10.254.0.0", 16)
            if (enableProxy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val activeProxyPort = proxyServer?.boundPort ?: LocalHttpProxyServer.PROXY_PORT
                runCatching {
                    builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", activeProxyPort))
                }
            }
            runCatching { builder.addDisallowedApplication(packageName) }
            if (!lockdownOn) { whitelist.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } } }
        }

        val pfd = runCatching { builder.establish() }.getOrNull()
        tunInterface = pfd
        if (pfd == null) {
            // Do not falsely report protection as active when VPN consent was revoked or establish() failed
            _isRunning.value = false
            _connectionState.value = ConnectionState.PROTECTION_OFF
            _statusLabel.value = "الحماية متوقفة — يرجى إعادة التفعيل"
            return
        }
        _isRunning.value = true

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
                    blockDohDot = policy.blockDohDotEnabled,
                    blockQuicUdp443 = policy.blockQuicUdp443Enabled
                )
                packetLoopJob = serviceScope.launch {
                    val buffer = ByteArray(32767)
                    try {
                        while (isActive) {
                            val len = runCatching { input.read(buffer) }.getOrElse { -1 }
                            if (len < 0) break
                            if (len > 0) runCatching { fwd.processTunPacket(buffer, len) }
                        }
                    } finally {
                        runCatching { input.close() }
                        runCatching { output.close() }
                    }
                }
            } else {
                runCatching { input.close() }
                runCatching { output.close() }
            }
            return
        }

        val dnsQueue = Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        packetLoopJob = serviceScope.launch {
            val workers = List(8) {
                launch {
                    for (pktCopy in dnsQueue) {
                        val policy = cachedPolicy
                        val resp = runCatching {
                            dnsHandler.handlePacket(
                                packet = pktCopy,
                                length = pktCopy.size,
                                sinkholeEnabled = policy.dnsSinkholeEnabled,
                                cacheEnabled = policy.dnsCacheEnabled,
                                blockDohDot = policy.blockDohDotEnabled,
                                blockQuicUdp443 = false,
                                stripAaaa = false
                            )
                        }.getOrNull()
                        if (resp != null) {
                            synchronized(output) { runCatching { output.write(resp) } }
                        }
                    }
                }
            }
            val buffer = ByteArray(32767)
            try {
                while (isActive) {
                    val len = runCatching { input.read(buffer) }.getOrElse { -1 }
                    if (len < 0) break
                    if (len > 0 && !blockAll && blockedApps.isEmpty() && useLocalDns) {
                        dnsQueue.trySend(buffer.copyOf(len))
                    }
                }
            } finally {
                dnsQueue.close()
                workers.forEach { it.cancel() }
                runCatching { input.close() }
                runCatching { output.close() }
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
        return runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            // Check active network (or underlying Wi-Fi when VPN is active) rather than any associated Wi-Fi
            val active = cm.activeNetwork
            val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
            if (activeCaps != null && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return false
            }
            @Suppress("DEPRECATION")
            cm.allNetworks.any { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }.getOrDefault(false)
    }

    private fun computePeriodKey(period: LimitPeriod): String {
        val today = LocalDate.now()
        return when (period) {
            LimitPeriod.DAILY -> today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US))
            LimitPeriod.WEEKLY -> {
                val wf = WeekFields.of(Locale.getDefault())
                String.format(Locale.US, "%04d-W%02d", today.get(wf.weekBasedYear()), today.get(wf.weekOfWeekBasedYear()))
            }
            LimitPeriod.MONTHLY -> today.format(DateTimeFormatter.ofPattern("yyyy-MM", Locale.US))
        }
    }

    private fun checkAndNotifyUsageMilestones(
        usedMb: Long,
        limitMb: Long,
        period: LimitPeriod,
        policy: SpeedPolicyConfig,
        windowUsedMb: Long
    ) {
        if (limitMb <= 0L) return
        val key = "${period.name}:${computePeriodKey(period)}"
        if (milestonePeriodKey != key) {
            milestonePeriodKey = key
            sentMilestones.clear()
        }
        val graceMb = ((limitMb.toDouble() / 100.0) * policy.graceExtraPercent.coerceIn(0, 50)).toLong().coerceAtLeast(0L)
        val hardCapMb = if (limitMb > Long.MAX_VALUE - graceMb) Long.MAX_VALUE else limitMb + graceMb
        val pct = ((usedMb.coerceAtLeast(0L) * 100.0) / limitMb.toDouble()).toInt().coerceAtLeast(0)
        val remPct = (100 - pct).coerceAtLeast(0)

        if (usedMb >= hardCapMb && sentMilestones.add("hard_cap")) {
            sendUsageAlertNotification(
                id = 3110,
                title = "انتهى الرصيد بالكامل",
                body = "تم إيقاف الإنترنت مؤقتاً — التطبيقات المسموحة فقط تعمل حتى تجديد الفترة."
            )
        } else if (policy.graceExtraPercent > 0 && usedMb >= limitMb && usedMb < hardCapMb && sentMilestones.add("100")) {
            sendUsageAlertNotification(
                id = 3100,
                title = "انتهت باقتك الأساسية",
                body = "استهلكت 100% من الحد المحدد. بدأ الرصيد الإضافي (+${policy.graceExtraPercent}%) بسرعة ${formatSpeedArabic(policy.graceSpeedMbPerSec)}."
            )
        } else if (remPct <= policy.finalPhaseRemainingPercent && pct < 100 && sentMilestones.add("final")) {
            sendUsageAlertNotification(
                id = 3080,
                title = "تنبيه: متبقي $remPct% فقط من باقتك",
                body = "تم تفعيل السرعة النهائية (${formatSpeedArabic(policy.finalPhaseSpeedMbPerSec)}) للحفاظ على المتبقي."
            )
        } else if (pct >= 50 && sentMilestones.add("50")) {
            sendUsageAlertNotification(
                id = 3050,
                title = "تم استهلاك 50% من باقتك",
                body = "استهلكت نصف الحد المحدد (${formatDataSizeArabic(usedMb)} من ${formatDataSizeArabic(limitMb)})."
            )
        }

        if (policy.windowPaceEnabled) {
            val windowCapMb = ((limitMb.toDouble() / 100.0) * policy.windowMaxPercent).toLong().coerceAtLeast(10L)
            val winSlot = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) / policy.windowHours.coerceIn(1, 24)
            if (windowUsedMb >= windowCapMb && sentMilestones.add("win_${LocalDate.now()}_$winSlot")) {
                sendUsageAlertNotification(
                    id = 3200,
                    title = "توزيع الاستهلاك نشط",
                    body = "وصلت إلى ${policy.windowMaxPercent}% خلال ${policy.windowHours} ساعات — تم تهدئة السرعة مؤقتاً إلى ${formatSpeedArabic(policy.windowSlowSpeedMbps)}."
                )
            }
        }
    }

    private fun sendUsageAlertNotification(id: Int, title: String, body: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val pi = PendingIntent.getActivity(this, id, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(this, USAGE_ALERT_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(id, notif)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "حماية الاتصال",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    USAGE_ALERT_CHANNEL_ID,
                    "إشعارات استهلاك الباقة",
                    NotificationManager.IMPORTANCE_HIGH
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
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildNotification(text, title))
        }
    }
}

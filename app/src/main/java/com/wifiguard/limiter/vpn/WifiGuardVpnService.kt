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
import com.wifiguard.limiter.data.AppUsageStatsHelper
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.computeEffectiveSpeed
import com.wifiguard.limiter.model.formatDataSizeArabic
import com.wifiguard.limiter.network.ClientGuardService
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
    private var currentMeteredMode: Boolean = false
    private var currentWhitelist: Set<String> = emptySet()
    private var currentBlockedApps: Set<String> = emptySet()
    private var lastTrafficBytes: Long = -1L
    private val lastAppUidBytes = mutableMapOf<String, Long>()
    @Volatile private var cachedPolicy = com.wifiguard.limiter.model.SpeedPolicyConfig()
    private var pendingUsedBytes: Long = 0L
    private var pendingSavedBytes: Long = 0L
    private var lastFlushMs: Long = 0L
    private var lastAppStatsQueryMs: Long = 0L
    private var cachedAppUsedMbMap: Map<String, Long> = emptyMap()
    private var lastNotifTitle: String = ""
    private var lastNotifText: String = ""
    private var milestoneDay: String = ""
    private val sentMilestones = mutableSetOf<String>()

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

        fun restart(context: Context) {
            runCatching {
                val intent = Intent(context, WifiGuardVpnService::class.java).apply {
                    action = ACTION_RESTART
                }
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
        NetworkScanner.ensureWifiInfoTracker(applicationContext)
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
        if (intent?.action == ACTION_RESTART) {
            serviceScope.launch {
                runCatching {
                    establishTunnel(
                        blockAll = currentBlockAll,
                        blockedApps = currentBlockedApps,
                        enableProxy = currentProxyActive,
                        meteredMode = currentMeteredMode,
                        whitelist = currentWhitelist
                    )
                }
            }
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

                    val isClientOnHomeLan = ClientGuardService.isSupervisorReachable
                    val outsideTargetWifi = cfg.targetNetworkOnly &&
                        cfg.targetNetworkSig.isNotBlank() &&
                        onWifi &&
                        !isClientOnHomeLan &&
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
                                    val secs = if (powerManager.isInteractive) 1.0 else 5.0
                                    _liveSpeedMbps.value = (delta / 125_000.0) / secs
                                }
                            }
                            lastTrafficBytes = currentTotal
                        } else {
                            lastTrafficBytes = -1L
                            _liveSpeedMbps.value = 0.0
                        }

                        if (!powerManager.isInteractive) {
                            screenOffSeconds += 5
                        } else {
                            screenOffSeconds = 0
                        }
                        val isScreenOffIdle = screenOffSeconds >= 60

                        val usedMb = prefs.getUsedMbActivePeriod()
                        val windowUsedMb = if (cfg.speedPolicy.windowPaceEnabled) {
                            prefs.getWindowUsedMb(cfg.speedPolicy.windowHours)
                        } else 0L

                        val effective = computeEffectiveSpeed(
                            usedMb = usedMb,
                            limitMb = cfg.limitMb,
                            policy = cfg.speedPolicy,
                            forcedBySupervisor = cfg.forceQuotaSaver,
                            globalSpeedCapMbPerSec = cfg.globalSpeedCapMbps,
                            burstDetected = false,
                            isScreenOffIdle = isScreenOffIdle,
                            windowUsedMb = windowUsedMb
                        )

                        checkAndNotifyUsageMilestones(usedMb, cfg.limitMb, cfg.speedPolicy, windowUsedMb)

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

                        // Accurate Per-App Wi-Fi Usage via NetworkStatsManager (works on Android 8-15!)
                        val appQuotas = cfg.speedPolicy.appQuotaPercents
                        if (onWifi && (nowMs - lastAppStatsQueryMs >= 10_000L || lastAppStatsQueryMs == 0L)) {
                            lastAppStatsQueryMs = nowMs
                            val allPkgs = (appQuotas.keys + com.wifiguard.limiter.model.getInstalledLaunchableApps(applicationContext).map { it.packageName }).toSet()
                            val queried = AppUsageStatsHelper.getWifiUsageMbByPackage(applicationContext, allPkgs, cfg.limitPeriod)
                            if (queried.isNotEmpty()) {
                                cachedAppUsedMbMap = queried
                                prefs.setAppUsedMbMap(queried, cfg.limitPeriod)
                            }
                        }
                        val overQuotaApps = appQuotas.filter { (pkg, pct) ->
                            val allowedMb = ((cfg.limitMb * pct) / 100L).coerceAtLeast(1L)
                            (cachedAppUsedMbMap[pkg] ?: 0L) >= allowedMb && !cfg.whitelist.contains(pkg)
                        }.keys

                        tokenBucket.setRateBytesPerSec(effective.targetBytesPerSec)
                        val mediaSaverOn = effective.saverActive && cfg.speedPolicy.mediaCdnSaverEnabled
                        val blockUpdatesOn = effective.saverActive && cfg.speedPolicy.blockAppUpdatesInSaver
                        proxyServer?.updateMediaSaverConfig(mediaSaverOn, cfg.speedPolicy.mediaCdnSpeedMbps, blockUpdatesOn)
                        val blockAllNow = effective.state == ConnectionState.BLOCKED || effective.state == ConnectionState.IDLE_FROZEN
                        val proxyNeeded = !blockAllNow && overQuotaApps.isEmpty() && (effective.targetBytesPerSec < Long.MAX_VALUE || mediaSaverOn || blockUpdatesOn)
                        val useLocalDnsNow = BuildConfig.ENABLE_DNS_ADBLOCK && !isSystemPrivateDnsActive()
                        val meteredNow = effective.saverActive && cfg.speedPolicy.meteredWifiSaverEnabled

                        if (blockAllNow != currentBlockAll ||
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

                delay(if (powerManager.isInteractive) 1000L else 5000L)
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
        packetLoopJob?.cancel()
        runCatching { legacyForwarder?.close() }
        legacyForwarder = null
        runCatching { tunInterface?.close() }
        currentBlockAll = blockAll
        currentBlockedApps = blockedApps
        currentProxyActive = enableProxy
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
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            runCatching { builder.addDisallowedApplication(packageName) }
            if (!lockdownOn) { whitelist.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } } }
        } else if (blockedApps.isNotEmpty()) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            blockedApps.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
        } else if (useLegacyTunThrottle) {
            // Android 8/9 (API < 29, e.g. Oppo A1k) does not support VpnService.setHttpProxy -> use Tun2NetForwarder + TokenBucket
            builder.addRoute("0.0.0.0", 0)
            runCatching { builder.addDisallowedApplication(packageName) }
            if (!lockdownOn) { whitelist.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } } }
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
            if (!lockdownOn) { whitelist.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } } }
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

    private fun checkAndNotifyUsageMilestones(
        usedMb: Long,
        limitMb: Long,
        policy: com.wifiguard.limiter.model.SpeedPolicyConfig,
        windowUsedMb: Long
    ) {
        if (limitMb <= 0L) return
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        if (milestoneDay != today) {
            milestoneDay = today
            sentMilestones.clear()
        }
        val pct = ((usedMb * 100) / limitMb).toInt()
        val remPct = (100 - pct).coerceAtLeast(0)

        if (pct >= 100 && sentMilestones.add("100")) {
            sendUsageAlertNotification(
                id = 3100,
                title = "انتهت باقتك الأساسية",
                body = "استهلكت 100% من الحد المحدد. بدأ الرصيد الإضافي (+${policy.graceExtraPercent}%) بسرعة ${com.wifiguard.limiter.model.formatSpeedArabic(policy.graceSpeedMbPerSec)}."
            )
        } else if (remPct <= policy.finalPhaseRemainingPercent && pct < 100 && sentMilestones.add("final")) {
            sendUsageAlertNotification(
                id = 3080,
                title = "تنبيه: متبقي $remPct% فقط من باقتك",
                body = "تم تفعيل السرعة النهائية (${com.wifiguard.limiter.model.formatSpeedArabic(policy.finalPhaseSpeedMbPerSec)}) للحفاظ على المتبقي."
            )
        } else if (pct >= 50 && pct < 80 && sentMilestones.add("50")) {
            sendUsageAlertNotification(
                id = 3050,
                title = "تم استهلاك 50% من باقتك",
                body = "استهلكت نصف الحد المحدد (${formatDataSizeArabic(usedMb)} من ${formatDataSizeArabic(limitMb)})."
            )
        }

        if (policy.windowPaceEnabled) {
            val windowCapMb = ((limitMb * policy.windowMaxPercent) / 100L).coerceAtLeast(10L)
            if (windowUsedMb >= windowCapMb && sentMilestones.add("win_${java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)}")) {
                sendUsageAlertNotification(
                    id = 3200,
                    title = "توزيع الاستهلاك نشط",
                    body = "وصلت إلى ${policy.windowMaxPercent}% خلال ${policy.windowHours} ساعات — تم تهدئة السرعة مؤقتاً إلى ${com.wifiguard.limiter.model.formatSpeedArabic(policy.windowSlowSpeedMbps)}."
                )
            }
        }
    }

    private fun sendUsageAlertNotification(id: Int, title: String, body: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
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
            val nm = getSystemService(NotificationManager::class.java)
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
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text, title))
        }
    }
}

package com.wifiguard.limiter.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wifiguard.limiter.MainActivity
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.InstalledAppItem
import com.wifiguard.limiter.model.ManagedDevice
import com.wifiguard.limiter.model.TamperAlert
import com.wifiguard.limiter.model.optCleanString
import com.wifiguard.limiter.model.sanitizeFinite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SupervisorServerService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private lateinit var prefs: PreferencesManager
    @Volatile private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val alertedOfflineDevices = ConcurrentHashMap.newKeySet<String>()
    private val alertedMacCheatDevices = ConcurrentHashMap.newKeySet<String>()
    private val portClosedStrikes = ConcurrentHashMap<String, Int>()
    private val vpnOffFirstSeenMs = ConcurrentHashMap<String, Long>()
    private val recentTamperByCategory = ConcurrentHashMap<String, Long>()

    companion object {
        const val CHANNEL_ID = "wifiguard_supervisor_channel"
        const val ALERT_CHANNEL_ID = "wifiguard_alerts_channel"
        const val NOTIF_ID = 1001
        private const val MAX_HEADER_LINE_BYTES = 8192
        private const val MAX_HEADERS = 64
        private const val MAX_BODY_BYTES = 512 * 1024
        private const val MAX_HISTORY_DAYS = 90
        private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

        fun start(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, SupervisorServerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(Intent(context.applicationContext, SupervisorServerService::class.java))
            }
        }

        fun getLocalWifiIp(context: Context): String {
            return NetworkScanner.getLocalWifiIp(context).ifBlank { "غير متصل بالواي فاي" }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(applicationContext)
        createNotificationChannels()
        runCatching {
            val notif = buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }
        // Avoid holding permanent LOW_LATENCY WifiLock to preserve Supervisor battery
        registerNsdService()
        startHttpServer()
        registerSupervisorWifiReconnect()
        startOfflineAndMacCheatWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            wifiCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(it)
            }
        }
        wifiCallback = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        unregisterNsdService()
        serviceScope.cancel()
    }

    @Synchronized
    private fun unregisterNsdService() {
        runCatching {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        }
        registrationListener = null
    }

    @Synchronized
    private fun registerNsdService() {
        unregisterNsdService()
        nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "WifiGuardSupervisor"
            serviceType = LocalLanClient.SERVICE_TYPE
            port = LocalLanClient.DEFAULT_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun startHttpServer() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val server = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(LocalLanClient.DEFAULT_PORT))
                    }
                    serverSocket = server
                    try {
                        while (isActive && !server.isClosed) {
                            val client = runCatching { server.accept() }.getOrNull() ?: break
                            launch { handleClient(client) }
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

    private suspend fun handleClient(socket: Socket) {
        val remoteIp = (socket.inetAddress?.hostAddress ?: "").removePrefix("::ffff:")
        socket.use { s ->
            runCatching {
                s.soTimeout = 5000
                val rawIn = s.getInputStream()
                fun readHttpLine(): String? {
                    val lineBytes = ByteArrayOutputStream()
                    while (lineBytes.size() < MAX_HEADER_LINE_BYTES) {
                        val b = rawIn.read()
                        if (b == -1) return if (lineBytes.size() == 0) null else lineBytes.toString("UTF-8")
                        if (b == '\n'.code) return lineBytes.toString("UTF-8")
                        if (b != '\r'.code) lineBytes.write(b)
                    }
                    return null
                }

                val requestLine = readHttpLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val path = parts[1]

                var contentLength = 0
                var authHeader: String? = null
                var headerCount = 0
                while (headerCount < MAX_HEADERS) {
                    val header = readHttpLine() ?: break
                    if (header.isEmpty()) break
                    headerCount++
                    val lower = header.lowercase()
                    when {
                        lower.startsWith("content-length:") -> {
                            contentLength = header.substringAfter(":").trim().toIntOrNull() ?: -1
                        }
                        lower.startsWith("authorization:") -> {
                            authHeader = header.substringAfter(":").trim()
                        }
                    }
                }

                if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                    sendHttpJson(s, 413, "Payload Too Large", JSONObject().put("ok", false).put("error", "Payload too large"))
                    return
                }

                val bodyBytes = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = rawIn.read(bodyBytes, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                val bodyJson = if (totalRead > 0) {
                    runCatching { JSONObject(String(bodyBytes, 0, totalRead, Charsets.UTF_8)) }.getOrElse {
                        sendHttpJson(s, 400, "Bad Request", JSONObject().put("ok", false).put("error", "JSON غير صالح"))
                        return
                    }
                } else JSONObject()

                val (statusCode, statusText, responseJson) = when (path) {
                    "/pair" -> Triple(200, "OK", handlePair(bodyJson, remoteIp))
                    "/heartbeat" -> handleHeartbeat(bodyJson, remoteIp, authHeader)
                    "/tamper" -> handleTamper(bodyJson, authHeader)
                    else -> Triple(404, "Not Found", JSONObject().put("ok", false).put("error", "مسار غير معروف"))
                }

                sendHttpJson(s, statusCode, statusText, responseJson)
            }
        }
    }

    private fun sendHttpJson(socket: Socket, code: Int, reason: String, json: JSONObject) {
        val respBytes = json.toString().toByteArray(Charsets.UTF_8)
        val headerBytes = (
            "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Content-Length: ${respBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val rawOut = socket.getOutputStream()
        rawOut.write(headerBytes)
        rawOut.write(respBytes)
        rawOut.flush()
    }

    private fun parseInstalledApps(body: JSONObject): List<InstalledAppItem> {
        val arr = body.optJSONArray("installedApps") ?: return emptyList()
        val list = mutableListOf<InstalledAppItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val pkg = obj.optCleanString("packageName", "").trim()
            val name = obj.optCleanString("appName", pkg).ifBlank { pkg }
            if (pkg.isNotBlank()) list.add(InstalledAppItem(pkg, name))
        }
        return list.distinctBy { it.packageName }
    }

    private fun pruneHistoryMap(map: MutableMap<String, Long>) {
        if (map.size <= MAX_HISTORY_DAYS) return
        val keep = map.keys.sortedDescending().take(MAX_HISTORY_DAYS).toSet()
        map.keys.retainAll(keep)
    }

    private suspend fun handlePair(body: JSONObject, remoteIp: String): JSONObject {
        val code = body.optCleanString("pairingCode", "").trim()
        val expectedCode = prefs.getOrCreatePairingCode()
        if (!MessageDigest.isEqual(code.toByteArray(Charsets.UTF_8), expectedCode.toByteArray(Charsets.UTF_8))) {
            return JSONObject().put("ok", false).put("error", "رمز الربط غير صحيح")
        }
        val deviceId = body.optCleanString("deviceId", "").trim()
        if (deviceId.isBlank()) {
            return JSONObject().put("ok", false).put("error", "معرف الجهاز مفقود")
        }
        val deviceName = body.optCleanString("deviceName", "جهاز جديد").ifBlank { "جهاز جديد" }

        val parsedApps = parseInstalledApps(body)
        val defaultPolicy = prefs.speedPolicyFlow.first().copy(appQuotaPercents = emptyMap())
        val defaultPeriod = prefs.limitPeriodFlow.first()
        val defaultLimitMb = prefs.dailyLimitMbFlow.first().coerceAtLeast(100L)

        var savedDevice: ManagedDevice? = null
        prefs.updateAllManagedDevices { list ->
            val existing = list.firstOrNull { it.deviceId == deviceId }
            val token = existing?.authToken?.ifBlank { null } ?: PreferencesManager.generateAuthToken()
            val device = existing?.copy(
                deviceName = deviceName,
                installedApps = if (parsedApps.isNotEmpty()) parsedApps else existing.installedApps,
                lastKnownIp = remoteIp.ifBlank { existing.lastKnownIp },
                lastSeenMillis = System.currentTimeMillis(),
                isOnline = true,
                pendingConfigPush = false,
                tamperWarning = null,
                authToken = token
            ) ?: ManagedDevice(
                deviceId = deviceId,
                deviceName = deviceName,
                limitPeriod = defaultPeriod,
                dailyLimitMb = defaultLimitMb,
                speedPolicy = defaultPolicy,
                installedApps = parsedApps,
                lastKnownIp = remoteIp,
                lastSeenMillis = System.currentTimeMillis(),
                isOnline = true,
                authToken = token
            )
            savedDevice = device
            if (existing != null) list.map { if (it.deviceId == deviceId) device else it } else list + device
        }
        val device = savedDevice ?: return JSONObject().put("ok", false).put("error", "خطأ داخلي")

        alertedOfflineDevices.remove(deviceId)
        alertedMacCheatDevices.remove(deviceId)

        val globalCap = prefs.globalSpeedCapMbpsFlow.first()
        val forceAll = prefs.forceQuotaSaverAllFlow.first()
        val targetOnly = prefs.targetNetworkOnlyFlow.first()
        val targetSig = prefs.targetNetworkSigFlow.first()

        return JSONObject().apply {
            put("ok", true)
            put("authToken", device.authToken)
            put("dailyLimitMb", device.dailyLimitMb)
            put("limitPeriod", device.limitPeriod.name)
            put("speedPolicy", device.speedPolicy.toJson())
            put("whitelistedPackages", JSONArray(device.whitelistedPackages))
            put("globalSpeedCapMbps", globalCap)
            put("forceQuotaSaver", forceAll || device.forceQuotaSaver)
            put("targetNetworkOnly", targetOnly)
            put("targetNetworkSig", targetSig)
            put("isPaused", device.isPaused)
        }
    }

    private suspend fun handleHeartbeat(
        body: JSONObject,
        remoteIp: String,
        authHeader: String?
    ): Triple<Int, String, JSONObject> {
        val deviceId = body.optCleanString("deviceId", "").trim()
        if (deviceId.isBlank()) {
            return Triple(400, "Bad Request", JSONObject().put("ok", false).put("error", "معرف الجهاز مفقود"))
        }
        val existingCheck = prefs.managedDevicesFlow.first().firstOrNull { it.deviceId == deviceId }
            ?: return Triple(401, "Unauthorized", JSONObject().put("ok", false).put("error", "جهاز غير مربوط"))

        if (!isTokenAuthorized(existingCheck.authToken, authHeader)) {
            return Triple(401, "Unauthorized", JSONObject().put("ok", false).put("error", "رمز مصادقة غير صالح"))
        }

        val deviceName = body.optCleanString("deviceName", existingCheck.deviceName).ifBlank { existingCheck.deviceName }
        val usedMb = body.optLong("usedMb", 0L).coerceAtLeast(0L)
        val usedMbToday = if (body.has("usedMbToday")) {
            body.optLong("usedMbToday", 0L).coerceAtLeast(0L)
        } else if (existingCheck.limitPeriod == com.wifiguard.limiter.model.LimitPeriod.DAILY) {
            usedMb
        } else {
            existingCheck.dailyUsageHistory[LocalDate.now().format(DAY_FMT)] ?: 0L
        }
        val savedMbToday = body.optLong("savedMbToday", 0L).coerceAtLeast(0L)
        val actualSpeedMbps = body.optDouble("actualSpeedMbps", 0.0).sanitizeFinite(0.0, 0.0, 10_000.0)
        val appUsageObj = body.optJSONObject("appUsageMb")
        val parsedAppUsage = mutableMapOf<String, Long>()
        appUsageObj?.keys()?.forEach { k ->
            if (k.isNotBlank()) parsedAppUsage[k] = appUsageObj.optLong(k, 0L).coerceAtLeast(0L)
        }
        val protectionActive = body.optBoolean("protectionActive", false)
        val uninstallProtectionActive = body.optBoolean("uninstallProtectionActive", false)
        val stateStr = body.optCleanString("connectionState", ConnectionState.SMART_SAVING.name)
        val connState = runCatching { ConnectionState.valueOf(stateStr) }.getOrDefault(ConnectionState.SMART_SAVING)
        val parsedApps = parseInstalledApps(body)

        var tamperMsg: String? = null
        val nowHbMs = System.currentTimeMillis()
        if (protectionActive) {
            vpnOffFirstSeenMs.remove(deviceId)
        } else if (existingCheck.protectionActive || vpnOffFirstSeenMs.containsKey(deviceId)) {
            val firstOff = vpnOffFirstSeenMs.putIfAbsent(deviceId, nowHbMs) ?: nowHbMs
            // Require >= 30s sustained VPN-off state to eliminate boot / service-restart race fake fires
            if (nowHbMs - firstOff >= 30_000L) {
                tamperMsg = "تم إيقاف حماية الاتصال على جهاز $deviceName"
                recordAndNotifyTamper(deviceId, deviceName, tamperMsg, category = "VPN_OFF")
            }
        }
        if (tamperMsg == null && existingCheck.uninstallProtectionActive && !uninstallProtectionActive) {
            tamperMsg = "تم إلغاء حماية الحذف على جهاز $deviceName"
            recordAndNotifyTamper(deviceId, deviceName, tamperMsg, category = "ADMIN_OFF")
        }

        val todayKey = LocalDate.now().format(DAY_FMT)
        var latestDevice: ManagedDevice = existingCheck
        prefs.updateManagedDevice(deviceId) { cur ->
            val token = cur.authToken.ifBlank { PreferencesManager.generateAuthToken() }
            val usageHist = cur.dailyUsageHistory.toMutableMap().apply {
                put(todayKey, usedMbToday)
                pruneHistoryMap(this)
            }
            val savedHist = cur.dailySavedHistory.toMutableMap().apply {
                put(todayKey, savedMbToday)
                pruneHistoryMap(this)
            }
            val merged = cur.copy(
                pendingConfigPush = false,
                deviceName = deviceName,
                usedMb = usedMb,
                savedMbToday = savedMbToday,
                actualSpeedMbps = actualSpeedMbps,
                appUsageMb = if (body.has("appUsageMb")) parsedAppUsage else if (usedMb == 0L) emptyMap() else cur.appUsageMb,
                dailyUsageHistory = usageHist,
                dailySavedHistory = savedHist,
                installedApps = if (parsedApps.isNotEmpty()) parsedApps else cur.installedApps,
                protectionActive = protectionActive,
                uninstallProtectionActive = uninstallProtectionActive,
                connectionState = if (!protectionActive) ConnectionState.PROTECTION_OFF else connState,
                lastKnownIp = remoteIp.ifBlank { cur.lastKnownIp },
                lastSeenMillis = System.currentTimeMillis(),
                isOnline = true,
                tamperWarning = tamperMsg ?: if (protectionActive) null else cur.tamperWarning,
                authToken = token
            )
            latestDevice = merged
            merged
        }

        alertedOfflineDevices.remove(deviceId)
        if (protectionActive) alertedMacCheatDevices.remove(deviceId)

        val globalCap = prefs.globalSpeedCapMbpsFlow.first()
        val forceAll = prefs.forceQuotaSaverAllFlow.first()
        val targetOnly = prefs.targetNetworkOnlyFlow.first()
        val targetSig = prefs.targetNetworkSigFlow.first()

        val resp = JSONObject().apply {
            put("ok", true)
            put("authToken", latestDevice.authToken)
            put("dailyLimitMb", latestDevice.dailyLimitMb)
            put("limitPeriod", latestDevice.limitPeriod.name)
            put("speedPolicy", latestDevice.speedPolicy.toJson())
            put("whitelistedPackages", JSONArray(latestDevice.whitelistedPackages))
            put("globalSpeedCapMbps", globalCap)
            put("forceQuotaSaver", forceAll || latestDevice.forceQuotaSaver)
            put("targetNetworkOnly", targetOnly)
            put("targetNetworkSig", targetSig)
            put("isPaused", latestDevice.isPaused)
        }
        return Triple(200, "OK", resp)
    }

    private suspend fun handleTamper(
        body: JSONObject,
        authHeader: String?
    ): Triple<Int, String, JSONObject> {
        val deviceId = body.optCleanString("deviceId", "").trim()
        val existing = prefs.managedDevicesFlow.first().firstOrNull { it.deviceId == deviceId }
            ?: return Triple(401, "Unauthorized", JSONObject().put("ok", false).put("error", "جهاز غير مربوط"))

        if (!isTokenAuthorized(existing.authToken, authHeader)) {
            return Triple(401, "Unauthorized", JSONObject().put("ok", false).put("error", "غير مصرح"))
        }

        val deviceName = body.optCleanString("deviceName", existing.deviceName).ifBlank { existing.deviceName }
        val message = body.optCleanString("message", "محاولة تجاوز الحماية على جهاز $deviceName")
            .ifBlank { "محاولة تجاوز الحماية على جهاز $deviceName" }
        recordAndNotifyTamper(deviceId, deviceName, message, category = if (message.contains("الحذف")) "ADMIN_OFF" else "VPN_OFF")
        prefs.updateManagedDevice(deviceId) { it.copy(tamperWarning = message) }
        return Triple(200, "OK", JSONObject().put("ok", true))
    }

    private fun registerSupervisorWifiReconnect() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    serviceScope.launch {
                        delay(1000L)
                        registerNsdService()
                        pushAllPendingConfigs()
                    }
                }
            }
            wifiCallback = cb
            cm.registerNetworkCallback(req, cb)
        }
    }

    private suspend fun pushAllPendingConfigs() {
        val globalCap = prefs.globalSpeedCapMbpsFlow.first()
        val forceAll = prefs.forceQuotaSaverAllFlow.first()
        val targetOnly = prefs.targetNetworkOnlyFlow.first()
        val targetSig = prefs.targetNetworkSigFlow.first()
        val devices = prefs.managedDevicesFlow.first()
        coroutineScope {
            devices.filter { it.pendingConfigPush && it.lastKnownIp.isNotBlank() }.map { d ->
                async {
                    val ok = LocalLanClient.pushConfigToClient(
                        clientIp = d.lastKnownIp,
                        dailyLimitMb = d.dailyLimitMb,
                        limitPeriod = d.limitPeriod,
                        speedPolicy = d.speedPolicy,
                        whitelistedPackages = d.whitelistedPackages,
                        globalSpeedCapMbps = globalCap,
                        forceQuotaSaver = forceAll || d.forceQuotaSaver,
                        targetNetworkOnly = targetOnly,
                        targetNetworkSig = targetSig,
                        isPaused = d.isPaused,
                        authToken = d.authToken
                    )
                    if (ok) {
                        prefs.updateManagedDevice(d.deviceId) { it.copy(pendingConfigPush = false) }
                    }
                }
            }.awaitAll()
        }
    }

    private data class WatchdogPatch(
        val macAddress: String? = null,
        val usedMb: Long? = null,
        val savedMbToday: Long? = null,
        val actualSpeedMbps: Double? = null,
        val protectionActive: Boolean? = null,
        val lastSeenMillis: Long? = null,
        val isOnline: Boolean? = null,
        val tamperWarning: String? = null
    )

    private fun startOfflineAndMacCheatWatchdog() {
        serviceScope.launch {
            var tickCount = 0
            while (isActive) {
                delay(30_000L)
                tickCount++
                try {
                    pushAllPendingConfigs()
                    val now = System.currentTimeMillis()
                    val snapshotDevices = prefs.managedDevicesFlow.first()
                    if (snapshotDevices.isEmpty()) continue

                    val needsMacScan = snapshotDevices.any { it.macAddress.isBlank() && it.lastKnownIp.isNotBlank() }
                    val activePeers = if (needsMacScan && tickCount % 20 == 1) {
                        NetworkScanner.scanActiveLanPeers(applicationContext)
                    } else if (needsMacScan) {
                        NetworkScanner.readArpAndNeighTable()
                    } else {
                        emptyList()
                    }

                    val patches = mutableMapOf<String, WatchdogPatch>()

                    for (d in snapshotDevices) {
                        var foundMac: String? = null
                        if (d.macAddress.isBlank() && d.lastKnownIp.isNotBlank()) {
                            foundMac = activePeers.firstOrNull { it.ip == d.lastKnownIp }?.mac?.ifBlank { null }
                        }

                        val silentMs = if (d.lastSeenMillis > 0L) (now - d.lastSeenMillis) else 0L
                        val wasIdleSleeping = d.connectionState == ConnectionState.IDLE_FROZEN
                        // Screen-off heartbeats run every 180s; allow >= 240s (or 360s when IDLE_FROZEN) before offline alert
                        val timeoutThresholdMs = if (wasIdleSleeping) 1_200_000L else 480_000L
                        var heartbeatTimedOut = d.lastSeenMillis > 0L && (silentMs > timeoutThresholdMs)
                        var portClosedCheat = false

                        var probeUsedMb: Long? = null
                        var probeSavedMb: Long? = null
                        var probeSpeed: Double? = null
                        var probeProt: Boolean? = null
                        var probeSeen: Long? = null
                        var probeOnline: Boolean? = null
                        var probeWarning: String? = null

                        if (silentMs > 360_000L && d.lastKnownIp.isNotBlank()) {
                            when (val probe = LocalLanClient.probeClientStatus(d.lastKnownIp, d.authToken)) {
                                is ClientProbeResult.Online -> {
                                    val liveStatus = probe.json
                                    if (liveStatus.optBoolean("ok", false)) {
                                        heartbeatTimedOut = false
                                        probeUsedMb = liveStatus.optLong("usedMb", d.usedMb).coerceAtLeast(0L)
                                        probeSavedMb = liveStatus.optLong("savedMbToday", d.savedMbToday).coerceAtLeast(0L)
                                        probeSpeed = liveStatus.optDouble("actualSpeedMbps", d.actualSpeedMbps).sanitizeFinite(0.0, 0.0, 10_000.0)
                                        probeProt = liveStatus.optBoolean("protectionActive", d.protectionActive)
                                        probeSeen = now
                                        probeOnline = true
                                        portClosedStrikes.remove(d.deviceId)
                                        alertedOfflineDevices.remove(d.deviceId)
                                        if (probeProt == true) alertedMacCheatDevices.remove(d.deviceId)
                                    }
                                }
                                ClientProbeResult.PortClosedOnLan -> {
                                    // Verify ARP MAC matches before counting a strike (prevents fake fraud if DHCP gave powered-off phone's IP to another device!)
                                    val arpNow = NetworkScanner.readArpAndNeighTable().firstOrNull { it.ip == d.lastKnownIp }?.mac.orEmpty()
                                    val ipReassigned = d.macAddress.isNotBlank() && arpNow.isNotBlank() && !arpNow.equals(d.macAddress, ignoreCase = true)
                                    if (ipReassigned) {
                                        portClosedStrikes.remove(d.deviceId)
                                    } else {
                                        // Require 4 consecutive strikes (~2+ min) so power-off, reboots, and app updates never false-alarm
                                        val strikes = (portClosedStrikes[d.deviceId] ?: 0) + 1
                                        portClosedStrikes[d.deviceId] = strikes
                                        if (strikes >= 4) portClosedCheat = true
                                    }
                                    heartbeatTimedOut = true
                                }
                                ClientProbeResult.UnreachableOrSleeping -> {
                                    portClosedStrikes.remove(d.deviceId)
                                }
                            }
                        }

                        val effectiveOnline = probeOnline ?: if (heartbeatTimedOut) false else d.isOnline
                        val effectiveProt = probeProt ?: d.protectionActive

                        if (heartbeatTimedOut && d.isOnline) {
                            probeOnline = false
                        }

                        if (portClosedCheat || (!effectiveProt && effectiveOnline)) {
                            if (alertedMacCheatDevices.add(d.deviceId)) {
                                val effectiveMac = foundMac ?: d.macAddress
                                val idTag = if (effectiveMac.isNotBlank()) "MAC: $effectiveMac" else "IP: ${d.lastKnownIp}"
                                val msg = if (alertedOfflineDevices.contains(d.deviceId)) {
                                    "تحايل مؤكد: عاد جهاز ${d.deviceName} للاتصال بالواي فاي ($idTag) بعد إيقاف التطبيق أو حذفه!"
                                } else {
                                    "تحايل مؤكد: جهاز ${d.deviceName} متصل بالواي فاي حالياً ($idTag) مع إيقاف الحماية أو إغلاق التطبيق قسرياً!"
                                }
                                recordAndNotifyTamper(d.deviceId, d.deviceName, msg, category = "VPN_OFF")
                                probeWarning = msg
                            }
                        } else if (heartbeatTimedOut) {
                            // Normal offline / Doze sleep: mark card offline without spamming high-priority tamper alarms
                            alertedOfflineDevices.add(d.deviceId)
                        }

                        if (foundMac != null || probeUsedMb != null || probeOnline != null || probeWarning != null) {
                            patches[d.deviceId] = WatchdogPatch(
                                macAddress = foundMac,
                                usedMb = probeUsedMb,
                                savedMbToday = probeSavedMb,
                                actualSpeedMbps = probeSpeed,
                                protectionActive = probeProt,
                                lastSeenMillis = probeSeen,
                                isOnline = probeOnline,
                                tamperWarning = probeWarning
                            )
                        }
                    }

                    if (patches.isNotEmpty()) {
                        // Merge only probed telemetry fields onto the latest stored device list (prevents clobbering concurrent edits)
                        prefs.updateAllManagedDevices { latestList ->
                            latestList.map { cur ->
                                val patch = patches[cur.deviceId] ?: return@map cur
                                cur.copy(
                                    macAddress = patch.macAddress ?: cur.macAddress,
                                    usedMb = patch.usedMb ?: cur.usedMb,
                                    savedMbToday = patch.savedMbToday ?: cur.savedMbToday,
                                    actualSpeedMbps = patch.actualSpeedMbps ?: cur.actualSpeedMbps,
                                    protectionActive = patch.protectionActive ?: cur.protectionActive,
                                    lastSeenMillis = patch.lastSeenMillis ?: cur.lastSeenMillis,
                                    isOnline = patch.isOnline ?: cur.isOnline,
                                    tamperWarning = patch.tamperWarning ?: cur.tamperWarning
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Keep watchdog alive across transient network/storage errors
                }
            }
        }
    }

    private suspend fun recordAndNotifyTamper(
        deviceId: String,
        deviceName: String,
        message: String,
        category: String = "GENERAL"
    ) {
        val now = System.currentTimeMillis()
        val key = "$deviceId:$category"
        val prev = recentTamperByCategory[key] ?: 0L
        if (now - prev < 60_000L) return
        recentTamperByCategory[key] = now
        val alert = TamperAlert(
            id = UUID.randomUUID().toString().take(8),
            deviceId = deviceId,
            deviceName = deviceName,
            message = message,
            timestampMillis = now
        )
        prefs.addTamperAlert(alert)
        sendTamperNotification(deviceId, deviceName, message)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "مراقبة المشرف",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "تنبيهات محاولة التجاوز",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun buildForegroundNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("No Quota — وضع المشرف")
            .setContentText("يراقب الأجهزة والشبكة المحلية للكشف عن أي تجاوز")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun sendTamperNotification(deviceId: String, deviceName: String, message: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val notifId = 4000 + ((deviceId.hashCode() and 0x7FFF) % 10_000)
            val pi = PendingIntent.getActivity(
                this,
                notifId,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("تنبيه حماية: $deviceName")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(notifId, notif)
        }
    }
}

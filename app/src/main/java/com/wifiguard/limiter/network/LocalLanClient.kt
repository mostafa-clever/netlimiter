package com.wifiguard.limiter.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.InstalledAppItem
import com.wifiguard.limiter.model.LimitPeriod
import com.wifiguard.limiter.model.SpeedPolicyConfig
import com.wifiguard.limiter.model.optCleanString
import com.wifiguard.limiter.model.sanitizeFinite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredSupervisor(
    val host: String,
    val port: Int,
    val name: String
)

data class SyncedLimitConfig(
    val dailyLimitMb: Long,
    val limitPeriod: LimitPeriod,
    val speedPolicy: SpeedPolicyConfig,
    val whitelistedPackages: Set<String>,
    val globalSpeedCapMbps: Double,
    val forceQuotaSaver: Boolean,
    val targetNetworkOnly: Boolean,
    val targetNetworkSig: String,
    val isPaused: Boolean = false,
    val authToken: String = ""
)

sealed class ClientProbeResult {
    data class Online(val json: JSONObject) : ClientProbeResult()
    object PortClosedOnLan : ClientProbeResult()
    object UnreachableOrSleeping : ClientProbeResult()
}

sealed class HeartbeatOutcome {
    data class Synced(val config: SyncedLimitConfig) : HeartbeatOutcome()
    object Unpaired : HeartbeatOutcome()
    object NetworkError : HeartbeatOutcome()
}

object LocalLanClient {

    const val SERVICE_TYPE = "_wifiguard._tcp."
    const val DEFAULT_PORT = 8765
    const val CLIENT_PORT = 8766

    fun formatHostForUrl(host: String): String {
        val clean = host.trim().substringBefore("%").trimStart('[').trimEnd(']')
        return if (clean.contains(":")) "[$clean]" else clean
    }

    fun discoverSupervisors(context: Context): Flow<List<DiscoveredSupervisor>> = callbackFlow {
        val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val foundByServiceName = ConcurrentHashMap<String, DiscoveredSupervisor>()
        val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)

        fun drainResolveQueue() {
            if (!resolving.compareAndSet(false, true)) return
            val next = resolveQueue.poll()
            if (next == null) {
                resolving.set(false)
                return
            }
            val started = runCatching {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(next, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving.set(false)
                        drainResolveQueue()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val resolvedAddr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            serviceInfo.hostAddresses.firstOrNull { it is Inet4Address } ?: serviceInfo.hostAddresses.firstOrNull()
                        } else {
                            @Suppress("DEPRECATION")
                            serviceInfo.host
                        }
                        val host = resolvedAddr?.hostAddress?.substringBefore("%")
                        if (!host.isNullOrBlank()) {
                            val item = DiscoveredSupervisor(
                                host = host,
                                port = serviceInfo.port,
                                name = serviceInfo.serviceName
                            )
                            foundByServiceName[serviceInfo.serviceName] = item
                            trySend(foundByServiceName.values.distinctBy { "${it.host}:${it.port}" })
                        }
                        resolving.set(false)
                        drainResolveQueue()
                    }
                })
            }.isSuccess
            if (!started) {
                resolving.set(false)
                drainResolveQueue()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("wifiguard", ignoreCase = true)) {
                    resolveQueue.add(service)
                    drainResolveQueue()
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                foundByServiceName.remove(service.serviceName)
                trySend(foundByServiceName.values.distinctBy { "${it.host}:${it.port}" })
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }

        awaitClose {
            resolveQueue.clear()
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
    }

    suspend fun pairWithSupervisor(
        host: String,
        port: Int,
        pairingCode: String,
        deviceId: String,
        deviceName: String,
        installedApps: List<InstalledAppItem> = emptyList()
    ): Result<SyncedLimitConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("pairingCode", pairingCode.trim())
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                if (installedApps.isNotEmpty()) {
                    val arr = JSONArray()
                    installedApps.distinctBy { it.packageName }.forEach { a ->
                        arr.put(JSONObject().put("packageName", a.packageName).put("appName", a.appName))
                    }
                    put("installedApps", arr)
                }
            }
            val resp = postJson("http://${formatHostForUrl(host)}:$port/pair", payload)
            if (!resp.optBoolean("ok", false)) {
                throw IllegalStateException(resp.optCleanString("error", "رمز الربط غير صحيح"))
            }
            parseConfig(resp)
        }
    }

    suspend fun sendHeartbeat(
        host: String,
        port: Int,
        deviceId: String,
        deviceName: String,
        usedMb: Long,
        usedMbToday: Long = usedMb,
        savedMbToday: Long = 0L,
        actualSpeedMbps: Double = 0.0,
        appUsageMb: Map<String, Long> = emptyMap(),
        protectionActive: Boolean,
        uninstallProtectionActive: Boolean,
        connectionState: ConnectionState,
        installedApps: List<InstalledAppItem> = emptyList(),
        authToken: String = ""
    ): SyncedLimitConfig? {
        return when (val out = sendHeartbeatOutcome(
            host, port, deviceId, deviceName, usedMb, usedMbToday, savedMbToday,
            actualSpeedMbps, appUsageMb, protectionActive, uninstallProtectionActive,
            connectionState, installedApps, authToken
        )) {
            is HeartbeatOutcome.Synced -> out.config
            else -> null
        }
    }

    suspend fun sendHeartbeatOutcome(
        host: String,
        port: Int,
        deviceId: String,
        deviceName: String,
        usedMb: Long,
        usedMbToday: Long = usedMb,
        savedMbToday: Long = 0L,
        actualSpeedMbps: Double = 0.0,
        appUsageMb: Map<String, Long> = emptyMap(),
        protectionActive: Boolean,
        uninstallProtectionActive: Boolean,
        connectionState: ConnectionState,
        installedApps: List<InstalledAppItem> = emptyList(),
        authToken: String = ""
    ): HeartbeatOutcome = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext HeartbeatOutcome.NetworkError
        runCatching {
            val payload = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("usedMb", usedMb.coerceAtLeast(0L))
                put("usedMbToday", usedMbToday.coerceAtLeast(0L))
                put("savedMbToday", savedMbToday.coerceAtLeast(0L))
                put("actualSpeedMbps", actualSpeedMbps.sanitizeFinite(0.0, 0.0, 10_000.0))
                put("appUsageMb", JSONObject(appUsageMb.mapValues { it.value.coerceAtLeast(0L) }))
                put("protectionActive", protectionActive)
                put("uninstallProtectionActive", uninstallProtectionActive)
                put("connectionState", connectionState.name)
                if (installedApps.isNotEmpty()) {
                    val arr = JSONArray()
                    installedApps.distinctBy { it.packageName }.forEach { a ->
                        arr.put(JSONObject().put("packageName", a.packageName).put("appName", a.appName))
                    }
                    put("installedApps", arr)
                }
            }
            val (code, resp) = postJsonWithCode("http://${formatHostForUrl(host)}:$port/heartbeat", payload, authToken)
            when {
                code == 401 -> HeartbeatOutcome.Unpaired
                resp.optBoolean("ok", false) -> HeartbeatOutcome.Synced(parseConfig(resp))
                else -> HeartbeatOutcome.NetworkError
            }
        }.getOrDefault(HeartbeatOutcome.NetworkError)
    }

    suspend fun sendTamperAlert(
        host: String,
        port: Int,
        deviceId: String,
        deviceName: String,
        message: String,
        authToken: String = ""
    ) = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext
        runCatching {
            val payload = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("message", message)
            }
            postJson("http://${formatHostForUrl(host)}:$port/tamper", payload, authToken)
        }
    }

    suspend fun pushConfigToClient(
        clientIp: String,
        dailyLimitMb: Long,
        limitPeriod: LimitPeriod,
        speedPolicy: SpeedPolicyConfig,
        whitelistedPackages: List<String>,
        globalSpeedCapMbps: Double,
        forceQuotaSaver: Boolean,
        targetNetworkOnly: Boolean,
        targetNetworkSig: String,
        isPaused: Boolean = false,
        authToken: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (clientIp.isBlank()) return@withContext false
        runCatching {
            val payload = JSONObject().apply {
                put("dailyLimitMb", dailyLimitMb.coerceAtLeast(10L))
                put("limitPeriod", limitPeriod.name)
                put("speedPolicy", speedPolicy.toJson())
                put("whitelistedPackages", JSONArray(whitelistedPackages.distinct()))
                put("globalSpeedCapMbps", globalSpeedCapMbps.sanitizeFinite(0.0, 0.0, 1000.0))
                put("forceQuotaSaver", forceQuotaSaver)
                put("targetNetworkOnly", targetNetworkOnly)
                put("targetNetworkSig", targetNetworkSig)
                put("isPaused", isPaused)
            }
            val resp = postJson("http://${formatHostForUrl(clientIp)}:$CLIENT_PORT/config", payload, authToken)
            resp.optBoolean("ok", false)
        }.getOrDefault(false)
    }

    suspend fun probeClientStatus(clientIp: String, authToken: String = ""): ClientProbeResult = withContext(Dispatchers.IO) {
        if (clientIp.isBlank()) return@withContext ClientProbeResult.UnreachableOrSleeping
        var conn: HttpURLConnection? = null
        try {
            val url = URL("http://${formatHostForUrl(clientIp)}:$CLIENT_PORT/status")
            conn = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                if (authToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $authToken")
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } } ?: "{}"
            ClientProbeResult.Online(JSONObject(text))
        } catch (_: java.net.ConnectException) {
            ClientProbeResult.PortClosedOnLan
        } catch (_: Throwable) {
            ClientProbeResult.UnreachableOrSleeping
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    suspend fun fetchClientStatus(
        clientIp: String,
        fullAnalytics: Boolean = false,
        authToken: String = ""
    ): JSONObject? = withContext(Dispatchers.IO) {
        if (clientIp.isBlank()) return@withContext null
        var conn: HttpURLConnection? = null
        try {
            val query = if (fullAnalytics) "?full=1" else ""
            val url = URL("http://${formatHostForUrl(clientIp)}:$CLIENT_PORT/status$query")
            conn = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = if (fullAnalytics) 12_000 else 4_000
                if (authToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $authToken")
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) return@withContext null
            val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            JSONObject(text)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun parseConfig(resp: JSONObject): SyncedLimitConfig {
        val arr = resp.optJSONArray("whitelistedPackages") ?: JSONArray()
        val pkgs = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            if (!arr.isNull(i)) {
                val p = arr.optString(i, "").trim()
                if (p.isNotBlank() && p != "null") pkgs.add(p)
            }
        }
        val period = runCatching {
            LimitPeriod.valueOf(resp.optCleanString("limitPeriod", LimitPeriod.DAILY.name))
        }.getOrDefault(LimitPeriod.DAILY)
        return SyncedLimitConfig(
            dailyLimitMb = resp.optLong("dailyLimitMb", 1024L).coerceAtLeast(10L),
            limitPeriod = period,
            speedPolicy = SpeedPolicyConfig.fromJson(resp.optJSONObject("speedPolicy")),
            whitelistedPackages = pkgs,
            globalSpeedCapMbps = resp.optDouble("globalSpeedCapMbps", 0.0).sanitizeFinite(0.0, 0.0, 1000.0),
            forceQuotaSaver = resp.optBoolean("forceQuotaSaver", false),
            targetNetworkOnly = resp.optBoolean("targetNetworkOnly", false),
            targetNetworkSig = resp.optCleanString("targetNetworkSig", ""),
            isPaused = resp.optBoolean("isPaused", false),
            authToken = resp.optCleanString("authToken", "")
        )
    }

    private fun postJson(urlStr: String, body: JSONObject, authToken: String = ""): JSONObject =
        postJsonWithCode(urlStr, body, authToken).second

    private fun postJsonWithCode(urlStr: String, body: JSONObject, authToken: String = ""): Pair<Int, JSONObject> {
        val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 6000
                doOutput = true
                setFixedLengthStreamingMode(bodyBytes.size)
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                if (authToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $authToken")
                }
            }
            conn.outputStream.use { os ->
                os.write(bodyBytes)
                os.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) {
                return code to JSONObject().put("ok", false).put("error", "HTTP $code")
            }
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            val json = runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("ok", false).put("error", "HTTP $code")
            }
            return code to json
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}

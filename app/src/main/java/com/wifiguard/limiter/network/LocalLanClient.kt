package com.wifiguard.limiter.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.InstalledAppItem
import com.wifiguard.limiter.model.LimitPeriod
import com.wifiguard.limiter.model.SpeedPolicyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

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
    val isPaused: Boolean = false
)

sealed class ClientProbeResult {
    data class Online(val json: JSONObject) : ClientProbeResult()
    object PortClosedOnLan : ClientProbeResult() // TCP RST (ECONNREFUSED) -> phone is awake on Wi-Fi, app was force-stopped or uninstalled!
    object UnreachableOrSleeping : ClientProbeResult() // Timeout / NoRoute -> phone is asleep in Doze or left Wi-Fi
}

object LocalLanClient {

    const val SERVICE_TYPE = "_wifiguard._tcp."
    const val DEFAULT_PORT = 8765
    const val CLIENT_PORT = 8766

    fun discoverSupervisors(context: Context): Flow<List<DiscoveredSupervisor>> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = mutableMapOf<String, DiscoveredSupervisor>()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("wifiguard")) {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress ?: return
                            val item = DiscoveredSupervisor(
                                host = host,
                                port = serviceInfo.port,
                                name = serviceInfo.serviceName
                            )
                            found[host] = item
                            trySend(found.values.toList())
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                found.remove(service.host?.hostAddress)
                trySend(found.values.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }

        awaitClose {
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
                put("pairingCode", pairingCode)
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                if (installedApps.isNotEmpty()) {
                    val arr = JSONArray()
                    installedApps.forEach { a ->
                        arr.put(JSONObject().put("packageName", a.packageName).put("appName", a.appName))
                    }
                    put("installedApps", arr)
                }
            }
            val resp = postJson("http://$host:$port/pair", payload)
            if (!resp.optBoolean("ok", false)) {
                throw IllegalStateException(resp.optString("error", "رمز الربط غير صحيح"))
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
        savedMbToday: Long = 0L,
        actualSpeedMbps: Double = 0.0,
        appUsageMb: Map<String, Long> = emptyMap(),
        protectionActive: Boolean,
        uninstallProtectionActive: Boolean,
        connectionState: ConnectionState,
        installedApps: List<InstalledAppItem> = emptyList()
    ): SyncedLimitConfig? = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext null
        runCatching {
            val payload = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("usedMb", usedMb)
                put("savedMbToday", savedMbToday)
                put("actualSpeedMbps", actualSpeedMbps)
                if (appUsageMb.isNotEmpty()) {
                    put("appUsageMb", JSONObject(appUsageMb.mapValues { it.value }))
                }
                put("protectionActive", protectionActive)
                put("uninstallProtectionActive", uninstallProtectionActive)
                put("connectionState", connectionState.name)
                if (installedApps.isNotEmpty()) {
                    val arr = JSONArray()
                    installedApps.forEach { a ->
                        arr.put(JSONObject().put("packageName", a.packageName).put("appName", a.appName))
                    }
                    put("installedApps", arr)
                }
            }
            val resp = postJson("http://$host:$port/heartbeat", payload)
            if (resp.optBoolean("ok", false)) parseConfig(resp) else null
        }.getOrNull()
    }

    suspend fun sendTamperAlert(
        host: String,
        port: Int,
        deviceId: String,
        deviceName: String,
        message: String
    ) = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext
        runCatching {
            val payload = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("message", message)
            }
            postJson("http://$host:$port/tamper", payload)
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
        isPaused: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (clientIp.isBlank()) return@withContext false
        runCatching {
            val payload = JSONObject().apply {
                put("dailyLimitMb", dailyLimitMb)
                put("limitPeriod", limitPeriod.name)
                put("speedPolicy", speedPolicy.toJson())
                put("whitelistedPackages", JSONArray(whitelistedPackages))
                put("globalSpeedCapMbps", globalSpeedCapMbps)
                put("forceQuotaSaver", forceQuotaSaver)
                put("targetNetworkOnly", targetNetworkOnly)
                put("targetNetworkSig", targetNetworkSig)
                put("isPaused", isPaused)
            }
            val resp = postJson("http://$clientIp:$CLIENT_PORT/config", payload)
            resp.optBoolean("ok", false)
        }.getOrDefault(false)
    }

    suspend fun probeClientStatus(clientIp: String): ClientProbeResult = withContext(Dispatchers.IO) {
        if (clientIp.isBlank()) return@withContext ClientProbeResult.UnreachableOrSleeping
        try {
            val url = URL("http://$clientIp:$CLIENT_PORT/status")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
            }
            val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            conn.disconnect()
            ClientProbeResult.Online(JSONObject(text))
        } catch (_: java.net.ConnectException) {
            ClientProbeResult.PortClosedOnLan
        } catch (_: Throwable) {
            ClientProbeResult.UnreachableOrSleeping
        }
    }

    suspend fun fetchClientStatus(clientIp: String): JSONObject? = withContext(Dispatchers.IO) {
        if (clientIp.isBlank()) return@withContext null
        runCatching {
            val url = URL("http://$clientIp:$CLIENT_PORT/status")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }
            val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            conn.disconnect()
            JSONObject(text)
        }.getOrNull()
    }

    private fun parseConfig(resp: JSONObject): SyncedLimitConfig {
        val arr = resp.optJSONArray("whitelistedPackages") ?: JSONArray()
        val pkgs = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val p = arr.optString(i)
            if (p.isNotBlank()) pkgs.add(p)
        }
        val period = runCatching {
            LimitPeriod.valueOf(resp.optString("limitPeriod", LimitPeriod.DAILY.name))
        }.getOrDefault(LimitPeriod.DAILY)
        return SyncedLimitConfig(
            dailyLimitMb = resp.optLong("dailyLimitMb", 1024L),
            limitPeriod = period,
            speedPolicy = SpeedPolicyConfig.fromJson(resp.optJSONObject("speedPolicy")),
            whitelistedPackages = pkgs,
            globalSpeedCapMbps = resp.optDouble("globalSpeedCapMbps", 0.0),
            forceQuotaSaver = resp.optBoolean("forceQuotaSaver", false),
            targetNetworkOnly = resp.optBoolean("targetNetworkOnly", false),
            targetNetworkSig = resp.optString("targetNetworkSig", ""),
            isPaused = resp.optBoolean("isPaused", false)
        )
    }

    private fun postJson(urlStr: String, body: JSONObject): JSONObject {
        val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setFixedLengthStreamingMode(bodyBytes.size)
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        conn.outputStream.use { os ->
            os.write(bodyBytes)
            os.flush()
        }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        conn.disconnect()
        return JSONObject(text)
    }
}

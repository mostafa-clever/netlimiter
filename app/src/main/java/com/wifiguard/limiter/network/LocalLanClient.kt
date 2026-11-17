package com.wifiguard.limiter.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.wifiguard.limiter.model.ConnectionState
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
    val slowdownThresholdPercent: Int,
    val whitelistedPackages: Set<String>
)

object LocalLanClient {

    const val SERVICE_TYPE = "_wifiguard._tcp."
    const val DEFAULT_PORT = 8765

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
        deviceName: String
    ): Result<SyncedLimitConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("pairingCode", pairingCode)
                put("deviceId", deviceId)
                put("deviceName", deviceName)
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
        protectionActive: Boolean,
        uninstallProtectionActive: Boolean,
        connectionState: ConnectionState
    ): SyncedLimitConfig? = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext null
        runCatching {
            val payload = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("usedMb", usedMb)
                put("protectionActive", protectionActive)
                put("uninstallProtectionActive", uninstallProtectionActive)
                put("connectionState", connectionState.name)
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

    private fun parseConfig(resp: JSONObject): SyncedLimitConfig {
        val arr = resp.optJSONArray("whitelistedPackages") ?: JSONArray()
        val pkgs = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val p = arr.optString(i)
            if (p.isNotBlank()) pkgs.add(p)
        }
        return SyncedLimitConfig(
            dailyLimitMb = resp.optLong("dailyLimitMb", 1024L),
            slowdownThresholdPercent = resp.optInt("slowdownThresholdPercent", 80),
            whitelistedPackages = pkgs
        )
    }

    private fun postJson(urlStr: String, body: JSONObject): JSONObject {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 4000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(body.toString())
            writer.flush()
        }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        conn.disconnect()
        return JSONObject(text)
    }
}

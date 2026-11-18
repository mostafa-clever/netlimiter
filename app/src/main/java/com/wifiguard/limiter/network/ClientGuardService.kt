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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wifiguard.limiter.MainActivity
import com.wifiguard.limiter.admin.WifiGuardDeviceAdminReceiver
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.LimitPeriod
import com.wifiguard.limiter.model.SpeedPolicyConfig
import com.wifiguard.limiter.model.getInstalledLaunchableApps
import com.wifiguard.limiter.vpn.WifiGuardVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

class ClientGuardService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: PreferencesManager
    private var controlSocket: ServerSocket? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var wasVpnRunning: Boolean = false
    private var sentAppsOnce: Boolean = false

    companion object {
        const val CHANNEL_ID = "wifiguard_client_guard_channel"
        const val NOTIF_ID = 2002

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, ClientGuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ClientGuardService::class.java)) }
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
        startPersistentHeartbeatAndVpnWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            wifiCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            }
        }
        runCatching { controlSocket?.close() }
        serviceScope.cancel()
    }

    private fun startControlServer() {
        serviceScope.launch {
            runCatching {
                controlSocket = ServerSocket(LocalLanClient.CLIENT_PORT)
                while (isActive) {
                    val sock = controlSocket?.accept() ?: break
                    launch { handleSupervisorCommand(sock) }
                }
            }
        }
    }

    private suspend fun handleSupervisorCommand(sock: Socket) {
        sock.use { s ->
            runCatching {
                s.soTimeout = 4000
                val rawIn = s.getInputStream()
                fun readLine(): String? {
                    val lb = ByteArrayOutputStream()
                    while (true) {
                        val b = rawIn.read()
                        if (b == -1) return if (lb.size() == 0) null else lb.toString("UTF-8")
                        if (b == '\n'.code) break
                        if (b != '\r'.code) lb.write(b)
                    }
                    return lb.toString("UTF-8")
                }

                val reqLine = readLine() ?: return
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
                    val obj = JSONObject(String(buf, 0, tot, Charsets.UTF_8))
                    val arr = obj.optJSONArray("whitelistedPackages") ?: JSONArray()
                    val pkgs = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        val p = arr.optString(i)
                        if (p.isNotBlank()) pkgs.add(p)
                    }
                    val period = runCatching {
                        LimitPeriod.valueOf(obj.optString("limitPeriod", "DAILY"))
                    }.getOrDefault(LimitPeriod.DAILY)
                    prefs.updateLocalLimits(
                        limitMb = obj.optLong("dailyLimitMb", 1024L),
                        limitPeriod = period,
                        speedPolicy = SpeedPolicyConfig.fromJson(obj.optJSONObject("speedPolicy")),
                        whitelist = pkgs,
                        globalSpeedCapMbps = obj.optDouble("globalSpeedCapMbps", 0.0),
                        forceQuotaSaver = obj.optBoolean("forceQuotaSaver", false),
                        targetNetworkOnly = obj.optBoolean("targetNetworkOnly", false),
                        targetNetworkSig = obj.optString("targetNetworkSig", "")
                    )
                    JSONObject().put("ok", true)
                } else {
                    val vpnUp = WifiGuardVpnService.isRunning.value
                    JSONObject().apply {
                        put("ok", true)
                        put("usedMb", prefs.getUsedMbActivePeriod())
                        put("savedMbToday", prefs.getSavedMbToday())
                        put("protectionActive", vpnUp)
                        put("uninstallProtectionActive", WifiGuardDeviceAdminReceiver.isActive(applicationContext))
                        put("connectionState", if (vpnUp) WifiGuardVpnService.connectionState.value.name else ConnectionState.PROTECTION_OFF.name)
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

    private fun registerWifiReconnectCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
            }
            wifiCallback = cb
            cm.registerNetworkCallback(req, cb)
        }
    }

    private fun startPersistentHeartbeatAndVpnWatchdog() {
        serviceScope.launch {
            while (isActive) {
                runCatching {
                    val role = prefs.roleFlow.first()
                    if (role == AppRole.CLIENT) {
                        val vpnNow = WifiGuardVpnService.isRunning.value
                        if (wasVpnRunning && !vpnNow) {
                            val host = prefs.getSupervisorHost()
                            val port = prefs.getSupervisorPort()
                            val id = prefs.getOrCreateDeviceId()
                            val name = prefs.getDeviceName()
                            LocalLanClient.sendTamperAlert(
                                host,
                                port,
                                id,
                                name,
                                "تحايل: قام جهاز $name بإيقاف الحماية (VPN) دون حذف التطبيق!"
                            )
                            syncWithSupervisorOnce()
                        }
                        wasVpnRunning = vpnNow
                    }
                }
                delay(3_000L)
            }
        }

        serviceScope.launch {
            var failures = 0
            while (isActive) {
                runCatching {
                    val role = prefs.roleFlow.first()
                    if (role == AppRole.CLIENT) {
                        val ok = syncWithSupervisorOnce()
                        if (ok) {
                            failures = 0
                        } else {
                            failures++
                            if (failures >= 2) {
                                failures = 0
                                val found = withTimeoutOrNull(3500L) {
                                    LocalLanClient.discoverSupervisors(applicationContext).first { it.isNotEmpty() }
                                }?.firstOrNull()
                                if (found != null) {
                                    prefs.setSupervisorConnection(found.host, found.port, "")
                                    syncWithSupervisorOnce()
                                }
                            }
                        }
                    }
                }
                delay(20_000L)
            }
        }
    }

    private suspend fun syncWithSupervisorOnce(): Boolean {
        val host = prefs.getSupervisorHost()
        val port = prefs.getSupervisorPort()
        if (host.isBlank()) return false
        val id = prefs.getOrCreateDeviceId()
        val name = prefs.getDeviceName()
        val usedMb = prefs.getUsedMbActivePeriod()
        val savedMb = prefs.getSavedMbToday()
        val vpnUp = WifiGuardVpnService.isRunning.value
        val adminActive = WifiGuardDeviceAdminReceiver.isActive(applicationContext)
        val updated = LocalLanClient.sendHeartbeat(
            host = host,
            port = port,
            deviceId = id,
            deviceName = name,
            usedMb = usedMb,
            savedMbToday = savedMb,
            protectionActive = vpnUp,
            uninstallProtectionActive = adminActive,
            connectionState = if (vpnUp) WifiGuardVpnService.connectionState.value else ConnectionState.PROTECTION_OFF,
            installedApps = if (!sentAppsOnce) getInstalledLaunchableApps(applicationContext) else emptyList()
        )
        if (updated != null) {
            sentAppsOnce = true
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
            return true
        }
        return false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "مراقبة الاتصال بالمشرف", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("حارس الواي فاي")
            .setContentText("متصل بجهاز المشرف")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}

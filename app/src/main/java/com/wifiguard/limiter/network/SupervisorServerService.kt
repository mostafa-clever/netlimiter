package com.wifiguard.limiter.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wifiguard.limiter.MainActivity
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.ManagedDevice
import com.wifiguard.limiter.model.TamperAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

class SupervisorServerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: PreferencesManager
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val alertedOfflineDevices = mutableSetOf<String>()

    companion object {
        const val CHANNEL_ID = "wifiguard_supervisor_channel"
        const val ALERT_CHANNEL_ID = "wifiguard_alerts_channel"
        const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, SupervisorServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SupervisorServerService::class.java))
        }

        fun getLocalWifiIp(context: Context): String {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "غير متصل بالواي فاي"
            val lp: LinkProperties = cm.getLinkProperties(network) ?: return "غير متاح"
            val ipv4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            return ipv4?.address?.hostAddress ?: "غير متاح"
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(applicationContext)
        createNotificationChannels()
        startForeground(NOTIF_ID, buildForegroundNotification())
        registerNsdService()
        startHttpServer()
        startOfflineWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        runCatching { serverSocket?.close() }
        runCatching {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        }
        serviceScope.cancel()
    }

    private fun registerNsdService() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
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
            runCatching {
                serverSocket = ServerSocket(LocalLanClient.DEFAULT_PORT)
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                }
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val path = parts[1]

                var contentLength = 0
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                    if (header.lowercase().startsWith("content-length:")) {
                        contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                val bodyChars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = reader.read(bodyChars, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                val bodyJson = if (totalRead > 0) JSONObject(String(bodyChars, 0, totalRead)) else JSONObject()

                val responseJson = when (path) {
                    "/pair" -> handlePair(bodyJson)
                    "/heartbeat" -> handleHeartbeat(bodyJson)
                    "/tamper" -> handleTamper(bodyJson)
                    else -> JSONObject().put("ok", false).put("error", "مسار غير معروف")
                }

                val respStr = responseJson.toString()
                val respBytes = respStr.toByteArray(Charsets.UTF_8)
                val out = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                out.write("HTTP/1.1 200 OK\r\n")
                out.write("Content-Type: application/json; charset=UTF-8\r\n")
                out.write("Content-Length: ${respBytes.size}\r\n")
                out.write("Connection: close\r\n\r\n")
                out.write(respStr)
                out.flush()
            }
        }
    }

    private suspend fun handlePair(body: JSONObject): JSONObject {
        val code = body.optString("pairingCode", "")
        val expectedCode = prefs.getOrCreatePairingCode()
        if (code != expectedCode) {
            return JSONObject().put("ok", false).put("error", "رمز الربط غير صحيح")
        }
        val deviceId = body.optString("deviceId", "")
        val deviceName = body.optString("deviceName", "جهاز جديد")
        val existing = prefs.managedDevicesFlow.first().firstOrNull { it.deviceId == deviceId }
        val device = existing?.copy(
            deviceName = deviceName,
            lastSeenMillis = System.currentTimeMillis(),
            isOnline = true
        ) ?: ManagedDevice(
            deviceId = deviceId,
            deviceName = deviceName,
            dailyLimitMb = 1024L,
            slowdownThresholdPercent = 80,
            lastSeenMillis = System.currentTimeMillis(),
            isOnline = true
        )
        prefs.upsertManagedDevice(device)
        alertedOfflineDevices.remove(deviceId)

        return JSONObject().apply {
            put("ok", true)
            put("dailyLimitMb", device.dailyLimitMb)
            put("slowdownThresholdPercent", device.slowdownThresholdPercent)
            put("whitelistedPackages", JSONArray(device.whitelistedPackages))
        }
    }

    private suspend fun handleHeartbeat(body: JSONObject): JSONObject {
        val deviceId = body.optString("deviceId", "")
        val deviceName = body.optString("deviceName", "جهاز")
        val usedMb = body.optLong("usedMb", 0L)
        val protectionActive = body.optBoolean("protectionActive", false)
        val uninstallProtectionActive = body.optBoolean("uninstallProtectionActive", false)
        val stateStr = body.optString("connectionState", ConnectionState.NORMAL.name)
        val connState = runCatching { ConnectionState.valueOf(stateStr) }.getOrDefault(ConnectionState.NORMAL)

        val existing = prefs.managedDevicesFlow.first().firstOrNull { it.deviceId == deviceId }
        val wasProtected = existing?.protectionActive == true

        var tamperMsg: String? = null
        if (wasProtected && !protectionActive) {
            tamperMsg = "تم إيقاف حماية الاتصال على جهاز $deviceName"
            recordAndNotifyTamper(deviceId, deviceName, tamperMsg)
        } else if (existing?.uninstallProtectionActive == true && !uninstallProtectionActive) {
            tamperMsg = "تم إلغاء حماية الحذف على جهاز $deviceName"
            recordAndNotifyTamper(deviceId, deviceName, tamperMsg)
        }

        val updated = (existing ?: ManagedDevice(deviceId = deviceId, deviceName = deviceName)).copy(
            deviceName = deviceName,
            usedMb = usedMb,
            protectionActive = protectionActive,
            uninstallProtectionActive = uninstallProtectionActive,
            connectionState = if (!protectionActive) ConnectionState.PROTECTION_OFF else connState,
            lastSeenMillis = System.currentTimeMillis(),
            isOnline = true,
            tamperWarning = tamperMsg ?: if (protectionActive) null else existing?.tamperWarning
        )
        prefs.upsertManagedDevice(updated)
        alertedOfflineDevices.remove(deviceId)

        return JSONObject().apply {
            put("ok", true)
            put("dailyLimitMb", updated.dailyLimitMb)
            put("slowdownThresholdPercent", updated.slowdownThresholdPercent)
            put("whitelistedPackages", JSONArray(updated.whitelistedPackages))
        }
    }

    private suspend fun handleTamper(body: JSONObject): JSONObject {
        val deviceId = body.optString("deviceId", "")
        val deviceName = body.optString("deviceName", "جهاز")
        val message = body.optString("message", "محاولة تجاوز الحماية على جهاز $deviceName")
        recordAndNotifyTamper(deviceId, deviceName, message)

        val existing = prefs.managedDevicesFlow.first().firstOrNull { it.deviceId == deviceId }
        if (existing != null) {
            prefs.upsertManagedDevice(existing.copy(tamperWarning = message))
        }
        return JSONObject().put("ok", true)
    }

    private fun startOfflineWatchdog() {
        serviceScope.launch {
            while (isActive) {
                delay(15_000L)
                val now = System.currentTimeMillis()
                val devices = prefs.managedDevicesFlow.first()
                var changed = false
                val updated = devices.map { d ->
                    if (d.lastSeenMillis > 0L && now - d.lastSeenMillis > 50_000L) {
                        if (d.isOnline || !alertedOfflineDevices.contains(d.deviceId)) {
                            alertedOfflineDevices.add(d.deviceId)
                            val msg = "انقطع اتصال جهاز ${d.deviceName} — قد يكون تم حذف التطبيق أو إيقاف الحماية"
                            recordAndNotifyTamper(d.deviceId, d.deviceName, msg)
                            changed = true
                            d.copy(isOnline = false, tamperWarning = msg)
                        } else {
                            d
                        }
                    } else {
                        d
                    }
                }
                if (changed) {
                    prefs.updateAllManagedDevices { updated }
                }
            }
        }
    }

    private suspend fun recordAndNotifyTamper(deviceId: String, deviceName: String, message: String) {
        val alert = TamperAlert(
            id = UUID.randomUUID().toString().take(8),
            deviceId = deviceId,
            deviceName = deviceName,
            message = message,
            timestampMillis = System.currentTimeMillis()
        )
        prefs.addTamperAlert(alert)
        sendTamperNotification(deviceName, message)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
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
            .setContentTitle("حارس الواي فاي — وضع المشرف")
            .setContentText("يراقب الأجهزة المرتبطة على شبكة الواي فاي")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun sendTamperNotification(deviceName: String, message: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val pi = PendingIntent.getActivity(
            this,
            1,
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
        nm.notify(deviceName.hashCode(), notif)
    }
}

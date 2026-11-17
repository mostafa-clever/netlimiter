package com.wifiguard.limiter.model

import org.json.JSONArray
import org.json.JSONObject

enum class AppRole {
    UNSET,
    SUPERVISOR,
    CLIENT
}

enum class ConnectionState {
    NORMAL,
    SLOWED,
    BLOCKED,
    PROTECTION_OFF
}

data class ManagedDevice(
    val deviceId: String,
    val deviceName: String,
    val dailyLimitMb: Long = 1024L,
    val usedMb: Long = 0L,
    val slowdownThresholdPercent: Int = 80,
    val whitelistedPackages: List<String> = emptyList(),
    val protectionActive: Boolean = false,
    val uninstallProtectionActive: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.NORMAL,
    val lastSeenMillis: Long = 0L,
    val isOnline: Boolean = false,
    val tamperWarning: String? = null
) {
    val usagePercent: Int
        get() = if (dailyLimitMb <= 0L) 100 else ((usedMb * 100) / dailyLimitMb).toInt().coerceIn(0, 100)

    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("dailyLimitMb", dailyLimitMb)
        put("usedMb", usedMb)
        put("slowdownThresholdPercent", slowdownThresholdPercent)
        put("whitelistedPackages", JSONArray(whitelistedPackages))
        put("protectionActive", protectionActive)
        put("uninstallProtectionActive", uninstallProtectionActive)
        put("connectionState", connectionState.name)
        put("lastSeenMillis", lastSeenMillis)
        put("isOnline", isOnline)
        put("tamperWarning", tamperWarning ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(obj: JSONObject): ManagedDevice {
            val pkgArray = obj.optJSONArray("whitelistedPackages") ?: JSONArray()
            val pkgs = mutableListOf<String>()
            for (i in 0 until pkgArray.length()) {
                pkgs.add(pkgArray.optString(i))
            }
            val stateStr = obj.optString("connectionState", ConnectionState.NORMAL.name)
            val state = runCatching { ConnectionState.valueOf(stateStr) }.getOrDefault(ConnectionState.NORMAL)
            val warning = if (obj.isNull("tamperWarning")) null else obj.optString("tamperWarning", null)
            return ManagedDevice(
                deviceId = obj.optString("deviceId", ""),
                deviceName = obj.optString("deviceName", "جهاز غير معروف"),
                dailyLimitMb = obj.optLong("dailyLimitMb", 1024L),
                usedMb = obj.optLong("usedMb", 0L),
                slowdownThresholdPercent = obj.optInt("slowdownThresholdPercent", 80),
                whitelistedPackages = pkgs,
                protectionActive = obj.optBoolean("protectionActive", false),
                uninstallProtectionActive = obj.optBoolean("uninstallProtectionActive", false),
                connectionState = state,
                lastSeenMillis = obj.optLong("lastSeenMillis", 0L),
                isOnline = obj.optBoolean("isOnline", false),
                tamperWarning = warning
            )
        }
    }
}

data class TamperAlert(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val message: String,
    val timestampMillis: Long,
    val acknowledged: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("message", message)
        put("timestampMillis", timestampMillis)
        put("acknowledged", acknowledged)
    }

    companion object {
        fun fromJson(obj: JSONObject): TamperAlert = TamperAlert(
            id = obj.optString("id", ""),
            deviceId = obj.optString("deviceId", ""),
            deviceName = obj.optString("deviceName", "جهاز"),
            message = obj.optString("message", ""),
            timestampMillis = obj.optLong("timestampMillis", 0L),
            acknowledged = obj.optBoolean("acknowledged", false)
        )
    }
}

data class InstalledAppItem(
    val packageName: String,
    val appName: String
)

fun formatDataSizeArabic(mb: Long): String {
    return if (mb >= 1024L) {
        val gb = mb / 1024.0
        String.format(java.util.Locale("ar"), "%.1f ج.ب", gb)
    } else {
        "$mb م.ب"
    }
}

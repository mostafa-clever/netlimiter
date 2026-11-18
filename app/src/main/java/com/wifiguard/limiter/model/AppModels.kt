package com.wifiguard.limiter.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

enum class AppRole {
    UNSET,
    SUPERVISOR,
    CLIENT
}

enum class LimitPeriod(val arabicLabel: String) {
    DAILY("يومي"),
    WEEKLY("أسبوعي"),
    MONTHLY("شهري")
}

enum class ConnectionState {
    NORMAL,
    SMART_SAVING,
    SLOWED,
    GRACE_EXTRA,
    IDLE_FROZEN,
    OUTSIDE_TARGET_WIFI,
    BLOCKED,
    PROTECTION_OFF
}

data class SpeedPolicyConfig(
    // Base speed cap in MB/s (0.0 = unlimited / full speed)
    val baseSpeedLimitMbPerSec: Double = 3.0,
    // Phased slowdown rules
    val slowdownStartPercent: Int = 50,
    val slowdownInitialSpeedPercent: Int = 40,
    val slowdownStepPerPercent: Int = 1,
    val finalPhaseRemainingPercent: Int = 20,
    val finalPhaseSpeedMbPerSec: Double = 1.0,
    // Extra mile / grace buffer after 100% (counted in weekly/monthly limits)
    val graceExtraPercent: Int = 10,
    val graceSpeedMbPerSec: Double = 0.25,
    // Automatic daily unused quota rollover (%)
    val rolloverPercent: Int = 0,
    // Customizable Quota Saver Mode & Auto-Trigger
    val quotaSaverModeEnabled: Boolean = true,
    val autoEnableSaverAtPercent: Int = 0, // 0 = from start, 1..99 = auto-enable at X%, 100 = manual/forced only
    val dnsSinkholeEnabled: Boolean = true,
    val dnsCacheEnabled: Boolean = true,
    val blockDohDotEnabled: Boolean = true,
    val blockQuicUdp443Enabled: Boolean = true,
    val idleScreenFreezeEnabled: Boolean = true,
    val peakHoursSaverEnabled: Boolean = true,
    val peakHoursSpeedMbPerSec: Double = 1.5,
    val burstGuardEnabled: Boolean = true,
    val burstThresholdMbPerMin: Int = 50,
    val burstCapSpeedMbPerSec: Double = 1.0,
    val nightSaverEnabled: Boolean = false,
    val nightSaverSpeedMbPerSec: Double = 0.5,
    val upstreamDnsIp: String = "94.140.14.14",
    val appQuotaPercents: Map<String, Int> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("baseSpeedLimitMbPerSec", baseSpeedLimitMbPerSec)
        put("slowdownStartPercent", slowdownStartPercent)
        put("slowdownInitialSpeedPercent", slowdownInitialSpeedPercent)
        put("slowdownStepPerPercent", slowdownStepPerPercent)
        put("finalPhaseRemainingPercent", finalPhaseRemainingPercent)
        put("finalPhaseSpeedMbPerSec", finalPhaseSpeedMbPerSec)
        put("graceExtraPercent", graceExtraPercent)
        put("graceSpeedMbPerSec", graceSpeedMbPerSec)
        put("rolloverPercent", rolloverPercent)
        put("quotaSaverModeEnabled", quotaSaverModeEnabled)
        put("autoEnableSaverAtPercent", autoEnableSaverAtPercent)
        put("dnsSinkholeEnabled", dnsSinkholeEnabled)
        put("dnsCacheEnabled", dnsCacheEnabled)
        put("blockDohDotEnabled", blockDohDotEnabled)
        put("blockQuicUdp443Enabled", blockQuicUdp443Enabled)
        put("idleScreenFreezeEnabled", idleScreenFreezeEnabled)
        put("peakHoursSaverEnabled", peakHoursSaverEnabled)
        put("peakHoursSpeedMbPerSec", peakHoursSpeedMbPerSec)
        put("burstGuardEnabled", burstGuardEnabled)
        put("burstThresholdMbPerMin", burstThresholdMbPerMin)
        put("burstCapSpeedMbPerSec", burstCapSpeedMbPerSec)
        put("nightSaverEnabled", nightSaverEnabled)
        put("nightSaverSpeedMbPerSec", nightSaverSpeedMbPerSec)
        put("upstreamDnsIp", upstreamDnsIp)
        put("appQuotaPercents", JSONObject(appQuotaPercents.mapValues { it.value }))
    }

    companion object {
        fun fromJson(obj: JSONObject?): SpeedPolicyConfig {
            if (obj == null) return SpeedPolicyConfig()
            return SpeedPolicyConfig(
                baseSpeedLimitMbPerSec = obj.optDouble("baseSpeedLimitMbPerSec", 3.0).coerceAtLeast(0.0),
                slowdownStartPercent = obj.optInt("slowdownStartPercent", 50).coerceIn(10, 95),
                slowdownInitialSpeedPercent = obj.optInt("slowdownInitialSpeedPercent", 40).coerceIn(5, 100),
                slowdownStepPerPercent = obj.optInt("slowdownStepPerPercent", 1).coerceIn(1, 5),
                finalPhaseRemainingPercent = obj.optInt("finalPhaseRemainingPercent", 20).coerceIn(1, 50),
                finalPhaseSpeedMbPerSec = obj.optDouble("finalPhaseSpeedMbPerSec", 1.0).coerceAtLeast(0.1),
                graceExtraPercent = obj.optInt("graceExtraPercent", 10).coerceIn(0, 50),
                graceSpeedMbPerSec = obj.optDouble("graceSpeedMbPerSec", 0.25).coerceAtLeast(0.05),
                rolloverPercent = obj.optInt("rolloverPercent", 0).coerceIn(0, 100),
                quotaSaverModeEnabled = obj.optBoolean("quotaSaverModeEnabled", true),
                autoEnableSaverAtPercent = obj.optInt("autoEnableSaverAtPercent", 0).coerceIn(0, 100),
                dnsSinkholeEnabled = obj.optBoolean("dnsSinkholeEnabled", true),
                dnsCacheEnabled = obj.optBoolean("dnsCacheEnabled", true),
                blockDohDotEnabled = obj.optBoolean("blockDohDotEnabled", true),
                blockQuicUdp443Enabled = obj.optBoolean("blockQuicUdp443Enabled", true),
                idleScreenFreezeEnabled = obj.optBoolean("idleScreenFreezeEnabled", true),
                peakHoursSaverEnabled = obj.optBoolean("peakHoursSaverEnabled", true),
                peakHoursSpeedMbPerSec = obj.optDouble("peakHoursSpeedMbPerSec", 1.5).coerceAtLeast(0.1),
                burstGuardEnabled = obj.optBoolean("burstGuardEnabled", true),
                burstThresholdMbPerMin = obj.optInt("burstThresholdMbPerMin", 50).coerceIn(10, 500),
                burstCapSpeedMbPerSec = obj.optDouble("burstCapSpeedMbPerSec", 1.0).coerceAtLeast(0.1),
                nightSaverEnabled = obj.optBoolean("nightSaverEnabled", false),
                nightSaverSpeedMbPerSec = obj.optDouble("nightSaverSpeedMbPerSec", 0.5).coerceAtLeast(0.1),
                upstreamDnsIp = obj.optString("upstreamDnsIp", "94.140.14.14").ifBlank { "94.140.14.14" },
                appQuotaPercents = parseAppQuotaMap(obj.optJSONObject("appQuotaPercents"))
            )
        }

        private fun parseAppQuotaMap(obj: JSONObject?): Map<String, Int> {
            if (obj == null) return emptyMap()
            val map = mutableMapOf<String, Int>()
            obj.keys().forEach { k ->
                val v = obj.optInt(k, 0)
                if (v in 1..100) map[k] = v
            }
            return map
        }

        fun presetBalanced() = SpeedPolicyConfig()

        fun presetMaxSaver() = SpeedPolicyConfig(
            baseSpeedLimitMbPerSec = 2.0,
            slowdownStartPercent = 40,
            slowdownInitialSpeedPercent = 35,
            slowdownStepPerPercent = 1,
            finalPhaseRemainingPercent = 25,
            finalPhaseSpeedMbPerSec = 0.5,
            graceExtraPercent = 10,
            graceSpeedMbPerSec = 0.25,
            rolloverPercent = 50,
            quotaSaverModeEnabled = true,
            autoEnableSaverAtPercent = 0,
            dnsSinkholeEnabled = true,
            dnsCacheEnabled = true,
            blockDohDotEnabled = true,
            blockQuicUdp443Enabled = true,
            idleScreenFreezeEnabled = true,
            peakHoursSaverEnabled = true,
            peakHoursSpeedMbPerSec = 1.0,
            burstGuardEnabled = true,
            burstThresholdMbPerMin = 30,
            burstCapSpeedMbPerSec = 0.75,
            nightSaverEnabled = true,
            nightSaverSpeedMbPerSec = 0.3
        )

        fun presetRelaxed() = SpeedPolicyConfig(
            baseSpeedLimitMbPerSec = 0.0,
            slowdownStartPercent = 75,
            slowdownInitialSpeedPercent = 50,
            slowdownStepPerPercent = 1,
            finalPhaseRemainingPercent = 10,
            finalPhaseSpeedMbPerSec = 1.5,
            graceExtraPercent = 15,
            graceSpeedMbPerSec = 0.5,
            rolloverPercent = 50,
            quotaSaverModeEnabled = false,
            autoEnableSaverAtPercent = 70,
            dnsSinkholeEnabled = true,
            dnsCacheEnabled = true,
            blockDohDotEnabled = false,
            blockQuicUdp443Enabled = false,
            idleScreenFreezeEnabled = false,
            peakHoursSaverEnabled = false,
            burstGuardEnabled = false,
            nightSaverEnabled = false
        )
    }
}

data class EffectiveSpeedStatus(
    val state: ConnectionState,
    val targetBytesPerSec: Long,
    val activeSpeedPercent: Int,
    val saverActive: Boolean,
    val statusLabelArabic: String
)

fun isQuotaSaverActive(
    usedMb: Long,
    limitMb: Long,
    policy: SpeedPolicyConfig,
    forcedBySupervisor: Boolean
): Boolean {
    if (forcedBySupervisor || policy.quotaSaverModeEnabled) return true
    if (policy.autoEnableSaverAtPercent < 100 && limitMb > 0L) {
        val usedPercent = ((usedMb * 100.0) / limitMb).coerceIn(0.0, 100.0)
        if (usedPercent >= policy.autoEnableSaverAtPercent.toDouble()) return true
    }
    return false
}

fun computeEffectiveSpeed(
    usedMb: Long,
    limitMb: Long,
    policy: SpeedPolicyConfig,
    forcedBySupervisor: Boolean = false,
    globalSpeedCapMbPerSec: Double = 0.0,
    burstDetected: Boolean = false,
    isScreenOffIdle: Boolean = false
): EffectiveSpeedStatus {
    val graceMb = (limitMb * policy.graceExtraPercent) / 100L
    val hardCapMb = limitMb + graceMb
    val saverActive = isQuotaSaverActive(usedMb, limitMb, policy, forcedBySupervisor)

    if (limitMb <= 0L || usedMb >= hardCapMb) {
        return EffectiveSpeedStatus(
            state = ConnectionState.BLOCKED,
            targetBytesPerSec = 0L,
            activeSpeedPercent = 0,
            saverActive = saverActive,
            statusLabelArabic = "انتهى الرصيد بالكامل — التطبيقات المسموحة فقط تعمل"
        )
    }

    if (saverActive && policy.idleScreenFreezeEnabled && isScreenOffIdle) {
        return EffectiveSpeedStatus(
            state = ConnectionState.IDLE_FROZEN,
            targetBytesPerSec = 0L,
            activeSpeedPercent = 0,
            saverActive = true,
            statusLabelArabic = "تجميد الخلفية (الشاشة مغلقة) لتوفير الباقة"
        )
    }

    if (usedMb >= limitMb) {
        val graceBytesPerSec = (policy.graceSpeedMbPerSec * 125_000.0).toLong().coerceAtLeast(8_000L)
        val extraLeftMb = (hardCapMb - usedMb).coerceAtLeast(0L)
        val speedStr = formatSpeedArabic(policy.graceSpeedMbPerSec)
        return EffectiveSpeedStatus(
            state = ConnectionState.GRACE_EXTRA,
            targetBytesPerSec = graceBytesPerSec,
            activeSpeedPercent = 5,
            saverActive = saverActive,
            statusLabelArabic = "رصيد إضافي ($speedStr) • متبقي ${formatDataSizeArabic(extraLeftMb)}"
        )
    }

    val effectiveBaseMbPerSec = when {
        policy.baseSpeedLimitMbPerSec > 0.0 -> policy.baseSpeedLimitMbPerSec
        globalSpeedCapMbPerSec > 0.0 -> globalSpeedCapMbPerSec
        else -> 0.0
    }

    val baseBytesPerSec = if (effectiveBaseMbPerSec > 0.0) {
        (effectiveBaseMbPerSec * 125_000.0).toLong().coerceAtLeast(8_000L)
    } else {
        25L * 125_000L
    }

    val usedPercent = ((usedMb * 100.0) / limitMb).coerceIn(0.0, 100.0)
    val remainingPercent = 100.0 - usedPercent
    val finalPhaseBytesPerSec = (policy.finalPhaseSpeedMbPerSec * 125_000.0).toLong().coerceAtLeast(8_000L)

    if (remainingPercent <= policy.finalPhaseRemainingPercent.toDouble()) {
        val capped = if (globalSpeedCapMbPerSec > 0.0) {
            minOf(finalPhaseBytesPerSec, (globalSpeedCapMbPerSec * 125_000.0).toLong())
        } else finalPhaseBytesPerSec
        val speedStr = formatSpeedArabic(capped / 125_000.0)
        return EffectiveSpeedStatus(
            state = ConnectionState.SLOWED,
            targetBytesPerSec = capped,
            activeSpeedPercent = 10,
            saverActive = saverActive,
            statusLabelArabic = "المرحلة الأخيرة • سرعة $speedStr"
        )
    }

    if (usedPercent >= policy.slowdownStartPercent.toDouble()) {
        val stepsUsed = (usedPercent - policy.slowdownStartPercent).toInt().coerceAtLeast(0)
        val speedPct = (policy.slowdownInitialSpeedPercent - (stepsUsed * policy.slowdownStepPerPercent)).coerceIn(1, 100)
        val computedBytes = maxOf(finalPhaseBytesPerSec, (baseBytesPerSec * speedPct) / 100L)
        val mbps = computedBytes / 125_000.0
        return EffectiveSpeedStatus(
            state = ConnectionState.SLOWED,
            targetBytesPerSec = computedBytes,
            activeSpeedPercent = speedPct,
            saverActive = saverActive,
            statusLabelArabic = "تهدئة تدريجية • $speedPct% (${formatSpeedArabic(mbps)})"
        )
    }

    var targetBytes = if (effectiveBaseMbPerSec > 0.0) baseBytesPerSec else Long.MAX_VALUE
    var state = if (saverActive || effectiveBaseMbPerSec > 0.0) ConnectionState.SMART_SAVING else ConnectionState.NORMAL
    var label = when {
        saverActive && effectiveBaseMbPerSec > 0.0 -> "توفير ذكي • ${formatSpeedArabic(effectiveBaseMbPerSec)}"
        saverActive -> "توفير ذكي نشط (حجب الإعلانات)"
        effectiveBaseMbPerSec > 0.0 -> "سرعة محددة • ${formatSpeedArabic(effectiveBaseMbPerSec)}"
        else -> "متصل بسرعة كاملة"
    }

    if (saverActive) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (policy.nightSaverEnabled && (hour in 1..6)) {
            val nightBytes = (policy.nightSaverSpeedMbPerSec * 125_000.0).toLong().coerceAtLeast(8_000L)
            targetBytes = minOf(targetBytes, nightBytes)
            state = ConnectionState.SMART_SAVING
            label = "توفير ليلي • ${formatSpeedArabic(targetBytes / 125_000.0)}"
        } else if (policy.peakHoursSaverEnabled && (hour in 18..23)) {
            val peakBytes = (policy.peakHoursSpeedMbPerSec * 125_000.0).toLong().coerceAtLeast(8_000L)
            targetBytes = minOf(targetBytes, peakBytes)
            state = ConnectionState.SMART_SAVING
            label = "ساعات الذروة • ${formatSpeedArabic(targetBytes / 125_000.0)}"
        }

        if (policy.burstGuardEnabled && burstDetected) {
            val burstBytes = (policy.burstCapSpeedMbPerSec * 125_000.0).toLong().coerceAtLeast(8_000L)
            targetBytes = minOf(targetBytes, burstBytes)
            state = ConnectionState.SMART_SAVING
            label = "حماية السحب المفاجئ • ${formatSpeedArabic(targetBytes / 125_000.0)}"
        }
    }

    return EffectiveSpeedStatus(
        state = state,
        targetBytesPerSec = targetBytes,
        activeSpeedPercent = 100,
        saverActive = saverActive,
        statusLabelArabic = label
    )
}

fun formatSpeedArabic(mbPerSec: Double): String {
    return if (mbPerSec <= 0.0) {
        "سرعة كاملة"
    } else if (mbPerSec % 1.0 == 0.0) {
        "${mbPerSec.toInt()} ميجابت/ث"
    } else {
        String.format(java.util.Locale("ar"), "%.2f ميجابت/ث", mbPerSec)
            .replace("0 ميجابت/ث", " ميجابت/ث")
    }
}

data class ManagedDevice(
    val deviceId: String,
    val deviceName: String,
    val limitPeriod: LimitPeriod = LimitPeriod.DAILY,
    val dailyLimitMb: Long = 1024L,
    val usedMb: Long = 0L,
    val savedMbToday: Long = 0L,
    val dailyUsageHistory: Map<String, Long> = emptyMap(),
    val dailySavedHistory: Map<String, Long> = emptyMap(),
    val forceQuotaSaver: Boolean = false,
    val speedPolicy: SpeedPolicyConfig = SpeedPolicyConfig(),
    val whitelistedPackages: List<String> = emptyList(),
    val installedApps: List<InstalledAppItem> = emptyList(),
    val protectionActive: Boolean = false,
    val uninstallProtectionActive: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.SMART_SAVING,
    val lastKnownIp: String = "",
    val macAddress: String = "",
    val lastSeenMillis: Long = 0L,
    val isOnline: Boolean = false,
    val pendingConfigPush: Boolean = false,
    val tamperWarning: String? = null
) {
    val usagePercent: Int
        get() = if (dailyLimitMb <= 0L) 100 else ((usedMb * 100) / dailyLimitMb).toInt().coerceIn(0, 100)

    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("limitPeriod", limitPeriod.name)
        put("dailyLimitMb", dailyLimitMb)
        put("usedMb", usedMb)
        put("savedMbToday", savedMbToday)
        put("dailyUsageHistory", JSONObject(dailyUsageHistory.mapValues { it.value }))
        put("dailySavedHistory", JSONObject(dailySavedHistory.mapValues { it.value }))
        put("forceQuotaSaver", forceQuotaSaver)
        put("speedPolicy", speedPolicy.toJson())
        put("whitelistedPackages", JSONArray(whitelistedPackages))
        val appsArr = JSONArray()
        installedApps.forEach { app ->
            appsArr.put(JSONObject().apply {
                put("packageName", app.packageName)
                put("appName", app.appName)
            })
        }
        put("installedApps", appsArr)
        put("protectionActive", protectionActive)
        put("uninstallProtectionActive", uninstallProtectionActive)
        put("connectionState", connectionState.name)
        put("lastKnownIp", lastKnownIp)
        put("macAddress", macAddress)
        put("lastSeenMillis", lastSeenMillis)
        put("isOnline", isOnline)
        put("pendingConfigPush", pendingConfigPush)
        put("tamperWarning", tamperWarning ?: JSONObject.NULL)
    }

    companion object {
        private fun parseHistoryMap(json: JSONObject?): Map<String, Long> {
            if (json == null) return emptyMap()
            val map = mutableMapOf<String, Long>()
            json.keys().forEach { k -> map[k] = json.optLong(k, 0L) }
            return map
        }

        fun fromJson(obj: JSONObject): ManagedDevice {
            val pkgArray = obj.optJSONArray("whitelistedPackages") ?: JSONArray()
            val pkgs = mutableListOf<String>()
            for (i in 0 until pkgArray.length()) {
                pkgs.add(pkgArray.optString(i))
            }
            val appsJson = obj.optJSONArray("installedApps") ?: JSONArray()
            val appsList = mutableListOf<InstalledAppItem>()
            for (i in 0 until appsJson.length()) {
                val item = appsJson.optJSONObject(i) ?: continue
                val pkg = item.optString("packageName", "")
                val name = item.optString("appName", pkg)
                if (pkg.isNotBlank()) appsList.add(InstalledAppItem(pkg, name))
            }
            val periodStr = obj.optString("limitPeriod", LimitPeriod.DAILY.name)
            val period = runCatching { LimitPeriod.valueOf(periodStr) }.getOrDefault(LimitPeriod.DAILY)
            val stateStr = obj.optString("connectionState", ConnectionState.SMART_SAVING.name)
            val state = runCatching { ConnectionState.valueOf(stateStr) }.getOrDefault(ConnectionState.SMART_SAVING)
            val warning = if (obj.isNull("tamperWarning")) null else obj.optString("tamperWarning", null)
            return ManagedDevice(
                deviceId = obj.optString("deviceId", ""),
                deviceName = obj.optString("deviceName", "جهاز غير معروف"),
                limitPeriod = period,
                dailyLimitMb = obj.optLong("dailyLimitMb", 1024L),
                usedMb = obj.optLong("usedMb", 0L),
                savedMbToday = obj.optLong("savedMbToday", 0L),
                dailyUsageHistory = parseHistoryMap(obj.optJSONObject("dailyUsageHistory")),
                dailySavedHistory = parseHistoryMap(obj.optJSONObject("dailySavedHistory")),
                forceQuotaSaver = obj.optBoolean("forceQuotaSaver", false),
                speedPolicy = SpeedPolicyConfig.fromJson(obj.optJSONObject("speedPolicy")),
                whitelistedPackages = pkgs,
                installedApps = appsList,
                protectionActive = obj.optBoolean("protectionActive", false),
                uninstallProtectionActive = obj.optBoolean("uninstallProtectionActive", false),
                connectionState = state,
                lastKnownIp = obj.optString("lastKnownIp", ""),
                macAddress = obj.optString("macAddress", ""),
                lastSeenMillis = obj.optLong("lastSeenMillis", 0L),
                isOnline = obj.optBoolean("isOnline", false),
                pendingConfigPush = obj.optBoolean("pendingConfigPush", false),
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


fun getInstalledLaunchableApps(context: android.content.Context): List<InstalledAppItem> {
    val pm = context.packageManager
    return runCatching {
        pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            .filter {
                (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .filter { it.packageName != context.packageName }
            .map {
                InstalledAppItem(
                    packageName = it.packageName,
                    appName = pm.getApplicationLabel(it).toString()
                )
            }
            .sortedBy { it.appName }
    }.getOrDefault(emptyList())
}

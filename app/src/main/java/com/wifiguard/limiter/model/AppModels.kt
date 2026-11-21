package com.wifiguard.limiter.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Locale

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
    PAUSED,
    BLOCKED,
    PROTECTION_OFF
}

fun Double.sanitizeFinite(default: Double, min: Double = 0.0, max: Double = 10_000.0): Double {
    return if (this.isFinite()) this.coerceIn(min, max) else default
}

fun JSONObject.optCleanString(key: String, default: String = ""): String {
    if (!has(key) || isNull(key)) return default
    val raw = optString(key, default)
    return if (raw == "null") default else raw
}

fun isValidIpv4(ip: String): Boolean {
    val trimmed = ip.trim()
    val parts = trimmed.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
            (part.toIntOrNull() in 0..255)
    }
}

fun normalizeArabicDigits(input: String): String {
    if (input.isEmpty()) return input
    val sb = StringBuilder(input.length)
    for (ch in input) {
        when (ch) {
            in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - '\u0660'.code)).toChar())
            in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - '\u06F0'.code)).toChar())
            '\u066B', ',' -> sb.append('.')
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

data class SpeedPolicyConfig(
    // Base speed cap in Mbps (megabits/sec; 0.0 = unlimited / full speed)
    val baseSpeedLimitMbPerSec: Double = 2.0,
    // Phased slowdown rules (kept for schema compatibility)
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
    val appQuotaPercents: Map<String, Int> = emptyMap(),
    val mediaCdnSaverEnabled: Boolean = true,
    val mediaCdnSpeedMbps: Double = 0.75,
    val blockAppUpdatesInSaver: Boolean = true,
    // Time-window pacing limit (e.g. max 50% within a 6-hour window)
    val windowPaceEnabled: Boolean = false,
    val windowHours: Int = 6,
    val windowMaxPercent: Int = 50,
    val windowSlowSpeedMbps: Double = 0.5,
    val meteredWifiSaverEnabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("baseSpeedLimitMbPerSec", baseSpeedLimitMbPerSec.sanitizeFinite(2.0, 0.0, 1000.0))
        put("slowdownStartPercent", slowdownStartPercent.coerceIn(10, 95))
        put("slowdownInitialSpeedPercent", slowdownInitialSpeedPercent.coerceIn(5, 100))
        put("slowdownStepPerPercent", slowdownStepPerPercent.coerceIn(1, 5))
        put("finalPhaseRemainingPercent", finalPhaseRemainingPercent.coerceIn(1, 50))
        put("finalPhaseSpeedMbPerSec", finalPhaseSpeedMbPerSec.sanitizeFinite(1.0, 0.1, 1000.0))
        put("graceExtraPercent", graceExtraPercent.coerceIn(0, 50))
        put("graceSpeedMbPerSec", graceSpeedMbPerSec.sanitizeFinite(0.25, 0.05, 1000.0))
        put("rolloverPercent", rolloverPercent.coerceIn(0, 100))
        put("quotaSaverModeEnabled", quotaSaverModeEnabled)
        put("autoEnableSaverAtPercent", autoEnableSaverAtPercent.coerceIn(0, 100))
        put("dnsSinkholeEnabled", dnsSinkholeEnabled)
        put("dnsCacheEnabled", dnsCacheEnabled)
        put("blockDohDotEnabled", blockDohDotEnabled)
        put("blockQuicUdp443Enabled", blockQuicUdp443Enabled)
        put("idleScreenFreezeEnabled", idleScreenFreezeEnabled)
        put("peakHoursSaverEnabled", peakHoursSaverEnabled)
        put("peakHoursSpeedMbPerSec", peakHoursSpeedMbPerSec.sanitizeFinite(1.5, 0.1, 1000.0))
        put("burstGuardEnabled", burstGuardEnabled)
        put("burstThresholdMbPerMin", burstThresholdMbPerMin.coerceIn(10, 500))
        put("burstCapSpeedMbPerSec", burstCapSpeedMbPerSec.sanitizeFinite(1.0, 0.1, 1000.0))
        put("nightSaverEnabled", nightSaverEnabled)
        put("nightSaverSpeedMbPerSec", nightSaverSpeedMbPerSec.sanitizeFinite(0.5, 0.1, 1000.0))
        put("upstreamDnsIp", if (isValidIpv4(upstreamDnsIp)) upstreamDnsIp.trim() else "94.140.14.14")
        put("appQuotaPercents", JSONObject(appQuotaPercents.filterValues { it in 1..100 }))
        put("mediaCdnSaverEnabled", mediaCdnSaverEnabled)
        put("mediaCdnSpeedMbps", mediaCdnSpeedMbps.sanitizeFinite(0.75, 0.25, 1000.0))
        put("blockAppUpdatesInSaver", blockAppUpdatesInSaver)
        put("windowPaceEnabled", windowPaceEnabled)
        put("windowHours", windowHours.coerceIn(1, 24))
        put("windowMaxPercent", windowMaxPercent.coerceIn(10, 95))
        put("windowSlowSpeedMbps", windowSlowSpeedMbps.sanitizeFinite(0.5, 0.1, 1000.0))
        put("meteredWifiSaverEnabled", meteredWifiSaverEnabled)
    }

    companion object {
        fun fromJson(obj: JSONObject?): SpeedPolicyConfig {
            if (obj == null) return SpeedPolicyConfig()
            val rawDns = obj.optCleanString("upstreamDnsIp", "94.140.14.14").trim()
            val validDns = if (isValidIpv4(rawDns)) rawDns else "94.140.14.14"
            return SpeedPolicyConfig(
                baseSpeedLimitMbPerSec = obj.optDouble("baseSpeedLimitMbPerSec", 2.0).sanitizeFinite(2.0, 0.0, 1000.0),
                slowdownStartPercent = obj.optInt("slowdownStartPercent", 50).coerceIn(10, 95),
                slowdownInitialSpeedPercent = obj.optInt("slowdownInitialSpeedPercent", 40).coerceIn(5, 100),
                slowdownStepPerPercent = obj.optInt("slowdownStepPerPercent", 1).coerceIn(1, 5),
                finalPhaseRemainingPercent = obj.optInt("finalPhaseRemainingPercent", 20).coerceIn(1, 50),
                finalPhaseSpeedMbPerSec = obj.optDouble("finalPhaseSpeedMbPerSec", 1.0).sanitizeFinite(1.0, 0.1, 1000.0),
                graceExtraPercent = obj.optInt("graceExtraPercent", 10).coerceIn(0, 50),
                graceSpeedMbPerSec = obj.optDouble("graceSpeedMbPerSec", 0.25).sanitizeFinite(0.25, 0.05, 1000.0),
                rolloverPercent = obj.optInt("rolloverPercent", 0).coerceIn(0, 100),
                quotaSaverModeEnabled = obj.optBoolean("quotaSaverModeEnabled", true),
                autoEnableSaverAtPercent = obj.optInt("autoEnableSaverAtPercent", 0).coerceIn(0, 100),
                dnsSinkholeEnabled = obj.optBoolean("dnsSinkholeEnabled", true),
                dnsCacheEnabled = obj.optBoolean("dnsCacheEnabled", true),
                blockDohDotEnabled = obj.optBoolean("blockDohDotEnabled", true),
                blockQuicUdp443Enabled = obj.optBoolean("blockQuicUdp443Enabled", true),
                idleScreenFreezeEnabled = obj.optBoolean("idleScreenFreezeEnabled", true),
                peakHoursSaverEnabled = obj.optBoolean("peakHoursSaverEnabled", true),
                peakHoursSpeedMbPerSec = obj.optDouble("peakHoursSpeedMbPerSec", 1.5).sanitizeFinite(1.5, 0.1, 1000.0),
                burstGuardEnabled = obj.optBoolean("burstGuardEnabled", true),
                burstThresholdMbPerMin = obj.optInt("burstThresholdMbPerMin", 50).coerceIn(10, 500),
                burstCapSpeedMbPerSec = obj.optDouble("burstCapSpeedMbPerSec", 1.0).sanitizeFinite(1.0, 0.1, 1000.0),
                nightSaverEnabled = obj.optBoolean("nightSaverEnabled", false),
                nightSaverSpeedMbPerSec = obj.optDouble("nightSaverSpeedMbPerSec", 0.5).sanitizeFinite(0.5, 0.1, 1000.0),
                upstreamDnsIp = validDns,
                appQuotaPercents = parseAppQuotaMap(obj.optJSONObject("appQuotaPercents")),
                mediaCdnSaverEnabled = obj.optBoolean("mediaCdnSaverEnabled", true),
                mediaCdnSpeedMbps = obj.optDouble("mediaCdnSpeedMbps", 0.75).sanitizeFinite(0.75, 0.25, 1000.0),
                blockAppUpdatesInSaver = obj.optBoolean("blockAppUpdatesInSaver", true),
                windowPaceEnabled = obj.optBoolean("windowPaceEnabled", false),
                windowHours = obj.optInt("windowHours", 6).coerceIn(1, 24),
                windowMaxPercent = obj.optInt("windowMaxPercent", 50).coerceIn(10, 95),
                windowSlowSpeedMbps = obj.optDouble("windowSlowSpeedMbps", 0.5).sanitizeFinite(0.5, 0.1, 1000.0),
                meteredWifiSaverEnabled = obj.optBoolean("meteredWifiSaverEnabled", true)
            )
        }

        private fun parseAppQuotaMap(obj: JSONObject?): Map<String, Int> {
            if (obj == null) return emptyMap()
            val map = mutableMapOf<String, Int>()
            obj.keys().forEach { k ->
                val v = obj.optInt(k, 0)
                if (k.isNotBlank() && v in 1..100) map[k] = v
            }
            return map
        }

        fun presetBalanced() = SpeedPolicyConfig()

        fun presetMaxSaver() = SpeedPolicyConfig(
            baseSpeedLimitMbPerSec = 1.5,
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
            nightSaverSpeedMbPerSec = 0.3,
            mediaCdnSpeedMbps = 0.55
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

private fun mbpsToBytesPerSec(mbps: Double): Long {
    val clean = mbps.sanitizeFinite(0.0, 0.0, 1000.0)
    if (clean <= 0.0) return Long.MAX_VALUE
    return (clean * 125_000.0).toLong().coerceAtLeast(8_000L)
}

fun isQuotaSaverActive(
    usedMb: Long,
    limitMb: Long,
    policy: SpeedPolicyConfig,
    forcedBySupervisor: Boolean
): Boolean {
    if (forcedBySupervisor || policy.quotaSaverModeEnabled) return true
    if (policy.autoEnableSaverAtPercent < 100 && limitMb > 0L) {
        val usedPercent = ((usedMb.coerceAtLeast(0L) * 100.0) / limitMb.toDouble()).coerceIn(0.0, 100.0)
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
    isScreenOffIdle: Boolean = false,
    windowUsedMb: Long = 0L
): EffectiveSpeedStatus {
    val safeUsedMb = usedMb.coerceAtLeast(0L)
    val safeLimitMb = limitMb.coerceAtLeast(0L)
    val graceMb = if (safeLimitMb > 0L) {
        ((safeLimitMb.toDouble() / 100.0) * policy.graceExtraPercent.coerceIn(0, 50)).toLong().coerceAtLeast(0L)
    } else 0L
    val hardCapMb = if (safeLimitMb > Long.MAX_VALUE - graceMb) Long.MAX_VALUE else safeLimitMb + graceMb
    val saverActive = isQuotaSaverActive(safeUsedMb, safeLimitMb, policy, forcedBySupervisor)

    if (safeLimitMb <= 0L || safeUsedMb >= hardCapMb) {
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

    val safeGlobalCap = globalSpeedCapMbPerSec.sanitizeFinite(0.0, 0.0, 1000.0)
    val safeBaseCap = policy.baseSpeedLimitMbPerSec.sanitizeFinite(0.0, 0.0, 1000.0)
    val effectiveBaseMbPerSec = when {
        safeBaseCap > 0.0 && safeGlobalCap > 0.0 -> minOf(safeBaseCap, safeGlobalCap)
        safeBaseCap > 0.0 -> safeBaseCap
        safeGlobalCap > 0.0 -> safeGlobalCap
        else -> 0.0
    }
    val baseCapBytes = mbpsToBytesPerSec(effectiveBaseMbPerSec)

    if (safeUsedMb >= safeLimitMb) {
        val graceBytesPerSec = minOf(baseCapBytes, mbpsToBytesPerSec(policy.graceSpeedMbPerSec))
        val extraLeftMb = (hardCapMb - safeUsedMb).coerceAtLeast(0L)
        val speedStr = formatSpeedArabic(graceBytesPerSec / 125_000.0)
        return EffectiveSpeedStatus(
            state = ConnectionState.GRACE_EXTRA,
            targetBytesPerSec = graceBytesPerSec,
            activeSpeedPercent = 5,
            saverActive = saverActive,
            statusLabelArabic = "رصيد إضافي ($speedStr) • متبقي ${formatDataSizeArabic(extraLeftMb)}"
        )
    }

    val usedPercent = ((safeUsedMb * 100.0) / safeLimitMb.toDouble()).coerceIn(0.0, 100.0)
    val remainingPercent = 100.0 - usedPercent

    var targetBytes = baseCapBytes
    var state = if (saverActive || effectiveBaseMbPerSec > 0.0) ConnectionState.SMART_SAVING else ConnectionState.NORMAL
    var activePct = 100
    var label = when {
        saverActive && effectiveBaseMbPerSec > 0.0 -> "توفير ذكي • ${formatSpeedArabic(effectiveBaseMbPerSec)}"
        saverActive -> "توفير ذكي نشط (حجب الإعلانات)"
        effectiveBaseMbPerSec > 0.0 -> "سرعة محددة • ${formatSpeedArabic(effectiveBaseMbPerSec)}"
        else -> "متصل بسرعة كاملة"
    }

    // Apply saver schedules & burst guard first so phases never speed traffic back up
    if (saverActive) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (policy.nightSaverEnabled && (hour in 0..6)) {
            val nightBytes = mbpsToBytesPerSec(policy.nightSaverSpeedMbPerSec)
            if (nightBytes < targetBytes) {
                targetBytes = nightBytes
                state = ConnectionState.SMART_SAVING
                label = "توفير ليلي • ${formatSpeedArabic(targetBytes / 125_000.0)}"
            }
        } else if (policy.peakHoursSaverEnabled && (hour in 18..23)) {
            val peakBytes = mbpsToBytesPerSec(policy.peakHoursSpeedMbPerSec)
            if (peakBytes < targetBytes) {
                targetBytes = peakBytes
                state = ConnectionState.SMART_SAVING
                label = "ساعات الذروة • ${formatSpeedArabic(targetBytes / 125_000.0)}"
            }
        }

        if (policy.burstGuardEnabled && burstDetected) {
            val burstBytes = mbpsToBytesPerSec(policy.burstCapSpeedMbPerSec)
            if (burstBytes < targetBytes) {
                targetBytes = burstBytes
                state = ConnectionState.SMART_SAVING
                label = "حماية السحب المفاجئ • ${formatSpeedArabic(targetBytes / 125_000.0)}"
            }
        }
    }

    // Time-window pacing limit (always bounded by targetBytes so it never speeds up traffic)
    if (policy.windowPaceEnabled && safeLimitMb > 0L) {
        val windowCapMb = ((safeLimitMb.toDouble() / 100.0) * policy.windowMaxPercent.coerceIn(10, 95))
            .toLong().coerceAtLeast(10L)
        val safeWindowUsed = windowUsedMb.coerceIn(0L, safeUsedMb)
        val windowRatio = (safeWindowUsed * 100.0) / windowCapMb.toDouble()
        if (safeWindowUsed >= windowCapMb) {
            val dropBytes = minOf(targetBytes, mbpsToBytesPerSec(policy.windowSlowSpeedMbps))
            targetBytes = dropBytes
            state = ConnectionState.SLOWED
            activePct = 15
            label = "حد فترة ${policy.windowHours} ساعات • سرعة ${formatSpeedArabic(targetBytes / 125_000.0)}"
        } else if (windowRatio >= 80.0) {
            val preSlowBytes = minOf(targetBytes, mbpsToBytesPerSec(policy.finalPhaseSpeedMbPerSec))
            targetBytes = preSlowBytes
            state = ConnectionState.SLOWED
            activePct = 30
            label = "اقتراب من حد ${policy.windowHours} ساعات • ${formatSpeedArabic(targetBytes / 125_000.0)}"
        }
    }

    // Final speed phase (always bounded by targetBytes)
    if (remainingPercent <= policy.finalPhaseRemainingPercent.toDouble()) {
        val finalBytes = minOf(targetBytes, mbpsToBytesPerSec(policy.finalPhaseSpeedMbPerSec))
        targetBytes = finalBytes
        state = ConnectionState.SLOWED
        activePct = 10
        label = "المرحلة الأخيرة • سرعة ${formatSpeedArabic(targetBytes / 125_000.0)}"
    }

    return EffectiveSpeedStatus(
        state = state,
        targetBytesPerSec = targetBytes,
        activeSpeedPercent = activePct,
        saverActive = saverActive,
        statusLabelArabic = label
    )
}

fun formatSpeedArabic(mbPerSec: Double): String {
    if (!mbPerSec.isFinite() || mbPerSec <= 0.0) return "سرعة كاملة"
    val df = DecimalFormat("0.##", DecimalFormatSymbols(Locale.US))
    return "${df.format(mbPerSec)} ميجابت/ث"
}

data class ManagedDevice(
    val deviceId: String,
    val deviceName: String,
    val limitPeriod: LimitPeriod = LimitPeriod.DAILY,
    val dailyLimitMb: Long = 1024L,
    val usedMb: Long = 0L,
    val savedMbToday: Long = 0L,
    val actualSpeedMbps: Double = 0.0,
    val appUsageMb: Map<String, Long> = emptyMap(),
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
    val isPaused: Boolean = false,
    val tamperWarning: String? = null,
    val authToken: String = ""
) {
    val usagePercent: Int
        get() = if (dailyLimitMb <= 0L) {
            if (usedMb > 0L) 100 else 0
        } else {
            ((usedMb.coerceAtLeast(0L) * 100.0) / dailyLimitMb.toDouble()).coerceIn(0.0, 100.0).toInt()
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("limitPeriod", limitPeriod.name)
        put("dailyLimitMb", dailyLimitMb.coerceAtLeast(0L))
        put("usedMb", usedMb.coerceAtLeast(0L))
        put("savedMbToday", savedMbToday.coerceAtLeast(0L))
        put("actualSpeedMbps", actualSpeedMbps.sanitizeFinite(0.0, 0.0, 10_000.0))
        put("appUsageMb", JSONObject(appUsageMb.mapValues { it.value.coerceAtLeast(0L) }))
        put("dailyUsageHistory", JSONObject(dailyUsageHistory.mapValues { it.value.coerceAtLeast(0L) }))
        put("dailySavedHistory", JSONObject(dailySavedHistory.mapValues { it.value.coerceAtLeast(0L) }))
        put("forceQuotaSaver", forceQuotaSaver)
        put("speedPolicy", speedPolicy.toJson())
        put("whitelistedPackages", JSONArray(whitelistedPackages.filter { it.isNotBlank() }.distinct()))
        val appsArr = JSONArray()
        installedApps.distinctBy { it.packageName }.forEach { app ->
            if (app.packageName.isNotBlank()) {
                appsArr.put(JSONObject().apply {
                    put("packageName", app.packageName)
                    put("appName", app.appName.ifBlank { app.packageName })
                })
            }
        }
        put("installedApps", appsArr)
        put("protectionActive", protectionActive)
        put("uninstallProtectionActive", uninstallProtectionActive)
        put("connectionState", connectionState.name)
        put("lastKnownIp", lastKnownIp)
        put("macAddress", macAddress)
        put("lastSeenMillis", lastSeenMillis.coerceAtLeast(0L))
        put("isOnline", isOnline)
        put("pendingConfigPush", pendingConfigPush)
        put("isPaused", isPaused)
        put("tamperWarning", tamperWarning ?: JSONObject.NULL)
        put("authToken", authToken)
    }

    companion object {
        private fun parseHistoryMap(json: JSONObject?): Map<String, Long> {
            if (json == null) return emptyMap()
            val map = mutableMapOf<String, Long>()
            json.keys().forEach { k ->
                if (k.isNotBlank()) map[k] = json.optLong(k, 0L).coerceAtLeast(0L)
            }
            return map
        }

        fun fromJson(obj: JSONObject): ManagedDevice {
            val pkgArray = obj.optJSONArray("whitelistedPackages") ?: JSONArray()
            val pkgs = mutableListOf<String>()
            for (i in 0 until pkgArray.length()) {
                if (!pkgArray.isNull(i)) {
                    val p = pkgArray.optString(i, "").trim()
                    if (p.isNotBlank() && p != "null") pkgs.add(p)
                }
            }
            val appsJson = obj.optJSONArray("installedApps") ?: JSONArray()
            val appsList = mutableListOf<InstalledAppItem>()
            for (i in 0 until appsJson.length()) {
                val item = appsJson.optJSONObject(i) ?: continue
                val pkg = item.optCleanString("packageName", "").trim()
                val name = item.optCleanString("appName", pkg).ifBlank { pkg }
                if (pkg.isNotBlank()) appsList.add(InstalledAppItem(pkg, name))
            }
            val periodStr = obj.optCleanString("limitPeriod", LimitPeriod.DAILY.name)
            val period = runCatching { LimitPeriod.valueOf(periodStr) }.getOrDefault(LimitPeriod.DAILY)
            val stateStr = obj.optCleanString("connectionState", ConnectionState.SMART_SAVING.name)
            val state = runCatching { ConnectionState.valueOf(stateStr) }.getOrDefault(ConnectionState.SMART_SAVING)
            val warning = obj.optCleanString("tamperWarning", "").ifBlank { null }
            return ManagedDevice(
                deviceId = obj.optCleanString("deviceId", ""),
                deviceName = obj.optCleanString("deviceName", "جهاز غير معروف").ifBlank { "جهاز غير معروف" },
                limitPeriod = period,
                dailyLimitMb = obj.optLong("dailyLimitMb", 1024L).coerceAtLeast(10L),
                usedMb = obj.optLong("usedMb", 0L).coerceAtLeast(0L),
                savedMbToday = obj.optLong("savedMbToday", 0L).coerceAtLeast(0L),
                actualSpeedMbps = obj.optDouble("actualSpeedMbps", 0.0).sanitizeFinite(0.0, 0.0, 10_000.0),
                appUsageMb = parseHistoryMap(obj.optJSONObject("appUsageMb")),
                dailyUsageHistory = parseHistoryMap(obj.optJSONObject("dailyUsageHistory")),
                dailySavedHistory = parseHistoryMap(obj.optJSONObject("dailySavedHistory")),
                forceQuotaSaver = obj.optBoolean("forceQuotaSaver", false),
                speedPolicy = SpeedPolicyConfig.fromJson(obj.optJSONObject("speedPolicy")),
                whitelistedPackages = pkgs.distinct(),
                installedApps = appsList.distinctBy { it.packageName },
                protectionActive = obj.optBoolean("protectionActive", false),
                uninstallProtectionActive = obj.optBoolean("uninstallProtectionActive", false),
                connectionState = state,
                lastKnownIp = obj.optCleanString("lastKnownIp", ""),
                macAddress = obj.optCleanString("macAddress", ""),
                lastSeenMillis = obj.optLong("lastSeenMillis", 0L).coerceAtLeast(0L),
                isOnline = obj.optBoolean("isOnline", false),
                pendingConfigPush = obj.optBoolean("pendingConfigPush", false),
                isPaused = obj.optBoolean("isPaused", false),
                tamperWarning = warning,
                authToken = obj.optCleanString("authToken", "")
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
        put("timestampMillis", timestampMillis.coerceAtLeast(0L))
        put("acknowledged", acknowledged)
    }

    companion object {
        fun fromJson(obj: JSONObject): TamperAlert = TamperAlert(
            id = obj.optCleanString("id", ""),
            deviceId = obj.optCleanString("deviceId", ""),
            deviceName = obj.optCleanString("deviceName", "جهاز").ifBlank { "جهاز" },
            message = obj.optCleanString("message", ""),
            timestampMillis = obj.optLong("timestampMillis", 0L).coerceAtLeast(0L),
            acknowledged = obj.optBoolean("acknowledged", false)
        )
    }
}

data class InstalledAppItem(
    val packageName: String,
    val appName: String
)

fun formatDataSizeArabic(mb: Long): String {
    val safeMb = mb.coerceAtLeast(0L)
    return if (safeMb >= 1024L) {
        val gb = safeMb / 1024.0
        val df = DecimalFormat("0.#", DecimalFormatSymbols(Locale.US))
        "${df.format(gb)} ج.ب"
    } else {
        "$safeMb م.ب"
    }
}

fun getInstalledLaunchableApps(context: android.content.Context): List<InstalledAppItem> {
    val pm = context.packageManager
    return runCatching {
        pm.getInstalledApplications(0)
            .filter {
                (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map {
                InstalledAppItem(
                    packageName = it.packageName,
                    appName = runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName)
                )
            }
            .sortedBy { it.appName.lowercase(Locale.getDefault()) }
    }.getOrDefault(emptyList())
}

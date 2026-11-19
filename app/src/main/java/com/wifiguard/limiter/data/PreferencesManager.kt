package com.wifiguard.limiter.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.model.LimitPeriod
import com.wifiguard.limiter.model.ManagedDevice
import com.wifiguard.limiter.model.SpeedPolicyConfig
import com.wifiguard.limiter.model.TamperAlert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

private val Context.dataStore by preferencesDataStore(name = "wifiguard_prefs")

class PreferencesManager(private val context: Context) {

    private object Keys {
        val ROLE = stringPreferencesKey("app_role")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val PAIRING_CODE = stringPreferencesKey("pairing_code")
        val SUPERVISOR_HOST = stringPreferencesKey("supervisor_host")
        val SUPERVISOR_PORT = intPreferencesKey("supervisor_port")
        val SUPERVISOR_PIN = stringPreferencesKey("supervisor_pin")
        val GITHUB_REPO = stringPreferencesKey("github_repo")

        // Limit & period config
        val LIMIT_PERIOD = stringPreferencesKey("limit_period")
        val DAILY_LIMIT_MB = longPreferencesKey("daily_limit_mb")
        val DAILY_BONUS_MB = longPreferencesKey("daily_bonus_mb")
        val SPEED_POLICY_JSON = stringPreferencesKey("speed_policy_json")
        val WHITELIST_PACKAGES = stringSetPreferencesKey("whitelist_packages")
        val SELF_LIMIT_ENABLED = booleanPreferencesKey("self_limit_enabled")

        // Global supervisor settings for all devices
        val GLOBAL_SPEED_CAP_MBPS = doublePreferencesKey("global_speed_cap_mbps")
        val FORCE_QUOTA_SAVER_ALL = booleanPreferencesKey("force_quota_saver_all")
        val TARGET_NETWORK_ONLY = booleanPreferencesKey("target_network_only")
        val TARGET_NETWORK_SIG = stringPreferencesKey("target_network_sig")
        val IS_PAUSED = booleanPreferencesKey("is_paused")

        // Period usage tracking
        val USED_BYTES_DAY = longPreferencesKey("used_bytes_today")
        val SAVED_BYTES_DAY = longPreferencesKey("saved_bytes_today")
        val DAILY_USAGE_HISTORY_JSON = stringPreferencesKey("daily_usage_history_json")
        val DAILY_SAVED_HISTORY_JSON = stringPreferencesKey("daily_saved_history_json")
        val APP_USAGE_BYTES_JSON = stringPreferencesKey("app_usage_bytes_json")
        val APP_USAGE_PERIOD_KEY = stringPreferencesKey("app_usage_period_key")
        val DATE_DAY = stringPreferencesKey("usage_date")
        val USED_BYTES_WEEK = longPreferencesKey("used_bytes_week")
        val DATE_WEEK = stringPreferencesKey("usage_week")
        val USED_BYTES_MONTH = longPreferencesKey("used_bytes_month")
        val DATE_MONTH = stringPreferencesKey("usage_month")

        // Supervisor stored devices & alerts
        val MANAGED_DEVICES_JSON = stringPreferencesKey("managed_devices_json")
        val TAMPER_ALERTS_JSON = stringPreferencesKey("tamper_alerts_json")
    }

    private fun dayStr(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun weekStr(): String = SimpleDateFormat("yyyy-'W'ww", Locale.US).format(Date())
    private fun monthStr(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    val roleFlow: Flow<AppRole> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.ROLE] ?: AppRole.UNSET.name
        runCatching { AppRole.valueOf(raw) }.getOrDefault(AppRole.UNSET)
    }

    val pairingCodeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.PAIRING_CODE] ?: ""
    }

    val deviceNameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEVICE_NAME] ?: Build.MODEL
    }

    val supervisorPinFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SUPERVISOR_PIN] ?: ""
    }

    val githubRepoFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.GITHUB_REPO]?.ifBlank { "mostafa-clever/netlimiter" } ?: "mostafa-clever/netlimiter"
    }

    val limitPeriodFlow: Flow<LimitPeriod> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.LIMIT_PERIOD] ?: LimitPeriod.DAILY.name
        runCatching { LimitPeriod.valueOf(raw) }.getOrDefault(LimitPeriod.DAILY)
    }

    val dailyLimitMbFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        val base = prefs[Keys.DAILY_LIMIT_MB] ?: 1024L
        val bonus = if (prefs[Keys.DATE_DAY] == dayStr()) prefs[Keys.DAILY_BONUS_MB] ?: 0L else 0L
        base + bonus
    }

    val usedMbActivePeriodFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        val period = runCatching {
            LimitPeriod.valueOf(prefs[Keys.LIMIT_PERIOD] ?: LimitPeriod.DAILY.name)
        }.getOrDefault(LimitPeriod.DAILY)
        val bytes = when (period) {
            LimitPeriod.DAILY -> if (prefs[Keys.DATE_DAY] == dayStr()) prefs[Keys.USED_BYTES_DAY] ?: 0L else 0L
            LimitPeriod.WEEKLY -> if (prefs[Keys.DATE_WEEK] == weekStr()) prefs[Keys.USED_BYTES_WEEK] ?: 0L else 0L
            LimitPeriod.MONTHLY -> if (prefs[Keys.DATE_MONTH] == monthStr()) prefs[Keys.USED_BYTES_MONTH] ?: 0L else 0L
        }
        bytes / (1024L * 1024L)
    }

    val speedPolicyFlow: Flow<SpeedPolicyConfig> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.SPEED_POLICY_JSON]
        if (raw.isNullOrBlank()) SpeedPolicyConfig()
        else runCatching { SpeedPolicyConfig.fromJson(JSONObject(raw)) }.getOrDefault(SpeedPolicyConfig())
    }

    val whitelistFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.WHITELIST_PACKAGES] ?: emptySet()
    }

    val selfLimitEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELF_LIMIT_ENABLED] ?: false
    }

    val globalSpeedCapMbpsFlow: Flow<Double> = context.dataStore.data.map { prefs ->
        prefs[Keys.GLOBAL_SPEED_CAP_MBPS] ?: 0.0
    }

    val forceQuotaSaverAllFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.FORCE_QUOTA_SAVER_ALL] ?: false
    }

    val targetNetworkOnlyFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.TARGET_NETWORK_ONLY] ?: false
    }

    val targetNetworkSigFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.TARGET_NETWORK_SIG] ?: ""
    }

    val managedDevicesFlow: Flow<List<ManagedDevice>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.MANAGED_DEVICES_JSON] ?: "[]"
        parseDevicesList(raw)
    }

    val localUsageHistoryFlow: Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
        parseLongMap(prefs[Keys.DAILY_USAGE_HISTORY_JSON] ?: "{}")
    }

    val localSavedHistoryFlow: Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
        parseLongMap(prefs[Keys.DAILY_SAVED_HISTORY_JSON] ?: "{}")
    }

    val tamperAlertsFlow: Flow<List<TamperAlert>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.TAMPER_ALERTS_JSON] ?: "[]"
        parseAlertsList(raw)
    }

    suspend fun getOrCreateDeviceId(): String {
        val current = context.dataStore.data.first()[Keys.DEVICE_ID]
        if (!current.isNullOrBlank()) return current
        val newId = UUID.randomUUID().toString().take(12)
        context.dataStore.edit { it[Keys.DEVICE_ID] = newId }
        return newId
    }

    suspend fun getOrCreatePairingCode(): String {
        val current = context.dataStore.data.first()[Keys.PAIRING_CODE]
        if (!current.isNullOrBlank()) return current
        val code = Random.nextInt(100000, 999999).toString()
        context.dataStore.edit { it[Keys.PAIRING_CODE] = code }
        return code
    }

    suspend fun setRole(role: AppRole, deviceName: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ROLE] = role.name
            if (!deviceName.isNullOrBlank()) {
                prefs[Keys.DEVICE_NAME] = deviceName
            } else if (prefs[Keys.DEVICE_NAME].isNullOrBlank()) {
                prefs[Keys.DEVICE_NAME] = Build.MODEL
            }
        }
        getOrCreateDeviceId()
        if (role == AppRole.SUPERVISOR) {
            getOrCreatePairingCode()
        }
    }

    suspend fun setSupervisorConnection(host: String, port: Int, pairingCode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SUPERVISOR_HOST] = host.trim()
            prefs[Keys.SUPERVISOR_PORT] = port
            prefs[Keys.PAIRING_CODE] = pairingCode.trim()
        }
    }

    suspend fun getSupervisorHost(): String = context.dataStore.data.first()[Keys.SUPERVISOR_HOST] ?: ""
    suspend fun getSupervisorPort(): Int = context.dataStore.data.first()[Keys.SUPERVISOR_PORT] ?: 8765
    suspend fun getDeviceName(): String = context.dataStore.data.first()[Keys.DEVICE_NAME] ?: Build.MODEL

    suspend fun setSupervisorPin(pin: String) {
        context.dataStore.edit { it[Keys.SUPERVISOR_PIN] = pin.trim() }
    }

    suspend fun setGithubRepo(repo: String) {
        context.dataStore.edit { it[Keys.GITHUB_REPO] = repo.trim() }
    }

    suspend fun setSelfLimitEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SELF_LIMIT_ENABLED] = enabled }
    }

    suspend fun setGlobalSpeedCapMbps(mbps: Double) {
        context.dataStore.edit { it[Keys.GLOBAL_SPEED_CAP_MBPS] = mbps.coerceAtLeast(0.0) }
    }

    suspend fun setForceQuotaSaverAll(forced: Boolean) {
        context.dataStore.edit { it[Keys.FORCE_QUOTA_SAVER_ALL] = forced }
    }

    suspend fun setTargetNetwork(onlyTarget: Boolean, signature: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TARGET_NETWORK_ONLY] = onlyTarget
            if (signature != null) prefs[Keys.TARGET_NETWORK_SIG] = signature
        }
    }

    suspend fun updateLocalLimits(
        limitMb: Long,
        limitPeriod: LimitPeriod,
        speedPolicy: SpeedPolicyConfig,
        whitelist: Set<String>,
        globalSpeedCapMbps: Double = 0.0,
        forceQuotaSaver: Boolean? = null,
        targetNetworkOnly: Boolean? = null,
        targetNetworkSig: String? = null,
        isPaused: Boolean? = null
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DAILY_LIMIT_MB] = limitMb.coerceAtLeast(10L)
            prefs[Keys.LIMIT_PERIOD] = limitPeriod.name
            prefs[Keys.SPEED_POLICY_JSON] = speedPolicy.toJson().toString()
            prefs[Keys.WHITELIST_PACKAGES] = whitelist
            prefs[Keys.GLOBAL_SPEED_CAP_MBPS] = globalSpeedCapMbps.coerceAtLeast(0.0)
            if (forceQuotaSaver != null) prefs[Keys.FORCE_QUOTA_SAVER_ALL] = forceQuotaSaver
            if (targetNetworkOnly != null) prefs[Keys.TARGET_NETWORK_ONLY] = targetNetworkOnly
            if (targetNetworkSig != null) prefs[Keys.TARGET_NETWORK_SIG] = targetNetworkSig
            if (isPaused != null) prefs[Keys.IS_PAUSED] = isPaused
        }
    }

    suspend fun applyPolicyToAllDevices(policy: SpeedPolicyConfig) {
        updateAllManagedDevices { list ->
            list.map { it.copy(speedPolicy = policy) }
        }
    }

    suspend fun addUsedBytes(deltaBytes: Long) {
        if (deltaBytes <= 0L) return
        val d = dayStr()
        val w = weekStr()
        val m = monthStr()
        context.dataStore.edit { prefs ->
            val savedDay = prefs[Keys.DATE_DAY] ?: ""
            if (savedDay != d) {
                // New day started: compute unused daily rollover bonus if configured
                val prevUsedMb = (prefs[Keys.USED_BYTES_DAY] ?: 0L) / (1024L * 1024L)
                val baseLimitMb = prefs[Keys.DAILY_LIMIT_MB] ?: 1024L
                val rawPolicy = prefs[Keys.SPEED_POLICY_JSON]
                val rolloverPct = if (!rawPolicy.isNullOrBlank()) {
                    runCatching { SpeedPolicyConfig.fromJson(JSONObject(rawPolicy)).rolloverPercent }.getOrDefault(0)
                } else 0
                val unusedMb = (baseLimitMb - prevUsedMb).coerceAtLeast(0L)
                val bonusMb = if (savedDay.isNotBlank() && rolloverPct > 0) (unusedMb * rolloverPct) / 100L else 0L
                prefs[Keys.DAILY_BONUS_MB] = bonusMb
                prefs[Keys.DATE_DAY] = d
                prefs[Keys.USED_BYTES_DAY] = deltaBytes
            } else {
                val curDay = prefs[Keys.USED_BYTES_DAY] ?: 0L
                prefs[Keys.USED_BYTES_DAY] = curDay + deltaBytes
            }

            val curWeek = if (prefs[Keys.DATE_WEEK] == w) prefs[Keys.USED_BYTES_WEEK] ?: 0L else 0L
            prefs[Keys.DATE_WEEK] = w
            prefs[Keys.USED_BYTES_WEEK] = curWeek + deltaBytes

            val curMonth = if (prefs[Keys.DATE_MONTH] == m) prefs[Keys.USED_BYTES_MONTH] ?: 0L else 0L
            prefs[Keys.DATE_MONTH] = m
            prefs[Keys.USED_BYTES_MONTH] = curMonth + deltaBytes

            val newDayBytes = prefs[Keys.USED_BYTES_DAY] ?: 0L
            val hist = parseLongMap(prefs[Keys.DAILY_USAGE_HISTORY_JSON] ?: "{}").toMutableMap()
            hist[d] = newDayBytes / (1024L * 1024L)
            prefs[Keys.DAILY_USAGE_HISTORY_JSON] = JSONObject(hist as Map<*, *>).toString()
        }
    }

    suspend fun addSavedBytes(deltaBytes: Long) {
        if (deltaBytes <= 0L) return
        val d = dayStr()
        context.dataStore.edit { prefs ->
            val curSaved = if (prefs[Keys.DATE_DAY] == d) prefs[Keys.SAVED_BYTES_DAY] ?: 0L else 0L
            val newSaved = curSaved + deltaBytes
            prefs[Keys.SAVED_BYTES_DAY] = newSaved
            val hist = parseLongMap(prefs[Keys.DAILY_SAVED_HISTORY_JSON] ?: "{}").toMutableMap()
            hist[d] = newSaved / (1024L * 1024L)
            prefs[Keys.DAILY_SAVED_HISTORY_JSON] = JSONObject(hist as Map<*, *>).toString()
        }
    }

    private fun activePeriodKey(period: LimitPeriod): String = when (period) {
        LimitPeriod.DAILY -> dayStr()
        LimitPeriod.WEEKLY -> weekStr()
        LimitPeriod.MONTHLY -> monthStr()
    }

    suspend fun addAppUsedBytes(deltas: Map<String, Long>, period: LimitPeriod) {
        if (deltas.isEmpty()) return
        val pKey = activePeriodKey(period)
        context.dataStore.edit { prefs ->
            val savedKey = prefs[Keys.APP_USAGE_PERIOD_KEY] ?: ""
            val map = if (savedKey == pKey) {
                parseLongMap(prefs[Keys.APP_USAGE_BYTES_JSON] ?: "{}").toMutableMap()
            } else {
                mutableMapOf()
            }
            deltas.forEach { (pkg, d) ->
                if (d > 0L) map[pkg] = (map[pkg] ?: 0L) + d
            }
            prefs[Keys.APP_USAGE_PERIOD_KEY] = pKey
            prefs[Keys.APP_USAGE_BYTES_JSON] = JSONObject(map as Map<*, *>).toString()
        }
    }

    suspend fun getAppUsedMbMap(period: LimitPeriod): Map<String, Long> {
        val prefs = context.dataStore.data.first()
        if (prefs[Keys.APP_USAGE_PERIOD_KEY] != activePeriodKey(period)) return emptyMap()
        return parseLongMap(prefs[Keys.APP_USAGE_BYTES_JSON] ?: "{}").mapValues { it.value / (1024L * 1024L) }
    }

    suspend fun getSavedMbToday(): Long {
        val prefs = context.dataStore.data.first()
        if (prefs[Keys.DATE_DAY] != dayStr()) return 0L
        return (prefs[Keys.SAVED_BYTES_DAY] ?: 0L) / (1024L * 1024L)
    }

    suspend fun addBonusQuotaMb(extraMb: Long) {
        if (extraMb <= 0L) return
        val d = dayStr()
        context.dataStore.edit { prefs ->
            val currentBonus = if (prefs[Keys.DATE_DAY] == d) prefs[Keys.DAILY_BONUS_MB] ?: 0L else 0L
            prefs[Keys.DATE_DAY] = d
            prefs[Keys.DAILY_BONUS_MB] = currentBonus + extraMb
        }
    }

    suspend fun resetUsageCounters() {
        context.dataStore.edit { prefs ->
            prefs[Keys.DATE_DAY] = dayStr()
            prefs[Keys.USED_BYTES_DAY] = 0L
            prefs[Keys.DAILY_BONUS_MB] = 0L
            prefs[Keys.DATE_WEEK] = weekStr()
            prefs[Keys.USED_BYTES_WEEK] = 0L
            prefs[Keys.DATE_MONTH] = monthStr()
            prefs[Keys.USED_BYTES_MONTH] = 0L
            prefs[Keys.APP_USAGE_BYTES_JSON] = "{}"
        }
    }

    data class LocalConfigSnapshot(
        val limitMb: Long,
        val limitPeriod: LimitPeriod,
        val speedPolicy: SpeedPolicyConfig,
        val whitelist: Set<String>,
        val globalSpeedCapMbps: Double,
        val forceQuotaSaver: Boolean,
        val targetNetworkOnly: Boolean,
        val targetNetworkSig: String,
        val isPaused: Boolean
    )

    suspend fun getLocalLimitConfig(): LocalConfigSnapshot {
        val prefs = context.dataStore.data.first()
        val rawPolicy = prefs[Keys.SPEED_POLICY_JSON]
        val policy = if (rawPolicy.isNullOrBlank()) SpeedPolicyConfig()
        else runCatching { SpeedPolicyConfig.fromJson(JSONObject(rawPolicy)) }.getOrDefault(SpeedPolicyConfig())
        val period = runCatching {
            LimitPeriod.valueOf(prefs[Keys.LIMIT_PERIOD] ?: LimitPeriod.DAILY.name)
        }.getOrDefault(LimitPeriod.DAILY)
        val base = prefs[Keys.DAILY_LIMIT_MB] ?: 1024L
        val bonus = if (prefs[Keys.DATE_DAY] == dayStr()) prefs[Keys.DAILY_BONUS_MB] ?: 0L else 0L
        return LocalConfigSnapshot(
            limitMb = base + bonus,
            limitPeriod = period,
            speedPolicy = policy,
            whitelist = prefs[Keys.WHITELIST_PACKAGES] ?: emptySet(),
            globalSpeedCapMbps = prefs[Keys.GLOBAL_SPEED_CAP_MBPS] ?: 0.0,
            forceQuotaSaver = prefs[Keys.FORCE_QUOTA_SAVER_ALL] ?: false,
            targetNetworkOnly = prefs[Keys.TARGET_NETWORK_ONLY] ?: false,
            targetNetworkSig = prefs[Keys.TARGET_NETWORK_SIG] ?: "",
            isPaused = prefs[Keys.IS_PAUSED] ?: false
        )
    }

    suspend fun getUsedMbActivePeriod(): Long {
        val prefs = context.dataStore.data.first()
        val period = runCatching {
            LimitPeriod.valueOf(prefs[Keys.LIMIT_PERIOD] ?: LimitPeriod.DAILY.name)
        }.getOrDefault(LimitPeriod.DAILY)
        val bytes = when (period) {
            LimitPeriod.DAILY -> if (prefs[Keys.DATE_DAY] == dayStr()) prefs[Keys.USED_BYTES_DAY] ?: 0L else 0L
            LimitPeriod.WEEKLY -> if (prefs[Keys.DATE_WEEK] == weekStr()) prefs[Keys.USED_BYTES_WEEK] ?: 0L else 0L
            LimitPeriod.MONTHLY -> if (prefs[Keys.DATE_MONTH] == monthStr()) prefs[Keys.USED_BYTES_MONTH] ?: 0L else 0L
        }
        return bytes / (1024L * 1024L)
    }

    suspend fun upsertManagedDevice(device: ManagedDevice) {
        context.dataStore.edit { prefs ->
            val list = parseDevicesList(prefs[Keys.MANAGED_DEVICES_JSON] ?: "[]").toMutableList()
            val idx = list.indexOfFirst { it.deviceId == device.deviceId }
            if (idx >= 0) list[idx] = device else list.add(device)
            prefs[Keys.MANAGED_DEVICES_JSON] = serializeDevicesList(list)
        }
    }

    suspend fun updateAllManagedDevices(transform: (List<ManagedDevice>) -> List<ManagedDevice>) {
        context.dataStore.edit { prefs ->
            val list = parseDevicesList(prefs[Keys.MANAGED_DEVICES_JSON] ?: "[]")
            prefs[Keys.MANAGED_DEVICES_JSON] = serializeDevicesList(transform(list))
        }
    }

    suspend fun removeManagedDevice(deviceId: String) {
        context.dataStore.edit { prefs ->
            val list = parseDevicesList(prefs[Keys.MANAGED_DEVICES_JSON] ?: "[]")
                .filterNot { it.deviceId == deviceId }
            prefs[Keys.MANAGED_DEVICES_JSON] = serializeDevicesList(list)
        }
    }

    suspend fun addTamperAlert(alert: TamperAlert) {
        context.dataStore.edit { prefs ->
            val list = parseAlertsList(prefs[Keys.TAMPER_ALERTS_JSON] ?: "[]").toMutableList()
            list.add(0, alert)
            if (list.size > 30) list.subList(30, list.size).clear()
            prefs[Keys.TAMPER_ALERTS_JSON] = serializeAlertsList(list)
        }
    }

    suspend fun clearTamperAlerts() {
        context.dataStore.edit { prefs -> prefs[Keys.TAMPER_ALERTS_JSON] = "[]" }
    }

    private fun parseLongMap(jsonStr: String): Map<String, Long> {
        return runCatching {
            val obj = JSONObject(jsonStr)
            val map = mutableMapOf<String, Long>()
            obj.keys().forEach { k -> map[k] = obj.optLong(k, 0L) }
            map
        }.getOrDefault(emptyMap())
    }

    private fun parseDevicesList(jsonStr: String): List<ManagedDevice> {
        return runCatching {
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).map { ManagedDevice.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun serializeDevicesList(list: List<ManagedDevice>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    private fun parseAlertsList(jsonStr: String): List<TamperAlert> {
        return runCatching {
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).map { TamperAlert.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun serializeAlertsList(list: List<TamperAlert>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }
}

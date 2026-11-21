package com.wifiguard.limiter.data

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
import com.wifiguard.limiter.model.sanitizeFinite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.dataStore by preferencesDataStore(
    name = "wifiguard_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class PreferencesManager(context: Context) {

    private val appContext: Context = context.applicationContext

    private object Keys {
        val ROLE = stringPreferencesKey("app_role")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val PAIRING_CODE = stringPreferencesKey("pairing_code")
        val SUPERVISOR_HOST = stringPreferencesKey("supervisor_host")
        val SUPERVISOR_PORT = intPreferencesKey("supervisor_port")
        val CLIENT_AUTH_TOKEN = stringPreferencesKey("client_auth_token")
        val SUPERVISOR_PIN = stringPreferencesKey("supervisor_pin")
        val GITHUB_REPO = stringPreferencesKey("github_repo")

        // Limit & period config
        val LIMIT_PERIOD = stringPreferencesKey("limit_period")
        val DAILY_LIMIT_MB = longPreferencesKey("daily_limit_mb")
        val DAILY_BONUS_MB = longPreferencesKey("daily_bonus_mb")
        val BONUS_PERIOD_KEY = stringPreferencesKey("bonus_period_key")
        val SPEED_POLICY_JSON = stringPreferencesKey("speed_policy_json")
        val WHITELIST_PACKAGES = stringSetPreferencesKey("whitelist_packages")
        val SELF_LIMIT_ENABLED = booleanPreferencesKey("self_limit_enabled")

        // Global supervisor settings for all devices
        val GLOBAL_SPEED_CAP_MBPS = doublePreferencesKey("global_speed_cap_mbps")
        val FORCE_QUOTA_SAVER_ALL = booleanPreferencesKey("force_quota_saver_all")
        val TARGET_NETWORK_ONLY = booleanPreferencesKey("target_network_only")
        val TARGET_NETWORK_SIG = stringPreferencesKey("target_network_sig")
        val IS_PAUSED = booleanPreferencesKey("is_paused")
        val ALWAYS_ON_CONFIRMED = booleanPreferencesKey("always_on_confirmed")

        // Period usage tracking
        val USED_BYTES_DAY = longPreferencesKey("used_bytes_today")
        val SAVED_BYTES_DAY = longPreferencesKey("saved_bytes_today")
        val DAILY_USAGE_HISTORY_JSON = stringPreferencesKey("daily_usage_history_json")
        val DAILY_SAVED_HISTORY_JSON = stringPreferencesKey("daily_saved_history_json")
        val APP_USAGE_BYTES_JSON = stringPreferencesKey("app_usage_bytes_json")
        val APP_USAGE_PERIOD_KEY = stringPreferencesKey("app_usage_period_key")
        val HOURLY_USAGE_JSON = stringPreferencesKey("hourly_usage_json")
        val DATE_DAY = stringPreferencesKey("usage_date")
        val USED_BYTES_WEEK = longPreferencesKey("used_bytes_week")
        val DATE_WEEK = stringPreferencesKey("usage_week")
        val USED_BYTES_MONTH = longPreferencesKey("used_bytes_month")
        val DATE_MONTH = stringPreferencesKey("usage_month")

        // Supervisor stored devices & alerts
        val MANAGED_DEVICES_JSON = stringPreferencesKey("managed_devices_json")
        val TAMPER_ALERTS_JSON = stringPreferencesKey("tamper_alerts_json")
    }

    companion object {
        private const val PIN_PREFIX = "pbkdf2$"
        private const val PBKDF2_ITERATIONS = 10_000
        private const val MAX_HISTORY_DAYS = 90
        private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        private val MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)
        private val secureRandom = SecureRandom()

        fun generateAuthToken(): String {
            val bytes = ByteArray(24)
            secureRandom.nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
        }

        private fun hashPin(pin: String): String {
            val salt = ByteArray(16)
            secureRandom.nextBytes(salt)
            val hash = pbkdf2(pin, salt)
            val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
            val hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP)
            return "$PIN_PREFIX$saltB64$$hashB64"
        }

        private fun verifyStoredPin(stored: String, candidate: String): Boolean {
            if (stored.isBlank()) return true
            val trimmed = candidate.trim()
            if (!stored.startsWith(PIN_PREFIX)) {
                return MessageDigest.isEqual(
                    stored.toByteArray(Charsets.UTF_8),
                    trimmed.toByteArray(Charsets.UTF_8)
                )
            }
            val parts = stored.removePrefix(PIN_PREFIX).split("$")
            if (parts.size != 2) return false
            val salt = runCatching { Base64.decode(parts[0], Base64.NO_WRAP) }.getOrNull() ?: return false
            val expected = runCatching { Base64.decode(parts[1], Base64.NO_WRAP) }.getOrNull() ?: return false
            val actual = pbkdf2(trimmed, salt)
            return MessageDigest.isEqual(expected, actual)
        }

        private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        }
    }

    private fun localDateNow(nowMillis: Long = System.currentTimeMillis()): LocalDate =
        Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun dayStr(date: LocalDate = localDateNow()): String = date.format(DAY_FMT)

    private fun weekStr(date: LocalDate = localDateNow()): String {
        val wf = WeekFields.of(Locale.getDefault())
        val weekYear = date.get(wf.weekBasedYear())
        val weekNum = date.get(wf.weekOfWeekBasedYear())
        return String.format(Locale.US, "%04d-W%02d", weekYear, weekNum)
    }

    private fun monthStr(date: LocalDate = localDateNow()): String = date.format(MONTH_FMT)

    private fun localHourKey(dateStr: String, hourOfDay: Int): String =
        String.format(Locale.US, "%s-%02d", dateStr, hourOfDay.coerceIn(0, 23))

    private fun activePeriodKey(period: LimitPeriod, date: LocalDate = localDateNow()): String = when (period) {
        LimitPeriod.DAILY -> dayStr(date)
        LimitPeriod.WEEKLY -> weekStr(date)
        LimitPeriod.MONTHLY -> monthStr(date)
    }

    private fun ensureDayRolledOver(prefs: MutablePreferences, todayStr: String) {
        val savedDay = prefs[Keys.DATE_DAY] ?: ""
        if (savedDay == todayStr) return

        val period = runCatching {
            LimitPeriod.valueOf(prefs[Keys.LIMIT_PERIOD] ?: LimitPeriod.DAILY.name)
        }.getOrDefault(LimitPeriod.DAILY)

        val bonusMb = if (period == LimitPeriod.DAILY && savedDay.isNotBlank()) {
            val prevUsedMb = (prefs[Keys.USED_BYTES_DAY] ?: 0L).coerceAtLeast(0L) / (1024L * 1024L)
            val baseLimitMb = (prefs[Keys.DAILY_LIMIT_MB] ?: 1024L).coerceAtLeast(10L)
            val rawPolicy = prefs[Keys.SPEED_POLICY_JSON]
            val rolloverPct = if (!rawPolicy.isNullOrBlank()) {
                runCatching { SpeedPolicyConfig.fromJson(JSONObject(rawPolicy)).rolloverPercent }.getOrDefault(0)
            } else 0
            val unusedMb = (baseLimitMb - prevUsedMb).coerceAtLeast(0L)
            if (rolloverPct > 0) ((unusedMb.toDouble() / 100.0) * rolloverPct).toLong().coerceAtLeast(0L) else 0L
        } else 0L

        if (period == LimitPeriod.DAILY) {
            prefs[Keys.DAILY_BONUS_MB] = bonusMb
            prefs[Keys.BONUS_PERIOD_KEY] = todayStr
        }
        prefs[Keys.USED_BYTES_DAY] = 0L
        prefs[Keys.SAVED_BYTES_DAY] = 0L
        prefs[Keys.HOURLY_USAGE_JSON] = "{}"
        prefs[Keys.DATE_DAY] = todayStr
    }

    private fun pruneHistoryMap(map: MutableMap<String, Long>) {
        if (map.size <= MAX_HISTORY_DAYS) return
        val keepKeys = map.keys.sortedDescending().take(MAX_HISTORY_DAYS).toSet()
        map.keys.retainAll(keepKeys)
    }

    val roleFlow: Flow<AppRole> = appContext.dataStore.data.map { prefs ->
        val raw = prefs[Keys.ROLE] ?: AppRole.UNSET.name
        runCatching { AppRole.valueOf(raw) }.getOrDefault(AppRole.UNSET)
    }

    val pairingCodeFlow: Flow<String> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.PAIRING_CODE] ?: ""
    }

    val deviceNameFlow: Flow<String> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.DEVICE_NAME] ?: Build.MODEL
    }

    val hasSupervisorPinFlow: Flow<Boolean> = appContext.dataStore.data.map { prefs ->
        !prefs[Keys.SUPERVISOR_PIN].isNullOrBlank()
    }

    val supervisorPinFlow: Flow<String> = appContext.dataStore.data.map { prefs ->
        if (prefs[Keys.SUPERVISOR_PIN].isNullOrBlank()) "" else "SET"
    }

    suspend fun verifySupervisorPin(candidate: String): Boolean {
        val stored = appContext.dataStore.data.first()[Keys.SUPERVISOR_PIN] ?: ""
        val ok = verifyStoredPin(stored, candidate)
        // Transparently upgrade legacy plaintext PIN to PBKDF2 hash upon successful verification
        if (ok && stored.isNotBlank() && !stored.startsWith(PIN_PREFIX)) {
            appContext.dataStore.edit { it[Keys.SUPERVISOR_PIN] = hashPin(candidate.trim()) }
        }
        return ok
    }

    val alwaysOnConfirmedFlow: Flow<Boolean> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.ALWAYS_ON_CONFIRMED] ?: false
    }

    suspend fun setAlwaysOnConfirmed(confirmed: Boolean) {
        appContext.dataStore.edit { it[Keys.ALWAYS_ON_CONFIRMED] = confirmed }
    }

    val githubRepoFlow: Flow<String> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.GITHUB_REPO]?.ifBlank { "mostafa-clever/netlimiter" } ?: "mostafa-clever/netlimiter"
    }

    val limitPeriodFlow: Flow<LimitPeriod> = appContext.dataStore.data.map { prefs ->
        val raw = prefs[Keys.LIMIT_PERIOD] ?: LimitPeriod.DAILY.name
        runCatching { LimitPeriod.valueOf(raw) }.getOrDefault(LimitPeriod.DAILY)
    }

    val dailyLimitMbFlow: Flow<Long> = appContext.dataStore.data.map { prefs ->
        val period = runCatching {
            LimitPeriod.valueOf(prefs[Keys.LIMIT_PERIOD] ?: LimitPeriod.DAILY.name)
        }.getOrDefault(LimitPeriod.DAILY)
        val base = (prefs[Keys.DAILY_LIMIT_MB] ?: 1024L).coerceAtLeast(10L)
        val expectedBonusKey = activePeriodKey(period)
        val storedBonusKey = prefs[Keys.BONUS_PERIOD_KEY] ?: prefs[Keys.DATE_DAY]
        val bonus = if (storedBonusKey == expectedBonusKey) {
            (prefs[Keys.DAILY_BONUS_MB] ?: 0L).coerceAtLeast(0L)
        } else 0L
        base + bonus
    }

    val usedMbActivePeriodFlow: Flow<Long> = appContext.dataStore.data.map { prefs ->
        val now = localDateNow()
        val period = runCatching {
            LimitPeriod.valueOf(prefs[Keys.LIMIT_PERIOD] ?: LimitPeriod.DAILY.name)
        }.getOrDefault(LimitPeriod.DAILY)
        val bytes = when (period) {
            LimitPeriod.DAILY -> if (prefs[Keys.DATE_DAY] == dayStr(now)) prefs[Keys.USED_BYTES_DAY] ?: 0L else 0L
            LimitPeriod.WEEKLY -> if (prefs[Keys.DATE_WEEK] == weekStr(now)) prefs[Keys.USED_BYTES_WEEK] ?: 0L else 0L
            LimitPeriod.MONTHLY -> if (prefs[Keys.DATE_MONTH] == monthStr(now)) prefs[Keys.USED_BYTES_MONTH] ?: 0L else 0L
        }
        bytes.coerceAtLeast(0L) / (1024L * 1024L)
    }

    val speedPolicyFlow: Flow<SpeedPolicyConfig> = appContext.dataStore.data.map { prefs ->
        val raw = prefs[Keys.SPEED_POLICY_JSON]
        if (raw.isNullOrBlank()) SpeedPolicyConfig()
        else runCatching { SpeedPolicyConfig.fromJson(JSONObject(raw)) }.getOrDefault(SpeedPolicyConfig())
    }

    val whitelistFlow: Flow<Set<String>> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.WHITELIST_PACKAGES] ?: emptySet()
    }

    val selfLimitEnabledFlow: Flow<Boolean> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.SELF_LIMIT_ENABLED] ?: false
    }

    val globalSpeedCapMbpsFlow: Flow<Double> = appContext.dataStore.data.map { prefs ->
        (prefs[Keys.GLOBAL_SPEED_CAP_MBPS] ?: 0.0).sanitizeFinite(0.0, 0.0, 1000.0)
    }

    val forceQuotaSaverAllFlow: Flow<Boolean> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.FORCE_QUOTA_SAVER_ALL] ?: false
    }

    val targetNetworkOnlyFlow: Flow<Boolean> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.TARGET_NETWORK_ONLY] ?: false
    }

    val targetNetworkSigFlow: Flow<String> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.TARGET_NETWORK_SIG] ?: ""
    }

    val managedDevicesFlow: Flow<List<ManagedDevice>> = appContext.dataStore.data.map { prefs ->
        val raw = prefs[Keys.MANAGED_DEVICES_JSON] ?: "[]"
        parseDevicesList(raw)
    }

    val localUsageHistoryFlow: Flow<Map<String, Long>> = appContext.dataStore.data.map { prefs ->
        parseLongMap(prefs[Keys.DAILY_USAGE_HISTORY_JSON] ?: "{}")
    }

    val localSavedHistoryFlow: Flow<Map<String, Long>> = appContext.dataStore.data.map { prefs ->
        parseLongMap(prefs[Keys.DAILY_SAVED_HISTORY_JSON] ?: "{}")
    }

    val localAppUsageMbFlow: Flow<Map<String, Long>> = appContext.dataStore.data.map { prefs ->
        val period = runCatching {
            LimitPeriod.valueOf(prefs[Keys.LIMIT_PERIOD] ?: LimitPeriod.DAILY.name)
        }.getOrDefault(LimitPeriod.DAILY)
        if (prefs[Keys.APP_USAGE_PERIOD_KEY] != activePeriodKey(period)) {
            emptyMap()
        } else {
            parseLongMap(prefs[Keys.APP_USAGE_BYTES_JSON] ?: "{}")
                .mapValues { it.value.coerceAtLeast(0L) / (1024L * 1024L) }
        }
    }

    val tamperAlertsFlow: Flow<List<TamperAlert>> = appContext.dataStore.data.map { prefs ->
        val raw = prefs[Keys.TAMPER_ALERTS_JSON] ?: "[]"
        parseAlertsList(raw)
    }

    suspend fun getOrCreateDeviceId(): String {
        var resolved = ""
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.DEVICE_ID]
            if (!current.isNullOrBlank()) {
                resolved = current
            } else {
                resolved = UUID.randomUUID().toString().replace("-", "").take(16)
                prefs[Keys.DEVICE_ID] = resolved
            }
        }
        return resolved
    }

    suspend fun getOrCreatePairingCode(): String {
        var resolved = ""
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.PAIRING_CODE]
            if (!current.isNullOrBlank()) {
                resolved = current
            } else {
                resolved = (secureRandom.nextInt(900_000) + 100_000).toString()
                prefs[Keys.PAIRING_CODE] = resolved
            }
        }
        return resolved
    }

    suspend fun setRole(role: AppRole, deviceName: String? = null) {
        appContext.dataStore.edit { prefs ->
            prefs[Keys.ROLE] = role.name
            if (!deviceName.isNullOrBlank()) {
                prefs[Keys.DEVICE_NAME] = deviceName.trim()
            } else if (prefs[Keys.DEVICE_NAME].isNullOrBlank()) {
                prefs[Keys.DEVICE_NAME] = Build.MODEL
            }
        }
        getOrCreateDeviceId()
        if (role == AppRole.SUPERVISOR) {
            getOrCreatePairingCode()
        }
    }

    suspend fun setSupervisorConnection(
        host: String,
        port: Int,
        pairingCode: String? = null,
        authToken: String? = null
    ) {
        appContext.dataStore.edit { prefs ->
            prefs[Keys.SUPERVISOR_HOST] = host.trim()
            prefs[Keys.SUPERVISOR_PORT] = port.coerceIn(1, 65535)
            if (!pairingCode.isNullOrBlank()) {
                prefs[Keys.PAIRING_CODE] = pairingCode.trim()
            }
            if (!authToken.isNullOrBlank()) {
                prefs[Keys.CLIENT_AUTH_TOKEN] = authToken.trim()
            }
        }
    }

    suspend fun getSupervisorHost(): String = appContext.dataStore.data.first()[Keys.SUPERVISOR_HOST] ?: ""
    suspend fun getSupervisorPort(): Int = appContext.dataStore.data.first()[Keys.SUPERVISOR_PORT] ?: 8765
    suspend fun getDeviceName(): String = appContext.dataStore.data.first()[Keys.DEVICE_NAME] ?: Build.MODEL
    suspend fun getClientAuthToken(): String = appContext.dataStore.data.first()[Keys.CLIENT_AUTH_TOKEN] ?: ""

    suspend fun setClientAuthToken(token: String) {
        appContext.dataStore.edit { it[Keys.CLIENT_AUTH_TOKEN] = token.trim() }
    }

    suspend fun setSupervisorPin(pin: String) {
        val trimmed = pin.trim()
        appContext.dataStore.edit {
            it[Keys.SUPERVISOR_PIN] = if (trimmed.isEmpty()) "" else hashPin(trimmed)
        }
    }

    suspend fun setGithubRepo(repo: String) {
        appContext.dataStore.edit { it[Keys.GITHUB_REPO] = repo.trim() }
    }

    suspend fun setSelfLimitEnabled(enabled: Boolean) {
        appContext.dataStore.edit { it[Keys.SELF_LIMIT_ENABLED] = enabled }
    }

    suspend fun setGlobalSpeedCapMbps(mbps: Double) {
        appContext.dataStore.edit { it[Keys.GLOBAL_SPEED_CAP_MBPS] = mbps.sanitizeFinite(0.0, 0.0, 1000.0) }
    }

    suspend fun setForceQuotaSaverAll(forced: Boolean) {
        appContext.dataStore.edit { it[Keys.FORCE_QUOTA_SAVER_ALL] = forced }
    }

    suspend fun setTargetNetwork(onlyTarget: Boolean, signature: String? = null) {
        appContext.dataStore.edit { prefs ->
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
        appContext.dataStore.edit { prefs ->
            prefs[Keys.DAILY_LIMIT_MB] = limitMb.coerceIn(10L, 10_485_760L)
            prefs[Keys.LIMIT_PERIOD] = limitPeriod.name
            prefs[Keys.SPEED_POLICY_JSON] = speedPolicy.toJson().toString()
            prefs[Keys.WHITELIST_PACKAGES] = whitelist
            prefs[Keys.GLOBAL_SPEED_CAP_MBPS] = globalSpeedCapMbps.sanitizeFinite(0.0, 0.0, 1000.0)
            if (forceQuotaSaver != null) prefs[Keys.FORCE_QUOTA_SAVER_ALL] = forceQuotaSaver
            if (targetNetworkOnly != null) prefs[Keys.TARGET_NETWORK_ONLY] = targetNetworkOnly
            if (targetNetworkSig != null) prefs[Keys.TARGET_NETWORK_SIG] = targetNetworkSig
            if (isPaused != null) prefs[Keys.IS_PAUSED] = isPaused
        }
    }

    suspend fun applyPolicyToAllDevices(policy: SpeedPolicyConfig) {
        updateAllManagedDevices { list ->
            list.map {
                it.copy(
                    speedPolicy = policy.copy(appQuotaPercents = it.speedPolicy.appQuotaPercents),
                    pendingConfigPush = true
                )
            }
        }
    }

    suspend fun addUsedBytes(deltaBytes: Long) {
        if (deltaBytes <= 0L) return
        appContext.dataStore.edit { prefs ->
            val nowMillis = System.currentTimeMillis()
            val today = localDateNow(nowMillis)
            val d = dayStr(today)
            val w = weekStr(today)
            val m = monthStr(today)

            ensureDayRolledOver(prefs, d)
            val curDay = (prefs[Keys.USED_BYTES_DAY] ?: 0L).coerceAtLeast(0L)
            val newDayBytes = curDay + deltaBytes
            prefs[Keys.USED_BYTES_DAY] = newDayBytes

            val curWeek = if (prefs[Keys.DATE_WEEK] == w) (prefs[Keys.USED_BYTES_WEEK] ?: 0L).coerceAtLeast(0L) else 0L
            prefs[Keys.DATE_WEEK] = w
            prefs[Keys.USED_BYTES_WEEK] = curWeek + deltaBytes

            val curMonth = if (prefs[Keys.DATE_MONTH] == m) (prefs[Keys.USED_BYTES_MONTH] ?: 0L).coerceAtLeast(0L) else 0L
            prefs[Keys.DATE_MONTH] = m
            prefs[Keys.USED_BYTES_MONTH] = curMonth + deltaBytes

            val curHour = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).hour
            val curHourKey = localHourKey(d, curHour)
            val hourlyMap = parseLongMap(prefs[Keys.HOURLY_USAGE_JSON] ?: "{}").toMutableMap()
            hourlyMap[curHourKey] = (hourlyMap[curHourKey] ?: 0L).coerceAtLeast(0L) + deltaBytes
            // Keep only today's hour buckets so yesterday's usage never leaks into today's sub-period
            hourlyMap.keys.retainAll { it.startsWith("$d-") }
            prefs[Keys.HOURLY_USAGE_JSON] = JSONObject(hourlyMap as Map<*, *>).toString()

            val hist = parseLongMap(prefs[Keys.DAILY_USAGE_HISTORY_JSON] ?: "{}").toMutableMap()
            hist[d] = newDayBytes / (1024L * 1024L)
            pruneHistoryMap(hist)
            prefs[Keys.DAILY_USAGE_HISTORY_JSON] = JSONObject(hist as Map<*, *>).toString()
        }
    }

    suspend fun addSavedBytes(deltaBytes: Long) {
        if (deltaBytes <= 0L) return
        appContext.dataStore.edit { prefs ->
            val today = localDateNow()
            val d = dayStr(today)
            ensureDayRolledOver(prefs, d)
            val curSaved = (prefs[Keys.SAVED_BYTES_DAY] ?: 0L).coerceAtLeast(0L)
            val newSaved = curSaved + deltaBytes
            prefs[Keys.SAVED_BYTES_DAY] = newSaved
            val hist = parseLongMap(prefs[Keys.DAILY_SAVED_HISTORY_JSON] ?: "{}").toMutableMap()
            hist[d] = newSaved / (1024L * 1024L)
            pruneHistoryMap(hist)
            prefs[Keys.DAILY_SAVED_HISTORY_JSON] = JSONObject(hist as Map<*, *>).toString()
        }
    }

    suspend fun addAppUsedBytes(deltas: Map<String, Long>, period: LimitPeriod) {
        if (deltas.isEmpty()) return
        appContext.dataStore.edit { prefs ->
            val pKey = activePeriodKey(period)
            val savedKey = prefs[Keys.APP_USAGE_PERIOD_KEY] ?: ""
            val map = if (savedKey == pKey) {
                parseLongMap(prefs[Keys.APP_USAGE_BYTES_JSON] ?: "{}").toMutableMap()
            } else {
                mutableMapOf()
            }
            deltas.forEach { (pkg, d) ->
                if (pkg.isNotBlank() && d > 0L) map[pkg] = (map[pkg] ?: 0L).coerceAtLeast(0L) + d
            }
            prefs[Keys.APP_USAGE_PERIOD_KEY] = pKey
            prefs[Keys.APP_USAGE_BYTES_JSON] = JSONObject(map as Map<*, *>).toString()
        }
    }

    suspend fun setAppUsedMbMap(mbMap: Map<String, Long>, period: LimitPeriod) {
        appContext.dataStore.edit { prefs ->
            val pKey = activePeriodKey(period)
            prefs[Keys.APP_USAGE_PERIOD_KEY] = pKey
            val byteMap = mbMap
                .filter { it.key.isNotBlank() && it.value > 0L }
                .mapValues { it.value * 1024L * 1024L }
            prefs[Keys.APP_USAGE_BYTES_JSON] = JSONObject(byteMap as Map<*, *>).toString()
        }
    }

    suspend fun getAppUsedMbMap(period: LimitPeriod): Map<String, Long> {
        val prefs = appContext.dataStore.data.first()
        if (prefs[Keys.APP_USAGE_PERIOD_KEY] != activePeriodKey(period)) return emptyMap()
        return parseLongMap(prefs[Keys.APP_USAGE_BYTES_JSON] ?: "{}")
            .mapValues { it.value.coerceAtLeast(0L) / (1024L * 1024L) }
    }

    suspend fun getWindowUsedMb(windowHours: Int): Long {
        val prefs = appContext.dataStore.data.first()
        val zdt = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        val todayStr = dayStr(zdt.toLocalDate())
        if (prefs[Keys.DATE_DAY] != todayStr) return 0L
        val win = windowHours.coerceIn(1, 24)
        val curHour = zdt.hour
        // Fixed day-aligned sub-period slot (e.g. 00-06, 06-12, 12-18, 18-24) so it resets cleanly to 0 MB when the sub-period ends
        val slotStartHour = (curHour / win) * win
        val map = parseLongMap(prefs[Keys.HOURLY_USAGE_JSON] ?: "{}")
        var totalBytes = 0L
        for (h in slotStartHour..curHour) {
            totalBytes += (map[localHourKey(todayStr, h)] ?: 0L).coerceAtLeast(0L)
        }
        return totalBytes / (1024L * 1024L)
    }

    suspend fun getSavedMbToday(): Long {
        val prefs = appContext.dataStore.data.first()
        if (prefs[Keys.DATE_DAY] != dayStr()) return 0L
        return (prefs[Keys.SAVED_BYTES_DAY] ?: 0L).coerceAtLeast(0L) / (1024L * 1024L)
    }

    suspend fun addBonusQuotaMb(extraMb: Long) {
        if (extraMb <= 0L) return
        appContext.dataStore.edit { prefs ->
            val today = localDateNow()
            val d = dayStr(today)
            ensureDayRolledOver(prefs, d)
            val period = runCatching {
                LimitPeriod.valueOf(prefs[Keys.LIMIT_PERIOD] ?: LimitPeriod.DAILY.name)
            }.getOrDefault(LimitPeriod.DAILY)
            val pKey = activePeriodKey(period, today)
            val storedKey = prefs[Keys.BONUS_PERIOD_KEY] ?: prefs[Keys.DATE_DAY]
            val currentBonus = if (storedKey == pKey) (prefs[Keys.DAILY_BONUS_MB] ?: 0L).coerceAtLeast(0L) else 0L
            prefs[Keys.BONUS_PERIOD_KEY] = pKey
            prefs[Keys.DAILY_BONUS_MB] = currentBonus + extraMb
        }
    }

    suspend fun ensurePeriodRolledOverIfNeeded() {
        val today = localDateNow()
        val d = dayStr(today)
        val w = weekStr(today)
        val m = monthStr(today)
        val cur = appContext.dataStore.data.first()
        if (cur[Keys.DATE_DAY] == d && cur[Keys.DATE_WEEK] == w && cur[Keys.DATE_MONTH] == m) return
        appContext.dataStore.edit { prefs ->
            ensureDayRolledOver(prefs, d)
            if (prefs[Keys.DATE_WEEK] != w) {
                prefs[Keys.DATE_WEEK] = w
                prefs[Keys.USED_BYTES_WEEK] = 0L
            }
            if (prefs[Keys.DATE_MONTH] != m) {
                prefs[Keys.DATE_MONTH] = m
                prefs[Keys.USED_BYTES_MONTH] = 0L
            }
        }
    }

    suspend fun getUsedMbToday(): Long {
        val prefs = appContext.dataStore.data.first()
        if (prefs[Keys.DATE_DAY] != dayStr()) return 0L
        return (prefs[Keys.USED_BYTES_DAY] ?: 0L).coerceAtLeast(0L) / (1024L * 1024L)
    }

    suspend fun resetUsageCounters() {
        appContext.dataStore.edit { prefs ->
            val today = localDateNow()
            val d = dayStr(today)
            prefs[Keys.DATE_DAY] = d
            prefs[Keys.USED_BYTES_DAY] = 0L
            prefs[Keys.SAVED_BYTES_DAY] = 0L
            prefs[Keys.DAILY_BONUS_MB] = 0L
            prefs[Keys.DATE_WEEK] = weekStr(today)
            prefs[Keys.USED_BYTES_WEEK] = 0L
            prefs[Keys.DATE_MONTH] = monthStr(today)
            prefs[Keys.USED_BYTES_MONTH] = 0L
            prefs[Keys.APP_USAGE_BYTES_JSON] = "{}"
            prefs[Keys.APP_USAGE_PERIOD_KEY] = ""
            prefs[Keys.HOURLY_USAGE_JSON] = "{}"

            val usageHist = parseLongMap(prefs[Keys.DAILY_USAGE_HISTORY_JSON] ?: "{}").toMutableMap()
            usageHist[d] = 0L
            prefs[Keys.DAILY_USAGE_HISTORY_JSON] = JSONObject(usageHist as Map<*, *>).toString()

            val savedHist = parseLongMap(prefs[Keys.DAILY_SAVED_HISTORY_JSON] ?: "{}").toMutableMap()
            savedHist[d] = 0L
            prefs[Keys.DAILY_SAVED_HISTORY_JSON] = JSONObject(savedHist as Map<*, *>).toString()
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
        val prefs = appContext.dataStore.data.first()
        val rawPolicy = prefs[Keys.SPEED_POLICY_JSON]
        val policy = if (rawPolicy.isNullOrBlank()) SpeedPolicyConfig()
        else runCatching { SpeedPolicyConfig.fromJson(JSONObject(rawPolicy)) }.getOrDefault(SpeedPolicyConfig())
        val period = runCatching {
            LimitPeriod.valueOf(prefs[Keys.LIMIT_PERIOD] ?: LimitPeriod.DAILY.name)
        }.getOrDefault(LimitPeriod.DAILY)
        val base = (prefs[Keys.DAILY_LIMIT_MB] ?: 1024L).coerceAtLeast(10L)
        val expectedBonusKey = activePeriodKey(period)
        val storedBonusKey = prefs[Keys.BONUS_PERIOD_KEY] ?: prefs[Keys.DATE_DAY]
        val bonus = if (storedBonusKey == expectedBonusKey) {
            (prefs[Keys.DAILY_BONUS_MB] ?: 0L).coerceAtLeast(0L)
        } else 0L
        return LocalConfigSnapshot(
            limitMb = base + bonus,
            limitPeriod = period,
            speedPolicy = policy,
            whitelist = prefs[Keys.WHITELIST_PACKAGES] ?: emptySet(),
            globalSpeedCapMbps = (prefs[Keys.GLOBAL_SPEED_CAP_MBPS] ?: 0.0).sanitizeFinite(0.0, 0.0, 1000.0),
            forceQuotaSaver = prefs[Keys.FORCE_QUOTA_SAVER_ALL] ?: false,
            targetNetworkOnly = prefs[Keys.TARGET_NETWORK_ONLY] ?: false,
            targetNetworkSig = prefs[Keys.TARGET_NETWORK_SIG] ?: "",
            isPaused = prefs[Keys.IS_PAUSED] ?: false
        )
    }

    suspend fun getUsedMbActivePeriod(): Long {
        val prefs = appContext.dataStore.data.first()
        val today = localDateNow()
        val period = runCatching {
            LimitPeriod.valueOf(prefs[Keys.LIMIT_PERIOD] ?: LimitPeriod.DAILY.name)
        }.getOrDefault(LimitPeriod.DAILY)
        val bytes = when (period) {
            LimitPeriod.DAILY -> if (prefs[Keys.DATE_DAY] == dayStr(today)) prefs[Keys.USED_BYTES_DAY] ?: 0L else 0L
            LimitPeriod.WEEKLY -> if (prefs[Keys.DATE_WEEK] == weekStr(today)) prefs[Keys.USED_BYTES_WEEK] ?: 0L else 0L
            LimitPeriod.MONTHLY -> if (prefs[Keys.DATE_MONTH] == monthStr(today)) prefs[Keys.USED_BYTES_MONTH] ?: 0L else 0L
        }
        return bytes.coerceAtLeast(0L) / (1024L * 1024L)
    }

    suspend fun upsertManagedDevice(device: ManagedDevice) {
        appContext.dataStore.edit { prefs ->
            val list = parseDevicesList(prefs[Keys.MANAGED_DEVICES_JSON] ?: "[]").toMutableList()
            val idx = list.indexOfFirst { it.deviceId == device.deviceId }
            if (idx >= 0) list[idx] = device else list.add(device)
            prefs[Keys.MANAGED_DEVICES_JSON] = serializeDevicesList(list)
        }
    }

    suspend fun updateManagedDevice(deviceId: String, transform: (ManagedDevice) -> ManagedDevice) {
        appContext.dataStore.edit { prefs ->
            val list = parseDevicesList(prefs[Keys.MANAGED_DEVICES_JSON] ?: "[]").map {
                if (it.deviceId == deviceId) transform(it) else it
            }
            prefs[Keys.MANAGED_DEVICES_JSON] = serializeDevicesList(list)
        }
    }

    suspend fun updateAllManagedDevices(transform: (List<ManagedDevice>) -> List<ManagedDevice>) {
        appContext.dataStore.edit { prefs ->
            val list = parseDevicesList(prefs[Keys.MANAGED_DEVICES_JSON] ?: "[]")
            prefs[Keys.MANAGED_DEVICES_JSON] = serializeDevicesList(transform(list))
        }
    }

    suspend fun removeManagedDevice(deviceId: String) {
        appContext.dataStore.edit { prefs ->
            val list = parseDevicesList(prefs[Keys.MANAGED_DEVICES_JSON] ?: "[]")
                .filterNot { it.deviceId == deviceId }
            prefs[Keys.MANAGED_DEVICES_JSON] = serializeDevicesList(list)
        }
    }

    suspend fun addTamperAlert(alert: TamperAlert) {
        appContext.dataStore.edit { prefs ->
            val list = parseAlertsList(prefs[Keys.TAMPER_ALERTS_JSON] ?: "[]").toMutableList()
            list.add(0, alert)
            if (list.size > 30) list.subList(30, list.size).clear()
            prefs[Keys.TAMPER_ALERTS_JSON] = serializeAlertsList(list)
        }
    }

    suspend fun clearTamperAlerts() {
        appContext.dataStore.edit { prefs ->
            prefs[Keys.TAMPER_ALERTS_JSON] = "[]"
            val devices = parseDevicesList(prefs[Keys.MANAGED_DEVICES_JSON] ?: "[]")
            if (devices.any { !it.tamperWarning.isNullOrBlank() }) {
                prefs[Keys.MANAGED_DEVICES_JSON] = serializeDevicesList(devices.map { it.copy(tamperWarning = null) })
            }
        }
    }

    private fun parseLongMap(jsonStr: String): Map<String, Long> {
        return runCatching {
            val obj = JSONObject(jsonStr)
            val map = mutableMapOf<String, Long>()
            obj.keys().forEach { k ->
                if (k.isNotBlank()) map[k] = obj.optLong(k, 0L).coerceAtLeast(0L)
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun parseDevicesList(jsonStr: String): List<ManagedDevice> {
        return runCatching {
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { obj ->
                    runCatching { ManagedDevice.fromJson(obj) }.getOrNull()
                }
            }
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
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { obj ->
                    runCatching { TamperAlert.fromJson(obj) }.getOrNull()
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeAlertsList(list: List<TamperAlert>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }
}

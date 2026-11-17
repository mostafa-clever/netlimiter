package com.wifiguard.limiter.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.model.ManagedDevice
import com.wifiguard.limiter.model.TamperAlert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
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

        // Local device (Client or Supervisor self-limit) daily usage & limits
        val DAILY_LIMIT_MB = longPreferencesKey("daily_limit_mb")
        val USED_BYTES_TODAY = longPreferencesKey("used_bytes_today")
        val USAGE_DATE = stringPreferencesKey("usage_date")
        val SLOWDOWN_THRESHOLD = intPreferencesKey("slowdown_threshold")
        val WHITELIST_PACKAGES = stringSetPreferencesKey("whitelist_packages")
        val SELF_LIMIT_ENABLED = booleanPreferencesKey("self_limit_enabled")

        // Supervisor stored devices & alerts
        val MANAGED_DEVICES_JSON = stringPreferencesKey("managed_devices_json")
        val TAMPER_ALERTS_JSON = stringPreferencesKey("tamper_alerts_json")
    }

    private fun todayDateStr(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

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

    val supervisorHostFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SUPERVISOR_HOST] ?: ""
    }

    val supervisorPinFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SUPERVISOR_PIN] ?: ""
    }

    val dailyLimitMbFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.DAILY_LIMIT_MB] ?: 1024L
    }

    val usedMbTodayFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        val savedDate = prefs[Keys.USAGE_DATE] ?: ""
        if (savedDate != todayDateStr()) 0L
        else (prefs[Keys.USED_BYTES_TODAY] ?: 0L) / (1024L * 1024L)
    }

    val slowdownThresholdFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.SLOWDOWN_THRESHOLD] ?: 80
    }

    val whitelistFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.WHITELIST_PACKAGES] ?: emptySet()
    }

    val selfLimitEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELF_LIMIT_ENABLED] ?: false
    }

    val managedDevicesFlow: Flow<List<ManagedDevice>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.MANAGED_DEVICES_JSON] ?: "[]"
        parseDevicesList(raw)
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

    suspend fun setSelfLimitEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SELF_LIMIT_ENABLED] = enabled }
    }

    suspend fun updateLocalLimits(
        dailyLimitMb: Long,
        slowdownThresholdPercent: Int,
        whitelist: Set<String>
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DAILY_LIMIT_MB] = dailyLimitMb.coerceAtLeast(10L)
            prefs[Keys.SLOWDOWN_THRESHOLD] = slowdownThresholdPercent.coerceIn(50, 99)
            prefs[Keys.WHITELIST_PACKAGES] = whitelist
        }
    }

    suspend fun addUsedBytesToday(deltaBytes: Long) {
        if (deltaBytes <= 0L) return
        val today = todayDateStr()
        context.dataStore.edit { prefs ->
            val savedDate = prefs[Keys.USAGE_DATE] ?: ""
            val current = if (savedDate == today) prefs[Keys.USED_BYTES_TODAY] ?: 0L else 0L
            prefs[Keys.USAGE_DATE] = today
            prefs[Keys.USED_BYTES_TODAY] = current + deltaBytes
        }
    }

    suspend fun resetTodayUsage() {
        val today = todayDateStr()
        context.dataStore.edit { prefs ->
            prefs[Keys.USAGE_DATE] = today
            prefs[Keys.USED_BYTES_TODAY] = 0L
        }
    }

    suspend fun getLocalLimitConfig(): Triple<Long, Int, Set<String>> {
        val prefs = context.dataStore.data.first()
        return Triple(
            prefs[Keys.DAILY_LIMIT_MB] ?: 1024L,
            prefs[Keys.SLOWDOWN_THRESHOLD] ?: 80,
            prefs[Keys.WHITELIST_PACKAGES] ?: emptySet()
        )
    }

    suspend fun getUsedMbToday(): Long {
        val prefs = context.dataStore.data.first()
        val savedDate = prefs[Keys.USAGE_DATE] ?: ""
        if (savedDate != todayDateStr()) return 0L
        return (prefs[Keys.USED_BYTES_TODAY] ?: 0L) / (1024L * 1024L)
    }

    suspend fun upsertManagedDevice(device: ManagedDevice) {
        context.dataStore.edit { prefs ->
            val list = parseDevicesList(prefs[Keys.MANAGED_DEVICES_JSON] ?: "[]").toMutableList()
            val idx = list.indexOfFirst { it.deviceId == device.deviceId }
            if (idx >= 0) {
                list[idx] = device
            } else {
                list.add(device)
            }
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
            if (list.size > 30) {
                list.subList(30, list.size).clear()
            }
            prefs[Keys.TAMPER_ALERTS_JSON] = serializeAlertsList(list)
        }
    }

    suspend fun clearTamperAlerts() {
        context.dataStore.edit { prefs ->
            prefs[Keys.TAMPER_ALERTS_JSON] = "[]"
        }
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

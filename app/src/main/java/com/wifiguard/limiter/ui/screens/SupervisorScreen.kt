package com.wifiguard.limiter.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wifiguard.limiter.BuildConfig
import com.wifiguard.limiter.admin.WifiGuardDeviceAdminReceiver
import com.wifiguard.limiter.data.AppUsageStatsHelper
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.LimitPeriod
import com.wifiguard.limiter.model.ManagedDevice
import com.wifiguard.limiter.model.SpeedPolicyConfig
import com.wifiguard.limiter.model.TamperAlert
import com.wifiguard.limiter.model.formatDataSizeArabic
import com.wifiguard.limiter.model.formatSpeedArabic
import com.wifiguard.limiter.model.isValidIpv4
import com.wifiguard.limiter.model.normalizeArabicDigits
import com.wifiguard.limiter.model.sanitizeFinite
import com.wifiguard.limiter.network.LocalLanClient
import com.wifiguard.limiter.network.NetworkScanner
import com.wifiguard.limiter.network.SupervisorServerService
import com.wifiguard.limiter.ui.components.AppQuotaPercentsDialog
import com.wifiguard.limiter.ui.components.GuideType
import com.wifiguard.limiter.ui.components.SetupGuideDialog
import com.wifiguard.limiter.ui.components.UsageRingCard
import com.wifiguard.limiter.ui.components.WhitelistPickerDialog
import com.wifiguard.limiter.ui.theme.AlertRed
import com.wifiguard.limiter.ui.theme.AlertRedBg
import com.wifiguard.limiter.ui.theme.PrimaryTeal
import com.wifiguard.limiter.ui.theme.SoftMint
import com.wifiguard.limiter.ui.theme.SuccessGreen
import com.wifiguard.limiter.ui.theme.TextMuted
import com.wifiguard.limiter.ui.theme.WarnAmber
import com.wifiguard.limiter.update.GitHubUpdater
import com.wifiguard.limiter.vpn.WifiGuardVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorScreen(
    prefs: PreferencesManager,
    onRequestStartVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onOpenAlwaysOnSettings: () -> Unit,
    onRequestDeviceAdmin: () -> Unit,
    onResetRole: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val pairingCode by prefs.pairingCodeFlow.collectAsState(initial = "")
    val devices by prefs.managedDevicesFlow.collectAsState(initial = emptyList())
    val alerts by prefs.tamperAlertsFlow.collectAsState(initial = emptyList())
    val hasSupervisorPin by prefs.hasSupervisorPinFlow.collectAsState(initial = false)
    val githubRepo by prefs.githubRepoFlow.collectAsState(initial = "")
    val globalSpeedCapMbps by prefs.globalSpeedCapMbpsFlow.collectAsState(initial = 0.0)
    val forceQuotaSaverAll by prefs.forceQuotaSaverAllFlow.collectAsState(initial = false)
    val targetNetworkOnly by prefs.targetNetworkOnlyFlow.collectAsState(initial = false)
    val targetNetworkSig by prefs.targetNetworkSigFlow.collectAsState(initial = "")
    val alwaysOnSystem by WifiGuardVpnService.isAlwaysOnActive.collectAsState()
    val alwaysOnConfirmed by prefs.alwaysOnConfirmedFlow.collectAsState(initial = false)
    val alwaysOnActive = alwaysOnSystem || alwaysOnConfirmed

    // Self-limit & template policy states
    val selfLimitEnabled by prefs.selfLimitEnabledFlow.collectAsState(initial = false)
    val localUsageHist by prefs.localUsageHistoryFlow.collectAsState(initial = emptyMap())
    val localSavedHist by prefs.localSavedHistoryFlow.collectAsState(initial = emptyMap())
    val localAppUsageMb by prefs.localAppUsageMbFlow.collectAsState(initial = emptyMap())
    val limitPeriod by prefs.limitPeriodFlow.collectAsState(initial = LimitPeriod.DAILY)
    val usedMb by prefs.usedMbActivePeriodFlow.collectAsState(initial = 0L)
    val dailyLimitMb by prefs.dailyLimitMbFlow.collectAsState(initial = 1024L)
    val speedPolicy by prefs.speedPolicyFlow.collectAsState(initial = SpeedPolicyConfig())
    val whitelist by prefs.whitelistFlow.collectAsState(initial = emptySet())
    val vpnRunning by WifiGuardVpnService.isRunning.collectAsState()
    val connState by WifiGuardVpnService.connectionState.collectAsState()
    val statusLabel by WifiGuardVpnService.statusLabel.collectAsState()

    var editingDevice by remember { mutableStateOf<ManagedDevice?>(null) }
    var showSelfWhitelistDialog by remember { mutableStateOf(false) }
    var showSelfLimitDialog by remember { mutableStateOf(false) }
    var pinGateAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showSupervisorGuide by rememberSaveable { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    var resumeTick by remember { mutableIntStateOf(0) }
    var adminActive by remember { mutableStateOf(WifiGuardDeviceAdminReceiver.isActive(context)) }
    var hasUsageAccess by remember { mutableStateOf(AppUsageStatsHelper.hasUsageStatsPermission(context)) }
    var showAlwaysOnGuide by rememberSaveable { mutableStateOf(false) }
    var pinUnlockedUntilMs by rememberSaveable { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                adminActive = WifiGuardDeviceAdminReceiver.isActive(context)
                hasUsageAccess = AppUsageStatsHelper.hasUsageStatsPermission(context)
                resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val localIp by produceState(initialValue = "جاري الفحص...", key1 = resumeTick) {
        value = withContext(Dispatchers.IO) { SupervisorServerService.getLocalWifiIp(context) }
    }

    fun requirePinIfSet(action: () -> Unit) {
        if (hasSupervisorPin && SystemClock.elapsedRealtime() > pinUnlockedUntilMs) {
            pinGateAction = {
                pinUnlockedUntilMs = SystemClock.elapsedRealtime() + 5 * 60_000L
                action()
            }
        } else {
            action()
        }
    }

    LaunchedEffect(Unit) {
        prefs.getOrCreatePairingCode()
    }

    // On-demand per-app analytics pull whenever Supervisor opens the Analytics tab
    LaunchedEffect(selectedTab) {
        if (selectedTab == 2) {
            val now = System.currentTimeMillis()
            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val snapshot = devices
            coroutineScope {
                snapshot.filter { it.lastKnownIp.isNotBlank() }.map { d ->
                    async {
                        val live = LocalLanClient.fetchClientStatus(d.lastKnownIp, fullAnalytics = true, authToken = d.authToken)
                        if (live != null && live.optBoolean("ok", false)) {
                            val newUsed = live.optLong("usedMb", d.usedMb).coerceAtLeast(0L)
                            val newUsedToday = if (live.has("usedMbToday")) live.optLong("usedMbToday", 0L).coerceAtLeast(0L) else if (d.limitPeriod == LimitPeriod.DAILY) newUsed else (d.dailyUsageHistory[todayKey] ?: 0L)
                            val newSaved = live.optLong("savedMbToday", d.savedMbToday).coerceAtLeast(0L)
                            val appObj = live.optJSONObject("appUsageMb")
                            val liveApps = mutableMapOf<String, Long>()
                            appObj?.keys()?.forEach { k -> liveApps[k] = appObj.optLong(k, 0L).coerceAtLeast(0L) }
                            prefs.updateManagedDevice(d.deviceId) { cur ->
                                val uHist = cur.dailyUsageHistory.toMutableMap().apply { put(todayKey, newUsedToday) }
                                val sHist = cur.dailySavedHistory.toMutableMap().apply { put(todayKey, newSaved) }
                                cur.copy(
                                    usedMb = newUsed,
                                    savedMbToday = newSaved,
                                    appUsageMb = if (live.has("appUsageMb")) liveApps else cur.appUsageMb,
                                    dailyUsageHistory = uHist,
                                    dailySavedHistory = sHist,
                                    lastSeenMillis = now,
                                    isOnline = true
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (selectedTab) {
                            0 -> "No Quota — المشرف"
                            1 -> "حدي الشخصي"
                            2 -> "التحليلات والتوفير"
                            else -> "الإعدادات المتقدمة"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                actions = {
                    IconButton(onClick = { showSupervisorGuide = true }) {
                        Icon(Icons.Rounded.HelpOutline, contentDescription = "دليل المشرف", tint = Color.White)
                    }
                    if (selectedTab == 0 && devices.isNotEmpty()) {
                        IconButton(
                            enabled = !isRefreshing,
                            onClick = {
                                if (isRefreshing) return@IconButton
                                isRefreshing = true
                                scope.launch {
                                    try {
                                        resumeTick++
                                        val now = System.currentTimeMillis()
                                        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                        val snapshot = devices
                                        val results = coroutineScope {
                                            snapshot.map { d ->
                                                async {
                                                    if (d.lastKnownIp.isBlank()) return@async false
                                                    val live = LocalLanClient.fetchClientStatus(d.lastKnownIp, authToken = d.authToken)
                                                    if (live != null && live.optBoolean("ok", false)) {
                                                        val newUsed = live.optLong("usedMb", d.usedMb).coerceAtLeast(0L)
                                                        val newUsedToday = if (live.has("usedMbToday")) live.optLong("usedMbToday", 0L).coerceAtLeast(0L) else if (d.limitPeriod == LimitPeriod.DAILY) newUsed else (d.dailyUsageHistory[todayKey] ?: 0L)
                                                        val newSaved = live.optLong("savedMbToday", d.savedMbToday).coerceAtLeast(0L)
                                                        val newSpeed = live.optDouble("actualSpeedMbps", d.actualSpeedMbps).sanitizeFinite(0.0)
                                                        val appObj = live.optJSONObject("appUsageMb")
                                                        val liveApps = mutableMapOf<String, Long>()
                                                        appObj?.keys()?.forEach { k -> liveApps[k] = appObj.optLong(k, 0L).coerceAtLeast(0L) }
                                                        val prot = live.optBoolean("protectionActive", d.protectionActive)
                                                        val st = runCatching {
                                                            ConnectionState.valueOf(live.optString("connectionState", d.connectionState.name))
                                                        }.getOrDefault(d.connectionState)
                                                        prefs.updateManagedDevice(d.deviceId) { cur ->
                                                            val uHist = cur.dailyUsageHistory.toMutableMap().apply { put(todayKey, newUsedToday) }
                                                            val sHist = cur.dailySavedHistory.toMutableMap().apply { put(todayKey, newSaved) }
                                                            cur.copy(
                                                                usedMb = newUsed,
                                                                savedMbToday = newSaved,
                                                                actualSpeedMbps = newSpeed,
                                                                appUsageMb = if (live.has("appUsageMb")) liveApps else cur.appUsageMb,
                                                                protectionActive = prot,
                                                                connectionState = if (prot) st else ConnectionState.PROTECTION_OFF,
                                                                dailyUsageHistory = uHist,
                                                                dailySavedHistory = sHist,
                                                                lastSeenMillis = now,
                                                                isOnline = true
                                                            )
                                                        }
                                                        true
                                                    } else false
                                                }
                                            }.awaitAll()
                                        }
                                        val okCount = results.count { it }
                                        Toast.makeText(context, "تم تحديث $okCount من ${snapshot.size} أجهزة", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isRefreshing = false
                                    }
                                }
                            }
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.Refresh, contentDescription = "تحديث الآن", tint = Color.White)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryTeal,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Rounded.Devices, contentDescription = null) },
                    label = { Text("الأجهزة") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                    label = { Text("حدي الشخصي") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Rounded.BarChart, contentDescription = null) },
                    label = { Text("التحليلات") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                    label = { Text("المتقدمة") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (selectedTab) {
                0 -> DevicesTab(
                    pairingCode = pairingCode,
                    localIp = localIp,
                    devices = devices,
                    alerts = alerts,
                    globalSpeedCapMbps = globalSpeedCapMbps,
                    onClearAlerts = {
                        requirePinIfSet { scope.launch { prefs.clearTamperAlerts() } }
                    },
                    onEditDevice = { dev ->
                        requirePinIfSet { editingDevice = dev }
                    },
                    onTogglePauseDevice = { dev ->
                        requirePinIfSet {
                            val newPaused = !dev.isPaused
                            scope.launch {
                                prefs.updateManagedDevice(dev.deviceId) { it.copy(isPaused = newPaused) }
                                val ok = LocalLanClient.pushConfigToClient(
                                    clientIp = dev.lastKnownIp,
                                    dailyLimitMb = dev.dailyLimitMb,
                                    limitPeriod = dev.limitPeriod,
                                    speedPolicy = dev.speedPolicy,
                                    whitelistedPackages = dev.whitelistedPackages,
                                    globalSpeedCapMbps = globalSpeedCapMbps,
                                    forceQuotaSaver = forceQuotaSaverAll || dev.forceQuotaSaver,
                                    targetNetworkOnly = targetNetworkOnly,
                                    targetNetworkSig = targetNetworkSig,
                                    isPaused = newPaused,
                                    authToken = dev.authToken
                                )
                                if (!ok) {
                                    prefs.updateManagedDevice(dev.deviceId) { it.copy(pendingConfigPush = true) }
                                    Toast.makeText(context, "الجهاز غير متصل — سيتم التطبيق فور عودته", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, if (newPaused) "تم إيقاف الإنترنت عن ${dev.deviceName}" else "تم استئناف الإنترنت لـ ${dev.deviceName}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onQuickGlobalCapChange = { cap ->
                        requirePinIfSet {
                            scope.launch {
                                prefs.setGlobalSpeedCapMbps(cap)
                                WifiGuardVpnService.notifyConfigChanged()
                                devices.forEach { d ->
                                    val ok = LocalLanClient.pushConfigToClient(
                                        clientIp = d.lastKnownIp,
                                        dailyLimitMb = d.dailyLimitMb,
                                        limitPeriod = d.limitPeriod,
                                        speedPolicy = d.speedPolicy,
                                        whitelistedPackages = d.whitelistedPackages,
                                        globalSpeedCapMbps = cap,
                                        forceQuotaSaver = forceQuotaSaverAll || d.forceQuotaSaver,
                                        targetNetworkOnly = targetNetworkOnly,
                                        targetNetworkSig = targetNetworkSig,
                                        isPaused = d.isPaused,
                                        authToken = d.authToken
                                    )
                                    if (!ok) prefs.updateManagedDevice(d.deviceId) { it.copy(pendingConfigPush = true) }
                                }
                            }
                        }
                    }
                )
                1 -> SelfLimitTab(
                    selfLimitEnabled = selfLimitEnabled,
                    limitPeriod = limitPeriod,
                    usedMb = usedMb,
                    dailyLimitMb = dailyLimitMb,
                    speedPolicy = speedPolicy,
                    whitelistCount = whitelist.size,
                    vpnRunning = vpnRunning,
                    connState = connState,
                    statusLabel = statusLabel,
                    alwaysOnActive = alwaysOnActive,
                    adminActive = adminActive,
                    onToggleSelfLimit = { enable ->
                        val action = {
                            scope.launch {
                                prefs.setSelfLimitEnabled(enable)
                                if (!enable) onStopVpn() else onRequestStartVpn()
                            }
                        }
                        if (!enable) requirePinIfSet { action() } else action()
                    },
                    onRequestStartVpn = onRequestStartVpn,
                    onEditLimits = { requirePinIfSet { showSelfLimitDialog = true } },
                    onAddBonusQuota = { extraMb ->
                        requirePinIfSet {
                            scope.launch {
                                prefs.addBonusQuotaMb(extraMb)
                                WifiGuardVpnService.notifyConfigChanged()
                                Toast.makeText(context, "تمت إضافة +$extraMb م.ب بنجاح", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onEditWhitelist = { requirePinIfSet { showSelfWhitelistDialog = true } },
                    hasUsageAccess = hasUsageAccess,
                    onOpenAlwaysOn = { showAlwaysOnGuide = true },
                    onRequestAdmin = onRequestDeviceAdmin
                )
                2 -> AnalyticsSection(
                    devices = devices,
                    localUsageHistory = localUsageHist,
                    localSavedHistory = localSavedHist,
                    localAppUsageMb = localAppUsageMb,
                    includeSelf = selfLimitEnabled,
                    selfUsedMbToday = if (limitPeriod == LimitPeriod.DAILY) usedMb else 0L
                )
                else -> AdvancedSettingsTab(
                    globalSpeedCapMbps = globalSpeedCapMbps,
                    policy = speedPolicy,
                    hasPin = hasSupervisorPin,
                    deviceCount = devices.size,
                    onSavePolicyWithCap = { newPolicy, newGlobalCap ->
                        requirePinIfSet {
                            scope.launch {
                                val effCap = newGlobalCap ?: globalSpeedCapMbps
                                if (newGlobalCap != null && newGlobalCap != globalSpeedCapMbps) {
                                    prefs.setGlobalSpeedCapMbps(newGlobalCap)
                                }
                                prefs.updateLocalLimits(dailyLimitMb, limitPeriod, newPolicy, whitelist, effCap)
                                WifiGuardVpnService.notifyConfigChanged()
                                Toast.makeText(context, "تم حفظ الإعدادات", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onApplyPolicyToAllWithCap = { newPolicy, newGlobalCap ->
                        requirePinIfSet {
                            scope.launch {
                                val effCap = newGlobalCap ?: globalSpeedCapMbps
                                if (newGlobalCap != null && newGlobalCap != globalSpeedCapMbps) {
                                    prefs.setGlobalSpeedCapMbps(newGlobalCap)
                                }
                                prefs.updateLocalLimits(dailyLimitMb, limitPeriod, newPolicy, whitelist, effCap)
                                prefs.applyPolicyToAllDevices(newPolicy)
                                WifiGuardVpnService.notifyConfigChanged()
                                val pushedCount = coroutineScope {
                                    devices.map { d ->
                                        async {
                                            val mergedPolicy = newPolicy.copy(appQuotaPercents = d.speedPolicy.appQuotaPercents)
                                            val ok = LocalLanClient.pushConfigToClient(
                                                clientIp = d.lastKnownIp,
                                                dailyLimitMb = d.dailyLimitMb,
                                                limitPeriod = d.limitPeriod,
                                                speedPolicy = mergedPolicy,
                                                whitelistedPackages = d.whitelistedPackages,
                                                globalSpeedCapMbps = effCap,
                                                forceQuotaSaver = forceQuotaSaverAll || d.forceQuotaSaver,
                                                targetNetworkOnly = targetNetworkOnly,
                                                targetNetworkSig = targetNetworkSig,
                                                isPaused = d.isPaused,
                                                authToken = d.authToken
                                            )
                                            if (ok) prefs.updateManagedDevice(d.deviceId) { it.copy(pendingConfigPush = false) }
                                            ok
                                        }
                                    }.awaitAll().count { it }
                                }
                                Toast.makeText(context, "تم التطبيق ($pushedCount من ${devices.size} متصل)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    githubRepo = githubRepo,
                    targetNetworkOnly = targetNetworkOnly,
                    targetNetworkSig = targetNetworkSig,
                    onSetTargetNetwork = { only, sig ->
                        requirePinIfSet {
                            scope.launch {
                                prefs.setTargetNetwork(only, sig)
                                WifiGuardVpnService.notifyConfigChanged()
                                val finalSig = sig ?: targetNetworkSig
                                devices.forEach { d ->
                                    val ok = LocalLanClient.pushConfigToClient(
                                        clientIp = d.lastKnownIp,
                                        dailyLimitMb = d.dailyLimitMb,
                                        limitPeriod = d.limitPeriod,
                                        speedPolicy = d.speedPolicy,
                                        whitelistedPackages = d.whitelistedPackages,
                                        globalSpeedCapMbps = globalSpeedCapMbps,
                                        forceQuotaSaver = forceQuotaSaverAll || d.forceQuotaSaver,
                                        targetNetworkOnly = only,
                                        targetNetworkSig = finalSig,
                                        isPaused = d.isPaused,
                                        authToken = d.authToken
                                    )
                                    if (!ok) prefs.updateManagedDevice(d.deviceId) { it.copy(pendingConfigPush = true) }
                                }
                            }
                        }
                    },
                    onSavePin = { newPin ->
                        requirePinIfSet {
                            scope.launch {
                                prefs.setSupervisorPin(newPin)
                                Toast.makeText(
                                    context,
                                    if (newPin.isBlank()) "تم إلغاء الرمز السري" else "تم حفظ الرمز السري بنجاح",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    onResetUsage = {
                        requirePinIfSet {
                            scope.launch {
                                prefs.resetUsageCounters()
                                WifiGuardVpnService.notifyConfigChanged()
                                Toast.makeText(context, "تم تصفير عدادات الاستهلاك", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onSwitchRole = {
                        requirePinIfSet {
                            scope.launch {
                                prefs.setRole(AppRole.UNSET)
                                onStopVpn()
                                SupervisorServerService.stop(context)
                                onResetRole()
                            }
                        }
                    }
                )
            }
        }
    }

    editingDevice?.let { device ->
        EditDeviceDialog(
            device = device,
            onDismiss = { editingDevice = null },
            onSave = { updated ->
                scope.launch {
                    prefs.updateManagedDevice(updated.deviceId) { cur ->
                        cur.copy(
                            dailyLimitMb = updated.dailyLimitMb,
                            limitPeriod = updated.limitPeriod,
                            forceQuotaSaver = updated.forceQuotaSaver,
                            speedPolicy = updated.speedPolicy,
                            whitelistedPackages = updated.whitelistedPackages
                        )
                    }
                    var pushed = false
                    for (attempt in 0..2) {
                        pushed = LocalLanClient.pushConfigToClient(
                            clientIp = updated.lastKnownIp,
                            dailyLimitMb = updated.dailyLimitMb,
                            limitPeriod = updated.limitPeriod,
                            speedPolicy = updated.speedPolicy,
                            whitelistedPackages = updated.whitelistedPackages,
                            globalSpeedCapMbps = globalSpeedCapMbps,
                            forceQuotaSaver = forceQuotaSaverAll || updated.forceQuotaSaver,
                            targetNetworkOnly = targetNetworkOnly,
                            targetNetworkSig = targetNetworkSig,
                            isPaused = updated.isPaused,
                            authToken = updated.authToken
                        )
                        if (pushed) break
                        delay(1500L)
                    }
                    if (!pushed) {
                        prefs.updateManagedDevice(updated.deviceId) { it.copy(pendingConfigPush = true) }
                        Toast.makeText(context, "تم الحفظ — سيتم التطبيق عند اتصال ${updated.deviceName}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "تم حفظ وإرسال الإعدادات لـ ${updated.deviceName}", Toast.LENGTH_SHORT).show()
                    }
                }
                editingDevice = null
            },
            onDelete = {
                scope.launch { prefs.removeManagedDevice(device.deviceId) }
                editingDevice = null
            }
        )
    }

    if (showSelfLimitDialog) {
        EditSelfLimitDialog(
            currentLimitMb = dailyLimitMb,
            currentPeriod = limitPeriod,
            currentPolicy = speedPolicy,
            onDismiss = { showSelfLimitDialog = false },
            onSave = { newLimit, newPeriod, newPolicy ->
                scope.launch {
                    prefs.updateLocalLimits(newLimit, newPeriod, newPolicy, whitelist, globalSpeedCapMbps)
                    WifiGuardVpnService.notifyConfigChanged()
                }
                showSelfLimitDialog = false
            }
        )
    }

    if (showSelfWhitelistDialog) {
        WhitelistPickerDialog(
            initialSelected = whitelist,
            onDismiss = { showSelfWhitelistDialog = false },
            onConfirm = { newSet ->
                scope.launch {
                    prefs.updateLocalLimits(dailyLimitMb, limitPeriod, speedPolicy, newSet, globalSpeedCapMbps)
                    WifiGuardVpnService.notifyConfigChanged()
                }
                showSelfWhitelistDialog = false
            }
        )
    }

    if (showSupervisorGuide) {
        SetupGuideDialog(
            guideType = GuideType.SUPERVISOR_GUIDE,
            onDismiss = { showSupervisorGuide = false }
        )
    }

    if (showAlwaysOnGuide) {
        SetupGuideDialog(
            guideType = GuideType.ALWAYS_ON_VPN,
            onDismiss = { showAlwaysOnGuide = false },
            onPrimaryAction = onOpenAlwaysOnSettings,
            primaryActionLabel = "فتح إعدادات الحماية الآن",
            onSecondaryAction = { scope.launch { prefs.setAlwaysOnConfirmed(true) } },
            secondaryActionLabel = "تم التفعيل ✓"
        )
    }

    pinGateAction?.let { action ->
        PinCheckDialog(
            onVerifyPin = { candidate -> prefs.verifySupervisorPin(candidate) },
            onDismiss = { pinGateAction = null },
            onSuccess = {
                pinGateAction = null
                action()
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicesTab(
    pairingCode: String,
    localIp: String,
    devices: List<ManagedDevice>,
    alerts: List<TamperAlert>,
    globalSpeedCapMbps: Double,
    onClearAlerts: () -> Unit,
    onEditDevice: (ManagedDevice) -> Unit,
    onTogglePauseDevice: (ManagedDevice) -> Unit,
    onQuickGlobalCapChange: (Double) -> Unit
) {
    if (alerts.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = AlertRedBg)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = AlertRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "تنبيهات محاولة التجاوز (${alerts.size})",
                            fontWeight = FontWeight.Bold,
                            color = AlertRed,
                            fontSize = 15.sp
                        )
                    }
                    TextButton(onClick = onClearAlerts) {
                        Text("مسح", color = AlertRed)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                var showAllAlerts by remember { mutableStateOf(false) }
                val fmt = remember { SimpleDateFormat("hh:mm a", Locale("ar")) }
                val visibleAlerts = if (showAllAlerts) alerts else alerts.take(5)
                visibleAlerts.forEach { alert ->
                    Text(
                        text = "• ${alert.message} (${fmt.format(Date(alert.timestampMillis))})",
                        fontSize = 13.sp,
                        color = AlertRed,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                if (alerts.size > 5) {
                    TextButton(onClick = { showAllAlerts = !showAllAlerts }) {
                        Text(if (showAllAlerts) "عرض أقل" else "عرض الكل (${alerts.size})", color = AlertRed, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    val supCtx = LocalContext.current
    val supPm = remember { supCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var supBatteryExempt by remember {
        mutableStateOf(runCatching { supPm?.isIgnoringBatteryOptimizations(supCtx.packageName) == true }.getOrDefault(false))
    }
    val supLifecycle = LocalLifecycleOwner.current
    DisposableEffect(supLifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) {
                supBatteryExempt = runCatching { supPm?.isIgnoringBatteryOptimizations(supCtx.packageName) == true }.getOrDefault(false)
            }
        }
        supLifecycle.lifecycle.addObserver(obs)
        onDispose { supLifecycle.lifecycle.removeObserver(obs) }
    }
    if (!supBatteryExempt) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SoftMint)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, tint = PrimaryTeal)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("استثنِ المشرف من توفير البطارية لضمان استقرار الاتصال", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = {
                    val opened = runCatching {
                        supCtx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${supCtx.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    }.isSuccess || runCatching {
                        supCtx.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.isSuccess
                    if (!opened) {
                        Toast.makeText(supCtx, "يرجى استثناء التطبيق من تحسين البطارية عبر الإعدادات.", Toast.LENGTH_LONG).show()
                    }
                }) { Text("تفعيل") }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = PrimaryTeal)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "السرعة القصوى لجميع الأجهزة: ${formatSpeedArabic(globalSpeedCapMbps)}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.0 to "بدون حد", 1.0 to "1 ميجابت/ث", 2.0 to "2 ميجابت/ث", 3.0 to "3 ميجابت/ث", 5.0 to "5 ميجابت/ث").forEach { (cap, label) ->
                    FilterChip(
                        selected = globalSpeedCapMbps == cap,
                        onClick = { onQuickGlobalCapChange(cap) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SoftMint)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("رمز ربط جهاز جديد", fontSize = 13.sp, color = TextMuted)
                Text(
                    text = pairingCode.ifBlank { "------" },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PrimaryTeal,
                    letterSpacing = 4.sp
                )
                Text("عنوان الشبكة المحلي: $localIp", fontSize = 12.sp, color = TextMuted)
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(PrimaryTeal),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = Color.White)
            }
        }
    }

    Text(
        text = "الأجهزة المرتبطة (${devices.size})",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
    )

    if (devices.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("لا توجد أجهزة مرتبطة بعد", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "ثبّت التطبيق على جهاز العميل واختر (جهاز عضو) ثم أدخل رمز الربط أعلاه.",
                    fontSize = 13.sp,
                    color = TextMuted
                )
            }
        }
    } else {
        devices.forEach { device ->
            DeviceMonitorCard(device = device, globalSpeedCapMbps = globalSpeedCapMbps, onClick = { onEditDevice(device) }, onTogglePause = { onTogglePauseDevice(device) })
        }
    }
}

@Composable
private fun DeviceMonitorCard(device: ManagedDevice, globalSpeedCapMbps: Double, onClick: () -> Unit, onTogglePause: () -> Unit) {
    val progress = (device.usagePercent / 100f).coerceIn(0f, 1f)
    val barColor = when {
        !device.isOnline || !device.protectionActive -> AlertRed
        device.isPaused || device.connectionState == ConnectionState.BLOCKED || device.connectionState == ConnectionState.PAUSED -> AlertRed
        device.connectionState == ConnectionState.SLOWED || device.connectionState == ConnectionState.GRACE_EXTRA -> WarnAmber
        else -> PrimaryTeal
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (device.isOnline) SuccessGreen else AlertRed)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${device.deviceName} (${device.limitPeriod.arabicLabel})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onTogglePause, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = if (device.isPaused) Icons.Rounded.PlayCircle else Icons.Rounded.PauseCircle,
                            contentDescription = if (device.isPaused) "استئناف الإنترنت" else "إيقاف مؤقت",
                            tint = if (device.isPaused) SuccessGreen else AlertRed,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Rounded.Edit, contentDescription = "تعديل", tint = TextMuted, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = barColor,
                trackColor = Color(0xFFE5ECEE)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatDataSizeArabic(device.usedMb)} / ${formatDataSizeArabic(device.dailyLimitMb)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when {
                        !device.isOnline -> "غير متصل"
                        !device.protectionActive -> "الحماية متوقفة!"
                        device.isPaused || device.connectionState == ConnectionState.PAUSED -> "موقوف مؤقتاً بواسطة المشرف"
                        device.connectionState == ConnectionState.BLOCKED -> "موقوف (انتهى الحد)"
                        device.connectionState == ConnectionState.IDLE_FROZEN -> "تجميد الخلفية"
                        device.connectionState == ConnectionState.GRACE_EXTRA -> "رصيد إضافي"
                        device.connectionState == ConnectionState.SLOWED -> "تهدئة تدريجية"
                        device.connectionState == ConnectionState.OUTSIDE_TARGET_WIFI -> "خارج الشبكة المحددة"
                        device.connectionState == ConnectionState.SMART_SAVING -> "توفير ذكي نشط"
                        else -> "متصل بشكل طبيعي"
                    },
                    fontSize = 12.sp,
                    color = barColor,
                    fontWeight = FontWeight.Bold
                )
            }

            val speedFmt = remember { DecimalFormat("0.#", DecimalFormatSymbols(Locale.US)) }
            val safeSpeed = device.actualSpeedMbps.sanitizeFinite(0.0, 0.0, 10_000.0)
            val liveSpd = if (device.isOnline) "${speedFmt.format(safeSpeed)} ميجابت/ث" else "0 ميجابت/ث"
            val effBaseCap = when {
                device.speedPolicy.baseSpeedLimitMbPerSec > 0.0 && globalSpeedCapMbps > 0.0 ->
                    minOf(device.speedPolicy.baseSpeedLimitMbPerSec, globalSpeedCapMbps)
                device.speedPolicy.baseSpeedLimitMbPerSec > 0.0 -> device.speedPolicy.baseSpeedLimitMbPerSec
                globalSpeedCapMbps > 0.0 -> globalSpeedCapMbps
                else -> 0.0
            }
            val capText = formatSpeedArabic(effBaseCap)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "السرعة الفعلية: $liveSpd (الحد: $capText) • المستثناة: ${device.whitelistedPackages.size}",
                fontSize = 12.sp,
                color = TextMuted
            )
            if (device.pendingConfigPush) {
                Text(
                    text = "⏳ توجد تعديلات بانتظار اتصال الجهاز لتطبيقها",
                    fontSize = 11.sp,
                    color = WarnAmber,
                    fontWeight = FontWeight.SemiBold
                )
            } else if (!device.isOnline && device.lastSeenMillis > 0L) {
                val minsAgo = ((System.currentTimeMillis() - device.lastSeenMillis).coerceAtLeast(0L) / 60_000L)
                val agoText = when {
                    minsAgo < 1L -> "منذ لحظات"
                    minsAgo < 60L -> "منذ $minsAgo دقيقة"
                    else -> "منذ ${minsAgo / 60L} ساعة"
                }
                Text(text = "آخر ظهور: $agoText", fontSize = 11.sp, color = TextMuted)
            }

            if (!device.tamperWarning.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "⚠ ${device.tamperWarning}",
                    fontSize = 12.sp,
                    color = AlertRed,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SelfLimitTab(
    selfLimitEnabled: Boolean,
    limitPeriod: LimitPeriod,
    usedMb: Long,
    dailyLimitMb: Long,
    speedPolicy: SpeedPolicyConfig,
    whitelistCount: Int,
    vpnRunning: Boolean,
    connState: ConnectionState,
    statusLabel: String,
    alwaysOnActive: Boolean,
    adminActive: Boolean,
    hasUsageAccess: Boolean,
    onToggleSelfLimit: (Boolean) -> Unit,
    onRequestStartVpn: () -> Unit,
    onEditLimits: () -> Unit,
    onAddBonusQuota: (Long) -> Unit,
    onEditWhitelist: () -> Unit,
    onOpenAlwaysOn: () -> Unit,
    onRequestAdmin: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("تفعيل الحد على جهازي", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "تطبيق نفس القواعد الذكية والحدود على جهازك الشخصي",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
            Switch(checked = selfLimitEnabled, onCheckedChange = onToggleSelfLimit)
        }
    }

    if (selfLimitEnabled && !vpnRunning) {
        Button(
            onClick = onRequestStartVpn,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("تفعيل حماية الاتصال الآن")
        }
    }

    UsageRingCard(
        title = "استهلاكي (${limitPeriod.arabicLabel})",
        usedMb = usedMb,
        dailyLimitMb = dailyLimitMb,
        connectionState = connState,
        protectionActive = vpnRunning,
        statusLabelOverride = statusLabel
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("إعدادات حدي الشخصي", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "الحد (${limitPeriod.arabicLabel}): ${formatDataSizeArabic(dailyLimitMb)} • السرعة: ${formatSpeedArabic(speedPolicy.baseSpeedLimitMbPerSec)}",
                fontSize = 13.sp,
                color = TextMuted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onEditLimits, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Text("تعديل الحد والمراحل")
                }
                OutlinedButton(onClick = onEditWhitelist, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("المسموحة ($whitelistCount)")
                }
            }
            OutlinedButton(
                onClick = { onAddBonusQuota(250L) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("إضافة رصيد إضافي سريع (+250 م.ب للفترة الحالية)")
            }
            if (!alwaysOnActive) {
                OutlinedButton(onClick = onOpenAlwaysOn, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text("تثبيت الحماية الدائمة من إعدادات الجهاز")
                }
            }
            if (!hasUsageAccess && selfLimitEnabled) {
                val slCtx = LocalContext.current
                OutlinedButton(
                    onClick = {
                        runCatching {
                            slCtx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure {
                            Toast.makeText(slCtx, "يرجى تفعيل صلاحية الوصول لبيانات الاستخدام من الإعدادات", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("منح إذن إحصائيات التطبيقات")
                }
            }
            if (!adminActive) {
                OutlinedButton(onClick = onRequestAdmin, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text("تفعيل الحماية ضد الحذف")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedSettingsTab(
    globalSpeedCapMbps: Double,
    policy: SpeedPolicyConfig,
    hasPin: Boolean,
    deviceCount: Int,
    onSavePolicyWithCap: (SpeedPolicyConfig, Double?) -> Unit,
    onApplyPolicyToAllWithCap: (SpeedPolicyConfig, Double?) -> Unit,
    githubRepo: String,
    targetNetworkOnly: Boolean,
    targetNetworkSig: String,
    onSetTargetNetwork: (Boolean, String?) -> Unit,
    onSavePin: (String) -> Unit,
    onResetUsage: () -> Unit,
    onSwitchRole: () -> Unit
) {
    var draftPolicy by remember(policy) { mutableStateOf(policy) }
    var presetSyncVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(policy) {
        draftPolicy = policy
        presetSyncVersion++
    }
    var globalCapText by remember(globalSpeedCapMbps) {
        mutableStateOf(if (globalSpeedCapMbps == 0.0) "0" else globalSpeedCapMbps.toString())
    }
    var baseText by remember(presetSyncVersion) {
        mutableStateOf(draftPolicy.baseSpeedLimitMbPerSec.toString())
    }
    var confirmResetUsage by remember { mutableStateOf(false) }
    var confirmSwitchRole by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    var pinInput by remember { mutableStateOf("") }

    var expandSpeed by rememberSaveable { mutableStateOf(true) }
    var expandPhases by rememberSaveable { mutableStateOf(false) }
    var expandGrace by rememberSaveable { mutableStateOf(false) }
    var expandAutomation by rememberSaveable { mutableStateOf(false) }
    var expandSecurity by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SoftMint)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("أوضاع جاهزة بضغطة واحدة", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = PrimaryTeal)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val bal = SpeedPolicyConfig.presetBalanced()
                val maxS = SpeedPolicyConfig.presetMaxSaver()
                val rel = SpeedPolicyConfig.presetRelaxed()
                FilterChip(
                    selected = draftPolicy.baseSpeedLimitMbPerSec == bal.baseSpeedLimitMbPerSec && draftPolicy.finalPhaseSpeedMbPerSec == bal.finalPhaseSpeedMbPerSec && draftPolicy.nightSaverEnabled == bal.nightSaverEnabled,
                    onClick = {
                        val next = bal.copy(appQuotaPercents = draftPolicy.appQuotaPercents)
                        onSavePolicyWithCap(next, null)
                    },
                    label = { Text("متوازن") }
                )
                FilterChip(
                    selected = draftPolicy.baseSpeedLimitMbPerSec == maxS.baseSpeedLimitMbPerSec && draftPolicy.finalPhaseSpeedMbPerSec == maxS.finalPhaseSpeedMbPerSec && draftPolicy.nightSaverEnabled == maxS.nightSaverEnabled,
                    onClick = {
                        val next = maxS.copy(appQuotaPercents = draftPolicy.appQuotaPercents)
                        onSavePolicyWithCap(next, null)
                    },
                    label = { Text("توفير أقصى") }
                )
                FilterChip(
                    selected = draftPolicy.baseSpeedLimitMbPerSec == rel.baseSpeedLimitMbPerSec && draftPolicy.finalPhaseSpeedMbPerSec == rel.finalPhaseSpeedMbPerSec && !draftPolicy.quotaSaverModeEnabled,
                    onClick = {
                        val next = rel.copy(appQuotaPercents = draftPolicy.appQuotaPercents)
                        onSavePolicyWithCap(next, null)
                    },
                    label = { Text("مرن (سرعة عالية)") }
                )
            }
        }
    }

    AccordionCard(
        icon = Icons.Rounded.Speed,
        title = "1. السرعة القصوى (ميجابت/ث)",
        subtitle = "تحديد سرعة جميع الأجهزة أو هذا الجهاز بالميجابت/ثانية (Mbps)",
        expanded = expandSpeed,
        onToggle = { expandSpeed = !expandSpeed }
    ) {
        val parsedGlobal = normalizeArabicDigits(globalCapText).toDoubleOrNull()
        val globalValid = parsedGlobal != null && parsedGlobal.isFinite() && parsedGlobal in 0.0..1000.0
        OutlinedTextField(
            value = globalCapText,
            onValueChange = { txt -> globalCapText = normalizeArabicDigits(txt) },
            label = { Text("الحد الأقصى للسرعة لجميع الأجهزة (ميجابت/ث، 0 = بدون حد)") },
            isError = globalCapText.isNotBlank() && !globalValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = baseText,
            onValueChange = { txt ->
                val norm = normalizeArabicDigits(txt)
                baseText = norm
                norm.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1000.0 }?.let { v ->
                    draftPolicy = draftPolicy.copy(baseSpeedLimitMbPerSec = v)
                }
            },
            label = { Text("السرعة الافتراضية قبل التهدئة (ميجابت/ث، 0 = سرعة كاملة)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    AccordionCard(
        icon = Icons.Rounded.Tune,
        title = "2. السرعة النهائية قبل انتهاء الباقة",
        subtitle = "عند تبقي ${draftPolicy.finalPhaseRemainingPercent}% ← ${draftPolicy.finalPhaseSpeedMbPerSec} ميجابت/ث",
        expanded = expandPhases,
        onToggle = { expandPhases = !expandPhases }
    ) {
        PhasedRulesControls(policy = draftPolicy, syncKey = presetSyncVersion, onChange = { draftPolicy = it })
    }

    AccordionCard(
        icon = Icons.Rounded.HourglassBottom,
        title = "3. الرصيد الإضافي وترحيل الباقة",
        subtitle = "+${draftPolicy.graceExtraPercent}% بسرعة ${draftPolicy.graceSpeedMbPerSec} ميجابت/ث • ترحيل ${draftPolicy.rolloverPercent}%",
        expanded = expandGrace,
        onToggle = { expandGrace = !expandGrace }
    ) {
        GraceAndRolloverControls(policy = draftPolicy, syncKey = presetSyncVersion, onChange = { draftPolicy = it })
    }

    AccordionCard(
        icon = Icons.Rounded.AutoAwesome,
        title = "4. التوفير الذكي التلقائي",
        subtitle = if (draftPolicy.quotaSaverModeEnabled) "مفعّل" else "معطّل",
        expanded = expandAutomation,
        onToggle = { expandAutomation = !expandAutomation }
    ) {
        SmartAutomationControls(policy = draftPolicy, syncKey = presetSyncVersion, onChange = { draftPolicy = it })
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                val parsedCap = normalizeArabicDigits(globalCapText).toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1000.0 }
                onSavePolicyWithCap(draftPolicy, parsedCap)
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("حفظ الإعدادات")
        }
        if (deviceCount > 0) {
            OutlinedButton(
                onClick = {
                    val parsedCap = normalizeArabicDigits(globalCapText).toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1000.0 }
                    onApplyPolicyToAllWithCap(draftPolicy, parsedCap)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("تطبيق على الكل ($deviceCount)")
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("تطبيق القيود على شبكة واحدة فقط", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "عند الخروج لشبكة أخرى تُرفع القيود مؤقتاً ولا يُحسب الاستهلاك",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
                val netScope = rememberCoroutineScope()
                Switch(
                    checked = targetNetworkOnly,
                    onCheckedChange = { enabled ->
                        if (enabled && targetNetworkSig.isBlank()) {
                            netScope.launch {
                                val sig = NetworkScanner.getCurrentWifiSignatureActive(ctx)
                                onSetTargetNetwork(true, sig)
                            }
                        } else {
                            onSetTargetNetwork(enabled, null)
                        }
                    }
                )
            }
            if (targetNetworkSig.isNotBlank()) {
                Text(
                    text = "الشبكة المقفولة حالياً: ${NetworkScanner.formatSignatureForDisplay(targetNetworkSig)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryTeal
                )
            }
            val btnNetScope = rememberCoroutineScope()
            var showLocationRationale by remember { mutableStateOf(false) }
            var showManualSsidDialog by remember { mutableStateOf(false) }
            var manualSsidText by remember { mutableStateOf("") }
            var detectedMacPart by remember { mutableStateOf("") }

            fun captureOrAskSsid() {
                btnNetScope.launch {
                    val sig = NetworkScanner.getCurrentWifiSignatureActive(ctx)
                    if (sig.contains("ssid=")) {
                        onSetTargetNetwork(true, sig)
                    } else {
                        detectedMacPart = sig
                        manualSsidText = ""
                        showManualSsidDialog = true
                    }
                }
            }

            val locationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { _ ->
                captureOrAskSsid()
            }
            OutlinedButton(
                onClick = {
                    val hasLoc = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (!hasLoc) {
                        showLocationRationale = true
                    } else {
                        captureOrAskSsid()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (targetNetworkSig.isBlank()) "تعيين شبكة الواي فاي الحالية للمراقبة"
                    else "تحديث الشبكة المحددة (${NetworkScanner.formatSignatureForDisplay(targetNetworkSig)})",
                    fontSize = 12.sp
                )
            }
            if (showManualSsidDialog) {
                AlertDialog(
                    onDismissRequest = { showManualSsidDialog = false },
                    title = { Text("أدخل اسم شبكة الواي فاي", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "فعّل زر الموقع (GPS) في شريط الإشعارات لقراءة الاسم تلقائياً، أو اكتب اسم شبكة الواي فاي هنا مباشرة:",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                            OutlinedTextField(
                                value = manualSsidText,
                                onValueChange = { manualSsidText = it.replace("|", "") },
                                label = { Text("اسم شبكة الواي فاي (SSID)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val clean = manualSsidText.replace("|", "").trim()
                            if (clean.isNotBlank()) {
                                val combined = if (detectedMacPart.isNotBlank()) "ssid=$clean|$detectedMacPart" else "ssid=$clean"
                                onSetTargetNetwork(true, combined)
                                showManualSsidDialog = false
                            }
                        }) { Text("حفظ") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showManualSsidDialog = false }) { Text("إلغاء") }
                    }
                )
            }

            if (showLocationRationale) {
                AlertDialog(
                    onDismissRequest = { showLocationRationale = false },
                    title = { Text("لماذا نحتاج إذن الموقع؟", fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            "يشترط نظام أندرويد إذن الموقع فقط لقراءة اسم وبصمة راوتر الواي فاي (BSSID) لتمييز شبكتك عن أي شبكة أخرى. التطبيق لا يتتبع موقعك أبداً."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showLocationRationale = false
                            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }) { Text("موافق، منح الإذن") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLocationRationale = false }) { Text("إلغاء") }
                    }
                )
            }
        }
    }

    if (BuildConfig.ENABLE_GITHUB_UPDATER) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            val updateScope = rememberCoroutineScope()
            var updateMsg by remember { mutableStateOf<String?>(null) }
            var isChecking by remember { mutableStateOf(false) }

            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = PrimaryTeal)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("تحديثات التطبيق", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Text(
                    "الإصدار الحالي: 1.0.${GitHubUpdater.getInstalledVersionCode(ctx)}",
                    fontSize = 12.sp,
                    color = TextMuted
                )
                if (updateMsg != null) {
                    Text(updateMsg!!, fontSize = 12.sp, color = PrimaryTeal, fontWeight = FontWeight.Medium)
                }
                Button(
                    onClick = {
                        isChecking = true
                        updateMsg = "جاري التحقق من آخر إصدار..."
                        updateScope.launch {
                            val targetRepo = githubRepo.ifBlank { GitHubUpdater.DEFAULT_REPO }
                            val res = GitHubUpdater.checkLatestRelease(ctx, targetRepo)
                            res.onSuccess { info ->
                                if (!info.hasUpdate) {
                                    updateMsg = "لديك أحدث إصدار بالفعل (1.0.${info.localBuildNumber})"
                                } else {
                                    updateMsg = "يتوفر إصدار جديد (${info.versionTag}) — جاري التحميل..."
                                    val dl = GitHubUpdater.downloadAndInstallApk(ctx, info.downloadUrl)
                                    dl.onSuccess {
                                        updateMsg = "تم تحميل التحديث — أكمل التثبيت من الشاشة."
                                    }.onFailure { e ->
                                        updateMsg = "فشل التحميل: ${e.message}"
                                    }
                                }
                            }.onFailure { e ->
                                updateMsg = e.message ?: "تعذر التحقق من التحديث"
                            }
                            isChecking = false
                        }
                    },
                    enabled = !isChecking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isChecking) "جاري التحقق..." else "التحقق من التحديثات")
                }
            }
        }
    }

    AccordionCard(
        icon = Icons.Rounded.Lock,
        title = "5. الأمان وإدارة التطبيق",
        subtitle = if (hasPin) "الرمز السري مفعّل • تصفير العدادات • تبديل الوضع" else "الرمز السري، تصفير العدادات، تبديل وضع الجهاز",
        expanded = expandSecurity,
        onToggle = { expandSecurity = !expandSecurity }
    ) {
        OutlinedTextField(
            value = pinInput,
            onValueChange = { raw ->
                val clean = normalizeArabicDigits(raw).filter { it in '0'..'9' }
                if (clean.length <= 6) pinInput = clean
            },
            label = {
                Text(if (hasPin) "رمز سري جديد (اتركه فارغاً لإلغاء القفل)" else "الرمز السري لقفل الإعدادات (اختياري)")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = hasPin || pinInput.length >= 4,
            onClick = {
                val targetPin = pinInput
                onSavePin(targetPin)
                pinInput = ""
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(if (pinInput.isBlank() && hasPin) "إلغاء الرمز السري" else "حفظ الرمز السري")
        }
        OutlinedButton(onClick = { confirmResetUsage = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Text("تصفير عدادات الاستهلاك لهذا الجهاز")
        }
        OutlinedButton(
            onClick = { confirmSwitchRole = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertRed)
        ) {
            Text("تغيير نوع الجهاز (مشرف / عضو)")
        }
    }

    if (confirmResetUsage) {
        AlertDialog(
            onDismissRequest = { confirmResetUsage = false },
            title = { Text("تأكيد تصفير العدادات", fontWeight = FontWeight.Bold) },
            text = { Text("سيتم تصفير عدادات الاستهلاك اليومي والأسبوعي والشهري لهذا الجهاز فقط. هل تريد المتابعة؟") },
            confirmButton = {
                TextButton(onClick = {
                    confirmResetUsage = false
                    onResetUsage()
                }) { Text("تصفير", color = AlertRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetUsage = false }) { Text("إلغاء") }
            }
        )
    }

    if (confirmSwitchRole) {
        AlertDialog(
            onDismissRequest = { confirmSwitchRole = false },
            title = { Text("تأكيد تغيير وضع الجهاز", fontWeight = FontWeight.Bold) },
            text = { Text("سيتم إيقاف خدمة المشرف والعودة لشاشة الإعداد الأولي. هل تريد المتابعة؟") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSwitchRole = false
                    onSwitchRole()
                }) { Text("تغيير الوضع", color = AlertRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSwitchRole = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun AccordionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = PrimaryTeal)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(subtitle, fontSize = 12.sp, color = TextMuted)
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = TextMuted
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider()
                    content()
                }
            }
        }
    }
}

@Composable
private fun PhasedRulesControls(
    policy: SpeedPolicyConfig,
    syncKey: Int = 0,
    onChange: (SpeedPolicyConfig) -> Unit
) {
    var finalSpeedText by remember(syncKey) {
        mutableStateOf(policy.finalPhaseSpeedMbPerSec.toString())
    }
    var winSpeedText by remember(syncKey) {
        mutableStateOf(policy.windowSlowSpeedMbps.toString())
    }

    Text("المرحلة الأخيرة عند تبقي: ${policy.finalPhaseRemainingPercent}% من الباقة", fontSize = 12.sp)
    Slider(
        value = policy.finalPhaseRemainingPercent.toFloat(),
        onValueChange = { onChange(policy.copy(finalPhaseRemainingPercent = it.toInt())) },
        valueRange = 5f..40f
    )

    OutlinedTextField(
        value = finalSpeedText,
        onValueChange = { txt ->
            val norm = normalizeArabicDigits(txt)
            finalSpeedText = norm
            norm.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..1000.0 }?.let { v ->
                onChange(policy.copy(finalPhaseSpeedMbPerSec = v))
            }
        },
        label = { Text("سرعة المرحلة الأخيرة (ميجابت/ث)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("منع السحب السريع خلال فترة زمنية", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("يمنع استهلاك نسبة كبيرة من الباقة في وقت قصير ويُهدئ السرعة حتى تنتهي الفترة", fontSize = 11.sp, color = TextMuted)
        }
        Switch(
            checked = policy.windowPaceEnabled,
            onCheckedChange = { onChange(policy.copy(windowPaceEnabled = it)) }
        )
    }

    if (policy.windowPaceEnabled) {
        Text("أقصى استهلاك مسموح: ${policy.windowMaxPercent}% خلال ${policy.windowHours} ساعات", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PrimaryTeal)
        Slider(
            value = policy.windowMaxPercent.toFloat(),
            onValueChange = { onChange(policy.copy(windowMaxPercent = it.toInt())) },
            valueRange = 15f..90f
        )
        Text("مدة النافذة الزمنية: ${policy.windowHours} ساعات", fontSize = 12.sp)
        Slider(
            value = policy.windowHours.toFloat(),
            onValueChange = { onChange(policy.copy(windowHours = it.toInt())) },
            valueRange = 1f..12f
        )
        OutlinedTextField(
            value = winSpeedText,
            onValueChange = { txt ->
                val norm = normalizeArabicDigits(txt)
                winSpeedText = norm
                norm.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..1000.0 }?.let { v ->
                    onChange(policy.copy(windowSlowSpeedMbps = v))
                }
            },
            label = { Text("السرعة عند الوصول لحد الفترة (ميجابت/ث)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GraceAndRolloverControls(
    policy: SpeedPolicyConfig,
    syncKey: Int = 0,
    onChange: (SpeedPolicyConfig) -> Unit
) {
    var graceSpeedText by remember(syncKey) {
        mutableStateOf(policy.graceSpeedMbPerSec.toString())
    }
    Text(
        "بعد انتهاء 100% يستمر الاتصال بالسرعة المحددة ويُخصم من الحد الأسبوعي والشهري:",
        fontSize = 11.sp,
        color = TextMuted
    )
    Text("نسبة الرصيد الإضافي: +${policy.graceExtraPercent}% (0% = إيقاف فوري)", fontSize = 12.sp)
    Slider(
        value = policy.graceExtraPercent.toFloat(),
        onValueChange = { onChange(policy.copy(graceExtraPercent = it.toInt())) },
        valueRange = 0f..30f
    )
    OutlinedTextField(
        value = graceSpeedText,
        onValueChange = { txt ->
            val norm = normalizeArabicDigits(txt)
            graceSpeedText = norm
            norm.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.05..1000.0 }?.let { v ->
                onChange(policy.copy(graceSpeedMbPerSec = v))
            }
        },
        label = { Text("سرعة الرصيد الإضافي (ميجابت/ث)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    HorizontalDivider()
    Text("ترحيل الرصيد المتبقي تلقائياً لليوم التالي: ${policy.rolloverPercent}%", fontSize = 12.sp)
    Slider(
        value = policy.rolloverPercent.toFloat(),
        onValueChange = { onChange(policy.copy(rolloverPercent = it.toInt())) },
        valueRange = 0f..100f
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartAutomationControls(
    policy: SpeedPolicyConfig,
    syncKey: Int = 0,
    onChange: (SpeedPolicyConfig) -> Unit
) {
    var mediaSpeedText by remember(syncKey) { mutableStateOf(policy.mediaCdnSpeedMbps.toString()) }
    var burstCapText by remember(syncKey) { mutableStateOf(policy.burstCapSpeedMbPerSec.toString()) }
    var peakSpeedText by remember(syncKey) { mutableStateOf(policy.peakHoursSpeedMbPerSec.toString()) }
    var nightSpeedText by remember(syncKey) { mutableStateOf(policy.nightSaverSpeedMbPerSec.toString()) }
    var customDnsText by remember(syncKey) { mutableStateOf(policy.upstreamDnsIp) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = policy.quotaSaverModeEnabled,
            onCheckedChange = {
                onChange(policy.copy(quotaSaverModeEnabled = it, autoEnableSaverAtPercent = if (it) 0 else 100))
            }
        )
        Column {
            Text("تفعيل وضع التوفير الذكي", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("تقليل جودة الفيديو + معاملة الواي فاي كباقة محدودة + تجميد الخلفية", fontSize = 11.sp, color = TextMuted)
        }
    }

    HorizontalDivider()
    Text("تخصيص مكونات وضع التوفير:", fontWeight = FontWeight.Bold, fontSize = 13.sp)

    if (BuildConfig.ENABLE_DNS_ADBLOCK) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = policy.dnsSinkholeEnabled,
                onCheckedChange = { onChange(policy.copy(dnsSinkholeEnabled = it)) }
            )
            Column {
                Text("حجب الإعلانات والتتبع قبل التحميل", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("يمنع تحميل الإعلانات وملفات التتبع (صفر بايت)", fontSize = 11.sp, color = TextMuted)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = policy.dnsCacheEnabled,
                onCheckedChange = { onChange(policy.copy(dnsCacheEnabled = it)) }
            )
            Text("تخزين مؤقت ذكي للعناوين (5 دقائق)", fontSize = 12.sp)
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = policy.blockDohDotEnabled,
                onCheckedChange = { onChange(policy.copy(blockDohDotEnabled = it)) }
            )
            Text("منع التطبيقات من تجاوز حجب الإعلانات", fontSize = 12.sp)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = policy.blockQuicUdp443Enabled,
            onCheckedChange = { onChange(policy.copy(blockQuicUdp443Enabled = it)) }
        )
        Text("تحسين تقليل جودة الفيديو التلقائي (360p/480p)", fontSize = 12.sp)
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = policy.meteredWifiSaverEnabled,
            onCheckedChange = { onChange(policy.copy(meteredWifiSaverEnabled = it)) }
        )
        Column {
            Text("معاملة الواي فاي كباقة محدودة (مثل بيانات الهاتف)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("يخفض استهلاك يوتيوب وفيسبوك والواتساب والمتجر تلقائياً بنسبة تصل إلى 70% (أندرويد 10+)", fontSize = 11.sp, color = TextMuted)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = policy.mediaCdnSaverEnabled,
            onCheckedChange = { onChange(policy.copy(mediaCdnSaverEnabled = it)) }
        )
        Column {
            Text("توفير ذكي للفيديو والريلز (360p/480p تلقائياً)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("يخفض سرعة خوادم الفيديو فقط مع إبقاء التصفح والمحادثات سريعة", fontSize = 11.sp, color = TextMuted)
        }
    }
    if (policy.mediaCdnSaverEnabled) {
        OutlinedTextField(
            value = mediaSpeedText,
            onValueChange = { txt ->
                val norm = normalizeArabicDigits(txt)
                mediaSpeedText = norm
                norm.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.25..1000.0 }?.let { v ->
                    onChange(policy.copy(mediaCdnSpeedMbps = v))
                }
            },
            label = { Text("سرعة خوادم الفيديو والريلز (ميجابت/ث)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = policy.blockAppUpdatesInSaver,
            onCheckedChange = { onChange(policy.copy(blockAppUpdatesInSaver = it)) }
        )
        Column {
            Text("منع التحديثات الصامتة للتطبيقات أثناء التوفير", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("يمنع تحميل تحديثات المتجر والنظام الضخمة في الخلفية", fontSize = 11.sp, color = TextMuted)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = policy.idleScreenFreezeEnabled,
            onCheckedChange = { onChange(policy.copy(idleScreenFreezeEnabled = it)) }
        )
        Column {
            Text("تجميد الخلفية عند إغلاق الشاشة (> 60 ثانية)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("يمنع التحديثات والنسخ الاحتياطي الصامت في الخلفية", fontSize = 11.sp, color = TextMuted)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = policy.burstGuardEnabled,
            onCheckedChange = { onChange(policy.copy(burstGuardEnabled = it)) }
        )
        Text("حماية السحب المفاجئ (>${policy.burstThresholdMbPerMin} م.ب/دقيقة ← ${policy.burstCapSpeedMbPerSec} ميجابت/ث)", fontSize = 12.sp)
    }
    if (policy.burstGuardEnabled) {
        Text("حساسية السحب المفاجئ: ${policy.burstThresholdMbPerMin} م.ب/دقيقة", fontSize = 11.sp, color = TextMuted)
        Slider(
            value = policy.burstThresholdMbPerMin.toFloat(),
            onValueChange = { onChange(policy.copy(burstThresholdMbPerMin = it.toInt())) },
            valueRange = 20f..150f
        )
        OutlinedTextField(
            value = burstCapText,
            onValueChange = { txt ->
                val norm = normalizeArabicDigits(txt)
                burstCapText = norm
                norm.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..1000.0 }?.let { v ->
                    onChange(policy.copy(burstCapSpeedMbPerSec = v))
                }
            },
            label = { Text("السرعة للتطبيق عند السحب المفاجئ (ميجابت/ث)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = policy.peakHoursSaverEnabled,
            onCheckedChange = { onChange(policy.copy(peakHoursSaverEnabled = it)) }
        )
        Text("تخفيف ساعات الذروة (6م – 12ص ← ${policy.peakHoursSpeedMbPerSec} ميجابت/ث)", fontSize = 12.sp)
    }
    if (policy.peakHoursSaverEnabled) {
        OutlinedTextField(
            value = peakSpeedText,
            onValueChange = { txt ->
                val norm = normalizeArabicDigits(txt)
                peakSpeedText = norm
                norm.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..1000.0 }?.let { v ->
                    onChange(policy.copy(peakHoursSpeedMbPerSec = v))
                }
            },
            label = { Text("سرعة ساعات الذروة (ميجابت/ث)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (BuildConfig.ENABLE_DNS_ADBLOCK) {
        HorizontalDivider()
        Text("مزود تصفية الإعلانات (DNS):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "94.140.14.14" to "AdGuard (حجب الإعلانات)",
                "1.1.1.1" to "Cloudflare",
                "8.8.8.8" to "Google"
            ).forEach { (ip, label) ->
                FilterChip(
                    selected = policy.upstreamDnsIp == ip,
                    onClick = {
                        customDnsText = ip
                        onChange(policy.copy(upstreamDnsIp = ip))
                    },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }
        val dnsValid = isValidIpv4(customDnsText)
        OutlinedTextField(
            value = customDnsText,
            onValueChange = { txt ->
                val norm = normalizeArabicDigits(txt)
                customDnsText = norm
                if (isValidIpv4(norm)) {
                    onChange(policy.copy(upstreamDnsIp = norm.trim()))
                }
            },
            label = { Text("عنوان DNS مخصص (IPv4)") },
            isError = customDnsText.isNotBlank() && !dnsValid,
            supportingText = if (customDnsText.isNotBlank() && !dnsValid) {
                { Text("أدخل عنوان IPv4 صالحاً مثل 94.140.14.14") }
            } else null,
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = policy.nightSaverEnabled,
            onCheckedChange = { onChange(policy.copy(nightSaverEnabled = it)) }
        )
        Text("توفير ليلي (12ص – 7ص ← ${policy.nightSaverSpeedMbPerSec} ميجابت/ث)", fontSize = 12.sp)
    }
    if (policy.nightSaverEnabled) {
        OutlinedTextField(
            value = nightSpeedText,
            onValueChange = { txt ->
                val norm = normalizeArabicDigits(txt)
                nightSpeedText = norm
                norm.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..1000.0 }?.let { v ->
                    onChange(policy.copy(nightSaverSpeedMbPerSec = v))
                }
            },
            label = { Text("سرعة التوفير الليلي (ميجابت/ث)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodSelectorRow(
    selected: LimitPeriod,
    onSelect: (LimitPeriod) -> Unit
) {
    Column {
        Text("فترة الحد:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LimitPeriod.entries.forEach { period ->
                FilterChip(
                    selected = selected == period,
                    onClick = { onSelect(period) },
                    label = { Text(period.arabicLabel) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDeviceDialog(
    device: ManagedDevice,
    onDismiss: () -> Unit,
    onSave: (ManagedDevice) -> Unit,
    onDelete: () -> Unit
) {
    var limitMbText by remember(device.deviceId) { mutableStateOf(device.dailyLimitMb.toString()) }
    var period by remember(device.deviceId) { mutableStateOf(device.limitPeriod) }
    var policy by remember(device.deviceId) { mutableStateOf(device.speedPolicy) }
    var forceSaverForDevice by remember(device.deviceId) { mutableStateOf(device.forceQuotaSaver) }
    var baseSpeedText by remember(device.deviceId) { mutableStateOf(device.speedPolicy.baseSpeedLimitMbPerSec.toString()) }
    var whitelist by remember(device.deviceId) { mutableStateOf(device.whitelistedPackages.toSet()) }
    var showWhitelist by remember(device.deviceId) { mutableStateOf(false) }
    var showAppQuotas by remember(device.deviceId) { mutableStateOf(false) }
    var showAdvancedInDialog by remember(device.deviceId) { mutableStateOf(false) }
    var confirmDelete by remember(device.deviceId) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("إعدادات ${device.deviceName}", fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "حذف", tint = AlertRed)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 450.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PeriodSelectorRow(selected = period, onSelect = { period = it })

                Text("الحد (${period.arabicLabel}) بالميجابايت:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1024L to "1 ج.ب", 5120L to "5 ج.ب", 15360L to "15 ج.ب", 30720L to "30 ج.ب").forEach { (mb, label) ->
                        FilterChip(
                            selected = limitMbText == mb.toString(),
                            onClick = { limitMbText = mb.toString() },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = limitMbText,
                    onValueChange = { raw ->
                        limitMbText = normalizeArabicDigits(raw).filter { ch -> ch in '0'..'9' }
                    },
                    label = { Text("أو أدخل الرقم بالميجابايت") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("تفعيل وضع التوفير لهذا الجهاز", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Switch(checked = forceSaverForDevice, onCheckedChange = { forceSaverForDevice = it })
                }

                OutlinedTextField(
                    value = baseSpeedText,
                    onValueChange = { txt ->
                        val norm = normalizeArabicDigits(txt)
                        baseSpeedText = norm
                        norm.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1000.0 }?.let { v ->
                            policy = policy.copy(baseSpeedLimitMbPerSec = v)
                        }
                    },
                    label = { Text("السرعة القصوى لهذا الجهاز (ميجابت/ث، 0 = سرعة كاملة)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = { showWhitelist = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("التطبيقات المسموحة دائماً (${whitelist.size})")
                }

                OutlinedButton(
                    onClick = { showAppQuotas = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("حد نسبة الباقة لتطبيقات معينة (${policy.appQuotaPercents.size})")
                }

                TextButton(
                    onClick = { showAdvancedInDialog = !showAdvancedInDialog },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (showAdvancedInDialog) "إخفاء المراحل والأتمتة ▲" else "تخصيص المراحل والأتمتة ▼")
                }

                AnimatedVisibility(visible = showAdvancedInDialog) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PhasedRulesControls(policy = policy, onChange = { policy = it })
                        HorizontalDivider()
                        GraceAndRolloverControls(policy = policy, onChange = { policy = it })
                        HorizontalDivider()
                        SmartAutomationControls(policy = policy, onChange = { policy = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mb = normalizeArabicDigits(limitMbText).toLongOrNull()?.coerceIn(10L, 10_485_760L) ?: device.dailyLimitMb
                onSave(
                    device.copy(
                        dailyLimitMb = mb,
                        limitPeriod = period,
                        forceQuotaSaver = forceSaverForDevice,
                        speedPolicy = policy,
                        whitelistedPackages = whitelist.toList()
                    )
                )
            }) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("تأكيد حذف الجهاز", fontWeight = FontWeight.Bold) },
            text = { Text("هل أنت متأكد من إزالة (${device.deviceName}) من قائمة الأجهزة المرتبطة؟") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text("حذف", color = AlertRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") }
            }
        )
    }

    if (showWhitelist) {
        WhitelistPickerDialog(
            initialSelected = whitelist,
            remoteApps = device.installedApps,
            onDismiss = { showWhitelist = false },
            onConfirm = {
                whitelist = it
                showWhitelist = false
            }
        )
    }

    if (showAppQuotas) {
        AppQuotaPercentsDialog(
            initialMap = policy.appQuotaPercents,
            remoteApps = device.installedApps,
            onDismiss = { showAppQuotas = false },
            onConfirm = {
                policy = policy.copy(appQuotaPercents = it)
                showAppQuotas = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditSelfLimitDialog(
    currentLimitMb: Long,
    currentPeriod: LimitPeriod,
    currentPolicy: SpeedPolicyConfig,
    onDismiss: () -> Unit,
    onSave: (Long, LimitPeriod, SpeedPolicyConfig) -> Unit
) {
    var limitMbText by rememberSaveable { mutableStateOf(currentLimitMb.toString()) }
    var period by rememberSaveable { mutableStateOf(currentPeriod) }
    var policy by remember { mutableStateOf(currentPolicy) }
    var baseSpeedText by rememberSaveable { mutableStateOf(currentPolicy.baseSpeedLimitMbPerSec.toString()) }
    var showSelfAppQuotas by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل حدي الشخصي", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PeriodSelectorRow(selected = period, onSelect = { period = it })

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1024L to "1 ج.ب", 5120L to "5 ج.ب", 15360L to "15 ج.ب", 30720L to "30 ج.ب").forEach { (mb, label) ->
                        FilterChip(
                            selected = limitMbText == mb.toString(),
                            onClick = { limitMbText = mb.toString() },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = limitMbText,
                    onValueChange = { raw ->
                        limitMbText = normalizeArabicDigits(raw).filter { ch -> ch in '0'..'9' }
                    },
                    label = { Text("الحد (${period.arabicLabel}) بالميجابايت") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseSpeedText,
                    onValueChange = { txt ->
                        val norm = normalizeArabicDigits(txt)
                        baseSpeedText = norm
                        norm.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1000.0 }?.let { v ->
                            policy = policy.copy(baseSpeedLimitMbPerSec = v)
                        }
                    },
                    label = { Text("السرعة القصوى (ميجابت/ث، 0 = سرعة كاملة)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { showSelfAppQuotas = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("حد نسبة الباقة لتطبيقات معينة (${policy.appQuotaPercents.size})")
                }
                PhasedRulesControls(policy = policy, onChange = { policy = it })
                GraceAndRolloverControls(policy = policy, onChange = { policy = it })
                SmartAutomationControls(policy = policy, onChange = { policy = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mb = normalizeArabicDigits(limitMbText).toLongOrNull()?.coerceIn(10L, 10_485_760L) ?: currentLimitMb
                onSave(mb, period, policy)
            }) { Text("حفظ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )

    if (showSelfAppQuotas) {
        AppQuotaPercentsDialog(
            initialMap = policy.appQuotaPercents,
            remoteApps = null,
            onDismiss = { showSelfAppQuotas = false },
            onConfirm = {
                policy = policy.copy(appQuotaPercents = it)
                showSelfAppQuotas = false
            }
        )
    }
}

@Composable
private fun PinCheckDialog(
    onVerifyPin: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("أدخل الرمز السري", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { raw ->
                        val clean = normalizeArabicDigits(raw).filter { it in '0'..'9' }
                        if (clean.length <= 6) {
                            input = clean
                            error = false
                        }
                    },
                    label = { Text("الرمز السري") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("الرمز غير صحيح", color = AlertRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !checking && input.isNotBlank(),
                onClick = {
                    checking = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { onVerifyPin(input) }
                        checking = false
                        if (ok) onSuccess() else error = true
                    }
                }
            ) { Text("تأكيد") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

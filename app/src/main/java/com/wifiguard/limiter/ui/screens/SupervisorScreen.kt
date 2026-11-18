package com.wifiguard.limiter.ui.screens

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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifiguard.limiter.admin.WifiGuardDeviceAdminReceiver
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.LimitPeriod
import com.wifiguard.limiter.model.ManagedDevice
import com.wifiguard.limiter.model.SpeedPolicyConfig
import com.wifiguard.limiter.model.TamperAlert
import com.wifiguard.limiter.model.formatDataSizeArabic
import com.wifiguard.limiter.model.formatSpeedArabic
import com.wifiguard.limiter.network.NetworkScanner
import com.wifiguard.limiter.network.SupervisorServerService
import com.wifiguard.limiter.ui.components.UsageRingCard
import com.wifiguard.limiter.ui.components.WhitelistPickerDialog
import com.wifiguard.limiter.ui.theme.AlertRed
import com.wifiguard.limiter.ui.theme.AlertRedBg
import com.wifiguard.limiter.ui.theme.PrimaryTeal
import com.wifiguard.limiter.ui.theme.SoftMint
import com.wifiguard.limiter.ui.theme.SuccessGreen
import com.wifiguard.limiter.ui.theme.TextMuted
import com.wifiguard.limiter.ui.theme.WarnAmber
import com.wifiguard.limiter.vpn.WifiGuardVpnService
import kotlinx.coroutines.launch
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
    var selectedTab by remember { mutableIntStateOf(0) }

    val pairingCode by prefs.pairingCodeFlow.collectAsState(initial = "")
    val devices by prefs.managedDevicesFlow.collectAsState(initial = emptyList())
    val alerts by prefs.tamperAlertsFlow.collectAsState(initial = emptyList())
    val supervisorPin by prefs.supervisorPinFlow.collectAsState(initial = "")
    val globalSpeedCapMbps by prefs.globalSpeedCapMbpsFlow.collectAsState(initial = 0.0)
    val forceQuotaSaverAll by prefs.forceQuotaSaverAllFlow.collectAsState(initial = false)
    val targetNetworkOnly by prefs.targetNetworkOnlyFlow.collectAsState(initial = false)
    val targetNetworkSig by prefs.targetNetworkSigFlow.collectAsState(initial = "")
    val blockedAds by WifiGuardVpnService.blockedAdsCount.collectAsState()

    // Self-limit & template policy states
    val selfLimitEnabled by prefs.selfLimitEnabledFlow.collectAsState(initial = false)
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

    LaunchedEffect(Unit) {
        prefs.getOrCreatePairingCode()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (selectedTab) {
                            0 -> "حارس الواي فاي — المشرف"
                            1 -> "حدي الشخصي"
                            else -> "الإعدادات المتقدمة"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
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
                    localIp = SupervisorServerService.getLocalWifiIp(context),
                    devices = devices,
                    alerts = alerts,
                    globalSpeedCapMbps = globalSpeedCapMbps,
                    forceQuotaSaverAll = forceQuotaSaverAll,
                    onToggleForceQuotaSaverAll = { forced -> scope.launch { prefs.setForceQuotaSaverAll(forced) } },
                    onClearAlerts = { scope.launch { prefs.clearTamperAlerts() } },
                    onEditDevice = { editingDevice = it },
                    onQuickGlobalCapChange = { cap ->
                        scope.launch { prefs.setGlobalSpeedCapMbps(cap) }
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
                    adminActive = WifiGuardDeviceAdminReceiver.isActive(context),
                    onToggleSelfLimit = { enable ->
                        val action = {
                            scope.launch {
                                prefs.setSelfLimitEnabled(enable)
                                if (enable) onRequestStartVpn() else onStopVpn()
                            }
                        }
                        if (!enable && supervisorPin.isNotBlank()) {
                            pinGateAction = { action() }
                        } else {
                            action()
                        }
                    },
                    onEditLimits = {
                        if (supervisorPin.isNotBlank()) {
                            pinGateAction = { showSelfLimitDialog = true }
                        } else {
                            showSelfLimitDialog = true
                        }
                    },
                    onAddBonusQuota = { extraMb ->
                        scope.launch { prefs.addBonusQuotaMb(extraMb) }
                    },
                    onEditWhitelist = {
                        if (supervisorPin.isNotBlank()) {
                            pinGateAction = { showSelfWhitelistDialog = true }
                        } else {
                            showSelfWhitelistDialog = true
                        }
                    },
                    onOpenAlwaysOn = onOpenAlwaysOnSettings,
                    onRequestAdmin = onRequestDeviceAdmin
                )
                else -> AdvancedSettingsTab(
                    globalSpeedCapMbps = globalSpeedCapMbps,
                    policy = speedPolicy,
                    currentPin = supervisorPin,
                    deviceCount = devices.size,
                    onSaveGlobalCap = { cap -> scope.launch { prefs.setGlobalSpeedCapMbps(cap) } },
                    onSavePolicy = { newPolicy ->
                        scope.launch {
                            prefs.updateLocalLimits(dailyLimitMb, limitPeriod, newPolicy, whitelist, globalSpeedCapMbps)
                        }
                    },
                    onApplyPolicyToAll = { newPolicy ->
                        scope.launch {
                            prefs.updateLocalLimits(dailyLimitMb, limitPeriod, newPolicy, whitelist, globalSpeedCapMbps)
                            prefs.applyPolicyToAllDevices(newPolicy)
                        }
                    },
                    targetNetworkOnly = targetNetworkOnly,
                    targetNetworkSig = targetNetworkSig,
                    onSetTargetNetwork = { only, sig -> scope.launch { prefs.setTargetNetwork(only, sig) } },
                    onSavePin = { newPin -> scope.launch { prefs.setSupervisorPin(newPin) } },
                    onResetUsage = { scope.launch { prefs.resetUsageCounters() } },
                    onSwitchRole = {
                        scope.launch {
                            prefs.setRole(AppRole.UNSET)
                            onStopVpn()
                            SupervisorServerService.stop(context)
                            onResetRole()
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
                scope.launch { prefs.upsertManagedDevice(updated) }
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
                }
                showSelfWhitelistDialog = false
            }
        )
    }

    pinGateAction?.let { action ->
        PinCheckDialog(
            expectedPin = supervisorPin,
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
    forceQuotaSaverAll: Boolean,
    onToggleForceQuotaSaverAll: (Boolean) -> Unit,
    onClearAlerts: () -> Unit,
    onEditDevice: (ManagedDevice) -> Unit,
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
                val fmt = remember { SimpleDateFormat("hh:mm a", Locale("ar")) }
                alerts.take(5).forEach { alert ->
                    Text(
                        text = "• ${alert.message} (${fmt.format(Date(alert.timestampMillis))})",
                        fontSize = 13.sp,
                        color = AlertRed,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }

    // Force Quota Saver on ALL devices card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("فرض وضع التوفير على جميع الأجهزة", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "يُفعّل حجب الإعلانات وتجميد الخلفية وتوفير الوسائط على الكل",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
            Switch(checked = forceQuotaSaverAll, onCheckedChange = onToggleForceQuotaSaverAll)
        }
    }

    // Quick global speed cap bar for all devices
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
            DeviceMonitorCard(device = device, onClick = { onEditDevice(device) })
        }
    }
}

@Composable
private fun DeviceMonitorCard(device: ManagedDevice, onClick: () -> Unit) {
    val progress = (device.usagePercent / 100f).coerceIn(0f, 1f)
    val barColor = when {
        !device.isOnline || !device.protectionActive -> AlertRed
        device.connectionState == ConnectionState.BLOCKED -> AlertRed
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                Icon(Icons.Rounded.Edit, contentDescription = "تعديل", tint = TextMuted, modifier = Modifier.size(18.dp))
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

            val capText = formatSpeedArabic(device.speedPolicy.baseSpeedLimitMbPerSec)
            val macText = if (device.macAddress.isNotBlank()) " • MAC: ${device.macAddress}" else ""
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "السرعة: $capText • المستثناة: ${device.whitelistedPackages.size}$macText",
                fontSize = 12.sp,
                color = TextMuted
            )

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
    adminActive: Boolean,
    onToggleSelfLimit: (Boolean) -> Unit,
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
                Text("إضافة رصيد إضافي سريع (+250 م.ب لليوم)")
            }
            OutlinedButton(onClick = onOpenAlwaysOn, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text("تثبيت الحماية الدائمة من إعدادات الجهاز")
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
    currentPin: String,
    deviceCount: Int,
    onSaveGlobalCap: (Double) -> Unit,
    onSavePolicy: (SpeedPolicyConfig) -> Unit,
    onApplyPolicyToAll: (SpeedPolicyConfig) -> Unit,
    targetNetworkOnly: Boolean,
    targetNetworkSig: String,
    onSetTargetNetwork: (Boolean, String?) -> Unit,
    onSavePin: (String) -> Unit,
    onResetUsage: () -> Unit,
    onSwitchRole: () -> Unit
) {
    var draftPolicy by remember(policy) { mutableStateOf(policy) }
    var globalCapText by remember(globalSpeedCapMbps) {
        mutableStateOf(if (globalSpeedCapMbps == 0.0) "0" else globalSpeedCapMbps.toString())
    }
    val ctx = LocalContext.current
    var pinInput by remember(currentPin) { mutableStateOf(currentPin) }

    // Clean collapsible sections so everything is easy to find without clutter
    var expandSpeed by remember { mutableStateOf(true) }
    var expandPhases by remember { mutableStateOf(false) }
    var expandGrace by remember { mutableStateOf(false) }
    var expandAutomation by remember { mutableStateOf(false) }
    var expandSecurity by remember { mutableStateOf(false) }

    // 1-Tap Presets Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SoftMint)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("أوضاع جاهزة بضغطة واحدة", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = PrimaryTeal)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = false,
                    onClick = {
                        draftPolicy = SpeedPolicyConfig.presetBalanced()
                        onSavePolicy(draftPolicy)
                    },
                    label = { Text("متوازن") }
                )
                FilterChip(
                    selected = false,
                    onClick = {
                        draftPolicy = SpeedPolicyConfig.presetMaxSaver()
                        onSavePolicy(draftPolicy)
                    },
                    label = { Text("توفير أقصى") }
                )
                FilterChip(
                    selected = false,
                    onClick = {
                        draftPolicy = SpeedPolicyConfig.presetRelaxed()
                        onSavePolicy(draftPolicy)
                    },
                    label = { Text("مرن (سرعة عالية)") }
                )
            }
        }
    }

    // Section 1: Speed in MB/s (All devices & Default)
    AccordionCard(
        icon = Icons.Rounded.Speed,
        title = "1. السرعة القصوى (ميجابت/ث)",
        subtitle = "تحديد سرعة جميع الأجهزة أو هذا الجهاز بالميجابت/ثانية (Mbps)",
        expanded = expandSpeed,
        onToggle = { expandSpeed = !expandSpeed }
    ) {
        OutlinedTextField(
            value = globalCapText,
            onValueChange = { txt ->
                globalCapText = txt
                txt.toDoubleOrNull()?.let { onSaveGlobalCap(it) }
            },
            label = { Text("الحد الأقصى للسرعة لجميع الأجهزة (ميجابت/ث، 0 = بدون حد)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        var baseText by remember(draftPolicy.baseSpeedLimitMbPerSec) {
            mutableStateOf(draftPolicy.baseSpeedLimitMbPerSec.toString())
        }
        OutlinedTextField(
            value = baseText,
            onValueChange = { txt ->
                baseText = txt
                txt.toDoubleOrNull()?.let { v ->
                    draftPolicy = draftPolicy.copy(baseSpeedLimitMbPerSec = v.coerceAtLeast(0.0))
                }
            },
            label = { Text("السرعة الافتراضية قبل التهدئة (ميجابت/ث، 0 = سرعة كاملة)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Section 2: Phased Speed Rules
    AccordionCard(
        icon = Icons.Rounded.Tune,
        title = "2. مراحل التهدئة التلقائية",
        subtitle = "تبدأ عند ${draftPolicy.slowdownStartPercent}% • تنخفض إلى ${draftPolicy.finalPhaseSpeedMbPerSec} ميجابت/ث",
        expanded = expandPhases,
        onToggle = { expandPhases = !expandPhases }
    ) {
        PhasedRulesControls(policy = draftPolicy, onChange = { draftPolicy = it })
    }

    // Section 3: Extra Mile & Rollover
    AccordionCard(
        icon = Icons.Rounded.HourglassBottom,
        title = "3. الرصيد الإضافي وترحيل الباقة",
        subtitle = "+${draftPolicy.graceExtraPercent}% بسرعة ${draftPolicy.graceSpeedMbPerSec} ميجابت/ث • ترحيل ${draftPolicy.rolloverPercent}%",
        expanded = expandGrace,
        onToggle = { expandGrace = !expandGrace }
    ) {
        GraceAndRolloverControls(policy = draftPolicy, onChange = { draftPolicy = it })
    }

    // Section 4: Real Smart Automations
    AccordionCard(
        icon = Icons.Rounded.AutoAwesome,
        title = "4. التوفير الذكي التلقائي",
        subtitle = if (draftPolicy.quotaSaverModeEnabled) "مفعّل + حجب الإعلانات" else "تلقائي عند ${draftPolicy.autoEnableSaverAtPercent}%",
        expanded = expandAutomation,
        onToggle = { expandAutomation = !expandAutomation }
    ) {
        SmartAutomationControls(policy = draftPolicy, onChange = { draftPolicy = it })
    }

    // Save / Apply to All buttons
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { onSavePolicy(draftPolicy) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("حفظ الإعدادات")
        }
        if (deviceCount > 0) {
            OutlinedButton(
                onClick = { onApplyPolicyToAll(draftPolicy) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("تطبيق على الكل ($deviceCount)")
            }
        }
    }

    // Section 4.5: Target Wi-Fi Network Only
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
                Switch(
                    checked = targetNetworkOnly,
                    onCheckedChange = { enabled ->
                        val sig = if (enabled && targetNetworkSig.isBlank()) {
                            NetworkScanner.getCurrentWifiSignature(ctx)
                        } else null
                        onSetTargetNetwork(enabled, sig)
                    }
                )
            }
            OutlinedButton(
                onClick = {
                    val sig = NetworkScanner.getCurrentWifiSignature(ctx)
                    if (sig.isNotBlank()) onSetTargetNetwork(true, sig)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (targetNetworkSig.isBlank()) "تعيين شبكة الواي فاي الحالية للمراقبة"
                    else "تحديث الشبكة المحددة (الحالية: $targetNetworkSig)",
                    fontSize = 12.sp
                )
            }
        }
    }

    // Section 5: Security & Role
    AccordionCard(
        icon = Icons.Rounded.Lock,
        title = "5. الأمان وإدارة التطبيق",
        subtitle = "الرمز السري، تصفير العدادات، تبديل وضع الجهاز",
        expanded = expandSecurity,
        onToggle = { expandSecurity = !expandSecurity }
    ) {
        OutlinedTextField(
            value = pinInput,
            onValueChange = { if (it.length <= 6) pinInput = it },
            label = { Text("الرمز السري لقفل الإعدادات (اختياري)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onSavePin(pinInput) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("حفظ الرمز السري")
        }
        OutlinedButton(onClick = onResetUsage, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Text("تصفير عدادات الاستهلاك لهذا الجهاز")
        }
        OutlinedButton(
            onClick = onSwitchRole,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertRed)
        ) {
            Text("تغيير نوع الجهاز (مشرف / عضو)")
        }
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
    onChange: (SpeedPolicyConfig) -> Unit
) {
    var finalSpeedText by remember(policy.finalPhaseSpeedMbPerSec) {
        mutableStateOf(policy.finalPhaseSpeedMbPerSec.toString())
    }

    Text("تبدأ التهدئة التدريجية عند استهلاك: ${policy.slowdownStartPercent}%", fontSize = 12.sp)
    Slider(
        value = policy.slowdownStartPercent.toFloat(),
        onValueChange = { onChange(policy.copy(slowdownStartPercent = it.toInt())) },
        valueRange = 10f..90f
    )

    Text("السرعة عند بداية التهدئة: ${policy.slowdownInitialSpeedPercent}%", fontSize = 12.sp)
    Slider(
        value = policy.slowdownInitialSpeedPercent.toFloat(),
        onValueChange = { onChange(policy.copy(slowdownInitialSpeedPercent = it.toInt())) },
        valueRange = 10f..90f
    )

    Text("مقدار النقص لكل 1% استهلاك إضافي: -${policy.slowdownStepPerPercent}%", fontSize = 12.sp)
    Slider(
        value = policy.slowdownStepPerPercent.toFloat(),
        onValueChange = { onChange(policy.copy(slowdownStepPerPercent = it.toInt())) },
        valueRange = 1f..5f
    )

    Text("المرحلة الأخيرة عند تبقي: ${policy.finalPhaseRemainingPercent}% من الباقة", fontSize = 12.sp)
    Slider(
        value = policy.finalPhaseRemainingPercent.toFloat(),
        onValueChange = { onChange(policy.copy(finalPhaseRemainingPercent = it.toInt())) },
        valueRange = 5f..40f
    )

    OutlinedTextField(
        value = finalSpeedText,
        onValueChange = { txt ->
            finalSpeedText = txt
            txt.toDoubleOrNull()?.let { v ->
                if (v >= 0.1) onChange(policy.copy(finalPhaseSpeedMbPerSec = v))
            }
        },
        label = { Text("سرعة المرحلة الأخيرة (ميجابت/ث)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun GraceAndRolloverControls(
    policy: SpeedPolicyConfig,
    onChange: (SpeedPolicyConfig) -> Unit
) {
    var graceSpeedText by remember(policy.graceSpeedMbPerSec) {
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
            graceSpeedText = txt
            txt.toDoubleOrNull()?.let { v ->
                if (v >= 0.05) onChange(policy.copy(graceSpeedMbPerSec = v))
            }
        },
        label = { Text("سرعة الرصيد الإضافي (ميجابت/ث)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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

@Composable
private fun SmartAutomationControls(
    policy: SpeedPolicyConfig,
    onChange: (SpeedPolicyConfig) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("تفعيل وضع التوفير يدوياً", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("حجب الإعلانات + تقليل استهلاك الفيديو + تجميد الخلفية", fontSize = 11.sp, color = TextMuted)
        }
        Switch(
            checked = policy.quotaSaverModeEnabled,
            onCheckedChange = { onChange(policy.copy(quotaSaverModeEnabled = it)) }
        )
    }

    val autoLabel = when (policy.autoEnableSaverAtPercent) {
        0 -> "تفعيل تلقائي: من بداية الاستهلاك (0%)"
        100 -> "تفعيل تلقائي: معطّل (يدوي أو مفروض فقط)"
        else -> "تفعيل تلقائي عند استهلاك: ${policy.autoEnableSaverAtPercent}%"
    }
    Text(autoLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Slider(
        value = policy.autoEnableSaverAtPercent.toFloat(),
        onValueChange = { onChange(policy.copy(autoEnableSaverAtPercent = it.toInt())) },
        valueRange = 0f..100f
    )

    HorizontalDivider()
    Text("تخصيص مكونات وضع التوفير:", fontWeight = FontWeight.Bold, fontSize = 13.sp)

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
        Text("تخزين مؤقت ذكي للعناوين (30 دقيقة)", fontSize = 12.sp)
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = policy.blockDohDotEnabled,
            onCheckedChange = { onChange(policy.copy(blockDohDotEnabled = it)) }
        )
        Text("منع التطبيقات من تجاوز حجب الإعلانات", fontSize = 12.sp)
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
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = policy.peakHoursSaverEnabled,
            onCheckedChange = { onChange(policy.copy(peakHoursSaverEnabled = it)) }
        )
        Text("تخفيف ساعات الذروة (6م – 12ص ← ${policy.peakHoursSpeedMbPerSec} ميجابت/ث)", fontSize = 12.sp)
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = policy.nightSaverEnabled,
            onCheckedChange = { onChange(policy.copy(nightSaverEnabled = it)) }
        )
        Text("توفير ليلي (1ص – 7ص ← ${policy.nightSaverSpeedMbPerSec} ميجابت/ث)", fontSize = 12.sp)
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
    var limitMbText by remember { mutableStateOf(device.dailyLimitMb.toString()) }
    var period by remember { mutableStateOf(device.limitPeriod) }
    var policy by remember { mutableStateOf(device.speedPolicy) }
    var forceSaverForDevice by remember { mutableStateOf(device.forceQuotaSaver) }
    var baseSpeedText by remember { mutableStateOf(device.speedPolicy.baseSpeedLimitMbPerSec.toString()) }
    var whitelist by remember { mutableStateOf(device.whitelistedPackages.toSet()) }
    var showWhitelist by remember { mutableStateOf(false) }
    var showAdvancedInDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("إعدادات ${device.deviceName}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                IconButton(onClick = onDelete) {
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
                    onValueChange = { limitMbText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("أو أدخل الرقم بالميجابايت") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("فرض وضع التوفير على هذا الجهاز", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Switch(checked = forceSaverForDevice, onCheckedChange = { forceSaverForDevice = it })
                }

                OutlinedTextField(
                    value = baseSpeedText,
                    onValueChange = { txt ->
                        baseSpeedText = txt
                        txt.toDoubleOrNull()?.let { v ->
                            policy = policy.copy(baseSpeedLimitMbPerSec = v.coerceAtLeast(0.0))
                        }
                    },
                    label = { Text("السرعة القصوى لهذا الجهاز (ميجابت/ث، 0 = سرعة كاملة)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                val mb = limitMbText.toLongOrNull()?.coerceAtLeast(10L) ?: device.dailyLimitMb
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

    if (showWhitelist) {
        WhitelistPickerDialog(
            initialSelected = whitelist,
            onDismiss = { showWhitelist = false },
            onConfirm = {
                whitelist = it
                showWhitelist = false
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
    var limitMbText by remember { mutableStateOf(currentLimitMb.toString()) }
    var period by remember { mutableStateOf(currentPeriod) }
    var policy by remember { mutableStateOf(currentPolicy) }
    var baseSpeedText by remember { mutableStateOf(currentPolicy.baseSpeedLimitMbPerSec.toString()) }

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
                    onValueChange = { limitMbText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("الحد (${period.arabicLabel}) بالميجابايت") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseSpeedText,
                    onValueChange = { txt ->
                        baseSpeedText = txt
                        txt.toDoubleOrNull()?.let { v ->
                            policy = policy.copy(baseSpeedLimitMbPerSec = v.coerceAtLeast(0.0))
                        }
                    },
                    label = { Text("السرعة القصوى (ميجابت/ث، 0 = سرعة كاملة)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                PhasedRulesControls(policy = policy, onChange = { policy = it })
                GraceAndRolloverControls(policy = policy, onChange = { policy = it })
                SmartAutomationControls(policy = policy, onChange = { policy = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mb = limitMbText.toLongOrNull()?.coerceAtLeast(10L) ?: currentLimitMb
                onSave(mb, period, policy)
            }) { Text("حفظ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
private fun PinCheckDialog(
    expectedPin: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("أدخل الرمز السري", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = false
                    },
                    label = { Text("الرمز السري") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
            TextButton(onClick = {
                if (input == expectedPin) onSuccess() else error = true
            }) { Text("تأكيد") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

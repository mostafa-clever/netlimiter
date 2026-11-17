package com.wifiguard.limiter.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.wifiguard.limiter.model.ManagedDevice
import com.wifiguard.limiter.model.TamperAlert
import com.wifiguard.limiter.model.formatDataSizeArabic
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

    // Self-limit states
    val selfLimitEnabled by prefs.selfLimitEnabledFlow.collectAsState(initial = false)
    val usedMb by prefs.usedMbTodayFlow.collectAsState(initial = 0L)
    val dailyLimitMb by prefs.dailyLimitMbFlow.collectAsState(initial = 1024L)
    val slowdownThreshold by prefs.slowdownThresholdFlow.collectAsState(initial = 80)
    val whitelist by prefs.whitelistFlow.collectAsState(initial = emptySet())
    val vpnRunning by WifiGuardVpnService.isRunning.collectAsState()
    val connState by WifiGuardVpnService.connectionState.collectAsState()

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
                            else -> "الإعدادات"
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
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text("الإعدادات") }
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
                    onClearAlerts = { scope.launch { prefs.clearTamperAlerts() } },
                    onEditDevice = { editingDevice = it }
                )
                1 -> SelfLimitTab(
                    selfLimitEnabled = selfLimitEnabled,
                    usedMb = usedMb,
                    dailyLimitMb = dailyLimitMb,
                    slowdownThreshold = slowdownThreshold,
                    whitelistCount = whitelist.size,
                    vpnRunning = vpnRunning,
                    connState = connState,
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
                else -> SettingsTab(
                    currentPin = supervisorPin,
                    onSavePin = { newPin -> scope.launch { prefs.setSupervisorPin(newPin) } },
                    onResetToday = { scope.launch { prefs.resetTodayUsage() } },
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
            currentThreshold = slowdownThreshold,
            onDismiss = { showSelfLimitDialog = false },
            onSave = { newLimit, newThreshold ->
                scope.launch {
                    prefs.updateLocalLimits(newLimit, newThreshold, whitelist)
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
                    prefs.updateLocalLimits(dailyLimitMb, slowdownThreshold, newSet)
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

@Composable
private fun DevicesTab(
    pairingCode: String,
    localIp: String,
    devices: List<ManagedDevice>,
    alerts: List<TamperAlert>,
    onClearAlerts: () -> Unit,
    onEditDevice: (ManagedDevice) -> Unit
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

    // Pairing info card
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
        device.connectionState == ConnectionState.SLOWED -> WarnAmber
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
                    Text(device.deviceName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                        device.connectionState == ConnectionState.SLOWED -> "سرعة مهدّأة"
                        else -> "متصل بشكل طبيعي"
                    },
                    fontSize = 12.sp,
                    color = barColor,
                    fontWeight = FontWeight.Bold
                )
            }

            if (device.whitelistedPackages.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "التطبيقات المسموحة: ${device.whitelistedPackages.size}",
                    fontSize = 12.sp,
                    color = TextMuted
                )
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
    usedMb: Long,
    dailyLimitMb: Long,
    slowdownThreshold: Int,
    whitelistCount: Int,
    vpnRunning: Boolean,
    connState: ConnectionState,
    adminActive: Boolean,
    onToggleSelfLimit: (Boolean) -> Unit,
    onEditLimits: () -> Unit,
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
                Text("تفعيل الحد اليومي على جهازي", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "يساعدك على تنظيم استخدامك الشخصي للواي فاي وتهدئة السرعة قبل الانتهاء",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
            Switch(checked = selfLimitEnabled, onCheckedChange = onToggleSelfLimit)
        }
    }

    UsageRingCard(
        title = "استهلاكي اليوم",
        usedMb = usedMb,
        dailyLimitMb = dailyLimitMb,
        connectionState = connState,
        protectionActive = vpnRunning
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("إعدادات حدي الشخصي", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "الحد اليومي: ${formatDataSizeArabic(dailyLimitMb)} • تهدئة السرعة عند $slowdownThreshold%",
                fontSize = 13.sp,
                color = TextMuted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onEditLimits, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Text("تعديل الحد")
                }
                OutlinedButton(onClick = onEditWhitelist, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("المسموحة ($whitelistCount)")
                }
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

@Composable
private fun SettingsTab(
    currentPin: String,
    onSavePin: (String) -> Unit,
    onResetToday: () -> Unit,
    onSwitchRole: () -> Unit
) {
    var pinInput by remember(currentPin) { mutableStateOf(currentPin) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("قفل إعدادات المشرف", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "أضف رمزاً سرياً لمنع تغيير حدك الشخصي بسهولة:",
                fontSize = 13.sp,
                color = TextMuted
            )
            OutlinedTextField(
                value = pinInput,
                onValueChange = { if (it.length <= 6) pinInput = it },
                label = { Text("الرمز السري (اختياري)") },
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
                Text("حفظ الرمز")
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onResetToday, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text("تصفير استهلاك اليوم لهذا الجهاز")
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
    var threshold by remember { mutableIntStateOf(device.slowdownThresholdPercent) }
    var whitelist by remember { mutableStateOf(device.whitelistedPackages.toSet()) }
    var showWhitelist by remember { mutableStateOf(false) }

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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("الحد اليومي (بالميجابايت):", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(500L to "500 م.ب", 1024L to "1 ج.ب", 2048L to "2 ج.ب", 5120L to "5 ج.ب").forEach { (mb, label) ->
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
                Text("تهدئة السرعة عند الوصول إلى: $threshold%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = threshold.toFloat(),
                    onValueChange = { threshold = it.toInt() },
                    valueRange = 50f..95f
                )
                OutlinedButton(
                    onClick = { showWhitelist = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("التطبيقات المسموحة دائماً (${whitelist.size})")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mb = limitMbText.toLongOrNull()?.coerceAtLeast(10L) ?: device.dailyLimitMb
                onSave(
                    device.copy(
                        dailyLimitMb = mb,
                        slowdownThresholdPercent = threshold,
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
    currentThreshold: Int,
    onDismiss: () -> Unit,
    onSave: (Long, Int) -> Unit
) {
    var limitMbText by remember { mutableStateOf(currentLimitMb.toString()) }
    var threshold by remember { mutableIntStateOf(currentThreshold) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل حدي الشخصي", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(500L to "500 م.ب", 1024L to "1 ج.ب", 2048L to "2 ج.ب", 5120L to "5 ج.ب").forEach { (mb, label) ->
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
                    label = { Text("الحد اليومي بالميجابايت") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("تهدئة السرعة عند: $threshold%", fontSize = 13.sp)
                Slider(
                    value = threshold.toFloat(),
                    onValueChange = { threshold = it.toInt() },
                    valueRange = 50f..95f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mb = limitMbText.toLongOrNull()?.coerceAtLeast(10L) ?: currentLimitMb
                onSave(mb, threshold)
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

package com.wifiguard.limiter.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wifiguard.limiter.BuildConfig
import com.wifiguard.limiter.admin.WifiGuardDeviceAdminReceiver
import com.wifiguard.limiter.data.AppUsageStatsHelper
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.LimitPeriod
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.core.content.ContextCompat
import com.wifiguard.limiter.model.SpeedPolicyConfig
import com.wifiguard.limiter.model.formatSpeedArabic
import com.wifiguard.limiter.model.getInstalledLaunchableApps
import com.wifiguard.limiter.model.normalizeArabicDigits
import com.wifiguard.limiter.network.ClientGuardService
import com.wifiguard.limiter.network.LocalLanClient
import com.wifiguard.limiter.network.NetworkScanner
import com.wifiguard.limiter.ui.components.GuideType
import com.wifiguard.limiter.ui.components.SetupGuideDialog
import com.wifiguard.limiter.ui.components.UsageRingCard
import com.wifiguard.limiter.ui.theme.PrimaryTeal
import com.wifiguard.limiter.ui.theme.SoftMint
import com.wifiguard.limiter.ui.theme.SuccessGreen
import com.wifiguard.limiter.ui.theme.SuccessGreenBg
import com.wifiguard.limiter.ui.theme.TextMuted
import com.wifiguard.limiter.ui.theme.WarnAmber
import com.wifiguard.limiter.ui.theme.WarnAmberBg
import com.wifiguard.limiter.vpn.WifiGuardVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClientScreen(
    prefs: PreferencesManager,
    onRequestStartVpn: () -> Unit,
    onOpenAlwaysOnSettings: () -> Unit,
    onRequestDeviceAdmin: () -> Unit
) {
    val context = LocalContext.current
    val deviceName by prefs.deviceNameFlow.collectAsState(initial = "جهازي")
    val limitPeriod by prefs.limitPeriodFlow.collectAsState(initial = LimitPeriod.DAILY)
    val usedMb by prefs.usedMbActivePeriodFlow.collectAsState(initial = 0L)
    val dailyLimitMb by prefs.dailyLimitMbFlow.collectAsState(initial = 1024L)
    val whitelist by prefs.whitelistFlow.collectAsState(initial = emptySet())
    val targetNetworkOnly by prefs.targetNetworkOnlyFlow.collectAsState(initial = false)
    val targetNetworkSig by prefs.targetNetworkSigFlow.collectAsState(initial = "")
    val speedPolicy by prefs.speedPolicyFlow.collectAsState(initial = SpeedPolicyConfig())
    val globalSpeedCapMbps by prefs.globalSpeedCapMbpsFlow.collectAsState(initial = 0.0)
    val supervisorReachable by ClientGuardService.isSupervisorReachableFlow.collectAsState()
    val isUnpaired by ClientGuardService.isUnpairedFlow.collectAsState()
    var showReconnectDialog by remember { mutableStateOf(false) }
    var hasLocationPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasLocationPerm = granted
        if (granted) WifiGuardVpnService.notifyConfigChanged()
    }
    // Rely strictly on WifiGuardVpnService.isRunning so third-party VPNs cannot spoof protection status
    val vpnRunning by WifiGuardVpnService.isRunning.collectAsState()
    val connState by WifiGuardVpnService.connectionState.collectAsState()
    val statusLabel by WifiGuardVpnService.statusLabel.collectAsState()
    val blockedAds by WifiGuardVpnService.blockedAdsCount.collectAsState()
    val alwaysOnSystem by WifiGuardVpnService.isAlwaysOnActive.collectAsState()
    val alwaysOnConfirmed by prefs.alwaysOnConfirmedFlow.collectAsState(initial = false)
    val alwaysOnActive = alwaysOnSystem || alwaysOnConfirmed
    val clientScope = rememberCoroutineScope()
    var adminActive by remember { mutableStateOf(WifiGuardDeviceAdminReceiver.isActive(context)) }
    val pmPower = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var batteryExempt by remember {
        mutableStateOf(runCatching { pmPower?.isIgnoringBatteryOptimizations(context.packageName) == true }.getOrDefault(false))
    }
    var hasUsageAccess by remember { mutableStateOf(AppUsageStatsHelper.hasUsageStatsPermission(context)) }
    var activeGuide by rememberSaveable { mutableStateOf<GuideType?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                adminActive = WifiGuardDeviceAdminReceiver.isActive(context)
                batteryExempt = runCatching { pmPower?.isIgnoringBatteryOptimizations(context.packageName) == true }.getOrDefault(false)
                hasUsageAccess = AppUsageStatsHelper.hasUsageStatsPermission(context)
                hasLocationPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allowedAppNames by produceState<List<String>>(initialValue = emptyList(), key1 = whitelist) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            whitelist.mapNotNull { pkg ->
                runCatching {
                    val info = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(info).toString()
                }.getOrNull()
            }.sorted()
        }
    }

    fun restartOrRequestVpn() {
        val hasConsent = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
        if (vpnRunning && hasConsent) {
            WifiGuardVpnService.restart(context)
            Toast.makeText(context, "جاري تحديث اتصال الحماية...", Toast.LENGTH_SHORT).show()
        } else {
            onRequestStartVpn()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("No Quota", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("جهاز: $deviceName", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                    }
                },
                actions = {
                    IconButton(onClick = { restartOrRequestVpn() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "إعادة تشغيل الاتصال", tint = Color.White)
                    }
                    IconButton(onClick = { activeGuide = GuideType.CLIENT_FULL_SETUP }) {
                        Icon(Icons.Rounded.HelpOutline, contentDescription = "دليل التفعيل", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryTeal,
                    titleContentColor = Color.White
                )
            )
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
            UsageRingCard(
                title = "استهلاك الواي فاي (${limitPeriod.arabicLabel})",
                usedMb = usedMb,
                dailyLimitMb = dailyLimitMb,
                connectionState = connState,
                protectionActive = vpnRunning,
                statusLabelOverride = statusLabel
            )

            val effCap = when {
                speedPolicy.baseSpeedLimitMbPerSec > 0.0 && globalSpeedCapMbps > 0.0 -> minOf(speedPolicy.baseSpeedLimitMbPerSec, globalSpeedCapMbps)
                speedPolicy.baseSpeedLimitMbPerSec > 0.0 -> speedPolicy.baseSpeedLimitMbPerSec
                globalSpeedCapMbps > 0.0 -> globalSpeedCapMbps
                else -> 0.0
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isUnpaired -> com.wifiguard.limiter.ui.theme.AlertRedBg
                        supervisorReachable -> SuccessGreenBg.copy(alpha = 0.65f)
                        else -> WarnAmberBg.copy(alpha = 0.7f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isUnpaired -> "تم فصل هذا الجهاز من المشرف"
                                supervisorReachable -> "متصل بجهاز المشرف"
                                else -> "بانتظار الاتصال بجهاز المشرف على الواي فاي"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isUnpaired -> com.wifiguard.limiter.ui.theme.AlertRed
                                supervisorReachable -> SuccessGreen
                                else -> WarnAmber
                            }
                        )
                        Text(
                            text = "السرعة المحددة: ${formatSpeedArabic(effCap)}",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                    if (!supervisorReachable || isUnpaired) {
                        TextButton(onClick = { showReconnectDialog = true }) {
                            Text(if (isUnpaired) "إعادة الربط" else "تحديث IP", fontSize = 12.sp)
                        }
                    }
                }
            }

            if (targetNetworkOnly && targetNetworkSig.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "الشبكة المراقَبة: ${NetworkScanner.formatSignatureForDisplay(targetNetworkSig)}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryTeal
                        )
                        if (!hasLocationPerm) {
                            OutlinedButton(
                                onClick = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("منح إذن الموقع لتمييز الشبكة تلقائياً", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            if (BuildConfig.ENABLE_DNS_ADBLOCK && blockedAds > 0L) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SoftMint)
                ) {
                    Text(
                        text = "تم حجب $blockedAds طلب إعلاني وتتبّع اليوم لتوفير الباقة",
                        modifier = Modifier.padding(14.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryTeal
                    )
                }
            }

            if (!vpnRunning || !alwaysOnActive || !adminActive || !batteryExempt || !hasUsageAccess) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "إكمال تفعيل الحماية",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = { activeGuide = GuideType.CLIENT_FULL_SETUP }) {
                                Text("شرح الخطوات", fontSize = 12.sp)
                            }
                        }

                        if (!vpnRunning) {
                            ProtectionStepRow(
                                icon = Icons.Rounded.Shield,
                                title = "حماية الاتصال",
                                subtitle = "غير مفعّلة — اضغط للتفعيل",
                                isActive = false,
                                actionLabel = "تفعيل",
                                onAction = onRequestStartVpn
                            )
                        }

                        if (!alwaysOnActive) {
                            ProtectionStepRow(
                                icon = Icons.Rounded.Lock,
                                title = "تثبيت الحماية الدائمة",
                                subtitle = "اختر (No Quota) من الإعدادات وفعّل التشغيل الدائم",
                                isActive = false,
                                actionLabel = "الخطوات",
                                showCheck = false,
                                onAction = { activeGuide = GuideType.ALWAYS_ON_VPN }
                            )
                        }

                        if (!adminActive) {
                            ProtectionStepRow(
                                icon = Icons.Rounded.Security,
                                title = "حماية ضد الحذف",
                                subtitle = "غير مفعّلة — يُنصح بتفعيلها",
                                isActive = false,
                                actionLabel = "تفعيل",
                                onAction = onRequestDeviceAdmin
                            )
                        }

                        if (!hasUsageAccess) {
                            ProtectionStepRow(
                                icon = Icons.Rounded.Apps,
                                title = "إذن إحصائيات التطبيقات",
                                subtitle = "مطلوب لتطبيق حد نسبة الباقة على التطبيقات وعرض تقارير الاستهلاك",
                                isActive = false,
                                actionLabel = "تفعيل",
                                onAction = {
                                    val opened = runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }.isSuccess || runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }.isSuccess
                                    if (!opened) {
                                        Toast.makeText(context, "يرجى فتح إعدادات الهاتف وتفعيل الوصول لبيانات الاستخدام.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }

                        if (!batteryExempt) {
                            ProtectionStepRow(
                                icon = Icons.Rounded.BatteryChargingFull,
                                title = "استثناء من توفير البطارية",
                                subtitle = "يمنع النظام من إغلاق الحماية في الخلفية",
                                isActive = false,
                                actionLabel = "تفعيل",
                                onAction = {
                                    val opened = runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                        )
                                    }.isSuccess || runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }.isSuccess
                                    if (!opened) {
                                        Toast.makeText(context, "يرجى استثناء التطبيق من تحسين البطارية عبر إعدادات الهاتف.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Apps, contentDescription = null, tint = PrimaryTeal)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "التطبيقات المسموحة دائماً",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "تستمر هذه التطبيقات في العمل حتى بعد انتهاء الرصيد:",
                        fontSize = 13.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (allowedAppNames.isEmpty()) {
                        Text(
                            text = "لم يحدد المشرف تطبيقات مستثناة بعد.",
                            fontSize = 13.sp,
                            color = TextMuted
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            allowedAppNames.forEach { appName ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(appName, fontSize = 13.sp) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    activeGuide?.let { guide ->
        SetupGuideDialog(
            guideType = guide,
            onDismiss = { activeGuide = null },
            onPrimaryAction = if (guide == GuideType.ALWAYS_ON_VPN) onOpenAlwaysOnSettings else null,
            primaryActionLabel = if (guide == GuideType.ALWAYS_ON_VPN) "فتح إعدادات الحماية الآن" else null,
            onSecondaryAction = if (guide == GuideType.ALWAYS_ON_VPN) {
                { clientScope.launch { prefs.setAlwaysOnConfirmed(true) } }
            } else null,
            secondaryActionLabel = if (guide == GuideType.ALWAYS_ON_VPN) "تم التفعيل ✓" else null
        )
    }

    if (showReconnectDialog) {
        var hostInput by remember { mutableStateOf("") }
        var codeInput by remember { mutableStateOf("") }
        var reconnectBusy by remember { mutableStateOf(false) }
        var reconnectErr by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!reconnectBusy) showReconnectDialog = false },
            title = { Text("تحديث اتصال المشرف", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "أدخل عنوان IP الظاهر على جهاز المشرف ورمز الربط المكون من 6 أرقام:",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = { hostInput = normalizeArabicDigits(it); reconnectErr = null },
                        label = { Text("عنوان المشرف (مثال: 192.168.1.10)") },
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { raw ->
                            val clean = normalizeArabicDigits(raw).filter { it in '0'..'9' }
                            if (clean.length <= 6) codeInput = clean
                            reconnectErr = null
                        },
                        label = { Text("رمز الربط (6 أرقام)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (reconnectErr != null) {
                        Text(reconnectErr!!, color = com.wifiguard.limiter.ui.theme.AlertRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !reconnectBusy,
                    onClick = {
                        val cleanHost = hostInput.trim().removePrefix("http://").removePrefix("https://").substringBefore("/").trim()
                        if (cleanHost.isBlank() || codeInput.length != 6) {
                            reconnectErr = "أدخل عنوان المشرف ورمز الربط (6 أرقام)."
                            return@TextButton
                        }
                        reconnectBusy = true
                        clientScope.launch {
                            val devId = prefs.getOrCreateDeviceId()
                            val devName = prefs.getDeviceName()
                            val apps = withContext(Dispatchers.IO) { getInstalledLaunchableApps(context) }
                            val res = LocalLanClient.pairWithSupervisor(cleanHost, LocalLanClient.DEFAULT_PORT, codeInput, devId, devName, apps)
                            reconnectBusy = false
                            res.onSuccess { cfg ->
                                prefs.setSupervisorConnection(cleanHost, LocalLanClient.DEFAULT_PORT, codeInput, cfg.authToken)
                                prefs.updateLocalLimits(
                                    limitMb = cfg.dailyLimitMb,
                                    limitPeriod = cfg.limitPeriod,
                                    speedPolicy = cfg.speedPolicy,
                                    whitelist = cfg.whitelistedPackages,
                                    globalSpeedCapMbps = cfg.globalSpeedCapMbps,
                                    forceQuotaSaver = cfg.forceQuotaSaver,
                                    targetNetworkOnly = cfg.targetNetworkOnly,
                                    targetNetworkSig = cfg.targetNetworkSig,
                                    isPaused = cfg.isPaused
                                )
                                WifiGuardVpnService.notifyConfigChanged()
                                showReconnectDialog = false
                                Toast.makeText(context, "تم الربط بالمشرف بنجاح", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                reconnectErr = err.message ?: "تعذر الاتصال بجهاز المشرف"
                            }
                        }
                    }
                ) { Text(if (reconnectBusy) "جاري الاتصال..." else "اتصال") }
            },
            dismissButton = {
                TextButton(enabled = !reconnectBusy, onClick = { showReconnectDialog = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun ProtectionStepRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isActive: Boolean,
    actionLabel: String,
    showCheck: Boolean = true,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isActive && showCheck) SuccessGreenBg.copy(alpha = 0.5f) else SoftMint.copy(alpha = 0.45f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isActive && showCheck) SuccessGreenBg else WarnAmberBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isActive && showCheck) Icons.Rounded.CheckCircle else icon,
                contentDescription = null,
                tint = if (isActive && showCheck) SuccessGreen else WarnAmber
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(text = subtitle, fontSize = 12.sp, color = TextMuted, lineHeight = 17.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isActive && showCheck) {
            Text(
                text = actionLabel,
                color = SuccessGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        } else {
            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(actionLabel, fontSize = 12.sp)
            }
        }
    }
}

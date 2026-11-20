package com.wifiguard.limiter.ui.screens

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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import com.wifiguard.limiter.ui.components.GuideType
import com.wifiguard.limiter.ui.components.SetupGuideDialog
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifiguard.limiter.BuildConfig
import com.wifiguard.limiter.admin.WifiGuardDeviceAdminReceiver
import com.wifiguard.limiter.data.AppUsageStatsHelper
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.LimitPeriod
import com.wifiguard.limiter.ui.components.UsageRingCard
import com.wifiguard.limiter.ui.theme.PrimaryTeal
import com.wifiguard.limiter.ui.theme.SoftMint
import com.wifiguard.limiter.ui.theme.SuccessGreen
import com.wifiguard.limiter.ui.theme.SuccessGreenBg
import com.wifiguard.limiter.ui.theme.TextMuted
import com.wifiguard.limiter.ui.theme.WarnAmber
import com.wifiguard.limiter.ui.theme.WarnAmberBg
import com.wifiguard.limiter.network.NetworkScanner
import com.wifiguard.limiter.vpn.WifiGuardVpnService

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
    val vpnServiceRunning by WifiGuardVpnService.isRunning.collectAsState()
    var systemVpnDetected by remember { mutableStateOf(false) }
    val vpnRunning = vpnServiceRunning || systemVpnDetected
    val connState by WifiGuardVpnService.connectionState.collectAsState()
    val statusLabel by WifiGuardVpnService.statusLabel.collectAsState()
    val blockedAds by WifiGuardVpnService.blockedAdsCount.collectAsState()
    val alwaysOnSystem by WifiGuardVpnService.isAlwaysOnActive.collectAsState()
    val alwaysOnConfirmed by prefs.alwaysOnConfirmedFlow.collectAsState(initial = false)
    val alwaysOnActive = alwaysOnSystem || alwaysOnConfirmed
    val clientScope = rememberCoroutineScope()
    var adminActive by remember { mutableStateOf(WifiGuardDeviceAdminReceiver.isActive(context)) }
    val pmPower = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager }
    var batteryExempt by remember { mutableStateOf(pmPower.isIgnoringBatteryOptimizations(context.packageName)) }
    var hasUsageAccess by remember { mutableStateOf(AppUsageStatsHelper.hasUsageStatsPermission(context)) }
    var activeGuide by remember { mutableStateOf<GuideType?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                adminActive = WifiGuardDeviceAdminReceiver.isActive(context)
                batteryExempt = pmPower.isIgnoringBatteryOptimizations(context.packageName)
                hasUsageAccess = AppUsageStatsHelper.hasUsageStatsPermission(context)
                val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                systemVpnDetected = cm.allNetworks.any { net ->
                    cm.getNetworkCapabilities(net)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allowedAppNames = remember(whitelist) {
        val pm = context.packageManager
        whitelist.mapNotNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            }.getOrNull()
        }.sorted()
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
                    IconButton(onClick = {
                        if (vpnRunning) WifiGuardVpnService.restart(context) else onRequestStartVpn()
                    }) {
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

            OutlinedButton(
                onClick = {
                    if (vpnRunning) WifiGuardVpnService.restart(context) else onRequestStartVpn()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("إعادة تشغيل الحماية (تحديث الاتصال)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            if (targetNetworkOnly && targetNetworkSig.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        text = "الشبكة المراقَبة: ${NetworkScanner.formatSignatureForDisplay(targetNetworkSig)}",
                        modifier = Modifier.padding(14.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryTeal
                    )
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
                                subtitle = "اختر (حارس الواي فاي) من الإعدادات وفعّل التشغيل الدائم",
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
                                    runCatching {
                                        val i = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(i)
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
                                    runCatching {
                                        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(i)
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

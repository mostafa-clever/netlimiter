package com.wifiguard.limiter.ui.screens

import android.content.pm.PackageManager
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
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.runtime.collectAsState
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
import com.wifiguard.limiter.admin.WifiGuardDeviceAdminReceiver
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.ui.components.UsageRingCard
import com.wifiguard.limiter.ui.theme.PrimaryTeal
import com.wifiguard.limiter.ui.theme.SoftMint
import com.wifiguard.limiter.ui.theme.SuccessGreen
import com.wifiguard.limiter.ui.theme.SuccessGreenBg
import com.wifiguard.limiter.ui.theme.TextMuted
import com.wifiguard.limiter.ui.theme.WarnAmber
import com.wifiguard.limiter.ui.theme.WarnAmberBg
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
    val usedMb by prefs.usedMbTodayFlow.collectAsState(initial = 0L)
    val dailyLimitMb by prefs.dailyLimitMbFlow.collectAsState(initial = 1024L)
    val whitelist by prefs.whitelistFlow.collectAsState(initial = emptySet())
    val vpnRunning by WifiGuardVpnService.isRunning.collectAsState()
    val connState by WifiGuardVpnService.connectionState.collectAsState()
    val adminActive = WifiGuardDeviceAdminReceiver.isActive(context)

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
                        Text("حارس الواي فاي", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("جهاز: $deviceName", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
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
                title = "استهلاك الواي فاي اليوم",
                usedMb = usedMb,
                dailyLimitMb = dailyLimitMb,
                connectionState = connState,
                protectionActive = vpnRunning
            )

            // Protection status & easy setup cards
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "حالة الحماية والأمان",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    ProtectionStepRow(
                        icon = Icons.Rounded.Shield,
                        title = "حماية الاتصال اليومية",
                        subtitle = if (vpnRunning) "مفعّلة وتعمل في الخلفية" else "غير مفعّلة — اضغط للتفعيل",
                        isActive = vpnRunning,
                        actionLabel = if (vpnRunning) "مفعّل" else "تفعيل",
                        onAction = onRequestStartVpn
                    )

                    ProtectionStepRow(
                        icon = Icons.Rounded.Lock,
                        title = "تثبيت الحماية الدائمة",
                        subtitle = "اختر (حارس الواي فاي) من الإعدادات وفعّل التشغيل الدائم ومنع الاتصال بدونه",
                        isActive = false,
                        actionLabel = "فتح الإعدادات",
                        showCheck = false,
                        onAction = onOpenAlwaysOnSettings
                    )

                    ProtectionStepRow(
                        icon = Icons.Rounded.Security,
                        title = "حماية ضد الحذف",
                        subtitle = if (adminActive) "مفعّلة — لا يمكن حذف التطبيق بسهولة" else "غير مفعّلة — يُنصح بتفعيلها",
                        isActive = adminActive,
                        actionLabel = if (adminActive) "مفعّل" else "تفعيل",
                        onAction = onRequestDeviceAdmin
                    )
                }
            }

            // Whitelisted apps card
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
                        text = "تستمر هذه التطبيقات في العمل حتى بعد انتهاء رصيد الواي فاي اليومي:",
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

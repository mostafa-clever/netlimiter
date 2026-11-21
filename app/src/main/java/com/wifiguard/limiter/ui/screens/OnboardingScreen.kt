package com.wifiguard.limiter.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.model.getInstalledLaunchableApps
import com.wifiguard.limiter.model.normalizeArabicDigits
import com.wifiguard.limiter.network.LocalLanClient
import com.wifiguard.limiter.ui.theme.AlertRed
import com.wifiguard.limiter.ui.theme.PrimaryTeal
import com.wifiguard.limiter.ui.theme.SoftMint
import com.wifiguard.limiter.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingScreen(
    prefs: PreferencesManager,
    onSetupComplete: (AppRole) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var deviceName by rememberSaveable { mutableStateOf(Build.MODEL) }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var manualHost by rememberSaveable { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var pairingJob by remember { mutableStateOf<Job?>(null) }
    var showManualTip by remember { mutableStateOf(false) }
    var isSelectingSupervisor by remember { mutableStateOf(false) }
    var errorMsg by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(step) {
        showManualTip = false
        if (step == 1) {
            delay(6_000L)
            showManualTip = true
        }
    }

    BackHandler(enabled = step == 1) {
        if (isConnecting) {
            pairingJob?.cancel()
            pairingJob = null
            isConnecting = false
        } else {
            step = 0
            errorMsg = null
        }
    }

    // Memoize NSD discovery flow and only run when on the Client pairing step (prevents NSD listener leak)
    val discoveryFlow = remember(context, step) {
        if (step == 1) LocalLanClient.discoverSupervisors(context) else flowOf(emptyList())
    }
    val discovered by discoveryFlow.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(SoftMint),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Wifi,
                contentDescription = null,
                tint = PrimaryTeal,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Quota",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = PrimaryTeal
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "تنظيم ذكي لاستهلاك الواي فاي اليومي والأسبوعي والشهري",
            fontSize = 14.sp,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(28.dp))

        if (step == 0) {
            RoleOptionCard(
                icon = Icons.Rounded.AdminPanelSettings,
                title = "جهاز المشرف",
                description = "لمتابعة الأجهزة المرتبطة، تحديد الحدود (يومي/أسبوعي/شهري)، وفرض توفير الباقة — ويمكنك أيضاً تحديد حد لجهازك الشخصي.",
                enabled = !isSelectingSupervisor,
                onClick = {
                    if (isSelectingSupervisor) return@RoleOptionCard
                    isSelectingSupervisor = true
                    scope.launch {
                        prefs.setRole(AppRole.SUPERVISOR)
                        onSetupComplete(AppRole.SUPERVISOR)
                    }
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
            RoleOptionCard(
                icon = Icons.Rounded.PhoneAndroid,
                title = "جهاز عضو (مُدار)",
                description = "يرتبط بجهاز المشرف على نفس شبكة الواي فاي لتطبيق الحد وتهدئة السرعة تدريجياً.",
                enabled = !isSelectingSupervisor,
                onClick = { step = 1 }
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "ربط هذا الجهاز بالمشرف",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text("اسم الجهاز (مثال: هاتف أحمد)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { raw ->
                            val normalized = normalizeArabicDigits(raw).filter { it in '0'..'9' }
                            if (normalized.length <= 6) pairingCode = normalized
                        },
                        label = { Text("رمز الربط (6 أرقام من جهاز المشرف)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    if (discovered.isNotEmpty()) {
                        Text(
                            text = "تم العثور على جهاز المشرف تلقائياً:",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryTeal
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        discovered.forEachIndexed { idx, sup ->
                            val selected = manualHost.trim() == sup.host || (manualHost.isBlank() && idx == 0)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { manualHost = sup.host },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) SoftMint else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = "جهاز المشرف المتصل (${sup.host})",
                                    modifier = Modifier.padding(12.dp),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PrimaryTeal)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (showManualTip) {
                                    "لم يظهر المشرف تلقائياً — أدخل عنوان IP الظاهر على شاشة المشرف بالأسفل"
                                } else {
                                    "جاري البحث عن جهاز المشرف على نفس الواي فاي..."
                                },
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = normalizeArabicDigits(it) },
                        label = { Text("عنوان المشرف (اختياري إذا لم يظهر تلقائياً)") },
                        placeholder = { Text("مثال: 192.168.1.10") },
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMsg != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text = errorMsg!!, color = AlertRed, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            val rawManual = manualHost.trim()
                                .removePrefix("http://")
                                .removePrefix("https://")
                                .substringBefore("/")
                                .trim()
                            val cleanManual = when {
                                rawManual.startsWith("[") -> rawManual.substringAfter("[").substringBefore("]")
                                rawManual.count { it == ':' } == 1 -> rawManual.substringBefore(":")
                                else -> rawManual
                            }.trim()
                            val targetHost = cleanManual.ifBlank { discovered.firstOrNull()?.host ?: "" }
                            val targetPort = discovered.firstOrNull { it.host == targetHost }?.port
                                ?: LocalLanClient.DEFAULT_PORT

                            if (targetHost.isBlank() || targetHost.contains(" ")) {
                                errorMsg = "تأكد أن جهاز المشرف مفتوح على نفس شبكة الواي فاي أو أدخل عنوان IP صحيحاً."
                                return@Button
                            }
                            if (pairingCode.length != 6) {
                                errorMsg = "أدخل رمز الربط المكون من 6 أرقام."
                                return@Button
                            }
                            isConnecting = true
                            errorMsg = null
                            pairingJob = scope.launch {
                                val devId = prefs.getOrCreateDeviceId()
                                val cleanName = deviceName.trim().ifBlank { Build.MODEL }
                                val apps = withContext(Dispatchers.IO) { getInstalledLaunchableApps(context) }
                                val result = LocalLanClient.pairWithSupervisor(
                                    host = targetHost,
                                    port = targetPort,
                                    pairingCode = pairingCode,
                                    deviceId = devId,
                                    deviceName = cleanName,
                                    installedApps = apps
                                )
                                result.onSuccess { cfg ->
                                    prefs.setSupervisorConnection(targetHost, targetPort, pairingCode, cfg.authToken)
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
                                    prefs.setRole(AppRole.CLIENT, cleanName)
                                    isConnecting = false
                                    onSetupComplete(AppRole.CLIENT)
                                }.onFailure { err ->
                                    isConnecting = false
                                    errorMsg = err.message ?: "تعذر الاتصال بجهاز المشرف"
                                }
                            }
                        },
                        enabled = !isConnecting,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("ربط الجهاز والبدء", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (isConnecting) {
                                pairingJob?.cancel()
                                pairingJob = null
                                isConnecting = false
                            } else {
                                step = 0
                                errorMsg = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(if (isConnecting) "إلغاء الاتصال" else "رجوع")
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleOptionCard(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SoftMint),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(30.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = description, fontSize = 13.sp, color = TextMuted, lineHeight = 19.sp)
            }
        }
    }
}

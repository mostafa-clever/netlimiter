package com.wifiguard.limiter.ui.components

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifiguard.limiter.model.ConnectionState
import com.wifiguard.limiter.model.InstalledAppItem
import com.wifiguard.limiter.model.formatDataSizeArabic
import com.wifiguard.limiter.ui.theme.AlertRed
import com.wifiguard.limiter.ui.theme.AlertRedBg
import com.wifiguard.limiter.ui.theme.PrimaryTeal
import com.wifiguard.limiter.ui.theme.SoftMint
import com.wifiguard.limiter.ui.theme.SuccessGreen
import com.wifiguard.limiter.ui.theme.SuccessGreenBg
import com.wifiguard.limiter.ui.theme.TextMuted
import com.wifiguard.limiter.ui.theme.WarnAmber
import com.wifiguard.limiter.ui.theme.WarnAmberBg

@Composable
fun UsageRingCard(
    title: String,
    usedMb: Long,
    dailyLimitMb: Long,
    connectionState: ConnectionState,
    protectionActive: Boolean,
    statusLabelOverride: String? = null
) {
    val ratio = if (dailyLimitMb <= 0L) 1f else (usedMb.toFloat() / dailyLimitMb.toFloat()).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = ratio,
        animationSpec = tween(durationMillis = 700),
        label = "usage_ring"
    )
    val remainingMb = (dailyLimitMb - usedMb).coerceAtLeast(0L)

    val ringColor = when {
        !protectionActive -> TextMuted
        connectionState == ConnectionState.BLOCKED -> AlertRed
        connectionState == ConnectionState.SLOWED || connectionState == ConnectionState.GRACE_EXTRA -> WarnAmber
        else -> PrimaryTeal
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier.size(170.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(170.dp)) {
                    val strokeWidth = 16.dp.toPx()
                    drawArc(
                        color = Color(0xFFE5ECEE),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * animatedProgress,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatDataSizeArabic(usedMb),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ringColor
                    )
                    Text(
                        text = "من ${formatDataSizeArabic(dailyLimitMb)}",
                        fontSize = 13.sp,
                        color = TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            StatusPill(
                connectionState = connectionState,
                protectionActive = protectionActive,
                labelOverride = statusLabelOverride
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatChip(label = "المتبقي", value = formatDataSizeArabic(remainingMb))
                StatChip(label = "نسبة الاستهلاك", value = "${if (dailyLimitMb <= 0L) 100 else ((usedMb * 100) / dailyLimitMb)}%")
            }
        }
    }
}

@Composable
fun StatusPill(
    connectionState: ConnectionState,
    protectionActive: Boolean,
    labelOverride: String? = null
) {
    val (bg, fg, icon, defaultText) = when {
        !protectionActive -> Quad(AlertRedBg, AlertRed, Icons.Rounded.Warning, "الحماية متوقفة حالياً")
        connectionState == ConnectionState.BLOCKED -> Quad(
            AlertRedBg,
            AlertRed,
            Icons.Rounded.WifiOff,
            "انتهى الرصيد بالكامل — التطبيقات المسموحة فقط تعمل"
        )
        connectionState == ConnectionState.IDLE_FROZEN -> Quad(
            SoftMint,
            PrimaryTeal,
            Icons.Rounded.Bedtime,
            "تجميد الخلفية لتوفير الباقة"
        )
        connectionState == ConnectionState.GRACE_EXTRA -> Quad(
            WarnAmberBg,
            WarnAmber,
            Icons.Rounded.HourglassBottom,
            "رصيد إضافي • سرعة 0.25 م.ب/ث"
        )
        connectionState == ConnectionState.SLOWED -> Quad(
            WarnAmberBg,
            WarnAmber,
            Icons.Rounded.Speed,
            "تهدئة تدريجية للسرعة"
        )
        connectionState == ConnectionState.SMART_SAVING -> Quad(
            SoftMint,
            PrimaryTeal,
            Icons.Rounded.Eco,
            "التوفير الذكي نشط"
        )
        else -> Quad(
            SuccessGreenBg,
            SuccessGreen,
            Icons.Rounded.CheckCircle,
            "متصل بسرعة كاملة"
        )
    }

    val text = if (!protectionActive) defaultText else (labelOverride ?: defaultText)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 12.sp, color = TextMuted)
        Text(text = value, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun WhitelistPickerDialog(
    initialSelected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    val installedApps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.packageName != context.packageName }
            .map {
                InstalledAppItem(
                    packageName = it.packageName,
                    appName = pm.getApplicationLabel(it).toString()
                )
            }
            .sortedBy { it.appName }
    }

    var searchQuery by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialSelected) }

    val filtered = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter { it.appName.contains(searchQuery, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("التطبيقات المسموحة دائماً", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "هذه التطبيقات ستستمر بالعمل حتى بعد انتهاء الرصيد:",
                    fontSize = 13.sp,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("ابحث عن تطبيق...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        val isChecked = selected.contains(app.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (isChecked) selected - app.packageName else selected + app.packageName
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + app.packageName else selected - app.packageName
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = app.appName, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text("حفظ (${selected.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

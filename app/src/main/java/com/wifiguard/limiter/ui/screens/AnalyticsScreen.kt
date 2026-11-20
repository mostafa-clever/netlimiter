package com.wifiguard.limiter.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import android.app.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifiguard.limiter.model.ManagedDevice
import com.wifiguard.limiter.model.formatDataSizeArabic
import com.wifiguard.limiter.ui.theme.PrimaryTeal
import com.wifiguard.limiter.ui.theme.SoftMint
import com.wifiguard.limiter.ui.theme.SuccessGreen
import com.wifiguard.limiter.ui.theme.SuccessGreenBg
import com.wifiguard.limiter.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class AnalyticsRange(val arabicLabel: String) {
    TODAY("اليوم"),
    LAST_7_DAYS("آخر 7 أيام"),
    LAST_30_DAYS("آخر 30 يوماً"),
    FULL_MONTH("شهر كامل"),
    CUSTOM("نطاق مخصص")
}

data class DailyMetric(
    val index: Int,
    val dateKey: String,
    val usedMb: Long,
    val savedMb: Long
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalyticsSection(
    devices: List<ManagedDevice>,
    localUsageHistory: Map<String, Long>,
    localSavedHistory: Map<String, Long>,
    localAppUsageMb: Map<String, Long> = emptyMap(),
    includeSelf: Boolean
) {
    val context = LocalContext.current
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    var selectedRange by remember { mutableStateOf(AnalyticsRange.LAST_7_DAYS) }
    var monthOffset by remember { mutableIntStateOf(0) }
    var customStartMillis by remember {
        mutableStateOf(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -6) }.timeInMillis)
    }
    var customEndMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    val dayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    // Build date keys for the chosen range
    val dateKeys: List<String> = remember(selectedRange, monthOffset, customStartMillis, customEndMillis) {
        when (selectedRange) {
            AnalyticsRange.TODAY -> listOf(dayFormat.format(Calendar.getInstance().time))
            AnalyticsRange.LAST_7_DAYS -> {
                (6 downTo 0).map { offset ->
                    val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -offset) }
                    dayFormat.format(c.time)
                }
            }
            AnalyticsRange.LAST_30_DAYS -> {
                (29 downTo 0).map { offset ->
                    val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -offset) }
                    dayFormat.format(c.time)
                }
            }
            AnalyticsRange.FULL_MONTH -> {
                val c = Calendar.getInstance().apply { add(Calendar.MONTH, monthOffset) }
                val monthKey = SimpleDateFormat("yyyy-MM", Locale.US).format(c.time)
                val maxDay = c.getActualMaximum(Calendar.DAY_OF_MONTH)
                (1..maxDay).map { day -> String.format(Locale.US, "%s-%02d", monthKey, day) }
            }
            AnalyticsRange.CUSTOM -> {
                val startMs = minOf(customStartMillis, customEndMillis)
                val endMs = maxOf(customStartMillis, customEndMillis)
                val cur = Calendar.getInstance().apply {
                    timeInMillis = startMs
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val endCal = Calendar.getInstance().apply {
                    timeInMillis = endMs
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
                }
                val list = mutableListOf<String>()
                var guard = 0
                while (!cur.after(endCal) && guard < 120) {
                    list.add(dayFormat.format(cur.time))
                    cur.add(Calendar.DAY_OF_YEAR, 1)
                    guard++
                }
                if (list.isEmpty()) listOf(dayFormat.format(Calendar.getInstance().time)) else list
            }
        }
    }

    val rangeTitle = remember(selectedRange, monthOffset, dateKeys) {
        if (selectedRange == AnalyticsRange.FULL_MONTH) {
            val c = Calendar.getInstance().apply { add(Calendar.MONTH, monthOffset) }
            SimpleDateFormat("MMMM yyyy", Locale("ar")).format(c.time)
        } else if (dateKeys.size == 1) {
            dateKeys.first()
        } else {
            "${dateKeys.first()}  ←  ${dateKeys.last()}"
        }
    }

    var selectedIdx by remember(dateKeys) { mutableIntStateOf((dateKeys.size - 1).coerceAtLeast(0)) }

    val metrics: List<DailyMetric> = remember(selectedDeviceId, dateKeys, devices, localUsageHistory, localSavedHistory, includeSelf) {
        dateKeys.mapIndexed { idx, dayStr ->
            var used = 0L
            var saved = 0L
            if (selectedDeviceId == null) {
                devices.forEach { d ->
                    used += d.dailyUsageHistory[dayStr] ?: 0L
                    saved += d.dailySavedHistory[dayStr] ?: 0L
                }
                if (includeSelf) {
                    used += localUsageHistory[dayStr] ?: 0L
                    saved += localSavedHistory[dayStr] ?: 0L
                }
            } else {
                val dev = devices.firstOrNull { it.deviceId == selectedDeviceId }
                used = dev?.dailyUsageHistory?.get(dayStr) ?: 0L
                saved = dev?.dailySavedHistory?.get(dayStr) ?: 0L
            }
            DailyMetric(index = idx, dateKey = dayStr, usedMb = used, savedMb = saved)
        }
    }

    // Aggregate per-app usage breakdown
    val appBreakdown: List<Pair<String, Long>> = remember(selectedDeviceId, devices, localAppUsageMb, includeSelf) {
        val merged = mutableMapOf<String, Long>()
        val nameLookup = mutableMapOf<String, String>()
        devices.forEach { d ->
            d.installedApps.forEach { a -> nameLookup[a.packageName] = a.appName }
        }
        if (selectedDeviceId == null) {
            devices.forEach { d ->
                d.appUsageMb.forEach { (pkg, mb) ->
                    if (mb > 0L) merged[pkg] = (merged[pkg] ?: 0L) + mb
                }
            }
            if (includeSelf) {
                localAppUsageMb.forEach { (pkg, mb) ->
                    if (mb > 0L) merged[pkg] = (merged[pkg] ?: 0L) + mb
                }
            }
        } else {
            val dev = devices.firstOrNull { it.deviceId == selectedDeviceId }
            dev?.appUsageMb?.forEach { (pkg, mb) ->
                if (mb > 0L) merged[pkg] = mb
            }
        }
        val pm = context.packageManager
        merged.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (pkg, mb) ->
                val label = nameLookup[pkg] ?: runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg.substringAfterLast('.'))
                label to mb
            }
    }

    val totalUsedRange = metrics.sumOf { it.usedMb }
    val totalSavedRange = metrics.sumOf { it.savedMb }
    val savingsPercent = if (totalUsedRange + totalSavedRange > 0L) {
        ((totalSavedRange * 100) / (totalUsedRange + totalSavedRange)).toInt()
    } else 0

    val selectedMetric = metrics.getOrNull(selectedIdx) ?: metrics.last()

    // Scope Chips: Entire Network vs Each Client
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Insights, contentDescription = null, tint = PrimaryTeal)
                Spacer(modifier = Modifier.width(8.dp))
                Text("نطاق التحليلات", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedDeviceId == null,
                    onClick = { selectedDeviceId = null },
                    label = { Text("الشبكة بالكامل") }
                )
                devices.forEach { dev ->
                    FilterChip(
                        selected = selectedDeviceId == dev.deviceId,
                        onClick = { selectedDeviceId = dev.deviceId },
                        label = { Text(dev.deviceName) }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DateRange, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("الفترة الزمنية", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnalyticsRange.entries.forEach { range ->
                    FilterChip(
                        selected = selectedRange == range,
                        onClick = { selectedRange = range },
                        label = { Text(range.arabicLabel, fontSize = 12.sp) }
                    )
                }
            }
            if (selectedRange == AnalyticsRange.CUSTOM) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val c = Calendar.getInstance().apply { timeInMillis = customStartMillis }
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val picked = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0) }
                                    customStartMillis = picked.timeInMillis
                                },
                                c.get(Calendar.YEAR),
                                c.get(Calendar.MONTH),
                                c.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("من: ${dayFormat.format(java.util.Date(customStartMillis))}", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val c = Calendar.getInstance().apply { timeInMillis = customEndMillis }
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val picked = Calendar.getInstance().apply { set(y, m, d, 23, 59, 59) }
                                    customEndMillis = picked.timeInMillis
                                },
                                c.get(Calendar.YEAR),
                                c.get(Calendar.MONTH),
                                c.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("إلى: ${dayFormat.format(java.util.Date(customEndMillis))}", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // KPI Summary Cards
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("إجمالي الاستهلاك (${selectedRange.arabicLabel})", fontSize = 11.sp, color = TextMuted)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatDataSizeArabic(totalUsedRange),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PrimaryTeal
                )
            }
        }

        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SuccessGreenBg)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Eco, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("التوفير المقدر", fontSize = 12.sp, color = SuccessGreen, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${formatDataSizeArabic(totalSavedRange)} ($savingsPercent%)",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = SuccessGreen
                )
            }
        }
    }

    // Bar Chart Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedRange == AnalyticsRange.FULL_MONTH) {
                    IconButton(onClick = { monthOffset-- }) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = "الشهر السابق")
                    }
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(rangeTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (selectedRange == AnalyticsRange.FULL_MONTH) {
                    IconButton(onClick = { if (monthOffset < 0) monthOffset++ }, enabled = monthOffset < 0) {
                        Icon(Icons.Rounded.ChevronLeft, contentDescription = "الشهر التالي")
                    }
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val maxVal = (metrics.maxOfOrNull { it.usedMb + it.savedMb } ?: 100L).coerceAtLeast(10L)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .pointerInput(metrics) {
                        detectTapGestures { offset ->
                            val barSlot = size.width.toFloat() / metrics.size.toFloat()
                            val idx = (offset.x / barSlot).toInt().coerceIn(0, metrics.lastIndex)
                            selectedIdx = idx
                        }
                    }
            ) {
                val slotW = size.width / metrics.size.toFloat()
                val barW = (slotW * 0.58f).coerceIn(6f, 44f)
                val chartH = size.height - 10f

                metrics.forEachIndexed { idx, item ->
                    val x = idx * slotW + (slotW - barW) / 2f
                    val usedH = (item.usedMb.toFloat() / maxVal.toFloat()) * chartH
                    val savedH = (item.savedMb.toFloat() / maxVal.toFloat()) * chartH
                    val isSel = idx == selectedIdx

                    if (isSel) {
                        drawRoundRect(
                            color = SoftMint,
                            topLeft = Offset(idx * slotW, 0f),
                            size = Size(slotW, size.height),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                    }

                    if (usedH > 0f) {
                        drawRoundRect(
                            color = PrimaryTeal,
                            topLeft = Offset(x, chartH - usedH),
                            size = Size(barW, usedH),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                    }

                    if (savedH > 0f) {
                        drawRoundRect(
                            color = SuccessGreen,
                            topLeft = Offset(x, (chartH - usedH - savedH).coerceAtLeast(0f)),
                            size = Size(barW, savedH),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(PrimaryTeal))
                Spacer(modifier = Modifier.width(6.dp))
                Text("الاستهلاك الفعلي", fontSize = 12.sp, color = TextMuted)
                Spacer(modifier = Modifier.width(16.dp))
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(SuccessGreen))
                Spacer(modifier = Modifier.width(6.dp))
                Text("البيانات الموفّرة", fontSize = 12.sp, color = TextMuted)
            }
        }
    }

    // Selected Day Detail Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SoftMint)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "تفاصيل يوم ${selectedMetric.dateKey} (اضغط أي عمود للتبديل)",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = PrimaryTeal
            )
            Text(
                text = "الاستهلاك الفعلي: ${formatDataSizeArabic(selectedMetric.usedMb)}  •  البيانات الموفّرة: ${formatDataSizeArabic(selectedMetric.savedMb)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    // Per-App Usage Breakdown Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Apps, contentDescription = null, tint = PrimaryTeal)
                Spacer(modifier = Modifier.width(8.dp))
                Text("توزيع الاستهلاك حسب التطبيقات", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            if (appBreakdown.isEmpty()) {
                Text(
                    "ستظهر تفاصيل استهلاك كل تطبيق هنا تلقائياً مع بدء الاستخدام.",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            } else {
                val totalAppMb = appBreakdown.sumOf { it.second }.coerceAtLeast(1L)
                appBreakdown.forEach { (appName, mb) ->
                    val pct = ((mb * 100) / totalAppMb).toInt().coerceIn(1, 100)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(appName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${formatDataSizeArabic(mb)} ($pct%)",
                                fontSize = 12.sp,
                                color = PrimaryTeal,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (mb.toFloat() / totalAppMb.toFloat()).coerceIn(0.02f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = PrimaryTeal,
                            trackColor = Color(0xFFE5ECEE)
                        )
                    }
                }
            }
        }
    }
}

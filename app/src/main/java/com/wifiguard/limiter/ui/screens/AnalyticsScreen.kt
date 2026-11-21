package com.wifiguard.limiter.ui.screens

import android.app.DatePickerDialog
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
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
    includeSelf: Boolean,
    selfUsedMbToday: Long = 0L
) {
    val context = LocalContext.current
    var selectedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRange by rememberSaveable { mutableStateOf(AnalyticsRange.LAST_7_DAYS) }
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    var customStartMillis by rememberSaveable {
        mutableLongStateOf(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -6) }.timeInMillis)
    }
    var customEndMillis by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var activeDateDialog by remember { mutableStateOf<DatePickerDialog?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { activeDateDialog?.dismiss() }
            activeDateDialog = null
        }
    }

    LaunchedEffect(devices, includeSelf) {
        if (selectedDeviceId != null && selectedDeviceId != "__SELF__" && devices.none { it.deviceId == selectedDeviceId }) {
            selectedDeviceId = null
        }
        if (selectedDeviceId == "__SELF__" && !includeSelf) {
            selectedDeviceId = null
        }
    }

    val dayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val todayKey = dayFormat.format(Date())

    val dateKeys: List<String> = remember(selectedRange, monthOffset, customStartMillis, customEndMillis, todayKey) {
        when (selectedRange) {
            AnalyticsRange.TODAY -> listOf(todayKey)
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
                if (list.isEmpty()) listOf(todayKey) else list
            }
        }
    }

    val rangeTitle = remember(selectedRange, monthOffset, dateKeys) {
        if (selectedRange == AnalyticsRange.FULL_MONTH) {
            val c = Calendar.getInstance().apply { add(Calendar.MONTH, monthOffset) }
            SimpleDateFormat("MMMM yyyy", Locale("ar")).format(c.time)
        } else if (dateKeys.size <= 1) {
            dateKeys.firstOrNull() ?: todayKey
        } else {
            "${dateKeys.first()}  ←  ${dateKeys.last()}"
        }
    }

    var selectedIdx by remember(dateKeys, todayKey) {
        val todayIdx = dateKeys.indexOf(todayKey)
        mutableIntStateOf(if (todayIdx >= 0) todayIdx else (dateKeys.size - 1).coerceAtLeast(0))
    }

    val metrics: List<DailyMetric> = remember(selectedDeviceId, dateKeys, devices, localUsageHistory, localSavedHistory, includeSelf, selfUsedMbToday, todayKey) {
        dateKeys.mapIndexed { idx, dayStr ->
            var used = 0L
            var saved = 0L
            when (selectedDeviceId) {
                null -> {
                    devices.forEach { d ->
                        val histUsed = d.dailyUsageHistory[dayStr] ?: 0L
                        val liveDay = if (dayStr == todayKey && d.limitPeriod == com.wifiguard.limiter.model.LimitPeriod.DAILY) d.usedMb else 0L
                        used += maxOf(histUsed, liveDay)
                        saved += maxOf(d.dailySavedHistory[dayStr] ?: 0L, if (dayStr == todayKey) d.savedMbToday else 0L)
                    }
                    if (includeSelf) {
                        val selfHist = localUsageHistory[dayStr] ?: 0L
                        used += if (dayStr == todayKey) maxOf(selfHist, selfUsedMbToday) else selfHist
                        saved += localSavedHistory[dayStr] ?: 0L
                    }
                }
                "__SELF__" -> {
                    val selfHist = localUsageHistory[dayStr] ?: 0L
                    used = if (dayStr == todayKey) maxOf(selfHist, selfUsedMbToday) else selfHist
                    saved = localSavedHistory[dayStr] ?: 0L
                }
                else -> {
                    val dev = devices.firstOrNull { it.deviceId == selectedDeviceId }
                    val histUsed = dev?.dailyUsageHistory?.get(dayStr) ?: 0L
                    val liveDay = if (dev != null && dayStr == todayKey && dev.limitPeriod == com.wifiguard.limiter.model.LimitPeriod.DAILY) dev.usedMb else 0L
                    used = maxOf(histUsed, liveDay)
                    saved = maxOf(dev?.dailySavedHistory?.get(dayStr) ?: 0L, if (dev != null && dayStr == todayKey) dev.savedMbToday else 0L)
                }
            }
            DailyMetric(index = idx, dateKey = dayStr, usedMb = used.coerceAtLeast(0L), savedMb = saved.coerceAtLeast(0L))
        }
    }

    // Resolve per-app usage labels asynchronously off the Main thread
    val appBreakdown by produceState<List<Pair<String, Long>>>(
        initialValue = emptyList(),
        selectedDeviceId, devices, localAppUsageMb, includeSelf
    ) {
        value = withContext(Dispatchers.IO) {
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
            } else if (selectedDeviceId == "__SELF__") {
                localAppUsageMb.forEach { (pkg, mb) ->
                    if (mb > 0L) merged[pkg] = mb
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
    }

    val totalUsedRange = metrics.sumOf { it.usedMb }
    val totalSavedRange = metrics.sumOf { it.savedMb }
    val savingsPercent = if (totalUsedRange + totalSavedRange > 0L) {
        ((totalSavedRange * 100.0) / (totalUsedRange + totalSavedRange).toDouble()).coerceIn(0.0, 100.0).toInt()
    } else 0

    val selectedMetric = metrics.getOrNull(selectedIdx) ?: metrics.lastOrNull()

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
                if (includeSelf) {
                    FilterChip(
                        selected = selectedDeviceId == "__SELF__",
                        onClick = { selectedDeviceId = "__SELF__" },
                        label = { Text("جهازي (المشرف)") }
                    )
                }
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
                            runCatching { activeDateDialog?.dismiss() }
                            val dlg = DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val picked = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0) }.timeInMillis
                                    customStartMillis = picked
                                    if (customStartMillis > customEndMillis) customEndMillis = picked
                                },
                                c.get(Calendar.YEAR),
                                c.get(Calendar.MONTH),
                                c.get(Calendar.DAY_OF_MONTH)
                            ).apply {
                                datePicker.maxDate = System.currentTimeMillis()
                            }
                            activeDateDialog = dlg
                            runCatching { dlg.show() }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("من: ${dayFormat.format(Date(customStartMillis))}", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val c = Calendar.getInstance().apply { timeInMillis = customEndMillis }
                            runCatching { activeDateDialog?.dismiss() }
                            val dlg = DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val picked = Calendar.getInstance().apply { set(y, m, d, 23, 59, 59) }.timeInMillis
                                    customEndMillis = picked
                                    if (customEndMillis < customStartMillis) customStartMillis = picked
                                },
                                c.get(Calendar.YEAR),
                                c.get(Calendar.MONTH),
                                c.get(Calendar.DAY_OF_MONTH)
                            ).apply {
                                datePicker.maxDate = System.currentTimeMillis()
                            }
                            activeDateDialog = dlg
                            runCatching { dlg.show() }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("إلى: ${dayFormat.format(Date(customEndMillis))}", fontSize = 12.sp)
                    }
                }
            }
        }
    }

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

            val hasAnyData = metrics.any { it.usedMb > 0L || it.savedMb > 0L }
            val maxVal = (metrics.maxOfOrNull { it.usedMb + it.savedMb } ?: 100L).coerceAtLeast(10L)
            if (!hasAnyData) {
                Text(
                    "لا توجد بيانات استهلاك مسجلة لهذه الفترة حتى الآن.",
                    fontSize = 12.sp,
                    color = TextMuted,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .pointerInput(metrics) {
                        detectTapGestures { offset ->
                            if (metrics.isEmpty() || size.width <= 0) return@detectTapGestures
                            val barSlot = size.width.toFloat() / metrics.size.toFloat()
                            if (barSlot <= 0f) return@detectTapGestures
                            val visualIdx = (offset.x / barSlot).toInt().coerceIn(0, metrics.lastIndex)
                            // Match RTL plotting order (right = oldest, left = newest)
                            selectedIdx = (metrics.lastIndex - visualIdx).coerceIn(0, metrics.lastIndex)
                        }
                    }
            ) {
                if (metrics.isEmpty() || size.width <= 0f || size.height <= 12f) return@Canvas
                val slotW = size.width / metrics.size.toFloat()
                val barW = (slotW * 0.58f).coerceIn(1f, minOf(44f, slotW))
                val chartH = size.height - 10f

                metrics.forEachIndexed { idx, item ->
                    val visualIdx = metrics.lastIndex - idx
                    val x = visualIdx * slotW + (slotW - barW) / 2f
                    val usedH = ((item.usedMb.toFloat() / maxVal.toFloat()) * chartH).coerceIn(0f, chartH)
                    val savedH = ((item.savedMb.toFloat() / maxVal.toFloat()) * chartH).coerceIn(0f, chartH - usedH)
                    val isSel = idx == selectedIdx

                    if (isSel) {
                        drawRoundRect(
                            color = SoftMint,
                            topLeft = Offset(visualIdx * slotW, 0f),
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

    if (selectedMetric != null) {
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
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Apps, contentDescription = null, tint = PrimaryTeal)
                Spacer(modifier = Modifier.width(8.dp))
                Text("توزيع الاستهلاك حسب التطبيقات (الفترة الحالية)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                    val pct = ((mb * 100.0) / totalAppMb.toDouble()).toInt().coerceIn(1, 100)
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

package com.wifiguard.limiter.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

data class DailyMetric(
    val dayOfMonth: Int,
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
    includeSelf: Boolean
) {
    // null = Entire Network, otherwise specific deviceId
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    var monthOffset by remember { mutableIntStateOf(0) }

    val calendar = remember(monthOffset) {
        Calendar.getInstance().apply { add(Calendar.MONTH, monthOffset) }
    }
    val monthKey = remember(monthOffset) {
        SimpleDateFormat("yyyy-MM", Locale.US).format(calendar.time)
    }
    val monthTitle = remember(monthOffset) {
        SimpleDateFormat("MMMM yyyy", Locale("ar")).format(calendar.time)
    }
    val daysInMonth = remember(monthOffset) {
        calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
    val todayDay = remember(monthOffset) {
        if (monthOffset == 0) Calendar.getInstance().get(Calendar.DAY_OF_MONTH) else 1
    }
    var selectedDay by remember(monthOffset) { mutableIntStateOf(todayDay) }

    // Aggregate metrics for selected scope (Entire Network or Specific Client)
    val metrics: List<DailyMetric> = remember(selectedDeviceId, monthKey, daysInMonth, devices, localUsageHistory, localSavedHistory, includeSelf) {
        (1..daysInMonth).map { day ->
            val dayStr = String.format(Locale.US, "%s-%02d", monthKey, day)
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
            DailyMetric(dayOfMonth = day, dateKey = dayStr, usedMb = used, savedMb = saved)
        }
    }

    val totalUsedMonth = metrics.sumOf { it.usedMb }
    val totalSavedMonth = metrics.sumOf { it.savedMb }
    val savingsPercent = if (totalUsedMonth + totalSavedMonth > 0L) {
        ((totalSavedMonth * 100) / (totalUsedMonth + totalSavedMonth)).toInt()
    } else 0

    val selectedMetric = metrics.firstOrNull { it.dayOfMonth == selectedDay } ?: metrics.first()

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
        }
    }

    // Monthly KPI Summary Cards
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
                Text("الاستهلاك الشهري", fontSize = 12.sp, color = TextMuted)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatDataSizeArabic(totalUsedMonth),
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
                    text = "${formatDataSizeArabic(totalSavedMonth)} ($savingsPercent%)",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = SuccessGreen
                )
            }
        }
    }

    // Month Navigator & Bar Chart
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
                IconButton(onClick = { monthOffset-- }) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "الشهر السابق")
                }
                Text(monthTitle, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                IconButton(onClick = { if (monthOffset < 0) monthOffset++ }, enabled = monthOffset < 0) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "الشهر التالي")
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
                            selectedDay = metrics[idx].dayOfMonth
                        }
                    }
            ) {
                val slotW = size.width / metrics.size.toFloat()
                val barW = (slotW * 0.62f).coerceAtLeast(4f)
                val chartH = size.height - 10f

                metrics.forEachIndexed { idx, item ->
                    val x = idx * slotW + (slotW - barW) / 2f
                    val usedH = (item.usedMb.toFloat() / maxVal.toFloat()) * chartH
                    val savedH = (item.savedMb.toFloat() / maxVal.toFloat()) * chartH
                    val isSel = item.dayOfMonth == selectedDay

                    if (isSel) {
                        drawRoundRect(
                            color = SoftMint,
                            topLeft = Offset(idx * slotW, 0f),
                            size = Size(slotW, size.height),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                    }

                    // Used bar (Teal)
                    if (usedH > 0f) {
                        drawRoundRect(
                            color = PrimaryTeal,
                            topLeft = Offset(x, chartH - usedH),
                            size = Size(barW, usedH),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                    }

                    // Saved bar stacked on top (Green)
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
            Text(
                text = "يُحسب التوفير من: حجب طلبات الإعلانات والتتبع + تجميد الخلفية عند إغلاق الشاشة + تقليل جودة البث التلقائي.",
                fontSize = 11.sp,
                color = TextMuted
            )
        }
    }
}

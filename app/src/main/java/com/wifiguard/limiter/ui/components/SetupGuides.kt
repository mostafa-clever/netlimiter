package com.wifiguard.limiter.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifiguard.limiter.ui.theme.PrimaryTeal
import com.wifiguard.limiter.ui.theme.SoftMint
import com.wifiguard.limiter.ui.theme.TextMuted

enum class GuideType {
    CLIENT_FULL_SETUP,
    ALWAYS_ON_VPN,
    SUPERVISOR_GUIDE
}

@Composable
fun SetupGuideDialog(
    guideType: GuideType,
    onDismiss: () -> Unit,
    onPrimaryAction: (() -> Unit)? = null,
    primaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null
) {
    val title = when (guideType) {
        GuideType.CLIENT_FULL_SETUP -> "دليل التفعيل خطوة بخطوة"
        GuideType.ALWAYS_ON_VPN -> "تثبيت الحماية الدائمة"
        GuideType.SUPERVISOR_GUIDE -> "دليل استخدام المشرف"
    }

    val steps = when (guideType) {
        GuideType.ALWAYS_ON_VPN -> listOf(
            "اضغط على زر (${primaryActionLabel ?: "فتح إعدادات الحماية الآن"}) بالأسفل للانتقال مباشرة إلى صفحة الحماية في هاتفك.",
            "ابحث عن تطبيق (No Quota) واضغط على علامة الترس (الإعدادات) بجانبه.",
            "فعّل خيار (التشغيل الدائم / Always-on) فقط — واترك (حظر الاتصالات بدون VPN) مغلقاً حتى تعمل التطبيقات المستثناة والمزامنة المحلية بشكل سليم.",
            "ارجع للتطبيق واضغط (تم التفعيل ✓) إذا لم تختفِ هذه الخطوة تلقائياً على جهازك."
        )
        GuideType.CLIENT_FULL_SETUP -> listOf(
            "تفعيل الحماية اليومية: اضغط (تفعيل) ثم وافق على رسالة النظام لبدء تنظيم الواي فاي.",
            "تثبيت الحماية الدائمة: افتح الإعدادات ← اضغط الترس بجانب No Quota ← فعّل (التشغيل الدائم / Always-on) فقط (اترك حظر الاتصالات بدون VPN مغلقاً).",
            "الحماية ضد الحذف: اضغط (تفعيل) ثم (تفعيل تطبيق مسؤول الجهاز) لمنع إزالة التطبيق بسهولة.",
            "إذن إحصائيات التطبيقات: اضغط (تفعيل) وامنح صلاحية الوصول لاستخدام التطبيقات لحساب استهلاك كل تطبيق بدقة.",
            "استثناء البطارية: اضغط (تفعيل) ثم (سماح) حتى لا يغلق النظام التطبيق أثناء إغلاق الشاشة.",
            "توفير إضافي لأندرويد 9 وأقل: افتح إعدادات الواي فاي ← اضغط على شبكتك ← متقدم ← فعّل (شبكة محدودة / Metered) لتقليل استهلاك التطبيقات مثل بيانات الهاتف."
        )
        GuideType.SUPERVISOR_GUIDE -> listOf(
            "ربط جهاز جديد: ثبّت No Quota على جهاز العضو، اختر (جهاز عضو)، وأدخل رمز الربط المكون من 6 أرقام الظاهر في شاشتك.",
            "تعديل الحدود والسرعة: اضغط على أي جهاز لتحديد حد (يومي/أسبوعي/شهري)، السرعة القصوى (ميجابت/ث)، ونسبة الباقة لتطبيقات معينة.",
            "الإيقاف المؤقت السريع: استخدم زر الإيقاف (⏸) بجانب اسم أي جهاز لفصل الإنترنت عنه فوراً مع إبقاء التطبيقات المستثناة.",
            "مراقبة التحايل: إذا أوقف أحد الأعضاء الحماية أو أغلق التطبيق قسرياً وهو على نفس الواي فاي، سيصلك تنبيه فوري.",
            "راوتر فرعي بالكابل: اجعل الراوتر الفرعي في وضع Access Point (إيقاف DHCP وتوصيل الكابل في منفذ LAN) ليعمل كل شيء بسلاسة."
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    steps.forEachIndexed { idx, stepText ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(SoftMint.copy(alpha = 0.55f))
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(PrimaryTeal),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stepText,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = TextMuted
                            )
                        }
                    }
                }
                // Keep primary action button pinned below the scroll container so it is always visible
                if (onPrimaryAction != null && primaryActionLabel != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            onDismiss()
                            onPrimaryAction()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(primaryActionLabel)
                    }
                }
            }
        },
        confirmButton = {
            if (onSecondaryAction != null && secondaryActionLabel != null) {
                TextButton(onClick = {
                    onSecondaryAction()
                    onDismiss()
                }) { Text(secondaryActionLabel) }
            } else {
                TextButton(onClick = onDismiss) { Text("فهمت") }
            }
        },
        dismissButton = {
            if (onSecondaryAction != null) {
                TextButton(onClick = onDismiss) { Text("إغلاق") }
            }
        }
    )
}

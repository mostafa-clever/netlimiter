package com.wifiguard.limiter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

val PrimaryTeal = Color(0xFF0D7377)
val PrimaryDark = Color(0xFF095256)
val SoftMint = Color(0xFFE0F2F1)
val SurfaceLight = Color(0xFFF6F9FA)
val CardWhite = Color(0xFFFFFFFF)
val TextDark = Color(0xFF1A252B)
val TextMuted = Color(0xFF60727A)
val WarnAmber = Color(0xFFD97706)
val WarnAmberBg = Color(0xFFFEF3C7)
val AlertRed = Color(0xFFDC2626)
val AlertRedBg = Color(0xFFFEE2E2)
val SuccessGreen = Color(0xFF059669)
val SuccessGreenBg = Color(0xFFD1FAE5)

private val AppColorScheme = lightColorScheme(
    primary = PrimaryTeal,
    onPrimary = Color.White,
    primaryContainer = SoftMint,
    onPrimaryContainer = PrimaryDark,
    secondary = Color(0xFF14919B),
    onSecondary = Color.White,
    background = SurfaceLight,
    onBackground = TextDark,
    surface = CardWhite,
    onSurface = TextDark,
    surfaceVariant = Color(0xFFEBF1F3),
    onSurfaceVariant = TextMuted,
    error = AlertRed,
    onError = Color.White
)

@Composable
fun WifiGuardTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = AppColorScheme,
            content = content
        )
    }
}

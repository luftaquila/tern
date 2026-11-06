// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.ThemeMode
import androidx.compose.ui.text.font.Font as ResFont

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CA6FF),
    onPrimary = Color(0xFF0B1640),
    primaryContainer = Color(0xFF263463),
    onPrimaryContainer = Color(0xFFDCE3FF),
    inversePrimary = Color(0xFF4C6FFF),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF16443D),
    onSecondaryContainer = Color(0xFFB8F5EA),
    tertiary = Color(0xFFF5B3D3),
    onTertiary = Color(0xFF4A1233),
    tertiaryContainer = Color(0xFF5E2A47),
    onTertiaryContainer = Color(0xFFFFD8E9),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF0B0D12),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF1E2430),
    onSurfaceVariant = Color(0xFFA3ACBD),
    inverseSurface = Color(0xFFE6E9EF),
    inverseOnSurface = Color(0xFF161A22),
    error = Color(0xFFFF6B81),
    onError = Color(0xFF4A0613),
    errorContainer = Color(0xFF6B1426),
    onErrorContainer = Color(0xFFFFD9DE),
    outline = Color(0xFF3A4253),
    outlineVariant = Color(0xFF262D3A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A3140),
    surfaceDim = Color(0xFF0B0D12),
    surfaceContainerLowest = Color(0xFF07090C),
    surfaceContainerLow = Color(0xFF11141B),
    surfaceContainer = Color(0xFF151922),
    surfaceContainerHigh = Color(0xFF1B202A),
    surfaceContainerHighest = Color(0xFF222835),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F5EF0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF0B1A63),
    inversePrimary = Color(0xFF8CA6FF),
    secondary = Color(0xFF0D9488),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC8F2EB),
    onSecondaryContainer = Color(0xFF003731),
    tertiary = Color(0xFFB83D7A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E9),
    onTertiaryContainer = Color(0xFF3E0525),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF161A22),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF161A22),
    surfaceVariant = Color(0xFFE3E7EE),
    onSurfaceVariant = Color(0xFF5B6475),
    inverseSurface = Color(0xFF232833),
    inverseOnSurface = Color(0xFFF0F2F6),
    error = Color(0xFFD32F4A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9DE),
    onErrorContainer = Color(0xFF410010),
    outline = Color(0xFFC3C9D4),
    outlineVariant = Color(0xFFE1E5EC),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDCE0E8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F7),
    surfaceContainer = Color(0xFFECEFF4),
    surfaceContainerHigh = Color(0xFFE6E9EF),
    surfaceContainerHighest = Color(0xFFE0E4EB),
)

/** D2Coding for anything that shows terminal-ish text (addresses, paths, fingerprints). */
val MonoFamily = FontFamily(ResFont(R.font.d2coding))

private val TernShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun ternTypography(): Typography {
    val base = Typography()
    return base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

val MonoTextStyle = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp)

/** Tag colors for hosts. */
val HostColors = listOf(
    Color(0xFF8CA6FF), Color(0xFF5EEAD4), Color(0xFFFFC777), Color(0xFFF7768E),
    Color(0xFFBB9AF7), Color(0xFF9ECE6A), Color(0xFF7DCFFF), Color(0xFFFF9E64),
)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

@Composable
fun TernTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = isDarkTheme(mode)
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = ternTypography(), shapes = TernShapes, content = content)
}

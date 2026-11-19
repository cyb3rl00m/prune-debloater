package io.github.cyb3rl00m.prune.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cyb3rl00m.prune.core.model.Risk
import io.github.cyb3rl00m.prune.core.settings.ThemeMode

private val Mint = Color(0xFF3DDC97)
private val MintDeep = Color(0xFF0E9F6E)
private val Violet = Color(0xFF7C6CFF)
private val VioletDeep = Color(0xFF5B4BDB)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00281A),
    primaryContainer = Color(0xFF0F3B2C),
    onPrimaryContainer = Color(0xFFB6F5D8),
    secondary = Violet,
    onSecondary = Color(0xFF14104A),
    secondaryContainer = Color(0xFF2A2463),
    onSecondaryContainer = Color(0xFFE1DCFF),
    tertiary = Color(0xFFFFB547),
    onTertiary = Color(0xFF2E1B00),
    background = Color(0xFF0B0F17),
    onBackground = Color(0xFFE8EEF7),
    surface = Color(0xFF0B0F17),
    onSurface = Color(0xFFE8EEF7),
    surfaceVariant = Color(0xFF1B2433),
    onSurfaceVariant = Color(0xFFA9B4C6),
    surfaceContainerLowest = Color(0xFF080B11),
    surfaceContainerLow = Color(0xFF10161F),
    surfaceContainer = Color(0xFF141B27),
    surfaceContainerHigh = Color(0xFF1A2230),
    surfaceContainerHighest = Color(0xFF212B3B),
    outline = Color(0xFF3A4659),
    outlineVariant = Color(0xFF263042),
    error = Color(0xFFFF5C6C),
    onError = Color(0xFF3A0008),
    errorContainer = Color(0xFF4A1119),
    onErrorContainer = Color(0xFFFFD9DC),
)

private val LightColors = lightColorScheme(
    primary = MintDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8F4DF),
    onPrimaryContainer = Color(0xFF00321F),
    secondary = VioletDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E0FF),
    onSecondaryContainer = Color(0xFF17106A),
    tertiary = Color(0xFFB86E00),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF0E1420),
    surface = Color(0xFFF4F6FA),
    onSurface = Color(0xFF0E1420),
    surfaceVariant = Color(0xFFE2E7F0),
    onSurfaceVariant = Color(0xFF4A5568),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEEF1F6),
    surfaceContainerHighest = Color(0xFFE6EAF1),
    outline = Color(0xFFB7C0CE),
    outlineVariant = Color(0xFFDCE2EA),
    error = Color(0xFFD32F45),
    errorContainer = Color(0xFFFFDADF),
    onErrorContainer = Color(0xFF410009),
)

@Immutable
data class RiskColors(val safe: Color, val low: Color, val moderate: Color, val high: Color, val locked: Color) {
    fun of(r: Risk) = when (r) {
        Risk.SAFE -> safe
        Risk.LOW -> low
        Risk.MODERATE -> moderate
        Risk.HIGH -> high
        Risk.LOCKED -> locked
    }
}

private val DarkRisk = RiskColors(Mint, Color(0xFF5CC8FF), Color(0xFFFFB547), Color(0xFFFF6B57), Color(0xFF8A94A6))
private val LightRisk = RiskColors(MintDeep, Color(0xFF0B84C6), Color(0xFFB86E00), Color(0xFFD1402B), Color(0xFF6B7587))

val LocalRiskColors = staticCompositionLocalOf { DarkRisk }

private val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val Base = Typography()
private val AppTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
fun PruneTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalRiskColors provides if (dark) DarkRisk else LightRisk) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = Shapes,
            content = content,
        )
    }
}

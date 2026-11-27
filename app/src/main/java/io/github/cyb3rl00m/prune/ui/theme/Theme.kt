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

// "Graphite": neutral greys without a blue cast, ink-coloured primary actions and a few muted signal colours.
// Colour is reserved for meaning (risk, warnings, errors); everything else is grey.
private val Ink = Color(0xFF1B1B1A)
private val Paper = Color(0xFFE7E5E1)

private val DarkColors = darkColorScheme(
    primary = Paper,
    onPrimary = Color(0xFF151515),
    primaryContainer = Color(0xFF2B2B2A),
    onPrimaryContainer = Paper,
    secondary = Color(0xFFB4B1AB),
    onSecondary = Color(0xFF151515),
    secondaryContainer = Color(0xFF242423),
    onSecondaryContainer = Paper,
    tertiary = Color(0xFFC9A55F),
    onTertiary = Color(0xFF1E1606),
    tertiaryContainer = Color(0xFF2E281C),
    onTertiaryContainer = Color(0xFFE9D9B6),
    background = Color(0xFF151515),
    onBackground = Paper,
    surface = Color(0xFF151515),
    onSurface = Paper,
    surfaceVariant = Color(0xFF242423),
    onSurfaceVariant = Color(0xFF9D9A95),
    surfaceContainerLowest = Color(0xFF101010),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF1B1B1B),
    surfaceContainerHigh = Color(0xFF222222),
    surfaceContainerHighest = Color(0xFF2A2A29),
    outline = Color(0xFF4A4947),
    outlineVariant = Color(0xFF2C2C2B),
    error = Color(0xFFD27561),
    onError = Color(0xFF240A05),
    errorContainer = Color(0xFF34201B),
    onErrorContainer = Color(0xFFF1D1C8),
)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color(0xFFF5F4F1),
    primaryContainer = Color(0xFFE4E2DD),
    onPrimaryContainer = Ink,
    secondary = Color(0xFF5E5C58),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E7E2),
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF8A6A2A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF1E8D4),
    onTertiaryContainer = Color(0xFF3A2C0E),
    background = Color(0xFFF5F4F1),
    onBackground = Ink,
    surface = Color(0xFFF5F4F1),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9E7E2),
    onSurfaceVariant = Color(0xFF64625D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9F8F6),
    surfaceContainer = Color(0xFFFBFAF8),
    surfaceContainerHigh = Color(0xFFEFEDE9),
    surfaceContainerHighest = Color(0xFFE7E5E0),
    outline = Color(0xFFB9B6AF),
    outlineVariant = Color(0xFFE0DED8),
    error = Color(0xFFA8432F),
    errorContainer = Color(0xFFF5DDD6),
    onErrorContainer = Color(0xFF3D1209),
)

@Immutable
data class RiskColors(val safe: Color, val low: Color, val moderate: Color, val high: Color, val locked: Color, val unknown: Color) {
    fun of(r: Risk) = when (r) {
        Risk.SAFE -> safe
        Risk.LOW -> low
        Risk.MODERATE -> moderate
        Risk.HIGH -> high
        Risk.UNKNOWN -> unknown
        Risk.LOCKED -> locked
    }
}

// Muted, earthy signal colours: distinguishable, but none of them glows.
private val DarkRisk = RiskColors(
    safe = Color(0xFF94AD92), low = Color(0xFFA2ABB3), moderate = Color(0xFFC9A55F),
    high = Color(0xFFD27561), locked = Color(0xFF7D7B77), unknown = Color(0xFFB3A994),
)
private val LightRisk = RiskColors(
    safe = Color(0xFF4D6A4C), low = Color(0xFF56616B), moderate = Color(0xFF8A6A2A),
    high = Color(0xFFA8432F), locked = Color(0xFF77746E), unknown = Color(0xFF6E6656),
)

val LocalRiskColors = staticCompositionLocalOf { DarkRisk }

private val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

private val Base = Typography()
private val AppTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Medium),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Medium),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Medium, fontSize = 26.sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Medium),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Medium),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.Medium),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

/** Numbers (counts, sizes): monospace, so they read like measurements rather than marketing. */
val NumberStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal)

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

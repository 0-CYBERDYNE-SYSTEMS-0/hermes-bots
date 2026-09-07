package ai.hermes.bots.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Brand type scale (audit A9): hierarchy from weight + size, default font family.
 * Metadata/time styles carry tabular figures ("tnum") so clocks don't jitter.
 */
private val AppTypography = Typography(
  titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
  titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
  titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
  bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
  bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
  bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
  labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
  labelMedium = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
    lineHeight = 16.sp,
    fontFeatureSettings = "tnum",
  ),
  labelSmall = TextStyle(
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    lineHeight = 15.sp,
    fontFeatureSettings = "tnum",
  ),
)

/** Shape scale (audit A10): bubbles/composer roundest, cards 16, chips 12, badges 8. */
private val AppShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(20.dp),
  extraLarge = RoundedCornerShape(28.dp),
)

// Brand palette — UI-SPEC.md §3 (powder blue primary, burnt orange secondary).
// Dark: bg #0E1116 / surface #161B22 / raised #1F262E · primary #8FC7E8 · secondary #E0662B
// Light: bg #FAFAF8 / surface #FFFFFF / raised #F0EDE8 · primary #3D7EA6 · secondary #C65218

data class BrandPalette(val success: Color, val danger: Color)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FC7E8),
    onPrimary = Color(0xFF0E1116),
    primaryContainer = Color(0xFF1E3A4C),
    onPrimaryContainer = Color(0xFF8FC7E8),
    secondary = Color(0xFFE0662B),
    onSecondary = Color(0xFF0E1116),
    secondaryContainer = Color(0xFF4A2412),
    onSecondaryContainer = Color(0xFFF6B79A),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1F262E),
    onSurfaceVariant = Color(0xFF9DA7B3),
    outline = Color(0xFF2D3640),
    surfaceContainer = Color(0xFF1F262E),
    surfaceContainerHigh = Color(0xFF242B34),
    surfaceContainerHighest = Color(0xFF2A323C),
    surfaceTint = Color(0xFF1F262E),
    error = Color(0xFFE5534B),
    onError = Color(0xFF0E1116),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D7EA6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E7F2),
    onPrimaryContainer = Color(0xFF1C3A4D),
    secondary = Color(0xFFC65218),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7DCCB),
    onSecondaryContainer = Color(0xFF7A3110),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF1C2128),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C2128),
    surfaceVariant = Color(0xFFF0EDE8),
    onSurfaceVariant = Color(0xFF57606A),
    outline = Color(0xFFD0D4D9),
    surfaceContainer = Color(0xFFF0EDE8),
    surfaceContainerHigh = Color(0xFFEBE7E1),
    surfaceContainerHighest = Color(0xFFE5E1DB),
    surfaceTint = Color(0xFFF0EDE8),
    error = Color(0xFFC93C37),
    onError = Color.White,
)

/** Tracks the resolved dark flag (in-app override wins over system) so non-color-scheme
 *  accents (status dots, danger text) follow the SAME theme the user picked. */
val LocalBrandDark = staticCompositionLocalOf { true }

@Composable
fun brandPalette(): BrandPalette =
    if (LocalBrandDark.current) BrandPalette(success = Color(0xFF57AB5A), danger = Color(0xFFE5534B))
    else BrandPalette(success = Color(0xFF3E8F4A), danger = Color(0xFFC93C37))

@Composable
fun HermesBotsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBrandDark provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

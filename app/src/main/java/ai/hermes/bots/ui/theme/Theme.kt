package ai.hermes.bots.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
            content = content,
        )
    }
}

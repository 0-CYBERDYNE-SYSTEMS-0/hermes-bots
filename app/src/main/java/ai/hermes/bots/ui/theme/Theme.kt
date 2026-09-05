package ai.hermes.bots.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette (DECISIONS.md #9): powder blue = primary accent, burnt orange = secondary.
private val PowderBlue = Color(0xFFB0E0E6)
private val PowderBlueDeep = Color(0xFF3E6B79)
private val PowderBlueLight = Color(0xFF89CEDC)
private val BurntOrange = Color(0xFF8C4218)
private val BurntOrangeLight = Color(0xFFFFB68C)
private val BurntOrangeDeep = Color(0xFF74300C)

private val LightColors = lightColorScheme(
    primary = PowderBlueDeep,
    onPrimary = Color.White,
    primaryContainer = PowderBlue,
    onPrimaryContainer = Color(0xFF0A2830),
    secondary = BurntOrange,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBC7),
    onSecondaryContainer = Color(0xFF331200),
    background = Color(0xFFF6FAFB),
    onBackground = Color(0xFF181C1E),
    surface = Color(0xFFF6FAFB),
    onSurface = Color(0xFF181C1E),
    surfaceVariant = Color(0xFFDBE4E7),
    onSurfaceVariant = Color(0xFF40484C),
)

private val DarkColors = darkColorScheme(
    primary = PowderBlueLight,
    onPrimary = Color(0xFF003640),
    primaryContainer = Color(0xFF1E4E5A),
    onPrimaryContainer = PowderBlue,
    secondary = BurntOrangeLight,
    onSecondary = Color(0xFF522200),
    secondaryContainer = BurntOrangeDeep,
    onSecondaryContainer = Color(0xFFFFDBC7),
    background = Color(0xFF101416),
    onBackground = Color(0xFFDFE4E6),
    surface = Color(0xFF101416),
    onSurface = Color(0xFFDFE4E6),
    surfaceVariant = Color(0xFF40484C),
    onSurfaceVariant = Color(0xFFBFC8CC),
)

@Composable
fun HermesBotsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

package ai.hermes.bots.ui.theme

import ai.hermes.bots.R
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ManropeFontFamily = FontFamily(
    Font(R.font.manrope_variable, weight = FontWeight.Normal),
    Font(R.font.manrope_variable, weight = FontWeight(450)),
    Font(R.font.manrope_variable, weight = FontWeight.Medium),
    Font(R.font.manrope_variable, weight = FontWeight(650)),
    Font(R.font.manrope_variable, weight = FontWeight.Bold),
)

val IbmPlexMonoFontFamily = FontFamily(
    Font(R.font.ibm_plex_mono_medium, weight = FontWeight.Medium),
    Font(R.font.ibm_plex_mono_bold, weight = FontWeight.Bold),
)

data class FleetTypography(
    val screenTitle: TextStyle,
    val sectionTitle: TextStyle,
    val machineName: TextStyle,
    val botName: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val metadata: TextStyle,
    val metadataStrong: TextStyle,
    val eyebrow: TextStyle,
)

private val FleetType = FleetTypography(
    screenTitle = TextStyle(
        fontFamily = ManropeFontFamily,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight(650),
        letterSpacing = (-0.98).sp,
    ),
    sectionTitle = TextStyle(
        fontFamily = ManropeFontFamily,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight(650),
    ),
    machineName = TextStyle(
        fontFamily = ManropeFontFamily,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Bold,
    ),
    botName = TextStyle(
        fontFamily = ManropeFontFamily,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight(650),
    ),
    body = TextStyle(
        fontFamily = ManropeFontFamily,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight(450),
    ),
    bodySmall = TextStyle(
        fontFamily = ManropeFontFamily,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight(450),
    ),
    label = TextStyle(
        fontFamily = ManropeFontFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight(650),
    ),
    metadata = TextStyle(
        fontFamily = IbmPlexMonoFontFamily,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    ),
    metadataStrong = TextStyle(
        fontFamily = IbmPlexMonoFontFamily,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
    ),
    eyebrow = TextStyle(
        fontFamily = IbmPlexMonoFontFamily,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        fontFeatureSettings = "tnum",
    ),
)

private val AppTypography = Typography(
    displaySmall = FleetType.screenTitle,
    headlineSmall = FleetType.screenTitle,
    headlineMedium = FleetType.screenTitle,
    titleLarge = FleetType.screenTitle,
    titleMedium = FleetType.sectionTitle,
    titleSmall = FleetType.machineName,
    bodyLarge = FleetType.body,
    bodyMedium = FleetType.body,
    bodySmall = FleetType.bodySmall,
    labelLarge = FleetType.label,
    labelMedium = FleetType.metadata,
    labelSmall = FleetType.metadata,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Dimens.RadiusControl),
    small = RoundedCornerShape(Dimens.RadiusRow),
    medium = RoundedCornerShape(Dimens.RadiusAttention),
    large = RoundedCornerShape(Dimens.RadiusInput),
    extraLarge = RoundedCornerShape(Dimens.RadiusMachine),
)

data class FleetColors(
    val canvas: Color,
    val canvasDeep: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceInput: Color,
    val surfaceNav: Color,
    val line: Color,
    val lineQuiet: Color,
    val text: Color,
    val textMuted: Color,
    val textDim: Color,
    val primary: Color,
    val primaryDeep: Color,
    val attention: Color,
    val success: Color,
    val danger: Color,
    val primaryMachine: Color,
    val identityLime: Color,
)

private val DarkFleetColors = FleetColors(
    canvas = Color(0xFF0D1116),
    canvasDeep = Color(0xFF0B0E12),
    surface = Color(0xFF12171E),
    surfaceRaised = Color(0xFF1A212B),
    surfaceInput = Color(0xFF202731),
    surfaceNav = Color(0xF50C1015),
    line = Color(0xFF29323E),
    lineQuiet = Color(0xFF202833),
    text = Color(0xFFF3F5F7),
    textMuted = Color(0xFF9AA6B5),
    textDim = Color(0xFF667282),
    primary = Color(0xFF9DD7F2),
    primaryDeep = Color(0xFF163D52),
    attention = Color(0xFFEF7137),
    success = Color(0xFF65D28C),
    danger = Color(0xFFFF6F68),
    primaryMachine = Color(0xFFEFC75E),
    identityLime = Color(0xFFCDE73E),
)

private val LightFleetColors = FleetColors(
    canvas = Color(0xFFF6F5F1),
    canvasDeep = Color(0xFFEEECE7),
    surface = Color.White,
    surfaceRaised = Color(0xFFECEFF2),
    surfaceInput = Color(0xFFE8EDF1),
    surfaceNav = Color(0xFAFBFAF7),
    line = Color(0xFFD1D8DE),
    lineQuiet = Color(0xFFE2E6EA),
    text = Color(0xFF17202A),
    textMuted = Color(0xFF596675),
    textDim = Color(0xFF778391),
    primary = Color(0xFF286F94),
    primaryDeep = Color(0xFFD8EBF4),
    attention = Color(0xFFB94716),
    success = Color(0xFF287345),
    danger = Color(0xFFB93430),
    primaryMachine = Color(0xFF806000),
    identityLime = Color(0xFF647800),
)

private fun darkMaterialColors(colors: FleetColors) = darkColorScheme(
    primary = colors.primary,
    onPrimary = colors.canvasDeep,
    primaryContainer = colors.primaryDeep,
    onPrimaryContainer = colors.primary,
    secondary = colors.attention,
    onSecondary = colors.canvasDeep,
    secondaryContainer = colors.surfaceRaised,
    onSecondaryContainer = colors.attention,
    tertiary = colors.success,
    onTertiary = colors.canvasDeep,
    background = colors.canvas,
    onBackground = colors.text,
    surface = colors.surface,
    onSurface = colors.text,
    surfaceVariant = colors.surfaceRaised,
    onSurfaceVariant = colors.textMuted,
    outline = colors.line,
    outlineVariant = colors.lineQuiet,
    surfaceContainerLowest = colors.canvasDeep,
    surfaceContainerLow = colors.surface,
    surfaceContainer = colors.surfaceRaised,
    surfaceContainerHigh = colors.surfaceInput,
    surfaceContainerHighest = colors.surfaceInput,
    surfaceTint = Color.Transparent,
    error = colors.danger,
    onError = colors.canvasDeep,
    scrim = colors.canvasDeep,
)

private fun lightMaterialColors(colors: FleetColors) = lightColorScheme(
    primary = colors.primary,
    onPrimary = Color.White,
    primaryContainer = colors.primaryDeep,
    onPrimaryContainer = colors.text,
    secondary = colors.attention,
    onSecondary = Color.White,
    secondaryContainer = colors.surfaceRaised,
    onSecondaryContainer = colors.attention,
    tertiary = colors.success,
    onTertiary = Color.White,
    background = colors.canvas,
    onBackground = colors.text,
    surface = colors.surface,
    onSurface = colors.text,
    surfaceVariant = colors.surfaceRaised,
    onSurfaceVariant = colors.textMuted,
    outline = colors.line,
    outlineVariant = colors.lineQuiet,
    surfaceContainerLowest = colors.canvasDeep,
    surfaceContainerLow = colors.surface,
    surfaceContainer = colors.surfaceRaised,
    surfaceContainerHigh = colors.surfaceInput,
    surfaceContainerHighest = colors.surfaceInput,
    surfaceTint = Color.Transparent,
    error = colors.danger,
    onError = Color.White,
    scrim = colors.canvasDeep,
)

data class BrandPalette(val success: Color, val danger: Color)

val LocalBrandDark = staticCompositionLocalOf { true }
val LocalFleetColors = staticCompositionLocalOf { DarkFleetColors }
val LocalFleetTypography = staticCompositionLocalOf { FleetType }

object HermesTheme {
    val colors: FleetColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFleetColors.current

    val typography: FleetTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalFleetTypography.current
}

@Composable
fun brandPalette(): BrandPalette = BrandPalette(
    success = HermesTheme.colors.success,
    danger = HermesTheme.colors.danger,
)

@Composable
fun HermesBotsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkFleetColors else LightFleetColors
    CompositionLocalProvider(
        LocalBrandDark provides darkTheme,
        LocalFleetColors provides colors,
        LocalFleetTypography provides FleetType,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkMaterialColors(colors) else lightMaterialColors(colors),
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

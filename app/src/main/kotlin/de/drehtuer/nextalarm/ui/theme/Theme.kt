package de.drehtuer.nextalarm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Every value comes from docs/design.md.

val LightColors = lightColorScheme(
    primary = Color(0xFF00639B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE5FF),
    onPrimaryContainer = Color(0xFF001D33),
    secondary = Color(0xFF8A5100),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCBE),
    onSecondaryContainer = Color(0xFF2C1600),
    background = Color(0xFFF8F8F8),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFF8F8F8),
    onSurface = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFFEDEDED),
    surfaceContainerHigh = Color(0xFFE4E4E4),
    surfaceVariant = Color(0xFFE2E2E2),
    onSurfaceVariant = Color(0xFF474747),
    outline = Color(0xFF767676),
    outlineVariant = Color(0xFFC6C6C6),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF1F1F1),
    inversePrimary = Color(0xFF99CBFF),
    scrim = Color(0xFF000000),
    error = Color(0xFFA3161A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF99CBFF),
    onPrimary = Color(0xFF003354),
    primaryContainer = Color(0xFF004A76),
    onPrimaryContainer = Color(0xFFCFE5FF),
    secondary = Color(0xFFFFB870),
    onSecondary = Color(0xFF4A2800),
    secondaryContainer = Color(0xFF693C00),
    onSecondaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE6E6E6),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceVariant = Color(0xFF303030),
    onSurfaceVariant = Color(0xFFC6C6C6),
    outline = Color(0xFF929292),
    outlineVariant = Color(0xFF474747),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF303030),
    inversePrimary = Color(0xFF00639B),
    scrim = Color(0xFF000000),
    error = Color(0xFFFF8E84),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Screens 8 and 9. The roles without a night value keep their dark value. */
val NightColors = DarkColors.copy(
    primary = Color(0xFF99CBFF),
    onPrimary = Color(0xFF003354),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE6E6E6),
    surfaceContainer = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFA8A8A8),
    outline = Color(0xFF474747),
)

@Immutable
data class ExtraColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

val LightExtraColors = ExtraColors(Color(0xFF1E6E3C), Color(0xFFC8EFD2), Color(0xFF0B3D1E))
val DarkExtraColors = ExtraColors(Color(0xFF7FD99A), Color(0xFF0B3D1E), Color(0xFFC8EFD2))

val LocalExtraColors = staticCompositionLocalOf { LightExtraColors }

private fun style(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
)

/** Times line up because every time uses tabular figures. */
const val TABULAR_FIGURES = "tnum"

val NextAlarmTypography = Typography(
    displayLarge = style(112, 120).copy(fontFeatureSettings = TABULAR_FIGURES, letterSpacing = (-0.02).em),
    displayMedium = style(72, 80).copy(fontFeatureSettings = TABULAR_FIGURES),
    displaySmall = style(64, 72).copy(fontFeatureSettings = TABULAR_FIGURES),
    headlineMedium = style(32, 40),
    headlineSmall = style(26, 34, FontWeight.Medium),
    titleLarge = style(22, 28),
    titleMedium = style(18, 26),
    bodyLarge = style(16, 24),
    bodyMedium = style(14, 20),
    bodySmall = style(13, 18),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium),
)

/** Times in lists. */
val ListTimeStyle = style(20, 28, FontWeight.Medium).copy(fontFeatureSettings = TABULAR_FIGURES)

/** A long title on the alarm screen. */
val LongAlarmTitleStyle = style(24, 32, FontWeight.Medium)

val NextAlarmShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Screens 1 to 7. They follow the system's light or dark setting, with a fixed palette. */
@Composable
fun NextAlarmTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtraColors else LightExtraColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = NextAlarmTypography,
            shapes = NextAlarmShapes,
            content = content,
        )
    }
}

/** Screens 8 and 9: always night, whatever the system setting. */
@Composable
fun NightTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtraColors provides DarkExtraColors) {
        MaterialTheme(
            colorScheme = NightColors,
            typography = NextAlarmTypography,
            shapes = NextAlarmShapes,
            content = content,
        )
    }
}

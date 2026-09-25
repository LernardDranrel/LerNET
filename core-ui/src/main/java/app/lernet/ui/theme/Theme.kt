package app.lernet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SecondaryContainer = Color(0xFF163528)

private val LerNetScheme = darkColorScheme(
    primary = LerNetAccent,
    onPrimary = LerNetBlack,
    primaryContainer = LerNetAccentSoft,
    onPrimaryContainer = LerNetWhite,
    secondary = LerNetOk,
    onSecondary = LerNetBlack,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = LerNetWhite,
    tertiary = LerNetWarn,
    onTertiary = LerNetBlack,
    tertiaryContainer = Color(0xFF392F1D),
    onTertiaryContainer = Color(0xFFF4DCA5),
    background = LerNetBlack,
    onBackground = LerNetWhite,
    surface = LerNetInk,
    onSurface = LerNetWhite,
    surfaceVariant = LerNetPanel,
    onSurfaceVariant = LerNetMuted,
    surfaceTint = LerNetAccent,
    outline = LerNetLine,
    outlineVariant = LerNetElevated,
    error = LerNetDanger,
    onError = Color(0xFF690005),
    errorContainer = LerNetErrorContainer,
    onErrorContainer = LerNetOnErrorContainer,
    surfaceBright = LerNetElevated,
    surfaceDim = LerNetBlack,
    surfaceContainerLowest = LerNetBlack,
    surfaceContainerLow = LerNetInk,
    surfaceContainer = LerNetPanel,
    surfaceContainerHigh = LerNetElevated,
    surfaceContainerHighest = LerNetLine,
)

@Composable
fun LerNetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LerNetScheme,
        typography = LerNetTypography,
        shapes = LerNetShapes,
        content = content,
    )
}

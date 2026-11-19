package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class StudioSemanticColors(
    val paperBackground: Color = PaperWhite,
    val cardSurface: Color = CardSurface,
    val secondaryContainer: Color = SecondaryContainer,
    val elevatedSurface: Color = ElevatedSurface,
    val hairlineBorder: Color = HairlineBorder,
    val primaryInk: Color = PrimaryInk,
    val mutedGraphite: Color = MutedGraphite,
    val activeAccent: Color = ActiveCobalt,
    val activeAccentSoft: Color = ActiveCobaltSoft,
    val emerald: Color = EmeraldInk,
    val emeraldSoft: Color = EmeraldSoft,
    val amber: Color = AmberInk,
    val amberSoft: Color = AmberSoft,
    val crimson: Color = CrimsonInk,
    val crimsonSoft: Color = CrimsonSoft
)

val LocalStudioColors = staticCompositionLocalOf { StudioSemanticColors() }

val LocalWindowWidthSizeClass = staticCompositionLocalOf { WindowWidthSizeClass.Compact }

private val StudioLightColorScheme = lightColorScheme(
    primary = ActiveCobalt,
    onPrimary = Color.White,
    primaryContainer = ActiveCobaltSoft,
    onPrimaryContainer = ActiveCobalt,
    secondary = PrimaryInk,
    onSecondary = Color.White,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = PrimaryInk,
    tertiary = EmeraldInk,
    onTertiary = Color.White,
    tertiaryContainer = EmeraldSoft,
    onTertiaryContainer = EmeraldInk,
    background = PaperWhite,
    onBackground = PrimaryInk,
    surface = CardSurface,
    onSurface = PrimaryInk,
    surfaceVariant = SecondaryContainer,
    onSurfaceVariant = MutedGraphite,
    surfaceContainerLowest = CardSurface,
    surfaceContainerLow = PaperWhite,
    surfaceContainer = SecondaryContainer,
    surfaceContainerHigh = ElevatedSurface,
    surfaceContainerHighest = ElevatedSurface,
    outline = HairlineBorder,
    outlineVariant = HairlineBorder,
    error = CrimsonInk,
    onError = Color.White,
    errorContainer = CrimsonSoft,
    onErrorContainer = CrimsonInk
)

val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun UniversalUsbPrinterTheme(
    content: @Composable () -> Unit
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStudioColors provides StudioSemanticColors()
    ) {
        MaterialTheme(
            colorScheme = StudioLightColorScheme,
            typography = StudioTypography,
            shapes = StudioShapes,
            content = content
        )
    }
}

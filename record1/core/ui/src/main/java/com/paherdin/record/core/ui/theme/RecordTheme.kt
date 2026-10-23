package com.paherdin.record.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val lightColors = lightColorScheme(
    primary = RecordPalette.primaryAccentLight,
    onPrimary = RecordPalette.onPrimaryContentLight,
    primaryContainer = RecordPalette.primarySurfaceLight,
    onPrimaryContainer = RecordPalette.primaryContentLight,
    inversePrimary = RecordPalette.primaryAccentDark,
    secondary = RecordPalette.warmSecondaryLight,
    onSecondary = RecordPalette.onPrimaryContentLight,
    secondaryContainer = RecordPalette.primarySurfaceLight,
    onSecondaryContainer = RecordPalette.primaryContentLight,
    tertiary = RecordPalette.warmSecondaryLight,
    onTertiary = RecordPalette.onPrimaryContentLight,
    tertiaryContainer = RecordPalette.primarySurfaceLight,
    onTertiaryContainer = RecordPalette.primaryContentLight,
    background = RecordPalette.appBackgroundLight,
    onBackground = RecordPalette.primaryContentLight,
    surface = RecordPalette.primarySurfaceLight,
    onSurface = RecordPalette.primaryContentLight,
    surfaceVariant = RecordPalette.primarySurfaceLight,
    onSurfaceVariant = RecordPalette.primaryContentLight,
    surfaceTint = RecordPalette.primaryAccentLight,
    inverseSurface = RecordPalette.primarySurfaceDark,
    inverseOnSurface = RecordPalette.primaryContentDark,
    outline = RecordPalette.primaryAccentLight,
    outlineVariant = RecordPalette.warmSecondaryLight,
    scrim = RecordPalette.primaryContentLight,
    surfaceBright = RecordPalette.appBackgroundLight,
    surfaceContainer = RecordPalette.primarySurfaceLight,
    surfaceContainerHigh = RecordPalette.primarySurfaceLight,
    surfaceContainerHighest = RecordPalette.primarySurfaceLight,
    surfaceContainerLow = RecordPalette.primarySurfaceLight,
    surfaceContainerLowest = RecordPalette.appBackgroundLight,
    surfaceDim = RecordPalette.primarySurfaceLight,
    primaryFixed = RecordPalette.primaryAccentDark,
    primaryFixedDim = RecordPalette.primaryAccentDark,
    onPrimaryFixed = RecordPalette.onPrimaryContentDark,
    onPrimaryFixedVariant = RecordPalette.onPrimaryContentDark,
    secondaryFixed = RecordPalette.warmSecondaryDark,
    secondaryFixedDim = RecordPalette.warmSecondaryDark,
    onSecondaryFixed = RecordPalette.onPrimaryContentDark,
    onSecondaryFixedVariant = RecordPalette.onPrimaryContentDark,
    tertiaryFixed = RecordPalette.warmSecondaryDark,
    tertiaryFixedDim = RecordPalette.warmSecondaryDark,
    onTertiaryFixed = RecordPalette.onPrimaryContentDark,
    onTertiaryFixedVariant = RecordPalette.onPrimaryContentDark,
)

private val darkColors = darkColorScheme(
    primary = RecordPalette.primaryAccentDark,
    onPrimary = RecordPalette.onPrimaryContentDark,
    primaryContainer = RecordPalette.primarySurfaceDark,
    onPrimaryContainer = RecordPalette.primaryContentDark,
    inversePrimary = RecordPalette.primaryAccentLight,
    secondary = RecordPalette.warmSecondaryDark,
    onSecondary = RecordPalette.onPrimaryContentDark,
    secondaryContainer = RecordPalette.primarySurfaceDark,
    onSecondaryContainer = RecordPalette.primaryContentDark,
    tertiary = RecordPalette.warmSecondaryDark,
    onTertiary = RecordPalette.onPrimaryContentDark,
    tertiaryContainer = RecordPalette.primarySurfaceDark,
    onTertiaryContainer = RecordPalette.primaryContentDark,
    background = RecordPalette.appBackgroundDark,
    onBackground = RecordPalette.primaryContentDark,
    surface = RecordPalette.primarySurfaceDark,
    onSurface = RecordPalette.primaryContentDark,
    surfaceVariant = RecordPalette.primarySurfaceDark,
    onSurfaceVariant = RecordPalette.primaryContentDark,
    surfaceTint = RecordPalette.primaryAccentDark,
    inverseSurface = RecordPalette.primarySurfaceLight,
    inverseOnSurface = RecordPalette.primaryContentLight,
    outline = RecordPalette.primaryAccentDark,
    outlineVariant = RecordPalette.warmSecondaryDark,
    scrim = RecordPalette.primaryContentLight,
    surfaceBright = RecordPalette.primarySurfaceDark,
    surfaceContainer = RecordPalette.primarySurfaceDark,
    surfaceContainerHigh = RecordPalette.primarySurfaceDark,
    surfaceContainerHighest = RecordPalette.primarySurfaceDark,
    surfaceContainerLow = RecordPalette.primarySurfaceDark,
    surfaceContainerLowest = RecordPalette.appBackgroundDark,
    surfaceDim = RecordPalette.appBackgroundDark,
    primaryFixed = RecordPalette.primaryAccentDark,
    primaryFixedDim = RecordPalette.primaryAccentDark,
    onPrimaryFixed = RecordPalette.onPrimaryContentDark,
    onPrimaryFixedVariant = RecordPalette.onPrimaryContentDark,
    secondaryFixed = RecordPalette.warmSecondaryDark,
    secondaryFixedDim = RecordPalette.warmSecondaryDark,
    onSecondaryFixed = RecordPalette.onPrimaryContentDark,
    onSecondaryFixedVariant = RecordPalette.onPrimaryContentDark,
    tertiaryFixed = RecordPalette.warmSecondaryDark,
    tertiaryFixedDim = RecordPalette.warmSecondaryDark,
    onTertiaryFixed = RecordPalette.onPrimaryContentDark,
    onTertiaryFixedVariant = RecordPalette.onPrimaryContentDark,
)

/**
 * The A/A1 application theme. It follows the system light/dark setting with no
 * in-app toggle and no dynamic-color override; typography stays on the system
 * sans family.
 */
@Composable
fun RecordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColors else lightColors,
        content = content,
    )
}

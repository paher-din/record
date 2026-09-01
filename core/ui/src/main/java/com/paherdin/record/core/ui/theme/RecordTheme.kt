package com.paherdin.record.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = RecordPalette.PrimaryAccentLight,
    onPrimary = RecordPalette.OnPrimaryContentLight,
    primaryContainer = RecordPalette.PrimarySurfaceLight,
    onPrimaryContainer = RecordPalette.PrimaryContentLight,
    inversePrimary = RecordPalette.PrimaryAccentDark,
    secondary = RecordPalette.WarmSecondaryLight,
    onSecondary = RecordPalette.OnPrimaryContentLight,
    secondaryContainer = RecordPalette.PrimarySurfaceLight,
    onSecondaryContainer = RecordPalette.PrimaryContentLight,
    tertiary = RecordPalette.WarmSecondaryLight,
    onTertiary = RecordPalette.OnPrimaryContentLight,
    tertiaryContainer = RecordPalette.PrimarySurfaceLight,
    onTertiaryContainer = RecordPalette.PrimaryContentLight,
    background = RecordPalette.AppBackgroundLight,
    onBackground = RecordPalette.PrimaryContentLight,
    surface = RecordPalette.PrimarySurfaceLight,
    onSurface = RecordPalette.PrimaryContentLight,
    surfaceVariant = RecordPalette.PrimarySurfaceLight,
    onSurfaceVariant = RecordPalette.PrimaryContentLight,
    surfaceTint = RecordPalette.PrimaryAccentLight,
    inverseSurface = RecordPalette.PrimarySurfaceDark,
    inverseOnSurface = RecordPalette.PrimaryContentDark,
    outline = RecordPalette.PrimaryAccentLight,
    outlineVariant = RecordPalette.WarmSecondaryLight,
    scrim = RecordPalette.PrimaryContentLight,
    surfaceBright = RecordPalette.AppBackgroundLight,
    surfaceContainer = RecordPalette.PrimarySurfaceLight,
    surfaceContainerHigh = RecordPalette.PrimarySurfaceLight,
    surfaceContainerHighest = RecordPalette.PrimarySurfaceLight,
    surfaceContainerLow = RecordPalette.PrimarySurfaceLight,
    surfaceContainerLowest = RecordPalette.AppBackgroundLight,
    surfaceDim = RecordPalette.PrimarySurfaceLight,
    primaryFixed = RecordPalette.PrimaryAccentDark,
    primaryFixedDim = RecordPalette.PrimaryAccentDark,
    onPrimaryFixed = RecordPalette.OnPrimaryContentDark,
    onPrimaryFixedVariant = RecordPalette.OnPrimaryContentDark,
    secondaryFixed = RecordPalette.WarmSecondaryDark,
    secondaryFixedDim = RecordPalette.WarmSecondaryDark,
    onSecondaryFixed = RecordPalette.OnPrimaryContentDark,
    onSecondaryFixedVariant = RecordPalette.OnPrimaryContentDark,
    tertiaryFixed = RecordPalette.WarmSecondaryDark,
    tertiaryFixedDim = RecordPalette.WarmSecondaryDark,
    onTertiaryFixed = RecordPalette.OnPrimaryContentDark,
    onTertiaryFixedVariant = RecordPalette.OnPrimaryContentDark,
)

private val DarkColors = darkColorScheme(
    primary = RecordPalette.PrimaryAccentDark,
    onPrimary = RecordPalette.OnPrimaryContentDark,
    primaryContainer = RecordPalette.PrimarySurfaceDark,
    onPrimaryContainer = RecordPalette.PrimaryContentDark,
    inversePrimary = RecordPalette.PrimaryAccentLight,
    secondary = RecordPalette.WarmSecondaryDark,
    onSecondary = RecordPalette.OnPrimaryContentDark,
    secondaryContainer = RecordPalette.PrimarySurfaceDark,
    onSecondaryContainer = RecordPalette.PrimaryContentDark,
    tertiary = RecordPalette.WarmSecondaryDark,
    onTertiary = RecordPalette.OnPrimaryContentDark,
    tertiaryContainer = RecordPalette.PrimarySurfaceDark,
    onTertiaryContainer = RecordPalette.PrimaryContentDark,
    background = RecordPalette.AppBackgroundDark,
    onBackground = RecordPalette.PrimaryContentDark,
    surface = RecordPalette.PrimarySurfaceDark,
    onSurface = RecordPalette.PrimaryContentDark,
    surfaceVariant = RecordPalette.PrimarySurfaceDark,
    onSurfaceVariant = RecordPalette.PrimaryContentDark,
    surfaceTint = RecordPalette.PrimaryAccentDark,
    inverseSurface = RecordPalette.PrimarySurfaceLight,
    inverseOnSurface = RecordPalette.PrimaryContentLight,
    outline = RecordPalette.PrimaryAccentDark,
    outlineVariant = RecordPalette.WarmSecondaryDark,
    scrim = RecordPalette.PrimaryContentLight,
    surfaceBright = RecordPalette.PrimarySurfaceDark,
    surfaceContainer = RecordPalette.PrimarySurfaceDark,
    surfaceContainerHigh = RecordPalette.PrimarySurfaceDark,
    surfaceContainerHighest = RecordPalette.PrimarySurfaceDark,
    surfaceContainerLow = RecordPalette.PrimarySurfaceDark,
    surfaceContainerLowest = RecordPalette.AppBackgroundDark,
    surfaceDim = RecordPalette.AppBackgroundDark,
    primaryFixed = RecordPalette.PrimaryAccentDark,
    primaryFixedDim = RecordPalette.PrimaryAccentDark,
    onPrimaryFixed = RecordPalette.OnPrimaryContentDark,
    onPrimaryFixedVariant = RecordPalette.OnPrimaryContentDark,
    secondaryFixed = RecordPalette.WarmSecondaryDark,
    secondaryFixedDim = RecordPalette.WarmSecondaryDark,
    onSecondaryFixed = RecordPalette.OnPrimaryContentDark,
    onSecondaryFixedVariant = RecordPalette.OnPrimaryContentDark,
    tertiaryFixed = RecordPalette.WarmSecondaryDark,
    tertiaryFixedDim = RecordPalette.WarmSecondaryDark,
    onTertiaryFixed = RecordPalette.OnPrimaryContentDark,
    onTertiaryFixedVariant = RecordPalette.OnPrimaryContentDark,
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
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

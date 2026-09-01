package com.paherdin.record.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The frozen A/A1 warm-editorial semantic palette, by role. Feature code never
 * scatters literal colors; it references these roles. Error colors remain
 * Material semantic error roles rather than reusing [WarmSecondaryLight].
 */
object RecordPalette {
    // Light references.
    val AppBackgroundLight = Color(0xFFF6F2E9)
    val PrimarySurfaceLight = Color(0xFFEEE6D7)
    val PrimaryAccentLight = Color(0xFF145C4B)
    val WarmSecondaryLight = Color(0xFF925D39)
    val PrimaryContentLight = Color(0xFF24342F)
    val OnPrimaryContentLight = Color(0xFFFFF9EE)

    // Dark references.
    val AppBackgroundDark = Color(0xFF171C1A)
    val PrimarySurfaceDark = Color(0xFF222A27)
    val PrimaryAccentDark = Color(0xFF8BD0BB)
    val WarmSecondaryDark = Color(0xFFD2A987)
    val PrimaryContentDark = Color(0xFFE9EEE9)
    val OnPrimaryContentDark = Color(0xFF0D392F)
}

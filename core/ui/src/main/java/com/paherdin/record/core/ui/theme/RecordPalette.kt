package com.paherdin.record.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The frozen A/A1 warm-editorial semantic palette, by role. Feature code never
 * scatters literal colors; it references these roles. Error colors remain
 * Material semantic error roles rather than reusing [warmSecondaryLight].
 */
object RecordPalette {
    // Light references.
    val appBackgroundLight = Color(0xFFF6F2E9)
    val primarySurfaceLight = Color(0xFFEEE6D7)
    val primaryAccentLight = Color(0xFF145C4B)
    val warmSecondaryLight = Color(0xFF925D39)
    val primaryContentLight = Color(0xFF24342F)
    val onPrimaryContentLight = Color(0xFFFFF9EE)

    // Dark references.
    val appBackgroundDark = Color(0xFF171C1A)
    val primarySurfaceDark = Color(0xFF222A27)
    val primaryAccentDark = Color(0xFF8BD0BB)
    val warmSecondaryDark = Color(0xFFD2A987)
    val primaryContentDark = Color(0xFFE9EEE9)
    val onPrimaryContentDark = Color(0xFF0D392F)
}

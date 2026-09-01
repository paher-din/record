package com.paherdin.record.core.ui

import androidx.compose.ui.graphics.Color
import com.paherdin.record.core.ui.theme.RecordPalette
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordPaletteTest {

    @Test
    fun light_references_are_exact() {
        assertEquals(Color(0xFFF6F2E9), RecordPalette.AppBackgroundLight)
        assertEquals(Color(0xFFEEE6D7), RecordPalette.PrimarySurfaceLight)
        assertEquals(Color(0xFF145C4B), RecordPalette.PrimaryAccentLight)
        assertEquals(Color(0xFF925D39), RecordPalette.WarmSecondaryLight)
        assertEquals(Color(0xFF24342F), RecordPalette.PrimaryContentLight)
        assertEquals(Color(0xFFFFF9EE), RecordPalette.OnPrimaryContentLight)
    }

    @Test
    fun dark_references_are_exact() {
        assertEquals(Color(0xFF171C1A), RecordPalette.AppBackgroundDark)
        assertEquals(Color(0xFF222A27), RecordPalette.PrimarySurfaceDark)
        assertEquals(Color(0xFF8BD0BB), RecordPalette.PrimaryAccentDark)
        assertEquals(Color(0xFFD2A987), RecordPalette.WarmSecondaryDark)
        assertEquals(Color(0xFFE9EEE9), RecordPalette.PrimaryContentDark)
        assertEquals(Color(0xFF0D392F), RecordPalette.OnPrimaryContentDark)
    }

    @Test
    fun light_foreground_background_pairs_meet_contrast() {
        // High-emphasis text on root and grouped surfaces.
        assertTrue(contrastRatio(RecordPalette.PrimaryContentLight, RecordPalette.AppBackgroundLight) >= 4.5)
        assertTrue(contrastRatio(RecordPalette.PrimaryContentLight, RecordPalette.PrimarySurfaceLight) >= 4.5)
        // Content placed on the primary action/accent.
        assertTrue(contrastRatio(RecordPalette.OnPrimaryContentLight, RecordPalette.PrimaryAccentLight) >= 4.5)
        // Warm secondary emphasis on the app background (the approved darkened value).
        assertTrue(contrastRatio(RecordPalette.WarmSecondaryLight, RecordPalette.AppBackgroundLight) >= 4.5)
        // Focus emphasis / selected navigation.
        assertTrue(contrastRatio(RecordPalette.PrimaryAccentLight, RecordPalette.AppBackgroundLight) >= 3.0)
        assertTrue(contrastRatio(RecordPalette.PrimaryAccentLight, RecordPalette.PrimarySurfaceLight) >= 3.0)
    }

    @Test
    fun dark_foreground_background_pairs_meet_contrast() {
        assertTrue(contrastRatio(RecordPalette.PrimaryContentDark, RecordPalette.AppBackgroundDark) >= 4.5)
        assertTrue(contrastRatio(RecordPalette.PrimaryContentDark, RecordPalette.PrimarySurfaceDark) >= 4.5)
        assertTrue(contrastRatio(RecordPalette.OnPrimaryContentDark, RecordPalette.PrimaryAccentDark) >= 4.5)
        assertTrue(contrastRatio(RecordPalette.WarmSecondaryDark, RecordPalette.AppBackgroundDark) >= 4.5)
        assertTrue(contrastRatio(RecordPalette.PrimaryAccentDark, RecordPalette.AppBackgroundDark) >= 3.0)
        assertTrue(contrastRatio(RecordPalette.PrimaryAccentDark, RecordPalette.PrimarySurfaceDark) >= 3.0)
    }

    @Test
    fun warm_secondary_on_light_background_reaches_four_point_five_to_one() {
        // Spec 5.2 explicit requirement: the darkened light warm accent reaches >= 4.5:1 on the app background.
        val ratio = contrastRatio(RecordPalette.WarmSecondaryLight, RecordPalette.AppBackgroundLight)
        assertTrue("expected >= 4.5, got $ratio", ratio >= 4.5)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }
}

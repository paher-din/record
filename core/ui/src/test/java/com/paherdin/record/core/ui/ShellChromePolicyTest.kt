package com.paherdin.record.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellChromePolicyTest {

    @Test
    fun top_level_global_and_detail_show_bottom_bar() {
        for (kind in listOf(DestinationKind.TopLevel, DestinationKind.Global, DestinationKind.Detail)) {
            for (phase in Phase.entries) {
                assertEquals(
                    "kind=$kind phase=$phase",
                    ShellChrome(showBottomBar = true, allowBack = true),
                    ShellChromePolicy.resolve(kind, phase),
                )
            }
        }
    }

    @Test
    fun editor_hides_bottom_bar_and_blocks_back_while_saving() {
        assertEquals(
            ShellChrome(showBottomBar = false, allowBack = false),
            ShellChromePolicy.resolve(DestinationKind.Editor, Phase.Saving),
        )
    }

    @Test
    fun editor_hides_bottom_bar_and_allows_back_outside_saving() {
        for (phase in Phase.entries - Phase.Saving) {
            assertEquals(
                "phase=$phase",
                ShellChrome(showBottomBar = false, allowBack = true),
                ShellChromePolicy.resolve(DestinationKind.Editor, phase),
            )
        }
    }

    @Test
    fun progress_hides_bottom_bar_and_blocks_back_only_while_active() {
        for (phase in Phase.entries) {
            val expected = ShellChrome(
                showBottomBar = false,
                allowBack = phase != Phase.Running && phase != Phase.Cancelling,
            )
            assertEquals("phase=$phase", expected, ShellChromePolicy.resolve(DestinationKind.Progress, phase))
        }
    }

    @Test
    fun startup_never_constructs_shell() {
        for (phase in Phase.entries) {
            assertEquals("phase=$phase", ShellChrome(showBottomBar = false, allowBack = false), ShellChromePolicy.resolve(DestinationKind.Startup, phase))
        }
    }

    @Test
    fun destination_kind_tokens_are_exact() {
        assertEquals(
            listOf("TopLevel", "Global", "Detail", "Editor", "Progress", "Startup"),
            DestinationKind.entries.map { it.name },
        )
    }
}

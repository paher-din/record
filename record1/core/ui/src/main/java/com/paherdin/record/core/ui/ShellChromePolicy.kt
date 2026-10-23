package com.paherdin.record.core.ui

enum class DestinationKind { TopLevel, Global, Detail, Editor, Progress, Startup }

data class ShellChrome(val showBottomBar: Boolean, val allowBack: Boolean)

/**
 * The exhaustive chrome policy mapping destination kind and operation phase to
 * bottom-navigation/back availability. An unmapped production destination is a
 * build/test failure, never a default-visible fallback.
 */
object ShellChromePolicy {
    fun resolve(kind: DestinationKind, phase: Phase): ShellChrome = when (kind) {
        DestinationKind.TopLevel, DestinationKind.Global, DestinationKind.Detail ->
            ShellChrome(showBottomBar = true, allowBack = true)

        DestinationKind.Editor ->
            ShellChrome(showBottomBar = false, allowBack = phase != Phase.Saving)

        DestinationKind.Progress ->
            ShellChrome(
                showBottomBar = false,
                allowBack = phase != Phase.Running && phase != Phase.Cancelling,
            )

        DestinationKind.Startup ->
            ShellChrome(showBottomBar = false, allowBack = false)
    }
}

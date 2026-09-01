package com.paherdin.record.core.ui

/**
 * The immutable screen-body presentation family. It describes whether complete
 * body content is available; it never replaces or aliases [Phase].
 */
sealed interface ContentPresentation<out T> {
    data object Loading : ContentPresentation<Nothing>

    data class Empty(val reason: EmptyReason) : ContentPresentation<Nothing>

    data class Error(val category: UiErrorCategory, val canRetry: Boolean) : ContentPresentation<Nothing>

    data class Ready<T>(val content: T) : ContentPresentation<T>
}

enum class EmptyReason { NoApplicableItems }

enum class UiErrorCategory { TemporarilyUnavailable, RecoveryRequired }

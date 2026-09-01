package com.paherdin.record.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Semantic size/spacing/shape tokens shared across shell and feature surfaces.
 * Interactive controls must never fall below [minimumTouchTarget].
 */
object RecordTokens {
    val minimumTouchTarget = 48.dp
    val iconDefaultSize = 24.dp

    val spaceXSmall = 4.dp
    val spaceSmall = 8.dp
    val spaceMedium = 16.dp
    val spaceLarge = 24.dp

    val shapeSmall = RoundedCornerShape(8.dp)
    val shapeMedium = RoundedCornerShape(16.dp)
}

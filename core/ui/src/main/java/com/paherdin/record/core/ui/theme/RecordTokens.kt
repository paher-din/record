package com.paherdin.record.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Semantic size/spacing/shape tokens shared across shell and feature surfaces.
 * Interactive controls must never fall below [MinimumTouchTarget].
 */
object RecordTokens {
    val MinimumTouchTarget = 48.dp

    val SpaceXSmall = 4.dp
    val SpaceSmall = 8.dp
    val SpaceMedium = 16.dp
    val SpaceLarge = 24.dp

    val ShapeSmall = RoundedCornerShape(8.dp)
    val ShapeMedium = RoundedCornerShape(16.dp)
}

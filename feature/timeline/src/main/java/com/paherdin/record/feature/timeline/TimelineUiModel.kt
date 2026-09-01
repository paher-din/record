package com.paherdin.record.feature.timeline

/**
 * Source-typed representative card used to prove M1 source dispatch. Production
 * M1 renders [TimelineContent.Availability]; synthetic [TimelineContent.Cards]
 * values exist only in test source and never become product data.
 */
enum class TimelineCardKind { Transaction, Note }

data class TimelineCardUiModel(
    val id: String,
    val kind: TimelineCardKind,
    val headline: String,
)

sealed interface TimelineContent {
    data object Availability : TimelineContent
    data class Cards(val cards: List<TimelineCardUiModel>) : TimelineContent
}

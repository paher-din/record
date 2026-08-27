package com.paherdin.record.core.data.timeline

/**
 * Tech PoC P6 — benchmark-scoped value types that model the future M2/M3
 * timeline read shape. Lives only in `:core:data`'s `benchmark` source set;
 * absent from `main`/`release`.
 *
 * The canonical wire discriminator is exactly `TRANSACTION` | `NOTE` and the
 * card fields mirror `TimelineCardEntity` one-to-one so the typed reader's
 * canonical encoding reproduces the frozen oracle format byte-for-byte.
 */
enum class CardType(val wire: String) {
    TRANSACTION("TRANSACTION"),
    NOTE("NOTE"),
}

/** A non-empty local-day boundary in the flattened projection; date is `yyyy-MM-dd`. */
data class DayHeader(val date: String) : TimelineProjectionItem

/** A single timeline card (header-less); type discriminator is [CardType], never an ID prefix. */
data class TimelineCard(
    val id: String,
    val type: CardType,
    val occurredAt: Long,
    val title: String,
    val amountMinor: Long?,
    val body: String?,
) : TimelineProjectionItem

/** Flat projection item: either a [DayHeader] or a [TimelineCard]. */
sealed interface TimelineProjectionItem

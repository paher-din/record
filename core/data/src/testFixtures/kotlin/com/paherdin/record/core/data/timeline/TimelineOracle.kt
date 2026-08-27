package com.paherdin.record.core.data.timeline

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed interface TimelineOracleItem

data class TimelineOracleHeader(val date: LocalDate) : TimelineOracleItem

data class TimelineOracleCard(val record: TimelineFixtureRecord) : TimelineOracleItem

data class TimelineDayOracle(
    val date: LocalDate,
    val dayStart: Long,
    val nextDayStart: Long,
    val items: List<TimelineOracleItem>,
)

private val TIMELINE_RECORD_ORDER = Comparator<TimelineFixtureRecord> { a, b ->
    val byTime = b.occurredAt.compareTo(a.occurredAt)
    if (byTime != 0) byTime else a.id.compareTo(b.id)
}

/**
 * Tech PoC P6 — the independent pure-JVM oracle. Consumes fixture records but
 * implements its own sort (occurredAt DESC, then canonical lowercase UUID text
 * ascending under SQLite `BINARY`/ASCII lexical order — an explicit string
 * comparator, never `UUID.compareTo`), its own local-day grouping and its own
 * canonical encoding. It never calls `TimelineTypedReader`, Room, SQL, Compose
 * or any projection canonicalization.
 */
object TimelineOracle {

    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * The 11,000-item flattened artifact: one `H\t<yyyy-MM-dd>` header per
     * non-empty local day, then that day's cards as canonical C records, in
     * flattened (descending-day) order.
     */
    fun flatten(cards: List<TimelineFixtureRecord>): List<String> {
        val sorted = cards.sortedWith(TIMELINE_RECORD_ORDER)
        val lines = ArrayList<String>(cards.size + cards.size / TimelineFixtureGenerator.cardsPerDay + 1)
        var currentDate: String? = null
        for (card in sorted) {
            val date = Instant.ofEpochMilli(card.occurredAt)
                .atZone(zone).toLocalDate().format(dateFormatter)
            if (date != currentDate) {
                lines.add("H\t$date")
                currentDate = date
            }
            lines.add(TimelineFixtureGenerator.encodeC(card))
        }
        return lines
    }

    /** Canonical oracle stream: H/C records in flattened order, LF-terminated, UTF-8, LF only. */
    fun encodeOracle(cards: List<TimelineFixtureRecord>): ByteArray {
        val out = StringBuilder()
        for (line in flatten(cards)) {
            out.append(line).append('\n')
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun encodeDayOracle(day: TimelineDayOracle): ByteArray {
        val out = StringBuilder()
        for (item in day.items) {
            when (item) {
                is TimelineOracleHeader -> out.append("H\t").append(item.date).append('\n')
                is TimelineOracleCard -> out.append(TimelineFixtureGenerator.encodeC(item.record)).append('\n')
            }
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

}

fun TimelineOracle.selectedDay(date: LocalDate): TimelineDayOracle {
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val nextDayStart = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val cards = TimelineFixtureGenerator.generate()
        .asSequence()
        .filter { it.occurredAt >= dayStart && it.occurredAt < nextDayStart }
        .sortedWith(TIMELINE_RECORD_ORDER)
        .toList()
    val items = if (cards.isEmpty()) {
        emptyList()
    } else {
        buildList(cards.size + 1) {
            add(TimelineOracleHeader(date))
            cards.forEach { add(TimelineOracleCard(it)) }
        }
    }
    return TimelineDayOracle(date, dayStart, nextDayStart, items)
}

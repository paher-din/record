package com.paherdin.record.core.data.timeline

import android.os.Trace
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tech PoC P6 — the typed read boundary for the benchmark shadow projection.
 * Consumes the DAO's selected-day ordered query and groups it into one
 * `[DayHeader, Card]` state, or the declared empty state.
 *
 * Canonical encoding is an independent implementation of the frozen oracle
 * format (`TimelineOracle.encodeOracle`) — it deliberately does not import
 * `testFixtures`. The device sort is delegated to SQL (`occurredAt DESC,
 * id COLLATE BINARY ASC`); this reader never produces its own expected order,
 * and byte-identical encoding is what makes the SHA-256 comparison meaningful.
 */
object TimelineTypedReader {

    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    suspend fun readSelectedDay(
        dao: TimelineCardDao,
        dayStart: Long,
        nextDayStart: Long,
    ): List<TimelineProjectionItem> {
        Trace.beginSection("P6:SelectedDayQuery")
        val rows = try {
            dao.querySelectedDay(dayStart, nextDayStart)
        } finally {
            Trace.endSection()
        }
        Trace.beginSection("P6:SelectedDayGrouping")
        return try {
            materializeSelectedDay(rows, dayStart, nextDayStart)
        } finally {
            Trace.endSection()
        }
    }

    internal fun materializeSelectedDay(
        rows: List<TimelineCardEntity>,
        dayStart: Long,
        nextDayStart: Long,
    ): List<TimelineProjectionItem> {
        require(dayStart < nextDayStart) { "dayStart must be before nextDayStart" }
        if (rows.isEmpty()) return emptyList()

        val firstDate = localDate(rows.first())
        val items = ArrayList<TimelineProjectionItem>(rows.size + 1)
        items.add(DayHeader(firstDate))
        for (row in rows) {
            require(row.occurredAt >= dayStart && row.occurredAt < nextDayStart) {
                "selected-day query returned out-of-range row ${row.id}"
            }
            require(localDate(row) == firstDate) {
                "selected-day query returned more than one local date"
            }
            items.add(
                TimelineCard(
                    id = row.id,
                    type = CardType.valueOf(row.type),
                    occurredAt = row.occurredAt,
                    title = row.title,
                    amountMinor = row.amountMinor,
                    body = row.body,
                ),
            )
        }
        return items
    }

    private fun localDate(row: TimelineCardEntity): String =
        Instant.ofEpochMilli(row.occurredAt).atZone(zone).toLocalDate().format(dateFormatter)

    /**
     * Canonical projection stream: one `H\t<yyyy-MM-dd>` per non-empty local
     * day followed by that day's `C` records in flattened order, LF-terminated,
     * UTF-8, LF only. Byte-identical to the oracle's flattened artifact.
     */
    fun encodeCanonical(items: List<TimelineProjectionItem>): ByteArray {
        val out = StringBuilder()
        for (item in items) {
            when (item) {
                is DayHeader -> out.append("H\t").append(item.date).append('\n')
                is TimelineCard -> out.append(encodeC(item)).append('\n')
            }
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** Canonical C record: 6 tab-separated length-prefixed fields; nullable = `NULL` / `-1:`. */
    private fun encodeC(card: TimelineCard): String {
        val fields = listOf(
            encodeField(card.id),
            encodeField(card.type.wire),
            encodeField(card.occurredAt.toString()),
            encodeField(card.title),
            if (card.amountMinor == null) NULL_AMOUNT else encodeField(card.amountMinor.toString()),
            if (card.body == null) NULL_STRING else encodeField(card.body),
        )
        return "C\t" + fields.joinToString("\t")
    }

    private fun encodeField(value: String): String =
        "${value.toByteArray(Charsets.UTF_8).size}:$value"

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private const val NULL_AMOUNT = "NULL"
    private const val NULL_STRING = "-1:"
}

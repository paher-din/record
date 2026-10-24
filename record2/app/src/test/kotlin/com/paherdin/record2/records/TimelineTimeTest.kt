package com.paherdin.record2.records

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTimeTest {
    @Test
    fun dayRangeUsesZoneMidnightRatherThanFixedTwentyFourHours() {
        val start = TimelineTime.dayStart(LocalDate.parse("2026-10-24"), "Asia/Shanghai")
        val next = TimelineTime.nextDayStart(LocalDate.parse("2026-10-24"), "Asia/Shanghai")
        assertEquals(Instant.parse("2026-10-23T16:00:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-10-24T16:00:00Z").toEpochMilli(), next)
        assertEquals(24 * 60 * 60 * 1000L, next - start)
    }

    @Test
    fun dayRangeHandlesDstGapAndOverlapLengths() {
        val gap = TimelineTime.range(
            LocalDate.parse("2026-09-06"), LocalDate.parse("2026-09-07"), "America/Santiago",
        )
        assertEquals(23 * 60 * 60 * 1000L, gap.endExclusive - gap.startInclusive)

        val overlap = TimelineTime.range(
            LocalDate.parse("2026-04-04"), LocalDate.parse("2026-04-05"), "America/Santiago",
        )
        assertEquals(25 * 60 * 60 * 1000L, overlap.endExclusive - overlap.startInclusive)
    }

    @Test
    fun nextDayStartMatchesRangeEnd() {
        val range = TimelineTime.range(
            LocalDate.parse("2026-10-21"), LocalDate.parse("2026-10-22"), "Asia/Shanghai",
        )
        assertEquals(range.endExclusive, TimelineTime.nextDayStart(LocalDate.parse("2026-10-21"), "Asia/Shanghai"))
    }

    @Test
    fun invalidRangesAndZonesAreValidationErrors() {
        assertThrows(TimelineException::class.java) {
            TimelineTime.range(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-24"), "UTC")
        }.also { assertEquals(TimelineError.Validation, it.code) }
        assertThrows(TimelineException::class.java) {
            TimelineTime.dayStart(LocalDate.parse("2026-10-24"), "Not/AZone")
        }.also { assertEquals(TimelineError.Validation, it.code) }
        assertThrows(TimelineException::class.java) {
            TimelineTime.nextDayStart(LocalDate.MAX, "UTC")
        }.also { assertEquals(TimelineError.Validation, it.code) }
    }

    @Test
    fun keywordRulesFoldAsciiCaseAndEnforceTheSafetyBudget() {
        assertEquals("abc", TimelineQueryRules.keyword("ABC"))
        assertEquals("中文abc", TimelineQueryRules.keyword("中文ABC"))
        assertEquals("中文Ä", TimelineQueryRules.keyword("中文Ä"))
        assertNull(TimelineQueryRules.keyword(" \n "))
        TimelineQueryRules.requireKeywordWithinBudget("a".repeat(1_000))
        assertThrows(TimelineException::class.java) {
            TimelineQueryRules.requireKeywordWithinBudget("a".repeat(1_001))
        }.also { assertEquals(TimelineError.Validation, it.code) }
    }

    @Test
    fun queryRulesCanonicalizeBlankKeywordsAndZones() {
        assertNull(TimelineQueryRules.keyword(" \n\t "))
        assertEquals(" 线索\n第二行 ", TimelineQueryRules.keyword(" 线索\n第二行 "))
        assertNull(TimelineQueryRules.canonical(TimelineQuery(zoneId = "Asia/Shanghai")).keyword)
        assertEquals(
            "Asia/Shanghai",
            TimelineQueryRules.canonical(TimelineQuery(zoneId = "Asia/Shanghai")).zoneId,
        )
    }

    @Test
    fun filterDigestCoversEveryCursorBindingField() {
        val base = TimelineQuery(zoneId = "Asia/Shanghai")
        val digest = TimelineQueryRules.digest(base)
        assertEquals(64, digest.length)
        assertTrue(digest.all { it in "0123456789abcdef" })
        assertEquals(digest, TimelineQueryRules.digest(base.copy(keyword = "   ")))

        assertNotEquals(digest, TimelineQueryRules.digest(base.copy(kind = RecordKind.NOTE)))
        assertNotEquals(digest, TimelineQueryRules.digest(base.copy(keyword = "线索")))
        assertNotEquals(digest, TimelineQueryRules.digest(base.copy(range = TimelineRange(1, 2))))
        assertNotEquals(digest, TimelineQueryRules.digest(base.copy(zoneId = "UTC")))
        assertNotEquals(
            TimelineQueryRules.digest(base.copy(keyword = "a|1")),
            TimelineQueryRules.digest(base.copy(keyword = "a", range = TimelineRange(1, 2))),
        )
    }

    @Test
    fun cursorDigestBindsLastSortKeyAndGenerationState() {
        val query = TimelineQuery(zoneId = "Asia/Shanghai")
        val id = "00000000-0000-0000-0000-000000000001"
        val generation = "00000000-0000-0000-0000-0000000000aa"
        val digest = TimelineQueryRules.cursorDigest(query, 5, RecordKind.TRANSACTION, id, generation, 7)

        assertEquals(64, digest.length)
        assertNotEquals(digest, TimelineQueryRules.cursorDigest(query, 6, RecordKind.TRANSACTION, id, generation, 7))
        assertNotEquals(digest, TimelineQueryRules.cursorDigest(query, 5, RecordKind.NOTE, id, generation, 7))
        assertNotEquals(digest, TimelineQueryRules.cursorDigest(query, 5, RecordKind.TRANSACTION, id + "0", generation, 7))
        assertNotEquals(digest, TimelineQueryRules.cursorDigest(query, 5, RecordKind.TRANSACTION, id, generation, 8))
        assertNotEquals(digest, TimelineQueryRules.cursorDigest(query.copy(kind = RecordKind.NOTE), 5, RecordKind.TRANSACTION, id, generation, 7))
    }
}

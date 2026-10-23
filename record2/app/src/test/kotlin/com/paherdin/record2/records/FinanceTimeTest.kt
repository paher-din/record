package com.paherdin.record2.records

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FinanceTimeTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneId.of("UTC"))

    @Test fun gapsFutureMalformedAndSubMillisecondTimesAreRejected() {
        for ((local, zone) in listOf(
            "2026-03-08T02:30" to "America/New_York", "2026-10-24T00:00" to "UTC",
            "invalid" to "UTC", "2026-10-23T12:00" to "invalid",
            "2026-10-23T10:00:00.000000001" to "UTC",
        )) {
            assertEquals(FinanceError.Validation, assertThrows(FinanceException::class.java) { FinanceTime.resolve(local, zone, clock) }.code)
        }
    }

    @Test fun overlapsUseEarlierOffsetAndDayRangesAreNotAssumedToBe24Hours() {
        val overlap = FinanceTime.resolve("2025-11-02T01:30", "America/New_York", clock)
        assertEquals(Instant.parse("2025-11-02T05:30:00Z").toEpochMilli(), overlap.occurredAt)
        assertEquals(-4 * 3600, overlap.offsetSeconds)
        val zone = ZoneId.of("America/New_York")
        for ((date, hours) in listOf("2026-03-08" to 23, "2025-11-02" to 25)) {
            val day = LocalDate.parse(date)
            assertEquals(hours.toLong(), java.time.Duration.between(day.atStartOfDay(zone), day.plusDays(1).atStartOfDay(zone)).toHours())
        }
    }

    @Test fun seedNamesDirectionsAndReservedIdentitiesAreFixedAndDistinct() {
        assertEquals("日常账户", FinanceSeeds.account.name)
        assertEquals(listOf("餐饮", "出行", "购物", "居家", "学习", "其他支出"), FinanceSeeds.categories.filter { it.direction == FinanceDirection.EXPENSE }.map { it.name })
        assertEquals(listOf("生活费", "工资", "其他收入"), FinanceSeeds.categories.filter { it.direction == FinanceDirection.INCOME }.map { it.name })
        val ids = listOf(FinanceSeeds.ACCOUNT_ID) + FinanceSeeds.categories.map { it.id }
        assertEquals(10, ids.toSet().size)
        ids.forEach { assertEquals(it, java.util.UUID.fromString(it).toString()) }
    }
}

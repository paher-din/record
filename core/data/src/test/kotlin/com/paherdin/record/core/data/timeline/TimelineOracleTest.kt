package com.paherdin.record.core.data.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.TimeZone

/**
 * Tech PoC P6 (plan Task 2): the independent oracle's 11,000-item flattened
 * artifact — exact count/order/fields, 1,000 headers grouped by Asia/Shanghai
 * local day, UUID-text tie-break (never UUID.compareTo), the frozen top/bottom
 * sentinels, mutation detection, repeatability and the frozen oracle SHA-256.
 */
class TimelineOracleTest {

    private companion object {
        // Frozen by plan Task 2; cross-validated against an independent Java reference.
        const val FROZEN_ORACLE_SHA256 =
            "b5e7151de0251b5d81d7726e54ff1fd534580a0dad3bf5b8bfe39ce14ec7545d"
    }

    @Test
    fun flattenedArtifactIsExactlyElevenThousandItems() {
        val lines = TimelineOracle.flatten(TimelineFixtureGenerator.generate())
        assertEquals(11_000, lines.size)
        assertEquals(1_000, lines.count { it.startsWith("H\t") })
        assertEquals(10_000, lines.count { it.startsWith("C\t") })
    }

    @Test
    fun headersAreOnePerLocalDayDescending() {
        val lines = TimelineOracle.flatten(TimelineFixtureGenerator.generate())
        val headers = lines.filter { it.startsWith("H\t") }
        assertEquals(1_000, headers.size)
        assertEquals("H\t2025-09-26", headers.first())
        assertEquals("H\t2023-01-01", headers.last())
        val dates = headers.map { it.removePrefix("H\t") }
        dates.zipWithNext().forEach { (a, b) -> assertTrue("$a must be after $b", a > b) }
    }

    @Test
    fun everyDayBlockContainsExactlyTenCards() {
        val lines = TimelineOracle.flatten(TimelineFixtureGenerator.generate())
        var blockSize = 0
        var blocks = 0
        for (line in lines) {
            if (line.startsWith("H\t")) {
                if (blockSize != 0) assertEquals("a completed day block must hold 10 cards", 10, blockSize)
                blockSize = 0
                blocks++
            } else {
                blockSize++
            }
        }
        assertEquals("the final day block must hold 10 cards", 10, blockSize)
        assertEquals(1_000, blocks)
    }

    @Test
    fun cardsSortDescByTimeThenAscByUuidText() {
        val lines = TimelineOracle.flatten(TimelineFixtureGenerator.generate())
        val parsed = lines.filter { it.startsWith("C\t") }.map(::parseC)
        parsed.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "card ${a.id} (${a.occurredAt}) must precede ${b.id} (${b.occurredAt})",
                a.occurredAt > b.occurredAt || (a.occurredAt == b.occurredAt && a.id < b.id),
            )
        }
    }

    @Test
    fun tieAtEarliestTimestampOrdersByUuidTextAscending() {
        val lines = TimelineOracle.flatten(TimelineFixtureGenerator.generate())
        val lastC = lines.last { it.startsWith("C\t") }
        val secondLastC = lines.dropLast(1).last { it.startsWith("C\t") }
        // Day 0 slots 0/1 tie at the earliest timestamp; index 1 (0d47...) sorts
        // before index 0 (5116...), so index 0 is the terminal card.
        assertTrue("second-to-last card is index 1", secondLastC.startsWith("C\t36:0d478e01-52e8-3da5-a5ef-09060cbd250a\t"))
        assertTrue("terminal card is index 0", lastC.startsWith("C\t36:5116ef58-5c4c-3c25-bce0-90978ba33e89\t"))
    }

    @Test
    fun flattenedTopAndBottomMatchFrozenSentinels() {
        val lines = TimelineOracle.flatten(TimelineFixtureGenerator.generate())
        val cardLines = lines.filter { it.startsWith("C\t") }
        assertTrue("top card is index 9999", cardLines.first().startsWith("C\t36:8ad0ba0d-1a93-30f7-8671-678259ad3846\t"))
        assertTrue("bottom card is index 0", cardLines.last().startsWith("C\t36:5116ef58-5c4c-3c25-bce0-90978ba33e89\t"))
    }

    @Test
    fun headersGroupByAsiaShanghaiLocalDate() {
        val lines = TimelineOracle.flatten(TimelineFixtureGenerator.generate())
        // Day 999 (2025-09-26) is the top block: header + its 10 cards, then the next header.
        assertEquals("H\t2025-09-26", lines[0])
        assertEquals(10, lines.subList(0, 11).count { it.startsWith("C\t") })
        assertEquals("H\t2025-09-25", lines[11])
        // The terminal block (2023-01-01) closes the artifact with 10 cards.
        val lastIdx = lines.indexOf("H\t2023-01-01")
        assertTrue(lastIdx >= 0)
        assertEquals(10, lines.subList(lastIdx, lines.size).count { it.startsWith("C\t") })
    }

    @Test
    fun oracleIsRepeatable() {
        val a = TimelineOracle.encodeOracle(TimelineFixtureGenerator.generate())
        val b = TimelineOracle.encodeOracle(TimelineFixtureGenerator.generate())
        assertEquals(a.toList(), b.toList())
    }

    @Test
    fun oracleHashMatchesFrozenValue() {
        val bytes = TimelineOracle.encodeOracle(TimelineFixtureGenerator.generate())
        val sha = sha256Hex(bytes)
        println("timeline-v1 oracle SHA-256: $sha")
        assertEquals(FROZEN_ORACLE_SHA256, sha)
    }

    @Test
    fun swappingAdjacentFlattenedItemsChangesTheOracle() {
        val cards = TimelineFixtureGenerator.generate()
        val original = TimelineOracle.encodeOracle(cards)
        val lines = TimelineOracle.flatten(cards).toMutableList()
        val n = lines.size
        val tmp = lines[n - 2]
        lines[n - 2] = lines[n - 1]
        lines[n - 1] = tmp
        assertNotEquals(sha256Hex(original), sha256Hex(encodeLines(lines)))
    }

    @Test
    fun missingDuplicateExtraAndFieldMutationsAreDetected() {
        val cards = TimelineFixtureGenerator.generate()
        val oracle = TimelineOracle.encodeOracle(cards)
        assertNotEquals("dropped card", sha256Hex(oracle), sha256Hex(TimelineOracle.encodeOracle(cards.drop(1))))
        assertNotEquals("duplicated card", sha256Hex(oracle), sha256Hex(TimelineOracle.encodeOracle(cards + cards.first())))
        val extra = cards.toMutableList().also { it.add(it.first().copy(title = "入侵")) }
        assertNotEquals("extra card", sha256Hex(oracle), sha256Hex(TimelineOracle.encodeOracle(extra)))
        val changed = cards.toMutableList().also { it[5_000] = it[5_000].copy(amountMinor = 1L) }
        assertNotEquals("mutated amount", sha256Hex(oracle), sha256Hex(TimelineOracle.encodeOracle(changed)))
    }

    @Test
    fun mutatingATiedTimestampChangesTheOracle() {
        val cards = TimelineFixtureGenerator.generate().toMutableList()
        // Break the day-0 slots 0/1 tie by nudging card 1's timestamp +1 ms: the
        // sorted order and therefore the flattened artifact must change.
        cards[1] = cards[1].copy(occurredAt = cards[1].occurredAt + 1)
        assertNotEquals(
            sha256Hex(TimelineOracle.encodeOracle(TimelineFixtureGenerator.generate())),
            sha256Hex(TimelineOracle.encodeOracle(cards)),
        )
    }

    @Test
    fun firstFixtureDaySliceUsesHalfOpenBoundsAndMatchesCompleteOracle() {
        val date = LocalDate.parse("2023-01-01")
        val oracle = TimelineOracle.selectedDay(date)
        val cards = oracle.items.filterIsInstance<TimelineOracleCard>().map { it.record }

        assertEquals(date, oracle.date)
        assertEquals(1_672_502_400_000L, oracle.dayStart)
        assertEquals(1_672_588_800_000L, oracle.nextDayStart)
        assertEquals(11, oracle.items.size)
        assertEquals(oracleLinesFor(date), oracle.items.map(::encodeOracleItem))
        assertTrue(cards.any { it.occurredAt == oracle.dayStart })
        assertTrue(cards.any { it.occurredAt == oracle.nextDayStart - 1 })
        assertTrue(cards.none { it.occurredAt >= oracle.nextDayStart })
        assertTrue(
            TimelineFixtureGenerator.generate()
                .filter { it.occurredAt == oracle.nextDayStart }
                .none { nextDayCard -> cards.any { it.id == nextDayCard.id } },
        )
    }

    @Test
    fun lastFixtureDaySliceUsesHalfOpenBoundsAndMatchesCompleteOracle() {
        val date = LocalDate.parse("2025-09-26")
        val oracle = TimelineOracle.selectedDay(date)
        val cards = oracle.items.filterIsInstance<TimelineOracleCard>().map { it.record }

        assertEquals(date, oracle.date)
        assertEquals(1_758_816_000_000L, oracle.dayStart)
        assertEquals(1_758_902_400_000L, oracle.nextDayStart)
        assertEquals(11, oracle.items.size)
        assertEquals(oracleLinesFor(date), oracle.items.map(::encodeOracleItem))
        assertTrue(cards.any { it.occurredAt == oracle.dayStart })
        assertTrue(cards.any { it.occurredAt == oracle.nextDayStart - 1 })
        assertTrue(cards.none { it.occurredAt >= oracle.nextDayStart })
    }

    @Test
    fun emptyDaySliceHasNoHeaderOrCard() {
        val date = LocalDate.parse("2022-12-31")
        val oracle = TimelineOracle.selectedDay(date)

        assertEquals(date, oracle.date)
        assertEquals(1_672_416_000_000L, oracle.dayStart)
        assertEquals(1_672_502_400_000L, oracle.nextDayStart)
        assertTrue(oracle.items.isEmpty())
    }

    @Test
    fun selectedDaySlicePreservesTimestampTieIdOrdering() {
        listOf(LocalDate.parse("2023-01-01"), LocalDate.parse("2025-09-26")).forEach { date ->
            val cards = TimelineOracle.selectedDay(date).items
                .filterIsInstance<TimelineOracleCard>()
                .map { it.record }
            val ties = cards.zipWithNext().filter { (a, b) -> a.occurredAt == b.occurredAt }

            assertTrue("$date must retain deterministic timestamp ties", ties.isNotEmpty())
            ties.forEach { (a, b) ->
                assertTrue("$date tie ${a.id}/${b.id} must use UUID text ascending", a.id < b.id)
            }
        }
    }

    @Test
    fun selectedDaySliceRejectsMutableDefaultZoneDependence() {
        val original = TimeZone.getDefault()
        try {
            val date = LocalDate.parse("2023-01-01")
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val losAngelesDefault = TimelineOracle.selectedDay(date)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val kiritimatiDefault = TimelineOracle.selectedDay(date)

            assertEquals(losAngelesDefault, kiritimatiDefault)
            assertEquals(1_672_502_400_000L, kiritimatiDefault.dayStart)
            assertEquals(1_672_588_800_000L, kiritimatiDefault.nextDayStart)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun emittedDayOracleManifestIsDeterministic() {
        val outputDir = File(
            System.getProperty("java.io.tmpdir"),
            "record-p6-day-oracle-test-${ProcessHandle.current().pid()}",
        )
        TimelineArtifactEmitter.emit(outputDir)
        val manifest = File(outputDir, "day-oracles/manifest.tsv")
        val firstEmission = manifest.readBytes()
        TimelineArtifactEmitter.emit(outputDir)

        assertEquals(firstEmission.toList(), manifest.readBytes().toList())
        assertEquals(
            listOf(
                "date\tdayStart\tnextDayStart\titemCount\tsha256",
                "2022-12-31\t1672416000000\t1672502400000\t0\t" +
                    sha256Hex(File(outputDir, "day-oracles/2022-12-31.txt").readBytes()),
                "2023-01-01\t1672502400000\t1672588800000\t11\t" +
                    sha256Hex(File(outputDir, "day-oracles/2023-01-01.txt").readBytes()),
                "2025-09-26\t1758816000000\t1758902400000\t11\t" +
                    sha256Hex(File(outputDir, "day-oracles/2025-09-26.txt").readBytes()),
                "",
            ),
            manifest.readText(Charsets.UTF_8).split('\n'),
        )
    }

    private data class ParsedCard(val id: String, val occurredAt: Long)

    private fun oracleLinesFor(date: LocalDate): List<String> {
        val lines = TimelineOracle.flatten(TimelineFixtureGenerator.generate())
        val headerIndex = lines.indexOf("H\t$date")
        if (headerIndex < 0) return emptyList()
        val nextHeaderIndex = lines.indexOfFirstFrom(headerIndex + 1) { it.startsWith("H\t") }
        return lines.subList(headerIndex, if (nextHeaderIndex < 0) lines.size else nextHeaderIndex)
    }

    private fun List<String>.indexOfFirstFrom(startIndex: Int, predicate: (String) -> Boolean): Int {
        for (index in startIndex until size) if (predicate(this[index])) return index
        return -1
    }

    private fun encodeOracleItem(item: TimelineOracleItem): String = when (item) {
        is TimelineOracleHeader -> "H\t${item.date}"
        is TimelineOracleCard -> TimelineFixtureGenerator.encodeC(item.record)
    }

    private fun parseC(line: String): ParsedCard {
        val fields = line.split("\t")
        return ParsedCard(
            id = fields[1].substringAfter(":"),
            occurredAt = fields[3].substringAfter(":").toLong(),
        )
    }

    private fun encodeLines(lines: List<String>): ByteArray =
        lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

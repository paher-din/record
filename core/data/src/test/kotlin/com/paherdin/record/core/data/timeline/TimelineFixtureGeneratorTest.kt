package com.paherdin.record.core.data.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

/**
 * Tech PoC P6 (plan Task 2): `timeline-v1` frozen fixture invariants —
 * seed/version/ZoneId, exact counts and kind mix, slot sets, oldest-edge
 * openings, day-edge milliseconds, cross-type ties, canonical UUID rules,
 * frozen spot records, note body/title rules, transfer safety, byte-length/LF
 * serialization, determinism and the frozen fixture SHA-256.
 */
class TimelineFixtureGeneratorTest {

    private companion object {
        // Frozen by plan Task 2; cross-validated against an independent Java reference.
        const val FROZEN_TIMELINE_V1_SHA256 =
            "32fd36deaab5798f54533931b2ab261cd24c759790179895d37f0f553430b1b9"
    }

    @Test
    fun frozenConstantsAreExact() {
        assertEquals(2026081606L, TimelineFixtureGenerator.seed)
        assertEquals("timeline-v1", TimelineFixtureGenerator.fixtureVersion)
        assertEquals("Asia/Shanghai", TimelineFixtureGenerator.zoneId)
        assertEquals("Asia/Shanghai", TimelineFixtureGenerator.zone.id)
        assertEquals(10_000, TimelineFixtureGenerator.cardCount)
        assertEquals(1_000, TimelineFixtureGenerator.dayCount)
        assertEquals(10, TimelineFixtureGenerator.cardsPerDay)
        assertEquals(6_000, TimelineFixtureGenerator.transactionCount)
        assertEquals(4_000, TimelineFixtureGenerator.noteCount)
        assertEquals(listOf(0, 2, 4, 5, 7, 9), TimelineFixtureGenerator.transactionSlots)
        assertEquals(listOf(1, 3, 6, 8), TimelineFixtureGenerator.noteSlots)
        assertEquals(
            listOf(0L, 0L, 21_600_000L, 32_400_000L, 43_200_000L, 54_000_000L, 43_200_000L, 64_800_000L, 75_600_000L, 86_399_999L),
            TimelineFixtureGenerator.slotOffsetsMs,
        )
        assertEquals(listOf("现金", "招商银行", "支付宝", "微信"), TimelineFixtureGenerator.accounts)
        assertEquals(listOf("餐饮", "交通", "购物", "居住"), TimelineFixtureGenerator.expenseCategories)
        assertEquals(listOf("工资", "奖金", "退款"), TimelineFixtureGenerator.incomeCategories)
    }

    @Test
    fun generateHasTenThousandCardsOverOneThousandDaysTenPerDay() {
        val cards = TimelineFixtureGenerator.generate()
        assertEquals(10_000, cards.size)
        val perDay = cards.withIndex().groupBy { (i, _) -> i / 10 }
        assertEquals(1_000, perDay.size)
        perDay.forEach { (d, list) -> assertEquals("day $d has 10 cards", 10, list.size) }
        cards.forEachIndexed { i, c ->
            val d = i / 10
            assertTrue("card $i >= dayStart($d)", c.occurredAt >= TimelineFixtureGenerator.dayStart(d))
            assertTrue("card $i < dayStart($d)+1day", c.occurredAt <= TimelineFixtureGenerator.dayStart(d) + 86_399_999L)
        }
    }

    @Test
    fun typeMixIsSixThousandTransactionsFourThousandNotes() {
        val cards = TimelineFixtureGenerator.generate()
        assertEquals(6_000, cards.count { it.type == "TRANSACTION" })
        assertEquals(4_000, cards.count { it.type == "NOTE" })
    }

    @Test
    fun kindMixIsFrozenTenThreeThousandEighteenHundredElevenNinety() {
        val kinds = TimelineFixtureGenerator.generate().withIndex()
            .mapNotNull { (i, _) -> TimelineFixtureGenerator.transactionKind(i) }
        assertEquals(6_000, kinds.size)
        assertEquals(10, kinds.count { it == TransactionKind.OPENING_BALANCE })
        assertEquals(3_000, kinds.count { it == TransactionKind.EXPENSE })
        assertEquals(1_800, kinds.count { it == TransactionKind.INCOME })
        assertEquals(1_190, kinds.count { it == TransactionKind.TRANSFER })
    }

    @Test
    fun slotsFollowTheFrozenSets() {
        TimelineFixtureGenerator.generate().forEachIndexed { i, c ->
            val s = i % 10
            val isTxn = c.type == "TRANSACTION"
            assertEquals("slot $s index $i transaction", isTxn, s in listOf(0, 2, 4, 5, 7, 9))
            assertEquals("slot $s index $i note", !isTxn, s in listOf(1, 3, 6, 8))
        }
    }

    @Test
    fun oldestTenChronologicalTransactionsAreOpeningBalance() {
        // Ordinals 0..9 = day 0's six transaction slots (i=0,2,4,5,7,9) + day 1's first four (i=10,12,14,15).
        listOf(0, 2, 4, 5, 7, 9, 10, 12, 14, 15).forEach { i ->
            assertEquals("index $i", TransactionKind.OPENING_BALANCE, TimelineFixtureGenerator.transactionKind(i))
        }
        // The next chronological transaction (day 1 slot 7, index 17) is the first expense.
        assertEquals(TransactionKind.EXPENSE, TimelineFixtureGenerator.transactionKind(17))
        // Frozen ordinal boundaries: last expense (3009) at index 5015, first income (3010) at 5017.
        assertEquals(TransactionKind.EXPENSE, TimelineFixtureGenerator.transactionKind(5015))
        assertEquals(TransactionKind.INCOME, TimelineFixtureGenerator.transactionKind(5017))
        // Last transaction (ordinal 5999) is index 9999.
        assertEquals(TransactionKind.TRANSFER, TimelineFixtureGenerator.transactionKind(9999))
        // Note slots carry no kind.
        assertEquals(null, TimelineFixtureGenerator.transactionKind(1))
    }

    @Test
    fun dayEdgesPinFirstAndLastMillisecond() {
        assertEquals(1_672_502_400_000L, TimelineFixtureGenerator.dayStart(0))
        assertEquals(1_672_588_800_000L, TimelineFixtureGenerator.dayStart(1))
        val cards = TimelineFixtureGenerator.generate()
        assertEquals(1_672_502_400_000L, cards[0].occurredAt) // day 0 slot 0 = local midnight
        assertEquals(1_672_588_799_999L, cards[9].occurredAt) // day 0 slot 9 = 1 ms before day 1
        assertEquals(1_672_588_800_000L, cards[10].occurredAt) // day 1 slot 0
        assertEquals(1_758_902_399_999L, cards[9999].occurredAt) // day 999 slot 9
        for (d in 0 until 1_000) {
            assertEquals("day $d slot 0", TimelineFixtureGenerator.dayStart(d), cards[d * 10].occurredAt)
            assertEquals("day $d slot 9", TimelineFixtureGenerator.dayStart(d) + 86_399_999L, cards[d * 10 + 9].occurredAt)
        }
    }

    @Test
    fun crossTypeTiesShareTimestamps() {
        val cards = TimelineFixtureGenerator.generate()
        for (d in 0 until 1_000) {
            val s0 = cards[d * 10 + 0]
            val s1 = cards[d * 10 + 1]
            assertEquals("day $d slots 0/1 tie", s0.occurredAt, s1.occurredAt)
            assertNotEquals("day $d slots 0/1 cross-type", s0.type, s1.type)
            val s4 = cards[d * 10 + 4]
            val s6 = cards[d * 10 + 6]
            assertEquals("day $d slots 4/6 tie", s4.occurredAt, s6.occurredAt)
            assertNotEquals("day $d slots 4/6 cross-type", s4.type, s6.type)
        }
    }

    @Test
    fun uuidsAreNameBasedDeterministicUniqueAndLowercase() {
        val cards = TimelineFixtureGenerator.generate()
        val ids = cards.map { it.id }
        assertEquals(10_000, ids.toSet().size)
        ids.forEach { id ->
            val uuid = UUID.fromString(id) // throws on malformed syntax
            assertEquals(3, uuid.version()) // nameUUIDFromBytes is a version-3 (MD5) UUID
            assertEquals(id, id.lowercase())
        }
        val again = TimelineFixtureGenerator.generate()
        assertEquals(ids, again.map { it.id })
    }

    @Test
    fun spotRecordsMatchFrozenCanonicalText() {
        val cards = TimelineFixtureGenerator.generate()
        assertEquals(
            "C\t36:5116ef58-5c4c-3c25-bce0-90978ba33e89\t11:TRANSACTION\t13:1672502400000\t22:微信 · 期初余额\t6:450853\t-1:",
            TimelineFixtureGenerator.encodeC(cards[0]),
        )
        assertEquals(
            "C\t36:1b7be401-f30e-308c-b840-1e882770553a\t4:NOTE\t13:1672632000000\t9:记录 16\tNULL\t36:# 记录 16\n第 1 天，第 6 槽。",
            TimelineFixtureGenerator.encodeC(cards[16]),
        )
        assertEquals(
            "C\t36:7d904ad6-b227-3626-8743-edce488f31b3\t11:TRANSACTION\t13:1672653600000\t19:支付宝 · 居住\t5:-6299\t-1:",
            TimelineFixtureGenerator.encodeC(cards[17]),
        )
        assertEquals(
            "C\t36:e4707802-8213-3d52-8dc6-4bd795c51ac5\t11:TRANSACTION\t13:1715853600000\t19:支付宝 · 退款\t6:155147\t-1:",
            TimelineFixtureGenerator.encodeC(cards[5017]),
        )
        assertEquals(
            "C\t36:8ad0ba0d-1a93-30f7-8671-678259ad3846\t11:TRANSACTION\t13:1758902399999\t23:现金 → 招商银行\t6:-49895\t-1:",
            TimelineFixtureGenerator.encodeC(cards[9999]),
        )
    }

    @Test
    fun noteTitlesDeriveFromFirstNonBlankLine() {
        TimelineFixtureGenerator.generate().forEachIndexed { i, c ->
            if (c.type == "NOTE") {
                val d = i / 10
                val s = i % 10
                assertEquals("note $i body exact", "# 记录 $i\n第 $d 天，第 $s 槽。", c.body)
                assertEquals("note $i title", "记录 $i", c.title)
                assertEquals("note $i amount null", null, c.amountMinor)
            }
        }
    }

    @Test
    fun transactionLabelsAndAmountSignsFollowFrozenRules() {
        TimelineFixtureGenerator.generate().forEachIndexed { i, c ->
            if (c.type != "TRANSACTION") return@forEachIndexed
            val kind = TimelineFixtureGenerator.transactionKind(i)!!
            assertEquals("txn $i body null", null, c.body)
            assertTrue("txn $i amount present", c.amountMinor != null)
            when (kind) {
                TransactionKind.OPENING_BALANCE -> {
                    assertTrue("opening $i title", c.title.endsWith(" · 期初余额"))
                    assertTrue("opening $i amount positive", c.amountMinor!! > 0)
                }
                TransactionKind.EXPENSE -> {
                    assertTrue("expense $i title joins account and category", c.title.contains(" · "))
                    assertTrue("expense $i amount negative", c.amountMinor!! < 0)
                }
                TransactionKind.INCOME -> {
                    assertTrue("income $i title joins account and category", c.title.contains(" · "))
                    assertTrue("income $i amount positive", c.amountMinor!! > 0)
                }
                TransactionKind.TRANSFER -> {
                    assertTrue("transfer $i title has arrow", c.title.contains(" → "))
                    val from = c.title.substringBefore(" → ")
                    val to = c.title.substringAfter(" → ")
                    assertTrue("transfer $i from is an account", from in TimelineFixtureGenerator.accounts)
                    assertTrue("transfer $i to is an account", to in TimelineFixtureGenerator.accounts)
                    assertNotEquals("transfer $i never to source", from, to)
                    assertTrue("transfer $i amount negative", c.amountMinor!! < 0)
                }
            }
        }
    }

    @Test
    fun canonicalSerializationIsUtf8LfOnlyLengthPrefixedAndDeterministic() {
        val first = TimelineFixtureGenerator.encodeFixture()
        val text = String(first, Charsets.UTF_8)
        assertFalse("no CR anywhere", text.contains('\r'))
        val header = "timeline-v1\n2026081606\nAsia/Shanghai\n"
        assertTrue("frozen stream header", text.startsWith(header))
        assertEquals("repeatable bytes", first.toList(), TimelineFixtureGenerator.encodeFixture().toList())
        // Fixture is exactly 3 header lines + 10,000 LF-terminated C records, so it
        // contains exactly 10,003 newline bytes if no embedded newlines existed —
        // but the 4,000 notes embed one newline each, so expect 14,003.
        assertEquals(14_003, text.count { it == '\n' })
    }

    @Test
    fun embeddedNoteNewlineIsDisambiguatedByByteLength() {
        val note = TimelineFixtureGenerator.generate()[16]
        val rec = TimelineFixtureGenerator.encodeC(note)
        assertEquals("record has exactly one embedded newline", 1, rec.count { it == '\n' })
        val bodyField = rec.substringAfterLast("\t")
        assertTrue("body field is length-prefixed", bodyField.startsWith("36:"))
        assertEquals(36, bodyField.substringAfter(":").toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun fixtureHashMatchesFrozenValue() {
        val bytes = TimelineFixtureGenerator.encodeFixture()
        val sha = sha256Hex(bytes)
        println("timeline-v1 fixture SHA-256: $sha")
        assertEquals(FROZEN_TIMELINE_V1_SHA256, sha)
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

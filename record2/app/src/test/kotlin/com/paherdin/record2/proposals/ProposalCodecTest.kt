package com.paherdin.record2.proposals

import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceInput
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalCodecTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private val account = "00000000-0000-4000-8000-000000000001"
    private val expense = "00000000-0000-4000-8000-000000000101"
    private val income = "00000000-0000-4000-8000-000000000107"
    private val storedTransaction = "11111111-2222-4333-8444-555555555555"
    private val storedNote = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

    private val goldenCanonical = """{"formatVersion":1,"operations":[{"type":"CREATE_TRANSACTION","localRef":"#t1","direction":"EXPENSE","amountMinor":5800,"accountId":"00000000-0000-4000-8000-000000000001","accountVersion":1,"categoryId":null,"categoryVersion":null,"memo":"午饭","time":{"occurredAt":1792729800000,"zoneId":"Asia/Shanghai","localDateTime":"2026-10-23T12:30","offsetSeconds":28800}},{"type":"CREATE_NOTE","localRef":"#n1","body":"买菜记录","time":{"occurredAt":1792729800000,"zoneId":"Asia/Shanghai","localDateTime":"2026-10-23T12:30","offsetSeconds":28800}},{"type":"LINK_RECORDS","pairs":[{"transaction":{"type":"LOCAL","localRef":"#t1"},"transactionVersion":1,"note":{"type":"LOCAL","localRef":"#n1"},"noteVersion":1}]}]}"""

    // Produced by `sha256sum` over the golden canonical bytes, not by ProposalCodec.
    private val goldenDigest = "ac2bbda5fa0c8a4017e5d0a725719c354b718d28cbf03b5df171fe3dd8455fa3"

    private fun uuid(index: Int) = "%08x-1111-4111-8111-%012x".format(index, index)

    private fun input(
        amount: String = "58.00",
        memo: String = "午饭",
        direction: FinanceDirection = FinanceDirection.EXPENSE,
        categoryId: String? = null,
        categoryVersion: Long? = null,
        localDateTime: String = "2026-10-23T12:30:00",
        zoneId: String = "Asia/Shanghai",
        accountId: String = account,
        accountVersion: Long = 1,
    ) = FinanceInput(amount, direction, accountId, accountVersion, categoryId, categoryVersion, memo, localDateTime, zoneId)

    private fun createTransaction(localRef: String = "#t1", amount: String = "58.00", memo: String = "午饭") =
        ProposalIntent.CreateTransaction(localRef, input(amount, memo))

    private fun createNote(localRef: String = "#n1", body: String = "买菜记录") =
        ProposalIntent.CreateNote(localRef, body, "2026-10-23T12:30:00", "Asia/Shanghai")

    private fun localPair(transaction: String = "#t1", note: String = "#n1") =
        RecordPair(RecordRef.Local(transaction), 1, RecordRef.Local(note), 1)

    private fun normalize(vararg intents: ProposalIntent) = ProposalCodec.normalize(intents.toList(), clock)
    private fun canonical(vararg intents: ProposalIntent) = ProposalCodec.canonical(normalize(*intents))

    private fun expect(code: ProposalError, block: () -> Unit) {
        val error = try {
            block()
            error("Expected $code")
        } catch (error: ProposalException) {
            error
        }
        assertEquals(code, error.code)
    }

    @Test
    fun canonicalEncodingIsFrozenByteForByte() {
        val canonical = canonical(createTransaction(), createNote(), ProposalIntent.LinkRecords(listOf(localPair())))
        assertEquals(goldenCanonical, canonical)
        assertEquals(711, canonical.toByteArray(Charsets.UTF_8).size)
        assertEquals(goldenDigest, ProposalCodec.digest(canonical))
        assertTrue(ProposalCodec.digest(canonical).matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun equivalentIntentOrderingsProduceIdenticalCanonicalBytesAndDigest() {
        val link = ProposalIntent.LinkRecords(listOf(localPair()))
        val forward = canonical(createTransaction(), createNote(), link)
        val reversed = canonical(link, createNote(), createTransaction())
        assertEquals(forward, reversed)
        assertEquals(forward, canonical(createNote(), link, createTransaction()))
        assertEquals(goldenDigest, ProposalCodec.digest(reversed))
    }

    @Test
    fun canonicalNestingDepthIsFrozenWithinBudget() {
        assertEquals(ProposalBudget.MAX_DEPTH, depth(goldenCanonical))
        val deepest = canonical(
            createTransaction(),
            createNote(),
            ProposalIntent.LinkRecords(listOf(localPair())),
            ProposalIntent.UnlinkRecords(listOf(RecordPair(RecordRef.Transaction(storedTransaction), 3, RecordRef.Note(storedNote), 7))),
        )
        assertEquals(ProposalBudget.MAX_DEPTH, depth(deepest))
    }

    @Test
    fun digestChangesWithEveryFrozenField() {
        val base = ProposalCodec.digest(canonical(createTransaction()))
        val variants = listOf(
            canonical(createTransaction(amount = "58.01")),
            canonical(createTransaction(memo = "晚饭")),
            canonical(ProposalIntent.CreateTransaction("#t1", input(categoryId = expense, categoryVersion = 1))),
            canonical(ProposalIntent.CreateTransaction("#t1", input(accountVersion = 2))),
            canonical(ProposalIntent.CreateTransaction("#t2", input())),
            canonical(createNote()),
        ).map { ProposalCodec.digest(it) }
        assertEquals(6, variants.distinct().size)
        assertEquals(7, (variants + base).distinct().size)
    }

    @Test
    fun amountTextBecomesIntegerMinorWithoutFloatingPoint() {
        val operation = normalize(createTransaction(amount = "92233720368547758.07")).single() as ProposalOperation.CreateTransaction
        assertEquals(Long.MAX_VALUE, operation.amountMinor)
        assertTrue(canonical(createTransaction(amount = "92233720368547758.07")).contains("\"amountMinor\":9223372036854775807"))
        for (bad in listOf("", "0", "-1", "1.234", "1e2", "1,000", "92233720368547758.08", " 58", "58 ", "+58", "58.")) {
            expect(ProposalError.Validation) { normalize(createTransaction(amount = bad)) }
        }
    }

    @Test
    fun timeIsResolvedOnceAndKeepsItsZoneExplanation() {
        val operation = normalize(createTransaction()).single() as ProposalOperation.CreateTransaction
        assertEquals(1792729800000L, operation.time.occurredAt)
        assertEquals("Asia/Shanghai", operation.time.zoneId)
        assertEquals("2026-10-23T12:30", operation.time.localDateTime)
        assertEquals(28800, operation.time.offsetSeconds)
        assertEquals(canonical(createTransaction()), canonical(ProposalIntent.CreateTransaction("#t1", input(localDateTime = "2026-10-23T12:30"))))
        expect(ProposalError.Validation) { normalize(ProposalIntent.CreateTransaction("#t1", input(localDateTime = "2026-10-24T00:00"))) }
        expect(ProposalError.Validation) { normalize(ProposalIntent.CreateTransaction("#t1", input(localDateTime = "2026-03-08T02:30", zoneId = "America/New_York"))) }
        expect(ProposalError.Validation) { normalize(ProposalIntent.CreateTransaction("#t1", input(zoneId = "Not/AZone"))) }
        val overlap = normalize(ProposalIntent.CreateTransaction("#t1", input(localDateTime = "2025-11-02T01:30", zoneId = "America/New_York"))).single() as ProposalOperation.CreateTransaction
        assertEquals(-14400, overlap.time.offsetSeconds)
    }

    @Test
    fun operationBudgetIsEnforced() {
        val maximum = (1..ProposalBudget.MAX_OPERATIONS).map { createNote("#n$it") }
        assertEquals(ProposalBudget.MAX_OPERATIONS, normalize(*maximum.toTypedArray()).size)
        expect(ProposalError.BudgetExceeded) { normalize(*(maximum + createNote("#overflow")).toTypedArray()) }
        expect(ProposalError.Validation) { normalize() }
    }

    @Test
    fun relationOperandBudgetIsEnforced() {
        val note = RecordRef.Note(storedNote)
        val pairs = (1..ProposalBudget.MAX_LINK_PAIRS).map { RecordPair(RecordRef.Transaction(uuid(it)), 1, note, 1) }
        assertEquals(pairs, (normalize(ProposalIntent.LinkRecords(pairs)).single() as ProposalOperation.LinkRecords).pairs)
        expect(ProposalError.BudgetExceeded) {
            normalize(ProposalIntent.LinkRecords(pairs + RecordPair(RecordRef.Transaction(uuid(33)), 1, note, 1)))
        }
        expect(ProposalError.Validation) { normalize(createTransaction(), ProposalIntent.LinkRecords(emptyList())) }
        expect(ProposalError.Validation) { normalize(ProposalIntent.UnlinkRecords(emptyList())) }
    }

    @Test
    fun mergedRelationBudgetIsRecheckedAfterIntentsCombine() {
        val note = RecordRef.Note(storedNote)
        fun pair(index: Int) = RecordPair(RecordRef.Transaction(uuid(index)), 1, note, 1)
        val pairs = (1..ProposalBudget.MAX_LINK_PAIRS).map { pair(it) }
        assertEquals(pairs, (normalize(
            ProposalIntent.LinkRecords(pairs.take(16)),
            ProposalIntent.LinkRecords(pairs.drop(16)),
        ).single() as ProposalOperation.LinkRecords).pairs)
        assertEquals(pairs, (normalize(
            ProposalIntent.LinkRecords(pairs),
            ProposalIntent.LinkRecords(pairs),
        ).single() as ProposalOperation.LinkRecords).pairs)
        // Each intent stays within the per-intent budget; only their merged union overflows it.
        expect(ProposalError.BudgetExceeded) {
            normalize(ProposalIntent.LinkRecords((1..16).map { pair(it) }), ProposalIntent.LinkRecords((17..33).map { pair(it) }))
        }
        expect(ProposalError.BudgetExceeded) {
            normalize(ProposalIntent.UnlinkRecords((1..16).map { pair(it) }), ProposalIntent.UnlinkRecords((17..33).map { pair(it) }))
        }
    }

    @Test
    fun textBudgetsAreEnforced() {
        val memo = normalize(createTransaction(memo = "记".repeat(ProposalBudget.MAX_MEMO_CHARS))).single() as ProposalOperation.CreateTransaction
        assertEquals(ProposalBudget.MAX_MEMO_CHARS, memo.memo.length)
        expect(ProposalError.BudgetExceeded) { normalize(createTransaction(memo = "记".repeat(ProposalBudget.MAX_MEMO_CHARS + 1))) }
        val body = normalize(createNote(body = "字".repeat(ProposalBudget.MAX_NOTE_BODY_CHARS))).single() as ProposalOperation.CreateNote
        assertEquals(ProposalBudget.MAX_NOTE_BODY_CHARS, body.body.length)
        expect(ProposalError.BudgetExceeded) { normalize(createNote(body = "字".repeat(ProposalBudget.MAX_NOTE_BODY_CHARS + 1))) }
    }

    @Test
    fun totalPayloadBytesStayBoundedWhenEveryOperandLimitIsMet() {
        val heavy = (1..ProposalBudget.MAX_OPERATIONS).map { createNote("#n$it", "字".repeat(ProposalBudget.MAX_NOTE_BODY_CHARS)) }
        expect(ProposalError.BudgetExceeded) { ProposalCodec.canonical(ProposalCodec.normalize(heavy, clock)) }
        val light = canonical(ProposalIntent.LinkRecords((1..ProposalBudget.MAX_LINK_PAIRS).map { RecordPair(RecordRef.Transaction(uuid(it)), 1, RecordRef.Note(storedNote), 1) }))
        assertTrue(light.toByteArray(Charsets.UTF_8).size <= ProposalBudget.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun localRefShapeIsRestricted() {
        for (bad in listOf("t1", "#", "##t1", "# t1", "#t1!", "#中文", "#" + "a".repeat(65))) {
            expect(ProposalError.Validation) { normalize(createNote(bad)) }
        }
        assertEquals(64, normalize(createNote("#" + "a".repeat(64))).let { (it.single() as ProposalOperation.CreateNote).localRef.length - 1 })
        normalize(createNote("#A-1_b"))
    }

    @Test
    fun existingIdentitiesMustBeCanonicalLowerCaseUuids() {
        for (bad in listOf(storedNote.uppercase(), "not-a-uuid", "", storedTransaction.dropLast(1), storedTransaction + "0")) {
            expect(ProposalError.Validation) { normalize(ProposalIntent.UpdateNote(bad, 1, "正文", "2026-10-23T12:30:00", "Asia/Shanghai")) }
        }
        normalize(ProposalIntent.UpdateNote(storedNote, 1, "正文", "2026-10-23T12:30:00", "Asia/Shanghai"))
    }

    @Test
    fun duplicateDanglingAndMismatchedLocalRefsAreRejected() {
        expect(ProposalError.Validation) { normalize(createTransaction("#t1"), createTransaction("#t1")) }
        expect(ProposalError.Validation) { normalize(createTransaction("#t1"), createNote("#t1")) }
        expect(ProposalError.Validation) { normalize(ProposalIntent.LinkRecords(listOf(localPair()))) }
        expect(ProposalError.Validation) { normalize(createNote("#n1"), ProposalIntent.LinkRecords(listOf(localPair()))) }
        expect(ProposalError.Validation) { normalize(createTransaction("#t1"), ProposalIntent.LinkRecords(listOf(localPair()))) }
        expect(ProposalError.Validation) {
            normalize(createTransaction(), ProposalIntent.LinkRecords(listOf(RecordPair(RecordRef.Note(storedNote), 1, RecordRef.Local("#n1"), 1))))
        }
        expect(ProposalError.Validation) {
            normalize(createNote(), ProposalIntent.LinkRecords(listOf(RecordPair(RecordRef.Local("#n1"), 1, RecordRef.Transaction(storedTransaction), 1))))
        }
        expect(ProposalError.Validation) {
            normalize(createTransaction(), createNote(), ProposalIntent.LinkRecords(listOf(RecordPair(RecordRef.Local("#t1"), 2, RecordRef.Local("#n1"), 1))))
        }
    }

    @Test
    fun resolveOrderRejectsAReferenceThatIsNotDeclaredBeforeUse() {
        val linkOnly = ProposalOperation.LinkRecords(listOf(localPair()))
        expect(ProposalError.Validation) { ProposalCodec.resolveOrder(listOf(linkOnly)) }
        val time = ProposalTime(1792729800000L, "Asia/Shanghai", "2026-10-23T12:30", 28800)
        val declared = ProposalCodec.resolveOrder(listOf(
            linkOnly,
            ProposalOperation.CreateNote("#n1", "正文", time),
            ProposalOperation.CreateTransaction("#t1", FinanceDirection.EXPENSE, 5800, account, 1, null, null, "", time),
        ))
        assertEquals(
            listOf("CreateTransaction", "CreateNote", "LinkRecords"),
            declared.map { it::class.simpleName },
        )
    }

    @Test
    fun contradictoryIntentsOnOneTargetAreInvalidAndIdenticalOnesMerge() {
        val first = ProposalIntent.UpdateTransaction(storedTransaction, 3, input(amount = "60.50"))
        expect(ProposalError.Validation) { normalize(first, ProposalIntent.UpdateTransaction(storedTransaction, 3, input(amount = "58.00"))) }
        expect(ProposalError.Validation) { normalize(first, ProposalIntent.UpdateTransaction(storedTransaction, 4, input(amount = "60.50"))) }
        assertEquals(1, normalize(first, first).size)
        val note = ProposalIntent.UpdateNote(storedNote, 7, "修订正文", "2026-10-23T12:30:00", "Asia/Shanghai")
        expect(ProposalError.Validation) { normalize(note, ProposalIntent.UpdateNote(storedNote, 7, "另一个正文", "2026-10-23T12:30:00", "Asia/Shanghai")) }
        assertEquals(1, normalize(note, note).size)
        expect(ProposalError.Validation) { normalize(createNote(body = "   \n\t ")) }
    }

    @Test
    fun relationSetsAreUniqueSortedAndMutuallyExclusive() {
        val existingPair = RecordPair(RecordRef.Transaction(storedTransaction), 3, RecordRef.Note(storedNote), 7)
        val secondPair = RecordPair(RecordRef.Transaction(storedTransaction), 3, RecordRef.Note("00000000-0000-4000-8000-0000000000ff"), 1)
        val merged = normalize(
            ProposalIntent.LinkRecords(listOf(secondPair, existingPair)),
            ProposalIntent.LinkRecords(listOf(existingPair)),
        ).single() as ProposalOperation.LinkRecords
        assertEquals(listOf(secondPair, existingPair), merged.pairs)
        expect(ProposalError.Validation) { normalize(ProposalIntent.LinkRecords(listOf(existingPair, existingPair.copy(noteVersion = 8)))) }
        assertEquals(
            listOf(existingPair),
            (normalize(ProposalIntent.LinkRecords(listOf(existingPair, existingPair))).single() as ProposalOperation.LinkRecords).pairs,
        )
        expect(ProposalError.Validation) {
            normalize(ProposalIntent.LinkRecords(listOf(existingPair)), ProposalIntent.UnlinkRecords(listOf(existingPair)))
        }
        assertEquals(2, normalize(ProposalIntent.LinkRecords(listOf(existingPair)), ProposalIntent.UnlinkRecords(listOf(secondPair))).size)
    }

    @Test
    fun categoryIdentityAndVersionMustAgree() {
        normalize(ProposalIntent.CreateTransaction("#t1", input(direction = FinanceDirection.INCOME, categoryId = income, categoryVersion = 1)))
        normalize(ProposalIntent.CreateTransaction("#t1", input(categoryId = expense, categoryVersion = 1)))
        expect(ProposalError.Validation) { normalize(ProposalIntent.CreateTransaction("#t1", input(categoryId = expense))) }
        expect(ProposalError.Validation) { normalize(ProposalIntent.CreateTransaction("#t1", input(categoryVersion = 1))) }
        expect(ProposalError.Validation) { normalize(ProposalIntent.CreateTransaction("#t1", input(categoryId = expense, categoryVersion = 0))) }
        expect(ProposalError.Validation) { normalize(ProposalIntent.CreateTransaction("#t1", input(categoryId = expense, categoryVersion = Long.MAX_VALUE))) }
        expect(ProposalError.Validation) { normalize(ProposalIntent.UpdateTransaction(storedTransaction, 0, input())) }
        expect(ProposalError.Validation) { normalize(ProposalIntent.UpdateTransaction(storedTransaction, Long.MAX_VALUE, input())) }
        expect(ProposalError.Validation) { normalize(ProposalIntent.UpdateNote(storedNote, -1, "正文", "2026-10-23T12:30:00", "Asia/Shanghai")) }
    }

    @Test
    fun canonicalTextEscapesControlCharactersAndPreservesNonAscii() {
        val memo = "引号\"反斜杠\\换行\n制表\t控制\u0001字符 中文与空格  "
        val canonical = canonical(createTransaction(memo = memo))
        assertTrue(canonical.contains("\"memo\":\"引号\\\"反斜杠\\\\换行\\n制表\\t控制\\u0001字符 中文与空格  \""))
        val decoded = ProposalCodec.decode(canonical).single() as ProposalOperation.CreateTransaction
        assertEquals(memo, decoded.memo)
        assertEquals(canonical, ProposalCodec.canonical(listOf(decoded)))
    }

    @Test
    fun decodeRoundTripsAndRejectsAnythingOutsideTheClosedFormat() {
        val operations = normalize(
            createTransaction(),
            createNote(),
            ProposalIntent.LinkRecords(listOf(localPair())),
            ProposalIntent.UnlinkRecords(listOf(RecordPair(RecordRef.Transaction(storedTransaction), 3, RecordRef.Note(storedNote), 7))),
        )
        val canonical = ProposalCodec.canonical(operations)
        assertEquals(operations, ProposalCodec.decode(canonical))
        assertEquals(canonical, ProposalCodec.canonical(ProposalCodec.decode(canonical)))
        for (corrupt in listOf(
            canonical.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            canonical.replace("\"CREATE_NOTE\"", "\"DELETE_RECORD\""),
            canonical.replace("\"body\":", "\"unexpected\":0,\"body\":"),
            canonical.replace("\"amountMinor\":5800", "\"amountMinor\":58.00"),
            ProposalCodec.canonical(listOf(ProposalOperation.LinkRecords(
                (1..ProposalBudget.MAX_LINK_PAIRS + 1).map { RecordPair(RecordRef.Transaction(uuid(it)), 1, RecordRef.Note(storedNote), 1) },
            ))),
            ProposalCodec.canonical(listOf(ProposalOperation.UnlinkRecords(emptyList()))),
            """{"formatVersion":1,"operations":[]}""",
            """{"formatVersion":1}""",
            "",
            "{}",
            "not json",
        )) {
            expect(ProposalError.RecoveryRequired) { ProposalCodec.decode(corrupt) }
        }
    }

    private fun depth(json: String): Int {
        var depth = 0
        var maximum = 0
        var inString = false
        var escaped = false
        for (character in json) {
            if (escaped) {
                escaped = false
                continue
            }
            if (inString && character == '\\') {
                escaped = true
                continue
            }
            if (character == '"') inString = !inString
            if (inString) continue
            if (character == '{' || character == '[') maximum = maxOf(maximum, ++depth)
            if (character == '}' || character == ']') depth--
        }
        assertEquals(0, depth)
        return maximum
    }
}

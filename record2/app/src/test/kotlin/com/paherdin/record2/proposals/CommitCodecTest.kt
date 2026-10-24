package com.paherdin.record2.proposals

import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceInput
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class CommitCodecTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private val account = "00000000-0000-4000-8000-000000000001"
    private val storedTransaction = "11111111-2222-4333-8444-555555555555"
    private val storedNote = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val newTransaction = "10000000-0000-4000-8000-000000000001"
    private val newNote = "10000000-0000-4000-8000-000000000002"

    private val displayedDigest = "ac2bbda5fa0c8a4017e5d0a725719c354b718d28cbf03b5df171fe3dd8455fa3"

    // Produced by sha256sum over the golden frozen bytes, not by CommitCodec.
    private val goldenFrozen = """{"formatVersion":1,"displayedDigest":"$displayedDigest","idAssignments":{"#n1":"$newNote","#t1":"$newTransaction"},"operations":[{"type":"CREATE_TRANSACTION","id":"$newTransaction","direction":"EXPENSE","amountMinor":5800,"accountId":"$account","accountVersion":1,"categoryId":null,"categoryVersion":null,"memo":"午饭","time":{"occurredAt":1792729800000,"zoneId":"Asia/Shanghai","localDateTime":"2026-10-23T12:30","offsetSeconds":28800}},{"type":"CREATE_NOTE","id":"$newNote","body":"买菜记录","time":{"occurredAt":1792729800000,"zoneId":"Asia/Shanghai","localDateTime":"2026-10-23T12:30","offsetSeconds":28800}},{"type":"LINK_RECORDS","pairs":[{"transactionId":"$newTransaction","transactionVersion":1,"noteId":"$newNote","noteVersion":1}]}]}"""
    private val goldenFrozenDigest = "de681c0462957c1f2a49033f39562a5b73208268428c80d42e71ba6a0a67867a"

    private fun input(amount: String = "58.00", memo: String = "午饭") =
        FinanceInput(amount, FinanceDirection.EXPENSE, account, 1, null, null, memo, "2026-10-23T12:30:00", "Asia/Shanghai")

    private fun goldenOperations() = ProposalCodec.normalize(
        listOf(
            ProposalIntent.CreateTransaction("#t1", input()),
            ProposalIntent.CreateNote("#n1", "买菜记录", "2026-10-23T12:30:00", "Asia/Shanghai"),
            ProposalIntent.LinkRecords(listOf(RecordPair(RecordRef.Local("#t1"), 1, RecordRef.Local("#n1"), 1))),
        ),
        clock,
    )

    private fun fixedIds() = sequenceOf(newTransaction, newNote).iterator()::next

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun expectRecovery(block: () -> Unit) {
        val error = try {
            block()
            error("Expected RecoveryRequired")
        } catch (error: CommitException) {
            error
        }
        assertEquals(CommitError.RecoveryRequired, error.code)
    }

    private fun expect(code: CommitError, block: () -> Unit) {
        val error = try {
            block()
            error("Expected $code")
        } catch (error: CommitException) {
            error
        }
        assertEquals(code, error.code)
    }

    @Test
    fun freezeReplacesLocalReferencesWithStableIdentitiesByteForByte() {
        val frozen = CommitCodec.freeze(goldenOperations(), displayedDigest, fixedIds())
        assertEquals(goldenFrozen, frozen.canonicalJson)
        assertEquals(972, frozen.canonicalJson.toByteArray(Charsets.UTF_8).size)
        assertEquals(goldenFrozenDigest, frozen.payloadDigest)
        // An independent MessageDigest in the test must reproduce the pinned digest.
        assertEquals(goldenFrozenDigest, sha256(frozen.canonicalJson))
        assertEquals(displayedDigest, frozen.displayedDigest)
        assertEquals(mapOf("#t1" to newTransaction, "#n1" to newNote), frozen.idAssignments)
    }

    @Test
    fun decodeRoundTripsTheGoldenDocumentAndItsAssignmentMap() {
        val decoded = CommitCodec.decode(goldenFrozen)
        assertEquals(goldenFrozen, decoded.canonicalJson)
        assertEquals(goldenFrozenDigest, decoded.payloadDigest)
        assertEquals(displayedDigest, decoded.displayedDigest)
        assertEquals(mapOf("#t1" to newTransaction, "#n1" to newNote), decoded.idAssignments)
        assertEquals(3, decoded.operations.size)
        assertEquals(FrozenOperation.CreateTransaction::class, decoded.operations[0]::class)
        assertEquals(newTransaction, (decoded.operations[0] as FrozenOperation.CreateTransaction).id)
        assertEquals(FrozenOperation.LinkRecords::class, decoded.operations[2]::class)
        assertEquals(newNote, (decoded.operations[2] as FrozenOperation.LinkRecords).pairs.single().noteId)
        assertEquals(FrozenCommit(displayedDigest, goldenFrozen, goldenFrozenDigest, decoded.idAssignments, decoded.operations), decoded)
    }

    @Test
    fun freezeKeepsExistingIdentitiesAndBasesPairVersionsOnTheDisplayedPayload() {
        val operations = ProposalCodec.normalize(
            listOf(
                ProposalIntent.UpdateTransaction(storedTransaction, 4, input(amount = "60.50", memo = "改写说明")),
                ProposalIntent.UpdateNote(storedNote, 7, "修订正文", "2026-10-23T12:30:00", "Asia/Shanghai"),
                ProposalIntent.LinkRecords(listOf(RecordPair(RecordRef.Transaction(storedTransaction), 4, RecordRef.Local("#n1"), 1))),
                ProposalIntent.CreateNote("#n1", "买菜记录", "2026-10-23T12:30:00", "Asia/Shanghai"),
            ),
            clock,
        )
        // #n1 is the only creation here, so it takes the first minted identity.
        val frozen = CommitCodec.freeze(operations, displayedDigest, sequenceOf(newNote).iterator()::next)
        val decoded = CommitCodec.decode(frozen.canonicalJson)
        val update = decoded.operations.first { it is FrozenOperation.UpdateTransaction } as FrozenOperation.UpdateTransaction
        assertEquals(storedTransaction, update.id)
        assertEquals(4L, update.expectedVersion)
        val pair = (decoded.operations.first { it is FrozenOperation.LinkRecords } as FrozenOperation.LinkRecords).pairs.single()
        assertEquals(storedTransaction, pair.transactionId)
        assertEquals(4L, pair.transactionVersion)
        assertEquals(newNote, pair.noteId)
        assertEquals(1L, pair.noteVersion)
    }

    @Test
    fun freezeRejectsUndeclaredAndWrongKindLocalConsumersAndForeignDigests() {
        val time = ProposalTime(1792729800000, "Asia/Shanghai", "2026-10-23T12:30", 28800)
        // Hand-built lists bypass normalize so freeze's own dependency resolution is exercised.
        val dangling = listOf(
            ProposalOperation.LinkRecords(listOf(RecordPair(RecordRef.Local("#t1"), 1, RecordRef.Local("#n1"), 1))),
        )
        expect(CommitError.Validation) { CommitCodec.freeze(dangling, displayedDigest, fixedIds()) }
        val wrongKind = listOf(
            ProposalOperation.CreateNote("#n1", "正文", time),
            ProposalOperation.LinkRecords(listOf(RecordPair(RecordRef.Local("#n1"), 1, RecordRef.Local("#n1"), 1))),
        )
        expect(CommitError.Validation) { CommitCodec.freeze(wrongKind, displayedDigest, fixedIds()) }
        val malformedIdentity = listOf(ProposalOperation.CreateNote("#n1", "正文", time))
        expect(CommitError.Validation) { CommitCodec.freeze(malformedIdentity, displayedDigest, { "not-an-identity" }) }
        expect(CommitError.Validation) { CommitCodec.freeze(malformedIdentity, "not-hex", fixedIds()) }
    }

    @Test
    fun decodeFailsClosedOnForeignOrCorruptDocuments() {
        val corruptions = listOf(
            "{}",
            """{"formatVersion":2,"displayedDigest":"$displayedDigest","idAssignments":{},"operations":[]}""",
            """{"formatVersion":1,"displayedDigest":"$displayedDigest","idAssignments":{},"operations":[{"type":"DELETE_RECORD"}]}""",
            goldenFrozen.replace("\"displayedDigest\":\"$displayedDigest\"", "\"displayedDigest\":\"${"g".repeat(64)}\""),
            goldenFrozen.replace("\"memo\":\"午饭\"", "\"unexpected\":0,\"memo\":\"午饭\""),
            goldenFrozen.replace("\"amountMinor\":5800", "\"amountMinor\":0"),
            goldenFrozen.replace("\"accountVersion\":1", "\"accountVersion\":0"),
            goldenFrozen.replace("\"occurredAt\":1792729800000", "\"occurredAt\":1792729800001"),
            goldenFrozen.replace("\"offsetSeconds\":28800", "\"offsetSeconds\":28801"),
            goldenFrozen.replace("\"$newTransaction\"", "\"${"f".repeat(36)}\""),
            goldenFrozen.replace("\"noteVersion\":1", "\"noteVersion\":0"),
            // Assignment no longer matches the CREATE identities.
            goldenFrozen.replace("\"#t1\":\"$newTransaction\"", "\"#t1\":\"$newNote\""),
            // Assignment keys out of canonical order survive re-encoding only if canonical too.
            goldenFrozen.replace("\"#n1\":\"$newNote\",\"#t1\":\"$newTransaction\"", "\"#t1\":\"$newTransaction\",\"#n1\":\"$newNote\""),
            // A body widened past the budget cannot be read back.
            goldenFrozen.replace("\"body\":\"买菜记录\"", "\"body\":\"${"字".repeat(ProposalBudget.MAX_NOTE_BODY_CHARS + 1)}\""),
            goldenFrozen + " ",
        )
        for (corruption in corruptions) {
            expectRecovery { CommitCodec.decode(corruption) }
        }
        // A pair endpoint version B would find stale is a business conflict at execution, not a
        // decode failure; the document itself stays readable and byte-stable.
        val staleEndpoint = goldenFrozen.replace("\"transactionVersion\":1", "\"transactionVersion\":2")
        assertEquals(staleEndpoint, CommitCodec.decode(staleEndpoint).canonicalJson)
    }

    @Test
    fun decodedOperationsKeepTheClosedSetInvariants() {
        // A duplicated pair inside one LINK operation is foreign even when every byte is canonical.
        val duplicatePair = goldenFrozen.replace(
            "\"pairs\":[{\"transactionId\":\"$newTransaction\",\"transactionVersion\":1,\"noteId\":\"$newNote\",\"noteVersion\":1}]}",
            "\"pairs\":[{\"transactionId\":\"$newTransaction\",\"transactionVersion\":1,\"noteId\":\"$newNote\",\"noteVersion\":1}," +
                "{\"transactionId\":\"$newTransaction\",\"transactionVersion\":1,\"noteId\":\"$newNote\",\"noteVersion\":1}]}",
        )
        expectRecovery { CommitCodec.decode(duplicatePair) }
        val malformedIdentity = goldenFrozen.replace("\"$newTransaction\"", "\"${"f".repeat(36)}\"")
        expectRecovery { CommitCodec.decode(malformedIdentity) }
    }

    @Test
    fun appliedResultCodecIsDeterministicAndClosed() {
        val result = AppliedCommitResult(
            12,
            listOf(
                AppliedOutcome.CreateTransaction(newTransaction, 1),
                AppliedOutcome.UpdateNote(storedNote, 8),
                AppliedOutcome.LinkRecords(1),
                AppliedOutcome.UnlinkRecords(0),
            ),
        )
        val canonical = CommitResultCodec.canonical(result)
        assertEquals(
            """{"businessRevision":12,"outcomes":[{"type":"CREATE_TRANSACTION","id":"$newTransaction","version":1},""" +
                """{"type":"UPDATE_NOTE","id":"$storedNote","version":8},{"type":"LINK_RECORDS","count":1},""" +
                """{"type":"UNLINK_RECORDS","count":0}]}""",
            canonical,
        )
        assertEquals(result, CommitResultCodec.decode(canonical))
        assertEquals(canonical, CommitResultCodec.canonical(CommitResultCodec.decode(canonical)))
        expectRecovery { CommitResultCodec.decode("{}") }
        expectRecovery { CommitResultCodec.decode(canonical.replace("\"version\":1", "\"version\":0")) }
        expectRecovery { CommitResultCodec.decode(canonical.replace("\"count\":1", "\"count\":-1")) }
        expectRecovery { CommitResultCodec.decode(canonical.replace("\"businessRevision\":12", "\"businessRevision\":-1")) }
        expectRecovery { CommitResultCodec.decode(canonical.replace("\"id\":\"$newTransaction\"", "\"id\":\"not-an-id\"")) }
        assertEquals(4, CommitResultCodec.decode(canonical).outcomes.size)
    }

    @Test
    fun appliedReceiptsMustCorrespondOneToOneWithTheFrozenOperations() {
        val golden = CommitCodec.decode(goldenFrozen)
        CommitResultCodec.validateAgainst(
            AppliedCommitResult(
                2,
                listOf(
                    AppliedOutcome.CreateTransaction(newTransaction, 1),
                    AppliedOutcome.CreateNote(newNote, 1),
                    AppliedOutcome.LinkRecords(1),
                ),
            ),
            golden.operations,
        )
        val shortReceipt = AppliedCommitResult(2, listOf(AppliedOutcome.CreateNote(newNote, 1)))
        expectRecovery { CommitResultCodec.validateAgainst(shortReceipt, golden.operations) }
        val reordered = AppliedCommitResult(
            2,
            listOf(
                AppliedOutcome.CreateTransaction(newTransaction, 1),
                AppliedOutcome.LinkRecords(1),
                AppliedOutcome.CreateNote(newNote, 1),
            ),
        )
        expectRecovery { CommitResultCodec.validateAgainst(reordered, golden.operations) }
        val wrongIdentity = AppliedCommitResult(
            2,
            listOf(
                AppliedOutcome.CreateTransaction(newNote, 1),
                AppliedOutcome.CreateNote(newNote, 1),
                AppliedOutcome.LinkRecords(1),
            ),
        )
        expectRecovery { CommitResultCodec.validateAgainst(wrongIdentity, golden.operations) }
        val wrongCreateVersion = AppliedCommitResult(
            2,
            listOf(
                AppliedOutcome.CreateTransaction(newTransaction, 2),
                AppliedOutcome.CreateNote(newNote, 1),
                AppliedOutcome.LinkRecords(1),
            ),
        )
        expectRecovery { CommitResultCodec.validateAgainst(wrongCreateVersion, golden.operations) }
        val oversizedCount = AppliedCommitResult(
            2,
            listOf(
                AppliedOutcome.CreateTransaction(newTransaction, 1),
                AppliedOutcome.CreateNote(newNote, 1),
                AppliedOutcome.LinkRecords(2),
            ),
        )
        expectRecovery { CommitResultCodec.validateAgainst(oversizedCount, golden.operations) }

        // Update outcomes may carry either the unchanged baseline or the bumped version.
        val time = ProposalTime(1792729800000, "Asia/Shanghai", "2026-10-23T12:30", 28800)
        val updates = listOf(
            FrozenOperation.UpdateTransaction(storedTransaction, 3, FinanceDirection.EXPENSE, 6050, account, 1, null, null, "改写说明", time),
            FrozenOperation.UpdateNote(storedNote, 7, "修订正文", time),
        )
        CommitResultCodec.validateAgainst(
            AppliedCommitResult(2, listOf(AppliedOutcome.UpdateTransaction(storedTransaction, 4), AppliedOutcome.UpdateNote(storedNote, 7))),
            updates,
        )
        CommitResultCodec.validateAgainst(
            AppliedCommitResult(3, listOf(AppliedOutcome.UpdateTransaction(storedTransaction, 3), AppliedOutcome.UpdateNote(storedNote, 8))),
            updates,
        )
        val skippedVersion = AppliedCommitResult(2, listOf(AppliedOutcome.UpdateTransaction(storedTransaction, 5), AppliedOutcome.UpdateNote(storedNote, 7)))
        expectRecovery { CommitResultCodec.validateAgainst(skippedVersion, updates) }
        val swappedKinds = AppliedCommitResult(2, listOf(AppliedOutcome.UpdateNote(storedNote, 7), AppliedOutcome.UpdateTransaction(storedTransaction, 4)))
        expectRecovery { CommitResultCodec.validateAgainst(swappedKinds, updates) }
    }
}

package com.paherdin.record2.storage

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteFullException
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.proposals.ProposalBudget
import com.paherdin.record2.proposals.ProposalCodec
import com.paherdin.record2.proposals.ProposalError
import com.paherdin.record2.proposals.ProposalException
import com.paherdin.record2.proposals.ProposalIntent
import com.paherdin.record2.proposals.ProposalRunContext
import com.paherdin.record2.proposals.ProposalState
import com.paherdin.record2.proposals.ProposalView
import com.paherdin.record2.proposals.RecordPair
import com.paherdin.record2.proposals.RecordRef
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ProposalServiceContractTest {
    private val name = "c0-contract-${schemaId()}.db"
    private val path get() = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)
    private lateinit var database: RecordDatabase
    private lateinit var fixture: ProposalFixture
    private lateinit var service: RoomProposalService

    private val goldenCanonical = """{"formatVersion":1,"operations":[{"type":"CREATE_TRANSACTION","localRef":"#t1","direction":"EXPENSE","amountMinor":5800,"accountId":"00000000-0000-4000-8000-000000000001","accountVersion":1,"categoryId":null,"categoryVersion":null,"memo":"午饭","time":{"occurredAt":1792729800000,"zoneId":"Asia/Shanghai","localDateTime":"2026-10-23T12:30","offsetSeconds":28800}},{"type":"CREATE_NOTE","localRef":"#n1","body":"买菜记录","time":{"occurredAt":1792729800000,"zoneId":"Asia/Shanghai","localDateTime":"2026-10-23T12:30","offsetSeconds":28800}},{"type":"LINK_RECORDS","pairs":[{"transaction":{"type":"LOCAL","localRef":"#t1"},"transactionVersion":1,"note":{"type":"LOCAL","localRef":"#n1"},"noteVersion":1}]}]}"""
    private val goldenDigest = "ac2bbda5fa0c8a4017e5d0a725719c354b718d28cbf03b5df171fe3dd8455fa3"
    private val goldenIntents get() = listOf(fixture.createTransaction(), fixture.createNote(), fixture.linkNew())

    @Before
    fun open() {
        check(!path.exists())
        database = proposalDatabase(name)
        database.openHelper.writableDatabase
        fixture = ProposalFixture(database)
        fixture.seed()
        service = RoomProposalService(database, proposalClock)
    }

    @After
    fun close() {
        database.close()
    }

    private val context get() = fixture.context(fixture.run)
    private fun snapshot() = SchemaDiskObserver.snapshot(path)
    private fun rows(table: String) = snapshot().getValue(table)
    private fun proposals() = rows("change_proposal")
    private fun proposalRow() = proposals().single()
    private fun sql(statement: String, vararg args: Any?) = database.openHelper.writableDatabase.execSQL(statement, args)
    private fun code(result: Result<*>) = (result.exceptionOrNull() as? ProposalException)?.code
    private fun reopen() {
        database.close()
        database = proposalDatabase(name)
        fixture.rebind(database)
        service = RoomProposalService(database, proposalClock)
    }

    private suspend fun <T> onTwoConnections(
        first: suspend (RoomProposalService) -> T,
        second: suspend (RoomProposalService) -> T,
    ): List<Result<T>> {
        val other = proposalDatabase(name)
        try {
            val peer = RoomProposalService(other, proposalClock)
            return coroutineScope {
                listOf(async { runCatching { first(service) } }, async { runCatching { second(peer) } }).awaitAll()
            }
        } finally {
            other.close()
        }
    }

    @Test
    fun prepareWritesOneEditableProposalAndNoBusinessFactAtAll() = runBlocking {
        val seeded = snapshot()
        assertTrue(seeded.getValue("change_proposal").isEmpty())
        val view = service.prepare(context, goldenIntents)
        val row = proposalRow()
        assertEquals(view.id, row[0])
        assertEquals(fixture.session.id, row[1])
        assertEquals(fixture.run.id, row[2])
        assertEquals(fixture.run.id, row[3])
        assertEquals(fixture.run.proposalCreationRequestId, row[4])
        assertEquals(row[7], row[5])
        assertEquals(1L, row[6])
        assertEquals(goldenCanonical, row[7])
        assertEquals(goldenDigest, row[8])
        assertEquals(sha256(row[7] as String), row[8])
        assertEquals(1L, row[9])
        assertEquals(fixture.generation, row[10])
        assertEquals("EDITABLE", row[11])
        assertEquals(1L, row[12])
        assertEquals(proposalClock.millis(), row[13])
        assertEquals(proposalClock.millis(), row[14])
        assertEquals(
            ProposalView(
                view.id, fixture.session.id, fixture.run.id, fixture.run.id, fixture.run.proposalCreationRequestId,
                1, ProposalState.EDITABLE, 1, goldenDigest, fixture.generation,
                ProposalCodec.normalize(goldenIntents, proposalClock), proposalClock.millis(), proposalClock.millis(),
            ),
            view,
        )
        assertEquals(seeded.filterKeys { it != "change_proposal" }, snapshot().filterKeys { it != "change_proposal" })
        val draft = rows("native_finance_draft").single()
        assertEquals(fixture.draft.draftId, draft[1])
        assertEquals(4L, draft[2])
        assertEquals("EDITING", draft[3])
        assertNull(draft[9])
        assertEquals(view, service.activeProposal(fixture.generation, fixture.session.id))
        assertNull(service.activeProposal(fixture.generation, fixture.secondSession.id))
        val before = snapshot()
        reopen()
        assertEquals(view, service.proposal(view.id))
        assertEquals(view, service.activeProposal(fixture.generation, fixture.session.id))
        assertEquals(before, snapshot())
    }

    @Test
    fun repeatedPrepareReturnsTheCurrentRevisionAndRejectsDifferentInitialContent() = runBlocking {
        val original = listOf(fixture.createTransaction())
        val first = service.prepare(context, original)
        val revised = service.revise(context, first.id, 1, listOf(fixture.createTransaction(amount = "60.50")))
        assertEquals(2L, revised.revision)
        val repeated = service.prepare(context, original)
        assertEquals(first.id, repeated.id)
        assertEquals(2L, repeated.revision)
        assertEquals(revised.payloadDigest, repeated.payloadDigest)
        assertTrue((proposalRow()[5] as String).contains("\"amountMinor\":5800"))
        assertEquals(1, proposals().size)
        val before = snapshot()
        expectProposalError(ProposalError.CreationRequestConflict) { service.prepare(context, listOf(fixture.createTransaction(amount = "58.01"))) }
        expectProposalError(ProposalError.CreationRequestConflict) { service.prepare(context, listOf(fixture.createNote())) }
        expectProposalError(ProposalError.CreationRequestConflict) { service.prepare(context, original + fixture.createNote()) }
        assertEquals(before, snapshot())
        assertEquals(2L, proposalRow()[6])
        reopen()
        assertEquals(revised, service.proposal(first.id))
    }

    @Test
    fun concurrentPrepareOnOneCreationRequestYieldsExactlyOneProposal() = runBlocking {
        val results = onTwoConnections({ it.prepare(context, goldenIntents) }, { it.prepare(context, goldenIntents) })
        val views = results.map { it.getOrThrow() }
        assertEquals(views[0], views[1])
        assertEquals(1, proposals().size)
        assertEquals(1, proposals().count { it[12] == 1L })
        assertEquals(goldenDigest, proposalRow()[8])
        assertEquals(views[0], service.activeProposal(fixture.generation, fixture.session.id))
    }

    @Test
    fun concurrentPrepareWithDifferentContentNeverCreatesASecondIdentity() = runBlocking {
        val low = listOf(fixture.createTransaction(amount = "58.00"))
        val high = listOf(fixture.createTransaction(amount = "60.50"))
        val results = onTwoConnections({ it.prepare(context, low) }, { it.prepare(context, high) })
        assertEquals(1, results.count { it.exceptionOrNull() == null })
        assertEquals(1, results.count { code(it) == ProposalError.CreationRequestConflict })
        assertEquals(1, proposals().size)
        val winner = results.first { it.exceptionOrNull() == null }.getOrThrow()
        assertEquals(winner.payloadDigest, proposalRow()[8])
        val stored = proposalRow()[7] as String
        val loser = if (stored.contains("\"amountMinor\":5800")) high else low
        expectProposalError(ProposalError.CreationRequestConflict) { service.prepare(context, loser) }
        assertEquals(1, proposals().size)
        assertEquals(stored, proposalRow()[7])
    }

    @Test
    fun reviseUsesRevisionCasAndKeepsIdentityCreationPayloadAndOriginRun() = runBlocking {
        val first = service.prepare(context, listOf(fixture.createTransaction(amount = "58.00")))
        val second = service.revise(context, first.id, 1, listOf(fixture.createTransaction(amount = "60.50")))
        assertEquals(first.id, second.id)
        assertEquals(2L, second.revision)
        assertEquals(2L, proposalRow()[6])
        assertTrue((proposalRow()[5] as String).contains("\"amountMinor\":5800"))
        assertTrue((proposalRow()[7] as String).contains("\"amountMinor\":6050"))
        assertEquals(sha256(proposalRow()[7] as String), proposalRow()[8])
        assertEquals(fixture.run.id, proposalRow()[2])
        assertEquals(fixture.run.id, proposalRow()[3])
        assertEquals(first.createdAt, second.createdAt)
        val before = snapshot()
        expectProposalError(ProposalError.RevisionConflict) { service.revise(context, first.id, 1, listOf(fixture.createTransaction(amount = "70.00"))) }
        expectProposalError(ProposalError.RevisionConflict) { service.revise(context, first.id, 3, listOf(fixture.createTransaction())) }
        for (revision in listOf(0L, -1L, Long.MAX_VALUE)) {
            expectProposalError(ProposalError.RevisionConflict) { service.revise(context, first.id, revision, listOf(fixture.createTransaction())) }
        }
        expectProposalError(ProposalError.NotFound) { service.revise(context, UUID.randomUUID().toString(), 1, listOf(fixture.createTransaction())) }
        expectProposalError(ProposalError.RunInvalidated) { service.revise(fixture.context(fixture.secondRun), first.id, 2, listOf(fixture.createTransaction())) }
        assertEquals(before, snapshot())
        reopen()
        assertEquals(second, service.proposal(second.id))
        assertEquals(before, snapshot())
        sql("UPDATE agent_run SET state='COMPLETED', activeSlot=NULL, finishedAt=? WHERE id=?", fixture.time, fixture.run.id)
        val successor = fixture.newRun()
        val third = service.revise(fixture.context(successor), second.id, 2, listOf(fixture.createTransaction(amount = "88.00")))
        assertEquals(second.id, third.id)
        assertEquals(3L, third.revision)
        assertEquals(fixture.run.id, third.originRunId)
        assertEquals(successor.id, third.lastWriterRunId)
        assertEquals(fixture.run.id, proposalRow()[2])
        assertEquals(successor.id, proposalRow()[3])
        assertTrue((proposalRow()[5] as String).contains("\"amountMinor\":5800"))
    }

    @Test
    fun concurrentReviseOnOneExpectedRevisionHasExactlyOneWinner() = runBlocking {
        val first = service.prepare(context, listOf(fixture.createTransaction()))
        val results = onTwoConnections(
            { it.revise(context, first.id, 1, listOf(fixture.createTransaction(amount = "60.50"))) },
            { it.revise(context, first.id, 1, listOf(fixture.createTransaction(amount = "88.00"))) },
        )
        assertEquals(1, results.count { it.exceptionOrNull() == null })
        assertEquals(1, results.count { code(it) == ProposalError.RevisionConflict })
        val winner = results.first { it.exceptionOrNull() == null }.getOrThrow()
        assertEquals(first.id, winner.id)
        assertEquals(2L, proposalRow()[6])
        assertEquals(winner.payloadDigest, proposalRow()[8])
        assertEquals(winner.operations, ProposalCodec.decode(proposalRow()[7] as String))
        val stored = proposalRow()[7] as String
        val loser = if (stored.contains("\"amountMinor\":6050")) "88.00" else "60.50"
        assertNotEquals(
            ProposalCodec.canonical(ProposalCodec.normalize(listOf(fixture.createTransaction(amount = loser)), proposalClock)),
            stored,
        )
        assertEquals(1, proposals().size)
    }

    @Test
    fun anotherRunMayReviseButCannotCreateASecondActiveProposalInOneSession() = runBlocking {
        val first = service.prepare(context, listOf(fixture.createTransaction()))
        sql("UPDATE agent_run SET state='CANCELLED', activeSlot=NULL, finishedAt=? WHERE id=?", fixture.time, fixture.run.id)
        val successor = fixture.newRun()
        val before = snapshot()
        expectProposalError(ProposalError.ActiveProposalExists) { service.prepare(fixture.context(successor), listOf(fixture.createNote())) }
        assertEquals(before, snapshot())
        assertEquals(1, proposals().size)
        val revised = service.revise(fixture.context(successor), first.id, 1, listOf(fixture.createTransaction(amount = "60.50")))
        assertEquals(first.id, revised.id)
        assertEquals(2L, revised.revision)
        assertEquals(successor.id, revised.lastWriterRunId)
        assertEquals(fixture.run.id, revised.originRunId)
        val other = service.prepare(fixture.context(fixture.secondRun), listOf(fixture.createNote()))
        assertNotEquals(first.id, other.id)
        assertEquals(2, proposals().size)
        assertEquals(1, proposals().count { it[1] == fixture.session.id && it[12] == 1L })
        assertEquals(1, proposals().count { it[1] == fixture.secondSession.id && it[12] == 1L })
        assertEquals(first.id, service.activeProposal(fixture.generation, fixture.session.id)?.id)
        assertEquals(other.id, service.activeProposal(fixture.generation, fixture.secondSession.id)?.id)
    }

    @Test
    fun staleCancelledAndCrossGenerationRunContextsAreRejectedWithoutAnyWrite() = runBlocking {
        val intents = listOf(fixture.createTransaction())
        val prepared = service.prepare(fixture.context(fixture.secondRun), intents)
        val retired = listOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED").map { fixture.newRun(state = it, activeSlot = null) }
        val before = snapshot()
        for (run in retired) {
            expectProposalError(ProposalError.RunInvalidated) { service.prepare(fixture.context(run), intents) }
            expectProposalError(ProposalError.RunInvalidated) { service.revise(fixture.context(run), prepared.id, 1, intents) }
        }
        expectProposalError(ProposalError.RunInvalidated) { service.prepare(context.copy(processOwnerId = UUID.randomUUID().toString()), intents) }
        expectProposalError(ProposalError.RunInvalidated) { service.prepare(context.copy(receiverIdentity = "another-provider"), intents) }
        expectProposalError(ProposalError.RunInvalidated) { service.prepare(context.copy(authEpoch = 2), intents) }
        expectProposalError(ProposalError.RunInvalidated) {
            service.prepare(ProposalRunContext(fixture.session.id, fixture.secondRun.id, fixture.owner, fixture.receiver, 1), intents)
        }
        expectProposalError(ProposalError.NotFound) { service.prepare(context.copy(runId = UUID.randomUUID().toString()), intents) }
        expectProposalError(ProposalError.NotFound) { service.prepare(context.copy(sessionId = UUID.randomUUID().toString()), intents) }
        assertEquals(before, snapshot())
        sql("UPDATE agent_run SET dataGeneration=? WHERE id=?", fixture.staleGeneration, fixture.run.id)
        expectProposalError(ProposalError.GenerationMismatch) { service.prepare(context, intents) }
        expectProposalError(ProposalError.GenerationMismatch) { service.revise(context, prepared.id, 1, intents) }
        sql("UPDATE agent_run SET dataGeneration=? WHERE id=?", fixture.generation, fixture.run.id)
        sql("UPDATE record_data_state SET dataGeneration=?", fixture.staleGeneration)
        expectProposalError(ProposalError.GenerationMismatch) { service.prepare(context, intents) }
        expectProposalError(ProposalError.GenerationMismatch) { service.activeProposal(fixture.generation, fixture.session.id) }
        sql("UPDATE record_data_state SET dataGeneration=?", fixture.generation)
        sql("DELETE FROM record_data_state")
        expectProposalError(ProposalError.RecoveryRequired) { service.prepare(context, intents) }
        sql("INSERT INTO record_data_state (id, dataGeneration, businessRevision, processOwnerId, restoreReceipt) VALUES (1, ?, 1, ?, NULL)", fixture.generation, fixture.owner)
        sql("UPDATE record_data_state SET dataGeneration='not-a-uuid'")
        expectProposalError(ProposalError.RecoveryRequired) { service.prepare(context, intents) }
        sql("UPDATE record_data_state SET dataGeneration=?", fixture.generation)
        assertEquals(before, snapshot())
        assertEquals(prepared, service.proposal(prepared.id))
    }

    @Test
    fun creationRequestIdentityComesOnlyFromThePersistedRunRow() = runBlocking {
        val first = service.prepare(context, listOf(fixture.createTransaction()))
        assertEquals(fixture.run.proposalCreationRequestId, first.creationRequestId)
        val other = service.prepare(fixture.context(fixture.secondRun), listOf(fixture.createNote()))
        assertEquals(fixture.secondRun.proposalCreationRequestId, other.creationRequestId)
        assertNotEquals(first.creationRequestId, other.creationRequestId)
        val retired = fixture.newRun(state = "COMPLETED", activeSlot = null)
        val before = snapshot()
        val reused = retired.copy(id = UUID.randomUUID().toString(), proposalCreationRequestId = fixture.run.proposalCreationRequestId)
        try {
            database.sessions().insertRun(reused)
            fail("One creation identity must not be reusable by a second run")
        } catch (expected: SQLiteConstraintException) {
        }
        assertEquals(before, snapshot())
        sql("UPDATE change_proposal SET originRunId=? WHERE id=?", retired.id, first.id)
        val corrupted = snapshot()
        expectProposalError(ProposalError.RecoveryRequired) { service.prepare(context, listOf(fixture.createTransaction())) }
        assertEquals(corrupted, snapshot())
        assertEquals(2, proposals().size)
    }

    @Test
    fun localRefDuplicateDanglingAndTypeErrorsAreRejectedWithZeroBusinessFacts() = runBlocking {
        val before = snapshot()
        val swapped = ProposalIntent.LinkRecords(listOf(RecordPair(RecordRef.Local("#n1"), 1, RecordRef.Local("#t1"), 1)))
        val fabricatedBaseline = ProposalIntent.LinkRecords(listOf(RecordPair(RecordRef.Local("#t1"), 2, RecordRef.Local("#n1"), 1)))
        val noteInTransactionSlot = ProposalIntent.LinkRecords(listOf(RecordPair(RecordRef.Note(fixture.note.id), 7, RecordRef.Local("#n1"), 1)))
        val undeclared = ProposalIntent.LinkRecords(listOf(RecordPair(RecordRef.Local("#t1"), 1, RecordRef.Local("#missing"), 1)))
        val cases = listOf(
            listOf(fixture.createTransaction("#t1"), fixture.createTransaction("#t1")),
            listOf(fixture.createNote("#n1"), fixture.createNote("#n1")),
            listOf(fixture.createTransaction("#t1"), fixture.createNote("#n1"), swapped),
            listOf(fixture.createTransaction("#t1"), fixture.createNote("#n1"), fabricatedBaseline),
            listOf(fixture.createTransaction("#t1"), fixture.createNote("#n1"), noteInTransactionSlot),
            listOf(fixture.createTransaction("#t1"), fixture.createNote("#n1"), undeclared),
            listOf(fixture.linkNew()),
            listOf(fixture.createNote("#n1"), fixture.linkNew()),
            listOf(fixture.createTransaction("#t1"), fixture.linkNew()),
            listOf(fixture.createTransaction("t1")),
            listOf(fixture.createTransaction("#中文")),
            listOf(fixture.createTransaction(), ProposalIntent.LinkRecords(emptyList())),
        )
        for (intent in cases) {
            expectProposalError(ProposalError.Validation) { service.prepare(context, intent) }
            assertEquals(before, snapshot())
            assertTrue(proposals().isEmpty())
        }
        val valid = service.prepare(context, goldenIntents)
        assertEquals(1, proposals().size)
        assertEquals(goldenDigest, valid.payloadDigest)
    }

    @Test
    fun invalidAmountTimeAccountCategoryVersionAndVisibilityAreRejectedWithZeroBusinessFacts() = runBlocking {
        val before = snapshot()
        val rejections = listOf(
            listOf(fixture.createTransaction(amount = "0")) to ProposalError.Validation,
            listOf(fixture.createTransaction(amount = "1.234")) to ProposalError.Validation,
            listOf(fixture.createTransaction(amount = "92233720368547758.08")) to ProposalError.Validation,
            listOf(ProposalIntent.CreateTransaction("#t1", fixture.input(accountVersion = Long.MAX_VALUE))) to ProposalError.Validation,
            listOf(ProposalIntent.CreateTransaction("#t1", fixture.input(localDateTime = "2026-10-24T00:00"))) to ProposalError.Validation,
            listOf(ProposalIntent.CreateTransaction("#t1", fixture.input(localDateTime = "2026-03-08T02:30", zoneId = "America/New_York"))) to ProposalError.Validation,
            listOf(ProposalIntent.CreateTransaction("#t1", fixture.input(categoryId = fixture.expense.id))) to ProposalError.Validation,
            listOf(ProposalIntent.CreateTransaction("#t1", fixture.input(categoryId = fixture.expense.id, categoryVersion = 0))) to ProposalError.Validation,
            listOf(fixture.createTransaction(categoryId = fixture.income.id, categoryVersion = 1)) to ProposalError.Validation,
            listOf(fixture.createNote(body = "  \n\t ")) to ProposalError.Validation,
            listOf(fixture.createTransaction(memo = "记".repeat(ProposalBudget.MAX_MEMO_CHARS + 1))) to ProposalError.BudgetExceeded,
            listOf(fixture.createNote(body = "字".repeat(ProposalBudget.MAX_NOTE_BODY_CHARS + 1))) to ProposalError.BudgetExceeded,
            listOf(ProposalIntent.CreateTransaction("#t1", fixture.input(categoryId = UUID.randomUUID().toString(), categoryVersion = 1))) to ProposalError.NotFound,
            listOf(ProposalIntent.CreateTransaction("#t1", fixture.input(accountId = UUID.randomUUID().toString()))) to ProposalError.NotFound,
            listOf(ProposalIntent.CreateTransaction("#t1", fixture.input(accountVersion = 2))) to ProposalError.VersionConflict,
            listOf(fixture.createTransaction(categoryId = fixture.expense.id, categoryVersion = 2)) to ProposalError.VersionConflict,
            listOf(ProposalIntent.UpdateTransaction(UUID.randomUUID().toString(), 1, fixture.input())) to ProposalError.NotFound,
            listOf(fixture.updateTransaction(expectedVersion = 4)) to ProposalError.VersionConflict,
            listOf(ProposalIntent.UpdateNote(UUID.randomUUID().toString(), 1, "正文", "2026-10-23T12:30:00", "Asia/Shanghai")) to ProposalError.NotFound,
            listOf(ProposalIntent.UpdateNote(fixture.deletedNote.id, 2, "正文", "2026-10-23T12:30:00", "Asia/Shanghai")) to ProposalError.NotVisible,
            listOf(fixture.updateNote(expectedVersion = 8)) to ProposalError.VersionConflict,
            listOf(fixture.linkExisting(transactionVersion = 4)) to ProposalError.VersionConflict,
            listOf(fixture.linkExisting(noteVersion = 8)) to ProposalError.VersionConflict,
            listOf(fixture.unlinkDeletedNote()) to ProposalError.NotVisible,
            listOf(ProposalIntent.LinkRecords(listOf(RecordPair(RecordRef.Transaction(UUID.randomUUID().toString()), 1, RecordRef.Note(fixture.note.id), 7)))) to ProposalError.NotFound,
            (1..ProposalBudget.MAX_OPERATIONS + 1).map { fixture.createNote("#n$it") } to ProposalError.BudgetExceeded,
            emptyList<ProposalIntent>() to ProposalError.Validation,
        )
        for ((intents, expected) in rejections) {
            expectProposalError(expected) { service.prepare(context, intents) }
            assertEquals(before, snapshot())
            assertTrue(proposals().isEmpty())
        }
        sql("UPDATE account SET version=2, name='改名后的账户' WHERE id=?", fixture.account.id)
        val renamed = snapshot()
        expectProposalError(ProposalError.VersionConflict) { service.prepare(context, listOf(ProposalIntent.CreateTransaction("#t1", fixture.input(accountVersion = 1)))) }
        assertEquals(renamed, snapshot())
        sql("UPDATE account SET version=1, name='日常账户' WHERE id=?", fixture.account.id)
        val valid = service.prepare(context, listOf(fixture.updateTransaction(), fixture.updateNote(), fixture.unlinkExisting()))
        assertEquals(3, valid.operations.size)
        assertEquals(before.filterKeys { it != "change_proposal" }, snapshot().filterKeys { it != "change_proposal" })
    }

    @Test
    fun frozenTerminalAndNeedsReviewProposalsRejectRevisionAndFreeTheActiveSlot() = runBlocking {
        val first = service.prepare(context, listOf(fixture.createTransaction()))
        for (state in ProposalState.entries.filter { it != ProposalState.EDITABLE }) {
            val slot = if (state.occupiesActiveSlot) 1 else null
            sql("UPDATE change_proposal SET state=?, activeSlot=? WHERE id=?", state.name, slot, first.id)
            val frozen = snapshot()
            expectProposalError(ProposalError.ProposalFrozen) { service.revise(context, first.id, 1, listOf(fixture.createTransaction(amount = "60.50"))) }
            assertEquals(frozen, snapshot())
            assertEquals(state.name, proposalRow()[11])
            if (slot == null) {
                assertNull(service.activeProposal(fixture.generation, fixture.session.id))
            } else {
                assertNotNull(service.activeProposal(fixture.generation, fixture.session.id))
            }
        }
        sql("UPDATE change_proposal SET state='NEEDS_REVIEW', activeSlot=NULL WHERE id=?", first.id)
        sql("UPDATE agent_run SET state='COMPLETED', activeSlot=NULL, finishedAt=? WHERE id=?", fixture.time, fixture.run.id)
        val successor = fixture.newRun()
        val reviewed = service.prepare(fixture.context(successor), listOf(fixture.createTransaction(amount = "60.50")))
        assertNotEquals(first.id, reviewed.id)
        assertEquals(1L, reviewed.revision)
        assertEquals(successor.id, reviewed.originRunId)
        assertEquals(successor.proposalCreationRequestId, reviewed.creationRequestId)
        assertEquals(2, proposals().size)
        assertEquals(1, proposals().count { it[12] == 1L })
        assertEquals("NEEDS_REVIEW", proposals().single { it[0] == first.id }[11])
        assertEquals(reviewed, service.activeProposal(fixture.generation, fixture.session.id))
        expectProposalError(ProposalError.CreationRequestConflict) { service.prepare(fixture.context(successor), listOf(fixture.createNote())) }
        assertEquals(2, proposals().size)
        sql("UPDATE change_proposal SET state='UNRECOGNIZED' WHERE id=?", reviewed.id)
        val unrecognized = snapshot()
        expectProposalError(ProposalError.RecoveryRequired) { service.proposal(reviewed.id) }
        expectProposalError(ProposalError.RecoveryRequired) { service.activeProposal(fixture.generation, fixture.session.id) }
        expectProposalError(ProposalError.RecoveryRequired) { service.prepare(fixture.context(successor), listOf(fixture.createNote())) }
        expectProposalError(ProposalError.RecoveryRequired) { service.revise(fixture.context(successor), reviewed.id, 1, listOf(fixture.createNote())) }
        assertEquals(unrecognized, snapshot())
        assertEquals(first.copy(state = ProposalState.NEEDS_REVIEW), service.proposal(first.id))
    }

    @Test
    fun uniqueIndexesPhysicallyRejectASecondActiveSlotAndASecondCreationRequest() = runBlocking {
        val first = service.prepare(context, listOf(fixture.createTransaction()))
        val before = snapshot()
        val candidate = ChangeProposalEntity(
            UUID.randomUUID().toString(), fixture.session.id, fixture.run.id, fixture.run.id,
            UUID.randomUUID().toString(), "{}", 1, "{}", "c".repeat(64), 1, fixture.generation,
            "EDITABLE", 1, fixture.time, fixture.time,
        )
        try {
            database.proposals().insertProposal(candidate)
            fail("One session must not hold two active proposals")
        } catch (expected: SQLiteConstraintException) {
        }
        try {
            database.proposals().insertProposal(candidate.copy(creationRequestId = first.creationRequestId, state = "ABANDONED", activeSlot = null))
            fail("One creation identity must not produce two proposals")
        } catch (expected: SQLiteConstraintException) {
        }
        assertEquals(before, snapshot())
        database.proposals().insertProposal(candidate.copy(state = "ABANDONED", activeSlot = null))
        assertEquals(2, proposals().size)
        assertEquals(1, proposals().count { it[12] == 1L })
    }

    @Test
    fun corruptPersistedProposalFailsClosedOnEveryReadAndRepeatPath() = runBlocking {
        val view = service.prepare(context, goldenIntents)
        val original = proposalRow()
        val oversized = ProposalCodec.canonical(
            ProposalCodec.normalize(listOf(fixture.createNote(body = "字".repeat(ProposalBudget.MAX_NOTE_BODY_CHARS))), proposalClock),
        ) + " ".repeat(ProposalBudget.MAX_PAYLOAD_BYTES)
        val corruptions = listOf(
            listOf("UPDATE change_proposal SET payloadJson='{}'"),
            listOf("UPDATE change_proposal SET payloadJson=?", """{"formatVersion":1,"operations":[{"type":"DELETE_RECORD"}]}"""),
            listOf("UPDATE change_proposal SET payloadJson=?", goldenCanonical.replace("\"body\":", "\"unexpected\":0,\"body\":")),
            listOf("UPDATE change_proposal SET payloadDigest=?", "0".repeat(64)),
            listOf("UPDATE change_proposal SET payloadFormatVersion=2"),
            listOf("UPDATE change_proposal SET activeSlot=NULL"),
            listOf("UPDATE change_proposal SET revision=0"),
            listOf("UPDATE change_proposal SET creationPayloadJson='not json'"),
            listOf("UPDATE change_proposal SET updatedAt=createdAt-1"),
            listOf("UPDATE change_proposal SET state='UNRECOGNIZED'"),
            listOf("UPDATE change_proposal SET payloadJson=?, payloadDigest=?", oversized, sha256(oversized)),
        )
        for (corruption in corruptions) {
            sql(corruption.first(), *corruption.drop(1).toTypedArray())
            val corrupt = snapshot()
            expectProposalError(ProposalError.RecoveryRequired) { service.proposal(view.id) }
            expectProposalError(ProposalError.RecoveryRequired) { service.prepare(context, goldenIntents) }
            expectProposalError(ProposalError.RecoveryRequired) { service.revise(context, view.id, 1, goldenIntents) }
            assertEquals(corrupt, snapshot())
            restore(original)
            assertEquals(view, service.proposal(view.id))
        }
        sql("UPDATE change_proposal SET payloadDigest=?", "0".repeat(64))
        expectProposalError(ProposalError.RecoveryRequired) { service.activeProposal(fixture.generation, fixture.session.id) }
        restore(original)
        assertEquals(view, service.activeProposal(fixture.generation, fixture.session.id))
        // Identity corruption keeps the row findable by primary key but unreadable as a proposal.
        sql("UPDATE change_proposal SET creationRequestId='not-a-uuid'")
        val orphanedRequest = snapshot()
        expectProposalError(ProposalError.RecoveryRequired) { service.proposal(view.id) }
        expectProposalError(ProposalError.RecoveryRequired) { service.prepare(context, goldenIntents) }
        expectProposalError(ProposalError.RecoveryRequired) { service.activeProposal(fixture.generation, fixture.session.id) }
        assertEquals(orphanedRequest, snapshot())
        restore(original)
        sql("UPDATE change_proposal SET dataGeneration='not-a-uuid'")
        val corruptGeneration = snapshot()
        expectProposalError(ProposalError.RecoveryRequired) { service.proposal(view.id) }
        expectProposalError(ProposalError.RecoveryRequired) { service.prepare(context, goldenIntents) }
        expectProposalError(ProposalError.RecoveryRequired) { service.revise(context, view.id, 1, goldenIntents) }
        assertEquals(corruptGeneration, snapshot())
        restore(original)
        // A well-formed but retired generation is a stale request, not corruption.
        sql("UPDATE change_proposal SET dataGeneration=?", fixture.staleGeneration)
        val staleGeneration = snapshot()
        expectProposalError(ProposalError.GenerationMismatch) { service.prepare(context, goldenIntents) }
        expectProposalError(ProposalError.GenerationMismatch) { service.revise(context, view.id, 1, goldenIntents) }
        expectProposalError(ProposalError.GenerationMismatch) { service.activeProposal(fixture.generation, fixture.session.id) }
        assertEquals(staleGeneration, snapshot())
        restore(original)
        reopen()
        assertEquals(view, service.proposal(view.id))
    }

    @Test
    fun cancellationInsideTheRealTransactionLeavesNoPartialProposal() = runBlocking {
        val before = snapshot()
        for (point in listOf(ProposalCheckpoint.TARGETS_VALIDATED, ProposalCheckpoint.PROPOSAL_INSERTED)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val gated = RoomProposalService(database, proposalClock, checkpoint = {
                if (it == point) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            })
            val job = launch { gated.prepare(context, goldenIntents) }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
                assertEquals(before, snapshot())
                job.cancel()
            } finally {
                release.countDown()
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(before, snapshot())
            assertTrue(proposals().isEmpty())
        }
        val committed = service.prepare(context, goldenIntents)
        val prepared = snapshot()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated = RoomProposalService(database, proposalClock, checkpoint = {
            if (it == ProposalCheckpoint.PROPOSAL_REVISED) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        })
        val job = launch { gated.revise(context, committed.id, 1, listOf(fixture.createTransaction(amount = "60.50"))) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            assertEquals(prepared, snapshot())
            job.cancel()
        } finally {
            release.countDown()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(prepared, snapshot())
        assertEquals(1L, proposalRow()[6])
        assertEquals(2L, service.revise(context, committed.id, 1, listOf(fixture.createTransaction(amount = "60.50"))).revision)
    }

    @Test
    fun storageFailureDuringPrepareAndReviseRollsBackAndTheSameRequestRetries() = runBlocking {
        val intents = listOf(fixture.createTransaction())
        val before = snapshot()
        val failing = RoomProposalService(database, proposalClock, checkpoint = {
            if (it == ProposalCheckpoint.PROPOSAL_INSERTED) throw SQLiteFullException("Synthetic full disk")
        })
        repeat(2) {
            expectProposalError(ProposalError.StorageFull) { failing.prepare(context, intents) }
            assertEquals(before, snapshot())
        }
        reopen()
        assertEquals(before, snapshot())
        val committed = service.prepare(context, intents)
        assertEquals(1, proposals().size)
        val beforeRevise = snapshot()
        val failingRevise = RoomProposalService(database, proposalClock, checkpoint = {
            if (it == ProposalCheckpoint.PROPOSAL_REVISED) throw SQLiteFullException("Synthetic full disk")
        })
        repeat(2) {
            expectProposalError(ProposalError.StorageFull) { failingRevise.revise(context, committed.id, 1, listOf(fixture.createTransaction(amount = "60.50"))) }
            assertEquals(beforeRevise, snapshot())
        }
        assertEquals(1L, proposalRow()[6])
        assertEquals(2L, service.revise(context, committed.id, 1, listOf(fixture.createTransaction(amount = "60.50"))).revision)
        reopen()
        assertEquals(2L, proposalRow()[6])
    }

    private fun restore(row: List<Any?>) = sql(
        """UPDATE change_proposal SET id=?, sessionId=?, originRunId=?, lastWriterRunId=?, creationRequestId=?,
        creationPayloadJson=?, revision=?, payloadJson=?, payloadDigest=?, payloadFormatVersion=?, dataGeneration=?,
        state=?, activeSlot=?, createdAt=?, updatedAt=? WHERE 1""",
        *row.toTypedArray(),
    )
}

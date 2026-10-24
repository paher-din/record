package com.paherdin.record2.storage

import android.database.sqlite.SQLiteFullException
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.proposals.AppliedCommitResult
import com.paherdin.record2.proposals.AppliedOutcome
import com.paherdin.record2.proposals.CommitCheckpoint
import com.paherdin.record2.proposals.CommitError
import com.paherdin.record2.proposals.CommitException
import com.paherdin.record2.proposals.CommitReceiptLossException
import com.paherdin.record2.proposals.CommitResultCodec
import com.paherdin.record2.proposals.CommitStatus
import com.paherdin.record2.proposals.ConfirmationCommand
import com.paherdin.record2.proposals.ProposalError
import com.paherdin.record2.proposals.ProposalException
import com.paherdin.record2.proposals.ProposalIntent
import com.paherdin.record2.proposals.ProposalView
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C1 oracle: the full repeated-confirmation matrix of B0 §5.2, the all-or-nothing business
 * commit of §5.3, generation ineligibility, the same-id retry, and the fault points around
 * every durable boundary. Independent disk facts come from SchemaDiskObserver only.
 */
class CommitContractTest {
    private val name = "c1-contract-${schemaId()}.db"
    private val path get() = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)
    private lateinit var database: RecordDatabase
    private lateinit var fixture: ProposalFixture
    private lateinit var proposals: RoomProposalService
    private lateinit var commits: RoomCommitService

    private val goldenIntents get() = listOf(fixture.createTransaction(), fixture.createNote(), fixture.linkNew())
    private val context get() = fixture.context(fixture.run)
    private val businessTables = setOf(
        "record_data_state", "account", "category", "record_transaction", "note",
        "transaction_note_link", "native_finance_draft",
    )

    @Before
    fun open() {
        check(!path.exists())
        database = commitDatabase(name)
        database.openHelper.writableDatabase
        fixture = ProposalFixture(database)
        fixture.seed()
        proposals = RoomProposalService(database, proposalClock)
        commits = service()
    }

    @After
    fun close() {
        database.close()
    }

    private fun service(
        ids: List<String> = emptyList(),
        checkpoint: (CommitCheckpoint, Int) -> Unit = { _, _ -> },
    ) = RoomCommitService(
        database, proposalClock,
        if (ids.isEmpty()) ({ UUID.randomUUID().toString() }) else queuedIds(*ids.toTypedArray()),
        checkpoint,
    )

    private fun command(
        view: ProposalView,
        revision: Long = view.revision,
        digest: String = view.payloadDigest,
        generation: String = fixture.generation,
    ) = ConfirmationCommand(view.id, revision, digest, generation)

    private fun snapshot() = SchemaDiskObserver.snapshot(path)
    private fun rows(table: String) = snapshot().getValue(table)
    private fun rowOf(table: String, column: Int, value: Any?) = rows(table).single { it[column] == value }
    private fun operations() = rows("commit_operation")
    private fun business(): Map<String, List<List<Any?>>> = snapshot().filterKeys { it in businessTables }.mapValues { it.value!! }
    private fun sql(statement: String, vararg args: Any?) = database.openHelper.writableDatabase.execSQL(statement, args)

    private fun reopen() {
        database.close()
        database = commitDatabase(name)
        fixture.rebind(database)
        proposals = RoomProposalService(database, proposalClock)
        commits = service()
    }

    private suspend fun <T> onTwoConnections(
        first: suspend (RoomProposalService, RoomCommitService) -> T,
        second: suspend (RoomProposalService, RoomCommitService) -> T,
    ): List<Result<T>> {
        val other = commitDatabase(name)
        try {
            val peerProposals = RoomProposalService(other, proposalClock)
            val peerCommits = RoomCommitService(other, proposalClock)
            return coroutineScope {
                listOf(
                    async { runCatching { first(proposals, commits) } },
                    async { runCatching { second(peerProposals, peerCommits) } },
                ).awaitAll()
            }
        } finally {
            other.close()
        }
    }

    @Test
    fun confirmFreezesTheDisplayedPayloadLocksTheProposalAndWritesNoBusinessFact() = runBlocking {
        val seeded = snapshot()
        val view = proposals.prepare(context, goldenIntents)
        val outcome = service(CommitGolden.ids()).confirm(command(view))
        assertTrue(outcome.fresh)
        assertEquals(CommitStatus.Pending, outcome.status)
        assertEquals(CommitGolden.OPERATION, outcome.operationId)
        assertEquals(view.id, outcome.proposalId)
        assertEquals(1L, outcome.confirmedRevision)
        assertEquals(fixture.generation, outcome.createdGeneration)
        assertEquals(mapOf("#t1" to CommitGolden.NEW_TRANSACTION, "#n1" to CommitGolden.NEW_NOTE), outcome.idAssignments)
        assertNull(outcome.result)
        assertNull(outcome.finishedAt)
        assertNull(outcome.rejectionCode)

        val proposal = rowOf("change_proposal", 0, view.id)
        assertEquals("LOCKED", proposal[11])
        assertEquals(1L, proposal[12])
        assertEquals(1L, proposal[6])
        val operation = operations().single()
        assertEquals(CommitGolden.OPERATION, operation[0])
        assertEquals(view.id, operation[1])
        assertEquals(1L, operation[2])
        assertEquals(fixture.generation, operation[3])
        assertEquals(CommitGolden.FROZEN_JSON, operation[4])
        assertEquals(CommitGolden.FROZEN_DIGEST, operation[5])
        assertEquals(sha256(operation[4] as String), operation[5])
        assertEquals(1L, operation[6])
        assertEquals("Pending", operation[7])
        assertEquals(fixture.time, operation[8])
        assertNull(operation[9])
        assertNull(operation[10])
        assertNull(operation[11])

        // A touches neither the business tables nor the native draft.
        assertEquals(
            seeded.filterKeys { it != "change_proposal" && it != "commit_operation" },
            snapshot().filterKeys { it != "change_proposal" && it != "commit_operation" },
        )

        // A repeated confirmation returns the original Pending operation and never executes B.
        val afterConfirm = snapshot()
        val repeat = service(CommitGolden.ids()).confirm(command(view))
        assertEquals(CommitGolden.OPERATION, repeat.operationId)
        assertFalse(repeat.fresh)
        assertEquals(CommitStatus.Pending, repeat.status)
        assertEquals(afterConfirm, snapshot())
        assertTrue(business().getValue("record_transaction").size == seeded.getValue("record_transaction").size)
        reopen()
        assertEquals(CommitGolden.FROZEN_JSON, operations().single()[4])
    }

    @Test
    fun repeatedConfirmationsReturnTheOriginalOperationInEveryState() = runBlocking {
        val view = proposals.prepare(context, goldenIntents)
        val cmd = command(view)
        val commits = service(CommitGolden.ids())
        commits.confirm(cmd)
        val pending = snapshot()
        expectCommitError(CommitError.ConfirmationMismatch) { commits.confirm(cmd.copy(displayedRevision = 2)) }
        expectCommitError(CommitError.ConfirmationMismatch) { commits.confirm(cmd.copy(displayedPayloadDigest = sha256("other"))) }
        assertEquals(pending, snapshot())

        val applied = commits.execute(CommitGolden.OPERATION)
        assertEquals(CommitStatus.Applied, applied.status)
        val reapplied = service().confirm(cmd)
        assertEquals(CommitGolden.OPERATION, reapplied.operationId)
        assertFalse(reapplied.fresh)
        assertEquals(CommitStatus.Applied, reapplied.status)
        assertEquals(applied.result, reapplied.result)
        expectCommitError(CommitError.ConfirmationMismatch) { service().confirm(cmd.copy(displayedRevision = 2)) }

        // A deterministic conflict settles the second proposal into its own rejection.
        val second = proposals.prepare(fixture.context(fixture.secondRun), listOf(fixture.updateTransaction()))
        sql("UPDATE record_transaction SET version=4 WHERE id=?", fixture.transaction.id)
        val secondCmd = command(second)
        val rejectedOperation = service(CommitGolden.ids()).confirm(secondCmd)
        val rejected = service().execute(rejectedOperation.operationId)
        assertEquals(CommitStatus.RejectedConflict, rejected.status)
        assertEquals("VersionConflict", rejected.rejectionCode)
        assertNotNull(rejected.finishedAt)
        val reread = service().confirm(secondCmd)
        assertEquals(rejectedOperation.operationId, reread.operationId)
        assertEquals(CommitStatus.RejectedConflict, reread.status)
        assertEquals("VersionConflict", reread.rejectionCode)
        val rejectedAgain = service().execute(rejectedOperation.operationId)
        assertEquals(rejected.rejectionCode, rejectedAgain.rejectionCode)
        assertEquals(rejected.finishedAt, rejectedAgain.finishedAt)

        // RejectedInvalid is synthesized here: a valid frozen payload cannot be deterministically
        // invalid by construction, so the state is written directly to prove the repeat contract.
        sql("UPDATE commit_operation SET state='RejectedInvalid', rejectionCode='Validation', finishedAt=? WHERE id=?", fixture.time, rejectedOperation.operationId)
        val terminal = snapshot()
        val synthesized = service().confirm(secondCmd)
        assertEquals(CommitStatus.RejectedInvalid, synthesized.status)
        assertEquals("Validation", synthesized.rejectionCode)
        expectCommitError(CommitError.ConfirmationMismatch) { service().confirm(secondCmd.copy(displayedPayloadDigest = sha256("other"))) }
        expectCommitError(CommitError.ConfirmationMismatch) { service().confirm(secondCmd.copy(displayedRevision = 2)) }
        assertEquals(terminal, snapshot())
        // Both rejections left the business facts exactly as the native edit had moved them.
        assertEquals(1, operations().count { it[7] == "Applied" })
    }

    @Test
    fun confirmationVersusRevisionAndDoubleClicksHaveExactlyOneWinner() = runBlocking {
        val view = proposals.prepare(context, goldenIntents)
        val cmd = command(view)
        val race = onTwoConnections(
            { p, _ -> p.revise(context, view.id, 1, listOf(fixture.createTransaction(amount = "60.50"))) },
            { _, c -> c.confirm(cmd) },
        )
        assertEquals(1, race.count { it.exceptionOrNull() == null })
        if (race[1].exceptionOrNull() == null) {
            assertEquals(ProposalError.ProposalFrozen, (race[0].exceptionOrNull() as ProposalException).code)
            assertEquals(1, operations().size)
            assertEquals("LOCKED", rowOf("change_proposal", 0, view.id)[11])
            // A successful confirmation freezes the payload; the loser's content never lands.
            val locked = proposals.proposal(view.id)!!
            assertEquals(view.payloadDigest, locked.payloadDigest)
        } else {
            assertEquals(CommitError.ConfirmationMismatch, (race[1].exceptionOrNull() as CommitException).code)
            assertEquals(0, operations().size)
            assertEquals(2L, rowOf("change_proposal", 0, view.id)[6])
            // The stale display is refused forever; confirming the current revision still works.
            expectCommitError(CommitError.ConfirmationMismatch) { commits.confirm(cmd) }
            val current = proposals.proposal(view.id)!!
            assertTrue(service().confirm(command(current)).fresh)
        }

        // A double click on one command creates exactly one operation.
        val second = proposals.prepare(fixture.context(fixture.secondRun), listOf(fixture.createNote()))
        val secondCmd = command(second)
        val clicks = onTwoConnections({ _, c -> c.confirm(secondCmd) }, { _, c -> c.confirm(secondCmd) })
        clicks.forEach { it.getOrThrow() }
        assertEquals(clicks[0].getOrThrow().operationId, clicks[1].getOrThrow().operationId)
        assertEquals(1, clicks.count { it.getOrThrow().fresh })
        assertEquals(1, operations().count { it[1] == second.id })
        assertEquals("LOCKED", rowOf("change_proposal", 0, second.id)[11])
    }

    @Test
    fun executeAppliesTheCombinedPayloadAllOrNothing() = runBlocking {
        val newTransaction = "20000000-0000-4000-8000-000000000001"
        val newNote = "20000000-0000-4000-8000-000000000002"
        val operationId = "20000000-0000-4000-8000-000000000003"
        val intents = listOf(
            fixture.createTransaction("#t1", amount = "88.80", memo = "晚饭", categoryId = fixture.expense.id, categoryVersion = 1),
            fixture.createNote("#n1", body = "买菜记录"),
            fixture.updateTransaction(amount = "60.50", memo = "改写说明"),
            fixture.updateNote(body = "修订正文"),
            fixture.linkNew(),
            fixture.unlinkExisting(),
        )
        val view = proposals.prepare(context, intents)
        val confirmed = service(listOf(newTransaction, newNote, operationId)).confirm(command(view))
        assertEquals(CommitStatus.Pending, confirmed.status)
        assertEquals(seededTransactionCount(), snapshot().getValue("record_transaction").size)

        val applied = service().execute(operationId)
        assertEquals(CommitStatus.Applied, applied.status)
        assertEquals(fixture.time, applied.finishedAt)

        val created = rowOf("record_transaction", 0, newTransaction)
        assertEquals("EXPENSE", created[1])
        assertEquals(8880L, created[2])
        assertEquals("CNY", created[3])
        assertEquals(fixture.account.id, created[4])
        assertEquals(fixture.expense.id, created[5])
        assertEquals("晚饭", created[6])
        assertEquals(1_792_729_800_000L, created[7])
        assertEquals(2L, created[8])
        val createdNote = rowOf("note", 0, newNote)
        assertEquals("买菜记录", createdNote[1])
        assertEquals(1_792_729_800_000L, createdNote[2])
        assertEquals(2L, createdNote[3])
        assertNull(createdNote[6])

        val updated = rowOf("record_transaction", 0, fixture.transaction.id)
        assertEquals(6050L, updated[2])
        assertEquals("改写说明", updated[6])
        assertEquals(5L, updated[8])
        val updatedNote = rowOf("note", 0, fixture.note.id)
        assertEquals("修订正文", updatedNote[1])
        assertEquals(9L, updatedNote[3])

        assertEquals(1, rows("transaction_note_link").size)
        val link = rows("transaction_note_link").single()
        assertEquals(newTransaction, link[0])
        assertEquals(newNote, link[1])
        assertEquals(2L, rows("record_data_state").single()[2])

        val expected = AppliedCommitResult(
            2,
            listOf(
                AppliedOutcome.CreateTransaction(newTransaction, 1),
                AppliedOutcome.CreateNote(newNote, 1),
                AppliedOutcome.UpdateTransaction(fixture.transaction.id, 4),
                AppliedOutcome.UpdateNote(fixture.note.id, 8),
                AppliedOutcome.LinkRecords(1),
                AppliedOutcome.UnlinkRecords(1),
            ),
        )
        val operation = operations().single()
        assertEquals("Applied", operation[7])
        assertEquals(CommitResultCodec.canonical(expected), operation[10])
        assertEquals(expected, applied.result)
        assertEquals("APPLIED", rowOf("change_proposal", 0, view.id)[11])
        assertNull(rowOf("change_proposal", 0, view.id)[12])

        // Repeated execution and a reopen both return the identical persisted result.
        val again = service().execute(operationId)
        assertEquals(applied, again)
        val afterApplied = snapshot()
        reopen()
        val inspected = service().inspect(operationId)!!
        assertEquals(applied.copy(fresh = false), inspected)
        assertEquals(afterApplied, snapshot())
    }

    @Test
    fun executeRejectsConflictsWithZeroBusinessModification() = runBlocking {
        val seededBusiness = business()

        val stale = proposals.prepare(context, listOf(fixture.updateTransaction()))
        val staleOperation = service().confirm(command(stale))
        sql("UPDATE record_transaction SET version=4 WHERE id=?", fixture.transaction.id)
        val bumped = business()
        val rejected = service().execute(staleOperation.operationId)
        assertEquals(CommitStatus.RejectedConflict, rejected.status)
        assertEquals("VersionConflict", rejected.rejectionCode)
        assertEquals(fixture.time, rejected.finishedAt)
        assertNull(rejected.result)
        assertEquals(bumped, business())
        assertEquals("REJECTED_CONFLICT", rowOf("change_proposal", 0, stale.id)[11])
        assertNull(rowOf("change_proposal", 0, stale.id)[12])

        // Each proposal needs its own run creation identity; the second run serves the next two.
        val hidden = proposals.prepare(fixture.context(fixture.secondRun), listOf(fixture.updateNote()))
        val hiddenOperation = service().confirm(command(hidden))
        sql("UPDATE note SET deletedAt=? WHERE id=?", fixture.time, fixture.note.id)
        val hiddenRejected = service().execute(hiddenOperation.operationId)
        assertEquals(CommitStatus.RejectedConflict, hiddenRejected.status)
        assertEquals("NotVisible", hiddenRejected.rejectionCode)

        sql("UPDATE agent_run SET state='COMPLETED', activeSlot=NULL, finishedAt=? WHERE id=?", fixture.time, fixture.secondRun.id)
        val thirdRun = fixture.newRun(session = fixture.secondSession)
        val occupied = proposals.prepare(fixture.context(thirdRun), listOf(fixture.createTransaction("#t1")))
        val occupiedOperation = service(listOf(fixture.transaction.id, UUID.randomUUID().toString())).confirm(command(occupied))
        val occupiedRejected = service().execute(occupiedOperation.operationId)
        assertEquals(CommitStatus.RejectedConflict, occupiedRejected.status)
        assertEquals("IdOccupied", occupiedRejected.rejectionCode)

        // Every rejection left the business facts untouched beyond the native edit itself.
        assertTrue(business().getValue("note").any { it[6] != null })
        assertEquals(bumped.getValue("record_transaction"), business().getValue("record_transaction"))
        assertEquals(0, operations().count { it[7] == "Applied" })
        assertEquals(3, operations().count { it[7] == "RejectedConflict" })
        // The locked payloads survive verbatim for review; none was unlocked or edited.
        assertTrue(operations().all { sha256(it[4] as String) == it[5] })
    }

    @Test
    fun crossGenerationPendingIsHistoryAndNeverAnExecutionGrant() = runBlocking {
        val view = proposals.prepare(context, goldenIntents)
        val other = proposals.prepare(fixture.context(fixture.secondRun), listOf(fixture.createNote()))
        service(CommitGolden.ids()).confirm(command(view))
        sql("UPDATE record_data_state SET dataGeneration=?", fixture.staleGeneration)
        val stale = snapshot()
        expectCommitError(CommitError.GenerationMismatch) { service().execute(CommitGolden.OPERATION) }
        assertEquals(stale, snapshot())
        // The identity path still verifies the old operation across generations.
        val verified = service().confirm(command(view))
        assertEquals(CommitGolden.OPERATION, verified.operationId)
        assertEquals(CommitStatus.Pending, verified.status)
        assertFalse(verified.fresh)
        assertEquals(stale, snapshot())
        // A fresh confirmation under the retired generation is refused.
        expectCommitError(CommitError.GenerationMismatch) { service().confirm(command(other)) }
        assertEquals(stale, snapshot())
        // Returning to the creating generation re-enables the owner's same-id retry.
        sql("UPDATE record_data_state SET dataGeneration=?", fixture.generation)
        val applied = service().execute(CommitGolden.OPERATION)
        assertEquals(CommitStatus.Applied, applied.status)
        assertEquals(2L, rowOf("record_transaction", 0, CommitGolden.NEW_TRANSACTION)[8])
    }

    @Test
    fun interruptedExecutionRollsBackToPendingAndTheSameIdRetryAppliesExactlyOnce() = runBlocking {
        val view = proposals.prepare(context, goldenIntents)
        service(CommitGolden.ids()).confirm(command(view))
        val locked = snapshot()
        for (point in listOf(
            CommitCheckpoint.PAYLOAD_VERIFIED,
            CommitCheckpoint.TARGETS_VALIDATED,
            CommitCheckpoint.BUSINESS_WRITTEN,
            CommitCheckpoint.APPLIED_WRITTEN,
        )) {
            val failing = service(checkpoint = { reached, _ -> if (reached == point) throw SQLiteFullException("Synthetic full disk") })
            expectCommitError(CommitError.StorageFull) { failing.execute(CommitGolden.OPERATION) }
            assertEquals(locked, snapshot())
        }
        for (point in listOf(CommitCheckpoint.TARGETS_VALIDATED, CommitCheckpoint.BUSINESS_WRITTEN)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val gated = service(checkpoint = { reached, _ ->
                if (reached == point) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            })
            val job = launch { gated.execute(CommitGolden.OPERATION) }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
                assertEquals(locked, snapshot())
                job.cancel()
            } finally {
                release.countDown()
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(locked, snapshot())
            assertEquals("Pending", operations().single()[7])
        }
        val applied = service().execute(CommitGolden.OPERATION)
        assertEquals(CommitStatus.Applied, applied.status)
        assertEquals(2L, rowOf("record_transaction", 0, CommitGolden.NEW_TRANSACTION)[8])
        assertEquals(2L, rowOf("note", 0, CommitGolden.NEW_NOTE)[3])
        assertEquals(2L, rows("record_data_state").single()[2])
        val afterApply = snapshot()
        service().execute(CommitGolden.OPERATION)
        assertEquals(afterApply, snapshot())
        reopen()
        assertEquals(applied, service().execute(CommitGolden.OPERATION))
    }

    @Test
    fun receiptLossAfterCommitIsOutcomeUnknownAndReconcilesByIdentity() = runBlocking {
        val view = proposals.prepare(context, goldenIntents)
        val failingA = service(CommitGolden.ids()) { point, _ -> if (point == CommitCheckpoint.A_COMMITTED) throw RuntimeException("Receipt channel died") }
        try {
            failingA.confirm(command(view))
            error("Expected receipt loss")
        } catch (expected: CommitReceiptLossException) {
        }
        // The durable state is already committed: Pending operation, locked proposal.
        assertEquals("Pending", operations().single()[7])
        assertEquals("LOCKED", rowOf("change_proposal", 0, view.id)[11])
        val reconciled = service().confirm(command(view))
        assertEquals(CommitGolden.OPERATION, reconciled.operationId)
        assertFalse(reconciled.fresh)
        assertEquals(CommitStatus.Pending, reconciled.status)

        val failingB = service { point, _ -> if (point == CommitCheckpoint.B_COMMITTED) throw RuntimeException("Receipt channel died") }
        try {
            failingB.execute(CommitGolden.OPERATION)
            error("Expected receipt loss")
        } catch (expected: CommitReceiptLossException) {
        }
        val inspected = service().inspect(CommitGolden.OPERATION)!!
        assertEquals(CommitStatus.Applied, inspected.status)
        assertEquals(2L, rowOf("record_transaction", 0, CommitGolden.NEW_TRANSACTION)[8])
        val reexecuted = service().execute(CommitGolden.OPERATION)
        assertEquals(inspected, reexecuted)
        assertEquals(inspected, service().confirm(command(view)).copy(fresh = false))
    }

    @Test
    fun concurrentExecutionOfOneOperationHasExactlyOneBusinessResult() = runBlocking {
        val view = proposals.prepare(context, goldenIntents)
        service(CommitGolden.ids()).confirm(command(view))
        val results = onTwoConnections(
            { _, c -> c.execute(CommitGolden.OPERATION) },
            { _, c -> c.execute(CommitGolden.OPERATION) },
        )
        results.forEach { it.getOrThrow() }
        assertEquals(results[0].getOrThrow(), results[1].getOrThrow())
        assertEquals(CommitStatus.Applied, results[0].getOrThrow().status)
        assertEquals(2L, rowOf("record_transaction", 0, CommitGolden.NEW_TRANSACTION)[8])
        assertEquals(2L, rowOf("note", 0, CommitGolden.NEW_NOTE)[3])
        assertEquals(1, rows("transaction_note_link").count { it[0] == CommitGolden.NEW_TRANSACTION })
        assertEquals(2L, rows("record_data_state").single()[2])
        assertEquals(1, operations().size)
    }

    @Test
    fun operationsSurviveParentDeletionAndStayVerifiableAndExecutable() = runBlocking {
        val view = proposals.prepare(context, goldenIntents)
        service(CommitGolden.ids()).confirm(command(view))
        // Session deletion is a future native action (E2/F0); the parent rows are removed
        // directly here to prove commit_operation keeps its own verification basis.
        sql("DELETE FROM change_proposal WHERE id=?", view.id)
        sql("DELETE FROM agent_run WHERE id=?", fixture.run.id)
        sql("DELETE FROM agent_session WHERE id=?", fixture.session.id)
        val orphaned = snapshot()

        val verified = service().confirm(command(view))
        assertEquals(CommitGolden.OPERATION, verified.operationId)
        assertEquals(CommitStatus.Pending, verified.status)
        assertEquals(orphaned, snapshot())

        val applied = service().execute(CommitGolden.OPERATION)
        assertEquals(CommitStatus.Applied, applied.status)
        assertEquals(2L, rowOf("record_transaction", 0, CommitGolden.NEW_TRANSACTION)[8])
        assertEquals(CommitStatus.Applied, service().inspect(CommitGolden.OPERATION)!!.status)
        assertEquals(CommitStatus.Applied, service().confirm(command(view)).status)
        assertEquals(applied.result, service().confirm(command(view)).result)
    }

    @Test
    fun freshConfirmationRequiresEditableCurrentGenerationExactDisplay() = runBlocking {
        val view = proposals.prepare(context, goldenIntents)
        val cmd = command(view)
        val before = snapshot()
        expectCommitError(CommitError.Validation) { service().confirm(cmd.copy(proposalId = "not-an-id")) }
        expectCommitError(CommitError.Validation) { service().confirm(cmd.copy(displayedRevision = 0)) }
        expectCommitError(CommitError.Validation) { service().confirm(cmd.copy(displayedPayloadDigest = "ZZ")) }
        expectCommitError(CommitError.Validation) { service().confirm(cmd.copy(dataGeneration = "not-a-uuid")) }
        expectCommitError(CommitError.NotFound) { service().confirm(cmd.copy(proposalId = UUID.randomUUID().toString())) }
        expectCommitError(CommitError.ConfirmationMismatch) { service().confirm(cmd.copy(displayedPayloadDigest = sha256("foreign"))) }
        expectCommitError(CommitError.ConfirmationMismatch) { service().confirm(cmd.copy(displayedRevision = 2)) }
        assertEquals(before, snapshot())

        sql("UPDATE change_proposal SET state='LOCKED' WHERE id=?", view.id)
        expectCommitError(CommitError.ProposalFrozen) { service().confirm(cmd) }
        sql("UPDATE change_proposal SET state='EDITABLE' WHERE id=?", view.id)

        sql("DELETE FROM change_proposal WHERE id=?", view.id)
        sql("DELETE FROM agent_run WHERE id=?", fixture.run.id)
        sql("DELETE FROM agent_session WHERE id=?", fixture.session.id)
        expectCommitError(CommitError.NotFound) { service().confirm(cmd) }
        assertTrue(operations().isEmpty())

        val second = proposals.prepare(fixture.context(fixture.secondRun), listOf(fixture.createNote()))
        val secondCmd = command(second)
        sql("UPDATE change_proposal SET dataGeneration=?", fixture.staleGeneration)
        expectCommitError(CommitError.GenerationMismatch) { service().confirm(secondCmd) }
        sql("UPDATE change_proposal SET dataGeneration=?", fixture.generation)
        expectCommitError(CommitError.GenerationMismatch) { service().confirm(secondCmd.copy(dataGeneration = fixture.staleGeneration)) }
        sql("UPDATE change_proposal SET payloadDigest=?", "0".repeat(64))
        expectCommitError(CommitError.RecoveryRequired) { service().confirm(secondCmd) }
        sql("UPDATE change_proposal SET payloadDigest=?", second.payloadDigest)
        assertEquals(0, operations().size)
        assertTrue(service().confirm(secondCmd).fresh)
        assertEquals(1, operations().size)
    }

    @Test
    fun corruptOperationsFailClosedOnConfirmExecuteAndInspect() = runBlocking {
        val view = proposals.prepare(context, goldenIntents)
        service(CommitGolden.ids()).confirm(command(view))
        val original = operations().single()
        val digestConsistent = CommitGolden.FROZEN_JSON.replace("\"amountMinor\":5800", "\"amountMinor\":0")
        val corruptions: List<List<Any?>> = listOf(
            listOf("UPDATE commit_operation SET payloadDigest=?", "0".repeat(64)),
            listOf("UPDATE commit_operation SET frozenPayloadJson='{}'"),
            listOf("UPDATE commit_operation SET frozenPayloadJson=?, payloadDigest=?", digestConsistent, sha256(digestConsistent)),
            listOf("UPDATE commit_operation SET state='Weird'"),
            listOf("UPDATE commit_operation SET finishedAt=?", fixture.time),
            listOf("UPDATE commit_operation SET payloadFormatVersion=2"),
            listOf("UPDATE commit_operation SET createdGeneration='not-a-uuid'"),
            listOf("UPDATE commit_operation SET resultJson='{}'"),
        )
        for (corruption in corruptions) {
            sql(corruption[0] as String, *corruption.drop(1).toTypedArray())
            val corrupt = snapshot()
            expectCommitError(CommitError.RecoveryRequired) { service().confirm(command(view)) }
            expectCommitError(CommitError.RecoveryRequired) { service().execute(CommitGolden.OPERATION) }
            expectCommitError(CommitError.RecoveryRequired) { service().inspect(CommitGolden.OPERATION) }
            assertEquals(corrupt, snapshot())
            restoreOperation(original)
        }
        reopen()
        assertEquals(CommitStatus.Pending, service().confirm(command(view)).status)
    }

    @Test
    fun noChangeCommitsStayAppliedWithoutBumpingAnyVersion() = runBlocking {
        // The stored transaction carries the 餐饮 category, so "no change" must repeat it exactly.
        val identicalUpdate = ProposalIntent.UpdateTransaction(
            fixture.transaction.id, 3,
            fixture.input(amount = "58.00", memo = "午饭\n原样保留  ", categoryId = fixture.expense.id, categoryVersion = 1),
        )
        val intents = listOf(
            identicalUpdate,
            fixture.updateNote(body = "中文\n**符号** 与空格  "),
            fixture.linkExisting(),
        )
        val view = proposals.prepare(context, intents)
        val before = snapshot()
        val operation = service(listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())).confirm(command(view))
        val applied = service().execute(operation.operationId)
        assertEquals(CommitStatus.Applied, applied.status)
        assertEquals(
            AppliedCommitResult(
                1,
                listOf(
                    AppliedOutcome.UpdateTransaction(fixture.transaction.id, 3),
                    AppliedOutcome.UpdateNote(fixture.note.id, 7),
                    AppliedOutcome.LinkRecords(0),
                ),
            ),
            applied.result,
        )
        assertEquals(before.filterKeys { it != "change_proposal" && it != "commit_operation" }, snapshot().filterKeys { it != "change_proposal" && it != "commit_operation" })
        assertEquals("APPLIED", rowOf("change_proposal", 0, view.id)[11])
        assertEquals(1L, rows("record_data_state").single()[2])
    }

    @Test
    fun inspectIsReadOnlyAndDistinguishesUnknownFromMalformedIdentities() = runBlocking {
        assertNull(service().inspect(UUID.randomUUID().toString()))
        expectCommitError(CommitError.Validation) { service().inspect("not-an-id") }
        val view = proposals.prepare(context, goldenIntents)
        service(CommitGolden.ids()).confirm(command(view))
        val pending = snapshot()
        assertEquals(CommitStatus.Pending, service().inspect(CommitGolden.OPERATION)!!.status)
        assertEquals(pending, snapshot())
        service().execute(CommitGolden.OPERATION)
        val applied = snapshot()
        assertEquals(CommitStatus.Applied, service().inspect(CommitGolden.OPERATION)!!.status)
        assertEquals(applied, snapshot())
    }

    @Test
    fun mismatchedCreationIdentityFailsClosedBeforeAnyFreeze() = runBlocking {
        val view = proposals.prepare(context, goldenIntents)
        // A well-formed identity that no longer binds to the origin run is C0 corruption; C1
        // must refuse to freeze it into an operation even though every displayed field matches.
        sql("UPDATE change_proposal SET creationRequestId=? WHERE id=?", UUID.randomUUID().toString(), view.id)
        val corrupt = snapshot()
        expectCommitError(CommitError.RecoveryRequired) { service().confirm(command(view)) }
        assertEquals(corrupt, snapshot())
        assertTrue(operations().isEmpty())
        assertEquals("EDITABLE", rowOf("change_proposal", 0, view.id)[11])
        assertEquals(1L, rowOf("change_proposal", 0, view.id)[12])
        // C0 parity: the same row is unreadable through the proposal service too.
        expectProposalError(ProposalError.RecoveryRequired) { proposals.proposal(view.id) }
    }

    @Test
    fun appliedReceiptsThatDoNotCorrespondToTheFrozenOperationsFailClosed() = runBlocking {
        val view = proposals.prepare(context, goldenIntents)
        service(CommitGolden.ids()).confirm(command(view))
        service().execute(CommitGolden.OPERATION)
        val original = operations().single()
        val shortReceipt = CommitResultCodec.canonical(
            AppliedCommitResult(2, listOf(AppliedOutcome.CreateNote(CommitGolden.NEW_NOTE, 1))),
        )
        val swappedIdentity = CommitResultCodec.canonical(
            AppliedCommitResult(
                2,
                listOf(
                    AppliedOutcome.CreateTransaction(CommitGolden.NEW_NOTE, 1),
                    AppliedOutcome.CreateNote(CommitGolden.NEW_NOTE, 1),
                    AppliedOutcome.LinkRecords(1),
                ),
            ),
        )
        val oversizedCount = CommitResultCodec.canonical(
            AppliedCommitResult(
                2,
                listOf(
                    AppliedOutcome.CreateTransaction(CommitGolden.NEW_TRANSACTION, 1),
                    AppliedOutcome.CreateNote(CommitGolden.NEW_NOTE, 1),
                    AppliedOutcome.LinkRecords(2),
                ),
            ),
        )
        val corruptions: List<List<Any?>> = listOf(
            listOf("UPDATE commit_operation SET resultJson=?", shortReceipt),
            listOf("UPDATE commit_operation SET resultJson=?", swappedIdentity),
            listOf("UPDATE commit_operation SET resultJson=?", oversizedCount),
            // Well-formed receipts whose rejection cause contradicts the persisted state.
            listOf("UPDATE commit_operation SET state='RejectedConflict', rejectionCode='Validation', resultJson=NULL"),
            listOf("UPDATE commit_operation SET state='RejectedInvalid', rejectionCode='VersionConflict', resultJson=NULL"),
            listOf("UPDATE commit_operation SET state='RejectedConflict', rejectionCode='NoSuchError', resultJson=NULL"),
        )
        for (corruption in corruptions) {
            sql(corruption[0] as String, *corruption.drop(1).toTypedArray())
            val corrupt = snapshot()
            expectCommitError(CommitError.RecoveryRequired) { service().confirm(command(view)) }
            expectCommitError(CommitError.RecoveryRequired) { service().execute(CommitGolden.OPERATION) }
            expectCommitError(CommitError.RecoveryRequired) { service().inspect(CommitGolden.OPERATION) }
            assertEquals(corrupt, snapshot())
            restoreOperation(original)
        }
        reopen()
        assertEquals(CommitStatus.Applied, service().inspect(CommitGolden.OPERATION)!!.status)
    }

    /** Parks every dispatch until drained, so a test can cancel exactly at a return boundary. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(block)
        }

        fun drain() {
            while (true) (queue.poll() ?: return).run()
        }
    }

    private suspend fun awaitDurable(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { "Target state never became durable" }
            delay(20)
        }
    }

    @Test
    fun committedConfirmationCancelledAtTheReturnBoundaryIsReceiptLoss() = runBlocking {
        val view = proposals.prepare(context, goldenIntents)
        val dispatcher = HeldDispatcher()
        val scope = CoroutineScope(dispatcher + Job())
        val deferred = scope.async { service(CommitGolden.ids()).confirm(command(view)) }
        // Run to the io suspension; the confirm body commits on Dispatchers.IO while its
        // completion stays parked in the held dispatcher.
        dispatcher.drain()
        awaitDurable { operations().isNotEmpty() }
        // The operation is durable and the caller has not resumed yet: this cancellation races
        // the withContext return, which must discard the value without losing the classification.
        deferred.cancel()
        val error = cancelAtReturnBoundaryAndAwait(deferred, dispatcher)
        assertTrue(error is CommitReceiptLossException)
        assertEquals("Pending", operations().single()[7])
        assertEquals("LOCKED", rowOf("change_proposal", 0, view.id)[11])
        val reconciled = service().confirm(command(view))
        assertEquals(CommitGolden.OPERATION, reconciled.operationId)
        assertFalse(reconciled.fresh)
        assertEquals(CommitStatus.Pending, reconciled.status)
    }

    @Test
    fun committedExecutionCancelledAtTheReturnBoundaryIsReceiptLoss() = runBlocking {
        val view = proposals.prepare(context, goldenIntents)
        service(CommitGolden.ids()).confirm(command(view))
        val dispatcher = HeldDispatcher()
        val scope = CoroutineScope(dispatcher + Job())
        val deferred = scope.async { service().execute(CommitGolden.OPERATION) }
        dispatcher.drain()
        awaitDurable { operations().isNotEmpty() && operations().single()[7] == "Applied" }
        deferred.cancel()
        val error = cancelAtReturnBoundaryAndAwait(deferred, dispatcher)
        assertTrue(error is CommitReceiptLossException)
        // The facts committed exactly once; a later idempotent re-execution returns the same result.
        assertEquals(2L, rowOf("record_transaction", 0, CommitGolden.NEW_TRANSACTION)[8])
        assertEquals(2L, rowOf("note", 0, CommitGolden.NEW_NOTE)[3])
        val after = snapshot()
        val reapplied = service().execute(CommitGolden.OPERATION)
        assertEquals(CommitStatus.Applied, reapplied.status)
        assertEquals(after, snapshot())
    }

    /** Resumes the parked caller (the cancellation race) and keeps draining its follow-ups. */
    private suspend fun <T> cancelAtReturnBoundaryAndAwait(
        deferred: kotlinx.coroutines.Deferred<T>,
        dispatcher: HeldDispatcher,
    ): Throwable? = kotlinx.coroutines.coroutineScope {
        val drainer = launch(Dispatchers.Default) {
            while (!deferred.isCompleted) {
                dispatcher.drain()
                delay(5)
            }
            dispatcher.drain()
        }
        val error = runCatching { deferred.await() }.exceptionOrNull()
        drainer.cancel()
        error
    }

    private fun seededTransactionCount() = snapshot().getValue("record_transaction").size

    private fun restoreOperation(row: List<Any?>) = sql(
        """UPDATE commit_operation SET id=?, proposalId=?, confirmedRevision=?, createdGeneration=?,
        frozenPayloadJson=?, payloadDigest=?, payloadFormatVersion=?, state=?, createdAt=?,
        finishedAt=?, resultJson=?, rejectionCode=? WHERE 1""",
        *row.toTypedArray(),
    )
}

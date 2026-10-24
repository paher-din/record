package com.paherdin.record2.storage

import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.ai.runs.AgentRunCoordinator
import com.paherdin.record2.ai.runs.AgentRunOwner
import com.paherdin.record2.ai.runs.AgentRunException
import com.paherdin.record2.ai.runs.AgentRunError
import com.paherdin.record2.ai.security.AiMode
import com.paherdin.record2.ai.security.KeystoreCredentialVault
import com.paherdin.record2.ai.security.RecordProviderReceivers
import com.paherdin.record2.proposals.CommitService
import com.paherdin.record2.proposals.ConfirmationCommand
import com.paherdin.record2.proposals.ProposalIntent
import com.paherdin.record2.proposals.ProposalRunContext
import com.paherdin.record2.proposals.ProposalService
import com.paherdin.record2.sessions.AgentHistoryError
import com.paherdin.record2.sessions.MessageResultKind
import com.paherdin.record2.sessions.RunOutcome
import com.paherdin.record2.sessions.RunState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Real-Room contract for the E2 run/session lifecycle (Plan E2, Spec 5.2.6, SES-03/04/08/13/16):
 * the application-level owner survives caller-scope death, user cancel and authorization changes
 * persist terminal state before stopping in-memory work, and the explicit session-deletion
 * transaction keeps business facts and every commit_operation row while refusing unsafe content.
 * Cross-process recovery with a real killed process is covered by E2ProcessRecoveryContractTest
 * plus tools/verify-e2-process.py.
 */
class SessionLifecycleContractTest {
    private lateinit var fixture: E2LifecycleFixture

    @Before
    fun setUp() {
        fixture = E2LifecycleFixture(UUID.randomUUID().toString())
        fixture.assertDatabaseAbsent()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun coordinator(http: ScriptedDeviceHttp): AgentRunCoordinator = AgentRunCoordinator(
        security = fixture.security,
        history = fixture.history,
        processOwnerId = fixture.owner,
        httpClientFactory = deviceScriptFactory(http),
    )

    private fun owner(http: ScriptedDeviceHttp): AgentRunOwner = AgentRunOwner(
        coordinator = coordinator(http),
        history = fixture.history,
        processOwnerId = fixture.owner,
    )

    private suspend fun awaitDeviceRequests(http: ScriptedDeviceHttp, count: Int) {
        withTimeout(20_000) { while (http.requests.size < count) delay(20) }
    }

    @Test
    fun uiScopeDeathDoesNotCancelAnOwnedRun() = runBlocking {
        fixture.initialize()
        val session = fixture.history.createSession("e2 ui scope")
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedDeviceHttp { _, _ -> gate.await(); chatDeviceResponse("SURVIVED") }
        val runOwner = owner(http)
        // The UI caller launches from its own scope; that scope then dies outright.
        val callerScope = CoroutineScope(Dispatchers.Default + Job())
        val handleInCaller = CompletableDeferred<com.paherdin.record2.ai.runs.OwnedRunHandle>()
        callerScope.launch { handleInCaller.complete(runOwner.launchRun(session.id, "hello")) }
        val handle = withTimeout(20_000) { handleInCaller.await() }
        awaitDeviceRequests(http, 1)
        callerScope.cancel()
        assertTrue(handle.isActive)
        gate.complete(Unit)
        val result = withTimeout(20_000) { handle.await() }
        assertEquals("SURVIVED", result.answer)
        val messages = fixture.history.sessionMessages(session.id)
        assertEquals(2, messages.size)
        assertEquals(RunState.COMPLETED, messages[1].runState)
        runOwner.close()
        Unit
    }

    @Test
    fun userCancelPersistsCancelledFreesSlotAndRefusesLateCompletion() = runBlocking {
        fixture.initialize()
        val session = fixture.history.createSession("e2 cancel")
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedDeviceHttp { index, _ ->
            if (index == 1) gate.await()
            chatDeviceResponse("TOO-LATE")
        }
        val runOwner = owner(http)
        val handle = runOwner.launchRun(session.id, "运行中的输入")
        awaitDeviceRequests(http, 1)
        val epochAtStart = fixture.permitEpoch()
        val receipt = runOwner.cancelRun(session.id)
        assertEquals(handle.runId, receipt?.runId)
        assertEquals(RunState.CANCELLED, receipt?.state)
        withTimeout(20_000) {
            try { handle.await(); fail("cancelled run must not await") } catch (_: CancellationException) {}
        }
        // Durable shape: CANCELLED with slot freed, input still readable, no final reply.
        val runRow = fixture.rows("agent_run").single { it[0] == handle.runId }
        assertEquals("CANCELLED", runRow[9])
        assertEquals(null, runRow[10])
        assertEquals("CANCELLED", runRow[13])
        val messages = fixture.history.sessionMessages(session.id)
        assertEquals(listOf("运行中的输入"), messages.map { it.body })
        val afterCancel = fixture.snapshot()
        // A late completion from the stopped network work changes nothing.
        expectHistoryError(AgentHistoryError.RunInvalidated) {
            fixture.history.completeRun(
                handle.runId, fixture.receiver.identity, epochAtStart, fixture.owner,
                RunOutcome(MessageResultKind.Answer, "TOO-LATE"),
            )
        }
        assertEquals(afterCancel, fixture.snapshot())
        // The session accepts a fresh run once the slot is free.
        val next = runOwner.launchRun(session.id, "再试一次")
        assertEquals("TOO-LATE", withTimeout(20_000) { next.await() }.answer)
        runOwner.close()
        Unit
    }

    @Test
    fun revokeFailsRunningRunsAtomicallyAndTheOwnerStopsOutbound() = runBlocking {
        fixture.initialize()
        val session = fixture.history.createSession("e2 revoke")
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedDeviceHttp { _, _ -> gate.await(); chatDeviceResponse("TOO-LATE") }
        val runOwner = owner(http)
        val handle = runOwner.launchRun(session.id, "运行中的输入")
        awaitDeviceRequests(http, 1)
        val epochAtStart = fixture.permitEpoch()
        // The control mutation itself fails every RUNNING run in the same transaction.
        fixture.security.revoke()
        val runRow = fixture.rows("agent_run").single { it[0] == handle.runId }
        assertEquals("FAILED", runRow[9])
        assertEquals(null, runRow[10])
        assertEquals("AUTHORIZATION", runRow[13])
        // The owner sweep stops the in-memory work; the late network reply never lands. The
        // stopped run ends either as job cancellation or as the surfaced terminal refusal.
        runOwner.onAuthorizationChanged()
        withTimeout(20_000) {
            try {
                handle.await()
                fail("invalidated run must not await")
            } catch (_: CancellationException) {
            } catch (stopped: AgentRunException) {
                assertEquals(AgentRunError.RunInvalidated, stopped.code)
            }
        }
        gate.complete(Unit)
        delay(300)
        assertEquals(1, http.requests.size)
        val afterGate = fixture.snapshot()
        expectHistoryError(AgentHistoryError.RunInvalidated) {
            fixture.history.completeRun(
                handle.runId, fixture.receiver.identity, epochAtStart, fixture.owner,
                RunOutcome(MessageResultKind.Answer, "TOO-LATE"),
            )
        }
        assertEquals(afterGate, fixture.snapshot())
        assertEquals(0, fixture.history.sessionMessages(session.id).count { it.runState == RunState.COMPLETED })
        runOwner.close()
        Unit
    }

    @Test
    fun aiOffFailsRunningRunsAtomically() = runBlocking {
        fixture.initialize()
        val session = fixture.history.createSession("e2 ai off")
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedDeviceHttp { index, _ ->
            if (index == 1) gate.await()
            chatDeviceResponse("TOO-LATE")
        }
        val runOwner = owner(http)
        val handle = runOwner.launchRun(session.id, "运行中的输入")
        awaitDeviceRequests(http, 1)
        fixture.security.setMode(AiMode.OFF)
        val runRow = fixture.rows("agent_run").single { it[0] == handle.runId }
        assertEquals("FAILED", runRow[9])
        assertEquals("AUTHORIZATION", runRow[13])
        // The sweep stops the work; a NEW run cannot even start without authorization.
        runOwner.onAuthorizationChanged()
        withTimeout(20_000) {
            try {
                handle.await()
            } catch (_: CancellationException) {
            } catch (stopped: AgentRunException) {
                assertEquals(AgentRunError.RunInvalidated, stopped.code)
            }
        }
        val denied = runOwner.launchRun(session.id, "无授权输入")
        try {
            withTimeout(20_000) { denied.await() }
            fail("new run must be denied without authorization")
        } catch (error: AgentRunException) {
            assertEquals(AgentRunError.AuthorizationRequired, error.code)
        }
        gate.complete(Unit)
        delay(300)
        // Only the first (invalidated) request ever went out; the denied run added none.
        assertEquals(1, http.requests.size)
        runOwner.close()
        Unit
    }

    @Test
    fun deleteSessionCancelsRunsDiscardsUnconfirmedAndRetainsOperations() = runBlocking {
        fixture.initialize()
        val rich = fixture.history.createSession("e2 rich")
        val other = fixture.history.createSession("e2 other")

        // run1 completes with proposal1 confirmed and Applied (keeps the verification basis).
        val run1 = fixture.history.startRun(
            rich.id, "记账", fixture.receiver.identity, fixture.permitEpoch(), fixture.owner,
            fixture.history.newRunIdentity(),
        )
        val proposal1 = fixture.proposals.prepare(
            ProposalRunContext(rich.id, run1.runId, fixture.owner, fixture.receiver.identity, run1.authEpoch),
            listOf(ProposalIntent.CreateNote("#n1", "已确认笔记", "2026-10-23T12:30:00", "Asia/Shanghai")),
        )
        fixture.history.completeRun(
            run1.runId, fixture.receiver.identity, run1.authEpoch, fixture.owner,
            RunOutcome(MessageResultKind.ProposalReady, "提案就绪", proposalId = proposal1.id, proposalRevision = proposal1.revision),
        )
        val operation1 = fixture.commit.confirm(
            ConfirmationCommand(proposal1.id, proposal1.revision, proposal1.payloadDigest, proposal1.dataGeneration),
        )
        val applied1 = fixture.commit.execute(operation1.operationId)
        assertEquals(com.paherdin.record2.proposals.CommitStatus.Applied, applied1.status)

        // run2 completes with proposal2 confirmed and left Pending (awaiting verification).
        val run2 = fixture.history.startRun(
            rich.id, "再记一笔", fixture.receiver.identity, fixture.permitEpoch(), fixture.owner,
            fixture.history.newRunIdentity(),
        )
        val proposal2 = fixture.proposals.prepare(
            ProposalRunContext(rich.id, run2.runId, fixture.owner, fixture.receiver.identity, run2.authEpoch),
            listOf(ProposalIntent.CreateNote("#n1", "待核查笔记", "2026-10-23T12:30:00", "Asia/Shanghai")),
        )
        val operation2 = fixture.commit.confirm(
            ConfirmationCommand(proposal2.id, proposal2.revision, proposal2.payloadDigest, proposal2.dataGeneration),
        )
        fixture.history.completeRun(
            run2.runId, fixture.receiver.identity, run2.authEpoch, fixture.owner,
            RunOutcome(MessageResultKind.Answer, "已准备"),
        )

        // run3 stays RUNNING: the active run the deletion must cancel.
        val run3 = fixture.history.startRun(
            rich.id, "运行中的输入", fixture.receiver.identity, fixture.permitEpoch(), fixture.owner,
            fixture.history.newRunIdentity(),
        )

        // A second session keeps an unconfirmed EDITABLE proposal; deleting `rich` must not
        // touch it (scope by sessionId, FK RESTRICT makes cross-session orphaning impossible).
        val run4 = fixture.history.startRun(
            other.id, "另一会话", fixture.receiver.identity, fixture.permitEpoch(), fixture.owner,
            fixture.history.newRunIdentity(),
        )
        val proposal3 = fixture.proposals.prepare(
            ProposalRunContext(other.id, run4.runId, fixture.owner, fixture.receiver.identity, run4.authEpoch),
            listOf(ProposalIntent.CreateNote("#n1", "别动我", "2026-10-23T12:30:00", "Asia/Shanghai")),
        )
        fixture.history.completeRun(
            run4.runId, fixture.receiver.identity, run4.authEpoch, fixture.owner,
            RunOutcome(MessageResultKind.ProposalReady, "提案就绪", proposalId = proposal3.id, proposalRevision = proposal3.revision),
        )

        val before = fixture.snapshot()
        val noteFactsBefore = before.getValue("note").size

        val report = fixture.history.deleteSession(rich.id, fixture.owner)
        assertEquals(rich.id, report.sessionId)
        assertEquals(1, report.cancelledActiveRuns)
        assertEquals(0, report.discardedProposals)
        assertEquals(2, report.proposalsWithRetainedOperations)
        assertEquals(3, report.deletedRuns)
        assertEquals(5, report.deletedMessages)
        assertEquals(1, report.retainedOperations.applied)
        assertEquals(1, report.retainedOperations.pending)

        val after = fixture.snapshot()
        // The rich session and all of its rows are gone; the other session is untouched.
        assertTrue(after.getValue("agent_session").all { it[0] == other.id })
        assertTrue(after.getValue("agent_run").all { it[0] == run4.runId })
        assertTrue(after.getValue("agent_message").all { it[1] == other.id })
        assertTrue(after.getValue("change_proposal").all { it[0] == proposal3.id })
        // Business facts and both operation rows are retained byte-for-byte.
        assertEquals(noteFactsBefore, after.getValue("note").size)
        assertEquals(
            before.getValue("commit_operation").sortedBy { it[0] as String },
            after.getValue("commit_operation").sortedBy { it[0] as String },
        )

        // Nothing resurrects: every late writer for the deleted session fails closed.
        expectHistoryError(AgentHistoryError.NotFound) {
            fixture.history.completeRun(
                run3.runId, fixture.receiver.identity, run3.authEpoch, fixture.owner,
                RunOutcome(MessageResultKind.Answer, "迟到回复"),
            )
        }
        expectHistoryError(AgentHistoryError.NotFound) {
            fixture.history.startRun(
                rich.id, "复活输入", fixture.receiver.identity, fixture.permitEpoch(), fixture.owner,
                fixture.history.newRunIdentity(),
            )
        }
        expectHistoryError(AgentHistoryError.NotFound) { fixture.history.sessionMessages(rich.id) }
        expectHistoryError(AgentHistoryError.NotFound) { fixture.history.session(rich.id) }

        // Retained operations stay independently verifiable, and the user-controlled same-id
        // retry still works with the proposal row gone (C1 identity semantics).
        assertEquals("Applied", fixture.commit.inspect(operation1.operationId)!!.status.name)
        assertEquals("Pending", fixture.commit.inspect(operation2.operationId)!!.status.name)
        val retry = fixture.commit.execute(operation2.operationId)
        assertEquals("Applied", retry.status.name)
        assertEquals(noteFactsBefore + 1, fixture.snapshot().getValue("note").size)
        assertEquals(retry, fixture.commit.execute(operation2.operationId).copy(fresh = retry.fresh))
        // The other session's unconfirmed proposal is still readable and revisable.
        assertEquals("EDITABLE", fixture.proposals.proposal(proposal3.id)!!.state.name)
        Unit
    }

    @Test
    fun deleteSessionRefusesUnknownOperationStateAndRollsBack() = runBlocking {
        fixture.initialize()
        val session = fixture.history.createSession("e2 unsafe")
        val run = fixture.history.startRun(
            session.id, "记账", fixture.receiver.identity, fixture.permitEpoch(), fixture.owner,
            fixture.history.newRunIdentity(),
        )
        val proposal = fixture.proposals.prepare(
            ProposalRunContext(session.id, run.runId, fixture.owner, fixture.receiver.identity, run.authEpoch),
            listOf(ProposalIntent.CreateNote("#n1", "损坏状态", "2026-10-23T12:30:00", "Asia/Shanghai")),
        )
        val operation = fixture.commit.confirm(
            ConfirmationCommand(proposal.id, proposal.revision, proposal.payloadDigest, proposal.dataGeneration),
        )
        // An operation state the deletion cannot certify never cascade-deletes (E2 risk rule).
        fixture.database.openHelper.writableDatabase.execSQL(
            "UPDATE commit_operation SET state = 'CorruptedBeyondVocabulary' WHERE id = ?",
            arrayOf(operation.operationId),
        )
        val afterCorruption = fixture.snapshot()
        expectHistoryError(AgentHistoryError.UnsafeSessionContent) {
            fixture.history.deleteSession(session.id, fixture.owner)
        }
        assertEquals(afterCorruption, fixture.snapshot())
        // The refusal is data-driven: once the state parses again, the same deletion succeeds.
        fixture.database.openHelper.writableDatabase.execSQL(
            "UPDATE commit_operation SET state = 'Pending' WHERE id = ?",
            arrayOf(operation.operationId),
        )
        val report = fixture.history.deleteSession(session.id, fixture.owner)
        assertEquals(1, report.retainedOperations.pending)
        Unit
    }

    @Test
    fun deletionCheckpointFaultRollsBackKeepingHistoryIntact() = runBlocking {
        fixture.initialize()
        val session = fixture.history.createSession("e2 fault")
        val run = fixture.history.startRun(
            session.id, "运行中的输入", fixture.receiver.identity, fixture.permitEpoch(), fixture.owner,
            fixture.history.newRunIdentity(),
        )
        val proposal = fixture.proposals.prepare(
            ProposalRunContext(session.id, run.runId, fixture.owner, fixture.receiver.identity, run.authEpoch),
            listOf(ProposalIntent.CreateNote("#n1", "故障保留", "2026-10-23T12:30:00", "Asia/Shanghai")),
        )
        fixture.commit.confirm(
            ConfirmationCommand(proposal.id, proposal.revision, proposal.payloadDigest, proposal.dataGeneration),
        )
        val before = fixture.snapshot()
        fixture.reinstateHistory(checkpoint = { if (it == SessionCheckpoint.SESSION_DELETED) throw SQLiteFault() })
        expectHistoryError(AgentHistoryError.RecoverableIO) {
            fixture.history.deleteSession(session.id, fixture.owner)
        }
        assertEquals(before, fixture.snapshot())
        // After the fault clears, the same deletion completes.
        fixture.reinstateHistory()
        val report = fixture.history.deleteSession(session.id, fixture.owner)
        assertEquals(1, report.cancelledActiveRuns)
        Unit
    }

    @Test
    fun newRunAfterInterruptionRevalidatesAuthorizationAndExcludesInterruptedInput() = runBlocking {
        fixture.initialize()
        val session = fixture.history.createSession("e2 recovery")
        val stale = fixture.history.startRun(
            session.id, "被中断的输入", fixture.receiver.identity, fixture.permitEpoch(), fixture.owner,
            fixture.history.newRunIdentity(),
        )
        // Simulated process death: the next process adopts ownership and interrupts leftovers.
        val nextOwner = "${fixture.owner}-next"
        val receipt = fixture.history.beginProcessOwnership(nextOwner)
        assertTrue(receipt.adopted)
        assertEquals(1, receipt.interruptedRuns)
        assertEquals("INTERRUPTED", fixture.rows("agent_run").single { it[0] == stale.runId }[9])

        val http = ScriptedDeviceHttp { _, _ -> chatDeviceResponse("RECOVERED") }
        val runOwner = AgentRunOwner(
            coordinator = AgentRunCoordinator(
                security = fixture.security,
                history = fixture.history,
                processOwnerId = nextOwner,
                httpClientFactory = deviceScriptFactory(http),
            ),
            history = fixture.history,
            processOwnerId = nextOwner,
        )
        // AI is OFF in the recovered process: a new run is denied before any network work.
        fixture.security.setMode(AiMode.OFF)
        val denied = runOwner.launchRun(session.id, "恢复后的输入")
        try {
            withTimeout(20_000) { denied.await() }
            fail("new run must be denied while AI is off")
        } catch (error: AgentRunException) {
            assertEquals(AgentRunError.AuthorizationRequired, error.code)
        }
        assertEquals(0, http.requests.size)

        fixture.security.setMode(AiMode.FULL)
        val handle = runOwner.launchRun(session.id, "恢复后的输入")
        val result = withTimeout(20_000) { handle.await() }
        assertEquals("RECOVERED", result.answer)
        // The interrupted input never entered the new run's model context.
        val request = http.requests.single()
        assertFalse(request.contains("被中断的输入"))
        assertTrue(request.contains("恢复后的输入"))
        // Full user-visible history keeps the interrupted input readable (E1 rule, re-pinned).
        val bodies = fixture.history.sessionMessages(session.id).map { it.body }
        assertEquals(listOf("被中断的输入", "恢复后的输入", result.answer), bodies)
        runOwner.close()
        Unit
    }

    private class SQLiteFault : android.database.sqlite.SQLiteException("synthetic deletion fault")
}

/** Private per-test database, vault and services for the E2 lifecycle contract (C1/B2 style). */
internal class E2LifecycleFixture(
    private val id: String,
    databaseName: String? = null,
) {
    val name = databaseName ?: "e2-contract-$id.db"
    val directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
        "e2-vault-$id",
    )
    val owner = "e2-owner-$id"
    val receiver = RecordProviderReceivers.deepSeek
    private val sentinel = "r2-e2-synthetic-3f8d1a6c5b9e2d74"

    var database: RecordDatabase = e2Database(name)
        private set
    val vault = KeystoreCredentialVault(directory, "record2.ai.e2.$id")
    val security = RoomAiSecurityService(database, vault, clock = sessionClock)
    val finance = RoomFinanceService(database, sessionClock)
    var history: RoomAgentHistoryService = RoomAgentHistoryService(database, sessionClock)
        private set
    val proposals: ProposalService = RoomProposalService(database, sessionClock)
    val commit: CommitService = RoomCommitService(database, sessionClock)

    fun assertDatabaseAbsent() {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)
        check(!path.exists()) { "$name must start absent" }
    }

    suspend fun initialize() {
        finance.initialize()
        history.beginProcessOwnership(owner)
        security.setMode(AiMode.FULL)
        security.selectReceiver(receiver)
        security.grant(receiver)
        security.replaceCredential(sentinel.toCharArray())
    }

    fun reinstateHistory(checkpoint: (SessionCheckpoint) -> Unit = {}) {
        history = RoomAgentHistoryService(database, sessionClock, checkpoint)
    }

    suspend fun permitEpoch(): Long = security.requirePermit(receiver).authEpoch

    fun rows(table: String): List<List<Any?>> = snapshotRows(table)

    fun snapshot(): Map<String, List<List<Any?>>> = SchemaDiskObserver.snapshot(
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name),
    )

    private fun snapshotRows(table: String): List<List<Any?>> = snapshot().getValue(table)

    fun close() {
        database.close()
    }
}

internal fun e2Database(name: String): RecordDatabase {
    check(Regex("e2-(contract|process)-[a-z0-9-]+\\.db").matches(name)) { "unexpected e2 database name $name" }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == "com.paherdin.record2.debug")
    return androidx.room.Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
}

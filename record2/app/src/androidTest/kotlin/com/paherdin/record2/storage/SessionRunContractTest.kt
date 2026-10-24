package com.paherdin.record2.storage

import android.database.sqlite.SQLiteFullException
import com.paherdin.record2.ai.runs.AgentRunCoordinator
import com.paherdin.record2.ai.runs.AgentRunDefaults
import com.paherdin.record2.sessions.AgentHistoryError
import com.paherdin.record2.sessions.MessageResultKind
import com.paherdin.record2.sessions.MessageReference
import com.paherdin.record2.sessions.RunOutcome
import com.paherdin.record2.sessions.RunState
import com.paherdin.record2.sessions.RunTerminal
import com.paherdin.record2.sessions.SafeRunErrorCode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real-Room contract for the authoritative session history and the run transactions
 * (B0 §3.4/§6, SES-01/02/04/10-12). Every assertion reads the SQLite file through the
 * independent disk observer; the writer path is never used as its own oracle. Network is
 * fully scripted; the E0 Keystore chain stays real.
 */
class SessionRunContractTest {
    private val id = schemaId()
    private val fixture = SessionContractFixture(id)
    private val database get() = fixture.database
    private val history get() = fixture.history
    private val security get() = fixture.security
    private val owner get() = fixture.owner
    private val receiver get() = fixture.receiver
    private val sentinel get() = fixture.sentinel
    private lateinit var generation: String

    @Before fun open() = runBlocking {
        check(!databasePath().exists())
        generation = fixture.finance.initialize().generation
        fixture.initialize()
    }

    @After fun close() {
        database.close()
        assertTrue(fixture.directory.deleteRecursively() || !fixture.directory.exists())
    }

    private fun databasePath() =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.getDatabasePath(fixture.name)

    private fun snapshot() = fixture.snapshot()
    private fun rows(table: String) = snapshot().getValue(table)

    private fun runRow(runId: String) = rows("agent_run").single { it[0] == runId }

    private fun coordinator(
        http: ScriptedDeviceHttp,
        window: Int = AgentRunDefaults.HISTORY_WINDOW,
        tools: ai.koog.agents.core.tools.ToolRegistry = ai.koog.agents.core.tools.ToolRegistry.EMPTY,
    ): AgentRunCoordinator = AgentRunCoordinator(
        security = security,
        history = history,
        processOwnerId = owner,
        historyWindow = window,
        toolRegistry = tools,
        httpClientFactory = deviceScriptFactory(http),
    )

    private suspend fun completeSimpleRun(reply: String = "第一次回复"): Pair<String, String> {
        val session = history.createSession()
        val permit = security.requirePermit(receiver)
        val start = history.startRun(session.id, "第一次输入", receiver.identity, permit.authEpoch, owner, history.newRunIdentity())
        history.completeRun(
            start.runId, receiver.identity, permit.authEpoch, owner,
            RunOutcome(MessageResultKind.Answer, reply),
        )
        return session.id to start.runId
    }

    @Test
    fun startRunPersistsUserMessageRunningRunAndBoundaryAtomically() = runBlocking {
        val before = snapshot()
        val session = history.createSession("采购清单")
        val afterCreate = snapshot()
        assertEquals(before.filterKeys { it != "agent_session" }, afterCreate.filterKeys { it != "agent_session" })
        val permit = security.requirePermit(receiver)
        val start = history.startRun(session.id, "记一笔午饭 32 元", receiver.identity, permit.authEpoch, owner, history.newRunIdentity())
        val disk = rows("agent_session").single()
        assertEquals(listOf(session.id, "采购清单", sessionClock.millis(), sessionClock.millis(), 1L, 1L), disk)
        assertEquals(
            listOf(
                start.runId, session.id, generation, owner, receiver.identity, permit.authEpoch,
                start.proposalCreationRequestId, 0L, 1L, "RUNNING", 1L,
                sessionClock.millis(), null, null,
            ),
            runRow(start.runId),
        )
        assertEquals(
            listOf(
                start.userMessageId, session.id, start.runId, 0L, "USER", "记一笔午饭 32 元",
                sessionClock.millis(), "INPUT", null, null, null,
            ),
            rows("agent_message").single(),
        )
        assertTrue(rows("agent_message_reference").isEmpty())
        assertEquals(1, history.sessionMessages(session.id).size)
    }

    @Test
    fun concurrentStartsElectExactlyOneRunningRunAndLosingInputPersistsNothing() = runBlocking {
        val session = history.createSession()
        val permit = security.requirePermit(receiver)
        val second = sessionDatabase(fixture.name)
        try {
            val other = RoomAgentHistoryService(second, sessionClock)
            val outcomes = coroutineScope {
                listOf(
                    async { runCatching { history.startRun(session.id, "输入A", receiver.identity, permit.authEpoch, owner, history.newRunIdentity()) } },
                    async { runCatching { other.startRun(session.id, "输入B", receiver.identity, permit.authEpoch, owner, other.newRunIdentity()) } },
                ).awaitAll()
            }
            assertEquals(1, outcomes.count { it.isSuccess })
            expectHistoryError(AgentHistoryError.SessionBusy) {
                history.startRun(session.id, "输入C", receiver.identity, permit.authEpoch, owner, history.newRunIdentity())
            }
            assertEquals(1, rows("agent_run").size)
            assertEquals("RUNNING", rows("agent_run").single()[9])
            val messages = rows("agent_message")
            assertEquals(1, messages.size)
            val winnerBody = history.sessionMessages(session.id).single().body
            assertTrue(winnerBody in setOf("输入A", "输入B"))
            assertEquals(1L, rows("agent_session").single()[5])
        } finally {
            second.close()
        }
    }

    @Test
    fun crossSessionHistoryStaysIsolated() = runBlocking {
        val (sessionA, _) = completeSimpleRun()
        val sessionB = history.createSession("另一段会话")
        assertTrue(history.completedHistory(sessionB.id, 0).isEmpty())
        assertEquals(0, history.sessionMessages(sessionB.id).size)
        assertEquals(2, history.sessionMessages(sessionA).size)
        val boundary = history.session(sessionA).nextMessageSequence
        val loaded = history.completedHistory(sessionA, boundary)
        assertEquals(listOf("USER", "ASSISTANT"), loaded.map { it.role.name })
        assertEquals(listOf("第一次输入", "第一次回复"), loaded.map { it.body })
    }

    @Test
    fun completionFaultsRollBackAndCommittedReceiptsReconcileByIdentity() = runBlocking {
        val session = history.createSession()
        val permit = security.requirePermit(receiver)
        val start = history.startRun(session.id, "输入", receiver.identity, permit.authEpoch, owner, history.newRunIdentity())
        val afterStart = snapshot()

        fixture.reinstateHistory(checkpoint = {
            if (it == SessionCheckpoint.COMPLETION_WRITTEN) throw SQLiteFullException("Synthetic disk full")
        })
        repeat(2) {
            expectHistoryError(AgentHistoryError.StorageFull) {
                history.completeRun(
                    start.runId, receiver.identity, permit.authEpoch, owner,
                    RunOutcome(MessageResultKind.Answer, "最终回复"),
                )
            }
            assertEquals(afterStart, snapshot())
        }
        reopen()
        assertEquals(afterStart, snapshot())

        cancelAt(SessionCheckpoint.COMPLETION_WRITTEN) {
            history.completeRun(
                start.runId, receiver.identity, permit.authEpoch, owner,
                RunOutcome(MessageResultKind.Answer, "最终回复"),
            )
        }

        // Lost receipt after a real commit: OutcomeUnknown, state committed exactly once.
        fixture.reinstateHistory(checkpoint = {
            if (it == SessionCheckpoint.COMPLETION_COMMITTED) throw IllegalStateException("Synthetic lost receipt")
        })
        expectHistoryError(AgentHistoryError.OutcomeUnknown) {
            history.completeRun(
                start.runId, receiver.identity, permit.authEpoch, owner,
                RunOutcome(MessageResultKind.Answer, "最终回复"),
            )
        }
        val committed = snapshot()
        assertEquals("COMPLETED", runRow(start.runId)[9])
        assertNull(runRow(start.runId)[10])
        assertEquals(2, rows("agent_message").size)
        fixture.reinstateHistory()
        val redelivered = history.completeRun(
            start.runId, receiver.identity, permit.authEpoch, owner,
            RunOutcome(MessageResultKind.Answer, "最终回复"),
        )
        assertTrue(redelivered.alreadyCompleted)
        assertEquals(committed, snapshot())
        expectHistoryError(AgentHistoryError.RunInvalidated) {
            history.completeRun(
                start.runId, receiver.identity, permit.authEpoch, owner,
                RunOutcome(MessageResultKind.Answer, "不同的迟到内容"),
            )
        }
        assertEquals(committed, snapshot())
    }

    @Test
    fun midRunRevocationAndTerminalStatesBlockLateWriters() = runBlocking {
        val (sessionId, runId) = completeSimpleRun()
        val afterComplete = snapshot()
        val permit = security.requirePermit(receiver)
        // Re-delivering the same final reply reconciles; a different payload can never rewrite.
        expectHistoryError(AgentHistoryError.RunInvalidated) {
            history.completeRun(
                runId, receiver.identity, permit.authEpoch, owner,
                RunOutcome(MessageResultKind.Answer, "不同的迟到内容"),
            )
        }
        assertEquals(afterComplete, snapshot())
        val terminal = history.failRun(runId, RunTerminal.FAILED, SafeRunErrorCode.INTERNAL)
        assertTrue(terminal.alreadyTerminal)
        assertEquals(RunState.COMPLETED, terminal.state)
        assertEquals(afterComplete, snapshot())

        // A run whose epoch was revoked mid-flight can neither complete nor be resurrected.
        // E2 semantics: the revoke transaction itself fails every RUNNING run atomically
        // (FAILED/AUTHORIZATION, slot freed), so the late writer faces a terminal row.
        val start = history.startRun(sessionId, "运行中的输入", receiver.identity, permit.authEpoch, owner, history.newRunIdentity())
        security.revoke()
        expectHistoryError(AgentHistoryError.RunInvalidated) {
            history.completeRun(
                start.runId, receiver.identity, permit.authEpoch, owner,
                RunOutcome(MessageResultKind.Answer, "被撤销后的回复"),
            )
        }
        val afterRevoke = snapshot()
        assertEquals("FAILED", runRow(start.runId)[9])
        assertEquals(null, runRow(start.runId)[10])
        assertEquals("AUTHORIZATION", runRow(start.runId)[13])
        security.grant(receiver)
        val afterRegrant = snapshot()
        expectHistoryError(AgentHistoryError.RunInvalidated) {
            history.completeRun(
                start.runId, receiver.identity, permit.authEpoch, owner,
                RunOutcome(MessageResultKind.Answer, "被撤销后的回复"),
            )
        }
        assertEquals(afterRegrant, snapshot())
        // The eager FAILED mark is the durable truth: a later cancel is an already-terminal no-op.
        val failed = history.failRun(start.runId, RunTerminal.CANCELLED, SafeRunErrorCode.AUTHORIZATION)
        assertTrue(failed.alreadyTerminal)
        assertEquals("FAILED", runRow(start.runId)[9])
        assertEquals("AUTHORIZATION", runRow(start.runId)[13])
    }

    @Test
    fun ownershipGateInterruptsStaleRunsAndStaleWritersStayBlind() = runBlocking {
        val session = history.createSession()
        val permit = security.requirePermit(receiver)
        val stale = history.startRun(session.id, "旧进程输入", receiver.identity, permit.authEpoch, owner, history.newRunIdentity())
        val nextOwner = "$owner-next"
        val adopted = history.beginProcessOwnership(nextOwner)
        assertTrue(adopted.adopted)
        assertEquals(1, adopted.interruptedRuns)
        val staleRow = runRow(stale.runId)
        assertEquals("INTERRUPTED", staleRow[9])
        assertNull(staleRow[10])
        assertEquals(sessionClock.millis(), staleRow[11])
        assertEquals(nextOwner, rows("record_data_state").single()[3])
        val afterAdoption = snapshot()

        expectHistoryError(AgentHistoryError.RunInvalidated) {
            history.completeRun(
                stale.runId, receiver.identity, permit.authEpoch, owner,
                RunOutcome(MessageResultKind.Answer, "旧进程的迟到回复"),
            )
        }
        assertEquals(afterAdoption, snapshot())
        assertTrue(history.failRun(stale.runId, RunTerminal.FAILED, SafeRunErrorCode.INTERNAL).alreadyTerminal)

        // The new owner starts a fresh run with a fresh creation identity (B0 §5.1 re-entry).
        val fresh = history.startRun(session.id, "新进程输入", receiver.identity, permit.authEpoch, nextOwner, history.newRunIdentity())
        assertNotEquals(stale.proposalCreationRequestId, fresh.proposalCreationRequestId)
        assertEquals(2, rows("agent_message").size)

        // A repeated gate call under the current owner is a no-op that spares live runs.
        val again = history.beginProcessOwnership(nextOwner)
        assertFalse(again.adopted)
        assertEquals(0, again.interruptedRuns)
        assertEquals("RUNNING", runRow(fresh.runId)[9])
    }

    @Test
    fun windowTrimmingKeepsRoomCompleteWhileTheContextStaysBounded() = runBlocking {
        val http = ScriptedDeviceHttp { index, _ -> chatDeviceResponse("回复$index") }
        val coord = coordinator(http, window = 7)
        val session = history.createSession()
        repeat(8) { index -> coord.run(session.id, "输入$index") }
        assertEquals(16, rows("agent_message").size)
        coord.run(session.id, "输入8")
        val ninth = deviceRequestMessages(http.requests.last())
        // Window 7 = system + 2 complete pairs + the current input; pairs are never split.
        assertEquals(6, ninth.size)
        assertEquals(
            listOf("system") + List(2) { listOf("user", "assistant") }.flatten() + listOf("user"),
            ninth.map { it.deviceRole() },
        )
        assertTrue(ninth.any { it.toString().contains("输入6") })
        assertTrue(ninth.any { it.toString().contains("输入7") })
        assertFalse(ninth.any { it.toString().contains("输入0") })
        assertFalse(ninth.any { it.toString().contains("输入5") })
        // The authoritative disk kept every exchange; the UI still reads the full history.
        assertEquals(18, rows("agent_message").size)
        val messages = history.sessionMessages(session.id)
        assertEquals((0L..17L).toList(), messages.map { it.sequence })
        assertEquals(9, messages.count { it.role.name == "USER" })
        assertEquals((0 until 9).map { index -> "输入$index" }, messages.filter { it.role.name == "USER" }.map { it.body })
    }

    @Test
    fun hiddenReasoningAndToolTrafficNeverReachTheAuthoritativeHistory() = runBlocking {
        val tool = DeviceEchoTool()
        val http = ScriptedDeviceHttp { index, _ ->
            when (index) {
                1 -> chatDeviceResponse(
                    reasoning = "SECRET-REASONING-$sentinel",
                    toolCall = "call-1" to """{"word":"X"}""",
                )
                else -> chatDeviceResponse("工具完成后的回复")
            }
        }
        val coord = coordinator(http, tools = echoToolRegistry(tool))
        val session = history.createSession()
        val result = coord.run(session.id, "用工具echo一下X")
        assertEquals("工具完成后的回复", result.answer)
        assertTrue(tool.calls.contains("X"))
        val messages = rows("agent_message")
        assertEquals(2, messages.size)
        assertEquals("用工具echo一下X", messages.single { it[4] == "USER" }[5])
        assertEquals("工具完成后的回复", messages.single { it[4] == "ASSISTANT" }[5])
        snapshot().forEach { (table, tableRows) ->
            tableRows.forEach { row ->
                row.forEach { value ->
                    if (value is String) {
                        assertFalse("$table leaked reasoning", value.contains("SECRET-REASONING-$sentinel"))
                        assertFalse("$table leaked tool call", value.contains("call-1"))
                    }
                }
            }
        }
    }

    @Test
    fun uiHistoryStaysReadableAcrossRunStatesAndReopen() = runBlocking {
        val session = history.createSession()
        val permit = security.requirePermit(receiver)
        val first = history.startRun(session.id, "完成输入", receiver.identity, permit.authEpoch, owner, history.newRunIdentity())
        history.completeRun(
            first.runId, receiver.identity, permit.authEpoch, owner,
            RunOutcome(MessageResultKind.Answer, "完成回复"),
        )
        val second = history.startRun(session.id, "失败输入", receiver.identity, permit.authEpoch, owner, history.newRunIdentity())
        history.failRun(second.runId, RunTerminal.FAILED, SafeRunErrorCode.PROVIDER_FAILURE)
        val third = history.startRun(session.id, "中断输入", receiver.identity, permit.authEpoch, owner, history.newRunIdentity())
        history.beginProcessOwnership("$owner-next")
        assertEquals(4, history.sessionMessages(session.id).size)

        reopen()
        val messages = history.sessionMessages(session.id)
        assertEquals(listOf("完成输入", "完成回复", "失败输入", "中断输入"), messages.map { it.body })
        assertEquals(
            listOf(RunState.COMPLETED, RunState.COMPLETED, RunState.FAILED, RunState.INTERRUPTED),
            messages.map { it.runState },
        )
        val completed = history.completedHistory(session.id, history.session(session.id).nextMessageSequence)
        assertEquals(listOf("完成输入", "完成回复"), completed.map { it.body })
        assertFalse(completed.any { it.body == "失败输入" || it.body == "中断输入" })
    }

    @Test
    fun referencesValidateTargetsAndPersistWithTheFinalReply() = runBlocking {
        val noteId = schemaId()
        database.records().insertNote(
            NoteEntity(noteId, "被引用的笔记", sessionClock.millis(), 1, sessionClock.millis(), sessionClock.millis(), null),
        )
        val session = history.createSession()
        val permit = security.requirePermit(receiver)
        val start = history.startRun(session.id, "引用输入", receiver.identity, permit.authEpoch, owner, history.newRunIdentity())
        expectHistoryError(AgentHistoryError.Validation) {
            history.completeRun(
                start.runId, receiver.identity, permit.authEpoch, owner,
                RunOutcome(
                    MessageResultKind.Answer, "带错误引用",
                    references = listOf(MessageReference(1, null, noteId)),
                ),
            )
        }
        expectHistoryError(AgentHistoryError.Validation) {
            history.completeRun(
                start.runId, receiver.identity, permit.authEpoch, owner,
                RunOutcome(
                    MessageResultKind.Answer, "带悬空引用",
                    references = listOf(MessageReference(0, null, schemaId())),
                ),
            )
        }
        assertEquals(1, rows("agent_message").size)
        val receipt = history.completeRun(
            start.runId, receiver.identity, permit.authEpoch, owner,
            RunOutcome(
                MessageResultKind.Answer, "带引用的回复",
                references = listOf(MessageReference(0, null, noteId)),
            ),
        )
        assertFalse(receipt.alreadyCompleted)
        assertEquals(listOf(receipt.assistantMessageId, 0L, null, noteId), rows("agent_message_reference").single())
        val last = history.sessionMessages(session.id).last()
        assertEquals("带引用的回复", last.body)
        assertEquals(listOf(MessageReference(0, null, noteId)), last.references)
    }

    @Test
    fun runTerminalFaultsRollBackAndIdempotentRetriesStayTerminal() = runBlocking {
        val session = history.createSession()
        val permit = security.requirePermit(receiver)
        val start = history.startRun(session.id, "将失败的输入", receiver.identity, permit.authEpoch, owner, history.newRunIdentity())
        val afterStart = snapshot()
        fixture.reinstateHistory(checkpoint = {
            if (it == SessionCheckpoint.TERMINAL_WRITTEN) throw SQLiteFullException("Synthetic disk full")
        })
        repeat(2) {
            expectHistoryError(AgentHistoryError.StorageFull) {
                history.failRun(start.runId, RunTerminal.FAILED, SafeRunErrorCode.PROVIDER_FAILURE)
            }
            assertEquals(afterStart, snapshot())
        }
        cancelAt(SessionCheckpoint.TERMINAL_WRITTEN) {
            history.failRun(start.runId, RunTerminal.FAILED, SafeRunErrorCode.PROVIDER_FAILURE)
        }
        fixture.reinstateHistory()
        val terminal = history.failRun(start.runId, RunTerminal.FAILED, SafeRunErrorCode.PROVIDER_FAILURE)
        assertFalse(terminal.alreadyTerminal)
        val repeated = history.failRun(start.runId, RunTerminal.CANCELLED, SafeRunErrorCode.CANCELLED)
        assertTrue(repeated.alreadyTerminal)
        assertEquals("FAILED", runRow(start.runId)[9])
        assertEquals("PROVIDER_FAILURE", runRow(start.runId)[13])
    }

    @Test
    fun realCancellationDuringRunFreesTheSlotAndPersistsTerminalState() = runBlocking {
        val session = history.createSession()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = ScriptedDeviceHttp { _, _ ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            chatDeviceResponse("不该出现的回复")
        }
        val coord = coordinator(http)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val job = scope.launch { coord.run(session.id, "被取消的输入") }
        assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
        // Cancel first, then unblock the responder: its latch wait is a blocking JDK call that
        // does not observe coroutine cancellation on its own.
        job.cancel()
        release.countDown()
        job.join()
        assertTrue(job.isCancelled)
        // The terminal marking ran under NonCancellable against real Room.
        val runRow = rows("agent_run").single()
        assertEquals("CANCELLED", runRow[9])
        assertNull(runRow[10])
        assertEquals("CANCELLED", runRow[13])
        assertEquals(1, rows("agent_message").size)
        assertEquals("被取消的输入", history.sessionMessages(session.id).single().body)
        // The slot is free: a new run starts on the same session without a gate.
        val permit = security.requirePermit(receiver)
        val second = history.startRun(session.id, "取消后的新输入", receiver.identity, permit.authEpoch, owner, history.newRunIdentity())
        assertEquals("RUNNING", rows("agent_run").single { it[0] == second.runId }[9])
        scope.cancel()
    }

    @Test
    fun startReceiptLossReconcilesTheCommittedOrphanByIdentity() = runBlocking {
        val session = history.createSession()
        fixture.reinstateHistory(checkpoint = {
            if (it == SessionCheckpoint.RUN_START_COMMITTED) throw IllegalStateException("Synthetic lost start receipt")
        })
        val http = ScriptedDeviceHttp { _, _ -> chatDeviceResponse("R") }
        val coord = coordinator(http)
        try {
            coord.run(session.id, "丢失启动回执的输入")
            fail("run should surface the unknown start outcome")
        } catch (error: com.paherdin.record2.ai.runs.AgentRunException) {
            assertEquals(com.paherdin.record2.ai.runs.AgentRunError.OutcomeUnknown, error.code)
        }
        // The committed run was terminally reconciled by its pre-generated identity.
        val runRow = rows("agent_run").single()
        assertEquals("CANCELLED", runRow[9])
        assertNull(runRow[10])
        assertEquals(1, rows("agent_message").size)
        assertEquals("丢失启动回执的输入", history.sessionMessages(session.id).single().body)
        // The session accepts a fresh run with a fresh creation identity.
        fixture.reinstateHistory()
        val second = coordinator(http).run(session.id, "后续输入")
        assertEquals("R", second.answer)
        assertEquals(3, rows("agent_message").size)
        val creationRequests = rows("agent_run").map { it[6] }
        assertEquals(2, creationRequests.toSet().size)
    }

    @Test
    fun coordinatorCompletionReceiptLossReconcilesToCommittedSuccess() = runBlocking {
        val session = history.createSession()
        val http = ScriptedDeviceHttp { _, _ -> chatDeviceResponse("已提交的回复") }
        fixture.reinstateHistory(checkpoint = {
            if (it == SessionCheckpoint.COMPLETION_COMMITTED) throw IllegalStateException("Synthetic lost completion receipt")
        })
        val coord = coordinator(http)
        val result = coord.run(session.id, "输入")
        // Room committed the final reply; the coordinator reconciles instead of failing.
        assertEquals("已提交的回复", result.answer)
        assertTrue(result.completion.alreadyCompleted)
        assertEquals("COMPLETED", runRow(result.runId)[9])
        assertEquals(2, rows("agent_message").size)
        val messages = history.sessionMessages(session.id)
        assertEquals(listOf("输入", "已提交的回复"), messages.map { it.body })
        assertEquals(2, messages.count { it.runState == RunState.COMPLETED })
        assertEquals(
            RunOutcome(MessageResultKind.Answer, "已提交的回复"),
            history.persistedResult(result.runId)?.outcome,
        )
    }

    @Test
    fun completionIdempotenceComparesTheFullOutcomeSemantics() = runBlocking {
        val noteId = schemaId()
        database.records().insertNote(
            NoteEntity(noteId, "幂等比较笔记", sessionClock.millis(), 1, sessionClock.millis(), sessionClock.millis(), null),
        )
        val session = history.createSession()
        val permit = security.requirePermit(receiver)
        val start = history.startRun(session.id, "输入", receiver.identity, permit.authEpoch, owner, history.newRunIdentity())
        val original = history.completeRun(
            start.runId, receiver.identity, permit.authEpoch, owner,
            RunOutcome(MessageResultKind.Answer, "回复", references = listOf(MessageReference(0, null, noteId))),
        )
        assertFalse(original.alreadyCompleted)
        val committed = snapshot()

        // Same body alone is not the same outcome: kind, proposal identity and references count.
        expectHistoryError(AgentHistoryError.RunInvalidated) {
            history.completeRun(
                start.runId, receiver.identity, permit.authEpoch, owner,
                RunOutcome(MessageResultKind.Navigate, "回复", references = listOf(MessageReference(0, null, noteId))),
            )
        }
        expectHistoryError(AgentHistoryError.RunInvalidated) {
            history.completeRun(
                start.runId, receiver.identity, permit.authEpoch, owner,
                RunOutcome(MessageResultKind.Answer, "回复", proposalId = schemaId(), proposalRevision = 1,
                    references = listOf(MessageReference(0, null, noteId))),
            )
        }
        expectHistoryError(AgentHistoryError.RunInvalidated) {
            history.completeRun(
                start.runId, receiver.identity, permit.authEpoch, owner,
                RunOutcome(MessageResultKind.Answer, "回复"),
            )
        }
        assertEquals(committed, snapshot())

        // A fully identical redelivery reconciles to the original receipt.
        val redelivered = history.completeRun(
            start.runId, receiver.identity, permit.authEpoch, owner,
            RunOutcome(MessageResultKind.Answer, "回复", references = listOf(MessageReference(0, null, noteId))),
        )
        assertTrue(redelivered.alreadyCompleted)
        assertEquals(original.assistantMessageId, redelivered.assistantMessageId)
        assertEquals(committed, snapshot())
    }

    private fun reopen() {
        fixture.reopenDatabase()
        fixture.reinstateHistory()
    }

    private suspend fun cancelAt(point: SessionCheckpoint, action: suspend () -> Unit) = coroutineScope {
        val before = snapshot()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.reinstateHistory(checkpoint = {
            if (it == point) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        })
        val job = launch {
            try {
                action()
            } catch (error: Throwable) {
                throw error
            }
        }
        try {
            // Suspend (don't block) so the launched child can run on this event loop.
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            assertEquals(before, snapshot())
            job.cancel()
        } finally {
            release.countDown()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(before, snapshot())
        fixture.reinstateHistory()
    }
}

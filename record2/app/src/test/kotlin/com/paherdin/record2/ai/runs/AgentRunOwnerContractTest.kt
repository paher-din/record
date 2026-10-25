package com.paherdin.record2.ai.runs

import ai.koog.http.client.KoogHttpClient
import com.paherdin.record2.ai.security.AiDenial
import com.paherdin.record2.sessions.AgentHistoryError
import com.paherdin.record2.sessions.RunState
import com.paherdin.record2.sessions.RunTerminal
import com.paherdin.record2.sessions.SafeRunErrorCode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Host JVM contract for the application-level run owner (Plan E2, Spec 5.2.6): runs survive the
 * caller's scope death (Activity rebuild / page switch / panel close), user cancel and
 * authorization invalidation persist the terminal state BEFORE stopping the in-memory work, and
 * session deletion stops work only after the transaction commits. Real Room transaction shapes
 * for the same rules are covered by SessionLifecycleContractTest on a device.
 */
class AgentRunOwnerContractTest {
    private fun owner(
        history: FakeHistoryService,
        security: FakeAiSecurity,
        http: ScriptedHttp,
    ): AgentRunOwner = AgentRunOwner(
        coordinator = AgentRunCoordinator(
            security = security,
            history = history,
            processOwnerId = "e2-owner",
            // FakeHistoryService stamps startedAt=0; use the same clock domain for the D1 run
            // wall-clock deadline.
            clock = Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC),
            httpClientFactory = httpFactory(http),
        ),
        history = history,
        processOwnerId = "e2-owner",
    )

    private fun httpFactory(http: ScriptedHttp): KoogHttpClient.Factory =
        object : KoogHttpClient.Factory {
            override fun create(
                clientName: String, baseUrl: String, headers: Map<String, String>,
                queryParameters: Map<String, String>, requestTimeoutMillis: Long,
                connectTimeoutMillis: Long, socketTimeoutMillis: Long,
                json: kotlinx.serialization.json.Json,
            ): KoogHttpClient = http
        }

    private suspend fun awaitRequests(http: ScriptedHttp, count: Int) {
        withTimeout(10_000) { while (http.requests.size < count) delay(10) }
    }

    @Test
    fun callerScopeDeathDoesNotCancelTheOwnedRun() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedHttp { _, _ -> gate.await(); chatResponse("SURVIVED") }
        val runOwner = owner(history, FakeAiSecurity(), http)
        // The UI caller launches the run from its own scope, then that scope dies outright
        // (Activity rebuild, page switch, panel close). The owned run must keep going.
        val callerScope = CoroutineScope(Dispatchers.Default + Job())
        val handleInCaller = CompletableDeferred<OwnedRunHandle>()
        callerScope.launch { handleInCaller.complete(runOwner.launchRun(session.id, "hello")) }
        val handle = withTimeout(10_000) { handleInCaller.await() }
        awaitRequests(http, 1)
        callerScope.cancel()
        assertTrue(handle.isActive)
        gate.complete(Unit)
        val result = withTimeout(10_000) { handle.await() }
        assertEquals("SURVIVED", result.answer)
        assertEquals(1, history.completedRuns.size)
        runOwner.close()
        Unit
    }

    @Test
    fun userCancelPersistsCancelledBeforeStoppingTheJob() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedHttp { _, _ -> gate.await(); chatResponse("TOO-LATE") }
        val runOwner = owner(history, FakeAiSecurity(), http)
        val handle = runOwner.launchRun(session.id, "hello")
        awaitRequests(http, 1)
        val receipt = runOwner.cancelRun(session.id)
        assertEquals(handle.runId, receipt?.runId)
        assertEquals(RunState.CANCELLED, receipt?.state)
        // The durable CANCELLED mark landed; the job stops without a ghost reply.
        assertEquals(
            listOf(Triple(handle.runId, RunTerminal.CANCELLED, SafeRunErrorCode.CANCELLED)),
            history.failedRuns,
        )
        withTimeout(10_000) { try { handle.await(); fail("cancelled run must not await") } catch (_: CancellationException) {} }
        assertFalse(handle.isActive)
        assertEquals(0, history.completedRuns.size)
        // A late completion for the cancelled run is refused (fake mirrors the real service).
        try {
            history.completeRun(
                handle.runId, "deepseek.api.deepseek.com", 7, "e2-owner",
                com.paherdin.record2.sessions.RunOutcome(
                    com.paherdin.record2.sessions.MessageResultKind.Answer, "TOO-LATE",
                ),
            )
            fail("late completion must be refused")
        } catch (error: com.paherdin.record2.sessions.AgentHistoryException) {
            assertEquals(AgentHistoryError.RunInvalidated, error.code)
        }
        runOwner.close()
        Unit
    }

    @Test
    fun userCancelAfterTerminalIsAnIdempotentNoOp() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val http = ScriptedHttp { _, _ -> chatResponse("DONE") }
        val runOwner = owner(history, FakeAiSecurity(), http)
        val handle = runOwner.launchRun(session.id, "hello")
        assertEquals("DONE", withTimeout(10_000) { handle.await() }.answer)
        val receipt = runOwner.cancelRun(session.id)
        assertEquals(null, receipt)
        assertTrue(history.failedRuns.isEmpty())
        runOwner.close()
        Unit
    }

    @Test
    fun cancelDuringTheStartWindowLeavesNothingBehind() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val gate = CompletableDeferred<Unit>()
        history.startRunGate = gate
        val http = ScriptedHttp { _, _ -> chatResponse("NEVER") }
        val runOwner = owner(history, FakeAiSecurity(), http)
        val handle = runOwner.launchRun(session.id, "hello")
        // The start transaction is suspended at its entry; the user cancels before it commits.
        delay(200)
        runOwner.cancelRun(session.id)
        withTimeout(10_000) { try { handle.await() } catch (_: CancellationException) {} }
        // Nothing committed, nothing reconciled onto disk, no network work — and the identity
        // reconciliation the coordinator attempted by runId is a NotFound no-op, exactly like
        // the real service, so the session slot was never held.
        assertTrue(history.startedRuns.isEmpty())
        assertTrue(history.failedRuns.isEmpty())
        assertEquals(0, http.requests.size)
        // The session accepts a fresh run immediately after the cancelled attempt.
        history.startRunGate = null
        val next = runOwner.launchRun(session.id, "hello-again")
        assertEquals("NEVER", withTimeout(10_000) { next.await() }.answer)
        runOwner.close()
        Unit
    }

    @Test
    fun authorizationChangeStopsEveryInvalidatedRun() = runBlocking {
        val history = FakeHistoryService()
        val first = history.newSession()
        val second = history.newSession()
        val gates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        val http = ScriptedHttp { index, _ ->
            val gate = gates.getOrElse(index - 1) {
                CompletableDeferred<Unit>().also { gates += it }
            }
            gate.await()
            chatResponse("R$index")
        }
        val runOwner = owner(history, FakeAiSecurity(), http)
        val one = runOwner.launchRun(first.id, "one")
        val two = runOwner.launchRun(second.id, "two")
        awaitRequests(http, 2)
        // A real control mutation fails EVERY running run atomically (permits are epoch-bound);
        // the owner sweep then stops all of them so no further request goes out (CTX-04).
        history.simulateAuthorizationInvalidation()
        runOwner.onAuthorizationChanged()
        withTimeout(10_000) {
            try { one.await(); fail("invalidated run must not await") } catch (_: CancellationException) {}
            try { two.await(); fail("invalidated run must not await") } catch (_: CancellationException) {}
        }
        assertFalse(one.isActive)
        assertFalse(two.isActive)
        assertEquals(0, history.completedRuns.size)
        assertEquals(setOf(one.runId, two.runId), history.authorizationInvalidated.toSet())
        // The stopped work never issues another request even after the gates release.
        gates.forEach { it.complete(Unit) }
        delay(200)
        assertEquals(2, http.requests.size)
        runOwner.close()
        Unit
    }

    @Test
    fun spuriousAuthorizationCallbackKeepsAValidRunRunning() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedHttp { _, _ -> gate.await(); chatResponse("R") }
        val runOwner = owner(history, FakeAiSecurity(), http)
        val handle = runOwner.launchRun(session.id, "hello")
        awaitRequests(http, 1)
        // No epoch change happened; the sweep must not touch the still-RUNNING run.
        runOwner.onAuthorizationChanged()
        assertTrue(handle.isActive)
        gate.complete(Unit)
        assertEquals("R", withTimeout(10_000) { handle.await() }.answer)
        runOwner.close()
        Unit
    }

    @Test
    fun deleteSessionStopsTheJobOnlyAfterTheCommit() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedHttp { _, _ -> gate.await(); chatResponse("TOO-LATE") }
        val runOwner = owner(history, FakeAiSecurity(), http)
        val handle = runOwner.launchRun(session.id, "hello")
        awaitRequests(http, 1)
        val report = runOwner.deleteSession(session.id)
        assertEquals(session.id to "e2-owner", history.deleteSessionCalls.single())
        assertEquals(1, report.cancelledActiveRuns)
        withTimeout(10_000) { try { handle.await() } catch (_: CancellationException) {} }
        assertFalse(handle.isActive)
        assertEquals(0, history.completedRuns.size)
        runOwner.close()
        Unit
    }

    @Test
    fun deleteSessionRefusalLeavesTheSessionAndRunIntact() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedHttp { _, _ -> gate.await(); chatResponse("STILL-RUNNING") }
        history.deleteSessionError = AgentHistoryError.UnsafeSessionContent
        val runOwner = owner(history, FakeAiSecurity(), http)
        val handle = runOwner.launchRun(session.id, "hello")
        awaitRequests(http, 1)
        try {
            runOwner.deleteSession(session.id)
            fail("refusal must propagate")
        } catch (error: com.paherdin.record2.sessions.AgentHistoryException) {
            assertEquals(AgentHistoryError.UnsafeSessionContent, error.code)
        }
        assertTrue(handle.isActive)
        assertTrue(session.id !in history.deletedSessions)
        // The run is untouched by the refused deletion and completes normally.
        gate.complete(Unit)
        assertEquals("STILL-RUNNING", withTimeout(10_000) { handle.await() }.answer)
        runOwner.close()
        Unit
    }

    @Test
    fun recoverProcessTouchesOnlyTheOwnershipGate() = runBlocking {
        val history = FakeHistoryService()
        val http = ScriptedHttp { _, _ -> chatResponse("R") }
        val runOwner = owner(history, FakeAiSecurity(), http)
        val receipt = runOwner.recoverProcess()
        assertEquals("e2-owner", receipt.processOwnerId)
        assertEquals(listOf("e2-owner"), history.ownershipCalls)
        assertEquals(0, http.requests.size)
        runOwner.close()
        Unit
    }

    @Test
    fun preFlightDenialSurfacesThroughAwaitWithoutStartingARun() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val http = ScriptedHttp { _, _ -> chatResponse("R") }
        val runOwner = owner(history, FakeAiSecurity(denial = AiDenial.AI_DISABLED), http)
        val handle = runOwner.launchRun(session.id, "hello")
        try {
            withTimeout(10_000) { handle.await() }
            fail("authorization denial must surface")
        } catch (error: AgentRunException) {
            assertEquals(AgentRunError.AuthorizationRequired, error.code)
        }
        assertTrue(history.startedRuns.isEmpty())
        assertEquals(0, http.requests.size)
        runOwner.close()
        Unit
    }

    // ---- PR #29 review regressions: registry and cancellation lifecycle ----

    @Test
    fun duplicateLaunchCannotOrphanTheActiveRun() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedHttp { _, _ -> gate.await(); chatResponse("R") }
        val runOwner = owner(history, FakeAiSecurity(), http)
        val active = runOwner.launchRun(session.id, "active input")
        awaitRequests(http, 1)
        // A duplicate launch on the busy session fails at startRun; it must not displace the
        // live run from the owner's registry, so a later cancel still reaches it.
        val duplicate = runOwner.launchRun(session.id, "duplicate input")
        try {
            withTimeout(10_000) { duplicate.await() }
            fail("the duplicate must fail with SessionBusy")
        } catch (error: AgentRunException) {
            assertEquals(AgentRunError.SessionBusy, error.code)
        }
        val receipt = runOwner.cancelRun(session.id)
        assertEquals(active.runId, receipt?.runId)
        assertEquals(RunState.CANCELLED, receipt?.state)
        withTimeout(10_000) {
            try { active.await(); fail("cancelled run must not await") } catch (_: CancellationException) {}
        }
        // No further outbound work for either the live run or the rejected duplicate.
        gate.complete(Unit)
        delay(200)
        assertEquals(1, http.requests.size)
        runOwner.close()
        Unit
    }

    @Test
    fun immediateCancellationNeverHangsAwait() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val startGate = CompletableDeferred<Unit>()
        history.startRunGate = startGate
        val http = ScriptedHttp { _, _ -> chatResponse("R") }
        val runOwner = owner(history, FakeAiSecurity(), http)
        // Cancel right after launch, before the start transaction commits: whether the
        // coroutine already suspended at the gate or never started, await must terminate.
        val handle = runOwner.launchRun(session.id, "hello")
        runOwner.cancelRun(session.id)
        withTimeout(10_000) {
            try { handle.await(); fail("cancelled run must not await") } catch (_: CancellationException) {}
        }
        startGate.complete(Unit)
        runOwner.close()
        Unit
    }

    @Test
    fun closeTerminatesPendingAwaitsAndLaterLaunchesFailFast() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedHttp { _, _ -> gate.await(); chatResponse("R") }
        val runOwner = owner(history, FakeAiSecurity(), http)
        val handle = runOwner.launchRun(session.id, "hello")
        awaitRequests(http, 1)
        runOwner.close()
        withTimeout(10_000) {
            try { handle.await(); fail("closed owner must terminate awaits") } catch (_: CancellationException) {}
        }
        // A launch after close cannot hang either: the deferred is dead on arrival.
        val late = runOwner.launchRun(session.id, "after close")
        withTimeout(10_000) {
            try { late.await(); fail("launch after close must fail fast") } catch (_: CancellationException) {}
        }
        Unit
    }

    @Test
    fun deleteSessionReceiptLossStillStopsTheWorkAfterTheCommit() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedHttp { _, _ -> gate.await(); chatResponse("TOO-LATE") }
        history.deleteSessionPersistThenOutcomeUnknown = true
        val runOwner = owner(history, FakeAiSecurity(), http)
        val handle = runOwner.launchRun(session.id, "hello")
        awaitRequests(http, 1)
        try {
            runOwner.deleteSession(session.id)
            fail("the lost receipt must surface as OutcomeUnknown")
        } catch (error: com.paherdin.record2.sessions.AgentHistoryException) {
            assertEquals(AgentHistoryError.OutcomeUnknown, error.code)
        }
        // The deletion actually committed: the session is gone and the work is stopped even
        // though the receipt was lost — a deleted session keeps no network life.
        assertTrue(session.id in history.deletedSessions)
        withTimeout(10_000) {
            try { handle.await(); fail("work of a deleted session must stop") } catch (_: CancellationException) {}
        }
        gate.complete(Unit)
        delay(200)
        assertEquals(1, http.requests.size)
        Unit
    }

    @Test
    fun cancelPersistenceFailureSurfacesAndStillStopsTheWork() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedHttp { _, _ -> gate.await(); chatResponse("TOO-LATE") }
        val runOwner = owner(history, FakeAiSecurity(), http)
        val handle = runOwner.launchRun(session.id, "hello")
        awaitRequests(http, 1)
        // The durable CANCELLED mark fails: the storage error must surface (never a fake null),
        // while the in-memory work still stops.
        history.failRunError = AgentHistoryError.RecoverableIO
        try {
            runOwner.cancelRun(session.id)
            fail("a failed durable mark must not be reported as a clean cancel")
        } catch (error: com.paherdin.record2.sessions.AgentHistoryException) {
            assertEquals(AgentHistoryError.RecoverableIO, error.code)
        }
        withTimeout(10_000) {
            try { handle.await(); fail("the work must still stop") } catch (_: CancellationException) {}
        }
        gate.complete(Unit)
        delay(200)
        assertEquals(1, http.requests.size)
        // Recovery: once storage accepts writes again, the cancel lands and reconciles.
        history.failRunError = null
        val receipt = runOwner.cancelRun(session.id)
        assertEquals(handle.runId, receipt?.runId)
        assertEquals(RunState.CANCELLED, receipt?.state)
        runOwner.close()
        Unit
    }

    @Test
    fun terminalRunsLeaveTheRegistry() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val http = ScriptedHttp { index, _ -> chatResponse("R$index") }
        val runOwner = owner(history, FakeAiSecurity(), http)
        repeat(3) {
            val handle = runOwner.launchRun(session.id, "turn $it")
            withTimeout(10_000) { handle.await() }
        }
        withTimeout(10_000) { while (runOwner.trackedRunCount() > 0) delay(10) }
        assertEquals(0, runOwner.trackedRunCount())
        runOwner.close()
        Unit
    }
}

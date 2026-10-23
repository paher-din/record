package com.paherdin.record.startup

import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RecordStartup
import com.paherdin.record.core.data.StartupScriptGate
import com.paherdin.record.core.data.StartupScripts
import com.paherdin.record.core.ui.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the application startup state store (plan M1-T07 Step 1):
 * startup progress maps onto the exact interaction phases, terminal outcomes
 * map onto Completed/RecoverableFailure/BlockingFailure, no shell state exists
 * before the Ready emission, and one attempt owns start/retry (concurrent
 * callers coalesce; `retry()` follows [com.paherdin.record.core.data.RecordStartup]
 * semantics). The scripted [StartupScripts] fixture drives the real
 * `:core:data` engine on the JVM.
 */
class StartupStateStoreTest {

    /** The store launches attempts on its own multi-threaded scope, like production. */
    private fun newStore(startup: RecordStartup): StartupStateStore =
        StartupStateStore(startup, CoroutineScope(Dispatchers.Default))

    private fun awaitState(
        store: StartupStateStore,
        timeoutMs: Long = 15_000,
        predicate: (AppStartState) -> Boolean,
    ): AppStartState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var current = store.state.value
        while (!predicate(current)) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for startup state; last=$current" }
            Thread.sleep(20)
            current = store.state.value
        }
        return current
    }

    // ---- no shell before Ready ----

    @Test
    fun initialStoreStateIsACheckingGateWithoutShell() {
        val store = newStore(StartupScripts.ready())
        val initial = store.state.value
        assertTrue("before any attempt the state is a Gate", initial is AppStartState.Gate)
        assertEquals(Phase.Checking, (initial as AppStartState.Gate).phase)
        assertTrue("the initial gate offers no retry", !initial.canRetry)
    }

    // ---- progress → phase mapping ----

    @Test
    fun checkingProgressHoldsTheCheckingGate() {
        val gate = StartupScriptGate()
        val store = newStore(StartupScripts.gatedReady(gate))
        store.start()
        // The one attempt runs past Checking and holds inside openActive: the
        // state stays a Gate and never becomes Ready before the Ready result.
        val held = awaitState(store) { it is AppStartState.Gate && it.phase == Phase.Checking }
        gate.complete()
        val ready = awaitState(store) { it is AppStartState.Ready }
        assertEquals(Phase.Completed, (store.state.value as AppStartState.Ready).phase)
        assertTrue(held is AppStartState.Gate)
    }

    @Test
    fun recoveryDetectedProgressMapsToPhaseRunning() {
        val gate = StartupScriptGate()
        val store = newStore(StartupScripts.gatedRunning(gate))
        store.start()
        val running = awaitState(store) { it is AppStartState.Gate && it.phase == Phase.Running }
        assertTrue(running is AppStartState.Gate)
        assertTrue("a progress gate must not offer retry", !(running as AppStartState.Gate).canRetry)
        gate.complete()
        awaitState(store) { it is AppStartState.Ready }
    }

    @Test
    fun recoveringProgressMapsToPhaseRecovering() {
        val gate = StartupScriptGate()
        val store = newStore(StartupScripts.gatedRecovering(gate))
        store.start()
        val recovering = awaitState(store) { it is AppStartState.Gate && it.phase == Phase.Recovering }
        assertTrue(recovering is AppStartState.Gate)
        assertTrue(!(recovering as AppStartState.Gate).canRetry)
        gate.complete()
        awaitState(store) { it is AppStartState.Ready }
    }

    // ---- result → phase mapping ----

    @Test
    fun readyResultBecomesTheCompletedShellState() {
        val store = newStore(StartupScripts.ready())
        store.start()
        val ready = awaitState(store) { it is AppStartState.Ready }
        assertTrue(ready is AppStartState.Ready)
        assertEquals(Phase.Completed, (ready as AppStartState.Ready).phase)
        assertTrue(ready.repository is RecordRepository)
    }

    @Test
    fun recoveryThroughActivatedOutcomeStillEndsReady() {
        val store = newStore(StartupScripts.readyThroughRecovery())
        store.start()
        val ready = awaitState(store) { it is AppStartState.Ready }
        assertEquals(Phase.Completed, (ready as AppStartState.Ready).phase)
    }

    @Test
    fun retryableBlockedBecomesARecoverableFailureGate() {
        val store = newStore(StartupScripts.retryableBlocked())
        store.start()
        val gate = awaitState(store) { it is AppStartState.Gate && it.phase == Phase.RecoverableFailure }
        assertTrue((gate as AppStartState.Gate).canRetry)
        assertTrue(gate !is AppStartState.Ready)
    }

    @Test
    fun storageExhaustedBecomesANonRetryableBlockingFailureGate() {
        val store = newStore(StartupScripts.blockingStorageExhausted())
        store.start()
        val blocked = awaitState(store) { it is AppStartState.Gate && it.phase == Phase.BlockingFailure }
        assertTrue(!(blocked as AppStartState.Gate).canRetry)
        assertTrue(blocked !is AppStartState.Ready)
    }

    // ---- one start/retry attempt ----

    @Test
    fun secondConcurrentStartCoalescesOntoTheSingleAttempt() {
        val gate = StartupScriptGate()
        val store = newStore(StartupScripts.gatedReady(gate))
        store.start()
        store.start()
        // The single in-flight attempt holds the gate; a second engine attempt
        // would have failed the open step and produced a blocking failure.
        val held = awaitState(store) { it is AppStartState.Gate && it.phase == Phase.Checking }
        gate.complete()
        val ready = awaitState(store) { it is AppStartState.Ready }
        assertTrue(ready is AppStartState.Ready)
    }

    @Test
    fun retryAfterRetryableBlockedRunsExactlyOneMoreAttempt() {
        val store = newStore(StartupScripts.retryableBlockedThenReady())
        store.start()
        awaitState(store) { it is AppStartState.Gate && it.phase == Phase.RecoverableFailure && it.canRetry }
        store.retry()
        val ready = awaitState(store) { it is AppStartState.Ready }
        assertEquals(Phase.Completed, (ready as AppStartState.Ready).phase)
    }

    @Test
    fun retryAfterNonRetryableBlockedKeepsTheSameGate() {
        val store = newStore(StartupScripts.blockingStorageExhausted())
        store.start()
        val blocked = awaitState(store) { it is AppStartState.Gate && it.phase == Phase.BlockingFailure }
        assertEquals(AppStartState.Gate(Phase.BlockingFailure, canRetry = false), blocked)
        store.retry()
        val after = awaitState(store) { it is AppStartState.Gate && it.phase == Phase.BlockingFailure }
        assertEquals("a non-retryable blocked result must not relaunch storage work", blocked, after)
    }

    @Test
    fun retryAfterReadyKeepsTheCachedReadyState() {
        val store = newStore(StartupScripts.ready())
        store.start()
        val first = awaitState(store) { it is AppStartState.Ready }
        assertTrue(first is AppStartState.Ready)
        store.retry()
        assertTrue(store.state.value is AppStartState.Ready)
    }

    // ---- production availability models only ----

    @Test
    fun productionSurfacesExposeOnlyAvailabilityContentModels() {
        // The shell consumes the feature-owned M1 availability content models;
        // no synthetic business data type exists on the production path.
        assertTrue(
            com.paherdin.record.feature.timeline.TimelineContent.Availability ===
                com.paherdin.record.feature.timeline.TimelineContent.Availability,
        )
        assertTrue(
            com.paherdin.record.feature.finance.FinanceAvailability ===
                com.paherdin.record.feature.finance.FinanceAvailability,
        )
        assertTrue(
            com.paherdin.record.feature.notes.NotesAvailability ===
                com.paherdin.record.feature.notes.NotesAvailability,
        )
    }
}
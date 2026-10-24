package com.paherdin.record2.ai.runs

import com.paherdin.record2.sessions.ActiveRunView
import com.paherdin.record2.sessions.AgentHistoryService
import com.paherdin.record2.sessions.ProcessOwnershipReceipt
import com.paherdin.record2.sessions.RunIdentity
import com.paherdin.record2.sessions.RunState
import com.paherdin.record2.sessions.RunTerminal
import com.paherdin.record2.sessions.RunTerminalReceipt
import com.paherdin.record2.sessions.SafeRunErrorCode
import com.paherdin.record2.sessions.SessionDeletionReport
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The handle a caller keeps for one application-owned run. It survives the caller's scope:
 * cancelling or destroying the scope that called [AgentRunOwner.launchRun] (Activity rebuild,
 * page switch, panel close) never cancels the run — only the owner's explicit entries do.
 *
 * [await] returns the [RunResult] on success, rethrows [AgentRunException] on failure, and
 * rethrows [CancellationException] when the run was stopped via [AgentRunOwner.cancelRun] or
 * [AgentRunOwner.onAuthorizationChanged]; the durable run state is always readable from the
 * history service, which is the authority.
 */
internal class OwnedRunHandle internal constructor(
    val sessionId: String,
    /** Pre-generated stable identity: addressable even if the start receipt is lost. */
    val runId: String,
    internal val job: Job,
    private val completion: CompletableDeferred<RunResult>,
) {
    val isActive: Boolean get() = job.isActive

    suspend fun await(): RunResult = completion.await()
}

/**
 * Application-level owner of agent runs (Spec 5.2.6, Plan E2): runs execute in this owner's
 * scope, not in any UI/view scope, so page changes and Activity recreation never cancel them,
 * and no background service exists. This class owns the invalidation call sites E1 left open —
 * every entry persists the terminal state FIRST (a short Room transaction) and only then stops
 * the in-memory coroutine, so a lost process or a lost race can never resurrect a run.
 *
 * Process protocol: call [recoverProcess] once per process before any run/session entry. It only
 * touches Room (leftover RUNNING rows become INTERRUPTED); it never starts model, tool or
 * network work.
 */
internal class AgentRunOwner(
    private val coordinator: AgentRunCoordinator,
    private val history: AgentHistoryService,
    private val processOwnerId: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val tracked = ConcurrentHashMap<String, TrackedRun>()

    private class TrackedRun(val identity: RunIdentity, val job: Job)

    /** Process-start gate (B0 §6): adopt this process, mark leftovers INTERRUPTED, zero calls. */
    suspend fun recoverProcess(): ProcessOwnershipReceipt = coordinator.beginProcessOwnership()

    suspend fun createSession(title: String? = null) = coordinator.createSession(title)

    /**
     * Launch one run owned at application level. Pre-flight failures (blank input, missing
     * authorization, busy session) surface through [OwnedRunHandle.await], not from this call.
     */
    fun launchRun(sessionId: String, userText: String): OwnedRunHandle {
        val identity = history.newRunIdentity()
        val completion = CompletableDeferred<RunResult>()
        val job = scope.launch {
            try {
                completion.complete(coordinator.run(sessionId, userText, identity))
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
            }
        }
        tracked[sessionId] = TrackedRun(identity, job)
        return OwnedRunHandle(sessionId, identity.runId, job, completion)
    }

    /**
     * User cancel for the session's current run: the durable CANCELLED mark lands first (outside
     * cancellation), then the in-memory coroutine is stopped. A late completion from the stopped
     * network work is refused by the run's terminal state — never a ghost reply. Idempotent:
     * after a terminal run it changes nothing and returns null when no active run was marked.
     */
    suspend fun cancelRun(sessionId: String): RunTerminalReceipt? {
        val receipt = withContext(NonCancellable) {
            try {
                activeRunOf(sessionId)?.let { active ->
                    history.failRun(active.runId, RunTerminal.CANCELLED, SafeRunErrorCode.CANCELLED)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The durable mark failed; stopping the job below still routes through the
                // coordinator's own NonCancellable terminal marking, and the process-ownership
                // gate remains the backstop. Surface nothing here: the user intent was "stop".
                null
            }
        }
        tracked.remove(sessionId)?.job?.cancel(RunStoppedException("run cancelled by user"))
        return receipt
    }

    /**
     * Call after any AI control mutation (mode, receiver selection, grant, revoke, credential
     * replace/clear). The mutation's Room transaction has already failed every RUNNING run
     * atomically; this stops the still-executing in-memory work so no further request goes out
     * (CTX-04). Runs that are still RUNNING under the current epoch are left alone, so a
     * spurious call cannot kill a valid run.
     */
    suspend fun onAuthorizationChanged() {
        for ((sessionId, trackedRun) in tracked.toList()) {
            val persisted = try {
                history.persistedResult(trackedRun.identity.runId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null // unreadable: never kill a run we cannot certify invalidated
            }
            when (persisted?.state) {
                RunState.RUNNING -> Unit // epoch change did not touch it (no-op mutation)
                RunState.COMPLETED -> Unit // finished; let the receipt land
                null -> Unit // not started yet; startRun revalidates the new authorization
                RunState.FAILED, RunState.CANCELLED, RunState.INTERRUPTED -> {
                    if (tracked.remove(sessionId, trackedRun)) {
                        trackedRun.job.cancel(
                            RunStoppedException("run invalidated by authorization change"),
                        )
                    }
                }
            }
        }
    }

    /**
     * Explicit user session deletion (Spec 5.2, B0 §8): one Room transaction cancels the active
     * run, discards unconfirmed proposals, deletes the user-visible history and keeps every
     * commit_operation row as the independent verification basis. The in-memory work stops only
     * after a successful commit; a refusal (UnsafeSessionContent and friends) propagates with
     * the session fully intact.
     */
    suspend fun deleteSession(sessionId: String): SessionDeletionReport {
        val report = history.deleteSession(sessionId, processOwnerId)
        tracked.remove(sessionId)?.job?.cancel(RunStoppedException("session deleted by user"))
        return report
    }

    /** Process teardown only; runs are otherwise never cancelled by lifecycle events. */
    fun close() {
        scope.cancel()
    }

    private suspend fun activeRunOf(sessionId: String): ActiveRunView? =
        history.activeRun(sessionId)?.takeIf { it.state == RunState.RUNNING }

    private class RunStoppedException(message: String) :
        CancellationException(message)
}

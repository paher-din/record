package com.paherdin.record2.ai.runs

import com.paherdin.record2.sessions.ActiveRunView
import com.paherdin.record2.sessions.AgentHistoryError
import com.paherdin.record2.sessions.AgentHistoryException
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * The handle a caller keeps for one application-owned run. It survives the caller's scope:
 * cancelling or destroying the scope that called [AgentRunOwner.launchRun] (Activity rebuild,
 * page switch, panel close) never cancels the run — only the owner's explicit entries do.
 *
 * [await] returns the [RunResult] on success, rethrows [AgentRunException] on failure, and
 * rethrows [CancellationException] when the run was stopped via [AgentRunOwner.cancelRun],
 * [AgentRunOwner.onAuthorizationChanged], [AgentRunOwner.deleteSession] or [AgentRunOwner.close];
 * the durable run state is always readable from the history service, which is the authority.
 * The completion is a real [Deferred]: a run cancelled before its coroutine even starts still
 * terminates [await] instead of hanging.
 */
internal class OwnedRunHandle internal constructor(
    val sessionId: String,
    /** Pre-generated stable identity: addressable even if the start receipt is lost. */
    val runId: String,
    internal val job: Job,
    private val completion: Deferred<RunResult>,
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
 * Registry invariants (PR #29 review): entries are keyed by runId, so a second launch on a busy
 * session never overwrites the live run's entry (the duplicate fails at startRun with
 * SessionBusy and removes itself); finished runs always deregister themselves; and every launch
 * owns a cancellable Deferred whose await cannot outlive the job.
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

    private class TrackedRun(val sessionId: String, val identity: RunIdentity) {
        // Set immediately after launch; sweeps treat a null job like a not-yet-started run.
        @Volatile var job: Job? = null
    }

    /** Process-start gate (B0 §6): adopt this process, mark leftovers INTERRUPTED, zero calls. */
    suspend fun recoverProcess(): ProcessOwnershipReceipt = coordinator.beginProcessOwnership()

    suspend fun createSession(title: String? = null) = coordinator.createSession(title)

    /**
     * Launch one run owned at application level. Pre-flight failures (blank input, missing
     * authorization, busy session) surface through [OwnedRunHandle.await], not from this call.
     * A duplicate launch on a session whose slot is held fails fast at startRun; it can never
     * displace the live run from this owner's registry.
     */
    fun launchRun(sessionId: String, userText: String): OwnedRunHandle {
        val identity = history.newRunIdentity()
        val entry = TrackedRun(sessionId, identity)
        // Registered before launch so a registration can never be orphaned by a fast-completing
        // coroutine; the completion hook deregisters on EVERY terminal state, including a
        // cancellation that lands before the coroutine ever starts.
        tracked[identity.runId] = entry
        val completion = scope.async { coordinator.run(sessionId, userText, identity) }
        completion.invokeOnCompletion { tracked.remove(identity.runId, entry) }
        entry.job = completion
        return OwnedRunHandle(sessionId, identity.runId, completion, completion)
    }

    /**
     * User cancel for the session's current run: the durable CANCELLED mark lands first, then
     * the in-memory coroutines stop — the whole sequence runs outside caller cancellation so a
     * cancelled caller cannot skip the in-memory stop. A late completion from the stopped
     * network work is refused by the run's terminal state — never a ghost reply.
     *
     * Returns the receipt of the durable mark, or null when no active run was marked. A failed
     * durable mark is NOT silently reported as null: the original storage error is rethrown
     * after the in-memory stop (the coordinator's own NonCancellable marking and the
     * process-ownership gate remain the backstops), so callers can surface an unconfirmed
     * cancel instead of a fake clean one. Idempotent for already-terminal runs.
     */
    suspend fun cancelRun(sessionId: String): RunTerminalReceipt? = withContext(NonCancellable) {
        val receipt = try {
            activeRunOf(sessionId)?.let { active ->
                history.failRun(active.runId, RunTerminal.CANCELLED, SafeRunErrorCode.CANCELLED)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            stopSessionWork(sessionId)
            throw error
        }
        stopSessionWork(sessionId)
        receipt
    }

    /**
     * Call after any AI control mutation (mode, receiver selection, grant, revoke, credential
     * replace/clear). The mutation's Room transaction has already failed every RUNNING run
     * atomically; this stops the still-executing in-memory work so no further request goes out
     * (CTX-04). Runs that are still RUNNING under the current epoch are left alone, so a
     * spurious call cannot kill a valid run.
     */
    suspend fun onAuthorizationChanged() {
        for (entry in tracked.values.toList()) {
            val job = entry.job ?: continue // not started yet; startRun revalidates
            val persisted = try {
                history.persistedResult(entry.identity.runId)
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
                    if (tracked.remove(entry.identity.runId, entry)) {
                        job.cancel(RunStoppedException("run invalidated by authorization change"))
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
     * the session fully intact and the run untouched.
     *
     * If the transaction commits but the receipt is lost (OutcomeUnknown), the session is
     * re-read: once it is verifiably gone, the work stops too and the OutcomeUnknown still
     * propagates — a deleted session never keeps consuming the network.
     */
    suspend fun deleteSession(sessionId: String): SessionDeletionReport = withContext(NonCancellable) {
        val report = try {
            history.deleteSession(sessionId, processOwnerId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AgentHistoryException) {
            if (error.code == AgentHistoryError.OutcomeUnknown && sessionVerifiablyGone(sessionId)) {
                stopSessionWork(sessionId)
            }
            throw error
        }
        stopSessionWork(sessionId)
        report
    }

    /**
     * G1 restore entry point: stop the in-memory work for the enumerated RUNNING runs without
     * writing CANCELLED/FAILED/INTERRUPTED or releasing their DB slots. The restore gate owns the
     * durable fate; a failed restore later uses the explicit controlled-interruption path.
     */
    fun revokeForRestore(runIds: Set<String>) {
        if (runIds.isEmpty()) return
        for (entry in tracked.values.toList()) {
            if (entry.identity.runId !in runIds) continue
            if (tracked.remove(entry.identity.runId, entry)) {
                entry.job?.cancel(RestoreStoppedException("run revoked for confirmed restore"))
            }
        }
    }

    /** Process teardown only; runs are otherwise never cancelled by lifecycle events. */
    fun close() {
        scope.cancel()
    }

    /** Test seam: live registry size; terminal runs deregister themselves via completion hooks. */
    internal fun trackedRunCount(): Int = tracked.size

    private suspend fun sessionVerifiablyGone(sessionId: String): Boolean = try {
        history.session(sessionId)
        false
    } catch (error: CancellationException) {
        throw error
    } catch (error: AgentHistoryException) {
        error.code == AgentHistoryError.NotFound
    } catch (error: Exception) {
        // The re-read itself failed: cannot certify the deletion, so do not stop the work.
        false
    }

    /** Stops every in-memory run of the session; already-finished jobs ignore cancellation. */
    private fun stopSessionWork(sessionId: String) {
        for (entry in tracked.values.toList()) {
            if (entry.sessionId != sessionId) continue
            if (tracked.remove(entry.identity.runId, entry)) {
                entry.job?.cancel(RunStoppedException("run stopped by owner"))
            }
        }
    }

    private suspend fun activeRunOf(sessionId: String): ActiveRunView? =
        history.activeRun(sessionId)?.takeIf { it.state == RunState.RUNNING }

    private class RunStoppedException(message: String) :
        CancellationException(message)

    private class RestoreStoppedException(message: String) :
        CancellationException(message)
}

package com.paherdin.record2.backup

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class RestorePhase {
    IDLE,
    CONFIRMED,
    WAITING_FOR_GATE,
    PREPARING,
    IMPORTING,
    COMMITTED,
    POST_COMMIT,
    FINISHED,
    FAILED_NO_COMMIT,
    OUTCOME_UNKNOWN,
}

internal data class RestoreStatus(
    val phase: RestorePhase,
    val operationId: String? = null,
    val candidateId: String? = null,
    val result: RestoreResult? = null,
    val errorCode: BackupError? = null,
    val errorMessage: String? = null,
    /** True when the result was rebuilt from a receipt after a lost observation/cancellation. */
    val recoveredFromReceipt: Boolean = false,
)

internal class RestoreHandle internal constructor(
    val operationId: String,
    private val completion: Deferred<RestoreResult>,
) {
    val isCompleted: Boolean get() = completion.isCompleted

    suspend fun await(): RestoreResult = completion.await()
}

internal sealed interface RestoreStart {
    data class Started(val handle: RestoreHandle) : RestoreStart
    data class Existing(val handle: RestoreHandle) : RestoreStart
    data class Busy(val activeOperationId: String) : RestoreStart
}

/**
 * Application-level owner of confirmed restores. The execution scope is independent of any page,
 * Activity or ViewModel; cancelling an observer only cancels that waiter, never the restore.
 * One confirmed operation may run per process. Repeating the same candidate returns the original
 * handle; another candidate is Busy until the active operation reaches a terminal state.
 *
 * A persisted receipt is the commit authority. If a receipt already matches the candidate, the
 * coordinator returns a receipt-rebuilt result (transform counts Unknown) without importing again.
 * The composition root must keep exactly one instance per process and must not persist or reuse an
 * unfinished candidate across process death; the receipt proves the current database content, not
 * a permanent cross-process attempt identity.
 */
internal class RestoreCoordinator(
    private val engine: RestoreEngine,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class Active(
        val operationId: String,
        val candidate: RestoreCandidate,
        val handle: RestoreHandle,
        val completion: CompletableDeferred<RestoreResult>,
        var job: Job? = null,
    )

    private data class Terminal(val handle: RestoreHandle, val status: RestoreStatus)

    private val lock = Any()
    private var active: Active? = null
    private val terminal = LinkedHashMap<String, Terminal>()
    private val _status = MutableStateFlow(RestoreStatus(RestorePhase.IDLE))
    val status: StateFlow<RestoreStatus> = _status.asStateFlow()

    fun currentStatus(): RestoreStatus = _status.value

    /** Called only after preflight and explicit user confirmation. Never suspends on the UI job. */
    fun confirm(candidate: RestoreCandidate): RestoreStart = synchronized(lock) {
        active?.let { running ->
            return if (running.candidate.id == candidate.id) {
                RestoreStart.Existing(running.handle)
            } else {
                RestoreStart.Busy(running.operationId)
            }
        }
        terminal[candidate.id]?.let { return RestoreStart.Existing(it.handle) }

        val operationId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<RestoreResult>()
        val handle = RestoreHandle(operationId, completion)
        val entry = Active(operationId, candidate, handle, completion)
        active = entry
        _status.value = RestoreStatus(
            phase = RestorePhase.CONFIRMED,
            operationId = operationId,
            candidateId = candidate.id,
        )
        entry.job = scope.launch { execute(entry) }
        RestoreStart.Started(handle)
    }

    /** Process teardown only; observers and pages never call this. */
    fun close() {
        scope.cancel()
    }

    private suspend fun execute(entry: Active) {
        try {
            when (val lookup = engine.receiptLookup(entry.candidate)) {
                is RestoreReceiptLookup.Committed -> {
                    val result = engine.completedResult(lookup.receipt)
                    succeed(entry, result, recovered = true)
                }
                is RestoreReceiptLookup.Unknown -> {
                    fail(
                        entry,
                        BackupError.TargetInvalid,
                        lookup.reason,
                        RestorePhase.OUTCOME_UNKNOWN,
                    )
                }
                RestoreReceiptLookup.None -> {
                    val result = engine.restore(entry.candidate) { progress ->
                        reportProgress(entry, progress)
                    }
                    succeed(entry, result, recovered = false)
                }
            }
        } catch (cancelled: CancellationException) {
            classifyAfterCancellation(entry, cancelled)
            throw cancelled
        } catch (error: BackupException) {
            classifyFailure(entry, error, error.code)
        } catch (error: Exception) {
            classifyFailure(entry, error, BackupError.Internal)
        }
    }

    private fun reportProgress(entry: Active, progress: RestoreProgress) {
        val phase = when (progress) {
            RestoreProgress.WAITING_FOR_GATE -> RestorePhase.WAITING_FOR_GATE
            RestoreProgress.PREPARING -> RestorePhase.PREPARING
            RestoreProgress.IMPORTING -> RestorePhase.IMPORTING
            RestoreProgress.COMMITTED -> RestorePhase.COMMITTED
            RestoreProgress.POST_COMMIT -> RestorePhase.POST_COMMIT
        }
        synchronized(lock) {
            if (active?.operationId == entry.operationId) {
                _status.value = RestoreStatus(
                    phase = phase,
                    operationId = entry.operationId,
                    candidateId = entry.candidate.id,
                )
            }
        }
    }

    private suspend fun classifyAfterCancellation(entry: Active, cancelled: CancellationException) {
        val lookup = try {
            engine.receiptLookup(entry.candidate)
        } catch (readError: Exception) {
            fail(
                entry,
                BackupError.Internal,
                "restore cancellation could not verify the receipt: ${readError.message}",
                RestorePhase.OUTCOME_UNKNOWN,
                cancelled,
            )
            return
        }
        when (lookup) {
            is RestoreReceiptLookup.Committed -> {
                succeed(entry, engine.completedResult(lookup.receipt), recovered = true)
            }
            is RestoreReceiptLookup.Unknown -> fail(
                entry,
                BackupError.TargetInvalid,
                lookup.reason,
                RestorePhase.OUTCOME_UNKNOWN,
                cancelled,
            )
            RestoreReceiptLookup.None -> fail(
                entry,
                BackupError.Cancelled,
                "confirmed restore stopped before commit",
                RestorePhase.FAILED_NO_COMMIT,
                cancelled,
            )
        }
    }

    private suspend fun classifyFailure(entry: Active, error: Exception, code: BackupError) {
        val lookup = try {
            engine.receiptLookup(entry.candidate)
        } catch (readError: Exception) {
            fail(
                entry,
                BackupError.Internal,
                "restore failure could not verify the receipt: ${readError.message}",
                RestorePhase.OUTCOME_UNKNOWN,
                error,
            )
            return
        }
        when (lookup) {
            is RestoreReceiptLookup.Committed -> {
                succeed(entry, engine.completedResult(lookup.receipt), recovered = true)
            }
            is RestoreReceiptLookup.Unknown -> fail(
                entry,
                BackupError.TargetInvalid,
                lookup.reason,
                RestorePhase.OUTCOME_UNKNOWN,
                error,
            )
            RestoreReceiptLookup.None -> fail(
                entry,
                code,
                error.message ?: "restore failed before commit",
                RestorePhase.FAILED_NO_COMMIT,
                error,
            )
        }
    }

    private fun succeed(entry: Active, result: RestoreResult, recovered: Boolean) {
        val status = RestoreStatus(
            phase = RestorePhase.FINISHED,
            operationId = entry.operationId,
            candidateId = entry.candidate.id,
            result = result,
            recoveredFromReceipt = recovered,
        )
        finish(entry, status) { entry.completion.complete(result) }
    }

    private fun fail(
        entry: Active,
        code: BackupError,
        message: String,
        phase: RestorePhase,
        cause: Throwable? = null,
    ) {
        val status = RestoreStatus(
            phase = phase,
            operationId = entry.operationId,
            candidateId = entry.candidate.id,
            errorCode = code,
            errorMessage = message,
        )
        finish(entry, status) {
            entry.completion.completeExceptionally(
                cause ?: BackupException(code, message),
            )
        }
    }

    private fun finish(entry: Active, status: RestoreStatus, complete: () -> Unit) {
        synchronized(lock) {
            if (active?.operationId == entry.operationId) active = null
            terminal[entry.candidate.id] = Terminal(entry.handle, status)
            while (terminal.size > MAX_TERMINAL_RECORDS) {
                val oldest = terminal.keys.iterator()
                oldest.next()
                oldest.remove()
            }
            _status.value = status
        }
        complete()
    }

    private companion object {
        const val MAX_TERMINAL_RECORDS = 16
    }
}

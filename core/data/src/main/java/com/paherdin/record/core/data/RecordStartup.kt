package com.paherdin.record.core.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Data-free startup progress (ADR 0016); never evidence that recovery completed. */
enum class StartupProgress { Checking, RecoveryDetected, Recovering }

/** Stable startup failure category (ADR 0016); carries no path, count, or exception. */
enum class StartupFailure { StorageExhausted, RecoveryRequired, Unexpected }

/** Receives every progress emission of one startup attempt. */
fun interface StartupObserver {
    fun onProgress(progress: StartupProgress)
}

/** Terminal outcome of one startup attempt (ADR 0016). */
sealed interface StartupResult {
    /** The active generation's writable Room database is open and bound. */
    data class Ready(val repository: RecordRepository) : StartupResult

    /** `canRetry` means the same coordinator may explicitly re-enter the engine. */
    data class Blocked(val failure: StartupFailure, val canRetry: Boolean) : StartupResult
}

/**
 * The single-owner application-facing startup coordinator (ADR 0016, plan
 * M1-T03). One instance owns at most one in-flight attempt: concurrent
 * `start()` callers coalesce onto the same terminal result, Ready is cached
 * without reopening, `retry()` opens a new attempt only after a retryable
 * `Blocked` result (Ready returns the cached Ready; a non-retryable Blocked
 * returns the same result without touching storage).
 *
 * The constructor is `internal` (not private) so the same-module pure tests
 * can inject a controlled engine; the public boundary still exposes only
 * `create(context)`.
 */
class RecordStartup internal constructor(private val engine: StartupEngine) {

    private val attempt = Mutex()
    private var lastTerminal: StartupResult? = null

    suspend fun start(observer: StartupObserver): StartupResult = attempt.withLock {
        lastTerminal?.let { return@withLock it }
        runAttempt(observer)
    }

    suspend fun retry(observer: StartupObserver): StartupResult = attempt.withLock {
        when (val last = lastTerminal) {
            is StartupResult.Ready -> last
            is StartupResult.Blocked -> if (last.canRetry) runAttempt(observer) else last
            null -> runAttempt(observer)
        }
    }

    private suspend fun runAttempt(observer: StartupObserver): StartupResult {
        val result = engine.run(observer)
        lastTerminal = result
        return result
    }

    companion object {
        fun create(context: Context): RecordStartup = RecordStartup(StartupEngine(context))
    }
}

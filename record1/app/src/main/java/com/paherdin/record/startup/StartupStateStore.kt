package com.paherdin.record.startup

import kotlinx.coroutines.CoroutineScope
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RecordStartup
import com.paherdin.record.core.data.StartupObserver
import com.paherdin.record.core.data.StartupProgress
import com.paherdin.record.core.data.StartupResult
import com.paherdin.record.core.ui.Phase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The immutable application startup state. Every pre-safe value is a [Gate]:
 * no shell, route, or repository surface exists before the single Ready
 * emission (ADR 0016, spec 9.2).
 */
internal sealed interface AppStartState {

    /** Data-free gate surface; `canRetry` only when the coordinator permits. */
    data class Gate(val phase: Phase, val canRetry: Boolean = false) : AppStartState

    /** The safe repository binding; the shell may be constructed. */
    data class Ready internal constructor(
        val repository: RecordRepository,
        val phase: Phase = Phase.Completed,
    ) : AppStartState
}

/**
 * The application-scoped startup state (plan M1-T07 Step 3): one in-flight
 * attempt at a time, one immutable [state] flow, and idempotent
 * `start`/`retry`. It maps the `RecordStartup` progress/outcomes onto the
 * exact interaction phases and never exposes the repository to feature UI.
 */
internal class StartupStateStore(
    private val startup: RecordStartup,
    private val scope: CoroutineScope,
) {
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _state = MutableStateFlow<AppStartState>(AppStartState.Gate(Phase.Checking))
    val state: StateFlow<AppStartState> = _state.asStateFlow()

    fun start() = launchAttempt { observer -> startup.start(observer) }

    fun retry() = launchAttempt { observer -> startup.retry(observer) }

    private fun launchAttempt(block: suspend (StartupObserver) -> StartupResult) {
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val result = block(StartupObserver { progress ->
                    _state.value = AppStartState.Gate(
                        when (progress) {
                            StartupProgress.Checking -> Phase.Checking
                            StartupProgress.RecoveryDetected -> Phase.Running
                            StartupProgress.Recovering -> Phase.Recovering
                        },
                    )
                })
                _state.value = when (result) {
                    is StartupResult.Ready -> AppStartState.Ready(result.repository)
                    is StartupResult.Blocked -> AppStartState.Gate(
                        phase = if (result.canRetry) Phase.RecoverableFailure else Phase.BlockingFailure,
                        canRetry = result.canRetry,
                    )
                }
            } finally {
                inFlight.set(false)
            }
        }
    }
}
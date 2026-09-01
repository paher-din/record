package com.paherdin.record.core.ui

/**
 * The exact closed operation-phase state machine shared by the app and features.
 *
 * This mirrors the interaction-rules phase table verbatim. Features may narrow
 * legal transitions per flow but may not add tokens, widen transitions, or
 * introduce hidden alias flags.
 */
enum class Phase {
    Loading,
    Idle,
    Selecting,
    Checking,
    Validating,
    Saving,
    Running,
    Cancelling,
    Cancelled,
    Recovering,
    RecoverableFailure,
    BlockingFailure,
    Completed,
}

/** The single source of truth for legal [Phase] transitions. */
object PhaseTransitions {
    fun allows(from: Phase, to: Phase): Boolean = to in when (from) {
        Phase.Loading -> setOf(Phase.Idle, Phase.Selecting, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Idle -> setOf(Phase.Selecting, Phase.Checking, Phase.Validating, Phase.Saving, Phase.Running, Phase.Cancelled)
        Phase.Selecting -> setOf(Phase.Idle, Phase.Checking, Phase.Validating, Phase.Running, Phase.Cancelled, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Checking -> setOf(Phase.Idle, Phase.Selecting, Phase.Validating, Phase.Running, Phase.Cancelling, Phase.Completed, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Validating -> setOf(Phase.Idle, Phase.Saving, Phase.Running, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Saving -> setOf(Phase.Completed, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Running -> setOf(Phase.Running, Phase.Idle, Phase.Cancelling, Phase.Completed, Phase.Recovering, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Cancelling -> setOf(Phase.Cancelled, Phase.Completed, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Recovering -> setOf(Phase.Completed, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.RecoverableFailure, Phase.BlockingFailure -> setOf(Phase.Loading, Phase.Idle, Phase.Selecting, Phase.Checking, Phase.Validating, Phase.Saving, Phase.Running, Phase.Recovering)
        Phase.Cancelled, Phase.Completed -> emptySet()
    }
}

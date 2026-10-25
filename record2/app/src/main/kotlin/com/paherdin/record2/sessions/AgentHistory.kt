package com.paherdin.record2.sessions

/**
 * Koog-free contract for the Room-authoritative session history (B0 §3.4, §6).
 * Implementations live in storage; the AI layer adapts them to Koog. This package must not
 * depend on Koog types.
 */

internal enum class SessionRole { USER, ASSISTANT }

// Persisted names follow B0 §3.4: ordinary input, Answer, Navigate, ProposalReady, ClarificationNeeded.
internal enum class MessageResultKind { INPUT, Answer, Navigate, ProposalReady, ClarificationNeeded }

internal enum class RunState { RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

// Terminal states a local writer may set; INTERRUPTED is owned by the process-ownership gate.
internal enum class RunTerminal { FAILED, CANCELLED }

// Bounded vocabulary only; never carries request/response content (B0 §3.4 safeErrorCode).
internal enum class SafeRunErrorCode { PROVIDER_FAILURE, BUDGET_EXCEEDED, CANCELLED, AUTHORIZATION, INTERNAL }

internal data class AgentSessionInfo(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val historyVersion: Long,
    val nextMessageSequence: Long,
)

internal data class MessageReference(val ordinal: Int, val transactionId: String?, val noteId: String?)

internal data class AgentSessionMessage(
    val id: String,
    val sequence: Long,
    val role: SessionRole,
    val body: String,
    val resultKind: MessageResultKind,
    val resultJson: String?,
    val proposalId: String?,
    val proposalRevision: Long?,
    val runId: String,
    val runState: RunState,
    val createdAt: Long,
    val references: List<MessageReference>,
)

// One completed USER/ASSISTANT exchange eligible for Koog context rebuild. No result payloads,
// no references, no hidden reasoning: the smallest reconstruction the model context needs.
internal data class CompletedHistoryEntry(val role: SessionRole, val body: String)

internal data class RunOutcome(
    val resultKind: MessageResultKind,
    val body: String,
    val resultJson: String? = null,
    val proposalId: String? = null,
    val proposalRevision: Long? = null,
    val references: List<MessageReference> = emptyList(),
) {
    init {
        require(resultKind != MessageResultKind.INPUT) { "A run outcome is never an ordinary input" }
    }
}

/** Stable identities generated before the start transaction, so a caller that loses the
 * start receipt (crash or cancellation in the commit window) can still reconcile the exact run
 * it attempted by identity instead of leaving an orphan slot holder. */
internal data class RunIdentity(
    val runId: String,
    val userMessageId: String,
    val proposalCreationRequestId: String,
)

internal data class RunStartReceipt(
    val runId: String,
    val sessionId: String,
    val userMessageId: String,
    val userMessageSequence: Long,
    val boundarySequence: Long,
    val boundaryHistoryVersion: Long,
    val proposalCreationRequestId: String,
    val receiverIdentity: String,
    val authEpoch: Long,
    val dataGeneration: String,
    val processOwnerId: String,
    val startedAt: Long,
)

internal data class RunCompletionReceipt(
    val runId: String,
    val assistantMessageId: String,
    val assistantMessageSequence: Long,
    val finishedAt: Long,
    val alreadyCompleted: Boolean,
)

internal data class RunTerminalReceipt(
    val runId: String,
    val state: RunState,
    val safeErrorCode: String?,
    val alreadyTerminal: Boolean,
)

/** Read-only reconciliation view of one run's persisted outcome, keyed by run identity. */
internal data class PersistedRunResult(
    val runId: String,
    val state: RunState,
    val assistantMessageId: String?,
    val assistantMessageSequence: Long?,
    val finishedAt: Long?,
    /** Full persisted outcome semantics, including resultKind, result payload and references. */
    val outcome: RunOutcome?,
)

internal data class ProcessOwnershipReceipt(
    val processOwnerId: String,
    val adopted: Boolean,
    val interruptedRuns: Int,
    val emptyDatabase: Boolean,
)

/** Read-only view of the run currently holding a session's single active slot (B0 §3.4). */
internal data class ActiveRunView(
    val runId: String,
    val sessionId: String,
    val state: RunState,
    val startedAt: Long,
)

/** Retained commit_operation rows of a deleted session, by persisted state (E2 deletion report). */
internal data class SessionOperationCensus(
    val pending: Int,
    val applied: Int,
    val rejectedConflict: Int,
    val rejectedInvalid: Int,
) {
    val total: Int get() = pending + applied + rejectedConflict + rejectedInvalid
}

/**
 * Disclosure data for the explicit session-deletion entry (Spec 5.2, B0 §8): what the single
 * Room transaction cancelled, discarded, deleted and — always — retained. Business facts,
 * Applied mappings and Pending operations are never deleted by this protocol; the census is
 * the input the F2 disclosure UI will present before/after deletion.
 */
internal data class SessionDeletionReport(
    val sessionId: String,
    val cancelledActiveRuns: Int,
    /** Proposals deleted without any operation — the unconfirmed ones B0 §8 discards. */
    val discardedProposals: Int,
    /** Proposals deleted whose operation row stays behind as the verification basis. */
    val proposalsWithRetainedOperations: Int,
    val deletedRuns: Int,
    val deletedMessages: Int,
    val retainedOperations: SessionOperationCensus,
)

internal enum class AgentHistoryError {
    Validation,
    NotFound,
    SessionBusy,
    RunInvalidated,
    AuthorizationRequired,
    NotInitialized,
    RecoveryRequired,
    ConcurrentUpdate,
    OutcomeUnknown,
    RecoverableIO,
    StorageFull,
    /** A session row's derived content (e.g. an unparseable commit_operation state) cannot be
     * certified safe for independent retention, so the deletion refuses (Spec 5.2). */
    UnsafeSessionContent,
}

internal class AgentHistoryException(val code: AgentHistoryError, cause: Throwable? = null) :
    Exception(code.name, cause)

internal interface AgentHistoryService {
    suspend fun createSession(title: String? = null): AgentSessionInfo

    suspend fun session(sessionId: String): AgentSessionInfo

    /** Full user-visible history for UI rendering: failed/interrupted inputs stay readable. */
    suspend fun sessionMessages(sessionId: String): List<AgentSessionMessage>

    /**
     * Completed USER/ASSISTANT history before [beforeSequence], ascending by sequence. [limit]
     * bounds the database scan and the returned entries to the most recent ones; the full UI
     * history stays on [sessionMessages].
     */
    suspend fun completedHistory(
        sessionId: String,
        beforeSequence: Long,
        limit: Int? = null,
    ): List<CompletedHistoryEntry>

    /** Fresh stable identities for one start attempt; pure, no database access. */
    fun newRunIdentity(): RunIdentity

    /** Read-only outcome view used to reconcile a lost completion receipt by run identity. */
    suspend fun persistedResult(runId: String): PersistedRunResult?

    /**
     * Single short Room transaction: persist the current user message plus a RUNNING run that
     * owns it, recording the completed-history boundary (B0 §6). Concurrent starts on one
     * session elect exactly one run; the loser persists nothing.
     */
    suspend fun startRun(
        sessionId: String,
        userText: String,
        receiverIdentity: String,
        authEpoch: Long,
        processOwnerId: String,
        identity: RunIdentity,
    ): RunStartReceipt

    /**
     * Single short Room transaction: final reply plus run terminal state plus slot release
     * plus history-version bump (B0 §6). Revalidates run ownership, authorization epoch and
     * data generation; invalidated or late writers change nothing.
     */
    suspend fun completeRun(
        runId: String,
        receiverIdentity: String,
        authEpoch: Long,
        processOwnerId: String,
        outcome: RunOutcome,
    ): RunCompletionReceipt

    /** Terminal FAILED/CANCELLED without a final reply; idempotent for already-terminal runs. */
    suspend fun failRun(runId: String, state: RunTerminal, safeErrorCode: SafeRunErrorCode): RunTerminalReceipt

    /**
     * Process-start gate (B0 §6): adopt [processOwnerId], mark every RUNNING run INTERRUPTED
     * (single-process V1: at gate time all RUNNING rows predate this process) and free their
     * slots. A repeated call with the current owner is a no-op that never touches live runs.
     */
    suspend fun beginProcessOwnership(processOwnerId: String): ProcessOwnershipReceipt

    /** The run currently holding [sessionId]'s single active slot, or null when the slot is free. */
    suspend fun activeRun(sessionId: String): ActiveRunView?

    /**
     * Explicit user session deletion in one short Room transaction (Spec 5.2, B0 §8): the active
     * run is cancelled, unconfirmed proposals discarded, message references/messages/proposals/
     * runs deleted and finally the session row — leaving no row a late writer could resurrect.
     * Business facts and every commit_operation row are never touched: operations stay behind as
     * the independent verification basis. Refuses with [AgentHistoryError.UnsafeSessionContent]
     * when an operation state cannot be parsed (unknown operations are never cascade-deleted)
     * and leaves the whole history intact.
     */
    suspend fun deleteSession(sessionId: String, processOwnerId: String): SessionDeletionReport
}

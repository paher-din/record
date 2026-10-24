package com.paherdin.record2.proposals

/**
 * What the native confirmation command carries: the exact displayed revision and digest the
 * owner approved, plus the data generation the approving screen was reading. The entry is
 * native-only; agents never call it, and it does not require the originating run to be alive.
 */
internal data class ConfirmationCommand(
    val proposalId: String,
    val displayedRevision: Long,
    val displayedPayloadDigest: String,
    val dataGeneration: String,
)

internal data class CommitOutcome(
    val operationId: String,
    val proposalId: String,
    val confirmedRevision: Long,
    val createdGeneration: String,
    val status: CommitStatus,
    /** True only when this confirm call itself created the operation; an existing operation never re-arms execution. */
    val fresh: Boolean,
    val result: AppliedCommitResult?,
    val rejectionCode: String?,
    val finishedAt: Long?,
    val idAssignments: Map<String, String>,
)

internal interface CommitService {
    /** Transaction A: freeze the displayed proposal into a Pending operation and lock it, or verify a repeat. */
    suspend fun confirm(command: ConfirmationCommand): CommitOutcome

    /**
     * Transaction B: deterministically apply one operation by identity. Idempotent for every
     * terminal state; a Pending operation only executes while its created generation is current.
     * User-initiated only — the same entry serves the controlled same-id retry after an
     * interrupted execution.
     */
    suspend fun execute(operationId: String): CommitOutcome

    /** Read-only verification by operation identity; unknown identities return null, corruption fails closed. */
    suspend fun inspect(operationId: String): CommitOutcome?
}

/** Fault points of the A and B transactions; the integer orders BUSINESS_WRITTEN writes. */
internal enum class CommitCheckpoint {
    DISPLAY_VERIFIED, OPERATION_WRITTEN, A_COMMITTED,
    PAYLOAD_VERIFIED, TARGETS_VALIDATED, BUSINESS_WRITTEN, APPLIED_WRITTEN, B_COMMITTED,
}

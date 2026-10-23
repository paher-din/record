package com.paherdin.record2.proposals

/**
 * Minimal run identity the Coordinator hands to prepare/revise. The creation request identity is
 * deliberately absent: it is read from the persisted run row so that a repeated or replayed tool
 * call cannot mint a second creation identity for the same run.
 */
internal data class ProposalRunContext(
    val sessionId: String,
    val runId: String,
    val processOwnerId: String,
    val receiverIdentity: String,
    val authEpoch: Long,
)

internal data class ProposalView(
    val id: String,
    val sessionId: String,
    val originRunId: String,
    val lastWriterRunId: String,
    val creationRequestId: String,
    val revision: Long,
    val state: ProposalState,
    val payloadFormatVersion: Int,
    val payloadDigest: String,
    val dataGeneration: String,
    val operations: List<ProposalOperation>,
    val createdAt: Long,
    val updatedAt: Long,
)

internal interface ProposalService {
    suspend fun prepare(context: ProposalRunContext, intents: List<ProposalIntent>): ProposalView
    suspend fun revise(
        context: ProposalRunContext,
        proposalId: String,
        expectedRevision: Long,
        intents: List<ProposalIntent>,
    ): ProposalView

    /** Active slot of a session; a stale generation is an unrecovered state and fails closed. */
    suspend fun activeProposal(generation: String, sessionId: String): ProposalView?

    /** Persisted history read; the returned view carries its own generation for the caller to judge. */
    suspend fun proposal(proposalId: String): ProposalView?
}

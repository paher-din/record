package com.paherdin.record2.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface ProposalDao {
    @Insert fun insertProposal(row: ChangeProposalEntity)
    @Insert fun insertOperation(row: CommitOperationEntity)

    @Query("SELECT * FROM change_proposal WHERE id = :id")
    fun proposal(id: String): ChangeProposalEntity?

    @Query("SELECT * FROM change_proposal WHERE creationRequestId = :creationRequestId")
    fun proposalByCreationRequest(creationRequestId: String): ChangeProposalEntity?

    @Query("SELECT * FROM change_proposal WHERE sessionId = :sessionId AND activeSlot = 1")
    fun activeProposal(sessionId: String): ChangeProposalEntity?

    // A soft-deleted target must be reported as NotVisible, which a visible-only read cannot
    // distinguish from an absent one.
    @Query("SELECT * FROM note WHERE id = :id")
    fun noteIncludingDeleted(id: String): NoteEntity?

    @Query("SELECT * FROM commit_operation WHERE proposalId = :proposalId")
    fun operationForProposal(proposalId: String): CommitOperationEntity?

    @Query("SELECT * FROM commit_operation WHERE id = :id")
    fun operation(id: String): CommitOperationEntity?

    // Session deletion (E2, B0 §8): enumerate one session's proposals for the retention census,
    // then remove them inside the same transaction. Operations are never deleted here.
    @Query("SELECT * FROM change_proposal WHERE sessionId = :sessionId")
    fun proposalsForSession(sessionId: String): List<ChangeProposalEntity>

    @Query("DELETE FROM change_proposal WHERE sessionId = :sessionId")
    fun deleteProposalsForSession(sessionId: String): Int

    // Transaction A locks the confirmed proposal; LOCKED keeps occupying the active slot.
    @Query("""
        UPDATE change_proposal SET state = 'LOCKED', updatedAt = :updatedAt
        WHERE id = :id AND revision = :expectedRevision AND state = 'EDITABLE' AND activeSlot = 1
    """)
    fun lockProposal(id: String, expectedRevision: Long, updatedAt: Long): Int

    // Transaction B settles a still-present locked proposal into a terminal state and frees the slot.
    @Query("""
        UPDATE change_proposal SET state = :state, activeSlot = NULL, updatedAt = :updatedAt
        WHERE id = :id AND state = 'LOCKED' AND activeSlot = 1
        AND :state IN ('APPLIED', 'REJECTED_CONFLICT', 'REJECTED_INVALID')
    """)
    fun settleProposal(id: String, state: String, updatedAt: Long): Int

    @Query("""
        UPDATE change_proposal SET payloadJson = :payloadJson, payloadDigest = :payloadDigest,
        revision = revision + 1, lastWriterRunId = :runId, updatedAt = :updatedAt
        WHERE id = :id AND revision = :expectedRevision AND state = 'EDITABLE'
        AND activeSlot = 1 AND revision < 9223372036854775807
    """)
    fun reviseProposal(
        id: String, expectedRevision: Long, runId: String,
        payloadJson: String, payloadDigest: String, updatedAt: Long,
    ): Int

    @Query("""
        UPDATE commit_operation SET state = :state, resultJson = :resultJson,
        rejectionCode = :rejectionCode, finishedAt = :finishedAt
        WHERE id = :id AND state = 'Pending' AND payloadDigest = :expectedDigest
        AND :state IN ('Applied', 'RejectedConflict', 'RejectedInvalid')
    """)
    fun finishOperation(
        id: String, expectedDigest: String, state: String,
        resultJson: String?, rejectionCode: String?, finishedAt: Long,
    ): Int
}

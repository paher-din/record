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

    @Query("SELECT * FROM commit_operation WHERE proposalId = :proposalId")
    fun operationForProposal(proposalId: String): CommitOperationEntity?

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

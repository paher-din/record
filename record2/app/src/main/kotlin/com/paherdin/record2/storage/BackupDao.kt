package com.paherdin.record2.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Delete/insert surface used only by the single restore transaction. */
@Dao
internal interface BackupDao {
    @Query("DELETE FROM agent_message_reference") fun deleteReferences(): Int
    @Query("DELETE FROM agent_message") fun deleteMessages(): Int
    @Query("DELETE FROM change_proposal") fun deleteProposals(): Int
    @Query("DELETE FROM commit_operation") fun deleteOperations(): Int
    @Query("DELETE FROM agent_run") fun deleteRuns(): Int
    @Query("DELETE FROM agent_session") fun deleteSessions(): Int
    @Query("DELETE FROM transaction_note_link") fun deleteLinks(): Int
    @Query("DELETE FROM native_finance_draft") fun deleteDrafts(): Int
    @Query("DELETE FROM note") fun deleteNotes(): Int
    @Query("DELETE FROM record_transaction") fun deleteTransactions(): Int
    @Query("DELETE FROM category") fun deleteCategories(): Int
    @Query("DELETE FROM account") fun deleteAccounts(): Int
    @Query("DELETE FROM ai_control") fun deleteControl(): Int
    @Query("DELETE FROM record_data_state") fun deleteDataState(): Int

    @Insert fun insertAccount(row: AccountEntity)
    @Insert fun insertCategory(row: CategoryEntity)
    @Insert fun insertTransaction(row: TransactionEntity)
    @Insert fun insertNote(row: NoteEntity)
    @Insert fun insertLink(row: TransactionNoteLinkEntity)
    @Insert fun insertDraft(row: NativeFinanceDraftEntity)
    @Insert fun insertProposal(row: ChangeProposalEntity)
    @Insert fun insertOperation(row: CommitOperationEntity)
    @Insert fun insertSession(row: AgentSessionEntity)
    @Insert fun insertRun(row: AgentRunEntity)
    @Insert fun insertMessage(row: AgentMessageEntity)
    @Insert fun insertReference(row: AgentMessageReferenceEntity)
    @Insert fun insertControl(row: AiControlEntity)
    @Insert fun insertDataState(row: RecordDataStateEntity)

    @Query("SELECT COUNT(*) FROM account") fun accountCount(): Long
    @Query("SELECT COUNT(*) FROM category") fun categoryCount(): Long
    @Query("SELECT COUNT(*) FROM record_transaction") fun transactionCount(): Long
    @Query("SELECT COUNT(*) FROM note") fun noteCount(): Long
    @Query("SELECT COUNT(*) FROM transaction_note_link") fun linkCount(): Long
    @Query("SELECT COUNT(*) FROM native_finance_draft") fun draftCount(): Long
    @Query("SELECT COUNT(*) FROM change_proposal") fun proposalCount(): Long
    @Query("SELECT COUNT(*) FROM commit_operation") fun operationCount(): Long
    @Query("SELECT COUNT(*) FROM agent_session") fun sessionCount(): Long
    @Query("SELECT COUNT(*) FROM agent_run") fun runCount(): Long
    @Query("SELECT COUNT(*) FROM agent_message") fun messageCount(): Long
    @Query("SELECT COUNT(*) FROM agent_message_reference") fun referenceCount(): Long

    @Query("SELECT COUNT(*) FROM agent_run WHERE state = 'RUNNING' OR activeSlot IS NOT NULL")
    fun activeRunCount(): Long

    @Query("SELECT COUNT(*) FROM change_proposal WHERE state IN ('EDITABLE', 'LOCKED') OR activeSlot IS NOT NULL")
    fun activeProposalCount(): Long

    @Query("SELECT COUNT(*) FROM ai_control WHERE id = 1 AND mode = 'OFF' AND grantReceiverIdentity IS NULL") fun offControlCount(): Long
}

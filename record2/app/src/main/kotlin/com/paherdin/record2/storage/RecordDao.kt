package com.paherdin.record2.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface RecordDao {
    @Insert fun insertDataState(row: RecordDataStateEntity)
    @Insert fun insertAccount(row: AccountEntity)
    @Insert fun insertCategory(row: CategoryEntity)
    @Insert fun insertTransaction(row: TransactionEntity)
    @Insert fun insertNote(row: NoteEntity)
    @Insert fun insertLink(row: TransactionNoteLinkEntity)
    @Insert fun insertDraft(row: NativeFinanceDraftEntity)

    @Query("SELECT * FROM record_transaction WHERE id = :id")
    fun transaction(id: String): TransactionEntity?

    @Query("SELECT * FROM note WHERE id = :id AND deletedAt IS NULL")
    fun visibleNote(id: String): NoteEntity?

    @Query("""
        SELECT n.* FROM note n
        JOIN transaction_note_link l ON l.noteId = n.id
        WHERE l.transactionId = :transactionId AND n.deletedAt IS NULL
        ORDER BY n.occurredAt DESC, n.id ASC
    """)
    fun visibleNotesForTransaction(transactionId: String): List<NoteEntity>

    @Query("""
        SELECT t.* FROM record_transaction t
        JOIN transaction_note_link l ON l.transactionId = t.id
        WHERE l.noteId = :noteId
        ORDER BY t.occurredAt DESC, t.id ASC
    """)
    fun transactionsForNote(noteId: String): List<TransactionEntity>

    @Query("""
        SELECT COUNT(*) FROM transaction_note_link l
        JOIN note n ON n.id = l.noteId
        WHERE l.transactionId = :transactionId AND n.deletedAt IS NULL
    """)
    fun visibleLinkCount(transactionId: String): Int

    @Query("SELECT noteId FROM transaction_note_link WHERE transactionId = :transactionId")
    fun linkedNoteIds(transactionId: String): List<String>

    @Query("""
        SELECT n.* FROM note n
        WHERE n.deletedAt IS NULL
        AND n.id NOT IN (SELECT noteId FROM transaction_note_link WHERE transactionId = :transactionId)
        ORDER BY n.occurredAt DESC, n.id ASC
    """)
    fun visibleUnlinkedNoteCandidates(transactionId: String): List<NoteEntity>

    @Query("""
        UPDATE note SET version = version + 1, updatedAt = :updatedAt
        WHERE id = :id AND version = :expectedVersion AND deletedAt IS NULL
        AND version < 9223372036854775807
    """)
    fun touchNote(id: String, expectedVersion: Long, updatedAt: Long): Int

    @Query("""
        UPDATE record_transaction SET version = version + 1, updatedAt = :updatedAt
        WHERE id = :id AND version = :expectedVersion AND version < 9223372036854775807
    """)
    fun touchTransaction(id: String, expectedVersion: Long, updatedAt: Long): Int

    @Query("SELECT * FROM note WHERE id = :id")
    fun noteForLifecycle(id: String): NoteEntity?

    @Query("""
        SELECT * FROM note WHERE deletedAt IS NOT NULL
        AND (:afterTime IS NULL OR deletedAt < :afterTime OR (deletedAt = :afterTime AND id > :afterId))
        ORDER BY deletedAt DESC, id ASC LIMIT :limit
    """)
    fun deletedNotes(limit: Int, afterTime: Long?, afterId: String?): List<NoteEntity>

    @Query("""
        UPDATE note SET deletedAt = :deletedAt, version = version + 1, updatedAt = :updatedAt
        WHERE id = :id AND version = :expectedVersion AND deletedAt IS NULL
        AND version < 9223372036854775807
    """)
    fun softDeleteNote(id: String, expectedVersion: Long, deletedAt: Long, updatedAt: Long): Int

    @Query("""
        UPDATE note SET deletedAt = NULL, version = version + 1, updatedAt = :updatedAt
        WHERE id = :id AND version = :expectedVersion AND deletedAt IS NOT NULL
        AND version < 9223372036854775807
    """)
    fun restoreNote(id: String, expectedVersion: Long, updatedAt: Long): Int

    @Query("SELECT * FROM native_finance_draft WHERE slot = 1")
    fun draft(): NativeFinanceDraftEntity?

    @Query("SELECT * FROM record_data_state WHERE id = 1")
    fun dataState(): RecordDataStateEntity?

    @Query("UPDATE record_data_state SET processOwnerId = :processOwnerId WHERE id = 1")
    fun updateProcessOwner(processOwnerId: String): Int

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM account UNION ALL SELECT 1 FROM category
            UNION ALL SELECT 1 FROM record_transaction UNION ALL SELECT 1 FROM note
            UNION ALL SELECT 1 FROM transaction_note_link UNION ALL SELECT 1 FROM native_finance_draft
            UNION ALL SELECT 1 FROM change_proposal UNION ALL SELECT 1 FROM commit_operation
            UNION ALL SELECT 1 FROM agent_session UNION ALL SELECT 1 FROM agent_run
            UNION ALL SELECT 1 FROM agent_message UNION ALL SELECT 1 FROM agent_message_reference
            UNION ALL SELECT 1 FROM ai_control LIMIT 1
        )
    """)
    fun hasDataWithoutGeneration(): Boolean

    @Query("SELECT * FROM account WHERE id = :id")
    fun account(id: String): AccountEntity?

    @Query("SELECT * FROM category WHERE id = :id")
    fun category(id: String): CategoryEntity?

    @Query("SELECT * FROM category ORDER BY id")
    fun categories(): List<CategoryEntity>

    @Query("UPDATE record_data_state SET businessRevision = businessRevision + 1 WHERE id = 1 AND businessRevision = :expected AND businessRevision < 9223372036854775807")
    fun advanceBusinessRevision(expected: Long): Int

    @Query("UPDATE account SET name = :name, version = version + 1, updatedAt = :updatedAt WHERE id = :id AND version = :expectedVersion AND version < 9223372036854775807")
    fun renameAccount(id: String, expectedVersion: Long, name: String, updatedAt: Long): Int

    @Query("""
        UPDATE record_transaction SET direction = :direction, amountMinor = :amountMinor,
        accountId = :accountId, categoryId = :categoryId, memo = :memo, occurredAt = :occurredAt,
        version = version + 1, updatedAt = :updatedAt
        WHERE id = :id AND version = :expectedVersion AND version < 9223372036854775807
    """)
    fun editTransaction(
        id: String, expectedVersion: Long, direction: String, amountMinor: Long,
        accountId: String, categoryId: String?, memo: String, occurredAt: Long, updatedAt: Long,
    ): Int

    @Query("""
        UPDATE native_finance_draft SET draftId = :draftId, draftRevision = draftRevision + 1,
        state = 'EDITING', mode = :mode, recordId = :recordId, baseVersion = :baseVersion,
        inputJson = :inputJson, updatedAt = :updatedAt, saveReceiptJson = NULL
        WHERE slot = 1 AND draftId = :expectedId AND draftRevision = :expectedRevision
        AND state IN ('SAVED', 'DISCARDED') AND draftRevision < 9223372036854775807
    """)
    fun replaceDraft(
        expectedId: String, expectedRevision: Long, draftId: String, mode: String,
        recordId: String, baseVersion: Long?, inputJson: String, updatedAt: Long,
    ): Int

    @Query("""
        UPDATE native_finance_draft SET inputJson = :inputJson,
        draftRevision = draftRevision + 1, updatedAt = :updatedAt
        WHERE slot = 1 AND draftId = :id AND draftRevision = :expectedRevision
        AND state = 'EDITING' AND draftRevision < 9223372036854775807
    """)
    fun reviseDraft(id: String, expectedRevision: Long, inputJson: String, updatedAt: Long): Int

    @Query("""
        UPDATE native_finance_draft SET state = :state, inputJson = :inputJson,
        saveReceiptJson = :receiptJson, draftRevision = draftRevision + 1, updatedAt = :updatedAt
        WHERE slot = 1 AND draftId = :id AND draftRevision = :expectedRevision
        AND state = 'EDITING' AND draftRevision < 9223372036854775807
        AND :state IN ('SAVED', 'DISCARDED')
    """)
    fun finishDraft(
        id: String, expectedRevision: Long, state: String,
        inputJson: String, receiptJson: String?, updatedAt: Long,
    ): Int

    @Query("""
        UPDATE note SET body = :body, version = version + 1, updatedAt = :updatedAt
        WHERE id = :id AND version = :expectedVersion AND deletedAt IS NULL
        AND version < 9223372036854775807
    """)
    fun updateNoteBody(id: String, expectedVersion: Long, body: String, updatedAt: Long): Int

    @Query("DELETE FROM transaction_note_link WHERE transactionId = :transactionId AND noteId = :noteId")
    fun removeLink(transactionId: String, noteId: String): Int
}

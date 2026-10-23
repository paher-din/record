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

    @Query("SELECT * FROM native_finance_draft WHERE slot = 1")
    fun draft(): NativeFinanceDraftEntity?

    @Query("""
        UPDATE note SET body = :body, version = version + 1, updatedAt = :updatedAt
        WHERE id = :id AND version = :expectedVersion AND deletedAt IS NULL
        AND version < 9223372036854775807
    """)
    fun updateNoteBody(id: String, expectedVersion: Long, body: String, updatedAt: Long): Int

    @Query("DELETE FROM transaction_note_link WHERE transactionId = :transactionId AND noteId = :noteId")
    fun removeLink(transactionId: String, noteId: String): Int
}

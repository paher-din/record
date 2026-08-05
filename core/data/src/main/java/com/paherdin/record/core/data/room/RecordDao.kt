package com.paherdin.record.core.data.room

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.paherdin.record.core.data.room.Transaction as TransactionEntity

/** Transaction plus its entries in one read (Room relation via the FK). */
internal data class TransactionWithEntries(
    @Embedded val transaction: TransactionEntity,
    @Relation(parentColumn = "id", entityColumn = "transactionId")
    val entries: List<AccountEntry>,
)

/**
 * Module-internal DAO. All writes performed by the repository happen inside a
 * single `withTransaction`; constraint violations surface as SQLite exceptions
 * and are mapped at the repository boundary. The read-only `all*` queries are
 * the test snapshot seam.
 */
@Dao
internal interface RecordDao {
    @Insert
    suspend fun insertEntityRef(ref: EntityRef)

    @Insert
    suspend fun insertTransaction(tx: TransactionEntity)

    @Insert
    suspend fun insertAccount(account: Account)

    @Insert
    suspend fun insertAccountEntry(entry: AccountEntry)

    @Insert
    suspend fun insertNote(note: Note)

    @Insert
    suspend fun insertRelation(relation: TransactionNoteRelation)

    @Insert
    suspend fun insertAllEntries(entries: List<AccountEntry>)

    @Insert
    suspend fun insertAllRelations(relations: List<TransactionNoteRelation>)

    @Query("SELECT * FROM EntityRef WHERE id = :id")
    suspend fun getEntityRef(id: String): EntityRef?

    @Query("SELECT * FROM `Transaction` WHERE id = :id")
    suspend fun getTransaction(id: String): TransactionEntity?

    @Query("SELECT * FROM Note WHERE id = :id")
    suspend fun getNote(id: String): Note?

    @Query("SELECT * FROM Account WHERE id = :id")
    suspend fun getAccount(id: String): Account?

    @Transaction
    @Query("SELECT * FROM `Transaction` WHERE id = :id")
    suspend fun getTransactionWithEntries(id: String): TransactionWithEntries?

    @Query("SELECT * FROM AccountEntry WHERE transactionId = :transactionId")
    suspend fun getEntriesForTransaction(transactionId: String): List<AccountEntry>

    @Query("SELECT * FROM TransactionNoteRelation WHERE transactionId = :transactionId")
    suspend fun getRelationsForTransaction(transactionId: String): List<TransactionNoteRelation>

    @Query("SELECT * FROM TransactionNoteRelation WHERE noteId = :noteId")
    suspend fun getRelationsForNote(noteId: String): List<TransactionNoteRelation>

    @Query("DELETE FROM TransactionNoteRelation WHERE transactionId = :transactionId")
    suspend fun deleteRelationsForTransaction(transactionId: String)

    // Read-only snapshots (test seam).
    @Query("SELECT * FROM EntityRef")
    suspend fun allEntityRefs(): List<EntityRef>

    @Query("SELECT * FROM `Transaction`")
    suspend fun allTransactions(): List<TransactionEntity>

    @Query("SELECT * FROM Account")
    suspend fun allAccounts(): List<Account>

    @Query("SELECT * FROM AccountEntry")
    suspend fun allAccountEntries(): List<AccountEntry>

    @Query("SELECT * FROM Note")
    suspend fun allNotes(): List<Note>

    @Query("SELECT * FROM TransactionNoteRelation")
    suspend fun allRelations(): List<TransactionNoteRelation>
}

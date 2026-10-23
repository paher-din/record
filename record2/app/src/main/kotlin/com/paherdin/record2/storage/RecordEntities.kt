package com.paherdin.record2.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "record_data_state")
internal data class RecordDataStateEntity(
    @PrimaryKey val id: Int,
    val dataGeneration: String,
    val businessRevision: Long,
    val processOwnerId: String,
    val restoreReceipt: String?,
)

@Entity(tableName = "account")
internal data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currency: String,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "category", indices = [Index(value = ["id", "direction"], unique = true)])
internal data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val direction: String,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "record_transaction",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class, parentColumns = ["id", "direction"],
            childColumns = ["categoryId", "direction"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["occurredAt", "id"]),
        Index(value = ["direction", "occurredAt", "id"]),
        Index(value = ["accountId"]),
        Index(value = ["categoryId", "direction"]),
    ],
)
internal data class TransactionEntity(
    @PrimaryKey val id: String,
    val direction: String,
    val amountMinor: Long,
    val currency: String,
    val accountId: String,
    val categoryId: String?,
    val memo: String,
    val occurredAt: Long,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "note", indices = [Index(value = ["deletedAt", "occurredAt", "id"])])
internal data class NoteEntity(
    @PrimaryKey val id: String,
    val body: String,
    val occurredAt: Long,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Entity(
    tableName = "transaction_note_link",
    primaryKeys = ["transactionId", "noteId"],
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["noteId", "transactionId"])],
)
internal data class TransactionNoteLinkEntity(val transactionId: String, val noteId: String)

@Entity(tableName = "native_finance_draft")
internal data class NativeFinanceDraftEntity(
    @PrimaryKey val slot: Int,
    val draftId: String,
    val draftRevision: Long,
    val state: String,
    val mode: String,
    val recordId: String,
    val baseVersion: Long?,
    val inputJson: String,
    val updatedAt: Long,
    val saveReceiptJson: String?,
)

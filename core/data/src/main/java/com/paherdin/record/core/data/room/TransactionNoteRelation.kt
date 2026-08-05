package com.paherdin.record.core.data.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Typed, plain many-to-many relation (ADR 0002): composite PK, both FKs
 * `NO ACTION`, `(noteId, transactionId)` index for note-direction queries.
 */
@Entity(
    primaryKeys = ["transactionId", "noteId"],
    foreignKeys = [
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("noteId", "transactionId")],
)
internal data class TransactionNoteRelation(
    val transactionId: String,
    val noteId: String,
)

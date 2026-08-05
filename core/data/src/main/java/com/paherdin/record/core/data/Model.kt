package com.paherdin.record.core.data

import com.paherdin.record.model.TransactionType

/**
 * Application DTOs (plan "Public API" section). No discriminator field:
 * `entityType` is implied by the typed DTO — `NewTransaction` is always
 * `TRANSACTION`, `NewNote` always `NOTE`.
 */

data class NewAccount(val id: String, val name: String, val currency: String)

data class NewAccountEntry(val id: String, val accountId: String, val amountMinor: Long)

data class NewTransaction(
    val id: String,
    val type: TransactionType,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val entries: List<NewAccountEntry>,
    val relationNoteIds: Set<String>,
)

data class NewNote(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val relationTransactionIds: Set<String>,
)

data class TransactionView(
    val id: String,
    val type: TransactionType,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val entries: List<NewAccountEntry>,
    val relationNoteIds: Set<String>,
)

data class NoteView(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val relationTransactionIds: Set<String>,
)

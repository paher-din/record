package com.paherdin.record2.records

internal data class NoteRecord(
    val id: String,
    val body: String,
    val occurredAt: Long,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

internal data class DeletedNoteCursor(
    val deletedAt: Long,
    val id: String,
    val generation: String,
    val businessRevision: Long,
)
internal data class DeletedNotePage(val notes: List<NoteRecord>, val next: DeletedNoteCursor?)
internal data class NoteTransition(val note: NoteRecord, val changed: Boolean)

internal enum class NoteError {
    Validation, NotFound, NotVisible, VersionConflict, GenerationMismatch,
    RecoveryRequired, OutcomeUnknown, RecoverableIO, StorageFull, CursorExpired,
}

internal class NoteException(val code: NoteError, cause: Throwable? = null) : Exception(code.name, cause)

internal interface NoteService {
    suspend fun save(generation: String, id: String, expectedVersion: Long?, body: String, occurredAt: Long): NoteRecord?
    suspend fun visible(generation: String, id: String): NoteRecord
    suspend fun deleted(generation: String, id: String): NoteRecord
    suspend fun deletedPage(generation: String, limit: Int, after: DeletedNoteCursor? = null): DeletedNotePage
    suspend fun delete(generation: String, id: String, expectedVersion: Long): NoteTransition
    suspend fun restore(generation: String, id: String, expectedVersion: Long): NoteTransition
}

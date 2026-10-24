package com.paherdin.record2.records

internal data class LinkNoteRef(val id: String, val version: Long)

internal data class TransactionLinks(
    val transaction: FinanceRecord,
    val notes: List<NoteRecord>,
    val visibleLinkCount: Int,
)

internal data class NoteLinks(
    val note: NoteRecord,
    val transactions: List<FinanceRecord>,
    val visibleLinkCount: Int,
)

internal data class LinkSelection(
    val generation: String,
    val transaction: FinanceRecord,
    val linked: List<NoteRecord>,
    val candidates: List<NoteRecord>,
)

internal data class LinkBatchResult(
    val generation: String,
    val transaction: FinanceRecord,
    val notes: List<NoteRecord>,
    val visibleLinkCount: Int,
    val businessRevision: Long,
    val changed: Boolean,
)

internal enum class LinkError {
    Validation, NotFound, NotVisible, VersionConflict, GenerationMismatch,
    RecoveryRequired, OutcomeUnknown, RecoverableIO, StorageFull,
}

internal class LinkException(val code: LinkError, cause: Throwable? = null) : Exception(code.name, cause)

internal interface LinkService {
    suspend fun transactionLinks(generation: String, transactionId: String): TransactionLinks
    suspend fun noteLinks(generation: String, noteId: String): NoteLinks
    suspend fun selection(generation: String, transactionId: String): LinkSelection
    suspend fun apply(
        generation: String,
        transactionId: String,
        transactionExpectedVersion: Long,
        add: List<LinkNoteRef>,
        remove: List<LinkNoteRef>,
    ): LinkBatchResult
}

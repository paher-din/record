package com.paherdin.record2.records

import kotlinx.serialization.Serializable

@Serializable
internal enum class FinanceDirection { INCOME, EXPENSE }

@Serializable
internal data class FinanceAccount(val id: String, val name: String, val version: Long)

@Serializable
internal data class FinanceCategory(val id: String, val name: String, val direction: FinanceDirection, val version: Long)

internal data class FinanceCatalog(val generation: String, val account: FinanceAccount, val categories: List<FinanceCategory>)

@Serializable
internal data class FinanceInput(
    val amountText: String,
    val direction: FinanceDirection,
    val accountId: String,
    val accountVersion: Long,
    val categoryId: String?,
    val categoryVersion: Long?,
    val memo: String,
    val localDateTime: String,
    val zoneId: String,
)

@Serializable
internal data class FinanceRecord(
    val id: String, val direction: FinanceDirection, val amountMinor: Long,
    val accountId: String, val categoryId: String?, val memo: String,
    val occurredAt: Long, val version: Long, val createdAt: Long, val updatedAt: Long,
)

@Serializable
internal enum class FinanceDraftMode { NEW, EDIT }

internal data class FinanceDraft(
    val id: String, val revision: Long, val mode: FinanceDraftMode,
    val recordId: String, val baseVersion: Long?, val input: FinanceInput,
)

@Serializable
internal data class FinanceSaveReceipt(
    val formatVersion: Int = 1,
    val draftId: String,
    val confirmedDraftRevision: Long,
    val input: FinanceInput,
    val resolvedTime: ResolvedFinanceTime,
    val record: FinanceRecord,
)

internal enum class FinanceError {
    Validation, NotFound, VersionConflict, DraftConflict, DraftExists,
    GenerationMismatch, RecoveryRequired, OutcomeUnknown, RecoverableIO, StorageFull,
}

internal class FinanceException(val code: FinanceError, cause: Throwable? = null) : Exception(code.name, cause)

internal interface FinanceService {
    suspend fun initialize(): FinanceCatalog
    suspend fun renameAccount(generation: String, expectedVersion: Long, name: String): FinanceAccount
    suspend fun beginNewDraft(generation: String, input: FinanceInput): FinanceDraft
    suspend fun beginEditDraft(generation: String, recordId: String, expectedVersion: Long, zoneId: String): FinanceDraft
    suspend fun readDraft(generation: String): FinanceDraft?
    suspend fun updateDraft(generation: String, draftId: String, expectedRevision: Long, input: FinanceInput): FinanceDraft
    suspend fun discardDraft(generation: String, draftId: String, expectedRevision: Long)
    suspend fun saveDraft(generation: String, draftId: String, expectedRevision: Long): FinanceSaveReceipt
    suspend fun transaction(generation: String, id: String): FinanceRecord
}

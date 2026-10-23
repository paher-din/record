package com.paherdin.record2.proposals

import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceInput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * LOCAL names are proposal scoped and syntactically disjoint from persisted UUID identities, so a
 * reference can never be reinterpreted as a formal record identity.
 */
@Serializable
internal sealed class RecordRef {
    @Serializable
    @SerialName("LOCAL")
    data class Local(val localRef: String) : RecordRef()

    @Serializable
    @SerialName("TRANSACTION")
    data class Transaction(val id: String) : RecordRef()

    @Serializable
    @SerialName("NOTE")
    data class Note(val id: String) : RecordRef()
}

@Serializable
internal data class RecordPair(
    val transaction: RecordRef,
    val transactionVersion: Long,
    val note: RecordRef,
    val noteVersion: Long,
)

/** Tool facing request values; amounts and local date-times are still unresolved text. */
internal sealed class ProposalIntent {
    data class CreateTransaction(val localRef: String, val input: FinanceInput) : ProposalIntent()
    data class UpdateTransaction(val id: String, val expectedVersion: Long, val input: FinanceInput) : ProposalIntent()
    data class CreateNote(val localRef: String, val body: String, val localDateTime: String, val zoneId: String) : ProposalIntent()
    data class UpdateNote(val id: String, val expectedVersion: Long, val body: String, val localDateTime: String, val zoneId: String) : ProposalIntent()
    data class LinkRecords(val pairs: List<RecordPair>) : ProposalIntent()
    data class UnlinkRecords(val pairs: List<RecordPair>) : ProposalIntent()
}

@Serializable
internal data class ProposalTime(
    val occurredAt: Long,
    val zoneId: String,
    val localDateTime: String,
    val offsetSeconds: Int,
)

/** Normalized closed operations; every value is resolved and frozen, nothing is reinterpreted later. */
@Serializable
internal sealed class ProposalOperation {
    @Serializable
    @SerialName("CREATE_TRANSACTION")
    data class CreateTransaction(
        val localRef: String,
        override val direction: FinanceDirection,
        override val amountMinor: Long,
        override val accountId: String,
        override val accountVersion: Long,
        override val categoryId: String?,
        override val categoryVersion: Long?,
        override val memo: String,
        val time: ProposalTime,
    ) : ProposalOperation(), TransactionChange

    @Serializable
    @SerialName("UPDATE_TRANSACTION")
    data class UpdateTransaction(
        val id: String,
        val expectedVersion: Long,
        override val direction: FinanceDirection,
        override val amountMinor: Long,
        override val accountId: String,
        override val accountVersion: Long,
        override val categoryId: String?,
        override val categoryVersion: Long?,
        override val memo: String,
        val time: ProposalTime,
    ) : ProposalOperation(), TransactionChange

    @Serializable
    @SerialName("CREATE_NOTE")
    data class CreateNote(val localRef: String, val body: String, val time: ProposalTime) : ProposalOperation()

    @Serializable
    @SerialName("UPDATE_NOTE")
    data class UpdateNote(val id: String, val expectedVersion: Long, val body: String, val time: ProposalTime) : ProposalOperation()

    @Serializable
    @SerialName("LINK_RECORDS")
    data class LinkRecords(val pairs: List<RecordPair>) : ProposalOperation()

    @Serializable
    @SerialName("UNLINK_RECORDS")
    data class UnlinkRecords(val pairs: List<RecordPair>) : ProposalOperation()
}

internal interface TransactionChange {
    val direction: FinanceDirection
    val amountMinor: Long
    val accountId: String
    val accountVersion: Long
    val categoryId: String?
    val categoryVersion: Long?
    val memo: String
}

internal enum class ProposalState {
    EDITABLE, LOCKED, APPLIED, REJECTED_CONFLICT, REJECTED_INVALID, ABANDONED, NEEDS_REVIEW;

    val occupiesActiveSlot: Boolean get() = this == EDITABLE || this == LOCKED
}

internal enum class ProposalError {
    Validation, NotFound, NotVisible, VersionConflict, RevisionConflict, ProposalFrozen,
    CreationRequestConflict, ActiveProposalExists, RunInvalidated, GenerationMismatch,
    BudgetExceeded, RecoveryRequired, RecoverableIO, StorageFull,
}

internal class ProposalException(val code: ProposalError, cause: Throwable? = null) : Exception(code.name, cause)

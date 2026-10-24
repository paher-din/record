package com.paherdin.record2.proposals

import com.paherdin.record2.records.FinanceDirection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Persisted operation states. The names are the durable strings of the commit_operation state
 * column; only these four values exist on that table.
 */
internal enum class CommitStatus {
    Pending, Applied, RejectedConflict, RejectedInvalid;

    val isTerminal: Boolean get() = this != Pending
}

/**
 * A frozen operation is the persisted payload with every LOCAL reference replaced by the stable
 * identity it was assigned at confirmation time. Nothing in it is reinterpreted later: the
 * transaction/note ids, versions and the frozen time quadruple are executed exactly as stored.
 */
@Serializable
internal sealed class FrozenOperation {
    @Serializable
    @SerialName("CREATE_TRANSACTION")
    data class CreateTransaction(
        val id: String,
        override val direction: FinanceDirection,
        override val amountMinor: Long,
        override val accountId: String,
        override val accountVersion: Long,
        override val categoryId: String?,
        override val categoryVersion: Long?,
        override val memo: String,
        val time: ProposalTime,
    ) : FrozenOperation(), TransactionChange

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
    ) : FrozenOperation(), TransactionChange

    @Serializable
    @SerialName("CREATE_NOTE")
    data class CreateNote(val id: String, val body: String, val time: ProposalTime) : FrozenOperation()

    @Serializable
    @SerialName("UPDATE_NOTE")
    data class UpdateNote(val id: String, val expectedVersion: Long, val body: String, val time: ProposalTime) : FrozenOperation()

    @Serializable
    @SerialName("LINK_RECORDS")
    data class LinkRecords(val pairs: List<FrozenPair>) : FrozenOperation()

    @Serializable
    @SerialName("UNLINK_RECORDS")
    data class UnlinkRecords(val pairs: List<FrozenPair>) : FrozenOperation()
}

@Serializable
internal data class FrozenPair(
    val transactionId: String,
    val transactionVersion: Long,
    val noteId: String,
    val noteVersion: Long,
)

internal enum class CommitError {
    Validation, NotFound, NotVisible, VersionConflict, RevisionConflict, ConfirmationMismatch,
    ProposalFrozen, ActiveProposalExists, GenerationMismatch, IdOccupied, BudgetExceeded,
    RecoveryRequired, RecoverableIO, StorageFull, OutcomeUnknown,
}

internal class CommitException(val code: CommitError, cause: Throwable? = null) : Exception(code.name, cause)

/** The transaction committed but the receipt boundary failed; the durable state is authoritative. */
internal class CommitReceiptLossException(cause: Throwable) : Exception(CommitError.OutcomeUnknown.name, cause)

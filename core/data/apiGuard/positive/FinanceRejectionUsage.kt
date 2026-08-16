package consumer

import com.paherdin.record.core.data.FinanceViolation
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.RepositoryResult.Failure
import com.paherdin.record.core.data.RepositoryResult.Failure.CommittedButResponseFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.DuplicateIdentity
import com.paherdin.record.core.data.RepositoryResult.Failure.AttachmentCorrupted
import com.paherdin.record.core.data.RepositoryResult.Failure.AttachmentIoFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.AttachmentRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.BackupIoFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.BackupRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.EndpointRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.FinanceRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.RestoreIoFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.RestoreRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.StorageExhausted
import com.paherdin.record.core.data.RepositoryResult.Failure.TransactionFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.Unexpected
import com.paherdin.record.core.data.RepositoryResult.Failure.ValidationRejected
import com.paherdin.record.core.data.RepositoryResult.Success
import com.paherdin.record.model.TransactionType

/**
 * Positive consumer fixture: documents the sealed-source compatibility
 * obligation of the P2 addition. The new `FinanceRejected(violation)` direct
 * subtype is unpacked and an exhaustive `when` over every direct `Failure`
 * subtype recompiles with the new branch included.
 */
class FinanceRejectionUsage {
    suspend fun classify(repo: RecordRepository, tx: NewTransaction): String {
        val result: RepositoryResult<Unit> = repo.saveTransactionAggregate(tx)
        return when (result) {
            is Success -> "ok"
            is ValidationRejected -> "xor:${result.violation}"
            is FinanceRejected -> "finance:${result.violation}"
            is AttachmentRejected -> "attachment:${result.violation}"
            is AttachmentCorrupted -> "corrupt:${result.detail}"
            is AttachmentIoFailed -> "io:${result.stage}"
            is BackupRejected -> "backup:${result.violation}"
            is BackupIoFailed -> "backup-io:${result.stage}"
            is RestoreRejected -> "restore:${result.violation}"
            is RestoreIoFailed -> "restore-io:${result.stage}"
            is DuplicateIdentity -> "duplicate"
            is EndpointRejected -> "endpoint"
            is StorageExhausted -> "storage"
            is TransactionFailed -> "transaction"
            is CommittedButResponseFailed -> "commit-response"
            is Unexpected -> "unexpected"
        }
    }

    fun violations(): List<String> = FinanceViolation.entries.map { it.name }

    fun sameAccountRejection(): FinanceViolation = FinanceViolation.TRANSFER_SAME_ACCOUNT

    fun transferType(): TransactionType = TransactionType.TRANSFER
}

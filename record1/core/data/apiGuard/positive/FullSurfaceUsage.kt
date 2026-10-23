package consumer

import android.content.Context
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.RepositoryResult.Failure
import com.paherdin.record.core.data.RepositoryResult.Success
import com.paherdin.record.core.data.TransactionView
import com.paherdin.record.core.data.XorViolation
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType

/**
 * Positive consumer fixture: the documented public surface compiles —
 * factory, DTOs, typed results, enums, views.
 */
class FullSurfaceUsage {
    fun use(context: Context) {
        val result: RepositoryResult<RecordRepository> = RecordRepository.open(context)
        when (result) {
            is Success -> result.value.close()
            is Failure -> {}
        }
        val tx = NewTransaction(
            id = "id",
            type = TransactionType.INCOME,
            createdAt = 1L,
            updatedAt = 1L,
            deletedAt = null,
            entries = listOf(NewAccountEntry("e", "a", 1L)),
            relationNoteIds = setOf("n"),
        )
        val account = NewAccount("a", "name", "CNY")
        val note = NewNote("n", 1L, 1L, null, setOf("t"))
        val entityType = EntityType.TRANSACTION
        val transactionType = TransactionType.TRANSFER
        val violation = XorViolation.CROSS_TABLE_ID_COLLISION
        val view: TransactionView? = null
        check(view == null)
        check(tx.id.isNotEmpty())
        check(account.name.isNotEmpty() && note.id.isNotEmpty())
        check(entityType.name.isNotEmpty() && transactionType.name.isNotEmpty() && violation.name.isNotEmpty())
    }
}

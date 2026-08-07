package consumer

import android.content.Context
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult.Failure
import com.paherdin.record.core.data.RepositoryResult.Success
import com.paherdin.record.model.TransactionType

/**
 * Positive consumer fixture: unpacks `Success(repository)` from the public
 * `open`, calls methods and `close()` on it.
 */
class SuccessUnpacking {
    suspend fun unpack(context: Context) {
        val repo: RecordRepository = when (val result = RecordRepository.open(context)) {
            is Success -> result.value
            is Failure -> error("open failed: $result")
        }
        repo.createAccount(NewAccount("a", "name", "CNY"))
        repo.saveTransactionAggregate(
            NewTransaction("t", TransactionType.INCOME, 1L, 1L, null, listOf(NewAccountEntry("e", "a", 1L)), emptySet()),
        )
        repo.saveNoteAggregate(NewNote("n", 1L, 1L, null, setOf("t")))
        repo.replaceTransactionNoteRelations("t", setOf("n"))
        repo.getTransactionView("t")
        repo.getNoteView("n")
        repo.close()
    }
}

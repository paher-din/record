package com.paherdin.record.core.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RepositoryResult.Failure.EndpointRejected
import com.paherdin.record.core.data.RepositoryResult.Success
import com.paherdin.record.core.data.openForTest
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Criterion 4: `TransactionNoteRelation` accepts only existing, correctly
 * typed endpoints; complete-set replacement is one atomic operation. A
 * duplicate relation pair is not asserted — the public API takes `Set<String>`,
 * so duplicates are removed before the repository.
 */
@RunWith(AndroidJUnit4::class)
class TypedEndpointTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()

    @After
    fun tearDown() {
        names.forEach { context.deleteDatabase(it) }
    }

    private fun dbName(tag: String): String = "endpoint_$tag.db".also { names += it }

    private suspend fun setUp(repo: com.paherdin.record.core.data.RecordRepository) {
        assertEquals(Success(Unit), repo.createAccount(NewAccount("acc-1", "Wallet", "CNY")))
        assertEquals(Success(Unit), repo.saveNoteAggregate(NewNote("note-1", 1L, 1L, null, emptySet())))
        assertEquals(
            Success(Unit),
            repo.saveTransactionAggregate(
                NewTransaction("tx-1", TransactionType.INCOME, 1L, 1L, null, emptyList(), setOf("note-1")),
            ),
        )
    }

    @Test
    fun relationToNonexistentNoteIsRejected() = runBlocking {
        val name = dbName("missing_note")
        val repo = openForTest(context, name)
        setUp(repo)
        assertEquals(
            EndpointRejected,
            repo.saveTransactionAggregate(
                NewTransaction("tx-2", TransactionType.INCOME, 1L, 1L, null, emptyList(), setOf("no-such-note")),
            ),
        )
        // Nothing persisted.
        assertEquals(setOf("tx-1"), snapshot(name, context).transactions.map { it.id }.toSet())
        repo.close()
    }

    @Test
    fun relationToWronglyTypedEndpointIsRejected() = runBlocking {
        val name = dbName("wrong_type")
        val repo = openForTest(context, name)
        setUp(repo)
        // tx-1 is a Transaction, not a Note: wrong type for a note endpoint.
        assertEquals(
            EndpointRejected,
            repo.saveTransactionAggregate(
                NewTransaction("tx-2", TransactionType.INCOME, 1L, 1L, null, emptyList(), setOf("tx-1")),
            ),
        )
        // note-1 is a Note, not a Transaction: wrong type for a transaction endpoint.
        assertEquals(
            EndpointRejected,
            repo.saveNoteAggregate(NewNote("note-2", 1L, 1L, null, setOf("note-1"))),
        )
        assertEquals(setOf("tx-1"), snapshot(name, context).transactions.map { it.id }.toSet())
        assertEquals(setOf("note-1"), snapshot(name, context).notes.map { it.id }.toSet())
        repo.close()
    }

    @Test
    fun entryWithMissingAccountIsRejected() = runBlocking {
        val name = dbName("missing_account")
        val repo = openForTest(context, name)
        assertEquals(
            EndpointRejected,
            repo.saveTransactionAggregate(
                NewTransaction(
                    "tx-1",
                    TransactionType.INCOME,
                    1L,
                    1L,
                    null,
                    listOf(NewAccountEntry("entry-1", "no-such-account", 100L)),
                    emptySet(),
                ),
            ),
        )
        assertEquals(0, snapshot(name, context).accountEntries.size)
        repo.close()
    }

    @Test
    fun completeSetReplacementIsAtomic() = runBlocking {
        val name = dbName("replace_atomic")
        val repo = openForTest(context, name)
        setUp(repo)
        assertEquals(Success(Unit), repo.saveNoteAggregate(NewNote("note-2", 1L, 1L, null, emptySet())))

        // Old set {note-1} → replace with {note-2}: success, complete new set.
        assertEquals(Success(Unit), repo.replaceTransactionNoteRelations("tx-1", setOf("note-2")))
        assertEquals(setOf("note-2"), snapshot(name, context).relations.map { it.noteId }.toSet())

        // A rejected replacement leaves the old set fully intact (atomic).
        assertEquals(
            EndpointRejected,
            repo.replaceTransactionNoteRelations("tx-1", setOf("no-such-note")),
        )
        assertEquals(setOf("note-2"), snapshot(name, context).relations.map { it.noteId }.toSet())
        repo.close()
    }
}

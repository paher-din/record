package com.paherdin.record.core.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RepositoryResult.Failure.DuplicateIdentity
import com.paherdin.record.core.data.RepositoryResult.Success
import com.paherdin.record.core.data.TransactionView
import com.paherdin.record.core.data.openForTest
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Criterion 6: concurrent writes and relation-set replacement races. Every
 * observer sees a complete state — old or new — and never a mixed or partial
 * set; raw SQLite/Room exceptions never escape the repository (typed results
 * only). Sequences are fixed, not random.
 */
@RunWith(AndroidJUnit4::class)
class ConcurrencyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()

    @After
    fun tearDown() {
        names.forEach { context.deleteDatabase(it) }
    }

    private fun dbName(tag: String): String = "concurrency_$tag.db".also { names += it }

    @Test
    fun concurrentSameIdSavesYieldExactlyOneSuccessAndOneDuplicate() = runBlocking {
        val name = dbName("same_id")
        val repo = openForTest(context, name)
        val tx = NewTransaction("x", TransactionType.INCOME, 1L, 1L, null, emptyList(), emptySet())
        val results = (1..2).map { async { repo.saveTransactionAggregate(tx) } }.map { it.await() }
        assertEquals(1, results.count { it == Success(Unit) })
        assertEquals(1, results.count { it == DuplicateIdentity })
        // Final state equals the successful aggregate's complete state.
        assertEquals(
            Success(TransactionView("x", TransactionType.INCOME, 1L, 1L, null, emptyList(), emptySet())),
            repo.getTransactionView("x"),
        )
        val snap = snapshot(name, context)
        assertEquals(setOf(Transaction("x", TransactionType.INCOME)), snap.transactions)
        assertEquals(1, snap.entityRefs.size)
        repo.close()
    }

    @Test
    fun relationReplacementRacingReaderNeverObservesMixedSet() = runBlocking {
        val name = dbName("rel_race")
        val repo = openForTest(context, name)
        assertEquals(Success(Unit), repo.saveNoteAggregate(NewNote("n1", 1L, 1L, null, emptySet())))
        assertEquals(Success(Unit), repo.saveNoteAggregate(NewNote("n2", 1L, 1L, null, emptySet())))
        assertEquals(
            Success(Unit),
            repo.saveTransactionAggregate(
                NewTransaction("t1", TransactionType.INCOME, 1L, 1L, null, emptyList(), setOf("n1")),
            ),
        )

        // Reader on its own Room connection (WAL), never the writer's.
        val readerDao = Room.databaseBuilder(context, RecordDatabase::class.java, name).build().recordDao()

        val writer = async(Dispatchers.IO) {
            repeat(20) { i ->
                val target = if (i % 2 == 0) setOf("n1") else setOf("n2")
                assertEquals(Success(Unit), repo.replaceTransactionNoteRelations("t1", target))
            }
        }
        val reader = async {
            repeat(50) {
                val observed = readerDao.getRelationsForTransaction("t1").map { it.noteId }.toSet()
                check(observed == setOf("n1") || observed == setOf("n2")) {
                    "reader observed a mixed or partial set: $observed"
                }
            }
        }
        writer.await()
        reader.await()

        val finalSet = readerDao.getRelationsForTransaction("t1").map { it.noteId }.toSet()
        assertTrue(finalSet == setOf("n1") || finalSet == setOf("n2"))
        repo.close()
    }
}

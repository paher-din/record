package com.paherdin.record.core.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RepositoryResult.Failure.DuplicateIdentity
import com.paherdin.record.core.data.RepositoryResult.Success
import com.paherdin.record.core.data.openForTest
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Criterion 5 (spec 5.2): concurrent transfers. (a) Two concurrent saves of
 * the same transfer ID yield exactly one `Success` and one `DuplicateIdentity`
 * with the final state equal to the successful aggregate. (b) Two distinct
 * concurrent transfers `A`/`B` from a known initial state racing an
 * independent reader: every observation equals one of the four complete
 * snapshots — initial, only `A`, only `B`, or both — via the atomic
 * single-transaction read (`consistentSnapshot`), never a torn mix and never
 * one side of either transfer. Raw SQLite/Room exceptions never escape.
 */
@RunWith(AndroidJUnit4::class)
class TransferConcurrencyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()

    @After
    fun tearDown() {
        names.forEach { context.deleteDatabase(it) }
    }

    private fun dbName(tag: String): String = "transfer_concurrency_$tag.db".also { names += it }

    private suspend fun setUpAccounts(repo: com.paherdin.record.core.data.RecordRepository) {
        assertEquals(Success(Unit), repo.createAccount(NewAccount("acc-1", "Wallet", "CNY")))
        assertEquals(Success(Unit), repo.createAccount(NewAccount("acc-2", "Savings", "CNY")))
    }

    private fun transfer(id: String, magnitude: Long, entryPrefix: String) = NewTransaction(
        id = id,
        type = TransactionType.TRANSFER,
        createdAt = 1L,
        updatedAt = 1L,
        deletedAt = null,
        entries = listOf(
            NewAccountEntry("$entryPrefix-src", "acc-1", -magnitude),
            NewAccountEntry("$entryPrefix-dst", "acc-2", magnitude),
        ),
        relationNoteIds = emptySet(),
    )

    private fun initialSnapshot() = FullSnapshot(
        entityRefs = emptySet(),
        transactions = emptySet(),
        accounts = setOf(Account("acc-1", "Wallet", "CNY"), Account("acc-2", "Savings", "CNY")),
        accountEntries = emptySet(),
        notes = emptySet(),
        relations = emptySet(),
    )

    private fun withTransfer(base: FullSnapshot, id: String, magnitude: Long, entryPrefix: String) = FullSnapshot(
        entityRefs = base.entityRefs + setOf(EntityRef(id, EntityType.TRANSACTION, 1L, 1L, null)),
        transactions = base.transactions + setOf(Transaction(id, TransactionType.TRANSFER)),
        accounts = base.accounts,
        accountEntries = base.accountEntries + setOf(
            AccountEntry("$entryPrefix-src", id, "acc-1", -magnitude),
            AccountEntry("$entryPrefix-dst", id, "acc-2", magnitude),
        ),
        notes = base.notes,
        relations = base.relations,
    )

    @Test
    fun concurrentSameIdTransfersYieldExactlyOneSuccessAndOneDuplicate() = runBlocking {
        val name = dbName("same_id")
        val repo = openForTest(context, name)
        setUpAccounts(repo)
        val tx = transfer("t1", 50L, "e1")

        val results = (1..2).map { async { repo.saveTransactionAggregate(tx) } }.map { it.await() }
        assertEquals(1, results.count { it == Success(Unit) })
        assertEquals(1, results.count { it == DuplicateIdentity })

        // Final state equals the successful aggregate's complete state.
        val expected = withTransfer(initialSnapshot(), "t1", 50L, "e1")
        assertEquals(expected, consistentSnapshot(name, context))
        assertEquals(
            Success(com.paherdin.record.core.data.TransactionView("t1", TransactionType.TRANSFER, 1L, 1L, null, tx.entries, emptySet())),
            repo.getTransactionView("t1"),
        )
        repo.close()
    }

    @Test
    fun distinctConcurrentTransfersRaceReaderSeesOnlyCompleteSnapshots() = runBlocking {
        val name = dbName("distinct_race")
        val repo = openForTest(context, name)
        setUpAccounts(repo)

        val transferA = transfer("t-A", 50L, "eA")
        val transferB = transfer("t-B", 30L, "eB")
        val initial = initialSnapshot()
        val onlyA = withTransfer(initial, "t-A", 50L, "eA")
        val onlyB = withTransfer(initial, "t-B", 30L, "eB")
        val both = withTransfer(onlyA, "t-B", 30L, "eB")
        val allowed = setOf(initial, onlyA, onlyB, both)

        val writerA = async(Dispatchers.IO) { repo.saveTransactionAggregate(transferA) }
        val writerB = async(Dispatchers.IO) { repo.saveTransactionAggregate(transferB) }
        val reader = async {
            repeat(60) {
                val observed = consistentSnapshot(name, context)
                check(observed in allowed) { "reader observed a disallowed snapshot: $observed" }
            }
        }
        val outcomes = listOf(writerA.await(), writerB.await())
        reader.await()

        assertEquals(listOf(Success(Unit), Success(Unit)), outcomes)
        // Both distinct transfers commit; the final state is the complete union.
        val finalObserved = consistentSnapshot(name, context)
        assertEquals(both, finalObserved)
        assertEquals(both.effectiveEntrySum("acc-1"), finalObserved.effectiveEntrySum("acc-1"))
        assertEquals(both.effectiveEntrySum("acc-2"), finalObserved.effectiveEntrySum("acc-2"))
        repo.close()
    }
}

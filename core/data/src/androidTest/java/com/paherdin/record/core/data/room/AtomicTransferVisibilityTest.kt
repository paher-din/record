package com.paherdin.record.core.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.RepositoryResult.Failure.CommittedButResponseFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.TransactionFailed
import com.paherdin.record.core.data.RepositoryResult.Success
import com.paherdin.record.core.data.openForTest
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger

/**
 * Criterion 3 (spec 5.2): the transfer aggregate (transaction, both entries,
 * relations) commits as one indivisible transaction. Failure injected at every
 * hook stage/ordinal — before the transaction; after `EntityRef` (0); after
 * `Transaction` (1); after the negative source entry before the positive
 * destination entry (2); after the destination (3); after the relation stage
 * (4); before commit; and after the commit has succeeded. The external reader
 * observes only the old or the new complete state — never one side of the
 * transfer — with both accounts' entry histories and `BigInteger` balances
 * compared exactly. The write deliberately passes the entries destination
 * first to exercise the deterministic ascending sort; the source-first order
 * itself is enforced by that sort (the fault matrix observes only rolled-back
 * or committed pairs, so the intermediate order is not directly observable).
 */
@RunWith(AndroidJUnit4::class)
class AtomicTransferVisibilityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()

    @After
    fun tearDown() {
        names.forEach { context.deleteDatabase(it) }
    }

    private fun dbName(tag: String): String = "atomic_transfer_$tag.db".also { names += it }

    private fun oldSnapshot() = FullSnapshot(
        entityRefs = setOf(EntityRef("note-1", EntityType.NOTE, 1L, 1L, null)),
        transactions = emptySet(),
        accounts = setOf(Account("acc-1", "Wallet", "CNY"), Account("acc-2", "Savings", "CNY")),
        accountEntries = emptySet(),
        notes = setOf(Note("note-1")),
        relations = emptySet(),
    )

    private fun newSnapshot() = FullSnapshot(
        entityRefs = oldSnapshot().entityRefs + setOf(EntityRef("tx-1", EntityType.TRANSACTION, 100L, 200L, null)),
        transactions = setOf(Transaction("tx-1", TransactionType.TRANSFER)),
        accounts = oldSnapshot().accounts,
        accountEntries = setOf(
            AccountEntry("e-src", "tx-1", "acc-1", -50L),
            AccountEntry("e-dst", "tx-1", "acc-2", 50L),
        ),
        notes = setOf(Note("note-1")),
        relations = setOf(TransactionNoteRelation("tx-1", "note-1")),
    )

    private val setup: suspend (RecordRepository) -> Unit = {
        it.createAccount(NewAccount("acc-1", "Wallet", "CNY"))
        it.createAccount(NewAccount("acc-2", "Savings", "CNY"))
        it.saveNoteAggregate(NewNote("note-1", 1L, 1L, null, emptySet()))
    }

    /** Destination entry listed first in the input: the repository must write source first. */
    private val write: suspend (RecordRepository) -> RepositoryResult<Unit> = {
        it.saveTransactionAggregate(
            NewTransaction(
                id = "tx-1",
                type = TransactionType.TRANSFER,
                createdAt = 100L,
                updatedAt = 200L,
                deletedAt = null,
                entries = listOf(
                    NewAccountEntry("e-dst", "acc-2", 50L),
                    NewAccountEntry("e-src", "acc-1", -50L),
                ),
                relationNoteIds = setOf("note-1"),
            ),
        )
    }

    @Test
    fun transferFaultMatrixNeverExposesOneSidedEffect() = runBlocking {
        val oldSnap = oldSnapshot()
        val newSnap = newSnapshot()
        val faulted = TransactionFailed
        val cases = listOf(
            LatchFaultHook.Injection(FaultStage.BEFORE_TRANSACTION) to faulted,
            LatchFaultHook.Injection(FaultStage.CHILD, 0) to faulted, // after EntityRef
            LatchFaultHook.Injection(FaultStage.CHILD, 1) to faulted, // after Transaction
            LatchFaultHook.Injection(FaultStage.CHILD, 2) to faulted, // after source, before destination
            LatchFaultHook.Injection(FaultStage.CHILD, 3) to faulted, // after destination
            LatchFaultHook.Injection(FaultStage.CHILD, 4) to faulted, // after relation stage
            LatchFaultHook.Injection(FaultStage.BEFORE_COMMIT) to faulted,
            LatchFaultHook.Injection(FaultStage.AFTER_COMMIT_RESPONSE) to CommittedButResponseFailed,
            null to Success(Unit),
        )
        cases.forEachIndexed { i, (injection, expectedOutcome) ->
            val name = dbName("matrix_$i")

            // Durable old complete state through the public API.
            val setupRepo = openForTest(context, name)
            setup(setupRepo)
            setupRepo.close()
            assertEquals(oldSnap, snapshot(name, context))

            val hook = LatchFaultHook(injection)
            val writerRepo = openForTest(context, name, hook)
            val writer = coroutineScope { async(Dispatchers.IO) { write(writerRepo) } }

            if (injection != null) {
                hook.awaitEntered()
                val pausedExpected =
                    if (injection.stage == FaultStage.AFTER_COMMIT_RESPONSE) newSnap else oldSnap
                repeat(3) {
                    val observed = snapshot(name, context)
                    assertEquals(pausedExpected, observed)
                    assertBalances(observed, pausedExpected)
                }
                hook.release()
            }

            val outcome = writer.await()
            assertEquals(expectedOutcome, outcome)
            val finalExpected =
                if (injection == null || injection.stage == FaultStage.AFTER_COMMIT_RESPONSE) newSnap else oldSnap
            val finalObserved = snapshot(name, context)
            assertEquals(finalExpected, finalObserved)
            assertBalances(finalObserved, finalExpected)
            writerRepo.close()
        }
    }

    private fun assertBalances(observed: FullSnapshot, expected: FullSnapshot) {
        for (accountId in listOf("acc-1", "acc-2")) {
            assertEquals(
                "balance drift on $accountId (observed=$observed)",
                expected.effectiveEntrySum(accountId),
                observed.effectiveEntrySum(accountId),
            )
        }
        // The transfer itself is exactly zero-sum in any observed complete state.
        observed.accountEntries
            .filter { observed.transactions.any { t -> t.id == it.transactionId && t.type == TransactionType.TRANSFER } }
            .fold(BigInteger.ZERO) { acc, e -> acc.add(e.amountMinor.toBigInteger()) }
            .let { assertEquals(BigInteger.ZERO, it) }
    }
}

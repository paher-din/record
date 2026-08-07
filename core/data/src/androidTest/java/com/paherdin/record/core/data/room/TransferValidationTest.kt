package com.paherdin.record.core.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.FinanceViolation
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult.Failure.DuplicateIdentity
import com.paherdin.record.core.data.RepositoryResult.Failure.EndpointRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.FinanceRejected
import com.paherdin.record.core.data.RepositoryResult.Success
import com.paherdin.record.core.data.openForTest
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Criterion 2 + repository-path part of criterion 1 (spec 5.2): typed
 * endpoint/currency/finance rejections through the public surface with no
 * partial state, deterministic cross-family precedence, and the persisted
 * effective-entry exclusion (no public soft-delete transition is claimed).
 */
@RunWith(AndroidJUnit4::class)
class TransferValidationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()

    @After
    fun tearDown() {
        names.forEach { context.deleteDatabase(it) }
    }

    private fun dbName(tag: String): String = "transfer_validation_$tag.db".also { names += it }

    private suspend fun setUpAccounts(repo: RecordRepository) {
        assertEquals(Success(Unit), repo.createAccount(NewAccount("acc-1", "Wallet", "CNY")))
        assertEquals(Success(Unit), repo.createAccount(NewAccount("acc-2", "Savings", "CNY")))
        assertEquals(Success(Unit), repo.createAccount(NewAccount("acc-3", "Dollar", "USD")))
    }

    private fun transfer(
        id: String,
        entries: List<NewAccountEntry>,
        relationNoteIds: Set<String> = emptySet(),
        type: TransactionType = TransactionType.TRANSFER,
    ) = NewTransaction(id, type, 1L, 1L, null, entries, relationNoteIds)

    private fun entry(id: String, accountId: String, amountMinor: Long) =
        NewAccountEntry(id, accountId, amountMinor)

    @Test
    fun entryWithMissingAccountEndpointIsRejected() = runBlocking {
        val name = dbName("missing_account")
        val repo = openForTest(context, name)
        setUpAccounts(repo)
        assertEquals(
            EndpointRejected,
            repo.saveTransactionAggregate(
                transfer("t1", listOf(entry("e1", "no-such-account", -50L), entry("e2", "acc-2", 50L))),
            ),
        )
        // The rejected aggregate leaves no trace: setup accounts remain, no transaction side.
        val snap = snapshot(name, context)
        assertEquals(setOf("acc-1", "acc-2", "acc-3"), snap.accounts.map { it.id }.toSet())
        assertEquals(emptySet<EntityRef>(), snap.entityRefs)
        assertEquals(emptySet<Transaction>(), snap.transactions)
        assertEquals(emptySet<AccountEntry>(), snap.accountEntries)
        assertEquals(emptySet<TransactionNoteRelation>(), snap.relations)
        repo.close()
    }

    @Test
    fun entryAccountIdOccupiedOnlyByAnotherEntityTypeIsRejected() = runBlocking {
        val name = dbName("wrongly_typed_account")
        val repo = openForTest(context, name)
        setUpAccounts(repo)
        // "n1" is a Note identity; no Account "n1" exists — a wrongly typed account endpoint.
        assertEquals(Success(Unit), repo.saveNoteAggregate(NewNote("n1", 1L, 1L, null, emptySet())))
        assertEquals(
            EndpointRejected,
            repo.saveTransactionAggregate(
                transfer("t1", listOf(entry("e1", "n1", -50L), entry("e2", "acc-2", 50L))),
            ),
        )
        assertEquals(emptySet<AccountEntry>(), snapshot(name, context).accountEntries)
        repo.close()
    }

    @Test
    fun sameAccountTransferIsRejected() = runBlocking {
        val name = dbName("same_account")
        val repo = openForTest(context, name)
        setUpAccounts(repo)
        assertEquals(
            FinanceRejected(FinanceViolation.TRANSFER_SAME_ACCOUNT),
            repo.saveTransactionAggregate(
                transfer("t1", listOf(entry("e1", "acc-1", -50L), entry("e2", "acc-1", 50L))),
            ),
        )
        assertEquals(emptySet<AccountEntry>(), snapshot(name, context).accountEntries)
        repo.close()
    }

    @Test
    fun differingCurrencyTransferIsRejected() = runBlocking {
        val name = dbName("currency_mismatch")
        val repo = openForTest(context, name)
        setUpAccounts(repo)
        assertEquals(
            FinanceRejected(FinanceViolation.TRANSFER_CURRENCY_MISMATCH),
            repo.saveTransactionAggregate(
                transfer("t1", listOf(entry("e1", "acc-1", -50L), entry("e2", "acc-3", 50L))),
            ),
        )
        assertEquals(emptySet<AccountEntry>(), snapshot(name, context).accountEntries)
        repo.close()
    }

    @Test
    fun financeMatrixViolationsAreRejectedThroughTheRepository() = runBlocking {
        val name = dbName("matrix")
        val repo = openForTest(context, name)
        setUpAccounts(repo)
        assertEquals(
            FinanceRejected(FinanceViolation.ENTRY_CARDINALITY),
            repo.saveTransactionAggregate(transfer("t1", listOf(entry("e1", "acc-1", -50L)))),
        )
        assertEquals(
            FinanceRejected(FinanceViolation.ZERO_AMOUNT),
            repo.saveTransactionAggregate(
                transfer("t2", listOf(entry("e1", "acc-1", 0L), entry("e2", "acc-2", 50L))),
            ),
        )
        assertEquals(
            FinanceRejected(FinanceViolation.WRONG_SIGN),
            repo.saveTransactionAggregate(
                transfer("t3", listOf(entry("e1", "acc-1", 50L), entry("e2", "acc-2", 50L))),
            ),
        )
        assertEquals(
            FinanceRejected(FinanceViolation.TRANSFER_NOT_ZERO_SUM),
            repo.saveTransactionAggregate(
                transfer("t4", listOf(entry("e1", "acc-1", -40L), entry("e2", "acc-2", 50L))),
            ),
        )
        assertEquals(
            FinanceRejected(FinanceViolation.ENTRY_CARDINALITY),
            repo.saveTransactionAggregate(
                transfer("t5", listOf(entry("e1", "acc-1", -50L), entry("e2", "acc-2", 50L), entry("e3", "acc-3", 0L))),
            ),
        )
        // Nothing persisted by any rejected candidate.
        assertEquals(emptySet<AccountEntry>(), snapshot(name, context).accountEntries)
        assertEquals(emptySet<Transaction>(), snapshot(name, context).transactions)
        repo.close()
    }

    @Test
    fun crossFamilyPrecedenceIsDeterministic() = runBlocking {
        val name = dbName("precedence")
        val repo = openForTest(context, name)
        setUpAccounts(repo)
        val valid = transfer(
            "x", listOf(entry("e1", "acc-1", -50L), entry("e2", "acc-2", 50L)),
        )
        assertEquals(Success(Unit), repo.saveTransactionAggregate(valid))

        // Same-ID duplicate + invalid finance → DuplicateIdentity (duplicate precedes finance).
        assertEquals(
            DuplicateIdentity,
            repo.saveTransactionAggregate(
                transfer("x", listOf(entry("e3", "acc-1", 0L), entry("e4", "acc-2", 0L))),
            ),
        )

        // Missing account endpoint + cardinality violation → EndpointRejected (endpoints precede finance).
        assertEquals(
            EndpointRejected,
            repo.saveTransactionAggregate(
                NewTransaction(
                    "y", TransactionType.INCOME, 1L, 1L, null,
                    listOf(entry("e5", "no-such-account", 5L), entry("e6", "acc-2", 5L)), emptySet(),
                ),
            ),
        )

        // Valid endpoints + finance violation + missing relation endpoint → FinanceRejected.
        assertEquals(
            FinanceRejected(FinanceViolation.TRANSFER_NOT_ZERO_SUM),
            repo.saveTransactionAggregate(
                transfer("z", listOf(entry("e7", "acc-1", -40L), entry("e8", "acc-2", 50L)), setOf("no-such-note")),
            ),
        )

        // Only the valid transfer persisted; every rejected candidate left no trace.
        val snap = snapshot(name, context)
        assertEquals(setOf("x"), snap.transactions.map { it.id }.toSet())
        assertEquals(setOf("e1", "e2"), snap.accountEntries.map { it.id }.toSet())
        assertEquals(emptySet<TransactionNoteRelation>(), snap.relations)
        repo.close()
    }

    @Test
    fun persistedSoftDeletedAggregateContributesNoEffectiveEntry() = runBlocking {
        val name = dbName("effective_exclusion")
        val repo = openForTest(context, name)
        setUpAccounts(repo)
        // An aggregate whose deletedAt is already present, through the typed surface.
        assertEquals(
            Success(Unit),
            repo.saveTransactionAggregate(
                NewTransaction(
                    "t1", TransactionType.INCOME, 1L, 1L, deletedAt = 5000L,
                    entries = listOf(entry("e1", "acc-1", 100L)), relationNoteIds = emptySet(),
                ),
            ),
        )
        assertEquals(
            Success(Unit),
            repo.saveTransactionAggregate(
                NewTransaction(
                    "t2", TransactionType.INCOME, 1L, 1L, deletedAt = null,
                    entries = listOf(entry("e2", "acc-1", 200L)), relationNoteIds = emptySet(),
                ),
            ),
        )
        val snap = snapshot(name, context)
        // Both entries exist physically…
        assertEquals(setOf("e1", "e2"), snap.accountEntries.map { it.id }.toSet())
        // …but only t2's entry is effective for the balance.
        assertEquals(java.math.BigInteger.valueOf(200L), snap.effectiveEntrySum("acc-1"))
        repo.close()
    }
}

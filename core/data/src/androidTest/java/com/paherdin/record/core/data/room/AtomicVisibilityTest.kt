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

/**
 * Criterion 5: atomic aggregate visibility under injected failure at every
 * stage/ordinal, with latch-paused writers and an external reader on its own
 * connection (Room WAL). Expected state matrix per the plan:
 *
 * | Injection | While paused | After completion | Result |
 * |---|---|---|---|
 * | beforeTransaction | old | old | TransactionFailed, nothing persisted |
 * | afterChildWrite(ordinal) | old | old | TransactionFailed, nothing persisted |
 * | beforeCommit | old | old | TransactionFailed, nothing persisted |
 * | afterCommitResponse | new | new | CommittedButResponseFailed, data persisted |
 * | no fault | — | new | Success |
 */
@RunWith(AndroidJUnit4::class)
class AtomicVisibilityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()

    @After
    fun tearDown() {
        names.forEach { context.deleteDatabase(it) }
    }

    private fun dbName(tag: String): String = "atomic_$tag.db".also { names += it }

    private suspend fun runMatrix(
        tag: String,
        oldSnapshot: FullSnapshot,
        setup: suspend (RecordRepository) -> Unit,
        newSnapshot: FullSnapshot,
        write: suspend (RecordRepository) -> RepositoryResult<Unit>,
        injection: LatchFaultHook.Injection?,
        expectedOutcome: RepositoryResult<Unit>,
    ) {
        val name = dbName(tag)

        // Durable old complete state, built through the public API.
        val setupRepo = openForTest(context, name)
        setup(setupRepo)
        setupRepo.close()
        assertEquals(oldSnapshot, snapshot(name, context))

        val hook = LatchFaultHook(injection)
        val writerRepo = openForTest(context, name, hook)
        val writer = coroutineScope { async(Dispatchers.IO) { write(writerRepo) } }

        if (injection != null) {
            hook.awaitEntered()
            // External reader on its own connection takes several snapshots
            // while the writer is paused; every one equals the matrix state.
            val pausedExpected = if (injection.stage == FaultStage.AFTER_COMMIT_RESPONSE) newSnapshot else oldSnapshot
            repeat(3) { assertEquals(pausedExpected, snapshot(name, context)) }
            hook.release()
        }

        val outcome = writer.await()
        assertEquals(expectedOutcome, outcome)
        val finalExpected = if (injection == null || injection.stage == FaultStage.AFTER_COMMIT_RESPONSE) newSnapshot else oldSnapshot
        assertEquals(finalExpected, snapshot(name, context))
        writerRepo.close()
    }

    @Test
    fun saveTransactionAggregateFaultMatrix() = runBlocking {
        val oldSnap = FullSnapshot(
            entityRefs = setOf(EntityRef("note-1", EntityType.NOTE, 1L, 1L, null)),
            accounts = setOf(Account("acc-1", "Wallet", "CNY")),
            notes = setOf(Note("note-1")),
            transactions = emptySet(),
            accountEntries = emptySet(),
            relations = emptySet(),
        )
        val newSnap = FullSnapshot(
            entityRefs = oldSnap.entityRefs + setOf(EntityRef("tx-1", EntityType.TRANSACTION, 100L, 200L, null)),
            accounts = oldSnap.accounts,
            notes = oldSnap.notes,
            transactions = setOf(Transaction("tx-1", TransactionType.EXPENSE)),
            accountEntries = setOf(AccountEntry("entry-1", "tx-1", "acc-1", -50L)),
            relations = setOf(TransactionNoteRelation("tx-1", "note-1")),
        )
        val write: suspend (RecordRepository) -> RepositoryResult<Unit> = {
            it.saveTransactionAggregate(
                NewTransaction(
                    id = "tx-1",
                    type = TransactionType.EXPENSE,
                    createdAt = 100L,
                    updatedAt = 200L,
                    deletedAt = null,
                    entries = listOf(NewAccountEntry("entry-1", "acc-1", -50L)),
                    relationNoteIds = setOf("note-1"),
                ),
            )
        }
        val setup: suspend (RecordRepository) -> Unit = {
            it.createAccount(NewAccount("acc-1", "Wallet", "CNY"))
            it.saveNoteAggregate(NewNote("note-1", 1L, 1L, null, emptySet()))
        }
        val faulted = TransactionFailed
        val cases = listOf(
            LatchFaultHook.Injection(FaultStage.BEFORE_TRANSACTION) to faulted,
            LatchFaultHook.Injection(FaultStage.CHILD, 0) to faulted,
            LatchFaultHook.Injection(FaultStage.CHILD, 1) to faulted,
            LatchFaultHook.Injection(FaultStage.CHILD, 2) to faulted,
            LatchFaultHook.Injection(FaultStage.CHILD, 3) to faulted,
            LatchFaultHook.Injection(FaultStage.BEFORE_COMMIT) to faulted,
            LatchFaultHook.Injection(FaultStage.AFTER_COMMIT_RESPONSE) to CommittedButResponseFailed,
            null to Success(Unit),
        )
        cases.forEachIndexed { i, (injection, expected) ->
            runMatrix(
                tag = "tx_matrix_$i",
                oldSnapshot = oldSnap,
                setup = setup,
                newSnapshot = newSnap,
                write = write,
                injection = injection,
                expectedOutcome = expected,
            )
        }
    }

    @Test
    fun saveNoteAggregateFaultMatrix() = runBlocking {
        val oldSnap = FullSnapshot(
            entityRefs = setOf(EntityRef("tx-1", EntityType.TRANSACTION, 1L, 1L, null)),
            transactions = setOf(Transaction("tx-1", TransactionType.INCOME)),
            accounts = setOf(Account("acc-1", "Wallet", "CNY")),
            accountEntries = setOf(AccountEntry("entry-1", "tx-1", "acc-1", 50L)),
            notes = emptySet(),
            relations = emptySet(),
        )
        val newSnap = FullSnapshot(
            entityRefs = oldSnap.entityRefs + setOf(EntityRef("note-1", EntityType.NOTE, 100L, 200L, null)),
            transactions = oldSnap.transactions,
            accounts = oldSnap.accounts,
            accountEntries = oldSnap.accountEntries,
            notes = setOf(Note("note-1")),
            relations = setOf(TransactionNoteRelation("tx-1", "note-1")),
        )
        val setup: suspend (RecordRepository) -> Unit = {
            it.createAccount(NewAccount("acc-1", "Wallet", "CNY"))
            it.saveTransactionAggregate(
                NewTransaction(
                    "tx-1", TransactionType.INCOME, 1L, 1L, null,
                    listOf(NewAccountEntry("entry-1", "acc-1", 50L)), emptySet(),
                ),
            )
        }
        val write: suspend (RecordRepository) -> RepositoryResult<Unit> = {
            it.saveNoteAggregate(NewNote("note-1", 100L, 200L, null, setOf("tx-1")))
        }
        val faulted = TransactionFailed
        val cases = listOf(
            LatchFaultHook.Injection(FaultStage.BEFORE_TRANSACTION) to faulted,
            LatchFaultHook.Injection(FaultStage.CHILD, 0) to faulted,
            LatchFaultHook.Injection(FaultStage.CHILD, 1) to faulted,
            LatchFaultHook.Injection(FaultStage.CHILD, 2) to faulted,
            LatchFaultHook.Injection(FaultStage.BEFORE_COMMIT) to faulted,
            LatchFaultHook.Injection(FaultStage.AFTER_COMMIT_RESPONSE) to CommittedButResponseFailed,
            null to Success(Unit),
        )
        cases.forEachIndexed { i, (injection, expected) ->
            runMatrix(
                tag = "note_matrix_$i",
                oldSnapshot = oldSnap,
                setup = setup,
                newSnapshot = newSnap,
                write = write,
                injection = injection,
                expectedOutcome = expected,
            )
        }
    }

    @Test
    fun replaceTransactionNoteRelationsFaultMatrix() = runBlocking {
        val oldSnap = FullSnapshot(
            entityRefs = setOf(
                EntityRef("tx-1", EntityType.TRANSACTION, 1L, 1L, null),
                EntityRef("note-1", EntityType.NOTE, 1L, 1L, null),
                EntityRef("note-2", EntityType.NOTE, 1L, 1L, null),
            ),
            transactions = setOf(Transaction("tx-1", TransactionType.INCOME)),
            accounts = setOf(Account("acc-1", "Wallet", "CNY")),
            accountEntries = setOf(AccountEntry("entry-1", "tx-1", "acc-1", 50L)),
            notes = setOf(Note("note-1"), Note("note-2")),
            relations = setOf(TransactionNoteRelation("tx-1", "note-1")),
        )
        val newSnap = oldSnap.copy(relations = setOf(TransactionNoteRelation("tx-1", "note-2")))
        val setup: suspend (RecordRepository) -> Unit = {
            it.createAccount(NewAccount("acc-1", "Wallet", "CNY"))
            it.saveNoteAggregate(NewNote("note-1", 1L, 1L, null, emptySet()))
            it.saveNoteAggregate(NewNote("note-2", 1L, 1L, null, emptySet()))
            it.saveTransactionAggregate(
                NewTransaction(
                    "tx-1", TransactionType.INCOME, 1L, 1L, null,
                    listOf(NewAccountEntry("entry-1", "acc-1", 50L)), setOf("note-1"),
                ),
            )
        }
        val write: suspend (RecordRepository) -> RepositoryResult<Unit> = {
            it.replaceTransactionNoteRelations("tx-1", setOf("note-2"))
        }
        val faulted = TransactionFailed
        val cases = listOf(
            LatchFaultHook.Injection(FaultStage.BEFORE_TRANSACTION) to faulted,
            LatchFaultHook.Injection(FaultStage.CHILD, 0) to faulted,
            LatchFaultHook.Injection(FaultStage.CHILD, 1) to faulted,
            LatchFaultHook.Injection(FaultStage.BEFORE_COMMIT) to faulted,
            LatchFaultHook.Injection(FaultStage.AFTER_COMMIT_RESPONSE) to CommittedButResponseFailed,
            null to Success(Unit),
        )
        cases.forEachIndexed { i, (injection, expected) ->
            runMatrix(
                tag = "replace_matrix_$i",
                oldSnapshot = oldSnap,
                setup = setup,
                newSnapshot = newSnap,
                write = write,
                injection = injection,
                expectedOutcome = expected,
            )
        }
    }
}

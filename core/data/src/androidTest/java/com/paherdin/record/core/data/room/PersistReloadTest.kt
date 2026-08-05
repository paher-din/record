package com.paherdin.record.core.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.NoteView
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult.Failure.Unexpected
import com.paherdin.record.core.data.RepositoryResult.Success
import com.paherdin.record.core.data.TransactionFaultHook
import com.paherdin.record.core.data.TransactionView
import com.paherdin.record.core.data.openForTest
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.cancellation.CancellationException

/**
 * Criterion 1 (persist and reload) plus the typed `open()` boundary mapping:
 * one `openForTest(context, dbName)` instance, public writes, `close()`,
 * reopen with the same `dbName`, public read views + internal read-only
 * snapshot; and the open boundary cases (a) public `open` → `Success` +
 * `close()`, (b) builder `IllegalArgumentException` → `Unexpected`,
 * (c) builder `CancellationException` rethrown.
 */
@RunWith(AndroidJUnit4::class)
class PersistReloadTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()

    @After
    fun tearDown() {
        names.forEach { context.deleteDatabase(it) }
        context.deleteDatabase("record.db")
    }

    private fun dbName(tag: String): String = "persist_reload_$tag.db".also { names += it }

    @Test
    fun minimalFactsRoundTripThroughPublicApi() = runBlocking {
        val name = dbName("roundtrip")

        val repo = openForTest(context, name)
        assertEquals(Success(Unit), repo.createAccount(NewAccount("acc-1", "Wallet", "CNY")))
        assertEquals(
            Success(Unit),
            repo.saveNoteAggregate(NewNote(id = "note-1", createdAt = 1000L, updatedAt = 2000L, deletedAt = null, relationTransactionIds = emptySet())),
        )
        assertEquals(
            Success(Unit),
            repo.saveTransactionAggregate(
                NewTransaction(
                    id = "tx-1",
                    type = TransactionType.EXPENSE,
                    createdAt = 3000L,
                    updatedAt = 4000L,
                    deletedAt = 5000L,
                    entries = listOf(NewAccountEntry("entry-1", "acc-1", -12345L)),
                    relationNoteIds = setOf("note-1"),
                ),
            ),
        )
        assertEquals(
            Success(Unit),
            repo.saveTransactionAggregate(
                NewTransaction(id = "tx-2", type = TransactionType.INCOME, createdAt = 6000L, updatedAt = 6000L, deletedAt = null, entries = emptyList(), relationNoteIds = emptySet()),
            ),
        )
        assertEquals(Success(Unit), repo.replaceTransactionNoteRelations("tx-1", setOf("note-1")))
        repo.close()

        // Reopen the same dbName: public read views.
        val reopened = openForTest(context, name)
        assertEquals(
            Success(
                TransactionView(
                    id = "tx-1",
                    type = TransactionType.EXPENSE,
                    createdAt = 3000L,
                    updatedAt = 4000L,
                    deletedAt = 5000L,
                    entries = listOf(NewAccountEntry("entry-1", "acc-1", -12345L)),
                    relationNoteIds = setOf("note-1"),
                ),
            ),
            reopened.getTransactionView("tx-1"),
        )
        assertEquals(
            Success(TransactionView("tx-2", TransactionType.INCOME, 6000L, 6000L, null, emptyList(), emptySet())),
            reopened.getTransactionView("tx-2"),
        )
        assertEquals(
            Success(NoteView("note-1", 1000L, 2000L, null, setOf("tx-1"))),
            reopened.getNoteView("note-1"),
        )
        assertEquals(Success(null), reopened.getTransactionView("missing"))
        assertEquals(Success(null), reopened.getNoteView("missing"))

        // Internal read-only snapshot of all six physical tables.
        val db = Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
        try {
            assertEquals(
                setOf(
                    EntityRef("tx-1", EntityType.TRANSACTION, 3000L, 4000L, 5000L),
                    EntityRef("tx-2", EntityType.TRANSACTION, 6000L, 6000L, null),
                    EntityRef("note-1", EntityType.NOTE, 1000L, 2000L, null),
                ),
                db.recordDao().allEntityRefs().toSet(),
            )
            assertEquals(
                setOf(Transaction("tx-1", TransactionType.EXPENSE), Transaction("tx-2", TransactionType.INCOME)),
                db.recordDao().allTransactions().toSet(),
            )
            assertEquals(setOf(Account("acc-1", "Wallet", "CNY")), db.recordDao().allAccounts().toSet())
            assertEquals(setOf(AccountEntry("entry-1", "tx-1", "acc-1", -12345L)), db.recordDao().allAccountEntries().toSet())
            assertEquals(setOf(Note("note-1")), db.recordDao().allNotes().toSet())
            assertEquals(setOf(TransactionNoteRelation("tx-1", "note-1")), db.recordDao().allRelations().toSet())
            // Foreign keys are enforced on open.
            db.openHelper.readableDatabase.query("PRAGMA foreign_keys").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
        reopened.close()
    }

    @Test
    fun publicOpenSucceedsAndCloses() {
        val result = RecordRepository.open(context)
        assertTrue("expected Success, got $result", result is Success)
        (result as Success).value.close()
    }

    @Test
    fun builderIllegalArgumentExceptionMapsToUnexpected() {
        val result = RecordRepository.openWithBuilder(context, TransactionFaultHook.NO_OP) {
            throw IllegalArgumentException("bad configuration")
        }
        assertEquals(Unexpected, result)
    }

    @Test
    fun builderCancellationExceptionIsRethrown() {
        assertThrows(CancellationException::class.java) {
            RecordRepository.openWithBuilder(context, TransactionFaultHook.NO_OP) {
                throw CancellationException("cancelled")
            }
        }
    }
}

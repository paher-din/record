package com.paherdin.record.core.data.room

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RepositoryResult.Failure.ValidationRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.DuplicateIdentity
import com.paherdin.record.core.data.RepositoryResult.Success
import com.paherdin.record.core.data.XorViolation
import com.paherdin.record.core.data.openForTest
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Criterion 2 (XOR identity). Database-layer evidence uses direct internal
 * writes only (the allowed bypass for schema-evidence cases, spec 5.1);
 * repository-layer cases go through the public write surface. No
 * `PROJECT`-persistence claim is made at the database layer — ADR 0006
 * establishes that no database `CHECK` exists.
 */
@RunWith(AndroidJUnit4::class)
class XorIdentityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()

    @After
    fun tearDown() {
        names.forEach { context.deleteDatabase(it) }
    }

    private fun dbName(tag: String): String = "xor_$tag.db".also { names += it }

    @Test
    fun databaseLayerRejectsSubtypeRowWithoutEntityRef() = runBlocking {
        val name = dbName("missing_ref")
        val db = Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
        try {
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { db.recordDao().insertTransaction(Transaction("t1", TransactionType.INCOME)) }
            }
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { db.recordDao().insertNote(Note("n1")) }
            }
            assertEquals(0, db.recordDao().allEntityRefs().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun databaseLayerRejectsDuplicateSubtypeRowPerTable() = runBlocking {
        val name = dbName("dup_subtype")
        val db = Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
        try {
            db.recordDao().insertEntityRef(EntityRef("x", EntityType.TRANSACTION, 1L, 1L, null))
            db.recordDao().insertTransaction(Transaction("x", TransactionType.INCOME))
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { db.recordDao().insertTransaction(Transaction("x", TransactionType.EXPENSE)) }
            }
            assertEquals(1, db.recordDao().allTransactions().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun repositoryRejectsCrossTableIdCollision() = runBlocking {
        val name = dbName("cross_collision")
        val repo = openForTest(context, name)
        assertEquals(
            Success(Unit),
            repo.saveNoteAggregate(NewNote(id = "x", createdAt = 1L, updatedAt = 1L, deletedAt = null, relationTransactionIds = emptySet())),
        )
        assertEquals(
            ValidationRejected(XorViolation.CROSS_TABLE_ID_COLLISION),
            repo.saveTransactionAggregate(
                NewTransaction(id = "x", type = TransactionType.INCOME, createdAt = 1L, updatedAt = 1L, deletedAt = null, entries = emptyList(), relationNoteIds = emptySet()),
            ),
        )
        // No partial state: only the note aggregate exists.
        val snap = snapshot(name, context)
        assertEquals(setOf(EntityRef("x", EntityType.NOTE, 1L, 1L, null)), snap.entityRefs)
        assertEquals(setOf(Note("x")), snap.notes)
        assertEquals(emptySet<Transaction>(), snap.transactions)
        repo.close()
    }

    @Test
    fun repositoryRejectsDuplicateSameTypeId() = runBlocking {
        val name = dbName("dup_sametype")
        val repo = openForTest(context, name)
        assertEquals(Success(Unit), repo.createAccount(NewAccount("acc-1", "Wallet", "CNY")))
        val tx = NewTransaction(
            id = "x", type = TransactionType.INCOME, createdAt = 1L, updatedAt = 1L, deletedAt = null,
            entries = listOf(NewAccountEntry("entry-1", "acc-1", 50L)), relationNoteIds = emptySet(),
        )
        assertEquals(Success(Unit), repo.saveTransactionAggregate(tx))
        assertEquals(DuplicateIdentity, repo.saveTransactionAggregate(tx))
        // No partial state: exactly one transaction aggregate.
        val snap = snapshot(name, context)
        assertEquals(setOf(Transaction("x", TransactionType.INCOME)), snap.transactions)
        assertEquals(setOf(EntityRef("x", EntityType.TRANSACTION, 1L, 1L, null)), snap.entityRefs)
        repo.close()
    }
}

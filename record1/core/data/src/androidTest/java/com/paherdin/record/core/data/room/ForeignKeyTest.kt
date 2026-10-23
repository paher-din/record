package com.paherdin.record.core.data.room

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Criterion 3: real foreign keys. Child rows with missing parents fail to
 * insert; a parent with child rows cannot be deleted into an orphaned state;
 * `NO ACTION` means no cascade is observed.
 */
@RunWith(AndroidJUnit4::class)
class ForeignKeyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()

    @After
    fun tearDown() {
        names.forEach { context.deleteDatabase(it) }
    }

    private fun dbName(tag: String): String = "fk_$tag.db".also { names += it }

    @Test
    fun childRowsWithMissingParentsFailToInsert() = runBlocking {
        val name = dbName("missing_parent")
        val db = Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
        try {
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { db.recordDao().insertAccountEntry(AccountEntry("e1", "no-tx", "no-acc", 1L)) }
            }
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { db.recordDao().insertRelation(TransactionNoteRelation("no-tx", "no-note")) }
            }
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { db.recordDao().insertAccountEntry(AccountEntry("e2", "tx-1", "no-acc", 1L)) }
            }
            assertEquals(0, db.recordDao().allAccountEntries().size)
            assertEquals(0, db.recordDao().allRelations().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun deletingParentWithChildrenFailsAndNoCascadeOccurs() = runBlocking {
        val name = dbName("no_cascade")
        val db = Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
        try {
            val dao = db.recordDao()
            dao.insertEntityRef(EntityRef("tx-1", EntityType.TRANSACTION, 1L, 1L, null))
            dao.insertTransaction(Transaction("tx-1", TransactionType.INCOME))
            dao.insertEntityRef(EntityRef("note-1", EntityType.NOTE, 1L, 1L, null))
            dao.insertNote(Note("note-1"))
            dao.insertAccount(Account("acc-1", "Wallet", "CNY"))
            dao.insertAccountEntry(AccountEntry("entry-1", "tx-1", "acc-1", 100L))
            dao.insertRelation(TransactionNoteRelation("tx-1", "note-1"))

            // A parent with children cannot be deleted into an orphaned state.
            assertThrows(SQLiteConstraintException::class.java) {
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM `Transaction` WHERE id = ?",
                    arrayOf("tx-1"),
                )
            }
            // No cascade: every child row is still present.
            assertEquals(setOf(Transaction("tx-1", TransactionType.INCOME)), dao.allTransactions().toSet())
            assertEquals(setOf(AccountEntry("entry-1", "tx-1", "acc-1", 100L)), dao.allAccountEntries().toSet())
            assertEquals(setOf(TransactionNoteRelation("tx-1", "note-1")), dao.allRelations().toSet())
        } finally {
            db.close()
        }
    }
}

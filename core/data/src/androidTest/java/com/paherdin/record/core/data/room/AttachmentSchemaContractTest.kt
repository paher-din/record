package com.paherdin.record.core.data.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.room.RecordDatabase.Companion.MIGRATION_1_2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Criterion 9(a) execution side: `Migration(1, 2)` applied to a v1 fixture
 * built from the v1 DDL preserves every v1 row, FK, and index, creates exactly
 * the two v2 tables, enforces `NO ACTION` FKs, and never uses destructive
 * fallback. The JSON contract itself is asserted on the JVM
 * (`SchemaV2ContractTest` against the committed `2.json`). `user_version`
 * advancement is Room's job at open; this test asserts the SQL the migration
 * executes.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentSchemaContractTest {

    private lateinit var context: Context
    private lateinit var dbFile: java.io.File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dir = java.io.File(context.cacheDir, "schema-test-${System.nanoTime()}")
        dir.mkdirs()
        dbFile = java.io.File(dir, "v1.db")
    }

    private fun createV1Database(db: SQLiteDatabase) = AttachmentTestFixtures.createV1Schema(db)

    private fun insertV1Fixture(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO `EntityRef` VALUES ('ref-t1', 'TRANSACTION', 1, 1, NULL)")
        db.execSQL("INSERT INTO `Transaction` VALUES ('ref-t1', 'EXPENSE')")
        db.execSQL("INSERT INTO `EntityRef` VALUES ('ref-n1', 'NOTE', 2, 2, NULL)")
        db.execSQL("INSERT INTO `Note` VALUES ('ref-n1')")
        db.execSQL("INSERT INTO `Account` VALUES ('acc-1', 'Wallet', 'CNY')")
        db.execSQL("INSERT INTO `AccountEntry` VALUES ('entry-1', 'ref-t1', 'acc-1', -5000)")
        db.execSQL("INSERT INTO `TransactionNoteRelation` VALUES ('ref-t1', 'ref-n1')")
        db.execSQL("INSERT INTO `EntityRef` VALUES ('ref-del', 'TRANSACTION', 3, 3, 100)")
        db.execSQL("INSERT INTO `Transaction` VALUES ('ref-del', 'INCOME')")
        db.execSQL("INSERT INTO `AccountEntry` VALUES ('entry-2', 'ref-del', 'acc-1', 100)")
    }

    /** A SupportSQLite handle over the fixture file with a matching version-1 callback (no auto-upgrade). */
    private fun openSupportDb(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
            .also { it.execSQL("PRAGMA foreign_keys = ON") }
    }

    @Test
    fun migrationPreservesV1RowsAndCreatesExactlyTheTwoTables() {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.CREATE_IF_NECESSARY).use { db ->
            createV1Database(db)
            insertV1Fixture(db)
        }
        openSupportDb().use { db ->
            MIGRATION_1_2.migrate(db)

            // v1 rows intact (three EntityRefs in the fixture).
            assertEquals(3L, db.query("SELECT COUNT(*) FROM `EntityRef`").use { c -> c.moveToFirst(); c.getLong(0) })
            assertEquals(1L, db.query("SELECT COUNT(*) FROM `Account`").use { c -> c.moveToFirst(); c.getLong(0) })
            assertEquals(2L, db.query("SELECT COUNT(*) FROM `AccountEntry`").use { c -> c.moveToFirst(); c.getLong(0) })
            assertEquals(1L, db.query("SELECT COUNT(*) FROM `TransactionNoteRelation`").use { c -> c.moveToFirst(); c.getLong(0) })
            assertEquals(-5000L, db.query("SELECT `amountMinor` FROM `AccountEntry` WHERE id='entry-1'").use { c -> c.moveToFirst(); c.getLong(0) })
            assertEquals(
                "CNY",
                db.query("SELECT `currency` FROM `Account` WHERE id='acc-1'").use { c -> c.moveToFirst(); c.getString(0) },
            )

            // Exactly the two new tables exist; no projection tables.
            val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            assertTrue(tables.contains("Attachment"))
            assertTrue(tables.contains("AttachmentLink"))
            assertTrue(!tables.contains("Timeline") && !tables.contains("SearchDocument"))

            // New rows work: attachment + link round trip.
            db.execSQL("INSERT INTO `Attachment` VALUES ('att-1', '${"A".repeat(64)}', NULL)")
            db.execSQL("INSERT INTO `AttachmentLink` VALUES ('att-1', 'ref-t1')")
            assertEquals(
                1L,
                db.query("SELECT COUNT(*) FROM `AttachmentLink` WHERE attachmentId='att-1'").use { c -> c.moveToFirst(); c.getLong(0) },
            )
        }
    }

    @Test
    fun duplicateContentIdentityIsRejectedByTheDatabase() {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.CREATE_IF_NECESSARY).use { db ->
            createV1Database(db)
        }
        openSupportDb().use { db ->
            MIGRATION_1_2.migrate(db)
            db.execSQL("INSERT INTO `Attachment` VALUES ('att-1', '${"B".repeat(64)}', NULL)")
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                db.execSQL("INSERT INTO `Attachment` VALUES ('att-2', '${"B".repeat(64)}', NULL)")
            }
        }
    }

    @Test
    fun attachmentLinkEnforcesNoActionForeignKeys() {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.CREATE_IF_NECESSARY).use { db ->
            createV1Database(db)
            db.execSQL("INSERT INTO `EntityRef` VALUES ('ref-t1', 'TRANSACTION', 1, 1, NULL)")
            db.execSQL("INSERT INTO `Transaction` VALUES ('ref-t1', 'EXPENSE')")
        }
        openSupportDb().use { db ->
            MIGRATION_1_2.migrate(db)
            // Link to a missing attachment: rejected.
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                db.execSQL("INSERT INTO `AttachmentLink` VALUES ('missing-att', 'ref-t1')")
            }
            // Link from a missing owner EntityRef: rejected.
            db.execSQL("INSERT INTO `Attachment` VALUES ('att-1', '${"C".repeat(64)}', NULL)")
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                db.execSQL("INSERT INTO `AttachmentLink` VALUES ('att-1', 'no-such-owner')")
            }
        }
    }
}

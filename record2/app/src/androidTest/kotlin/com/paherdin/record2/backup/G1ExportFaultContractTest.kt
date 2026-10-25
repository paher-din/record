package com.paherdin.record2.backup

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.FinanceSeeds
import com.paherdin.record2.storage.AiControlEntity
import com.paherdin.record2.storage.RecordDataStateEntity
import com.paherdin.record2.storage.RecordDatabase
import com.paherdin.record2.storage.toEntity
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-room export failure contract: a live WAL growth beyond the injected limit aborts the
 * export through the real FileSystemExportResourceMonitor, releases the read-only snapshot and
 * gate, removes the private export staging directory, and leaves the database writable for a
 * successful retry.
 */
class G1ExportFaultContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_010_000), ZoneOffset.UTC)
    private val staging = File(context.noBackupFilesDir, "g1-export-fault")
    private var database: RecordDatabase? = null
    private var name: String? = null

    @After
    fun cleanup() {
        database?.close()
        database = null
        name?.let { context.deleteDatabase(it) }
        staging.deleteRecursively()
    }

    @Test
    fun walGrowthAbortsExportAndRetrySucceeds() = runBlocking {
        val dbName = "g1-export-fault-${UUID.randomUUID()}.db"
        name = dbName
        val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        database = db
        db.openHelper.writableDatabase
        seed(db, 300)
        checkpoint(db)
        val path = context.getDatabasePath(dbName)
        val gate = DataOperationGate()
        val firstTarget = File(staging, "first.zip")

        val first = BackupExporter(
            path, staging, gate, AppInfo(1, "0.1.0"), clock,
            availableBytes = { Long.MAX_VALUE },
            monitorFactory = { databasePath, stagingDirectory ->
                FileSystemExportResourceMonitor(
                    databasePath, stagingDirectory,
                    freeSpace = { Long.MAX_VALUE },
                    maxWalGrowthBytes = 1024L,
                )
            },
            snapshotHook = { insertNote(db, "concurrent-during-export", 4096) },
        )
        val error = runCatching {
            first.export(FileTarget(firstTarget), firstTarget.name)
        }.exceptionOrNull()
        assertTrue("expected StorageFull, got $error", error is BackupException)
        assertEquals(BackupError.StorageFull, (error as BackupException).code)
        assertFalse(firstTarget.exists())
        assertEquals(emptyList<String>(), staging.listFiles().orEmpty().map { it.name }.filter { it.startsWith("export-") })
        runBlocking { gate.beginExport().close() }
        runBlocking { gate.withWrite { Unit } }

        insertNote(db, "after-failed-export", 8)
        val retryTarget = File(staging, "retry.zip")
        val retry = BackupExporter(
            path, staging, DataOperationGate(), AppInfo(1, "0.1.0"), clock,
            availableBytes = { Long.MAX_VALUE },
        ).export(FileTarget(retryTarget), retryTarget.name)
        val initialRows = 300L + 1 + FinanceSeeds.categories.size
        assertEquals(initialRows + 2, retry.totalRows)
        val verified = LogicalZipReader.verify(retryTarget)
        assertEquals(initialRows + 2, verified.manifest.totalRows)
        assertTrue(verified.manifest.totalUncompressedBytes > 0)
        assertEquals(
            302L,
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM note").use { cursor ->
                cursor.moveToFirst(); cursor.getLong(0)
            },
        )
    }

    private fun seed(database: RecordDatabase, noteCount: Int) = runBlocking {
        database.withTransaction {
            val dao = database.backup()
            dao.insertAccount(
                AccountRow(FinanceSeeds.ACCOUNT_ID, "日常账户", "CNY", 1, START, FINISH).toEntity(),
            )
            FinanceSeeds.categories.forEachIndexed { index, seed ->
                dao.insertCategory(
                    CategoryRow(seed.id, seed.name, seed.direction.name, 1, START + index, FINISH).toEntity(),
                )
            }
            dao.insertControl(AiControlEntity(1, "OFF", null, null, null, null, null, 1))
            dao.insertDataState(
                RecordDataStateEntity(
                    1, "11111111-1111-4111-8111-111111111111", 1,
                    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", null,
                ),
            )
        }
        val raw = database.openHelper.writableDatabase
        raw.beginTransaction()
        try {
            val statement = raw.compileStatement(
                "INSERT INTO note (id, body, occurredAt, version, createdAt, updatedAt, deletedAt) " +
                    "VALUES (?, ?, ?, 1, ?, ?, NULL)",
            )
            repeat(noteCount) { index ->
                statement.bindString(1, UUID.randomUUID().toString())
                statement.bindString(2, "note-$index")
                statement.bindLong(3, START + index)
                statement.bindLong(4, START)
                statement.bindLong(5, FINISH)
                statement.executeInsert()
            }
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
        }
    }

    private fun insertNote(database: RecordDatabase, bodyPrefix: String, bodyBytes: Int) {
        val raw = database.openHelper.writableDatabase
        raw.beginTransaction()
        try {
            val statement = raw.compileStatement(
                "INSERT INTO note (id, body, occurredAt, version, createdAt, updatedAt, deletedAt) " +
                    "VALUES (?, ?, ?, 1, ?, ?, NULL)",
            )
            statement.bindString(1, UUID.randomUUID().toString())
            statement.bindString(2, bodyPrefix + "x".repeat(bodyBytes))
            statement.bindLong(3, START + 500)
            statement.bindLong(4, START)
            statement.bindLong(5, FINISH)
            statement.executeInsert()
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
        }
    }

    private fun checkpoint(database: RecordDatabase) {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            it.moveToFirst()
        }
    }

    private class FileTarget(private val file: File) : BackupTarget {
        override suspend fun writeFrom(source: File) {
            source.inputStream().use { input ->
                file.parentFile?.mkdirs()
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }

        override suspend fun readBackSha256(): String = file.inputStream().use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65_536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        override suspend fun readBackBytes(): Long = file.length()
    }

    private companion object {
        const val START = 1_700_000_000_000L
        const val FINISH = START + 1_000L
    }
}

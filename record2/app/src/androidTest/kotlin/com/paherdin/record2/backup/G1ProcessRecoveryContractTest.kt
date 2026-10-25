package com.paherdin.record2.backup

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.FinanceSeeds
import com.paherdin.record2.storage.AiControlEntity
import com.paherdin.record2.storage.RecordDataStateEntity
import com.paherdin.record2.storage.RecordDatabase
import com.paherdin.record2.storage.RoomRestoreStore
import com.paherdin.record2.storage.toEntity
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Real force-stop matrix for a confirmed restore, driven by tools/verify-g1-process.py:
 *
 *  1. `#prepare` builds a source ZIP, seeds a distinct target with no receipt, then either blocks
 *     inside preflight copy or at a restore checkpoint and reports `g1_ready`.
 *  2. The host force-stops the process (PID must vanish), relaunches MainActivity and waits for a
 *     fresh foreground process.
 *  3. `#verify` in the fresh process proves the checkpoint boundary: pre-commit checkpoints roll
 *     back to the byte-identical baseline with no receipt; AFTER_COMMIT keeps the complete data,
 *     the receipt and a usable startup gate. Pre-commit states then run a normal restore to prove
 *     the database is still writable.
 */
class G1ProcessRecoveryContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = Clock.fixed(
        Instant.ofEpochMilli(BackupTestDataFactory.FINISH + 10_000),
        ZoneOffset.UTC,
    )
    private val root = File(context.filesDir, "g1-process")
    private val staging = File(root, "staging")

    @Test
    fun prepare() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val databaseName = args.getString("databaseName") ?: error("missing -e databaseName")
        val checkpointName = args.getString("checkpoint") ?: error("missing -e checkpoint")
        val checkpoint = if (checkpointName == "PREFLIGHT") null else RestoreCheckpoint.valueOf(checkpointName)
        root.mkdirs()
        staging.mkdirs()
        val orphan = File(
            BackupStaging.appDirectory(context),
            "preflight-${UUID.randomUUID()}",
        ).apply {
            mkdirs()
            File(this, "source.zip").writeText("orphan")
        }
        val orphanPath = orphan.absolutePath
        val work = File(root, checkpointName).apply {
            deleteRecursively()
            mkdirs()
        }
        val zip = File(work, "source.zip")
        val sourceName = "g1-process-source-${UUID.randomUUID()}.db"
        val sourceDb = open(sourceName)
        seed(sourceDb, BackupTestDataFactory.create())
        try {
            BackupExporter(
                context.getDatabasePath(sourceName), staging, DataOperationGate(),
                AppInfo(1, "0.1.0"), clock,
                availableBytes = { Long.MAX_VALUE },
                monitorFactory = { _, _ -> NoopExportResourceMonitor() },
            ).export(FileTarget(zip), zip.name)
        } finally {
            sourceDb.close()
            context.deleteDatabase(sourceName)
        }
        val zipSha = sha256(zip)
        val zipBytes = zip.length()

        val target = open(databaseName)
        val targetPath = context.getDatabasePath(databaseName)
        val baseline = baselineContent()
        seedContent(target, baseline, BASELINE_GENERATION, BASELINE_OWNER, receipt = null)
        val baselineDigest = tableDigest(targetPath)
        val metadata = JSONObject()
            .put("database", databaseName)
            .put("checkpoint", checkpointName)
            .put("zip", zip.absolutePath)
            .put("zipSha", zipSha)
            .put("zipBytes", zipBytes)
            .put("baseline", baselineDigest)
            .put("baselineGeneration", BASELINE_GENERATION)
            .put("checkpointFile", checkpointName)
            .put("orphan", orphanPath)
        var preflightGeneration: String? = null
        val service = RestoreService(
            RoomRestoreStore(target, clock), targetPath, staging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE },
            checkpoint = { reached ->
                if (reached == checkpoint) {
                    metadata.put("preflightGeneration", preflightGeneration)
                    ready(metadata)
                    CountDownLatch(1).await()
                }
            },
        )
        val source = if (checkpoint == null) BlockingSource(zip) { ready(metadata) } else FileSource(zip)
        val candidate = service.preflight(source)
        preflightGeneration = candidate.targetGeneration
        try {
            service.restore(candidate) { }
            fail("restore must be killed at the selected checkpoint")
        } finally {
            target.close()
        }
    }

    @Test
    fun verify() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val databaseName = args.getString("databaseName") ?: error("missing -e databaseName")
        val checkpointName = args.getString("checkpoint") ?: error("missing -e checkpoint")
        val metadata = JSONObject(File(File(root, checkpointName), "ready.json").readText())
        val targetPath = context.getDatabasePath(databaseName)
        val target = Room.databaseBuilder(context, RecordDatabase::class.java, databaseName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        target.openHelper.writableDatabase
        try {
            val startup = RestoreStartupGate(targetPath, clock).check()
            assertEquals(StartupKind.NORMAL, startup.kind)
            val orphan = File(metadata.getString("orphan"))
            val orphanDeadline = System.currentTimeMillis() + 10_000
            while (orphan.exists() && System.currentTimeMillis() < orphanDeadline) delay(100)
            assertFalse("RecordApplication must sweep orphan staging at startup", orphan.exists())
            val zip = File(metadata.getString("zip"))
            val zipSha = metadata.getString("zipSha")
            assertEquals(zipSha, sha256(zip))
            if (checkpointName == "AFTER_COMMIT") {
                assertNotNull(startup.receipt)
                assertEquals(zipSha, startup.receipt!!.sourceZipSha256)
                val expected = LogicalZipReader.read(zip).backup.content
                assertEquals(
                    expected.notes.size.toLong(),
                    count(targetPath, "note"),
                )
                assertEquals(
                    expected.transactions.size.toLong(),
                    count(targetPath, "record_transaction"),
                )
                assertEquals(0L, count(targetPath, "agent_run WHERE activeSlot IS NOT NULL"))
                assertEquals(0L, count(targetPath, "change_proposal WHERE activeSlot IS NOT NULL"))
                // The pre-death generation proves the receipt-reuse path after observation loss.
                val preflightGeneration = metadata.getString("preflightGeneration")
                val candidate = candidate(zip, zipSha, preflightGeneration)
                val lookup = service(target, targetPath).receiptLookup(candidate)
                assertTrue(lookup is RestoreReceiptLookup.Committed)
                assertEquals(
                    startup.receipt!!.restoredGeneration,
                    (lookup as RestoreReceiptLookup.Committed).receipt.restoredGeneration,
                )
            } else {
                val currentDigest = tableDigest(targetPath)
                if (currentDigest != metadata.getString("baseline")) {
                    val counts = TABLES.associateWith { count(targetPath, it) }
                    File(root, "digest-diff.txt").writeText(
                        "baseline=${metadata.getString("baseline")}\n" +
                            "current=$currentDigest\ncounts=$counts\n",
                    )
                }
                assertEquals(metadata.getString("baseline"), currentDigest)
                assertNull(startup.receipt)
                assertNull(target.records().dataState()?.restoreReceipt)
                // A fresh restore after the kill proves the database and gate are still usable.
                val service = service(target, targetPath)
                val candidate = service.preflight(FileSource(zip))
                service.restore(candidate) { }
                val after = RestoreStartupGate(targetPath, clock).check()
                assertEquals(StartupKind.NORMAL, after.kind)
                assertNotNull(after.receipt)
                assertEquals(zipSha, after.receipt!!.sourceZipSha256)
            }
        } finally {
            target.close()
            context.deleteDatabase(databaseName)
            File(root, checkpointName).deleteRecursively()
            staging.deleteRecursively()
        }
    }

    private fun service(database: RecordDatabase, path: File): RestoreService = RestoreService(
        RoomRestoreStore(database, clock), path, staging, DataOperationGate(), clock,
        availableBytes = { Long.MAX_VALUE },
    )

    private fun ready(metadata: JSONObject) {
        File(root, metadata.getString("checkpointFile")).mkdirs()
        File(File(root, metadata.getString("checkpointFile")), "ready.json")
            .writeText(metadata.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("g1_ready", metadata.toString()) },
        )
    }

    private class BlockingSource(
        private val file: File,
        private val onReady: () -> Unit,
    ) : BackupSource {
        override val displayName: String? = file.name

        override suspend fun copyTo(destination: File, maxBytes: Long): Long {
            file.inputStream().use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
            onReady()
            CountDownLatch(1).await()
            return file.length()
        }
    }

    private class FileSource(private val file: File) : BackupSource {
        override val displayName: String? = file.name
        override suspend fun copyTo(destination: File, maxBytes: Long): Long {
            file.inputStream().use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
            return file.length()
        }
    }

    private class FileTarget(private val file: File) : BackupTarget {
        override suspend fun writeFrom(source: File) {
            source.inputStream().use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
        }

        override suspend fun readBackSha256(): String = sha256(file)
        override suspend fun readBackBytes(): Long = file.length()
    }

    private fun open(name: String): RecordDatabase {
        val database = Room.databaseBuilder(context, RecordDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        database.openHelper.writableDatabase
        return database
    }

    private fun seed(database: RecordDatabase, data: BackupTestData) {
        seedContent(database, data.content, data.generation, data.owner, receipt = null)
    }

    private fun seedContent(
        database: RecordDatabase,
        content: LogicalBackupContent,
        generation: String,
        owner: String,
        receipt: String?,
    ) {
        runBlocking {
            database.withTransaction {
                val dao = database.backup()
                content.accounts.forEach { dao.insertAccount(it.toEntity()) }
                content.categories.forEach { dao.insertCategory(it.toEntity()) }
                content.transactions.forEach { dao.insertTransaction(it.toEntity()) }
                content.notes.forEach { dao.insertNote(it.toEntity()) }
                content.links.forEach { dao.insertLink(it.toEntity()) }
                content.drafts.forEach { dao.insertDraft(it.toEntity()) }
                content.sessions.forEach { dao.insertSession(it.toEntity()) }
                content.runs.forEach { dao.insertRun(it.toEntity()) }
                content.proposals.forEach { dao.insertProposal(it.toEntity()) }
                content.operations.forEach { dao.insertOperation(it.toEntity()) }
                content.messages.forEach { dao.insertMessage(it.toEntity()) }
                content.references.forEach { dao.insertReference(it.toEntity()) }
                dao.insertControl(AiControlEntity(1, "OFF", null, null, null, null, null, 1))
                dao.insertDataState(
                    RecordDataStateEntity(1, generation, 1, owner, receipt),
                )
            }
        }
    }

    private fun baselineContent(): LogicalBackupContent {
        val account = AccountRow(FinanceSeeds.ACCOUNT_ID, "baseline", "CNY", 1, START, FINISH)
        val categories = FinanceSeeds.categories.mapIndexed { index, seed ->
            CategoryRow(seed.id, seed.name, seed.direction.name, 1, START + index, FINISH)
        }
        return LogicalBackupContent(
            accounts = listOf(account),
            categories = categories,
            transactions = emptyList(),
            notes = listOf(
                NoteRow(BASELINE_NOTE, "baseline-note", START, 1, START, FINISH, null),
            ),
            links = emptyList(),
            drafts = emptyList(),
            proposals = emptyList(),
            operations = emptyList(),
            sessions = emptyList(),
            runs = emptyList(),
            messages = emptyList(),
            references = emptyList(),
        )
    }

    private fun candidate(zip: File, sha: String, generation: String): RestoreCandidate {
        val manifest = LogicalZipReader.read(zip).backup.manifest
        return RestoreCandidate(
            id = "process-verify",
            source = FileSource(zip),
            sourceDisplayName = zip.name,
            sourceBytes = zip.length(),
            sourceSha256 = sha,
            manifest = manifest,
            targetGeneration = generation,
            targetAuthEpoch = null,
            preflightAt = 0,
            appSchemaVersion = 1,
            appVersionCode = 1,
        )
    }

    private fun count(path: File, table: String): Long =
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
        }

    private fun tableDigest(path: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            for (table in TABLES) {
                digest.update(table.toByteArray())
                db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                    val rows = ArrayList<String>()
                    while (cursor.moveToNext()) {
                        rows += (0 until cursor.columnCount).joinToString("|") { index ->
                            if (cursor.isNull(index)) "<null>" else cursor.getString(index)
                        }
                    }
                    rows.sort()
                    rows.forEach { digest.update(it.toByteArray()) }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private companion object {
        const val BASELINE_GENERATION = "22222222-2222-4222-8222-222222222222"
        const val BASELINE_OWNER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val BASELINE_NOTE = "12121212-1212-4212-8212-121212121212"
        const val START = BackupTestDataFactory.START
        const val FINISH = BackupTestDataFactory.FINISH
        val TABLES = listOf(
            "account", "category", "record_transaction", "note", "transaction_note_link",
            "native_finance_draft", "change_proposal", "commit_operation", "agent_session",
            "agent_run", "agent_message", "agent_message_reference", "ai_control",
            "record_data_state",
        )
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65_536)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

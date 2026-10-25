package com.paherdin.record2.backup

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real Android ContentResolver document path for backup output and restore input. Documents are
 * inserted into MediaStore Downloads (the same DocumentsProvider family DocumentsUI saves to),
 * written through [ContentResolverBackupTarget], read back by digest and restored through
 * [ContentResolverBackupSource]. The picker Activity itself belongs to F1 and is not launched
 * here; [BackupIntents] is asserted against the platform contract.
 */
class G1SafContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val clock = Clock.fixed(
        Instant.ofEpochMilli(BackupTestDataFactory.FINISH + 10_000),
        ZoneOffset.UTC,
    )
    private val staging = File(context.noBackupFilesDir, "g1-saf")

    @After
    fun cleanup() {
        staging.deleteRecursively()
    }

    @Test
    fun documentTargetAndSourceRoundTrip() = runBlocking {
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-saf-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val zip = File(staging, "saf-source.zip")
        staging.mkdirs()
        try {
            BackupExporter(
                context.getDatabasePath(sourceName), staging, DataOperationGate(),
                AppInfo(1, "0.1.0"), clock,
                availableBytes = { Long.MAX_VALUE },
                monitorFactory = { _, _ -> NoopExportResourceMonitor() },
            ).export(object : BackupTarget {
                override suspend fun writeFrom(source: File) {
                    source.inputStream().use { input ->
                        FileOutputStream(zip).use { output -> input.copyTo(output) }
                    }
                }

                override suspend fun readBackSha256(): String = fileDigest(zip)
                override suspend fun readBackBytes(): Long = zip.length()
            }, zip.name)
        } finally {
            source.close()
            context.deleteDatabase(sourceName)
        }
        val sha = fileDigest(zip)
        val bytes = zip.length()
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "g1-saf-${UUID.randomUUID()}.zip")
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Record2G1",
                )
            },
        ) ?: error("MediaStore did not create the document")
        try {
            val target = ContentResolverBackupTarget(resolver, uri)
            target.writeFrom(zip)
            assertEquals(sha, target.readBackSha256())
            assertEquals(bytes, target.readBackBytes())

            val restoredName = "g1-saf-target-${UUID.randomUUID()}.db"
            val restored = open(restoredName)
            val restoredPath = context.getDatabasePath(restoredName)
            try {
                val service = RestoreService(
                    RoomRestoreStore(restored, clock), restoredPath, staging,
                    DataOperationGate(), clock,
                    availableBytes = { Long.MAX_VALUE },
                )
                val candidate = service.preflight(
                    ContentResolverBackupSource(resolver, uri, "saf-source.zip"),
                )
                assertEquals(sha, candidate.sourceSha256)
                assertEquals(bytes, candidate.sourceBytes)
                val result = service.restore(candidate)
                assertEquals(sha, result.receipt.sourceZipSha256)
                assertEquals(
                    data.content.notes.size.toLong(),
                    restored.query("SELECT COUNT(*) FROM note", null).use { cursor ->
                        cursor.moveToFirst()
                        cursor.getLong(0)
                    },
                )
                assertEquals(
                    StartupKind.NORMAL,
                    RestoreStartupGate(restoredPath, clock).check().kind,
                )
            } finally {
                restored.close()
                context.deleteDatabase(restoredName)
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun missingDocumentFailsAsRecoverableIo() = runBlocking {
        val missing = Uri.parse("content://media/external/downloads/999999999")
        val error = assertThrows(BackupException::class.java) {
            runBlocking {
                ContentResolverBackupSource(resolver, missing, null)
                    .copyTo(File(staging, "missing.zip"), BackupLimits.MAX_ZIP_BYTES)
            }
        }
        assertEquals(BackupError.RecoverableIO, error.code)
    }

    @Test
    fun sourceLargerThanTheCallerBudgetIsRejectedAndCleaned() = runBlocking {
        val file = File(staging, "oversize.bin").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4096))
        }
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "g1-oversize-${UUID.randomUUID()}.bin")
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Record2G1",
                )
            },
        ) ?: error("MediaStore did not create the document")
        try {
            resolver.openOutputStream(uri)!!.use { output -> output.write(file.readBytes()) }
            val destination = File(staging, "oversize-copy.bin")
            val error = assertThrows(BackupException::class.java) {
                runBlocking {
                    ContentResolverBackupSource(resolver, uri, null)
                        .copyTo(destination, 1024)
                }
            }
            assertEquals(BackupError.InvalidSource, error.code)
            assertTrue(!destination.exists())
        } finally {
            resolver.delete(uri, null, null)
            file.delete()
        }
    }

    @Test
    fun pickerIntentsCarryTheDocumentContract() {
        val create = BackupIntents.createDocument("record2-test.zip")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.action)
        assertEquals("application/zip", create.type)
        assertTrue(create.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("record2-test.zip", create.getStringExtra(Intent.EXTRA_TITLE))
        val open = BackupIntents.openDocument()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, open.action)
        assertEquals("application/zip", open.type)
        assertTrue(open.hasCategory(Intent.CATEGORY_OPENABLE))
    }

    private fun open(name: String): RecordDatabase {
        val database = Room.databaseBuilder(context, RecordDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        database.openHelper.writableDatabase
        return database
    }

    private fun seed(database: RecordDatabase, data: BackupTestData) {
        runBlocking {
            database.withTransaction {
                val dao = database.backup()
                data.content.accounts.forEach { dao.insertAccount(it.toEntity()) }
                data.content.categories.forEach { dao.insertCategory(it.toEntity()) }
                data.content.transactions.forEach { dao.insertTransaction(it.toEntity()) }
                data.content.notes.forEach { dao.insertNote(it.toEntity()) }
                data.content.links.forEach { dao.insertLink(it.toEntity()) }
                data.content.drafts.forEach { dao.insertDraft(it.toEntity()) }
                data.content.sessions.forEach { dao.insertSession(it.toEntity()) }
                data.content.runs.forEach { dao.insertRun(it.toEntity()) }
                data.content.proposals.forEach { dao.insertProposal(it.toEntity()) }
                data.content.operations.forEach { dao.insertOperation(it.toEntity()) }
                data.content.messages.forEach { dao.insertMessage(it.toEntity()) }
                data.content.references.forEach { dao.insertReference(it.toEntity()) }
                dao.insertControl(AiControlEntity(1, "OFF", null, null, null, null, null, 1))
                dao.insertDataState(
                    RecordDataStateEntity(1, data.generation, 1, data.owner, null),
                )
            }
        }
    }
}

private fun fileDigest(file: File): String {
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

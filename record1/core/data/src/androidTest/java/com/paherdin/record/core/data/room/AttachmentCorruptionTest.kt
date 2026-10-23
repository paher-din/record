package com.paherdin.record.core.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.CorruptionDetail
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.openForTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Criterion 7 — corruption fails closed: metadata/blob split, hash/size
 * mismatch, and conflicting metadata produce the typed corruption outcome,
 * quarantine only an unlinked new input, never overwrite the canonical blob
 * or metadata, never create a second Attachment row, and leave the
 * pre-existing owner state unchanged. The expected zero-link pending deletion
 * window is classified separately (Task 7).
 */
@RunWith(AndroidJUnit4::class)
class AttachmentCorruptionTest {

    private lateinit var context: Context
    private lateinit var srcDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Isolate the shared content-addressed store between tests: the
        // canonical blob directory is global by design (dedup), so each test
        // starts from an empty store.
        File(context.filesDir, "attachments").deleteRecursively()
        srcDir = File(context.cacheDir, "corrupt-" + System.nanoTime())
        srcDir.mkdirs()
    }

    private fun source(name: String) = AttachmentTestFixtures.writeSource(srcDir, name, AttachmentTestFixtures.ONE_PIXEL_PNG)

    private fun expectedSha(): String = MessageDigest.getInstance("SHA-256")
        .digest(AttachmentTestFixtures.ONE_PIXEL_PNG)
        .joinToString("") { "%02x".format(it) }

    private fun insertAttachment(dbName: String, attachment: Attachment) {
        kotlinx.coroutines.runBlocking {
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            try {
                db.recordDao().insertAttachment(attachment)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun metadataBlobSplitIsCorruptionAndQuarantinesOnlyNewInput() {
        runBlocking {
            val dbName = "corrupt_" + System.nanoTime() + ".db"
            // Seed a canonical row WITHOUT its blob (simulated metadata/blob split).
            insertAttachment(dbName, Attachment(UUID.randomUUID().toString(), expectedSha(), null))

            val repo = openForTest(context, dbName)
            val result = repo.ingestAttachment(source("s1.png"), "image/png")
            assertTrue("split must be corruption, got " + result, result is RepositoryResult.Failure.AttachmentCorrupted)
            assertEquals(CorruptionDetail.METADATA_BLOB_SPLIT, (result as RepositoryResult.Failure.AttachmentCorrupted).detail)
            repo.close()

            // No second row, no overwrite, and the new input is quarantined.
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            assertEquals(1, db.recordDao().allAttachments().size)
            db.close()
            val quarantine = File(context.filesDir, "attachments/quarantine").listFiles()?.filter { it.isFile } ?: emptyList()
            assertTrue("the unlinked new input must be quarantined", quarantine.isNotEmpty())
            assertTrue(
                "the canonical blob must not exist (nothing overwritten)",
                !File(context.filesDir, "attachments/content/" + expectedSha()).exists(),
            )
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun hashMismatchIsCorruptionAndNeverOverwrites() {
        runBlocking {
            val dbName = "corrupt_" + System.nanoTime() + ".db"
            // Seed a canonical row for the expected identity, but occupy the
            // canonical target with DIFFERENT bytes (hash mismatch).
            insertAttachment(dbName, Attachment(UUID.randomUUID().toString(), expectedSha(), null))
            val wrongBlob = File(context.filesDir, "attachments/content/" + expectedSha())
            wrongBlob.parentFile.mkdirs()
            wrongBlob.writeBytes("different bytes".toByteArray())

            val repo = openForTest(context, dbName)
            val result = repo.ingestAttachment(source("s2.png"), "image/png")
            assertTrue("mismatch must be corruption, got " + result, result is RepositoryResult.Failure.AttachmentCorrupted)
            assertEquals(CorruptionDetail.HASH_MISMATCH, (result as RepositoryResult.Failure.AttachmentCorrupted).detail)
            repo.close()

            // The canonical blob was never overwritten with the incoming bytes.
            assertEquals("different bytes", wrongBlob.readText())
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            assertEquals(1, db.recordDao().allAttachments().size)
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun conflictingMetadataWithoutRowIsCorruption() {
        runBlocking {
            val dbName = "corrupt_" + System.nanoTime() + ".db"
            // Blob occupies the canonical target but NO row exists.
            val sha = expectedSha()
            val target = File(context.filesDir, "attachments/content/" + sha)
            target.parentFile.mkdirs()
            target.writeBytes("unverified bytes at the canonical target".toByteArray())

            val repo = openForTest(context, dbName)
            val result = repo.ingestAttachment(source("s3.png"), "image/png")
            assertTrue("conflict must be corruption, got " + result, result is RepositoryResult.Failure.AttachmentCorrupted)
            assertEquals(CorruptionDetail.CONFLICTING_METADATA, (result as RepositoryResult.Failure.AttachmentCorrupted).detail)
            repo.close()

            // The occupied target is untouched; no row appeared.
            assertEquals("unverified bytes at the canonical target", target.readText())
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            assertEquals(0, db.recordDao().allAttachments().size)
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}

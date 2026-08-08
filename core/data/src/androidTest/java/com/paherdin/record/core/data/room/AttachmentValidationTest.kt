package com.paherdin.record.core.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.AttachmentValidator
import com.paherdin.record.core.data.AttachmentViolation
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

/**
 * Criterion 2 — validation and hash through the public surface with the real
 * `BitmapFactory` probe: rejected content never passes `VERIFIED` (no link,
 * no success result, owner unchanged), the 32 MiB PoC size limit is enforced,
 * the declared/decoded MIME matrix is exact, and the ingestion pipeline never
 * trusts a caller-supplied hash.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentValidationTest {

    private lateinit var context: Context
    private lateinit var srcDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Isolate the shared content-addressed store between tests: the
        // canonical blob directory is global by design (dedup), so each test
        // starts from an empty store.
        File(context.filesDir, "attachments").deleteRecursively()
        srcDir = File(context.cacheDir, "val-" + System.nanoTime())
        srcDir.mkdirs()
    }

    private fun assertRejected(result: RepositoryResult<*>, violation: AttachmentViolation, message: String) {
        assertTrue(message, result is RepositoryResult.Failure.AttachmentRejected)
        assertEquals(violation, (result as RepositoryResult.Failure.AttachmentRejected).violation)
    }

    @Test
    fun undecodableContentIsRejectedAndNothingBecomesVisible() {
        runBlocking {
            val bad = AttachmentTestFixtures.writeSource(srcDir, "bad.png", "this is not an image at all".toByteArray())
            val repo = openForTest(context, "val_" + System.nanoTime() + ".db")
            val result = repo.ingestAttachment(bad, "image/png")
            assertRejected(result, AttachmentViolation.UNDECODABLE, "undecodable bytes accepted")
            repo.close()
        }
    }

    @Test
    fun mimeOutsideSetIsRejectedBeforeDecoding() {
        runBlocking {
            val png = AttachmentTestFixtures.writeSource(srcDir, "ok.png", AttachmentTestFixtures.ONE_PIXEL_PNG)
            val repo = openForTest(context, "val_" + System.nanoTime() + ".db")
            val result = repo.ingestAttachment(png, "application/pdf")
            assertRejected(result, AttachmentViolation.MIME_TYPE_NOT_ACCEPTED, "declared MIME outside the PoC set accepted")
            repo.close()
        }
    }

    @Test
    fun decodedMimeDifferentFromDeclaredIsRejected() {
        runBlocking {
            val jpeg = AttachmentTestFixtures.writeSource(srcDir, "ok.jpg", AttachmentTestFixtures.ONE_PIXEL_JPEG)
            val repo = openForTest(context, "val_" + System.nanoTime() + ".db")
            val result = repo.ingestAttachment(jpeg, "image/png")
            assertRejected(result, AttachmentViolation.MIME_TYPE_NOT_ACCEPTED, "declared/decoded MIME mismatch accepted")
            repo.close()
        }
    }

    @Test
    fun sizeOverPoCLimitIsRejected() {
        runBlocking {
            val huge = AttachmentTestFixtures.writeSparse(srcDir, "huge.png", 32L * 1024 * 1024 + 1)
            val repo = openForTest(context, "val_" + System.nanoTime() + ".db")
            val result = repo.ingestAttachment(huge, "image/png")
            assertRejected(result, AttachmentViolation.SIZE_LIMIT_EXCEEDED, "content over the 32 MiB PoC limit accepted")
            repo.close()
        }
    }

    @Test
    fun validImageIngestsAndHashMatchesFileContent() {
        runBlocking {
            val png = AttachmentTestFixtures.writeSource(srcDir, "ok.png", AttachmentTestFixtures.ONE_PIXEL_PNG)
            val expected = MessageDigest.getInstance("SHA-256").digest(AttachmentTestFixtures.ONE_PIXEL_PNG)
                .joinToString("") { "%02x".format(it) }
            val repo = openForTest(context, "val_" + System.nanoTime() + ".db")
            val result = repo.ingestAttachment(png, "image/png")
            assertTrue("valid PNG rejected: " + result, result is RepositoryResult.Success)
            assertEquals(expected, (result as RepositoryResult.Success).value)
            repo.close()
        }
    }

    @Test
    fun rejectedContentLeavesNoCanonicalRowAndNoBlob() {
        runBlocking {
            val bad = AttachmentTestFixtures.writeSource(srcDir, "bad.png", "not an image".toByteArray())
            val dbName = "val_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            repo.ingestAttachment(bad, "image/png")
            repo.close()
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            assertEquals(0, db.recordDao().allAttachments().size)
            assertEquals(0, db.recordDao().allAttachmentLinks().size)
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun sha256HelperMatchesKnownVectorOnDevice() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AttachmentValidator.sha256("abc".toByteArray(Charsets.UTF_8)),
        )
    }
}

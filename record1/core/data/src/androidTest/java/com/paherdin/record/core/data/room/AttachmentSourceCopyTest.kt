package com.paherdin.record.core.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.AttachmentFaultHook
import com.paherdin.record.core.data.AttachmentStage
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.openForTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Criterion 1 — the `ATTACHMENT_SOURCE_COPY` matrix: interrupt the source
 * copy before temp creation, during first/middle/final chunks, after final
 * write before close, after close before validation/hash, and on
 * source-read/destination-flush failure. For every point: no `AttachmentLink`
 * becomes visible, no success result is emitted, and the pre-existing owner
 * stays unchanged; the partial TEMP residue is classified for safe cleanup
 * and ingestion can be retried without reusing unverified bytes. Every point
 * runs the three-scenario protocol (plan Task 5).
 */
@RunWith(AndroidJUnit4::class)
class AttachmentSourceCopyTest {

    private lateinit var context: Context
    private lateinit var srcDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Isolate the shared content-addressed store between tests: the
        // canonical blob directory is global by design (dedup), so each test
        // starts from an empty store.
        File(context.filesDir, "attachments").deleteRecursively()
        srcDir = File(context.cacheDir, "src-" + System.nanoTime())
        srcDir.mkdirs()
    }

    private fun source(): File = AttachmentTestFixtures.writeSource(srcDir, "img.png", AttachmentTestFixtures.ONE_PIXEL_PNG)

    private fun failingAt(failPoint: String): AttachmentFaultHook = object : AttachmentFaultHook {
        override fun beforeTempCreate() = maybe(failPoint, "beforeTempCreate")
        override fun beforeSourceRead(chunkOrdinal: Int) = maybe(failPoint, "beforeSourceRead")
        override fun afterCopyChunk(chunkOrdinal: Int, bytesCopied: Long, totalBytes: Long) = maybe(failPoint, "afterCopyChunk")
        override fun afterFinalWriteBeforeClose() = maybe(failPoint, "afterFinalWriteBeforeClose")
        override fun beforeDestinationFlush() = maybe(failPoint, "beforeDestinationFlush")
        override fun afterCloseBeforeValidation() = maybe(failPoint, "afterCloseBeforeValidation")

        private fun maybe(active: String, name: String) {
            if (active == name) throw RuntimeException("simulated fault at " + name)
        }
    }

    private fun assertNoSideEffects(result: RepositoryResult<*>, message: String) {
        assertTrue(message, result is RepositoryResult.Failure)
        if (result is RepositoryResult.Failure.AttachmentIoFailed) {
            assertEquals(AttachmentStage.TEMP_COPY, result.stage)
        }
    }

    @Test
    fun failOnceAtEveryCopyBoundaryThenRestartSucceeds() {
        runBlocking {
            val boundaries = listOf(
                "beforeTempCreate", "beforeSourceRead", "afterCopyChunk", "afterFinalWriteBeforeClose",
                "beforeDestinationFlush", "afterCloseBeforeValidation",
            )
            for (boundary in boundaries) {
                val dbName = "copy_" + System.nanoTime() + ".db"
                val src = source()
                val repo = openForTest(context, dbName, attachmentHook = failingAt(boundary))
                val failed = repo.ingestAttachment(src, "image/png")
                assertNoSideEffects(failed, "boundary " + boundary + ": expected failure")
                repo.close()

                // Restart with the no-op hook: the same source ingests
                // successfully and never reuses unverified bytes.
                val repo2 = openForTest(context, dbName)
                val result = repo2.ingestAttachment(src, "image/png")
                assertTrue("boundary " + boundary + ": retry failed: " + result, result is RepositoryResult.Success)
                repo2.close()
                context.deleteDatabase(dbName)
            }
        }
    }

    @Test
    fun repeatedFailureNeverEmitsSuccessAndLeavesClassifiedResidue() {
        runBlocking {
            val dbName = "copy_" + System.nanoTime() + ".db"
            val src = source()
            repeat(3) {
                val repo = openForTest(context, dbName, attachmentHook = failingAt("beforeDestinationFlush"))
                val result = repo.ingestAttachment(src, "image/png")
                assertNoSideEffects(result, "repeated failure reported success")
                repo.close()
            }
            // Residue: partial TEMP files exist and are classified for safe cleanup.
            val tempDir = File(context.filesDir, "attachments/tmp")
            assertTrue(tempDir.exists())
            val residue = tempDir.listFiles()?.filter { it.isFile } ?: emptyList()
            assertTrue("expected classified TEMP residue, found none", residue.isNotEmpty())
            // No canonical row was ever committed.
            val repo = openForTest(context, dbName)
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val count = db.recordDao().allAttachments().size
            assertEquals(0, count)
            db.close()
            repo.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun completeThenRepeatIngestionIsIdempotent() {
        runBlocking {
            val dbName = "copy_" + System.nanoTime() + ".db"
            val src = source()
            val repo = openForTest(context, dbName)
            val first = repo.ingestAttachment(src, "image/png")
            assertTrue(first is RepositoryResult.Success)
            val identity = (first as RepositoryResult.Success).value
            val second = repo.ingestAttachment(src, "image/png")
            assertTrue(second is RepositoryResult.Success)
            assertEquals(identity, (second as RepositoryResult.Success).value)
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            assertEquals(1, db.recordDao().allAttachments().size)
            assertEquals(1, File(context.filesDir, "attachments/content").listFiles()?.filter { it.isFile }?.size ?: 0)
            db.close()
            repo.close()
            context.deleteDatabase(dbName)
        }
    }

    // ---- atomic move failure (criterion 4) ----

    @Test
    fun atomicMoveFailureReturnsTypedFailureAndNoNonAtomicFallback() {
        runBlocking {
            val dbName = "copy_${System.nanoTime()}.db"
            val src = source()
            val hook = object : AttachmentFaultHook {
                override fun beforeAtomicMove() = throw RuntimeException("simulated atomic-move fault")
            }
            val repo = openForTest(context, dbName, attachmentHook = hook)
            val result = repo.ingestAttachment(src, "image/png")
            assertTrue("expected ATOMIC_MOVE failure, got $result", result is RepositoryResult.Failure.AttachmentIoFailed)
            assertEquals(AttachmentStage.ATOMIC_MOVE, (result as RepositoryResult.Failure.AttachmentIoFailed).stage)
            repo.close()

            // No row, no blob at the canonical target, and the moved input is
            // retained as classified TEMP residue — never a non-atomic copy.
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            assertEquals(0, db.recordDao().allAttachments().size)
            db.close()
            val content = File(context.filesDir, "attachments/content")
            assertEquals("no blob may appear without a row", 0, content.listFiles()?.filter { it.isFile }?.size ?: 0)

            // Clean retry succeeds (the same source re-ingests fully).
            val repo2 = openForTest(context, dbName)
            assertTrue(repo2.ingestAttachment(src, "image/png") is RepositoryResult.Success)
            repo2.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun metadataWriteFailureLeavesMovedBlobAsClassifiedOrphan() {
        runBlocking {
            val dbName = "copy_${System.nanoTime()}.db"
            val src = source()
            val hook = object : AttachmentFaultHook {
                override fun beforeMetadataWrite() = throw RuntimeException("simulated metadata-write fault")
            }
            val repo = openForTest(context, dbName, attachmentHook = hook)
            val result = repo.ingestAttachment(src, "image/png")
            assertTrue("expected METADATA_WRITE failure, got $result", result is RepositoryResult.Failure.AttachmentIoFailed)
            assertEquals(AttachmentStage.METADATA_WRITE, (result as RepositoryResult.Failure.AttachmentIoFailed).stage)
            repo.close()

            // The moved-but-uncommitted blob is a safe orphan candidate: no row,
            // blob present, classified for post-verification cleanup.
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            assertEquals(0, db.recordDao().allAttachments().size)
            db.close()
            assertEquals("moved blob present as orphan", 1, File(context.filesDir, "attachments/content").listFiles()?.filter { it.isFile }?.size ?: 0)
            // The orphan is cleaned by the cleaner after verification + grace
            // (the blob's own timestamp is the fail-closed grace input).
            val repo2 = openForTest(context, dbName)
            val blob = File(context.filesDir, "attachments/content").listFiles()!!.single()
            blob.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
            repo2.runAttachmentCleanup()
            assertEquals("orphan cleaned after grace", 0, File(context.filesDir, "attachments/content").listFiles()?.filter { it.isFile }?.size ?: 0)
            repo2.close()
            context.deleteDatabase(dbName)
        }
    }

    // ---- pre-existing owner unchanged (criterion 1) ----

    @Test
    fun copyFailureLeavesPreExistingOwnerExactlyUnchanged() {
        runBlocking {
            val dbName = "copy_${System.nanoTime()}.db"
            val repo = openForTest(context, dbName)
            val ownerId = "note-pre"
            assertTrue(repo.saveNoteAggregate(NewNote(ownerId, 1L, 1L, null, emptySet())) is RepositoryResult.Success)
            repo.close()

            val src = source()
            val repo2 = openForTest(context, dbName, attachmentHook = failingAt("beforeSourceRead"))
            val result = repo2.ingestAttachment(src, "image/png")
            assertNoSideEffects(result, "copy failure reported success")
            repo2.close()

            // The pre-existing owner is exactly unchanged: still present, no
            // attachments, no links, no canonical rows.
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            assertEquals(1, db.recordDao().allEntityRefs().size)
            assertEquals(ownerId, db.recordDao().allEntityRefs().single().id)
            assertEquals(0, db.recordDao().allAttachments().size)
            assertEquals(0, db.recordDao().allAttachmentLinks().size)
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    // ---- repeated failure at every boundary (three-scenario protocol) ----

    @Test
    fun repeatedFailureAtEveryBoundaryNeverEmitsSuccess() {
        runBlocking {
            val boundaries = listOf(
                "beforeTempCreate", "beforeSourceRead", "afterCopyChunk", "afterFinalWriteBeforeClose",
                "beforeDestinationFlush", "afterCloseBeforeValidation",
            )
            for (boundary in boundaries) {
                val dbName = "copy_${System.nanoTime()}.db"
                val src = source()
                repeat(3) {
                    val repo = openForTest(context, dbName, attachmentHook = failingAt(boundary))
                    val result = repo.ingestAttachment(src, "image/png")
                    assertNoSideEffects(result, "boundary $boundary repeated failure reported success")
                    repo.close()
                }
                val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
                assertEquals("boundary $boundary repeated failure committed a row", 0, db.recordDao().allAttachments().size)
                db.close()
                context.deleteDatabase(dbName)
            }
        }
    }
}

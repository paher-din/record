package com.paherdin.record.core.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.AttachmentCleaner
import com.paherdin.record.core.data.AttachmentContentStore
import com.paherdin.record.core.data.AttachmentFaultHook
import com.paherdin.record.core.data.AttachmentHashLockRegistry
import com.paherdin.record.core.data.AttachmentIo
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.openForTest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Criterion 5/8 — the cleaner: zero-link rows past the 7-day grace period are
 * removed row-and-blob together under per-hash serialization; the expected
 * missing-blob window completes metadata deletion only; physical-delete
 * failure retains the row for retry; linked content is never deleted; orphan
 * blobs and TEMP residue are grace-gated with fail-closed timestamps; and
 * cleanup racing a relink preserves the new link and the bytes.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentOrphanCleanupTest {

    private lateinit var context: Context
    private lateinit var srcDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        srcDir = File(context.cacheDir, "clean-" + System.nanoTime())
        srcDir.mkdirs()
        File(context.filesDir, "attachments").deleteRecursively()
    }

    /** A cleaner with an injectable clock over the shared test store/dao. */
    private fun cleaner(store: AttachmentContentStore, dao: RecordDao, clock: AtomicLong, hook: AttachmentFaultHook = AttachmentFaultHook.NO_OP) =
        AttachmentCleaner(store, AttachmentHashLockRegistry(), dao, { clock.get() }, hook = hook)

    private fun source(name: String) = AttachmentTestFixtures.writeSource(srcDir, name, AttachmentTestFixtures.ONE_PIXEL_PNG)

    private suspend fun ingestInto(repo: com.paherdin.record.core.data.RecordRepository, name: String): String =
        (repo.ingestAttachment(source(name), "image/png") as RepositoryResult.Success).value

    @Test
    fun gracePeriodBoundaryIsExact() {
        runBlocking {
            val dbName = "clean_bound_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            val identity = ingestInto(repo, "b.png")
            val noteId = "note-bound"
            repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet(), setOf(identity)))
            repo.unlinkAttachment(noteId, identity)
            repo.close()

            val clock = AtomicLong(System.currentTimeMillis())
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val row = db.recordDao().allAttachments().single()
            val c = cleaner(AttachmentContentStore(context.filesDir, AttachmentIo()), db.recordDao(), clock)
            // Exactly at the grace boundary: NOT yet eligible.
            clock.set(row.deletedAt!! + AttachmentCleaner.GRACE_PERIOD_MILLIS - 1)
            assertEquals("one ms before the boundary must not clean", 0, c.run().rowsCleaned)
            // At the boundary: eligible.
            clock.set(row.deletedAt!! + AttachmentCleaner.GRACE_PERIOD_MILLIS)
            val report = c.run()
            assertEquals("at the boundary the row must clean", 1, report.rowsCleaned)
            assertEquals(0, db.recordDao().allAttachments().size)
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun zeroLinkRowAndBlobAreRemovedTogetherAfterGrace() {
        runBlocking {
            val dbName = "clean_row_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            val identity = ingestInto(repo, "c.png")
            val noteId = "note-row"
            repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet(), setOf(identity)))
            repo.unlinkAttachment(noteId, identity)
            repo.close()

            val clock = AtomicLong(System.currentTimeMillis())
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val row = db.recordDao().allAttachments().single()
            val blob = File(context.filesDir, "attachments/content/" + identity)
            assertTrue(blob.isFile)
            clock.set(row.deletedAt!! + AttachmentCleaner.GRACE_PERIOD_MILLIS)

            val c = cleaner(AttachmentContentStore(context.filesDir, AttachmentIo()), db.recordDao(), clock)
            val report = c.run()
            assertEquals(1, report.rowsCleaned)
            assertEquals("row and blob removed together", 0, db.recordDao().allAttachments().size)
            assertTrue("blob physically absent", !blob.exists())
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun expectedMissingBlobWindowCompletesMetadataDeletion() {
        runBlocking {
            val dbName = "clean_win_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            val identity = ingestInto(repo, "d.png")
            val noteId = "note-win"
            repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet(), setOf(identity)))
            repo.unlinkAttachment(noteId, identity)
            repo.close()

            // Simulate the crash window: blob absent, metadata present.
            val blob = File(context.filesDir, "attachments/content/" + identity)
            assertTrue(blob.delete())
            val clock = AtomicLong(System.currentTimeMillis())
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val row = db.recordDao().allAttachments().single()
            clock.set(row.deletedAt!! + AttachmentCleaner.GRACE_PERIOD_MILLIS)

            val c = cleaner(AttachmentContentStore(context.filesDir, AttachmentIo()), db.recordDao(), clock)
            val report = c.run()
            assertEquals("expected window completes metadata deletion", 1, report.rowsCleaned)
            assertEquals("not counted as corruption", 0, db.recordDao().allAttachments().size)
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun physicalDeleteFailureRetainsRowForRetry() {
        runBlocking {
            val dbName = "clean_retry_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            val identity = ingestInto(repo, "e.png")
            val noteId = "note-retry"
            repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet(), setOf(identity)))
            repo.unlinkAttachment(noteId, identity)
            repo.close()

            val clock = AtomicLong(System.currentTimeMillis())
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val row = db.recordDao().allAttachments().single()
            clock.set(row.deletedAt!! + AttachmentCleaner.GRACE_PERIOD_MILLIS)
            val failing = object : AttachmentFaultHook {
                override fun beforePhysicalDelete() = throw RuntimeException("simulated delete failure")
            }
            val c = cleaner(AttachmentContentStore(context.filesDir, AttachmentIo()), db.recordDao(), clock, failing)
            // Repeated failure: the row is retained every time.
            repeat(3) {
                assertEquals(0, c.run().rowsCleaned)
                assertEquals(1, db.recordDao().allAttachments().size)
                assertTrue(File(context.filesDir, "attachments/content/" + identity).isFile)
            }
            // Clean retry (no-op hook) succeeds idempotently.
            val c2 = cleaner(AttachmentContentStore(context.filesDir, AttachmentIo()), db.recordDao(), clock)
            assertEquals(1, c2.run().rowsCleaned)
            assertEquals(0, db.recordDao().allAttachments().size)
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun linkedContentIsNeverDeleted() {
        runBlocking {
            val dbName = "clean_linked_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            val identity = ingestInto(repo, "f.png")
            val noteId = "note-linked"
            repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet(), setOf(identity)))
            repo.close()

            val clock = AtomicLong(System.currentTimeMillis())
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val row = db.recordDao().allAttachments().single()
            // Even with a very old marker (should not exist for active rows), the
            // cleaner must never delete linked content.
            db.recordDao().updateAttachmentDeletedAt(row.id, clock.get() - 100L * AttachmentCleaner.GRACE_PERIOD_MILLIS)
            val c = cleaner(AttachmentContentStore(context.filesDir, AttachmentIo()), db.recordDao(), clock)
            val report = c.run()
            assertEquals(0, report.rowsCleaned)
            assertEquals(1, db.recordDao().allAttachments().size)
            assertTrue(File(context.filesDir, "attachments/content/" + identity).isFile)
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun orphanBlobAndTempResidueAreGraceGatedWithFailClosedTimestamps() {
        runBlocking {
            val dbName = "clean_orphan_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            repo.close()

            val store = AttachmentContentStore(context.filesDir, AttachmentIo())
            val clock = AtomicLong(System.currentTimeMillis())
            // Old orphan blob (past grace): cleaned. Fresh orphan: retained.
            val oldBlob = File(store.contentDir, "a".repeat(64))
            oldBlob.writeBytes("old orphan".toByteArray())
            oldBlob.setLastModified(clock.get() - 2 * AttachmentCleaner.GRACE_PERIOD_MILLIS)
            val freshBlob = File(store.contentDir, "b".repeat(64))
            freshBlob.writeBytes("fresh orphan".toByteArray())
            freshBlob.setLastModified(clock.get())
            // Old TEMP residue: cleaned.
            val oldTemp = File(store.tempDir, "old-temp")
            oldTemp.writeBytes("residue".toByteArray())
            oldTemp.setLastModified(clock.get() - 2 * AttachmentCleaner.GRACE_PERIOD_MILLIS)
            // Invalid timestamp (0): fail closed, retained.
            val invalidTemp = File(store.tempDir, "invalid-temp")
            invalidTemp.writeBytes("residue".toByteArray())
            invalidTemp.setLastModified(0L)

            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val c = cleaner(store, db.recordDao(), clock)
            val report = c.run()
            assertEquals(1, report.orphanBlobsCleaned)
            assertEquals(1, report.tempResidueCleaned)
            assertEquals(1, report.failedClosed)
            assertTrue(!oldBlob.exists())
            assertTrue(freshBlob.exists())
            assertTrue(!oldTemp.exists())
            assertTrue(invalidTemp.exists())
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun cleanupRacingRelinkPreservesTheNewLinkAndBytes() {
        runBlocking {
            val dbName = "clean_race_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            val identity = ingestInto(repo, "g.png")
            val noteId = "note-race"
            repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet(), setOf(identity)))
            repo.unlinkAttachment(noteId, identity)
            repo.close()

            // Age the marker so the repository's real-clock cleaner is eligible,
            // then race the cleaner against a relink on the SAME repository (the
            // same per-hash lock registry serializes them).
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val row = db.recordDao().allAttachments().single()
            db.recordDao().updateAttachmentDeletedAt(
                row.id,
                System.currentTimeMillis() - AttachmentCleaner.GRACE_PERIOD_MILLIS - 1,
            )
            db.close()

            val repo2 = openForTest(context, dbName)
            val results = coroutineScope {
                val cleanup = async { repo2.runAttachmentCleanup() }
                val relink = async {
                    delay(5)
                    repo2.linkAttachment(noteId, identity)
                }
                cleanup.await() to relink.await()
            }
            val (report, relinkResult) = results
            repo2.close()

            // Either the relink won (link present, bytes present) or the cleanup
            // won before the link committed (row+blob gone) — but never a
            // committed link to missing bytes and never a deleted row with a link.
            val db2 = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val rows = db2.recordDao().allAttachments()
            val links = db2.recordDao().allAttachmentLinks()
            if (relinkResult is RepositoryResult.Success) {
                assertEquals("relink won: one row", 1, rows.size)
                assertEquals("relink won: one link", 1, links.size)
                assertEquals("relink won: marker cleared", null, rows.single().deletedAt)
                assertTrue("relink won: bytes present", File(context.filesDir, "attachments/content/" + identity).isFile)
            } else {
                assertEquals("cleanup won: no rows", 0, rows.size)
                assertEquals("cleanup won: no links", 0, links.size)
            }
            db2.close()
            context.deleteDatabase(dbName)
        }
    }
}

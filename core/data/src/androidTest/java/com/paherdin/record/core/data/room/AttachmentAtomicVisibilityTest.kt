package com.paherdin.record.core.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.TransactionFaultHook
import com.paherdin.record.core.data.openForTest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Criterion 4 — owner-save atomicity with attachments: an external reader
 * observes only the old complete owner state or the new complete
 * owner-with-links state, never a partial link set; an attachment failure
 * rejects the save with nothing committed (the owner does not exist either);
 * and a failed owner save leaves the staged blob as a classified orphan.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentAtomicVisibilityTest {

    private lateinit var context: Context
    private lateinit var srcDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        srcDir = File(context.cacheDir, "atom-" + System.nanoTime())
        srcDir.mkdirs()
        File(context.filesDir, "attachments").deleteRecursively()
    }

    @Test
    fun failedOwnerSaveCommitsNothingIncludingOwner() {
        runBlocking {
            val dbName = "atom_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            // Unknown content identity rejects the whole save: neither the
            // owner nor any link may exist.
            val noteId = "note-fail"
            val result = repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet(), setOf("deadbeef".repeat(8))))
            assertTrue("expected rejection, got $result", result is RepositoryResult.Failure.AttachmentRejected)
            repo.close()
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            assertEquals(0, db.recordDao().allEntityRefs().size)
            assertEquals(0, db.recordDao().allAttachmentLinks().size)
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun externalReaderNeverObservesPartialLinkSet() {
        runBlocking {
            val dbName = "atom_" + System.nanoTime() + ".db"
            val latch = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pausingHook = object : TransactionFaultHook {
                override fun afterChildWrite(childIndex: Int) {
                    if (childIndex == 3) { // attachment stage of the note save
                        latch.countDown()
                        try {
                            check(release.await(15, TimeUnit.SECONDS)) { "writer release timed out" }
                        } catch (e: InterruptedException) {
                            throw RuntimeException(e)
                        }
                        throw RuntimeException("simulated fault after attachment link")
                    }
                }
            }
            val repo = openForTest(context, dbName, hook = pausingHook)
            val src = AttachmentTestFixtures.writeSource(srcDir, "a.png", AttachmentTestFixtures.ONE_PIXEL_PNG)
            val identity = (repo.ingestAttachment(src, "image/png") as RepositoryResult.Success).value
            val noteId = "note-vis"

            // runBlocking's own event loop is single-threaded: the writer must
            // run on a real dispatcher or the blocking latch.await would starve it.
            val writer = launch(kotlinx.coroutines.Dispatchers.Default) {
                repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet(), setOf(identity)))
            }
            check(latch.await(15, TimeUnit.SECONDS)) { "writer never reached the attachment stage" }

            // While the writer is paused mid-transaction, an external reader on
            // its own connection sees only the old complete state (no owner).
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val refs = db.recordDao().allEntityRefs()
            assertEquals("reader observed partial owner", 0, refs.size)
            assertEquals("reader observed partial link", 0, db.recordDao().allAttachmentLinks().size)
            db.close()

            release.countDown()
            val result = writer.join()
            val finalDb = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            assertEquals("failed save must not commit the owner", 0, finalDb.recordDao().allEntityRefs().size)
            assertEquals("failed save must not commit links", 0, finalDb.recordDao().allAttachmentLinks().size)
            // The zero-link canonical row survives with its cleanup marker.
            val row = finalDb.recordDao().allAttachments().single()
            assertTrue("staged content stays cleanup-pending", row.deletedAt != null)
            finalDb.close()
            repo.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun successfulOwnerSaveWithLinksIsFullyVisibleAfterCommit() {
        runBlocking {
            val dbName = "atom_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            val src = AttachmentTestFixtures.writeSource(srcDir, "b.png", AttachmentTestFixtures.ONE_PIXEL_PNG)
            val identity = (repo.ingestAttachment(src, "image/png") as RepositoryResult.Success).value
            val noteId = "note-ok"
            assertTrue(repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet(), setOf(identity))) is RepositoryResult.Success)
            repo.close()

            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val row = db.recordDao().allAttachments().single()
            assertEquals(1, db.recordDao().countAttachmentLinks(row.id))
            assertEquals(null, row.deletedAt)
            assertEquals(1, db.recordDao().allEntityRefs().size)
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}

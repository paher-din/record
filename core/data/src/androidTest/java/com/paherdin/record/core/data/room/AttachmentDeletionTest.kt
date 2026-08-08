package com.paherdin.record.core.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.AttachmentFaultHook
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
 * Criterion 5 — unlink, deletion, and cleanup entry: unlinking one owner
 * while another remains linked leaves the shared canonical fact and blob
 * active; unlinking the last owner marks cleanup-pending in the same
 * transaction; failure injected inside the unlink transaction leaves no
 * partial link removal and no inconsistent deletion marker; the expected
 * missing-blob window and physical deletion retry belong to the cleaner
 * (Task 7).
 */
@RunWith(AndroidJUnit4::class)
class AttachmentDeletionTest {

    private lateinit var context: Context
    private lateinit var srcDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        srcDir = File(context.cacheDir, "del-" + System.nanoTime())
        srcDir.mkdirs()
        File(context.filesDir, "attachments").deleteRecursively()
    }

    /** Creates a Note owner linked to the fixture image; returns (repo, dbName, noteId, identity). */
    private suspend fun linkedNote(tag: String): LinkedOwner {
        val dbName = "del_" + tag + "_" + System.nanoTime() + ".db"
        val repo = openForTest(context, dbName)
        val src = AttachmentTestFixtures.writeSource(srcDir, tag + ".png", AttachmentTestFixtures.ONE_PIXEL_PNG)
        val identity = (repo.ingestAttachment(src, "image/png") as RepositoryResult.Success).value
        val noteId = "note-" + tag
        assertTrue(repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet(), setOf(identity))) is RepositoryResult.Success)
        return LinkedOwner(repo, dbName, noteId, identity)
    }

    @Test
    fun unlinkOneOfTwoOwnersLeavesSharedContentActive() {
        runBlocking {
            // Both owners share ONE database so they link the same canonical row.
            val dbName = "del_share_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            val src = AttachmentTestFixtures.writeSource(srcDir, "share.png", AttachmentTestFixtures.ONE_PIXEL_PNG)
            val identity = (repo.ingestAttachment(src, "image/png") as RepositoryResult.Success).value
            val noteA = "note-share-a"
            val noteB = "note-share-b"
            assertTrue(repo.saveNoteAggregate(NewNote(noteA, 1L, 1L, null, emptySet(), setOf(identity))) is RepositoryResult.Success)
            assertTrue(repo.saveNoteAggregate(NewNote(noteB, 1L, 1L, null, emptySet(), setOf(identity))) is RepositoryResult.Success)

            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val row = db.recordDao().allAttachments().single()
            assertEquals(2, db.recordDao().countAttachmentLinks(row.id))
            db.close()

            assertTrue(repo.unlinkAttachment(noteA, identity) is RepositoryResult.Success)
            val db2 = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val row2 = db2.recordDao().allAttachments().single()
            assertEquals("one owner must remain linked", 1, db2.recordDao().countAttachmentLinks(row2.id))
            assertEquals("shared content must stay active", null, row2.deletedAt)
            val blob = File(context.filesDir, "attachments/content/" + identity)
            assertTrue("the shared blob must remain", blob.isFile)
            db2.close()
            repo.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun lastUnlinkMarksCleanupPendingInSameTransaction() {
        runBlocking {
            val a = linkedNote("c")
            assertTrue(a.repo.unlinkAttachment(a.noteId, a.identity) is RepositoryResult.Success)
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, a.dbName).build()
            val row = db.recordDao().allAttachments().single()
            assertEquals(0, db.recordDao().countAttachmentLinks(row.id))
            assertTrue("last unlink must mark cleanup-pending", row.deletedAt != null)
            db.close()
            a.repo.close()
            context.deleteDatabase(a.dbName)
        }
    }

    @Test
    fun failureInsideUnlinkTransactionLeavesNoPartialState() {
        runBlocking {
            val dbName = "del_fault_" + System.nanoTime() + ".db"
            val hook = object : AttachmentFaultHook {
                override fun beforeUnlinkCommit() = throw RuntimeException("simulated unlink fault")
            }
            val repo = openForTest(context, dbName, attachmentHook = hook)
            val src = AttachmentTestFixtures.writeSource(srcDir, "d.png", AttachmentTestFixtures.ONE_PIXEL_PNG)
            val identity = (repo.ingestAttachment(src, "image/png") as RepositoryResult.Success).value
            val noteId = "note-fault"
            repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet(), setOf(identity)))
            // The hook fires at beforeUnlinkCommit only; the initial save used
            // the same hook but no unlink ran, so the link committed normally.
            // Now unlink with the fault: the transaction rolls back entirely.
            // Re-open without the hook to drive the unlink cleanly after the
            // failed attempt below.
            repo.close()

            val repo2 = openForTest(context, dbName, attachmentHook = hook)
            val result = repo2.unlinkAttachment(noteId, identity)
            assertTrue("expected failure, got $result", result is RepositoryResult.Failure)
            repo2.close()

            // The failed unlink left the link intact and no deletion marker.
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val row = db.recordDao().allAttachments().single()
            assertEquals("link must survive the failed unlink", 1, db.recordDao().countAttachmentLinks(row.id))
            assertEquals("no inconsistent deletion marker", null, row.deletedAt)
            db.close()

            // The same unlink on a clean repo succeeds (retry without reusing state).
            val repo3 = openForTest(context, dbName)
            assertTrue(repo3.unlinkAttachment(noteId, identity) is RepositoryResult.Success)
            repo3.close()
            val db2 = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val row2 = db2.recordDao().allAttachments().single()
            assertEquals(0, db2.recordDao().countAttachmentLinks(row2.id))
            assertTrue(row2.deletedAt != null)
            db2.close()
            context.deleteDatabase(dbName)
        }
    }

    data class LinkedOwner(val repo: com.paherdin.record.core.data.RecordRepository, val dbName: String, val noteId: String, val identity: String)
}

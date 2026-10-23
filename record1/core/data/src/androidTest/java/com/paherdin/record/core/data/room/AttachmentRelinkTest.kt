package com.paherdin.record.core.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.AttachmentViolation
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
 * Criterion 6 — relink: a link request on zero-link pending content rechecks
 * the target, verifies the actual bytes, cancels pending deletion, and reuses
 * the same canonical ID; a relink whose target blob is missing re-ingests
 * from a validated temporary file or fails without committing a link to
 * missing content; cleanup racing a relink preserves the new link and bytes
 * (the race itself is exercised with the cleaner in Task 7).
 */
@RunWith(AndroidJUnit4::class)
class AttachmentRelinkTest {

    private lateinit var context: Context
    private lateinit var srcDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        srcDir = File(context.cacheDir, "relink-" + System.nanoTime())
        srcDir.mkdirs()
        File(context.filesDir, "attachments").deleteRecursively()
    }

    private suspend fun pendingOwner(tag: String): Pending {
        val dbName = "relink_" + tag + "_" + System.nanoTime() + ".db"
        val repo = openForTest(context, dbName)
        val src = AttachmentTestFixtures.writeSource(srcDir, tag + ".png", AttachmentTestFixtures.ONE_PIXEL_PNG)
        val identity = (repo.ingestAttachment(src, "image/png") as RepositoryResult.Success).value
        val noteId = "note-" + tag
        repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet(), setOf(identity)))
        // Unlink to enter zero-link cleanup-pending.
        assertTrue(repo.unlinkAttachment(noteId, identity) is RepositoryResult.Success)
        return Pending(repo, dbName, noteId, identity)
    }

    @Test
    fun relinkCancelsPendingDeletionAndReusesCanonicalId() {
        runBlocking {
            val p = pendingOwner("a")
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, p.dbName).build()
            val pendingRow = db.recordDao().allAttachments().single()
            assertTrue("row must be cleanup-pending before relink", pendingRow.deletedAt != null)
            db.close()

            assertTrue(p.repo.linkAttachment(p.noteId, p.identity) is RepositoryResult.Success)
            val db2 = Room.databaseBuilder(context, RecordDatabase::class.java, p.dbName).build()
            val row = db2.recordDao().allAttachments().single()
            assertEquals("canonical ID must be reused", pendingRow.id, row.id)
            assertEquals("pending deletion cancelled", null, row.deletedAt)
            assertEquals(1, db2.recordDao().countAttachmentLinks(row.id))
            db2.close()
            p.repo.close()
            context.deleteDatabase(p.dbName)
        }
    }

    @Test
    fun relinkWithMissingBlobFailsWithoutCommittingLinkToMissingContent() {
        runBlocking {
            val p = pendingOwner("b")
            // Simulate the expected cleanup window: the blob is gone.
            val blob = File(context.filesDir, "attachments/content/" + p.identity)
            assertTrue(blob.delete())

            val result = p.repo.linkAttachment(p.noteId, p.identity)
            assertTrue("expected CONTENT_NOT_AVAILABLE, got $result", result is RepositoryResult.Failure.AttachmentRejected)
            assertEquals(
                AttachmentViolation.CONTENT_NOT_AVAILABLE,
                (result as RepositoryResult.Failure.AttachmentRejected).violation,
            )
            // No link committed against missing bytes.
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, p.dbName).build()
            assertEquals(0, db.recordDao().allAttachmentLinks().size)
            assertTrue("row stays cleanup-pending", db.recordDao().allAttachments().single().deletedAt != null)
            db.close()

            // Fresh validated ingestion of the same bytes restores the pending
            // zero-link target; then the link succeeds (ADR 0008 restore path).
            val src = AttachmentTestFixtures.writeSource(srcDir, "b2.png", AttachmentTestFixtures.ONE_PIXEL_PNG)
            val restored = p.repo.ingestAttachment(src, "image/png")
            assertTrue("re-ingestion must restore the identity: $restored", restored is RepositoryResult.Success)
            assertEquals(p.identity, (restored as RepositoryResult.Success).value)
            assertTrue(p.repo.linkAttachment(p.noteId, p.identity) is RepositoryResult.Success)
            val db2 = Room.databaseBuilder(context, RecordDatabase::class.java, p.dbName).build()
            val row = db2.recordDao().allAttachments().single()
            assertEquals(null, row.deletedAt)
            assertEquals(1, db2.recordDao().countAttachmentLinks(row.id))
            db2.close()
            p.repo.close()
            context.deleteDatabase(p.dbName)
        }
    }

    data class Pending(val repo: com.paherdin.record.core.data.RecordRepository, val dbName: String, val noteId: String, val identity: String)
}

package com.paherdin.record.core.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.openForTest
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Criterion 6/4.1 endpoint rules: link/unlink against a nonexistent or
 * non-Transaction/Note EntityRef is `EndpointRejected` with nothing
 * committed; both owner types (Transaction and Note) link and unlink through
 * the public surface (spec 4.1, ADR 0002).
 */
@RunWith(AndroidJUnit4::class)
class AttachmentEndpointTest {

    private lateinit var context: Context
    private lateinit var srcDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        srcDir = File(context.cacheDir, "endpoint-" + System.nanoTime())
        srcDir.mkdirs()
        File(context.filesDir, "attachments").deleteRecursively()
    }

    @Test
    fun linkToNonexistentOwnerIsEndpointRejected() {
        runBlocking {
            val dbName = "ep_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            val src = AttachmentTestFixtures.writeSource(srcDir, "a.png", AttachmentTestFixtures.ONE_PIXEL_PNG)
            val identity = (repo.ingestAttachment(src, "image/png") as RepositoryResult.Success).value

            val result = repo.linkAttachment("no-such-owner", identity)
            assertTrue("expected EndpointRejected, got $result", result == RepositoryResult.Failure.EndpointRejected)
            repo.close()
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            assertEquals(0, db.recordDao().allAttachmentLinks().size)
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun linkWithUnknownContentIdentityIsRejected() {
        runBlocking {
            val dbName = "ep_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            val noteId = "note-1"
            repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet()))
            val result = repo.linkAttachment(noteId, "f".repeat(64))
            assertTrue("expected CONTENT_IDENTITY_UNKNOWN, got $result", result is RepositoryResult.Failure.AttachmentRejected)
            assertEquals(
                com.paherdin.record.core.data.AttachmentViolation.CONTENT_IDENTITY_UNKNOWN,
                (result as RepositoryResult.Failure.AttachmentRejected).violation,
            )
            repo.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun noteOwnerLinksAndUnlinksThroughPublicSurface() {
        runBlocking {
            val dbName = "ep_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            val noteId = "note-2"
            assertTrue(repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet())) is RepositoryResult.Success)
            val src = AttachmentTestFixtures.writeSource(srcDir, "b.png", AttachmentTestFixtures.ONE_PIXEL_PNG)
            val identity = (repo.ingestAttachment(src, "image/png") as RepositoryResult.Success).value

            assertTrue(repo.linkAttachment(noteId, identity) is RepositoryResult.Success)
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val rows = db.recordDao().allAttachments().single()
            assertEquals(1, db.recordDao().countAttachmentLinks(rows.id))
            assertEquals(null, rows.deletedAt) // marker cleared by the verified link
            db.close()

            assertTrue(repo.unlinkAttachment(noteId, identity) is RepositoryResult.Success)
            val db2 = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val row2 = db2.recordDao().allAttachments().single()
            assertEquals(0, db2.recordDao().countAttachmentLinks(row2.id))
            assertTrue("last unlink must mark cleanup-pending", row2.deletedAt != null)
            db2.close()
            repo.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun transactionOwnerLinksThroughOwnerSave() {
        runBlocking {
            val dbName = "ep_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            repo.createAccount(com.paherdin.record.core.data.NewAccount("acc-1", "Wallet", "CNY"))
            val src = AttachmentTestFixtures.writeSource(srcDir, "c.png", AttachmentTestFixtures.ONE_PIXEL_PNG)
            val identity = (repo.ingestAttachment(src, "image/png") as RepositoryResult.Success).value
            val tx = NewTransaction(
                id = "tx-1",
                type = TransactionType.EXPENSE,
                createdAt = 1L,
                updatedAt = 1L,
                deletedAt = null,
                entries = listOf(com.paherdin.record.core.data.NewAccountEntry("e1", "acc-1", -100L)),
                relationNoteIds = emptySet(),
                attachmentContentIds = setOf(identity),
            )
            assertTrue("owner save with attachment failed: $tx", repo.saveTransactionAggregate(tx) is RepositoryResult.Success)
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val row = db.recordDao().allAttachments().single()
            assertEquals(1, db.recordDao().countAttachmentLinks(row.id))
            assertEquals(null, row.deletedAt)
            db.close()
            repo.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun unlinkOfMissingLinkIsIdempotentSuccess() {
        runBlocking {
            val dbName = "ep_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            val noteId = "note-3"
            repo.saveNoteAggregate(NewNote(noteId, 1L, 1L, null, emptySet()))
            assertTrue(repo.unlinkAttachment(noteId, "a".repeat(64)) is RepositoryResult.Success)
            repo.close()
            context.deleteDatabase(dbName)
        }
    }
}

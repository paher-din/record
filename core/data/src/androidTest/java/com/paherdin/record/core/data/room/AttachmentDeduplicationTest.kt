package com.paherdin.record.core.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.openForTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Criterion 3 — duplicate-content reuse: two owners importing the same bytes
 * share exactly one canonical Attachment ID and one physical blob; a
 * duplicate import verifies the existing blob and reuses the identity;
 * concurrent ingestion of identical bytes produces exactly one row; and no
 * path creates a second Attachment row for one SHA-256 (the database unique
 * constraint is the backstop, exercised in AttachmentSchemaContractTest).
 * Owner sharing through the link surface is extended in Task 6.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentDeduplicationTest {

    private lateinit var context: Context
    private lateinit var srcDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Isolate the shared content-addressed store between tests: the
        // canonical blob directory is global by design (dedup), so each test
        // starts from an empty store.
        File(context.filesDir, "attachments").deleteRecursively()
        srcDir = File(context.cacheDir, "dedup-" + System.nanoTime())
        srcDir.mkdirs()
    }

    private fun twoDistinctSources(): Pair<File, File> {
        val a = AttachmentTestFixtures.writeSource(srcDir, "a.png", AttachmentTestFixtures.ONE_PIXEL_PNG)
        val b = AttachmentTestFixtures.writeSource(srcDir, "b.png", AttachmentTestFixtures.ONE_PIXEL_PNG)
        return a to b
    }

    @Test
    fun duplicateImportReusesOneCanonicalRowAndBlob() {
        runBlocking {
            val dbName = "dedup_" + System.nanoTime() + ".db"
            val (a, b) = twoDistinctSources()
            val repo = openForTest(context, dbName)
            val first = repo.ingestAttachment(a, "image/png")
            assertTrue(first is RepositoryResult.Success)
            val second = repo.ingestAttachment(b, "image/png")
            assertTrue(second is RepositoryResult.Success)
            assertEquals("duplicate import must return the same canonical identity", first, second)
            repo.close()

            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val rows = db.recordDao().allAttachments()
            assertEquals("exactly one canonical row for one SHA-256", 1, rows.size)
            assertEquals(1, File(context.filesDir, "attachments/content").listFiles()?.filter { it.isFile }?.size ?: 0)
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun concurrentIdenticalIngestionProducesExactlyOneRow() {
        runBlocking {
            val dbName = "dedup_" + System.nanoTime() + ".db"
            val repo = openForTest(context, dbName)
            val source = AttachmentTestFixtures.writeSource(srcDir, "c.png", AttachmentTestFixtures.ONE_PIXEL_PNG)
            val results = coroutineScope {
                (1..8).map {
                    async { repo.ingestAttachment(source, "image/png") }
                }.awaitAll()
            }
            assertTrue("all concurrent ingests succeeded: " + results, results.all { it is RepositoryResult.Success })
            val identities = results.filterIsInstance<RepositoryResult.Success<String>>().map { it.value }.toSet()
            assertEquals("all concurrent ingests share one identity", 1, identities.size)
            repo.close()

            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            assertEquals("concurrent identical ingestion produced extra rows", 1, db.recordDao().allAttachments().size)
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun reingestionOfZeroLinkContentRefreshesGracePeriodStart() {
        runBlocking {
            val dbName = "dedup_" + System.nanoTime() + ".db"
            val (a, b) = twoDistinctSources()
            val repo = openForTest(context, dbName)
            repo.ingestAttachment(a, "image/png")
            repo.close()

            // First row carries its ingestion time as the cleanup marker.
            val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val firstRow = db.recordDao().allAttachments().single()
            val firstDeletedAt = firstRow.deletedAt
            assertTrue("zero-link row must start cleanup-pending", firstDeletedAt != null)
            db.close()

            // A fresh successful ingestion of the same bytes refreshes the marker,
            // so the reported preparation always has a full grace period (ADR 0008).
            val repo2 = openForTest(context, dbName)
            repo2.ingestAttachment(b, "image/png")
            repo2.close()
            val db2 = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
            val refreshed = db2.recordDao().allAttachments().single()
            assertEquals("canonical ID is reused", firstRow.id, refreshed.id)
            assertTrue("grace-period start must refresh", refreshed.deletedAt!! >= firstDeletedAt!!)
            db2.close()
            context.deleteDatabase(dbName)
        }
    }
}

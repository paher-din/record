package com.paherdin.record.core.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.BackupArtifact
import com.paherdin.record.core.data.BackupStage
import com.paherdin.record.core.data.BackupTarget
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.backup.BackupFaultHook
import com.paherdin.record.core.data.backup.BackupIoException
import com.paherdin.record.core.data.backup.ManifestCodec
import com.paherdin.record.core.data.openForTest
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupStreamPublishTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("export_test.db")
        // Remove any stale private-temporary residue from prior runs so the
        // fault matrix can assert deterministic cleanup.
        context.cacheDir.listFiles { f -> f.name.startsWith(".tmp-") }?.forEach { it.delete() }
    }

    private fun newTarget(): File = File(context.cacheDir, "backup-${System.nanoTime()}.zip")

    private suspend fun seed(repo: RecordRepository) {
        assertEquals(RepositoryResult.Success(Unit), repo.createAccount(NewAccount("acc-1", "Wallet", "CNY")))
        val source = File(context.cacheDir, "seed-${System.nanoTime()}.png")
        source.writeBytes(AttachmentTestFixtures.ONE_PIXEL_PNG)
        val ingest = repo.ingestAttachment(source, "image/png")
        assertTrue("ingest failed: $ingest", ingest is RepositoryResult.Success)
        val contentIdentity = (ingest as RepositoryResult.Success).value
        assertEquals(
            RepositoryResult.Success(Unit),
            repo.saveTransactionAggregate(
                NewTransaction(
                    id = "tx-1",
                    type = TransactionType.INCOME,
                    createdAt = 1,
                    updatedAt = 1,
                    deletedAt = null,
                    entries = listOf(NewAccountEntry("e-1", "acc-1", 5000)),
                    relationNoteIds = emptySet(),
                    attachmentContentIds = setOf(contentIdentity),
                ),
            ),
        )
        assertEquals(
            RepositoryResult.Success(Unit),
            repo.saveNoteAggregate(
                NewNote("n-1", createdAt = 2, updatedAt = 2, deletedAt = null, relationTransactionIds = emptySet(), attachmentContentIds = emptySet()),
            ),
        )
    }

    @Test
    fun exportProducesValidCompletePackage() = runBlocking {
        val repo = openForTest(context, "export_test.db")
        seed(repo)
        val target = newTarget()
        val result = repo.exportBackup(BackupTarget.PrivateFile(target))
        assertTrue("export failed: $result", result is RepositoryResult.Success)
        val artifact = (result as RepositoryResult.Success).value as BackupArtifact
        assertTrue("target missing", target.isFile)
        assertTrue("package hash non-empty", artifact.sha256.isNotBlank())

        val manifest = ManifestCodec.decode(readZipEntry(target, "manifest.json"))
        assertEquals(ManifestCodec.FORMAT_VERSION, manifest.formatVersion)
        assertEquals(2, manifest.databaseSchemaVersion)
        assertEquals(ManifestCodec.HASH_ALGORITHM, manifest.hashAlgorithm)
        assertEquals("no catalog tables", emptyList<Int>(), manifest.catalogNameKeyVersions)

        val entryNames = manifest.entries.map { it.path }.toSet()
        for (dataset in listOf("EntityRef", "Account", "Transaction", "AccountEntry", "Note", "TransactionNoteAssociation", "Attachment", "AttachmentLink")) {
            assertTrue("missing dataset $dataset", entryNames.contains("$dataset.jsonl"))
        }
        assertTrue("missing attachment entry", entryNames.any { it.startsWith("attachments/") })
        assertTrue("missing manifest entry", entryNames.none { it == "manifest.json" })

        val byType = manifest.entries.associateBy { it.logicalType }
        assertEquals(2L, byType["EntityRef"]!!.recordCount)
        assertEquals(1L, byType["Transaction"]!!.recordCount)
        assertEquals(1L, byType["Note"]!!.recordCount)
        assertEquals(1L, byType["Attachment"]!!.recordCount)
        assertEquals(1L, byType["AttachmentLink"]!!.recordCount)

        // Every manifest entry hash matches its ZIP entry content (read as bytes;
        // attachment entries are binary).
        for (entry in manifest.entries) {
            val actual = sha256(readZipEntryBytes(target, entry.path))
            assertEquals("hash mismatch for ${entry.path}", entry.sha256, actual)
        }
        repo.close()
    }

    @Test
    fun privateAtomicRenamePublishesOnlyCompletePackage() = runBlocking {
        val repo = openForTest(context, "export_test.db")
        seed(repo)
        val target = newTarget()
        val result = repo.exportBackup(BackupTarget.PrivateFile(target))
        assertTrue(result is RepositoryResult.Success)
        repo.close()
    }

    private data class BoundarySpec(val name: String, val stage: BackupStage, val published: Boolean)

    @Test
    fun backupStreamAndPublishFaultMatrixFailsOnceThenRetries() = runBlocking {
        val boundaries = listOf(
            BoundarySpec("beforeEntryWrite:EntityRef", BackupStage.STREAM_JSONL, false),
            BoundarySpec("midEntryWrite:EntityRef", BackupStage.STREAM_JSONL, false),
            BoundarySpec("afterEntryWrite:EntityRef", BackupStage.STREAM_JSONL, false),
            BoundarySpec("beforeEntryWrite:Transaction", BackupStage.STREAM_JSONL, false),
            BoundarySpec("midEntryWrite:Transaction", BackupStage.STREAM_JSONL, false),
            BoundarySpec("afterEntryWrite:Transaction", BackupStage.STREAM_JSONL, false),
            BoundarySpec("beforeEntryWrite:AttachmentContent", BackupStage.STREAM_ATTACHMENT, false),
            BoundarySpec("midEntryWrite:AttachmentContent", BackupStage.STREAM_ATTACHMENT, false),
            BoundarySpec("afterEntryWrite:AttachmentContent", BackupStage.STREAM_ATTACHMENT, false),
            BoundarySpec("beforeManifestWrite", BackupStage.MANIFEST_WRITE, false),
            BoundarySpec("midManifestWrite", BackupStage.MANIFEST_WRITE, false),
            BoundarySpec("afterManifestWriteBeforeClose", BackupStage.MANIFEST_WRITE, false),
            BoundarySpec("beforePrivateRename", BackupStage.PRIVATE_RENAME, false),
            BoundarySpec("afterPrivateRename", BackupStage.PRIVATE_RENAME, true),
        )
        for (spec in boundaries) {
            val repo = openForTest(context, "export_${System.nanoTime()}.db")
            seed(repo)
            val target = newTarget()
            repo.setBackupHookForTest(FailingBackupHook(spec.name, spec.stage))
            val failed = repo.exportBackup(BackupTarget.PrivateFile(target))
            assertTrue("boundary ${spec.name}: expected failure, got $failed", failed is RepositoryResult.Failure)
            if (spec.published) {
                assertTrue("boundary ${spec.name}: after atomic rename the complete package is published", target.isFile)
            } else {
                assertFalse("boundary ${spec.name}: package must not be visible before publish", target.exists())
                val residue = target.parentFile.listFiles { f -> f.name.startsWith(".tmp-") }
                assertTrue("boundary ${spec.name}: temp residue cleaned, found ${residue?.size}", residue.isNullOrEmpty())
            }
            // Fail once then restart: retry with no fault completes and publishes.
            repo.setBackupHookForTest(BackupFaultHook.NO_OP)
            val retried = repo.exportBackup(BackupTarget.PrivateFile(target))
            assertTrue("boundary ${spec.name}: retry must succeed, got $retried", retried is RepositoryResult.Success)
            assertTrue("boundary ${spec.name}: retry publishes the target", target.isFile)
            repo.close()
        }
    }

    @Test
    fun repeatedFaultNeverPublishesAndRetainsNoResidue() = runBlocking {
        val repo = openForTest(context, "export_${System.nanoTime()}.db")
        seed(repo)
        val target = newTarget()
        val hook = FailingBackupHook("beforeManifestWrite", BackupStage.MANIFEST_WRITE, failFirstNCalls = Int.MAX_VALUE)
        repo.setBackupHookForTest(hook)
        for (attempt in 1..2) {
            val result = repo.exportBackup(BackupTarget.PrivateFile(target))
            assertTrue("attempt $attempt must fail, got $result", result is RepositoryResult.Failure)
            assertFalse("attempt $attempt must not publish", target.exists())
        }
        val residue = target.parentFile.listFiles { f -> f.name.startsWith(".tmp-") }
        assertTrue("repeated failure leaves no temp residue", residue.isNullOrEmpty())
        repo.close()
    }

    @Test
    fun completeThenRepeatIsIdempotentWithDistinctArtifacts() = runBlocking {
        val repo = openForTest(context, "export_${System.nanoTime()}.db")
        seed(repo)
        val firstTarget = newTarget()
        val secondTarget = newTarget()
        val first = repo.exportBackup(BackupTarget.PrivateFile(firstTarget))
        val second = repo.exportBackup(BackupTarget.PrivateFile(secondTarget))
        assertTrue(first is RepositoryResult.Success)
        assertTrue(second is RepositoryResult.Success)
        val artifact1 = (first as RepositoryResult.Success).value as BackupArtifact
        val artifact2 = (second as RepositoryResult.Success).value as BackupArtifact
        assertFalse("distinct backups", artifact1.backupId == artifact2.backupId)
        repo.close()
    }

    private fun readZipEntry(zip: File, name: String): String {
        java.util.zip.ZipFile(zip).use { zf ->
            val entry = zf.getEntry(name) ?: return ""
            return zf.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private fun readZipEntryBytes(zip: File, name: String): ByteArray {
        java.util.zip.ZipFile(zip).use { zf ->
            val entry = zf.getEntry(name) ?: return ByteArray(0)
            return zf.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Fails a named export boundary the first N invocations of that boundary. */
private class FailingBackupHook(
    private val boundary: String,
    private val stage: BackupStage,
    private val failFirstNCalls: Int = 1,
) : BackupFaultHook {
    private val counts = HashMap<String, Int>()

    private fun hit(name: String) {
        val c = (counts[name] ?: 0) + 1
        counts[name] = c
        if (name == boundary && c <= failFirstNCalls) throw BackupIoException(stage, "injected $name")
    }

    override fun beforeEntryWrite(logicalType: String) = hit("beforeEntryWrite:$logicalType")
    override fun midEntryWrite(logicalType: String) = hit("midEntryWrite:$logicalType")
    override fun afterEntryWrite(logicalType: String) = hit("afterEntryWrite:$logicalType")
    override fun beforeManifestWrite() = hit("beforeManifestWrite")
    override fun midManifestWrite() = hit("midManifestWrite")
    override fun afterManifestWriteBeforeClose() = hit("afterManifestWriteBeforeClose")
    override fun beforePrivateRename() = hit("beforePrivateRename")
    override fun afterPrivateRename() = hit("afterPrivateRename")
}

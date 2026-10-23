package com.paherdin.record.core.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.BackupArtifact
import com.paherdin.record.core.data.BackupTarget
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.RestoreViolation
import com.paherdin.record.core.data.StagedRestore
import com.paherdin.record.core.data.openForTest
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class StagingValidationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("stage_test.db")
        File(context.filesDir, "vaults").deleteRecursively()
        File(context.filesDir, "recovery").deleteRecursively()
    }

    private fun newZip(): File = File(context.cacheDir, "stage-${System.nanoTime()}.zip")

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
                    id = "tx-1", type = TransactionType.INCOME, createdAt = 1, updatedAt = 1, deletedAt = null,
                    entries = listOf(NewAccountEntry("e-1", "acc-1", 5000)),
                    relationNoteIds = emptySet(), attachmentContentIds = setOf(contentIdentity),
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

    private suspend fun seedAndExport(repo: RecordRepository): File {
        seed(repo)
        val target = newZip()
        val result = repo.exportBackup(BackupTarget.PrivateFile(target))
        assertTrue("export failed: $result", result is RepositoryResult.Success)
        return target
    }

    private fun readZipText(zip: File, name: String): String =
        ZipFile(zip).use { zf -> zf.getInputStream(zf.getEntry(name)!!).bufferedReader(Charsets.UTF_8).use { it.readText() } }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Rebuilds the zip with one entry replaced and a manifest recomputed to match. */
    private fun rewriteZipEntry(
        zip: File,
        name: String,
        newContent: String,
        catalogVersions: List<Int> = emptyList(),
    ): File {
        val out = newZip()
        val entryBytes = LinkedHashMap<String, ByteArray>()
        ZipFile(zip).use { zf ->
            zf.entries().asSequence().forEach { entry ->
                if (!entry.isDirectory && entry.name != "manifest.json") {
                    val bytes = if (entry.name == name) newContent.toByteArray(Charsets.UTF_8) else zf.getInputStream(entry).use { it.readBytes() }
                    entryBytes[entry.name] = bytes
                }
            }
        }
        ZipOutputStream(out.outputStream().buffered()).use { zipOut ->
            val manifestEntries = entryBytes.map { (n, bytes) ->
                val isContent = n.startsWith("attachments/")
                val logicalType = if (isContent) "AttachmentContent" else n.removeSuffix(".jsonl")
                val recordCount = if (isContent) null else bytes.count { it.toInt() == '\n'.code }.toLong()
                """{"logicalType":"$logicalType","path":"$n","size":${bytes.size},"sha256":"${sha256(bytes)}"${recordCount?.let { ",\"recordCount\":$it" } ?: ""}}"""
            }
            val manifest = "{" +
                "\"formatVersion\":1,\"databaseSchemaVersion\":2,\"appVersion\":\"t\",\"createdAt\":1," +
                "\"backupId\":\"patched\",\"hashAlgorithm\":\"SHA-256\"," +
                "\"catalogNameKeyVersions\":[${catalogVersions.joinToString(",")}]," +
                "\"entries\":[${manifestEntries.joinToString(",")}]}"
            entryBytes.forEach { (n, bytes) ->
                zipOut.putNextEntry(ZipEntry(n))
                zipOut.write(bytes)
                zipOut.closeEntry()
            }
            zipOut.putNextEntry(ZipEntry("manifest.json"))
            zipOut.write(manifest.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        }
        return out
    }

    @Test
    fun stagesValidPackageWithCorrectCounts() = runBlocking {
        val repo = openForTest(context, "stage_test.db")
        val zip = seedAndExport(repo)
        val result = repo.stageRestore(zip)
        assertTrue("staging failed: $result", result is RepositoryResult.Success)
        val staged = (result as RepositoryResult.Success).value as StagedRestore
        assertEquals(2, staged.recordCounts["EntityRef"])
        assertEquals(1, staged.recordCounts["Account"])
        assertEquals(1, staged.recordCounts["Transaction"])
        assertEquals(1, staged.recordCounts["Note"])
        assertEquals(1, staged.recordCounts["AccountEntry"])
        assertEquals(1, staged.recordCounts["Attachment"])
        assertEquals(1, staged.recordCounts["AttachmentLink"])
        assertEquals(1, staged.attachmentCount)
        assertTrue(staged.validationDigest.isNotBlank())
        assertNotNull(repo.currentStagedCandidate())
        repo.close()
    }

    @Test
    fun secondStageReplacesTheCandidate() = runBlocking {
        val repo = openForTest(context, "stage_test.db")
        val zip = seedAndExport(repo)
        val first = (repo.stageRestore(zip) as RepositoryResult.Success).value as StagedRestore
        val second = (repo.stageRestore(zip) as RepositoryResult.Success).value as StagedRestore
        assertNotEquals("one candidate at a time", first.candidateId, second.candidateId)
        assertNotEquals("replacement rebinds the digest", first.validationDigest, second.validationDigest)
        repo.close()
    }

    @Test
    fun rejectsFinanceViolation() = runBlocking {
        val repo = openForTest(context, "stage_test.db")
        val zip = seedAndExport(repo)
        // INCOME with one positive entry becomes TRANSFER (needs two opposite entries).
        val patched = rewriteZipEntry(zip, "Transaction.jsonl", "{\"id\":\"tx-1\",\"type\":\"TRANSFER\"}\n")
        val result = repo.stageRestore(patched)
        assertEquals(
            RestoreViolation.FINANCE_INVALID,
            (result as RepositoryResult.Failure).let { it as? com.paherdin.record.core.data.RepositoryResult.Failure.RestoreRejected }!!.violation,
        )
        repo.close()
    }

    @Test
    fun rejectsDanglingForeignKey() = runBlocking {
        val repo = openForTest(context, "stage_test.db")
        val zip = seedAndExport(repo)
        val links = readZipText(zip, "AttachmentLink.jsonl").lineSequence().filter { it.isNotBlank() }.first()
        val attachmentId = Regex("\"attachmentId\":\"([^\"]+)\"").find(links)!!.groupValues[1]
        val dangling = "{\"attachmentId\":\"$attachmentId\",\"ownerEntityRefId\":\"ghost\"}\n"
        val patched = rewriteZipEntry(zip, "AttachmentLink.jsonl", dangling)
        val result = repo.stageRestore(patched)
        assertEquals(
            RestoreViolation.FOREIGN_KEY_INVALID,
            (result as RepositoryResult.Failure).let { it as? com.paherdin.record.core.data.RepositoryResult.Failure.RestoreRejected }!!.violation,
        )
        repo.close()
    }

    @Test
    fun rejectsInvalidRecordSyntax() = runBlocking {
        val repo = openForTest(context, "stage_test.db")
        val zip = seedAndExport(repo)
        val patched = rewriteZipEntry(zip, "EntityRef.jsonl", "not-json\n")
        val result = repo.stageRestore(patched)
        assertEquals(
            RestoreViolation.RECORD_INVALID,
            (result as RepositoryResult.Failure).let { it as? com.paherdin.record.core.data.RepositoryResult.Failure.RestoreRejected }!!.violation,
        )
        repo.close()
    }

    @Test
    fun rejectsUnsupportedCatalogVersionInPreflight() = runBlocking {
        val repo = openForTest(context, "stage_test.db")
        val zip = seedAndExport(repo)
        // Regenerate the manifest with an unsupported catalog version; entry
        // sizes/hashes/counts stay consistent so preflight reaches the framework.
        val patched = rewriteZipEntry(zip, "manifest.json", "", catalogVersions = listOf(2))
        val result = repo.stageRestore(patched)
        assertEquals(
            RestoreViolation.NAME_KEY_VERSION_UNSUPPORTED,
            (result as RepositoryResult.Failure).let { it as? com.paherdin.record.core.data.RepositoryResult.Failure.RestoreRejected }!!.violation,
        )
        repo.close()
    }

    @Test
    fun rejectedCandidateLeavesVaultUntouched() = runBlocking {
        val repo = openForTest(context, "stage_test.db")
        val zip = seedAndExport(repo)
        val patched = rewriteZipEntry(zip, "manifest.json", "", catalogVersions = listOf(2))
        assertTrue(repo.stageRestore(patched) is RepositoryResult.Failure)
        // No leftover staging generation and no record written.
        assertTrue(File(context.filesDir, "vaults").listFiles().isNullOrEmpty())
        assertTrue(!File(context.filesDir, "recovery/activation-recovery-v1.json").exists())
        repo.close()
    }
}

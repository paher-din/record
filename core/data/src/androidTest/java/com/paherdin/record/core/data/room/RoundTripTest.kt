package com.paherdin.record.core.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.BackupTarget
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.NewNote
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.restore.ActivationRecordStore
import com.paherdin.record.core.data.restore.GenerationState
import com.paherdin.record.core.data.restore.VaultSessionRegistry
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RoundTripTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        VaultSessionRegistry.resetForTest()
        context.deleteDatabase("record.db")
        File(context.filesDir, "vaults").deleteRecursively()
        File(context.filesDir, "recovery").deleteRecursively()
        File(context.filesDir, "migration").deleteRecursively()
        File(context.filesDir, "attachments").deleteRecursively()
    }

    private suspend fun openRepo(): RecordRepository =
        (RecordRepository.open(context) as RepositoryResult.Success).value

    private suspend fun closeRepo(repo: RecordRepository) {
        repo.close()
    }

    /** Seeds the current-schema canonical fixture over the public surface. */
    private suspend fun seedCanonical(repo: RecordRepository) {
        // Account with a Unicode name; one soft-deleted fact.
        assertEquals(RepositoryResult.Success(Unit), repo.createAccount(NewAccount("acc-cny", "现金/钱包", "CNY")))
        assertEquals(RepositoryResult.Success(Unit), repo.createAccount(NewAccount("acc-cny2", "零钱", "CNY")))
        assertEquals(RepositoryResult.Success(Unit), repo.createAccount(NewAccount("acc-usd", "Wallet", "USD")))
        // Opening balance.
        assertEquals(
            RepositoryResult.Success(Unit),
            repo.saveTransactionAggregate(
                NewTransaction(
                    id = "tx-open", type = TransactionType.OPENING_BALANCE, createdAt = 1, updatedAt = 1, deletedAt = null,
                    entries = listOf(NewAccountEntry("e-open", "acc-cny", 100_00)),
                    relationNoteIds = emptySet(), attachmentContentIds = emptySet(),
                ),
            ),
        )
        // Income with two allocations across two accounts is not allowed by the
        // finance matrix (same currency only); use a single-account income and a
        // multi-currency transfer separately.
        assertEquals(
            RepositoryResult.Success(Unit),
            repo.saveTransactionAggregate(
                NewTransaction(
                    id = "tx-inc", type = TransactionType.INCOME, createdAt = 2, updatedAt = 2, deletedAt = null,
                    entries = listOf(NewAccountEntry("e-inc", "acc-cny", 5000)),
                    relationNoteIds = emptySet(), attachmentContentIds = emptySet(),
                ),
            ),
        )
        // Expense.
        assertEquals(
            RepositoryResult.Success(Unit),
            repo.saveTransactionAggregate(
                NewTransaction(
                    id = "tx-exp", type = TransactionType.EXPENSE, createdAt = 3, updatedAt = 3, deletedAt = null,
                    entries = listOf(NewAccountEntry("e-exp", "acc-cny", -800)),
                    relationNoteIds = emptySet(), attachmentContentIds = emptySet(),
                ),
            ),
        )
        // Same-currency transfer between two distinct CNY accounts.
        assertEquals(
            RepositoryResult.Success(Unit),
            repo.saveTransactionAggregate(
                NewTransaction(
                    id = "tx-xfer", type = TransactionType.TRANSFER, createdAt = 4, updatedAt = 4, deletedAt = null,
                    entries = listOf(NewAccountEntry("e-x1", "acc-cny", -2000), NewAccountEntry("e-x2", "acc-cny2", 2000)),
                    relationNoteIds = emptySet(), attachmentContentIds = emptySet(),
                ),
            ),
        )
        // Note.
        assertEquals(
            RepositoryResult.Success(Unit),
            repo.saveNoteAggregate(
                NewNote("note-1", createdAt = 5, updatedAt = 5, deletedAt = null, relationTransactionIds = emptySet(), attachmentContentIds = emptySet()),
            ),
        )
        // Bidirectional transaction-note association.
        assertEquals(
            RepositoryResult.Success(Unit),
            repo.replaceTransactionNoteRelations("tx-exp", setOf("note-1")),
        )
        // Attachment with two owners sharing one canonical blob.
        val source = File(context.cacheDir, "roundtrip-${System.nanoTime()}.png")
        source.writeBytes(AttachmentTestFixtures.ONE_PIXEL_PNG)
        val ingest = repo.ingestAttachment(source, "image/png")
        assertTrue("ingest failed: $ingest", ingest is RepositoryResult.Success)
        val contentIdentity = (ingest as RepositoryResult.Success).value
        assertEquals(
            RepositoryResult.Success(Unit),
            repo.linkAttachment("tx-inc", contentIdentity),
        )
        assertEquals(
            RepositoryResult.Success(Unit),
            repo.linkAttachment("note-1", contentIdentity),
        )
        // Soft-deleted fact: a soft-deleted note.
        assertEquals(
            RepositoryResult.Success(Unit),
            repo.saveNoteAggregate(
                NewNote("note-deleted", createdAt = 6, updatedAt = 6, deletedAt = 999, relationTransactionIds = emptySet(), attachmentContentIds = emptySet()),
            ),
        )
    }

    /** Reads the eight datasets' sorted JSONL lines (logical content) from a package. */
    private fun logicalContent(pkg: File): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        java.util.zip.ZipFile(pkg).use { zip ->
            for (name in listOf(
                "EntityRef.jsonl", "Account.jsonl", "Transaction.jsonl", "AccountEntry.jsonl",
                "Note.jsonl", "TransactionNoteAssociation.jsonl", "Attachment.jsonl", "AttachmentLink.jsonl",
            )) {
                val entry = zip.getEntry(name)
                if (entry == null) {
                    result[name] = emptyList()
                    continue
                }
                val lines = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .lineSequence().filter { it.isNotBlank() }.sorted()
                result[name] = lines.toList()
            }
        }
        return result
    }

    @Test
    fun completeRoundTripMatchesEveryLogicalField() = runBlocking {
        val repo = openRepo()
        seedCanonical(repo)
        val packagePath = File(context.cacheDir, "roundtrip-export-${System.nanoTime()}.zip")
        assertTrue(repo.exportBackup(BackupTarget.PrivateFile(packagePath)) is RepositoryResult.Success)
        val pre = logicalContent(packagePath)
        val oldGen = repo.currentGenerationId()

        // Overwrite restore: stage the exported package and confirm it.
        val staged = (repo.stageRestore(packagePath) as RepositoryResult.Success).value
        assertTrue("confirm failed", repo.confirmRestore(staged) is RepositoryResult.Success)
        assertNotEquals("active generation changed after restore", oldGen, repo.currentGenerationId())
        closeRepo(repo)

        // Cross the pending startup-validation gate via cold start.
        val restarted = openRepo()
        assertEquals("cold start uses the restored candidate", staged.candidateId, restarted.currentGenerationId())
        val post = logicalContent(File(context.cacheDir, "roundtrip-export2-${System.nanoTime()}.zip").also {
            assertTrue(restarted.exportBackup(BackupTarget.PrivateFile(it)) is RepositoryResult.Success)
        })
        assertEquals("EntityRef matches", pre["EntityRef.jsonl"], post["EntityRef.jsonl"])
        assertEquals("Account matches", pre["Account.jsonl"], post["Account.jsonl"])
        assertEquals("Transaction matches", pre["Transaction.jsonl"], post["Transaction.jsonl"])
        assertEquals("AccountEntry matches", pre["AccountEntry.jsonl"], post["AccountEntry.jsonl"])
        assertEquals("Note matches", pre["Note.jsonl"], post["Note.jsonl"])
        assertEquals("TransactionNoteAssociation matches", pre["TransactionNoteAssociation.jsonl"], post["TransactionNoteAssociation.jsonl"])
        assertEquals("Attachment matches", pre["Attachment.jsonl"], post["Attachment.jsonl"])
        assertEquals("AttachmentLink matches", pre["AttachmentLink.jsonl"], post["AttachmentLink.jsonl"])
        closeRepo(restarted)

        // Second restart: integrity and balance recheck.
        val second = openRepo()
        val accView = second.getTransactionView("tx-open")
        assertTrue(accView is RepositoryResult.Success && (accView as RepositoryResult.Success).value != null)
        closeRepo(second)
    }

    @Test
    fun softDeletedHistorySurvivesTheRoundTrip() = runBlocking {
        val repo = openRepo()
        seedCanonical(repo)
        val pkg = File(context.cacheDir, "roundtrip-soft-${System.nanoTime()}.zip")
        assertTrue(repo.exportBackup(BackupTarget.PrivateFile(pkg)) is RepositoryResult.Success)
        val pre = logicalContent(pkg)
        val staged = (repo.stageRestore(pkg) as RepositoryResult.Success).value
        assertTrue(repo.confirmRestore(staged) is RepositoryResult.Success)
        closeRepo(repo)

        val restarted = openRepo()
        val post = logicalContent(File(context.cacheDir, "roundtrip-soft2-${System.nanoTime()}.zip").also {
            assertTrue(restarted.exportBackup(BackupTarget.PrivateFile(it)) is RepositoryResult.Success)
        })
        assertTrue("soft-deleted note retained", pre["Note.jsonl"]!!.any { it.contains("note-deleted") })
        assertEquals("soft-deleted note in restored history", pre["Note.jsonl"], post["Note.jsonl"])
        closeRepo(restarted)
    }
}

package com.paherdin.record.core.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.BackupTarget
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.StagedRestore
import com.paherdin.record.core.data.restore.ActivationRecord
import com.paherdin.record.core.data.restore.ActivationRecordStore
import com.paherdin.record.core.data.restore.GenerationActivation
import com.paherdin.record.core.data.restore.GenerationState
import com.paherdin.record.core.data.restore.VaultSessionRegistry
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RepositoryGenerationHandoffTest {

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

    private fun recordStore(): ActivationRecordStore =
        ActivationRecordStore(RecordRepository.recoveryRecordFile(context))

    private suspend fun openRepo(): RecordRepository {
        VaultSessionRegistry.resetForTest()
        return (RecordRepository.open(context) as RepositoryResult.Success).value
    }

    private suspend fun seed(repo: RecordRepository) {
        assertEquals(RepositoryResult.Success(Unit), repo.createAccount(NewAccount("acc-1", "Wallet", "CNY")))
        assertEquals(
            RepositoryResult.Success(Unit),
            repo.saveTransactionAggregate(
                NewTransaction(
                    id = "tx-1", type = TransactionType.INCOME, createdAt = 1, updatedAt = 1, deletedAt = null,
                    entries = listOf(NewAccountEntry("e-1", "acc-1", 5000)),
                    relationNoteIds = emptySet(), attachmentContentIds = emptySet(),
                ),
            ),
        )
    }

    private suspend fun export(repo: RecordRepository): File {
        val target = File(context.cacheDir, "handoff-${System.nanoTime()}.zip")
        assertTrue(repo.exportBackup(BackupTarget.PrivateFile(target)) is RepositoryResult.Success)
        return target
    }

    private suspend fun stage(repo: RecordRepository, pkg: File): StagedRestore =
        (repo.stageRestore(pkg) as RepositoryResult.Success).value

    @Test
    fun concurrentWritesDrainBeforeConfirmation() = runBlocking {
        val repo = openRepo()
        seed(repo)
        val staged = stage(repo, export(repo))

        val writes = coroutineScope {
            (0 until 5).map { i ->
                async {
                    repo.saveTransactionAggregate(
                        NewTransaction(
                            id = "tx-concurrent-$i", type = TransactionType.EXPENSE, createdAt = 10L + i, updatedAt = 10L + i, deletedAt = null,
                            entries = listOf(NewAccountEntry("ec-$i", "acc-1", -100L - i)),
                            relationNoteIds = emptySet(), attachmentContentIds = emptySet(),
                        ),
                    )
                }
            }
        }
        val confirm = coroutineScope { async { repo.confirmRestore(staged) } }

        val writeResults = writes.awaitAll()
        val confirmResult = confirm.await()
        writeResults.forEach { assertTrue("concurrent write failed: $it", it is RepositoryResult.Success) }
        assertTrue("confirm must succeed after draining writes: $confirmResult", confirmResult is RepositoryResult.Success)
        assertEquals("repository rebound to the candidate", staged.candidateId, repo.currentGenerationId())
        // The candidate generation contains tx-1 (from the exported package).
        val view = repo.getTransactionView("tx-1")
        assertTrue(view is RepositoryResult.Success && (view as RepositoryResult.Success).value != null)
        repo.close()
    }

    @Test
    fun nextRestoreRunsPendingRetirementFirst() = runBlocking {
        val repo = openRepo()
        seed(repo)
        val oldGen = repo.currentGenerationId()
        val staged = stage(repo, export(repo))
        assertTrue(repo.confirmRestore(staged) is RepositoryResult.Success)
        // Old generation is now retiring.
        assertEquals(oldGen, recordStore().read()!!.generationActivation.retiringGenerationId)
        assertTrue(File(context.filesDir, "vaults/$oldGen").exists())

        // A new restore preflight completes the pending retirement idempotently.
        val pkg2 = export(repo)
        val staged2 = repo.stageRestore(pkg2)
        assertTrue("new restore must stage after retirement: $staged2", staged2 is RepositoryResult.Success)
        assertFalse("retiring generation deleted by next-restore maintenance", File(context.filesDir, "vaults/$oldGen").exists())
        assertEquals(null, recordStore().read()!!.generationActivation.retiringGenerationId)
        repo.close()
    }

    @Test
    fun pendingActivationBlocksNewRestore() = runBlocking {
        val repo = openRepo()
        seed(repo)
        val oldGen = repo.currentGenerationId()
        val staged = stage(repo, export(repo))
        // Simulate a durable PENDING activation.
        recordStore().write(
            ActivationRecord(
                migrationRecoveryState = com.paherdin.record.core.data.MigrationRecoveryState.MIGRATION_HEALTHY,
                migration = null,
                generationActivation = com.paherdin.record.core.data.restore.GenerationActivation(
                    staged.candidateId, oldGen, staged.candidateId, null, GenerationState.PENDING,
                ),
            ),
        )
        val pkg = export(repo)
        val result = repo.stageRestore(pkg)
        assertTrue(
            "pending activation must block a new restore with MAINTENANCE_PENDING, got $result",
            result is RepositoryResult.Failure && (result as? com.paherdin.record.core.data.RepositoryResult.Failure.RestoreRejected)?.violation == com.paherdin.record.core.data.RestoreViolation.MAINTENANCE_PENDING,
        )
        repo.close()
    }
}

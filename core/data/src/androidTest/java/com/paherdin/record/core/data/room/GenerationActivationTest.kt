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
import com.paherdin.record.core.data.RestoreViolation
import com.paherdin.record.core.data.StagedRestore
import com.paherdin.record.core.data.restore.ActivationRecord
import com.paherdin.record.core.data.restore.ActivationRecordStore
import com.paherdin.record.core.data.restore.GenerationActivation
import com.paherdin.record.core.data.restore.GenerationState
import com.paherdin.record.core.data.restore.RestoreFaultHook
import com.paherdin.record.core.data.restore.GenerationState.HEALTHY
import com.paherdin.record.core.data.restore.GenerationState.PENDING
import com.paherdin.record.core.data.restore.VaultSessionRegistry
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
class GenerationActivationTest {

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

    /** A fully clean durable vault (session + record + generations), per iteration. */
    private suspend fun freshVault(): RecordRepository {
        VaultSessionRegistry.resetForTest()
        File(context.filesDir, "vaults").deleteRecursively()
        File(context.filesDir, "recovery").deleteRecursively()
        File(context.filesDir, "migration").deleteRecursively()
        File(context.filesDir, "attachments").deleteRecursively()
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
        val target = File(context.cacheDir, "act-${System.nanoTime()}.zip")
        assertTrue(repo.exportBackup(BackupTarget.PrivateFile(target)) is RepositoryResult.Success)
        return target
    }

    private suspend fun stage(repo: RecordRepository, pkg: File): StagedRestore =
        (repo.stageRestore(pkg) as RepositoryResult.Success).value

    private suspend fun closeRepo(repo: RecordRepository) {
        repo.close()
    }

    @Test
    fun confirmSwitchesGenerationAndKeepsSameInstance() = runBlocking {
        val repo = openRepo()
        seed(repo)
        val oldGen = repo.currentGenerationId()
        val staged = stage(repo, export(repo))

        val result = repo.confirmRestore(staged)
        assertTrue("confirm failed: $result", result is RepositoryResult.Success)

        assertEquals("same instance rebound to the candidate", staged.candidateId, repo.currentGenerationId())
        assertFalse("candidate differs from the old generation", staged.candidateId == oldGen)
        val record = recordStore().read()!!
        assertEquals(HEALTHY, record.generationActivation.state)
        assertEquals(staged.candidateId, record.generationActivation.activeGenerationId)
        assertEquals(oldGen, record.generationActivation.retiringGenerationId)
        // The same repository instance now reads the candidate generation.
        val view = repo.getTransactionView("tx-1")
        assertTrue("candidate must be readable after confirm: $view", view is RepositoryResult.Success && (view as RepositoryResult.Success).value != null)
        closeRepo(repo)
    }

    @Test
    fun staleOrForgedHandleIsRejectedBeforeMutation() = runBlocking {
        val repo = openRepo()
        seed(repo)
        val oldGen = repo.currentGenerationId()
        val staged = stage(repo, export(repo))

        val bogus = staged.copy(validationDigest = "0".repeat(64))
        val result = repo.confirmRestore(bogus)
        assertTrue(
            "forged handle must be CANDIDATE_MISMATCH, got $result",
            result is RepositoryResult.Failure && (result as? com.paherdin.record.core.data.RepositoryResult.Failure.RestoreRejected)?.violation == RestoreViolation.CANDIDATE_MISMATCH,
        )
        // No mutation: still healthy on the old generation.
        val record = recordStore().read()!!
        assertEquals(HEALTHY, record.generationActivation.state)
        assertEquals(oldGen, record.generationActivation.activeGenerationId)
        closeRepo(repo)
    }

    @Test
    fun revalidationFailureRollsBackToOriginal() = runBlocking {
        val repo = openRepo()
        seed(repo)
        val oldGen = repo.currentGenerationId()
        val staged = stage(repo, export(repo))
        // Corrupt the candidate generation so revalidation fails: a TRANSFER
        // transaction with a single positive entry violates the finance matrix.
        val candidateDb = File(context.filesDir, "vaults/${staged.candidateId}/record.db")
        assertTrue(candidateDb.isFile)
        android.database.sqlite.SQLiteDatabase.openDatabase(candidateDb.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO EntityRef VALUES ('bad-tx','TRANSACTION',1,1,NULL)")
            db.execSQL("INSERT INTO `Transaction` VALUES ('bad-tx','TRANSFER')")
            db.execSQL("INSERT INTO AccountEntry VALUES ('bad-e','bad-tx','acc-1',5000)")
        }

        val result = repo.confirmRestore(staged)
        assertTrue("corrupt candidate must fail: $result", result is RepositoryResult.Failure)
        assertEquals("rolled back to the original binding", oldGen, repo.currentGenerationId())
        val record = recordStore().read()!!
        assertEquals(HEALTHY, record.generationActivation.state)
        assertEquals(oldGen, record.generationActivation.activeGenerationId)
        assertEquals(staged.candidateId, record.generationActivation.retiringGenerationId)
        closeRepo(repo)
    }

    @Test
    fun coldStartResolvesDurablePending() = runBlocking {
        val repo = openRepo()
        seed(repo)
        val oldGen = repo.currentGenerationId()
        val staged = stage(repo, export(repo))
        // Simulate a crash after the PENDING record is durable.
        recordStore().write(
            ActivationRecord(
                migrationRecoveryState = com.paherdin.record.core.data.MigrationRecoveryState.MIGRATION_HEALTHY,
                migration = null,
                generationActivation = GenerationActivation(staged.candidateId, oldGen, staged.candidateId, null, PENDING),
            ),
        )
        repo.close() // process death: release the session

        val reopened = openRepo()
        assertEquals("cold start resolves the pending candidate", staged.candidateId, reopened.currentGenerationId())
        val view = reopened.getTransactionView("tx-1")
        assertTrue("resolved candidate must be readable: $view", view is RepositoryResult.Success && (view as RepositoryResult.Success).value != null)
        val record = recordStore().read()!!
        assertEquals(HEALTHY, record.generationActivation.state)
        assertEquals(staged.candidateId, record.generationActivation.activeGenerationId)
        closeRepo(reopened)
    }

    @Test
    fun activationFaultMatrixFailsOnceThenResumes() = runBlocking {
        val boundaries = listOf(
            "beforeCloseOldBinding",
            "beforePendingWrite",
            "afterPendingWrite",
            "beforeRevalidate",
            "afterRevalidate",
            "beforeHealthyWrite",
        )
        for (boundary in boundaries) {
            val repo = freshVault()
            seed(repo)
            val staged = stage(repo, export(repo))
            repo.setRestoreHookForTest(FailingRestoreHook(boundary, failFirstNCalls = 1))
            val failed = repo.confirmRestore(staged)
            assertTrue("boundary $boundary: expected failure, got $failed", failed is RepositoryResult.Failure)
            repo.setRestoreHookForTest(RestoreFaultHook.NO_OP)
            val resumed = repo.confirmRestore(staged)
            assertTrue("boundary $boundary: retry must succeed, got $resumed", resumed is RepositoryResult.Success)
            assertEquals(staged.candidateId, repo.currentGenerationId())
            closeRepo(repo)
        }
    }

    @Test
    fun retirementClearsThePointerAndDeletesTheGeneration() = runBlocking {
        val repo = openRepo()
        seed(repo)
        val oldGen = repo.currentGenerationId()
        val staged = stage(repo, export(repo))
        assertTrue(repo.confirmRestore(staged) is RepositoryResult.Success)
        // O is now retiring; retire it (step 6).
        val activationStore = recordStore()
        val retired = activationStore.read()!!
        assertEquals(oldGen, retired.generationActivation.retiringGenerationId)
        val manager = com.paherdin.record.core.data.restore.GenerationManager(context)
        val coordinator = com.paherdin.record.core.data.restore.RestoreActivationCoordinator(context, activationStore, manager, RestoreFaultHook.NO_OP)
        val cleared = runBlocking { coordinator.retire(oldGen) }
        assertEquals(null, cleared.generationActivation.retiringGenerationId)
        assertFalse("retired generation directory deleted", File(context.filesDir, "vaults/$oldGen").exists())
        closeRepo(repo)
    }
}

/** Fails a named activation boundary the first N invocations of that boundary. */
private class FailingRestoreHook(
    private val boundary: String,
    private val failFirstNCalls: Int = 1,
) : RestoreFaultHook {
    private val counts = HashMap<String, Int>()

    private fun hit(name: String) {
        val c = (counts[name] ?: 0) + 1
        counts[name] = c
        if (name == boundary && c <= failFirstNCalls) throw RuntimeException("injected $name")
    }

    override fun beforeCloseOldBinding() = hit("beforeCloseOldBinding")
    override fun afterCloseOldBinding() = hit("afterCloseOldBinding")
    override fun beforePendingWrite() = hit("beforePendingWrite")
    override fun afterPendingWrite() = hit("afterPendingWrite")
    override fun beforeRevalidate() = hit("beforeRevalidate")
    override fun afterRevalidate() = hit("afterRevalidate")
    override fun beforeHealthyWrite() = hit("beforeHealthyWrite")
    override fun afterHealthyWrite() = hit("afterHealthyWrite")
    override fun beforeRollbackWrite() = hit("beforeRollbackWrite")
    override fun afterRollbackWrite() = hit("afterRollbackWrite")
    override fun beforeRetireDelete() = hit("beforeRetireDelete")
    override fun afterRetireDelete() = hit("afterRetireDelete")
    override fun beforeRetirePointerClear() = hit("beforeRetirePointerClear")
    override fun afterRetirePointerClear() = hit("afterRetirePointerClear")
}

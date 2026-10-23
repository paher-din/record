package com.paherdin.record.core.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.MigrationPayload
import com.paherdin.record.core.data.MigrationRecoveryState
import com.paherdin.record.core.data.MigrationStage
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.restore.ActivationRecord
import com.paherdin.record.core.data.restore.ActivationRecordStore
import com.paherdin.record.core.data.restore.GenerationActivation
import com.paherdin.record.core.data.restore.GenerationManager
import com.paherdin.record.core.data.restore.GenerationState
import com.paherdin.record.core.data.restore.VaultSessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StartupGateOrderTest {

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

    @Test
    fun pendingMigrationRecordBlocksRoomOpen() {
        // A v2 record with a pending migration must block startup before any
        // generation opens (migration gate precedes the generation gate).
        val generationManager = GenerationManager(context)
        val activeId = generationManager.newGenerationId()
        generationManager.createGeneration(activeId)
        recordStore().write(
            ActivationRecord(
                migrationRecoveryState = MigrationRecoveryState.MIGRATION_RECOVERY_PENDING,
                migration = MigrationPayload(
                    snapshotId = "snap-1",
                    snapshotSha256 = "a".repeat(64),
                    liveDatabasePath = "databases/record.db",
                    workDatabasePath = "databases/record.db",
                    restoreTempPath = "recovery/restore-snap-1.db",
                    quarantinePath = "recovery/quarantine-snap-1",
                    stage = MigrationStage.PREPARED,
                ),
                generationActivation = GenerationActivation(activeId, null, null, null, GenerationState.HEALTHY),
            ),
        )
        val result = RecordRepository.open(context)
        assertTrue("pending migration must block startup, got $result", result is RepositoryResult.Failure)
        // Room never created the active generation database.
        assertTrue(!generationManager.databaseFile(activeId).exists())
    }

    @Test
    fun pendingGenerationResolvesWhenMigrationIsHealthy() {
        // Healthier path: migration healthy, generation PENDING -> the common
        // resolver revalidates the candidate at startup.
        val manager = GenerationManager(context)
        val candidateId = manager.newGenerationId()
        val candidateDir = manager.createGeneration(candidateId)
        // A valid (empty) candidate generation.
        recordStore().write(
            ActivationRecord(
                migrationRecoveryState = MigrationRecoveryState.MIGRATION_HEALTHY,
                migration = null,
                generationActivation = GenerationActivation(
                    activeGenerationId = candidateId,
                    previousGenerationId = manager.newGenerationId(),
                    candidateGenerationId = candidateId,
                    retiringGenerationId = null,
                    state = GenerationState.PENDING,
                ),
            ),
        )
        // The candidate has no Room database yet; Room creates it on open and the
        // resolver confirms it (empty vault is valid).
        val result = RecordRepository.open(context)
        assertTrue("pending generation must resolve at startup, got $result", result is RepositoryResult.Success)
        val repo = (result as RepositoryResult.Success).value
        assertEquals("cold start activates the candidate", candidateId, repo.currentGenerationId())
        repo.close()
        val record = recordStore().read()!!
        assertEquals(GenerationState.HEALTHY, record.generationActivation.state)
        assertEquals(candidateId, record.generationActivation.activeGenerationId)
    }
}

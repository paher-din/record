package com.paherdin.record.core.data.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.MigrationCoordinator
import com.paherdin.record.core.data.MigrationPayload
import com.paherdin.record.core.data.MigrationRecoveryRecordStore
import com.paherdin.record.core.data.MigrationRecoveryState
import com.paherdin.record.core.data.MigrationRecord
import com.paherdin.record.core.data.MigrationStage
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.ProceedAfterSnapshot
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RecordStartup
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.StartupFailure
import com.paherdin.record.core.data.StartupObserver
import com.paherdin.record.core.data.StartupProgress
import com.paherdin.record.core.data.StartupResult
import com.paherdin.record.core.data.restore.ActivationRecord
import com.paherdin.record.core.data.restore.ActivationRecordStore
import com.paherdin.record.core.data.restore.GenerationActivation
import com.paherdin.record.core.data.restore.GenerationManager
import com.paherdin.record.core.data.restore.GenerationState
import com.paherdin.record.core.data.restore.VaultSessionRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device tests for the ADR-0016 startup gate handoff (plan M1-T03 Step 1):
 * the real bootstrap, migration recovery, activation resolution, and Room
 * open run through `RecordStartup` on the reference device. Asserts Checking
 * first, RecoveryDetected then Recovering before resolution, no Room open
 * before recovery, Ready only after a writable database, stable blocked
 * categories, `RecordRepository.open()` compatibility reaching the same
 * repository identity, and restart/idempotency through every current pending
 * migration stage.
 */
@RunWith(AndroidJUnit4::class)
class RecordStartupDeviceTest {

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

    private class RecordingObserver : StartupObserver {
        val events = mutableListOf<StartupProgress>()
        override fun onProgress(progress: StartupProgress) {
            events.add(progress)
        }
    }

    private fun recordStore(): ActivationRecordStore =
        ActivationRecordStore(RecordRepository.recoveryRecordFile(context))

    // ---- fresh / healthy path ----

    @Test
    fun freshStartEmitsCheckingOnlyAndReadyDatabaseIsWritable() = runBlocking {
        val startup = RecordStartup.create(context)
        val observer = RecordingObserver()
        val result = startup.start(observer)
        assertTrue("fresh start must become Ready, got $result", result is StartupResult.Ready)
        assertEquals(listOf(StartupProgress.Checking), observer.events)
        val repository = (result as StartupResult.Ready).repository
        // Ready only after the active generation's writable Room database is open.
        assertEquals(
            RepositoryResult.Success(Unit),
            repository.createAccount(NewAccount("acc-startup", "Wallet", "CNY")),
        )
        // Ready is cached: a later start returns the same result without reopening.
        val cached = startup.start(RecordingObserver())
        assertSame(result, cached)
        repository.close()
    }

    // ---- pending activation ----

    @Test
    fun pendingActivationEmitsRecoveryThenResolvesBeforeReady() = runBlocking {
        val manager = GenerationManager(context)
        val candidateId = manager.newGenerationId()
        manager.createGeneration(candidateId)
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
        val startup = RecordStartup.create(context)
        val observer = RecordingObserver()
        val result = startup.start(observer)
        assertTrue("pending activation must resolve to Ready, got $result", result is StartupResult.Ready)
        assertEquals(
            listOf(StartupProgress.Checking, StartupProgress.RecoveryDetected, StartupProgress.Recovering),
            observer.events,
        )
        assertEquals(candidateId, (result as StartupResult.Ready).repository.currentGenerationId())
        assertEquals(GenerationState.HEALTHY, recordStore().read()!!.generationActivation.state)
        result.repository.close()
    }

    // ---- durable pending migration (no resolver) ----

    @Test
    fun durablePendingMigrationBlocksWithoutRoomOrRetry() = runBlocking {
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
        val startup = RecordStartup.create(context)
        val observer = RecordingObserver()
        val result = startup.start(observer)
        val blocked = result as? StartupResult.Blocked ?: error("expected Blocked, got $result")
        assertEquals(StartupFailure.RecoveryRequired, blocked.failure)
        assertFalse("durable pending migration is not retryable", blocked.canRetry)
        assertEquals(listOf(StartupProgress.Checking), observer.events)
        // No Room was opened before the blocked classification.
        assertFalse(generationManager.databaseFile(activeId).exists())
        // Retry returns the same result without touching storage.
        assertSame(blocked, startup.retry(RecordingObserver()))
    }

    // ---- compatibility entry ----

    @Test
    fun openCompatibilityReachesTheSameRepositoryIdentity() = runBlocking {
        val ready = RecordStartup.create(context).start(RecordingObserver()) as StartupResult.Ready
        val opened = RecordRepository.open(context)
        assertTrue("open() must succeed after start(), got $opened", opened is RepositoryResult.Success)
        assertSame(ready.repository, (opened as RepositoryResult.Success).value)
        opened.value.close()
    }

    // ---- restart/idempotency through every pending migration stage ----

    @Test
    fun everyPendingMigrationStageRestartsAndIsIdempotent() = runBlocking {
        for (stage in MigrationStage.entries) {
            setUp()
            pendingMigrationRecordAt(stage)
            val startup = RecordStartup.create(context)
            val observer = RecordingObserver()
            val result = startup.start(observer)
            assertTrue("stage $stage: start must recover to Ready, got $result", result is StartupResult.Ready)
            assertEquals(
                "stage $stage: recovery progress must precede Ready",
                listOf(StartupProgress.Checking, StartupProgress.RecoveryDetected, StartupProgress.Recovering),
                observer.events,
            )
            (result as StartupResult.Ready).repository.close()
            // Restart on a fresh coordinator is idempotent: the now-healthy v2
            // record opens without another recovery.
            val restartedObserver = RecordingObserver()
            val restarted = RecordStartup.create(context).start(restartedObserver)
            assertTrue("stage $stage: restart must be idempotent, got $restarted", restarted is StartupResult.Ready)
            assertEquals(listOf(StartupProgress.Checking), restartedObserver.events)
            (restarted as StartupResult.Ready).repository.close()
        }
    }

    /** v1 live fixture with committed facts (same DDL helper as MigrationRecoveryTest). */
    private fun createV1FixtureAtLivePath() {
        val live = context.getDatabasePath("record.db")
        live.parentFile!!.mkdirs()
        SQLiteDatabase.openDatabase(live.path, null, SQLiteDatabase.CREATE_IF_NECESSARY).use { db ->
            AttachmentTestFixtures.createV1Schema(db)
            db.execSQL("INSERT INTO `EntityRef` VALUES ('ref-t1', 'TRANSACTION', 1, 1, NULL)")
            db.execSQL("INSERT INTO `Transaction` VALUES ('ref-t1', 'EXPENSE')")
            db.execSQL("INSERT INTO `Account` VALUES ('acc-1', 'Wallet', 'CNY')")
            db.execSQL("INSERT INTO `AccountEntry` VALUES ('entry-1', 'ref-t1', 'acc-1', -5000)")
        }
    }

    /**
     * Publishes a verified v1 snapshot through the P3 coordinator seam, then
     * rewrites the durable recovery record at the exact stage under test.
     */
    private fun pendingMigrationRecordAt(stage: MigrationStage) {
        createV1FixtureAtLivePath()
        val recordFile = RecordRepository.recoveryRecordFile(context)
        val gate = MigrationCoordinator(context, MigrationRecoveryRecordStore(recordFile)).startupGate()
        assertEquals(ProceedAfterSnapshot, gate)
        val store = MigrationRecoveryRecordStore(recordFile)
        val payload = checkNotNull(checkNotNull(store.read()).migration)
        if (stage != MigrationStage.PREPARED) {
            // Later stages resume from the validated restore copy.
            val restoreTemp = File(context.filesDir.parentFile!!, payload.restoreTempPath)
            restoreTemp.parentFile!!.mkdirs()
            File(context.filesDir, "migration/snapshot-${payload.snapshotId}.db")
                .copyTo(restoreTemp, overwrite = true)
        }
        store.write(MigrationRecord(1, MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, payload.copy(stage = stage)))
    }
}

package com.paherdin.record.core.data

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.paherdin.record.core.data.restore.ActivationOutcome
import com.paherdin.record.core.data.restore.ActivationRecord
import com.paherdin.record.core.data.restore.ActivationRecordStore
import com.paherdin.record.core.data.restore.BootstrapResult
import com.paherdin.record.core.data.restore.GenerationManager
import com.paherdin.record.core.data.restore.GenerationState
import com.paherdin.record.core.data.restore.LegacyGenerationBootstrap
import com.paherdin.record.core.data.restore.RestoreActivationCoordinator
import com.paherdin.record.core.data.restore.RestoreFaultHook
import com.paherdin.record.core.data.restore.VaultSessionRegistry
import com.paherdin.record.core.data.restore.openGenerationDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * The single-owner startup engine (ADR 0016, plan M1-T03): owns the existing
 * P5 order — legacy classification/bootstrap, migration recovery, generation
 * activation validation/rollback, orphan cleanup, then the active generation's
 * writable Room open — one process-wide attempt mutex, and stable exception
 * mapping. Every attempt emits `Checking` first; `RecoveryDetected` then
 * `Recovering` precede pending-resolution dispatch. It emits no path, count,
 * ID, raw stage, or exception.
 *
 * The injected steps are the P5 sequence seams: production wires the real
 * bootstrap/coordinator/generation/Room machinery; pure tests drive the same
 * order and mapping with fakes. The whole sequence is serialized process-wide
 * because `RecordStartup` and the legacy `RecordRepository.open()` entry share
 * one correctness sequence per process (the session registry alone cannot
 * order the pre-registry bootstrap/recovery work).
 */
internal class StartupEngine internal constructor(
    private val classify: (StartupObserver) -> BootstrapResult,
    private val createFreshRecord: () -> ActivationRecord,
    private val resolvePendingActivation: suspend (ActivationRecord) -> ActivationOutcome,
    private val cleanupOrphans: (ActivationRecord) -> Unit,
    private val openActive: suspend (ActivationRecord, Boolean) -> RepositoryResult<RecordRepository>,
) {

    internal constructor(context: Context) : this(
        classify = { observer ->
            val recordFile = RecordRepository.recoveryRecordFile(context)
            val generationManager = GenerationManager(context)
            generationManager.underLock {
                LegacyGenerationBootstrap(
                    context = context,
                    activationStore = ActivationRecordStore(recordFile),
                    legacyStore = MigrationRecoveryRecordStore(recordFile),
                    generationManager = generationManager,
                    coordinator = MigrationCoordinator(context, MigrationRecoveryRecordStore(recordFile)),
                ).classify(observer)
            }
        },
        createFreshRecord = {
            val generationManager = GenerationManager(context)
            val activationStore = ActivationRecordStore(RecordRepository.recoveryRecordFile(context))
            generationManager.underLock {
                val created = ActivationRecord(
                    migrationRecoveryState = MigrationRecoveryState.MIGRATION_HEALTHY,
                    migration = null,
                    generationActivation = generationManager.createInitialGeneration(),
                )
                activationStore.write(created)
                created
            }
        },
        resolvePendingActivation = { record ->
            RestoreActivationCoordinator(
                context,
                ActivationRecordStore(RecordRepository.recoveryRecordFile(context)),
                GenerationManager(context),
                RestoreFaultHook.NO_OP,
            ).resolvePending(record)
        },
        cleanupOrphans = { record ->
            val generationManager = GenerationManager(context)
            generationManager.underLock {
                generationManager.orphanCandidates(record).forEach { orphan ->
                    generationManager.deleteGenerationDirectory(orphan.name)
                }
            }
        },
        openActive = { record, fresh -> openActive(context, record, fresh) },
    )

    /** One in-flight attempt process-wide (ADR 0016: at most one correctness sequence). */
    suspend fun run(observer: StartupObserver): StartupResult = attemptMutex.withLock {
        try {
            observer.onProgress(StartupProgress.Checking)
            val bootstrapResult = classify(observer)
            val fresh = bootstrapResult is BootstrapResult.Fresh
            val classified = when (bootstrapResult) {
                BootstrapResult.Fresh -> createFreshRecord()
                is BootstrapResult.Ready -> bootstrapResult.record
                is BootstrapResult.Blocked -> return@withLock StartupResult.Blocked(StartupFailure.RecoveryRequired, true)
            }
            val record = if (classified.generationActivation.state == GenerationState.PENDING) {
                // Durable PENDING activation: RecoveryDetected before the
                // existing resolver is dispatched, Recovering while it runs.
                observer.onProgress(StartupProgress.RecoveryDetected)
                observer.onProgress(StartupProgress.Recovering)
                // ADR 0016: once dispatched, the resolver is non-cancellable
                // until it reaches verified healthy, rollback, or blocked
                // (same boundary as confirmRestore). The caller's
                // cancellation is surfaced immediately after the boundary.
                val outcome = withContext(NonCancellable) { resolvePendingActivation(classified) }
                coroutineContext.ensureActive()
                when (outcome) {
                    is ActivationOutcome.Activated -> outcome.record
                    is ActivationOutcome.RolledBack -> outcome.record
                    is ActivationOutcome.Blocked -> return@withLock StartupResult.Blocked(StartupFailure.RecoveryRequired, true)
                }
            } else {
                classified
            }
            if (record.migrationRecoveryState != MigrationRecoveryState.MIGRATION_HEALTHY) {
                return@withLock StartupResult.Blocked(StartupFailure.RecoveryRequired, false)
            }
            if (record.generationActivation.state != GenerationState.HEALTHY) {
                return@withLock StartupResult.Blocked(StartupFailure.RecoveryRequired, false)
            }
            cleanupOrphans(record)
            when (val opened = openActive(record, fresh)) {
                is RepositoryResult.Success -> StartupResult.Ready(opened.value)
                is RepositoryResult.Failure.StorageExhausted -> StartupResult.Blocked(StartupFailure.StorageExhausted, false)
                is RepositoryResult.Failure -> StartupResult.Blocked(StartupFailure.Unexpected, false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteFullException) {
            StartupResult.Blocked(StartupFailure.StorageExhausted, false)
        } catch (e: SQLiteException) {
            StartupResult.Blocked(StartupFailure.Unexpected, false)
        } catch (e: MigrationGateException) {
            StartupResult.Blocked(StartupFailure.Unexpected, false)
        } catch (e: IllegalArgumentException) {
            StartupResult.Blocked(StartupFailure.Unexpected, false)
        } catch (e: Throwable) {
            StartupResult.Blocked(StartupFailure.Unexpected, false)
        }
    }

    private companion object {
        private val attemptMutex = Mutex()

        /**
         * Opens the active generation's writable Room database and binds the
         * repository through the process-wide session registry, so no second
         * live repository can exist for the canonical vault root.
         */
        private fun openActive(
            context: Context,
            record: ActivationRecord,
            fresh: Boolean,
        ): RepositoryResult<RecordRepository> {
            val root = checkNotNull(context.filesDir.parentFile) { "app data root unavailable" }
            val generationManager = GenerationManager(context)
            val generationId = record.generationActivation.activeGenerationId
            val databaseFile = generationManager.databaseFile(generationId)
            if (!fresh && !generationManager.isIncomplete(generationId) && !databaseFile.isFile) {
                return RepositoryResult.Failure.TransactionFailed
            }
            return VaultSessionRegistry.open(root) {
                val opened = RecordRepository.openWithBuilder(
                    context = context,
                    hook = TransactionFaultHook.NO_OP,
                    builder = { ctx -> openGenerationDatabase(ctx, databaseFile) },
                    attachmentHook = AttachmentFaultHook.NO_OP,
                    attachmentFilesDir = generationManager.generationDir(generationId),
                    generationId = generationId,
                )
                if (opened is RepositoryResult.Success && generationManager.isIncomplete(generationId)) {
                    try {
                        generationManager.markComplete(generationId)
                    } catch (e: Throwable) {
                        opened.value.close()
                        throw e
                    }
                }
                opened
            }
        }
    }
}

/**
 * Compatibility mapping of the engine outcome into the existing result
 * categories (ADR 0016): Ready becomes `Success`; blocked storage stays
 * `StorageExhausted`; every other blocked outcome — including unclassified
 * failures, which the pre-delegation `open()` used to surface as
 * `Unexpected` — now stably maps to `TransactionFailed` (plan M1-T03 Step 3).
 */
internal fun StartupResult.toRepositoryResult(): RepositoryResult<RecordRepository> = when (this) {
    is StartupResult.Ready -> RepositoryResult.Success(repository)
    is StartupResult.Blocked -> when (failure) {
        StartupFailure.StorageExhausted -> RepositoryResult.Failure.StorageExhausted
        StartupFailure.RecoveryRequired, StartupFailure.Unexpected -> RepositoryResult.Failure.TransactionFailed
    }
}

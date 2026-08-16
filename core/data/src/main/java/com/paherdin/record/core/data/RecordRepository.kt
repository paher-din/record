package com.paherdin.record.core.data

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.paherdin.record.core.data.room.Account
import com.paherdin.record.core.data.room.AccountEntry
import com.paherdin.record.core.data.room.EntityRef
import com.paherdin.record.core.data.room.Note
import com.paherdin.record.core.data.room.RecordDao
import com.paherdin.record.core.data.room.RecordDatabase
import com.paherdin.record.core.data.room.Transaction
import com.paherdin.record.core.data.room.TransactionNoteRelation
import com.paherdin.record.core.data.restore.ActivationRecord
import com.paherdin.record.core.data.restore.ActivationRecordStore
import com.paherdin.record.core.data.restore.BootstrapResult
import com.paherdin.record.core.data.restore.ActivationOutcome
import com.paherdin.record.core.data.restore.GenerationActivation
import com.paherdin.record.core.data.restore.GenerationManager
import com.paherdin.record.core.data.restore.GenerationState
import com.paherdin.record.core.data.restore.LegacyGenerationBootstrap
import com.paherdin.record.core.data.restore.PreflightResult
import com.paherdin.record.core.data.restore.RestoreActivationCoordinator
import com.paherdin.record.core.data.restore.RestoreFaultHook
import com.paherdin.record.core.data.restore.RestorePreflight
import com.paherdin.record.core.data.restore.RestoreStager
import com.paherdin.record.core.data.restore.StagingOutcome
import com.paherdin.record.core.data.restore.VaultBinding
import com.paherdin.record.core.data.restore.VaultOperationGate
import com.paherdin.record.core.data.restore.VaultSessionRegistry
import com.paherdin.record.core.data.restore.openGenerationDatabase
import com.paherdin.record.core.data.backup.BackupExporter
import com.paherdin.record.core.data.backup.BackupFaultHook
import com.paherdin.record.core.data.backup.BackupIoException
import com.paherdin.record.core.data.backup.LogicalType
import com.paherdin.record.core.data.RepositoryResult.Failure.AttachmentCorrupted
import com.paherdin.record.core.data.RepositoryResult.Failure.AttachmentIoFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.AttachmentRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.BackupIoFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.BackupRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.CommittedButResponseFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.DuplicateIdentity
import com.paherdin.record.core.data.RepositoryResult.Failure.EndpointRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.FinanceRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.RestoreIoFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.RestoreRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.StorageExhausted
import com.paherdin.record.core.data.RepositoryResult.Failure.TransactionFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.Unexpected
import com.paherdin.record.core.data.RepositoryResult.Failure.ValidationRejected
import com.paherdin.record.core.data.RepositoryResult.Success
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Same-type ID already exists; nothing committed. */
private class DuplicateIdentityException : IllegalStateException()

/** The currently staged restore candidate and its exact receipt digest. */
internal data class StagedCandidate(val candidateId: String, val receiptDigest: String)

/** A referenced endpoint is missing or wrongly typed; nothing committed. */
private class EndpointRejectedException : IllegalStateException()

/** Hook-originated exception, attributed as a simulated fault. */
internal class HookFaultException(val fromCommitResponse: Boolean) : RuntimeException()

/**
 * The only public write surface of `:core:data` (spec 5.1, ADR 0006).
 * Every write runs one `withTransaction` containing the proactive typed
 * checks (occupancy queries → XOR validator first → same-type duplicate →
 * account endpoints → finance-matrix validation → relation endpoints →
 * ordered child writes → `beforeCommit` last), then `afterCommitResponse`
 * after the durable commit. Catch priority per the plan: cancellation
 * rethrown, validation/finance/duplicate/endpoint rejections, storage full,
 * other SQLite exceptions, hook faults, then anything else — raw exceptions
 * never cross the boundary.
 */
class RecordRepository internal constructor(
    private val attachmentLocks: AttachmentHashLockRegistry = AttachmentHashLockRegistry(),
) : Closeable {

    internal constructor(
        binding: VaultBinding,
        attachmentLocks: AttachmentHashLockRegistry,
        hook: TransactionFaultHook = TransactionFaultHook.NO_OP,
        appContext: Context? = null,
        appFilesDir: File? = null,
    ) : this(attachmentLocks) {
        this.binding = binding
        this.hook = hook
        this.appContext = appContext
        this.appFilesDir = appFilesDir
    }

    @Volatile private var binding: VaultBinding? = null
    private var hook: TransactionFaultHook = TransactionFaultHook.NO_OP
    private val database: RecordDatabase get() = checkNotNull(binding).database
    private val dao: RecordDao get() = database.recordDao()
    private val attachmentIo: AttachmentIo get() = checkNotNull(binding).attachmentIo
    private val attachmentStore: AttachmentContentStore get() = checkNotNull(binding).attachmentStore
    private val attachmentIngestor: AttachmentIngestor? get() = checkNotNull(binding).attachmentIngestor
    private val attachmentLinkManager: AttachmentLinkManager? get() = checkNotNull(binding).attachmentLinkManager
    private val attachmentCleaner: AttachmentCleaner? get() = checkNotNull(binding).attachmentCleaner
    private val financeValidator = FinanceMatrixValidator()
    private val gate = VaultOperationGate()
    private var restoreHook: RestoreFaultHook = RestoreFaultHook.NO_OP
    private var appFilesDir: File? = null
    private var appContext: Context? = null
    private var backupHook: BackupFaultHook = BackupFaultHook.NO_OP
    private var providerChunkBytes: Int = 64 * 1024
    private val appVersion: String = ""
    private var currentStaged: StagedCandidate? = null
    private var sessionRootKey: String? = null
    @Volatile private var closed: Boolean = false

    companion object {
        fun open(context: Context): RepositoryResult<RecordRepository> {
            val root = checkNotNull(context.filesDir.parentFile) { "app data root unavailable" }
            return VaultSessionRegistry.open(root) { openProduction(context) }
        }

        private fun openProduction(context: Context): RepositoryResult<RecordRepository> {
            return try {
                val recordFile = recoveryRecordFile(context)
                val legacyStore = MigrationRecoveryRecordStore(recordFile)
                val activationStore = ActivationRecordStore(recordFile)
                val generationManager = GenerationManager(context)
                val bootstrap = LegacyGenerationBootstrap(
                    context = context,
                    activationStore = activationStore,
                    legacyStore = legacyStore,
                    generationManager = generationManager,
                    coordinator = MigrationCoordinator(context, legacyStore),
                )

                val bootstrapResult = generationManager.underLock { bootstrap.classify() }
                val fresh = bootstrapResult is BootstrapResult.Fresh
                var record = when (bootstrapResult) {
                    BootstrapResult.Fresh -> generationManager.underLock {
                        val created = ActivationRecord(
                            migrationRecoveryState = MigrationRecoveryState.MIGRATION_HEALTHY,
                            migration = null,
                            generationActivation = generationManager.createInitialGeneration(),
                        )
                        activationStore.write(created)
                        created
                    }
                    is BootstrapResult.Ready -> bootstrapResult.record
                    is BootstrapResult.Blocked -> return TransactionFailed
                }
                if (record.generationActivation.state == GenerationState.PENDING) {
                    // Cold-start resolution of a durable PENDING candidate (same resolver as confirm).
                    val coordinator = RestoreActivationCoordinator(context, activationStore, generationManager, RestoreFaultHook.NO_OP)
                    record = when (val outcome = runBlocking { coordinator.resolvePending(record) }) {
                        is ActivationOutcome.Activated -> outcome.record
                        is ActivationOutcome.RolledBack -> outcome.record
                        is ActivationOutcome.Blocked -> return TransactionFailed
                    }
                }
                if (record.migrationRecoveryState != MigrationRecoveryState.MIGRATION_HEALTHY) return TransactionFailed
                if (record.generationActivation.state != GenerationState.HEALTHY) return TransactionFailed

                generationManager.underLock {
                    generationManager.orphanCandidates(record).forEach { orphan ->
                        generationManager.deleteGenerationDirectory(orphan.name)
                    }
                }

                val generationId = record.generationActivation.activeGenerationId
                val databaseFile = generationManager.databaseFile(generationId)
                val resumableFresh = generationManager.isIncomplete(generationId)
                if (!fresh && !resumableFresh && !databaseFile.isFile) return TransactionFailed
                val opened = openWithBuilder(
                    context = context,
                    hook = TransactionFaultHook.NO_OP,
                    builder = { ctx -> openGenerationDatabase(ctx, databaseFile) },
                    attachmentHook = AttachmentFaultHook.NO_OP,
                    attachmentFilesDir = generationManager.generationDir(generationId),
                    generationId = generationId,
                )
                if (opened is Success && generationManager.isIncomplete(generationId)) {
                    try {
                        generationManager.markComplete(generationId)
                    } catch (e: Throwable) {
                        opened.value.close()
                        throw e
                    }
                }
                opened
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLiteFullException) {
                StorageExhausted
            } catch (e: SQLiteException) {
                TransactionFailed
            } catch (e: MigrationGateException) {
                TransactionFailed
            } catch (e: IllegalArgumentException) {
                TransactionFailed
            } catch (e: Throwable) {
                Unexpected
            }
        }

        /** The app-private activation/recovery record (data and storage, plan Task 3). */
        internal fun recoveryRecordFile(context: Context): File =
            File(context.filesDir, "recovery/activation-recovery-v1.json")

        /**
         * Internal unified mapping function: the typed `open()` boundary is
         * runtime-verified here. `CancellationException` is rethrown; any
         * other construction-stage exception (Room builder configuration
         * errors) maps to [RepositoryResult.Failure.Unexpected]. The database
         * file is created lazily by Room; storage failures belong to the typed
         * read/write methods.
         */
        internal fun openWithBuilder(
            context: Context,
            hook: TransactionFaultHook,
            builder: (Context) -> RecordDatabase,
            attachmentHook: AttachmentFaultHook = AttachmentFaultHook.NO_OP,
            attachmentFilesDir: File = context.filesDir,
            generationId: String = "gen-test",
        ): RepositoryResult<RecordRepository> = try {
            val database = builder(context)
            // Task 3: force the writable database before reporting success so a
            // lazy Room builder cannot report success before migration/open
            // completes (plan Task 3 startup order).
            database.openHelper.writableDatabase
            val locks = AttachmentHashLockRegistry()
            val binding = buildBinding(database, attachmentFilesDir, attachmentHook, locks, generationId)
            Success(RecordRepository(binding, locks, hook, context, context.filesDir))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteFullException) {
            StorageExhausted
        } catch (e: SQLiteException) {
            TransactionFailed
        } catch (e: Throwable) {
            Unexpected
        }

        private fun buildBinding(
            database: RecordDatabase,
            attachmentRoot: File,
            attachmentHook: AttachmentFaultHook,
            attachmentLocks: AttachmentHashLockRegistry,
            generationId: String,
        ): VaultBinding {
            val io = AttachmentIo(attachmentHook)
            val store = AttachmentContentStore(attachmentRoot, io)
            val ingestor = AttachmentIngestor(
                store, io, attachmentLocks, database.recordDao(),
                clock = { System.currentTimeMillis() }, hook = attachmentHook,
            )
            val linkManager = AttachmentLinkManager(
                store, attachmentLocks, database.recordDao(),
                clock = { System.currentTimeMillis() }, hook = attachmentHook,
            )
            val cleaner = AttachmentCleaner(
                store, attachmentLocks, database.recordDao(),
                clock = { System.currentTimeMillis() }, hook = attachmentHook,
            )
            return VaultBinding(generationId, database, io, store, ingestor, linkManager, cleaner)
        }
    }


    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        // Closing swaps under the exclusive gate so a concurrent confirmation
        // cannot hand the repository a new binding after the close decision.
        val closeResources = {
            binding?.let { it.close() }
            Unit
        }
        val key = sessionRootKey
        gate.exclusiveBlocking {
            if (key == null) closeResources() else VaultSessionRegistry.releaseAndClose(key, this, closeResources)
        }
    }

    internal fun attachSessionRootKey(key: String) {
        check(sessionRootKey == null) { "repository already belongs to a vault session" }
        sessionRootKey = key
    }

    /**
     * Ingests an image file (plan Task 5, spec 4.7): copies to app-private
     * TEMP, validates declared/decoded MIME, size, and decodability, computes
     * SHA-256, deduplicates under per-hash serialization, publishes the blob
     * atomically, and commits the zero-link canonical row with
     * `deletedAt = ingestedAt`. Returns the canonical `contentIdentity` only
     * after the row/blob pair is durably prepared.
     */
    suspend fun ingestAttachment(source: File, declaredMimeType: String): RepositoryResult<String> = gate.shared { try {
        val ingestor = checkNotNull(attachmentIngestor) { "attachment pipeline not assembled" }
        when (val outcome = ingestor.ingest(source, declaredMimeType)) {
            is IngestSuccess -> Success(outcome.contentIdentity)
            is IngestRejected -> AttachmentRejected(outcome.violation)
            is IngestCorrupted -> AttachmentCorrupted(outcome.detail)
            is IngestIoFailed -> AttachmentIoFailed(outcome.stage)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Unexpected
        }
        }

    /**
     * Exports a complete logical ZIP over the active generation (spec 4.1,
     * plan Task 4): one consistent read, streamed JSONL/attachment entries
     * hashed as written, manifest last, complete-package validation, then
     * publication. Private publication is an atomic rename on the same
     * filesystem after validation; provider publication runs the
     * `BACKUP_SAF_COPY` protocol (plan Task 5). Success is reported only after
     * the package is fully validated and published; the vault is never mutated.
     */
    suspend fun exportBackup(target: BackupTarget): RepositoryResult<BackupArtifact> = gate.shared {
        var tempFile: File? = null
        var published = false
        try {
            val snapshot = database.withTransaction { readAllForExport() }
            val exporter = BackupExporter(
                snapshot = snapshot,
                attachmentContentDir = checkNotNull(attachmentStore).contentDir,
                databaseSchemaVersion = DATABASE_SCHEMA_VERSION,
                appVersion = appVersion,
                hook = backupHook,
            )
            tempFile = when (target) {
                is BackupTarget.PrivateFile -> File(target.file.parentFile, ".tmp-${java.util.UUID.randomUUID()}.zip")
                is BackupTarget.Provider -> File(providerTempDir(), ".tmp-${java.util.UUID.randomUUID()}.zip")
            }
            val built = exporter.build(tempFile)
            if (!exporter.validate(built.file, built.manifest)) {
                tempFile.delete()
                return BackupRejected(BackupViolation.PACKAGE_VALIDATION_FAILED)
            }
            when (target) {
                is BackupTarget.PrivateFile -> {
                    backupHook.beforePrivateRename()
                    if (!built.file.renameTo(target.file)) {
                        tempFile = null
                        return BackupIoFailed(BackupStage.PRIVATE_RENAME)
                    }
                    published = true
                    backupHook.afterPrivateRename()
                    Success(BackupArtifact(built.manifest.backupId, target.file.absolutePath, built.packageSha256))
                }
                is BackupTarget.Provider -> {
                    val result = publishToProvider(built, target.provider)
                    if (result is RepositoryResult.Failure) tempFile.delete()
                    result
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupIoException) {
            if (!published) tempFile?.delete()
            BackupIoFailed(e.stage)
        } catch (e: Throwable) {
            if (!published) tempFile?.delete()
            Unexpected
        }
    }

    /** The `BACKUP_SAF_COPY` protocol (spec 4.1, plan Task 5), with a named stage per step. */
    private suspend fun publishToProvider(
        built: com.paherdin.record.core.data.backup.BuiltPackage,
        provider: BackupProviderTarget,
    ): RepositoryResult<BackupArtifact> {
        val created = try {
            provider.createIncomplete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return BackupIoFailed(BackupStage.PROVIDER_CREATE)
        }
        if (!created) return BackupIoFailed(BackupStage.PROVIDER_CREATE)
        providerStep(BackupStage.PROVIDER_COPY_CHUNK) {
            built.file.inputStream().buffered().use { input ->
                val buffer = ByteArray(providerChunkBytes)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    provider.writeChunk(buffer.copyOf(read))
                }
            }
        }?.let { return it }
        providerStep(BackupStage.PROVIDER_FLUSH_CLOSE) { provider.flushAndClose() }?.let { return it }
        val verified = try {
            provider.verify()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return BackupIoFailed(BackupStage.PROVIDER_VERIFY)
        }
        if (!verified) return BackupIoFailed(BackupStage.PROVIDER_VERIFY)
        providerStep(BackupStage.PROVIDER_RENAME) { provider.renameToFinalIfSupported() }?.let { return it }
        providerStep(BackupStage.PROVIDER_MARKER_CLEAR) { provider.clearIncompleteMarker() }?.let { return it }
        return Success(BackupArtifact(built.manifest.backupId, built.file.absolutePath, built.packageSha256))
    }

    /** Runs one provider step; returns the typed failure when the step throws. */
    private suspend fun providerStep(
        stage: BackupStage,
        block: suspend () -> Unit,
    ): BackupIoFailed? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        BackupIoFailed(stage)
    }

    private suspend fun readAllForExport(): Map<LogicalType, List<Any>> = database.withTransaction {
        mapOf(
            LogicalType.EntityRef to dao.allEntityRefs(),
            LogicalType.Account to dao.allAccounts(),
            LogicalType.Transaction to dao.allTransactions(),
            LogicalType.AccountEntry to dao.allAccountEntries(),
            LogicalType.Note to dao.allNotes(),
            LogicalType.TransactionNoteAssociation to dao.allRelations(),
            LogicalType.Attachment to dao.allAttachments(),
            LogicalType.AttachmentLink to dao.allAttachmentLinks(),
        )
    }

    private fun providerTempDir(): File {
        val dir = File(checkNotNull(appFilesDir), "backups")
        dir.mkdirs()
        return dir
    }

    internal fun setBackupHookForTest(hook: BackupFaultHook) {
        backupHook = hook
    }

    internal fun setProviderChunkBytesForTest(bytes: Int) {
        providerChunkBytes = bytes
    }

    /**
     * Stages a candidate from a preflighted package into an isolated
     * unreferenced generation and validates it (spec 4.5, plan Task 7). The
     * current vault is untouched. The returned handle binds the exact
     * candidate for `confirmRestore`.
     */
    suspend fun stageRestore(source: File): RepositoryResult<StagedRestore> = gate.shared {
        val context = appContext ?: return RestoreIoFailed(RestoreStage.STAGING_IMPORT)
        try {
            val space = appFilesDir?.usableSpace ?: 0L
            val manifest = when (val preflight = RestorePreflight(source, space).run()) {
                is PreflightResult.Rejected -> return RestoreRejected(preflight.violation)
                is PreflightResult.Accepted -> preflight.manifest
            }
            // Step-0 preflight maintenance (spec 4.5, plan Task 8): no staging while
            // activation is pending; pending retirement completes idempotently first.
            val activationStore = ActivationRecordStore(recoveryRecordFile(context))
            val current = try {
                activationStore.read()
            } catch (e: Exception) {
                null
            }
            if (current != null) {
                if (current.generationActivation.state == GenerationState.PENDING) {
                    return RestoreRejected(RestoreViolation.MAINTENANCE_PENDING)
                }
                current.generationActivation.retiringGenerationId?.let { retiring ->
                    val generationManager = GenerationManager(context)
                    val coordinator = RestoreActivationCoordinator(context, activationStore, generationManager, RestoreFaultHook.NO_OP)
                    try {
                        generationManager.underLock { coordinator.retire(retiring) }
                    } catch (e: Exception) {
                        return RestoreRejected(RestoreViolation.MAINTENANCE_PENDING)
                    }
                }
            }
            val stager = RestoreStager(context, GenerationManager(context))
            when (val outcome = stager.stage(source, manifest, sha256File(source))) {
                is StagingOutcome.Rejected -> RestoreRejected(outcome.violation)
                is StagingOutcome.Ready -> {
                    currentStaged = StagedCandidate(outcome.candidateId, outcome.receipt.digest())
                    Success(
                        StagedRestore(
                            candidateId = outcome.candidateId,
                            validationDigest = outcome.receipt.digest(),
                            recordCounts = outcome.recordCounts,
                            attachmentCount = outcome.attachmentCount,
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Unexpected
        }
    }

    internal fun currentStagedCandidate(): StagedCandidate? = currentStaged

    internal fun currentGenerationId(): String = checkNotNull(binding).generationId

    internal fun setRestoreHookForTest(hook: RestoreFaultHook) {
        restoreHook = hook
    }

    /**
     * Confirms a staged candidate (spec 4.5, ADR 0010, plan Task 8): verifies
     * the exact receipt-bound handle, takes the exclusive gate, closes the old
     * generation binding before `PENDING`, runs the same candidate resolver a
     * cold startup would run, and atomically swaps the same repository instance
     * to the healthy candidate (or back to the original on rollback). After
     * `PENDING` is durable, resolution is non-cancellable.
     */
    suspend fun confirmRestore(staged: StagedRestore): RepositoryResult<Unit> {
        val context = appContext ?: return RestoreIoFailed(RestoreStage.ACTIVATION_SWITCH)
        return try {
            gate.exclusive {
                val current = currentStaged
                if (current == null || current.candidateId != staged.candidateId || current.receiptDigest != staged.validationDigest) {
                    return@exclusive RestoreRejected(RestoreViolation.CANDIDATE_MISMATCH)
                }
                val generationManager = GenerationManager(context)
                val activationStore = ActivationRecordStore(recoveryRecordFile(context))
                val record = try {
                    activationStore.read()
                } catch (e: Exception) {
                    return@exclusive RestoreIoFailed(RestoreStage.ACTIVATION_SWITCH)
                } ?: return@exclusive RestoreIoFailed(RestoreStage.ACTIVATION_SWITCH)
                val coordinator = RestoreActivationCoordinator(context, activationStore, generationManager, restoreHook)
                val isPending = record.generationActivation.state == GenerationState.PENDING
                if (isPending && record.generationActivation.activeGenerationId != staged.candidateId) {
                    return@exclusive RestoreRejected(RestoreViolation.CANDIDATE_MISMATCH)
                }
                if (!isPending) {
                    val oldBinding = checkNotNull(binding)
                    restoreHook.beforeCloseOldBinding()
                    oldBinding.close()
                    restoreHook.afterCloseOldBinding()
                }
                try {
                    withContext(NonCancellable) {
                        val outcome = if (isPending) {
                            coordinator.resolvePending(record)
                        } else {
                            coordinator.confirm(record, staged.candidateId)
                        }
                        when (outcome) {
                            is ActivationOutcome.Activated -> {
                                binding = buildBindingForRepository(context, generationManager, staged.candidateId)
                                currentStaged = null
                                Success(Unit)
                            }
                            is ActivationOutcome.RolledBack -> {
                                binding = buildBindingForRepository(context, generationManager, outcome.record.generationActivation.activeGenerationId)
                                currentStaged = null
                                RestoreRejected(RestoreViolation.CANDIDATE_MISMATCH)
                            }
                            is ActivationOutcome.Blocked -> RestoreIoFailed(RestoreStage.ACTIVATION_SWITCH)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Rebuild the binding from the durable record so the repository
                    // never holds a closed database after a failed transition.
                    val durable = try {
                        activationStore.read()
                    } catch (e2: Exception) {
                        null
                    }
                    val targetGen = durable?.generationActivation?.activeGenerationId
                        ?: record.generationActivation.activeGenerationId
                    try {
                        binding = buildBindingForRepository(context, generationManager, targetGen)
                    } catch (e2: Throwable) {
                        // The durable generation itself is unopenable; surface the failure.
                    }
                    Unexpected
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Unexpected
        }
    }

    private fun buildBindingForRepository(
        context: Context,
        generationManager: GenerationManager,
        generationId: String,
    ): VaultBinding {
        val db = openGenerationDatabase(context, generationManager.databaseFile(generationId))
        val genDir = generationManager.generationDir(generationId)
        val io = AttachmentIo(AttachmentFaultHook.NO_OP)
        val store = AttachmentContentStore(genDir, io)
        val ingestor = AttachmentIngestor(
            store, io, attachmentLocks, db.recordDao(),
            clock = { System.currentTimeMillis() }, hook = AttachmentFaultHook.NO_OP,
        )
        val linkManager = AttachmentLinkManager(
            store, attachmentLocks, db.recordDao(),
            clock = { System.currentTimeMillis() }, hook = AttachmentFaultHook.NO_OP,
        )
        val cleaner = AttachmentCleaner(
            store, attachmentLocks, db.recordDao(),
            clock = { System.currentTimeMillis() }, hook = AttachmentFaultHook.NO_OP,
        )
        return VaultBinding(generationId, db, io, store, ingestor, linkManager, cleaner)
    }

    private fun sha256File(file: File): String = file.inputStream().use {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private val DATABASE_SCHEMA_VERSION = 2

    suspend fun createAccount(account: NewAccount): RepositoryResult<Unit> = gate.shared { try {
        runHook { hook.beforeTransaction() }
        db.withTransaction {
                if (dao.getAccount(account.id) != null) throw DuplicateIdentityException()
                dao.insertAccount(Account(account.id, account.name, account.currency))
                runHook { hook.afterChildWrite(0) }
                runHook { hook.beforeCommit() }
            }
            runHook(afterCommitResponse = true) { hook.afterCommitResponse() }
            Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: XorViolationException) {
            ValidationRejected(e.violation)
        } catch (e: DuplicateIdentityException) {
            DuplicateIdentity
        } catch (e: EndpointRejectedException) {
            EndpointRejected
        } catch (e: SQLiteFullException) {
            StorageExhausted
        } catch (e: SQLiteException) {
            TransactionFailed
        } catch (e: HookFaultException) {
            if (e.fromCommitResponse) CommittedButResponseFailed else TransactionFailed
        } catch (e: Throwable) {
            Unexpected
        }
        }

    suspend fun saveTransactionAggregate(tx: NewTransaction): RepositoryResult<Unit> = gate.shared { try {
        runHook { hook.beforeTransaction() }
        db.withTransaction {
                val transactionExists = dao.getTransaction(tx.id) != null
                val noteExists = dao.getNote(tx.id) != null
                // XOR validator first, on the candidate occupancy this write produces.
                XorInvariantValidator.validate(EntityType.TRANSACTION, true, noteExists)
                if (transactionExists || dao.getEntityRef(tx.id) != null) throw DuplicateIdentityException()
                // Step 4: resolve every entry account endpoint (deduplicated) and
                // build the stored-currency map; any missing account is EndpointRejected
                // before any finance classification.
                val currencyByAccountId = tx.entries.map { it.accountId }.distinct().associateWith { accountId ->
                    dao.getAccount(accountId)?.currency ?: throw EndpointRejectedException()
                }
                // Step 5: finance-matrix validation with deterministic precedence.
                financeValidator.validate(tx.type, tx.entries, currencyByAccountId)
                if (tx.relationNoteIds.any { dao.getNote(it) == null }) throw EndpointRejectedException()
                dao.insertEntityRef(EntityRef(tx.id, EntityType.TRANSACTION, tx.createdAt, tx.updatedAt, tx.deletedAt))
                runHook { hook.afterChildWrite(0) }
                dao.insertTransaction(Transaction(tx.id, tx.type))
                runHook { hook.afterChildWrite(1) }
                // Step 7: deterministic entry order. After validation a transfer has
                // exactly one negative and one positive amount, so sorting ascending
                // writes the negative source at ordinal 2 before the positive
                // destination at ordinal 3 regardless of input iteration order.
                // Single-entry types write their one entry at ordinal 2, keeping the
                // P1 ordinals; the relation stage follows at 2 + entries.size.
                tx.entries.sortedBy { it.amountMinor }.forEachIndexed { index, entry ->
                    dao.insertAccountEntry(AccountEntry(entry.id, tx.id, entry.accountId, entry.amountMinor))
                    runHook { hook.afterChildWrite(index + 2) }
                }
                if (tx.relationNoteIds.isNotEmpty()) {
                    dao.insertAllRelations(tx.relationNoteIds.map { TransactionNoteRelation(tx.id, it) })
                }
                runHook { hook.afterChildWrite(2 + tx.entries.size) }
                if (tx.attachmentContentIds.isNotEmpty()) {
                    attachmentLinkManager!!.linkAll(tx.attachmentContentIds, tx.id)
                    runHook { hook.afterChildWrite(3 + tx.entries.size) }
                }
                runHook { hook.beforeCommit() }
            }
            runHook(afterCommitResponse = true) { hook.afterCommitResponse() }
            Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: XorViolationException) {
            ValidationRejected(e.violation)
        } catch (e: FinanceViolationException) {
            FinanceRejected(e.violation)
        } catch (e: AttachmentRejectedException) {
            AttachmentRejected(e.violation)
        } catch (e: AttachmentCorruptedException) {
            AttachmentCorrupted(e.detail)
        } catch (e: DuplicateIdentityException) {
            DuplicateIdentity
        } catch (e: EndpointRejectedException) {
            EndpointRejected
        } catch (e: SQLiteFullException) {
            StorageExhausted
        } catch (e: SQLiteException) {
            TransactionFailed
        } catch (e: HookFaultException) {
            if (e.fromCommitResponse) CommittedButResponseFailed else TransactionFailed
        } catch (e: Throwable) {
            Unexpected
        }
        }

    suspend fun saveNoteAggregate(note: NewNote): RepositoryResult<Unit> = gate.shared { try {
        runHook { hook.beforeTransaction() }
        db.withTransaction {
                val transactionExists = dao.getTransaction(note.id) != null
                val noteExists = dao.getNote(note.id) != null
                XorInvariantValidator.validate(EntityType.NOTE, transactionExists, true)
                if (noteExists || dao.getEntityRef(note.id) != null) throw DuplicateIdentityException()
                if (note.relationTransactionIds.any { dao.getTransaction(it) == null }) throw EndpointRejectedException()
                dao.insertEntityRef(EntityRef(note.id, EntityType.NOTE, note.createdAt, note.updatedAt, note.deletedAt))
                runHook { hook.afterChildWrite(0) }
                dao.insertNote(Note(note.id))
                runHook { hook.afterChildWrite(1) }
                if (note.relationTransactionIds.isNotEmpty()) {
                    dao.insertAllRelations(note.relationTransactionIds.map { TransactionNoteRelation(it, note.id) })
                }
                runHook { hook.afterChildWrite(2) }
                if (note.attachmentContentIds.isNotEmpty()) {
                    attachmentLinkManager!!.linkAll(note.attachmentContentIds, note.id)
                    runHook { hook.afterChildWrite(3) }
                }
                runHook { hook.beforeCommit() }
            }
            runHook(afterCommitResponse = true) { hook.afterCommitResponse() }
            Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: XorViolationException) {
            ValidationRejected(e.violation)
        } catch (e: AttachmentRejectedException) {
            AttachmentRejected(e.violation)
        } catch (e: AttachmentCorruptedException) {
            AttachmentCorrupted(e.detail)
        } catch (e: DuplicateIdentityException) {
            DuplicateIdentity
        } catch (e: EndpointRejectedException) {
            EndpointRejected
        } catch (e: SQLiteFullException) {
            StorageExhausted
        } catch (e: SQLiteException) {
            TransactionFailed
        } catch (e: HookFaultException) {
            if (e.fromCommitResponse) CommittedButResponseFailed else TransactionFailed
        } catch (e: Throwable) {
            Unexpected
        }
        }

    suspend fun replaceTransactionNoteRelations(transactionId: String, noteIds: Set<String>): RepositoryResult<Unit> = gate.shared { try {
        runHook { hook.beforeTransaction() }
        db.withTransaction {
                if (dao.getTransaction(transactionId) == null) throw EndpointRejectedException()
                if (noteIds.any { dao.getNote(it) == null }) throw EndpointRejectedException()
                dao.deleteRelationsForTransaction(transactionId)
                runHook { hook.afterChildWrite(0) }
                if (noteIds.isNotEmpty()) {
                    dao.insertAllRelations(noteIds.map { TransactionNoteRelation(transactionId, it) })
                }
                runHook { hook.afterChildWrite(1) }
                runHook { hook.beforeCommit() }
            }
            runHook(afterCommitResponse = true) { hook.afterCommitResponse() }
            Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: XorViolationException) {
            ValidationRejected(e.violation)
        } catch (e: DuplicateIdentityException) {
            DuplicateIdentity
        } catch (e: EndpointRejectedException) {
            EndpointRejected
        } catch (e: SQLiteFullException) {
            StorageExhausted
        } catch (e: SQLiteException) {
            TransactionFailed
        } catch (e: HookFaultException) {
            if (e.fromCommitResponse) CommittedButResponseFailed else TransactionFailed
        } catch (e: Throwable) {
            Unexpected
        }
        }

    /**
     * Links an existing Transaction/Note owner to canonical content (spec
     * 4.7): under the shared per-hash serialization, rechecks the target,
     * verifies the actual bytes, clears the zero-link cleanup marker, and
     * commits the link in one Room transaction. Unknown content, missing
     * bytes, or corruption rejects with nothing committed; a non-owner
     * endpoint is `EndpointRejected`.
     */
    suspend fun linkAttachment(ownerEntityRefId: String, contentIdentity: String): RepositoryResult<Unit> = gate.shared { try {
        runHook { hook.beforeTransaction() }
        db.withTransaction {
            if (!isValidAttachmentOwner(ownerEntityRefId)) throw EndpointRejectedException()
            // Per-hash serialization is global (registry doc): the dedicated
            // link path must hold the same lock as owner-save linking and the
            // cleaner, or the cleaner could delete the blob of a row this link
            // is about to revive.
            attachmentLocks.withLock(contentIdentity) {
                attachmentLinkManager!!.linkOne(contentIdentity, ownerEntityRefId)
            }
            runHook { hook.beforeCommit() }
        }
        runHook(afterCommitResponse = true) { hook.afterCommitResponse() }
        Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: AttachmentRejectedException) {
        AttachmentRejected(e.violation)
    } catch (e: AttachmentCorruptedException) {
        AttachmentCorrupted(e.detail)
    } catch (e: EndpointRejectedException) {
        EndpointRejected
    } catch (e: SQLiteFullException) {
        StorageExhausted
    } catch (e: SQLiteException) {
        TransactionFailed
    } catch (e: HookFaultException) {
        if (e.fromCommitResponse) CommittedButResponseFailed else TransactionFailed
    } catch (e: Throwable) {
        Unexpected
        }
        }

    /**
     * Removes the owner link in one transaction; at zero global link count
     * the canonical Attachment enters cleanup pending (`deletedAt =
     * clock.now()`) in the same transaction. Idempotent: unlinking content
     * or a link that does not exist is a no-op success.
     */
    suspend fun unlinkAttachment(ownerEntityRefId: String, contentIdentity: String): RepositoryResult<Unit> = gate.shared { try {
        runHook { hook.beforeTransaction() }
        db.withTransaction {
            if (!isValidAttachmentOwner(ownerEntityRefId)) throw EndpointRejectedException()
            attachmentLinkManager!!.unlinkOne(contentIdentity, ownerEntityRefId)
            runHook { hook.beforeCommit() }
        }
        runHook(afterCommitResponse = true) { hook.afterCommitResponse() }
        Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: EndpointRejectedException) {
        EndpointRejected
    } catch (e: SQLiteFullException) {
        StorageExhausted
    } catch (e: SQLiteException) {
        TransactionFailed
    } catch (e: HookFaultException) {
        if (e.fromCommitResponse) CommittedButResponseFailed else TransactionFailed
    } catch (e: Throwable) {
        Unexpected
        }
        }

    /** Internal test seam: runs the cleaner and returns its report. */
    internal suspend fun runAttachmentCleanup(): AttachmentCleaner.Report =
        checkNotNull(attachmentCleaner) { "attachment pipeline not assembled" }.run()

    /** The owner endpoint must exist and be a Transaction or Note EntityRef (ADR 0002). */
    private suspend fun isValidAttachmentOwner(ownerEntityRefId: String): Boolean {
        val ref = dao.getEntityRef(ownerEntityRefId) ?: return false
        return when (ref.entityType) {
            EntityType.TRANSACTION -> dao.getTransaction(ownerEntityRefId) != null
            EntityType.NOTE -> dao.getNote(ownerEntityRefId) != null
            EntityType.PROJECT -> false
        }
    }

    suspend fun getTransactionView(id: String): RepositoryResult<TransactionView?> = gate.shared { try {
        db.withTransaction {
            val ref = dao.getEntityRef(id)
            val row = dao.getTransactionWithEntries(id)
            if (ref == null || row == null) {
                null
            } else {
                TransactionView(
                    id = row.transaction.id,
                    type = row.transaction.type,
                    createdAt = ref.createdAt,
                    updatedAt = ref.updatedAt,
                    deletedAt = ref.deletedAt,
                    entries = row.entries.map { NewAccountEntry(it.id, it.accountId, it.amountMinor) },
                    relationNoteIds = dao.getRelationsForTransaction(id).map { it.noteId }.toSet(),
                )
            }
        }.let { Success(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SQLiteFullException) {
        StorageExhausted
    } catch (e: Throwable) {
        Unexpected
        }
        }

    suspend fun getNoteView(id: String): RepositoryResult<NoteView?> = gate.shared { try {
        db.withTransaction {
            val ref = dao.getEntityRef(id)
            if (ref == null || dao.getNote(id) == null) {
                null
            } else {
                NoteView(
                    id = id,
                    createdAt = ref.createdAt,
                    updatedAt = ref.updatedAt,
                    deletedAt = ref.deletedAt,
                    relationTransactionIds = dao.getRelationsForNote(id).map { it.transactionId }.toSet(),
                )
            }
        }.let { Success(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SQLiteFullException) {
        StorageExhausted
    } catch (e: Throwable) {
        Unexpected
        }
        }

    private fun runHook(afterCommitResponse: Boolean = false, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw HookFaultException(afterCommitResponse)
        }
    }

    private inline val db: RecordDatabase get() = database
}

/**
 * Internal test factory: same unified mapping function as the public `open()`,
 * with an injectable test builder and fault hook on an isolated `dbName`.
 */
internal fun openForTest(
    context: Context,
    dbName: String,
    hook: TransactionFaultHook = TransactionFaultHook.NO_OP,
    attachmentHook: AttachmentFaultHook = AttachmentFaultHook.NO_OP,
): RecordRepository = when (val result = RecordRepository.openWithBuilder(
    context,
    hook,
    { ctx ->
        Room.databaseBuilder(ctx, RecordDatabase::class.java, dbName)
            .addMigrations(RecordDatabase.MIGRATION_1_2)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    },
    attachmentHook,
)) {
    is Success -> result.value
    is RepositoryResult.Failure -> error("test open of '$dbName' failed with $result")
}

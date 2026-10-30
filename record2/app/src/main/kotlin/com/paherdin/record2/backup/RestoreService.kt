package com.paherdin.record2.backup

import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class RestoreCandidate(
    val id: String,
    val source: BackupSource,
    val sourceDisplayName: String?,
    val sourceBytes: Long,
    val sourceSha256: String,
    val manifest: BackupManifest,
    val targetGeneration: String?,
    val targetAuthEpoch: Long?,
    val preflightAt: Long,
    val appSchemaVersion: Int,
    val appVersionCode: Long,
)

internal data class RestoreResult(
    val restoredGeneration: String,
    val processOwnerId: String,
    val authEpoch: Long,
    val receipt: RestoreReceipt,
    val importedRows: Long,
    val transformStats: RestoreTransformStats,
)

internal enum class RestoreCheckpoint {
    AFTER_DELETE,
    BEFORE_RECEIPT,
    BEFORE_COMMIT,
    AFTER_COMMIT,
}

internal class RestoreService(
    private val store: RestoreStore,
    private val databasePath: File,
    private val stagingDirectory: File,
    private val gate: DataOperationGate,
    private val clock: Clock = Clock.systemUTC(),
    private val availableBytes: (File) -> Long = { FileSpace.availableBytes(it) },
    private val checkpoint: (RestoreCheckpoint) -> Unit = {},
    private val memoryBarrier: RestoreMemoryBarrier = ProcessRestoreMemoryBarrier,
    private val processOwnerId: String? = null,
    private val currentAppVersionCode: Long = 1,
    private val cleanupStaging: (File) -> Boolean = { it.deleteRecursively() },
    /** Observability hook only: cleanup failure never changes a committed restore or the gate. */
    private val onStagingCleanupFailure: (File, Throwable) -> Unit = { _, _ -> },
) : RestoreEngine {
    override suspend fun preflight(source: BackupSource): RestoreCandidate = withContext(Dispatchers.IO) {
        if (gate.isRestoreInProgress) throw DataOperationBusyException("Restore is already in progress")
        stagingDirectory.mkdirs()
        val work = BackupStaging.createTask(stagingDirectory, "preflight")
        val zip = File(work, "source.zip")
        val context = currentCoroutineContext()
        val check = { context.ensureActive() }
        try {
            val bytes = source.copyTo(zip, BackupLimits.MAX_ZIP_BYTES)
            check()
            if (bytes > BackupLimits.MAX_ZIP_BYTES) fail(BackupError.InvalidSource, "backup exceeds 64 MiB")
            val now = clock.millis()
            val validator = StreamingBackupValidator(now)
            val streamed = LogicalZipReader.forEachRow(zip, check) { segment, row ->
                validator.accept(segment, row)
            }
            check()
            validator.finish(check)
            LogicalBackupValidator.validateManifest(streamed.manifest, now)
            check()
            val target = ReadOnlyTargetInspector.inspect(databasePath)
            checkDisk(streamed.manifest, bytes, target)
            RestoreCandidate(
                id = UUID.randomUUID().toString(),
                source = source,
                sourceDisplayName = source.displayName,
                sourceBytes = bytes,
                sourceSha256 = streamed.zipSha256,
                manifest = streamed.manifest,
                targetGeneration = target.generation,
                targetAuthEpoch = target.authEpoch,
                preflightAt = now,
                appSchemaVersion = BackupLimits.DATABASE_SCHEMA_VERSION,
                appVersionCode = currentAppVersionCode,
            )
        } finally {
            try { work.deleteRecursively() } finally { BackupStaging.releaseTask(work) }
        }
    }

    override suspend fun restore(
        candidate: RestoreCandidate,
        progress: (RestoreProgress) -> Unit,
    ): RestoreResult = withContext(Dispatchers.IO) {
        progress(RestoreProgress.WAITING_FOR_GATE)
        val lease = gate.beginRestore()
        // The lease is acquired before any staging/progress work and released by the outermost
        // finally. A throwing PREPARING callback or a failing staging cleanup must never leave
        // the process-wide gate stuck in RESTORE.
        try {
            progress(RestoreProgress.PREPARING)
            stagingDirectory.mkdirs()
            val work = BackupStaging.createTask(stagingDirectory, "restore")
            val zip = File(work, "source.zip")
            try {
                try {
                    withContext(NonCancellable) {
                        restoreLocked(candidate, work, zip, progress)
                    }
                } catch (cancelled: CancellationException) {
                    val committed = committedReceipt(candidate) ?: throw cancelled
                    resultFromReceipt(committed)
                }
            } finally {
                try { cleanupAfterRestore(work) } finally { BackupStaging.releaseTask(work) }
            }
        } finally {
            lease.close()
        }
    }

    private fun cleanupAfterRestore(work: File) {
        val failure = try {
            if (cleanupStaging(work)) null else IOException("staging cleanup did not complete")
        } catch (error: Exception) {
            error
        }
        if (failure != null) {
            runCatching { onStagingCleanupFailure(work, failure) }
        }
    }

    /** Returns the canonical receipt if this candidate already committed, without re-running import. */
    suspend fun committedReceipt(candidate: RestoreCandidate): RestoreReceipt? =
        (receiptLookup(candidate) as? RestoreReceiptLookup.Committed)?.receipt

    override suspend fun receiptLookup(candidate: RestoreCandidate): RestoreReceiptLookup =
        withContext(Dispatchers.IO) {
            val state = store.currentState() ?: return@withContext RestoreReceiptLookup.None
            val text = state.restoreReceipt ?: return@withContext RestoreReceiptLookup.None
            // A fresh candidate was preflighted against the current generation. A matching old
            // receipt must not turn that confirmed replacement into a no-op (A -> new data -> A).
            val generationUnchanged = candidate.targetGeneration == state.dataGeneration
            val receipt = try {
                RestoreReceiptCodec.decode(text, clock.millis())
            } catch (error: BackupException) {
                return@withContext if (generationUnchanged) {
                    // A malformed receipt must not block a valid rescue replacement; startup
                    // gating still reports RecoveryRequired until this new restore commits.
                    RestoreReceiptLookup.None
                } else {
                    RestoreReceiptLookup.Unknown("restore receipt is unreadable: ${error.code}")
                }
            }
            val matchesCandidate = receipt.restoredGeneration == state.dataGeneration &&
                receipt.sourceZipSha256 == candidate.sourceSha256 &&
                receipt.sourceZipBytes == candidate.sourceBytes &&
                receipt.sourceFormatVersion == candidate.manifest.formatVersion &&
                receipt.sourceSchemaVersion == candidate.manifest.databaseSchemaVersion &&
                receipt.contentDigest == candidate.manifest.contentDigest &&
                receipt.sourceGeneration == candidate.manifest.sourceGeneration
            if (!matchesCandidate) {
                // A different valid backup (A -> B) is a new replacement, not an unknown outcome.
                return@withContext RestoreReceiptLookup.None
            }
            if (generationUnchanged) {
                // The preflight target is still current: this is a fresh replacement of the same
                // ZIP, even though a prior restore with the same source committed earlier.
                RestoreReceiptLookup.None
            } else {
                // The generation moved after preflight and the receipt proves this exact ZIP is
                // what committed. This is the lost-observation / same-attempt case.
                RestoreReceiptLookup.Committed(receipt)
            }
        }

    override suspend fun completedResult(receipt: RestoreReceipt): RestoreResult =
        resultFromReceipt(receipt)

    private suspend fun restoreLocked(
        candidate: RestoreCandidate,
        work: File,
        zip: File,
        progress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        val running = runCatching { ReadOnlyTargetInspector.runningRunIds(databasePath) }.getOrNull()
            ?: fail(BackupError.RecoverableIO, "cannot enumerate active runs; restore aborted")
        memoryBarrier.revokeRuns(running)
        memoryBarrier.invalidateInMemory()
        if (candidate.appVersionCode != currentAppVersionCode) {
            fail(BackupError.TargetInvalid, "app version changed since preflight")
        }
        if (!work.isDirectory) fail(BackupError.RecoverableIO, "restore staging directory is missing")
        val copied = candidate.source.copyTo(zip, BackupLimits.MAX_ZIP_BYTES)
        if (copied != candidate.sourceBytes) {
            fail(BackupError.InvalidSource, "source bytes changed since preflight")
        }
        val sourceSha = sha256File(zip)
        if (sourceSha != candidate.sourceSha256) {
            fail(BackupError.InvalidSource, "source digest changed since preflight")
        }
        val restoredAt = clock.millis()
        val validator = StreamingBackupValidator(restoredAt)
        val streamed = LogicalZipReader.forEachRow(zip) { segment, row ->
            validator.accept(segment, row)
        }
        validator.finish()
        if (streamed.manifest != candidate.manifest) {
            fail(BackupError.InvalidSource, "manifest changed since preflight")
        }
        if (streamed.zipSha256 != candidate.sourceSha256 || streamed.zipBytes != copied) {
            fail(BackupError.InvalidSource, "staging backup does not match the preflight digest")
        }
        LogicalBackupValidator.validateManifest(streamed.manifest, restoredAt)
        val target = ReadOnlyTargetInspector.inspect(databasePath)
        if (target.generation != candidate.targetGeneration) {
            fail(BackupError.TargetInvalid, "target generation changed since preflight")
        }
        checkDisk(streamed.manifest, copied, target)
        val newEpoch = when (val old = target.authEpoch) {
            null -> 1L
            else -> old + 1L
        }
        if (newEpoch !in 1..Long.MAX_VALUE - 1) {
            fail(BackupError.TargetInvalid, "restore would write an unusable authEpoch")
        }
        val restoredGeneration = UUID.randomUUID().toString()
        if (restoredGeneration == streamed.manifest.sourceGeneration) {
            fail(BackupError.Internal, "new generation collided with source generation")
        }
        val owner = selectOwner(candidate.manifest, validator)
        val receipt = RestoreReceipt(
            formatVersion = 1,
            sourceZipSha256 = streamed.zipSha256,
            sourceZipBytes = copied,
            sourceFormatVersion = streamed.manifest.formatVersion,
            sourceSchemaVersion = streamed.manifest.databaseSchemaVersion,
            sourceGeneration = streamed.manifest.sourceGeneration,
            restoredGeneration = restoredGeneration,
            restoredAt = restoredAt,
            contentDigest = streamed.manifest.contentDigest,
            importedRows = streamed.manifest.totalRows,
            businessRevisionAtRestore = streamed.manifest.businessRevision,
        )
        val receiptText = RestoreReceiptCodec.encode(receipt)
        progress(RestoreProgress.IMPORTING)
        val transformStats = store.import(
            expectedManifest = candidate.manifest,
            expectedZipSha256 = candidate.sourceSha256,
            expectedZipBytes = copied,
            restoredGeneration = restoredGeneration,
            owner = owner,
            newEpoch = newEpoch,
            receipt = receiptText,
            expectedGeneration = candidate.targetGeneration,
            expectedEpoch = target.authEpoch,
            streamRows = { sink ->
                var transformedRuns = 0
                var transformedProposals = 0
                val imported = LogicalZipReader.forEachRow(zip) { segment, row ->
                    when (segment) {
                        LogicalSegment.RUN -> {
                            val run = row as RunRow
                            if (run.state == "RUNNING") {
                                transformedRuns++
                                sink(
                                    segment,
                                    run.copy(
                                        state = "INTERRUPTED",
                                        activeSlot = null,
                                        finishedAt = maxOf(restoredAt, run.startedAt),
                                    ),
                                )
                            } else {
                                sink(segment, run)
                            }
                        }
                        LogicalSegment.PROPOSAL -> {
                            val proposal = row as ProposalRow
                            if (proposal.state == "EDITABLE" || proposal.state == "LOCKED") {
                                transformedProposals++
                                sink(segment, proposal.copy(state = "NEEDS_REVIEW", activeSlot = null))
                            } else {
                                sink(segment, proposal)
                            }
                        }
                        else -> sink(segment, row)
                    }
                }
                StreamedImport(
                    backup = imported,
                    stats = RestoreTransformStats.Known(transformedRuns, transformedProposals),
                )
            },
            checkpoint = checkpoint,
        )
        checkpoint(RestoreCheckpoint.AFTER_COMMIT)
        progress(RestoreProgress.COMMITTED)
        val result = RestoreResult(
            restoredGeneration = restoredGeneration,
            processOwnerId = owner,
            authEpoch = newEpoch,
            receipt = receipt,
            importedRows = receipt.importedRows,
            transformStats = transformStats,
        )
        progress(RestoreProgress.POST_COMMIT)
        return result
    }

    private suspend fun resultFromReceipt(receipt: RestoreReceipt): RestoreResult {
        val state = store.completionState()
            ?: fail(BackupError.RecoverableIO, "restored data state is missing")
        return RestoreResult(
            restoredGeneration = receipt.restoredGeneration,
            processOwnerId = state.processOwnerId,
            authEpoch = state.authEpoch,
            receipt = receipt,
            importedRows = receipt.importedRows,
            transformStats = RestoreTransformStats.Unknown,
        )
    }

    private fun selectOwner(manifest: BackupManifest, validator: StreamingBackupValidator): String {
        val requested = processOwnerId
        if (requested != null && requested != manifest.sourceGeneration &&
            !validator.usesProcessOwner(requested)
        ) {
            return requested
        }
        while (true) {
            val generated = UUID.randomUUID().toString()
            if (generated != manifest.sourceGeneration && !validator.usesProcessOwner(generated)) {
                return generated
            }
        }
    }

    private fun checkDisk(manifest: BackupManifest, zipBytes: Long, target: TargetSnapshot) {
        val required = zipBytes +
            2L * manifest.totalUncompressedBytes +
            target.databaseBytes +
            target.walBytes +
            BackupLimits.EXPORT_STAGING_RESERVE_BYTES
        val databaseDir = databasePath.parentFile ?: databasePath
        if (availableBytes(databaseDir) < required) {
            fail(BackupError.StorageFull, "database volume lacks the restore disk reserve")
        }
        if (availableBytes(stagingDirectory) < BackupLimits.EXPORT_STAGING_RESERVE_BYTES) {
            fail(BackupError.StorageFull, "staging volume lacks the restore disk reserve")
        }
    }
}

private fun sha256File(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65_536)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

package com.paherdin.record2.backup

import java.io.File
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
) : RestoreEngine {
    override suspend fun preflight(source: BackupSource): RestoreCandidate = withContext(Dispatchers.IO) {
        if (gate.isRestoreInProgress) throw DataOperationBusyException("Restore is already in progress")
        stagingDirectory.mkdirs()
        val work = File(stagingDirectory, "preflight-${UUID.randomUUID()}")
        val zip = File(work, "source.zip")
        val context = currentCoroutineContext()
        val check = { context.ensureActive() }
        try {
            if (!work.mkdirs()) fail(BackupError.RecoverableIO, "could not create preflight directory")
            val bytes = source.copyTo(zip, BackupLimits.MAX_ZIP_BYTES)
            check()
            if (bytes > BackupLimits.MAX_ZIP_BYTES) fail(BackupError.InvalidSource, "backup exceeds 64 MiB")
            val read = LogicalZipReader.read(zip, check)
            check()
            val now = clock.millis()
            LogicalBackupValidator.validate(read.backup, now, check)
            check()
            val target = ReadOnlyTargetInspector.inspect(databasePath)
            checkDisk(read.backup.manifest, bytes, target)
            RestoreCandidate(
                id = UUID.randomUUID().toString(),
                source = source,
                sourceDisplayName = source.displayName,
                sourceBytes = bytes,
                sourceSha256 = read.zipSha256,
                manifest = read.backup.manifest,
                targetGeneration = target.generation,
                targetAuthEpoch = target.authEpoch,
                preflightAt = now,
                appSchemaVersion = BackupLimits.DATABASE_SCHEMA_VERSION,
                appVersionCode = currentAppVersionCode,
            )
        } finally {
            work.deleteRecursively()
        }
    }

    override suspend fun restore(
        candidate: RestoreCandidate,
        progress: (RestoreProgress) -> Unit,
    ): RestoreResult = withContext(Dispatchers.IO) {
        progress(RestoreProgress.WAITING_FOR_GATE)
        val lease = gate.beginRestore()
        progress(RestoreProgress.PREPARING)
        stagingDirectory.mkdirs()
        val work = File(stagingDirectory, "restore-${UUID.randomUUID()}")
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
            work.deleteRecursively()
            lease.close()
        }
    }

    /** Returns the canonical receipt if this candidate already committed, without re-running import. */
    suspend fun committedReceipt(candidate: RestoreCandidate): RestoreReceipt? =
        (receiptLookup(candidate) as? RestoreReceiptLookup.Committed)?.receipt

    override suspend fun receiptLookup(candidate: RestoreCandidate): RestoreReceiptLookup =
        withContext(Dispatchers.IO) {
            val state = store.currentState() ?: return@withContext RestoreReceiptLookup.None
            val text = state.restoreReceipt ?: return@withContext RestoreReceiptLookup.None
            val receipt = try {
                RestoreReceiptCodec.decode(text, clock.millis())
            } catch (error: BackupException) {
                return@withContext RestoreReceiptLookup.Unknown("restore receipt is unreadable: ${error.code}")
            }
            if (receipt.restoredGeneration != state.dataGeneration ||
                receipt.sourceZipSha256 != candidate.sourceSha256 ||
                receipt.sourceZipBytes != candidate.sourceBytes ||
                receipt.sourceFormatVersion != candidate.manifest.formatVersion ||
                receipt.sourceSchemaVersion != candidate.manifest.databaseSchemaVersion ||
                receipt.contentDigest != candidate.manifest.contentDigest ||
                receipt.sourceGeneration != candidate.manifest.sourceGeneration
            ) {
                return@withContext RestoreReceiptLookup.Unknown(
                    "persisted restore receipt does not match the confirmed candidate",
                )
            }
            RestoreReceiptLookup.Committed(receipt)
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
        if (!work.mkdirs()) fail(BackupError.RecoverableIO, "could not create restore staging directory")
        val copied = candidate.source.copyTo(zip, BackupLimits.MAX_ZIP_BYTES)
        if (copied != candidate.sourceBytes) {
            fail(BackupError.InvalidSource, "source bytes changed since preflight")
        }
        val sourceSha = sha256File(zip)
        if (sourceSha != candidate.sourceSha256) {
            fail(BackupError.InvalidSource, "source digest changed since preflight")
        }
        val read = LogicalZipReader.read(zip)
        if (read.backup.manifest != candidate.manifest) {
            fail(BackupError.InvalidSource, "manifest changed since preflight")
        }
        val restoredAt = clock.millis()
        LogicalBackupValidator.validate(read.backup, restoredAt)
        val target = ReadOnlyTargetInspector.inspect(databasePath)
        if (target.generation != candidate.targetGeneration) {
            fail(BackupError.TargetInvalid, "target generation changed since preflight")
        }
        checkDisk(read.backup.manifest, copied, target)
        val newEpoch = when (val old = target.authEpoch) {
            null -> 1L
            else -> old + 1L
        }
        if (newEpoch !in 1..Long.MAX_VALUE - 1) {
            fail(BackupError.TargetInvalid, "restore would write an unusable authEpoch")
        }
        val restoredGeneration = UUID.randomUUID().toString()
        if (restoredGeneration == read.backup.manifest.sourceGeneration) {
            fail(BackupError.Internal, "new generation collided with source generation")
        }
        val owner = selectOwner(candidate.manifest, read.backup.content)
        val receipt = RestoreReceipt(
            formatVersion = 1,
            sourceZipSha256 = read.zipSha256,
            sourceZipBytes = copied,
            sourceFormatVersion = read.backup.manifest.formatVersion,
            sourceSchemaVersion = read.backup.manifest.databaseSchemaVersion,
            sourceGeneration = read.backup.manifest.sourceGeneration,
            restoredGeneration = restoredGeneration,
            restoredAt = restoredAt,
            contentDigest = read.backup.manifest.contentDigest,
            importedRows = read.backup.manifest.totalRows,
            businessRevisionAtRestore = read.backup.manifest.businessRevision,
        )
        val receiptText = RestoreReceiptCodec.encode(receipt)
        val transformedRuns = read.backup.content.runs.map { run ->
            if (run.state != "RUNNING") {
                run
            } else {
                run.copy(
                    state = "INTERRUPTED",
                    activeSlot = null,
                    finishedAt = maxOf(restoredAt, run.startedAt),
                )
            }
        }
        val transformedProposals = read.backup.content.proposals.map { proposal ->
            if (proposal.state == "EDITABLE" || proposal.state == "LOCKED") {
                proposal.copy(state = "NEEDS_REVIEW", activeSlot = null)
            } else {
                proposal
            }
        }
        progress(RestoreProgress.IMPORTING)
        store.import(
            read.backup,
            transformedRuns,
            transformedProposals,
            restoredGeneration,
            owner,
            newEpoch,
            receiptText,
            candidate.targetGeneration,
            target.authEpoch,
            checkpoint,
        )
        checkpoint(RestoreCheckpoint.AFTER_COMMIT)
        progress(RestoreProgress.COMMITTED)
        val result = RestoreResult(
            restoredGeneration = restoredGeneration,
            processOwnerId = owner,
            authEpoch = newEpoch,
            receipt = receipt,
            importedRows = receipt.importedRows,
            transformStats = RestoreTransformStats.Known(
                transformedRuns = read.backup.content.runs.count { it.state == "RUNNING" },
                transformedProposals = read.backup.content.proposals.count {
                    it.state == "EDITABLE" || it.state == "LOCKED"
                },
            ),
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

    private fun selectOwner(manifest: BackupManifest, content: LogicalBackupContent): String {
        val requested = processOwnerId
        if (requested != null && requested != manifest.sourceGeneration &&
            content.runs.none { it.processOwnerId == requested }
        ) {
            return requested
        }
        while (true) {
            val generated = UUID.randomUUID().toString()
            if (generated != manifest.sourceGeneration && content.runs.none { it.processOwnerId == generated }) {
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

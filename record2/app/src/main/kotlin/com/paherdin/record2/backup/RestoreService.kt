package com.paherdin.record2.backup

import androidx.room.withTransaction
import com.paherdin.record2.storage.RecordDatabase
import java.io.File
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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
    val transformedRuns: Int,
    val transformedProposals: Int,
)

internal enum class RestoreCheckpoint {
    AFTER_DELETE,
    BEFORE_RECEIPT,
    BEFORE_COMMIT,
}

internal class RestoreService(
    private val database: RecordDatabase,
    private val databasePath: File,
    private val stagingDirectory: File,
    private val gate: DataOperationGate,
    private val clock: Clock = Clock.systemUTC(),
    private val availableBytes: (File) -> Long = { FileSpace.availableBytes(it) },
    private val checkpoint: (RestoreCheckpoint) -> Unit = {},
    private val memoryBarrier: RestoreMemoryBarrier = ProcessRestoreMemoryBarrier,
    private val processOwnerId: String? = null,
    private val currentAppVersionCode: Long = 1,
) {
    suspend fun preflight(source: BackupSource): RestoreCandidate = withContext(Dispatchers.IO) {
        if (gate.isRestoreInProgress) throw DataOperationBusyException("Restore is already in progress")
        stagingDirectory.mkdirs()
        val work = File(stagingDirectory, "preflight-${UUID.randomUUID()}")
        val zip = File(work, "source.zip")
        try {
            if (!work.mkdirs()) fail(BackupError.RecoverableIO, "could not create preflight directory")
            val bytes = source.copyTo(zip, BackupLimits.MAX_ZIP_BYTES)
            if (bytes > BackupLimits.MAX_ZIP_BYTES) fail(BackupError.InvalidSource, "backup exceeds 64 MiB")
            val read = LogicalZipReader.read(zip)
            val now = clock.millis()
            LogicalBackupValidator.validate(read.backup, now)
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

    suspend fun restore(candidate: RestoreCandidate): RestoreResult = withContext(Dispatchers.IO) {
        val lease = gate.beginRestore()
        stagingDirectory.mkdirs()
        val work = File(stagingDirectory, "restore-${UUID.randomUUID()}")
        val zip = File(work, "source.zip")
        try {
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
            importTransaction(
                read.backup,
                transformedRuns,
                transformedProposals,
                restoredGeneration,
                owner,
                newEpoch,
                receiptText,
                candidate.targetGeneration,
                target.authEpoch,
            )
            RestoreResult(
                restoredGeneration = restoredGeneration,
                processOwnerId = owner,
                authEpoch = newEpoch,
                receipt = receipt,
                importedRows = receipt.importedRows,
                transformedRuns = read.backup.content.runs.count { it.state == "RUNNING" },
                transformedProposals = read.backup.content.proposals.count {
                    it.state == "EDITABLE" || it.state == "LOCKED"
                },
            )
        } finally {
            work.deleteRecursively()
            lease.close()
        }
    }

    private suspend fun importTransaction(
        backup: LogicalBackup,
        runs: List<RunRow>,
        proposals: List<ProposalRow>,
        restoredGeneration: String,
        owner: String,
        newEpoch: Long,
        receipt: String,
        expectedGeneration: String?,
        expectedEpoch: Long?,
    ) {
        database.withTransaction {
            val current = database.records().dataState()
            if (current?.dataGeneration != expectedGeneration) {
                fail(BackupError.TargetInvalid, "target generation changed inside the restore gate")
            }
            val currentControl = database.aiControl().control()
            if (currentControl?.authEpoch != expectedEpoch) {
                fail(BackupError.TargetInvalid, "target authEpoch changed inside the restore gate")
            }
            val dao = database.backup()
            dao.deleteReferences()
            dao.deleteMessages()
            dao.deleteProposals()
            dao.deleteOperations()
            dao.deleteRuns()
            dao.deleteSessions()
            dao.deleteLinks()
            dao.deleteDrafts()
            dao.deleteNotes()
            dao.deleteTransactions()
            dao.deleteCategories()
            dao.deleteAccounts()
            dao.deleteControl()
            dao.deleteDataState()
            checkpoint(RestoreCheckpoint.AFTER_DELETE)

            backup.content.accounts.forEach { dao.insertAccount(it.toEntity()) }
            backup.content.categories.forEach { dao.insertCategory(it.toEntity()) }
            backup.content.transactions.forEach { dao.insertTransaction(it.toEntity()) }
            backup.content.notes.forEach { dao.insertNote(it.toEntity()) }
            backup.content.links.forEach { dao.insertLink(it.toEntity()) }
            backup.content.drafts.forEach { dao.insertDraft(it.toEntity()) }
            backup.content.sessions.forEach { dao.insertSession(it.toEntity()) }
            runs.forEach { dao.insertRun(it.toEntity()) }
            proposals.forEach { dao.insertProposal(it.toEntity()) }
            backup.content.operations.forEach { dao.insertOperation(it.toEntity()) }
            backup.content.messages.forEach { dao.insertMessage(it.toEntity()) }
            backup.content.references.forEach { dao.insertReference(it.toEntity()) }
            dao.insertControl(
                com.paherdin.record2.storage.AiControlEntity(
                    1, "OFF", null, null, null, null, null, newEpoch,
                ),
            )
            checkpoint(RestoreCheckpoint.BEFORE_RECEIPT)
            dao.insertDataState(
                com.paherdin.record2.storage.RecordDataStateEntity(
                    1, restoredGeneration, backup.manifest.businessRevision, owner, receipt,
                ),
            )
            assertImported(dao, backup, restoredGeneration, newEpoch, receipt)
            checkpoint(RestoreCheckpoint.BEFORE_COMMIT)
        }
    }

    private fun assertImported(
        dao: com.paherdin.record2.storage.BackupDao,
        backup: LogicalBackup,
        restoredGeneration: String,
        newEpoch: Long,
        receipt: String,
    ) {
        val content = backup.content
        val counts = listOf(
            dao.accountCount() to content.accounts.size.toLong(),
            dao.categoryCount() to content.categories.size.toLong(),
            dao.transactionCount() to content.transactions.size.toLong(),
            dao.noteCount() to content.notes.size.toLong(),
            dao.linkCount() to content.links.size.toLong(),
            dao.draftCount() to content.drafts.size.toLong(),
            dao.proposalCount() to content.proposals.size.toLong(),
            dao.operationCount() to content.operations.size.toLong(),
            dao.sessionCount() to content.sessions.size.toLong(),
            dao.runCount() to content.runs.size.toLong(),
            dao.messageCount() to content.messages.size.toLong(),
            dao.referenceCount() to content.references.size.toLong(),
        )
        if (counts.any { it.first != it.second }) {
            fail(BackupError.Internal, "restored segment counts do not match the manifest")
        }
        if (dao.activeRunCount() != 0L || dao.activeProposalCount() != 0L) {
            fail(BackupError.Internal, "restore left an active run or proposal slot")
        }
        if (dao.offControlCount() != 1L) {
            fail(BackupError.Internal, "restore did not leave AI OFF without a grant")
        }
        val state = database.records().dataState()
        if (state == null || state.dataGeneration != restoredGeneration ||
            state.restoreReceipt != receipt || state.businessRevision != backup.manifest.businessRevision
        ) {
            fail(BackupError.Internal, "restore receipt/data state is not self-consistent")
        }
        val parsed = RestoreReceiptCodec.decode(receipt, clock.millis())
        if (parsed.restoredGeneration != restoredGeneration) {
            fail(BackupError.Internal, "restore receipt generation mismatch")
        }
        val control = database.aiControl().control()
        if (control == null || control.authEpoch != newEpoch || control.mode != "OFF" ||
            control.grantReceiverIdentity != null || control.selectedReceiverIdentity != null
        ) {
            fail(BackupError.Internal, "restore control row is not the frozen OFF state")
        }
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

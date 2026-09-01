package com.paherdin.record.core.data.restore

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.paherdin.record.core.data.AttachmentValidator
import com.paherdin.record.core.data.Blocked
import com.paherdin.record.core.data.GateResult
import com.paherdin.record.core.data.MigrationCoordinator
import com.paherdin.record.core.data.MigrationRecoveryRecordStore
import com.paherdin.record.core.data.MigrationRecoveryState
import com.paherdin.record.core.data.StartupObserver
import com.paherdin.record.core.data.StartupProgress
import kotlinx.coroutines.runBlocking
import java.io.File

internal sealed class BootstrapResult {
    /** Truly fresh install: no record, no legacy data. Caller creates the initial generation. */
    internal data object Fresh : BootstrapResult()

    /** A complete v2 record is authoritative (bootstrap just published it, or v2 already existed). */
    internal data class Ready(val record: ActivationRecord) : BootstrapResult()

    internal data class Blocked(val reason: String) : BootstrapResult()
}

/**
 * The ADR-0010 legacy-layout bootstrap (spec 4.5, plan Task 3): classifies the
 * installed state without Room, resolves pending v1 migration recovery on the
 * legacy root, then copies the clean legacy database and complete root
 * attachment tree into one unreferenced generation, validates the copy, and
 * atomically publishes the healthy v2 pointer. The legacy source is never
 * mutated or deleted; a crash before the v2 write leaves it authoritative, a
 * crash after leaves a complete pointer-owned generation.
 */
internal class LegacyGenerationBootstrap(
    private val context: Context,
    private val activationStore: ActivationRecordStore,
    private val legacyStore: MigrationRecoveryRecordStore,
    private val generationManager: GenerationManager,
    private val coordinator: MigrationCoordinator,
    private val hook: BootstrapFaultHook = BootstrapFaultHook.NO_OP,
) {
    private val legacyDatabaseFile: File get() = context.getDatabasePath("record.db")
    private val legacyAttachmentsDir: File get() = File(context.filesDir, "attachments")

    fun classify(observer: StartupObserver = StartupObserver {}): BootstrapResult {
        when (val version = activationStore.recordVersion()) {
            ActivationRecord.RECORD_VERSION -> return BootstrapResult.Ready(checkNotNull(activationStore.read()))
            com.paherdin.record.core.data.MigrationRecord.RECORD_VERSION -> {
                val legacy = checkNotNull(legacyStore.read())
                // ADR 0016: a durable pending migration proves recovery work;
                // RecoveryDetected precedes the resolver dispatch below and
                // Recovering covers the resolver's owned progress.
                if (legacy.state == MigrationRecoveryState.MIGRATION_RECOVERY_PENDING) {
                    observer.onProgress(StartupProgress.RecoveryDetected)
                    observer.onProgress(StartupProgress.Recovering)
                }
                return bootstrapLegacy()
            }
            null -> Unit
            else -> throw IllegalArgumentException("unsupported recordVersion $version")
        }
        val legacyDb = legacyDatabaseFile
        val hasLegacy = legacyDb.exists() ||
            (legacyAttachmentsDir.isDirectory && (legacyAttachmentsDir.list()?.isNotEmpty() == true))
        return if (hasLegacy) bootstrapLegacy() else BootstrapResult.Fresh
    }

    private fun bootstrapLegacy(): BootstrapResult {
        hook.beforeLegacyRecovery()
        val gate = coordinator.startupGate()
        hook.afterLegacyRecovery()
        if (gate is Blocked) return BootstrapResult.Blocked("legacy recovery blocked: ${gate.reason}")
        completeLegacyMigration(gate)

        hook.beforeLegacyCheckpoint()
        checkpointAndClose(legacyDatabaseFile)
        hook.afterLegacyCheckpoint()

        val baselineDb = sha256(legacyDatabaseFile)
        val baselineAttachments = hashTree(legacyAttachmentsDir)

        val genId = generationManager.newGenerationId()
        val genDir = generationManager.createGeneration(genId)
        val genDb = File(genDir, "record.db")
        hook.beforeDatabaseCopy()
        copyFile(legacyDatabaseFile, genDb)
        hook.afterDatabaseCopy()
        hook.beforeAttachmentCopy()
        copyTree(legacyAttachmentsDir, generationManager.attachmentsDir(genId))
        hook.afterAttachmentCopy()

        hook.beforeCopyValidation()
        val db = openGenerationDatabase(context, genDb)
        val validation = try {
            runBlocking { StagedGenerationValidator(db, File(generationManager.attachmentsDir(genId), "content")).validate() }
        } finally {
            db.close()
        }
        hook.afterCopyValidation()
        if (validation !is GenerationValidation.Valid) {
            val detail = (validation as? GenerationValidation.Failed)?.detail ?: "unknown"
            return BootstrapResult.Blocked("copied generation validation failed: $detail")
        }

        if (sha256(legacyDatabaseFile) != baselineDb) return BootstrapResult.Blocked("legacy source changed during bootstrap")
        if (hashTree(legacyAttachmentsDir) != baselineAttachments) return BootstrapResult.Blocked("legacy attachments changed during bootstrap")
        generationManager.markComplete(genId)

        val record = ActivationRecord(
            migrationRecoveryState = MigrationRecoveryState.MIGRATION_HEALTHY,
            migration = null,
            generationActivation = GenerationActivation(genId, null, null, null, GenerationState.HEALTHY),
        )
        hook.beforeRecordWrite()
        activationStore.write(record)
        hook.afterRecordWrite()
        return BootstrapResult.Ready(record)
    }

    /** Completes the approved v1-to-v2 Room migration on the legacy path. */
    private fun completeLegacyMigration(gate: GateResult) {
        if (gate !is com.paherdin.record.core.data.ProceedAfterSnapshot) return
        val db = androidx.room.Room.databaseBuilder(
            context,
            com.paherdin.record.core.data.room.RecordDatabase::class.java,
            "record.db",
        )
            .addMigrations(com.paherdin.record.core.data.room.RecordDatabase.MIGRATION_1_2)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        try {
            db.openHelper.writableDatabase
        } finally {
            db.close()
        }
        legacyStore.write(
            com.paherdin.record.core.data.MigrationRecord(
                com.paherdin.record.core.data.MigrationRecord.RECORD_VERSION,
                MigrationRecoveryState.MIGRATION_HEALTHY,
                null,
            ),
        )
    }

    private fun checkpointAndClose(dbFile: File) {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
    }

    private fun sha256(file: File): String =
        file.inputStream().use { AttachmentValidator.sha256(it) }

    private fun hashTree(dir: File): Map<String, String> {
        if (!dir.isDirectory) return emptyMap()
        val result = LinkedHashMap<String, String>()
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).path }.forEach { f ->
            result[f.relativeTo(dir).path.replace('\\', '/')] = sha256(f)
        }
        return result
    }

    private fun copyFile(from: File, to: File) {
        to.parentFile?.mkdirs()
        from.inputStream().use { input -> to.outputStream().use { output -> input.copyTo(output) } }
    }

    private fun copyTree(from: File, to: File) {
        if (!from.isDirectory) return
        to.mkdirs()
        from.walkTopDown().forEach { source ->
            val rel = source.relativeTo(from).path
            val target = File(to, rel)
            if (source.isDirectory) target.mkdirs() else copyFile(source, target)
        }
    }
}

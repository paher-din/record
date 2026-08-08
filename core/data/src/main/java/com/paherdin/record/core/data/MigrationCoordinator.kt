package com.paherdin.record.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** A startup-gate failure with a stable public classification. */
internal class MigrationGateException(val reason: String) : IllegalStateException(reason)

/** Storage exhaustion during snapshot/restore work. */
internal class MigrationStorageExhaustedException : IllegalStateException()

internal sealed interface GateResult

internal data object ProceedFresh : GateResult

internal data object ProceedAfterSnapshot : GateResult

internal data object ProceedNormal : GateResult

internal data class Blocked(val reason: String) : GateResult



/**
 * The recoverable-migration-snapshot coordinator (ADR 0007, data and storage,
 * plan Task 3). Runs the startup gate before any Room/SQLite initialization:
 * resolves the recovery record, resumes pending recovery idempotently, and
 * for a v1 live database creates and publishes a verified snapshot before the
 * migration opens the new schema. Raw SQLite access is old-schema-compatible
 * and every handle is closed before any file move; a pending record is
 * persisted atomically before any move and is never inferred from file
 * presence. All file operations are on the app-private filesystem.
 */
internal class MigrationCoordinator(
    private val context: Context,
    private val recordStore: MigrationRecoveryRecordStore,
    private val hook: MigrationFaultHook = MigrationFaultHook.NO_OP,
    private val snapshotDir: File = File(context.filesDir, "migration"),
    private val recoveryDir: File = File(context.filesDir, "recovery"),
) {
    private val liveDatabaseFile: File get() = context.getDatabasePath(DEFAULT_DB_NAME)
    private val liveWalFile: File get() = File(liveDatabaseFile.path + "-wal")
    private val liveShmFile: File get() = File(liveDatabaseFile.path + "-shm")

    fun startupGate(): GateResult {
        val record = recordStore.read()
        if (record != null) {
            when (record.state) {
                MigrationRecoveryState.MIGRATION_RECOVERY_PENDING -> {
                    // The recovery result IS the gate result: PREPARED with a
                    // verified snapshot proceeds to migration; restored stages
                    // also proceed (the restored live database is the v1
                    // snapshot and must migrate to v2); only Blocked keeps Room
                    // closed.
                    return resumeRecovery(record.migration!!)
                }
                MigrationRecoveryState.MIGRATION_RECOVERED -> {
                    recordStore.write(MigrationRecord(MigrationRecord.RECORD_VERSION, MigrationRecoveryState.MIGRATION_HEALTHY, null))
                }
                MigrationRecoveryState.MIGRATION_HEALTHY -> Unit
            }
        } else {
            // First P3 startup only: initialize the healthy record atomically.
            recordStore.write(MigrationRecord(MigrationRecord.RECORD_VERSION, MigrationRecoveryState.MIGRATION_HEALTHY, null))
        }

        if (!liveDatabaseFile.exists()) return ProceedFresh
        return when (readUserVersion()) {
            1 -> {
                snapshotForMigration()
                ProceedAfterSnapshot
            }
            2 -> ProceedNormal
            else -> throw MigrationGateException("unsupported user_version")
        }
    }

    // ---- snapshot protocol (v1 live database) ----

    private fun snapshotForMigration() {
        snapshotDir.mkdirs()
        val snapshotId = java.util.UUID.randomUUID().toString()
        val staging = File(snapshotDir, "staging-$snapshotId.db")

        hook.beforeSnapshotCopy()
        // 1. Old-schema-compatible checkpoint, then close every handle.
        checkpointAndClose(liveDatabaseFile)
        // 2. Copy the clean main database to an app-private staging file on the
        // same filesystem as the published path. Never copy a live database or
        // its WAL files.
        copyFile(liveDatabaseFile, staging)
        hook.afterSnapshotCopy()
        // 3. Verify the staged copy before any publish.
        val sha256 = verifySnapshot(staging)

        val published = File(snapshotDir, "snapshot-$snapshotId.db")
        val restoreTemp = File(recoveryDir, "restore-$snapshotId.db")
        val quarantine = File(recoveryDir, "quarantine-$snapshotId/")
        val pending = MigrationPayload(
            snapshotId = snapshotId,
            snapshotSha256 = sha256,
            liveDatabasePath = liveDatabaseFile.toRelativeString(context.filesDir.parentFile!!).replace('\\', '/'),
            workDatabasePath = liveDatabaseFile.toRelativeString(context.filesDir.parentFile!!).replace('\\', '/'),
            restoreTempPath = restoreTemp.toRelativeString(context.filesDir.parentFile!!).replace('\\', '/'),
            quarantinePath = quarantine.toRelativeString(context.filesDir.parentFile!!).replace('\\', '/'),
            stage = MigrationStage.PREPARED,
        )
        // 4. Atomically persist the pending record BEFORE any file move.
        recordStore.write(MigrationRecord(MigrationRecord.RECORD_VERSION, MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, pending))
        hook.afterPendingPersistBeforeMove()
        // 5. Atomically publish the verified snapshot (same-filesystem rename).
        hook.beforePublish()
        if (!staging.renameTo(published)) throw MigrationGateException("snapshot publish failed")
        hook.afterPublish()
    }

    // ---- pending recovery state machine (idempotent, restart-safe) ----

    private fun resumeRecovery(payload: MigrationPayload): GateResult {
        val snapshot = File(snapshotDir, "snapshot-${payload.snapshotId}.db")
        val restoreTemp = File(context.filesDir.parentFile!!, payload.restoreTempPath)
        val quarantine = File(context.filesDir.parentFile!!, payload.quarantinePath)

        return when (payload.stage) {
            MigrationStage.PREPARED -> {
                if (!snapshot.exists()) {
                    // The snapshot was never published (crash before publish):
                    // redo the snapshot protocol over the untouched v1 live
                    // database; the pending record is overwritten atomically.
                    snapshotForMigration()
                    return ProceedAfterSnapshot
                }
                if (!verifySnapshotFile(snapshot, payload.snapshotSha256)) {
                    // The published snapshot failed reverification: isolate the
                    // failed live set, then the snapshot (the only restore
                    // input) is corrupt — keep Room closed and retain the
                    // pending record for explicit recovery.
                    advance(payload, MigrationStage.FAILED_SET_ISOLATED)
                    isolateSidecars(payload)
                    return Blocked("published snapshot failed reverification")
                }
                // The live database is untouched: migration may proceed on the
                // verified snapshot. Recovery stages below apply only when the
                // live set was already isolated or replaced.
                ProceedAfterSnapshot
            }
            MigrationStage.FAILED_SET_ISOLATED -> {
                isolateSidecars(payload)
                advance(payload, MigrationStage.RESTORE_COPY_VALIDATED)
                resumeRecovery(payload.copy(stage = MigrationStage.RESTORE_COPY_VALIDATED))
            }
            MigrationStage.RESTORE_COPY_VALIDATED -> {
                hook.beforeRestoreCopyValidation()
                if (!restoreTemp.exists() || !verifySnapshotFile(restoreTemp, payload.snapshotSha256)) {
                    // Recreate the validated restore copy from the unchanged published snapshot.
                    copyFile(snapshot, restoreTemp)
                    if (!verifySnapshotFile(restoreTemp, payload.snapshotSha256)) {
                        return Blocked("restore copy validation failed")
                    }
                }
                advance(payload, MigrationStage.MAIN_REPLACED)
                resumeRecovery(payload.copy(stage = MigrationStage.MAIN_REPLACED))
            }
            MigrationStage.MAIN_REPLACED -> {
                hook.beforeSnapshotReplace()
                val live = liveDatabaseFile
                if (!verifySnapshotFile(live, payload.snapshotSha256)) {
                    // Replace the live database atomically with the validated restore copy.
                    isolateSidecars(payload)
                    if (!restoreTemp.renameTo(live)) {
                        if (!live.exists()) {
                            copyFile(snapshot, live)
                        }
                    }
                    if (!verifySnapshotFile(live, payload.snapshotSha256)) {
                        return Blocked("installed file validation failed")
                    }
                }
                hook.afterSnapshotReplace()
                recordStore.write(MigrationRecord(MigrationRecord.RECORD_VERSION, MigrationRecoveryState.MIGRATION_RECOVERED, payload))
                hook.afterMigrationRecovered()
                // The restored live database is the v1 snapshot: the migration
                // must still run to open v2. The open() normalization writes
                // HEALTHY after the migration completes.
                ProceedAfterSnapshot
            }
        }
    }

    private fun advance(payload: MigrationPayload, stage: MigrationStage) {
        recordStore.write(
            MigrationRecord(
                MigrationRecord.RECORD_VERSION,
                MigrationRecoveryState.MIGRATION_RECOVERY_PENDING,
                payload.copy(stage = stage),
            ),
        )
    }

    private fun isolateSidecars(payload: MigrationPayload) {
        val quarantine = File(context.filesDir.parentFile!!, payload.quarantinePath)
        quarantine.mkdirs()
        for (f in listOf(liveDatabaseFile, liveWalFile, liveShmFile)) {
            hook.beforeSidecarIsolationEntry(f.name)
            if (f.exists()) {
                val target = File(quarantine, f.name)
                if (target.exists()) target.delete()
                if (!f.renameTo(target) && f.exists()) {
                    throw MigrationGateException("sidecar isolation failed for ${f.name}")
                }
            }
            hook.afterSidecarIsolationEntry(f.name)
        }
    }

    // ---- old-schema-compatible SQLite helpers ----

    private fun readUserVersion(): Int = openRaw(liveDatabaseFile, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("PRAGMA user_version", null).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
    }

    private fun checkpointAndClose(dbFile: File) {
        // `wal_checkpoint` returns a result row, so execSQL rejects it on
        // modern Android; run it as a query and ignore the returned state.
        openRaw(dbFile, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
    }

    private fun openRaw(dbFile: File, flags: Int): SQLiteDatabase =
        SQLiteDatabase.openDatabase(dbFile.path, null, flags)

    // ---- snapshot verification ----

    /** Verifies SHA-256, SQLite integrity, and the old-schema domain invariants. */
    private fun verifySnapshot(snapshotFile: File): String {
        if (!snapshotFile.exists()) throw MigrationGateException("snapshot missing")
        val sha256 = snapshotFile.inputStream().use { AttachmentValidator.sha256(it) }
        openRaw(snapshotFile, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                c.moveToFirst()
                if (c.getString(0) != "ok") throw MigrationGateException("snapshot integrity check failed")
            }
            // Old-schema domain invariants: the six v1 tables exist.
            val expected = setOf(
                "EntityRef", "Transaction", "Account", "AccountEntry", "Note", "TransactionNoteRelation",
            )
            val found = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) found.add(c.getString(0))
            }
            if (!found.containsAll(expected)) throw MigrationGateException("snapshot missing v1 tables")
        }
        return sha256
    }

    private fun verifySnapshotFile(snapshotFile: File, expectedSha256: String): Boolean {
        return try {
            if (!snapshotFile.exists()) return false
            val actual = snapshotFile.inputStream().use { AttachmentValidator.sha256(it) }
            if (!actual.equals(expectedSha256, ignoreCase = true)) return false
            openRaw(snapshotFile, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { c ->
                    c.moveToFirst()
                    if (c.getString(0) != "ok") return false
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun copyFile(from: File, to: File) {
        to.parentFile?.mkdirs()
        from.inputStream().use { input ->
            to.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    companion object {
        private const val DEFAULT_DB_NAME = "record.db"
    }
}

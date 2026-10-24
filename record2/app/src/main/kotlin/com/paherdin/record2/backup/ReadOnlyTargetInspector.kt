package com.paherdin.record2.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File

internal data class TargetSnapshot(
    val initialized: Boolean,
    val schemaVersion: Int,
    val generation: String?,
    val authEpoch: Long?,
    val databaseBytes: Long,
    val walBytes: Long,
)

internal object ReadOnlyTargetInspector {
    fun inspect(databasePath: File): TargetSnapshot {
        val dbBytes = if (databasePath.isFile) databasePath.length() else 0L
        val walBytes = File(databasePath.absolutePath + "-wal").let { if (it.isFile) it.length() else 0L }
        if (!databasePath.isFile) {
            return TargetSnapshot(false, 0, null, null, dbBytes, walBytes)
        }
        var database: SQLiteDatabase? = null
        try {
            database = SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            val schemaVersion = database.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            val hasDataState = tableExists(database, "record_data_state")
            val hasControl = tableExists(database, "ai_control")
            if (!hasDataState) {
                if (schemaVersion != 0) {
                    fail(BackupError.TargetInvalid, "target schema is incomplete")
                }
                return TargetSnapshot(false, schemaVersion, null, null, dbBytes, walBytes)
            }
            if (schemaVersion != BackupLimits.DATABASE_SCHEMA_VERSION) {
                fail(BackupError.TargetInvalid, "target Room schema version is not 1")
            }
            val stateRows = database.rawQuery(
                "SELECT id, dataGeneration FROM record_data_state ORDER BY id ASC",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getInt(0) to cursor.getString(1))
                }
            }
            if (stateRows.isEmpty()) {
                return TargetSnapshot(true, schemaVersion, null, readEpoch(database, hasControl), dbBytes, walBytes)
            }
            if (stateRows.size != 1 || stateRows.single().first != 1) {
                fail(BackupError.TargetInvalid, "target record_data_state is malformed")
            }
            val generation = stateRows.single().second
                ?: fail(BackupError.TargetInvalid, "target dataGeneration is missing")
            if (!canonicalUuid(generation)) {
                fail(BackupError.TargetInvalid, "target dataGeneration is malformed")
            }
            return TargetSnapshot(
                initialized = true,
                schemaVersion = schemaVersion,
                generation = generation,
                authEpoch = readEpoch(database, hasControl),
                databaseBytes = dbBytes,
                walBytes = walBytes,
            )
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            throw BackupException(BackupError.TargetInvalid, "target database cannot be inspected", error)
        } finally {
            runCatching { database?.close() }
        }
    }

    private fun readEpoch(database: SQLiteDatabase, hasControl: Boolean): Long? {
        if (!hasControl) return null
        return database.rawQuery(
            "SELECT authEpoch FROM ai_control WHERE id = 1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val epoch = cursor.getLong(0)
            if (epoch < 0 || epoch >= Long.MAX_VALUE - 1) {
                fail(BackupError.TargetInvalid, "target authEpoch is outside the restorable range")
            }
            epoch
        }
    }

    private fun tableExists(database: SQLiteDatabase, table: String): Boolean =
        database.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
}

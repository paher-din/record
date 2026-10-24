package com.paherdin.record2.backup

import android.database.sqlite.SQLiteDatabase
import com.paherdin.record2.sessions.AgentHistoryService
import com.paherdin.record2.sessions.ProcessOwnershipReceipt
import java.io.File
import java.time.Clock

internal enum class StartupKind { NORMAL, RECOVERY_REQUIRED }

internal data class StartupStatus(
    val kind: StartupKind,
    val receipt: RestoreReceipt? = null,
    val dataGeneration: String? = null,
    val detail: String? = null,
)

internal data class StartupResult(
    val status: StartupStatus,
    val ownership: ProcessOwnershipReceipt? = null,
)

internal class RestoreStartupGate(
    private val databasePath: File,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun check(): StartupStatus {
        if (!databasePath.isFile) return StartupStatus(StartupKind.NORMAL)
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
            if (!tableExists(database, "record_data_state")) {
                return StartupStatus(StartupKind.NORMAL)
            }
            if (schemaVersion != BackupLimits.DATABASE_SCHEMA_VERSION) {
                return StartupStatus(StartupKind.RECOVERY_REQUIRED, detail = "Room schema version is not 1")
            }
            val rows = database.rawQuery(
                "SELECT id, dataGeneration, businessRevision, restoreReceipt FROM record_data_state ORDER BY id ASC",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            StateRow(
                                id = cursor.getInt(0),
                                generation = if (cursor.isNull(1)) null else cursor.getString(1),
                                businessRevision = cursor.getLong(2),
                                receipt = if (cursor.isNull(3)) null else cursor.getString(3),
                            ),
                        )
                    }
                }
            }
            if (rows.isEmpty()) return StartupStatus(StartupKind.NORMAL)
            val canonicalRows = rows.filter { it.id == 1 }
            if (canonicalRows.size != 1) {
                val anyReceipt = rows.any { it.receipt != null }
                return if (anyReceipt) {
                    StartupStatus(StartupKind.RECOVERY_REQUIRED, detail = "record_data_state id=1 is missing")
                } else {
                    StartupStatus(StartupKind.NORMAL)
                }
            }
            val row = canonicalRows.single()
            val receiptText = row.receipt ?: return StartupStatus(StartupKind.NORMAL)
            if (row.generation == null || !canonicalUuid(row.generation)) {
                return StartupStatus(StartupKind.RECOVERY_REQUIRED, detail = "restored dataGeneration is malformed")
            }
            if (row.businessRevision < 0) {
                return StartupStatus(StartupKind.RECOVERY_REQUIRED, detail = "businessRevision is negative")
            }
            val receipt = try {
                RestoreReceiptCodec.decode(receiptText, clock.millis())
            } catch (error: BackupException) {
                return StartupStatus(StartupKind.RECOVERY_REQUIRED, detail = error.message)
            }
            if (receipt.restoredGeneration != row.generation) {
                return StartupStatus(
                    StartupKind.RECOVERY_REQUIRED,
                    receipt,
                    row.generation,
                    "restore receipt generation does not match data state",
                )
            }
            return StartupStatus(StartupKind.NORMAL, receipt, row.generation)
        } catch (error: Exception) {
            return StartupStatus(StartupKind.RECOVERY_REQUIRED, detail = "database could not be read")
        } finally {
            runCatching { database?.close() }
        }
    }

    suspend fun beginStartup(history: AgentHistoryService, processOwnerId: String): StartupResult {
        val status = check()
        if (status.kind == StartupKind.RECOVERY_REQUIRED) return StartupResult(status)
        val ownership = history.beginProcessOwnership(processOwnerId)
        return StartupResult(status, ownership)
    }

    private data class StateRow(
        val id: Int,
        val generation: String?,
        val businessRevision: Long,
        val receipt: String?,
    )

    private fun tableExists(database: SQLiteDatabase, table: String): Boolean =
        database.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
}

package com.paherdin.record2.backup

import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import java.io.File
import java.nio.charset.StandardCharsets

internal data class SnapshotContent(
    val content: LogicalBackupContent,
    val dataGeneration: String,
    val businessRevision: Long,
)

internal interface ExportResourceMonitor {
    fun check()
}

internal class NoopExportResourceMonitor : ExportResourceMonitor {
    override fun check() = Unit
}

internal class FileSystemExportResourceMonitor(
    private val databasePath: File,
    private val stagingDirectory: File,
    private val freeSpace: (File) -> Long = { path -> StatFs(path.absolutePath).availableBytes },
) : ExportResourceMonitor {
    private val wal = File(databasePath.absolutePath + "-wal")
    private val baseline = if (wal.isFile) wal.length() else 0L

    override fun check() {
        val growth = (if (wal.isFile) wal.length() else 0L) - baseline
        if (growth > BackupLimits.MAX_EXPORT_WAL_GROWTH_BYTES) {
            fail(BackupError.StorageFull, "WAL grew beyond the export monitoring limit")
        }
        if (freeSpace(databasePath.parentFile ?: databasePath) < BackupLimits.EXPORT_FREE_RESERVE_BYTES) {
            fail(BackupError.StorageFull, "database volume free space fell below the export reserve")
        }
        if (freeSpace(stagingDirectory) < BackupLimits.EXPORT_FREE_RESERVE_BYTES) {
            fail(BackupError.StorageFull, "staging volume free space fell below the export reserve")
        }
    }
}

/**
 * Reads the whole snapshot with one SELECT statement on an OPEN_READONLY + query_only connection.
 * Android's SQLiteDatabase wrapper turns an explicit BEGIN into a writable transaction on a
 * read-only connection, so a single compound statement is the supported way to keep every table
 * on one SQLite read snapshot without acquiring a writer lock. The statement holds its implicit
 * read transaction until the cursor is exhausted; no other statement or connection participates.
 */
internal object ReadOnlyBackupSnapshot {
    fun read(
        databasePath: File,
        nowMillis: Long,
        monitor: ExportResourceMonitor = NoopExportResourceMonitor(),
        onSnapshotOpen: () -> Unit = {},
    ): SnapshotContent {
        if (!databasePath.isFile) fail(BackupError.Unsupported, "database file is missing")
        var database: SQLiteDatabase? = null
        try {
            database = SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            database.execSQL("PRAGMA query_only=ON")
            val journalMode = database.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            if (!journalMode.equals("wal", ignoreCase = true)) {
                fail(BackupError.Unsupported, "export requires WAL journal mode")
            }
            val userVersion = database.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else -1
            }
            if (userVersion != BackupLimits.DATABASE_SCHEMA_VERSION) {
                fail(BackupError.Unsupported, "database schema version is not 1")
            }
            monitor.check()
            val rows = LinkedHashMap<LogicalSegment, MutableList<SegmentRow>>()
            for (segment in LogicalSegment.entries) rows[segment] = ArrayList()
            var dataGeneration: String? = null
            var businessRevision: Long? = null
            var snapshotOpened = false
            database.rawQuery(snapshotQuery(), null).use { cursor ->
                while (cursor.moveToNext()) {
                    val label = cursor.getString(0)
                    val payload = cursor.getString(1)
                    if (payload == null) fail(BackupError.InvalidSource, "snapshot row is null")
                    if (payload.toByteArray(StandardCharsets.UTF_8).size > BackupLimits.MAX_LINE_BYTES) {
                        fail(BackupError.StorageFull, "snapshot row exceeds 8 MiB")
                    }
                    val value = StrictJson.decodeObject(
                        payload.toByteArray(StandardCharsets.UTF_8),
                        BackupLimits.MAX_LINE_BYTES,
                    )
                    if (label == "data_state") {
                        dataGeneration = value.string("dataGeneration", "record_data_state")
                        businessRevision = value.long("businessRevision", "record_data_state")
                    } else {
                        val segment = LogicalSegment.entries.firstOrNull { it.name == label }
                            ?: fail(BackupError.InvalidSource, "unknown snapshot segment $label")
                        rows.getValue(segment) += SegmentCodecs.decode(segment, value)
                    }
                    if (!snapshotOpened) {
                        snapshotOpened = true
                        onSnapshotOpen()
                    }
                    monitor.check()
                }
            }
            if (!snapshotOpened) fail(BackupError.InvalidSource, "snapshot is empty")
            val generation = dataGeneration ?: fail(BackupError.InvalidSource, "record_data_state is missing")
            val revision = businessRevision ?: fail(BackupError.InvalidSource, "record_data_state is missing")
            if (!canonicalUuid(generation) || revision < 0) {
                fail(BackupError.InvalidSource, "record_data_state is corrupt")
            }
            val content = sort(buildContent(rows))
            LogicalBackupValidator.validateContent(content, nowMillis)
            return SnapshotContent(content, generation, revision)
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            throw BackupException(BackupError.RecoverableIO, "could not take the read-only backup snapshot", error)
        } finally {
            runCatching { database?.close() }
        }
    }

    private fun snapshotQuery(): String {
        val parts = LogicalSegment.entries.map { segment ->
            val projection = segment.fields.joinToString(",") { field -> "'$field', $field" }
            "SELECT '${segment.name}' AS segment, json_object($projection) AS payload FROM ${table(segment)}"
        } + (
            "SELECT 'data_state' AS segment, " +
                "json_object('dataGeneration', dataGeneration, 'businessRevision', businessRevision) AS payload " +
                "FROM record_data_state WHERE id = 1"
            )
        return parts.joinToString(" UNION ALL ")
    }

    private fun table(segment: LogicalSegment): String = when (segment) {
        LogicalSegment.ACCOUNT -> "account"
        LogicalSegment.CATEGORY -> "category"
        LogicalSegment.TRANSACTION -> "record_transaction"
        LogicalSegment.NOTE -> "note"
        LogicalSegment.LINK -> "transaction_note_link"
        LogicalSegment.DRAFT -> "native_finance_draft"
        LogicalSegment.PROPOSAL -> "change_proposal"
        LogicalSegment.OPERATION -> "commit_operation"
        LogicalSegment.SESSION -> "agent_session"
        LogicalSegment.RUN -> "agent_run"
        LogicalSegment.MESSAGE -> "agent_message"
        LogicalSegment.REFERENCE -> "agent_message_reference"
    }

    private fun sort(content: LogicalBackupContent): LogicalBackupContent = content.copy(
        accounts = content.accounts.sortedBy { it.id },
        categories = content.categories.sortedBy { it.id },
        transactions = content.transactions.sortedWith(compareBy({ it.occurredAt }, { it.id })),
        notes = content.notes.sortedWith(compareBy({ it.occurredAt }, { it.id })),
        links = content.links.sortedWith(compareBy({ it.transactionId }, { it.noteId })),
        drafts = content.drafts.sortedBy { it.slot },
        proposals = content.proposals.sortedWith(compareBy({ it.createdAt }, { it.id })),
        operations = content.operations.sortedWith(compareBy({ it.createdAt }, { it.id })),
        sessions = content.sessions.sortedWith(compareBy({ it.createdAt }, { it.id })),
        runs = content.runs.sortedWith(compareBy({ it.startedAt }, { it.id })),
        messages = content.messages.sortedWith(compareBy({ it.sessionId }, { it.sequence })),
        references = content.references.sortedWith(compareBy({ it.messageId }, { it.ordinal })),
    )
}

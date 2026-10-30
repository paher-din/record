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

internal data class StreamedSnapshot(
    val dataGeneration: String,
    val businessRevision: Long,
    val sources: List<SegmentSource>,
    val totalRows: Long,
    val totalUncompressedBytes: Long,
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
    /** Test seam for the real monitor; production keeps the frozen 384 MiB limit. */
    private val maxWalGrowthBytes: Long = BackupLimits.MAX_EXPORT_WAL_GROWTH_BYTES,
) : ExportResourceMonitor {
    private val wal = File(databasePath.absolutePath + "-wal")
    private val baseline = if (wal.isFile) wal.length() else 0L

    override fun check() {
        val growth = (if (wal.isFile) wal.length() else 0L) - baseline
        if (growth > maxWalGrowthBytes) {
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
 * Legacy fixture inspection helper retained for existing tests. Production exports use
 * SystemExportSnapshot and StaticLogicalZip. A CursorWindow refill can restart this SELECT;
 * this helper must not be reused as a concurrent production snapshot implementation.
 */
internal object ReadOnlyBackupSnapshot {
    private const val CHUNK_CHARS = 262_144

    fun read(
        databasePath: File,
        nowMillis: Long,
        monitor: ExportResourceMonitor = NoopExportResourceMonitor(),
        onSnapshotOpen: () -> Unit = {},
    ): SnapshotContent {
        val directory = File(
            databasePath.parentFile ?: databasePath,
            "snapshot-read-${System.nanoTime()}",
        )
        try {
            val streamed = stream(databasePath, nowMillis, monitor, onSnapshotOpen, directory)
            val rows = LinkedHashMap<LogicalSegment, MutableList<SegmentRow>>()
            for (segment in LogicalSegment.entries) rows[segment] = ArrayList()
            for (source in streamed.sources) {
                source.file.inputStream().bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotEmpty()) {
                            val value = StrictJson.decodeObject(
                                line.toByteArray(StandardCharsets.UTF_8),
                                BackupLimits.MAX_LINE_BYTES,
                            )
                            rows.getValue(source.segment) += SegmentCodecs.decode(source.segment, value)
                        }
                    }
                }
            }
            val content = sort(buildContent(rows))
            LogicalBackupValidator.validateContent(content, nowMillis)
            return SnapshotContent(content, streamed.dataGeneration, streamed.businessRevision)
        } finally {
            directory.deleteRecursively()
        }
    }

    fun stream(
        databasePath: File,
        nowMillis: Long,
        monitor: ExportResourceMonitor = NoopExportResourceMonitor(),
        onSnapshotOpen: () -> Unit = {},
        outputDirectory: File,
    ): StreamedSnapshot {
        if (!databasePath.isFile) fail(BackupError.Unsupported, "database file is missing")
        if (!outputDirectory.mkdirs() && !outputDirectory.isDirectory) {
            fail(BackupError.RecoverableIO, "could not create snapshot staging directory")
        }
        val writers = LinkedHashMap<LogicalSegment, SegmentFileWriter>()
        var database: SQLiteDatabase? = null
        try {
            for (segment in LogicalSegment.entries) {
                writers[segment] = SegmentFileWriter(
                    segment,
                    File(outputDirectory, "${segment.ordinal}-${segment.path.substringAfterLast('/')}"),
                )
            }
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
            var dataGeneration: String? = null
            var businessRevision: Long? = null
            var snapshotOpened = false
            var totalRows = 0L

            fun consume(index: Int, payload: String) {
                if (payload.isEmpty()) fail(BackupError.InvalidSource, "snapshot row is empty")
                if (payload.toByteArray(StandardCharsets.UTF_8).size > BackupLimits.MAX_LINE_BYTES) {
                    fail(BackupError.StorageFull, "snapshot row exceeds 8 MiB")
                }
                val value = StrictJson.decodeObject(
                    payload.toByteArray(StandardCharsets.UTF_8),
                    BackupLimits.MAX_LINE_BYTES,
                )
                if (index == LogicalSegment.entries.size) {
                    if (dataGeneration != null) fail(BackupError.InvalidSource, "duplicate data_state row")
                    dataGeneration = value.string("dataGeneration", "record_data_state")
                    businessRevision = value.long("businessRevision", "record_data_state")
                } else {
                    val segment = LogicalSegment.entries.getOrNull(index)
                        ?: fail(BackupError.InvalidSource, "unknown snapshot segment $index")
                    writers.getValue(segment).append(SegmentCodecs.decode(segment, value))
                    totalRows++
                    if (totalRows > BackupLimits.MAX_ROWS_TOTAL) {
                        fail(BackupError.StorageFull, "export exceeds the 500,000 row limit")
                    }
                }
                if (!snapshotOpened) {
                    snapshotOpened = true
                    onSnapshotOpen()
                }
            }

            var currentSegment = -1
            var currentRowId = 0L
            var currentOffset = 0
            val payload = StringBuilder()
            fun flush() {
                if (currentSegment < 0) return
                if (currentOffset != payload.codePointCount(0, payload.length)) {
                    fail(BackupError.Internal, "snapshot chunk sequence is incomplete")
                }
                consume(currentSegment, payload.toString())
                payload.setLength(0)
                currentSegment = -1
                currentRowId = 0L
                currentOffset = 0
            }

            database.rawQuery(chunkedSnapshotQuery(), null).use { cursor ->
                while (cursor.moveToNext()) {
                    val segment = cursor.getInt(0)
                    val rowId = cursor.getLong(3)
                    val offset = cursor.getInt(4)
                    val chunk = cursor.getString(5)
                        ?: fail(BackupError.InvalidSource, "snapshot chunk is null")
                    if (segment != currentSegment || rowId != currentRowId) {
                        flush()
                        currentSegment = segment
                        currentRowId = rowId
                        currentOffset = 0
                    }
                    if (offset != currentOffset) fail(BackupError.Internal, "snapshot chunk gap")
                    payload.append(chunk)
                    currentOffset += chunk.codePointCount(0, chunk.length)
                    if (payload.length > BackupLimits.MAX_LINE_BYTES) {
                        fail(BackupError.StorageFull, "snapshot row exceeds 8 MiB")
                    }
                    monitor.check()
                }
            }
            flush()
            if (!snapshotOpened) fail(BackupError.InvalidSource, "snapshot is empty")
            val generation = dataGeneration ?: fail(BackupError.InvalidSource, "record_data_state is missing")
            val revision = businessRevision ?: fail(BackupError.InvalidSource, "record_data_state is missing")
            if (!canonicalUuid(generation) || revision < 0) {
                fail(BackupError.InvalidSource, "record_data_state is corrupt")
            }
            val sources = ArrayList<SegmentSource>(LogicalSegment.entries.size)
            var totalBytes = 0L
            for (segment in LogicalSegment.entries) {
                val source = writers.getValue(segment).finish()
                sources += source
                totalBytes += source.uncompressedBytes
            }
            if (totalBytes > BackupLimits.MAX_TOTAL_UNCOMPRESSED) {
                fail(BackupError.StorageFull, "export exceeds the 256 MiB uncompressed limit")
            }
            return StreamedSnapshot(generation, revision, sources, totalRows, totalBytes)
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            throw BackupException(BackupError.RecoverableIO, "could not take the read-only backup snapshot", error)
        } finally {
            writers.values.forEach { runCatching { it.close() } }
            runCatching { database?.close() }
        }
    }

    private fun chunkedSnapshotQuery(): String {
        val ctes = StringBuilder("WITH RECURSIVE ")
        val selects = ArrayList<String>(LogicalSegment.entries.size + 1)
        LogicalSegment.entries.forEachIndexed { index, segment ->
            val order = orderKeys(segment)
            val filter = if (segment == LogicalSegment.DRAFT) " WHERE state = 'EDITING'" else ""
            val source = "s$index"
            val chunks = "c$index"
            ctes.append("$source AS MATERIALIZED (SELECT rowid AS rid, ${order.first} AS k1, " +
                "${order.second} AS k2, ${payloadJson(segment)} AS p FROM ${table(segment)}$filter), ")
            ctes.append("$chunks(rid,k1,k2,off) AS (SELECT rid,k1,k2,0 FROM $source " +
                "UNION ALL SELECT rid,k1,k2,off + $CHUNK_CHARS FROM $chunks " +
                "WHERE off + $CHUNK_CHARS < (SELECT length(p) FROM $source WHERE rid = $chunks.rid)), ")
            selects += "SELECT $index AS segmentIndex, k1, k2, rid, off, " +
                "substr((SELECT p FROM $source WHERE rid = $chunks.rid), off + 1, $CHUNK_CHARS) AS chunk " +
                "FROM $chunks"
        }
        val index = LogicalSegment.entries.size
        val source = "s$index"
        val chunks = "c$index"
        ctes.append("$source AS MATERIALIZED (SELECT rowid AS rid, 0 AS k1, '' AS k2, " +
            "${dataStateJson()} AS p FROM record_data_state WHERE id = 1), ")
        ctes.append("$chunks(rid,k1,k2,off) AS (SELECT rid,k1,k2,0 FROM $source " +
            "UNION ALL SELECT rid,k1,k2,off + $CHUNK_CHARS FROM $chunks " +
            "WHERE off + $CHUNK_CHARS < (SELECT length(p) FROM $source WHERE rid = $chunks.rid)), ")
        selects += "SELECT $index AS segmentIndex, k1, k2, rid, off, " +
            "substr((SELECT p FROM $source WHERE rid = $chunks.rid), off + 1, $CHUNK_CHARS) AS chunk " +
            "FROM $chunks"
        return ctes.substring(0, ctes.length - 2) + " " +
            selects.joinToString(" UNION ALL ") + " ORDER BY segmentIndex, k1, k2, rid, off"
    }

    private fun payloadJson(segment: LogicalSegment): String {
        val projection = segment.fields.joinToString(",") { field -> "'$field', $field" }
        return "json_object($projection)"
    }

    private fun dataStateJson(): String =
        "json_object('dataGeneration', dataGeneration, 'businessRevision', businessRevision)"

    private fun orderKeys(segment: LogicalSegment): Pair<String, String> = when (segment) {
        LogicalSegment.ACCOUNT, LogicalSegment.CATEGORY -> "id" to "''"
        LogicalSegment.TRANSACTION, LogicalSegment.NOTE -> "occurredAt" to "id"
        LogicalSegment.LINK -> "transactionId" to "noteId"
        LogicalSegment.DRAFT -> "slot" to "''"
        LogicalSegment.PROPOSAL, LogicalSegment.OPERATION, LogicalSegment.SESSION -> "createdAt" to "id"
        LogicalSegment.RUN -> "startedAt" to "id"
        LogicalSegment.MESSAGE -> "sessionId" to "sequence"
        LogicalSegment.REFERENCE -> "messageId" to "ordinal"
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

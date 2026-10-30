package com.paherdin.record2.backup

import android.database.sqlite.SQLiteDatabase
import java.io.*
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object ExportTables {
    fun name(s: LogicalSegment) = when (s) {
        LogicalSegment.DRAFT -> "native_finance_draft"
        LogicalSegment.PROPOSAL -> "change_proposal"
        LogicalSegment.OPERATION -> "commit_operation"
        else -> s.path.substringAfterLast('/').substringBefore('.')
    }
}

internal object StaticLogicalZip {
    private data class Stats(val rows: Long, val bytes: Long, val compressed: Long, val crc: Long, val sha: String)
    fun write(db: SQLiteDatabase, work: File, at: Long, app: AppInfo, check: () -> Unit,
              available: () -> Long, written: () -> Unit): Pair<File, StreamedLogicalBackup> {
        val counts = LogicalSegment.entries.associateWith { s ->
            db.compileStatement("SELECT COUNT(*) FROM ${ExportTables.name(s)}" +
                if (s == LogicalSegment.DRAFT) " WHERE state='EDITING'" else "").use { it.simpleQueryForLong() }
        }
        if (counts.values.sum() > BackupLimits.MAX_ROWS_TOTAL) fail(BackupError.InvalidSource, "export exceeds row limit")
        val keys = SnapshotKeys(work, check).let { sorter -> LogicalSegment.entries.associateWith { sorter.sort(db, it) } }
        val generation = db.compileStatement("SELECT dataGeneration FROM record_data_state WHERE id=1").use { it.simpleQueryForString() }
        val revision = db.compileStatement("SELECT businessRevision FROM record_data_state WHERE id=1").use { it.simpleQueryForLong() }
        requireIdentity(generation, "source generation"); requireNonNegativeBelowMax(revision, "businessRevision")
        val zip = File(work, "backup.zip")
        // Keys are now allocated: do not reserve their conservative bound a second time.
        val quota = minOf(BackupLimits.MAX_ZIP_BYTES, available() - BackupLimits.EXPORT_FREE_RESERVE_BYTES - 65536)
        if (quota <= 0) fail(BackupError.StorageFull, "no space for ZIP output")
        var stored = emptyMap<LogicalSegment, Stats>()
        repeat(2) { pass ->
            val stats = LinkedHashMap<LogicalSegment, Stats>()
            var total = 0L
            var manifestWritten: BackupManifest? = null
            ZipOutputStream(object : FilterOutputStream(zip.outputStream().buffered(65536)) {
                var outputBytes = 0L
                override fun write(b: ByteArray, off: Int, len: Int) {
                    check()
                    if (outputBytes + len > quota) fail(BackupError.StorageFull, "ZIP output work quota exceeded")
                    out.write(b, off, len); outputBytes += len
                }
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            }).use { output ->
                for (s in LogicalSegment.entries) {
                    val prior = stored[s]
                    val e = ZipEntry(s.path).apply {
                        time = 946771200000L
                        method = if (counts.getValue(s) == 0L || prior != null) ZipEntry.STORED else ZipEntry.DEFLATED
                        if (method == ZipEntry.STORED) { size = prior?.bytes ?: 0; compressedSize = size; crc = prior?.crc ?: 0 }
                    }
                    output.putNextEntry(e)
                    val digest = MessageDigest.getInstance("SHA-256"); val crc = CRC32()
                    var bytes = 0L; var rows = 0L
                    val sql = "SELECT json_object(${s.fields.joinToString(",") { "'$it',$it" }}) FROM ${ExportTables.name(s)} WHERE rowid=?"
                    db.compileStatement(sql).use { statement ->
                        DataInputStream(keys.getValue(s).inputStream().buffered(65536)).use { input ->
                            repeat(counts.getValue(s).toInt()) {
                                check(); statement.bindLong(1, input.readLong())
                                val raw = statement.simpleQueryForString().toByteArray(Charsets.UTF_8)
                                if (raw.size > BackupLimits.MAX_LINE_BYTES) fail(BackupError.InvalidSource, "export row exceeds line limit")
                                bytes += raw.size + 1; total += raw.size + 1; rows++
                                if (bytes > BackupLimits.MAX_ENTRY_UNCOMPRESSED || total > BackupLimits.MAX_TOTAL_UNCOMPRESSED) {
                                    fail(BackupError.InvalidSource, "export exceeds logical byte limits")
                                }
                                output.write(raw); output.write(10); digest.update(raw); digest.update(10.toByte()); crc.update(raw); crc.update(10)
                            }
                        }
                    }
                    output.closeEntry()
                    stats[s] = Stats(rows, bytes, e.compressedSize, crc.value,
                        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                }
                val provisional = BackupManifest(BackupLimits.FORMAT, 1, 1, at, generation, revision,
                    app.versionCode, app.versionName, 13, counts.values.sum(), total,
                    LogicalSegment.entries.map { s -> stats.getValue(s).let { ManifestSegment(s.path, it.rows, it.bytes, it.sha) } }, "0".repeat(64))
                val manifest = provisional.copy(contentDigest = ManifestCodec.contentDigest(provisional))
                manifestWritten = manifest
                val encoded = ManifestCodec.encode(manifest)
                output.putNextEntry(ZipEntry("manifest.json").apply {
                    time = 946771200000L; method = ZipEntry.STORED; size = encoded.size.toLong(); compressedSize = size
                    crc = CRC32().apply { update(encoded) }.value
                })
                output.write(encoded); output.closeEntry()
            }
            val excessive = stats.filterValues { it.bytes > 0 && it.compressed * 100 < it.bytes }
            if (excessive.isEmpty()) {
                written()
                val validator = StreamingBackupValidator(at)
                val verified = LogicalZipReader.forEachRow(zip, check) { s, row -> validator.accept(s, row) }
                validator.finish(check)
                if (verified.manifest != manifestWritten) fail(BackupError.Internal, "written manifest changed")
                return zip to verified
            }
            if (pass != 0) fail(BackupError.Internal, "compression fallback failed")
            stored = excessive
        }
        fail(BackupError.Internal, "export did not produce a ZIP")
    }
}

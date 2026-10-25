package com.paherdin.record2.backup

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

internal data class SegmentSource(
    val segment: LogicalSegment,
    val file: File,
    val rows: Long,
    val uncompressedBytes: Long,
    val crc: Long,
    val sha256: String,
)

internal data class SegmentStats(
    val segment: LogicalSegment,
    val rows: Long,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val crc: Long,
    val sha256: String,
)

internal data class ReadLogicalBackup(
    val backup: LogicalBackup,
    val zipBytes: Long,
    val zipSha256: String,
    val stats: Map<LogicalSegment, SegmentStats>,
)

internal data class StreamedLogicalBackup(
    val manifest: BackupManifest,
    val zipBytes: Long,
    val zipSha256: String,
)

internal object LogicalZipWriter {
    fun write(target: File, manifest: BackupManifest, sources: List<SegmentSource>) {
        if (sources.map { it.segment } != LogicalSegment.entries.toList()) {
            fail(BackupError.Internal, "segment sources must cover the fixed 12-segment order")
        }
        if (sources.map { it.segment.path } != manifest.segments.map { it.name }) {
            fail(BackupError.Internal, "manifest segment list does not match sources")
        }
        if (ManifestCodec.contentDigest(manifest) != manifest.contentDigest) {
            fail(BackupError.Internal, "manifest contentDigest is not self-consistent")
        }
        val manifestBytes = ManifestCodec.encode(manifest)
        if (manifestBytes.size > BackupLimits.MAX_MANIFEST_BYTES) {
            fail(BackupError.InvalidSource, "manifest exceeds 1 MiB")
        }
        target.parentFile?.mkdirs()
        if (target.exists() && !target.delete()) {
            fail(BackupError.RecoverableIO, "could not replace staging ZIP")
        }
        try {
            FileOutputStream(target).use { fileOut ->
                val out = CountingOutputStream(BufferedOutputStream(fileOut))
                val central = ArrayList<CentralRecord>(BackupLimits.ENTRY_COUNT)
                val manifestCrc = crcOf(manifestBytes)
                writeLocal(out, "manifest.json", 0, manifestCrc, manifestBytes.size.toLong(), manifestBytes.size.toLong())
                out.write(manifestBytes)
                central += CentralRecord(
                    name = "manifest.json", method = 0, crc = manifestCrc,
                    compressedBytes = manifestBytes.size.toLong(),
                    uncompressedBytes = manifestBytes.size.toLong(),
                    localOffset = 0,
                )
                for (source in sources) {
                    val offset = out.count
                    central += writeSegment(out, target, source, offset)
                }
                val cdOffset = out.count
                for (record in central) writeCentral(out, record)
                val cdSize = out.count - cdOffset
                writeEnd(out, central.size, cdSize, cdOffset)
                out.flush()
            }
            if (target.length() > BackupLimits.MAX_ZIP_BYTES) {
                target.delete()
                fail(BackupError.StorageFull, "export ZIP exceeds 64 MiB")
            }
        } catch (error: BackupException) {
            target.delete()
            throw error
        } catch (error: IOException) {
            target.delete()
            throw BackupException(BackupError.RecoverableIO, "could not write staging ZIP", error)
        }
    }

    private fun writeSegment(
        out: CountingOutputStream,
        target: File,
        source: SegmentSource,
        localOffset: Long,
    ): CentralRecord {
        if (!source.file.isFile || source.file.length() != source.uncompressedBytes) {
            fail(BackupError.Internal, "segment staging file length mismatch")
        }
        val hashed = hashFile(source.file)
        if (hashed.bytes != source.uncompressedBytes || hashed.crc != source.crc || hashed.sha256 != source.sha256) {
            fail(BackupError.Internal, "segment staging file digest mismatch")
        }
        if (source.uncompressedBytes == 0L) {
            if (source.rows != 0L || source.crc != 0L) {
                fail(BackupError.Internal, "empty segment must have zero rows and CRC")
            }
            writeLocal(out, source.segment.path, 0, 0, 0, 0)
            return CentralRecord(source.segment.path, 0, 0, 0, 0, localOffset)
        }
        val compressed = File(target.parentFile, "${target.name}.${source.segment.ordinal}.deflate")
        var compressedBytes = 0L
        try {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            try {
                DeflaterOutputStream(
                    BufferedOutputStream(FileOutputStream(compressed)),
                    deflater,
                    65_536,
                ).use { stream ->
                    FileInputStream(source.file).use { input ->
                        input.copyTo(stream, 65_536)
                    }
                }
            } finally {
                deflater.end()
            }
            compressedBytes = compressed.length()
            val method = if (
                compressedBytes > 0 &&
                compressedBytes < source.uncompressedBytes &&
                compressedBytes * 100 >= source.uncompressedBytes
            ) {
                8
            } else {
                0
            }
            val storedBytes = if (method == 8) compressedBytes else source.uncompressedBytes
            writeLocal(out, source.segment.path, method, source.crc, storedBytes, source.uncompressedBytes)
            if (method == 8) {
                FileInputStream(compressed).use { it.copyTo(out, 65_536) }
            } else {
                FileInputStream(source.file).use { it.copyTo(out, 65_536) }
            }
            return CentralRecord(
                source.segment.path, method, source.crc, storedBytes, source.uncompressedBytes, localOffset,
            )
        } finally {
            compressed.delete()
        }
    }

    private fun writeLocal(
        out: CountingOutputStream,
        name: String,
        method: Int,
        crc: Long,
        compressedBytes: Long,
        uncompressedBytes: Long,
    ) {
        val nameBytes = name.toByteArray(StandardCharsets.US_ASCII)
        writeInt(out, 0x04034b50)
        writeShort(out, 20)
        writeShort(out, 0x0800)
        writeShort(out, method)
        writeShort(out, 0)
        writeShort(out, 0x0021)
        writeInt(out, crc)
        writeInt(out, compressedBytes)
        writeInt(out, uncompressedBytes)
        writeShort(out, nameBytes.size)
        writeShort(out, 0)
        out.write(nameBytes)
    }

    private fun writeCentral(out: CountingOutputStream, record: CentralRecord) {
        val nameBytes = record.name.toByteArray(StandardCharsets.US_ASCII)
        writeInt(out, 0x02014b50)
        writeShort(out, 20)
        writeShort(out, 20)
        writeShort(out, 0x0800)
        writeShort(out, record.method)
        writeShort(out, 0)
        writeShort(out, 0x0021)
        writeInt(out, record.crc)
        writeInt(out, record.compressedBytes)
        writeInt(out, record.uncompressedBytes)
        writeShort(out, nameBytes.size)
        writeShort(out, 0)
        writeShort(out, 0)
        writeShort(out, 0)
        writeShort(out, 0)
        writeInt(out, 0)
        writeInt(out, record.localOffset)
        out.write(nameBytes)
    }

    private fun writeEnd(out: CountingOutputStream, entries: Int, cdSize: Long, cdOffset: Long) {
        writeInt(out, 0x06054b50)
        writeShort(out, 0)
        writeShort(out, 0)
        writeShort(out, entries)
        writeShort(out, entries)
        writeInt(out, cdSize)
        writeInt(out, cdOffset)
        writeShort(out, 0)
    }

    private data class CentralRecord(
        val name: String,
        val method: Int,
        val crc: Long,
        val compressedBytes: Long,
        val uncompressedBytes: Long,
        val localOffset: Long,
    )
}

internal data class VerifiedLogicalBackup(
    val manifest: BackupManifest,
    val zipBytes: Long,
    val zipSha256: String,
    val stats: Map<LogicalSegment, SegmentStats>,
)

internal object LogicalZipReader {
    private val expectedNames = listOf("manifest.json") + LogicalSegment.entries.map { it.path }

    fun read(file: File, cancellationCheck: () -> Unit = {}): ReadLogicalBackup {
        val parsed = readInternal(file, collectRows = true, cancellationCheck = cancellationCheck)
        return ReadLogicalBackup(
            backup = LogicalBackup(parsed.manifest, buildContent(parsed.rows)),
            zipBytes = parsed.zipBytes,
            zipSha256 = parsed.zipSha256,
            stats = parsed.stats,
        )
    }

    /**
     * Streams real rows without retaining them. Container headers, per-entry sizes/CRCs and the
     * segment/manifest totals are verified; the manifest is returned after the last segment. The
     * caller owns the transaction boundary.
     */
    fun forEachRow(
        file: File,
        cancellationCheck: () -> Unit = {},
        sink: (LogicalSegment, SegmentRow) -> Unit,
    ): StreamedLogicalBackup {
        val parsed = readInternal(
            file,
            collectRows = false,
            cancellationCheck = cancellationCheck,
            rowSink = sink,
        )
        return StreamedLogicalBackup(parsed.manifest, parsed.zipBytes, parsed.zipSha256)
    }

    /** Container/hash/row verification without retaining the decoded rows. */
    fun verify(file: File): VerifiedLogicalBackup {
        val parsed = readInternal(file, collectRows = false, cancellationCheck = {})
        return VerifiedLogicalBackup(parsed.manifest, parsed.zipBytes, parsed.zipSha256, parsed.stats)
    }

    private fun readInternal(
        file: File,
        collectRows: Boolean,
        cancellationCheck: () -> Unit,
        rowSink: ((LogicalSegment, SegmentRow) -> Unit)? = null,
    ): ParsedZip {
        if (!file.isFile) fail(BackupError.InvalidSource, "backup ZIP does not exist")
        val size = file.length()
        if (size <= 0 || size > BackupLimits.MAX_ZIP_BYTES) {
            fail(BackupError.InvalidSource, "backup ZIP size is outside the v1 limit")
        }
        try {
            RandomAccessFile(file, "r").use { raf ->
                val eocd = readEnd(raf, size)
                if (eocd.entries != BackupLimits.ENTRY_COUNT) {
                    fail(BackupError.InvalidSource, "backup ZIP must contain exactly 13 entries")
                }
                if (eocd.cdOffset + eocd.cdSize != eocd.offset) {
                    fail(BackupError.InvalidSource, "central directory does not end at EOCD")
                }
                val central = readCentralDirectory(raf, eocd)
                if (central.size != BackupLimits.ENTRY_COUNT || central.keys != expectedNames.toSet()) {
                    fail(BackupError.InvalidSource, "backup ZIP entry set is not the fixed v1 set")
                }
                readAndValidateLocals(raf, central, eocd)
                var zipUncompressed = 0L
                var zipRows = 0L
                val stats = LinkedHashMap<LogicalSegment, SegmentStats>()
                val rows = LinkedHashMap<LogicalSegment, List<SegmentRow>>()
                for (segment in LogicalSegment.entries) {
                    cancellationCheck()
                    val entry = central.getValue(segment.path)
                    val payload = readEntry(
                        raf, entry, segment, collectRows, cancellationCheck, rowSink,
                    )
                    if (payload.stats.uncompressedBytes > BackupLimits.MAX_ENTRY_UNCOMPRESSED) {
                        fail(BackupError.InvalidSource, "${segment.path} exceeds 128 MiB")
                    }
                    zipUncompressed += payload.stats.uncompressedBytes
                    zipRows += payload.stats.rows
                    stats[segment] = payload.stats
                    rows[segment] = payload.rows
                }
                val manifestEntry = central.getValue("manifest.json")
                val manifestBytes = readManifestBytes(raf, manifestEntry)
                val manifest = ManifestCodec.decode(manifestBytes)
                if (manifest.totalRows != zipRows || manifest.totalUncompressedBytes != zipUncompressed) {
                    fail(BackupError.InvalidSource, "manifest totals do not match ZIP contents")
                }
                if (zipRows > BackupLimits.MAX_ROWS_TOTAL) {
                    fail(BackupError.InvalidSource, "backup row count exceeds 500,000")
                }
                for (segment in LogicalSegment.entries) {
                    val expected = manifest.segments[segment.ordinal]
                    val actual = stats.getValue(segment)
                    if (expected.name != segment.path ||
                        expected.rows != actual.rows ||
                        expected.uncompressedBytes != actual.uncompressedBytes ||
                        expected.sha256 != actual.sha256
                    ) {
                        fail(BackupError.InvalidSource, "${segment.path} does not match manifest")
                    }
                }
                if (ManifestCodec.contentDigest(manifest) != manifest.contentDigest) {
                    fail(BackupError.InvalidSource, "manifest contentDigest mismatch")
                }
                val zipSha = sha256File(file)
                    ?: fail(BackupError.RecoverableIO, "could not hash backup ZIP")
                return ParsedZip(manifest, rows, size, zipSha, stats)
            }
        } catch (error: BackupException) {
            throw error
        } catch (error: IOException) {
            throw BackupException(BackupError.RecoverableIO, "could not read backup ZIP", error)
        }
    }

    private data class ParsedZip(
        val manifest: BackupManifest,
        val rows: Map<LogicalSegment, List<SegmentRow>>,
        val zipBytes: Long,
        val zipSha256: String,
        val stats: Map<LogicalSegment, SegmentStats>,
    )

    private fun readEnd(raf: RandomAccessFile, size: Long): EndRecord {
        val maxTail = minOf(size, 22L + 65_535L).toInt()
        val tail = ByteArray(maxTail)
        raf.seek(size - maxTail)
        raf.readFully(tail)
        var found = -1
        var index = tail.size - 22
        while (index >= 0) {
            if (u32(tail, index) == 0x06054b50L) {
                val commentLength = u16(tail, index + 20)
                if (index + 22 + commentLength == tail.size) {
                    found = index
                    break
                }
            }
            index--
        }
        if (found < 0) fail(BackupError.InvalidSource, "ZIP EOCD is missing")
        if (found >= 20 && u32(tail, found - 20) == 0x07064b50L) {
            fail(BackupError.InvalidSource, "ZIP64 is not allowed")
        }
        val disk = u16(tail, found + 4)
        val cdDisk = u16(tail, found + 6)
        val entriesDisk = u16(tail, found + 8)
        val entries = u16(tail, found + 10)
        val cdSize = u32(tail, found + 12)
        val cdOffset = u32(tail, found + 16)
        val comment = u16(tail, found + 20)
        if (disk != 0 || cdDisk != 0 || entriesDisk != entries || entries == 0xFFFF ||
            cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL || comment != 0
        ) {
            fail(BackupError.InvalidSource, "multi-disk, ZIP64 or commented ZIP is not allowed")
        }
        val eocdOffset = size - tail.size + found
        if (cdOffset + cdSize != eocdOffset) {
            fail(BackupError.InvalidSource, "ZIP has trailing or missing central directory bytes")
        }
        return EndRecord(entries = entries, cdSize = cdSize, cdOffset = cdOffset, offset = eocdOffset)
    }

    private fun readCentralDirectory(raf: RandomAccessFile, eocd: EndRecord): Map<String, CentralEntry> {
        raf.seek(eocd.cdOffset)
        val entries = LinkedHashMap<String, CentralEntry>()
        repeat(eocd.entries) {
            if (raf.filePointer + 46 > eocd.cdOffset + eocd.cdSize) {
                fail(BackupError.InvalidSource, "central directory is truncated")
            }
            if (randU32(raf) != 0x02014b50L) fail(BackupError.InvalidSource, "bad central directory entry")
            randU16(raf)
            randU16(raf)
            val flags = randU16(raf)
            val method = randU16(raf)
            randU16(raf)
            randU16(raf)
            val crc = randU32(raf)
            val compressed = randU32(raf)
            val uncompressed = randU32(raf)
            val nameLength = randU16(raf)
            val extraLength = randU16(raf)
            val commentLength = randU16(raf)
            val diskStart = randU16(raf)
            randU16(raf)
            val externalAttributes = randU32(raf)
            val localOffset = randU32(raf)
            val nameBytes = ByteArray(nameLength)
            raf.readFully(nameBytes)
            val extra = ByteArray(extraLength)
            raf.readFully(extra)
            val comment = ByteArray(commentLength)
            raf.readFully(comment)
            if (diskStart != 0 || extraLength != 0 || commentLength != 0) {
                fail(BackupError.InvalidSource, "entry extra fields, comments and split disks are not allowed")
            }
            if (flags and 0x0001 != 0 || flags and 0x0040 != 0 || flags and 0x2000 != 0 || flags and 0x0020 != 0) {
                fail(BackupError.InvalidSource, "encrypted or patched ZIP entries are not allowed")
            }
            if (method != 0 && method != 8) fail(BackupError.InvalidSource, "unsupported ZIP compression method")
            val mode = (externalAttributes ushr 16) and 0xFFFF
            if (mode != 0L) {
                val type = mode and 0xF000L
                if (type != 0L && type != 0x8000L) {
                    fail(BackupError.InvalidSource, "directories, symlinks and special ZIP entries are not allowed")
                }
            }
            if (compressed == 0xFFFFFFFFL || uncompressed == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                fail(BackupError.InvalidSource, "ZIP64 entries are not allowed")
            }
            val name = decodeAscii(nameBytes)
            if (name !in expectedNames) fail(BackupError.InvalidSource, "unknown ZIP entry $name")
            if (entries.put(name, CentralEntry(
                    name, flags, method, crc, compressed, uncompressed, localOffset,
                )) != null
            ) {
                fail(BackupError.InvalidSource, "duplicate ZIP entry $name")
            }
        }
        if (raf.filePointer != eocd.cdOffset + eocd.cdSize) {
            fail(BackupError.InvalidSource, "central directory size mismatch")
        }
        return entries
    }

    private fun readAndValidateLocals(
        raf: RandomAccessFile,
        central: Map<String, CentralEntry>,
        eocd: EndRecord,
    ) {
        val locals = ArrayList<LocalEntry>(central.size)
        for (entry in central.values) {
            if (entry.localOffset >= eocd.cdOffset) fail(BackupError.InvalidSource, "local header offset outside data area")
            raf.seek(entry.localOffset)
            if (randU32(raf) != 0x04034b50L) fail(BackupError.InvalidSource, "bad local file header")
            randU16(raf)
            val flags = randU16(raf)
            val method = randU16(raf)
            randU16(raf)
            randU16(raf)
            val crc = randU32(raf)
            val compressed = randU32(raf)
            val uncompressed = randU32(raf)
            val nameLength = randU16(raf)
            val extraLength = randU16(raf)
            val nameBytes = ByteArray(nameLength)
            raf.readFully(nameBytes)
            val extra = ByteArray(extraLength)
            raf.readFully(extra)
            val name = decodeAscii(nameBytes)
            if (name != entry.name) fail(BackupError.InvalidSource, "local/central name mismatch")
            if (flags != entry.flags || method != entry.method) {
                fail(BackupError.InvalidSource, "local/central flags or method mismatch")
            }
            if (extraLength != 0) fail(BackupError.InvalidSource, "local extra fields are not allowed")
            val dataStart = entry.localOffset + 30 + nameLength
            val dataEnd = dataStart + entry.compressedBytes
            if (dataEnd > eocd.cdOffset) fail(BackupError.InvalidSource, "entry data overlaps central directory")
            var end = dataEnd
            val hasDescriptor = flags and 0x0008 != 0
            if (!hasDescriptor) {
                if (crc != entry.crc || compressed != entry.compressedBytes || uncompressed != entry.uncompressedBytes) {
                    fail(BackupError.InvalidSource, "local header sizes/CRC do not match central directory")
                }
            } else {
                if (crc != 0L && crc != entry.crc) fail(BackupError.InvalidSource, "local CRC mismatch")
                if (compressed != 0L && compressed != entry.compressedBytes) {
                    fail(BackupError.InvalidSource, "local compressed size mismatch")
                }
                if (uncompressed != 0L && uncompressed != entry.uncompressedBytes) {
                    fail(BackupError.InvalidSource, "local uncompressed size mismatch")
                }
                raf.seek(dataEnd)
                val descriptorSignature = randU32(raf)
                val descriptorCrc = if (descriptorSignature == 0x08074b50L) randU32(raf) else descriptorSignature
                val descriptorCompressed = randU32(raf)
                val descriptorUncompressed = randU32(raf)
                if (descriptorCrc != entry.crc ||
                    descriptorCompressed != entry.compressedBytes ||
                    descriptorUncompressed != entry.uncompressedBytes
                ) {
                    fail(BackupError.InvalidSource, "ZIP data descriptor mismatch")
                }
                end += if (descriptorSignature == 0x08074b50L) 16 else 12
            }
            locals += LocalEntry(entry, dataStart, end)
        }
        locals.sortBy { it.entry.localOffset }
        var cursor = 0L
        for (local in locals) {
            if (local.entry.localOffset != cursor) {
                fail(BackupError.InvalidSource, "ZIP contains gaps or hidden local data")
            }
            cursor = local.end
        }
        if (cursor != eocd.cdOffset) fail(BackupError.InvalidSource, "ZIP local data does not cover the file")
    }

    private fun readManifestBytes(raf: RandomAccessFile, entry: CentralEntry): ByteArray {
        val out = ByteArrayOutputStream()
        val result = readUncompressed(raf, entry, {}) { buffer, length ->
            if (out.size().toLong() + length > BackupLimits.MAX_MANIFEST_BYTES) {
                fail(BackupError.InvalidSource, "manifest exceeds 1 MiB")
            }
            out.write(buffer, 0, length)
        }
        if (result.uncompressedBytes == 0L) fail(BackupError.InvalidSource, "manifest is empty")
        return out.toByteArray()
    }

    private data class EntryRead(
        val rows: List<SegmentRow>,
        val stats: SegmentStats,
    )

    private fun readEntry(
        raf: RandomAccessFile,
        entry: CentralEntry,
        segment: LogicalSegment,
        collectRows: Boolean,
        cancellationCheck: () -> Unit,
        rowSink: ((LogicalSegment, SegmentRow) -> Unit)?,
    ): EntryRead {
        validateSegmentContainer(entry, segment)
        val rows = if (collectRows) ArrayList<SegmentRow>() else null
        val decoder = LineDecoder(segment, rows, cancellationCheck, rowSink)
        val result = readUncompressed(raf, entry, cancellationCheck) { buffer, length -> decoder.accept(buffer, length) }
        decoder.finish()
        if (result.uncompressedBytes == 0L) {
            if (decoder.lineCount != 0L) fail(BackupError.InvalidSource, "${segment.path} empty bytes with rows")
            if (entry.method != 0 || entry.compressedBytes != 0L || entry.crc != 0L) {
                fail(BackupError.InvalidSource, "empty segment must be STORED with 0/0 and CRC 0")
            }
        } else if (decoder.lineCount == 0L) {
            fail(BackupError.InvalidSource, "${segment.path} has bytes but no rows")
        }
        return EntryRead(
            rows.orEmpty(),
            SegmentStats(
                segment, decoder.lineCount, result.uncompressedBytes, entry.compressedBytes,
                result.crc, result.sha256,
            ),
        )
    }

    private class LineDecoder(
        private val segment: LogicalSegment,
        private val rows: MutableList<SegmentRow>?,
        private val cancellationCheck: () -> Unit,
        private val rowSink: ((LogicalSegment, SegmentRow) -> Unit)?,
    ) {
        private val line = ByteArrayOutputStream()
        var lineCount: Long = 0
            private set

        fun accept(buffer: ByteArray, length: Int) {
            cancellationCheck()
            var index = 0
            while (index < length) {
                var lf = index
                while (lf < length && buffer[lf] != '\n'.code.toByte()) lf++
                val piece = lf - index
                if (line.size().toLong() + piece > BackupLimits.MAX_LINE_BYTES) {
                    fail(BackupError.InvalidSource, "${segment.path} line exceeds 8 MiB")
                }
                line.write(buffer, index, piece)
                if (lf == length) return
                if (line.size() == 0) {
                    fail(BackupError.InvalidSource, "${segment.path} has an empty line")
                }
                val bytes = line.toByteArray()
                if (bytes.any { it == '\r'.code.toByte() }) {
                    fail(BackupError.InvalidSource, "${segment.path} contains CRLF")
                }
                val value = StrictJson.decodeObject(bytes, BackupLimits.MAX_LINE_BYTES)
                val row = SegmentCodecs.decode(segment, value)
                rows?.add(row)
                rowSink?.invoke(segment, row)
                lineCount++
                if (lineCount > BackupLimits.MAX_ROWS_TOTAL) {
                    fail(BackupError.InvalidSource, "row count exceeds 500,000")
                }
                line.reset()
                index = lf + 1
            }
        }

        fun finish() {
            if (line.size() != 0) {
                fail(BackupError.InvalidSource, "${segment.path} last line is not LF-terminated")
            }
        }
    }

    private fun validateSegmentContainer(entry: CentralEntry, segment: LogicalSegment) {
        if (entry.uncompressedBytes > BackupLimits.MAX_ENTRY_UNCOMPRESSED) {
            fail(BackupError.InvalidSource, "${segment.path} exceeds 128 MiB")
        }
        if (entry.method == 0 && entry.compressedBytes != entry.uncompressedBytes) {
            fail(BackupError.InvalidSource, "${segment.path} STORED sizes mismatch")
        }
        if (entry.uncompressedBytes == 0L) {
            if (entry.method != 0 || entry.compressedBytes != 0L || entry.crc != 0L ||
                entry.flags and 0x0008 != 0
            ) {
                fail(BackupError.InvalidSource, "empty segment must be STORED/0/0 without a data descriptor")
            }
        } else {
            if (entry.compressedBytes == 0L) fail(BackupError.InvalidSource, "${segment.path} has zero compressed bytes")
            if (entry.compressedBytes * 100 < entry.uncompressedBytes) {
                fail(BackupError.InvalidSource, "${segment.path} compression ratio exceeds 100:1")
            }
        }
    }

    private data class UncompressedResult(
        val uncompressedBytes: Long,
        val crc: Long,
        val sha256: String,
    )

    private fun readUncompressed(
        raf: RandomAccessFile,
        entry: CentralEntry,
        cancellationCheck: () -> Unit,
        sink: (ByteArray, Int) -> Unit,
    ): UncompressedResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var total = 0L
        val slice = SliceInputStream(raf, entry.dataStart(), entry.compressedBytes)
        fun consume(buffer: ByteArray, length: Int) {
            total += length
            if (total > entry.uncompressedBytes) {
                fail(BackupError.InvalidSource, "entry expands beyond its declared size")
            }
            crc.update(buffer, 0, length)
            digest.update(buffer, 0, length)
            sink(buffer, length)
        }
        if (entry.method == 0) {
            val buffer = ByteArray(65_536)
            while (true) {
                cancellationCheck()
                val read = slice.read(buffer)
                if (read < 0) break
                consume(buffer, read)
            }
        } else {
            val inflater = Inflater(true)
            try {
                val input = ByteArray(65_536)
                val output = ByteArray(65_536)
                while (!inflater.finished()) {
                    cancellationCheck()
                    if (inflater.needsInput()) {
                        val read = slice.read(input)
                        if (read < 0) fail(BackupError.InvalidSource, "truncated DEFLATE stream")
                        inflater.setInput(input, 0, read)
                    }
                    val produced = try {
                        inflater.inflate(output)
                    } catch (error: Exception) {
                        throw BackupException(BackupError.InvalidSource, "invalid DEFLATE stream", error)
                    }
                    if (produced > 0) consume(output, produced)
                    if (produced == 0 && inflater.needsDictionary()) {
                        fail(BackupError.InvalidSource, "DEFLATE dictionary is not allowed")
                    }
                    if (produced == 0 && !inflater.needsInput() && !inflater.finished()) {
                        fail(BackupError.InvalidSource, "DEFLATE stream makes no progress")
                    }
                }
                if (inflater.remaining != 0) fail(BackupError.InvalidSource, "trailing DEFLATE bytes")
                if (slice.remaining != 0L) fail(BackupError.InvalidSource, "compressed entry has trailing bytes")
            } finally {
                inflater.end()
            }
        }
        if (total != entry.uncompressedBytes) {
            fail(BackupError.InvalidSource, "entry uncompressed size mismatch")
        }
        if (crc.value != entry.crc) fail(BackupError.InvalidSource, "entry CRC mismatch")
        return UncompressedResult(total, crc.value, hex(digest.digest()))
    }

    private fun CentralEntry.dataStart(): Long = localOffset + 30 + name.toByteArray(StandardCharsets.US_ASCII).size

    private data class EndRecord(val entries: Int, val cdSize: Long, val cdOffset: Long, val offset: Long)
    private data class CentralEntry(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedBytes: Long,
        val uncompressedBytes: Long,
        val localOffset: Long,
    )
    private data class LocalEntry(val entry: CentralEntry, val dataStart: Long, val end: Long)

    private fun decodeAscii(bytes: ByteArray): String {
        if (bytes.any { it < 0 }) fail(BackupError.InvalidSource, "non-ASCII ZIP entry name")
        return String(bytes, StandardCharsets.US_ASCII)
    }

    private fun randU16(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        if (b0 < 0 || b1 < 0) fail(BackupError.InvalidSource, "truncated ZIP")
        return b0 or (b1 shl 8)
    }

    private fun randU32(raf: RandomAccessFile): Long {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) fail(BackupError.InvalidSource, "truncated ZIP")
        return (b0.toLong() or (b1.toLong() shl 8) or (b2.toLong() shl 16) or (b3.toLong() shl 24)) and 0xFFFFFFFFL
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toInt() and 0xFF).toLong() or
            ((bytes[offset + 1].toInt() and 0xFF).toLong() shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF).toLong() shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF).toLong() shl 24)) and 0xFFFFFFFFL
}

private class SliceInputStream(
    private val raf: RandomAccessFile,
    start: Long,
    private val length: Long,
) : java.io.InputStream() {
    private var position = start
    var remaining: Long = length
        private set

    override fun read(): Int {
        val one = ByteArray(1)
        val count = read(one, 0, 1)
        return if (count < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining == 0L) return -1
        val allowed = minOf(len.toLong(), remaining).toInt()
        raf.seek(position)
        val read = raf.read(buffer, offset, allowed)
        if (read < 0) return -1
        position += read
        remaining -= read
        return read
    }
}

private class CountingOutputStream(private val delegate: OutputStream) : FilterOutputStream(delegate) {
    var count: Long = 0
        private set

    override fun write(byte: Int) {
        out.write(byte)
        count++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        count += length
    }

    override fun flush() = out.flush()
    override fun close() = out.close()
}

private data class FileHash(val bytes: Long, val crc: Long, val sha256: String)

private fun hashFile(file: File): FileHash {
    val digest = MessageDigest.getInstance("SHA-256")
    val crc = CRC32()
    var total = 0L
    FileInputStream(file).use { input ->
        val buffer = ByteArray(65_536)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            crc.update(buffer, 0, read)
            digest.update(buffer, 0, read)
        }
    }
    return FileHash(total, crc.value, hex(digest.digest()))
}

private fun sha256File(file: File): String? {
    val digest = MessageDigest.getInstance("SHA-256")
    return try {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        hex(digest.digest())
    } catch (error: IOException) {
        null
    }
}

private fun crcOf(bytes: ByteArray): Long {
    val crc = CRC32()
    crc.update(bytes)
    return crc.value
}

private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (byte in bytes) {
        append(HEX[byte.toInt() shr 4 and 15])
        append(HEX[byte.toInt() and 15])
    }
}

private fun writeShort(out: OutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write(value ushr 8 and 0xFF)
}

private fun writeInt(out: OutputStream, value: Int) {
    writeInt(out, value.toLong())
}

private fun writeInt(out: OutputStream, value: Long) {
    out.write((value and 0xFF).toInt())
    out.write((value ushr 8 and 0xFF).toInt())
    out.write((value ushr 16 and 0xFF).toInt())
    out.write((value ushr 24 and 0xFF).toInt())
}

private val HEX = "0123456789abcdef"

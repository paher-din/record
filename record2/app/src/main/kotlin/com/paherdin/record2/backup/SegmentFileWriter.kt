package com.paherdin.record2.backup

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32

/** Streams one sorted segment to a temporary file and returns the exact bytes its ZIP entry needs. */
internal class SegmentFileWriter(
    private val segment: LogicalSegment,
    private val file: File,
) : AutoCloseable {
    private val out = BufferedOutputStream(FileOutputStream(file), 65_536)
    private val crc = CRC32()
    private val digest = MessageDigest.getInstance("SHA-256")
    private var rows = 0L
    private var bytes = 0L
    private var finished = false

    fun append(row: SegmentRow) {
        check(!finished) { "segment writer already closed" }
        val line = SegmentCodecs.encode(row).toByteArray(StandardCharsets.UTF_8)
        if (line.size > BackupLimits.MAX_LINE_BYTES) {
            fail(BackupError.StorageFull, "${segment.path} line exceeds 8 MiB")
        }
        if (bytes + line.size + 1 > BackupLimits.MAX_ENTRY_UNCOMPRESSED) {
            fail(BackupError.StorageFull, "${segment.path} exceeds 128 MiB")
        }
        if (rows >= BackupLimits.MAX_ROWS_TOTAL) {
            fail(BackupError.StorageFull, "row count exceeds 500,000")
        }
        val newline = byteArrayOf('\n'.code.toByte())
        out.write(line)
        out.write(newline)
        crc.update(line)
        crc.update(newline)
        digest.update(line)
        digest.update(newline)
        rows++
        bytes += line.size + 1L
    }

    fun finish(): SegmentSource {
        check(!finished) { "segment writer already closed" }
        finished = true
        try {
            out.flush()
            out.close()
        } catch (error: IOException) {
            file.delete()
            throw BackupException(BackupError.RecoverableIO, "could not close segment staging file", error)
        }
        if (file.length() != bytes) {
            file.delete()
            fail(BackupError.Internal, "segment staging file length mismatch")
        }
        return SegmentSource(
            segment = segment,
            file = file,
            rows = rows,
            uncompressedBytes = bytes,
            crc = crc.value,
            sha256 = hex(digest.digest()),
        )
    }

    override fun close() {
        if (!finished) {
            finished = true
            runCatching { out.close() }
        }
    }
}

private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (byte in bytes) {
        append(HEX[byte.toInt() shr 4 and 15])
        append(HEX[byte.toInt() and 15])
    }
}

private val HEX = "0123456789abcdef"

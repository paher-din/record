package com.paherdin.record2.backup

import android.os.Debug
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.FinanceSeeds
import com.paherdin.record2.storage.RecordDatabase
import com.paherdin.record2.storage.RoomRestoreStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Opt-in maximum-package resource probe. It is not part of the normal G1 regression suite.
 *
 * Near-maximum row/segment/total package (500,000 rows, ~126 MiB transaction segment, ~232 MiB
 * uncompressed, ~46 MiB ZIP, 7.4 MiB lines):
 * `-e rows 500000 -e bodyChars 40 -e mode large`
 *
 * ZIP-heavy package (500,000 rows, ~180 MiB uncompressed, ten incompressible 7.4 MiB memo lines,
 * ZIP close to the 64 MiB cap): `-e rows 500000 -e bodyChars 40 -e mode zip`
 */
internal class G1ResourceContractTest {
    private class Source(private val file: File) : BackupSource {
        override val displayName: String? = file.name
        override suspend fun copyTo(destination: File, maxBytes: Long): Long {
            if (file.length() > maxBytes) {
                throw BackupException(BackupError.InvalidSource, "source exceeds limit")
            }
            file.inputStream().use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
            return file.length()
        }
    }

    @Test
    fun largeNotesPackagePreflightAndRestore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val rows = args.getString("rows")?.toIntOrNull() ?: 100_000
        val bodyChars = args.getString("bodyChars")?.toIntOrNull() ?: 120
        val mode = args.getString("mode") ?: "notes"
        val large = mode == "large"
        val zipHeavy = mode == "zip"
        val transactionCount = if (zipHeavy) 10 else 17
        val random = args.getString("randomBody") == "true"
        val randomSource = java.util.Random(42L)
        val now = System.currentTimeMillis()
        val staging = File(context.cacheDir, "g1-resource-${UUID.randomUUID()}").apply { mkdirs() }
        val zip = File(staging, "large.zip")
        val targetName = "g1-resource-target-${UUID.randomUUID()}.db"
        val target = Room.databaseBuilder(context, RecordDatabase::class.java, targetName).build()
        target.openHelper.writableDatabase
        val targetPath = context.getDatabasePath(targetName)
        val runtime = Runtime.getRuntime()
        fun used(): Long = runtime.totalMemory() - runtime.freeMemory()

        try {
            val account = AccountRow(
                FinanceSeeds.ACCOUNT_ID, "日常账户", "CNY", 2, now - 10_000, now - 5_000,
            )
            val categories = FinanceSeeds.categories.mapIndexed { index, seed ->
                CategoryRow(seed.id, seed.name, seed.direction.name, 1, now - 10_000 + index, now - 5_000)
            }
            val noteCount = rows - 1 - categories.size - if (large) 34 else if (zipHeavy) transactionCount * 2 else 0
            require(noteCount > 17) { "rows must exceed the fixed seed rows and link samples" }
            val noteFile = File(staging, "notes.jsonl")
            val digest = MessageDigest.getInstance("SHA-256")
            val crc = CRC32()
            var bytes = 0L
            val firstNoteIds = ArrayList<String>(17)
            BufferedOutputStream(FileOutputStream(noteFile)).use { out ->
                repeat(noteCount) { index ->
                    val body = when {
                        random -> "n$index-" + randomPayload(bodyChars, randomSource)
                        large -> "n$index-" + mixedPayload(bodyChars, randomSource)
                        else -> "n$index-" + "x".repeat(bodyChars)
                    }
                    val note = NoteRow(
                        id = UUID.randomUUID().toString(),
                        body = body,
                        occurredAt = now - 10_000,
                        version = 1,
                        createdAt = now - 10_000,
                        updatedAt = now - 5_000,
                        deletedAt = null,
                    )
                    if (firstNoteIds.size < 17) firstNoteIds += note.id
                    val line = (SegmentCodecs.encode(note) + "\n").toByteArray(StandardCharsets.UTF_8)
                    out.write(line)
                    crc.update(line)
                    digest.update(line)
                    bytes += line.size
                }
            }
            val noteSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }

            var transactionFile: File? = null
            var linkFile: File? = null
            var transactionRows = 0L
            var transactionBytes = 0L
            var transactionCrc = 0L
            var transactionSha = ""
            var linkRows = 0L
            var linkBytes = 0L
            var linkCrc = 0L
            var linkSha = ""
            if (large || zipHeavy) {
                val expenseCategory = categories.first { it.direction == "EXPENSE" }
                val txDigest = MessageDigest.getInstance("SHA-256")
                val txCrc = CRC32()
                var txBytes = 0L
                val transactionIds = ArrayList<String>(transactionCount)
                transactionFile = File(staging, "transactions.jsonl")
                BufferedOutputStream(FileOutputStream(transactionFile)).use { out ->
                    repeat(transactionCount) { index ->
                        val memo = if (zipHeavy) {
                            "t$index-" + randomPayload(7_400_000, randomSource)
                        } else {
                            "t$index-" + mixedPayload(7_400_000, randomSource)
                        }
                        val transaction = TransactionRow(
                            id = UUID.randomUUID().toString(),
                            direction = "EXPENSE",
                            amountMinor = 1,
                            currency = "CNY",
                            accountId = FinanceSeeds.ACCOUNT_ID,
                            categoryId = expenseCategory.id,
                            memo = memo,
                            occurredAt = now - 10_000,
                            version = 1,
                            createdAt = now - 10_000,
                            updatedAt = now - 5_000,
                        )
                        transactionIds += transaction.id
                        val line = (SegmentCodecs.encode(transaction) + "\n").toByteArray(StandardCharsets.UTF_8)
                        out.write(line)
                        txCrc.update(line)
                        txDigest.update(line)
                        txBytes += line.size
                    }
                }
                val linkDigest = MessageDigest.getInstance("SHA-256")
                val linkCrc32 = CRC32()
                var linkFileBytes = 0L
                linkFile = File(staging, "links.jsonl")
                BufferedOutputStream(FileOutputStream(linkFile)).use { out ->
                    repeat(transactionCount) { index ->
                        val link = LinkRow(transactionIds[index], firstNoteIds[index])
                        val line = (SegmentCodecs.encode(link) + "\n").toByteArray(StandardCharsets.UTF_8)
                        out.write(line)
                        linkCrc32.update(line)
                        linkDigest.update(line)
                        linkFileBytes += line.size
                    }
                }
                transactionRows = transactionCount.toLong()
                transactionBytes = txBytes
                transactionCrc = txCrc.value
                transactionSha = txDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                linkRows = transactionCount.toLong()
                linkBytes = linkFileBytes
                linkCrc = linkCrc32.value
                linkSha = linkDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            }

            val sources = LogicalSegment.entries.map { segment ->
                val file: File
                val segmentRows: Long
                val segmentBytes: Long
                val segmentCrc: Long
                val segmentSha: String
                when (segment) {
                    LogicalSegment.ACCOUNT -> {
                        file = File(staging, "account.jsonl")
                        file.writeText(SegmentCodecs.encode(account) + "\n", StandardCharsets.UTF_8)
                        segmentRows = 1
                        segmentBytes = file.length()
                        val data = file.readBytes()
                        segmentCrc = CRC32().apply { update(data) }.value
                        segmentSha = sha256(data)
                    }
                    LogicalSegment.CATEGORY -> {
                        file = File(staging, "category.jsonl")
                        file.writeText(categories.joinToString("") { SegmentCodecs.encode(it) + "\n" }, StandardCharsets.UTF_8)
                        segmentRows = categories.size.toLong()
                        segmentBytes = file.length()
                        val data = file.readBytes()
                        segmentCrc = CRC32().apply { update(data) }.value
                        segmentSha = sha256(data)
                    }
                    LogicalSegment.TRANSACTION -> {
                        val existing = transactionFile
                        if (existing == null) {
                            file = File(staging, "transaction.jsonl").apply { writeBytes(ByteArray(0)) }
                            segmentRows = 0
                            segmentBytes = 0
                            segmentCrc = 0
                            segmentSha = sha256(ByteArray(0))
                        } else {
                            file = existing
                            segmentRows = transactionRows
                            segmentBytes = transactionBytes
                            segmentCrc = transactionCrc
                            segmentSha = transactionSha
                        }
                    }
                    LogicalSegment.LINK -> {
                        val existing = linkFile
                        if (existing == null) {
                            file = File(staging, "link.jsonl").apply { writeBytes(ByteArray(0)) }
                            segmentRows = 0
                            segmentBytes = 0
                            segmentCrc = 0
                            segmentSha = sha256(ByteArray(0))
                        } else {
                            file = existing
                            segmentRows = linkRows
                            segmentBytes = linkBytes
                            segmentCrc = linkCrc
                            segmentSha = linkSha
                        }
                    }
                    LogicalSegment.NOTE -> {
                        file = noteFile
                        segmentRows = noteCount.toLong()
                        segmentBytes = bytes
                        segmentCrc = crc.value
                        segmentSha = noteSha
                    }
                    else -> {
                        file = File(staging, "${segment.name.lowercase()}.jsonl").apply { writeBytes(ByteArray(0)) }
                        segmentRows = 0
                        segmentBytes = 0
                        segmentCrc = 0
                        segmentSha = sha256(ByteArray(0))
                    }
                }
                SegmentSource(segment, file, segmentRows, segmentBytes, segmentCrc, segmentSha)
            }
            val totalRows = sources.sumOf { it.rows }
            val totalBytes = sources.sumOf { it.uncompressedBytes }
            val baseManifest = BackupManifest(
                format = BackupLimits.FORMAT,
                formatVersion = BackupLimits.FORMAT_VERSION,
                databaseSchemaVersion = BackupLimits.DATABASE_SCHEMA_VERSION,
                exportedAt = now,
                sourceGeneration = UUID.randomUUID().toString(),
                businessRevision = 1,
                appVersionCode = 1,
                appVersionName = "resource",
                entryCount = BackupLimits.ENTRY_COUNT,
                totalRows = totalRows,
                totalUncompressedBytes = totalBytes,
                segments = sources.map { ManifestSegment(it.segment.path, it.rows, it.uncompressedBytes, it.sha256) },
                contentDigest = "",
            )
            val manifest = baseManifest.copy(contentDigest = ManifestCodec.contentDigest(baseManifest))
            LogicalZipWriter.write(zip, manifest, sources)
            val zipBytes = zip.length()
            val preflightStart = System.nanoTime()
            val service = RestoreService(
                RoomRestoreStore(target, java.time.Clock.systemUTC()),
                targetPath, staging, DataOperationGate(), java.time.Clock.systemUTC(),
                availableBytes = { Long.MAX_VALUE },
            )
            val candidate = service.preflight(Source(zip))
            val preflightMs = (System.nanoTime() - preflightStart) / 1_000_000
            val phaseStart = System.nanoTime()
            val result = service.restore(candidate)
            val restoreMs = (System.nanoTime() - phaseStart) / 1_000_000
            val count = target.query("SELECT COUNT(*) FROM note", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
            assertEquals(noteCount.toLong(), count)
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            println(
                "G1-RESOURCE " + buildString {
                    append("{\"mode\":\"").append(mode).append("\"")
                    append(",\"rows\":").append(totalRows)
                    append(",\"notes\":").append(noteCount)
                    append(",\"bodyChars\":").append(bodyChars)
                    append(",\"uncompressedBytes\":").append(totalBytes)
                    append(",\"zipBytes\":").append(zipBytes)
                    append(",\"preflightMs\":").append(preflightMs)
                    append(",\"restoreMs\":").append(restoreMs)
                    append(",\"heapBefore\":").append(used())
                    append(",\"totalPssKb\":").append(memory.totalPss)
                    append(",\"restoredGeneration\":\"").append(result.restoredGeneration).append("\"}")
                },
            )
        } finally {
            target.close()
            staging.deleteRecursively()
            context.deleteDatabase(targetName)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun randomPayload(length: Int, random: java.util.Random): String {
        val chars = CharArray(length) { ('a'.code + random.nextInt(26)).toChar() }
        return String(chars)
    }

    /** ~25% incompressible + 75% repeated: DEFLATE ratio stays under the 100:1 ZIP rule. */
    private fun mixedPayload(length: Int, random: java.util.Random): String {
        val chars = CharArray(length) { index ->
            if (index and 3 == 0) ('a'.code + random.nextInt(26)).toChar() else 'x'
        }
        return String(chars)
    }
}

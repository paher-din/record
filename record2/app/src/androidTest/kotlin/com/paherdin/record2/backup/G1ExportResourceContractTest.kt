package com.paherdin.record2.backup

import android.database.sqlite.SQLiteDatabase
import android.os.Debug
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceSeeds
import com.paherdin.record2.storage.AiControlEntity
import com.paherdin.record2.storage.RecordDataStateEntity
import com.paherdin.record2.storage.RecordDatabase
import com.paherdin.record2.storage.toEntity
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opt-in real-Room export resource probe. Seeds the real Room schema directly (fast bulk SQL),
 * exports through BackupExporter.export(), and samples heap/WAL/staging/database/cache/free space
 * for the whole run. The first-row hook also proves a concurrent write after the snapshot starts
 * is excluded from the exported rows.
 *
 * `-e rows 500000 -e bodyChars 40 -e mode max -e concurrent true`
 * `-e rows 500000 -e bodyChars 40 -e mode notes`
 */
class G1ExportResourceContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = Clock.fixed(Instant.ofEpochMilli(System.currentTimeMillis()), ZoneOffset.UTC)

    @Test
    fun realDatabaseExportStaysWithinLimits() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val rows = args.getString("rows")?.toIntOrNull() ?: 100_000
        val bodyChars = args.getString("bodyChars")?.toIntOrNull() ?: 40
        val mode = args.getString("mode") ?: "notes"
        val concurrent = args.getString("concurrent") == "true"
        val now = System.currentTimeMillis()
        val base = now - 86_400_000L
        val end = now - 1L
        val databaseName = "g1-export-resource-${UUID.randomUUID()}.db"
        val staging = File(context.noBackupFilesDir, "g1-export-resource-${UUID.randomUUID()}")
        val zip = File(staging, "export.zip")
        val db = Room.databaseBuilder(context, RecordDatabase::class.java, databaseName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        db.openHelper.writableDatabase
        val path = context.getDatabasePath(databaseName)
        val gate = DataOperationGate()
        val sampler = ExportSampler(path, path.parentFile ?: path, staging, context.cacheDir)
        var concurrentId: String? = null
        var firstRowMs = -1L
        val phases = LinkedHashMap<String, Long>()
        try {
            val seeded = seed(
                db, rows, bodyChars, mode == "max", base, end,
                RandomPayload(java.util.Random(42L)),
            )
            assertEquals(rows.toLong(), seeded)
            Log.i("G1-EXPORT", "seeded rows=$seeded")
            checkpoint(db)
            Log.i("G1-EXPORT", "checkpointed")
            val exportStart = System.nanoTime()
            val walBaseline = File(path.path + "-wal").let { if (it.isFile) it.length() else 0L }
            val exporter = BackupExporter(
                path, staging, gate, AppInfo(1, "0.1.0"), clock,
                availableBytes = { FileSpace.availableBytes(it) },
                snapshotHook = {
                    firstRowMs = (System.nanoTime() - exportStart) / 1_000_000
                    Log.i("G1-EXPORT", "first row at ${firstRowMs}ms")
                    if (concurrent) {
                        concurrentId = insertConcurrentNote(db, base, end)
                    }
                },
                checkpoint = { point -> phases[point.name] = (System.nanoTime() - exportStart) / 1_000_000 },
            )
            sampler.start()
            val receipt = exporter.export(FileTarget(zip), zip.name)
            val exportMs = (System.nanoTime() - exportStart) / 1_000_000
            Log.i("G1-EXPORT", "export finished at ${exportMs}ms")
            val peaks = sampler.stop()
            assertTrue(firstRowMs >= 0)
            assertEquals(rows.toLong(), receipt.totalRows)
            assertTrue(receipt.zipBytes <= BackupLimits.MAX_ZIP_BYTES)
            assertTrue(receipt.totalUncompressedBytes <= BackupLimits.MAX_TOTAL_UNCOMPRESSED)
            val verified = LogicalZipReader.verify(zip)
            assertEquals(rows.toLong(), verified.manifest.totalRows)
            assertEquals(receipt.zipSha256, sha256(zip))
            val concurrentRows = if (concurrent) {
                assertNotNull(concurrentId)
                val count = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM note").use { cursor ->
                    cursor.moveToFirst(); cursor.getLong(0)
                }
                val noteCount = rows - 1 - FinanceSeeds.categories.size - if (mode == "max") 34 else 0
                assertEquals(noteCount.toLong() + 1, count)
                var found = false
                LogicalZipReader.forEachRow(zip) { segment, row ->
                    if (segment == LogicalSegment.NOTE && (row as NoteRow).id == concurrentId) found = true
                }
                assertFalse("concurrent write must be excluded from the snapshot", found)
                1
            } else {
                0
            }
            println(
                "G1-EXPORT-RESOURCE " + JSONObject()
                    .put("mode", mode)
                    .put("rows", receipt.totalRows)
                    .put("bodyChars", bodyChars)
                    .put("uncompressedBytes", receipt.totalUncompressedBytes)
                    .put("zipBytes", receipt.zipBytes)
                    .put("exportMs", exportMs)
                    .put("firstRowMs", firstRowMs)
                    .put("walBaselineBytes", walBaseline)
                    .put("peakWalBytes", peaks.peakWalBytes)
                    .put("peakWalGrowthBytes", maxOf(0L, peaks.peakWalBytes - walBaseline))
                    .put("peakPssKb", peaks.peakPssKb)
                    .put("peakStagingBytes", peaks.peakStagingBytes)
                    .put("peakDbFileBytes", peaks.peakDbFileBytes)
                    .put("peakTempBytes", peaks.peakTempBytes)
                    .put("peakCacheBytes", peaks.peakCacheBytes)
                    .put("minFreeBytes", peaks.minFreeBytes)
                    .put("concurrentRows", concurrentRows)
                    .put("phases", JSONObject(phases))
                    .put("zipSha256", receipt.zipSha256),
            )
        } finally {
            sampler.stop()
            db.close()
            context.deleteDatabase(databaseName)
            staging.deleteRecursively()
        }
    }

    private suspend fun seed(
        database: RecordDatabase,
        rows: Int,
        bodyChars: Int,
        max: Boolean,
        base: Long,
        end: Long,
        random: RandomPayload,
    ): Long {
        val fixed = 1 + FinanceSeeds.categories.size
        val largeRows = if (max) 34 else 0
        val noteCount = rows - fixed - largeRows
        require(noteCount > 17) { "rows must exceed the fixed seed and link samples" }
        database.withTransaction {
            val dao = database.backup()
            dao.insertAccount(
                AccountRow(FinanceSeeds.ACCOUNT_ID, "日常账户", "CNY", 1, base, end).toEntity(),
            )
            FinanceSeeds.categories.forEachIndexed { index, seed ->
                dao.insertCategory(
                    CategoryRow(seed.id, seed.name, seed.direction.name, 1, base + index, end).toEntity(),
                )
            }
            dao.insertControl(AiControlEntity(1, "OFF", null, null, null, null, null, 1))
            dao.insertDataState(
                RecordDataStateEntity(
                    1, "11111111-1111-4111-8111-111111111111", 1,
                    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", null,
                ),
            )
        }
        val raw = database.openHelper.writableDatabase
        raw.beginTransaction()
        try {
            val noteIds = ArrayList<String>(17)
            val noteStatement = raw.compileStatement(
                "INSERT INTO note (id, body, occurredAt, version, createdAt, updatedAt, deletedAt) " +
                    "VALUES (?, ?, ?, 1, ?, ?, NULL)",
            )
            repeat(noteCount) { index ->
                val id = UUID.randomUUID().toString()
                if (noteIds.size < 17) noteIds += id
                noteStatement.bindString(1, id)
                noteStatement.bindString(2, "n$index-" + random.mixed(bodyChars))
                noteStatement.bindLong(3, base + (index * 37L) % 86_400_000L)
                noteStatement.bindLong(4, base)
                noteStatement.bindLong(5, end)
                noteStatement.executeInsert()
            }
            if (max) {
                val expense = FinanceSeeds.categories.first { it.direction == FinanceDirection.EXPENSE }
                val transactionIds = ArrayList<String>(17)
                val transactionStatement = raw.compileStatement(
                    "INSERT INTO record_transaction " +
                        "(id, direction, amountMinor, currency, accountId, categoryId, memo, occurredAt, version, createdAt, updatedAt) " +
                        "VALUES (?, 'EXPENSE', 1, 'CNY', ?, ?, ?, ?, 1, ?, ?)",
                )
                repeat(17) { index ->
                    val id = UUID.randomUUID().toString()
                    transactionIds += id
                    transactionStatement.bindString(1, id)
                    transactionStatement.bindString(2, FinanceSeeds.ACCOUNT_ID)
                    transactionStatement.bindString(3, expense.id)
                    transactionStatement.bindString(4, "t$index-" + random.mixed(7_400_000))
                    transactionStatement.bindLong(5, base + index)
                    transactionStatement.bindLong(6, base)
                    transactionStatement.bindLong(7, end)
                    transactionStatement.executeInsert()
                }
                val linkStatement = raw.compileStatement(
                    "INSERT INTO transaction_note_link (transactionId, noteId) VALUES (?, ?)",
                )
                repeat(17) { index ->
                    linkStatement.bindString(1, transactionIds[index])
                    linkStatement.bindString(2, noteIds[index])
                    linkStatement.executeInsert()
                }
            }
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
        }
        return rows.toLong()
    }

    private fun insertConcurrentNote(database: RecordDatabase, base: Long, end: Long): String {
        val id = UUID.randomUUID().toString()
        val raw = database.openHelper.writableDatabase
        raw.beginTransaction()
        try {
            val statement = raw.compileStatement(
                "INSERT INTO note (id, body, occurredAt, version, createdAt, updatedAt, deletedAt) " +
                    "VALUES (?, ?, ?, 1, ?, ?, NULL)",
            )
            statement.bindString(1, id)
            statement.bindString(2, "concurrent-$id")
            statement.bindLong(3, base + 1)
            statement.bindLong(4, base)
            statement.bindLong(5, end)
            statement.executeInsert()
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
        }
        return id
    }

    private fun checkpoint(database: RecordDatabase) {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            it.moveToFirst()
        }
    }

    private class RandomPayload(private val random: java.util.Random) {
        fun mixed(length: Int): String {
            val chars = CharArray(length) { index ->
                if (index and 3 == 0) ('a'.code + random.nextInt(26)).toChar() else 'x'
            }
            return String(chars)
        }
    }

    private class FileTarget(private val file: File) : BackupTarget {
        override suspend fun writeFrom(source: File) {
            source.inputStream().use { input ->
                file.parentFile?.mkdirs()
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }

        override suspend fun readBackSha256(): String = sha256(file)
        override suspend fun readBackBytes(): Long = file.length()
    }

    private class ExportSampler(
        private val databasePath: File,
        private val databaseDirectory: File,
        private val stagingDirectory: File,
        private val cacheDirectory: File,
    ) {
        private val stopped = AtomicBoolean(false)
        @Volatile var peakPssKb: Long = 0
        @Volatile var peakWalBytes: Long = 0
        @Volatile var peakStagingBytes: Long = 0
        @Volatile var peakDbFileBytes: Long = 0
        @Volatile var peakTempBytes: Long = 0
        @Volatile var peakCacheBytes: Long = 0
        @Volatile var minFreeBytes: Long = Long.MAX_VALUE
        private val thread = Thread({
            val info = Debug.MemoryInfo()
            while (!stopped.get()) {
                Debug.getMemoryInfo(info)
                peakPssKb = maxOf(peakPssKb, info.totalPss.toLong())
                peakWalBytes = maxOf(
                    peakWalBytes,
                    File(databasePath.path + "-wal").let { if (it.isFile) it.length() else 0L },
                )
                peakStagingBytes = maxOf(peakStagingBytes, treeBytes(stagingDirectory))
                peakDbFileBytes = maxOf(
                    peakDbFileBytes,
                    databasePath.length() + File(databasePath.path + "-wal").let { if (it.isFile) it.length() else 0L } +
                        File(databasePath.path + "-shm").let { if (it.isFile) it.length() else 0L },
                )
                peakTempBytes = maxOf(peakTempBytes, tempBytes(databaseDirectory))
                peakCacheBytes = maxOf(peakCacheBytes, treeBytes(cacheDirectory))
                minFreeBytes = minOf(minFreeBytes, FileSpace.availableBytes(databaseDirectory))
                Thread.sleep(250)
            }
            Debug.getMemoryInfo(info)
            peakPssKb = maxOf(peakPssKb, info.totalPss.toLong())
        }, "g1-export-sampler").apply { isDaemon = true }

        fun start() = thread.start()

        fun stop(): Peaks {
            stopped.set(true)
            thread.join(5_000)
            return Peaks(
                peakPssKb, peakWalBytes, peakStagingBytes, peakDbFileBytes, peakTempBytes,
                peakCacheBytes, if (minFreeBytes == Long.MAX_VALUE) 0 else minFreeBytes,
            )
        }

        private fun treeBytes(root: File): Long {
            if (!root.isDirectory) return 0
            var total = 0L
            root.walkTopDown().forEach { if (it.isFile) total += it.length() }
            return total
        }

        private fun tempBytes(directory: File): Long =
            directory.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("etilqs_") }
                .sumOf { it.length() }
    }

    private data class Peaks(
        val peakPssKb: Long,
        val peakWalBytes: Long,
        val peakStagingBytes: Long,
        val peakDbFileBytes: Long,
        val peakTempBytes: Long,
        val peakCacheBytes: Long,
        val minFreeBytes: Long,
    )
}

private fun sha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65_536)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

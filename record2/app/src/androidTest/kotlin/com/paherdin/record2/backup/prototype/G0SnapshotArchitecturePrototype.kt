package com.paherdin.record2.backup.prototype

import android.os.Debug
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.StatFs
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.backup.*
import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceSeeds
import com.paherdin.record2.storage.RecordDatabase
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * THROWAWAY G0 architecture experiment, never a production exporter.
 * Current Room, mixed-engine, and unified-engine experiments run in separate instrumentation
 * invocations. Only unique synthetic Schema-v1 databases are opened. A mixed-engine PASS is
 * explicitly NOT evidence that POSIX lock coordination is safe for production.
 */
class G0SnapshotArchitecturePrototype {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val route get() = args.getString("route") ?: "room-current"
    private val rows get() = args.getString("rows")?.toInt() ?: 50_000
    private val large get() = args.getString("large") == "true"
    private val concurrent get() = args.getString("concurrent") != "false"
    private val seed = 42L
    private val base = 1_700_000_000_000L
    private val end = base + 86_400_000L
    private val generation = "11111111-1111-4111-8111-111111111111"

    @Test
    fun prepareFixtureForFreshProcess(): Unit = runBlocking {
        val fixture = fixture()
        // Persist only synthetic fixture/oracle data. The next instrumentation must have a new PID.
        fixture.db.useWriterConnection { c ->
            c.immediateTransaction {
                usePrepared("UPDATE note SET body='checkpoint-placeholder' WHERE id=?") {
                    it.bindText(1, id("note-0")); it.step()
                }
            }
        }
        fixture.db.close()
        val expected = JSONObject()
        fixture.expected.forEach { (segment, oracle) ->
            expected.put(segment.name, JSONObject().put("rows", oracle.rows).put("sha256", oracle.sha()))
        }
        val metadata = JSONObject().put("route", route).put("rows", rows).put("large", large).put("expected", expected)
        File(fixture.directory, "fixture.json").writeText(metadata.toString(), Charsets.UTF_8)
        val token = fixture.name.removePrefix("g0-export-prototype-").removeSuffix(".db")
        emitReport(JSONObject().put("route", route).put("runId", args.getString("runId"))
            .put("fixtureToken", token).put("preparePid", android.os.Process.myPid()))
    }

    @Test
    fun snapshotAndResourceExperiment(): Unit = runBlocking {
        val fixture = fixture()
        val report = JSONObject().put("route", route).put("fixtureSeed", seed)
            .put("runId", args.getString("runId"))
            .put("exportPid", android.os.Process.myPid()).put("freshFixture", args.getString("fixtureToken") != null)
            .put("writePattern", if (args.getString("bulkWrite") == "true") "rewrite-17-large-memos" else "normal-three-records")
            .put("rows", rows).put("large", large).put("concurrent", concurrent)
        val sampler = Sampler(fixture.path, fixture.directory)
        val executor = Executors.newSingleThreadExecutor()
        var writer = executor.submit<Boolean> { true }
        var writerCommitted = !concurrent
        var totalRows = 0L
        var totalBytes = 0L
        var sources: List<SegmentSource> = emptyList()
        var failure: Throwable? = null
        val start = System.nanoTime()
        sampler.start()
        try {
            val physicalSnapshot = if (route == "vacuum-system") {
                createVacuumSnapshot(fixture, report, cancel = false).also {
                    writerCommitted = !concurrent || report.optBoolean("copyWriterCommittedWhileCopyActive")
                    if (concurrent) check(writerCommitted) { "writer did not commit during physical snapshot creation" }
                }
            } else null
            readTransaction(fixture, physicalSnapshot) { query ->
                engineReport(query, report)
                if (physicalSnapshot != null) {
                    query("PRAGMA integrity_check", null) { assertTrue(it.step()); assertEquals("ok", it.getText(0)) }
                    query("PRAGMA foreign_key_check", null) { assertFalse(it.step()) }
                    query("PRAGMA user_version", null) { assertTrue(it.step()); assertEquals(1L, it.getLong(0)) }
                    query("SELECT identity_hash FROM room_master_table WHERE id=42", null) {
                        assertTrue(it.step()); assertEquals("c1b84c0a7822f354324484b6995cfb0d", it.getText(0))
                    }
                    report.put("snapshotIntegrity", "ok").put("snapshotSchemaIdentity", "c1b84c0a7822f354324484b6995cfb0d")
                }
                query("SELECT dataGeneration, businessRevision FROM record_data_state WHERE id=1", null) {
                    assertTrue(it.step())
                    assertEquals(generation, it.getText(0))
                    assertEquals(1L, it.getLong(1))
                }
                report.put("snapshotOpenMs", elapsed(start))
                if (concurrent && route != "vacuum-system") {
                    writer = executor.submit<Boolean> {
                        runBlocking { mutate(fixture.db) }
                        true
                    }
                    try {
                        writerCommitted = writer.get(3, TimeUnit.SECONDS)
                    } catch (_: TimeoutException) {
                        writerCommitted = false
                    }
                    report.put("writerCommittedWhileSnapshotOpen", writerCommitted)
                    check(writerCommitted) { "normal business writer blocked by the proposed read transaction" }
                }
                val output = ArrayList<SegmentSource>()
                val keyStages = JSONObject()
                report.put("keyStages", keyStages)
                for (segment in LogicalSegment.entries) {
                    val keyStage = JSONObject().put("startMs", elapsed(start)).put("baselinePssKb", sampler.sample())
                    keyStages.put(segment.name, keyStage)
                    sampler.beginStage()
                    val keyFile = File(fixture.directory, "${segment.ordinal}.keys")
                    DataOutputStream(BufferedOutputStream(keyFile.outputStream(), 65_536)).use { out ->
                        query("SELECT rowid FROM ${table(segment)}${filter(segment)} ORDER BY ${order(segment)}", null) { s ->
                            var keyCount = 0
                            while (s.step()) {
                                if (keyCount == 0) {
                                    keyStage.put("firstKeyMs", elapsed(start)).put("firstKeyPssKb", sampler.sample())
                                        .put("peakBeforeFirstKeyKb", sampler.stagePeak())
                                }
                                if (!report.has("firstKeyMs")) {
                                    report.put("firstKeyMs", elapsed(start))
                                    report.put("firstKeyPssKb", sampler.sample())
                                }
                                check(++keyCount <= BackupLimits.MAX_ROWS_TOTAL)
                                out.writeLong(s.getLong(0))
                            }
                        }
                    }
                    keyStage.put("endMs", elapsed(start)).put("peakKeyPssKb", sampler.stagePeak())
                    val target = File(fixture.directory, "${segment.ordinal}.jsonl")
                    SegmentFileWriter(segment, target).use { out ->
                        DataInputStream(keyFile.inputStream().buffered()).use { keys ->
                            repeat((keyFile.length() / 8).toInt()) {
                                val rowid = keys.readLong()
                                query("SELECT ${json(segment)} FROM ${table(segment)} WHERE rowid=?", rowid) { s ->
                                    assertTrue(s.step())
                                    val bytes = s.getText(0).toByteArray(Charsets.UTF_8)
                                    check(bytes.size <= BackupLimits.MAX_LINE_BYTES)
                                    out.append(SegmentCodecs.decode(segment, StrictJson.decodeObject(bytes, BackupLimits.MAX_LINE_BYTES)))
                                    assertFalse(s.step())
                                }
                            }
                        }
                        val source = out.finish()
                        val oracle = fixture.expected.getValue(segment)
                        assertEquals("${segment.name}: independent fixture rows", oracle.rows, source.rows)
                        assertEquals("${segment.name}: all-field canonical hash", oracle.sha(), source.sha256)
                        totalRows += source.rows
                        totalBytes += source.uncompressedBytes
                        output += source
                    }
                    Log.i("G0-PROTOTYPE", "$route ${segment.name} complete at ${elapsed(start)}ms")
                }
                query("SELECT businessRevision FROM record_data_state WHERE id=1", null) {
                    assertTrue(it.step()); assertEquals(1L, it.getLong(0))
                }
                sources = output
            }
            report.put("snapshotMs", elapsed(start))
            assertEquals(rows.toLong(), totalRows)
            check(totalBytes <= BackupLimits.MAX_TOTAL_UNCOMPRESSED)
            val manifest = manifest(totalRows, totalBytes, sources)
            val zip = File(fixture.directory, "prototype.zip")
            LogicalZipWriter.write(zip, manifest, sources)
            report.put("zipWrittenMs", elapsed(start))
            assertEquals(manifest, LogicalZipReader.verify(zip).manifest)
            report.put("zipBytes", zip.length()).put("zipSha256", hashFile(zip))
                .put("uncompressedBytes", totalBytes).put("contentDigest", manifest.contentDigest)
            assertTrue(zip.length() <= BackupLimits.MAX_ZIP_BYTES)
            assertTrue(writer.get(5, TimeUnit.SECONDS))
            fixture.db.useReaderConnection { connection ->
                connection.usePrepared("SELECT businessRevision FROM record_data_state WHERE id=1") {
                    assertTrue(it.step()); assertEquals(if (concurrent) 2L else 1L, it.getLong(0))
                }
            }
        } catch (error: Throwable) {
            failure = error
            report.put("errorClass", error.javaClass.name).put("error", error.message)
        } finally {
            report.put("elapsedMs", elapsed(start)).put("rowsExported", totalRows)
                .put("writerCommittedWhileSnapshotOpen", writerCommitted)
            sampler.stop(report)
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            fixture.db.close()
            context.deleteDatabase(fixture.name)
            fixture.directory.deleteRecursively()
            report.put("verdict", if (failure == null) "PASS" else "FAIL")
            emitReport(report)
        }
        failure?.let { throw it }
    }

    @Test
    fun vacuumCancellationLeavesSourceWritable(): Unit = verifyVacuumAbort(resourceFault = false)

    @Test
    fun vacuumResourceAbortLeavesSourceWritable(): Unit = verifyVacuumAbort(resourceFault = true)

    private fun verifyVacuumAbort(resourceFault: Boolean): Unit = runBlocking {
        val fixture = fixture()
        val report = JSONObject().put("route", route).put("runId", args.getString("runId"))
        try {
            val before = frameworkFactsHash(fixture.path)
            var interrupted = false
            try {
                createVacuumSnapshot(fixture, report, cancel = true, resourceFault = resourceFault)
            } catch (error: OperationCanceledException) {
                interrupted = true
                report.put("cancelError", error.javaClass.name)
            }
            assertTrue("CancellationSignal must actually interrupt the native operation", interrupted)
            val after = frameworkFactsHash(fixture.path)
            assertEquals("cancelled physical snapshot must preserve every field of all 14 source tables", before, after)
            report.put("sourceFactsBefore", before).put("sourceFactsAfter", after)
            fixture.db.useReaderConnection { c ->
                c.usePrepared("PRAGMA integrity_check") { assertTrue(it.step()); assertEquals("ok", it.getText(0)) }
                c.usePrepared("SELECT businessRevision FROM record_data_state") { assertTrue(it.step()); assertEquals(1L, it.getLong(0)) }
            }
            mutate(fixture.db)
            fixture.db.useReaderConnection { c ->
                c.usePrepared("SELECT businessRevision FROM record_data_state") { assertTrue(it.step()); assertEquals(2L, it.getLong(0)) }
            }
            report.put("cancelled", true).put("sourceIntegrity", "ok").put("writerAfterCancel", true)
        } finally {
            emitReport(report)
            fixture.db.close()
            context.deleteDatabase(fixture.name)
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun corruptSourceIsRejectedWithoutDeletingIt(): Unit = runBlocking {
        val fixture = fixture(small = true)
        fixture.db.close()
        try {
            java.io.RandomAccessFile(fixture.path, "rw").use {
                it.seek(0); it.write("not-a-sqlite-db!".toByteArray(Charsets.US_ASCII))
            }
            val before = hashFile(fixture.path)
            var refused = false
            try {
                openProtectedSource(fixture.path).use {
                    it.rawQuery("PRAGMA user_version", null).use { cursor -> cursor.moveToFirst() }
                }
            } catch (_: SQLiteDatabaseCorruptException) {
                refused = true
            }
            assertTrue("corrupt source must fail closed", refused)
            assertTrue("default Android corruption handler must not delete source", fixture.path.isFile)
            assertEquals(before, hashFile(fixture.path))
            emitReport(JSONObject().put("route", route).put("runId", args.getString("runId"))
                .put("corruptSourceRefused", true).put("sourceStillExists", true).put("sourceBytesUnchanged", true))
        } finally {
            context.deleteDatabase(fixture.name)
            fixture.directory.deleteRecursively()
        }
    }

    private fun createVacuumSnapshot(fixture: Fixture, report: JSONObject, cancel: Boolean, resourceFault: Boolean = false): File {
        val target = File(fixture.directory, "consistent-private-snapshot.db")
        val signal = CancellationSignal()
        val active = AtomicBoolean(true)
        val actionAt = AtomicLong(-1)
        val resourceAbort = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val start = System.nanoTime()
        val observer = executor.submit<Boolean> {
            while (active.get() && target.length() < 65_536) Thread.sleep(1)
            if (!active.get()) return@submit false
            actionAt.set(elapsed(start))
            if (cancel) {
                if (resourceFault) {
                    // The real monitor rejects injected low free space while VACUUM is still native.
                    try {
                        FileSystemExportResourceMonitor(fixture.path, fixture.directory, freeSpace = { 0L }).check()
                    } catch (error: BackupException) {
                        check(error.code == BackupError.StorageFull)
                        resourceAbort.set(true)
                    }
                    check(resourceAbort.get())
                }
                signal.cancel()
                true
            } else if (concurrent) {
                runBlocking { mutate(fixture.db) }
                active.get()
            } else true
        }
        try {
            openProtectedSource(fixture.path).use { source ->
                assertTrue(source.isReadOnly)
                source.rawQuery("SELECT sqlite_version(), sqlite_source_id()", null).use {
                    assertTrue(it.moveToFirst()); report.put("sourceSqliteVersion", it.getString(0)).put("sourceSqliteSourceId", it.getString(1))
                }
                source.rawQuery("PRAGMA journal_mode", null).use { assertTrue(it.moveToFirst()); assertEquals("wal", it.getString(0)) }
                // Source is OS READONLY. Destination writing is intentional in this G0 C prototype;
                // query_only is not enabled because it also prohibits writing the private destination.
                source.rawQuery("VACUUM main INTO ?", arrayOf(target.absolutePath), signal).use { cursor ->
                    assertEquals(0, cursor.count)
                }
            }
        } finally {
            active.set(false)
            val copyMs = elapsed(start)
            report.put("copyMs", copyMs).put("snapshotDbBytes", target.length())
                .put("copyActionAtMs", actionAt.get())
                .put("resourceMonitorAborted", resourceAbort.get())
            try {
                report.put("copyWriterCommittedWhileCopyActive", observer.get(10, TimeUnit.SECONDS))
            } finally {
                executor.shutdown()
                executor.awaitTermination(30, TimeUnit.SECONDS)
            }
        }
        assertTrue(target.isFile)
        // Read a raw header only before any SQLite connection opens this static target.
        target.inputStream().use { input ->
            val header = ByteArray(100); assertEquals(100, input.read(header))
            report.put("snapshotHeaderReadVersion", header[19].toInt()).put("snapshotHeaderWriteVersion", header[18].toInt())
        }
        return target
    }

    @Test
    fun readerActuallyRejectsWrites(): Unit = runBlocking {
        val fixture = fixture(small = true)
        var rejected = false
        var writeError: String? = null
        try {
            readTransaction(fixture) { query ->
                try {
                    query("UPDATE account SET name=name", null) { it.step() }
                } catch (error: SQLiteException) {
                    writeError = error.message
                    // BUSY/LOCKED is not write protection: it can disappear without any policy change.
                    rejected = error.message.orEmpty().contains("readonly", ignoreCase = true) ||
                        error.message.orEmpty().contains("read-only", ignoreCase = true)
                    println("G0-READONLY route=$route rejected=$rejected error=${error.message}")
                }
            }
            println("G0-READONLY route=$route rejected=$rejected")
            emitReport(JSONObject().put("route", route).put("runId", args.getString("runId"))
                .put("writeRejectedAsReadOnly", rejected).put("writeError", writeError))
            assertTrue("reader connection must reject SQL writes", rejected)
        } finally {
            fixture.db.close()
            context.deleteDatabase(fixture.name)
            fixture.directory.deleteRecursively()
        }
    }

    private suspend fun readTransaction(fixture: Fixture, physicalSnapshot: File? = null, action: suspend (Query) -> Unit) {
        if (route.startsWith("room-")) {
            fixture.db.useReaderConnection { connection ->
                connection.deferredTransaction {
                    action { sql, bind, block ->
                        usePrepared(sql) { statement ->
                            if (bind != null) statement.bindLong(1, bind)
                            block(statement)
                        }
                    }
                }
            }
        } else {
            // C reads the static destination with bundled, NEVER the live framework-owned file.
            BundledSQLiteDriver().open((physicalSnapshot ?: fixture.path).absolutePath, SQLITE_OPEN_READONLY).use { reader ->
                execute(reader, "PRAGMA query_only=ON")
                execute(reader, "PRAGMA temp_store=FILE")
                execute(reader, "PRAGMA cache_size=-2048")
                execute(reader, "BEGIN DEFERRED")
                val statements = HashMap<String, SQLiteStatement>()
                try {
                    action { sql, bind, block ->
                        val statement = statements.getOrPut(sql) { reader.prepare(sql) }
                        statement.reset()
                        statement.clearBindings()
                        if (bind != null) statement.bindLong(1, bind)
                        block(statement)
                    }
                    statements.values.forEach { it.close() }
                    statements.clear()
                    execute(reader, "COMMIT")
                } finally {
                    statements.values.forEach { it.close() }
                }
            }
        }
    }

    private suspend fun engineReport(query: Query, report: JSONObject) {
        query("SELECT sqlite_version(), sqlite_source_id()", null) {
            assertTrue(it.step()); report.put("sqliteVersion", it.getText(0)).put("sqliteSourceId", it.getText(1))
        }
        query("PRAGMA journal_mode", null) { assertTrue(it.step()); report.put("readerJournalMode", it.getText(0)) }
        query("PRAGMA compile_options", null) { s ->
            val options = ArrayList<String>()
            while (s.step()) {
                val value = s.getText(0)
                if (value.startsWith("THREADSAFE") || value.startsWith("TEMP_STORE") || value.contains("SNAPSHOT")) options += value
            }
            report.put("compileOptions", options.joinToString(","))
        }
    }

    private suspend fun fixture(small: Boolean = false): Fixture {
        val preparedToken = args.getString("fixtureToken")
        if (preparedToken != null) {
            require(UUID.fromString(preparedToken).toString() == preparedToken)
            val name = "g0-export-prototype-$preparedToken.db"
            val directory = File(context.noBackupFilesDir, "g0-export-prototype-$preparedToken")
            val metadata = JSONObject(File(directory, "fixture.json").readText(Charsets.UTF_8))
            check(metadata.getString("route") == route && metadata.getInt("rows") == rows && metadata.getBoolean("large") == large)
            val frozen = metadata.getJSONObject("expected")
            val expected = LogicalSegment.entries.associateWith { s ->
                val item = frozen.getJSONObject(s.name)
                Oracle(item.getString("sha256"), item.getLong("rows"))
            }
            val db = buildDatabase(name)
            writeWalValue(db)
            return Fixture(db, name, context.getDatabasePath(name), directory, expected)
        }
        val name = "g0-export-prototype-${UUID.randomUUID()}.db"
        val builder = Room.databaseBuilder(context, RecordDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        // native-unified and room-unified use the SAME bundled engine for every connection.
        if (route.endsWith("unified")) builder.setDriver(BundledSQLiteDriver())
        val db = builder.build()
        val directory = File(context.noBackupFilesDir, name.removeSuffix(".db"))
        check(directory.mkdirs())
        val count = if (small) 50 else rows
        val max = !small && large
        val expected = LogicalSegment.entries.associateWith { Oracle() }
        val noteCount = count - 1 - FinanceSeeds.categories.size - if (max) 34 else 0
        require(noteCount > 17)
        db.useWriterConnection { connection ->
            connection.immediateTransaction {
                suspend fun insert(segment: LogicalSegment, row: SegmentRow, values: (SQLiteStatement) -> Unit) {
                    val fields = segment.fields.joinToString(",")
                    val slots = segment.fields.joinToString(",") { "?" }
                    usePrepared("INSERT INTO ${table(segment)} ($fields) VALUES ($slots)") { s -> values(s); s.step() }
                    expected.getValue(segment).add(row)
                }
                val account = AccountRow(FinanceSeeds.ACCOUNT_ID, "日常账户", "CNY", 1, base, end)
                insert(LogicalSegment.ACCOUNT, account) { s ->
                    s.bindText(1, account.id); s.bindText(2, account.name); s.bindText(3, account.currency)
                    s.bindLong(4, 1); s.bindLong(5, base); s.bindLong(6, end)
                }
                FinanceSeeds.categories.sortedBy { it.id }.forEach { category ->
                    val row = CategoryRow(category.id, category.name, category.direction.name, 1, base, end)
                    insert(LogicalSegment.CATEGORY, row) { s ->
                        s.bindText(1, row.id); s.bindText(2, row.name); s.bindText(3, row.direction)
                        s.bindLong(4, 1); s.bindLong(5, base); s.bindLong(6, end)
                    }
                }
                usePrepared("INSERT INTO record_data_state VALUES (1, ?, 1, ?, NULL)") {
                    it.bindText(1, generation); it.bindText(2, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"); it.step()
                }
                usePrepared("INSERT INTO ai_control VALUES (1, 'OFF', NULL, NULL, NULL, NULL, NULL, 1)") { it.step() }
                usePrepared("INSERT INTO note VALUES (?, ?, ?, 1, ?, ?, ?)") { s ->
                    val random = java.util.Random(seed)
                    repeat(noteCount) { index ->
                        val id = id("note-$index")
                        val body = "n$index-" + mixed(random, 40)
                        val deletedAt = if (index % 31 == 0) end else null
                        val row = NoteRow(id, body, base + index, 1, base, end, deletedAt)
                        s.reset(); s.clearBindings()
                        s.bindText(1, id); s.bindText(2, if (index == 0) "checkpoint-placeholder" else body); s.bindLong(3, row.occurredAt)
                        s.bindLong(4, base); s.bindLong(5, end)
                        if (deletedAt == null) s.bindNull(6) else s.bindLong(6, deletedAt)
                        s.step()
                        expected.getValue(LogicalSegment.NOTE).add(row)
                    }
                }
                if (max) {
                    val expense = FinanceSeeds.categories.first { it.direction == FinanceDirection.EXPENSE }
                    val links = ArrayList<LinkRow>()
                    usePrepared("INSERT INTO record_transaction VALUES (?, 'EXPENSE', 1, 'CNY', ?, ?, ?, ?, 1, ?, ?)") { s ->
                        val random = java.util.Random(seed + 1)
                        repeat(17) { index ->
                            val id = id("transaction-$index")
                            val memo = "t$index-" + mixed(random, 7_400_000)
                            val row = TransactionRow(id, "EXPENSE", 1, "CNY", account.id, expense.id, memo, base + index, 1, base, end)
                            s.reset(); s.clearBindings()
                            s.bindText(1, id); s.bindText(2, account.id); s.bindText(3, expense.id)
                            s.bindText(4, memo); s.bindLong(5, row.occurredAt); s.bindLong(6, base); s.bindLong(7, end)
                            s.step()
                            expected.getValue(LogicalSegment.TRANSACTION).add(row)
                            links += LinkRow(id, id("note-$index"))
                        }
                    }
                    usePrepared("INSERT INTO transaction_note_link VALUES (?, ?)") { s ->
                        links.sortedWith(compareBy({ it.transactionId }, { it.noteId })).forEach { link ->
                            s.reset(); s.clearBindings(); s.bindText(1, link.transactionId); s.bindText(2, link.noteId); s.step()
                            expected.getValue(LogicalSegment.LINK).add(link)
                        }
                    }
                }
            }
        }
        Log.i("G0-PROTOTYPE", "seeded route=$route rows=$count large=$max")
        // Drop the seeding statement caches and large temporary strings before measuring export.
        // This is fixture setup, not an export optimization or production GC requirement.
        db.close()
        val reopened = buildDatabase(name)
        // A required logical value exists only in a fresh WAL commit, after closing/checkpointing
        // the initial seeder. Export must see it; reading only the main DB file cannot pass the oracle.
        writeWalValue(reopened)
        System.gc()
        return Fixture(reopened, name, context.getDatabasePath(name), directory, expected)
    }

    private fun buildDatabase(name: String) = Room.databaseBuilder(context, RecordDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .also { if (route.endsWith("unified")) it.setDriver(BundledSQLiteDriver()) }.build()

    private suspend fun writeWalValue(db: RecordDatabase) {
        db.useWriterConnection { c ->
            c.immediateTransaction {
                usePrepared("UPDATE note SET body=? WHERE id=?") {
                    it.bindText(1, "n0-" + mixed(java.util.Random(seed), 40)); it.bindText(2, id("note-0")); it.step()
                }
            }
        }
    }

    private suspend fun mutate(db: RecordDatabase) {
        db.useWriterConnection { connection ->
            connection.immediateTransaction {
                usePrepared("UPDATE account SET name='after-snapshot', version=2") { it.step() }
                usePrepared("UPDATE note SET body='after-snapshot', occurredAt=?, version=2 WHERE id=?") {
                    it.bindLong(1, end - 1); it.bindText(2, id("note-0")); it.step()
                }
                usePrepared("DELETE FROM note WHERE id=?") { it.bindText(1, id("note-100")); it.step() }
                usePrepared("INSERT INTO note VALUES (?, 'new-concurrent', ?, 1, ?, ?, NULL)") {
                    it.bindText(1, id("concurrent")); it.bindLong(2, base); it.bindLong(3, base); it.bindLong(4, end); it.step()
                }
                if (args.getString("bulkWrite") == "true") {
                    usePrepared("UPDATE record_transaction SET memo='after-snapshot', version=2") { it.step() }
                } else {
                    usePrepared("UPDATE record_transaction SET version=2 WHERE id=?") {
                        it.bindText(1, id("transaction-0")); it.step()
                    }
                }
                usePrepared("UPDATE record_data_state SET businessRevision=2") { it.step() }
            }
        }
    }

    private fun manifest(rows: Long, bytes: Long, sources: List<SegmentSource>): BackupManifest {
        val value = BackupManifest(BackupLimits.FORMAT, 1, 1, end, generation, 1, 1, "prototype",
            BackupLimits.ENTRY_COUNT, rows, bytes, sources.map { ManifestSegment(it.segment.path, it.rows, it.uncompressedBytes, it.sha256) }, "0".repeat(64))
        return value.copy(contentDigest = ManifestCodec.contentDigest(value))
    }

    private data class Fixture(val db: RecordDatabase, val name: String, val path: File, val directory: File, val expected: Map<LogicalSegment, Oracle>)
    private class Oracle(private val frozenSha: String? = null, initialRows: Long = 0) {
        private val digest = MessageDigest.getInstance("SHA-256")
        var rows = initialRows
        fun add(row: SegmentRow) {
            digest.update(SegmentCodecs.encode(row).toByteArray(Charsets.UTF_8)); digest.update('\n'.code.toByte()); rows++
        }
        fun sha() = frozenSha ?: digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private class Sampler(private val path: File, private val directory: File) {
        private val stopped = AtomicBoolean(false)
        private val baselineWal = File(path.path + "-wal").length()
        private val info = Debug.MemoryInfo()
        private var baselinePss = 0L
        private var peakPss = 0L
        private var peakJava = 0L
        private var peakNative = 0L
        private var peakWal = 0L
        private var peakStaging = 0L
        private var peakDatabase = 0L
        private var peakVisibleSqlTemp = 0L
        private var minFree = Long.MAX_VALUE
        private var stagePeakPss = 0L
        private val worker = Thread({
            while (!stopped.get()) { sample(); Thread.sleep(100) }
        }, "g0-prototype-sampler")
        @Synchronized fun sample(): Long {
            Debug.getMemoryInfo(info)
            val pss = info.totalPss.toLong()
            peakPss = maxOf(peakPss, pss)
            stagePeakPss = maxOf(stagePeakPss, pss)
            val runtime = Runtime.getRuntime()
            peakJava = maxOf(peakJava, runtime.totalMemory() - runtime.freeMemory())
            peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
            peakWal = maxOf(peakWal, File(path.path + "-wal").length())
            peakDatabase = maxOf(peakDatabase, path.length() + File(path.path + "-wal").length() + File(path.path + "-shm").length())
            peakVisibleSqlTemp = maxOf(peakVisibleSqlTemp,
                path.parentFile?.listFiles().orEmpty().filter { it.name.startsWith("etilqs_") && it.isFile }.sumOf { it.length() })
            peakStaging = maxOf(peakStaging, directory.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            minFree = minOf(minFree, StatFs(directory.absolutePath).availableBytes)
            return pss
        }
        fun start() { baselinePss = sample(); worker.start() }
        @Synchronized fun beginStage() { stagePeakPss = sample() }
        @Synchronized fun stagePeak() = stagePeakPss
        fun stop(report: JSONObject) {
            stopped.set(true); worker.join(2_000); sample()
            report.put("baselinePssKb", baselinePss).put("peakPssKb", peakPss)
                .put("pssIncreaseKb", maxOf(0L, peakPss - baselinePss))
                .put("peakJavaHeapBytes", peakJava).put("peakNativeHeapBytes", peakNative)
                .put("walBaselineBytes", baselineWal).put("peakWalBytes", peakWal)
                .put("walGrowthBytes", maxOf(0L, peakWal - baselineWal))
                .put("peakStagingBytes", peakStaging).put("peakDbWalShmBytes", peakDatabase)
                .put("peakVisibleSqlTempBytes", peakVisibleSqlTemp)
                .put("sampleIntervalMs", 100).put("minFreeBytes", minFree)
        }
    }

    private fun id(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
    private fun emitReport(report: JSONObject) {
        val runId = args.getString("runId") ?: return
        check(Regex("[a-f0-9]{32}(-prepare)?").matches(runId))
        val directory = File(context.noBackupFilesDir, "g0-prototype-reports")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$runId.json").writeText(report.toString(), Charsets.UTF_8)
        println("G0-PROTOTYPE $report")
    }
    private fun mixed(random: java.util.Random, length: Int) = String(CharArray(length) { i -> if (i and 3 == 0) ('a'.code + random.nextInt(26)).toChar() else 'x' })
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000
    private fun execute(connection: SQLiteConnection, sql: String) = connection.prepare(sql).use { it.step(); Unit }
    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65_536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun frameworkFactsHash(path: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openProtectedSource(path).use { source ->
            val tables = LogicalSegment.entries.map { table(it) } + listOf("record_data_state", "ai_control")
            for (table in tables) {
                val fields = ArrayList<String>()
                source.rawQuery("PRAGMA table_info($table)", null).use { while (it.moveToNext()) fields += it.getString(1) }
                digest.update(table.toByteArray(Charsets.UTF_8))
                source.compileStatement("SELECT json_object(${fields.joinToString(",") { "'$it',$it" }}) FROM $table WHERE rowid=?").use { row ->
                    source.rawQuery("SELECT rowid FROM $table ORDER BY rowid", null).use { keys ->
                        while (keys.moveToNext()) {
                            row.bindLong(1, keys.getLong(0))
                            digest.update(row.simpleQueryForString().toByteArray(Charsets.UTF_8))
                            digest.update('\n'.code.toByte())
                        }
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun openProtectedSource(path: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        path.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
    ) { throw SQLiteDatabaseCorruptException("prototype source rejected; automatic deletion disabled") }
    private fun json(s: LogicalSegment) = "json_object(${s.fields.joinToString(",") { "'$it',$it" }})"
    private fun table(s: LogicalSegment) = s.path.substringAfterLast('/').substringBefore('.')
        .let { if (s == LogicalSegment.DRAFT) "native_finance_draft" else if (s == LogicalSegment.PROPOSAL) "change_proposal" else if (s == LogicalSegment.OPERATION) "commit_operation" else it }
    private fun filter(s: LogicalSegment) = if (s == LogicalSegment.DRAFT) " WHERE state='EDITING'" else ""
    private fun order(s: LogicalSegment): String = when (s) {
        LogicalSegment.ACCOUNT, LogicalSegment.CATEGORY -> "id"
        LogicalSegment.TRANSACTION, LogicalSegment.NOTE -> "occurredAt,id"
        LogicalSegment.LINK -> "transactionId,noteId"
        LogicalSegment.DRAFT -> "slot"
        LogicalSegment.PROPOSAL, LogicalSegment.OPERATION, LogicalSegment.SESSION -> "createdAt,id"
        LogicalSegment.RUN -> "startedAt,id"
        LogicalSegment.MESSAGE -> "sessionId,sequence"
        LogicalSegment.REFERENCE -> "messageId,ordinal"
    }
}

private typealias Query = suspend (String, Long?, (SQLiteStatement) -> Unit) -> Unit

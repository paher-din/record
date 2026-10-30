package com.paherdin.record2.backup.prototype

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteStatement as FrameworkStatement
import android.os.Debug
import android.os.StatFs
import androidx.room.PooledConnection
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.backup.*
import com.paherdin.record2.storage.RecordDatabase
import com.paherdin.record2.storage.RoomRestoreStore
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Research only. Shares the previous deterministic scratch fixture, never production wiring. */
class LightweightZipPrototype {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val route get() = args.getString("route") ?: "vacuum-system"
    private val token get() = args.getString("fixtureToken")!!.also { require(UUID.fromString(it).toString() == it) }
    private val root get() = File(context.noBackupFilesDir, "g0-export-prototype-$token")
    private val name get() = "g0-export-prototype-$token.db"
    private val path get() = context.getDatabasePath(name)
    private val unified get() = route == "native-unified"
    private val base = 1_700_000_000_000L

    @Test fun streamingRoundTrip(): Unit = execute(fault = null)
    @Test fun cancellationLeavesSourceUnchanged(): Unit = execute(fault = "cancel")
    @Test fun spaceFailureLeavesSourceUnchanged(): Unit = execute(fault = "space")
    @Test fun holdForForceStop(): Unit = execute(fault = "kill")

    @Test fun managedReaderRejectsWrites(): Unit = runBlocking {
        check(unified)
        val db=database(name)
        try {
            var refused=false
            db.useReaderConnection { c->c.deferredTransaction {
                try { usePrepared("UPDATE account SET name=name") { it.step() } }
                catch(e:androidx.sqlite.SQLiteException) { refused=e.message.orEmpty().contains("readonly",true) }
            } }
            assertTrue("managed native reader must enforce query_only",refused)
            emit(JSONObject().put("managedReaderWriteRejected",true))
        } finally {db.close();context.deleteDatabase(name);root.deleteRecursively()}
    }

    @Test fun verifyAfterForceStop(): Unit = runBlocking {
        val marker = JSONObject(File(root, "kill-marker.json").readText())
        val db = database(name)
        try {
            assertEquals(marker.getString("facts"), facts(db))
            assertFalse(File(root, "success.json").exists())
            assertTrue(runCatching { LogicalZipReader.verify(File(root, "lightweight.zip")) }.isFailure)
            mutate(db)
            emit(JSONObject().put("sourceFactsUnchanged", true).put("partialZipRejected", true).put("writerAfterKill", true))
        } finally { db.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }

    private fun execute(fault: String?): Unit = runBlocking {
        val metadata = JSONObject(File(root, "fixture.json").readText())
        val db = database(name)
        // This required value is written into WAL after fixture checkpoint/old-process death.
        // Replay Random(42) once rather than deriving each position independently.
        val random = java.util.Random(42)
        val highRatio = args.getString("highRatio") == "true"
        val walBody = if (highRatio) "x".repeat(7_000_000) else "n0-" + String(CharArray(40) { i -> if (i and 3 == 0) ('a'.code + random.nextInt(26)).toChar() else 'x' })
        if (highRatio) {
            val oracle = MessageDigest.getInstance("SHA-256"); val r = java.util.Random(42)
            repeat(metadata.getJSONObject("expected").getJSONObject("NOTE").getInt("rows")) { index ->
                val ordinary = "n$index-" + String(CharArray(40) { i -> if (i and 3 == 0) ('a'.code + r.nextInt(26)).toChar() else 'x' })
                val row = NoteRow(id("note-$index"), if(index==0)walBody else ordinary, base+index,1,base,base+86_400_000,if(index%31==0)base+86_400_000 else null)
                oracle.update((SegmentCodecs.encode(row)+"\n").toByteArray(Charsets.UTF_8))
            }
            metadata.getJSONObject("expected").getJSONObject("NOTE").put("sha256",hex(oracle.digest()))
        }
        db.useWriterConnection { c -> c.immediateTransaction {
            usePrepared("UPDATE note SET body=? WHERE id=?") { it.bindText(1, walBody); it.bindText(2, id("note-0")); it.step() }
        } }
        val before = if (fault != null) facts(db) else null
        val report = JSONObject().put("route", route).put("pid", android.os.Process.myPid())
            .put("packing", args.getString("packing") ?: "size-first")
            .put("highRatio", highRatio)
            .put("rows", metadata.getInt("rows")).put("large", metadata.getBoolean("large"))
            .put("fault", fault ?: "none").put("sourceDbBytes", path.length())
        val sampler = Sampler(path, root)
        val start = System.nanoTime()
        val zip = File(root, "lightweight.zip")
        val executor = Executors.newSingleThreadExecutor()
        var completed = false
        var error: Throwable? = null
        sampler.start()
        try {
            suspend fun capture(reader: Access) {
                val state = reader.state()
                report.put("sqliteConfig",reader.config())
                assertEquals(1L, state.second)
                if (fault == null && args.getString("concurrent") != "false") {
                    val writer = executor.submit<Boolean> { runBlocking { mutate(db) }; true }
                    assertTrue(writer.get(3, TimeUnit.SECONDS))
                    report.put("writerCommittedWhileSnapshotOpen", true)
                }
                val keys = LogicalSegment.entries.associateWith { segment ->
                    File(root, "lw-${segment.ordinal}.keys").also { file ->
                        DataOutputStream(file.outputStream().buffered()).use { output ->
                            var n = 0L
                            reader.keys(segment) { rowid -> check(++n <= BackupLimits.MAX_ROWS_TOTAL); output.writeLong(rowid) }
                        }
                    }
                }
                if (args.getString("packing") == "stream" || args.getString("packing") == "level0") {
                    streamArchive(reader, keys, state, metadata, zip, report, fault) { stream ->
                        stream.flush()
                        File(root, "kill-marker.json").writeText(JSONObject().put("facts", before).put("pid", android.os.Process.myPid()).toString())
                        while (true) Thread.sleep(250)
                    }
                    completed = true
                    return
                }
                // First pass computes exact payload/CRC/hash/compressed size, but stores NO JSONL.
                val stats = LinkedHashMap<LogicalSegment, Stats>()
                for (segment in LogicalSegment.entries) {
                    val hash = MessageDigest.getInstance("SHA-256")
                    val crc = CRC32()
                    val counter = ByteCounter(OutputStream.nullOutputStream(), Long.MAX_VALUE)
                    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
                    var bytes = 0L; var rows = 0L
                    try {
                        DeflaterOutputStream(counter, deflater, 65_536).use { compressed ->
                            eachLine(reader, segment, keys.getValue(segment)) { line ->
                                hash.update(line); crc.update(line); bytes += line.size; rows++
                                check(bytes <= BackupLimits.MAX_ENTRY_UNCOMPRESSED)
                                compressed.write(line)
                            }
                        }
                    } finally { deflater.end() }
                    val sha = hex(hash.digest())
                    val oracle = metadata.getJSONObject("expected").getJSONObject(segment.name)
                    assertEquals(oracle.getLong("rows"), rows); assertEquals(oracle.getString("sha256"), sha)
                    val method = if (rows == 0L || counter.bytes >= bytes || counter.bytes * 100 < bytes) 0 else 8
                    stats[segment] = Stats(rows, bytes, if (method == 0) bytes else counter.bytes, crc.value, sha, method)
                }
                val totalRows = stats.values.sumOf { it.rows }; val totalBytes = stats.values.sumOf { it.bytes }
                check(totalRows <= BackupLimits.MAX_ROWS_TOTAL && totalBytes <= BackupLimits.MAX_TOTAL_UNCOMPRESSED)
                val provisional = BackupManifest(BackupLimits.FORMAT, 1, 1, base + 86_400_000,
                    state.first, state.second, 1, "lightweight-prototype", 13, totalRows, totalBytes,
                    LogicalSegment.entries.map { s -> stats.getValue(s).let { ManifestSegment(s.path, it.rows, it.bytes, it.sha) } }, "0".repeat(64))
                val manifest = provisional.copy(contentDigest = ManifestCodec.contentDigest(provisional))
                val manifestBytes = ManifestCodec.encode(manifest)
                val exactUpperZip = stats.values.sumOf { it.compressed } + manifestBytes.size + 22L +
                    (LogicalSegment.entries.map { it.path } + "manifest.json").sumOf { 76 + 2 * it.length } + 16 * 12
                check(exactUpperZip <= BackupLimits.MAX_ZIP_BYTES)
                report.put("sizePassMs", elapsed(start)).put("logicalBytes", totalBytes).put("zipUpperBytes", exactUpperZip)
                    .put("keyBytes", keys.values.sumOf { it.length() }).put("storedNonemptySegments", stats.values.count { it.rows > 0 && it.method == 0 })
                // No enormous product reserve is asserted here. Candidate formulas belong to the report.
                val out = ByteCounter(zip.outputStream().buffered(65_536), if (fault == "space") 1_024 else BackupLimits.MAX_ZIP_BYTES)
                var writtenRows = 0L
                ZipOutputStream(out).use { stream ->
                    for (segment in LogicalSegment.entries) {
                        val value = stats.getValue(segment)
                        val entry = ZipEntry(segment.path).apply {
                            // Mid-DOS-range date avoids timezone-dependent pre-1980 extra fields.
                            time = 946_771_200_000L; method = value.method
                            if (method == 0) { size = value.bytes; compressedSize = value.bytes; crc = value.crc }
                        }
                        stream.putNextEntry(entry)
                        eachLine(reader, segment, keys.getValue(segment)) { line ->
                            if (fault == "cancel" && writtenRows >= 50) throw CancellationException("prototype cancellation")
                            stream.write(line); writtenRows++
                            if (fault == "kill" && writtenRows == 50L) {
                                stream.flush()
                                File(root, "kill-marker.json").writeText(JSONObject().put("facts", before).put("pid", android.os.Process.myPid()).toString())
                                while (true) Thread.sleep(250)
                            }
                        }
                        stream.closeEntry()
                        assertEquals(value.bytes, entry.size); assertEquals(value.crc, entry.crc)
                        assertEquals(value.compressed, entry.compressedSize)
                    }
                    val crc = CRC32().apply { update(manifestBytes) }.value
                    stream.putNextEntry(ZipEntry("manifest.json").apply {
                        time = 946_771_200_000L; method = 0; size = manifestBytes.size.toLong(); compressedSize = size; this.crc = crc
                    })
                    stream.write(manifestBytes); stream.closeEntry()
                }
                assertEquals(manifest, LogicalZipReader.verify(zip).manifest)
                report.put("zipBytes", zip.length()).put("contentDigest", manifest.contentDigest)
                    .put("zipMs", elapsed(start)).put("zipSha256", hashFile(zip))
                ZipFile(zip).use { input ->
                    val entries = input.entries().asSequence().toList()
                    assertEquals("manifest.json", entries.last().name)
                    assertTrue(entries.filter { it.size == 0L }.all { it.method == 0 && it.compressedSize == 0L && it.crc == 0L })
                }
                RandomAccessFile(zip, "r").use { it.seek(6); val flags = it.readUnsignedByte() or (it.readUnsignedByte() shl 8); assertTrue(flags and 8 != 0) }
                completed = true
            }
            if (unified) {
                db.useReaderConnection { c -> c.deferredTransaction { capture(NativeAccess(this)) } }
            } else {
                val snapshot = File(root, "lw-static.db")
                val copied = System.nanoTime()
                protectedOpen(path).use { source -> source.rawQuery("VACUUM main INTO ?", arrayOf(snapshot.absolutePath)).use { assertEquals(0, it.count) } }
                report.put("copyMs", elapsed(copied)).put("physicalSnapshotBytes", snapshot.length())
                protectedOpen(snapshot).use { target -> FrameworkAccess(target).use { capture(it) } }
            }
            report.put("exportMs", elapsed(start))
        } catch (caught: Throwable) { error = caught; report.put("error", caught.javaClass.name + ": " + caught.message) }
        finally { sampler.stop(report); executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS) }
        try {
            if (fault != null) {
                assertFalse(completed); assertNotNull(error); assertEquals(before, facts(db)); mutate(db)
                report.put("source14TablesUnchanged", true).put("writerAfterAbort", true)
            } else {
                error?.let { throw it }; assertTrue(completed)
                if(highRatio && args.getString("packing")=="stream") { assertEquals(2,report.getInt("passes"));assertTrue(report.getInt("storedNonemptySegments")>0) }
                val targetName = "lw-restore-${UUID.randomUUID()}.db"
                val target = Room.databaseBuilder(context, RecordDatabase::class.java, targetName)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
                target.openHelper.writableDatabase
                try {
                    val restore = RestoreService(RoomRestoreStore(target), context.getDatabasePath(targetName), File(root, "restore"), DataOperationGate())
                    val restoreStart = System.nanoTime()
                    val candidate = restore.preflight(Source(zip)); val result = restore.restore(candidate)
                    assertEquals(metadata.getLong("rows"), result.importedRows)
                    target.useReaderConnection { c -> c.usePrepared("SELECT COUNT(*) FROM note") {
                        assertTrue(it.step()); assertEquals(metadata.getJSONObject("expected").getJSONObject("NOTE").getLong("rows"), it.getLong(0))
                    } }
                    report.put("existingImporterRoundTrip", true).put("restoreMs", elapsed(restoreStart))
                    File(root, "success.json").writeText("{\"complete\":true}")
                } finally { target.close(); context.deleteDatabase(targetName) }
            }
            emit(report.put("verdict", "PASS"))
        } catch (failure: Throwable) {
            emit(report.put("verdict", "FAIL").put("failure", failure.javaClass.name + ": " + failure.message))
            throw failure
        } finally { db.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }

    private suspend fun eachLine(reader: Access, segment: LogicalSegment, file: File, sink: (ByteArray) -> Unit) {
        DataInputStream(file.inputStream().buffered()).use { keys -> repeat((file.length() / 8).toInt()) {
            val text = reader.payload(segment, keys.readLong())
            val value = StrictJson.decodeObject(text.toByteArray(Charsets.UTF_8), BackupLimits.MAX_LINE_BYTES)
            val line = (SegmentCodecs.encode(SegmentCodecs.decode(segment, value)) + "\n").toByteArray(Charsets.UTF_8)
            check(line.size - 1 <= BackupLimits.MAX_LINE_BYTES); sink(line)
        } }
    }
    private suspend fun streamArchive(reader: Access, keys: Map<LogicalSegment,File>, state: Pair<String,Long>, metadata: JSONObject,
        zip: File, report: JSONObject, fault: String?, hold: (ZipOutputStream)->Unit) {
        var stored = emptyMap<LogicalSegment,Stats>()
        repeat(2) { attempt ->
            val stats=LinkedHashMap<LogicalSegment,Stats>(); var totalRows=0L; var totalBytes=0L
            ZipOutputStream(ByteCounter(zip.outputStream().buffered(65536),if(fault=="space")1024 else BackupLimits.MAX_ZIP_BYTES)).use { stream ->
                if(args.getString("packing")=="level0")stream.setLevel(Deflater.NO_COMPRESSION)
                for(segment in LogicalSegment.entries) {
                    val rows=keys.getValue(segment).length()/8
                    val prior=stored[segment]
                    val method=if(rows==0L||prior!=null)0 else 8
                    val entry=ZipEntry(segment.path).apply {time=946_771_200_000L;this.method=method
                        if(method==0){size=prior?.bytes?:0;compressedSize=size;crc=prior?.crc?:0} }
                    stream.putNextEntry(entry)
                    val hash=MessageDigest.getInstance("SHA-256");val crc=CRC32();var bytes=0L;var count=0L
                    eachLine(reader,segment,keys.getValue(segment)) { line ->
                        stream.write(line);hash.update(line);crc.update(line);bytes+=line.size;count++;totalRows++;totalBytes+=line.size
                        check(bytes<=BackupLimits.MAX_ENTRY_UNCOMPRESSED&&totalBytes<=BackupLimits.MAX_TOTAL_UNCOMPRESSED&&totalRows<=BackupLimits.MAX_ROWS_TOTAL)
                        if(fault=="cancel"&&totalRows>=50)throw CancellationException("prototype cancellation")
                        if(fault=="kill"&&totalRows==50L)hold(stream)
                    }
                    stream.closeEntry()
                    val sha=hex(hash.digest());val oracle=metadata.getJSONObject("expected").getJSONObject(segment.name)
                    assertEquals(oracle.getLong("rows"),count);assertEquals(oracle.getString("sha256"),sha)
                    assertEquals(bytes,entry.size);assertEquals(crc.value,entry.crc)
                    stats[segment]=Stats(count,bytes,entry.compressedSize,crc.value,sha,method)
                }
                val p=BackupManifest(BackupLimits.FORMAT,1,1,base+86_400_000,state.first,state.second,1,"lightweight-prototype",13,totalRows,totalBytes,
                    LogicalSegment.entries.map { s->stats.getValue(s).let {ManifestSegment(s.path,it.rows,it.bytes,it.sha)} },"0".repeat(64))
                val manifest=p.copy(contentDigest=ManifestCodec.contentDigest(p))
                val bytes=ManifestCodec.encode(manifest)
                stream.putNextEntry(ZipEntry("manifest.json").apply {time=946_771_200_000L;method=0;size=bytes.size.toLong();compressedSize=size;crc=CRC32().apply{update(bytes)}.value})
                stream.write(bytes);stream.closeEntry()
                report.put("contentDigest",manifest.contentDigest)
            }
            val excessive=stats.filterValues {it.method==8&&it.bytes>0&&it.compressed*100<it.bytes}
            if(excessive.isEmpty()) {
                val verified=LogicalZipReader.verify(zip)
                assertEquals(totalRows,verified.manifest.totalRows)
                ZipFile(zip).use {z->assertEquals("manifest.json",z.entries().asSequence().last().name)}
                report.put("passes",attempt+1).put("logicalBytes",totalBytes).put("zipBytes",zip.length()).put("keyBytes",keys.values.sumOf{it.length()})
                    .put("zipSha256",hashFile(zip)).put("storedNonemptySegments",stats.values.count{it.rows>0&&it.method==0})
                return
            }
            check(attempt==0){"compression fallback still violates ratio"}
            // The old tentative ZIP is closed before opening/truncating our own private task file.
            // Re-read the SAME snapshot, STORED only for excessive-ratio segments; no partial success.
            stored=excessive
        }
    }
    private interface Access {
        suspend fun keys(s: LogicalSegment, sink: (Long) -> Unit)
        suspend fun payload(s: LogicalSegment, id: Long): String
        suspend fun state(): Pair<String, Long>
        suspend fun config(): JSONObject
    }
    private class NativeAccess(private val c: PooledConnection) : Access {
        override suspend fun keys(s: LogicalSegment, sink: (Long) -> Unit) { c.usePrepared(keySql(s)) { while (it.step()) sink(it.getLong(0)) } }
        override suspend fun payload(s: LogicalSegment, id: Long) = c.usePrepared(payloadSql(s)) { it.bindLong(1, id); check(it.step()); it.getText(0) }
        override suspend fun state() = c.usePrepared("SELECT dataGeneration,businessRevision FROM record_data_state WHERE id=1") { check(it.step()); it.getText(0) to it.getLong(1) }
        override suspend fun config(): JSONObject { val result=JSONObject();for(p in listOf("cache_size","page_size","mmap_size","temp_store"))c.usePrepared("PRAGMA $p"){if(it.step())result.put(p,it.getLong(0))};return result }
    }
    private class FrameworkAccess(private val db: SQLiteDatabase) : Access, AutoCloseable {
        private val cached = HashMap<LogicalSegment, FrameworkStatement>()
        override suspend fun keys(s: LogicalSegment, sink: (Long) -> Unit) { db.rawQuery(keySql(s), null).use { while (it.moveToNext()) sink(it.getLong(0)) } }
        override suspend fun payload(s: LogicalSegment, id: Long): String { val stmt = cached.getOrPut(s) { db.compileStatement(payloadSql(s)) }; stmt.bindLong(1,id); return stmt.simpleQueryForString() }
        override suspend fun state() = db.rawQuery("SELECT dataGeneration,businessRevision FROM record_data_state WHERE id=1", null).use { check(it.moveToFirst()); it.getString(0) to it.getLong(1) }
        override suspend fun config(): JSONObject { val result=JSONObject();for(p in listOf("cache_size","page_size","mmap_size","temp_store"))db.rawQuery("PRAGMA $p",null).use {if(it.moveToFirst())result.put(p,it.getLong(0))};return result }
        override fun close() { cached.values.forEach { it.close() } }
    }
    private data class Stats(val rows: Long, val bytes: Long, val compressed: Long, val crc: Long, val sha: String, val method: Int)
    private class ByteCounter(private val out: OutputStream, private val limit: Long) : OutputStream() {
        var bytes = 0L
        override fun write(b: Int) { checkLimit(1); out.write(b); bytes++ }
        override fun write(b: ByteArray, off: Int, len: Int) { checkLimit(len); out.write(b,off,len); bytes += len }
        private fun checkLimit(n: Int) { if (bytes + n > limit) throw IOException("prototype output space budget exceeded") }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }
    private class Source(private val file: File) : BackupSource {
        override val displayName = "lightweight-prototype.zip"
        override suspend fun copyTo(destination: File, maxBytes: Long): Long { check(file.length() <= maxBytes); file.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }; return destination.length() }
    }
    private fun database(name: String) = Room.databaseBuilder(context, RecordDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).also { if (unified) it.setDriver(BundledSQLiteDriver()) }.build()
    private suspend fun mutate(db: RecordDatabase) { db.useWriterConnection { c -> c.immediateTransaction {
        usePrepared("UPDATE note SET body='after-snapshot',version=2 WHERE id=?") { it.bindText(1,id("note-0")); it.step() }
        usePrepared("UPDATE account SET name='after-snapshot',version=2") { it.step() }
        usePrepared("UPDATE record_data_state SET businessRevision=2") { it.step() }
    } } }
    private suspend fun facts(db: RecordDatabase): String = db.useReaderConnection { c -> c.deferredTransaction {
        val digest = MessageDigest.getInstance("SHA-256")
        for (table in LogicalSegment.entries.map { table(it) } + listOf("record_data_state","ai_control")) {
            val fields = ArrayList<String>()
            usePrepared("PRAGMA table_info($table)") { while(it.step()) fields += it.getText(1) }
            digest.update(table.toByteArray())
            usePrepared("SELECT json_object(${fields.joinToString(",") { "'$it',$it" }}) FROM $table ORDER BY rowid") {
                while(it.step()) { digest.update(it.getText(0).toByteArray(Charsets.UTF_8)); digest.update(10.toByte()) }
            }
        }
        hex(digest.digest())
    } }
    private class Sampler(private val db: File, private val work: File) {
        private val done = AtomicBoolean(false)
        private var peakPss=0L; private var baseline=0L; private var peakJava=0L; private var peakNative=0L
        private var peakFiles=0L; private var peakWal=0L; private var free=Long.MAX_VALUE
        private val walBaseline=File(db.path+"-wal").length()
        private val worker=Thread({ while(!done.get()) { sample(); Thread.sleep(100) } },"lw-sampler")
        @Synchronized private fun sample(): Long { val info=Debug.MemoryInfo(); Debug.getMemoryInfo(info); val pss=info.totalPss.toLong()
            peakPss=maxOf(peakPss,pss); val r=Runtime.getRuntime(); peakJava=maxOf(peakJava,r.totalMemory()-r.freeMemory())
            peakNative=maxOf(peakNative,Debug.getNativeHeapAllocatedSize()); peakWal=maxOf(peakWal,File(db.path+"-wal").length())
            peakFiles=maxOf(peakFiles,work.walkTopDown().filter { it.isFile }.sumOf { it.length() }); free=minOf(free,StatFs(work.path).availableBytes); return pss }
        fun start() { baseline=sample(); worker.start() }
        fun stop(report: JSONObject) { done.set(true); worker.join(2000); sample(); report.put("baselinePssKb",baseline).put("peakPssKb",peakPss)
            .put("pssIncreaseKb",maxOf(0L,peakPss-baseline)).put("peakJavaBytes",peakJava).put("peakNativeBytes",peakNative)
            .put("peakTempBytes",peakFiles).put("walBaselineBytes",walBaseline).put("peakWalBytes",peakWal)
            .put("walGrowthBytes",maxOf(0L,peakWal-walBaseline)).put("minFreeBytes",free).put("sampleIntervalMs",100) }
    }
    private fun emit(report: JSONObject) { val run=args.getString("runId")!!; val dir=File(context.noBackupFilesDir,"lightweight-reports"); check(dir.mkdirs()||dir.isDirectory)
        File(dir,"$run.json").writeText(report.put("runId",run).put("pid",android.os.Process.myPid()).toString()); println("LIGHTWEIGHT $report") }
    private fun protectedOpen(path: File): SQLiteDatabase = SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY) { throw SQLiteDatabaseCorruptException("automatic deletion disabled") }
    private fun elapsed(start: Long)=(System.nanoTime()-start)/1_000_000
    private fun id(text: String)=UUID.nameUUIDFromBytes(text.toByteArray()).toString()
    companion object {
        private fun table(s: LogicalSegment)=when(s) { LogicalSegment.DRAFT->"native_finance_draft";LogicalSegment.PROPOSAL->"change_proposal";LogicalSegment.OPERATION->"commit_operation";else->s.path.substringAfterLast('/').substringBefore('.') }
        private fun keySql(s: LogicalSegment)="SELECT rowid FROM ${table(s)}"+(if(s==LogicalSegment.DRAFT) " WHERE state='EDITING'" else "")+" ORDER BY "+when(s) {
            LogicalSegment.ACCOUNT,LogicalSegment.CATEGORY->"id";LogicalSegment.TRANSACTION,LogicalSegment.NOTE->"occurredAt,id";LogicalSegment.LINK->"transactionId,noteId"
            LogicalSegment.DRAFT->"slot";LogicalSegment.PROPOSAL,LogicalSegment.OPERATION,LogicalSegment.SESSION->"createdAt,id";LogicalSegment.RUN->"startedAt,id"
            LogicalSegment.MESSAGE->"sessionId,sequence";LogicalSegment.REFERENCE->"messageId,ordinal" }
        private fun payloadSql(s: LogicalSegment)="SELECT json_object(${s.fields.joinToString(",") { "'$it',$it" }}) FROM ${table(s)} WHERE rowid=?"
        private fun hex(bytes: ByteArray)=bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun hashFile(file: File): String { val d=MessageDigest.getInstance("SHA-256");file.inputStream().use { val b=ByteArray(65536);while(true){val n=it.read(b);if(n<0)break;d.update(b,0,n)} };return hex(d.digest()) }
    }
}

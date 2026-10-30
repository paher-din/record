package com.paherdin.record2.backup

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.os.CancellationSignal
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Engine-created scratch only. Never reopens an incomplete target after process death. */
internal object SystemExportSnapshot {
    fun openReadOnly(path: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        path.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
    ) { throw SQLiteDatabaseCorruptException("automatic backup source/target deletion disabled") }

    fun capture(source: File, work: File, check: () -> Unit, available: () -> Long): File {
        if (!source.isFile) fail(BackupError.Unsupported, "source database is missing")
        val target = File(work, "snapshot.db")
        val signal = CancellationSignal()
        val done = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val copyWorkQuota = available() - BackupLimits.EXPORT_FREE_RESERVE_BYTES
        openReadOnly(source).use { db ->
            fun scalar(sql: String) = db.compileStatement(sql).use { it.simpleQueryForLong() }
            if (db.compileStatement("PRAGMA journal_mode").use { it.simpleQueryForString() } != "wal") {
                fail(BackupError.Unsupported, "export requires WAL")
            }
            if (scalar("PRAGMA user_version") != 1L) fail(BackupError.Unsupported, "unsupported source schema")
            val live = (scalar("PRAGMA page_count") - scalar("PRAGMA freelist_count")) * scalar("PRAGMA page_size")
            if (available() - BackupLimits.EXPORT_FREE_RESERVE_BYTES < live + 65536) {
                fail(BackupError.StorageFull, "insufficient space for a compact database snapshot")
            }
            val watcher = Thread({
                while (!done.get()) {
                    try {
                        check()
                        val visibleBytes = work.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
                        if (visibleBytes > copyWorkQuota) fail(BackupError.StorageFull, "snapshot work quota exceeded")
                    } catch (e: Throwable) { failure.compareAndSet(null, e); signal.cancel(); break }
                    Thread.sleep(10)
                }
            }, "record2-export-monitor").apply { isDaemon = true }
            watcher.start()
            try {
                db.rawQuery("VACUUM main INTO ?", arrayOf(target.absolutePath), signal).use { it.count }
                failure.get()?.let { throw it }
                check()
            } catch (error: Throwable) {
                throw failure.get() ?: error
            } finally {
                done.set(true); watcher.join()
            }
            // A sample can fail between the last foreground check and watcher shutdown.
            // Joining publishes that failure; a completed native copy must not hide it.
            failure.get()?.let { throw it }
        }
        openReadOnly(target).use { db ->
            if (db.compileStatement("PRAGMA journal_mode").use { it.simpleQueryForString() } != "delete") {
                fail(BackupError.Unsupported, "snapshot is not a standalone rollback-journal database")
            }
            if (db.compileStatement("PRAGMA integrity_check").use { it.simpleQueryForString() } != "ok") {
                fail(BackupError.InvalidSource, "snapshot integrity check failed")
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use {
                if (it.moveToFirst()) fail(BackupError.InvalidSource, "snapshot contains invalid foreign keys")
            }
            if (db.compileStatement("PRAGMA user_version").use { it.simpleQueryForLong() } != 1L ||
                db.compileStatement("SELECT identity_hash FROM room_master_table WHERE id=42").use { it.simpleQueryForString() } != "c1b84c0a7822f354324484b6995cfb0d") {
                fail(BackupError.Unsupported, "snapshot schema identity mismatch")
            }
        }
        return target
    }
}

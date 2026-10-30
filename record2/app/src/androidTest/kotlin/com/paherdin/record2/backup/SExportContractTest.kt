package com.paherdin.record2.backup

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SExportContractTest {
    @Test
    fun cancelledProductionExportDoesNotDeliverOrChangeSource() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "s-export-cancel-${UUID.randomUUID()}.db"
        val database = androidx.room.Room.databaseBuilder(context,
            com.paherdin.record2.storage.RecordDatabase::class.java, name)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        database.openHelper.writableDatabase
        val path = context.getDatabasePath(name)
        val root = File(context.noBackupFilesDir, "s-export-cancel-${UUID.randomUUID()}")
        val gate = DataOperationGate()
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(path.path, null, 0).use {
                com.paherdin.record2.backup.prototype.SFinalFixture(1000, "small", false).seed(it)
            }
            val before = path.readBytes()
            val wal = File(path.path + "-wal")
            val walBefore = if (wal.exists()) wal.readBytes() else byteArrayOf()
            val job = kotlinx.coroutines.Job()
            var delivered = false
            val result = runCatching {
                kotlinx.coroutines.withContext(job) {
                    BackupExporter(path, root, gate, AppInfo(1, "test"), checkpoint = {
                        if (it == ExportCheckpoint.SNAPSHOT_READ) job.cancel()
                    }).export(object : BackupTarget {
                        override suspend fun writeFrom(source: File) { delivered = true }
                        override suspend fun readBackSha256() = ""
                        override suspend fun readBackBytes() = 0L
                    })
                }
            }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertFalse(delivered)
            assertArrayEquals(before, path.readBytes())
            assertArrayEquals(walBefore, if (wal.exists()) wal.readBytes() else byteArrayOf())
            assertTrue(root.listFiles().orEmpty().isEmpty())
            gate.beginExport().close()
        } finally { database.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }

    @Test
    fun corruptSourceIsNeverDeletedAndLeaseIsReleased() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = context.getDatabasePath("s-export-corrupt-${UUID.randomUUID()}.db")
        source.parentFile!!.mkdirs()
        val original = ByteArray(4096) { 0x5a }
        source.writeBytes(original)
        val root = File(context.noBackupFilesDir, "s-export-corrupt-${UUID.randomUUID()}")
        val gate = DataOperationGate()
        var delivered = false
        try {
            val result = runCatching {
                BackupExporter(source, root, gate, AppInfo(1, "test")).export(object : BackupTarget {
                    override suspend fun writeFrom(source: File) { delivered = true }
                    override suspend fun readBackSha256() = ""
                    override suspend fun readBackBytes() = 0L
                })
            }
            assertTrue(result.isFailure)
            assertFalse(delivered)
            assertTrue(source.isFile)
            assertArrayEquals(original, source.readBytes())
            assertTrue(root.listFiles().orEmpty().isEmpty())
            gate.beginExport().close()
        } finally { source.delete(); root.deleteRecursively() }
    }
}

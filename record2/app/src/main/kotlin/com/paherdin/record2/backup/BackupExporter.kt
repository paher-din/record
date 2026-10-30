package com.paherdin.record2.backup

import java.io.File
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

internal data class AppInfo(val versionCode: Long, val versionName: String)

internal data class ExportReceipt(
    val displayName: String?,
    val zipBytes: Long,
    val zipSha256: String,
    val exportedAt: Long,
    val totalRows: Long,
    val totalUncompressedBytes: Long,
    val segmentCount: Int,
)

internal enum class ExportCheckpoint {
    SNAPSHOT_READ,
    SEGMENTS_WRITTEN,
    ZIP_WRITTEN,
    ZIP_VERIFIED,
    SAF_WRITTEN,
    SAF_READ_BACK,
}

internal class BackupExporter(
    private val databasePath: File,
    private val stagingDirectory: File,
    private val gate: DataOperationGate,
    private val appInfo: AppInfo,
    private val clock: Clock = Clock.systemUTC(),
    private val availableBytes: (File) -> Long = { FileSpace.availableBytes(it) },
    private val monitorFactory: (File, File) -> ExportResourceMonitor = { db, staging ->
        FileSystemExportResourceMonitor(db, staging)
    },
    private val snapshotHook: () -> Unit = {},
    private val checkpoint: (ExportCheckpoint) -> Unit = {},
) {
    suspend fun export(target: BackupTarget, displayName: String? = null): ExportReceipt = withContext(Dispatchers.IO) {
        val lease = gate.beginExport()
        val work = try { BackupStaging.createTask(stagingDirectory, "export") }
            catch (error: Throwable) { lease.close(); throw error }
        try {
            val context = kotlinx.coroutines.currentCoroutineContext()
            val monitor = monitorFactory(databasePath, stagingDirectory)
            val monitorLock = Any()
            var nextSample = 0L
            fun check() {
                context.ensureActive()
                synchronized(monitorLock) {
                    val now = System.nanoTime()
                    if (now >= nextSample) {
                        monitor.check()
                        nextSample = now + 10_000_000L
                    }
                }
            }
            val exportedAt = clock.millis()
            val snapshot = SystemExportSnapshot.capture(databasePath, work, ::check) { availableBytes(stagingDirectory) }
            snapshotHook()
            check()
            checkpoint(ExportCheckpoint.SNAPSHOT_READ)
            val (zip, verified) = SystemExportSnapshot.openReadOnly(snapshot).use { db ->
                StaticLogicalZip.write(db, work, exportedAt, appInfo, ::check,
                    available = { availableBytes(stagingDirectory) }, written = {
                        checkpoint(ExportCheckpoint.SEGMENTS_WRITTEN)
                        checkpoint(ExportCheckpoint.ZIP_WRITTEN)
                    })
            }
            val manifest = verified.manifest
            val totalRows = manifest.totalRows
            val totalBytes = manifest.totalUncompressedBytes
            if (zip.length() > BackupLimits.MAX_ZIP_BYTES) {
                fail(BackupError.StorageFull, "export ZIP exceeds 64 MiB")
            }
            val zipSha = verified.zipSha256
            checkpoint(ExportCheckpoint.ZIP_VERIFIED)
            check()
            // Release all physical/key scratch before a same-volume SAF copy can coexist.
            work.listFiles()?.filter { it != zip }?.forEach {
                if (!it.deleteRecursively()) fail(BackupError.RecoverableIO, "could not release snapshot workspace")
            }
            target.writeFrom(zip)
            check()
            checkpoint(ExportCheckpoint.SAF_WRITTEN)
            val readBackBytes = target.readBackBytes()
            if (readBackBytes != zip.length()) {
                fail(BackupError.RecoverableIO, "SAF read-back size does not match the staging ZIP")
            }
            val readBackSha = target.readBackSha256()
            if (readBackSha != zipSha) {
                fail(BackupError.RecoverableIO, "SAF read-back digest does not match the staging ZIP")
            }
            checkpoint(ExportCheckpoint.SAF_READ_BACK)
            ExportReceipt(
                displayName = displayName,
                zipBytes = zip.length(),
                zipSha256 = zipSha,
                exportedAt = exportedAt,
                totalRows = totalRows,
                totalUncompressedBytes = totalBytes,
                segmentCount = LogicalSegment.entries.size,
            )
        } catch (error: android.database.sqlite.SQLiteFullException) {
            throw BackupException(BackupError.StorageFull, "snapshot volume is full", error)
        } catch (error: java.io.IOException) {
            val full = generateSequence<Throwable>(error) { it.cause }.any {
                it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC
            }
            throw BackupException(if (full) BackupError.StorageFull else BackupError.RecoverableIO, "export I/O failed", error)
        } finally {
            try {
                val cleanup = runCatching { work.deleteRecursively() }
                if (!cleanup.getOrDefault(false)) android.util.Log.w(
                    "RecordStaging", "export workspace cleanup failed", cleanup.exceptionOrNull(),
                )
            } finally {
                try { BackupStaging.releaseTask(work) } finally { lease.close() }
            }
        }
    }
}

internal object FileSpace {
    fun availableBytes(directory: File): Long =
        android.os.StatFs(directory.absolutePath).availableBytes
}

private fun sha256FileOrFail(file: File): String {
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

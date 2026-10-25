package com.paherdin.record2.backup

import java.io.File
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        stagingDirectory.mkdirs()
        val lease = gate.beginExport()
        val work = File(stagingDirectory, "export-${UUID.randomUUID()}")
        try {
            if (availableBytes(stagingDirectory) < BackupLimits.EXPORT_STAGING_RESERVE_BYTES) {
                fail(BackupError.StorageFull, "staging volume has less than 128 MiB available")
            }
            if (availableBytes(databasePath.parentFile ?: databasePath) < BackupLimits.EXPORT_FREE_RESERVE_BYTES) {
                fail(BackupError.StorageFull, "database volume has less than 64 MiB available")
            }
            if (!work.mkdirs()) fail(BackupError.RecoverableIO, "could not create export staging directory")
            val exportedAt = clock.millis()
            val snapshot = ReadOnlyBackupSnapshot.stream(
                databasePath,
                exportedAt,
                monitorFactory(databasePath, stagingDirectory),
                snapshotHook,
                work,
            )
            checkpoint(ExportCheckpoint.SNAPSHOT_READ)
            val sources = snapshot.sources
            val totalRows = snapshot.totalRows
            val totalBytes = snapshot.totalUncompressedBytes
            checkpoint(ExportCheckpoint.SEGMENTS_WRITTEN)
            val segments = sources.mapIndexed { index, source ->
                ManifestSegment(
                    name = LogicalSegment.entries[index].path,
                    rows = source.rows,
                    uncompressedBytes = source.uncompressedBytes,
                    sha256 = source.sha256,
                )
            }
            val provisional = BackupManifest(
                format = BackupLimits.FORMAT,
                formatVersion = BackupLimits.FORMAT_VERSION,
                databaseSchemaVersion = BackupLimits.DATABASE_SCHEMA_VERSION,
                exportedAt = exportedAt,
                sourceGeneration = snapshot.dataGeneration,
                businessRevision = snapshot.businessRevision,
                appVersionCode = appInfo.versionCode,
                appVersionName = appInfo.versionName,
                entryCount = BackupLimits.ENTRY_COUNT,
                totalRows = totalRows,
                totalUncompressedBytes = totalBytes,
                segments = segments,
                contentDigest = "0".repeat(64),
            )
            val manifest = provisional.copy(contentDigest = ManifestCodec.contentDigest(provisional))
            val zip = File(work, "backup.zip")
            LogicalZipWriter.write(zip, manifest, sources)
            checkpoint(ExportCheckpoint.ZIP_WRITTEN)
            val verified = LogicalZipReader.verify(zip)
            if (verified.manifest != manifest) {
                fail(BackupError.Internal, "staging ZIP does not round-trip its manifest")
            }
            if (zip.length() > BackupLimits.MAX_ZIP_BYTES) {
                fail(BackupError.StorageFull, "export ZIP exceeds 64 MiB")
            }
            val zipSha = sha256FileOrFail(zip)
            checkpoint(ExportCheckpoint.ZIP_VERIFIED)
            target.writeFrom(zip)
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
        } finally {
            lease.close()
            work.deleteRecursively()
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

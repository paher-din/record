package com.paherdin.record.core.data.backup

import com.paherdin.record.core.data.BackupStage
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class BackupIoException(val stage: BackupStage, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The ZIP package built by the export pipeline, before publication. */
internal data class BuiltPackage(
    val file: File,
    val manifest: Manifest,
    val packageSha256: String,
)

/**
 * The export pipeline (spec 4.1, plan Task 4): streams the consistent-read
 * snapshot's eight datasets and the attachment blobs into a ZIP, hashing each
 * entry with SHA-256 as it is written, writes the manifest last, and returns
 * the built package for validation and publication. The manifest is written
 * after every payload entry so the complete package is validated before it is
 * visible anywhere.
 */
internal class BackupExporter(
    private val snapshot: Map<LogicalType, List<Any>>,
    private val attachmentContentDir: File,
    private val databaseSchemaVersion: Int,
    private val appVersion: String,
    private val hook: BackupFaultHook = BackupFaultHook.NO_OP,
) {
    fun build(zipFile: File): BuiltPackage {
        val entries = ArrayList<ManifestEntry>()
        val backupId = UUID.randomUUID().toString()
        zipFile.parentFile?.mkdirs()
        var manifest: Manifest? = null
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            for (type in LogicalBackupSchema.DATASET_ORDER) {
                val records = snapshot[type].orEmpty()
                hook.beforeEntryWrite(type.name)
                val path = "${type.name}.jsonl"
                val (bytes, count) = writeJsonl(zip, type, path, records)
                hook.afterEntryWrite(type.name)
                entries += ManifestEntry(type.name, path, bytes.size.toLong(), sha256(bytes), count.toLong())
            }
            val attachments = snapshot[LogicalType.Attachment].orEmpty()
            for (a in attachments) {
                val contentIdentity = (a as com.paherdin.record.core.data.room.Attachment).contentIdentity
                hook.beforeEntryWrite("AttachmentContent")
                val blob = File(attachmentContentDir, contentIdentity)
                if (!blob.isFile) throw BackupIoException(BackupStage.STREAM_ATTACHMENT, "blob missing for $contentIdentity")
                val path = "attachments/$contentIdentity"
                val (size, hash) = writeFile(zip, path, blob) { hook.midEntryWrite("AttachmentContent") }
                hook.afterEntryWrite("AttachmentContent")
                entries += ManifestEntry("AttachmentContent", path, size, hash, null)
            }
            hook.beforeManifestWrite()
            manifest = Manifest(
                formatVersion = ManifestCodec.FORMAT_VERSION,
                databaseSchemaVersion = databaseSchemaVersion,
                appVersion = appVersion,
                createdAt = System.currentTimeMillis(),
                backupId = backupId,
                hashAlgorithm = ManifestCodec.HASH_ALGORITHM,
                catalogNameKeyVersions = emptyList(),
                entries = entries,
            )
            val manifestBytes = ManifestCodec.encode(manifest!!).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestBytes)
            zip.closeEntry()
            hook.midManifestWrite()
            hook.afterManifestWriteBeforeClose()
        }
        val packageSha = sha256(zipFile.inputStream())
        return BuiltPackage(zipFile, checkNotNull(manifest), packageSha)
    }

    /** Validates that every manifest entry hash matches its ZIP entry content. */
    fun validate(zipFile: File, manifest: Manifest): Boolean {
        return try {
            val entryHashes = mutableMapOf<String, String>()
            java.util.zip.ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.name != "manifest.json") {
                        entryHashes[entry.name] = zip.getInputStream(entry).use { sha256(it) }
                    }
                }
            }
            manifest.entries.all { entryHashes[it.path]?.equals(it.sha256, ignoreCase = true) == true }
        } catch (e: Exception) {
            false
        }
    }

    private fun writeJsonl(zip: ZipOutputStream, type: LogicalType, path: String, records: List<Any>): Pair<ByteArray, Int> {
        val sb = StringBuilder()
        records.forEachIndexed { index, record ->
            sb.append(LogicalBackupSchema.encode(type, record)).append('\n')
            if (index == 0) hook.midEntryWrite(type.name)
        }
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
        return bytes to records.size
    }

    private fun writeFile(zip: ZipOutputStream, path: String, file: File, onMidCopy: () -> Unit = {}): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(path))
        var total = 0L
        var firstChunk = true
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                zip.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                total += read
                if (firstChunk) {
                    firstChunk = false
                    onMidCopy()
                }
            }
        }
        zip.closeEntry()
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

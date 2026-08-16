package com.paherdin.record.core.data.room

import com.paherdin.record.core.data.backup.ManifestCodec
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a ZIP whose manifest and entries can be freely shaped for the
 * malicious-ZIP matrix. Over-limit per-entry/aggregate sizes are declared in
 * the manifest (the declared pass uses the manifest size) rather than
 * physically materializing multi-GiB payloads.
 */
object MaliciousZipGenerator {

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Builds a ZIP at `file` with the given entries (name -> bytes) and a
     * manifest whose descriptors use the given `declared` sizes/hashes for the
     * same names. `databaseSchemaVersion` and `formatVersion` are settable to
     * exercise format and version rejections.
     */
    fun build(
        file: File,
        entries: List<Pair<String, ByteArray>>,
        declaredSizes: Map<String, Long> = emptyMap(),
        declaredHashes: Map<String, String> = emptyMap(),
        formatVersion: Int = 1,
        databaseSchemaVersion: Int = 2,
        catalogVersions: List<Int> = emptyList(),
    ): File {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            val manifestEntries = entries.map { (name, bytes) ->
                val size = declaredSizes[name] ?: bytes.size.toLong()
                val hash = declaredHashes[name] ?: sha256(bytes)
                val isContent = name.startsWith("attachments/")
                val logicalType = if (isContent) "AttachmentContent" else name.removeSuffix(".jsonl")
                val recordCount = if (isContent) null else bytes.count { it.toInt() == '\n'.code }.toLong()
                """{"logicalType":${json(logicalType)},"path":${json(name)},"size":$size,"sha256":${json(hash)}${recordCount?.let { "," + "\"recordCount\":$it" } ?: ""}}"""
            }
            val manifest = "{" +
                "\"formatVersion\":$formatVersion," +
                "\"databaseSchemaVersion\":$databaseSchemaVersion," +
                "\"appVersion\":\"test (1)\"," +
                "\"createdAt\":1," +
                "\"backupId\":\"malicious-1\"," +
                "\"hashAlgorithm\":\"SHA-256\"," +
                "\"catalogNameKeyVersions\":[${catalogVersions.joinToString(",")}]," +
                "\"entries\":[${manifestEntries.joinToString(",")}]" +
                "}"
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }

    /**
     * Builds a ZIP at `file` with the given entries (name -> bytes) and a
     * hand-authored manifest JSON string. Used for cases the auto-derived
     * manifest cannot express (duplicate logicalType, duplicate normalized
     * path, overlong paths, and so on).
     */
    fun buildWithManifest(file: File, entries: List<Pair<String, ByteArray>>, manifestJson: String): File {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }

    private fun json(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

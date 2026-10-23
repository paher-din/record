package com.paherdin.record.core.data.restore

import com.paherdin.record.core.data.RestoreViolation
import com.paherdin.record.core.data.backup.LogicalType
import com.paherdin.record.core.data.backup.LogicalBackupSchema
import com.paherdin.record.core.data.backup.Manifest
import com.paherdin.record.core.data.backup.ManifestCodec
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal sealed interface PreflightResult {
    data class Accepted(val manifest: Manifest) : PreflightResult
    data class Rejected(val violation: RestoreViolation) : PreflightResult
}

/**
 * The restore preflight (spec 4.3, plan Task 6): the declared pass combines the
 * strict manifest with ZIP-directory metadata, the streaming pass independently
 * counts actual entries and bytes without trusting manifest or ZIP headers, and
 * the catalog-contract framework is enforced. Any failure rejects the candidate
 * before the current vault is touched. Pure Kotlin; the caller maps a rejection
 * to `RestoreRejected(violation)`.
 *
 * The manifest itself is size-capped before parsing (a descriptor for 100,000
 * entries is far below the cap), and the streaming pass consumes entries in
 * bounded chunks so an oversized payload rejects instead of materializing.
 */
internal class RestorePreflight(
    private val source: File,
    private val availableAppPrivateSpace: Long,
) {
    fun run(): PreflightResult {
        val manifest = readManifest() ?: return PreflightResult.Rejected(RestoreViolation.MANIFEST_INVALID)

        // plan Task 6 first-match order: the format gate precedes type/path checks.
        if (manifest.formatVersion != ManifestCodec.FORMAT_VERSION) {
            return PreflightResult.Rejected(RestoreViolation.FORMAT_UNSUPPORTED)
        }
        val supportedLogicalTypes = (LogicalBackupSchema.DATASET_ORDER.map { it.name } + ATTACHMENT_CONTENT_TYPE).toSet()

        for (entry in manifest.entries) {
            CatalogContractChecker.checkLogicalType(entry.logicalType, supportedLogicalTypes)
                ?.let { return PreflightResult.Rejected(it) }
        }
        val singletonTypes = LogicalBackupSchema.SINGLETON_TYPES.map { it.name }.toSet()
        val duplicateSingleton = manifest.entries
            .map { it.logicalType }
            .groupingBy { it }
            .eachCount()
            .any { (type, count) -> type in singletonTypes && count > 1 }
        if (duplicateSingleton) return PreflightResult.Rejected(RestoreViolation.SINGLETON_DUPLICATE)
        CatalogContractChecker.checkManifestVersions(manifest.catalogNameKeyVersions, SUPPORTED_CATALOG_VERSIONS)
            ?.let { return PreflightResult.Rejected(it) }
        // The encountered per-record nameKeyVersion set must exactly match the
        // manifest summary. No catalog table exists in v2, so any declared
        // catalog version has no matching records.
        CatalogContractChecker.checkEncounteredVersions(manifest.catalogNameKeyVersions, emptySet())
            ?.let { return PreflightResult.Rejected(it) }

        val declared = buildDeclaredInput(manifest) ?: return PreflightResult.Rejected(RestoreViolation.MANIFEST_INVALID)
        ZipPreflightDeclared.classify(declared)?.let { return PreflightResult.Rejected(it) }

        streamPass(manifest)?.let { return PreflightResult.Rejected(it) }

        return PreflightResult.Accepted(manifest)
    }

    private fun readManifest(): Manifest? {
        return try {
            ZipFile(source).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                if (entry.size > MAX_MANIFEST_BYTES) return null
                ManifestCodec.decode(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() })
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildDeclaredInput(manifest: Manifest): DeclaredInput? {
        return try {
            ZipFile(source).use { zip ->
                val manifestByPath = manifest.entries.associateBy { it.path }
                val zipEntries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                val totalZipEntries = zipEntries.size.toLong() // includes manifest.json
                val payloadEntries = zipEntries.filter { it.name != "manifest.json" }
                if (payloadEntries.size != manifest.entries.size) return null
                val declaredEntries = payloadEntries.map { ze ->
                    val manifestEntry = manifestByPath[ze.name] ?: return null
                    DeclaredEntry(
                        logicalType = manifestEntry.logicalType,
                        rawPath = ze.name,
                        isAttachment = manifestEntry.logicalType == ATTACHMENT_CONTENT_TYPE,
                        declaredSize = manifestEntry.size,
                        zipUncompressedSize = ze.size,
                        zipCompressedSize = ze.compressedSize,
                    )
                }
                DeclaredInput(
                    formatVersion = manifest.formatVersion,
                    catalogNameKeyVersions = manifest.catalogNameKeyVersions,
                    supportedCatalogVersions = SUPPORTED_CATALOG_VERSIONS,
                    supportedLogicalTypes = (LogicalBackupSchema.DATASET_ORDER.map { it.name } + ATTACHMENT_CONTENT_TYPE).toSet(),
                    singletonLogicalTypes = LogicalBackupSchema.SINGLETON_TYPES.map { it.name }.toSet(),
                    entries = declaredEntries,
                    totalZipEntries = totalZipEntries,
                    availableAppPrivateSpace = availableAppPrivateSpace,
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun streamPass(manifest: Manifest): RestoreViolation? {
        return try {
            val declaredUncompressed = manifest.entries.map { it.size }
            val declaredAttachment = manifest.entries.map { it.logicalType == ATTACHMENT_CONTENT_TYPE }
            val preflight = ZipStreamingPreflight(
                declaredEntries = manifest.entries.size.toLong(),
                declaredPerEntryUncompressed = declaredUncompressed,
                declaredPerEntryAttachment = declaredAttachment,
                declaredTotalUncompressed = manifest.entries.sumOf { it.size },
                availableAppPrivateSpace = availableAppPrivateSpace,
            )
            ZipInputStream(source.inputStream().buffered()).use { zip ->
                var index = 0L
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name != "manifest.json") {
                        // Bounded chunked consumption: once the actual bytes pass the
                        // declared size, the violation is proven and the rest of the
                        // entry is skipped without materializing it.
                        val declaredSize = declaredUncompressed.getOrNull(index.toInt()) ?: Long.MAX_VALUE
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > declaredSize) break
                        }
                        val violation = preflight.feedEntry(index, total)
                        if (violation != null) return violation
                        index++
                    }
                    entry = zip.nextEntry
                }
            }
            null
        } catch (e: Exception) {
            RestoreViolation.MANIFEST_INVALID
        }
    }

    private companion object {
        val SUPPORTED_CATALOG_VERSIONS: Set<Int> = setOf(1)

        /** A manifest describing at most 100,000 entries is far below this cap. */
        const val MAX_MANIFEST_BYTES: Long = 64L * 1024 * 1024
        const val ATTACHMENT_CONTENT_TYPE: String = "AttachmentContent"
    }
}

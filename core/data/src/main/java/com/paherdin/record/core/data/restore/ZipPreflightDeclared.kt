package com.paherdin.record.core.data.restore

import com.paherdin.record.core.data.RestoreViolation
import com.paherdin.record.core.data.backup.ManifestCodec

/**
 * Declared-pass preflight decision table (spec 4.3, plan Task 6): combines the
 * manifest descriptors with ZIP-directory metadata and returns the first
 * binding violation, or `null` when the declared package passes. Pure Kotlin.
 */
internal data class DeclaredEntry(
    val logicalType: String,
    val rawPath: String,
    val isAttachment: Boolean,
    val declaredSize: Long,
    val zipUncompressedSize: Long,
    val zipCompressedSize: Long,
)

internal data class DeclaredInput(
    val formatVersion: Int,
    val catalogNameKeyVersions: List<Int>,
    val supportedCatalogVersions: Set<Int>,
    val supportedLogicalTypes: Set<String>,
    val singletonLogicalTypes: Set<String>,
    val entries: List<DeclaredEntry>,
    val totalZipEntries: Long,
    val availableAppPrivateSpace: Long,
)

internal object ZipPreflightDeclared {

    const val MAX_ENTRIES = 100_000L
    const val MAX_PATH_UTF8_BYTES = 240
    const val MAX_ATTACHMENT_SIZE = 512L * 1024 * 1024
    const val MAX_STRUCTURED_SIZE = 1L * 1024 * 1024 * 1024
    const val MAX_AGGREGATE_SIZE = 20L * 1024 * 1024 * 1024
    const val SPACE_RESERVE = 1L * 1024 * 1024 * 1024
    const val RATIO_LIMIT = 200L

    fun classify(input: DeclaredInput): RestoreViolation? {
        if (input.formatVersion != ManifestCodec.FORMAT_VERSION) return RestoreViolation.FORMAT_UNSUPPORTED
        for (v in input.catalogNameKeyVersions) {
            if (v !in input.supportedCatalogVersions) return RestoreViolation.NAME_KEY_VERSION_UNSUPPORTED
        }
        // Duplicate singleton logicalType (independent of path uniqueness).
        val seenSingletons = HashSet<String>()
        for (e in input.entries) {
            if (e.logicalType !in input.supportedLogicalTypes) return RestoreViolation.UNKNOWN_LOGICAL_TYPE
            if (e.logicalType in input.singletonLogicalTypes && !seenSingletons.add(e.logicalType)) {
                return RestoreViolation.SINGLETON_DUPLICATE
            }
        }
        // Path validation: normalize, reject traversal/absolute/overlong/duplicates.
        val normalized = HashSet<String>()
        for (e in input.entries) {
            val norm = normalizePath(e.rawPath) ?: return RestoreViolation.PATH_INVALID
            if (norm.toByteArray(Charsets.UTF_8).size > MAX_PATH_UTF8_BYTES) return RestoreViolation.PATH_TOO_LONG
            if (!normalized.add(norm)) return RestoreViolation.PATH_DUPLICATE
        }
        if (input.totalZipEntries > MAX_ENTRIES) return RestoreViolation.ENTRY_COUNT_LIMIT
        var totalUncompressed = 0L
        var archiveCompressed = 0L
        for (e in input.entries) {
            val effectiveSize = e.zipUncompressedSize.coerceAtLeast(e.declaredSize)
            if (e.isAttachment && effectiveSize > MAX_ATTACHMENT_SIZE) return RestoreViolation.ENTRY_SIZE_LIMIT
            if (!e.isAttachment && effectiveSize > MAX_STRUCTURED_SIZE) return RestoreViolation.ENTRY_SIZE_LIMIT
            if (effectiveSize > 0) {
                if (e.zipCompressedSize <= 0) return RestoreViolation.COMPRESSION_RATIO_LIMIT
                if (effectiveSize / e.zipCompressedSize > RATIO_LIMIT) return RestoreViolation.COMPRESSION_RATIO_LIMIT
            }
            totalUncompressed += effectiveSize
            archiveCompressed += e.zipCompressedSize
        }
        val spaceBudget = input.availableAppPrivateSpace - SPACE_RESERVE
        if (spaceBudget <= 0) return RestoreViolation.SPACE_BUDGET
        if (totalUncompressed > minOf(MAX_AGGREGATE_SIZE, spaceBudget)) return RestoreViolation.AGGREGATE_SIZE_LIMIT
        if (totalUncompressed > 0) {
            if (archiveCompressed <= 0) return RestoreViolation.COMPRESSION_RATIO_LIMIT
            if (totalUncompressed / archiveCompressed > RATIO_LIMIT) return RestoreViolation.COMPRESSION_RATIO_LIMIT
        }
        return null
    }

    /**
     * Returns the normalized relative path or `null` when the path is invalid:
     * absolute (leading `/` or `\` or drive-letter), contains a `..` traversal or
     * `.` segment, or escapes relative form. Byte length is checked by the caller.
     */
    fun normalizePath(path: String): String? {
        if (path.isEmpty()) return null
        val unixPath = path.replace('\\', '/')
        if (unixPath.startsWith("/")) return null
        if (unixPath.matches(Regex("^[A-Za-z]:.*"))) return null
        val segments = unixPath.split('/')
        val out = ArrayList<String>()
        for (seg in segments) {
            when (seg) {
                "", "." -> Unit
                ".." -> return null
                else -> out.add(seg)
            }
        }
        if (out.isEmpty()) return null
        return out.joinToString("/")
    }
}

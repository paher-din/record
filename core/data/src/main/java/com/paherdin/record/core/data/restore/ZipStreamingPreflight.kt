package com.paherdin.record.core.data.restore

import com.paherdin.record.core.data.RestoreViolation

/**
 * Independent streaming-pass preflight (spec 4.3, plan Task 6). Counts actual
 * entries and actual uncompressed bytes without trusting manifest or ZIP
 * headers, and reports the first violation. Actual uncompressed bytes may never
 * exceed the declared per-entry size, the declared aggregate, or the space
 * budget; crossing a limit fails immediately.
 *
 * Compressed bytes are NOT tracked here: `ZipInputStream` buffers ahead from
 * the underlying stream, so per-entry compressed attribution is unreliable.
 * Zip-bomb protection is covered by the declared pass (central-directory
 * ratios, reliable) plus this pass's declared-below-actual uncompressed and
 * aggregate checks.
 */
internal class ZipStreamingPreflight(
    private val declaredEntries: Long,
    private val declaredPerEntryUncompressed: List<Long>,
    private val declaredPerEntryAttachment: List<Boolean>,
    private val declaredTotalUncompressed: Long,
    private val availableAppPrivateSpace: Long,
) {
    private var actualEntries = 0L
    private var actualUncompressed = 0L
    private val spaceBudget = availableAppPrivateSpace - ZipPreflightDeclared.SPACE_RESERVE

    /** Feed one finished entry at the given zero-based index. Returns the first violation, or null. */
    fun feedEntry(index: Long, uncompressed: Long): RestoreViolation? {
        actualEntries++
        if (actualEntries > declaredEntries) return RestoreViolation.ENTRY_COUNT_LIMIT
        if (actualEntries > ZipPreflightDeclared.MAX_ENTRIES) return RestoreViolation.ENTRY_COUNT_LIMIT
        val declaredSize = declaredPerEntryUncompressed.getOrNull(index.toInt()) ?: Long.MAX_VALUE
        if (uncompressed > declaredSize) return RestoreViolation.SIZE_UNDER_DECLARED
        val declaredAttachment = declaredPerEntryAttachment.getOrNull(index.toInt()) ?: false
        val limit = if (declaredAttachment) ZipPreflightDeclared.MAX_ATTACHMENT_SIZE else ZipPreflightDeclared.MAX_STRUCTURED_SIZE
        if (uncompressed > limit) return RestoreViolation.ENTRY_SIZE_LIMIT
        actualUncompressed += uncompressed
        if (actualUncompressed > declaredTotalUncompressed) return RestoreViolation.AGGREGATE_SIZE_LIMIT
        if (spaceBudget <= 0) return RestoreViolation.SPACE_BUDGET
        if (actualUncompressed > minOf(ZipPreflightDeclared.MAX_AGGREGATE_SIZE, spaceBudget)) {
            return RestoreViolation.AGGREGATE_SIZE_LIMIT
        }
        return null
    }

    fun totalUncompressed(): Long = actualUncompressed
}

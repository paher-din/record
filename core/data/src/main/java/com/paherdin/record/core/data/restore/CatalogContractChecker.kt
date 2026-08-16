package com.paherdin.record.core.data.restore

import com.paherdin.record.core.data.RestoreViolation

/**
 * Catalog-contract framework checks (spec 4.4, plan Task 2). The current v2
 * schema has no catalog tables, so P5 proves the mechanism: unknown logical
 * types reject, declared and encountered catalog normalization versions are
 * checked against the reader's supported set, and the encountered per-record
 * set must exactly equal the manifest summary (omitted, extra, or unsupported
 * versions reject). Pure Kotlin.
 */
internal object CatalogContractChecker {

    fun checkLogicalType(type: String, supportedLogicalTypes: Set<String>): RestoreViolation? =
        if (type in supportedLogicalTypes) null else RestoreViolation.UNKNOWN_LOGICAL_TYPE

    fun checkManifestVersions(declared: List<Int>, supported: Set<Int>): RestoreViolation? {
        for (v in declared) if (v !in supported) return RestoreViolation.NAME_KEY_VERSION_UNSUPPORTED
        return null
    }

    /** The encountered per-record version set must exactly match the manifest summary. */
    fun checkEncounteredVersions(declared: List<Int>, encountered: Set<Int>): RestoreViolation? {
        if (encountered != declared.toSet()) return RestoreViolation.NAME_KEY_VERSION_UNSUPPORTED
        return null
    }
}

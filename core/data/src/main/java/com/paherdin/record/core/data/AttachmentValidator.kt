package com.paherdin.record.core.data

import java.io.InputStream
import java.security.MessageDigest

/**
 * Pure attachment validation and classification (plan Task 2, spec 4.7 and
 * criteria 2/3/8). No `android.*` call in this file: the Android bounds probe
 * (`AttachmentImageProbe`) supplies the decodability/decoded-MIME result and
 * this classifier decides. All comparisons use normalized MIME strings and
 * exact byte sizes; the SHA-256 helper is the only hashing path and callers
 * never trust a caller-supplied hash.
 */
internal object AttachmentValidator {

    /** Recorded PoC size limit; the product value is M3's (spec 4.8). */
    const val POC_SIZE_LIMIT_BYTES: Long = 32L * 1024 * 1024

    /** Recorded PoC image-type set; the product whitelist is M3's (spec 4.8). */
    val POC_MIME_SET: Set<String> = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

    /** Result of the platform bounds probe, passed in so this file stays pure. */
    internal data class Probe(val decodable: Boolean, val decodedMime: String?)

    /**
     * Normalize a declared or decoded MIME type: trim surrounding whitespace,
     * drop any `;` parameters, lowercase with `Locale.ROOT` (plan Task 2).
     */
    fun normalizeMime(raw: String): String {
        val trimmed = raw.trim()
        val withoutParams = trimmed.substringBefore(';').trim()
        return withoutParams.lowercase(java.util.Locale.ROOT)
    }

    /**
     * Deterministic classification precedence (first match wins, plan Task 2):
     * declared MIME outside the PoC set → `MIME_TYPE_NOT_ACCEPTED`; size over
     * the PoC limit → `SIZE_LIMIT_EXCEEDED`; undecodable → `UNDECODABLE`;
     * decoded MIME outside the set or different from the declared MIME →
     * `MIME_TYPE_NOT_ACCEPTED`.
     */
    fun classify(declaredMimeRaw: String, sizeBytes: Long, probe: Probe): Classification {
        val declared = normalizeMime(declaredMimeRaw)
        if (declared !in POC_MIME_SET) return Rejected(AttachmentViolation.MIME_TYPE_NOT_ACCEPTED)
        if (sizeBytes > POC_SIZE_LIMIT_BYTES) return Rejected(AttachmentViolation.SIZE_LIMIT_EXCEEDED)
        if (!probe.decodable || probe.decodedMime == null) return Rejected(AttachmentViolation.UNDECODABLE)
        val decoded = normalizeMime(probe.decodedMime)
        if (decoded !in POC_MIME_SET || decoded != declared) {
            return Rejected(AttachmentViolation.MIME_TYPE_NOT_ACCEPTED)
        }
        return Accepted
    }

    /** SHA-256 of `bytes` as a lowercase hex string (the content identity). */
    fun sha256(bytes: ByteArray): String = sha256Of(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** SHA-256 of `stream` as a lowercase hex string, streaming 64 KiB chunks. */
    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return sha256Of(digest.digest())
    }

    private fun sha256Of(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(java.util.Locale.ROOT, b))
        return sb.toString()
    }
}

internal sealed interface Classification

internal data object Accepted : Classification

internal data class Rejected(val violation: AttachmentViolation) : Classification



/**
 * The deduplication decision table (plan Task 2, spec 4.2, ADR 0008): given
 * the canonical-row state and the verified blob state, decide reuse / move /
 * refresh / restore / corruption — never silently overwrite and never create
 * a second Attachment row.
 */
internal enum class DedupDecision {
    /** No row, no target: atomic move then create with `deletedAt = ingestedAt`. */
    MOVE_AND_CREATE,

    /** No row but a verified orphan blob occupies the target: adopt it and create the row. */
    ADOPT_VERIFIED_BLOB,

    /** Active/linked row, blob verifies: reuse the identity, `deletedAt` stays null. */
    REUSE_ACTIVE,

    /** Zero-link pending row, blob verifies: reuse and refresh `deletedAt = ingestedAt`. */
    REUSE_REFRESH,

    /** Zero-link pending row, blob absent (expected cleanup window): restore the validated blob and refresh. */
    RESTORE_REFRESH,

    /** Active/linked row without bytes: corruption, never cleaned or overwritten. */
    CORRUPT_SPLIT,

    /** Row exists but the blob does not verify: corruption. */
    CORRUPT_MISMATCH,

    /** No row but the target holds unverifiable bytes: conflicting metadata corruption. */
    CORRUPT_CONFLICT,
}

internal object AttachmentDedupDecider {

    internal enum class RowState { ACTIVE, ZERO_LINK_PENDING }

    internal enum class TargetState { ABSENT, VERIFIED, MISMATCHED }

    fun decide(row: RowState?, target: TargetState): DedupDecision = when {
        row == null -> when (target) {
            TargetState.ABSENT -> DedupDecision.MOVE_AND_CREATE
            // A verified orphan blob left by a failed owner transaction is
            // adopted, never reported as corruption (spec 4.5 orphan rules).
            TargetState.VERIFIED -> DedupDecision.ADOPT_VERIFIED_BLOB
            TargetState.MISMATCHED -> DedupDecision.CORRUPT_CONFLICT
        }
        row == RowState.ACTIVE -> when (target) {
            TargetState.VERIFIED -> DedupDecision.REUSE_ACTIVE
            TargetState.ABSENT -> DedupDecision.CORRUPT_SPLIT
            TargetState.MISMATCHED -> DedupDecision.CORRUPT_MISMATCH
        }
        else -> when (target) {
            TargetState.VERIFIED -> DedupDecision.REUSE_REFRESH
            TargetState.ABSENT -> DedupDecision.RESTORE_REFRESH
            TargetState.MISMATCHED -> DedupDecision.CORRUPT_MISMATCH
        }
    }
}

/**
 * Orphan classification for the cleaner (plan Task 2, spec 4.5 and criterion
 * 8): every deletable residue needs a valid grace-period input, and a
 * missing/invalid timestamp fails closed and retains the residue for explicit
 * recovery rather than deleting it on a guess.
 */
internal object AttachmentOrphanClassifier {

    internal data class Observation(
        val inTempDir: Boolean,
        val hasCanonicalRow: Boolean,
        val rowPending: Boolean,
        val blobExists: Boolean,
        val timestampValid: Boolean,
    )

    internal enum class Class {
        /** `tmp/` staging residue with a valid timestamp: safe cleanup. */
        TEMP_PARTIAL,

        /** Blob with no canonical row (moved-but-uncommitted / failed owner save): cleanup after verification + grace. */
        MOVED_UNCOMMITTED,

        /** Zero-link pending row with its blob: row and blob are removed together after grace. */
        ZERO_LINK_ROW,

        /** Zero-link pending row without a blob: the expected delete crash window; the cleaner completes metadata deletion only. */
        EXPECTED_DELETE_WINDOW,

        /** Active/linked row without a blob: `METADATA_BLOB_SPLIT` corruption; the cleaner never touches it. */
        LINKED_MISSING_CORRUPTION,

        /** Active row with its blob: not an orphan at all. */
        NOT_ORPHAN,

        /** Missing/invalid grace-period input: fail closed, retain for explicit recovery. */
        FAIL_CLOSED,
    }

    fun classify(o: Observation): Class = when {
        o.inTempDir && !o.hasCanonicalRow ->
            if (o.timestampValid) Class.TEMP_PARTIAL else Class.FAIL_CLOSED
        !o.inTempDir && !o.hasCanonicalRow && o.blobExists ->
            if (o.timestampValid) Class.MOVED_UNCOMMITTED else Class.FAIL_CLOSED
        o.hasCanonicalRow && o.rowPending && o.blobExists ->
            if (o.timestampValid) Class.ZERO_LINK_ROW else Class.FAIL_CLOSED
        o.hasCanonicalRow && o.rowPending && !o.blobExists -> Class.EXPECTED_DELETE_WINDOW
        o.hasCanonicalRow && !o.rowPending && !o.blobExists -> Class.LINKED_MISSING_CORRUPTION
        else -> Class.NOT_ORPHAN
    }
}

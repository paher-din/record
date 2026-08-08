package com.paherdin.record.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Orphan classification rules (plan Task 2, spec criterion 8 and section
 * 4.5): TEMP partials, moved-but-uncommitted blobs, failed-owner orphans,
 * zero-link rows, the expected delete crash window, and the fail-closed rule
 * for missing/invalid timestamps (residue retained, never deleted on a guess).
 */
class AttachmentOrphanClassifierTest {

    private val c = AttachmentOrphanClassifier

    private fun obs(
        inTempDir: Boolean = false,
        hasCanonicalRow: Boolean = false,
        rowPending: Boolean = false,
        blobExists: Boolean = false,
        timestampValid: Boolean = true,
    ) = AttachmentOrphanClassifier.Observation(inTempDir, hasCanonicalRow, rowPending, blobExists, timestampValid)

    // ---- temp staging residue ----

    @Test
    fun tempPartialWithValidTimestampIsSafeCleanup() {
        assertEquals(AttachmentOrphanClassifier.Class.TEMP_PARTIAL, c.classify(obs(inTempDir = true, timestampValid = true)))
    }

    @Test
    fun tempPartialWithInvalidTimestampFailsClosed() {
        assertEquals(AttachmentOrphanClassifier.Class.FAIL_CLOSED, c.classify(obs(inTempDir = true, timestampValid = false)))
    }

    // ---- moved-but-uncommitted blob (no row) ----

    @Test
    fun uncommittedBlobWithValidTimestampIsCleanupEligible() {
        assertEquals(AttachmentOrphanClassifier.Class.MOVED_UNCOMMITTED, c.classify(obs(blobExists = true, timestampValid = true)))
    }

    @Test
    fun uncommittedBlobWithInvalidTimestampFailsClosed() {
        assertEquals(AttachmentOrphanClassifier.Class.FAIL_CLOSED, c.classify(obs(blobExists = true, timestampValid = false)))
    }

    // ---- zero-link canonical row ----

    @Test
    fun pendingRowWithBlobIsZeroLinkRowCleanup() {
        // Row and blob are removed together, never separately (spec 4.5).
        assertEquals(AttachmentOrphanClassifier.Class.ZERO_LINK_ROW, c.classify(obs(hasCanonicalRow = true, rowPending = true, blobExists = true)))
    }

    @Test
    fun pendingRowWithoutBlobIsExpectedDeleteWindow() {
        // ADR 0008: non-null deletedAt + absent blob is the crash window after
        // physical deletion before metadata commit; the cleaner completes the
        // metadata deletion, never reports corruption.
        assertEquals(AttachmentOrphanClassifier.Class.EXPECTED_DELETE_WINDOW, c.classify(obs(hasCanonicalRow = true, rowPending = true, blobExists = false)))
    }

    @Test
    fun activeRowWithoutBlobIsCorruptionNotCleanup() {
        // A linked/active row whose blob is absent is METADATA_BLOB_SPLIT;
        // the cleaner never touches it.
        assertEquals(AttachmentOrphanClassifier.Class.LINKED_MISSING_CORRUPTION, c.classify(obs(hasCanonicalRow = true, rowPending = false, blobExists = false)))
    }

    @Test
    fun activeRowWithBlobIsNotAnOrphan() {
        assertEquals(AttachmentOrphanClassifier.Class.NOT_ORPHAN, c.classify(obs(hasCanonicalRow = true, rowPending = false, blobExists = true)))
    }

    // ---- fail-closed dominates every deletable class ----

    @Test
    fun missingTimestampFailsClosedEvenForPendingRow() {
        assertEquals(AttachmentOrphanClassifier.Class.FAIL_CLOSED, c.classify(obs(hasCanonicalRow = true, rowPending = true, blobExists = true, timestampValid = false)))
    }
}

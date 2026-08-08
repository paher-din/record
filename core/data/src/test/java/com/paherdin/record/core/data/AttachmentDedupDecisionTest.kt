package com.paherdin.record.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every row of the deduplication decision table (plan Task 2, spec criterion
 * 3 and section 4.2): canonical-row state × blob state → reuse / move /
 * refresh / restore / corruption classification, including the ADR 0008
 * expected zero-link missing-blob recovery versus active-row corruption split.
 */
class AttachmentDedupDecisionTest {

    private val d = AttachmentDedupDecider

    private fun decide(
        row: AttachmentDedupDecider.RowState?,
        target: AttachmentDedupDecider.TargetState,
    ) = d.decide(row, target)

    // ---- no canonical row ----

    @Test
    fun noRowNoTargetMovesAndCreates() {
        assertEquals(DedupDecision.MOVE_AND_CREATE, decide(null, AttachmentDedupDecider.TargetState.ABSENT))
    }

    @Test
    fun noRowVerifiedOrphanBlobIsAdopted() {
        // A verified orphan blob left by a failed owner transaction is adopted
        // and the row is created; it is never reported as corruption.
        assertEquals(
            DedupDecision.ADOPT_VERIFIED_BLOB,
            decide(null, AttachmentDedupDecider.TargetState.VERIFIED),
        )
    }

    @Test
    fun noRowUnverifiableBlobIsConflictingMetadataCorruption() {
        assertEquals(
            DedupDecision.CORRUPT_CONFLICT,
            decide(null, AttachmentDedupDecider.TargetState.MISMATCHED),
        )
    }

    // ---- active/linked row (deletedAt == null) ----

    @Test
    fun activeRowVerifiedBlobReusesIdentityWithoutRefreshingMarker() {
        assertEquals(
            DedupDecision.REUSE_ACTIVE,
            decide(AttachmentDedupDecider.RowState.ACTIVE, AttachmentDedupDecider.TargetState.VERIFIED),
        )
    }

    @Test
    fun activeRowMissingBlobIsCorruption() {
        assertEquals(
            DedupDecision.CORRUPT_SPLIT,
            decide(AttachmentDedupDecider.RowState.ACTIVE, AttachmentDedupDecider.TargetState.ABSENT),
        )
    }

    @Test
    fun activeRowMismatchedBlobIsCorruption() {
        assertEquals(
            DedupDecision.CORRUPT_MISMATCH,
            decide(AttachmentDedupDecider.RowState.ACTIVE, AttachmentDedupDecider.TargetState.MISMATCHED),
        )
    }

    // ---- zero-link pending row (deletedAt != null) ----

    @Test
    fun pendingRowVerifiedBlobReusesAndRefreshesGracePeriod() {
        // ADR 0008: fresh successful ingestion of valid existing zero-link
        // content refreshes deletedAt = ingestedAt so the reported preparation
        // always receives a full grace period.
        assertEquals(
            DedupDecision.REUSE_REFRESH,
            decide(AttachmentDedupDecider.RowState.ZERO_LINK_PENDING, AttachmentDedupDecider.TargetState.VERIFIED),
        )
    }

    @Test
    fun pendingRowMissingBlobRestoresThenRefreshes() {
        // Expected cleanup crash window: blob absent + metadata present is
        // restored by fresh validated ingestion, never reported as corruption.
        assertEquals(
            DedupDecision.RESTORE_REFRESH,
            decide(AttachmentDedupDecider.RowState.ZERO_LINK_PENDING, AttachmentDedupDecider.TargetState.ABSENT),
        )
    }

    @Test
    fun pendingRowMismatchedBlobIsCorruption() {
        // A zero-link pending row whose bytes do not verify is data
        // inconsistency, not an expected cleanup window.
        assertEquals(
            DedupDecision.CORRUPT_MISMATCH,
            decide(AttachmentDedupDecider.RowState.ZERO_LINK_PENDING, AttachmentDedupDecider.TargetState.MISMATCHED),
        )
    }
}

package com.paherdin.record.core.data.search

import org.junit.Assert.assertEquals
import org.junit.Test
import com.paherdin.record.core.data.search.SelectionRule.CandidateEvidence
import com.paherdin.record.core.data.search.SelectionRule.Selection

/**
 * Tech PoC P4 (plan Task 5): every eligibility and tie-break boundary of the
 * frozen selection rule — exact 300 ms / one nanosecond over, CV 0.049/0.05,
 * symmetric p95 0.099/0.10, one/both ineligible, and the final tie.
 */
class SelectionRuleTest {

    private fun like(correct: Boolean = true, p95: Long = 100L, cv: Double = 0.02) =
        CandidateEvidence(correct, p95, cv)

    private fun fts(correct: Boolean = true, p95: Long = 100L, cv: Double = 0.02) =
        CandidateEvidence(correct, p95, cv)

    @Test
    fun bothEligibleAndNearlyIdenticalTiesToLike() {
        assertEquals(Selection.Like, SelectionRule.selectCandidate(like(), fts()))
    }

    @Test
    fun exactThreeHundredMillisecondsIsEligible() {
        assertEquals(
            Selection.Like,
            SelectionRule.selectCandidate(
                like(p95 = SelectionRule.ABSOLUTE_P95_LIMIT_NS),
                fts(correct = false),
            ),
        )
    }

    @Test
    fun oneNanosecondOverTheAbsoluteLimitIsIneligible() {
        assertEquals(
            Selection.Fts,
            SelectionRule.selectCandidate(
                like(p95 = SelectionRule.ABSOLUTE_P95_LIMIT_NS + 1),
                fts(),
            ),
        )
    }

    @Test
    fun incorrectCandidateIsIneligibleEvenWhenFast() {
        assertEquals(
            Selection.Fts,
            SelectionRule.selectCandidate(
                like(correct = false, p95 = 1L),
                fts(),
            ),
        )
    }

    @Test
    fun bothIneligibleFailsP4() {
        assertEquals(
            Selection.P4Failed,
            SelectionRule.selectCandidate(
                like(correct = false),
                fts(p95 = SelectionRule.ABSOLUTE_P95_LIMIT_NS + 1),
            ),
        )
    }

    @Test
    fun cvBoundaryAtZeroPointZeroFive() {
        // Below 0.05 falls through to the p95 comparison; at 0.05 CV decides.
        // 0.5 is binary-exact, so 0.55 - 0.5 = 0.0500…4 > 0.05 and
        // 0.5499 - 0.5 = 0.0499… < 0.05 are both predictable in double.
        val below = SelectionRule.selectCandidate(like(cv = 0.5), fts(cv = 0.5499))
        assertEquals("abs difference 0.0499 falls through to p95 tie → LIKE", Selection.Like, below)
        val at = SelectionRule.selectCandidate(like(cv = 0.5), fts(cv = 0.55))
        assertEquals("abs difference 0.05 chooses lower CV", Selection.Like, at)
        val reversed = SelectionRule.selectCandidate(like(cv = 0.55), fts(cv = 0.5))
        assertEquals("higher-CV LIKE loses at 0.05", Selection.Fts, reversed)
    }

    @Test
    fun p95RelativeBoundaryAtTenPercent() {
        val likeP95 = 100_000_000L
        val ftsP95 = 110_000_000L
        // (110-100)/110 = 0.0909… < 0.10 → tie → LIKE.
        assertEquals(Selection.Like, SelectionRule.selectCandidate(like(p95 = likeP95), fts(p95 = ftsP95)))
        // (111-100)/111 = 0.0991… < 0.10 → tie → LIKE.
        assertEquals(Selection.Like, SelectionRule.selectCandidate(like(p95 = likeP95), fts(p95 = 111_000_000L)))
        // (112-100)/112 = 0.1071… ≥ 0.10 → lower p95 (LIKE) wins.
        assertEquals(Selection.Like, SelectionRule.selectCandidate(like(p95 = likeP95), fts(p95 = 112_000_000L)))
        // Reversed: FTS lower p95 wins at ≥ 0.10 symmetric relative difference.
        assertEquals(Selection.Fts, SelectionRule.selectCandidate(like(p95 = 112_000_000L), fts(p95 = likeP95)))
    }
}

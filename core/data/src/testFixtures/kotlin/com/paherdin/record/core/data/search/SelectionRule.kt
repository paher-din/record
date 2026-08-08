package com.paherdin.record.core.data.search

/**
 * Tech PoC P4 (plan Task 5) — the frozen selection rule over candidate-level
 * metrics (spec 4.5): a candidate is eligible only when every actual first
 * page equals its ordered expected first-page ID list (exact correctness) and
 * its comparison p95 (maximum per-query p95) is at most 300 ms. Both eligible
 * compare `abs(cvL - cvF) >= 0.05`, then the symmetric relative p95 difference
 * `abs(p95L - p95F) / max(p95L, p95F) >= 0.10`; still tied, choose paged `LIKE`.
 * A both-ineligible outcome is a failed P4, not a default selection.
 */
object SelectionRule {

    const val ABSOLUTE_P95_LIMIT_NS = 300_000_000L
    private const val CV_DIFFERENCE_THRESHOLD = 0.05
    private const val P95_RELATIVE_DIFFERENCE_THRESHOLD = 0.10

    data class CandidateEvidence(
        val correct: Boolean,
        val comparisonP95Ns: Long,
        val comparisonCv: Double,
    )

    sealed interface Selection {
        data object P4Failed : Selection
        data object Like : Selection
        data object Fts : Selection
    }

    fun selectCandidate(like: CandidateEvidence, fts: CandidateEvidence): Selection {
        val likeEligible = like.correct && like.comparisonP95Ns <= ABSOLUTE_P95_LIMIT_NS
        val ftsEligible = fts.correct && fts.comparisonP95Ns <= ABSOLUTE_P95_LIMIT_NS
        return when {
            !likeEligible && !ftsEligible -> Selection.P4Failed
            likeEligible && !ftsEligible -> Selection.Like
            !likeEligible && ftsEligible -> Selection.Fts
            else -> compareBothEligible(like, fts)
        }
    }

    private fun compareBothEligible(like: CandidateEvidence, fts: CandidateEvidence): Selection {
        val cvDifference = kotlin.math.abs(like.comparisonCv - fts.comparisonCv)
        if (cvDifference >= CV_DIFFERENCE_THRESHOLD) {
            return if (like.comparisonCv < fts.comparisonCv) Selection.Like else Selection.Fts
        }
        val p95Relative = kotlin.math.abs(like.comparisonP95Ns - fts.comparisonP95Ns)
            .toDouble() / kotlin.math.max(like.comparisonP95Ns, fts.comparisonP95Ns)
        if (p95Relative >= P95_RELATIVE_DIFFERENCE_THRESHOLD) {
            return if (like.comparisonP95Ns < fts.comparisonP95Ns) Selection.Like else Selection.Fts
        }
        return Selection.Like
    }
}

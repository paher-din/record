package com.paherdin.record.core.data.timeline

/**
 * Tech PoC P6 — host-only binding metric formulas (plan "Frozen metric
 * formulas", spec §4.4–4.7). Integer-exact throughout: samples and amounts
 * stay `Long`, fractions use basis points, and the one-sided regression gate
 * uses cross-multiplication so no `Float`/`Double` appears and the 10%
 * boundary is exact. Inputs are unit-consistent integers (ms, KiB, counts) —
 * the rank and ratio comparisons are unit-agnostic.
 */
object TimelineMetricMath {

    const val REGRESSION_ALLOWANCE_PERCENT = 10L

    /**
     * Nearest-rank percentile over an ascending-sorted sample list: one-based
     * rank `ceil(percent/100 * n)`. For 30 samples this yields p50 rank 15
     * and p95 rank 29.
     */
    fun nearestRankPercentile(ascendingSamples: List<Long>, percent: Int): Long {
        require(ascendingSamples.isNotEmpty()) { "nearest-rank requires at least one sample" }
        require(percent in 1..100) { "percent must be in 1..100, was $percent" }
        val rank = (percent.toLong() * ascendingSamples.size + 99) / 100 // ceil, one-based
        return ascendingSamples[(rank - 1).toInt()]
    }

    /**
     * Jank fraction as basis points (fraction * 10_000) over every measured
     * frame: `count(frameOverrunMs > 0) / count(all samples)`. Integer-exact;
     * 2% is 200.
     */
    fun jankBasisPoints(overrunCount: Long, totalCount: Long): Long {
        require(totalCount > 0) { "jank denominator must be positive, was $totalCount" }
        require(overrunCount in 0..totalCount) { "overrunCount=$overrunCount out of range for totalCount=$totalCount" }
        return overrunCount * 10_000 / totalCount
    }

    /** Peak anonymous RSS across samples, in KiB. */
    fun maxRssAnonKb(samples: List<Long>): Long {
        require(samples.isNotEmpty()) { "maxRssAnonKb requires at least one sample" }
        return samples.max()
    }

    /**
     * One-sided regression gate for a p95 (query or frame): `new <= baseline`
     * always passes; only when `new > baseline` is
     * `(new - baseline) / max(new, baseline)` compared with the allowance —
     * greater than the allowance fails. Cross-multiplication keeps the 10%
     * boundary exact: a ratio of exactly 0.10 passes, just over fails.
     */
    fun oneSidedRegressionPasses(
        baseline: Long,
        current: Long,
        allowancePercent: Long = REGRESSION_ALLOWANCE_PERCENT,
    ): Boolean {
        if (current <= baseline) return true
        val diff = current - baseline
        val denominator = maxOf(current, baseline)
        return allowancePercent * denominator >= 100 * diff
    }
}

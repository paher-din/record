package com.paherdin.record.core.data.search

import kotlin.math.sqrt

/**
 * Tech PoC P4 (plan Task 5) — the frozen metric formulas.
 *
 * `MeasurementStats.from(samplesNs)` requires exactly 30 positive samples.
 * After ascending sort, p50 is element 15 and p95 element 29 in one-based
 * notation (Kotlin indexes 14 and 28); CV is population standard deviation
 * divided by the positive arithmetic mean.
 *
 * `CandidateMetrics.comparisonP95Ns` and `comparisonCv` are the maximum
 * per-query values.
 */
object MeasurementStats {

    data class Stats(val p50Ns: Long, val p95Ns: Long, val cv: Double)

    fun from(samplesNs: List<Long>): Stats {
        require(samplesNs.size == 30) { "exactly 30 samples required, got ${samplesNs.size}" }
        require(samplesNs.all { it > 0 }) { "all samples must be positive" }
        val sorted = samplesNs.sorted()
        val p50 = sorted[14]
        val p95 = sorted[28]
        val mean = sorted.average()
        val variance = sorted.sumOf { (it - mean) * (it - mean) } / sorted.size
        return Stats(p50Ns = p50, p95Ns = p95, cv = sqrt(variance) / mean)
    }
}

data class CandidateMetrics(
    val perQueryP95Ns: List<Long>,
    val perQueryCv: List<Double>,
) {
    val comparisonP95Ns: Long = perQueryP95Ns.max()
    val comparisonCv: Double = perQueryCv.max()
}

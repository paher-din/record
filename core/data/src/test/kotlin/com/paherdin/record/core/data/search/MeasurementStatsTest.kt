package com.paherdin.record.core.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tech PoC P4 (plan Task 5): the frozen metric formulas — exactly 30 positive
 * samples, nearest-rank p50 (Kotlin index 14) and p95 (index 28), population
 * CV, and candidate maxima.
 */
class MeasurementStatsTest {

    @Test
    fun exactlyThirtyPositiveSamplesAreRequired() {
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementStats.from((1L..29L).toList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementStats.from((1L..31L).toList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementStats.from((0L..29L).toList()) // zero sample present
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementStats.from((1L..29L).toList() + (-1L)) // negative sample
        }
    }

    @Test
    fun nearestRankIndexesAreFourteenAndTwentyEight() {
        // Samples 1..30 ns ascending: p50 is element 15 (value 15), p95 element 29 (value 29).
        val stats = MeasurementStats.from((1L..30L).toList())
        assertEquals(15L, stats.p50Ns)
        assertEquals(29L, stats.p95Ns)
    }

    @Test
    fun populationCvUsesPopulationStandardDeviation() {
        // Samples 10,10,10: zero variance, CV zero.
        assertEquals(0.0, MeasurementStats.from(List(30) { 10L }).cv, 1e-12)
        // Samples 1..30: mean 15.5; population variance = sum((x-15.5)^2)/30.
        val samples = (1L..30L).toList()
        val mean = 15.5
        val variance = samples.sumOf { (it - mean) * (it - mean) } / 30.0
        val expected = kotlin.math.sqrt(variance) / mean
        assertEquals(expected, MeasurementStats.from(samples).cv, 1e-12)
    }

    @Test
    fun candidateMetricsTakePerQueryMaxima() {
        val metrics = CandidateMetrics(
            perQueryP95Ns = listOf(100L, 250L, 90L),
            perQueryCv = listOf(0.05, 0.02, 0.07),
        )
        assertEquals(250L, metrics.comparisonP95Ns)
        assertEquals(0.07, metrics.comparisonCv, 1e-12)
    }
}

package com.paherdin.record.core.data.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tech PoC P6 (plan Task 2): boundary tests for the host-only metric formulas —
 * nearest-rank positions 15/29, pooled-frame p95, all-sample positive-overrun
 * fraction, memory maximum, and the one-sided regression gate (improvement,
 * equality, exactly 10%, and just over 10%).
 */
class TimelineMetricMathTest {

    @Test
    fun nearestRankPositionsFifteenAndTwentyNineForThirtySamples() {
        val samples = (1L..30L).toList()
        assertEquals(15L, TimelineMetricMath.nearestRankPercentile(samples, 50))
        assertEquals(29L, TimelineMetricMath.nearestRankPercentile(samples, 95))
    }

    @Test
    fun pooledFrameP95UsesNearestRankOverThePooledSet() {
        val pooled = (1L..100L).toList()
        assertEquals(95L, TimelineMetricMath.nearestRankPercentile(pooled, 95))
        val tiny = (1L..5L).toList() // rank ceil(0.95*5) = 5
        assertEquals(5L, TimelineMetricMath.nearestRankPercentile(tiny, 95))
    }

    @Test
    fun nearestRankHandlesSingleSampleAndGuards() {
        assertEquals(42L, TimelineMetricMath.nearestRankPercentile(listOf(42L), 50))
        assertEquals(42L, TimelineMetricMath.nearestRankPercentile(listOf(42L), 95))
        assertFails { TimelineMetricMath.nearestRankPercentile(emptyList(), 50) }
        assertFails { TimelineMetricMath.nearestRankPercentile(listOf(1L), 0) }
        assertFails { TimelineMetricMath.nearestRankPercentile(listOf(1L), 101) }
    }

    @Test
    fun jankIsPositiveOverrunFractionOverAllFrames() {
        assertEquals(0L, TimelineMetricMath.jankBasisPoints(0, 100))
        assertEquals(200L, TimelineMetricMath.jankBasisPoints(2, 100)) // exactly 2%
        assertEquals(10_000L, TimelineMetricMath.jankBasisPoints(100, 100)) // 100% jank
        assertTrue(TimelineMetricMath.jankBasisPoints(3, 100) > 200L)
        assertFails { TimelineMetricMath.jankBasisPoints(101, 100) }
        assertFails { TimelineMetricMath.jankBasisPoints(0, 0) }
    }

    @Test
    fun memoryIsMaximumOverSamples() {
        assertEquals(123L, TimelineMetricMath.maxRssAnonKb(listOf(1L, 123L, 5L, 88L)))
        assertEquals(0L, TimelineMetricMath.maxRssAnonKb(listOf(0L, 0L)))
        assertFails { TimelineMetricMath.maxRssAnonKb(emptyList()) }
    }

    @Test
    fun oneSidedRegressionImprovementAlwaysPasses() {
        assertTrue(TimelineMetricMath.oneSidedRegressionPasses(baseline = 1000, current = 999))
        assertTrue(TimelineMetricMath.oneSidedRegressionPasses(baseline = 1000, current = 500))
        assertTrue(TimelineMetricMath.oneSidedRegressionPasses(baseline = 60, current = 1))
    }

    @Test
    fun oneSidedRegressionEqualityPasses() {
        assertTrue(TimelineMetricMath.oneSidedRegressionPasses(baseline = 1000, current = 1000))
        assertTrue(TimelineMetricMath.oneSidedRegressionPasses(baseline = 60, current = 60))
    }

    @Test
    fun oneSidedRegressionExactlyTenPercentPasses() {
        // (1000 - 900) / max(1000, 900) = 0.10 exactly -> must pass (only > 0.10 fails).
        assertTrue(TimelineMetricMath.oneSidedRegressionPasses(baseline = 900, current = 1000))
    }

    @Test
    fun oneSidedRegressionJustOverTenPercentFails() {
        // (1001 - 900) / max(1001, 900) = 0.101 > 0.10 -> must fail.
        assertFalse(TimelineMetricMath.oneSidedRegressionPasses(baseline = 900, current = 1001))
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}

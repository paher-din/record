package com.paherdin.record.core.data

import com.paherdin.record.core.data.restore.ZipPreflightDeclared
import com.paherdin.record.core.data.restore.ZipStreamingPreflight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZipStreamingPreflightTest {

    private fun preflight(
        declaredEntries: Long = 2,
        declaredPerEntry: List<Long> = listOf(10, 20),
        declaredAttachment: List<Boolean> = listOf(false, false),
        declaredTotalUncompressed: Long = 30,
        space: Long = 1L * 1024 * 1024 * 1024 * 1024,
    ) = ZipStreamingPreflight(declaredEntries, declaredPerEntry, declaredAttachment, declaredTotalUncompressed, space)

    @Test
    fun withinDeclaredPasses() {
        val p = preflight()
        assertNull(p.feedEntry(0, 10))
        assertNull(p.feedEntry(1, 20))
        assertEquals(30L, p.totalUncompressed())
    }

    @Test
    fun actualEntryOverDeclaredCountFails() {
        val p = preflight(declaredEntries = 1, declaredPerEntry = listOf(10), declaredTotalUncompressed = 10)
        assertNull(p.feedEntry(0, 10))
        assertEquals(RestoreViolation.ENTRY_COUNT_LIMIT, p.feedEntry(1, 10))
    }

    @Test
    fun declaredUnderActualPerEntryFails() {
        val p = preflight(declaredPerEntry = listOf(10, 20))
        assertEquals(RestoreViolation.SIZE_UNDER_DECLARED, p.feedEntry(0, 11))
    }

    @Test
    fun perEntryAttachmentLimitIsEnforced() {
        val at = ZipPreflightDeclared.MAX_ATTACHMENT_SIZE
        val p = preflight(declaredPerEntry = listOf(at + 1), declaredAttachment = listOf(true), declaredTotalUncompressed = at + 1)
        assertEquals(RestoreViolation.ENTRY_SIZE_LIMIT, p.feedEntry(0, at + 1))
    }

    @Test
    fun aggregateOverDeclaredTotalFails() {
        val p = preflight(declaredPerEntry = listOf(10, 20), declaredTotalUncompressed = 29)
        assertNull(p.feedEntry(0, 10))
        assertEquals(RestoreViolation.AGGREGATE_SIZE_LIMIT, p.feedEntry(1, 20))
    }

    @Test
    fun nonPositiveSpaceBudgetFails() {
        val p = preflight(space = 1L * 1024 * 1024 * 1024)
        assertEquals(RestoreViolation.SPACE_BUDGET, p.feedEntry(0, 10))
    }

    @Test
    fun declaredUnderActualAggregateDetectsZipBomb() {
        // A stream that materializes more bytes than declared is the streaming
        // zip-bomb signal, independent of the central directory.
        val p = preflight(declaredPerEntry = listOf(10, 20), declaredTotalUncompressed = 29)
        assertNull(p.feedEntry(0, 10))
        assertEquals(RestoreViolation.AGGREGATE_SIZE_LIMIT, p.feedEntry(1, 20))
    }
}

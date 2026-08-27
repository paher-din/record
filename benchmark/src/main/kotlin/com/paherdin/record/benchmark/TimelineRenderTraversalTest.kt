package com.paherdin.record.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Rev4 out-of-process selected-day rendered-correctness contract. */
@RunWith(AndroidJUnit4::class)
class TimelineRenderTraversalTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation = instrumentation.uiAutomation

    @Test
    fun renderedFirstDayMatchesHostSlice() {
        assertRenderedDay(
            date = FIRST_DATE,
            dayStart = FIRST_DAY_START,
            nextDayStart = FIRST_DAY_END,
            expectedHash = requiredHash(ARG_FIRST_HASH),
            expectedCount = 11,
        )
    }

    @Test
    fun renderedLastDayMatchesHostSlice() {
        assertRenderedDay(
            date = LAST_DATE,
            dayStart = LAST_DAY_START,
            nextDayStart = LAST_DAY_END,
            expectedHash = requiredHash(ARG_LAST_HASH),
            expectedCount = 11,
        )
    }

    @Test
    fun renderedEmptyDayHasNoHeaderOrCard() {
        ensurePrepared()
        val sentinels = targetSentinels()
        val previousRunId = sentinels.getString("runId")
        launchRender("render-correctness", EMPTY_DATE, EMPTY_DAY_START, EMPTY_DAY_END)
        waitForFreshRun(sentinels, "render-correctness", previousRunId)

        val (hash, error) = waitForRenderOutcome(sentinels, RENDER_TIMEOUT_MS)
        assertNull("empty-day render reported an error: $error", error)
        assertRenderedHashEqualsOracle(requiredHash(ARG_EMPTY_HASH), checkNotNull(hash))
        assertEquals("0", sentinels.getString("count"))
        assertEquals("true", sentinels.getString("ready"))
        assertEquals(EMPTY_DATE, sentinels.getString("selectedDate"))
        assertEquals(EMPTY_DAY_START.toString(), sentinels.getString("dayStart"))
        assertEquals(EMPTY_DAY_END.toString(), sentinels.getString("nextDayStart"))
        assertEquals("0", sentinels.getString("publishedDateCount"))
        assertEquals("", sentinels.getString("publishedDates"))
        assertNull("empty day must expose no header", sentinels.getString("topHeaderDate"))
        assertNull("empty day must expose no top card", sentinels.getString("topCardId"))
        assertNull("empty day must expose no bottom card", sentinels.getString("bottomCardId"))
    }

    @Test
    fun withinDayTieSwapFailsSharedEqualityAssertion() {
        ensurePrepared()
        val expectedHash = requiredHash(ARG_LAST_HASH)
        val sentinels = targetSentinels()
        val previousRunId = sentinels.getString("runId")
        launchRender("render-wrong-order", LAST_DATE, LAST_DAY_START, LAST_DAY_END)
        waitForFreshRun(sentinels, "render-wrong-order", previousRunId)

        val (hash, error) = waitForRenderOutcome(sentinels, RENDER_TIMEOUT_MS)
        assertNull("wrong-order render reported an error: $error", error)
        val actual = checkNotNull(hash)

        assertEquals("wrong-order render must still capture one selected day", "11", sentinels.getString("count"))
        assertNotEquals(
            "within-day tie swap must differ from the oracle",
            expectedHash, actual,
        )
        val thrown = try {
            assertRenderedHashEqualsOracle(expectedHash, actual)
            null
        } catch (e: AssertionError) {
            e
        }
        assertNotNull("wrong-order render must fail the shared equality assertion", thrown)
    }

    @Test
    fun publishedReadyStateNeverContainsAnotherDay() {
        ensurePrepared()
        val sentinels = targetSentinels()
        val previousRunId = sentinels.getString("runId")
        launchRender("render-correctness", LAST_DATE, LAST_DAY_START, LAST_DAY_END)
        waitForFreshRun(sentinels, "render-correctness", previousRunId)
        val (hash, error) = waitForRenderOutcome(sentinels, RENDER_TIMEOUT_MS)
        assertNull("one-day render reported an error: $error", error)
        assertRenderedHashEqualsOracle(requiredHash(ARG_LAST_HASH), checkNotNull(hash))
        assertEquals("one selected date only", "1", sentinels.getString("publishedDateCount"))
        assertEquals("no adjacent date may enter ready state", LAST_DATE, sentinels.getString("publishedDates"))
        assertEquals("11", sentinels.getString("count"))
    }

    /** Shared equality assertion used by both tests (and expected to fail for wrong-order). */
    private fun assertRenderedHashEqualsOracle(expected: String, actual: String) {
        assertEquals("rendered hash must equal oracle hash", expected, actual)
    }

    private fun assertRenderedDay(
        date: String,
        dayStart: Long,
        nextDayStart: Long,
        expectedHash: String,
        expectedCount: Int,
    ) {
        ensurePrepared()
        val sentinels = targetSentinels()
        val previousRunId = sentinels.getString("runId")
        launchRender("render-correctness", date, dayStart, nextDayStart)
        waitForFreshRun(sentinels, "render-correctness", previousRunId)
        val (hash, error) = waitForRenderOutcome(sentinels, RENDER_TIMEOUT_MS)
        assertNull("render-correctness reported an error: $error", error)
        assertRenderedHashEqualsOracle(expectedHash, checkNotNull(hash))
        assertEquals(expectedCount.toString(), sentinels.getString("count"))
        assertEquals("true", sentinels.getString("ready"))
        assertEquals("true", sentinels.getString("traceOk"))
        assertEquals(date, sentinels.getString("selectedDate"))
        assertEquals(dayStart.toString(), sentinels.getString("dayStart"))
        assertEquals(nextDayStart.toString(), sentinels.getString("nextDayStart"))
        assertEquals("1", sentinels.getString("publishedDateCount"))
        assertEquals(date, sentinels.getString("publishedDates"))
        assertEquals(date, sentinels.getString("topHeaderDate"))
    }

    private fun requiredHash(argument: String): String =
        InstrumentationRegistry.getArguments().getString(argument)
            ?: throw AssertionError("missing instrumentation argument $argument")

    private fun launchRender(mode: String, date: String, dayStart: Long, nextDayStart: Long) {
        val component = "$TARGET_APP_PACKAGE/" +
            "com.paherdin.record.benchmark.TimelineBenchmarkActivity"
        val launchOut = uiAutomation.executeShellCommand(
            "am start -W -f 0x10008000 -n $component --es mode $mode " +
                "--es selectedDate $date --el dayStart $dayStart --el nextDayStart $nextDayStart",
        ).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
        }
        check(launchOut.contains("Status: ok") || launchOut.contains("LaunchState")) {
            "target Activity failed to launch: $launchOut"
        }
    }

    private fun ensurePrepared() {
        synchronized(PREPARATION_LOCK) {
            if (prepared) return
            val sentinels = targetSentinels()
            val previousRunId = sentinels.getString("runId")
            val component = "$TARGET_APP_PACKAGE/" +
                "com.paherdin.record.benchmark.TimelineBenchmarkActivity"
            val out = shell(
                "am start -W -f 0x10008000 -n $component --es mode prepare-selected-day-contract",
            )
            check(out.contains("Status: ok") || out.contains("LaunchState")) {
                "prepare Activity failed to launch: $out"
            }
            waitForFreshRun(sentinels, "prepare-selected-day-contract", previousRunId)
            val deadline = System.currentTimeMillis() + PREPARE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                sentinels.getString("error")?.let { fail("prepare failed: $it") }
                if (sentinels.getString("prepared") == "true") {
                    prepared = true
                    return
                }
                Thread.sleep(250)
            }
            fail("selected-day shadow preparation did not finish within ${PREPARE_TIMEOUT_MS}ms")
        }
    }

    private fun targetSentinels(): TimelineSentinelReader =
        TimelineSentinelReader(::shell)

    /**
     * Waits until the launched Activity has atomically stamped its actual mode
     * and cleared stale outcomes — proving this run (not a previous launch's
     * window) owns the sentinels before any outcome is read.
     */
    private fun waitForFreshRun(
        sentinels: TimelineSentinelReader,
        mode: String,
        previousRunId: String?,
    ) {
        val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val runId = sentinels.getString("runId")
            if (runId != null && runId != previousRunId && mode == sentinels.getString("mode")) {
                return
            }
            Thread.sleep(100)
        }
        fail("launched Activity did not start a fresh run (mode '$mode') within ${LAUNCH_TIMEOUT_MS}ms")
    }

    /**
     * Polls for the fresh render outcome. Returns (hash, error) — exactly one is
     * non-null.
     */
    private fun waitForRenderOutcome(
        sentinels: TimelineSentinelReader,
        timeoutMs: Long,
    ): Pair<String?, String?> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val error = sentinels.getString("error")
            if (error != null) return null to error
            val hash = sentinels.getString("hash")
            if (hash != null) return hash to null
            Thread.sleep(250)
        }
        fail("rendered hash sentinel not exposed within ${timeoutMs}ms")
        throw AssertionError("unreachable")
    }

    private fun shell(command: String): String =
        uiAutomation.executeShellCommand(command).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
    }

    private companion object {
        const val ARG_FIRST_HASH = "p6ExpectedFirstDayHash"
        const val ARG_LAST_HASH = "p6ExpectedLastDayHash"
        const val ARG_EMPTY_HASH = "p6ExpectedEmptyDayHash"
        const val FIRST_DATE = "2023-01-01"
        const val LAST_DATE = "2025-09-26"
        const val EMPTY_DATE = "2022-12-31"
        const val FIRST_DAY_START = 1_672_502_400_000L
        const val FIRST_DAY_END = 1_672_588_800_000L
        const val LAST_DAY_START = 1_758_816_000_000L
        const val LAST_DAY_END = 1_758_902_400_000L
        const val EMPTY_DAY_START = 1_672_416_000_000L
        const val EMPTY_DAY_END = 1_672_502_400_000L
        const val RENDER_TIMEOUT_MS = 120_000L
        const val PREPARE_TIMEOUT_MS = 300_000L
        const val LAUNCH_TIMEOUT_MS = 60_000L
        val PREPARATION_LOCK = Any()
        @Volatile var prepared = false
    }
}

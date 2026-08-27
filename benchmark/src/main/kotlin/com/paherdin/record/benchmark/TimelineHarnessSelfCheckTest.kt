package com.paherdin.record.benchmark

import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.FileInputStream
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tech PoC P6 Task 6R4-B — device-side harness self-check (plan Task 6R4-B
 * Step 3, spec §4.6 / criterion 10). Proves, out of process, that every harness
 * control fails closed before any binding measurement: the three selected-day
 * slice-hash arguments, exact 10,000-row database count after preparation,
 * exact migration slice hashes, the bounded selected-day query statement and its
 * indexed plan, unique query-to-first-draw trace, frozen top identity, one-day-
 * only published state, the read-only native display binding (exact 120/null
 * settings, positive-finite refresh observation, no settings write), the
 * thermal `NONE`/`LIGHT` gate, target-PID continuity, package exit-info delta,
 * the exact 1,000-day replacement traversal summary, and the fail-closed
 * traversal-controller negatives (duplicate/skipped date, overlapping query,
 * next query before prior draw, mixed-day publication, item count > 11,
 * nonzero prefetch, wrong first/last date, incomplete traversal).
 *
 * Every gate is a shared production assertion in [TimelineHarnessGates] — the
 * same functions a binding suite must call — and each negative control below
 * proves that its shared assertion throws on the violating input.
 *
 * The reference-day selected-day render (`2025-09-26`) is the same state the
 * load protocol measures; the self-check proves it renders the frozen top and
 * publishes exactly one date. The one unmeasured complete replacement
 * traversal (`mode=replace-ready`) reports the exact frozen summary and is
 * asserted here, then a second start with the same run ID is proven to fail
 * closed.
 */
@RunWith(AndroidJUnit4::class)
class TimelineHarnessSelfCheckTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation = instrumentation.uiAutomation
    // Self-instrumenting module (Task 6 repair): targetContext is the test
    // package, so the target app package is the frozen constant and sentinels
    // are read from the app-published mirror file via shell.
    private val targetPackage = TARGET_APP_PACKAGE
    private val sentinels: TimelineSentinelReader
        get() = TimelineSentinelReader(::shell)
    private val device: UiDevice
        get() = UiDevice.getInstance(instrumentation)
    private val activityComponent = "$targetPackage/com.paherdin.record.benchmark.TimelineBenchmarkActivity"

    // ------------------------------------------------------------------
    // Positive controls
    // ------------------------------------------------------------------

    @Test
    fun selectedDayHashArgumentsArePresent() {
        TimelineHarnessGates.assertOracleHashPresent(InstrumentationRegistry.getArguments())
    }

    @Test
    fun preparationSeedsShadowDbAndSelectedDaySlicesMatchHost() {
        val firstHash = requireArg(ARG_FIRST_HASH)
        val lastHash = requireArg(ARG_LAST_HASH)
        launchMode("prepare-selected-day-contract")
        waitForSentinel("prepared").also { assertEquals("true", it) }
        TimelineHarnessGates.assertRowCount(waitForSentinel("rowCount"))
        TimelineHarnessGates.assertSeedWriteCount(waitForSentinel("seedWriteCount"))
        assertEquals(firstHash, waitForSentinel("migrationFirstHashBefore"))
        assertEquals(firstHash, waitForSentinel("migrationFirstHashAfter"))
        assertEquals(lastHash, waitForSentinel("migrationLastHashBefore"))
        assertEquals(lastHash, waitForSentinel("migrationLastHashAfter"))
        val plan = waitForSentinel("migrationQueryPlanDetail")
        assertTrue(
            "migration query plan must contain the indexed range search: $plan",
            plan.uppercase().contains("SEARCH TIMELINE_CARD USING INDEX INDEX_TIMELINE_CARD_OCCURREDAT"),
        )
        Log.i(TAG, "preparation ok: 10000 rows, first/last migration slice hashes match host")
    }

    @Test
    fun queryStatementIsSelectedDayBoundedAndUnpaged() {
        launchRenderReady(REFERENCE_DATE, REFERENCE_DAY_START, REFERENCE_DAY_END)
        val sql = sentinels.getString("querySql") ?: throw AssertionError("no querySql sentinel")
        TimelineHarnessGates.assertSelectedDayQueryNoPaging(sql)
        Log.i(TAG, "querySql=$sql")
    }

    @Test
    fun traceGateReportsSingleStartEnd() {
        launchRenderReady(REFERENCE_DATE, REFERENCE_DAY_START, REFERENCE_DAY_END)
        val traceOk = sentinels.getString("traceOk") ?: throw AssertionError("no traceOk sentinel")
        TimelineHarnessGates.assertTraceGateOk(traceOk)
    }

    @Test
    fun topSentinelsMatchFrozenReferenceDay() {
        launchRenderReady(REFERENCE_DATE, REFERENCE_DAY_START, REFERENCE_DAY_END)
        val topHeaderDate = sentinels.getString("topHeaderDate") ?: throw AssertionError("no topHeaderDate sentinel")
        val topCardId = sentinels.getString("topCardId") ?: throw AssertionError("no topCardId sentinel")
        TimelineHarnessGates.assertTopState(topHeaderDate, topCardId)
    }

    @Test
    fun publishedReadyStateContainsExactlyOneSelectedDay() {
        launchRenderReady(REFERENCE_DATE, REFERENCE_DAY_START, REFERENCE_DAY_END)
        assertEquals("a ready state may publish exactly one date", "1", sentinels.getString("publishedDateCount"))
        assertEquals(REFERENCE_DATE, sentinels.getString("publishedDates"))
        launchRenderReady(FIRST_DAY, FIRST_DAY_START, FIRST_DAY_END)
        assertEquals("a ready state may publish exactly one date", "1", sentinels.getString("publishedDateCount"))
        assertEquals(FIRST_DAY, sentinels.getString("publishedDates"))
    }

    /**
     * Step 4 positive: the complete unmeasured replacement traversal reports
     * the exact frozen summary, then a second `--activity-single-top` start for
     * the same run ID fails closed.
     */
    @Test
    fun unmeasuredReplacementTraversalReportsExactFrozenSummary() {
        val prevRunId = sentinels.getString("runId")
        launchMode("replace-ready")
        waitForFreshRun(prevRunId, "replace-ready")
        val runId = sentinels.getString("runId") ?: throw AssertionError("no replace-ready runId")
        waitForSentinel("replaceReady")
        shell(
            "am start --activity-single-top -n $activityComponent " +
                "--es mode replace-ready --ez startTraversal true --es runId $runId",
        )

        val deadline = System.currentTimeMillis() + TRAVERSAL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            sentinels.getString("error")?.let { fail("replace-ready reported an error: $it") }
            if (sentinels.getString("traversalComplete") == "true") break
            Thread.sleep(500)
        }
        if (sentinels.getString("traversalComplete") != "true") {
            fail("replacement traversal did not complete within ${TRAVERSAL_TIMEOUT_MS}ms")
        }
        val summary = SelectedDayTraversalSummary(
            selectedDayDrawCount = waitForSentinel("selectedDayDrawCount").toInt(),
            replacementCount = waitForSentinel("replacementCount").toInt(),
            maxPublishedItemCount = waitForSentinel("maxPublishedItemCount").toInt(),
            prefetchQueryCount = waitForSentinel("prefetchQueryCount").toInt(),
            firstDate = waitForSentinel("firstDate"),
            lastDate = waitForSentinel("lastDate"),
        )
        TimelineHarnessGates.assertTraversalSummary(summary)
        Log.i(TAG, "unmeasured replacement traversal summary=$summary")

        // A second start for the same run ID must fail closed.
        shell(
            "am start --activity-single-top -n $activityComponent " +
                "--es mode replace-ready --ez startTraversal true --es runId $runId",
        )
        val errDeadline = System.currentTimeMillis() + SECOND_START_TIMEOUT_MS
        while (System.currentTimeMillis() < errDeadline) {
            val err = sentinels.getString("error")
            if (err != null) {
                assertTrue("second start must fail closed as 'already started': $err", err.contains("already started"))
                return
            }
            Thread.sleep(250)
        }
        fail("second replacement start did not fail closed within ${SECOND_START_TIMEOUT_MS}ms")
    }

    @Test
    fun fiveManualColdLoadWarmupsReachReady() {
        // The per-launch runId (captured immediately before each launch) is the
        // deterministic freshness proof.
        var prevRunId: String? = null
        TimelineScrollHarness.runColdLoadWarmups(
            uiAutomation = uiAutomation,
            targetPackage = targetPackage,
            count = 5,
            launchFresh = {
                prevRunId = sentinels.getString("runId")
                launchMode("load-only")
            },
            waitReady = {
                waitForFreshRun(prevRunId, "load-only")
                waitForReady()
            },
        )
    }

    @Test
    fun thermalStatusIsWithinGate() {
        val dump = shell("dumpsys thermalservice")
        val status = TimelineHarnessGates.thermalStatusFromDumpsys(dump)
            ?: throw AssertionError("no 'Thermal Status: N' line in dumpsys thermalservice")
        Log.i(TAG, "thermalStatus=$status (${statusName(status)})")
        TimelineHarnessGates.assertThermalWithinGate(status)
    }

    /**
     * The native user display settings must be exactly 120/null — captured
     * read-only from the real device, never written.
     */
    @Test
    fun nativeDisplayBindingPassesUnderNaturalSettings() {
        val settings = TimelineDisplayBinding.capture(::shell)
        Log.i(TAG, "native display settings peak=${settings.peak} min=${settings.min}")
        TimelineDisplayBinding.assertNative(settings)
    }

    /** Any positive finite refresh observation passes — no fixed-120 gate. */
    @Test
    fun positiveRefreshObservationsPass() {
        for (raw in listOf("60.0", "90.0", "120.0")) {
            val value = TimelineDisplayBinding.requirePositiveRefresh(raw)
            check(value > 0.0) { "positive refresh '$raw' parsed as $value" }
        }
    }

    // ------------------------------------------------------------------
    // Negative controls — each proves a shared production assertion throws
    // on the violating input, i.e. the harness fails closed.
    // ------------------------------------------------------------------

    @Test
    fun negativePeakSixtyFailsDisplayBinding() {
        assertSharedGateThrows {
            TimelineDisplayBinding.assertNative(TimelineDisplaySettings(peak = "60", min = "null"))
        }
    }

    @Test
    fun negativeMissingPeakFailsDisplayBinding() {
        // "null" is what `settings get` prints for an unset peak_refresh_rate.
        assertSharedGateThrows {
            TimelineDisplayBinding.assertNative(TimelineDisplaySettings(peak = "null", min = "null"))
        }
    }

    @Test
    fun negativeNonNullMinFailsDisplayBinding() {
        assertSharedGateThrows {
            TimelineDisplayBinding.assertNative(TimelineDisplaySettings(peak = "120", min = "60"))
        }
    }

    @Test
    fun negativeRefreshObservationsFail() {
        for (raw in listOf("", "0", "-5", "NaN", "Infinity")) {
            val thrown = try {
                TimelineDisplayBinding.requirePositiveRefresh(raw)
                null
            } catch (e: Exception) {
                e
            }
            assertNotNull("refresh '$raw' must be rejected by requirePositiveRefresh", thrown)
        }
    }

    @Test
    fun negativeMissingOracleHashFailsSharedAssertion() {
        assertSharedGateThrows { TimelineHarnessGates.assertOracleHashPresent(Bundle()) }
    }

    @Test
    fun negativeWrongRowCountFailsSharedAssertion() {
        assertSharedGateThrows { TimelineHarnessGates.assertRowCount("9999") }
    }

    @Test
    fun negativePagedQueryFailsSharedAssertion() {
        assertSharedGateThrows {
            TimelineHarnessGates.assertSelectedDayQueryNoPaging(
                "SELECT * FROM timeline_card " +
                    "WHERE occurredAt >= :dayStart AND occurredAt < :nextDayStart " +
                    "ORDER BY occurredAt DESC, id COLLATE BINARY ASC LIMIT 10",
            )
        }
    }

    @Test
    fun negativeTraceGateFailureFailsSharedAssertion() {
        assertSharedGateThrows { TimelineHarnessGates.assertTraceGateOk("false") }
    }

    @Test
    fun negativeWrongTopFailsSharedAssertion() {
        assertSharedGateThrows { TimelineHarnessGates.assertTopState("2025-09-25", TimelineHarnessGates.FROZEN_TOP_ID) }
    }

    @Test
    fun negativeWrongTraversalSummaryFailsSharedAssertion() {
        val wrongDraw = SelectedDayTraversalSummary(999, 999, 11, 0, "2025-09-26", "2023-01-01")
        assertSharedGateThrows { TimelineHarnessGates.assertTraversalSummary(wrongDraw) }
        val wrongReplacement = SelectedDayTraversalSummary(1000, 998, 11, 0, "2025-09-26", "2023-01-01")
        assertSharedGateThrows { TimelineHarnessGates.assertTraversalSummary(wrongReplacement) }
        val wrongMax = SelectedDayTraversalSummary(1000, 999, 12, 0, "2025-09-26", "2023-01-01")
        assertSharedGateThrows { TimelineHarnessGates.assertTraversalSummary(wrongMax) }
        val wrongPrefetch = SelectedDayTraversalSummary(1000, 999, 11, 1, "2025-09-26", "2023-01-01")
        assertSharedGateThrows { TimelineHarnessGates.assertTraversalSummary(wrongPrefetch) }
        val wrongFirst = SelectedDayTraversalSummary(1000, 999, 11, 0, "2025-09-25", "2023-01-01")
        assertSharedGateThrows { TimelineHarnessGates.assertTraversalSummary(wrongFirst) }
        val wrongLast = SelectedDayTraversalSummary(1000, 999, 11, 0, "2025-09-26", "2023-01-02")
        assertSharedGateThrows { TimelineHarnessGates.assertTraversalSummary(wrongLast) }
    }

    @Test
    fun negativeDuplicateOrSkippedDateFailsTraversalController() {
        // Duplicate date (second query for the already-drawn date).
        assertSharedGateThrows {
            TimelineTraversalController().apply {
                onQuery("2025-09-26", 11)
                onDraw("2025-09-26", setOf("2025-09-26"))
                onQuery("2025-09-26", 11)
            }
        }
        // Skipped date (2025-09-24 is not 2025-09-26 - 1).
        assertSharedGateThrows {
            TimelineTraversalController().apply {
                onQuery("2025-09-26", 11)
                onDraw("2025-09-26", setOf("2025-09-26"))
                onQuery("2025-09-24", 11)
            }
        }
    }

    @Test
    fun negativeOverlappingQueryAndNextQueryBeforePriorDrawFails() {
        assertSharedGateThrows {
            TimelineTraversalController().apply {
                onQuery("2025-09-26", 11)
                onQuery("2025-09-25", 11) // second query while the prior draw is pending
            }
        }
    }

    @Test
    fun negativeMixedDayPublicationFailsTraversalController() {
        // Draw a different date than the pending query.
        assertSharedGateThrows {
            TimelineTraversalController().apply {
                onQuery("2025-09-26", 11)
                onDraw("2025-09-25", setOf("2025-09-25"))
            }
        }
        // Published state carries two dates at once.
        assertSharedGateThrows {
            TimelineTraversalController().apply {
                onQuery("2025-09-26", 11)
                onDraw("2025-09-26", setOf("2025-09-26", "2025-09-25"))
            }
        }
    }

    @Test
    fun negativeItemCountAboveElevenFailsTraversalController() {
        assertSharedGateThrows {
            TimelineTraversalController().onQuery("2025-09-26", 12)
        }
    }

    @Test
    fun negativeNonzeroPrefetchFailsTraversalController() {
        assertSharedGateThrows {
            val controller = TimelineTraversalController()
            runCompleteTraversal(controller)
            controller.onPrefetch()
            controller.finish()
        }
    }

    @Test
    fun negativeWrongFirstOrLastDateFailsTraversalController() {
        // A complete traversal from the wrong start date has the wrong
        // first/last dates and must fail.
        assertSharedGateThrows {
            val controller = TimelineTraversalController()
            var date = LocalDate.parse("2025-09-24")
            for (i in 0 until 1000) {
                val d = date.toString()
                controller.onQuery(d, 11)
                controller.onDraw(d, setOf(d))
                date = date.minusDays(1)
            }
            controller.finish()
        }
    }

    @Test
    fun negativeIncompleteTraversalFailsTraversalController() {
        assertSharedGateThrows {
            val controller = TimelineTraversalController()
            runCompleteTraversal(controller, datesToRun = 3)
            controller.finish()
        }
    }

    @Test
    fun negativeThermalModerateFailsSharedAssertion() {
        assertSharedGateThrows { TimelineHarnessGates.assertThermalWithinGate(2) } // MODERATE
    }

    @Test
    fun negativePidDeltaFailsSharedAssertion() {
        assertSharedGateThrows { TimelineHarnessGates.assertPidContinuity("100", "200") }
    }

    @Test
    fun negativeExitInfoDeltaFailsSharedAssertion() {
        val before = "ACTIVITY MANAGER PROCESS EXIT INFO (dumpsys activity exit-info)\n" +
            "Last Timestamp of Persistence Into Persistent Storage: 2026-08-16 16:44:45.961\n"
        val after = before + "  com.paherdin.record (pid 1234) 2026-08-16 reason: crashed\n"
        assertSharedGateThrows { TimelineHarnessGates.assertNoNewExitInfo(before, after) }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun runCompleteTraversal(controller: TimelineTraversalController, datesToRun: Int = 1000) {
        var date = LocalDate.parse("2025-09-26")
        for (i in 0 until datesToRun) {
            val d = date.toString()
            controller.onQuery(d, 11)
            controller.onDraw(d, setOf(d))
            date = date.minusDays(1)
        }
    }

    private fun launchRenderReady(date: String, dayStart: Long, nextDayStart: Long) {
        val prevRunId = sentinels.getString("runId")
        val out = shell(
            "am start -W -f 0x10008000 -n $activityComponent --es mode render-correctness " +
                "--es selectedDate $date --el dayStart $dayStart --el nextDayStart $nextDayStart",
        )
        check(out.contains("Status: ok") || out.contains("LaunchState")) {
            "target Activity failed to launch: $out"
        }
        waitForFreshRun(prevRunId, "render-correctness")
        waitForReady()
    }

    private fun launchMode(mode: String) {
        val out = shell("am start -W -f 0x10008000 -n $activityComponent --es mode $mode")
        check(out.contains("Status: ok") || out.contains("LaunchState")) {
            "target Activity failed to launch: $out"
        }
    }

    /**
     * Waits until the launched Activity stamps a runId different from
     * [prevRunId] (captured immediately before the launch). A per-launch UUID
     * runId is the deterministic freshness proof: the whole selected-day load
     * can complete within the `am start -W` window on this device, so the
     * previous transient `ready==null` check raced with the load and could
     * miss the cleared state entirely.
     */
    private fun waitForFreshRun(prevRunId: String?, expectedMode: String) {
        val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val runId = sentinels.getString("runId")
            if (runId != null && runId != prevRunId &&
                sentinels.getString("mode") == expectedMode
            ) {
                return
            }
            Thread.sleep(100)
        }
        fail("launched Activity did not stamp a fresh $expectedMode run within ${LAUNCH_TIMEOUT_MS}ms")
    }

    private fun waitForReady() {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            sentinels.getString("error")?.let {
                fail("target reported an error: $it")
            }
            if (sentinels.getString("ready") == "true") return
            Thread.sleep(200)
        }
        fail("ready sentinel not set within ${READY_TIMEOUT_MS}ms")
    }

    private fun waitForSentinel(key: String, timeoutMs: Long = 120_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            sentinels.getString(key)?.let { return it }
            Thread.sleep(250)
        }
        fail("sentinel '$key' not exposed within ${timeoutMs}ms")
        throw AssertionError("unreachable")
    }

    private fun requireArg(key: String): String =
        InstrumentationRegistry.getArguments().getString(key)
            ?: throw AssertionError("missing instrumentation argument $key")

    private fun shell(command: String): String =
        uiAutomation.executeShellCommand(command).use { pfd ->
            FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
        }

    private fun statusName(status: Int): String = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }

    private fun assertSharedGateThrows(block: () -> Unit) {
        // The gates throw IllegalStateException (fail-closed production
        // semantics), so catch Exception — not just AssertionError.
        val thrown = try {
            block()
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull("shared gate must fail on the violating input", thrown)
    }

    private companion object {
        const val TAG = "P6SelfCheck"
        const val ARG_FIRST_HASH = "p6ExpectedFirstDayHash"
        const val ARG_LAST_HASH = "p6ExpectedLastDayHash"
        const val ARG_EMPTY_HASH = "p6ExpectedEmptyDayHash"
        const val REFERENCE_DATE = "2025-09-26"
        const val REFERENCE_DAY_START = 1_758_816_000_000L
        const val REFERENCE_DAY_END = 1_758_902_400_000L
        const val FIRST_DAY = "2023-01-01"
        const val FIRST_DAY_START = 1_672_502_400_000L
        const val FIRST_DAY_END = 1_672_588_800_000L
        const val LAUNCH_TIMEOUT_MS = 60_000L
        const val READY_TIMEOUT_MS = 120_000L
        const val TRAVERSAL_TIMEOUT_MS = 600_000L
        const val SECOND_START_TIMEOUT_MS = 30_000L
    }
}

/**
 * Pure, fail-closed controller of a one-day-at-a-time selected-day replacement
 * traversal. It mirrors the exact protocol the app-side `replace-ready` mode
 * follows (spec §4.5 / plan Task 6R4-B Step 4): strictly descending dates, one
 * query in flight, generation-matched draw before the next query, no prefetch,
 * no cross-day state. The device negatives prove each violation throws; a valid
 * complete traversal finishes with the exact frozen summary.
 */
internal class TimelineTraversalController {
    private val visitedDates = LinkedHashSet<String>()
    private var lastDrawnDate: String? = null
    private var queryPending = false
    private var queriedDate: String? = null
    private var queriedItemCount = 0
    private var drawnCount = 0
    private var replacementCount = 0
    private var maxPublishedItemCount = 0
    private var prefetchQueryCount = 0

    fun onQuery(date: String, itemCount: Int) {
        check(!queryPending) {
            "replacement traversal queried '$date' while the previous query's correct draw " +
                "was still pending (overlapping query / next query before prior draw)"
        }
        check(itemCount <= SelectedDayTraversalSummary.FROZEN.maxPublishedItemCount) {
            "replacement traversal published item count $itemCount exceeds the frozen " +
                "${SelectedDayTraversalSummary.FROZEN.maxPublishedItemCount} (item count > 11)"
        }
        val expected = lastDrawnDate?.let { LocalDate.parse(it).minusDays(1).toString() }
        if (expected != null) {
            check(date == expected) {
                "replacement traversal queried '$date' but the next descending date after " +
                    "'$lastDrawnDate' is '$expected' (duplicate or skipped date)"
            }
        }
        queryPending = true
        queriedDate = date
        queriedItemCount = itemCount
    }

    fun onDraw(publishedDate: String, publishedDates: Set<String>) {
        check(queryPending) {
            "replacement traversal drew '$publishedDate' without a pending selected-day query"
        }
        check(publishedDate == queriedDate) {
            "replacement traversal drew '$publishedDate' but the pending query was for " +
                "'$queriedDate' (mixed-day publication)"
        }
        check(publishedDates.size == 1 && publishedDates.single() == publishedDate) {
            "replacement traversal published cross-day state $publishedDates for " +
                "'$publishedDate' (mixed-day publication)"
        }
        check(visitedDates.add(publishedDate)) {
            "replacement traversal drew '$publishedDate' a second time (duplicate date)"
        }
        drawnCount++
        if (lastDrawnDate != null) replacementCount++
        maxPublishedItemCount = maxOf(maxPublishedItemCount, queriedItemCount)
        lastDrawnDate = publishedDate
        queryPending = false
        queriedDate = null
    }

    fun onPrefetch() {
        prefetchQueryCount++
    }

    fun finish(): SelectedDayTraversalSummary {
        val summary = SelectedDayTraversalSummary(
            selectedDayDrawCount = drawnCount,
            replacementCount = replacementCount,
            maxPublishedItemCount = maxPublishedItemCount,
            prefetchQueryCount = prefetchQueryCount,
            firstDate = visitedDates.firstOrNull().orEmpty(),
            lastDate = visitedDates.lastOrNull().orEmpty(),
        )
        TimelineHarnessGates.assertTraversalSummary(summary)
        return summary
    }
}

/**
 * Shared production gates. A binding suite must call exactly these assertions
 * per iteration (refresh, thermal, PID continuity, exit-info, traversal
 * summary); the self-check's negative controls prove each one throws on a
 * violating input.
 */
internal object TimelineHarnessGates {

    const val FROZEN_TOP_ID = "8ad0ba0d-1a93-30f7-8671-678259ad3846"
    const val FROZEN_BOTTOM_ID = "5116ef58-5c4c-3c25-bce0-90978ba33e89"
    const val FROZEN_TOP_HEADER_DATE = "2025-09-26"
    const val MAX_GESTURES = 1200
    const val SWIPE_STEPS = 10
    const val GESTURE_GAP_BOUND_MS = 1_500L

    const val EXPECTED_ROW_COUNT = "10000"
    const val EXPECTED_FLATTENED_COUNT = "11000"
    const val EXPECTED_SEED_WRITE_COUNT = "10000"

    fun assertOracleHashPresent(args: Bundle) {
        checkNotNull(args.getString("p6ExpectedFirstDayHash")) {
            "missing instrumentation argument p6ExpectedFirstDayHash — the host selected-day " +
                "slice hashes must be passed for any correctness or measurement run"
        }
        checkNotNull(args.getString("p6ExpectedLastDayHash")) {
            "missing instrumentation argument p6ExpectedLastDayHash"
        }
        checkNotNull(args.getString("p6ExpectedEmptyDayHash")) {
            "missing instrumentation argument p6ExpectedEmptyDayHash"
        }
    }

    fun assertHashMatchesOracle(expected: String, actual: String) {
        check(actual == expected) { "device hash '$actual' != oracle hash '$expected'" }
    }

    fun assertRowCount(rowCount: String) {
        check(rowCount == EXPECTED_ROW_COUNT) {
            "shadow DB row count $rowCount != $EXPECTED_ROW_COUNT"
        }
    }

    /** Retained for the Rev3 `seed-and-verify` binding path (Task 6R4-C rewrites it). */
    fun assertCounts(rowCount: String, flattenedCount: String) {
        check(rowCount == EXPECTED_ROW_COUNT) {
            "shadow DB row count $rowCount != $EXPECTED_ROW_COUNT"
        }
        check(flattenedCount == EXPECTED_FLATTENED_COUNT) {
            "flattened projection count $flattenedCount != $EXPECTED_FLATTENED_COUNT"
        }
    }

    fun assertSeedWriteCount(count: String) {
        check(count == EXPECTED_SEED_WRITE_COUNT) {
            "preparation seed write count $count != $EXPECTED_SEED_WRITE_COUNT"
        }
    }

    /** Requires the exact frozen selected-day traversal summary. */
    fun assertTraversalSummary(summary: SelectedDayTraversalSummary) {
        check(summary == SelectedDayTraversalSummary.FROZEN) {
            "traversal summary $summary != frozen ${SelectedDayTraversalSummary.FROZEN}"
        }
    }

    fun assertSelectedDayQueryNoPaging(sql: String) {
        check(sql.startsWith("SELECT * FROM timeline_card")) {
            "query does not select from timeline_card: $sql"
        }
        check(sql.contains("WHERE occurredAt >= :dayStart AND occurredAt < :nextDayStart")) {
            "query lacks the frozen half-open selected-day bound: $sql"
        }
        check(sql.contains("ORDER BY occurredAt DESC") && sql.contains("COLLATE BINARY")) {
            "query lacks the frozen DESC time + BINARY id order: $sql"
        }
        val upper = sql.uppercase()
        check("LIMIT" !in upper && "OFFSET" !in upper && "PAGE" !in upper && "FIRST(" !in upper) {
            "query must be the unpaged selected-day materialization, found paging token: $sql"
        }
    }

    fun assertTraceGateOk(traceOk: String) {
        check(traceOk == "true") {
            "TimelineQueryToRender gate not ok (traceOk=$traceOk) — the trace must start once " +
                "immediately before the single selected-day query and end at the first correct draw"
        }
    }

    fun assertTopState(topHeaderDate: String, topCardId: String) {
        check(topHeaderDate == FROZEN_TOP_HEADER_DATE) {
            "top header $topHeaderDate != frozen $FROZEN_TOP_HEADER_DATE"
        }
        check(topCardId == FROZEN_TOP_ID) {
            "top card $topCardId != frozen $FROZEN_TOP_ID"
        }
    }

    fun assertBottomState(bottomCardId: String, bottomReached: Boolean) {
        check(bottomReached) {
            "bottom sentinel never composed — the scroll did not reach the final card"
        }
        check(bottomCardId == FROZEN_BOTTOM_ID) {
            "bottom card $bottomCardId != frozen $FROZEN_BOTTOM_ID"
        }
    }

    fun assertUnderGestureCap(gestureCount: Int) {
        check(gestureCount < MAX_GESTURES) {
            "bottom not reached within the $MAX_GESTURES-gesture cap (gestures=$gestureCount)"
        }
        check(gestureCount > 0) { "gesture count must be positive, was $gestureCount" }
    }

    fun assertNoExplicitGestureGap(gestureCount: Int, elapsedMs: Long) {
        check(elapsedMs < gestureCount * GESTURE_GAP_BOUND_MS) {
            "scroll took ${elapsedMs}ms for $gestureCount gestures — an explicit idle gap " +
                "between gestures violates the frozen immediate-repeat protocol"
        }
    }

    fun assertThermalWithinGate(status: Int) {
        check(status <= LIGHT_STATUS) {
            "thermal status $status (>= MODERATE) invalidates the whole suite"
        }
    }

    /** NONE=0, LIGHT=1, MODERATE=2, ... — same ordinals as ThermalStatus. */
    const val LIGHT_STATUS = 1

    /** Parses the `Thermal Status: N` line of `dumpsys thermalservice`; null when absent. */
    fun thermalStatusFromDumpsys(dumpsys: String): Int? =
        Regex("Thermal Status:\\s*(\\d+)").find(dumpsys)?.groupValues?.get(1)?.toIntOrNull()

    fun assertPidContinuity(beforePid: String, afterPid: String) {
        check(beforePid.isNotBlank() && afterPid == beforePid) {
            "target process PID changed '$beforePid' -> '$afterPid' — process death/relaunch"
        }
    }

    fun assertNoNewExitInfo(beforeText: String, afterText: String) {
        check(normalizeExitInfo(beforeText) == normalizeExitInfo(afterText)) {
            "new package ANR/crash/OOM/process-exit evidence appeared between captures"
        }
    }

    /**
     * Suite-level stability gate (binding runs): fails on any NEW exit-info
     * entry whose reason is not `USER REQUESTED`.
     */
    fun assertNoUnexpectedExitInfo(beforeText: String, afterText: String) {
        val before = exitInfoReasonCounts(beforeText)
        val after = exitInfoReasonCounts(afterText)
        val unexpected = after.entries
            .filter { it.key != "USER REQUESTED" }
            .mapNotNull { (reason, count) ->
                val delta = count - (before[reason] ?: 0)
                if (delta > 0) "$reason: $delta new" else null
            }
        check(unexpected.isEmpty()) {
            "new package ANR/crash/OOM/process-exit evidence appeared: $unexpected"
        }
    }

    /**
     * Counts exit-info entries by their `reason=NN (NAME)` label. The
     * negative lookbehind excludes the `subreason=` line.
     */
    fun exitInfoReasonCounts(text: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        Regex("(?<![a-zA-Z])reason=\\d+ \\(([^)]+)\\)").findAll(text).forEach { m ->
            counts.merge(m.groupValues[1], 1, Int::plus)
        }
        return counts
    }

    /** Evidence lines only: drops the header and the persistence timestamp line. */
    fun normalizeExitInfo(text: String): String =
        text.lines()
            .filter { it.isNotBlank() }
            .filterNot { it.trim().startsWith("ACTIVITY MANAGER PROCESS EXIT INFO") }
            .filterNot { it.trim().startsWith("Last Timestamp of Persistence") }
            .joinToString("\n")
}

/**
 * Device-side harness actions shared with a binding suite: fresh cold launches
 * of the benchmark Activity, the (historical Rev3) one manual unmeasured full
 * top-to-bottom scroll, the five manual cold-process load warmups, the forced-
 * 3-button-navigation scroll control, and shell evidence capture. The scroll
 * helpers remain only because the Task 6R4-C binding class still compiles
 * against them; the Rev4 self-check exercises the selected-day/traversal path.
 */
internal object TimelineScrollHarness {

    fun manualFullScrollToBottom(device: UiDevice, read: (String) -> String?): ScrollOutcome {
        val metrics = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics
        val centerX = metrics.widthPixels / 2
        val startY = metrics.heightPixels * 85 / 100
        val endY = metrics.heightPixels * 15 / 100
        var gestures = 0
        val startNanos = System.nanoTime()
        while (read("bottomReached") != "true" &&
            gestures < TimelineHarnessGates.MAX_GESTURES
        ) {
            check(device.swipe(centerX, startY, centerX, endY, TimelineHarnessGates.SWIPE_STEPS)) {
                "UiDevice.swipe failed at gesture $gestures"
            }
            gestures++
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        return ScrollOutcome(
            gestureCount = gestures,
            elapsedMs = elapsedMs,
            bottomReached = read("bottomReached") == "true",
        )
    }

    /**
     * Exactly [count] manual cold-process load warmups, to run immediately
     * before binding measurement. Warmups are control evidence and never part
     * of the gating samples.
     */
    fun runColdLoadWarmups(
        uiAutomation: android.app.UiAutomation,
        targetPackage: String,
        count: Int,
        launchFresh: () -> Unit,
        waitReady: () -> Unit,
    ) {
        val testPid = Process.myPid()
        val targetPid = pidOf(uiAutomation, targetPackage)
        val canForceStop = targetPid != null && targetPid != testPid.toString()
        if (!canForceStop) {
            Log.w(
                "P6SelfCheck",
                "warmups run without force-stop: the instrumentation shares the target process " +
                    "in this configuration; a self-instrumenting macrobenchmark configuration " +
                    "supplies the cold restart",
            )
        }
        repeat(count) { i ->
            if (canForceStop) {
                uiAutomation.executeShellCommand("am force-stop $targetPackage").close()
            }
            launchFresh()
            waitReady()
            Log.i("P6SelfCheck", "warmup $i reached ready")
        }
    }

    /**
     * Runs [block] under forced 3-button navigation and restores the original
     * `navigation_mode` in `finally` (restoration failure propagates). Retained
     * for a Rev3-style scroll control; the Rev4 replacement traversal needs no
     * swipes and never applies this control.
     */
    fun withNavigationButtons(shell: (String) -> String, block: () -> Unit) {
        val original = shell("settings get secure navigation_mode").trim()
        shell("settings put secure navigation_mode 0")
        check(shell("settings get secure navigation_mode").trim() == "0") {
            "failed to force 3-button navigation mode (settings put secure navigation_mode 0)"
        }
        try {
            block()
        } finally {
            if (original == "null") {
                shell("settings delete secure navigation_mode")
            } else {
                shell("settings put secure navigation_mode $original")
            }
            check(shell("settings get secure navigation_mode").trim() == original) {
                "navigation_mode restore failed (original=$original)"
            }
        }
    }

    fun pidOf(uiAutomation: android.app.UiAutomation, targetPackage: String): String? =
        uiAutomation.executeShellCommand("pidof $targetPackage").use { pfd ->
            FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }.trim()
        }.takeIf { it.isNotEmpty() }

    fun captureExitInfo(uiAutomation: android.app.UiAutomation, targetPackage: String): String =
        uiAutomation.executeShellCommand("dumpsys activity exit-info $targetPackage").use { pfd ->
            FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
        }
}

/** Outcome of one manual full top-to-bottom scroll (historical Rev3 control). */
internal data class ScrollOutcome(
    val gestureCount: Int,
    val elapsedMs: Long,
    val bottomReached: Boolean,
)
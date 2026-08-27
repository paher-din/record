package com.paherdin.record.benchmark

import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.FileInputStream
import org.junit.AfterClass
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Tech PoC P6 Task 6R4-C — the Rev4 binding benchmark (plan Task 6R4-C; spec
 * §4.4–4.7). Four name-ordered blocks under `@FixMethodOrder(NAME_ASCENDING)`:
 *
 * ```
 * binding01LoadA    15 cold-process selected-day load iterations
 * binding02ReplaceA  3 prepared 1,000-day replacement iterations
 * binding03ReplaceB  2 prepared 1,000-day replacement iterations
 * binding04LoadB    15 cold-process selected-day load iterations
 * ```
 *
 * Suite lifecycle: capture and assert the read-only native display binding
 * (`120`/`null`) before any warmup; capture exit-info/PID/airplane/battery/
 * thermal evidence; verify the prepared 10,000-row shadow DB plus the last-day
 * slice hash; enable airplane mode (the P6-introduced change, the ONLY setting
 * the suite modifies and restores); foreground-control the target; idle five
 * minutes with thermal probes at most 60 s apart. A class-level `@AfterClass`
 * path restores only the airplane setting, re-reads and asserts the display
 * settings (never writes them), diffs the package exit-info, and finalizes the
 * run manifest. Any restoration, settings, stability, or manifest-finalization
 * failure keeps the suite incomplete.
 *
 * Controls: immediately before `binding01LoadA` exactly five manual cold-process
 * loads of `2025-09-26` run; immediately before `binding02ReplaceA` one manual
 * unmeasured complete selected-day replacement traversal runs and MUST report
 * the exact frozen summary (`1000/999/11/0/2025-09-26/2023-01-01`). None of
 * these six controls produce Macrobenchmark samples.
 *
 * Measurement: load blocks use `CompilationMode.Full`, `StartupMode.COLD`,
 * `TraceSectionMetric("TimelineQueryToRender")` (the Activity starts/ends the
 * named section around its one bounded selected-day query / first correct draw),
 * and `MemoryUsageMetric(Mode.Max, [RssAnon])`; `startActivityAndWait()` is
 * inside the measured block and every launch receives date `2025-09-26` +
 * exact bounds. Replacement blocks use `CompilationMode.Full`,
 * `startupMode = null` (never COLD — the prepared process must survive),
 * `FrameTimingMetric()`, and the same memory metric; `setupBlock` force-stops
 * the old process, launches `replace-ready`, waits for the seeded/open/no-day
 * ready state, and captures PID/settings/thermal/refreshBefore; the measured
 * block sends the one-shot `am start --activity-single-top` with the matching
 * run ID, waits for `traversalComplete`, requires the exact frozen summary,
 * reads refreshAfter, and rechecks thermal/PID. No swipes, no prefetch, no date
 * concatenation, and no process/database setup inside the measure.
 *
 * Invalid-iteration semantics: nothing is ever dropped, replaced, or retried; a
 * missing sentinel, setting drift, thermal `MODERATE+`, PID delta, traversal
 * mismatch, runner error, ANR/OOM/crash/relaunch/timeout, or manifest write
 * failure aborts the suite. A refresh change between two positive finite
 * observations is recorded and never fails by itself. Each valid iteration
 * appends one control record to `p6-run-manifest.json` (exactly 35 records).
 *
 * Every per-iteration gate is the shared production assertion from
 * `TimelineHarnessGates`; this class applies them with no local redefinitions.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@OptIn(ExperimentalMetricApi::class)
class TimelineBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation = instrumentation.uiAutomation
    // Self-instrumenting module (Task 6 repair): targetContext is the test
    // package, so the target app package is the frozen constant.
    private val targetPackage = TARGET_APP_PACKAGE
    private val sentinels: TimelineSentinelReader
        get() = TimelineSentinelReader(::shell)
    private val device: UiDevice
        get() = UiDevice.getInstance(instrumentation)

    // ------------------------------------------------------------------
    // The four frozen binding blocks (name-ascending execution order)
    // ------------------------------------------------------------------

    /** Lifecycle init + five manual cold-load warmups + 15 measured cold loads. */
    @Test
    fun binding01LoadA() {
        runSafely("binding01LoadA") {
            suiteLifecycleInit()
            // Step 1: exactly five manual cold-process load warmups of the
            // bound reference day — control evidence only, never part of the 30
            // gating samples. Warmups use the same load-only Activity mode +
            // date/bounds as the measured loads. The per-launch runId (captured
            // immediately before each launch) is the deterministic freshness
            // proof. Because an `am start` issued directly after `am force-stop`
            // can be transiently swallowed on this firmware (the fresh process
            // is killed mid-start, or the launch is redirected during recents
            // restoration), each warmup's cold launch is re-issued once when the
            // fresh-run sentinel does not appear — a CONTROL relaunch that has
            // not yet produced any sample, never a measured-iteration retry.
            runColdLoadWarmups()
            Log.i(TAG, "five manual cold-load warmups complete")
            runLoadBlock(BLOCK_LOAD_A, LOAD_A_ITERATIONS)
        }
    }

    /**
     * One manual unmeasured complete selected-day replacement traversal as a
     * GATING control (exact frozen summary required) + 3 measured prepared
     * replacements.
     */
    @Test
    fun binding02ReplaceA() {
        runSafely("binding02ReplaceA") {
            checkSuiteHealthy()
            // Step 1: exactly one unmeasured complete replacement traversal
            // immediately before the first measured replacement block. It must
            // report the exact frozen summary and is recorded as the suite-level
            // `controlTraversal` in the run manifest.
            runControlTraversal()
            runReplaceBlock(BLOCK_REPLACE_A, REPLACE_A_ITERATIONS)
        }
    }

    /** 2 measured prepared 1,000-day replacement iterations. */
    @Test
    fun binding03ReplaceB() {
        runSafely("binding03ReplaceB") {
            checkSuiteHealthy()
            runReplaceBlock(BLOCK_REPLACE_B, REPLACE_B_ITERATIONS)
        }
    }

    /** 15 cold-process load iterations completing the 30 load samples. */
    @Test
    fun binding04LoadB() {
        runSafely("binding04LoadB") {
            checkSuiteHealthy()
            runLoadBlock(BLOCK_LOAD_B, LOAD_B_ITERATIONS)
        }
    }

    // ------------------------------------------------------------------
    // Step 2: selected-day load measurement and its control record
    // ------------------------------------------------------------------

    private fun runLoadBlock(block: String, iterations: Int) {
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(
                // The :benchmark module never references the target's classes;
                // the section name is a frozen literal, identical to the app-side
                // gate's SECTION_NAME. Macrobenchmark 1.4.1 reports this section
                // as the `TimelineQueryToRenderCount` (slice uniqueness) and
                // `TimelineQueryToRenderSumMs` (duration) host metrics.
                TraceSectionMetric(QUERY_TRACE_SECTION),
                MemoryUsageMetric(
                    MemoryUsageMetric.Mode.Max,
                    listOf(MemoryUsageMetric.SubMetric.RssAnon),
                ),
            ),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.COLD,
            iterations = iterations,
            setupBlock = { },
            measureBlock = {
                // Thermal before the launch; COLD kills the process first, so
                // the target PID is captured after startActivityAndWait, before
                // the ready wait — the measured query/draw window.
                val thermalBefore = requireThermal()
                val prevRunId = sentinels.getString(SENTINEL_RUN_ID)
                startActivityAndWait(
                    Intent().apply {
                        setClassName(targetPackage, ACTIVITY_COMPONENT)
                        // The selected-day load-only mode receives the frozen
                        // bound reference day + its exact Asia/Shanghai bounds.
                        putExtra(EXTRA_MODE, MODE_LOAD_ONLY)
                        putExtra(EXTRA_SELECTED_DATE, REFERENCE_DATE)
                        putExtra(EXTRA_DAY_START, REFERENCE_DAY_START)
                        putExtra(EXTRA_NEXT_DAY_START, REFERENCE_DAY_END)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    },
                )
                waitForFreshScrollReady(prevRunId, MODE_LOAD_ONLY)
                waitForReady()
                val pidBefore = pidOf() ?: throw AssertionError("target PID missing after launch")

                // Step 2 Rev4 load gates: item count 11, matching day/hash,
                // query-plan sentinel, only the one selected day published
                // (no adjacent-day/prefetch content), one closed trace section.
                check(sentinel(SENTINEL_SELECTED_DATE) == REFERENCE_DATE) {
                    "load selectedDate '${sentinel(SENTINEL_SELECTED_DATE)}' != $REFERENCE_DATE"
                }
                check(sentinel(SENTINEL_DAY_START) == REFERENCE_DAY_START.toString()) {
                    "load dayStart '${sentinel(SENTINEL_DAY_START)}' != ${REFERENCE_DAY_START}"
                }
                check(sentinel(SENTINEL_NEXT_DAY_START) == REFERENCE_DAY_END.toString()) {
                    "load nextDayStart '${sentinel(SENTINEL_NEXT_DAY_START)}' != ${REFERENCE_DAY_END}"
                }
                check(sentinel(SENTINEL_COUNT) == EXPECTED_ITEM_COUNT) {
                    "load item count '${sentinel(SENTINEL_COUNT)}' != $EXPECTED_ITEM_COUNT"
                }
                TimelineHarnessGates.assertHashMatchesOracle(
                    lastDayHash(),
                    sentinel(SENTINEL_HASH),
                )
                TimelineHarnessGates.assertSelectedDayQueryNoPaging(sentinel(SENTINEL_QUERY_SQL))
                check(sentinel(SENTINEL_PUBLISHED_DATE_COUNT) == "1") {
                    "load publishedDateCount '${sentinel(SENTINEL_PUBLISHED_DATE_COUNT)}' != 1"
                }
                check(sentinel(SENTINEL_PUBLISHED_DATES) == REFERENCE_DATE) {
                    "load publishedDates '${sentinel(SENTINEL_PUBLISHED_DATES)}' != $REFERENCE_DATE " +
                        "(adjacent-day/prefetch content must be absent)"
                }
                TimelineHarnessGates.assertTraceGateOk(sentinel(SENTINEL_TRACE_OK))
                // The load path is a read-only open: no seed writes during the run.
                check(sentinel(SENTINEL_SEED_WRITE_COUNT) == "0") {
                    "load path performed seed writes: seedWriteCount=" +
                        sentinel(SENTINEL_SEED_WRITE_COUNT)
                }

                val refreshBefore = TimelineDisplayBinding.requirePositiveRefresh(
                    sentinel(SENTINEL_REFRESH_BEFORE_HZ),
                )
                val refreshAfter = TimelineDisplayBinding.requirePositiveRefresh(
                    sentinel(SENTINEL_REFRESH_AFTER_HZ),
                )
                val pidAfter = pidOf() ?: throw AssertionError("target PID missing after ready")
                val thermalAfter = requireThermal()
                TimelineHarnessGates.assertPidContinuity(pidBefore, pidAfter)
                val iteration = requireIteration(iteration)
                manifest().add(
                    TimelineIterationControl(
                        block = block,
                        iteration = iteration,
                        refreshBeforeHz = refreshBefore,
                        refreshAfterHz = refreshAfter,
                        thermalBefore = thermalBefore,
                        thermalAfter = thermalAfter,
                        pidBefore = pidBefore,
                        pidAfter = pidAfter,
                        // Rev4 load iterations carry no traversal summary.
                        selectedDayDrawCount = null,
                        replacementCount = null,
                        maxPublishedItemCount = null,
                        prefetchQueryCount = null,
                        firstDate = null,
                        lastDate = null,
                    ),
                )
                Log.i(
                    TAG,
                    "$block#$iteration load ok refreshBefore=$refreshBefore refreshAfter=$refreshAfter " +
                        "thermal=$thermalBefore/$thermalAfter",
                )
            },
        )
    }

    // ------------------------------------------------------------------
    // Step 3: prepared replacement measurement and its control record
    // ------------------------------------------------------------------

    /** Per-iteration setup state, published to the following measure block. */
    private var setupPidBefore: String? = null
    private var setupRefreshBeforeHz: Double? = null
    private var setupThermalBefore: Int? = null
    private var setupRunId: String? = null

    private fun runReplaceBlock(block: String, iterations: Int) {
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(
                FrameTimingMetric(),
                MemoryUsageMetric(
                    MemoryUsageMetric.Mode.Max,
                    listOf(MemoryUsageMetric.SubMetric.RssAnon),
                ),
            ),
            compilationMode = CompilationMode.Full(),
            startupMode = null, // the prepared process survives; COLD is forbidden here
            iterations = iterations,
            setupBlock = {
                // Identical prepared replace-ready state for every measured
                // iteration: kill the old process, launch a fresh replace-ready
                // Activity, wait for the seeded/open/no-day ready state, validate
                // the native binding and thermal, and capture PID + refreshBefore.
                killProcess()
                launchReplaceReadyAndWait()
                TimelineDisplayBinding.assertNative(TimelineDisplayBinding.capture(::shell))
                setupPidBefore = pidOf() ?: throw AssertionError("target PID missing in replace setup")
                setupThermalBefore = requireThermal()
                setupRefreshBeforeHz = deviceRefreshHz()
                setupRunId = sentinels.getString(SENTINEL_RUN_ID)
                    ?: throw AssertionError("no replace-ready runId in setup")
                device.waitForIdle()
            },
            measureBlock = {
                // The measured block contains only the one-shot start of the
                // exact replacement traversal and the wait for its completion —
                // no swipes, no prefetch, no process/database setup.
                val runId = setupRunId ?: throw AssertionError("replace-ready runId not captured in setup")
                startReplacementTraversal(runId)
                waitForTraversalComplete()
                val summary = readTraversalSummary()
                TimelineHarnessGates.assertTraversalSummary(summary)
                val refreshAfter = deviceRefreshHz()
                val pidAfter = pidOf() ?: throw AssertionError("target PID missing after replacement traversal")
                val thermalAfter = requireThermal()
                TimelineHarnessGates.assertThermalWithinGate(thermalAfter)
                TimelineHarnessGates.assertPidContinuity(setupPidBefore!!, pidAfter)
                val iteration = requireIteration(iteration)
                manifest().add(
                    TimelineIterationControl(
                        block = block,
                        iteration = iteration,
                        refreshBeforeHz = setupRefreshBeforeHz!!,
                        refreshAfterHz = refreshAfter,
                        thermalBefore = setupThermalBefore!!,
                        thermalAfter = thermalAfter,
                        pidBefore = setupPidBefore!!,
                        pidAfter = pidAfter,
                        // Rev4: the measured replacement iterations report the
                        // exact frozen traversal summary.
                        selectedDayDrawCount = summary.selectedDayDrawCount,
                        replacementCount = summary.replacementCount,
                        maxPublishedItemCount = summary.maxPublishedItemCount,
                        prefetchQueryCount = summary.prefetchQueryCount,
                        firstDate = summary.firstDate,
                        lastDate = summary.lastDate,
                    ),
                )
                Log.i(
                    TAG,
                    "$block#$iteration replace ok summary=$summary " +
                        "refreshBefore=$setupRefreshBeforeHz refreshAfter=$refreshAfter " +
                        "thermal=$setupThermalBefore/$thermalAfter",
                )
            },
        )
    }

    /** One unmeasured complete replacement traversal with the exact frozen summary. */
    private fun runControlTraversal() {
        killProcess()
        launchReplaceReadyAndWait()
        val runId = sentinels.getString(SENTINEL_RUN_ID)
            ?: throw AssertionError("no replace-ready runId for the control traversal")
        startReplacementTraversal(runId)
        waitForTraversalComplete()
        val summary = readTraversalSummary()
        TimelineHarnessGates.assertTraversalSummary(summary)
        manifest().setControlTraversal(summary)
        Log.i(
            TAG,
            "control traversal complete summary=$summary (unmeasured; suite-level control)",
        )
        println("P6Benchmark controlTraversal selectedDayDrawCount=${summary.selectedDayDrawCount} " +
            "replacementCount=${summary.replacementCount} maxPublishedItemCount=${summary.maxPublishedItemCount} " +
            "prefetchQueryCount=${summary.prefetchQueryCount} firstDate=${summary.firstDate} lastDate=${summary.lastDate}")
    }

    // ------------------------------------------------------------------
    // Suite lifecycle (runs once, from binding01LoadA)
    // ------------------------------------------------------------------

    private fun suiteLifecycleInit() {
        if (suiteInitialized) return
        suiteInitialized = true

        // Read-only display binding BEFORE any warmup; never written.
        val displayBefore = TimelineDisplayBinding.capture(::shell)
        TimelineDisplayBinding.assertNative(displayBefore)
        Log.i(TAG, "display settings before peak=${displayBefore.peak} min=${displayBefore.min}")

        // Never measure against a missing last-day slice-hash reference.
        checkNotNull(InstrumentationRegistry.getArguments().getString(ARG_LAST_HASH)) {
            "missing instrumentation argument $ARG_LAST_HASH — the Rev4 binding requires " +
                "the last-day slice hash (binding day 2025-09-26)"
        }

        // Capture package exit-info, PID, battery, and thermal evidence.
        exitInfoBefore = TimelineScrollHarness.captureExitInfo(uiAutomation, targetPackage)
        val battery = shell("dumpsys battery")
        Log.i(
            TAG,
            "battery: ${battery.lineSequence().filter { it.contains("level") || it.contains("status") || it.contains("temperature") }.joinToString(" | ").trim()}",
        )
        val thermalNow = requireThermal()
        TimelineHarnessGates.assertThermalWithinGate(thermalNow)
        pidOf()?.let { Log.i(TAG, "target PID at suite start: $it") }

        // Rev4 prepared-DB contract: 10,000 rows + last-day slice hash before
        // measurement (replaces the Rev3 full-oracle 11,000-item check).
        verifySeededDatabase()

        // Enable airplane mode (P6-introduced change; restored in @AfterClass).
        airplaneOriginal = shell("settings get global airplane_mode_on").trim()
        setAirplaneMode(true)

        // Foreground-control the target.
        val out = shell("am start -W -n $targetPackage/.MainActivity")
        check(out.contains("Status: ok") || out.contains("LaunchState")) {
            "foreground launch of target failed: $out"
        }
        pidOf()?.let { Log.i(TAG, "target PID after foreground control: $it") }

        // Five-minute idle with probes at most 60 s apart.
        idleFiveMinutesWithProbes()

        suiteManifest = TimelineRunManifest(displayBefore)
        Log.i(TAG, "suite lifecycle complete; manifest publication dir ready")
    }

    /**
     * Rev4 prepared-DB verification (`prepare-selected-day-contract`): the
     * prepared shadow DB retains exactly 10,000 rows, the one-time seed wrote
     * exactly 10,000 rows, the version-1 -> 2 migration preserves all 10,000
     * rows and the binding reference day's (last-day) slice hash, the first-day
     * slice hash is preserved through the migration, and the query plan proves
     * the indexed `occurredAt` range search. The last-day hash is compared with
     * the host `p6ExpectedLastDayHash` (the binding passes only the last-day
     * hash; first/empty slice correctness is owned by the accepted Task 6R4-A/B
     * data and rendered contracts).
     */
    private fun verifySeededDatabase() {
        val prevRunId = sentinels.getString(SENTINEL_RUN_ID)
        launchMode(MODE_PREPARE_SELECTED_DAY_CONTRACT)
        waitForFreshScrollReady(prevRunId, MODE_PREPARE_SELECTED_DAY_CONTRACT)
        waitForPrepared()
        val lastHash = lastDayHash()
        TimelineHarnessGates.assertRowCount(waitForSentinel(SENTINEL_ROW_COUNT))
        TimelineHarnessGates.assertSeedWriteCount(waitForSentinel(SENTINEL_SEED_WRITE_COUNT))
        check(waitForSentinel(SENTINEL_MIGRATION_BEFORE_ROW_COUNT) == "10000") {
            "migration before row count != 10000"
        }
        check(waitForSentinel(SENTINEL_MIGRATION_AFTER_ROW_COUNT) == "10000") {
            "migration after row count != 10000"
        }
        check(waitForSentinel(SENTINEL_MIGRATION_LAST_HASH_BEFORE) == lastHash) {
            "migration last-day hash before differs from the host slice hash"
        }
        check(waitForSentinel(SENTINEL_MIGRATION_LAST_HASH_AFTER) == lastHash) {
            "migration last-day hash after differs from the host slice hash"
        }
        check(
            waitForSentinel(SENTINEL_MIGRATION_FIRST_HASH_BEFORE) ==
                waitForSentinel(SENTINEL_MIGRATION_FIRST_HASH_AFTER),
        ) {
            "migration changed the first-day slice hash"
        }
        val plan = waitForSentinel(SENTINEL_MIGRATION_QUERY_PLAN_DETAIL)
        check(plan.uppercase().contains("SEARCH TIMELINE_CARD USING INDEX INDEX_TIMELINE_CARD_OCCURREDAT")) {
            "migration query plan must use the occurredAt index: $plan"
        }
        Log.i(TAG, "shadow DB prepared: 10000 rows, last-day slice hash preserved $lastHash")
    }

    private fun idleFiveMinutesWithProbes() {
        val totalMs = 5 * 60_000L
        val deadline = System.currentTimeMillis() + totalMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            Thread.sleep(minOf(50_000L, remaining))
            val thermal = requireThermal()
            TimelineHarnessGates.assertThermalWithinGate(thermal)
            Log.i(TAG, "idle probe: thermal=$thermal remainingMs=$remaining")
        }
        Log.i(TAG, "five-minute idle complete")
    }

    private fun setAirplaneMode(enabled: Boolean) {
        val state = if (enabled) "1" else "0"
        shell("settings put global airplane_mode_on $state")
        shell("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled")
        val actual = shell("settings get global airplane_mode_on").trim()
        check(actual == state) { "airplane_mode_on failed to set to $state, was $actual" }
    }

    // ------------------------------------------------------------------
    // Shared per-iteration helpers (gates are TimelineHarnessGates)
    // ------------------------------------------------------------------

    private fun lastDayHash(): String =
        InstrumentationRegistry.getArguments().getString(ARG_LAST_HASH)
            ?: throw AssertionError("missing instrumentation argument $ARG_LAST_HASH")

    private fun requireThermal(): Int {
        val status = TimelineHarnessGates.thermalStatusFromDumpsys(shell("dumpsys thermalservice"))
            ?: throw AssertionError("no 'Thermal Status: N' line in dumpsys thermalservice")
        TimelineHarnessGates.assertThermalWithinGate(status)
        return status
    }

    private fun pidOf(): String? = TimelineScrollHarness.pidOf(uiAutomation, targetPackage)

    private fun sentinel(key: String): String =
        sentinels.getString(key) ?: throw AssertionError("missing sentinel '$key'")

    private fun requireIteration(value: Int?): Int =
        value ?: throw AssertionError("MacrobenchmarkScope.iteration not set")

    private fun manifest(): TimelineRunManifest =
        suiteManifest ?: throw AssertionError("suite manifest not initialized (lifecycle init failed)")

    private fun killProcess() {
        shell("am force-stop $targetPackage")
    }

    /**
     * Exactly five manual cold-process load warmups of the bound reference day.
     * Each warmup force-stops the target, launches `load-only` with the frozen
     * date/bounds, and waits for a fresh load-only run + ready. A warmup whose
     * cold launch is swallowed by the platform (see [awaitFreshLoadOnlyRun]) is
     * re-issued once; warmups are controls and produce no Macrobenchmark sample,
     * so this is not a measured-iteration drop/replace/retry.
     */
    private fun runColdLoadWarmups() {
        val testPid = Process.myPid()
        val targetPid = pidOf()
        val canForceStop = targetPid != null && targetPid != testPid.toString()
        var prevRunId: String? = null
        repeat(5) { i ->
            var attempt = 0
            while (true) {
                if (canForceStop) shell("am force-stop $targetPackage")
                prevRunId = sentinels.getString(SENTINEL_RUN_ID)
                launchLoadOnly()
                attempt++
                if (awaitFreshLoadOnlyRun(prevRunId, WARMUP_FRESH_TIMEOUT_MS)) break
                check(attempt < 2) { "warmup $i cold-process load did not start after 2 attempts" }
                Log.w(TAG, "warmup $i cold-process launch swallowed; re-issuing once")
            }
            waitForFreshScrollReady(prevRunId, MODE_LOAD_ONLY)
            waitForReady()
            Log.i(TAG, "warmup $i reached ready")
        }
    }

    /**
     * True when a fresh `load-only` runId appears (different from [prevRunId]
     * with the expected mode) within [timeoutMs]. A swallowed launch keeps the
     * previous sentinel file unchanged, so the caller re-issues the cold launch.
     */
    private fun awaitFreshLoadOnlyRun(prevRunId: String?, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            sentinels.getString(SENTINEL_ERROR)?.let {
                throw AssertionError("warmup target reported an error: $it")
            }
            val runId = sentinels.getString(SENTINEL_RUN_ID)
            if (runId != null && runId != prevRunId &&
                sentinels.getString(SENTINEL_MODE) == MODE_LOAD_ONLY
            ) {
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    private fun launchLoadOnly() {
        val component = "$targetPackage/$ACTIVITY_COMPONENT"
        // CLEAR_TASK forces a fresh Activity instance even when one is already
        // resumed, so the mode stamp + sentinel clear always run.
        val out = shell(
            "am start -W -f 0x10008000 -n $component --es mode $MODE_LOAD_ONLY " +
                "--es selectedDate $REFERENCE_DATE --el dayStart $REFERENCE_DAY_START " +
                "--el nextDayStart $REFERENCE_DAY_END",
        )
        check(out.contains("Status: ok") || out.contains("LaunchState")) {
            "target Activity failed to launch: $out"
        }
    }

    private fun launchMode(mode: String) {
        val component = "$targetPackage/$ACTIVITY_COMPONENT"
        // CLEAR_TASK forces a fresh Activity instance even when one is already
        // resumed, so the mode stamp + sentinel clear always run.
        val out = shell("am start -W -f 0x10008000 -n $component --es mode $mode")
        check(out.contains("Status: ok") || out.contains("LaunchState")) {
            "target Activity failed to launch: $out"
        }
    }

    /** Kills the old process and brings a fresh `replace-ready` Activity up. */
    private fun launchReplaceReadyAndWait() {
        val prevRunId = sentinels.getString(SENTINEL_RUN_ID)
        launchMode(MODE_REPLACE_READY)
        waitForFreshScrollReady(prevRunId, MODE_REPLACE_READY)
        waitForReplaceReady()
    }

    /** Waits for the seeded/open/no-day ready state of `replace-ready`. */
    private fun waitForReplaceReady() {
        val deadline = System.currentTimeMillis() + REPLACE_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            sentinels.getString(SENTINEL_ERROR)?.let {
                throw AssertionError("replace-ready reported an error: $it")
            }
            if (sentinels.getString(SENTINEL_REPLACE_READY) == "true") return
            Thread.sleep(200)
        }
        throw AssertionError("replace-ready sentinel not set within ${REPLACE_READY_TIMEOUT_MS}ms")
    }

    /**
     * One-shot `--activity-single-top` start that triggers the traversal. Per
     * the frozen replace-ready protocol (Task 6R4-B), the non-wait `am start`
     * prints only `Starting: Intent {...}` — there is no `Status: ok` token
     * without `-W` — so the command output is not gated here; completion and
     * correctness are proven by `waitForTraversalComplete` + the exact frozen
     * summary, and a run-ID mismatch or second start fails closed inside the
     * Activity via the `error` sentinel.
     */
    private fun startReplacementTraversal(runId: String) {
        val component = "$targetPackage/$ACTIVITY_COMPONENT"
        val out = shell(
            "am start --activity-single-top -n $component --es mode $MODE_REPLACE_READY " +
                "--ez $EXTRA_START_TRAVERSAL true --es $EXTRA_RUN_ID $runId",
        )
        Log.i(TAG, "one-shot replacement start output: ${out.trim().lineSequence().firstOrNull()}")
    }

    /** Waits for the exact traversal completion sentinel. */
    private fun waitForTraversalComplete() {
        val deadline = System.currentTimeMillis() + TRAVERSAL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            sentinels.getString(SENTINEL_ERROR)?.let {
                throw AssertionError("replace-ready reported an error: $it")
            }
            if (sentinels.getString(SENTINEL_TRAVERSAL_COMPLETE) == "true") return
            Thread.sleep(500)
        }
        throw AssertionError("replacement traversal did not complete within ${TRAVERSAL_TIMEOUT_MS}ms")
    }

    /** Reads the exact frozen selected-day traversal summary sentinels. */
    private fun readTraversalSummary(): SelectedDayTraversalSummary =
        SelectedDayTraversalSummary(
            selectedDayDrawCount = waitForSentinel(SENTINEL_SELECTED_DAY_DRAW_COUNT).toInt(),
            replacementCount = waitForSentinel(SENTINEL_REPLACEMENT_COUNT).toInt(),
            maxPublishedItemCount = waitForSentinel(SENTINEL_MAX_PUBLISHED_ITEM_COUNT).toInt(),
            prefetchQueryCount = waitForSentinel(SENTINEL_PREFETCH_QUERY_COUNT).toInt(),
            firstDate = waitForSentinel(SENTINEL_FIRST_DATE),
            lastDate = waitForSentinel(SENTINEL_LAST_DATE),
        )

    /**
     * Device-visible active refresh observation for the foreground replacement
     * traversal. The `replace-ready` Activity exposes no refresh sentinel (the
     * frozen app surface), so the harness reads the display's current render
     * frame rate from `dumpsys display` — a positive finite observation imported
     * through the shared `requirePositiveRefresh` gate.
     */
    private fun deviceRefreshHz(): Double {
        val dump = shell("dumpsys display")
        val match = Regex("mActiveRenderFrameRate=([0-9.]+)").find(dump)
            ?: throw AssertionError("no mActiveRenderFrameRate line in dumpsys display")
        return TimelineDisplayBinding.requirePositiveRefresh(match.groupValues[1])
    }

    private fun waitForPrepared() {
        val deadline = System.currentTimeMillis() + PREPARE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            sentinels.getString(SENTINEL_ERROR)?.let {
                throw AssertionError("prepare-selected-day-contract reported an error: $it")
            }
            if (sentinels.getString(SENTINEL_PREPARED) == "true") return
            Thread.sleep(250)
        }
        throw AssertionError("selected-day preparation did not finish within ${PREPARE_TIMEOUT_MS}ms")
    }

    /**
     * Waits until the launched Activity stamps a runId different from
     * [prevRunId] (captured immediately before the launch). A per-launch UUID
     * runId is the deterministic freshness proof: the full selected-day load
     * can complete within the `am start -W` window on this device, so the
     * previous transient `ready==null` check raced with the load and could
     * miss the cleared state entirely.
     */
    private fun waitForFreshScrollReady(prevRunId: String?, expectedMode: String) {
        val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val runId = sentinels.getString(SENTINEL_RUN_ID)
            if (runId != null && runId != prevRunId &&
                sentinels.getString(SENTINEL_MODE) == expectedMode
            ) {
                return
            }
            Thread.sleep(100)
        }
        throw AssertionError("launched Activity did not stamp a fresh $expectedMode run within ${LAUNCH_TIMEOUT_MS}ms")
    }

    private fun waitForReady() {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            sentinels.getString(SENTINEL_ERROR)?.let {
                throw AssertionError("target reported an error: $it")
            }
            if (sentinels.getString(SENTINEL_READY) == "true") return
            Thread.sleep(200)
        }
        throw AssertionError("ready sentinel not set within ${READY_TIMEOUT_MS}ms")
    }

    private fun waitForSentinel(key: String, timeoutMs: Long = 120_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            sentinels.getString(key)?.let { return it }
            Thread.sleep(250)
        }
        throw AssertionError("sentinel '$key' not exposed within ${timeoutMs}ms")
    }

    private fun shell(command: String): String =
        uiAutomation.executeShellCommand(command).use { pfd ->
            FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
        }

    /** Records the first suite failure and rethrows; later blocks fail fast. */
    private fun runSafely(blockName: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            if (suiteAbort == null) {
                suiteAbort = t
                Log.e(TAG, "suite aborted in $blockName: ${t.message}", t)
            }
            throw t
        }
    }

    private fun checkSuiteHealthy() {
        suiteAbort?.let { abort ->
            throw AssertionError("suite aborted in an earlier block: ${abort.message}", abort)
        }
    }

    companion object {
        private const val TAG = "P6Benchmark"

        const val BLOCK_LOAD_A = "binding01LoadA"
        const val BLOCK_REPLACE_A = "binding02ReplaceA"
        const val BLOCK_REPLACE_B = "binding03ReplaceB"
        const val BLOCK_LOAD_B = "binding04LoadB"

        /**
         * Frozen trace-section name; identical to the app-side
         * `TimelineQueryToRenderGate.SECTION_NAME`. Macrobenchmark 1.4.1 reports
         * it as the `TimelineQueryToRenderCount` (uniqueness) +
         * `TimelineQueryToRenderSumMs` (duration) host metrics. Kept as a
         * literal here because `:benchmark` has no project dependency on `:app`
         * and never references its classes.
         */
        const val QUERY_TRACE_SECTION = "TimelineQueryToRender"
        const val LOAD_A_ITERATIONS = 15
        const val REPLACE_A_ITERATIONS = 3
        const val REPLACE_B_ITERATIONS = 2
        const val LOAD_B_ITERATIONS = 15

        const val ACTIVITY_COMPONENT = "com.paherdin.record.benchmark.TimelineBenchmarkActivity"

        // Bound reference day + exact Asia/Shanghai half-open bounds (frozen).
        const val REFERENCE_DATE = "2025-09-26"
        const val REFERENCE_DAY_START = 1_758_816_000_000L
        const val REFERENCE_DAY_END = 1_758_902_400_000L
        const val EXPECTED_ITEM_COUNT = "11"

        // Host instrumentation arguments.
        const val ARG_LAST_HASH = "p6ExpectedLastDayHash"

        // Intent extras (frozen across the process boundary; :benchmark never
        // references the target's class constants).
        const val EXTRA_MODE = "mode"
        const val EXTRA_SELECTED_DATE = "selectedDate"
        const val EXTRA_DAY_START = "dayStart"
        const val EXTRA_NEXT_DAY_START = "nextDayStart"
        const val EXTRA_START_TRAVERSAL = "startTraversal"
        const val EXTRA_RUN_ID = "runId"

        const val MODE_LOAD_ONLY = "load-only"
        const val MODE_REPLACE_READY = "replace-ready"
        const val MODE_PREPARE_SELECTED_DAY_CONTRACT = "prepare-selected-day-contract"

        // Sentinel keys (mirror the frozen Activity constants).
        const val SENTINEL_MODE = "mode"
        const val SENTINEL_RUN_ID = "runId"
        const val SENTINEL_READY = "ready"
        const val SENTINEL_ERROR = "error"
        const val SENTINEL_SELECTED_DATE = "selectedDate"
        const val SENTINEL_DAY_START = "dayStart"
        const val SENTINEL_NEXT_DAY_START = "nextDayStart"
        const val SENTINEL_COUNT = "count"
        const val SENTINEL_HASH = "hash"
        const val SENTINEL_QUERY_SQL = "querySql"
        const val SENTINEL_PUBLISHED_DATE_COUNT = "publishedDateCount"
        const val SENTINEL_PUBLISHED_DATES = "publishedDates"
        const val SENTINEL_TRACE_OK = "traceOk"
        const val SENTINEL_REFRESH_BEFORE_HZ = "refreshBeforeHz"
        const val SENTINEL_REFRESH_AFTER_HZ = "refreshAfterHz"
        const val SENTINEL_REPLACE_READY = "replaceReady"
        const val SENTINEL_SELECTED_DAY_DRAW_COUNT = "selectedDayDrawCount"
        const val SENTINEL_REPLACEMENT_COUNT = "replacementCount"
        const val SENTINEL_MAX_PUBLISHED_ITEM_COUNT = "maxPublishedItemCount"
        const val SENTINEL_PREFETCH_QUERY_COUNT = "prefetchQueryCount"
        const val SENTINEL_FIRST_DATE = "firstDate"
        const val SENTINEL_LAST_DATE = "lastDate"
        const val SENTINEL_TRAVERSAL_COMPLETE = "traversalComplete"
        const val SENTINEL_PREPARED = "prepared"
        const val SENTINEL_ROW_COUNT = "rowCount"
        const val SENTINEL_SEED_WRITE_COUNT = "seedWriteCount"
        const val SENTINEL_MIGRATION_BEFORE_ROW_COUNT = "migrationBeforeRowCount"
        const val SENTINEL_MIGRATION_AFTER_ROW_COUNT = "migrationAfterRowCount"
        const val SENTINEL_MIGRATION_FIRST_HASH_BEFORE = "migrationFirstHashBefore"
        const val SENTINEL_MIGRATION_FIRST_HASH_AFTER = "migrationFirstHashAfter"
        const val SENTINEL_MIGRATION_LAST_HASH_BEFORE = "migrationLastHashBefore"
        const val SENTINEL_MIGRATION_LAST_HASH_AFTER = "migrationLastHashAfter"
        const val SENTINEL_MIGRATION_QUERY_PLAN_DETAIL = "migrationQueryPlanDetail"

        const val LAUNCH_TIMEOUT_MS = 60_000L
        const val READY_TIMEOUT_MS = 120_000L
        const val REPLACE_READY_TIMEOUT_MS = 120_000L
        const val TRAVERSAL_TIMEOUT_MS = 600_000L
        const val PREPARE_TIMEOUT_MS = 300_000L
        const val WARMUP_FRESH_TIMEOUT_MS = 15_000L

        // ---- suite-shared state (crosses the four test methods) ----
        private var suiteInitialized = false
        private var suiteManifest: TimelineRunManifest? = null
        private var airplaneOriginal: String? = null
        private var exitInfoBefore: String? = null
        private var suiteAbort: Throwable? = null

        private fun shellStatic(command: String): String =
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand(command).use { pfd ->
                    FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
                }

        private fun setAirplaneModeStatic(enabled: Boolean) {
            val state = if (enabled) "1" else "0"
            shellStatic("settings put global airplane_mode_on $state")
            shellStatic("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled")
            val actual = shellStatic("settings get global airplane_mode_on").trim()
            check(actual == state) { "airplane_mode_on failed to set to $state, was $actual" }
        }

        /** Restores ONLY the airplane setting (the one setting P6 changed). */
        private fun restoreAirplaneSetting() {
            val original = airplaneOriginal ?: return
            if (original == "null") {
                shellStatic("settings delete global airplane_mode_on")
            } else {
                setAirplaneModeStatic(original == "1")
            }
            val restored = shellStatic("settings get global airplane_mode_on").trim()
            check(restored == original) { "airplane_mode_on restore failed: expected $original, was $restored" }
            Log.i(TAG, "airplane_mode_on restored to '$original'")
        }

        /**
         * Class-level close: restores ONLY the airplane setting (the one setting
         * P6 changed), re-asserts the display settings read-only, diffs package
         * exit-info, and finalizes the manifest. Every failure is reported —
         * none is converted to a warning.
         */
        @JvmStatic
        @AfterClass
        fun tearDownSuite() {
            var failure: Throwable? = null
            try {
                restoreAirplaneSetting()
            } catch (t: Throwable) {
                if (failure == null) failure = t
                Log.e(TAG, "airplane restore failed: ${t.message}", t)
            }
            var displayAfter: TimelineDisplaySettings? = null
            try {
                val settings = TimelineDisplayBinding.capture(::shellStatic)
                TimelineDisplayBinding.assertNative(settings)
                displayAfter = settings
                Log.i(TAG, "display settings after peak=${settings.peak} min=${settings.min}")
            } catch (t: Throwable) {
                if (failure == null) failure = t
                Log.e(TAG, "display settings after-assert failed: ${t.message}", t)
            }
            try {
                exitInfoBefore?.let { before ->
                    val after = TimelineScrollHarness.captureExitInfo(
                        InstrumentationRegistry.getInstrumentation().uiAutomation,
                        TARGET_APP_PACKAGE,
                    )
                    // Suite-level gate: only non-USER-REQUESTED new exits
                    // (ANR/crash/OOM/...) fail; the harness's own force-stops
                    // log reason=10 (USER REQUESTED) by design.
                    TimelineHarnessGates.assertNoUnexpectedExitInfo(before, after)
                    Log.i(
                        TAG,
                        "package exit-info clean across the suite (reasons: " +
                            "${TimelineHarnessGates.exitInfoReasonCounts(after)})",
                    )
                }
            } catch (t: Throwable) {
                if (failure == null) failure = t
                Log.e(TAG, "package exit-info diff failed: ${t.message}", t)
            }
            try {
                val manifest = suiteManifest
                if (manifest == null) {
                    Log.w(TAG, "suite manifest never initialized; nothing to finalize")
                } else if (displayAfter == null) {
                    Log.w(TAG, "display settings after-assert failed; manifest NOT finalized")
                } else {
                    manifest.finish(displayAfter)
                    Log.i(
                        TAG,
                        "manifest finalized at ${manifest.publicationDir.absolutePath} " +
                            "/${TimelineRunManifest.MANIFEST_FILE}",
                    )
                }
            } catch (t: Throwable) {
                if (failure == null) failure = t
                Log.e(TAG, "manifest finalization failed: ${t.message}", t)
            }
            failure?.let { throw it }
        }
    }
}
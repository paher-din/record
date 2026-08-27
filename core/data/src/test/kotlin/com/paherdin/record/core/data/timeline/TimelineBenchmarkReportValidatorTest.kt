package com.paherdin.record.core.data.timeline

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tech PoC P6 Task 6R4-B — host validator for the copied Macrobenchmark 1.4.1
 * raw reports and the Rev4 selected-day replacement run manifest.
 *
 * The report contract is the REAL device output shape of macrobenchmark 1.4.1 /
 * AGP 9.3 (verified against the actual `-benchmarkData.json` copied back from
 * the device). The manifest contract is the Rev4 shape: suite-level
 * `displaySettingsBefore`/`displaySettingsAfter` (`peak`=120, `min`=null), a
 * suite-level `controlTraversal` (the one unmeasured replacement traversal)
 * carrying exactly the frozen values, and exactly 35 per-iteration records with
 * the six nullable traversal fields in place of the removed `gestureCount`.
 *
 * The frozen Rev4 validation contract: exactly one `<package>-benchmarkData.json`
 * whose `benchmarks[]` holds the four blocks keyed by bare name, exact iteration
 * counts (loadA 15, replaceA 3, replaceB 2, loadB 15), one raw query duration
 * sample per load iteration (`TimelineQueryToRenderSumMs`; every
 * `TimelineQueryToRenderCount` run exactly 1.0 — trace uniqueness),
 * `memoryRssAnonMaxKb` on every iteration, evenly-sized per-iteration
 * `frameDurationCpuMs`/`frameOverrunMs` sample arrays on every replacement
 * iteration, exactly 35 `.perfetto-trace` files, a `p6-run-manifest.json`
 * carrying the exact native settings before/after plus the exact control
 * traversal and 35 per-iteration control records (positive finite refresh,
 * thermal <= LIGHT, identical PIDs, load records all-null traversal fields,
 * replacement records exactly 1000/999/11/0/2025-09-26/2023-01-01), each
 * benchmark entry's `profilerOutputs` matching its iteration count with 35
 * distinct referenced filenames all resolving to retained traces, and the
 * gates recomputed from the raw samples (query p95 <= 60 ms, pooled frame-CPU
 * p95 <= 16.67 ms, peak RssAnon <= 196,608 KiB). The pooled positive-overrun
 * fraction is recomputed as `jankBp` and retained as a non-gating diagnostic
 * (ADR 0014), never a P6 budget. Negative `frameOverrunMs` values are normal
 * (frames finished early) and stay in the denominator. `gestureCount` is stale
 * Rev3 schema and is rejected.
 */
class TimelineBenchmarkReportValidatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // Fixture builders — minimal hand-written JSON matching the REAL 1.4.1
    // `-benchmarkData.json` shape (verified against actual device output).
    // ------------------------------------------------------------------

    /**
     * One Rev4 per-iteration control record. Refresh and traversal values are
     * raw JSON texts so fixtures can inject zero/NaN/Infinity or omit a field
     * entirely (`null` means "field absent").
     */
    private data class IterationFixture(
        val block: String,
        val iteration: Int,
        val refreshBeforeHz: String? = "120.0",
        val refreshAfterHz: String? = "120.0",
        val thermalBefore: Int = 0,
        val thermalAfter: Int = 0,
        val pidBefore: String = "1234",
        val pidAfter: String = "1234",
        val selectedDayDrawCount: String? = null,
        val replacementCount: String? = null,
        val maxPublishedItemCount: String? = null,
        val prefetchQueryCount: String? = null,
        val firstDate: String? = null,
        val lastDate: String? = null,
        val staleGestureCount: String? = null,
    )

    private fun IterationFixture.toJson(): String {
        val fields = mutableListOf(
            "\"block\":\"$block\"",
            "\"iteration\":$iteration",
        )
        if (refreshBeforeHz != null) fields += "\"refreshBeforeHz\":$refreshBeforeHz"
        if (refreshAfterHz != null) fields += "\"refreshAfterHz\":$refreshAfterHz"
        fields += "\"thermalBefore\":$thermalBefore"
        fields += "\"thermalAfter\":$thermalAfter"
        fields += "\"pidBefore\":\"$pidBefore\""
        fields += "\"pidAfter\":\"$pidAfter\""
        fields += "\"selectedDayDrawCount\":${selectedDayDrawCount ?: "null"}"
        fields += "\"replacementCount\":${replacementCount ?: "null"}"
        fields += "\"maxPublishedItemCount\":${maxPublishedItemCount ?: "null"}"
        fields += "\"prefetchQueryCount\":${prefetchQueryCount ?: "null"}"
        fields += "\"firstDate\":${firstDate ?: "null"}"
        fields += "\"lastDate\":${lastDate ?: "null"}"
        if (staleGestureCount != null) fields += "\"gestureCount\":$staleGestureCount"
        return "{" + fields.joinToString(",") + "}"
    }

    /**
     * The exact frozen Rev4 traversal summary (spec §4.5) as raw JSON values:
     * counts are unquoted numbers, dates are pre-quoted strings.
     */
    private val frozenTraversalFields: Map<String, String> = linkedMapOf(
        "selectedDayDrawCount" to "1000",
        "replacementCount" to "999",
        "maxPublishedItemCount" to "11",
        "prefetchQueryCount" to "0",
        "firstDate" to "\"2025-09-26\"",
        "lastDate" to "\"2023-01-01\"",
    )

    private fun IterationFixture.withTraversal(traversal: Map<String, String>?): IterationFixture =
        if (traversal == null) this.copy(
            selectedDayDrawCount = null, replacementCount = null, maxPublishedItemCount = null,
            prefetchQueryCount = null, firstDate = null, lastDate = null,
        ) else this.copy(
            selectedDayDrawCount = traversal["selectedDayDrawCount"],
            replacementCount = traversal["replacementCount"],
            maxPublishedItemCount = traversal["maxPublishedItemCount"],
            prefetchQueryCount = traversal["prefetchQueryCount"],
            firstDate = traversal["firstDate"],
            lastDate = traversal["lastDate"],
        )

    /** The exact frozen 35-record sequence: 15 load / 3 replace / 2 replace / 15 load. */
    private fun defaultIterations(): List<IterationFixture> =
        blockIterations(BLOCK_LOAD_A, 15, null) +
            blockIterations(BLOCK_REPLACE_A, 3, frozenTraversalFields) +
            blockIterations(BLOCK_REPLACE_B, 2, frozenTraversalFields) +
            blockIterations(BLOCK_LOAD_B, 15, null)

    private fun blockIterations(
        block: String,
        count: Int,
        traversal: Map<String, String>?,
    ): List<IterationFixture> =
        (0 until count).map { IterationFixture(block, it).withTraversal(traversal) }

    private fun controlTraversalJson(traversal: Map<String, String>): String =
        "{" + traversal.entries.joinToString(",") { (k, v) -> "\"$k\":$v" } + "}"

    private fun controlTraversalJson(): String = controlTraversalJson(frozenTraversalFields)

    /** Global perfetto index where a block's profiler outputs start. */
    private fun traceStartFor(methodName: String): Int = when (methodName) {
        BLOCK_LOAD_A -> 0
        BLOCK_REPLACE_A -> 15
        BLOCK_REPLACE_B -> 18
        BLOCK_LOAD_B -> 20
        else -> 0
    }

    private fun singleJson(vararg runs: Double): String =
        """{"minimum":0.0,"maximum":0.0,"median":0.0,"coefficientOfVariation":0.0,"runs":[${runs.joinToString(",")}]}"""

    private fun sampledJson(vararg runs: List<Double>): String =
        """{"P50":0.0,"P90":0.0,"P95":0.0,"P99":0.0,"runs":[${runs.joinToString(",") { "[" + it.joinToString(",") + "]" }}]}"""

    /**
     * One real-shape load benchmark entry. `querySumRuns` are the 1.4.1
     * `TimelineQueryToRenderSumMs` durations (ms); `queryCountRuns` are the
     * `TimelineQueryToRenderCount` runs (must all equal 1.0).
     */
    private fun loadBenchmarkJson(
        name: String,
        iterations: Int,
        querySumRuns: List<Double>? = List(iterations) { 45.0 },
        memoryRuns: List<Double>? = List(iterations) { 150_000.0 },
        queryCountRuns: List<Double>? = List(iterations) { 1.0 },
        warmupIterations: Int? = 0,
        repeatIterations: Int = iterations,
        profilerOutputs: List<String>? = null,
    ): String {
        val metricParts = mutableListOf<String>()
        queryCountRuns?.let {
            metricParts += "\"$TRACE_COUNT_METRIC\":${singleJson(*it.toDoubleArray())}"
        }
        querySumRuns?.let {
            metricParts += "\"$TRACE_SUM_METRIC\":${singleJson(*it.toDoubleArray())}"
        }
        memoryRuns?.let {
            metricParts += "\"$MEMORY_METRIC\":${singleJson(*it.toDoubleArray())}"
        }
        val profilers = profilerOutputs
            ?: (0 until iterations).map { "iter_${traceStartFor(name) + it}.perfetto-trace" }
        return benchmarkJson(
            name = name,
            metrics = metricParts,
            sampledMetrics = emptyList(),
            warmupIterations = warmupIterations,
            repeatIterations = repeatIterations,
            profilerOutputs = profilers,
        )
    }

    /** Frames for one replacement iteration: [frameCount] CPU samples at [ms]. */
    private fun frames(frameCount: Int, ms: Double): List<Double> = List(frameCount) { ms }

    /**
     * Overruns for one replacement iteration: [frameCount] samples. Real 1.4.1
     * overruns are NEGATIVE for on-time frames and positive for dropped ones.
     * [positiveOverruns] of each iteration's samples are 1.0, the rest are
     * [baseMs] (default -5.0, non-jank but still in the denominator).
     */
    private fun overruns(
        frameCount: Int,
        baseMs: Double = -5.0,
        positiveOverruns: Int = 0,
    ): List<Double> = MutableList(frameCount) { baseMs }.apply {
        for (i in 0 until positiveOverruns) this[i] = 1.0
    }

    /**
     * One real-shape replacement benchmark entry: `frameCount` +
     * `memoryRssAnonMaxKb` in `metrics`; nested `frameDurationCpuMs` /
     * `frameOverrunMs` in `sampledMetrics` (`runs[iter][frame]`).
     */
    private fun replacementBenchmarkJson(
        name: String,
        iterations: Int,
        frameCount: Int = 40,
        frameMs: Double = 8.0,
        positiveOverrunsPerIteration: Int = 0,
        overrunBaseMs: Double = -5.0,
        memoryRuns: List<Double>? = List(iterations) { 150_000.0 },
        profilerOutputs: List<String>? = null,
    ): String {
        val metricParts = mutableListOf(
            "\"frameCount\":${singleJson(*List(iterations) { frameCount.toDouble() }.toDoubleArray())}",
        )
        memoryRuns?.let {
            metricParts += "\"$MEMORY_METRIC\":${singleJson(*it.toDoubleArray())}"
        }
        val sampledParts = mutableListOf(
            "\"$FRAME_CPU_METRIC\":${sampledJson(*List(iterations) { frames(frameCount, frameMs) }.toTypedArray())}",
            "\"$FRAME_OVERRUN_METRIC\":${sampledJson(*List(iterations) { overruns(frameCount, overrunBaseMs, positiveOverrunsPerIteration) }.toTypedArray())}",
        )
        val profilers = profilerOutputs
            ?: (0 until iterations).map { "iter_${traceStartFor(name) + it}.perfetto-trace" }
        return benchmarkJson(
            name = name,
            metrics = metricParts,
            sampledMetrics = sampledParts,
            warmupIterations = 0,
            repeatIterations = iterations,
            profilerOutputs = profilers,
        )
    }

    /** One `BenchmarkData.TestResult` entry in the real 1.4.1 shape. */
    private fun benchmarkJson(
        name: String,
        metrics: List<String>,
        sampledMetrics: List<String>,
        warmupIterations: Int?,
        repeatIterations: Int,
        profilerOutputs: List<String>,
    ): String = """
        {
          "name": "$name",
          "params": {},
          "className": "$BENCHMARK_CLASS",
          "totalRunTimeNs": 1000,
          "metrics": {${metrics.joinToString(",")}},
          "sampledMetrics": {${sampledMetrics.joinToString(",")}},
          "warmupIterations": ${warmupIterations ?: "null"},
          "repeatIterations": $repeatIterations,
          "thermalThrottleSleepSeconds": 0,
          "profilerOutputs": [${profilerOutputs.joinToString(",") {
            """{"type":"PerfettoTrace","label":"$it","filename":"$it"}"""
          }}]
        }
    """.trimIndent()

    private fun defaultLoadA() =
        loadBenchmarkJson(BLOCK_LOAD_A, 15)

    private fun defaultReplaceA() =
        replacementBenchmarkJson(BLOCK_REPLACE_A, 3)

    private fun defaultReplaceB() =
        replacementBenchmarkJson(BLOCK_REPLACE_B, 2)

    private fun defaultLoadB() =
        loadBenchmarkJson(BLOCK_LOAD_B, 15)

    /** Writes the single real-shape aggregate `<package>-benchmarkData.json`. */
    private fun writeBenchmarkData(root: File, vararg entries: String) {
        val json =
            """{"context":{"build":{"fingerprint":"fixture"}},"benchmarks":[${entries.joinToString(",")}]}"""
        File(root, REPORT_DATA_FILE).writeText(json)
    }

    private fun writeTraces(root: File, count: Int) {
        for (i in 0 until count) {
            val trace = File(root, "traces/iter_$i.perfetto-trace")
            trace.parentFile?.mkdirs()
            trace.writeText("x")
        }
    }

    private fun writeManifest(
        root: File,
        iterations: List<IterationFixture>,
        peakBefore: String = "120",
        minBefore: String = "null",
        peakAfter: String = "120",
        minAfter: String = "null",
        controlTraversalJson: String? = controlTraversalJson(),
    ) {
        val controlJson = controlTraversalJson?.let { "\"controlTraversal\":$it," } ?: ""
        File(root, "p6-run-manifest.json").writeText(
            buildString {
                append(
                    """{"suite":"TimelineBenchmark","displaySettingsBefore":{"peak":"$peakBefore","min":"$minBefore"},"displaySettingsAfter":{"peak":"$peakAfter","min":"$minAfter"},""",
                )
                append(controlJson)
                append("\"iterations\":[")
                append(iterations.joinToString(",") { it.toJson() })
                append("]}")
            },
        )
    }

    /**
     * Full valid tree in the REAL 1.4.1 shape: single benchmark-data file with
     * 4 entries, 35 traces, Rev4 run manifest (exact control traversal).
     */
    private fun writeValidTree(
        root: File,
        loadA: String = defaultLoadA(),
        replaceA: String = defaultReplaceA(),
        replaceB: String = defaultReplaceB(),
        loadB: String = defaultLoadB(),
    ) {
        writeBenchmarkData(root, loadA, replaceA, replaceB, loadB)
        writeTraces(root, 35)
        writeManifest(root, defaultIterations())
    }

    private fun validate(root: File) =
        TimelineBenchmarkReportValidator.validate(root)

    private fun assertPasses(root: File) {
        val result = validate(root)
        assertTrue("expected PASS, got problems: ${result.problems}", result.problems.isEmpty())
    }

    private fun assertFails(root: File) {
        val result = validate(root)
        assertTrue("expected at least one validation problem, got none", result.problems.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // Positive controls
    // ------------------------------------------------------------------

    @Test
    fun validFrozenReportTreePassesAllFourGates() {
        val root = tmp.newFolder("valid")
        writeValidTree(root)
        assertPasses(root)
    }

    @Test
    fun nativeSettingsAndThirtyFiveRefreshPairsPass() {
        val root = tmp.newFolder("native-settings-35")
        writeValidTree(root)
        assertPasses(root)
    }

    @Test
    fun variableRefreshUnderNativeSettingsPasses() {
        // One 120.0 -> 60.0 pair and one 60.0 -> 120.0 pair while both suite
        // snapshots remain peak="120"/min="null": a refresh change between two
        // positive finite observations must NOT fail — no fixed-120 gate.
        val root = tmp.newFolder("variable-refresh")
        writeValidTree(root)
        val iterations = defaultIterations().map {
            when {
                it.block == BLOCK_REPLACE_A && it.iteration == 0 ->
                    it.copy(refreshBeforeHz = "120.0", refreshAfterHz = "60.0")
                it.block == BLOCK_REPLACE_B && it.iteration == 0 ->
                    it.copy(refreshBeforeHz = "60.0", refreshAfterHz = "120.0")
                else -> it
            }
        }
        writeManifest(root, iterations)
        assertPasses(root)
    }

    @Test
    fun frozenControlTraversalPasses() {
        val root = tmp.newFolder("frozen-control")
        writeValidTree(root)
        val result = validate(root)
        assertTrue("expected PASS, got: ${result.problems}", result.problems.isEmpty())
        val control = result.controlTraversal
        assertTrue("control traversal must be exposed", control != null)
        assertEquals(1000, control!!.selectedDayDrawCount)
        assertEquals(999, control.replacementCount)
        assertEquals(11, control.maxPublishedItemCount)
        assertEquals(0, control.prefetchQueryCount)
        assertEquals("2025-09-26", control.firstDate)
        assertEquals("2023-01-01", control.lastDate)
    }

    @Test
    fun frameOverrunZeroIsNonJankAndStaysInDenominator() {
        // 500 pooled frames across 5 replacement iterations: 490 overrun samples
        // are exactly 0.0 (non-jank) and 10 are positive. The frozen formula
        // keeps every sample in the denominator: bp = 10/500 * 10_000 = 200,
        // exactly the 2% boundary, which passes. If zeros were excluded from
        // the denominator the fraction would be 10/10 = 10,000 bp and this
        // valid report would fail — so this case proves zeros are counted.
        val root = tmp.newFolder("zero-overrun")
        writeValidTree(
            root,
            replaceA = replacementBenchmarkJson(BLOCK_REPLACE_A, 3, frameCount = 100, overrunBaseMs = 0.0, positiveOverrunsPerIteration = 2),
            replaceB = replacementBenchmarkJson(BLOCK_REPLACE_B, 2, frameCount = 100, overrunBaseMs = 0.0, positiveOverrunsPerIteration = 2),
        )
        val result = validate(root)
        assertTrue("expected PASS at the exact 2% boundary, got: ${result.problems}", result.problems.isEmpty())
        assertEquals("jank must be 10/500 = 200 bp with zeros in the denominator", 200L, result.jankBp)
    }

    @Test
    fun negativeOverrunsAreNonJankAndStayInDenominator() {
        // Real 1.4.1 overruns are negative for on-time frames; they must stay in
        // the denominator with 0% jank (and no division hazard).
        val root = tmp.newFolder("real-negative-overrun")
        writeValidTree(root) // default baseMs = -5.0, positive overruns = 0
        val result = validate(root)
        assertTrue("negative overruns must not be jank: ${result.problems}", result.problems.isEmpty())
        assertEquals(0L, result.jankBp)
    }

    @Test
    fun parserReadsRealAggregateBenchmarkData() {
        val root = tmp.newFolder("parser")
        writeBenchmarkData(root, defaultLoadA(), defaultReplaceA())
        val reports = TimelineBenchmarkReportParser.parseBenchmarkDataFile(
            File(root, REPORT_DATA_FILE),
        )
        val loadA = reports.single { it.name == BLOCK_LOAD_A }
        assertEquals(BLOCK_LOAD_A, loadA.name)
        assertEquals(15, loadA.singleMetrics.getValue(TRACE_SUM_METRIC).size)
        assertEquals(15, loadA.singleMetrics.getValue(TRACE_COUNT_METRIC).size)
        assertEquals(15, loadA.singleMetrics.getValue(MEMORY_METRIC).size)
        assertEquals(45.0, loadA.singleMetrics.getValue(TRACE_SUM_METRIC).first(), 0.001)
        assertEquals(1.0, loadA.singleMetrics.getValue(TRACE_COUNT_METRIC).first(), 0.001)
        assertEquals(15, loadA.repeatIterations)
        assertEquals(0, loadA.warmupIterations)
        assertEquals(15, loadA.profilerOutputs.size)
        val replaceA = reports.single { it.name == BLOCK_REPLACE_A }
        assertEquals(3, replaceA.sampledMetrics.getValue(FRAME_CPU_METRIC).size)
        assertEquals(3, replaceA.sampledMetrics.getValue(FRAME_OVERRUN_METRIC).size)
        assertEquals(40, replaceA.sampledMetrics.getValue(FRAME_CPU_METRIC)[0].size)
    }

    // ------------------------------------------------------------------
    // Negative controls — each must produce at least one problem
    // ------------------------------------------------------------------

    @Test
    fun missingBenchmarkDataFileFails() {
        val root = tmp.newFolder("missing-data-file")
        writeTraces(root, 35)
        writeManifest(root, defaultIterations())
        assertFails(root)
    }

    @Test
    fun missingQueryTraceFails() {
        val root = tmp.newFolder("missing-trace")
        writeValidTree(root, loadA = loadBenchmarkJson(BLOCK_LOAD_A, 15, querySumRuns = null))
        assertFails(root)
    }

    @Test
    fun missingQueryCountFails() {
        val root = tmp.newFolder("missing-count")
        writeValidTree(root, loadA = loadBenchmarkJson(BLOCK_LOAD_A, 15, queryCountRuns = null))
        assertFails(root)
    }

    @Test
    fun duplicateQueryTraceFails() {
        val root = tmp.newFolder("duplicate-trace")
        writeValidTree(root)
        // 16 samples for a 15-iteration block = a second trace matched once.
        writeValidTree(root, loadA = loadBenchmarkJson(BLOCK_LOAD_A, 15, querySumRuns = List(16) { 45.0 }))
        assertFails(root)
    }

    @Test
    fun twentyNineQuerySamplesFail() {
        val root = tmp.newFolder("29")
        writeValidTree(root)
        // loadA with 14 iterations -> 14 + 15 = 29 samples, one iteration missing.
        writeValidTree(root, loadA = loadBenchmarkJson(BLOCK_LOAD_A, 14))
        assertFails(root)
    }

    @Test
    fun thirtyOneQuerySamplesFail() {
        val root = tmp.newFolder("31")
        writeValidTree(root)
        // loadB with 16 iterations -> 15 + 16 = 31 samples, one iteration extra.
        writeValidTree(root, loadB = loadBenchmarkJson(BLOCK_LOAD_B, 16))
        assertFails(root)
    }

    @Test
    fun loadCountNotOneFails() {
        // Trace uniqueness: a TimelineQueryToRenderCount != 1.0 on any load
        // iteration means the slice was open/duplicated and must fail.
        val root = tmp.newFolder("count-not-one")
        writeValidTree(
            root,
            loadA = loadBenchmarkJson(
                BLOCK_LOAD_A, 15,
                queryCountRuns = List(14) { 1.0 } + 0.0,
            ),
        )
        assertFails(root)
    }

    @Test
    fun missingFrameArrayFails() {
        val root = tmp.newFolder("missing-frame")
        writeValidTree(root)
        // replaceA without any frame metric.
        writeValidTree(
            root,
            replaceA = benchmarkJson(
                name = BLOCK_REPLACE_A,
                metrics = listOf("\"frameCount\":${singleJson(*List(3) { 40.0 }.toDoubleArray())}"),
                sampledMetrics = emptyList(),
                warmupIterations = 0,
                repeatIterations = 3,
                profilerOutputs = (15..17).map { "iter_$it.perfetto-trace" },
            ),
        )
        assertFails(root)
    }

    @Test
    fun emptyFrameArrayFails() {
        val root = tmp.newFolder("empty-frame")
        writeValidTree(root)
        // replaceB with one empty frame run — a missing frame sample in disguise.
        writeValidTree(
            root,
            replaceB = replacementBenchmarkJson(
                BLOCK_REPLACE_B, 2,
                frameCount = 0,
                positiveOverrunsPerIteration = 0,
            ),
        )
        assertFails(root)
    }

    @Test
    fun overrunArrayShorterThanFrameArrayFails() {
        val root = tmp.newFolder("overrun-shorter")
        writeValidTree(root)
        // replaceA: three 40-frame CPU runs but iteration 1's overrun run has only
        // 10 samples — the per-iteration pair lengths must match.
        val badReplaceA = benchmarkJson(
            name = BLOCK_REPLACE_A,
            metrics = listOf(
                "\"frameCount\":${singleJson(40.0, 40.0, 40.0)}",
                "\"$MEMORY_METRIC\":${singleJson(150_000.0, 150_000.0, 150_000.0)}",
            ),
            sampledMetrics = listOf(
                "\"$FRAME_CPU_METRIC\":${sampledJson(frames(40, 8.0), frames(40, 8.0), frames(40, 8.0))}",
                "\"$FRAME_OVERRUN_METRIC\":${sampledJson(overruns(40, -5.0, 0), overruns(10, -5.0, 0), overruns(40, -5.0, 0))}",
            ),
            warmupIterations = 0,
            repeatIterations = 3,
            profilerOutputs = (15..17).map { "iter_$it.perfetto-trace" },
        )
        writeValidTree(root, replaceA = badReplaceA)
        assertFails(root)
    }

    @Test
    fun missingRssAnonFails() {
        val root = tmp.newFolder("missing-rss")
        writeValidTree(root, loadA = loadBenchmarkJson(BLOCK_LOAD_A, 15, memoryRuns = null))
        assertFails(root)
    }

    @Test
    fun wrongReportNameFails() {
        val root = tmp.newFolder("wrong-name")
        writeValidTree(root, loadA = loadBenchmarkJson("binding99Wrong", 15))
        assertFails(root)
    }

    @Test
    fun extraBenchmarkBlockFails() {
        val root = tmp.newFolder("extra-block")
        writeValidTree(root)
        // One extra benchmark entry that is not one of the four blocks.
        writeBenchmarkData(root)
        writeBenchmarkData(
            root,
            defaultLoadA(),
            defaultReplaceA(),
            defaultReplaceB(),
            defaultLoadB(),
            loadBenchmarkJson("binding05Extra", 1),
        )
        writeTraces(root, 35)
        writeManifest(root, defaultIterations())
        assertFails(root)
    }

    @Test
    fun missingTraceFileFails() {
        val root = tmp.newFolder("missing-tracefile")
        writeValidTree(root)
        // 34 traces instead of 35: one measured iteration lost its Perfetto trace.
        File(root, "traces/iter_34.perfetto-trace").delete()
        assertFails(root)
    }

    // ------------------------------------------------------------------
    // Rev4 traversal-control negatives — gestureCount is stale schema
    // ------------------------------------------------------------------

    @Test
    fun staleGestureCountFieldIsNotParsedAndFailsReplacement() {
        // A record that still carries the Rev3 gestureCount field but no Rev4
        // traversal fields is inspected as a replacement iteration with null
        // traversal — the parser never reads gestureCount, and the record fails
        // the exact traversal requirement instead of silently passing.
        val root = tmp.newFolder("stale-gesture")
        writeValidTree(root)
        val iterations = defaultIterations().map {
            if (it.block == BLOCK_REPLACE_A && it.iteration == 0) {
                it.copy(
                    selectedDayDrawCount = null,
                    replacementCount = null,
                    maxPublishedItemCount = null,
                    prefetchQueryCount = null,
                    firstDate = null,
                    lastDate = null,
                    staleGestureCount = "950",
                )
            } else {
                it
            }
        }
        writeManifest(root, iterations)
        assertFails(root)
        val parsed = TimelineBenchmarkReportParser.parseRunManifest(File(root, "p6-run-manifest.json"))
        val stale = parsed.iterations.first { it.block == BLOCK_REPLACE_A && it.iteration == 0 }
        // The parser exposes no gestureCount channel at all.
        assertEquals(null, stale.selectedDayDrawCount)
        assertEquals(null, stale.lastDate)
    }

    @Test
    fun replacementIterationWithValidTraversalAndStaleGestureCountFails() {
        // A replacement record that carries BOTH the exact frozen traversal
        // summary AND a stale Rev3 gestureCount must be rejected as stale
        // schema — the frozen Rev4 report contract has no gestureCount channel.
        val root = tmp.newFolder("stale-gesture-valid-traversal")
        writeValidTree(root)
        val iterations = defaultIterations().mapIndexed { i, it ->
            if (i == 15) it.withTraversal(frozenTraversalFields).copy(staleGestureCount = "936") else it
        }
        writeManifest(root, iterations)
        assertFails(root)
    }

    @Test
    fun loadIterationWithStaleGestureCountFails() {
        // An otherwise-valid load record that still carries the stale Rev3
        // gestureCount field fails as stale schema, never silently passing.
        val root = tmp.newFolder("stale-gesture-load")
        writeValidTree(root)
        val iterations = defaultIterations().mapIndexed { i, it ->
            if (i == 0) it.copy(staleGestureCount = "936") else it
        }
        writeManifest(root, iterations)
        assertFails(root)
    }

    @Test
    fun missingControlTraversalFails() {
        val root = tmp.newFolder("missing-control")
        writeValidTree(root)
        writeManifest(root, defaultIterations(), controlTraversalJson = null)
        assertFails(root)
    }

    @Test
    fun wrongControlTraversalValueFails() {
        for ((label, mutated) in listOf(
            "draw" to frozenTraversalFields + ("selectedDayDrawCount" to "999"),
            "replacement" to frozenTraversalFields + ("replacementCount" to "998"),
            "max" to frozenTraversalFields + ("maxPublishedItemCount" to "12"),
            "prefetch" to frozenTraversalFields + ("prefetchQueryCount" to "1"),
            "first" to frozenTraversalFields + ("firstDate" to "\"2025-09-25\""),
            "last" to frozenTraversalFields + ("lastDate" to "\"2023-01-02\""),
        )) {
            val root = tmp.newFolder("wrong-control-$label")
            writeValidTree(root)
            writeManifest(root, defaultIterations(), controlTraversalJson = controlTraversalJson(mutated))
            assertFails(root)
        }
    }

    @Test
    fun loadIterationWithTraversalFieldsFails() {
        val root = tmp.newFolder("load-traversal")
        writeValidTree(root)
        val iterations = defaultIterations().mapIndexed { i, it ->
            if (i == 0) it.withTraversal(frozenTraversalFields) else it
        }
        writeManifest(root, iterations)
        assertFails(root)
    }

    @Test
    fun partiallyFilledLoadIterationFails() {
        val root = tmp.newFolder("partial-load")
        writeValidTree(root)
        val iterations = defaultIterations().mapIndexed { i, it ->
            if (i == 0) it.copy(selectedDayDrawCount = "999") else it
        }
        writeManifest(root, iterations)
        assertFails(root)
    }

    @Test
    fun replacementIterationMissingTraversalFails() {
        val root = tmp.newFolder("missing-replacement-traversal")
        writeValidTree(root)
        val iterations = defaultIterations().mapIndexed { i, it ->
            if (i == 15) it.withTraversal(null) else it
        }
        writeManifest(root, iterations)
        assertFails(root)
    }

    @Test
    fun replacementIterationWrongTraversalFails() {
        for ((label, mutated) in listOf(
            "draw" to frozenTraversalFields + ("selectedDayDrawCount" to "999"),
            "replacement" to frozenTraversalFields + ("replacementCount" to "998"),
            "max" to frozenTraversalFields + ("maxPublishedItemCount" to "12"),
            "prefetch" to frozenTraversalFields + ("prefetchQueryCount" to "1"),
            "first" to frozenTraversalFields + ("firstDate" to "\"2025-09-25\""),
            "last" to frozenTraversalFields + ("lastDate" to "\"2023-01-02\""),
        )) {
            val root = tmp.newFolder("replacement-wrong-$label")
            writeValidTree(root)
            val iterations = defaultIterations().mapIndexed { i, it ->
                if (i == 15) it.withTraversal(mutated) else it
            }
            writeManifest(root, iterations)
            assertFails(root)
        }
    }

    @Test
    fun partiallyFilledReplacementIterationFails() {
        val root = tmp.newFolder("partial-replacement")
        writeValidTree(root)
        val iterations = defaultIterations().mapIndexed { i, it ->
            if (i == 15) {
                IterationFixture(
                    BLOCK_REPLACE_A,
                    15,
                    selectedDayDrawCount = "1000",
                    firstDate = "\"2025-09-26\"",
                )
            } else {
                it
            }
        }
        writeManifest(root, iterations)
        assertFails(root)
    }

    @Test
    fun missingRunManifestFails() {
        val root = tmp.newFolder("no-manifest")
        writeValidTree(root)
        File(root, "p6-run-manifest.json").delete()
        assertFails(root)
    }

    @Test
    fun changedPeakSettingFails() {
        val root = tmp.newFolder("changed-peak")
        writeValidTree(root)
        writeManifest(root, defaultIterations(), peakBefore = "60", minBefore = "null")
        assertFails(root)
    }

    @Test
    fun changedMinSettingFails() {
        val root = tmp.newFolder("changed-min")
        writeValidTree(root)
        writeManifest(root, defaultIterations(), peakBefore = "120", minBefore = "60")
        assertFails(root)
    }

    @Test
    fun missingRefreshBeforeFails() {
        val root = tmp.newFolder("missing-refresh-before")
        writeValidTree(root)
        writeManifest(
            root,
            defaultIterations().mapIndexed { i, it -> if (i == 0) it.copy(refreshBeforeHz = null) else it },
        )
        assertFails(root)
    }

    @Test
    fun missingRefreshAfterFails() {
        val root = tmp.newFolder("missing-refresh-after")
        writeValidTree(root)
        writeManifest(
            root,
            defaultIterations().mapIndexed { i, it -> if (i == 0) it.copy(refreshAfterHz = null) else it },
        )
        assertFails(root)
    }

    @Test
    fun zeroNanOrInfiniteRefreshFails() {
        for ((label, raw) in listOf("zero" to "0.0", "nan" to "NaN", "inf" to "Infinity")) {
            val root = tmp.newFolder("bad-refresh-$label")
            writeValidTree(root)
            writeManifest(
                root,
                defaultIterations().mapIndexed { i, it -> if (i == 0) it.copy(refreshBeforeHz = raw) else it },
            )
            assertFails(root)
        }
    }

    @Test
    fun missingOrDuplicateIterationRecordFails() {
        // Missing: only 34 records — one iteration record dropped.
        val rootMissing = tmp.newFolder("missing-record")
        writeValidTree(rootMissing)
        writeManifest(rootMissing, defaultIterations().drop(1))
        assertFails(rootMissing)

        // Duplicate: 36 records — one (block, iteration) pair repeated.
        val rootDuplicate = tmp.newFolder("duplicate-record")
        writeValidTree(rootDuplicate)
        writeManifest(rootDuplicate, defaultIterations() + defaultIterations().first())
        assertFails(rootDuplicate)
    }

    @Test
    fun missingProfilerOutputFails() {
        val root = tmp.newFolder("missing-profiler")
        writeValidTree(root, loadA = loadBenchmarkJson(BLOCK_LOAD_A, 15, profilerOutputs = emptyList()))
        assertFails(root)
    }

    @Test
    fun profilerOutputWithoutCopiedTraceFails() {
        val root = tmp.newFolder("profiler-no-trace")
        writeValidTree(
            root,
            replaceA = replacementBenchmarkJson(
                BLOCK_REPLACE_A, 3,
                profilerOutputs = listOf("iter_15.perfetto-trace", "iter_16.perfetto-trace", "iter_999.perfetto-trace"),
            ),
        )
        assertFails(root)
    }

    @Test
    fun queryP95BudgetViolationFails() {
        val root = tmp.newFolder("query-budget")
        writeValidTree(root)
        // p95 = 61 ms > 60 ms budget.
        writeValidTree(
            root,
            loadA = loadBenchmarkJson(BLOCK_LOAD_A, 15, querySumRuns = List(15) { 61.0 }),
            loadB = loadBenchmarkJson(BLOCK_LOAD_B, 15, querySumRuns = List(15) { 61.0 }),
        )
        assertFails(root)
    }

    @Test
    fun frameP95BudgetViolationFails() {
        val root = tmp.newFolder("frame-budget")
        writeValidTree(root)
        // pooled p95 = 20 ms > 16.67 ms budget.
        writeValidTree(
            root,
            replaceA = replacementBenchmarkJson(BLOCK_REPLACE_A, 3, frameMs = 20.0),
            replaceB = replacementBenchmarkJson(BLOCK_REPLACE_B, 2, frameMs = 20.0),
        )
        assertFails(root)
    }

    @Test
    fun jankAboveTwoPercentIsRetainedDiagnosticAndPasses() {
        val root = tmp.newFolder("jank-diagnostic")
        writeValidTree(root)
        // 15 of 500 pooled frames overrun across the 5 replacement iterations =
        // 300 bp > 200 bp (the old Rev4 2% gate). Under ADR 0014 the
        // positive-overrun fraction is a recomputed, retained diagnostic that
        // adds no validation problem.
        writeValidTree(
            root,
            replaceA = replacementBenchmarkJson(BLOCK_REPLACE_A, 3, frameCount = 100, positiveOverrunsPerIteration = 3),
            replaceB = replacementBenchmarkJson(BLOCK_REPLACE_B, 2, frameCount = 100, positiveOverrunsPerIteration = 3),
        )
        val result = validate(root)
        assertTrue("jank above 2% must not fail the suite, got: ${result.problems}", result.problems.isEmpty())
        assertEquals("jank must be recomputed as 15/500 = 300 bp", 300L, result.jankBp)
    }

    @Test
    fun memoryBudgetViolationFails() {
        val root = tmp.newFolder("memory-budget")
        writeValidTree(root)
        // Peak 200,000 KiB > 196,608 KiB budget.
        writeValidTree(
            root,
            loadA = loadBenchmarkJson(BLOCK_LOAD_A, 15, memoryRuns = List(15) { 200_000.0 }),
        )
        assertFails(root)
    }

    @Test
    fun zeroQuerySampleFails() {
        val root = tmp.newFolder("zero-query")
        writeValidTree(
            root,
            loadA = loadBenchmarkJson(BLOCK_LOAD_A, 15, querySumRuns = List(14) { 45.0 } + 0.0),
        )
        assertFails(root)
    }

    companion object {
        const val BENCHMARK_CLASS = "com.paherdin.record.benchmark.TimelineBenchmark"
        const val BLOCK_LOAD_A = "binding01LoadA"
        const val BLOCK_REPLACE_A = "binding02ReplaceA"
        const val BLOCK_REPLACE_B = "binding03ReplaceB"
        const val BLOCK_LOAD_B = "binding04LoadB"
        const val TRACE_COUNT_METRIC = "TimelineQueryToRenderCount"
        const val TRACE_SUM_METRIC = "TimelineQueryToRenderSumMs"
        const val FRAME_CPU_METRIC = "frameDurationCpuMs"
        const val FRAME_OVERRUN_METRIC = "frameOverrunMs"
        const val MEMORY_METRIC = "memoryRssAnonMaxKb"

        /** The real single benchmark-data filename copied back from the device. */
        const val REPORT_DATA_FILE = "com.paherdin.record.benchmark.test-benchmarkData.json"
    }
}

/**
 * Host entry point for the Task 6 `validateP6BenchmarkReports` Gradle task:
 * reads `-Dp6ReportDir=<copied additional output root>`, runs the validator,
 * prints PASS/FAIL plus the derived values and the exact control traversal
 * summary, and exits non-zero on any problem. The Gradle task wiring itself is
 * Task 6's job.
 */
fun main() {
    val reportDir = System.getProperty("p6ReportDir")
        ?: error("validateP6BenchmarkReports requires -Dp6ReportDir=<dir>")
    val result = TimelineBenchmarkReportValidator.validate(File(reportDir))
    // Derived values recomputed from the raw samples (recorded in
    // verification.md as the host-recomputed evidence, not transcribed).
    println("derived: queryP50Us=${result.queryP50Us} queryP95Us=${result.queryP95Us} " +
        "frameP95Us=${result.frameP95Us} jankBp=${result.jankBp} memoryMaxKib=${result.memoryMaxKib}")
    println("controlTraversal=${result.controlTraversal}")
    if (result.passed) {
        println("P6 benchmark report validation: PASS")
    } else {
        println("P6 benchmark report validation: FAIL")
        result.problems.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
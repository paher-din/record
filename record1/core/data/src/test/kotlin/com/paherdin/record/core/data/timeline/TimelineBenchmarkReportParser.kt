package com.paherdin.record.core.data.timeline

import java.io.File
import kotlin.math.roundToLong

/**
 * Tech PoC P6 Task 5 — host-only parser + validator for the copied
 * Macrobenchmark 1.4.1 raw reports (plan Task 5 checklist; spec §6). Lives in
 * the unit-test source set and deliberately has **no production JSON
 * dependency**: [MiniJsonParser] is a minimal recursive-descent parser, because
 * the unit-test classpath has no JSON library and `org.json` on the JVM is the
 * un-mocked Android stub.
 *
 * Validator contract (fixed to the real AGP 9.3 / macrobenchmark 1.4.1 output
 * shape after the trace-gate verify run; recorded in verification.md):
 *
 * - Recursively read only the supplied report root. Exactly one 1.4.1
 *   benchmark-data file named `*.benchmarkData.json` (the device writes
 *   `<instrumentationPackage>-benchmarkData.json`, e.g.
 *   `com.paherdin.record.benchmark.test-benchmarkData.json`), whose
 *   `benchmarks[]` array holds one entry per binding method, in name order, with
 *   `name` = the bare method name
 *   (`binding01LoadA|binding02ReplaceA|binding03ReplaceB|binding04LoadB`).
 * - Exact raw sample counts: loadA 15 / scrollA 3 / scrollB 2 / loadB 15
 *   iterations; every load iteration contributes exactly one
 *   `TimelineQueryToRenderSumMs` duration sample (trace uniqueness is proven by
 *   `TimelineQueryToRenderCount` == 1.0 on every iteration) and one
 *   `memoryRssAnonMaxKb` sample; every scroll iteration contributes a
 *   non-empty `frameDurationCpuMs` + `frameOverrunMs` sampled run of equal
 *   length (per-iteration frame arrays).
 * - Exactly 35 `*.perfetto-trace` files (one trace per measured iteration).
 * - Exactly one `p6-run-manifest.json` (Rev4 contract): suite-level
 *   `displaySettingsBefore`/`displaySettingsAfter` whose `peak`/`min` strings
 *   are exactly `120`/`null`, a suite-level `controlTraversal` carrying exactly
 *   the frozen selected-day traversal summary (the one unmeasured replacement
 *   traversal), and exactly 35 ordered iteration records split 15/3/2/15.
 *   Every record has `block`, `iteration`, positive finite
 *   `refreshBeforeHz`/`refreshAfterHz`, `thermalBefore`/`thermalAfter`
 *   (<= LIGHT), identical nonblank `pidBefore`/`pidAfter`, and the six nullable
 *   traversal fields. Load records require every traversal field JSON `null`;
 *   replacement records require exactly `1000/999/11/0/2025-09-26/2023-01-01`.
 *   The Rev3 `gestureCount` field is never parsed. A refresh change between two
 *   positive finite observations is recorded but never rejected by itself.
 * - Each benchmark entry's ordered `profilerOutputs` count equals its block
 *   iteration count; the 35 referenced filenames are distinct overall and every
 *   one resolves to a retained trace under the supplied root.
 * - The gates are recomputed from the raw samples with [TimelineMetricMath]
 *   (integer-exact Long math; ms values are scaled to µs): query p95 ≤ 60 ms
 *   (nearest-rank ranks 15/29 over the pooled 30 load durations),
 *   pooled frame-CPU p95 ≤ 16.67 ms, peak RssAnon ≤ 196,608 KiB. The pooled
 *   positive-overrun fraction is recomputed as `jankBp` and retained as a
 *   non-gating diagnostic (ADR 0014). Nothing is written; the result is a
 *   problem list, empty = PASS.
 *
 * The five manual warmups produce no Macrobenchmark samples, so the real
 * report's `warmupIterations` is 0 and the validator does NOT require it — the
 * warmup evidence lives in the run manifest and logcat, not the report.
 */
object TimelineBenchmarkReportParser {

    /** Frozen metric/report keys of the real Macrobenchmark 1.4.1 device output. */
    object CONTRACT {
        const val BENCHMARK_CLASS = "com.paherdin.record.benchmark.TimelineBenchmark"
        // TraceSectionMetric emits two per-iteration aggregates: `Count` (always
        // 1.0 for a uniquely-closed slice) and `SumMs` (the measured duration).
        const val TRACE_COUNT_METRIC = "TimelineQueryToRenderCount"
        const val TRACE_SUM_METRIC = "TimelineQueryToRenderSumMs"
        const val FRAME_CPU_METRIC = "frameDurationCpuMs"
        const val FRAME_OVERRUN_METRIC = "frameOverrunMs"
        // Macrobenchmark names memory metrics "memory" + SubMetric + Mode + "Kb".
        const val MEMORY_METRIC = "memoryRssAnonMaxKb"
        // The single device-published report data file ends with this suffix
        // (ResultWriter: `<instrumentationPackage>-benchmarkData.json`).
        const val BENCHMARK_DATA_SUFFIX = "benchmarkData.json"
        const val REPORT_DATA_FILE = "com.paherdin.record.benchmark.test-benchmarkData.json"
        const val MANIFEST_FILE = "p6-run-manifest.json"
        const val TRACE_FILE_SUFFIX = ".perfetto-trace"

        // Frozen budgets (spec §4.7); frame/query ms values are scaled to µs
        // for integer-exact Long math.
        const val QUERY_P95_BUDGET_US = 60_000L    // 60 ms
        const val FRAME_P95_BUDGET_US = 16_670L    // 16.67 ms
        const val MEMORY_BUDGET_KIB = 196_608L     // 192 MiB
        const val EXPECTED_TRACE_COUNT = 35        // 15 + 3 + 2 + 15
        const val EXPECTED_ITERATION_RECORDS = 35  // 15 + 3 + 2 + 15
        const val THERMAL_MAX_STATUS = 1           // LIGHT (NONE=0, LIGHT=1, MODERATE=2, ...)

        // Exact native user display settings the run manifest must report
        // (frozen Revision 2 contract; the harness reads but never writes them).
        const val EXPECTED_PEAK_REFRESH = "120"
        const val EXPECTED_MIN_REFRESH = "null"

        // Frozen Rev4 selected-day replacement traversal contract (spec §4.5 /
        // plan Task 6R4-B Step 3): the one unmeasured control traversal and
        // every valid measured replacement iteration report exactly these values.
        const val FROZEN_TRAVERSAL_DRAW_COUNT = 1000
        const val FROZEN_TRAVERSAL_REPLACEMENT_COUNT = 999
        const val FROZEN_TRAVERSAL_MAX_PUBLISHED_ITEM_COUNT = 11
        const val FROZEN_TRAVERSAL_PREFETCH_QUERY_COUNT = 0
        const val FROZEN_TRAVERSAL_FIRST_DATE = "2025-09-26"
        const val FROZEN_TRAVERSAL_LAST_DATE = "2023-01-01"

        /** The two prepared replacement blocks. */
        val REPLACEMENT_BLOCKS: Set<String> = setOf("binding02ReplaceA", "binding03ReplaceB")

        data class BlockSpec(
            val methodName: String,
            val isReplacement: Boolean,
            val iterations: Int,
        )

        val BLOCKS = listOf(
            BlockSpec("binding01LoadA", isReplacement = false, iterations = 15),
            BlockSpec("binding02ReplaceA", isReplacement = true, iterations = 3),
            BlockSpec("binding03ReplaceB", isReplacement = true, iterations = 2),
            BlockSpec("binding04LoadB", isReplacement = false, iterations = 15),
        )

        /** Bare method name — the real benchmark `name` field has no class prefix. */
        fun expectedName(block: BlockSpec): String = block.methodName
    }

    /**
     * Typed slice of `BenchmarkData.TestResult` the validator needs. A missing
     * metric is an empty list (never an exception) so the validator can report
     * the absence precisely.
     */
    data class ParsedBenchmarkReport(
        val name: String,
        val className: String,
        val repeatIterations: Int?,
        val warmupIterations: Int?,
        /** `metrics` — one aggregate value per iteration (name -> runs). */
        val singleMetrics: Map<String, List<Double>>,
        /** `sampledMetrics` — per-iteration sample lists (name -> runs). */
        val sampledMetrics: Map<String, List<List<Double>>>,
        /** `profilerOutputs` — retained profiler file names (diagnostic). */
        val profilerOutputs: List<String>,
    )

    /** Suite-level native display settings snapshot from the run manifest. */
    data class ParsedDisplaySettings(val peak: String, val min: String)

    /** One measured iteration's control record from the run manifest. */
    data class ParsedIterationControl(
        val block: String,
        val iteration: Int,
        val refreshBeforeHz: Double,
        val refreshAfterHz: Double,
        val thermalBefore: Int,
        val thermalAfter: Int,
        val pidBefore: String,
        val pidAfter: String,
        val selectedDayDrawCount: Int?,
        val replacementCount: Int?,
        val maxPublishedItemCount: Int?,
        val prefetchQueryCount: Int?,
        val firstDate: String?,
        val lastDate: String?,
        /**
         * True when the raw iteration record carried a stale Rev3 `gestureCount`
         * field. The parser never reads the value — its presence alone is a
         * schema error the validator rejects.
         */
        val staleGestureCountPresent: Boolean = false,
    )

    /** The frozen pure selected-day replacement traversal summary (host mirror). */
    data class ParsedTraversalSummary(
        val selectedDayDrawCount: Int,
        val replacementCount: Int,
        val maxPublishedItemCount: Int,
        val prefetchQueryCount: Int,
        val firstDate: String,
        val lastDate: String,
    )

    /** The full Rev4 run manifest: native settings + control traversal + iteration records. */
    data class ParsedRunManifest(
        val displaySettingsBefore: ParsedDisplaySettings,
        val displaySettingsAfter: ParsedDisplaySettings,
        val controlTraversal: ParsedTraversalSummary?,
        val iterations: List<ParsedIterationControl>,
    )

    /**
     * Parses one copied `p6-run-manifest.json`; throws only on malformed JSON
     * or missing fields. Numeric fields accept `NaN`/`Infinity` tokens (org.json
     * emits them for non-finite doubles); finiteness is the validator's job.
     */
    fun parseRunManifest(file: File): ParsedRunManifest {
        val root = MiniJsonParser.parse(file.readText()) as? JsonValue.Obj
            ?: error("run manifest $file is not a JSON object")
        fun settings(key: String): ParsedDisplaySettings {
            val o = root.entries[key] as? JsonValue.Obj
                ?: error("run manifest $file missing object '$key'")
            val peak = (o.entries["peak"] as? JsonValue.Str)?.value
                ?: error("run manifest $file $key.peak missing or non-string")
            val min = (o.entries["min"] as? JsonValue.Str)?.value
                ?: error("run manifest $file $key.min missing or non-string")
            return ParsedDisplaySettings(peak, min)
        }
        val iterationsJson = root.entries["iterations"] as? JsonValue.Arr
            ?: error("run manifest $file missing 'iterations' array")
        val controlTraversal = (root.entries["controlTraversal"] as? JsonValue.Obj)?.let { o ->
            ParsedTraversalSummary(
                selectedDayDrawCount = (o.entries["selectedDayDrawCount"] as? JsonValue.Num)?.raw
                    ?.toIntOrNull()
                    ?: error("run manifest $file controlTraversal.selectedDayDrawCount missing or non-integer"),
                replacementCount = (o.entries["replacementCount"] as? JsonValue.Num)?.raw
                    ?.toIntOrNull()
                    ?: error("run manifest $file controlTraversal.replacementCount missing or non-integer"),
                maxPublishedItemCount = (o.entries["maxPublishedItemCount"] as? JsonValue.Num)?.raw
                    ?.toIntOrNull()
                    ?: error("run manifest $file controlTraversal.maxPublishedItemCount missing or non-integer"),
                prefetchQueryCount = (o.entries["prefetchQueryCount"] as? JsonValue.Num)?.raw
                    ?.toIntOrNull()
                    ?: error("run manifest $file controlTraversal.prefetchQueryCount missing or non-integer"),
                firstDate = (o.entries["firstDate"] as? JsonValue.Str)?.value
                    ?: error("run manifest $file controlTraversal.firstDate missing or non-string"),
                lastDate = (o.entries["lastDate"] as? JsonValue.Str)?.value
                    ?: error("run manifest $file controlTraversal.lastDate missing or non-string"),
            )
        }
        val iterations = iterationsJson.items.map { item ->
            val o = item as? JsonValue.Obj
                ?: error("run manifest $file iteration record is not an object")
            fun str(field: String): String = (o.entries[field] as? JsonValue.Str)?.value
                ?: error("run manifest $file iteration missing string field '$field'")
            fun num(field: String): Double = (o.entries[field] as? JsonValue.Num)?.raw
                ?.toDoubleOrNull()
                ?: error("run manifest $file iteration missing or non-numeric field '$field'")
            fun int(field: String): Int = (o.entries[field] as? JsonValue.Num)?.raw
                ?.toIntOrNull()
                ?: error("run manifest $file iteration missing or non-integer field '$field'")
            ParsedIterationControl(
                block = str("block"),
                iteration = int("iteration"),
                refreshBeforeHz = num("refreshBeforeHz"),
                refreshAfterHz = num("refreshAfterHz"),
                thermalBefore = int("thermalBefore"),
                thermalAfter = int("thermalAfter"),
                pidBefore = str("pidBefore"),
                pidAfter = str("pidAfter"),
                selectedDayDrawCount = (o.entries["selectedDayDrawCount"] as? JsonValue.Num)?.raw?.toIntOrNull(),
                replacementCount = (o.entries["replacementCount"] as? JsonValue.Num)?.raw?.toIntOrNull(),
                maxPublishedItemCount = (o.entries["maxPublishedItemCount"] as? JsonValue.Num)?.raw?.toIntOrNull(),
                prefetchQueryCount = (o.entries["prefetchQueryCount"] as? JsonValue.Num)?.raw?.toIntOrNull(),
                firstDate = (o.entries["firstDate"] as? JsonValue.Str)?.value,
                lastDate = (o.entries["lastDate"] as? JsonValue.Str)?.value,
                staleGestureCountPresent = o.entries["gestureCount"] != null,
            )
        }
        return ParsedRunManifest(
            displaySettingsBefore = settings("displaySettingsBefore"),
            displaySettingsAfter = settings("displaySettingsAfter"),
            controlTraversal = controlTraversal,
            iterations = iterations,
        )
    }

    /**
     * Parses the one copied `<package>-benchmarkData.json` into its complete
     * `benchmarks[]` array (one entry per binding method, in name order); throws
     * only on malformed JSON. The device's ResultWriter — one static
     * BenchmarkData document per instrumentation run plus
     * `appendTestResult` — produces one aggregate file, not four per-method
     * files, so this returns every benchmark entry.
     */
    fun parseBenchmarkDataFile(file: File): List<ParsedBenchmarkReport> {
        val root = MiniJsonParser.parse(file.readText())
        val benchmarks = (root as? JsonValue.Obj)?.entries?.get("benchmarks") as? JsonValue.Arr
            ?: error("report $file has no benchmarks array")
        return benchmarks.items.map { item ->
            parseBenchmarkEntry(item as? JsonValue.Obj ?: error("report $file benchmark is not an object"), file)
        }
    }

    /** Parses one `BenchmarkData.TestResult` entry from the aggregate file. */
    private fun parseBenchmarkEntry(test: JsonValue.Obj, file: File): ParsedBenchmarkReport {
        fun str(key: String): String =
            (test.entries[key] as? JsonValue.Str)?.value
                ?: error("report $file TestResult missing string field '$key'")
        fun optInt(key: String): Int? =
            (test.entries[key] as? JsonValue.Num)?.raw?.toIntOrNull()
        val metrics = (test.entries["metrics"] as? JsonValue.Obj)?.entries.orEmpty()
        val sampledMetrics = (test.entries["sampledMetrics"] as? JsonValue.Obj)?.entries.orEmpty()
        fun singleRuns(name: String): List<Double> =
            runListOrNull(metrics[name], file, "metrics.$name").orEmpty()
        fun sampledRuns(name: String): List<List<Double>> {
            val runs = (sampledMetrics[name] as? JsonValue.Obj)?.entries?.get("runs") as? JsonValue.Arr
                ?: return emptyList()
            return runs.items.map { run ->
                (run as? JsonValue.Arr)?.items?.map { (it as? JsonValue.Num)?.raw?.toDoubleOrNull()
                    ?: error("report $file sampledMetrics.$name run contains a non-number")
                } ?: error("report $file sampledMetrics.$name run is not an array")
            }
        }
        val profilerOutputs = ((test.entries["profilerOutputs"] as? JsonValue.Arr)?.items.orEmpty())
            .mapNotNull { (it as? JsonValue.Obj)?.entries?.get("filename") as? JsonValue.Str }
            .map { it.value }
        return ParsedBenchmarkReport(
            name = str("name"),
            className = str("className"),
            repeatIterations = optInt("repeatIterations"),
            warmupIterations = optInt("warmupIterations"),
            singleMetrics = mapOf(
                CONTRACT.TRACE_COUNT_METRIC to singleRuns(CONTRACT.TRACE_COUNT_METRIC),
                CONTRACT.TRACE_SUM_METRIC to singleRuns(CONTRACT.TRACE_SUM_METRIC),
                CONTRACT.MEMORY_METRIC to singleRuns(CONTRACT.MEMORY_METRIC),
            ),
            sampledMetrics = mapOf(
                CONTRACT.FRAME_CPU_METRIC to sampledRuns(CONTRACT.FRAME_CPU_METRIC),
                CONTRACT.FRAME_OVERRUN_METRIC to sampledRuns(CONTRACT.FRAME_OVERRUN_METRIC),
            ),
            profilerOutputs = profilerOutputs,
        )
    }

    private fun runListOrNull(value: JsonValue?, file: File, label: String): List<Double>? {
        val runs = (value as? JsonValue.Obj)?.entries?.get("runs") as? JsonValue.Arr ?: return null
        return runs.items.map { (it as? JsonValue.Num)?.raw?.toDoubleOrNull()
            ?: error("report $file $label run contains a non-number")
        }
    }
}

/** Validation outcome: empty [problems] = PASS; recomputed gates exposed for tests. */
data class TimelineValidationResult(
    val problems: List<String>,
    val queryP50Us: Long?,
    val queryP95Us: Long?,
    val frameP95Us: Long?,
    val jankBp: Long?,
    val memoryMaxKib: Long?,
    /** The parsed suite-level unmeasured replacement traversal control. */
    val controlTraversal: TimelineBenchmarkReportParser.ParsedTraversalSummary?,
) {
    val passed: Boolean get() = problems.isEmpty()
}

/**
 * Recomputes the four frozen gates from the raw reports under [reportRoot] and
 * requires the exact named blocks/sample counts. Pure: reads only the passed
 * root, writes nothing, returns a problem list.
 */
object TimelineBenchmarkReportValidator {

    fun validate(reportRoot: File): TimelineValidationResult {
        require(reportRoot.isDirectory) { "report root is not a directory: $reportRoot" }
        val problems = mutableListOf<String>()
        val contract = TimelineBenchmarkReportParser.CONTRACT

        // ---- exactly one 1.4.1 benchmark-data file, holding all four blocks ----
        val reportFiles = reportRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(contract.BENCHMARK_DATA_SUFFIX) }.toList()
        if (reportFiles.size != 1) {
            problems += "expected exactly one '${contract.BENCHMARK_DATA_SUFFIX}' " +
                "benchmark-data file, found ${reportFiles.size}"
        }
        val parsedByName = LinkedHashMap<String, TimelineBenchmarkReportParser.ParsedBenchmarkReport>()
        for (file in reportFiles) {
            val reports = try {
                TimelineBenchmarkReportParser.parseBenchmarkDataFile(file)
            } catch (e: Exception) {
                problems += "unparseable benchmark-data $file: ${e.message}"
                continue
            }
            for (report in reports) {
                if (report.className != contract.BENCHMARK_CLASS) {
                    problems += "report $file benchmark '${report.name}' has unexpected " +
                        "className '${report.className}'"
                }
                parsedByName[report.name] = report
            }
        }
        for (block in contract.BLOCKS) {
            val report = parsedByName[contract.expectedName(block)]
            if (report == null) {
                problems += "missing report for block '${block.methodName}' " +
                    "(expected name '${contract.expectedName(block)}')"
                continue
            }
            checkBlock(block, report, problems, contract)
        }
        val extraNames = parsedByName.keys - contract.BLOCKS.map { it.methodName }.toSet()
        for (extra in extraNames) {
            problems += "benchmark-data has a benchmark entry for unknown block '$extra'"
        }

        // ---- exactly 35 Perfetto trace files ----
        val traceFiles = reportRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(contract.TRACE_FILE_SUFFIX) }.toList()
        if (traceFiles.size != contract.EXPECTED_TRACE_COUNT) {
            problems += "expected exactly ${contract.EXPECTED_TRACE_COUNT} " +
                "'${contract.TRACE_FILE_SUFFIX}' traces, found ${traceFiles.size}"
        }

        // ---- run manifest: exact Revision 2 contract (native settings + 35
        // per-iteration control records). A refresh change between two positive
        // finite observations is recorded but never rejected by itself. ----
        val manifestFiles = reportRoot.walkTopDown()
            .filter { it.isFile && it.name == contract.MANIFEST_FILE }.toList()
        var controlTraversal: TimelineBenchmarkReportParser.ParsedTraversalSummary? = null
        if (manifestFiles.size != 1) {
            problems += "expected exactly one '${contract.MANIFEST_FILE}', found ${manifestFiles.size}"
        } else {
            try {
                val manifest = TimelineBenchmarkReportParser.parseRunManifest(manifestFiles[0])
                requireDisplaySettings(manifest.displaySettingsBefore, "before", problems)
                requireDisplaySettings(manifest.displaySettingsAfter, "after", problems)

                // Suite-level control traversal: the one unmeasured replacement
                // traversal must be present and match the exact frozen values.
                val control = manifest.controlTraversal
                if (control == null) {
                    problems += "run manifest has no suite-level controlTraversal " +
                        "(the one unmeasured replacement traversal)"
                } else {
                    checkTraversalExact(control, "controlTraversal", problems, contract)
                }

                val controls = manifest.iterations
                if (controls.size != contract.EXPECTED_ITERATION_RECORDS) {
                    problems += "run manifest has ${controls.size} iteration records, " +
                        "expected ${contract.EXPECTED_ITERATION_RECORDS}"
                }
                val expected = LinkedHashMap<String, MutableSet<Int>>()
                for (block in contract.BLOCKS) {
                    expected[block.methodName] = (0 until block.iterations).toMutableSet()
                }
                val actual = LinkedHashMap<String, MutableSet<Int>>()
                for (c in controls) {
                    if (c.block !in expected) {
                        problems += "run manifest has an iteration record for unknown block '${c.block}'"
                        continue
                    }
                    if (!actual.getOrPut(c.block) { mutableSetOf() }.add(c.iteration)) {
                        problems += "duplicate iteration record ${c.block}#${c.iteration}"
                    }
                    if (!c.refreshBeforeHz.isFinite() || c.refreshBeforeHz <= 0.0) {
                        problems += "iteration ${c.block}#${c.iteration} refreshBeforeHz " +
                            "${c.refreshBeforeHz} must be positive and finite"
                    }
                    if (!c.refreshAfterHz.isFinite() || c.refreshAfterHz <= 0.0) {
                        problems += "iteration ${c.block}#${c.iteration} refreshAfterHz " +
                            "${c.refreshAfterHz} must be positive and finite"
                    }
                    if (c.thermalBefore > contract.THERMAL_MAX_STATUS ||
                        c.thermalAfter > contract.THERMAL_MAX_STATUS
                    ) {
                        problems += "iteration ${c.block}#${c.iteration} thermal " +
                            "${c.thermalBefore}/${c.thermalAfter} exceeds LIGHT(" +
                            "${contract.THERMAL_MAX_STATUS})"
                    }
                    if (c.pidBefore.isBlank() || c.pidAfter.isBlank()) {
                        problems += "iteration ${c.block}#${c.iteration} has a blank PID"
                    } else if (c.pidBefore != c.pidAfter) {
                        problems += "iteration ${c.block}#${c.iteration} PID changed " +
                            "'${c.pidBefore}' -> '${c.pidAfter}'"
                    }
                    // The Rev3 `gestureCount` field is stale schema in Rev4 and
                    // must be rejected, never silently ignored.
                    if (c.staleGestureCountPresent) {
                        problems += "iteration ${c.block}#${c.iteration} carries a stale Rev3 " +
                            "gestureCount field; gestureCount is removed from the Rev4 contract"
                    }
                    // Rev4 traversal fields: load records require every field
                    // null; replacement records require the exact frozen summary.
                    val summary = traversalSummaryOrNull(c)
                    if (c.block in contract.REPLACEMENT_BLOCKS) {
                        if (summary == null) {
                            problems += "replacement iteration ${c.block}#${c.iteration} " +
                                "must report the exact frozen traversal summary " +
                                "(1000/999/11/0/2025-09-26/2023-01-01), found null"
                        } else {
                            checkTraversalExact(
                                summary,
                                "iteration ${c.block}#${c.iteration}",
                                problems,
                                contract,
                            )
                        }
                    } else if (!traversalAllNull(c)) {
                        problems += "load iteration ${c.block}#${c.iteration} must have all " +
                            "traversal fields null, found $summary"
                    }
                }
                for ((block, indices) in expected) {
                    val seen = actual[block].orEmpty()
                    val missing = indices - seen
                    if (missing.isNotEmpty()) {
                        problems += "run manifest missing $block iteration record(s) $missing"
                    }
                    val extra = seen - indices
                    if (extra.isNotEmpty()) {
                        problems += "run manifest has out-of-range iteration record(s) " +
                            "for $block: $extra"
                    }
                }
                controlTraversal = control
            } catch (e: Exception) {
                problems += "unparseable run manifest ${manifestFiles[0]}: ${e.message}"
            }
        }

        // ---- profiler outputs: per-block count == iteration count, 35 distinct
        // referenced filenames overall, every one resolving to a retained trace. ----
        val referencedFilenames = mutableListOf<String>()
        for (block in contract.BLOCKS) {
            val report = parsedByName[contract.expectedName(block)] ?: continue
            if (report.profilerOutputs.size != block.iterations) {
                problems += "${block.methodName} has ${report.profilerOutputs.size} " +
                    "profilerOutputs, expected ${block.iterations}"
            }
            referencedFilenames += report.profilerOutputs
        }
        val distinctFilenames = referencedFilenames.distinct()
        if (distinctFilenames.size != contract.EXPECTED_TRACE_COUNT) {
            problems += "profilerOutputs reference ${distinctFilenames.size} distinct " +
                "filenames, expected ${contract.EXPECTED_TRACE_COUNT}"
        }
        for (name in distinctFilenames) {
            val resolved = reportRoot.walkTopDown().any { it.isFile && it.name == name }
            if (!resolved) {
                problems += "profilerOutput filename '$name' does not resolve to a " +
                    "retained trace under $reportRoot"
            }
        }

        // ---- recompute the four gates from raw samples (independent; never reads
        // a pre-computed p95/p50 from the report) ----
        val allQueryUs = ArrayList<Long>()
        val allFrameUs = ArrayList<Long>()
        val allOverrunMs = ArrayList<Double>()
        val allMemoryKib = ArrayList<Long>()
        for (block in contract.BLOCKS) {
            val report = parsedByName[contract.expectedName(block)] ?: continue
            if (!block.isReplacement) {
                allQueryUs += report.singleMetrics[contract.TRACE_SUM_METRIC].orEmpty()
                    .map { s -> (s * 1_000.0).roundToLong() }
            } else {
                for (run in report.sampledMetrics[contract.FRAME_CPU_METRIC].orEmpty()) {
                    allFrameUs += run.map { s -> (s * 1_000.0).roundToLong() }
                }
                // Negative overruns are normal (frame finished early); every
                // frame stays in the denominator. Jank = positive-overrun fraction.
                for (run in report.sampledMetrics[contract.FRAME_OVERRUN_METRIC].orEmpty()) {
                    allOverrunMs += run
                }
            }
            allMemoryKib += report.singleMetrics[contract.MEMORY_METRIC].orEmpty()
                .map { s -> s.roundToLong() }
        }
        val sortedQueryUs = allQueryUs.sorted()
        val queryP50 = if (sortedQueryUs.size == 30) {
            TimelineMetricMath.nearestRankPercentile(sortedQueryUs, 50)
        } else null
        val queryP95 = if (sortedQueryUs.size == 30) {
            TimelineMetricMath.nearestRankPercentile(sortedQueryUs, 95)
        } else null
        val frameP95 = if (allFrameUs.isNotEmpty()) {
            TimelineMetricMath.nearestRankPercentile(allFrameUs.sorted(), 95)
        } else null
        val jankBp = if (allOverrunMs.isNotEmpty()) {
            TimelineMetricMath.jankBasisPoints(
                allOverrunMs.count { it > 0.0 }.toLong(),
                allOverrunMs.size.toLong(),
            )
        } else null
        val memoryMax = if (allMemoryKib.isNotEmpty()) {
            TimelineMetricMath.maxRssAnonKb(allMemoryKib)
        } else null

        if (queryP95 != null && queryP95 > contract.QUERY_P95_BUDGET_US) {
            problems += "query-to-first-correct-draw p95 ${queryP95}us exceeds budget " +
                "${contract.QUERY_P95_BUDGET_US}us"
        }
        if (frameP95 != null && frameP95 > contract.FRAME_P95_BUDGET_US) {
            problems += "pooled frame-CPU p95 ${frameP95}us exceeds budget " +
                "${contract.FRAME_P95_BUDGET_US}us"
        }
        if (memoryMax != null && memoryMax > contract.MEMORY_BUDGET_KIB) {
            problems += "peak RssAnon ${memoryMax}KiB exceeds budget ${contract.MEMORY_BUDGET_KIB}KiB"
        }

        return TimelineValidationResult(
            problems,
            queryP50,
            queryP95,
            frameP95,
            jankBp,
            memoryMax,
            controlTraversal = controlTraversal,
        )
    }

    /** True when every traversal field of a parsed iteration record is null. */
    private fun traversalAllNull(
        c: TimelineBenchmarkReportParser.ParsedIterationControl,
    ): Boolean =
        c.selectedDayDrawCount == null && c.replacementCount == null &&
            c.maxPublishedItemCount == null && c.prefetchQueryCount == null &&
            c.firstDate == null && c.lastDate == null

    /** Lifts a parsed iteration's traversal fields; null when every field is null. */
    private fun traversalSummaryOrNull(
        c: TimelineBenchmarkReportParser.ParsedIterationControl,
    ): TimelineBenchmarkReportParser.ParsedTraversalSummary? {
        if (c.selectedDayDrawCount == null && c.replacementCount == null &&
            c.maxPublishedItemCount == null && c.prefetchQueryCount == null &&
            c.firstDate == null && c.lastDate == null
        ) {
            return null
        }
        return TimelineBenchmarkReportParser.ParsedTraversalSummary(
            selectedDayDrawCount = c.selectedDayDrawCount ?: return null,
            replacementCount = c.replacementCount ?: return null,
            maxPublishedItemCount = c.maxPublishedItemCount ?: return null,
            prefetchQueryCount = c.prefetchQueryCount ?: return null,
            firstDate = c.firstDate ?: return null,
            lastDate = c.lastDate ?: return null,
        )
    }

    /** Requires every frozen selected-day traversal field to match exactly. */
    private fun checkTraversalExact(
        s: TimelineBenchmarkReportParser.ParsedTraversalSummary,
        label: String,
        problems: MutableList<String>,
        contract: TimelineBenchmarkReportParser.CONTRACT,
    ) {
        if (s.selectedDayDrawCount != contract.FROZEN_TRAVERSAL_DRAW_COUNT) {
            problems += "$label selectedDayDrawCount ${s.selectedDayDrawCount} != " +
                "frozen ${contract.FROZEN_TRAVERSAL_DRAW_COUNT}"
        }
        if (s.replacementCount != contract.FROZEN_TRAVERSAL_REPLACEMENT_COUNT) {
            problems += "$label replacementCount ${s.replacementCount} != " +
                "frozen ${contract.FROZEN_TRAVERSAL_REPLACEMENT_COUNT}"
        }
        if (s.maxPublishedItemCount != contract.FROZEN_TRAVERSAL_MAX_PUBLISHED_ITEM_COUNT) {
            problems += "$label maxPublishedItemCount ${s.maxPublishedItemCount} != " +
                "frozen ${contract.FROZEN_TRAVERSAL_MAX_PUBLISHED_ITEM_COUNT}"
        }
        if (s.prefetchQueryCount != contract.FROZEN_TRAVERSAL_PREFETCH_QUERY_COUNT) {
            problems += "$label prefetchQueryCount ${s.prefetchQueryCount} != " +
                "frozen ${contract.FROZEN_TRAVERSAL_PREFETCH_QUERY_COUNT}"
        }
        if (s.firstDate != contract.FROZEN_TRAVERSAL_FIRST_DATE) {
            problems += "$label firstDate '${s.firstDate}' != frozen " +
                "'${contract.FROZEN_TRAVERSAL_FIRST_DATE}'"
        }
        if (s.lastDate != contract.FROZEN_TRAVERSAL_LAST_DATE) {
            problems += "$label lastDate '${s.lastDate}' != frozen " +
                "'${contract.FROZEN_TRAVERSAL_LAST_DATE}'"
        }
    }

    private fun requireDisplaySettings(
        settings: TimelineBenchmarkReportParser.ParsedDisplaySettings,
        label: String,
        problems: MutableList<String>,
    ) {
        val contract = TimelineBenchmarkReportParser.CONTRACT
        if (settings.peak != contract.EXPECTED_PEAK_REFRESH) {
            problems += "display settings $label peak '${settings.peak}' != expected " +
                "'${contract.EXPECTED_PEAK_REFRESH}'"
        }
        if (settings.min != contract.EXPECTED_MIN_REFRESH) {
            problems += "display settings $label min '${settings.min}' != expected " +
                "'${contract.EXPECTED_MIN_REFRESH}'"
        }
    }

    private fun checkBlock(
        block: TimelineBenchmarkReportParser.CONTRACT.BlockSpec,
        report: TimelineBenchmarkReportParser.ParsedBenchmarkReport,
        problems: MutableList<String>,
        contract: TimelineBenchmarkReportParser.CONTRACT,
    ) {
        val label = block.methodName
        if (report.repeatIterations != null && report.repeatIterations != block.iterations) {
            problems += "$label repeatIterations ${report.repeatIterations} != expected ${block.iterations}"
        }
        if (block.isReplacement) {
            val durations = report.sampledMetrics[contract.FRAME_CPU_METRIC].orEmpty()
            val overruns = report.sampledMetrics[contract.FRAME_OVERRUN_METRIC].orEmpty()
            if (durations.isEmpty()) {
                problems += "$label missing frameDurationCpuMs sampled runs"
            } else if (durations.size != block.iterations) {
                problems += "$label has ${durations.size} frame iterations, expected ${block.iterations}"
            }
            if (overruns.isEmpty()) {
                problems += "$label missing frameOverrunMs sampled runs"
            } else if (overruns.size != block.iterations) {
                problems += "$label has ${overruns.size} overrun iterations, expected ${block.iterations}"
            }
            for (i in 0 until minOf(durations.size, overruns.size)) {
                if (durations[i].isEmpty()) {
                    problems += "$label frame iteration $i has an empty frameDurationCpuMs run"
                }
                if (durations[i].size != overruns[i].size) {
                    problems += "$label iteration $i overrun run size ${overruns[i].size} " +
                        "!= frameDurationCpuMs run size ${durations[i].size}"
                }
            }
        } else {
            // Real 1.4.1 TraceSectionMetric emits Count (== 1.0 per closed slice)
            // and SumMs (the measured duration) as two single metrics.
            val count = report.singleMetrics[contract.TRACE_COUNT_METRIC].orEmpty()
            if (count.isEmpty()) {
                problems += "$label missing TimelineQueryToRenderCount samples"
            } else if (count.size != block.iterations) {
                problems += "$label has ${count.size} TimelineQueryToRenderCount samples, " +
                    "expected exactly ${block.iterations}"
            }
            if (count.any { it != 1.0 }) {
                problems += "$label has a TraceSectionMetric count != 1.0 " +
                    "(per-iteration slice uniqueness violated): $count"
            }
            val query = report.singleMetrics[contract.TRACE_SUM_METRIC].orEmpty()
            if (query.isEmpty()) {
                problems += "$label missing TimelineQueryToRenderSumMs samples"
            } else if (query.size != block.iterations) {
                problems += "$label has ${query.size} query samples, expected exactly " +
                    "${block.iterations} (one per iteration; a duplicate trace or a dropped " +
                    "iteration is invalid)"
            }
            if (query.any { it <= 0.0 }) {
                problems += "$label has non-positive query samples (trace did not run): $query"
            }
        }
        val memory = report.singleMetrics[contract.MEMORY_METRIC].orEmpty()
        if (memory.isEmpty()) {
            problems += "$label missing memoryRssAnonMaxKb samples"
        } else if (memory.size != block.iterations) {
            problems += "$label has ${memory.size} memory samples, expected ${block.iterations}"
        }
    }
}

/**
 * Minimal strict recursive-descent JSON parser (RFC 8259 grammar subset used by
 * the benchmark reports: objects, arrays, strings with escapes, numbers,
 * true/false/null). No production JSON dependency; throws on malformed input
 * or trailing garbage.
 */
internal object MiniJsonParser {

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        check(parser.atEnd()) { "trailing content after JSON value at offset ${parser.pos}" }
        return value
    }

    private class Parser(private val text: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            check(pos < text.length) { "unexpected end of JSON" }
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> { expect("true"); JsonValue.Bool(true) }
                'f' -> { expect("false"); JsonValue.Bool(false) }
                'n' -> { expect("null"); JsonValue.Null }
                // org.json emits bare NaN/Infinity for non-finite doubles; parse
                // them as numbers so the validator can reject them with a precise
                // "non-finite" problem instead of an unparseable-manifest one.
                'N' -> { expect("NaN"); JsonValue.Num("NaN") }
                'I' -> { expect("Infinity"); JsonValue.Num("Infinity") }
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("unexpected character '${text[pos]}' at offset $pos")
            }
        }

        private fun parseObject(): JsonValue.Obj {
            expect("{")
            skipWhitespace()
            val entries = LinkedHashMap<String, JsonValue>()
            if (peek() == '}') { pos++; return JsonValue.Obj(entries) }
            while (true) {
                skipWhitespace()
                check(pos < text.length && text[pos] == '"') { "expected string key at offset $pos" }
                val key = parseString()
                skipWhitespace()
                expect(":")
                entries[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return JsonValue.Obj(entries) }
                    else -> throw IllegalArgumentException("expected ',' or '}' at offset $pos")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            expect("[")
            skipWhitespace()
            val items = ArrayList<JsonValue>()
            if (peek() == ']') { pos++; return JsonValue.Arr(items) }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return JsonValue.Arr(items) }
                    else -> throw IllegalArgumentException("expected ',' or ']' at offset $pos")
                }
            }
        }

        private fun parseString(): String {
            expect("\"")
            val sb = StringBuilder()
            while (true) {
                check(pos < text.length) { "unterminated string" }
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        check(pos < text.length) { "unterminated escape" }
                        when (val e = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                check(pos + 4 <= text.length) { "truncated \\u escape" }
                                sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("invalid escape '\\$e' at offset ${pos - 1}")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            if (text.startsWith("-Infinity", pos)) {
                pos += "-Infinity".length
                return JsonValue.Num("-Infinity")
            }
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            if (pos < text.length && text[pos] == '.') {
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            check(raw != "-") { "malformed number at offset $start" }
            return JsonValue.Num(raw)
        }

        private fun peek(): Char =
            text.getOrElse(pos) { throw IllegalArgumentException("unexpected end of JSON") }

        private fun expect(literal: String) {
            check(text.startsWith(literal, pos)) { "expected '$literal' at offset $pos" }
            pos += literal.length
        }
    }
}

/** Minimal JSON AST; [JsonValue.Num] keeps the raw text so Long math stays exact. */
internal sealed interface JsonValue {
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val raw: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

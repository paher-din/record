package com.paherdin.record.core.data.startup

import com.paherdin.record.core.data.timeline.JsonValue
import com.paherdin.record.core.data.timeline.MiniJsonParser
import java.io.File
import kotlin.system.exitProcess

/**
 * M1-T09 Step 6 — host-only parser + validator for the copied Macrobenchmark
 * 1.4.1 raw startup reports (plan Task M1-T09 Step 6; spec 11.3). Lives in the
 * unit-test source set with no production JSON dependency: [MiniJsonParser]
 * (the P6 recursive-descent parser) is the only reader, because the unit-test
 * classpath has no JSON library and `org.json` on the JVM is the un-mocked
 * Android stub.
 *
 * Validator contract:
 *
 * - Exactly one 1.4.1 benchmark-data file named
 *   `<instrumentationPackage>-benchmarkData.json` whose `benchmarks[]` holds
 *   one entry per binding method with `name` = the bare method name and
 *   `className` = [M1StartupReportParser.CONTRACT.BENCHMARK_CLASS].
 * - Exactly the eight frozen startup blocks, each with exactly
 *   [M1StartupReportParser.CONTRACT.SAMPLES_PER_BLOCK] positive, finite
 *   samples of `timeToInitialDisplayMs`. `timeToFullDisplayMs` must be present
 *   with the same exact sample count on every `ttfdRequired` block (the
 *   ready/recovery sentinel: MainActivity fires `reportFullyDrawn` only in the
 *   Ready branch) and must be absent on every pending-migration block (the
 *   no-shell sentinel: a blocked gate never renders a shell).
 * - Nearest-rank p95 is rank 29 of 30. TTID p95 <= 1,000 ms on every block;
 *   TTFD p95 <= 1,500 ms on the healthy blocks only. The pending-activation
 *   block's TTFD p95 is retained as a non-gating diagnostic (it proves the
 *   shell rendered) and is never budgeted.
 * - Exactly 240 `*.perfetto-trace` files; each block's ordered
 *   `profilerOutputs` has exactly 30 distinct filenames that all resolve to
 *   retained traces under the report root, so one sample exists per measured
 *   iteration and no trace is reused.
 * - Exactly one `m1-run-manifest.json`: the suite/fixture version, the exact
 *   environment snapshot (no blank field), the exact harness identity (target
 *   and test package, target and setup components, the benchmark-variant
 *   metadata marker, the 64-hex signing-cert digest, the app data dir), the
 *   exact eight-block table, a nonempty command log whose every entry is
 *   allowed and targets only the benchmark variant packages (any occurrence of
 *   the production `com.paherdin.record` token outside the frozen benchmark
 *   target/test/activity tokens is a problem), and exactly 240 ordered
 *   iteration records with no duplicate, missing, or out-of-range iteration;
 *   every record carries a nonblank pid, thermal status within LIGHT, no
 *   unexpected exit info, and `fixtureValidated = true` with the exact fixture
 *   name and fixture digest of its block.
 *
 * Nothing is written; the result is a problem list, empty = PASS.
 */
object M1StartupReportParser {

    /** Frozen metric/report keys of the real Macrobenchmark 1.4.1 device output. */
    object CONTRACT {
        const val BENCHMARK_CLASS = "com.paherdin.record.benchmark.M1StartupBenchmark"
        const val TTID_METRIC = "timeToInitialDisplayMs"
        const val TTFD_METRIC = "timeToFullDisplayMs"
        const val BENCHMARK_DATA_SUFFIX = "benchmarkData.json"
        const val REPORT_DATA_FILE = "com.paherdin.record.benchmark.test-benchmarkData.json"
        const val MANIFEST_FILE = "m1-run-manifest.json"
        const val TRACE_FILE_SUFFIX = ".perfetto-trace"

        /** The frozen harness identity (exact target/test packages and components). */
        const val SUITE = "M1StartupBenchmark"
        const val FIXTURE_VERSION = M1StartupFixtureGenerator.FIXTURE_VERSION
        const val TARGET_PACKAGE = "com.paherdin.record.benchmark"
        const val TEST_PACKAGE = "com.paherdin.record.benchmark.test"
        const val TARGET_ACTIVITY = "com.paherdin.record.MainActivity"
        const val SETUP_COMPONENT = "com.paherdin.record.benchmark.M1StartupFixtureActivity"
        /**
         * The exact string meta-data value the merged manifest must carry
         * (`m1-benchmark`): string values are the only application meta-data
         * shape every package parser (including HyperOS's, which drops
         * boolean-typed values at parse time) preserves.
         */
        const val VARIANT_MARKER = "m1-benchmark"
        const val DATA_DIR_PREFIX = "/data/user/0/"

        /** The only package tokens a logged command may carry. */
        val ALLOWED_PACKAGE_TOKENS = setOf(TARGET_PACKAGE, TEST_PACKAGE, TARGET_ACTIVITY)

        /** Frozen cardinalities and budgets (spec 11.3). */
        const val SAMPLES_PER_BLOCK = 30
        const val WARMUPS_PER_BLOCK = 5
        const val EXPECTED_TRACE_COUNT = 240   // 8 blocks x 30 measured iterations
        const val EXPECTED_ITERATION_RECORDS = 240
        const val THERMAL_MAX_STATUS = 1       // LIGHT (NONE=0, LIGHT=1, MODERATE=2, ...)
        const val TTID_P95_BUDGET_MS = 1_000.0
        const val TTFD_P95_BUDGET_MS = 1_500.0

        /** The pending-activation block keeps its TTFD as a diagnostic only. */
        const val PENDING_ACTIVATION_BLOCK = "startup04PendingActivation"

        /** One frozen startup block: name, fixture, and the TTFD presence rule. */
        data class BlockSpec(
            val block: String,
            val fixture: String,
            val ttfdRequired: Boolean,
            val iterations: Int = SAMPLES_PER_BLOCK,
            val warmups: Int = WARMUPS_PER_BLOCK,
        )

        /** The exact eight blocks, in frozen suite order (spec 11.2/11.3). */
        val BLOCKS = listOf(
            BlockSpec("startup01Fresh", fixture = "Fresh", ttfdRequired = true),
            BlockSpec("startup02Typical", fixture = "Typical", ttfdRequired = true),
            BlockSpec("startup03HighVolume", fixture = "HighVolume", ttfdRequired = true),
            BlockSpec(
                "startup04PendingActivation",
                fixture = "PendingActivation",
                ttfdRequired = true,
            ),
            BlockSpec(
                "startup05MigrationPrepared",
                fixture = "PendingMigrationPrepared",
                ttfdRequired = false,
            ),
            BlockSpec(
                "startup06MigrationFailedSetIsolated",
                fixture = "PendingMigrationFailedSetIsolated",
                ttfdRequired = false,
            ),
            BlockSpec(
                "startup07MigrationRestoreCopyValidated",
                fixture = "PendingMigrationRestoreCopyValidated",
                ttfdRequired = false,
            ),
            BlockSpec(
                "startup08MigrationMainReplaced",
                fixture = "PendingMigrationMainReplaced",
                ttfdRequired = false,
            ),
        )
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
        /** `profilerOutputs` — retained profiler file names (order preserved). */
        val profilerOutputs: List<String>,
    )

    /** The suite-level identity block of the run manifest. */
    data class ParsedIdentity(
        val targetPackage: String,
        val testPackage: String,
        val targetActivity: String,
        val setupComponent: String,
        val variantMarker: String,
        val signingCertSha256: String,
        val dataDir: String,
    )

    /** The suite-level environment snapshot of the run manifest. */
    data class ParsedEnvironment(
        val brand: String,
        val model: String,
        val device: String,
        val fingerprint: String,
        val sdkInt: Int?,
        val hardware: String,
        val cpuAbi: String,
        val totalRamMb: Int?,
        val thermalStatus: String,
        val batteryLevel: Int?,
        val displayPeak: String,
        val displayMin: String,
    )

    /** One frozen block row of the run manifest's block table. */
    data class ParsedBlockRow(
        val block: String,
        val fixture: String,
        val fixtureSha256: String,
        val iterations: Int?,
        val warmups: Int?,
        val ttfdRequired: Boolean?,
    )

    /** One logged command of the run manifest's command log. */
    data class ParsedCommandLogEntry(
        val command: String,
        val target: String,
        val allowed: Boolean?,
    )

    /** One measured iteration's control record from the run manifest. */
    data class ParsedIterationControl(
        val block: String,
        val iteration: Int,
        val fixture: String,
        val fixtureSha256: String,
        val thermalBefore: Int,
        val thermalAfter: Int,
        val pid: String,
        val exitInfoUnexpected: Boolean,
        val fixtureValidated: Boolean,
    )

    /** The full M1 run manifest. */
    data class ParsedRunManifest(
        val suite: String,
        val fixtureVersion: String,
        val environment: ParsedEnvironment,
        val identity: ParsedIdentity,
        val blocks: List<ParsedBlockRow>,
        val commandLog: List<ParsedCommandLogEntry>,
        val iterations: List<ParsedIterationControl>,
    )

    /**
     * Parses the one copied `<package>-benchmarkData.json` into its complete
     * `benchmarks[]` array (one entry per block, in name order); throws only on
     * malformed JSON.
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
        fun singleRuns(name: String): List<Double> {
            val runs = (metrics[name] as? JsonValue.Obj)?.entries?.get("runs") as? JsonValue.Arr
                ?: return emptyList()
            return runs.items.map { (it as? JsonValue.Num)?.raw?.toDoubleOrNull()
                ?: error("report $file metrics.$name run contains a non-number") }
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
                CONTRACT.TTID_METRIC to singleRuns(CONTRACT.TTID_METRIC),
                CONTRACT.TTFD_METRIC to singleRuns(CONTRACT.TTFD_METRIC),
            ),
            profilerOutputs = profilerOutputs,
        )
    }

    /**
     * Parses one copied `m1-run-manifest.json`; throws only on malformed JSON
     * or missing fields. Numeric fields accept `NaN`/`Infinity` tokens; the
     * validator reports them as problems rather than parse errors.
     */
    fun parseRunManifest(file: File): ParsedRunManifest {
        val root = MiniJsonParser.parse(file.readText()) as? JsonValue.Obj
            ?: error("run manifest $file is not a JSON object")
        fun str(key: String): String =
            (root.entries[key] as? JsonValue.Str)?.value
                ?: error("run manifest $file missing string field '$key'")
        val suite = str("suite")
        val fixtureVersion = str("fixtureVersion")
        val environmentObj = root.entries["environment"] as? JsonValue.Obj
            ?: error("run manifest $file missing object 'environment'")
        fun envStr(key: String): String = (environmentObj.entries[key] as? JsonValue.Str)?.value
            ?: error("run manifest $file environment.$key missing or non-string")
        fun envInt(key: String): Int? = (environmentObj.entries[key] as? JsonValue.Num)?.raw?.toIntOrNull()
        val environment = ParsedEnvironment(
            brand = envStr("brand"),
            model = envStr("model"),
            device = envStr("device"),
            fingerprint = envStr("fingerprint"),
            sdkInt = envInt("sdkInt"),
            hardware = envStr("hardware"),
            cpuAbi = envStr("cpuAbi"),
            totalRamMb = envInt("totalRamMb"),
            thermalStatus = envStr("thermalStatus"),
            batteryLevel = envInt("batteryLevel"),
            displayPeak = envStr("displayPeak"),
            displayMin = envStr("displayMin"),
        )
        val identityObj = root.entries["identity"] as? JsonValue.Obj
            ?: error("run manifest $file missing object 'identity'")
        fun identityStr(key: String): String = (identityObj.entries[key] as? JsonValue.Str)?.value
            ?: error("run manifest $file identity.$key missing or non-string")
        val identity = ParsedIdentity(
            targetPackage = identityStr("targetPackage"),
            testPackage = identityStr("testPackage"),
            targetActivity = identityStr("targetActivity"),
            setupComponent = identityStr("setupComponent"),
            variantMarker = identityStr("variantMarker"),
            signingCertSha256 = identityStr("signingCertSha256"),
            dataDir = identityStr("dataDir"),
        )
        val blocksJson = root.entries["blocks"] as? JsonValue.Arr
            ?: error("run manifest $file missing 'blocks' array")
        val blocks = blocksJson.items.map { item ->
            val o = item as? JsonValue.Obj
                ?: error("run manifest $file block row is not an object")
            fun blockStr(key: String): String = (o.entries[key] as? JsonValue.Str)?.value
                ?: error("run manifest $file block row missing string field '$key'")
            fun blockInt(key: String): Int? = (o.entries[key] as? JsonValue.Num)?.raw?.toIntOrNull()
            fun blockBool(key: String): Boolean? = when (val v = o.entries[key]) {
                is JsonValue.Bool -> v.value
                is JsonValue.Str -> v.value.toBooleanStrictOrNull()
                else -> null
            }
            ParsedBlockRow(
                block = blockStr("block"),
                fixture = blockStr("fixture"),
                fixtureSha256 = blockStr("fixtureSha256"),
                iterations = blockInt("iterations"),
                warmups = blockInt("warmups"),
                ttfdRequired = blockBool("ttfdRequired"),
            )
        }
        val commandLogJson = root.entries["commandLog"] as? JsonValue.Arr
            ?: error("run manifest $file missing 'commandLog' array")
        val commandLog = commandLogJson.items.map { item ->
            val o = item as? JsonValue.Obj
                ?: error("run manifest $file command-log entry is not an object")
            fun cmdStr(key: String): String = (o.entries[key] as? JsonValue.Str)?.value
                ?: error("run manifest $file command-log entry missing string field '$key'")
            ParsedCommandLogEntry(
                command = cmdStr("command"),
                target = cmdStr("target"),
                allowed = when (val v = o.entries["allowed"]) {
                    is JsonValue.Bool -> v.value
                    else -> null
                },
            )
        }
        val iterationsJson = root.entries["iterations"] as? JsonValue.Arr
            ?: error("run manifest $file missing 'iterations' array")
        val iterations = iterationsJson.items.map { item ->
            val o = item as? JsonValue.Obj
                ?: error("run manifest $file iteration record is not an object")
            fun itStr(key: String): String = (o.entries[key] as? JsonValue.Str)?.value
                ?: error("run manifest $file iteration missing string field '$key'")
            fun itInt(key: String): Int = (o.entries[key] as? JsonValue.Num)?.raw?.toIntOrNull()
                ?: error("run manifest $file iteration missing or non-integer field '$key'")
            fun itBool(key: String): Boolean = (o.entries[key] as? JsonValue.Bool)?.value
                ?: error("run manifest $file iteration missing or non-boolean field '$key'")
            ParsedIterationControl(
                block = itStr("block"),
                iteration = itInt("iteration"),
                fixture = itStr("fixture"),
                fixtureSha256 = itStr("fixtureSha256"),
                thermalBefore = itInt("thermalBefore"),
                thermalAfter = itInt("thermalAfter"),
                pid = itStr("pid"),
                exitInfoUnexpected = itBool("exitInfoUnexpected"),
                fixtureValidated = itBool("fixtureValidated"),
            )
        }
        return ParsedRunManifest(
            suite = suite,
            fixtureVersion = fixtureVersion,
            environment = environment,
            identity = identity,
            blocks = blocks,
            commandLog = commandLog,
            iterations = iterations,
        )
    }
}

/** Validation outcome: empty [problems] = PASS; retained diagnostics exposed for tests. */
data class M1StartupReportValidation(
    val problems: List<String>,
    /** TTID p95 (nearest rank 29) per frozen block name, ms. */
    val ttidP95ByBlock: Map<String, Double>,
    /** TTFD p95 per block that reported the metric, ms. */
    val ttfdP95ByBlock: Map<String, Double>,
    /** The pending-activation block's TTFD p95 diagnostic; never budgeted. */
    val pendingActivationTtfdP95Ms: Double?,
) {
    val passed: Boolean get() = problems.isEmpty()
}

/**
 * Recomputes the frozen gates from the raw reports under [reportRoot] and
 * requires the exact named blocks, sample counts, identities, and iteration
 * records. Pure: reads only the passed root, writes nothing, returns a problem
 * list.
 */
object M1StartupReportValidator {

    /** The pending-migration fixtures whose gate must never reach a shell. */
    private val PENDING_MIGRATION_FIXTURES = setOf(
        "PendingMigrationPrepared",
        "PendingMigrationFailedSetIsolated",
        "PendingMigrationRestoreCopyValidated",
        "PendingMigrationMainReplaced",
    )

    /** Nearest-rank percentile: the `ceil(p/100 * n)`-th smallest of a sorted list. */
    fun nearestRankPercentile(sorted: List<Double>, percentile: Int): Double {
        require(sorted.isNotEmpty()) { "percentile of an empty sample set" }
        val rank = kotlin.math.ceil(percentile / 100.0 * sorted.size).toInt()
        return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
    }

    fun validate(reportRoot: File): M1StartupReportValidation {
        require(reportRoot.isDirectory) { "report root is not a directory: $reportRoot" }
        val problems = mutableListOf<String>()
        val contract = M1StartupReportParser.CONTRACT

        // ---- exactly one 1.4.1 benchmark-data file, holding all eight blocks ----
        val reportFiles = reportRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(contract.BENCHMARK_DATA_SUFFIX) }.toList()
        if (reportFiles.size != 1) {
            problems += "expected exactly one '${contract.BENCHMARK_DATA_SUFFIX}' " +
                "benchmark-data file, found ${reportFiles.size}"
        }
        val parsedByName = LinkedHashMap<String, M1StartupReportParser.ParsedBenchmarkReport>()
        for (file in reportFiles) {
            val reports = try {
                M1StartupReportParser.parseBenchmarkDataFile(file)
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
        val ttidP95 = LinkedHashMap<String, Double>()
        val ttfdP95 = LinkedHashMap<String, Double>()
        for (spec in contract.BLOCKS) {
            val report = parsedByName[spec.block]
            if (report == null) {
                problems += "missing report for block '${spec.block}'"
                continue
            }
            checkBlock(spec, report, problems, ttidP95, ttfdP95)
        }
        val extraNames = parsedByName.keys - contract.BLOCKS.map { it.block }.toSet()
        for (extra in extraNames) {
            problems += "benchmark-data has a benchmark entry for unknown block '$extra'"
        }

        // ---- exactly 240 Perfetto trace files ----
        val traceFiles = reportRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(contract.TRACE_FILE_SUFFIX) }.toList()
        if (traceFiles.size != contract.EXPECTED_TRACE_COUNT) {
            problems += "expected exactly ${contract.EXPECTED_TRACE_COUNT} " +
                "'${contract.TRACE_FILE_SUFFIX}' traces, found ${traceFiles.size}"
        }

        // ---- run manifest: the exact frozen M1 contract ----
        val manifestFiles = reportRoot.walkTopDown()
            .filter { it.isFile && it.name == contract.MANIFEST_FILE }.toList()
        val fixtureShaByBlock = LinkedHashMap<String, String>()
        if (manifestFiles.size != 1) {
            problems += "expected exactly one '${contract.MANIFEST_FILE}', found ${manifestFiles.size}"
        } else {
            try {
                val manifest = M1StartupReportParser.parseRunManifest(manifestFiles[0])
                checkManifest(manifest, problems, fixtureShaByBlock)
            } catch (e: Exception) {
                problems += "unparseable run manifest ${manifestFiles[0]}: ${e.message}"
            }
        }

        // ---- profiler outputs: per-block count == iteration count, 240 distinct
        // referenced filenames overall, every one resolving to a retained trace. ----
        val referencedFilenames = mutableListOf<String>()
        for (spec in contract.BLOCKS) {
            val report = parsedByName[spec.block] ?: continue
            if (report.profilerOutputs.size != spec.iterations) {
                problems += "${spec.block} has ${report.profilerOutputs.size} " +
                    "profilerOutputs, expected ${spec.iterations}"
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

        return M1StartupReportValidation(
            problems,
            ttidP95ByBlock = ttidP95,
            ttfdP95ByBlock = ttfdP95,
            pendingActivationTtfdP95Ms = ttfdP95[contract.PENDING_ACTIVATION_BLOCK],
        )
    }

    /** Per-block raw sample gates: counts, finiteness, TTFD presence rule, p95s. */
    private fun checkBlock(
        spec: M1StartupReportParser.CONTRACT.BlockSpec,
        report: M1StartupReportParser.ParsedBenchmarkReport,
        problems: MutableList<String>,
        ttidP95: MutableMap<String, Double>,
        ttfdP95: MutableMap<String, Double>,
    ) {
        val contract = M1StartupReportParser.CONTRACT
        val label = spec.block
        if (report.repeatIterations != null && report.repeatIterations != spec.iterations) {
            problems += "$label repeatIterations ${report.repeatIterations} != expected ${spec.iterations}"
        }
        val ttid = report.singleMetrics[contract.TTID_METRIC].orEmpty()
        if (ttid.isEmpty()) {
            problems += "$label missing ${contract.TTID_METRIC} samples"
        } else if (ttid.size != spec.iterations) {
            problems += "$label has ${ttid.size} ${contract.TTID_METRIC} samples, " +
                "expected exactly ${spec.iterations} (one per measured iteration)"
        }
        if (ttid.any { !it.isFinite() || it <= 0.0 }) {
            problems += "$label has a non-positive or non-finite ${contract.TTID_METRIC} sample"
        }
        if (ttid.size == spec.iterations) {
            val p95 = nearestRankPercentile(ttid.sorted(), 95)
            ttidP95[label] = p95
            if (p95 > contract.TTID_P95_BUDGET_MS) {
                problems += "$label TTID p95 ${p95}ms exceeds the ${contract.TTID_P95_BUDGET_MS}ms budget"
            }
        }
        val ttfd = report.singleMetrics[contract.TTFD_METRIC].orEmpty()
        if (spec.ttfdRequired) {
            if (ttfd.isEmpty()) {
                problems += "$label missing ${contract.TTFD_METRIC} samples (the shell never " +
                    "reached reportFullyDrawn, so the ready sentinel failed)"
            } else if (ttfd.size != spec.iterations) {
                problems += "$label has ${ttfd.size} ${contract.TTFD_METRIC} samples, " +
                    "expected exactly ${spec.iterations}"
            }
            if (ttfd.any { !it.isFinite() || it <= 0.0 }) {
                problems += "$label has a non-positive or non-finite ${contract.TTFD_METRIC} sample"
            }
            if (ttfd.size == spec.iterations) {
                val p95 = nearestRankPercentile(ttfd.sorted(), 95)
                ttfdP95[label] = p95
                // The TTFD budget gates the healthy blocks only; the
                // pending-activation block keeps its p95 as a diagnostic.
                val healthy = spec.fixture !in PENDING_MIGRATION_FIXTURES &&
                    spec.block != contract.PENDING_ACTIVATION_BLOCK
                if (healthy && p95 > contract.TTFD_P95_BUDGET_MS) {
                    problems += "$label TTFD p95 ${p95}ms exceeds the " +
                        "${contract.TTFD_P95_BUDGET_MS}ms budget"
                }
            }
        } else if (ttfd.isNotEmpty()) {
            problems += "$label reported ${contract.TTFD_METRIC} samples but a pending-migration " +
                "gate never renders a shell (no-shell sentinel violated)"
        }
    }

    /** The run manifest's exact frozen M1 contract. */
    private fun checkManifest(
        manifest: M1StartupReportParser.ParsedRunManifest,
        problems: MutableList<String>,
        fixtureShaByBlock: MutableMap<String, String>,
    ) {
        val contract = M1StartupReportParser.CONTRACT
        if (manifest.suite != contract.SUITE) {
            problems += "run manifest suite '${manifest.suite}' != expected '${contract.SUITE}'"
        }
        if (manifest.fixtureVersion != contract.FIXTURE_VERSION) {
            problems += "run manifest fixtureVersion '${manifest.fixtureVersion}' != expected " +
                "'${contract.FIXTURE_VERSION}'"
        }

        // Environment: every snapshot field present and nonblank.
        val env = manifest.environment
        for ((key, value) in listOf(
            "brand" to env.brand, "model" to env.model, "device" to env.device,
            "fingerprint" to env.fingerprint, "hardware" to env.hardware,
            "cpuAbi" to env.cpuAbi, "thermalStatus" to env.thermalStatus,
            "displayPeak" to env.displayPeak, "displayMin" to env.displayMin,
        )) {
            if (value.isBlank()) problems += "run manifest environment.$key is blank"
        }
        if (env.sdkInt == null) problems += "run manifest environment.sdkInt missing or non-integer"
        if (env.totalRamMb == null) problems += "run manifest environment.totalRamMb missing or non-integer"
        if (env.batteryLevel == null) problems += "run manifest environment.batteryLevel missing or non-integer"

        // Identity: the exact benchmark-variant facts.
        val identity = manifest.identity
        if (identity.targetPackage != contract.TARGET_PACKAGE) {
            problems += "run manifest identity.targetPackage '${identity.targetPackage}' != expected " +
                "'${contract.TARGET_PACKAGE}'"
        }
        if (identity.testPackage != contract.TEST_PACKAGE) {
            problems += "run manifest identity.testPackage '${identity.testPackage}' != expected " +
                "'${contract.TEST_PACKAGE}'"
        }
        if (identity.targetActivity != contract.TARGET_ACTIVITY) {
            problems += "run manifest identity.targetActivity '${identity.targetActivity}' != expected " +
                "'${contract.TARGET_ACTIVITY}'"
        }
        if (identity.setupComponent != contract.SETUP_COMPONENT) {
            problems += "run manifest identity.setupComponent '${identity.setupComponent}' != expected " +
                "'${contract.SETUP_COMPONENT}'"
        }
        if (identity.variantMarker != contract.VARIANT_MARKER) {
            problems += "run manifest identity.variantMarker '${identity.variantMarker}' != expected " +
                "'${contract.VARIANT_MARKER}' (not the benchmark-variant app)"
        }
        if (!Regex("^[0-9a-f]{64}$").matches(identity.signingCertSha256)) {
            problems += "run manifest identity.signingCertSha256 must be 64 lowercase hex, " +
                "got '${identity.signingCertSha256}'"
        }
        if (identity.dataDir != contract.DATA_DIR_PREFIX + contract.TARGET_PACKAGE) {
            problems += "run manifest identity.dataDir '${identity.dataDir}' != expected " +
                "'${contract.DATA_DIR_PREFIX}${contract.TARGET_PACKAGE}'"
        }

        // Block table: the exact eight frozen rows.
        if (manifest.blocks.size != contract.BLOCKS.size) {
            problems += "run manifest has ${manifest.blocks.size} block rows, expected ${contract.BLOCKS.size}"
        }
        for ((index, spec) in contract.BLOCKS.withIndex()) {
            val row = manifest.blocks.getOrNull(index)
            if (row == null) continue
            if (row.block != spec.block) {
                problems += "run manifest block row $index '${row.block}' != expected '${spec.block}'"
            }
            if (row.fixture != spec.fixture) {
                problems += "run manifest block '${row.block}' fixture '${row.fixture}' " +
                    "!= expected '${spec.fixture}'"
            }
            if (row.ttfdRequired == null || row.ttfdRequired != spec.ttfdRequired) {
                problems += "run manifest block '${row.block}' ttfdRequired ${row.ttfdRequired} " +
                    "!= expected ${spec.ttfdRequired}"
            }
            if (row.iterations == null || row.iterations != spec.iterations) {
                problems += "run manifest block '${row.block}' iterations ${row.iterations} " +
                    "!= expected ${spec.iterations}"
            }
            if (row.warmups == null || row.warmups != spec.warmups) {
                problems += "run manifest block '${row.block}' warmups ${row.warmups} " +
                    "!= expected ${spec.warmups}"
            }
            if (!Regex("^[0-9a-f]{64}$").matches(row.fixtureSha256)) {
                problems += "run manifest block '${row.block}' fixtureSha256 must be 64 lowercase hex"
            }
            fixtureShaByBlock[spec.block] = row.fixtureSha256
        }

        // Command log: nonempty, every entry allowed, targets only the variant,
        // and no production package token outside the frozen allowlist.
        if (manifest.commandLog.isEmpty()) {
            problems += "run manifest commandLog is empty (the identity/command evidence is mandatory)"
        }
        for (entry in manifest.commandLog) {
            if (entry.allowed != true) {
                problems += "run manifest commandLog contains a disallowed command: ${entry.command}"
            }
            if (entry.target != contract.TARGET_PACKAGE && entry.target != contract.TEST_PACKAGE) {
                problems += "run manifest commandLog target '${entry.target}' is not the " +
                    "benchmark variant or its test package"
            }
            if (!commandTokensAllowed(entry.command)) {
                problems += "run manifest commandLog command carries a package token outside the " +
                    "benchmark allowlist: ${entry.command}"
            }
        }

        // Iteration records: exactly 240, ordered, no duplicate/missing/unknown,
        // each matching its block's frozen fixture and integrity facts.
        val expected = LinkedHashMap<String, MutableSet<Int>>()
        for (spec in contract.BLOCKS) expected[spec.block] = (0 until spec.iterations).toMutableSet()
        val actual = LinkedHashMap<String, MutableSet<Int>>()
        if (manifest.iterations.size != contract.EXPECTED_ITERATION_RECORDS) {
            problems += "run manifest has ${manifest.iterations.size} iteration records, " +
                "expected ${contract.EXPECTED_ITERATION_RECORDS}"
        }
        for (control in manifest.iterations) {
            val spec = contract.BLOCKS.firstOrNull { it.block == control.block }
            if (spec == null) {
                problems += "run manifest has an iteration record for unknown block '${control.block}'"
                continue
            }
            if (!actual.getOrPut(control.block) { mutableSetOf() }.add(control.iteration)) {
                problems += "duplicate iteration record ${control.block}#${control.iteration}"
            }
            if (control.fixture != spec.fixture) {
                problems += "iteration ${control.block}#${control.iteration} fixture " +
                    "'${control.fixture}' != expected '${spec.fixture}'"
            }
            if (fixtureShaByBlock[control.block]?.let { it != control.fixtureSha256 } == true) {
                problems += "iteration ${control.block}#${control.iteration} fixtureSha256 " +
                    "does not match its block table row"
            }
            if (!Regex("^[0-9a-f]{64}$").matches(control.fixtureSha256)) {
                problems += "iteration ${control.block}#${control.iteration} fixtureSha256 " +
                    "must be 64 lowercase hex"
            }
            if (control.thermalBefore > contract.THERMAL_MAX_STATUS ||
                control.thermalAfter > contract.THERMAL_MAX_STATUS
            ) {
                problems += "iteration ${control.block}#${control.iteration} thermal " +
                    "${control.thermalBefore}/${control.thermalAfter} exceeds " +
                    "LIGHT(${contract.THERMAL_MAX_STATUS})"
            }
            if (control.pid.isBlank()) {
                problems += "iteration ${control.block}#${control.iteration} has a blank PID"
            }
            if (control.exitInfoUnexpected) {
                problems += "iteration ${control.block}#${control.iteration} reported " +
                    "unexpected exit info (crash/ANR/timeout)"
            }
            if (!control.fixtureValidated) {
                problems += "iteration ${control.block}#${control.iteration} ran against an " +
                    "unvalidated fixture"
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
                problems += "run manifest has out-of-range iteration record(s) for $block: $extra"
            }
        }
    }

    /**
     * True when every package token in [command] is exactly one of the frozen
     * benchmark allowlist tokens and the command carries no shell-quoting
     * character. Any occurrence of the production `com.paherdin.record`
     * namespace outside those complete tokens (for example
     * `pm clear com.paherdin.record`) is refused, as is any quoting character
     * that could split one token into two argv words on the device shell
     * (`pm clear com.paherdin.rec"ord"`).
     */
    fun commandTokensAllowed(command: String): Boolean {
        val contract = M1StartupReportParser.CONTRACT
        if (command.any { it == '"' || it == '\'' || it == '\\' || it == '$' }) {
            return false
        }
        var text = command
        // Strip every allowed complete token; a residual production-prefix
        // fragment means the command carried an out-of-allowlist package token.
        for (token in contract.ALLOWED_PACKAGE_TOKENS) {
            text = text.replace(token, " ")
        }
        return !text.contains("com.paherdin.record")
    }
}

/**
 * The M1 raw-report gate entry (plan Step 6 / the T10 device-task gate):
 * `:core:data:validateM1StartupReports` runs this `main` with
 * `-Dm1ReportDir=<copied report root>` and requires exit code 0.
 */
fun main() {
    val reportDir = System.getProperty("m1ReportDir")
        ?: error("missing -Dm1ReportDir=<copied benchmark report root>")
    val result = M1StartupReportValidator.validate(File(reportDir))
    val contract = M1StartupReportParser.CONTRACT
    println("M1 startup report validation for $reportDir")
    println("contract: ${contract.FIXTURE_VERSION}, ${contract.BLOCKS.size} blocks, " +
        "${contract.SAMPLES_PER_BLOCK} samples per block")
    for (spec in contract.BLOCKS) {
        println("  ${spec.block} fixture=${spec.fixture} ttfdRequired=${spec.ttfdRequired}")
    }
    if (result.passed) {
        println("PASS")
        println("TTID p95 per block: ${result.ttidP95ByBlock}")
        println("TTFD p95 per block: ${result.ttfdP95ByBlock}")
        println("pendingActivationTtfdP95Ms=${result.pendingActivationTtfdP95Ms}")
    } else {
        println("FAIL (${result.problems.size} problems)")
        for (problem in result.problems) println("  - $problem")
        exitProcess(1)
    }
}
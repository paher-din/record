package com.paherdin.record.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileInputStream
import java.util.UUID
import org.junit.AfterClass
import org.junit.Rule
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * M1-T09 Step 5 — the isolated M1 startup Macrobenchmark suite (plan Task
 * M1-T09 Step 5; spec 11.2/11.3). Eight frozen blocks, executed in
 * name-ascending order, one per `m1-startup-v1` fixture:
 *
 *  - exactly 5 unmeasured cold warmups per fixture, each preceded by an
 *    identical fixture restore outside any timed launch, then
 *  - exactly 30 measured `StartupMode.COLD` launches per fixture with
 *    `CompilationMode.Full()` and [StartupTimingMetric] only.
 *
 * Correctness protocol per iteration (all outside the timed launch):
 *  - the identical fixture is restored through the benchmark-only setup
 *    surface (`pm clear` of the benchmark variant + one fresh one-shot setup
 *    token launch of `M1StartupFixtureActivity` + the fresh-run sentinel
 *    wait), because a pending activation resolves during the launch and every
 *    warmup/measurement must replay the exact fixture state;
 *  - the target PID is captured after the launch (process-alive sentinel),
 *  - the package exit-info evidence is diffed against the suite baseline
 *    (no ANR/crash/OOM/process-exit evidence may appear), and
 *  - the thermal status is observed before and after the launch and must stay
 *    within LIGHT.
 *
 * Ready/recovery semantics sentinel: `StartupTimingMetric` reports TTFD only
 * when the target fires `reportFullyDrawn`, which MainActivity does only in
 * the Ready branch — so the healthy and pending-activation bindings must
 * report TTFD (the shell rendered) and the pending-migration bindings must
 * report none (the M1 gate fails closed on every resumable migration-recovery
 * stage and never renders a shell). The host validator
 * (`:core:data:validateM1StartupReports`) enforces exactly that per binding.
 *
 * Every iteration appends one strict control record to `m1-run-manifest.json`
 * (exactly 240 ordered records). Nothing is ever dropped, replaced, or
 * retried; a missing sentinel, fixture mismatch, thermal `MODERATE+`, PID
 * loss, unexpected exit info, or setup failure aborts the suite.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class M1StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation = instrumentation.uiAutomation

    /** Self-instrumenting module: the target app package is the frozen constant. */
    private val targetPackage = M1StartupHarnessGates.TARGET_PACKAGE
    private val testPackage = instrumentation.context.packageName

    private val sentinels = M1StartupSentinelReader(::shell)
    private val targetMainComponent = "${M1StartupHarnessGates.TARGET_PACKAGE}/" +
        M1StartupHarnessGates.TARGET_ACTIVITY
    private val setupComponent = "${M1StartupHarnessGates.TARGET_PACKAGE}/" +
        M1StartupHarnessGates.SETUP_COMPONENT

    // ------------------------------------------------------------------
    // The eight frozen startup blocks (name-ascending execution order)
    // ------------------------------------------------------------------

    /** Lifecycle init + 5 warmups + 30 measured cold launches of Fresh. */
    @Test
    fun startup01Fresh() = runSafely(BLOCK_FRESH) {
        suiteLifecycleInit()
        runStartupBlock(BLOCK_FRESH, FIXTURE_FRESH, ttfdRequired = true, settleMs = SETTLE_HEALTHY_MS)
    }

    /** 5 warmups + 30 measured cold launches of Typical. */
    @Test
    fun startup02Typical() = runSafely(BLOCK_TYPICAL) {
        checkSuiteHealthy()
        runStartupBlock(BLOCK_TYPICAL, FIXTURE_TYPICAL, ttfdRequired = true, settleMs = SETTLE_HEALTHY_MS)
    }

    /** 5 warmups + 30 measured cold launches of High-volume. */
    @Test
    fun startup03HighVolume() = runSafely(BLOCK_HIGH_VOLUME) {
        checkSuiteHealthy()
        runStartupBlock(
            BLOCK_HIGH_VOLUME,
            FIXTURE_HIGH_VOLUME,
            ttfdRequired = true,
            settleMs = SETTLE_HEALTHY_MS,
        )
    }

    /** 5 warmups + 30 measured cold launches of the pending-activation boundary. */
    @Test
    fun startup04PendingActivation() = runSafely(BLOCK_PENDING_ACTIVATION) {
        checkSuiteHealthy()
        runStartupBlock(
            BLOCK_PENDING_ACTIVATION,
            FIXTURE_PENDING_ACTIVATION,
            ttfdRequired = true,
            settleMs = SETTLE_HEALTHY_MS,
        )
    }

    /** 5 warmups + 30 measured cold launches of the PREPARED recovery stage. */
    @Test
    fun startup05MigrationPrepared() = runSafely(BLOCK_MIGRATION_PREPARED) {
        checkSuiteHealthy()
        runStartupBlock(
            BLOCK_MIGRATION_PREPARED,
            FIXTURE_PENDING_MIGRATION_PREPARED,
            ttfdRequired = false,
            settleMs = SETTLE_BLOCKED_MS,
        )
    }

    /** 5 warmups + 30 measured cold launches of the FAILED_SET_ISOLATED stage. */
    @Test
    fun startup06MigrationFailedSetIsolated() = runSafely(BLOCK_MIGRATION_FAILED_SET_ISOLATED) {
        checkSuiteHealthy()
        runStartupBlock(
            BLOCK_MIGRATION_FAILED_SET_ISOLATED,
            FIXTURE_PENDING_MIGRATION_FAILED_SET_ISOLATED,
            ttfdRequired = false,
            settleMs = SETTLE_BLOCKED_MS,
        )
    }

    /** 5 warmups + 30 measured cold launches of the RESTORE_COPY_VALIDATED stage. */
    @Test
    fun startup07MigrationRestoreCopyValidated() =
        runSafely(BLOCK_MIGRATION_RESTORE_COPY_VALIDATED) {
            checkSuiteHealthy()
            runStartupBlock(
                BLOCK_MIGRATION_RESTORE_COPY_VALIDATED,
                FIXTURE_PENDING_MIGRATION_RESTORE_COPY_VALIDATED,
                ttfdRequired = false,
                settleMs = SETTLE_BLOCKED_MS,
            )
        }

    /** 5 warmups + 30 measured cold launches of the MAIN_REPLACED stage. */
    @Test
    fun startup08MigrationMainReplaced() = runSafely(BLOCK_MIGRATION_MAIN_REPLACED) {
        checkSuiteHealthy()
        runStartupBlock(
            BLOCK_MIGRATION_MAIN_REPLACED,
            FIXTURE_PENDING_MIGRATION_MAIN_REPLACED,
            ttfdRequired = false,
            settleMs = SETTLE_BLOCKED_MS,
        )
    }

    // ------------------------------------------------------------------
    // One startup block: identical restores, 5 warmups, 30 measured launches
    // ------------------------------------------------------------------

    private fun runStartupBlock(
        block: String,
        fixture: String,
        ttfdRequired: Boolean,
        settleMs: Long,
    ) {
        // Exactly five manual cold warmups; each is preceded by an identical
        // fixture restore and produces no Macrobenchmark sample.
        repeat(M1StartupRunManifest.WARMUPS_PER_BLOCK) { i ->
            restoreFixture(fixture)
            launchMainActivity()
            val pid = pidOf() ?: throw AssertionError("warmup target PID missing after launch")
            settle(settleMs)
            requireThermal()
            assertStableExitInfo()
            Log.i(TAG, "$block warmup $i reached settle (pid=$pid)")
        }
        // The identical fixture's digest from the restore that just ran.
        val digest = sentinels.getString(M1StartupSentinelKeys.FIXTURE_CONTENT_DIGEST)
            ?: throw AssertionError("missing fixture content digest sentinel")
        manifest().addBlock(block, fixture, digest, ttfdRequired)
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.COLD,
            iterations = M1StartupRunManifest.ITERATIONS_PER_BLOCK,
            setupBlock = {
                // Identical fixture before every measurement, outside the
                // timed launch. COLD then kills the restored process and the
                // measured launch replays the exact fixture state.
                restoreFixture(fixture)
            },
            measureBlock = {
                val thermalBefore = requireThermal()
                startActivityAndWait()
                val pid = pidOf()
                    ?: throw AssertionError("target PID missing after the measured launch")
                val thermalAfter = requireThermal()
                settle(settleMs)
                // Per-iteration stability check after the bounded settle, so
                // any crash/ANR/OOM evidence from this launch is already
                // persisted when the diff runs.
                assertStableExitInfo()
                val iteration = requireIteration(iteration)
                manifest().addIteration(
                    block = block,
                    iteration = iteration,
                    thermalBefore = thermalBefore,
                    thermalAfter = thermalAfter,
                    pid = pid,
                )
                Log.i(
                    TAG,
                    "$block#$iteration ok pid=$pid thermal=$thermalBefore/$thermalAfter",
                )
            },
        )
    }

    // ------------------------------------------------------------------
    // Suite lifecycle (runs once, from startup01Fresh)
    // ------------------------------------------------------------------

    private fun suiteLifecycleInit() {
        if (suiteInitialized) return
        suiteInitialized = true

        // Identity gates: the exact benchmark-variant target and test packages,
        // both installed, and the installed variant non-debuggable.
        M1StartupHarnessGates.requireExactTargetPackage(targetPackage)
        M1StartupHarnessGates.requireExactTestPackage(testPackage)
        val targetPath = shell("pm path $targetPackage")
        check(targetPath.isNotBlank() && targetPath.contains("base.apk")) {
            "benchmark target not installed: pm path $targetPackage -> $targetPath"
        }
        val testPath = shell("pm path $testPackage")
        check(testPath.isNotBlank() && testPath.contains("base.apk")) {
            "benchmark test package not installed: pm path $testPackage -> $testPath"
        }
        M1StartupHarnessGates.assertNonDebuggable(shell("dumpsys package $targetPackage"))

        // Suite-level exit-info baseline and thermal evidence.
        exitInfoBaseline = captureExitInfo()
        val thermalNow = requireThermal()
        Log.i(TAG, "suite init thermal=$thermalNow")

        // One fresh fixture setup BEFORE the manifest exists publishes the
        // identity facts (signing cert digest, variant marker, data root) the
        // manifest mirrors; it also proves the benchmark-only setup surface.
        restoreFixture(FIXTURE_FRESH)
        val identitySentinels = sentinels.readAll()
        M1StartupHarnessGates.assertSetupSentinels(FIXTURE_FRESH, identitySentinels, FIXTURE_FRESH)
        val identity = M1StartupIdentity(
            targetPackage = targetPackage,
            testPackage = testPackage,
            targetActivity = M1StartupHarnessGates.TARGET_ACTIVITY,
            setupComponent = M1StartupHarnessGates.SETUP_COMPONENT,
            variantMarker = requireNotNull(identitySentinels[M1StartupSentinelKeys.VARIANT_MARKER]),
            signingCertSha256 =
                requireNotNull(identitySentinels[M1StartupSentinelKeys.SIGNING_CERT_SHA256]),
            dataDir = requireNotNull(identitySentinels[M1StartupSentinelKeys.DATA_ROOT]),
        )
        suiteManifest = M1StartupRunManifest(captureEnvironment(), identity)
        pendingCommandLog.forEach { (command, target) ->
            suiteManifest!!.logCommand(command, target)
        }
        pendingCommandLog.clear()
        Log.i(TAG, "suite lifecycle complete; identity and environment captured")
    }

    // ------------------------------------------------------------------
    // Fixture restore (outside every timed launch) and launch helpers
    // ------------------------------------------------------------------

    /**
     * Restores the identical fixture: `pm clear` of the benchmark variant
     * (the app-private root reset — never the production package) plus one
     * fresh launch of the benchmark-only setup surface with an explicit
     * fixture enum name and a fresh one-shot setup token, then the fresh-run
     * sentinel wait and the exact published-fixture validation.
     */
    private fun restoreFixture(fixture: String) {
        shell("pm clear $targetPackage")
        val token = UUID.randomUUID().toString()
        shell(
            "am start -W -n $setupComponent --es $EXTRA_FIXTURE $fixture " +
                "--es $EXTRA_SETUP_TOKEN $token",
        )
        waitForFreshSetup(fixture)
    }

    /** Cold-process launch of the frozen target MainActivity. */
    private fun launchMainActivity() {
        val out = shell("am start -W -f 0x10008000 -n $targetMainComponent")
        check(out.contains("Status: ok") || out.contains("LaunchState")) {
            "target MainActivity failed to launch: $out"
        }
    }

    /**
     * Waits for the setup surface's fresh-run stamp: the `m1-setup` mode, no
     * error, the exact fixture, and `setupDone` — then validates the exact
     * published-fixture sentinels.
     */
    private fun waitForFreshSetup(fixture: String) {
        val deadline = System.currentTimeMillis() + SETUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            sentinels.getString(M1StartupSentinelKeys.ERROR)?.let {
                throw AssertionError("fixture setup reported an error: $it")
            }
            val setupDone = sentinels.getString(M1StartupSentinelKeys.SETUP_DONE)
            val stampedFixture = sentinels.getString(M1StartupSentinelKeys.FIXTURE)
            val mode = sentinels.getString(M1StartupSentinelKeys.MODE)
            if (setupDone == "true" && stampedFixture == fixture &&
                mode == MODE_M1_SETUP
            ) {
                M1StartupHarnessGates.assertSetupSentinels(fixture, sentinels.readAll(), fixture)
                return
            }
            Thread.sleep(150)
        }
        throw AssertionError(
            "fixture setup for $fixture did not complete within ${SETUP_TIMEOUT_MS}ms",
        )
    }

    private fun settle(settleMs: Long) {
        Thread.sleep(settleMs)
    }

    private fun requireThermal(): Int {
        val status = M1StartupHarnessGates.thermalStatusFromDumpsys(shell("dumpsys thermalservice"))
            ?: throw AssertionError("no 'Thermal Status: N' line in dumpsys thermalservice")
        M1StartupHarnessGates.assertThermalWithinGate(status)
        return status
    }

    private fun pidOf(): String? =
        uiAutomation.executeShellCommand("pidof $targetPackage").use { pfd ->
            FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }.trim()
        }.takeIf { it.isNotEmpty() }

    private fun captureExitInfo(): String =
        uiAutomation.executeShellCommand("dumpsys activity exit-info $targetPackage").use { pfd ->
            FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
        }

    private fun assertStableExitInfo() {
        val after = captureExitInfo()
        M1StartupHarnessGates.assertNoUnexpectedExitInfo(requireBaseline(), after)
    }

    private fun requireBaseline(): String =
        exitInfoBaseline ?: throw AssertionError("suite exit-info baseline not captured")

    /**
     * The shell command gate: every command is allow-listed against the
     * benchmark package tokens, logged (one-shot setup tokens redacted), and
     * only then executed. Before the manifest exists the log is buffered and
     * flushed into the manifest at suite init.
     */
    private fun shell(command: String): String {
        M1StartupHarnessGates.assertCommandAllowed(command)
        val target = if (command.contains(testPackage)) testPackage else targetPackage
        val logged = command.replace(TOKEN_EXTRA_PATTERN, "$1<redacted>")
        if (suiteManifest != null) {
            suiteManifest!!.logCommand(logged, target)
        } else {
            pendingCommandLog.add(logged to target)
        }
        return uiAutomation.executeShellCommand(command).use { pfd ->
            FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
        }
    }

    private fun captureEnvironment(): M1StartupEnvironment {
        val activityManager = instrumentation.context
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val battery = shell("dumpsys battery")
        val level = Regex("level:\\s*(\\d+)").find(battery)?.groupValues?.get(1) ?: "-1"
        val peak = shell("settings get system peak_refresh_rate").trim().ifBlank { "null" }
        val min = shell("settings get system min_refresh_rate").trim().ifBlank { "null" }
        return M1StartupEnvironment(
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            fingerprint = Build.FINGERPRINT,
            sdkInt = Build.VERSION.SDK_INT,
            hardware = Build.HARDWARE,
            cpuAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            totalRamMb = (memory.totalMem / (1024L * 1024L)).toInt(),
            thermalStatus = requireThermal().toString(),
            batteryLevel = level.toIntOrNull() ?: -1,
            displayPeak = peak,
            displayMin = min,
        )
    }

    private fun requireIteration(value: Int?): Int =
        value ?: throw AssertionError("MacrobenchmarkScope.iteration not set")

    private fun manifest(): M1StartupRunManifest =
        suiteManifest
            ?: throw AssertionError("suite manifest not initialized (lifecycle init failed)")

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
        private const val TAG = "M1Benchmark"

        /** The fixture Activity's setup mode stamp (frozen across the boundary). */
        const val MODE_M1_SETUP = "m1-setup"

        // The eight frozen blocks and their exact fixtures (spec 11.2/11.3).
        const val BLOCK_FRESH = "startup01Fresh"
        const val BLOCK_TYPICAL = "startup02Typical"
        const val BLOCK_HIGH_VOLUME = "startup03HighVolume"
        const val BLOCK_PENDING_ACTIVATION = "startup04PendingActivation"
        const val BLOCK_MIGRATION_PREPARED = "startup05MigrationPrepared"
        const val BLOCK_MIGRATION_FAILED_SET_ISOLATED = "startup06MigrationFailedSetIsolated"
        const val BLOCK_MIGRATION_RESTORE_COPY_VALIDATED = "startup07MigrationRestoreCopyValidated"
        const val BLOCK_MIGRATION_MAIN_REPLACED = "startup08MigrationMainReplaced"

        const val FIXTURE_FRESH = "Fresh"
        const val FIXTURE_TYPICAL = "Typical"
        const val FIXTURE_HIGH_VOLUME = "HighVolume"
        const val FIXTURE_PENDING_ACTIVATION = "PendingActivation"
        const val FIXTURE_PENDING_MIGRATION_PREPARED = "PendingMigrationPrepared"
        const val FIXTURE_PENDING_MIGRATION_FAILED_SET_ISOLATED = "PendingMigrationFailedSetIsolated"
        const val FIXTURE_PENDING_MIGRATION_RESTORE_COPY_VALIDATED =
            "PendingMigrationRestoreCopyValidated"
        const val FIXTURE_PENDING_MIGRATION_MAIN_REPLACED = "PendingMigrationMainReplaced"

        // Bounded post-launch settle waits (outside the timed TTID/TTFD spans).
        const val SETTLE_HEALTHY_MS = 5_000L
        const val SETTLE_BLOCKED_MS = 2_000L

        const val SETUP_TIMEOUT_MS = 300_000L

        // Intent extras across the process boundary (frozen literals; the
        // :benchmark module has no dependency on any production module).
        const val EXTRA_FIXTURE = "m1Fixture"
        const val EXTRA_SETUP_TOKEN = "m1SetupToken"

        // ---- suite-shared state (crosses the eight test methods) ----
        private var suiteInitialized = false
        private var suiteManifest: M1StartupRunManifest? = null
        private var exitInfoBaseline: String? = null
        private var suiteAbort: Throwable? = null
        private val pendingCommandLog = ArrayList<Pair<String, String>>()

        /** Redacts the one-shot setup token value from logged commands. */
        private val TOKEN_EXTRA_PATTERN = Regex("(--es m1SetupToken\\s+)\\S+")

        @AfterClass
        fun finalizeSuite() {
            // Suite-level exit-info stability: no unexpected evidence may have
            // appeared over the whole run.
            exitInfoBaseline?.let { baseline ->
                val after = InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("dumpsys activity exit-info com.paherdin.record.benchmark")
                    .use { pfd ->
                        FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
                    }
                M1StartupHarnessGates.assertNoUnexpectedExitInfo(baseline, after)
            }
            suiteManifest?.finish()
        }
    }
}
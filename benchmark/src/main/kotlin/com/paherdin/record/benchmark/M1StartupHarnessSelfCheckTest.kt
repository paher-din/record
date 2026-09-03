package com.paherdin.record.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M1-T09 Step 4 — device-side harness self-check (plan Task M1-T09 Step 4).
 * Compiled by `:benchmark:assembleBenchmark` in T09; executed on the device at
 * Task M1-T10. It pins the harness-side gates that have no host mirror:
 *
 *  - the command allowlist accepts the exact benchmark setup/clear/launch
 *    commands and refuses any occurrence of the production
 *    `com.paherdin.record` namespace outside the frozen benchmark tokens
 *    (bare production package, `run-as`, quoted forms, suffixed variants),
 *  - the identity gates refuse a wrong target/test package, a debuggable
 *    variant, a missing variant marker, and a wrong data dir,
 *  - the fixture setup sentinel validation fails closed on a fixture
 *    mismatch, a wrong fixture version, a non-hex digest, an unvalidated
 *    fixture, or an unfinished setup, and
 *  - the run-manifest model refuses duplicate/out-of-range/unregistered
 *    iterations, thermal beyond LIGHT, blank pids, disallowed or off-target
 *    logged commands, and publishes the exact frozen JSON shape.
 */
@RunWith(AndroidJUnit4::class)
class M1StartupHarnessSelfCheckTest {

    // ------------------------------------------------------------------
    // Command allowlist (the M1 red line against production data)
    // ------------------------------------------------------------------

    @Test
    fun commandAllowlistAcceptsTheExactBenchmarkCommands() {
        val target = M1StartupHarnessGates.TARGET_PACKAGE
        val test = M1StartupHarnessGates.TEST_PACKAGE
        for (command in listOf(
            "pm clear $target",
            "pm path $target",
            "pm path $test",
            "dumpsys package $target",
            "am start -W -n $target/${M1StartupHarnessGates.SETUP_COMPONENT}",
            "am start -W -f 0x10008000 -n $target/${M1StartupHarnessGates.TARGET_ACTIVITY}",
            "pidof $target",
            "dumpsys activity exit-info $target",
            "dumpsys thermalservice",
        )) {
            M1StartupHarnessGates.assertCommandAllowed(command)
        }
    }

    @Test
    fun commandAllowlistRefusesEveryProductionPackageToken() {
        for (command in listOf(
            "pm clear com.paherdin.record",
            "run-as com.paherdin.record cat files/recovery/activation-recovery-v1.json",
            "am start -n \"com.paherdin.record/com.paherdin.record.MainActivity\"",
            "pm clear com.paherdin.recordx",
            "pm clear \"com.paherdin.record\"",
            "am force-stop com.paherdin.record",
        )) {
            try {
                M1StartupHarnessGates.assertCommandAllowed(command)
            } catch (expected: IllegalStateException) {
                continue
            }
            fail("the production token must be refused: $command")
        }
    }

    @Test
    fun commandAllowlistRefusesSuffixedLookalikes() {
        for (command in listOf(
            "pm clear com.paherdin.record.debug",
            "am start -n com.paherdin.record/com.paherdin.record.benchmark.MainActivity",
        )) {
            try {
                M1StartupHarnessGates.assertCommandAllowed(command)
            } catch (expected: IllegalStateException) {
                continue
            }
            fail("an out-of-allowlist token must be refused: $command")
        }
    }

    // ------------------------------------------------------------------
    // Identity gates
    // ------------------------------------------------------------------

    @Test
    fun identityGatesAcceptTheExactBenchmarkVariant() {
        M1StartupHarnessGates.requireExactTargetPackage(M1StartupHarnessGates.TARGET_PACKAGE)
        M1StartupHarnessGates.requireExactTestPackage(M1StartupHarnessGates.TEST_PACKAGE)
        M1StartupHarnessGates.assertVariantMarker("true")
        M1StartupHarnessGates.assertDataDir("/data/user/0/${M1StartupHarnessGates.TARGET_PACKAGE}")
        M1StartupHarnessGates.assertNonDebuggable("pkgFlags=[ SYSTEM VISIBLE ]")
    }

    @Test
    fun identityGatesRefuseWrongIdentities() {
        for (block in listOf<() -> Unit>(
            { M1StartupHarnessGates.requireExactTargetPackage("com.paherdin.record") },
            { M1StartupHarnessGates.requireExactTestPackage("com.other.test") },
            { M1StartupHarnessGates.assertVariantMarker("false") },
            { M1StartupHarnessGates.assertVariantMarker(null) },
            { M1StartupHarnessGates.assertDataDir("/data/user/0/com.paherdin.record") },
            { M1StartupHarnessGates.assertNonDebuggable("pkgFlags=[ DEBUGGABLE SYSTEM ]") },
        )) {
            try {
                block()
            } catch (expected: IllegalStateException) {
                continue
            }
            fail("the wrong identity must be refused")
        }
    }

    // ------------------------------------------------------------------
    // Fixture setup sentinel validation
    // ------------------------------------------------------------------

    private fun validSetupSentinels(): Map<String, String> = mapOf(
        M1StartupSentinelKeys.PACKAGE_NAME to M1StartupHarnessGates.TARGET_PACKAGE,
        M1StartupSentinelKeys.VARIANT_MARKER to "true",
        M1StartupSentinelKeys.SIGNING_CERT_SHA256 to "b".repeat(64),
        M1StartupSentinelKeys.DATA_ROOT to "/data/user/0/${M1StartupHarnessGates.TARGET_PACKAGE}",
        M1StartupSentinelKeys.FIXTURE to "Typical",
        M1StartupSentinelKeys.FIXTURE_VERSION to "m1-startup-v1",
        M1StartupSentinelKeys.FIXTURE_CONTENT_DIGEST to "a".repeat(64),
        M1StartupSentinelKeys.FIXTURE_VALIDATED to "true",
        M1StartupSentinelKeys.SETUP_DONE to "true",
    )

    @Test
    fun setupSentinelValidationAcceptsTheExactPublishedFixture() {
        val digest = M1StartupHarnessGates.assertSetupSentinels(
            fixture = "Typical",
            sentinels = validSetupSentinels(),
            expectedFixture = "Typical",
        )
        assertEquals("a".repeat(64), digest)
    }

    @Test
    fun setupSentinelValidationRefusesBrokenFixtureEvidence() {
        for (mutated in listOf(
            validSetupSentinels() - M1StartupSentinelKeys.SETUP_DONE,
            validSetupSentinels() + mapOf(M1StartupSentinelKeys.SETUP_DONE to "false"),
            validSetupSentinels() + mapOf(M1StartupSentinelKeys.FIXTURE_VALIDATED to "false"),
            validSetupSentinels() + mapOf(M1StartupSentinelKeys.FIXTURE to "Fresh"),
            validSetupSentinels() + mapOf(M1StartupSentinelKeys.FIXTURE_VERSION to "m1-startup-v2"),
            validSetupSentinels() + mapOf(M1StartupSentinelKeys.FIXTURE_CONTENT_DIGEST to "short"),
            validSetupSentinels() + mapOf(M1StartupSentinelKeys.VARIANT_MARKER to "false"),
        )) {
            try {
                M1StartupHarnessGates.assertSetupSentinels("Typical", mutated, "Typical")
            } catch (expected: IllegalStateException) {
                continue
            }
            fail("broken fixture evidence must be refused: $mutated")
        }
    }

    // ------------------------------------------------------------------
    // Run-manifest model: strict add + exact published shape
    // ------------------------------------------------------------------

    private fun newManifest(): M1StartupRunManifest =
        M1StartupRunManifest(
            environment = M1StartupEnvironment(
                brand = "xiaomi",
                model = "M1",
                device = "device",
                fingerprint = "f/1",
                sdkInt = 34,
                hardware = "qcom",
                cpuAbi = "arm64-v8a",
                totalRamMb = 16384,
                thermalStatus = "0",
                batteryLevel = 80,
                displayPeak = "120",
                displayMin = "null",
            ),
            identity = M1StartupIdentity(
                targetPackage = M1StartupHarnessGates.TARGET_PACKAGE,
                testPackage = M1StartupHarnessGates.TEST_PACKAGE,
                targetActivity = M1StartupHarnessGates.TARGET_ACTIVITY,
                setupComponent = M1StartupHarnessGates.SETUP_COMPONENT,
                variantMarker = "true",
                signingCertSha256 = "b".repeat(64),
                dataDir = "/data/user/0/${M1StartupHarnessGates.TARGET_PACKAGE}",
            ),
            suite = "M1StartupBenchmark",
            fixtureVersion = "m1-startup-v1",
            publicationDir = createTempDir(prefix = "m1-selfcheck"),
        )

    @Test
    fun manifestPublishesTheExactFrozenShape() {
        val manifest = newManifest()
        manifest.logCommand(
            "pm clear ${M1StartupHarnessGates.TARGET_PACKAGE}",
            M1StartupHarnessGates.TARGET_PACKAGE,
        )
        manifest.addBlock("startup01Fresh", "Fresh", "a".repeat(64), ttfdRequired = true)
        manifest.addIteration("startup01Fresh", 0, thermalBefore = 0, thermalAfter = 1, pid = "1234")
        manifest.finish()
        val published = File(manifest.publicationDir, M1StartupRunManifest.MANIFEST_FILE)
        assertTrue(published.isFile)
        val root = JSONObject(published.readText())
        assertEquals("M1StartupBenchmark", root.getString("suite"))
        assertEquals("m1-startup-v1", root.getString("fixtureVersion"))
        assertEquals(34, root.getJSONObject("environment").getInt("sdkInt"))
        assertEquals(1, root.getJSONArray("commandLog").length())
        assertEquals(true, root.getJSONArray("commandLog").getJSONObject(0).getBoolean("allowed"))
        val block = root.getJSONArray("blocks").getJSONObject(0)
        assertEquals("startup01Fresh", block.getString("block"))
        assertEquals("Fresh", block.getString("fixture"))
        assertEquals(30, block.getInt("iterations"))
        assertEquals(5, block.getInt("warmups"))
        assertEquals(true, block.getBoolean("ttfdRequired"))
        val iteration = root.getJSONArray("iterations").getJSONObject(0)
        assertEquals("startup01Fresh", iteration.getString("block"))
        assertEquals(0, iteration.getInt("iteration"))
        assertEquals("Fresh", iteration.getString("fixture"))
        assertEquals("a".repeat(64), iteration.getString("fixtureSha256"))
        assertEquals("1234", iteration.getString("pid"))
        assertEquals(false, iteration.getBoolean("exitInfoUnexpected"))
        assertEquals(true, iteration.getBoolean("fixtureValidated"))
        // Atomic publication leaves no temp file behind.
        assertTrue(!File(manifest.publicationDir, M1StartupRunManifest.MANIFEST_FILE_TEMP).exists())
    }

    @Test
    fun manifestRejectsInvalidIterationsAndCommands() {
        for (block in listOf<() -> Unit>(
            { duplicateIteration() },
            { outOfRangeIteration() },
            { thermalBeyondLight() },
            { blankPid() },
            { unregisteredBlock() },
            { productionCommandTarget() },
            { disallowedCommand() },
        )) {
            try {
                block()
            } catch (expected: RuntimeException) {
                continue
            }
            fail("the manifest must refuse the invalid record or command")
        }
    }

    private fun duplicateIteration() {
        val manifest = newManifest()
        manifest.addBlock("startup01Fresh", "Fresh", "a".repeat(64), ttfdRequired = true)
        manifest.addIteration("startup01Fresh", 3, 0, 0, "1234")
        manifest.addIteration("startup01Fresh", 3, 0, 0, "1234")
    }

    private fun outOfRangeIteration() {
        val manifest = newManifest()
        manifest.addBlock("startup01Fresh", "Fresh", "a".repeat(64), ttfdRequired = true)
        manifest.addIteration("startup01Fresh", 30, 0, 0, "1234")
    }

    private fun thermalBeyondLight() {
        val manifest = newManifest()
        manifest.addBlock("startup01Fresh", "Fresh", "a".repeat(64), ttfdRequired = true)
        manifest.addIteration("startup01Fresh", 0, thermalBefore = 0, thermalAfter = 2, pid = "1234")
    }

    private fun blankPid() {
        val manifest = newManifest()
        manifest.addBlock("startup01Fresh", "Fresh", "a".repeat(64), ttfdRequired = true)
        manifest.addIteration("startup01Fresh", 0, 0, 0, " ")
    }

    private fun unregisteredBlock() {
        val manifest = newManifest()
        manifest.addIteration("startup99Wrong", 0, 0, 0, "1234")
    }

    private fun productionCommandTarget() {
        val manifest = newManifest()
        manifest.logCommand("dumpsys thermalservice", "com.paherdin.record")
    }

    private fun disallowedCommand() {
        val manifest = newManifest()
        manifest.logCommand("pm clear com.paherdin.record", M1StartupHarnessGates.TARGET_PACKAGE)
    }

    // ------------------------------------------------------------------
    // Nearest-rank p95 gate (the frozen rank 29 of 30)
    // ------------------------------------------------------------------

    @Test
    fun nearestRankP95IsRankTwentyNineOfThirty() {
        val sorted = List(29) { 300.0 } + 1_000.0
        assertEquals(300.0, M1StartupReportGate.nearestRankPercentile(sorted, 95), 0.0001)
        val allAtBudget = List(30) { 1_001.0 }
        assertEquals(1_001.0, M1StartupReportGate.nearestRankPercentile(allAtBudget, 95), 0.0001)
        try {
            M1StartupReportGate.nearestRankPercentile(emptyList(), 95)
        } catch (expected: IllegalArgumentException) {
            return
        }
        fail("a percentile of an empty sample set must be refused")
    }
}

/**
 * Device mirror of the host validator's nearest-rank p95 gate; T10's device
 * self-check exercises it, the host validator recomputes the same value.
 */
internal object M1StartupReportGate {
    fun nearestRankPercentile(sorted: List<Double>, percentile: Int): Double {
        require(sorted.isNotEmpty()) { "percentile of an empty sample set" }
        val rank = kotlin.math.ceil(percentile / 100.0 * sorted.size).toInt()
        return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
    }
}
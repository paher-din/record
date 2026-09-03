package com.paherdin.record.benchmark

import org.json.JSONObject

/**
 * M1-T09 Step 4 — harness identity gates for the startup suite (plan Task
 * M1-T09 Step 4). Every device action of `M1StartupBenchmark` runs through
 * these gates:
 *
 *  - the target package must be exactly the benchmark variant
 *    `com.paherdin.record.benchmark` (never the production application id),
 *  - every shell command is allow-listed: any occurrence of the production
 *    `com.paherdin.record` namespace whose complete token is not exactly the
 *    benchmark target package, the benchmark test package, or the frozen
 *    target activity class is refused (so `pm clear com.paherdin.record`
 *    against production data is unreachable by construction),
 *  - the installed benchmark variant must be present, non-debuggable, and
 *    carry the `m1BenchmarkVariant` marker, and
 *  - the fixture setup sentinels must prove the exact published fixture
 *    (version, digest, validated state) before any measurement.
 *
 * The gates are the device mirror of the host validator's identity/command-log
 * contract; both pin the same frozen constants.
 */
internal object M1StartupHarnessGates {

    /** The exact benchmark-variant target package (applicationIdSuffix ".benchmark"). */
    const val TARGET_PACKAGE = "com.paherdin.record.benchmark"

    /** The exact benchmark test (instrumentation) package. */
    const val TEST_PACKAGE = "com.paherdin.record.benchmark.test"

    /** The frozen target Activity of the benchmark variant app. */
    const val TARGET_ACTIVITY = "com.paherdin.record.MainActivity"

    /** The frozen exported fixture setup component. */
    const val SETUP_COMPONENT = "com.paherdin.record.benchmark.M1StartupFixtureActivity"

    /** The merged-manifest variant marker key the setup surface requires. */
    const val VARIANT_METADATA_KEY = "m1BenchmarkVariant"

    /** The sentinel mirror file the fixture Activity publishes. */
    const val SENTINEL_FILE_NAME = "m1_startup_sentinels.json"

    /** The only package tokens any logged/issued command may carry. */
    val ALLOWED_PACKAGE_TOKENS = setOf(TARGET_PACKAGE, TEST_PACKAGE, TARGET_ACTIVITY)

    /** NONE=0, LIGHT=1, MODERATE=2, ... — same ordinals as ThermalStatus. */
    const val LIGHT_STATUS = 1

    /** Refuses any target package but the exact benchmark variant package. */
    fun requireExactTargetPackage(actual: String) {
        check(actual == TARGET_PACKAGE) {
            "startup benchmark target package '$actual' != exact benchmark variant " +
                "'$TARGET_PACKAGE'"
        }
    }

    /** Refuses any test package but the exact benchmark test package. */
    fun requireExactTestPackage(actual: String) {
        check(actual == TEST_PACKAGE) {
            "startup benchmark test package '$actual' != exact '$TEST_PACKAGE'"
        }
    }

    /**
     * True when every package token in [command] is exactly one of
     * [ALLOWED_PACKAGE_TOKENS]. Any residual occurrence of the production
     * `com.paherdin.record` namespace outside those complete tokens (for
     * example `pm clear com.paherdin.record`) is refused.
     */
    fun assertCommandAllowed(command: String) {
        var text = command
        // Strip every allowed complete token; a residual production-prefix
        // fragment means the command carried an out-of-allowlist package token.
        for (token in ALLOWED_PACKAGE_TOKENS) {
            text = text.replace(token, " ")
        }
        check(!text.contains("com.paherdin.record")) {
            "command carries a package token outside the benchmark allowlist: $command"
        }
    }

    /**
     * Requires the installed target to be non-debuggable: its
     * `dumpsys package` flags must not carry the DEBUGGABLE bit (the benchmark
     * build type initializes from release and disables debuggability).
     */
    fun assertNonDebuggable(dumpsysPackage: String) {
        val flagsLine = dumpsysPackage.lineSequence()
            .firstOrNull { it.contains("pkgFlags") || it.contains("flags=[") }
            ?: throw AssertionError("no package flags line in dumpsys package output")
        check(!flagsLine.contains("DEBUGGABLE")) {
            "benchmark variant must not be debuggable: $flagsLine"
        }
    }

    /** Requires the fixture Activity's published variant marker. */
    fun assertVariantMarker(marker: String?) {
        check(marker == "true") {
            "published variant marker '$marker' != 'true' (not the benchmark-variant app)"
        }
    }

    /** Requires the exact benchmark-variant data dir. */
    fun assertDataDir(dataDir: String?) {
        check(dataDir == "/data/user/0/$TARGET_PACKAGE") {
            "published data dir '$dataDir' != '/data/user/0/$TARGET_PACKAGE'"
        }
    }

    /**
     * The fixture Activity's setup sentinels, exactly as the setup surface
     * publishes them: exact package name and variant marker, a 64-hex signing
     * cert digest, the fixture version and content digest, a validated fixture,
     * and `setupDone`.
     */
    fun assertSetupSentinels(
        fixture: String,
        sentinels: Map<String, String?>,
        expectedFixture: String,
    ): String {
        check(sentinels[M1StartupSentinelKeys.PACKAGE_NAME] == TARGET_PACKAGE) {
            "setup package name '${sentinels[M1StartupSentinelKeys.PACKAGE_NAME]}' != $TARGET_PACKAGE"
        }
        assertVariantMarker(sentinels[M1StartupSentinelKeys.VARIANT_MARKER])
        val cert = sentinels[M1StartupSentinelKeys.SIGNING_CERT_SHA256]
        check(cert != null && Regex("^[0-9a-f]{64}$").matches(cert)) {
            "published signing cert digest must be 64 lowercase hex, got '$cert'"
        }
        assertDataDir(sentinels[M1StartupSentinelKeys.DATA_ROOT])
        check(sentinels[M1StartupSentinelKeys.FIXTURE] == expectedFixture) {
            "setup fixture '${sentinels[M1StartupSentinelKeys.FIXTURE]}' != expected $expectedFixture"
        }
        val version = sentinels[M1StartupSentinelKeys.FIXTURE_VERSION]
        check(version == "m1-startup-v1") {
            "setup fixture version '$version' != m1-startup-v1"
        }
        val digest = sentinels[M1StartupSentinelKeys.FIXTURE_CONTENT_DIGEST]
        check(digest != null && Regex("^[0-9a-f]{64}$").matches(digest)) {
            "published fixture content digest must be 64 lowercase hex, got '$digest'"
        }
        check(sentinels[M1StartupSentinelKeys.FIXTURE_VALIDATED] == "true") {
            "the published fixture is not validated (fixtureValidated=" +
                sentinels[M1StartupSentinelKeys.FIXTURE_VALIDATED] + ")"
        }
        check(sentinels[M1StartupSentinelKeys.SETUP_DONE] == "true") {
            "fixture setup did not complete (setupDone=" +
                sentinels[M1StartupSentinelKeys.SETUP_DONE] + ")"
        }
        return digest
    }

    /** Parses the `Thermal Status: N` line of `dumpsys thermalservice`; null when absent. */
    fun thermalStatusFromDumpsys(dumpsys: String): Int? =
        Regex("Thermal Status:\\s*(\\d+)").find(dumpsys)?.groupValues?.get(1)?.toIntOrNull()

    fun assertThermalWithinGate(status: Int) {
        check(status <= LIGHT_STATUS) {
            "thermal status $status (>= MODERATE) invalidates the whole suite"
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

    /** Counts exit-info entries by their `reason=NN (NAME)` label. */
    fun exitInfoReasonCounts(text: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        Regex("(?<![a-zA-Z])reason=\\d+ \\(([^)]+)\\)").findAll(text).forEach { m ->
            counts.merge(m.groupValues[1], 1, Int::plus)
        }
        return counts
    }
}

/**
 * The frozen sentinel keys the fixture Activity publishes; the harness reads
 * them through the shell-readable mirror file only.
 */
internal object M1StartupSentinelKeys {
    const val MODE = "mode"
    const val RUN_ID = "runId"
    const val ERROR = "error"
    const val SETUP_DONE = "setupDone"
    const val PACKAGE_NAME = "packageName"
    const val VARIANT_MARKER = "variantMarker"
    const val SIGNING_CERT_SHA256 = "signingCertSha256"
    const val DATA_ROOT = "dataRoot"
    const val SETUP_TOKEN_DIGEST = "setupTokenDigest"
    const val FIXTURE = "fixture"
    const val FIXTURE_VERSION = "fixtureVersion"
    const val FIXTURE_CONTENT_DIGEST = "fixtureContentDigest"
    const val RECORD_COUNTS = "recordCounts"
    const val ACTIVATION_RECORD_SHA256 = "activationRecordSha256"
    const val SNAPSHOT_SHA256 = "snapshotSha256"
    const val GENERATION_IDS = "generationIds"
    const val FIXTURE_VALIDATED = "fixtureValidated"
}

/**
 * Cached reader over the fixture Activity's sentinel mirror file
 * (`/sdcard/Android/media/<targetPackage>/m1_startup_sentinels.json`, read
 * through `cat` — UiAutomation runs with shell privileges). Mirrors the P6
 * `TimelineSentinelReader` shape.
 */
internal class M1StartupSentinelReader(
    private val shell: (String) -> String,
    private val targetPackage: String = M1StartupHarnessGates.TARGET_PACKAGE,
) {
    private var cache: Map<String, String> = emptyMap()
    private var lastReadNanos = 0L

    /** The mirror file's shell-readable path. */
    fun filePath(): String =
        "/sdcard/Android/media/$targetPackage/${M1StartupHarnessGates.SENTINEL_FILE_NAME}"

    /** `getString(key, default)` — same shape as the P6 reader call sites. */
    fun getString(key: String, default: String? = null): String? = readAll()[key] ?: default

    fun readAll(): Map<String, String> {
        val now = System.nanoTime()
        if (now - lastReadNanos < CACHE_TTL_NANOS) return cache
        lastReadNanos = now
        cache = readFrom()
        return cache
    }

    private fun readFrom(): Map<String, String> {
        val raw = shell("cat ${filePath()} 2>/dev/null").trim()
        if (raw.isEmpty()) return emptyMap()
        return try {
            val root = JSONObject(raw)
            val out = HashMap<String, String>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = root.getString(key)
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private companion object {
        const val CACHE_TTL_NANOS = 150_000_000L // 150 ms
    }
}
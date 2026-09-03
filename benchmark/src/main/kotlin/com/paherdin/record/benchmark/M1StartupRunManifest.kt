package com.paherdin.record.benchmark

import androidx.benchmark.Outputs
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * M1-T09 Step 4 — run-manifest model for the startup suite (plan Task M1-T09
 * Step 5). Owns atomic JSON publication of the `m1-run-manifest.json` control
 * manifest under the resolved AndroidX 1.4.1 `Outputs.outputDirectory`, beside
 * the Macrobenchmark reports and Perfetto traces that the host validator
 * (`:core:data:validateM1StartupReports`) reads.
 *
 * The exact frozen shape (host contract in
 * `M1StartupReportParser.parseRunManifest`):
 *
 * ```
 * {
 *   "suite": "M1StartupBenchmark",
 *   "fixtureVersion": "m1-startup-v1",
 *   "environment": {brand, model, device, fingerprint, sdkInt, hardware,
 *                   cpuAbi, totalRamMb, thermalStatus, batteryLevel,
 *                   displayPeak, displayMin},
 *   "identity": {targetPackage, testPackage, targetActivity, setupComponent,
 *                variantMarker, signingCertSha256, dataDir},
 *   "blocks": [ {block, fixture, fixtureSha256, iterations, warmups,
 *                ttfdRequired}, ... ],                    // exactly 8
 *   "commandLog": [ {command, target, allowed}, ... ],    // nonempty
 *   "iterations": [ {block, iteration, fixture, fixtureSha256, thermalBefore,
 *                    thermalAfter, pid, exitInfoUnexpected, fixtureValidated},
 *                   ... ]                                  // exactly 240
 * }
 * ```
 *
 * `addIteration` is strict and fails closed: duplicate or out-of-range
 * `(block, iteration)`, thermal beyond LIGHT, a blank pid, and an unregistered
 * block are rejected, so an invalid iteration surfaces immediately inside the
 * measured block. `finish` publishes atomically: the full JSON is written to a
 * temp file in the publication directory and renamed over the target, so a
 * crash mid-write can never leave a partial manifest. Serialization uses
 * `org.json` (supplied by Android; no new dependency).
 */
internal class M1StartupRunManifest internal constructor(
    private val environment: M1StartupEnvironment,
    private val identity: M1StartupIdentity,
    private val suite: String,
    private val fixtureVersion: String,
    internal val publicationDir: File,
) {

    /** Frozen shape: publication under the resolved `Outputs.outputDirectory`. */
    constructor(
        environment: M1StartupEnvironment,
        identity: M1StartupIdentity,
    ) : this(environment, identity, SUITE_NAME, FIXTURE_VERSION, Outputs.outputDirectory)

    private data class BlockRow(
        val block: String,
        val fixture: String,
        val fixtureSha256: String,
        val ttfdRequired: Boolean,
    )

    private data class IterationRecord(
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

    private data class CommandEntry(val command: String, val target: String)

    private val blocks = LinkedHashMap<String, BlockRow>()
    private val iterations = ArrayList<IterationRecord>()
    private val seen = HashSet<Pair<String, Int>>()
    private val commandLog = ArrayList<CommandEntry>()
    private var finished = false

    /** Registers one frozen block row before its iterations run (single-shot). */
    fun addBlock(block: String, fixture: String, fixtureSha256: String, ttfdRequired: Boolean) {
        require(!finished) { "M1StartupRunManifest already finished; cannot add block $block" }
        require(!blocks.containsKey(block)) { "duplicate block row $block" }
        require(Regex("^[0-9a-f]{64}$").matches(fixtureSha256)) {
            "block $block fixtureSha256 must be 64 lowercase hex"
        }
        blocks[block] = BlockRow(block, fixture, fixtureSha256, ttfdRequired)
    }

    /** Appends one logged shell command (target = the addressed package). */
    fun logCommand(command: String, target: String) {
        require(!finished) { "M1StartupRunManifest already finished; cannot log commands" }
        require(command.isNotBlank()) { "logged command must be nonblank" }
        M1StartupHarnessGates.assertCommandAllowed(command)
        require(target == M1StartupHarnessGates.TARGET_PACKAGE ||
            target == M1StartupHarnessGates.TEST_PACKAGE) {
            "logged command target '$target' is not the benchmark variant or its test package"
        }
        commandLog.add(CommandEntry(command, target))
    }

    /**
     * Appends one measured iteration's control record, in call order, strictly:
     * the block must be a registered row, the iteration must be in
     * `0 until ITERATIONS_PER_BLOCK`, the fixture name/digest must match the
     * block row, the thermal status must stay within LIGHT, and the pid must
     * be nonblank. `exitInfoUnexpected` is always false here — the harness
     * gate fails the iteration before a record exists.
     */
    fun addIteration(
        block: String,
        iteration: Int,
        thermalBefore: Int,
        thermalAfter: Int,
        pid: String,
    ) {
        require(!finished) { "M1StartupRunManifest already finished; cannot add $block#$iteration" }
        val row = requireNotNull(blocks[block]) { "iteration for unregistered block $block" }
        require(iteration in 0 until ITERATIONS_PER_BLOCK) {
            "iteration $block#$iteration outside 0..${ITERATIONS_PER_BLOCK - 1}"
        }
        require(seen.add(block to iteration)) {
            "duplicate iteration record $block#$iteration"
        }
        require(thermalBefore <= M1StartupHarnessGates.LIGHT_STATUS &&
            thermalAfter <= M1StartupHarnessGates.LIGHT_STATUS) {
            "iteration $block#$iteration thermal $thermalBefore/$thermalAfter exceeds LIGHT"
        }
        require(pid.isNotBlank()) { "iteration $block#$iteration has a blank PID" }
        iterations.add(
            IterationRecord(
                block = block,
                iteration = iteration,
                fixture = row.fixture,
                fixtureSha256 = row.fixtureSha256,
                thermalBefore = thermalBefore,
                thermalAfter = thermalAfter,
                pid = pid,
                exitInfoUnexpected = false,
                fixtureValidated = true,
            ),
        )
    }

    /**
     * Publishes `m1-run-manifest.json` atomically under [publicationDir]:
     * full JSON to a temp file, then rename over the target. Single-shot.
     */
    fun finish() {
        check(!finished) { "M1StartupRunManifest already finished" }
        finished = true
        publicationDir.mkdirs()
        check(publicationDir.isDirectory) { "publication dir is not a directory: $publicationDir" }
        val target = File(publicationDir, MANIFEST_FILE)
        val temp = File(publicationDir, MANIFEST_FILE_TEMP)
        temp.writeText(buildJson())
        check(temp.renameTo(target)) {
            "atomic rename ${temp.name} -> ${target.name} failed in $publicationDir"
        }
    }

    private fun buildJson(): String {
        val root = JSONObject()
        root.put("suite", suite)
        root.put("fixtureVersion", fixtureVersion)
        root.put(
            "environment",
            JSONObject()
                .put("brand", environment.brand)
                .put("model", environment.model)
                .put("device", environment.device)
                .put("fingerprint", environment.fingerprint)
                .put("sdkInt", environment.sdkInt)
                .put("hardware", environment.hardware)
                .put("cpuAbi", environment.cpuAbi)
                .put("totalRamMb", environment.totalRamMb)
                .put("thermalStatus", environment.thermalStatus)
                .put("batteryLevel", environment.batteryLevel)
                .put("displayPeak", environment.displayPeak)
                .put("displayMin", environment.displayMin),
        )
        root.put(
            "identity",
            JSONObject()
                .put("targetPackage", identity.targetPackage)
                .put("testPackage", identity.testPackage)
                .put("targetActivity", identity.targetActivity)
                .put("setupComponent", identity.setupComponent)
                .put("variantMarker", identity.variantMarker)
                .put("signingCertSha256", identity.signingCertSha256)
                .put("dataDir", identity.dataDir),
        )
        val blocksJson = JSONArray()
        for (row in blocks.values) {
            blocksJson.put(
                JSONObject()
                    .put("block", row.block)
                    .put("fixture", row.fixture)
                    .put("fixtureSha256", row.fixtureSha256)
                    .put("iterations", ITERATIONS_PER_BLOCK)
                    .put("warmups", WARMUPS_PER_BLOCK)
                    .put("ttfdRequired", row.ttfdRequired),
            )
        }
        root.put("blocks", blocksJson)
        val commandLogJson = JSONArray()
        for (entry in commandLog) {
            commandLogJson.put(
                JSONObject()
                    .put("command", entry.command)
                    .put("target", entry.target)
                    .put("allowed", true),
            )
        }
        root.put("commandLog", commandLogJson)
        val iterationsJson = JSONArray()
        for (record in iterations) {
            iterationsJson.put(
                JSONObject()
                    .put("block", record.block)
                    .put("iteration", record.iteration)
                    .put("fixture", record.fixture)
                    .put("fixtureSha256", record.fixtureSha256)
                    .put("thermalBefore", record.thermalBefore)
                    .put("thermalAfter", record.thermalAfter)
                    .put("pid", record.pid)
                    .put("exitInfoUnexpected", record.exitInfoUnexpected)
                    .put("fixtureValidated", record.fixtureValidated),
            )
        }
        root.put("iterations", iterationsJson)
        return root.toString()
    }

    companion object {
        /** Exact filename the host validator requires (CONTRACT.MANIFEST_FILE). */
        const val MANIFEST_FILE = "m1-run-manifest.json"
        const val MANIFEST_FILE_TEMP = "m1-run-manifest.json.tmp"
        const val SUITE_NAME = "M1StartupBenchmark"
        const val FIXTURE_VERSION = "m1-startup-v1"

        /** The frozen per-block cardinalities (spec 11.3). */
        const val ITERATIONS_PER_BLOCK = 30
        const val WARMUPS_PER_BLOCK = 5
    }
}

/** The suite-level environment snapshot of the run manifest. */
internal data class M1StartupEnvironment(
    val brand: String,
    val model: String,
    val device: String,
    val fingerprint: String,
    val sdkInt: Int,
    val hardware: String,
    val cpuAbi: String,
    val totalRamMb: Int,
    val thermalStatus: String,
    val batteryLevel: Int,
    val displayPeak: String,
    val displayMin: String,
)

/** The suite-level harness identity of the run manifest. */
internal data class M1StartupIdentity(
    val targetPackage: String,
    val testPackage: String,
    val targetActivity: String,
    val setupComponent: String,
    val variantMarker: String,
    val signingCertSha256: String,
    val dataDir: String,
)
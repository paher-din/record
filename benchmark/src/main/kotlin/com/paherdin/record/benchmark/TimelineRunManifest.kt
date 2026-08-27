package com.paherdin.record.benchmark

import androidx.benchmark.Outputs
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tech PoC P6 Task 6R4-B — run-manifest model (plan Task 6R4-B Step 3). Owns
 * atomic JSON publication of the Rev4 control manifest under the resolved
 * AndroidX 1.4.1 `Outputs.outputDirectory` (beside the Macrobenchmark reports
 * and Perfetto traces that the host validator reads).
 *
 * The exact frozen shape (host contract in
 * `TimelineBenchmarkReportParser.parseRunManifest`):
 *
 * ```
 * {
 *   "suite": "TimelineBenchmark",
 *   "displaySettingsBefore": {"peak":"120","min":"null"},
 *   "displaySettingsAfter":  {"peak":"120","min":"null"},
 *   "controlTraversal": {selectedDayDrawCount:1000, replacementCount:999,
 *                        maxPublishedItemCount:11, prefetchQueryCount:0,
 *                        firstDate:"2025-09-26", lastDate:"2023-01-01"},
 *   "iterations": [ {block, iteration, refreshBeforeHz, refreshAfterHz,
 *                    thermalBefore, thermalAfter, pidBefore, pidAfter,
 *                    selectedDayDrawCount, replacementCount,
 *                    maxPublishedItemCount, prefetchQueryCount,
 *                    firstDate, lastDate}, ... ]   // exactly 35, ordered
 * }
 * ```
 *
 * `gestureCount` no longer exists (Rev4 has no gesture/scroll workload). Load
 * records require every traversal field JSON `null`; prepared replacement
 * records require exactly the frozen 1000/999/11/0/2025-09-26/2023-01-01
 * summary. The suite-level `controlTraversal` records the one unmeasured
 * replacement traversal control and must carry the same exact values.
 *
 * `add` is strict and fails closed: duplicate `(block, iteration)`, non-finite
 * or non-positive refresh, blank or changing PIDs, and traversal-field
 * mismatches are rejected, so an invalid iteration surfaces immediately inside
 * the measured block.
 *
 * `finish` publishes atomically: the full JSON is written to a temp file in
 * the publication directory and renamed over the target, so a crash mid-write
 * can never leave a partial manifest; a later `finish` fully replaces the
 * earlier content. Serialization uses `org.json` (supplied by Android; no new
 * dependency). Perfetto filenames are validated from Macrobenchmark reports on
 * the host and are intentionally absent here.
 *
 * The primary constructor (frozen in the plan) resolves the publication
 * directory from `Outputs.outputDirectory`; the internal constructor is a
 * hermetic test seam used only by `TimelineRunManifestTest`.
 */

/**
 * The frozen pure Rev4 selected-day replacement traversal summary. Both the
 * unmeasured control traversal and every valid measured replacement iteration
 * report exactly [FROZEN].
 */
internal data class SelectedDayTraversalSummary(
    val selectedDayDrawCount: Int,
    val replacementCount: Int,
    val maxPublishedItemCount: Int,
    val prefetchQueryCount: Int,
    val firstDate: String,
    val lastDate: String,
) {
    companion object {
        /** Exact frozen traversal control (spec §4.5 / plan Task 6R4-B Step 3). */
        val FROZEN = SelectedDayTraversalSummary(
            selectedDayDrawCount = 1_000,
            replacementCount = 999,
            maxPublishedItemCount = 11,
            prefetchQueryCount = 0,
            firstDate = "2025-09-26",
            lastDate = "2023-01-01",
        )
    }
}

/** One measured iteration's control record; traversal fields are nullable. */
internal data class TimelineIterationControl(
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
) {
    /** True when every traversal field is null. */
    fun traversalAllNull(): Boolean =
        selectedDayDrawCount == null && replacementCount == null &&
            maxPublishedItemCount == null && prefetchQueryCount == null &&
            firstDate == null && lastDate == null

    /** The lifted traversal summary, or null when every traversal field is null. */
    fun traversalSummaryOrNull(): SelectedDayTraversalSummary? {
        if (selectedDayDrawCount == null && replacementCount == null &&
            maxPublishedItemCount == null && prefetchQueryCount == null &&
            firstDate == null && lastDate == null
        ) {
            return null
        }
        return SelectedDayTraversalSummary(
            selectedDayDrawCount = selectedDayDrawCount ?: return null,
            replacementCount = replacementCount ?: return null,
            maxPublishedItemCount = maxPublishedItemCount ?: return null,
            prefetchQueryCount = prefetchQueryCount ?: return null,
            firstDate = firstDate ?: return null,
            lastDate = lastDate ?: return null,
        )
    }
}

internal class TimelineRunManifest internal constructor(
    val displaySettingsBefore: TimelineDisplaySettings,
    internal val publicationDir: File,
) {

    /** Frozen plan shape: publication under the resolved `Outputs.outputDirectory`. */
    constructor(displaySettingsBefore: TimelineDisplaySettings) :
        this(displaySettingsBefore, Outputs.outputDirectory)

    private val controls = ArrayList<TimelineIterationControl>()
    private val seen = HashSet<Pair<String, Int>>()
    private var finished = false
    private var controlTraversal: SelectedDayTraversalSummary? = null

    /** Appends one measured iteration's control record, in call order. */
    fun add(control: TimelineIterationControl) {
        require(!finished) { "TimelineRunManifest already finished; cannot add ${control.block}#${control.iteration}" }
        require(seen.add(control.block to control.iteration)) {
            "duplicate iteration record ${control.block}#${control.iteration}"
        }
        require(control.refreshBeforeHz.isFinite() && control.refreshBeforeHz > 0.0) {
            "iteration ${control.block}#${control.iteration} refreshBeforeHz=" +
                "${control.refreshBeforeHz} must be positive and finite"
        }
        require(control.refreshAfterHz.isFinite() && control.refreshAfterHz > 0.0) {
            "iteration ${control.block}#${control.iteration} refreshAfterHz=" +
                "${control.refreshAfterHz} must be positive and finite"
        }
        require(control.pidBefore.isNotBlank() && control.pidAfter == control.pidBefore) {
            "iteration ${control.block}#${control.iteration} PID '${control.pidBefore}' -> " +
                "'${control.pidAfter}' must be identical and nonblank (process death/relaunch)"
        }
        requireValidTraversal(control)
        controls.add(control)
    }

    /** Records the one unmeasured control replacement traversal (single-shot). */
    fun setControlTraversal(summary: SelectedDayTraversalSummary) {
        require(!finished) { "TimelineRunManifest already finished; cannot set control traversal" }
        check(controlTraversal == null) { "control traversal already set" }
        controlTraversal = summary
    }

    /**
     * Publishes `p6-run-manifest.json` atomically under [publicationDir]:
     * full JSON to a temp file, then rename over the target. Single-shot.
     */
    fun finish(displaySettingsAfter: TimelineDisplaySettings) {
        check(!finished) { "TimelineRunManifest already finished" }
        finished = true
        publicationDir.mkdirs()
        check(publicationDir.isDirectory) { "publication dir is not a directory: $publicationDir" }
        val target = File(publicationDir, MANIFEST_FILE)
        val temp = File(publicationDir, MANIFEST_FILE_TEMP)
        temp.writeText(buildJson(displaySettingsAfter))
        check(temp.renameTo(target)) {
            "atomic rename ${temp.name} -> ${target.name} failed in $publicationDir"
        }
    }

    private fun requireValidTraversal(control: TimelineIterationControl) {
        val summary = control.traversalSummaryOrNull()
        when (control.block) {
            in LOAD_BLOCKS -> require(control.traversalAllNull()) {
                "load iteration ${control.block}#${control.iteration} must have all traversal " +
                    "fields null, found $summary"
            }
            in REPLACEMENT_BLOCKS -> require(summary == SelectedDayTraversalSummary.FROZEN) {
                "replacement iteration ${control.block}#${control.iteration} must report the exact " +
                    "frozen traversal summary 1000/999/11/0/2025-09-26/2023-01-01, found $summary"
            }
        }
    }

    private fun buildJson(displaySettingsAfter: TimelineDisplaySettings): String {
        val root = JSONObject()
        root.put("suite", SUITE_NAME)
        root.put(
            "displaySettingsBefore",
            JSONObject()
                .put("peak", displaySettingsBefore.peak)
                .put("min", displaySettingsBefore.min),
        )
        root.put(
            "displaySettingsAfter",
            JSONObject()
                .put("peak", displaySettingsAfter.peak)
                .put("min", displaySettingsAfter.min),
        )
        controlTraversal?.let { summary ->
            root.put(
                "controlTraversal",
                JSONObject()
                    .put("selectedDayDrawCount", summary.selectedDayDrawCount)
                    .put("replacementCount", summary.replacementCount)
                    .put("maxPublishedItemCount", summary.maxPublishedItemCount)
                    .put("prefetchQueryCount", summary.prefetchQueryCount)
                    .put("firstDate", summary.firstDate)
                    .put("lastDate", summary.lastDate),
            )
        }
        val iterations = JSONArray()
        for (control in controls) {
            iterations.put(
                JSONObject()
                    .put("block", control.block)
                    .put("iteration", control.iteration)
                    .put("refreshBeforeHz", control.refreshBeforeHz)
                    .put("refreshAfterHz", control.refreshAfterHz)
                    .put("thermalBefore", control.thermalBefore)
                    .put("thermalAfter", control.thermalAfter)
                    .put("pidBefore", control.pidBefore)
                    .put("pidAfter", control.pidAfter)
                    .put("selectedDayDrawCount", intOrNull(control.selectedDayDrawCount))
                    .put("replacementCount", intOrNull(control.replacementCount))
                    .put("maxPublishedItemCount", intOrNull(control.maxPublishedItemCount))
                    .put("prefetchQueryCount", intOrNull(control.prefetchQueryCount))
                    .put("firstDate", strOrNull(control.firstDate))
                    .put("lastDate", strOrNull(control.lastDate)),
            )
        }
        root.put("iterations", iterations)
        return root.toString()
    }

    private fun intOrNull(value: Int?): Any = value ?: JSONObject.NULL

    private fun strOrNull(value: String?): Any = value ?: JSONObject.NULL

    companion object {
        /** Exact filename the host validator requires (CONTRACT.MANIFEST_FILE). */
        const val MANIFEST_FILE = "p6-run-manifest.json"
        const val MANIFEST_FILE_TEMP = "p6-run-manifest.json.tmp"
        const val SUITE_NAME = "TimelineBenchmark"

        /**
         * Frozen four-block classification for traversal-field validation (final
         * Rev4 binding names, Task 6R4-C).
         */
        val LOAD_BLOCKS: Set<String> = setOf("binding01LoadA", "binding04LoadB")
        val REPLACEMENT_BLOCKS: Set<String> = setOf("binding02ReplaceA", "binding03ReplaceB")
    }
}
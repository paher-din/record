package com.paherdin.record.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tech PoC P6 Task 6R4-B Step 3 — device tests for the run-manifest model
 * (`TimelineRunManifest`, plan Task 6R4-B). These run under
 * `connectedBenchmarkAndroidTest` because serialization uses `org.json`, which
 * on the JVM is the un-mocked Android stub; on the device it is the real
 * library. They are hermetic: each test writes into its own directory under
 * the test package's cache dir and removes it in `@After`, so nothing reaches
 * the Macrobenchmark additional-output root.
 *
 * The frozen Rev4 contract under test:
 * - ordered append: the `iterations` JSON array preserves block-major
 *   insertion order (15/3/2/15 = exactly 35 records);
 * - duplicate `(block, iteration)` pairs are rejected;
 * - non-finite or non-positive refresh observations are rejected;
 * - blank or changing PIDs are rejected;
 * - `gestureCount` no longer exists: load records carry JSON `null` for all six
 *   traversal fields, prepared replacement records carry exactly the frozen
 *   summary (1000/999/11/0/2025-09-26/2023-01-01); a load record with traversal
 *   fields or a replacement record with any other summary is rejected;
 * - the suite-level `controlTraversal` (the one unmeasured replacement
 *   traversal control) is serialized with the same exact frozen values;
 * - `finish` publishes atomically (temp + rename) and a later manifest fully
 *   replaces an earlier one;
 * - the final JSON has the exact Rev4 shape: suite-level
 *   `displaySettingsBefore`/`displaySettingsAfter` (`peak`=`120`,
 *   `min`=`null` strings) and exactly 35 iteration objects with exactly the
 *   fourteen frozen fields.
 */
@RunWith(AndroidJUnit4::class)
class TimelineRunManifestTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val scratchDirs = mutableListOf<File>()

    @After
    fun cleanUpScratchDirs() {
        scratchDirs.forEach { it.deleteRecursively() }
        scratchDirs.clear()
    }

    // ------------------------------------------------------------------
    // Fixture builders
    // ------------------------------------------------------------------

    private fun freshDir(): File =
        File(instrumentation.targetContext.cacheDir, "manifest-test-${System.nanoTime()}").also {
            check(it.mkdirs()) { "cannot create scratch dir $it" }
            scratchDirs += it
        }

    private fun control(
        block: String,
        iteration: Int,
        refreshBeforeHz: Double = 120.0,
        refreshAfterHz: Double = 120.0,
        thermalBefore: Int = 0,
        thermalAfter: Int = 0,
        pidBefore: String = "1234",
        pidAfter: String = "1234",
        traversal: SelectedDayTraversalSummary? = null,
    ): TimelineIterationControl = TimelineIterationControl(
        block = block,
        iteration = iteration,
        refreshBeforeHz = refreshBeforeHz,
        refreshAfterHz = refreshAfterHz,
        thermalBefore = thermalBefore,
        thermalAfter = thermalAfter,
        pidBefore = pidBefore,
        pidAfter = pidAfter,
        selectedDayDrawCount = traversal?.selectedDayDrawCount,
        replacementCount = traversal?.replacementCount,
        maxPublishedItemCount = traversal?.maxPublishedItemCount,
        prefetchQueryCount = traversal?.prefetchQueryCount,
        firstDate = traversal?.firstDate,
        lastDate = traversal?.lastDate,
    )

    private fun blockRecords(
        block: String,
        count: Int,
        traversal: SelectedDayTraversalSummary? = null,
    ): List<TimelineIterationControl> =
        (0 until count).map { control(block, it, traversal = traversal) }

    /** The frozen 35-record sequence: 15 load / 3 replace / 2 replace / 15 load. */
    private fun fullIterationSet(): List<TimelineIterationControl> =
        blockRecords(BLOCK_LOAD_A, 15) +
            blockRecords(BLOCK_REPLACE_A, 3, traversal = SelectedDayTraversalSummary.FROZEN) +
            blockRecords(BLOCK_REPLACE_B, 2, traversal = SelectedDayTraversalSummary.FROZEN) +
            blockRecords(BLOCK_LOAD_B, 15)

    private fun readManifest(dir: File): JSONObject =
        JSONObject(File(dir, TimelineRunManifest.MANIFEST_FILE).readText())

    private fun sortedKeys(obj: JSONObject): List<String> =
        obj.keys().asSequence().toList().sorted()

    private fun assertExactKeys(obj: JSONObject, vararg keys: String) {
        assertEquals(keys.sorted(), sortedKeys(obj))
    }

    private fun assertControlJson(record: JSONObject, control: TimelineIterationControl) {
        assertEquals(control.block, record.getString("block"))
        assertEquals(control.iteration, record.getInt("iteration"))
        assertEquals(control.refreshBeforeHz, record.getDouble("refreshBeforeHz"), 1e-9)
        assertEquals(control.refreshAfterHz, record.getDouble("refreshAfterHz"), 1e-9)
        assertEquals(control.thermalBefore, record.getInt("thermalBefore"))
        assertEquals(control.thermalAfter, record.getInt("thermalAfter"))
        assertEquals(control.pidBefore, record.getString("pidBefore"))
        assertEquals(control.pidAfter, record.getString("pidAfter"))
        val summary = control.traversalSummaryOrNull()
        if (summary == null) {
            record.isNull("selectedDayDrawCount").also { assertTrue("load record must serialize selectedDayDrawCount as JSON null", it) }
            record.isNull("replacementCount").also { assertTrue("load record must serialize replacementCount as JSON null", it) }
            record.isNull("maxPublishedItemCount").also { assertTrue("load record must serialize maxPublishedItemCount as JSON null", it) }
            record.isNull("prefetchQueryCount").also { assertTrue("load record must serialize prefetchQueryCount as JSON null", it) }
            record.isNull("firstDate").also { assertTrue("load record must serialize firstDate as JSON null", it) }
            record.isNull("lastDate").also { assertTrue("load record must serialize lastDate as JSON null", it) }
        } else {
            assertEquals(summary.selectedDayDrawCount, record.getInt("selectedDayDrawCount"))
            assertEquals(summary.replacementCount, record.getInt("replacementCount"))
            assertEquals(summary.maxPublishedItemCount, record.getInt("maxPublishedItemCount"))
            assertEquals(summary.prefetchQueryCount, record.getInt("prefetchQueryCount"))
            assertEquals(summary.firstDate, record.getString("firstDate"))
            assertEquals(summary.lastDate, record.getString("lastDate"))
        }
    }

    private fun assertControlTraversalJson(obj: JSONObject?, summary: SelectedDayTraversalSummary) {
        assertTrue("controlTraversal must be present", obj != null)
        assertEquals(summary.selectedDayDrawCount, obj?.getInt("selectedDayDrawCount"))
        assertEquals(summary.replacementCount, obj?.getInt("replacementCount"))
        assertEquals(summary.maxPublishedItemCount, obj?.getInt("maxPublishedItemCount"))
        assertEquals(summary.prefetchQueryCount, obj?.getInt("prefetchQueryCount"))
        assertEquals(summary.firstDate, obj?.getString("firstDate"))
        assertEquals(summary.lastDate, obj?.getString("lastDate"))
    }

    // ------------------------------------------------------------------
    // Step 3 tests
    // ------------------------------------------------------------------

    @Test
    fun orderedAppendPreservesExactBlockMajorSequence() {
        val dir = freshDir()
        val manifest = TimelineRunManifest(TimelineDisplaySettings("120", "null"), dir)
        val expected = fullIterationSet()
        manifest.setControlTraversal(SelectedDayTraversalSummary.FROZEN)
        expected.forEach { manifest.add(it) }
        manifest.finish(TimelineDisplaySettings("120", "null"))

        val root = readManifest(dir)
        val iterations = root.getJSONArray("iterations")
        assertEquals(35, iterations.length())
        for (i in 0 until 35) {
            assertControlJson(iterations.getJSONObject(i), expected[i])
        }
    }

    @Test
    fun duplicateBlockIterationPairRejected() {
        val manifest = TimelineRunManifest(TimelineDisplaySettings("120", "null"), freshDir())
        manifest.add(control(BLOCK_LOAD_A, 0))
        assertThrows(IllegalArgumentException::class.java) {
            manifest.add(control(BLOCK_LOAD_A, 0))
        }
        // A different iteration of the same block remains addable.
        manifest.add(control(BLOCK_LOAD_A, 1))
    }

    @Test
    fun nonFiniteOrNonPositiveRefreshRejected() {
        val badBefore = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)
        for (bad in badBefore) {
            val manifest = TimelineRunManifest(TimelineDisplaySettings("120", "null"), freshDir())
            assertThrows(
                "refreshBeforeHz=$bad must be rejected",
                IllegalArgumentException::class.java,
            ) {
                manifest.add(control(BLOCK_LOAD_A, 0, refreshBeforeHz = bad))
            }
        }
        val badAfter = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)
        for (bad in badAfter) {
            val manifest = TimelineRunManifest(TimelineDisplaySettings("120", "null"), freshDir())
            assertThrows(
                "refreshAfterHz=$bad must be rejected",
                IllegalArgumentException::class.java,
            ) {
                manifest.add(control(BLOCK_LOAD_A, 0, refreshAfterHz = bad))
            }
        }
    }

    @Test
    fun pidDeltaOrBlankRejected() {
        val delta = TimelineRunManifest(TimelineDisplaySettings("120", "null"), freshDir())
        assertThrows(IllegalArgumentException::class.java) {
            delta.add(control(BLOCK_LOAD_A, 0, pidBefore = "100", pidAfter = "200"))
        }
        val blankBefore = TimelineRunManifest(TimelineDisplaySettings("120", "null"), freshDir())
        assertThrows(IllegalArgumentException::class.java) {
            blankBefore.add(control(BLOCK_LOAD_A, 0, pidBefore = " ", pidAfter = "100"))
        }
        val blankAfter = TimelineRunManifest(TimelineDisplaySettings("120", "null"), freshDir())
        assertThrows(IllegalArgumentException::class.java) {
            blankAfter.add(control(BLOCK_LOAD_A, 0, pidBefore = "100", pidAfter = " "))
        }
        val ok = TimelineRunManifest(TimelineDisplaySettings("120", "null"), freshDir())
        ok.add(control(BLOCK_LOAD_A, 0, pidBefore = "100", pidAfter = "100"))
    }

    @Test
    fun replacementRecordsCarryExactTraversalAndLoadRecordsCarryNull() {
        val dir = freshDir()
        val manifest = TimelineRunManifest(TimelineDisplaySettings("120", "null"), dir)
        manifest.add(control(BLOCK_REPLACE_A, 0, traversal = SelectedDayTraversalSummary.FROZEN))
        manifest.add(control(BLOCK_LOAD_A, 0, traversal = null))
        manifest.finish(TimelineDisplaySettings("120", "null"))

        val root = readManifest(dir)
        val iterations = root.getJSONArray("iterations")
        assertEquals(2, iterations.length())
        assertControlJson(iterations.getJSONObject(0), control(BLOCK_REPLACE_A, 0, traversal = SelectedDayTraversalSummary.FROZEN))
        assertControlJson(iterations.getJSONObject(1), control(BLOCK_LOAD_A, 0, traversal = null))
    }

    @Test
    fun loadRecordWithTraversalFieldsRejected() {
        val manifest = TimelineRunManifest(TimelineDisplaySettings("120", "null"), freshDir())
        assertThrows(IllegalArgumentException::class.java) {
            manifest.add(
                control(BLOCK_LOAD_A, 0, traversal = SelectedDayTraversalSummary.FROZEN),
            )
        }
        // A partially-filled load record is also rejected.
        val partial = TimelineRunManifest(TimelineDisplaySettings("120", "null"), freshDir())
        assertThrows(IllegalArgumentException::class.java) {
            partial.add(
                control(BLOCK_LOAD_A, 0).copy(selectedDayDrawCount = 999),
            )
        }
    }

    @Test
    fun replacementRecordWrongSummaryRejected() {
        val manifest = TimelineRunManifest(TimelineDisplaySettings("120", "null"), freshDir())
        val wrong = SelectedDayTraversalSummary(
            selectedDayDrawCount = 999,
            replacementCount = 998,
            maxPublishedItemCount = 12,
            prefetchQueryCount = 1,
            firstDate = "2025-09-25",
            lastDate = "2023-01-02",
        )
        assertThrows(IllegalArgumentException::class.java) {
            manifest.add(control(BLOCK_REPLACE_A, 0, traversal = wrong))
        }
        // A replacement record with all-null traversal fields is also rejected.
        val nullTraversal = TimelineRunManifest(TimelineDisplaySettings("120", "null"), freshDir())
        assertThrows(IllegalArgumentException::class.java) {
            nullTraversal.add(control(BLOCK_REPLACE_A, 0, traversal = null))
        }
    }

    @Test
    fun controlTraversalSerializedWithExactFrozenValues() {
        val dir = freshDir()
        val manifest = TimelineRunManifest(TimelineDisplaySettings("120", "null"), dir)
        manifest.setControlTraversal(SelectedDayTraversalSummary.FROZEN)
        manifest.add(control(BLOCK_LOAD_A, 0))
        manifest.finish(TimelineDisplaySettings("120", "null"))

        val root = readManifest(dir)
        assertExactKeys(root, "suite", "displaySettingsBefore", "displaySettingsAfter", "controlTraversal", "iterations")
        assertControlTraversalJson(root.optJSONObject("controlTraversal"), SelectedDayTraversalSummary.FROZEN)
    }

    @Test
    fun atomicPublicationReplacesPriorManifestFully() {
        val dir = freshDir()
        val target = File(dir, TimelineRunManifest.MANIFEST_FILE)

        val first = TimelineRunManifest(TimelineDisplaySettings("120", "null"), dir)
        first.add(control(BLOCK_LOAD_A, 0, refreshBeforeHz = 120.0))
        assertTrue("no manifest file may exist before finish()", !target.exists())

        first.finish(TimelineDisplaySettings("120", "null"))
        assertTrue("finish() must publish the manifest file", target.exists())
        var root = readManifest(dir)
        assertEquals(1, root.getJSONArray("iterations").length())
        assertEquals(120.0, root.getJSONArray("iterations").getJSONObject(0).getDouble("refreshBeforeHz"), 1e-9)
        assertEquals(
            listOf(TimelineRunManifest.MANIFEST_FILE),
            dir.listFiles()!!.map { it.name }.sorted(),
        )

        assertThrows(IllegalStateException::class.java) {
            first.finish(TimelineDisplaySettings("120", "null"))
        }

        val second = TimelineRunManifest(TimelineDisplaySettings("120", "null"), dir)
        second.setControlTraversal(SelectedDayTraversalSummary.FROZEN)
        val replacement = listOf(
            control(BLOCK_LOAD_A, 0, refreshBeforeHz = 90.0, refreshAfterHz = 90.0),
            control(BLOCK_REPLACE_A, 0, refreshBeforeHz = 90.0, refreshAfterHz = 90.0, traversal = SelectedDayTraversalSummary.FROZEN),
        )
        replacement.forEach { second.add(it) }
        second.finish(TimelineDisplaySettings("120", "null"))

        root = readManifest(dir)
        val iterations = root.getJSONArray("iterations")
        assertEquals("replacement must not contain stale or duplicate records", 2, iterations.length())
        for (i in 0 until 2) {
            assertControlJson(iterations.getJSONObject(i), replacement[i])
        }
        assertControlTraversalJson(root.optJSONObject("controlTraversal"), SelectedDayTraversalSummary.FROZEN)
        assertEquals(
            listOf(TimelineRunManifest.MANIFEST_FILE),
            dir.listFiles()!!.map { it.name }.sorted(),
        )
    }

    @Test
    fun finishWritesExactThirtyFiveRecordShape() {
        val dir = freshDir()
        val before = TimelineDisplaySettings("120", "null")
        val after = TimelineDisplaySettings("120", "null")
        val manifest = TimelineRunManifest(before, dir)
        manifest.setControlTraversal(SelectedDayTraversalSummary.FROZEN)
        val expected = fullIterationSet()
        expected.forEach { manifest.add(it) }
        manifest.finish(after)

        val root = readManifest(dir)
        assertExactKeys(root, "suite", "displaySettingsBefore", "displaySettingsAfter", "controlTraversal", "iterations")
        assertEquals("TimelineBenchmark", root.getString("suite"))

        val beforeJson = root.getJSONObject("displaySettingsBefore")
        assertExactKeys(beforeJson, "peak", "min")
        assertEquals("120", beforeJson.getString("peak"))
        assertEquals("null", beforeJson.getString("min"))

        val afterJson = root.getJSONObject("displaySettingsAfter")
        assertExactKeys(afterJson, "peak", "min")
        assertEquals("120", afterJson.getString("peak"))
        assertEquals("null", afterJson.getString("min"))

        assertExactKeys(
            root.getJSONObject("controlTraversal"),
            "selectedDayDrawCount", "replacementCount", "maxPublishedItemCount",
            "prefetchQueryCount", "firstDate", "lastDate",
        )

        val iterations: JSONArray = root.getJSONArray("iterations")
        assertEquals(35, iterations.length())
        val seenPairs = HashSet<Pair<String, Int>>()
        for (i in 0 until 35) {
            val record = iterations.getJSONObject(i)
            assertExactKeys(
                record,
                "block", "iteration", "refreshBeforeHz", "refreshAfterHz",
                "thermalBefore", "thermalAfter", "pidBefore", "pidAfter",
                "selectedDayDrawCount", "replacementCount", "maxPublishedItemCount",
                "prefetchQueryCount", "firstDate", "lastDate",
            )
            val pair = record.getString("block") to record.getInt("iteration")
            assertTrue("(block, iteration) must be unique in the final shape: $pair", seenPairs.add(pair))
            assertControlJson(record, expected[i])
        }
    }

    private companion object {
        const val BLOCK_LOAD_A = "binding01LoadA"
        const val BLOCK_REPLACE_A = "binding02ScrollA"
        const val BLOCK_REPLACE_B = "binding03ScrollB"
        const val BLOCK_LOAD_B = "binding04LoadB"
    }
}
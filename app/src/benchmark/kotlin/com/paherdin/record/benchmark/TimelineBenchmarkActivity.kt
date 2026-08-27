package com.paherdin.record.benchmark

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.paherdin.record.core.data.timeline.TimelineFixtureGenerator
import com.paherdin.record.core.data.timeline.ShadowProjectionHandle
import com.paherdin.record.core.data.timeline.ShadowPreparationFailurePoint
import com.paherdin.record.core.data.timeline.ShadowResourceSnapshot
import com.paherdin.record.core.data.timeline.TimelineSeedCard
import com.paherdin.record.core.data.timeline.TimelineShadowSeeder
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import org.json.JSONObject

/**
 * Tech PoC P6 — benchmark-build-only Activity, absent from `main`/`release`.
 * Modes include one-time `seed-and-verify`/`prepare-selected-day-contract`,
 * read-only selected-day contracts, and the Task 4/5 render controls.
 *
 * Render/scroll modes install the reference-day immutable 11-item projection,
 * draw a `LazyColumn` of day headers + mixed cards, and expose
 * count/hash/top/bottom/refresh/trace sentinels. `scroll-ready` additionally
 * stays idle for the out-of-process manual scroll: it watches for the final
 * card being laid out and self-reports `bottomReached`, since the
 * instrumentation cannot read the target window on this firmware. UI code
 * never touches a DAO, Room Entity, database handle, or physical path — the
 * [TimelineShadowSeeder] facade is the only Room-facing surface.
 */
class TimelineBenchmarkActivity : ComponentActivity() {

    private val replacementStartRequests = Channel<String>(capacity = 1)
    private var replacementRunId: String? = null
    private var replacementStarted = false
    private var replacementHandle: ShadowProjectionHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_MODE) ?: MODE_SEED_AND_VERIFY) {
            MODE_SEED_AND_VERIFY -> prepareSelectedDayContract(MODE_SEED_AND_VERIFY)
            MODE_PREPARE_SELECTED_DAY_CONTRACT ->
                prepareSelectedDayContract(MODE_PREPARE_SELECTED_DAY_CONTRACT)
            MODE_SELECTED_DAY_CONTRACT -> selectedDayContract(
                mode = MODE_SELECTED_DAY_CONTRACT,
                selectedDate = requireNotNull(intent.getStringExtra(EXTRA_SELECTED_DATE)) {
                    "selected-day contract requires selectedDate"
                },
                dayStart = intent.getLongExtra(EXTRA_DAY_START, Long.MIN_VALUE),
                nextDayStart = intent.getLongExtra(EXTRA_NEXT_DAY_START, Long.MIN_VALUE),
            )
            MODE_RENDER_CORRECTNESS -> startRender(wrongOrder = false)
            MODE_RENDER_WRONG_ORDER -> startRender(wrongOrder = true)
            MODE_REPLACE_READY -> startReplacementReady()
            MODE_LOAD_ONLY -> startRender(wrongOrder = false, loadOnly = true)
            MODE_RESOURCE_FAILURE_CONTRACT -> prepareSelectedDayContract(
                mode = MODE_RESOURCE_FAILURE_CONTRACT,
                failurePoint = ShadowPreparationFailurePoint.fromWire(
                    requireNotNull(intent.getStringExtra(EXTRA_FAILURE_POINT)) {
                        "resource failure contract requires failurePoint"
                    },
                ),
            )
            MODE_RENDER_RESOURCE_FAILURE -> startRender(
                wrongOrder = true,
                forceStateValidationFailure = true,
            )
            else -> throw IllegalArgumentException(
                "unknown mode: ${intent.getStringExtra(EXTRA_MODE)}",
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_MODE) != MODE_REPLACE_READY ||
            !intent.getBooleanExtra(EXTRA_START_TRAVERSAL, false)
        ) {
            writeSentinel(SENTINEL_ERROR, "replace-ready requires a one-shot traversal start")
            return
        }
        val suppliedRunId = intent.getStringExtra(EXTRA_RUN_ID)
        if (suppliedRunId == null || suppliedRunId != replacementRunId) {
            writeSentinel(
                SENTINEL_ERROR,
                "replacement runId '$suppliedRunId' does not match ready runId '$replacementRunId'",
            )
            return
        }
        if (replacementStarted) {
            writeSentinel(SENTINEL_ERROR, "replacement runId '$suppliedRunId' already started")
            return
        }
        replacementStarted = true
        if (!replacementStartRequests.trySend(suppliedRunId).isSuccess) {
            writeSentinel(SENTINEL_ERROR, "replacement start channel rejected runId '$suppliedRunId'")
        }
    }

    override fun onDestroy() {
        replacementHandle?.close()
        replacementHandle = null
        replacementStartRequests.close()
        super.onDestroy()
    }

    private fun selectedDayContract(
        mode: String,
        selectedDate: String,
        dayStart: Long,
        nextDayStart: Long,
    ) {
        startFreshRun(
            mode = mode,
            keysToClear = CONTRACT_SENTINELS + SENTINEL_ERROR,
        )
        Thread {
            try {
                val result = TimelineShadowSeeder.readSelectedDayContract(
                    applicationContext,
                    selectedDate,
                    dayStart,
                    nextDayStart,
                )
                val values = linkedMapOf(
                    SENTINEL_ROW_COUNT to result.rowCount.toString(),
                    SENTINEL_SELECTED_DATE to result.selectedDate,
                    SENTINEL_DAY_START to result.dayStart.toString(),
                    SENTINEL_NEXT_DAY_START to result.nextDayStart.toString(),
                    SENTINEL_ITEM_COUNT to result.itemCount.toString(),
                    SENTINEL_QUERY_PLAN_DETAIL to result.queryPlanDetail,
                    SENTINEL_SEED_WRITE_COUNT to result.seedWriteCount.toString(),
                    SENTINEL_ACTUAL_HASH to result.hash,
                )
                values.forEach(::writeSentinel)
                runOnUiThread { showContractSentinels(values) }
            } catch (t: Throwable) {
                writeSentinel(SENTINEL_ERROR, t.message ?: t::class.java.simpleName)
            }
        }.start()
    }

    private fun prepareSelectedDayContract(
        mode: String,
        failurePoint: ShadowPreparationFailurePoint? = null,
    ) {
        startFreshRun(
            mode = mode,
            keysToClear = CONTRACT_SENTINELS + RESOURCE_SENTINELS + SENTINEL_ERROR,
        )
        Thread {
            val before = TimelineShadowSeeder.resourceSnapshot()
            try {
                val result = TimelineShadowSeeder.prepareSeedAndVerify(
                    applicationContext,
                    timelineSeedCards(),
                    failurePoint,
                )
                val values = linkedMapOf(
                    SENTINEL_ROW_COUNT to result.rowCount.toString(),
                    SENTINEL_SEED_WRITE_COUNT to result.seedWriteCount.toString(),
                    SENTINEL_MIGRATION_BEFORE_ROW_COUNT to result.migration.beforeRowCount.toString(),
                    SENTINEL_MIGRATION_AFTER_ROW_COUNT to result.migration.afterRowCount.toString(),
                    SENTINEL_MIGRATION_FIRST_HASH_BEFORE to result.migration.firstHashBefore,
                    SENTINEL_MIGRATION_FIRST_HASH_AFTER to result.migration.firstHashAfter,
                    SENTINEL_MIGRATION_LAST_HASH_BEFORE to result.migration.lastHashBefore,
                    SENTINEL_MIGRATION_LAST_HASH_AFTER to result.migration.lastHashAfter,
                    SENTINEL_MIGRATION_QUERY_PLAN_DETAIL to result.migration.queryPlanDetail,
                    SENTINEL_PREPARED to "true",
                )
                values.forEach(::writeSentinel)
                runOnUiThread { showContractSentinels(values) }
            } catch (t: Throwable) {
                writeSentinel(SENTINEL_ERROR, t.message ?: t::class.java.simpleName)
                writeResourceDeltas(before)
            }
        }.start()
    }

    internal fun timelineSeedCards(): List<TimelineSeedCard> =
        TimelineFixtureGenerator.generate().map { record ->
            TimelineSeedCard(
                id = record.id,
                type = record.type,
                occurredAt = record.occurredAt,
                title = record.title,
                amountMinor = record.amountMinor,
                body = record.body,
            )
        }

    internal fun writeResourceDeltas(before: ShadowResourceSnapshot) {
        val after = TimelineShadowSeeder.resourceSnapshot()
        writeSentinel(SENTINEL_RESOURCE_OPEN_DELTA, after.openedSince(before).toString())
        writeSentinel(SENTINEL_RESOURCE_CLOSE_DELTA, after.closedSince(before).toString())
    }

    private fun startRender(
        wrongOrder: Boolean,
        loadOnly: Boolean = false,
        forceStateValidationFailure: Boolean = false,
    ) {
        // Atomically stamp the actual mode and clear any stale outcome from a
        // previous launch, so the out-of-process test provably waits on THIS
        // run's fresh sentinels (mode match) rather than an old window's prefs.
        val mode = when {
            loadOnly -> MODE_LOAD_ONLY
            forceStateValidationFailure -> MODE_RENDER_RESOURCE_FAILURE
            wrongOrder -> MODE_RENDER_WRONG_ORDER
            else -> MODE_RENDER_CORRECTNESS
        }
        startFreshRun(
            mode = mode,
            keysToClear = listOf(
                SENTINEL_ERROR,
                SENTINEL_READY,
                SENTINEL_COUNT,
                SENTINEL_HASH,
                SENTINEL_TRACE_OK,
                SENTINEL_TOP_CARD,
                SENTINEL_TOP_HEADER_DATE,
                SENTINEL_BOTTOM_CARD,
                SENTINEL_BOTTOM_REACHED,
                SENTINEL_QUERY_SQL,
                SENTINEL_SELECTED_DATE,
                SENTINEL_DAY_START,
                SENTINEL_NEXT_DAY_START,
                SENTINEL_PUBLISHED_DATE_COUNT,
                SENTINEL_PUBLISHED_DATES,
                SENTINEL_REFRESH_BEFORE_HZ,
                SENTINEL_REFRESH_AFTER_HZ,
                SENTINEL_SEED_WRITE_COUNT,
                SENTINEL_RESOURCE_OPEN_DELTA,
                SENTINEL_RESOURCE_CLOSE_DELTA,
            ),
        )
        setContent {
            TimelineRenderApp(
                activity = this,
                selectedDate = intent.getStringExtra(EXTRA_SELECTED_DATE) ?: REFERENCE_DATE,
                dayStart = intent.getLongExtra(EXTRA_DAY_START, REFERENCE_DAY_START),
                nextDayStart = intent.getLongExtra(EXTRA_NEXT_DAY_START, REFERENCE_DAY_END),
                wrongOrder = wrongOrder,
                loadOnly = loadOnly,
                forceStateValidationFailure = forceStateValidationFailure,
            )
        }
    }

    private fun startReplacementReady() {
        replacementStarted = false
        replacementHandle?.close()
        replacementHandle = null
        replacementRunId = startFreshRun(
            mode = MODE_REPLACE_READY,
            keysToClear = listOf(
                SENTINEL_ERROR,
                SENTINEL_READY,
                SENTINEL_REPLACE_READY,
                SENTINEL_SELECTED_DATE,
                SENTINEL_DAY_START,
                SENTINEL_NEXT_DAY_START,
                SENTINEL_COUNT,
                SENTINEL_HASH,
                SENTINEL_TOP_CARD,
                SENTINEL_TOP_HEADER_DATE,
                SENTINEL_BOTTOM_CARD,
                SENTINEL_PUBLISHED_DATE_COUNT,
                SENTINEL_PUBLISHED_DATES,
                SENTINEL_SELECTED_DAY_DRAW_COUNT,
                SENTINEL_REPLACEMENT_COUNT,
                SENTINEL_MAX_PUBLISHED_ITEM_COUNT,
                SENTINEL_PREFETCH_QUERY_COUNT,
                SENTINEL_FIRST_DATE,
                SENTINEL_LAST_DATE,
                SENTINEL_TRAVERSAL_COMPLETE,
                SENTINEL_REFRESH_BEFORE_HZ,
                SENTINEL_REFRESH_AFTER_HZ,
                SENTINEL_QUERY_SQL,
                SENTINEL_SEED_WRITE_COUNT,
            ),
        )
        setContent {
            TimelineReplacementApp(
                activity = this,
                startRequests = replacementStartRequests,
            )
        }
    }

    internal fun replacementStarts(): ReceiveChannel<String> = replacementStartRequests

    internal fun installReplacementHandle(handle: ShadowProjectionHandle) {
        check(replacementHandle == null) { "replacement handle already installed" }
        replacementHandle = handle
    }

    internal fun closeReplacementHandle() {
        replacementHandle?.close()
        replacementHandle = null
    }

    private fun showContractSentinels(values: Map<String, String>) {
        // Visible sentinels for manual / uiautomator verification.
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        values.forEach { (key, value) ->
            layout.addView(
                TextView(this@TimelineBenchmarkActivity).apply { text = "P6:$key=$value" },
            )
        }
        setContentView(layout)
    }

    internal fun sentinelPrefs(): SharedPreferences =
        getSharedPreferences(SENTINEL_PREFS, MODE_PRIVATE)

    /**
     * Writes one sentinel to the app's SharedPreferences AND to the
     * cross-process mirror file `<externalMediaDir>/p6_sentinels.json`.
     *
     * Task 6 repair (self-instrumenting benchmark module): the out-of-process
     * harness no longer shares the app uid, so the prefs file is unreadable to
     * it. The mirror file lives in the app's own external media directory —
     * app-writable, shell-readable — and is published on a background
     * executor so the write never lands inside the measured trace section.
     * The synchronous prefs commit is retained unchanged.
     *
     * Task 6 D′ measurement-contract fix: this method MUST NOT be called
     * between `gate.start()` and `gate.end()` — the synchronous prefs commit
     * is real I/O that would pollute the measured `TimelineQueryToRender`
     * slice. During the measured path call [bufferSentinel] instead; persist
     * with [flushBufferedSentinels] only AFTER the trace has ended.
     */
    internal fun writeSentinel(key: String, value: String) {
        sentinelMirror[key] = value
        sentinelPrefs().edit().putString(key, value).commit()
        publishSentinelFile()
    }

    /**
     * Buffers one sentinel in memory ONLY — no SharedPreferences/file I/O.
     * Used for the top/header/bottom/refresh sentinels that the render
     * surface must expose while the `TimelineQueryToRender` slice is open, so
     * nothing but the measured query/draw path runs inside [start, end].
     */
    private val bufferedSentinels: MutableMap<String, String> = LinkedHashMap()

    /** Buffers a sentinel in memory only (no prefs/file I/O). */
    internal fun bufferSentinel(key: String, value: String) {
        bufferedSentinels[key] = value
    }

    /**
     * Persists every buffered sentinel through the normal prefs + mirror-file
     * path. MUST be called only after the measured trace span has ended.
     */
    internal fun flushBufferedSentinels() {
        val snapshot = LinkedHashMap(bufferedSentinels)
        bufferedSentinels.clear()
        snapshot.forEach { (key, value) -> writeSentinel(key, value) }
    }

    /**
     * Atomically stamps [mode], a fresh per-launch [SENTINEL_RUN_ID], and the
     * [keysToClear] removals in both the prefs and the mirror file, exactly
     * like the previous single prefs edit.
     *
     * The mirror-file publication is SYNCHRONOUS here: the out-of-process
     * harness's freshness protocol waits for a NEW runId (never for a
     * transient cleared state — the full load can complete within the
     * `am start -W` window on this device), so the new run identity must be
     * deterministically visible by the time `am start -W` returns. The write
     * is tiny and happens before the measured query trace starts.
     */
    internal fun startFreshRun(mode: String, keysToClear: Collection<String>): String {
        val edit = sentinelPrefs().edit()
        val runId = UUID.randomUUID().toString()
        bufferedSentinels.clear()
        sentinelMirror[SENTINEL_MODE] = mode
        sentinelMirror[SENTINEL_RUN_ID] = runId
        edit.putString(SENTINEL_MODE, mode)
        edit.putString(SENTINEL_RUN_ID, runId)
        for (key in keysToClear) {
            sentinelMirror.remove(key)
            edit.remove(key)
        }
        edit.commit()
        publishSentinelFileSynchronously()
        return runId
    }

    /** Enqueues one async publication of the mirror file snapshot. */
    private fun publishSentinelFile() {
        val dir = getExternalMediaDirs().firstOrNull() ?: return
        val snapshot = HashMap(sentinelMirror)
        SENTINEL_FILE_EXECUTOR.execute {
            writeSentinelFile(dir, snapshot)
        }
    }

    /** Synchronous publication used by the launch-time fresh-run protocol. */
    private fun publishSentinelFileSynchronously() {
        val dir = getExternalMediaDirs().firstOrNull() ?: return
        writeSentinelFile(dir, HashMap(sentinelMirror))
    }

    private fun writeSentinelFile(dir: File, snapshot: Map<String, String>) {
        try {
            val file = File(dir, SENTINEL_FILE_NAME)
            file.parentFile?.mkdirs()
            val root = JSONObject()
            snapshot.forEach { (key, value) -> root.put(key, value) }
            file.writeText(root.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "sentinel file publish failed: ${t.message}")
        }
    }

    /**
     * Actual display refresh rate rounded to 0.1 Hz — the value of the distinct
     * `refreshBeforeHz` / `refreshAfterHz` sentinels. Read-only observation;
     * never written to system settings.
     */
    internal fun currentRefreshHz(): String {
        val hz = display?.refreshRate?.let { Math.round(it * 10.0) / 10.0 } ?: 0.0
        return "$hz"
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_SELECTED_DATE = "selectedDate"
        const val EXTRA_DAY_START = "dayStart"
        const val EXTRA_NEXT_DAY_START = "nextDayStart"
        const val EXTRA_FAILURE_POINT = "failurePoint"
        const val EXTRA_RUN_ID = "runId"
        const val EXTRA_START_TRAVERSAL = "startTraversal"
        const val MODE_SEED_AND_VERIFY = "seed-and-verify"
        const val MODE_PREPARE_SELECTED_DAY_CONTRACT = "prepare-selected-day-contract"
        const val MODE_SELECTED_DAY_CONTRACT = "selected-day-contract"
        const val MODE_RENDER_CORRECTNESS = "render-correctness"
        const val MODE_RENDER_WRONG_ORDER = "render-wrong-order"
        const val MODE_REPLACE_READY = "replace-ready"
        const val MODE_LOAD_ONLY = "load-only"
        const val MODE_RESOURCE_FAILURE_CONTRACT = "resource-failure-contract"
        const val MODE_RENDER_RESOURCE_FAILURE = "render-resource-failure"
        const val SENTINEL_PREFS = "p6_sentinels"
        const val SENTINEL_ROW_COUNT = "rowCount"
        const val SENTINEL_FLATTENED_COUNT = "flattenedCount"
        const val SENTINEL_ACTUAL_HASH = "actualHash"
        const val SENTINEL_SELECTED_DATE = "selectedDate"
        const val SENTINEL_DAY_START = "dayStart"
        const val SENTINEL_NEXT_DAY_START = "nextDayStart"
        const val SENTINEL_ITEM_COUNT = "itemCount"
        const val SENTINEL_QUERY_PLAN_DETAIL = "queryPlanDetail"
        const val SENTINEL_SEED_WRITE_COUNT = "seedWriteCount"
        const val SENTINEL_PREPARED = "prepared"
        const val SENTINEL_RESOURCE_OPEN_DELTA = "resourceOpenDelta"
        const val SENTINEL_RESOURCE_CLOSE_DELTA = "resourceCloseDelta"
        const val SENTINEL_MIGRATION_BEFORE_ROW_COUNT = "migrationBeforeRowCount"
        const val SENTINEL_MIGRATION_AFTER_ROW_COUNT = "migrationAfterRowCount"
        const val SENTINEL_MIGRATION_FIRST_HASH_BEFORE = "migrationFirstHashBefore"
        const val SENTINEL_MIGRATION_FIRST_HASH_AFTER = "migrationFirstHashAfter"
        const val SENTINEL_MIGRATION_LAST_HASH_BEFORE = "migrationLastHashBefore"
        const val SENTINEL_MIGRATION_LAST_HASH_AFTER = "migrationLastHashAfter"
        const val SENTINEL_MIGRATION_QUERY_PLAN_DETAIL = "migrationQueryPlanDetail"
        const val SENTINEL_READY = "ready"
        const val SENTINEL_COUNT = "count"
        const val SENTINEL_HASH = "hash"
        const val SENTINEL_TOP_CARD = "topCardId"
        const val SENTINEL_TOP_HEADER_DATE = "topHeaderDate"
        const val SENTINEL_BOTTOM_CARD = "bottomCardId"
        const val SENTINEL_BOTTOM_REACHED = "bottomReached"
        const val SENTINEL_QUERY_SQL = "querySql"
        const val SENTINEL_PUBLISHED_DATE_COUNT = "publishedDateCount"
        const val SENTINEL_PUBLISHED_DATES = "publishedDates"
        const val SENTINEL_SELECTED_DAY_DRAW_COUNT = "selectedDayDrawCount"
        const val SENTINEL_REPLACEMENT_COUNT = "replacementCount"
        const val SENTINEL_MAX_PUBLISHED_ITEM_COUNT = "maxPublishedItemCount"
        const val SENTINEL_PREFETCH_QUERY_COUNT = "prefetchQueryCount"
        const val SENTINEL_FIRST_DATE = "firstDate"
        const val SENTINEL_LAST_DATE = "lastDate"
        const val SENTINEL_TRAVERSAL_COMPLETE = "traversalComplete"
        const val SENTINEL_REFRESH_BEFORE_HZ = "refreshBeforeHz"
        const val SENTINEL_REFRESH_AFTER_HZ = "refreshAfterHz"
        const val SENTINEL_TRACE_OK = "traceOk"
        const val SENTINEL_TRACE_MS = "queryToRenderMs"
        const val SENTINEL_MODE = "mode"
        const val SENTINEL_RUN_ID = "runId"
        const val SENTINEL_REPLACE_READY = "replaceReady"
        const val SENTINEL_ERROR = "error"
        const val EXPECTED_ITEM_COUNT = 11
        const val REFERENCE_DATE = "2025-09-26"
        const val REFERENCE_DAY_START = 1_758_816_000_000L
        const val REFERENCE_DAY_END = 1_758_902_400_000L

        private val CONTRACT_SENTINELS = listOf(
            SENTINEL_ROW_COUNT,
            SENTINEL_ACTUAL_HASH,
            SENTINEL_SELECTED_DATE,
            SENTINEL_DAY_START,
            SENTINEL_NEXT_DAY_START,
            SENTINEL_ITEM_COUNT,
            SENTINEL_QUERY_PLAN_DETAIL,
            SENTINEL_SEED_WRITE_COUNT,
            SENTINEL_PREPARED,
            SENTINEL_MIGRATION_BEFORE_ROW_COUNT,
            SENTINEL_MIGRATION_AFTER_ROW_COUNT,
            SENTINEL_MIGRATION_FIRST_HASH_BEFORE,
            SENTINEL_MIGRATION_FIRST_HASH_AFTER,
            SENTINEL_MIGRATION_LAST_HASH_BEFORE,
            SENTINEL_MIGRATION_LAST_HASH_AFTER,
            SENTINEL_MIGRATION_QUERY_PLAN_DETAIL,
        )
        private val RESOURCE_SENTINELS = listOf(
            SENTINEL_RESOURCE_OPEN_DELTA,
            SENTINEL_RESOURCE_CLOSE_DELTA,
        )

        private const val TAG = "P6BenchmarkActivity"
        const val SENTINEL_FILE_NAME = "p6_sentinels.json"

        /** App-process-wide publisher; COLD kills keep the thread count bounded. */
        private val SENTINEL_FILE_EXECUTOR: java.util.concurrent.ExecutorService =
            Executors.newSingleThreadExecutor { r -> Thread(r, "p6-sentinel-publish") }

        /** Mirror of the sentinel map, published to the shell-readable file. */
        private val sentinelMirror: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    }
}

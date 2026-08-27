package com.paherdin.record.benchmark

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.paherdin.record.core.data.timeline.DayHeader
import com.paherdin.record.core.data.timeline.ShadowProjectionHandle
import com.paherdin.record.core.data.timeline.TimelineCard
import com.paherdin.record.core.data.timeline.TimelineProjectionItem
import com.paherdin.record.core.data.timeline.TimelineShadowSeeder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout

/**
 * Tech PoC P6 Task 6R4-B — benchmark-only selected-day render surface.
 *
 * [TimelineRenderApp] is the render/load-mode entry point: it opens the
 * already-prepared shadow projection through the [TimelineShadowSeeder]
 * facade, runs the selected-day `TimelineQueryToRender` handshake (gate closes
 * only at the first root draw containing the current generation's expected
 * header/first card), then runs the one-day recorder traversal and exposes the
 * count/hash sentinels. A non-empty state is exactly 1 header + 10 cards; an
 * empty state renders the declared empty surface with no header/card.
 *
 * [TimelineReplacementApp] is the prepared replacement traversal entry point
 * (`mode=replace-ready`): it opens/seeds the DB outside measurement, publishes
 * no day state, stamps readiness, and waits for exactly one shell
 * `--activity-single-top` start with the matching run ID. It then visits all
 * 1,000 fixture dates by `LocalDate.minusDays(1)` with `Asia/Shanghai` bounds,
 * awaiting the generation-matched first correct draw before the next query,
 * tracking counters only (never accumulating prior lists or hashes in memory).
 * After the last correct draw the exact summary is buffered/finalized outside
 * any measured frame boundary. A second start for the same run ID fails closed
 * in the Activity.
 *
 * [TimelineRenderSurface] is the `LazyColumn`: one stable key per flattened
 * item and a header/card content type, rendering only the frozen fields. Every
 * composed item registers its canonical payload with the recorder when one is
 * supplied; the replacement traversal passes no recorder.
 */
@Composable
fun TimelineRenderApp(
    activity: TimelineBenchmarkActivity,
    selectedDate: String,
    dayStart: Long,
    nextDayStart: Long,
    wrongOrder: Boolean,
    loadOnly: Boolean = false,
    forceStateValidationFailure: Boolean = false,
) {
    val gate = remember { TimelineQueryToRenderGate() }
    val listState = rememberLazyListState()
    var renderState by remember { mutableStateOf<SelectedDayRenderState?>(null) }
    var status by remember { mutableStateOf("P6:loading") }
    val recorder = remember { TimelineRenderedPayloadRecorder(listState) }
    val firstCorrectDraw = remember { SelectedDayDrawSignal() }

    fun reportFailure(phase: String, t: Throwable) {
        val msg = "$phase failed: ${t::class.java.simpleName}: ${t.message}"
        activity.writeSentinel(TimelineBenchmarkActivity.SENTINEL_ERROR, msg)
        status = "P6:error $msg"
    }

    LaunchedEffect(Unit) {
        val date = LocalDate.parse(selectedDate)
        val expectedBounds = selectedDayBounds(date)
        check(dayStart == expectedBounds.first && nextDayStart == expectedBounds.second) {
            "declared bounds [$dayStart,$nextDayStart) do not match $date bounds " +
                "[${expectedBounds.first},${expectedBounds.second})"
        }
        val traceEnabled = date != EMPTY_REFERENCE_DATE
        val operationBefore = TimelineShadowSeeder.resourceSnapshot()
        val resourceBefore = operationBefore.takeIf { forceStateValidationFailure }
        // The frozen selected-day statement (single source of truth for Room's
        // @Query), so the device self-check asserts exactly what Room executes.
        activity.writeSentinel(
            TimelineBenchmarkActivity.SENTINEL_QUERY_SQL,
            TimelineShadowSeeder.SELECTED_DAY_QUERY_SQL,
        )

        // Live handle over the already-prepared shadow DB. Opening and the
        // row-count handshake complete OUTSIDE the measured section; only readSelectedDay() runs
        // inside the TimelineQueryToRender span. close() tears down the Room
        // connection pool / invalidation tracker / scope, so it must NOT land
        // between gate.start() and gate.end() (Hard-bug-1) — it is deferred
        // until after gate.end() fires inside the first correct draw callback.
        // Failure paths close directly: they involve no measurement, so where
        // they close is unconstrained.
        var handle: ShadowProjectionHandle? = null
        val loaded = try {
            val h = TimelineShadowSeeder.openPrepared(activity.applicationContext)
            handle = h
            activity.writeSentinel(
                TimelineBenchmarkActivity.SENTINEL_SEED_WRITE_COUNT,
                TimelineShadowSeeder.resourceSnapshot().seedWritesSince(operationBefore).toString(),
            )
            activity.bufferSentinel(
                TimelineBenchmarkActivity.SENTINEL_REFRESH_BEFORE_HZ,
                activity.currentRefreshHz(),
            )
            if (traceEnabled) gate.start()
            h.readSelectedDay(dayStart, nextDayStart)
        } catch (t: CancellationException) {
            handle?.close()
            resourceBefore?.let(activity::writeResourceDeltas)
            throw t
        } catch (t: Throwable) {
            handle?.close()
            reportFailure("load", t)
            resourceBefore?.let(activity::writeResourceDeltas)
            return@LaunchedEffect
        }

        val finalItems = try {
            val swapped = if (wrongOrder) {
                check(!forceStateValidationFailure) {
                    "injected wrong-order selected-day state validation failure"
                }
                swapOldestEqualTimePair(loaded)
            } else {
                loaded
            }
            validateSelectedDayRenderState(
                SelectedDayRenderState(generation = 1L, date = date, items = swapped),
            )
            swapped
        } catch (t: CancellationException) {
            handle?.close()
            resourceBefore?.let(activity::writeResourceDeltas)
            throw t
        } catch (t: Throwable) {
            handle?.close()
            reportFailure("prepare", t)
            resourceBefore?.let(activity::writeResourceDeltas)
            return@LaunchedEffect
        }

        val state = SelectedDayRenderState(generation = 1L, date = date, items = finalItems)
        val draw = firstCorrectDraw.expect(state, if (traceEnabled) gate else null)
        renderState = state

        // Wait for the real FIRST ROOT DRAW that proves the top header + first
        // card are the frame being drawn. The root drawBehind below fires the
        // one-shot signal on the MAIN thread AFTER layout, when the first card
        // key is part of that drawn frame. gate.end() now runs DIRECTLY inside
        // that draw callback (Hard-bug-2) at the exact first-correct-draw
        // moment — before the deferred wakes this coroutine — preserving
        // android.os.Trace's same-thread begin/end pairing. On resumption only
        // post-span work remains: closing the DB and flushing sentinels.
        try {
            withTimeout(30_000) { draw.await() }
            // gate.end() already fired inside the first correct drawBehind;
            // the Room teardown now executes strictly after gate.end().
            handle?.close()
        } catch (t: CancellationException) {
            handle?.close()
            resourceBefore?.let(activity::writeResourceDeltas)
            throw t
        } catch (t: Throwable) {
            // No confirmed first correct draw (timeout/failure): the span stays
            // open (a missing closed slice is the iteration-failure signal);
            // just close the DB so nothing leaks.
            handle?.close()
            reportFailure("trace-handshake", t)
            resourceBefore?.let(activity::writeResourceDeltas)
            return@LaunchedEffect
        }
        val result = try {
            recorder.run(finalItems.size)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            reportFailure("traversal", t)
            return@LaunchedEffect
        }
        activity.bufferSentinel(TimelineBenchmarkActivity.SENTINEL_SELECTED_DATE, selectedDate)
        activity.bufferSentinel(TimelineBenchmarkActivity.SENTINEL_DAY_START, dayStart.toString())
        activity.bufferSentinel(TimelineBenchmarkActivity.SENTINEL_NEXT_DAY_START, nextDayStart.toString())
        activity.bufferSentinel(TimelineBenchmarkActivity.SENTINEL_COUNT, result.capturedCount.toString())
        activity.bufferSentinel(TimelineBenchmarkActivity.SENTINEL_HASH, result.hash)
        val publishedDates = selectedDayDates(state)
        activity.bufferSentinel(
            TimelineBenchmarkActivity.SENTINEL_PUBLISHED_DATE_COUNT,
            publishedDates.size.toString(),
        )
        activity.bufferSentinel(
            TimelineBenchmarkActivity.SENTINEL_PUBLISHED_DATES,
            publishedDates.joinToString(","),
        )
        val cards = finalItems.filterIsInstance<TimelineCard>()
        if (cards.isNotEmpty()) {
            activity.bufferSentinel(TimelineBenchmarkActivity.SENTINEL_TOP_CARD, cards.first().id)
            activity.bufferSentinel(
                TimelineBenchmarkActivity.SENTINEL_TOP_HEADER_DATE,
                (finalItems.first() as DayHeader).date,
            )
            activity.bufferSentinel(TimelineBenchmarkActivity.SENTINEL_BOTTOM_CARD, cards.last().id)
        }
        activity.bufferSentinel(
            TimelineBenchmarkActivity.SENTINEL_REFRESH_AFTER_HZ,
            activity.currentRefreshHz(),
        )
        activity.flushBufferedSentinels()
        activity.writeSentinel(TimelineBenchmarkActivity.SENTINEL_READY, "true")
        if (traceEnabled) {
            activity.writeSentinel(TimelineBenchmarkActivity.SENTINEL_TRACE_OK, gate.ok.toString())
            activity.writeSentinel(
                TimelineBenchmarkActivity.SENTINEL_TRACE_MS,
                String.format(Locale.US, "%.2f", gate.durationMs()),
            )
        }
        status = "P6:count=${result.capturedCount} hash=${result.hash} " +
            "selectedDate=$selectedDate loadOnly=$loadOnly"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                renderState?.let { firstCorrectDraw.markDrawn(it, listState) }
            },
    ) {
        Text(status)
        val state = renderState
        TimelineRenderSurface(
            items = state?.items.orEmpty(),
            listState = listState,
            recorder = recorder,
            topCardId = (state?.items?.firstOrNull { it is TimelineCard } as? TimelineCard)?.id.orEmpty(),
            bottomCardId = (state?.items?.lastOrNull() as? TimelineCard)?.id.orEmpty(),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Task 6R4-B prepared replacement traversal. `mode=replace-ready` opens the
 * already-seeded shadow DB (no day state published), stamps readiness, and
 * waits on [startRequests] for exactly one `--activity-single-top` start whose
 * run ID the Activity already validated on the main thread. From that moment it
 * visits all fixture dates in descending order with `Asia/Shanghai` bounds,
 * publishes one selected-day state per date, and awaits the generation-matched
 * first correct draw before issuing the next query (one query in flight, no
 * prefetch, no cross-day concatenation). Counters only are tracked; the exact
 * frozen summary is buffered/finalized strictly after the last correct draw.
 */
@Composable
fun TimelineReplacementApp(
    activity: TimelineBenchmarkActivity,
    startRequests: ReceiveChannel<String>,
) {
    val listState = rememberLazyListState()
    var renderState by remember { mutableStateOf<SelectedDayRenderState?>(null) }
    var status by remember { mutableStateOf("P6:replace-ready") }
    val firstCorrectDraw = remember { SelectedDayDrawSignal() }

    LaunchedEffect(Unit) {
        var handle: ShadowProjectionHandle? = null
        try {
            handle = TimelineShadowSeeder.openPrepared(activity.applicationContext)
            activity.installReplacementHandle(handle)
            activity.writeSentinel(TimelineBenchmarkActivity.SENTINEL_REPLACE_READY, "true")
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            activity.writeSentinel(
                TimelineBenchmarkActivity.SENTINEL_ERROR,
                "replace-ready open failed: ${t.message ?: t::class.java.simpleName}",
            )
            status = "P6:error"
            return@LaunchedEffect
        }

        var drawCount = 0
        var replacementCount = 0
        var maxPublishedItemCount = 0
        var firstDate: String? = null
        var lastDate: String? = null
        var generation = 0L
        try {
            // One-shot start; runId already matched by the Activity.
            startRequests.receive()
            var date = LocalDate.parse(REPLACEMENT_FIRST_DATE)
            while (true) {
                val (dayStart, nextDayStart) = selectedDayBounds(date)
                val items = handle.readSelectedDay(dayStart, nextDayStart)
                maxPublishedItemCount = maxOf(maxPublishedItemCount, items.size)
                val state = SelectedDayRenderState(generation + 1L, date, items)
                validateSelectedDayRenderState(state)
                generation = state.generation
                val draw = firstCorrectDraw.expect(state, gate = null)
                renderState = state
                withTimeout(DRAW_TIMEOUT_MS) { draw.await() }
                drawCount++
                if (firstDate == null) firstDate = date.toString()
                lastDate = date.toString()
                if (date.toString() == REPLACEMENT_LAST_DATE) break
                date = date.minusDays(1)
            }
            replacementCount = drawCount - 1
            // Summary is buffered only after the last correct draw and persisted
            // outside any measured frame boundary.
            activity.bufferSentinel(
                TimelineBenchmarkActivity.SENTINEL_SELECTED_DAY_DRAW_COUNT,
                drawCount.toString(),
            )
            activity.bufferSentinel(
                TimelineBenchmarkActivity.SENTINEL_REPLACEMENT_COUNT,
                replacementCount.toString(),
            )
            activity.bufferSentinel(
                TimelineBenchmarkActivity.SENTINEL_MAX_PUBLISHED_ITEM_COUNT,
                maxPublishedItemCount.toString(),
            )
            activity.bufferSentinel(
                TimelineBenchmarkActivity.SENTINEL_PREFETCH_QUERY_COUNT,
                "0",
            )
            activity.bufferSentinel(
                TimelineBenchmarkActivity.SENTINEL_FIRST_DATE,
                checkNotNull(firstDate),
            )
            activity.bufferSentinel(
                TimelineBenchmarkActivity.SENTINEL_LAST_DATE,
                checkNotNull(lastDate),
            )
            activity.bufferSentinel(TimelineBenchmarkActivity.SENTINEL_TRAVERSAL_COMPLETE, "true")
            activity.flushBufferedSentinels()
            status = "P6:traversal-complete selectedDayDrawCount=$drawCount " +
                "replacementCount=$replacementCount firstDate=$firstDate lastDate=$lastDate"
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            activity.writeSentinel(
                TimelineBenchmarkActivity.SENTINEL_ERROR,
                "replacement traversal failed: ${t.message ?: t::class.java.simpleName}",
            )
            status = "P6:error"
        } finally {
            handle?.close()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                renderState?.let { firstCorrectDraw.markDrawn(it, listState) }
            },
    ) {
        Text(status)
        TimelineRenderSurface(
            items = renderState?.items.orEmpty(),
            listState = listState,
            recorder = null,
            topCardId = "",
            bottomCardId = "",
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One-shot, suspendable, main-thread draw signal. The root composable's
 * `drawBehind` invokes [markDrawnWhenFirstCardPresent] on the MAIN thread
 * during the draw phase of EVERY frame; it fires only on the first frame
 * whose drawn layout already contains the first card key — i.e. the real
 * first correct draw (spec §4.4), NOT layout visibility. On fire it calls
 * [TimelineQueryToRenderGate.end] DIRECTLY inside the draw callback — at the
 * exact first-correct-draw moment on the same main thread that ran
 * [TimelineQueryToRenderGate.start] — and only then completes the deferred so
 * the load coroutine resumes to run post-span work (DB close, sentinel flush).
 * Must not be fired more than once, so begin/end stays one pair per launch.
 */
private class SelectedDayDrawSignal {
    private data class PendingDraw(
        val generation: Long,
        val expectedKeys: Set<String>,
        val gate: TimelineQueryToRenderGate?,
        val deferred: kotlinx.coroutines.CompletableDeferred<Unit>,
    )

    private var pending: PendingDraw? = null
    private var lastGeneration = 0L

    fun expect(
        state: SelectedDayRenderState,
        gate: TimelineQueryToRenderGate?,
    ): kotlinx.coroutines.Deferred<Unit> {
        check(state.generation > lastGeneration) {
            "draw generation ${state.generation} must be newer than $lastGeneration"
        }
        check(pending == null) { "a selected-day draw is already pending" }
        val expectedKeys = if (state.items.isEmpty()) {
            emptySet()
        } else {
            setOf(itemKey(state.items[0]), itemKey(state.items[1]))
        }
        return kotlinx.coroutines.CompletableDeferred<Unit>().also { deferred ->
            pending = PendingDraw(state.generation, expectedKeys, gate, deferred)
        }
    }

    fun markDrawn(state: SelectedDayRenderState, listState: LazyListState) {
        val current = pending ?: return
        if (current.generation != state.generation) return
        val visibleKeys = listState.layoutInfo.visibleItemsInfo.mapTo(HashSet()) { it.key }
        if (current.expectedKeys.isNotEmpty() && !visibleKeys.containsAll(current.expectedKeys)) return
        current.gate?.end()
        lastGeneration = current.generation
        pending = null
        current.deferred.complete(Unit)
    }
}

/**
 * The benchmark `LazyColumn`: stable per-item keys, header/card content types,
 * only the frozen fields, and `testTagsAsResourceId` so the top/item/bottom
 * testTag sentinels are exposed to shell-side UIAutomator on firmware that can
 * see the window. The recorder is null for the replacement traversal (counters
 * only, never accumulating composed payloads).
 */
@Composable
private fun TimelineRenderSurface(
    items: List<TimelineProjectionItem>,
    listState: LazyListState,
    recorder: TimelineRenderedPayloadRecorder?,
    topCardId: String,
    bottomCardId: String,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .testTag("timeline-root")
            .semantics { testTagsAsResourceId = true },
    ) {
        items(
            count = items.size,
            key = { index -> itemKey(items[index]) },
            contentType = { index -> if (items[index] is DayHeader) "header" else "card" },
        ) { index ->
            when (val item = items[index]) {
                is DayHeader -> HeaderRow(header = item, index = index, recorder = recorder)
                is TimelineCard -> CardRow(
                    card = item,
                    index = index,
                    isTop = item.id == topCardId,
                    isBottom = item.id == bottomCardId,
                    recorder = recorder,
                )
            }
        }
    }
}

@Composable
private fun HeaderRow(header: DayHeader, index: Int, recorder: TimelineRenderedPayloadRecorder?) {
    val payload = remember(header) { encodeRenderedHeader(header.date) }
    SideEffect { recorder?.onItemComposed(index, payload) }
    Text(
        text = header.date,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("TimelineHeader:${header.date}"),
    )
}

@Composable
private fun CardRow(
    card: TimelineCard,
    index: Int,
    isTop: Boolean,
    isBottom: Boolean,
    recorder: TimelineRenderedPayloadRecorder?,
) {
    val payload = remember(card) { encodeRenderedCard(card) }
    SideEffect { recorder?.onItemComposed(index, payload) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("TimelineItem:${card.id}")
            .then(if (isTop) Modifier.testTag("TimelineTop:${card.id}") else Modifier)
            .then(if (isBottom) Modifier.testTag("TimelineBottom:${card.id}") else Modifier),
    ) {
        Text(card.title)
        Text("${card.type.wire} | ${card.occurredAt} | ${card.amountMinor}")
        val body = card.body
        if (body != null) Text(body)
    }
}

/**
 * App-side `TimelineQueryToRender` boundary. [start] fires on the MAIN thread
 * immediately before dispatching the selected-day DAO query; [end] fires on the
 * MAIN thread at the first correct draw (first card key laid out). They MUST be
 * called on the SAME thread: `android.os.Trace.beginSection/endSection` pair
 * per-thread, so a cross-thread begin/end leaves the slice open and
 * `TraceSectionMetric` counts zero sections (a D′-run defect this replacement
 * fixes). Missing/duplicate start, or end before start, surfaces as [ok] ==
 * false.
 *
 * Task 6 wraps the same boundary in `TraceSectionMetric("TimelineQueryToRender")`:
 * a successful start emits the platform trace section [SECTION_NAME] on the
 * main thread and a successful end closes it on the same thread, so
 * Macrobenchmark's metric finds exactly one closed slice per measured iteration
 * spanning query dispatch → query → state publish → first correct draw. The
 * uniqueness gate guarantees one begin/end pair per launch.
 */
class TimelineQueryToRenderGate {
    @Volatile private var startNanos = 0L
    @Volatile private var endNanos = 0L
    @Volatile private var failure: String? = null

    @Synchronized
    fun start() {
        if (failure != null) return
        if (startNanos != 0L) {
            failure = "duplicate start"
            return
        }
        startNanos = System.nanoTime()
        android.os.Trace.beginSection(SECTION_NAME)
    }

    @Synchronized
    fun end() {
        if (failure != null) return
        if (startNanos == 0L) {
            failure = "end before start"
            return
        }
        if (endNanos != 0L) {
            failure = "duplicate end"
            return
        }
        endNanos = System.nanoTime()
        android.os.Trace.endSection()
    }

    val ok: Boolean get() = failure == null && startNanos != 0L && endNanos != 0L
    val status: String get() = failure ?: if (ok) "ok" else "incomplete"

    fun durationMs(): Double =
        if (startNanos != 0L && endNanos != 0L) (endNanos - startNanos) / 1_000_000.0 else -1.0

    companion object {
        /** Must equal `CONTRACT.TRACE_METRIC` on the host validator side. */
        const val SECTION_NAME = "TimelineQueryToRender"
    }
}

// ---------------------------------------------------------------------------
// Task 6R4-B selected-day state model and helpers
// ---------------------------------------------------------------------------

/**
 * One immutable published selected-day state. A non-empty state is exactly one
 * matching [DayHeader] plus ten in-range cards; an empty state is the declared
 * empty surface (no header/card). [generation] strictly increases per state so
 * [SelectedDayDrawSignal] can match the current generation's first correct
 * draw.
 */
internal data class SelectedDayRenderState(
    val generation: Long,
    val date: LocalDate,
    val items: List<TimelineProjectionItem>,
)

/** Empty reference day `2022-12-31`; renders the declared empty surface. */
internal val EMPTY_REFERENCE_DATE: LocalDate = LocalDate.of(2022, 12, 31)

/** Asia/Shanghai half-open local-day bounds; never the device default zone. */
internal fun selectedDayBounds(date: LocalDate): Pair<Long, Long> {
    val zone = ZoneId.of("Asia/Shanghai")
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val nextDayStart = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return dayStart to nextDayStart
}

/**
 * Controlled within-day wrong-order injection: swaps the two cards tied at the
 * day's oldest `occurredAt` (the frozen fixture's slots 0/1 both sit at
 * `dayStart`). Both cards belong to the same selected day, so only their order
 * — and therefore the canonical hash — changes.
 */
internal fun swapOldestEqualTimePair(items: List<TimelineProjectionItem>): List<TimelineProjectionItem> {
    val cardIndices = items.mapIndexedNotNull { index, item -> if (item is TimelineCard) index else null }
    require(cardIndices.size >= 2) { "wrong-order swap requires at least two cards" }
    val oldestMs = cardIndices.minOf { (items[it] as TimelineCard).occurredAt }
    val tied = cardIndices.filter { (items[it] as TimelineCard).occurredAt == oldestMs }
    require(tied.size == 2) {
        "wrong-order swap requires exactly two oldest-time tied cards, found ${tied.size}"
    }
    val result = items.toMutableList()
    val (a, b) = tied
    val tmp = result[a]
    result[a] = result[b]
    result[b] = tmp
    return result
}

/**
 * Fail-closed validation of a published selected-day state: either the declared
 * empty state (no items), or exactly one matching header + ten in-range cards
 * all belonging to the state's local date. Cross-day or over-sized states are
 * rejected.
 */
internal fun validateSelectedDayRenderState(state: SelectedDayRenderState) {
    val items = state.items
    if (items.isEmpty()) return
    val bounds = selectedDayBounds(state.date)
    require(items.size == FROZEN_ITEMS_PER_DAY) {
        "selected-day state for ${state.date} has ${items.size} items, expected $FROZEN_ITEMS_PER_DAY"
    }
    val header = items.first()
    require(header is DayHeader && header.date == state.date.toString()) {
        "selected-day state must start with the matching DayHeader for ${state.date}"
    }
    require(items.count { it is DayHeader } == 1) {
        "selected-day state must have exactly one DayHeader"
    }
    val cards = items.filterIsInstance<TimelineCard>()
    require(cards.size == FROZEN_CARDS_PER_DAY) {
        "selected-day state must have exactly $FROZEN_CARDS_PER_DAY cards, found ${cards.size}"
    }
    for (card in cards) {
        require(card.occurredAt >= bounds.first && card.occurredAt < bounds.second) {
            "selected-day card ${card.id} is outside ${state.date} bounds " +
                "[${bounds.first},${bounds.second})"
        }
        require(localDateOf(card) == state.date) {
            "selected-day card ${card.id} is not a ${state.date} row"
        }
    }
}

/** Distinct local dates present in the published state (empty state -> empty). */
internal fun selectedDayDates(state: SelectedDayRenderState): List<String> {
    val dates = LinkedHashSet<String>()
    for (item in state.items) {
        when (item) {
            is DayHeader -> dates.add(item.date)
            is TimelineCard -> dates.add(localDateOf(item).format(DATE_FORMATTER))
        }
    }
    return dates.toList()
}

private fun localDateOf(card: TimelineCard): LocalDate =
    Instant.ofEpochMilli(card.occurredAt).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()

/** Stable per-flattened-item key (unique for headers and cards). */
internal fun itemKey(item: TimelineProjectionItem): String = when (item) {
    is DayHeader -> "h:${item.date}"
    is TimelineCard -> "c:${item.id}"
}

/**
 * Render-layer canonical encoding — an independent re-encoding of the frozen
 * oracle format, byte-identical to `TimelineOracle`/`TimelineTypedReader` but
 * produced only from composed item data. The recorder's hash therefore
 * evidences rendered output, never the typed-list path.
 */
internal fun encodeRenderedHeader(date: String): String = "H\t$date\n"

internal fun encodeRenderedCard(card: TimelineCard): String {
    val body = card.body
    return "C\t" + listOf(
        encodeField(card.id),
        encodeField(card.type.wire),
        encodeField(card.occurredAt.toString()),
        encodeField(card.title),
        if (card.amountMinor == null) "NULL" else encodeField(card.amountMinor.toString()),
        if (body == null) "-1:" else encodeField(body),
    ).joinToString("\t") + "\n"
}

private fun encodeField(value: String): String =
    "${value.toByteArray(Charsets.UTF_8).size}:$value"

private const val FROZEN_ITEMS_PER_DAY = 11
private const val FROZEN_CARDS_PER_DAY = 10
private const val REPLACEMENT_FIRST_DATE = "2025-09-26"
private const val REPLACEMENT_LAST_DATE = "2023-01-01"
private const val DRAW_TIMEOUT_MS = 30_000L
private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
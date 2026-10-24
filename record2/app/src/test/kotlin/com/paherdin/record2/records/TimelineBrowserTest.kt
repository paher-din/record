package com.paherdin.record2.records

import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineBrowserTest {
    private val generation = "00000000-0000-0000-0000-0000000000aa"
    private val zone = "Asia/Shanghai"

    @Test
    fun loadMoreAppendsPagesAndStopsAtEnd() = runBlocking {
        val service = FakeTimelineService(generation)
        val browser = TimelineBrowser(service, generation, zone)
        val firstEntry = transaction("00000000-0000-0000-0000-000000000001", 200)
        val secondEntry = note("00000000-0000-0000-0000-000000000002", 100)
        val first = page(firstEntry, next = cursor(firstEntry))
        val second = page(secondEntry)
        service.timelineHandler = { _, _, after -> if (after == null) first else second }

        assertTrue(browser.openNewest())
        assertEquals(listOf(firstEntry.id), browser.state.value.entries.map { it.id })
        assertTrue(browser.loadMore())
        assertEquals(listOf(firstEntry.id, secondEntry.id), browser.state.value.entries.map { it.id })
        assertNull(browser.state.value.next)
        assertFalse(browser.loadMore())
    }

    @Test
    fun lateLoadMoreIsDiscardedAfterFilterChange() = runBlocking {
        val service = FakeTimelineService(generation)
        val browser = TimelineBrowser(service, generation, zone)
        val firstEntry = transaction("00000000-0000-0000-0000-000000000001", 200)
        val first = page(firstEntry, next = cursor(firstEntry))
        service.timelineHandler = { _, _, after -> if (after == null) first else error("unexpected") }
        assertTrue(browser.openNewest(RecordKind.TRANSACTION))

        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<TimelinePage>()
        service.timelineHandler = { _, _, after ->
            if (after == null) {
                first
            } else {
                started.complete(Unit)
                gate.await()
            }
        }
        val stale = async { browser.loadMore() }
        started.await()

        val freshEntry = note("00000000-0000-0000-0000-000000000002", 300)
        service.timelineHandler = { _, _, _ -> page(freshEntry) }
        assertTrue(browser.setKind(RecordKind.NOTE))
        gate.complete(page(transaction("00000000-0000-0000-0000-000000000003", 400)))
        assertFalse(stale.await())

        assertEquals(RecordKind.NOTE, browser.state.value.query.kind)
        assertEquals(listOf(freshEntry.id), browser.state.value.entries.map { it.id })
        assertFalse(browser.state.value.loading)
    }

    @Test
    fun cursorExpiryKeepsReadableEntriesAndRefreshReloads() = runBlocking {
        val service = FakeTimelineService(generation)
        val browser = TimelineBrowser(service, generation, zone)
        val firstEntry = transaction("00000000-0000-0000-0000-000000000001", 200)
        service.timelineHandler = { _, _, after ->
            if (after == null) page(firstEntry, next = cursor(firstEntry)) else throw TimelineException(TimelineError.CursorExpired)
        }
        assertTrue(browser.openNewest())
        assertFalse(browser.loadMore())
        assertEquals(listOf(firstEntry.id), browser.state.value.entries.map { it.id })
        assertEquals(TimelineError.CursorExpired, browser.state.value.error)
        assertTrue(browser.state.value.requiresReload)
        assertNull(browser.state.value.next)

        val fresh = note("00000000-0000-0000-0000-000000000002", 300)
        service.timelineHandler = { _, _, _ -> page(fresh) }
        assertTrue(browser.refresh())
        assertEquals(listOf(fresh.id), browser.state.value.entries.map { it.id })
        assertFalse(browser.state.value.requiresReload)
        assertNull(browser.state.value.error)
    }

    @Test
    fun failedFilterChangeKeepsReadablePageAndRetryUsesPendingRequest() = runBlocking {
        val service = FakeTimelineService(generation)
        val browser = TimelineBrowser(service, generation, zone)
        val original = transaction("00000000-0000-0000-0000-000000000001", 200)
        service.timelineHandler = { _, _, _ -> page(original) }
        assertTrue(browser.openNewest(RecordKind.TRANSACTION))

        service.timelineHandler = { _, _, _ -> throw TimelineException(TimelineError.RecoverableIO) }
        assertFalse(browser.setKind(RecordKind.NOTE))
        assertEquals(listOf(original.id), browser.state.value.entries.map { it.id })
        assertEquals(RecordKind.TRANSACTION, browser.state.value.query.kind)
        assertEquals(TimelineError.RecoverableIO, browser.state.value.error)

        val fresh = note("00000000-0000-0000-0000-000000000002", 300)
        service.timelineHandler = { query, _, _ ->
            if (query.kind == RecordKind.NOTE) page(fresh) else error("retry did not keep the pending filter")
        }
        assertTrue(browser.retry())
        assertEquals(RecordKind.NOTE, browser.state.value.query.kind)
        assertEquals(listOf(fresh.id), browser.state.value.entries.map { it.id })
        assertNull(browser.state.value.error)
    }

    @Test
    fun jumpToAnchorsAtNextDayStartAndPreservesAnchorAcrossFilters() = runBlocking {
        val service = FakeTimelineService(generation)
        val browser = TimelineBrowser(service, generation, zone)
        val date = LocalDate.parse("2026-10-22")
        val beforeCalls = mutableListOf<Long>()
        val entry = transaction("00000000-0000-0000-0000-000000000001", 200)
        service.beforeHandler = { _, _, before ->
            beforeCalls += before
            page(entry)
        }

        assertTrue(browser.jumpTo(date, RecordKind.TRANSACTION))
        assertEquals(listOf(TimelineTime.nextDayStart(date, zone)), beforeCalls)
        assertEquals(date, browser.state.value.anchorDate)

        val noteEntry = note("00000000-0000-0000-0000-000000000002", 100)
        service.beforeHandler = { _, _, before ->
            beforeCalls += before
            page(noteEntry)
        }
        assertTrue(browser.setKind(RecordKind.NOTE))
        assertEquals(2, beforeCalls.size)
        assertEquals(TimelineTime.nextDayStart(date, zone), beforeCalls.last())
        assertEquals(date, browser.state.value.anchorDate)
        assertEquals(RecordKind.NOTE, browser.state.value.query.kind)

        service.timelineHandler = { _, _, _ -> page(entry) }
        assertTrue(browser.openNewest())
        assertNull(browser.state.value.anchorDate)
    }

    @Test
    fun staleBrowseDoesNotOverwriteNewerBrowse() = runBlocking {
        val service = FakeTimelineService(generation)
        val browser = TimelineBrowser(service, generation, zone)
        val slowStarted = CompletableDeferred<Unit>()
        val slowGate = CompletableDeferred<TimelinePage>()
        service.timelineHandler = { _, _, _ ->
            slowStarted.complete(Unit)
            slowGate.await()
        }
        val slow = async { browser.openNewest(RecordKind.TRANSACTION) }
        slowStarted.await()

        val fresh = note("00000000-0000-0000-0000-000000000002", 300)
        service.timelineHandler = { query, _, _ ->
            if (query.kind == RecordKind.NOTE) page(fresh) else error("unexpected")
        }
        assertTrue(browser.setKind(RecordKind.NOTE))
        slowGate.complete(page(transaction("00000000-0000-0000-0000-000000000001", 200)))
        assertFalse(slow.await())

        assertEquals(RecordKind.NOTE, browser.state.value.query.kind)
        assertEquals(listOf(fresh.id), browser.state.value.entries.map { it.id })
    }

    @Test
    fun rangeFilterIsPreservedAcrossKindChangesAndCanBeCleared() = runBlocking {
        val service = FakeTimelineService(generation)
        val browser = TimelineBrowser(service, generation, zone)
        val entry = transaction("00000000-0000-0000-0000-000000000001", 200)
        val range = TimelineRange(10, 20)
        service.timelineHandler = { query, _, _ ->
            assertEquals(range, query.range)
            page(entry)
        }
        assertTrue(browser.setRange(range))
        assertEquals(range, browser.state.value.query.range)

        val noteEntry = note("00000000-0000-0000-0000-000000000002", 100)
        service.timelineHandler = { query, _, _ ->
            assertEquals(range, query.range)
            page(noteEntry)
        }
        assertTrue(browser.setKind(RecordKind.NOTE))
        assertEquals(range, browser.state.value.query.range)
        assertEquals(RecordKind.NOTE, browser.state.value.query.kind)

        service.timelineHandler = { query, _, _ ->
            assertNull(query.range)
            page(entry)
        }
        assertTrue(browser.setRange(null))
        assertNull(browser.state.value.query.range)
        assertEquals(RecordKind.NOTE, browser.state.value.query.kind)
    }

    @Test
    fun loadMoreFailureRetriesTheSamePageInsteadOfReloadingTheFirstPage() = runBlocking {
        val service = FakeTimelineService(generation)
        val browser = TimelineBrowser(service, generation, zone)
        val firstEntry = transaction("00000000-0000-0000-0000-000000000001", 200)
        val secondEntry = note("00000000-0000-0000-0000-000000000002", 100)
        var failMore = true
        service.timelineHandler = { _, _, after ->
            if (after == null) {
                page(firstEntry, next = cursor(firstEntry))
            } else if (failMore) {
                failMore = false
                throw TimelineException(TimelineError.RecoverableIO)
            } else {
                page(secondEntry)
            }
        }
        assertTrue(browser.openNewest())
        assertFalse(browser.loadMore())
        assertTrue(browser.state.value.requiresReload.not())
        assertEquals(TimelineError.RecoverableIO, browser.state.value.error)
        assertTrue(browser.retry())
        assertEquals(listOf(firstEntry.id, secondEntry.id), browser.state.value.entries.map { it.id })
        assertNull(browser.state.value.error)
    }

    @Test
    fun loadMoreIsBlockedWhileAFilterChangeStillNeedsRetry() = runBlocking {
        val service = FakeTimelineService(generation)
        val browser = TimelineBrowser(service, generation, zone)
        val firstEntry = transaction("00000000-0000-0000-0000-000000000001", 200)
        service.timelineHandler = { _, _, after ->
            if (after == null) page(firstEntry, next = cursor(firstEntry))
            else throw TimelineException(TimelineError.RecoverableIO)
        }
        assertTrue(browser.openNewest(RecordKind.TRANSACTION))
        service.timelineHandler = { _, _, _ -> throw TimelineException(TimelineError.RecoverableIO) }
        assertFalse(browser.setKind(RecordKind.NOTE))
        assertFalse(browser.loadMore())
        assertEquals(TimelineError.RecoverableIO, browser.state.value.error)

        val fresh = note("00000000-0000-0000-0000-000000000002", 100)
        service.timelineHandler = { query, _, _ ->
            if (query.kind == RecordKind.NOTE) page(fresh) else error("unexpected")
        }
        assertTrue(browser.retry())
        assertEquals(RecordKind.NOTE, browser.state.value.query.kind)
        assertEquals(listOf(fresh.id), browser.state.value.entries.map { it.id })
    }

    @Test
    fun generationMismatchRequiresANewBrowserAndBlocksRefresh() = runBlocking {
        val service = FakeTimelineService(generation)
        val browser = TimelineBrowser(service, generation, zone)
        service.timelineHandler = { _, _, _ -> throw TimelineException(TimelineError.GenerationMismatch) }
        assertFalse(browser.openNewest())
        assertTrue(browser.state.value.requiresNewGeneration)
        assertFalse(browser.refresh())
        assertFalse(browser.retry())
    }

    private fun page(vararg entries: TimelineEntry, next: TimelineCursor? = null) =
        TimelinePage(generation, 0, entries.toList(), next)

    private fun cursor(entry: TimelineEntry) = TimelineCursor(
        occurredAt = entry.occurredAt,
        kind = if (entry is TransactionTimelineEntry) RecordKind.TRANSACTION else RecordKind.NOTE,
        id = entry.id,
        generation = generation,
        businessRevision = 0,
        filterDigest = "digest",
        cursorDigest = "digest",
    )

    private fun transaction(id: String, occurredAt: Long) = TransactionTimelineEntry(
        FinanceRecord(id, FinanceDirection.EXPENSE, 100, "account", null, "memo", occurredAt, 1, 0, 0),
    )

    private fun note(id: String, occurredAt: Long) = NoteTimelineEntry(
        NoteRecord(id, "body", occurredAt, 1, 0, 0, null),
    )

    private class FakeTimelineService(private val generation: String) : TimelineService {
        var timelineHandler: suspend (TimelineQuery, Int, TimelineCursor?) -> TimelinePage =
            { _, _, _ -> error("timeline not configured") }
        var beforeHandler: suspend (TimelineQuery, Int, Long) -> TimelinePage =
            { _, _, _ -> error("timelineBefore not configured") }

        override suspend fun timeline(
            generation: String,
            query: TimelineQuery,
            limit: Int,
            after: TimelineCursor?,
        ): TimelinePage = timelineHandler(query, limit, after)

        override suspend fun timelineBefore(
            generation: String,
            query: TimelineQuery,
            limit: Int,
            beforeExclusive: Long,
        ): TimelinePage = beforeHandler(query, limit, beforeExclusive)

        override suspend fun detail(generation: String, ref: RecordRef): RecordDetail =
            error("detail not configured")

        override suspend fun aggregateFinances(
            generation: String,
            range: TimelineRange,
            category: FinanceCategoryScope,
        ): FinanceAggregate = error("aggregate not configured")
    }
}

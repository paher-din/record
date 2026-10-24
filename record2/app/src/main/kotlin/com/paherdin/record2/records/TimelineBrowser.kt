package com.paherdin.record2.records

import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class TimelineState(
    val entries: List<TimelineEntry>,
    val query: TimelineQuery,
    val anchorDate: LocalDate? = null,
    val next: TimelineCursor? = null,
    val loading: Boolean = false,
    val error: TimelineError? = null,
    val requiresReload: Boolean = false,
    val requiresNewGeneration: Boolean = false,
)

// Non-visual timeline state; final pages, navigation and scroll restoration belong to F0/F1.
internal class TimelineBrowser(
    private val service: TimelineService,
    private val generation: String,
    zoneId: String,
) {
    private val zoneId = try {
        ZoneId.of(zoneId).id
    } catch (error: RuntimeException) {
        throw TimelineException(TimelineError.Validation, error)
    }
    private val mutableState = MutableStateFlow(
        TimelineState(entries = emptyList(), query = TimelineQuery(zoneId = this.zoneId)),
    )
    val state: StateFlow<TimelineState> = mutableState.asStateFlow()

    private val requests = AtomicLong(0)
    @Volatile private var pending: Pending? = null

    suspend fun openNewest(
        kind: RecordKind? = state.value.query.kind,
        keyword: String? = state.value.query.keyword,
    ): Boolean = browse(
        state.value.query.copy(kind = kind, keyword = keyword, zoneId = zoneId),
        null,
    )

    suspend fun jumpTo(
        date: LocalDate,
        kind: RecordKind? = state.value.query.kind,
        keyword: String? = state.value.query.keyword,
    ): Boolean = browse(
        state.value.query.copy(kind = kind, keyword = keyword, zoneId = zoneId),
        date,
    )

    suspend fun setRange(range: TimelineRange?): Boolean = browse(state.value.query.copy(range = range), null)

    suspend fun setKind(kind: RecordKind?): Boolean {
        val current = state.value
        return browse(current.query.copy(kind = kind), current.anchorDate)
    }

    suspend fun setKeyword(keyword: String?): Boolean {
        val current = state.value
        return browse(current.query.copy(keyword = keyword), current.anchorDate)
    }

    suspend fun loadMore(): Boolean {
        val current = state.value
        val cursor = current.next ?: return false
        if (current.loading || current.requiresReload || current.requiresNewGeneration) return false
        if (pending is Pending.Browse) return false
        pending = Pending.More(cursor)
        val token = requests.incrementAndGet()
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val page = service.timeline(generation, current.query, PAGE_SIZE, cursor)
            if (token != requests.get()) return false
            validatePage(page)
            pending = null
            mutableState.update {
                it.copy(entries = it.entries + page.entries, next = page.next, loading = false, error = null)
            }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TimelineException) {
            if (token != requests.get()) return false
            val terminal = error.code == TimelineError.CursorExpired ||
                error.code == TimelineError.GenerationMismatch ||
                error.code == TimelineError.RecoveryRequired
            if (terminal) pending = null
            mutableState.update {
                it.copy(
                    loading = false,
                    error = error.code,
                    requiresReload = error.code == TimelineError.CursorExpired,
                    requiresNewGeneration = error.code == TimelineError.GenerationMismatch,
                    next = if (terminal) null else it.next,
                )
            }
            return false
        } finally {
            if (token == requests.get()) mutableState.update { it.copy(loading = false) }
        }
    }

    suspend fun retry(): Boolean {
        val current = state.value
        if (current.requiresNewGeneration) return false
        if (current.requiresReload) return refresh()
        return when (val request = pending) {
            null -> refresh()
            is Pending.Browse -> browse(request.query, request.anchorDate)
            is Pending.More -> loadMore()
        }
    }

    suspend fun refresh(): Boolean {
        val current = state.value
        if (current.requiresNewGeneration) return false
        return browse(current.query, current.anchorDate)
    }

    private suspend fun browse(query: TimelineQuery, anchorDate: LocalDate?): Boolean {
        val canonical = try {
            TimelineQueryRules.canonical(query.copy(zoneId = zoneId))
        } catch (error: RuntimeException) {
            throw TimelineException(TimelineError.Validation, error)
        }
        val before = anchorDate?.let { TimelineTime.nextDayStart(it, zoneId) }
        pending = Pending.Browse(canonical, anchorDate)
        val token = requests.incrementAndGet()
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val page = if (before == null) {
                service.timeline(generation, canonical, PAGE_SIZE)
            } else {
                service.timelineBefore(generation, canonical, PAGE_SIZE, before)
            }
            if (token != requests.get()) return false
            validatePage(page)
            pending = null
            mutableState.update {
                it.copy(
                    entries = page.entries,
                    query = canonical,
                    anchorDate = anchorDate,
                    next = page.next,
                    loading = false,
                    error = null,
                    requiresReload = false,
                    requiresNewGeneration = false,
                )
            }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TimelineException) {
            if (token != requests.get()) return false
            mutableState.update {
                it.copy(
                    loading = false,
                    error = error.code,
                    requiresReload = error.code == TimelineError.CursorExpired,
                    requiresNewGeneration = error.code == TimelineError.GenerationMismatch,
                )
            }
            return false
        } finally {
            if (token == requests.get()) mutableState.update { it.copy(loading = false) }
        }
    }

    private fun validatePage(page: TimelinePage) {
        if (page.generation != generation) throw TimelineException(TimelineError.RecoveryRequired)
    }

    private sealed interface Pending {
        data class Browse(val query: TimelineQuery, val anchorDate: LocalDate?) : Pending
        data class More(val cursor: TimelineCursor) : Pending
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

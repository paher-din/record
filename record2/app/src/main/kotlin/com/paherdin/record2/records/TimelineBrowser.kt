package com.paherdin.record2.records

import java.time.LocalDate
import java.time.ZoneId
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

    // Registration and commit are short critical sections; the service call never runs under this lock.
    private val lock = Any()
    private var requestToken = 0L
    private var pending: Pending? = null

    suspend fun openNewest(): Boolean = browseFromCurrent { BrowseIntent(it.query, null) }

    suspend fun openNewest(kind: RecordKind?, keyword: String?): Boolean =
        browseFromCurrent { BrowseIntent(it.query.copy(kind = kind, keyword = keyword), null) }

    suspend fun jumpTo(date: LocalDate): Boolean = browseFromCurrent { BrowseIntent(it.query, date) }

    suspend fun jumpTo(date: LocalDate, kind: RecordKind?, keyword: String?): Boolean =
        browseFromCurrent { BrowseIntent(it.query.copy(kind = kind, keyword = keyword), date) }

    suspend fun setRange(range: TimelineRange?): Boolean =
        browseFromCurrent { BrowseIntent(it.query.copy(range = range), null) }

    suspend fun setKind(kind: RecordKind?): Boolean =
        browseFromCurrent { BrowseIntent(it.query.copy(kind = kind), it.anchorDate) }

    suspend fun setKeyword(keyword: String?): Boolean =
        browseFromCurrent { BrowseIntent(it.query.copy(keyword = keyword), it.anchorDate) }

    suspend fun loadMore(): Boolean {
        val registration = synchronized(lock) { registerMoreLocked() } ?: return false
        try {
            val page = service.timeline(generation, registration.query, PAGE_SIZE, registration.cursor)
            validatePage(page)
            return commitMore(registration, page)
        } catch (cancelled: CancellationException) {
            clearLoading(registration.token)
            throw cancelled
        } catch (error: TimelineException) {
            return commitError(registration.token, error.code, forLoadMore = true)
        } finally {
            clearLoading(registration.token)
        }
    }

    suspend fun retry(): Boolean {
        val current = synchronized(lock) { mutableState.value }
        if (current.requiresNewGeneration) return false
        return when (val request = synchronized(lock) { pending }) {
            is Pending.Browse -> browseExplicit(request.query, request.anchorDate)
            is Pending.More -> if (current.requiresReload) refresh() else loadMore()
            null -> refresh()
        }
    }

    suspend fun refresh(): Boolean {
        val blocked = synchronized(lock) { mutableState.value.requiresNewGeneration }
        if (blocked) return false
        return browseFromCurrent { BrowseIntent(it.query, it.anchorDate) }
    }

    private suspend fun browseFromCurrent(transform: (TimelineState) -> BrowseIntent): Boolean {
        val registration = synchronized(lock) {
            val intent = transform(mutableState.value)
            val canonical = try {
                TimelineQueryRules.canonical(intent.query.copy(zoneId = zoneId))
            } catch (error: RuntimeException) {
                throw TimelineException(TimelineError.Validation, error)
            }
            val before = intent.anchorDate?.let { TimelineTime.nextDayStart(it, zoneId) }
            registerBrowseLocked(canonical, intent.anchorDate, before)
        }
        return performBrowse(registration)
    }

    private suspend fun browseExplicit(query: TimelineQuery, anchorDate: LocalDate?): Boolean {
        val canonical = try {
            TimelineQueryRules.canonical(query.copy(zoneId = zoneId))
        } catch (error: RuntimeException) {
            throw TimelineException(TimelineError.Validation, error)
        }
        val before = anchorDate?.let { TimelineTime.nextDayStart(it, zoneId) }
        val registration = synchronized(lock) { registerBrowseLocked(canonical, anchorDate, before) }
        return performBrowse(registration)
    }

    private suspend fun performBrowse(registration: BrowseRegistration): Boolean {
        try {
            val page = if (registration.before == null) {
                service.timeline(generation, registration.query, PAGE_SIZE)
            } else {
                service.timelineBefore(generation, registration.query, PAGE_SIZE, registration.before)
            }
            validatePage(page)
            return commitBrowse(registration, page)
        } catch (cancelled: CancellationException) {
            clearLoading(registration.token)
            throw cancelled
        } catch (error: TimelineException) {
            return commitError(registration.token, error.code, forLoadMore = false)
        } finally {
            clearLoading(registration.token)
        }
    }

    private fun registerBrowseLocked(query: TimelineQuery, anchorDate: LocalDate?, before: Long?): BrowseRegistration {
        val token = ++requestToken
        pending = Pending.Browse(query, anchorDate)
        mutableState.update { it.copy(loading = true, error = null) }
        return BrowseRegistration(token, query, anchorDate, before)
    }

    private fun registerMoreLocked(): MoreRegistration? {
        val current = mutableState.value
        val cursor = current.next ?: return null
        if (current.loading || current.requiresReload || current.requiresNewGeneration) return null
        if (pending is Pending.Browse) return null
        val token = ++requestToken
        pending = Pending.More(cursor)
        mutableState.update { it.copy(loading = true, error = null) }
        return MoreRegistration(token, current.query, cursor)
    }

    private fun commitBrowse(registration: BrowseRegistration, page: TimelinePage): Boolean = synchronized(lock) {
        if (registration.token != requestToken) return false
        pending = null
        mutableState.update {
            it.copy(
                entries = page.entries,
                query = registration.query,
                anchorDate = registration.anchorDate,
                next = page.next,
                loading = false,
                error = null,
                requiresReload = false,
                requiresNewGeneration = false,
            )
        }
        true
    }

    private fun commitMore(registration: MoreRegistration, page: TimelinePage): Boolean = synchronized(lock) {
        if (registration.token != requestToken) return false
        pending = null
        mutableState.update {
            it.copy(entries = it.entries + page.entries, next = page.next, loading = false, error = null)
        }
        true
    }

    private fun commitError(token: Long, code: TimelineError, forLoadMore: Boolean): Boolean {
        synchronized(lock) {
            if (token != requestToken) return false
            val terminal = code == TimelineError.CursorExpired ||
                code == TimelineError.GenerationMismatch ||
                code == TimelineError.RecoveryRequired
            if (forLoadMore && terminal) pending = null
            mutableState.update {
                it.copy(
                    loading = false,
                    error = code,
                    requiresReload = code == TimelineError.CursorExpired,
                    requiresNewGeneration = code == TimelineError.GenerationMismatch,
                    next = if (forLoadMore && terminal) null else it.next,
                )
            }
        }
        return false
    }

    private fun clearLoading(token: Long) {
        synchronized(lock) {
            if (token == requestToken) mutableState.update { it.copy(loading = false) }
        }
    }

    private fun validatePage(page: TimelinePage) {
        if (page.generation != generation) throw TimelineException(TimelineError.RecoveryRequired)
    }

    private data class BrowseIntent(val query: TimelineQuery, val anchorDate: LocalDate?)

    private data class BrowseRegistration(
        val token: Long,
        val query: TimelineQuery,
        val anchorDate: LocalDate?,
        val before: Long?,
    )

    private data class MoreRegistration(
        val token: Long,
        val query: TimelineQuery,
        val cursor: TimelineCursor,
    )

    private sealed interface Pending {
        data class Browse(val query: TimelineQuery, val anchorDate: LocalDate?) : Pending
        data class More(val cursor: TimelineCursor) : Pending
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

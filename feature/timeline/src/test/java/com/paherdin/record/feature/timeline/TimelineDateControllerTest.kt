package com.paherdin.record.feature.timeline

import androidx.compose.runtime.saveable.SaverScope
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TimelineDateControllerTest {

    /** Accepts every value for the saver save-side call. */
    private object RoundTripScope : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, zoneId)

    /** 2026-08-31 23:59:59 Asia/Shanghai. */
    private val beforeMidnight = clockAt(Instant.parse("2026-08-31T15:59:59Z"))

    /** 2026-09-01 10:30 Asia/Shanghai. */
    private val septemberFirst = clockAt(Instant.parse("2026-09-01T02:30:00Z"))

    @Test
    fun initialWithoutRestoredStateUsesCurrentDayFromClock() {
        val state = TimelineDateController(septemberFirst, zoneId).initial(restored = null)

        assertEquals(LocalDate.of(2026, 9, 1), state.selectedDay)
        assertEquals(0L, state.requestRevision)
        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemOffset)
    }

    @Test
    fun initialWithRestoredStatePrefersRestoredDayOverClock() {
        val restored = TimelineDateState(
            LocalDate.of(2026, 8, 15),
            requestRevision = 42,
            firstVisibleItemIndex = 3,
            firstVisibleItemOffset = 7,
        )

        val state = TimelineDateController(septemberFirst, zoneId).initial(restored)

        assertEquals(restored, state)
    }

    @Test
    fun todayRereadsClockSelectingTheThenCurrentDay() {
        val controller = TimelineDateController(septemberFirst, zoneId)
        val initial = controller.initial(restored = null)
        // 2026-09-02 00:30 Asia/Shanghai.
        val nextDay = clockAt(Instant.parse("2026-09-01T16:30:00Z"))

        val state = TimelineDateController(nextDay, zoneId).today(initial)

        assertEquals(LocalDate.of(2026, 9, 2), state.selectedDay)
        assertEquals(1L, state.requestRevision)
    }

    @Test
    fun todayIncrementsRevisionOnEachCall() {
        val controller = TimelineDateController(septemberFirst, zoneId)
        val base = TimelineDateState(LocalDate.of(2026, 8, 1), requestRevision = 0, firstVisibleItemIndex = 0, firstVisibleItemOffset = 0)

        val state = controller.today(controller.today(base))

        assertEquals(2L, state.requestRevision)
    }

    @Test
    fun todayResetsScrollToTopAndPreservesEveryOtherField() {
        val scrolled = TimelineDateState(
            LocalDate.of(2026, 8, 20),
            requestRevision = 5,
            firstVisibleItemIndex = 12,
            firstVisibleItemOffset = 200,
        )

        val state = TimelineDateController(septemberFirst, zoneId).today(scrolled)

        // Applicable filter/context fields are preserved: the reducer changes only
        // the documented day, revision, and scroll fields.
        assertEquals(
            scrolled.copy(
                selectedDay = LocalDate.of(2026, 9, 1),
                requestRevision = 6,
                firstVisibleItemIndex = 0,
                firstVisibleItemOffset = 0,
            ),
            state,
        )
    }

    @Test
    fun viewedDayIsStableAcrossMidnightUntilExplicitToday() {
        val state = TimelineDateController(beforeMidnight, zoneId).initial(restored = null)
        assertEquals(LocalDate.of(2026, 8, 31), state.selectedDay)

        // Foreground midnight passes; the retained state object is unchanged.
        val afterMidnight = TimelineDateController(clockAt(Instant.parse("2026-08-31T16:00:01Z")), zoneId)
        assertEquals(LocalDate.of(2026, 8, 31), state.selectedDay)
        assertEquals(state, afterMidnight.initial(restored = state))
        assertNotEquals(LocalDate.of(2026, 9, 1), state.selectedDay)

        // Only an explicit Today re-read moves the viewed day.
        val refreshed = afterMidnight.today(state)
        assertEquals(LocalDate.of(2026, 9, 1), refreshed.selectedDay)
    }

    @Test
    fun dateStateSaverRoundTripsEveryField() {
        val original = TimelineDateState(
            LocalDate.of(2026, 9, 1),
            requestRevision = 7,
            firstVisibleItemIndex = 2,
            firstVisibleItemOffset = 150,
        )

        // This toolchain's K2 cannot resolve Saver.save calls with an explicit
        // dispatch receiver (main-agent probe reproduced every form); the
        // nested `with` keeps both receivers implicit, the minimal compilable
        // shape. If Kotlin is upgraded to fix resolution, call directly.
        val saved: Any? = with(TimelineDateStateSaver) {
            with(RoundTripScope) { save(original) }
        }
        val restored = TimelineDateStateSaver.restore(checkNotNull(saved))

        assertEquals(original, restored)
    }

    @Test
    fun dateStateSaverRestoresNumericValuesAsIntOrLong() {
        // Restore must accept numeric save entries arriving as either Int or Long.
        val withLongFields = TimelineDateStateSaver.restore(listOf("2026-09-01", 7L, 2, 150))
        assertEquals(TimelineDateState(LocalDate.of(2026, 9, 1), 7, 2, 150), withLongFields)

        val withIntFields = TimelineDateStateSaver.restore(listOf("2026-09-01", 7, 2, 150))
        assertEquals(TimelineDateState(LocalDate.of(2026, 9, 1), 7, 2, 150), withIntFields)
    }
}

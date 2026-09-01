package com.paherdin.record.feature.timeline

import androidx.compose.runtime.saveable.listSaver
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * The retained Timeline surface state. [selectedDay] is the viewed day;
 * [requestRevision] invalidates obsolete date-bound requests; the scroll pair
 * restores the list position.
 */
data class TimelineDateState(
    val selectedDay: LocalDate,
    val requestRevision: Long,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemOffset: Int,
)

/**
 * The pure reducer over [TimelineDateState]. [initial] prefers legal restored
 * state over the clock; [today] re-reads the injected time sources, selects
 * the then-current local date, invalidates the previous request revision, and
 * returns the list to the top while preserving every other field (applicable
 * filters included). Foreground midnight and ordinary surface reads never
 * change the viewed day.
 */
class TimelineDateController(private val clock: Clock, private val zoneId: ZoneId) {
    fun initial(restored: TimelineDateState?): TimelineDateState = restored ?: TimelineDateState(
        LocalDate.now(clock.withZone(zoneId)), 0, 0, 0,
    )

    fun today(current: TimelineDateState): TimelineDateState = current.copy(
        selectedDay = LocalDate.now(clock.withZone(zoneId)),
        requestRevision = current.requestRevision + 1,
        firstVisibleItemIndex = 0,
        firstVisibleItemOffset = 0,
    )
}

/** Compose saveable round trip for [TimelineDateState]. */
val TimelineDateStateSaver = listSaver<TimelineDateState, Any>(
    save = { listOf(it.selectedDay.toString(), it.requestRevision, it.firstVisibleItemIndex, it.firstVisibleItemOffset) },
    restore = {
        TimelineDateState(
            selectedDay = LocalDate.parse(it[0] as String),
            requestRevision = (it[1] as Number).toLong(),
            firstVisibleItemIndex = (it[2] as Number).toInt(),
            firstVisibleItemOffset = (it[3] as Number).toInt(),
        )
    },
)

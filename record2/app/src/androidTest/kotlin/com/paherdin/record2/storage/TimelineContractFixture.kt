package com.paherdin.record2.storage

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.TimelineError
import com.paherdin.record2.records.TimelineException
import com.paherdin.record2.records.TimelineRange
import com.paherdin.record2.records.TimelineTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal val timelineClock: Clock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC)
internal val timelineWriteClock: Clock = Clock.offset(timelineClock, Duration.ofSeconds(1))
internal const val timelineZone = "Asia/Shanghai"

internal fun timelineDay(date: String): Long = TimelineTime.dayStart(LocalDate.parse(date), timelineZone)

internal fun timelineRange(start: String, endExclusive: String): TimelineRange =
    TimelineTime.range(LocalDate.parse(start), LocalDate.parse(endExclusive), timelineZone)

internal fun timelineDatabase(name: String): RecordDatabase {
    check(Regex("b4-(contract|perf)-[a-z0-9-]+\\.db").matches(name))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == "com.paherdin.record2.debug")
    return Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
}

internal suspend fun expectTimelineError(code: TimelineError, block: suspend () -> Unit) {
    val error = try { block(); error("Expected $code") } catch (error: TimelineException) { error }
    org.junit.Assert.assertEquals(code, error.code)
}

package com.paherdin.record2.storage

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.LinkError
import com.paherdin.record2.records.LinkException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

internal val linkClock: Clock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC)
internal val linkWriteClock: Clock = Clock.offset(linkClock, Duration.ofSeconds(1))
internal const val linkDayOne = 1_792_663_200_000L
internal const val linkDayTwo = 1_792_749_600_000L
internal const val linkDayThree = 1_792_832_400_000L
internal const val linkNoteOneBody = "第一天笔记\n原文  "
internal const val linkNoteTwoBody = "第二天笔记"
internal const val linkNoteThreeBody = "第三天候选"

internal fun linkDatabase(name: String): RecordDatabase {
    check(Regex("b3-(contract|process)-[a-z0-9-]+\\.db").matches(name))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == "com.paherdin.record2.debug")
    return Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
}

internal suspend fun expectLinkError(code: LinkError, block: suspend () -> Unit) {
    val error = try { block(); error("Expected $code") } catch (error: LinkException) { error }
    org.junit.Assert.assertEquals(code, error.code)
}

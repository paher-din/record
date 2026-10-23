package com.paherdin.record2.storage

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal val noteClock: Clock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC)
internal const val noteOccurredAt = 1_792_729_800_000L
internal const val noteOriginalBody = "中文原文\n**Markdown符号** 与空格  \n第二段"
internal const val noteUpdatedBody = "新的正文\n换行与符号 `[]` 原样保留  "

internal fun noteDatabase(name: String): RecordDatabase {
    check(Regex("b2-contract-[a-z0-9-]+\\.db").matches(name))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == "com.paherdin.record2.debug")
    return Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
}

internal suspend fun expectNoteError(code: NoteError, block: suspend () -> Unit) {
    val error = try { block(); error("Expected $code") } catch (error: NoteException) { error }
    org.junit.Assert.assertEquals(code, error.code)
}

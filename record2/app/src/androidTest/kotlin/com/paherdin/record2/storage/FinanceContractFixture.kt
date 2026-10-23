package com.paherdin.record2.storage

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal val financeClock: Clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)

internal fun financeInput(catalog: FinanceCatalog, amount: String = "58.00") = FinanceInput(
    amount, FinanceDirection.EXPENSE, catalog.account.id, catalog.account.version,
    null, null, "午饭\n原样保留  ", "2026-10-23T12:30:00", "Asia/Shanghai",
)

internal fun financeDatabase(name: String): RecordDatabase {
    check(Regex("b1-contract-[a-z0-9-]+\\.db").matches(name))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == "com.paherdin.record2.debug")
    return Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
}

internal suspend fun expectFinanceError(code: FinanceError, block: suspend () -> Unit) {
    val error = try { block(); error("Expected $code") } catch (error: FinanceException) { error }
    org.junit.Assert.assertEquals(code, error.code)
}

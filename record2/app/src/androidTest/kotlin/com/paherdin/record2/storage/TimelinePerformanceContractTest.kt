package com.paherdin.record2.storage

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.*
import java.math.BigInteger
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimelinePerformanceContractTest {
    private val name = "b4-perf-${schemaId()}.db"
    private val path get() = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)
    private lateinit var database: RecordDatabase
    private lateinit var timeline: RoomTimelineService
    private lateinit var generation: String

    @Before fun open() = runBlocking {
        check(!path.exists())
        database = timelineDatabase(name)
        generation = RoomFinanceService(database, timelineClock).initialize().generation
        timeline = RoomTimelineService(database)
    }

    @After fun close() { database.close() }

    @Test fun typicalDatasetBaseline() = runBlocking {
        measure("typical", transactionCount = 5_000, noteCount = 2_000, pages = 10, budget = Budget(100, 100, 100, 250))
    }

    @Test fun highVolumeDatasetBaseline() = runBlocking {
        measure("high", transactionCount = 30_000, noteCount = 10_000, pages = 20, budget = Budget(200, 100, 150, 1_000))
    }

    private suspend fun measure(
        label: String,
        transactionCount: Int,
        noteCount: Int,
        pages: Int,
        budget: Budget,
    ) {
        val base = timelineDay("2025-10-24")
        val transactionStep = 600_000L
        val noteStep = 1_800_000L
        val now = timelineClock.millis()
        database.withTransaction {
            val dao = database.records()
            for (index in 0 until transactionCount) {
                dao.insertTransaction(
                    TransactionEntity(
                        id = UUID(4, (index + 1).toLong()).toString(),
                        direction = if (index % 2 == 0) "INCOME" else "EXPENSE",
                        amountMinor = 1_000L + (index % 9_000),
                        currency = "CNY",
                        accountId = FinanceSeeds.ACCOUNT_ID,
                        categoryId = null,
                        memo = if (index % 500 == 0) "needle 检索关键词" else "memo $index",
                        occurredAt = base + index * transactionStep,
                        version = 1,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            for (index in 0 until noteCount) {
                dao.insertNote(
                    NoteEntity(
                        id = UUID(3, (index + 1).toLong()).toString(),
                        body = if (index % 1_000 == 0) "needle note\n第二行" else "note $index",
                        occurredAt = base + index * noteStep,
                        version = 1,
                        createdAt = now,
                        updatedAt = now,
                        deletedAt = null,
                    ),
                )
            }
        }

        database.close()
        database = timelineDatabase(name)
        timeline = RoomTimelineService(database)

        val firstStart = System.nanoTime()
        val first = timeline.timeline(generation, TimelineQuery(), 50)
        val firstMs = elapsedMillis(firstStart)
        assertEquals(50, first.entries.size)
        assertTrue(first.next != null)

        var cursor = first.next
        var pageCount = 1
        var totalEntries = first.entries.size
        val collected = first.entries.mapTo(mutableSetOf()) { it.id }
        var maxScrollMs = 0L
        val scrollStart = System.nanoTime()
        while (cursor != null && pageCount < pages) {
            val pageStart = System.nanoTime()
            val page = timeline.timeline(generation, TimelineQuery(), 50, cursor)
            maxScrollMs = maxOf(maxScrollMs, elapsedMillis(pageStart))
            page.entries.forEach { assertTrue(collected.add(it.id)) }
            totalEntries += page.entries.size
            cursor = page.next
            pageCount++
        }
        val scrollTotalMs = elapsedMillis(scrollStart)
        assertEquals(pages, pageCount)
        assertEquals(pages * 50, collected.size)
        assertEquals(totalEntries, collected.size)

        val searchStart = System.nanoTime()
        val search = timeline.timeline(
            generation,
            TimelineQuery(kind = RecordKind.NOTE, keyword = "needle"),
            20,
        )
        val searchMs = elapsedMillis(searchStart)
        assertEquals((noteCount + 999) / 1_000, search.entries.size)

        val aggregateStart = System.nanoTime()
        val aggregate = timeline.aggregateFinances(
            generation,
            TimelineRange(base, base + transactionCount * transactionStep + 1),
        )
        val aggregateMs = elapsedMillis(aggregateStart)
        var expectedIncome = BigInteger.ZERO
        var expectedExpense = BigInteger.ZERO
        var expectedIncomeCount = 0L
        var expectedExpenseCount = 0L
        for (index in 0 until transactionCount) {
            val amount = BigInteger.valueOf(1_000L + (index % 9_000))
            if (index % 2 == 0) {
                expectedIncome += amount
                expectedIncomeCount++
            } else {
                expectedExpense += amount
                expectedExpenseCount++
            }
        }
        assertEquals(expectedIncome, BigInteger.valueOf(aggregate.incomeMinor))
        assertEquals(expectedExpense, BigInteger.valueOf(aggregate.expenseMinor))
        assertEquals(expectedIncomeCount, aggregate.incomeCount)
        assertEquals(expectedExpenseCount, aggregate.expenseCount)

        assertTrue("first ${firstMs}ms exceeds ${budget.first}ms", firstMs <= budget.first)
        assertTrue("scroll max ${maxScrollMs}ms exceeds ${budget.scroll}ms", maxScrollMs <= budget.scroll)
        assertTrue("search ${searchMs}ms exceeds ${budget.search}ms", searchMs <= budget.search)
        assertTrue("aggregate ${aggregateMs}ms exceeds ${budget.aggregate}ms", aggregateMs <= budget.aggregate)

        Log.i(
            TAG,
            "$label tx=$transactionCount notes=$noteCount first_ms=$firstMs " +
                "scroll_pages=$pageCount scroll_total_ms=$scrollTotalMs scroll_max_ms=$maxScrollMs " +
                "search_ms=$searchMs aggregate_ms=$aggregateMs " +
                "budget_first_ms=${budget.first} budget_scroll_ms=${budget.scroll} " +
                "budget_search_ms=${budget.search} budget_aggregate_ms=${budget.aggregate}",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.openFileOutput("b4-perf-$label.txt", Context.MODE_PRIVATE).use { stream ->
            stream.write(
                ("label=$label tx=$transactionCount notes=$noteCount first_ms=$firstMs " +
                    "scroll_pages=$pageCount scroll_total_ms=$scrollTotalMs scroll_max_ms=$maxScrollMs " +
                    "search_ms=$searchMs aggregate_ms=$aggregateMs " +
                    "budget_first_ms=${budget.first} budget_scroll_ms=${budget.scroll} " +
                    "budget_search_ms=${budget.search} budget_aggregate_ms=${budget.aggregate}\n").toByteArray(),
            )
        }
    }

    private data class Budget(val first: Long, val scroll: Long, val search: Long, val aggregate: Long)

    private fun elapsedMillis(start: Long): Long = (System.nanoTime() - start) / 1_000_000

    private companion object {
        const val TAG = "B4_PERF"
    }
}

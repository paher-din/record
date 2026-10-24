package com.paherdin.record2.storage

import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.*
import java.math.BigInteger
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TimelineServiceContractTest {
    private val name = "b4-contract-${schemaId()}.db"
    private val path get() = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)
    private lateinit var database: RecordDatabase
    private lateinit var timeline: RoomTimelineService
    private lateinit var notes: RoomNoteService
    private lateinit var finance: RoomFinanceService
    private lateinit var links: RoomLinkService
    private lateinit var catalog: FinanceCatalog
    private lateinit var generation: String

    @Before fun open() = runBlocking {
        check(!path.exists())
        database = timelineDatabase(name)
        finance = RoomFinanceService(database, timelineClock)
        catalog = finance.initialize()
        generation = catalog.generation
        timeline = RoomTimelineService(database)
        notes = RoomNoteService(database, timelineClock)
        links = RoomLinkService(database, timelineWriteClock)
    }

    @After fun close() { database.close() }

    private fun revision(): Long =
        SchemaDiskObserver.snapshot(path).getValue("record_data_state").single()[2] as Long

    private fun insertTransaction(
        occurredAt: Long,
        direction: String = "EXPENSE",
        amountMinor: Long = 5800,
        categoryId: String? = null,
        memo: String = "fixture",
        id: String = schemaId(),
    ): String {
        database.records().insertTransaction(
            TransactionEntity(
                id, direction, amountMinor, "CNY", FinanceSeeds.ACCOUNT_ID, categoryId, memo,
                occurredAt, 1, timelineClock.millis(), timelineClock.millis(),
            ),
        )
        return id
    }

    private suspend fun createNote(occurredAt: Long, body: String, id: String = schemaId()): NoteRecord =
        notes.save(generation, id, null, body, occurredAt)!!

    private suspend fun query(
        keyword: String? = null,
        kind: RecordKind? = null,
        range: TimelineRange? = null,
        limit: Int = 50,
        after: TimelineCursor? = null,
        zoneId: String? = null,
    ): TimelinePage = timeline.timeline(generation, TimelineQuery(kind, keyword, range, zoneId), limit, after)

    private fun ids(page: TimelinePage) = page.entries.map { it.id }

    @Test
    fun mixedTimelineUsesStableTotalOrderAndKeysetPages() = runBlocking {
        val dayOne = timelineDay("2026-10-22")
        val dayTwo = timelineDay("2026-10-23")
        val txA = insertTransaction(dayTwo + 1_000, id = "00000000-0000-0000-0000-00000000000a")
        val txB = insertTransaction(dayTwo + 1_000, id = "00000000-0000-0000-0000-00000000000b")
        val noteC = createNote(dayTwo + 1_000, "note c", "00000000-0000-0000-0000-00000000000c")
        val noteD = createNote(dayTwo + 1_000, "note d", "00000000-0000-0000-0000-00000000000d")
        val txE = insertTransaction(dayOne + 1_000, id = "00000000-0000-0000-0000-00000000000e")
        val noteF = createNote(dayOne + 1_000, "note f", "00000000-0000-0000-0000-00000000000f")
        val expected = listOf(txA, txB, noteC.id, noteD.id, txE, noteF.id)

        val collected = mutableListOf<String>()
        var cursor: TimelineCursor? = null
        do {
            val page = query(limit = 2, after = cursor)
            assertTrue(page.entries.size <= 2)
            assertEquals(revision(), page.businessRevision)
            collected += ids(page)
            cursor = page.next
        } while (cursor != null)
        assertEquals(expected, collected)
        assertEquals(expected.size, collected.toSet().size)

        val emptyRange = TimelineRange(dayOne - 10, dayOne - 1)
        val empty = query(range = emptyRange)
        assertTrue(empty.entries.isEmpty())
        assertNull(empty.next)
    }

    @Test
    fun keywordSearchMatchesChineseNewlinesAndAsciiCaseLiterally() = runBlocking {
        val day = timelineDay("2026-10-23")
        val transaction = insertTransaction(day + 1_000, memo = "午餐\nDeepSeek API 100%_done")
        val note = createNote(day + 2_000, "中文原文\n第二行 ABC 100%_done")

        assertEquals(listOf(note.id), ids(query(keyword = "中文")))
        assertEquals(listOf(note.id), ids(query(keyword = "\n第二")))
        assertEquals(listOf(note.id), ids(query(keyword = "abc")))
        assertEquals(ids(query(keyword = "abc")), ids(query(keyword = "ABC")))
        assertEquals(listOf(transaction), ids(query(keyword = "deepseek")))
        assertEquals(ids(query(keyword = "deepseek")), ids(query(keyword = "DEEPSEEK")))
        assertEquals(setOf(transaction, note.id), ids(query(keyword = "100%_")).toSet())
        assertEquals(setOf(transaction, note.id), ids(query(keyword = "_")).toSet())
        assertEquals(setOf(transaction, note.id), ids(query(keyword = "%")).toSet())
        assertTrue(query(keyword = "不存在").entries.isEmpty())
        assertEquals(setOf(transaction, note.id), ids(query(keyword = "   ")).toSet())
        assertEquals(listOf(transaction), ids(query(keyword = "deepseek", kind = RecordKind.TRANSACTION)))
        expectTimelineError(TimelineError.Validation) {
            timeline.timeline(generation, TimelineQuery(keyword = "a".repeat(1_001)), 2)
        }

        notes.delete(generation, note.id, note.version)
        assertTrue(query(keyword = "中文").entries.isEmpty())
        assertEquals(listOf(transaction), ids(query()))
        notes.restore(generation, note.id, 2)
        assertEquals(listOf(note.id), ids(query(keyword = "中文")))
    }

    @Test
    fun keywordPaginationKeepsTiesStableAcrossPages() = runBlocking {
        val day = timelineDay("2026-10-23")
        val matching = mutableListOf<String>()
        repeat(6) { matching += createNote(day + 1_000, "needle $it").id }
        repeat(3) { createNote(day + 2_000, "other $it") }

        val collected = mutableListOf<String>()
        var cursor: TimelineCursor? = null
        do {
            val page = timeline.timeline(generation, TimelineQuery(keyword = "NEEDLE"), 2, cursor)
            collected += ids(page)
            cursor = page.next
        } while (cursor != null)

        assertEquals(matching.sorted(), collected)
        assertEquals(matching.toSet(), collected.toSet())
    }

    @Test
    fun deletedNoteIsHiddenFromTimelineSearchAndLinkDetailsUntilRestored() = runBlocking {
        val day = timelineDay("2026-10-23")
        val transaction = insertTransaction(day + 1_000)
        val note = createNote(day + 2_000, "linked body")
        val linked = links.apply(
            generation, transaction, 1,
            listOf(LinkNoteRef(note.id, note.version)), emptyList(),
        )
        val linkedPage = query()
        assertEquals(1, linkedPage.entries.single { it.id == transaction }.visibleLinkCount)
        assertEquals(1, linkedPage.entries.single { it.id == note.id }.visibleLinkCount)

        val transactionDetail = timeline.detail(generation, RecordRef.Transaction(transaction)) as TransactionDetail
        assertEquals(listOf(note.id), transactionDetail.notes.map { it.id })
        assertEquals(1, transactionDetail.visibleLinkCount)
        assertEquals(linked.transaction.version, transactionDetail.version)
        val noteDetail = timeline.detail(generation, RecordRef.Note(note.id)) as NoteDetail
        assertEquals(listOf(transaction), noteDetail.transactions.map { it.id })
        assertEquals(1, noteDetail.visibleLinkCount)

        val deleted = notes.delete(generation, note.id, linked.notes.single().version)
        expectTimelineError(TimelineError.NotVisible) {
            timeline.detail(generation, RecordRef.Note(note.id))
        }
        val hidden = timeline.detail(generation, RecordRef.Transaction(transaction)) as TransactionDetail
        assertTrue(hidden.notes.isEmpty())
        assertEquals(0, hidden.visibleLinkCount)
        assertFalse(ids(query()).contains(note.id))
        assertEquals(0, query().entries.single { it.id == transaction }.visibleLinkCount)
        assertTrue(query(keyword = "linked").entries.isEmpty())

        notes.restore(generation, note.id, deleted.note.version)
        val restored = timeline.detail(generation, RecordRef.Transaction(transaction)) as TransactionDetail
        assertEquals(listOf(note.id), restored.notes.map { it.id })
        assertEquals(1, restored.visibleLinkCount)
        assertEquals(1, query().entries.single { it.id == transaction }.visibleLinkCount)
        assertEquals(listOf(note.id), ids(query(keyword = "linked")))

        expectTimelineError(TimelineError.NotFound) {
            timeline.detail(generation, RecordRef.Note(schemaId()))
        }
        expectTimelineError(TimelineError.Validation) {
            timeline.detail(generation, RecordRef.Note("not-a-uuid"))
        }
    }

    @Test
    fun dateRangesAreHalfOpenAndUseTheRequestedZone() = runBlocking {
        val dayStart = timelineDay("2026-10-23")
        val nextDayStart = timelineDay("2026-10-24")
        val atStart = insertTransaction(dayStart)
        val beforeEnd = insertTransaction(nextDayStart - 1)
        val atEnd = insertTransaction(nextDayStart)
        val range = timelineRange("2026-10-23", "2026-10-24")

        assertEquals(setOf(atStart, beforeEnd), ids(query(range = range)).toSet())
        assertFalse(ids(query(range = range)).contains(atEnd))
        assertEquals(listOf(beforeEnd, atStart), ids(query(range = range)))

        val shanghaiMidnight = timelineDay("2026-10-24") + 30 * 60 * 1000L
        val boundary = insertTransaction(shanghaiMidnight)
        val utcDay23 = TimelineTime.range(
            LocalDate.parse("2026-10-23"), LocalDate.parse("2026-10-24"), "UTC",
        )
        val utcDay24 = TimelineTime.range(
            LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-25"), "UTC",
        )
        assertTrue(ids(query(range = timelineRange("2026-10-24", "2026-10-25"))).contains(boundary))
        assertTrue(ids(query(range = utcDay23)).contains(boundary))
        assertFalse(ids(query(range = utcDay24)).contains(boundary))
    }

    @Test
    fun cursorExpiresAfterBusinessChangesAndOnFilterMismatch() = runBlocking {
        val day = timelineDay("2026-10-23")
        repeat(5) { createNote(day + it * 1_000L, "note $it") }
        val base = TimelineQuery(zoneId = timelineZone)
        val first = timeline.timeline(generation, base, 2)
        val cursor = first.next ?: error("Expected a next cursor")
        val second = timeline.timeline(generation, base, 2, cursor)
        assertTrue(ids(first).intersect(ids(second).toSet()).isEmpty())

        listOf(
            base.copy(kind = RecordKind.NOTE),
            base.copy(keyword = "note"),
            base.copy(range = TimelineRange(0, 1)),
            base.copy(zoneId = "UTC"),
        ).forEach { changed ->
            expectTimelineError(TimelineError.CursorExpired) {
                timeline.timeline(generation, changed, 2, cursor)
            }
        }
        listOf(
            cursor.copy(occurredAt = cursor.occurredAt + 1),
            cursor.copy(kind = if (cursor.kind == RecordKind.TRANSACTION) RecordKind.NOTE else RecordKind.TRANSACTION),
            cursor.copy(id = schemaId()),
            cursor.copy(id = "not-a-uuid"),
            cursor.copy(id = ""),
            cursor.copy(businessRevision = cursor.businessRevision + 1),
            cursor.copy(cursorDigest = "0".repeat(64)),
        ).forEach { tampered ->
            expectTimelineError(TimelineError.CursorExpired) {
                timeline.timeline(generation, base, 2, tampered)
            }
        }
        expectTimelineError(TimelineError.GenerationMismatch) {
            timeline.timeline(schemaId(), base, 2, cursor)
        }
        expectTimelineError(TimelineError.Validation) { timeline.timeline(generation, base, 0) }
        expectTimelineError(TimelineError.Validation) { timeline.timeline(generation, base, 101) }
        expectTimelineError(TimelineError.Validation) {
            timeline.timeline(generation, TimelineQuery(range = TimelineRange(10, 10)), 2)
        }

        val revisionBefore = revision()
        createNote(day + 100_000L, "newest")
        assertEquals(revisionBefore + 1, revision())
        expectTimelineError(TimelineError.CursorExpired) {
            timeline.timeline(generation, base, 2, cursor)
        }
        assertEquals("newest", (query(limit = 1).entries.single() as NoteTimelineEntry).record.body)
    }

    @Test
    fun editingTransactionDateMovesItAndExpiresTheOldCursor() = runBlocking {
        val day = timelineDay("2026-10-22")
        val input = FinanceInput(
            amountText = "58.00",
            direction = FinanceDirection.EXPENSE,
            accountId = catalog.account.id,
            accountVersion = catalog.account.version,
            categoryId = null,
            categoryVersion = null,
            memo = "before edit",
            localDateTime = "2026-10-22T12:00:00",
            zoneId = timelineZone,
        )
        val draft = finance.beginNewDraft(generation, input)
        val record = finance.saveDraft(generation, draft.id, draft.revision).record
        val anchor = createNote(day + 1_000, "anchor")
        val dayRange = timelineRange("2026-10-22", "2026-10-23")
        val firstPage = query(range = dayRange, limit = 1)
        val cursor = firstPage.next ?: error("Expected a next cursor")
        assertTrue(ids(firstPage).contains(record.id))

        val editDraft = finance.beginEditDraft(generation, record.id, record.version, timelineZone)
        val editedInput = editDraft.input.copy(localDateTime = "2026-10-24T12:00:00", memo = "after edit")
        val updated = finance.updateDraft(generation, editDraft.id, editDraft.revision, editedInput)
        val edited = finance.saveDraft(generation, updated.id, updated.revision).record
        assertEquals(record.id, edited.id)
        assertEquals(2L, edited.version)
        assertEquals("after edit", edited.memo)

        expectTimelineError(TimelineError.CursorExpired) {
            timeline.timeline(generation, TimelineQuery(range = dayRange), 1, cursor)
        }
        assertEquals(listOf(anchor.id), ids(query(range = dayRange)))
        val moved = query(range = timelineRange("2026-10-24", "2026-10-25"))
        assertEquals(listOf(edited.id), ids(moved))
        assertEquals(edited.occurredAt, moved.entries.single().occurredAt)
    }

    @Test
    fun anchorStartsAtRequestedDayAndContinuesBackwardAcrossDays() = runBlocking {
        val day21 = timelineDay("2026-10-21")
        val day22 = timelineDay("2026-10-22")
        val day23 = timelineDay("2026-10-23")
        val day24 = timelineDay("2026-10-24")
        val tx21 = insertTransaction(day21 + 12 * 3_600_000L)
        val tx22 = insertTransaction(day22 + 12 * 3_600_000L)
        val tx23 = insertTransaction(day23 + 12 * 3_600_000L)
        val note23 = createNote(day23 + 12 * 3_600_000L, "same moment note")
        val hidden = createNote(day23 + 12 * 3_600_000L, "hidden note")
        notes.delete(generation, hidden.id, hidden.version)
        val tx24 = insertTransaction(day24 + 12 * 3_600_000L)

        val beforeDay24 = timelineDay("2026-10-24")
        val first = timeline.timelineBefore(generation, TimelineQuery(), 2, beforeDay24)
        val firstIds = ids(first)
        assertEquals(listOf(tx23, note23.id), firstIds)
        assertFalse(firstIds.contains(hidden.id))
        assertFalse(firstIds.contains(tx24))
        val cursor = first.next ?: error("Expected next cursor")
        val second = timeline.timeline(generation, TimelineQuery(), 2, cursor)
        assertEquals(listOf(tx22, tx21), ids(second))
        assertNull(second.next)
    }

    @Test
    fun aggregateIsExactAcrossHalfOpenRangesCategoriesAndNullableCategory() = runBlocking {
        val day = timelineDay("2026-10-23")
        val range = timelineRange("2026-10-23", "2026-10-24")
        val incomeCategory = catalog.categories.first { it.direction == FinanceDirection.INCOME }
        val expenseCategory = catalog.categories.first { it.direction == FinanceDirection.EXPENSE }
        insertTransaction(day, "INCOME", 1_000, incomeCategory.id, "income categorized")
        insertTransaction(day + 1, "EXPENSE", 200, expenseCategory.id, "expense categorized")
        insertTransaction(day + 2, "INCOME", 300, null, "income uncategorized")
        insertTransaction(day + 3, "EXPENSE", 400, null, "expense uncategorized")
        insertTransaction(range.endExclusive, "INCOME", 500, incomeCategory.id, "excluded end")
        insertTransaction(range.startInclusive - 1, "EXPENSE", 600, expenseCategory.id, "excluded start")

        val all = timeline.aggregateFinances(generation, range)
        assertEquals(1_300, all.incomeMinor)
        assertEquals(600, all.expenseMinor)
        assertEquals(2, all.incomeCount)
        assertEquals(2, all.expenseCount)
        val byCategory = timeline.aggregateFinances(
            generation, range, FinanceCategoryScope.Category(expenseCategory.id),
        )
        assertEquals(0, byCategory.incomeMinor)
        assertEquals(200, byCategory.expenseMinor)
        assertEquals(1, byCategory.expenseCount)
        val uncategorized = timeline.aggregateFinances(generation, range, FinanceCategoryScope.Uncategorized)
        assertEquals(300, uncategorized.incomeMinor)
        assertEquals(400, uncategorized.expenseMinor)

        val raw = SchemaDiskObserver.snapshot(path).getValue("record_transaction")
        val oracle = raw.filter { row ->
            val occurredAt = row[7] as Long
            occurredAt >= range.startInclusive && occurredAt < range.endExclusive
        }
        assertEquals(
            BigInteger.valueOf(all.incomeMinor),
            oracle.filter { it[1] == "INCOME" }.fold(BigInteger.ZERO) { sum, row -> sum + BigInteger.valueOf(row[2] as Long) },
        )
        assertEquals(
            BigInteger.valueOf(all.expenseMinor),
            oracle.filter { it[1] == "EXPENSE" }.fold(BigInteger.ZERO) { sum, row -> sum + BigInteger.valueOf(row[2] as Long) },
        )
        assertEquals(oracle.size.toLong(), all.incomeCount + all.expenseCount)

        val empty = timeline.aggregateFinances(generation, TimelineRange(day - 2 * 86_400_000L, day - 86_400_000L))
        assertEquals(0, empty.incomeMinor)
        assertEquals(0, empty.expenseMinor)
        assertEquals(0, empty.incomeCount)
        assertEquals(0, empty.expenseCount)

        database.records().insertDraft(
            NativeFinanceDraftEntity(
                1, schemaId(), 1, "EDITING", "NEW", schemaId(), null,
                """{"amountText":"999999.00","formatVersion":1}""", timelineClock.millis(), null,
            ),
        )
        val withDraft = timeline.aggregateFinances(generation, range)
        assertEquals(all.incomeMinor, withDraft.incomeMinor)
        assertEquals(all.expenseMinor, withDraft.expenseMinor)
        assertEquals(all.incomeCount, withDraft.incomeCount)
        assertEquals(all.expenseCount, withDraft.expenseCount)

        expectTimelineError(TimelineError.NotFound) {
            timeline.aggregateFinances(generation, range, FinanceCategoryScope.Category(schemaId()))
        }
        expectTimelineError(TimelineError.Validation) {
            timeline.aggregateFinances(generation, TimelineRange(10, 10))
        }
        expectTimelineError(TimelineError.GenerationMismatch) {
            timeline.aggregateFinances(schemaId(), range)
        }
    }

    @Test
    fun aggregateDetectsLongOverflowWithoutPartialTotals() = runBlocking {
        val day = timelineDay("2026-10-22")
        val half = Long.MAX_VALUE / 2 + 1
        insertTransaction(day, "EXPENSE", half)
        insertTransaction(day + 1, "EXPENSE", half)
        expectTimelineError(TimelineError.AggregateOverflow) {
            timeline.aggregateFinances(generation, timelineRange("2026-10-22", "2026-10-23"))
        }

        val dayTwo = timelineDay("2026-10-23")
        insertTransaction(dayTwo, "INCOME", Long.MAX_VALUE)
        val exact = timeline.aggregateFinances(generation, timelineRange("2026-10-23", "2026-10-24"))
        assertEquals(Long.MAX_VALUE, exact.incomeMinor)
        assertEquals(0, exact.expenseMinor)
        assertEquals(1, exact.incomeCount)

        insertTransaction(dayTwo + 1, "EXPENSE", 100)
        val mixed = timeline.aggregateFinances(generation, timelineRange("2026-10-23", "2026-10-24"))
        assertEquals(Long.MAX_VALUE, mixed.incomeMinor)
        assertEquals(100, mixed.expenseMinor)
        assertEquals(1, mixed.expenseCount)

        val dayThree = timelineDay("2026-10-21")
        insertTransaction(dayThree, "INCOME", half)
        insertTransaction(dayThree + 1, "INCOME", half)
        expectTimelineError(TimelineError.AggregateOverflow) {
            timeline.aggregateFinances(generation, timelineRange("2026-10-21", "2026-10-22"))
        }
    }
}

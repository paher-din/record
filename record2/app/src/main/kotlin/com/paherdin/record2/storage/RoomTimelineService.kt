package com.paherdin.record2.storage

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.paherdin.record2.records.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class RoomTimelineService(private val database: RecordDatabase) : TimelineService {
    private val dao = database.records()

    override suspend fun timeline(
        generation: String,
        query: TimelineQuery,
        limit: Int,
        after: TimelineCursor?,
    ): TimelinePage = io {
        database.withTransaction {
            val state = state(generation)
            if (limit !in 1..100) fail(TimelineError.Validation)
            val canonical = canonical(query)
            after?.let { validateCursor(it, state, canonical) }
            val entries = load(canonical, limit + 1, after, before = null)
            page(state, canonical, entries, limit)
        }
    }

    override suspend fun timelineBefore(
        generation: String,
        query: TimelineQuery,
        limit: Int,
        beforeExclusive: Long,
    ): TimelinePage = io {
        database.withTransaction {
            val state = state(generation)
            if (limit !in 1..100) fail(TimelineError.Validation)
            val canonical = canonical(query)
            val entries = load(canonical, limit + 1, after = null, before = beforeExclusive)
            page(state, canonical, entries, limit)
        }
    }

    override suspend fun detail(generation: String, ref: RecordRef): RecordDetail = io {
        database.withTransaction {
            state(generation)
            when (ref) {
                is RecordRef.Transaction -> {
                    val transaction = transactionRow(ref.id)
                    val notes = dao.visibleNotesForTransaction(transaction.id).map { it.checkedNote().toDto() }
                    TransactionDetail(transaction.toDto(), notes, dao.visibleLinkCount(transaction.id))
                }
                is RecordRef.Note -> {
                    val note = noteForRead(ref.id)
                    val transactions = dao.transactionsForNote(note.id).map { it.checkedTransaction().toDto() }
                    NoteDetail(note.toDto(), transactions, transactions.size)
                }
            }
        }
    }

    override suspend fun aggregateFinances(
        generation: String,
        range: TimelineRange,
        category: FinanceCategoryScope,
    ): FinanceAggregate = io {
        database.withTransaction {
            state(generation)
            if (range.startInclusive >= range.endExclusive) fail(TimelineError.Validation)
            val rows = when (category) {
                FinanceCategoryScope.All -> dao.aggregateAmounts(range.startInclusive, range.endExclusive)
                FinanceCategoryScope.Uncategorized -> dao.aggregateAmountsUncategorized(range.startInclusive, range.endExclusive)
                is FinanceCategoryScope.Category -> {
                    identity(category.id)
                    if (dao.category(category.id) == null) fail(TimelineError.NotFound)
                    dao.aggregateAmountsForCategory(range.startInclusive, range.endExclusive, category.id)
                }
            }
            var income = 0L
            var expense = 0L
            var incomeCount = 0L
            var expenseCount = 0L
            for (row in rows) {
                if (row.currency != "CNY" || row.amountMinor <= 0) fail(TimelineError.RecoveryRequired)
                try {
                    when (row.direction) {
                        "INCOME" -> {
                            income = Math.addExact(income, row.amountMinor)
                            incomeCount = Math.addExact(incomeCount, 1L)
                        }
                        "EXPENSE" -> {
                            expense = Math.addExact(expense, row.amountMinor)
                            expenseCount = Math.addExact(expenseCount, 1L)
                        }
                        else -> fail(TimelineError.RecoveryRequired)
                    }
                } catch (error: ArithmeticException) {
                    throw TimelineException(TimelineError.AggregateOverflow, error)
                }
            }
            FinanceAggregate(range, category, income, expense, incomeCount, expenseCount)
        }
    }

    private fun load(
        query: TimelineQuery,
        limit: Int,
        after: TimelineCursor?,
        before: Long?,
    ): List<TimelineEntry> {
        val transactions = if (query.kind == null || query.kind == RecordKind.TRANSACTION) {
            dao.timelineTransactions(
                query.range?.startInclusive, query.range?.endExclusive, before, query.keyword, limit,
                after?.occurredAt, after?.kind?.name, after?.id,
            ).map { TransactionTimelineEntry(it.checkedTransaction().toDto()) }
        } else {
            emptyList()
        }
        val notes = if (query.kind == null || query.kind == RecordKind.NOTE) {
            dao.timelineNotes(
                query.range?.startInclusive, query.range?.endExclusive, before, query.keyword, limit,
                after?.occurredAt, after?.kind?.name, after?.id,
            ).map { NoteTimelineEntry(it.checkedNote().toDto()) }
        } else {
            emptyList()
        }
        return (transactions + notes).sortedWith(ENTRY_ORDER)
    }

    private fun page(
        state: RecordDataStateEntity,
        query: TimelineQuery,
        entries: List<TimelineEntry>,
        limit: Int,
    ): TimelinePage {
        val pageEntries = withLinkCounts(entries.take(limit))
        val next = if (entries.size > limit) {
            val last = pageEntries.last()
            TimelineCursor(
                occurredAt = last.occurredAt,
                kind = kind(last),
                id = last.id,
                generation = state.dataGeneration,
                businessRevision = state.businessRevision,
                filterDigest = TimelineQueryRules.digest(query),
                cursorDigest = TimelineQueryRules.cursorDigest(
                    query, last.occurredAt, kind(last), last.id,
                    state.dataGeneration, state.businessRevision,
                ),
            )
        } else {
            null
        }
        return TimelinePage(state.dataGeneration, state.businessRevision, pageEntries, next)
    }

    private fun withLinkCounts(entries: List<TimelineEntry>): List<TimelineEntry> {
        val transactionIds = entries.filterIsInstance<TransactionTimelineEntry>().map { it.id }
        val noteIds = entries.filterIsInstance<NoteTimelineEntry>().map { it.id }
        val transactionCounts = if (transactionIds.isEmpty()) emptyMap() else {
            dao.visibleLinkCountsForTransactions(transactionIds).associate { it.id to it.count }
        }
        val noteCounts = if (noteIds.isEmpty()) emptyMap() else {
            dao.linkCountsForNotes(noteIds).associate { it.id to it.count }
        }
        return entries.map { entry ->
            when (entry) {
                is TransactionTimelineEntry -> entry.copy(visibleLinkCount = transactionCounts[entry.id] ?: 0)
                is NoteTimelineEntry -> entry.copy(visibleLinkCount = noteCounts[entry.id] ?: 0)
            }
        }
    }

    private fun validateCursor(cursor: TimelineCursor, state: RecordDataStateEntity, query: TimelineQuery) {
        identity(cursor.id)
        val expectedDigest = TimelineQueryRules.cursorDigest(
            query, cursor.occurredAt, cursor.kind, cursor.id, cursor.generation, cursor.businessRevision,
        )
        if (cursor.generation != state.dataGeneration ||
            cursor.businessRevision != state.businessRevision ||
            cursor.filterDigest != TimelineQueryRules.digest(query) ||
            cursor.cursorDigest != expectedDigest
        ) {
            fail(TimelineError.CursorExpired)
        }
    }

    private fun canonical(query: TimelineQuery): TimelineQuery {
        query.range?.let { if (it.startInclusive >= it.endExclusive) fail(TimelineError.Validation) }
        val canonical = try {
            TimelineQueryRules.canonical(query)
        } catch (error: RuntimeException) {
            throw TimelineException(TimelineError.Validation, error)
        }
        TimelineQueryRules.requireKeywordWithinBudget(canonical.keyword)
        return canonical
    }

    private fun state(generation: String): RecordDataStateEntity {
        val state = dao.dataState() ?: fail(TimelineError.RecoveryRequired)
        if (state.businessRevision < 0 ||
            runCatching { UUID.fromString(state.dataGeneration).toString() }.getOrNull() != state.dataGeneration
        ) {
            fail(TimelineError.RecoveryRequired)
        }
        if (state.dataGeneration != generation) fail(TimelineError.GenerationMismatch)
        return state
    }

    private fun identity(id: String) {
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) fail(TimelineError.Validation)
    }

    private fun transactionRow(id: String): TransactionEntity {
        identity(id)
        return (dao.transaction(id) ?: fail(TimelineError.NotFound)).checkedTransaction()
    }

    private fun noteForRead(id: String): NoteEntity {
        identity(id)
        val note = (dao.noteForLifecycle(id) ?: fail(TimelineError.NotFound)).checkedNote()
        if (note.deletedAt != null) fail(TimelineError.NotVisible)
        return note
    }

    private fun TransactionEntity.checkedTransaction(): TransactionEntity {
        if (currency != "CNY" || amountMinor <= 0 || version < 1) fail(TimelineError.RecoveryRequired)
        direction(direction)
        return this
    }

    private fun NoteEntity.checkedNote(): NoteEntity {
        if (body.isBlank() || version < 1 || (deletedAt != null && deletedAt < createdAt)) {
            fail(TimelineError.RecoveryRequired)
        }
        return this
    }

    private fun TransactionEntity.toDto() = FinanceRecord(
        id, direction(direction), amountMinor, accountId, categoryId, memo, occurredAt, version, createdAt, updatedAt,
    )

    private fun NoteEntity.toDto() = NoteRecord(id, body, occurredAt, version, createdAt, updatedAt, deletedAt)

    private fun direction(value: String) = try {
        FinanceDirection.valueOf(value)
    } catch (error: IllegalArgumentException) {
        throw TimelineException(TimelineError.RecoveryRequired, error)
    }

    private fun kind(entry: TimelineEntry) = when (entry) {
        is TransactionTimelineEntry -> RecordKind.TRANSACTION
        is NoteTimelineEntry -> RecordKind.NOTE
    }

    private fun fail(code: TimelineError): Nothing = throw TimelineException(code)

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SQLiteFullException) {
            throw TimelineException(TimelineError.StorageFull, error)
        } catch (error: SQLiteException) {
            throw TimelineException(TimelineError.RecoverableIO, error)
        }
    }

    private companion object {
        // RecordDao cursor predicates mirror this order: transactions precede notes at the same instant.
        val ENTRY_ORDER: Comparator<TimelineEntry> = compareByDescending<TimelineEntry> { it.occurredAt }
            .thenBy { entry -> if (entry is TransactionTimelineEntry) 0 else 1 }
            .thenBy { entry -> entry.id }
    }
}

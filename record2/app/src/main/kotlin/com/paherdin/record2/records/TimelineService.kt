package com.paherdin.record2.records

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class RecordKind { TRANSACTION, NOTE }

internal data class TimelineRange(val startInclusive: Long, val endExclusive: Long)

internal data class TimelineQuery(
    val kind: RecordKind? = null,
    val keyword: String? = null,
    val range: TimelineRange? = null,
    val zoneId: String? = null,
)

internal data class TimelineCursor(
    val occurredAt: Long,
    val kind: RecordKind,
    val id: String,
    val generation: String,
    val businessRevision: Long,
    val filterDigest: String,
    val cursorDigest: String,
)

internal sealed interface TimelineEntry {
    val id: String
    val occurredAt: Long
    val version: Long
    val visibleLinkCount: Int
}

internal data class TransactionTimelineEntry(
    val record: FinanceRecord,
    override val visibleLinkCount: Int = 0,
) : TimelineEntry {
    override val id: String get() = record.id
    override val occurredAt: Long get() = record.occurredAt
    override val version: Long get() = record.version
}

internal data class NoteTimelineEntry(
    val record: NoteRecord,
    override val visibleLinkCount: Int = 0,
) : TimelineEntry {
    override val id: String get() = record.id
    override val occurredAt: Long get() = record.occurredAt
    override val version: Long get() = record.version
}

internal data class TimelinePage(
    val generation: String,
    val businessRevision: Long,
    val entries: List<TimelineEntry>,
    val next: TimelineCursor?,
)

internal sealed interface RecordRef {
    val id: String

    data class Transaction(override val id: String) : RecordRef
    data class Note(override val id: String) : RecordRef
}

internal sealed interface RecordDetail {
    val id: String
    val version: Long
    val occurredAt: Long
}

internal data class TransactionDetail(
    val record: FinanceRecord,
    val notes: List<NoteRecord>,
    val visibleLinkCount: Int,
) : RecordDetail {
    override val id: String get() = record.id
    override val version: Long get() = record.version
    override val occurredAt: Long get() = record.occurredAt
}

internal data class NoteDetail(
    val record: NoteRecord,
    val transactions: List<FinanceRecord>,
    val visibleLinkCount: Int,
) : RecordDetail {
    override val id: String get() = record.id
    override val version: Long get() = record.version
    override val occurredAt: Long get() = record.occurredAt
}

internal sealed interface FinanceCategoryScope {
    data object All : FinanceCategoryScope
    data object Uncategorized : FinanceCategoryScope
    data class Category(val id: String) : FinanceCategoryScope
}

internal data class FinanceAggregate(
    val range: TimelineRange,
    val category: FinanceCategoryScope,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val incomeCount: Long,
    val expenseCount: Long,
)

internal enum class TimelineError {
    Validation, NotFound, NotVisible, GenerationMismatch, RecoveryRequired,
    CursorExpired, AggregateOverflow, RecoverableIO, StorageFull,
}

internal class TimelineException(val code: TimelineError, cause: Throwable? = null) : Exception(code.name, cause)

internal interface TimelineService {
    suspend fun timeline(
        generation: String,
        query: TimelineQuery,
        limit: Int,
        after: TimelineCursor? = null,
    ): TimelinePage

    suspend fun timelineBefore(
        generation: String,
        query: TimelineQuery,
        limit: Int,
        beforeExclusive: Long,
    ): TimelinePage

    suspend fun detail(generation: String, ref: RecordRef): RecordDetail

    suspend fun aggregateFinances(
        generation: String,
        range: TimelineRange,
        category: FinanceCategoryScope = FinanceCategoryScope.All,
    ): FinanceAggregate
}

internal object TimelineQueryRules {
    private const val MAX_KEYWORD_CODE_POINTS = 1_000
    private val cursorMacKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

    // Blank means no keyword filter; non-blank text is matched literally, never as LIKE wildcards.
    fun keyword(value: String?): String? = value?.takeUnless { it.isBlank() }?.let(::asciiLowercase)

    fun canonical(query: TimelineQuery): TimelineQuery = query.copy(
        keyword = keyword(query.keyword),
        zoneId = query.zoneId?.let { ZoneId.of(it).id },
    )

    fun requireKeywordWithinBudget(keyword: String?) {
        if (keyword != null && keyword.codePointCount(0, keyword.length) > MAX_KEYWORD_CODE_POINTS) {
            throw TimelineException(TimelineError.Validation)
        }
    }

    fun digest(query: TimelineQuery): String = sha256(canonicalText(query))

    fun cursorDigest(
        query: TimelineQuery,
        occurredAt: Long,
        kind: RecordKind,
        id: String,
        generation: String,
        businessRevision: Long,
    ): String = hmac(
        canonicalText(query) + "|" + occurredAt + "|" + kind.name + "|" +
            id.length + ":" + id + "|" + generation + "|" + businessRevision,
    )

    private fun canonicalText(query: TimelineQuery): String {
        val canonical = canonical(query)
        val keyword = canonical.keyword
        return buildString {
            append(canonical.kind?.name ?: "*").append('|')
            if (keyword == null) append('*') else append(keyword.length).append(':').append(keyword)
            append('|')
            append(canonical.range?.startInclusive ?: "*").append('|')
            append(canonical.range?.endExclusive ?: "*").append('|')
            append(canonical.zoneId ?: "*")
        }
    }

    // ASCII-only folding matches SQLite lower(); non-ASCII text keeps its exact case.
    private fun asciiLowercase(value: String): String = buildString(value.length) {
        for (char in value) append(if (char in 'A'..'Z') char + ('a' - 'A') else char)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    // The key is process-local: a cursor cannot be forged or replayed after process restart.
    private fun hmac(text: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(cursorMacKey, "HmacSHA256"))
        return mac.doFinal(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

internal object TimelineTime {
    fun dayStart(date: LocalDate, zoneId: String): Long = start(date, zoneId)

    fun nextDayStart(date: LocalDate, zoneId: String): Long = try {
        start(date.plusDays(1), zoneId)
    } catch (error: TimelineException) {
        throw error
    } catch (error: RuntimeException) {
        throw TimelineException(TimelineError.Validation, error)
    }

    fun range(startDate: LocalDate, endDateExclusive: LocalDate, zoneId: String): TimelineRange {
        if (!startDate.isBefore(endDateExclusive)) throw TimelineException(TimelineError.Validation)
        val start = dayStart(startDate, zoneId)
        val end = dayStart(endDateExclusive, zoneId)
        if (start >= end) throw TimelineException(TimelineError.Validation)
        return TimelineRange(start, end)
    }

    private fun start(date: LocalDate, zoneId: String): Long = try {
        date.atStartOfDay(ZoneId.of(zoneId)).toInstant().toEpochMilli()
    } catch (error: RuntimeException) {
        throw TimelineException(TimelineError.Validation, error)
    }
}

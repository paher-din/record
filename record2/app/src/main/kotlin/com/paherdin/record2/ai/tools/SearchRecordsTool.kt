package com.paherdin.record2.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.paherdin.record2.records.RecordKind
import com.paherdin.record2.records.TimelineError
import com.paherdin.record2.records.TimelineException
import com.paherdin.record2.records.TimelineQuery
import com.paherdin.record2.records.TimelineRange
import com.paherdin.record2.records.TimelineTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
internal data class SearchRecordsArgs(
    val kind: String? = null,
    val keyword: String? = null,
    val dateStart: String? = null,
    val dateEnd: String? = null,
    val zoneId: String? = null,
    val limit: Int? = null,
    val cursor: String? = null,
)

/**
 * `search_records` (Spec §6): date/type/keyword filtering with legal pagination cursor and
 * limits; returns real visible record ids, versions, summaries, occurred time, link counts, the
 * next cursor and the covered scope. Empty results and query failure are distinct outcomes.
 */
internal class SearchRecordsTool(private val context: RecordToolContext) :
    SimpleTool<SearchRecordsArgs>(
        argsType = typeToken<SearchRecordsArgs>(),
        name = "search_records",
        description = "Search visible finance transactions and notes. Optional filters: kind " +
            "(TRANSACTION or NOTE), keyword (literal substring), and a local date range " +
            "[dateStart, dateEnd) which requires zoneId. Paginate with the returned nextCursor. " +
            "Each result carries the real record id and version. An empty result list means no " +
            "visible record matches; it never means the query failed.",
    ) {
    private val localFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    override suspend fun execute(args: SearchRecordsArgs): String {
        context.guard("search_records", RecordToolContext.toolJson.encodeToString(SearchRecordsArgs.serializer(), args), RecordToolBudget.SEARCH_CALLS_PER_RUN)
        val issues = ToolIssues()
        val kind = args.kind?.let {
            when (it) {
                "TRANSACTION" -> RecordKind.TRANSACTION
                "NOTE" -> RecordKind.NOTE
                else -> {
                    issues.add("kind", "kind must be TRANSACTION or NOTE")
                    null
                }
            }
        }
        val keyword = args.keyword?.takeIf { it.isNotBlank() }
        if (keyword != null && keyword.codePointCount(0, keyword.length) > RecordToolBudget.KEYWORD_MAX_CODE_POINTS) {
            issues.add("keyword", "keyword exceeds ${RecordToolBudget.KEYWORD_MAX_CODE_POINTS} characters")
        }
        val limit = when (args.limit) {
            null -> RecordToolBudget.PAGE_SIZE_DEFAULT
            in 1..RecordToolBudget.PAGE_SIZE_MAX -> args.limit
            else -> {
                issues.add("limit", "limit must be between 1 and ${RecordToolBudget.PAGE_SIZE_MAX}")
                null
            }
        }
        val range = parseRange(args, issues)
        val cursor = args.cursor?.let {
            CursorCodec.decode(it) ?: run {
                issues.add("cursor", "cursor is malformed or expired; restart from the first page")
                null
            }
        }
        if (issues.present()) return context.charge(issues.result())
        val zone = range?.second ?: args.zoneId
        try {
            val query = TimelineQuery(kind = kind, keyword = keyword, range = range?.first, zoneId = zone)
            val page = context.deps.timeline.timeline(context.start.dataGeneration, query, limit!!, cursor)
            val entries = buildJsonArray {
                page.entries.forEach { entry ->
                    add(buildJsonObject {
                        put("id", entry.id)
                        put("kind", if (entry is com.paherdin.record2.records.TransactionTimelineEntry) "TRANSACTION" else "NOTE")
                        put("version", entry.version)
                        put("occurredAt", entry.occurredAt)
                        put("localTime", localTime(entry.occurredAt, zone))
                        put("visibleLinkCount", entry.visibleLinkCount)
                        put("summary", summary(entry))
                    })
                    context.recordReference(
                        if (entry is com.paherdin.record2.records.TransactionTimelineEntry) "TRANSACTION" else "NOTE",
                        entry.id,
                    )
                }
            }
            return context.charge(buildJsonObject {
                put("ok", true)
                put("results", entries)
                put("more", page.next != null)
                page.next?.let { put("nextCursor", CursorCodec.encode(it)) }
                putJsonObject("coverage") {
                    put("kind", kind?.name ?: "ALL")
                    put("keyword", keyword)
                    put("rangeStart", args.dateStart)
                    put("rangeEndExclusive", args.dateEnd)
                    put("zoneId", zone)
                    put("entriesOnPage", page.entries.size)
                    put("generation", page.generation)
                    put("businessRevision", page.businessRevision)
                }
            }.toString())
        } catch (error: TimelineException) {
            return when (error.code) {
                TimelineError.Validation, TimelineError.CursorExpired, TimelineError.NotFound ->
                    context.charge(issues.also {
                        it.add("query", "query rejected (${error.code}); fix the filters or restart from the first page")
                    }.result())
                else -> context.poisonFrom(error)
            }
        }
    }

    private fun parseRange(
        args: SearchRecordsArgs,
        issues: ToolIssues,
    ): Pair<TimelineRange, String>? {
        if (args.dateStart == null && args.dateEnd == null) return null
        if (args.dateStart == null || args.dateEnd == null) {
            issues.add("dateRange", "dateStart and dateEnd must be provided together (yyyy-MM-dd, end exclusive)")
            return null
        }
        val zone = args.zoneId
        if (zone.isNullOrBlank()) {
            issues.add("zoneId", "zoneId is required with a date range")
            return null
        }
        return try {
            TimelineTime.range(
                java.time.LocalDate.parse(args.dateStart),
                java.time.LocalDate.parse(args.dateEnd),
                zone,
            ) to zone
        } catch (error: TimelineException) {
            issues.add("dateRange", "invalid date range or zone: use yyyy-MM-dd dates and a valid zoneId")
            null
        } catch (error: RuntimeException) {
            issues.add("dateRange", "invalid date format: use yyyy-MM-dd")
            null
        }
    }

    private fun localTime(occurredAt: Long, zoneId: String?): String {
        val zone = try {
            zoneId?.let { ZoneId.of(it) } ?: ZoneId.of("UTC")
        } catch (error: RuntimeException) {
            ZoneId.of("UTC")
        }
        return localFormat.format(Instant.ofEpochMilli(occurredAt).atZone(zone))
    }

    private suspend fun summary(entry: com.paherdin.record2.records.TimelineEntry): String = when (entry) {
        is com.paherdin.record2.records.TransactionTimelineEntry -> {
            val record = entry.record
            val category = record.categoryId?.let { id ->
                ToolCatalog.categoryName(context, id)
            } ?: "未分类"
            "${record.direction.name} ${com.paherdin.record2.records.Money.formatMinor(record.amountMinor)} CNY" +
                " ${category}${if (record.memo.isBlank()) "" else " 「" + TextSegments.snippet(record.memo, RecordToolBudget.MEMO_SNIPPET_CHARS) + "」"}"
        }
        is com.paherdin.record2.records.NoteTimelineEntry ->
            TextSegments.snippet(entry.record.body, RecordToolBudget.NOTE_SNIPPET_CHARS)
    }
}

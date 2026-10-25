package com.paherdin.record2.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.paherdin.record2.proposals.ProposalBudget
import com.paherdin.record2.records.Money
import com.paherdin.record2.records.NoteDetail
import com.paherdin.record2.records.NoteRecord
import com.paherdin.record2.records.RecordRef
import com.paherdin.record2.records.TimelineError
import com.paherdin.record2.records.TimelineException
import com.paherdin.record2.records.TransactionDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class GetRecordDetailArgs(
    val kind: String,
    val id: String,
    val segmentCursor: Long? = null,
    val maxChars: Int? = null,
)

/**
 * `get_record_detail` (Spec §6): typed record identity with segmented full text. Visibility is
 * re-verified on every call, so a deleted or hidden note is never reachable by guessing an id
 * (CTX-05). Long bodies are returned in code-point segments; the result always states whether the
 * content is truncated, so unread parts can never be summarized as read (CTX-09).
 */
internal class GetRecordDetailTool(private val context: RecordToolContext) :
    SimpleTool<GetRecordDetailArgs>(
        argsType = typeToken<GetRecordDetailArgs>(),
        name = "get_record_detail",
        description = "Read one visible record by its real id and kind (TRANSACTION or NOTE). " +
            "Long text is segmented: pass maxChars (200..4000, default 2000) and segmentCursor " +
            "(0-based). The response reports totalSegments, truncated and nextSegmentCursor; do " +
            "not summarize a record as fully read while truncated is true.",
    ) {
    private val localFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    override suspend fun execute(args: GetRecordDetailArgs): String {
        context.guard(
            "get_record_detail",
            RecordToolContext.toolJson.encodeToString(GetRecordDetailArgs.serializer(), args),
            RecordToolBudget.DETAIL_CALLS_PER_RUN,
        )
        val issues = ToolIssues()
        val kind = when (args.kind) {
            "TRANSACTION" -> "TRANSACTION"
            "NOTE" -> "NOTE"
            else -> {
                issues.add("kind", "kind must be TRANSACTION or NOTE")
                null
            }
        }
        if (!ProposalBudget.IDENTITY.matches(args.id)) {
            issues.add("id", "id must be the real record UUID returned by search_records")
        }
        val maxChars = when (args.maxChars) {
            null -> RecordToolBudget.DETAIL_CHARS_DEFAULT
            in RecordToolBudget.DETAIL_CHARS_MIN..RecordToolBudget.DETAIL_CHARS_MAX -> args.maxChars
            else -> {
                issues.add(
                    "maxChars",
                    "maxChars must be ${RecordToolBudget.DETAIL_CHARS_MIN}..${RecordToolBudget.DETAIL_CHARS_MAX}",
                )
                null
            }
        }
        val segmentCursor = when (args.segmentCursor) {
            null -> 0L
            in 0..RecordToolBudget.DETAIL_SEGMENT_MAX.toLong() -> args.segmentCursor
            else -> {
                issues.add("segmentCursor", "segmentCursor out of range")
                null
            }
        }
        if (issues.present()) return context.charge(issues.result())
        val segmentIndex = segmentCursor!!
        val segmentChars = maxChars!!
        val ref = if (kind == "TRANSACTION") RecordRef.Transaction(args.id) else RecordRef.Note(args.id)
        try {
            val detail = context.deps.timeline.detail(context.start.dataGeneration, ref)
            val text = when (detail) {
                is TransactionDetail -> transactionText(detail)
                is NoteDetail -> detail.record.body
            }
            val totalSegments = ((TextSegments.count(text) + segmentChars - 1) / segmentChars).toLong()
                .coerceAtLeast(1)
            if (segmentIndex >= totalSegments) {
                return context.charge(ToolIssues().also {
                    it.add("segmentCursor", "segmentCursor $segmentIndex is beyond the content ($totalSegments segments)")
                }.result())
            }
            val segmentText = TextSegments.slice(text, (segmentIndex * segmentChars).toInt(), segmentChars)
            val truncated = segmentIndex < totalSegments - 1
            context.recordReference(kind!!, args.id)
            return context.charge(buildJsonObject {
                put("ok", true)
                put("id", args.id)
                put("kind", kind)
                put("version", detail.version)
                put("occurredAt", detail.occurredAt)
                put("localTime", localTime(detail.occurredAt))
                put("segmentIndex", segmentIndex)
                put("totalSegments", totalSegments)
                put("truncated", truncated)
                if (truncated) put("nextSegmentCursor", segmentIndex + 1)
                put("text", segmentText)
                if (detail is TransactionDetail) put("transaction", transactionMeta(detail))
                put("visibleLinkCount", visibleLinkCount(detail))
                put("linkedRecords", linkedRecords(detail))
            }.toString())
        } catch (error: TimelineException) {
            return when (error.code) {
                TimelineError.NotFound -> context.charge(ToolIssues("NotFound").also {
                    it.add("id", "no record ${args.id} of kind $kind exists")
                }.result())
                TimelineError.NotVisible -> context.charge(ToolIssues("NotVisible").also {
                    it.add("id", "record ${args.id} is deleted or not visible; deleted records cannot be read")
                }.result())
                TimelineError.Validation -> context.charge(ToolIssues().also {
                    it.add("kind", "kind does not match the record type")
                }.result())
                else -> context.poisonFrom(error)
            }
        }
    }

    private suspend fun transactionText(detail: TransactionDetail): String = buildString {
        val record = detail.record
        append(if (record.direction.name == "INCOME") "收入" else "支出")
        append(' ').append(Money.formatMinor(record.amountMinor)).append(" CNY\n")
        append("账户: ").append(ToolCatalog.accountName(context, record.accountId) ?: record.accountId).append('\n')
        append("分类: ").append(record.categoryId?.let { ToolCatalog.categoryName(context, it) } ?: "未分类").append('\n')
        append("备注: ").append(record.memo)
    }

    private fun transactionMeta(detail: TransactionDetail): JsonObject = buildJsonObject {
        put("direction", detail.record.direction.name)
        put("amountMinor", detail.record.amountMinor)
    }

    private fun visibleLinkCount(detail: com.paherdin.record2.records.RecordDetail): Int = when (detail) {
        is TransactionDetail -> detail.visibleLinkCount
        is NoteDetail -> detail.visibleLinkCount
    }

    private fun linkedRecords(detail: com.paherdin.record2.records.RecordDetail): JsonArray =
        buildJsonArray {
            when (detail) {
                is TransactionDetail -> detail.notes.take(RecordToolBudget.LINKS_PER_DETAIL).forEach { note ->
                    add(buildJsonObject {
                        put("id", note.id)
                        put("kind", "NOTE")
                        put("version", note.version)
                        put("summary", TextSegments.snippet(note.body, RecordToolBudget.NOTE_SNIPPET_CHARS))
                    })
                }
                is NoteDetail -> detail.transactions.take(RecordToolBudget.LINKS_PER_DETAIL).forEach { transaction ->
                    add(buildJsonObject {
                        put("id", transaction.id)
                        put("kind", "TRANSACTION")
                        put("version", transaction.version)
                        put("summary", TextSegments.snippet(transaction.memo, RecordToolBudget.MEMO_SNIPPET_CHARS))
                    })
                }
            }
        }

    private fun localTime(occurredAt: Long): String =
        localFormat.format(Instant.ofEpochMilli(occurredAt).atZone(ZoneId.systemDefault()))
}

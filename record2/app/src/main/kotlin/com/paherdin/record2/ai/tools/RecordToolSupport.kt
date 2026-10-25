package com.paherdin.record2.ai.tools

import com.paherdin.record2.records.TimelineCursor
import java.time.Clock
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * D1 hard budgets for the four record tools. The values are D1 execution bounds derived from the
 * approved D0 test caps (Plan §24.5: 900 s run wall clock, 50 agent steps) and the B4 keyword
 * budget; they are enforced per run by [ToolBudgetLedger] and per call by each tool. They are not
 * frozen product defaults — the product budget waits for D1/E2 evidence (Plan §24.6).
 */
internal object RecordToolBudget {
    const val TOOL_CALLS_PER_RUN = 40
    const val IDENTICAL_CALL_LIMIT = 3
    const val RESULT_CHARS_PER_RUN = 120_000
    const val WALL_MS_PER_RUN = 900_000L
    // D0 §24.5 test upper bounds, used as provisional per-run production guards until the
    // product defaults are frozen from D1/E2 evidence. They are not a billing quote.
    const val TOKENS_PER_RUN = 64_000L
    const val FLASH_USD_PER_RUN = 0.10
    const val PRO_USD_PER_RUN = 0.25
    const val FLASH_INPUT_USD_PER_MILLION = 0.30
    const val FLASH_OUTPUT_USD_PER_MILLION = 1.20
    const val PRO_INPUT_USD_PER_MILLION = 1.32
    const val PRO_OUTPUT_USD_PER_MILLION = 3.96
    const val SEARCH_CALLS_PER_RUN = 30
    const val DETAIL_CALLS_PER_RUN = 30
    const val PREPARE_CALLS_PER_RUN = 20
    const val AGGREGATE_CALLS_PER_RUN = 8
    const val PAGE_SIZE_DEFAULT = 20
    const val PAGE_SIZE_MAX = 50
    const val KEYWORD_MAX_CODE_POINTS = 200
    const val DETAIL_CHARS_DEFAULT = 2_000
    const val DETAIL_CHARS_MIN = 200
    const val DETAIL_CHARS_MAX = 4_000
    const val DETAIL_SEGMENT_MAX = 200
    const val AGGREGATE_MAX_DAYS = 400L
    const val LINKS_PER_DETAIL = 8
    const val NOTE_SNIPPET_CHARS = 80
    const val MEMO_SNIPPET_CHARS = 40
    const val REFERENCES_PER_KIND = 8
}

/**
 * Failures that must end the run instead of returning a model-recoverable tool result: the tool
 * boundary is no longer safe to continue (authorization revoked, run/session/generation moved, or
 * a hard budget is exhausted). Model-recoverable problems are returned as ordinary tool results
 * with `{"ok":false,...}` and never throw.
 */
internal enum class RecordToolFailure {
    AuthorizationRevoked, RunInvalidated, GenerationMoved, BudgetExceeded,
    UsageUnknown, RecoveryRequired, RecoverableIO, StorageFull,
}

internal class RecordToolException(val code: RecordToolFailure, message: String) :
    Exception("$code: $message")

/**
 * Per-run hard-budget ledger. Koog executes one agent loop per run, so entries are charged
 * sequentially; a violation throws [RecordToolException] and fails the run with
 * BUDGET_EXCEEDED semantics ("stop at the limit and report, never fake completion").
 */
internal class ToolBudgetLedger(private val clock: Clock, private val deadlineMillis: Long) {
    var toolCalls: Int = 0
        private set
    private val perTool = HashMap<String, Int>()
    private val identical = HashMap<String, Int>()
    private var resultChars = 0

    fun checkEntry(tool: String, argsKey: String, perToolCap: Int) {
        if (clock.millis() >= deadlineMillis) {
            throw RecordToolException(RecordToolFailure.BudgetExceeded, "run wall clock exhausted")
        }
        toolCalls += 1
        if (toolCalls > RecordToolBudget.TOOL_CALLS_PER_RUN) {
            throw RecordToolException(RecordToolFailure.BudgetExceeded, "tool calls per run exceeded")
        }
        val toolCount = perTool.merge(tool, 1, Int::plus)!!
        if (toolCount > perToolCap) {
            throw RecordToolException(RecordToolFailure.BudgetExceeded, "$tool calls per run exceeded $perToolCap")
        }
        val repeats = identical.merge("$tool|$argsKey", 1, Int::plus)!!
        if (repeats > RecordToolBudget.IDENTICAL_CALL_LIMIT) {
            throw RecordToolException(
                RecordToolFailure.BudgetExceeded,
                "no progress: identical $tool call repeated $repeats times",
            )
        }
    }

    fun chargeResult(result: String): String {
        resultChars += result.length
        if (resultChars > RecordToolBudget.RESULT_CHARS_PER_RUN) {
            throw RecordToolException(RecordToolFailure.BudgetExceeded, "tool result chars per run exceeded")
        }
        return result
    }

    fun withinWallClock(): Boolean = clock.millis() < deadlineMillis
}

@Serializable
private data class CursorPayload(
    val occurredAt: Long,
    val kind: String,
    val id: String,
    val generation: String,
    val businessRevision: Long,
    val filterDigest: String,
    val cursorDigest: String,
)

/**
 * Opaque wire encoding for B4 timeline cursors. The payload only carries what
 * [com.paherdin.record2.records.TimelineCursor] holds; authenticity is not established here — the
 * timeline service recomputes the process-local HMAC digest against the re-canonicalized query, so
 * a forged or replayed cursor fails there with CursorExpired.
 */
internal object CursorCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(cursor: TimelineCursor): String {
        val payload = json.encodeToString(
            CursorPayload.serializer(),
            CursorPayload(
                occurredAt = cursor.occurredAt,
                kind = cursor.kind.name,
                id = cursor.id,
                generation = cursor.generation,
                businessRevision = cursor.businessRevision,
                filterDigest = cursor.filterDigest,
                cursorDigest = cursor.cursorDigest,
            ),
        )
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    /** Returns null for any malformed input; callers turn that into a validation tool result. */
    fun decode(text: String): com.paherdin.record2.records.TimelineCursor? = runCatching {
        val payload = json.decodeFromString(
            CursorPayload.serializer(),
            String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8),
        )
        com.paherdin.record2.records.TimelineCursor(
            occurredAt = payload.occurredAt,
            kind = enumValues<com.paherdin.record2.records.RecordKind>().first { it.name == payload.kind },
            id = payload.id,
            generation = payload.generation,
            businessRevision = payload.businessRevision,
            filterDigest = payload.filterDigest,
            cursorDigest = payload.cursorDigest,
        )
    }.getOrNull()
}

/** Code-point aware helpers: segmentation must never split a surrogate pair. */
internal object TextSegments {
    fun count(text: String): Int = text.codePointCount(0, text.length)

    fun slice(text: String, offsetCodePoints: Int, lengthCodePoints: Int): String {
        val out = StringBuilder()
        var index = 0
        var codePoint = 0
        while (index < text.length && codePoint < offsetCodePoints + lengthCodePoints) {
            val current = text.codePointAt(index)
            if (codePoint >= offsetCodePoints) out.appendCodePoint(current)
            index += Character.charCount(current)
            codePoint += 1
        }
        return out.toString()
    }

    fun snippet(text: String, maxChars: Int): String {
        val count = count(text)
        return if (count <= maxChars) text else slice(text, 0, maxChars) + "…"
    }
}

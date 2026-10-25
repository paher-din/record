package com.paherdin.record2.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Per-run provider usage ledger. The guarded HTTP client charges every successful LLM response
 * here; the coordinator refuses a final answer once the cumulative token or cost upper bound is
 * crossed. Bounds are D0 §24.5 test-derived guards, not frozen product billing defaults.
 *
 * A response without a usage block is counted as unmetered rather than silently treated as zero;
 * the D0 protocol evidence records that state. DeepSeek's normal responses carry usage.
 */
internal class RunUsageLedger(
    private val maxTokens: Long = RecordToolBudget.TOKENS_PER_RUN,
    private val flashUsdCap: Double = RecordToolBudget.FLASH_USD_PER_RUN,
    private val proUsdCap: Double = RecordToolBudget.PRO_USD_PER_RUN,
    private val flashInputRate: Double = RecordToolBudget.FLASH_INPUT_USD_PER_MILLION,
    private val flashOutputRate: Double = RecordToolBudget.FLASH_OUTPUT_USD_PER_MILLION,
    private val proInputRate: Double = RecordToolBudget.PRO_INPUT_USD_PER_MILLION,
    private val proOutputRate: Double = RecordToolBudget.PRO_OUTPUT_USD_PER_MILLION,
) {
    var inputTokens: Long = 0L
        private set
    var outputTokens: Long = 0L
        private set
    var estimatedCostUsd: Double = 0.0
        private set
    var unmeteredResponses: Int = 0
        private set
    var exceededReason: String? = null
        private set

    /** Returns a hard-budget reason when this response crossed a limit, otherwise null. */
    fun record(requestBody: String, responseBody: String): String? {
        val usage = usageOf(responseBody)
        if (usage == null) {
            unmeteredResponses += 1
            return null
        }
        val model = modelOf(requestBody)
        val input = usage.first
        val output = usage.second
        inputTokens += input
        outputTokens += output
        val isPro = model.contains("pro", ignoreCase = true)
        val inputRate = if (isPro) proInputRate else flashInputRate
        val outputRate = if (isPro) proOutputRate else flashOutputRate
        estimatedCostUsd += (input * inputRate + output * outputRate) / 1_000_000.0
        val cap = if (isPro) proUsdCap else flashUsdCap
        val total = inputTokens + outputTokens
        val reason = when {
            total > maxTokens -> "run token budget exceeded: $total > $maxTokens"
            estimatedCostUsd > cap -> "run cost budget exceeded: $estimatedCostUsd > $cap"
            else -> null
        }
        if (reason != null) exceededReason = reason
        return reason
    }

    private fun usageOf(responseBody: String): Pair<Long, Long>? = runCatching {
        val usage = Json.parseToJsonElement(responseBody).jsonObject["usage"]?.jsonObject
            ?: return@runCatching null
        val input = usage["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val output = usage["completion_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        input to output
    }.getOrNull()

    private fun modelOf(requestBody: String): String = runCatching {
        Json.parseToJsonElement(requestBody).jsonObject["model"]?.jsonPrimitive?.content
    }.getOrNull() ?: "deepseek-flash"
}

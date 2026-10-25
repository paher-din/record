package com.paherdin.record2.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal sealed interface RunUsageVerdict {
    data object Within : RunUsageVerdict
    data class Exceeded(val reason: String) : RunUsageVerdict
    data class Unknown(val reason: String) : RunUsageVerdict
}

/**
 * Per-run provider usage ledger. The guarded HTTP client charges every successful LLM response
 * here; the coordinator refuses a final answer once the cumulative token or cost upper bound is
 * crossed. Bounds are D0 §24.5 test-derived guards, not frozen product billing defaults.
 *
 * A response whose usage is missing, partial, malformed, negative or overflowing is UNKNOWN, not
 * zero. The run is terminated rather than marked completed with an unverifiable budget; the
 * persisted safe code is PROVIDER_FAILURE because no dedicated usage code exists.
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
    var unknownReason: String? = null
        private set

    fun record(requestBody: String, responseBody: String): RunUsageVerdict {
        unknownReason?.let { return RunUsageVerdict.Unknown(it) }
        val usage = usageOf(responseBody)
        if (usage is ParsedUsage.Invalid) {
            return unknown(usage.reason)
        }
        val valid = usage as ParsedUsage.Valid
        val model = modelOf(requestBody)
        val newInput = addTokens(inputTokens, valid.input)
            ?: return unknown("input token count overflow")
        val newOutput = addTokens(outputTokens, valid.output)
            ?: return unknown("output token count overflow")
        val total = addTokens(newInput, newOutput)
            ?: return unknown("total token count overflow")
        val isPro = model.contains("pro", ignoreCase = true)
        val inputRate = if (isPro) proInputRate else flashInputRate
        val outputRate = if (isPro) proOutputRate else flashOutputRate
        val deltaCost = (valid.input * inputRate + valid.output * outputRate) / 1_000_000.0
        val newCost = estimatedCostUsd + deltaCost
        if (!newCost.isFinite()) return unknown("cost accumulation overflow")
        inputTokens = newInput
        outputTokens = newOutput
        estimatedCostUsd = newCost
        val cap = if (isPro) proUsdCap else flashUsdCap
        val reason = when {
            total > maxTokens -> "run token budget exceeded: $total > $maxTokens"
            newCost > cap -> "run cost budget exceeded: $newCost > $cap"
            else -> null
        }
        return if (reason == null) {
            RunUsageVerdict.Within
        } else {
            exceededReason = reason
            RunUsageVerdict.Exceeded(reason)
        }
    }

    private fun unknown(reason: String): RunUsageVerdict {
        unmeteredResponses += 1
        if (unknownReason == null) unknownReason = reason
        return RunUsageVerdict.Unknown(reason)
    }

    private fun addTokens(left: Long, right: Long): Long? = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

    private sealed interface ParsedUsage {
        data class Valid(val input: Long, val output: Long) : ParsedUsage
        data class Invalid(val reason: String) : ParsedUsage
    }

    private fun usageOf(responseBody: String): ParsedUsage {
        val usage = try {
            Json.parseToJsonElement(responseBody).jsonObject["usage"]?.jsonObject
                ?: return ParsedUsage.Invalid("usage block missing")
        } catch (error: IllegalArgumentException) {
            return ParsedUsage.Invalid("response usage is not valid JSON")
        }
        val input = tokenField(usage, "prompt_tokens")
            ?: return ParsedUsage.Invalid("prompt_tokens missing or invalid")
        val output = tokenField(usage, "completion_tokens")
            ?: return ParsedUsage.Invalid("completion_tokens missing or invalid")
        if (input < 0 || output < 0) return ParsedUsage.Invalid("usage token count is negative")
        return ParsedUsage.Valid(input, output)
    }

    private fun tokenField(usage: kotlinx.serialization.json.JsonObject, name: String): Long? {
        val element = usage[name] ?: return null
        val value = try {
            element.jsonPrimitive.longOrNull
        } catch (error: IllegalArgumentException) {
            null
        } ?: return null
        return value
    }

    private fun modelOf(requestBody: String): String = runCatching {
        Json.parseToJsonElement(requestBody).jsonObject["model"]?.jsonPrimitive?.content
    }.getOrNull() ?: "deepseek-flash"
}

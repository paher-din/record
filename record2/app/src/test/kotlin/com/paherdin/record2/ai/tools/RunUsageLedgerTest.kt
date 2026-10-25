package com.paherdin.record2.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RunUsageLedgerTest {
    private fun request(model: String = "deepseek-flash"): String = buildJsonObject {
        put("model", model)
    }.toString()

    private fun response(promptTokens: Long, completionTokens: Long): String =
        rawResponse(JsonPrimitive(promptTokens), JsonPrimitive(completionTokens))

    private fun rawResponse(promptTokens: JsonElement?, completionTokens: JsonElement?): String =
        buildJsonObject {
            putJsonObject("usage") {
                promptTokens?.let { put("prompt_tokens", it) }
                completionTokens?.let { put("completion_tokens", it) }
            }
        }.toString()

    @Test
    fun accumulatesWithinBounds() {
        val ledger = RunUsageLedger()
        assertTrue(ledger.record(request(), response(10, 5)) is RunUsageVerdict.Within)
        assertTrue(ledger.record(request(), response(20, 7)) is RunUsageVerdict.Within)
        assertEquals(30L, ledger.inputTokens)
        assertEquals(12L, ledger.outputTokens)
        assertNull(ledger.exceededReason)
        assertNull(ledger.unknownReason)
        assertEquals(0, ledger.unmeteredResponses)
    }

    @Test
    fun stopsAtTheCumulativeTokenBound() {
        val ledger = RunUsageLedger(maxTokens = 10)
        assertTrue(ledger.record(request(), response(6, 2)) is RunUsageVerdict.Within)
        val verdict = ledger.record(request(), response(2, 1))
        assertTrue(verdict is RunUsageVerdict.Exceeded)
        assertTrue((verdict as RunUsageVerdict.Exceeded).reason.contains("token budget"))
        assertEquals(11L, ledger.inputTokens + ledger.outputTokens)
        assertTrue(ledger.exceededReason!!.contains("token budget"))
    }

    @Test
    fun stopsAtTheCostBound() {
        val ledger = RunUsageLedger(maxTokens = 1_000_000, flashUsdCap = 0.000001)
        val verdict = ledger.record(request(), response(10, 1))
        assertTrue(verdict is RunUsageVerdict.Exceeded)
        assertTrue((verdict as RunUsageVerdict.Exceeded).reason.contains("cost budget"))
        assertTrue(ledger.exceededReason!!.contains("cost budget"))
    }

    @Test
    fun missingUsageIsUnknownNotWithin() {
        val ledger = RunUsageLedger()
        val verdict = ledger.record(request(), """{"choices":[]}""")
        assertTrue(verdict is RunUsageVerdict.Unknown)
        assertTrue((verdict as RunUsageVerdict.Unknown).reason.contains("usage block missing"))
        assertEquals(1, ledger.unmeteredResponses)
        assertEquals(0L, ledger.inputTokens + ledger.outputTokens)
        assertTrue(ledger.unknownReason!!.contains("usage block missing"))
        assertNull(ledger.exceededReason)
    }

    @Test
    fun partialUsageIsUnknown() {
        val ledger = RunUsageLedger()
        val verdict = ledger.record(request(), rawResponse(JsonPrimitive(10), null))
        assertTrue(verdict is RunUsageVerdict.Unknown)
        assertTrue((verdict as RunUsageVerdict.Unknown).reason.contains("completion_tokens"))
        assertEquals(0L, ledger.inputTokens + ledger.outputTokens)
    }

    @Test
    fun malformedUsageIsUnknown() {
        val ledger = RunUsageLedger()
        val verdict = ledger.record(
            request(),
            rawResponse(JsonPrimitive("ten"), JsonPrimitive(5)),
        )
        assertTrue(verdict is RunUsageVerdict.Unknown)
        assertTrue((verdict as RunUsageVerdict.Unknown).reason.contains("prompt_tokens"))
    }

    @Test
    fun negativeUsageIsUnknown() {
        val ledger = RunUsageLedger()
        val verdict = ledger.record(request(), response(-1, 5))
        assertTrue(verdict is RunUsageVerdict.Unknown)
        assertTrue((verdict as RunUsageVerdict.Unknown).reason.contains("negative"))
    }

    @Test
    fun overflowingUsageIsUnknown() {
        val ledger = RunUsageLedger()
        val verdict = ledger.record(request(), response(Long.MAX_VALUE, 1))
        assertTrue(verdict is RunUsageVerdict.Unknown)
        assertTrue((verdict as RunUsageVerdict.Unknown).reason.contains("overflow"))
    }

    @Test
    fun zeroUsageIsAValidMeasurement() {
        val ledger = RunUsageLedger()
        assertTrue(ledger.record(request(), response(0, 0)) is RunUsageVerdict.Within)
        assertNull(ledger.unknownReason)
    }

    @Test
    fun proModelUsesTheProRates() {
        val ledger = RunUsageLedger(maxTokens = 1_000_000, proUsdCap = 0.000001)
        val verdict = ledger.record(request("deepseek-v4-pro"), response(10, 1))
        assertTrue(verdict is RunUsageVerdict.Exceeded)
        assertTrue((verdict as RunUsageVerdict.Exceeded).reason.contains("cost budget"))
    }
}

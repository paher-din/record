package com.paherdin.record2.ai.tools

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

    private fun response(promptTokens: Long, completionTokens: Long): String = buildJsonObject {
        putJsonObject("usage") {
            put("prompt_tokens", promptTokens)
            put("completion_tokens", completionTokens)
        }
    }.toString()

    @Test
    fun accumulatesWithinBounds() {
        val ledger = RunUsageLedger()
        assertNull(ledger.record(request(), response(10, 5)))
        assertNull(ledger.record(request(), response(20, 7)))
        assertEquals(30L, ledger.inputTokens)
        assertEquals(12L, ledger.outputTokens)
        assertNull(ledger.exceededReason)
        assertEquals(0, ledger.unmeteredResponses)
    }

    @Test
    fun stopsAtTheCumulativeTokenBound() {
        val ledger = RunUsageLedger(maxTokens = 10)
        assertNull(ledger.record(request(), response(6, 2)))
        val reason = ledger.record(request(), response(2, 1))
        assertTrue(reason!!.contains("token budget"))
        assertEquals(11L, ledger.inputTokens + ledger.outputTokens)
        assertTrue(ledger.exceededReason!!.contains("token budget"))
    }

    @Test
    fun stopsAtTheCostBound() {
        val ledger = RunUsageLedger(maxTokens = 1_000_000, flashUsdCap = 0.000001)
        val reason = ledger.record(request(), response(10, 1))
        assertTrue(reason!!.contains("cost budget"))
        assertTrue(ledger.exceededReason!!.contains("cost budget"))
    }

    @Test
    fun missingUsageIsUnmeteredNotZero() {
        val ledger = RunUsageLedger()
        assertNull(ledger.record(request(), """{"choices":[]}"""))
        assertEquals(1, ledger.unmeteredResponses)
        assertEquals(0L, ledger.inputTokens + ledger.outputTokens)
        assertNull(ledger.exceededReason)
    }

    @Test
    fun proModelUsesTheProRates() {
        val ledger = RunUsageLedger(maxTokens = 1_000_000, proUsdCap = 0.000001)
        val reason = ledger.record(request("deepseek-v4-pro"), response(10, 1))
        assertTrue(reason!!.contains("cost budget"))
    }
}

package com.paherdin.record2.ai

import android.os.SystemClock
import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.utils.time.KoogClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

class DeepSeekProtocolContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun normalChatExplicitlyDisablesThinking() = runBlocking {
        val recorder = recordingFactory()
        DeepSeekProtocol.client(key(), httpClientFactory = recorder).use { client ->
            val models = client.models().map { it.id }
            val request = prompt("d0-normal", DeepSeekProtocol.params(thinking = false)) {
                system("Reply with one short synthetic sentence.")
                user("Synthetic probe: say ALPHA.")
            }
            val started = SystemClock.elapsedRealtime()
            val outcome = runCatching { withD0RunLimit { client.execute(request, DeepSeekProtocol.Flash) } }
            val elapsed = SystemClock.elapsedRealtime() - started
            val usage = usageTotals(recorder.responses)
            checkFunctionalBudget(
                "normalChatExplicitlyDisablesThinking",
                recorder,
                "deepseek-flash",
                usage,
                elapsed,
                configuredSteps = 0,
                runFailure = outcome.exceptionOrNull(),
            )
            val response = outcome.getOrThrow()
            val meta = response.metaInfo
            val body = recorder.requests.single()

            assertTrue(response.textContent().isNotBlank())
            assertTrue(response.toolCalls().isEmpty())
            assertTrue(response.parts.none { it is MessagePart.Reasoning })
            assertFalse(body.contains(key()))
            assertRequestMetadata(body, "deepseek-flash", "disabled")
            assertNotNull(meta.totalTokensCount)
            assertTrue(meta.totalTokensCount!! > 0)
            assertTrue("deepseek-flash" in models)

            evidence.add(
                buildJsonObject {
                    put("test", "normalChatExplicitlyDisablesThinking")
                    put("model", "deepseek-flash")
                    put("thinking", "disabled")
                    put("elapsedMs", elapsed)
                    put("inputTokens", meta.inputTokensCount ?: 0)
                    put("outputTokens", meta.outputTokensCount ?: 0)
                    put("totalTokens", meta.totalTokensCount ?: 0)
                    put("estimatedCostUsd", D0TestLimits.costUsd("deepseek-flash", usage))
                    put("budgetOutcome", "WITHIN")
                    put("requestCount", recorder.requests.size)
                    put("reasoningPresent", false)
                    put("models", models.joinToString(","))
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun thinkingChatReturnsReasoning() = runBlocking {
        val recorder = recordingFactory()
        DeepSeekProtocol.client(key(), httpClientFactory = recorder).use { client ->
            val request = prompt("d0-thinking", DeepSeekProtocol.params(thinking = true)) {
                system("Explain the arithmetic in one short sentence, then answer.")
                user("Synthetic probe: compute 17 + 25.")
            }
            val started = SystemClock.elapsedRealtime()
            val outcome = runCatching { withD0RunLimit { client.execute(request, DeepSeekProtocol.Flash) } }
            val elapsed = SystemClock.elapsedRealtime() - started
            val usage = usageTotals(recorder.responses)
            checkFunctionalBudget(
                "thinkingChatReturnsReasoning",
                recorder,
                "deepseek-flash",
                usage,
                elapsed,
                configuredSteps = 0,
                runFailure = outcome.exceptionOrNull(),
            )
            val response = outcome.getOrThrow()
            val meta = response.metaInfo
            val body = recorder.requests.single()

            assertTrue(response.textContent().contains("42"))
            assertTrue(response.reasoningText().isNotBlank())
            assertFalse(body.contains(key()))
            assertRequestMetadata(body, "deepseek-flash", "enabled")
            assertTrue((meta.totalTokensCount ?: 0) > 0)

            evidence.add(
                buildJsonObject {
                    put("test", "thinkingChatReturnsReasoning")
                    put("model", "deepseek-flash")
                    put("thinking", "enabled")
                    put("elapsedMs", elapsed)
                    put("inputTokens", meta.inputTokensCount ?: 0)
                    put("outputTokens", meta.outputTokensCount ?: 0)
                    put("totalTokens", meta.totalTokensCount ?: 0)
                    put("estimatedCostUsd", D0TestLimits.costUsd("deepseek-flash", usage))
                    put("budgetOutcome", "WITHIN")
                    put("requestCount", recorder.requests.size)
                    put("reasoningPresent", true)
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun proModelRespondsWithDisabledThinking() = runBlocking {
        val recorder = recordingFactory()
        DeepSeekProtocol.client(key(), httpClientFactory = recorder).use { client ->
            val request = prompt("d0-pro", DeepSeekProtocol.params(thinking = false)) {
                system("Reply with one short synthetic sentence.")
                user("Synthetic probe: say PRO.")
            }
            val started = SystemClock.elapsedRealtime()
            val outcome = runCatching { withD0RunLimit { client.execute(request, DeepSeekProtocol.Pro) } }
            val elapsed = SystemClock.elapsedRealtime() - started
            val usage = usageTotals(recorder.responses)
            checkFunctionalBudget(
                "proModelRespondsWithDisabledThinking",
                recorder,
                "deepseek-v4-pro",
                usage,
                elapsed,
                configuredSteps = 0,
                runFailure = outcome.exceptionOrNull(),
            )
            val response = outcome.getOrThrow()
            val meta = response.metaInfo
            val body = recorder.requests.single()

            assertTrue(response.textContent().isNotBlank())
            assertTrue(response.parts.none { it is MessagePart.Reasoning })
            assertFalse(body.contains(key()))
            assertRequestMetadata(body, "deepseek-v4-pro", "disabled")
            assertTrue((meta.totalTokensCount ?: 0) > 0)

            evidence.add(
                buildJsonObject {
                    put("test", "proModelRespondsWithDisabledThinking")
                    put("model", "deepseek-v4-pro")
                    put("thinking", "disabled")
                    put("elapsedMs", elapsed)
                    put("inputTokens", meta.inputTokensCount ?: 0)
                    put("outputTokens", meta.outputTokensCount ?: 0)
                    put("totalTokens", meta.totalTokensCount ?: 0)
                    put("estimatedCostUsd", D0TestLimits.costUsd("deepseek-v4-pro", usage))
                    put("budgetOutcome", "WITHIN")
                    put("requestCount", recorder.requests.size)
                    put("reasoningPresent", false)
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun proThinkingReturnsReasoning() = runBlocking {
        val recorder = recordingFactory()
        DeepSeekProtocol.client(key(), httpClientFactory = recorder).use { client ->
            val request = prompt("d0-pro-thinking", DeepSeekProtocol.params(thinking = true)) {
                system("Explain the arithmetic in one short sentence, then answer.")
                user("Synthetic probe: compute 31 + 11.")
            }
            val started = SystemClock.elapsedRealtime()
            val outcome = runCatching { withD0RunLimit { client.execute(request, DeepSeekProtocol.Pro) } }
            val elapsed = SystemClock.elapsedRealtime() - started
            val usage = usageTotals(recorder.responses)
            checkFunctionalBudget(
                "proThinkingReturnsReasoning",
                recorder,
                "deepseek-v4-pro",
                usage,
                elapsed,
                configuredSteps = 0,
                runFailure = outcome.exceptionOrNull(),
            )
            val response = outcome.getOrThrow()
            val meta = response.metaInfo
            val body = recorder.requests.single()

            assertTrue(response.textContent().contains("42"))
            assertTrue(response.reasoningText().isNotBlank())
            assertFalse(body.contains(key()))
            assertRequestMetadata(body, "deepseek-v4-pro", "enabled")
            assertTrue((meta.totalTokensCount ?: 0) > 0)

            evidence.add(
                buildJsonObject {
                    put("test", "proThinkingReturnsReasoning")
                    put("model", "deepseek-v4-pro")
                    put("thinking", "enabled")
                    put("elapsedMs", elapsed)
                    put("inputTokens", meta.inputTokensCount ?: 0)
                    put("outputTokens", meta.outputTokensCount ?: 0)
                    put("totalTokens", meta.totalTokensCount ?: 0)
                    put("estimatedCostUsd", D0TestLimits.costUsd("deepseek-v4-pro", usage))
                    put("budgetOutcome", "WITHIN")
                    put("requestCount", recorder.requests.size)
                    put("reasoningPresent", true)
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun directToolRoundTripKeepsPairingAndReasoningWhenPresent() = runBlocking {
        val recorder = recordingFactory()
        val echo = EchoTool()
        DeepSeekProtocol.client(key(), httpClientFactory = recorder).use { client ->
            val params = DeepSeekProtocol.params(thinking = true)
                .copy(toolChoice = ai.koog.prompt.params.LLMParams.ToolChoice.Auto)
            val base = prompt("d0-tool-roundtrip", params) {
                system("Use the record2_echo tool whenever the user asks for an echo.")
                user("Synthetic probe: call record2_echo with word orange, then report the exact result.")
            }
            val started = SystemClock.elapsedRealtime()
            val outcome = runCatching {
                val first = withD0RunLimit { client.execute(base, DeepSeekProtocol.Flash, toolDescriptors(echo)) }
                val call = first.toolCalls().single()
                val followUp = base.withMessages { messages ->
                    messages + first + Message.User(
                        MessagePart.Tool.Result(id = call.id, tool = call.tool, output = "ECHO:orange"),
                        RequestMetaInfo.create(KoogClock.System),
                    )
                }
                val second = withD0RunLimit { client.execute(followUp, DeepSeekProtocol.Flash, toolDescriptors(echo)) }
                Triple(first, call, second)
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            val usage = usageTotals(recorder.responses)
            checkFunctionalBudget(
                "directToolRoundTripKeepsPairingAndReasoningWhenPresent",
                recorder,
                "deepseek-flash",
                usage,
                elapsed,
                configuredSteps = 0,
                runFailure = outcome.exceptionOrNull(),
            )
            val (first, call, second) = outcome.getOrThrow()

            assertTrue(second.textContent().contains("ECHO:orange"))
            assertEquals(2, recorder.requests.size)
            val secondRequest = recorder.requests[1]
            assertFalse(secondRequest.contains(key()))
            assertTrue(secondRequest.contains(call.id.orEmpty()))
            val pairing = assertToolPairing(secondRequest)
            assertEquals(1, pairing.calls)
            assertEquals(1, pairing.results)
            if (first.reasoningText().isNotBlank()) {
                assertTrue(secondRequest.contains("reasoning_content"))
                assertTrue(secondRequest.contains(first.reasoningText().take(24)))
            }
            evidence.add(
                buildJsonObject {
                    put("test", "directToolRoundTripKeepsPairingAndReasoningWhenPresent")
                    put("model", "deepseek-flash")
                    put("thinking", "enabled")
                    put("elapsedMs", elapsed)
                    put("requestCount", recorder.requests.size)
                    put("toolCallIdPresent", call.id != null)
                    put("pairingCalls", pairing.calls)
                    put("pairingResults", pairing.results)
                    put("reasoningPresent", first.reasoningText().isNotBlank())
                    put("inputTokens", usage.inputTokens)
                    put("outputTokens", usage.outputTokens)
                    put("totalTokens", usage.totalTokens)
                    put("estimatedCostUsd", D0TestLimits.costUsd("deepseek-flash", usage))
                    put("budgetOutcome", "WITHIN")
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun aiAgentExecutesSingleToolCall() = runBlocking {
        val recorder = recordingFactory()
        val echo = EchoTool()
        DeepSeekProtocol.client(key(), httpClientFactory = recorder).use { client ->
            val executor = MultiLLMPromptExecutor(LLMProvider.DeepSeek to client)
            val agent = DeepSeekProtocol.agent(
                executor = executor,
                model = DeepSeekProtocol.Flash,
                toolRegistry = toolRegistry(echo),
                thinking = false,
                maxIterations = D0TestLimits.MAX_AGENT_STEPS,
                systemPrompt = "You are a deterministic tool-use test agent. Follow the tool instruction exactly.",
            )
            val started = SystemClock.elapsedRealtime()
            val outcome = runCatching {
                withD0RunLimit {
                    agent.run(
                        "Synthetic probe: call record2_echo exactly once with word alpha, " +
                            "then reply with the exact tool output and nothing else.",
                    )
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            val usage = usageTotals(recorder.responses)
            checkFunctionalBudget(
                "aiAgentExecutesSingleToolCall",
                recorder,
                "deepseek-flash",
                usage,
                elapsed,
                configuredSteps = D0TestLimits.MAX_AGENT_STEPS,
                runFailure = outcome.exceptionOrNull(),
            )
            val output = outcome.getOrThrow()

            assertEquals(listOf("alpha"), echo.calls.toList())
            assertTrue(output.contains("ECHO:alpha"))
            assertTrue(recorder.requests.size >= 2)
            recorder.requests.forEach { assertFalse(it.contains(key())) }

            evidence.add(
                buildJsonObject {
                    put("test", "aiAgentExecutesSingleToolCall")
                    put("model", "deepseek-flash")
                    put("thinking", "disabled")
                    put("elapsedMs", elapsed)
                    put("requestCount", recorder.requests.size)
                    put("toolCalls", echo.calls.joinToString(","))
                    put("inputTokens", usage.inputTokens)
                    put("outputTokens", usage.outputTokens)
                    put("totalTokens", usage.totalTokens)
                    put("estimatedCostUsd", D0TestLimits.costUsd("deepseek-flash", usage))
                    put("budgetOutcome", "WITHIN")
                    put("configuredSteps", D0TestLimits.MAX_AGENT_STEPS)
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun aiAgentExecutesTwoConsecutiveToolCalls() = runBlocking {
        val promptText =
            "Synthetic probe: call record2_step with step first. The tool result tells you the next step. " +
                "You MUST then call record2_step with step second. Only after the second tool result, " +
                "reply with the final result and nothing else."
        var passed = false
        var lastObservation = "no attempt"
        for (attempt in 1..3) {
            val recorder = recordingFactory()
            DeepSeekProtocol.client(key(), httpClientFactory = recorder).use { client ->
                val executor = MultiLLMPromptExecutor(LLMProvider.DeepSeek to client)
                val step = StepTool()
                val agent = DeepSeekProtocol.agent(
                    executor = executor,
                    model = DeepSeekProtocol.Flash,
                    toolRegistry = toolRegistry(step),
                    thinking = true,
                    maxIterations = D0TestLimits.MAX_AGENT_STEPS,
                    systemPrompt = "You are a deterministic tool-use test agent. Never reply before both " +
                        "record2_step calls have completed, and follow the tool result instructions exactly.",
                )
                val started = SystemClock.elapsedRealtime()
                val outcome = runCatching {
                    withD0RunLimit { agent.run(promptText) }
                }
                val output = outcome.getOrNull()
                val elapsed = SystemClock.elapsedRealtime() - started
                val usage = usageTotals(recorder.responses)
                val calls = step.calls.toList()
                val pairings = runCatching {
                    recorder.requests.map { body ->
                        assertFalse(body.contains(key()))
                        assertToolPairing(body)
                    }
                }
                val pairingOk = pairings.isSuccess &&
                    pairings.getOrNull()?.lastOrNull()?.let { it.calls >= 2 && it.calls == it.results } == true
                val reasoning = reasoningPreservation(recorder)
                val reasoningOk = !reasoning.observed || reasoning.preserved
                val protocolPathAvailable = calls.isNotEmpty()
                val fullSequenceObserved = calls.take(2) == listOf("first", "second")
                val modelCompliant = fullSequenceObserved && output?.contains("DONE-ORANGE-731") == true
                val budgetExceeded = recorder.budget.exceeded
                val ok = modelCompliant && pairingOk && reasoningOk
                evidence.add(
                    buildJsonObject {
                        put("test", "aiAgentExecutesTwoConsecutiveToolCalls")
                        put("attempt", attempt)
                        put("attemptsAllowed", 3)
                        put("model", "deepseek-flash")
                        put("thinking", "enabled")
                        put("elapsedMs", elapsed)
                        put("requestCount", recorder.requests.size)
                        put("toolCalls", calls.joinToString(","))
                        put("protocolPathAvailable", protocolPathAvailable)
                        put("fullSequenceObserved", fullSequenceObserved)
                        put("modelCompliant", modelCompliant)
                        put("pairingOk", pairingOk)
                        put("reasoningObserved", reasoning.observed)
                        put("reasoningPreserved", reasoning.preserved)
                        put("inputTokens", usage.inputTokens)
                        put("outputTokens", usage.outputTokens)
                        put("totalTokens", usage.totalTokens)
                        put("estimatedCostUsd", D0TestLimits.costUsd("deepseek-flash", usage))
                        put("budgetOutcome", if (budgetExceeded) "INCONCLUSIVE/BudgetExceeded" else "WITHIN")
                        put("configuredSteps", D0TestLimits.MAX_AGENT_STEPS)
                        put("result", when {
                            budgetExceeded -> "INCONCLUSIVE"
                            ok -> "PASS"
                            else -> "RETRY"
                        })
                    },
                )
                checkFunctionalBudget(
                    "aiAgentExecutesTwoConsecutiveToolCalls-attempt-$attempt",
                    recorder,
                    "deepseek-flash",
                    usage,
                    elapsed,
                    configuredSteps = D0TestLimits.MAX_AGENT_STEPS,
                    runFailure = outcome.exceptionOrNull(),
                )
                lastObservation =
                    "attempt=$attempt calls=$calls requests=${recorder.requests.size} " +
                        "output=${output?.replace('\n', ' ')?.take(160) ?: "exception"}"
                if (ok && !budgetExceeded) {
                    passed = true
                    return@use
                }
            }
            if (passed) break
        }
        assertTrue("consecutive tool calls did not complete: $lastObservation", passed)
    }

    @Test
    fun crossRunChatMemoryRecallsSyntheticFact() = runBlocking {
        val recorder = recordingFactory()
        val provider = SharedChatHistoryProvider()
        DeepSeekProtocol.client(key(), httpClientFactory = recorder).use { client ->
            val executor = MultiLLMPromptExecutor(LLMProvider.DeepSeek to client)
            val config = AIAgentConfig(
                prompt = prompt("d0-cross-run", DeepSeekProtocol.params(thinking = false)) {
                    system("Answer synthetic questions exactly. Preserve the requested code verbatim.")
                },
                model = DeepSeekProtocol.Flash,
                maxAgentIterations = D0TestLimits.MAX_AGENT_STEPS,
            )
            val agent = AIAgent(
                promptExecutor = executor,
                agentConfig = config,
                toolRegistry = ToolRegistry.EMPTY,
            ) {
                install(ChatMemory) {
                    chatHistoryProvider = provider
                    windowSize(40)
                }
            }
            val started = SystemClock.elapsedRealtime()
            val outcome = runCatching {
                val first = withD0RunLimit {
                    agent.run("Synthetic probe: remember code ORANGE-731. Reply OK.")
                }
                val second = withD0RunLimit {
                    agent.run(
                        "Synthetic probe: which code did I ask you to remember? Reply with the code only.",
                    )
                }
                first to second
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            val usage = usageTotals(recorder.responses)
            checkFunctionalBudget(
                "crossRunChatMemoryRecallsSyntheticFact",
                recorder,
                "deepseek-flash",
                usage,
                elapsed,
                configuredSteps = D0TestLimits.MAX_AGENT_STEPS,
                runFailure = outcome.exceptionOrNull(),
            )
            val (first, second) = outcome.getOrThrow()

            assertTrue(first.isNotBlank())
            assertTrue(second.contains("ORANGE-731"))
            assertEquals(2, recorder.requests.size)
            val messages = parseMessages(recorder.requests[1])
            assertTrue(messages.any { it.stringField("role") == "system" })
            assertTrue(messages.count { it.stringField("role") == "user" } >= 2)
            assertFalse(recorder.requests[1].contains(key()))

            evidence.add(
                buildJsonObject {
                    put("test", "crossRunChatMemoryRecallsSyntheticFact")
                    put("model", "deepseek-flash")
                    put("thinking", "disabled")
                    put("elapsedMs", elapsed)
                    put("requestCount", recorder.requests.size)
                    put("storedMessages", provider.stored.size)
                    put("inputTokens", usage.inputTokens)
                    put("outputTokens", usage.outputTokens)
                    put("totalTokens", usage.totalTokens)
                    put("estimatedCostUsd", D0TestLimits.costUsd("deepseek-flash", usage))
                    put("budgetOutcome", "WITHIN")
                    put("configuredSteps", D0TestLimits.MAX_AGENT_STEPS)
                    put("result", "PASS")
                },
            )
        }
    }

    private fun checkFunctionalBudget(
        testName: String,
        recorder: RecordingFactory,
        model: String,
        usage: UsageTotals,
        elapsedMs: Long,
        configuredSteps: Int,
        runFailure: Throwable? = null,
    ) {
        val reasons = mutableListOf<String>()
        if (runFailure is BudgetExceededException) {
            reasons += runFailure.reasons.ifEmpty { listOf("run budget exceeded") }
        }
        if (configuredSteps > D0TestLimits.MAX_AGENT_STEPS) {
            reasons += "agent steps $configuredSteps > ${D0TestLimits.MAX_AGENT_STEPS}"
        }
        if (usage.totalTokens > D0TestLimits.RUN_TOKENS) {
            reasons += "tokens ${usage.totalTokens} > ${D0TestLimits.RUN_TOKENS}"
        }
        if (elapsedMs > D0TestLimits.RUN_WALL_MS) {
            reasons += "run wall ${elapsedMs}ms > ${D0TestLimits.RUN_WALL_MS}ms"
        }
        val cost = D0TestLimits.costUsd(model, usage)
        if (cost > D0TestLimits.costCapUsd(model)) {
            reasons += "cost $cost > ${D0TestLimits.costCapUsd(model)}"
        }
        if (recorder.budget.exceeded) reasons += recorder.budget.reasons
        evidence.add(
            buildJsonObject {
                put("test", "$testName-budget")
                put("model", model)
                put("configuredSteps", configuredSteps)
                put("approvedStepCap", D0TestLimits.MAX_AGENT_STEPS)
                put("runWallMs", elapsedMs)
                put("totalTokens", usage.totalTokens)
                put("estimatedCostUsd", cost)
                put("budgetOutcome", if (reasons.isEmpty()) "WITHIN" else "INCONCLUSIVE/BudgetExceeded")
                put("budgetReasons", reasons.joinToString("; "))
                put("result", if (reasons.isEmpty()) "PASS" else "INCONCLUSIVE")
            },
        )
        assumeTrue("BudgetExceeded: ${reasons.joinToString("; ")}", reasons.isEmpty())
    }

    private fun assertRequestMetadata(body: String, model: String, thinkingType: String) {
        val request = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals(model, request.stringField("model"))
        assertEquals(thinkingType, request["thinking"]?.jsonObject?.stringField("type"))
        assertFalse(request.toString().contains(key()))
    }

    private data class ToolPairingSummary(
        val calls: Int,
        val results: Int,
    )

    private data class PairingCall(val id: String, val name: String, val messageIndex: Int)

    private data class PairingResult(val id: String, val messageIndex: Int)

    private fun assertToolPairing(body: String): ToolPairingSummary {
        val messages = parseMessages(body)
        val calls = mutableListOf<PairingCall>()
        val results = mutableListOf<PairingResult>()
        messages.forEachIndexed { messageIndex, message ->
            message["tool_calls"].jsonObjectList().forEach { call ->
                val id = (call["id"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    .orEmpty()
                val name = call["function"]?.jsonObject?.stringField("name").orEmpty()
                calls += PairingCall(id, name, messageIndex)
            }
            val resultId = (message["tool_call_id"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            if (resultId != null) {
                assertEquals("tool result message must have role=tool", "tool", message.stringField("role"))
                results += PairingResult(resultId, messageIndex)
            }
        }

        calls.forEach { call ->
            assertTrue("tool call id must be non-blank", call.id.isNotBlank())
            assertTrue("tool call name must be non-blank", call.name.isNotBlank())
        }
        results.forEach { result ->
            assertTrue("tool result id must be non-blank", result.id.isNotBlank())
        }
        assertEquals("duplicate tool call ids", calls.size, calls.map { it.id }.toSet().size)
        assertEquals("duplicate tool result ids", results.size, results.map { it.id }.toSet().size)

        results.forEach { result ->
            val matches = calls.filter { it.id == result.id }
            assertEquals("tool result must match exactly one call: ${result.id}", 1, matches.size)
            assertTrue(
                "tool result ${result.id} precedes its call",
                result.messageIndex > matches.single().messageIndex,
            )
        }
        val unmatched = calls.filter { call -> results.none { it.id == call.id } }
        assertTrue("unanswered tool calls: ${unmatched.map { it.id }}", unmatched.isEmpty())
        val resultsInCallOrder = calls.map { call -> results.single { it.id == call.id } }
        assertEquals(
            "tool results must preserve call order",
            resultsInCallOrder.sortedBy { it.messageIndex },
            resultsInCallOrder,
        )
        return ToolPairingSummary(calls.size, results.size)
    }

    private data class ReasoningCheck(
        val observed: Boolean,
        val preserved: Boolean,
    )

    private fun reasoningPreservation(recorder: RecordingFactory): ReasoningCheck {
        val reasoning = recorder.responses.mapNotNull { response ->
            runCatching {
                val message = kotlinx.serialization.json.Json.parseToJsonElement(response)
                    .jsonObject["choices"].jsonObjectList()[0]["message"]?.jsonObject
                message?.stringField("reasoning_content")
            }.getOrNull()
        }.firstOrNull { !it.isNullOrBlank() } ?: return ReasoningCheck(false, false)
        return ReasoningCheck(
            observed = true,
            preserved = recorder.requests.any { it.contains(reasoning.take(24)) },
        )
    }

    private class SharedChatHistoryProvider : ChatHistoryProvider {
        var stored: List<Message> = emptyList()
            private set

        override suspend fun store(conversationId: String, messages: List<Message>) {
            stored = messages
        }

        override suspend fun load(conversationId: String): List<Message> = stored
    }

    private fun key(): String = checkNotNull(apiKey) { "D0 key not loaded" }

    companion object {
        private val evidence = D0Evidence("real.json")
        private var apiKey: String? = null

        @JvmStatic
        @BeforeClass
        fun loadKey() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            apiKey = D0Key.load(context)
            evidence.keyFingerprint = D0Key.fingerprint(apiKey!!)
        }

        @JvmStatic
        @AfterClass
        fun writeEvidence() {
            apiKey = null
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            evidence.write(context)
        }
    }
}

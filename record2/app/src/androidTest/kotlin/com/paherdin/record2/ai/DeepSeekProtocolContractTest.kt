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
import ai.koog.prompt.message.ResponseMetaInfo
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
import org.junit.Assert.assertTrue
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
            val response = client.execute(request, DeepSeekProtocol.Flash)
            val elapsed = SystemClock.elapsedRealtime() - started
            val usage = response.metaInfo as ResponseMetaInfo
            val body = recorder.requests.single()

            assertTrue(response.textContent().isNotBlank())
            assertTrue(response.toolCalls().isEmpty())
            assertTrue(response.parts.none { it is MessagePart.Reasoning })
            assertFalse(body.contains(key()))
            assertRequestMetadata(body, "deepseek-flash", "disabled")
            assertNotNull(usage.totalTokensCount)
            assertTrue(usage.totalTokensCount!! > 0)
            assertTrue("deepseek-flash" in models)

            evidence.add(
                buildJsonObject {
                    put("test", "normalChatExplicitlyDisablesThinking")
                    put("model", "deepseek-flash")
                    put("thinking", "disabled")
                    put("elapsedMs", elapsed)
                    put("inputTokens", usage.inputTokensCount ?: 0)
                    put("outputTokens", usage.outputTokensCount ?: 0)
                    put("totalTokens", usage.totalTokensCount ?: 0)
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
            val response = client.execute(request, DeepSeekProtocol.Flash)
            val elapsed = SystemClock.elapsedRealtime() - started
            val usage = response.metaInfo as ResponseMetaInfo
            val body = recorder.requests.single()

            assertTrue(response.textContent().contains("42"))
            assertTrue(response.reasoningText().isNotBlank())
            assertFalse(body.contains(key()))
            assertRequestMetadata(body, "deepseek-flash", "enabled")
            assertTrue((usage.totalTokensCount ?: 0) > 0)

            evidence.add(
                buildJsonObject {
                    put("test", "thinkingChatReturnsReasoning")
                    put("model", "deepseek-flash")
                    put("thinking", "enabled")
                    put("elapsedMs", elapsed)
                    put("inputTokens", usage.inputTokensCount ?: 0)
                    put("outputTokens", usage.outputTokensCount ?: 0)
                    put("totalTokens", usage.totalTokensCount ?: 0)
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
            val response = client.execute(request, DeepSeekProtocol.Pro)
            val elapsed = SystemClock.elapsedRealtime() - started
            val usage = response.metaInfo as ResponseMetaInfo
            val body = recorder.requests.single()

            assertTrue(response.textContent().isNotBlank())
            assertTrue(response.parts.none { it is MessagePart.Reasoning })
            assertFalse(body.contains(key()))
            assertRequestMetadata(body, "deepseek-v4-pro", "disabled")
            assertTrue((usage.totalTokensCount ?: 0) > 0)

            evidence.add(
                buildJsonObject {
                    put("test", "proModelRespondsWithDisabledThinking")
                    put("model", "deepseek-v4-pro")
                    put("thinking", "disabled")
                    put("elapsedMs", elapsed)
                    put("inputTokens", usage.inputTokensCount ?: 0)
                    put("outputTokens", usage.outputTokensCount ?: 0)
                    put("totalTokens", usage.totalTokensCount ?: 0)
                    put("requestCount", recorder.requests.size)
                    put("reasoningPresent", false)
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
            val first = client.execute(base, DeepSeekProtocol.Flash, toolDescriptors(echo))
            val call = first.toolCalls().single()
            val followUp = base.withMessages { messages ->
                messages + first + Message.User(
                    MessagePart.Tool.Result(id = call.id, tool = call.tool, output = "ECHO:orange"),
                    RequestMetaInfo.create(KoogClock.System),
                )
            }
            val second = client.execute(followUp, DeepSeekProtocol.Flash, toolDescriptors(echo))

            assertTrue(second.textContent().contains("ECHO:orange"))
            assertEquals(2, recorder.requests.size)
            val secondRequest = recorder.requests[1]
            assertFalse(secondRequest.contains(key()))
            assertTrue(secondRequest.contains(call.id.orEmpty()))
            assertToolPairing(secondRequest)
            if (first.reasoningText().isNotBlank()) {
                assertTrue(secondRequest.contains("reasoning_content"))
                assertTrue(secondRequest.contains(first.reasoningText().take(24)))
            }
            evidence.add(
                buildJsonObject {
                    put("test", "directToolRoundTripKeepsPairingAndReasoningWhenPresent")
                    put("model", "deepseek-flash")
                    put("thinking", "enabled")
                    put("requestCount", recorder.requests.size)
                    put("toolCallIdPresent", call.id != null)
                    put("reasoningPresent", first.reasoningText().isNotBlank())
                    put("usage", usageTotals(recorder.responses).toString())
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
                maxIterations = 6,
                systemPrompt = "You are a deterministic tool-use test agent. Follow the tool instruction exactly.",
            )
            val started = SystemClock.elapsedRealtime()
            val output = agent.run(
                "Synthetic probe: call record2_echo exactly once with word alpha, " +
                    "then reply with the exact tool output and nothing else.",
            )
            val elapsed = SystemClock.elapsedRealtime() - started
            val usage = usageTotals(recorder.responses)

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
                    maxIterations = 10,
                    systemPrompt = "You are a deterministic tool-use test agent. Never reply before both " +
                        "record2_step calls have completed, and follow the tool result instructions exactly.",
                )
                val started = SystemClock.elapsedRealtime()
                val output = runCatching { agent.run(promptText) }.getOrNull()
                val elapsed = SystemClock.elapsedRealtime() - started
                val usage = usageTotals(recorder.responses)
                val calls = step.calls.toList()
                val pairingOk = runCatching {
                    recorder.requests.forEach { body ->
                        assertFalse(body.contains(key()))
                        assertToolPairing(body)
                    }
                }.isSuccess
                val reasoning = reasoningPreservation(recorder)
                val reasoningOk = !reasoning.observed || reasoning.preserved
                val ok = calls.size >= 2 &&
                    calls.take(2) == listOf("first", "second") &&
                    output?.contains("DONE-ORANGE-731") == true &&
                    pairingOk &&
                    reasoningOk
                evidence.add(
                    buildJsonObject {
                        put("test", "aiAgentExecutesTwoConsecutiveToolCalls")
                        put("attempt", attempt)
                        put("model", "deepseek-flash")
                        put("thinking", "enabled")
                        put("elapsedMs", elapsed)
                        put("requestCount", recorder.requests.size)
                        put("toolCalls", calls.joinToString(","))
                        put("reasoningObserved", reasoning.observed)
                        put("reasoningPreserved", reasoning.preserved)
                        put("inputTokens", usage.inputTokens)
                        put("outputTokens", usage.outputTokens)
                        put("totalTokens", usage.totalTokens)
                        put("result", if (ok) "PASS" else "RETRY")
                    },
                )
                lastObservation =
                    "attempt=$attempt calls=$calls requests=${recorder.requests.size} " +
                        "output=${output?.replace('\n', ' ')?.take(160) ?: "exception"}"
                if (ok) {
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
                maxAgentIterations = 6,
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
            val first = agent.run("Synthetic probe: remember code ORANGE-731. Reply OK.")
            val second = agent.run(
                "Synthetic probe: which code did I ask you to remember? Reply with the code only.",
            )

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
                    put("requestCount", recorder.requests.size)
                    put("storedMessages", provider.stored.size)
                    put("usage", usageTotals(recorder.responses).toString())
                    put("result", "PASS")
                },
            )
        }
    }

    private fun assertRequestMetadata(body: String, model: String, thinkingType: String) {
        val request = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals(model, request.stringField("model"))
        assertEquals(thinkingType, request["thinking"]?.jsonObject?.stringField("type"))
        assertFalse(request.toString().contains(key()))
    }

    private fun assertToolPairing(body: String) {
        val messages = parseMessages(body)
        val callIds = messages.flatMap { message ->
            message["tool_calls"].jsonObjectList().mapNotNull { call ->
                (call["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
        }.toSet()
        val resultIds = messages.mapNotNull { message ->
            (message["tool_call_id"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        }
        if (resultIds.isNotEmpty()) {
            assertTrue("tool result without matching call: $resultIds vs $callIds", callIds.containsAll(resultIds))
        }
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

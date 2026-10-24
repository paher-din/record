package com.paherdin.record2.ai

import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException

class DeepSeekProtocolFailureTest {
    private fun newClient(fake: ScriptedHttpClient) =
        DeepSeekLLMClient(settings = DeepSeekClientSettings(), httpClient = fake)

    private fun executor(client: DeepSeekLLMClient) =
        MultiLLMPromptExecutor(LLMProvider.DeepSeek to client)

    private fun request() = prompt("d0-failure", DeepSeekProtocol.params(thinking = false)) {
        system("Synthetic failure probe.")
        user("Synthetic failure probe input.")
    }

    private fun expectClientFailure(block: () -> Unit): LLMClientException {
        try {
            block()
        } catch (failure: LLMClientException) {
            return failure
        }
        fail("Expected LLMClientException")
        error("unreachable")
    }

    private fun expectControlledFailure(block: () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        fail("Expected a controlled failure")
        error("unreachable")
    }

    @Test
    fun emptyChoicesFailsClosed() {
        val fake = ScriptedHttpClient { _, _ -> emptyChoicesResponse() }
        newClient(fake).use { client ->
            val failure = expectControlledFailure { runBlocking { client.execute(request(), DeepSeekProtocol.Flash) } }
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure.message.orEmpty().contains("Empty choices"))
            assertEquals(1, fake.requests.size)
            evidence.add(
                buildJsonObject {
                    put("test", "emptyChoicesFailsClosed")
                    put("requestCount", fake.requests.size)
                    put("failure", failure.javaClass.simpleName)
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun malformedJsonFailsClosed() {
        val fake = ScriptedHttpClient { _, _ -> "{not-json" }
        newClient(fake).use { client ->
            expectClientFailure { runBlocking { client.execute(request(), DeepSeekProtocol.Flash) } }
            assertEquals(1, fake.requests.size)
            evidence.add(
                buildJsonObject {
                    put("test", "malformedJsonFailsClosed")
                    put("requestCount", fake.requests.size)
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun http429SurfacesWithoutRetry() {
        val fake = ScriptedHttpClient { _, _ ->
            throw KoogHttpClientException(
                clientName = "record2-d0-scripted",
                statusCode = 429,
                errorBody = "synthetic rate limit",
            )
        }
        newClient(fake).use { client ->
            val failure = expectClientFailure { runBlocking { client.execute(request(), DeepSeekProtocol.Flash) } }
            val cause = failure.cause
            assertTrue(cause is KoogHttpClientException)
            assertEquals(429, (cause as KoogHttpClientException).statusCode)
            assertEquals(1, fake.requests.size)
            evidence.add(
                buildJsonObject {
                    put("test", "http429SurfacesWithoutRetry")
                    put("requestCount", fake.requests.size)
                    put("status", 429)
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun activeTokenBudgetGuardStopsOversizedResponse() {
        val fake = ScriptedHttpClient { _, _ ->
            chatResponse(text = "synthetic oversized response", inputTokens = 64_001, outputTokens = 1)
        }
        val budget = D0RunBudget()
        val requestBody = """{"model":"deepseek-flash","messages":[]}"""
        RecordingHttpClient(fake, budget).use { guarded ->
            val failure = runCatching {
                runBlocking {
                    guarded.post(
                        path = "chat/completions",
                        requestBody = requestBody,
                        requestBodyType = String::class,
                        responseType = String::class,
                        parameters = emptyMap(),
                        headers = emptyMap(),
                    )
                }
            }.exceptionOrNull()
            assertTrue("active budget guard must stop the response", failure is BudgetExceededException)
            assertTrue(budget.exceeded)
            assertTrue(budget.reasons.any { it.startsWith("tokens") })
            assertEquals(1, fake.requests.size)
            evidence.add(
                buildJsonObject {
                    put("test", "activeTokenBudgetGuardStopsOversizedResponse")
                    put("requestCount", fake.requests.size)
                    put("budgetOutcome", "BudgetExceeded")
                    put("budgetReasons", budget.reasons.joinToString("; "))
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun http503SurfacesWithoutRetry() {
        val fake = ScriptedHttpClient { _, _ ->
            throw KoogHttpClientException(
                clientName = "record2-d0-scripted",
                statusCode = 503,
                errorBody = "synthetic service unavailable",
            )
        }
        newClient(fake).use { client ->
            val failure = expectClientFailure { runBlocking { client.execute(request(), DeepSeekProtocol.Flash) } }
            val cause = failure.cause
            assertTrue(cause is KoogHttpClientException)
            assertEquals(503, (cause as KoogHttpClientException).statusCode)
            assertEquals(1, fake.requests.size)
            evidence.add(
                buildJsonObject {
                    put("test", "http503SurfacesWithoutRetry")
                    put("requestCount", fake.requests.size)
                    put("status", 503)
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun timeoutSurfacesWithoutRetry() {
        val fake = ScriptedHttpClient { _, _ -> throw SocketTimeoutException("synthetic timeout") }
        newClient(fake).use { client ->
            val failure = expectClientFailure { runBlocking { client.execute(request(), DeepSeekProtocol.Flash) } }
            assertTrue(failure.cause is SocketTimeoutException)
            assertEquals(1, fake.requests.size)
            evidence.add(
                buildJsonObject {
                    put("test", "timeoutSurfacesWithoutRetry")
                    put("requestCount", fake.requests.size)
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun cancelStopsInflightRequest() {
        val fake = ScriptedHttpClient { _, _ ->
            delay(30_000)
            chatResponse(text = "late result")
        }
        newClient(fake).use { client ->
            try {
                runBlocking {
                    withTimeout(300) {
                        client.execute(request(), DeepSeekProtocol.Flash)
                    }
                }
                fail("Expected cancellation")
            } catch (_: TimeoutCancellationException) {
                // expected
            }
            assertEquals(1, fake.requests.size)
            evidence.add(
                buildJsonObject {
                    put("test", "cancelStopsInflightRequest")
                    put("requestCount", fake.requests.size)
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun budgetExhaustionStopsToolLoop() {
        val fake = ScriptedHttpClient { _, _ ->
            chatResponse(
                toolCalls = listOf(FixtureToolCall("call-loop", "record2_echo", """{"word":"alpha"}""")),
            )
        }
        val echo = EchoTool()
        newClient(fake).use { client ->
            val agent = DeepSeekProtocol.agent(
                executor = executor(client),
                model = DeepSeekProtocol.Flash,
                toolRegistry = toolRegistry(echo),
                thinking = false,
                maxIterations = 4,
            )
            val failure = runCatching { runBlocking { agent.run("Synthetic loop probe.") } }.exceptionOrNull()
            assertTrue(
                "expected max-iterations termination, got $failure",
                failure is AIAgentMaxNumberOfIterationsReachedException,
            )
            val terminal = failure as AIAgentMaxNumberOfIterationsReachedException
            assertTrue(terminal.message.orEmpty().contains("steps"))
            assertEquals(1, echo.calls.size)
            assertEquals(2, fake.requests.size)
            evidence.add(
                buildJsonObject {
                    put("test", "budgetExhaustionStopsToolLoop")
                    put("requestCount", fake.requests.size)
                    put("toolCalls", echo.calls.size)
                    put("failure", terminal.javaClass.simpleName)
                    put("terminal", "AIAgentMaxNumberOfIterationsReachedException")
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun unknownToolIsNotExecuted() {
        val fake = ScriptedHttpClient { _, _ ->
            chatResponse(
                toolCalls = listOf(FixtureToolCall("call-unknown", "unknown_tool", """{"word":"nope"}""")),
            )
        }
        val echo = EchoTool()
        newClient(fake).use { client ->
            val agent = DeepSeekProtocol.agent(
                executor = executor(client),
                model = DeepSeekProtocol.Flash,
                toolRegistry = toolRegistry(echo),
                thinking = false,
                maxIterations = 2,
            )
            val failure = runCatching { runBlocking { agent.run("Synthetic unknown tool probe.") } }.exceptionOrNull()
            assertTrue(
                "expected max-iterations termination, got $failure",
                failure is AIAgentMaxNumberOfIterationsReachedException,
            )
            val terminal = failure as AIAgentMaxNumberOfIterationsReachedException
            assertTrue(echo.calls.isEmpty())
            assertEquals(1, fake.requests.size)
            evidence.add(
                buildJsonObject {
                    put("test", "unknownToolIsNotExecuted")
                    put("requestCount", fake.requests.size)
                    put("failure", terminal.javaClass.simpleName)
                    put("terminal", "AIAgentMaxNumberOfIterationsReachedException")
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun malformedToolArgumentsAreNotExecuted() {
        val fake = ScriptedHttpClient { _, _ ->
            chatResponse(
                toolCalls = listOf(FixtureToolCall("call-malformed", "record2_echo", "{not-json")),
            )
        }
        val echo = EchoTool()
        newClient(fake).use { client ->
            val agent = DeepSeekProtocol.agent(
                executor = executor(client),
                model = DeepSeekProtocol.Flash,
                toolRegistry = toolRegistry(echo),
                thinking = false,
                maxIterations = 2,
            )
            val failure = runCatching { runBlocking { agent.run("Synthetic malformed args probe.") } }.exceptionOrNull()
            assertTrue(
                "expected JsonDecodingException termination, got $failure",
                failure != null && failure.javaClass.simpleName == "JsonDecodingException",
            )
            val terminal = requireNotNull(failure)
            assertTrue(echo.calls.isEmpty())
            assertEquals(1, fake.requests.size)
            evidence.add(
                buildJsonObject {
                    put("test", "malformedToolArgumentsAreNotExecuted")
                    put("requestCount", fake.requests.size)
                    put("failure", terminal.javaClass.simpleName)
                    put("terminal", "JsonDecodingException")
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun toolLevelNoProgressErrorBecomesToolResultAndLoopContinues() {
        val fake = ScriptedHttpClient { _, _ ->
            chatResponse(
                toolCalls = listOf(FixtureToolCall("call-repeat", "record2_echo", """{"word":"repeat"}""")),
            )
        }
        val echo = NoProgressEchoTool(maxIdenticalCalls = 3)
        newClient(fake).use { client ->
            val agent = DeepSeekProtocol.agent(
                executor = executor(client),
                model = DeepSeekProtocol.Flash,
                toolRegistry = toolRegistry(echo),
                thinking = false,
                maxIterations = 12,
            )
            val failure = runCatching { runBlocking { agent.run("Synthetic no-progress probe.") } }.exceptionOrNull()
            assertTrue(
                "expected max-iterations termination, got $failure",
                failure is AIAgentMaxNumberOfIterationsReachedException,
            )
            val terminal = failure as AIAgentMaxNumberOfIterationsReachedException
            assertTrue(echo.calls.size >= 4)
            assertTrue(echo.calls.size <= 7)
            assertTrue(fake.requests.size <= 7)
            evidence.add(
                buildJsonObject {
                    put("test", "toolLevelNoProgressErrorBecomesToolResultAndLoopContinues")
                    put("requestCount", fake.requests.size)
                    put("toolCalls", echo.calls.size)
                    put("threshold", 3)
                    put("failure", terminal.javaClass.simpleName)
                    put("terminal", "AIAgentMaxNumberOfIterationsReachedException")
                    put("finding", "tool exception is converted to a tool result; loop continues")
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun noProgressWatchdogCancelsRun() {
        val fake = ScriptedHttpClient { _, _ ->
            chatResponse(
                toolCalls = listOf(FixtureToolCall("call-watchdog", "record2_echo", """{"word":"repeat"}""")),
            )
        }
        val echo = SlowEchoTool(delayMillis = 100)
        newClient(fake).use { client ->
            val agent = DeepSeekProtocol.agent(
                executor = executor(client),
                model = DeepSeekProtocol.Flash,
                toolRegistry = toolRegistry(echo),
                thinking = false,
                maxIterations = 12,
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var cancelled = false
            var requestsAtCancel = -1
            val run = scope.async { agent.run("Synthetic no-progress watchdog probe.") }
            val watchdog = scope.launch {
                while (isActive) {
                    if (echo.calls.count { it == "repeat" } >= D0TestLimits.NO_PROGRESS_CALLS) {
                        cancelled = true
                        requestsAtCancel = fake.requests.size
                        run.cancel(CancellationException("synthetic no-progress watchdog"))
                        break
                    }
                    delay(10)
                }
            }
            val outcome = runCatching { runBlocking { run.await() } }
            val failure = outcome.exceptionOrNull()
            assertTrue("watchdog must cancel the run, got $failure", failure is CancellationException)
            watchdog.cancel()
            runBlocking { delay(250) }
            assertEquals(
                "no request may start after watchdog cancellation",
                requestsAtCancel,
                fake.requests.size,
            )
            scope.cancel()
            assertTrue(cancelled)
            assertTrue(echo.calls.size in D0TestLimits.NO_PROGRESS_CALLS..(D0TestLimits.NO_PROGRESS_CALLS + 1))
            assertTrue(fake.requests.size in D0TestLimits.NO_PROGRESS_CALLS..(D0TestLimits.NO_PROGRESS_CALLS + 1))
            evidence.add(
                buildJsonObject {
                    put("test", "noProgressWatchdogCancelsRun")
                    put("requestCount", fake.requests.size)
                    put("toolCalls", echo.calls.size)
                    put("threshold", D0TestLimits.NO_PROGRESS_CALLS)
                    put("terminal", failure?.javaClass?.simpleName ?: "none")
                    put("requestsAtCancel", requestsAtCancel)
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun toolCallReasoningContentIsReplayed() = runBlocking {
        val firstResponse = chatResponse(
            reasoning = "SYNTH-REASONING-731",
            toolCalls = listOf(
                FixtureToolCall("call-reasoning", "record2_echo", """{"word":"orange"}"""),
            ),
        )
        val secondResponse = chatResponse(text = "ECHO:orange")
        val fake = ScriptedHttpClient { index, _ -> if (index == 1) firstResponse else secondResponse }
        newClient(fake).use { client ->
            val echo = EchoTool()
            val params = DeepSeekProtocol.params(thinking = true)
                .copy(toolChoice = ai.koog.prompt.params.LLMParams.ToolChoice.Auto)
            val base = prompt("d0-reasoning-replay", params) {
                system("Use the record2_echo tool.")
                user("Synthetic probe: call record2_echo with word orange.")
            }
            val first = client.execute(base, DeepSeekProtocol.Flash, toolDescriptors(echo))
            val call = first.toolCalls().single()
            assertTrue(first.reasoningText().contains("SYNTH-REASONING-731"))
            val followUp = base.withMessages { messages ->
                messages + first + ai.koog.prompt.message.Message.User(
                    ai.koog.prompt.message.MessagePart.Tool.Result(
                        id = call.id,
                        tool = call.tool,
                        output = "ECHO:orange",
                    ),
                    ai.koog.prompt.message.RequestMetaInfo.create(ai.koog.utils.time.KoogClock.System),
                )
            }
            val second = client.execute(followUp, DeepSeekProtocol.Flash, toolDescriptors(echo))
            assertEquals("ECHO:orange", second.textContent())
            val secondRequest = fake.requests[1]
            assertTrue(secondRequest.contains("reasoning_content"))
            assertTrue(secondRequest.contains("SYNTH-REASONING-731"))
            assertTrue(secondRequest.contains(call.id.orEmpty()))
            evidence.add(
                buildJsonObject {
                    put("test", "toolCallReasoningContentIsReplayed")
                    put("requestCount", fake.requests.size)
                    put("reasoningReplayed", true)
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun missingUsageStillReturnsText() {
        val response = """
            {"id":"d0-no-usage","object":"chat.completion","created":0,"model":"deepseek-flash",
            "system_fingerprint":"d0","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},
            "finish_reason":"stop"}]}
        """.trimIndent()
        val fake = ScriptedHttpClient { _, _ -> response }
        newClient(fake).use { client ->
            val result = runBlocking { client.execute(request(), DeepSeekProtocol.Flash) }
            assertEquals("OK", result.textContent())
            assertTrue(result.metaInfo.totalTokensCount == null)
            evidence.add(
                buildJsonObject {
                    put("test", "missingUsageStillReturnsText")
                    put("requestCount", fake.requests.size)
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun maxStepCapExhaustionIsBudgetInconclusiveNotProtocolFailure() {
        val fake = ScriptedHttpClient { _, _ ->
            chatResponse(toolCalls = listOf(FixtureToolCall("call-cap", "record2_echo", """{"word":"cap"}""")))
        }
        val echo = EchoTool()
        newClient(fake).use { client ->
            val agent = DeepSeekProtocol.agent(
                executor = executor(client),
                model = DeepSeekProtocol.Flash,
                toolRegistry = toolRegistry(echo),
                thinking = false,
                maxIterations = D0TestLimits.MAX_AGENT_STEPS,
            )
            val failure = runCatching { runBlocking { agent.run("Synthetic D0 step-cap probe.") } }.exceptionOrNull()
            assertTrue(
                "expected step-cap termination, got $failure",
                failure is AIAgentMaxNumberOfIterationsReachedException,
            )
            val reason = runLimitFailureReason(failure)
            assertTrue("step-cap exhaustion must classify as a run limit, not a protocol failure", reason != null)
            assertTrue(reason.orEmpty().contains("max iterations"))
            assertTrue(fake.requests.size >= 2)
            evidence.add(
                buildJsonObject {
                    put("test", "maxStepCapExhaustionIsBudgetInconclusiveNotProtocolFailure")
                    put("configuredSteps", D0TestLimits.MAX_AGENT_STEPS)
                    put("requestCount", fake.requests.size)
                    put("failure", failure!!.javaClass.simpleName)
                    put("classification", "INCONCLUSIVE/BudgetExceeded")
                    put("result", "PASS")
                },
            )
        }
    }

    @Test
    fun missingUsageMarksRunBudgetUnmetered() {
        val budget = D0RunBudget()
        budget.recordFrom("""{"model":"deepseek-flash","messages":[]}""", noUsageResponse())
        assertEquals(1, budget.unmeteredResponses)
        assertEquals(0, budget.inputTokens)
        assertEquals(0, budget.outputTokens)
        assertFalse(budget.exceeded)
        evidence.add(
            buildJsonObject {
                put("test", "missingUsageMarksRunBudgetUnmetered")
                put("unmeteredResponses", budget.unmeteredResponses)
                put("budgetOutcome", "UNMETERED/INCONCLUSIVE")
                put("result", "PASS")
            },
        )
    }

    @Test
    fun batchBudgetGuardReservesBeforeRequests() {
        val guard = D0BatchBudgetGuard(capUsd = 0.20)
        val body = """{"model":"deepseek-flash","messages":[]}"""
        guard.reserveForRequest(body)
        guard.record(body, chatResponse(text = "ok"))
        guard.reserveForRequest(body)
        guard.record(body, noUsageResponse())
        val blocked = runCatching { guard.reserveForRequest(body) }.exceptionOrNull()
        assertTrue("guard must block the next request before it is sent", blocked is BudgetExceededException)
        assertEquals(1, guard.unmeteredResponses)
        assertTrue(guard.spentUsd > 0.10)
        evidence.add(
            buildJsonObject {
                put("test", "batchBudgetGuardReservesBeforeRequests")
                put("capUsd", 0.20)
                put("meteredSpentUsd", guard.spentUsd)
                put("unmeteredResponses", guard.unmeteredResponses)
                put("blockedBeforeRequest", true)
                put("result", "PASS")
            },
        )
    }

    @Test
    fun concurrentReservesCannotOversubscribeBatchCap() = runBlocking {
        val guard = D0BatchBudgetGuard(capUsd = 0.20)
        val body = """{"model":"deepseek-flash","messages":[]}"""
        // Every request reserves before any of them settles: this is the window in which a
        // check-only implementation would let concurrent requests oversubscribe the cap.
        val allReserved = java.util.concurrent.CountDownLatch(10)
        val grants = (1..10).map {
            async(Dispatchers.IO) {
                val granted = runCatching { guard.reserveForRequest(body) }.isSuccess
                allReserved.countDown()
                allReserved.await()
                if (granted) guard.record(body, chatResponse(text = "ok"))
                granted
            }
        }.awaitAll().count { it }
        assertEquals("only reserves covered by the cap may be granted", 2, grants)
        assertTrue("settled spend must stay within the cap", guard.spentUsd <= 0.20)
        assertEquals("all holds must be settled", guard.spentUsd, guard.reservedUsd, 1e-9)
        evidence.add(
            buildJsonObject {
                put("test", "concurrentReservesCannotOversubscribeBatchCap")
                put("capUsd", 0.20)
                put("concurrentRequests", 10)
                put("grantedReserves", grants)
                put("settledSpentUsd", guard.spentUsd)
                put("result", "PASS")
            },
        )
    }

    @Test
    fun failedRequestSettlesFullReserve() {
        val guard = D0BatchBudgetGuard(capUsd = 0.20)
        val body = """{"model":"deepseek-flash","messages":[]}"""
        val fake = ScriptedHttpClient { _, _ ->
            throw KoogHttpClientException(
                clientName = "record2-d0-scripted",
                statusCode = 500,
                errorBody = "synthetic request failure",
            )
        }
        RecordingHttpClient(fake, budget = null, batchBudget = guard).use { guarded ->
            repeat(2) {
                val failure = runCatching {
                    runBlocking {
                        guarded.post(
                            path = "chat/completions",
                            requestBody = body,
                            requestBodyType = String::class,
                            responseType = String::class,
                            parameters = emptyMap(),
                            headers = emptyMap(),
                        )
                    }
                }.exceptionOrNull()
                assertTrue(failure is KoogHttpClientException)
            }
            assertEquals("failed requests must keep their full reserve charged", 0.20, guard.spentUsd, 1e-9)
            assertEquals(0.20, guard.reservedUsd, 1e-9)
            val blocked = runCatching { guard.reserveForRequest(body) }.exceptionOrNull()
            assertTrue("cap must be exhausted after two failed full-reserve settlements", blocked is BudgetExceededException)
            assertEquals(2, fake.requests.size)
            evidence.add(
                buildJsonObject {
                    put("test", "failedRequestSettlesFullReserve")
                    put("capUsd", 0.20)
                    put("failedRequests", fake.requests.size)
                    put("chargedUsd", guard.spentUsd)
                    put("blockedAfterFailures", true)
                    put("result", "PASS")
                },
            )
        }
    }

    companion object {
        private val evidence = D0Evidence("failures.json")

        @JvmStatic
        @AfterClass
        fun writeEvidence() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            evidence.write(context)
        }
    }
}

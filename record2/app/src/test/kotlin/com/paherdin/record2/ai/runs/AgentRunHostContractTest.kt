package com.paherdin.record2.ai.runs

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.typeToken
import com.paherdin.record2.ai.security.AiDenial
import com.paherdin.record2.ai.security.AiSecurityService
import com.paherdin.record2.sessions.AgentHistoryError
import com.paherdin.record2.sessions.AgentHistoryService
import com.paherdin.record2.sessions.RunState
import com.paherdin.record2.sessions.RunTerminal
import com.paherdin.record2.sessions.SafeRunErrorCode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Host JVM contract for the E1 run seam: the real Koog agent and ChatMemory feature over a
 * scripted transport, with in-memory fakes for history/security. Pins the context-rebuild
 * protocol (Spec 5.2.2/5.2.4, SES-02/10/11/12). Real Room transactions and disk facts are
 * covered by SessionRunContractTest on a device.
 */
class AgentRunHostContractTest {
    private fun coordinator(
        history: AgentHistoryService,
        security: AiSecurityService,
        http: ScriptedHttp,
        window: Int = AgentRunDefaults.HISTORY_WINDOW,
        tools: ToolRegistry = ToolRegistry.EMPTY,
    ): AgentRunCoordinator = AgentRunCoordinator(
        security = security,
        history = history,
        processOwnerId = "owner-host",
        historyWindow = window,
        toolRegistry = tools,
        httpClientFactory = object : KoogHttpClient.Factory {
            override fun create(
                clientName: String, baseUrl: String, headers: Map<String, String>,
                queryParameters: Map<String, String>, requestTimeoutMillis: Long,
                connectTimeoutMillis: Long, socketTimeoutMillis: Long,
                json: kotlinx.serialization.json.Json,
            ): KoogHttpClient = http
        },
    )

    @Test
    fun currentInputJoinsOnceAndCompletedHistoryReplaysInOrder() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val script = listOf(chatResponse("R1", reasoning = "SECRET-REASONING"), chatResponse("R2"))
        val http = ScriptedHttp { index, _ -> script[index - 1] }
        val coord = coordinator(history, FakeAiSecurity(), http)
        val first = coord.run(session.id, "hello")
        val second = coord.run(session.id, "again")

        assertEquals("R1", first.answer)
        assertEquals("R2", second.answer)
        assertEquals(2, http.requests.size)
        val firstRun = requestMessages(http.requests[0])
        assertEquals(listOf("system", "user"), firstRun.map { it.role() })
        assertEquals(1, firstRun.count { it.textContent() == "hello" })
        val secondRun = requestMessages(http.requests[1])
        assertEquals(listOf("system", "user", "assistant", "user"), secondRun.map { it.role() })
        assertEquals(1, secondRun.count { it.textContent() == "hello" })
        assertEquals(1, secondRun.count { it.textContent() == "again" })
        assertEquals("R1", secondRun.first { it.role() == "assistant" }.textContent())
        // Hidden reasoning from the provider stays out of the authoritative outcome.
        val outcome = history.completedRuns.first().second
        assertEquals("R1", outcome.body)
        assertFalse(outcome.body.contains("SECRET-REASONING"))
        // load() is consulted once per run against the boundary fixed at start.
        assertEquals(2, history.completedHistoryCalls)
    }

    @Test
    fun failedRunInputStaysReadableForUiButNeverReentersModelContext() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val http = ScriptedHttp { index, _ ->
            if (index == 1) throw IOException("synthetic provider outage")
            chatResponse("R2")
        }
        val coord = coordinator(history, FakeAiSecurity(), http)
        try {
            coord.run(session.id, "hello")
            fail("run should fail")
        } catch (error: AgentRunException) {
            assertEquals(AgentRunError.ProviderFailure, error.code)
        }
        assertEquals(1, history.failedRuns.size)
        assertEquals(Triple("run-1", RunTerminal.FAILED, SafeRunErrorCode.PROVIDER_FAILURE), history.failedRuns.single())
        assertEquals(0, history.completedRuns.size)
        // The failed input remains user-visible history...
        assertEquals(listOf("hello" to RunState.FAILED), history.inputStates())
        // ...but is not replayed into the next run's model context.
        coord.run(session.id, "again")
        val secondRun = requestMessages(http.requests[1])
        assertEquals(listOf("system", "user"), secondRun.map { it.role() })
        assertEquals("again", secondRun.last { it.role() == "user" }.textContent())
        assertFalse(secondRun.any { it.textContent() == "hello" })
    }

    @Test
    fun incompleteToolSequenceAndUncommittedReplyNeverEnterTheNextRun() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val tool = EchoTool()
        history.completeRunError = AgentHistoryError.OutcomeUnknown
        val http = ScriptedHttp { index, _ ->
            when (index) {
                1 -> chatResponse(toolCall = "call-1" to """{"word":"X"}""")
                2 -> chatResponse("DONE-1")
                else -> chatResponse("SECOND")
            }
        }
        val coord = coordinator(history, FakeAiSecurity(), http, tools = ToolRegistry { tool(tool) })
        try {
            coord.run(session.id, "hello")
            fail("run should fail before its final reply commits")
        } catch (error: AgentRunException) {
            assertEquals(AgentRunError.Internal, error.code)
        }
        // The model-side store happened (Koog offered the full window including the tool
        // protocol messages), but the authority never recorded a final reply.
        assertEquals(1, history.failedRuns.size)
        assertEquals(0, history.completedRuns.size)
        // The tool protocol existed only inside the failed run's own requests.
        val toolRound = requestMessages(http.requests[1])
        assertTrue(toolRound.any { it.role() == "tool" })
        assertTrue(http.requests[1].contains("call-1"))
        history.completeRunError = null
        coord.run(session.id, "again")
        val nextRun = requestMessages(http.requests[2])
        assertEquals(listOf("system", "user"), nextRun.map { it.role() })
        assertFalse(http.requests[2].contains("call-1"))
        assertFalse(http.requests[2].contains("DONE-1"))
        assertFalse(http.requests[2].contains("ECHO:"))
    }

    @Test
    fun windowTrimmingBoundsThePromptWhileTheHistorySourceStaysComplete() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        history.seedCompleted((0 until 6).map { index -> "old-$index" to "OLD-REPLY-$index" })
        val http = ScriptedHttp { _, _ -> chatResponse("R") }
        // Window of 7 = system + the six most recent completed entries + the current input.
        val coord = coordinator(history, FakeAiSecurity(), http, window = 7)
        coord.run(session.id, "new-input")
        assertEquals(1, http.requests.size)
        val messages = requestMessages(http.requests[0])
        assertEquals(8, messages.size)
        assertEquals(
            listOf("system") + List(3) { listOf("user", "assistant") }.flatten() + listOf("user"),
            messages.map { it.role() },
        )
        assertFalse(http.requests[0].contains("old-0"))
        assertFalse(http.requests[0].contains("old-2"))
        assertTrue(http.requests[0].contains("old-3"))
        assertTrue(http.requests[0].contains("old-5"))
        assertTrue(http.requests[0].contains("new-input"))
        // The trim is the provider's view only; the source kept all 12 seeded entries and the
        // newly completed pair.
        assertEquals(14, history.entriesSnapshot().size)
    }

    @Test
    fun authorizationDeniedStopsBeforeAnySideEffect() = runBlocking {
        val history = FakeHistoryService()
        val http = ScriptedHttp { _, _ -> chatResponse("R") }
        val coord = coordinator(history, FakeAiSecurity(denial = AiDenial.AI_DISABLED), http)
        val session = history.newSession()
        try {
            coord.run(session.id, "hello")
            fail("run should fail without a permit")
        } catch (error: AgentRunException) {
            assertEquals(AgentRunError.AuthorizationRequired, error.code)
        }
        assertTrue(history.startedRuns.isEmpty())
        assertEquals(0, http.requests.size)
    }

    @Test
    fun sessionBusyStopsBeforeAnyNetworkCall() = runBlocking {
        val history = FakeHistoryService()
        history.startRunError = AgentHistoryError.SessionBusy
        val http = ScriptedHttp { _, _ -> chatResponse("R") }
        val coord = coordinator(history, FakeAiSecurity(), http)
        val session = history.newSession()
        try {
            coord.run(session.id, "hello")
            fail("run should fail on a busy session")
        } catch (error: AgentRunException) {
            assertEquals(AgentRunError.SessionBusy, error.code)
        }
        assertEquals(0, http.requests.size)
        assertTrue(history.failedRuns.isEmpty())
    }

    @Test
    fun loadFailureFailsTheRunWithoutAFinalReply() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        history.completedHistoryError = AgentHistoryError.RecoverableIO
        val http = ScriptedHttp { _, _ -> chatResponse("R") }
        val coord = coordinator(history, FakeAiSecurity(), http)
        try {
            coord.run(session.id, "hello")
            fail("run should fail when history cannot be rebuilt")
        } catch (error: AgentRunException) {
            assertEquals(AgentRunError.RecoverableIO, error.code)
        }
        assertEquals(1, history.failedRuns.size)
        assertEquals(RunTerminal.FAILED, history.failedRuns.single().second)
        assertEquals(SafeRunErrorCode.INTERNAL, history.failedRuns.single().third)
        assertEquals(0, history.completedRuns.size)
    }

    @Test
    fun blankInputIsRejectedBeforePermitOrSideEffects() = runBlocking {
        val history = FakeHistoryService()
        val security = FakeAiSecurity()
        val http = ScriptedHttp { _, _ -> chatResponse("R") }
        val coord = coordinator(history, security, http)
        val session = history.newSession()
        for (input in listOf("", "   ", "\n\t")) {
            try {
                coord.run(session.id, input)
                fail("blank input must be rejected")
            } catch (error: AgentRunException) {
                assertEquals(AgentRunError.Validation, error.code)
            }
        }
        assertTrue(security.permits.isEmpty())
        assertTrue(history.startedRuns.isEmpty())
        assertEquals(0, http.requests.size)
    }

    @Test
    fun cancellationDuringRunMarksTheRunCancelledAndAddsNoReply() = runBlocking {
        val history = FakeHistoryService()
        val session = history.newSession()
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedHttp { _, _ ->
            gate.await()
            chatResponse("R")
        }
        val coord = coordinator(history, FakeAiSecurity(), http)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val runJob = scope.launch { coord.run(session.id, "hello") }
        withTimeout(10_000) {
            while (http.requests.size < 1) delay(10)
        }
        runJob.cancelAndJoin()
        assertTrue(runJob.isCancelled)
        assertEquals(1, history.failedRuns.size)
        assertEquals(RunTerminal.CANCELLED, history.failedRuns.single().second)
        assertEquals(0, history.completedRuns.size)
        scope.cancel()
    }

    @Test
    fun historyProviderMapsCompletedEntriesAndNeverWritesBack() = runBlocking {
        val history = FakeHistoryService()
        history.seedCompleted(listOf("u1" to "a1"))
        val provider = RoomChatHistoryProvider(
            history = history,
            sessionId = "session-1",
            boundarySequence = 2,
            systemPrompt = "SYS-PROMPT-FIXTURE",
            window = 40,
        )
        val loaded = provider.load("koog-run-id")
        assertEquals(3, loaded.size)
        assertTrue(loaded[0] is Message.System)
        assertTrue(loaded[0].textContentOfMessage().contains("SYS-PROMPT-FIXTURE"))
        assertEquals("u1", loaded[1].textContentOfMessage())
        assertEquals("a1", loaded[2].textContentOfMessage())
        // load() is bounded by the window while the source stays complete.
        val trimmed = RoomChatHistoryProvider(history, "session-1", 2, "SYS", window = 2).load("koog")
        assertEquals(2, trimmed.size)
        assertTrue(trimmed[0] is Message.System)
        assertEquals("a1", trimmed[1].textContentOfMessage())
        provider.store("koog-run-id", emptyList())
        assertEquals(0, history.completedRuns.size)
        assertEquals(0, history.startedRuns.size)
    }

    private fun Message.textContentOfMessage(): String =
        parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }

    private companion object {
        @Serializable
        data class EchoArgs(val word: String)

        class EchoTool : SimpleTool<EchoArgs>(
            argsType = typeToken<EchoArgs>(),
            name = "host_echo",
            description = "Echo a synthetic word exactly once.",
        ) {
            val calls = CopyOnWriteArrayList<String>()
            override suspend fun execute(args: EchoArgs): String {
                calls += args.word
                return "ECHO:${args.word}"
            }
        }
    }
}

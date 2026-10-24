package com.paherdin.record2.ai.runs

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import com.paherdin.record2.ai.DeepSeekProtocol
import com.paherdin.record2.ai.security.AiSecurityError
import com.paherdin.record2.ai.security.AiSecurityException
import com.paherdin.record2.ai.security.AiSecurityService
import com.paherdin.record2.ai.security.AiSendPermit
import com.paherdin.record2.ai.security.ProviderReceiver
import com.paherdin.record2.ai.security.RecordProviderReceivers
import com.paherdin.record2.sessions.AgentHistoryService
import com.paherdin.record2.sessions.AgentSessionInfo
import com.paherdin.record2.sessions.AgentSessionMessage
import com.paherdin.record2.sessions.MessageResultKind
import com.paherdin.record2.sessions.ProcessOwnershipReceipt
import com.paherdin.record2.sessions.RunCompletionReceipt
import com.paherdin.record2.sessions.RunOutcome
import com.paherdin.record2.sessions.RunTerminal
import com.paherdin.record2.sessions.SafeRunErrorCode
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class AgentRunError {
    Validation,
    AuthorizationRequired,
    CredentialUnavailable,
    SessionBusy,
    RunInvalidated,
    ProviderFailure,
    BudgetExceeded,
    RecoverableIO,
    StorageFull,
    Internal,
}

internal class AgentRunException(val code: AgentRunError, cause: Throwable? = null) :
    Exception(code.name, cause)

internal data class RunResult(
    val runId: String,
    val sessionId: String,
    val answer: String,
    val completion: RunCompletionReceipt,
)

/**
 * Application-level default context bounds. [HISTORY_WINDOW] counts prompt messages including
 * the system message; the completed history is trimmed inside the history provider because
 * Koog's stock window preprocessor would trim the system message away (Koog 1.3.0 merge rule).
 * D0 test budgets (Plan §24.5) are an upper-bound reference; product defaults stay unfrozen
 * until D1/E2 evidence lands.
 */
internal object AgentRunDefaults {
    const val HISTORY_WINDOW = 40
    const val MAX_AGENT_ITERATIONS = 50
}

/**
 * Owns one user-facing agent run end to end (Spec 5.2): E0 permit → atomic start transaction →
 * Koog agent with the Room-backed history provider → final reply plus terminal state in one
 * Room transaction. Page switches and activity recreation do not cancel a run; only explicit
 * invalidation paths do, and they are owned by later tasks (E2/D1/F2 call sites).
 */
internal class AgentRunCoordinator(
    private val security: AiSecurityService,
    private val history: AgentHistoryService,
    private val receiver: ProviderReceiver = RecordProviderReceivers.deepSeek,
    private val processOwnerId: String,
    private val historyWindow: Int = AgentRunDefaults.HISTORY_WINDOW,
    private val maxAgentIterations: Int = AgentRunDefaults.MAX_AGENT_ITERATIONS,
    private val systemPrompt: String = DeepSeekProtocol.DEFAULT_SYSTEM_PROMPT,
    private val toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    private val httpClientFactory: KoogHttpClient.Factory? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Call once at process start, before any run/session entry (B0 §6 recovery gate). */
    suspend fun beginProcessOwnership(): ProcessOwnershipReceipt = history.beginProcessOwnership(processOwnerId)

    suspend fun createSession(title: String? = null): AgentSessionInfo = history.createSession(title)

    suspend fun sessionMessages(sessionId: String): List<AgentSessionMessage> = history.sessionMessages(sessionId)

    suspend fun run(sessionId: String, userText: String): RunResult = withContext(Dispatchers.IO) {
        if (userText.isBlank()) fail(AgentRunError.Validation)
        val permit = try {
            security.requirePermit(receiver)
        } catch (error: AiSecurityException) {
            throw AgentRunException(safeAuthorization(error), error)
        }
        val start = try {
            history.startRun(sessionId, userText, receiver.identity, permit.authEpoch, processOwnerId)
        } catch (error: Exception) {
            throw AgentRunException(safeStartError(error), error)
        }
        try {
            // The plaintext exists only inside the E0 callback: build the client there, clear the
            // array, then run the agent. The client keeps its own copy for the network calls the
            // same way D0's seam does; mid-run revocation is enforced by the completion
            // transaction's epoch revalidation, and network cancellation stays with E2.
            val client = security.useCredential(permit) { secret ->
                DeepSeekProtocol.client(
                    apiKey = String(secret),
                    httpClientFactory = httpClientFactory ?: DefaultHttpFactory,
                )
            }
            val executor = MultiLLMPromptExecutor(LLMProvider.DeepSeek to client)
            val reply = agent(start, executor).run(userText)
            if (reply.isBlank()) fail(AgentRunError.ProviderFailure)
            val completion = history.completeRun(
                runId = start.runId,
                receiverIdentity = receiver.identity,
                authEpoch = permit.authEpoch,
                processOwnerId = processOwnerId,
                outcome = RunOutcome(resultKind = MessageResultKind.Answer, body = reply),
            )
            RunResult(start.runId, sessionId, reply, completion)
        } catch (error: CancellationException) {
            markTerminal(start.runId, RunTerminal.CANCELLED, SafeRunErrorCode.CANCELLED)
            throw error
        } catch (error: Exception) {
            markTerminal(start.runId, RunTerminal.FAILED, safeErrorCode(error))
            throw AgentRunException(safeRunError(error), error)
        }
    }

    private fun agent(start: com.paherdin.record2.sessions.RunStartReceipt, executor: PromptExecutor): AIAgent<String, String> {
        val config = AIAgentConfig(
            prompt = prompt("record2-e1", params = DeepSeekProtocol.params(thinking = false)) {
                // D1 owns the real system prompt; the D0 seam default keeps the protocol unchanged.
                system(systemPrompt)
            },
            model = DeepSeekProtocol.Flash,
            maxAgentIterations = maxAgentIterations,
        )
        return AIAgent(
            promptExecutor = executor,
            agentConfig = config,
            toolRegistry = toolRegistry,
        ) {
            install(ChatMemory) {
                // The provider owns the system message and window: Koog's merge replaces the
                // whole prompt with non-empty loaded history (see RoomChatHistoryProvider).
                chatHistoryProvider = RoomChatHistoryProvider(
                    history = history,
                    sessionId = start.sessionId,
                    boundarySequence = start.boundarySequence,
                    systemPrompt = systemPrompt,
                    window = historyWindow,
                )
            }
        }
    }

    private suspend fun markTerminal(runId: String, state: RunTerminal, code: SafeRunErrorCode) {
        try {
            history.failRun(runId, state, code)
        } catch (ignored: Exception) {
            // Terminal marking is best-effort on the failure path; a lost mark leaves the run to
            // the ownership gate, never to a ghost final reply.
        }
    }

    private fun safeAuthorization(error: AiSecurityException): AgentRunError = when (error.code) {
        AiSecurityError.CredentialUnavailable -> AgentRunError.CredentialUnavailable
        else -> AgentRunError.AuthorizationRequired
    }

    private fun safeStartError(error: Exception): AgentRunError = safeRunError(error)

    private fun safeRunError(error: Exception): AgentRunError = when (error) {
        is AiSecurityException -> safeAuthorization(error)
        is com.paherdin.record2.sessions.AgentHistoryException ->
            when (error.code) {
                com.paherdin.record2.sessions.AgentHistoryError.SessionBusy -> AgentRunError.SessionBusy
                com.paherdin.record2.sessions.AgentHistoryError.AuthorizationRequired ->
                    AgentRunError.AuthorizationRequired
                com.paherdin.record2.sessions.AgentHistoryError.NotInitialized ->
                    AgentRunError.AuthorizationRequired
                com.paherdin.record2.sessions.AgentHistoryError.Validation -> AgentRunError.Validation
                com.paherdin.record2.sessions.AgentHistoryError.StorageFull -> AgentRunError.StorageFull
                com.paherdin.record2.sessions.AgentHistoryError.RecoverableIO -> AgentRunError.RecoverableIO
                else -> AgentRunError.Internal
            }
        is AgentRunException -> error.code
        is kotlinx.coroutines.TimeoutCancellationException -> AgentRunError.ProviderFailure
        is kotlinx.serialization.SerializationException -> AgentRunError.ProviderFailure
        is java.io.IOException -> AgentRunError.RecoverableIO
        else -> AgentRunError.ProviderFailure
    }

    private fun safeErrorCode(error: Exception): SafeRunErrorCode = when (safeRunError(error)) {
        AgentRunError.BudgetExceeded -> SafeRunErrorCode.BUDGET_EXCEEDED
        AgentRunError.AuthorizationRequired, AgentRunError.CredentialUnavailable -> SafeRunErrorCode.AUTHORIZATION
        AgentRunError.Internal, AgentRunError.RecoverableIO, AgentRunError.StorageFull ->
            SafeRunErrorCode.INTERNAL
        else -> SafeRunErrorCode.PROVIDER_FAILURE
    }

    private fun fail(code: AgentRunError): Nothing = throw AgentRunException(code)

    private companion object {
        // D0 finding: the Ktor factory has no service-loader entry on Android, so callers must
        // construct it explicitly; keep one shared instance for the production path.
        val DefaultHttpFactory: KoogHttpClient.Factory by lazy {
            ai.koog.http.client.ktor.KtorKoogHttpClient.Factory()
        }
    }
}

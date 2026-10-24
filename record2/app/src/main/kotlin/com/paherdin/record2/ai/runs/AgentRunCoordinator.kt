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
import com.paherdin.record2.ai.tools.GuardedHttpClientFactory
import com.paherdin.record2.ai.tools.RecordToolContext
import com.paherdin.record2.ai.tools.RecordToolDeps
import com.paherdin.record2.ai.tools.RecordToolException
import com.paherdin.record2.ai.tools.RecordToolFailure
import com.paherdin.record2.ai.tools.RecordToolRegistry
import com.paherdin.record2.records.FinanceError
import com.paherdin.record2.records.FinanceException
import com.paherdin.record2.sessions.AgentHistoryError
import com.paherdin.record2.sessions.AgentHistoryException
import com.paherdin.record2.sessions.AgentHistoryService
import com.paherdin.record2.sessions.AgentSessionInfo
import com.paherdin.record2.sessions.AgentSessionMessage
import com.paherdin.record2.sessions.MessageResultKind
import com.paherdin.record2.sessions.ProcessOwnershipReceipt
import com.paherdin.record2.sessions.RunCompletionReceipt
import com.paherdin.record2.sessions.RunOutcome
import com.paherdin.record2.sessions.RunState
import com.paherdin.record2.sessions.RunTerminal
import com.paherdin.record2.sessions.SafeRunErrorCode
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class AgentRunError {
    Validation,
    AuthorizationRequired,
    CredentialUnavailable,
    SessionBusy,
    RunInvalidated,
    OutcomeUnknown,
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
 * Application-level default context bounds. [HISTORY_WINDOW] bounds the whole prompt — the
 * system message plus complete USER/ASSISTANT pairs plus the current input — and the trim is
 * done inside the history provider, because Koog's stock window preprocessor would trim the
 * system message away and could split a pair (Koog 1.3.0 merge rule). D0 test budgets
 * (Plan §24.5) are an upper-bound reference; product defaults stay unfrozen until D1/E2
 * evidence lands.
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
    /** D1 record tools; when present the per-run registry and the real D1 system prompt take over. */
    private val recordTools: RecordToolDeps? = null,
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
        // Identities are generated before the start transaction: if the commit lands but the
        // receipt is lost (crash, cancellation in the commit window, lost post-commit receipt),
        // the exact orphan is still addressable by identity and can be terminally reconciled
        // instead of holding the session's single run slot forever.
        val identity = history.newRunIdentity()
        val start = try {
            history.startRun(
                sessionId, userText, receiver.identity, permit.authEpoch, processOwnerId, identity,
            )
        } catch (error: CancellationException) {
            // Preserve cancellation semantics; clean up any run this attempt may have committed.
            withContext(NonCancellable) {
                markTerminal(identity.runId, RunTerminal.CANCELLED, SafeRunErrorCode.CANCELLED)
            }
            throw error
        } catch (error: Exception) {
            if (error is AgentHistoryException && error.code == AgentHistoryError.OutcomeUnknown) {
                // Committed but the receipt was lost: reconcile the orphan by identity so the
                // session's slot frees deterministically instead of waiting for a future gate.
                withContext(NonCancellable) {
                    markTerminal(identity.runId, RunTerminal.CANCELLED, SafeRunErrorCode.INTERNAL)
                }
                throw AgentRunException(AgentRunError.OutcomeUnknown, error)
            }
            throw AgentRunException(safeStartError(error), error)
        }
        var reply: String? = null
        var outcome: RunOutcome? = null
        // D1: one tool context per run — entry guards, hard budgets and native handoff evidence.
        // The system prompt is rebuilt per run so the model sees the current local time and the
        // seeded catalog names; relative time is resolved by the model, tools only take absolutes.
        val toolContext = recordTools?.let { RecordToolContext(it, permit, start, clock) }
        val runSystemPrompt = if (recordTools != null) {
            RecordToolRegistry.systemPrompt(recordTools.finance.initialize(), clock, ZoneId.systemDefault())
        } else {
            systemPrompt
        }
        val runToolRegistry = toolContext?.let(RecordToolRegistry::forRun) ?: toolRegistry
        try {
            // The plaintext exists only inside the E0 callback: build the client there, clear the
            // array, then run the agent. The client keeps its own copy for the network calls the
            // same way D0's seam does; mid-run revocation is enforced at every request by the
            // guarded factory plus the completion transaction's epoch revalidation, and network
            // cancellation stays with E2.
            val client = security.useCredential(permit) { secret ->
                val baseFactory = httpClientFactory ?: DefaultHttpFactory
                DeepSeekProtocol.client(
                    apiKey = String(secret),
                    httpClientFactory = if (toolContext != null) {
                        GuardedHttpClientFactory(baseFactory, toolContext)
                    } else {
                        baseFactory
                    },
                )
            }
            val executor = MultiLLMPromptExecutor(LLMProvider.DeepSeek to client)
            reply = agent(start, executor, runSystemPrompt, runToolRegistry).run(userText)
            if (reply.isBlank()) fail(AgentRunError.ProviderFailure)
            // Classification is deterministic from tool state, never from the model's own claim:
            // a proposal this run wrote and that is still editable is ProposalReady; unresolved
            // prepare issues are ClarificationNeeded; tools themselves never commit anything.
            val completedOutcome = toolContext?.classifyOutcome(reply)
                ?: RunOutcome(resultKind = MessageResultKind.Answer, body = reply)
            outcome = completedOutcome
            val completion = history.completeRun(
                runId = start.runId,
                receiverIdentity = receiver.identity,
                authEpoch = permit.authEpoch,
                processOwnerId = processOwnerId,
                outcome = completedOutcome,
            )
            RunResult(start.runId, sessionId, reply, completion)
        } catch (error: CancellationException) {
            // The coroutine is already cancelled: a plain suspend call could be skipped
            // entirely, stranding the run as RUNNING with its slot held. Run the terminal
            // marking outside cancellation so the slot is freed deterministically here —
            // not only when some later ownership gate happens to run.
            withContext(NonCancellable) {
                markTerminal(start.runId, RunTerminal.CANCELLED, SafeRunErrorCode.CANCELLED)
            }
            throw error
        } catch (error: Exception) {
            if (error is AgentHistoryException && error.code == AgentHistoryError.OutcomeUnknown) {
                // The final reply may already be committed; never report failure while Room says
                // otherwise. Reconcile by run identity using the same full outcome semantics as
                // completeRun idempotence, not just the reply body.
                val expected = outcome
                val persisted = try {
                    history.persistedResult(start.runId)
                } catch (readError: Exception) {
                    if (readError is CancellationException) {
                        withContext(NonCancellable) {
                            markTerminal(start.runId, RunTerminal.CANCELLED, SafeRunErrorCode.CANCELLED)
                        }
                        throw readError
                    }
                    // The read failed, so the run may still be committed. Free the slot when
                    // possible, but preserve the original OutcomeUnknown instead of surfacing
                    // an unrelated storage exception.
                    withContext(NonCancellable) {
                        markTerminal(start.runId, RunTerminal.FAILED, SafeRunErrorCode.INTERNAL)
                    }
                    error.addSuppressed(readError)
                    throw AgentRunException(AgentRunError.OutcomeUnknown, error)
                }
                if (expected != null &&
                    persisted?.state == RunState.COMPLETED &&
                    persisted.outcome == expected
                ) {
                    return@withContext RunResult(
                        runId = start.runId,
                        sessionId = sessionId,
                        answer = expected.body,
                        completion = RunCompletionReceipt(
                            runId = start.runId,
                            assistantMessageId = persisted.assistantMessageId!!,
                            assistantMessageSequence = persisted.assistantMessageSequence!!,
                            finishedAt = persisted.finishedAt ?: clock.millis(),
                            alreadyCompleted = true,
                        ),
                    )
                }
                withContext(NonCancellable) {
                    markTerminal(start.runId, RunTerminal.FAILED, SafeRunErrorCode.INTERNAL)
                }
                throw AgentRunException(AgentRunError.OutcomeUnknown, error)
            }
            markTerminal(start.runId, RunTerminal.FAILED, safeErrorCode(error))
            throw AgentRunException(safeRunError(error), error)
        }
    }

    private fun agent(
        start: com.paherdin.record2.sessions.RunStartReceipt,
        executor: PromptExecutor,
        runSystemPrompt: String,
        runToolRegistry: ToolRegistry,
    ): AIAgent<String, String> {
        val config = AIAgentConfig(
            prompt = prompt("record2-agent", params = DeepSeekProtocol.params(thinking = false)) {
                system(runSystemPrompt)
            },
            model = DeepSeekProtocol.Flash,
            maxAgentIterations = maxAgentIterations,
        )
        return AIAgent(
            promptExecutor = executor,
            agentConfig = config,
            toolRegistry = runToolRegistry,
        ) {
            install(ChatMemory) {
                // The provider owns the system message and window: Koog's merge replaces the
                // whole prompt with non-empty loaded history (see RoomChatHistoryProvider).
                chatHistoryProvider = RoomChatHistoryProvider(
                    history = history,
                    sessionId = start.sessionId,
                    boundarySequence = start.boundarySequence,
                    systemPrompt = runSystemPrompt,
                    window = historyWindow,
                )
            }
        }
    }

    private suspend fun markTerminal(runId: String, state: RunTerminal, code: SafeRunErrorCode) {
        try {
            history.failRun(runId, state, code)
        } catch (error: CancellationException) {
            throw error
        } catch (ignored: Exception) {
            // Terminal marking is best-effort on the failure path; a lost mark leaves the run to
            // the ownership gate, never to a ghost final reply. Callers invoke this under
            // NonCancellable when their own coroutine is already cancelled.
        }
    }

    private fun safeAuthorization(error: AiSecurityException): AgentRunError = when (error.code) {
        AiSecurityError.CredentialUnavailable -> AgentRunError.CredentialUnavailable
        else -> AgentRunError.AuthorizationRequired
    }

    private fun safeStartError(error: Exception): AgentRunError = safeRunError(error)

    private fun safeRunError(error: Exception): AgentRunError {
        // The guarded HTTP client throws inside the LLM client, which may wrap the cause; the tool
        // failure must survive the wrapping so the run fails with its real code.
        val toolError = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<RecordToolException>().firstOrNull()
        if (toolError != null) return toolRunError(toolError)
        return when (error) {
        is FinanceException -> when (error.code) {
            FinanceError.GenerationMismatch -> AgentRunError.RunInvalidated
            FinanceError.RecoveryRequired -> AgentRunError.Internal
            else -> AgentRunError.Internal
        }
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
                com.paherdin.record2.sessions.AgentHistoryError.OutcomeUnknown -> AgentRunError.OutcomeUnknown
                else -> AgentRunError.Internal
            }
        is AgentRunException -> error.code
        is kotlinx.coroutines.TimeoutCancellationException -> AgentRunError.ProviderFailure
        is kotlinx.serialization.SerializationException -> AgentRunError.ProviderFailure
        is java.io.IOException -> AgentRunError.RecoverableIO
        else -> AgentRunError.ProviderFailure
        }
    }

    private fun toolRunError(error: RecordToolException): AgentRunError = when (error.code) {
        RecordToolFailure.BudgetExceeded -> AgentRunError.BudgetExceeded
        RecordToolFailure.AuthorizationRevoked -> AgentRunError.AuthorizationRequired
        RecordToolFailure.RunInvalidated, RecordToolFailure.GenerationMoved -> AgentRunError.RunInvalidated
        RecordToolFailure.RecoverableIO -> AgentRunError.RecoverableIO
        RecordToolFailure.StorageFull -> AgentRunError.StorageFull
        RecordToolFailure.RecoveryRequired -> AgentRunError.Internal
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

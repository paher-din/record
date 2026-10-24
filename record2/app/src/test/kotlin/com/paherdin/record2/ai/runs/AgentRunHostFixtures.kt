package com.paherdin.record2.ai.runs

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import com.paherdin.record2.ai.security.AiDenial
import com.paherdin.record2.ai.security.AiMode
import com.paherdin.record2.ai.security.AiSecurityError
import com.paherdin.record2.ai.security.AiSecurityException
import com.paherdin.record2.ai.security.AiSecurityService
import com.paherdin.record2.ai.security.AiSecurityState
import com.paherdin.record2.ai.security.AiSendEligibility
import com.paherdin.record2.ai.security.AiSendPermit
import com.paherdin.record2.ai.security.CredentialStatus
import com.paherdin.record2.ai.security.CredentialUnavailableException
import com.paherdin.record2.ai.security.CredentialVault
import com.paherdin.record2.ai.security.ProviderReceiver
import com.paherdin.record2.ai.security.RecordProviderReceivers
import com.paherdin.record2.sessions.AgentHistoryError
import com.paherdin.record2.sessions.AgentHistoryException
import com.paherdin.record2.sessions.AgentHistoryService
import com.paherdin.record2.sessions.AgentSessionInfo
import com.paherdin.record2.sessions.AgentSessionMessage
import com.paherdin.record2.sessions.CompletedHistoryEntry
import com.paherdin.record2.sessions.MessageResultKind
import com.paherdin.record2.sessions.PersistedRunResult
import com.paherdin.record2.sessions.ProcessOwnershipReceipt
import com.paherdin.record2.sessions.RunIdentity
import com.paherdin.record2.sessions.RunCompletionReceipt
import com.paherdin.record2.sessions.RunOutcome
import com.paherdin.record2.sessions.RunStartReceipt
import com.paherdin.record2.sessions.RunState
import com.paherdin.record2.sessions.RunTerminal
import com.paherdin.record2.sessions.RunTerminalReceipt
import com.paherdin.record2.sessions.SafeRunErrorCode
import com.paherdin.record2.sessions.SessionRole
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Host-side fixtures: real Koog agent over a scripted HTTP transport with in-memory history and
// security fakes. Real Room behavior is covered by instrumentation tests.

internal class ScriptedHttp(
    private val responder: suspend (callIndex: Int, requestBody: String) -> String,
) : KoogHttpClient {
    override val clientName: String = "record2-e1-scripted"
    val requests = CopyOnWriteArrayList<String>()

    override suspend fun <R : Any> get(
        path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>,
    ): R = error("ScriptedHttp does not support GET")

    override suspend fun <T : Any, R : Any> post(
        path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>,
        parameters: Map<String, String>, headers: Map<String, String>,
    ): R {
        val body = requestBody as String
        requests += body
        @Suppress("UNCHECKED_CAST")
        return responder(requests.size, body) as R
    }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>, headers: Map<String, String>,
    ) = error("ScriptedHttp does not support SSE")

    override fun <T : Any> lines(
        path: String, requestBody: T, requestBodyType: KClass<T>,
        parameters: Map<String, String>, headers: Map<String, String>,
    ) = error("ScriptedHttp does not support lines")

    override fun close() = Unit
}

internal fun chatResponse(
    text: String? = null,
    reasoning: String? = null,
    toolCall: Pair<String, String>? = null,
): String {
    val message = buildJsonObject {
        put("role", "assistant")
        put("content", text?.let(::JsonPrimitive) ?: JsonPrimitive(""))
        if (reasoning != null) put("reasoning_content", reasoning)
        if (toolCall != null) {
            put(
                "tool_calls",
                JsonArray(listOf(buildJsonObject {
                    put("id", toolCall.first)
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject { put("name", "host_echo"); put("arguments", toolCall.second) },
                    )
                })),
            )
        }
    }
    return buildJsonObject {
        put("id", "e1-fixture")
        put("object", "chat.completion")
        put("created", 0)
        put("model", "deepseek-flash")
        put("system_fingerprint", "e1-fixture")
        put("choices", JsonArray(listOf(buildJsonObject {
            put("index", 0)
            put("message", message)
            put("finish_reason", if (toolCall == null) "stop" else "tool_calls")
        })))
        put("usage", buildJsonObject {
            put("prompt_tokens", 10)
            put("completion_tokens", 5)
            put("total_tokens", 15)
        })
    }.toString()
}

internal fun requestMessages(body: String): List<JsonObject> =
    Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }

internal fun JsonObject.role(): String = this["role"]!!.jsonPrimitive.content
internal fun JsonObject.textContent(): String? = (this["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal class FakeHistoryService(
    private val receiverIdentity: String = RecordProviderReceivers.deepSeek.identity,
) : AgentHistoryService {
    val startedRuns = CopyOnWriteArrayList<RunStartReceipt>()
    val completedRuns = CopyOnWriteArrayList<Pair<RunStartReceipt, RunOutcome>>()
    val failedRuns = CopyOnWriteArrayList<Triple<String, RunTerminal, SafeRunErrorCode>>()
    val ownershipCalls = CopyOnWriteArrayList<String>()
    var completedHistoryCalls = 0

    var startRunError: AgentHistoryError? = null
    var completeRunError: AgentHistoryError? = null
    var completedHistoryError: AgentHistoryError? = null
    var requireAuthorizationMismatch: Boolean = false

    // Commit-then-lose-the-receipt simulations for coordinator reconciliation paths.
    var startRunPersistThenOutcomeUnknown: Boolean = false
    var completeRunPersistThenOutcomeUnknown: Boolean = false
    var persistedOutcomeOverride: RunOutcome? = null
    var persistedResultError: Exception? = null

    private var sequence = 0L
    private var sessionId: String? = null
    private val entries = CopyOnWriteArrayList<CompletedHistoryEntry>()
    private val inputs = CopyOnWriteArrayList<Pair<String, RunState>>()

    fun newSession(): AgentSessionInfo {
        val id = "session-${sessionIdCounter.incrementAndGet()}"
        sessionId = id
        sequence = entries.size.toLong()
        return AgentSessionInfo(id, null, 0, 0, 1, sequence)
    }

    fun seedCompleted(items: List<Pair<String, String>>) {
        items.forEach { (user, assistant) ->
            entries += CompletedHistoryEntry(SessionRole.USER, user)
            entries += CompletedHistoryEntry(SessionRole.ASSISTANT, assistant)
        }
        sequence = entries.size.toLong()
    }

    fun inputStates(): List<Pair<String, RunState>> = inputs.toList()

    fun entriesSnapshot(): List<CompletedHistoryEntry> = entries.toList()

    override suspend fun createSession(title: String?): AgentSessionInfo = newSession()

    override suspend fun session(sessionId: String): AgentSessionInfo =
        AgentSessionInfo(sessionId, null, 0, 0, 1, sequence)

    override suspend fun sessionMessages(sessionId: String): List<AgentSessionMessage> =
        inputs.mapIndexed { index, (body, state) ->
            AgentSessionMessage(
                id = "m$index", sequence = index.toLong(), role = SessionRole.USER, body = body,
                resultKind = MessageResultKind.INPUT, resultJson = null, proposalId = null,
                proposalRevision = null, runId = "r$index", runState = state, createdAt = 0,
                references = emptyList(),
            )
        }

    override suspend fun completedHistory(
        sessionId: String,
        beforeSequence: Long,
        limit: Int?,
    ): List<CompletedHistoryEntry> {
        completedHistoryCalls += 1
        completedHistoryError?.let { throw AgentHistoryException(it) }
        val source = entries.toList()
        return if (limit == null) source else source.takeLast(limit)
    }

    override fun newRunIdentity(): RunIdentity = RunIdentity(
        runId = "run-${startedRuns.size + 1}",
        userMessageId = "m-${startedRuns.size + 1}-user",
        proposalCreationRequestId = "req-${startedRuns.size + 1}",
    )

    override suspend fun startRun(
        sessionId: String,
        userText: String,
        receiverIdentity: String,
        authEpoch: Long,
        processOwnerId: String,
        identity: RunIdentity,
    ): RunStartReceipt {
        startRunError?.let { throw AgentHistoryException(it) }
        if (requireAuthorizationMismatch) throw AgentHistoryException(AgentHistoryError.AuthorizationRequired)
        val receipt = RunStartReceipt(
            runId = identity.runId,
            sessionId = sessionId,
            userMessageId = identity.userMessageId,
            userMessageSequence = sequence,
            boundarySequence = sequence,
            boundaryHistoryVersion = 1,
            proposalCreationRequestId = identity.proposalCreationRequestId,
            receiverIdentity = receiverIdentity,
            authEpoch = authEpoch,
            dataGeneration = "generation",
            processOwnerId = processOwnerId,
            startedAt = 0,
        )
        startedRuns += receipt
        inputs += userText to RunState.RUNNING
        sequence += 1
        if (startRunPersistThenOutcomeUnknown) {
            throw AgentHistoryException(AgentHistoryError.OutcomeUnknown)
        }
        return receipt
    }

    override suspend fun completeRun(
        runId: String,
        receiverIdentity: String,
        authEpoch: Long,
        processOwnerId: String,
        outcome: RunOutcome,
    ): RunCompletionReceipt {
        completeRunError?.let { throw AgentHistoryException(it) }
        val start = startedRuns.single { it.runId == runId }
        completedRuns += start to outcome
        entries += CompletedHistoryEntry(SessionRole.USER, startedBody(start))
        entries += CompletedHistoryEntry(SessionRole.ASSISTANT, outcome.body)
        sequence += 1
        inputs.replaceAll { (body, state) -> if (body == startedBody(start)) body to RunState.COMPLETED else body to state }
        val receipt = RunCompletionReceipt(runId, "assistant-${completedRuns.size}", sequence - 1, 0, false)
        if (completeRunPersistThenOutcomeUnknown) {
            throw AgentHistoryException(AgentHistoryError.OutcomeUnknown)
        }
        return receipt
    }

    override suspend fun persistedResult(runId: String): PersistedRunResult? {
        persistedResultError?.let { throw it }
        val completed = completedRuns.singleOrNull { it.first.runId == runId }
        return if (completed != null) {
            PersistedRunResult(
                runId = runId,
                state = RunState.COMPLETED,
                assistantMessageId = "assistant-${completedRuns.indexOf(completed) + 1}",
                assistantMessageSequence = completed.second.let { sequence - 1 },
                finishedAt = 0,
                outcome = persistedOutcomeOverride ?: completed.second,
            )
        } else {
            startedRuns.singleOrNull { it.runId == runId }?.let {
                PersistedRunResult(runId, RunState.RUNNING, null, null, null, null)
            }
        }
    }

    override suspend fun failRun(
        runId: String,
        state: RunTerminal,
        safeErrorCode: SafeRunErrorCode,
    ): RunTerminalReceipt {
        val start = startedRuns.single { it.runId == runId }
        failedRuns += Triple(runId, state, safeErrorCode)
        inputs.replaceAll { (body, runState) ->
            if (body == startedBody(start) && runState == RunState.RUNNING) {
                body to (if (state == RunTerminal.CANCELLED) RunState.CANCELLED else RunState.FAILED)
            } else {
                body to runState
            }
        }
        return RunTerminalReceipt(runId, if (state == RunTerminal.CANCELLED) RunState.CANCELLED else RunState.FAILED, safeErrorCode.name, false)
    }

    override suspend fun beginProcessOwnership(processOwnerId: String): ProcessOwnershipReceipt {
        ownershipCalls += processOwnerId
        return ProcessOwnershipReceipt(processOwnerId, true, 0, false)
    }

    private fun startedBody(start: RunStartReceipt): String =
        startedRuns.indexOf(start).let { inputs[it].first }

    private companion object {
        val sessionIdCounter = java.util.concurrent.atomic.AtomicInteger(0)
    }
}

internal class FakeVault : CredentialVault {
    override fun status(): CredentialStatus = CredentialStatus.AVAILABLE
    override fun store(secret: CharArray) = Unit
    override fun clear() = Unit
    override fun <T> use(block: (CharArray) -> T): T = block("sk-synthetic-host".toCharArray())
}

internal class FakeAiSecurity(
    private val receiver: ProviderReceiver = RecordProviderReceivers.deepSeek,
    private val denial: AiDenial? = null,
) : AiSecurityService {
    val permits = CopyOnWriteArrayList<AiSendPermit>()

    override suspend fun state(): AiSecurityState = AiSecurityState(
        AiMode.FULL, receiver.identity, receiver.identity, receiver.scopeVersion,
        receiver.disclosureVersion, 0, 7, CredentialStatus.AVAILABLE,
    )

    override suspend fun setMode(mode: AiMode): AiSecurityState = state()
    override suspend fun selectReceiver(receiver: ProviderReceiver): AiSecurityState = state()
    override suspend fun grant(receiver: ProviderReceiver): AiSecurityState = state()
    override suspend fun revoke(): AiSecurityState = state()
    override suspend fun replaceCredential(secret: CharArray): AiSecurityState = state()
    override suspend fun clearCredential(): AiSecurityState = state()

    override suspend fun eligibility(receiver: ProviderReceiver): AiSendEligibility =
        if (denial == null) AiSendEligibility.Ready else AiSendEligibility.Denied(denial)

    override suspend fun requirePermit(receiver: ProviderReceiver): AiSendPermit {
        denial?.let { throw AiSecurityException(AiSecurityError.AuthorizationRequired) }
        return AiSendPermit(receiver, 7).also { permits += it }
    }

    override suspend fun stillValid(permit: AiSendPermit): Boolean = denial == null

    override suspend fun <T> useCredential(permit: AiSendPermit, block: (CharArray) -> T): T {
        if (denial != null) throw AiSecurityException(AiSecurityError.RunInvalidated)
        return try {
            block("sk-synthetic-host".toCharArray())
        } catch (error: CredentialUnavailableException) {
            throw AiSecurityException(AiSecurityError.CredentialUnavailable, error)
        }
    }
}

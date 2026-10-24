package com.paherdin.record2.ai.tools

import com.paherdin.record2.ai.security.AiSendPermit
import com.paherdin.record2.ai.security.AiSecurityService
import com.paherdin.record2.proposals.ProposalError
import com.paherdin.record2.proposals.ProposalException
import com.paherdin.record2.proposals.ProposalService
import com.paherdin.record2.proposals.ProposalState
import com.paherdin.record2.proposals.ProposalView
import com.paherdin.record2.records.FinanceService
import com.paherdin.record2.records.TimelineService
import com.paherdin.record2.records.TimelineError
import com.paherdin.record2.records.TimelineException
import com.paherdin.record2.sessions.AgentHistoryService
import com.paherdin.record2.sessions.MessageReference
import com.paherdin.record2.sessions.MessageResultKind
import com.paherdin.record2.sessions.RunOutcome
import com.paherdin.record2.sessions.RunStartReceipt
import com.paherdin.record2.sessions.RunState
import java.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Real business services the four tools delegate to; no DAO and no commit surface is exposed. */
internal class RecordToolDeps(
    val security: AiSecurityService,
    val history: AgentHistoryService,
    val timeline: TimelineService,
    val proposals: ProposalService,
    val finance: FinanceService,
)

/**
 * One run's tool execution state. Every tool entry passes [guard], which revalidates the E0
 * authorization epoch, the persisted run state and the shared budget ledger (Spec §6: each tool
 * entry checks valid authorization, Run/Session/generation). The context also collects the native
 * handoff evidence — surfaced record references, the proposal this run wrote and unresolved
 * clarification issues — that the Coordinator turns into the run's terminal outcome.
 */
internal class RecordToolContext(
    val deps: RecordToolDeps,
    val permit: AiSendPermit,
    val start: RunStartReceipt,
    clock: Clock,
) {
    val ledger = ToolBudgetLedger(clock, start.startedAt + RecordToolBudget.WALL_MS_PER_RUN)
    private val transactionReferences = LinkedHashSet<String>()
    private val noteReferences = LinkedHashSet<String>()

    /** Non-empty when the last prepare_changes reply left facts unresolved and no proposal exists. */
    var clarificationIssues: List<String> = emptyList()
        private set

    /**
     * Set by the first hard failure. Koog 1.3.0 turns a thrown tool error into a model-visible
     * tool result and keeps looping, so the tool layer alone cannot stop further network requests;
     * the coordinator's guarded HTTP client re-checks this flag (plus authorization and the wall
     * clock) before every request and fails the run at the request boundary (E0 §23: D0/D1 own the
     * final revalidation at the actual request start).
     */
    var poisoned: RecordToolFailure? = null
        private set

    fun poison(code: RecordToolFailure, message: String): Nothing {
        if (poisoned == null) poisoned = code
        throw RecordToolException(code, message)
    }

    fun poisonFrom(error: Throwable): Nothing {
        val mapped = hard(error)
        if (poisoned == null) poisoned = mapped.code
        throw mapped
    }

    suspend fun guard(tool: String, argsKey: String, perToolCap: Int) {
        try {
            ledger.checkEntry(tool, argsKey, perToolCap)
        } catch (error: RecordToolException) {
            poison(error.code, error.message ?: "budget exceeded")
        }
        if (!deps.security.stillValid(permit)) {
            poison(RecordToolFailure.AuthorizationRevoked, "$tool after revocation")
        }
        val run = deps.history.persistedResult(start.runId)
            ?: poison(RecordToolFailure.RunInvalidated, "$tool: run row missing")
        if (run.state != RunState.RUNNING) {
            poison(RecordToolFailure.RunInvalidated, "$tool: run is ${run.state}")
        }
    }

    fun charge(result: String): String = try {
        ledger.chargeResult(result)
    } catch (error: RecordToolException) {
        poison(error.code, error.message ?: "budget exceeded")
    }

    fun recordReference(kind: String, id: String) {
        when (kind) {
            "TRANSACTION" -> if (transactionReferences.size < RecordToolBudget.REFERENCES_PER_KIND) {
                transactionReferences += id
            }
            "NOTE" -> if (noteReferences.size < RecordToolBudget.REFERENCES_PER_KIND) {
                noteReferences += id
            }
        }
    }

    fun references(): List<MessageReference> {
        val bounded = transactionReferences.take(RecordToolBudget.REFERENCES_PER_KIND)
            .map { MessageReference(ordinal = 0, transactionId = it, noteId = null) } +
            noteReferences.take(RecordToolBudget.REFERENCES_PER_KIND)
                .map { MessageReference(ordinal = 0, transactionId = null, noteId = it) }
        return bounded.mapIndexed { index, reference -> reference.copy(ordinal = index) }
    }

    fun onProposalPrepared() {
        clarificationIssues = emptyList()
    }

    fun onClarification(issues: List<String>) {
        clarificationIssues = issues
    }

    /**
     * Deterministic completion classification from tool state, never from the model's own claim:
     * a proposal this run last wrote and that is still editable wins; unresolved prepare issues
     * become ClarificationNeeded; anything else is an Answer. The tool layer never commits, so
     * ProposalReady only ever means "an editable proposal exists for native confirmation".
     */
    suspend fun classifyOutcome(reply: String): RunOutcome {
        val active = try {
            deps.proposals.activeProposal(start.dataGeneration, start.sessionId)
        } catch (error: Exception) {
            throw hard(error)
        }
        return when {
            active != null && active.lastWriterRunId == start.runId && active.state == ProposalState.EDITABLE ->
                RunOutcome(
                    resultKind = MessageResultKind.ProposalReady,
                    body = reply,
                    resultJson = proposalResultJson(active),
                    proposalId = active.id,
                    proposalRevision = active.revision,
                    references = references(),
                )
            clarificationIssues.isNotEmpty() -> RunOutcome(
                resultKind = MessageResultKind.ClarificationNeeded,
                body = reply,
                resultJson = buildJsonObject {
                    put("issues", buildJsonArray { clarificationIssues.forEach { add(JsonPrimitive(it)) } })
                }.toString(),
                references = references(),
            )
            else -> RunOutcome(
                resultKind = MessageResultKind.Answer,
                body = reply,
                references = references(),
            )
        }
    }

    private fun proposalResultJson(active: ProposalView): String = buildJsonObject {
        put("proposalId", active.id)
        put("revision", active.revision)
        put("state", active.state.name)
        put("awaitingUserConfirmation", true)
        putJsonObject("coverage") {
            put("operations", active.operations.size)
            put("formalRecordsWritten", 0)
        }
    }.toString()

    companion object {
        val toolJson: Json = Json { encodeDefaults = false }

        /** Hard-failure mapping shared by all four tools. */
        fun hard(error: Throwable): RecordToolException = when (error) {
            is RecordToolException -> error
            is ProposalException -> when (error.code) {
                ProposalError.RunInvalidated ->
                    RecordToolException(RecordToolFailure.RunInvalidated, "proposal service: ${error.code}")
                ProposalError.GenerationMismatch ->
                    RecordToolException(RecordToolFailure.GenerationMoved, "proposal service: ${error.code}")
                ProposalError.RecoveryRequired ->
                    RecordToolException(RecordToolFailure.RecoveryRequired, "proposal service: ${error.code}")
                ProposalError.RecoverableIO ->
                    RecordToolException(RecordToolFailure.RecoverableIO, "proposal service: ${error.code}")
                ProposalError.StorageFull ->
                    RecordToolException(RecordToolFailure.StorageFull, "proposal service: ${error.code}")
                else -> RecordToolException(RecordToolFailure.RecoveryRequired, "proposal service: ${error.code}")
            }
            is TimelineException -> when (error.code) {
                TimelineError.GenerationMismatch ->
                    RecordToolException(RecordToolFailure.GenerationMoved, "timeline service: ${error.code}")
                TimelineError.RecoveryRequired ->
                    RecordToolException(RecordToolFailure.RecoveryRequired, "timeline service: ${error.code}")
                TimelineError.RecoverableIO ->
                    RecordToolException(RecordToolFailure.RecoverableIO, "timeline service: ${error.code}")
                TimelineError.StorageFull ->
                    RecordToolException(RecordToolFailure.StorageFull, "timeline service: ${error.code}")
                else -> RecordToolException(RecordToolFailure.RecoveryRequired, "timeline service: ${error.code}")
            }
            else -> RecordToolException(RecordToolFailure.RecoveryRequired, error.message ?: "tool failure")
        }
    }
}

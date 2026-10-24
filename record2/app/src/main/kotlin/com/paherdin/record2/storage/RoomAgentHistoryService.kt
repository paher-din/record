package com.paherdin.record2.storage

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.paherdin.record2.sessions.AgentHistoryError
import com.paherdin.record2.sessions.AgentHistoryException
import com.paherdin.record2.sessions.AgentSessionInfo
import com.paherdin.record2.sessions.AgentSessionMessage
import com.paherdin.record2.sessions.AgentHistoryService
import com.paherdin.record2.sessions.CompletedHistoryEntry
import com.paherdin.record2.sessions.MessageReference
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
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal enum class SessionCheckpoint {
    RUN_WRITTEN,
    RUN_START_COMMITTED,
    COMPLETION_WRITTEN,
    COMPLETION_COMMITTED,
    TERMINAL_WRITTEN,
    TERMINAL_COMMITTED,
    OWNERSHIP_COMMITTED,
}

/**
 * Room-authoritative session/run/message history (B0 §3.4, §6). Every state change is one short
 * Room transaction with conditional updates and affected-row checks; the database is the only
 * authority for what was said, which run owns the current input and which runs completed.
 */
internal class RoomAgentHistoryService(
    private val database: RecordDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val checkpoint: (SessionCheckpoint) -> Unit = {},
) : AgentHistoryService {
    private val dao = database.sessions()
    private val records = database.records()
    private val control = database.aiControl()

    override suspend fun createSession(title: String?): AgentSessionInfo = io {
        database.withTransaction {
            requireGeneration()
            val now = clock.millis()
            val row = AgentSessionEntity(
                id = UUID.randomUUID().toString(),
                title = title?.takeIf { it.isNotBlank() },
                createdAt = now,
                updatedAt = now,
                historyVersion = 1,
                nextMessageSequence = 0,
            )
            dao.insertSession(row)
            row.dto()
        }
    }

    override suspend fun session(sessionId: String): AgentSessionInfo = io {
        database.withTransaction { readSession(sessionId).dto() }
    }

    override suspend fun sessionMessages(sessionId: String): List<AgentSessionMessage> = io {
        database.withTransaction {
            readSession(sessionId)
            val references = dao.referencesForSession(sessionId).groupBy { it.messageId }
            dao.messagesWithRunState(sessionId).map { row ->
                val message = row.message
                AgentSessionMessage(
                    id = message.id,
                    sequence = message.sequence,
                    role = parseRole(message.role),
                    body = message.body,
                    resultKind = parseKind(message.resultKind),
                    resultJson = message.resultJson,
                    proposalId = message.proposalId,
                    proposalRevision = message.proposalRevision,
                    runId = message.runId,
                    runState = parseState(row.runState),
                    createdAt = message.createdAt,
                    references = references[message.id].orEmpty().map {
                        MessageReference(it.ordinal, it.transactionId, it.noteId)
                    },
                )
            }
        }
    }

    override suspend fun completedHistory(
        sessionId: String,
        beforeSequence: Long,
        limit: Int?,
    ): List<CompletedHistoryEntry> = io {
        database.withTransaction {
            readSession(sessionId)
            val rows = if (limit == null) {
                dao.completedHistoryBefore(sessionId, beforeSequence)
            } else {
                require(limit >= 0)
                // Most recent first, bounded at the database; restore ascending order after.
                dao.completedHistoryBeforeLimited(sessionId, beforeSequence, limit).asReversed()
            }
            rows.map { row ->
                CompletedHistoryEntry(
                    role = parseRole(row.role),
                    body = row.body,
                )
            }
        }
    }

    override fun newRunIdentity(): RunIdentity = RunIdentity(
        runId = UUID.randomUUID().toString(),
        userMessageId = UUID.randomUUID().toString(),
        proposalCreationRequestId = UUID.randomUUID().toString(),
    )

    override suspend fun persistedResult(runId: String): PersistedRunResult? = io {
        database.withTransaction {
            val run = dao.run(runId) ?: return@withTransaction null
            val assistant = dao.finalAssistant(runId)
            PersistedRunResult(
                runId = runId,
                state = parseState(run.state),
                assistantMessageId = assistant?.id,
                assistantMessageSequence = assistant?.sequence,
                finishedAt = run.finishedAt,
                body = assistant?.body,
            )
        }
    }

    override suspend fun startRun(
        sessionId: String,
        userText: String,
        receiverIdentity: String,
        authEpoch: Long,
        processOwnerId: String,
        identity: RunIdentity,
    ): RunStartReceipt = io {
        if (userText.isEmpty()) fail(AgentHistoryError.Validation)
        try {
            database.withTransaction {
                val state = requireGeneration()
                if (state.processOwnerId != processOwnerId) fail(AgentHistoryError.RecoveryRequired)
                requireAuthorization(receiverIdentity, authEpoch)
                val session = readSession(sessionId)
                if (session.nextMessageSequence >= Long.MAX_VALUE) fail(AgentHistoryError.RecoveryRequired)
                if (dao.activeRun(sessionId) != null) fail(AgentHistoryError.SessionBusy)
                val now = clock.millis()
                val runId = identity.runId
                val messageId = identity.userMessageId
                val creationRequestId = identity.proposalCreationRequestId
                val boundary = session.nextMessageSequence
                dao.insertRun(
                    AgentRunEntity(
                        id = runId,
                        sessionId = sessionId,
                        dataGeneration = state.dataGeneration,
                        processOwnerId = processOwnerId,
                        receiverIdentity = receiverIdentity,
                        authEpoch = authEpoch,
                        proposalCreationRequestId = creationRequestId,
                        historyBoundarySequence = boundary,
                        historyBoundaryVersion = session.historyVersion,
                        state = RunState.RUNNING.name,
                        activeSlot = 1,
                        startedAt = now,
                        finishedAt = null,
                        safeErrorCode = null,
                    ),
                )
                dao.insertMessage(
                    AgentMessageEntity(
                        id = messageId,
                        sessionId = sessionId,
                        runId = runId,
                        sequence = boundary,
                        role = SessionRole.USER.name,
                        body = userText,
                        createdAt = now,
                        resultKind = MessageResultKind.INPUT.name,
                        resultJson = null,
                        proposalId = null,
                        proposalRevision = null,
                    ),
                )
                if (dao.advanceSequence(sessionId, boundary, boundary + 1, now) != 1) {
                    fail(AgentHistoryError.ConcurrentUpdate)
                }
                mark(SessionCheckpoint.RUN_WRITTEN)
                RunStartReceipt(
                    runId = runId,
                    sessionId = sessionId,
                    userMessageId = messageId,
                    userMessageSequence = boundary,
                    boundarySequence = boundary,
                    boundaryHistoryVersion = session.historyVersion,
                    proposalCreationRequestId = creationRequestId,
                    receiverIdentity = receiverIdentity,
                    authEpoch = authEpoch,
                    dataGeneration = state.dataGeneration,
                    processOwnerId = processOwnerId,
                    startedAt = now,
                )
            }
        } catch (error: SQLiteConstraintException) {
            // The unique (sessionId, activeSlot) index is the concurrency gate (B0 §6): a losing
            // concurrent start must surface as a busy session, not as storage corruption.
            throw AgentHistoryException(AgentHistoryError.SessionBusy, error)
        }.also { receipt ->
            // Post-commit cancellation must not strand a committed run without its receipt:
            // the caller reconciles by the identity it generated, but only if it learns the
            // outcome, so the tail runs outside cancellation while the transaction body stays
            // cancellable (rollback semantics preserved).
            withContext(NonCancellable) { committed(SessionCheckpoint.RUN_START_COMMITTED) }
            receipt
        }
    }

    override suspend fun completeRun(
        runId: String,
        receiverIdentity: String,
        authEpoch: Long,
        processOwnerId: String,
        outcome: RunOutcome,
    ): RunCompletionReceipt = io {
        database.withTransaction {
            val run = dao.run(runId) ?: fail(AgentHistoryError.NotFound)
            val now = clock.millis()
            if (run.state == RunState.COMPLETED.name) {
                val existing = dao.finalAssistant(runId) ?: fail(AgentHistoryError.RecoveryRequired)
                if (!sameOutcome(existing, outcome)) fail(AgentHistoryError.RunInvalidated)
                return@withTransaction RunCompletionReceipt(
                    runId = runId,
                    assistantMessageId = existing.id,
                    assistantMessageSequence = existing.sequence,
                    finishedAt = run.finishedAt ?: now,
                    alreadyCompleted = true,
                )
            }
            if (run.state != RunState.RUNNING.name || run.activeSlot == null) {
                fail(AgentHistoryError.RunInvalidated)
            }
            if (run.processOwnerId != processOwnerId) fail(AgentHistoryError.RunInvalidated)
            if (run.receiverIdentity != receiverIdentity || run.authEpoch != authEpoch) {
                fail(AgentHistoryError.AuthorizationRequired)
            }
            val state = records.dataState() ?: fail(AgentHistoryError.RecoveryRequired)
            if (state.dataGeneration != run.dataGeneration) fail(AgentHistoryError.RunInvalidated)
            val currentControl = control.control()
            if (currentControl == null || currentControl.authEpoch != run.authEpoch) {
                fail(AgentHistoryError.AuthorizationRequired)
            }
            val session = readSession(run.sessionId)
            if (session.historyVersion != run.historyBoundaryVersion) {
                // The completed history boundary is fixed at start (B0 §6); slot exclusivity makes
                // drift impossible for correct callers, so refuse instead of committing on it.
                fail(AgentHistoryError.RecoveryRequired)
            }
            if (session.nextMessageSequence >= Long.MAX_VALUE) fail(AgentHistoryError.RecoveryRequired)
            val assistantId = UUID.randomUUID().toString()
            val sequence = session.nextMessageSequence
            validateOutcome(outcome)
            dao.insertMessage(
                AgentMessageEntity(
                    id = assistantId,
                    sessionId = run.sessionId,
                    runId = runId,
                    sequence = sequence,
                    role = SessionRole.ASSISTANT.name,
                    body = outcome.body,
                    createdAt = now,
                    resultKind = outcome.resultKind.name,
                    resultJson = outcome.resultJson,
                    proposalId = outcome.proposalId,
                    proposalRevision = outcome.proposalRevision,
                ),
            )
            outcome.references.forEach { reference ->
                dao.insertReference(
                    AgentMessageReferenceEntity(assistantId, reference.ordinal, reference.transactionId, reference.noteId),
                )
            }
            if (dao.completeRun(runId, now, processOwnerId, run.dataGeneration) != 1) {
                fail(AgentHistoryError.ConcurrentUpdate)
            }
            if (dao.advanceHistory(
                    id = run.sessionId,
                    expected = sequence,
                    next = sequence + 1,
                    expectedHistoryVersion = session.historyVersion,
                    nextHistoryVersion = session.historyVersion + 1,
                    updatedAt = now,
                ) != 1
            ) {
                fail(AgentHistoryError.ConcurrentUpdate)
            }
            mark(SessionCheckpoint.COMPLETION_WRITTEN)
            RunCompletionReceipt(
                runId = runId,
                assistantMessageId = assistantId,
                assistantMessageSequence = sequence,
                finishedAt = now,
                alreadyCompleted = false,
            )
        }.also { receipt ->
            // Same post-commit tail rule as startRun: cancellation after the commit must not
            // misreport a committed final reply as a lost one.
            withContext(NonCancellable) { committed(SessionCheckpoint.COMPLETION_COMMITTED) }
            receipt
        }
    }

    override suspend fun failRun(
        runId: String,
        state: RunTerminal,
        safeErrorCode: SafeRunErrorCode,
    ): RunTerminalReceipt = io {
        database.withTransaction {
            val run = dao.run(runId) ?: fail(AgentHistoryError.NotFound)
            if (run.state != RunState.RUNNING.name || run.activeSlot == null) {
                return@withTransaction RunTerminalReceipt(runId, parseState(run.state), run.safeErrorCode, true)
            }
            val current = records.dataState() ?: fail(AgentHistoryError.RecoveryRequired)
            if (current.processOwnerId != run.processOwnerId) {
                // A stale process lost its runs to the ownership gate; it must not retag them.
                return@withTransaction RunTerminalReceipt(runId, parseState(run.state), run.safeErrorCode, true)
            }
            val now = clock.millis()
            if (dao.failRun(runId, state.name, now, safeErrorCode.name, run.processOwnerId) != 1) {
                fail(AgentHistoryError.ConcurrentUpdate)
            }
            mark(SessionCheckpoint.TERMINAL_WRITTEN)
            RunTerminalReceipt(runId, parseState(state.name), safeErrorCode.name, false)
        }.also { receipt ->
            withContext(NonCancellable) { committed(SessionCheckpoint.TERMINAL_COMMITTED) }
            receipt
        }
    }

    override suspend fun beginProcessOwnership(processOwnerId: String): ProcessOwnershipReceipt = io {
        database.withTransaction {
            val state = records.dataState()
            if (state == null) {
                if (records.hasDataWithoutGeneration()) fail(AgentHistoryError.RecoveryRequired)
                return@withTransaction ProcessOwnershipReceipt(processOwnerId, false, 0, true)
            }
            if (state.processOwnerId == processOwnerId) {
                return@withTransaction ProcessOwnershipReceipt(processOwnerId, false, 0, false)
            }
            val interrupted = dao.interruptAllRunning(clock.millis())
            if (records.updateProcessOwner(processOwnerId) != 1) fail(AgentHistoryError.ConcurrentUpdate)
            mark(SessionCheckpoint.OWNERSHIP_COMMITTED)
            ProcessOwnershipReceipt(processOwnerId, true, interrupted, false)
        }
    }

    private fun sameOutcome(existing: AgentMessageEntity, outcome: RunOutcome): Boolean {
        val persistedReferences = dao.referencesForMessage(existing.id)
            .map { Triple(it.ordinal, it.transactionId, it.noteId) }
        val requestedReferences = outcome.references
            .map { Triple(it.ordinal, it.transactionId, it.noteId) }
        return existing.body == outcome.body &&
            existing.resultKind == outcome.resultKind.name &&
            existing.resultJson == outcome.resultJson &&
            existing.proposalId == outcome.proposalId &&
            existing.proposalRevision == outcome.proposalRevision &&
            persistedReferences == requestedReferences
    }

    private fun validateOutcome(outcome: RunOutcome) {
        if (outcome.proposalId != null && outcome.proposalRevision == null) fail(AgentHistoryError.Validation)
        if (outcome.proposalId == null && outcome.proposalRevision != null) fail(AgentHistoryError.Validation)
        outcome.references.forEachIndexed { index, reference ->
            if (reference.ordinal != index) fail(AgentHistoryError.Validation)
            val hasTransaction = reference.transactionId != null
            val hasNote = reference.noteId != null
            if (hasTransaction == hasNote) fail(AgentHistoryError.Validation)
            if (hasTransaction && records.transaction(reference.transactionId) == null) {
                fail(AgentHistoryError.Validation)
            }
            if (hasNote && records.noteForLifecycle(reference.noteId!!) == null) fail(AgentHistoryError.Validation)
        }
    }

    private fun requireGeneration(): RecordDataStateEntity {
        val state = records.dataState()
        if (state == null) {
            if (records.hasDataWithoutGeneration()) fail(AgentHistoryError.RecoveryRequired)
            fail(AgentHistoryError.NotInitialized)
        }
        if (runCatching { UUID.fromString(state.dataGeneration) }.getOrNull() == null) {
            fail(AgentHistoryError.RecoveryRequired)
        }
        return state
    }

    private fun requireAuthorization(receiverIdentity: String, authEpoch: Long) {
        val row = control.control() ?: fail(AgentHistoryError.NotInitialized)
        if (row.mode != "FULL" ||
            row.selectedReceiverIdentity != receiverIdentity ||
            row.grantReceiverIdentity != receiverIdentity ||
            row.grantScopeVersion == null ||
            row.grantDisclosureVersion == null ||
            row.authEpoch != authEpoch
        ) {
            fail(AgentHistoryError.AuthorizationRequired)
        }
    }

    private fun readSession(sessionId: String): AgentSessionEntity =
        dao.session(sessionId) ?: fail(AgentHistoryError.NotFound)

    private fun AgentSessionEntity.dto() = AgentSessionInfo(
        id = id, title = title, createdAt = createdAt, updatedAt = updatedAt,
        historyVersion = historyVersion, nextMessageSequence = nextMessageSequence,
    )

    private fun parseRole(label: String): SessionRole = parse(SessionRole::valueOf, label)
    private fun parseKind(label: String): MessageResultKind = parse(MessageResultKind::valueOf, label)
    private fun parseState(label: String): RunState = parse(RunState::valueOf, label)

    private fun <E : Enum<E>> parse(parser: (String) -> E, label: String): E =
        runCatching { parser(label) }.getOrElse { fail(AgentHistoryError.RecoveryRequired) }

    private suspend fun mark(point: SessionCheckpoint) {
        checkpoint(point)
        currentCoroutineContext().ensureActive()
    }

    private suspend fun committed(point: SessionCheckpoint) {
        try {
            mark(point)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw AgentHistoryException(AgentHistoryError.OutcomeUnknown, error)
        }
    }

    private fun fail(code: AgentHistoryError): Nothing = throw AgentHistoryException(code)

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: AgentHistoryException) {
            throw error
        } catch (error: SQLiteFullException) {
            throw AgentHistoryException(AgentHistoryError.StorageFull, error)
        } catch (error: SQLiteException) {
            throw AgentHistoryException(AgentHistoryError.RecoverableIO, error)
        }
    }
}

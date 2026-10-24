package com.paherdin.record2.backup

import com.paherdin.record2.storage.AccountEntity
import com.paherdin.record2.storage.AgentMessageEntity
import com.paherdin.record2.storage.AgentMessageReferenceEntity
import com.paherdin.record2.storage.AgentRunEntity
import com.paherdin.record2.storage.AgentSessionEntity
import com.paherdin.record2.storage.CategoryEntity
import com.paherdin.record2.storage.ChangeProposalEntity
import com.paherdin.record2.storage.CommitOperationEntity
import com.paherdin.record2.storage.NativeFinanceDraftEntity
import com.paherdin.record2.storage.NoteEntity
import com.paherdin.record2.storage.TransactionEntity
import com.paherdin.record2.storage.TransactionNoteLinkEntity

internal fun AccountRow.toEntity() = AccountEntity(id, name, currency, version, createdAt, updatedAt)

internal fun CategoryRow.toEntity() = CategoryEntity(id, name, direction, version, createdAt, updatedAt)

internal fun TransactionRow.toEntity() = TransactionEntity(
    id, direction, amountMinor, currency, accountId, categoryId, memo, occurredAt, version, createdAt, updatedAt,
)

internal fun NoteRow.toEntity() = NoteEntity(id, body, occurredAt, version, createdAt, updatedAt, deletedAt)

internal fun LinkRow.toEntity() = TransactionNoteLinkEntity(transactionId, noteId)

internal fun DraftRow.toEntity() = NativeFinanceDraftEntity(
    slot, draftId, draftRevision, state, mode, recordId, baseVersion, inputJson, updatedAt, null,
)

internal fun ProposalRow.toEntity() = ChangeProposalEntity(
    id, sessionId, originRunId, lastWriterRunId, creationRequestId, creationPayloadJson,
    revision, payloadJson, payloadDigest, payloadFormatVersion, dataGeneration, state,
    activeSlot, createdAt, updatedAt,
)

internal fun OperationRow.toEntity() = CommitOperationEntity(
    id, proposalId, confirmedRevision, createdGeneration, frozenPayloadJson, payloadDigest,
    payloadFormatVersion, state, createdAt, finishedAt, resultJson, rejectionCode,
)

internal fun SessionRow.toEntity() = AgentSessionEntity(
    id, title, createdAt, updatedAt, historyVersion, nextMessageSequence,
)

internal fun RunRow.toEntity() = AgentRunEntity(
    id, sessionId, dataGeneration, processOwnerId, receiverIdentity, authEpoch,
    proposalCreationRequestId, historyBoundarySequence, historyBoundaryVersion, state,
    activeSlot, startedAt, finishedAt, safeErrorCode,
)

internal fun MessageRow.toEntity() = AgentMessageEntity(
    id, sessionId, runId, sequence, role, body, createdAt, resultKind, resultJson,
    proposalId, proposalRevision,
)

internal fun ReferenceRow.toEntity() = AgentMessageReferenceEntity(messageId, ordinal, transactionId, noteId)

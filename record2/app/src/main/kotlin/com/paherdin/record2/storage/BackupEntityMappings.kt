package com.paherdin.record2.storage

import com.paherdin.record2.backup.AccountRow
import com.paherdin.record2.backup.CategoryRow
import com.paherdin.record2.backup.DraftRow
import com.paherdin.record2.backup.LinkRow
import com.paherdin.record2.backup.MessageRow
import com.paherdin.record2.backup.NoteRow
import com.paherdin.record2.backup.OperationRow
import com.paherdin.record2.backup.ProposalRow
import com.paherdin.record2.backup.ReferenceRow
import com.paherdin.record2.backup.RunRow
import com.paherdin.record2.backup.SessionRow
import com.paherdin.record2.backup.TransactionRow

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

package com.paherdin.record2.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "agent_session")
internal data class AgentSessionEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val historyVersion: Long,
    val nextMessageSequence: Long,
)

@Entity(
    tableName = "agent_run",
    foreignKeys = [ForeignKey(
        entity = AgentSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"],
        onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["id", "sessionId"], unique = true),
        Index(value = ["sessionId", "activeSlot"], unique = true),
        Index(value = ["proposalCreationRequestId"], unique = true),
    ],
)
internal data class AgentRunEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val dataGeneration: String,
    val processOwnerId: String,
    val receiverIdentity: String,
    val authEpoch: Long,
    val proposalCreationRequestId: String,
    val historyBoundarySequence: Long,
    val historyBoundaryVersion: Long,
    val state: String,
    val activeSlot: Int?,
    val startedAt: Long,
    val finishedAt: Long?,
    val safeErrorCode: String?,
)

@Entity(
    tableName = "agent_message",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AgentRunEntity::class, parentColumns = ["id", "sessionId"],
            childColumns = ["runId", "sessionId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "sequence"], unique = true),
        Index(value = ["runId", "role"], unique = true),
        Index(value = ["runId", "sessionId"]),
    ],
)
internal data class AgentMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val runId: String,
    val sequence: Long,
    val role: String,
    val body: String,
    val createdAt: Long,
    val resultKind: String,
    val resultJson: String?,
    val proposalId: String?,
    val proposalRevision: Long?,
)

@Entity(
    tableName = "agent_message_reference",
    primaryKeys = ["messageId", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = AgentMessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["transactionId"]), Index(value = ["noteId"])],
)
internal data class AgentMessageReferenceEntity(
    val messageId: String,
    val ordinal: Int,
    val transactionId: String?,
    val noteId: String?,
)

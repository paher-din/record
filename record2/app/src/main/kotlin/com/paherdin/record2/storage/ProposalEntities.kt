package com.paherdin.record2.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "change_proposal",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AgentRunEntity::class, parentColumns = ["id", "sessionId"],
            childColumns = ["originRunId", "sessionId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AgentRunEntity::class, parentColumns = ["id", "sessionId"],
            childColumns = ["lastWriterRunId", "sessionId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "activeSlot"], unique = true),
        Index(value = ["originRunId", "sessionId"]),
        Index(value = ["lastWriterRunId", "sessionId"]),
        Index(value = ["creationRequestId"], unique = true),
    ],
)
internal data class ChangeProposalEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val originRunId: String,
    val lastWriterRunId: String,
    val creationRequestId: String,
    val creationPayloadJson: String,
    val revision: Long,
    val payloadJson: String,
    val payloadDigest: String,
    val payloadFormatVersion: Int,
    val dataGeneration: String,
    val state: String,
    val activeSlot: Int?,
    val createdAt: Long,
    val updatedAt: Long,
)

// Source identities are immutable provenance, not lifecycle foreign keys.
@Entity(tableName = "commit_operation", indices = [Index(value = ["proposalId"], unique = true)])
internal data class CommitOperationEntity(
    @PrimaryKey val id: String,
    val proposalId: String,
    val confirmedRevision: Long,
    val createdGeneration: String,
    val frozenPayloadJson: String,
    val payloadDigest: String,
    val payloadFormatVersion: Int,
    val state: String,
    val createdAt: Long,
    val finishedAt: Long?,
    val resultJson: String?,
    val rejectionCode: String?,
)

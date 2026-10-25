package com.paherdin.record2.storage

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query

internal data class SessionMessageWithRunRow(
    @Embedded val message: AgentMessageEntity,
    val runState: String,
)

internal data class CompletedHistoryRow(val role: String, val body: String)

@Dao
internal interface SessionDao {
    @Insert fun insertSession(row: AgentSessionEntity)
    @Insert fun insertRun(row: AgentRunEntity)
    @Insert fun insertMessage(row: AgentMessageEntity)
    @Insert fun insertReference(row: AgentMessageReferenceEntity)

    @Query("SELECT * FROM agent_session WHERE id = :id")
    fun session(id: String): AgentSessionEntity?

    @Query("SELECT * FROM agent_run WHERE id = :id")
    fun run(id: String): AgentRunEntity?

    @Query("SELECT * FROM agent_message WHERE id = :id")
    fun message(id: String): AgentMessageEntity?

    @Query("SELECT * FROM agent_message WHERE runId = :runId AND role = 'ASSISTANT'")
    fun finalAssistant(runId: String): AgentMessageEntity?

    @Query("SELECT * FROM agent_run WHERE sessionId = :sessionId AND activeSlot = 1")
    fun activeRun(sessionId: String): AgentRunEntity?

    @Query(
        "UPDATE agent_session SET nextMessageSequence = :next, updatedAt = :updatedAt " +
            "WHERE id = :id AND nextMessageSequence = :expected AND nextMessageSequence < 9223372036854775807",
    )
    fun advanceSequence(id: String, expected: Long, next: Long, updatedAt: Long): Int

    @Query(
        "UPDATE agent_session SET nextMessageSequence = :next, historyVersion = :nextHistoryVersion, " +
            "updatedAt = :updatedAt WHERE id = :id AND nextMessageSequence = :expected " +
            "AND historyVersion = :expectedHistoryVersion AND nextMessageSequence < 9223372036854775807 " +
            "AND historyVersion < 9223372036854775807",
    )
    fun advanceHistory(
        id: String, expected: Long, next: Long,
        expectedHistoryVersion: Long, nextHistoryVersion: Long, updatedAt: Long,
    ): Int

    @Query(
        "UPDATE agent_run SET state = 'COMPLETED', finishedAt = :finishedAt, activeSlot = NULL " +
            "WHERE id = :id AND state = 'RUNNING' AND activeSlot = 1 " +
            "AND processOwnerId = :processOwnerId AND dataGeneration = :dataGeneration",
    )
    fun completeRun(id: String, finishedAt: Long, processOwnerId: String, dataGeneration: String): Int

    @Query(
        "UPDATE agent_run SET state = :state, finishedAt = :finishedAt, activeSlot = NULL, " +
            "safeErrorCode = :safeErrorCode WHERE id = :id AND state = 'RUNNING' AND activeSlot = 1 " +
            "AND processOwnerId = :processOwnerId AND :state IN ('FAILED', 'CANCELLED')",
    )
    fun failRun(
        id: String, state: String, finishedAt: Long, safeErrorCode: String, processOwnerId: String,
    ): Int

    @Query(
        "UPDATE agent_run SET state = 'INTERRUPTED', finishedAt = :finishedAt, activeSlot = NULL " +
            "WHERE state = 'RUNNING'",
    )
    fun interruptAllRunning(finishedAt: Long): Int

    // Authorization-change invalidation (E2): same transaction as the ai_control epoch bump.
    // Every RUNNING row holds a strictly older epoch, so scope-by-epoch is redundant here.
    @Query(
        "UPDATE agent_run SET state = 'FAILED', finishedAt = :finishedAt, activeSlot = NULL, " +
            "safeErrorCode = 'AUTHORIZATION' WHERE state = 'RUNNING'",
    )
    fun failAllRunningForAuthorization(finishedAt: Long): Int

    // Session deletion (B0 §8): explicit child-to-parent order; every FK is RESTRICT, so a miss
    // would abort the transaction rather than orphan anything.
    @Query(
        "DELETE FROM agent_message_reference WHERE messageId IN " +
            "(SELECT id FROM agent_message WHERE sessionId = :sessionId)",
    )
    fun deleteReferencesForSession(sessionId: String): Int

    @Query("DELETE FROM agent_message WHERE sessionId = :sessionId")
    fun deleteMessagesForSession(sessionId: String): Int

    @Query("DELETE FROM agent_run WHERE sessionId = :sessionId")
    fun deleteRunsForSession(sessionId: String): Int

    @Query("DELETE FROM agent_session WHERE id = :sessionId")
    fun deleteSessionRow(sessionId: String): Int

    @Query(
        "SELECT m.role AS role, m.body AS body FROM agent_message m " +
            "JOIN agent_run r ON r.id = m.runId AND r.sessionId = m.sessionId " +
            "WHERE m.sessionId = :sessionId AND m.sequence < :beforeSequence AND r.state = 'COMPLETED' " +
            "ORDER BY m.sequence ASC",
    )
    fun completedHistoryBefore(sessionId: String, beforeSequence: Long): List<CompletedHistoryRow>

    @Query(
        "SELECT m.role AS role, m.body AS body FROM agent_message m " +
            "JOIN agent_run r ON r.id = m.runId AND r.sessionId = m.sessionId " +
            "WHERE m.sessionId = :sessionId AND m.sequence < :beforeSequence AND r.state = 'COMPLETED' " +
            "ORDER BY m.sequence DESC LIMIT :limit",
    )
    fun completedHistoryBeforeLimited(
        sessionId: String,
        beforeSequence: Long,
        limit: Int,
    ): List<CompletedHistoryRow>

    @Query(
        "SELECT ref.* FROM agent_message_reference ref WHERE ref.messageId = :messageId " +
            "ORDER BY ref.ordinal ASC",
    )
    fun referencesForMessage(messageId: String): List<AgentMessageReferenceEntity>

    @Query(
        "SELECT m.*, r.state AS runState FROM agent_message m " +
            "JOIN agent_run r ON r.id = m.runId AND r.sessionId = m.sessionId " +
            "WHERE m.sessionId = :sessionId ORDER BY m.sequence ASC",
    )
    fun messagesWithRunState(sessionId: String): List<SessionMessageWithRunRow>

    @Query(
        "SELECT ref.* FROM agent_message_reference ref " +
            "JOIN agent_message m ON m.id = ref.messageId WHERE m.sessionId = :sessionId " +
            "ORDER BY ref.messageId ASC, ref.ordinal ASC",
    )
    fun referencesForSession(sessionId: String): List<AgentMessageReferenceEntity>
}

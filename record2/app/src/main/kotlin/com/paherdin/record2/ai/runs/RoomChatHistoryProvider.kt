package com.paherdin.record2.ai.runs

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import com.paherdin.record2.sessions.AgentHistoryService
import com.paherdin.record2.sessions.CompletedHistoryEntry
import com.paherdin.record2.sessions.SessionRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Koog adapter over the Room-authoritative history (B0 §6). One instance per run, bound to the
 * session and the completed-history boundary fixed by the start transaction, so the current
 * input joins through the run parameter exactly once and later runs never leak in.
 *
 * Koog 1.3.0's ChatMemory merge replaces the whole prompt (including the agent config's system
 * message) with the loaded history whenever it is non-empty, so load() must carry the system
 * message itself and bound the window here — the stock WindowSizePreProcessor would trim the
 * system message away on long histories. The system message is never persisted in Room; it is
 * rebuilt from the run configuration on every load. The database scan itself is bounded by the
 * same budget, so long histories cost neither unbounded reads nor trimmed-authoritative copies.
 *
 * store deliberately keeps nothing: Koog's stored window may already be trimmed, and a cache
 * that survives a crash before the final reply commits is how ghost replies happen. Room is
 * rebuilt every run instead (Spec 5.2.4).
 */
internal class RoomChatHistoryProvider(
    private val history: AgentHistoryService,
    private val sessionId: String,
    private val boundarySequence: Long,
    private val systemPrompt: String,
    private val window: Int,
) : ChatHistoryProvider {
    override suspend fun load(conversationId: String): List<Message> = withContext(Dispatchers.IO) {
        // The window bounds the whole prompt: system + complete history pairs + the current user
        // input (which joins through the run parameter). Budget an even slot count so trimming
        // never splits a USER/ASSISTANT pair, and never let the window start with an assistant
        // whose question was trimmed away.
        val budget = ((window - 2).coerceAtLeast(0)) / 2 * 2
        val entries = history.completedHistory(sessionId, boundarySequence, limit = budget + 1)
            .dropWhile { it.role == SessionRole.ASSISTANT }
            .takeLast(budget)
        buildList(entries.size + 1) {
            add(
                Message.System(
                    parts = listOf(MessagePart.Text(systemPrompt)),
                    metaInfo = RequestMetaInfo.create(KoogClock.System),
                ),
            )
            entries.forEach { add(it.toKoog()) }
        }
    }

    override suspend fun store(conversationId: String, messages: List<Message>) = Unit
}

internal fun CompletedHistoryEntry.toKoog(): Message = when (role) {
    SessionRole.USER -> Message.User(body, RequestMetaInfo.create(KoogClock.System))
    SessionRole.ASSISTANT -> Message.Assistant(
        parts = listOf(MessagePart.Text(body)),
        metaInfo = ResponseMetaInfo.create(KoogClock.System),
    )
}

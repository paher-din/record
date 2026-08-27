package com.paherdin.record.benchmark

import androidx.compose.foundation.lazy.LazyListState
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/** Captures only payloads actually composed for the current selected-day state. */
class TimelineRenderedPayloadRecorder(private val listState: LazyListState) {
    private val composedPayloads = ConcurrentHashMap<Int, String>()

    /** Registered by each composed item's `SideEffect` (render-layer source). */
    fun onItemComposed(index: Int, payload: String) {
        composedPayloads.putIfAbsent(index, payload)
    }

    /** Traverses one selected-day state in order; empty state hashes empty bytes. */
    suspend fun run(totalCount: Int): RenderedTraversalResult {
        require(totalCount in 0..MAX_SELECTED_DAY_ITEMS) {
            "selected-day recorder count $totalCount exceeds $MAX_SELECTED_DAY_ITEMS"
        }
        val lines = ArrayList<String>(totalCount)
        for (index in 0 until totalCount) {
            listState.scrollToItem(index)
            lines.add(awaitComposed(index))
        }
        val bytes = lines.joinToString(separator = "") { it }.toByteArray(Charsets.UTF_8)
        return RenderedTraversalResult(
            capturedCount = lines.size,
            hash = sha256Hex(bytes),
        )
    }

    private suspend fun awaitComposed(index: Int, timeoutMs: Long = 5_000): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            composedPayloads[index]?.let { return it }
            delay(2)
        }
        error("index $index never composed within ${timeoutMs}ms during render traversal")
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val MAX_SELECTED_DAY_ITEMS = 11
    }
}

/** Correctness traversal outcome. */
data class RenderedTraversalResult(
    val capturedCount: Int,
    val hash: String,
)

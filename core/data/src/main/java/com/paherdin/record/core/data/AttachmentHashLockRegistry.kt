package com.paherdin.record.core.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Repository-owned shared keyed registry of reference-counted
 * `kotlinx.coroutines.sync.Mutex` instances (plan Task 5): ingestion,
 * owner-save/link/unlink, and the cleaner all receive the SAME registry from
 * `RecordRepository` so per-SHA-256 serialization is global. Map acquire and
 * release are atomic under an internal lock; entries are removed only when
 * the reference count reaches zero after unlock, so no acquire/remove race
 * can hand one thread an unregistered lock while another still holds it.
 */
internal class AttachmentHashLockRegistry {

    private val guard = Any()
    private val entries = HashMap<String, Entry>()

    private class Entry(val mutex: Mutex) {
        var refCount = 0
    }

    /** Runs `block` under the per-hash lock, releasing the registry entry afterwards. */
    suspend fun <T> withLock(sha256: String, block: suspend () -> T): T {
        val entry = synchronized(guard) {
            entries.getOrPut(sha256) { Entry(Mutex()) }.also { it.refCount++ }
        }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(guard) {
                entry.refCount--
                if (entry.refCount == 0) entries.remove(sha256)
            }
        }
    }

    /** Test seam: the registry must return to zero entries after every release. */
    internal fun size(): Int = synchronized(guard) { entries.size }
}

package com.paherdin.record.core.data.restore

import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult
import java.io.File

/**
 * Process-wide owner of the single live repository identity for each
 * canonical vault root (ADR 0010). Initialization is serialized per process;
 * failed opens are never cached.
 */
internal object VaultSessionRegistry {
    private val lock = Any()
    private val sessions = mutableMapOf<String, RecordRepository>()

    fun open(
        vaultRoot: File,
        initializer: () -> RepositoryResult<RecordRepository>,
    ): RepositoryResult<RecordRepository> = synchronized(lock) {
        val key = vaultRoot.canonicalFile.path
        sessions[key]?.let { return@synchronized RepositoryResult.Success(it) }
        val result = initializer()
        if (result is RepositoryResult.Success) {
            result.value.attachSessionRootKey(key)
            sessions[key] = result.value
        }
        result
    }

    fun releaseAndClose(key: String, repository: RecordRepository, closeResources: () -> Unit) {
        synchronized(lock) {
            if (sessions[key] === repository) sessions.remove(key)
            closeResources()
        }
    }

    /** Test seam: closes and clears every cached session. */
    fun resetForTest() {
        synchronized(lock) {
            sessions.values.forEach { it.close() }
            sessions.clear()
        }
    }
}

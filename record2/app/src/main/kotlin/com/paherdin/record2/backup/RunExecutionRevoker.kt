package com.paherdin.record2.backup

import java.util.concurrent.ConcurrentHashMap

/** Process-local execution revocation for runs whose restore attempt failed before commit. */
internal interface RunExecutionRevoker {
    fun revoke(runIds: Set<String>)
    fun fenceAll()
    fun isRevoked(runId: String): Boolean
    fun restoreCommitted()
}

internal object ProcessRunExecutionRevoker : RunExecutionRevoker {
    private val revoked = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var fenced = false

    override fun revoke(runIds: Set<String>) {
        revoked.addAll(runIds)
    }

    override fun fenceAll() {
        fenced = true
    }

    override fun isRevoked(runId: String): Boolean = fenced || revoked.contains(runId)

    override fun restoreCommitted() {
        fenced = false
    }

    internal fun clearForTest() {
        revoked.clear()
        fenced = false
    }
}

internal interface RestoreMemoryBarrier {
    fun invalidateInMemory()
    fun revokeRuns(runIds: Set<String>)
    fun fenceAllRuns()
    fun restoreCommitted()
}

internal object ProcessRestoreMemoryBarrier : RestoreMemoryBarrier {
    override fun invalidateInMemory() = Unit
    override fun revokeRuns(runIds: Set<String>) = ProcessRunExecutionRevoker.revoke(runIds)
    override fun fenceAllRuns() = ProcessRunExecutionRevoker.fenceAll()
    override fun restoreCommitted() = ProcessRunExecutionRevoker.restoreCommitted()
}

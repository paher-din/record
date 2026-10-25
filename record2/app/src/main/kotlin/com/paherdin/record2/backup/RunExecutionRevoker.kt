package com.paherdin.record2.backup

import java.util.concurrent.ConcurrentHashMap

/** Process-local execution revocation for runs whose restore attempt failed before commit. */
internal interface RunExecutionRevoker {
    fun revoke(runIds: Set<String>)
    fun isRevoked(runId: String): Boolean
}

internal object ProcessRunExecutionRevoker : RunExecutionRevoker {
    private val revoked = ConcurrentHashMap.newKeySet<String>()

    override fun revoke(runIds: Set<String>) {
        revoked.addAll(runIds)
    }

    override fun isRevoked(runId: String): Boolean = revoked.contains(runId)

    internal fun clearForTest() {
        revoked.clear()
    }
}

internal interface RestoreMemoryBarrier {
    fun invalidateInMemory()
    fun revokeRuns(runIds: Set<String>)
}

internal object ProcessRestoreMemoryBarrier : RestoreMemoryBarrier {
    override fun invalidateInMemory() = Unit
    override fun revokeRuns(runIds: Set<String>) = ProcessRunExecutionRevoker.revoke(runIds)
}

package com.paherdin.record2.ai.runs

import com.paherdin.record2.backup.RestoreMemoryBarrier
import com.paherdin.record2.backup.RunExecutionRevoker

/**
 * E2/D1 binding for G1 restore: register the old runs as revoked (late DB/tool writes are
 * rejected) and stop their in-memory work without writing a terminal state. The restore
 * transaction (or the explicit controlled-interruption path after a failed restore) owns the
 * durable result.
 */
internal class OwnerRestoreMemoryBarrier(
    private val owner: AgentRunOwner,
    private val revoker: RunExecutionRevoker,
) : RestoreMemoryBarrier {
    override fun revokeRuns(runIds: Set<String>) {
        revoker.revoke(runIds)
        owner.revokeForRestore(runIds)
    }

    override fun invalidateInMemory() = Unit
}

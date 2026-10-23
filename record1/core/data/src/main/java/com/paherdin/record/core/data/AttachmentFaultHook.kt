package com.paherdin.record.core.data

/**
 * Simulated-fault injection boundaries of the attachment pipeline (plan Task
 * 5/6/7, testing strategy `ATTACHMENT_SOURCE_COPY` and
 * `ATTACHMENT_CONTENT_COMMIT`). Production default is the no-op; tests throw
 * at named boundaries. Every injected point runs the three-scenario protocol:
 * fail once then restart, fail repeatedly without bypassing safety, complete
 * then repeat.
 */
internal interface AttachmentFaultHook {
    fun beforeTempCreate() {}
    fun beforeSourceRead(chunkOrdinal: Int) {}
    fun afterCopyChunk(chunkOrdinal: Int, bytesCopied: Long, totalBytes: Long) {}
    fun afterFinalWriteBeforeClose() {}
    fun beforeDestinationFlush() {}
    fun afterCloseBeforeValidation() {}
    fun beforeAtomicMove() {}
    fun afterAtomicMove() {}
    fun beforeMetadataWrite() {}
    fun beforeLinkCommit() {}
    fun afterChildWrite(ordinal: Int) {}
    fun beforeUnlinkCommit() {}
    fun beforePhysicalDelete() {}
    fun afterPhysicalDelete() {}
    fun beforeMetadataDelete() {}
    fun afterCommitResponse() {}

    companion object {
        val NO_OP: AttachmentFaultHook = object : AttachmentFaultHook {}
    }
}

package com.paherdin.record.core.data.restore

import com.paherdin.record.core.data.AttachmentCleaner
import com.paherdin.record.core.data.AttachmentContentStore
import com.paherdin.record.core.data.AttachmentIo
import com.paherdin.record.core.data.AttachmentIngestor
import com.paherdin.record.core.data.AttachmentLinkManager
import com.paherdin.record.core.data.room.RecordDao
import com.paherdin.record.core.data.room.RecordDatabase

/**
 * One immutable generation-scoped bundle (spec 4.5, ADR 0010, plan Task 8):
 * generation id/path, the Room database/DAO, the attachment store and the
 * pipeline components that use that vault. The repository atomically swaps its
 * binding reference under the exclusive operation gate, so callers never retain
 * a repository whose DAO or file collaborators target a generation different
 * from the durable active pointer. The per-hash lock registry is repository
 * scope (shared across bindings) so cleanup/linking stay serialized across a
 * handoff.
 */
internal class VaultBinding(
    val generationId: String,
    val database: RecordDatabase,
    val attachmentIo: AttachmentIo,
    val attachmentStore: AttachmentContentStore,
    val attachmentIngestor: AttachmentIngestor?,
    val attachmentLinkManager: AttachmentLinkManager?,
    val attachmentCleaner: AttachmentCleaner?,
) {
    fun dao(): RecordDao = database.recordDao()

    fun close() {
        database.close()
    }
}

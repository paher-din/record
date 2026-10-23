package com.paherdin.record.core.data.restore

import com.paherdin.record.core.data.RestoreViolation

/**
 * Record-level restore failure categories and their deterministic mapping to
 * `RestoreViolation` (plan Task 2/7). The staging validator produces a
 * `RecordLevelCategory`; the classification is total and first-match. Pure
 * Kotlin; Room/SQLite-touching validators live device-side and call into these
 * categories.
 */
internal sealed class RecordLevelCategory {
    internal data object ForeignKey : RecordLevelCategory()
    internal data object Xor : RecordLevelCategory()
    internal data object Finance : RecordLevelCategory()
    internal data object Balance : RecordLevelCategory()
    internal data object Endpoint : RecordLevelCategory()
    internal data object Catalog : RecordLevelCategory()
    internal data object AttachmentDuplicateIdentity : RecordLevelCategory()
    internal data object AttachmentLinkDangling : RecordLevelCategory()
    internal data object AttachmentMissing : RecordLevelCategory()
    internal data object AttachmentExtra : RecordLevelCategory()
    internal data object AttachmentCorrupt : RecordLevelCategory()
}

internal object RestoreClassification {

    /** Total mapping; every category yields exactly one violation. */
    fun fromCategory(c: RecordLevelCategory): RestoreViolation = when (c) {
        RecordLevelCategory.ForeignKey -> RestoreViolation.FOREIGN_KEY_INVALID
        RecordLevelCategory.Xor -> RestoreViolation.XOR_INVALID
        RecordLevelCategory.Finance -> RestoreViolation.FINANCE_INVALID
        RecordLevelCategory.Balance -> RestoreViolation.BALANCE_INVALID
        RecordLevelCategory.Endpoint -> RestoreViolation.ENDPOINT_INVALID
        RecordLevelCategory.Catalog -> RestoreViolation.CATALOG_INVALID
        RecordLevelCategory.AttachmentDuplicateIdentity -> RestoreViolation.ATTACHMENT_DUPLICATE_IDENTITY
        RecordLevelCategory.AttachmentLinkDangling -> RestoreViolation.ATTACHMENT_LINK_DANGLING
        RecordLevelCategory.AttachmentMissing -> RestoreViolation.ATTACHMENT_MISSING
        RecordLevelCategory.AttachmentExtra -> RestoreViolation.ATTACHMENT_EXTRA
        RecordLevelCategory.AttachmentCorrupt -> RestoreViolation.ATTACHMENT_CORRUPT
    }
}

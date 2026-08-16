package com.paherdin.record.core.data

import com.paherdin.record.core.data.restore.RecordLevelCategory
import com.paherdin.record.core.data.restore.RestoreClassification
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreViolationClassificationTest {

    @Test
    fun everyCategoryMapsTotallyToOneViolation() {
        val expected = mapOf(
            RecordLevelCategory.ForeignKey to RestoreViolation.FOREIGN_KEY_INVALID,
            RecordLevelCategory.Xor to RestoreViolation.XOR_INVALID,
            RecordLevelCategory.Finance to RestoreViolation.FINANCE_INVALID,
            RecordLevelCategory.Balance to RestoreViolation.BALANCE_INVALID,
            RecordLevelCategory.Endpoint to RestoreViolation.ENDPOINT_INVALID,
            RecordLevelCategory.Catalog to RestoreViolation.CATALOG_INVALID,
            RecordLevelCategory.AttachmentDuplicateIdentity to RestoreViolation.ATTACHMENT_DUPLICATE_IDENTITY,
            RecordLevelCategory.AttachmentLinkDangling to RestoreViolation.ATTACHMENT_LINK_DANGLING,
            RecordLevelCategory.AttachmentMissing to RestoreViolation.ATTACHMENT_MISSING,
            RecordLevelCategory.AttachmentExtra to RestoreViolation.ATTACHMENT_EXTRA,
            RecordLevelCategory.AttachmentCorrupt to RestoreViolation.ATTACHMENT_CORRUPT,
        )
        // `when` without `else` makes the mapping exhaustive by construction; assert every pairing
        // and that no two categories collapse to one violation.
        expected.forEach { (category, violation) -> assertEquals(violation, RestoreClassification.fromCategory(category)) }
        assertEquals(expected.size, expected.values.toSet().size)
    }

    @Test
    fun catalogViolationIsDistinctFromAttachmentViolations() {
        val catalog = RestoreClassification.fromCategory(RecordLevelCategory.Catalog)
        val attachment = RestoreClassification.fromCategory(RecordLevelCategory.AttachmentCorrupt)
        assert(catalog != attachment)
    }
}

package com.paherdin.record.core.data

import com.paherdin.record.core.data.restore.CatalogContractChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogContractCheckerTest {

    private val supported = setOf("EntityRef", "Account", "Transaction", "AccountEntry", "Note", "TransactionNoteAssociation", "Attachment", "AttachmentLink", "AttachmentContent")

    @Test
    fun supportedLogicalTypePasses() {
        assertNull(CatalogContractChecker.checkLogicalType("EntityRef", supported))
    }

    @Test
    fun unknownLogicalTypeRejects() {
        assertEquals(RestoreViolation.UNKNOWN_LOGICAL_TYPE, CatalogContractChecker.checkLogicalType("Category", supported))
    }

    @Test
    fun supportedCatalogVersionsPass() {
        assertNull(CatalogContractChecker.checkManifestVersions(listOf(1), setOf(1)))
    }

    @Test
    fun unsupportedCatalogVersionRejects() {
        assertEquals(RestoreViolation.NAME_KEY_VERSION_UNSUPPORTED, CatalogContractChecker.checkManifestVersions(listOf(2), setOf(1)))
    }

    @Test
    fun emptyManifestWithNoEncounteredVersionsMatches() {
        assertNull(CatalogContractChecker.checkEncounteredVersions(emptyList(), emptySet()))
    }

    @Test
    fun omittedEncounteredVersionRejects() {
        assertEquals(RestoreViolation.NAME_KEY_VERSION_UNSUPPORTED, CatalogContractChecker.checkEncounteredVersions(listOf(1), emptySet()))
    }

    @Test
    fun extraEncounteredVersionRejects() {
        assertEquals(RestoreViolation.NAME_KEY_VERSION_UNSUPPORTED, CatalogContractChecker.checkEncounteredVersions(emptyList(), setOf(1)))
    }

    @Test
    fun matchingEncounteredSetPasses() {
        assertNull(CatalogContractChecker.checkEncounteredVersions(listOf(1, 1), setOf(1)))
    }
}

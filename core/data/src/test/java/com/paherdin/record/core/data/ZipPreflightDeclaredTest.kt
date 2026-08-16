package com.paherdin.record.core.data

import com.paherdin.record.core.data.backup.LogicalType
import com.paherdin.record.core.data.restore.DeclaredEntry
import com.paherdin.record.core.data.restore.DeclaredInput
import com.paherdin.record.core.data.restore.ZipPreflightDeclared
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZipPreflightDeclaredTest {

    private val supportedTypes = LogicalType.entries.map { it.name }.toSet() + "AttachmentContent"
    private val singletonTypes = LogicalType.entries.map { it.name }.toSet()

    private fun entry(
        logicalType: String = "EntityRef",
        path: String = "EntityRef.jsonl",
        isAttachment: Boolean = false,
        declaredSize: Long = 10,
        uncompressed: Long = 10,
        compressed: Long = uncompressed,
    ) = DeclaredEntry(logicalType, path, isAttachment, declaredSize, uncompressed, compressed)

    private fun input(
        entries: List<DeclaredEntry> = listOf(entry()),
        formatVersion: Int = 1,
        space: Long = 1L * 1024 * 1024 * 1024 * 1024,
        catalogVersions: List<Int> = emptyList(),
        totalZipEntries: Long = entries.size.toLong(),
    ) = DeclaredInput(
        formatVersion = formatVersion,
        catalogNameKeyVersions = catalogVersions,
        supportedCatalogVersions = setOf(1),
        supportedLogicalTypes = supportedTypes,
        singletonLogicalTypes = singletonTypes,
        entries = entries,
        totalZipEntries = totalZipEntries,
        availableAppPrivateSpace = space,
    )

    @Test
    fun validPackagePasses() {
        assertNull(ZipPreflightDeclared.classify(input()))
    }

    @Test
    fun unsupportedFormatFails() {
        assertEquals(RestoreViolation.FORMAT_UNSUPPORTED, ZipPreflightDeclared.classify(input(formatVersion = 0)))
    }

    @Test
    fun unsupportedCatalogVersionFails() {
        assertEquals(
            RestoreViolation.NAME_KEY_VERSION_UNSUPPORTED,
            ZipPreflightDeclared.classify(input(catalogVersions = listOf(2))),
        )
    }

    @Test
    fun unknownLogicalTypeFails() {
        assertEquals(
            RestoreViolation.UNKNOWN_LOGICAL_TYPE,
            ZipPreflightDeclared.classify(input(listOf(entry(logicalType = "Bogus")))),
        )
    }

    @Test
    fun duplicateSingletonFails() {
        assertEquals(
            RestoreViolation.SINGLETON_DUPLICATE,
            ZipPreflightDeclared.classify(input(listOf(entry(), entry(path = "EntityRef2.jsonl")))),
        )
    }

    @Test
    fun attachmentContentIsNotSingleton() {
        assertNull(
            ZipPreflightDeclared.classify(
                input(listOf(entry(logicalType = "AttachmentContent", path = "attachments/a", isAttachment = true))),
            ),
        )
    }

    // ---- paths ----

    @Test
    fun traversalPathFails() {
        assertEquals(RestoreViolation.PATH_INVALID, ZipPreflightDeclared.classify(input(listOf(entry(path = "../evil")))))
    }

    @Test
    fun absolutePathFails() {
        assertEquals(RestoreViolation.PATH_INVALID, ZipPreflightDeclared.classify(input(listOf(entry(path = "/evil")))))
        assertEquals(RestoreViolation.PATH_INVALID, ZipPreflightDeclared.classify(input(listOf(entry(path = "C:/evil")))))
    }

    @Test
    fun duplicateNormalizedPathFails() {
        assertEquals(
            RestoreViolation.PATH_DUPLICATE,
            ZipPreflightDeclared.classify(
                input(listOf(entry(logicalType = "AttachmentContent", path = "a//b", isAttachment = true), entry(logicalType = "AttachmentContent", path = "a/b", isAttachment = true))),
            ),
        )
    }

    @Test
    fun pathAt240BytesPassesAnd241Fails() {
        val ok = "a".repeat(240)
        assertNull(ZipPreflightDeclared.classify(input(listOf(entry(path = ok)))))
        val over = "a".repeat(241)
        assertEquals(RestoreViolation.PATH_TOO_LONG, ZipPreflightDeclared.classify(input(listOf(entry(path = over)))))
    }

    // ---- entry count ----

    @Test
    fun entryCountAtLimitPassesAndOneOverFails() {
        val many = (0 until 100_000).map { entry(logicalType = "AttachmentContent", path = "attachments/f$it", isAttachment = true) }
        assertNull(ZipPreflightDeclared.classify(input(many)))
        val over = many + entry(logicalType = "AttachmentContent", path = "attachments/extra", isAttachment = true)
        assertEquals(RestoreViolation.ENTRY_COUNT_LIMIT, ZipPreflightDeclared.classify(input(over)))
    }

    // ---- per-entry size ----

    @Test
    fun attachmentSizeAtLimitPassesAndOneOverFails() {
        val at = ZipPreflightDeclared.MAX_ATTACHMENT_SIZE
        assertNull(ZipPreflightDeclared.classify(input(listOf(entry(logicalType = "AttachmentContent", path = "a", isAttachment = true, declaredSize = at, uncompressed = at)))))
        assertEquals(
            RestoreViolation.ENTRY_SIZE_LIMIT,
            ZipPreflightDeclared.classify(input(listOf(entry(logicalType = "AttachmentContent", path = "a", isAttachment = true, declaredSize = at + 1, uncompressed = at + 1)))),
        )
    }

    @Test
    fun structuredSizeAtLimitPassesAndOneOverFails() {
        val at = ZipPreflightDeclared.MAX_STRUCTURED_SIZE
        assertNull(ZipPreflightDeclared.classify(input(listOf(entry(declaredSize = at, uncompressed = at)))))
        assertEquals(
            RestoreViolation.ENTRY_SIZE_LIMIT,
            ZipPreflightDeclared.classify(input(listOf(entry(declaredSize = at + 1, uncompressed = at + 1)))),
        )
    }

    // ---- aggregate and space ----

    @Test
    fun aggregateOverSpaceDerivedBudgetFails() {
        // Nine attachment entries each exactly at the 512 MiB per-entry limit sum over the 4 GiB budget.
        val at = ZipPreflightDeclared.MAX_ATTACHMENT_SIZE
        val nine = (0 until 9).map { entry(logicalType = "AttachmentContent", path = "a$it", isAttachment = true, declaredSize = at, uncompressed = at) }
        assertEquals(RestoreViolation.AGGREGATE_SIZE_LIMIT, ZipPreflightDeclared.classify(input(nine, space = 5L * 1024 * 1024 * 1024)))
    }

    @Test
    fun nonPositiveSpaceBudgetFails() {
        assertEquals(RestoreViolation.SPACE_BUDGET, ZipPreflightDeclared.classify(input(space = 1L * 1024 * 1024 * 1024)))
    }

    // ---- compression ratio ----

    @Test
    fun ratioAtLimitPassesAndAboveFails() {
        assertNull(ZipPreflightDeclared.classify(input(listOf(entry(uncompressed = 200, compressed = 1)))))
        assertEquals(
            RestoreViolation.COMPRESSION_RATIO_LIMIT,
            ZipPreflightDeclared.classify(input(listOf(entry(uncompressed = 201, compressed = 1)))),
        )
    }

    @Test
    fun nonEmptyPayloadWithZeroCompressedDenominatorFails() {
        assertEquals(
            RestoreViolation.COMPRESSION_RATIO_LIMIT,
            ZipPreflightDeclared.classify(input(listOf(entry(uncompressed = 10, compressed = 0)))),
        )
    }

    @Test
    fun zeroByteEntryIsAllowed() {
        assertNull(ZipPreflightDeclared.classify(input(listOf(entry(declaredSize = 0, uncompressed = 0, compressed = 0)))))
    }

    // ---- precedence: earlier rules win ----

    @Test
    fun formatPrecedesUnknownType() {
        val e = entry(logicalType = "Bogus")
        assertEquals(RestoreViolation.FORMAT_UNSUPPORTED, ZipPreflightDeclared.classify(input(listOf(e), formatVersion = 0)))
    }

    @Test
    fun unknownTypePrecedesEntryCount() {
        val many = (0 until 100_001).map { entry(path = "f$it.json", logicalType = "Bogus") }
        assertEquals(RestoreViolation.UNKNOWN_LOGICAL_TYPE, ZipPreflightDeclared.classify(input(many)))
    }
}

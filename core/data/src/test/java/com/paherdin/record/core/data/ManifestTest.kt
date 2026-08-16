package com.paherdin.record.core.data

import com.paherdin.record.core.data.backup.Manifest
import com.paherdin.record.core.data.backup.ManifestCodec
import com.paherdin.record.core.data.backup.ManifestEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestTest {

    private fun sample() = Manifest(
        formatVersion = ManifestCodec.FORMAT_VERSION,
        databaseSchemaVersion = 2,
        appVersion = "0.1.0 (1)",
        createdAt = 123,
        backupId = "uuid-1",
        hashAlgorithm = ManifestCodec.HASH_ALGORITHM,
        catalogNameKeyVersions = emptyList(),
        entries = listOf(
            ManifestEntry("EntityRef", "EntityRef.jsonl", 5, "a".repeat(64), 5),
            ManifestEntry("AttachmentContent", "attachments/abc", 456, "b".repeat(64), null),
        ),
    )

    @Test
    fun roundTripPreservesEveryField() {
        val m = sample()
        assertEquals(m, ManifestCodec.decode(ManifestCodec.encode(m)))
    }

    @Test
    fun emptyCatalogVersionsRoundTrip() {
        val m = sample()
        assertEquals(emptyList<Int>(), ManifestCodec.decode(ManifestCodec.encode(m)).catalogNameKeyVersions)
    }

    @Test
    fun nonEmptyCatalogVersionsRoundTrip() {
        val m = sample().copy(catalogNameKeyVersions = listOf(1, 1))
        assertEquals(listOf(1, 1), ManifestCodec.decode(ManifestCodec.encode(m)).catalogNameKeyVersions)
    }

    @Test
    fun attachmentDescriptorHasNoRecordCount() {
        val m = sample()
        val decoded = ManifestCodec.decode(ManifestCodec.encode(m))
        assertEquals(null, decoded.entries.first { it.logicalType == "AttachmentContent" }.recordCount)
        assertEquals(5L, decoded.entries.first { it.logicalType == "EntityRef" }.recordCount)
    }

    @Test(expected = JsonParseException::class)
    fun missingEntriesIsRejected() {
        ManifestCodec.decode("""{"formatVersion":1,"databaseSchemaVersion":2,"appVersion":"a","createdAt":1,"backupId":"b","hashAlgorithm":"SHA-256","catalogNameKeyVersions":[]}""")
    }

    @Test(expected = JsonParseException::class)
    fun wrongEntryTypeIsRejected() {
        ManifestCodec.decode("""{"formatVersion":1,"catalogNameKeyVersions":[],"entries":[{"logicalType":1}]}""")
    }

    @Test(expected = JsonParseException::class)
    fun duplicateManifestKeyIsRejected() {
        ManifestCodec.decode("""{"formatVersion":1,"formatVersion":2,"catalogNameKeyVersions":[],"entries":[]}""")
    }
}

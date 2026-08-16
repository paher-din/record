package com.paherdin.record.core.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.RestoreViolation
import com.paherdin.record.core.data.restore.PreflightResult
import com.paherdin.record.core.data.restore.RestorePreflight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MaliciousZipRestoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "vaults").deleteRecursively()
        File(context.filesDir, "recovery").deleteRecursively()
    }

    private fun newSource(): File = File(context.cacheDir, "malicious-${System.nanoTime()}.zip")

    private fun run(source: File, space: Long = 1L * 1024 * 1024 * 1024 * 1024): PreflightResult =
        RestorePreflight(source, space).run()

    private fun baseEntries(): List<Pair<String, ByteArray>> = listOf(
        "EntityRef.jsonl" to "{\"a\":1}\n{\"a\":2}\n".toByteArray(),
        "Transaction.jsonl" to "{\"b\":1}\n".toByteArray(),
    )

    @Test
    fun validPackagePassesPreflight() {
        val source = MaliciousZipGenerator.build(newSource(), baseEntries())
        val result = run(source)
        assertTrue("valid package must be accepted, got $result", result is PreflightResult.Accepted)
    }

    @Test
    fun rejectsAfterAndUnknownTypes() {
        val bad = MaliciousZipGenerator.build(newSource(), baseEntries(), formatVersion = 2)
        assertEquals(RestoreViolation.FORMAT_UNSUPPORTED, (run(bad) as PreflightResult.Rejected).violation)

        val unknown = MaliciousZipGenerator.build(newSource(), listOf("Category.jsonl" to "{\"c\":1}\n".toByteArray()))
        assertEquals(RestoreViolation.UNKNOWN_LOGICAL_TYPE, (run(unknown) as PreflightResult.Rejected).violation)
    }

    @Test
    fun rejectsPathViolations() {
        // A traversal-named entry is rejected; some Android ZipFile builds
        // sanitize traversal entries so the exact violation may be PATH_INVALID
        // or MANIFEST_INVALID — the safety property is rejection. The precise
        // PATH_INVALID decision table is JVM-tested (ZipPreflightDeclaredTest).
        val traversal = MaliciousZipGenerator.buildWithManifest(
            newSource(),
            listOf("../evil.jsonl" to "x\n".toByteArray()),
            manifestWith("EntityRef", "../evil.jsonl", 2, "e".repeat(64)),
        )
        assertTrue("traversal input must be rejected, got ${run(traversal)}", run(traversal) is PreflightResult.Rejected)

        val overlongName = "a".repeat(241) + ".jsonl"
        val overlong = MaliciousZipGenerator.buildWithManifest(
            newSource(),
            listOf(overlongName to "x\n".toByteArray()),
            manifestWith("EntityRef", overlongName, 2, "e".repeat(64)),
        )
        assertEquals(RestoreViolation.PATH_TOO_LONG, (run(overlong) as PreflightResult.Rejected).violation)

        val dup = MaliciousZipGenerator.buildWithManifest(
            newSource(),
            listOf(
                "a.jsonl" to "x\n".toByteArray(),
                "b.jsonl" to "y\n".toByteArray(),
            ),
            "{" +
                "\"formatVersion\":1,\"databaseSchemaVersion\":2,\"appVersion\":\"t\",\"createdAt\":1," +
                "\"backupId\":\"d\",\"hashAlgorithm\":\"SHA-256\",\"catalogNameKeyVersions\":[]," +
                "\"entries\":[" +
                "{\"logicalType\":\"EntityRef\",\"path\":\"a.jsonl\",\"size\":4,\"sha256\":\"${"a".repeat(64)}\",\"recordCount\":1}," +
                "{\"logicalType\":\"EntityRef\",\"path\":\"b.jsonl\",\"size\":4,\"sha256\":\"${"b".repeat(64)}\",\"recordCount\":1}" +
                "]}" ,
        )
        assertEquals(RestoreViolation.SINGLETON_DUPLICATE, (run(dup) as PreflightResult.Rejected).violation)
    }

    @Test
    fun rejectsDeclaredSizeOverLimit() {
        // An attachment entry whose manifest-declared size is 512 MiB + 1; the
        // declared pass uses the manifest size, so no 512 MiB payload is needed.
        val big = MaliciousZipGenerator.build(
            newSource(),
            listOf("attachments/abc" to "tiny".toByteArray()),
            declaredSizes = mapOf("attachments/abc" to (512L * 1024 * 1024 + 1)),
        )
        assertEquals(RestoreViolation.ENTRY_SIZE_LIMIT, (run(big) as PreflightResult.Rejected).violation)
    }

    @Test
    fun rejectsRatioOverLimit() {
        // 200,000 zero bytes compress to a few hundred -> ratio far over 200:1.
        val compressible = MaliciousZipGenerator.build(
            newSource(),
            listOf("EntityRef.jsonl" to ByteArray(200_000)),
        )
        assertEquals(RestoreViolation.COMPRESSION_RATIO_LIMIT, (run(compressible) as PreflightResult.Rejected).violation)
    }

    @Test
    fun rejectsAggregateOverSpaceBudget() {
        val source = MaliciousZipGenerator.build(newSource(), baseEntries())
        val result = run(source, space = 1L * 1024 * 1024 * 1024) // budget = 0
        assertEquals(RestoreViolation.SPACE_BUDGET, (result as PreflightResult.Rejected).violation)
    }

    @Test
    fun rejectsDeclaredBelowActualInStreamingPass() {
        val source = MaliciousZipGenerator.build(
            newSource(),
            baseEntries(),
            declaredSizes = mapOf("EntityRef.jsonl" to 5L), // real bytes are 14
        )
        assertEquals(RestoreViolation.SIZE_UNDER_DECLARED, (run(source) as PreflightResult.Rejected).violation)
    }

    @Test
    fun rejectsEntryCountOverLimit() {
        // AttachmentContent is the only non-singleton logical type, so 100,001
        // distinct attachment entries reach the total-entry-count limit.
        val many = (0 until 100_001).map { "attachments/f$it" to ByteArray(0) }
        val source = MaliciousZipGenerator.build(newSource(), many)
        assertEquals(RestoreViolation.ENTRY_COUNT_LIMIT, (run(source) as PreflightResult.Rejected).violation)
    }

    @Test
    fun preflightNeverTouchesTheVault() {
        val source = MaliciousZipGenerator.build(newSource(), baseEntries())
        // Rejection and acceptance both leave the vault and record untouched.
        run(source)
        run(MaliciousZipGenerator.build(newSource(), baseEntries(), formatVersion = 2))
        assertTrue(File(context.filesDir, "vaults").listFiles()?.isEmpty() ?: true)
        assertTrue(!File(context.filesDir, "recovery/activation-recovery-v1.json").exists())
    }

    private fun manifestWith(logicalType: String, path: String, size: Long, hash: String): String =
        "{" +
            "\"formatVersion\":1,\"databaseSchemaVersion\":2,\"appVersion\":\"t\",\"createdAt\":1," +
            "\"backupId\":\"m\",\"hashAlgorithm\":\"SHA-256\",\"catalogNameKeyVersions\":[]," +
            "\"entries\":[{\"logicalType\":\"$logicalType\",\"path\":\"$path\",\"size\":$size,\"sha256\":\"$hash\",\"recordCount\":1}]}"
}

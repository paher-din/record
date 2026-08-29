package com.paherdin.record.core.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Tech PoC P4 (plan Task 4): the frozen `manifest-v1` — exact 30-query order,
 * expected-source emitter under `build/p4/`, and frozen-artifact hash equality.
 */
class QueryManifestTest {

    private companion object {
        const val FROZEN_MANIFEST_V1_SHA256 =
            "14b381f8bf2a0cec42b12691cf163edce15d66e841d8b94b75bdb5f99a11efb1"
    }

    @Test
    fun manifestHasExactlyThirtyQueriesInFrozenOrder() {
        val q = QueryManifest.queries
        assertEquals(30, q.size)
        assertEquals("q01", q.first().id)
        assertEquals("q30", q.last().id)
        assertEquals((1..30).map { "q%02d".format(it) }, q.map { it.id })
    }

    @Test
    fun caseSensitiveDedupKeepsThirtyRawQueries() {
        // The 30 raw query texts remain 30 distinct strings when deduplicated
        // case-sensitively (EXPENSE and expense differ as raw text).
        assertEquals(30, QueryManifest.queries.map { it.text }.toSet().size)
    }

    @Test
    fun emitExpectedManifestV1() {
        // Generates the canonical Kotlin source of ExpectedManifestV1.kt under
        // build/p4/ for review, then apply_patch moves it to the fixture path.
        val corpus = CorpusGenerator.generate()
        val sb = StringBuilder()
        sb.append("package com.paherdin.record.core.data.search\n\n")
        sb.append("/**\n")
        sb.append(" * Tech PoC P4 — generated-once frozen artifact (plan Task 4): the exact\n")
        sb.append(" * ordered expected first-page entityRefId list for every manifest-v1 query,\n")
        sb.append(" * computed by the independent reference oracle. Do not edit by hand;\n")
        sb.append(" * regenerate only with an explicit plan revision.\n")
        sb.append(" */\n")
        sb.append("object ExpectedManifestV1 {\n")
        sb.append("    data class ExpectedFirstPage(val id: String, val ids: List<String>)\n\n")
        sb.append("    val firstPageIds: List<ExpectedFirstPage> = listOf(\n")
        for (mq in QueryManifest.queries) {
            val ids = ReferenceOracle.expectedFirstPage(corpus, mq.text)
            sb.append("        ExpectedFirstPage(\"${mq.id}\", listOf(\n")
            ids.forEach { id ->
                sb.append("            \"$id\",\n")
            }
            sb.append("        )),\n")
        }
        sb.append("    )\n")
        sb.append("}\n")
        // The Gradle test working directory is the module dir (:core:data),
        // so "build/p4" resolves to the project-relative core/data/build/p4.
        val targetDir = File(System.getProperty("user.dir"), "build/p4")
        targetDir.mkdirs()
        val target = File(targetDir, "ExpectedManifestV1.kt")
        target.writeText(sb.toString(), Charsets.UTF_8)
        println("Wrote ${target.absolutePath} (${sb.length} chars)")
    }

    @Test
    fun frozenArtifactHashCanonicalizesCheckoutLineEndings() {
        val lfSource = "package example\nval label = \"清单-v1\"\n"
        val crlfSource = lfSource.replace("\n", "\r\n")
        val crSource = lfSource.replace("\n", "\r")

        val lfHashInput = canonicalUtf8BytesForFrozenHash(lfSource.toByteArray(Charsets.UTF_8))
        val crlfHashInput = canonicalUtf8BytesForFrozenHash(crlfSource.toByteArray(Charsets.UTF_8))
        val crHashInput = canonicalUtf8BytesForFrozenHash(crSource.toByteArray(Charsets.UTF_8))
        assertTrue(lfHashInput.contentEquals(crlfHashInput))
        assertTrue(lfHashInput.contentEquals(crHashInput))

        val lfSha = MessageDigest.getInstance("SHA-256").digest(lfHashInput)
        val crlfSha = MessageDigest.getInstance("SHA-256").digest(crlfHashInput)
        val crSha = MessageDigest.getInstance("SHA-256").digest(crHashInput)
        assertTrue(lfSha.contentEquals(crlfSha))
        assertTrue(lfSha.contentEquals(crSha))
    }

    @Test
    fun frozenArtifactHashIsStable() {
        // The committed artifact hash is recorded in verification.md (Task 4);
        // this test pins the artifact as the comparison oracle for freshness.
        val source = File(
            System.getProperty("user.dir"),
            "src/testFixtures/kotlin/com/paherdin/record/core/data/search/ExpectedManifestV1.kt",
        )
        assertTrue("committed ExpectedManifestV1.kt exists", source.isFile)
        val hashInput = canonicalUtf8BytesForFrozenHash(source.readBytes())
        val sha = MessageDigest.getInstance("SHA-256").digest(hashInput)
            .joinToString("") { "%02x".format(it) }
        println("ExpectedManifestV1.kt SHA-256: $sha")
        assertEquals(FROZEN_MANIFEST_V1_SHA256, sha)
    }

    private fun canonicalUtf8BytesForFrozenHash(sourceBytes: ByteArray): ByteArray =
        sourceBytes.toString(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .toByteArray(Charsets.UTF_8)
}

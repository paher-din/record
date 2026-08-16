package com.paherdin.record.core.data

import com.paherdin.record.core.data.restore.StagingReceipt
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingReceiptTest {

    private fun receipt() = StagingReceipt(
        candidateId = "gen-00000000-0000-0000-0000-000000000001",
        sourcePackageSha256 = "a".repeat(64),
        manifestSha256 = "b".repeat(64),
        entryHashes = mapOf("EntityRef.jsonl" to "c".repeat(64)),
        recordCounts = mapOf("EntityRef" to 5L),
        attachmentCount = 1,
        validationContractVersion = 1,
        candidateDigest = "d".repeat(64),
    )

    @Test
    fun canonicalUtf8IsDeterministic() {
        assertTrue(receipt().canonicalUtf8().contentEquals(receipt().canonicalUtf8()))
    }

    @Test
    fun digestIsDeterministic() {
        assertTrue(receipt().digest() == receipt().digest())
    }

    @Test
    fun digestChangesForEveryBoundField() {
        val base = receipt().digest()
        assertNotEquals(base, receipt().copy(candidateId = "gen-00000000-0000-0000-0000-000000000002").digest())
        assertNotEquals(base, receipt().copy(sourcePackageSha256 = "e".repeat(64)).digest())
        assertNotEquals(base, receipt().copy(manifestSha256 = "f".repeat(64)).digest())
        assertNotEquals(base, receipt().copy(entryHashes = mapOf("Note.jsonl" to "g".repeat(64))).digest())
        assertNotEquals(base, receipt().copy(recordCounts = mapOf("EntityRef" to 6L)).digest())
        assertNotEquals(base, receipt().copy(attachmentCount = 2).digest())
        assertNotEquals(base, receipt().copy(validationContractVersion = 2).digest())
        assertNotEquals(base, receipt().copy(candidateDigest = "h".repeat(64)).digest())
    }

    @Test
    fun entryAndCountOrderingIsStable() {
        val sorted = receipt().copy(
            entryHashes = mapOf("b.jsonl" to "x", "a.jsonl" to "y"),
            recordCounts = mapOf("z" to 2L, "a" to 1L),
        )
        val sortedAgain = receipt().copy(
            entryHashes = mapOf("a.jsonl" to "y", "b.jsonl" to "x"),
            recordCounts = mapOf("a" to 1L, "z" to 2L),
        )
        // Insertion order of the maps differs but the canonical form sorts; digests must match.
        assertTrue(sorted.digest() == sortedAgain.digest())
    }
}

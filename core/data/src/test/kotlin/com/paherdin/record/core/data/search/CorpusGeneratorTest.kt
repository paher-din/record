package com.paherdin.record.core.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

/**
 * Tech PoC P4 (plan Task 3): `corpus-v1` determinism and invariants —
 * seed, count, type/language mix, deterministic UUID identity without type
 * prefixes, three-way `occurredAt` ties, boundary records, zero-hit
 * exclusion, canonical serialization, and repeatable SHA-256.
 */
class CorpusGeneratorTest {

    private companion object {
        const val FROZEN_CORPUS_V1_SHA256 =
            "2f27b8754f2c3beaa96b0a1eed3ed5e06ecec917f465392eea2897086cbeb5e9"
    }

    @Test
    fun seedIsFrozenAndCorpusHasExactlyTenThousandRecords() {
        assertEquals(2026080804L, CorpusGenerator.seed)
        val corpus = CorpusGenerator.generate()
        assertEquals(10_000, corpus.size)
    }

    @Test
    fun typeMixIsSeventyThirtyTransactionNote() {
        val corpus = CorpusGenerator.generate()
        corpus.forEachIndexed { i, r ->
            val expected = if (i < 7000) "Transaction" else "Note"
            assertEquals("type of record $i", expected, r.type)
        }
    }

    @Test
    fun languageMixIsExactlyFiftyFiveThirtyFifteen() {
        val corpus = CorpusGenerator.generate()
        val chinese = corpus.count { it.language == Language.CHINESE }
        val english = corpus.count { it.language == Language.ENGLISH }
        val mixed = corpus.count { it.language == Language.MIXED }
        assertEquals(5500, chinese)
        assertEquals(3000, english)
        assertEquals(1500, mixed)
    }

    @Test
    fun entityRefIdsAreDeterministicUuidsWithoutTypePrefixes() {
        val corpus = CorpusGenerator.generate()
        val ids = corpus.map { it.entityRefId }
        assertEquals(10_000, ids.toSet().size)
        ids.forEach { id ->
            val uuid = UUID.fromString(id) // throws on malformed syntax
            // nameUUIDFromBytes is a name-based (MD5) UUID, hence version 3.
            assertEquals(3, uuid.version())
            assertFalse(id.startsWith("Transaction"))
            assertFalse(id.startsWith("Note"))
        }
        // Deterministic: regenerating reproduces the identical IDs in order.
        val again = CorpusGenerator.generate()
        assertEquals(ids, again.map { it.entityRefId })
    }

    @Test
    fun occurredAtHasThreeWayTiesAndUpdatedAtFollows() {
        val corpus = CorpusGenerator.generate()
        for (i in 0 until 9 step 3) {
            val a = corpus[i].occurredAtMs
            val b = corpus[i + 1].occurredAtMs
            val c = corpus[i + 2].occurredAtMs
            assertEquals("records $i..${i + 2} share occurredAt", a, b)
            assertEquals(a, c)
            // Next triple starts floor(i/3)+1 minutes earlier.
            if (i + 3 < corpus.size) {
                assertEquals(a - 60_000L, corpus[i + 3].occurredAtMs)
            }
        }
        corpus.forEachIndexed { i, r ->
            assertTrue("updatedAt >= occurredAt for $i", r.updatedAtMs >= r.occurredAtMs)
        }
    }

    @Test
    fun boundaryRecordsCarryTheFrozenSuffixes() {
        val corpus = CorpusGenerator.generate()
        assertTrue(corpus[0].title!!.endsWith(" ＡＢＣ"))
        assertTrue(corpus[1].title!!.endsWith(" 50%"))
        assertTrue(corpus[2].title!!.endsWith(" under_score"))
        assertTrue(corpus[3].title!!.endsWith(" 𠀀"))
        assertTrue(corpus[4].title!!.endsWith(" 﨑"))
    }

    @Test
    fun noRecordContainsAnyZeroHitQuerySubstring() {
        val corpus = CorpusGenerator.generate()
        val zeroHits = listOf("量子火箭", "unfindabletoken", "火星 ledger")
            .map { SearchNormalizer.normalize(it) }
        corpus.forEachIndexed { i, r ->
            zeroHits.forEach { q ->
                assertFalse(
                    "record $i normalizedText must not contain zero-hit query '$q'",
                    r.normalizedText.contains(q),
                )
            }
        }
    }

    @Test
    fun canonicalBytesAreDeterministicAndHashIsRepeatable() {
        val first = CorpusGenerator.generate().canonicalUtf8()
        val second = CorpusGenerator.generate().canonicalUtf8()
        assertEquals("canonical bytes repeatable", first.toList(), second.toList())
        val sha = MessageDigest.getInstance("SHA-256").digest(first)
            .joinToString("") { "%02x".format(it) }
        println("corpus-v1 SHA-256: $sha")
        assertEquals(FROZEN_CORPUS_V1_SHA256, sha)
    }

    @Test
    fun fieldRulesMatchTheFrozenFillingRules() {
        val corpus = CorpusGenerator.generate()
        corpus.forEachIndexed { i, r ->
            val isTransaction = i < 7000
            if (isTransaction) {
                // body present exactly when i % 5 != 0
                assertEquals("body presence for transaction $i", i % 5 != 0, r.body != null)
                // title always one vocabulary phrase plus optional boundary suffix
                assertTrue("transaction $i has a title", r.title != null)
            } else {
                // Note title present exactly when i % 5 < 2
                assertEquals("note title presence $i", i % 5 < 2, r.title != null)
                assertTrue("note $i has a body", r.body != null)
            }
            assertTrue("auxiliary present for $i", r.auxiliary.isNotBlank())
        }
    }
}

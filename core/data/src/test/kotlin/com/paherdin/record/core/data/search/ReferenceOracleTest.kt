package com.paherdin.record.core.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tech PoC P4 (plan Task 4): the reference oracle — full-scan substring
 * semantics, frozen order (`occurredAt` DESC, `entityRefId` ASC), `take(50)`
 * first-page truncation, a >50-hit query, zero hits, and determinism.
 */
class ReferenceOracleTest {

    private val corpus = CorpusGenerator.generate()

    @Test
    fun singleHitQueryReturnsThatRecordFirst() {
        // "ＡＢＣ" appears only on record 0 (boundary suffix), normalized to "abc".
        val ids = ReferenceOracle.expectedFirstPage(corpus, "ＡＢＣ")
        assertEquals(1, ids.size)
        assertEquals(corpus[0].entityRefId, ids[0])
    }

    @Test
    fun firstPageIsTruncatedToFifty() {
        // 餐 is a substring of every Chinese-vocabulary record — far more than 50.
        val ids = ReferenceOracle.expectedFirstPage(corpus, "餐")
        assertEquals(50, ids.size)
        assertTrue(
            "the unpaged match list must exceed the page size",
            ReferenceOracle.expectedAllMatches(corpus, SearchNormalizer.normalize("餐")).size >= 50,
        )
    }

    @Test
    fun zeroHitQueryReturnsEmptyList() {
        val zeroHits = listOf("量子火箭", "unfindabletoken", "火星 ledger")
        zeroHits.forEach { q ->
            assertEquals("query '$q'", emptyList<String>(), ReferenceOracle.expectedFirstPage(corpus, q))
        }
    }

    @Test
    fun orderIsOccurredAtDescendingThenEntityRefIdAscending() {
        val ids = ReferenceOracle.expectedFirstPage(corpus, "expense")
        val matches = ReferenceOracle.expectedAllMatches(corpus, SearchNormalizer.normalize("expense"))
        assertEquals(ids, matches.take(50).map { it.entityRefId })
        for (i in 1 until matches.size) {
            val prev = matches[i - 1]
            val cur = matches[i]
            val order = when {
                prev.occurredAtMs != cur.occurredAtMs -> prev.occurredAtMs > cur.occurredAtMs
                else -> prev.entityRefId < cur.entityRefId
            }
            assertTrue("sorted pair $i", order)
        }
    }

    @Test
    fun caseFoldMeansExpenseAndEXPENSEShareTheSameFirstPage() {
        val a = ReferenceOracle.expectedFirstPage(corpus, "expense")
        val b = ReferenceOracle.expectedFirstPage(corpus, "EXPENSE")
        assertEquals(a, b)
        assertTrue(a.isNotEmpty())
    }

    @Test
    fun oracleIsDeterministic() {
        val q = QueryManifest.queries.first().text
        assertEquals(
            ReferenceOracle.expectedFirstPage(corpus, q),
            ReferenceOracle.expectedFirstPage(corpus, q),
        )
    }

    @Test
    fun expectedManifestV1MatchesAFreshComputationForEveryQuery() {
        // The committed frozen artifact must equal a fresh full-scan computation
        // byte-for-byte for every one of the 30 frozen queries.
        val fresh = QueryManifest.queries.associate { q ->
            q.id to ReferenceOracle.expectedFirstPage(corpus, q.text)
        }
        val frozen = ExpectedManifestV1.firstPageIds
        assertEquals("query ids", QueryManifest.queries.map { it.id }, frozen.map { it.id })
        fresh.forEach { (id, ids) ->
            assertEquals("fresh computation for $id", ids, frozen.first { it.id == id }.ids)
        }
    }
}

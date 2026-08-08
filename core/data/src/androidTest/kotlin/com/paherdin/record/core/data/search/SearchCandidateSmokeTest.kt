package com.paherdin.record.core.data.search

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tech PoC P4 (plan Task 6) — candidate capability smoke test on the reference
 * device: identical schema/fill order, escaped literal `%`, exact order/page
 * size, FTS4 table creation, `docid == rowId` for all 10,000 rows, and both
 * query paths.
 */
@RunWith(AndroidJUnit4::class)
class SearchCandidateSmokeTest {

    private lateinit var context: Context
    private lateinit var corpus: List<SearchFixtureRecord>
    private lateinit var runDir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val runId = InstrumentationRegistry.getArguments().getString("p4RunId") ?: "p4-smoke-local"
        runDir = File(context.cacheDir, "p4-search-benchmark/$runId")
        corpus = CorpusGenerator.generate()
    }

    /** One database directory per test method: methods must not share files. */
    private fun dbDir(method: String): File = File(runDir, method)

    @Test
    fun bothCandidatesFillIdenticalCorpusInSameOrder() = runBlocking {
        val like = SearchCandidateDb.likeDatabase(context, dbDir("fill-like"))
        val fts = SearchCandidateDb.ftsDatabase(context, dbDir("fill-fts"))
        try {
            like.dao().insertAll(corpus.toEntities())
            fts.dao().insertAll(corpus.toEntities())
            val likeIds = like.dao().allEntityRefIdsInFillOrder()
            val ftsIds = fts.dao().allEntityRefIdsInFillOrder()
            assertEquals(10_000, likeIds.size)
            assertEquals(10_000, ftsIds.size)
            assertEquals(likeIds, ftsIds)
            assertEquals(corpus.map { it.entityRefId }, likeIds)
        } finally {
            like.close()
            fts.close()
        }
    }

    @Test
    fun escapedLiteralPercentMatchesOnlyTheBoundaryRecord() = runBlocking {
        val like = SearchCandidateDb.likeDatabase(context, dbDir("escape-like"))
        try {
            like.dao().insertAll(corpus.toEntities())
            // Query "50%" escaped to "50\%" must match only record 1's title suffix.
            val q = SearchNormalizer.escapeLike(SearchNormalizer.normalize("50%"))
            val ids = like.dao().likeFirstPage(q)
            assertEquals(1, ids.size)
            assertEquals(corpus[1].entityRefId, ids.single())
        } finally {
            like.close()
        }
    }

    @Test
    fun likePathMatchesOracleExpectedFirstPageExactly() = runBlocking {
        val like = SearchCandidateDb.likeDatabase(context, dbDir("like-expected"))
        try {
            like.dao().insertAll(corpus.toEntities())
            for (mq in QueryManifest.queries) {
                val q = SearchNormalizer.escapeLike(SearchNormalizer.normalize(mq.text))
                val actual = like.dao().likeFirstPage(q)
                val expected = ExpectedManifestV1.firstPageIds.first { it.id == mq.id }.ids
                assertEquals("LIKE first page for ${mq.id} (${mq.text})", expected, actual)
            }
        } finally {
            like.close()
        }
    }

    @Test
    fun ftsTableIsCreatedAndDocidEqualsRowIdForAllTenThousandRows() {
        val fts = SearchCandidateDb.ftsDatabase(context, dbDir("docid-fts"))
        runBlocking { fts.dao().insertAll(corpus.toEntities()) }
        try {
            val db = fts.openHelper.readableDatabase
            val total = db.query("SELECT COUNT(*) FROM SearchDocument_fts").use { c ->
                c.moveToFirst(); c.getLong(0)
            }
            assertEquals(10_000L, total)
            // No FTS docid without a matching content rowId, and no duplicates.
            val orphan = db.query(
                "SELECT COUNT(*) FROM SearchDocument_fts f LEFT JOIN SearchDocument s " +
                    "ON f.docid = s.rowId WHERE s.rowId IS NULL",
            ).use { c -> c.moveToFirst(); c.getLong(0) }
            assertEquals(0L, orphan)
            val distinct = db.query("SELECT COUNT(DISTINCT docid) FROM SearchDocument_fts").use { c ->
                c.moveToFirst(); c.getLong(0)
            }
            assertEquals(10_000L, distinct)
        } finally {
            fts.close()
        }
    }

    @Test
    fun ftsQueryPathExecutesAndReturnsOrderedRealMatches() = runBlocking {
        // Capability smoke: every returned ID is a real match under the frozen
        // semantics, page size respected, and the order follows the frozen rule.
        // Full 100% correctness is measured by the Task 8 protocol, not here.
        val fts = SearchCandidateDb.ftsDatabase(context, dbDir("query-fts"))
        try {
            fts.dao().insertAll(corpus.toEntities())
            for (mq in QueryManifest.queries) {
                val m = FtsMatchTranslator.translate(mq.text)
                val actual = fts.dao().ftsFirstPage(m)
                val matchSet = ReferenceOracle.expectedAllMatches(
                    corpus, SearchNormalizer.normalize(mq.text),
                ).map { it.entityRefId }.toSet()
                assertTrue("${mq.id}: at most page size 50", actual.size <= 50)
                actual.forEach { id ->
                    assertTrue("${mq.id}: returned '$id' must be a real substring match", id in matchSet)
                }
                val oracleOrderedSubset = matchSet
                    .filter { it in actual.toSet() }
                    .let { full -> ReferenceOracle.expectedFirstPage(corpus, mq.text).filter { it in full } }
                assertEquals("${mq.id} (${mq.text} → $m) order follows the frozen rule", oracleOrderedSubset, actual)
            }
        } finally {
            fts.close()
        }
    }
}

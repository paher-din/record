package com.paherdin.record.core.data.search

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tech PoC P4 (plan Task 7/8) — the benchmark test: `harnessSelfCheck` proves
 * the harness measures what it claims; `fullProtocol` runs the frozen device
 * benchmark for both candidates over all 30 manifest queries.
 */
@RunWith(AndroidJUnit4::class)
class SearchBenchmarkTest {

    private lateinit var context: Context
    private lateinit var runDir: File
    private lateinit var runId: String
    private lateinit var corpus: List<SearchFixtureRecord>

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        runId = InstrumentationRegistry.getArguments().getString("p4RunId") ?: "p4-local"
        runDir = File(context.cacheDir, "p4-search-benchmark/$runId")
        corpus = CorpusGenerator.generate()
    }

    // ------------------------------------------------------------------
    // Task 7 — harness self-check
    // ------------------------------------------------------------------

    @Test
    fun harnessSelfCheck() = runBlocking {
        println("P4-CACHE-DIR: ${context.cacheDir.absolutePath}")
        // 1. New retained run directory per invocation.
        runDir.mkdirs()
        assertTrue("run directory exists: $runDir", runDir.isDirectory)

        // 2. Both candidates fill and produce their database files.
        // The expected lists below must derive from the same filled subset.
        val smallCorpus = corpus.take(100)
        val like = SearchCandidateDb.likeDatabase(context, File(runDir, "selfcheck"))
        val fts = SearchCandidateDb.ftsDatabase(context, File(runDir, "selfcheck"))
        like.dao().insertAll(smallCorpus.toEntities())
        fts.dao().insertAll(smallCorpus.toEntities())
        assertEquals(100, like.dao().allEntityRefIdsInFillOrder().size)
        assertEquals(100, fts.dao().allEntityRefIdsInFillOrder().size)
        assertTrue(File(runDir, "selfcheck/like.db").isFile)
        assertTrue(File(runDir, "selfcheck/fts.db").isFile)

        // 3. Counterbalancing: even → L first, odd → F first.
        assertEquals(
            listOf(SearchBenchmarkHarness.Candidate.LIKE, SearchBenchmarkHarness.Candidate.FTS),
            SearchBenchmarkHarness.candidateOrderForQueryIndex(0),
        )
        assertEquals(
            listOf(SearchBenchmarkHarness.Candidate.FTS, SearchBenchmarkHarness.Candidate.LIKE),
            SearchBenchmarkHarness.candidateOrderForQueryIndex(1),
        )
        assertEquals(
            listOf(SearchBenchmarkHarness.Candidate.LIKE, SearchBenchmarkHarness.Candidate.FTS),
            SearchBenchmarkHarness.candidateOrderForQueryIndex(28),
        )
        assertEquals(
            listOf(SearchBenchmarkHarness.Candidate.FTS, SearchBenchmarkHarness.Candidate.LIKE),
            SearchBenchmarkHarness.candidateOrderForQueryIndex(29),
        )

        // 4. runQueryBlock: exactly 5 warmups + 30 positive samples, DAO-only.
        val expected = ReferenceOracle.expectedFirstPage(smallCorpus, "早餐")
        var queryCalls = 0
        var clockReads = 0
        var now = 0L
        val prepared = SearchBenchmarkHarness.Candidate.LIKE.prepareQuery("早餐")
        val res = SearchBenchmarkHarness.runPreparedQueryBlock(
            candidate = SearchBenchmarkHarness.Candidate.LIKE,
            queryId = "q02",
            preparedQuery = prepared,
            expectedIds = expected,
            query = {
                queryCalls += 1
                like.dao().likeFirstPage(it)
            },
            nanoTime = {
                clockReads += 1
                now += 1
                now
            },
        )
        assertEquals(35, queryCalls)
        assertEquals(60, clockReads)
        assertEquals(30, res.samplesNs.size)
        assertTrue("all samples positive", res.samplesNs.all { it > 0 })
        assertTrue(res.correct)
        assertNull(res.queryException)

        // 5. Wrong-order expected list is captured as incorrect, not thrown.
        val wrongOrder = expected.reversed()
        val wrong = SearchBenchmarkHarness.runPreparedQueryBlock(
            candidate = SearchBenchmarkHarness.Candidate.LIKE,
            queryId = "q02-wrong",
            preparedQuery = prepared,
            expectedIds = wrongOrder,
            query = like.dao()::likeFirstPage,
        )
        assertFalse("wrong-order expected must mark the run incorrect", wrong.correct)

        // 6. A measured DAO exception is evidence for this block and does not
        // escape to abort the remaining candidate/query matrix.
        var failingCalls = 0
        val failed = SearchBenchmarkHarness.runPreparedQueryBlock(
            candidate = SearchBenchmarkHarness.Candidate.LIKE,
            queryId = "q02-exception",
            preparedQuery = prepared,
            expectedIds = expected,
            query = {
                failingCalls += 1
                if (failingCalls == SearchBenchmarkHarness.WARMUPS + 2) error("injected query failure")
                expected
            },
        )
        assertFalse(failed.correct)
        assertEquals(1, failed.samplesNs.size)
        assertTrue(failed.queryException!!.contains("measured[1]"))
        assertNull(failed.p50Ns)
        assertNull(failed.p95Ns)
        assertNull(failed.cv)

        // 7. Thermal gate: NONE/LIGHT valid, MODERATE+ invalid.
        assertTrue(SearchBenchmarkHarness.isThermalStatusValid(PowerManager.THERMAL_STATUS_NONE))
        assertTrue(SearchBenchmarkHarness.isThermalStatusValid(PowerManager.THERMAL_STATUS_LIGHT))
        assertFalse(SearchBenchmarkHarness.isThermalStatusValid(PowerManager.THERMAL_STATUS_MODERATE))
        assertFalse(SearchBenchmarkHarness.isThermalStatusValid(PowerManager.THERMAL_STATUS_SEVERE))

        // 8. JSONL evidence retains expected/actual order, every sample, and
        // the exact thermal status before/after the block.
        val line = SearchBenchmarkHarness.toJsonl(
            SearchBenchmarkHarness.QueryBlockEvidence(
                result = res,
                thermalStatusBefore = PowerManager.THERMAL_STATUS_NONE,
                thermalStatusAfter = PowerManager.THERMAL_STATUS_LIGHT,
            ),
        )
        val obj = JSONObject(line)
        assertEquals("q02", obj.getString("queryId"))
        assertEquals("L", obj.getString("candidate"))
        assertEquals(expected, obj.getJSONArray("expectedIds").toStringList())
        assertEquals(expected, obj.getJSONArray("actualIds").toStringList())
        assertEquals(30, obj.getJSONArray("samplesNs").length())
        assertEquals(res.p95Ns, obj.getLong("p95Ns"))
        assertEquals(PowerManager.THERMAL_STATUS_NONE, obj.getInt("thermalStatusBefore"))
        assertEquals(PowerManager.THERMAL_STATUS_LIGHT, obj.getInt("thermalStatusAfter"))
        assertTrue(obj.isNull("queryException"))

        val header = SearchBenchmarkHarness.headerJson(context, runId, PowerManager.THERMAL_STATUS_NONE)
        assertTrue(header.getString("socModel").isNotBlank())
        assertTrue(header.getLong("totalRamBytes") > 0L)
        SearchBenchmarkHarness.requireFrozenReferenceProfile(header)

        // 9. Candidate maxima over query results.
        val p95 = requireNotNull(res.p95Ns)
        val cv = requireNotNull(res.cv)
        val metrics = CandidateMetrics(
            perQueryP95Ns = listOf(p95, p95 - 1),
            perQueryCv = listOf(cv, cv - 0.001),
        )
        assertEquals(p95, metrics.comparisonP95Ns)
        assertEquals(cv, metrics.comparisonCv, 1e-12)

        like.close()
        fts.close()
    }

    private fun org.json.JSONArray.toStringList(): List<String> =
        (0 until length()).map(::getString)

    // ------------------------------------------------------------------
    // Task 8 — full protocol on the reference device
    // ------------------------------------------------------------------

    @Test
    fun fullProtocol() = runBlocking {
        // Frozen preconditions: airplane mode on, interactive (foreground
        // instrumentation), initial thermal status NONE/LIGHT. Five idle
        // minutes before the run is an execution precondition outside this test.
        val resolver = context.contentResolver
        val airplaneOn = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val initialThermal = SearchBenchmarkHarness.thermalStatus(powerManager)
        require(airplaneOn) { "airplane mode must be enabled before the measured run" }
        require(powerManager.isInteractive) { "device must be interactive/foreground before the measured run" }
        require(SearchBenchmarkHarness.isThermalStatusValid(initialThermal)) {
            "initial thermal status must be NONE or LIGHT, got $initialThermal"
        }
        val header = SearchBenchmarkHarness.headerJson(context, runId, initialThermal)
        SearchBenchmarkHarness.requireFrozenReferenceProfile(header)
        SearchBenchmarkHarness.appendEvidence(context, runDir, header.toString())

        val like = SearchCandidateDb.likeDatabase(context, File(runDir, "full"))
        val fts = SearchCandidateDb.ftsDatabase(context, File(runDir, "full"))
        try {
            SearchBenchmarkHarness.fillCandidates(like, fts, corpus)

            val results = mutableListOf<SearchBenchmarkHarness.QueryResult>()
            QueryManifest.queries.forEachIndexed { index, mq ->
                val expected = ExpectedManifestV1.firstPageIds.first { it.id == mq.id }.ids
                val order = SearchBenchmarkHarness.candidateOrderForQueryIndex(index)
                for (candidate in order) {
                    val thermalBefore = SearchBenchmarkHarness.thermalStatus(powerManager)
                    val res = SearchBenchmarkHarness.runQueryBlock(
                        candidate = candidate,
                        queryId = mq.id,
                        queryText = mq.text,
                        expectedIds = expected,
                        dao = if (candidate == SearchBenchmarkHarness.Candidate.LIKE) like.dao() else fts.dao(),
                    )
                    val thermalAfter = SearchBenchmarkHarness.thermalStatus(powerManager)
                    val evidence = SearchBenchmarkHarness.QueryBlockEvidence(
                        result = res,
                        thermalStatusBefore = thermalBefore,
                        thermalStatusAfter = thermalAfter,
                    )
                    SearchBenchmarkHarness.appendEvidence(
                        context,
                        runDir,
                        SearchBenchmarkHarness.toJsonl(evidence),
                    )
                    require(SearchBenchmarkHarness.isThermalStatusValid(thermalBefore) &&
                        SearchBenchmarkHarness.isThermalStatusValid(thermalAfter)) {
                        "thermal status NONE/LIGHT required around ${mq.id}/${candidate.evidenceId}, " +
                            "got before=$thermalBefore after=$thermalAfter — full run invalidated"
                    }
                    results += res
                }
            }

            // Candidate-level maxima from the retained evidence.
            val likeResults = results.filter { it.candidate == SearchBenchmarkHarness.Candidate.LIKE }
            val ftsResults = results.filter { it.candidate == SearchBenchmarkHarness.Candidate.FTS }
            val likeSummary = summarizeCandidate(likeResults)
            val ftsSummary = summarizeCandidate(ftsResults)
            val selection = SelectionRule.selectCandidate(
                likeSummary.toSelectionEvidence(),
                ftsSummary.toSelectionEvidence(),
            )
            val summary = JSONObject()
                .put("type", "summary")
                .put("likeCorrect", likeSummary.correct)
                .put("ftsCorrect", ftsSummary.correct)
                .put("likeEligible", likeSummary.eligible)
                .put("ftsEligible", ftsSummary.eligible)
                .put("likeQueryExceptions", likeSummary.queryExceptions)
                .put("ftsQueryExceptions", ftsSummary.queryExceptions)
                .put("likeComparisonP95Ns", likeSummary.comparisonP95Ns ?: JSONObject.NULL)
                .put("ftsComparisonP95Ns", ftsSummary.comparisonP95Ns ?: JSONObject.NULL)
                .put("likeComparisonCv", likeSummary.comparisonCv ?: JSONObject.NULL)
                .put("ftsComparisonCv", ftsSummary.comparisonCv ?: JSONObject.NULL)
                .put("selection", selection.toString())
            SearchBenchmarkHarness.appendEvidence(context, runDir, summary.toString())
            println("P4-SUMMARY $summary")
        } finally {
            like.close()
            fts.close()
        }
    }

    private data class CandidateSummary(
        val correct: Boolean,
        val comparisonP95Ns: Long?,
        val comparisonCv: Double?,
        val eligible: Boolean,
        val queryExceptions: Int,
    ) {
        fun toSelectionEvidence() = SelectionRule.CandidateEvidence(
            correct = correct,
            comparisonP95Ns = comparisonP95Ns ?: Long.MAX_VALUE,
            comparisonCv = comparisonCv ?: Double.MAX_VALUE,
        )
    }

    private fun summarizeCandidate(results: List<SearchBenchmarkHarness.QueryResult>): CandidateSummary {
        val complete = results.size == QueryManifest.queries.size && results.all {
            it.queryException == null && it.samplesNs.size == SearchBenchmarkHarness.MEASURED &&
                it.p95Ns != null && it.cv != null
        }
        // Candidate-level maxima reuse the frozen CandidateMetrics aggregation.
        val metrics = if (complete) {
            CandidateMetrics(
                perQueryP95Ns = results.map { requireNotNull(it.p95Ns) },
                perQueryCv = results.map { requireNotNull(it.cv) },
            )
        } else {
            null
        }
        val p95 = metrics?.comparisonP95Ns
        val cv = metrics?.comparisonCv
        val correct = complete && results.all { it.correct }
        return CandidateSummary(
            correct = correct,
            comparisonP95Ns = p95,
            comparisonCv = cv,
            eligible = correct && requireNotNull(p95) <= SelectionRule.ABSOLUTE_P95_LIMIT_NS,
            queryExceptions = results.count { it.queryException != null },
        )
    }
}

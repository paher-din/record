package com.paherdin.record.core.data.search

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Tech PoC P4 benchmark protocol and machine-readable evidence writer. */
object SearchBenchmarkHarness {

    const val WARMUPS = 5
    const val MEASURED = 30
    const val PAGE_SIZE = 50

    private const val FROZEN_DEVICE_MODEL = "23116PN5BC"
    private const val FROZEN_DEVICE = "shennong"
    private const val FROZEN_BUILD_FINGERPRINT =
        "Xiaomi/shennong/shennong:16/BP2A.250605.031.A3/OS3.0.307.0.WNBCNXM:user/release-keys"
    private const val FROZEN_API_LEVEL = 36
    private const val FROZEN_CPU_ABI = "arm64-v8a"
    private const val FROZEN_SOC_MANUFACTURER = "QTI"
    private const val FROZEN_SOC_MODEL = "SM8650"
    private const val FROZEN_HARDWARE = "qcom"
    private const val FROZEN_TOTAL_RAM_BYTES = 11_710_394_368L

    enum class Candidate(val evidenceId: String) {
        LIKE("L") {
            override fun prepareQuery(queryText: String): String =
                SearchNormalizer.escapeLike(SearchNormalizer.normalize(queryText))

            override suspend fun execute(dao: SearchDao, preparedQuery: String): List<String> =
                dao.likeFirstPage(preparedQuery)
        },
        FTS("F") {
            override fun prepareQuery(queryText: String): String =
                FtsMatchTranslator.translate(queryText)

            override suspend fun execute(dao: SearchDao, preparedQuery: String): List<String> =
                dao.ftsFirstPage(preparedQuery)
        };

        abstract fun prepareQuery(queryText: String): String
        abstract suspend fun execute(dao: SearchDao, preparedQuery: String): List<String>
    }

    /** Frozen counterbalance: even query index runs L→F, odd runs F→L. */
    fun candidateOrderForQueryIndex(index: Int): List<Candidate> =
        if (index % 2 == 0) listOf(Candidate.LIKE, Candidate.FTS)
        else listOf(Candidate.FTS, Candidate.LIKE)

    data class QueryResult(
        val queryId: String,
        val candidate: Candidate,
        val correct: Boolean,
        val expectedIds: List<String>,
        val actualIds: List<String>,
        val samplesNs: List<Long>,
        val p50Ns: Long?,
        val p95Ns: Long?,
        val cv: Double?,
        val queryException: String?,
        val failedAttemptElapsedNs: Long?,
    )

    data class QueryBlockEvidence(
        val result: QueryResult,
        val thermalStatusBefore: Int,
        val thermalStatusAfter: Int,
    )

    /**
     * Prepares the candidate parameter before delegating to the measured block.
     * Only the DAO call and returned-list materialization sit between clock reads.
     */
    suspend fun runQueryBlock(
        candidate: Candidate,
        queryId: String,
        queryText: String,
        expectedIds: List<String>,
        dao: SearchDao,
    ): QueryResult {
        val preparedQuery = candidate.prepareQuery(queryText)
        return runPreparedQueryBlock(
            candidate = candidate,
            queryId = queryId,
            preparedQuery = preparedQuery,
            expectedIds = expectedIds,
            query = { candidate.execute(dao, it) },
        )
    }

    /**
     * Runs 5 warmups and 30 measured DAO calls. Candidate-query exceptions are
     * returned as block evidence so the matrix can continue; harness, evidence,
     * setup, cancellation, and environment failures still fail the test.
     */
    suspend fun runPreparedQueryBlock(
        candidate: Candidate,
        queryId: String,
        preparedQuery: String,
        expectedIds: List<String>,
        query: suspend (String) -> List<String>,
        nanoTime: () -> Long = SystemClock::elapsedRealtimeNanos,
    ): QueryResult {
        val samples = ArrayList<Long>(MEASURED)
        var actualIds: List<String> = emptyList()
        var correct = true

        repeat(WARMUPS) { index ->
            try {
                query(preparedQuery)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                return failedQueryResult(
                    candidate, queryId, expectedIds, actualIds, samples,
                    "warmup[$index]", error, failedAttemptElapsedNs = null,
                )
            }
        }

        repeat(MEASURED) { index ->
            val start = nanoTime()
            val nextIds = try {
                query(preparedQuery)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val elapsed = nanoTime() - start
                check(elapsed > 0) { "non-positive failed-attempt sample for $queryId/${candidate.evidenceId}" }
                return failedQueryResult(
                    candidate, queryId, expectedIds, actualIds, samples,
                    "measured[$index]", error, failedAttemptElapsedNs = elapsed,
                )
            }
            val elapsed = nanoTime() - start
            check(elapsed > 0) { "non-positive sample for $queryId/${candidate.evidenceId}" }
            actualIds = nextIds
            if (actualIds != expectedIds) correct = false
            samples += elapsed
        }

        val stats = MeasurementStats.from(samples)
        return QueryResult(
            queryId = queryId,
            candidate = candidate,
            correct = correct,
            expectedIds = expectedIds,
            actualIds = actualIds,
            samplesNs = samples,
            p50Ns = stats.p50Ns,
            p95Ns = stats.p95Ns,
            cv = stats.cv,
            queryException = null,
            failedAttemptElapsedNs = null,
        )
    }

    private fun failedQueryResult(
        candidate: Candidate,
        queryId: String,
        expectedIds: List<String>,
        actualIds: List<String>,
        samplesNs: List<Long>,
        phase: String,
        error: Exception,
        failedAttemptElapsedNs: Long?,
    ) = QueryResult(
        queryId = queryId,
        candidate = candidate,
        correct = false,
        expectedIds = expectedIds,
        actualIds = actualIds,
        samplesNs = samplesNs.toList(),
        p50Ns = null,
        p95Ns = null,
        cv = null,
        queryException = "$phase ${error.javaClass.name}: ${error.message.orEmpty()}",
        failedAttemptElapsedNs = failedAttemptElapsedNs,
    )

    fun thermalStatus(powerManager: PowerManager): Int = powerManager.currentThermalStatus

    fun isThermalStatusValid(status: Int): Boolean = status < PowerManager.THERMAL_STATUS_MODERATE

    /** Complete per-block JSONL evidence, including frozen expected order. */
    fun toJsonl(evidence: QueryBlockEvidence): String {
        val r = evidence.result
        return JSONObject()
            .put("type", "query-block")
            .put("queryId", r.queryId)
            .put("candidate", r.candidate.evidenceId)
            .put("correct", r.correct)
            .put("expectedIds", JSONArray(r.expectedIds))
            .put("actualIds", JSONArray(r.actualIds))
            .put("warmups", WARMUPS)
            .put("measuredRunsCompleted", r.samplesNs.size)
            .put("p50Ns", r.p50Ns ?: JSONObject.NULL)
            .put("p95Ns", r.p95Ns ?: JSONObject.NULL)
            .put("cv", r.cv ?: JSONObject.NULL)
            .put("samplesNs", JSONArray(r.samplesNs))
            .put("queryException", r.queryException ?: JSONObject.NULL)
            .put("failedAttemptElapsedNs", r.failedAttemptElapsedNs ?: JSONObject.NULL)
            .put("thermalStatusBefore", evidence.thermalStatusBefore)
            .put("thermalStatusAfter", evidence.thermalStatusAfter)
            .put("timingBoundary", "DAO call through materialized first-page list")
            .toString()
    }

    /** Frozen reference profile stored in the evidence header. */
    fun headerJson(context: Context, runId: String, initialThermalStatus: Int): JSONObject {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val airplaneMode = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0,
        ) == 1
        return JSONObject()
            .put("type", "header")
            .put("runId", runId)
            .put("corpusVersion", CorpusGenerator.corpusVersion)
            .put("manifestVersion", QueryManifest.manifestVersion)
            .put("pageSize", PAGE_SIZE)
            .put("warmups", WARMUPS)
            .put("measured", MEASURED)
            .put("deviceModel", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("buildFingerprint", Build.FINGERPRINT)
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("cpuAbi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            .put("socManufacturer", Build.SOC_MANUFACTURER)
            .put("socModel", Build.SOC_MODEL)
            .put("hardware", Build.HARDWARE)
            .put("totalRamBytes", memoryInfo.totalMem)
            .put("airplaneModeOn", airplaneMode)
            .put("interactive", powerManager.isInteractive)
            .put("initialThermalStatus", initialThermalStatus)
    }

    /** Fails before database creation when any approved reference fact drifts. */
    fun requireFrozenReferenceProfile(header: JSONObject) {
        require(header.getString("runId").isNotBlank()) { "p4RunId must be non-blank" }
        require(header.getString("deviceModel") == FROZEN_DEVICE_MODEL) { "device model changed" }
        require(header.getString("device") == FROZEN_DEVICE) { "device codename changed" }
        require(header.getString("buildFingerprint") == FROZEN_BUILD_FINGERPRINT) {
            "build fingerprint changed"
        }
        require(header.getInt("apiLevel") == FROZEN_API_LEVEL) { "API level changed" }
        require(header.getString("cpuAbi") == FROZEN_CPU_ABI) { "CPU ABI changed" }
        require(header.getString("socManufacturer") == FROZEN_SOC_MANUFACTURER) {
            "SoC manufacturer changed"
        }
        require(header.getString("socModel") == FROZEN_SOC_MODEL) { "SoC model changed" }
        require(header.getString("hardware") == FROZEN_HARDWARE) { "hardware identifier changed" }
        require(header.getLong("totalRamBytes") == FROZEN_TOTAL_RAM_BYTES) { "total RAM changed" }
        require(header.getBoolean("airplaneModeOn")) { "airplane mode must be enabled" }
        require(header.getBoolean("interactive")) { "device must be interactive/foreground" }
        require(isThermalStatusValid(header.getInt("initialThermalStatus"))) {
            "initial thermal status must be NONE or LIGHT"
        }
    }

    /** Writes the canonical JSONL file and reconstructable output chunks. */
    fun appendEvidence(context: Context, runDir: File, line: String) {
        val file = File(runDir, "benchmark-evidence.jsonl")
        runDir.mkdirs()
        file.appendText(line + "\n", Charsets.UTF_8)
        val externalRoot = requireNotNull(context.getExternalFilesDir(null)) {
            "external files directory unavailable for benchmark evidence"
        }
        val external = File(
            externalRoot,
            "p4-search-benchmark/${runDir.name}/benchmark-evidence.jsonl",
        )
        external.parentFile?.mkdirs()
        external.appendText(line + "\n", Charsets.UTF_8)
        val chunks = line.chunked(3_000)
        chunks.forEachIndexed { index, chunk ->
            println("P4-EVIDENCE-CHUNK ${index + 1}/${chunks.size} $chunk")
        }
    }

    fun appendHeader(context: Context, runDir: File, runId: String, initialThermalStatus: Int) {
        appendEvidence(context, runDir, headerJson(context, runId, initialThermalStatus).toString())
    }

    fun fillCandidates(
        likeDb: SearchCandidateDatabase,
        ftsDb: SearchCandidateDatabase,
        corpus: List<SearchFixtureRecord>,
    ) = runBlocking {
        likeDb.dao().insertAll(corpus.toEntities())
        ftsDb.dao().insertAll(corpus.toEntities())
    }
}

package com.paherdin.record.core.data.startup

import com.paherdin.record.core.data.FinanceMatrixValidator
import com.paherdin.record.core.data.FinanceViolationException
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M1-T09 Step 1 — failing fixture generator tests (plan Task M1-T09 Step 1).
 * They pin the exact `m1-startup-v1` spec 11.2 cardinalities, the deterministic
 * bytes/hashes of every fixture, the exact v2 activation-record JSON shape for
 * the pending states, and the pre-publish integrity contract (finance matrix,
 * XOR identity, relation/attachment endpoints, attachment blob bytes) before a
 * fixture may be published.
 */
class M1StartupFixtureGeneratorTest {

    // ------------------------------------------------------------------
    // Exact spec 11.2 cardinalities (frozen, not invented)
    // ------------------------------------------------------------------

    @Test
    fun fixtureVersionIsM1StartupV1() {
        assertEquals("m1-startup-v1", M1StartupFixtureGenerator.FIXTURE_VERSION)
    }

    @Test
    fun allEightFixturesAreEnumeratedInSpecOrder() {
        assertEquals(
            listOf(
                M1StartupFixture.Fresh,
                M1StartupFixture.Typical,
                M1StartupFixture.HighVolume,
                M1StartupFixture.PendingActivation,
                M1StartupFixture.PendingMigrationPrepared,
                M1StartupFixture.PendingMigrationFailedSetIsolated,
                M1StartupFixture.PendingMigrationRestoreCopyValidated,
                M1StartupFixture.PendingMigrationMainReplaced,
            ),
            M1StartupFixture.entries,
        )
    }

    @Test
    fun freshFixtureIsEmptyAndHashless() {
        val plan = M1StartupFixtureGenerator.plan(M1StartupFixture.Fresh)
        assertEquals(0, plan.records.accounts.size)
        assertEquals(0, plan.records.entityRefs.size)
        assertEquals(0, plan.records.transactions.size)
        assertEquals(0, plan.records.entries.size)
        assertEquals(0, plan.records.notes.size)
        assertEquals(0, plan.records.relations.size)
        assertEquals(0, plan.records.attachments.size)
        assertEquals(0, plan.records.links.size)
        assertEquals(0, plan.blobs.size)
        assertEquals("a fresh fixture writes no activation record", null, plan.activationRecordJson)
        assertTrue("a fresh fixture has no generation", plan.generationIds.isEmpty())
        assertEquals("a fresh fixture has no staged candidate", emptySet<String>(), plan.incompleteMarkerGenerations)
        assertEquals("a fresh fixture has no migration snapshot", null, plan.snapshot)
        assertTrue(M1StartupFixtureGenerator.validatePlan(plan).isEmpty())
    }

    @Test
    fun typicalFixtureMatchesExactSpecCardinalities() {
        val plan = M1StartupFixtureGenerator.plan(M1StartupFixture.Typical)
        assertEquals(2, plan.records.accounts.size)
        assertEquals(5, plan.records.transactions.size)
        assertEquals(6, plan.records.entries.size)
        assertEquals(2, plan.records.notes.size)
        assertEquals(2, plan.records.relations.size)
        assertEquals(1, plan.records.attachments.size)
        assertEquals(2, plan.records.links.size)
        // 5 transactions = 2 opening + 1 expense + 1 income + 1 transfer (spec 11.2).
        assertEquals(
            listOf(
                TransactionType.OPENING_BALANCE,
                TransactionType.OPENING_BALANCE,
                TransactionType.EXPENSE,
                TransactionType.INCOME,
                TransactionType.TRANSFER,
            ),
            plan.records.transactions.map { it.type },
        )
        // 2 + 1 + 1 + 2 = 6 AccountEntries (one per single-entry type, two for the transfer).
        assertEquals(
            listOf(1, 1, 1, 1, 2),
            plan.records.entries.groupBy { it.transactionId }.values.map { it.size },
        )
        // 7 EntityRef rows: 5 transactions + 2 notes.
        assertEquals(7, plan.records.entityRefs.size)
        assertEquals(5, plan.records.entityRefs.count { it.entityType == EntityType.TRANSACTION })
        assertEquals(2, plan.records.entityRefs.count { it.entityType == EntityType.NOTE })
        // The one canonical attachment blob is linked to both valid owners.
        assertEquals(1, plan.blobs.size)
        val owners = plan.records.links.map { it.ownerEntityRefId }.toSet()
        assertEquals(2, owners.size)
        val ownerTypes = plan.records.entityRefs.filter { it.id in owners }.map { it.entityType }.toSet()
        assertEquals(setOf(EntityType.TRANSACTION, EntityType.NOTE), ownerTypes)
        // Healthy v2 activation record.
        assertNotNull(plan.activationRecordJson)
        assertTrue(plan.activationRecordJson!!.contains("\"migrationRecoveryState\":\"MIGRATION_HEALTHY\""))
        assertEquals("the healthy fixture generation is complete", emptySet<String>(), plan.incompleteMarkerGenerations)
        assertTrue(M1StartupFixtureGenerator.validatePlan(plan).isEmpty())
    }

    @Test
    fun highVolumeFixtureMatchesExactSpecCardinalities() {
        val plan = M1StartupFixtureGenerator.plan(M1StartupFixture.HighVolume)
        assertEquals(10, plan.records.accounts.size)
        assertEquals(6_000, plan.records.transactions.size)
        assertEquals(7_190, plan.records.entries.size)
        assertEquals(4_000, plan.records.notes.size)
        assertEquals(2_000, plan.records.relations.size)
        assertEquals(100, plan.records.attachments.size)
        assertEquals(100, plan.records.links.size)
        assertEquals(10_000, plan.records.entityRefs.size)
        // The frozen P6 type mix (spec 11.2): 10 opening, 3,000 expense,
        // 1,800 income, 1,190 same-currency transfer.
        val types = plan.records.transactions.map { it.type }
        assertEquals(10, types.count { it == TransactionType.OPENING_BALANCE })
        assertEquals(3_000, types.count { it == TransactionType.EXPENSE })
        assertEquals(1_800, types.count { it == TransactionType.INCOME })
        assertEquals(1_190, types.count { it == TransactionType.TRANSFER })
        // 100 canonical 64-KiB synthetic image blobs, each with one valid owner link.
        assertEquals(100, plan.blobs.size)
        plan.blobs.values.forEach { assertEquals(65_536, it.size) }
        plan.records.attachments.forEach { attachment ->
            assertEquals(65_536, plan.blobs.getValue(attachment.contentIdentity).size)
        }
        assertTrue(M1StartupFixtureGenerator.validatePlan(plan).isEmpty())
    }

    @Test
    fun pendingActivationFixtureIsTypicalPlusStagedCandidateAtPreResolutionBoundary() {
        val plan = M1StartupFixtureGenerator.plan(M1StartupFixture.PendingActivation)
        // Identical typical cardinality in the candidate generation.
        assertEquals(2, plan.records.accounts.size)
        assertEquals(5, plan.records.transactions.size)
        assertEquals(6, plan.records.entries.size)
        assertEquals(2, plan.records.notes.size)
        assertEquals(2, plan.records.relations.size)
        assertEquals(1, plan.records.attachments.size)
        assertEquals(2, plan.records.links.size)
        // PENDING pre-resolution boundary: active = candidate, previous = original.
        val generation = plan.generationActivationJsonOrNull()
        assertEquals("PENDING", generation?.state)
        assertEquals(generation?.candidate, generation?.active)
        assertNotNull(generation?.previous)
        assertTrue("PENDING previous and active generations must differ", generation?.previous != generation?.active)
        assertEquals("PENDING cannot retire a generation", null, generation?.retiring)
        // The staged candidate keeps its incomplete marker; the original is complete.
        assertEquals(setOf(plan.candidateGenerationId), plan.incompleteMarkerGenerations)
        assertEquals(2, plan.generationIds.size)
        assertTrue(M1StartupFixtureGenerator.validatePlan(plan).isEmpty())
    }

    @Test
    fun everyMigrationRecoveryStageProducesAnExactPendingRecord() {
        val stages = listOf(
            M1StartupFixture.PendingMigrationPrepared to "PREPARED",
            M1StartupFixture.PendingMigrationFailedSetIsolated to "FAILED_SET_ISOLATED",
            M1StartupFixture.PendingMigrationRestoreCopyValidated to "RESTORE_COPY_VALIDATED",
            M1StartupFixture.PendingMigrationMainReplaced to "MAIN_REPLACED",
        )
        for ((fixture, stage) in stages) {
            val plan = M1StartupFixtureGenerator.plan(fixture)
            assertEquals("MIGRATION_RECOVERY_PENDING", plan.migrationStateJsonOrNull())
            assertEquals(stage, plan.migrationStageJsonOrNull())
            val payload = plan.migrationPayloadJsonOrNull()
            assertNotNull("pending migration fixture $stage requires a migration payload", payload)
            // A valid published snapshot plus its hash reference.
            assertNotNull("fixture $stage must plan a published snapshot", plan.snapshot)
            assertEquals(
                "the record payload carries the deterministic placeholder substituted at setup",
                M1StartupFixtureGenerator.SNAPSHOT_SHA256_PLACEHOLDER,
                payload!!.getValue("snapshotSha256"),
            )
            assertEquals(
                "the migration payload carries exactly seven fields",
                listOf(
                    "snapshotId", "snapshotSha256", "liveDatabasePath", "workDatabasePath",
                    "restoreTempPath", "quarantinePath", "stage",
                ),
                payload.keys.toList(),
            )
            // Every recorded path is app-data-root-relative and non-escaping.
            for (key in listOf("liveDatabasePath", "workDatabasePath", "restoreTempPath", "quarantinePath")) {
                val value = payload.getValue(key)
                assertTrue("path must be relative: $value", !value.startsWith("/") && !value.startsWith("\\"))
                assertTrue("no traversal segments: $value", value.split('/').none { it == ".." })
                assertTrue("no drive letters: $value", Regex("^[A-Za-z]:").find(value) == null)
            }
            assertEquals("the migration portion keeps a healthy generation pointer", "HEALTHY", plan.generationStateOf())
        }
    }

    @Test
    fun healthyRecordsCarryNoMigrationPayload() {
        for (fixture in listOf(M1StartupFixture.Typical, M1StartupFixture.HighVolume)) {
            val plan = M1StartupFixtureGenerator.plan(fixture)
            assertEquals("MIGRATION_HEALTHY", plan.migrationStateJsonOrNull())
            assertEquals("\"migration\":null", plan.migrationFieldJson())
        }
    }

    // ------------------------------------------------------------------
    // Determinism (fixed seed / clock / IDs)
    // ------------------------------------------------------------------

    @Test
    fun everyDataFixtureIsByteForByteDeterministic() {
        for (fixture in M1StartupFixture.entries) {
            if (fixture == M1StartupFixture.Fresh) continue
            val first = M1StartupFixtureGenerator.plan(fixture)
            val second = M1StartupFixtureGenerator.plan(fixture)
            assertTrue(fixture.name, first.canonicalUtf8().contentEquals(second.canonicalUtf8()))
            assertEquals(fixture.name, first.contentDigest, second.contentDigest)
            assertEquals(fixture.name, first.activationRecordJson, second.activationRecordJson)
            first.blobs.forEach { (identity, bytes) ->
                assertTrue(fixture.name, second.blobs[identity]?.contentEquals(bytes) == true)
            }
        }
    }

    @Test
    fun contentDigestIsSha256OfTheCanonicalBytes() {
        for (fixture in M1StartupFixture.entries) {
            val plan = M1StartupFixtureGenerator.plan(fixture)
            assertEquals(
                fixture.name,
                sha256(plan.canonicalUtf8()),
                plan.contentDigest,
            )
            assertTrue("content digest must be 64 lowercase hex", Regex("^[0-9a-f]{64}$").matches(plan.contentDigest))
        }
    }

    @Test
    fun generationIdsAndRecordIdsAreCanonicalAndDeterministic() {
        for (fixture in M1StartupFixture.entries) {
            val plan = M1StartupFixtureGenerator.plan(fixture)
            plan.generationIds.forEach { id ->
                assertTrue(
                    "$fixture generation id must match the store grammar: $id",
                    Regex("^gen-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(id),
                )
            }
            plan.records.entityRefs.forEach { ref ->
                assertEquals("record ids must be canonical lowercase UUID text", ref.id, ref.id.lowercase())
                assertTrue(ref.id.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
            }
        }
    }

    // ------------------------------------------------------------------
    // Synthetic images: valid, decodable, exactly 64 KiB, deterministic
    // ------------------------------------------------------------------

    @Test
    fun attachmentBlobsAreDeterministicDecodablePngsOfExactly64KiB() {
        for (fixture in listOf(M1StartupFixture.Typical, M1StartupFixture.HighVolume)) {
            val plan = M1StartupFixtureGenerator.plan(fixture)
            assertTrue(plan.blobs.isNotEmpty())
            plan.blobs.forEach { (identity, bytes) ->
                assertEquals("blob $identity must be exactly 64 KiB", 65_536, bytes.size)
                assertEquals(
                    "blob $identity contentIdentity must be the blob SHA-256",
                    identity,
                    sha256(bytes),
                )
                assertDecodablePng(identity, bytes)
            }
            // Distinct images: every blob identity is unique.
            assertEquals(plan.blobs.size, plan.records.attachments.map { it.contentIdentity }.toSet().size)
        }
    }

    /** Re-inflates the IDAT stream and checks every chunk CRC; a conformant PNG decoder succeeds. */
    private fun assertDecodablePng(identity: String, bytes: ByteArray) {
        assertEquals("PNG signature", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).toList(), bytes.take(8).toList())
        var offset = 8
        var sawIhdr = false
        var sawIdat = false
        while (offset < bytes.size) {
            val length = readInt(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataEnd = offset + 8 + length
            assertTrue("chunk $type of blob $identity exceeds the blob bounds", dataEnd + 4 <= bytes.size)
            val crcInput = bytes.copyOfRange(offset + 4, dataEnd)
            val expectedCrc = readInt(bytes, dataEnd)
            val crc = java.util.zip.CRC32()
            crc.update(crcInput)
            assertEquals(
                "chunk $type of blob $identity has a bad CRC",
                expectedCrc.toLong() and 0xFFFF_FFFFL,
                crc.value,
            )
            when (type) {
                "IHDR" -> {
                    sawIhdr = true
                    assertEquals("grayscale PNG", 0, bytes[offset + 8 + 9].toInt())
                }
                "IDAT" -> {
                    sawIdat = true
                    val inflater = Inflater()
                    inflater.setInput(bytes, offset + 8, length)
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (!inflater.finished() && !inflater.needsInput()) {
                        val read = inflater.inflate(buffer)
                        if (read == 0 && inflater.needsDictionary()) break
                        output.write(buffer, 0, read)
                    }
                    inflater.end()
                    assertTrue("IDAT of blob $identity must inflate", output.size() > 0)
                }
                "IEND" -> assertEquals("IEND must be empty", 0, length)
            }
            offset = dataEnd + 4
            if (type == "IEND") break
        }
        assertTrue("blob $identity must contain IHDR", sawIhdr)
        assertTrue("blob $identity must contain IDAT", sawIdat)
        assertEquals("blob $identity must end at the declared size", bytes.size, offset)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    // ------------------------------------------------------------------
    // Pre-publish integrity: finance / XOR / relations / attachment bytes
    // ------------------------------------------------------------------

    @Test
    fun everyDataFixturePassesFinanceMatrixXorEndpointsAndAttachmentIntegrity() {
        for (fixture in M1StartupFixture.entries) {
            val plan = M1StartupFixtureGenerator.plan(fixture)
            if (fixture == M1StartupFixture.Fresh) continue
            val problems = M1StartupFixtureGenerator.validatePlan(plan)
            assertTrue("fixture $fixture must be integral, got: $problems", problems.isEmpty())

            // Independent finance-matrix check (not delegated to the generator).
            val currencyByAccount = plan.records.accounts.associate { it.id to it.currency }
            for (tx in plan.records.transactions) {
                val txEntries = plan.records.entries
                    .filter { it.transactionId == tx.id }
                    .map { com.paherdin.record.core.data.NewAccountEntry(it.id, it.accountId, it.amountMinor) }
                try {
                    FinanceMatrixValidator().validate(tx.type, txEntries, currencyByAccount)
                } catch (e: FinanceViolationException) {
                    fail("fixture $fixture transaction ${tx.id}: ${e.violation}")
                }
            }
            // Independent XOR identity check.
            val txIds = plan.records.transactions.map { it.id }.toSet()
            val noteIds = plan.records.notes.map { it.id }.toSet()
            for (ref in plan.records.entityRefs) {
                val ok = when (ref.entityType) {
                    EntityType.TRANSACTION -> ref.id in txIds && ref.id !in noteIds
                    EntityType.NOTE -> ref.id in noteIds && ref.id !in txIds
                    EntityType.PROJECT -> false
                }
                assertTrue("fixture $fixture XOR violation for EntityRef ${ref.id}", ok)
            }
            // Independent relation endpoint check.
            for (relation in plan.records.relations) {
                assertTrue(relation.transactionId in txIds)
                assertTrue(relation.noteId in noteIds)
            }
            // Independent attachment byte check.
            for (attachment in plan.records.attachments) {
                val blob = plan.blobs[attachment.contentIdentity]
                assertNotNull("fixture $fixture missing blob for ${attachment.contentIdentity}", blob)
                assertEquals(attachment.contentIdentity, sha256(blob!!))
            }
        }
    }

    // ------------------------------------------------------------------
    // Exact v2 activation-record JSON shape
    // ------------------------------------------------------------------

    @Test
    fun activationRecordJsonMatchesTheExactV2StoreShape() {
        val typical = M1StartupFixtureGenerator.plan(M1StartupFixture.Typical)
        assertEquals(
            "{\"recordVersion\":2,\"migrationRecoveryState\":\"MIGRATION_HEALTHY\",\"migration\":null," +
                "\"generationActivation\":{\"activeGenerationId\":\"${typical.activeGenerationId}\"," +
                "\"previousGenerationId\":null,\"candidateGenerationId\":null," +
                "\"retiringGenerationId\":null,\"state\":\"HEALTHY\"}}",
            typical.activationRecordJson,
        )
        val pending = M1StartupFixtureGenerator.plan(M1StartupFixture.PendingActivation).activationRecordJson!!
        assertTrue(pending.startsWith("{\"recordVersion\":2,\"migrationRecoveryState\":\"MIGRATION_HEALTHY\""))
        // Pending ACTIVATION state: the generation portion carries PENDING.
        assertTrue(pending.contains("\"state\":\"PENDING\""))
        assertTrue(pending.contains("\"previousGenerationId\":\""))
    }

    @Test
    fun pendingMigrationRecordJsonCarriesTheExactPayloadShape() {
        val plan = M1StartupFixtureGenerator.plan(M1StartupFixture.PendingMigrationPrepared)
        val record = plan.activationRecordJson!!
        assertTrue(record.startsWith("{\"recordVersion\":2,\"migrationRecoveryState\":\"MIGRATION_RECOVERY_PENDING\""))
        assertTrue(record.contains("\"liveDatabasePath\":\"databases/record.db\""))
        assertTrue(record.contains("\"workDatabasePath\":\"databases/record.db\""))
        assertTrue(record.contains("\"restoreTempPath\":\"files/recovery/restore-${plan.snapshot?.snapshotId}.db\""))
        assertTrue(record.contains("\"quarantinePath\":\"files/recovery/quarantine-${plan.snapshot?.snapshotId}/\""))
        assertTrue(record.contains("\"stage\":\"PREPARED\""))
    }

    @Test
    fun everyFixtureReportsSixtyFourHexContentIdentityForEveryBlob() {
        for (fixture in M1StartupFixture.entries) {
            M1StartupFixtureGenerator.plan(fixture).blobs.forEach { (identity, _) ->
                assertTrue(Regex("^[0-9a-f]{64}$").matches(identity))
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Test-side re-derivation of the pending record's generation-activation object. */
    private fun M1StartupFixturePlan.generationActivationJsonOrNull(): M1GenerationJson? {
        val record = activationRecordJson ?: return null
        val start = record.indexOf("\"generationActivation\":") + "\"generationActivation\":".length
        val end = record.lastIndexOf('}')
        val body = record.substring(start, end + 1)
        fun field(name: String): String? {
            val marker = "\"$name\":"
            val index = body.indexOf(marker) + marker.length
            if (body[index] == 'n') return null
            val close = body.indexOf(',', index).takeIf { it >= 0 } ?: body.indexOf('}', index)
            return body.substring(index, close).trim('"')
        }
        return M1GenerationJson(
            active = field("activeGenerationId"),
            previous = field("previousGenerationId"),
            candidate = field("candidateGenerationId"),
            retiring = field("retiringGenerationId"),
            state = field("state"),
        )
    }

    private class M1GenerationJson(
        val active: String?,
        val previous: String?,
        val candidate: String?,
        val retiring: String?,
        val state: String?,
    )

    private fun M1StartupFixturePlan.generationStateOf(): String =
        generationActivationJsonOrNull()?.state.orEmpty()

    private fun M1StartupFixturePlan.migrationStateJsonOrNull(): String? {
        val record = activationRecordJson ?: return null
        val marker = "\"migrationRecoveryState\":\""
        val start = record.indexOf(marker) + marker.length
        return record.substring(start, record.indexOf('"', start))
    }

    private fun M1StartupFixturePlan.migrationStageJsonOrNull(): String? {
        val record = activationRecordJson ?: return null
        val marker = "\"stage\":\""
        val start = record.indexOf(marker) + marker.length
        if (start < marker.length) return null
        return record.substring(start, record.indexOf('"', start))
    }

    private fun M1StartupFixturePlan.migrationFieldJson(): String {
        val record = activationRecordJson!!
        val marker = "\"migration\":"
        val start = record.indexOf(marker)
        return record.substring(start, record.indexOf(",\"generationActivation\"", start))
    }

    /** Extracts the `migration` object text (keys preserved) of a pending record. */
    private fun M1StartupFixturePlan.migrationPayloadJsonOrNull(): Map<String, String>? {
        val record = activationRecordJson ?: return null
        val marker = "\"migration\":"
        val start = record.indexOf(marker) + marker.length
        if (record.startsWith("null", start)) return null
        val end = record.indexOf("},", start) + 1
        val body = record.substring(start, end)
        val result = LinkedHashMap<String, String>()
        var offset = 1
        while (offset < body.length) {
            val keyStart = body.indexOf('"', offset)
            val keyEnd = body.indexOf('"', keyStart + 1)
            val key = body.substring(keyStart + 1, keyEnd)
            val valueStart = body.indexOf('"', body.indexOf(':', keyEnd))
            val valueEnd = body.indexOf('"', valueStart + 1)
            result[key] = body.substring(valueStart + 1, valueEnd)
            offset = body.indexOf(',', valueEnd) + 1
            if (offset == 0) break
        }
        return result
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * M1-T09 Step 6 — host report validator contract tests (plan Task M1-T09
 * Step 6). They pin the exact raw-report contract of the copied Macrobenchmark
 * 1.4.1 output: 30 unique positive samples per binding, nearest-rank rank 29
 * p95, the healthy TTID/TTFD budgets, the pending TTID budget with the no-shell
 * (no TTFD) pending-migration rule, and the exact fixture/environment/command
 * log with no invalid iteration.
 */
class M1StartupReportValidatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // Fixture builders — real 1.4.1 aggregate benchmark-data shape
    // ------------------------------------------------------------------

    private fun startupMetricJson(vararg runs: Double): String =
        """{"minimum":0.0,"maximum":0.0,"median":0.0,"coefficientOfVariation":0.0,"runs":[${runs.joinToString(",")}]}"""

    private fun benchmarkJson(block: String, ttidRuns: List<Double>, ttfdRuns: List<Double>?): String {
        val ttfd = ttfdRuns?.let { "\"timeToFullDisplayMs\":${startupMetricJson(*it.toDoubleArray())}," } ?: ""
        return """
            {
              "name": "$block",
              "params": {},
              "className": "$BENCHMARK_CLASS",
              "totalRunTimeNs": 1000,
              "metrics": {"timeToInitialDisplayMs":${startupMetricJson(*ttidRuns.toDoubleArray())},$ttfd"memoryUnusedMs":${startupMetricJson(*ttidRuns.toDoubleArray())}},
              "sampledMetrics": {},
              "warmupIterations": 0,
              "repeatIterations": 30,
              "thermalThrottleSleepSeconds": 0,
              "profilerOutputs": [${(0 until ttidRuns.size).joinToString(",") {
            """{"type":"PerfettoTrace","label":"iter_${blockNumber(it, block)}.perfetto-trace","filename":"iter_${blockNumber(it, block)}.perfetto-trace"}"""
        }}]
            }
        """.trimIndent()
    }

    private fun blockNumber(sampleIndex: Int, block: String): Int {
        val start = M1StartupReportParser.CONTRACT.BLOCKS.indexOfFirst { it.block == block }
        return start * 30 + sampleIndex
    }

    private fun writeBenchmarkData(root: File, vararg entries: String) {
        val json =
            """{"context":{"build":{"fingerprint":"fixture"}},"benchmarks":[${entries.joinToString(",")}]}"""
        File(root, REPORT_DATA_FILE).writeText(json)
    }

    private fun writeTraces(root: File, count: Int) {
        for (i in 0 until count) {
            val trace = File(root, "traces/iter_$i.perfetto-trace")
            trace.parentFile?.mkdirs()
            trace.writeText("x")
        }
    }

    private fun defaultIterationsJson(): String =
        (0 until 8).joinToString(",") { b ->
            (0 until 30).joinToString(",") { i ->
                iterationJson(blockIndex = b, iteration = i)
            }
        }

    private fun iterationJson(
        blockIndex: Int = 0,
        iteration: Int = 0,
        block: String? = null,
        fixture: String? = null,
        fixtureSha256: String? = null,
        thermalBefore: Int = 0,
        thermalAfter: Int = 0,
        pid: String = "1234",
        exitInfoUnexpected: Boolean = false,
        fixtureValidated: Boolean = true,
    ): String {
        val spec = M1StartupReportParser.CONTRACT.BLOCKS[blockIndex]
        return "{" +
            "\"block\":\"${block ?: spec.block}\"," +
            "\"iteration\":$iteration," +
            "\"fixture\":\"${fixture ?: spec.fixture}\"," +
            "\"fixtureSha256\":\"${fixtureSha256 ?: "a".repeat(64)}\"," +
            "\"thermalBefore\":$thermalBefore," +
            "\"thermalAfter\":$thermalAfter," +
            "\"pid\":\"$pid\"," +
            "\"exitInfoUnexpected\":$exitInfoUnexpected," +
            "\"fixtureValidated\":$fixtureValidated" +
            "}"
    }

    private fun defaultCommandLogJson(): String =
        "[{\"command\":\"dumpsys thermalservice\",\"target\":\"$TARGET_PACKAGE\",\"allowed\":true}," +
            "{\"command\":\"am start -W -n $TARGET_PACKAGE/$TARGET_ACTIVITY\",\"target\":\"$TARGET_PACKAGE\",\"allowed\":true}," +
            "{\"command\":\"pm path $TEST_PACKAGE\",\"target\":\"$TEST_PACKAGE\",\"allowed\":true}]"

    private fun writeManifest(
        root: File,
        iterationsJson: String = defaultIterationsJson(),
        blocksJson: String? = null,
        commandLogJson: String = defaultCommandLogJson(),
        identityJson: String? = null,
        environmentJson: String? = null,
    ) {
        val identity = identityJson ?: defaultIdentityJson()
        val environment = environmentJson ?: defaultEnvironmentJson()
        val blocks = blocksJson ?: defaultBlocksJson()
        File(root, "m1-run-manifest.json").writeText(
            buildString {
                append("""{"suite":"M1StartupBenchmark","fixtureVersion":"m1-startup-v1",""")
                append("\"environment\":$environment,")
                append("\"identity\":$identity,")
                append("\"blocks\":[$blocks],")
                append("\"commandLog\":$commandLogJson,")
                append("\"iterations\":[$iterationsJson]}")
            },
        )
    }

    private fun defaultIdentityJson(): String =
        "{\"targetPackage\":\"$TARGET_PACKAGE\",\"testPackage\":\"$TEST_PACKAGE\"," +
            "\"targetActivity\":\"$TARGET_ACTIVITY\",\"setupComponent\":\"$SETUP_COMPONENT\"," +
            "\"variantMarker\":\"true\",\"signingCertSha256\":\"${"b".repeat(64)}\"," +
            "\"dataDir\":\"/data/user/0/$TARGET_PACKAGE\"}"

    private fun defaultEnvironmentJson(): String =
        "{\"brand\":\"xiaomi\",\"model\":\"M1\",\"device\":\"device\",\"fingerprint\":\"f/1\",\"sdkInt\":34," +
            "\"hardware\":\"qcom\",\"cpuAbi\":\"arm64-v8a\",\"totalRamMb\":16384,\"thermalStatus\":\"0\"," +
            "\"batteryLevel\":80,\"displayPeak\":\"120\",\"displayMin\":\"null\"}"

    private fun defaultBlocksJson(): String =
        (0 until 8).joinToString(",") { b ->
            val spec = M1StartupReportParser.CONTRACT.BLOCKS[b]
            "{\"block\":\"${spec.block}\",\"fixture\":\"${spec.fixture}\"," +
                "\"fixtureSha256\":\"${"a".repeat(64)}\",\"iterations\":30,\"warmups\":5,\"ttfdRequired\":${spec.ttfdRequired}}"
        }

    /** Full valid tree: 8 benchmark entries x 30 samples, 240 traces, exact manifest. */
    private fun writeValidTree(root: File) {
        val entries = M1StartupReportParser.CONTRACT.BLOCKS.map { spec ->
            val ttid = List(30) { if (spec.ttfdRequired) 300.0 else 500.0 }
            benchmarkJson(spec.block, ttid, if (spec.ttfdRequired) List(30) { 350.0 } else null)
        }
        writeBenchmarkData(root, *entries.toTypedArray())
        writeTraces(root, 240)
        writeManifest(root)
    }

    private fun validate(root: File) = M1StartupReportValidator.validate(root)

    private fun assertPasses(root: File) {
        val result = validate(root)
        assertTrue("expected PASS, got problems: ${result.problems}", result.problems.isEmpty())
    }

    private fun assertFails(root: File) {
        val result = validate(root)
        assertTrue("expected at least one validation problem, got none", result.problems.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // Positive controls
    // ------------------------------------------------------------------

    @Test
    fun validFrozenReportTreePassesAllGates() {
        val root = tmp.newFolder("valid")
        writeValidTree(root)
        assertPasses(root)
    }

    @Test
    fun pendingActivationKeepsTtfdDiagnosticWithoutBudget() {
        val root = tmp.newFolder("pending-activation-ttfd")
        writeValidTree(root)
        val result = validate(root)
        assertTrue(result.problems.isEmpty())
        // TTFD p95 of the pending-activation block is retained as a diagnostic, never budgeted.
        assertEquals(350.0, result.pendingActivationTtfdP95Ms!!, 0.0001)
    }

    @Test
    fun nearestRankP95IsRankTwentyNineOfThirty() {
        val root = tmp.newFolder("nearest-rank")
        writeValidTree(root)
        val result = validate(root)
        assertTrue(result.problems.isEmpty())
        // Fresh TTID runs: 29 samples at 300.0 and one at 1_000.0 -> rank 29 stays 300.0.
        val ttidRuns = List(29) { 300.0 } + 1_000.0
        val fresh = M1StartupReportParser.CONTRACT.BLOCKS[0]
        val entries = M1StartupReportParser.CONTRACT.BLOCKS.map { spec ->
            if (spec.block == fresh.block) {
                benchmarkJson(fresh.block, ttidRuns, List(30) { 350.0 })
            } else {
                benchmarkJson(spec.block, List(30) { if (spec.ttfdRequired) 300.0 else 500.0 },
                    if (spec.ttfdRequired) List(30) { 350.0 } else null)
            }
        }
        writeBenchmarkData(root, *entries.toTypedArray())
        val recomputed = validate(root)
        assertTrue("rank-29 p95 of 29x300+1x1000 must stay 300.0: ${recomputed.problems}", recomputed.problems.isEmpty())
    }

    // ------------------------------------------------------------------
    // Negative controls
    // ------------------------------------------------------------------

    @Test
    fun missingBenchmarkDataFileFails() {
        val root = tmp.newFolder("missing-data")
        writeTraces(root, 240)
        writeManifest(root)
        assertFails(root)
    }

    @Test
    fun twentyNineSamplesPerBindingFails() {
        val root = tmp.newFolder("29-samples")
        writeValidTree(root)
        val spec = M1StartupReportParser.CONTRACT.BLOCKS[0]
        writeBenchmarkData(root, benchmarkJson(spec.block, List(29) { 300.0 }, List(29) { 350.0 }))
        assertFails(root)
    }

    @Test
    fun zeroOrNegativeOrNonFiniteSampleFails() {
        for ((label, bad) in listOf("zero" to 0.0, "negative" to -1.0, "nan" to Double.NaN, "inf" to Double.POSITIVE_INFINITY)) {
            val root = tmp.newFolder("bad-sample-$label")
            writeValidTree(root)
            val spec = M1StartupReportParser.CONTRACT.BLOCKS[1]
            val runs = List(29) { 300.0 } + bad
            writeBenchmarkData(root, benchmarkJson(spec.block, runs, List(30) { 350.0 }))
            assertFails(root)
        }
    }

    @Test
    fun duplicateIterationRecordFails() {
        val root = tmp.newFolder("duplicate-iteration")
        writeValidTree(root)
        writeManifest(root, defaultIterationsJson() + "," + iterationJson(blockIndex = 0, iteration = 0))
        assertFails(root)
    }

    @Test
    fun missingIterationRecordFails() {
        val root = tmp.newFolder("missing-iteration")
        writeValidTree(root)
        val all = (0 until 8).joinToString(",") { b ->
            (0 until 30).joinToString(",") { i ->
                if (b == 2 && i == 17) "null" else iterationJson(blockIndex = b, iteration = i)
            }.split(",").filterNotNull().joinToString(",")
        }
        writeManifest(root, all)
        assertFails(root)
    }

    @Test
    fun unexpectedExitInfoIterationFails() {
        val root = tmp.newFolder("exit-info")
        writeValidTree(root)
        val all = (0 until 8).joinToString(",") { b ->
            (0 until 30).joinToString(",") { i ->
                iterationJson(blockIndex = b, iteration = i, exitInfoUnexpected = b == 3 && i == 5)
            }
        }
        writeManifest(root, all)
        assertFails(root)
    }

    @Test
    fun unvalidatedFixtureIterationFails() {
        val root = tmp.newFolder("unvalidated")
        writeValidTree(root)
        val all = (0 until 8).joinToString(",") { b ->
            (0 until 30).joinToString(",") { i ->
                iterationJson(blockIndex = b, iteration = i, fixtureValidated = b == 1 && i == 0)
            }
        }
        writeManifest(root, all)
        assertFails(root)
    }

    @Test
    fun wrongFixtureNamePerBlockFails() {
        val root = tmp.newFolder("wrong-fixture")
        writeValidTree(root)
        val all = (0 until 8).joinToString(",") { b ->
            (0 until 30).joinToString(",") { i ->
                iterationJson(blockIndex = b, iteration = i, fixture = if (b == 0) "Typical" else null)
            }
        }
        writeManifest(root, all)
        assertFails(root)
    }

    @Test
    fun pendingMigrationBindingWithTtfdSampleFails() {
        val root = tmp.newFolder("pending-ttfd")
        writeValidTree(root)
        // A pending-migration block must never report TTFD (no shell before safety).
        val blocked = M1StartupReportParser.CONTRACT.BLOCKS.first { !it.ttfdRequired }
        val ttfdEntry = benchmarkJson(blocked.block, List(30) { 500.0 }, List(30) { 350.0 })
        writeBenchmarkData(root, ttfdEntry)
        assertFails(root)
    }

    @Test
    fun healthyBindingWithMissingTtfdFails() {
        val root = tmp.newFolder("healthy-no-ttfd")
        writeValidTree(root)
        val healthy = M1StartupReportParser.CONTRACT.BLOCKS.first { it.ttfdRequired }
        writeBenchmarkData(root, benchmarkJson(healthy.block, List(30) { 300.0 }, null))
        assertFails(root)
    }

    @Test
    fun ttidBudgetViolationFails() {
        val root = tmp.newFolder("ttid-budget")
        writeValidTree(root)
        // p95 (rank 29) of 30 samples at 1_001 ms exceeds the 1,000 ms budget.
        val healthy = M1StartupReportParser.CONTRACT.BLOCKS[0]
        writeBenchmarkData(root, benchmarkJson(healthy.block, List(30) { 1_001.0 }, List(30) { 350.0 }))
        assertFails(root)
    }

    @Test
    fun ttfdBudgetViolationFails() {
        val root = tmp.newFolder("ttfd-budget")
        writeValidTree(root)
        val healthy = M1StartupReportParser.CONTRACT.BLOCKS[0]
        writeBenchmarkData(root, benchmarkJson(healthy.block, List(30) { 300.0 }, List(30) { 1_501.0 }))
        assertFails(root)
    }

    @Test
    fun missingRunManifestFails() {
        val root = tmp.newFolder("no-manifest")
        writeValidTree(root)
        File(root, "m1-run-manifest.json").delete()
        assertFails(root)
    }

    @Test
    fun wrongIdentityBlockFails() {
        for ((label, mutate) in listOf(
            "target" to { json: String -> json.replace("\"targetPackage\":\"$TARGET_PACKAGE\"", "\"targetPackage\":\"com.other.app\"") },
            "marker" to { json: String -> defaultIdentityJson().replace("\"variantMarker\":\"true\"", "\"variantMarker\":\"false\"") },
            "cert" to { json: String -> defaultIdentityJson().replace("\"signingCertSha256\":\"${"b".repeat(64)}\"", "\"signingCertSha256\":\"short\"") },
            "datadir" to { json: String -> defaultIdentityJson().replace("0/$TARGET_PACKAGE", "0/other") },
        )) {
            val root = tmp.newFolder("identity-$label")
            writeValidTree(root)
            writeManifest(root, identityJson = mutate(defaultIdentityJson()))
            assertFails(root)
        }
    }

    @Test
    fun commandLogTargetingProductionPackageFails() {
        val root = tmp.newFolder("command-log")
        writeValidTree(root)
        val bad = "[{\"command\":\"pm clear com.paherdin.record\",\"target\":\"com.paherdin.record\",\"allowed\":true}]"
        writeManifest(root, commandLogJson = bad)
        assertFails(root)
    }

    @Test
    fun emptyCommandLogFails() {
        val root = tmp.newFolder("empty-command-log")
        writeValidTree(root)
        writeManifest(root, commandLogJson = "[]")
        assertFails(root)
    }

    @Test
    fun blankEnvironmentFieldFails() {
        val root = tmp.newFolder("blank-env")
        writeValidTree(root)
        writeManifest(root, environmentJson = defaultEnvironmentJson().replace("\"brand\":\"xiaomi\"", "\"brand\":\"\""))
        assertFails(root)
    }

    @Test
    fun missingTraceFileFails() {
        val root = tmp.newFolder("missing-trace")
        writeValidTree(root)
        File(root, "traces/iter_239.perfetto-trace").delete()
        assertFails(root)
    }

    @Test
    fun wrongBlockNameFails() {
        val root = tmp.newFolder("wrong-block")
        writeValidTree(root)
        val healthy = M1StartupReportParser.CONTRACT.BLOCKS[0]
        writeBenchmarkData(root, benchmarkJson("startup99Wrong", List(30) { 300.0 }, List(30) { 350.0 }))
        assertTrue("unknown block must be a problem", validate(root).problems.isNotEmpty())
    }

    companion object {
        const val BENCHMARK_CLASS = "com.paherdin.record.benchmark.M1StartupBenchmark"
        const val REPORT_DATA_FILE = "com.paherdin.record.benchmark.test-benchmarkData.json"
        const val TARGET_PACKAGE = "com.paherdin.record.benchmark"
        const val TEST_PACKAGE = "com.paherdin.record.benchmark.test"
        const val TARGET_ACTIVITY = "com.paherdin.record.MainActivity"
        const val SETUP_COMPONENT = "com.paherdin.record.benchmark.M1StartupFixtureActivity"
    }
}
package com.paherdin.record.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Out-of-process contract for the benchmark-shadow selected-day read boundary. */
@RunWith(AndroidJUnit4::class)
class TimelineProjectionContractTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation = instrumentation.uiAutomation

    @Test
    fun databaseRetainsTenThousandRowsButFirstDayReaderReturnsOnlyItsSlice() {
        val expectedHash = requiredArgument("p6ExpectedFirstDayHash")
        val result = launchReadOnly(FIRST_DAY)

        assertSelectedDay(result, FIRST_DAY, expectedItemCount = 11, expectedHash)
        assertEquals("10000", result.required("migrationBeforeRowCount"))
        assertEquals("10000", result.required("migrationAfterRowCount"))
        assertEquals(expectedHash, result.required("migrationFirstHashBefore"))
        assertEquals(expectedHash, result.required("migrationFirstHashAfter"))
    }

    @Test
    fun lastDayReaderReturnsOnlyItsSlice() {
        val expectedHash = requiredArgument("p6ExpectedLastDayHash")
        val result = launchReadOnly(LAST_DAY)

        assertSelectedDay(result, LAST_DAY, expectedItemCount = 11, expectedHash)
        assertEquals("10000", result.required("migrationBeforeRowCount"))
        assertEquals("10000", result.required("migrationAfterRowCount"))
        assertEquals(expectedHash, result.required("migrationLastHashBefore"))
        assertEquals(expectedHash, result.required("migrationLastHashAfter"))
    }

    @Test
    fun emptyDayReaderReturnsEmptyState() {
        val expectedHash = requiredArgument("p6ExpectedEmptyDayHash")
        val result = launchReadOnly(EMPTY_DAY)

        assertSelectedDay(result, EMPTY_DAY, expectedItemCount = 0, expectedHash)
    }

    @Test
    fun selectedDayQueryUsesHalfOpenBoundsAndOccurredAtIndex() {
        val result = launchReadOnly(LAST_DAY)
        assertIndexedOccurredAtRangePlan(result.required("queryPlanDetail"))
        assertIndexedOccurredAtRangePlan(result.required("migrationQueryPlanDetail"))

        val error = launchReadOnly(LAST_DAY.copy(nextDayStart = LAST_DAY.dayStart))
        assertEquals("dayStart must be before nextDayStart", error.required("error"))
    }

    @Test
    fun readOnlySelectedDayLaunchPerformsNoSeedWrites() {
        val result = launchReadOnly(LAST_DAY)

        assertEquals("read-only launch must not seed", "0", result.required("seedWriteCount"))
    }

    @Test
    fun preparationAndRenderStateFailuresCloseEveryOpenedResource() {
        listOf("after-open", "after-insert", "after-check").forEach { failurePoint ->
            assertResourcesClosed(
                launchMode(
                    mode = "resource-failure-contract",
                    extras = "--es failurePoint $failurePoint",
                ) { it["error"] != null && it["resourceOpenDelta"] != null && it["resourceCloseDelta"] != null },
            )
        }
        assertResourcesClosed(
            launchMode(mode = "render-resource-failure") {
                it["error"] != null && it["resourceOpenDelta"] != null && it["resourceCloseDelta"] != null
            },
        )
    }

    @Test
    fun migrationCleanupNameOracleAcceptsOnlyCanonicalAuthorizedBasename() {
        val canonical = "$MIGRATION_DB_PREFIX$CANONICAL_UUID.db"
        assertTrue(isAuthorizedMigrationCleanupName(canonical))

        CLEANUP_REJECTED_NAMES.forEach { (label, name) ->
            assertTrue("$label must be rejected: $name", !isAuthorizedMigrationCleanupName(name))
        }
    }

    @Test
    fun successfulMigrationCleanupLeavesNoAuthorizedResidueAndPreservesUnrelatedSentinel() {
        val evidence = ensurePrepared().required("migrationQueryPlanDetail")

        assertCleanupGuardEvidence(evidence)
        assertExactCleanupTargets(evidence)
        assertTrue(evidence, evidence.contains("cleanupAfterHandlesClosed=true"))
        assertTrue(evidence, evidence.contains("cleanupResidue=0"))
        assertTrue(evidence, evidence.contains("unrelatedSentinelPresent=true"))
    }

    @Test
    fun injectedMigrationFailureStillCleansAuthorizedDatabaseAndSidecars() {
        val result = launchMode(
            mode = "resource-failure-contract",
            extras = "--es failurePoint migration-after-room-open",
        ) { it["error"] != null && it["resourceOpenDelta"] != null && it["resourceCloseDelta"] != null }
        val error = result.required("error")

        assertResourcesClosed(result)
        assertCleanupGuardEvidence(error)
        assertExactCleanupTargets(error)
        assertTrue(error, error.contains("cleanupAfterHandlesClosed=true"))
        assertTrue(error, error.contains("cleanupResidue=0"))
        assertTrue(error, error.contains("unrelatedSentinelPresent=true"))
    }

    private fun assertCleanupGuardEvidence(evidence: String) {
        assertTrue(evidence, evidence.contains("canonical=ACCEPTED"))
        CLEANUP_REJECTED_NAMES.keys.forEach { label ->
            assertTrue(evidence, evidence.contains("$label=REJECTED"))
        }
    }

    private fun assertExactCleanupTargets(evidence: String) {
        val targets = evidenceField(evidence, "cleanupTargets").split('|')
        assertEquals("cleanup resolver must return exactly base/wal/shm: $evidence", 3, targets.size)
        val base = targets[0]
        assertTrue("base target must use the canonical authorized name: $base", isAuthorizedMigrationCleanupName(base))
        assertEquals(base + "-wal", targets[1])
        assertEquals(base + "-shm", targets[2])
        assertEquals("cleanup targets must be unique", targets.size, targets.toSet().size)

        val calls = evidenceField(evidence, "cleanupDeleteCalls")
            .takeIf(String::isNotEmpty)
            ?.split('|')
            .orEmpty()
        assertTrue("deleter received an unrelated target: $evidence", calls.all { it in targets })
        assertEquals("deleter calls must be unique", calls.size, calls.toSet().size)
        listOf("-journal", "-wipecheck", "-mj", "/", "\\").forEach { forbidden ->
            assertTrue("cleanup evidence contains forbidden target '$forbidden': $evidence", targets.none { forbidden in it })
            assertTrue("delete call contains forbidden target '$forbidden': $evidence", calls.none { forbidden in it })
        }
        assertTrue(evidence, evidence.contains("cleanupApi=File.delete"))
    }

    private fun evidenceField(evidence: String, key: String): String =
        Regex("(?:^|;)$key=([^;]*)").find(evidence)?.groupValues?.get(1)
            ?: throw AssertionError("missing cleanup evidence field $key: $evidence")

    private fun isAuthorizedMigrationCleanupName(name: String): Boolean {
        if (name == "timeline-shadow.db" || name == "record.db") return false
        if ('/' in name || '\\' in name) return false
        if (!name.startsWith(MIGRATION_DB_PREFIX) || !name.endsWith(".db")) return false
        val uuidText = name.removePrefix(MIGRATION_DB_PREFIX).removeSuffix(".db")
        val uuid = runCatching { UUID.fromString(uuidText) }.getOrNull() ?: return false
        return uuid.toString() == uuidText
    }

    private fun assertResourcesClosed(result: Map<String, String>) {
        val opens = result.required("resourceOpenDelta").toInt()
        val closes = result.required("resourceCloseDelta").toInt()
        assertTrue("failure contract must open a real Room resource: $result", opens > 0)
        assertEquals("every opened resource must close after failure: $result", opens, closes)
    }

    private fun assertIndexedOccurredAtRangePlan(detail: String) {
        val plan = detail.uppercase()
        assertTrue(plan, plan.contains("SEARCH TIMELINE_CARD USING INDEX INDEX_TIMELINE_CARD_OCCURREDAT"))
        assertTrue(plan, plan.contains("OCCURREDAT>?"))
        assertTrue(plan, plan.contains("OCCURREDAT<?"))
    }

    private fun assertSelectedDay(
        result: Map<String, String>,
        request: RequestedDay,
        expectedItemCount: Int,
        expectedHash: String,
    ) {
        assertEquals("shadow DB must retain all fixture rows", "10000", result.required("rowCount"))
        assertEquals(request.date, result.required("selectedDate"))
        assertEquals(request.dayStart.toString(), result.required("dayStart"))
        assertEquals(request.nextDayStart.toString(), result.required("nextDayStart"))
        assertEquals(expectedItemCount.toString(), result.required("itemCount"))
        assertEquals(expectedHash, result.required("actualHash"))
    }

    private fun launchReadOnly(request: RequestedDay): Map<String, String> {
        val preparation = ensurePrepared()
        val selected = launchMode(
            mode = "selected-day-contract",
            extras = "--es selectedDate ${request.date} " +
                "--el dayStart ${request.dayStart} " +
                "--el nextDayStart ${request.nextDayStart}",
        ) { it["actualHash"] != null || it["error"] != null }
        return preparation + selected
    }

    private fun ensurePrepared(): Map<String, String> = synchronized(PREPARATION_LOCK) {
        preparationResult ?: launchMode(mode = "prepare-selected-day-contract") {
            it["prepared"] == "true" || it["error"] != null
        }.also {
            assertEquals("preparation must seed the complete fixture once", "10000", it.required("seedWriteCount"))
            preparationResult = it
        }
    }

    private fun launchMode(
        mode: String,
        extras: String = "",
        complete: (Map<String, String>) -> Boolean,
    ): Map<String, String> {
        val sentinels = TimelineSentinelReader(::shell)
        val previousRunId = sentinels.getString("runId")
        shell("am force-stop $TARGET_APP_PACKAGE")
        val component = "$TARGET_APP_PACKAGE/com.paherdin.record.benchmark.TimelineBenchmarkActivity"
        val launchOut = shell(
            "am start -W -n $component " +
                "--es mode $mode $extras",
        )
        check(launchOut.contains("Status: ok") || launchOut.contains("LaunchState")) {
            "target Activity failed to launch: $launchOut"
        }

        val deadline = System.currentTimeMillis() + RESULT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val all = sentinels.readAll()
            val fresh = all["runId"] != null && all["runId"] != previousRunId
            if (fresh && all["mode"] == mode && complete(all)) {
                return all
            }
            Thread.sleep(250)
        }
        fail("fresh $mode result not exposed within ${RESULT_TIMEOUT_MS}ms")
        throw AssertionError("unreachable")
    }

    private fun requiredArgument(key: String): String = InstrumentationRegistry.getArguments()
        .getString(key) ?: throw AssertionError("missing instrumentation argument $key")

    private fun Map<String, String>.required(key: String): String =
        get(key) ?: throw AssertionError("missing sentinel $key; actual=$this")

    private fun shell(command: String): String =
        uiAutomation.executeShellCommand(command).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
        }

    private data class RequestedDay(
        val date: String,
        val dayStart: Long,
        val nextDayStart: Long,
    )

    private companion object {
        const val RESULT_TIMEOUT_MS = 120_000L
        val PREPARATION_LOCK = Any()
        @Volatile var preparationResult: Map<String, String>? = null
        const val MIGRATION_DB_PREFIX = "timeline-shadow-migration-contract-"
        const val CANONICAL_UUID = "123e4567-e89b-12d3-a456-426614174000"
        val CLEANUP_REJECTED_NAMES = linkedMapOf(
            "wrongPrefix" to "timeline-shadow-other-$CANONICAL_UUID.db",
            "missingUuid" to "$MIGRATION_DB_PREFIX.db",
            "invalidUuid" to "${MIGRATION_DB_PREFIX}not-a-uuid.db",
            "noncanonicalUuid" to "$MIGRATION_DB_PREFIX${CANONICAL_UUID.uppercase()}.db",
            "suffixTrick" to "$MIGRATION_DB_PREFIX$CANONICAL_UUID.db.bak",
            "forwardSeparator" to "nested/$MIGRATION_DB_PREFIX$CANONICAL_UUID.db",
            "backSeparator" to "nested\\$MIGRATION_DB_PREFIX$CANONICAL_UUID.db",
            "absolutePath" to "/data/user/0/com.paherdin.record/databases/$MIGRATION_DB_PREFIX$CANONICAL_UUID.db",
            "mainShadow" to "timeline-shadow.db",
            "production" to "record.db",
            "generationLike" to "vault-generation-$CANONICAL_UUID.db",
            "snapshotLike" to "migration-snapshot-$CANONICAL_UUID.db",
            "nonBasename" to "../$MIGRATION_DB_PREFIX$CANONICAL_UUID.db",
        )
        val FIRST_DAY = RequestedDay("2023-01-01", 1_672_502_400_000L, 1_672_588_800_000L)
        val LAST_DAY = RequestedDay("2025-09-26", 1_758_816_000_000L, 1_758_902_400_000L)
        val EMPTY_DAY = RequestedDay("2022-12-31", 1_672_416_000_000L, 1_672_502_400_000L)
    }
}

package com.paherdin.record.core.data.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.MigrationCoordinator
import com.paherdin.record.core.data.MigrationRecord
import com.paherdin.record.core.data.MigrationRecoveryRecordStore
import com.paherdin.record.core.data.MigrationRecoveryState
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.restore.ActivationRecordStore
import com.paherdin.record.core.data.restore.ActivationRecord
import com.paherdin.record.core.data.restore.BootstrapResult
import com.paherdin.record.core.data.restore.GenerationActivation
import com.paherdin.record.core.data.restore.GenerationManager
import com.paherdin.record.core.data.restore.GenerationState
import com.paherdin.record.core.data.restore.BootstrapFaultHook
import com.paherdin.record.core.data.restore.LegacyGenerationBootstrap
import com.paherdin.record.core.data.restore.VaultSessionRegistry
import com.paherdin.record.core.data.openForTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Fails a named bootstrap boundary the first N invocations of that boundary, or always. */
private class FailingBootstrapHook(
    private val boundary: String,
    private val failFirstNCalls: Int = Int.MAX_VALUE,
) : BootstrapFaultHook {
    private val boundaryCalls = mutableMapOf<String, Int>()

    private fun hit(name: String) {
        val count = (boundaryCalls[name] ?: 0) + 1
        boundaryCalls[name] = count
        if (name == boundary && count <= failFirstNCalls) throw RuntimeException("injected $boundary fault")
    }

    override fun beforeLegacyRecovery() = hit("beforeLegacyRecovery")
    override fun afterLegacyRecovery() = hit("afterLegacyRecovery")
    override fun beforeLegacyCheckpoint() = hit("beforeLegacyCheckpoint")
    override fun afterLegacyCheckpoint() = hit("afterLegacyCheckpoint")
    override fun beforeDatabaseCopy() = hit("beforeDatabaseCopy")
    override fun afterDatabaseCopy() = hit("afterDatabaseCopy")
    override fun beforeAttachmentCopy() = hit("beforeAttachmentCopy")
    override fun afterAttachmentCopy() = hit("afterAttachmentCopy")
    override fun beforeCopyValidation() = hit("beforeCopyValidation")
    override fun afterCopyValidation() = hit("afterCopyValidation")
    override fun beforeRecordWrite() = hit("beforeRecordWrite")
    override fun afterRecordWrite() = hit("afterRecordWrite")
}

/** Captures the legacy source hashes at the post-checkpoint frozen baseline. */
private class CaptureBaselineHook(
    private val legacyDb: File,
    private val legacyAttachments: File,
) : BootstrapFaultHook {
    var baselineDb: String? = null
    var baselineAttachments: Map<String, String>? = null

    override fun afterLegacyCheckpoint() {
        baselineDb = legacyDb.inputStream().use { com.paherdin.record.core.data.AttachmentValidator.sha256(it) }
        baselineAttachments = hashTreeOf(legacyAttachments)
    }

    private fun hashTreeOf(dir: File): Map<String, String> {
        if (!dir.isDirectory) return emptyMap()
        val result = LinkedHashMap<String, String>()
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).path }.forEach { f ->
            result[f.relativeTo(dir).path.replace('\\', '/')] = f.inputStream().use { com.paherdin.record.core.data.AttachmentValidator.sha256(it) }
        }
        return result
    }
}

/**
 * P5 criterion 14 / `GENERATION_BOOTSTRAP` entry tests. P3's coordinator
 * tests retain the version-1/root-layout protocol; this suite owns the later
 * production-startup conversion into a pointer-owned generation.
 */
@RunWith(AndroidJUnit4::class)
class LegacyGenerationBootstrapTest {

    private lateinit var context: Context
    private lateinit var recordFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        VaultSessionRegistry.resetForTest()
        context.deleteDatabase("record.db")
        File(context.filesDir, "attachments").deleteRecursively()
        File(context.filesDir, "migration").deleteRecursively()
        File(context.filesDir, "recovery").deleteRecursively()
        File(context.filesDir, "vaults").deleteRecursively()
        recordFile = RecordRepository.recoveryRecordFile(context)
    }

    @Test
    fun freshProductionOpenPublishesV2PointerBeforeRoomCreatesDatabase() {
        val result = RecordRepository.open(context)
        assertTrue("expected Success, got $result", result is RepositoryResult.Success)
        (result as RepositoryResult.Success).value.close()

        val record = ActivationRecordStore(recordFile).read()!!
        assertEquals(MigrationRecoveryState.MIGRATION_HEALTHY, record.migrationRecoveryState)
        assertEquals(GenerationState.HEALTHY, record.generationActivation.state)
        val activeDb = GenerationManager(context).databaseFile(record.generationActivation.activeGenerationId)
        assertTrue("active generation database missing", activeDb.isFile)
        assertFalse("completed fresh generation must clear its incomplete marker", File(activeDb.parentFile, ".incomplete").exists())
        assertFalse("fresh production open must not create the legacy root database", context.getDatabasePath("record.db").exists())
    }

    @Test
    fun healthyV1RecordMigratesBeforeCopyAndLeavesLegacySourceAuthoritativeUntilPublish() {
        createV1Fixture()
        MigrationRecoveryRecordStore(recordFile).write(
            MigrationRecord(1, MigrationRecoveryState.MIGRATION_HEALTHY, null),
        )

        val legacyDb = context.getDatabasePath("record.db")
        val result = bootstrap().classify()
        assertTrue("expected Ready, got $result", result is BootstrapResult.Ready)
        result as BootstrapResult.Ready

        assertTrue("P5 must retain the legacy source", legacyDb.isFile)
        assertEquals(2L, scalar(legacyDb, "PRAGMA user_version"))
        val activeDb = GenerationManager(context).databaseFile(result.record.generationActivation.activeGenerationId)
        assertEquals(2L, scalar(activeDb, "PRAGMA user_version"))
        assertEquals(1L, scalar(activeDb, "SELECT COUNT(*) FROM `AccountEntry`"))
        assertFalse("bootstrap must publish only a complete generation", File(activeDb.parentFile, ".incomplete").exists())
        assertEquals(result.record, ActivationRecordStore(recordFile).read())
    }

    @Test
    fun noRecordV1DatabaseIsBootstrapInputRatherThanFreshState() {
        createV1Fixture()

        val result = bootstrap().classify()
        assertTrue("expected Ready, got $result", result is BootstrapResult.Ready)
        result as BootstrapResult.Ready

        val activeDb = GenerationManager(context).databaseFile(result.record.generationActivation.activeGenerationId)
        assertEquals(2L, scalar(activeDb, "PRAGMA user_version"))
        assertEquals(2L, scalar(activeDb, "SELECT COUNT(*) FROM `EntityRef`"))
        assertTrue("legacy database must be retained", context.getDatabasePath("record.db").isFile)
    }

    @Test
    fun repeatedProductionOpenReturnsOneLiveRepositoryIdentity() {
        val first = (RecordRepository.open(context) as RepositoryResult.Success).value
        val second = (RecordRepository.open(context) as RepositoryResult.Success).value
        try {
            assertSame(first, second)
        } finally {
            first.close()
            if (second !== first) second.close()
        }
    }

    @Test
    fun restartCompletesPointerOwnedFreshGenerationBeforeNormalUse() {
        val generationId = "gen-00000000-0000-0000-0000-000000000099"
        val manager = GenerationManager(context)
        val dir = manager.createGeneration(generationId)
        File(dir, ".incomplete").writeText("fresh")
        ActivationRecordStore(recordFile).write(
            ActivationRecord(
                migrationRecoveryState = MigrationRecoveryState.MIGRATION_HEALTHY,
                migration = null,
                generationActivation = GenerationActivation(
                    activeGenerationId = generationId,
                    previousGenerationId = null,
                    candidateGenerationId = null,
                    retiringGenerationId = null,
                    state = GenerationState.HEALTHY,
                ),
            ),
        )

        val result = RecordRepository.open(context)
        assertTrue("pointer-owned fresh restart must resume, got $result", result is RepositoryResult.Success)
        (result as RepositoryResult.Success).value.close()
        assertTrue(manager.databaseFile(generationId).isFile)
        assertFalse(File(dir, ".incomplete").exists())
    }

    // ---- GENERATION_BOOTSTRAP fault matrix (three-scenario protocol) ----

    @Test
    fun faultBeforeRecordWriteLeavesLegacyAuthoritativeAndRetries() {
        createV1Fixture()
        MigrationRecoveryRecordStore(recordFile).write(
            MigrationRecord(1, MigrationRecoveryState.MIGRATION_HEALTHY, null),
        )
        // Fail once at the last durable boundary, then restart.
        assertThrows(RuntimeException::class.java) {
            bootstrap(FailingBootstrapHook("beforeRecordWrite", failFirstNCalls = 1)).classify()
        }
        // Crash before the v2 write: no v2 pointer, legacy source still authoritative.
        val v2Before = ActivationRecordStore(recordFile).recordVersion()
        assertTrue("no v2 record before restart, was $v2Before", v2Before == null || v2Before == 1)
        assertTrue(context.getDatabasePath("record.db").isFile)

        val result = bootstrap().classify()
        assertTrue("restart must complete, got $result", result is BootstrapResult.Ready)
        result as BootstrapResult.Ready
        assertTrue(context.getDatabasePath("record.db").isFile)
        assertEquals(
            result.record,
            ActivationRecordStore(recordFile).read(),
        )
    }

    @Test
    fun repeatedFaultNeverPublishesAndRetainsSource() {
        createV1Fixture()
        MigrationRecoveryRecordStore(recordFile).write(
            MigrationRecord(1, MigrationRecoveryState.MIGRATION_HEALTHY, null),
        )
        val hook = FailingBootstrapHook("beforeRecordWrite") // always fails
        assertThrows(RuntimeException::class.java) { bootstrap(hook).classify() }
        assertThrows(RuntimeException::class.java) { bootstrap(hook).classify() }
        val v2 = ActivationRecordStore(recordFile).recordVersion()
        assertTrue("never publishes a v2 pointer, was $v2", v2 == null || v2 == 1)
        assertTrue(context.getDatabasePath("record.db").isFile)
    }

    @Test
    fun completesThenRepeatsIdempotentlyWithoutRecopy() {
        createV1Fixture()
        MigrationRecoveryRecordStore(recordFile).write(
            MigrationRecord(1, MigrationRecoveryState.MIGRATION_HEALTHY, null),
        )
        val first = bootstrap().classify()
        assertTrue(first is BootstrapResult.Ready)
        val legacyDbHash = sha256(context.getDatabasePath("record.db"))
        val firstActive = (first as BootstrapResult.Ready).record.generationActivation.activeGenerationId

        val second = bootstrap().classify()
        assertTrue("repeat must hit the v2 path, got $second", second is BootstrapResult.Ready)
        assertEquals(firstActive, (second as BootstrapResult.Ready).record.generationActivation.activeGenerationId)
        assertEquals("legacy source unchanged by repeat", legacyDbHash, sha256(context.getDatabasePath("record.db")))
    }

    @Test
    fun frozenSourceHashesUnchangedThroughCopyValidationAndPublish() {
        createV1Fixture()
        MigrationRecoveryRecordStore(recordFile).write(
            MigrationRecord(1, MigrationRecoveryState.MIGRATION_HEALTHY, null),
        )
        val capture = CaptureBaselineHook(context.getDatabasePath("record.db"), File(context.filesDir, "attachments"))
        val result = bootstrap(capture).classify()
        assertTrue("expected Ready, got $result", result is BootstrapResult.Ready)

        assertEquals("frozen db hash unchanged through publish", capture.baselineDb, sha256(context.getDatabasePath("record.db")))
        assertEquals("frozen attachment hashes unchanged through publish", capture.baselineAttachments, hashTree(File(context.filesDir, "attachments")))
    }

    @Test
    fun concurrentFirstOpenRunsBootstrapOnceAndReturnsOneIdentity() {
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        val identities = ConcurrentHashMap.newKeySet<RecordRepository>()
        val failures = AtomicInteger()
        val futures = (0 until threads).map {
            pool.submit {
                barrier.await()
                when (val r = RecordRepository.open(context)) {
                    is RepositoryResult.Success -> identities.add(r.value)
                    else -> failures.incrementAndGet()
                }
            }
        }
        try {
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
        assertEquals("no open failed", 0, failures.get())
        assertEquals("exactly one live identity", 1, identities.size)
        val repo = identities.first()
        repo.close()
        val record = ActivationRecordStore(recordFile).read()!!
        val manager = GenerationManager(context)
        val activeId = record.generationActivation.activeGenerationId
        assertTrue(manager.databaseFile(activeId).isFile)
        assertFalse("completed fresh generation clears its marker", File(manager.generationDir(activeId), ".incomplete").exists())
        assertFalse("concurrent fresh open must not create the legacy root database", context.getDatabasePath("record.db").exists())
    }

    @Test
    fun differentVaultRootsRemainIndependent() {
        val rootA = File(context.cacheDir, "root-a-${System.nanoTime()}")
        val rootB = File(context.cacheDir, "root-b-${System.nanoTime()}")
        rootA.mkdirs()
        rootB.mkdirs()
        val nameA = "indep_${System.nanoTime()}_a.db"
        val nameB = "indep_${System.nanoTime()}_b.db"

        val repoA1 = (VaultSessionRegistry.open(rootA) { RepositoryResult.Success(openForTest(context, nameA)) } as RepositoryResult.Success).value
        val repoB = (VaultSessionRegistry.open(rootB) { RepositoryResult.Success(openForTest(context, nameB)) } as RepositoryResult.Success).value
        val repoA2 = (VaultSessionRegistry.open(rootA) { RepositoryResult.Success(openForTest(context, nameA)) } as RepositoryResult.Success).value
        try {
            assertSame("same root returns one identity", repoA1, repoA2)
            assertNotSame("distinct roots are independent", repoA1, repoB)
            repoA1.close() // release root A only
            assertSame("root B unaffected by releasing root A", repoB, (VaultSessionRegistry.open(rootB) { RepositoryResult.Success(openForTest(context, nameB)) } as RepositoryResult.Success).value)
        } finally {
            repoA1.close()
            repoB.close()
        }
    }

    private fun bootstrap(hook: BootstrapFaultHook = BootstrapFaultHook.NO_OP): LegacyGenerationBootstrap {        val legacyStore = MigrationRecoveryRecordStore(recordFile)
        return LegacyGenerationBootstrap(
            context = context,
            activationStore = ActivationRecordStore(recordFile),
            legacyStore = legacyStore,
            generationManager = GenerationManager(context),
            coordinator = MigrationCoordinator(context, legacyStore),
            hook = hook,
        )
    }

    private fun createV1Fixture() {
        val file = context.getDatabasePath("record.db")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.CREATE_IF_NECESSARY).use { db ->
            AttachmentTestFixtures.createV1Schema(db)
            db.execSQL("INSERT INTO `EntityRef` VALUES ('ref-t1', 'TRANSACTION', 1, 1, NULL)")
            db.execSQL("INSERT INTO `Transaction` VALUES ('ref-t1', 'EXPENSE')")
            db.execSQL("INSERT INTO `EntityRef` VALUES ('ref-n1', 'NOTE', 2, 2, NULL)")
            db.execSQL("INSERT INTO `Note` VALUES ('ref-n1')")
            db.execSQL("INSERT INTO `Account` VALUES ('acc-1', 'Wallet', 'CNY')")
            db.execSQL("INSERT INTO `AccountEntry` VALUES ('entry-1', 'ref-t1', 'acc-1', -5000)")
            db.execSQL("INSERT INTO `TransactionNoteRelation` VALUES ('ref-t1', 'ref-n1')")
        }
    }

    private fun sha256(file: File): String =
        file.inputStream().use { com.paherdin.record.core.data.AttachmentValidator.sha256(it) }

    private fun hashTree(dir: File): Map<String, String> {
        if (!dir.isDirectory) return emptyMap()
        val result = LinkedHashMap<String, String>()
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).path }.forEach { f ->
            result[f.relativeTo(dir).path.replace('\\', '/')] = sha256(f)
        }
        return result
    }

    private fun scalar(file: File, sql: String): Long =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, null).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
        }
}

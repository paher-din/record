package com.paherdin.record2.backup

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.ai.security.AiMode
import com.paherdin.record2.ai.security.CredentialStatus
import com.paherdin.record2.ai.security.CredentialVault
import com.paherdin.record2.proposals.CommitStatus
import com.paherdin.record2.proposals.ProposalError
import com.paherdin.record2.proposals.ProposalException
import com.paherdin.record2.proposals.ProposalRunContext
import com.paherdin.record2.proposals.ProposalState
import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceDraft
import com.paherdin.record2.records.FinanceInput
import com.paherdin.record2.records.FinanceRecord
import com.paherdin.record2.sessions.AgentHistoryError
import com.paherdin.record2.sessions.AgentHistoryException
import com.paherdin.record2.sessions.RunOutcome
import com.paherdin.record2.sessions.RunState
import com.paherdin.record2.sessions.MessageResultKind
import com.paherdin.record2.storage.AiControlEntity
import com.paherdin.record2.storage.RecordDataStateEntity
import com.paherdin.record2.storage.RecordDatabase
import com.paherdin.record2.storage.RoomAgentHistoryService
import com.paherdin.record2.storage.RoomAiSecurityService
import com.paherdin.record2.storage.RoomCommitService
import com.paherdin.record2.storage.RoomFinanceService
import com.paherdin.record2.storage.RoomNoteService
import com.paherdin.record2.storage.RoomProposalService
import com.paherdin.record2.storage.RoomRestoreStore
import com.paherdin.record2.storage.toEntity
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = Clock.fixed(
        Instant.ofEpochMilli(BackupTestDataFactory.FINISH + 10_000),
        ZoneOffset.UTC,
    )
    private val staging = File(context.noBackupFilesDir, "g1-backup-contract")
    private val databases = ArrayList<RecordDatabase>()
    private val databaseNames = ArrayList<String>()

    @After
    fun cleanup() {
        databases.forEach { runCatching { it.close() } }
        databaseNames.forEach { context.deleteDatabase(it) }
        staging.deleteRecursively()
    }

    @Test
    fun roundTripRestoresBusinessChatAndEligibility() = runBlocking {
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-roundtrip-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val sourcePath = context.getDatabasePath(sourceName)
        val before = tables(sourcePath)
        val zip = File(staging, "roundtrip.zip")
        val gate = DataOperationGate()
        export(sourcePath, zip, gate)
        assertEquals(before, tables(sourcePath))

        val targetName = "g1-roundtrip-target-${UUID.randomUUID()}.db"
        val target = open(targetName)
        val targetPath = context.getDatabasePath(targetName)
        val result = restore(target, targetPath, zip, DataOperationGate())

        val restored = ReadOnlyBackupSnapshot.read(targetPath, clock.millis(), NoopExportResourceMonitor())
        assertUnchanged(data, restored.content, result.receipt.restoredAt)
        assertEquals(data.content.operations.associateBy { it.id }, restored.content.operations.associateBy { it.id })
        assertEquals(data.content.notes.associateBy { it.id }, restored.content.notes.associateBy { it.id })
        assertEquals(data.content.links.associateBy { it.transactionId + "|" + it.noteId }, restored.content.links.associateBy { it.transactionId + "|" + it.noteId })
        assertEquals(data.content.drafts.associateBy { it.slot }, restored.content.drafts.associateBy { it.slot })
        assertEquals(data.content.sessions.associateBy { it.id }, restored.content.sessions.associateBy { it.id })
        assertEquals(data.content.messages.associateBy { it.id }, restored.content.messages.associateBy { it.id })
        assertEquals(data.content.references.associateBy { it.messageId + "|" + it.ordinal }, restored.content.references.associateBy { it.messageId + "|" + it.ordinal })

        assertOwnerReRead(target, data, result)
        val startup = RestoreStartupGate(targetPath, clock).check()
        assertEquals(StartupKind.NORMAL, startup.kind)
        assertEquals(result.restoredGeneration, startup.receipt?.restoredGeneration)
        target.close()
    }

    @Test
    fun gateRejectsLateWritesWithoutChangingAll14Tables() = runBlocking {
        val data = BackupTestDataFactory.create()
        val name = "g1-gate-${UUID.randomUUID()}.db"
        val database = open(name)
        seed(database, data)
        val path = context.getDatabasePath(name)
        val gate = DataOperationGate()
        val notes = RoomNoteService(database, clock).also { it.dataOperationGate = gate }
        val finance = RoomFinanceService(database, clock).also { it.dataOperationGate = gate }
        val history = RoomAgentHistoryService(database, clock).also { it.dataOperationGate = gate }
        val commits = RoomCommitService(database, clock).also { it.dataOperationGate = gate }
        val security = RoomAiSecurityService(database, NoopVault(), clock = clock)
            .also { it.dataOperationGate = gate }
        val before = tables(path)

        val lease = gate.beginRestore()
        try {
            assertThrows(DataOperationBusyException::class.java) {
                runBlocking { notes.save(data.generation, UUID.randomUUID().toString(), null, "late", clock.millis()) }
            }
            assertThrows(DataOperationBusyException::class.java) {
                runBlocking { finance.discardDraft(data.generation, BackupTestDataFactory.DRAFT_ID, 1) }
            }
            assertThrows(DataOperationBusyException::class.java) {
                runBlocking {
                    history.completeRun(
                        data.completedRunId,
                        BackupTestDataFactory.RECEIVER,
                        1,
                        data.owner,
                        RunOutcome(MessageResultKind.Answer, "late"),
                    )
                }
            }
            assertThrows(DataOperationBusyException::class.java) {
                runBlocking { commits.execute(data.pendingOperationId) }
            }
            assertThrows(DataOperationBusyException::class.java) {
                runBlocking { security.setMode(AiMode.OFF) }
            }
            assertEquals(before, tables(path))
        } finally {
            lease.close()
        }
        assertNotNull(notes.save(data.generation, UUID.randomUUID().toString(), null, "after", clock.millis()))
        database.close()
    }

    @Test
    fun authEpochBoundaryAndReceiptStartup() = runBlocking {
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-epoch-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val zip = File(staging, "epoch.zip")
        export(context.getDatabasePath(sourceName), zip, DataOperationGate())

        val refusedName = "g1-epoch-refused-${UUID.randomUUID()}.db"
        val refused = open(refusedName)
        seedTargetControl(refused, Long.MAX_VALUE - 1)
        val refusedPath = context.getDatabasePath(refusedName)
        val refusedBefore = tables(refusedPath)
        val refusedService = RestoreService(
            RoomRestoreStore(refused, clock), refusedPath, staging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE },
        )
        val rejected = assertThrows(BackupException::class.java) {
            runBlocking { refusedService.preflight(FileSource(zip)) }
        }
        assertEquals(BackupError.TargetInvalid, rejected.code)
        assertEquals(refusedBefore, tables(refusedPath))
        refused.close()

        val acceptedName = "g1-epoch-accepted-${UUID.randomUUID()}.db"
        val accepted = open(acceptedName)
        seedTargetControl(accepted, Long.MAX_VALUE - 2)
        val acceptedPath = context.getDatabasePath(acceptedName)
        val acceptedService = RestoreService(
            RoomRestoreStore(accepted, clock), acceptedPath, staging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE },
        )
        val candidate = acceptedService.preflight(FileSource(zip))
        val result = acceptedService.restore(candidate)
        assertEquals(Long.MAX_VALUE - 1, result.authEpoch)
        assertEquals(Long.MAX_VALUE - 1, accepted.aiControl().control()?.authEpoch)

        val startup = RestoreStartupGate(acceptedPath, clock)
        assertEquals(StartupKind.NORMAL, startup.check().kind)
        accepted.openHelper.writableDatabase.execSQL(
            "UPDATE record_data_state SET restoreReceipt = '{}' WHERE id = 1",
        )
        val beforeBad = tables(acceptedPath)
        assertEquals(StartupKind.RECOVERY_REQUIRED, startup.check().kind)
        assertEquals(beforeBad, tables(acceptedPath))
        accepted.openHelper.writableDatabase.execSQL(
            "UPDATE record_data_state SET restoreReceipt = NULL WHERE id = 1",
        )
        assertEquals(StartupKind.NORMAL, startup.check().kind)
        accepted.close()
    }

    @Test
    fun exportUsesWalAndDoesNotBlockConcurrentWriter() = runBlocking {
        staging.mkdirs()
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-export-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val path = context.getDatabasePath(sourceName)
        val notes = RoomNoteService(source, clock)
        val noteId = UUID.randomUUID().toString()
        var concurrentDone = false
        var writerAlive = true
        val zip = File(staging, "concurrent.zip")
        val exporter = BackupExporter(
            path, staging, DataOperationGate(), AppInfo(1, "0.1.0"), clock,
            availableBytes = { Long.MAX_VALUE },
            monitorFactory = { _, _ -> NoopExportResourceMonitor() },
            snapshotHook = {
                val writer = Thread {
                    runBlocking {
                        notes.save(data.generation, noteId, null, "concurrent", clock.millis())
                    }
                    concurrentDone = true
                }
                writer.start()
                writer.join(10_000)
                writerAlive = writer.isAlive
            },
        )
        exporter.export(FileTarget(zip), zip.name)
        assertTrue(concurrentDone)
        assertFalse(writerAlive)
        val read = LogicalZipReader.read(zip)
        assertTrue(read.backup.content.notes.none { it.id == noteId })
        assertNotNull(notes.visible(data.generation, noteId))
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val mode = db.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            assertEquals("wal", mode?.lowercase())
        }
        source.close()
    }

    @Test
    fun cancelledPreflightCleansStagingAndLeavesDatabaseUntouched() = runBlocking {
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-cancel-preflight-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val zip = File(staging, "cancel-preflight.zip")
        export(context.getDatabasePath(sourceName), zip, DataOperationGate())
        val bytes = zip.readBytes()

        val targetName = "g1-cancel-preflight-target-${UUID.randomUUID()}.db"
        val target = open(targetName)
        val targetPath = context.getDatabasePath(targetName)
        val privateStaging = File(context.noBackupFilesDir, "g1-preflight-cancel-${UUID.randomUUID()}")
        privateStaging.mkdirs()
        val slow = object : BackupSource {
            override val displayName: String = "slow.zip"
            override suspend fun copyTo(destination: File, maxBytes: Long): Long {
                var offset = 0
                destination.outputStream().use { output ->
                    while (offset < bytes.size) {
                        currentCoroutineContext().ensureActive()
                        val end = minOf(offset + 32_768, bytes.size)
                        output.write(bytes, offset, end - offset)
                        offset = end
                        delay(2)
                    }
                }
                return bytes.size.toLong()
            }
        }
        val service = RestoreService(
            RoomRestoreStore(target, clock), targetPath, privateStaging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE },
        )
        val before = tables(targetPath)
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            started.complete(Unit)
            runCatching { service.preflight(slow) }
        }
        started.await()
        delay(10)
        job.cancelAndJoin()
        assertEquals(before, tables(targetPath))
        assertTrue(privateStaging.listFiles().isNullOrEmpty())
        privateStaging.deleteRecursively()
        source.close()
        target.close()
    }

    @Test
    fun cancellationAtCommitKeepsCommittedReceipt() = runBlocking {
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-cancel-commit-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val zip = File(staging, "cancel-commit.zip")
        export(context.getDatabasePath(sourceName), zip, DataOperationGate())

        val targetName = "g1-cancel-commit-target-${UUID.randomUUID()}.db"
        val target = open(targetName)
        val targetPath = context.getDatabasePath(targetName)
        var job: Job? = null
        val service = RestoreService(
            RoomRestoreStore(target, clock), targetPath, staging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE },
            checkpoint = { point -> if (point == RestoreCheckpoint.BEFORE_COMMIT) job?.cancel() },
        )
        val candidate = service.preflight(FileSource(zip))
        job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runCatching { service.restore(candidate) }
        }
        job.start()
        job.join()

        val receipt = service.committedReceipt(candidate)
        assertNotNull(receipt)
        assertEquals(receipt!!.restoredGeneration, target.records().dataState()?.dataGeneration)
        assertEquals(StartupKind.NORMAL, RestoreStartupGate(targetPath, clock).check().kind)
        source.close()
        target.close()
    }

    @Test
    fun gateIsReleasedWhenProgressThrows() = runBlocking {
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-progress-throw-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val zip = File(staging, "progress-throw.zip")
        export(context.getDatabasePath(sourceName), zip, DataOperationGate())

        val targetName = "g1-progress-throw-target-${UUID.randomUUID()}.db"
        val target = open(targetName)
        val targetPath = context.getDatabasePath(targetName)
        val gate = DataOperationGate()
        val service = RestoreService(
            RoomRestoreStore(target, clock), targetPath, staging, gate, clock,
            availableBytes = { Long.MAX_VALUE },
        )
        val candidate = service.preflight(FileSource(zip))
        val before = tables(targetPath)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                service.restore(candidate) { point ->
                    if (point == RestoreProgress.PREPARING) error("progress callback failed")
                }
            }
        }
        assertFalse(gate.isRestoreInProgress)
        runBlocking { gate.withWrite { Unit } }
        assertEquals(before, tables(targetPath))

        source.close()
        target.close()
    }

    @Test
    fun gateIsReleasedWhenStagingCleanupThrows() = runBlocking {
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-cleanup-throw-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val zip = File(staging, "cleanup-throw.zip")
        export(context.getDatabasePath(sourceName), zip, DataOperationGate())

        val targetName = "g1-cleanup-throw-target-${UUID.randomUUID()}.db"
        val target = open(targetName)
        val targetPath = context.getDatabasePath(targetName)
        val gate = DataOperationGate()
        val service = RestoreService(
            RoomRestoreStore(target, clock), targetPath, staging, gate, clock,
            availableBytes = { Long.MAX_VALUE },
            cleanupStaging = { throw IllegalStateException("staging cleanup failed") },
        )
        val candidate = service.preflight(FileSource(zip))
        val before = tables(targetPath)
        // Corrupt the source after preflight so the restore fails before commit; cleanup also
        // throws, and the gate must still be released by the outer finally.
        zip.writeBytes(zip.readBytes().copyOf(zip.length().toInt() / 2))
        assertThrows(BackupException::class.java) {
            runBlocking { service.restore(candidate) }
        }
        assertFalse(gate.isRestoreInProgress)
        runBlocking { gate.withWrite { Unit } }
        assertEquals(before, tables(targetPath))
        staging.listFiles()?.forEach { it.deleteRecursively() }

        source.close()
        target.close()
    }

    @Test
    fun coordinatorReusesCommittedReceiptWithoutASecondImport() = runBlocking {
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-coordinator-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val zip = File(staging, "coordinator.zip")
        export(context.getDatabasePath(sourceName), zip, DataOperationGate())

        val targetName = "g1-coordinator-target-${UUID.randomUUID()}.db"
        val target = open(targetName)
        val targetPath = context.getDatabasePath(targetName)
        val engine = RestoreService(
            RoomRestoreStore(target, clock), targetPath, staging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE },
        )
        val candidate = engine.preflight(FileSource(zip))
        val coordinator = RestoreCoordinator(engine)
        val first = coordinator.confirm(candidate) as RestoreStart.Started
        val result = withTimeout(30_000) { first.handle.await() }
        assertEquals(candidate.manifest.sourceGeneration, result.receipt.sourceGeneration)
        assertEquals(StartupKind.NORMAL, RestoreStartupGate(targetPath, clock).check().kind)
        val afterFirst = tables(targetPath)

        // A new coordinator/engine represents a fresh process observation; the persisted receipt
        // must prove the same commit without running the import again.
        val secondEngine = RestoreService(
            RoomRestoreStore(target, clock), targetPath, staging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE },
        )
        val second = RestoreCoordinator(secondEngine)
        val restarted = second.confirm(candidate) as RestoreStart.Started
        val recovered = withTimeout(30_000) { restarted.handle.await() }
        assertEquals(result.receipt, recovered.receipt)
        assertTrue(recovered.transformStats is RestoreTransformStats.Unknown)
        assertTrue(second.currentStatus().recoveredFromReceipt)
        assertEquals(afterFirst, tables(targetPath))

        coordinator.close()
        second.close()
        source.close()
        target.close()
    }

    @Test
    fun coordinatorSurvivesObserverCancellationAndDuplicateConfirmation() = runBlocking {
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-coordinator-observer-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val zip = File(staging, "coordinator-observer.zip")
        export(context.getDatabasePath(sourceName), zip, DataOperationGate())

        val targetName = "g1-coordinator-observer-target-${UUID.randomUUID()}.db"
        val target = open(targetName)
        val targetPath = context.getDatabasePath(targetName)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = RestoreService(
            RoomRestoreStore(target, clock), targetPath, staging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE },
            checkpoint = { point ->
                if (point == RestoreCheckpoint.AFTER_DELETE) {
                    entered.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            },
        )
        val firstCandidate = engine.preflight(FileSource(zip))
        val secondCandidate = engine.preflight(FileSource(zip))
        val coordinator = RestoreCoordinator(engine)
        try {
            val started = coordinator.confirm(firstCandidate) as RestoreStart.Started
            assertTrue(entered.await(20, TimeUnit.SECONDS))

            // The page observer dies while the restore transaction is in progress.
            val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val observer = observerScope.async { runCatching { started.handle.await() } }
            observer.cancelAndJoin()

            val duplicate = coordinator.confirm(firstCandidate)
            assertTrue(duplicate is RestoreStart.Existing)
            assertEquals(
                started.handle.operationId,
                (duplicate as RestoreStart.Existing).handle.operationId,
            )
            val other = coordinator.confirm(secondCandidate)
            assertTrue(other is RestoreStart.Busy)

            release.countDown()
            val result = withTimeout(30_000) { started.handle.await() }
            assertEquals(firstCandidate.manifest.sourceGeneration, result.receipt.sourceGeneration)
            assertEquals(RestorePhase.FINISHED, coordinator.currentStatus().phase)
            assertEquals(StartupKind.NORMAL, RestoreStartupGate(targetPath, clock).check().kind)
        } finally {
            release.countDown()
            coordinator.close()
            source.close()
            target.close()
        }
    }

    @Test
    fun secondRestoreWithDifferentBackupSucceeds() = runBlocking {
        val dataA = BackupTestDataFactory.create()
        val dataB = dataA.copy(
            generation = UUID.randomUUID().toString(),
            content = dataA.content.copy(
                notes = dataA.content.notes.map {
                    if (it.id == BackupTestDataFactory.NOTE_ACTIVE) it.copy(body = "backup B note") else it
                },
            ),
        )
        val sourceAName = "g1-a-b-source-a-${UUID.randomUUID()}.db"
        val sourceBName = "g1-a-b-source-b-${UUID.randomUUID()}.db"
        val targetName = "g1-a-b-target-${UUID.randomUUID()}.db"
        val aDb = open(sourceAName)
        val bDb = open(sourceBName)
        seed(aDb, dataA)
        seed(bDb, dataB)
        val aZip = File(staging, "a-b-a.zip")
        val bZip = File(staging, "a-b-b.zip")
        export(context.getDatabasePath(sourceAName), aZip, DataOperationGate())
        export(context.getDatabasePath(sourceBName), bZip, DataOperationGate())
        val targetDb = open(targetName)
        val targetPath2 = context.getDatabasePath(targetName)
        val first = RestoreService(
            RoomRestoreStore(targetDb, clock), targetPath2, staging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE },
        )
        val firstCoordinator = RestoreCoordinator(first)
        val candidateA = first.preflight(FileSource(aZip))
        val resultA = withTimeout(30_000) {
            (firstCoordinator.confirm(candidateA) as RestoreStart.Started).handle.await()
        }
        firstCoordinator.close()
        val generationA = targetDb.records().dataState()!!.dataGeneration
        assertEquals(resultA.restoredGeneration, generationA)

        val second = RestoreService(
            RoomRestoreStore(targetDb, clock), targetPath2, staging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE },
        )
        val secondCoordinator = RestoreCoordinator(second)
        val candidateB = second.preflight(FileSource(bZip))
        assertEquals(generationA, candidateB.targetGeneration)
        val resultB = withTimeout(30_000) {
            (secondCoordinator.confirm(candidateB) as RestoreStart.Started).handle.await()
        }
        secondCoordinator.close()
        assertTrue(resultB.restoredGeneration != generationA)
        assertEquals(resultB.restoredGeneration, targetDb.records().dataState()!!.dataGeneration)
        val body = targetDb.query(
            "SELECT body FROM note WHERE id = ?", arrayOf(BackupTestDataFactory.NOTE_ACTIVE),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("backup B note", body)
        assertEquals(StartupKind.NORMAL, RestoreStartupGate(targetPath2, clock).check().kind)

        aDb.close()
        bDb.close()
        targetDb.close()
    }

    @Test
    fun freshRestoreOfSameBackupReplacesDataAddedAfterTheFirstRestore() = runBlocking {
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-same-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val zip = File(staging, "same-source.zip")
        export(context.getDatabasePath(sourceName), zip, DataOperationGate())

        val targetName = "g1-same-target-${UUID.randomUUID()}.db"
        val target = open(targetName)
        val targetPath = context.getDatabasePath(targetName)
        val first = RestoreService(
            RoomRestoreStore(target, clock), targetPath, staging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE },
        )
        val firstCoordinator = RestoreCoordinator(first)
        val candidate = first.preflight(FileSource(zip))
        withTimeout(30_000) {
            (firstCoordinator.confirm(candidate) as RestoreStart.Started).handle.await()
        }
        firstCoordinator.close()
        val generation = target.records().dataState()!!.dataGeneration
        val extraId = UUID.randomUUID().toString()
        assertNotNull(
            RoomNoteService(target, clock).save(
                generation, extraId, null, "added after restore", BackupTestDataFactory.START,
            ),
        )

        val second = RestoreService(
            RoomRestoreStore(target, clock), targetPath, staging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE },
        )
        val secondCoordinator = RestoreCoordinator(second)
        val fresh = second.preflight(FileSource(zip))
        assertEquals(generation, fresh.targetGeneration)
        val result = withTimeout(30_000) {
            (secondCoordinator.confirm(fresh) as RestoreStart.Started).handle.await()
        }
        secondCoordinator.close()
        assertTrue(result.restoredGeneration != generation)
        val extraCount = target.query(
            "SELECT COUNT(*) FROM note WHERE id = ?", arrayOf(extraId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        assertEquals(0, extraCount)
        assertEquals(StartupKind.NORMAL, RestoreStartupGate(targetPath, clock).check().kind)

        source.close()
        target.close()
    }

    @Test
    fun malformedReceiptCanBeReplacedByValidBackup() = runBlocking {
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-malformed-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val zip = File(staging, "malformed-source.zip")
        export(context.getDatabasePath(sourceName), zip, DataOperationGate())

        val targetName = "g1-malformed-target-${UUID.randomUUID()}.db"
        val target = open(targetName)
        seed(target, data)
        target.openHelper.writableDatabase.execSQL(
            "UPDATE record_data_state SET restoreReceipt = '{' WHERE id = 1",
        )
        val targetPath = context.getDatabasePath(targetName)
        assertEquals(StartupKind.RECOVERY_REQUIRED, RestoreStartupGate(targetPath, clock).check().kind)

        val service = RestoreService(
            RoomRestoreStore(target, clock), targetPath, staging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE },
        )
        val coordinator = RestoreCoordinator(service)
        val candidate = service.preflight(FileSource(zip))
        assertEquals(target.records().dataState()!!.dataGeneration, candidate.targetGeneration)
        val result = withTimeout(30_000) {
            (coordinator.confirm(candidate) as RestoreStart.Started).handle.await()
        }
        coordinator.close()
        assertTrue(result.restoredGeneration != candidate.targetGeneration)
        assertNotNull(target.records().dataState()?.restoreReceipt)
        assertEquals(StartupKind.NORMAL, RestoreStartupGate(targetPath, clock).check().kind)

        source.close()
        target.close()
    }

    @Test
    fun emojiAndMixedTextCrossChunkBoundary() = runBlocking {
        val data = BackupTestDataFactory.create()
        val body = "中文abc😀".repeat(60_000)
        assertTrue(body.codePointCount(0, body.length) > 262_144)
        val content = data.content.copy(
            notes = data.content.notes.map {
                if (it.id == BackupTestDataFactory.NOTE_ACTIVE) it.copy(body = body) else it
            },
        )
        val sourceName = "g1-emoji-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seedContent(source, content, data.generation, data.owner)
        val sourcePath = context.getDatabasePath(sourceName)
        val zip = File(staging, "emoji.zip")
        export(sourcePath, zip, DataOperationGate())
        assertEquals(
            body,
            LogicalZipReader.read(zip).backup.content.notes
                .first { it.id == BackupTestDataFactory.NOTE_ACTIVE }.body,
        )

        val targetName = "g1-emoji-target-${UUID.randomUUID()}.db"
        val target = open(targetName)
        val targetPath = context.getDatabasePath(targetName)
        restore(target, targetPath, zip, DataOperationGate())
        assertEquals(
            body,
            ReadOnlyBackupSnapshot.read(targetPath, clock.millis(), NoopExportResourceMonitor())
                .content.notes.first { it.id == BackupTestDataFactory.NOTE_ACTIVE }.body,
        )
        source.close()
        target.close()
    }

    @Test
    fun exportSnapshotIsPointInTimeAcrossTables() = runBlocking {
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-atomic-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val path = context.getDatabasePath(sourceName)
        val oldBodies = data.content.notes.associate { it.id to it.body }
        var writerDone = false
        var writerAlive = true
        val zip = File(staging, "atomic.zip")
        val exporter = BackupExporter(
            path, staging, DataOperationGate(), AppInfo(1, "0.1.0"), clock,
            availableBytes = { Long.MAX_VALUE },
            monitorFactory = { _, _ -> NoopExportResourceMonitor() },
            snapshotHook = {
                val writer = Thread {
                    val database = SQLiteDatabase.openDatabase(
                        path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE,
                    )
                    try {
                        database.beginTransaction()
                        try {
                            database.execSQL(
                                "UPDATE note SET body = body || '-new', version = version + 1, updatedAt = ? " +
                                    "WHERE id IN (?, ?)",
                                arrayOf<Any>(
                                    clock.millis() + 1,
                                    BackupTestDataFactory.NOTE_ACTIVE,
                                    BackupTestDataFactory.NOTE_DELETED,
                                ),
                            )
                            database.execSQL(
                                "UPDATE record_data_state SET businessRevision = businessRevision + 1 WHERE id = 1",
                            )
                            database.setTransactionSuccessful()
                        } finally {
                            database.endTransaction()
                        }
                    } finally {
                        database.close()
                    }
                    writerDone = true
                }
                writer.start()
                writer.join(10_000)
                writerAlive = writer.isAlive
            },
        )
        exporter.export(FileTarget(zip), zip.name)
        assertTrue(writerDone)
        assertFalse(writerAlive)
        val read = LogicalZipReader.read(zip)
        assertEquals(oldBodies, read.backup.content.notes.associate { it.id to it.body })
        assertEquals(1L, read.backup.manifest.businessRevision)
        SQLiteDatabase.openDatabase(
            path.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery(
                "SELECT businessRevision FROM record_data_state WHERE id = 1",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2L, cursor.getLong(0))
            }
        }
        source.close()
    }

    @Test
    fun largeNoteBodiesExportAtCursorWindowBoundaries() = runBlocking {
        for (mib in listOf(1, 2, 4, 7)) {
            val data = BackupTestDataFactory.create()
            val body = "界".repeat(mib * 1024 * 1024 / 3)
            val content = data.content.copy(
                notes = data.content.notes.map {
                    if (it.id == BackupTestDataFactory.NOTE_ACTIVE) it.copy(body = body) else it
                },
            )
            val sourceName = "g1-large-note-$mib-${UUID.randomUUID()}.db"
            val source = open(sourceName)
            seedContent(source, content, data.generation, data.owner)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(sourceName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { direct ->
                val statement = direct.compileStatement("SELECT body FROM note WHERE id = ?")
                try {
                    statement.bindString(1, BackupTestDataFactory.NOTE_ACTIVE)
                    assertEquals(body, statement.simpleQueryForString())
                } finally {
                    statement.close()
                }
            }
            val zip = File(staging, "large-note-$mib.zip")
            export(context.getDatabasePath(sourceName), zip, DataOperationGate())
            val read = LogicalZipReader.read(zip)
            assertEquals(
                body,
                read.backup.content.notes.first { it.id == BackupTestDataFactory.NOTE_ACTIVE }.body,
            )
            source.close()
        }
    }

    @Test
    fun emptySegmentsRoundTripThroughRestore() = runBlocking {
        val content = BackupTestDataFactory.seedOnly()
        val sourceName = "g1-empty-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seedContent(source, content, BackupTestDataFactory.GENERATION, BackupTestDataFactory.OWNER)
        val sourcePath = context.getDatabasePath(sourceName)
        val firstZip = File(staging, "empty-first.zip")
        export(sourcePath, firstZip, DataOperationGate())
        assertEmptySegments(firstZip)

        val targetName = "g1-empty-target-${UUID.randomUUID()}.db"
        val target = open(targetName)
        val targetPath = context.getDatabasePath(targetName)
        restore(target, targetPath, firstZip, DataOperationGate())
        val secondZip = File(staging, "empty-second.zip")
        export(targetPath, secondZip, DataOperationGate())
        assertEmptySegments(secondZip)
        val second = LogicalZipReader.read(secondZip)
        assertTrue(second.backup.content.transactions.isEmpty())
        assertTrue(second.backup.content.runs.isEmpty())
        assertTrue(second.backup.content.messages.isEmpty())
        source.close()
        target.close()
    }

    private fun assertEmptySegments(zipFile: File) {
        ZipFile(zipFile).use { zip ->
            for (segment in LogicalSegment.entries) {
                if (segment == LogicalSegment.ACCOUNT || segment == LogicalSegment.CATEGORY) continue
                val entry = zip.getEntry(segment.path)
                assertEquals(ZipEntry.STORED, entry.method)
                assertEquals(0L, entry.compressedSize)
                assertEquals(0L, entry.size)
                assertEquals(0L, entry.crc)
            }
        }
    }

    @Test
    fun savedAndDiscardedDraftsAreExcludedAndRestorable() = runBlocking {
        for (state in listOf("SAVED", "DISCARDED")) {
            val data = BackupTestDataFactory.create()
            val content = data.content.copy(
                drafts = data.content.drafts.map { it.copy(state = state) },
            )
            val sourceName = "g1-draft-$state-${UUID.randomUUID()}.db"
            val source = open(sourceName)
            seedContent(source, content, data.generation, data.owner)
            val sourcePath = context.getDatabasePath(sourceName)
            val zip = File(staging, "draft-$state.zip")
            export(sourcePath, zip, DataOperationGate())
            val read = LogicalZipReader.read(zip)
            assertTrue(read.backup.content.drafts.isEmpty())

            val targetName = "g1-draft-target-$state-${UUID.randomUUID()}.db"
            val target = open(targetName)
            val targetPath = context.getDatabasePath(targetName)
            val result = restore(target, targetPath, zip, DataOperationGate())
            assertNotNull(result.restoredGeneration)
            val restored = ReadOnlyBackupSnapshot.read(targetPath, clock.millis(), NoopExportResourceMonitor())
            assertTrue(restored.content.drafts.isEmpty())
            source.close()
            target.close()
        }
    }

    @Test
    fun realDraftLifecycleIsExcludedAndRestorable() = runBlocking {
        for (state in listOf("SAVED", "DISCARDED")) {
            val content = BackupTestDataFactory.seedOnly()
            val sourceName = "g1-real-draft-$state-${UUID.randomUUID()}.db"
            val source = open(sourceName)
            seedContent(
                source, content,
                BackupTestDataFactory.GENERATION, BackupTestDataFactory.OWNER,
            )
            val finance = RoomFinanceService(source, clock)
            val input = FinanceInput(
                amountText = "12.34",
                direction = FinanceDirection.EXPENSE,
                accountId = com.paherdin.record2.records.FinanceSeeds.ACCOUNT_ID,
                accountVersion = 1,
                categoryId = null,
                categoryVersion = null,
                memo = "real draft",
                localDateTime = "2023-11-14T22:13:20",
                zoneId = "UTC",
            )
            val draft = finance.beginNewDraft(BackupTestDataFactory.GENERATION, input)
            if (state == "SAVED") {
                assertNotNull(finance.saveDraft(BackupTestDataFactory.GENERATION, draft.id, draft.revision))
            } else {
                finance.discardDraft(BackupTestDataFactory.GENERATION, draft.id, draft.revision)
            }
            val sourcePath = context.getDatabasePath(sourceName)
            val zip = File(staging, "real-draft-$state.zip")
            export(sourcePath, zip, DataOperationGate())
            assertTrue(LogicalZipReader.read(zip).backup.content.drafts.isEmpty())

            val targetName = "g1-real-draft-target-$state-${UUID.randomUUID()}.db"
            val target = open(targetName)
            val targetPath = context.getDatabasePath(targetName)
            restore(target, targetPath, zip, DataOperationGate())
            assertTrue(ReadOnlyBackupSnapshot.read(targetPath, clock.millis(), NoopExportResourceMonitor()).content.drafts.isEmpty())
            source.close()
            target.close()
        }
    }

    @Test
    fun failedRestoreRevokesLateRunCompletion() = runBlocking {
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-late-source-${UUID.randomUUID()}.db"
        val source = open(sourceName)
        seed(source, data)
        val zip = File(staging, "late-source.zip")
        export(context.getDatabasePath(sourceName), zip, DataOperationGate())

        val targetName = "g1-late-target-${UUID.randomUUID()}.db"
        val target = open(targetName)
        seed(target, data)
        target.openHelper.writableDatabase.execSQL(
            "UPDATE ai_control SET mode = 'FULL', selectedReceiverIdentity = ?, " +
                "grantReceiverIdentity = ?, grantScopeVersion = 1, grantDisclosureVersion = 1, " +
                "grantedAt = ?, authEpoch = 1 WHERE id = 1",
            arrayOf<Any>(BackupTestDataFactory.RECEIVER, BackupTestDataFactory.RECEIVER, clock.millis()),
        )
        val revoker = FreshRunExecutionRevoker()
        val barrier = object : RestoreMemoryBarrier {
            override fun invalidateInMemory() = Unit
            override fun revokeRuns(runIds: Set<String>) = revoker.revoke(runIds)
        }
        val targetPath = context.getDatabasePath(targetName)
        val service = RestoreService(
            RoomRestoreStore(target, clock), targetPath, staging, DataOperationGate(), clock,
            availableBytes = { Long.MAX_VALUE }, memoryBarrier = barrier,
        )
        val candidate = service.preflight(FileSource(zip))
        val good = zip.readBytes()
        zip.writeBytes(good.copyOf(good.size / 2))
        assertThrows(BackupException::class.java) { runBlocking { service.restore(candidate) } }

        val history = RoomAgentHistoryService(target, clock)
        history.runExecutionRevoker = revoker
        val before = tables(targetPath)
        val error = assertThrows(AgentHistoryException::class.java) {
            runBlocking {
                history.completeRun(
                    BackupTestDataFactory.RUN_EDITABLE,
                    BackupTestDataFactory.RECEIVER,
                    1,
                    data.owner,
                    RunOutcome(MessageResultKind.Answer, "late"),
                )
            }
        }
        assertEquals(AgentHistoryError.RunInvalidated, error.code)
        assertEquals(before, tables(targetPath))

        val c0 = RoomProposalService(target, clock)
        c0.runExecutionRevoker = revoker
        val context = ProposalRunContext(
            BackupTestDataFactory.SESSION_EDITABLE,
            BackupTestDataFactory.RUN_EDITABLE,
            data.owner,
            BackupTestDataFactory.RECEIVER,
            1,
        )
        val beforeC0 = tables(targetPath)
        val prepareError = assertThrows(ProposalException::class.java) {
            runBlocking { c0.prepare(context, emptyList()) }
        }
        assertEquals(ProposalError.RunInvalidated, prepareError.code)
        val reviseError = assertThrows(ProposalException::class.java) {
            runBlocking {
                c0.revise(context, BackupTestDataFactory.PROPOSAL_EDITABLE, 1, emptyList())
            }
        }
        assertEquals(ProposalError.RunInvalidated, reviseError.code)
        assertEquals(beforeC0, tables(targetPath))

        val interrupted = history.interruptRevokedRun(BackupTestDataFactory.RUN_EDITABLE, data.owner)
        assertEquals(RunState.INTERRUPTED, interrupted.state)
        val started = history.startRun(
            BackupTestDataFactory.SESSION_EDITABLE,
            "new run after failed restore",
            BackupTestDataFactory.RECEIVER,
            1,
            data.owner,
            history.newRunIdentity(),
        )
        assertEquals(BackupTestDataFactory.SESSION_EDITABLE, started.sessionId)
        source.close()
        target.close()
    }

    private fun open(name: String): RecordDatabase {
        val database = Room.databaseBuilder(context, RecordDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        database.openHelper.writableDatabase
        databases += database
        databaseNames += name
        return database
    }

    private fun seed(database: RecordDatabase, data: BackupTestData) {
        seedContent(database, data.content, data.generation, data.owner)
    }

    private fun seedContent(
        database: RecordDatabase,
        content: LogicalBackupContent,
        generation: String,
        owner: String,
    ) {
        runBlocking {
            database.withTransaction {
                val dao = database.backup()
                content.accounts.forEach { dao.insertAccount(it.toEntity()) }
                content.categories.forEach { dao.insertCategory(it.toEntity()) }
                content.transactions.forEach { dao.insertTransaction(it.toEntity()) }
                content.notes.forEach { dao.insertNote(it.toEntity()) }
                content.links.forEach { dao.insertLink(it.toEntity()) }
                content.drafts.forEach { dao.insertDraft(it.toEntity()) }
                content.sessions.forEach { dao.insertSession(it.toEntity()) }
                content.runs.forEach { dao.insertRun(it.toEntity()) }
                content.proposals.forEach { dao.insertProposal(it.toEntity()) }
                content.operations.forEach { dao.insertOperation(it.toEntity()) }
                content.messages.forEach { dao.insertMessage(it.toEntity()) }
                content.references.forEach { dao.insertReference(it.toEntity()) }
                dao.insertControl(AiControlEntity(1, "OFF", null, null, null, null, null, 1))
                dao.insertDataState(
                    RecordDataStateEntity(1, generation, 1, owner, null),
                )
            }
        }
    }

    private fun seedTargetControl(database: RecordDatabase, epoch: Long) {
        runBlocking {
            database.withTransaction {
                database.backup().insertDataState(
                    RecordDataStateEntity(
                        1, "22222222-2222-4222-8222-222222222222", 0,
                        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", null,
                    ),
                )
                database.backup().insertControl(
                    AiControlEntity(1, "OFF", null, null, null, null, null, epoch),
                )
            }
        }
    }

    private suspend fun export(sourcePath: File, zip: File, gate: DataOperationGate) {
        staging.mkdirs()
        BackupExporter(
            sourcePath, staging, gate, AppInfo(1, "0.1.0"), clock,
            availableBytes = { Long.MAX_VALUE },
            monitorFactory = { _, _ -> NoopExportResourceMonitor() },
        ).export(FileTarget(zip), zip.name)
    }

    private fun restore(
        database: RecordDatabase,
        path: File,
        zip: File,
        gate: DataOperationGate,
    ): RestoreResult = runBlocking {
        val service = RestoreService(
            RoomRestoreStore(database, clock), path, staging, gate, clock,
            availableBytes = { Long.MAX_VALUE },
        )
        val candidate = service.preflight(FileSource(zip))
        service.restore(candidate)
    }

    private fun assertUnchanged(data: BackupTestData, restored: LogicalBackupContent, restoredAt: Long) {
        assertEquals(data.content.accounts.associateBy { it.id }, restored.accounts.associateBy { it.id })
        assertEquals(data.content.categories.associateBy { it.id }, restored.categories.associateBy { it.id })
        assertEquals(data.content.transactions.associateBy { it.id }, restored.transactions.associateBy { it.id })
        val expectedRuns = data.content.runs.associateBy { it.id }.mapValues { (_, run) ->
            if (run.state == "RUNNING") {
                run.copy(
                    state = "INTERRUPTED",
                    activeSlot = null,
                    finishedAt = maxOf(restoredAt, run.startedAt),
                )
            } else {
                run
            }
        }
        assertEquals(expectedRuns, restored.runs.associateBy { it.id })
        val expectedProposals = data.content.proposals.associateBy { it.id }.mapValues { (_, proposal) ->
            if (proposal.state == "EDITABLE" || proposal.state == "LOCKED") {
                proposal.copy(state = "NEEDS_REVIEW", activeSlot = null)
            } else {
                proposal
            }
        }
        assertEquals(expectedProposals, restored.proposals.associateBy { it.id })
    }

    private fun assertOwnerReRead(database: RecordDatabase, data: BackupTestData, result: RestoreResult) {
        runBlocking {
            val proposals = RoomProposalService(database, clock)
            assertEquals(ProposalState.APPLIED, proposals.proposal(data.appliedProposalId)?.state)
            assertEquals(ProposalState.NEEDS_REVIEW, proposals.proposal(data.editableProposalId)?.state)
            assertEquals(ProposalState.NEEDS_REVIEW, proposals.proposal(data.lockedProposalId)?.state)

            val commits = RoomCommitService(database, clock)
            assertEquals(CommitStatus.Applied, commits.inspect(data.appliedOperationId)?.status)
            assertEquals(CommitStatus.Pending, commits.inspect(data.pendingOperationId)?.status)

            val finance = RoomFinanceService(database, clock)
            val transaction: FinanceRecord = finance.transaction(result.restoredGeneration, BackupTestDataFactory.TRANSACTION_ID)
            assertEquals(BackupTestDataFactory.TRANSACTION_ID, transaction.id)
            val draft: FinanceDraft? = finance.readDraft(result.restoredGeneration)
            assertEquals(BackupTestDataFactory.DRAFT_ID, draft?.id)

            val notes = RoomNoteService(database, clock)
            assertEquals(BackupTestDataFactory.NOTE_ACTIVE, notes.visible(result.restoredGeneration, BackupTestDataFactory.NOTE_ACTIVE).id)
            assertEquals(BackupTestDataFactory.NOTE_DELETED, notes.deleted(result.restoredGeneration, BackupTestDataFactory.NOTE_DELETED).id)

            val history = RoomAgentHistoryService(database, clock)
            assertEquals(2, history.sessionMessages(BackupTestDataFactory.SESSION_COMPLETED).size)
            assertEquals(RunState.COMPLETED, history.persistedResult(BackupTestDataFactory.RUN_COMPLETED)?.state)
            assertEquals(RunState.INTERRUPTED, history.persistedResult(BackupTestDataFactory.RUN_EDITABLE)?.state)
            assertEquals(RunState.INTERRUPTED, history.persistedResult(BackupTestDataFactory.RUN_LOCKED)?.state)
        }
    }

    private fun tables(path: File): Map<String, List<String>> {
        if (!path.isFile) return emptyMap()
        val result = LinkedHashMap<String, List<String>>()
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            for (table in TABLES) {
                val rows = ArrayList<String>()
                db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        rows += (0 until cursor.columnCount).joinToString("|") { index ->
                            if (cursor.isNull(index)) "<null>" else cursor.getString(index)
                        }
                    }
                }
                rows.sort()
                result[table] = rows
            }
        }
        return result
    }

    private class FileSource(private val file: File) : BackupSource {
        override val displayName: String? = file.name
        override suspend fun copyTo(destination: File, maxBytes: Long): Long {
            if (file.length() > maxBytes) throw BackupException(BackupError.InvalidSource, "source exceeds limit")
            file.inputStream().use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
            return file.length()
        }
    }

    private class FileTarget(private val file: File) : BackupTarget {
        override suspend fun writeFrom(source: File) {
            source.inputStream().use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
        }

        override suspend fun readBackSha256(): String = sha256(file)
        override suspend fun readBackBytes(): Long = file.length()
    }

    private class NoopVault : CredentialVault {
        override fun status() = CredentialStatus.NOT_CONFIGURED
        override fun store(secret: CharArray) = Unit
        override fun clear() = Unit
        override fun <T> use(block: (CharArray) -> T): T = block(charArrayOf('x'))
    }

    private class FreshRunExecutionRevoker : RunExecutionRevoker {
        private val revoked = HashSet<String>()

        override fun revoke(runIds: Set<String>) {
            revoked.addAll(runIds)
        }

        override fun isRevoked(runId: String): Boolean = runId in revoked
    }

    private companion object {
        val TABLES = listOf(
            "record_data_state", "account", "category", "record_transaction", "note",
            "transaction_note_link", "native_finance_draft", "change_proposal",
            "commit_operation", "agent_session", "agent_run", "agent_message",
            "agent_message_reference", "ai_control",
        )

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}

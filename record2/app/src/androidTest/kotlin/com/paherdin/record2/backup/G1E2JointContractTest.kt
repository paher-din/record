package com.paherdin.record2.backup

import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.ai.runs.AgentRunCoordinator
import com.paherdin.record2.ai.runs.AgentRunOwner
import com.paherdin.record2.ai.runs.OwnerRestoreMemoryBarrier
import com.paherdin.record2.ai.security.AiSecurityException
import com.paherdin.record2.sessions.AgentHistoryError
import com.paherdin.record2.sessions.AgentHistoryException
import com.paherdin.record2.sessions.MessageResultKind
import com.paherdin.record2.sessions.RunOutcome
import com.paherdin.record2.storage.E2LifecycleFixture
import com.paherdin.record2.storage.RecordDatabase
import com.paherdin.record2.storage.RoomRestoreStore
import com.paherdin.record2.storage.deviceScriptFactory
import com.paherdin.record2.storage.ScriptedDeviceHttp
import com.paherdin.record2.storage.sessionClock
import com.paherdin.record2.storage.toEntity
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real E2 owner + G1 restore joint contract: a live run is executing over a scripted HTTP
 * client when a confirmed restore replaces the target. The owner barrier must revoke the run
 * in memory and in the revoker without writing a terminal state; the restore transaction owns
 * the durable result, and a late completion after the restore can never land.
 */
class G1E2JointContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = Clock.fixed(
        Instant.ofEpochMilli(BackupTestDataFactory.FINISH + 10_000),
        ZoneOffset.UTC,
    )
    private val staging = File(context.noBackupFilesDir, "g1-e2-joint")

    @Test
    fun restoreRevokesLiveOwnerWithoutTerminalWrite() = runBlocking {
        ProcessRunExecutionRevoker.clearForTest()
        val data = BackupTestDataFactory.create()
        val sourceName = "g1-e2-joint-source-${UUID.randomUUID()}.db"
        val source = androidx.room.Room.databaseBuilder(context, RecordDatabase::class.java, sourceName).build()
        seed(source, data)
        val zip = File(staging, "joint.zip")
        staging.mkdirs()
        try {
            BackupExporter(
                context.getDatabasePath(sourceName), staging, DataOperationGate(),
                AppInfo(1, "0.1.0"), clock,
                availableBytes = { Long.MAX_VALUE },
                monitorFactory = { _, _ -> NoopExportResourceMonitor() },
            ).export(FileTarget(zip), zip.name)
        } finally {
            source.close()
            context.deleteDatabase(sourceName)
        }

        val id = UUID.randomUUID().toString()
        val fixture = E2LifecycleFixture("g1-joint-$id", "e2-contract-g1-joint-$id.db")
        val started = CompletableDeferred<Unit>()
        val http = ScriptedDeviceHttp { _, _ ->
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
            error("unreachable")
        }
        val owner = AgentRunOwner(
            AgentRunCoordinator(
                security = fixture.security,
                history = fixture.history,
                processOwnerId = fixture.owner,
                clock = sessionClock,
                httpClientFactory = deviceScriptFactory(http),
                isRestoreRevoked = { ProcessRunExecutionRevoker.isRevoked(it) },
            ),
            fixture.history,
            fixture.owner,
        )
        try {
            fixture.initialize()
            val session = fixture.history.createSession("g1 joint session")
            val handle = owner.launchRun(session.id, "live run before restore")
            withTimeout(30_000) { started.await() }
            val running = fixture.history.persistedResult(handle.runId)
            assertEquals(com.paherdin.record2.sessions.RunState.RUNNING, running?.state)

            val targetPath = context.getDatabasePath(fixture.name)
            val service = RestoreService(
                RoomRestoreStore(fixture.database, sessionClock), targetPath, staging,
                DataOperationGate(), sessionClock,
                availableBytes = { Long.MAX_VALUE },
                memoryBarrier = OwnerRestoreMemoryBarrier(owner, ProcessRunExecutionRevoker),
                processOwnerId = fixture.owner,
            )
            val result = service.preflight(FileSource(zip)).let { service.restore(it) }
            assertEquals(zip.length(), result.receipt.sourceZipBytes)

            val failure = runCatching { withTimeout(10_000) { handle.await() } }.exceptionOrNull()
            assertTrue("live run must be cancelled by restore, was $failure", failure is CancellationException)
            assertEquals(0, owner.trackedRunCount())
            assertTrue(ProcessRunExecutionRevoker.isRevoked(handle.runId))
            assertNull(fixture.history.persistedResult(handle.runId))

            val restored = ReadOnlyBackupSnapshot.read(
                targetPath, sessionClock.millis(), NoopExportResourceMonitor(),
            )
            assertEquals(
                data.content.notes.associateBy { it.id },
                restored.content.notes.associateBy { it.id },
            )
            assertEquals(
                data.content.transactions.associateBy { it.id },
                restored.content.transactions.associateBy { it.id },
            )
            assertEquals(
                data.content.sessions.associateBy { it.id },
                restored.content.sessions.associateBy { it.id },
            )
            assertTrue(restored.content.runs.none { it.state == "RUNNING" })
            assertTrue(restored.content.proposals.none { it.state == "EDITABLE" || it.state == "LOCKED" })

            val late = assertThrows(AgentHistoryException::class.java) {
                runBlocking {
                    fixture.history.completeRun(
                        handle.runId,
                        fixture.receiver.identity,
                        1,
                        fixture.owner,
                        RunOutcome(MessageResultKind.Answer, "late completion"),
                    )
                }
            }
            assertEquals(AgentHistoryError.NotFound, late.code)
            assertThrows(AiSecurityException::class.java) {
                runBlocking { fixture.security.requirePermit(fixture.receiver) }
            }
            assertEquals(
                0L,
                fixture.database.query(
                    "SELECT COUNT(*) FROM agent_run WHERE id = ?", arrayOf(handle.runId),
                ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) },
            )
        } finally {
            owner.close()
            fixture.close()
            fixture.directory.deleteRecursively()
            context.deleteDatabase(fixture.name)
            staging.deleteRecursively()
            ProcessRunExecutionRevoker.clearForTest()
        }
    }

    private fun seed(database: RecordDatabase, data: BackupTestData) {
        runBlocking {
            database.withTransaction {
                val dao = database.backup()
                data.content.accounts.forEach { dao.insertAccount(it.toEntity()) }
                data.content.categories.forEach { dao.insertCategory(it.toEntity()) }
                data.content.transactions.forEach { dao.insertTransaction(it.toEntity()) }
                data.content.notes.forEach { dao.insertNote(it.toEntity()) }
                data.content.links.forEach { dao.insertLink(it.toEntity()) }
                data.content.drafts.forEach { dao.insertDraft(it.toEntity()) }
                data.content.sessions.forEach { dao.insertSession(it.toEntity()) }
                data.content.runs.forEach { dao.insertRun(it.toEntity()) }
                data.content.proposals.forEach { dao.insertProposal(it.toEntity()) }
                data.content.operations.forEach { dao.insertOperation(it.toEntity()) }
                data.content.messages.forEach { dao.insertMessage(it.toEntity()) }
                data.content.references.forEach { dao.insertReference(it.toEntity()) }
                dao.insertControl(
                    com.paherdin.record2.storage.AiControlEntity(1, "OFF", null, null, null, null, null, 1),
                )
                dao.insertDataState(
                    com.paherdin.record2.storage.RecordDataStateEntity(
                        1, data.generation, 1, data.owner, null,
                    ),
                )
            }
        }
    }

    private class FileSource(private val file: File) : BackupSource {
        override val displayName: String? = file.name
        override suspend fun copyTo(destination: File, maxBytes: Long): Long {
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

        override suspend fun readBackSha256(): String = fileDigest(file)
        override suspend fun readBackBytes(): Long = file.length()
    }
}

private fun fileDigest(file: File): String {
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

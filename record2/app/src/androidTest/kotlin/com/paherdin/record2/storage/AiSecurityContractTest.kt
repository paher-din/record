package com.paherdin.record2.storage

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.ai.security.AiDenial
import com.paherdin.record2.ai.security.AiMode
import com.paherdin.record2.ai.security.AiSecurityError
import com.paherdin.record2.ai.security.AiSecurityException
import com.paherdin.record2.ai.security.AiSecurityState
import com.paherdin.record2.ai.security.AiSendEligibility
import com.paherdin.record2.ai.security.CredentialStatus
import com.paherdin.record2.ai.security.CredentialVault
import com.paherdin.record2.ai.security.KeystoreCredentialVault
import com.paherdin.record2.ai.security.ProviderReceiver
import com.paherdin.record2.ai.security.ProviderReceiverCatalog
import com.paherdin.record2.ai.security.RecordProviderReceivers
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AiSecurityContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val name = "e0-auth-$id.db"
    private val dbPath get() = context.getDatabasePath(name)
    private val directory = File(context.noBackupFilesDir, "e0-auth-$id")
    private val alias = "record2.ai.e0.$id"
    private val sentinel = "r2-e0-synthetic-9f4c7a2e6b1d4c8f"
    private val receiver = RecordProviderReceivers.deepSeek
    private val alternate = ProviderReceiver("e0.alternate.receiver.example", 2, 3)
    private val catalog = ProviderReceiverCatalog(listOf(receiver, alternate))
    private val clock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var database: RecordDatabase
    private lateinit var vault: KeystoreCredentialVault
    private lateinit var service: RoomAiSecurityService

    @Before fun open() {
        check(!dbPath.exists())
        check(!directory.exists())
        database = Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
        database.openHelper.writableDatabase
        runBlocking { RoomFinanceService(database, clock).initialize() }
        vault = KeystoreCredentialVault(directory, alias, "credential.bin")
        service = RoomAiSecurityService(database, vault, catalog, clock)
    }

    @After fun close() {
        database.close()
        try {
            vault.clear()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun initialStateIsOffWithoutGrantOrCredential() = runBlocking {
        val state = service.state()
        assertEquals(AiMode.OFF, state.mode)
        assertNull(state.selectedReceiverIdentity)
        assertNull(state.grantReceiverIdentity)
        assertEquals(0L, state.authEpoch)
        assertEquals(CredentialStatus.NOT_CONFIGURED, state.credentialStatus)
        assertEquals(AiSendEligibility.Denied(AiDenial.AI_DISABLED), service.eligibility(receiver))
        expectError(AiSecurityError.AuthorizationRequired) { service.requirePermit(receiver) }
        assertEquals(
            listOf(listOf(1L, "OFF", null, null, null, null, null, 0L)),
            controlRow(),
        )
    }

    @Test fun modeGrantAndCredentialAreIndependentAndMutationsInvalidatePermits() = runBlocking {
        var state = service.setMode(AiMode.FULL)
        assertEquals(1L, state.authEpoch)
        assertEquals(AiSendEligibility.Denied(AiDenial.RECEIVER_NOT_SELECTED), service.eligibility(receiver))

        state = service.selectReceiver(receiver)
        assertEquals(2L, state.authEpoch)
        assertEquals(AiSendEligibility.Denied(AiDenial.RECEIVER_UNAUTHORIZED), service.eligibility(receiver))

        state = service.grant(receiver)
        assertEquals(3L, state.authEpoch)
        assertEquals(receiver.identity, state.grantReceiverIdentity)
        assertEquals(receiver.scopeVersion, state.grantScopeVersion)
        assertEquals(receiver.disclosureVersion, state.grantDisclosureVersion)
        assertEquals(clock.millis(), state.grantedAt)
        assertEquals(AiSendEligibility.Denied(AiDenial.CREDENTIAL_REQUIRED), service.eligibility(receiver))

        state = storeCredential()
        assertEquals(4L, state.authEpoch)
        assertEquals(CredentialStatus.AVAILABLE, state.credentialStatus)
        assertEquals(AiSendEligibility.Ready, service.eligibility(receiver))
        val permit = service.requirePermit(receiver)
        assertTrue(service.stillValid(permit))

        state = service.setMode(AiMode.OFF)
        assertEquals(5L, state.authEpoch)
        assertEquals(AiSendEligibility.Denied(AiDenial.AI_DISABLED), service.eligibility(receiver))
        assertFalse(service.stillValid(permit))
        assertEquals(CredentialStatus.AVAILABLE, state.credentialStatus)
        assertEquals(receiver.identity, state.grantReceiverIdentity)

        state = service.setMode(AiMode.FULL)
        assertEquals(6L, state.authEpoch)
        assertEquals(AiSendEligibility.Ready, service.eligibility(receiver))
        val nextPermit = service.requirePermit(receiver)

        state = service.revoke()
        assertEquals(7L, state.authEpoch)
        assertNull(state.grantReceiverIdentity)
        assertEquals(AiSendEligibility.Denied(AiDenial.RECEIVER_UNAUTHORIZED), service.eligibility(receiver))
        assertFalse(service.stillValid(nextPermit))
        assertEquals(CredentialStatus.AVAILABLE, state.credentialStatus)

        state = service.grant(receiver)
        assertEquals(8L, state.authEpoch)
        assertEquals(AiSendEligibility.Ready, service.eligibility(receiver))

        state = service.clearCredential()
        assertEquals(9L, state.authEpoch)
        assertEquals(CredentialStatus.NOT_CONFIGURED, state.credentialStatus)
        assertEquals(AiSendEligibility.Denied(AiDenial.CREDENTIAL_REQUIRED), service.eligibility(receiver))
        assertEquals(receiver.identity, state.grantReceiverIdentity)
        assertEquals(1, controlRow().size)
    }

    @Test fun receiverChangeInvalidatesGrantAndRequiresMatchingVersions() = runBlocking {
        storeCredential()
        service.setMode(AiMode.FULL)
        service.selectReceiver(receiver)
        service.grant(receiver)
        val oldPermit = service.requirePermit(receiver)

        var state = service.selectReceiver(alternate)
        assertNull(state.grantReceiverIdentity)
        assertFalse(service.stillValid(oldPermit))
        assertEquals(AiSendEligibility.Denied(AiDenial.RECEIVER_UNAUTHORIZED), service.eligibility(alternate))

        state = service.grant(alternate)
        assertEquals(alternate.identity, state.grantReceiverIdentity)
        assertEquals(alternate.scopeVersion, state.grantScopeVersion)
        assertEquals(alternate.disclosureVersion, state.grantDisclosureVersion)
        assertEquals(AiSendEligibility.Ready, service.eligibility(alternate))
        assertEquals(AiSendEligibility.Denied(AiDenial.RECEIVER_UNAUTHORIZED), service.eligibility(receiver))

        state = service.selectReceiver(receiver)
        assertNull(state.grantReceiverIdentity)
        state = service.grant(receiver)
        assertEquals(AiSendEligibility.Ready, service.eligibility(receiver))

        val untrusted = ProviderReceiver("https://untrusted.example", 1, 1)
        expectError(AiSecurityError.UnsupportedReceiver) { service.selectReceiver(untrusted) }
        assertEquals(AiSendEligibility.Denied(AiDenial.UNSUPPORTED_RECEIVER), service.eligibility(untrusted))
    }

    @Test fun credentialReplacementAndCorruptionDoNotAlterTheControlRecord() = runBlocking {
        storeCredential()
        service.setMode(AiMode.FULL)
        service.selectReceiver(receiver)
        service.grant(receiver)
        val permit = service.requirePermit(receiver)
        val before = controlRow().single()

        val replacement = "r2-e0-synthetic-replacement-4d1e"
        val replaced = storeCredential(replacement)
        assertEquals(CredentialStatus.AVAILABLE, replaced.credentialStatus)
        assertFalse(service.stillValid(permit))
        assertEquals(replacement, vault.use { String(it) })
        val afterReplacement = controlRow().single()
        assertEquals((before[7] as Long) + 1, afterReplacement[7])
        assertEquals(before.take(7), afterReplacement.take(7))

        val bytes = vault.storageFile.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x5A).toByte()
        vault.storageFile.writeBytes(bytes)
        val corrupt = service.state()
        assertEquals(CredentialStatus.UNREADABLE, corrupt.credentialStatus)
        assertEquals(AiSendEligibility.Denied(AiDenial.CREDENTIAL_UNREADABLE), service.eligibility(receiver))
        expectError(AiSecurityError.CredentialUnavailable) { service.requirePermit(receiver) }
        assertEquals(afterReplacement, controlRow().single())

        val restored = storeCredential()
        assertEquals(CredentialStatus.AVAILABLE, restored.credentialStatus)
        assertEquals(AiSendEligibility.Ready, service.eligibility(receiver))
    }

    @Test fun guardedSendHasZeroEgressUntilAuthorizedAndFailureKeepsCredential() = runBlocking {
        val transport = FakeTransport()
        suspend fun attempt(payload: String): Boolean = try {
            val permit = service.requirePermit(receiver)
            service.useCredential(permit) { transport.send(payload) }
            true
        } catch (_: Exception) {
            false
        }

        assertFalse(attempt("off"))
        assertEquals(0, transport.calls)

        service.setMode(AiMode.FULL)
        assertFalse(attempt("no-grant"))
        assertEquals(0, transport.calls)

        service.selectReceiver(receiver)
        assertFalse(attempt("selected-only"))
        assertEquals(0, transport.calls)

        service.grant(receiver)
        assertFalse(attempt("no-credential"))
        assertEquals(0, transport.calls)

        storeCredential()
        assertTrue(attempt("authorized"))
        assertEquals(1, transport.calls)

        val permit = service.requirePermit(receiver)
        transport.fail = true
        try {
            service.useCredential(permit) { transport.send("network-failure") }
            fail("Synthetic provider failure must propagate")
        } catch (_: IOException) {
        }
        assertEquals(2, transport.calls)
        val afterFailure = service.state()
        assertEquals(CredentialStatus.AVAILABLE, afterFailure.credentialStatus)
        assertEquals(receiver.identity, afterFailure.grantReceiverIdentity)
        assertEquals(AiMode.FULL, afterFailure.mode)

        transport.fail = false
        assertTrue(attempt("retry"))
        assertEquals(3, transport.calls)

        service.revoke()
        assertFalse(attempt("revoked"))
        assertEquals(3, transport.calls)
        assertFalse(service.stillValid(permit))
    }

    @Test fun credentialRotationBlocksPermitIssuanceUntilReplacementCompletes() = runBlocking {
        storeCredential("r2-e0-synthetic-old-3a7c")
        service.setMode(AiMode.FULL)
        service.selectReceiver(receiver)
        service.grant(receiver)
        val oldPermit = service.requirePermit(receiver)

        val blocking = BlockingVault(vault)
        val rotating = RoomAiSecurityService(database, blocking, catalog, clock)
        val replacement = "r2-e0-synthetic-rotated-7a1b"
        val secret = replacement.toCharArray()
        val rotation = async(Dispatchers.IO) {
            try {
                rotating.replaceCredential(secret)
            } finally {
                secret.fill('\u0000')
            }
        }
        assertTrue(blocking.started.await(5, TimeUnit.SECONDS))
        val permitStarted = CountDownLatch(1)
        val permit = async(Dispatchers.IO) {
            permitStarted.countDown()
            rotating.requirePermit(receiver)
        }
        assertTrue(permitStarted.await(5, TimeUnit.SECONDS))
        try {
            assertNull(withTimeoutOrNull(300) { permit.await() })
        } finally {
            blocking.allowStore.countDown()
        }
        val rotated = withTimeout(10_000) { rotation.await() }
        val issued = withTimeout(10_000) { permit.await() }
        assertEquals(rotated.authEpoch, issued.authEpoch)
        assertFalse(rotating.stillValid(oldPermit))
        assertTrue(rotating.stillValid(issued))
        assertEquals(replacement, vault.use { String(it) })
    }

    @Test fun failedCredentialReplacementRetainsOldCredentialAndBumpsEpoch() = runBlocking {
        storeCredential("r2-e0-synthetic-old-3a7c")
        service.setMode(AiMode.FULL)
        service.selectReceiver(receiver)
        service.grant(receiver)
        val before = service.state()

        val failingService = RoomAiSecurityService(database, FailingVault(vault), catalog, clock)
        val replacement = "r2-e0-synthetic-failed-9c2d".toCharArray()
        try {
            expectError(AiSecurityError.RecoverableIO) { failingService.replaceCredential(replacement) }
        } finally {
            replacement.fill('\u0000')
        }
        val after = service.state()
        assertEquals(before.authEpoch + 1, after.authEpoch)
        assertEquals(CredentialStatus.AVAILABLE, after.credentialStatus)
        assertEquals("r2-e0-synthetic-old-3a7c", vault.use { String(it) })
        val permit = failingService.requirePermit(receiver)
        assertEquals(after.authEpoch, permit.authEpoch)
    }

    @Test fun credentialSentinelIsAbsentFromRoomFilesAndApplicationStorage() = runBlocking {
        storeCredential()
        service.setMode(AiMode.FULL)
        service.selectReceiver(receiver)
        service.grant(receiver)
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }

        val needle = sentinel.toByteArray(Charsets.UTF_8)
        val hits = context.dataDir.walkTopDown()
            .filter { it.isFile }
            .filter { it.readBytes().containsAscii(needle) }
            .map { it.absolutePath }
            .toList()
        assertTrue("Credential sentinel found in ${hits.joinToString()}", hits.isEmpty())
        assertTrue(vault.storageFile.isFile)
        assertEquals(directory.canonicalFile, vault.storageFile.parentFile!!.canonicalFile)
        assertEquals(context.noBackupFilesDir.canonicalFile, directory.parentFile!!.canonicalFile)
        assertFalse(controlRow().single().toString().contains(sentinel))
    }

    private suspend fun storeCredential(value: String = sentinel): AiSecurityState {
        val secret = value.toCharArray()
        return try {
            service.replaceCredential(secret)
        } finally {
            secret.fill('\u0000')
        }
    }

    private fun controlRow(): List<List<Any?>> =
        SchemaDiskObserver.snapshot(dbPath).getValue("ai_control")

    private suspend fun expectError(code: AiSecurityError, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (error: AiSecurityException) {
            assertEquals(code, error.code)
        }
    }

    private class FakeTransport {
        var calls = 0
        var fail = false

        fun send(payload: String) {
            check(payload.isNotEmpty())
            calls += 1
            if (fail) throw IOException("Synthetic provider failure")
        }
    }

    private class BlockingVault(private val delegate: CredentialVault) : CredentialVault {
        val started = CountDownLatch(1)
        val allowStore = CountDownLatch(1)

        override fun status(): CredentialStatus = delegate.status()

        override fun store(secret: CharArray) {
            started.countDown()
            check(allowStore.await(10, TimeUnit.SECONDS)) {
                "Timed out waiting for the test to release the credential store"
            }
            delegate.store(secret)
        }

        override fun clear() = delegate.clear()
        override fun <T> use(block: (CharArray) -> T): T = delegate.use(block)
    }

    private class FailingVault(private val delegate: CredentialVault) : CredentialVault {
        override fun status(): CredentialStatus = delegate.status()
        override fun store(secret: CharArray) {
            throw IOException("Synthetic credential write failure")
        }
        override fun clear() = delegate.clear()
        override fun <T> use(block: (CharArray) -> T): T = delegate.use(block)
    }

    private fun ByteArray.containsAscii(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..(size - needle.size)) {
            for (index in needle.indices) {
                if (this[start + index] != needle[index]) continue@outer
            }
            return true
        }
        return false
    }
}

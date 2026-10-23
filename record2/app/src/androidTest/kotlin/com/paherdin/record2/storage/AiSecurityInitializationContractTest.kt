package com.paherdin.record2.storage

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.ai.security.AiDenial
import com.paherdin.record2.ai.security.AiMode
import com.paherdin.record2.ai.security.AiSecurityError
import com.paherdin.record2.ai.security.AiSecurityException
import com.paherdin.record2.ai.security.AiSendEligibility
import com.paherdin.record2.ai.security.CredentialStatus
import com.paherdin.record2.ai.security.KeystoreCredentialVault
import com.paherdin.record2.ai.security.RecordProviderReceivers
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AiSecurityInitializationContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val name = "e0-init-$id.db"
    private val dbPath get() = context.getDatabasePath(name)
    private val directory = File(context.noBackupFilesDir, "e0-init-$id")
    private val alias = "record2.ai.e0.init.$id"
    private val sentinel = "r2-e0-synthetic-init-6b8d"
    private val receiver = RecordProviderReceivers.deepSeek
    private val clock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var database: RecordDatabase
    private lateinit var vault: KeystoreCredentialVault
    private lateinit var service: RoomAiSecurityService

    @Before fun open() {
        check(!dbPath.exists())
        check(!directory.exists())
        database = Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
        database.openHelper.writableDatabase
        vault = KeystoreCredentialVault(directory, alias, "credential.bin")
        service = RoomAiSecurityService(database, vault, RecordProviderReceivers.catalog, clock)
    }

    @After fun close() {
        database.close()
        try {
            vault.clear()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun e0ReadBeforeB1InitializationDoesNotCreateControlRowAndB1StillInitializes() = runBlocking {
        val initial = service.state()
        assertEquals(AiMode.OFF, initial.mode)
        assertEquals(CredentialStatus.NOT_CONFIGURED, initial.credentialStatus)
        assertEquals(AiSendEligibility.Denied(AiDenial.AI_DISABLED), service.eligibility(receiver))
        assertTrue(controlRow().isEmpty())

        val finance = RoomFinanceService(database, clock)
        val catalog = finance.initialize()
        assertNotNull(database.records().dataState())
        assertNotNull(database.records().account(catalog.account.id))
        assertEquals(9, database.records().categories().size)

        val after = service.state()
        assertEquals(AiMode.OFF, after.mode)
        assertEquals(1, controlRow().size)
        assertEquals(catalog, finance.initialize())
        assertEquals(1, controlRow().size)
    }

    @Test fun controlMutationsBeforeDataGenerationFailWithoutWriting() = runBlocking {
        expectError(AiSecurityError.NotInitialized) { service.setMode(AiMode.FULL) }
        expectError(AiSecurityError.NotInitialized) { service.selectReceiver(receiver) }
        expectError(AiSecurityError.NotInitialized) { service.grant(receiver) }
        expectError(AiSecurityError.NotInitialized) { service.revoke() }

        val secret = sentinel.toCharArray()
        try {
            expectError(AiSecurityError.NotInitialized) { service.replaceCredential(secret) }
        } finally {
            secret.fill('\u0000')
        }
        expectError(AiSecurityError.NotInitialized) { service.clearCredential() }

        assertTrue(controlRow().isEmpty())
        assertEquals(CredentialStatus.NOT_CONFIGURED, vault.status())
        assertFalse(vault.storageFile.exists())
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
}

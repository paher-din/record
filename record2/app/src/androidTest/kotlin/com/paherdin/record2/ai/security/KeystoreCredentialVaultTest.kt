package com.paherdin.record2.ai.security

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class KeystoreCredentialVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.noBackupFilesDir, "e0-vault-$id")
    private val alias = "record2.ai.e0.$id"
    private val sentinel = "r2-e0-synthetic-9f4c7a2e6b1d4c8f"
    private lateinit var vault: KeystoreCredentialVault

    @Before fun create() {
        assertEquals(context.noBackupFilesDir.canonicalFile, directory.parentFile!!.canonicalFile)
        vault = KeystoreCredentialVault(directory, alias, "credential.bin")
    }

    @After fun remove() {
        try {
            vault.clear()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun statusDoesNotCreateCredentialMaterial() {
        assertEquals(CredentialStatus.NOT_CONFIGURED, vault.status())
        assertFalse(vault.storageFile.exists())
        assertFalse(keyStore().containsAlias(alias))
    }

    @Test fun encryptedCredentialUsesUniqueIvsAndNeverStoresPlaintext() {
        vault.store(sentinel.toCharArray())
        assertEquals(CredentialStatus.AVAILABLE, vault.status())
        val first = CredentialEnvelope.decode(vault.storageFile.readBytes())
        assertFalse(vault.storageFile.readBytes().containsAscii(sentinel.toByteArray(Charsets.UTF_8)))
        assertEquals(sentinel, vault.use { String(it) })

        vault.store(sentinel.toCharArray())
        val second = CredentialEnvelope.decode(vault.storageFile.readBytes())
        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
        assertEquals(sentinel, vault.use { String(it) })
        assertEquals(directory.canonicalFile, vault.storageFile.parentFile!!.canonicalFile)
        assertEquals(context.noBackupFilesDir.canonicalFile, directory.parentFile!!.canonicalFile)
    }

    @Test fun corruptedCiphertextIsUnreadableAndCanBeReconfigured() {
        vault.store(sentinel.toCharArray())
        val file = vault.storageFile
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x5A).toByte()
        file.writeBytes(bytes)

        assertEquals(CredentialStatus.UNREADABLE, vault.status())
        expectUnavailable { vault.use { String(it) } }
        assertTrue(file.exists())

        vault.store(sentinel.toCharArray())
        assertEquals(CredentialStatus.AVAILABLE, vault.status())
        assertEquals(sentinel, vault.use { String(it) })
    }

    @Test fun missingKeystoreKeyIsUnreadableAndCanBeReconfigured() {
        vault.store(sentinel.toCharArray())
        keyStore().deleteEntry(alias)

        assertEquals(CredentialStatus.UNREADABLE, vault.status())
        expectUnavailable { vault.use { String(it) } }
        assertTrue(vault.storageFile.exists())

        vault.store(sentinel.toCharArray())
        assertEquals(CredentialStatus.AVAILABLE, vault.status())
        assertEquals(sentinel, vault.use { String(it) })
    }

    @Test fun clearRemovesBlobAndKeystoreKey() {
        vault.store(sentinel.toCharArray())
        vault.clear()

        assertEquals(CredentialStatus.NOT_CONFIGURED, vault.status())
        assertFalse(vault.storageFile.exists())
        assertFalse(keyStore().containsAlias(alias))
    }

    @Test fun blankCredentialIsRejectedWithoutMaterial() {
        for (secret in listOf(charArrayOf(), charArrayOf(' ', '\n'))) {
            try {
                vault.store(secret)
                fail("Blank credential was accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(CredentialStatus.NOT_CONFIGURED, vault.status())
        assertFalse(vault.storageFile.exists())
        assertFalse(keyStore().containsAlias(alias))
    }

    private fun expectUnavailable(block: () -> Unit) {
        try {
            block()
            fail("Unreadable credential was accepted")
        } catch (_: CredentialUnavailableException) {
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

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

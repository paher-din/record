package com.paherdin.record2.ai.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CredentialEnvelopeTest {
    private val iv = ByteArray(CredentialEnvelope.IV_BYTES) { (it + 1).toByte() }
    private val ciphertext = ByteArray(32) { (it + 31).toByte() }

    @Test fun encodeAndDecodePreserveIvAndCiphertext() {
        val encoded = CredentialEnvelope.of(iv, ciphertext).encode()
        val decoded = CredentialEnvelope.decode(encoded)
        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
        assertTrue(encoded.copyOfRange(0, 4).contentEquals("R2CV".toByteArray()))
        assertEquals(1, encoded[4].toInt())
        assertEquals(CredentialEnvelope.IV_BYTES, encoded[5].toInt())
    }

    @Test fun malformedEnvelopesAreRejected() {
        val invalidIv = CredentialEnvelope.of(ByteArray(11), ciphertext)
        val shortTag = CredentialEnvelope.of(iv, ByteArray(15))
        val encoded = CredentialEnvelope.of(iv, ciphertext).encode()
        for ((name, action) in listOf<Pair<String, () -> Unit>>(
            "short IV" to { invalidIv.encode() },
            "short tag" to { shortTag.encode() },
            "truncated" to { CredentialEnvelope.decode(encoded.copyOf(encoded.size - 32)) },
            "empty" to { CredentialEnvelope.decode(ByteArray(0)) },
            "bad magic" to {
                val bad = encoded.copyOf()
                bad[0] = 'X'.code.toByte()
                CredentialEnvelope.decode(bad)
            },
            "bad version" to {
                val bad = encoded.copyOf()
                bad[4] = 2
                CredentialEnvelope.decode(bad)
            },
            "bad IV length" to {
                val bad = encoded.copyOf()
                bad[5] = 13
                CredentialEnvelope.decode(bad)
            },
        )) {
            try {
                action()
                fail("$name envelope was accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}

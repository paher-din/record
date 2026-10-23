package com.paherdin.record2.ai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class CredentialEnvelope private constructor(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    fun encode(): ByteArray {
        require(iv.size == IV_BYTES) { "Unexpected IV length" }
        require(ciphertext.size >= GCM_TAG_BYTES) { "Ciphertext is shorter than the GCM tag" }
        return MAGIC + byteArrayOf(FORMAT_VERSION.toByte(), iv.size.toByte()) + iv + ciphertext
    }

    companion object {
        const val IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val FORMAT_VERSION = 1
        private val MAGIC = "R2CV".toByteArray(Charsets.US_ASCII)

        fun of(iv: ByteArray, ciphertext: ByteArray): CredentialEnvelope =
            CredentialEnvelope(iv.copyOf(), ciphertext.copyOf())

        fun decode(bytes: ByteArray): CredentialEnvelope {
            require(bytes.size >= MAGIC.size + 2 + IV_BYTES + GCM_TAG_BYTES) { "Truncated credential envelope" }
            require(bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Unknown credential magic" }
            require((bytes[4].toInt() and 0xFF) == FORMAT_VERSION) { "Unsupported credential format" }
            val ivLength = bytes[5].toInt() and 0xFF
            require(ivLength == IV_BYTES) { "Unexpected IV length" }
            val header = MAGIC.size + 2 + ivLength
            require(bytes.size >= header + GCM_TAG_BYTES) { "Truncated credential ciphertext" }
            return CredentialEnvelope(
                iv = bytes.copyOfRange(MAGIC.size + 2, header),
                ciphertext = bytes.copyOfRange(header, bytes.size),
            )
        }
    }
}

internal class KeystoreCredentialVault(
    private val directory: File,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val fileName: String = DEFAULT_FILE_NAME,
) : CredentialVault {
    private val lock = Any()
    internal val storageFile: File = File(directory, fileName)
    private val temporaryFile = File(directory, "$fileName.tmp")

    override fun status(): CredentialStatus = synchronized(lock) {
        if (!storageFile.isFile) {
            CredentialStatus.NOT_CONFIGURED
        } else {
            try {
                decrypt().fill('\u0000')
                CredentialStatus.AVAILABLE
            } catch (_: Exception) {
                CredentialStatus.UNREADABLE
            }
        }
    }

    override fun store(secret: CharArray) = synchronized(lock) {
        require(secret.any { !it.isWhitespace() }) { "Blank credential" }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Credential directory is unavailable")
        }
        val encoded = encrypt(secret).encode()
        var moved = false
        try {
            FileOutputStream(temporaryFile).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporaryFile.toPath(), storageFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile.toPath(), storageFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            moved = true
        } finally {
            if (!moved) temporaryFile.delete()
        }
    }

    override fun clear(): Unit {
        synchronized(lock) {
            var failure: IOException? = null
            if (temporaryFile.exists() && !temporaryFile.delete()) {
                failure = IOException("Temporary credential file could not be removed")
            }
            if (storageFile.exists() && !storageFile.delete()) {
                failure = IOException("Credential file could not be removed")
            }
            try {
                val store = keyStore()
                if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
            } catch (error: GeneralSecurityException) {
                if (failure == null) failure = IOException("Credential key could not be removed", error)
            }
            failure?.let { throw it }
        }
    }

    override fun <T> use(block: (CharArray) -> T): T {
        val secret = synchronized(lock) { decrypt() }
        return try {
            block(secret)
        } finally {
            secret.fill('\u0000')
        }
    }

    private fun encrypt(secret: CharArray): CredentialEnvelope {
        val encoded = try {
            Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(secret))
        } catch (error: Exception) {
            throw CredentialUnavailableException(error)
        }
        val plain = ByteArray(encoded.remaining())
        encoded.get(plain)
        if (encoded.hasArray()) encoded.array().fill(0)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            val ciphertext = cipher.doFinal(plain)
            val iv = cipher.iv ?: throw CredentialUnavailableException()
            CredentialEnvelope.of(iv, ciphertext)
        } catch (error: Exception) {
            throw CredentialUnavailableException(error)
        } finally {
            plain.fill(0)
        }
    }

    private fun decrypt(): CharArray {
        val envelope = try {
            CredentialEnvelope.decode(storageFile.readBytes())
        } catch (error: Exception) {
            throw CredentialUnavailableException(error)
        }
        val key = try {
            existingKey() ?: throw CredentialUnavailableException()
        } catch (error: CredentialUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw CredentialUnavailableException(error)
        }
        val plain = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv))
            cipher.updateAAD(AAD)
            cipher.doFinal(envelope.ciphertext)
        } catch (error: Exception) {
            throw CredentialUnavailableException(error)
        }
        var forZeroing: CharBuffer? = null
        return try {
            val decoded = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plain))
            forZeroing = decoded
            CharArray(decoded.remaining()).also { decoded.get(it) }
        } catch (error: Exception) {
            throw CredentialUnavailableException(error)
        } finally {
            plain.fill(0)
            forZeroing?.let { if (it.hasArray()) it.array().fill('\u0000') }
        }
    }

    private fun existingKey(): SecretKey? {
        val store = keyStore()
        return (store.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val DEFAULT_KEY_ALIAS = "record2.ai.credential.v1"
        const val DEFAULT_FILE_NAME = "ai_provider_credential.bin"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val AAD = "record2.ai.credential.v1".toByteArray(Charsets.UTF_8)

        fun forApp(context: Context): KeystoreCredentialVault =
            KeystoreCredentialVault(context.noBackupFilesDir)
    }
}

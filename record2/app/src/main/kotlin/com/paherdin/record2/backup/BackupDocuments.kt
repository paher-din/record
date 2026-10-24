package com.paherdin.record2.backup

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface BackupSource {
    val displayName: String?
    suspend fun copyTo(destination: File, maxBytes: Long): Long
}

internal interface BackupTarget {
    suspend fun writeFrom(source: File)
    suspend fun readBackSha256(): String
    suspend fun readBackBytes(): Long
}

internal class ContentResolverBackupSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val displayName: String?,
) : BackupSource {
    override suspend fun copyTo(destination: File, maxBytes: Long): Long = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri)
            ?: throw BackupException(BackupError.RecoverableIO, "selected backup cannot be opened")
        input.use { stream ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(65_536)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        destination.delete()
                        throw BackupException(BackupError.InvalidSource, "backup exceeds 64 MiB")
                    }
                    output.write(buffer, 0, read)
                }
                output.flush()
                total
            }
        }
    }
}

internal class ContentResolverBackupTarget(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : BackupTarget {
    override suspend fun writeFrom(source: File) = withContext(Dispatchers.IO) {
        val output = resolver.openOutputStream(uri, "wt")
            ?: throw BackupException(BackupError.RecoverableIO, "selected output cannot be opened")
        try {
            source.inputStream().use { input ->
                output.use { stream ->
                    input.copyTo(stream, 65_536)
                    stream.flush()
                    if (stream is FileOutputStream) stream.fd.sync()
                }
            }
        } catch (error: IOException) {
            throw BackupException(BackupError.RecoverableIO, "could not write the backup document", error)
        }
    }

    override suspend fun readBackSha256(): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: throw BackupException(BackupError.RecoverableIO, "backup document cannot be reopened")
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    override suspend fun readBackBytes(): Long = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { input ->
            var total = 0L
            val buffer = ByteArray(65_536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        } ?: throw BackupException(BackupError.RecoverableIO, "backup document cannot be reopened")
    }
}

internal object BackupIntents {
    fun createDocument(title: String = "record2-backup.zip"): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, title)
        }

    fun openDocument(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }

    fun takePersistableRead(activity: Activity, uri: Uri) {
        activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

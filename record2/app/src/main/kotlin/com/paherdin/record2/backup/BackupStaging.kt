package com.paherdin.record2.backup

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal data class StagingSweepReport(
    val deleted: List<String>,
    val failed: List<String>,
)

/**
 * Startup-only recovery for private export/restore task directories. The root is a dedicated
 * app-private directory; only direct children named `<preflight|restore|export>-<canonical UUID>`
 * are removed. Symlinks and every other entry are left untouched, and deletion failures are
 * reported instead of thrown so startup never blocks on a damaged staging directory.
 */
internal object BackupStaging {
    private val prefixes = setOf("preflight", "restore", "export")

    fun appDirectory(context: Context): File =
        File(context.noBackupFilesDir, "record2-backup-staging")

    fun sweep(
        root: File,
        deleteTask: (File) -> Boolean = ::deleteTaskDirectory,
    ): StagingSweepReport {
        if (!root.isDirectory) return StagingSweepReport(emptyList(), emptyList())
        val deleted = ArrayList<String>()
        val failed = ArrayList<String>()
        root.listFiles()?.forEach { child ->
            if (Files.isSymbolicLink(child.toPath())) return@forEach
            if (!child.isDirectory) return@forEach
            val name = child.name
            val separator = name.indexOf('-')
            if (separator <= 0) return@forEach
            val prefix = name.substring(0, separator)
            val identity = name.substring(separator + 1)
            if (prefix !in prefixes || !canonicalUuid(identity)) return@forEach
            if (deleteTask(child)) deleted += name else failed += name
        }
        return StagingSweepReport(deleted, failed)
    }

    private fun deleteTaskDirectory(directory: File): Boolean = try {
        Files.walkFileTree(
            directory.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        true
    } catch (error: IOException) {
        false
    } catch (error: SecurityException) {
        false
    }
}

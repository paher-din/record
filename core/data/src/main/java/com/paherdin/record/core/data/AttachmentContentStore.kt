package com.paherdin.record.core.data

import java.io.File

/**
 * App-private attachment file layout (plan Task 5 "Storage layout"):
 * `files/attachments/tmp/` staging on the same filesystem as the content
 * path, `files/attachments/content/<sha256>` content-addressed blobs, and
 * `files/attachments/quarantine/` for quarantined unlinked new inputs and
 * corrupt zero-link targets. Physical deletion is idempotent; orphan
 * classification uses file timestamps and fails closed on missing/invalid
 * ones (spec criterion 8).
 */
internal class AttachmentContentStore(
    filesDir: File,
    private val io: AttachmentIo,
) {
    private val root = File(filesDir, "attachments")
    val tempDir: File = File(root, "tmp")
    val contentDir: File = File(root, "content")
    val quarantineDir: File = File(root, "quarantine")

    init {
        tempDir.mkdirs()
        contentDir.mkdirs()
        quarantineDir.mkdirs()
    }

    fun newTempFile(): File {
        tempDir.mkdirs()
        return File(tempDir, "ingest-${System.nanoTime()}-${java.util.UUID.randomUUID()}")
    }

    fun contentFile(sha256: String): File = File(contentDir, sha256)

    fun quarantineFile(name: String): File {
        quarantineDir.mkdirs()
        return File(quarantineDir, name)
    }

    /**
     * Publishes a verified TEMP file to the content-addressed path by atomic
     * move; the target must be absent (caller holds per-hash serialization).
     * Returns the published file. A moved-but-uncommitted blob is a safe
     * orphan candidate for the cleaner.
     */
    fun atomicPublish(temp: File, sha256: String): File? {
        val target = contentFile(sha256)
        if (!io.atomicMove(temp, target)) return null
        return target
    }

    /** Idempotent physical deletion; returns whether the file was present. */
    fun deleteIfExists(file: File): Boolean = file.exists() && file.delete()

    /** Moves a residue into quarantine (quarantine only unlinked new inputs). */
    fun quarantine(temp: File): File {
        val target = quarantineFile(temp.name)
        if (!temp.renameTo(target)) temp.delete()
        return target
    }

    /**
     * Scans the content directory for blobs (file-only residue). The caller
     * cross-checks canonical rows before any cleanup; classification and
     * grace gating live in the cleaner.
     */
    fun scanContentBlobs(): List<File> = contentDir.listFiles()?.filter { it.isFile } ?: emptyList()

    /** Scans TEMP staging residue. */
    fun scanTempResidue(): List<File> = tempDir.listFiles()?.filter { it.isFile } ?: emptyList()

    /** Moves a blob into quarantine (only for corrupt zero-link targets). */
    fun quarantineBlob(file: File): File {
        val target = File(quarantineDir, file.name)
        if (!file.renameTo(target)) file.delete()
        return target
    }
}

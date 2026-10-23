package com.paherdin.record.core.data.room

import com.paherdin.record.core.data.BackupProviderTarget
import java.io.File

/**
 * App-private provider target implementing the SAF copy protocol (plan Task 5)
 * with injectable step faults. A partial object stays incomplete-marked until
 * fully copied and reverified.
 */
class TestProviderTarget(private val file: File) : BackupProviderTarget {
    private val markerFile = File(file.parentFile, file.name + ".incomplete")

    var created = false
    var closed = false
    var verified = false
    var renamed = false
    var markerCleared = false
    private val chunks = mutableListOf<ByteArray>()

    private var faultStep: String? = null
    private var faultChunkOrdinal: Int? = null
    private var renameSupported = true

    fun failAt(step: String) {
        faultStep = step
    }

    fun failChunk(ordinal: Int) {
        faultChunkOrdinal = ordinal
    }

    fun clearFault() {
        faultStep = null
        faultChunkOrdinal = null
    }

    fun setRenameUnsupported() {
        renameSupported = false
    }

    fun isIncompleteMarked(): Boolean = markerFile.isFile

    fun chunkCount(): Int = chunks.size

    fun writtenBytes(): Long = chunks.sumOf { it.size.toLong() }

    override suspend fun createIncomplete(): Boolean {
        if (faultStep == "create") throw RuntimeException("fault create")
        chunks.clear()
        created = true
        markerFile.writeText("incomplete\n")
        return true
    }

    override suspend fun writeChunk(bytes: ByteArray) {
        if (faultChunkOrdinal == chunks.size) throw RuntimeException("fault chunk ${chunks.size}")
        chunks.add(bytes)
    }

    override suspend fun flushAndClose() {
        if (faultStep == "flushClose") throw RuntimeException("fault flushClose")
        closed = true
    }

    override suspend fun verify(): Boolean {
        if (faultStep == "verify") throw RuntimeException("fault verify")
        verified = true
        return true
    }

    override suspend fun renameToFinalIfSupported(): Boolean {
        if (faultStep == "rename") throw RuntimeException("fault rename")
        renamed = true
        return renameSupported
    }

    override suspend fun clearIncompleteMarker() {
        if (faultStep == "clear") throw RuntimeException("fault clear")
        markerCleared = true
        markerFile.delete()
    }
}

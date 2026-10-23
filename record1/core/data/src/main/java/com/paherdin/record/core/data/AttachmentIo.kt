package com.paherdin.record.core.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Injectable copy/flush/close and atomic-move adapter (plan Task 5): the
 * production path uses fixed 64 KiB chunks and `Files.move(..., ATOMIC_MOVE)`
 * ONLY — no non-atomic fallback (spec criterion 4). Tests inject
 * source-read, flush, close, and atomic-move failures without changing
 * production paths.
 */
internal class AttachmentIo(
    private val hook: AttachmentFaultHook = AttachmentFaultHook.NO_OP,
    private val chunkSize: Int = 64 * 1024,
) {

    internal class CopyResult(val tempFile: File, val totalBytes: Long)

    /**
     * Copies `source` into `temp` in fixed chunks with the named fault
     * boundaries. Throws on IO failure; the caller classifies the residue.
     */
    fun copyToTemp(source: File, temp: File): CopyResult {
        hook.beforeTempCreate()
        val buffer = ByteArray(chunkSize)
        var total = 0L
        var ordinal = 0
        source.inputStream().use { input ->
            temp.outputStream().use { output ->
                while (true) {
                    hook.beforeSourceRead(ordinal)
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    total += read
                    hook.afterCopyChunk(ordinal, read.toLong(), total)
                    ordinal++
                }
                hook.afterFinalWriteBeforeClose()
                hook.beforeDestinationFlush()
                output.flush()
            }
        }
        hook.afterCloseBeforeValidation()
        return CopyResult(temp, total)
    }

    /**
     * Publishes `source` to `target` on the same filesystem. Atomic move only:
     * unavailability or failure surfaces as a typed IO failure and never
     * triggers a non-atomic fallback.
     */
    fun atomicMove(source: File, target: File): Boolean {
        return try {
            hook.beforeAtomicMove()
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            hook.afterAtomicMove()
            true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Unsupported atomic move, IO failure, or a hook-originated fault
            // all mean "no publish"; the caller maps this to
            // AttachmentIoFailed(ATOMIC_MOVE). Never a non-atomic fallback.
            false
        }
    }
}

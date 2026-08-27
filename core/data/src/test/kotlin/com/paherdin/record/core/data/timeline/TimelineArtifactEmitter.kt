package com.paherdin.record.core.data.timeline

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Tech PoC P6 — Task 2 host entry point (run by the `emitP6Oracle` JavaExec
 * task over the unit-test runtime). Regenerates the three frozen artifacts
 * under `core/data/build/p6/` from the fixture generator and the independent
 * oracle: `fixture.sha256`, `oracle.sha256`, `oracle.txt`. Each file is
 * written temp-first and atomically renamed so a partial run never leaves a
 * half-written artifact. Generated files are build outputs, never committed.
 */
object TimelineArtifactEmitter {

    fun emit(outputDir: File) {
        outputDir.mkdirs()
        val fixtureBytes = TimelineFixtureGenerator.encodeFixture()
        val oracleBytes = TimelineOracle.encodeOracle(TimelineFixtureGenerator.generate())
        writeAtomic(File(outputDir, "fixture.sha256"), sha256Hex(fixtureBytes))
        writeAtomic(File(outputDir, "oracle.sha256"), sha256Hex(oracleBytes))
        writeAtomic(File(outputDir, "oracle.txt"), oracleBytes)
        emitDayOracles(outputDir)
    }

    private fun emitDayOracles(outputDir: File) {
        val dayOutputDir = File(outputDir, "day-oracles")
        val days = listOf(
            LocalDate.parse("2022-12-31"),
            LocalDate.parse("2023-01-01"),
            LocalDate.parse("2025-09-26"),
        )
        val manifest = StringBuilder("date\tdayStart\tnextDayStart\titemCount\tsha256\n")
        for (date in days) {
            val day = TimelineOracle.selectedDay(date)
            val bytes = TimelineOracle.encodeDayOracle(day)
            val hash = sha256Hex(bytes)
            writeAtomic(File(dayOutputDir, "$date.txt"), bytes)
            writeAtomic(File(dayOutputDir, "$date.sha256"), hash)
            manifest.append(date).append('\t')
                .append(day.dayStart).append('\t')
                .append(day.nextDayStart).append('\t')
                .append(day.items.size).append('\t')
                .append(hash).append('\n')
        }
        writeAtomic(File(dayOutputDir, "manifest.tsv"), manifest.toString())
    }

    private fun writeAtomic(target: File, text: String) =
        writeAtomic(target, text.toByteArray(Charsets.UTF_8))

    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        Files.write(tmp.toPath(), bytes)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.io.IOException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/** Entry point for the `emitP6Oracle` host JavaExec task. */
fun main() {
    val outputDir = System.getProperty("p6OutputDir")
        ?: error("emitP6Oracle requires -Dp6OutputDir=<dir>")
    TimelineArtifactEmitter.emit(File(outputDir))
    println("P6 artifacts written under $outputDir")
}

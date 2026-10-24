package com.paherdin.record2.backup

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class LogicalFormatTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validFixtureRoundTripsAndPassesValidator() {
        val fixture = BackupFixtureFactory.create(temporaryFolder.newFolder("valid"))
        assertEquals(fixture.content, fixture.backup.content)
        LogicalBackupValidator.validate(fixture.backup, fixture.now)
    }

    @Test
    fun emptySegmentsUseStoredZeroBytesAndRoundTrip() {
        val content = emptyContent()
        val fixture = BackupFixtureFactory.write(temporaryFolder.newFolder("empty"), content, BackupFixtureFactory.FINISH + 1)
        LogicalBackupValidator.validate(fixture.backup, fixture.now)
        ZipFile(fixture.zip).use { zip ->
            for (segment in LogicalSegment.entries) {
                if (segment == LogicalSegment.ACCOUNT || segment == LogicalSegment.CATEGORY) continue
                val entry = zip.getEntry(segment.path)
                assertEquals(ZipEntry.STORED, entry.method)
                assertEquals(0L, entry.compressedSize)
                assertEquals(0L, entry.size)
                assertEquals(0L, entry.crc)
            }
        }
        assertEquals(0, fixture.backup.content.transactions.size)
        assertEquals(0, fixture.backup.content.runs.size)
    }

    @Test
    fun deflateEmptySegmentIsRejected() {
        val fixture = BackupFixtureFactory.write(
            temporaryFolder.newFolder("empty-source"),
            emptyContent(),
            BackupFixtureFactory.FINISH + 1,
        )
        val target = File(temporaryFolder.newFolder("deflate-empty"), "bad.zip")
        ZipFile(fixture.zip).use { source ->
            ZipOutputStream(target.outputStream()).use { out ->
                for (segment in LogicalSegment.entries) {
                    val bytes = source.getInputStream(source.getEntry(segment.path)).readBytes()
                    val entry = ZipEntry(segment.path)
                    if (bytes.isEmpty()) {
                        entry.method = ZipEntry.DEFLATED
                    } else {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.compressedSize = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    out.putNextEntry(entry)
                    if (bytes.isNotEmpty()) out.write(bytes)
                    out.closeEntry()
                }
                val manifest = source.getInputStream(source.getEntry("manifest.json")).readBytes()
                val entry = ZipEntry("manifest.json")
                entry.method = ZipEntry.STORED
                entry.size = manifest.size.toLong()
                entry.compressedSize = manifest.size.toLong()
                entry.crc = CRC32().apply { update(manifest) }.value
                out.putNextEntry(entry)
                out.write(manifest)
                out.closeEntry()
            }
        }
        val error = assertThrows(BackupException::class.java) { LogicalZipReader.read(target) }
        assertEquals(BackupError.InvalidSource, error.code)
    }

    @Test
    fun trailingZipBytesAreRejected() {
        val fixture = BackupFixtureFactory.create(temporaryFolder.newFolder("trailing-source"))
        val target = File(temporaryFolder.newFolder("trailing"), "bad.zip")
        target.writeBytes(fixture.zip.readBytes() + byteArrayOf(1, 2, 3))
        val error = assertThrows(BackupException::class.java) { LogicalZipReader.read(target) }
        assertEquals(BackupError.InvalidSource, error.code)
    }

    @Test
    fun manifestUnknownFieldIsRejected() {
        val fixture = BackupFixtureFactory.create(temporaryFolder.newFolder("manifest-source"))
        val original = ManifestCodec.encode(fixture.backup.manifest).toString(StandardCharsets.UTF_8)
        val corrupted = original.replaceFirst("{\"format\"", "{\"extra\":1,\"format\"")
        val error = assertThrows(BackupException::class.java) {
            ManifestCodec.decode(corrupted.toByteArray(StandardCharsets.UTF_8))
        }
        assertEquals(BackupError.InvalidSource, error.code)
    }

    @Test
    fun strictJsonRejectsDuplicateFloatBomAndDepth() {
        assertThrows(BackupException::class.java) {
            StrictJson.decodeObject("""{"a":1,"a":2}""".toByteArray())
        }
        assertThrows(BackupException::class.java) {
            StrictJson.decodeObject("""{"a":1.5}""".toByteArray())
        }
        assertThrows(BackupException::class.java) {
            StrictJson.decodeObject(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{}".toByteArray())
        }
        val deep = "{".repeat(33) + "1" + "}".repeat(33)
        assertThrows(BackupException::class.java) {
            StrictJson.decodeObject(deep.toByteArray())
        }
    }

    @Test
    fun receiptRoundTripsAndRejectsSameGeneration() {
        val receipt = RestoreReceipt(
            formatVersion = 1,
            sourceZipSha256 = "a".repeat(64),
            sourceZipBytes = 100,
            sourceFormatVersion = 1,
            sourceSchemaVersion = 1,
            sourceGeneration = BackupFixtureFactory.GENERATION,
            restoredGeneration = BackupFixtureFactory.OWNER,
            restoredAt = BackupFixtureFactory.FINISH,
            contentDigest = "b".repeat(64),
            importedRows = 10,
            businessRevisionAtRestore = 1,
        )
        val text = RestoreReceiptCodec.encode(receipt)
        assertEquals(receipt, RestoreReceiptCodec.decode(text, BackupFixtureFactory.FINISH + 1))
        val error = assertThrows(BackupException::class.java) {
            RestoreReceiptCodec.decode(text, BackupFixtureFactory.FINISH + 1)
                .copy(sourceGeneration = BackupFixtureFactory.OWNER)
                .let { RestoreReceiptCodec.encode(it) }
        }
        assertEquals(BackupError.TargetInvalid, error.code)
    }

    @Test
    fun validatorRejectsCorruptHistories() {        val fixture = BackupFixtureFactory.create(temporaryFolder.newFolder("corrupt"))
        fun reject(transform: (LogicalBackupContent) -> LogicalBackupContent) {
            assertThrows(BackupException::class.java) {
                LogicalBackupValidator.validateContent(transform(fixture.content), fixture.now)
            }
        }
        reject { it.copy(accounts = it.accounts + it.accounts.single().copy(id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")) }
        reject { it.copy(notes = it.notes.map { note -> note.copy(body = "   ") }) }
        reject { it.copy(links = it.links + LinkRow(BackupFixtureFactory.TRANSACTION_ID, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")) }
        reject { it.copy(runs = it.runs.map { run -> run.copy(state = "RUNNING") }) }
        reject { it.copy(messages = it.messages.map { message -> if (message.role == "ASSISTANT") message.copy(resultKind = "INPUT") else message }) }
        reject { it.copy(proposals = it.proposals.map { proposal -> proposal.copy(activeSlot = 1) }) }
        reject { it.copy(drafts = it.drafts.map { draft -> draft.copy(state = "SAVED") }) }
    }

    private fun emptyContent(): LogicalBackupContent {
        val categories = com.paherdin.record2.records.FinanceSeeds.categories.mapIndexed { index, seed ->
            CategoryRow(seed.id, seed.name, seed.direction.name, 1, BackupFixtureFactory.START + index, BackupFixtureFactory.FINISH)
        }
        return LogicalBackupContent(
            accounts = listOf(
                AccountRow(
                    com.paherdin.record2.records.FinanceSeeds.ACCOUNT_ID, "日常账户", "CNY", 1,
                    BackupFixtureFactory.START, BackupFixtureFactory.FINISH,
                ),
            ),
            categories = categories,
            transactions = emptyList(), notes = emptyList(), links = emptyList(), drafts = emptyList(),
            proposals = emptyList(), operations = emptyList(), sessions = emptyList(), runs = emptyList(),
            messages = emptyList(), references = emptyList(),
        )
    }
}

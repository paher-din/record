package com.paherdin.record2.backup

import com.paherdin.record2.proposals.AppliedCommitResult
import com.paherdin.record2.proposals.AppliedOutcome
import com.paherdin.record2.proposals.CommitCodec
import com.paherdin.record2.proposals.CommitResultCodec
import com.paherdin.record2.proposals.ProposalCodec
import com.paherdin.record2.proposals.ProposalOperation
import com.paherdin.record2.proposals.ProposalTime
import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceSeeds
import com.paherdin.record2.proposals.ProposalBudget
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

internal data class BackupFixture(
    val content: LogicalBackupContent,
    val backup: LogicalBackup,
    val zip: File,
    val now: Long,
)

internal object BackupFixtureFactory {
    const val GENERATION = "11111111-1111-4111-8111-111111111111"
    const val OWNER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    const val RECEIVER = "synthetic-provider"
    const val SESSION_ID = "33333333-3333-4333-8333-333333333333"
    const val RUN_ID = "44444444-4444-4444-8444-444444444444"
    const val PROPOSAL_ID = "55555555-5555-4555-8555-555555555555"
    const val OPERATION_ID = "66666666-6666-4666-8666-666666666666"
    const val NOTE_ID = "77777777-7777-4777-8777-777777777777"
    const val TRANSACTION_ID = "88888888-8888-4888-8888-888888888888"
    const val DRAFT_ID = "99999999-9999-4999-8999-999999999999"
    const val DRAFT_RECORD_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    const val USER_MESSAGE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    const val ASSISTANT_MESSAGE_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    const val CREATION_REQUEST_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
    const val START = 1_700_000_000_000L
    const val FINISH = START + 1_000L

    fun create(directory: File, now: Long = FINISH + 10_000L): BackupFixture {
        val local = "2023-11-14T22:13:20"
        val time = ProposalTime(
            occurredAt = START,
            zoneId = "UTC",
            localDateTime = local,
            offsetSeconds = 0,
        )
        check(LocalDateTime.parse(local).toInstant(ZoneOffset.UTC).toEpochMilli() == START)

        val account = AccountRow(FinanceSeeds.ACCOUNT_ID, "日常账户", "CNY", 2, START, FINISH)
        val categories = FinanceSeeds.categories.mapIndexed { index, seed ->
            CategoryRow(seed.id, seed.name, seed.direction.name, 1, START + index, FINISH)
        }
        val transaction = TransactionRow(
            TRANSACTION_ID, "EXPENSE", 1234, "CNY", FinanceSeeds.ACCOUNT_ID,
            FinanceSeeds.categories.first { it.direction == FinanceDirection.EXPENSE }.id,
            "memo\n原样", START, 1, START, FINISH,
        )
        val note = NoteRow(NOTE_ID, "proposal note", START, 2, START, FINISH, null)
        val link = LinkRow(TRANSACTION_ID, NOTE_ID)
        val draft = DraftRow(
            slot = 1,
            draftId = DRAFT_ID,
            draftRevision = 1,
            state = "EDITING",
            mode = "NEW",
            recordId = DRAFT_RECORD_ID,
            baseVersion = null,
            inputJson = """{"formatVersion":1,"input":{"amountText":"12.34","direction":"EXPENSE","accountId":"${FinanceSeeds.ACCOUNT_ID}","accountVersion":2,"categoryId":null,"categoryVersion":null,"memo":"draft","localDateTime":"2023-11-14T22:13:20","zoneId":"UTC"}}""",
            updatedAt = FINISH,
        )
        val session = SessionRow(SESSION_ID, "合成会话", START, FINISH, 2, 2)
        val run = RunRow(
            RUN_ID, SESSION_ID, GENERATION, OWNER, RECEIVER, 1, CREATION_REQUEST_ID,
            0, 1, "COMPLETED", null, START, FINISH, null,
        )
        val operation = ProposalOperation.CreateNote("#n", "proposal note", time)
        val payloadJson = ProposalCodec.canonical(listOf(operation))
        val displayedDigest = ProposalCodec.digest(payloadJson)
        val frozen = CommitCodec.freeze(listOf(operation), displayedDigest) { NOTE_ID }
        val resultJson = CommitResultCodec.canonical(
            AppliedCommitResult(1, listOf(AppliedOutcome.CreateNote(NOTE_ID, 1))),
        )
        val proposal = ProposalRow(
            PROPOSAL_ID, SESSION_ID, RUN_ID, RUN_ID, CREATION_REQUEST_ID, payloadJson, 1,
            payloadJson, displayedDigest, ProposalBudget.FORMAT_VERSION, GENERATION, "APPLIED",
            null, START, FINISH,
        )
        val operationRow = OperationRow(
            OPERATION_ID, PROPOSAL_ID, 1, GENERATION, frozen.canonicalJson,
            frozen.payloadDigest, ProposalBudget.FORMAT_VERSION, "Applied", START, FINISH,
            resultJson, null,
        )
        val user = MessageRow(
            USER_MESSAGE_ID, SESSION_ID, RUN_ID, 0, "USER", "合成输入", START,
            "INPUT", null, null, null,
        )
        val assistant = MessageRow(
            ASSISTANT_MESSAGE_ID, SESSION_ID, RUN_ID, 1, "ASSISTANT", "合成回复", FINISH,
            "ProposalReady", null, PROPOSAL_ID, 1,
        )
        val reference = ReferenceRow(ASSISTANT_MESSAGE_ID, 0, null, NOTE_ID)
        val content = LogicalBackupContent(
            accounts = listOf(account),
            categories = categories,
            transactions = listOf(transaction),
            notes = listOf(note),
            links = listOf(link),
            drafts = listOf(draft),
            proposals = listOf(proposal),
            operations = listOf(operationRow),
            sessions = listOf(session),
            runs = listOf(run),
            messages = listOf(user, assistant),
            references = listOf(reference),
        )
        return write(directory, content, now)
    }

    fun write(directory: File, content: LogicalBackupContent, now: Long): BackupFixture {
        val sources = ArrayList<SegmentSource>()
        for (segment in LogicalSegment.entries) {
            val file = File(directory, "${segment.ordinal}-${segment.path.substringAfterLast('/')}")
            SegmentFileWriter(segment, file).use { writer ->
                content.rows(segment).forEach { writer.append(it) }
                sources += writer.finish()
            }
        }
        val totalRows = sources.sumOf { it.rows }
        val totalBytes = sources.sumOf { it.uncompressedBytes }
        val segments = sources.mapIndexed { index, source ->
            ManifestSegment(
                name = LogicalSegment.entries[index].path,
                rows = source.rows,
                uncompressedBytes = source.uncompressedBytes,
                sha256 = source.sha256,
            )
        }
        val provisional = BackupManifest(
            format = BackupLimits.FORMAT,
            formatVersion = BackupLimits.FORMAT_VERSION,
            databaseSchemaVersion = BackupLimits.DATABASE_SCHEMA_VERSION,
            exportedAt = FINISH,
            sourceGeneration = GENERATION,
            businessRevision = 1,
            appVersionCode = 1,
            appVersionName = "0.1.0",
            entryCount = BackupLimits.ENTRY_COUNT,
            totalRows = totalRows,
            totalUncompressedBytes = totalBytes,
            segments = segments,
            contentDigest = "0".repeat(64),
        )
        val manifest = provisional.copy(contentDigest = ManifestCodec.contentDigest(provisional))
        val zip = File(directory, "backup.zip")
        LogicalZipWriter.write(zip, manifest, sources)
        val read = LogicalZipReader.read(zip)
        return BackupFixture(content, read.backup, zip, now)
    }
}

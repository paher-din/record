package com.paherdin.record2.backup

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal enum class LogicalSegment(val path: String, val fields: List<String>) {
    ACCOUNT(
        "data/account.jsonl",
        listOf("id", "name", "currency", "version", "createdAt", "updatedAt"),
    ),
    CATEGORY(
        "data/category.jsonl",
        listOf("id", "name", "direction", "version", "createdAt", "updatedAt"),
    ),
    TRANSACTION(
        "data/record_transaction.jsonl",
        listOf(
            "id", "direction", "amountMinor", "currency", "accountId", "categoryId",
            "memo", "occurredAt", "version", "createdAt", "updatedAt",
        ),
    ),
    NOTE(
        "data/note.jsonl",
        listOf("id", "body", "occurredAt", "version", "createdAt", "updatedAt", "deletedAt"),
    ),
    LINK("data/transaction_note_link.jsonl", listOf("transactionId", "noteId")),
    DRAFT(
        "data/native_finance_draft.jsonl",
        listOf(
            "slot", "draftId", "draftRevision", "state", "mode", "recordId",
            "baseVersion", "inputJson", "updatedAt",
        ),
    ),
    PROPOSAL(
        "data/change_proposal.jsonl",
        listOf(
            "id", "sessionId", "originRunId", "lastWriterRunId", "creationRequestId",
            "creationPayloadJson", "revision", "payloadJson", "payloadDigest",
            "payloadFormatVersion", "dataGeneration", "state", "activeSlot",
            "createdAt", "updatedAt",
        ),
    ),
    OPERATION(
        "data/commit_operation.jsonl",
        listOf(
            "id", "proposalId", "confirmedRevision", "createdGeneration",
            "frozenPayloadJson", "payloadDigest", "payloadFormatVersion", "state",
            "createdAt", "finishedAt", "resultJson", "rejectionCode",
        ),
    ),
    SESSION(
        "data/agent_session.jsonl",
        listOf("id", "title", "createdAt", "updatedAt", "historyVersion", "nextMessageSequence"),
    ),
    RUN(
        "data/agent_run.jsonl",
        listOf(
            "id", "sessionId", "dataGeneration", "processOwnerId", "receiverIdentity",
            "authEpoch", "proposalCreationRequestId", "historyBoundarySequence",
            "historyBoundaryVersion", "state", "activeSlot", "startedAt", "finishedAt",
            "safeErrorCode",
        ),
    ),
    MESSAGE(
        "data/agent_message.jsonl",
        listOf(
            "id", "sessionId", "runId", "sequence", "role", "body", "createdAt",
            "resultKind", "resultJson", "proposalId", "proposalRevision",
        ),
    ),
    REFERENCE(
        "data/agent_message_reference.jsonl",
        listOf("messageId", "ordinal", "transactionId", "noteId"),
    ),
}

internal data class ManifestSegment(
    val name: String,
    val rows: Long,
    val uncompressedBytes: Long,
    val sha256: String,
)

internal data class BackupManifest(
    val format: String,
    val formatVersion: Int,
    val databaseSchemaVersion: Int,
    val exportedAt: Long,
    val sourceGeneration: String,
    val businessRevision: Long,
    val appVersionCode: Long,
    val appVersionName: String,
    val entryCount: Int,
    val totalRows: Long,
    val totalUncompressedBytes: Long,
    val segments: List<ManifestSegment>,
    val contentDigest: String,
)

internal sealed interface SegmentRow

internal data class AccountRow(
    val id: String,
    val name: String,
    val currency: String,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
) : SegmentRow

internal data class CategoryRow(
    val id: String,
    val name: String,
    val direction: String,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
) : SegmentRow

internal data class TransactionRow(
    val id: String,
    val direction: String,
    val amountMinor: Long,
    val currency: String,
    val accountId: String,
    val categoryId: String?,
    val memo: String,
    val occurredAt: Long,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
) : SegmentRow

internal data class NoteRow(
    val id: String,
    val body: String,
    val occurredAt: Long,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
) : SegmentRow

internal data class LinkRow(val transactionId: String, val noteId: String) : SegmentRow

internal data class DraftRow(
    val slot: Int,
    val draftId: String,
    val draftRevision: Long,
    val state: String,
    val mode: String,
    val recordId: String,
    val baseVersion: Long?,
    val inputJson: String,
    val updatedAt: Long,
) : SegmentRow

internal data class ProposalRow(
    val id: String,
    val sessionId: String,
    val originRunId: String,
    val lastWriterRunId: String,
    val creationRequestId: String,
    val creationPayloadJson: String,
    val revision: Long,
    val payloadJson: String,
    val payloadDigest: String,
    val payloadFormatVersion: Int,
    val dataGeneration: String,
    val state: String,
    val activeSlot: Int?,
    val createdAt: Long,
    val updatedAt: Long,
) : SegmentRow

internal data class OperationRow(
    val id: String,
    val proposalId: String,
    val confirmedRevision: Long,
    val createdGeneration: String,
    val frozenPayloadJson: String,
    val payloadDigest: String,
    val payloadFormatVersion: Int,
    val state: String,
    val createdAt: Long,
    val finishedAt: Long?,
    val resultJson: String?,
    val rejectionCode: String?,
) : SegmentRow

internal data class SessionRow(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val historyVersion: Long,
    val nextMessageSequence: Long,
) : SegmentRow

internal data class RunRow(
    val id: String,
    val sessionId: String,
    val dataGeneration: String,
    val processOwnerId: String,
    val receiverIdentity: String,
    val authEpoch: Long,
    val proposalCreationRequestId: String,
    val historyBoundarySequence: Long,
    val historyBoundaryVersion: Long,
    val state: String,
    val activeSlot: Int?,
    val startedAt: Long,
    val finishedAt: Long?,
    val safeErrorCode: String?,
) : SegmentRow

internal data class MessageRow(
    val id: String,
    val sessionId: String,
    val runId: String,
    val sequence: Long,
    val role: String,
    val body: String,
    val createdAt: Long,
    val resultKind: String,
    val resultJson: String?,
    val proposalId: String?,
    val proposalRevision: Long?,
) : SegmentRow

internal data class ReferenceRow(
    val messageId: String,
    val ordinal: Int,
    val transactionId: String?,
    val noteId: String?,
) : SegmentRow

internal data class LogicalBackupContent(
    val accounts: List<AccountRow>,
    val categories: List<CategoryRow>,
    val transactions: List<TransactionRow>,
    val notes: List<NoteRow>,
    val links: List<LinkRow>,
    val drafts: List<DraftRow>,
    val proposals: List<ProposalRow>,
    val operations: List<OperationRow>,
    val sessions: List<SessionRow>,
    val runs: List<RunRow>,
    val messages: List<MessageRow>,
    val references: List<ReferenceRow>,
) {
    fun rows(segment: LogicalSegment): List<SegmentRow> = when (segment) {
        LogicalSegment.ACCOUNT -> accounts
        LogicalSegment.CATEGORY -> categories
        LogicalSegment.TRANSACTION -> transactions
        LogicalSegment.NOTE -> notes
        LogicalSegment.LINK -> links
        LogicalSegment.DRAFT -> drafts
        LogicalSegment.PROPOSAL -> proposals
        LogicalSegment.OPERATION -> operations
        LogicalSegment.SESSION -> sessions
        LogicalSegment.RUN -> runs
        LogicalSegment.MESSAGE -> messages
        LogicalSegment.REFERENCE -> references
    }

    fun rowCount(segment: LogicalSegment): Long = rows(segment).size.toLong()
}

internal data class LogicalBackup(
    val manifest: BackupManifest,
    val content: LogicalBackupContent,
)

internal object ManifestCodec {
    private val fields = listOf(
        "format", "formatVersion", "databaseSchemaVersion", "exportedAt", "sourceGeneration",
        "businessRevision", "appVersionCode", "appVersionName", "entryCount", "totalRows",
        "totalUncompressedBytes", "segments", "contentDigest",
    )
    private val segmentFields = listOf("name", "rows", "uncompressedBytes", "sha256")

    fun encode(manifest: BackupManifest): ByteArray {
        val out = StringBuilder()
        out.append("{\"format\":").append(StrictJson.quote(manifest.format))
        out.append(",\"formatVersion\":").append(manifest.formatVersion)
        out.append(",\"databaseSchemaVersion\":").append(manifest.databaseSchemaVersion)
        out.append(",\"exportedAt\":").append(manifest.exportedAt)
        out.append(",\"sourceGeneration\":").append(StrictJson.quote(manifest.sourceGeneration))
        out.append(",\"businessRevision\":").append(manifest.businessRevision)
        out.append(",\"appVersionCode\":").append(manifest.appVersionCode)
        out.append(",\"appVersionName\":").append(StrictJson.quote(manifest.appVersionName))
        out.append(",\"entryCount\":").append(manifest.entryCount)
        out.append(",\"totalRows\":").append(manifest.totalRows)
        out.append(",\"totalUncompressedBytes\":").append(manifest.totalUncompressedBytes)
        out.append(",\"segments\":[")
        manifest.segments.forEachIndexed { index, segment ->
            if (index > 0) out.append(',')
            out.append("{\"name\":").append(StrictJson.quote(segment.name))
            out.append(",\"rows\":").append(segment.rows)
            out.append(",\"uncompressedBytes\":").append(segment.uncompressedBytes)
            out.append(",\"sha256\":").append(StrictJson.quote(segment.sha256)).append('}')
        }
        out.append("],\"contentDigest\":").append(StrictJson.quote(manifest.contentDigest)).append("}\n")
        return out.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): BackupManifest {
        val root = StrictJson.decodeObject(bytes, BackupLimits.MAX_MANIFEST_BYTES, requireFinalLf = true)
        root.exactFields(fields, "manifest")
        val format = root.string("format", "manifest")
        if (format != BackupLimits.FORMAT) fail(BackupError.InvalidSource, "unsupported manifest format")
        val formatVersion = root.int("formatVersion", "manifest")
        if (formatVersion != BackupLimits.FORMAT_VERSION) {
            fail(BackupError.InvalidSource, "unsupported manifest formatVersion")
        }
        val schemaVersion = root.int("databaseSchemaVersion", "manifest")
        if (schemaVersion != BackupLimits.DATABASE_SCHEMA_VERSION) {
            fail(BackupError.InvalidSource, "unsupported databaseSchemaVersion")
        }
        val exportedAt = root.long("exportedAt", "manifest")
        val sourceGeneration = root.string("sourceGeneration", "manifest")
        if (!canonicalUuid(sourceGeneration)) {
            fail(BackupError.InvalidSource, "manifest.sourceGeneration must be a canonical UUID")
        }
        val businessRevision = root.long("businessRevision", "manifest")
        requireNonNegativeBelowMax(businessRevision, "manifest.businessRevision")
        val appVersionCode = root.long("appVersionCode", "manifest")
        if (appVersionCode < 1) fail(BackupError.InvalidSource, "manifest.appVersionCode must be positive")
        val appVersionName = root.string("appVersionName", "manifest")
        requireNoControl(appVersionName, "manifest.appVersionName", BackupLimits.MAX_APP_VERSION_NAME_CHARS)
        val entryCount = root.int("entryCount", "manifest")
        if (entryCount != BackupLimits.ENTRY_COUNT) {
            fail(BackupError.InvalidSource, "manifest.entryCount must be 13")
        }
        val totalRows = root.long("totalRows", "manifest")
        if (totalRows !in 0..BackupLimits.MAX_ROWS_TOTAL) {
            fail(BackupError.InvalidSource, "manifest.totalRows is outside the v1 limit")
        }
        val totalBytes = root.long("totalUncompressedBytes", "manifest")
        if (totalBytes !in 0..BackupLimits.MAX_TOTAL_UNCOMPRESSED) {
            fail(BackupError.InvalidSource, "manifest.totalUncompressedBytes is outside the v1 limit")
        }
        val segmentArray = root.fields["segments"] as? JsonValue.ArrayValue
            ?: fail(BackupError.InvalidSource, "manifest.segments must be an array")
        if (segmentArray.items.size != BackupLimits.SEGMENT_COUNT) {
            fail(BackupError.InvalidSource, "manifest.segments must contain exactly 12 entries")
        }
        val segments = segmentArray.items.mapIndexed { index, value ->
            val item = value as? JsonValue.ObjectValue
                ?: fail(BackupError.InvalidSource, "manifest.segments[$index] must be an object")
            item.exactFields(segmentFields, "manifest.segments[$index]")
            val name = item.string("name", "manifest.segments[$index]")
            val expected = LogicalSegment.entries[index].path
            if (name != expected) {
                fail(BackupError.InvalidSource, "manifest segment order/name mismatch at $index")
            }
            val rows = item.long("rows", "manifest.segments[$index]")
            if (rows !in 0..BackupLimits.MAX_ROWS_TOTAL) {
                fail(BackupError.InvalidSource, "manifest segment rows outside the v1 limit")
            }
            val uncompressed = item.long("uncompressedBytes", "manifest.segments[$index]")
            if (uncompressed !in 0..BackupLimits.MAX_ENTRY_UNCOMPRESSED) {
                fail(BackupError.InvalidSource, "manifest segment bytes outside the v1 limit")
            }
            val sha = item.string("sha256", "manifest.segments[$index]")
            requireDigest(sha, "manifest.segments[$index].sha256")
            ManifestSegment(name, rows, uncompressed, sha)
        }
        val sumRows = segments.sumOf { it.rows }
        val sumBytes = segments.sumOf { it.uncompressedBytes }
        if (sumRows != totalRows) fail(BackupError.InvalidSource, "manifest totalRows does not match segments")
        if (sumBytes != totalBytes) {
            fail(BackupError.InvalidSource, "manifest totalUncompressedBytes does not match segments")
        }
        val digest = root.string("contentDigest", "manifest")
        requireDigest(digest, "manifest.contentDigest")
        val manifest = BackupManifest(
            format = format,
            formatVersion = formatVersion,
            databaseSchemaVersion = schemaVersion,
            exportedAt = exportedAt,
            sourceGeneration = sourceGeneration,
            businessRevision = businessRevision,
            appVersionCode = appVersionCode,
            appVersionName = appVersionName,
            entryCount = entryCount,
            totalRows = totalRows,
            totalUncompressedBytes = totalBytes,
            segments = segments,
            contentDigest = digest,
        )
        if (contentDigest(manifest) != digest) {
            fail(BackupError.InvalidSource, "manifest contentDigest mismatch")
        }
        return manifest
    }

    fun contentDigest(manifest: BackupManifest): String {
        val out = StringBuilder()
        out.append(BackupLimits.FORMAT).append('\n')
        out.append(manifest.formatVersion).append('\n')
        out.append(manifest.databaseSchemaVersion).append('\n')
        out.append(manifest.sourceGeneration).append('\n')
        out.append(manifest.businessRevision).append('\n')
        for (segment in manifest.segments) {
            out.append(segment.name).append('\n')
            out.append(segment.rows).append('\n')
            out.append(segment.uncompressedBytes).append('\n')
            out.append(segment.sha256).append('\n')
        }
        return sha256Hex(out.toString().toByteArray(StandardCharsets.UTF_8))
    }
}

internal object SegmentCodecs {
    fun encode(row: SegmentRow): String = when (row) {
        is AccountRow -> buildString {
            append("{\"id\":").append(StrictJson.quote(row.id))
            append(",\"name\":").append(StrictJson.quote(row.name))
            append(",\"currency\":").append(StrictJson.quote(row.currency))
            append(",\"version\":").append(row.version)
            append(",\"createdAt\":").append(row.createdAt)
            append(",\"updatedAt\":").append(row.updatedAt).append('}')
        }
        is CategoryRow -> buildString {
            append("{\"id\":").append(StrictJson.quote(row.id))
            append(",\"name\":").append(StrictJson.quote(row.name))
            append(",\"direction\":").append(StrictJson.quote(row.direction))
            append(",\"version\":").append(row.version)
            append(",\"createdAt\":").append(row.createdAt)
            append(",\"updatedAt\":").append(row.updatedAt).append('}')
        }
        is TransactionRow -> buildString {
            append("{\"id\":").append(StrictJson.quote(row.id))
            append(",\"direction\":").append(StrictJson.quote(row.direction))
            append(",\"amountMinor\":").append(row.amountMinor)
            append(",\"currency\":").append(StrictJson.quote(row.currency))
            append(",\"accountId\":").append(StrictJson.quote(row.accountId))
            append(",\"categoryId\":").append(row.categoryId?.let(StrictJson::quote) ?: "null")
            append(",\"memo\":").append(StrictJson.quote(row.memo))
            append(",\"occurredAt\":").append(row.occurredAt)
            append(",\"version\":").append(row.version)
            append(",\"createdAt\":").append(row.createdAt)
            append(",\"updatedAt\":").append(row.updatedAt).append('}')
        }
        is NoteRow -> buildString {
            append("{\"id\":").append(StrictJson.quote(row.id))
            append(",\"body\":").append(StrictJson.quote(row.body))
            append(",\"occurredAt\":").append(row.occurredAt)
            append(",\"version\":").append(row.version)
            append(",\"createdAt\":").append(row.createdAt)
            append(",\"updatedAt\":").append(row.updatedAt)
            append(",\"deletedAt\":").append(row.deletedAt?.toString() ?: "null").append('}')
        }
        is LinkRow -> buildString {
            append("{\"transactionId\":").append(StrictJson.quote(row.transactionId))
            append(",\"noteId\":").append(StrictJson.quote(row.noteId)).append('}')
        }
        is DraftRow -> buildString {
            append("{\"slot\":").append(row.slot)
            append(",\"draftId\":").append(StrictJson.quote(row.draftId))
            append(",\"draftRevision\":").append(row.draftRevision)
            append(",\"state\":").append(StrictJson.quote(row.state))
            append(",\"mode\":").append(StrictJson.quote(row.mode))
            append(",\"recordId\":").append(StrictJson.quote(row.recordId))
            append(",\"baseVersion\":").append(row.baseVersion?.toString() ?: "null")
            append(",\"inputJson\":").append(StrictJson.quote(row.inputJson))
            append(",\"updatedAt\":").append(row.updatedAt).append('}')
        }
        is ProposalRow -> buildString {
            append("{\"id\":").append(StrictJson.quote(row.id))
            append(",\"sessionId\":").append(StrictJson.quote(row.sessionId))
            append(",\"originRunId\":").append(StrictJson.quote(row.originRunId))
            append(",\"lastWriterRunId\":").append(StrictJson.quote(row.lastWriterRunId))
            append(",\"creationRequestId\":").append(StrictJson.quote(row.creationRequestId))
            append(",\"creationPayloadJson\":").append(StrictJson.quote(row.creationPayloadJson))
            append(",\"revision\":").append(row.revision)
            append(",\"payloadJson\":").append(StrictJson.quote(row.payloadJson))
            append(",\"payloadDigest\":").append(StrictJson.quote(row.payloadDigest))
            append(",\"payloadFormatVersion\":").append(row.payloadFormatVersion)
            append(",\"dataGeneration\":").append(StrictJson.quote(row.dataGeneration))
            append(",\"state\":").append(StrictJson.quote(row.state))
            append(",\"activeSlot\":").append(row.activeSlot?.toString() ?: "null")
            append(",\"createdAt\":").append(row.createdAt)
            append(",\"updatedAt\":").append(row.updatedAt).append('}')
        }
        is OperationRow -> buildString {
            append("{\"id\":").append(StrictJson.quote(row.id))
            append(",\"proposalId\":").append(StrictJson.quote(row.proposalId))
            append(",\"confirmedRevision\":").append(row.confirmedRevision)
            append(",\"createdGeneration\":").append(StrictJson.quote(row.createdGeneration))
            append(",\"frozenPayloadJson\":").append(StrictJson.quote(row.frozenPayloadJson))
            append(",\"payloadDigest\":").append(StrictJson.quote(row.payloadDigest))
            append(",\"payloadFormatVersion\":").append(row.payloadFormatVersion)
            append(",\"state\":").append(StrictJson.quote(row.state))
            append(",\"createdAt\":").append(row.createdAt)
            append(",\"finishedAt\":").append(row.finishedAt?.toString() ?: "null")
            append(",\"resultJson\":").append(row.resultJson?.let(StrictJson::quote) ?: "null")
            append(",\"rejectionCode\":").append(row.rejectionCode?.let(StrictJson::quote) ?: "null").append('}')
        }
        is SessionRow -> buildString {
            append("{\"id\":").append(StrictJson.quote(row.id))
            append(",\"title\":").append(row.title?.let(StrictJson::quote) ?: "null")
            append(",\"createdAt\":").append(row.createdAt)
            append(",\"updatedAt\":").append(row.updatedAt)
            append(",\"historyVersion\":").append(row.historyVersion)
            append(",\"nextMessageSequence\":").append(row.nextMessageSequence).append('}')
        }
        is RunRow -> buildString {
            append("{\"id\":").append(StrictJson.quote(row.id))
            append(",\"sessionId\":").append(StrictJson.quote(row.sessionId))
            append(",\"dataGeneration\":").append(StrictJson.quote(row.dataGeneration))
            append(",\"processOwnerId\":").append(StrictJson.quote(row.processOwnerId))
            append(",\"receiverIdentity\":").append(StrictJson.quote(row.receiverIdentity))
            append(",\"authEpoch\":").append(row.authEpoch)
            append(",\"proposalCreationRequestId\":").append(StrictJson.quote(row.proposalCreationRequestId))
            append(",\"historyBoundarySequence\":").append(row.historyBoundarySequence)
            append(",\"historyBoundaryVersion\":").append(row.historyBoundaryVersion)
            append(",\"state\":").append(StrictJson.quote(row.state))
            append(",\"activeSlot\":").append(row.activeSlot?.toString() ?: "null")
            append(",\"startedAt\":").append(row.startedAt)
            append(",\"finishedAt\":").append(row.finishedAt?.toString() ?: "null")
            append(",\"safeErrorCode\":").append(row.safeErrorCode?.let(StrictJson::quote) ?: "null").append('}')
        }
        is MessageRow -> buildString {
            append("{\"id\":").append(StrictJson.quote(row.id))
            append(",\"sessionId\":").append(StrictJson.quote(row.sessionId))
            append(",\"runId\":").append(StrictJson.quote(row.runId))
            append(",\"sequence\":").append(row.sequence)
            append(",\"role\":").append(StrictJson.quote(row.role))
            append(",\"body\":").append(StrictJson.quote(row.body))
            append(",\"createdAt\":").append(row.createdAt)
            append(",\"resultKind\":").append(StrictJson.quote(row.resultKind))
            append(",\"resultJson\":").append(row.resultJson?.let(StrictJson::quote) ?: "null")
            append(",\"proposalId\":").append(row.proposalId?.let(StrictJson::quote) ?: "null")
            append(",\"proposalRevision\":").append(row.proposalRevision?.toString() ?: "null").append('}')
        }
        is ReferenceRow -> buildString {
            append("{\"messageId\":").append(StrictJson.quote(row.messageId))
            append(",\"ordinal\":").append(row.ordinal)
            append(",\"transactionId\":").append(row.transactionId?.let(StrictJson::quote) ?: "null")
            append(",\"noteId\":").append(row.noteId?.let(StrictJson::quote) ?: "null").append('}')
        }
    }

    fun decode(segment: LogicalSegment, value: JsonValue.ObjectValue): SegmentRow {
        val context = segment.path
        return when (segment) {
            LogicalSegment.ACCOUNT -> {
                value.exactFields(segment.fields, context)
                AccountRow(
                    value.string("id", context), value.string("name", context),
                    value.string("currency", context), value.long("version", context),
                    value.long("createdAt", context), value.long("updatedAt", context),
                )
            }
            LogicalSegment.CATEGORY -> {
                value.exactFields(segment.fields, context)
                CategoryRow(
                    value.string("id", context), value.string("name", context),
                    value.string("direction", context), value.long("version", context),
                    value.long("createdAt", context), value.long("updatedAt", context),
                )
            }
            LogicalSegment.TRANSACTION -> {
                value.exactFields(segment.fields, context)
                TransactionRow(
                    value.string("id", context), value.string("direction", context),
                    value.long("amountMinor", context), value.string("currency", context),
                    value.string("accountId", context), value.nullableString("categoryId", context),
                    value.string("memo", context), value.long("occurredAt", context),
                    value.long("version", context), value.long("createdAt", context),
                    value.long("updatedAt", context),
                )
            }
            LogicalSegment.NOTE -> {
                value.exactFields(segment.fields, context)
                NoteRow(
                    value.string("id", context), value.string("body", context),
                    value.long("occurredAt", context), value.long("version", context),
                    value.long("createdAt", context), value.long("updatedAt", context),
                    value.nullableLong("deletedAt", context),
                )
            }
            LogicalSegment.LINK -> {
                value.exactFields(segment.fields, context)
                LinkRow(value.string("transactionId", context), value.string("noteId", context))
            }
            LogicalSegment.DRAFT -> {
                value.exactFields(segment.fields, context)
                DraftRow(
                    value.int("slot", context), value.string("draftId", context),
                    value.long("draftRevision", context), value.string("state", context),
                    value.string("mode", context), value.string("recordId", context),
                    value.nullableLong("baseVersion", context), value.string("inputJson", context),
                    value.long("updatedAt", context),
                )
            }
            LogicalSegment.PROPOSAL -> {
                value.exactFields(segment.fields, context)
                ProposalRow(
                    value.string("id", context), value.string("sessionId", context),
                    value.string("originRunId", context), value.string("lastWriterRunId", context),
                    value.string("creationRequestId", context),
                    value.string("creationPayloadJson", context), value.long("revision", context),
                    value.string("payloadJson", context), value.string("payloadDigest", context),
                    value.int("payloadFormatVersion", context), value.string("dataGeneration", context),
                    value.string("state", context), value.nullableInt("activeSlot", context),
                    value.long("createdAt", context), value.long("updatedAt", context),
                )
            }
            LogicalSegment.OPERATION -> {
                value.exactFields(segment.fields, context)
                OperationRow(
                    value.string("id", context), value.string("proposalId", context),
                    value.long("confirmedRevision", context), value.string("createdGeneration", context),
                    value.string("frozenPayloadJson", context), value.string("payloadDigest", context),
                    value.int("payloadFormatVersion", context), value.string("state", context),
                    value.long("createdAt", context), value.nullableLong("finishedAt", context),
                    value.nullableString("resultJson", context), value.nullableString("rejectionCode", context),
                )
            }
            LogicalSegment.SESSION -> {
                value.exactFields(segment.fields, context)
                SessionRow(
                    value.string("id", context), value.nullableString("title", context),
                    value.long("createdAt", context), value.long("updatedAt", context),
                    value.long("historyVersion", context), value.long("nextMessageSequence", context),
                )
            }
            LogicalSegment.RUN -> {
                value.exactFields(segment.fields, context)
                RunRow(
                    value.string("id", context), value.string("sessionId", context),
                    value.string("dataGeneration", context), value.string("processOwnerId", context),
                    value.string("receiverIdentity", context), value.long("authEpoch", context),
                    value.string("proposalCreationRequestId", context),
                    value.long("historyBoundarySequence", context),
                    value.long("historyBoundaryVersion", context), value.string("state", context),
                    value.nullableInt("activeSlot", context), value.long("startedAt", context),
                    value.nullableLong("finishedAt", context), value.nullableString("safeErrorCode", context),
                )
            }
            LogicalSegment.MESSAGE -> {
                value.exactFields(segment.fields, context)
                MessageRow(
                    value.string("id", context), value.string("sessionId", context),
                    value.string("runId", context), value.long("sequence", context),
                    value.string("role", context), value.string("body", context),
                    value.long("createdAt", context), value.string("resultKind", context),
                    value.nullableString("resultJson", context), value.nullableString("proposalId", context),
                    value.nullableLong("proposalRevision", context),
                )
            }
            LogicalSegment.REFERENCE -> {
                value.exactFields(segment.fields, context)
                ReferenceRow(
                    value.string("messageId", context), value.int("ordinal", context),
                    value.nullableString("transactionId", context), value.nullableString("noteId", context),
                )
            }
        }
    }
}

internal fun buildContent(rows: Map<LogicalSegment, List<SegmentRow>>): LogicalBackupContent =
    LogicalBackupContent(
        accounts = rows[LogicalSegment.ACCOUNT].orEmpty().map { it as AccountRow },
        categories = rows[LogicalSegment.CATEGORY].orEmpty().map { it as CategoryRow },
        transactions = rows[LogicalSegment.TRANSACTION].orEmpty().map { it as TransactionRow },
        notes = rows[LogicalSegment.NOTE].orEmpty().map { it as NoteRow },
        links = rows[LogicalSegment.LINK].orEmpty().map { it as LinkRow },
        drafts = rows[LogicalSegment.DRAFT].orEmpty().map { it as DraftRow },
        proposals = rows[LogicalSegment.PROPOSAL].orEmpty().map { it as ProposalRow },
        operations = rows[LogicalSegment.OPERATION].orEmpty().map { it as OperationRow },
        sessions = rows[LogicalSegment.SESSION].orEmpty().map { it as SessionRow },
        runs = rows[LogicalSegment.RUN].orEmpty().map { it as RunRow },
        messages = rows[LogicalSegment.MESSAGE].orEmpty().map { it as MessageRow },
        references = rows[LogicalSegment.REFERENCE].orEmpty().map { it as ReferenceRow },
    )

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return buildString(digest.size * 2) {
        for (byte in digest) {
            append(HEX[byte.toInt() shr 4 and 15])
            append(HEX[byte.toInt() and 15])
        }
    }
}

private val HEX = "0123456789abcdef"

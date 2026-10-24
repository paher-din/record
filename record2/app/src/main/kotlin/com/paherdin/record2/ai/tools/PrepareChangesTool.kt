package com.paherdin.record2.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.paherdin.record2.proposals.ProposalBudget
import com.paherdin.record2.proposals.ProposalError
import com.paherdin.record2.proposals.ProposalException
import com.paherdin.record2.proposals.ProposalIntent
import com.paherdin.record2.proposals.ProposalOperation
import com.paherdin.record2.proposals.ProposalRunContext
import com.paherdin.record2.proposals.ProposalState
import com.paherdin.record2.proposals.RecordPair
import com.paherdin.record2.proposals.RecordRef
import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceInput
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
internal data class PrepareChangesArgs(
    val proposalId: String? = null,
    val expectedRevision: Long? = null,
    val ops: List<PrepareOpArgs>,
)

@Serializable
internal data class PrepareOpArgs(
    val op: String,
    val localRef: String? = null,
    val id: String? = null,
    val expectedVersion: Long? = null,
    val direction: String? = null,
    val amountText: String? = null,
    val accountName: String? = null,
    val categoryName: String? = null,
    val memo: String? = null,
    val body: String? = null,
    val localDateTime: String? = null,
    val zoneId: String? = null,
    val pairs: List<LinkPairArgs>? = null,
)

@Serializable
internal data class LinkPairArgs(
    val transaction: String,
    val transactionVersion: Long? = null,
    val note: String,
    val noteVersion: Long? = null,
)

/**
 * `prepare_changes` (Spec §6): creates or revises one of the six structured proposal types through
 * the real C0 proposal service with revision CAS. It never writes formal business facts — a
 * successful result is always an editable proposal awaiting native user confirmation, and the
 * result says so explicitly so the model cannot claim a commit. Account and category are resolved
 * by their seeded human names; unknown or direction-mismatched names are validation issues, never
 * silently widened vocabulary.
 */
internal class PrepareChangesTool(private val context: RecordToolContext) :
    SimpleTool<PrepareChangesArgs>(
        argsType = typeToken<PrepareChangesArgs>(),
        name = "prepare_changes",
        description = "Create or revise a structured change proposal (nothing is committed to " +
            "formal records until the user confirms natively). Omit proposalId to create; pass " +
            "proposalId plus its current expectedRevision to revise. op is one of " +
            "CREATE_TRANSACTION, UPDATE_TRANSACTION, CREATE_NOTE, UPDATE_NOTE, LINK, UNLINK. " +
            "New records created inside the same proposal are referenced by localRef like #t1; " +
            "existing records are referenced by their real UUID and expectedVersion.",
    ) {
    override suspend fun execute(args: PrepareChangesArgs): String {
        context.guard(
            "prepare_changes",
            RecordToolContext.toolJson.encodeToString(PrepareChangesArgs.serializer(), args),
            RecordToolBudget.PREPARE_CALLS_PER_RUN,
        )
        val issues = ToolIssues()
        if (args.ops.isEmpty() || args.ops.size > ProposalBudget.MAX_OPERATIONS) {
            issues.add("ops", "ops must contain 1..${ProposalBudget.MAX_OPERATIONS} operations")
            return context.charge(issues.result())
        }
        if ((args.proposalId == null) != (args.expectedRevision == null)) {
            issues.add("proposalId", "proposalId and expectedRevision must be provided together")
        }
        args.proposalId?.let {
            if (!ProposalBudget.IDENTITY.matches(it)) issues.add("proposalId", "proposalId must be a UUID")
        }
        args.expectedRevision?.let {
            if (it < 1) issues.add("expectedRevision", "expectedRevision must be >= 1")
        }
        if (issues.present()) return context.charge(issues.result())
        val intents = try {
            buildIntents(args, issues)
        } catch (error: ToolIssuesSignal) {
            return context.charge(error.payload)
        }
        if (issues.present()) {
            context.onClarification(issues.messages())
            return context.charge(issues.result())
        }
        val runContext = ProposalRunContext(
            sessionId = context.start.sessionId,
            runId = context.start.runId,
            processOwnerId = context.start.processOwnerId,
            receiverIdentity = context.start.receiverIdentity,
            authEpoch = context.start.authEpoch,
        )
        try {
            val view = if (args.proposalId == null) {
                context.deps.proposals.prepare(runContext, intents)
            } else {
                context.deps.proposals.revise(runContext, args.proposalId, args.expectedRevision!!, intents)
            }
            context.onProposalPrepared()
            return context.charge(success(view))
        } catch (error: ProposalException) {
            return when (error.code) {
                ProposalError.RunInvalidated, ProposalError.GenerationMismatch,
                ProposalError.RecoveryRequired, ProposalError.RecoverableIO, ProposalError.StorageFull,
                -> context.poisonFrom(error)
                ProposalError.ActiveProposalExists -> context.charge(activeProposalExists())
                ProposalError.BudgetExceeded -> context.charge(
                    soft("BudgetExceeded", "payload", "payload exceeds the proposal budget; split into fewer operations")
                )
                ProposalError.CreationRequestConflict -> context.charge(
                    soft(
                        "CreationRequestConflict",
                        "ops",
                        "this run already created a proposal with different content; revise it with proposalId + expectedRevision instead",
                    )
                )
                else -> context.charge(
                    soft(
                        "Validation",
                        "ops",
                        "operations rejected (${error.code}); check amount format (positive decimal, <=2 fraction digits), " +
                            "absolute localDateTime (yyyy-MM-ddTHH:mm:ss), valid zoneId, real ids/versions and localRef shapes",
                    )
                )
            }
        }
    }

    private class ToolIssuesSignal(val payload: String) : RuntimeException(payload)

    private suspend fun buildIntents(args: PrepareChangesArgs, issues: ToolIssues): List<ProposalIntent> =
        args.ops.mapIndexed { index, op ->
            val at = "ops[$index]"
            when (op.op) {
                "CREATE_TRANSACTION" -> ProposalIntent.CreateTransaction(
                    // Placeholders are only reachable once the same op already recorded an issue;
                    // the service call below is skipped while any issue is present.
                    localRef = required(op.localRef, "$at.localRef", issues, ::localRefShape) ?: "#invalid",
                    input = transactionInput(op, at, issues),
                )
                "UPDATE_TRANSACTION" -> ProposalIntent.UpdateTransaction(
                    id = identity(op.id, "$at.id", issues),
                    expectedVersion = requiredVersion(op.expectedVersion, "$at.expectedVersion", issues),
                    input = transactionInput(op, at, issues),
                )
                "CREATE_NOTE" -> ProposalIntent.CreateNote(
                    localRef = required(op.localRef, "$at.localRef", issues, ::localRefShape) ?: "#invalid",
                    body = required(op.body, "$at.body", issues) ?: "",
                    localDateTime = required(op.localDateTime, "$at.localDateTime", issues, ::dateTimeShape) ?: "",
                    zoneId = required(op.zoneId, "$at.zoneId", issues, ::zoneShape) ?: "",
                )
                "UPDATE_NOTE" -> ProposalIntent.UpdateNote(
                    id = identity(op.id, "$at.id", issues),
                    expectedVersion = requiredVersion(op.expectedVersion, "$at.expectedVersion", issues),
                    body = required(op.body, "$at.body", issues) ?: "",
                    localDateTime = required(op.localDateTime, "$at.localDateTime", issues, ::dateTimeShape) ?: "",
                    zoneId = required(op.zoneId, "$at.zoneId", issues, ::zoneShape) ?: "",
                )
                "LINK" -> ProposalIntent.LinkRecords(pairs(op.pairs, at, issues))
                "UNLINK" -> ProposalIntent.UnlinkRecords(pairs(op.pairs, at, issues))
                else -> {
                    issues.add("$at.op", "op must be one of the six proposal types")
                    throw ToolIssuesSignal(issues.result())
                }
            }
        }

    private suspend fun transactionInput(op: PrepareOpArgs, at: String, issues: ToolIssues): FinanceInput {
        val direction = when (op.direction) {
            "INCOME" -> FinanceDirection.INCOME
            "EXPENSE" -> FinanceDirection.EXPENSE
            else -> {
                issues.add("$at.direction", "direction must be INCOME or EXPENSE")
                null
            }
        }
        val account = required(op.accountName, "$at.accountName", issues)?.let { name ->
            ToolCatalog.accountVersionByName(context, name) ?: run {
                issues.add("$at.accountName", "unknown account \"$name\"; available: ${ToolCatalog.catalog(context).account.name}")
                null
            }
        }
        val category = op.categoryName?.takeIf { it.isNotBlank() }?.let { name ->
            val resolved = direction?.let { ToolCatalog.categoryByName(context, name, it) }
            if (resolved == null) {
                issues.add(
                    "$at.categoryName",
                    "category \"$name\" is unknown or does not match direction ${direction?.name ?: "?"}; " +
                        "valid ${direction?.name ?: ""} categories: ${ToolCatalog.catalog(context).categories.filter { it.direction == direction }.joinToString("、") { it.name }}",
                )
                null
            } else {
                resolved
            }
        }
        return FinanceInput(
            amountText = required(op.amountText, "$at.amountText", issues, ::amountShape) ?: "1",
            direction = direction ?: FinanceDirection.EXPENSE,
            accountId = account?.first ?: "",
            accountVersion = account?.second ?: 0,
            categoryId = category?.id,
            categoryVersion = category?.version,
            memo = op.memo ?: "",
            localDateTime = required(op.localDateTime, "$at.localDateTime", issues, ::dateTimeShape) ?: "",
            zoneId = required(op.zoneId, "$at.zoneId", issues, ::zoneShape) ?: "",
        )
    }

    private fun pairs(raw: List<LinkPairArgs>?, at: String, issues: ToolIssues): List<RecordPair> {
        if (raw.isNullOrEmpty()) {
            issues.add("$at.pairs", "pairs must be a non-empty list")
            throw ToolIssuesSignal(issues.result())
        }
        return raw.mapIndexed { pairIndex, pair ->
            val pairAt = "$at.pairs[$pairIndex]"
            RecordPair(
                transaction = endpoint(pair.transaction, pair.transactionVersion, transaction = true, "$pairAt.transaction", issues),
                transactionVersion = versionOf(pair.transaction, pair.transactionVersion, "$pairAt.transactionVersion", issues),
                note = endpoint(pair.note, pair.noteVersion, transaction = false, "$pairAt.note", issues),
                noteVersion = versionOf(pair.note, pair.noteVersion, "$pairAt.noteVersion", issues),
            )
        }
    }

    private fun endpoint(
        value: String,
        version: Long?,
        transaction: Boolean,
        at: String,
        issues: ToolIssues,
    ): RecordRef = if (value.startsWith("#")) {
        if (!ProposalBudget.LOCAL_REF.matches(value)) {
            issues.add(at, "localRef must match ${ProposalBudget.LOCAL_REF.pattern}")
        }
        RecordRef.Local(value)
    } else if (ProposalBudget.IDENTITY.matches(value)) {
        if (transaction) RecordRef.Transaction(value) else RecordRef.Note(value)
    } else {
        issues.add(at, "must be a localRef (#name) or a real record UUID")
        RecordRef.Local("#invalid")
    }

    private fun versionOf(value: String, version: Long?, at: String, issues: ToolIssues): Long =
        if (value.startsWith("#")) {
            if (version != null && version != 1L) issues.add(at, "a localRef endpoint must use version 1")
            1L
        } else {
            if (version == null || version < 1) {
                issues.add(at, "existing record endpoints require their current version")
                0L
            } else {
                version
            }
        }

    private fun required(value: String?, at: String, issues: ToolIssues, shape: ((String) -> String?)? = null): String? {
        if (value.isNullOrBlank()) {
            issues.add(at, "field is required")
            return null
        }
        shape?.invoke(value)?.let { message ->
            issues.add(at, message)
            return null
        }
        return value
    }

    private fun requiredVersion(value: Long?, at: String, issues: ToolIssues): Long {
        if (value == null || value < 1) {
            issues.add(at, "field is required and must be >= 1")
            return 1L
        }
        return value
    }

    private fun identity(value: String?, at: String, issues: ToolIssues): String {
        if (value == null || !ProposalBudget.IDENTITY.matches(value)) {
            issues.add(at, "must be the real record UUID from search_records/get_record_detail")
            return "00000000-0000-0000-0000-000000000000"
        }
        return value
    }

    private fun localRefShape(value: String): String? =
        if (ProposalBudget.LOCAL_REF.matches(value)) null else "localRef must match ${ProposalBudget.LOCAL_REF.pattern}"

    private fun dateTimeShape(value: String): String? = try {
        LocalDateTime.parse(value)
        null
    } catch (error: RuntimeException) {
        "localDateTime must be absolute local time, yyyy-MM-ddTHH:mm:ss"
    }

    private fun zoneShape(value: String): String? =
        if (runCatching { ZoneId.of(value) }.isSuccess) null else "zoneId must be a valid IANA zone id"

    private fun amountShape(value: String): String? {
        val minor = runCatching { com.paherdin.record2.records.Money.parseMinor(value) }.getOrNull()
        return if (minor != null && minor > 0) null else "amountText must be a positive decimal with at most two fraction digits"
    }

    private suspend fun success(view: com.paherdin.record2.proposals.ProposalView): String {
        val operations = buildJsonArray {
            view.operations.forEach { operation ->
                add(buildJsonObject {
                    put("type", operationType(operation))
                    when (operation) {
                        is ProposalOperation.CreateTransaction -> {
                            put("localRef", operation.localRef)
                            put("direction", operation.direction.name)
                            put("amountMinor", operation.amountMinor)
                            put("memo", operation.memo)
                        }
                        is ProposalOperation.UpdateTransaction -> {
                            put("id", operation.id)
                            put("direction", operation.direction.name)
                            put("amountMinor", operation.amountMinor)
                            put("memo", operation.memo)
                        }
                        is ProposalOperation.CreateNote -> put("localRef", operation.localRef)
                        is ProposalOperation.UpdateNote -> put("id", operation.id)
                        is ProposalOperation.LinkRecords -> put("pairs", operation.pairs.size)
                        is ProposalOperation.UnlinkRecords -> put("pairs", operation.pairs.size)
                    }
                })
            }
        }
        return buildJsonObject {
            put("ok", true)
            put("proposalId", view.id)
            put("revision", view.revision)
            put("state", view.state.name)
            put("operations", operations)
            put("committed", false)
            put(
                "note",
                "提案已保存并等待用户在原生界面确认；正式记录尚未写入。确认前不会产生任何业务事实。",
            )
        }.toString()
    }

    private suspend fun activeProposalExists(): String {
        val existing = runCatching {
            context.deps.proposals.activeProposal(context.start.dataGeneration, context.start.sessionId)
        }.getOrNull()
        context.onClarification(listOf("active proposal exists; revise it instead of creating a second one"))
        return buildJsonObject {
            put("ok", false)
            put("error", "ActiveProposalExists")
            put("issues", buildJsonArray {
                add(buildJsonObject {
                    put("field", "ops")
                    put("message", "this session already has an active proposal; revise it instead")
                })
            })
            if (existing != null) {
                put("existingProposalId", existing.id)
                put("existingRevision", existing.revision)
                put("existingState", existing.state.name)
            }
        }.toString()
    }

    private fun soft(error: String, field: String, message: String): String {
        context.onClarification(listOf("$field: $message"))
        return buildJsonObject {
            put("ok", false)
            put("error", error)
            put("issues", buildJsonArray {
                add(buildJsonObject {
                    put("field", field)
                    put("message", message)
                })
            })
        }.toString()
    }

    private fun operationType(operation: ProposalOperation): String = when (operation) {
        is ProposalOperation.CreateTransaction -> "CREATE_TRANSACTION"
        is ProposalOperation.UpdateTransaction -> "UPDATE_TRANSACTION"
        is ProposalOperation.CreateNote -> "CREATE_NOTE"
        is ProposalOperation.UpdateNote -> "UPDATE_NOTE"
        is ProposalOperation.LinkRecords -> "LINK_RECORDS"
        is ProposalOperation.UnlinkRecords -> "UNLINK_RECORDS"
    }
}

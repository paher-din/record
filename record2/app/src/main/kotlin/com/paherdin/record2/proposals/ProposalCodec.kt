package com.paherdin.record2.proposals

import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceException
import com.paherdin.record2.records.FinanceInput
import com.paherdin.record2.records.FinanceTime
import com.paherdin.record2.records.Money
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Frozen C0 encoding and input budget. Canonical bytes are a durable identity, so the field order
 * and escaping below belong to the persisted contract rather than to a serializer's behaviour.
 */
internal object ProposalBudget {
    const val FORMAT_VERSION = 1
    const val MAX_PAYLOAD_BYTES = 65_536
    const val MAX_OPERATIONS = 32
    const val MAX_LINK_PAIRS = 32
    const val MAX_MEMO_CHARS = 1_000
    const val MAX_NOTE_BODY_CHARS = 20_000
    const val MAX_DEPTH = 6
    val LOCAL_REF: Regex = Regex("#[A-Za-z0-9_-]{1,64}")
    val IDENTITY: Regex = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}

@Serializable
internal data class ProposalPayloadDocument(val formatVersion: Int, val operations: List<ProposalOperation>)

internal object ProposalCodec {
    private val json = Json { classDiscriminator = "type" }
    private const val HEX = "0123456789abcdef"

    fun normalize(intents: List<ProposalIntent>, clock: Clock): List<ProposalOperation> {
        if (intents.isEmpty()) fail(ProposalError.Validation)
        if (intents.size > ProposalBudget.MAX_OPERATIONS) fail(ProposalError.BudgetExceeded)
        val creates = LinkedHashMap<String, ProposalOperation>()
        val updates = LinkedHashMap<String, ProposalOperation>()
        val links = ArrayList<RecordPair>()
        val unlinks = ArrayList<RecordPair>()
        for (intent in intents) when (intent) {
            is ProposalIntent.CreateTransaction -> {
                val ref = localRef(intent.localRef)
                val category = category(intent.input)
                declare(creates, ref, ProposalOperation.CreateTransaction(
                    ref, intent.input.direction, amount(intent.input.amountText),
                    identity(intent.input.accountId), version(intent.input.accountVersion),
                    category.first, category.second,
                    memo(intent.input.memo), time(intent.input.localDateTime, intent.input.zoneId, clock),
                ))
            }
            is ProposalIntent.UpdateTransaction -> {
                val id = identity(intent.id)
                val category = category(intent.input)
                merge(updates, "T$id", ProposalOperation.UpdateTransaction(
                    id, version(intent.expectedVersion), intent.input.direction,
                    amount(intent.input.amountText), identity(intent.input.accountId),
                    version(intent.input.accountVersion), category.first, category.second,
                    memo(intent.input.memo), time(intent.input.localDateTime, intent.input.zoneId, clock),
                ))
            }
            is ProposalIntent.CreateNote -> {
                val ref = localRef(intent.localRef)
                declare(creates, ref, ProposalOperation.CreateNote(ref, body(intent.body), time(intent.localDateTime, intent.zoneId, clock)))
            }
            is ProposalIntent.UpdateNote -> {
                val id = identity(intent.id)
                merge(updates, "N$id", ProposalOperation.UpdateNote(id, version(intent.expectedVersion), body(intent.body), time(intent.localDateTime, intent.zoneId, clock)))
            }
            is ProposalIntent.LinkRecords -> links += checkedPairs(intent.pairs)
            is ProposalIntent.UnlinkRecords -> unlinks += checkedPairs(intent.pairs)
        }
        val linked = relationSet(links, unlinks)
        val unlinked = relationSet(unlinks, links)
        // Per-intent budgets can add up once the intents merge, so the merged set is rechecked.
        if (linked.size > ProposalBudget.MAX_LINK_PAIRS) fail(ProposalError.BudgetExceeded)
        if (unlinked.size > ProposalBudget.MAX_LINK_PAIRS) fail(ProposalError.BudgetExceeded)
        val operations = buildList {
            addAll(creates.values)
            addAll(updates.values)
            if (linked.isNotEmpty()) add(ProposalOperation.LinkRecords(linked))
            if (unlinked.isNotEmpty()) add(ProposalOperation.UnlinkRecords(unlinked))
        }
        if (operations.isEmpty()) fail(ProposalError.Validation)
        return resolveOrder(operations)
    }

    /**
     * Orders operations so that every localRef is declared before it is consumed, and rejects a
     * reference whose declared kind does not match the slot it is used in. CREATE has the lowest
     * rank and references nothing, so an undeclared reference means a dangling or cyclic payload.
     */
    fun resolveOrder(operations: List<ProposalOperation>): List<ProposalOperation> {
        val ordered = operations.sortedWith(compareBy({ typeRank(it) }, { targetKey(it) }))
        val declared = HashMap<String, Boolean>()
        for (operation in ordered) {
            for (pair in pairsOf(operation)) {
                requireKind(declared, pair.transaction, transaction = true)
                requireKind(declared, pair.note, transaction = false)
            }
            when (operation) {
                is ProposalOperation.CreateTransaction -> declared[operation.localRef] = true
                is ProposalOperation.CreateNote -> declared[operation.localRef] = false
                else -> Unit
            }
        }
        return ordered
    }

    fun canonical(operations: List<ProposalOperation>): String {
        val out = StringBuilder()
        out.append("{\"formatVersion\":").append(ProposalBudget.FORMAT_VERSION).append(",\"operations\":[")
        resolveOrder(operations).forEachIndexed { index, operation ->
            if (index > 0) out.append(',')
            operation(out, operation)
        }
        val canonical = out.append("]}").toString()
        // Per-operand limits alone still admit a payload too large to read back, so the total is bounded too.
        if (canonical.toByteArray(Charsets.UTF_8).size > ProposalBudget.MAX_PAYLOAD_BYTES) fail(ProposalError.BudgetExceeded)
        return canonical
    }

    fun digest(canonicalJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) append(HEX[byte.toInt() shr 4 and 15]).append(HEX[byte.toInt() and 15])
        }
    }

    fun decode(canonicalJson: String): List<ProposalOperation> = try {
        require(canonicalJson.toByteArray(Charsets.UTF_8).size <= ProposalBudget.MAX_PAYLOAD_BYTES)
        val document = json.decodeFromString<ProposalPayloadDocument>(canonicalJson)
        require(document.formatVersion == ProposalBudget.FORMAT_VERSION)
        require(document.operations.isNotEmpty() && document.operations.size <= ProposalBudget.MAX_OPERATIONS)
        document.operations.forEach { operation ->
            when (operation) {
                is ProposalOperation.LinkRecords -> requirePairs(operation.pairs)
                is ProposalOperation.UnlinkRecords -> requirePairs(operation.pairs)
                else -> Unit
            }
        }
        document.operations
    } catch (error: RuntimeException) {
        throw ProposalException(ProposalError.RecoveryRequired, error)
    }

    // A persisted relation operation outside the closed pair budget can only be a foreign or
    // corrupt row; canonical output never emits one.
    private fun requirePairs(pairs: List<RecordPair>) {
        require(pairs.isNotEmpty() && pairs.size <= ProposalBudget.MAX_LINK_PAIRS)
    }

    private fun checkedPairs(pairs: List<RecordPair>): List<RecordPair> {
        if (pairs.isEmpty()) fail(ProposalError.Validation)
        if (pairs.size > ProposalBudget.MAX_LINK_PAIRS) fail(ProposalError.BudgetExceeded)
        for (pair in pairs) {
            shape(pair.transaction)
            shape(pair.note)
            endpointVersion(pair.transaction, pair.transactionVersion)
            endpointVersion(pair.note, pair.noteVersion)
        }
        return pairs
    }

    private fun shape(ref: RecordRef) {
        when (ref) {
            is RecordRef.Local -> localRef(ref.localRef)
            is RecordRef.Transaction -> identity(ref.id)
            is RecordRef.Note -> identity(ref.id)
        }
    }

    private fun endpointVersion(ref: RecordRef, value: Long) {
        // A new object always starts at version 1, so any other baseline is a fabricated identity.
        if (ref is RecordRef.Local) {
            if (value != 1L) fail(ProposalError.Validation)
        } else {
            version(value)
        }
    }

    private fun requireKind(declared: Map<String, Boolean>, ref: RecordRef, transaction: Boolean) {
        val local = ref as? RecordRef.Local ?: return
        val kind = declared[local.localRef] ?: fail(ProposalError.Validation)
        if (kind != transaction) fail(ProposalError.Validation)
    }

    private fun pairsOf(operation: ProposalOperation): List<RecordPair> = when (operation) {
        is ProposalOperation.LinkRecords -> operation.pairs
        is ProposalOperation.UnlinkRecords -> operation.pairs
        else -> emptyList()
    }

    private fun relationSet(pairs: List<RecordPair>, excluded: List<RecordPair>): List<RecordPair> {
        if (pairs.isEmpty()) return emptyList()
        val unique = LinkedHashMap<String, RecordPair>()
        for (pair in pairs) {
            val key = endpointKey(pair)
            val previous = unique.putIfAbsent(key, pair)
            if (previous != null && previous != pair) fail(ProposalError.Validation)
            if (excluded.any { endpointKey(it) == key }) fail(ProposalError.Validation)
        }
        return unique.values.sortedBy { endpointKey(it) }
    }

    private fun endpointKey(pair: RecordPair) = refKey(pair.transaction) + "|" + refKey(pair.note)

    private fun refKey(ref: RecordRef): String = when (ref) {
        is RecordRef.Local -> "L" + ref.localRef
        is RecordRef.Transaction -> "T" + ref.id
        is RecordRef.Note -> "N" + ref.id
    }

    private fun typeRank(operation: ProposalOperation) = when (operation) {
        is ProposalOperation.CreateTransaction -> 0
        is ProposalOperation.CreateNote -> 1
        is ProposalOperation.UpdateTransaction -> 2
        is ProposalOperation.UpdateNote -> 3
        is ProposalOperation.LinkRecords -> 4
        is ProposalOperation.UnlinkRecords -> 5
    }

    private fun targetKey(operation: ProposalOperation) = when (operation) {
        is ProposalOperation.CreateTransaction -> operation.localRef
        is ProposalOperation.CreateNote -> operation.localRef
        is ProposalOperation.UpdateTransaction -> operation.id
        is ProposalOperation.UpdateNote -> operation.id
        else -> ""
    }

    private fun declare(creates: MutableMap<String, ProposalOperation>, ref: String, operation: ProposalOperation) {
        if (creates.putIfAbsent(ref, operation) != null) fail(ProposalError.Validation)
    }

    private fun merge(updates: MutableMap<String, ProposalOperation>, key: String, operation: ProposalOperation) {
        val previous = updates.putIfAbsent(key, operation)
        if (previous != null && previous != operation) fail(ProposalError.Validation)
    }

    private fun localRef(value: String): String {
        if (!ProposalBudget.LOCAL_REF.matches(value)) fail(ProposalError.Validation)
        return value
    }

    private fun identity(value: String): String {
        if (!ProposalBudget.IDENTITY.matches(value)) fail(ProposalError.Validation)
        return value
    }

    private fun version(value: Long): Long {
        if (value < 1 || value == Long.MAX_VALUE) fail(ProposalError.Validation)
        return value
    }

    private fun amount(text: String): Long = try {
        Money.parseMinor(text)
    } catch (error: IllegalArgumentException) {
        throw ProposalException(ProposalError.Validation, error)
    }

    private fun category(input: FinanceInput): Pair<String?, Long?> {
        val id = input.categoryId?.let { identity(it) }
        val version = input.categoryVersion?.let { version(it) }
        if ((id == null) != (version == null)) fail(ProposalError.Validation)
        return id to version
    }

    private fun memo(value: String): String {
        if (value.length > ProposalBudget.MAX_MEMO_CHARS) fail(ProposalError.BudgetExceeded)
        return value
    }

    private fun body(value: String): String {
        if (value.isBlank()) fail(ProposalError.Validation)
        if (value.length > ProposalBudget.MAX_NOTE_BODY_CHARS) fail(ProposalError.BudgetExceeded)
        return value
    }

    private fun time(localDateTime: String, zoneId: String, clock: Clock): ProposalTime {
        val resolved = try {
            FinanceTime.resolve(localDateTime, zoneId, clock)
        } catch (error: FinanceException) {
            throw ProposalException(ProposalError.Validation, error)
        }
        val normalized = try {
            LocalDateTime.parse(localDateTime).toString()
        } catch (error: RuntimeException) {
            throw ProposalException(ProposalError.Validation, error)
        }
        return ProposalTime(resolved.occurredAt, resolved.zoneId, normalized, resolved.offsetSeconds)
    }

    private fun operation(out: StringBuilder, operation: ProposalOperation) {
        when (operation) {
            is ProposalOperation.CreateTransaction -> {
                out.append("{\"type\":\"CREATE_TRANSACTION\",\"localRef\":").append(quoted(operation.localRef))
                transactionFields(out, operation)
                timeFields(out, operation.time)
            }
            is ProposalOperation.UpdateTransaction -> {
                out.append("{\"type\":\"UPDATE_TRANSACTION\",\"id\":").append(quoted(operation.id))
                out.append(",\"expectedVersion\":").append(operation.expectedVersion)
                transactionFields(out, operation)
                timeFields(out, operation.time)
            }
            is ProposalOperation.CreateNote -> {
                out.append("{\"type\":\"CREATE_NOTE\",\"localRef\":").append(quoted(operation.localRef))
                out.append(",\"body\":").append(quoted(operation.body))
                timeFields(out, operation.time)
            }
            is ProposalOperation.UpdateNote -> {
                out.append("{\"type\":\"UPDATE_NOTE\",\"id\":").append(quoted(operation.id))
                out.append(",\"expectedVersion\":").append(operation.expectedVersion)
                out.append(",\"body\":").append(quoted(operation.body))
                timeFields(out, operation.time)
            }
            is ProposalOperation.LinkRecords -> pairs(out, "LINK_RECORDS", operation.pairs)
            is ProposalOperation.UnlinkRecords -> pairs(out, "UNLINK_RECORDS", operation.pairs)
        }
        out.append('}')
    }

    private fun transactionFields(out: StringBuilder, operation: TransactionChange) {
        out.append(",\"direction\":").append(quoted(operation.direction.name))
        out.append(",\"amountMinor\":").append(operation.amountMinor)
        out.append(",\"accountId\":").append(quoted(operation.accountId))
        out.append(",\"accountVersion\":").append(operation.accountVersion)
        out.append(",\"categoryId\":").append(operation.categoryId?.let { quoted(it) } ?: "null")
        out.append(",\"categoryVersion\":").append(operation.categoryVersion?.toString() ?: "null")
        out.append(",\"memo\":").append(quoted(operation.memo))
    }

    private fun pairs(out: StringBuilder, type: String, pairs: List<RecordPair>) {
        out.append("{\"type\":\"").append(type).append("\",\"pairs\":[")
        pairs.forEachIndexed { index, pair ->
            if (index > 0) out.append(',')
            out.append("{\"transaction\":").append(ref(pair.transaction))
            out.append(",\"transactionVersion\":").append(pair.transactionVersion)
            out.append(",\"note\":").append(ref(pair.note))
            out.append(",\"noteVersion\":").append(pair.noteVersion).append('}')
        }
        out.append(']')
    }

    private fun ref(ref: RecordRef): String = when (ref) {
        is RecordRef.Local -> "{\"type\":\"LOCAL\",\"localRef\":" + quoted(ref.localRef) + "}"
        is RecordRef.Transaction -> "{\"type\":\"TRANSACTION\",\"id\":" + quoted(ref.id) + "}"
        is RecordRef.Note -> "{\"type\":\"NOTE\",\"id\":" + quoted(ref.id) + "}"
    }

    private fun timeFields(out: StringBuilder, time: ProposalTime) {
        out.append(",\"time\":{\"occurredAt\":").append(time.occurredAt)
        out.append(",\"zoneId\":").append(quoted(time.zoneId))
        out.append(",\"localDateTime\":").append(quoted(time.localDateTime))
        out.append(",\"offsetSeconds\":").append(time.offsetSeconds).append('}')
    }

    private fun quoted(value: String): String = buildString(value.length + 2) {
        append('"')
        for (character in value) when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') {
                append("\\u00").append(HEX[character.code shr 4 and 15]).append(HEX[character.code and 15])
            } else {
                append(character)
            }
        }
        append('"')
    }

    private fun fail(code: ProposalError): Nothing = throw ProposalException(code)
}

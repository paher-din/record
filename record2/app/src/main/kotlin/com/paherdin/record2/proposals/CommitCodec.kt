package com.paherdin.record2.proposals

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class FrozenCommit(
    /** The payload digest of the displayed proposal revision the owner approved. */
    val displayedDigest: String,
    val canonicalJson: String,
    val payloadDigest: String,
    val idAssignments: Map<String, String>,
    val operations: List<FrozenOperation>,
)

/**
 * Deterministic encoding of a confirmed commit. The frozen document replaces every LOCAL
 * reference of the displayed payload with the stable identity assigned at confirmation, keeps
 * the displayed digest the user actually approved, and is the only input Transaction B ever
 * reads back. Canonical bytes are generated explicitly, never by a serializer's map order.
 */
internal object CommitCodec {
    private val json = Json { classDiscriminator = "type" }
    private const val HEX = "0123456789abcdef"
    private val DIGEST = Regex("[0-9a-f]{64}")

    @Serializable
    private data class FrozenDocument(
        val formatVersion: Int,
        val displayedDigest: String,
        val idAssignments: Map<String, String>,
        val operations: List<FrozenOperation>,
    )

    fun freeze(operations: List<ProposalOperation>, displayedDigest: String, newId: () -> String): FrozenCommit {
        if (!DIGEST.matches(displayedDigest)) fail(CommitError.Validation)
        val assignments = LinkedHashMap<String, String>()
        val transactionIds = HashMap<String, String>()
        val noteIds = HashMap<String, String>()
        val frozen = ArrayList<FrozenOperation>(operations.size)
        for (operation in operations) when (operation) {
            is ProposalOperation.CreateTransaction -> {
                val id = newId()
                declare(transactionIds, assignments, operation.localRef, id)
                frozen += FrozenOperation.CreateTransaction(
                    id, operation.direction, operation.amountMinor, operation.accountId,
                    operation.accountVersion, operation.categoryId, operation.categoryVersion,
                    operation.memo, operation.time,
                )
            }
            is ProposalOperation.UpdateTransaction -> frozen += FrozenOperation.UpdateTransaction(
                operation.id, operation.expectedVersion, operation.direction, operation.amountMinor,
                operation.accountId, operation.accountVersion, operation.categoryId,
                operation.categoryVersion, operation.memo, operation.time,
            )
            is ProposalOperation.CreateNote -> {
                val id = newId()
                declare(noteIds, assignments, operation.localRef, id)
                frozen += FrozenOperation.CreateNote(id, operation.body, operation.time)
            }
            is ProposalOperation.UpdateNote -> frozen += FrozenOperation.UpdateNote(
                operation.id, operation.expectedVersion, operation.body, operation.time,
            )
            is ProposalOperation.LinkRecords -> frozen += FrozenOperation.LinkRecords(operation.pairs.map { resolvePair(it, transactionIds, noteIds) })
            is ProposalOperation.UnlinkRecords -> frozen += FrozenOperation.UnlinkRecords(operation.pairs.map { resolvePair(it, transactionIds, noteIds) })
        }
        val canonical = canonical(displayedDigest, assignments, frozen)
        return FrozenCommit(displayedDigest, canonical, digest(canonical), assignments, frozen)
    }

    fun canonical(displayedDigest: String, assignments: Map<String, String>, operations: List<FrozenOperation>): String {
        val out = StringBuilder()
        out.append("{\"formatVersion\":").append(ProposalBudget.FORMAT_VERSION)
        out.append(",\"displayedDigest\":").append(quoted(displayedDigest))
        out.append(",\"idAssignments\":{")
        assignments.keys.sorted().forEachIndexed { index, key ->
            if (index > 0) out.append(',')
            out.append(quoted(key)).append(':').append(quoted(assignments.getValue(key)))
        }
        out.append("},\"operations\":[")
        operations.forEachIndexed { index, operation ->
            if (index > 0) out.append(',')
            operation(out, operation)
        }
        out.append("]}")
        val canonical = out.toString()
        if (canonical.toByteArray(Charsets.UTF_8).size > ProposalBudget.MAX_PAYLOAD_BYTES) fail(CommitError.BudgetExceeded)
        return canonical
    }

    fun digest(canonicalJson: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonicalJson.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (byte in bytes) append(HEX[byte.toInt() shr 4 and 15]).append(HEX[byte.toInt() and 15])
        }
    }

    /** Reads back a persisted frozen payload; anything but a byte-perfect document fails closed. */
    fun decode(canonicalJson: String): FrozenCommit = try {
        require(canonicalJson.toByteArray(Charsets.UTF_8).size <= ProposalBudget.MAX_PAYLOAD_BYTES)
        val document = json.decodeFromString<FrozenDocument>(canonicalJson)
        require(document.formatVersion == ProposalBudget.FORMAT_VERSION)
        require(DIGEST.matches(document.displayedDigest))
        require(document.operations.isNotEmpty() && document.operations.size <= ProposalBudget.MAX_OPERATIONS)
        validate(document)
        require(canonical(document.displayedDigest, document.idAssignments, document.operations) == canonicalJson)
        FrozenCommit(document.displayedDigest, canonicalJson, digest(canonicalJson), document.idAssignments, document.operations)
    } catch (error: CommitException) {
        throw CommitException(CommitError.RecoveryRequired, error)
    } catch (error: RuntimeException) {
        throw CommitException(CommitError.RecoveryRequired, error)
    }

    private fun validate(document: FrozenDocument) {
        val updatedTransactions = HashSet<String>()
        val updatedNotes = HashSet<String>()
        val linked = HashSet<String>()
        val unlinked = HashSet<String>()
        val createdIds = HashSet<String>()
        var linkOperations = 0
        var unlinkOperations = 0
        for (operation in document.operations) when (operation) {
            is FrozenOperation.CreateTransaction -> {
                identity(operation.id)
                require(createdIds.add(operation.id))
                transactionFields(operation)
                time(operation.time)
            }
            is FrozenOperation.UpdateTransaction -> {
                identity(operation.id)
                version(operation.expectedVersion)
                require(updatedTransactions.add(operation.id))
                transactionFields(operation)
                time(operation.time)
            }
            is FrozenOperation.CreateNote -> {
                identity(operation.id)
                require(createdIds.add(operation.id))
                require(operation.body.isNotBlank() && operation.body.length <= ProposalBudget.MAX_NOTE_BODY_CHARS)
                time(operation.time)
            }
            is FrozenOperation.UpdateNote -> {
                identity(operation.id)
                version(operation.expectedVersion)
                require(updatedNotes.add(operation.id))
                require(operation.body.isNotBlank() && operation.body.length <= ProposalBudget.MAX_NOTE_BODY_CHARS)
                time(operation.time)
            }
            is FrozenOperation.LinkRecords -> {
                requirePairs(operation.pairs)
                linkOperations++
                operation.pairs.forEach { require(linked.add(pairKey(it))); pair(it) }
            }
            is FrozenOperation.UnlinkRecords -> {
                requirePairs(operation.pairs)
                unlinkOperations++
                operation.pairs.forEach { require(unlinked.add(pairKey(it))); pair(it) }
            }
        }
        require(linkOperations <= 1 && unlinkOperations <= 1)
        require(linked.none { it in unlinked })
        // The assignment map must be exactly the identities the CREATE operations carry, so an
        // orphaned or duplicate assignment is a foreign document, not a display difference.
        val assigned = document.idAssignments
        require(assigned.keys.all { ProposalBudget.LOCAL_REF.matches(it) })
        require(assigned.values.all { ProposalBudget.IDENTITY.matches(it) })
        require(assigned.values.size == assigned.values.toSet().size)
        require(assigned.keys.sorted() == assigned.keys.toList())
        require(assigned.values.toSet() == createdIds)
    }

    private fun declare(
        declared: MutableMap<String, String>,
        assignments: MutableMap<String, String>,
        localRef: String,
        id: String,
    ) {
        if (!ProposalBudget.LOCAL_REF.matches(localRef) || !ProposalBudget.IDENTITY.matches(id)) fail(CommitError.Validation)
        if (declared.putIfAbsent(localRef, id) != null || assignments.putIfAbsent(localRef, id) != null) fail(CommitError.Validation)
    }

    private fun resolvePair(pair: RecordPair, transactions: Map<String, String>, notes: Map<String, String>): FrozenPair {
        val transactionId = when (val ref = pair.transaction) {
            is RecordRef.Local -> transactions[ref.localRef] ?: fail(CommitError.Validation)
            is RecordRef.Transaction -> ref.id
            is RecordRef.Note -> fail(CommitError.Validation)
        }
        val noteId = when (val ref = pair.note) {
            is RecordRef.Local -> notes[ref.localRef] ?: fail(CommitError.Validation)
            is RecordRef.Note -> ref.id
            is RecordRef.Transaction -> fail(CommitError.Validation)
        }
        return FrozenPair(transactionId, pair.transactionVersion, noteId, pair.noteVersion)
    }

    private fun requirePairs(pairs: List<FrozenPair>) {
        require(pairs.isNotEmpty() && pairs.size <= ProposalBudget.MAX_LINK_PAIRS)
    }

    private fun pair(pair: FrozenPair) {
        identity(pair.transactionId)
        version(pair.transactionVersion)
        identity(pair.noteId)
        version(pair.noteVersion)
    }

    private fun pairKey(pair: FrozenPair) = pair.transactionId + "|" + pair.noteId

    private fun transactionFields(operation: TransactionChange) {
        require(operation.amountMinor in 1..Long.MAX_VALUE)
        identity(operation.accountId)
        version(operation.accountVersion)
        require((operation.categoryId == null) == (operation.categoryVersion == null))
        operation.categoryId?.let { identity(it) }
        operation.categoryVersion?.let { version(it) }
        require(operation.memo.length <= ProposalBudget.MAX_MEMO_CHARS)
    }

    private fun time(time: ProposalTime) {
        require(runCatching { java.time.ZoneId.of(time.zoneId) }.isSuccess)
        require(time.offsetSeconds in -64800..64800)
        val local = java.time.LocalDateTime.parse(time.localDateTime)
        require(local.toString() == time.localDateTime && local.nano % 1_000_000 == 0)
        require(local.toInstant(java.time.ZoneOffset.ofTotalSeconds(time.offsetSeconds)).toEpochMilli() == time.occurredAt)
    }

    private fun identity(value: String) {
        require(ProposalBudget.IDENTITY.matches(value))
    }

    private fun version(value: Long) {
        require(value in 1 until Long.MAX_VALUE)
    }

    private fun operation(out: StringBuilder, operation: FrozenOperation) {
        when (operation) {
            is FrozenOperation.CreateTransaction -> {
                out.append("{\"type\":\"CREATE_TRANSACTION\",\"id\":").append(quoted(operation.id))
                transactionFields(out, operation)
                timeFields(out, operation.time)
            }
            is FrozenOperation.UpdateTransaction -> {
                out.append("{\"type\":\"UPDATE_TRANSACTION\",\"id\":").append(quoted(operation.id))
                out.append(",\"expectedVersion\":").append(operation.expectedVersion)
                transactionFields(out, operation)
                timeFields(out, operation.time)
            }
            is FrozenOperation.CreateNote -> {
                out.append("{\"type\":\"CREATE_NOTE\",\"id\":").append(quoted(operation.id))
                out.append(",\"body\":").append(quoted(operation.body))
                timeFields(out, operation.time)
            }
            is FrozenOperation.UpdateNote -> {
                out.append("{\"type\":\"UPDATE_NOTE\",\"id\":").append(quoted(operation.id))
                out.append(",\"expectedVersion\":").append(operation.expectedVersion)
                out.append(",\"body\":").append(quoted(operation.body))
                timeFields(out, operation.time)
            }
            is FrozenOperation.LinkRecords -> pairs(out, "LINK_RECORDS", operation.pairs)
            is FrozenOperation.UnlinkRecords -> pairs(out, "UNLINK_RECORDS", operation.pairs)
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

    private fun pairs(out: StringBuilder, type: String, pairs: List<FrozenPair>) {
        out.append("{\"type\":\"").append(type).append("\",\"pairs\":[")
        pairs.forEachIndexed { index, pair ->
            if (index > 0) out.append(',')
            out.append("{\"transactionId\":").append(quoted(pair.transactionId))
            out.append(",\"transactionVersion\":").append(pair.transactionVersion)
            out.append(",\"noteId\":").append(quoted(pair.noteId))
            out.append(",\"noteVersion\":").append(pair.noteVersion).append('}')
        }
        out.append(']')
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

    private fun fail(code: CommitError): Nothing = throw CommitException(code)
}

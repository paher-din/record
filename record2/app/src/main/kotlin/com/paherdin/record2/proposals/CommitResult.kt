package com.paherdin.record2.proposals

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The durable Applied receipt of Transaction B. Every repeated execution or inspection of an
 * Applied operation returns exactly these bytes, so the encoding is canonical and closed like
 * the frozen payload itself.
 */
@Serializable
internal data class AppliedCommitResult(val businessRevision: Long, val outcomes: List<AppliedOutcome>)

@Serializable
internal sealed class AppliedOutcome {
    @Serializable
    @SerialName("CREATE_TRANSACTION")
    data class CreateTransaction(val id: String, val version: Long) : AppliedOutcome()

    @Serializable
    @SerialName("UPDATE_TRANSACTION")
    data class UpdateTransaction(val id: String, val version: Long) : AppliedOutcome()

    @Serializable
    @SerialName("CREATE_NOTE")
    data class CreateNote(val id: String, val version: Long) : AppliedOutcome()

    @Serializable
    @SerialName("UPDATE_NOTE")
    data class UpdateNote(val id: String, val version: Long) : AppliedOutcome()

    @Serializable
    @SerialName("LINK_RECORDS")
    data class LinkRecords(val count: Int) : AppliedOutcome()

    @Serializable
    @SerialName("UNLINK_RECORDS")
    data class UnlinkRecords(val count: Int) : AppliedOutcome()
}

internal object CommitResultCodec {
    private val json = Json { classDiscriminator = "type" }

    fun canonical(result: AppliedCommitResult): String {
        val out = StringBuilder()
        out.append("{\"businessRevision\":").append(result.businessRevision).append(",\"outcomes\":[")
        result.outcomes.forEachIndexed { index, outcome ->
            if (index > 0) out.append(',')
            when (outcome) {
                is AppliedOutcome.CreateTransaction -> idVersion(out, "CREATE_TRANSACTION", outcome.id, outcome.version)
                is AppliedOutcome.UpdateTransaction -> idVersion(out, "UPDATE_TRANSACTION", outcome.id, outcome.version)
                is AppliedOutcome.CreateNote -> idVersion(out, "CREATE_NOTE", outcome.id, outcome.version)
                is AppliedOutcome.UpdateNote -> idVersion(out, "UPDATE_NOTE", outcome.id, outcome.version)
                is AppliedOutcome.LinkRecords -> count(out, "LINK_RECORDS", outcome.count)
                is AppliedOutcome.UnlinkRecords -> count(out, "UNLINK_RECORDS", outcome.count)
            }
        }
        out.append("]}")
        return out.toString()
    }

    fun decode(canonicalJson: String): AppliedCommitResult = try {
        val result = json.decodeFromString<AppliedCommitResult>(canonicalJson)
        require(result.businessRevision >= 0)
        require(result.outcomes.isNotEmpty() && result.outcomes.size <= ProposalBudget.MAX_OPERATIONS)
        for (outcome in result.outcomes) when (outcome) {
            is AppliedOutcome.CreateTransaction -> stable(outcome.id, outcome.version)
            is AppliedOutcome.UpdateTransaction -> stable(outcome.id, outcome.version)
            is AppliedOutcome.CreateNote -> stable(outcome.id, outcome.version)
            is AppliedOutcome.UpdateNote -> stable(outcome.id, outcome.version)
            is AppliedOutcome.LinkRecords -> require(outcome.count in 0..ProposalBudget.MAX_LINK_PAIRS)
            is AppliedOutcome.UnlinkRecords -> require(outcome.count in 0..ProposalBudget.MAX_LINK_PAIRS)
        }
        require(canonical(result) == canonicalJson)
        result
    } catch (error: RuntimeException) {
        throw CommitException(CommitError.RecoveryRequired, error)
    }

    private fun stable(id: String, version: Long) {
        require(ProposalBudget.IDENTITY.matches(id))
        require(version in 1 until Long.MAX_VALUE)
    }

    private fun idVersion(out: StringBuilder, type: String, id: String, version: Long) {
        // A validated identity is plain lowercase hex and dashes, so it needs no escaping.
        out.append("{\"type\":\"").append(type).append("\",\"id\":\"").append(id)
        out.append("\",\"version\":").append(version).append('}')
    }

    private fun count(out: StringBuilder, type: String, count: Int) {
        out.append("{\"type\":\"").append(type).append("\",\"count\":").append(count).append('}')
    }
}

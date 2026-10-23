package com.paherdin.record.core.data.restore

import java.security.MessageDigest

/**
 * The strict canonical staging receipt (spec 4.5, plan Task 7). A process-local
 * handle to exactly one validated staged candidate: the confirmation path
 * recomputes the receipt hash and verifies the supplied handle matches that
 * exact candidate before any activation-record mutation. The canonical UTF-8
 * bytes are deterministic; the SHA-256 digest changes for every bound field.
 */
internal data class StagingReceipt(
    val candidateId: String,
    val sourcePackageSha256: String,
    val manifestSha256: String,
    val entryHashes: Map<String, String>,
    val recordCounts: Map<String, Long>,
    val attachmentCount: Int,
    val validationContractVersion: Int,
    val candidateDigest: String,
) {
    fun canonicalUtf8(): ByteArray {
        val sb = StringBuilder()
        sb.append("candidateId=").append(candidateId).append('\n')
        sb.append("sourcePackageSha256=").append(sourcePackageSha256).append('\n')
        sb.append("manifestSha256=").append(manifestSha256).append('\n')
        entryHashes.toSortedMap().forEach { (k, v) -> sb.append("entry:").append(k).append('=').append(v).append('\n') }
        recordCounts.toSortedMap().forEach { (k, v) -> sb.append("count:").append(k).append('=').append(v).append('\n') }
        sb.append("attachmentCount=").append(attachmentCount).append('\n')
        sb.append("validationContractVersion=").append(validationContractVersion).append('\n')
        sb.append("candidateDigest=").append(candidateDigest).append('\n')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun digest(): String = sha256(canonicalUtf8())

    companion object {
        const val VALIDATION_CONTRACT_VERSION = 1

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

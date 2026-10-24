package com.paherdin.record2.storage

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.proposals.CommitError
import com.paherdin.record2.proposals.CommitException
import com.paherdin.record2.records.FinanceSeeds

/** C1 contract databases are private per test and never touch any other batch's files. */
internal fun commitDatabase(name: String): RecordDatabase = commitRoomDatabase(name, "c1-contract")

internal fun commitProcessDatabase(name: String): RecordDatabase = commitRoomDatabase(name, "c1-process")

private fun commitRoomDatabase(name: String, prefix: String): RecordDatabase {
    check(Regex("$prefix-[a-z0-9-]+\\.db").matches(name))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == "com.paherdin.record2.debug")
    return Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
}

internal suspend fun expectCommitError(code: CommitError, block: suspend () -> Unit) {
    val error = try {
        block()
        error("Expected $code")
    } catch (error: CommitException) {
        error
    }
    org.junit.Assert.assertEquals(code, error.code)
}

/** The stable identities A assigns in the golden scenario, minted in freeze encounter order. */
internal object CommitGolden {
    const val NEW_TRANSACTION = "10000000-0000-4000-8000-000000000001"
    const val NEW_NOTE = "10000000-0000-4000-8000-000000000002"
    const val OPERATION = "10000000-0000-4000-8000-000000000003"
    const val DISPLAYED_DIGEST = "ac2bbda5fa0c8a4017e5d0a725719c354b718d28cbf03b5df171fe3dd8455fa3"
    const val FROZEN_DIGEST = "de681c0462957c1f2a49033f39562a5b73208268428c80d42e71ba6a0a67867a"

    // Byte-pinned by the host CommitCodecTest; sha256sum produced the digest constant.
    val FROZEN_JSON =
        """{"formatVersion":1,"displayedDigest":"$DISPLAYED_DIGEST",""" +
            """"idAssignments":{"#n1":"$NEW_NOTE","#t1":"$NEW_TRANSACTION"},"operations":[""" +
            """{"type":"CREATE_TRANSACTION","id":"$NEW_TRANSACTION","direction":"EXPENSE","amountMinor":5800,""" +
            """"accountId":"${FinanceSeeds.ACCOUNT_ID}","accountVersion":1,"categoryId":null,"categoryVersion":null,""" +
            """"memo":"午饭","time":{"occurredAt":1792729800000,"zoneId":"Asia/Shanghai","localDateTime":"2026-10-23T12:30","offsetSeconds":28800}},""" +
            """{"type":"CREATE_NOTE","id":"$NEW_NOTE","body":"买菜记录","time":{"occurredAt":1792729800000,"zoneId":"Asia/Shanghai","localDateTime":"2026-10-23T12:30","offsetSeconds":28800}},""" +
            """{"type":"LINK_RECORDS","pairs":[{"transactionId":"$NEW_TRANSACTION","transactionVersion":1,"noteId":"$NEW_NOTE","noteVersion":1}]}]}"""

    fun ids(): List<String> = listOf(NEW_TRANSACTION, NEW_NOTE, OPERATION)
}

internal fun queuedIds(vararg ids: String): () -> String {
    val queue = ArrayDeque(ids.toList())
    return {
        check(queue.isNotEmpty()) { "Test identity queue exhausted" }
        queue.removeFirst()
    }
}

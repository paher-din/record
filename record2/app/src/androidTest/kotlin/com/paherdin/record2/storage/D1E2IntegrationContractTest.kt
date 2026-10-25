package com.paherdin.record2.storage

import com.paherdin.record2.ai.runs.AgentRunCoordinator
import com.paherdin.record2.ai.runs.AgentRunOwner
import com.paherdin.record2.ai.tools.RecordToolDeps
import com.paherdin.record2.sessions.RunState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * D1+E2 integration seam: an application-owned run uses the D1 tool registry over the real Room
 * services, and E2's owner cancels it before a late reply. This pins the `RunIdentity` bridge:
 * the owner pre-generates the identity, the D1 coordinator consumes it, and the durable
 * CANCELLED mark wins over the in-flight tool/HTTP work.
 */
internal class D1E2IntegrationContractTest {
    @Test
    fun ownedRunWithRecordToolsIsCancelledBeforeLateReply() = runBlocking {
        val fixture = D1ToolsFixture(schemaId())
        fixture.finance.initialize()
        fixture.initialize()
        val gate = CompletableDeferred<Unit>()
        val http = ScriptedDeviceHttp { callIndex, _ ->
            when (callIndex) {
                1 -> d1ToolChat("search_records", "{}")
                else -> {
                    gate.await()
                    d1TextChat("TOO-LATE")
                }
            }
        }
        val coordinator = AgentRunCoordinator(
            security = fixture.security,
            history = fixture.history,
            processOwnerId = fixture.owner,
            recordTools = RecordToolDeps(
                fixture.security, fixture.history, fixture.timeline, fixture.proposals, fixture.finance,
            ),
            httpClientFactory = deviceScriptFactory(http),
            clock = fixture.clock,
        )
        val owner = AgentRunOwner(coordinator, fixture.history, fixture.owner)
        try {
            val session = owner.createSession("d1-e2-integration")
            val handle = owner.launchRun(session.id, "查一下咖啡")
            withTimeout(20_000) { while (http.requests.size < 2) delay(10) }

            val receipt = owner.cancelRun(session.id)
            assertEquals(RunState.CANCELLED, receipt?.state)
            try {
                withTimeout(10_000) { handle.await() }
                fail("cancelled owned run must not complete")
            } catch (_: CancellationException) {
            }
            val state = fixture.database.query(
                "SELECT state FROM agent_run WHERE id = ?", arrayOf(handle.runId),
            ).use { cursor ->
                check(cursor.moveToFirst()) { "run row missing" }
                cursor.getString(0)
            }
            assertEquals("CANCELLED", state)
            val assistantMessages = fixture.database.query(
                "SELECT COUNT(*) FROM agent_message WHERE role = 'ASSISTANT'", null,
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            assertEquals(0, assistantMessages)
        } finally {
            owner.close()
            fixture.database.close()
            fixture.directory.deleteRecursively()
        }
    }
}

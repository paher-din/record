package com.paherdin.record2.storage

import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.ai.runs.AgentRunCoordinator
import com.paherdin.record2.ai.security.AiMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Real Koog/DeepSeek cross-run contract (SES-02/15, Plan E1 “真实 Koog/DeepSeek 连续多轮和恢复
 * 后新 Run 验证”). Two instrumentation invocations against one fixed private database:
 *
 *  1. `prepare` (invocation A): inject the key first via
 *     `adb -s <serial> shell run-as com.paherdin.record2.debug sh -c 'mkdir -p files/e1 && cat > files/e1/deepseek.key'`
 *     then run with `-e class ...SessionRunRealContractTest#prepare`. It resets its own synthetic
 *     database, configures the E0 authorization chain with the real key, and runs two real turns
 *     on one session. Then force-stop the app process.
 *  2. `verify` (invocation B): re-inject the key, then run with
 *     `-e class ...SessionRunRealContractTest#verify` in the fresh process. The ownership gate
 *     adopts a new owner, and a third real turn must recall the turn-1 fact purely from the
 *     Room-rebuilt history. The key file is deleted on first read in each invocation.
 *
 * Real spend stays inside the D0-approved Flash per-run cap (each turn is one short reply).
 * Without the key/marker file the tests abort via Assume (INCONCLUSIVE), never failing the
 * deterministic suites. The sentinel credential stored by the deterministic fixture is never
 * used here: the real key replaces it before any network call.
 */
class SessionRunRealContractTest {
    private val code = "ORANGE-731"

    private fun keyFile(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.filesDir, "e1/deepseek.key")
    }

    private fun markerFile(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "e1/session-id.txt")
    }

    private fun readKey(): String {
        val key = keyFile().readText().trim()
        keyFile().delete()
        check(key.startsWith("sk-") && key.length >= 20) { "files/e1/deepseek.key is not a DeepSeek key" }
        return key
    }

    @Test
    fun prepare() = runBlocking {
        Assume.assumeTrue("requires files/e1/deepseek.key", keyFile().isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("e1-contract-real.db")
        File(context.noBackupFilesDir, "e1-vault-real").deleteRecursively()
        markerFile().delete()
        val fixture = SessionContractFixture("real")
        try {
            fixture.finance.initialize()
            fixture.history.beginProcessOwnership(fixture.owner)
            fixture.security.setMode(AiMode.FULL)
            fixture.security.selectReceiver(fixture.receiver)
            fixture.security.grant(fixture.receiver)
            fixture.security.replaceCredential(readKey().toCharArray())
            val session = fixture.history.createSession("E1 real cross-run")
            markerFile().parentFile?.mkdirs()
            markerFile().writeText(session.id)
            val coord = AgentRunCoordinator(
                security = fixture.security,
                history = fixture.history,
                processOwnerId = fixture.owner,
                clock = sessionClock,
            )
            val first = coord.run(session.id, "请记住编号 $code。只回复OK。")
            assertTrue("first reply was blank", first.answer.isNotBlank())
            val second = coord.run(session.id, "确认你已收到编号 $code。只回复OK。")
            assertTrue("second reply was blank", second.answer.isNotBlank())
            val messages = fixture.history.sessionMessages(session.id)
            assertEquals(4, messages.size)
            markerFile().appendText("\n${first.answer.take(80).replace('\n', ' ')}")
        } finally {
            fixture.database.close()
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun verify() = runBlocking {
        Assume.assumeTrue("requires the prepare marker file", markerFile().isFile)
        val fixture = SessionContractFixture("real")
        try {
            val sessionId = markerFile().readText().trim().lineSequence().first()
            fixture.finance.initialize()
            // Fresh process: adopt a new owner; prepare completed cleanly, so nothing runs stale.
            val ownership = fixture.history.beginProcessOwnership("${fixture.owner}-verify")
            assertTrue(ownership.adopted)
            assertEquals(0, ownership.interruptedRuns)
            val before = fixture.history.sessionMessages(sessionId)
            assertEquals(4, before.size)
            fixture.security.replaceCredential(readKey().toCharArray())
            val coord = AgentRunCoordinator(
                security = fixture.security,
                history = fixture.history,
                processOwnerId = "${fixture.owner}-verify",
                clock = sessionClock,
            )
            val third = coord.run(sessionId, "我之前让你记住的编号是什么？只回复编号本身。")
            val messages = fixture.history.sessionMessages(sessionId)
            assertEquals(6, messages.size)
            assertTrue(
                "third turn must recall the code from Room-rebuilt history: ${third.answer.take(120)}",
                third.answer.contains(code),
            )
            markerFile().delete()
            Unit
        } finally {
            fixture.database.close()
            fixture.directory.deleteRecursively()
        }
    }
}

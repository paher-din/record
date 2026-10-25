package com.paherdin.record2.storage

import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.ai.D0RunBudget
import com.paherdin.record2.ai.RecordingFactory
import com.paherdin.record2.ai.recordingFactory
import com.paherdin.record2.ai.runs.AgentRunCoordinator
import com.paherdin.record2.ai.runs.AgentRunException
import com.paherdin.record2.ai.tools.RecordToolDeps
import com.paherdin.record2.ai.tools.RecordToolRegistry
import com.paherdin.record2.proposals.ProposalOperation
import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceInput
import com.paherdin.record2.sessions.AgentSessionMessage
import com.paherdin.record2.sessions.MessageResultKind
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * Real DeepSeek end-to-end for the D1 record tools: the production coordinator path (E0 permit →
 * E1 run transaction → real Koog/DeepSeek flash → four tools over real Room services →
 * deterministic completion classification). The D0 dedicated test key is injected via stdin into
 * app-private `files/d1/deepseek.key`, read once per class and deleted on first read, exactly
 * like the E0/D0/E1 batches; without the key this class assumes (INCONCLUSIVE, never PASS).
 *
 * Inject with:
 * `adb -s <serial> shell run-as com.paherdin.record2.debug sh -c 'mkdir -p files/d1 && cat > files/d1/deepseek.key'`
 */
internal class RecordToolsRealContractTest {
    private val id = schemaId()
    private val fixture = D1ToolsFixture(id)
    private val database get() = fixture.database
    private val security get() = fixture.security
    private val history get() = fixture.history
    private val finance get() = fixture.finance
    private val owner get() = fixture.owner
    private lateinit var generation: String

    @Before fun open() {
        runBlocking {
            generation = finance.initialize().generation
            fixture.initialize()
            // D1ToolsFixture initializes with a synthetic sentinel credential for the scripted
            // suites; the real e2e replaces it through the E0 path before any request.
            security.replaceCredential(apiKey!!.toCharArray())
        }
    }

    @After fun close() {
        database.close()
        assertTrue(fixture.directory.deleteRecursively() || !fixture.directory.exists())
    }

    private fun count(table: String): Int = database.query("SELECT COUNT(*) FROM $table", null).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0).toInt()
    }

    private fun coordinator(recorder: RecordingFactory): AgentRunCoordinator {
        return AgentRunCoordinator(
            security = security,
            history = history,
            processOwnerId = owner,
            recordTools = RecordToolDeps(security, history, fixture.timeline, fixture.proposals, finance),
            // RecordingFactory forwards the client headers (including the E0-provided bearer
            // credential) to the real transport; a factory that discards them would 401.
            httpClientFactory = recorder,
            clock = fixture.clock,
        )
    }

    /**
     * Every real request must carry the fixed Flash model, explicit thinking=disabled and exactly
     * the four spec tools, and no request body may contain the API key (CTX-07/AI-AC10).
     */
    private fun assertRealRequestContract(recorder: RecordingFactory) {
        assertTrue("expected at least one real request", recorder.requests.isNotEmpty())
        recorder.requests.forEach { body ->
            assertFalse("API key leaked into a request body", body.contains(apiKey!!))
            val request = Json.parseToJsonElement(body).jsonObject
            assertEquals("deepseek-flash", request["model"]!!.jsonPrimitive.content)
            assertEquals("disabled", request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            val tools = request["tools"]!!.jsonArray
                .map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
                .sorted()
            assertEquals(RecordToolRegistry.TOOL_NAMES.sorted(), tools)
        }
    }

    private fun evidence(stage: String, budget: D0RunBudget, requests: Int) {
        println(
            "D1-REAL-EVIDENCE $stage: " + buildJsonObject {
                put("requests", requests)
                put("inputTokens", budget.inputTokens)
                put("outputTokens", budget.outputTokens)
                put("estimatedCostUsd", budget.estimatedCostUsd)
                put("keyFingerprint", keyFingerprint!!)
            }.toString(),
        )
    }

    @Test fun realPrepareReviseKeepsProposalIdentityWithoutBusinessWrites() = runBlocking {
        val recorder = recordingFactory()
        val runner = coordinator(recorder)
        var preparedSession: String? = null
        var attempts = 0
        // A model that answers without preparing is non-compliance, not a protocol failure;
        // retry with a fresh session (D0 finding #8) and keep the attempt count as evidence.
        while (preparedSession == null && attempts < 3) {
            attempts += 1
            val session = history.createSession("d1-real-prepare-$attempts")
            runCatching {
                runner.run(
                    session.id,
                    "请帮我记一笔账：2026年10月24日 12:30 午饭花了 36.5 元，分类用餐饮，备注“午饭”；" +
                        "再写一条笔记“报销提醒”，发生时间同一天 12:31，并把它和这笔账关联起来。",
                )
            }
            val final = history.sessionMessages(session.id).lastOrNull()
            if (final?.resultKind == MessageResultKind.ProposalReady && final.proposalId != null) {
                preparedSession = session.id
            }
        }
        println(
            "D1-REAL-PREPARE: " + buildJsonObject {
                put("attempts", attempts)
                put("requests", recorder.requests.size)
            }.toString(),
        )
        assertTrue("model never prepared a proposal in $attempts attempts", preparedSession != null)
        val final = history.sessionMessages(preparedSession!!).last()
        val proposalId = final.proposalId!!
        val view = fixture.proposals.proposal(proposalId)!!
        assertEquals(
            listOf("CreateNote", "CreateTransaction", "LinkRecords"),
            view.operations.map { it::class.simpleName!! }.sorted(),
        )
        assertTrue(
            view.operations.any {
                it is com.paherdin.record2.proposals.ProposalOperation.CreateTransaction &&
                    it.amountMinor == 3650L && it.categoryId != null
            },
        )
        assertTrue("no formal business facts before confirmation", count("record_transaction") == 0 && count("note") == 0)

        // Second turn in the same session revises the same proposal identity (AI-AC3). Like the
        // create turn this allows a bounded, counted retry for model non-compliance; a revision
        // that changes identity or fails to reach revision 2 is never accepted.
        var reviseFinal: AgentSessionMessage? = null
        var reviseAttempts = 0
        var lastReviseProblem: String? = null
        while (reviseFinal == null && reviseAttempts < 3) {
            reviseAttempts += 1
            val attempt = runCatching {
                runner.run(preparedSession, "把那笔午饭改成 40 元，其他都不变。")
            }
            val candidate = history.sessionMessages(preparedSession).last()
            if (candidate.resultKind == MessageResultKind.ProposalReady &&
                candidate.proposalId == proposalId &&
                candidate.proposalRevision == 2L
            ) {
                reviseFinal = candidate
            } else {
                lastReviseProblem = attempt.exceptionOrNull()?.message
                    ?: "resultKind=${candidate.resultKind} proposal=${candidate.proposalId} revision=${candidate.proposalRevision}"
            }
        }
        val revised = checkNotNull(reviseFinal) {
            "model never revised the proposal in $reviseAttempts attempts: $lastReviseProblem"
        }
        println(
            "D1-REAL-REVISE: " + buildJsonObject {
                put("attempts", reviseAttempts)
                put("resultKind", revised.resultKind.name)
                put("proposalId", revised.proposalId)
                put("revision", revised.proposalRevision)
            }.toString(),
        )
        val revisedView = fixture.proposals.proposal(proposalId)!!
        assertEquals(2L, revisedView.revision)
        assertEquals(
            listOf("CreateNote", "CreateTransaction", "LinkRecords"),
            revisedView.operations.map { it::class.simpleName!! }.sorted(),
        )
        assertTrue(
            revisedView.operations.any {
                it is ProposalOperation.CreateTransaction && it.amountMinor == 4000L
            },
        )
        assertTrue(count("record_transaction") == 0 && count("note") == 0)
        assertRealRequestContract(recorder)
        evidence("prepare-revise", recorder.budget, recorder.requests.size)
    }

    @Test fun realSearchAnswersFromRealDataWithoutProposals() = runBlocking {
        val catalog = finance.initialize()
        val coffee = finance.beginNewDraft(
            catalog.generation,
            FinanceInput("36.50", FinanceDirection.EXPENSE, catalog.account.id, catalog.account.version, catalog.categories[0].id, 1, "咖啡 挂耳", "2026-10-23T12:30:00", "Asia/Shanghai"),
        )
        val coffeeReceipt = finance.saveDraft(catalog.generation, coffee.id, coffee.revision)
        val commute = finance.beginNewDraft(
            catalog.generation,
            FinanceInput("13.50", FinanceDirection.EXPENSE, catalog.account.id, catalog.account.version, null, null, "地铁", "2026-10-24T09:00:00", "Asia/Shanghai"),
        )
        finance.saveDraft(catalog.generation, commute.id, commute.revision)

        val recorder = recordingFactory()
        val runner = coordinator(recorder)
        val session = history.createSession("d1-real-search")
        val outcome = runCatching {
            runner.run(session.id, "我 2026 年 10 月 23 日到 24 日这两天总共花了多少钱？分别列出是哪几笔？咖啡那笔的备注是什么？")
        }
        val final = history.sessionMessages(session.id).lastOrNull()
        println(
            "D1-REAL-SEARCH: " + buildJsonObject {
                put("runCompleted", outcome.isSuccess)
                put("resultKind", final?.resultKind?.name ?: "none")
                put("references", final?.references?.size ?: 0)
            }.toString(),
        )
        assertTrue("real run failed: ${outcome.exceptionOrNull()?.message}", outcome.isSuccess)
        assertEquals(MessageResultKind.Answer, final!!.resultKind)
        // The surfaced references must be real ids from the seeded business facts (AI-AC5).
        assertTrue(final.references.any { it.transactionId == coffeeReceipt.record.id })
        // No proposal appears for a pure read-only question.
        assertEquals(0, count("change_proposal"))
        assertRealRequestContract(recorder)
        evidence("search", recorder.budget, recorder.requests.size)
    }

    @Test fun revokedBeforeRunMakesZeroRequests() = runBlocking {
        val recorder = recordingFactory()
        val runner = coordinator(recorder)
        security.revoke()
        val session = history.createSession("d1-real-revoked")
        val outcome = runCatching { runner.run(session.id, "随便记一笔") }
        assertTrue(outcome.isFailure && outcome.exceptionOrNull() is AgentRunException)
        assertEquals(0, recorder.requests.size)
        assertEquals(0, recorder.budget.inputTokens + recorder.budget.outputTokens)
    }

    companion object {
        private var keyFingerprint: String? = null
        private var apiKey: String? = null

        @BeforeClass @JvmStatic fun loadKey() {
            val file = File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "d1/deepseek.key",
            )
            assumeTrue("requires files/d1/deepseek.key (D0 dedicated test key)", file.isFile)
            val key = file.readText().trim()
            file.delete()
            check(key.startsWith("sk-") && key.length >= 20) { "files/d1/deepseek.key is not a DeepSeek key" }
            apiKey = key
            keyFingerprint = MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(12)
        }
    }
}

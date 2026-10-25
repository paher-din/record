package com.paherdin.record2.ai.tools

import ai.koog.http.client.KoogHttpClient
import com.paherdin.record2.ai.security.AiMode
import com.paherdin.record2.ai.security.AiSecurityService
import com.paherdin.record2.ai.security.AiSecurityState
import com.paherdin.record2.ai.security.AiSendEligibility
import com.paherdin.record2.ai.security.AiSendPermit
import com.paherdin.record2.ai.security.CredentialStatus
import com.paherdin.record2.ai.security.CredentialVault
import com.paherdin.record2.ai.security.ProviderReceiver
import com.paherdin.record2.ai.security.RecordProviderReceivers
import com.paherdin.record2.proposals.ProposalError
import com.paherdin.record2.proposals.ProposalException
import com.paherdin.record2.proposals.ProposalIntent
import com.paherdin.record2.proposals.ProposalRunContext
import com.paherdin.record2.proposals.ProposalService
import com.paherdin.record2.proposals.ProposalState
import com.paherdin.record2.proposals.ProposalView
import com.paherdin.record2.records.FinanceCatalog
import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceError
import com.paherdin.record2.records.FinanceException
import com.paherdin.record2.records.FinanceRecord
import com.paherdin.record2.records.FinanceService
import com.paherdin.record2.records.NoteRecord
import com.paherdin.record2.records.TimelineCursor
import com.paherdin.record2.records.TimelineError
import com.paherdin.record2.records.TimelineException
import com.paherdin.record2.records.TimelinePage
import com.paherdin.record2.records.TimelineQuery
import com.paherdin.record2.records.TimelineService
import com.paherdin.record2.records.RecordRef
import com.paherdin.record2.records.RecordDetail
import com.paherdin.record2.records.NoteDetail
import com.paherdin.record2.records.TransactionDetail
import com.paherdin.record2.records.NoteTimelineEntry
import com.paherdin.record2.records.TransactionTimelineEntry
import com.paherdin.record2.records.TimelineRange
import com.paherdin.record2.sessions.AgentHistoryService
import com.paherdin.record2.sessions.PersistedRunResult
import com.paherdin.record2.sessions.RunState
import com.paherdin.record2.sessions.RunStartReceipt
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.KClass

// Host-side D1 tool contract tests: the four tools over in-memory fakes. Real Room behavior,
// real authorization state and the real DeepSeek loop are covered by instrumentation tests.

private class MutableClock(var nowMillis: Long = 0L) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)
}

private class ToolFakeSecurity(var valid: Boolean = true) : AiSecurityService {
    override suspend fun state() = AiSecurityState(
        AiMode.FULL, RecordProviderReceivers.deepSeek.identity, RecordProviderReceivers.deepSeek.identity,
        1, 1, 0, 7, CredentialStatus.AVAILABLE,
    )
    override suspend fun setMode(mode: AiMode) = state()
    override suspend fun selectReceiver(receiver: ProviderReceiver) = state()
    override suspend fun grant(receiver: ProviderReceiver) = state()
    override suspend fun revoke() = state()
    override suspend fun replaceCredential(secret: CharArray) = state()
    override suspend fun clearCredential() = state()
    override suspend fun eligibility(receiver: ProviderReceiver): AiSendEligibility =
        if (valid) AiSendEligibility.Ready else AiSendEligibility.Denied(
            com.paherdin.record2.ai.security.AiDenial.RECEIVER_UNAUTHORIZED,
        )
    override suspend fun requirePermit(receiver: ProviderReceiver) = AiSendPermit(receiver, 7)
    override suspend fun stillValid(permit: AiSendPermit) = valid
    override suspend fun <T> useCredential(permit: AiSendPermit, block: (CharArray) -> T): T =
        block("sk-synthetic".toCharArray())
}

private class ToolFakeHistory(var runState: RunState = RunState.RUNNING) : AgentHistoryService {
    var persistedReads = 0
    override suspend fun persistedResult(runId: String): PersistedRunResult? {
        persistedReads += 1
        return PersistedRunResult(runId, runState, null, null, null, null)
    }
    override suspend fun activeRun(sessionId: String): com.paherdin.record2.sessions.ActiveRunView =
        error("unused")
    override suspend fun deleteSession(
        sessionId: String,
        processOwnerId: String,
    ): com.paherdin.record2.sessions.SessionDeletionReport = error("unused")
    // Unused by the tool layer.
    override suspend fun createSession(title: String?) = error("unused")
    override suspend fun session(sessionId: String) = error("unused")
    override suspend fun sessionMessages(sessionId: String) = error("unused")
    override suspend fun completedHistory(sessionId: String, beforeSequence: Long, limit: Int?) = error("unused")
    override fun newRunIdentity() = error("unused")
    override suspend fun startRun(
        sessionId: String, userText: String, receiverIdentity: String, authEpoch: Long,
        processOwnerId: String, identity: com.paherdin.record2.sessions.RunIdentity,
    ) = error("unused")
    override suspend fun completeRun(
        runId: String, receiverIdentity: String, authEpoch: Long, processOwnerId: String,
        outcome: com.paherdin.record2.sessions.RunOutcome,
    ) = error("unused")
    override suspend fun failRun(
        runId: String, state: com.paherdin.record2.sessions.RunTerminal,
        safeErrorCode: com.paherdin.record2.sessions.SafeRunErrorCode,
    ) = error("unused")
    override suspend fun beginProcessOwnership(processOwnerId: String) = error("unused")
}

private class ToolFakeTimeline : TimelineService {
    val timelineCalls = CopyOnWriteArrayList<Pair<String, TimelineQuery>>()
    var page: TimelinePage = TimelinePage("gen-1", 0, emptyList(), null)
    var timelineError: TimelineException? = null
    var detailResult: RecordDetail? = null
    var detailError: TimelineException? = null
    var aggregateResult: com.paherdin.record2.records.FinanceAggregate? = null
    var aggregateError: TimelineException? = null
    val aggregateCalls = CopyOnWriteArrayList<Pair<TimelineRange, com.paherdin.record2.records.FinanceCategoryScope>>()

    override suspend fun timeline(generation: String, query: TimelineQuery, limit: Int, after: TimelineCursor?): TimelinePage {
        timelineCalls += generation to query
        timelineError?.let { throw it }
        return page
    }

    override suspend fun timelineBefore(
        generation: String, query: TimelineQuery, limit: Int, beforeExclusive: Long,
    ): TimelinePage = error("unused")

    override suspend fun detail(generation: String, ref: RecordRef): RecordDetail {
        detailError?.let { throw it }
        return detailResult ?: error("no detail configured")
    }

    override suspend fun aggregateFinances(
        generation: String,
        range: TimelineRange,
        category: com.paherdin.record2.records.FinanceCategoryScope,
    ): com.paherdin.record2.records.FinanceAggregate {
        aggregateCalls += range to category
        aggregateError?.let { throw it }
        return aggregateResult ?: error("no aggregate configured")
    }
}

private class ToolFakeProposals : ProposalService {
    val prepares = CopyOnWriteArrayList<Pair<ProposalRunContext, List<ProposalIntent>>>()
    val revises = CopyOnWriteArrayList<Triple<String, Long, List<ProposalIntent>>>()
    var prepareResult: ProposalView = viewOf("proposal-1", 1)
    var prepareError: ProposalException? = null
    var active: ProposalView? = null
    var activeError: ProposalException? = null

    override suspend fun prepare(context: ProposalRunContext, intents: List<ProposalIntent>): ProposalView {
        prepares += context to intents
        prepareError?.let { throw it }
        return prepareResult
    }

    override suspend fun revise(
        context: ProposalRunContext,
        proposalId: String,
        expectedRevision: Long,
        intents: List<ProposalIntent>,
    ): ProposalView {
        revises += Triple(proposalId, expectedRevision, intents)
        return prepareResult
    }

    override suspend fun activeProposal(generation: String, sessionId: String): ProposalView? {
        activeError?.let { throw it }
        return active
    }

    override suspend fun proposal(proposalId: String): ProposalView? = null
}

private class ToolFakeFinance : FinanceService {
    var initializeCalls = 0
    var initializeError: Exception? = null
    override suspend fun initialize(): FinanceCatalog {
        initializeCalls += 1
        initializeError?.let { throw it }
        return FinanceCatalog("gen-1", com.paherdin.record2.records.FinanceSeeds.account, com.paherdin.record2.records.FinanceSeeds.categories)
    }
    override suspend fun renameAccount(generation: String, expectedVersion: Long, name: String) = error("unused")
    override suspend fun beginNewDraft(generation: String, input: com.paherdin.record2.records.FinanceInput) = error("unused")
    override suspend fun beginEditDraft(generation: String, recordId: String, expectedVersion: Long, zoneId: String) = error("unused")
    override suspend fun readDraft(generation: String) = error("unused")
    override suspend fun updateDraft(
        generation: String, draftId: String, expectedRevision: Long,
        input: com.paherdin.record2.records.FinanceInput,
    ) = error("unused")
    override suspend fun discardDraft(generation: String, draftId: String, expectedRevision: Long) = error("unused")
    override suspend fun saveDraft(generation: String, draftId: String, expectedRevision: Long) = error("unused")
    override suspend fun transaction(generation: String, id: String) = error("unused")
}

private fun startReceipt(startedAt: Long = 0L) = RunStartReceipt(
    runId = "run-1", sessionId = "session-1", userMessageId = "m1", userMessageSequence = 0,
    boundarySequence = 0, boundaryHistoryVersion = 1, proposalCreationRequestId = "req-1",
    receiverIdentity = RecordProviderReceivers.deepSeek.identity, authEpoch = 7,
    dataGeneration = "gen-1", processOwnerId = "owner-1", startedAt = startedAt,
)

private fun viewOf(id: String, revision: Long, lastWriter: String = "run-1"): ProposalView = ProposalView(
    id = id, sessionId = "session-1", originRunId = "run-1", lastWriterRunId = lastWriter,
    creationRequestId = "req-1", revision = revision, state = ProposalState.EDITABLE,
    payloadFormatVersion = 1, payloadDigest = "digest", dataGeneration = "gen-1",
    operations = emptyList(), createdAt = 0, updatedAt = 0,
)

private fun transactionEntry(id: String, memo: String = "咖啡"): TransactionTimelineEntry = TransactionTimelineEntry(
    FinanceRecord(
        id = id, direction = FinanceDirection.EXPENSE, amountMinor = 3650,
        accountId = com.paherdin.record2.records.FinanceSeeds.ACCOUNT_ID,
        categoryId = "00000000-0000-4000-8000-000000000101", memo = memo,
        occurredAt = 1_700_000_000_000, version = 3, createdAt = 0, updatedAt = 0,
    ),
)

private fun noteEntry(id: String, body: String = "笔记正文"): NoteTimelineEntry = NoteTimelineEntry(
    NoteRecord(id = id, body = body, occurredAt = 1_700_000_000_000, version = 2, createdAt = 0, updatedAt = 0, deletedAt = null),
)

private class GuardFailingHttpClient : KoogHttpClient {
    var posts = 0
    override val clientName: String = "guard-failing"

    override suspend fun <R : Any> get(
        path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>,
    ): R = error("delegate get must not be reached")

    override suspend fun <T : Any, R : Any> post(
        path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>,
        parameters: Map<String, String>, headers: Map<String, String>,
    ): R {
        posts += 1
        error("delegate post must not be reached")
    }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>, headers: Map<String, String>,
    ): Flow<O> = flow { error("delegate sse must not be reached") }

    override fun <T : Any> lines(
        path: String, requestBody: T, requestBodyType: KClass<T>,
        parameters: Map<String, String>, headers: Map<String, String>,
    ): Flow<String> = flow { error("delegate lines must not be reached") }

    override fun close() = Unit
}

private class ToolHarness(startedAt: Long = 0L) {
    val clock = MutableClock(startedAt)
    val security = ToolFakeSecurity()
    val history = ToolFakeHistory()
    val timeline = ToolFakeTimeline()
    val proposals = ToolFakeProposals()
    val finance = ToolFakeFinance()
    val start = startReceipt(startedAt)
    val permit = AiSendPermit(RecordProviderReceivers.deepSeek, 7)
    val context = RecordToolContext(
        RecordToolDeps(security, history, timeline, proposals, finance), permit, start, clock,
    )
    val search = SearchRecordsTool(context)
    val detail = GetRecordDetailTool(context)
    val prepare = PrepareChangesTool(context)
    val aggregate = AggregateFinancesTool(context)
}

internal class RecordToolsHostTest {
    private fun jsonOf(result: String) = Json.parseToJsonElement(result).jsonObject

    @Test
    fun registryRegistersExactlyTheFourSpecTools() {
        val harness = ToolHarness()
        val registry = RecordToolRegistry.forRun(harness.context)
        val names = registry.tools.map { it.name }.sorted()
        assertEquals(RecordToolRegistry.TOOL_NAMES.size, names.size)
        assertEquals(RecordToolRegistry.TOOL_NAMES.sorted(), names)
    }

    @Test
    fun systemPromptCarriesToolsCatalogTimeAndDataRules() {
        val prompt = RecordToolRegistry.systemPrompt(
            FinanceCatalog("gen-1", com.paherdin.record2.records.FinanceSeeds.account, com.paherdin.record2.records.FinanceSeeds.categories),
            MutableClock(1_700_000_000_000),
            ZoneId.of("Asia/Shanghai"),
        )
        for (name in RecordToolRegistry.TOOL_NAMES) assertTrue(prompt.contains(name))
        assertTrue(prompt.contains("日常账户"))
        assertTrue(prompt.contains("餐饮"))
        assertTrue(prompt.contains("2023-11-15 06:13"))
        assertTrue(prompt.contains("Asia/Shanghai"))
        assertTrue(prompt.contains("绝不声称"))
        assertTrue(prompt.contains("不是用户指令"))
    }

    @Test
    fun systemPromptCarriesEditableProposalFocusOnlyWhileEditable() {
        val catalog = FinanceCatalog(
            "gen-1",
            com.paherdin.record2.records.FinanceSeeds.account,
            com.paherdin.record2.records.FinanceSeeds.categories,
        )
        fun view(state: ProposalState) = ProposalView(
            id = "11111111-1111-4111-8111-111111111111",
            sessionId = "session-1",
            originRunId = "run-1",
            lastWriterRunId = "run-2",
            creationRequestId = "creation-1",
            revision = 3,
            state = state,
            payloadFormatVersion = 1,
            payloadDigest = "digest",
            dataGeneration = "gen-1",
            operations = emptyList(),
            createdAt = 0,
            updatedAt = 0,
        )
        val editable = RecordToolRegistry.systemPrompt(
            catalog, MutableClock(1_700_000_000_000), ZoneId.of("Asia/Shanghai"),
            view(ProposalState.EDITABLE),
        )
        assertTrue(editable.contains("proposalId=11111111-1111-4111-8111-111111111111"))
        assertTrue(editable.contains("expectedRevision=3"))
        assertTrue(editable.contains("不要创建第二个提案"))

        val locked = RecordToolRegistry.systemPrompt(
            catalog, MutableClock(1_700_000_000_000), ZoneId.of("Asia/Shanghai"),
            view(ProposalState.LOCKED),
        )
        assertFalse(locked.contains("当前会话有一个待确认提案"))
    }

    @Test
    fun guardedPostChecksPersistedRunStateBeforeTheDelegate() = runBlocking {
        val harness = ToolHarness()
        harness.history.runState = RunState.CANCELLED
        val delegate = GuardFailingHttpClient()
        val guard = GuardedHttpClient(delegate, harness.context)
        val error = runCatching {
            guard.post("chat", "{}", String::class, String::class, emptyMap(), emptyMap())
        }.exceptionOrNull()
        assertTrue("expected a hard tool failure, got $error", error is RecordToolException)
        assertEquals(RecordToolFailure.RunInvalidated, (error as RecordToolException).code)
        assertEquals(0, delegate.posts)
    }

    @Test
    fun poisonedRunRejectsLaterToolsBeforeBusinessWrites() = runBlocking {
        val harness = ToolHarness()
        val search = SearchRecordsArgs()
        repeat(3) { harness.search.execute(search) }
        val poison = runCatching { harness.search.execute(search) }.exceptionOrNull()
        assertTrue("expected the no-progress limit, got $poison", poison is RecordToolException)
        assertEquals(RecordToolFailure.BudgetExceeded, (poison as RecordToolException).code)

        val staged = runCatching {
            harness.prepare.execute(
                PrepareChangesArgs(
                    ops = listOf(
                        PrepareOpArgs(
                            op = "CREATE_TRANSACTION", localRef = "#t1", direction = "EXPENSE",
                            amountText = "1.00", accountName = "日常账户", categoryName = "餐饮",
                            localDateTime = "2026-10-24T12:30:00", zoneId = "Asia/Shanghai",
                        ),
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue("expected a poisoned-run rejection, got $staged", staged is RecordToolException)
        assertEquals(RecordToolFailure.BudgetExceeded, (staged as RecordToolException).code)
        assertTrue(harness.proposals.prepares.isEmpty())
        assertTrue(harness.proposals.revises.isEmpty())
    }

    @Test
    fun catalogReadFailurePoisonsAndBlocksTheNextRequest() = runBlocking {
        val harness = ToolHarness()
        harness.finance.initializeError = FinanceException(FinanceError.StorageFull)
        val failure = runCatching {
            harness.prepare.execute(
                PrepareChangesArgs(
                    ops = listOf(
                        PrepareOpArgs(
                            op = "CREATE_TRANSACTION", localRef = "#t1", direction = "EXPENSE",
                            amountText = "1.00", accountName = "日常账户", categoryName = "餐饮",
                            localDateTime = "2026-10-24T12:30:00", zoneId = "Asia/Shanghai",
                        ),
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue("expected a hard catalog failure, got $failure", failure is RecordToolException)
        assertEquals(RecordToolFailure.StorageFull, (failure as RecordToolException).code)
        assertEquals(RecordToolFailure.StorageFull, harness.context.poisoned)
        assertTrue(harness.proposals.prepares.isEmpty())

        val delegate = GuardFailingHttpClient()
        val guard = GuardedHttpClient(delegate, harness.context)
        val request = runCatching {
            guard.post("chat", "{}", String::class, String::class, emptyMap(), emptyMap())
        }.exceptionOrNull()
        assertTrue("poisoned request must be blocked, got $request", request is RecordToolException)
        assertEquals(RecordToolFailure.StorageFull, (request as RecordToolException).code)
        assertEquals(0, delegate.posts)
    }

    @Test
    fun activeProposalReadFailureIsHardNotSwallowed() = runBlocking {
        val harness = ToolHarness()
        harness.proposals.prepareError = ProposalException(ProposalError.ActiveProposalExists)
        harness.proposals.activeError = ProposalException(ProposalError.GenerationMismatch)
        val failure = runCatching {
            harness.prepare.execute(
                PrepareChangesArgs(
                    ops = listOf(
                        PrepareOpArgs(
                            op = "CREATE_NOTE", localRef = "#n1", body = "x",
                            localDateTime = "2026-10-24T12:00:00", zoneId = "Asia/Shanghai",
                        ),
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue("expected a hard active-proposal read failure, got $failure", failure is RecordToolException)
        assertEquals(RecordToolFailure.GenerationMoved, (failure as RecordToolException).code)
        assertEquals(RecordToolFailure.GenerationMoved, harness.context.poisoned)
    }

    @Test
    fun guardedStreamingPathsAreRejectedBeforeAnyTransport() = runBlocking {
        val harness = ToolHarness()
        val delegate = GuardFailingHttpClient()
        val guard = GuardedHttpClient(delegate, harness.context)
        val sse = runCatching {
            guard.sse<String, String, Any>(
                "chat", "{}", String::class, { true }, { it }, { null }, emptyMap(), emptyMap(),
            )
        }.exceptionOrNull()
        assertTrue("expected an explicit streaming rejection, got $sse", sse is RecordToolException)
        assertEquals(RecordToolFailure.RecoveryRequired, (sse as RecordToolException).code)

        val lines = runCatching {
            guard.lines("chat", "{}", String::class, emptyMap(), emptyMap())
        }.exceptionOrNull()
        assertTrue("expected an explicit lines rejection, got $lines", lines is RecordToolException)
        assertEquals(RecordToolFailure.RecoveryRequired, (lines as RecordToolException).code)
    }

    @Test
    fun detailReturnsLinkedRecordsAsAStructuredArray() = runBlocking {
        val harness = ToolHarness()
        val transaction = FinanceRecord(
            id = "00000000-0000-4000-8000-0000000000aa",
            direction = FinanceDirection.EXPENSE,
            amountMinor = 3650,
            accountId = "00000000-0000-4000-8000-000000000001",
            categoryId = null,
            memo = "咖啡",
            occurredAt = 0,
            version = 3,
            createdAt = 0,
            updatedAt = 0,
        )
        harness.timeline.detailResult = NoteDetail(
            NoteRecord("00000000-0000-4000-8000-0000000000bb", "笔记", 1, 5, 0, 0, null),
            listOf(transaction),
            1,
        )
        val result = jsonOf(
            harness.detail.execute(
                GetRecordDetailArgs("NOTE", "00000000-0000-4000-8000-0000000000bb"),
            ),
        )
        val links = result["linkedRecords"]!!.jsonArray
        assertEquals(1, links.size)
        assertEquals("TRANSACTION", links[0].jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(transaction.id, links[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(3L, links[0].jsonObject["version"]!!.jsonPrimitive.long)
    }

    @Test
    fun searchEmptyResultIsSuccessNotFailure() = runBlocking {
        val harness = ToolHarness()
        harness.timeline.page = TimelinePage("gen-1", 0, emptyList(), null)
        val result = jsonOf(harness.search.execute(SearchRecordsArgs()))
        assertEquals(true, result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(0, result["results"]!!.jsonArray.size)
        assertEquals("gen-1", result["coverage"]!!.jsonObject["generation"]!!.jsonPrimitive.content)
    }

    @Test
    fun searchRejectsInvalidParametersWithIssues() = runBlocking {
        val harness = ToolHarness()
        val kind = jsonOf(harness.search.execute(SearchRecordsArgs(kind = "TRANSFER")))
        assertEquals(false, kind["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(kind["issues"].toString().contains("kind"))
        val range = jsonOf(
            harness.search.execute(SearchRecordsArgs(dateStart = "2026-10-01", dateEnd = "2026-10-31")),
        )
        assertTrue(range["issues"].toString().contains("zoneId"))
        val cursor = jsonOf(harness.search.execute(SearchRecordsArgs(cursor = "not-a-cursor")))
        assertTrue(cursor["issues"].toString().contains("cursor"))
        val keyword = jsonOf(
            harness.search.execute(SearchRecordsArgs(keyword = "x".repeat(RecordToolBudget.KEYWORD_MAX_CODE_POINTS + 1))),
        )
        assertTrue(keyword["issues"].toString().contains("keyword"))
        assertTrue(harness.timeline.timelineCalls.isEmpty())
    }

    @Test
    fun searchReturnsRealIdsVersionsAndRoundTripsCursor() = runBlocking {
        val harness = ToolHarness()
        val query = TimelineQuery(
            kind = com.paherdin.record2.records.RecordKind.TRANSACTION,
            keyword = "咖啡",
            range = TimelineRange(1, 2),
            zoneId = "Asia/Shanghai",
        )
        val next = TimelineCursor(
            occurredAt = 1_699_000_000_000, kind = com.paherdin.record2.records.RecordKind.TRANSACTION,
            id = "00000000-0000-4000-8000-0000000000aa", generation = "gen-1", businessRevision = 5,
            filterDigest = com.paherdin.record2.records.TimelineQueryRules.digest(query),
            cursorDigest = com.paherdin.record2.records.TimelineQueryRules.cursorDigest(
                query, 1_699_000_000_000, com.paherdin.record2.records.RecordKind.TRANSACTION,
                "00000000-0000-4000-8000-0000000000aa", "gen-1", 5,
            ),
        )
        harness.timeline.page = TimelinePage("gen-1", 5, listOf(transactionEntry("00000000-0000-4000-8000-0000000000aa")), next)
        val args = SearchRecordsArgs(
            kind = "TRANSACTION", keyword = "咖啡",
            dateStart = "2026-10-01", dateEnd = "2026-10-02", zoneId = "Asia/Shanghai", limit = 10,
        )
        val first = jsonOf(harness.search.execute(args))
        assertEquals(true, first["ok"]!!.jsonPrimitive.content.toBoolean())
        val entry = first["results"]!!.jsonArray.first().jsonObject
        assertEquals("00000000-0000-4000-8000-0000000000aa", entry["id"]!!.jsonPrimitive.content)
        assertEquals("3", entry["version"]!!.jsonPrimitive.content)
        val cursorText = first["nextCursor"]!!.jsonPrimitive.content
        val second = jsonOf(harness.search.execute(args.copy(cursor = cursorText)))
        assertEquals(true, second["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2, harness.timeline.timelineCalls.size)
    }

    @Test
    fun searchSurfacesServiceValidationAsModelRecoverableAndKeepsReferences() = runBlocking {
        val harness = ToolHarness()
        harness.timeline.timelineError = TimelineException(TimelineError.CursorExpired)
        val result = jsonOf(harness.search.execute(SearchRecordsArgs()))
        assertEquals(false, result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["issues"].toString().contains("query"))
        // CursorExpired must not kill the run and must not leak as a hard failure.
        val references = harness.context.references()
        assertTrue(references.isEmpty())
    }

    @Test
    fun detailSegmentsLongTextWithoutSplittingSurrogates() = runBlocking {
        val harness = ToolHarness()
        val body = "😄".repeat(5000) + "结尾"
        harness.timeline.detailResult = NoteDetail(
            NoteRecord("00000000-0000-4000-8000-0000000000bb", body, 1, 5, 0, 0, null),
            emptyList(), 0,
        )
        val first = jsonOf(
            harness.detail.execute(
                GetRecordDetailArgs(kind = "NOTE", id = "00000000-0000-4000-8000-0000000000bb", maxChars = 2000),
            ),
        )
        assertEquals(true, first["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("3", first["totalSegments"]!!.jsonPrimitive.content)
        assertEquals(true, first["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("1", first["nextSegmentCursor"]!!.jsonPrimitive.content)
        val text = first["text"]!!.jsonPrimitive.content
        assertEquals(2000, text.codePointCount(0, text.length))
        assertEquals("😄", text.substring(text.length - 2))
        val last = jsonOf(
            harness.detail.execute(
                GetRecordDetailArgs(
                    kind = "NOTE", id = "00000000-0000-4000-8000-0000000000bb",
                    maxChars = 2000, segmentCursor = 2,
                ),
            ),
        )
        assertEquals(false, last["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("😄".repeat(1000) + "结尾", last["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun detailHidesDeletedRecordsAndRejectsGuessableIds() = runBlocking {
        val harness = ToolHarness()
        harness.timeline.detailError = TimelineException(TimelineError.NotVisible)
        val hidden = jsonOf(harness.detail.execute(GetRecordDetailArgs("NOTE", "00000000-0000-4000-8000-0000000000cc")))
        assertEquals("NotVisible", hidden["error"]!!.jsonPrimitive.content)
        harness.timeline.detailError = TimelineException(TimelineError.NotFound)
        val missing = jsonOf(harness.detail.execute(GetRecordDetailArgs("TRANSACTION", "00000000-0000-4000-8000-0000000000dd")))
        assertEquals("NotFound", missing["error"]!!.jsonPrimitive.content)
        val malformed = jsonOf(harness.detail.execute(GetRecordDetailArgs("NOTE", "not-a-uuid")))
        assertEquals(false, malformed["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(harness.context.references().isEmpty())
    }

    @Test
    fun prepareMapsCreateLinkAndNoteOperationsWithResolvedNames() = runBlocking {
        val harness = ToolHarness()
        val result = jsonOf(
            harness.prepare.execute(
                PrepareChangesArgs(
                    ops = listOf(
                        PrepareOpArgs(
                            op = "CREATE_TRANSACTION", localRef = "#t1", direction = "EXPENSE",
                            amountText = "36.50", accountName = "日常账户", categoryName = "餐饮",
                            memo = "午饭", localDateTime = "2026-10-24T12:30:00", zoneId = "Asia/Shanghai",
                        ),
                        PrepareOpArgs(
                            op = "CREATE_NOTE", localRef = "#n1", body = "报销提醒",
                            localDateTime = "2026-10-24T12:31:00", zoneId = "Asia/Shanghai",
                        ),
                        PrepareOpArgs(
                            op = "LINK",
                            pairs = listOf(LinkPairArgs(transaction = "#t1", note = "#n1")),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(true, result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("false", result["committed"]!!.jsonPrimitive.content)
        assertEquals("1", result["revision"]!!.jsonPrimitive.content)
        val (context, intents) = harness.proposals.prepares.single()
        assertEquals("session-1", context.sessionId)
        assertEquals("run-1", context.runId)
        assertEquals(7, context.authEpoch)
        val create = intents.filterIsInstance<ProposalIntent.CreateTransaction>().single()
        assertEquals(3650, create.input.amountText.let { com.paherdin.record2.records.Money.parseMinor(it) })
        assertEquals(com.paherdin.record2.records.FinanceSeeds.ACCOUNT_ID, create.input.accountId)
        assertEquals(1, create.input.accountVersion)
        assertEquals("00000000-0000-4000-8000-000000000101", create.input.categoryId)
        val note = intents.filterIsInstance<ProposalIntent.CreateNote>().single()
        assertEquals("报销提醒", note.body)
        val link = intents.filterIsInstance<ProposalIntent.LinkRecords>().single()
        assertEquals("#t1", (link.pairs.single().transaction as com.paherdin.record2.proposals.RecordRef.Local).localRef)
    }

    @Test
    fun prepareResolvesExistingRecordEndpointsWithVersions() = runBlocking {
        val harness = ToolHarness()
        harness.prepare.execute(
            PrepareChangesArgs(
                ops = listOf(
                    PrepareOpArgs(
                        op = "UPDATE_TRANSACTION", id = "00000000-0000-4000-8000-0000000000aa",
                        expectedVersion = 4, direction = "EXPENSE", amountText = "40.00",
                        accountName = "日常账户", localDateTime = "2026-10-24T12:30:00", zoneId = "Asia/Shanghai",
                    ),
                    PrepareOpArgs(
                        op = "UNLINK",
                        pairs = listOf(
                            LinkPairArgs(
                                transaction = "00000000-0000-4000-8000-0000000000aa", transactionVersion = 4,
                                note = "00000000-0000-4000-8000-0000000000bb", noteVersion = 2,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val intents = harness.proposals.prepares.single().second
        val update = intents.filterIsInstance<ProposalIntent.UpdateTransaction>().single()
        assertEquals(4, update.expectedVersion)
        val unlink = intents.filterIsInstance<ProposalIntent.UnlinkRecords>().single()
        val pair = unlink.pairs.single()
        assertEquals(4, pair.transactionVersion)
        assertEquals(2, pair.noteVersion)
    }

    @Test
    fun prepareRejectsUnknownAccountAndDirectionMismatchedCategoryWithoutServiceCall() = runBlocking {
        val harness = ToolHarness()
        val account = jsonOf(
            harness.prepare.execute(
                PrepareChangesArgs(
                    ops = listOf(
                        PrepareOpArgs(
                            op = "CREATE_TRANSACTION", localRef = "#t1", direction = "EXPENSE",
                            amountText = "10.00", accountName = "不存在的账户",
                            localDateTime = "2026-10-24T12:30:00", zoneId = "Asia/Shanghai",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(false, account["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(account["issues"].toString().contains("不存在的账户"))
        val category = jsonOf(
            harness.prepare.execute(
                PrepareChangesArgs(
                    ops = listOf(
                        PrepareOpArgs(
                            op = "CREATE_TRANSACTION", localRef = "#t1", direction = "EXPENSE",
                            amountText = "10.00", accountName = "日常账户", categoryName = "工资",
                            localDateTime = "2026-10-24T12:30:00", zoneId = "Asia/Shanghai",
                        ),
                    ),
                ),
            ),
        )
        assertTrue(category["issues"].toString().contains("工资"))
        val directionMissing = jsonOf(
            harness.prepare.execute(
                PrepareChangesArgs(
                    ops = listOf(
                        PrepareOpArgs(
                            op = "CREATE_TRANSACTION", localRef = "#t1",
                            amountText = "10.00", accountName = "日常账户",
                            localDateTime = "2026-10-24T12:30:00", zoneId = "Asia/Shanghai",
                        ),
                    ),
                ),
            ),
        )
        assertTrue(directionMissing["issues"].toString().contains("direction"))
        assertTrue(harness.proposals.prepares.isEmpty())
        assertTrue(harness.context.clarificationIssues.isNotEmpty())
    }

    @Test
    fun prepareRevisePathCarriesProposalIdAndExpectedRevision() = runBlocking {
        val harness = ToolHarness()
        harness.prepare.execute(
            PrepareChangesArgs(
                proposalId = "00000000-0000-4000-8000-0000000000ee", expectedRevision = 2,
                ops = listOf(
                    PrepareOpArgs(
                        op = "CREATE_NOTE", localRef = "#n1", body = "v3",
                        localDateTime = "2026-10-24T12:30:00", zoneId = "Asia/Shanghai",
                    ),
                ),
            ),
        )
        val (id, revision, _) = harness.proposals.revises.single()
        assertEquals("00000000-0000-4000-8000-0000000000ee", id)
        assertEquals(2, revision)
        assertTrue(harness.proposals.prepares.isEmpty())
    }

    @Test
    fun prepareSurfacesActiveProposalWithRevisionGuidance() = runBlocking {
        val harness = ToolHarness()
        harness.proposals.prepareError = ProposalException(ProposalError.ActiveProposalExists)
        harness.proposals.active = viewOf("00000000-0000-4000-8000-0000000000ff", 3)
        val result = jsonOf(
            harness.prepare.execute(
                PrepareChangesArgs(
                    ops = listOf(
                        PrepareOpArgs(
                            op = "CREATE_NOTE", localRef = "#n1", body = "dup",
                            localDateTime = "2026-10-24T12:30:00", zoneId = "Asia/Shanghai",
                        ),
                    ),
                ),
            ),
        )
        assertEquals("ActiveProposalExists", result["error"]!!.jsonPrimitive.content)
        assertEquals("3", result["existingRevision"]!!.jsonPrimitive.content)
    }

    @Test
    fun aggregateEnforcesRangeCapAndResolvesCategoryScope() = runBlocking {
        val harness = ToolHarness()
        val tooWide = jsonOf(
            harness.aggregate.execute(
                AggregateFinancesArgs(dateStart = "2025-01-01", dateEnd = "2026-12-31", zoneId = "Asia/Shanghai"),
            ),
        )
        assertEquals(false, tooWide["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(tooWide["issues"].toString().contains("days"))
        val unknown = jsonOf(
            harness.aggregate.execute(
                AggregateFinancesArgs(dateStart = "2026-10-01", dateEnd = "2026-10-31", zoneId = "Asia/Shanghai", categoryScope = "餐饮收入"),
            ),
        )
        assertTrue(unknown["issues"].toString().contains("餐饮收入"))
        harness.timeline.aggregateResult = com.paherdin.record2.records.FinanceAggregate(
            TimelineRange(0, 1), com.paherdin.record2.records.FinanceCategoryScope.All,
            100000, 3650, 2, 1,
        )
        val ok = jsonOf(
            harness.aggregate.execute(
                AggregateFinancesArgs(dateStart = "2026-10-01", dateEnd = "2026-10-02", zoneId = "Asia/Shanghai"),
            ),
        )
        assertEquals(true, ok["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(100000L, ok["incomeMinor"]!!.jsonPrimitive.long)
        assertEquals("CNY", ok["currency"]!!.jsonPrimitive.content)
        assertEquals(1, harness.timeline.aggregateCalls.size)
    }

    @Test
    fun guardStopsToolsAfterRevocationOrRunInvalidation() = runBlocking {
        val harness = ToolHarness()
        harness.security.valid = false
        try {
            harness.search.execute(SearchRecordsArgs())
            fail("revocation must fail the run, not return a tool result")
        } catch (expected: RecordToolException) {
            assertEquals(RecordToolFailure.AuthorizationRevoked, expected.code)
        }
        assertTrue(harness.timeline.timelineCalls.isEmpty())
        // A poisoned run keeps its original hard-failure code; exercise the separate run-state
        // guard on a fresh context instead of resetting the already-poisoned ledger.
        val invalidated = ToolHarness()
        invalidated.history.runState = RunState.INTERRUPTED
        try {
            invalidated.aggregate.execute(AggregateFinancesArgs("2026-10-01", "2026-10-02", "Asia/Shanghai"))
            fail("an invalidated run must fail closed")
        } catch (expected: RecordToolException) {
            assertEquals(RecordToolFailure.RunInvalidated, expected.code)
        }
    }

    @Test
    fun budgetStopsIdenticalNoProgressCallsAndPerToolCaps() = runBlocking {
        val harness = ToolHarness()
        repeat(RecordToolBudget.IDENTICAL_CALL_LIMIT) {
            harness.search.execute(SearchRecordsArgs(keyword = "咖啡"))
        }
        try {
            harness.search.execute(SearchRecordsArgs(keyword = "咖啡"))
            fail("identical calls beyond the limit are no-progress")
        } catch (expected: RecordToolException) {
            assertEquals(RecordToolFailure.BudgetExceeded, expected.code)
            assertTrue(expected.message!!.contains("no progress"))
        }
        val harness2 = ToolHarness()
        harness2.timeline.page = TimelinePage("gen-1", 0, emptyList(), null)
        repeat(RecordToolBudget.SEARCH_CALLS_PER_RUN) { index ->
            harness2.search.execute(SearchRecordsArgs(keyword = "w$index"))
        }
        try {
            harness2.search.execute(SearchRecordsArgs(keyword = "final"))
            fail("per-tool cap must fail closed")
        } catch (expected: RecordToolException) {
            assertEquals(RecordToolFailure.BudgetExceeded, expected.code)
        }
    }

    @Test
    fun budgetStopsRunsPastTheWallClock() = runBlocking {
        val harness = ToolHarness(startedAt = 0)
        harness.clock.nowMillis = RecordToolBudget.WALL_MS_PER_RUN + 1
        try {
            harness.search.execute(SearchRecordsArgs())
            fail("wall clock budget must fail closed")
        } catch (expected: RecordToolException) {
            assertEquals(RecordToolFailure.BudgetExceeded, expected.code)
        }
    }

    @Test
    fun classificationAttributesProposalReadyOnlyToThisRun() = runBlocking {
        val harness = ToolHarness()
        harness.proposals.active = viewOf("00000000-0000-4000-8000-000000000011", 2, lastWriter = "run-1")
        val prepared = harness.context.classifyOutcome("提案已就绪")
        assertEquals(com.paherdin.record2.sessions.MessageResultKind.ProposalReady, prepared.resultKind)
        assertEquals("00000000-0000-4000-8000-000000000011", prepared.proposalId)
        assertEquals(2L, prepared.proposalRevision)
        assertEquals(false, prepared.resultJson!!.contains("\"formalRecordsWritten\":1"))
        harness.proposals.active = viewOf("00000000-0000-4000-8000-000000000012", 1, lastWriter = "run-earlier")
        val answer = harness.context.classifyOutcome("今天没有新提案")
        assertEquals(com.paherdin.record2.sessions.MessageResultKind.Answer, answer.resultKind)
        harness.proposals.active = null
        harness.context.onClarification(listOf("amountText: field is required"))
        val clarify = harness.context.classifyNeeded()
        assertEquals(com.paherdin.record2.sessions.MessageResultKind.ClarificationNeeded, clarify.resultKind)
        assertTrue(clarify.resultJson!!.contains("amountText"))
    }

    @Test
    fun referencesAreCollectedBoundedAndOrdered() = runBlocking {
        val harness = ToolHarness()
        harness.timeline.page = TimelinePage(
            "gen-1", 0,
            (1..(RecordToolBudget.REFERENCES_PER_KIND + 2)).map { transactionEntry("00000000-0000-4000-8000-%012x".format(it)) } +
                (1..3).map { noteEntry("00000000-0000-4000-8001-%012x".format(it)) },
            null,
        )
        harness.search.execute(SearchRecordsArgs())
        val references = harness.context.references()
        assertEquals(RecordToolBudget.REFERENCES_PER_KIND + 3, references.size)
        assertTrue(references.take(RecordToolBudget.REFERENCES_PER_KIND).all { it.transactionId != null })
        assertTrue(references.drop(RecordToolBudget.REFERENCES_PER_KIND).all { it.noteId != null })
        assertEquals((0 until references.size).toList(), references.map { it.ordinal })
    }
}

private fun RecordToolContext.classifyNeeded(): com.paherdin.record2.sessions.RunOutcome = runBlocking {
    classifyOutcome("请补充信息")
}

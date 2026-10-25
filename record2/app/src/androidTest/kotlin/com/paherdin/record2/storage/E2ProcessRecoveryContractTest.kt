package com.paherdin.record2.storage

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.ai.runs.AgentRunCoordinator
import com.paherdin.record2.ai.runs.AgentRunOwner
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real process-death recovery contract (Plan E2, SES-03): a run killed mid-flight must surface
 * as INTERRUPTED at the next process's ownership gate, the cold start must issue ZERO model or
 * tool requests, and a new run must revalidate authorization and rebuild context from Room
 * history only. Driven by tools/verify-e2-process.py:
 *
 *  1. `#prepare` (invocation A, `-e databaseName <name>`): configures the synthetic database,
 *     launches a run over a scripted transport that logs each request to files/e2/requests.log
 *     and never answers, reports `e2_ready` with the session/database/request baseline, then
 *     deliberately blocks so the host can kill the process while the run is in flight.
 *  2. The driver captures the PID, `am force-stop`s the process (PID must vanish), relaunches
 *     MainActivity and waits for a fresh foreground PID.
 *  3. `#verify` (invocation B, `-e databaseName <name> -e sessionId <id> -e requestCount <n>`):
 *     the fresh process adopts ownership (1 run INTERRUPTED), the request log is unchanged —
 *     proving no automatic model/tool call at cold start — and one explicitly launched new run
 *     completes without the interrupted input ever entering its model context.
 *
 * Deterministic scripted transport only: no real key, no network, synthetic data.
 */
class E2ProcessRecoveryContractTest {
    private val interruptedInput = "进程终止前的输入"

    private fun countRequests(): Int =
        if (requestsLogFile().isFile) requestsLogFile().readLines().count { it.isNotBlank() } else 0

    /** Transport that persists each request line across process death and then blocks forever. */
    private class LoggingBlockingHttp : ai.koog.http.client.KoogHttpClient {
        val logged = CompletableDeferred<Unit>()

        override val clientName: String = "record2-e2-process"

        override suspend fun <R : Any> get(
            path: String, responseType: kotlin.reflect.KClass<R>,
            parameters: Map<String, String>, headers: Map<String, String>,
        ): R = error("LoggingBlockingHttp does not support GET")

        override suspend fun <T : Any, R : Any> post(
            path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>,
            responseType: kotlin.reflect.KClass<R>,
            parameters: Map<String, String>, headers: Map<String, String>,
        ): R {
            val log = requestsLogFile()
            log.appendText("request ${System.currentTimeMillis()} ${requestBody.toString().length}\n")
            logged.complete(Unit)
            CompletableDeferred<Unit>().await() // the host kills this process here
            error("unreachable")
        }

        override fun <T : Any, R : Any, O : Any> sse(
            path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>,
            dataFilter: (String?) -> Boolean,
            decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
            parameters: Map<String, String>, headers: Map<String, String>,
        ) = error("LoggingBlockingHttp does not support SSE")

        override fun <T : Any> lines(
            path: String, requestBody: T, requestBodyType: kotlin.reflect.KClass<T>,
            parameters: Map<String, String>, headers: Map<String, String>,
        ) = error("LoggingBlockingHttp does not support lines")

        override fun close() = Unit
    }

    @Test
    fun prepare() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val databaseName = args.getString("databaseName")
            ?: error("verify-e2-process.py must pass -e databaseName")
        requestsLogFile().delete()
        val fixture = E2LifecycleFixture("process", databaseName)
        try {
            fixture.initialize()
            val session = fixture.history.createSession("e2 process recovery")
            val http = LoggingBlockingHttp()
            val runOwner = AgentRunOwner(
                coordinator = AgentRunCoordinator(
                    security = fixture.security,
                    history = fixture.history,
                    processOwnerId = fixture.owner,
                    clock = sessionClock,
                    httpClientFactory = singleClientFactory(http),
                ),
                history = fixture.history,
                processOwnerId = fixture.owner,
            )
            runOwner.launchRun(session.id, interruptedInput)
            withTimeout(30_000) { http.logged.await() }
            assertEquals(1, countRequests())
            // Baseline for the driver; the run stays in flight and this process gets killed.
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "e2_ready",
                        JSONObject()
                            .put("database", databaseName)
                            .put("sessionId", session.id)
                            .put("requests", countRequests())
                            .toString(),
                    )
                },
            )
            CompletableDeferred<Unit>().await() // the driver force-stops this process here
            Unit
        } finally {
            // Not reached in the driven run; keeps ad-hoc local runs from leaking the handle.
            fixture.database.close()
        }
    }

    @Test
    fun verify() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val databaseName = args.getString("databaseName") ?: error("missing -e databaseName")
        val sessionId = args.getString("sessionId") ?: error("missing -e sessionId")
        val baselineRequests = args.getString("requestCount")?.toInt() ?: error("missing -e requestCount")
        // Same fixture id as prepare: the Keystore-backed credential and its encrypted blob
        // live in the vault directory and must survive the process restart (deterministic
        // sentinel; no real key).
        val fixture = E2LifecycleFixture("process", databaseName)
        try {
            // Cold start: the request log is untouched before and after the ownership gate —
            // recovery and app startup issue no model, tool or network call (SES-03).
            assertEquals(baselineRequests, countRequests())
            val nextOwner = "${fixture.owner}-fresh"
            val http = ScriptedDeviceHttp { _, body ->
                requestsLogFile().appendText("request " + System.currentTimeMillis() + " " + body.length + System.lineSeparator())
                chatDeviceResponse("RECOVERED-731")
            }
            val runOwner = AgentRunOwner(
                coordinator = AgentRunCoordinator(
                    security = fixture.security,
                    history = fixture.history,
                    processOwnerId = nextOwner,
                    clock = sessionClock,
                    httpClientFactory = deviceScriptFactory(http),
                ),
                history = fixture.history,
                processOwnerId = nextOwner,
            )
            val receipt = runOwner.recoverProcess()
            assertTrue("fresh process must adopt ownership", receipt.adopted)
            assertEquals(1, receipt.interruptedRuns)
            assertEquals(baselineRequests, countRequests())

            // The killed run is durably INTERRUPTED with its slot freed; the input stays readable.
            val runs = SchemaDiskObserver.snapshot(
                InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(databaseName),
            ).getValue("agent_run")
            assertEquals(1, runs.size)
            assertEquals("INTERRUPTED", runs.single()[9])
            assertEquals(null, runs.single()[10])
            val messagesBefore = fixture.history.sessionMessages(sessionId)
            assertEquals(listOf(interruptedInput), messagesBefore.map { it.body })
            assertEquals(com.paherdin.record2.sessions.RunState.INTERRUPTED, messagesBefore.single().runState)

            // One explicitly launched new run completes; the interrupted input never re-enters
            // the model context, and the log grew by exactly the one new request.
            val handle = runOwner.launchRun(sessionId, "恢复后的新输入")
            val result = withTimeout(30_000) { handle.await() }
            assertEquals("RECOVERED-731", result.answer)
            assertEquals(baselineRequests + 1, countRequests())
            assertFalse(http.requests.single().contains(interruptedInput))
            assertTrue(http.requests.single().contains("恢复后的新输入"))

            val after = fixture.history.sessionMessages(sessionId)
            assertEquals(
                listOf(interruptedInput, "恢复后的新输入", result.answer),
                after.map { it.body },
            )
            Unit
        } finally {
            fixture.database.close()
        }
    }

    private fun singleClientFactory(client: ai.koog.http.client.KoogHttpClient): ai.koog.http.client.KoogHttpClient.Factory =
        object : ai.koog.http.client.KoogHttpClient.Factory {
            override fun create(
                clientName: String, baseUrl: String, headers: Map<String, String>,
                queryParameters: Map<String, String>, requestTimeoutMillis: Long,
                connectTimeoutMillis: Long, socketTimeoutMillis: Long,
                json: kotlinx.serialization.json.Json,
            ): ai.koog.http.client.KoogHttpClient = client
        }
}

private fun requestsLogFile(): File =
    File(
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        "e2/requests.log",
    ).also { it.parentFile?.mkdirs() }

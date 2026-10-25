package com.paherdin.record2.ai.tools

import ai.koog.http.client.KoogHttpClient
import com.paherdin.record2.sessions.RunState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlin.reflect.KClass

/**
 * Request-boundary guard for a run with record tools. Koog 1.3.0 converts a tool exception into a
 * model-visible tool result and keeps looping, so revocation, run invalidation or a hard budget
 * must additionally block the NEXT network request — this is the E0 §23 seam ("D0/D1 own the
 * final revalidation at the actual request start"). Every request method re-checks the poisoned
 * flag, the PERSISTED run state, the current authorization epoch and the run wall clock before
 * delegating; a violation fails the run deterministically instead of leaking another prompt.
 *
 * The persisted-state read is what catches a run cancelled/deleted by E2 even when the E0
 * authorization epoch did not change. Usage is charged after each successful response, so the
 * cumulative token/cost guard cannot be bypassed by a final answer.
 */
internal class GuardedHttpClient(
    private val delegate: KoogHttpClient,
    private val context: RecordToolContext,
) : KoogHttpClient {
    override val clientName: String get() = delegate.clientName

    private suspend fun check() {
        context.poisoned?.let { code ->
            throw RecordToolException(code, "request blocked: run already failed ($code)")
        }
        val run = context.deps.history.persistedResult(context.start.runId)
            ?: context.poison(RecordToolFailure.RunInvalidated, "request blocked: run row missing")
        if (run.state != RunState.RUNNING) {
            context.poison(RecordToolFailure.RunInvalidated, "request blocked: run is ${run.state}")
        }
        if (!context.deps.security.stillValid(context.permit)) {
            context.poison(RecordToolFailure.AuthorizationRevoked, "request blocked: authorization revoked")
        }
        if (!context.ledger.withinWallClock()) {
            context.poison(RecordToolFailure.BudgetExceeded, "request blocked: run wall clock exhausted")
        }
    }

    override suspend fun <R : Any> get(
        path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>,
    ): R {
        check()
        return delegate.get(path, responseType, parameters, headers)
    }

    override suspend fun <T : Any, R : Any> post(
        path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>,
        parameters: Map<String, String>, headers: Map<String, String>,
    ): R {
        check()
        val response = delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)
        if (requestBody is String && response is String) {
            context.recordUsage(requestBody, response)
        }
        return response
    }

    // sse()/lines() are not suspend in KoogHttpClient; run the complete guard at collection time,
    // before the upstream flow is collected. This removes the authorization/run/time bypass even
    // though D1's normal chat path uses post().
    override fun <T : Any, R : Any, O : Any> sse(
        path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>, headers: Map<String, String>,
    ): Flow<O> = delegate.sse(
        path, requestBody, requestBodyType, dataFilter,
        decodeStreamingResponse, processStreamingChunk, parameters, headers,
    ).onStart { check() }

    override fun <T : Any> lines(
        path: String, requestBody: T, requestBodyType: KClass<T>,
        parameters: Map<String, String>, headers: Map<String, String>,
    ): Flow<String> = delegate.lines(path, requestBody, requestBodyType, parameters, headers)
        .onStart { check() }

    override fun close() = delegate.close()
}

internal class GuardedHttpClientFactory(
    private val delegate: KoogHttpClient.Factory,
    private val context: RecordToolContext,
) : KoogHttpClient.Factory {
    override fun create(
        clientName: String, baseUrl: String, headers: Map<String, String>,
        queryParameters: Map<String, String>, requestTimeoutMillis: Long,
        connectTimeoutMillis: Long, socketTimeoutMillis: Long, json: kotlinx.serialization.json.Json,
    ): KoogHttpClient = GuardedHttpClient(
        delegate.create(
            clientName, baseUrl, headers, queryParameters,
            requestTimeoutMillis, connectTimeoutMillis, socketTimeoutMillis, json,
        ),
        context,
    )
}

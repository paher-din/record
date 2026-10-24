package com.paherdin.record2.storage

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.KoogHttpClient
import ai.koog.serialization.typeToken
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.ai.security.AiMode
import com.paherdin.record2.ai.security.KeystoreCredentialVault
import com.paherdin.record2.ai.security.RecordProviderReceivers
import com.paherdin.record2.sessions.AgentHistoryError
import com.paherdin.record2.sessions.AgentHistoryException
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val sessionClock: Clock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC)

internal fun sessionDatabase(name: String): RecordDatabase {
    check(Regex("e1-contract-[a-z0-9-]+\\.db").matches(name))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == "com.paherdin.record2.debug")
    return androidx.room.Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
}

internal suspend fun expectHistoryError(code: AgentHistoryError, block: suspend () -> Unit) {
    val error = try {
        block(); error("Expected $code")
    } catch (error: AgentHistoryException) {
        error
    }
    org.junit.Assert.assertEquals(code, error.code)
}

internal class ScriptedDeviceHttp(
    private val responder: suspend (callIndex: Int, requestBody: String) -> String,
) : KoogHttpClient {
    override val clientName: String = "record2-e1-scripted-device"
    val requests = CopyOnWriteArrayList<String>()

    override suspend fun <R : Any> get(
        path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>,
    ): R = error("ScriptedDeviceHttp does not support GET")

    override suspend fun <T : Any, R : Any> post(
        path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>,
        parameters: Map<String, String>, headers: Map<String, String>,
    ): R {
        val body = requestBody as String
        requests += body
        @Suppress("UNCHECKED_CAST")
        return responder(requests.size, body) as R
    }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>, headers: Map<String, String>,
    ) = error("ScriptedDeviceHttp does not support SSE")

    override fun <T : Any> lines(
        path: String, requestBody: T, requestBodyType: KClass<T>,
        parameters: Map<String, String>, headers: Map<String, String>,
    ) = error("ScriptedDeviceHttp does not support lines")

    override fun close() = Unit
}

internal fun deviceScriptFactory(http: ScriptedDeviceHttp): KoogHttpClient.Factory =
    object : KoogHttpClient.Factory {
        override fun create(
            clientName: String, baseUrl: String, headers: Map<String, String>,
            queryParameters: Map<String, String>, requestTimeoutMillis: Long,
            connectTimeoutMillis: Long, socketTimeoutMillis: Long, json: Json,
        ): KoogHttpClient = http
    }

internal fun chatDeviceResponse(
    text: String? = null,
    reasoning: String? = null,
    toolCall: Pair<String, String>? = null,
): String {
    val message = buildJsonObject {
        put("role", "assistant")
        put("content", text?.let(::JsonPrimitive) ?: JsonPrimitive(""))
        if (reasoning != null) put("reasoning_content", reasoning)
        if (toolCall != null) {
            put(
                "tool_calls",
                JsonArray(listOf(buildJsonObject {
                    put("id", toolCall.first)
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject { put("name", "device_echo"); put("arguments", toolCall.second) },
                    )
                })),
            )
        }
    }
    return buildJsonObject {
        put("id", "e1-device-fixture")
        put("object", "chat.completion")
        put("created", 0)
        put("model", "deepseek-flash")
        put("system_fingerprint", "e1-device-fixture")
        put("choices", JsonArray(listOf(buildJsonObject {
            put("index", 0)
            put("message", message)
            put("finish_reason", if (toolCall == null) "stop" else "tool_calls")
        })))
        put("usage", buildJsonObject {
            put("prompt_tokens", 10)
            put("completion_tokens", 5)
            put("total_tokens", 15)
        })
    }.toString()
}

internal fun deviceRequestMessages(body: String): List<JsonObject> =
    Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }

internal fun JsonObject.deviceRole(): String = this["role"]!!.jsonPrimitive.content

@Serializable
internal data class DeviceEchoArgs(val word: String)

internal class DeviceEchoTool : SimpleTool<DeviceEchoArgs>(
    argsType = typeToken<DeviceEchoArgs>(),
    name = "device_echo",
    description = "Echo a synthetic word exactly once.",
) {
    val calls = CopyOnWriteArrayList<String>()
    override suspend fun execute(args: DeviceEchoArgs): String {
        calls += args.word
        return "ECHO:${args.word}"
    }
}

internal fun echoToolRegistry(tool: DeviceEchoTool): ToolRegistry = ToolRegistry { tool(tool) }

internal class SessionContractFixture(private val id: String) {
    val name = "e1-contract-$id.db"
    val directory: File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
        "e1-vault-$id",
    )
    val keyAlias = "record2.ai.e1.$id"
    val owner = "e1-owner-$id"
    val receiver = RecordProviderReceivers.deepSeek
    val sentinel = "r2-e1-synthetic-7c1d4e9a2b3f4a58"

    var database: RecordDatabase = sessionDatabase(name)
        private set
    val vault = KeystoreCredentialVault(directory, keyAlias)
    val security = RoomAiSecurityService(database, vault, clock = sessionClock)
    val finance = RoomFinanceService(database, sessionClock)
    var history: RoomAgentHistoryService = RoomAgentHistoryService(database, sessionClock)
        private set

    suspend fun initialize() {
        finance.initialize()
        history.beginProcessOwnership(owner)
        security.setMode(AiMode.FULL)
        security.selectReceiver(receiver)
        security.grant(receiver)
        security.replaceCredential(sentinel.toCharArray())
    }

    fun reinstateHistory(checkpoint: (SessionCheckpoint) -> Unit = {}) {
        history = RoomAgentHistoryService(database, sessionClock, checkpoint)
    }

    /** Simulates a process restart for reopen checks; security/finance keep their old connection. */
    fun reopenDatabase() {
        database.close()
        database = sessionDatabase(name)
    }

    fun snapshot(): Map<String, List<List<Any?>>> = SchemaDiskObserver.snapshot(
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name),
    )
}

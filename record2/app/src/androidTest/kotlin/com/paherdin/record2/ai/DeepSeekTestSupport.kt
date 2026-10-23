package com.paherdin.record2.ai

import android.content.Context
import android.os.Build
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.typeToken
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

internal object D0Key {
    private const val RELATIVE_PATH = "d0/deepseek.key"

    fun load(context: Context): String {
        val file = File(context.filesDir, RELATIVE_PATH)
        check(file.isFile) {
            "D0 key file is missing. Inject it into app-private storage before running this class."
        }
        val key = file.readText().trim()
        file.delete()
        check(key.startsWith("sk-") && key.length >= 20) {
            "D0 key file does not contain a DeepSeek API key."
        }
        return key
    }

    fun fingerprint(key: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)
}

internal class RecordingHttpClient(
    private val delegate: KoogHttpClient,
) : KoogHttpClient by delegate {
    val requests = CopyOnWriteArrayList<String>()
    val responses = CopyOnWriteArrayList<String>()

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R {
        if (requestBody is String) requests += requestBody
        val response = delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)
        if (response is String) responses += response
        return response
    }

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R {
        val response = delegate.get(path, responseType, parameters, headers)
        if (response is String) responses += response
        return response
    }
}

internal class RecordingFactory(
    private val delegate: KoogHttpClient.Factory,
) : KoogHttpClient.Factory {
    val clients = CopyOnWriteArrayList<RecordingHttpClient>()

    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient = RecordingHttpClient(
        delegate.create(
            clientName = clientName,
            baseUrl = baseUrl,
            headers = headers,
            queryParameters = queryParameters,
            requestTimeoutMillis = requestTimeoutMillis,
            connectTimeoutMillis = connectTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            json = json,
        ),
    ).also { clients += it }

    val requests: List<String> get() = clients.flatMap { it.requests }
    val responses: List<String> get() = clients.flatMap { it.responses }
}

internal class ScriptedHttpClient(
    private val responder: suspend (callIndex: Int, requestBody: String) -> String,
) : KoogHttpClient {
    override val clientName: String = "record2-d0-scripted"
    val requests = CopyOnWriteArrayList<String>()

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = error("ScriptedHttpClient does not support GET")

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R {
        val body = requestBody as String
        requests += body
        @Suppress("UNCHECKED_CAST")
        return responder(requests.size, body) as R
    }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ) = error("ScriptedHttpClient does not support SSE")

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ) = error("ScriptedHttpClient does not support lines")

    override fun close() = Unit
}

@Serializable
internal data class EchoArgs(val word: String)

internal class EchoTool : SimpleTool<EchoArgs>(
    argsType = typeToken<EchoArgs>(),
    name = "record2_echo",
    description = "Echo a synthetic word exactly once. Use this tool whenever the user asks for an echo.",
) {
    val calls = CopyOnWriteArrayList<String>()

    override suspend fun execute(args: EchoArgs): String {
        calls += args.word
        return "ECHO:${args.word}"
    }
}

internal class SlowEchoTool(
    private val delayMillis: Long = 100,
) : SimpleTool<EchoArgs>(
    argsType = typeToken<EchoArgs>(),
    name = "record2_echo",
    description = "Echo a synthetic word after a short delay.",
) {
    val calls = CopyOnWriteArrayList<String>()

    override suspend fun execute(args: EchoArgs): String {
        calls += args.word
        delay(delayMillis)
        return "ECHO:${args.word}"
    }
}

@Serializable
internal data class StepArgs(val step: String)

internal class StepTool : SimpleTool<StepArgs>(
    argsType = typeToken<StepArgs>(),
    name = "record2_step",
    description = "Run one synthetic two-step sequence. The first step returns the instruction for the second.",
) {
    val calls = CopyOnWriteArrayList<String>()

    override suspend fun execute(args: StepArgs): String {
        calls += args.step
        return when (args.step) {
            "first" -> "STEP1:ORANGE-731. Now call record2_step again with step=second."
            "second" -> "STEP2:DONE-ORANGE-731."
            else -> "UNKNOWN_STEP:${args.step}"
        }
    }
}

internal class NoProgressEchoTool(
    private val maxIdenticalCalls: Int = 3,
) : SimpleTool<EchoArgs>(
    argsType = typeToken<EchoArgs>(),
    name = "record2_echo",
    description = "Echo a synthetic word. Repeated identical calls are a no-progress signal.",
) {
    val calls = CopyOnWriteArrayList<String>()

    override suspend fun execute(args: EchoArgs): String {
        calls += args.word
        val identical = calls.count { it == args.word }
        check(identical <= maxIdenticalCalls) {
            "NoProgress: identical tool call repeated $identical times"
        }
        return "ECHO:${args.word}"
    }
}

internal fun toolRegistry(vararg tools: SimpleTool<*>): ToolRegistry =
    ToolRegistry { tools.forEach { tool(it) } }

internal fun toolDescriptors(vararg tools: SimpleTool<*>): List<ToolDescriptor> =
    tools.map { it.descriptor }

@Serializable
internal data class FixtureToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

internal fun chatResponse(
    text: String? = null,
    reasoning: String? = null,
    toolCalls: List<FixtureToolCall> = emptyList(),
    finishReason: String = if (toolCalls.isEmpty()) "stop" else "tool_calls",
    inputTokens: Int = 10,
    outputTokens: Int = 5,
): String {
    val message = buildJsonObject {
        put("role", "assistant")
        put("content", text?.let(::JsonPrimitive) ?: JsonNull)
        if (reasoning != null) put("reasoning_content", reasoning)
        if (toolCalls.isNotEmpty()) {
            put(
                "tool_calls",
                buildJsonArray {
                    toolCalls.forEach { call ->
                        add(
                            buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }
    return buildJsonObject {
        put("id", "d0-fixture")
        put("object", "chat.completion")
        put("created", 0)
        put("model", "deepseek-flash")
        put("system_fingerprint", "d0-fixture")
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        put("message", message)
                        put("finish_reason", finishReason)
                    },
                )
            },
        )
        put(
            "usage",
            buildJsonObject {
                put("prompt_tokens", inputTokens)
                put("completion_tokens", outputTokens)
                put("total_tokens", inputTokens + outputTokens)
            },
        )
    }.toString()
}

internal fun emptyChoicesResponse(): String =
    """{"id":"d0-empty","object":"chat.completion","created":0,"model":"deepseek-flash","system_fingerprint":"d0","choices":[]}"""

internal fun parseMessages(requestBody: String): List<JsonObject> =
    Json.parseToJsonElement(requestBody).jsonObject["messages"]?.jsonObjectList().orEmpty()

internal fun JsonElement?.jsonObjectList(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

internal fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.intField(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

internal data class UsageTotals(
    val inputTokens: Int,
    val outputTokens: Int,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

internal fun usageTotals(responses: List<String>): UsageTotals {
    var input = 0
    var output = 0
    responses.forEach { response ->
        val usage = runCatching {
            Json.parseToJsonElement(response).jsonObject["usage"]?.jsonObject
        }.getOrNull() ?: return@forEach
        input += usage.intField("prompt_tokens") ?: 0
        output += usage.intField("completion_tokens") ?: 0
    }
    return UsageTotals(input, output)
}

internal class D0Evidence(
    private val fileName: String,
) {
    private val records = CopyOnWriteArrayList<JsonObject>()
    var keyFingerprint: String? = null

    fun add(record: JsonObject) {
        records += record
    }

    fun write(context: Context) {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "d0")
        directory.mkdirs()
        val body = buildJsonObject {
            put("device", Build.FINGERPRINT)
            put("sdk", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("key_fingerprint", keyFingerprint ?: "unavailable")
            put("records", JsonArray(records))
        }
        File(directory, fileName).writeText(body.toString())
    }
}

internal fun recordingFactory(): RecordingFactory =
    RecordingFactory(ai.koog.http.client.ktor.KtorKoogHttpClient.Factory())

internal fun Message.Assistant.toolCalls(): List<MessagePart.Tool.Call> =
    parts.filterIsInstance<MessagePart.Tool.Call>()

internal fun Message.Assistant.reasoningText(): String =
    parts.filterIsInstance<MessagePart.Reasoning>().flatMap { it.content }.joinToString("\n")

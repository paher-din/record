package com.paherdin.record2.ai

import android.content.Context
import android.os.Build
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.typeToken
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

internal object D0TestLimits {
    const val REQUEST_TIMEOUT_MS = 300_000L
    const val CONNECT_TIMEOUT_MS = 30_000L
    const val MAX_AGENT_STEPS = 50
    const val RUN_WALL_MS = 900_000L
    const val RUN_TOKENS = 64_000
    const val FLASH_RUN_USD = 0.10
    const val PRO_RUN_USD = 0.25
    const val NO_PROGRESS_CALLS = 3
    const val BATCH_USD = 1.00

    fun costUsd(model: String, inputTokens: Int, outputTokens: Int): Double =
        if (model.contains("pro")) {
            (inputTokens * 1.32 + outputTokens * 3.96) / 1_000_000.0
        } else {
            (inputTokens * 0.30 + outputTokens * 1.20) / 1_000_000.0
        }

    fun costUsd(model: String, usage: UsageTotals): Double =
        costUsd(model, usage.inputTokens, usage.outputTokens)

    fun costCapUsd(model: String): Double =
        if (model.contains("pro")) PRO_RUN_USD else FLASH_RUN_USD
}

internal class BudgetExceededException(
    val reasons: List<String>,
) : RuntimeException(reasons.joinToString("; "))

/**
 * A functional run terminated by a D0 limit (budget, wall clock or the configured agent step cap)
 * is INCONCLUSIVE, never a protocol failure. Koog surfaces step-cap exhaustion as
 * [AIAgentMaxNumberOfIterationsReachedException] from `AIAgent.run`.
 */
internal fun runLimitFailureReason(runFailure: Throwable?): String? = when (runFailure) {
    is BudgetExceededException -> runFailure.reasons.joinToString("; ").ifEmpty { "run budget exceeded" }
    is AIAgentMaxNumberOfIterationsReachedException ->
        "agent reached the configured max iterations (${D0TestLimits.MAX_AGENT_STEPS}) without finishing"
    else -> null
}

internal class D0RunBudget {
    var inputTokens: Int = 0
        private set
    var outputTokens: Int = 0
        private set
    var estimatedCostUsd: Double = 0.0
        private set
    var unmeteredResponses: Int = 0
        private set
    var exceeded: Boolean = false
        private set
    val reasons: MutableList<String> = mutableListOf()

    fun reset() {
        inputTokens = 0
        outputTokens = 0
        estimatedCostUsd = 0.0
        unmeteredResponses = 0
        exceeded = false
        reasons.clear()
    }

    fun recordFrom(requestBody: String, responseBody: String) {
        val model = runCatching {
            Json.parseToJsonElement(requestBody).jsonObject.stringField("model")
        }.getOrNull() ?: "deepseek-flash"
        val usage = runCatching {
            Json.parseToJsonElement(responseBody).jsonObject["usage"]?.jsonObject
        }.getOrNull()
        if (usage == null) {
            // Consumption without a usage block cannot be verified; the run is reported as
            // unmetered instead of silently counting it as zero.
            unmeteredResponses += 1
            return
        }
        inputTokens += usage.intField("prompt_tokens") ?: 0
        outputTokens += usage.intField("completion_tokens") ?: 0
        estimatedCostUsd = D0TestLimits.costUsd(model, inputTokens, outputTokens)
        val total = inputTokens + outputTokens
        if (total > D0TestLimits.RUN_TOKENS && reasons.none { it.startsWith("tokens") }) {
            reasons += "tokens $total > ${D0TestLimits.RUN_TOKENS}"
        }
        val cap = D0TestLimits.costCapUsd(model)
        if (estimatedCostUsd > cap && reasons.none { it.startsWith("cost") }) {
            reasons += "cost $estimatedCostUsd > $cap"
        }
        if (reasons.isNotEmpty()) {
            exceeded = true
            throw BudgetExceededException(reasons.toList())
        }
    }
}

/**
 * Batch-wide guard for real API calls. Each real request must reserve the model's full run cap
 * before it is sent, so the batch cannot exceed [capUsd] after the fact; responses without usage
 * are charged the run cap because their consumption is unknown.
 */
internal class D0BatchBudgetGuard(private val capUsd: Double = D0TestLimits.BATCH_USD) {
    private val lock = Any()
    private var spent = 0.0
    private var unmetered = 0

    val spentUsd: Double get() = synchronized(lock) { spent }
    val unmeteredResponses: Int get() = synchronized(lock) { unmetered }

    fun reset() {
        synchronized(lock) {
            spent = 0.0
            unmetered = 0
        }
    }

    fun reserveForRequest(requestBody: String) {
        val reserve = D0TestLimits.costCapUsd(modelOf(requestBody))
        synchronized(lock) {
            if (spent + reserve > capUsd) {
                throw BudgetExceededException(
                    listOf(
                        "batch budget: spent ${"%.4f".format(spent)} + next-request reserve " +
                            "${"%.4f".format(reserve)} exceeds cap $capUsd",
                    ),
                )
            }
        }
    }

    fun record(requestBody: String, responseBody: String) {
        val model = modelOf(requestBody)
        val usage = runCatching {
            Json.parseToJsonElement(responseBody).jsonObject["usage"]?.jsonObject
        }.getOrNull()
        synchronized(lock) {
            if (usage == null) {
                unmetered += 1
                spent += D0TestLimits.costCapUsd(model)
            } else {
                spent += D0TestLimits.costUsd(
                    model,
                    usage.intField("prompt_tokens") ?: 0,
                    usage.intField("completion_tokens") ?: 0,
                )
            }
        }
    }

    private fun modelOf(requestBody: String): String =
        runCatching {
            Json.parseToJsonElement(requestBody).jsonObject.stringField("model")
        }.getOrNull() ?: "deepseek-flash"
}

internal val D0BatchBudget = D0BatchBudgetGuard()

internal suspend fun <T> withD0RunLimit(block: suspend () -> T): T =
    try {
        withTimeout(D0TestLimits.RUN_WALL_MS) { block() }
    } catch (_: TimeoutCancellationException) {
        throw BudgetExceededException(
            listOf("run wall clock exceeded ${D0TestLimits.RUN_WALL_MS}ms"),
        )
    }

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
    private val budget: D0RunBudget? = null,
    private val batchBudget: D0BatchBudgetGuard? = null,
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
        if (requestBody is String) {
            requests += requestBody
            batchBudget?.reserveForRequest(requestBody)
        }
        val response = delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)
        if (response is String) {
            responses += response
            if (requestBody is String) {
                batchBudget?.record(requestBody, response)
                budget?.recordFrom(requestBody, response)
            }
        }
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
    private val batchBudget: D0BatchBudgetGuard = D0BatchBudget,
) : KoogHttpClient.Factory {
    val clients = CopyOnWriteArrayList<RecordingHttpClient>()
    val budget = D0RunBudget()

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
        delegate = delegate.create(
            clientName = clientName,
            baseUrl = baseUrl,
            headers = headers,
            queryParameters = queryParameters,
            requestTimeoutMillis = requestTimeoutMillis,
            connectTimeoutMillis = connectTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            json = json,
        ),
        budget = budget,
        batchBudget = batchBudget,
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

internal fun noUsageResponse(): String =
    """{"id":"d0-no-usage","object":"chat.completion","created":0,"model":"deepseek-flash",
    "system_fingerprint":"d0","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},
    "finish_reason":"stop"}]}""".trimIndent()

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
        val batchCostUsd = records
            .filterNot { (it["test"] as? JsonPrimitive)?.content?.endsWith("-budget") == true }
            .sumOf { record ->
                (record["estimatedCostUsd"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            }
        check(batchCostUsd <= D0TestLimits.BATCH_USD) {
            "D0 batch estimated cost $batchCostUsd exceeds ${D0TestLimits.BATCH_USD}"
        }
        val body = buildJsonObject {
            put("device", Build.FINGERPRINT)
            put("sdk", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("key_fingerprint", keyFingerprint ?: "unavailable")
            put("batchEstimatedCostUsd", batchCostUsd)
            put("batchWithinCap", true)
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

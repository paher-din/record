package com.paherdin.record2.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekParams
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Minimal Koog/DeepSeek seam. Callers own credential acquisition; this object never persists or logs a key.
 */
object DeepSeekProtocol {
    const val BASE_URL = "https://api.deepseek.com"
    const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant. Use tools when the task requires them."

    val Flash: LLModel = LLModel(
        provider = LLMProvider.DeepSeek,
        id = "deepseek-flash",
        capabilities = DeepSeekModels.DeepSeekV4Flash.capabilities,
        contextLength = DeepSeekModels.DeepSeekV4Flash.contextLength,
        maxOutputTokens = DeepSeekModels.DeepSeekV4Flash.maxOutputTokens,
    )
    val Pro: LLModel = DeepSeekModels.DeepSeekV4Pro

    val DefaultTimeouts: ConnectionTimeoutConfig = ConnectionTimeoutConfig(
        requestTimeoutMillis = 300_000,
        connectTimeoutMillis = 30_000,
        socketTimeoutMillis = 300_000,
    )

    // DeepSeek enables thinking when the field is absent, so the normal path must send an explicit disabled value.
    fun params(thinking: Boolean?, maxTokens: Int? = null): DeepSeekParams = DeepSeekParams(
        maxTokens = maxTokens,
        additionalProperties = thinking?.let { enabled ->
            mapOf(
                "thinking" to buildJsonObject {
                    put("type", if (enabled) "enabled" else "disabled")
                },
            )
        },
    )

    fun client(
        apiKey: String,
        timeouts: ConnectionTimeoutConfig = DefaultTimeouts,
        httpClientFactory: KoogHttpClient.Factory = KtorKoogHttpClient.Factory(),
    ): DeepSeekLLMClient = DeepSeekLLMClient(
        apiKey = apiKey,
        settings = DeepSeekClientSettings(baseUrl = BASE_URL, timeoutConfig = timeouts),
        httpClientFactory = httpClientFactory,
    )

    fun agent(
        executor: PromptExecutor,
        model: LLModel,
        toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
        thinking: Boolean = false,
        maxIterations: Int,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    ): AIAgent<String, String> {
        val config = AIAgentConfig(
            prompt = prompt("record2-d0", params = params(thinking)) {
                system(systemPrompt)
            },
            model = model,
            maxAgentIterations = maxIterations,
        )
        return AIAgent(
            promptExecutor = executor,
            agentConfig = config,
            toolRegistry = toolRegistry,
        )
    }
}

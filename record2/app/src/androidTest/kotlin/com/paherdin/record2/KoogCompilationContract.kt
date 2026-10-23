package com.paherdin.record2

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.serialization.typeToken
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.room.RoomDatabase
import com.paherdin.record2.records.Money
import kotlinx.serialization.Serializable

// Build-only adapter: no executor, credentials, history store or database is created.
internal fun agentCompilationContract(
    executor: PromptExecutor,
    model: LLModel,
    history: ChatHistoryProvider,
): AIAgent<String, String> = AIAgent(
    promptExecutor = executor,
    llmModel = model,
    toolRegistry = ToolRegistry { tool(AmountProbe) },
) {
    install(ChatMemory) {
        chatHistoryProvider = history
        windowSize(20)
    }
}

@Serializable
private data class AmountArgs(val text: String)

private object AmountProbe : SimpleTool<AmountArgs>(
    argsType = typeToken<AmountArgs>(),
    name = "build_amount_probe",
    description = "Compilation probe for deterministic money formatting",
) {
    override suspend fun execute(args: AmountArgs): String =
        Money.formatMinor(Money.parseMinor(args.text))
}

@Serializable
private data object BuildDestination

@Composable
private fun navigationCompilationContract(controller: NavHostController) {
    NavHost(controller, startDestination = BuildDestination) {
        composable<BuildDestination> { }
    }
}

internal fun roomCompilationContract(database: RoomDatabase): RoomDatabase = database

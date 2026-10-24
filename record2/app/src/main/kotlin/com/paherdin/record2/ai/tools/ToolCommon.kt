package com.paherdin.record2.ai.tools

import com.paherdin.record2.records.FinanceCatalog
import com.paherdin.record2.records.FinanceCategory
import com.paherdin.record2.records.FinanceDirection
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Model-recoverable tool failures are ordinary tool results with ok=false plus bounded issue
 * strings; the model corrects the arguments or asks the user instead of the run failing.
 */
internal class ToolIssues(private val error: String = "Validation") {
    private val issues = ArrayList<Pair<String, String>>()

    fun add(field: String, message: String) {
        issues += field to message
    }

    fun present(): Boolean = issues.isNotEmpty()

    fun messages(): List<String> = issues.map { "${it.first}: ${it.second}" }

    fun result(extra: com.paherdin.record2.ai.tools.ToolResultExtra? = null): String =
        buildJsonObject {
            put("ok", false)
            put("error", error)
            put("issues", buildJsonArray {
                issues.forEach { issue ->
                    add(buildJsonObject {
                        put("field", issue.first)
                        put("message", issue.second)
                    })
                }
            })
            extra?.applyTo(this)
        }.toString()
}

internal fun interface ToolResultExtra {
    fun applyTo(builder: kotlinx.serialization.json.JsonObjectBuilder)
}

/**
 * Account/category resolution by the human names the model actually has (the registry exposes no
 * catalog tool); names are matched exactly against the current seeded catalog, never extended by
 * model output (Spec §6: the model may not widen vocabularies). The catalog is resolved once per
 * tool call through the real finance service; no DAO is exposed.
 */
internal object ToolCatalog {
    suspend fun catalog(context: RecordToolContext): FinanceCatalog =
        context.deps.finance.initialize()

    suspend fun categories(context: RecordToolContext): List<FinanceCategory> =
        catalog(context).categories

    suspend fun accountVersionByName(context: RecordToolContext, name: String): Pair<String, Long>? {
        val account = catalog(context).account
        return if (account.name == name) account.id to account.version else null
    }

    suspend fun accountName(context: RecordToolContext, id: String): String? {
        val account = catalog(context).account
        return if (account.id == id) account.name else null
    }

    suspend fun categoryByName(
        context: RecordToolContext,
        name: String,
        direction: FinanceDirection,
    ): FinanceCategory? = catalog(context).categories
        .filter { it.direction == direction }
        .firstOrNull { it.name == name }

    suspend fun categoryName(context: RecordToolContext, id: String): String? =
        catalog(context).categories.firstOrNull { it.id == id }?.name
}

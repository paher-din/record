package com.paherdin.record2.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.paherdin.record2.records.FinanceAggregate
import com.paherdin.record2.records.FinanceCategoryScope
import com.paherdin.record2.records.TimelineError
import com.paherdin.record2.records.TimelineException
import com.paherdin.record2.records.TimelineTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
internal data class AggregateFinancesArgs(
    val dateStart: String,
    val dateEnd: String,
    val zoneId: String,
    val categoryScope: String? = null,
)

/**
 * `aggregate_finances` (Spec §6): registered only now that B4's deterministic service is accepted;
 * exact Long income/expense sums by explicit date/zone/category scope with an overflow-safe
 * service underneath. This is a precise calculator, not a statistics dashboard: the result is the
 * covered range and the deterministic numbers, nothing inferred.
 */
internal class AggregateFinancesTool(private val context: RecordToolContext) :
    SimpleTool<AggregateFinancesArgs>(
        argsType = typeToken<AggregateFinancesArgs>(),
        name = "aggregate_finances",
        description = "Exact Long income/expense totals (minor units, CNY) over an explicit local " +
            "date range [dateStart, dateEnd) and zoneId. Optional categoryScope: ALL (default), " +
            "UNCATEGORIZED, or an exact category name. The range must be at most " +
            "${RecordToolBudget.AGGREGATE_MAX_DAYS} days; narrow it for longer periods.",
    ) {
    override suspend fun execute(args: AggregateFinancesArgs): String {
        context.guard(
            "aggregate_finances",
            RecordToolContext.toolJson.encodeToString(AggregateFinancesArgs.serializer(), args),
            RecordToolBudget.AGGREGATE_CALLS_PER_RUN,
        )
        val issues = ToolIssues()
        val start = parseDate(args.dateStart, "dateStart", issues)
        val end = parseDate(args.dateEnd, "dateEnd", issues)
        if (runCatching { ZoneId.of(args.zoneId) }.isFailure) {
            issues.add("zoneId", "zoneId must be a valid IANA zone id")
        }
        if (start != null && end != null && ChronoUnit.DAYS.between(start, end) > RecordToolBudget.AGGREGATE_MAX_DAYS) {
            issues.add("dateRange", "range exceeds ${RecordToolBudget.AGGREGATE_MAX_DAYS} days; split into shorter ranges")
        }
        val scope = when (val requested = args.categoryScope?.trim()?.takeIf { it.isNotBlank() }) {
            null, "ALL" -> FinanceCategoryScope.All
            "UNCATEGORIZED" -> FinanceCategoryScope.Uncategorized
            else -> ToolCatalog.categories(context).firstOrNull { it.name == requested }?.let {
                FinanceCategoryScope.Category(it.id)
            } ?: run {
                issues.add(
                    "categoryScope",
                    "unknown category \"$requested\"; valid names: " +
                        ToolCatalog.categories(context).joinToString("、") { it.name },
                )
                null
            }
        }
        if (issues.present()) return context.charge(issues.result())
        try {
            val range = TimelineTime.range(start!!, end!!, args.zoneId)
            val aggregate = context.deps.timeline.aggregateFinances(context.start.dataGeneration, range, scope!!)
            return context.charge(result(aggregate, args))
        } catch (error: TimelineException) {
            return when (error.code) {
                TimelineError.Validation -> context.charge(
                    ToolIssues().also {
                        it.add("dateRange", "invalid range: dateStart must be before dateEnd (yyyy-MM-dd, end exclusive)")
                    }.result()
                )
                TimelineError.AggregateOverflow -> context.charge(
                    ToolIssues("AggregateOverflow").also {
                        it.add("dateRange", "totals overflow Long; narrow the range or category scope")
                    }.result()
                )
                else -> context.poisonFrom(error)
            }
        }
    }

    private fun parseDate(value: String, at: String, issues: ToolIssues): LocalDate? = try {
        LocalDate.parse(value)
    } catch (error: RuntimeException) {
        issues.add(at, "must be a yyyy-MM-dd date")
        null
    }

    private fun result(aggregate: FinanceAggregate, args: AggregateFinancesArgs): String =
        buildJsonObject {
            put("ok", true)
            put("currency", "CNY")
            put("incomeMinor", aggregate.incomeMinor)
            put("expenseMinor", aggregate.expenseMinor)
            put("incomeCount", aggregate.incomeCount)
            put("expenseCount", aggregate.expenseCount)
            putJsonObject("coverage") {
                put("dateStart", args.dateStart)
                put("dateEndExclusive", args.dateEnd)
                put("zoneId", args.zoneId)
                put("category", args.categoryScope?.takeIf { it.isNotBlank() } ?: "ALL")
                put("kind", "TRANSACTION")
            }
        }.toString()
}

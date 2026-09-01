package com.paherdin.record.feature.finance

/**
 * The typed M1 finance editor destinations. Each kind owns its later-slice
 * editor route; M1 renders only an availability surface for it.
 */
enum class FinanceEditorKind { Expense, Income, Transfer }

/**
 * The M1 finance home content model. No domain data exists before M2, so the
 * ready body carries only this marker and the availability copy.
 */
data object FinanceAvailability

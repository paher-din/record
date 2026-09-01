package com.paherdin.record.navigation

import kotlinx.serialization.Serializable

/**
 * The exact M1 production route allowlist (spec section 7, plan M1-T07 Step 2).
 * Route keys are `@Serializable` Kotlin types owned by `:app`; arguments carry
 * only stable ID text and the navigation-only origin discriminator. No other
 * production route is registered.
 */

/** The launching top-level domain of every non-root destination. */
@Serializable
enum class TopLevelOrigin { Timeline, Finance, Notes, Projects }

@Serializable
data object TimelineHome

@Serializable
data object FinanceHome

@Serializable
data object NotesHome

@Serializable
data object ProjectsHome

@Serializable
data class UnifiedSearch(val origin: TopLevelOrigin)

@Serializable
data class Settings(val origin: TopLevelOrigin)

@Serializable
data class TransactionDetail(val transactionId: String, val origin: TopLevelOrigin) {
    init {
        requireCanonicalRecordId(transactionId)
    }
}

@Serializable
data class NoteDetail(val noteId: String, val origin: TopLevelOrigin) {
    init {
        requireCanonicalRecordId(noteId)
    }
}

@Serializable
data class ExpenseEditor(val origin: TopLevelOrigin)

@Serializable
data class IncomeEditor(val origin: TopLevelOrigin)

@Serializable
data class TransferEditor(val origin: TopLevelOrigin)

@Serializable
data class NoteEditor(val origin: TopLevelOrigin)

@Serializable
data object RestoreRecoveryRoute

/**
 * Accepts only canonical lowercase UUID text for detail record IDs. Every
 * detail route enforces it in its `init` block, so malformed, blank, or
 * non-canonical (uppercase) IDs fail during construction and during
 * deserialization/restoration, before navigation.
 */
internal fun requireCanonicalRecordId(raw: String): String {
    val canonical = java.util.UUID.fromString(raw).toString()
    require(raw == canonical) { "record route ID must be canonical lowercase UUID text" }
    return canonical
}
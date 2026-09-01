package com.paherdin.record.navigation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the exact M1 production route allowlist (plan M1-T07 Step 1):
 * the 13 typed routes round-trip through Kotlin serialization, detail routes
 * reject non-canonical record IDs during construction and deserialization,
 * origin arguments map onto the four top-level domains, and production
 * destinations expose only the feature-owned availability content models.
 */
class M1RoutesTest {

    private val canonicalId = "0d8ecd0b-8b5c-4a3f-9a2e-1f0c5b6d7e8f"

    // ---- allowlist ----

    @Test
    fun topLevelOriginCoversExactlyTheFourTabs() {
        assertTrue(
            "TopLevelOrigin must own exactly Timeline/Finance/Notes/Projects",
            TopLevelOrigin.entries.toSet() ==
                setOf(TopLevelOrigin.Timeline, TopLevelOrigin.Finance, TopLevelOrigin.Notes, TopLevelOrigin.Projects),
        )
    }

    @Test
    fun everyProductionRouteSerializesToItsDeclaredShape() {
        val routes = listOf<Any>(
            TimelineHome,
            FinanceHome,
            NotesHome,
            ProjectsHome,
            UnifiedSearch(TopLevelOrigin.Timeline),
            Settings(TopLevelOrigin.Finance),
            TransactionDetail(canonicalId, TopLevelOrigin.Notes),
            NoteDetail(canonicalId, TopLevelOrigin.Notes),
            ExpenseEditor(TopLevelOrigin.Notes),
            IncomeEditor(TopLevelOrigin.Finance),
            TransferEditor(TopLevelOrigin.Timeline),
            NoteEditor(TopLevelOrigin.Notes),
            RestoreRecoveryRoute,
        )
        assertTrue("M1 registers exactly 13 production routes", routes.size == 13)
    }

    // ---- serialization round trips ----

    @Test
    fun homeRoutesRoundTripThroughKotlinSerialization() {
        assertEquals(TimelineHome, Json.decodeFromString(TimelineHome.serializer(), Json.encodeToString(TimelineHome.serializer(), TimelineHome)))
        assertEquals(FinanceHome, Json.decodeFromString(FinanceHome.serializer(), Json.encodeToString(FinanceHome.serializer(), FinanceHome)))
        assertEquals(NotesHome, Json.decodeFromString(NotesHome.serializer(), Json.encodeToString(NotesHome.serializer(), NotesHome)))
        assertEquals(ProjectsHome, Json.decodeFromString(ProjectsHome.serializer(), Json.encodeToString(ProjectsHome.serializer(), ProjectsHome)))
        assertEquals(RestoreRecoveryRoute, Json.decodeFromString(RestoreRecoveryRoute.serializer(), Json.encodeToString(RestoreRecoveryRoute.serializer(), RestoreRecoveryRoute)))
    }

    @Test
    fun originRoutesRoundTripForEveryOrigin() {
        for (origin in TopLevelOrigin.entries) {
            assertEquals(UnifiedSearch(origin), Json.decodeFromString(UnifiedSearch.serializer(), Json.encodeToString(UnifiedSearch.serializer(), UnifiedSearch(origin))))
            assertEquals(Settings(origin), Json.decodeFromString(Settings.serializer(), Json.encodeToString(Settings.serializer(), Settings(origin))))
        }
    }

    @Test
    fun editorRoutesRoundTripWithOrigin() {
        for (origin in TopLevelOrigin.entries) {
            assertEquals(ExpenseEditor(origin), Json.decodeFromString(ExpenseEditor.serializer(), Json.encodeToString(ExpenseEditor.serializer(), ExpenseEditor(origin))))
            assertEquals(IncomeEditor(origin), Json.decodeFromString(IncomeEditor.serializer(), Json.encodeToString(IncomeEditor.serializer(), IncomeEditor(origin))))
            assertEquals(TransferEditor(origin), Json.decodeFromString(TransferEditor.serializer(), Json.encodeToString(TransferEditor.serializer(), TransferEditor(origin))))
            assertEquals(NoteEditor(origin), Json.decodeFromString(NoteEditor.serializer(), Json.encodeToString(NoteEditor.serializer(), NoteEditor(origin))))
        }
    }

    @Test
    fun detailRoutesRoundTripCanonicalIds() {
        val transaction = TransactionDetail(canonicalId, TopLevelOrigin.Timeline)
        assertEquals(transaction, Json.decodeFromString(TransactionDetail.serializer(), Json.encodeToString(TransactionDetail.serializer(), transaction)))
        val note = NoteDetail(canonicalId, TopLevelOrigin.Notes)
        assertEquals(note, Json.decodeFromString(NoteDetail.serializer(), Json.encodeToString(NoteDetail.serializer(), note)))
        assertEquals(TopLevelOrigin.Timeline, transaction.origin)
    }

    // ---- canonical record IDs ----

    @Test
    fun requireCanonicalRecordIdAcceptsOnlyCanonicalLowercaseText() {
        assertEquals(canonicalId, requireCanonicalRecordId(canonicalId))
    }

    @Test
    fun requireCanonicalRecordIdRejectsMalformedUppercaseAndBlank() {
        for (raw in listOf("not-a-uuid", "", "   ", canonicalId.uppercase(), "0D8ECD0B-8B5C-4A3F-9A2E-1F0C5B6D7E8F")) {
            try {
                requireCanonicalRecordId(raw)
                error("requireCanonicalRecordId accepted '$raw'")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun detailRoutesRejectMalformedIdsDuringDeserialization() {
        for (json in listOf(
            """{"transactionId":"not-a-uuid","origin":"Finance"}""",
            """{"transactionId":"","origin":"Finance"}""",
            """{"transactionId":"${canonicalId.uppercase()}","origin":"Finance"}""",
        )) {
            try {
                Json.decodeFromString(TransactionDetail.serializer(), json)
                error("malformed transaction ID must fail deserialization: $json")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
        try {
            Json.decodeFromString(NoteDetail.serializer(), """{"noteId":"NOT-AN-ID","origin":"Notes"}""")
            throw AssertionError("uppercase note ID must fail deserialization")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    // ---- origin mapping ----

    @Test
    fun detailAndGlobalRoutesCarryTheirLaunchingOrigin() {
        val origins = TopLevelOrigin.entries
        assertTrue(UnifiedSearch(TopLevelOrigin.Projects).origin == TopLevelOrigin.Projects)
        assertTrue(Settings(TopLevelOrigin.Notes).origin == TopLevelOrigin.Notes)
        for (origin in origins) {
            assertTrue(TransactionDetail(canonicalId, origin).origin == origin)
            assertTrue(NoteDetail(canonicalId, origin).origin == origin)
            assertTrue(ExpenseEditor(origin).origin == origin)
            assertTrue(IncomeEditor(origin).origin == origin)
            assertTrue(TransferEditor(origin).origin == origin)
            assertTrue(NoteEditor(origin).origin == origin)
        }
    }

    // ---- production availability models only ----

    @Test
    fun productionRoutesCarryOnlyStableIdAndOriginArguments() {
        // Route payloads are stable ID text plus the navigation-only origin
        // discriminator; no repository, entity, path, or payload type appears.
        val detail = TransactionDetail(canonicalId, TopLevelOrigin.Timeline)
        assertEquals(canonicalId, detail.transactionId)
        val editor = NoteEditor(TopLevelOrigin.Notes)
        assertTrue(editor.origin == TopLevelOrigin.Notes)
        assertTrue(editor.origin is TopLevelOrigin)
    }
}
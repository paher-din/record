package com.paherdin.record.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseTest {

    private val allowed = mapOf(
        Phase.Loading to setOf(Phase.Idle, Phase.Selecting, Phase.RecoverableFailure, Phase.BlockingFailure),
        Phase.Idle to setOf(Phase.Selecting, Phase.Checking, Phase.Validating, Phase.Saving, Phase.Running, Phase.Cancelled),
        Phase.Selecting to setOf(Phase.Idle, Phase.Checking, Phase.Validating, Phase.Running, Phase.Cancelled, Phase.RecoverableFailure, Phase.BlockingFailure),
        Phase.Checking to setOf(Phase.Idle, Phase.Selecting, Phase.Validating, Phase.Running, Phase.Cancelling, Phase.Completed, Phase.RecoverableFailure, Phase.BlockingFailure),
        Phase.Validating to setOf(Phase.Idle, Phase.Saving, Phase.Running, Phase.RecoverableFailure, Phase.BlockingFailure),
        Phase.Saving to setOf(Phase.Completed, Phase.RecoverableFailure, Phase.BlockingFailure),
        Phase.Running to setOf(Phase.Running, Phase.Idle, Phase.Cancelling, Phase.Completed, Phase.Recovering, Phase.RecoverableFailure, Phase.BlockingFailure),
        Phase.Cancelling to setOf(Phase.Cancelled, Phase.Completed, Phase.RecoverableFailure, Phase.BlockingFailure),
        Phase.Recovering to setOf(Phase.Completed, Phase.RecoverableFailure, Phase.BlockingFailure),
        Phase.RecoverableFailure to setOf(Phase.Loading, Phase.Idle, Phase.Selecting, Phase.Checking, Phase.Validating, Phase.Saving, Phase.Running, Phase.Recovering),
        Phase.BlockingFailure to setOf(Phase.Loading, Phase.Idle, Phase.Selecting, Phase.Checking, Phase.Validating, Phase.Saving, Phase.Running, Phase.Recovering),
        Phase.Cancelled to emptySet(),
        Phase.Completed to emptySet(),
    )

    @Test
    fun exactly_thirteen_phase_tokens() {
        assertEquals(
            listOf(
                "Loading", "Idle", "Selecting", "Checking", "Validating", "Saving", "Running",
                "Cancelling", "Cancelled", "Recovering", "RecoverableFailure", "BlockingFailure", "Completed",
            ),
            Phase.entries.map { it.name },
        )
        assertEquals(Phase.entries.toSet(), allowed.keys)
    }

    @Test
    fun transition_table_is_exhaustive_and_exact() {
        for (from in Phase.entries) {
            for (to in Phase.entries) {
                val expected = to in (allowed[from] ?: emptySet())
                assertEquals("from=$from to=$to", expected, PhaseTransitions.allows(from, to))
            }
        }
    }

    @Test
    fun startup_gate_uses_only_legal_transitions() {
        // ADR 0016 contract: Checking -> Running -> Recovering; no shortcut into Recovering.
        assertTrue(PhaseTransitions.allows(Phase.Checking, Phase.Running))
        assertTrue(PhaseTransitions.allows(Phase.Running, Phase.Recovering))
        assertFalse(PhaseTransitions.allows(Phase.Checking, Phase.Recovering))
    }

    @Test
    fun terminal_phases_have_no_next_phase() {
        assertTrue(allowed[Phase.Completed].orEmpty().isEmpty())
        assertTrue(allowed[Phase.Cancelled].orEmpty().isEmpty())
    }
}

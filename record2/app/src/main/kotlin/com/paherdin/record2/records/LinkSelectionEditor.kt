package com.paherdin.record2.records

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class LinkSelectionState(
    val notes: Map<String, NoteRecord>,
    val selected: Set<String>,
    val transactionVersion: Long,
    val busy: Boolean = false,
    val completed: Boolean = false,
    val closed: Boolean = false,
    val requiresReload: Boolean = false,
    val error: LinkError? = null,
)

// Non-visual native selection state; the final page and its interactions belong to F0/F1.
internal class LinkSelectionEditor(
    private val service: LinkService,
    private val generation: String,
    private val transactionId: String,
    initial: LinkSelection,
) {
    init { validate(initial) }

    private val mutableState = MutableStateFlow(
        LinkSelectionState(
            notes = (initial.linked + initial.candidates).associateBy { it.id },
            selected = initial.linked.map { it.id }.toSet(),
            transactionVersion = initial.transaction.version,
        ),
    )
    val state: StateFlow<LinkSelectionState> = mutableState.asStateFlow()

    private var baselineVersions: Map<String, Long> = (initial.linked + initial.candidates).associate { it.id to it.version }
    private var baselineSelected: Set<String> = initial.linked.map { it.id }.toSet()
    private var baselineTransactionVersion: Long = initial.transaction.version

    fun toggle(id: String): Boolean {
        while (true) {
            val old = state.value
            if (old.busy || old.completed || old.closed || old.requiresReload || id !in old.notes) return false
            val selected = if (id in old.selected) old.selected - id else old.selected + id
            if (mutableState.compareAndSet(old, old.copy(selected = selected))) return true
        }
    }

    suspend fun complete(): Boolean {
        while (true) {
            val old = state.value
            if (old.busy || old.completed || old.closed || old.requiresReload) return false
            if (mutableState.compareAndSet(old, old.copy(busy = true))) break
        }
        try {
            val current = state.value
            val add = (current.selected - baselineSelected).sorted().map { LinkNoteRef(it, baselineVersions.getValue(it)) }
            val remove = (baselineSelected - current.selected).sorted().map { LinkNoteRef(it, baselineVersions.getValue(it)) }
            val result = service.apply(generation, transactionId, baselineTransactionVersion, add, remove)
            if (result.generation != generation || result.transaction.id != transactionId) {
                throw LinkException(LinkError.RecoveryRequired)
            }
            val notes = result.notes.associateBy { it.id }
            val selected = notes.keys
            baselineVersions = notes.mapValues { it.value.version }
            baselineSelected = selected
            baselineTransactionVersion = result.transaction.version
            mutableState.update {
                it.copy(
                    notes = notes,
                    selected = selected,
                    transactionVersion = result.transaction.version,
                    completed = true,
                    requiresReload = false,
                    error = null,
                )
            }
            return true
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(error = LinkError.OutcomeUnknown, requiresReload = true) }
            throw cancelled
        } catch (error: LinkException) {
            mutableState.update { it.copy(error = error.code, requiresReload = true) }
            return false
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    fun cancel(): Boolean {
        while (true) {
            val old = state.value
            if (old.busy || old.closed) return false
            if (mutableState.compareAndSet(old, old.copy(closed = true))) return true
        }
    }

    suspend fun reloadDiscardingSelection(): Boolean {
        while (true) {
            val old = state.value
            if (old.busy || old.completed || old.closed) return false
            if (mutableState.compareAndSet(old, old.copy(busy = true))) break
        }
        try {
            val fresh = service.selection(generation, transactionId)
            validate(fresh)
            val notes = (fresh.linked + fresh.candidates).associateBy { it.id }
            baselineVersions = notes.mapValues { it.value.version }
            baselineSelected = fresh.linked.map { it.id }.toSet()
            baselineTransactionVersion = fresh.transaction.version
            mutableState.value = LinkSelectionState(
                notes = notes,
                selected = baselineSelected,
                transactionVersion = fresh.transaction.version,
                busy = true,
            )
            return true
        } catch (error: LinkException) {
            mutableState.update { it.copy(error = error.code, requiresReload = true) }
            return false
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    private fun validate(selection: LinkSelection) {
        val linkedIds = selection.linked.map { it.id }
        val candidateIds = selection.candidates.map { it.id }
        val invalid = selection.generation != generation ||
            selection.transaction.id != transactionId ||
            selection.transaction.version < 1 ||
            selection.linked.any { it.deletedAt != null || it.version < 1 } ||
            selection.candidates.any { it.deletedAt != null || it.version < 1 } ||
            linkedIds.size != linkedIds.toSet().size ||
            candidateIds.size != candidateIds.toSet().size ||
            linkedIds.any { it in candidateIds.toSet() }
        if (invalid) throw LinkException(LinkError.RecoveryRequired)
    }
}

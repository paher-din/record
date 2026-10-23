package com.paherdin.record2.records

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class FinanceEditorState(
    val persisted: FinanceDraft,
    val input: FinanceInput = persisted.input,
    val saving: Boolean = false,
    val saved: FinanceSaveReceipt? = null,
    val error: FinanceError? = null,
)

// Native form state only: UI appearance and navigation stay with F0/F1.
internal class FinanceDraftEditor(
    private val service: FinanceService,
    private val generation: String,
    initial: FinanceDraft,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(FinanceEditorState(initial))
    val state: StateFlow<FinanceEditorState> = mutableState.asStateFlow()
    private val writes = Mutex()
    private var debounce: Job? = null

    fun edit(input: FinanceInput): Boolean {
        while (true) {
            val previous = mutableState.value
            if (previous.saving || previous.saved != null) return false
            if (mutableState.compareAndSet(previous, previous.copy(input = input, error = null))) break
        }
        debounce?.cancel()
        debounce = scope.launch {
            delay(500)
            // A later keystroke cancels the timer, never an in-flight database write.
            scope.launch { flush() }
        }
        return true
    }

    suspend fun flush(): Boolean = writes.withLock { persistLatest() }

    suspend fun leave(): Boolean {
        if (state.value.saving) return false
        return flush() && !state.value.saving
    }

    suspend fun save(): FinanceSaveReceipt? {
        while (true) {
            val previous = mutableState.value
            previous.saved?.let { return it }
            if (previous.saving) return null
            if (mutableState.compareAndSet(previous, previous.copy(saving = true, error = null))) break
        }
        debounce?.cancel()
        try {
            return writes.withLock {
                if (!persistLatest()) return@withLock null
                val draft = state.value.persisted
                val receipt = service.saveDraft(generation, draft.id, draft.revision)
                mutableState.update { it.copy(saved = receipt, error = null) }
                receipt
            }
        } catch (error: FinanceException) {
            mutableState.update { it.copy(error = error.code) }
            return null
        } finally {
            mutableState.update { it.copy(saving = false) }
        }
    }

    private suspend fun persistLatest(): Boolean {
        while (true) {
            val pending = state.value
            if (pending.saved != null || pending.input == pending.persisted.input) return true
            try {
                val persisted = service.updateDraft(generation, pending.persisted.id, pending.persisted.revision, pending.input)
                mutableState.update { it.copy(persisted = persisted, error = null) }
            } catch (error: FinanceException) {
                mutableState.update { it.copy(error = error.code) }
                return false
            }
        }
    }
}

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

internal data class NoteEditorState(
    val body: String,
    val persisted: NoteRecord?,
    val inputRevision: Long = 0,
    val persistedInputRevision: Long = 0,
    val writing: Boolean = false,
    val finishing: Boolean = false,
    val reloading: Boolean = false,
    val closed: Boolean = false,
    val error: NoteError? = null,
) {
    val isSaved: Boolean get() = persisted != null && persisted.deletedAt == null &&
        inputRevision == persistedInputRevision && body == persisted.body && !writing && error == null
}

// Native editor state only; proposal previews never instantiate this autosave owner.
internal class NoteEditor(
    private val service: NoteService,
    private val generation: String,
    val id: String,
    private val occurredAt: Long,
    initial: NoteRecord?,
    private val scope: CoroutineScope,
) {
    init { require(initial == null || (initial.id == id && initial.occurredAt == occurredAt && initial.deletedAt == null)) }
    private val mutableState = MutableStateFlow(NoteEditorState(initial?.body.orEmpty(), initial))
    val state: StateFlow<NoteEditorState> = mutableState.asStateFlow()
    private val writes = Mutex()
    private var debounce: Job? = null
    private val blocked = setOf(NoteError.VersionConflict, NoteError.NotVisible, NoteError.OutcomeUnknown, NoteError.GenerationMismatch, NoteError.RecoveryRequired, NoteError.NotFound)

    fun edit(body: String): Boolean {
        while (true) {
            val old = state.value
            if (old.closed || old.finishing || old.reloading || old.persisted?.deletedAt != null) return false
            val next = old.copy(body = body, inputRevision = Math.addExact(old.inputRevision, 1), error = old.error?.takeIf { it in blocked })
            if (mutableState.compareAndSet(old, next)) break
        }
        debounce?.cancel()
        debounce = scope.launch {
            delay(500)
            // Cancel keystroke timers, not a save whose disk outcome may be unknown.
            scope.launch { flush() }
        }
        return true
    }

    suspend fun flush(): Boolean {
        if (state.value.reloading) return false
        return writes.withLock { persistLatest() }
    }

    private suspend fun persistLatest(): Boolean {
        while (true) {
            val pending = state.value
            if (pending.closed) return true
            if (pending.reloading) return false
            if (pending.error in blocked) return false
            if (pending.persisted == null && pending.body.isBlank()) return true
            if (pending.persisted?.body == pending.body) {
                mutableState.update { it.copy(persistedInputRevision = pending.inputRevision, error = null) }
                if (state.value.inputRevision == pending.inputRevision) return true
                continue
            }
            mutableState.update { it.copy(writing = true) }
            try {
                val saved = service.save(generation, id, pending.persisted?.version, pending.body, occurredAt)
                mutableState.update { it.copy(persisted = saved, persistedInputRevision = pending.inputRevision, error = null) }
            } catch (error: NoteException) {
                mutableState.update { it.copy(error = error.code) }
                return false
            } finally {
                mutableState.update { it.copy(writing = false) }
            }
        }
    }

    suspend fun leave(): Boolean {
        while (true) {
            val old = state.value
            if (old.closed) return true
            if (old.finishing || old.reloading) return false
            if (mutableState.compareAndSet(old, old.copy(finishing = true))) break
        }
        debounce?.cancel()
        try {
            val saved = flush()
            if (saved) mutableState.update { it.copy(closed = true) }
            return saved
        } finally { mutableState.update { it.copy(finishing = false) } }
    }

    suspend fun discardAndLeave() {
        debounce?.cancel()
        mutableState.update { it.copy(closed = true) }
        writes.withLock {
            mutableState.update { it.copy(body = it.persisted?.body.orEmpty(), inputRevision = it.persistedInputRevision, error = null) }
        }
    }

    suspend fun reloadDiscardingInput(): Boolean {
        while (true) {
            val old = state.value
            if (old.closed || old.finishing || old.reloading) return false
            if (mutableState.compareAndSet(old, old.copy(reloading = true))) break
        }
        debounce?.cancel()
        try {
            return writes.withLock {
                if (state.value.closed) return@withLock false
                try {
                    val record = service.visible(generation, id)
                    mutableState.update { it.copy(body = record.body, persisted = record, persistedInputRevision = it.inputRevision, error = null) }
                    true
                } catch (error: NoteException) {
                    mutableState.update { it.copy(error = error.code) }
                    false
                }
            }
        } finally { mutableState.update { it.copy(reloading = false) } }
    }
}

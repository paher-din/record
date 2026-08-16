package com.paherdin.record.core.data.restore

/**
 * Named fault boundaries of restore activation (spec 4.6 `RESTORE_ACTIVATION`,
 * plan Task 8). No-op in production.
 */
internal interface RestoreFaultHook {
    fun beforeCloseOldBinding() = Unit
    fun afterCloseOldBinding() = Unit
    fun beforePendingWrite() = Unit
    fun afterPendingWrite() = Unit
    fun beforeRevalidate() = Unit
    fun afterRevalidate() = Unit
    fun beforeHealthyWrite() = Unit
    fun afterHealthyWrite() = Unit
    fun beforeRollbackWrite() = Unit
    fun afterRollbackWrite() = Unit
    fun beforeRetireDelete() = Unit
    fun afterRetireDelete() = Unit
    fun beforeRetirePointerClear() = Unit
    fun afterRetirePointerClear() = Unit

    companion object {
        val NO_OP: RestoreFaultHook = object : RestoreFaultHook {}
    }
}

package com.paherdin.record.core.data

/**
 * Simulated-fault injection stages. The repository calls each stage and
 * attributes hook-originated exceptions as simulated faults (boundary mapping
 * clause 4). Production default is the no-op; tests inject throwing
 * implementations at chosen stages/ordinals.
 */
internal interface TransactionFaultHook {
    /** Fired before `withTransaction` is called. */
    fun beforeTransaction() {}

    /** Fired after each logical child write, in order. */
    fun afterChildWrite(childIndex: Int) {}

    /** Fired as the last statement inside the transaction. */
    fun beforeCommit() {}

    /** Fired after `withTransaction` returns; the commit is already durable. */
    fun afterCommitResponse() {}

    companion object {
        val NO_OP: TransactionFaultHook = object : TransactionFaultHook {}
    }
}

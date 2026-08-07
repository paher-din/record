package consumer

import com.paherdin.record.core.data.FinanceViolationException

/**
 * Negative fixture: the internal finance exception type must not be reachable
 * from an external consumer.
 */
class ReferenceFinanceViolationException {
    fun use() {
        FinanceViolationException(com.paherdin.record.core.data.FinanceViolation.ZERO_AMOUNT)
    }
}

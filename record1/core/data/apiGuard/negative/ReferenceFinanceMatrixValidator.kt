package consumer

import com.paherdin.record.core.data.FinanceMatrixValidator

/**
 * Negative fixture: the finance matrix validator is module-internal and must
 * not be reachable from an external consumer.
 */
class ReferenceFinanceMatrixValidator {
    fun use() {
        FinanceMatrixValidator()
    }
}

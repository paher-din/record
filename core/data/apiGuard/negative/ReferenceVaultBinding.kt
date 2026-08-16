package consumer

/**
 * Negative fixture (P5, criterion 12c): the generation binding and session
 * registry are module-internal; a consumer cannot construct a binding or
 * bypass the startup gate.
 */
class ReferenceVaultBinding {
    fun use() {
        val binding = com.paherdin.record.core.data.restore.VaultBinding
        val registry = com.paherdin.record.core.data.restore.VaultSessionRegistry
    }
}

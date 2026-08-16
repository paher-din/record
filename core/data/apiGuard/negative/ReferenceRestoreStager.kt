package consumer

/**
 * Negative fixture (P5, criterion 12c): the staging reader and canonical
 * receipt are module-internal; a consumer cannot stage a restore or forge a
 * handle outside the typed surface.
 */
class ReferenceRestoreStager {
    fun use() {
        val stager = com.paherdin.record.core.data.restore.RestoreStager
        val receipt = com.paherdin.record.core.data.restore.StagingReceipt
        val coordinator = com.paherdin.record.core.data.restore.RestoreActivationCoordinator
    }
}

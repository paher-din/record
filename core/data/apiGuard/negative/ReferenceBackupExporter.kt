package consumer

/**
 * Negative fixture (P5, criterion 12c): the export pipeline and logical
 * schema are module-internal; a consumer cannot publish a backup outside the
 * typed export surface.
 */
class ReferenceBackupExporter {
    fun use() {
        val exporter = com.paherdin.record.core.data.backup.BackupExporter
        val schema = com.paherdin.record.core.data.backup.LogicalBackupSchema
    }
}

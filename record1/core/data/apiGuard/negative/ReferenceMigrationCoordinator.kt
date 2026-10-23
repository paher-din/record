package consumer

/**
 * Negative fixture (P3, criterion 9c): the migration coordinator and its
 * recovery record are module-internal; a consumer cannot bypass the startup
 * gate.
 */
class ReferenceMigrationCoordinator {
    fun use() {
        val coordinator = com.paherdin.record.core.data.MigrationCoordinator
    }
}

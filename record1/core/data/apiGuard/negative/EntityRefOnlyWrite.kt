package consumer

class EntityRefOnlyWrite {
    fun write(repo: com.paherdin.record.core.data.RecordRepository) {
        repo.saveEntityRef("id", com.paherdin.record.model.EntityType.TRANSACTION)
    }
}

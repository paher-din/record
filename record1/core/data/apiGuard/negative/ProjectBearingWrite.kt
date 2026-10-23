package consumer

class ProjectBearingWrite {
    fun write(repo: com.paherdin.record.core.data.RecordRepository) {
        repo.saveEntity(com.paherdin.record.model.EntityType.PROJECT)
    }
}

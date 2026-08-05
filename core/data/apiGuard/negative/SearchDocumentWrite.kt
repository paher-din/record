package consumer

class SearchDocumentWrite {
    fun write(repo: com.paherdin.record.core.data.RecordRepository) {
        repo.writeSearchDocument("id", "body")
        val doc: SearchDocument? = null
        check(doc == null)
    }
}

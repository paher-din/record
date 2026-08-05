package consumer

class TimelineWrite {
    fun write(repo: com.paherdin.record.core.data.RecordRepository) {
        repo.writeTimelineEntry("id")
        val entry: Timeline? = null
        check(entry == null)
    }
}

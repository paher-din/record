package consumer

/**
 * Negative fixture (P3, criterion 9c): no path can write attachment state
 * bypassing the typed surface — the Room entity is module-internal.
 */
class ReferenceAttachmentEntity {
    fun use() {
        val row = com.paherdin.record.core.data.room.Attachment("id", "sha", null)
    }
}

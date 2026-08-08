package consumer

/**
 * Negative fixture (P3, criterion 9c): the attachment pipeline internals are
 * unreachable from an external consumer — compiling this file must fail.
 */
class ReferenceAttachmentCleaner {
    fun use() {
        val cleaner = com.paherdin.record.core.data.AttachmentCleaner
    }
}

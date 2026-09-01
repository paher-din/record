package consumer

/**
 * Negative fixture (plan M1-T03 Step 4): the startup engine and every
 * persistence/recovery fact stay module-internal; a consumer can only reach
 * the narrow `RecordStartup` surface.
 */
class ReferenceStartupEngine {
    fun use() {
        val engine = com.paherdin.record.core.data.StartupEngine
    }
}

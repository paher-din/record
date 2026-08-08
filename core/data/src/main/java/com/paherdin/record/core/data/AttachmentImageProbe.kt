package com.paherdin.record.core.data

import android.graphics.BitmapFactory

/**
 * The Android bounds probe (plan Task 5): decodes only the image bounds via
 * `inJustDecodeBounds`, feeding the pure `AttachmentValidator` the decoded
 * MIME type and decodability. No pixel decode ever runs here. Exercised by
 * device tests; the pure classifier has no Android runtime dependency.
 */
internal object AttachmentImageProbe {

    fun probe(file: java.io.File): AttachmentValidator.Probe {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val decodedMime = options.outMimeType
        val decodable = options.outWidth > 0 && options.outHeight > 0 && decodedMime != null
        return AttachmentValidator.Probe(decodable, decodedMime)
    }
}

package com.paherdin.record.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure validation matrix of the attachment classifier (plan Task 2, spec
 * criterion 2): normalized declared/decoded MIME, the 32 MiB PoC size limit,
 * decodability, the deterministic precedence for multi-invalid candidates,
 * and SHA-256 correctness. No Android runtime dependency.
 */
class AttachmentValidatorTest {

    private val v = AttachmentValidator

    // ---- MIME normalization (plan: trim, drop after ';', lowercase(Locale.ROOT)) ----

    @Test
    fun normalizeMimeTrimsAndLowercases() {
        assertEquals("image/jpeg", v.normalizeMime("  IMAGE/JPEG  "))
    }

    @Test
    fun normalizeMimeDropsParametersAfterSemicolon() {
        assertEquals("image/png", v.normalizeMime("image/png; charset=binary"))
    }

    @Test
    fun normalizeMimeKeepsUnknownTypeAsIs() {
        assertEquals("image/heic", v.normalizeMime("image/heic"))
    }

    // ---- size boundary (32 MiB exactly accepted, one byte over rejected) ----

    @Test
    fun sizeExactlyAtLimitIsAccepted() {
        val result = v.classify("image/jpeg", 32L * 1024 * 1024, AttachmentValidator.Probe(true, "image/jpeg"))
        assertTrue(result is Accepted)
    }

    @Test
    fun sizeOneByteOverLimitIsRejected() {
        val result = v.classify("image/jpeg", 32L * 1024 * 1024 + 1, AttachmentValidator.Probe(true, "image/jpeg"))
        assertEquals(AttachmentViolation.SIZE_LIMIT_EXCEEDED, (result as Rejected).violation)
    }

    // ---- deterministic precedence (first match wins) ----

    @Test
    fun declaredMimeOutsideSetBeatsAllOtherInvalidity() {
        // Wrong declared MIME, over size, undecodable: declared MIME wins the precedence.
        val result = v.classify("image/heic", Long.MAX_VALUE, AttachmentValidator.Probe(false, null))
        assertEquals(AttachmentViolation.MIME_TYPE_NOT_ACCEPTED, (result as Rejected).violation)
    }

    @Test
    fun sizeLimitBeatsDecodabilityFailure() {
        val result = v.classify("image/jpeg", 33L * 1024 * 1024, AttachmentValidator.Probe(false, null))
        assertEquals(AttachmentViolation.SIZE_LIMIT_EXCEEDED, (result as Rejected).violation)
    }

    @Test
    fun undecodableIsRejectedAfterSizeCheck() {
        val result = v.classify("image/jpeg", 10L, AttachmentValidator.Probe(false, null))
        assertEquals(AttachmentViolation.UNDECODABLE, (result as Rejected).violation)
    }

    @Test
    fun decodedMimeOutsideSetIsRejected() {
        val result = v.classify("image/jpeg", 10L, AttachmentValidator.Probe(true, "application/pdf"))
        assertEquals(AttachmentViolation.MIME_TYPE_NOT_ACCEPTED, (result as Rejected).violation)
    }

    @Test
    fun decodedMimeDifferentFromDeclaredIsRejected() {
        val result = v.classify("image/jpeg", 10L, AttachmentValidator.Probe(true, "image/png"))
        assertEquals(AttachmentViolation.MIME_TYPE_NOT_ACCEPTED, (result as Rejected).violation)
    }

    @Test
    fun validImageInSetIsAccepted() {
        for (mime in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) {
            val result = v.classify(mime, 10L, AttachmentValidator.Probe(true, mime))
            assertTrue(result is Accepted)
        }
    }

    // ---- SHA-256 (never trusts a caller-supplied hash) ----

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            v.sha256(byteArrayOf()),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            v.sha256("abc".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun sha256IsDeterministicAndSensitiveToAnyByte() {
        val a = v.sha256("same".toByteArray())
        val b = v.sha256("same".toByteArray())
        val c = v.sha256("samf".toByteArray())
        assertEquals(a, b)
        assertTrue(a != c)
    }
}

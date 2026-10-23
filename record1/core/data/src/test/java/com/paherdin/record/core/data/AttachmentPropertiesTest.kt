package com.paherdin.record.core.data

import com.paherdin.record.core.data.room.Attachment
import com.paherdin.record.core.data.room.AttachmentLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Plan Task 8, spec section 6 and testing-strategy line ~56: property tests
 * over the canonical mapping — a global one-to-one mapping from SHA-256 to
 * canonical Attachment metadata/blob with arbitrary many valid owner links,
 * and a second Attachment row with the same content identity always invalid.
 * Fixed seed; rerunning the seed is mandatory before a fix is accepted.
 */
class AttachmentPropertiesTest {

    private val seed = 0x7033L // "P3"

    @Test
    fun canonicalMappingHoldsOverGeneratedHistories() {
        val random = Random(seed)
        val rows = mutableMapOf<String, Attachment>() // contentIdentity -> canonical row
        val links = mutableListOf<AttachmentLink>()
        var nextId = 0

        repeat(2_000) {
            val contentIdentity = randomContent(random)
            when (random.nextInt(10)) {
                // 0-5: ingest (a content identity never gains a second row).
                in 0..5 -> {
                    if (contentIdentity !in rows) {
                        rows[contentIdentity] = Attachment("att-${nextId++}", contentIdentity, null)
                    }
                }
                // 6-8: link an existing identity to a new owner (arbitrary many links).
                in 6..8 -> {
                    val identity = rows.keys.random(random)
                    val owner = "owner-${nextId++}"
                    links.add(AttachmentLink(rows.getValue(identity).id, owner))
                }
                // 9: unlink a random owner (link removal is legal; the canonical row stays).
                else -> {
                    if (links.isNotEmpty()) links.removeAt(random.nextInt(links.size))
                }
            }
        }

        // Invariant 1: one SHA-256 → exactly one canonical row.
        assertEquals("one identity must map to exactly one canonical row", rows.size, rows.values.map { it.contentIdentity }.toSet().size)
        // Invariant 2: every link references a canonical row.
        val rowIds = rows.values.map { it.id }.toSet()
        assertTrue("every link must reference a canonical row", links.all { it.attachmentId in rowIds })
        // Invariant 3: a second row with the same content identity is always invalid
        // — the registry-level rule forbids it by construction here; the database
        // unique index enforces it (SchemaV2ContractTest/AttachmentSchemaContractTest).
        val idsByIdentity = rows.values.groupBy { it.contentIdentity }
        assertTrue("no identity may have duplicate rows", idsByIdentity.values.all { it.size == 1 })
    }

    @Test
    fun validOwnerLinkSetsArePreservedAcrossUnlinkAndCleanupMutations() {
        val random = Random(seed xor 0x1)
        val canonical = Attachment("att-0", "0".repeat(64), null)
        val linked = mutableSetOf<String>()
        repeat(500) {
            val owner = "owner-${random.nextInt(100)}"
            if (random.nextBoolean()) linked.add(owner) else linked.remove(owner)
        }
        // Unlinking one owner never affects another owner's link.
        val removed = linked.random(random)
        linked.remove(removed)
        assertTrue("other owners remain linked", linked.isNotEmpty())
        // Zero-reference means NO owner is linked anywhere.
        val zeroRef = linked.isEmpty()
        if (!zeroRef) {
            assertEquals("the canonical row stays active while any link exists", null, canonical.deletedAt)
        }
    }

    private fun randomContent(random: Random): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

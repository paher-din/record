package com.paherdin.record.core.data

import com.paherdin.record.core.data.backup.LogicalBackupSchema
import com.paherdin.record.core.data.backup.LogicalType
import com.paherdin.record.core.data.room.Account
import com.paherdin.record.core.data.room.AccountEntry
import com.paherdin.record.core.data.room.Attachment
import com.paherdin.record.core.data.room.AttachmentLink
import com.paherdin.record.core.data.room.EntityRef
import com.paherdin.record.core.data.room.Note
import com.paherdin.record.core.data.room.Transaction
import com.paherdin.record.core.data.room.TransactionNoteRelation
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogicalBackupSchemaTest {

    private fun <T : Any> roundTrip(type: LogicalType, record: T): Any = LogicalBackupSchema.decode(type, LogicalBackupSchema.encode(type, record))

    @Test
    fun entityRefRoundTrip() {
        val e = EntityRef("id-1", EntityType.TRANSACTION, 1, 2, null)
        assertEquals(e, roundTrip(LogicalType.EntityRef, e))
    }

    @Test
    fun entityRefDeletedAtAbsentWhenNull() {
        val e = EntityRef("id-1", EntityType.NOTE, 1, 2, null)
        val json = LogicalBackupSchema.encode(LogicalType.EntityRef, e)
        assertFalse(json.contains("deletedAt"))
    }

    @Test
    fun entityRefDeletedAtPresentWhenSet() {
        val e = EntityRef("id-1", EntityType.PROJECT, 1, 2, 99)
        assertEquals(e, roundTrip(LogicalType.EntityRef, e))
        assert(LogicalBackupSchema.encode(LogicalType.EntityRef, e).contains("\"deletedAt\":99"))
    }

    @Test
    fun accountRoundTrip() {
        val a = Account("acct-1", "现金", "CNY")
        assertEquals(a, roundTrip(LogicalType.Account, a))
    }

    @Test
    fun transactionUsesVerbatimOpeningBalance() {
        val t = Transaction("tx-1", TransactionType.OPENING_BALANCE)
        val json = LogicalBackupSchema.encode(LogicalType.Transaction, t)
        assert(json.contains("\"type\":\"OPENING_BALANCE\""))
        assertEquals(t, roundTrip(LogicalType.Transaction, t))
    }

    @Test
    fun transactionAllTypesRoundTrip() {
        for (type in TransactionType.entries) {
            val t = Transaction("tx-$type", type)
            assertEquals(t, roundTrip(LogicalType.Transaction, t))
        }
    }

    @Test
    fun accountEntryRoundTrip() {
        val e = AccountEntry("e-1", "tx-1", "acct-1", -12345)
        assertEquals(e, roundTrip(LogicalType.AccountEntry, e))
    }

    @Test
    fun noteRoundTrip() {
        assertEquals(Note("n-1"), roundTrip(LogicalType.Note, Note("n-1")))
    }

    @Test
    fun relationRoundTrip() {
        val r = TransactionNoteRelation("tx-1", "n-1")
        assertEquals(r, roundTrip(LogicalType.TransactionNoteAssociation, r))
    }

    @Test
    fun attachmentRoundTripWithDeletedAt() {
        val a = Attachment("att-1", "sha", 55)
        assertEquals(a, roundTrip(LogicalType.Attachment, a))
    }

    @Test
    fun attachmentDeletedAtAbsentWhenActive() {
        val a = Attachment("att-1", "sha", null)
        assertFalse(LogicalBackupSchema.encode(LogicalType.Attachment, a).contains("deletedAt"))
    }

    @Test
    fun attachmentLinkRoundTrip() {
        val l = AttachmentLink("att-1", "tx-1")
        assertEquals(l, roundTrip(LogicalType.AttachmentLink, l))
    }

    @Test(expected = JsonParseException::class)
    fun unknownTransactionTypeIsRejected() {
        LogicalBackupSchema.decode(LogicalType.Transaction, """{"id":"t","type":"OPENING"}""")
    }

    @Test(expected = JsonParseException::class)
    fun unknownEntityTypeIsRejected() {
        LogicalBackupSchema.decode(LogicalType.EntityRef, """{"entityType":"BOGUS","id":"t","createdAt":1,"updatedAt":2}""")
    }

    @Test(expected = JsonParseException::class)
    fun unknownFieldIsRejected() {
        LogicalBackupSchema.decode(LogicalType.Note, """{"id":"n","extra":1}""")
    }
}

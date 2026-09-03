package com.paherdin.record.core.data.startup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import androidx.room.withTransaction
import com.paherdin.record.core.data.FinanceMatrixValidator
import com.paherdin.record.core.data.FinanceViolationException
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.XorInvariantValidator
import com.paherdin.record.core.data.room.Account
import com.paherdin.record.core.data.room.AccountEntry
import com.paherdin.record.core.data.room.Attachment
import com.paherdin.record.core.data.room.AttachmentLink
import com.paherdin.record.core.data.room.EntityRef
import com.paherdin.record.core.data.room.Note
import com.paherdin.record.core.data.room.Transaction
import com.paherdin.record.core.data.room.TransactionNoteRelation
import com.paherdin.record.core.data.restore.GenerationValidation
import com.paherdin.record.core.data.restore.StagedGenerationValidator
import com.paherdin.record.core.data.restore.openGenerationDatabase
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Adler32
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking

/**
 * The `m1-startup-v1` benchmark fixture generator (plan M1-T09, spec 11.2).
 *
 * Pure, deterministic core. `plan(fixture)` derives every fixture from a fixed
 * seed, one fixed epoch-millis clock, and deterministic `nameUUIDFromBytes`
 * IDs: the exact spec 11.2 cardinalities, the exact canonical v2
 * activation-record JSON, the synthetic attachment bytes, and a content digest
 * over the canonical serialization. No Android API is touched on this path, so
 * `M1StartupFixtureGeneratorTest` proves the counts, hashes, JSON shapes, and
 * the pre-publish integrity contract (finance matrix, XOR identity, relation
 * endpoints, attachment blob bytes) on the host JVM.
 *
 * `prepare(context, fixture)` is the device-side application step used only by
 * the benchmark-only `M1StartupFixtureActivity`: it resets the benchmark
 * application's own private generation/recovery root, writes the generation
 * databases through the production Room schema, writes the exact activation
 * record, builds the valid published snapshot for pending-migration fixtures,
 * validates every generation with the production `StagedGenerationValidator`,
 * and reports the on-device hashes. It emits only inside that app-private
 * root and never touches another package's data.
 *
 * Fixture states (spec 11.2) and their exact cardinalities:
 *  - `Fresh`: no legacy data and no recovery record; the real P5 protocol
 *    creates one healthy initial generation at the next startup.
 *  - `Typical`: 2 accounts; 5 transactions (2 opening, 1 expense, 1 income,
 *    1 transfer); 6 AccountEntries; 2 notes; 2 typed relations; 1 canonical
 *    attachment blob linked to both valid owners; healthy v2 record.
 *  - `HighVolume`: 10 accounts; 6,000 transactions (frozen P6 mix: 10 opening,
 *    3,000 expense, 1,800 income, 1,190 same-currency transfer); 7,190
 *    AccountEntries; 4,000 notes; 2,000 typed relations; 100 canonical 64-KiB
 *    synthetic image blobs with valid owner links; healthy v2 record.
 *  - `PendingActivation`: the typical dataset staged twice (a complete
 *    original plus a fully staged, incomplete-marked candidate) with an exact
 *    durable `PENDING` activation record at the pre-resolution boundary.
 *  - `PendingMigration<Stage>`: one fixture per current `MigrationStage`
 *    value, each carrying a valid published snapshot and an exact
 *    `MIGRATION_RECOVERY_PENDING` v2 record whose payload names that stage.
 *    The M1 startup gate fails closed on every one of these stages (the
 *    owning data-and-storage state machine's four resumable stage values), so
 *    each binding renders the data-free blocked recovery gate, never a shell.
 */
object M1StartupFixtureGenerator {

    /** Frozen fixture contract version (spec 11.2). */
    const val FIXTURE_VERSION = "m1-startup-v1"

    /** Fixed generator seed; no randomness anywhere in generation. */
    const val SEED = 2026090300L

    /** Fixed UTC epoch-millis clock value (2026-01-01T00:00:00Z). */
    const val FIXED_EPOCH_MILLIS = 1_767_225_600_000L

    /**
     * A pending-migration record carries this deterministic placeholder at the
     * one on-device value (the built snapshot file's SHA-256); `prepare`
     * substitutes the computed digest before the record is written.
     */
    val SNAPSHOT_SHA256_PLACEHOLDER: String = "__M1_SNAPSHOT_SHA256__".padEnd(64, '-')

    /** The exact 64-KiB target size of every synthetic attachment blob. */
    const val IMAGE_TARGET_BYTES = 65_536

    /** The app-data-root-relative roots one fixture reset owns. */
    private val FIXTURE_ROOTS = listOf(
        "files/vaults",
        "files/recovery",
        "files/migration",
        "files/attachments",
        "databases",
    )

    private const val TYPICAL_ACCOUNTS = 2
    private const val TYPICAL_TRANSACTIONS = 5
    private const val TYPICAL_NOTES = 2
    private const val HIGH_VOLUME_ACCOUNTS = 10
    private const val HIGH_VOLUME_TRANSACTIONS = 6_000
    private const val HIGH_VOLUME_OPENING = 10
    private const val HIGH_VOLUME_EXPENSE = 3_000
    private const val HIGH_VOLUME_INCOME = 1_800
    private const val HIGH_VOLUME_NOTES = 4_000
    private const val HIGH_VOLUME_RELATIONS = 2_000
    private const val HIGH_VOLUME_ATTACHMENTS = 100

    // ------------------------------------------------------------------
    // Pure plan
    // ------------------------------------------------------------------

    /** Derives the complete deterministic plan of one fixture. */
    fun plan(fixture: M1StartupFixture): M1StartupFixturePlan = when (fixture) {
        M1StartupFixture.Fresh -> freshPlan(fixture)
        M1StartupFixture.Typical -> dataPlan(fixture)
        M1StartupFixture.HighVolume -> dataPlan(fixture)
        M1StartupFixture.PendingActivation -> pendingActivationPlan(fixture)
        M1StartupFixture.PendingMigrationPrepared ->
            pendingMigrationPlan(fixture, "PREPARED")
        M1StartupFixture.PendingMigrationFailedSetIsolated ->
            pendingMigrationPlan(fixture, "FAILED_SET_ISOLATED")
        M1StartupFixture.PendingMigrationRestoreCopyValidated ->
            pendingMigrationPlan(fixture, "RESTORE_COPY_VALIDATED")
        M1StartupFixture.PendingMigrationMainReplaced ->
            pendingMigrationPlan(fixture, "MAIN_REPLACED")
    }

    /**
     * Pure pre-publish integrity gate (plan Step 1): finance matrix, XOR
     * identity, relation/attachment endpoints, and attachment blob bytes.
     * An empty list is the only accepted state before a fixture is published.
     */
    fun validatePlan(plan: M1StartupFixturePlan): List<String> {
        val problems = mutableListOf<String>()
        val records = plan.records
        val currencyByAccount = records.accounts.associate { it.id to it.currency }
        val accountIds = records.accounts.map { it.id }.toSet()
        val txIds = records.transactions.map { it.id }.toSet()
        val noteIds = records.notes.map { it.id }.toSet()
        val refIds = records.entityRefs.map { it.id }.toSet()
        val attachmentIds = records.attachments.map { it.id }.toSet()

        // Finance matrix per transaction.
        for (tx in records.transactions) {
            val txEntries = records.entries.filter { it.transactionId == tx.id }
                .map { NewAccountEntry(it.id, it.accountId, it.amountMinor) }
            try {
                FinanceMatrixValidator().validate(tx.type, txEntries, currencyByAccount)
            } catch (e: FinanceViolationException) {
                problems += "finance: transaction ${tx.id}: ${e.violation}"
            }
        }

        // XOR identity: the EntityRef type matches its exactly-one subtype row.
        for (ref in records.entityRefs) {
            try {
                XorInvariantValidator.validate(
                    entityType = ref.entityType,
                    candidateTransaction = ref.id in txIds,
                    candidateNote = ref.id in noteIds,
                )
            } catch (e: IllegalStateException) {
                problems += "xor: EntityRef ${ref.id} (${ref.entityType}): ${e.message}"
            }
        }

        // Entry/relation/attachment endpoints.
        for (entry in records.entries) {
            if (entry.transactionId !in txIds) problems += "endpoint: entry ${entry.id} -> missing transaction"
            if (entry.accountId !in accountIds) problems += "endpoint: entry ${entry.id} -> missing account"
        }
        for (relation in records.relations) {
            if (relation.transactionId !in txIds) problems += "endpoint: relation -> missing transaction"
            if (relation.noteId !in noteIds) problems += "endpoint: relation -> missing note"
        }
        for (link in records.links) {
            if (link.attachmentId !in attachmentIds) problems += "attachment: link -> missing attachment"
            val ownerType = records.entityRefs.firstOrNull { it.id == link.ownerEntityRefId }?.entityType
            if (ownerType == null) {
                problems += "attachment: link -> missing owner EntityRef ${link.ownerEntityRefId}"
            } else if (ownerType != EntityType.TRANSACTION && ownerType != EntityType.NOTE) {
                problems += "attachment: owner ${link.ownerEntityRefId} is not Transaction/Note"
            }
        }

        // Attachment identities and blob bytes.
        val identities = records.attachments.map { it.contentIdentity }
        if (identities.toSet().size != identities.size) problems += "attachment: duplicate contentIdentity rows"
        for (attachment in records.attachments) {
            val blob = plan.blobs[attachment.contentIdentity]
            if (blob == null) {
                problems += "attachment: blob missing for ${attachment.contentIdentity}"
            } else if (attachment.contentIdentity != sha256(blob)) {
                problems += "attachment: blob hash mismatch for ${attachment.contentIdentity}"
            }
        }
        for ((identity, bytes) in plan.blobs) {
            if (identity != sha256(bytes)) {
                problems += "attachment: blob bytes do not hash to their contentIdentity"
            }
        }
        if (refIds.size != records.entityRefs.size) problems += "duplicate EntityRef id"
        return problems
    }

    // ------------------------------------------------------------------
    // Plan builders
    // ------------------------------------------------------------------

    private fun freshPlan(fixture: M1StartupFixture): M1StartupFixturePlan = M1StartupFixturePlan(
        fixture = fixture,
        fixtureVersion = FIXTURE_VERSION,
        activeGenerationId = null,
        previousGenerationId = null,
        candidateGenerationId = null,
        generationIds = emptyList(),
        records = M1FixtureRecords(
            accounts = emptyList(), entityRefs = emptyList(), transactions = emptyList(),
            entries = emptyList(), notes = emptyList(), relations = emptyList(),
            attachments = emptyList(), links = emptyList(),
        ),
        blobs = emptyMap(),
        snapshot = null,
        activationRecordJson = null,
        incompleteMarkerGenerations = emptySet(),
    )

    private fun dataPlan(fixture: M1StartupFixture): M1StartupFixturePlan {
        val blobs = attachmentBlobs(
            count = if (fixture == M1StartupFixture.Typical) 1 else HIGH_VOLUME_ATTACHMENTS,
        )
        val records = if (fixture == M1StartupFixture.Typical) {
            typicalRecords(blobs.keys.toList())
        } else {
            highVolumeRecords(blobs.keys.toList())
        }
        val generationId = generationId(fixture, "active")
        return M1StartupFixturePlan(
            fixture = fixture,
            fixtureVersion = FIXTURE_VERSION,
            activeGenerationId = generationId,
            previousGenerationId = null,
            candidateGenerationId = null,
            generationIds = listOf(generationId),
            records = records,
            blobs = blobs,
            snapshot = null,
            activationRecordJson = activationRecordJson(
                migrationState = "MIGRATION_HEALTHY",
                migrationJson = null,
                activeGenerationId = generationId,
                previousGenerationId = null,
                candidateGenerationId = null,
                retiringGenerationId = null,
                state = "HEALTHY",
            ),
            incompleteMarkerGenerations = emptySet(),
        )
    }

    private fun pendingActivationPlan(fixture: M1StartupFixture): M1StartupFixturePlan {
        val blobs = attachmentBlobs(1)
        val records = typicalRecords(blobs.keys.toList())
        val originalId = generationId(fixture, "previous")
        val candidateId = generationId(fixture, "candidate")
        return M1StartupFixturePlan(
            fixture = fixture,
            fixtureVersion = FIXTURE_VERSION,
            activeGenerationId = candidateId,
            previousGenerationId = originalId,
            candidateGenerationId = candidateId,
            generationIds = listOf(originalId, candidateId),
            records = records,
            blobs = blobs,
            snapshot = null,
            activationRecordJson = activationRecordJson(
                migrationState = "MIGRATION_HEALTHY",
                migrationJson = null,
                activeGenerationId = candidateId,
                previousGenerationId = originalId,
                candidateGenerationId = candidateId,
                retiringGenerationId = null,
                state = "PENDING",
            ),
            incompleteMarkerGenerations = setOf(candidateId),
        )
    }

    private fun pendingMigrationPlan(fixture: M1StartupFixture, stage: String): M1StartupFixturePlan {
        val blobs = attachmentBlobs(1)
        val records = typicalRecords(blobs.keys.toList())
        val snapshotId = deterministicId("$FIXTURE_VERSION:$SEED:${fixture.name}:snapshot").toString()
        val activeId = generationId(fixture, "active")
        return M1StartupFixturePlan(
            fixture = fixture,
            fixtureVersion = FIXTURE_VERSION,
            activeGenerationId = activeId,
            previousGenerationId = null,
            candidateGenerationId = null,
            generationIds = listOf(activeId),
            records = records,
            blobs = blobs,
            snapshot = M1FixtureSnapshotPlan(snapshotId = snapshotId, records = records),
            activationRecordJson = activationRecordJson(
                migrationState = "MIGRATION_RECOVERY_PENDING",
                migrationJson = migrationPayloadJson(snapshotId = snapshotId, stage = stage),
                activeGenerationId = activeId,
                previousGenerationId = null,
                candidateGenerationId = null,
                retiringGenerationId = null,
                state = "HEALTHY",
            ),
            incompleteMarkerGenerations = emptySet(),
        )
    }

    // ------------------------------------------------------------------
    // Deterministic datasets
    // ------------------------------------------------------------------

    /** Spec 11.2 Typical healthy: 2/5/6/2/2/1 with the blob linked to both owners. */
    private fun typicalRecords(blobIdentities: List<String>): M1FixtureRecords {
        val accounts = (0 until TYPICAL_ACCOUNTS).map { i ->
            M1FixtureAccount(
                id = deterministicId("$FIXTURE_VERSION:$SEED:account:$i").toString(),
                name = if (i == 0) "现金" else "招商银行",
                currency = "CNY",
            )
        }
        val accountIds = accounts.map { it.id }
        val txIds = (0 until TYPICAL_TRANSACTIONS).map { deterministicId("$FIXTURE_VERSION:$SEED:tx:$it").toString() }
        val noteIds = (0 until TYPICAL_NOTES).map { deterministicId("$FIXTURE_VERSION:$SEED:note:$it").toString() }
        // Exact type order: 2 opening + 1 expense + 1 income + 1 transfer.
        val types = listOf(
            TransactionType.OPENING_BALANCE,
            TransactionType.OPENING_BALANCE,
            TransactionType.EXPENSE,
            TransactionType.INCOME,
            TransactionType.TRANSFER,
        )
        val transactions = types.mapIndexed { i, type -> M1FixtureTransaction(txIds[i], type) }
        val entries = listOf(
            M1FixtureAccountEntry(deterministicEntryId(0), txIds[0], accountIds[0], 500_000L),
            M1FixtureAccountEntry(deterministicEntryId(1), txIds[1], accountIds[1], 200_000L),
            M1FixtureAccountEntry(deterministicEntryId(2), txIds[2], accountIds[0], -15_000L),
            M1FixtureAccountEntry(deterministicEntryId(3), txIds[3], accountIds[0], 30_000L),
            M1FixtureAccountEntry(deterministicEntryId(4), txIds[4], accountIds[0], -10_000L),
            M1FixtureAccountEntry(deterministicEntryId(5), txIds[4], accountIds[1], 10_000L),
        )
        val notes = noteIds.map { M1FixtureNote(it) }
        val relations = listOf(
            M1FixtureRelation(transactionId = txIds[2], noteId = noteIds[0]),
            M1FixtureRelation(transactionId = txIds[3], noteId = noteIds[1]),
        )
        val attachments = listOf(
            M1FixtureAttachment(
                id = deterministicId("$FIXTURE_VERSION:$SEED:attachment:0").toString(),
                contentIdentity = blobIdentities[0],
                deletedAt = null,
            ),
        )
        // The canonical blob is linked to both valid owners: one transaction and one note.
        val links = listOf(
            M1FixtureAttachmentLink(attachmentId = attachments[0].id, ownerEntityRefId = txIds[1]),
            M1FixtureAttachmentLink(attachmentId = attachments[0].id, ownerEntityRefId = noteIds[0]),
        )
        return M1FixtureRecords(
            accounts = accounts,
            entityRefs = entityRefsFor(transactions.map { it.id }, notes.map { it.id }),
            transactions = transactions,
            entries = entries,
            notes = notes,
            relations = relations,
            attachments = attachments,
            links = links,
        )
    }

    /** Spec 11.2 High-volume healthy: the frozen P6 type mix over 6,000 transactions. */
    private fun highVolumeRecords(blobIdentities: List<String>): M1FixtureRecords {
        val accounts = (0 until HIGH_VOLUME_ACCOUNTS).map { i ->
            M1FixtureAccount(
                id = deterministicId("$FIXTURE_VERSION:$SEED:hv:account:$i").toString(),
                name = "FixtureAccount$i",
                currency = "CNY",
            )
        }
        val accountIds = accounts.map { it.id }
        val transactions = ArrayList<M1FixtureTransaction>(HIGH_VOLUME_TRANSACTIONS)
        val entries = ArrayList<M1FixtureAccountEntry>(7_190)
        var entryOrdinal = 0
        fun nextEntry(): String = deterministicId("$FIXTURE_VERSION:$SEED:hv:entry:${entryOrdinal++}").toString()
        for (i in 0 until HIGH_VOLUME_TRANSACTIONS) {
            val id = deterministicId("$FIXTURE_VERSION:$SEED:hv:tx:$i").toString()
            val type = when {
                i < HIGH_VOLUME_OPENING -> TransactionType.OPENING_BALANCE
                i < HIGH_VOLUME_OPENING + HIGH_VOLUME_EXPENSE -> TransactionType.EXPENSE
                i < HIGH_VOLUME_OPENING + HIGH_VOLUME_EXPENSE + HIGH_VOLUME_INCOME ->
                    TransactionType.INCOME
                else -> TransactionType.TRANSFER
            }
            transactions += M1FixtureTransaction(id, type)
            when (type) {
                TransactionType.OPENING_BALANCE -> entries += M1FixtureAccountEntry(
                    nextEntry(), id, accountIds[i % accountIds.size], 100_000L + i,
                )
                TransactionType.EXPENSE -> entries += M1FixtureAccountEntry(
                    nextEntry(), id, accountIds[i % accountIds.size], -(100L + i % 49_000),
                )
                TransactionType.INCOME -> entries += M1FixtureAccountEntry(
                    nextEntry(), id, accountIds[i % accountIds.size], 1_000L + i,
                )
                TransactionType.TRANSFER -> {
                    // Distinct same-currency accounts, exact zero sum, one negative leg.
                    val source = accountIds[i % accountIds.size]
                    val destination = accountIds[(i + 1 + i % (accountIds.size - 1)) % accountIds.size]
                    val amount = 1_000L + i % 199_000
                    entries += M1FixtureAccountEntry(nextEntry(), id, source, -amount)
                    entries += M1FixtureAccountEntry(nextEntry(), id, destination, amount)
                }
            }
        }
        val notes = (0 until HIGH_VOLUME_NOTES).map { i ->
            M1FixtureNote(deterministicId("$FIXTURE_VERSION:$SEED:hv:note:$i").toString())
        }
        val relations = (0 until HIGH_VOLUME_RELATIONS).map { i ->
            M1FixtureRelation(
                transactionId = transactions[i * 3].id,
                noteId = notes[(i * 2) % notes.size].id,
            )
        }
        val attachments = blobIdentities.mapIndexed { i, identity ->
            M1FixtureAttachment(
                id = deterministicId("$FIXTURE_VERSION:$SEED:hv:attachment:$i").toString(),
                contentIdentity = identity,
                deletedAt = null,
            )
        }
        val links = attachments.mapIndexed { i, attachment ->
            M1FixtureAttachmentLink(
                attachmentId = attachment.id,
                ownerEntityRefId = notes[i % notes.size].id,
            )
        }
        return M1FixtureRecords(
            accounts = accounts,
            entityRefs = entityRefsFor(transactions.map { it.id }, notes.map { it.id }),
            transactions = transactions,
            entries = entries,
            notes = notes,
            relations = relations,
            attachments = attachments,
            links = links,
        )
    }

    /** One EntityRef row per transaction/note subtype row, deterministic stamps. */
    private fun entityRefsFor(txIds: List<String>, noteIds: List<String>): List<M1FixtureEntityRef> {
        val refs = ArrayList<M1FixtureEntityRef>(txIds.size + noteIds.size)
        for ((i, id) in txIds.withIndex()) {
            refs += M1FixtureEntityRef(
                id = id,
                entityType = EntityType.TRANSACTION,
                createdAt = FIXED_EPOCH_MILLIS + i,
                updatedAt = FIXED_EPOCH_MILLIS + i,
                deletedAt = null,
            )
        }
        val txOffset = txIds.size
        for ((i, id) in noteIds.withIndex()) {
            refs += M1FixtureEntityRef(
                id = id,
                entityType = EntityType.NOTE,
                createdAt = FIXED_EPOCH_MILLIS + txOffset + i,
                updatedAt = FIXED_EPOCH_MILLIS + txOffset + i,
                deletedAt = null,
            )
        }
        return refs
    }

    private fun deterministicEntryId(i: Int): String =
        deterministicId("$FIXTURE_VERSION:$SEED:entry:$i").toString()

    /** `count` deterministic 64-KiB PNGs keyed by their content identity. */
    private fun attachmentBlobs(count: Int): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        repeat(count) { i ->
            val blob = syntheticImage(i, IMAGE_TARGET_BYTES)
            out[sha256(blob)] = blob
        }
        return out
    }

    // ------------------------------------------------------------------
    // Deterministic 64-KiB decodable PNG
    // ------------------------------------------------------------------

    /**
     * One deterministic, valid, decodable grayscale PNG of exactly [targetBytes]
     * bytes: signature, IHDR (8-bit grayscale), one uncompressed (BTYPE=00)
     * zlib IDAT so the bytes never depend on a deflate implementation, one
     * private ancillary `mrCt` chunk used only to reach the exact size, and
     * IEND last. Every conformant decoder ignores `mrCt`.
     */
    fun syntheticImage(index: Int, targetBytes: Int, salt: String = "m1"): ByteArray {
        require(targetBytes == IMAGE_TARGET_BYTES) { "the frozen 64-KiB target size is the only fixture size" }
        val width = 255
        val height = 255
        val raw = ByteArray((width + 1) * height) // one filter byte + one row of gray pixels
        var cursor = 0
        for (row in 0 until height) {
            raw[cursor++] = 0 // filter type: none
            for (x in 0 until width) {
                raw[cursor++] = ((31 * row + 17 * x + 7 + index) and 0xFF).toByte()
            }
        }
        val ihdrData = ByteArray(13)
        writeInt(ihdrData, 0, width)
        writeInt(ihdrData, 4, height)
        ihdrData[8] = 8 // bit depth
        ihdrData[9] = 0 // color type: grayscale
        ihdrData[10] = 0 // compression
        ihdrData[11] = 0 // filter
        ihdrData[12] = 0 // interlace
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val idatChunk = chunk("IDAT", storedZlib(raw))
        // Layout: signature + IHDR(25) + IDAT + mrCt(12 + padding) + IEND(12).
        val paddingLength = targetBytes - signature.size - 25 - idatChunk.size - 24
        require(paddingLength >= 0) { "no room for the padding chunk" }
        val padding = ByteArray(paddingLength) { i ->
            ((i * 13 + 5 + index + salt.hashCode()) and 0xFF).toByte()
        }
        return signature +
            chunk("IHDR", ihdrData) +
            idatChunk +
            chunk("mrCt", padding) +
            chunk("IEND", ByteArray(0))
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        require(type.length == 4)
        val out = ByteArray(12 + data.size)
        writeInt(out, 0, data.size)
        type.toByteArray(Charsets.US_ASCII).copyInto(out, 4)
        data.copyInto(out, 8)
        val crc = CRC32()
        crc.update(out, 4, data.size + 4)
        writeInt(out, 8 + data.size, crc.value.toInt())
        return out
    }

    /** A minimal deterministic zlib stream of stored blocks (no deflate dependency). */
    private fun storedZlib(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size + 16)
        out.write(0x78) // CMF: deflate, 32K window
        out.write(0x01) // FLG: fastest, no preset dictionary (0x7801 % 31 == 0)
        var offset = 0
        while (offset < raw.size) {
            val blockLength = minOf(65_535, raw.size - offset)
            val isFinal = offset + blockLength >= raw.size
            out.write(if (isFinal) 1 else 0)
            out.write(blockLength and 0xFF)
            out.write((blockLength shr 8) and 0xFF)
            out.write(blockLength.inv() and 0xFF)
            out.write((blockLength.inv() shr 8) and 0xFF)
            out.write(raw, offset, blockLength)
            offset += blockLength
        }
        val adler = Adler32()
        adler.update(raw)
        val value = adler.value
        out.write(((value shr 24) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
        return out.toByteArray()
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun deterministicId(tag: String): UUID =
        UUID.nameUUIDFromBytes(tag.toByteArray(Charsets.UTF_8))

    private fun generationId(fixture: M1StartupFixture, role: String): String =
        "gen-" + deterministicId("$FIXTURE_VERSION:$SEED:${fixture.name}:gen:$role").toString()

    // ------------------------------------------------------------------
    // Exact v2 activation-record JSON
    // ------------------------------------------------------------------

    /**
     * The byte-exact v2 record the strict `ActivationRecordStore` writes: the
     * same field order, the same `null` literals, no whitespace.
     */
    private fun activationRecordJson(
        migrationState: String,
        migrationJson: String?,
        activeGenerationId: String,
        previousGenerationId: String?,
        candidateGenerationId: String?,
        retiringGenerationId: String?,
        state: String,
    ): String =
        "{" +
            "\"recordVersion\":2," +
            "\"migrationRecoveryState\":\"$migrationState\"," +
            "\"migration\":${migrationJson ?: "null"}," +
            "\"generationActivation\":{" +
            "\"activeGenerationId\":\"$activeGenerationId\"," +
            "\"previousGenerationId\":${jsonNullable(previousGenerationId)}," +
            "\"candidateGenerationId\":${jsonNullable(candidateGenerationId)}," +
            "\"retiringGenerationId\":${jsonNullable(retiringGenerationId)}," +
            "\"state\":\"$state\"" +
            "}}"

    /** The exact seven-field P3 migration payload, with app-root-relative paths. */
    private fun migrationPayloadJson(snapshotId: String, stage: String): String =
        "{" +
            "\"snapshotId\":\"$snapshotId\"," +
            "\"snapshotSha256\":\"$SNAPSHOT_SHA256_PLACEHOLDER\"," +
            "\"liveDatabasePath\":\"databases/record.db\"," +
            "\"workDatabasePath\":\"databases/record.db\"," +
            "\"restoreTempPath\":\"files/recovery/restore-$snapshotId.db\"," +
            "\"quarantinePath\":\"files/recovery/quarantine-$snapshotId/\"," +
            "\"stage\":\"$stage\"" +
            "}"

    private fun jsonNullable(value: String?): String = value?.let { "\"$it\"" } ?: "null"

    // ------------------------------------------------------------------
    // Device-side application (benchmark app only)
    // ------------------------------------------------------------------

    /**
     * Applies one fixture into the benchmark application's own private
     * generation/recovery root, validates it with the production staged
     * validator, and returns the on-device report. This is the only entry the
     * benchmark-only fixture Activity calls; it never reads or writes another
     * package's data.
     */
    fun prepare(context: Context, fixture: M1StartupFixture): M1StartupFixtureReport {
        val plan = plan(fixture)
        val problems = validatePlan(plan)
        require(problems.isEmpty()) { "refusing to publish an invalid fixture: $problems" }
        resetFixtureRoots(context)
        val reportProblems = mutableListOf<String>()
        for (generationId in plan.generationIds) {
            val failure = writeGeneration(
                context,
                generationId,
                plan,
                markIncomplete = generationId in plan.incompleteMarkerGenerations,
            )
            if (failure != null) reportProblems += failure
        }
        var snapshotSha256: String? = null
        plan.snapshot?.let { snapshotPlan ->
            val snapshotFile = File(
                File(context.filesDir, "migration"),
                "snapshot-${snapshotPlan.snapshotId}.db",
            )
            snapshotFile.parentFile?.mkdirs()
            writeSnapshotDatabase(snapshotFile, snapshotPlan.records)
            snapshotSha256 = sha256(snapshotFile.readBytes())
        }
        var recordSha256: String? = null
        plan.activationRecordJson?.let { recordJson ->
            val bytes = recordJson.replace(SNAPSHOT_SHA256_PLACEHOLDER, snapshotSha256 ?: "")
                .toByteArray(Charsets.UTF_8)
            writeActivationRecord(context, bytes)
            recordSha256 = sha256(bytes)
        }
        if (plan.fixture == M1StartupFixture.Fresh) {
            reportProblems += verifyFreshAbsence(context)
        }
        return M1StartupFixtureReport(
            fixture = plan.fixture.name,
            fixtureVersion = plan.fixtureVersion,
            contentDigest = plan.contentDigest,
            activationRecordSha256 = recordSha256,
            snapshotSha256 = snapshotSha256,
            generationIds = plan.generationIds,
            recordCounts = plan.recordCounts(),
            attachmentCount = plan.records.attachments.size,
            problems = reportProblems,
        )
    }

    /** Deletes only this application's own generation/recovery roots. */
    private fun resetFixtureRoots(context: Context) {
        val dataRoot = checkNotNull(context.filesDir.parentFile) { "app data root unavailable" }
        for (relative in FIXTURE_ROOTS) {
            val dir = File(dataRoot, relative)
            if (dir.exists()) {
                check(dir.deleteRecursively()) { "failed to reset fixture root $relative" }
            }
        }
    }

    /** A fresh fixture must leave every generation/recovery root absent. */
    private fun verifyFreshAbsence(context: Context): List<String> {
        val dataRoot = checkNotNull(context.filesDir.parentFile)
        return FIXTURE_ROOTS
            .filter { File(dataRoot, it).exists() }
            .map { "fresh fixture root must be absent after reset: $it" }
    }

    /** Writes one complete generation (Room database + attachment content tree). */
    private fun writeGeneration(
        context: Context,
        generationId: String,
        plan: M1StartupFixturePlan,
        markIncomplete: Boolean,
    ): String? {
        val generationDir = File(File(context.filesDir, "vaults"), generationId)
        check(!generationDir.exists()) { "generation dir already exists: $generationId" }
        check(generationDir.mkdirs()) { "failed to create generation dir $generationDir" }
        if (markIncomplete) {
            File(generationDir, ".incomplete").writeText("incomplete\n", Charsets.UTF_8)
        }
        val database = openGenerationDatabase(context, File(generationDir, "record.db"))
        try {
            runBlocking {
                database.withTransaction {
                    val dao = database.recordDao()
                    dao.insertAllEntityRefs(plan.records.entityRefs.map {
                        EntityRef(it.id, it.entityType, it.createdAt, it.updatedAt, it.deletedAt)
                    })
                    dao.insertAllAccounts(plan.records.accounts.map { Account(it.id, it.name, it.currency) })
                    dao.insertAllTransactions(plan.records.transactions.map { Transaction(it.id, it.type) })
                    dao.insertAllNotes(plan.records.notes.map { Note(it.id) })
                    dao.insertAllEntries(plan.records.entries.map {
                        AccountEntry(it.id, it.transactionId, it.accountId, it.amountMinor)
                    })
                    dao.insertAllRelations(plan.records.relations.map {
                        TransactionNoteRelation(it.transactionId, it.noteId)
                    })
                    dao.insertAllAttachments(plan.records.attachments.map {
                        Attachment(it.id, it.contentIdentity, it.deletedAt)
                    })
                    dao.insertAllAttachmentLinks(plan.records.links.map {
                        AttachmentLink(it.attachmentId, it.ownerEntityRefId)
                    })
                }
            }
        } finally {
            database.close()
        }
        val contentDir = File(generationDir, "attachments/content")
        check(contentDir.mkdirs() || contentDir.isDirectory) { "failed to create content dir $contentDir" }
        plan.blobs.forEach { (identity, bytes) ->
            File(contentDir, identity).writeBytes(bytes)
        }
        return validateGeneration(context, generationDir)
    }

    /** The production staged validator over one written generation. */
    private fun validateGeneration(context: Context, generationDir: File): String? {
        val database = openGenerationDatabase(context, File(generationDir, "record.db"))
        return try {
            val validation = runBlocking {
                StagedGenerationValidator(database, File(generationDir, "attachments/content")).validate()
            }
            when (validation) {
                is GenerationValidation.Valid -> null
                is GenerationValidation.Failed -> "staged validation failed: ${validation.detail}"
                else -> "staged validation returned an unknown outcome"
            }
        } catch (e: Throwable) {
            "staged validation threw: ${e.message}"
        } finally {
            database.close()
        }
    }

    /** Writes the exact v2 record bytes atomically at the app-private record path. */
    private fun writeActivationRecord(context: Context, bytes: ByteArray) {
        val file = File(File(context.filesDir, "recovery"), "activation-recovery-v1.json")
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            out.flush()
            atomic.finishWrite(out)
        } catch (e: Throwable) {
            atomic.failWrite(out)
            throw e
        }
    }

    /**
     * Builds the valid published snapshot: a v1-schema SQLite database carrying
     * the six v1 tables with the fixture dataset's v1 rows at `user_version = 1`.
     * The DDL matches the committed `core/data/schemas/.../1.json` exactly and
     * changes no production schema.
     */
    private fun writeSnapshotDatabase(file: File, records: M1FixtureRecords) {
        SQLiteDatabase.deleteDatabase(file)
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.CREATE_IF_NECESSARY)
        try {
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS `EntityRef` (`id` TEXT NOT NULL, `entityType` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `Transaction` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`id`) REFERENCES `EntityRef`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
                db.execSQL("CREATE TABLE IF NOT EXISTS `Account` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `currency` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `AccountEntry` (`id` TEXT NOT NULL, `transactionId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`transactionId`) REFERENCES `Transaction`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`accountId`) REFERENCES `Account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
                db.execSQL("CREATE TABLE IF NOT EXISTS `Note` (`id` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`id`) REFERENCES `EntityRef`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
                db.execSQL("CREATE TABLE IF NOT EXISTS `TransactionNoteRelation` (`transactionId` TEXT NOT NULL, `noteId` TEXT NOT NULL, PRIMARY KEY(`transactionId`, `noteId`), FOREIGN KEY(`transactionId`) REFERENCES `Transaction`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`noteId`) REFERENCES `Note`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
                for (ref in records.entityRefs) {
                    db.execSQL(
                        "INSERT INTO `EntityRef` (`id`,`entityType`,`createdAt`,`updatedAt`,`deletedAt`) VALUES (?,?,?,?,?)",
                        arrayOf(ref.id, ref.entityType.name, ref.createdAt, ref.updatedAt, ref.deletedAt),
                    )
                }
                for (tx in records.transactions) {
                    db.execSQL("INSERT INTO `Transaction` (`id`,`type`) VALUES (?,?)", arrayOf(tx.id, tx.type.name))
                }
                for (account in records.accounts) {
                    db.execSQL("INSERT INTO `Account` (`id`,`name`,`currency`) VALUES (?,?,?)", arrayOf(account.id, account.name, account.currency))
                }
                for (entry in records.entries) {
                    db.execSQL(
                        "INSERT INTO `AccountEntry` (`id`,`transactionId`,`accountId`,`amountMinor`) VALUES (?,?,?,?)",
                        arrayOf(entry.id, entry.transactionId, entry.accountId, entry.amountMinor),
                    )
                }
                for (note in records.notes) {
                    db.execSQL("INSERT INTO `Note` (`id`) VALUES (?)", arrayOf(note.id))
                }
                for (relation in records.relations) {
                    db.execSQL("INSERT INTO `TransactionNoteRelation` (`transactionId`,`noteId`) VALUES (?,?)", arrayOf(relation.transactionId, relation.noteId))
                }
                db.version = 1
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }
}

/** The benchmark fixture states, in the exact spec 11.2 order. */
enum class M1StartupFixture {
    Fresh,
    Typical,
    HighVolume,
    PendingActivation,
    PendingMigrationPrepared,
    PendingMigrationFailedSetIsolated,
    PendingMigrationRestoreCopyValidated,
    PendingMigrationMainReplaced,
    ;

    companion object {
        /** The wire token is the enum name itself; unknown tokens are refused. */
        fun fromWire(raw: String): M1StartupFixture =
            entries.firstOrNull { it.name == raw }
                ?: throw IllegalArgumentException("unknown m1 fixture: $raw")
    }
}

/** One deterministic fixture account row (pure host types; no Room type leaks). */
class M1FixtureAccount(val id: String, val name: String, val currency: String)

/** One deterministic EntityRef discriminator row (pure host types). */
class M1FixtureEntityRef(
    val id: String,
    val entityType: EntityType,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

/** One deterministic Transaction subtype row (pure host types). */
class M1FixtureTransaction(val id: String, val type: TransactionType)

/** One deterministic AccountEntry row (pure host types). */
class M1FixtureAccountEntry(
    val id: String,
    val transactionId: String,
    val accountId: String,
    val amountMinor: Long,
)

/** One deterministic Note subtype row (pure host types). */
class M1FixtureNote(val id: String)

/** One deterministic typed relation row (pure host types). */
class M1FixtureRelation(val transactionId: String, val noteId: String)

/** One deterministic canonical attachment metadata row (pure host types). */
class M1FixtureAttachment(val id: String, val contentIdentity: String, val deletedAt: Long?)

/** One deterministic owner link row (pure host types). */
class M1FixtureAttachmentLink(val attachmentId: String, val ownerEntityRefId: String)

/** One generation's complete record set (pure host types). */
class M1FixtureRecords(
    val accounts: List<M1FixtureAccount>,
    val entityRefs: List<M1FixtureEntityRef>,
    val transactions: List<M1FixtureTransaction>,
    val entries: List<M1FixtureAccountEntry>,
    val notes: List<M1FixtureNote>,
    val relations: List<M1FixtureRelation>,
    val attachments: List<M1FixtureAttachment>,
    val links: List<M1FixtureAttachmentLink>,
)

/** The valid published snapshot plan of a pending-migration fixture. */
class M1FixtureSnapshotPlan(
    val snapshotId: String,
    val records: M1FixtureRecords,
)

/**
 * The complete deterministic plan of one fixture. [contentDigest] is the
 * SHA-256 of [canonicalUtf8], computed once.
 */
class M1StartupFixturePlan(
    val fixture: M1StartupFixture,
    val fixtureVersion: String,
    val activeGenerationId: String?,
    val previousGenerationId: String?,
    val candidateGenerationId: String?,
    val generationIds: List<String>,
    val records: M1FixtureRecords,
    val blobs: Map<String, ByteArray>,
    val snapshot: M1FixtureSnapshotPlan?,
    val activationRecordJson: String?,
    val incompleteMarkerGenerations: Set<String>,
) {
    /** Deterministic canonical UTF-8 serialization; its SHA-256 is [contentDigest]. */
    fun canonicalUtf8(): ByteArray = canonicalBytes(this)

    val contentDigest: String by lazy { sha256(canonicalBytes(this)) }

    /** The frozen per-table row counts the fixture Activity publishes. */
    fun recordCounts(): Map<String, Int> = linkedMapOf(
        "accounts" to records.accounts.size,
        "entityRefs" to records.entityRefs.size,
        "transactions" to records.transactions.size,
        "entries" to records.entries.size,
        "notes" to records.notes.size,
        "relations" to records.relations.size,
        "attachments" to records.attachments.size,
        "links" to records.links.size,
    )
}

/** The on-device result of applying one fixture; [problems] must be empty. */
class M1StartupFixtureReport(
    val fixture: String,
    val fixtureVersion: String,
    val contentDigest: String,
    val activationRecordSha256: String?,
    val snapshotSha256: String?,
    val generationIds: List<String>,
    val recordCounts: Map<String, Int>,
    val attachmentCount: Int,
    val problems: List<String>,
) {
    val passed: Boolean get() = problems.isEmpty()
}

/** The canonical serialization shared by the plan type. */
private fun canonicalBytes(plan: M1StartupFixturePlan): ByteArray = buildString {
    append(plan.fixtureVersion).append('\n')
    append("seed=").append(M1StartupFixtureGenerator.SEED).append('\n')
    append("clock=").append(M1StartupFixtureGenerator.FIXED_EPOCH_MILLIS).append('\n')
    append("fixture=").append(plan.fixture.name).append('\n')
    append("accounts=").append(plan.records.accounts.size).append('\n')
    append("entityRefs=").append(plan.records.entityRefs.size).append('\n')
    append("transactions=").append(plan.records.transactions.size).append('\n')
    append("entries=").append(plan.records.entries.size).append('\n')
    append("notes=").append(plan.records.notes.size).append('\n')
    append("relations=").append(plan.records.relations.size).append('\n')
    append("attachments=").append(plan.records.attachments.size).append('\n')
    append("links=").append(plan.records.links.size).append('\n')
    append("blobs=").append(plan.blobs.size).append('\n')
    append("genActive=").append(plan.activeGenerationId ?: "-").append('\n')
    append("genPrevious=").append(plan.previousGenerationId ?: "-").append('\n')
    append("genCandidate=").append(plan.candidateGenerationId ?: "-").append('\n')
    append("incomplete=").append(plan.incompleteMarkerGenerations.sorted().joinToString(",")).append('\n')
    append("record=").append(plan.activationRecordJson ?: "-").append('\n')
    append("snapshot=").append(plan.snapshot?.snapshotId ?: "-").append('\n')
    plan.blobs.entries.sortedBy { it.key }.forEach { (identity, bytes) ->
        append("blob:").append(identity).append(':').append(sha256(bytes))
            .append(':').append(bytes.size).append('\n')
    }
    plan.records.accounts.forEach {
        append("account:").append(it.id).append(':').append(it.name).append(':').append(it.currency).append('\n')
    }
    plan.records.entityRefs.forEach {
        append("entityRef:").append(it.id).append(':').append(it.entityType.name).append(':')
            .append(it.createdAt).append(':').append(it.updatedAt)
            .append(':').append(it.deletedAt ?: "null").append('\n')
    }
    plan.records.transactions.forEach {
        append("transaction:").append(it.id).append(':').append(it.type.name).append('\n')
    }
    plan.records.entries.forEach {
        append("entry:").append(it.id).append(':').append(it.transactionId).append(':')
            .append(it.accountId).append(':').append(it.amountMinor).append('\n')
    }
    plan.records.notes.forEach { append("note:").append(it.id).append('\n') }
    plan.records.relations.forEach {
        append("relation:").append(it.transactionId).append(':').append(it.noteId).append('\n')
    }
    plan.records.attachments.forEach {
        append("attachment:").append(it.id).append(':').append(it.contentIdentity)
            .append(':').append(it.deletedAt ?: "null").append('\n')
    }
    plan.records.links.forEach {
        append("link:").append(it.attachmentId).append(':').append(it.ownerEntityRefId).append('\n')
    }
    append("END")
}.toByteArray(Charsets.UTF_8)

/** Lowercase hex SHA-256 of [bytes]. */
internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
package com.paherdin.record.core.data.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Canonical attachment metadata (spec 4.6, ADR 0005/0008). `contentIdentity`
 * is globally unique — a second row for one SHA-256 is rejected by the
 * database before the application can observe it. `deletedAt` is the
 * zero-link cleanup marker and grace-period start (ADR 0008): non-null means
 * zero global links and cleanup-pending; new `CONTENT_READY` rows start with
 * `deletedAt = ingestedAt`; a verified owner-link transaction clears it.
 */
@Entity(
    tableName = "Attachment",
    indices = [Index(value = ["contentIdentity"], unique = true)],
)
internal data class Attachment(
    @PrimaryKey val id: String,
    val contentIdentity: String,
    val deletedAt: Long?,
)

/**
 * Owner link to the canonical attachment (spec 4.1, ADR 0002): composite PK
 * over the canonical Attachment ID and the owning Transaction/Note EntityRef,
 * both FKs with `NO ACTION` actions per ADR 0006. The `ownerEntityRefId`
 * index serves owner-direction queries.
 */
@Entity(
    tableName = "AttachmentLink",
    primaryKeys = ["attachmentId", "ownerEntityRefId"],
    foreignKeys = [
        ForeignKey(
            entity = Attachment::class,
            parentColumns = ["id"],
            childColumns = ["attachmentId"],
        ),
        ForeignKey(
            entity = EntityRef::class,
            parentColumns = ["id"],
            childColumns = ["ownerEntityRefId"],
        ),
    ],
    indices = [Index(value = ["ownerEntityRefId"])],
)
internal data class AttachmentLink(
    val attachmentId: String,
    val ownerEntityRefId: String,
)

# ADR 0005: Canonical attachment content identity

## Status

Accepted

## Date

2026-08-04

## Context

Transactions and Notes can attach the same image bytes. Content-addressed storage saves space, but an ambiguous metadata model could create several Attachment rows for one SHA-256 and let a cleaner delete a shared physical blob after counting links for only one row. Import, backup, restore, and migration need one identity rule that remains valid under duplicate imports, concurrent linking, unlinking, and cleanup.

The authoritative lifecycle is defined by [data and storage](../data-and-storage.md), domain ownership by the [domain model](../domain-model.md), and required evidence by the [testing strategy](../../engineering/testing-strategy.md).

## Options considered

- One globally canonical Attachment fact and physical blob per SHA-256, with many owner links.
- Multiple Attachment facts sharing one SHA-256 and a separate blob-reference table.
- One copied blob per owner with no deduplication.
- Best-effort deduplication without a durable uniqueness invariant.

## Decision

One SHA-256 `contentIdentity` globally identifies exactly one canonical Attachment metadata fact and exactly one physical content-addressed blob. The persistence model enforces a unique constraint on `Attachment.contentIdentity`; multiple Attachment rows sharing a SHA-256 are invalid. Every `AttachmentLink` references the canonical Attachment ID, and any number of valid Transaction/Note owners may link to it.

Import is serialized per SHA-256. A duplicate import must verify the existing blob's actual hash and size, reuse the existing canonical metadata identity, and add only the requested owner link. If metadata exists without valid bytes, bytes exist with conflicting metadata, or verification produces a mismatch, the operation reports typed corruption and never silently overwrites the canonical content or creates a second row. A newly ingested unlinked temporary file may be quarantined; linked canonical content is not relocated as a repair attempt.

Deletion state belongs to the canonical Attachment. A candidate reaches `DELETE_PENDING` only after counting every `AttachmentLink` to that canonical ID and observing zero. The cleaner obtains the same per-hash serialization, rechecks the global link count and blob identity, and holds serialization through physical-absence confirmation and canonical metadata removal. Removing one owner cannot affect another owner's link. A concurrent relink cancels or waits for deletion, revalidates bytes, and reuses the same canonical ID.

Logical backup serializes one canonical Attachment record and one blob per content identity plus all owner links. Restore rejects duplicate Attachment rows for one SHA-256, duplicate/conflicting blobs, missing content, hash/size mismatch, or links to a noncanonical/missing identity before activation. A round trip preserves canonical IDs, hashes, bytes, and the complete many-owner link set.

Every schema or backup-format migration preserves or establishes the global uniqueness invariant before publication. Legacy duplicate metadata, if ever supported by an explicit migration, must merge links only after byte equivalence is proven and must fail recoverably on conflicting content; implicit runtime repair is forbidden.

## Consequences

- Link counting and cleanup have one unambiguous global scope.
- Duplicate imports reuse both bytes and metadata, minimizing storage and preventing split lifecycle state.
- Per-hash serialization is required for import, linking, unlinking, and cleanup.
- Corrupt metadata/blob combinations fail closed and require explicit recovery rather than silent replacement.
- Backup, restore, and migration validators must enforce canonical uniqueness and many-link preservation.

## Verification

- Two owners import identical bytes, receive links to one canonical Attachment ID, and retain one physical blob.
- Unlinking either owner while the other remains linked cannot mark or delete the canonical content.
- Cleanup racing a relink preserves the new link and bytes; cleanup after the last unlink remains idempotent across process death.
- Duplicate import verifies and reuses valid content; mismatch and metadata/blob split fixtures fail without changing owners.
- Logical backup/restore preserves one canonical record, one blob, all links, and soft-deleted historical owners as required by the complete backup contract.

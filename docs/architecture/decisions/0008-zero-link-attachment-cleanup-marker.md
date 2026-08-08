# ADR 0008: Zero-link attachment cleanup marker

## Status

Accepted

## Date

2026-08-08

The user explicitly selected option A during P3 plan validation: a durably prepared zero-link canonical Attachment row uses the existing nullable `deletedAt` column as its cleanup marker and grace-period start.

## Context

P3 separates attachment ingestion from owner save. A successful ingestion must return a durable canonical `contentIdentity`, yet content may never be linked if the later owner save fails or is abandoned. Schema v2 already contains only `Attachment.id`, unique `contentIdentity`, and nullable `deletedAt`; the approved slice forbids speculative state columns.

The first P3 plan draft committed a zero-link `CONTENT_READY` row but did not give it a persisted grace-period start. That made the cleaner's seven-day gate unimplementable. It also described physical blob deletion and metadata deletion as if they were one durability step, contradicting the required retry window after the blob is absent but before metadata deletion commits.

The canonical identity and per-hash serialization decision remains owned by [ADR 0005](0005-canonical-attachment-content.md). Attachment lifecycle and cleanup remain owned by [data and storage](../data-and-storage.md); this ADR records the persistence meaning exposed by P3's first concrete schema.

## Options considered

- **Use `deletedAt` for every zero-link cleanup-pending row (chosen).** A new or freshly reingested zero-link row stores the ingestion time; verified linking clears it; last unlink stores the unlink time.
- Keep Attachment metadata out of Room until owner save and add a separate durable staging manifest. Rejected: it creates a second recovery protocol and identity store solely for the gap between ingestion and owner save.
- Add `readyAt` or a persisted state enum. Rejected: it widens schema v2 without adding a distinct invariant that the existing zero-link cleanup marker cannot express.

## Decision

The nullable `Attachment.deletedAt` reliability column has one persistence meaning: non-null means the canonical Attachment has zero global links and is cleanup-pending from that epoch-millis UTC instant; null means it is active and has at least one verified owner link.

- A new successful ingestion commits the canonical row with `deletedAt = ingestedAt` after atomic blob publication.
- Fresh successful ingestion of valid existing zero-link content reuses the canonical ID and refreshes `deletedAt = ingestedAt`, ensuring the reported preparation receives a full grace period.
- Owner save or explicit link obtains the shared per-hash serialization, reverifies the blob, and clears `deletedAt` in the same Room transaction that inserts the link.
- Last unlink sets `deletedAt = unlinkedAt` in the same Room transaction that removes the final link. Removing a non-final link leaves `deletedAt == null`.
- After the grace period, the cleaner holds the same per-hash serialization through zero-link recheck, physical deletion, physical-absence confirmation, and metadata-deletion transaction commit.
- A non-null zero-link row whose blob is absent is the expected crash window after physical deletion and before metadata deletion; the cleaner completes it idempotently. A linked row, or a row with `deletedAt == null`, whose blob is absent is corruption.
- A link request with no validated replacement bytes returns typed content-not-available when the expected cleanup window has already removed the blob. Fresh validated ingestion may restore that zero-link canonical identity before linking.

No additional column, table, public read state, or domain soft-delete transition is introduced.

## Consequences

- Every canonical zero-link row has a deterministic, persisted cleanup deadline without a second staging database or sidecar protocol.
- Successful reingestion cannot race immediate cleanup based on a stale grace-period start.
- The physical-delete/metadata-delete crash window is classified explicitly and does not create a false corruption report.
- `deletedAt` remains a reliability field owned by data and storage; UI and domain reads do not expose it as user deletion.
- Any future change to this marker's encoding or meaning is a persistence-compatibility decision requiring a new ADR and migration evidence.

## Verification

- New ingestion and valid zero-link reingestion set/refresh `deletedAt` to the injected clock value.
- Verified owner save/link clears the marker and inserts the complete link set atomically; failure preserves the prior marker and owner state.
- Last unlink sets the marker atomically, while unlink with another owner leaves it null.
- Cleaner retry after blob deletion but before metadata deletion recognizes the expected pending state and completes idempotently.
- Cleanup racing reingestion or relink cannot delete freshly prepared or newly linked content.

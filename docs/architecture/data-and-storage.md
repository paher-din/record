# Data and storage

This document is the stable source of truth for persistence and recovery decisions. Domain identities, relationships, the transaction matrix, and purge invariants remain owned by the [domain model](domain-model.md). Observable backup, restore, CSV, attachment, and offline behavior remains owned by the [MVP requirements](../product/mvp-requirements.md); this document does not duplicate the full product journeys.

## Ownership and reliability principles

- The Room structured database is the source of truth for domain facts.
- Attachment binary content uses content-addressed storage coordinated by `:core:data`.
- `SearchDocument` is a rebuildable projection, never a domain fact.
- The logical backup is the external portability source and is separate from the internal database schema.
- The binding inputs to persistence behavior are the domain transaction matrix, the XOR `EntityRef` subtype invariant, the endpoint whitelist, and atomic purge.

## Room baseline and migrations

- The MVP baseline is Room 2.8.4.
- Room schema JSON is exported and versioned with the repository.
- All schema changes use explicit migrations. Production `fallbackToDestructiveMigration` is forbidden.
- Before a schema upgrade begins, the app creates a recoverable local migration snapshot.
- Migration tests cover both N-1 and N-2 database versions upgrading to the current version.
- Every migration verifies applicable data invariants, not only the existence of tables.
- Backup `formatVersion` and `databaseSchemaVersion` are separate version axes. A backup format can remain compatible across internal schema changes, and internal schema compatibility does not imply backup-format compatibility.

This document intentionally contains no concrete schema, DDL, or Room annotations.

## Attachment state flow

The accepted ingest flow is:

Photo Picker or SAF → copy to a temporary file → validate declared and decoded MIME type, size, and decodability → calculate SHA-256 → atomically move to the content-addressed final path → use one Room transaction to write `Attachment` and `AttachmentLink`.

- In MVP, attachment owners are limited to `Transaction` and `Note` EntityRefs.
- A crash before the database commit can leave an orphan physical file. A later cleanup pass may remove it under the content lifecycle rules.
- The database must never expose a committed attachment link whose physical content is missing.
- Content is deduplicated by SHA-256. Physical content is eligible for cleanup only after its reference count reaches zero and the grace period has elapsed.
- Paths and filenames supplied by external input are never trusted. Storage derives its own destination from validated content identity.

## SearchDocument

Each `SearchDocument` contains enough information to identify and render a search result without becoming a fact authority:

- `entityRefId`: stable reference to the owning domain fact;
- `type`: the supported result type used for filtering and dispatch;
- searchable title, body, and auxiliary text: normalized projection text derived from domain facts;
- `occurredAt`: the domain occurrence time used for result chronology;
- `updatedAt`: the source update time used to reason about projection freshness.

A domain write and its projection update occur in the same Room transaction. The projection never writes back to domain facts, and a full rebuild from facts is supported.

The technical proof of concept uses 10,000 mixed Chinese and English records to compare FTS4 with the `unicode61` tokenizer against normalized, paged `LIKE`. The measured rule is correctness first, followed by acceptable paging latency and resource use on the supported device floor. If FTS4 correctness is inadequate, MVP selects paged `LIKE`; the project will not add a server or migrate to Room 3 solely for search. A later ADR records the final choice and its measurements.

## Logical backup

The portable backup is a ZIP with these logical contents:

- `manifest.json`;
- versioned JSONL for accounts, transactions, account entries, category allocations, notes, projects, tags, entity tags, transaction-note associations, attachments, and attachment ownership;
- attachment binaries at `attachments/<sha256>`;
- `transactions.csv`.

The manifest includes `formatVersion`, `databaseSchemaVersion`, `appVersion`, `createdAt`, `backupId`, and `deviceId`, plus the normalized path, cryptographic hash, and byte size of every packaged file.

Generation follows one ordered reliability boundary:

consistent logical read → stream JSONL and attachments → hash each output → write the manifest last → atomically publish the final ZIP.

An incomplete temporary package is never listed as restorable. Before import, the app validates normalized relative paths with no traversal, duplicate entries, size limits, checksums, and format compatibility.

`transactions.csv` is an interoperability artifact only and is never a restore source. It exports every saved transaction exactly once and preserves all user-visible transaction detail values. Its stable interoperability boundary is UTF-8 encoding, one header row, consistent columns, and locale-independent machine-readable date and amount representations. The exact versioned column list belongs to the exporter specification and is not invented here.

## Generation-based overwrite restore

Restore preflight validates the manifest, supported versions, normalized paths, size limits, and hashes. Insufficient temporary storage is a blocking preflight error.

After preflight, the app imports the database and attachment content into an isolated staging generation. It validates record counts, foreign keys, the `EntityRef` XOR invariant, the finance transaction matrix, resulting balances, associations, endpoint ownership, and attachment hashes before any switch.

The storage convention is:

- generation-specific database names;
- `files/vaults/<generationId>/attachments/` for generation-specific content;
- an atomically persisted active-generation pointer.

The current generation remains untouched while staging and validation run. After validation succeeds, the app atomically switches the active-generation pointer. The previous generation remains available for rollback until the new generation starts successfully and post-start validation passes again; cleanup occurs later under a safe retention policy.

Any failure keeps the current generation active and removes the staging generation or marks it for safe cleanup. Restore never exposes a partial replacement. This generation switch is a restore operation, not a database migration.

## CSV portability boundary

CSV export is separate from backup restore. It contains product-complete transaction rows rather than an internal database dump, and MVP does not support selective export. The exact columns may be versioned in a dedicated exporter specification, while the binding UTF-8, single-header, consistent-column, and locale-independent date and amount rules defined above remain stable.

## Explicit exclusions

- No syncing or replacement of a live SQLite database or WAL files.
- Android Auto Backup is not the product's primary backup promise.
- SQLCipher is not enabled by default in MVP.
- There is no cloud sync or record sync.
- Production destructive migration fallback is forbidden.
- Encrypted backup is V1 scope, not MVP.

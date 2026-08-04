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
- Migration tests cover both N-1 and N-2 database versions upgrading to the current version.
- Every migration verifies applicable data invariants, not only the existence of tables.
- Backup `formatVersion` and `databaseSchemaVersion` are separate version axes. A backup format can remain compatible across internal schema changes, and internal schema compatibility does not imply backup-format compatibility.

### Recoverable migration snapshot

Before a new Room schema opens, a migration coordinator blocks writers and creates a recoverable local snapshot using this protocol:

1. Use old-schema-compatible SQLite access to checkpoint the WAL, then close every database handle.
2. Copy the clean main database into an app-private temporary file. Never copy a live database or its WAL files.
3. Open the snapshot read-only, run the SQLite integrity check and the old-schema domain invariant checks, calculate its SHA-256, and atomically rename it into its published snapshot path.
4. Only after the verified snapshot is published may the migration open and change the working database.

If migration, database open, or startup validation fails, the coordinator closes every database handle and atomically restores the verified snapshot before normal use. It then enters an explicit recovery state and does not loop automatic migration retries. The snapshot is retained until the migrated generation has passed one successful startup and the user has produced a newer verified logical backup; only then may grace-period cleanup remove it.

This document intentionally contains no concrete schema, DDL, or Room annotations.

## Attachment state flow

Attachment content uses the following logical states. These are reliability states, not a concrete persistence schema.

| From | Trigger and serialized action | To | Binding result |
|---|---|---|---|
| External source | Copy into an app-private temporary file on the same filesystem as the final content path. | `TEMP` | External paths and filenames are discarded. |
| `TEMP` | Validate declared and decoded MIME type, size, and decodability, then calculate SHA-256. | `VERIFIED` | Invalid content fails without becoming visible. |
| `VERIFIED` | Under per-hash serialization, inspect the content-addressed target. If absent, atomically move the temporary file. If present, verify its actual SHA-256 and size before reuse. | `CONTENT_READY` or `QUARANTINED` | A mismatch is corruption: quarantine and fail; never overwrite it silently. Atomic move occurs only when the target is absent. |
| `CONTENT_READY` | Commit the complete owner save in one Room transaction. | `ACTIVE` | The owner and attachment become visible together. A database failure leaves physical content only as a safe orphan candidate. |
| `ACTIVE` | In the transaction that removes visible links, find zero references under per-hash serialization and mark the attachment for deletion. | `DELETE_PENDING` | Content remains present but cannot race an uncoordinated new link. |
| `DELETE_PENDING` | A linking transaction obtains the same per-hash serialization and either cancels pending deletion before committing the link or waits/fails. | `ACTIVE` | A committed new link always protects the content. |
| `DELETE_PENDING` | After the grace period, the cleaner obtains the same serialization, rechecks zero references, and retries physical deletion idempotently. | `DELETE_PENDING` or physically absent | Failed deletion retains `DELETE_PENDING` metadata for retry. Metadata is deleted only after physical absence is confirmed. |

In MVP, attachment owners are limited to `Transaction` and `Note` EntityRefs. The owner-save Room transaction includes the new or edited owner fact, its required finance children, requested relations, `Attachment` metadata, and every requested `AttachmentLink`; all commit or none commit. The database must never expose a committed link to missing content.

Cleanup and linking serialize per SHA-256. Visible links are removed first, a zero-reference candidate is marked `DELETE_PENDING` in that Room transaction, and the cleaner rechecks zero references under the same serialization before physical deletion. Physical deletion is idempotent, and metadata remains until absence is confirmed. Content is never deleted after a new link commits. Unreferenced physical content left by a failed owner transaction is eligible for orphan cleanup only after verification and the grace period.

## SearchDocument

Each `SearchDocument` contains enough information to identify and render a search result without becoming a fact authority:

- `entityRefId`: stable reference to the owning domain fact;
- `type`: the supported result type used for filtering and dispatch;
- searchable title, body, and auxiliary text: normalized projection text derived from domain facts;
- `occurredAt`: the domain occurrence time used for result chronology;
- `updatedAt`: the source update time used to reason about projection freshness.

A domain write and its projection update occur in the same Room transaction. The projection never writes back to domain facts, and a full rebuild from facts is supported.

The technical proof of concept compares FTS4 with the `unicode61` tokenizer against normalized, paged `LIKE` under a reproducible gate:

- Use a versioned corpus of exactly 10,000 mixed Chinese and English records and a fixed, versioned Chinese, English, and mixed-language query manifest. Every query declares the exact expected entity-ID set and order.
- Record one Android 14 reference device and software configuration with the results. This is the reference configuration, and both candidates run on that same device and configuration.
- Use a fixed page size of 50. For every query and candidate, run 5 warmups followed by 30 measured runs.
- Record p50, p95, and coefficient of variation (`CV`) for response time.
- Correctness is required first. If FTS4 Chinese correctness is inadequate, choose paged `LIKE` for MVP regardless of timing.
- If both FTS4 unicode61 and normalized paged LIKE satisfy correctness, choose the option with more stable response time. Stability is decided by choosing the lower `CV` when the absolute `CV` difference is at least 0.05. Otherwise choose the lower p95 when the relative difference is at least 10%. If still tied, choose paged `LIKE` for lower infrastructure complexity.

The project will not add a server or migrate to Room 3 solely for search. A later ADR records the corpus and query-manifest versions, reference configuration, complete measurements, and final choice.

## Logical backup

The portable backup is a ZIP with these logical contents:

- `manifest.json`;
- versioned JSONL for entity references (`EntityRef`), accounts, categories (`Category`), transactions, account entries, category allocations, notes, projects, tags, entity tags, transaction-note associations, attachments, and attachment ownership;
- attachment binaries at `attachments/<sha256>`.

`transactions.csv` is not embedded in the logical restore ZIP. CSV is a separate, independent export artifact and journey and is never a restore input.

The manifest includes `formatVersion`, `databaseSchemaVersion`, `appVersion`, `createdAt`, `backupId`, and `hashAlgorithm=SHA-256`. It contains one descriptor for every payload entry except `manifest.json` itself. Each descriptor contains `logicalType`, normalized relative `path`, byte `size`, `sha256`, and, for a JSONL dataset, `recordCount`. The manifest contains no device identifier or hardware IDs.

Backup acceptance or rejection for compatibility is determined only by `formatVersion`. `databaseSchemaVersion` is provenance and debugging information; it cannot by itself reject a logically supported backup.

Generation follows one ordered reliability boundary:

consistent logical read → stream and hash JSONL and attachments → write the manifest last → validate the complete package → publish.

Atomic publication applies only in the app-private backup directory: write a temporary package on the same filesystem as the final path, validate it, then atomically rename it.

For a Storage Access Framework destination, first build and validate the complete package in private storage. Copy it to a provider target marked or named as temporary or incomplete. If the provider supports rename, rename it only after the full copy closes and verification succeeds. If rename is unavailable, report success only after the full copy closes and the provider copy verifies successfully; only then clear the app's incomplete marker and treat that verified URI as final. Any partial residue remains marked or named incomplete, is deleted on a best-effort basis, is never reported as success, and is never listed by the app as restorable.

An incomplete temporary package is never listed as restorable. Before import, the app validates `formatVersion`, normalized relative paths with no traversal, duplicate normalized paths, duplicate `logicalType`/`path` pairs, duplicate singleton JSONL `logicalType` values, declared and actual size limits, SHA-256 values, and JSONL record counts.

The restore process does not use `databaseSchemaVersion` as a compatibility gate and does not accept any path, payload, or count that fails validation.

## Generation-based overwrite restore

Restore preflight validates the manifest and every rule above. Insufficient temporary storage is a blocking preflight error.

After preflight, the app imports the database and attachment content into an isolated staging generation. It validates record counts, foreign keys, the `EntityRef` XOR invariant, the finance transaction matrix, resulting balances, associations, endpoint ownership, and attachment hashes before activation.

The storage convention is:

- generation-specific database names;
- `files/vaults/<generationId>/attachments/` for generation-specific content;
- one atomically persisted activation record containing `activeGenerationId`, nullable `previousGenerationId`, nullable `candidateGenerationId`, and `state`, whose only values are `HEALTHY` and `PENDING`. This record is the active-generation pointer; there is no separately persisted pointer.

The activation protocol is:

| Step | Before | Event | Atomically persisted result and required action |
|---|---|---|---|
| 1. Stage | `activeGenerationId=O`, `candidateGenerationId=null`, `state=HEALTHY`; `previousGenerationId` may retain an older rollback generation | A staging candidate is imported and validated. | The activation record is unchanged; the validated staging generation is not yet referenced. |
| 2. Activate pending | Old generation `O` is active; validated candidate is `C`. | Commit one activation-record write. | `activeGenerationId=C`, `previousGenerationId=O`, `candidateGenerationId=C`, `state=PENDING`. |
| 3. Startup gate | `state=PENDING` | The next startup begins. | Revalidate candidate checksums and domain invariants before normal use; do not clear `PENDING` early. |
| 4. Confirm healthy | Candidate startup validation succeeds. | Commit one activation-record write. | Keep `activeGenerationId=C` and `previousGenerationId=O`, clear `candidateGenerationId`, and set `state=HEALTHY`. The previous generation remains for the rollback retention policy. |
| 5. Roll back | Candidate startup validation fails. | Using the candidate and previous IDs from the complete `PENDING` record, commit one rollback-record write. | Restore `activeGenerationId` from `previousGenerationId` to `O`, set `previousGenerationId=null`, `candidateGenerationId=null`, and `state=HEALTHY`; then quarantine `C`. Only after the restored record is durably confirmed may `C` be cleaned. |

Every step is idempotent, including validation recovery, rollback, quarantine, and cleanup. A crash before the activation-record switch leaves the old generation active. A crash after the switch leaves a durable `PENDING` record, so startup repeats validation. Because rollback updates the record atomically, a crash during rollback exposes either the whole new `PENDING` record or the whole restored old `HEALTHY` record, never mixed fields. Cleanup never deletes a generation referenced by `activeGenerationId`, `previousGenerationId`, or `candidateGenerationId`.

Any failure before activation leaves the current generation active and quarantines or marks staging for safe cleanup. Restore never exposes a partial replacement. This generation activation is a restore operation, not a database migration.

## CSV portability boundary

CSV export is a separate independent journey and artifact, never embedded in the logical restore ZIP and never accepted as restore input. It contains product-complete transaction rows rather than an internal database dump, and MVP does not support selective export. It exports every saved transaction exactly once and preserves every user-visible transaction detail value. The exact columns may be versioned in a dedicated exporter specification, while UTF-8 encoding, one header row, consistent columns, and locale-independent machine-readable date and amount representations remain binding.

## Required failure test points

Future fault-injection coverage is required at these boundaries:

- immediately before the pointer write represented by the activation-record write;
- after `PENDING` is durable but before startup validation;
- during startup validation;
- during the rollback-record write;
- during generation cleanup;
- during the attachment atomic move;
- during the owner-save Room transaction;
- during the `DELETE_PENDING` mark transaction;
- during physical attachment deletion;
- during SAF copy and provider rename.

## Explicit exclusions

- No syncing or replacement of a live SQLite database or WAL files.
- Android Auto Backup is not the product's primary backup promise.
- SQLCipher is not enabled by default in MVP.
- There is no cloud sync or record sync.
- Production destructive migration fallback is forbidden.
- Encrypted backup is not included in MVP. Future inclusion requires product approval and an ADR.

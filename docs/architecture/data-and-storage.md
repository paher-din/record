# Data and storage

This document is the stable source of truth for persistence and recovery decisions. Domain identities, relationships, the transaction matrix, and purge invariants remain owned by the [domain model](domain-model.md). Observable backup, restore, CSV, attachment, and offline behavior remains owned by the [MVP requirements](../product/mvp-requirements.md); this document does not duplicate the full product journeys.

## Ownership and reliability principles

- The Room structured database is the source of truth for domain facts.
- Attachment binary content uses content-addressed storage coordinated by `:core:data`.
- `SearchDocument` is a rebuildable projection, never a domain fact.
- The logical backup is the external portability source and is separate from the internal database schema.
- The binding inputs to persistence behavior are the domain transaction matrix, the XOR `EntityRef` subtype invariant, the endpoint whitelist, atomic purge, the versioned catalog name/lifecycle invariants accepted in [ADR 0004](decisions/0004-catalog-lifecycle-and-normalized-names.md), and the canonical attachment identity accepted in [ADR 0005](decisions/0005-canonical-attachment-content.md).

## Room baseline and migrations

- The MVP baseline is Room 2.8.4.
- Room schema JSON is exported and versioned with the repository.
- All schema changes use explicit migrations. Production `fallbackToDestructiveMigration` is forbidden.
- Migration tests cover both N-1 and N-2 database versions upgrading to the current version.
- Every migration verifies applicable data invariants, not only the existence of tables.
- Backup `formatVersion` and `databaseSchemaVersion` are separate version axes. A backup format can remain compatible across internal schema changes, and internal schema compatibility does not imply backup-format compatibility.
- A catalog normalization implementation, Unicode data-table version, pipeline, or `nameKeyVersion` cannot change through an ordinary dependency update. It requires a new ADR plus explicit database and logical-backup migration evidence.

### Catalog persistence contract

The initial catalog persistence model follows [ADR 0004](decisions/0004-catalog-lifecycle-and-normalized-names.md):

- every Category, Tag, and Project fact stores trimmed `displayName`, derived `nameKey`, and `nameKeyVersion` together;
- version 1 derives `nameKey` through Unicode trim → NFKC → full Unicode case fold, independent of locale, and persists the resulting Unicode scalar sequence as UTF-8 text rather than a locale/database-collation result;
- Category and Tag store only `ACTIVE` or `ARCHIVED`; `archivedAt` is absent for `ACTIVE` and present for `ARCHIVED`;
- Project Archive is represented only by its `EntityRef.deletedAt`; Project does not persist a second lifecycle state;
- the initial schema/index contract enforces one active-plus-archived `nameKey` namespace inside each catalog type, while Category, Tag, and Project namespaces remain independent;
- Category has no persisted hierarchy edge in MVP;
- create and rename atomically write all three name fields; Category/Tag Archive/Restore atomically update state and archive metadata; Project Archive/Restore atomically updates the same-ID `EntityRef.deletedAt` without changing primary assignments.

Before M2 begins, the owning spec freezes the concrete version-1 implementation and Unicode data-table version and records the fixed-corpus result. A future normalization change must migrate from a declared source version to a newly accepted version in one recoverable operation. It recomputes and collision-checks the entire affected catalog namespace before publishing any key. Unsupported source versions, generated collisions, or an incomplete name/lifecycle tuple block migration and use the recoverable migration path; no runtime open may silently repair or rewrite keys.

### Recoverable migration snapshot

Before a new Room schema opens, a migration coordinator blocks writers and creates a recoverable local snapshot using this protocol:

1. Use old-schema-compatible SQLite access to checkpoint the WAL, then close every database handle.
2. Copy the clean main database into an app-private temporary file. Never copy a live database or its WAL files.
3. Open the snapshot read-only, run the SQLite integrity check and the old-schema domain invariant checks, calculate its SHA-256, and atomically rename it into its published snapshot path.
4. Only after the verified snapshot is published may the migration open and change the working database.

If migration, database open, or startup validation fails, restore is coordinated by the migration-recovery portion of one app-private activation/recovery record that is readable and atomically writable without Room. Before moving any database, `-wal`, or `-shm` file, the coordinator atomically persists `migrationRecoveryState=MIGRATION_RECOVERY_PENDING` with at least `snapshotId`, `snapshotSha256`, `liveDatabasePath`, `workDatabasePath`, `restoreTempPath`, `quarantinePath`, and `stage`.

Every app startup reads this record before any Room or SQLite helper initialization. A pending record prohibits creating an empty database or opening a live database mixed with stale sidecars. Startup instead resumes the recorded recovery idempotently; normal Room opening remains blocked while the record is pending.

The migration recovery state machine is:

| State and stage | Binding action | Idempotent restart behavior |
|---|---|---|
| `migrationRecoveryState=MIGRATION_HEALTHY` | No migration recovery is active. A detected migration/open/startup failure atomically creates the complete pending recovery portion before any file move. | Either the healthy record or the whole pending record is visible, never partial fields. |
| `migrationRecoveryState=MIGRATION_RECOVERY_PENDING`, `stage=PREPARED` | Reverify the unchanged published snapshot against `snapshotSha256`, then rerun SQLite integrity and old-schema domain invariant validation through read-only access. Block writers and close every database handle before file operations. | Validation can repeat. Failure retains the pending record, keeps Room closed, and enters explicit recovery. |
| `migrationRecoveryState=MIGRATION_RECOVERY_PENDING`, `stage=FAILED_SET_ISOLATED` | Idempotently isolate the failed main database and its `-wal` and `-shm` sidecars under `quarantinePath`; atomically advance `stage` only after the recorded live/work paths can no longer form a mixed set. | Partial isolation is detected from the recorded paths and completed without creating a database. |
| `migrationRecoveryState=MIGRATION_RECOVERY_PENDING`, `stage=RESTORE_COPY_VALIDATED` | Copy the retained snapshot to `restoreTempPath` on the same filesystem as `liveDatabasePath`; verify the copy's SHA-256, SQLite integrity, and old-schema domain invariants. | An existing valid restore copy is reused; an invalid partial copy is replaced from the unchanged published snapshot. |
| `migrationRecoveryState=MIGRATION_RECOVERY_PENDING`, `stage=MAIN_REPLACED` | Atomically replace the main database with the validated restore copy, confirm the installed file's SHA-256, integrity, and domain invariants, then run recovery-startup validation before normal Room initialization. | Repeated replacement first verifies the installed file; a matching valid file advances without replacement, while any incomplete result is repaired from the recorded restore inputs. |
| `migrationRecoveryState=MIGRATION_RECOVERED` | Only after copy validation, atomic replacement, installed-file validation, and recovery-startup validation all succeed may one atomic record update clear pending by changing `migrationRecoveryState` to recovered. A later safe normalization may set `MIGRATION_HEALTHY`. | A restart sees either pending and resumes recovery, or recovered/healthy and may proceed; it never infers success from file presence alone. |

Partial isolation, repeated startup, and repeated replacement are therefore idempotent. Any failure keeps `migrationRecoveryState=MIGRATION_RECOVERY_PENDING`, preserves explicit recovery, and does not open Room. The coordinator does not loop automatic migration retries.

The published snapshot remains unchanged until the migrated generation has passed one successful startup and the user has produced a newer verified logical backup. Only then may grace-period cleanup remove the snapshot and quarantined failed database files.

This document intentionally contains no concrete schema, DDL, or Room annotations.

## Attachment state flow

Attachment content uses the following logical states. These are reliability states, not a concrete persistence schema.

One SHA-256 `contentIdentity` maps globally to exactly one canonical `Attachment` metadata fact and exactly one physical content-addressed blob. The database enforces uniqueness of `Attachment.contentIdentity`; every `AttachmentLink` references that canonical Attachment ID, and multiple Transaction/Note owners may share it. Multiple Attachment rows for one SHA-256 are forbidden.

| From | Trigger and serialized action | To | Binding result |
|---|---|---|---|
| External source | Copy into an app-private temporary file on the same filesystem as the final content path. | `TEMP` | External paths and filenames are discarded. |
| `TEMP` | Validate declared and decoded MIME type, size, and decodability, then calculate SHA-256. | `VERIFIED` | Invalid content fails without becoming visible. |
| `VERIFIED` | Under per-hash serialization, inspect the canonical Attachment fact and content-addressed target. If both are absent, atomically move the temporary file and prepare the canonical zero-link metadata fact with its persisted cleanup grace-period start. If both exist, verify the blob's actual SHA-256 and size and reuse that same metadata identity. | `CONTENT_READY` or `QUARANTINED` | A mismatch, an unexpected metadata/blob split, or conflicting metadata is corruption: quarantine only an unlinked new input and fail; never overwrite or create a second Attachment row silently. Atomic move occurs only when the target is absent. |
| `CONTENT_READY` | Reverify the blob and commit the complete owner save in one Room transaction, clearing the zero-link cleanup marker and adding the owner links with the owner facts. | `ACTIVE` | The owner and attachment become visible together. A database failure leaves zero-link content with its original grace-period start, or physical content only as a safe orphan candidate if metadata preparation did not commit. |
| `ACTIVE` | In the transaction that removes visible links, count every `AttachmentLink` for the canonical Attachment under per-hash serialization. Only when the global count becomes zero may the canonical Attachment enter pending deletion. | `DELETE_PENDING` | Removing one owner while another link remains leaves the shared canonical fact and blob active. Content remains present and cannot race an uncoordinated new link. |
| `DELETE_PENDING` | A linking transaction obtains the same per-hash serialization, rechecks that the target exists, and verifies its actual SHA-256 and size. Only successful verification may cancel pending deletion and commit the link. If the target is missing, the content must re-enter `VERIFIED` → `CONTENT_READY` from a validated app-private temporary file, or linking fails. | `ACTIVE`, `CONTENT_READY`, or unchanged | A committed new link always references reverified content. |
| `DELETE_PENDING` | After the grace period, the cleaner obtains the same per-hash serialization, rechecks zero references, retries physical deletion idempotently, confirms physical absence, and retains serialization until the metadata-deletion Room transaction commits. | `DELETE_PENDING` or physically absent with metadata removed | Failed deletion retains `DELETE_PENDING` metadata for retry. A retry after deletion but before metadata commit observes absence and completes metadata deletion. |

In MVP, attachment owners are limited to `Transaction` and `Note` EntityRefs. The owner-save Room transaction includes the new or edited owner fact, its required finance children, requested relations, `Attachment` metadata, and every requested `AttachmentLink`; all commit or none commit. The database must never expose a committed link to missing content.

Cleanup and linking serialize per SHA-256. Per [ADR 0008](decisions/0008-zero-link-attachment-cleanup-marker.md), a newly prepared zero-link canonical Attachment starts cleanup-pending with its ingestion time as the grace-period start; a fresh successful ingestion that reuses valid zero-link content refreshes that start so the reported preparation always has a full grace period. Visible links are removed first, and a canonical Attachment whose complete global link count reaches zero is marked `DELETE_PENDING` with the unlink time in that Room transaction. The cleaner rechecks all links to that canonical ID under the same serialization before physical deletion and holds that serialization through physical-absence confirmation and canonical metadata-deletion transaction commit. A concurrent relink cancels or waits for pending deletion and reuses the same canonical ID after byte verification. Physical deletion is idempotent, and metadata remains until absence is confirmed. A zero-link pending row whose blob is already absent is therefore an expected cleanup crash window, not corruption; a linked or active row whose blob is absent is corruption. Content is never deleted after a new link commits. Unreferenced physical content left by a failed owner transaction is eligible for orphan cleanup only after verification and the grace period.

Quarantine is limited to a newly ingested temporary file or a corrupt content-addressed target with zero active links. A target referenced by any active link is never moved or quarantined; corruption is reported and the operation fails without relocating that file.

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
- Before any measured run, the P4 spec records and freezes the actual reference device or AVD profile, API 34 system build, CPU and RAM, and, when an AVD is used, host CPU and RAM. Missing or changed reference configuration makes the run invalid and fails P4 until the spec records a new approved baseline.
- Use a fixed page size of 50. For every query and candidate, run 5 warmups followed by 30 measured runs.
- Record p50, p95, and coefficient of variation (`CV`) for response time.
- A candidate is eligible only when it returns 100% of every fixed expected entity-ID set in the exact expected order and its measured first-page query p95 is at most 300 ms. Correctness and the absolute p95 budget are both hard gates; a candidate that fails either cannot be selected.
- If neither candidate is eligible, P4 fails and no implementation candidate is selected. If exactly one candidate is eligible, select it. This includes the case where FTS4 Chinese correctness is inadequate but paged `LIKE` is 100% correct and meets the 300 ms budget.
- If both candidates pass the absolute gates, choose the option with more stable response time. Stability is decided by choosing the lower `CV` when the absolute `CV` difference is at least 0.05. Otherwise choose the lower p95 when the relative difference is at least 10%. If still tied, choose paged `LIKE` for lower infrastructure complexity.

The project will not add a server or migrate to Room 3 solely for search. A later ADR records the corpus and query-manifest versions, reference configuration, complete measurements, and final choice.

## Logical backup

The portable backup is a ZIP with these logical contents:

- `manifest.json`;
- versioned JSONL for entity references (`EntityRef`), accounts, categories (`Category`), transactions, account entries, category allocations, notes, projects, tags, entity tags, transaction-note associations, canonical attachments, and attachment ownership;
- attachment binaries at `attachments/<sha256>`.

Catalog logical records are complete rather than display-only:

- Category and Tag records contain identity, `displayName`, `nameKey`, `nameKeyVersion`, `state`, and nullable `archivedAt`;
- Project records contain identity, `displayName`, `nameKey`, and `nameKeyVersion`, while the matching `EntityRef.deletedAt` is their archive metadata;
- Category records contain no hierarchy/parent field in MVP;
- `CategoryAllocation`, `EntityTag`, and Transaction/Note primary-project references preserve their target IDs even when the target catalog item is archived.

This preserves current names, lifecycle states, archive metadata, and every historical reference across export and restore. Availability rules that prevent a new allocation or assignment to an archived item do not delete or invalidate those historical references.

`transactions.csv` is not embedded in the logical restore ZIP. CSV is a separate, independent export artifact and journey and is never a restore input.

The manifest includes `formatVersion`, `databaseSchemaVersion`, `appVersion`, `createdAt`, `backupId`, `hashAlgorithm=SHA-256`, and the sorted set `catalogNameKeyVersions` summarizing versions used by catalog records; the set is empty when the backup contains no catalog records. It contains one descriptor for every payload entry except `manifest.json` itself. Each descriptor contains `logicalType`, normalized relative `path`, byte `size`, `sha256`, and, for a JSONL dataset, `recordCount`. The manifest summary does not repeat per-record catalog content; every catalog record remains self-describing through its own `nameKeyVersion`. The manifest contains no device identifier or hardware IDs.

`formatVersion` determines structural backup-format compatibility. Within a structurally supported format, every declared and encountered `nameKeyVersion` is an independent required sub-contract and must be implemented by the restore reader. `databaseSchemaVersion` remains provenance and debugging information; it cannot by itself reject a logically supported backup.

Generation follows one ordered reliability boundary:

consistent logical read → stream and hash JSONL and attachments → write the manifest last → validate the complete package → publish.

Atomic publication applies only in the app-private backup directory: write a temporary package on the same filesystem as the final path, validate it, then atomically rename it.

For a Storage Access Framework destination, first build and validate the complete package in private storage. Copy it to a provider target marked or named as temporary or incomplete. If the provider supports rename, rename it only after the full copy closes and verification succeeds. If rename is unavailable, report success only after the full copy closes and the provider copy verifies successfully; only then clear the app's incomplete marker and treat that verified URI as final. Any partial residue remains marked or named incomplete, is deleted on a best-effort basis, is never reported as success, and is never listed by the app as restorable.

An incomplete temporary package is never listed as restorable. Before import, the app validates `formatVersion`, every manifest-declared catalog normalization version, normalized relative paths with no traversal, duplicate normalized paths, duplicate `logicalType`/`path` pairs, duplicate singleton JSONL `logicalType` values, declared and actual size limits, SHA-256 values, and JSONL record counts. It also requires exactly one canonical Attachment record and one matching blob for each referenced SHA-256, forbids duplicate Attachment rows sharing a `contentIdentity`, verifies every link targets the canonical ID, and rejects missing, extra, mismatched, or corrupt attachment content. During streaming, the encountered per-record `nameKeyVersion` set must exactly match the manifest summary; an omitted, extra, or unsupported version rejects the candidate.

### Restore preflight resource limits

The following are binding MVP input limits. They protect the current vault from malicious or malformed ZIP files and are not deferred to a future restore spec:

- at most 100,000 ZIP entries, including `manifest.json`;
- each normalized relative path is at most 240 bytes in UTF-8 and must be relative; reject absolute paths, traversal segments, duplicate normalized paths, and duplicate normalized `logicalType`/`path` pairs;
- each attachment payload is at most 512 MiB uncompressed;
- each structured-data entry, including JSONL, is at most 1 GiB uncompressed;
- total uncompressed bytes across all ZIP entries, including `manifest.json`, are at most `min(20 GiB, available app-private staging space - 1 GiB safety reserve)`; if the space-derived value is zero or negative, preflight rejects the backup before import;
- each non-empty entry and the non-empty archive as a whole have an uncompressed-to-compressed ratio of at most 200:1. A zero-uncompressed-byte entry has ratio zero and is allowed when otherwise valid. A non-empty payload with a zero compressed-byte denominator is rejected; an archive containing only zero-byte payloads has ratio zero.

Preflight enforces these limits twice. The declaration pass combines the manifest descriptors with ZIP-directory metadata: it counts declared entries and normalized UTF-8 path bytes, uses manifest `size` plus ZIP-declared uncompressed/compressed sizes, and checks per-entry ratios and aggregate values before staging. Streaming import independently counts actual entry count and actual compressed and uncompressed bytes without trusting manifest or ZIP headers. Actual streamed bytes may never exceed the manifest or ZIP-declared size, the per-entry limit, the aggregate limit, or the available-space budget; crossing any limit fails immediately, closes the stream, leaves the current vault unchanged, reports no restore success, and leaves temporary staging content identifiable for safe cleanup. A declared value below actual bytes is corruption, not a reason to expand the budget.

The restore process does not use `databaseSchemaVersion` as a compatibility gate and does not accept any path, payload, count, catalog normalization version, or catalog invariant that fails validation.

## Android system backup exclusion

MVP excludes the app database, attachment content, activation and migration-recovery state, diagnostics, preferences containing user state, and all other user data from Android cloud backup and system device-to-device transfer. Android system backup is neither a fallback nor a secondary migration path.

When Android code exists, the application must configure API 34 `android:dataExtractionRules`, compatible `android:fullBackupContent` exclusion rules, and manifest backup policy together. The manifest sets `android:allowBackup="false"` as the baseline denial, while both rule files explicitly exclude every applicable app-managed domain from cloud backup and device transfer; the implementation must not rely on `allowBackup` alone where platform or OEM transfer behavior can differ. Source manifest, merged manifest, and final APK policy verification are release requirements.

The only supported user migration path is the app's explicit complete logical backup and overwrite restore through a user-selected Storage Access Framework destination. CSV, Android cloud backup, system transfer, and copying live SQLite/WAL files are not supported restore paths.

## Generation-based overwrite restore

Restore preflight has both the manifest/resource pass above and a record-level candidate pass. Insufficient temporary storage is a blocking preflight error. The record-level pass imports database and attachment content into an isolated, non-active staging generation before overwrite confirmation is enabled. It validates record counts, foreign keys, the `EntityRef` XOR invariant, the finance transaction matrix, resulting balances, associations, endpoint ownership, attachment hashes, and the complete catalog contract. The current generation remains untouched. Final confirmation authorizes activation of that exact validated candidate; it does not bypass or postpone candidate validation. If the selected bytes or validation context change, preflight must run again.

Catalog staging validation is mandatory and ordered before the activation-record switch:

1. Every persisted `displayName` is already Unicode-trimmed and contains 1–40 Unicode code points.
2. Every Category is first-level and contains no hierarchy/parent data.
3. Every `nameKeyVersion` is declared by the manifest and supported by the current reader.
4. The reader recomputes the key using the exact implementation and Unicode table for that declared version and requires an exact match with persisted `nameKey`.
5. Recomputed keys are unique across active and archived items within each catalog type; Category, Tag, and Project are checked as three independent namespaces.
6. Category and Tag have only valid `ACTIVE`/`ARCHIVED` states, with `archivedAt` absent/present consistently. Project archive state is represented only by matching `EntityRef.deletedAt`, without a contradictory parallel state.
7. Every Category allocation, Tag association, and primary-project assignment targets an existing catalog fact, including when that target is archived. Historical archived references are valid; the active-only availability rule applies to new writes, not restore of history.
8. Current names, states, archive metadata, and all historical reference endpoints match the exported logical records exactly.

Any empty/overlong name, unsupported or mismatched key/version, per-type canonical duplicate, invalid state/archive metadata, non-first-level Category, contradictory Project lifecycle, or dangling catalog reference rejects preflight for the complete candidate before overwrite confirmation and activation. Rejection leaves the current vault unchanged, reports no restore success, and leaves staging identifiable for deterministic safe cleanup. No invalid catalog subset can be skipped, repaired implicitly, or partially restored.

The storage convention is:

- generation-specific database names;
- `files/vaults/<generationId>/attachments/` for generation-specific content;
- one app-private, atomically persisted activation/recovery record. Its generation-activation portion contains `activeGenerationId`, nullable `previousGenerationId`, nullable `candidateGenerationId`, nullable `retiringGenerationId`, and `state`, whose only values are `HEALTHY` and `PENDING`. Its independent `migrationRecoveryState` is defined above and is checked first at startup. This record is the active-generation pointer and durable cleanup intent; there is no separately persisted pointer.

Every startup resolves the migration gate and then the generation-activation gate before Room or SQLite opens the active generation. A safe startup may also scan generation directories: it must never delete a directory named by `activeGenerationId`, `previousGenerationId`, `candidateGenerationId`, or `retiringGenerationId`; complete or incomplete directories referenced by no pointer are orphan candidates and are cleaned idempotently under the same per-generation exclusion used by restore. Normal active reads are allowed while only retiring cleanup is pending, but a new restore is not.

The activation protocol is:

| Step | Before | Event | Atomically persisted result and required action |
|---|---|---|---|
| 0. Preflight maintenance | `state=HEALTHY` | A restore is requested. | Require `previousGenerationId=null`, `candidateGenerationId=null`, and `retiringGenerationId=null`. If retiring cleanup is pending, first run it idempotently. A transient cleanup failure returns a typed maintenance error and blocks this restore without affecting reads from `activeGenerationId`; no pointer may be overwritten. |
| 1. Stage | `activeGenerationId=O`, all three nullable generation fields are null, `state=HEALTHY` | A staging candidate is imported and validated. | The activation record is unchanged; the validated staging generation is not yet referenced. |
| 2. Activate pending | Old generation `O` is active; validated candidate is `C`. | Commit one activation-record write. | `activeGenerationId=C`, `previousGenerationId=O`, `candidateGenerationId=C`, `retiringGenerationId=null`, `state=PENDING`. |
| 3. Startup gate | `state=PENDING` | The next startup begins. | Revalidate candidate checksums, catalog name/lifecycle/version rules, historical catalog references, and all other domain invariants before normal use; do not clear `PENDING` early. |
| 4. Confirm healthy | Candidate startup validation succeeds. | Commit one activation-record write. | Keep `activeGenerationId=C`, set `retiringGenerationId=O`, clear `previousGenerationId` and `candidateGenerationId`, and set `state=HEALTHY`. This single transition makes the healthy candidate user-visible and persists cleanup intent without retaining a stale rollback pointer. |
| 5. Roll back | Candidate startup validation or candidate startup fails. | Using `C` and `O` from the complete `PENDING` record, commit one rollback-record write. | Restore `activeGenerationId=O`, set `retiringGenerationId=C`, clear `previousGenerationId` and `candidateGenerationId`, and set `state=HEALTHY`. The original generation is healthy before the failed candidate becomes cleanup-eligible. |
| 6. Retire | `state=HEALTHY`, `retiringGenerationId=R` | Run synchronous preflight maintenance or deferred maintenance. | Under a per-generation lock and with every handle to `R` closed, re-read the record and require `R` differs from all active/previous/candidate pointers. Delete `R` or confirm it is absent, then atomically clear `retiringGenerationId`. |

Every step is idempotent, including validation recovery, rollback, quarantine, orphan scanning, and retirement. A crash before the activation-record switch leaves the old generation active. A crash after the switch leaves a durable `PENDING` record, so startup repeats validation. Because confirm and rollback each update the record atomically, a crash exposes either the whole `PENDING` record or the whole resulting `HEALTHY` record, never mixed fields. A crash before retirement deletion retries deletion; a crash after deletion but before clearing the pointer observes absence and completes the clear. Cleanup never deletes a generation referenced by any activation-record generation field.

The restore UI may report terminal success after the new active generation is healthy; retirement is non-user-blocking safety maintenance. AndroidX WorkManager may retry that idempotent retirement after normal startup, but correctness never depends on it: startup gates resolve pending activation synchronously, and the next restore preflight must finish or fail retirement before staging. An unresolved retirement cannot be overwritten by another restore.

Any failure before activation leaves the current generation active and quarantines or marks staging for safe cleanup. Restore never exposes a partial replacement. This generation activation is a restore operation, not a database migration.

## CSV portability boundary

CSV export is a separate analytical/interoperability journey and artifact, never embedded in the logical restore ZIP and never accepted as restore input. It contains product-complete transaction rows rather than an internal or forensic database dump, and MVP does not support selective export. It exports every effective, non-soft-deleted Transaction exactly once, including effective opening balances, income, expense, and transfers; soft-deleted transactions are excluded. The complete logical backup, not CSV, preserves soft-deleted history for full restoration. Every exported row preserves the corresponding user-visible transaction detail values. The exact columns may be versioned in a dedicated exporter specification, while UTF-8 encoding, one header row, consistent columns, and locale-independent machine-readable date and amount representations remain binding.

## Required failure test points

Future fault-injection coverage is required at these boundaries:

- immediately before the pointer write represented by the activation-record write;
- after `PENDING` is durable but before startup validation;
- during startup validation;
- during the rollback-record write;
- immediately before and after each successful-validation and rollback transition that creates `retiringGenerationId`;
- before retirement deletion, after deletion but before pointer clear, and during pointer clear;
- while a new restore preflight encounters pending retirement;
- during startup orphan scanning, including complete and incomplete unreferenced directories and every pointer-protected generation;
- during the attachment atomic move;
- during the owner-save Room transaction;
- during the `DELETE_PENDING` mark transaction;
- during physical attachment deletion;
- after physical attachment deletion but before the metadata-deletion transaction commits;
- while two owners share one canonical attachment, one owner unlinks, cleanup races a relink, and the remaining owner stays linked;
- after `MIGRATION_RECOVERY_PENDING` is persisted but before the first database or sidecar move;
- immediately before and after atomic migration-snapshot replacement;
- during interruption of failed-database `-wal` and `-shm` sidecar isolation or cleanup;
- during SAF copy and provider rename.
- while recomputing catalog keys, checking name-key versions/uniqueness, validating lifecycle metadata, or resolving historical catalog references in staging and startup revalidation.

For every migration-recovery fault injection, the expected result is the same: startup creates no empty database, opens no mixed database/sidecar set, automatically resumes the recorded recovery idempotently, and leaves the published snapshot unchanged.

## Explicit exclusions

- No syncing or replacement of a live SQLite database or WAL files.
- Android cloud backup and system device-to-device transfer are disabled for all app user data; the app does not participate in either as a migration path.
- SQLCipher is not enabled by default in MVP.
- There is no cloud sync or record sync.
- Production destructive migration fallback is forbidden.
- Encrypted backup is not included in MVP. Future inclusion requires product approval and an ADR.

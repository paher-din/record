# Tech PoC P3 — Attachment Failure Recovery

- **Status:** `completed` — read-only history; later corrections use a new specification or ADR.
- **Spec approved:** 2026-08-08 — the user explicitly accepted the original spec after three passes of two-axis code review (15 findings closed).
- **Revision 1 approved:** 2026-08-08 — during plan validation, the user selected option A: a durably prepared zero-link canonical row carries `deletedAt = ingestedAt` until a verified owner link atomically clears it. The same revision pins declared-MIME input and expected cleanup-window classification without widening schema v2.
- **Plan approved:** 2026-08-08 — the user explicitly approved `plan.md` Revision 1; recorded here per the roadmap lifecycle rules. Revision 2 (the `ADOPT_VERIFIED_BLOB` decision-table correction) was user-confirmed on 2026-08-08 after code review.
- **Completed:** 2026-08-08 — all eleven acceptance criteria of section 5.2 and the roadmap P3 exit gate have verification evidence in `verification.md` (101 device tests, JVM suites, machine checks, `clean check`, launch regression); the user confirmed acceptance after a two-axis code review of the implementation and the follow-up fixes.
- **Drafted:** 2026-08-08
- **Roadmap:** [Tech PoC P3](../../../roadmap.md)
- **Domain facts and invariants:** [Domain model](../../../architecture/domain-model.md) (canonical attachment content, `Attachment`/`AttachmentLink`, owner endpoints)
- **Product behavior:** [MVP requirements](../../../product/mvp-requirements.md) (Journey 4 note attachments, Journey 7 picker attachment)
- **Persistence and recovery policy:** [Data and storage](../../../architecture/data-and-storage.md) (attachment state flow, recoverable migration snapshot protocol)
- **Canonical attachment identity decision:** [ADR 0005](../../../architecture/decisions/0005-canonical-attachment-content.md)
- **Zero-link cleanup-marker decision:** [ADR 0008](../../../architecture/decisions/0008-zero-link-attachment-cleanup-marker.md)
- **Identity and endpoint rules:** [ADR 0002](../../../architecture/decisions/0002-entityref-and-strong-relations.md)
- **Version-1 persistence contract:** [ADR 0006](../../../architecture/decisions/0006-version-1-persistence-contract.md)
- **Module boundaries:** [Architecture overview](../../../architecture/overview.md)
- **Verification policy:** [Testing strategy](../../../engineering/testing-strategy.md) (`ATTACHMENT_SOURCE_COPY`, `ATTACHMENT_CONTENT_COMMIT` mappings, plus the applicable `MIGRATION_RECOVERY` evidence of criterion 10)
- **Predecessor:** [Tech PoC P2](../p2-atomic-transfer/spec.md) — `completed`; P3 builds on the verified v1 schema and the P2 repository surface, and is the first schema-changing slice.

## 1. Purpose

Prove validated image ingestion, content deduplication, retry-safe cleanup, and owner-level atomic visibility under interruption, on the first schema change (v1 → v2). Fault tests cover every required copy, validation/hash, atomic move, owner commit, deletion, relink, and cleanup fail point; no committed link may reference missing or unverified bytes; duplicate content is reused safely; and orphan cleanup is idempotent. The `ATTACHMENT_SOURCE_COPY` and `ATTACHMENT_CONTENT_COMMIT` evidence mappings of the testing strategy are the attachment fault-recovery gate for the storage side; the mapping's logical-backup round-trip clause is owned by P5, recorded as an explicit deviation in section 4.8.

P3 is the first slice that opens a new Room schema. Per [data and storage](../../../architecture/data-and-storage.md), the recoverable-migration-snapshot protocol is binding before any schema change; section 4.6 resolves the phase-ownership decision registered in the P1 plan through a new ADR and establishes the coordinator's minimal complete implementation. P3 also proves the applicable `MIGRATION_RECOVERY` evidence for its own v1 → v2 migration; the full N-1/N-2 fault matrix remains owned by M7.

P3 proves the attachment lifecycle with a minimal typed surface. It does not add the editor, picker, thumbnail, or any UI: the MVP picker journey (Journey 7) and its validation UX belong to M3. P3 proves the storage-level reliability states the product journey depends on.

## 2. Reference environment

The reference environment remains the frozen P0 configuration, unchanged from P1/P2. Running instrumented tests on a different configuration makes the run invalid and requires an explicit approval record of a new baseline.

| Field | Recorded value |
|---|---|
| Device | Xiaomi 14 Pro (physical device, USB debugging) |
| Android baseline | Android 14 / API 34 (out-of-box baseline) |
| Actual system build | Recorded in `verification.md` at P3 verification (currently Android 16 / API 36 per P0–P2; recorded, not rejected, because `minSdk 34` keeps the app compatible) |
| AVD / host CPU-RAM fields | Not applicable — physical device reference |

## 3. Build baseline

- `minSdk = 34`, `targetSdk = 34`, `compileSdk = 36` — unchanged from P0–P2.
- Room 2.8.4, KSP 2.3.9, AGP 9.3 (built-in Kotlin 2.2.10), and the `androidx.room` Gradle plugin with the committed schema directory are already pinned and verified; P3 reuses the P1/P2 combination and upgrades `databaseVersion` to 2 with the committed `2.json` export.
- **No new dependencies are anticipated.** SHA-256 uses JDK `java.security.MessageDigest`, file operations use platform `java.nio.file`/`File`, and the property-test generator is hand-rolled; any dependency that the plan does introduce must first pass the engineering conventions' dependency review record at plan approval.
- `fallbackToDestructiveMigration` remains forbidden and must not appear anywhere in the build.
- The seven-module graph is unchanged; the P0 `checkModuleDependencies` task must keep passing.

## 4. Behavior scope

### 4.1 Attachment state flow proved by P3

P3 implements and proves the attachment reliability states of [data and storage](../../../architecture/data-and-storage.md) as an executable pipeline over the public repository surface and internal components. The states are reliability states, not a concrete persistence schema; P3 persists only the minimal state the lifecycle requires (section 4.6).

| From | Trigger and serialized action | To | P3 proof |
|---|---|---|---|
| External source | Accept the external bytes together with their declared MIME type, then copy into an app-private temporary file on the same filesystem as the final content path. | `TEMP` | `ATTACHMENT_SOURCE_COPY` — external paths and filenames are discarded; every interruption leaves no link, no success result, and an unchanged owner (criterion 1). |
| `TEMP` | Validate declared and decoded MIME type against the P3 image-type set, size against the recorded P3 limit, decodability, and then calculate SHA-256. | `VERIFIED` | Invalid content fails without becoming visible (criterion 2). |
| `VERIFIED` | Under per-hash serialization, inspect the canonical Attachment fact and content-addressed target. If both are absent, atomically move the temporary file and commit the canonical zero-link metadata row with `deletedAt = ingestedAt`; this is the persisted representation of durably prepared `CONTENT_READY` content and starts its cleanup grace period. If both exist, verify the blob's actual SHA-256 and size and reuse that same metadata identity. | `CONTENT_READY` or `QUARANTINED` | A mismatch, an unexpected metadata/blob split, or conflicting metadata is corruption: quarantine only an unlinked new input and fail; never overwrite or create a second Attachment row silently. Atomic move occurs only when the target is absent (criteria 3, 4, 7). |
| `CONTENT_READY` | Under the same per-hash serialization, reverify the blob and commit the complete owner save in one Room transaction; the transaction clears `deletedAt` and inserts every requested `AttachmentLink` together with the owner facts. | `ACTIVE` | The owner and attachment become visible together. A database failure leaves the zero-link canonical row marked with its original grace-period start, or a moved-but-uncommitted blob as a safe orphan candidate if metadata creation failed (criterion 4). |
| `ACTIVE` | In the transaction that removes visible links, count every `AttachmentLink` for the canonical Attachment under per-hash serialization. Only when the global count becomes zero may the canonical Attachment enter pending deletion. | `DELETE_PENDING` | Removing one owner while another link remains leaves the shared canonical fact and blob active (criterion 5). |
| `DELETE_PENDING` | A linking transaction obtains the same per-hash serialization, rechecks that the target exists, and verifies its actual SHA-256 and size. Only successful verification may cancel pending deletion and commit the link. If the target is missing, the content must re-enter `VERIFIED` → `CONTENT_READY` from a validated app-private temporary file, or linking fails. | `ACTIVE`, `CONTENT_READY`, or unchanged | A committed new link always references reverified content (criterion 6). |
| `DELETE_PENDING` | After the grace period, the cleaner obtains the same per-hash serialization, rechecks zero references, retries physical deletion idempotently, confirms physical absence, and retains serialization until the metadata-deletion Room transaction commits. | `DELETE_PENDING` or physically absent with metadata removed | Failed deletion retains `DELETE_PENDING` for retry; a retry after deletion but before metadata commit observes absence and completes metadata deletion (criterion 5). |

The owner-save Room transaction includes the new owner fact, its finance children, requested relations, the canonical `Attachment` metadata transition (`deletedAt` clear for zero-link prepared content), and every requested `AttachmentLink`; all commit or none commit. The database must never expose a committed link to missing content. Cleanup and linking serialize per SHA-256, and the cleaner holds serialization through physical-absence confirmation and the canonical metadata-deletion transaction commit.

A zero-link canonical row with non-null `deletedAt` and a present verified blob is expected `CONTENT_READY`/cleanup-pending state, not corruption. A fresh successful ingestion that reuses this row refreshes `deletedAt = ingestedAt`, so content just reported as prepared always receives a full grace period. A zero-link pending row whose blob is already absent is the expected crash window after physical deletion and before metadata deletion; only the cleaner may complete that metadata deletion. By contrast, a row with any committed link or with `deletedAt == null` whose blob is missing is a `METADATA_BLOB_SPLIT` corruption. A link request without validated replacement bytes returns the typed content-not-available rejection; a fresh successful ingestion of the same bytes may restore the pending zero-link target under the per-hash serialization before linking.

In MVP, attachment owners are limited to `Transaction` and `Note` EntityRefs (ADR 0002). P3 links owners through the existing `saveTransactionAggregate` and `saveNoteAggregate` create entry points — both are part of the pinned `:core:data` surface since P1 — and proves that link-management entry points reject any non-Transaction/Note endpoint.

### 4.2 Content deduplication

One SHA-256 `contentIdentity` maps globally to exactly one canonical `Attachment` metadata fact and exactly one physical content-addressed blob (ADR 0005). The persistence model enforces a unique constraint on `Attachment.contentIdentity`; multiple Attachment rows sharing one SHA-256 are invalid. Every `AttachmentLink` references the canonical Attachment ID, and any number of valid Transaction/Note owners may share it.

Import is serialized per SHA-256. A duplicate import must verify the existing blob's actual hash and size, reuse the existing canonical metadata identity, and add only the requested owner link. If metadata exists without valid bytes, bytes exist with conflicting metadata, or verification produces a mismatch, the operation reports typed corruption and never silently overwrites the canonical content or creates a second row. A newly ingested unlinked temporary file may be quarantined; linked canonical content is not relocated as a repair attempt.

### 4.3 Owner-level atomic visibility

A `Transaction` and its requested `AttachmentLink`s commit in one Room transaction; failure leaves neither the owner nor any link committed, and the pre-existing owner state remains exactly unchanged. Physical content already staged by ingestion is then either a zero-link canonical row carrying its original `deletedAt = ingestedAt` grace-period start or a moved-but-uncommitted orphan whose app-private file timestamp is its fail-closed grace-period input. It never becomes visible as an attachment and never changes the owner's earlier state.

### 4.4 Unlink, deletion, and relink

- Removing an owner's link removes only the ownership relation in the link-management transaction and never directly deletes attachment physical content.
- Unlinking the last owner of a canonical Attachment marks it `DELETE_PENDING` in that same transaction; unlinking while another owner remains linked leaves it active.
- Physical deletion is idempotent; metadata remains until physical absence is confirmed. A retry after deletion but before the metadata-deletion commit observes absence and completes the metadata deletion.
- A concurrent relink waits on the per-hash serialization, rechecks zero-reference state and blob identity, verifies the actual bytes, cancels pending deletion, and reuses the same canonical ID. Content is never deleted after a new link commits.
- Relinking content whose blob is missing re-enters `VERIFIED` → `CONTENT_READY` from a validated app-private temporary file, or linking fails; it never links to missing bytes.

### 4.5 Orphan cleanup and quarantine

- A partial `TEMP` copy, a blob moved but never committed to an owner, and unreferenced physical content left by a failed owner transaction are each deterministically classified for safe cleanup or retry.
- Orphan cleanup runs only after verification and the grace period, is idempotent, and never deletes a blob with any active `AttachmentLink` or a canonical metadata fact that still references committed bytes. A missing or invalid orphan timestamp fails closed and retains the residue for explicit recovery rather than deleting it.
- Quarantine is limited to a newly ingested temporary file or a corrupt content-addressed target with zero active links. A target referenced by any active link is never moved or quarantined; corruption is reported and the operation fails without relocating that file.
- The grace period uses an injectable clock so tests control time deterministically.

### 4.6 Schema v2 and migration boundary

P3 is the first schema-changing slice and resolves the open decision registered in the P1 plan (phase ownership of the recoverable-migration-snapshot machinery). The spec proposes that **P3 establishes the coordinator**: [data and storage](../../../architecture/data-and-storage.md) unconditionally requires the recoverable-migration-snapshot protocol before a new Room schema opens, and P3 is that first opening. A new ADR 0007 records this decision, the rejected option (deferring the coordinator past the first schema change), the evidence requirements, and the M7 boundary: the full N-1/N-2 migration matrix and the complete `MIGRATION_RECOVERY` fault suite remain M7's, while P3 carries the applicable evidence for its own v1 → v2 migration. ADR 0007 is created and accepted before P3 moves `in-progress`, and the ADR — not this draft — is the owning record of the decision.

Schema v2:

- `databaseVersion` advances 1 → 2 with an explicit `Migration(1, 2)` that creates exactly two tables and changes nothing in the six v1 tables; `fallbackToDestructiveMigration` stays forbidden.
- `Attachment` — `id` (PK, app-generated random UUID string), `contentIdentity` (globally unique), nullable `deletedAt` (epoch-millis UTC `Long`, absent while `ACTIVE`; present means zero-link cleanup-pending and is the grace-period start). New `CONTENT_READY` rows are created with `deletedAt = ingestedAt`; last unlink sets it to the unlink time; a verified owner-link transaction clears it. The column follows the v1 timestamp/absent-while-active convention of ADR 0006; it is a reliability state per [data and storage](../../../architecture/data-and-storage.md), not a domain-diagram field, and is the only state P3 persists beyond the domain fields.
- `AttachmentLink` — composite PK `(attachmentId, ownerEntityRefId)`, both columns FKs, `NO ACTION` actions unchanged from ADR 0006.
- The v2 index set adds `AttachmentLink(ownerEntityRefId)` (owner-direction queries). No other index.
- `Attachment.contentIdentity` uniqueness is enforced by the database unique constraint; a second Attachment row for one SHA-256 is rejected by the database layer before the application can observe it.
- The committed schema exports gain `2.json`; `1.json` stays committed and unchanged as the migration source contract.

The coordinator implements the [data and storage](../../../architecture/data-and-storage.md) protocol: checkpoint and close every handle, copy the clean main database to an app-private snapshot, verify integrity and old-schema invariants, hash, atomically publish, then migrate. The `migrationRecoveryState` record is readable and atomically writable without Room, and every startup resolves it before any Room or SQLite initialization. P3 proves the applicable recovery evidence: a failed migration or interruption after `MIGRATION_RECOVERY_PENDING` resumes idempotently from the published snapshot and never creates an empty database or a mixed database/sidecar set. The startup gate itself remains the existing P1/P2 open-and-resolve flow extended by the recovery record.

Core-document sync (documentation-first, before implementation): ADR 0008 records the zero-link cleanup-marker decision; data and storage plus the domain model define the `deletedAt` reliability meaning; and the roadmap/AGENTS/index candidate wording is updated as P3 enters the lifecycle. No other core fact changes.

The `deletedAt` column reuses the ADR 0006 timestamp convention (epoch-millis UTC `Long`, absent while active) with the reliability meaning defined by data and storage: present marks any zero-link canonical content as cleanup-pending and is the grace-period start, including newly ingested `CONTENT_READY` content and last-unlinked content. It is not a domain soft-delete transition and no read surface treats it as one.

### 4.7 Typed surface extension

The P1/P2 public surface is unchanged except for additive attachment operations:

- **Ingestion:** `ingestAttachment(source, declaredMimeType)` receives the declared MIME explicitly, copies an external file into app-private `TEMP`, validates, hashes, deduplicates under per-hash serialization, moves atomically, commits or reuses the zero-link cleanup-pending canonical row, and returns the canonical `contentIdentity`. Outcomes are typed: success carries the identity; failures carry a precise classification — invalid content (undecodable, size over the P3 limit, declared/decoded MIME type outside the P3 image-type set, unknown identity, or content no longer available), corruption (unexpected metadata/blob split, hash/size mismatch, conflicting metadata), or IO failure at a named stage. No outcome reports success when nothing was durably prepared.
- **Owner save extension:** the existing `saveTransactionAggregate` and `saveNoteAggregate` create entry points (both pinned since P1) accept requested attachments by `contentIdentity`; under per-hash serialization the owner transaction resolves each identity to its canonical Attachment row, verifies the committed blob, clears its cleanup marker, and writes the `AttachmentLink`. Unknown or cleanup-completed content is a precise typed rejection; unverified or unexpectedly corrupt content is a typed corruption; every failure commits nothing.
- **Link management:** typed link and unlink entry points for an existing owner EntityRef — linking re-enters the `DELETE_PENDING` cancellation path (criterion 6); unlinking is the `DELETE_PENDING` entry path (criterion 5). Non-Transaction/Note endpoints are rejected with the existing typed endpoint outcome.
- The precise members, names, and package placement are pinned normatively in the plan; the spec fixes only the semantics above. Every existing P1/P2 outcome is unchanged; P3 adds no renamed or re-meant outcome.

### 4.8 Deferred to later slices

Explicitly not part of P3:

- The picker, preview, thumbnail, editor draft rules, `Phase.Selecting`/`Validating`, and all attachment UI (M3; interaction rules).
- The Note editor, body fields, and attachment UX (M3). The `saveNoteAggregate` entry point exists since P1 and P3 links Note owners through it, but P3 claims only the storage-level link lifecycle, not the Note editor journey.
- The product-defined image size limit, MIME whitelist, and validation UX wording (M3). P3 uses a recorded PoC size limit and a recorded PoC image-type set for declared/decoded MIME validation; the product values are M3's and P3 claims no product-level validation completeness.
- Backup/restore of attachment content (P5), the full N-1/N-2 migration matrix and complete `MIGRATION_RECOVERY` fault suite (M7), WorkManager scheduling of the cleaner (M7).
- **Explicit deviation from the `ATTACHMENT_CONTENT_COMMIT` mapping:** the mapping's "logical backup round trip preserves one canonical record and both links" clause requires a backup/restore surface P3 does not have. The same "backup round trip" scenario appears in the testing strategy's "Attachment loss or orphan visibility" risk row, gated at "PoC P3 and every attachment change". Both occurrences are owned by P5, whose exit gate already compares attachment owner, size, and SHA-256 across export/restore; P3's claim to the mapping covers its storage-side fail points (validation/hash, atomic move, owner commit, pending deletion, physical deletion, metadata cleanup, concurrent relink, duplicate reuse) only. P5 records the round-trip evidence when the backup surface exists.
- Any projection, `Category`/`CategoryAllocation`, account lifecycle, or CSV surface.
- Generic attachment types beyond images; no arbitrary-file ingestion.

## 5. Invariants and acceptance criteria

### 5.1 Invariants in scope

- One SHA-256 `contentIdentity` has exactly one canonical `Attachment` metadata fact and one physical content-addressed blob; a second Attachment row is invalid.
- Every `AttachmentLink` references the canonical Attachment ID, and its endpoint is a `Transaction` or `Note` EntityRef.
- No committed link references missing or unverified bytes.
- Physical content is never deleted while any link references it; `DELETE_PENDING` is entered only at zero global link count and survives failed deletion for idempotent retry.
- A new link commits only after byte reverification, and cancels or waits for pending deletion.
- The owner save and the link-management transactions are atomic: all requested changes commit or none do, and a failure leaves the pre-existing owner state exactly unchanged.
- Orphan and quarantine handling is idempotent and never relocates or deletes linked content.

### 5.2 Acceptance criteria

The slice passes only when all of the following have evidence in `verification.md`:

1. **`ATTACHMENT_SOURCE_COPY` copy matrix.** Interrupt the source copy before private temporary-file creation; during the first, middle, and final copy chunks; after the final write but before close; after close but before validation/hash; and on source-read or destination-flush failure. For every point, assert that no `AttachmentLink` becomes visible, no success result is emitted, and the pre-existing owner remains exactly unchanged. Assert that the partial temporary file is classified for safe cleanup, can be cleaned, and the ingestion can be retried without reusing unverified bytes.
2. **Validation and hash.** Undecodable content, content over the recorded P3 size limit, and content whose explicit declared MIME or decoded MIME is outside the recorded P3 image-type set never pass `VERIFIED`; a declared/decoded mismatch is rejected deterministically. No link becomes visible, no success result is emitted, and the original owner remains unchanged. The SHA-256 result matches the file content for accepted content, and the ingestion pipeline never trusts a caller-supplied hash.
3. **Duplicate-content reuse.** Two owners importing the same bytes share exactly one canonical Attachment ID and one physical blob; a duplicate import verifies the existing blob's actual hash and size and reuses the same identity; concurrent ingestion of identical bytes under per-hash serialization produces exactly one Attachment row; and no path ever creates a second Attachment row for one SHA-256.
4. **Atomic move and owner commit.** The content target is published only through a same-filesystem atomic move; unsupported atomic move or an injected move failure returns a typed failure and never falls back to a non-atomic copy/move. With failure injected at the atomic-move call and at every stage of the owner Room transaction, an external reader observes only the old complete owner state or the new complete owner-with-links state, never a partial link set; a failed owner transaction leaves either the zero-link canonical row with its original cleanup timestamp or a moved-but-uncommitted blob classified for post-verification, post-grace cleanup, and no link references missing content.
5. **Unlink, deletion, and cleanup.** Unlinking one owner while another remains linked leaves the canonical fact and blob active. Unlinking the last owner marks `DELETE_PENDING` in the same transaction; failure injected inside that mark transaction leaves no partial link removal and no inconsistent deletion marker, and the pre-existing owner state is unchanged. Physical deletion fails and retains `DELETE_PENDING` for retry; a retry after physical deletion but before the metadata-deletion transaction recognizes the expected zero-link pending state, observes absence, and completes metadata deletion idempotently rather than reporting corruption; and cleanup never deletes linked content or a canonical fact that still references committed bytes.
6. **Concurrent relink.** Cleanup racing a relink preserves the new link and the bytes; the relink cancels or waits for pending deletion, re-verifies bytes, and reuses the same canonical ID; a relink whose target blob is missing either re-ingests from a validated app-private temporary file or fails without committing a link to missing content.
7. **Corruption fails closed.** An unexpected metadata/blob split (a linked or `deletedAt == null` row without bytes), hash or size mismatch, and conflicting metadata produce the typed corruption outcome, quarantine only an unlinked new input, never overwrite the canonical blob or metadata, and never create a second Attachment row; the pre-existing owner state is unchanged. The expected zero-link pending deletion window is classified separately and can only be completed by the cleaner or restored by fresh validated ingestion.
8. **Idempotent orphan cleanup.** Partial `TEMP` files, moved-but-uncommitted blobs, zero-link `CONTENT_READY` rows, and failed-owner-transaction orphans are verified, grace-period-gated (injectable clock), and cleaned idempotently. Canonical rows use `deletedAt`; file-only residue uses its app-private last-modified timestamp, and a missing/invalid timestamp fails closed. Repeated cleanup runs are no-ops with no side effects; cleanup respects pointer-protected and still-referenced content.
9. **Schema and surface machine checks.** (a) The schema contract test passes against the committed `2.json`: exactly the eight tables (the six v1 tables unchanged plus `Attachment` and `AttachmentLink`), `databaseVersion = 2`, the v1 contract encodings unchanged, and the migration from the committed `1.json` fixture applies without destructive fallback. (b) The `:core:data` public-surface allowlist is updated to the P3 normative list (the additive attachment operations only) and matches exactly in both directions. (c) `apiBoundaryCheck` passes, with negative fixtures proving no path can create an `AttachmentLink` or write attachment state bypassing the typed surface.
10. **Applicable migration recovery.** A v1 fixture database with committed facts upgrades to v2 through the coordinator: snapshot creation, publication, migration, and open all succeed; with failure injected at the data-and-storage recovery boundaries — before snapshot publication, after publication before migration, during migration, after `MIGRATION_RECOVERY_PENDING` is persisted but before the first database or sidecar move, immediately before and after atomic migration-snapshot replacement, and during interruption of failed-database `-wal`/`-shm` sidecar isolation or cleanup — restart behaves exactly as the protocol requires: a failure before the pending record exists leaves the live database untouched and creates no pending record; a failure after it exists resumes the recorded `MIGRATION_RECOVERY_PENDING` recovery idempotently; no restart creates an empty database, opens a mixed database/sidecar set, or changes the published snapshot; and the recovered vault matches the pre-migration state. The full N-1/N-2 migration matrix remains M7's.
11. **Regression.** `./gradlew clean check` passes (module graph, build, lint unchanged), and the app still installs and launches on the reference device.

## 6. Test evidence requirements

- JVM unit tests cover the pure pipeline logic (hash computation, validation classification, deduplication decision table, orphan classification) in `:core:data`'s test source set, mirroring the P1/P2 validator-test pattern.
- Property tests on the JVM prove the canonical-mapping invariant over generated histories — a global one-to-one mapping from SHA-256 to canonical Attachment metadata/blob with arbitrary many valid owner links, and a second Attachment row with the same content identity always invalid — with fixed seeds recorded on failure; rerunning that seed is mandatory before a fix is accepted.
- Instrumented tests on the reference device prove the persisted behavior through the public repository surface: the full copy matrix, atomic move, owner-commit atomicity with external-reader snapshots (the P1 latch protocol), duplicate reuse, unlink/deletion/relink, quarantine, orphan cleanup, and the v1 → v2 migration recovery.
- Every injected fail point in every criterion runs at least three scenarios, per the testing strategy: fail once then restart; fail repeatedly without bypassing safety; and complete then repeat the handler to prove idempotence. This protocol is a global requirement, not a copy-matrix-only rule.
- Fault-injection evidence names every fail point and the mapping it belongs to — `ATTACHMENT_SOURCE_COPY`, `ATTACHMENT_CONTENT_COMMIT`, or the applicable `MIGRATION_RECOVERY` evidence of criterion 10 — with the injected boundary, observed durable state, visibility/success assertion, residue classification, restart/retry outcome, and cleanup outcome — the testing strategy's required `Result` fields; a summary such as "fault tests passed" is insufficient.
- `verification.md` maps every acceptance criterion and the roadmap P3 exit gate to concrete commands and results in the testing strategy's `Command`, `Environment`, `Result`, `Remaining risk` format; a build-only result cannot stand in for the required tests.

## 7. Non-goals

P3 explicitly does not:

- Implement any UI, ViewModel, navigation, picker, preview, thumbnail, or draft rule; no `Phase.*` attachment UX.
- Implement the Note editor, body fields, or attachment UX (M3); Note-owner linking is verified in P3 through the P1 `saveNoteAggregate` entry point, with no claim about the M3 editor journey.
- Fix the product image size limit or MIME whitelist (M3).
- Build backup/export/restore of attachment content (P5), WorkManager scheduling (M7), or the full N-1/N-2 migration and `MIGRATION_RECOVERY` fault matrix (M7).
- Add generic attachment types, server features, or any new dependency.
- Change any P1/P2 outcome, table, or public declaration that is not the additive attachment surface of section 4.7.
- Silently diverge from any core-document fact. If implementation exposes a conflict with a core document, the documentation-first protocol applies before any code change and the spec returns to review.

## 8. Dependency review boundary

Every dependency introduced by P3 is recorded at plan approval per the engineering conventions: version, license, maintenance evidence, compatibility impact, and removal path. No new dependency is anticipated — `MessageDigest`, file I/O, and the hand-rolled generator are platform/JDK standard library — and the P1/P2 pinned toolchain is reused. A dependency is rejected when its license is incompatible, its maintenance status is unknown, it requires excessive permissions, it introduces undisclosed network behavior, or no practical removal path exists. No ads, analytics, crash-reporting, or closed-source tracking SDK.

## 9. Risks

- **Migration coordinator first implementation.** P3 is the first slice to open a new schema and the first to exercise the recoverable-migration-snapshot protocol; the applicable evidence (criterion 10) bounds the risk, and the full N-1/N-2 matrix stays with M7.
- **Per-hash serialization correctness.** Import, linking, unlinking, and cleanup must serialize per SHA-256; the concurrency and relink criteria (3, 6) verify it, and the database unique constraint is the backstop that fails closed.
- **File IO timing on the physical device.** The copy and cleanup matrices run on the reference device; the plan must account for device time and USB stability, as in P2.
- **Clock dependency for the grace period.** The grace period is time-gated; P3 uses an injectable clock so tests are deterministic and the product behavior is not falsely claimed.
- **PoC size limit vs product limit.** P3 records its PoC size limit and decodability validation; the product limit and MIME whitelist are M3's and P3 claims no product-level validation completeness.
- **Failed ingestion residue.** Every failure path must leave residue deterministically classified (TEMP partial, moved-but-uncommitted, quarantined); misclassification would leak files or delete unverified bytes. The classification rules and the three-scenario fail protocol are acceptance evidence.
- **Note-owner evidence depth.** P3 links Note owners through the P1 `saveNoteAggregate` entry point and verifies the endpoint rule for both owner types, but the Note editor journey, body fields, and attachment UI remain M3's; P3 claims only the storage-level link lifecycle.
- **Device system build.** The reference device runs API 36; behavior on the upgraded system is recorded at verification, not rejected — the same rule as P0–P2.

## 10. Lifecycle path

- `draft` (this document): behavior, boundaries, and acceptance are clarified here. No implementation.
- `approved`: the user explicitly accepts this spec; the approval date/context is recorded. **Before `in-progress`:** ADR 0007 records recoverable-migration-snapshot ownership, ADR 0008 records the zero-link cleanup marker, the owning core documents are synchronized, and the roadmap/AGENTS/index candidate wording is updated.
- `in-progress`: the user approves the plan (recording exact tasks, file changes, and verification commands); implementation may begin.
- `completed`: acceptance criteria and the roadmap P3 exit gate have verification evidence; P4 may then start.

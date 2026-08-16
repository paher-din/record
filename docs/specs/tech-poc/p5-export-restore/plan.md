# Tech PoC P5 — Export and Restore Implementation Plan

- **Status:** `completed` — read-only history; all fourteen acceptance criteria and the roadmap P5 exit gate pass (see `verification.md`).
- **Plan Revision 2 approved:** 2026-08-09 — the user approved plan Revision 2 and spec Revision 2 jointly; the approval date/context is recorded here per the roadmap lifecycle rules.
- **Spec:** [spec.md](spec.md) — Revision 2 `draft`, proposed for joint approval with this plan.
- **ADR 0003:** [generation-based restore](../../../architecture/decisions/0003-generation-based-restore.md) — accepted 2026-08-04; the activation protocol steps 0–6, `PENDING` semantics, rollback, and retirement.
- **ADR 0007:** [recoverable-migration-snapshot ownership](../../../architecture/decisions/0007-recoverable-migration-snapshot-ownership.md) — accepted 2026-08-08; P5 records the migration-recovery evidence applicable to its restore journeys; the full N-1/N-2 matrix stays M7's.
- **ADR 0010:** [generation bootstrap and live-session handoff](../../../architecture/decisions/0010-generation-bootstrap-and-live-session-handoff.md) — accepted 2026-08-09; non-destructive v1/root-layout copy-validation-pointer publication and same-repository exclusive rebind.
- **Revision 1:** 2026-08-08 — plan validation found five scope/fact corrections and revised the spec documentation-first: the thirteen-contract-dataset list vs the eight current v2 tables (catalog datasets deferred to M2–M4 as backward-compatible `formatVersion = 1` extensions), the hand-rolled strict codec replacing platform `org.json`, first-startup initial-generation creation, `recordVersion = 2` with the P3 migration portion unchanged, and criterion 1's catalog-field cleanup. This plan implements the revised scope.
- **Revision 2:** 2026-08-09 — review added the missing P3/P4 compatibility migration and safe live-session handoff, corrected the exact transaction enum, completed restore rejection classification, synchronized the testing facts, and made source/merged/packaged backup-policy verification executable. The user approved the ADR-0010 design; the revised spec and this implementation plan still require joint lifecycle approval before code.

**Goal:** Prove non-destructive bootstrap from the P3/P4 layout plus complete logical backup, overwrite restore, recovery, and explicit migration ownership on the v2 schema: `GENERATION_BOOTSTRAP`; an automated export → wipe/overwrite → restore → startup comparison matching every fact, relation, balance, attachment owner, size, and SHA-256; every binding ZIP limit passing declared and streamed boundary tests; both backup mappings, `RESTORE_ACTIVATION`, and applicable `MIGRATION_RECOVERY`; and source/merged/final-APK plus packaged-rule evidence excluding all app user data from cloud backup/device transfer.

**Architecture:** backup/restore stays in `:core:data`. A process-wide `VaultSessionRegistry` keyed by canonical vault root serializes bootstrap and returns one production repository identity per root. ADR 0010 copies/validates the legacy layout, then atomically publishes v2 without deletion. That repository holds one complete `VaultBinding`; `VaultOperationGate` gives ordinary calls shared access and bootstrap/activation/rebind exclusive access. Confirmation closes O, runs the cold-start resolver, and swaps the same repository to C or O. Public additions remain `exportBackup`, `stageRestore`, `confirmRestore(staged)`, typed values/failures, and provider protocol. Room schema stays v2.

**Tech Stack:** unchanged from P1–P4 — Gradle 9.5.0, AGP 9.3.0 (built-in Kotlin 2.2.10), KSP2 2.3.9, Room 2.8.4, `kotlin-metadata-jvm` 2.2.10 (test-time), androidx.test runner/ext-junit, JUnit 4.13.2. ZIP uses platform `java.util.zip` (`ZipOutputStream`/`ZipInputStream`), SHA-256 uses JDK `java.security.MessageDigest`, atomic record writes use Android `AtomicFile` (P3 precedent), and the JSONL/manifest codec is a hand-rolled strict pure-Kotlin streaming writer/parser with duplicate-key tracking (the P3 `MigrationRecoveryRecordStore` pattern, made JVM-testable). The malicious-ZIP fixtures are generated in tests with fixed seeds (sparse/generated streams, `Deflater` compression ratios) rather than repository fixtures of physical multi-GiB size. No new dependency declaration is added; if the pinned combination fails during execution, this plan is revised and re-approved first — versions are never switched mid-execution.

**Reference environment (frozen):** Xiaomi 14 Pro (physical, USB debugging), Android 14 / API 34 out-of-box baseline. The actual system build is recorded in `verification.md` at verification time (currently API 36 per P0–P4; recorded, not rejected).

**Namespaces:** `:core:data` → `com.paherdin.record.core.data` (public API), Room package `com.paherdin.record.core.data.room`; `:core:model` surface is unchanged (exactly `EntityType` and `TransactionType`).

**Pre-approval dependency validation:** none required. P5 introduces no new resolved artifact: `java.util.zip`, `MessageDigest`, and `AtomicFile` are JDK/Android platform APIs, and the JSONL/manifest codec is hand-rolled pure Kotlin with no platform `org.json` dependency. Task 1 records dependency insight so this assumption is machine-checked before implementation.

## Dependency review record

No new dependency declaration enters the version catalog or any build in P5. The P1 review record remains valid for every dependency reused; P5 directly uses the JDK ZIP/hash APIs and the Android `AtomicFile` API already used by P3. Any later change that adds or changes a resolved artifact must first pass the engineering conventions' dependency review record (version, license, maintenance, compatibility impact, removal path) at plan approval.

## Public API of `:core:data` — P5 normative delta

The P1–P4 normative declaration list is inherited **unchanged**. The P5 surface is that list plus exactly this additive delta, pinned here so the regenerated `api/expected-surface.txt` is derived from this list:

```kotlin
// package com.paherdin.record.core.data — additive members only

enum class BackupViolation { PACKAGE_VALIDATION_FAILED }

enum class BackupStage {
    CONSISTENT_READ,
    STREAM_JSONL,
    STREAM_ATTACHMENT,
    MANIFEST_WRITE,
    PRIVATE_VALIDATE,
    PRIVATE_RENAME,
    PROVIDER_CREATE,
    PROVIDER_COPY_CHUNK,
    PROVIDER_FLUSH_CLOSE,
    PROVIDER_VERIFY,
    PROVIDER_RENAME,
    PROVIDER_MARKER_CLEAR,
}

enum class RestoreStage {
    PREFLIGHT_DECLARED,
    PREFLIGHT_STREAMING,
    STAGING_IMPORT,
    STAGING_VALIDATE,
    ACTIVATION_SWITCH,
    STARTUP_REVALIDATE,
    ROLLBACK,
    RETIRE_DELETE,
    RETIRE_POINTER_CLEAR,
    ORPHAN_SCAN,
}

enum class RestoreViolation {
    FORMAT_UNSUPPORTED,
    MANIFEST_INVALID,
    RECORD_INVALID,
    UNKNOWN_LOGICAL_TYPE,
    SINGLETON_DUPLICATE,
    NAME_KEY_VERSION_UNSUPPORTED,
    CATALOG_INVALID,
    PATH_INVALID,
    PATH_TOO_LONG,
    PATH_DUPLICATE,
    ENTRY_COUNT_LIMIT,
    ENTRY_SIZE_LIMIT,
    AGGREGATE_SIZE_LIMIT,
    SPACE_BUDGET,
    COMPRESSION_RATIO_LIMIT,
    SIZE_UNDER_DECLARED,
    SHA256_MISMATCH,
    RECORD_COUNT_MISMATCH,
    ATTACHMENT_DUPLICATE_IDENTITY,
    ATTACHMENT_LINK_DANGLING,
    ATTACHMENT_MISSING,
    ATTACHMENT_EXTRA,
    ATTACHMENT_CORRUPT,
    FOREIGN_KEY_INVALID,
    XOR_INVALID,
    FINANCE_INVALID,
    BALANCE_INVALID,
    ENDPOINT_INVALID,
    CANDIDATE_MISMATCH,
    MAINTENANCE_PENDING,
}

// existing sealed interface RepositoryResult<out T> gains exactly these four
// Failure members — one shared taxonomy, every P1–P4 member unchanged
sealed interface RepositoryResult<out T> {
    sealed interface Failure : RepositoryResult<Nothing> {
        data class BackupRejected(val violation: BackupViolation) : Failure
        data class BackupIoFailed(val stage: BackupStage) : Failure
        data class RestoreRejected(val violation: RestoreViolation) : Failure
        data class RestoreIoFailed(val stage: RestoreStage) : Failure
    }
}

/** The published artifact identity returned on export success. */
data class BackupArtifact(val backupId: String, val path: String, val sha256: String)

/** The validated candidate summary returned when staging succeeds. */
data class StagedRestore(
    val candidateId: String,
    val validationDigest: String,
    val recordCounts: Map<String, Int>,
    val attachmentCount: Int,
)

/** Private atomic publication or an injected provider target (M6 wires the SAF DocumentFile). */
sealed interface BackupTarget {
    data class PrivateFile(val file: File) : BackupTarget
    data class Provider(val provider: BackupProviderTarget) : BackupTarget
}

/** The SAF copy protocol steps; a partial object stays incomplete-marked until fully verified. */
interface BackupProviderTarget {
    suspend fun createIncomplete(): Boolean   // false = target already exists; nothing written
    suspend fun writeChunk(bytes: ByteArray)
    suspend fun flushAndClose()
    suspend fun verify(): Boolean             // destination verification after close
    suspend fun renameToFinalIfSupported(): Boolean
    suspend fun clearIncompleteMarker()
}

// existing RecordRepository class gains exactly these three methods
suspend fun exportBackup(target: BackupTarget): RepositoryResult<BackupArtifact>
suspend fun stageRestore(source: File): RepositoryResult<StagedRestore>
suspend fun confirmRestore(staged: StagedRestore): RepositoryResult<Unit>
```

Semantics:

- `exportBackup(target)` performs the consistent logical read → stream/hash the eight JSONL datasets and attachment blobs → write the manifest last → validate the complete package → publish. `PrivateFile` publication is an atomic rename on the same filesystem after validation; `Provider` publication runs the `BACKUP_SAF_COPY` protocol (create-incomplete, chunk copy, flush/close, verify, rename-if-supported, clear marker) from the complete privately validated package. Success carries `backupId`/`path`/`sha256` only after the package is validated and published; failure is `BackupIoFailed(stage)` at the named stage or `BackupRejected(PACKAGE_VALIDATION_FAILED)` for a generated package that fails its own validation. Export is read-only over the active generation and is never blocked by pending retirement (only a new restore is, per spec 4.8). The current vault is never mutated.
- `stageRestore(source)` validates one isolated generation and writes a strict canonical receipt containing candidate ID, source-package SHA-256, manifest SHA-256, sorted entry hashes/counts, validation-contract version, and candidate logical-state digest. `validationDigest` is SHA-256 of that UTF-8 receipt. At most one candidate belongs to the repository; process death invalidates its handle and requires restaging. Replacement touches only unreferenced staging and never activation state.
- `confirmRestore(staged)` recomputes the receipt hash; stale, forged, replaced, repeated, or prior-process handles return `CANDIDATE_MISMATCH` before mutation. It takes the exclusive gate, drains shared calls, closes O's complete `VaultBinding`, writes `PENDING`, and enters `withContext(NonCancellable)` for resolver plus binding construction/swap. Success atomically swaps the same repository to C's Room/DAO/path/attachment/maintenance bundle; rollback swaps it to O before returning failure.
- The four `Failure` members share one taxonomy and cover every backup/restore operation; callers do not auto-retry (the P1–P4 caller-behavior class). ABI-additive, but adding direct subtypes to sealed `Failure` is source-breaking for a consumer that recompiles an exhaustive `when`; the consumer fixtures are updated with the new branches (same rule as P2/P3).

## Deterministic rejection classification

Restore classification is total and first-match deterministic. Declared pass: unsupported `formatVersion` → `FORMAT_UNSUPPORTED`; unreadable, duplicate-key, unknown-field, missing-field, or structurally inconsistent manifest → `MANIFEST_INVALID`; unknown logical type → `UNKNOWN_LOGICAL_TYPE`; duplicate singleton → `SINGLETON_DUPLICATE`; unsupported catalog version → `NAME_KEY_VERSION_UNSUPPORTED`; invalid/overlong/duplicate paths → the corresponding path violation; then entry count, per-entry size, aggregate, space, and compression-ratio violations in that order. Streaming pass: the same resource violation when crossed; declared below actual → `SIZE_UNDER_DECLARED`; hash mismatch → `SHA256_MISMATCH`; count mismatch → `RECORD_COUNT_MISMATCH`; malformed JSONL, duplicate/unknown/missing fields, invalid enum/timestamp/number, or trailing data → `RECORD_INVALID`. Record-level pass: unsupported catalog key/version is classified first, then other catalog contract failures → `CATALOG_INVALID`; attachment identity/link/content failures → the matching `ATTACHMENT_*`; general dangling Room relation → `FOREIGN_KEY_INVALID`; XOR → `XOR_INVALID`; finance → `FINANCE_INVALID`; derived balance → `BALANCE_INVALID`; typed owner endpoint → `ENDPOINT_INVALID`. Confirmation: staged-handle mismatch → `CANDIDATE_MISMATCH`; unresolved retirement → `MAINTENANCE_PENDING`. IO exceptions map only to `RestoreIoFailed(stage)` and never stand in for validation. Parameterized classification tests cover every row and multi-failure precedence.

## Recovery record v2 and storage layout

### Recovery record v2

The P3 `activation-recovery-v1.json` extends to `recordVersion = 2`; the migration portion keeps the exact P3 field meanings, and a new generation-activation portion is added. UTF-8 JSON with enum names stored verbatim; unknown versions, states, stages, missing fields, duplicate keys, absolute paths, or paths escaping the app-private root fail closed before Room/SQLite opens:

```json
{
  "recordVersion": 2,
  "migrationRecoveryState": "MIGRATION_HEALTHY",
  "migration": null,
  "generationActivation": {
    "activeGenerationId": "gen-<uuid>",
    "previousGenerationId": null,
    "candidateGenerationId": null,
    "retiringGenerationId": null,
    "state": "HEALTHY"
  }
}
```

`state` is `HEALTHY` or `PENDING`; all generation IDs are app-generated UUID strings, not timestamps. The normal reader supports exactly `recordVersion = 2`; unknown versions fail closed. The separate ADR-0010 bootstrap reader accepts exactly the P3 v1 shape and converts it only after resolving any pending migration recovery and validating the copied generation. Startup order is fixed: classify v1/v2/no-record plus legacy-data presence before SQLite/Room; complete legacy bootstrap when required; resolve the v2 migration gate; resolve the generation gate; then Room opens the active generation database.

### Generation storage layout and path evolution

- `files/vaults/<generationId>/record.db` (+ `-wal`/`-shm` sidecars) — the generation-specific database; `record.db` is the filename inside each generation directory.
- `files/vaults/<generationId>/attachments/{tmp,content,quarantine}/` — per-generation attachment content, same relative structure as P3's `files/attachments/`.
- `files/recovery/activation-recovery-v1.json` — the v2 activation/recovery record (filename unchanged).
- P3's root-level `files/attachments/` is retired: `AttachmentContentStore` and `MigrationCoordinator` receive the active-generation vault directory instead of `filesDir` (path parameterization, no behavior change; the P3 component tests are rerun on the generation layout).
- P3/P4's root database, sidecars, root attachment tree, and v1 artifacts are bootstrap inputs. Approved recovery/migration and WAL checkpoint may change their physical bytes before the copy baseline; after that baseline, P5 copy/validation/publication neither mutates nor deletes them.
- Grace period for zero-link cleanup: unchanged (7 days, injectable clock).

Bootstrap/generation gate on startup: a v1 record or no-record legacy state runs `LegacyGenerationBootstrap`; a truly fresh state atomically creates the first healthy v2 generation before Room opens. For v2 `PENDING`, revalidate the candidate through read-only access; success performs step 4, failure step 5. A non-null `retiringGenerationId` runs step 6 synchronously under the per-generation lock. Safe startup then scans `files/vaults/`, never deletes pointer-named directories, and cleans complete/incomplete unreferenced staging idempotently. The legacy root layout is excluded from orphan cleanup. Room opening remains blocked during bootstrap, migration recovery, or pending generation resolution.

`VaultOperationGate` wraps the repository's mutable backing database. Existing P1–P4 methods, attachment operations, export, stage, and maintenance take a shared lease; bootstrap, confirm, rollback, cold-start resolution, and backing-database replacement take the exclusive lease. Confirmation closes O, writes `PENDING`, invokes the common resolver, opens the resulting active generation, swaps the repository backing reference, and only then releases the gate. A cold-start resolver uses the same transition functions without an existing repository instance.

## JSONL dataset and manifest shapes

The eight current-schema datasets, pinned here; JSONL lines are UTF-8, one record per line, written in a deterministic table order with the exact field set below (no extra fields; the strict parser rejects unknown fields):

```json
// EntityRef.jsonl — entityType verbatim enum, deletedAt null when absent
{"entityType":"TRANSACTION|NOTE|PROJECT","id":"...","createdAt":123,"updatedAt":456,"deletedAt":123}
// Account.jsonl
{"id":"...","name":"...","currency":"CNY"}
// Transaction.jsonl — type verbatim TransactionType enum
{"id":"...","type":"OPENING_BALANCE|INCOME|EXPENSE|TRANSFER"}
// AccountEntry.jsonl
{"id":"...","transactionId":"...","accountId":"...","amountMinor":123}
// Note.jsonl
{"id":"..."}
// TransactionNoteAssociation.jsonl
{"transactionId":"...","noteId":"..."}
// Attachment.jsonl — deletedAt null when active, present when zero-link cleanup-pending
{"id":"...","contentIdentity":"...","deletedAt":123}
// AttachmentLink.jsonl
{"attachmentId":"...","ownerEntityRefId":"..."}
```

The manifest is written last and has exactly this shape (descriptor per payload entry except `manifest.json`; no device identifier or hardware ID):

```json
{
  "formatVersion": 1,
  "databaseSchemaVersion": 2,
  "appVersion": "<versionName> (<versionCode>)",
  "createdAt": 123,
  "backupId": "<uuid>",
  "hashAlgorithm": "SHA-256",
  "catalogNameKeyVersions": [],
  "entries": [
    {"logicalType":"EntityRef","path":"EntityRef.jsonl","size":123,"sha256":"...","recordCount":5},
    {"logicalType":"Transaction","path":"Transaction.jsonl","size":45,"sha256":"...","recordCount":1},
    {"logicalType":"AttachmentContent","path":"attachments/<sha256>","size":456,"sha256":"..."}
  ]
}
```

`AttachmentContent` descriptors carry no `recordCount`. `catalogNameKeyVersions` is empty in P5 (no catalog tables); the reader still enforces the contract exactly (an empty manifest set must equal the encountered record-level set, and a non-empty declared set containing an unsupported version rejects).

## File inventory

| File | Purpose |
|---|---|
| `core/data/src/main/java/com/paherdin/record/core/data/JsonlCodec.kt` | `internal` pure-Kotlin strict streaming JSON writer/parser (duplicate-key and unknown-field tracking, fail-closed), JVM-testable; the P3 record-store pattern promoted to a codec |
| `core/data/src/main/java/com/paherdin/record/core/data/backup/LogicalBackupSchema.kt` | `internal` the eight dataset record shapes (encode/decode to/from entity data classes), dataset order, singleton `logicalType` set |
| `core/data/src/main/java/com/paherdin/record/core/data/backup/Manifest.kt` | `internal` manifest model + writer/reader; descriptor/record-count/version-set consistency checks |
| `core/data/src/main/java/com/paherdin/record/core/data/backup/BackupExporter.kt` | `internal` export pipeline: consistent read → stream/hash JSONL and attachments → manifest last → private validation → publish; `BackupFaultHook` seams at every named boundary |
| `core/data/src/main/java/com/paherdin/record/core/data/backup/BackupFaultHook.kt` | `internal` named boundaries: before/mid/after each JSONL and attachment stream, before/mid/after manifest write, before/after private rename, each provider step; no-op in production |
| `core/data/src/main/java/com/paherdin/record/core/data/backup/BackupProviderTarget.kt` | `internal` the `BackupProviderTarget` interface (public) plus the SAF-copy coordinator |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/ZipPreflightDeclared.kt` | `internal` pure declared-pass decision table: path normalization/counting, limits, ratios, singleton/duplicate checks |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/ZipStreamingPreflight.kt` | `internal` independent streaming pass over `ZipInputStream`: actual counts/bytes, mid-stream limit crossing, declared-below-actual detection |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/LogicalBackupReader.kt` | `internal` manifest-driven staged reader: verified extraction to staging, record-level version/type checks |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/RestoreStager.kt` | `internal` record-level import into the staging generation (deterministic insertion order: EntityRef → Account → Transaction → Note → AccountEntry → TransactionNoteAssociation → Attachment → AttachmentLink) plus the full staging validation (counts, FK, XOR, finance matrix, balances, endpoints, attachment hashes, catalog-contract framework) |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/StagingReceipt.kt` | `internal` strict canonical receipt and SHA-256 handle binding source/manifest/entries/counts/validation version/logical-state digest; process-local candidate registry metadata |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/GenerationActivationRecord.kt` | `internal` v2 record model; extends the P3 store with the generation-activation portion (same `AtomicFile` protocol, same fail-closed parsing) |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/LegacyGenerationBootstrap.kt` | `internal` ADR-0010 classifier and copy/validate/publish state machine for v1 record, no-record legacy data, and fresh install; never deletes legacy input |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/GenerationManager.kt` | `internal` generation directory layout, initial-generation creation, pointer-aware orphan scan, step-6 retirement, per-generation lock |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/RestoreActivationCoordinator.kt` | `internal` steps 0–5 of the activation protocol; startup revalidation and healthy/rollback transitions; `RestoreFaultHook` seams |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/VaultOperationGate.kt` | `internal` shared leases for ordinary repository calls and exclusive leases for bootstrap/activation/rebind; owns the one-staged-candidate registry |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/VaultBinding.kt` | `internal` one immutable generation-scoped bundle: generation ID/path, Room/DAO, attachment store/ingestor/link manager/cleaner, migration and maintenance collaborators |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/VaultSessionRegistry.kt` | `internal` process-wide canonical-root registry: serializes first open/bootstrap, returns one production repository identity per root, releases isolated test sessions |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/RestoreFaultHook.kt` | `internal` named boundaries: declared/streaming preflight, staging import/validate, activation switch, startup revalidate, rollback, retire delete/pointer clear, orphan scan; no-op in production |
| `core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt` | additive `exportBackup`/`stageRestore`/`confirmRestore(staged)`; all operations join `VaultOperationGate`; `open()` classifies bootstrap then resolves migration/generation; confirmation rebinds the same instance |
| `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDao.kt` | additive bulk inserts per dataset for staging import (existing `all*` queries serve export); no schema change |
| `core/data/src/main/java/com/paherdin/record/core/data/RepositoryResult.kt` | the four additive `Failure` members and the three enums (P1 pattern) |
| `core/data/src/main/java/com/paherdin/record/core/data/AttachmentContentStore.kt`, `MigrationCoordinator.kt` | path parameterization only: vault-directory root instead of `filesDir`; no behavior change |
| `core/data/api/expected-surface.txt` | regenerated from the normative list above |
| `core/data/src/test/java/com/paherdin/record/core/data/JsonlCodecTest.kt` | JVM: strict round trips, duplicate/unknown-field/malformed rejection, Unicode and boundary timestamps, property round-trip stability over generated histories (seeds recorded on failure) |
| `core/data/src/test/java/com/paherdin/record/core/data/ManifestTest.kt` | JVM: exact shape, descriptor/record-count/version-set consistency, empty `catalogNameKeyVersions` contract |
| `core/data/src/test/java/com/paherdin/record/core/data/ZipPreflightDeclaredTest.kt` | JVM: the declared-pass decision table — every limit at exactly-at and one-over (100,000/100,001 entries; 240/241-byte paths; traversal/absolute/duplicates; 512 MiB/1 GiB; 20 GiB and space-derived budgets; 200:1 and zero-byte cases; singleton duplicates) |
| `core/data/src/test/java/com/paherdin/record/core/data/MaliciousZipGenerator.kt` | JVM test fixture: generates over-limit/mismatched ZIPs with fixed seeds (sparse/generated streams, controlled `Deflater` ratios) |
| `core/data/src/test/java/com/paherdin/record/core/data/ZipStreamingPreflightTest.kt` | JVM: streaming pass over generated ZIPs — actual-count/byte independence, declared-below-actual, mid-stream crossing, short read, exhaustion |
| `core/data/src/main/java/com/paherdin/record/core/data/restore/CatalogContractChecker.kt` | `internal` name-key-version support checks, unknown-`logicalType` rejection, manifest-set/record-set equality |
| `core/data/src/test/java/com/paherdin/record/core/data/CatalogContractCheckerTest.kt` | JVM: name-key-version support checks, unknown-`logicalType` rejection, manifest-set/record-set equality |
| `core/data/src/test/java/com/paherdin/record/core/data/GenerationActivationRecordTest.kt` | JVM: v2 record state machine transitions (steps 0–6), atomicity of each transition, PENDING semantics |
| `core/data/src/test/java/com/paherdin/record/core/data/RestoreViolationClassificationTest.kt` | JVM: total validator-to-violation mapping and multi-failure first-match precedence |
| `core/data/src/test/java/com/paherdin/record/core/data/StagingReceiptTest.kt` | JVM: canonical bytes, field/order binding, SHA-256 stability, and prior-process handle invalidation metadata |
| `core/data/src/test/java/com/paherdin/record/core/data/BackupViolationClassificationTest.kt` | JVM: the deterministic rejection classification order of this plan |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/TestProviderTarget.kt` | device test fixture: app-private provider target implementing create/write/flush/close/verify/rename/marker with injectable failures |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/RoundTripFixture.kt` | device test fixture: the canonical v2-schema fixture (all eight tables, each transaction type, multi-allocation, bidirectional relations, shared attachment, soft-deleted facts, Unicode names, boundary timestamps) and the logical-state comparator |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/BackupStreamPublishTest.kt` | criterion 4: `BACKUP_STREAM_AND_PUBLISH` matrix, three-scenario protocol, private atomic rename |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/BackupSafCopyTest.kt` | criterion 5: `BACKUP_SAF_COPY` matrix on `TestProviderTarget`, incomplete-marker semantics |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/MaliciousZipRestoreTest.kt` | criteria 6–7: declared and streaming rejection end to end on the device with current vault hash unchanged and staging identifiable |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/StagingValidationTest.kt` | criterion 8: record-level preflight — counts, FK, XOR, finance, balances, endpoints, attachment hashes, catalog-contract framework, catalog fault injection |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/GenerationActivationTest.kt` | criterion 9: steps 0–6 matrix, crash windows, rollback, retirement, orphan scan, next-restore gating |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/LegacyGenerationBootstrapTest.kt` | criterion 14: v1/no-record/fresh classification, recovery/migration/checkpoint/copy/publish crashes, logical and frozen-hash baselines, no deletion |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/RepositoryGenerationHandoffTest.kt` | criterion 14: exact handle, shared/exclusive drain, close-before-PENDING, same-instance C/O rebind, process-death recovery |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/StartupGateOrderTest.kt` | criterion 10: migration-before-generation order, pending-migration blocks restore, restored vault opens on v2 without migration |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/RoundTripTest.kt` | criterion 1: export → verify → wipe test vault → stage → confirm → startup gate → full comparison → second restart → integrity/balance recheck |
| `core/data/apiGuard/positive/BackupSurfaceUsage.kt`, `apiGuard/negative/*` | apiBoundaryCheck fixtures: positive unpacking of the new outcomes; negative compilation proving no path bypasses the typed surface for export/staging/activation or the startup gates |
| `docs/specs/tech-poc/p5-export-restore/verification.md` | Task 11: the evidence record mapping every criterion and the exit gate to commands and results (P1–P4 precedent) |

## Tasks

### Task 0: Lifecycle entry

**Files:** `plan.md`, `spec.md`, `AGENTS.md`, `docs/index.md`, `docs/roadmap.md`; verify `docs/architecture/data-and-storage.md`, `docs/engineering/testing-strategy.md`, and ADR 0010; create `verification.md`.

- [ ] After explicit joint approval, record its date/context, move plan/spec Revision 2 to `in-progress`, make P5 the unique active slice in AGENTS/index/roadmap, verify ADR 0010 remains accepted and both owning core documents contain the approved bootstrap/current-schema-fixture facts, and create `verification.md` with the initial slice-state record. Do this before any implementation edit; do not rewrite the already accepted ADR during lifecycle entry.

### Task 1: Toolchain, dependency, schema-source, and device preflight

**Files:** none; record results in `verification.md` after approval.

- [ ] Resolve JDK 17+, Gradle, Android SDK, and the reference device without changing persistent environment configuration:

```powershell
$p5Java = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (-not (Test-Path $p5Java)) { throw 'Set process-local JAVA_HOME to JDK 17+ before P5 execution' }
$p5Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$p5Adb = Join-Path $p5Sdk 'platform-tools/adb.exe'
if (-not (Test-Path $p5Adb)) { throw "adb not found: $p5Adb" }
& $p5Java -version
.\gradlew.bat --version
& $p5Adb devices -l
$p5SchemaV2 = (Get-FileHash 'core/data/schemas/com.paherdin.record.core.data.room.RecordDatabase/2.json' -Algorithm SHA256).Hash
$p5SchemaV2
```

Expected: JDK 17+, Gradle 9.5.0, Xiaomi 14 Pro present, and the v2 schema SHA-256 recorded as the immutable source pin (P5 changes no schema; criterion 12(a) reuses the existing `AttachmentSchemaContractTest` against the committed `2.json`).

### Task 2: Codec, manifest, rejection taxonomy, and preflight pure logic

**Files:** create `JsonlCodec.kt`, `LogicalBackupSchema.kt`, `Manifest.kt`, `ZipPreflightDeclared.kt`, `ZipStreamingPreflight.kt`, `CatalogContractChecker.kt` (module-internal), `RestoreViolationClassificationTest.kt`, and their JVM tests.

- [ ] Write tests first: strict JSON round trips (duplicate keys, unknown/missing fields, malformed input, Unicode, boundary timestamps, property stability over generated histories), the eight dataset shapes with verbatim `OPENING_BALANCE`, the manifest shape and descriptor consistency, the total rejection table including malformed JSONL/FK/catalog/candidate mismatch, multi-failure precedence, the declared-pass decision table, the streaming pass, and the catalog framework. Run and require missing-type failure:

```powershell
.\gradlew.bat :core:data:testDebugUnitTest --tests 'com.paherdin.record.core.data.JsonlCodecTest' --tests 'com.paherdin.record.core.data.ManifestTest' --tests 'com.paherdin.record.core.data.RestoreViolationClassificationTest' --tests 'com.paherdin.record.core.data.ZipPreflightDeclaredTest' --tests 'com.paherdin.record.core.data.ZipStreamingPreflightTest' --tests 'com.paherdin.record.core.data.CatalogContractCheckerTest'
```

Expected before implementation: FAIL from missing P5 types. Implement the codec, exact schemas, exhaustive exception-to-violation mapping, manifest, both preflight passes, and framework; rerun and require PASS. No `android.*` API may appear in these files.

### Task 3: Record v2 and non-destructive legacy generation bootstrap

**Files:** create `GenerationActivationRecord.kt`, `LegacyGenerationBootstrap.kt`, `GenerationManager.kt`, `VaultSessionRegistry.kt`; modify `MigrationRecoveryRecordStore.kt`, `MigrationCoordinator.kt`, `AttachmentContentStore.kt`, `RecordRepository.kt` (open path only); create `GenerationActivationRecordTest.kt`, `LegacyGenerationBootstrapTest.kt`; adapt and rerun the P3 suites without changing their legacy protocol assertions.

- [ ] Write v2/bootstrap tests first: record shapes; v1 states; no-record/fresh classification; recovery plus required legacy migration; checkpoint/copy/validation/v2-write interruption; logical/frozen-hash contracts; no deletion; concurrent same-root opens execute bootstrap once and return the same repository identity; distinct test roots remain independent. Compile and require missing types:

```powershell
.\gradlew.bat :core:data:compileDebugAndroidTestKotlin
```

- [ ] Implement `VaultSessionRegistry`, v1 reader, v2 store, bootstrap copier/validator, and generation manager. Canonicalize the root before registry lookup; production open returns the existing live repository or owns the single initialization; test teardown closes/releases only its explicit root. Then classify → recover/migrate legacy → copy/validate/publish or fresh-create → v2 gates → orphan scan → Room. Keep the evidence seams explicit: `MigrationRecoveryTest` and `MigrationRecoveryRecordTest` drive the P3 coordinator/store plus the legacy Room migration directly and continue to assert the version-1 record/root-layout protocol; `LegacyGenerationBootstrapTest` and production-open smoke tests assert the P5 conversion, version-2 record, active-generation paths, and repository identity. Do not rewrite P3 assertions to read the generation database or parse the version-2 record. Run:

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.LegacyGenerationBootstrapTest,com.paherdin.record.core.data.room.MigrationRecoveryTest,com.paherdin.record.core.data.room.MigrationRecoveryRecordTest,com.paherdin.record.core.data.room.AttachmentSourceCopyTest
```

Expected: PASS; one initialization and repository identity per production root, correct crash authority, fresh pointer ownership, no source deletion, and active-vault attachment paths. The complete attachment matrix runs in Task 11.

### Task 4: Export pipeline and `BACKUP_STREAM_AND_PUBLISH`

**Files:** create `BackupExporter.kt`, `BackupFaultHook.kt`, `Manifest.kt` usage; modify `RecordRepository.kt`; create `BackupStreamPublishTest.kt`.

- [ ] Write failing tests for the export boundary: consistent logical read (single snapshot point, all eight datasets), stream/hash order, manifest-last, private validation, atomic rename, and the named fail points — interrupt every JSONL and attachment stream before first write/mid-entry/after final payload/during flush-close; interrupt manifest write before first byte/mid-write/after final write before close; before/after private rename. Assert the package never appears at the final path, is never reported as success, and residue is identifiable for cleanup/retry; the current vault hash is unchanged. Implement `exportBackup(target)` and `exportBackup(PrivateFile)`. Run:

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.BackupStreamPublishTest
```

Expected: PASS for fail-once/restart, repeated-failure, and complete/repeat at every named fail point; manifest SHA-256 and per-entry hashes verify against the package.

### Task 5: Provider target and `BACKUP_SAF_COPY`

**Files:** create `BackupProviderTarget.kt` coordinator; create `TestProviderTarget.kt`, `BackupSafCopyTest.kt`; modify `RecordRepository.kt`.

- [ ] Write failing tests from a complete privately validated package: interrupt provider target creation, first/middle/final copy chunks (64 KiB fixtures), flush/close, destination verification, provider rename when supported, and marker clearing. Assert a partial provider object stays incomplete-marked, is never listed/reported complete, and cleanup/retry is deterministic; only a fully copied and reverified object clears the marker. Implement the provider coordinator and `exportBackup(Provider)`. Run:

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.BackupSafCopyTest
```

Expected: PASS for all three scenarios at every named provider boundary; the rename-unsupported path still terminates success only after verification.

### Task 6: Restore preflight and malicious ZIP matrix

**Files:** create `LogicalBackupReader.kt`, `MaliciousZipRestoreTest.kt`; modify `RecordRepository.kt` (`stageRestore` preflight portion only, no activation).

- [ ] Write failing tests for the declared and streaming passes end to end on the device, using `MaliciousZipGenerator` fixtures: entry counts 100,000/100,001 and streamed-over-declared; 240/241-byte paths; absolute/traversal/duplicate paths; duplicate `logicalType`/`path` pairs; duplicate singleton `logicalType`; 512 MiB/512 MiB+1 attachment and 1 GiB/1 GiB+1 structured entries (sparse/generated); aggregate at/over `min(20 GiB, space − 1 GiB)` including space at/below the reserve; ratio 200:1 and above, zero-byte entries/archive, zero compressed denominator; declared below actual, mid-stream per-entry/aggregate crossing, short read, exhaustion, and low-space change during staging. Every rejection leaves the current vault hash unchanged, reports no success, and leaves staging identifiable; every exact-limit valid fixture proceeds to the remaining gates. Implement the preflight portions of `stageRestore`. Run:

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.MaliciousZipRestoreTest
.\gradlew.bat :core:data:testDebugUnitTest --tests 'com.paherdin.record.core.data.ZipPreflightDeclaredTest' --tests 'com.paherdin.record.core.data.ZipStreamingPreflightTest'
```

Expected: PASS; declared and streaming limits are enforced independently of each other and of the manifest.

### Task 7: Staging import and record-level validation

**Files:** create `RestoreStager.kt`; modify `RecordDao.kt`, `RecordRepository.kt`; create `StagingValidationTest.kt`.

- [ ] Write failing tests for FK-safe staging, strict records, invariants, attachment/catalog rules, and the exact canonical receipt fields/order/UTF-8 bytes. Prove its SHA-256 changes for every bound field, one-candidate registry behavior, stale/replaced/prior-process handles, and catalog fault injection. Implement `StagingReceipt`, `RestoreStager`, and `stageRestore`. Run:

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.StagingValidationTest
.\gradlew.bat :core:data:testDebugUnitTest --tests 'com.paherdin.record.core.data.StagingReceiptTest'
```

Expected: PASS; the current vault is untouched, every invalid candidate has deterministic classification/residue, and success returns the exact immutable handle later required by confirm.

### Task 8: Repository operation gate, live handoff, and `RESTORE_ACTIVATION`

**Files:** create `VaultOperationGate.kt`, `VaultBinding.kt`, `RestoreActivationCoordinator.kt`, `RestoreFaultHook.kt`; modify `RecordRepository.kt` (`confirmRestore(staged)`, shared leases, common resolver, complete-binding rebind); create `GenerationActivationTest.kt`, `RepositoryGenerationHandoffTest.kt`.

- [ ] Write failing tests for gate drain/fairness, receipt matching, invalid handles, cancellation boundary, close-before-switch, atomic complete-binding swaps, and concurrent callers obtained through the same session registry. Assert every caller observes the same repository identity and DAO/file collaborators match the durable pointer; cover steps 0–6. Implement gate, binding, resolver, confirm, and cold startup. Run:

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.GenerationActivationTest,com.paherdin.record.core.data.room.RepositoryGenerationHandoffTest
```

Expected: PASS for all three scenarios at every boundary; the passed repository instance uses C after success and O after rollback, cold startup resolves process death after `PENDING`, and retirement remains idempotent/re-entrant.

### Task 9: Round trip, bootstrap/startup order, and applicable migration recovery

**Files:** create `RoundTripFixture.kt`, `RoundTripTest.kt`, `StartupGateOrderTest.kt`; reuse `LegacyGenerationBootstrapTest.kt` and `RepositoryGenerationHandoffTest.kt`.

- [ ] Write failing tests: (a) the canonical v2 fixture, including `OPENING_BALANCE`, exports, wipes, stages, confirms through the common resolver, compares every fact/relation/balance/attachment field, then restarts and rechecks. (b) Startup order is legacy classification/bootstrap → migration → generation → Room; pending v1/v2 migration blocks restore and resumes first; database schema provenance is never a logical gate; pending activation never opens mixed state. (c) The same repository is usable on the selected generation immediately after confirm. Run:

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.RoundTripTest,com.paherdin.record.core.data.room.StartupGateOrderTest,com.paherdin.record.core.data.room.LegacyGenerationBootstrapTest,com.paherdin.record.core.data.room.RepositoryGenerationHandoffTest
```

Expected: PASS; the restored vault is byte-comparable for all eight datasets, both attachment owners survive with one canonical record, and the v2 schema opens without migration.

### Task 10: Backup policy, property, public-surface, and boundary checks

**Files:** modify `app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/res/xml/backup_rules.xml`; create `BackupViolationClassificationTest.kt` if not already; update `ApiSurfaceTest.kt`, `api/expected-surface.txt`, and positive/negative `apiGuard` fixtures. No `:app` test dependency is added.

- [ ] Run this exact source-rule assertion before editing and require failure on the current root-only files. Update both XML files with exactly the nine domains, rerun the same assertion and require `POLICY_SOURCE PASS`, then build and inspect merged/packaged artifacts:

```powershell
$p5ExpectedDomains = @('database','device_database','device_file','device_root','device_sharedpref','external','file','root','sharedpref')
$p5SourceManifest = Get-Content -Raw 'app/src/main/AndroidManifest.xml'
if ($p5SourceManifest -notmatch 'android:allowBackup="false"' -or $p5SourceManifest -notmatch 'android:dataExtractionRules="@xml/data_extraction_rules"' -or $p5SourceManifest -notmatch 'android:fullBackupContent="@xml/backup_rules"' -or $p5SourceManifest -match 'android.permission.INTERNET') { throw 'source manifest policy mismatch' }
[xml]$p5Extraction = Get-Content -Raw 'app/src/main/res/xml/data_extraction_rules.xml'
[xml]$p5Compat = Get-Content -Raw 'app/src/main/res/xml/backup_rules.xml'
function Assert-P5Domains($nodes, $label) {
    $actual = @($nodes | ForEach-Object { $_.domain }) | Sort-Object -Unique
    $diff = Compare-Object ($p5ExpectedDomains | Sort-Object) $actual
    if ($diff) { throw "$label domain mismatch: $($diff | Out-String)" }
}
Assert-P5Domains $p5Extraction.'data-extraction-rules'.'cloud-backup'.exclude 'cloud-backup'
Assert-P5Domains $p5Extraction.'data-extraction-rules'.'device-transfer'.exclude 'device-transfer'
Assert-P5Domains $p5Compat.'full-backup-content'.exclude 'full-backup-content'
'POLICY_SOURCE PASS'

.\gradlew.bat :app:assembleDebug
$p5Apk = 'app/build/outputs/apk/debug/app-debug.apk'
$p5Aapt = Join-Path $p5Sdk 'build-tools/36.0.0/aapt2.exe'
$p5MergedManifest = 'app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml'
Get-Content 'app/src/main/AndroidManifest.xml'
Get-Content 'app/src/main/res/xml/data_extraction_rules.xml'
Get-Content 'app/src/main/res/xml/backup_rules.xml'
Get-Content $p5MergedManifest
$p5Badging = & $p5Aapt dump badging $p5Apk
if ($p5Badging -match 'android.permission.INTERNET') { throw 'INTERNET present in APK' }
$p5ManifestDump = & $p5Aapt dump xmltree --file AndroidManifest.xml $p5Apk
$p5ExtractionDump = & $p5Aapt dump xmltree --file res/xml/data_extraction_rules.xml $p5Apk
$p5CompatDump = & $p5Aapt dump xmltree --file res/xml/backup_rules.xml $p5Apk
if ($p5ManifestDump -notmatch 'allowBackup.*=false' -or $p5ManifestDump -notmatch 'dataExtractionRules' -or $p5ManifestDump -notmatch 'fullBackupContent') { throw 'packaged manifest policy mismatch' }
$p5ExtractionDomains = [regex]::Matches(($p5ExtractionDump -join "`n"), 'domain="([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
$p5CompatDomains = [regex]::Matches(($p5CompatDump -join "`n"), 'domain="([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
foreach ($domain in $p5ExpectedDomains) {
    if (@($p5ExtractionDomains | Where-Object { $_ -eq $domain }).Count -ne 2) { throw "packaged extraction count mismatch: $domain" }
    if (@($p5CompatDomains | Where-Object { $_ -eq $domain }).Count -ne 1) { throw "packaged compat count mismatch: $domain" }
}
if ($p5ExtractionDomains.Count -ne 18 -or $p5CompatDomains.Count -ne 9) { throw 'packaged rule has extra domain' }
$p5ManifestDump
$p5ExtractionDump
$p5CompatDump
'POLICY_PACKAGED PASS'
```

Expected: the source assertion first fails on eight missing domains, then passes; source/packaged rules have no missing/extra domain; manifest layers deny backup/reference both rules; network declarations are absent. Record compileSdk 36.0 and that API 36.1 cross-platform rules are inapplicable under the frozen baseline, plus SHA-256 for source manifest, both source rules, merged manifest, and APK.

- [ ] Dump the full public surface, apply the reviewed output, then run normal assertion mode and independent-consumer checks:

```powershell
.\gradlew.bat :core:data:testDebugUnitTest --tests 'com.paherdin.record.core.data.ApiSurfaceTest.publicSurfaceMatchesAllowlistExactly' -PapiSurfaceDump=true --info
.\gradlew.bat :core:data:testDebugUnitTest --tests 'com.paherdin.record.core.data.ApiSurfaceTest'
.\gradlew.bat :core:data:apiBoundaryCheck
```

Expected: exact inherited P1–P4 surface plus the normative P5 delta; negative fixtures cannot access entities, DAO/state writers, generation paths, hooks, coordinators, or the startup gates; no path bypasses the typed surface for export/staging/activation.

### Task 11: Complete mapped device fault suite, regression, launch, and evidence

**Files:** all P5 `androidTest` files; no production changes unless a failed assumption triggers the project failure protocol.

- [ ] Run all P5 instrumented tests together so shared-state/order bugs cannot hide behind per-class runs:

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.paherdin.record.core.data.room
```

Expected: every `GENERATION_BOOTSTRAP`, `BACKUP_STREAM_AND_PUBLISH`, `BACKUP_SAF_COPY`, `RESTORE_ACTIVATION`, and applicable `MIGRATION_RECOVERY` fail-point ID passes all three scenarios. A failure of a technical assumption triggers the ADR/spec/plan reapproval protocol; a harness failure is repaired and rerun without changing the PoC conclusion.

- [ ] Run the complete host and device gates and launch regression:

```powershell
.\gradlew.bat clean check
.\gradlew.bat :core:data:connectedDebugAndroidTest
.\gradlew.bat :app:installDebug
& $p5Adb shell getprop ro.build.version.sdk
& $p5Adb shell getprop ro.build.version.release
& $p5Adb shell getprop ro.build.display.id
& $p5Adb shell am start -n com.paherdin.record/.MainActivity
Start-Sleep -Seconds 2
& $p5Adb shell dumpsys activity activities | Select-String 'com.paherdin.record/.MainActivity'
git diff --check
git status --short
```

Expected: `BUILD SUCCESSFUL`, all JVM/device suites PASS, API/module checks PASS, Xiaomi build recorded, and `MainActivity` is resumed. `git status` may contain only the intended P5/core-document changes and pre-existing user changes; nothing is auto-staged or committed.

- [ ] Complete `verification.md` in `Command`/`Environment`/`Result`/`Remaining risk` format. Map criteria 1–14 and the roadmap gate to commands; enumerate every fail point and outcome. For bootstrap record pre-checkpoint logical state, post-checkpoint frozen source hashes, v1/v2 record hashes, candidate hash, pointer state, and no deletion. For handoff record session identity, staged receipt/digest, complete binding generation, and C/O outcome. Also record schema/manifest/API/policy hashes, seeds, dependencies, and deferred catalog scope.

## Exit gate

The P5 exit gate passes when `verification.md` shows: non-destructive v1/root-layout bootstrap and same-instance repository handoff pass `GENERATION_BOOTSTRAP`; the automated export → wipe/overwrite → restore → startup comparison matches every current-schema fact, relation, balance, attachment owner, size, and SHA-256; all binding ZIP limits pass declared and streamed tests; both backup mappings, `RESTORE_ACTIVATION`, and applicable `MIGRATION_RECOVERY` pass; source/merged/final-APK manifest plus packaged API-34/compatible rules exclude all user data and network migration; public-surface/apiBoundary checks, `clean check`, device suites, and launch pass. P6 may then start.

## Lifecycle

- `draft` Revision 2 (this document): ADR 0010 is accepted, but the implementation plan and spec Revision 2 remain unapproved; no implementation.
- `approved` → `in-progress`: the user explicitly approves this plan and spec Revision 2 together; the approval date/context is recorded here; P5 becomes the unique `in-progress` slice and implementation begins.
- `completed`: acceptance criteria and the roadmap P5 exit gate have verification evidence; P6 may then start.

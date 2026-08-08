# Tech PoC P3 — Attachment Failure Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Status:** `completed` — read-only history; the user confirmed P3 acceptance on 2026-08-08 after a two-axis code review; later corrections use a new specification or ADR.
- **Spec:** [spec.md](spec.md) — Revision 1 `approved` (the user selected the zero-link `CONTENT_READY` representation on 2026-08-08 after plan validation; the original spec had already passed three two-axis review passes with 15 findings closed).
- **ADR 0007:** [recoverable-migration-snapshot ownership](../../../architecture/decisions/0007-recoverable-migration-snapshot-ownership.md) — accepted 2026-08-08; P3 establishes the coordinator, M7 owns the full N-1/N-2 matrix.
- **ADR 0008:** [zero-link attachment cleanup marker](../../../architecture/decisions/0008-zero-link-attachment-cleanup-marker.md) — accepted 2026-08-08 after the user selected option A during plan validation; new and freshly reused zero-link content receives a persisted full grace period without a new schema field.
- **Revision 1:** 2026-08-08 — fixed the plan-validation findings: explicit declared-MIME input; zero-link `CONTENT_READY` rows start cleanup pending through `deletedAt = ingestedAt`; pure validation is separated from the Android image probe; atomic move has no non-atomic fallback; copy/move fault seams cover every named boundary; unknown/unavailable identities have exact outcomes; the recovery-record format and coordinator-first migration order are pinned; and executable verification commands replace summary-only steps.
- **Revision 2:** 2026-08-08 — user-confirmed implementation finding after code review: the deduplication decision table gains `ADOPT_VERIFIED_BLOB` (no canonical row but a *verified* orphan blob at the target → adopt the blob and create the row, per spec 4.5 orphan rules; only an unverifiable occupied target is `CONFLICTING_METADATA`). The `AttachmentDedupDecider` decision function and its decision table in this plan are updated accordingly.

**Goal:** Prove validated image ingestion, content deduplication, retry-safe cleanup, and owner-level atomic visibility under interruption, on the first schema change (v1 → v2). An internal attachment pipeline (validation → hash → per-hash serialized deduplication → atomic move → owner commit → unlink → cleanup) runs under the public repository surface; the migration coordinator implements the data-and-storage recoverable-migration-snapshot protocol; fault tests cover every fail point of the `ATTACHMENT_SOURCE_COPY`, `ATTACHMENT_CONTENT_COMMIT`, and applicable `MIGRATION_RECOVERY` mappings with the three-scenario protocol.

**Architecture:** all attachment machinery is module-internal in `:core:data` (the same visibility pattern as the P1 XOR validator and P2 finance validator). Public additions are additive: three entry points (`ingestAttachment`, `linkAttachment`, `unlinkAttachment`), three additive `RepositoryResult.Failure` members sharing one taxonomy, and one defaulted additive field each on `NewTransaction`/`NewNote`. A newly ingested canonical row is zero-link cleanup-pending (`deletedAt = ingestedAt`); verified owner linking clears the marker and writes the link atomically. The coordinator and recovery-record machinery land before runtime schema v2 is enabled; then one guarded change adds exactly `Attachment` and `AttachmentLink` with an explicit `Migration(1, 2)`. The committed `1.json` stays unchanged and `2.json` is committed. No new dependency declaration is added.

**Tech Stack:** unchanged from P1/P2 — Gradle 9.5.0, AGP 9.3.0 (built-in Kotlin 2.2.10), KSP2 2.3.9, Room 2.8.4, Room's existing `kotlinx-coroutines-core` API dependency (1.8.1 in the pinned Room 2.8.4 metadata), `kotlin-metadata-jvm` 2.2.10 (test-time), androidx.test runner/ext-junit, JUnit 4.13.2. SHA-256 uses JDK `java.security.MessageDigest`; file operations use `java.io.File`/`java.nio.file`; image decodability uses an internal Android `BitmapFactory` bounds probe feeding a pure classifier; the per-hash registry uses `kotlinx.coroutines.sync.Mutex`; the property-test generator is hand-rolled. No new dependency declaration is added; if the pinned combination fails during execution, this plan is revised and re-approved first — versions are never switched mid-execution.

**Reference environment (frozen):** Xiaomi 14 Pro (physical, USB debugging), Android 14 / API 34 out-of-box baseline. The actual system build is recorded in `verification.md` at verification time (currently API 36 per P0–P2; recorded, not rejected).

**Namespaces:** `:core:data` → `com.paherdin.record.core.data` (public API), Room package `com.paherdin.record.core.data.room`; `:core:model` surface is unchanged (exactly `EntityType` and `TransactionType`).

**Pre-approval dependency validation:** none required. P3 introduces no new resolved artifact: `MessageDigest` and file I/O are JDK APIs, `BitmapFactory` and `AtomicFile` are Android platform APIs, and coroutines 1.8.1 is already an API dependency of the pinned Room 2.8.4 runtime. Task 1 records dependency insight so this assumption is machine-checked before implementation.

## Dependency review record

No new dependency declaration enters the version catalog or any build in P3. The P1 review record remains valid for every dependency reused; P3 directly uses the coroutines API already exported by the pinned Room runtime and records the resolved version in verification. Any later change that adds or changes a resolved artifact must first pass the engineering conventions' dependency review record (version, license, maintenance, compatibility impact, removal path) at plan approval.

## Public API of `:core:data` — P3 normative delta

The P1/P2 normative declaration list is inherited **unchanged**, except that `NewTransaction` and `NewNote` each gain one additive field (source-breaking for existing call sites; all fixtures are recompiled with the delta). The P3 surface is the P1 list plus exactly this additive delta, pinned here so the regenerated `api/expected-surface.txt` is derived from this list:

```kotlin
// package com.paherdin.record.core.data — additive members only

enum class AttachmentViolation {
    UNDECODABLE,
    SIZE_LIMIT_EXCEEDED,
    MIME_TYPE_NOT_ACCEPTED,
    CONTENT_IDENTITY_UNKNOWN,
    CONTENT_NOT_AVAILABLE,
}

enum class CorruptionDetail { METADATA_BLOB_SPLIT, HASH_MISMATCH, SIZE_MISMATCH, CONFLICTING_METADATA }

enum class AttachmentStage {
    TEMP_COPY,
    VALIDATION_HASH,
    ATOMIC_MOVE,
    METADATA_WRITE,
    PHYSICAL_DELETE,
    METADATA_DELETE,
    ORPHAN_CLEANUP,
}

// existing sealed interface RepositoryResult<out T> gains exactly these three Failure members —
// one shared taxonomy for every attachment operation, matching the P1/P2 single-hierarchy pattern;
// every P1/P2 member remains exactly as pinned
sealed interface RepositoryResult<out T> {
    sealed interface Failure : RepositoryResult<Nothing> {
        data class AttachmentRejected(val violation: AttachmentViolation) : Failure
        data class AttachmentCorrupted(val detail: CorruptionDetail) : Failure
        data class AttachmentIoFailed(val stage: AttachmentStage) : Failure
    }
}

// existing RecordRepository class gains exactly these three methods
suspend fun ingestAttachment(source: File, declaredMimeType: String): RepositoryResult<String>
suspend fun linkAttachment(ownerEntityRefId: String, contentIdentity: String): RepositoryResult<Unit>
suspend fun unlinkAttachment(ownerEntityRefId: String, contentIdentity: String): RepositoryResult<Unit>

// existing data classes gain exactly one additive field each, with a default so the delta
// stays source-compatible for existing call sites
data class NewTransaction(/* P1 fields unchanged */, val attachmentContentIds: Set<String> = emptySet())
data class NewNote(/* P1 fields unchanged */, val attachmentContentIds: Set<String> = emptySet())
```

Semantics:

- `ingestAttachment(source, declaredMimeType)` reads an app-private-readable file (PoC input; the M3 picker supplies a SAF stream plus its resolver-provided MIME), copies it into app-private `TEMP` on the same filesystem as the content path, normalizes the explicit declared MIME by trimming, removing parameters after `;`, and applying `lowercase(Locale.ROOT)`, and compares it with the normalized decoded MIME from the Android bounds probe. It validates the PoC image-type set (`image/jpeg`, `image/png`, `image/webp`, `image/gif`), the 32 MiB PoC limit, and decodability; computes SHA-256; deduplicates under per-hash serialization; and publishes the blob only through `Files.move(..., ATOMIC_MOVE)` with no non-atomic fallback. A new canonical `Attachment` row is committed with `deletedAt = ingestedAt`, making the zero-link `CONTENT_READY` state cleanup-pending from a deterministic instant. It returns `Success(contentIdentity)` only after the row/blob pair is durably prepared and never trusts a caller-supplied hash.
- `linkAttachment(ownerEntityRefId, contentIdentity)` links an existing Transaction/Note owner to canonical content, re-entering the zero-link cleanup-pending cancellation path: under the shared per-hash `Mutex`, recheck target existence, verify actual SHA-256 and size, then clear `deletedAt` and commit the link in one Room transaction. An unknown row is `AttachmentRejected(CONTENT_IDENTITY_UNKNOWN)`; a zero-link pending row whose blob has already been deleted is `AttachmentRejected(CONTENT_NOT_AVAILABLE)` unless a prior fresh ingestion restored it; a linked/active row missing bytes is `AttachmentCorrupted(METADATA_BLOB_SPLIT)`. Every failure commits nothing. A non-Transaction/Note endpoint (no such EntityRef, or an entity that is not a Transaction/Note) is `EndpointRejected`.
- `unlinkAttachment(ownerEntityRefId, contentIdentity)` removes the owner link in one transaction; at zero global link count the canonical Attachment enters cleanup pending (`deletedAt = clock.now()`) in the same transaction.
- The three `Failure` members share one taxonomy and cover every attachment operation: `AttachmentRejected` (invalid content), `AttachmentCorrupted` (metadata/blob split or mismatch), `AttachmentIoFailed(stage)` (file IO failure at a named stage). Callers do not auto-retry: the same caller-behavior class as the P1/P2 rejections. ABI-additive, but adding direct subtypes to sealed `Failure` is source-breaking for a consumer that recompiles an exhaustive `when`; the consumer fixtures are updated with the new branches (same rule as P2).
- `File` is the ingestion source type for the PoC; the plan records that the M3 surface replaces it with the SAF content resolver. No `Uri` type enters `:core:data` in P3.

## Deterministic rejection classification

Ingestion classification order (first match wins): normalized declared MIME outside the PoC set → `MIME_TYPE_NOT_ACCEPTED`; size over the PoC limit → `SIZE_LIMIT_EXCEEDED`; bounds decode fails or returns no decoded MIME → `UNDECODABLE`; normalized decoded MIME outside the set or different from the declared MIME → `MIME_TYPE_NOT_ACCEPTED`. Then deduplication classification under the shared per-hash serialization: active/linked row exists and blob verifies (hash + size) → reuse its canonical ID without changing `deletedAt == null`; zero-link pending row exists and blob verifies → reuse its canonical ID and refresh `deletedAt = ingestedAt`; zero-link pending row exists but blob is absent → atomically restore the validated candidate at the canonical target and retain the same ID with `deletedAt = ingestedAt`; linked/active row exists but blob is absent or mismatched → `AttachmentCorrupted(METADATA_BLOB_SPLIT/HASH_MISMATCH/SIZE_MISMATCH)`, quarantine only the new unlinked input, never overwrite; no row but a *verified* orphan blob occupies the target (failed-owner residue) → adopt the verified blob and create the row with `deletedAt = ingestedAt` (`ADOPT_VERIFIED_BLOB`, spec 4.5 orphan rules — the incoming temp is discarded); no row and the occupied target does not verify → `AttachmentCorrupted(CONFLICTING_METADATA)`; otherwise atomic move → metadata write with `deletedAt = ingestedAt`. Atomic-move unavailability or failure is `AttachmentIoFailed(ATOMIC_MOVE)` and never triggers a non-atomic fallback.

Owner-save and link-management classification: missing or non-Transaction/Note endpoint → `EndpointRejected` before attachment classification; unknown `contentIdentity` → `AttachmentRejected(CONTENT_IDENTITY_UNKNOWN)`; zero-link pending row with already-absent bytes → `AttachmentRejected(CONTENT_NOT_AVAILABLE)`; linked/active row with missing bytes or any row with mismatched bytes → `AttachmentCorrupted` with the exact `CorruptionDetail`. Cleanup-pending cancellation requires successful byte verification; only then may the same Room transaction clear `deletedAt` and insert the link. No failure commits an owner, marker clear, or link.

## Storage layout (P3)

- `files/attachments/tmp/` — app-private TEMP staging on the same filesystem as the content path.
- `files/attachments/content/<sha256>` — content-addressed blobs, one per `contentIdentity`.
- `files/attachments/quarantine/` — quarantined unlinked new inputs and corrupt zero-link targets.
- `files/migration/snapshot-<snapshotId>.db` — immutable published snapshot; `snapshotId` is an app-generated UUID, not a timestamp.
- `files/recovery/activation-recovery-v1.json` — version-1 activation/recovery record, read and atomically written through Android `AtomicFile` before Room/SQLite initialization. P3 writes only its migration portion; P5 later adds the activation portion without changing the P3 field meanings.
- Grace period: recorded constant (7 days) with an injectable clock; tests control time deterministically.
- `ponytail:` comment where relevant: the P3 layout is pre-generation; P5 introduces `files/vaults/<generationId>/attachments/` and the restore slice owns that path evolution explicitly.

### Recovery record v1

UTF-8 JSON uses this exact shape; enum names are stored verbatim and unknown `recordVersion`, states, stages, missing fields, duplicate keys, absolute paths, or normalized paths escaping the app-private root fail closed before Room/SQLite opens:

```json
{
  "recordVersion": 1,
  "migrationRecoveryState": "MIGRATION_HEALTHY",
  "migration": null
}
```

For `MIGRATION_RECOVERY_PENDING`, `migration` is non-null and contains exactly `snapshotId`, `snapshotSha256`, `liveDatabasePath`, `workDatabasePath`, `restoreTempPath`, `quarantinePath`, and `stage`; all paths are normalized app-data-root-relative strings, and `stage` is one of `PREPARED`, `FAILED_SET_ISOLATED`, `RESTORE_COPY_VALIDATED`, or `MAIN_REPLACED`. `MIGRATION_RECOVERED` has the same completed migration payload until a later atomic normalization sets `MIGRATION_HEALTHY` and `migration = null`. On first P3 startup only, an absent record is initialized atomically to the healthy shape before migration preflight. A pending record is never deleted or inferred from file presence.

Startup order is fixed: read/validate the recovery record before any SQLite/Room object; resume a pending record to `MIGRATION_RECOVERED` or keep Room blocked; if healthy and the live database is absent, allow Room to create a fresh v2 database without a migration snapshot; otherwise use old-schema-compatible SQLite access to read `user_version`, checkpoint WAL, and close all handles. Version 1 then follows snapshot → forced Room migration/open → post-open validation; version 2 opens normally; any other version fails closed. `RecordRepository.open()` forces `openHelper.writableDatabase` before returning `Success`, so a lazy Room builder cannot report success before migration/open completes. Storage exhaustion maps to existing `StorageExhausted`; malformed recovery state, unrecovered pending state, migration/open failure, or post-open validation failure maps to existing `TransactionFailed`; raw paths, JSON, and SQLite exceptions never cross the public boundary.

## File inventory

| File | Purpose |
|---|---|
| `core/data/src/main/java/com/paherdin/record/core/data/room/Attachment.kt` | `internal` `AttachmentEntity` (`id` PK, `contentIdentity` UK, nullable `deletedAt`; new zero-link rows start non-null) and `AttachmentLinkEntity` (composite PK `(attachmentId, ownerEntityRefId)`, both FKs `NO ACTION`, index on `ownerEntityRefId`) |
| `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDatabase.kt` | `databaseVersion = 2`, `Migration(1, 2)` creating exactly the two new tables, `exportSchema = true` (commits `2.json`) |
| `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDao.kt` | additive internal queries: canonical row by `contentIdentity`, `AttachmentLink` count/select/delete, `Attachment` `deletedAt` mark/clear/delete, zero-link scan for the cleaner |
| `core/data/schemas/.../2.json` | committed v2 schema export; `1.json` unchanged |
| `core/data/src/main/java/com/paherdin/record/core/data/RepositoryResult.kt` | `AttachmentViolation`/`CorruptionDetail`/`AttachmentStage` enums and the three additive `RepositoryResult.Failure` members (P1 pattern: `XorViolation` lives in the same file) |
| `core/data/src/main/java/com/paherdin/record/core/data/Model.kt` | `NewTransaction`/`NewNote` additive `attachmentContentIds` field with default |
| `core/data/src/main/java/com/paherdin/record/core/data/AttachmentValidator.kt` | `internal` pure classifier over normalized declared MIME, decoded MIME, byte size, and decodable flag; contains the exact precedence and SHA-256 helper and has no Android decode call |
| `core/data/src/main/java/com/paherdin/record/core/data/AttachmentImageProbe.kt` | `internal` Android adapter using `BitmapFactory.Options.inJustDecodeBounds`; returns decoded MIME + decodability to the pure validator; exercised by device tests |
| `core/data/src/main/java/com/paherdin/record/core/data/AttachmentHashLockRegistry.kt` | one repository-owned shared keyed registry of reference-counted `kotlinx.coroutines.sync.Mutex` instances; ingestion, owner save/link/unlink, and cleaner receive the same instance; map acquire/release is atomic and entries are removed only when the reference count reaches zero after unlock |
| `core/data/src/main/java/com/paherdin/record/core/data/AttachmentIo.kt` | `internal` injectable copy/flush/close and atomic-move adapter. Production uses fixed 64 KiB chunks and `Files.move(..., ATOMIC_MOVE)` only; tests inject source-read, flush, close, and atomic-move failures without changing production paths |
| `core/data/src/main/java/com/paherdin/record/core/data/AttachmentContentStore.kt` | `internal` TEMP/content/quarantine paths, atomic publication, idempotent physical deletion, orphan timestamps/classification, orphan scan, and quarantine; invalid/missing orphan timestamps retain residue |
| `core/data/src/main/java/com/paherdin/record/core/data/AttachmentIngestor.kt` | `internal` ingestion pipeline: copy → platform probe + pure validation → hash → shared per-hash serialization → deduplication decision → atomic move → zero-link metadata write with `deletedAt = ingestedAt`; returns typed outcomes |
| `core/data/src/main/java/com/paherdin/record/core/data/AttachmentCleaner.kt` | `internal` cleaner: zero-link `deletedAt` grace gating, zero-reference recheck, physical deletion, expected missing-blob completion, metadata-deletion transaction, file-only orphan cleanup, injectable clock |
| `core/data/src/main/java/com/paherdin/record/core/data/AttachmentFaultHook.kt` | `internal` named boundaries: `beforeTempCreate`, `beforeSourceRead(chunkOrdinal)`, `afterCopyChunk(chunkOrdinal, bytesCopied, totalBytes)`, `afterFinalWriteBeforeClose`, `beforeDestinationFlush`, `afterCloseBeforeValidation`, `beforeAtomicMove`, `afterAtomicMove`, `beforeMetadataWrite`, `beforeLinkCommit`, `afterChildWrite(ordinal)`, `beforeUnlinkCommit`, `beforePhysicalDelete`, `afterPhysicalDelete`, `beforeMetadataDelete`, `afterCommitResponse`; no-op in production |
| `core/data/src/main/java/com/paherdin/record/core/data/MigrationRecoveryRecordStore.kt` | strict streaming `android.util.JsonReader` parser with duplicate-key tracking plus `AtomicFile` writer for the exact v1 recovery record; path normalization and fail-closed validation are pure-testable |
| `core/data/src/main/java/com/paherdin/record/core/data/MigrationCoordinator.kt` | `internal` coordinator: checkpoint/close, snapshot copy, integrity + old-schema invariant validation, SHA-256, atomic publication, migration invocation, v1 recovery state machine, startup resolution gate |
| `core/data/src/main/java/com/paherdin/record/core/data/MigrationFaultHook.kt` | `internal` hook: `beforeSnapshotCopy`, `afterSnapshotCopy`, `beforePublish`, `afterPublish`, `beforeMigration`, `duringMigration`, `afterPendingPersistBeforeMove`, `beforeSidecarIsolationEntry(name)`, `afterSidecarIsolationEntry(name)`, `beforeSnapshotReplace`, `afterSnapshotReplace`, `beforeRestoreCopyValidation`, `afterMigrationRecovered` |
| `core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt` | additive methods + owner-save attachment resolution/verification inside the existing transactions |
| `core/data/api/expected-surface.txt` | regenerated from the normative list above |
| `core/data/src/test/java/com/paherdin/record/core/data/AttachmentValidatorTest.kt` | JVM: normalized declared/decoded MIME, size/decodability matrix, classification precedence, exact-boundary fixtures (32 MiB and 1 byte over), no Android runtime dependency |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/MigrationRecoveryRecordTest.kt` | device: exact JSON shape, duplicate/unknown/missing-field rejection, enum/state invariants, relative-path normalization, `AtomicFile` round trip |
| `core/data/src/test/java/com/paherdin/record/core/data/AttachmentDedupDecisionTest.kt` | JVM: the deduplication decision table — reuse / atomic-move / corruption classification for every canonical-row × blob state combination, per spec §6 |
| `core/data/src/test/java/com/paherdin/record/core/data/AttachmentOrphanClassifierTest.kt` | JVM: orphan classification — TEMP partial vs moved-but-uncommitted vs failed-owner orphan vs zero-link `CONTENT_READY` row, per spec §6 |
| `core/data/src/test/java/com/paherdin/record/core/data/AttachmentHashLockRegistryTest.kt` | JVM: same hash serializes, different hashes proceed independently, cancellation releases references, and the registry returns to zero entries without an acquire/remove race |
| `core/data/src/test/java/com/paherdin/record/core/data/AttachmentPropertiesTest.kt` | JVM property: global one-to-one SHA-256 → canonical metadata/blob, arbitrary many valid owner links, second row always invalid, seeds recorded on failure |
| `core/data/src/test/java/com/paherdin/record/core/data/ApiSurfaceTest.kt` | regenerated allowlist check (existing machinery) |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AttachmentSourceCopyTest.kt` | criterion 1: copy matrix, three-scenario protocol, residue classification |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AttachmentValidationTest.kt` | criterion 2: real `BitmapFactory` probe, explicit declared MIME/decoded MIME matrix, size/decode rejection, hash correctness, no trust of caller hash |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AttachmentDeduplicationTest.kt` | criterion 3: Transaction + Note owners share one canonical, duplicate reuse, concurrent identical ingestion, unique-constraint backstop |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AttachmentEndpointTest.kt` | endpoint rule: non-Transaction/Note owner → `EndpointRejected`; Note-owner link/unlink through the public surface |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AttachmentAtomicVisibilityTest.kt` | criterion 4: atomic move + owner-transaction failure, external-reader latch snapshots |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AttachmentDeletionTest.kt` | criterion 5: unlink matrix, zero-link cleanup-marker transaction failure, physical deletion retry, expected metadata-deletion window, idempotence |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AttachmentRelinkTest.kt` | criterion 6: cleanup racing relink, byte reverification, missing-blob relink |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AttachmentCorruptionTest.kt` | criterion 7: metadata/blob split, hash/size mismatch, conflicting metadata, quarantine limits, fail-closed |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AttachmentOrphanCleanupTest.kt` | criterion 8: TEMP partials, moved-but-uncommitted, failed-owner orphans, grace gating, idempotent reruns |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AttachmentSchemaContractTest.kt` | criterion 9(a): `2.json` contract — `databaseVersion = 2`, eight tables, v1 encodings unchanged, migration from the `1.json` fixture |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/MigrationRecoveryTest.kt` | criterion 10: v1 fixture → coordinator upgrade, every named snapshot/migration/pending/isolation/restore boundary, snapshot immutability, recovered-vault equality |
| `core/data/apiGuard/positive/AttachmentSuccess.kt`, `apiGuard/negative/*` | apiBoundaryCheck fixtures: positive unpacking of new outcomes; negative compilation proving no path bypasses the typed surface for links/attachment state |
| `docs/specs/tech-poc/p3-attachment-failure-recovery/verification.md` | Step 10: the evidence record mapping every criterion and the exit gate to commands and results (P1/P2 precedent) |

## Tasks

### Task 0: Lifecycle entry

**Files:** `plan.md`, `spec.md`, `AGENTS.md`, `docs/index.md`, `docs/roadmap.md`; create `verification.md`.

- [ ] After explicit plan approval, record its date/context, move the plan and spec to `in-progress`, make P3 the unique active slice in all lifecycle sources, and create `verification.md` with the initial slice-state record. Do this before any implementation edit.

### Task 1: Toolchain, dependency, schema-source, and device preflight

**Files:** none; record results in `verification.md` after approval.

- [ ] Resolve JDK 17+, Gradle, Android SDK, and the reference device without changing persistent environment configuration:

```powershell
$p3Java = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (-not (Test-Path $p3Java)) { throw 'Set process-local JAVA_HOME to JDK 17+ before P3 execution' }
$p3Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$p3Adb = Join-Path $p3Sdk 'platform-tools/adb.exe'
if (-not (Test-Path $p3Adb)) { throw "adb not found: $p3Adb" }
& $p3Java -version
.\gradlew.bat --version
& $p3Adb devices -l
.\gradlew.bat :core:data:dependencies --configuration debugCompileClasspath | Select-String 'kotlinx-coroutines-core'
$p3SchemaV1 = (Get-FileHash 'core/data/schemas/com.paherdin.record.core.data.room.RecordDatabase/1.json' -Algorithm SHA256).Hash
$p3SchemaV1
```

Expected: JDK 17+, Gradle 9.5.0, Xiaomi 14 Pro present, resolved coroutines 1.8.1 through Room 2.8.4, and a non-empty uppercase SHA-256 recorded as the immutable v1 source pin.

### Task 2: Public taxonomy, pure validation, and decision tables

**Files:** modify `RepositoryResult.kt`; create `AttachmentValidator.kt`, `AttachmentValidatorTest.kt`, `AttachmentDedupDecisionTest.kt`, `AttachmentOrphanClassifierTest.kt`.

- [ ] Write tests first for the exact public enum members, MIME normalization/classification precedence, 32 MiB boundary, SHA-256 fixtures, every dedup row (including expected zero-link missing-blob recovery versus active-row corruption), and orphan timestamp fail-closed behavior. Run and require failure because the P3 declarations do not exist:

```powershell
.\gradlew.bat :core:data:testDebugUnitTest --tests 'com.paherdin.record.core.data.AttachmentValidatorTest' --tests 'com.paherdin.record.core.data.AttachmentDedupDecisionTest' --tests 'com.paherdin.record.core.data.AttachmentOrphanClassifierTest'
```

Expected before implementation: FAIL from missing P3 types. Implement the normative taxonomy and pure functions, rerun the same command, and require PASS. No `android.*` decode call may appear in `AttachmentValidator.kt`.

### Task 3: Recovery record and coordinator before schema enablement

**Files:** create `MigrationRecoveryRecordStore.kt`, `MigrationCoordinator.kt`, `MigrationFaultHook.kt`, `MigrationRecoveryRecordTest.kt`, `MigrationRecoveryTest.kt`; modify `RecordRepository.kt` only to introduce a coordinator-gated builder seam while `RecordDatabase.version` remains 1.

- [ ] Write the record parser/store tests first: exact healthy/pending/recovered shapes, duplicate/unknown/missing fields, path escape, state/payload mismatch, interrupted `AtomicFile` write, and stable round trip. Write coordinator tests for checkpoint/close, snapshot copy/validation/hash/publication and pending recovery. Compile before implementation and require missing-type failure:

```powershell
.\gradlew.bat :core:data:compileDebugAndroidTestKotlin
```

- [ ] Implement the strict v1 record and coordinator. At this task boundary `RecordDatabase.version` is still 1 and no runtime migration exists; the production `RecordRepository.open()` must call the startup gate before constructing Room, while isolated tests receive an explicit coordinator fixture rather than silently bypassing it. Run:

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.MigrationRecoveryRecordTest
```

Expected: PASS; malformed/pending records never construct Room or create a database.

### Task 4: Guarded schema v2 activation and migration recovery

**Files:** create `room/Attachment.kt`, `AttachmentSchemaContractTest.kt`; modify `RecordDatabase.kt`, `RecordDao.kt`, `RecordRepository.kt`; add `2.json` while preserving `1.json`.

- [ ] Add the schema contract test first, then add both entities and `Migration(1, 2)`. In the same logical change, register that migration only inside the coordinator-gated builder; there must be no intermediate runnable state where a production open can migrate without the verified snapshot. The v1 fixture contains one Account, one Transaction EntityRef/Transaction, one AccountEntry, one Note EntityRef/Note, and one TransactionNoteRelation so all six v1 tables and FK relationships are compared after upgrade.

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.AttachmentSchemaContractTest
$p3SchemaV1After = (Get-FileHash 'core/data/schemas/com.paherdin.record.core.data.room.RecordDatabase/1.json' -Algorithm SHA256).Hash
if ($p3SchemaV1After -ne $p3SchemaV1) { throw "v1 schema changed: $p3SchemaV1After" }
```

Expected: PASS; exactly eight tables; v1 rows/encodings/indexes/FKs unchanged; `2.json` present; no destructive fallback.

- [ ] Run `MigrationRecoveryTest` with these named boundaries: before/after snapshot copy, before/after publication, during migration, after pending persist before the first move, before/after each failed-set main/`-wal`/`-shm` isolation entry, before/after restore-copy validation, and before/after atomic main replacement. Every boundary runs fail-once/restart, repeated-failure, and complete/repeat scenarios.

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.MigrationRecoveryTest
```

Expected: PASS; no empty database, mixed sidecars, mutable snapshot, Room open while pending, or recovered-vault mismatch.

### Task 5: Content store, Android probe, hash registry, and ingestion

**Files:** create `AttachmentImageProbe.kt`, `AttachmentHashLockRegistry.kt`, `AttachmentIo.kt`, `AttachmentContentStore.kt`, `AttachmentIngestor.kt`, `AttachmentFaultHook.kt`; modify `RecordRepository.kt`; create the source-copy, validation, deduplication, and corruption tests from the inventory.

- [ ] Write failing tests for keyed-lock same-hash serialization/different-hash independence/cancellation/removal races, explicit declared MIME versus real decoded MIME, the exact copy boundaries, source-read/flush/close errors, unsupported/failed atomic move, after-move-before-row-write residue, zero-link row `deletedAt = ingestedAt`, valid pending-row reuse refreshing the full grace period, missing pending-row restoration, and active-row corruption. The copy fixture is exactly three 64 KiB chunks so chunk ordinals 0/1/2 are first/middle/final.
- [ ] Implement the production I/O adapter with no non-atomic fallback, the Android probe, one shared reference-counted keyed `Mutex` registry, and `ingestAttachment(source, declaredMimeType)`. Ingestor, repository attachment paths, and cleaner must receive the same registry instance from `RecordRepository`; no component constructs a private registry.

```powershell
.\gradlew.bat :core:data:testDebugUnitTest --tests 'com.paherdin.record.core.data.Attachment*Test'
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.AttachmentSourceCopyTest,com.paherdin.record.core.data.room.AttachmentValidationTest,com.paherdin.record.core.data.room.AttachmentDeduplicationTest,com.paherdin.record.core.data.room.AttachmentCorruptionTest
```

Expected: PASS. Each named injected point records all three scenarios and never reports success before the row/blob pair is durable.

### Task 6: Owner saves and typed link management

**Files:** modify `Model.kt`, `RecordRepository.kt`, `RecordDao.kt`; create `AttachmentEndpointTest.kt`, `AttachmentAtomicVisibilityTest.kt`, `AttachmentDeletionTest.kt`, `AttachmentRelinkTest.kt`.

- [ ] Write failing tests for Transaction/Note owner saves, exact unknown/unavailable/corruption outcomes, endpoint precedence, and every owner transaction ordinal. Implement `attachmentContentIds`, `linkAttachment`, and `unlinkAttachment` so verification runs under the shared keyed mutex and the Room transaction clears `deletedAt` plus inserts links atomically. Multiple requested identities are acquired in ascending SHA-256 order and released in reverse order to prevent deadlock.
- [ ] The external-reader latch matrix covers owner header/children/relations, marker state, complete link set, and physical-byte availability at every hook ordinal; no test asserts only row counts.

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.AttachmentEndpointTest,com.paherdin.record.core.data.room.AttachmentAtomicVisibilityTest,com.paherdin.record.core.data.room.AttachmentDeletionTest,com.paherdin.record.core.data.room.AttachmentRelinkTest
```

Expected: PASS; failed saves preserve the complete previous owner and the original zero-link cleanup timestamp.

### Task 7: Cleaner and expected deletion crash window

**Files:** create `AttachmentCleaner.kt`, `AttachmentOrphanCleanupTest.kt`; extend `AttachmentDeletionTest.kt` and `AttachmentRelinkTest.kt`.

- [ ] Write failing tests for seven-day boundary minus 1 ms/exactly seven days, zero-link row cleanup, file-only timestamps, invalid timestamp fail-closed behavior, physical-delete failure, the expected blob-absent/metadata-present retry window, linked-content protection, and cleanup/relink races. Implement the cleaner under the shared keyed mutex.
- [ ] The physical blob and metadata are necessarily removed in two durability steps: after blob deletion and before metadata commit, the non-null zero-link marker makes the missing blob an expected cleanup state. The cleaner confirms absence and deletes metadata; normal link without replacement bytes returns `CONTENT_NOT_AVAILABLE`; linked/active missing bytes remain corruption.

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.AttachmentOrphanCleanupTest,com.paherdin.record.core.data.room.AttachmentDeletionTest,com.paherdin.record.core.data.room.AttachmentRelinkTest
```

Expected: PASS for fail-once/restart, repeated-failure, and complete/repeat at every cleaner fail point.

### Task 8: Property, public-surface, and boundary checks

**Files:** create `AttachmentPropertiesTest.kt`; update `ApiSurfaceTest.kt`, `api/expected-surface.txt`, and positive/negative `apiGuard` fixtures.

- [ ] Generate fixed-seed histories covering ingest, duplicate ingest, link, unlink, pending restoration, cleanup, and corruption. At every prefix assert one SHA-256 → one canonical ID/blob, no linked missing bytes, and no deletion with links. Print the seed on failure.
- [ ] Dump the full public surface, apply the reviewed output with `apply_patch`, then run normal assertion mode and independent-consumer checks:

```powershell
.\gradlew.bat :core:data:testDebugUnitTest --tests 'com.paherdin.record.core.data.AttachmentPropertiesTest'
.\gradlew.bat :core:data:testDebugUnitTest --tests 'com.paherdin.record.core.data.ApiSurfaceTest.publicSurfaceMatchesAllowlistExactly' -PapiSurfaceDump=true --info
$p3ApiXml = [xml](Get-Content 'core/data/build/test-results/testDebugUnitTest/TEST-com.paherdin.record.core.data.ApiSurfaceTest.xml' -Raw)
$p3ApiXml.testsuite.'system-out'
.\gradlew.bat :core:data:testDebugUnitTest --tests 'com.paherdin.record.core.data.ApiSurfaceTest'
.\gradlew.bat :core:data:apiBoundaryCheck
```

Expected: exact inherited P1/P2 surface plus the normative P3 delta; negative fixtures cannot access entities, DAO/state writers, paths, hooks, coordinator, cleaner, or `Float`/`Double` attachment signatures.

### Task 9: Complete mapped device fault suite

**Files:** all P3 `androidTest` files; no production changes unless a failed assumption triggers the project failure protocol.

- [ ] Run all P3 instrumented tests together so shared-state/order bugs cannot hide behind per-class runs:

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.paherdin.record.core.data.room
```

Expected: every `ATTACHMENT_SOURCE_COPY`, storage-side `ATTACHMENT_CONTENT_COMMIT`, and applicable `MIGRATION_RECOVERY` fail-point ID passes all three scenarios. A failure of a technical assumption triggers the ADR/spec/plan reapproval protocol; a harness failure is repaired and rerun without changing the PoC conclusion.

### Task 10: Regression, launch, and evidence

**Files:** create/complete `verification.md`; re-read synchronized core docs.

- [ ] Run the complete host and device gates and launch regression:

```powershell
.\gradlew.bat clean check
.\gradlew.bat :core:data:connectedDebugAndroidTest
.\gradlew.bat :app:installDebug
$p3Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$p3Adb = Join-Path $p3Sdk 'platform-tools/adb.exe'
& $p3Adb shell getprop ro.build.version.sdk
& $p3Adb shell getprop ro.build.version.release
& $p3Adb shell getprop ro.build.display.id
& $p3Adb shell am start -n com.paherdin.record/.MainActivity
Start-Sleep -Seconds 2
& $p3Adb shell dumpsys activity activities | Select-String 'com.paherdin.record/.MainActivity'
git diff --check
git status --short
```

Expected: `BUILD SUCCESSFUL`, all JVM/device suites PASS, API/module checks PASS, Xiaomi build recorded, and `MainActivity` is resumed. `git status` may contain only the intended P3/core-document changes and pre-existing user changes; nothing is auto-staged or committed.

- [ ] Complete `verification.md` in `Command`/`Environment`/`Result`/`Remaining risk` format. Map criteria 1–11 and the roadmap gate to actual commands; enumerate mapping, fail-point ID, boundary, durable state, visibility/success, residue, restart/retry, and cleanup for every injected point; record the v1/v2 schema hashes, public-surface hash, property seeds, dependency resolution, and the explicit P5 backup-round-trip deviation.

## Exit gate

The P3 exit gate passes when `verification.md` shows: `ATTACHMENT_SOURCE_COPY` and `ATTACHMENT_CONTENT_COMMIT` evidence covers every required copy, validation/hash, atomic move, owner commit, deletion, relink, and cleanup fail point (with the storage-side claim and the backup-round-trip deviation recorded per spec 4.8); no committed link references missing or unverified bytes; duplicate content is reused safely; orphan cleanup is idempotent; the applicable `MIGRATION_RECOVERY` evidence passes; the v2 schema contract, the regenerated full public-signature allowlist, and the independent-consumer `apiBoundaryCheck` machine checks with negative verification pass; `clean check` and the device launch regression pass. P4 may then start.

## Lifecycle

- `draft` (this document): the plan is unapproved; no implementation.
- `approved` → `in-progress`: the user explicitly approves this plan; the approval date/context is recorded here; the spec becomes the unique `in-progress` slice and implementation begins.
- `completed`: acceptance criteria and the roadmap P3 exit gate have verification evidence; P4 may then start.

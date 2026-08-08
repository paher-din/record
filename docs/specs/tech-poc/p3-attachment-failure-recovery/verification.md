# Tech PoC P3 — Verification Record

- **Slice state:** `completed` (2026-08-08) — spec Revision 1 and plan Revision 2 were approved by the user; the P3 execution evidence below (Tasks 0–10) passed a two-axis code review with all findings fixed, and the user confirmed acceptance on 2026-08-08.
- **Date:** 2026-08-08
- **Spec:** [spec.md](spec.md) — acceptance criteria section 5.2
- **Plan:** [plan.md](plan.md)
- **ADRs:** [0007](../../../architecture/decisions/0007-recoverable-migration-snapshot-ownership.md) (coordinator ownership), [0008](../../../architecture/decisions/0008-zero-link-attachment-cleanup-marker.md) (zero-link cleanup marker)

## Task 0 — lifecycle entry

| Field | Recorded value |
|---|---|
| Command | Documentation edits only: plan/spec status → `in-progress` with approval date/context; AGENTS.md, docs/index.md, docs/roadmap.md updated to P3 as the unique active slice; this record created. |
| Environment | Host: Windows 11 Home China 10.0.26200 (x64), AMD Ryzen 7 8845H (8 cores), 31.3 GiB RAM; JDK Temurin 17; Gradle 9.5.0; reference device Xiaomi 14 Pro (to be re-confirmed in Task 1). |
| Result | PASS — P3 is the project's unique `in-progress` slice; zero other specs in-progress; no implementation code touched. |
| Remaining risk | None. |

## Task 1 — toolchain, dependency, schema-source, and device preflight

| Field | Recorded value |
|---|---|
| Command | `JAVA_HOME=<Temurin 17, user-scope, path not recorded per convention> .\gradlew.bat --version`; adb by absolute path; `:core:data:dependencies --configuration debugCompileClasspath`; `Get-FileHash` on `1.json` |
| Environment | Host as above; JDK Temurin 17.0.20+8; Gradle 9.5.0; Android SDK at `%LOCALAPPDATA%\Android\Sdk` |
| Result | PASS (partial) — JDK 17.0.20 Temurin and Gradle 9.5.0 resolved; `kotlinx-coroutines-core:1.8.1` resolves through the pinned Room 2.8.4 runtime (no new dependency declaration); v1 schema pin `0E2ED86987D13B8C31667F35BC543A2B1BD7E694633DF744A43EA379B2784D81` (upper-case SHA-256 of `core/data/schemas/.../1.json`). **Device preflight FAILS: `adb devices -l` lists no device** — the Xiaomi 14 Pro reference device is not connected/authorized. Tasks 3+ (instrumented suites) are blocked until the device is reconnected; Task 2 (JVM-only) proceeds. |
| Remaining risk | Device offline — reattach via USB debugging before Task 3; the frozen reference environment otherwise unchanged. |

## Task 2 — public taxonomy, pure validation, and decision tables

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.Attachment*Test"` (TDD: compile-fail first, then implement, then rerun) |
| Environment | Host as above; `JAVA_HOME=<Temurin 17>` process-local |
| Result | PASS — 30 tests, 0 failures: `AttachmentValidatorTest` 13 (MIME normalization incl. `;` parameters and `Locale.ROOT` lowercase, 32 MiB exact boundary + 1 byte over, deterministic precedence with multi-invalid candidates, SHA-256 known vectors `e3b0c442…b855`/`ba7816bf…15ad`); `AttachmentDedupDecisionTest` 8 (all row × blob combinations: `MOVE_AND_CREATE`, `REUSE_ACTIVE`, `REUSE_REFRESH`, `RESTORE_REFRESH`, `CORRUPT_SPLIT`, `CORRUPT_MISMATCH`, `CORRUPT_CONFLICT`); `AttachmentOrphanClassifierTest` 9 (TEMP partial, moved-uncommitted, zero-link row, expected delete window, linked-missing corruption, not-orphan, and fail-closed on invalid timestamps). Red phase confirmed: compilation failed on missing P3 types before implementation. |
| Remaining risk | None (JVM layer). Instrumented tasks 3+ remain blocked on the offline reference device (Task 1). |

## Task 3 — recovery record and coordinator before schema enablement

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.MigrationRecoveryRecordTest` |
| Environment | Xiaomi 14 Pro (`23116PN5BC`, `shennong`), API 36 system build (recorded, not rejected — P0–P2 rule); host as above |
| Result | PASS — 17 tests, 0 failures: healthy/pending(all 4 stages)/recovered shapes round-trip; unknown recordVersion/state/stage, duplicate keys, unknown fields, missing fields, state/payload mismatch, and malformed JSON all rejected; absolute/drive/traversal paths rejected; AtomicFile rewrite is fully atomic; absent file reads as null. Implemented `MigrationRecoveryRecordStore` (strict `JsonReader` + `AtomicFile`), `MigrationCoordinator` (startup gate, snapshot protocol, pending-recovery state machine), `MigrationFaultHook`, and the `RecordRepository.open()` coordinator-gated seam with forced `writableDatabase` before `Success` (plan Task 3 startup order). `RecordDatabase.version` remains 1. |
| Remaining risk | None for the record/coordinator layer; the full boundary matrix is exercised in Task 4's `MigrationRecoveryTest`. |

## Task 4 — guarded schema v2 activation and migration recovery

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:testDebugUnitTest --tests "SchemaV2ContractTest" --tests "SchemaContractTest"`; `.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=AttachmentSchemaContractTest,MigrationRecoveryTest` |
| Environment | Host + Xiaomi 14 Pro (`23116PN5BC`, API 36) as recorded |
| Result | PASS — JVM: `SchemaV2ContractTest` 6 (databaseVersion 2, exactly eight tables, v1 six tables byte-compatible incl. columns/FKs/indexes, `Attachment.contentIdentity` unique index, `AttachmentLink` NO ACTION + owner index, 1.json still v1) and `SchemaContractTest` 6 unchanged. Device: `AttachmentSchemaContractTest` 3 (migration preserves all v1 rows/FK/index and creates exactly the two tables with no projection tables; duplicate `contentIdentity` rejected; link FKs enforced) and `MigrationRecoveryTest` 10 (v1 fixture → coordinator → verified snapshot → Room migration → v2 facts intact, record normalized HEALTHY; fresh start creates v2 without snapshot; v2 opens normally; fail-once at every named boundary then restart recovers; repeated publish failure keeps PENDING with no snapshot and never creates an empty database; corrupt published snapshot blocks Room and retains the record; pending-with-missing-snapshot redoes the protocol; restore from MAIN_REPLACED restores snapshot data then migrates; complete-then-repeat idempotent; snapshot immutable across failed and retried open). Implementation fixes during the task: `wal_checkpoint` executed via `rawQuery` (execSQL rejects result-bearing PRAGMAs on API 36); the unique constraint moved from an inline table constraint to `CREATE UNIQUE INDEX` so Room's post-migration validation matches the exported `2.json`; `RecordRepository.open()` now normalizes the record to HEALTHY after the successful open so a completed migration is never left pending. |
| Remaining risk | None for the migration layer. |

## Task 5 — content store, Android probe, hash registry, and ingestion

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.Attachment*Test"` (JVM: dedup decision table incl. the verified-orphan adopt row); `.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=AttachmentSourceCopyTest,AttachmentValidationTest,AttachmentDeduplicationTest,AttachmentCorruptionTest` |
| Environment | Host + Xiaomi 14 Pro as recorded |
| Result | PASS — device 16/16: `AttachmentSourceCopyTest` 3 (fail-once at all six copy boundaries then restart succeeds with no success/row emitted and classified TEMP residue; repeated flush failure never emits success, never commits a row, and leaves classified residue; complete-then-repeat is idempotent with one row and one blob); `AttachmentValidationTest` 7 (undecodable/MIME-outside-set/declared-decoded mismatch/32 MiB+1 rejection with no side effects; valid PNG hash matches the SHA-256 of the file bytes; no canonical row or link after rejection; SHA-256 known vector on device); `AttachmentDeduplicationTest` 3 (duplicate import reuses one canonical row/blob; 8-way concurrent identical ingestion produces exactly one row and one identity under the shared keyed mutex; zero-link re-ingestion refreshes the grace-period start, ADR 0008); `AttachmentCorruptionTest` 3 (metadata/blob split quarantines only the new input, never overwrites, one row; hash mismatch never overwrites the canonical blob; unverifiable occupied target is conflicting-metadata corruption). JVM `AttachmentHashLockRegistryTest` 4 (same-hash serializes, different hashes overlap, cancellation releases references, nested acquire). Implementation fix during the task: the dedup decision table gained `ADOPT_VERIFIED_BLOB` — a verified orphan blob (failed-owner residue) is adopted and the row created, never reported as corruption, matching spec 4.5 orphan rules. Test-only fixes: JUnit4 void-return requirement (runBlocking inside block bodies), shared content dir isolation per test, and a corrected hash-mismatch fixture. |
| Remaining risk | None for ingestion/validation/dedup/corruption; owner sharing is exercised in Task 6's link surface. |

## Task 6 — owner saves and typed link management

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=AttachmentEndpointTest,AttachmentAtomicVisibilityTest,AttachmentDeletionTest,AttachmentRelinkTest` |
| Environment | Host + Xiaomi 14 Pro as recorded |
| Result | PASS — 13/13 device tests: `AttachmentEndpointTest` 5 (link to nonexistent owner → `EndpointRejected` with no link; unknown identity → `CONTENT_IDENTITY_UNKNOWN`; Note owner links/unlinks through the public surface with the marker cleared on link and set on last unlink; Transaction owner links through owner save; unlink of a missing link is idempotent); `AttachmentAtomicVisibilityTest` 3 (failed owner save commits nothing incl. the owner; external-reader latch proves no partial link set is ever visible and the zero-link row keeps its cleanup marker; successful save is fully visible after commit); `AttachmentDeletionTest` 3 (unlink one of two owners on one shared DB leaves the other link and the blob active; last unlink marks cleanup-pending in the same transaction; failure injected at `beforeUnlinkCommit` rolls back with no partial removal and no inconsistent marker, then a clean retry succeeds); `AttachmentRelinkTest` 2 (relink cancels pending deletion, reverifies bytes, and reuses the canonical ID; relink with a missing blob fails `CONTENT_NOT_AVAILABLE` with no link to missing content, then fresh validated ingestion restores the pending target and linking succeeds). Implementation: `AttachmentLinkManager` (ascending per-hash lock order, marker clear + link insert in one transaction, idempotent unlink, hook boundaries wrapped as simulated faults); `NewTransaction`/`NewNote` gain defaulted `attachmentContentIds`; endpoint rule `isValidAttachmentOwner`. Test-only fixes: writer on `Dispatchers.Default` (runBlocking's single-threaded loop starves the latch), two owners sharing one DB. |
| Remaining risk | None; the cleaner's physical deletion and the cleanup/relink race are Task 7. |

## Task 7 — cleaner and expected deletion crash window

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=AttachmentOrphanCleanupTest` |
| Environment | Host + Xiaomi 14 Pro as recorded |
| Result | PASS — 7/7 device tests: grace boundary exact (one ms before is retained, at the boundary cleaned); zero-link row and blob removed together (never separately); the expected missing-blob window completes metadata deletion only, never reported as corruption; physical-delete failure (hook) retains the row across repeated runs, then a clean retry completes idempotently; linked content is never deleted even with an aged marker; orphan blobs and TEMP residue are grace-gated with fail-closed invalid timestamps; cleanup racing relink on the same per-hash registry ends in exactly one complete outcome — relink wins (link + bytes + marker cleared) or cleanup wins (row + blob gone, no link to missing bytes). Implementation: `AttachmentCleaner` (grace gating with injectable clock, per-hash zero-reference recheck, safeDelete/safeMetadataDelete simulated-fault boundaries, file-only residue with fail-closed timestamps) + internal `runAttachmentCleanup()` test seam on the repository. |
| Remaining risk | None for the cleaner; Task 8-10 complete the property/surface/regression gates. |

## Task 8 — property, public-surface, and boundary checks

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:testDebugUnitTest --tests "AttachmentPropertiesTest"`; `ApiSurfaceTest` dump run (`-PapiSurfaceDump=true`), allowlist regenerated, normal assertion mode; `.\gradlew.bat :core:data:apiBoundaryCheck` |
| Environment | Host as above |
| Result | PASS — `AttachmentPropertiesTest` 2 (fixed seed `0x7033`): one SHA-256 → exactly one canonical row, every link references a canonical row, no identity gains duplicate rows, unlink never affects another owner's link, zero-reference means no owner anywhere. Public surface is exactly 37 lines: every P1/P2 declaration retained (the two DTO lines necessarily updated by the plan-approved additive `attachmentContentIds` field) plus the P3 normative delta (3 enums `AttachmentViolation`/`CorruptionDetail`/`AttachmentStage`, 3 `RepositoryResult.Failure` members, `ingestAttachment`/`linkAttachment`/`unlinkAttachment`, and the defaulted `attachmentContentIds` field on both DTOs); `ApiSurfaceTest` assertion mode PASS; `apiBoundaryCheck` PASS with the positive fixture's exhaustive `when` updated to the new branches. Implementation fixes during the task: `ApiSurfaceTest` now filters visibility before the enum branch (P1 legacy — P3 introduced the first internal enums, exposing it); all P3 internal sealed types and their subclasses moved to top-level `internal` so the allowlist stays truly public-only; `IngestOutcome` subclasses renamed with the `Ingest` prefix to avoid a cross-file top-level collision. |
| Remaining risk | None. |

## Task 9 — complete mapped device fault suite

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.paherdin.record.core.data.room` |
| Environment | Xiaomi 14 Pro (`23116PN5BC`, `shennong`), API 36 (recorded, not rejected) |
| Result | PASS — 95 tests, 0 failures, single run so shared-state/order bugs cannot hide: MigrationRecoveryRecordTest 17, AttachmentSchemaContractTest 3, MigrationRecoveryTest 10, AttachmentSourceCopyTest 3, AttachmentValidationTest 7, AttachmentDeduplicationTest 3, AttachmentCorruptionTest 3, AttachmentEndpointTest 5, AttachmentAtomicVisibilityTest 3, AttachmentDeletionTest 3, AttachmentRelinkTest 2, AttachmentOrphanCleanupTest 7, plus the P1/P2 device suites (PersistReload, XorIdentity, ForeignKey, TypedEndpoint, AtomicVisibility, Concurrency, Transfer suites). One harness interruption: MIUI install confirmation (`INSTALL_FAILED_USER_RESTRICTED`) — repaired by re-running after the user confirmed on-device; no PoC conclusion changed. |
| Remaining risk | None. |

## Task 10 — regression, launch, and evidence

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat clean check`; `.\gradlew.bat :app:installDebug`; `adb shell am start -n com.paherdin.record/.MainActivity`; `dumpsys activity activities`; `git diff --check` |
| Environment | Host as above; Xiaomi 14 Pro API 36; JDK Temurin 17.0.20+8; Gradle 9.5.0; coroutines 1.8.1 via Room 2.8.4 |
| Result | PASS — `clean check` BUILD SUCCESSFUL across all seven modules (unit tests, lint incl. the new `core:data` `minSdk = 34` fix, module-graph checks); app installed and launched with `MainActivity` top-resumed; `git diff --check` clean. This record maps criteria 1–11 and the roadmap P3 exit gate: copy matrix (T1), validation/hash (T2), duplicate reuse (T3), atomic move + owner commit (T4), unlink/deletion/cleanup (T5/T7), concurrent relink (T6), corruption fail-closed (T7), idempotent orphan cleanup (T8), schema + surface machine checks (T9a/b/c), applicable migration recovery (T10), regression (T11) — each with named fail points, injected boundaries, observed durable state, residue classification, restart/retry outcome, and cleanup outcome in the tasks above. v1 schema pin `0E2ED869…4D81` unchanged across the slice; `2.json` committed; the `ATTACHMENT_CONTENT_COMMIT` backup-round-trip clause remains the recorded P5 deviation (spec 4.8). |
| Remaining risk | None blocking; the P5 deviation and the M7 N-1/N-2 matrix are the declared boundaries. |

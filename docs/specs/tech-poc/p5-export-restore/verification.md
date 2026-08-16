# Tech PoC P5 — Export and Restore Verification Record

- **Slice:** Tech PoC P5 — export/restore.
- **Status:** `in-progress` — Revision 2 spec and plan jointly approved 2026-08-09; implementation in progress. This file records evidence only; no production change predates the approval.

## Initial slice-state record (Task 0)

- **Approval:** spec Revision 2 and plan Revision 2 jointly approved by the user on 2026-08-09; recorded in the spec and plan headers.
- **ADR 0010** `generation-bootstrap-and-live-session-handoff` — accepted 2026-08-09.
- **Owning core documents synchronized:** data and storage (formatVersion = 1, recordVersion = 2 form, GENERATION_BOOTSTRAP), testing strategy (GENERATION_BOOTSTRAP mapping, current-schema fixture), AGENTS/index/roadmap (P5 is the unique `in-progress` slice).
- **Frozen reference:** Xiaomi 14 Pro, Android 14 / API 34 baseline; actual build re-recorded at verification time (currently Android 16 / API 36 per P0–P4).
- **Frozen source pin (Task 1):** v2 schema SHA-256 recorded below once Task 1 runs.

## Evidence

### Task 1 — Toolchain, dependency, schema-source, device preflight

- **Command:** `$JAVA_HOME/bin/java.exe -version`; `./gradlew.bat --version`; `adb devices -l`; `sha256sum core/data/schemas/.../2.json`
- **Environment:** commit a143898 (pre-P5); JAVA_HOME=process-local `C:\Users\p3148\tools\jdk\jdk-17.0.20+8` (Temurin 17.0.20+8, JDK 25 incompatible per project memory); Gradle 9.5.0, launcher JVM 17.0.20; adb `C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- **Result:** PASS — JDK 17.0.20 (Temurin), Gradle 9.5.0, adb present. **Device re-verified 2026-08-09:** Xiaomi 14 Pro (23116PN5BC, `shennong`), Android 16 / API 36 (`ro.build.version.sdk=36`, release=16), USB debugging, transport_id 2. v2 schema SHA-256 = `C14F7FED0FC69255B4D7C25222BBC5BDB58AC13E0D65F4C01A42D890D02150D9` (committed `1.json` + `2.json` present; P5 changes no schema, criterion 12(a) reuses `AttachmentSchemaContractTest`).
- **Remaining risk:** none; the reference device is connected and instrumented tasks may proceed.

### Task 3 — Record v2 and non-destructive legacy generation bootstrap

- **Command:** `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=GenerationActivationRecordTest,LegacyGenerationBootstrapTest,MigrationRecoveryTest,MigrationRecoveryRecordTest,AttachmentSourceCopyTest,PersistReloadTest`; module gate `:core:data:check`
- **Environment:** commit a143898 + P5 Tasks 0–3 working tree; Temurin 17.0.20+8; Xiaomi 14 Pro `23116PN5BC` / Android 16 API 36, USB `c55ad7e4`
- **Result:** PASS — the full six-class Task 3 suite runs **56 tests with 0 failures** (50 prior + 6 new `GENERATION_BOOTSTRAP` boundary tests). Production components: `ActivationRecordStore` (v2 record, `recordVersion()` dispatcher, fail-closed), `GenerationManager` (`.incomplete` marker, layout, pointer-aware orphan scan, step-6 retirement, per-generation lock), `LegacyGenerationBootstrap` (ADR-0010 classify → resolve legacy recovery → complete v1→v2 migration on legacy root → checkpoint → copy → validate → publish v2), `StagedGenerationValidator`, `GenerationDatabase` (absolute-path Room open), `VaultSessionRegistry` (one identity per canonical root), `RecordRepository.open()` rewired through bootstrap + generation gate.
- **Complete `GENERATION_BOOTSTRAP` fault matrix (criterion 14, the four previously-open boundaries are now closed):**
  - Fault at every named boundary under the three-scenario protocol (`BootstrapFaultHook`): a fault before the v2 write leaves the legacy source authoritative and retries cleanly; a repeated fault never publishes and retains the source; completion then repeat is idempotent without re-copy.
  - Frozen post-checkpoint source hashes (database + attachment tree) are asserted unchanged through copy/validation/publication.
  - True concurrent first-open (8 threads, barrier) runs bootstrap exactly once and returns one repository identity.
  - Distinct vault roots remain independent under `VaultSessionRegistry`; releasing one does not affect another.
  - No legacy deletion is asserted at every point; fresh production open publishes the v2 pointer before Room creates the database; interrupted pointer-owned fresh restart resumes and clears the incomplete marker.
- **P3 test adaptation:** `MigrationRecoveryTest` verifies the coordinator/store and legacy root migration directly; production `RecordRepository.open(context)` calls in P3 tests are zero. The full N-1/N-2 migration matrix remains M7's.
- **Remaining risk:** none for Task 3 scope; the complete backup/restore mappings (Tasks 4–11) remain.

### Task 4 — Export pipeline and `BACKUP_STREAM_AND_PUBLISH`

- **Command:** `connectedDebugAndroidTest -P...class=BackupStreamPublishTest`; module gate `:core:data:check`
- **Environment:** commit a143898 + P5 Tasks 0–4 working tree; Temurin 17.0.20+8; Xiaomi 14 Pro / API 36
- **Result:** PASS — 5/5 device tests, `:core:data:check` green. `BackupExporter` streams the eight datasets and attachment blobs into a ZIP (SHA-256 per entry as written), writes the manifest last, and validates every entry hash against the manifest before publication; the consistent read is one Room transaction. `exportBackup` publishes privately by atomic rename (temp on the same filesystem, deleted on failure, never visible before publish) and via the `BACKUP_SAF_COPY` protocol (plan Task 5). Public surface regen (57 lines) adds `BackupArtifact`, `BackupTarget`, `BackupProviderTarget`, `exportBackup`.
- **`BACKUP_STREAM_AND_PUBLISH` matrix (criterion 4):** every JSONL and attachment stream (before-first/mid/after-final), manifest write (before/mid/after-close), and private rename (before/after) runs fail-once-then-retry; a repeated fault never publishes and leaves no residue; complete-then-repeat produces distinct artifacts. Before the atomic rename the package is never visible and temp residue is cleaned; after the rename the complete package is published.
- **Remaining risk:** none for Task 4 scope; Task 5 adds the dedicated `BACKUP_SAF_COPY` device matrix.

### Task 5 — Provider target and `BACKUP_SAF_COPY`

- **Command:** `connectedDebugAndroidTest -P...class=BackupSafCopyTest`
- **Environment:** commit a143898 + P5 Tasks 0–5 working tree; Temurin 17.0.20+8; Xiaomi 14 Pro / API 36
- **Result:** PASS — 3/3 device tests. `BackupProviderTarget` protocol (create-incomplete → chunk copy → flush/close → verify → rename-if-supported → clear marker) implemented in `exportBackup(Provider)`; `TestProviderTarget` is the injectable device fixture.
- **`BACKUP_SAF_COPY` matrix (criterion 5):** every step (create, first/middle/final chunks via an injectable small chunk size, flush/close, verify, rename, marker clear) fails once then retries; a partial provider object stays incomplete-marked (or never created) and is never reported complete; the rename-unsupported path still succeeds only after verification.
- **Remaining risk:** none for Task 5 scope.

### Task 6 — Restore preflight and malicious ZIP matrix

- **Command:** `connectedDebugAndroidTest -P...class=MaliciousZipRestoreTest`; JVM `ZipPreflightDeclaredTest`/`ZipStreamingPreflightTest`
- **Environment:** commit a143898 + P5 Tasks 0–6 working tree; Temurin 17.0.20+8; Xiaomi 14 Pro / API 36
- **Result:** PASS — 9/9 device tests; JVM decision-table tests green. `RestorePreflight` runs the declared pass (strict manifest + ZIP-directory metadata) and the streaming pass (independent uncompressed-byte counting; compressed bytes are intentionally not tracked because `ZipInputStream` buffers ahead — zip-bomb protection is the declared pass's central-directory ratios plus the streaming declared-below-actual uncompressed checks), then the catalog-contract framework. `DeclaredInput` gained `totalZipEntries` so the entry-count limit includes `manifest.json`.
- **Malicious ZIP matrix (criteria 6–7):** format, unknown logical type, singleton duplicate, traversal/overlong paths, declared attachment size over 512 MiB, compression-ratio over 200:1 (real highly-compressible entry), aggregate over the space budget, declared-below-actual in the streaming pass, and entry count 100,001 all reject with the vault untouched and no staging residue. `MaliciousZipGenerator` shapes manifests (declared-size over-limit via the manifest rather than multi-GiB payloads).
- **Remaining risk:** none for Task 6 scope; record-level staging validation (criterion 8) is Task 7.

### Task 7 — Staging import and record-level validation

- **Command:** `connectedDebugAndroidTest -P...class=StagingValidationTest`; JVM `StagingReceiptTest`
- **Environment:** commit a143898 + P5 Tasks 0–7 working tree; Temurin 17.0.20+8; Xiaomi 14 Pro / API 36
- **Result:** PASS — 7/7 device tests, JVM receipt tests green. `RestoreStager` parses the preflighted package's eight datasets strictly, imports in FK-safe deterministic order (EntityRef → Account → Transaction → Note → AccountEntry → TransactionNoteAssociation → Attachment → AttachmentLink) into an isolated unreferenced generation, writes the attachment blobs, validates the complete staged generation (record counts, foreign keys, XOR, finance matrix, balances, endpoints, attachment identity/hash), and produces the strict canonical `StagingReceipt`. `stageRestore` returns `StagedRestore(candidateId, validationDigest, recordCounts, attachmentCount)`; the receipt digest binds source package, manifest, entries, counts, and candidate digest — the exact handle `confirmRestore` later re-verifies.
- **Record-level rejection (criterion 8):** finance violation, dangling foreign key, invalid record syntax, and unsupported catalog version each reject with the exact `RestoreViolation`; a rejected candidate leaves the vault untouched and no staging residue; staging twice replaces the candidate (one candidate at a time). The JVM `StagingReceiptTest` proves the canonical UTF-8 bytes are deterministic and the SHA-256 digest changes for every bound field.
- **Remaining risk:** none for Task 7 scope; the activation protocol and same-repository handoff (criterion 9) are Task 8.

### Task 8 — Repository operation gate, live handoff, and `RESTORE_ACTIVATION`

- **Command:** `connectedDebugAndroidTest -P...class=GenerationActivationTest,RepositoryGenerationHandoffTest`
- **Environment:** commit a143898 + P5 Tasks 0–8 working tree; Temurin 17.0.20+8; Xiaomi 14 Pro / API 36
- **Result:** PASS — 9/9 device tests. The repository now holds one `@Volatile` `VaultBinding` (generation-scoped database + attachment pipeline); every ordinary operation runs under the shared side of `VaultOperationGate` and `confirmRestore` runs under the exclusive side. `confirmRestore(staged)` verifies the exact receipt-bound handle, drains shared calls, closes the old binding before `PENDING`, runs the same candidate resolver a cold startup runs (`RestoreActivationCoordinator`), and atomically swaps the same repository instance to the healthy candidate or back to the original on rollback.
- **`RESTORE_ACTIVATION` matrix (criterion 9, 14):** confirm switches the generation and keeps the same instance; a stale/forged handle is rejected before mutation; a revalidation failure rolls back to the original binding; a durable `PENDING` record is resolved at cold start by the shared resolver; the fault matrix runs every boundary (close-old/pending-write/revalidate/healthy-write) fail-once-then-resume; retirement deletes the retiring generation and clears the pointer; concurrent writes drain before confirmation; the next restore completes pending retirement first; a pending activation blocks a new restore with `MAINTENANCE_PENDING`.
- **Remaining risk:** none for Task 8 scope; the complete round trip and startup order (criteria 1, 10) are Task 9.

### Task 9 — Round trip, startup order, applicable migration recovery

- **Command:** `connectedDebugAndroidTest -P...class=RoundTripTest,StartupGateOrderTest`
- **Environment:** commit a143898 + P5 Tasks 0–9 working tree; Temurin 17.0.20+8; Xiaomi 14 Pro / API 36
- **Result:** PASS — 4/4 device tests. The canonical current-schema fixture (opening balance, income, expense, same-currency transfer, bidirectional transaction-note relation, a two-owner shared attachment, a Unicode account name, soft-deleted history, boundary timestamps) exports → stages → confirms → crosses the cold-start gate → compares every logical field of all eight datasets exactly → restarts again for integrity/balance recheck; soft-deleted history survives the round trip. `StartupGateOrderTest` proves a pending migration record blocks Room open before any generation opens, and a pending generation with healthy migration resolves via the shared resolver. The restored vault opens on v2 without a `databaseSchemaVersion` gate; the full N-1/N-2 matrix remains M7's.
- **Remaining risk:** none for Task 9 scope; the backup-policy inspection and public-surface checks (criteria 11, 12) are Task 10.

### Task 10 — Backup policy, public-surface, and boundary checks

- **Command:** source-rule assertion; `:app:assembleDebug`; `aapt2 dump xmltree` of the packaged manifest and both rule files; `:core:data:apiBoundaryCheck`
- **Environment:** commit a143898 + P5 Tasks 0–10 working tree; Temurin 17.0.20+8
- **Result:** PASS. `data_extraction_rules.xml` and `backup_rules.xml` now exclude exactly the nine Android backup domains (`root`, `file`, `database`, `sharedpref`, `external`, `device_root`, `device_file`, `device_database`, `device_sharedpref`) — 18 excludes under cloud+device-transfer and 9 under full-backup-content. Source manifest keeps `allowBackup=false` plus both rule references; the merged and packaged APK confirm `allowBackup=false`, the rule references, and the exact nine-domain set, with no `INTERNET`. The public surface regenerated to 68 lines; `apiBoundaryCheck` passes 4 positive (including `BackupSurfaceUsage` unpacking the additive backup/restore surface) and 25 negative fixtures (including `ReferenceVaultBinding`/`ReferenceRestoreStager`/`ReferenceBackupExporter` proving the startup gate, staging, and export machinery stay internal).
- **Remaining risk (M7):** the packaged APK carries `WRITE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, and `READ_PHONE_STATE` from a dependency merge — not network permissions, so they do not block P5, but they must be removed or justified in M7 privacy hardening.

### Task 11 — Complete mapped device fault suite, regression, launch, and evidence

- **Command:** `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.paherdin.record.core.data.room`; `./gradlew.bat clean check`; `:app:installDebug`; `am start -n com.paherdin.record/.MainActivity`; `git diff --check`
- **Environment:** commit a143898 + P5 Tasks 0–11 working tree; Temurin 17.0.20+8; Xiaomi 14 Pro `23116PN5BC` / Android 16 API 36, USB `c55ad7e4`
- **Result:** PASS — the complete `room`-package instrumented suite runs **154 tests with 0 failures** (P1–P4 regression plus every P5 mapping: `GENERATION_BOOTSTRAP`, `BACKUP_STREAM_AND_PUBLISH`, `BACKUP_SAF_COPY`, `RESTORE_ACTIVATION`, and applicable `MIGRATION_RECOVERY`). `./gradlew clean check` passes the whole module graph (202 JVM tests, lint, `apiBoundaryCheck`, `checkModuleDependencies`). The app installs and launches on the reference device (API 36, `MainActivity` resumed). `git diff --check` reports no whitespace errors.

## Post-completion three-axis code review (2026-08-16)

Three parallel review axes (standards / spec-conformance / adversarial correctness) found 14 confirmed defects and 4 recorded deviations; all confirmed defects are fixed and re-verified.

- **Fixed (correctness):** `exportBackup`/`stageRestore` now take the shared operation gate (previously could race confirmation's exclusive rebind); `confirmRestore` rebuilds the binding from the durable record when a transition throws after the old binding closes (previously left a closed database in hand); `close()` takes the blocking exclusive gate; the manifest is size-capped (64 MiB) before parsing; the streaming pass consumes entries in bounded chunks and stops once the violation is proven (no multi-hundred-MiB materialization); export temp files are deleted on the validate-failure and provider-create-failure paths; provider step failures map to their named `PROVIDER_*` stage instead of `Unexpected`; `FORMAT_UNSUPPORTED` precedes `UNKNOWN_LOGICAL_TYPE`; `normalizePath` normalizes backslashes; `JsonlCodec` maps number overflow to `JsonParseException`.
- **Fixed (validation):** the balance check is now exact arithmetic (`Math.addExact` → `BALANCE_INVALID` on overflow, previously dead code); unreferenced blobs reject as `ATTACHMENT_EXTRA` in restore staging (bootstrap copies keep them as expected orphan residue); the encountered `nameKeyVersion` set must exactly match the manifest summary (any declared catalog version with no matching records rejects); the rollback outcome returns `CANDIDATE_MISMATCH` instead of masquerading as an IO failure.
- **Fixed (machine check):** `ApiSurfaceTest` now skips nested classes whose enclosing chain is not public, closing the hole where `PreflightResult.Accepted`-style nested members of internal sealed interfaces leaked into the allowlist; 40 P5 internal classes joined `INTERNAL_CLASSES`. The regenerated allowlist (60 lines) contains only the normative surface.
- **Recorded deviations (defensible, unchanged):** the streaming pass does not count compressed bytes — `ZipInputStream` buffers ahead so per-entry compressed attribution is unreliable; zip-bomb protection is the declared pass's central-directory ratios plus the streaming declared-below-actual uncompressed checks (recorded in Task 6). The staged receipt handle is process-local by design; confirmation verifies the exact in-memory digest binding.
- **Re-verified after fixes:** full `room`-package device suite 154/154; `:core:data:check` (202 JVM tests, lint, `apiBoundaryCheck` 4 positive / 25 negative, surface allowlist) green.

## Exit gate

The P5 exit gate passes: non-destructive v1/root-layout bootstrap and same-instance handoff pass `GENERATION_BOOTSTRAP`; the export → stage → confirm → startup comparison matches every current-schema fact, relation, balance, attachment owner, size, and SHA-256; every binding ZIP limit passes declared and streamed tests; `BACKUP_STREAM_AND_PUBLISH`, `BACKUP_SAF_COPY`, `RESTORE_ACTIVATION`, and applicable `MIGRATION_RECOVERY` pass; source/merged/final-APK manifest plus packaged API-34/compatible rules exclude all user data from cloud backup/device transfer with no network migration; the public-surface allowlist and `apiBoundaryCheck` machine checks pass; `clean check` and the device launch regression pass. Criterion 12(a) reuses the existing `AttachmentSchemaContractTest` against the committed `2.json` (`databaseVersion = 2`, no migration added). The five catalog datasets and the full catalog data matrix remain M2–M4 evidence; the full N-1/N-2 migration matrix remains M7's. P6 may start.

### Task 2 — Codec, manifest, rejection taxonomy, and preflight pure logic

- **Command:** `./gradlew.bat :core:data:testDebugUnitTest --tests '...JsonlCodecTest' --tests '...ManifestTest' --tests '...LogicalBackupSchemaTest' --tests '...RestoreViolationClassificationTest' --tests '...ZipPreflightDeclaredTest' --tests '...ZipStreamingPreflightTest' --tests '...CatalogContractCheckerTest'`; then full `:core:data:testDebugUnitTest`
- **Environment:** commit a143898 + P5 Task 2 sources; JVM 17.0.20; no device needed
- **Result:** PASS — 72 Task 2 tests green (strict codec duplicate-key/trailing/malformed rejection, all eight dataset shapes with verbatim `OPENING_BALANCE`, manifest round trip, declared-pass decision table at every exactly-at/one-over boundary, streaming accumulator, catalog-contract framework, total record-level classification); full module unit suite green after the public surface was regenerated to the current delta (62 lines, all Task 2 machinery `internal`, only the normative `RepositoryResult` enums/failures public)
- **Remaining risk:** none known for the pure logic; the reference device is now connected and the Task 3 integrated subset has run as recorded above.

# Testing strategy

This document is the source of truth for risk-based verification and release gates. Product acceptance remains owned by the [MVP requirements](../product/mvp-requirements.md), invariants by the [domain model](../architecture/domain-model.md), persistence fault points by [data and storage](../architecture/data-and-storage.md), and UI state behavior by the [interaction rules](../experience/interaction-rules.md).

## Test ownership and execution policy

Although one person develops the app, ownership follows the boundary under test:

| Layer | Test owner | Run timing | Blocking rule |
|---|---|---|---|
| Pure domain and property tests | `:core:model` or the module owning the invariant | Every affected local change and CI once CI exists | Any failure blocks commit completion and every later gate. |
| Repository, Room, files, backup, migration, search | `:core:data` | Every affected local change; full suite before a PoC/MVP slice completes | Any invariant, atomicity, recovery, or comparison failure blocks the slice. |
| ViewModel state tests | Owning feature or `:app` | Every affected local change | Missing or illegal phase transition, lost draft, stale result, or premature success blocks the slice. |
| Compose UI and navigation tests | Owning feature plus `:app` for cross-feature routes | Before the related vertical slice completes; five critical flows before MVP release | Any acceptance-path or failure-path mismatch blocks the slice/release. |
| Macrobenchmark and device fault tests | `:app` with the owning module's fixture | At the relevant Tech PoC, after performance-affecting changes, and before MVP release | Missing reproducible evidence or exceeding the active spec's approved budget blocks promotion. |
| End-to-end recovery verification | `:core:data` plus `:app` startup gate | Every persistence/backup/restore change and before every release candidate | A passing build without a verified restore round trip is insufficient and release-blocking. |

When Gradle is introduced, the active slice plan records the exact Gradle tasks and device commands. A generic `build` or assemble result cannot stand in for a required test class, migration fixture, fault injection, benchmark, or round trip.

## Risk matrix

| Risk | Required test and fixture | Pass condition | Gate |
|---|---|---|---|
| Invalid money or relationship facts | Unit tests for integer minor units, signs, allocation totals, transfer cardinality, opening balance, endpoint whitelist, XOR identity, soft delete, purge, and projection write bans | Every invariant accepts valid boundaries and rejects invalid boundaries without partial state | Every domain change |
| Balance drift across arbitrary history | Property tests generate valid random income, expense, transfer, opening, and soft-delete sequences with fixed seeds recorded on failure | For every prefix and final state, each balance equals the sum of effective `AccountEntry` values; transfer sum remains zero | Every finance change and PoC P2 |
| Partial or racing persistence | Instrumented Room integration tests cover repository transactions, foreign keys, cascades, concurrent owner writes, relation replacement, attachment metadata/link commit, and projection update | Observers can see only the old complete state or new complete state; never a partial aggregate, one-sided relation, or source/projection mismatch | Every repository/schema change |
| Upgrade data loss | Room migration tests upgrade version N-1 and N-2 fixtures to N, including old WAL checkpoint/snapshot preparation and domain validation | Schema opens without destructive fallback; all fixture facts, relations, balances, and attachment metadata remain valid | Every schema change and MVP release |
| Backup cannot restore | Automated logical-backup round trip exports a ZIP, verifies it, wipes/overwrites the test vault, restores it, crosses the startup gate, then compares normalized logical state | Every entity, relationship, assignment, allocation, balance, attachment ownership, attachment byte size, and SHA-256 matches; no CSV is used as restore input | Every backup/restore change, PoC P5, every release |
| Malicious ZIP resource exhaustion | Generate declared and streamed boundary/overflow archives for entry count, normalized UTF-8 paths, per-entry sizes, aggregate staging budget, and compression ratios | Every over-limit or mismatched archive fails immediately with current vault unchanged, no success, and deterministic temporary cleanup; every exact-limit valid fixture proceeds to the remaining validation gates | Every restore-reader change, PoC P5, MVP release |
| CSV interoperability regression | Separate CSV contract test exports all saved transactions from the same fixture | Exactly one row per saved transaction; one header; consistent columns; UTF-8 and declared machine-readable values match transaction detail | Every CSV change; not accepted as restore evidence |
| Interrupted attachment source copy | Fault injection before private temporary-file creation, during the first/middle/final copy chunks, after the final write but before close, after close but before validation/hash, and on source read or destination flush failure | No visible `AttachmentLink` exists and no success is reported; the original owner remains completely unchanged; partial temporary/orphan state is deterministically classified for safe cleanup or retry under [data and storage](../architecture/data-and-storage.md) | PoC P3, every attachment/import change, and MVP release |
| Attachment loss or orphan visibility | Fault injection at validation/hash, atomic move, owner transaction, `DELETE_PENDING`, physical delete, and metadata cleanup; include concurrent link/cleanup and duplicate hash | No committed link points to missing/unverified bytes; a failed owner is invisible; retries are idempotent; zero-reference cleanup never deletes newly linked content | PoC P3 and every attachment change |
| Interrupted logical-backup publication | Fault injection during each JSONL/attachment streaming write, before/during/after manifest write, before/after private atomic publication, and during SAF create/copy/flush/close/verify/rename or incomplete-marker clearing | An incomplete package is never listed as restorable and never reported as success; private temporary packages and SAF incomplete-marked residues are deterministically identifiable for safe cleanup or retry; only a complete package whose payload and manifest validation passes becomes visible as a backup | PoC P5, every backup/export change, and MVP release |
| Unsafe migration recovery | Fault injection after `MIGRATION_RECOVERY_PENDING`, before/after snapshot replacement, during failed database/WAL/SHM sidecar isolation, restore-copy validation, main replacement, and repeated startup | No empty database or mixed sidecar set opens; published snapshot stays unchanged; restart resumes idempotently; ordinary Room/shell access remains blocked until verified recovery | Every migration/recovery change and MVP release |
| Partial restore activation | Fault injection before activation, after pending activation, during startup validation, rollback-record write, quarantine, and cleanup | Old or candidate generation is complete; failed candidate rolls back; pending work resumes; referenced generations are never deleted; no partial success UI | PoC P5 and every restore change |
| Incorrect, slow, or unstable Chinese search | Run the fixed 10,000-record Chinese/English corpus and query manifest protocol from [data and storage](../architecture/data-and-storage.md) on the frozen P4 reference configuration | Candidate correctness is 100% for expected IDs/order and measured first-page p95 is at most 300 ms after 5 warmups/30 measured runs/page 50; only candidates passing both absolute gates enter the CV/p95 tie-break | PoC P4 and any search/index change |
| UI state race or draft loss | ViewModel tests use controlled dispatchers, clock, IDs, and fake typed outcomes | All declared phases and only legal transitions occur; recoverable failures retain complete drafts; Saving locks exit; stale search is ignored; success waits for durable boundary | Every ViewModel change |
| Journey/navigation regression | Five critical Compose flows below run with success, validation, infrastructure failure, Back, and recreation cases | Every required assertion passes with correct source-detail routing and retained context | Related slice and MVP release |
| Mixed Timeline performance degradation | Reproducible mixed-card Timeline benchmark on the recorded Android 14 reference configuration | No ANR/OOM/corrupt output; metrics meet the approved Timeline budget and regression allowance | PoC P6, Timeline MVP slice, related change, MVP release |
| Thumbnail performance degradation | Reproducible repeated/unique image benchmark | No ANR/OOM/content leak; metrics meet the Notes/image slice budget and regression allowance | Notes/image MVP slice, related change, MVP release; never PoC P6 |
| Aggregation performance degradation | Reproducible monthly/category aggregation benchmark with reference-result comparison | Results match the pure calculation and metrics meet the statistics slice budget/regression allowance | Statistics MVP slice, related change, MVP release; never PoC P6 |
| Startup or recovery-gate performance degradation | Reproducible startup benchmark for healthy and pending states | Correct safe route appears without ANR/OOM and meets the owning shell/recovery slice budget; shell never wins by bypassing recovery/App Lock | App Shell slice and each restore/migration/App Lock slice, related change, MVP release; never PoC P6 |
| Android system backup or network policy drift | Inspect source manifest, merged release manifest, final APK, API 34 data-extraction rules, compatible rules, and dependencies; run offline journeys | No `INTERNET`/unexpected network component or related permission; all app user-data domains excluded from cloud backup/device transfer; Journeys 1–13 pass in airplane/network isolation | App Shell slice, every manifest/dependency change, MVP release |
| Diagnostic or access-control privacy regression | Boundary/unit tests plus Compose/instrumented lifecycle tests for ring buffer, App Lock, locked recovery, and diagnostic export | Three-field event allowlist only; UTC-day segment-level retention window of 7 days and exact 1-MiB rotation; secure previews; no auth/recovery bypass; modal export cannot leak, auto-share, or modify vault | Owning Privacy/Diagnostics slice, related change, MVP release |

## Domain and persistence suites

### Unit and property coverage

Boundary examples include zero, one minor unit, maximum supported minor amount, allocation rounding edges, same-account transfer, different-currency transfer, archived references, duplicate relations, unavailable targets, and soft-deleted owners. Property failures persist the seed and minimal counterexample in test output; rerunning that seed is mandatory before a fix is accepted.

### Room transaction coverage

Use a real Room database in instrumented tests, with foreign keys enabled and the same transaction entry points as production. For each write boundary, inject failure before the transaction, between each logical child write, before commit, and immediately after commit response handling. Assert both stored facts and observable projection state. Fakes are suitable for ViewModel tests but cannot prove Room atomicity.

### Migration coverage

Keep versioned N-1 and N-2 database fixtures and exported Room schema JSON in version control after Room exists. Each migration test verifies SQLite integrity, foreign keys, the applicable domain invariant matrix, projection rebuildability, and post-open queries. Tests also cover interrupted recoverable snapshots and the migration startup gate. `fallbackToDestructiveMigration` is prohibited and can never be used to make a migration test pass.

## Search PoC P4 absolute gate

P4 uses the binding protocol in [data and storage](../architecture/data-and-storage.md), not a relative-only benchmark:

1. Before test execution, the P4 spec records and freezes the actual reference device or AVD profile, API 34 build, CPU and RAM, and host CPU/RAM when an AVD is used. Missing, incomplete, or changed configuration invalidates the run and fails P4.
2. Both candidates use the same versioned 10,000-record mixed Chinese/English corpus, fixed query manifest with exact expected IDs/order, and page size 50.
3. Each query/candidate runs 5 warmups and 30 measured first-page runs; evidence records correctness, p50, p95, and CV.
4. A candidate must achieve 100% correctness for every expected ID set/order and measured first-page p95 at most 300 ms. A candidate failing either absolute gate is ineligible. If both are ineligible, P4 fails.
5. If one candidate is eligible, select it. If both are eligible, apply the existing absolute-CV-difference 0.05 rule, then relative-p95-difference 10% rule, then paged `LIKE` complexity tie-break.

No spec may replace the 300 ms default with “record a budget later.” Changing the stable absolute budget or protocol requires explicit approval and documentation-first architecture/ADR review.

## Backup, restore, and fault injection

The canonical round-trip fixture contains every MVP entity type, each transaction type, multi-allocation income/expense, bidirectional transaction-note associations, shared tags, zero/one project assignments, duplicate-content attachments, soft-deleted facts, archived account/category history, Unicode text, and boundary timestamps.

Comparison ignores representation-only ordering where the format declares it irrelevant, but compares stable IDs and every logical field, relation endpoint, derived balance, record count, attachment ownership, size, and hash. The procedure is:

1. create and validate the fixture;
2. export and verify the complete logical ZIP;
3. separately export and verify CSV without adding it to the ZIP;
4. wipe or overwrite only the isolated test vault;
5. restore into a staging generation and cross the pending startup-validation gate;
6. compare the restored logical state and attachment bytes with the pre-export snapshot;
7. restart once more and repeat integrity and balance checks.

Fault tests use named deterministic fail points from [data and storage](../architecture/data-and-storage.md). Every fail point runs at least three scenarios: fail once then restart, fail repeatedly without bypassing safety, and complete then repeat the handler to prove idempotence. Sidecar interruption explicitly covers main database, `-wal`, and `-shm` isolation. Activation coverage explicitly proves rollback after pending candidate failure. A test that validates only ZIP creation, database opening, or app build does not satisfy restore verification.

The fault suite uses these explicit gate mappings:

- **`ATTACHMENT_SOURCE_COPY` → PoC P3:** interrupt before temporary-file creation; during the first, middle, and final copy chunks; after final write but before close; after close but before validation/hash; and on source-read or destination-flush failure. For every point, assert that no `AttachmentLink` becomes visible, no success result is emitted, and the pre-existing owner remains exactly unchanged. Assert that a partial temporary file or post-move orphan is classified according to [data and storage](../architecture/data-and-storage.md), can be cleaned after its required safety boundary, and can be retried without reusing unverified bytes.
- **`ATTACHMENT_CONTENT_COMMIT` → PoC P3:** cover validation/hash, atomic content move, owner Room transaction, pending deletion, physical deletion, metadata cleanup, concurrent relink, and duplicate-content reuse. Together with `ATTACHMENT_SOURCE_COPY`, this is the complete attachment fault-recovery gate.
- **`BACKUP_STREAM_AND_PUBLISH` → PoC P5:** interrupt every versioned JSONL stream and attachment-binary stream before first write, mid-entry, after final payload write, during flush/close, and immediately before manifest creation. Interrupt manifest write before first byte, mid-write, after final write but before close, and before/after private atomic rename. In every case, assert that the package remains private temporary/incomplete, is never visible in the restorable list, and is never reported as success; cleanup and retry must identify the residue deterministically.
- **`BACKUP_SAF_COPY` → PoC P5:** starting from a complete privately validated package, interrupt SAF target creation, first/middle/final copy chunks, provider flush/close, destination verification, provider rename when supported, and incomplete-marker clearing. Assert that a partial provider object remains marked or named incomplete, is never listed or reported as complete, and is eligible for best-effort cleanup or an unambiguous retry. Only a fully copied and reverified provider object may clear the incomplete marker and become visible.
- **`RESTORE_ACTIVATION` and `MIGRATION_RECOVERY` → PoC P5/MVP recovery gates:** retain the pending activation, rollback, generation cleanup, migration pending, snapshot, sidecar, replacement, and repeated-startup cases defined above.

The named mappings are cumulative. PoC P3 does not pass without both attachment mappings; PoC P5 does not pass without backup stream/publication, SAF copy, restore activation, and migration-recovery evidence applicable to that slice.

### Malicious ZIP preflight matrix

Tests enforce every binding limit in [data and storage](../architecture/data-and-storage.md) at both declared-manifest and actual-stream layers:

- entry counts of exactly 100,000 and 100,001, plus a stream whose actual entry count exceeds its declaration;
- normalized UTF-8 relative paths of exactly 240 bytes and 241 bytes; absolute paths; `..` traversal before/after normalization; duplicate normalized paths; duplicate normalized `logicalType`/`path` pairs;
- attachments at exactly 512 MiB and one byte over, and structured-data entries at exactly 1 GiB and one byte over, using sparse/generated streams rather than repository fixtures of that physical size where practical;
- total uncompressed bytes exactly at and one byte over `min(20 GiB, available app-private staging space - 1 GiB)`, including available space equal to and below the 1 GiB reserve;
- per-entry and whole-archive compression ratio exactly 200:1 and above 200:1; zero-uncompressed-byte entries/archive; non-empty payload with zero compressed-byte denominator;
- declared sizes under actual streamed bytes, actual bytes crossing each per-entry/aggregate limit mid-stream, stream exhaustion, short read, low-space change during staging, and cleanup/retry after each failure.

Every rejection must occur without changing the current vault, exposing a success or confirmation state, or retaining an ambiguous restorable package. Temporary content remains identifiable and safely cleanable/retryable. Exact-limit fixtures still must pass format, hash, relationship, balance, and attachment validation; reaching a resource limit is not restore success.

## ViewModel state tests

Each editor and operation tests its declared entry state, valid and invalid primary-action enablement, all legal transitions, and rejection of illegal events. Required shared cases are:

- `Phase.Saving` rejects Back, Cancel, Discard, predictive Back, and duplicate Confirm, then emits one terminal result;
- recoverable failures retain every field, relation, assignment, and pending-media selection;
- validation and corrupt-input errors never schedule automatic retry;
- idempotent background retry is bounded and uses the injected scheduler;
- Search revisions ignore stale results and errors after edit, clear, or exit;
- export cancellation waits in `Phase.Cancelling` for its real terminal outcome;
- restore cannot cancel after final Confirm and cannot bypass `Phase.Recovering`;
- migration recovery pending and restore activation pending enter the app-start gate before Room-backed UI;
- App Lock defaults off; enable/disable requires platform authentication; enabled cold start and every foreground return lock immediately; cancel/failure/Back stays locked; generic recovery hides details until authentication;
- diagnostic export exposes only its envelope, uses the Running/Cancelling modal lock, and returns truthful terminal state without changing the vault or automatically sharing/uploading;
- saved-Detail relation edits commit independently, while Editor relation edits stay draft-only until owner save;
- success navigation occurs only after `Phase.Completed`.

## Five critical Compose UI flows

These are exactly the five release-critical UI flows from the approved design:

1. **Expense:** create an expense with multiple allocations, tags/project, and a linked note; assert validation, locked Saving, durable detail navigation, balance decrease, exact allocations, draft retention on failure, and no optimistic timeline/statistics update.
2. **Note:** create and edit Markdown and image-backed notes; assert body-or-confirmed-image validity, no required title, picker cancellation, offline confirmed image display, prior revision retention, and no visible orphan after save failure.
3. **Bidirectional association:** link multiple notes from a saved transaction and multiple transactions from a saved note; assert navigation from both details, atomic complete-set replacement, one relationship visible from both sides, Editor draft-only behavior, and unchanged prior sets on failure.
4. **Timeline:** launch into Timeline, browse a mixed day, switch all four bottom tabs, open transaction/note source details, and Back; assert card ordering/content, exact source dispatch, independent scroll/filter/date retention, and no projection write path.
5. **Export and restore:** create a complete logical backup, exercise progress/cancel race, overwrite the isolated test vault, restart through pending validation, and reach terminal success; also inject rollback and assert terminal failure returns only to the verified original vault. Assert no partial-success wording, no CSV in the ZIP, and no navigation escape after final restore confirmation.

CSV's separate interoperability test remains mandatory even though it is not a sixth critical Compose flow.

App Lock, locked recovery, and Diagnostic Export are additional mandatory Privacy/Diagnostics slice UI suites. They do not replace or renumber the five approved critical flows.

## Performance benchmarks

Each benchmark records dataset generator/version, app build, Android 14 reference device model, OS build, power/thermal setup, run count, warmups, metric definitions, p50/p95, and variability. Each owning slice records its numeric performance budget before benchmark implementation; a missing budget is a failed slice entry condition, not permission to defer the benchmark or report “looks fast.” The P4 search budget is already fixed above and is not governed by this paragraph.

- **Long timeline — PoC P6 and Timeline MVP slice:** mixed transaction/note fixture, cold first load plus sustained date/list scrolling; measure query-to-render latency, frame timing, memory peak, and dropped-frame/jank metric. This is the only P6 benchmark.
- **Image thumbnails — Notes/image MVP slice:** repeated and unique image fixture while timeline/note lists scroll; measure decode/load p95, frame timing, memory peak, and cache behavior without exposing full-size images unnecessarily. Rerun for related image/list changes and before release; it is not part of P6.
- **Aggregation — statistics MVP slice:** fixed high-volume finance fixture covering monthly income, expense, and categories; compare result correctness to a pure reference calculation and measure query p50/p95. Rerun for related query/model changes and before release; it is not part of P6.
- **Startup — App Shell and owning recovery/App Lock slices:** empty, typical, and high-volume vaults in healthy, pending activation, pending migration-recovery, locked, and locked-recovery states; measure time to first safe interactive frame or recovery/lock gate. A fast shell that bypasses recovery or App Lock is an automatic failure. Rerun for related startup/recovery/security changes and before release; it is not part of P6.

PoC P6 is blocked only by the mixed-card Timeline benchmark. The owning MVP slice for each of Timeline, thumbnails, aggregation, and startup is blocked by its own missing/failed benchmark; every one of the four is also an MVP release blocker. Related performance changes rerun only the affected benchmark(s) on the same frozen configuration. A missing fixture/budget, exceeded budget, wrong output, ANR/OOM, or non-comparable result without an explicitly approved new baseline is failure.

## Stage gates and release blockers

### Tech PoC gate into MVP

MVP work cannot begin until evidence passes all five architecture-risk proofs:

- atomic transfer and financial invariants;
- Chinese search 100% correctness, measured first-page p95 at most 300 ms, frozen reference configuration, and documented eligible-candidate selection;
- attachment fault recovery, deduplication, and orphan cleanup, explicitly including the `ATTACHMENT_SOURCE_COPY` and `ATTACHMENT_CONTENT_COMMIT` mappings;
- logical export → wipe/overwrite → complete restore comparison, explicitly including `BACKUP_STREAM_AND_PUBLISH`, `BACKUP_SAF_COPY`, pending activation, rollback, and applicable migration recovery;
- mixed-card Timeline performance on the approved P6 benchmark fixture; P6 contains no thumbnail, aggregation, or startup benchmark.

Failure changes the owning architecture source and ADR/spec as required; it cannot be waived by a passing UI demo or build.

### MVP release blockers

Release is blocked by any of the following:

- data loss, a balance that cannot be recomputed, an orphan/one-sided relation, a committed attachment link to missing content, or a backup that cannot fully restore;
- any N-1 or N-2 migration failure, pending migration recovery that can open ordinary Room/UI, or destructive migration fallback;
- any missing/failed malicious-ZIP limit case, Android system-backup/device-transfer exclusion check, source/merged/final artifact network-policy check, or airplane/network-isolated Journeys 1–13 run;
- failure of the Timeline, thumbnail, aggregation, or startup benchmark at its owning slice/relevant change/release gate;
- failure of App Lock/locked-recovery access control, secure-window/task-preview protection, diagnostic three-field event allowlist, UTC-day segment seven-day/1-MiB retention boundary, or Diagnostic Export modal/no-upload behavior;
- failure or missing evidence for any five critical Compose flows, required ViewModel state case, fault-injection suite, or approved performance budget; the mandatory fault suite includes `ATTACHMENT_SOURCE_COPY`, `ATTACHMENT_CONTENT_COMMIT`, `BACKUP_STREAM_AND_PUBLISH`, `BACKUP_SAF_COPY`, `RESTORE_ACTIVATION`, and `MIGRATION_RECOVERY`;
- incomplete 30-day real-use dogfood or unresolved data-integrity issue found during it;
- ads, commercial analytics, crash-reporting, or closed-source tracking SDK in the release dependency graph.

## Verification evidence format

Every PoC, slice, fix, and release candidate stores a verification record with these fields:

```text
Command: exact command, test selector, and arguments
Environment: commit, build variant, device/emulator model/profile, Android/API build, CPU/RAM, host CPU/RAM for AVD, fixture version, relevant configuration
Result: PASS/FAIL plus test count and measured values or artifact hashes
Remaining risk: untested boundary, environmental limitation, flaky signal, or "none known" with rationale
```

Evidence must name the specific required gate it satisfies and link generated reports when available. P4 evidence includes the frozen reference profile/API build/CPU/RAM/host fields, per-query expected/actual order, 30 measured first-page samples, correctness percentage, p50/p95/CV, absolute-gate eligibility, and final tie-break. ZIP-limit evidence enumerates every declared/actual boundary and proves unchanged current-vault hash plus temporary cleanup state. System-policy evidence includes source-manifest, merged-manifest, and final-APK inspection results for both network and backup/device-transfer rules. Diagnostic evidence proves every retained/exported event has exactly error category, stage, and anonymous operation ID with no timestamp; separately it records UTC-day segment creation/rollover/expiry, seven-day and exact 1-MiB boundaries, and package-summary `windowStart`/`windowEnd`/generation-time behavior without per-event association. App Lock evidence includes lifecycle, secure-window/task-preview, and recovery-order results. Fault-injection evidence places every required mapping and fail-point ID in `Result`, with the injected boundary, observed durable state, visibility/success assertion, residue classification, restart/retry outcome, and cleanup outcome. In particular, PoC P3 evidence must enumerate both attachment mappings, and PoC P5 evidence must enumerate both backup mappings plus restore activation and applicable migration recovery; a summary such as “fault tests passed” is insufficient. “Build passed,” screenshots without assertions, omitted commands, and unrepeatable manual claims are not gate evidence. A PoC, slice, or release remains blocked while any required mapped evidence is missing or failed.

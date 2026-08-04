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
| CSV interoperability regression | Separate CSV contract test exports all saved transactions from the same fixture | Exactly one row per saved transaction; one header; consistent columns; UTF-8 and declared machine-readable values match transaction detail | Every CSV change; not accepted as restore evidence |
| Interrupted attachment source copy | Fault injection before private temporary-file creation, during the first/middle/final copy chunks, after the final write but before close, after close but before validation/hash, and on source read or destination flush failure | No visible `AttachmentLink` exists and no success is reported; the original owner remains completely unchanged; partial temporary/orphan state is deterministically classified for safe cleanup or retry under [data and storage](../architecture/data-and-storage.md) | PoC P3, every attachment/import change, and MVP release |
| Attachment loss or orphan visibility | Fault injection at validation/hash, atomic move, owner transaction, `DELETE_PENDING`, physical delete, and metadata cleanup; include concurrent link/cleanup and duplicate hash | No committed link points to missing/unverified bytes; a failed owner is invisible; retries are idempotent; zero-reference cleanup never deletes newly linked content | PoC P3 and every attachment change |
| Interrupted logical-backup publication | Fault injection during each JSONL/attachment streaming write, before/during/after manifest write, before/after private atomic publication, and during SAF create/copy/flush/close/verify/rename or incomplete-marker clearing | An incomplete package is never listed as restorable and never reported as success; private temporary packages and SAF incomplete-marked residues are deterministically identifiable for safe cleanup or retry; only a complete package whose payload and manifest validation passes becomes visible as a backup | PoC P5, every backup/export change, and MVP release |
| Unsafe migration recovery | Fault injection after `MIGRATION_RECOVERY_PENDING`, before/after snapshot replacement, during failed database/WAL/SHM sidecar isolation, restore-copy validation, main replacement, and repeated startup | No empty database or mixed sidecar set opens; published snapshot stays unchanged; restart resumes idempotently; ordinary Room/shell access remains blocked until verified recovery | Every migration/recovery change and MVP release |
| Partial restore activation | Fault injection before activation, after pending activation, during startup validation, rollback-record write, quarantine, and cleanup | Old or candidate generation is complete; failed candidate rolls back; pending work resumes; referenced generations are never deleted; no partial success UI | PoC P5 and every restore change |
| Incorrect or unstable Chinese search | Run the fixed 10,000-record Chinese/English corpus and query manifest protocol from [data and storage](../architecture/data-and-storage.md) | Exact expected IDs and order pass before timing; 5 warmups/30 runs/page 50 produce p50, p95, CV; selection follows the documented correctness/stability tie-break | PoC P4 and any search/index change |
| UI state race or draft loss | ViewModel tests use controlled dispatchers, clock, IDs, and fake typed outcomes | All declared phases and only legal transitions occur; recoverable failures retain complete drafts; Saving locks exit; stale search is ignored; success waits for durable boundary | Every ViewModel change |
| Journey/navigation regression | Five critical Compose flows below run with success, validation, infrastructure failure, Back, and recreation cases | Every required assertion passes with correct source-detail routing and retained context | Related slice and MVP release |
| Performance degradation | Reproducible timeline, thumbnail, aggregation, and startup benchmarks below on the recorded Android 14 reference configuration | No ANR/OOM/corrupt output; metrics meet the numeric budget approved in the active PoC spec and remain within its regression allowance | PoC P6, performance change, MVP release |

## Domain and persistence suites

### Unit and property coverage

Boundary examples include zero, one minor unit, maximum supported minor amount, allocation rounding edges, same-account transfer, different-currency transfer, archived references, duplicate relations, unavailable targets, and soft-deleted owners. Property failures persist the seed and minimal counterexample in test output; rerunning that seed is mandatory before a fix is accepted.

### Room transaction coverage

Use a real Room database in instrumented tests, with foreign keys enabled and the same transaction entry points as production. For each write boundary, inject failure before the transaction, between each logical child write, before commit, and immediately after commit response handling. Assert both stored facts and observable projection state. Fakes are suitable for ViewModel tests but cannot prove Room atomicity.

### Migration coverage

Keep versioned N-1 and N-2 database fixtures and exported Room schema JSON in version control after Room exists. Each migration test verifies SQLite integrity, foreign keys, the applicable domain invariant matrix, projection rebuildability, and post-open queries. Tests also cover interrupted recoverable snapshots and the migration startup gate. `fallbackToDestructiveMigration` is prohibited and can never be used to make a migration test pass.

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

## Performance benchmarks

Each benchmark records dataset generator/version, app build, Android 14 reference device model, OS build, power/thermal setup, run count, warmups, metric definitions, p50/p95, and variability. The active Tech PoC spec must approve numeric budgets before benchmark implementation; a missing budget is a failed entry condition, not permission to report “looks fast.”

- **Long timeline:** mixed transaction/note fixture, cold first load plus sustained date/list scrolling; measure query-to-render latency, frame timing, memory peak, and dropped-frame/jank metric.
- **Image thumbnails:** repeated and unique image fixture while timeline/note lists scroll; measure decode/load p95, frame timing, memory peak, and cache behavior without exposing full-size images unnecessarily.
- **Aggregation:** fixed high-volume finance fixture covering monthly income, expense, and categories; compare result correctness to a pure reference calculation and measure query p50/p95.
- **Startup:** empty, typical, and high-volume vaults in healthy, pending activation, and pending migration-recovery states; measure time to first safe interactive frame or recovery gate. A fast shell that bypasses recovery is an automatic failure.

PoC P6 and the MVP release are blocked when a required benchmark lacks its approved fixture/budget, exceeds that budget, produces wrong output, or encounters ANR/OOM. Performance changes rerun the affected benchmark on the same reference configuration; non-comparable environments are reported as new baselines, not silently combined.

## Stage gates and release blockers

### Tech PoC gate into MVP

MVP work cannot begin until evidence passes all five architecture-risk proofs:

- atomic transfer and financial invariants;
- Chinese search correctness and documented candidate selection;
- attachment fault recovery, deduplication, and orphan cleanup, explicitly including the `ATTACHMENT_SOURCE_COPY` and `ATTACHMENT_CONTENT_COMMIT` mappings;
- logical export → wipe/overwrite → complete restore comparison, explicitly including `BACKUP_STREAM_AND_PUBLISH`, `BACKUP_SAF_COPY`, pending activation, rollback, and applicable migration recovery;
- mixed timeline performance on the approved benchmark fixture.

Failure changes the owning architecture source and ADR/spec as required; it cannot be waived by a passing UI demo or build.

### MVP release blockers

Release is blocked by any of the following:

- data loss, a balance that cannot be recomputed, an orphan/one-sided relation, a committed attachment link to missing content, or a backup that cannot fully restore;
- any N-1 or N-2 migration failure, pending migration recovery that can open ordinary Room/UI, or destructive migration fallback;
- failure or missing evidence for any five critical Compose flows, required ViewModel state case, fault-injection suite, or approved performance budget; the mandatory fault suite includes `ATTACHMENT_SOURCE_COPY`, `ATTACHMENT_CONTENT_COMMIT`, `BACKUP_STREAM_AND_PUBLISH`, `BACKUP_SAF_COPY`, `RESTORE_ACTIVATION`, and `MIGRATION_RECOVERY`;
- incomplete 30-day real-use dogfood or unresolved data-integrity issue found during it;
- ads, commercial analytics, crash-reporting, or closed-source tracking SDK in the release dependency graph.

## Verification evidence format

Every PoC, slice, fix, and release candidate stores a verification record with these fields:

```text
Command: exact command, test selector, and arguments
Environment: commit, build variant, device/emulator model, Android/OS build, fixture version, relevant configuration
Result: PASS/FAIL plus test count and measured values or artifact hashes
Remaining risk: untested boundary, environmental limitation, flaky signal, or "none known" with rationale
```

Evidence must name the specific required gate it satisfies and link generated reports when available. Fault-injection evidence places every required mapping and fail-point ID in `Result`, with the injected boundary, observed durable state, visibility/success assertion, residue classification, restart/retry outcome, and cleanup outcome. In particular, PoC P3 evidence must enumerate both attachment mappings, and PoC P5 evidence must enumerate both backup mappings plus restore activation and applicable migration recovery; a summary such as “fault tests passed” is insufficient. “Build passed,” screenshots without assertions, omitted commands, and unrepeatable manual claims are not gate evidence. A PoC, slice, or release remains blocked while any required mapped evidence is missing or failed.

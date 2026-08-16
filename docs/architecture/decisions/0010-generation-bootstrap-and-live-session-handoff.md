# ADR 0010: Generation bootstrap and live-session handoff

## Status

Accepted

## Date

2026-08-09

The user explicitly accepted the non-destructive legacy-layout bootstrap and same-repository atomic generation handoff on 2026-08-09 while reviewing Tech PoC P5 Revision 1.

## Context

Tech PoC P3 and P4 run against the root-level `record.db`, root-level `files/attachments/`, and the version-1 migration-recovery record at `files/recovery/activation-recovery-v1.json`. P5 introduces the generation layout required by [ADR 0003](0003-generation-based-restore.md): `files/vaults/<generationId>/record.db`, per-generation attachments, and a version-2 activation/recovery record containing both migration-recovery and generation-activation state.

[ADR 0007](0007-recoverable-migration-snapshot-ownership.md) makes the recovery-record encoding and published paths persistence-compatibility facts. Rejecting every version-1 record or creating an empty first generation while a legacy database exists would make an existing P3/P4 installation fail startup or abandon valid data. The first v1-to-v2 record conversion and the physical layout transition therefore require an explicit, recoverable compatibility decision even though the Room schema remains version 2.

P5 also needs a truthful terminal result from `confirmRestore`. Merely switching the activation record while the calling `RecordRepository` still owns a Room handle to the old generation would allow later calls through that instance to write obsolete data. Requiring an Android process restart solely to complete a foreground restore would move lifecycle complexity into M6 and make storage success depend on process control rather than the durable protocol.

## Options considered

- **Copy, validate, then atomically publish a version-2 pointer; rebind the same repository under an exclusive operation gate (chosen).** The legacy source remains untouched until a complete generation is validated, and all existing repository references continue through one object that changes its backing Room generation only inside an exclusive handoff.
- Require a clean install or destructive reset before P5. Rejected because it abandons existing valid local data, hides the compatibility problem, and violates the project's non-destructive persistence rule.
- Treat the root-level database and attachments as a permanent special “generation zero.” Rejected because every backup, restore, migration, attachment, cleanup, and startup path would retain a second layout indefinitely.
- Make `confirmRestore` return pending and require a process restart before success. Rejected because the product needs a foreground terminal result after the candidate is healthy, and Android process restart is not a storage invariant.
- Introduce a new public `VaultSession` layer. Rejected for P5 because a repository-wide operation gate and atomic backing-store rebind solve the concrete safety problem without expanding the seven-module architecture or adding a second public ownership abstraction.

## Decision

### Legacy-layout bootstrap

Before normal version-2 startup resolution, P5 classifies the installed state without opening Room:

1. If a version-1 recovery record exists, the legacy P3 migration coordinator resolves it completely against the legacy database before bootstrap. A malformed record fails closed. A pending record is never converted or bypassed.
2. If the version-2 record exists, normal migration-then-generation startup resolution runs; the legacy bootstrap never runs again.
3. If no record exists but a legacy database or root attachment tree exists, that legacy state is treated as bootstrap input rather than as a fresh install.
4. Only when neither a recovery record nor any legacy vault data exists does startup create a fresh initial generation and atomically publish a healthy version-2 record before Room opens.

Legacy bootstrap runs with no Room handle and no repository operation active. If v1 migration recovery restores an older schema, the existing P3 coordinator completes the already-approved v1→v2 Room migration on the legacy layout before bootstrap copying begins. Bootstrap then checkpoints and closes the legacy database, validates SQLite integrity and the current schema/domain invariants, and records the post-checkpoint source database plus attachment hashes as the frozen bootstrap baseline. It copies the clean main database and complete root attachment tree into one unreferenced, incomplete-marked generation, then validates the copy's record counts, foreign keys, XOR and finance invariants, balances, attachment metadata/link ownership, and every referenced blob's size and SHA-256. Copying, candidate validation, and pointer publication do not change the frozen source baseline; the checkpoint itself may change database/WAL bytes without changing logical state.

Only after the copied generation passes complete validation does one `AtomicFile` write replace the version-1 record with the complete version-2 activation/recovery record: the migration portion preserves its resolved meaning, `activeGenerationId` names the validated generation, every nullable generation field is null, and generation state is `HEALTHY`. The version-2 reader supports exactly version 2 during normal operation; version 1 is accepted only by this one-time bootstrap reader.

A crash before the version-2 record write leaves the version-1 record and legacy source authoritative; incomplete unreferenced bootstrap directories are identifiable and can be cleaned or replaced idempotently on retry. A crash after the record write leaves a complete pointer-owned generation authoritative. P5 does not delete the legacy database, sidecars, root attachment tree, or old migration artifacts. Their eventual removal is separately authorized maintenance; retaining them cannot make them active again or bypass the version-2 pointer.

### Live-session generation handoff

One process-wide internal session registry is keyed by canonical vault root. It serializes the first open/bootstrap and returns the same live `RecordRepository` for every production open of that root; a second independently bound repository is forbidden. Test helpers use distinct explicit roots and release their registry entries. The app declares no secondary process, and a future multi-process component requires a new cross-process locking decision.

Each registered repository owns one operation gate. Ordinary reads, writes, export, staging, attachment work, and maintenance acquire the shared side. Generation bootstrap, restore activation, rollback, startup resolution, and backing-database replacement acquire the exclusive side. No new operation begins while an exclusive transition is in progress.

`stageRestore` produces one immutable staged candidate and a strict staging receipt containing candidate ID, source-package SHA-256, manifest SHA-256, sorted entry hashes/counts, validation-contract version, and the candidate logical-state digest. The returned typed handle contains the candidate ID and SHA-256 of that canonical UTF-8 receipt. At most one staged candidate belongs to a repository at a time. `confirmRestore(staged)` recomputes the receipt hash and verifies that the supplied handle matches that exact candidate; a stale, forged, replaced, or mismatched handle fails before the activation record changes. A staged handle is process-local: process death makes it unusable and the user must stage the source again; unreferenced residue is ordinary safe orphan input.

The repository holds one generation-scoped `VaultBinding`: generation ID/path, Room database and DAO, attachment store/ingestor/link manager/cleaner, and migration/maintenance collaborators that use that vault. Under the exclusive gate, confirmation closes the old binding's Room database and WAL/SHM handles, atomically writes the `PENDING` activation record, and invokes the same migration-then-generation resolver used at cold startup. Candidate success writes the healthy transition, constructs a complete candidate binding, and atomically swaps the repository binding reference before returning success. Candidate failure writes rollback, constructs the original binding, swaps it back, and returns typed failure. Thus callers never retain a repository whose DAO or file collaborator targets a generation different from the durable active pointer.

If the process dies after `PENDING`, the next cold startup runs the same resolver. If an operation attempts to use the repository while its exclusive transition is in progress, it waits; no success or ordinary write is allowed through a half-rebound repository. Cancellation is honored only before the activation switch. After `PENDING` is durable, resolution runs in a non-cancellable section until healthy or rollback plus binding construction completes, then reports the real terminal state.

## Consequences

- P5 performs a persistent-layout migration without changing `databaseVersion`; it requires explicit upgrade and crash-window evidence rather than being described as fresh-install initialization.
- Existing P3/P4 data remains recoverable because the source is copied, never mutated or deleted, before the version-2 pointer becomes authoritative.
- The activation/recovery filename remains unchanged for continuity, while its normal-operation content advances to `recordVersion = 2`.
- All repository operations participate in one gate, adding a small coordination cost in exchange for preventing writes through stale Room handles.
- Production code has exactly one live repository/session per canonical vault root, so no second instance can retain an obsolete binding after handoff.
- Restore confirmation is bound to one validated candidate and can truthfully return terminal success without requiring process restart.
- Legacy-source cleanup is outside P5. No implementation or verification step may delete those files under this ADR.

## Verification

- Upgrade fixtures cover a healthy v1 record, a recovered v1 record, every pending v1 migration stage, no record with legacy data, and a truly fresh install.
- Fault injection covers before/during/after legacy WAL checkpoint, database copy, each attachment copy, copied-generation validation, and the atomic v2 record write. Every point runs fail-once/restart, repeated-failure, and complete/repeat scenarios.
- Before the v2 record write, restart opens or resumes only the unchanged legacy source. After the write, restart opens only the complete generation. No crash window creates an empty database or treats an incomplete directory as active.
- The copied generation matches every current-schema logical field, relation, balance, attachment owner, size, and SHA-256; post-checkpoint legacy source hashes remain unchanged through copy, validation, and publication.
- Concurrent repository tests hold reads and writes around confirmation, prove the exclusive gate drains them before pointer activation, and prove the same repository instance uses the new generation after success or the old generation after rollback.
- Concurrent-open tests prove one bootstrap execution and one repository identity per canonical production root; isolated test roots remain independent.
- Candidate-binding tests reject stale, mismatched, repeated, and concurrently replaced staged handles before activation-record mutation.

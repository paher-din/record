# ADR 0003: Generation-based overwrite restore

## Status

Accepted

## Date

2026-08-04

## Context

Overwrite restore must replace all current facts, relationships, and attachments as one validated unit while preserving a recoverable current state if import, validation, switching, or first startup fails. The complete format and lifecycle are defined by the [data and storage architecture](../data-and-storage.md), and the observable outcome remains defined by the [MVP requirements](../../product/mvp-requirements.md).

## Options considered

- Import into an isolated staging generation and switch only after validation.
- Mutate the active database and attachment tree in place.
- Replace or synchronize live SQLite and WAL files.
- Retain the previous generation for rollback or delete it immediately after switching.

## Decision

Import the database and attachments into an isolated staging generation. Before activation, validate both payload checksums and domain invariants across the complete staged generation. Leave the current generation untouched during staging.

Activation uses one atomically persisted record containing `activeGenerationId`, nullable `previousGenerationId`, nullable `candidateGenerationId`, nullable `retiringGenerationId`, and a `HEALTHY` or `PENDING` state. A new activation may begin only from `HEALTHY` with all three nullable generation fields clear. If retirement is pending, preflight first completes it idempotently or fails the new restore with a typed maintenance error while the current active generation remains readable.

Activation atomically writes `activeGenerationId=C`, `previousGenerationId=O`, `candidateGenerationId=C`, `retiringGenerationId=null`, and `state=PENDING`. The next startup must revalidate the pending candidate before normal use. Success performs one atomic transition to `activeGenerationId=C`, `retiringGenerationId=O`, both previous/candidate fields null, and `state=HEALTHY`. Candidate failure performs one atomic rollback to `activeGenerationId=O`, `retiringGenerationId=C`, both previous/candidate fields null, and `state=HEALTHY`.

Retirement is a persisted cleanup intention, not a rollback pointer. Under a per-generation lock and after closing all handles, cleanup re-reads the record, verifies the retiring ID differs from active, previous, and candidate, deletes that generation or confirms it absent, then atomically clears `retiringGenerationId`. A crash before deletion retries; a crash after deletion but before clear observes absence and completes. Startup scans complete and incomplete generation directories only after migration and activation gates are safe, never deletes a pointer-referenced generation, and idempotently removes unreferenced orphans. All activation, validation recovery, rollback, orphan scanning, and cleanup steps are idempotent.

The current restore may reach terminal UI success once the candidate is healthy; retirement may continue as non-blocking maintenance. It cannot be skipped for correctness: the next restore must complete retirement synchronously before staging, and a pending activation is always resolved at startup before Room or normal UI. AndroidX WorkManager may retry retiring cleanup, but it never owns activation, rollback, or the startup safety gate.

No failure may expose a partial restore. The restore path does not replace or synchronize a live SQLite database or its WAL files.

## Consequences

- Failed preflight, import, validation, activation, or startup leaves a complete healthy generation available rather than a partially replaced dataset.
- A durable `PENDING` state turns a crash after activation into an idempotent startup-validation retry rather than normal use of an unconfirmed generation.
- Atomic rollback restores a complete activation record before the failed candidate becomes cleanup-eligible.
- The previous generation is a rollback path only while the candidate is `PENDING`; successful validation or rollback converts the now-obsolete generation into explicit retirement intent.
- Restore requires temporary storage for staging and the pending rollback generation, with bounded post-health retirement accepted for data safety.
- A cleanup failure does not block current healthy reads, but it blocks the next restore until typed maintenance succeeds.
- Generation lifecycle, startup validation, idempotent transitions, pointer-protected orphan scanning, reference-aware deferred cleanup, and atomic activation-record persistence become explicit reliability responsibilities.

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

Activation uses one atomically persisted record containing `activeGenerationId`, nullable `previousGenerationId`, nullable `candidateGenerationId`, and a `HEALTHY` or `PENDING` state. Activation atomically changes the record from the old healthy generation to the candidate as active, the old generation as previous, the candidate marker set, and `state=PENDING`.

The next startup must revalidate the pending candidate before normal use. Success atomically sets `state=HEALTHY` and clears the candidate marker while retaining the previous generation for rollback retention. Failure atomically restores the previous generation as active and a healthy state before the failed candidate is quarantined or cleaned. All activation, validation recovery, rollback, quarantine, and cleanup steps are idempotent. No generation referenced by any activation-record field may be deleted.

No failure may expose a partial restore. The restore path does not replace or synchronize a live SQLite database or its WAL files.

## Consequences

- Failed preflight, import, validation, activation, or startup leaves a complete healthy generation available rather than a partially replaced dataset.
- A durable `PENDING` state turns a crash after activation into an idempotent startup-validation retry rather than normal use of an unconfirmed generation.
- Atomic rollback restores a complete activation record before the failed candidate becomes cleanup-eligible.
- The previous generation provides a bounded rollback path until the new generation becomes `HEALTHY` after startup validation.
- Restore requires temporary storage for the staging generation and retained rollback generation; that cost is accepted for data safety.
- Generation lifecycle, startup validation, idempotent transitions, reference-aware deferred cleanup, and atomic activation-record persistence become explicit reliability responsibilities.

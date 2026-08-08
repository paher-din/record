# ADR 0007: Recoverable-migration-snapshot machinery ownership

## Status

Accepted

## Date

2026-08-08

## Context

Tech PoC P1 registered an open decision: the phase ownership of the recoverable-migration-snapshot machinery is settled through the core documents and an ADR before the first migration after P1. P2 carried that decision forward unchanged because P2 opens no new schema. P3 — attachment failure recovery — is the first schema-changing slice: it must add `Attachment` and `AttachmentLink` tables and advance `databaseVersion` from 1 to 2.

[data and storage](../data-and-storage.md) states the recoverable-migration-snapshot protocol unconditionally: "Before a new Room schema opens, a migration coordinator blocks writers and creates a recoverable local snapshot." The protocol covers checkpoint/close, snapshot copy, integrity and old-schema invariant validation, SHA-256, atomic publication, and the `MIGRATION_RECOVERY_PENDING` state machine with idempotent restart. The testing strategy maps `MIGRATION_RECOVERY` evidence to the PoC P5/MVP recovery gates as "applicable to that slice", while its migration-risk row gates migration testing at "every migration/recovery change" and "MVP release".

The authoritative lifecycle is defined by [data and storage](../data-and-storage.md), the schema contract by [ADR 0006](0006-version-1-persistence-contract.md), and the P3 slice scope and evidence by the [P3 spec](../../specs/tech-poc/p3-attachment-failure-recovery/spec.md).

## Options considered

- **P3 establishes the coordinator (chosen).** The data-and-storage protocol is unconditional for every schema change, and P3 is the first one. P3 implements the coordinator and carries the applicable `MIGRATION_RECOVERY` evidence for its own v1 → v2 migration; the full N-1/N-2 migration matrix and the complete `MIGRATION_RECOVERY` fault suite belong to M7.
- Defer the coordinator past the first schema change (to P5 or M7). Rejected: the first migration would open a new schema without the recoverable snapshot the protocol requires, creating a persistence-compatibility hole exactly where the first upgrade of real v1 data occurs. P2's open decision explicitly forbids starting P3 implementation on an unresolved contradiction.
- Move the coordinator earlier, into P2. Rejected: P2 deliberately opens no schema and would implement machinery with no exercised migration; P3 is the earliest slice where the protocol has a real migration to protect.

## Decision

P3 establishes the recoverable-migration-snapshot machinery and implements the data-and-storage protocol for its v1 → v2 migration: the coordinator blocks writers, creates and publishes a verified snapshot before the migration opens the new schema, persists the `MIGRATION_RECOVERY_PENDING` record atomically without Room, and every startup resolves the record before any Room or SQLite initialization. P3 proves the applicable recovery evidence: every protocol boundary is injectable, recovery is idempotent, no restart creates an empty database or opens a mixed database/sidecar set, and the published snapshot stays unchanged.

The full N-1/N-2 migration matrix and the complete `MIGRATION_RECOVERY` fault suite remain M7's; P5 additionally records the migration-recovery evidence applicable to its restore journeys. Any later schema change after P3 must reuse or extend this coordinator, not re-implement it.

## Consequences

- P3's scope includes the coordinator and its applicable fault evidence, beyond the two attachment mappings.
- Every future schema change (M2 onward) builds on an existing, verified coordinator rather than introducing a new mechanism.
- The `migrationRecoveryState` record and the published snapshot path become persistence-compatibility facts from P3 on; changing them is itself a migration decision.
- The P2 open-decision record is closed by this ADR.

## Verification

- P3 spec criterion 10 passes: a v1 fixture database upgrades to v2 through the coordinator; failure injected at every protocol boundary resumes idempotently, never creates an empty database or a mixed database/sidecar set, never changes the published snapshot, and the recovered vault matches the pre-migration state.
- The coordinator and its recovery record are exercised by the P3 instrumented fault suite on the reference device.

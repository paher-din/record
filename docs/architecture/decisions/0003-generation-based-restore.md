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

Import the database and attachments into an isolated staging generation and validate the whole generation before switching. Persist the active-generation pointer atomically, and leave the current generation untouched until that pointer changes. Retain the previous generation until the new generation starts successfully and validation passes again.

No failure may expose a partial restore. The restore path does not replace or synchronize a live SQLite database or its WAL files.

## Consequences

- Failed preflight, import, validation, switching, or startup leaves a complete generation available rather than a partially replaced dataset.
- The previous generation provides a bounded rollback path until the new generation proves healthy.
- Restore requires temporary storage for the staging generation and retained rollback generation; that cost is accepted for data safety.
- Generation lifecycle, startup validation, deferred cleanup, and atomic pointer persistence become explicit reliability responsibilities.

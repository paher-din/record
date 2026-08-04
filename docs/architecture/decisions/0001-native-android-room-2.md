# ADR 0001: Native Android and Room 2 for MVP

## Status

Accepted

## Date

2026-08-04

## Context

The MVP needs an Android-first implementation with reliable local persistence, predictable platform integration, and a deliberately narrow compatibility surface. The architecture must choose both the application stack and the Room generation before implementation. Persistence and recovery policy is owned by the [data and storage architecture](../data-and-storage.md).

## Options considered

- Native Kotlin and Jetpack Compose.
- Flutter.
- React Native.
- A full-app fork of an existing application.
- Room 2.8.4.
- Room 3.
- AndroidX WorkManager for deferred maintenance.
- Supporting Android releases below Android 14.

## Decision

Build the MVP as a native Android application using Kotlin and Jetpack Compose rather than Flutter, React Native, or a full-app fork. Use the Room 2 generation rather than Room 3 for the MVP; the initial Room 2.8.4 baseline remains owned by the [data and storage architecture](../data-and-storage.md). Set Android 14 and `minSdk 34` as the supported platform floor. AndroidX WorkManager is part of the approved stack for delayed, idempotent, non-user-blocking maintenance only: zero-reference attachment cleanup after its grace period, retry of a persisted retiring-generation cleanup intention, and later maintenance explicitly approved under the same boundary.

WorkManager never performs a user Save, CSV export, logical backup, overwrite restore, migration, activation, rollback, startup validation, or any correctness-critical gate. Foreground user operations retain their truthful UI state machines, and startup synchronously resolves migration and activation gates before Room or the shell. Each maintenance worker uses unique work keyed by its persisted operation/content identity, rechecks durable preconditions on every run, and retries only typed transient failures with bounded backoff and constraints justified by the operation.

Room 2 patch and minor versions are maintained in the version catalog and require compatibility and migration verification, not a new ADR. A new persistence ADR is required only for a Room major-generation change or a persistence-contract change. Changes to the application stack or Android platform floor still require an appropriate architectural decision record.

## Consequences

- Engineering effort is concentrated on Android-first quality and direct platform integration.
- The product accepts less legacy Android compatibility and delays iOS support.
- Domain logic remains self-owned instead of inheriting another application's architecture or constraints.
- The project owns Room schema evolution, explicit migrations, migration tests, and recovery compatibility.
- Deferred maintenance gains Android-owned durable scheduling without moving correctness or foreground completion into a background worker.
- WorkManager version changes are maintained in the version catalog and verified like other dependencies.
- Room 2 patch and minor updates can follow verified dependency maintenance without creating decision-record churn.

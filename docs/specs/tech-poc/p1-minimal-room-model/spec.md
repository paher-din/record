# Tech PoC P1 — Minimal Room Model

- **Status:** `draft` — behavior, boundaries, and acceptance are being clarified. Implementation is forbidden.
- **Drafted:** 2026-08-05
- **Roadmap:** [Tech PoC P1](../../../roadmap.md)
- **Domain facts and invariants:** [Domain model](../../../architecture/domain-model.md)
- **Persistence and recovery policy:** [Data and storage](../../../architecture/data-and-storage.md)
- **Identity and relations decision:** [ADR 0002](../../../architecture/decisions/0002-entityref-and-strong-relations.md)
- **Module boundaries:** [Architecture overview](../../../architecture/overview.md)
- **Verification policy:** [Testing strategy](../../../engineering/testing-strategy.md)

## 1. Purpose

Prove the smallest Room 2.8.4 persistence model for `EntityRef`, `Transaction`, `AccountEntry`, `Note`, and the approved strong relations: the same-ID `EntityRef` subtype edges and the typed `TransactionNoteRelation`. Instrumented tests must persist and reload the minimal facts, enforce same-ID `EntityRef` XOR identity, real foreign keys, typed endpoint rules, and atomic aggregate visibility, and confirm that Timeline/Search projections cannot originate writes — without turning any projection into a fact.

Passing P1 gives P2 the verified persistence foundation for the finance matrix, and gives every later slice the schema-versioning and instrumented-test baseline.

## 2. Reference environment

The reference environment remains the frozen P0 configuration. It is part of the P1 gate: running instrumented tests on a different configuration makes the run invalid and requires an explicit approval record of a new baseline.

| Field | Recorded value |
|---|---|
| Device | Xiaomi 14 Pro (physical device, USB debugging) |
| Android baseline | Android 14 / API 34 (out-of-box baseline) |
| Actual system build | Recorded in `verification.md` at P1 verification (currently Android 16 / API 36 per P0; recorded, not rejected, because `minSdk 34` keeps the app compatible) |
| AVD / host CPU-RAM fields | Not applicable — physical device reference |

## 3. Build baseline

- `minSdk = 34`, `targetSdk = 34`, `compileSdk = 36` — unchanged from P0.
- Room 2.8.4 runtime and the Room compiler are added to `:core:data` through the version catalog; the compiler annotation processor (KSP pinned to the AGP 9 built-in Kotlin 2.2.10) and test dependencies are recorded with dependency-review evidence at plan approval, per the engineering conventions.
- The Room schema directory is configured so schema JSON is exported and versioned with the repository; P1 creates the first schema, `databaseVersion = 1`.
- `fallbackToDestructiveMigration` is forbidden and must not appear anywhere in the build.
- The seven-module graph and its enforced edges are unchanged; the P0 `checkModuleDependencies` task must keep passing.
- `:core:model` remains a pure Kotlin JVM module with no Android or Room types; Room entities, DAOs, and the database live in `:core:data`.

## 4. Model scope

### 4.1 Tables in database version 1

The minimal model contains exactly these tables, matching the [domain model](../../../architecture/domain-model.md) fields:

- `EntityRef` — `id` (PK, app-generated random UUID string), `entityType`, `createdAt`, `updatedAt`, nullable `deletedAt` (absent while active).
- `Transaction` — `id` (PK, same-ID FK to `EntityRef`), `type`.
- `Account` — `id` (PK), `name`, `currency`. Identity-only: it is the foreign-key target required by `AccountEntry`; no balance, lifecycle, or opening history.
- `AccountEntry` — `id` (PK), `transactionId` (FK), `accountId` (FK), `amountMinor` (signed integer minor units).
- `Note` — `id` (PK, same-ID FK to `EntityRef`).
- `TransactionNoteRelation` — `transactionId` (PK, FK), `noteId` (PK, FK): the typed, plain many-to-many relation from ADR 0002.

`EntityType` (`TRANSACTION` | `NOTE` | `PROJECT`) and `TransactionType` (`OPENING_BALANCE` | `INCOME` | `EXPENSE` | `TRANSFER`) enums mirror the domain model exactly and live in `:core:model`; the entities in `:core:data` persist them as their approved string values.

### 4.2 Deferred to later slices

Explicitly not part of P1:

- `Project` and its `EntityRef` identity edge (M4). In P1 no `EntityRef` of type `PROJECT` can exist because no `Project` table or type exists; the XOR invariant is therefore enforced and tested for the `Transaction` and `Note` subtype edges present here, and the same mechanism extends to `Project` when it arrives.
- `Category`, `CategoryAllocation` (M2), `Tag`, `EntityTag` (M3), `Attachment`, `AttachmentLink` (P3).
- `primaryProjectId` on `Transaction`/`Note`, catalog name fields, `SearchDocument`/`Timeline` projection tables (P4/M5), migrations, migration-recovery snapshot machinery, generation activation, and WorkManager.
- No `Money` type is introduced; P1 stores `amountMinor` as `Long` (P2 owns the money invariants).

## 5. Invariants and acceptance criteria

The slice passes only when all of the following have evidence in `verification.md`:

1. **Persist and reload minimal facts.** Every table from section 4.1 round-trips through a real Room database with foreign keys enabled: write, close/reopen, read back exact field values including enum string values, nullable `deletedAt` absent/present, and relation rows.
2. **Same-ID `EntityRef` XOR identity.** An `EntityRef` and its typed `Transaction`/`Note` row share the same ID, `entityType` matches exactly one present subtype, and the database rejects a mismatched discriminator, a missing subtype, and a duplicate subtype row.
3. **Foreign keys.** Referential integrity is enforced: a child row referencing a missing parent fails to insert, and a parent with child rows cannot be deleted into an orphaned state.
4. **Typed endpoint rules.** `TransactionNoteRelation` accepts only existing `Transaction` and `Note` endpoints; a relation with a nonexistent or wrongly-typed endpoint is rejected; complete-set replacement is one atomic operation.
5. **Atomic aggregate visibility.** A `Transaction` plus its `AccountEntry` rows plus requested relations — and a `Note` plus its relations — commit as one indivisible Room transaction. Failure injected before the transaction, between logical child writes, before commit, and after commit-response handling leaves only the old complete state observable; observers never see a partial aggregate, a one-sided relation, or a committed source/projection mismatch.
6. **Projection write ban.** The schema contains no `Timeline` or `SearchDocument` table, and the persistence layer's public write surface is closed over the owner aggregates: no write path exists for any projection type. A test proves no projection write API can be constructed from the schema or the public surface.
7. **Regression.** `./gradlew clean check` passes (module graph, build, lint unchanged), and the app still installs and launches on the reference device.

## 6. Test evidence requirements

- Instrumented Room tests (`androidTest`) run on the reference device with a real Room database, foreign keys enabled, and the same transaction entry points as production, per the testing strategy.
- Failure injection covers the four boundaries in criterion 5 for each aggregate entry point, with `Command`, `Environment`, `Result`, `Remaining risk` evidence per the testing strategy format.
- `verification.md` maps every acceptance criterion and the roadmap P1 exit gate to concrete commands and results; a build-only result cannot stand in for the required tests.

## 7. Non-goals

P1 explicitly does not:

- Implement the finance transaction matrix, transfer, zero-sum, or balance invariants (P2).
- Implement attachments or content-addressed storage (P3), search or projections (P4), backup/restore or migrations (P5), or timeline performance (P6).
- Introduce any catalog table, `Project`, catalog name fields, or lifecycle machinery (M2–M4).
- Implement migration recovery, generation activation, WorkManager, or any background maintenance.
- Add any UI, ViewModel, or navigation; the repository surface exists only to prove atomicity with production entry points.
- Change any core document fact. If implementation exposes a conflict with a core document, the documentation-first protocol applies before any code change.

## 8. Dependency review boundary

Every dependency introduced by P1 (Room compiler, KSP plugin, `room-testing`, and any test helper) is recorded at plan approval per the engineering conventions: version, license, maintenance evidence, compatibility impact, and removal path. A dependency is rejected when its license is incompatible, its maintenance status is unknown, it requires excessive permissions, it introduces undisclosed network behavior, or no practical removal path exists. AndroidX and Kotlin official libraries are preferred; no ads, analytics, crash-reporting, or closed-source tracking SDK.

## 9. Risks

- KSP must match the AGP 9 built-in Kotlin 2.2.10 exactly; an incompatible KSP/compiler pair is the most likely P1 failure and is resolved at plan approval.
- Schema JSON export and versioning must be configured when the first schema is created; a missing export is a P1 failure, not a cleanup item.
- Instrumented tests on the physical reference device are slower than local unit tests; the plan must account for device time and USB stability.
- The reference device runs API 36; Room behavior on the upgraded system is recorded at verification, not rejected — the same rule as P0.

## 10. Lifecycle path

- `draft` (this document): behavior, boundaries, and acceptance are clarified here. No implementation.
- `approved`: the user explicitly accepts this spec; the approval date/context is recorded.
- `in-progress`: the user approves the plan (recording exact tasks, file changes, and verification commands); implementation may begin.
- `completed`: acceptance criteria and the roadmap P1 exit gate have verification evidence; P2 may then start.

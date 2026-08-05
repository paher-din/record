# Tech PoC P1 — Minimal Room Model

- **Status:** `draft` — behavior, boundaries, and acceptance are being clarified. Implementation is forbidden.
- **Drafted:** 2026-08-05
- **Revision 1:** 2026-08-05 — review corrections: commit-after-failure expectation, XOR enforcement mechanism, migration boundary, KSP2 versioning, concurrency acceptance, field-subset wording, and machine-checkable projection write ban.
- **Roadmap:** [Tech PoC P1](../../../roadmap.md)
- **Domain facts and invariants:** [Domain model](../../../architecture/domain-model.md)
- **Persistence and recovery policy:** [Data and storage](../../../architecture/data-and-storage.md)
- **Identity and relations decision:** [ADR 0002](../../../architecture/decisions/0002-entityref-and-strong-relations.md)
- **Module boundaries:** [Architecture overview](../../../architecture/overview.md)
- **Verification policy:** [Testing strategy](../../../engineering/testing-strategy.md)

## 1. Purpose

Prove the smallest Room 2.8.4 persistence model for `EntityRef`, `Transaction`, `AccountEntry`, `Note`, and the approved strong relations: the same-ID `EntityRef` subtype edges and the typed `TransactionNoteRelation`. Instrumented tests must persist and reload the minimal facts, enforce same-ID `EntityRef` XOR identity, real foreign keys, typed endpoint rules, and atomic aggregate visibility under injected failure and concurrent writes, and confirm by machine check that Timeline/Search projections cannot originate writes — without turning any projection into a fact.

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
- Room 2.8.4 runtime and the Room compiler (KSP2) are added to `:core:data` through the version catalog. The concrete KSP2 version is chosen and validated at plan approval against AGP 9.3, the AGP 9 built-in Kotlin 2.2.10, and Room 2.8.4 compatibility evidence; KSP2 has an independent version line, so compatibility is never inferred from the Kotlin version number, and KSP1 (which does not support AGP 9) is not an option.
- The Room schema directory is configured so schema JSON is exported and versioned with the repository; P1 creates the first schema, `databaseVersion = 1`.
- `fallbackToDestructiveMigration` is forbidden and must not appear anywhere in the build.
- The seven-module graph and its enforced edges are unchanged; the P0 `checkModuleDependencies` task must keep passing.
- `:core:model` remains a pure Kotlin JVM module with no Android or Room types; Room entities, DAOs, and the database live in `:core:data`.

## 4. Model scope

### 4.1 Tables in database version 1 — minimal field subset

P1 persists a minimal field subset chosen to prove persistence and the invariants, not a full mapping of the [domain model](../../../architecture/domain-model.md). Field sources and later additions are explicit:

- `EntityRef` — `id` (PK, app-generated random UUID string), `entityType`, `createdAt`, `updatedAt`, nullable `deletedAt` (absent while active). Complete per the domain model.
- `Transaction` — `id` (PK, same-ID FK to `EntityRef`), `type`. Subset: occurrence information and descriptive fields are added by M2.
- `Account` — `id` (PK), `name`, `currency`. Subset: `name`/`currency` come from the [MVP requirements](../../../product/mvp-requirements.md) (an account needs a user-visible name and currency) and are not domain-diagram fields; identity-only, serving as the foreign-key target required by `AccountEntry`. No balance, lifecycle, or opening history (M2); the balance is derived, never a stored field.
- `AccountEntry` — `id` (PK), `transactionId` (FK), `accountId` (FK), `amountMinor` (signed integer minor units).
- `Note` — `id` (PK, same-ID FK to `EntityRef`). Subset: `body` and confirmed attachments are added by M3.
- `TransactionNoteRelation` — `transactionId` (PK, FK), `noteId` (PK, FK): the typed, plain many-to-many relation from ADR 0002. Complete.

`EntityType` (`TRANSACTION` | `NOTE` | `PROJECT`) and `TransactionType` (`OPENING_BALANCE` | `INCOME` | `EXPENSE` | `TRANSFER`) enums mirror the domain model exactly and live in `:core:model`; the entities in `:core:data` persist them as their string values.

### 4.2 Frozen persistence contract (databaseVersion = 1)

Because version 1 is the origin of the schema, P1 freezes the following contract; any later change is a schema change that requires an explicit migration under section 4.3:

- **Timestamps:** every timestamp is an epoch-millis (UTC) `Long` column; `deletedAt` is the same type and nullable, absent while active.
- **Enum storage and validation:** enum values persist as strings. `EntityRef.entityType` carries a `CHECK` constraint allowing only `'TRANSACTION'` and `'NOTE'` in version 1 — a `PROJECT` entity type cannot be persisted in P1, and the constraint is relaxed by an explicit migration when M4 introduces `Project`. `Transaction.type` carries a `CHECK` constraint over the four approved values.
- **Foreign-key actions:** every foreign key uses `NO ACTION` — no `ON DELETE`/`ON UPDATE CASCADE`. Soft delete and deletion are coordinated explicitly by repository atomic transactions; cascades would mask coordination errors.
- **Indexes:** `AccountEntry(transactionId)`, `AccountEntry(accountId)`; `TransactionNoteRelation` has composite PK `(transactionId, noteId)` whose prefix covers transaction-direction queries, plus an index on `(noteId, transactionId)` for the note-direction queries M5 requires. Later query paths are added by their owning slice with a migration.
- **Cross-table same-ID uniqueness** (`Transaction.id` and `Note.id` must never collide) is not expressible as declarative constraints in SQLite and is enforced by the application-layer XOR validator under section 5.1.

### 4.3 Migration boundary

- P1 creates `databaseVersion = 1` and therefore contains no upgrade migration.
- From P2 onward, any slice that changes the schema must provide the explicit migration and migration tests in that same slice, following the Room baseline and migration rules in [data and storage](../../../architecture/data-and-storage.md) (exported schema JSON, N-1/N-2 fixtures where those versions exist, no destructive fallback).
- P5 owns the migration-recovery snapshot and startup-gate machinery; before P5, schema changes still require explicit migrations but do not introduce that recovery machinery.

### 4.4 Deferred to later slices

Explicitly not part of P1:

- `Project` and its `EntityRef` identity edge (M4). In P1 no `EntityRef` of type `PROJECT` can exist: the database `CHECK` rejects it and the XOR validator rejects it. The XOR mechanism is therefore enforced and tested for the `Transaction` and `Note` subtype edges present here, and extends to `Project` with the same mechanism plus a migration when it arrives.
- `Category`, `CategoryAllocation` (M2), `Tag`, `EntityTag` (M3), `Attachment`, `AttachmentLink` (P3).
- `primaryProjectId` on `Transaction`/`Note`, catalog name fields, `SearchDocument`/`Timeline` projection tables (P4/M5), migration-recovery machinery, generation activation, and WorkManager.
- No `Money` type is introduced; P1 stores `amountMinor` as `Long` (P2 owns the money invariants).

## 5. Invariants and acceptance criteria

### 5.1 XOR identity enforcement mechanism

The same-ID XOR invariant is enforced by three cooperating layers. SQLite foreign keys alone cannot enforce it: an FK constrains only that a child references an existing parent, never that every parent has a child, that discriminator and subtype agree, or that one ID is not shared across subtype tables. P1 therefore does not rely on triggers or deferred constraints — they cannot express the cross-table XOR declaratively, and with the write surface closed (below) the application-layer validator is equivalently effective while keeping the schema simple.

1. **Database layer (declarative).** `EntityRef.id` is the primary key; `Transaction.id` and `Note.id` are primary keys and FKs to `EntityRef.id`, rejecting subtype rows for missing `EntityRef`s and duplicate subtype rows per table; `entityType` and `type` CHECK constraints reject out-of-contract enum values including `PROJECT`; remaining FKs reject orphan children. SQLite's FK rules deliberately allow a parent without a child, so "every `EntityRef` has exactly one subtype" is not guaranteed by this layer alone.
2. **Repository atomic entry points.** The repository is the only public write surface. An `EntityRef` and its subtype row are written in one Room transaction; the XOR validator runs inside that transaction before any write.
3. **XOR invariant validator** (shared component in `:core:data`). It rejects (a) discriminator and subtype mismatch, (b) a missing subtype, (c) an ID already present as the other subtype (cross-table collision), and (d) `entityType = PROJECT` in P1.

DAOs are module-internal and are not part of the public write surface; a test proves that a cross-table XOR violation cannot be reached through the public API.

### 5.2 Acceptance criteria

The slice passes only when all of the following have evidence in `verification.md`:

1. **Persist and reload minimal facts.** Every table from section 4.1 round-trips through a real Room database with foreign keys enabled: write, close/reopen, read back exact field values including enum string values, nullable `deletedAt` absent/present, and relation rows.
2. **Same-ID `EntityRef` XOR identity.** The three layers of section 5.1 behave as specified, with a test for each rejection the layers claim: mismatched discriminator, missing subtype, duplicate subtype row per table, cross-table same-ID collision, and `PROJECT` persistence — each rejected and leaving no partial state.
3. **Foreign keys.** Referential integrity is enforced: a child row referencing a missing parent fails to insert, and a parent with child rows cannot be deleted into an orphaned state.
4. **Typed endpoint rules.** `TransactionNoteRelation` accepts only existing `Transaction` and `Note` endpoints; a relation with a nonexistent or wrongly-typed endpoint is rejected; complete-set replacement is one atomic operation.
5. **Atomic aggregate visibility.** A `Transaction` plus its `AccountEntry` rows plus requested relations — and a `Note` plus its relations — commit as one indivisible Room transaction. For a failure injected before the transaction, between logical child writes, or before commit, only the old complete state is observable; for a failure after the commit has succeeded, during or after commit-response handling, only the new complete state is observable. At no point is a partial aggregate, a one-sided relation, or a source/projection mismatch observable. This matches the partial/racing-persistence expectation in the testing strategy: observers see only the old complete state or the new complete state, never a partial aggregate.
6. **Concurrent writes and relation-set replacement races.** Two concurrent saves of the same aggregate, and a complete-set replacement of one `TransactionNoteRelation` set racing a read, each leave every observer with a complete state — old or new — and never a mixed or partial set.
7. **Projection write ban (machine-checked).** (a) A test asserts against the exported schema JSON that the table set is exactly the six tables of section 4.1 and contains no `Timeline` or `SearchDocument` table. (b) A reflective or compile-time API check proves the public write surface exposes only the owner-aggregate entry points and no method can originate a write for any projection type. Neither criterion may be evidenced by manual code inspection or a runtime behavioral test alone.
8. **Regression.** `./gradlew clean check` passes (module graph, build, lint unchanged), and the app still installs and launches on the reference device.

## 6. Test evidence requirements

- Instrumented Room tests (`androidTest`) run on the reference device with a real Room database, foreign keys enabled, and the same transaction entry points as production, per the testing strategy.
- Failure injection covers the boundaries in criterion 5 for each aggregate entry point; concurrency evidence names the racing operations and the observed complete states; XOR evidence names the rejecting layer for every case; projection evidence names the schema-JSON assertion and the API check commands.
- `verification.md` maps every acceptance criterion and the roadmap P1 exit gate to concrete commands and results, in the testing strategy's `Command`, `Environment`, `Result`, `Remaining risk` format; a build-only result cannot stand in for the required tests.

## 7. Non-goals

P1 explicitly does not:

- Implement the finance transaction matrix, transfer, zero-sum, or balance invariants (P2).
- Implement attachments or content-addressed storage (P3), search or projections (P4), backup/restore or the migration-recovery snapshot and startup-gate machinery (P5), or timeline performance (P6). Explicit migrations for schema changes are a per-slice obligation from P2 onward under section 4.3, not deferred to P5.
- Introduce any catalog table, `Project`, catalog name fields, or lifecycle machinery (M2–M4).
- Implement generation activation, WorkManager, or any background maintenance.
- Add any UI, ViewModel, or navigation; the repository surface exists only to prove atomicity with production entry points.
- Change any core document fact. If implementation exposes a conflict with a core document, the documentation-first protocol applies before any code change.

## 8. Dependency review boundary

Every dependency introduced by P1 (Room runtime/compiler, KSP2 plugin, `room-testing`, and any test helper) is recorded at plan approval per the engineering conventions: version, license, maintenance evidence, compatibility impact, and removal path. The KSP2 selection must show verified compatibility with AGP 9.3, the built-in Kotlin, and Room 2.8.4 rather than a version-number inference. A dependency is rejected when its license is incompatible, its maintenance status is unknown, it requires excessive permissions, it introduces undisclosed network behavior, or no practical removal path exists. AndroidX and Kotlin official libraries are preferred; no ads, analytics, crash-reporting, or closed-source tracking SDK.

## 9. Risks

- The KSP2 / AGP 9.3 / built-in Kotlin 2.2.10 / Room 2.8.4 combination is the most likely P1 failure; it is resolved at plan approval with a real build check, not a version-line inference.
- Schema JSON export and versioning must be configured when the first schema is created; a missing export is a P1 failure, not a cleanup item.
- The frozen version-1 contract in section 4.2 means any later correction to timestamps, enum validation, FK actions, or indexes is a schema migration, so P1 verification must confirm the contract is actually persisted as specified.
- Instrumented tests on the physical reference device are slower than local unit tests; the plan must account for device time and USB stability.
- The reference device runs API 36; Room behavior on the upgraded system is recorded at verification, not rejected — the same rule as P0.

## 10. Lifecycle path

- `draft` (this document): behavior, boundaries, and acceptance are clarified here. No implementation.
- `approved`: the user explicitly accepts this spec; the approval date/context is recorded.
- `in-progress`: the user approves the plan (recording exact tasks, file changes, and verification commands); implementation may begin.
- `completed`: acceptance criteria and the roadmap P1 exit gate have verification evidence; P2 may then start.

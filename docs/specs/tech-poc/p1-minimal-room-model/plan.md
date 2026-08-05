# Tech PoC P1 — Minimal Room Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Status:** `in-progress` — the user explicitly approved this plan (Revision 7) on 2026-08-05 after review of commit `4272261`; the approval is recorded here per the roadmap lifecycle rules. Implementation is authorized per the tasks below.
- **Spec:** [spec.md](spec.md) — `in-progress` (user accepted Revision 2 and ADR 0006 on 2026-08-05 after review of commit `420f688`).

**Goal:** Prove the approved minimal Room 2.8.4 model — `EntityRef`, `Transaction`, `AccountEntry`, `Note`, plus `Account` (FK target) and the typed `TransactionNoteRelation` — with instrumented tests covering persist/reload, three-layer XOR identity enforcement, foreign keys, typed endpoint rules, atomic aggregate visibility under injected failure and concurrent writes, and machine-checked projection write bans. See the [P1 spec](spec.md) and [ADR 0006](../../../architecture/decisions/0006-version-1-persistence-contract.md).

**Architecture:** Room entities, DAOs, database, XOR validator, and the repository live in `:core:data`; `EntityType`/`TransactionType` enums live in `:core:model` (pure Kotlin JVM). **The public surface of `:core:data` is exactly the normative declaration list in the "Public API" section below**: the `RecordRepository` (public factory + `close()`), the application DTOs, the typed `RepositoryResult`, and the `:core:model` enums re-exported through `api(project(":core:model"))`. Room entities, the DAO, the database, and the validator are module-internal, per the [engineering conventions](../../../engineering/conventions.md). The seven-module graph is unchanged.

**Plan revision 3 (2026-08-05):** review corrections — proactive typed classification inside the write transaction replaces the unimplementable SQLite error-code mapping (no exception-message parsing, explicit catch priority, hook-originated faults attributed by a wrapper); `:core:data` declares `api(project(":core:model"))`; the API-surface check is restricted to `:core:data`'s own declarations with the full public signatures pinned normatively in this plan, classes without `@Metadata` failing by default (only the documented Room-generated exclusion allowed), reflection used solely to fetch the `@Metadata` annotation; `beforeTransaction` fires before `withTransaction`; the XOR validator takes candidate-occupancy semantics so legal creation is accepted; lifecycle entry/exit tasks added.

**Plan revision 4 (2026-08-05):** review corrections — typed prechecks moved **inside** `withTransaction` so concurrent outcomes are stable; cross-table collision checked before same-ID duplicate (the other subtype's `EntityRef` would otherwise shadow it); `CancellationException` rethrown before any mapping; read APIs wrapped in `RepositoryResult`; the ABI no-metadata exclusion narrowed to an explicit pinned FQCN set; `RecordRepository` declares an `internal constructor` proven by a negative fixture; the validator's truth table made complete and mutually exclusive; `PersistReloadTest` uses internal read-only DAO snapshots for the physical tables; lifecycle entry syncs every fact source; verification record environment fields completed.

**Plan revision 5 (2026-08-05):** review corrections — metadata deserialization fixed to the official `KotlinClassMetadata.readStrict` entry; the Room-generated exclusion set pinned to exactly two FQCNs with one consumer fixture each; `open()` documented and tested as performing no fallible storage operation (lazy open; first open happens inside typed methods); cross-table collision enforcement routed **through the XOR validator** (candidate occupancy computed first, validator first, then same-type duplicate) so the repository path exercises the spec-5.1/ADR-0006 authority; Task 1's Files block lists every fact source; the unreachable duplicate-relation-pair assertion removed.

**Plan revision 6 (2026-08-05):** review corrections — the public factory becomes `open(context): RepositoryResult<RecordRepository>`: `RoomDatabase.Builder.build()` can throw immediately on configuration errors, so construction-stage exceptions are mapped to typed outcomes (`CancellationException` rethrown; configuration/programming errors → `Unexpected`) instead of leaking; the file-absence assertion is removed (it proved nothing about exception safety and bound the check to Room implementation details); `PersistReloadTest` fixes the round-trip on one `openForTest(context, dbName)` instance — write through public methods → `close()` → reopen with the **same `dbName`** → public read views plus internal read-only physical snapshot — giving isolation and production write/transaction logic together.

**Plan revision 7 (2026-08-05):** review corrections — the public `open()` delegates to an internal, injectable `openWithBuilder(context, builder)` unified mapping function so the typed `open()` boundary is runtime-verified: `PersistReloadTest` covers `Success` + `close()`, builder `IllegalArgumentException` → `Unexpected`, and builder `CancellationException` rethrown as-is; a positive consumer fixture unpacks `Success(repository)` and calls a method plus `close()`; the `Success` outcome is reworded to general operation-success semantics (durable commit only for writes); stale Task-8/file-map references synchronized.

**Tech Stack:** Gradle 9.5.0, AGP 9.3.0 (built-in Kotlin 2.2.10), KSP2 2.3.9, Room 2.8.4 (runtime + `androidx.room` Gradle plugin + KSP2 compiler), `org.jetbrains.kotlin:kotlin-compiler-embeddable` 2.2.10 and `org.jetbrains.kotlin:kotlin-metadata-jvm` 2.2.10 (dev/test-time only), androidx.test runner/ext-junit, JUnit 4.13.2. `minSdk 34` / `targetSdk 34` / `compileSdk 36`.

**Reference environment (frozen):** Xiaomi 14 Pro (physical, USB debugging), Android 14 / API 34 out-of-box baseline. The actual system build is recorded in `verification.md` at verification time (currently API 36 per P0; recorded, not rejected).

**Namespaces:** `:core:model` → `com.paherdin.record.model`; `:core:data` → `com.paherdin.record.core.data` (Room package `com.paherdin.record.core.data.room`, public API in `com.paherdin.record.core.data`).

**Pre-approval dependency validation:** the real-build evidence for KSP 2.3.9 + AGP 9.3 + built-in Kotlin 2.2.10 + Room 2.8.4, including the schema-export finding (the `ksp` arg does not export under KSP2; the `androidx.room` Gradle plugin does) and the deterministic artifact SHA-256, is recorded in [verification.md](verification.md). Version-selection basis: KSP 2.3.1 added AGP 9.0 and built-in-Kotlin support (google/ksp release notes, issue #2674); 2.3.9 is the current release line validated by that build. **If any pinned version fails during execution, this plan is revised and re-approved first; versions are never switched mid-execution.**

## Dependency review record

Every dependency below is recorded per the engineering conventions (version, license, maintenance, compatibility, removal path) before it enters the version catalog or build.

| Dependency | Version | License | Maintenance | Compatibility impact | Removal path |
|---|---|---|---|---|---|
| KSP2 plugin (`com.google.devtools.ksp`) | 2.3.9 | Apache-2.0 | Google, active | AGP 9.0 + built-in Kotlin supported since 2.3.1; validated by the pre-approval build in [verification.md](verification.md) (Rounds 1–4). KSP1 is not an option (no AGP 9 support). | Remove plugin and `ksp(...)` compiler; `:core:data` keeps no Room code. |
| `androidx.room:room-runtime` | 2.8.4 | Apache-2.0 | Google, active | Already in catalog (P0); now referenced by `:core:data`. Since Room 2.7 it includes the APIs formerly in `room-ktx` (e.g. `withTransaction`); `room-ktx` is not added (empty artifact). | Remove the module dependency; catalog entry is maintenance. |
| `androidx.room` Gradle plugin | 2.8.4 | Apache-2.0 | Google, active | Sets Room processor options including schema export; required because the `ksp` arg route does not export under KSP2 (verification.md Round 1). | Remove the plugin; the committed schema JSON remains; future export needs alternative tooling and a plan revision. |
| `androidx.room:room-compiler` (KSP2) | 2.8.4 | Apache-2.0 | Google, active | KSP2 supported since Room 2.7; validated by the pre-approval build. | Remove `ksp(...)` entry and plugin. |
| `org.jetbrains.kotlin:kotlin-compiler-embeddable` | 2.2.10 | Apache-2.0 | JetBrains, active | Dev-time only; backs the `apiBoundaryCheck` consumer compilation (Task 9). Same version as the built-in KGP. Never part of app/runtime dependencies. | Remove the detached configuration and the `apiBoundaryCheck` task. |
| `org.jetbrains.kotlin:kotlin-metadata-jvm` | 2.2.10 | Apache-2.0 | JetBrains, active | Test-time only; the standard Kotlin metadata reader (the foundation BCV itself uses). Same version as the built-in KGP, so it reads exactly the metadata version the compiler writes. It does not read classfiles itself: the test obtains the `@Metadata` annotation via reflection — **annotation fetch only** — and deserializes it with the official documented entry `KotlinClassMetadata.readStrict(metadata)`, reading `kmClass`/`kmPackage` per the returned metadata kind; all visibility/signature decisions come from that deserialized metadata. | Remove the test dependency and `ApiSurfaceTest`; the allowlist file remains as documentation. |
| `androidx.test:runner` | 1.6.2 | Apache-2.0 | Google, active | Instrumented test runner on the reference device. | Remove; run tests another way. |
| `androidx.test.ext:junit` | 1.2.1 | Apache-2.0 | Google, active | JUnit4 rules for instrumented tests. | Remove. |
| `junit:junit` | 4.13.2 | EPL-1.0 | JUnit team, stable | JVM unit tests (`:core:data` test source set) for the schema contract, validator, and API surface. | Remove. |

No dependency introduces network permissions, analytics, crash reporting, or closed-source tracking. The app manifest is unchanged (no `INTERNET`).

## Public API of `:core:data` (the only write surface)

### Normative declaration list

The following is the complete public surface of `:core:data`, pinned here so that the committed `api/expected-surface.txt` allowlist is derived from this list, not decided at implementation time. The dump in `ApiSurfaceTest` (Task 9) normalizes declarations to signature lines; data classes contribute only their mechanical synthetic members (`componentN`/`copy`/`equals`/`hashCode`/`toString`), which follow from the primary constructor and are not design decisions.

```kotlin
// package com.paherdin.record.core.data

class RecordRepository internal constructor() : Closeable {
    companion object { fun open(context: Context): RepositoryResult<RecordRepository> }
    override fun close(): Unit
    suspend fun createAccount(account: NewAccount): RepositoryResult<Unit>
    suspend fun saveTransactionAggregate(tx: NewTransaction): RepositoryResult<Unit>
    suspend fun saveNoteAggregate(note: NewNote): RepositoryResult<Unit>
    suspend fun replaceTransactionNoteRelations(transactionId: String, noteIds: Set<String>): RepositoryResult<Unit>
    suspend fun getTransactionView(id: String): RepositoryResult<TransactionView?>
    suspend fun getNoteView(id: String): RepositoryResult<NoteView?>
}

data class NewAccount(val id: String, val name: String, val currency: String)
data class NewAccountEntry(val id: String, val accountId: String, val amountMinor: Long)
data class NewTransaction(
    val id: String, val type: TransactionType, val createdAt: Long, val updatedAt: Long,
    val deletedAt: Long?, val entries: List<NewAccountEntry>, val relationNoteIds: Set<String>,
)
data class NewNote(
    val id: String, val createdAt: Long, val updatedAt: Long,
    val deletedAt: Long?, val relationTransactionIds: Set<String>,
)
data class TransactionView(
    val id: String, val type: TransactionType, val createdAt: Long, val updatedAt: Long,
    val deletedAt: Long?, val entries: List<NewAccountEntry>, val relationNoteIds: Set<String>,
)
data class NoteView(
    val id: String, val createdAt: Long, val updatedAt: Long,
    val deletedAt: Long?, val relationTransactionIds: Set<String>,
)

sealed interface RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>
    sealed interface Failure : RepositoryResult<Nothing> {
        data class ValidationRejected(val violation: XorViolation) : Failure
        data object DuplicateIdentity : Failure
        data object EndpointRejected : Failure
        data object StorageExhausted : Failure
        data object TransactionFailed : Failure
        data object CommittedButResponseFailed : Failure
        data object Unexpected : Failure
    }
}

enum class XorViolation { DISCRIMINATOR_MISMATCH, MISSING_SUBTYPE, PROJECT_NOT_SUPPORTED, CROSS_TABLE_ID_COLLISION }
```

Semantics:

- `:core:model` enums (`EntityType`, `TransactionType`) appear in these signatures by qualified name; `:core:data` declares **`api(project(":core:model"))`** so external consumers can resolve them. `:core:model`'s own surface is exactly the two enums (compiler-public by definition).
- `open(context)` returns `RepositoryResult<RecordRepository>`: `RoomDatabase.Builder.build()` can throw immediately on configuration errors, so construction-stage exceptions are mapped at the boundary (`CancellationException` rethrown; configuration/programming errors → `Unexpected`); the database file is created lazily by Room, and storage failures belong to the typed read/write methods.
- The DTOs carry no discriminator field: `entityType` is implied by the typed DTO (`NewTransaction` is always `TRANSACTION`, `NewNote` always `NOTE`), so the public API cannot express a mismatched discriminator, a missing subtype, or a `PROJECT` write.
- `RepositoryResult` outcome semantics per the engineering conventions' stable-error taxonomy:

| Outcome | Meaning and caller behavior |
|---|---|
| `Success` | The operation completed. For a write operation, `Success` specifically means the durable commit completed. |
| `ValidationRejected(violation)` | Domain rejection carrying the specific invariant (`XorViolation`); nothing committed; do not auto-retry. |
| `DuplicateIdentity` | The same-type ID already exists; nothing committed; caller may rename/replace explicitly. |
| `EndpointRejected` | A referenced endpoint is missing or wrongly typed; nothing committed. |
| `StorageExhausted` | Storage full; nothing committed; require remediation, do not loop retries. |
| `TransactionFailed` | The transaction rolled back; **nothing committed**; caller may safely retry. |
| `CommittedButResponseFailed` | **The data IS durable**; the failure occurred after the commit; caller must NOT retry (would duplicate). |
| `Unexpected` | Unclassified failure (e.g. programming error); safest state preserved. |

Read outcomes: `Success(null)` means the record does not exist; a read failure is a typed `Failure` — `CancellationException` is always rethrown, `SQLiteFullException` → `StorageExhausted`, any other exception → `Unexpected`. Raw Room/SQLite exceptions never cross.

### Boundary mapping and catch priority

The write flow is: `hook.beforeTransaction()` → one `withTransaction { occupancy queries; candidate-occupancy validation; same-type duplicate check; endpoint checks; child writes; hook.beforeCommit() }` → `hook.afterCommitResponse()`. **The prechecks run inside the `withTransaction`**, so Room's write serialization makes their outcomes stable under concurrency: two concurrent same-ID saves yield exactly one `Success` and one `DuplicateIdentity`, and a raw `SQLiteConstraintException` never escapes. No `SQLiteConstraintException` error-code or message parsing is used (the framework exposes no reliable error-code accessor, and parsing messages violates the provider-specific-message ban). Order inside the transaction:

1. query both-subtype occupancy for the ID (a row exists in `Transaction` / in `Note`);
2. **the XOR validator runs first on the write's candidate occupancy** (below) — a cross-table collision is `(true, true)` and returns `ValidationRejected(CROSS_TABLE_ID_COLLISION)` naturally, making the validator the enforcing authority for cross-table uniqueness per spec 5.1 and ADR 0006; `PROJECT` and mismatch rows likewise fail here;
3. only after the validator passes, a same-subtype row or its `EntityRef` already exists → `DuplicateIdentity`;
4. every referenced endpoint exists with the correct type (relation `transactionId` is a `Transaction`, `noteId` is a `Note`, entry `accountId` is an `Account`) → `EndpointRejected`;
5. ordered child writes follow.

Catch priority (specific first, generic last; structured concurrency is never violated; `StorageExhausted` and programming errors are never swallowed by a generic transaction-failure catch):

0. `CancellationException` → **rethrow** — it is never converted to an outcome;
1. the validator's internal `XorViolationException` → `ValidationRejected(violation)`;
2. `SQLiteFullException` → `StorageExhausted`;
3. any other `SQLiteException` (including unclassified `SQLiteConstraintException`) → `TransactionFailed` — the kind is never guessed;
4. exceptions raised from hook invocations (attributed by the repository's wrapper as simulated faults): inside the transaction → `TransactionFailed`; from `afterCommitResponse` → `CommittedButResponseFailed`;
5. any other exception (programming errors) → `Unexpected`.

Raw exceptions or `Throwable.message` never cross the boundary.

### XOR validator semantics (candidate occupancy)

`XorInvariantValidator.validate(entityType, candidateTransaction, candidateNote)` is pure and its parameters mean **the occupancy this write will produce**: `candidateTransaction = (this request supplies a Transaction row) || (a Transaction row already exists)`, `candidateNote = (this request supplies a Note row) || (a Note row already exists)`. The repository computes the flags from its proactive same-ID queries and calls the validator inside the transaction. The truth table is complete and mutually exclusive:

| `entityType` | `candidateTransaction` | `candidateNote` | Result |
|---|---|---|---|
| `TRANSACTION` | true | false | valid |
| `NOTE` | false | true | valid |
| `TRANSACTION` | false | true | `DISCRIMINATOR_MISMATCH` |
| `NOTE` | true | false | `DISCRIMINATOR_MISMATCH` |
| `TRANSACTION`/`NOTE` | false | false | `MISSING_SUBTYPE` |
| `TRANSACTION`/`NOTE` | true | true | `CROSS_TABLE_ID_COLLISION` |
| `PROJECT` | any | any | `PROJECT_NOT_SUPPORTED` |

The invalid rows are unreachable through the typed public DTOs (a `NewTransaction` always supplies `candidateTransaction = true`); they are proven by the validator-directed unit tests and the consumer-compile fixtures.

Internal-only: the six `@Entity` classes, `RecordDao`, `RecordDatabase`, `XorInvariantValidator`, `TransactionFaultHook`. Tests may read raw state through internal DAO queries for verification and may write directly to the database **only** for the database-layer rejection evidence cases (spec 5.1); those cases prove the schema's own constraints and never construct valid application state, so the validator remains the single path for every successful write.

## File map

| Path | Responsibility |
|---|---|
| `core/model/src/main/java/com/paherdin/record/model/EntityType.kt` | `EntityType` enum (`TRANSACTION`/`NOTE`/`PROJECT`), mirror of the domain model |
| `core/model/src/main/java/com/paherdin/record/model/TransactionType.kt` | `TransactionType` enum (`OPENING_BALANCE`/`INCOME`/`EXPENSE`/`TRANSFER`) |
| `core/data/build.gradle.kts` | KSP2 2.3.9 + `androidx.room` plugin + Room deps + `api(project(":core:model"))`, `room { schemaDirectory("$projectDir/schemas") }`, test runner, JVM/instrumented test deps (incl. `kotlin-metadata-jvm` testImplementation), `apiBoundaryCheck` task wired into `check`, detached dev-only compiler configuration |
| `core/data/src/main/java/com/paherdin/record/core/data/room/EntityRef.kt` | `internal` `@Entity` per 4.1/4.2 of the spec (PK, enum as string, epoch-millis `Long` timestamps, nullable `deletedAt`) |
| `core/data/src/main/java/com/paherdin/record/core/data/room/Transaction.kt` | `internal` `@Entity` same-ID PK/FK to `EntityRef`, `type` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/Account.kt` | `internal` `@Entity` identity-only (`name`, `currency`) |
| `core/data/src/main/java/com/paherdin/record/core/data/room/AccountEntry.kt` | `internal` `@Entity` FKs to `Transaction`/`Account` with explicit `indices = [Index("transactionId"), Index("accountId")]`, `amountMinor` `Long` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/Note.kt` | `internal` `@Entity` same-ID PK/FK to `EntityRef` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/TransactionNoteRelation.kt` | `internal` `@Entity` composite PK `(transactionId, noteId)`, both FKs `NO ACTION`, index `(noteId, transactionId)` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDao.kt` | `internal` DAO interface: inserts, gets, relation set replacement, transaction-scoped queries used by the repository and tests |
| `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDatabase.kt` | `internal` `@Database(version = 1, exportSchema = true)`; FK enforcement on open |
| `core/data/src/main/java/com/paherdin/record/core/data/Model.kt` | Public DTOs per the normative list: `NewAccount`, `NewAccountEntry`, `NewTransaction`, `NewNote`, `TransactionView`, `NoteView` |
| `core/data/src/main/java/com/paherdin/record/core/data/RepositoryResult.kt` | Public sealed outcomes + `XorViolation` per the normative list |
| `core/data/src/main/java/com/paherdin/record/core/data/XorInvariantValidator.kt` | `internal` pure validator with candidate-occupancy semantics (above); throws internal `XorViolationException` |
| `core/data/src/main/java/com/paherdin/record/core/data/TransactionFaultHook.kt` | `internal` hook: `beforeTransaction()` (before `withTransaction`), `afterChildWrite(childIndex)`, `beforeCommit()`, `afterCommitResponse()`; no-op in production, tests throw at chosen stages/ordinals |
| `core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt` | Public `Closeable` `RecordRepository` per the normative list: proactive typed checks, candidate-occupancy validation, hook attribution, typed results; internal test factory |
| `core/data/schemas/com.paherdin.record.core.data.room.RecordDatabase/1.json` | Exported Room schema JSON — **committed**; verification artifact for ADR 0006 and the projection table-set assertion |
| `core/data/api/expected-surface.txt` | **Committed** allowlist derived one-to-one from the normative declaration list (generated by the dump at Task 9, reviewed against this plan) |
| `core/data/apiGuard/positive/*.kt` | **Committed** consumer-compilation fixtures that MUST compile: the documented public surface (factory, methods, `close()`, DTOs, enums, `RepositoryResult`) |
| `core/data/apiGuard/negative/*.kt` | **Committed** consumer-compilation fixtures, one concern per file, that MUST FAIL to compile: references to internal types (`RecordDao`, `RecordDatabase`, `XorInvariantValidator`, the six entity classes, each pinned generated class in its own fixture, a direct `RecordRepository(...)` construction attempt) and to hypothetical APIs the public surface must not have (an `EntityRef`-only write, a `PROJECT`-bearing write, projection write entries) |
| `core/data/src/test/java/com/paherdin/record/core/data/SchemaContractTest.kt` | JVM test: committed `1.json` table/column/index/FK set equals spec 4.1+4.2 exactly (incl. no projection tables) |
| `core/data/src/test/java/com/paherdin/record/core/data/XorInvariantValidatorTest.kt` | JVM tests of the pure validator: every row of the complete truth table |
| `core/data/src/test/java/com/paherdin/record/core/data/ApiSurfaceTest.kt` | JVM test: full public-signature allowlist of **`:core:data`'s own declarations only** (see Task 9); the only no-metadata exclusion is the explicit pinned Room-generated FQCN set, never a wildcard |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/PersistReloadTest.kt` | Criterion 1: same-`dbName` `openForTest` → public methods → `close()`/reopen → public views + internal read-only snapshot; plus the typed `open()` boundary mapping cases |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/XorIdentityTest.kt` | Criterion 2: database-layer rejection evidence (PK/FK/missing parent/same-table duplicates) + repository cross-table collision → typed rejection |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/ForeignKeyTest.kt` | Criterion 3 |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/TypedEndpointTest.kt` | Criterion 4 |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AtomicVisibilityTest.kt` | Criterion 5: ordinal fault injection at all four boundaries with latch-paused writers and external observers, per the state matrix |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/ConcurrencyTest.kt` | Criterion 6: concurrent saves + relation-set replacement races with full-state comparison and defined stable outcomes |
| `verification.md` | Created 2026-08-05 with the pre-approval validation record; slice-state line synced at plan approval (Task 1); completed at Task 11 with the P1 execution evidence |

## Module dependency and build notes

- `:core:data` gains **one** new project-module edge: `api(project(":core:model"))` (allowed by the graph); `checkModuleDependencies` must keep passing.
- Room schema export: `androidx.room` Gradle plugin with `room { schemaDirectory("$projectDir/schemas") }`; `exportSchema = true`. The `ksp` `arg("room.schemaLocation", ...)` route is **not** used — the pre-approval build proved it does not export under KSP2 (verification.md Round 1).
- `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`.
- No `fallbackToDestructiveMigration`, no `room-ktx` (merged into `room-runtime` since Room 2.7).

---

### Task 1: Lifecycle — record plan approval, enter `in-progress`

**Files:**
- Modify: `plan.md` (metadata), `spec.md` (status), `AGENTS.md` (current-active-spec line), `docs/index.md` (current-active-spec line), `docs/roadmap.md` (Current position), `docs/specs/tech-poc/p1-minimal-room-model/verification.md` (slice-state line)

- [ ] **Step 1: Record the plan approval**

The user's explicit approval of this plan (date/context from the approval message) is recorded in `plan.md` metadata, and the slice transitions to `in-progress` across **every fact source**: `spec.md` status → `in-progress` with the approval date/context, the current-active-spec lines in `AGENTS.md` and `docs/index.md`, the roadmap `Current position` line, and `verification.md`'s slice-state line. Commit as a lifecycle commit (`docs: approve P1 plan, P1 in-progress`) **before any implementation commit**. Implementation is forbidden until this step is done.

### Task 2: Verify toolchain and device prerequisites

**Files:** none (read-only checks)

- [ ] **Step 1: Confirm JDK, Gradle wrapper, and the reference device**

Run:

```powershell
java -version
.\gradlew.bat --version
adb devices -l
```

Expected: JDK 17+, Gradle 9.5.0, and the Xiaomi 14 Pro visible with USB debugging.

### Task 3: `:core:model` domain enums

**Files:**
- Create: `core/model/src/main/java/com/paherdin/record/model/EntityType.kt`, `TransactionType.kt`

- [ ] **Step 1: Add the two enums**

`EntityType` (`TRANSACTION`, `NOTE`, `PROJECT`) and `TransactionType` (`OPENING_BALANCE`, `INCOME`, `EXPENSE`, `TRANSFER`) mirror the domain model exactly; no extra members, no custom serialization. Persisted form is `name` (Room default).

- [ ] **Step 2: Verify the module still builds**

Run:

```powershell
.\gradlew.bat :core:model:build
```

Expected: `BUILD SUCCESSFUL`.

### Task 4: `:core:data` build setup — pinned versions, already validated

**Files:**
- Modify: `gradle/libs.versions.toml`, root `build.gradle.kts`, `core/data/build.gradle.kts`

- [ ] **Step 1: Add versions to the catalog**

```toml
[versions]
ksp = "2.3.9"
androidxTestRunner = "1.6.2"
androidxTestJunit = "1.2.1"
junit = "4.13.2"

[libraries]
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
kotlin-compiler-embeddable = { group = "org.jetbrains.kotlin", name = "kotlin-compiler-embeddable", version.ref = "kotlin" }
kotlin-metadata-jvm = { group = "org.jetbrains.kotlin", name = "kotlin-metadata-jvm", version.ref = "kotlin" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestJunit" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
androidx-room = { id = "androidx.room", version.ref = "room" }
```

- [ ] **Step 2: Apply plugins**

Root `build.gradle.kts`: add `alias(libs.plugins.ksp) apply false` and `alias(libs.plugins.androidx.room) apply false`. In `core/data/build.gradle.kts`: `alias(libs.plugins.ksp)` and `alias(libs.plugins.androidx.room)`.

- [ ] **Step 3: `:core:data` dependencies, schema export, test runner**

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.metadata.jvm)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
```

- [ ] **Step 4: Confirm the pre-approved combination builds**

Run:

```powershell
.\gradlew.bat :core:data:assembleDebug
```

Expected: `BUILD SUCCESSFUL` with the pinned KSP 2.3.9 + `androidx.room` plugin (the same combination the pre-approval build in `verification.md` proved). Any failure here revises this plan first; no version is switched mid-execution.

### Task 5: Entities, database, and schema export

**Files:**
- Create: the six entity files, `RecordDatabase.kt`
- Generated (committed): `core/data/schemas/com.paherdin.record.core.data.room.RecordDatabase/1.json`

- [ ] **Step 1: Entities per spec 4.1/4.2**

`EntityRef` (`id` PK, `entityType: EntityType`, `createdAt: Long`, `updatedAt: Long`, `deletedAt: Long?`); `Transaction` (`id` PK/FK → `EntityRef`, `type: TransactionType`); `Account` (`id` PK, `name`, `currency`); `AccountEntry` (`id` PK, `transactionId` FK `NO ACTION`, `accountId` FK `NO ACTION`, `amountMinor: Long`, `indices = [Index("transactionId"), Index("accountId")]`); `Note` (`id` PK/FK → `EntityRef`); `TransactionNoteRelation` (composite PK `(transactionId, noteId)`, both FKs `NO ACTION`, `indices = [Index("noteId", "transactionId")]`). All six classes are `internal`. No `Cascade`, no `SET NULL`, no extra fields.

- [ ] **Step 2: `RecordDatabase`**

`@Database(entities = [...six...], version = 1, exportSchema = true)`; `internal`; DAO accessor for the internal `RecordDao`; FK enforcement asserted (`PRAGMA foreign_keys`) in tests.

- [ ] **Step 3: Build and commit the exported schema JSON**

Run:

```powershell
.\gradlew.bat :core:data:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, `copyRoomSchemas` runs, and `schemas/com.paherdin.record.core.data.room.RecordDatabase/1.json` exists.

### Task 6: DTOs, typed results, DAO, validator, fault hook, repository

**Files:**
- Create: `Model.kt`, `RepositoryResult.kt`, `RecordDao.kt`, `XorInvariantValidator.kt`, `TransactionFaultHook.kt`, `RecordRepository.kt`

- [ ] **Step 1: Public DTOs and typed results**

Exactly the normative declaration list (no extra members, no discriminator fields). The repository is the only place that maps failures to outcomes, per the boundary mapping and catch priority above.

- [ ] **Step 2: `RecordDao` (internal)**

Insert-by-id and get-by-id for each entity, `getEntityRef`, `getTransactionWithEntries`, `getRelationsForTransaction`, `getRelationsForNote`, and `replaceTransactionNoteRelations(transactionId, noteIds)` (delete old set + insert new set in the caller's transaction). Existence queries used by the repository's proactive checks.

- [ ] **Step 3: `XorInvariantValidator` (internal, pure)**

Candidate-occupancy semantics per the "XOR validator semantics" section; throws the internal `XorViolationException(violation)`; no database access.

- [ ] **Step 4: `TransactionFaultHook` (internal)**

No-op interface: `beforeTransaction()` — fired **before** `db.withTransaction` is called — then `afterChildWrite(childIndex: Int)` (after each logical child write, in order), `beforeCommit()` (last statement inside the transaction), `afterCommitResponse()` (after `withTransaction` returns; the commit is already durable). Production default is the no-op; tests inject throwing implementations at chosen stages/ordinals.

- [ ] **Step 5: `RecordRepository` (public, `Closeable`)**

Exactly the normative surface, with an `internal constructor` (an external consumer cannot construct it directly — proven by a negative fixture). The public `open(context)` delegates to an **internal unified mapping function** `openWithBuilder(context, builder: (Context) -> RecordDatabase)` (builder injectable for tests) that applies the boundary mapping: `CancellationException` rethrown; any other construction-stage exception (Room builder configuration errors) → `Unexpected`; success → `Success(repository)`. The production builder is `Room.databaseBuilder` with the internal default-name constant; the database file is created lazily by Room, and storage failures are owned by the typed read/write methods. `close()` closes the database; internal test factory `openForTest(context, dbName)` reuses the same unified function with a test builder. Each write: `hook.beforeTransaction()` → one `withTransaction` containing the occupancy queries, candidate-occupancy validation, same-type duplicate check, endpoint checks, ordered child writes, and `beforeCommit` last → `afterCommitResponse()` outside the transaction. Hook invocations are wrapped so hook-originated exceptions are attributed as simulated faults (boundary mapping clause 4). Reads return `RepositoryResult` with `Success(null)` for a missing record. **The repository is the only public write surface.**

### Task 7: Instrumented tests — persist/reload, XOR, FK, typed endpoints

**Files:**
- Create: `PersistReloadTest.kt`, `XorIdentityTest.kt`, `ForeignKeyTest.kt`, `TypedEndpointTest.kt` under `:core:data` androidTest
- Create: `XorInvariantValidatorTest.kt` (JVM unit test)

- [ ] **Step 1: `PersistReloadTest` (criterion 1)**

Open one database via `openForTest(context, dbName)`; all successful writes go through the public repository methods only. `close()`; reopen with the **same `dbName`** via `openForTest`; then verify the round trip with public read views — `Success(value)` with exact field values, enum string values, `deletedAt` absent/present, and relation rows; `Success(null)` for a missing record — plus an **internal read-only DAO snapshot** of all six physical tables and `PRAGMA foreign_keys = 1` (the P1 public surface has no `AccountView`, so physical-table verification uses the internal read seam, never writes). The same `dbName` scheme isolates tests while reusing production write, transaction, and read logic.

The same class covers the **typed `open()` boundary mapping** through the injectable builder: (a) public `open(context)` → `Success` and the repository `close()`s normally; (b) a builder lambda throwing `IllegalArgumentException` → `Unexpected`; (c) a builder lambda throwing `CancellationException` → rethrown as-is (assert the call throws).

- [ ] **Step 2: `XorInvariantValidatorTest` (JVM, criterion 2 — validator layer)**

Every row of the complete mutually-exclusive truth table: both valid rows; both `DISCRIMINATOR_MISMATCH` rows; `MISSING_SUBTYPE`; `CROSS_TABLE_ID_COLLISION`; `PROJECT_NOT_SUPPORTED` for all four flag combinations.

- [ ] **Step 3: `XorIdentityTest` (instrumented, criterion 2 — database layer + repository)**

Database layer (direct internal writes only, the allowed bypass for schema-evidence cases): a subtype row for a missing `EntityRef` fails (FK); a duplicate subtype row in the same table fails (PK). **No `PROJECT`-persistence claim is made at the database layer** — ADR 0006 establishes there is no database `CHECK`. Repository layer: create `Note` id X, then `saveTransactionAggregate` with id X → `ValidationRejected(CROSS_TABLE_ID_COLLISION)`; `saveTransactionAggregate` with an existing same-type ID → `DuplicateIdentity`; every case asserts no partial state remains.

- [ ] **Step 4: `ForeignKeyTest` (criterion 3)**

Child rows with missing parents fail to insert; deleting a parent with children fails; `NO ACTION` is confirmed (no cascade observed).

- [ ] **Step 5: `TypedEndpointTest` (criterion 4)**

A relation referencing a nonexistent or wrongly-typed endpoint → `EndpointRejected`; complete-set replacement is atomic and observable only as old-set or new-set. (A duplicate relation pair is not asserted: the public API takes `Set<String>`, so duplicates are removed before the repository, and internal DAO direct writes are reserved for the database-layer rejection evidence in `XorIdentityTest`.)

### Task 8: Instrumented tests — atomic visibility and concurrency

**Files:**
- Create: `AtomicVisibilityTest.kt`, `ConcurrencyTest.kt` under `:core:data` androidTest

- [ ] **Step 1: `AtomicVisibilityTest` (criterion 5)**

For each aggregate entry point **and** for `replaceTransactionNoteRelations` (children = delete old set, insert new set), inject failure at every stage/ordinal of the hook. The expected state matrix is:

| Injection | While the writer is paused at the stage | After the operation completes | Expected result |
|---|---|---|---|
| `beforeTransaction` throws (before `withTransaction` starts) | old complete state | old complete state | `TransactionFailed`, nothing persisted |
| `afterChildWrite(ordinal)` throws | old complete state | old complete state | `TransactionFailed`, nothing persisted |
| `beforeCommit` throws | old complete state | old complete state | `TransactionFailed`, nothing persisted |
| `afterCommitResponse` throws (commit already durable) | new complete state | new complete state | `CommittedButResponseFailed`, data persisted |
| no-fault control group | old complete state | new complete state | `Success` |

**Latch protocol:** the writer pauses on a latch at the target stage; while paused, an **external reader** — a separate coroutine querying through a DAO suspend query on its own Room connection (Room WAL default), never the writer's connection — takes one or more snapshots and asserts each equals the exact complete state the matrix requires (full ID sets and fields, not counts); the latch is released; after the operation completes the reader asserts the final complete state. A paused writer's in-flight writes are never visible to the external reader because the reader is outside the writer's transaction connection. The `afterCommitResponse` case deliberately throws **after** `withTransaction` returns so the commit is already durable and the reader observes the new state even while the writer is paused.

- [ ] **Step 2: `ConcurrencyTest` (criterion 6)**

- Concurrent `replaceTransactionNoteRelations` on the same transaction racing a reader: every reader observation equals one complete relation set (full ID set comparison, never a count-only check, never a mixed set).
- Concurrent `saveTransactionAggregate` with the **same aggregate ID**: Room serializes `withTransaction`; the stable outcome is defined as exactly one `Success` and one `DuplicateIdentity`, with the final database state equal to the successful aggregate's complete state. Raw SQLite/Room exceptions never escape the repository (assert typed results only). Fixed seeds where sequences are generated.

### Task 9: Projection write ban — machine checks

**Files:**
- Create: `SchemaContractTest.kt`, `ApiSurfaceTest.kt` (JVM unit tests), `core/data/api/expected-surface.txt`, `core/data/apiGuard/positive/*.kt`, `core/data/apiGuard/negative/*.kt`
- Modify: `core/data/build.gradle.kts` (`apiBoundaryCheck` task + detached compiler configuration, wired into `check`)

- [ ] **Step 1: `SchemaContractTest` (criterion 7a)**

Read the committed `1.json`; assert the table set is exactly the six spec-4.1 tables (no `SearchDocument`, `Timeline`, or any other table); assert the ADR 0006 contract: timestamps and `amountMinor` integer affinity, enum columns text affinity, all FKs `NO ACTION`, the two `AccountEntry` indexes and the `TransactionNoteRelation` index.

- [ ] **Step 2: `ApiSurfaceTest` — full public-signature allowlist (criterion 7b)**

JVM unit test using `kotlin-metadata-jvm` 2.2.10. **Scope: `:core:data`'s own declarations only** — the test locates the classpath element containing `RecordDatabase.class` (the module's main classes dir, which contains only `:core:data` classes; `:core:model` types appear in signatures by qualified name and are not scanned). Mechanism: enumerate `.class` files under `com/paherdin/record/core/data/**` in that element; load each class and read its `kotlin.Metadata` annotation via reflection — **reflection is used only to fetch the annotation**; all visibility and signature decisions come from the Kotlin metadata deserialized with `kotlin-metadata-jvm`'s documented entry `KotlinClassMetadata.readStrict(metadata)`, reading `kmClass`/`kmPackage` per the returned metadata kind (official Kotlin Metadata JVM API).

- **Classes without `@Metadata` fail the test by default** (unexpected public surface — e.g. Java or generated classes are not silently exempt). The only exclusion is the **pinned set of Room-generated FQCNs**: exactly `com.paherdin.record.core.data.room.RecordDatabase_Impl` and `com.paherdin.record.core.data.room.RecordDao_Impl`. Any other class without `@Metadata` fails; a wildcard like `*_Impl` is never used. Each excluded class is proven unreachable externally by its **own** consumer fixture in `apiBoundaryCheck` (they extend/implement the internal `RecordDatabase`/`RecordDao`).
- Assertions: the six entity classes, `RecordDao`, `RecordDatabase`, and `XorInvariantValidator` are `internal` per the compiled metadata — compile-time visibility, not JVM reflection; the PUBLIC declarations match `api/expected-surface.txt` **exactly**, in both directions — any public declaration not in the allowlist fails (this catches any new public write method regardless of name), and any allowlist entry missing from the code fails; no `SearchDocument`/`Timeline` type and no projection-write method exists.
- The allowlist content is generated by the dump and must correspond one-to-one to the normative declaration list in this plan's "Public API" section (data-class synthetic members excepted as mechanical). The file is committed and reviewed.

- [ ] **Step 3: `apiBoundaryCheck` — independent consumer compilation**

BCV is not used (its `apiDump`/`apiCheck` do not register under AGP built-in Kotlin — Kotlin/binary-compatibility-validator#312). Instead, a Gradle task in `:core:data` that invokes the Kotlin compiler CLI (`org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` via `JavaExec`, classpath from a detached dev-only configuration of `kotlin-compiler-embeddable` 2.2.10) with `-classpath <module debug classes + debugCompileClasspath + android.jar (compileSdk 36)>`, `dependsOn("compileDebugKotlin")`. The fixtures compile **outside the module**: a separate compilation with no friend-path `internal` visibility, so a same-module source set cannot serve as this consumer. **Each fixture file is compiled in its own independent compiler invocation**, so one still-failing fixture can never mask another that unexpectedly started compiling:

- every `apiGuard/positive/*.kt` fixture compiles (exit 0) — the documented public surface (factory, methods, `close()`, DTOs, enums, `RepositoryResult`), including unpacking `Success(repository)` from `open` and calling a method and `close()` on it;
- every `apiGuard/negative/*.kt` fixture fails to compile (nonzero exit and compiler stderr shows the internal-visibility/unresolved-reference diagnostics) — one concern per file: `RecordDao`, `RecordDatabase`, `XorInvariantValidator`, each entity class, a direct `RecordRepository(...)` construction attempt (internal constructor), each pinned generated class in its own fixture (`RecordDatabase_Impl`, `RecordDao_Impl`), an `EntityRef`-only write, a `PROJECT`-bearing write, and projection write entries.

The task is wired into `:core:data:check` via `dependsOn` so the boundary is enforced on every `check` run, durably.

- [ ] **Step 4: Negative verification**

Two directions: (i) temporarily make `RecordDao` public → a negative fixture compiles → `apiBoundaryCheck` FAIL; (ii) temporarily add a public method to `RecordRepository` → `ApiSurfaceTest` FAIL. Revert both; run again; expected PASS. Record both states in `verification.md` (mirrors P0's negative-verification evidence).

### Task 10: Full build and device regression

**Files:** none (verification)

- [ ] **Step 1: Clean full check with all tests**

Run:

```powershell
.\gradlew.bat clean check
```

Expected: `BUILD SUCCESSFUL`; `checkModuleDependencies: PASS`; lint 0 errors; `:core:data:apiBoundaryCheck` PASS; JVM unit tests (schema contract, validator, API surface) PASS.

- [ ] **Step 2: Instrumented suite on the reference device**

Run:

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest
```

Expected: all six instrumented test classes PASS on the Xiaomi 14 Pro. Record the device build id:

```powershell
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release
adb shell getprop ro.build.display.id
```

- [ ] **Step 3: P0 launch regression**

Run:

```powershell
.\gradlew.bat :app:installDebug
adb shell am start -n com.paherdin.record/.MainActivity
Start-Sleep -Seconds 2
adb shell dumpsys activity activities | Select-String "com.paherdin.record"
```

Expected: `topResumedActivity` is `com.paherdin.record/.MainActivity`.

### Task 11: Complete the verification record and commit

**Files:**
- Complete: `docs/specs/tech-poc/p1-minimal-room-model/verification.md` (created 2026-08-05 with the pre-approval validation record)

- [ ] **Step 1: Complete `verification.md`**

Append the P1 execution record below the pre-approval section: every actual command, environment, result, deviation, and remaining risk in the testing strategy's `Command`/`Environment`/`Result`/`Remaining risk` format, mapping every acceptance criterion (spec section 5.2, items 1–8) and the roadmap P1 exit gate to concrete commands and results; include the negative-verification evidence (Task 9 Step 4), the committed schema JSON and allowlist hashes, and remaining risk. **All actual commands, environments, results, and deviations live only in `verification.md`** per the roadmap's document ownership; nothing is appended to this plan at completion.

- [ ] **Step 2: Confirm the working tree is clean except intended files**

Run:

```powershell
git status --short
git diff --check
```

- [ ] **Step 3: Commit the slice in logical units**

The approval-sync records (spec status, roadmap, AGENTS.md, docs/index.md, ADR 0006 acceptance) were already committed separately (`docs: record P1 spec and ADR 0006 approval (2026-08-05)`); the plan and the pre-approval verification record are already committed; the plan-approval lifecycle commit happened in Task 1. Implementation commits:

```powershell
git add gradle/libs.versions.toml build.gradle.kts core/data core/model
git commit -m "feat(poc): P1 Room schema v1, typed repository surface, and machine checks"

git add docs/specs/tech-poc/p1-minimal-room-model/verification.md
git commit -m "docs: record P1 minimal Room model verification"
```

Expected: clean local commits; working tree clean.

### Task 12: Lifecycle — complete the slice

**Files:**
- Modify: `spec.md` (status → `completed`), `roadmap.md` (current position), `docs/index.md`, `AGENTS.md`, `verification.md` (final)

- [ ] **Step 1: Confirm acceptance and sync lifecycle documents**

After the user confirms acceptance: set `spec.md` status to `completed` with the completion date/context; update `roadmap.md` current position to P1 `completed`; sync the current-active-spec lines in `docs/index.md` and `AGENTS.md`; finalize `verification.md` if needed. Commit as a lifecycle commit (`docs: complete Tech PoC P1 and advance roadmap status`).

- [ ] **Step 2: Verify state**

Run:

```powershell
git log --oneline -6
git status --short
```

Expected: approval, plan, implementation, and completion history present; working tree clean. P2 is the next candidate after P1.

## Completion result

The P1 exit gate passes when `verification.md` shows: the six-table `databaseVersion = 1` schema with the ADR 0006 contract persists and reloads through the public API on the reference device; XOR identity (database layer, validator-directed, consumer-compile, and repository paths), foreign keys, typed endpoints, atomic visibility (every stage/ordinal boundary, external-reader evidence, per the state matrix), and concurrency evidence pass with defined stable outcomes; the schema-JSON contract, the full public-signature allowlist, and the independent-consumer `apiBoundaryCheck` machine checks plus negative verification prove projections cannot originate writes and internal types are unreachable from an external consumer; `clean check` and the P0 launch regression pass.

## Open decision tracked for later slices

Per spec section 4.3, the phase ownership of the recoverable-migration-snapshot machinery (schema-changing slice vs. the P5 roadmap gate) is settled through the core documents and an ADR **before the first migration after P1**. P1 contains no migration and is not blocked by this; the decision is registered here so the P2/P3/P4 planning does not start from an unresolved contradiction.

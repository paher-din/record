# Tech PoC P1 — Minimal Room Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the approved minimal Room 2.8.4 model — `EntityRef`, `Transaction`, `AccountEntry`, `Note`, plus `Account` (FK target) and the typed `TransactionNoteRelation` — with instrumented tests covering persist/reload, three-layer XOR identity enforcement, foreign keys, typed endpoint rules, atomic aggregate visibility under injected failure and concurrent writes, and machine-checked projection write bans. See the [P1 spec](spec.md) and [ADR 0006](../../../architecture/decisions/0006-version-1-persistence-contract.md).

**Architecture:** Room entities, DAOs, database, XOR validator, and the repository live in `:core:data`; `EntityType`/`TransactionType` enums live in `:core:model` (pure Kotlin JVM). **The public surface of `:core:data` is exactly: the `RecordRepository` (with its factory), the application DTOs, the typed `RepositoryResult`, and the `:core:model` enums.** Room entities, the DAO, the database, and the validator are module-internal, per the [engineering conventions](../../../engineering/conventions.md) (repository APIs expose domain/application models and typed outcomes, never Room entities). The seven-module graph is unchanged.

**Plan revision 1 (2026-08-05):** review corrections — typed public repository surface (DTOs + `RepositoryResult` + factory) instead of entity leakage; KSP 2.3.9 pinned and validated by a real pre-approval build; BCV 0.17.0 removed and replaced by a decided independent consumer-compilation check (`apiBoundaryCheck`); fault-injection redesigned with ordinal hooks, latch-paused writers, and external observers; concurrency expectations defined; commit/verification tasks corrected; `room-ktx` removed; explicit entity indexes; ADR 0006 link fixed.

**Tech Stack:** Gradle 9.5.0, AGP 9.3.0 (built-in Kotlin 2.2.10), KSP2 2.3.9, Room 2.8.4 (runtime + `androidx.room` Gradle plugin + KSP2 compiler), `org.jetbrains.kotlin:kotlin-compiler-embeddable` 2.2.10 (dev-time only, for the API boundary check), androidx.test runner/ext-junit, JUnit 4.13.2. `minSdk 34` / `targetSdk 34` / `compileSdk 36`.

**Reference environment (frozen):** Xiaomi 14 Pro (physical, USB debugging), Android 14 / API 34 out-of-box baseline. The actual system build is recorded in `verification.md` at verification time (currently API 36 per P0; recorded, not rejected).

**Namespaces:** `:core:model` → `com.paherdin.record.model`; `:core:data` → `com.paherdin.record.core.data` (Room package `com.paherdin.record.core.data.room`, public API in `com.paherdin.record.core.data`).

---

## Pre-approval dependency validation record

Per the spec, the concrete KSP2 version and its real-build compatibility evidence are established before plan approval. The following transient experiment was run in the working tree and fully reverted; it is not P1 implementation.

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:clean :core:data:kspDebugKotlin` (after temporarily applying KSP 2.3.9 + Room 2.8.4 + a probe entity/database to `:core:data`) |
| Environment | Windows 11 Home China 10.0.26200; JDK Temurin 17.0.20+8; Gradle 9.5.0; AGP 9.3.0 built-in Kotlin 2.2.10; KSP 2.3.9; Room 2.8.4; compileSdk 36 |
| Result 1 | PASS — `kspDebugKotlin` ran; `ProbeDatabase_Impl.kt` generated. KSP 2.3.9 is compatible with AGP 9.3 built-in Kotlin 2.2.10 and Room 2.8.4. |
| Result 2 | FAIL-then-fixed — `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` **does not export schema JSON under KSP2**. The official `androidx.room` Gradle plugin (2.8.4) with `room { schemaDirectory("$projectDir/schemas") }` **does**: `copyRoomSchemas` ran and exported `core/data/schemas/<database-class>/1.json` (formatVersion 1, version 1). **The plan therefore uses the `androidx.room` Gradle plugin, not the `ksp` arg.** |
| Working tree | Fully reverted (no probe files, no build-script changes remain); committed history unchanged by this experiment. |

Version-selection basis: KSP 2.3.1 added AGP 9.0 and built-in-Kotlin support (google/ksp release notes, issue #2674); 2.3.2 was a hotfix release; 2.3.9 is the current release line, which the real build above validates against this exact toolchain. **If any pinned version fails during execution, the plan is revised and re-approved first; versions are never switched mid-execution.**

## Dependency review record

Every dependency below is recorded per the engineering conventions (version, license, maintenance, compatibility, removal path) before it enters the version catalog or build.

| Dependency | Version | License | Maintenance | Compatibility impact | Removal path |
|---|---|---|---|---|---|
| KSP2 plugin (`com.google.devtools.ksp`) | 2.3.9 | Apache-2.0 | Google, active | AGP 9.0 + built-in Kotlin supported since 2.3.1; validated by the pre-approval build above against AGP 9.3 / Kotlin 2.2.10 / Room 2.8.4. KSP1 is not an option (no AGP 9 support). | Remove plugin and `ksp(...)` compiler; `:core:data` keeps no Room code. |
| `androidx.room:room-runtime` | 2.8.4 | Apache-2.0 | Google, active | Already in catalog (P0); now referenced by `:core:data`. Since Room 2.7 it includes the APIs formerly in `room-ktx` (e.g. `withTransaction`); `room-ktx` is not added (empty artifact). | Remove the module dependency; catalog entry is maintenance. |
| `androidx.room` Gradle plugin | 2.8.4 | Apache-2.0 | Google, active | Sets Room processor options including schema export; validated by the pre-approval build (Result 2). Without it, schema export under KSP2 does not happen via the `ksp` arg. | Remove the plugin; the committed schema JSON remains; future export needs alternative tooling and a plan revision. |
| `androidx.room:room-compiler` (KSP2) | 2.8.4 | Apache-2.0 | Google, active | KSP2 supported since Room 2.7; validated by the pre-approval build. | Remove `ksp(...)` entry and plugin. |
| `org.jetbrains.kotlin:kotlin-compiler-embeddable` | 2.2.10 | Apache-2.0 | JetBrains, active | Dev-time only; backs the `apiBoundaryCheck` consumer compilation (Task 8). Same version as the built-in KGP. Never part of app/runtime dependencies. | Remove the detached configuration and the `apiBoundaryCheck` task. |
| `androidx.test:runner` | 1.6.2 | Apache-2.0 | Google, active | Instrumented test runner on the reference device. | Remove; run tests another way. |
| `androidx.test.ext:junit` | 1.2.1 | Apache-2.0 | Google, active | JUnit4 rules for instrumented tests. | Remove. |
| `junit:junit` | 4.13.2 | EPL-1.0 | JUnit team, stable | JVM unit tests (`:core:data` test source set) for the schema contract. | Remove. |

No dependency introduces network permissions, analytics, crash reporting, or closed-source tracking. The app manifest is unchanged (no `INTERNET`).

## Public API of `:core:data` (the only write surface)

Per the spec's section 5.1 and the engineering conventions, the public surface is closed and typed:

- `RecordRepository` — public class with `companion object { fun open(context: Context, dbName: String = "record.db"): RecordRepository }`; public methods: `createAccount(NewAccount)`, `saveTransactionAggregate(NewTransaction)`, `saveNoteAggregate(NewNote)`, `replaceTransactionNoteRelations(transactionId, noteIds)`, and read views (`getTransactionView`, `getNoteView`). Internal constructor; internal test factory for a controlled database. **Every behavioral test — production and test alike — calls only these public methods.**
- Application DTOs (public, `:core:data`): `NewAccount(id, name, currency)`, `NewAccountEntry(id, accountId, amountMinor)`, `NewTransaction(id, type, createdAt, updatedAt, deletedAt, entries, relationNoteIds)`, `NewNote(id, createdAt, updatedAt, deletedAt, relationTransactionIds)`, `TransactionView`, `NoteView`. These carry the same fields as the internal entities; the repository maps DTO → entity inside the transaction.
- `RepositoryResult<T>` — public sealed interface: `Success<T>`; `Failure` subclasses `InvalidXor` (validation/domain rejection), `Conflict` (duplicate identity or relation — validation rejection), `StorageFailure` (atomic transaction failed / unexpected failure). Room, SQLite, and validator exceptions are caught at the boundary and mapped to these; raw exceptions never cross the repository, per conventions "Stable application errors". No `Throwable.message` crosses.
- `:core:model` enums `EntityType`/`TransactionType` are referenced by the DTOs.

Internal-only: the six `@Entity` classes, `RecordDao`, `RecordDatabase`, `XorInvariantValidator`, `TransactionFaultHook`. Tests may read raw state through internal DAO queries for verification and may write directly to the database **only** for the database-layer rejection evidence cases (spec 5.1: duplicate subtype row, FK violations, `PROJECT` persistence attempt); those tests never construct valid application state, so the validator remains the single path for every successful write.

## File map

| Path | Responsibility |
|---|---|
| `core/model/src/main/java/com/paherdin/record/model/EntityType.kt` | `EntityType` enum (`TRANSACTION`/`NOTE`/`PROJECT`), mirror of the domain model |
| `core/model/src/main/java/com/paherdin/record/model/TransactionType.kt` | `TransactionType` enum (`OPENING_BALANCE`/`INCOME`/`EXPENSE`/`TRANSFER`) |
| `core/data/build.gradle.kts` | KSP2 2.3.9 + `androidx.room` plugin + Room deps, `room { schemaDirectory("$projectDir/schemas") }`, test runner, JVM/instrumented test deps, `apiBoundaryCheck` task wired into `check`, detached dev-only compiler configuration |
| `core/data/src/main/java/com/paherdin/record/core/data/room/EntityRef.kt` | `internal` `@Entity` per 4.1/4.2 of the spec (PK, enum as string, epoch-millis `Long` timestamps, nullable `deletedAt`) |
| `core/data/src/main/java/com/paherdin/record/core/data/room/Transaction.kt` | `internal` `@Entity` same-ID PK/FK to `EntityRef`, `type` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/Account.kt` | `internal` `@Entity` identity-only (`name`, `currency`) |
| `core/data/src/main/java/com/paherdin/record/core/data/room/AccountEntry.kt` | `internal` `@Entity` FKs to `Transaction`/`Account` with explicit `indices = [Index("transactionId"), Index("accountId")]`, `amountMinor` `Long` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/Note.kt` | `internal` `@Entity` same-ID PK/FK to `EntityRef` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/TransactionNoteRelation.kt` | `internal` `@Entity` composite PK `(transactionId, noteId)`, both FKs `NO ACTION`, index `(noteId, transactionId)` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDao.kt` | `internal` DAO interface: inserts, gets, relation set replacement, transaction-scoped queries used by the repository and tests |
| `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDatabase.kt` | `internal` `@Database(version = 1, exportSchema = true)`; FK enforcement on open |
| `core/data/src/main/java/com/paherdin/record/core/data/Model.kt` | Public DTOs: `NewAccount`, `NewAccountEntry`, `NewTransaction`, `NewNote`, `TransactionView`, `NoteView` |
| `core/data/src/main/java/com/paherdin/record/core/data/RepositoryResult.kt` | Public sealed typed outcomes (`Success`/`InvalidXor`/`Conflict`/`StorageFailure`) |
| `core/data/src/main/java/com/paherdin/record/core/data/XorInvariantValidator.kt` | `internal` validator per spec 5.1: mismatch, missing subtype, cross-table ID collision, `PROJECT` rejection |
| `core/data/src/main/java/com/paherdin/record/core/data/TransactionFaultHook.kt` | `internal` hook: `beforeTransaction()`, `afterChildWrite(childIndex)`, `beforeCommit()`, `afterCommitResponse()`; no-op in production, tests throw at chosen stages/ordinals |
| `core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt` | Public `RecordRepository`: public factory, DTO-mapped atomic entry points, read views, typed results; internal constructor + internal test factory; all writes in one `withTransaction` running the XOR validator |
| `core/data/schemas/com.paherdin.record.core.data.room.RecordDatabase/1.json` | Exported Room schema JSON — **committed**; verification artifact for ADR 0006 and the projection table-set assertion |
| `core/data/apiGuard/positive/*.kt` | **Committed** consumer-compilation fixtures that MUST compile: the full expected public surface of `:core:data` (repository, DTOs, enums, `RepositoryResult`) |
| `core/data/apiGuard/negative/*.kt` | **Committed** consumer-compilation fixtures that MUST FAIL to compile: references to internal types (`RecordDao`, `RecordDatabase`, `XorInvariantValidator`, the six entity classes) and to hypothetical projection write entries on the repository |
| `core/data/src/test/java/com/paherdin/record/core/data/SchemaContractTest.kt` | JVM test: committed `1.json` table/column/index/FK set equals spec 4.1+4.2 exactly (incl. no projection tables) |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/PersistReloadTest.kt` | Criterion 1: write → close/reopen → read back exact values |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/XorIdentityTest.kt` | Criterion 2: per-layer rejection evidence |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/ForeignKeyTest.kt` | Criterion 3 |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/TypedEndpointTest.kt` | Criterion 4 |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AtomicVisibilityTest.kt` | Criterion 5: ordinal fault injection at all four boundaries with latch-paused writers and external observers |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/ConcurrencyTest.kt` | Criterion 6: concurrent saves + relation-set replacement races with full-state comparison |
| `verification.md` | Created at completion at `docs/specs/tech-poc/p1-minimal-room-model/verification.md`; records commands, environment, results, remaining risk, and maps every criterion to the roadmap P1 exit gate |

## Module dependency and build notes

- `:core:data` gains no new project-module edge (still `:core:model` only); `checkModuleDependencies` must keep passing.
- Room schema export: `androidx.room` Gradle plugin with `room { schemaDirectory("$projectDir/schemas") }`; `exportSchema = true`. The `ksp` `arg("room.schemaLocation", ...)` route is **not** used — pre-approval validation proved it does not export under KSP2.
- `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`.
- No `fallbackToDestructiveMigration`, no `room-ktx` (merged into `room-runtime` since Room 2.7).

---

### Task 1: Verify toolchain and device prerequisites

**Files:** none (read-only checks)

- [ ] **Step 1: Confirm JDK, Gradle wrapper, and the reference device**

Run:

```powershell
java -version
.\gradlew.bat --version
adb devices -l
```

Expected: JDK 17+, Gradle 9.5.0, and the Xiaomi 14 Pro visible with USB debugging.

### Task 2: `:core:model` domain enums

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

### Task 3: `:core:data` build setup — pinned versions, already validated

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
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
```

- [ ] **Step 4: Confirm the pre-approved combination builds**

Run:

```powershell
.\gradlew.bat :core:data:assembleDebug
```

Expected: `BUILD SUCCESSFUL` with the pinned KSP 2.3.9 + `androidx.room` plugin (the same combination the pre-approval validation record already proved). Any failure here revises this plan first; no version is switched mid-execution.

### Task 4: Entities, database, and schema export

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

### Task 5: DTOs, typed results, DAO, validator, fault hook, repository

**Files:**
- Create: `Model.kt`, `RepositoryResult.kt`, `RecordDao.kt`, `XorInvariantValidator.kt`, `TransactionFaultHook.kt`, `RecordRepository.kt`

- [ ] **Step 1: Public DTOs and typed results**

DTOs per the "Public API" section; `RepositoryResult` sealed hierarchy per conventions "Stable application errors". Mapping rules: validator rejection → `InvalidXor`; `SQLiteConstraintException` (duplicate PK, duplicate relation pair) → `Conflict`; any other Room/SQLite exception → `StorageFailure`; no raw exception or `Throwable.message` crosses the boundary.

- [ ] **Step 2: `RecordDao` (internal)**

Insert-by-id and get-by-id for each entity, `getEntityRef`, `getTransactionWithEntries`, `getRelationsForTransaction`, `getRelationsForNote`, and `replaceTransactionNoteRelations(transactionId, noteIds)` (delete old set + insert new set in the caller's transaction).

- [ ] **Step 3: `XorInvariantValidator` (internal)**

Per spec 5.1: reject `entityType`/subtype mismatch, a missing subtype row, an ID already present as the other subtype (cross-table collision, checked inside the transaction), and `entityType = PROJECT` in P1. Throws `IllegalArgumentException`, which the repository maps to `InvalidXor`; the surrounding `withTransaction` rolls back.

- [ ] **Step 4: `TransactionFaultHook` (internal)**

No-op interface with ordered stages: `beforeTransaction()`, `afterChildWrite(childIndex: Int)` (called after each logical child write, in order), `beforeCommit()` (last statement inside the transaction), `afterCommitResponse()` (called **after** `withTransaction` returns — the commit is already durable). Production default is the no-op; tests inject throwing implementations at chosen stages/ordinals.

- [ ] **Step 5: `RecordRepository` (public)**

`companion object { fun open(context: Context, dbName: String = "record.db"): RecordRepository }` builds the internal database via `Room.databaseBuilder` and returns the repository — the composition entry for `:app`. Internal constructor and internal test factory for a controlled database. Child-write order per aggregate (EntityRef → subtype → entries/relations, each followed by `afterChildWrite(ordinal)`); `beforeCommit` last inside the transaction; `afterCommitResponse` outside it. All results are `RepositoryResult`. **The repository is the only public write surface.**

### Task 6: Instrumented tests — persist/reload, XOR, FK, typed endpoints

**Files:**
- Create: `PersistReloadTest.kt`, `XorIdentityTest.kt`, `ForeignKeyTest.kt`, `TypedEndpointTest.kt` under `:core:data` androidTest

- [ ] **Step 1: `PersistReloadTest` (criterion 1)**

Build a real on-device file-backed database via the public factory, insert one aggregate per table through public repository methods, close, reopen via the same factory, read back through public views: exact field values, enum string values, `deletedAt` absent/present, relation rows, `PRAGMA foreign_keys = 1`.

- [ ] **Step 2: `XorIdentityTest` (criterion 2)**

Evidence per rejecting layer: (a) database layer (direct internal writes, the only allowed bypass) — duplicate subtype row per table and subtype for a missing `EntityRef` fail; (b) validator via public repository — mismatched discriminator, missing subtype, `PROJECT` `EntityRef` all return `InvalidXor` with rollback; (c) cross-table same-ID collision returns `InvalidXor`; each case asserts no partial state remains.

- [ ] **Step 3: `ForeignKeyTest` (criterion 3)**

Child rows with missing parents fail to insert; deleting a parent with children fails; `NO ACTION` is confirmed (no cascade observed).

- [ ] **Step 4: `TypedEndpointTest` (criterion 4)**

`TransactionNoteRelation` with nonexistent or wrongly-typed endpoint returns `Conflict`/`InvalidXor` as designed; complete-set replacement is atomic and observable only as old-set or new-set.

### Task 7: Instrumented tests — atomic visibility and concurrency

**Files:**
- Create: `AtomicVisibilityTest.kt`, `ConcurrencyTest.kt` under `:core:data` androidTest

- [ ] **Step 1: `AtomicVisibilityTest` (criterion 5)**

For each aggregate entry point **and** for `replaceTransactionNoteRelations` (children = delete old set, insert new set), inject failure at every stage/ordinal of the hook:

- failures at `beforeTransaction`, any `afterChildWrite` ordinal, and `beforeCommit` → the transaction rolls back; an **external reader** (a separate coroutine querying through a DAO suspend query on its own Room connection, or a Flow collector) observes only the exact old complete snapshot, then after release and rollback still only the old snapshot; result is the matching typed `Failure`;
- failure at `afterCommitResponse` (thrown **after** `withTransaction` returns) → the commit remains durable; the external reader observes the exact new complete snapshot; the result is the typed failure while state is the new complete state — this simulates a commit-succeeded/response-path-failed case, never a transaction failure.

**Latch protocol:** the writer pauses on a latch at the target stage; while paused, the external reader takes one or more snapshots and asserts each equals the exact old complete state (full ID sets and fields, not counts); the latch is released; after the operation completes, the reader asserts the exact new complete state. A paused writer's in-flight writes are never visible to the external reader because the reader is outside the writer's transaction connection (Room WAL default).

- [ ] **Step 2: `ConcurrencyTest` (criterion 6)**

- Concurrent `replaceTransactionNoteRelations` on the same transaction racing a reader: every reader observation equals one complete relation set (full ID set comparison, never a count-only check, never a mixed set).
- Concurrent `saveTransactionAggregate` with the **same aggregate ID**: Room serializes `withTransaction`; the stable outcome is defined as exactly one `Success` and one `Conflict`, with the final database state equal to the successful aggregate's complete state. Raw SQLite/Room exceptions never escape the repository (assert typed results only). Fixed seeds where sequences are generated.

### Task 8: Projection write ban — machine checks

**Files:**
- Create: `SchemaContractTest.kt` (JVM unit test)
- Create: `core/data/apiGuard/positive/*.kt`, `core/data/apiGuard/negative/*.kt` (committed fixtures)
- Modify: `core/data/build.gradle.kts` (`apiBoundaryCheck` task + detached compiler configuration, wired into `check`)

- [ ] **Step 1: `SchemaContractTest` (criterion 7a)**

Read the committed `1.json`; assert the table set is exactly the six spec-4.1 tables (no `SearchDocument`, `Timeline`, or any other table); assert the ADR 0006 contract: timestamps and `amountMinor` integer affinity, enum columns text affinity, all FKs `NO ACTION`, the two `AccountEntry` indexes and the `TransactionNoteRelation` index.

- [ ] **Step 2: `apiBoundaryCheck` task**

BCV is not used (its `apiDump`/`apiCheck` do not register under AGP built-in Kotlin — Kotlin/binary-compatibility-validator#312). Instead, an independent consumer compilation: a Gradle task in `:core:data` that invokes the Kotlin compiler CLI (`org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` via `JavaExec`, classpath from a detached dev-only configuration of `kotlin-compiler-embeddable` 2.2.10) with `-classpath <module debug classes + debugCompileClasspath + android.jar (compileSdk 36)>` over the committed `apiGuard` fixtures. The task compiles **outside the module**: the fixtures are a separate compilation with no friend-path `internal` visibility, so a same-module source set cannot serve as this consumer. Assertions:

- every `apiGuard/positive/*.kt` fixture compiles (exit 0) — the documented public surface (repository factory/methods, DTOs, enums, `RepositoryResult`);
- every `apiGuard/negative/*.kt` fixture fails to compile (nonzero exit and compiler stderr shows the internal-visibility/unresolved-reference diagnostics) — fixtures reference `RecordDao`, `RecordDatabase`, `XorInvariantValidator`, the six entity classes, and hypothetical projection write entries on the repository.

The task is wired into `:core:data:check` via `dependsOn` so the boundary is enforced on every `check` run, durably — not a one-off experiment. Runtime reflection is not used for this check.

- [ ] **Step 3: Negative verification**

Temporarily make `RecordDao` public, run `.\gradlew.bat :core:data:check`; expected FAIL because a negative fixture compiles (asserted failure turned into success). Revert; run again; expected PASS. Record both states in `verification.md` (mirrors P0's negative-verification evidence).

### Task 9: Full build and device regression

**Files:** none (verification)

- [ ] **Step 1: Clean full check with all tests**

Run:

```powershell
.\gradlew.bat clean check
```

Expected: `BUILD SUCCESSFUL`; `checkModuleDependencies: PASS`; lint 0 errors; `:core:data:apiBoundaryCheck` PASS; JVM unit tests PASS.

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

### Task 10: Verification record and commits

**Files:**
- Create: `docs/specs/tech-poc/p1-minimal-room-model/verification.md`

- [ ] **Step 1: Create `verification.md`**

Follow the testing strategy evidence format (`Command`, `Environment`, `Result`, `Remaining risk`). Map every acceptance criterion (spec section 5.2, items 1–8) and the roadmap P1 exit gate to concrete commands and results; include the negative-verification evidence (Task 8 Step 3), the KSP2 validation evidence (Task 3 Step 4, referencing the pre-approval record), the committed schema JSON and fixture hashes, and remaining risk. **All actual commands, environments, results, and deviations live only in `verification.md`** per the roadmap's document ownership; nothing is appended to this plan at completion.

- [ ] **Step 2: Confirm the working tree is clean except intended files**

Run:

```powershell
git status --short
git diff --check
```

- [ ] **Step 3: Commit the slice in logical units**

The approval-sync records (spec status, roadmap, AGENTS.md, docs/index.md, ADR 0006 acceptance) were already committed separately as `docs: record P1 spec and ADR 0006 approval (2026-08-05)`; the plan is already committed. Implementation commits:

```powershell
git add gradle/libs.versions.toml build.gradle.kts core/data
git add core/model
git commit -m "feat(poc): P1 Room schema v1, typed repository surface, and machine checks"

git add docs/specs/tech-poc/p1-minimal-room-model/verification.md
git commit -m "docs: record P1 minimal Room model verification"
```

Expected: clean local commits; working tree clean.

### Task 11: Slice state confirmation

**Files:** none (verification)

- [ ] **Step 1: Verify state**

Run:

```powershell
git log --oneline -6
git status --short
```

Expected: approval, plan, and implementation history present; working tree clean.

## Completion result

The P1 exit gate passes when `verification.md` shows: the six-table `databaseVersion = 1` schema with the ADR 0006 contract persists and reloads on the reference device; XOR identity, foreign keys, typed endpoints, atomic visibility (every stage/ordinal boundary, old-vs-new complete states, external-reader evidence), and concurrency evidence pass with defined stable outcomes; the schema-JSON and independent-consumer `apiBoundaryCheck` machine checks plus negative verification prove projections cannot originate writes and internal types are unreachable from an external consumer; `clean check` and the P0 launch regression pass.

## Open decision tracked for later slices

Per spec section 4.3, the phase ownership of the recoverable-migration-snapshot machinery (schema-changing slice vs. the P5 roadmap gate) is settled through the core documents and an ADR **before the first migration after P1**. P1 contains no migration and is not blocked by this; the decision is registered here so the P2/P3/P4 planning does not start from an unresolved contradiction.

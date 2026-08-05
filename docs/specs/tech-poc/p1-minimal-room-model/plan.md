# Tech PoC P1 — Minimal Room Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the approved minimal Room 2.8.4 model — `EntityRef`, `Transaction`, `AccountEntry`, `Note`, plus `Account` (FK target) and the typed `TransactionNoteRelation` — with instrumented tests covering persist/reload, three-layer XOR identity enforcement, foreign keys, typed endpoint rules, atomic aggregate visibility under injected failure and concurrent writes, and machine-checked projection write bans. See the [P1 spec](spec.md) and [ADR 0006](../../../architecture/decisions/0006-version-1-persistence-contract.md).

**Architecture:** Room entities, DAOs, database, XOR validator, and the repository live in `:core:data`; `EntityType`/`TransactionType` enums live in `:core:model` (pure Kotlin JVM). **The public surface of `:core:data` is exactly: the `RecordRepository` (public factory + `close()`), the application DTOs, the typed `RepositoryResult`, and the `:core:model` enums.** Room entities, the DAO, the database, and the validator are module-internal, per the [engineering conventions](../../../engineering/conventions.md) (repository APIs expose domain/application models and typed outcomes, never Room entities). The seven-module graph is unchanged.

**Plan revision 2 (2026-08-05):** review corrections — `RepositoryResult` now distinguishes every durable outcome (including committed-but-response-failed); XOR acceptance split into database-layer / validator-directed / consumer-compile / repository paths that are actually executable; fault-injection latch matrix corrected; `apiBoundaryCheck` extended with a full public-signature allowlist compared via `kotlin-metadata-jvm`; pre-approval KSP evidence moved to `verification.md` per document ownership, with the full `assembleDebug` record; `RecordRepository` implements `Closeable` with `open(context)` and an internal test factory.

**Tech Stack:** Gradle 9.5.0, AGP 9.3.0 (built-in Kotlin 2.2.10), KSP2 2.3.9, Room 2.8.4 (runtime + `androidx.room` Gradle plugin + KSP2 compiler), `org.jetbrains.kotlin:kotlin-compiler-embeddable` 2.2.10 and `org.jetbrains.kotlin:kotlin-metadata-jvm` 2.2.10 (dev/test-time only), androidx.test runner/ext-junit, JUnit 4.13.2. `minSdk 34` / `targetSdk 34` / `compileSdk 36`.

**Reference environment (frozen):** Xiaomi 14 Pro (physical, USB debugging), Android 14 / API 34 out-of-box baseline. The actual system build is recorded in `verification.md` at verification time (currently API 36 per P0; recorded, not rejected).

**Namespaces:** `:core:model` → `com.paherdin.record.model`; `:core:data` → `com.paherdin.record.core.data` (Room package `com.paherdin.record.core.data.room`, public API in `com.paherdin.record.core.data`).

**Pre-approval dependency validation:** the real-build evidence for KSP 2.3.9 + AGP 9.3 + built-in Kotlin 2.2.10 + Room 2.8.4, including the schema-export finding (the `ksp` arg does not export under KSP2; the `androidx.room` Gradle plugin does), is recorded in [verification.md](verification.md). Version-selection basis: KSP 2.3.1 added AGP 9.0 and built-in-Kotlin support (google/ksp release notes, issue #2674); 2.3.9 is the current release line validated by that build. **If any pinned version fails during execution, this plan is revised and re-approved first; versions are never switched mid-execution.**

## Dependency review record

Every dependency below is recorded per the engineering conventions (version, license, maintenance, compatibility, removal path) before it enters the version catalog or build.

| Dependency | Version | License | Maintenance | Compatibility impact | Removal path |
|---|---|---|---|---|---|
| KSP2 plugin (`com.google.devtools.ksp`) | 2.3.9 | Apache-2.0 | Google, active | AGP 9.0 + built-in Kotlin supported since 2.3.1; validated by the pre-approval build in [verification.md](verification.md) (Rounds 1–3). KSP1 is not an option (no AGP 9 support). | Remove plugin and `ksp(...)` compiler; `:core:data` keeps no Room code. |
| `androidx.room:room-runtime` | 2.8.4 | Apache-2.0 | Google, active | Already in catalog (P0); now referenced by `:core:data`. Since Room 2.7 it includes the APIs formerly in `room-ktx` (e.g. `withTransaction`); `room-ktx` is not added (empty artifact). | Remove the module dependency; catalog entry is maintenance. |
| `androidx.room` Gradle plugin | 2.8.4 | Apache-2.0 | Google, active | Sets Room processor options including schema export; required because the `ksp` arg route does not export under KSP2 (verification.md Round 1). | Remove the plugin; the committed schema JSON remains; future export needs alternative tooling and a plan revision. |
| `androidx.room:room-compiler` (KSP2) | 2.8.4 | Apache-2.0 | Google, active | KSP2 supported since Room 2.7; validated by the pre-approval build. | Remove `ksp(...)` entry and plugin. |
| `org.jetbrains.kotlin:kotlin-compiler-embeddable` | 2.2.10 | Apache-2.0 | JetBrains, active | Dev-time only; backs the `apiBoundaryCheck` consumer compilation (Task 8). Same version as the built-in KGP. Never part of app/runtime dependencies. | Remove the detached configuration and the `apiBoundaryCheck` task. |
| `org.jetbrains.kotlin:kotlin-metadata-jvm` | 2.2.10 | Apache-2.0 | JetBrains, active | Test-time only; the standard Kotlin metadata reader (the foundation BCV itself uses). Same version as the built-in KGP, so it reads exactly the metadata version the compiler writes — no cross-version risk. Used by `ApiSurfaceTest` to compare the public-signature allowlist and to assert compile-time `internal` visibility. | Remove the test dependency and `ApiSurfaceTest`; the allowlist file remains as documentation. |
| `androidx.test:runner` | 1.6.2 | Apache-2.0 | Google, active | Instrumented test runner on the reference device. | Remove; run tests another way. |
| `androidx.test.ext:junit` | 1.2.1 | Apache-2.0 | Google, active | JUnit4 rules for instrumented tests. | Remove. |
| `junit:junit` | 4.13.2 | EPL-1.0 | JUnit team, stable | JVM unit tests (`:core:data` test source set) for the schema contract, validator, and API surface. | Remove. |

No dependency introduces network permissions, analytics, crash reporting, or closed-source tracking. The app manifest is unchanged (no `INTERNET`).

## Public API of `:core:data` (the only write surface)

Per the spec's section 5.1 and the engineering conventions, the public surface is closed and typed:

- `RecordRepository` — public class implementing `Closeable`. Public factory: `companion object { fun open(context: Context): RecordRepository }` using an internal default database-name constant; public `close()`. The internal test factory (`openForTest(context, dbName)`) supplies test database names/paths — the public surface never exposes a database name. **Every behavioral test — production and test alike — calls only the public methods; internal seams (the fault hook, the test factory) never bypass the XOR validator or the atomic entry points.**
- Application DTOs (public, `:core:data`): `NewAccount(id, name, currency)`, `NewAccountEntry(id, accountId, amountMinor)`, `NewTransaction(id, type, createdAt, updatedAt, deletedAt, entries, relationNoteIds)`, `NewNote(id, createdAt, updatedAt, deletedAt, relationTransactionIds)`, `TransactionView`, `NoteView`. The DTOs carry no discriminator field: `entityType` is implied by the typed DTO (`NewTransaction` is always `TRANSACTION`, `NewNote` always `NOTE`), so the public API cannot express a mismatched discriminator, a missing subtype, or a `PROJECT` write. The repository maps DTO → entity inside the transaction.
- `RepositoryResult<T>` — public sealed outcome, per the engineering conventions' stable-error taxonomy, with distinct kinds whose caller behavior differs:

```kotlin
sealed interface RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>
    sealed interface Failure : RepositoryResult<Nothing> {
        data class ValidationRejected(val violation: XorViolation) : Failure // domain rejection, carries the invariant
        data object DuplicateIdentity : Failure       // uniqueness conflict
        data object EndpointRejected : Failure        // FK / typed-endpoint rejection
        data object StorageExhausted : Failure
        data object TransactionFailed : Failure       // nothing committed; caller may safely retry
        data object CommittedButResponseFailed : Failure // data IS durable; do NOT retry
        data object Unexpected : Failure
    }
}
enum class XorViolation { DISCRIMINATOR_MISMATCH, MISSING_SUBTYPE, PROJECT_NOT_SUPPORTED, CROSS_TABLE_ID_COLLISION }
```

  Boundary mapping (inside the repository, never crossing outward): validator rejection → `ValidationRejected(violation)`; `SQLiteConstraintException` by error code — PRIMARYKEY/UNIQUE → `DuplicateIdentity`, FOREIGNKEY → `EndpointRejected`; `SQLiteFullException` → `StorageExhausted`; any exception raised inside `withTransaction` (including the hook at `beforeTransaction`/`afterChildWrite`/`beforeCommit`) → `TransactionFailed`; a hook exception at `afterCommitResponse` (raised **after** the commit is durable) → `CommittedButResponseFailed`, which explicitly tells the caller the data is committed and must not be retried; anything else → `Unexpected`. No raw exception or `Throwable.message` crosses.

- `:core:model` enums `EntityType`/`TransactionType` are referenced by the DTOs.

Internal-only: the six `@Entity` classes, `RecordDao`, `RecordDatabase`, `XorInvariantValidator`, `TransactionFaultHook`. Tests may read raw state through internal DAO queries for verification and may write directly to the database **only** for the database-layer rejection evidence cases (spec 5.1); those cases prove the schema's own constraints and never construct valid application state, so the validator remains the single path for every successful write.

## File map

| Path | Responsibility |
|---|---|
| `core/model/src/main/java/com/paherdin/record/model/EntityType.kt` | `EntityType` enum (`TRANSACTION`/`NOTE`/`PROJECT`), mirror of the domain model |
| `core/model/src/main/java/com/paherdin/record/model/TransactionType.kt` | `TransactionType` enum (`OPENING_BALANCE`/`INCOME`/`EXPENSE`/`TRANSFER`) |
| `core/data/build.gradle.kts` | KSP2 2.3.9 + `androidx.room` plugin + Room deps, `room { schemaDirectory("$projectDir/schemas") }`, test runner, JVM/instrumented test deps (incl. `kotlin-metadata-jvm` testImplementation), `apiBoundaryCheck` task wired into `check`, detached dev-only compiler configuration |
| `core/data/src/main/java/com/paherdin/record/core/data/room/EntityRef.kt` | `internal` `@Entity` per 4.1/4.2 of the spec (PK, enum as string, epoch-millis `Long` timestamps, nullable `deletedAt`) |
| `core/data/src/main/java/com/paherdin/record/core/data/room/Transaction.kt` | `internal` `@Entity` same-ID PK/FK to `EntityRef`, `type` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/Account.kt` | `internal` `@Entity` identity-only (`name`, `currency`) |
| `core/data/src/main/java/com/paherdin/record/core/data/room/AccountEntry.kt` | `internal` `@Entity` FKs to `Transaction`/`Account` with explicit `indices = [Index("transactionId"), Index("accountId")]`, `amountMinor` `Long` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/Note.kt` | `internal` `@Entity` same-ID PK/FK to `EntityRef` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/TransactionNoteRelation.kt` | `internal` `@Entity` composite PK `(transactionId, noteId)`, both FKs `NO ACTION`, index `(noteId, transactionId)` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDao.kt` | `internal` DAO interface: inserts, gets, relation set replacement, transaction-scoped queries used by the repository and tests |
| `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDatabase.kt` | `internal` `@Database(version = 1, exportSchema = true)`; FK enforcement on open |
| `core/data/src/main/java/com/paherdin/record/core/data/Model.kt` | Public DTOs: `NewAccount`, `NewAccountEntry`, `NewTransaction`, `NewNote`, `TransactionView`, `NoteView` |
| `core/data/src/main/java/com/paherdin/record/core/data/RepositoryResult.kt` | Public sealed typed outcomes (`Success` / `ValidationRejected` / `DuplicateIdentity` / `EndpointRejected` / `StorageExhausted` / `TransactionFailed` / `CommittedButResponseFailed` / `Unexpected`) + `XorViolation` |
| `core/data/src/main/java/com/paherdin/record/core/data/XorInvariantValidator.kt` | `internal` pure validator: `validate(entityType, transactionExists, noteExists)` rejecting mismatch, missing subtype, and `PROJECT`; the repository queries existence (incl. cross-table same-ID) and calls it inside the transaction |
| `core/data/src/main/java/com/paherdin/record/core/data/TransactionFaultHook.kt` | `internal` hook: `beforeTransaction()`, `afterChildWrite(childIndex)`, `beforeCommit()`, `afterCommitResponse()`; no-op in production, tests throw at chosen stages/ordinals |
| `core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt` | Public `Closeable` `RecordRepository`: public `open(context)` factory, internal test factory, DTO-mapped atomic entry points, read views, typed results; all writes in one `withTransaction` running the XOR validator |
| `core/data/schemas/com.paherdin.record.core.data.room.RecordDatabase/1.json` | Exported Room schema JSON — **committed**; verification artifact for ADR 0006 and the projection table-set assertion |
| `core/data/api/expected-surface.txt` | **Committed** allowlist of the exact public declarations of `:core:data` + `:core:model` (the machine-compared signature allowlist) |
| `core/data/apiGuard/positive/*.kt` | **Committed** consumer-compilation fixtures that MUST compile: the documented public surface (repository factory/methods/`close`, DTOs, enums, `RepositoryResult`) |
| `core/data/apiGuard/negative/*.kt` | **Committed** consumer-compilation fixtures, one concern per file, that MUST FAIL to compile: references to internal types (`RecordDao`, `RecordDatabase`, `XorInvariantValidator`, the six entity classes) and to hypothetical APIs the public surface must not have (an `EntityRef`-only write, a `PROJECT`-bearing write, projection write entries) |
| `core/data/src/test/java/com/paherdin/record/core/data/SchemaContractTest.kt` | JVM test: committed `1.json` table/column/index/FK set equals spec 4.1+4.2 exactly (incl. no projection tables) |
| `core/data/src/test/java/com/paherdin/record/core/data/XorInvariantValidatorTest.kt` | JVM tests of the pure validator: every (entityType, existence) combination — mismatch, missing subtype, `PROJECT` |
| `core/data/src/test/java/com/paherdin/record/core/data/ApiSurfaceTest.kt` | JVM test: parses the module classes' `@Metadata` with `kotlin-metadata-jvm`; asserts the six entities/DAO/database/validator are compile-time `internal`; asserts the PUBLIC declarations equal `api/expected-surface.txt` exactly (both directions) and contain no projection types or projection write methods |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/PersistReloadTest.kt` | Criterion 1: public `open` → write → `close()` → public `open` → read back exact values |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/XorIdentityTest.kt` | Criterion 2: database-layer rejection evidence (PK/FK/missing parent/same-table duplicates) + repository cross-table collision → typed rejection |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/ForeignKeyTest.kt` | Criterion 3 |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/TypedEndpointTest.kt` | Criterion 4 |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AtomicVisibilityTest.kt` | Criterion 5: ordinal fault injection at all four boundaries with latch-paused writers and external observers, per the state matrix |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/ConcurrencyTest.kt` | Criterion 6: concurrent saves + relation-set replacement races with full-state comparison and defined stable outcomes |
| `verification.md` | Created 2026-08-05 with the pre-approval validation record; completed at Task 10 with the P1 execution evidence |

## Module dependency and build notes

- `:core:data` gains no new project-module edge (still `:core:model` only); `checkModuleDependencies` must keep passing.
- Room schema export: `androidx.room` Gradle plugin with `room { schemaDirectory("$projectDir/schemas") }`; `exportSchema = true`. The `ksp` `arg("room.schemaLocation", ...)` route is **not** used — the pre-approval build proved it does not export under KSP2 (verification.md Round 1).
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

DTOs per the "Public API" section (no discriminator field — `entityType` implied by the typed DTO). `RepositoryResult` hierarchy and boundary mapping exactly as in the "Public API" section. The repository is the only place that maps Room/SQLite exceptions to outcomes; `CommittedButResponseFailed` is produced only by a failure raised after the durable commit.

- [ ] **Step 2: `RecordDao` (internal)**

Insert-by-id and get-by-id for each entity, `getEntityRef`, `getTransactionWithEntries`, `getRelationsForTransaction`, `getRelationsForNote`, and `replaceTransactionNoteRelations(transactionId, noteIds)` (delete old set + insert new set in the caller's transaction).

- [ ] **Step 3: `XorInvariantValidator` (internal, pure)**

`validate(entityType, transactionExists, noteExists)` throws on: `entityType`/subtype mismatch, a missing subtype, and `entityType = PROJECT` in P1. The repository performs the existence queries (including the cross-table same-ID existence check for the other subtype) inside the transaction and calls the validator before any write; its exception maps to `ValidationRejected(violation)`.

- [ ] **Step 4: `TransactionFaultHook` (internal)**

No-op interface with ordered stages: `beforeTransaction()`, `afterChildWrite(childIndex: Int)` (called after each logical child write, in order), `beforeCommit()` (last statement inside the transaction), `afterCommitResponse()` (called **after** `withTransaction` returns — the commit is already durable). Production default is the no-op; tests inject throwing implementations at chosen stages/ordinals.

- [ ] **Step 5: `RecordRepository` (public, `Closeable`)**

`companion object { fun open(context: Context): RecordRepository }` builds the internal database via `Room.databaseBuilder` with the internal default-name constant; `close()` closes the database. Internal test factory `openForTest(context, dbName)` for isolated test databases. Child-write order per aggregate (EntityRef → subtype → entries/relations, each followed by `afterChildWrite(ordinal)`); `beforeCommit` last inside the transaction; `afterCommitResponse` outside it. All results are `RepositoryResult`. **The repository is the only public write surface.**

### Task 6: Instrumented tests — persist/reload, XOR, FK, typed endpoints

**Files:**
- Create: `PersistReloadTest.kt`, `XorIdentityTest.kt`, `ForeignKeyTest.kt`, `TypedEndpointTest.kt` under `:core:data` androidTest
- Create: `XorInvariantValidatorTest.kt` (JVM unit test)

- [ ] **Step 1: `PersistReloadTest` (criterion 1)**

Public API only: `open(context)` → insert one aggregate per table through public repository methods → `close()` → `open(context)` → read back through public views: exact field values, enum string values, `deletedAt` absent/present, relation rows, `PRAGMA foreign_keys = 1`. Test isolation uses the internal test factory's database names.

- [ ] **Step 2: `XorInvariantValidatorTest` (JVM, criterion 2 — validator layer)**

Every (entityType, transactionExists, noteExists) combination: mismatch, missing subtype, and `PROJECT` all throw with the matching `XorViolation`; the two valid combos pass. The public API cannot even express these inputs (DTOs are typed), so this is the directed validator evidence.

- [ ] **Step 3: `XorIdentityTest` (instrumented, criterion 2 — database layer + repository)**

Database layer (direct internal writes only, the allowed bypass for schema-evidence cases): a subtype row for a missing `EntityRef` fails (FK); a duplicate subtype row in the same table fails (PK). **No `PROJECT`-persistence claim is made at the database layer** — ADR 0006 establishes there is no database `CHECK`. Repository layer: creating a `Note` with id X, then `saveTransactionAggregate` with the same id X returns `ValidationRejected(CROSS_TABLE_ID_COLLISION)` with no partial state. Every case asserts no partial state remains.

- [ ] **Step 4: `ForeignKeyTest` (criterion 3)**

Child rows with missing parents fail to insert; deleting a parent with children fails; `NO ACTION` is confirmed (no cascade observed).

- [ ] **Step 5: `TypedEndpointTest` (criterion 4)**

`TransactionNoteRelation` with a nonexistent endpoint returns `EndpointRejected`; a duplicate relation pair returns `DuplicateIdentity`; complete-set replacement is atomic and observable only as old-set or new-set.

### Task 7: Instrumented tests — atomic visibility and concurrency

**Files:**
- Create: `AtomicVisibilityTest.kt`, `ConcurrencyTest.kt` under `:core:data` androidTest

- [ ] **Step 1: `AtomicVisibilityTest` (criterion 5)**

For each aggregate entry point **and** for `replaceTransactionNoteRelations` (children = delete old set, insert new set), inject failure at every stage/ordinal of the hook. The expected state matrix is:

| Injection | While the writer is paused at the stage | After the operation completes | Expected result |
|---|---|---|---|
| `beforeTransaction` throws | old complete state | old complete state | `TransactionFailed`, nothing persisted |
| `afterChildWrite(ordinal)` throws | old complete state | old complete state | `TransactionFailed`, nothing persisted |
| `beforeCommit` throws | old complete state | old complete state | `TransactionFailed`, nothing persisted |
| `afterCommitResponse` throws (commit already durable) | new complete state | new complete state | `CommittedButResponseFailed`, data persisted |
| no-fault control group | old complete state | new complete state | `Success` |

**Latch protocol:** the writer pauses on a latch at the target stage; while paused, an **external reader** — a separate coroutine querying through a DAO suspend query on its own Room connection (Room WAL default), never the writer's connection — takes one or more snapshots and asserts each equals the exact complete state the matrix requires (full ID sets and fields, not counts); the latch is released; after the operation completes the reader asserts the final complete state. A paused writer's in-flight writes are never visible to the external reader because the reader is outside the writer's transaction connection. The `afterCommitResponse` case deliberately throws **after** `withTransaction` returns so the commit is already durable and the reader observes the new state even while the writer is paused.

- [ ] **Step 2: `ConcurrencyTest` (criterion 6)**

- Concurrent `replaceTransactionNoteRelations` on the same transaction racing a reader: every reader observation equals one complete relation set (full ID set comparison, never a count-only check, never a mixed set).
- Concurrent `saveTransactionAggregate` with the **same aggregate ID**: Room serializes `withTransaction`; the stable outcome is defined as exactly one `Success` and one `DuplicateIdentity`, with the final database state equal to the successful aggregate's complete state. Raw SQLite/Room exceptions never escape the repository (assert typed results only). Fixed seeds where sequences are generated.

### Task 8: Projection write ban — machine checks

**Files:**
- Create: `SchemaContractTest.kt`, `ApiSurfaceTest.kt` (JVM unit tests), `core/data/api/expected-surface.txt`, `core/data/apiGuard/positive/*.kt`, `core/data/apiGuard/negative/*.kt`
- Modify: `core/data/build.gradle.kts` (`apiBoundaryCheck` task + detached compiler configuration, wired into `check`)

- [ ] **Step 1: `SchemaContractTest` (criterion 7a)**

Read the committed `1.json`; assert the table set is exactly the six spec-4.1 tables (no `SearchDocument`, `Timeline`, or any other table); assert the ADR 0006 contract: timestamps and `amountMinor` integer affinity, enum columns text affinity, all FKs `NO ACTION`, the two `AccountEntry` indexes and the `TransactionNoteRelation` index.

- [ ] **Step 2: `ApiSurfaceTest` — full public-signature allowlist (criterion 7b)**

JVM unit test using `kotlin-metadata-jvm` 2.2.10 (the metadata reader the BCV tool itself is built on). The test locates the classpath element containing `RecordDatabase.class` (the module's main classes dir), enumerates `.class` files under `com/paherdin/record/core/data/**` and `com/paherdin/record/model/**` in that element only, parses each `@Metadata` (classes without metadata such as `R` are skipped; Room-generated `*_Impl` classes are skipped — documented exclusion, they extend the internal `RecordDatabase` and are unreachable externally), and asserts:

- the six entity classes, `RecordDao`, `RecordDatabase`, and `XorInvariantValidator` are `internal` per the compiled metadata — compile-time visibility, not JVM reflection;
- the PUBLIC declarations match `api/expected-surface.txt` **exactly**, in both directions: any public declaration not in the allowlist fails the test (this catches any new public write method regardless of name — e.g. a projection write entry), and any allowlist entry missing from the code fails it;
- no `SearchDocument`/`Timeline` type and no projection-write method exists.

The allowlist is committed and reviewed with the plan; this test runs in `:core:data:test` as part of `check`.

- [ ] **Step 3: `apiBoundaryCheck` — independent consumer compilation**

BCV is not used (its `apiDump`/`apiCheck` do not register under AGP built-in Kotlin — Kotlin/binary-compatibility-validator#312). Instead, a Gradle task in `:core:data` that invokes the Kotlin compiler CLI (`org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` via `JavaExec`, classpath from a detached dev-only configuration of `kotlin-compiler-embeddable` 2.2.10) with `-classpath <module debug classes + debugCompileClasspath + android.jar (compileSdk 36)>`, `dependsOn("compileDebugKotlin")`. The fixtures compile **outside the module**: a separate compilation with no friend-path `internal` visibility, so a same-module source set cannot serve as this consumer. **Each fixture file is compiled in its own independent compiler invocation**, so one still-failing fixture can never mask another that unexpectedly started compiling:

- every `apiGuard/positive/*.kt` fixture compiles (exit 0) — the documented public surface (factory, methods, `close()`, DTOs, enums, `RepositoryResult`);
- every `apiGuard/negative/*.kt` fixture fails to compile (nonzero exit and compiler stderr shows the internal-visibility/unresolved-reference diagnostics) — one concern per file: `RecordDao`, `RecordDatabase`, `XorInvariantValidator`, each entity class, an `EntityRef`-only write, a `PROJECT`-bearing write, and projection write entries.

The task is wired into `:core:data:check` via `dependsOn` so the boundary is enforced on every `check` run, durably.

- [ ] **Step 4: Negative verification**

Two directions: (i) temporarily make `RecordDao` public → a negative fixture compiles → `apiBoundaryCheck` FAIL; (ii) temporarily add a public method to `RecordRepository` → `ApiSurfaceTest` FAIL. Revert both; run again; expected PASS. Record both states in `verification.md` (mirrors P0's negative-verification evidence).

### Task 9: Full build and device regression

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

### Task 10: Complete the verification record and commit

**Files:**
- Complete: `docs/specs/tech-poc/p1-minimal-room-model/verification.md` (created 2026-08-05 with the pre-approval validation record)

- [ ] **Step 1: Complete `verification.md`**

Append the P1 execution record below the pre-approval section: every actual command, environment, result, deviation, and remaining risk in the testing strategy's `Command`/`Environment`/`Result`/`Remaining risk` format, mapping every acceptance criterion (spec section 5.2, items 1–8) and the roadmap P1 exit gate to concrete commands and results; include the negative-verification evidence (Task 8 Step 4), the committed schema JSON and allowlist hashes, and remaining risk. **All actual commands, environments, results, and deviations live only in `verification.md`** per the roadmap's document ownership; nothing is appended to this plan at completion.

- [ ] **Step 2: Confirm the working tree is clean except intended files**

Run:

```powershell
git status --short
git diff --check
```

- [ ] **Step 3: Commit the slice in logical units**

The approval-sync records (spec status, roadmap, AGENTS.md, docs/index.md, ADR 0006 acceptance) were already committed separately (`docs: record P1 spec and ADR 0006 approval (2026-08-05)`); the plan and the pre-approval verification record are already committed. Implementation commits:

```powershell
git add gradle/libs.versions.toml build.gradle.kts core/data core/model
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

The P1 exit gate passes when `verification.md` shows: the six-table `databaseVersion = 1` schema with the ADR 0006 contract persists and reloads through the public API on the reference device; XOR identity (database layer, validator-directed, consumer-compile, and repository paths), foreign keys, typed endpoints, atomic visibility (every stage/ordinal boundary, external-reader evidence, per the state matrix), and concurrency evidence pass with defined stable outcomes; the schema-JSON contract, the full public-signature allowlist, and the independent-consumer `apiBoundaryCheck` machine checks plus negative verification prove projections cannot originate writes and internal types are unreachable from an external consumer; `clean check` and the P0 launch regression pass.

## Open decision tracked for later slices

Per spec section 4.3, the phase ownership of the recoverable-migration-snapshot machinery (schema-changing slice vs. the P5 roadmap gate) is settled through the core documents and an ADR **before the first migration after P1**. P1 contains no migration and is not blocked by this; the decision is registered here so the P2/P3/P4 planning does not start from an unresolved contradiction.

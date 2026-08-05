# Tech PoC P1 — Minimal Room Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the approved minimal Room 2.8.4 model — `EntityRef`, `Transaction`, `AccountEntry`, `Note`, plus `Account` (FK target) and the typed `TransactionNoteRelation` — with instrumented tests covering persist/reload, three-layer XOR identity enforcement, foreign keys, typed endpoint rules, atomic aggregate visibility under injected failure and concurrent writes, and machine-checked projection write bans. See the [P1 spec](spec.md) and [ADR 0006](../../architecture/decisions/0006-version-1-persistence-contract.md).

**Architecture:** Room entities, DAOs, database, XOR validator, and the repository live in `:core:data`; `EntityType`/`TransactionType` enums live in `:core:model` (pure Kotlin JVM). The repository is the only public write surface; DAOs, the database, and the validator are module-internal. The seven-module graph is unchanged.

**Tech Stack:** Gradle 9.5.0, AGP 9.3.0 (built-in Kotlin 2.2.10), Room 2.8.4 (runtime + KSP2 compiler), KSP2 plugin `2.2.10-2.0.2`, kotlinx binary-compatibility-validator 0.17.0, androidx.test runner/ext-junit, JUnit 4.13.2. `minSdk 34` / `targetSdk 34` / `compileSdk 36`.

**Reference environment (frozen):** Xiaomi 14 Pro (physical, USB debugging), Android 14 / API 34 out-of-box baseline. The actual system build is recorded in `verification.md` at verification time (currently API 36 per P0; recorded, not rejected).

**Namespaces:** `:core:model` → `com.paherdin.record.model`; `:core:data` → `com.paherdin.record.core.data` (Room package `com.paherdin.record.core.data.room`).

---

## Dependency review record

Every dependency below is recorded per the engineering conventions (version, license, maintenance, compatibility, removal path) before it enters the version catalog or build.

| Dependency | Version | License | Maintenance | Compatibility impact | Removal path |
|---|---|---|---|---|---|
| KSP2 plugin (`com.google.devtools.ksp`) | 2.2.10-2.0.2 | Apache-2.0 | Google, active | KSP2 is the default since KSP 2.0.0; KSP1 does not support AGP 9. This version is the KSP release built against Kotlin 2.2.10 (the AGP 9 built-in KGP), so it is the best-evidenced match; KSP 2.3.x+ is a standalone version line and is the fallback if the build validation in Task 3 fails. **Validation is a real build, never a version inference.** | Remove the plugin and room-compiler; `:core:data` keeps no Room code. |
| `androidx.room:room-runtime` | 2.8.4 | Apache-2.0 | Google, active | Already in catalog (P0); now referenced by `:core:data`. | Remove the module dependency; catalog entry is maintenance. |
| `androidx.room:room-ktx` | 2.8.4 | Apache-2.0 | Google, active | Provides `withTransaction` and suspend support. | Remove the dependency; use plain DAO transactions. |
| `androidx.room:room-compiler` (KSP2) | 2.8.4 | Apache-2.0 | Google, active | KSP2 supported since Room 2.7; official 2.8.4 setup uses KSP. | Remove `ksp(...)` entry and plugin. |
| kotlinx binary-compatibility-validator (`org.jetbrains.kotlinx.binary-compatibility-validator`) | 0.17.0 | Apache-2.0 | JetBrains, active | Dev-time plugin only; applies to `:core:data` main compilation (release variant). Android-library support via `androidVariant`. If it proves incompatible with AGP 9 built-in Kotlin, fall back to the negative-compilation fixture (Task 8) and record the deviation. | Remove the plugin; `api/core-data.api` stays as a committed artifact. |
| `androidx.test:runner` | 1.6.2 | Apache-2.0 | Google, active | Instrumented test runner on the reference device. | Remove; run tests another way. |
| `androidx.test.ext:junit` | 1.2.1 | Apache-2.0 | Google, active | JUnit4 rules for instrumented tests. | Remove. |
| `junit:junit` | 4.13.2 | EPL-1.0 | JUnit team, stable | JVM unit tests (`:core:data` test source set) for the schema contract and API surface. | Remove. |

No dependency introduces network permissions, analytics, crash reporting, or closed-source tracking. The app manifest is unchanged (no `INTERNET`).

## File map

| Path | Responsibility |
|---|---|
| `core/model/src/main/java/com/paherdin/record/model/EntityType.kt` | `EntityType` enum (`TRANSACTION`/`NOTE`/`PROJECT`), mirror of the domain model |
| `core/model/src/main/java/com/paherdin/record/model/TransactionType.kt` | `TransactionType` enum (`OPENING_BALANCE`/`INCOME`/`EXPENSE`/`TRANSFER`) |
| `core/data/build.gradle.kts` | KSP2 plugin, Room runtime/ktx/compiler, room schema export to `$projectDir/schemas`, test runner, unit/instrumented test deps, BCV plugin |
| `core/data/src/main/java/com/paherdin/record/core/data/room/EntityRef.kt` | `@Entity` per 4.1/4.2 of the spec (PK, enum as string, epoch-millis `Long` timestamps, nullable `deletedAt`) |
| `core/data/src/main/java/com/paherdin/record/core/data/room/Transaction.kt` | `@Entity` same-ID PK/FK to `EntityRef`, `type` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/Account.kt` | `@Entity` identity-only (`name`, `currency`) |
| `core/data/src/main/java/com/paherdin/record/core/data/room/AccountEntry.kt` | `@Entity` FKs to `Transaction`/`Account`, `amountMinor` `Long`; `NO ACTION` FKs |
| `core/data/src/main/java/com/paherdin/record/core/data/room/Note.kt` | `@Entity` same-ID PK/FK to `EntityRef` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/TransactionNoteRelation.kt` | `@Entity` composite PK `(transactionId, noteId)`, both FKs `NO ACTION`, index `(noteId, transactionId)` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDao.kt` | `internal` DAO interface: inserts, gets, relation set replacement, transaction-scoped queries used by the repository and tests |
| `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDatabase.kt` | `internal` `@Database(version = 1, exportSchema = true)`; FK enforcement on open |
| `core/data/src/main/java/com/paherdin/record/core/data/XorInvariantValidator.kt` | `internal` validator per spec 5.1: mismatch, missing subtype, cross-table ID collision, `PROJECT` rejection |
| `core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt` | **The public write surface**: `saveTransactionAggregate`, `saveNoteAggregate`, `replaceTransactionNoteRelations`, read queries; each write in one `withTransaction` running the XOR validator; internal no-op `TransactionFaultHook` injection points for tests |
| `core/data/src/main/java/com/paherdin/record/core/data/TransactionFaultHook.kt` | `internal` hook (no-op in production) invoked before the transaction, between child writes, before commit, and after commit-response handling; tests set it to throw deterministically |
| `core/data/schemas/com.paherdin.record.core.data.room.RecordDatabase/1.json` | Exported Room schema JSON — **committed**; the verification artifact for ADR 0006 and the projection table-set assertion |
| `core/data/api/core-data.api` | BCV API dump — **committed**; the public-surface artifact for the projection write ban |
| `core/data/src/test/java/com/paherdin/record/core/data/SchemaContractTest.kt` | JVM test: committed `1.json` table/column/index/FK set equals spec 4.1+4.2 exactly (incl. no projection tables) |
| `core/data/src/test/java/com/paherdin/record/core/data/ApiSurfaceTest.kt` | JVM test: committed `core-data.api` contains no public DAO/database/validator/projection types and only the allowed `RecordRepository` methods |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/PersistReloadTest.kt` | Criterion 1: write → close/reopen → read back exact values |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/XorIdentityTest.kt` | Criterion 2: per-layer rejection evidence |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/ForeignKeyTest.kt` | Criterion 3 |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/TypedEndpointTest.kt` | Criterion 4 |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AtomicVisibilityTest.kt` | Criterion 5: fault injection at all four boundaries |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/ConcurrencyTest.kt` | Criterion 6: concurrent saves + relation-set replacement races |
| `verification.md` | Created at completion; records commands, environment, results, remaining risk, and maps every criterion to the roadmap P1 exit gate |

## Module dependency and build notes

- `:core:data` gains no new project-module edge (still `:core:model` only); `checkModuleDependencies` must keep passing.
- Room schema export: `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`; `exportSchema = true` on the database.
- BCV: apply the plugin in `:core:data` only; default `androidVariant` (release); `apiCheck` is part of `check`.
- `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`.
- No `fallbackToDestructiveMigration`, no `androidx.room` Gradle plugin (the `ksp` arg is sufficient and has fewer moving parts).

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

### Task 3: `:core:data` build setup — KSP2/Room/BCV dependency validation gate

**Files:**
- Modify: `gradle/libs.versions.toml`, root `build.gradle.kts`, `core/data/build.gradle.kts`

- [ ] **Step 1: Add versions to the catalog**

```toml
[versions]
ksp = "2.2.10-2.0.2"
bck = "0.17.0"
androidxTestRunner = "1.6.2"
androidxTestJunit = "1.2.1"
junit = "4.13.2"

[libraries]
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestJunit" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
binary-compatibility-validator = { id = "org.jetbrains.kotlinx.binary-compatibility-validator", version.ref = "bck" }
```

- [ ] **Step 2: Apply plugins**

Root `build.gradle.kts`: add `alias(libs.plugins.ksp) apply false` and `alias(libs.plugins.binary.compatibility.validator) apply false`. In `core/data/build.gradle.kts`: `alias(libs.plugins.ksp)` and `alias(libs.plugins.binary.compatibility.validator)`.

- [ ] **Step 3: `:core:data` dependencies and schema export**

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
```

- [ ] **Step 4: Compatibility validation — real build with a placeholder database**

Create a temporary `RecordDatabase` with one trivial entity, then run:

```powershell
.\gradlew.bat :core:data:assembleDebug
```

Expected: KSP2 runs, Room generates code, `BUILD SUCCESSFUL`. If `2.2.10-2.0.2` fails with AGP 9.3, record the failure and retry the standalone KSP 2.3.x line under the failure protocol before proceeding. This step is the KSP2 compatibility gate — no later step may depend on an unverified KSP2.

### Task 4: Entities, database, and schema export

**Files:**
- Create: the six entity files, `RecordDatabase.kt`
- Generated (committed): `core/data/schemas/com.paherdin.record.core.data.room.RecordDatabase/1.json`

- [ ] **Step 1: Entities per spec 4.1/4.2**

`EntityRef` (`id` PK, `entityType: EntityType`, `createdAt: Long`, `updatedAt: Long`, `deletedAt: Long?`); `Transaction` (`id` PK/FK → `EntityRef`, `type: TransactionType`); `Account` (`id` PK, `name`, `currency`); `AccountEntry` (`id` PK, `transactionId` FK `NO ACTION`, `accountId` FK `NO ACTION`, `amountMinor: Long`); `Note` (`id` PK/FK → `EntityRef`); `TransactionNoteRelation` (composite PK `(transactionId, noteId)`, both FKs `NO ACTION`, `indices = [Index("noteId", "transactionId")]`). No `Cascade`, no `SET NULL`, no extra fields.

- [ ] **Step 2: `RecordDatabase`**

`@Database(entities = [...six...], version = 1, exportSchema = true)`; `internal`; DAO accessor for the internal `RecordDao`; `Room.databaseBuilder`-independent — tests and repository receive the database instance. Ensure FK enforcement is on for every open (the Room default, but assert `PRAGMA foreign_keys` in tests).

- [ ] **Step 3: Build and commit the exported schema JSON**

Run:

```powershell
.\gradlew.bat :core:data:assembleDebug
```

Expected: `BUILD SUCCESSFUL` and `schemas/com.paherdin.record.core.data.room.RecordDatabase/1.json` exists. Commit it in this task's commit (see Task 11).

### Task 5: DAO, XOR validator, fault hook, and repository

**Files:**
- Create: `RecordDao.kt`, `XorInvariantValidator.kt`, `TransactionFaultHook.kt`, `RecordRepository.kt`

- [ ] **Step 1: `RecordDao` (internal)**

Insert-by-id and get-by-id for each entity, `getEntityRef`, `getTransactionWithEntries`, `getRelationsForTransaction`, `getRelationsForNote`, and a `replaceTransactionNoteRelations(transactionId, noteIds)` that deletes the old set and inserts the new set in the caller's transaction.

- [ ] **Step 2: `XorInvariantValidator` (internal)**

Per spec 5.1: reject `entityType`/subtype mismatch, a missing subtype row, an ID already present as the other subtype (cross-table collision, checked inside the transaction), and `entityType = PROJECT` in P1. Throws `IllegalArgumentException` so the surrounding `withTransaction` rolls back.

- [ ] **Step 3: `TransactionFaultHook` (internal)**

No-op interface with four points: `beforeTransaction`, `betweenChildWrites`, `beforeCommit`, `afterCommitResponse`. Production default is the no-op; tests inject throwing implementations.

- [ ] **Step 4: `RecordRepository` (public)**

`saveTransactionAggregate(entityRef, transaction, entries, relationNoteIds)` and `saveNoteAggregate(entityRef, note, relationTransactionIds)`: one `withTransaction`, XOR validator first, then inserts, then the hook points. `replaceTransactionNoteRelations(transactionId, noteIds)`: one `withTransaction`, complete-set replacement. Read queries for reload verification. The repository is the **only** public write surface — no public method accepts a projection type or exposes a DAO.

### Task 6: Instrumented tests — persist/reload, XOR, FK, typed endpoints

**Files:**
- Create: `PersistReloadTest.kt`, `XorIdentityTest.kt`, `ForeignKeyTest.kt`, `TypedEndpointTest.kt` under `:core:data` androidTest

- [ ] **Step 1: `PersistReloadTest` (criterion 1)**

Build a real on-device database (file-backed), insert one aggregate per table, close, reopen, read back: exact field values, enum string values, `deletedAt` absent/present, relation rows, `PRAGMA foreign_keys = 1`.

- [ ] **Step 2: `XorIdentityTest` (criterion 2)**

Evidence per rejecting layer: (a) database layer — duplicate subtype row per table and subtype for a missing `EntityRef` fail; (b) validator via repository — mismatched discriminator, missing subtype, `PROJECT` `EntityRef` all rejected with rollback; (c) cross-table same-ID collision rejected by the validator; each case asserts no partial state remains.

- [ ] **Step 3: `ForeignKeyTest` (criterion 3)**

Child rows with missing parents fail to insert; deleting a parent with children fails; `NO ACTION` is confirmed (no cascade observed).

- [ ] **Step 4: `TypedEndpointTest` (criterion 4)**

`TransactionNoteRelation` with nonexistent or wrongly-typed endpoint fails; complete-set replacement is atomic and observable only as old-set or new-set.

### Task 7: Instrumented tests — atomic visibility and concurrency

**Files:**
- Create: `AtomicVisibilityTest.kt`, `ConcurrencyTest.kt` under `:core:data` androidTest

- [ ] **Step 1: `AtomicVisibilityTest` (criterion 5)**

For each aggregate entry point, inject failure at `beforeTransaction`, `betweenChildWrites`, `beforeCommit`, and `afterCommitResponse`:

- failures before commit → only the old complete state observable (nothing persisted);
- failure after commit succeeds (during/after commit-response handling) → only the new complete state observable;
- at no point is a partial aggregate, one-sided relation, or mixed state observable (assert via the DAO query surface between hooks and after each case).

- [ ] **Step 2: `ConcurrencyTest` (criterion 6)**

Launch concurrent `saveTransactionAggregate` calls on the same aggregate ID and concurrent `replaceTransactionNoteRelations` on the same transaction racing readers; every reader observation is a complete old or new state (valid relation-set sizes only), never a mixed set. Fixed seeds where sequences are generated.

### Task 8: Projection write ban — machine checks

**Files:**
- Create: `SchemaContractTest.kt`, `ApiSurfaceTest.kt` (JVM unit tests)
- Generate (committed): `core/data/api/core-data.api`

- [ ] **Step 1: `SchemaContractTest` (criterion 7a)**

Read the committed `1.json`; assert the table set is exactly the six spec-4.1 tables (no `SearchDocument`, `Timeline`, or any other table); assert ADR 0006 contract: `deletedAt`/timestamps integer affinity, enum columns text affinity, all FKs `NO ACTION`, the two `AccountEntry` indexes and the `TransactionNoteRelation` index.

- [ ] **Step 2: Apply BCV and generate the API dump**

Run:

```powershell
.\gradlew.bat :core:data:apiDump
```

Expected: `core/data/api/core-data.api` lists only public types — `RecordRepository` and the six entity classes — with no public DAO, database, validator, or projection types. Commit the file.

- [ ] **Step 3: `ApiSurfaceTest` (criterion 7b)**

JVM test reading the committed `core-data.api` (Gradle test working dir is the module dir): assert no public type named with `Dao`, `RecordDatabase`, `Validator`, `SearchDocument`, or `Timeline`; assert `RecordRepository` exposes exactly the documented methods (the two aggregate saves, relation replacement, and read queries) and nothing else. This is the compile-time API-surface check; JVM reflection is not used.

- [ ] **Step 4: Negative verification**

Temporarily add a public `fun writeSearchDocument(...)` to `RecordRepository`, run `.\gradlew.bat :core:data:apiCheck`; expected FAIL with the new method in the dump diff. Revert, run again; expected PASS. Record both states in `verification.md` (mirrors P0's negative-verification evidence). If BCV proves incompatible with AGP 9 built-in Kotlin, implement the negative-compilation fixture instead (a Gradle task compiling a consumer file against `:core:data` output that references a would-be projection write API, asserting compilation fails) and record the deviation.

### Task 9: Full build and device regression

**Files:** none (verification)

- [ ] **Step 1: Clean full check with all tests**

Run:

```powershell
.\gradlew.bat clean check
```

Expected: `BUILD SUCCESSFUL`; `checkModuleDependencies: PASS`; lint 0 errors; `:core:data:apiCheck` PASS; JVM unit tests PASS.

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

### Task 10: Record verification and commit

**Files:**
- Create: `verification.md`

- [ ] **Step 1: Create `verification.md`**

Follow the testing strategy evidence format (`Command`, `Environment`, `Result`, `Remaining risk`). Map every acceptance criterion (spec section 5.2, items 1–8) and the roadmap P1 exit gate to concrete commands and results; include the negative-verification evidence (Task 8 Step 4), the KSP2 validation evidence (Task 3 Step 4), the committed schema JSON and API dump hashes, and remaining risk.

- [ ] **Step 2: Confirm the working tree is clean except intended files**

Run:

```powershell
git status --short
git diff --check
```

- [ ] **Step 3: Commit the slice in logical units**

```powershell
git add docs/specs/tech-poc/p1-minimal-room-model/plan.md
git commit -m "docs: add P1 minimal Room model plan"

git add core/model gradle/libs.versions.toml build.gradle.kts core/data
git commit -m "feat(poc): P1 minimal Room model, schema v1, and machine checks"
```

Expected: clean local commits; working tree clean.

### Task 11: Slice state confirmation

**Files:** none (verification)

- [ ] **Step 1: Verify state**

Run:

```powershell
git log --oneline -5
git status --short
```

Expected: spec/plan/implementation history present; working tree clean.

## Completion result

The P1 exit gate passes when `verification.md` shows: the six-table `databaseVersion = 1` schema with the ADR 0006 contract persists and reloads on the reference device; XOR identity, foreign keys, typed endpoints, atomic visibility (four failure boundaries, old-vs-new complete states), and concurrency evidence pass; the schema-JSON and API-dump machine checks plus negative verification prove projections cannot originate writes; `clean check` and the P0 launch regression pass.

## Open decision tracked for later slices

Per spec section 4.3, the phase ownership of the recoverable-migration-snapshot machinery (schema-changing slice vs. the P5 roadmap gate) is settled through the core documents and an ADR **before the first migration after P1**. P1 contains no migration and is not blocked by this; the decision is registered here so the P2/P3/P4 planning does not start from an unresolved contradiction.

### Execution verification record

To be appended here at completion: date, each Task's PASS/FAIL, negative-verification evidence, KSP2 validation result, device build id, committed schema/API artifact hashes, and remaining risk.

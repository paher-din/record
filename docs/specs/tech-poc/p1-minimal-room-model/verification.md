# Tech PoC P1 — Verification Record

- **Slice state:** `in-progress` — plan Revision 7 was approved by the user on 2026-08-05 (per plan Task 1); the P1 execution evidence is appended at Task 11, after which the slice may become `completed`.
- **Date:** 2026-08-05
- **Spec:** [spec.md](spec.md) — acceptance criteria section 5.2
- **Plan:** [plan.md](plan.md)

## Pre-approval dependency validation (KSP2 / Room / schema export)

Per the [P1 spec](spec.md) section 3, the concrete KSP2 version and its real-build compatibility evidence are established before plan approval. The following transient experiments ran in the working tree on top of commit `efca88b` (`docs: record P1 spec and ADR 0006 approval (2026-08-05)`) and were fully reverted after each round; they are not P1 implementation. A confirmation rerun on top of commit `651d1e4` reproduced the identical schema artifact, proving determinism.

Environment (all rounds): host Windows 11 Home China 10.0.26200 (x64), AMD Ryzen 7 8845H (8 cores), 31.3 GiB RAM; JDK Temurin 17.0.20+8 (user-scope install; the local `JAVA_HOME` path is not recorded); Gradle 9.5.0; AGP 9.3.0 built-in Kotlin 2.2.10; KSP 2.3.9; Room 2.8.4; compileSdk 36. Fixture version: probe fixture v1 (`ProbeEntity`/`ProbeDatabase` as quoted below).

### Temporary configuration (identical in every round)

Temporary additions to `gradle/libs.versions.toml` (reverted after the experiment):

```toml
[versions]
ksp = "2.3.9"

[libraries]
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
androidx-room = { id = "androidx.room", version.ref = "room" }
```

Temporary additions to the root `build.gradle.kts` (reverted):

```kotlin
alias(libs.plugins.ksp) apply false
alias(libs.plugins.androidx.room) apply false
```

Temporary `core/data/build.gradle.kts` (reverted): apply `libs.plugins.ksp` and `libs.plugins.androidx.room` to `:core:data`, add `room { schemaDirectory("$projectDir/schemas") }`, and add `implementation(libs.room.runtime)` + `ksp(libs.room.compiler)`. Round 1 additionally used `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` instead of the `androidx.room` plugin.

Temporary probe sources under `core/data/src/main/java/com/paherdin/record/core/data/room/` (reverted):

```kotlin
// ProbeEntity.kt
package com.paherdin.record.core.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ProbeEntity(
    @PrimaryKey val id: String,
)
```

```kotlin
// ProbeDatabase.kt
package com.paherdin.record.core.data.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ProbeEntity::class], version = 1, exportSchema = true)
abstract class ProbeDatabase : RoomDatabase()
```

### Round 1 — KSP 2.3.9 with the `ksp` arg schema route (finding)

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:assembleDebug` with the temporary configuration using `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` and no `androidx.room` plugin |
| Environment | Windows 11 Home China 10.0.26200; JDK Temurin 17.0.20+8; Gradle 9.5.0; AGP 9.3.0 built-in Kotlin 2.2.10; KSP 2.3.9; Room 2.8.4; compileSdk 36 |
| Result | PASS — `kspDebugKotlin` ran, `ProbeDatabase_Impl.kt` generated, `compileDebugKotlin` compiled it, `BUILD SUCCESSFUL`. FAIL — no `core/data/schemas` directory was created: under KSP2, the `ksp` `arg("room.schemaLocation", ...)` route does **not** export schema JSON. |

### Round 2 — `androidx.room` Gradle plugin schema export

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:clean :core:data:kspDebugKotlin` with the `androidx.room` Gradle plugin (2.8.4) and `room { schemaDirectory("$projectDir/schemas") }` replacing the `ksp` arg |
| Environment | Same as Round 1 |
| Result | PASS — `copyRoomSchemas` ran; schema JSON exported to `core/data/schemas/com.paherdin.record.core.data.room.ProbeDatabase/1.json` |

### Round 3 — full `assembleDebug` on the plugin variant

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:clean :core:data:assembleDebug` (same configuration as Round 2) |
| Environment | Same as Round 1 |
| Result | PASS — `BUILD SUCCESSFUL in 1m 19s`; task sequence `kspDebugKotlin` → `compileDebugKotlin` (generated `ProbeDatabase_Impl` compiled to `build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/paherdin/record/core/data/room/ProbeDatabase_Impl.class`) → `copyRoomSchemas` → `assembleDebug`; artifact `core/data/schemas/com.paherdin.record.core.data.room.ProbeDatabase/1.json` (formatVersion 1, databaseVersion 1, entity `ProbeEntity`) |

### Round 4 — determinism confirmation rerun

| Field | Recorded value |
|---|---|
| Command | Identical to Round 3, rerun on top of commit `651d1e4` |
| Environment | Same as Round 1 |
| Result | PASS — `BUILD SUCCESSFUL in 10s` (Gradle cache); identical task sequence; the schema artifact reproduced byte-identically. **Artifact SHA-256 (full 64 hex): `5cfd6eed085cafbb5a8ec1153ff08dfeb7a708334dc455b4d331045d6dd647c8`** |

### Conclusion

KSP 2.3.9 is compatible with AGP 9.3 built-in Kotlin 2.2.10 and Room 2.8.4, including successful compilation of Room-generated code. Schema export under KSP2 requires the `androidx.room` Gradle plugin; the `ksp` arg route does not work and is not used by the plan. The artifact is deterministic across reruns.

Remaining risk: none known for the pinned combination. The working tree was reverted after each round; committed history is unchanged.

## P1 execution record

All commands below ran on the host recorded in the pre-approval section (Windows 11 Home China 10.0.26200, AMD Ryzen 7 8845H, 31.3 GiB RAM). The shell default `java` is JRE 1.8.0_501 and `adb` is not on `PATH`; every Gradle invocation set `JAVA_HOME` explicitly to the user-scope Temurin 17.0.20+8 (path not recorded, per the pre-approval rule) and adb was invoked by absolute path under the Android SDK `platform-tools`. Reference device: Xiaomi 14 Pro (`23116PN5BC`, `shennong`), system build recorded at Task 10.

### Task 3 — `:core:model` enums

| Field | Recorded value |
|---|---|
| Command | `JAVA_HOME=<Temurin 17> .\gradlew.bat :core:model:build` |
| Environment | Host as above; Gradle 9.5.0 |
| Result | PASS — `BUILD SUCCESSFUL`; `EntityType` and `TransactionType` compiled with no extra members or serialization. |
| Remaining risk | None. |

### Task 4 — `:core:data` build setup (pinned versions)

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:assembleDebug` with KSP 2.3.9 + `androidx.room` plugin (2.8.4), `room { schemaDirectory(...) }`, `api(project(":core:model"))` |
| Environment | Host as above |
| Result | PASS — `BUILD SUCCESSFUL`; the pinned combination compiles the module with no KSP errors. |
| Remaining risk | None. |

### Task 5 — entities, database, schema export

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:assembleDebug`; `copyRoomSchemas` exported `core/data/schemas/com.paherdin.record.core.data.room.RecordDatabase/1.json` |
| Environment | Host as above |
| Result | PASS — schema JSON exported. A machine-readable inspection (python) confirmed the ADR 0006 contract: `databaseVersion 1`, `formatVersion 1`; exactly six tables (`EntityRef`, `Transaction`, `Account`, `AccountEntry`, `Note`, `TransactionNoteRelation`); `createdAt`/`updatedAt`/`deletedAt`/`amountMinor` INTEGER affinity; `entityType`/`type` TEXT affinity; every FK `NO ACTION`/`NO ACTION`; indexes exactly `AccountEntry(transactionId)`, `AccountEntry(accountId)`, `TransactionNoteRelation(noteId, transactionId)`. **Schema JSON SHA-256 (full 64 hex): `0e2ed86987d13b8c31667f35bc543a2b1bd7e694633df744a43ea379b2784d81`** |
| Remaining risk | None. |

### Task 6 — repository layer

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:compileDebugKotlin` |
| Environment | Host as above |
| Result | PASS. Implementation fixes during this task, all within the approved plan's design: (a) the table name `Transaction` is a SQLite keyword, so the DAO queries quote it (`` `Transaction` ``); (b) the `androidx.room.Transaction` annotation import shadowed the entity class in `RecordDao.kt` — the entity is imported aliased (`Transaction as TransactionEntity`); (c) the nested `RepositoryResult.*` outcome members are referenced by explicit import in the repository. The public surface is unchanged by these fixes. |
| Remaining risk | None. |

### Task 7–8 — test sources compile

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:assembleDebugAndroidTest :core:data:compileDebugUnitTestKotlin` |
| Environment | Host as above |
| Result | PASS — all six instrumented test classes and the JVM validator test compile. |
| Remaining risk | None. |

### Task 9 — projection write ban (machine checks)

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:testDebugUnitTest :core:data:apiBoundaryCheck` |
| Environment | Host as above |
| Result | PASS. `ApiSurfaceTest` (kotlin-metadata-jvm 2.2.10): the internal-assertion test and the allowlist-exactness test pass; `expected-surface.txt` (26 lines, generated by the test's dump mode and reviewed one-to-one against the plan's normative declaration list; **SHA-256: `81bd1cf913e08d294656c47512c080c3b12f8c5a608a808c649c0e00d0cec686`**) matches the compiled public surface in both directions; no `SearchDocument`/`Timeline` anywhere. `apiBoundaryCheck`: `PASS (2 positive, 16 negative fixtures)` — every negative fixture fails with internal-visibility or unresolved-reference diagnostics in its own compiler invocation. |
| Remaining risk | None. |

API-version notes (kotlin-metadata-jvm 2.2.10, recorded so later slices do not rediscover them): the 2.x API uses `KmVariance` (no `STAR` member — star projections are `KmTypeProjection.STAR`), class-kind/visibility/data/sealed are extension properties (`kind`, `visibility`, `isData`; sealed is detected via non-empty `sealedSubclasses`), file facades are `KotlinClassMetadata.FileFacade` with `kmPackage.functions`, and type-parameter classifiers carry only an `id` (names resolve from the enclosing declaration). AGP 9 exposes the module's main classes to unit tests as a jar (`bundleLibRuntimeToJarDebug/classes.jar`), so `ApiSurfaceTest` locates its classpath element via `java.class.path` and extracts the jar; the pinned Room-generated exclusion set covers the `_Impl` classes and their `$`-nested companions (the companion carries Room's generated `getRequiredConverters`).

Negative verification (both directions, executed then reverted; reverted state re-verified PASS):

| Direction | Experiment | Observed |
|---|---|---|
| (i) internal visibility widened | `RecordDao` made public | Build fails at compile: the generated `RecordDao_Impl` reports `'public' function exposes its 'internal' parameter type` for each entity — the surface check catches widening even before the boundary task runs. |
| (i) internal visibility widened (designed path) | `XorInvariantValidator` made public (its signature uses only public types) | `apiBoundaryCheck` FAILED: `NEGATIVE fixture must fail ... ReferenceXorInvariantValidator.kt (exit=0)`; after revert, PASS. |
| (ii) new public method | `fun touch(): Unit` added to `RecordRepository` | `ApiSurfaceTest` FAILED: public declarations differ from `expected-surface.txt`; after revert, PASS. |

Task-implementation fixes during this task: the `LibraryExtension` DSL type is not resolvable in AGP 9 (its `sdkDirectory`/`compileSdk` are not exposed), so `apiBoundaryCheck` reads `sdk.dir` from `local.properties` and uses the declared `android-36` jar with an existence check; the K2 compiler CLI prints lowercase `unresolved reference` diagnostics (the check is case-insensitive); `val _ =` is an experimental Kotlin 2.2 feature and was removed from the positive fixtures; the module classes dir was added as a declared task input after the first negative-verification run surfaced a cache blind spot.

### Task 10 Step 1 — clean full check

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat clean check` |
| Environment | Host as above |
| Result | PASS — exit 0; `checkModuleDependencies: PASS (7 modules, graph conforms)`; lint 0 errors; all JVM unit tests (`XorInvariantValidatorTest`, `SchemaContractTest`, `ApiSurfaceTest`) PASS; `:core:data:apiBoundaryCheck` PASS. |
| Remaining risk | None. |

### Task 10 Step 2 — instrumented suite on the reference device

Run 1 (device attached) — FAILED: `saveTransactionAggregateFaultMatrix` crashed with `HookFaultException` escaping the repository. Root cause: `hook.beforeTransaction()` was invoked outside the `try`/catch chain in the four write methods, so a before-transaction fault bypassed the boundary mapping. Fix: the hook call moved inside the `try` in `createAccount`, `saveTransactionAggregate`, `saveNoteAggregate`, `replaceTransactionNoteRelations` (catch-priority clause 4 now covers it). 

Run 2 (device attached) — 3 failures, all in `AtomicVisibilityTest`: `expected:<TransactionFailed> but was:<kotlin.Unit>`. Root cause: the matrix runner declared `write: suspend (RecordRepository) -> Unit`, which coerced each write lambda's `RepositoryResult` result to `Unit`; the writer coroutine returned `Unit` instead of the typed outcome. Fix: the parameter type is `suspend (RecordRepository) -> RepositoryResult<Unit>`. All other device tests (PersistReloadTest, XorIdentityTest, ForeignKeyTest, TypedEndpointTest, ConcurrencyTest) passed on run 2.

Run 3 (device reconnected) — **PASS**:

| Field | Recorded value |
|---|---|
| Command | `JAVA_HOME=<Temurin 17> .\gradlew.bat :core:data:connectedDebugAndroidTest` |
| Environment | Host as above; device `23116PN5BC` (Xiaomi 14 Pro, `shennong`), Android 16 / API 36, build `BP2A.250605.031.A3` (recorded, not rejected — the same rule as P0; `minSdk 34` keeps the app compatible) |
| Result | PASS — `BUILD SUCCESSFUL in 4m 52s`; all 19 instrumented test methods PASS: `PersistReloadTest` (4, incl. the typed `open()` boundary cases), `XorIdentityTest` (4), `ForeignKeyTest` (2), `TypedEndpointTest` (4), `AtomicVisibilityTest` (3 fault matrices: `saveTransactionAggregate` 8 cases, `saveNoteAggregate` 7 cases, `replaceTransactionNoteRelations` 6 cases — every stage/ordinal with external-reader evidence per the plan's state matrix), `ConcurrencyTest` (2). |
| Remaining risk | None. |

### Task 10 Step 3 — P0 launch regression

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :app:installDebug`; `adb shell am start -n com.paherdin.record/.MainActivity`; `adb shell dumpsys activity activities` |
| Environment | Same device as above |
| Result | PASS — `Installed on 1 device`, `BUILD SUCCESSFUL`; after `am start`, `topResumedActivity=ActivityRecord{... u0 com.paherdin.record/.MainActivity}`. (First check after the install showed the app's task visible with its process running but the phone's Settings app on top — the device had been in use; a second `am start` brought `MainActivity` to the top, confirming the launch.) |
| Remaining risk | None. |

### Remaining risk (as of this writing)

- None blocking. All eight acceptance criteria of spec section 5.2 have evidence: criteria 1–6 from the instrumented suite (run 3), criterion 7 from `SchemaContractTest` + `ApiSurfaceTest` + `apiBoundaryCheck` with both negative-verification directions, criterion 8 from `clean check` (run of Task 10 Step 1) and the launch regression above.
- Known non-blocking items: the reference device runs API 36 (recorded, not rejected); the local `JAVA_HOME` path remains deliberately unrecorded; two implementation root causes found during device-test runs 1–2 are fixed and documented above.

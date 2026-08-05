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

To be completed at Task 11 with the executed P1 verification commands, environment, results, deviations, and remaining risk, mapping every spec acceptance criterion (spec section 5.2, items 1–8) and the roadmap P1 exit gate.

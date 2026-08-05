# Tech PoC P1 — Verification Record

- **Slice state:** `approved` — the pre-approval dependency validation is recorded below; the P1 execution evidence is appended at Task 10, after which the slice may become `completed`.
- **Date:** 2026-08-05
- **Spec:** [spec.md](spec.md) — acceptance criteria section 5.2
- **Plan:** [plan.md](plan.md)

## Pre-approval dependency validation (KSP2 / Room / schema export)

Per the [P1 spec](spec.md) section 3, the concrete KSP2 version and its real-build compatibility evidence are established before plan approval. The following transient experiments ran in the working tree and were fully reverted; they are not P1 implementation. All commands use `JAVA_HOME=C:\Users\p3148\tools\jdk\jdk-17.0.20+8`.

### Round 1 — KSP 2.3.9 with the `ksp` arg schema route (finding)

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:assembleDebug` after temporarily applying KSP 2.3.9 + Room 2.8.4 + probe entity/database to `:core:data` with `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` |
| Environment | Windows 11 Home China 10.0.26200; Gradle 9.5.0; AGP 9.3.0 built-in Kotlin 2.2.10; KSP 2.3.9; Room 2.8.4; compileSdk 36 |
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
| Result | PASS — `BUILD SUCCESSFUL in 1m 19s`; task sequence `kspDebugKotlin` → `compileDebugKotlin` (generated `ProbeDatabase_Impl` compiled to `build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/paherdin/record/core/data/room/ProbeDatabase_Impl.class`) → `copyRoomSchemas` → `assembleDebug`; artifact `core/data/schemas/com.paherdin.record.core.data.room.ProbeDatabase/1.json` (formatVersion 1, databaseVersion 1, entity `ProbeEntity`, SHA-256 `5cfd6eed085cafbb…`) |

### Conclusion

KSP 2.3.9 is compatible with AGP 9.3 built-in Kotlin 2.2.10 and Room 2.8.4, including successful compilation of Room-generated code. Schema export under KSP2 requires the `androidx.room` Gradle plugin; the `ksp` arg route does not work and is not used by the plan.

Remaining risk: none known for the pinned combination. The working tree was reverted after each round; committed history is unchanged.

## P1 execution record

To be completed at Task 10 with the executed P1 verification commands, environment, results, deviations, and remaining risk, mapping every spec acceptance criterion (spec section 5.2, items 1–8) and the roadmap P1 exit gate.

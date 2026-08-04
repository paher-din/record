# Tech PoC P0 — Engineering Skeleton

- **Status:** `in-progress`
- **Spec approved:** 2026-08-04 — the user accepted the boundaries and acceptance criteria and confirmed the reference environment is a physical Xiaomi 14 Pro.
- **Plan approved:** 2026-08-04 — the user approved the implementation plan; this slice is the project's single active specification.
- **Drafted:** 2026-08-04
- **Roadmap:** [Tech PoC P0](../../../roadmap.md)
- **Module boundaries:** [Architecture overview](../../architecture/overview.md)
- **Engineering discipline:** [Engineering conventions](../../engineering/conventions.md)

## 1. Purpose

Prove that the approved seven-module Android 14+ project skeleton is buildable, launchable on the approved reference environment, and that its module dependency graph can be enforced automatically. Passing P0 gives P1–P6 a verified foundation and makes the approved dependency direction a machine-checked invariant instead of a manual convention.

## 2. Reference environment

The reference environment is frozen and recorded at spec approval. It is part of the P0 gate: building or launching on a different configuration makes the run invalid and requires an explicit approval record of a new baseline.

| Field | Recorded value |
|---|---|
| Device | Xiaomi 14 Pro (physical device, USB debugging) |
| Android baseline | Android 14 / API 34 (out-of-box baseline) |
| Actual system build | Recorded in `verification.md` at P0 verification (e.g. SDK level and build id); a system that was upgraded past API 34 is recorded, not rejected, because `minSdk 34` keeps the app compatible |
| AVD / host CPU-RAM fields | Not applicable — physical device reference |

## 3. Build baseline

- `minSdk = 34` (supported floor) and `targetSdk = 34` (API 34 reference behavior). `compileSdk` follows the toolchain requirement (36 for the approved AGP 9.3 baseline); compileSdk is a build-time setting and does not change the runtime reference environment.
- All external dependency and plugin versions are centralized in a version catalog (`gradle/libs.versions.toml`); module build files reference aliases only.
- Room 2.8.4 is the approved persistence baseline (ADR 0001) and must be declared in the version catalog even though no Room code exists in P0.
- Concrete toolchain versions (Gradle, AGP, Kotlin, Compose BOM, coroutines, test libraries) are recorded at plan approval with the dependency-review evidence required by the engineering conventions (version, license, maintenance evidence, compatibility impact, removal path). No version is added without that record.
- The app declares no `INTERNET` permission or other network capability.

## 4. Module graph

Exactly seven Gradle modules are registered in `settings.gradle.kts`:

- `:app`
- `:feature:finance`, `:feature:notes`, `:feature:timeline`
- `:core:model`, `:core:data`, `:core:ui`

The allowed project-module dependency edges are exactly the arrows of the approved graph in the [architecture overview](../../architecture/overview.md):

| Module | May depend on |
|---|---|
| `:app` | `:feature:finance`, `:feature:notes`, `:feature:timeline`, `:core:data`, `:core:ui` |
| `:feature:finance` | `:core:model`, `:core:data`, `:core:ui` |
| `:feature:notes` | `:core:model`, `:core:data`, `:core:ui` |
| `:feature:timeline` | `:core:model`, `:core:data`, `:core:ui` |
| `:core:data` | `:core:model` |
| `:core:ui` | `:core:model` |
| `:core:model` | no other project module |

No feature-to-feature edge, no core-to-feature edge, and no edge outside this table may exist. A dependency that is only transitive does not count as a direct edge.

## 5. Skeleton contents

P0 creates only the structural skeleton:

- Gradle wrapper, root and module build files, version catalog, `settings.gradle.kts` including exactly the seven modules.
- Minimal `:app` entry: an `Application` class and one placeholder `MainActivity` that launches and displays a skeleton status surface. No navigation, no bottom bar, no theme system, no business state.
- Android manifest baseline in `:app`: no `INTERNET` permission; `android:allowBackup="false"`; API 34 `dataExtractionRules` and compatible `fullBackupContent` exclusion files present (empty exclusion lists are valid for a skeleton with no user-data domains). Source/merged/final-APK policy inspection is exercised at the M1 gate, not P0.
- An automated module dependency check, wired into the default `check` task, that fails the build on any violation.
- Minimal smoke verification that the app launches on the reference environment.

Feature and core modules contain no domain types, no public interfaces, no repository/DAO/ViewModel code, and no speculative abstractions.

## 6. Acceptance criteria

The slice passes only when all of the following have evidence in `verification.md`:

1. **Module set:** the dependency check reports exactly `:app`, the three feature modules, and the three core modules.
2. **Graph conformance:** every direct project-module dependency edge is inside the table in section 4; the check passes and is part of the default build.
3. **Negative verification:** temporarily adding a violating edge (e.g. `:feature:finance` → `:feature:notes`) makes the check fail with a clear message, then the temporary edge is removed and the check passes again. Both states are recorded.
4. **Build:** the approved build tasks succeed on the frozen reference configuration with the recorded toolchain versions.
5. **Launch:** the app installs and launches on the reference environment; the placeholder `MainActivity` is visible (recorded via `adb` commands and output).
6. **Manifest baseline:** source manifest declares no `INTERNET` permission and sets `android:allowBackup="false"`; the two exclusion rule files exist.
7. **Version catalog:** every external dependency is declared once in the catalog with the dependency-review record from section 3.
8. **No leakage:** no business feature, persistence model, navigation, DI framework, or speculative interface was introduced.

## 7. Non-goals

P0 explicitly does not:

- Implement any Room schema, DAO, entity, repository, ViewModel, use case, or projection (P1+).
- Introduce navigation, bottom navigation, theme system, DI framework, or a shared UI state pattern (M1).
- Register or implement any WorkManager worker (first maintenance slice).
- Implement backup, restore, migration, attachments, search, or any manifest network change.
- Change any core document fact. If implementation exposes a conflict with a core document, the documentation-first protocol applies before any code change.

## 8. Dependency review boundary

Every dependency introduced by P0 is rejected when its license is incompatible, its maintenance status is unknown, it requires excessive permissions, it introduces undisclosed network behavior, or no practical removal path exists, per the engineering conventions. No ads, analytics, crash-reporting, or closed-source tracking SDK. AndroidX and Kotlin official libraries are preferred.

## 9. Risks

- Toolchain combination (Gradle / AGP / Kotlin / Compose) compatibility must be verified at plan approval; an incompatible pair is the most likely P0 failure.
- First build downloads dependencies (build-time network, not an app permission).
- The frozen reference environment changes require an explicit approval record.

## 10. Lifecycle path

- `draft` (this document): behavior, boundaries, and acceptance are clarified here. No implementation.
- `approved`: the user accepts this spec and the reference environment; the approval date/context is recorded.
- `in-progress`: the user approves the plan (which records exact tasks, file changes, and verification commands); implementation may begin.
- `completed`: acceptance criteria and the roadmap P0 gate have verification evidence; P1 may then start.

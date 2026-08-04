# Tech PoC P0 — Verification Record

- **Slice state:** `in-progress`, verification complete; awaiting user acceptance before `completed`.
- **Date:** 2026-08-05
- **Spec:** [spec.md](spec.md) — acceptance criteria section 6
- **Plan:** [plan.md](plan.md)

## Environment

| Field | Recorded value |
|---|---|
| Host | Windows 11 Home China 10.0.26200 (x64) |
| JDK | Temurin 17.0.20+8 (`C:\Users\p3148\tools\jdk\jdk-17.0.20+8`) |
| Gradle | 9.5.0 (wrapper; distribution `gradle-9.5.0-bin.zip`) |
| AGP | 9.3.0 (built-in Kotlin, KGP 2.2.10) |
| Kotlin / Compose compiler plugin | 2.2.10 |
| Compose BOM | 2026.06.00 |
| `androidx.activity:activity-compose` | 1.13.0 (not in BOM) |
| Room | 2.8.4 (catalog declaration only) |
| Android SDK | `C:\Users\p3148\AppData\Local\Android\Sdk`; platforms `android-36` (installed by AGP during build) |
| Reference device | Xiaomi 14 Pro (`23116PN5BC`, shennong), USB debugging |
| Device system | Android 16 / API 36, build `BP2A.250605.031.A3` — upgraded past API 34; recorded per spec, not rejected (`minSdk 34` keeps compatibility) |
| Build-time network | Clash proxy `127.0.0.1:32916` (JVM `GRADLE_OPTS` proxy properties) |

## Acceptance criteria evidence

| # | Criterion | Command | Result |
|---|---|---|---|
| 1 | Module set exactly seven | `./gradlew projects` | PASS — `:app`, `:feature:finance`, `:feature:notes`, `:feature:timeline`, `:core:model`, `:core:data`, `:core:ui` |
| 2 | Graph conformance, wired into default build | `./gradlew clean check` | PASS — `checkModuleDependencies: PASS (7 modules, graph conforms)`; task executes as part of `check` |
| 3 | Negative verification | temporary `implementation(project(":feature:notes"))` in `:feature:finance` | PASS — build failed: `Illegal project dependency edges from :feature:finance: [:feature:notes]`; after revert, `checkModuleDependencies: PASS` and `BUILD SUCCESSFUL` |
| 4 | Build on frozen reference configuration | `./gradlew clean check` | PASS — `BUILD SUCCESSFUL in 1m 31s`; 219 tasks; lint: 0 errors, 9 version-advice warnings (OldTargetApi by design, MissingApplicationIcon by skeleton scope, newer-version notices) |
| 5 | Launch on reference device | `./gradlew :app:installDebug`; `adb shell am start -n com.paherdin.record/.MainActivity`; `adb shell dumpsys activity activities` | PASS — `Installed on 1 device`; `topResumedActivity=ActivityRecord{... com.paherdin.record/.MainActivity}`; screenshot `C:\Users\p3148\tools\p0-skeleton.png` shows "P0 engineering skeleton" |
| 6 | Manifest baseline | source manifest inspection | PASS — no `<uses-permission>` at all (no `INTERNET`); `android:allowBackup="false"`; `data_extraction_rules.xml` and `backup_rules.xml` present and referenced |
| 7 | Version catalog with review record | `gradle/libs.versions.toml` | PASS — every external dependency declared once; dependency review record in [plan.md](plan.md) |
| 8 | No leakage | file inventory | PASS — no domain types, persistence, navigation, DI, WorkManager, or speculative interfaces; feature/core modules have no source files |

## Deviations from plan (all recorded, none change spec facts)

1. **Gradle 9 implicit container projects.** `include(":core:model")` auto-registers parent container projects `:core` and `:feature` (no build scripts). The dependency check counts only projects with a build file as modules.
2. **Root project self-inclusion.** The root project has a build script; the check excludes it explicitly.
3. **AGP 9 built-in Kotlin self-edges.** AGP injects self `ProjectDependency` entries into generated test compile/runtime configurations (`debugUnitTestCompileClasspath` etc.). The check inspects only developer-declared configurations (`implementation`, `api`, `compileOnly`, `runtimeOnly`, `testImplementation`, `androidTestImplementation`, `debugImplementation`, `releaseImplementation`) — a centrally maintained list in `build.gradle.kts`.
4. **Compose BOM requires `platform()`.** Direct `implementation(libs.compose.bom)` did not apply version constraints; fixed with `implementation(platform(libs.compose.bom))`.
5. **`activity-compose` not covered by BOM 2026.06.00.** Declared explicitly at 1.13.0 in the version catalog.
6. **`local.properties` lint escape.** Windows `sdk.dir` path needs `C\:\\Users\\...` escaping (lint `PropertyEscape`).
7. **Toolchain provisioning.** JDK 17 and Gradle 9.5.0 were installed under `C:\Users\p3148\tools\` (user scope, no admin); all Gradle runs use JVM proxy properties for the user's Clash proxy. Not repository content.
8. **Reference device is API 36.** Recorded per spec's reference-environment rule; not a rejection.

## Remaining risk

- None known for the P0 gate. Lint version-advice warnings (newer AGP 9.3.1, Kotlin 2.4.10, Gradle 9.6.1, Compose BOM 2026.06.01) are catalog maintenance items for later slices, not P0 blockers.
- Target SDK 34 remains the approved reference behavior; device runs Android 16, so `OldTargetApi` compatibility modes apply on this device by design.

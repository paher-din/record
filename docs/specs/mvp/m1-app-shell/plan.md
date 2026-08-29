# MVP M1 App Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved product-grade M1 Android shell with safe startup gating, typed four-tab navigation, retained state, A/A1 theme, accessible shared presentations, isolated startup benchmarks, and complete offline/artifact evidence.

**Architecture:** `:app` remains the composition root and owns route keys, startup-to-shell routing, and global chrome; each feature owns only its M1 screen content; `:core:ui` owns theme, phase, presentation, icons, and chrome policy; `:core:data` exposes one narrow startup coordinator over the unchanged P5 recovery protocol. The exact seven production modules remain unchanged, features never depend on one another, and the existing `:benchmark` project remains the only test-tool project.

**Tech Stack:** AGP 9.3.0 built-in Kotlin 2.2.10, Kotlin Serialization 2.2.10 / runtime 1.9.0, Navigation Compose 2.9.8, Lifecycle Runtime Compose 2.10.0, Compose BOM 2026.06.00 / Material 3, Room 2.8.4 unchanged, AndroidX Benchmark 1.4.1, JUnit4, AndroidX Test, API 34+.

---

- **Status:** `in-progress` — approved 2026-08-29 under the user's prior independent-review/approval delegation after ADR 0016 acceptance and core-document synchronization removed the last prerequisite. Execution starts at M1-T01; actual state/evidence lives in `verification.md`.
- **Drafted:** 2026-08-28
- **Independent plan review:** 2026-08-28 — user context: “你来把审阅这个plan吧，如果有问题就修复，没有问题可以自行批准”. Codex rechecked spec criteria 1–19, task/write-scope coverage, task dependencies, all 13 production routes, all 13 `Phase` tokens, startup progress transitions, destructive boundaries, type signatures, runnable commands, links, placeholders, and Git state. The plan itself had no remaining technical review finding; approval waited only for the now-satisfied ADR/core-doc prerequisite.
- **Review corrections:** concurrent startup callers now coalesce on one attempt/result; Ready and non-retryable terminal results are cached safely; detail route constructors validate canonical IDs during deserialization; benchmark identity uses runtime package + manifest metadata/signing digest rather than assuming `BuildConfig`; feature boundary scans handle the expected no-match exit code; and startup evidence paths/commands are deterministic and bounded.
- **Spec:** [MVP M1 — App Shell](spec.md) — approved 2026-08-28 and `in-progress` from 2026-08-29.
- **Plan approved:** 2026-08-29 — the user delegated independent correction/approval on 2026-08-28 and explicitly accepted ADR 0016 on 2026-08-29; the synchronized core-doc checks passed before this status change.
- **Architecture prerequisite:** satisfied 2026-08-29 — accepted [ADR 0016](../../../architecture/decisions/0016-application-facing-startup-gate-handoff.md) plus synchronized `docs/architecture/overview.md`, `docs/architecture/data-and-storage.md`, interaction, conventions, and security facts.
- **Execution revision:** `task_revision = 1` for every initial task. Any accepted behavior/criterion change increments the affected task revision and requires spec/plan reapproval.
- **Reference environment:** physical Xiaomi 14 Pro; actual Android build/API/CPU/RAM recorded at execution. Device was disconnected during plan drafting.
- **Destructive redline:** Task M1-T10 may clear only `com.paherdin.record.benchmark`, and only after a separate execution-time explanation and explicit user deletion approval. Plan approval is not deletion approval.
- **Schema boundary:** no schema, Room Entity/DAO, database version, migration, backup format, recovery-record shape, or production generation layout change is authorized.

## Plan-approval documentation prerequisite (satisfied 2026-08-29)

The following prerequisite is complete:

1. `docs/architecture/decisions/0016-application-facing-startup-gate-handoff.md` records the synchronous-open limitation, rejected alternatives, selected single-owner coordinator, public outcome/error/privacy surface, idempotent Retry, no-persistence impact, and compatibility/removal path; the user accepted it on 2026-08-29.
2. `docs/architecture/overview.md`, `docs/architecture/data-and-storage.md`, `docs/experience/interaction-rules.md`, `docs/engineering/conventions.md`, and `docs/engineering/security-and-privacy.md` are synchronized to the accepted decision.
3. `spec.md` and this plan use the accepted `Checking → RecoveryDetected → Recovering` contract and matching public type shape; no behavior or acceptance revision was required.
4. Plan approval and unique `in-progress` lifecycle metadata are recorded before functional dispatch.

## Execution and evidence rules

- The main agent follows [agentic-workflow.md](../../../engineering/agentic-workflow.md): it owns task order, dispatch contracts, `verification.md`, raw-evidence acceptance, integration, and combined gates; it does not write functional implementation.
- Every worker is not alone in the worktree. It edits only its assigned paths, never reverts or formats another owner's work, never stages unrelated files, and stops on overlap, frozen-decision conflict, permission redline, schema pressure, or required out-of-scope file.
- Before each dispatch, the main agent writes the exact `task_id`, `task_revision`, `attempt_id`, owner, write scope, dependencies, ready evidence, commands, and acceptance rows into `verification.md`. Workers do not edit its Execution pointer, Task log, or acceptance table.
- All Gradle commands use the known JDK directly, without mutating `PATH`, `JAVA_HOME`, `.env`, or `gradle.properties`:

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain check --console=plain
```

- Device commands use `C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Multi-minute suites run in the background and are polled; cancelled/timed-out runs are incomplete evidence.
- Each implementation task uses test-first steps and one focused commit. A worker reports `DONE`, `DONE_WITH_CONCERNS`, `NEEDS_CONTEXT`, `BLOCKED`, or `FAILED` with commands, exit codes, test counts, changed files, and raw artifact paths.

## Task and dependency map

| Task | Owner/write scope | Depends on | Supports criteria |
|---|---|---|---|
| M1-T00 | main agent; lifecycle docs + `verification.md` | accepted ADR + approved plan | 1, 19 |
| M1-T01 | build catalog/scripts + build files + benchmark target constant | T00 | 2, 14, 15, 17, 18 |
| M1-T02 | `:core:ui` | T01 | 3–6, 10 |
| M1-T03 | `:core:data` startup boundary/API evidence | T01, accepted ADR | 1, 13, 18 |
| M1-T04 | `:feature:finance` | T02 | 5, 8–10, 16, 18 |
| M1-T05 | `:feature:notes` | T02 | 5, 8–10, 16, 18 |
| M1-T06 | `:feature:timeline` | T02 | 5, 9, 11, 12, 16, 18 |
| M1-T07 | `:app` shell/startup/navigation/resources | T03–T06 | 3–13, 16–18 |
| M1-T08 | app/core/feature UI-navigation-accessibility tests | T07 | 3–12, 16, 18 |
| M1-T09 | startup fixture/harness/report validator code | T03, T07 | 13, 14, 17, 18 |
| M1-T10 | device startup runs and retained raw evidence | T09 + deletion approval | 13, 14, 16, 19 |
| M1-T11 | main-agent integration, artifact/offline/combined gates, lifecycle close | T08, T10 | 1–19 |

M1-T04, M1-T05, and M1-T06 may run concurrently with separate full dispatch contracts. Every other task runs serially because it changes shared build/API/navigation/evidence state or depends on unfinished output.

## Planned file map

### Shared/build

- Modify `gradle/libs.versions.toml`: approved navigation, serialization, lifecycle, Compose-test aliases.
- Modify root `build.gradle.kts`: serialization plugin declaration only.
- Modify `app/build.gradle.kts`, `core/ui/build.gradle.kts`, and three feature build files: Compose/test dependencies and benchmark application-ID isolation.
- Modify `benchmark/build.gradle.kts`: Lifecycle 2.10.0 alignment.
- Modify `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineSentinelBridge.kt`: isolated target package constant.
- Create `tools/verify-m1-artifacts.ps1`: bounded source/merged/APK/rule/dependency policy verification.

### `:core:ui`

- Create `core/ui/src/main/java/com/paherdin/record/core/ui/Phase.kt`.
- Create `core/ui/src/main/java/com/paherdin/record/core/ui/ContentPresentation.kt`.
- Create `core/ui/src/main/java/com/paherdin/record/core/ui/ShellChromePolicy.kt`.
- Create `core/ui/src/main/java/com/paherdin/record/core/ui/RecordIcons.kt`.
- Create `core/ui/src/main/java/com/paherdin/record/core/ui/theme/RecordTheme.kt`, `RecordPalette.kt`, `RecordTokens.kt`.
- Create `core/ui/src/main/java/com/paherdin/record/core/ui/SharedContent.kt` and `core/ui/src/main/res/values/strings.xml`.
- Create focused unit/android tests under matching `core/ui/src/test/...` and `core/ui/src/androidTest/...` packages.

### `:core:data`

- Create `core/data/src/main/java/com/paherdin/record/core/data/RecordStartup.kt`.
- Create internal `core/data/src/main/java/com/paherdin/record/core/data/StartupEngine.kt`.
- Modify `RecordRepository.kt` to delegate existing blocking compatibility entry to the same engine.
- Modify `core/data/api/expected-surface.txt`, `ApiSurfaceTest.kt`, and API guard fixtures.
- Create pure/device startup coordinator tests; preserve all committed Room schema JSON byte-for-byte.

### Feature modules

- Finance: create `FinanceUiModel.kt`, `FinanceScreens.kt`, strings, and tests.
- Notes: create `NotesUiModel.kt`, `NotesScreens.kt`, strings, and tests.
- Timeline: create `TimelineUiModel.kt`, `TimelineDateState.kt`, `TimelineScreens.kt`, strings, and tests.

### `:app`

- Create `navigation/M1Routes.kt`, `navigation/RecordNavHost.kt`, `startup/StartupStateStore.kt`, `ui/RecordShell.kt`, `ui/SettingsScreen.kt`.
- Modify `MainActivity.kt`, `RecordApplication.kt`, and `AndroidManifest.xml`.
- Create `res/values/{strings,colors,themes}.xml`, adaptive foreground/background/monochrome vector resources, and `mipmap-anydpi-v26` icon XML.
- Create route/store/shell tests under `app/src/test` and `app/src/androidTest`.

### Benchmark/evidence

- Create `core/data/src/testFixtures/kotlin/.../startup/M1StartupFixtureGenerator.kt` and pure tests.
- Create benchmark-only setup/sentinel code and manifest declaration in `app/src/benchmark`.
- Create `benchmark/.../M1StartupBenchmark.kt`, harness gates/tests, and report model.
- Create `core/data/src/test/kotlin/.../startup/M1StartupReportValidator.kt`; add `validateM1StartupReports` task.
- Create `docs/specs/mvp/m1-app-shell/verification.md` at activation; raw generated artifacts remain under `build/` or approved local evidence paths and are referenced, not pasted.

---

### Task M1-T00: Activate the approved slice and initialize durable execution state

**Owner:** main agent only. **Dispatch tier:** not dispatched; lifecycle/integration work.

**Files:**
- Modify: `docs/specs/mvp/m1-app-shell/spec.md`
- Modify: `docs/specs/mvp/m1-app-shell/plan.md`
- Create: `docs/specs/mvp/m1-app-shell/verification.md`
- Modify: `AGENTS.md`
- Modify: `docs/index.md`
- Modify: `docs/roadmap.md`

- [x] **Step 1: Verify approvals and architecture prerequisite**

Read the accepted ADR 0016, synchronized core documents, spec approval, and explicit plan approval. Assert zero other `in-progress` specs. Expected: every prerequisite recorded; otherwise M1 remains `approved` and execution stops.

- [x] **Step 2: Write the verification skeleton**

Create `verification.md` with this exact top-level structure:

```markdown
# MVP M1 — App Shell Verification

## Execution pointer
| Field | Value |
|---|---|
| Current task | M1-T01 |
| task_revision / attempt_id | 1 / M1-T01-A1 |
| Owner / write scope | pending dispatch |
| Dependencies / ready evidence | ADR 0016 accepted; plan approved |
| Status / dispatch failures | pending / 0 |
| Blocked on | none |
| Next command | dependency compatibility preflight |
| Last raw evidence | none |

## Acceptance criteria
| ID | Criterion | Status | Raw evidence |
|---:|---|---|---|
| 1 | Lifecycle and architecture prerequisite | pending | none |
| 2 | Module boundary | pending | none |
| 3 | Theme/localization/launch identity | pending | none |
| 4 | Accessibility | pending | none |
| 5 | Shared presentation | pending | none |
| 6 | Operation phases | pending | none |
| 7 | Default shell | pending | none |
| 8 | Global actions | pending | none |
| 9 | Typed/source-owned routing | pending | none |
| 10 | Chrome rules | pending | none |
| 11 | Independent retention | pending | none |
| 12 | Timeline date | pending | none |
| 13 | Safe startup | pending | none |
| 14 | Startup budgets and isolation | pending | none |
| 15 | Dependency review | pending | none |
| 16 | Offline routes | pending | none |
| 17 | Network/system-backup policy | pending | none |
| 18 | No premature domain work | pending | none |
| 19 | Verification record | pending | none |

## Task log
| Task | Revision | Attempt | Owner | State | Accepted | Evidence |
|---|---:|---|---|---|---|---|

## Commands and results

## Deviations and remaining risk
```

- [x] **Step 3: Move lifecycle state to active**

Set spec/plan to `in-progress`, record both approval contexts, and update the three routers to name M1 as the unique active spec. Do not change behavior or acceptance text.

- [x] **Step 4: Validate and commit lifecycle activation**

Run link/status scans plus `git diff --check`. Expected: exactly one `in-progress` spec and clean checks.

```powershell
git add AGENTS.md docs/index.md docs/roadmap.md docs/specs/mvp/m1-app-shell
git commit -m "docs: start MVP M1 app shell execution"
```

---

### Task M1-T01: Prove the dependency/build baseline and isolate the benchmark package

**Owner:** one build worker. **Dispatch tier:** full; shared build interfaces and multiple writable slices. **Depends on:** M1-T00.

**Files:**
- Modify: `gradle/libs.versions.toml`, root `build.gradle.kts`
- Modify: all five Android production module build files that need Compose/tests
- Modify: `benchmark/build.gradle.kts`
- Modify: `benchmark/.../TimelineSentinelBridge.kt`
- Create: `app/src/test/java/com/paherdin/record/compat/SerializationPluginSmokeTest.kt`

- [ ] **Step 1: Verify reference device without mutation**

```powershell
& 'C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
```

Expected: exactly the approved Xiaomi device is `device`. Empty/unauthorized output is `BLOCKED`, not a build failure.

- [ ] **Step 2: Write the failing serialization smoke test**

```kotlin
package com.paherdin.record.compat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializationPluginSmokeTest {
    @Serializable
    private data class Probe(val id: String)

    @Test fun serializableRouteCompilerPluginWorksWithBuiltInKotlin() {
        val encoded = Json.encodeToString(Probe.serializer(), Probe("probe"))
        assertEquals(Probe("probe"), Json.decodeFromString(Probe.serializer(), encoded))
    }
}
```

- [ ] **Step 3: Run the test and retain the expected failure**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest --tests com.paherdin.record.compat.SerializationPluginSmokeTest --console=plain
```

Expected: FAIL because serialization plugin/runtime and test setup are not yet declared.

- [ ] **Step 4: Apply only the approved catalog/build delta**

Add exact catalog entries for Navigation 2.9.8, Serialization 1.9.0/plugin 2.2.10, Lifecycle 2.10.0, Compose Foundation/test artifacts, and aliases:

```toml
[versions]
navigation = "2.9.8"
serialization = "1.9.0"
lifecycle = "2.10.0"

[libraries]
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
navigation-testing = { group = "androidx.navigation", name = "navigation-testing", version.ref = "navigation" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
lifecycle-runtime = { group = "androidx.lifecycle", name = "lifecycle-runtime", version.ref = "lifecycle" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
compose-foundation = { group = "androidx.compose.foundation", name = "foundation" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }

[plugins]
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

Merge these keys into the existing single TOML sections rather than duplicating section headers. Apply Compose compiler to `:core:ui` and each feature; apply serialization only to `:app`; add `applicationIdSuffix = ".benchmark"` only to the benchmark build type; align the benchmark test APK Lifecycle runtime to 2.10.0. Root `build.gradle.kts` adds `alias(libs.plugins.kotlin.serialization) apply false`; `:app` applies that alias. Replace the frozen target constant with:

`core/ui` and each feature enable Compose, set `minSdk = 34`, and configure `AndroidJUnitRunner`. `:core:ui` depends on the Compose BOM/UI/Foundation/Material3 only. Each M1 feature depends on `:core:ui` plus the Compose BOM/UI/Foundation/Material3 and no `:core:data`/feature module. `:app` adds Navigation Compose, Serialization JSON, Lifecycle Runtime Compose, JUnit, Navigation testing, Compose UI testing, and the debug test manifest. No module adds a network or DI dependency.

```kotlin
internal const val TARGET_APP_PACKAGE = "com.paherdin.record.benchmark"
```

Do not change `gradle.properties`, production `applicationId`, compile/target/min SDK, Room, schema, or module registrations.

- [ ] **Step 5: Prove the smoke test and build graph**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest --tests com.paherdin.record.compat.SerializationPluginSmokeTest checkModuleDependencies :core:data:apiBoundaryCheck --console=plain
```

Expected: smoke test PASS; exact 7-module + one test-tool check PASS; API guard PASS. Any plugin incompatibility stops all later tasks and triggers spec/plan revision; do not disable built-in Kotlin.

- [ ] **Step 6: Prove benchmark isolation and P6 harness compatibility**

Run the focused device classes against the new target ID:

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.BenchmarkWiringSmokeTest,com.paherdin.record.benchmark.TimelineHarnessSelfCheckTest" --console=plain
```

Expected: tests PASS; target package `com.paherdin.record.benchmark`; test package remains `com.paherdin.record.benchmark.test`; no command targets `com.paherdin.record`.

- [ ] **Step 7: Commit the accepted build baseline**

```powershell
git add gradle/libs.versions.toml build.gradle.kts app/build.gradle.kts core/ui/build.gradle.kts feature/finance/build.gradle.kts feature/notes/build.gradle.kts feature/timeline/build.gradle.kts benchmark/build.gradle.kts benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineSentinelBridge.kt app/src/test/java/com/paherdin/record/compat/SerializationPluginSmokeTest.kt
git commit -m "build: establish M1 Compose navigation baseline"
```

---

### Task M1-T02: Build the shared phase, presentation, chrome, icon, and theme system

**Owner:** core UI worker; only `core/ui/**`. **Dispatch tier:** lightweight. **Depends on:** M1-T01.

**Files:**
- Create: `core/ui/src/main/java/com/paherdin/record/core/ui/{Phase,ContentPresentation,ShellChromePolicy,RecordIcons,SharedContent}.kt`
- Create: `core/ui/src/main/java/com/paherdin/record/core/ui/theme/{RecordTheme,RecordPalette,RecordTokens}.kt`
- Create: `core/ui/src/main/res/values/strings.xml`
- Test: `core/ui/src/test/java/com/paherdin/record/core/ui/{PhaseTest,ShellChromePolicyTest,RecordPaletteTest}.kt`
- Test: `core/ui/src/androidTest/java/com/paherdin/record/core/ui/SharedContentTest.kt`

- [ ] **Step 1: Write failing pure tests**

Create `PhaseTest`, `ShellChromePolicyTest`, and `RecordPaletteTest`. The palette test calculates WCAG relative luminance and requires the six approved foreground/background pairs; include `#925D39` on `#F6F2E9 >= 4.5`.

- [ ] **Step 2: Implement the exact closed phase type and transitions**

```kotlin
enum class Phase { Loading, Idle, Selecting, Checking, Validating, Saving, Running,
    Cancelling, Cancelled, Recovering, RecoverableFailure, BlockingFailure, Completed }

object PhaseTransitions {
    fun allows(from: Phase, to: Phase): Boolean = to in when (from) {
        Phase.Loading -> setOf(Phase.Idle, Phase.Selecting, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Idle -> setOf(Phase.Selecting, Phase.Checking, Phase.Validating, Phase.Saving, Phase.Running, Phase.Cancelled)
        Phase.Selecting -> setOf(Phase.Idle, Phase.Checking, Phase.Validating, Phase.Running, Phase.Cancelled, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Checking -> setOf(Phase.Idle, Phase.Selecting, Phase.Validating, Phase.Running, Phase.Cancelling, Phase.Completed, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Validating -> setOf(Phase.Idle, Phase.Saving, Phase.Running, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Saving -> setOf(Phase.Completed, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Running -> setOf(Phase.Running, Phase.Idle, Phase.Cancelling, Phase.Completed, Phase.Recovering, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Cancelling -> setOf(Phase.Cancelled, Phase.Completed, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.Recovering -> setOf(Phase.Completed, Phase.RecoverableFailure, Phase.BlockingFailure)
        Phase.RecoverableFailure, Phase.BlockingFailure -> setOf(Phase.Loading, Phase.Idle, Phase.Selecting, Phase.Checking, Phase.Validating, Phase.Saving, Phase.Running, Phase.Recovering)
        Phase.Cancelled, Phase.Completed -> emptySet()
    }
}
```

- [ ] **Step 3: Implement immutable presentation and exhaustive chrome policy**

```kotlin
sealed interface ContentPresentation<out T> {
    data object Loading : ContentPresentation<Nothing>
    data class Empty(val reason: EmptyReason) : ContentPresentation<Nothing>
    data class Error(val category: UiErrorCategory, val canRetry: Boolean) : ContentPresentation<Nothing>
    data class Ready<T>(val content: T) : ContentPresentation<T>
}
enum class EmptyReason { NoApplicableItems }
enum class UiErrorCategory { TemporarilyUnavailable, RecoveryRequired }
enum class DestinationKind { TopLevel, Global, Detail, Editor, Progress, Startup }
data class ShellChrome(val showBottomBar: Boolean, val allowBack: Boolean)
```

`ShellChromePolicy.resolve(kind, phase)` must be exhaustive: startup never constructs shell; editors hide bottom bar; progress hides it and blocks Back in Running/Cancelling; roots/global/detail show it.

- [ ] **Step 4: Implement A/A1 theme, local icons, and shared content**

Use only the approved palette, system light/dark selection, local `ImageVector` paths for Timeline/Finance/Notes/Projects/Create/Search/Settings/Back, Material typography from system fonts, and resource-backed loading/empty/error copy. `SharedContent` accepts `ContentPresentation<T>`, `onRetry`, and a ready-content lambda; it exposes headings/state semantics and 48-dp actions.

- [ ] **Step 5: Run pure and Compose tests**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:ui:testDebugUnitTest :core:ui:connectedDebugAndroidTest --console=plain
```

Expected: all phase/palette/chrome tests PASS; shared states pass light/dark, semantics, Retry, and 200% font cases.

- [ ] **Step 6: Commit**

```powershell
git add core/ui
git commit -m "feat(ui): add M1 theme and shared presentation contract"
```

---

### Task M1-T03: Expose the single-owner safe startup coordinator

**Owner:** core data worker; only `core/data/**`. **Dispatch tier:** full; public stable API/recovery boundary. **Depends on:** M1-T01 and accepted ADR 0016.

**Files:**
- Create: `core/data/src/main/java/com/paherdin/record/core/data/RecordStartup.kt`
- Create: `core/data/src/main/java/com/paherdin/record/core/data/StartupEngine.kt`
- Modify: `core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt`
- Modify: `core/data/api/expected-surface.txt`
- Modify: `core/data/src/test/java/com/paherdin/record/core/data/ApiSurfaceTest.kt`
- Create: `core/data/apiGuard/positive/StartupSurfaceUsage.kt`
- Create: `core/data/apiGuard/negative/ReferenceStartupEngine.kt`
- Test: `core/data/src/test/java/com/paherdin/record/core/data/RecordStartupTest.kt`
- Test: `core/data/src/androidTest/java/com/paherdin/record/core/data/room/RecordStartupDeviceTest.kt`

- [ ] **Step 1: Write failing coordinator tests**

Tests must assert Checking first; RecoveryDetected then Recovering before pending resolution; no Room open before recovery; Ready only after writable DB; stable blocked categories; retry on the same engine; non-retryable failure never starts another attempt; concurrent `start()` callers coalesce onto one in-flight attempt/result; Ready is cached without reopening; existing `RecordRepository.open()` compatibility; restart/idempotency through every current pending stage.

- [ ] **Step 2: Implement the ADR-approved public surface**

Use this exact shape unless accepted ADR 0016 names differ, in which case revise this plan before approval:

```kotlin
enum class StartupProgress { Checking, RecoveryDetected, Recovering }
enum class StartupFailure { StorageExhausted, RecoveryRequired, Unexpected }
fun interface StartupObserver { fun onProgress(progress: StartupProgress) }
sealed interface StartupResult {
    data class Ready(val repository: RecordRepository) : StartupResult
    data class Blocked(val failure: StartupFailure, val canRetry: Boolean) : StartupResult
}
class RecordStartup private constructor(/* internal engine */) {
    suspend fun start(observer: StartupObserver): StartupResult
    suspend fun retry(observer: StartupObserver): StartupResult
    companion object { fun create(context: android.content.Context): RecordStartup }
}
```

`StartupEngine` owns classification, the existing P5 order, one attempt mutex, and stable exception mapping. It emits `Checking`, then `RecoveryDetected` before dispatch, then `Recovering` while the existing recovery owns progress; the app therefore uses only legal `Checking → Running → Recovering` UI transitions. It emits no path, count, ID, raw stage, or exception.

`RecordStartup` keeps one internal in-flight deferred and last terminal result: concurrent `start()` calls await the same attempt; Ready is returned thereafter without reopening; `retry()` starts a new attempt only after a retryable Blocked result; retry after Ready returns the cached Ready; retry after non-retryable Blocked returns that same Blocked result without touching storage.

- [ ] **Step 3: Refactor the compatibility entry without duplicating recovery**

`RecordRepository.open(context)` must delegate to the same engine and map `Ready` to `RepositoryResult.Success`; blocked storage to `StorageExhausted`; other blocked outcomes to the existing stable failure. It cannot keep a second copy of bootstrap/activation logic.

- [ ] **Step 4: Update API machine checks**

Add the exact public declarations to `api/expected-surface.txt`, add a positive `StartupSurfaceUsage.kt`, and add negative consumers proving internal engine/activation-record/path/mutation access fails. Update only explicit internal-class pins in `ApiSurfaceTest`.

- [ ] **Step 5: Run all core-data gates and schema no-diff check**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:data:testDebugUnitTest :core:data:apiBoundaryCheck :core:data:connectedDebugAndroidTest --console=plain
git diff --exit-code -- core/data/schemas
```

Expected: all tests PASS; API allowlist exact; schema directory unchanged.

- [ ] **Step 6: Commit**

```powershell
git add core/data
git commit -m "feat(data): expose safe startup gate handoff"
```

---

### Task M1-T04: Implement Finance-owned M1 surfaces

**Owner:** finance worker; only `feature/finance/**`. **Dispatch tier:** full because it may run concurrently with T05/T06. **Depends on:** M1-T02.

**Files:**
- Create: `feature/finance/src/main/java/com/paherdin/record/feature/finance/FinanceUiModel.kt`
- Create: `feature/finance/src/main/java/com/paherdin/record/feature/finance/FinanceScreens.kt`
- Create: `feature/finance/src/main/res/values/strings.xml`
- Test: `feature/finance/src/androidTest/java/com/paherdin/record/feature/finance/FinanceScreensTest.kt`

- [ ] **Step 1: Write failing Compose tests**

Cover Loading/Empty/Error/Ready, Chinese availability copy, Retry semantics, Finance heading, no Save/form/domain fields, Transaction detail ID not rendered, and Expense/Income/Transfer labels.

- [ ] **Step 2: Implement focused public screen contracts**

```kotlin
enum class FinanceEditorKind { Expense, Income, Transfer }
data object FinanceAvailability

@Composable fun FinanceHomeScreen(
    presentation: ContentPresentation<FinanceAvailability>,
    onRetry: () -> Unit,
)
@Composable fun TransactionDetailAvailabilityScreen(onBack: () -> Unit)
@Composable fun FinanceEditorAvailabilityScreen(kind: FinanceEditorKind, onBack: () -> Unit)
```

Ready copy states that Finance arrives in M2. No ID, amount, account, category, balance, Save, repository, or Context persistence appears.

- [ ] **Step 3: Run tests and boundary scan**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :feature:finance:testDebugUnitTest :feature:finance:connectedDebugAndroidTest --console=plain
$recordFinanceBoundary = git grep -n -E "Room|Dao|Entity|RecordRepository|Save" -- feature/finance/src/main
if ($LASTEXITCODE -eq 0) { throw "Finance boundary leak:`n$recordFinanceBoundary" }
if ($LASTEXITCODE -ne 1) { throw "Finance boundary scan failed with exit $LASTEXITCODE" }
Write-Output 'Finance boundary scan: PASS'
```

Expected: tests PASS; forbidden scan has no match.

- [ ] **Step 4: Commit**

```powershell
git add feature/finance
git commit -m "feat(finance): add M1 availability surfaces"
```

---

### Task M1-T05: Implement Notes-owned M1 surfaces

**Owner:** notes worker; only `feature/notes/**`. **Dispatch tier:** full concurrent slice. **Depends on:** M1-T02.

**Files:**
- Create: `feature/notes/src/main/java/com/paherdin/record/feature/notes/NotesUiModel.kt`
- Create: `feature/notes/src/main/java/com/paherdin/record/feature/notes/NotesScreens.kt`
- Create: `feature/notes/src/main/res/values/strings.xml`
- Test: `feature/notes/src/androidTest/java/com/paherdin/record/feature/notes/NotesScreensTest.kt`

- [ ] **Step 1: Write failing Compose tests**

Cover all four presentations, Chinese Notes/M3 copy, Retry, note-detail availability, editor availability, no title/Markdown/image/tag/project/Save field, and no domain/data dependency.

- [ ] **Step 2: Implement focused contracts**

```kotlin
data object NotesAvailability
@Composable fun NotesHomeScreen(presentation: ContentPresentation<NotesAvailability>, onRetry: () -> Unit)
@Composable fun NoteDetailAvailabilityScreen(onBack: () -> Unit)
@Composable fun NoteEditorAvailabilityScreen(onBack: () -> Unit)
```

- [ ] **Step 3: Run tests and boundary scan**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :feature:notes:testDebugUnitTest :feature:notes:connectedDebugAndroidTest --console=plain
$recordNotesBoundary = git grep -n -E "Room|Dao|Entity|RecordRepository|Markdown|Save" -- feature/notes/src/main
if ($LASTEXITCODE -eq 0) { throw "Notes boundary leak:`n$recordNotesBoundary" }
if ($LASTEXITCODE -ne 1) { throw "Notes boundary scan failed with exit $LASTEXITCODE" }
Write-Output 'Notes boundary scan: PASS'
```

Expected: tests PASS; no forbidden implementation surface.

- [ ] **Step 4: Commit**

```powershell
git add feature/notes
git commit -m "feat(notes): add M1 availability surfaces"
```

---

### Task M1-T06: Implement Timeline/Projects/Search surfaces and retained date state

**Owner:** timeline worker; only `feature/timeline/**`. **Dispatch tier:** full concurrent slice. **Depends on:** M1-T02.

**Files:**
- Create: `feature/timeline/src/main/java/com/paherdin/record/feature/timeline/TimelineUiModel.kt`
- Create: `feature/timeline/src/main/java/com/paherdin/record/feature/timeline/TimelineDateState.kt`
- Create: `feature/timeline/src/main/java/com/paherdin/record/feature/timeline/TimelineScreens.kt`
- Create: `feature/timeline/src/main/res/values/strings.xml`
- Test: `feature/timeline/src/test/java/com/paherdin/record/feature/timeline/TimelineDateControllerTest.kt`
- Test: `feature/timeline/src/androidTest/java/com/paherdin/record/feature/timeline/TimelineScreensTest.kt`

- [ ] **Step 1: Write failing pure date-state tests**

Use fixed `Clock` and `ZoneId.of("Asia/Shanghai")`. Cover no-state launch, restored-state precedence, midnight stability, Today re-read, revision increment, applicable-filter preservation, and scroll reset only on Today.

- [ ] **Step 2: Implement immutable date state and reducer**

```kotlin
data class TimelineDateState(
    val selectedDay: java.time.LocalDate,
    val requestRevision: Long,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemOffset: Int,
)
class TimelineDateController(private val clock: Clock, private val zoneId: ZoneId) {
    fun initial(restored: TimelineDateState?): TimelineDateState = restored ?: TimelineDateState(
        LocalDate.now(clock.withZone(zoneId)), 0, 0, 0,
    )
    fun today(current: TimelineDateState): TimelineDateState = current.copy(
        selectedDay = LocalDate.now(clock.withZone(zoneId)),
        requestRevision = current.requestRevision + 1,
        firstVisibleItemIndex = 0,
        firstVisibleItemOffset = 0,
    )
}

val TimelineDateStateSaver = androidx.compose.runtime.saveable.listSaver<TimelineDateState, Any>(
    save = { listOf(it.selectedDay.toString(), it.requestRevision, it.firstVisibleItemIndex, it.firstVisibleItemOffset) },
    restore = {
        TimelineDateState(
            selectedDay = LocalDate.parse(it[0] as String),
            requestRevision = (it[1] as Number).toLong(),
            firstVisibleItemIndex = (it[2] as Number).toInt(),
            firstVisibleItemOffset = (it[3] as Number).toInt(),
        )
    },
)
```

- [ ] **Step 3: Implement source-typed UI models and screens**

```kotlin
enum class TimelineCardKind { Transaction, Note }
data class TimelineCardUiModel(val id: String, val kind: TimelineCardKind, val headline: String)
sealed interface TimelineContent {
    data object Availability : TimelineContent
    data class Cards(val cards: List<TimelineCardUiModel>) : TimelineContent
}
```

`TimelineHomeScreen` receives the date state and callbacks `onToday`, `onOpenTransaction`, `onOpenNote`; `ProjectsHomeScreen` and `UnifiedSearchScreen` receive presentation and typed callbacks. Production uses Availability; synthetic Cards values live only in test source.

- [ ] **Step 4: Run unit/Compose tests and projection-write scan**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :feature:timeline:testDebugUnitTest :feature:timeline:connectedDebugAndroidTest --console=plain
$recordTimelineBoundary = git grep -n -E "Room|Dao|Entity|RecordRepository|insert|update|delete" -- feature/timeline/src/main
if ($LASTEXITCODE -eq 0) { throw "Timeline boundary leak:`n$recordTimelineBoundary" }
if ($LASTEXITCODE -ne 1) { throw "Timeline boundary scan failed with exit $LASTEXITCODE" }
Write-Output 'Timeline boundary scan: PASS'
```

Expected: tests PASS; no source-fact/data access or write path.

- [ ] **Step 5: Commit**

```powershell
git add feature/timeline
git commit -m "feat(timeline): add M1 shell surfaces and date state"
```

---

### Task M1-T07: Integrate startup, typed navigation, shell chrome, theme, and launch identity

**Owner:** app integration worker; only `app/**`. **Dispatch tier:** full shared integration. **Depends on:** M1-T03–T06 accepted.

**Files:**
- Create: `app/src/main/java/com/paherdin/record/navigation/{M1Routes,RecordNavHost}.kt`
- Create: `app/src/main/java/com/paherdin/record/startup/StartupStateStore.kt`
- Create: `app/src/main/java/com/paherdin/record/ui/{RecordShell,SettingsScreen}.kt`
- Modify: `app/src/main/java/com/paherdin/record/{MainActivity,RecordApplication}.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/{strings,colors,themes}.xml`
- Create: `app/src/main/res/drawable/{ic_record_foreground,ic_record_monochrome}.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/{ic_launcher,ic_launcher_round}.xml`
- Test: `app/src/test/java/com/paherdin/record/navigation/M1RoutesTest.kt`
- Test: `app/src/test/java/com/paherdin/record/startup/StartupStateStoreTest.kt`

- [ ] **Step 1: Write failing route/store tests**

Cover the exact route allowlist, Kotlin serialization round-trip, canonical nonblank detail IDs, origin mapping, startup outcome→Phase mapping, no shell before Ready, one start/retry attempt, and production availability models only.

- [ ] **Step 2: Define exact typed routes**

```kotlin
@Serializable data object TimelineHome
@Serializable data object FinanceHome
@Serializable data object NotesHome
@Serializable data object ProjectsHome
@Serializable data class UnifiedSearch(val origin: TopLevelOrigin)
@Serializable data class Settings(val origin: TopLevelOrigin)
@Serializable data class TransactionDetail(val transactionId: String, val origin: TopLevelOrigin) {
    init { requireCanonicalRecordId(transactionId) }
}
@Serializable data class NoteDetail(val noteId: String, val origin: TopLevelOrigin) {
    init { requireCanonicalRecordId(noteId) }
}
@Serializable data class ExpenseEditor(val origin: TopLevelOrigin)
@Serializable data class IncomeEditor(val origin: TopLevelOrigin)
@Serializable data class TransferEditor(val origin: TopLevelOrigin)
@Serializable data class NoteEditor(val origin: TopLevelOrigin)
@Serializable data object RestoreRecoveryRoute
@Serializable enum class TopLevelOrigin { Timeline, Finance, Notes, Projects }

internal fun requireCanonicalRecordId(raw: String): String {
    val canonical = java.util.UUID.fromString(raw).toString()
    require(raw == canonical) { "record route ID must be canonical lowercase UUID text" }
    return canonical
}
```

The route constructors enforce `requireCanonicalRecordId`, including deserialization/restoration; malformed/uppercase IDs fail before navigation. No other production route is registered.

- [ ] **Step 3: Implement application-scoped startup state**

`RecordApplication` builds one `AppContainer` with `RecordStartup`, injected IO dispatcher, `Clock.systemUTC()`, and system `ZoneId`. `StartupStateStore` owns an application `CoroutineScope(SupervisorJob() + dispatcher)`, one `MutableStateFlow`, and idempotent `start/retry`; it maps `Checking → Phase.Checking`, `RecoveryDetected → Phase.Running`, `Recovering → Phase.Recovering`, and results to Completed/RecoverableFailure/BlockingFailure. It never exposes the repository to feature UI.

```kotlin
internal sealed interface AppStartState {
    data class Gate(val phase: Phase, val canRetry: Boolean = false) : AppStartState
    data class Ready internal constructor(
        val repository: RecordRepository,
        val phase: Phase = Phase.Completed,
    ) : AppStartState
}

internal class StartupStateStore(
    private val startup: RecordStartup,
    private val scope: CoroutineScope,
) {
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _state = MutableStateFlow<AppStartState>(AppStartState.Gate(Phase.Checking))
    val state: StateFlow<AppStartState> = _state.asStateFlow()

    fun start() = launchAttempt { observer -> startup.start(observer) }
    fun retry() = launchAttempt { observer -> startup.retry(observer) }

    private fun launchAttempt(block: suspend (StartupObserver) -> StartupResult) {
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val result = block(StartupObserver { progress ->
                    _state.value = AppStartState.Gate(
                        when (progress) {
                            StartupProgress.Checking -> Phase.Checking
                            StartupProgress.RecoveryDetected -> Phase.Running
                            StartupProgress.Recovering -> Phase.Recovering
                        },
                    )
                })
                _state.value = when (result) {
                    is StartupResult.Ready -> AppStartState.Ready(result.repository)
                    is StartupResult.Blocked -> AppStartState.Gate(
                        phase = if (result.canRetry) Phase.RecoverableFailure else Phase.BlockingFailure,
                        canRetry = result.canRetry,
                    )
                }
            } finally {
                inFlight.set(false)
            }
        }
    }
}
```

- [ ] **Step 4: Implement NavHost and shell**

Use `rememberNavController`, `launchSingleTop`, `restoreState`, and `popUpTo(findStartDestination()) { saveState = true }`. Global app bar actions exist on all four roots. The create modal contains exactly 支出、收入、转账、笔记. App maps callbacks to feature-owned screens; bottom navigation is derived only from `ShellChromePolicy`.

- [ ] **Step 5: Add resources, system launch identity, and manifest policy**

Create resource-backed Chinese functional strings, `Record` app name, A/A1 XML colors/themes, adaptive foreground/background/monochrome timeline-thread vectors, platform starting-window theme, and manifest `icon`, `roundIcon`, `label`, `theme`. Preserve `allowBackup=false`, extraction/full-backup rules, exported launcher Activity only, and no permission.

- [ ] **Step 6: Replace skeleton Activity**

`MainActivity` calls `enableEdgeToEdge()`, renders `RecordTheme`, collects the startup store with lifecycle awareness, uses `ReportDrawnWhen` only when the safe shell is interactive, and renders a data-free recovery surface otherwise. No custom splash Activity/timer or old-frame content.

- [ ] **Step 7: Run host tests/build and commit**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease checkModuleDependencies --console=plain
```

Expected: PASS; release package remains `com.paherdin.record`; benchmark package is isolated; no manifest permission.

```powershell
git add app
git commit -m "feat(app): deliver M1 typed application shell"
```

---

### Task M1-T08: Prove navigation, retained state, shared presentation, and accessibility

**Owner:** UI acceptance worker; `app/src/androidTest/**`, feature/core UI test paths only. **Dispatch tier:** full shared test interface. **Depends on:** M1-T07.

**Files:**
- Create: `app/src/androidTest/java/com/paherdin/record/M1TestContent.kt`
- Create: `app/src/androidTest/java/com/paherdin/record/M1NavigationTest.kt`
- Create: `app/src/androidTest/java/com/paherdin/record/M1RetainedStateTest.kt`
- Create: `app/src/androidTest/java/com/paherdin/record/M1AccessibilityTest.kt`
- May modify for cross-module acceptance assertions only: `core/ui/src/androidTest/java/com/paherdin/record/core/ui/SharedContentTest.kt`
- May modify for cross-module acceptance assertions only: `feature/finance/src/androidTest/java/com/paherdin/record/feature/finance/FinanceScreensTest.kt`
- May modify for cross-module acceptance assertions only: `feature/notes/src/androidTest/java/com/paherdin/record/feature/notes/NotesScreensTest.kt`
- May modify for cross-module acceptance assertions only: `feature/timeline/src/androidTest/java/com/paherdin/record/feature/timeline/TimelineScreensTest.kt`

- [ ] **Step 1: Build a test-only app content fixture**

In androidTest, construct `RecordApp` with synthetic test-only Timeline cards and per-tab state probes. Do not add test switches/intents/fixtures to `main` or `release`.

- [ ] **Step 2: Implement navigation acceptance tests**

Assert default Timeline and exact tab order; switch/reselect state; exact global choices; Search/Settings origin; Transaction→Finance and Note→Notes routing; editor bottom-bar hiding; detail/global visibility; test-only progress lock; no generic Entry semantics.

- [ ] **Step 3: Implement recreation and Timeline tests**

Use `ActivityScenario.recreate()` and saved-state restoration to prove four independent stacks/probes. Use controlled clock/zone to prove no-state current day, restored day, midnight stability, Today-to-top, filter preservation, and stale revision.

- [ ] **Step 4: Implement shared-state/accessibility tests**

Render Loading/Empty/Error/Ready in light/dark and fontScale 1.0/2.0. Assert headings, state descriptions, selected tabs, focus/traversal, Retry, 48-dp bounds, no required text clipping/overlap, and absence of raw errors/IDs.

- [ ] **Step 5: Run every UI module suite and commit**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:ui:connectedDebugAndroidTest :feature:finance:connectedDebugAndroidTest :feature:notes:connectedDebugAndroidTest :feature:timeline:connectedDebugAndroidTest :app:connectedDebugAndroidTest --console=plain
```

Expected: all XML results have `failures="0" errors="0"` and named acceptance tests present.

```powershell
git add app/src/androidTest/java/com/paherdin/record core/ui/src/androidTest/java/com/paherdin/record/core/ui/SharedContentTest.kt feature/finance/src/androidTest/java/com/paherdin/record/feature/finance/FinanceScreensTest.kt feature/notes/src/androidTest/java/com/paherdin/record/feature/notes/NotesScreensTest.kt feature/timeline/src/androidTest/java/com/paherdin/record/feature/timeline/TimelineScreensTest.kt
git commit -m "test(ui): prove M1 shell navigation and accessibility"
```

---

### Task M1-T09: Build the isolated startup fixture, Macrobenchmark, and raw-report validator

**Owner:** benchmark worker; approved testFixtures/app benchmark/benchmark/core-data report-validator scopes. **Dispatch tier:** full. **Depends on:** M1-T03 and M1-T07.

**Files:**
- Modify: `.gitignore` to add `benchmark/m1-frozen/`
- Create: `core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/startup/M1StartupFixtureGenerator.kt`
- Test: `core/data/src/test/kotlin/com/paherdin/record/core/data/startup/M1StartupFixtureGeneratorTest.kt`
- Create: `app/src/benchmark/kotlin/com/paherdin/record/benchmark/{M1StartupFixtureActivity,M1StartupSentinelRecorder}.kt`
- Modify: `app/src/benchmark/AndroidManifest.xml`
- Create: `benchmark/src/main/kotlin/com/paherdin/record/benchmark/{M1StartupBenchmark,M1StartupHarnessGates,M1StartupRunManifest}.kt`
- Test: `benchmark/src/main/kotlin/com/paherdin/record/benchmark/M1StartupHarnessSelfCheckTest.kt`
- Create: `core/data/src/test/kotlin/com/paherdin/record/core/data/startup/M1StartupReportValidator.kt`
- Modify: `core/data/build.gradle.kts` to add `validateM1StartupReports`

- [ ] **Step 1: Write failing fixture generator tests**

Assert exact `m1-startup-v1` counts/hashes for Fresh, Typical, High-volume, Pending activation, and every current migration-recovery stage; validate finance/XOR/relations/attachment bytes before publishing a fixture.

- [ ] **Step 2: Implement deterministic fixture generation**

Use fixed seed/Clock/IDs and the exact spec cardinalities. Generate valid decodable synthetic images whose complete bytes/hash/64-KiB target size are deterministic. Emit only into the benchmark application's private generation/recovery root.

- [ ] **Step 3: Add benchmark-only setup surface and identity marker**

Add exported benchmark-only `M1StartupFixtureActivity` with explicit fixture enum and one-shot setup token. It requires `applicationContext.packageName == "com.paherdin.record.benchmark"`, requires its benchmark-only manifest metadata `m1BenchmarkVariant=true`, publishes the installed signing-certificate digest plus variant marker in the sentinel, prepares and validates one fixture, then finishes. It is absent from main/release and does not rely on generated `BuildConfig` availability.

- [ ] **Step 4: Implement host harness identity gates**

Before any clear/setup command, require exact target package, installed non-debuggable benchmark variant, marker component, and test package. Reject any command string containing `com.paherdin.record` unless the complete token is `com.paherdin.record.benchmark` or `.benchmark.test`; retain the command/target log.

- [ ] **Step 5: Implement Macrobenchmark blocks**

For each fixture: five manual unmeasured cold warmups; 30 `StartupMode.COLD` measured launches; `CompilationMode.Full`; `StartupTimingMetric`; ready/recovery semantics sentinel; no shell sentinel for pending states; exit-info/ANR/OOM/crash checks. Restore the identical fixture before every warmup/measurement outside timed launch.

- [ ] **Step 6: Implement raw report validator**

Parse copied benchmark JSON/Perfetto metadata; require 30 unique positive samples per binding; nearest-rank rank 29; healthy TTID/TTFD budgets; pending TTID budget; exact fixture/environment/command log; no invalid iteration. Add `:core:data:validateM1StartupReports` host task.

- [ ] **Step 7: Run pure/build self-checks and commit**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:data:testDebugUnitTest :app:assembleBenchmark :benchmark:assembleBenchmark --console=plain
```

Expected: fixture/hash/parser/harness tests PASS; no device clear is run in T09.

```powershell
git add .gitignore core/data/build.gradle.kts core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/startup core/data/src/test/kotlin/com/paherdin/record/core/data/startup app/src/benchmark/AndroidManifest.xml app/src/benchmark/kotlin/com/paherdin/record/benchmark/M1StartupFixtureActivity.kt app/src/benchmark/kotlin/com/paherdin/record/benchmark/M1StartupSentinelRecorder.kt benchmark/src/main/kotlin/com/paherdin/record/benchmark/M1StartupBenchmark.kt benchmark/src/main/kotlin/com/paherdin/record/benchmark/M1StartupHarnessGates.kt benchmark/src/main/kotlin/com/paherdin/record/benchmark/M1StartupRunManifest.kt benchmark/src/main/kotlin/com/paherdin/record/benchmark/M1StartupHarnessSelfCheckTest.kt
git commit -m "test(benchmark): add isolated M1 startup contract"
```

---

### Task M1-T10: Execute and validate the physical-device startup bindings

**Owner:** benchmark execution worker; no source writes except task-owned raw evidence outside gitignored user data. **Dispatch tier:** full long-running/device. **Depends on:** M1-T09 and explicit deletion approval.

**Files/artifacts:**
- Write only generated local evidence under `benchmark/m1-frozen/run-$recordM1EvidenceStamp/`
- Read generated AndroidX outputs under `benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/`
- No tracked source/document write; main agent records accepted results in `verification.md`

- [ ] **Step 1: Stop for destructive approval**

The main agent states: exact target `com.paherdin.record.benchmark`; action `pm clear`/private fixture reset; reason repeatable cold-start fixture; risk loss of any data inside that isolated benchmark package; production target explicitly excluded. Without explicit approval, report `BLOCKED` and run nothing destructive.

- [ ] **Step 2: Verify device/environment/package identity**

Record device/build/CPU/RAM/battery/thermal/display settings, installed package IDs, APK hashes, variant marker, and command allowlist. Any ambiguity fails before clear.

After the separate approval is recorded and the exact marker checks pass, the only permitted clear command is:

```powershell
& 'C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell pm clear com.paherdin.record.benchmark
```

Expected: `Success`. The harness may repeat only this exact command at the declared fixture-reset boundaries; `com.paherdin.record` without the `.benchmark` suffix is forbidden.

- [ ] **Step 3: Run the binding suite in background**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.M1StartupBenchmark" --console=plain
```

Expected: every fixture block completes with 30 valid measurements; no unexpected exit. Poll rather than interrupting a healthy run.

- [ ] **Step 4: Copy and validate raw reports**

Copy AndroidX additional-test-output into a new dated directory under `benchmark/m1-frozen/`, record hashes, and retain the task-specific absolute path in the same PowerShell session:

```powershell
$recordM1EvidenceStamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$recordM1EvidenceRoot = New-Item -ItemType Directory -Path 'benchmark\m1-frozen' -Force
$recordM1ReportDir = Join-Path (Resolve-Path -LiteralPath 'benchmark\m1-frozen') "run-$recordM1EvidenceStamp"
New-Item -ItemType Directory -Path $recordM1ReportDir | Out-Null
$recordM1AdditionalRoot = Resolve-Path -LiteralPath 'benchmark\build\outputs\connected_android_test_additional_output\benchmark\connected'
$recordM1DeviceOutput = Get-ChildItem -LiteralPath $recordM1AdditionalRoot -Directory | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($null -eq $recordM1DeviceOutput) { throw 'M1 AndroidX additional output directory is missing' }
Copy-Item -LiteralPath $recordM1DeviceOutput.FullName -Destination $recordM1ReportDir -Recurse
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain "-Dm1ReportDir=$recordM1ReportDir" :core:data:validateM1StartupReports --console=plain
```

The worker records the resolved source and destination directories in `verification.md`; the evidence root remains bounded to `benchmark/m1-frozen`. Expected: PASS with each p95 and budget printed.

- [ ] **Step 5: Record evidence without a source commit**

Report raw paths/hashes, test XML counts, metrics, stability, command-target log, and remaining risk. Main agent updates `verification.md`; generated device artifacts stay local unless a later approved evidence policy says otherwise.

---

### Task M1-T11: Run combined policy/offline gates and accept the slice

**Owner:** main agent integration/acceptance; `tools/verify-m1-artifacts.ps1`, docs verification state, non-overlapping fixes only. **Dispatch tier:** serial integration. **Depends on:** accepted T08 and T10.

**Files:**
- Create: `tools/verify-m1-artifacts.ps1`
- Modify: `docs/specs/mvp/m1-app-shell/verification.md`
- On full acceptance only, modify: `spec.md`, `plan.md`, `AGENTS.md`, `docs/index.md`, `docs/roadmap.md`

- [ ] **Step 1: Create the bounded artifact verifier test-first**

`tools/verify-m1-artifacts.ps1` resolves only `D:\record`, `local.properties` SDK, `app/build` outputs, and exact package names. It fails on source/merged/final `INTERNET` or related permissions, unexpected exported/network components, wrong package IDs, missing `allowBackup=false`, missing nine-domain cloud/device/full-backup exclusions, missing icon/monochrome/starting theme, test fixture in main/release, tracking SDK strings, or wrong dependency versions.

- [ ] **Step 2: Build all final artifacts and run host gates**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain clean check :app:assembleDebug :app:assembleRelease :app:assembleBenchmark :benchmark:assembleBenchmark --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify-m1-artifacts.ps1 -ProjectRoot 'D:\record'
```

Expected: build/check PASS; verifier prints exact package/permission/backup/icon/dependency PASS rows.

- [ ] **Step 3: Run final device/offline suites**

Run the accepted UI/navigation/startup-order test classes with airplane mode or equivalent network isolation; capture before/after setting and restore it. Do not rerun destructive benchmark reset without the same approval scope.

- [ ] **Step 4: Perform main-agent raw-evidence acceptance**

Check worker ownership, diffs, commits, every XML `tests/failures/errors`, API allowlist, schema no-diff, module graph, source scans, APK/rule output, benchmark raw samples, command-target log, and all 19 acceptance rows. Self-reported PASS without raw evidence remains unaccepted.

- [ ] **Step 5: Commit final verifier/verification records**

```powershell
git add tools/verify-m1-artifacts.ps1 docs/specs/mvp/m1-app-shell/verification.md
git commit -m "test: verify MVP M1 app shell acceptance"
```

- [ ] **Step 6: Complete lifecycle only when every row passes**

Set spec/plan to `completed`, record actual completion date/context, update routers/roadmap, and commit. If any row fails, keep M1 `in-progress`, record the exact failure/deviation/risk, and follow the approved revision/retry rules; do not advance to M2.

## Plan self-review checklist

- [ ] Each spec section and criteria 1–19 maps to at least one task and raw evidence path.
- [ ] No task changes schema, migration, recovery-record format, production module set, production/debug package data, or future M2+ behavior.
- [ ] Public type/function names are consistent across T03/T07/T09; route names exactly match the spec.
- [ ] Every functional task has failing-test → implementation → passing-test → commit steps.
- [ ] Concurrent scopes T04/T05/T06 do not overlap; all shared files are serialized.
- [ ] Device absence and deletion approval produce `BLOCKED`, never a hidden skip or false PASS.
- [ ] `verification.md` remains main-agent owned and contains actual commands/results, not this plan's expectations.

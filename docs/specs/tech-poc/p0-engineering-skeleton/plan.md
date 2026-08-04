# Tech PoC P0 — Engineering Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify the approved seven-module Android 14+ engineering skeleton: a version-catalog-based Gradle project, automated module dependency enforcement wired into the default build, and launch verification on the frozen reference environment (physical Xiaomi 14 Pro).

**Architecture:** The module graph, responsibilities, and allowed edges follow the [P0 spec](spec.md) and the [architecture overview](../../architecture/overview.md). The dependency check makes the approved graph a machine-checked invariant. No business code, no Room, no navigation, no DI framework, no WorkManager worker.

**Tech Stack:** Gradle 9.5.0 (Kotlin DSL), AGP 9.3.0 (built-in Kotlin; KGP 2.2.10), Compose compiler plugin `org.jetbrains.kotlin.plugin.compose` 2.2.10, Compose BOM 2026.06.00, Room 2.8.4 (catalog declaration only), JDK 17. `minSdk 34` / `targetSdk 34` / `compileSdk 36`.

**Reference environment (frozen):** Xiaomi 14 Pro (physical, USB debugging), Android 14 / API 34 out-of-box baseline. The actual system SDK level and build id are recorded in `verification.md` at verification time. A system upgraded past API 34 is recorded, not rejected.

**Application id / namespace:** `com.paherdin.record`.

---

## Dependency review record

Every dependency below is recorded per the engineering conventions (version, license, maintenance, compatibility, removal path) before it enters the version catalog.

| Dependency | Version | License | Maintenance | Compatibility impact | Removal path |
|---|---|---|---|---|---|
| Gradle wrapper | 9.5.0 | Apache-2.0 | Gradle Foundation, active | Required by AGP 9.3.0; JDK 17 | Delete wrapper files; any other Gradle 9.x works |
| AGP (`com.android.application` / `com.android.library`) | 9.3.0 | Apache-2.0 | Google, active | Requires Gradle 9.5.0, JDK 17, SDK Build Tools 36; AGP 9 built-in Kotlin removes `org.jetbrains.kotlin.android` | Downgrade to an older AGP 8.x needs Kotlin plugin re-added and a re-approval record |
| Kotlin (built-in KGP + `org.jetbrains.kotlin.jvm`) | 2.2.10 | Apache-2.0 | JetBrains, active | Version bound to AGP 9 default; Compose compiler plugin ships with the same version | N/A — built into the approved stack |
| Compose compiler plugin | 2.2.10 | Apache-2.0 | JetBrains, active | Same version as Kotlin; replaces deprecated `composeOptions` DSL | Remove plugin; use View system (no P0 code depends on Compose yet) |
| Compose BOM | 2026.06.00 | Apache-2.0 | Google, active | Compose 1.11 line; requires `compileSdk 36` | BOM version change is catalog maintenance, not an ADR |
| `androidx.activity:activity-compose` | managed by BOM | Apache-2.0 | Google, active | BOM-managed | Remove the one Compose dependency; skeleton stays buildable |
| `androidx.compose.ui:ui` / `material3` / `ui-tooling-preview` | managed by BOM | Apache-2.0 | Google, active | BOM-managed | Same as above |
| Room | 2.8.4 | Apache-2.0 | Google, active | Declared in catalog only; no Room code in P0 (approved baseline ADR 0001) | Catalog entry removal is catalog maintenance, not an ADR |

No dependency introduces network permissions, analytics, crash reporting, or closed-source tracking. The app declares no `INTERNET` permission.

## File map

| Path | Responsibility |
|---|---|
| `settings.gradle.kts` | Repositories, `rootProject.name = "record"`, include exactly the seven modules |
| `build.gradle.kts` (root) | Plugin declarations (`apply false`), the `checkModuleDependencies` task, its hook into `check` |
| `gradle.properties` | Minimal: `android.useAndroidx=true` |
| `gradle/libs.versions.toml` | Version catalog: AGP, Kotlin, Compose BOM, Room, Compose compiler plugin |
| `gradle/wrapper/*` | Gradle 9.5.0 wrapper |
| `:app/build.gradle.kts` | Application module; Compose enabled; `com.paherdin.record` |
| `:app/src/main/AndroidManifest.xml` | `RecordApplication`, `MainActivity` launcher, `allowBackup="false"`, zero permissions |
| `:app/src/main/java/com/paherdin/record/RecordApplication.kt` | Application class (skeleton body) |
| `:app/src/main/java/com/paherdin/record/MainActivity.kt` | Placeholder Compose surface |
| `:app/src/main/res/xml/data_extraction_rules.xml` | API 34 cloud-backup/device-transfer exclusion rules (empty exclusion list valid for skeleton) |
| `:app/src/main/res/xml/backup_rules.xml` | Compatible `fullBackupContent` exclusion rules (empty exclusion list) |
| `:feature:finance/build.gradle.kts` + `:feature:notes/` + `:feature:timeline/` | Android library modules, no source files, no project dependencies beyond the approved edges |
| `:core:model/build.gradle.kts` | Pure Kotlin JVM module (platform-independent per overview) |
| `:core:data/build.gradle.kts` + `:core:ui/build.gradle.kts` | Android library modules, no source files |
| `verification.md` | Created at completion; records commands, environment, results, remaining risk |

The existing `.gitignore` already covers `.gradle/`, `**/build/`, `local.properties`, `.idea/`, `.worktrees/`; no change needed unless an unignored generated file appears.

## Allowed dependency edges (enforced)

| Module | May depend on |
|---|---|
| `:app` | `:feature:finance`, `:feature:notes`, `:feature:timeline`, `:core:data`, `:core:ui` |
| `:feature:finance` | `:core:model`, `:core:data`, `:core:ui` |
| `:feature:notes` | `:core:model`, `:core:data`, `:core:ui` |
| `:feature:timeline` | `:core:model`, `:core:data`, `:core:ui` |
| `:core:data` | `:core:model` |
| `:core:ui` | `:core:model` |
| `:core:model` | no other project module |

Only direct declared project dependencies count; transitive dependencies do not. External (non-project) dependencies are unrestricted by this check.

---

### Task 1: Verify toolchain and device prerequisites

**Files:** none (read-only checks)

- [ ] **Step 1: Confirm JDK 17+**

Run:

```powershell
java -version
```

Expected: JDK 17 or newer. AGP 9.3.0 requires JDK 17.

- [ ] **Step 2: Confirm Android SDK**

Run:

```powershell
echo $env:ANDROID_HOME
Get-ChildItem -Name "$env:ANDROID_HOME\platforms"
```

Expected: `ANDROID_HOME` (or `local.properties` with `sdk.dir`) points at an SDK containing `android-34` and `android-36` platforms. If `local.properties` is created, it stays untracked (already ignored).

- [ ] **Step 3: Confirm the reference device is visible**

Run:

```powershell
adb devices -l
```

Expected: the Xiaomi 14 Pro appears with USB debugging enabled. If the device is unauthorized, accept the prompt on the device and re-run.

- [ ] **Step 4: Have one executable Gradle 9.x to generate the wrapper**

Run:

```powershell
gradle --version
```

Expected: any Gradle 9.x is present. If absent, install one temporarily (e.g. unzip `gradle-9.5.0-bin.zip` from `https://services.gradle.org/distributions/`) — it is needed only once to generate the wrapper, which is then the sole build entry point.

### Task 2: Create the Gradle skeleton

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `gradle/wrapper/*` (generated)

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "record"
include(
    ":app",
    ":feature:finance",
    ":feature:notes",
    ":feature:timeline",
    ":core:model",
    ":core:data",
    ":core:ui",
)
```

- [ ] **Step 2: Create root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
```

- [ ] **Step 3: Create `gradle.properties`**

```properties
android.useAndroidx=true
```

- [ ] **Step 4: Create the version catalog `gradle/libs.versions.toml`**

```toml
[versions]
agp = "9.3.0"
kotlin = "2.2.10"
composeBom = "2026.06.00"
room = "2.8.4"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
activity-compose = { group = "androidx.activity", name = "activity-compose" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

Compose artifacts resolve their versions from the BOM. `room-runtime` is declared but referenced by no module in P0; it pins the approved baseline so P1 cannot drift.

- [ ] **Step 5: Generate the wrapper**

Run:

```powershell
gradle wrapper --gradle-version 9.5.0
```

Expected: `gradlew`, `gradlew.bat`, and `gradle/wrapper/*` are created; `gradle-wrapper.properties` pins `distributionUrl` to `gradle-9.5.0-bin.zip`.

- [ ] **Step 6: Verify the wrapper works**

Run:

```powershell
.\gradlew.bat --version
```

Expected: prints Gradle 9.5.0 and a JDK 17+ runtime. From here on, `.\gradlew.bat` is the only build entry point.

### Task 3: Create the seven modules

**Files:**
- Create: `:app/build.gradle.kts`
- Create: `:feature:finance/build.gradle.kts`, `:feature:notes/build.gradle.kts`, `:feature:timeline/build.gradle.kts`
- Create: `:core:model/build.gradle.kts`, `:core:data/build.gradle.kts`, `:core:ui/build.gradle.kts`

- [ ] **Step 1: Create `:app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.paherdin.record"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.paherdin.record"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":feature:finance"))
    implementation(project(":feature:notes"))
    implementation(project(":feature:timeline"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    implementation(libs.compose.bom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)
}
```

- [ ] **Step 2: Create the three feature module build files**

Each of `:feature:finance`, `:feature:notes`, `:feature:timeline`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.paherdin.record.feature.finance" // notes / timeline per module
    compileSdk = 36
}
```

No source files, no dependencies beyond the approved edges in P0. The P0 spec forbids adding public interfaces or domain types here.

- [ ] **Step 3: Create `:core:model/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}
```

`core:model` stays a pure Kotlin JVM module per the architecture overview — Android types, Room, and storage paths never enter its API.

- [ ] **Step 4: Create `:core:data` and `:core:ui` build files**

```kotlin
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.paherdin.record.core.data" // / .core.ui per module
    compileSdk = 36
}
```

- [ ] **Step 5: Verify module registration**

Run:

```powershell
.\gradlew.bat projects
```

Expected: exactly seven subprojects: `:app`, `:feature:finance`, `:feature:notes`, `:feature:timeline`, `:core:model`, `:core:data`, `:core:ui`.

### Task 4: `:app` manifest and placeholder entry

**Files:**
- Create: `:app/src/main/AndroidManifest.xml`
- Create: `:app/src/main/res/xml/data_extraction_rules.xml`, `:app/src/main/res/xml/backup_rules.xml`
- Create: `:app/src/main/java/com/paherdin/record/RecordApplication.kt`, `MainActivity.kt`

- [ ] **Step 1: Create `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".RecordApplication"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:label="Record">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

No `<uses-permission>` element exists at all — in particular no `INTERNET`.

- [ ] **Step 2: Create the exclusion rule files**

`data_extraction_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
    </device-transfer>
</data-extraction-rules>
```

`backup_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="root" path="." />
</full-backup-content>
```

These are the skeleton-time baseline; the complete per-domain exclusion contract for database/attachments/recovery state lands with the first slice that owns user data (M1 gate performs the source/merged/final-APK policy inspection).

- [ ] **Step 3: Create `RecordApplication.kt`**

```kotlin
package com.paherdin.record

import android.app.Application

class RecordApplication : Application()
```

- [ ] **Step 4: Create `MainActivity.kt`**

```kotlin
package com.paherdin.record

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Text("P0 engineering skeleton")
            }
        }
    }
}
```

No navigation, no state, no business logic — the P0 spec requires the placeholder to prove launch only.

- [ ] **Step 5: Verify the app assembles**

Run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`; `:app/build/outputs/apk/debug/app-debug.apk` exists.

### Task 5: Automated module dependency check

**Files:**
- Modify: `build.gradle.kts` (root)

- [ ] **Step 1: Register `checkModuleDependencies` and wire it into `check`**

Add to the root `build.gradle.kts` (AGP 9 DSL; Kotlin DSL):

```kotlin
val allowedModules = setOf(
    ":app", ":feature:finance", ":feature:notes", ":feature:timeline",
    ":core:model", ":core:data", ":core:ui",
)

val allowedEdges = mapOf(
    ":app" to setOf(":feature:finance", ":feature:notes", ":feature:timeline", ":core:data", ":core:ui"),
    ":feature:finance" to setOf(":core:model", ":core:data", ":core:ui"),
    ":feature:notes" to setOf(":core:model", ":core:data", ":core:ui"),
    ":feature:timeline" to setOf(":core:model", ":core:data", ":core:ui"),
    ":core:data" to setOf(":core:model"),
    ":core:ui" to setOf(":core:model"),
    ":core:model" to emptySet(),
)

tasks.register("checkModuleDependencies") {
    doLast {
        val projects = rootProject.allprojects.map { it.path }.toSet()
        check(projects == allowedModules + "") {
            "Module set mismatch: expected $allowedModules, found ${projects - ""}"
        }
        rootProject.allprojects.forEach { project ->
            val deps = project.configurations
                .flatMap { it.dependencies }
                .filterIsInstance<ProjectDependency>()
                .map { it.path }
                .toSet()
            val violations = deps - (allowedEdges[project.path] ?: emptySet())
            check(violations.isEmpty()) {
                "Illegal project dependency edges from ${project.path}: $violations"
            }
        }
        println("checkModuleDependencies: PASS (7 modules, graph conforms)")
    }
}

gradle.projectsEvaluated {
    tasks.register("check") {
        dependsOn("checkModuleDependencies")
    }
}
```

The root `check` aggregates every subproject's `check` plus this task, so a dependency violation fails the default build.

- [ ] **Step 2: Verify the positive path**

Run:

```powershell
.\gradlew.bat check
```

Expected: `BUILD SUCCESSFUL` and output contains `checkModuleDependencies: PASS (7 modules, graph conforms)`.

### Task 6: Full positive build verification

**Files:** none (verification)

- [ ] **Step 1: Clean full build**

Run:

```powershell
.\gradlew.bat clean check
```

Expected: `BUILD SUCCESSFUL`; lint/compile/assemble of all seven modules pass; `checkModuleDependencies: PASS`.

- [ ] **Step 2: Record the environment**

Run:

```powershell
.\gradlew.bat --version
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release
adb shell getprop ro.build.display.id
```

Expected: Gradle 9.5.0 + JDK 17+; the device reports its actual SDK level, release, and build id. These values go into `verification.md` — the device SDK level/release is recorded even if the system was upgraded past API 34.

### Task 7: Negative verification of the dependency check

**Files:** temporarily modify `:feature:finance/build.gradle.kts`; revert after the check

- [ ] **Step 1: Introduce a violating edge**

Add to `:feature:finance/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":feature:notes"))
}
```

- [ ] **Step 2: Confirm the check fails**

Run:

```powershell
.\gradlew.bat check
```

Expected: build fails with `Illegal project dependency edges from :feature:finance: [:feature:notes]`. Record the exact failure output in `verification.md`.

- [ ] **Step 3: Revert and confirm recovery**

Remove the temporary `dependencies` block, then run:

```powershell
.\gradlew.bat check
```

Expected: `BUILD SUCCESSFUL` again, `checkModuleDependencies: PASS`. Both states are evidence that the check is real, not decorative.

### Task 8: Reference-device launch verification

**Files:** none (device verification)

- [ ] **Step 1: Install on the reference device**

Run:

```powershell
.\gradlew.bat :app:installDebug
```

Expected: `BUILD SUCCESSFUL`; the app installs on the Xiaomi 14 Pro.

- [ ] **Step 2: Launch and confirm the placeholder is visible**

Run:

```powershell
adb shell am start -n com.paherdin.record/.MainActivity
Start-Sleep -Seconds 2
adb shell dumpsys activity activities | Select-String "com.paherdin.record"
```

Expected: the activity reports `resumed` (or the top resumed activity is `com.paherdin.record/.MainActivity`). Optionally capture a screenshot (`adb exec-out screencap -p > skeleton.png`) as supplementary evidence; the `dumpsys` output is the required evidence.

### Task 9: Record verification and commit

**Files:**
- Create: `verification.md`
- Create: `docs/specs/tech-poc/p0-engineering-skeleton/` status continuity

- [ ] **Step 1: Create `verification.md`**

Follow the evidence format from the testing strategy: `Command`, `Environment`, `Result`, `Remaining risk`. It must cover every acceptance criterion in the P0 spec section 6 (module set, graph conformance, negative verification, build, launch, manifest baseline, version catalog, no leakage) and map each to the roadmap P0 exit gate.

- [ ] **Step 2: Confirm the working tree is clean except intended files**

Run:

```powershell
git status --short
git diff --check
```

Expected: only the intended new files appear; no whitespace errors.

- [ ] **Step 3: Commit the slices**

Run:

```powershell
git add docs/specs/tech-poc/p0-engineering-skeleton/spec.md
git commit -m "docs: approve P0 engineering skeleton spec"

git add docs/specs/tech-poc/p0-engineering-skeleton/plan.md
git commit -m "docs: add P0 engineering skeleton plan"

git add settings.gradle.kts build.gradle.kts gradle.properties gradle .gitignore .gitattributes
git add app feature core
git commit -m "feat(poc): scaffold seven-module Android skeleton"
```

Expected: three clean local commits. (Adjust the file paths to the actual layout if the module sources differ.)

- [ ] **Step 4: Verify the slice state**

Run:

```powershell
git log --oneline -5
git status --short
```

Expected: recent history shows the spec, plan, and skeleton commits; the working tree is clean.

## Completion result

The P0 exit gate passes when `verification.md` shows: the seven-module graph builds clean on the frozen reference toolchain; `check` enforces the approved edges and a deliberately inserted violation fails it; the placeholder app installs and reaches `resumed` on the Xiaomi 14 Pro; the manifest baseline (no `INTERNET`, `allowBackup=false`, both exclusion rule files) is recorded; every version-catalog entry carries the dependency-review record above.

Remaining risk: none known beyond the recorded items — toolchain combination was verified by the clean build, the reference device records its actual system build, and the negative check proves the enforcement is effective.

### Execution verification record

To be appended here at completion: date, each Task's PASS/FAIL, negative-verification evidence, device build id, and remaining risk.

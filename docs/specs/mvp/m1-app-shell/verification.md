# MVP M1 — App Shell Verification

- **Spec:** [spec.md](spec.md) — `in-progress` from 2026-08-29.
- **Plan:** [plan.md](plan.md) — approved 2026-08-29.
- **Architecture:** [ADR 0016](../../../architecture/decisions/0016-application-facing-startup-gate-handoff.md) — accepted 2026-08-29.
- **Evidence rule:** a task's worker result is not acceptance; the main agent records acceptance only after raw-evidence and combined-state checks.

## Execution pointer

| Field | Value |
|---|---|
| Current task | M1-T02 — shared phase, presentation, chrome, icon, and theme system |
| `task_revision` / `attempt_id` | 1 / `M1-T02-A2` |
| Owner / write scope | `/root/m1_t02_core_ui_resume`; `core/ui/**` only |
| Dependencies / ready evidence | M1-T01 revision 3 accepted; commits `2b0f1ed` + `98a522a`; retained device XML 32/0/0/0; both review stages APPROVED; main full `check` PASS |
| Status / dispatch failures | `in_progress` / 0 |
| Remaining steps | fresh-worker audit of retained source/tests → fix findings/EOF formatting → rerun unit/device/full checks → exact commit/report → two-stage review → main acceptance |
| Next command | inspect retained core/ui source against plan/spec and rerun `:core:ui:testDebugUnitTest` under Temurin 17 |
| `blocked-on` | none |
| Frozen environment | worktree `D:\record\.worktrees\m1-app-shell`, branch `codex/m1-app-shell`; dependency baseline from M1-T01; A/A1 palette and exact Phase contract from active spec |
| Contract references | plan M1-T02; spec criteria 3–6 and 10 |
| Last raw evidence | A1 uncommitted source plus unit XML 14/0/0 and device XML 1/0/0; A1 cancelled without report |
| Last updated | 2026-08-30 Asia/Shanghai |

## Acceptance criteria

| ID | Criterion | Status | Raw evidence |
|---:|---|---|---|
| 1 | Lifecycle and architecture prerequisite | PASS | ADR 0016 accepted; five core docs synchronized; spec/plan/router lifecycle scans and M1-T00 commands below |
| 2 | Module boundary | PASS | M1-T01 module check, exact commit scope, spec review, and main full check |
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
| 14 | Startup budgets and isolation | pending | M1-T01 isolated package/device subgate PASS via retained A5 XML; numeric startup budgets remain M1-T10 |
| 15 | Dependency review | PASS | approved versions resolved; host/dependency scans, smoke test, spec/quality reviews, and production-target manifest checks pass |
| 16 | Offline routes | pending | none |
| 17 | Network/system-backup policy | pending | M1-T01 production target has no INTERNET and retains allowBackup=false; full source/merged/final gate remains T11 |
| 18 | No premature domain work | pending | M1-T01 schema/domain/module-scope subgate PASS; cumulative final gate remains T11 |
| 19 | Verification record | pending | this file initialized; completion requires all actual task/device/artifact evidence |

## Task log

| Task | Revision | Attempt | Owner | State | Dispatch failures | Main-agent accepted | Evidence |
|---|---:|---|---|---|---:|---|---|
| M1-T00 | 1 | `M1-T00-A1` | main agent | done | 0 | yes | accepted ADR/core docs, lifecycle/link/schema/diff checks below |
| M1-S01 | 1 | `M1-S01-A1` | `/root/m1_s01_line_endings` | done | 0 | yes | commit `979bc816`; spec/quality APPROVED; main focused/full checks PASS |
| M1-T01 | 1 | `M1-T01-A1` | `/root/m1_t01_build` | blocked | 0 | no | host gates usable; device install cancelled by user, 0 tests; no commit |
| M1-T01 | 1 | `M1-T01-A2` | `/root/m1_t01_build` | failed | 0 | no | install succeeded; 32 tests / 9 failures from incomplete revision-1 wiring/command; no commit |
| M1-T01 | 2 | `M1-T01-A3` | `/root/m1_t01_build` | cancelled | 0 | no | worker reported corrected 32/32, then quota interruption prevented final report/commit; raw pass XML was later overwritten |
| M1-T01 | 2 | `M1-T01-A4` | main-agent acceptance | blocked | 0 | no | host check PASS; device install cancelled/restricted, XML 0 tests |
| M1-T01 | 2 | `M1-T01-A5` | main-agent integration | failed | 0 | no | retained device XML 32/0/0/0 and spec APPROVED; quality review found full benchmark relative Activity launch |
| M1-T01 | 3 | `M1-T01-A6` | `/root/m1_t01_build` | done | 0 | yes | commits `2b0f1ed` + `98a522a`; A5 XML 32/0/0/0; reviews/main checks PASS |
| M1-T02 | 1 | `M1-T02-A1` | `/root/m1_t02_core_ui` | cancelled | 0 | no | worker disappeared without report/commit; usable uncommitted source and 15 passing tests retained |
| M1-T02 | 1 | `M1-T02-A2` | `/root/m1_t02_core_ui_resume` | in_progress | 0 | no | fresh worker resumes retained state; must audit/fix/verify/commit exact core/ui scope |
| M1-T03–M1-T11 | 1 | not started | unassigned | pending | 0 | no | none |

## Commands and results

### M1-T00 — ADR/core-document synchronization and lifecycle activation

**Command:** targeted core-fact scan across ADR 0016, architecture overview, data/storage, interaction rules, conventions, security, spec, and plan; require `RecordStartup`, one startup engine, `RecoveryDetected`, legal `Phase.Checking → Phase.Running → Phase.Recovering`, synchronous-correctness wording, and no unqualified direct Checking-to-Recovering transition.

**Environment:** branch `main`; pre-activation HEAD `87679d5`; Windows/PowerShell; date 2026-08-29 Asia/Shanghai; documentation-only worktree.

**Result:** PASS — ADR 0016 is Accepted with explicit user context; every required owning fact is present; the forbidden direct transition scan is clean.

**Command:**

```powershell
git diff --name-only
git diff --exit-code -- core/data/schemas
git diff --check
```

**Environment:** same as above.

**Result:** PASS — only ADR/core/lifecycle documentation paths changed; Room schema directory has no diff; whitespace/error check passes.

**Command:** relative Markdown-link resolution for ADR/core/spec/plan/router files plus unique active-spec scan.

**Result:** PASS — all local links resolve; lifecycle sources agree that M1 is the unique `in-progress` spec after activation; no second active spec exists.

**Remaining risk:** reference-device availability and every functional/benchmark acceptance row remain unverified. No functional source, dependency, schema, migration, environment, secret, CI/CD, publication, or deletion action occurred in M1-T00.

### M1-S01 — worktree baseline diagnosis

**Command:** initial isolated-worktree `check` using `D:\Android\jbr\bin\java.exe`.

**Result:** FAIL before project tests — JDK 25 caused Java target 25 / Kotlin fallback target 24 incompatibility. Classified environment binding, not repository failure.

**Command:** full isolated-worktree `check` using `C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe` after mirroring the ignored SDK-only `local.properties`.

**Result:** FAIL — 293 tests, 1 failure: `QueryManifestTest.frozenArtifactHashIsStable`; expected `14b381...efb1`, worktree bytes `a0a3ea...2719`. Module graph and API boundary checks passed.

**Raw evidence:** `core/data/build/test-results/testDebugUnitTest/TEST-com.paherdin.record.core.data.search.QueryManifestTest.xml` in the isolated worktree.

**Command:** same focused test on the clean main checkout with Temurin JDK 17.

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:data:testDebugUnitTest --tests com.paherdin.record.core.data.search.QueryManifestTest.frozenArtifactHashIsStable --console=plain
```

**Result:** PASS — main checkout source bytes hash to the frozen LF value. `Get-FileHash` proves main=`14b381...efb1`, worktree CRLF checkout=`a0a3ea...2719`, while the Git blob canonical LF bytes equal the frozen value.

**Classification:** pure cross-platform test-infrastructure defect exposed by required worktree isolation. M1-S01 may normalize CRLF to LF only before hashing and add regression coverage. It may not change the frozen constant, committed fixture content, generator semantics, production code, schema, or M1 acceptance.

### M1-S01 — accepted repair evidence

**Changed file:** `core/data/src/test/kotlin/com/paherdin/record/core/data/search/QueryManifestTest.kt` only.

**Commit:** `979bc816e743140e47d534cf08d9e4afe34b7954` (`test(search): canonicalize frozen manifest hash line endings`).

**Worker red/green evidence:** original focused failure reproduced; new regression first failed because `canonicalUtf8BytesForFrozenHash` was absent; after implementation the complete `QueryManifestTest` class reports 5 tests, 0 failures, 0 errors, 0 skipped. Worker full `check` reports BUILD SUCCESSFUL and 31 XML suites / 294 tests / 0 failures / 0 errors.

**Spec-compliance review:** APPROVED — LF/CRLF/lone-CR equivalence, UTF-8 boundary, private helper, unchanged frozen constant, and one-file scope all match.

**Code-quality review:** APPROVED — no Critical, Important, or Minor findings; normalization order does not double-handle CRLF or alter non-line content.

**Main-agent focused command:**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:data:testDebugUnitTest --tests com.paherdin.record.core.data.search.QueryManifestTest --console=plain
```

**Result:** PASS, exit 0.

**Main-agent combined command:**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain check --console=plain
```

**Result:** PASS — `BUILD SUCCESSFUL in 41s`, 249 actionable tasks (7 executed, 242 up-to-date), `checkModuleDependencies: PASS`, no test failure.

### M1-T01-A1 — reference-device preflight

**Command:**

```powershell
& 'C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
```

**Result:** BLOCKED — ADB server started successfully, but the device list is empty. No build file, package, device setting, or app data was changed. M1-T01 functional dispatch remains withheld until the approved Xiaomi reference device is connected and reports state `device`.

**Recovery check:**

```powershell
$adb = 'C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb kill-server
& $adb start-server
& $adb devices -l
```

**Result:** BLOCKED unchanged — the absent daemon was started successfully, then the relisted device table remained empty. The blocker is an unattached/unavailable external reference device, not a stale ADB server.

**Resume preflight after user connected the device:**

```powershell
$adb = 'C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
& $adb shell getprop ro.product.model
& $adb shell getprop ro.product.marketname
& $adb shell getprop ro.build.version.sdk
& $adb shell getprop ro.build.fingerprint
```

**Result:** PASS — serial `c55ad7e4`, state `device`, product/device `shennong`, model `23116PN5BC`, market name `Xiaomi 14 Pro`, API 36, release fingerprint `Xiaomi/shennong/shennong:16/BP2A.250605.031.A3/OS3.0.307.0.WNBCNXM:user/release-keys`. M1-T01-A1 may dispatch.

### M1-T01-A1 — build compatibility evidence and device blocker

**Worker:** `/root/m1_t01_build`

**RED:** approved Serialization smoke test compiled before the dependency delta and failed as expected (exit 1, 46.9 s) with unresolved `serializer`, `Json`, and `junit` references.

**GREEN:** the exact serialization/module/API command passed on rerun (exit 0, 5.3 s); smoke XML reports 1 test, 0 skipped, 0 failures, 0 errors; `checkModuleDependencies` reports the exact seven production modules plus `:benchmark`; `apiBoundaryCheck` reports 4 positive and 25 negative fixtures.

**Host combined result:** full `check` exit 0 in 2m38s (252 tasks); app/benchmark dependency reports and `:app:assembleBenchmark :benchmark:assembleBenchmark` exit 0. Resolved versions are Navigation 2.9.8, Serialization 1.9.0, Lifecycle 2.10.0. Target metadata is `com.paherdin.record.benchmark`; test metadata is `com.paherdin.record.benchmark.test`; target benchmark APK is non-debuggable/profileable and has no INTERNET permission. The AndroidX test-tool APK retains test-only permissions including INTERNET; criterion 17 remains production-target scoped and pending final artifact verification.

**Mandatory device command result:** BLOCKED — exit 1 in 36.7 s with `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`; UTP started/finished 0 tests. Report: `benchmark/build/reports/androidTests/connected/benchmark/index.html`. Per device rules the worker stopped immediately, did not rerun, did not commit, and did not execute `pm clear` or manual deletion.

**Preserved owned changes:** nine tracked build/catalog/target files plus one ignored smoke-test source. The approved plan path is corrected from package segment `/build/` (matched by `.gitignore` `**/build/`) to `/compat/`; attempt A2 must recreate/copy the same test at `app/src/test/java/com/paherdin/record/compat/SerializationPluginSmokeTest.kt` and may remove the ignored old file only after explicit deletion approval.

**Acceptance mapping:** criterion 2 has worker PASS evidence but awaits main review; criterion 14 BLOCKED; criterion 15 has worker PASS evidence but awaits review; criterion 17 has production-target partial evidence; criterion 18 remains unverified because the mandatory stop preceded final schema/diff gates.

### M1-T01-A2 — retry authorization

**Changed conditions recorded 2026-08-29:** the user stated “已允许 USB 安装，同意迁移并删除旧测试文件”. This authorizes only the following bounded file operation: recreate identical smoke-test content/package at `app/src/test/java/com/paherdin/record/compat/SerializationPluginSmokeTest.kt`, verify the new file, then remove the ignored untracked `app/src/test/java/com/paherdin/record/build/SerializationPluginSmokeTest.kt`. Risk is limited to the agent-created untracked smoke source; no user/production data is in scope.

The same task revision resumes under attempt A2 because behavior and acceptance are unchanged. The worker must not repeat destructive device/package operations, must not run `pm clear`, and must still stop if the device install prompt is cancelled or restricted again.

### M1-T01-A2 — failed device infrastructure contract

**Host/final gates:** compat smoke GREEN PASS (1/0/0); full `check` PASS; schema diff and `git diff --check` PASS; exact owned scope PASS; package/permission/dependency assertions PASS.

**Device command:** installation succeeded, then the revision-1 focused suite failed: `tests=32`, `failures=9`, `errors=0`, `skipped=0`, time 123.474 s. Raw XML: `benchmark/build/outputs/androidTest-results/connected/benchmark/TEST-23116PN5BC - 16-_benchmark-.xml`.

**Root cause 1:** `BenchmarkWiringSmokeTest.kt` launched `$targetPackage/.MainActivity`; with target `com.paherdin.record.benchmark`, Android resolved nonexistent `com.paherdin.record.benchmark.MainActivity`. APK metadata/badging shows the actual class remains `com.paherdin.record.MainActivity`.

**Root cause 2:** revision-1 command passed only the class argument. `TimelineHarnessSelfCheckTest` requires `p6ExpectedFirstDayHash`, `p6ExpectedLastDayHash`, and `p6ExpectedEmptyDayHash`; preparation failed before seed, leaving the isolated shadow DB at 0 rows and causing the remaining failures.

**Classification:** test-infrastructure/plan contract failure, not a product or P6 technical conclusion. The same command cannot be retried unchanged. M1-T01 revision 2 adds the wiring-test file to scope and restores the P6-approved oracle/hash preparation command. Goal and acceptance criteria are unchanged; no spec behavior revision is required.

### M1-T01-A3/A4 — revision-2 implementation and retained-evidence blocker

**A3 last observable worker state:** corrected revision-2 device command reported exit 0 in 43.2 s with 32/32 tests and the three hashes `7432b279...5511d`, `7c055c9a...0fd5`, and `e3b0c442...b855`. The worker then hit an external agent-usage limit while running final host gates, so agentic workflow records A3 as `cancelled`, not DONE. Implementation changes remain preserved and uncommitted.

**Main-agent host acceptance command:** full `check` under Temurin 17.

**Result:** PASS — `BUILD SUCCESSFUL in 43s`, 252 actionable tasks (7 executed, 245 up-to-date), exact module check PASS.

**Main-agent corrected device command:** emitted/validated all three P6 hashes, then invoked the revision-2 focused suite. Installation returned `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`; UTP ran 0 tests and overwrote the prior device XML with a 0-test result. No `pm clear`, manual uninstall, or source mutation occurred.

**Evidence resolution:** the unretained A3 message did not substitute for raw XML after A4 overwrote it. A5 supplied the required fresh corrected pass and the XML was copied/read before any further device run.

### M1-T01-A5 — retained revision-2 pass and integration commit

**User condition:** device unlocked and ready for the install prompt.

**Corrected command result:** PASS — oracle emission succeeded; first hash `7432b2796ab8de33c6c6bbf54ac86a8f81db500f7b904c813b9f5149a885511d`, last hash `7c055c9a0bdd0e1976b0e847fbecda5a0fc9039c643d5e2cc4c98c5ce9c10fd5`, empty hash `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`; focused suite exit 0, `BUILD SUCCESSFUL in 40s`.

**Retained raw evidence:** `.superpowers/evidence/m1-t01-a5/TEST-23116PN5BC-16-benchmark.xml`; 32 tests, 0 failures, 0 errors, 0 skipped, time 18.004 s; SHA-256 `5AB2E23E5059FAE3C562429031B93EE68EB0EFCCB8647A9DECA5C023C5A37FC8`.

**Main host combined result:** full `check` PASS — `BUILD SUCCESSFUL in 43s`, 252 actionable tasks (7 executed, 245 up-to-date), module check PASS.

**Implementation commit:** `2b0f1ed` (`build: establish M1 Compose navigation baseline`), exactly 11 authorized paths. Post-commit scope comparison, `git show --check`, schema no-diff, and retained XML parse all PASS.

**Integration deviation:** the first pre-commit ownership script contained a malformed PowerShell `throw` token and therefore did not enforce its observed extra main-owned verification path. Only the explicit 11-file list was staged; the commit was subsequently checked from `diff-tree` against the exact allowlist and passed. The malformed check is not used as evidence.

**Spec-compliance review:** APPROVED — revision-2 commit exactly matched all 11 authorized paths and criteria 2/14-isolation/15/17-production/18 boundaries.

**Code-quality review:** CHANGES_REQUIRED — `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineBenchmark.kt:416` still launched `$targetPackage/.MainActivity`, which resolves to nonexistent `com.paherdin.record.benchmark.MainActivity` during the full benchmark. Revision 3 must use `TARGET_MAIN_ACTIVITY_CLASS` there and add a root source scan preventing recurrence. No other quality finding was reported.

### M1-T01-A6 — quality correction and final acceptance

**Worker commit:** `98a522a4f1c959eead1984aedff3cd9777a5227b` (`test(benchmark): enforce fully qualified target activity`), exactly `build.gradle.kts` and `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineBenchmark.kt`.

**RED/GREEN:** the new machine scan first failed on the exact `$targetPackage/.MainActivity` occurrence; after the one-line consumer fix, `checkModuleDependencies` passed with module, refresh-write, and target-activity scans clean. `:benchmark:assembleBenchmark`, full `check`, schema no-diff, `git diff --check`, and exact two-file ownership checks passed.

**Revision-3 spec review:** APPROVED — exact two-file scope, no P6 logic change, retained A5 XML valid.

**Revision-3 quality review:** APPROVED — the prior Important finding is closed; no Critical, Important, or Minor findings remain in the delta or combined T01 state.

**Main-agent fresh combined result:** full `check` PASS — `BUILD SUCCESSFUL in 7s`, 252 actionable tasks (7 executed, 245 up-to-date), module/refresh/target-activity scans clean.

**Main acceptance:** M1-T01 revision 3 accepted. Criteria 2 and 15 are PASS; criterion 14 package-isolation subgate, criterion 17 production-target subgate, and criterion 18 no-domain/schema subgate are retained for their later cumulative gates.

## Deviations and remaining risk

- No plan deviation in M1-T00.
- Environment-command correction: all plan Gradle commands now bind Temurin JDK 17.0.20+8 rather than the incompatible JDK 25 Android JBR; no behavior/criterion changed.
- M1-S01 is an accepted unplanned but bounded test-infrastructure repair required to establish the clean worktree baseline. It does not count as an M1 functional task or change an approved contract.
- The physical reference device was disconnected during plan drafting; M1-T01 must recheck it before any build-file change.
- M1-T01-A1 was initially blocked by an empty device list; the user connected the approved Xiaomi reference device and the fresh identity/state preflight passed. No dispatch failure was counted.
- M1-T01-A1 then became externally blocked by Xiaomi USB-install confirmation/cancellation. This is not counted as a dispatch failure and cannot be retried until the user changes the device permission state.
- M1-T01-A3 is recorded `cancelled` because the worker's usage-limit failure prevented a completion report/commit, despite usable last-observable device output. M1-T01-A4 is an external install blocker, not a dispatch failure. Neither permits a false PASS.
- Plan path correction: the smoke-test package changes from `/build/` to `/compat/` solely because repository `.gitignore` treats any `/build/` segment as generated output. The test content/behavior and acceptance command remain unchanged apart from its class name package.
- The separate destructive approval for clearing `com.paherdin.record.benchmark` has not been requested or granted; it is required only at M1-T10.
- M1 remains incomplete; criteria 2–19 are pending except the initialized lifecycle record in criterion 1.

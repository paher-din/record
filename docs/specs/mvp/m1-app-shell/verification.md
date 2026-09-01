# MVP M1 — App Shell Verification

- **Spec:** [spec.md](spec.md) — `in-progress` from 2026-08-29.
- **Plan:** [plan.md](plan.md) — approved 2026-08-29.
- **Architecture:** [ADR 0016](../../../architecture/decisions/0016-application-facing-startup-gate-handoff.md) — accepted 2026-08-29.
- **Evidence rule:** a task's worker result is not acceptance; the main agent records acceptance only after raw-evidence and combined-state checks.

## Execution pointer

| Field | Value |
|---|---|
| Current task | M1-T04/T05/T06 — Finance/Notes/Timeline availability surfaces (parallel slice) |
| `task_revision` / `attempt_id` | 1 / host slice dispatched |
| Owner / write scope | finance/notes/timeline workers; `feature/finance/**`, `feature/notes/**`, `feature/timeline/**`; main agent owns device gates, commits, reviews |
| Dependencies / ready evidence | M1-T02 revision 6 accepted; M1-T03 revision 3 accepted (commits `9ac16b3` + `af9f03f`, reviews APPROVED, device XML 165/0/0/0) |
| Status / dispatch failures | `in_progress` / 0 |
| Remaining steps | per feature: host gates → main-agent direct-instrumentation device gate (per-package app-op needs user approval) → boundary scan on staged files → commit → two-stage reviews → acceptance |
| Next command | collect T06 host report, then run serial feature device gates after per-package app-op approval |
| `blocked-on` | device app-op approval for `com.paherdin.record.feature.{finance,notes,timeline}.test` pending user consent (plan M1-T04/T05/T06 execution correction) |
| Frozen environment | worktree `D:\record\.worktrees\m1-app-shell`, branch `codex/m1-app-shell`; dependency baseline from M1-T01; A/A1 palette and exact Phase contract from active spec |
| Contract references | plan M1-T04/T05/T06; spec criteria 3–5, 7–9, 12 |
| Last raw evidence | T03 accepted: device 165/0/0/0 XML `3D213C0F…9BB67F`; feature host slices green, device gates pending |
| Last updated | 2026-09-01 Asia/Shanghai |

## Acceptance criteria

| ID | Criterion | Status | Raw evidence |
|---:|---|---|---|
| 1 | Lifecycle and architecture prerequisite | PASS | ADR 0016 accepted; five core docs synchronized; spec/plan/router lifecycle scans and M1-T00 commands below |
| 2 | Module boundary | PASS | M1-T01 module check, exact commit scope, spec review, and main full check |
| 3 | Theme/localization/launch identity | PASS (T02 subgate) | A12 device 11/11 + host PASS: A/A1 non-error roles, semantic error roles, light/dark, resource-backed Chinese copy, local-vector icons; packaged-resource/no-data-preframe subgates remain T08/T11 |
| 4 | Accessibility | PASS (T02 subgate) | A12 device 11/11: heading/state/live-region semantics, 48-dp Retry, 200%-font full-bounds/no-overlap/focus-action; selected-tab/focus subgate remains T08 |
| 5 | Shared presentation | PASS (T02 subgate) | A12 device 11/11 four-state semantics; error never empty, partial never ready, Retry callback verified; production fixtures carry no synthetic business data |
| 6 | Operation phases | PASS | PhaseTest 4/0/0/0 on the exact closed set/transitions; no alias flags found by scans |
| 7 | Default shell | pending | none |
| 8 | Global actions | pending | none |
| 9 | Typed/source-owned routing | pending | none |
| 10 | Chrome rules | PASS (policy subgate) | ShellChromePolicyTest 6/0/0/0 exhaustive resolve(kind, phase); runtime bar-visibility/back-lock device gate remains T08 |
| 11 | Independent retention | pending | none |
| 12 | Timeline date | pending | none |
| 13 | Safe startup | PASS (coordinator subgate) | T03 device 165/0/0/0: one `StartupEngine`, `Checking→RecoveryDetected→Recovering` ordering, resolver non-cancellable boundary, open() compatibility identity; app-side `RestoreRecoveryRoute` routing subgate remains T08 |
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
| M1-T02 | 1 | `M1-T02-A2` | `/root/m1_t02_core_ui_resume` | blocked | 0 | no | audit found theme/semantics/test gaps; unit compile PASS; device list became empty before behavior RED |
| M1-T02 | 1 | `M1-T02-A3` | `/root/m1_t02_core_ui_resume` | cancelled | 0 | no | targeted RED hit the 304-second host timeout with no Gradle output or assertion; no production changes |
| M1-T02 | 1 | `M1-T02-A4` | `/root/m1_t02_core_ui_resume` | cancelled | 0 | no | test started but hung at 0/1 for 1176 seconds; user requested stop; UTP classified the interruption as infrastructure |
| M1-T02 | 2 | `M1-T02-A5` | `/root/m1_t02_core_ui_resume` | failed | 0 | no | standard command timed out at 184 seconds; main postmortem proved OEM background-start rejection and obtained the expected palette RED with an exact shell Intent |
| M1-T02 | 2 | `M1-T02-A6` | `/root/m1_t02_core_ui_resume` | blocked | 0 | no | host implementation/checks PASS; exact 13-file change remains uncommitted; device GREEN waits for explicit temporary test-package app-op approval |
| M1-T02 | 2 | `M1-T02-A7` | `/root/m1_t02_core_ui_resume` | failed | 0 | no | UTP reinstall reset the pre-set op to ignore; combined command timed out; exact processes stopped and package removal proved rollback |
| M1-T02 | 3 | `M1-T02-A8` | `/root/m1_t02_core_ui_resume` | failed | 0 | no | host/device gates PASS and commit `d58ae68`; independent spec review found four existing-contract violations |
| M1-T02 | 4 | `M1-T02-A9` | `/root/m1_t02_spec_fix` | failed | 0 | no | host corrections PASS; first full device run had 10 pass plus one ActivityScenario destroyed-Activity infrastructure NPE |
| M1-T02 | 4 | `M1-T02-A10` | main-agent device gate | failed | 0 | no | host/device gates PASS and spec APPROVED; quality review found one Important and two Minor gaps |
| M1-T02 | 5 | `M1-T02-A11` | `/root/m1_t02_spec_fix` | failed | 0 | no | host gates PASS; device 11/3 exposed over-strong device-focus assertions and a UTP destroyed-Activity race; led to approved revision 6 |
| M1-T02 | 6 | `M1-T02-A12` | `/root/m1_t02_spec_fix` + main device gate | done | 0 | yes | commit `37032a3`; direct instrumentation 11/11 OK and full rollback; spec + quality reviews APPROVED; main full `check` PASS |
| M1-T03 | 1 | `M1-T03-A1` | `/root/m1_t03_core_data` | blocked | 0 | no | host gates PASS; full device suite hit pre-existing P4 SearchBenchmark airplane-mode preconditions (165/167); worker stopped without commit |
| M1-T03 | 2 | `M1-T03-A2` | main-agent device gate | done | 0 | yes | notClass correction recorded in plan; device 165/0/0/0; commit `9ac16b3`; spec APPROVED; quality CHANGES_REQUIRED (Important: resolver cancellability) |
| M1-T03 | 3 | `M1-T03-A3` | `/root/m1_t03_core_data` + main device gate | done | 0 | yes | commit `af9f03f`; device 165/0/0/0 XML `3D213C0F…9BB67F`; spec + quality rereviews APPROVED; main full `check` PASS |
| M1-T04 | 1 | host slice | `/root/m1_t04_finance` | in_progress | 0 | no | RED→GREEN host PASS; boundary content scan clean; device gate pending |
| M1-T05 | 1 | host slice | `/root/m1_t05_notes` | in_progress | 0 | no | RED→GREEN host PASS; boundary scan exit 1; device gate pending |
| M1-T06 | 1 | host slice | `/root/m1_t06_timeline` | in_progress | 0 | no | RED→GREEN host PASS; unit 7/0/0/0; SaverScope round-trip correction in flight; device gate pending |
| M1-T07–M1-T11 | 1 | not started | unassigned | pending | 0 | no | none |

## Commands and results

### M1-T02-A1/A2 — retained implementation audit and device blocker

**A1 state:** worker disappeared without a completion report or commit. Thirteen untracked `core/ui/src` files remained. Raw XML showed PhaseTest 4/0/0/0, RecordPaletteTest 5/0/0/0, ShellChromePolicyTest 5/0/0/0, and SharedContentTest 1/0/0/0. Agentic state is `cancelled`, not DONE.

**A2 audit findings:** all retained files lacked EOF newlines; ShellChrome totality coverage was partly tautological; SharedContent device coverage did not prove all state/heading/retry/font/target semantics; SharedContent lacked `stateDescription` and polite live-region semantics; RecordTheme left multiple non-error Material roles at default non-A/A1 values.

**A2 test-first changes:** strengthened `PhaseTest.kt`, `ShellChromePolicyTest.kt`, and `SharedContentTest.kt` only. The combined unit/androidTest compilation initially failed because a new test referenced a future ready-state resource; the test was corrected to assert the approved Chinese semantic value directly. Rerun exit 0; unit XML remains Phase 4/0/0/0, Palette 5/0/0/0, Chrome 5/0/0/0.

**Targeted device RED command:** attempted `SharedContentTest#themeDerivesEveryNonErrorRoleFromTheApprovedPalette`; Gradle failed before assertion with `No connected devices!`. ADB restart briefly listed `emulator-5554 offline`, then the final list was empty. The worker stopped without production fixes, full check, staging, or commit.

**Classification:** external device prerequisite blocker with useful audit/test work preserved. No dispatch failure counted. A3 must first restore the approved device, then execute the intended RED before modifying production theme/semantics.

### M1-T02-A3 — device resume preflight

**Command:** `adb devices -l` plus market/model/API/fingerprint properties.

**Result:** PASS — serial `c55ad7e4`, state `device`, Xiaomi 14 Pro / `23116PN5BC`, API 36, fingerprint `Xiaomi/shennong/shennong:16/BP2A.250605.031.A3/OS3.0.307.0.WNBCNXM:user/release-keys`. The same task revision resumes with the audited source/test state unchanged.

**Targeted RED result:** CANCELLED / incomplete — the exact `SharedContentTest#themeDerivesEveryNonErrorRoleFromTheApprovedPalette` command reached the 304-second host timeout with exit 124 and no Gradle console output. It did not produce an install error or test assertion, made no production change, and therefore supplies no RED/GREEN conclusion.

### M1-T02-A4 — stale-process cleanup and bounded retry

**Changed condition:** post-timeout inspection found `com.paherdin.record.core.ui.test` still running on the reference device. The main agent ran only `adb shell am force-stop com.paherdin.record.core.ui.test`; a follow-up process scan was empty and `adb devices -l` still reported serial `c55ad7e4` as `device`. No package data, installation, source, or device setting was deleted or reset.

**Retry result:** CANCELLED / incomplete by explicit user request after the command again produced no console output for a prolonged period. Post-stop raw artifacts prove UTP did start `SharedContentTest#themeDerivesEveryNonErrorRoleFromTheApprovedPalette`: `test-results.log` contains the start event only, and the generated XML records one test, zero failures/errors, elapsed 1176.351 seconds, plus `TEST_INTERRUPTED` / `AndroidInstrumentationDriver was canceled`. The Gradle daemon records `Tests 0/1 completed` followed by client disconnection. This is not a product PASS or FAIL.

**Exact stop evidence:** after the agent interruption left child work alive, the main agent stopped only wrapper PID 97644 and ran `adb shell am force-stop com.paherdin.record.core.ui.test`. Follow-up host/device process scans were empty for those exact targets and serial `c55ad7e4` remained `device`. The shared Gradle daemon, installed package, package data, device settings, and source were not cleared or deleted.

### M1-T02-A5 — bounded test-harness revision

**Classification:** the targeted test's mutable dark-theme transition plus `runOnIdle`/`waitForIdle` path can hang without reaching the intended palette assertion. This is a test-infrastructure failure; the approved A/A1 palette behavior and task acceptance do not change.

**Revision-2 contract:** modify the targeted test only so one bounded composition captures both explicit light and dark `MaterialTheme.colorScheme` values without state mutation, `runOnIdle`, or an unbounded idle wait. Keep the same exhaustive role assertions. Use a 3-minute host timeout for the exact targeted device RED and do not modify production until it fails on the expected non-A/A1 role assertion. Any install restriction, disconnect, timeout, or different failure stops the attempt.

**Standard-command result:** FAILED / incomplete — the revised harness still timed out after 184.024 seconds, exit 124, with no assertion output. It changed only `SharedContentTest.kt`; no production file was modified and no GREEN or commit followed.

**Main-agent postmortem:** direct instrumentation reproduced the hang. A live JDB `where all` proved the runner thread was waiting in `Instrumentation.startActivitySync` through `ActivityScenario.launch`, before the test body. Xiaomi's ActivityTaskManager simultaneously logged `Abort background activity starts from <test uid>` and result code 102 for the exact `androidx.activity.ComponentActivity` Intent. The package remained awake/unlocked, its merged test manifest contained the exported host Activity, and the Activity launched normally from shell; therefore the failure is the OEM background-start policy, not Compose, RecordTheme, or the assertion harness.

**Expected RED recovered:** while the direct instrumentation remained at the blocked start, the main agent launched the exact action/category/component/flags recorded by ActivityScenario from shell. The rule immediately resumed and AndroidJUnitRunner reported 1 test, 1 failure, 0 ignored with the expected `AssertionError: unexpected non-error roles` at `SharedContentTest.kt`. This is sufficient test-first evidence for the audited RecordTheme production fix.

**Device-policy evidence:** `appops get com.paherdin.record.core.ui.test` reports `MIUIOP(10021): ignore` with the rejection timestamp. No app-op or phone setting has been changed. A6 may implement production and run host checks now; device GREEN requires separate approval to set only this disposable test package's op to `allow`, followed by restore to `ignore` or automatic UTP uninstall. The risk is limited to that test APK being able to show a window from background while installed.

### M1-T02-A6 — host GREEN and device-approval blocker

**Owned implementation:** exactly the 13 planned `core/ui/src` files. RecordTheme now maps every non-error Material role from the frozen A/A1 references while retaining semantic error roles. SharedContent supplies distinct loading/empty/error/ready state semantics, merged headings where applicable, polite live regions for announced states, resource-backed Chinese copy, and a minimum 48-dp Retry width/height. The retained static light/dark palette harness no longer mutates theme state or calls unbounded idle helpers. All owned files have uniform CRLF, a final line ending, and no trailing whitespace.

**Host commands:** `:core:ui:testDebugUnitTest :core:ui:compileDebugAndroidTestKotlin` PASS in 27 seconds, followed by full host `check --console=plain` PASS in 111.2 seconds. After line-ending normalization the focused host command passed again in 6.4 seconds and a fresh full `check` passed in 68.4 seconds with 257 tasks (25 executed, 232 up-to-date); `checkModuleDependencies` passed.

**Raw unit XML:** Phase 4/0/0/0, RecordPalette 5/0/0/0, ShellChromePolicy 5/0/0/0. Exact planned-file scope, unchanged `core/ui/build.gradle.kts`, schema no-diff, source-boundary scan, tracked `git diff --check`, EOF, and trailing-whitespace checks pass.

**State:** BLOCKED, not committed or accepted. No device/app-op action occurred in A6. The main agent requested explicit approval for the temporary test-package-only `MIUIOP(10021)` change; production/debug package state is outside the request.

### M1-T02-A7 — approved temporary Xiaomi app-op

**Approval:** the user explicitly replied “同意” on 2026-08-30 after the main agent named exact package `com.paherdin.record.core.ui.test`, op `MIUIOP(10021)`, reason, background-window risk, and rollback.

**Preflight/action:** the Xiaomi reference device remained serial `c55ad7e4` in state `device`. The main agent ran `appops set --user 0 com.paherdin.record.core.ui.test 10021 allow`; immediate readback returned `MIUIOP(10021): allow`. This affects only the disposable test APK. A7 must run the device gate and then prove the op is restored to `ignore` or the package was removed by UTP.

**Result:** FAILED / incomplete — the exact combined Gradle command timed out after 304.040 seconds with exit 124 and no test assertion output. Post-timeout inspection found wrapper PID 94912, test PID 17491, and `MIUIOP(10021): ignore` with a fresh rejection timestamp. This proves UTP's reinstall reset the op before instrumentation. The main agent stopped the exact wrapper/package processes; UTP then removed the package, and `appops get` returned `No UID`, proving the temporary setting no longer existed. No source change or commit occurred in A7.

### M1-T02-A8 — corrected app-op timing

**Revision-3 contract:** keep one exact combined Gradle command. Poll only until UTP has installed and started the disposable test package, then apply the already approved `MIUIOP(10021)=allow` and launch the exact first pending ActivityScenario host Intent once. Do not start another Gradle/UTP run or repeatedly foreground the Activity. Accept only complete unit/device XML plus UTP package removal or `ignore` readback; otherwise stop as incomplete.

**Result:** device GREEN PASS — retained `.superpowers/evidence/m1-t02-a8/TEST-23116PN5BC-16-core-ui.xml` reports 11 tests, 0 failures, 0 errors, 0 skipped, 24.481 seconds; exit-code artifact is 0; SHA-256 `43A0EFDC3C452A7B04F57DF803AB0DF70C1867CE52FF324E26F9BE3C7C5F8A81`. UTP removed the disposable test package, `appops get` returned `No UID`, and no exact wrapper/test process remained. Main-agent host `:core:ui:testDebugUnitTest :core:ui:compileDebugAndroidTestKotlin check` passed in 50 seconds with 263 tasks and module checks clean. Implementation commit `d58ae68` contains exactly the planned 13 files.

**Spec review:** CHANGES_REQUIRED. ReadyBody did not guarantee a heading; Editor allowed Back during locked `Phase.Saving`; RecordIcons/RecordPalette/RecordTokens/theme/tag properties violated lowerCamelCase or compile-time `UPPER_SNAKE_CASE`; and the vector builder used `24.dp` instead of a semantic icon-size token. A8 is not accepted despite passing tests.

### M1-T02-A9 — spec-review corrections

**Revision-4 contract:** add failing coverage for Ready heading and Editor Saving Back lock, then correct only the four reviewed gaps within the existing 13-file ownership. Rename properties/constants mechanically without changing values or public responsibilities; source local-vector dimensions from a new semantic `RecordTokens` icon-size value. Rerun all host/device evidence and both review stages before acceptance.

**RED/GREEN host evidence:** Editor/Saving focused test first failed 1/1 at the old allow-Back behavior, then passed after correction. The strengthened Ready test compiled before production change; a bounded direct single-method run with one exact shell Intent failed 1/1 on missing `Heading` while retaining `StateDescription='内容已就绪'` and its child. After correction, task-local unit/androidTest compilation passed; unit XML is 15/0/0/0. Post-normalization full host `check` passed in 15 seconds with 257 tasks and module checks clean. Exact nine-file subset, no build/schema, naming, CRLF, whitespace, and boundary scans pass.

**First device GREEN attempt:** FAILED / incomplete as test infrastructure — the full suite ran 11 tests; 10 passed and `retryTargetIsAtLeastFortyEightDp` failed before its product assertion with `NullPointerException: Cannot run onActivity since Activity has been destroyed already`. XML SHA-256 is `43C12FCF718C04A45A1B44898F8DCFC3D350559E456D1003D34C7D5D4A3B9E59`. UTP then removed the package; no UID, app-op, test process, or Gradle wrapper remained. The changed retry condition is a completely absent package/old task, matching successful A8 startup state.

### M1-T02-A10 — cold-package device retry

**Contract:** no source, assertion, command, timeout, or app-op scope change. Start from package/app-op absent, run the same one-job corrected-timing gate once, and accept only 11/0/0/0 plus package/app-op rollback.

**Result:** PASS — the cold-package precondition was empty; the same combined command completed in 35 seconds with 11/0/0/0 and Gradle exit 0. Retained `.superpowers/evidence/m1-t02-a10/TEST-23116PN5BC-16-core-ui.xml` SHA-256 is `8932FE83A940B94986863C4EA76220C18FFDB5C52E39DD05772D39D5372C7677`. UTP removed the package and app-op. Correction commit `5f40add` contains exactly nine authorized files. Spec rereview returned APPROVED.

**Quality review:** CHANGES_REQUIRED. The Ready wrapper's own generic Heading could duplicate the real visible ready title; 200%-font coverage did not prove bounds/non-overlap/focusability; theme coverage omitted the four semantic error roles and their separation from warm secondary.

### M1-T02-A11 — quality-review corrections

**Revision-5 contract:** first make the ready test fixture expose its real visible heading and assert exactly one heading; the revision-4 wrapper must fail with a duplicate. Then remove Heading from the non-merging state wrapper and explicitly require readyContent to emit exactly one visible heading while keeping descendant text/actions addressable. Strengthen the existing 200%-font and light/dark error-role tests without adding new files or behavior.

**Host result:** PASS — exactly SharedContent production/test files changed; task-local unit/androidTest compilation and fresh full `check` passed; unit XML is 15/0/0/0; scope/schema/build/diff/CRLF/boundary gates passed.

**Device result:** FAILED as test infrastructure — 11 tests ran, with `retryTargetIsAtLeastFortyEightDp` failing before assertion because ActivityScenario's Activity was already destroyed, and both 200%-font cases failing because the test required `Focused=true` after invoking the exposed `RequestFocus` action. The node dump proves `RequestFocus`, OnClick, enabled Button role, and bounds were present. XML SHA-256 is `60D5F379168FC0A9440350361D8EE25028E761D0FF02EB4A1A3573D1D745B949`. UTP removed the package/app-op.

### M1-T02-A12 — direct Xiaomi instrumentation fallback

**Revision-6 contract:** focusability requires the semantics `RequestFocus` action, not OEM focus-state mutation. After host GREEN, install the disposable compiled test APK, set its already approved `MIUIOP(10021)=allow` before instrumentation, run the complete SharedContentTest class once directly, require 11/0/0/0, then uninstall the exact test package and prove no UID/app-op/process remains.

**Host result:** PASS — `SharedContentTest.kt` now asserts `SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus)` instead of `assertIsFocused`; the two obsolete imports were removed and a full-tree grep shows no residual focus-state assertion. Task-local `:core:ui:testDebugUnitTest :core:ui:compileDebugAndroidTestKotlin` PASS with unit XML 15/0/0/0 (Phase 4 + Palette 5 + Chrome 6); `:core:ui:assembleDebugAndroidTest` rebuilt `ui-debug-androidTest.apk` (31,252,462 bytes); fresh full `check` BUILD SUCCESSFUL with 257 tasks.

**Device result:** PASS — preflight proved the device awake with no package/app-op/test process present. After `adb install` (Success), `appops set --user 0 com.paherdin.record.core.ui.test 10021 allow` read back `MIUIOP(10021): allow`. Direct `am instrument -w -r -e class com.paherdin.record.core.ui.SharedContentTest com.paherdin.record.core.ui.test/androidx.test.runner.AndroidJUnitRunner` returned `OK (11 tests)` in 21.573 s. Raw output retained at `.superpowers/evidence/m1-t02-a12/am-instrument-raw.txt`, SHA-256 `0D3EF803B37EEE60C9353549DD863DB72239B40870F84141259B75FA7C8D1B68`. Uninstall returned Success; package list, `appops get` (`No UID`), and process scan were all empty — full rollback.

**Acceptance:** implementation commit `37032a3` contains exactly `SharedContent.kt` and `SharedContentTest.kt`. Spec-compliance review APPROVED (revision 5/6 contracts, A8 four items unregressed, A10 three findings closed, criteria 3/4/5/6/10 in T02 scope). Code-quality review APPROVED (Important + two Minor closed, no new findings). Main-agent fresh full `check` PASS in 38 s (257 tasks, exit 0). M1-T02 accepted; criteria 3/4/5/6/10 T02 subgates are PASS, app-level subgates remain with their owning tasks.

### M1-T03-A1 — worker host PASS and airplane-mode device blocker

**Host evidence:** RED first (`Unresolved reference 'RecordStartup'/'StartupProgress'/'StartupResult'` in both test sets); after implementation `:core:data:testDebugUnitTest :core:data:apiBoundaryCheck` PASS with 309/0/0/0 (RecordStartupTest 15/15) and `apiBoundaryCheck: PASS (5 positive, 26 negative)`; schema diff exit 0; full `check` PASS 257 tasks.

**Device result:** BLOCKED — install succeeded and 165/167 tests passed, but the two pre-existing P4 `SearchBenchmarkTest` measured runs failed on their own frozen preconditions (`airplane mode must be enabled`, device `airplane_mode_on=0`), independent of any M1 change. Worker stopped without commit.

### M1-T03-A2 — notClass device-gate correction and implementation commit

**Plan correction:** recorded in plan.md — the full `:core:data:connectedDebugAndroidTest` suite is re-run with `-Pandroid.testInstrumentationRunnerArguments.notClass=com.paherdin.record.core.data.search.SearchBenchmarkTest`; measured benchmarks belong to their own P4/P6 evidence chains, not the M1 regression gate.

**Device result:** PASS — 165/165, 0 failures, BUILD SUCCESSFUL in 7m26s. XML retained at `.superpowers/evidence/m1-t03-a2/TEST-23116PN5BC - 16-_core_data-.xml`, SHA-256 `870B69B7456F1900587DF59EDC9EA7B6C1243888E85188854E7BAE2A45034F43`; all five `RecordStartupDeviceTest` cases present and passing.

**Commit:** `9ac16b3` (`feat(data): expose safe startup gate handoff`), exactly 10 `core/data` files; schema no-diff; CRLF normalization applied.

**Spec review:** APPROVED — ADR 0016 surface, progress ordering, coalescing/caching/retry semantics, open() delegation and registry identity, data-free boundary all conform; both declared deviations accepted as documented (internal constructor for test injection with unchanged public surface; `LegacyGenerationBootstrap` +13 lines as the necessary minimum for v1-pending recovery events).

**Quality review:** CHANGES_REQUIRED — Important: `resolvePendingActivation` dispatch lacked `withContext(NonCancellable)`, violating ADR 0016:112; Minors: observer reentrancy KDoc, `toRepositoryResult` KDoc wording, test gaps for interleaved progress timeline, cancellation paths, and the process-wide mutex.

### M1-T03-A3 — revision-3 corrections and acceptance

**Revision-3 contract:** wrap the resolver dispatch in `withContext(NonCancellable)` matching `confirmRestore`, with `coroutineContext.ensureActive()` after the boundary so caller cancellation deterministically surfaces without writing a terminal result; add the three missing test classes of coverage; correct the two KDoc texts.

**RED:** `attemptInsideResolverRunsToCompletionDespiteCancellation` failed as expected against the pre-fix engine (AssertionError: resolver must complete before cancellation propagates); the other new tests were green regression pins.

**Host result:** `:core:data:testDebugUnitTest :core:data:apiBoundaryCheck` PASS with 312/0/0/0 (RecordStartupTest 18/18); apiBoundaryCheck 5+26; schema diff exit 0; full `check` PASS 257 tasks.

**Device result:** PASS — 165/165, 0 failures, BUILD SUCCESSFUL in 7m37s. XML retained at `.superpowers/evidence/m1-t03-a3/TEST-23116PN5BC - 16-_core_data-.xml`, SHA-256 `3D213C0FD822375EA3E54C8C53A32049D167B923AC1C3D3CEF4C1DB18C9BB67F`.

**Commit:** `af9f03f` (`fix(data): keep pending resolver non-cancellable per ADR 0016`), exactly 3 `core/data` files.

**Spec rereview:** APPROVED — boundary identical in kind to confirmRestore and matching ADR wording; cancellation/coalescing/caching semantics verified non-conflicting; red-test evidence valid; surface/schema/scope clean.

**Quality rereview:** APPROVED — Important closed, all five Minors closed, no new Critical/Important findings; one non-blocking Minor noted on the timing-based negative mutex assertion.

**Main-agent final result:** fresh full `check` BUILD SUCCESSFUL at HEAD (257 tasks, exit 0). M1-T03 revision 3 accepted; criterion 13 coordinator subgate PASS, app-side routing subgate remains T08.

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
- M1-T02 revision 6 is a bounded test-infrastructure correction recorded in plan.md: focusability evidence became the semantics `RequestFocus` action instead of device focus state, and the Xiaomi device gate used the approved direct-instrumentation fallback with pre-applied package-only app-op and explicit uninstall. Product assertions, scope, and acceptance criteria are unchanged; no spec reapproval was required.
- M1-T03: the plan device command implicitly required the P4 benchmark environment; the notClass correction (plan.md, revision 2) excludes measured benchmark runs from the regression gate without changing any acceptance criterion.
- M1-T03 documented deviations, all accepted by both review stages: `RecordStartup` constructor is `internal` instead of ADR-literal `private` (test injection; public surface machine-verified unchanged); `LegacyGenerationBootstrap.kt` gained a +13-line observer parameter as the necessary minimum to emit v1-pending recovery progress; unclassified `Throwable` in `RecordRepository.open()` now stably maps to `TransactionFailed` instead of the pre-delegation `Unexpected` (plan Step 3 wording; no external caller distinguishes them).
- The separate destructive approval for clearing `com.paherdin.record.benchmark` has not been requested or granted; it is required only at M1-T10.
- M1 remains incomplete; criteria 2–19 are pending except the initialized lifecycle record in criterion 1.

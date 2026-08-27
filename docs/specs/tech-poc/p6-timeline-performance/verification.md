# Tech PoC P6 — Timeline Performance Verification

- **Status:** `completed` — 2026-08-28. ADR 0014 and ADR 0015 are accepted; all 15 Rev5 criteria have repeatable evidence here; the unique active spec becomes `none`; MVP work may start.
- **Spec:** [spec.md](spec.md) — Revision 5 explicitly approved 2026-08-27.
- **Plan:** [plan.md](plan.md) — Revision 5 explicitly approved 2026-08-27.
- **ADR 0011:** [Macrobenchmark test-module boundary](../../../architecture/decisions/0011-macrobenchmark-test-module-boundary.md) — accepted.
- **ADR 0012:** [Native-display deadline-jank benchmark contract](../../../architecture/decisions/0012-native-display-deadline-jank-benchmark-contract.md) — accepted.
- **Failure/alternative ADR:** [ADR 0013](../../../architecture/decisions/0013-timeline-full-materialization-fails-60ms.md) — accepted 2026-08-22.
- **Jank deferral ADR:** [ADR 0014](../../../architecture/decisions/0014-native-display-deadline-jank-deferred-to-m5.md) — accepted 2026-08-27.
- **Result ADR:** ADR 0015 — drafted at Task 8 after a passing Rev5 run and accepted before completion.

## Execution pointer

Maintained by the main agent at every task handoff so a fresh agent can resume without re-deriving state. The Task log and acceptance-criteria rows below are the authoritative record; this block is a best-effort resume hint.

| Field | Value |
|---|---|
| Current task | P6 `completed` — ADR 0015 accepted 2026-08-28; no active spec remains. |
| Owner / write scope | Main agent `/root` owns dispatch and acceptance. Task 6R5-A owns only the host report parser/validator tests and this Rev5 acceptance overlay; accepted Task 6R4-A/B data, render, traversal, and device-harness files remain unchanged. |
| Remaining steps | 6R4-A/B done; 6R4-C failed jank (Rev4 evidence); 6R5-A done; Task 7 done. Task 8: populate criteria + draft ADR 0015, `clean` approval, rerun gates, ADR 0015 acceptance. |
| Blocked on | Nothing. |
| Next command | None — P6 complete. Final `git diff --check`/link/active-spec scans run and recorded. |
| Frozen env | `JAVA_HOME=C:/Users/p3148/tools/jdk/jdk-17.0.20+8`; adb `C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe`; display `peak=120`/`min=null` (read-only); `ZoneId=Asia/Shanghai`; binding selected day `2025-09-26`; historical full oracle `b5e7151d…`. Rev3 gesture controls are historical and are not a Rev4 workload input. |
| Last raw evidence | Rev4 binding frozen at `benchmark/p6-frozen/p6rev4/run-20260826T155112Z/` (1 JSON + 1 manifest + 4 message files + 35 traces + `EVIDENCE.sha256`); host validator `queryP50Us=20868 queryP95Us=39261 frameP95Us=14989 jankBp=3796 memoryMaxKib=122680`; device XML `tests=4 failures=0`. |
| Last updated | 2026-08-28 — ADR 0015 accepted; P6 `completed`. |

## Reference environment bindings

Recorded at Task 1 immediately before the first measured suite. A missing or changed binding invalidates the run until a new baseline is approved.

| Field | Value |
|---|---|
| Device | Xiaomi 14 Pro (physical, USB debugging) — serial `c55ad7e4`, model `23116PN5BC`, device `shennong` |
| Build fingerprint | `Xiaomi/shennong/shennong:16/BP2A.250605.031.A3/OS3.0.307.0.WNBCNXM:user/release-keys` |
| API level / release | Android 16 / API 36 |
| CPU | 8 cores, ARMv8-A (Snapdragon; BogoMIPS 38.40); raw `app/build/p6/device-cpuinfo.txt` |
| RAM | MemTotal 11,435,932 kB (~10.9 GiB); raw `app/build/p6/device-meminfo.txt` |
| Native user display settings | `peak_refresh_rate=120`, `min_refresh_rate=null`; this becomes the Revision 2 binding and must not be modified by the harness |
| Historical Revision 1 smoke mode | 60.00 Hz (`peak_refresh_rate=60.0`, `min_refresh_rate=60.0`; temporarily held during Task 1 smoke only, then restored); this is retained as historical evidence and is not a Revision 2 binding result |
| Projection ZoneId | `Asia/Shanghai` |

Device battery (at binding): USB powered, level 73%, status 2, temperature 36.8 °C. Thermal (at binding): battery 35–37 °C, CPU 41–43 °C, skin 40–42 °C; `IsStatusOverride=false`; raw `app/build/p6/device-battery.txt` + `app/build/p6/device-thermal.txt`. Display raw dump `app/build/p6/display-dump.txt`; `dumpsys package` dumps `app/build/p6/dumpsys-package*.txt`.

> **Task 1 note (2026-08-16):** the first `adb devices -l` check in the initial Task 1 session found no device; per the plan gate, the device-dependent steps were paused. The device (serial `c55ad7e4`, model `23116PN5BC`, API 36) was attached in the same Task 1 session and all device bindings, the smoke run, and the display-mode restore were then completed — the rows above are populated and criterion 3 is PASS.

> **Revision 2 correction (2026-08-16):** during Task 5, the device no longer converged to the Revision 1 forced-60-Hz Activity-visible mode after `settings put system peak_refresh_rate 60.0` and `min_refresh_rate 60.0`; it remained at native 120 Hz. A read-only follow-up reported `peak_refresh_rate=120`, `min_refresh_rate=null`, and `mActiveSfDisplayMode` / `mActiveRenderFrameRate` at approximately 120 Hz after restoration. No binding measurements were taken. ADR 0012 treats the forced-mode requirement as a failed measurement-method assumption and preserves the native settings as the new reference binding. Spec Revision 2 is approved; Task 5+ remain paused pending revised-plan approval. Criterion 3 must be revalidated under the revised no-write protocol; the Task 1 PASS below is historical Revision 1 evidence only.

## Task 1 build baseline (captured before any build-file change, 2026-08-16)

`:app:releaseRuntimeClasspath` full transitive closure: `app/build/p6-releaseRuntimeClasspath-baseline.txt` (retained raw evidence).

Top-level (direct) release runtime dependencies — unchanged during Task 1:

- `org.jetbrains.kotlin:kotlin-stdlib:2.2.10`
- `project :feature:finance`, `project :feature:notes`, `project :feature:timeline`, `project :core:data`, `project :core:ui`
- `androidx.compose:compose-bom:2026.06.00`
- `androidx.compose.ui:ui -> 1.11.3`, `androidx.compose.material3:material3 -> 1.4.0`

Seven production-project/edge snapshot — `./gradlew checkModuleDependencies` before Task 1 = `PASS (7 modules, graph conforms)`.

- Modules: `:app`, `:feature:finance`, `:feature:notes`, `:feature:timeline`, `:core:model`, `:core:data`, `:core:ui`.
- Edges: `:app → {finance, notes, timeline, core:data, core:ui}`; each feature → `{core:model, core:data, core:ui}`; `:core:data → {core:model}`; `:core:ui → {core:model}`; `:core:model → {}`.

## Frozen artifacts

| Artifact | Value |
|---|---|
| Fixture seed | `2026081606L` |
| Fixture version | `timeline-v1` |
| Fixture SHA-256 | `32fd36deaab5798f54533931b2ab261cd24c759790179895d37f0f553430b1b9` |
| Oracle SHA-256 | `b5e7151de0251b5d81d7726e54ff1fd534580a0dad3bf5b8bfe39ce14ec7545d` |
| Oracle flattened items | 11,000 (1,000 headers + 10,000 cards) |

Task 2 (2026-08-16) — both hashes are emitted by `:core:data:emitP6Oracle` into `core/data/build/p6/` (`fixture.sha256`, `oracle.sha256`) and were cross-validated against an independent JVM reference implementation; fresh regeneration reproduces them byte-for-byte. They are not hard-coded in the plan.

## Acceptance criteria

The detailed rows below preserve Rev1–Rev3 evidence. Their historical results do not replace the Rev4 selected-day evidence or the pending Rev5 contract. Row 5's 75.22 ms full-materialization `FAIL` and the Rev4 replacement-jank `FAIL` remain authoritative historical results and are never overwritten or relabeled. The current criterion names and required evidence are owned by [spec.md](spec.md) §5.2.

### Rev4 frozen acceptance result

This overlay records the contract and result that applied to the 2026-08-26 frozen binding run.

| # | Frozen Rev4 status | Evidence / blocker |
|---|---|---|
| 1 | **PASS — accepted 2026-08-24** | 42/42 host JVM tests (`15 + 18 + 9`), zero failures/errors; complete fixture/full-oracle hashes unchanged; deterministic empty/first/last slice manifest at `core/data/build/p6/day-oracles/manifest.tsv`; RED/GREEN logs retained. |
| 2 | **PASS — accepted 2026-08-24** | Benchmark source sets compile; the real v1 → v2 migration, 10,000-row and first/last hash preservation, indexed query plan, read-only `seedWriteCount=0`, resource closure, and exact-three-target cleanup pass. Final spec review compliant; quality review has no findings. |
| 3 | **PASS — accepted 2026-08-26** | Binding run recorded native `peak=120`/`min=null` before+after, 35 positive refresh pairs, thermal/PID/airplane-idle evidence; source scan proves zero refresh-setting writes. |
| 4 | **PASS — accepted 2026-08-26** | Typed data contract (Task 6R4-A, 9 tests) plus rendered selected-day slices (Task 6R4-B): device `TimelineRenderTraversalTest` 5/5 proves first/last/empty rendered states equal the independent oracle slices, within-day tie swap fails the shared equality assertion, and the published ready state never contains another day. No device correctness path renders the cross-day projection. |
| 5 | **PASS — accepted 2026-08-26** | 30 positive unique `TimelineQueryToRender` samples (count 1.0 each); query-to-first-correct-draw p95 39.26 ms ≤ 60 ms (p50 20.87 ms). |
| 6 | **PASS — accepted 2026-08-26** | 5 measured + 1 unmeasured traversals report the exact frozen summary `1000/999/11/0/2025-09-26/2023-01-01`; pooled frame-CPU p95 14.99 ms ≤ 16.67 ms. |
| 7 | **FAIL — frozen 2026-08-26** | Display-deadline jank 3,796 bp = 37.96 % (3,755/9,890 frames) > 2 % — genuine workload result at native 120 Hz (deadline ~8.33 ms vs selected-day render CPU p95 14.99 ms), not a harness defect. |
| 8 | **PASS — accepted 2026-08-26** | peak `memoryRssAnonKb` 122,680 KiB ≤ 196,608 KiB. |
| 9 | **PASS — accepted 2026-08-26** | No ANR/OOM/process-death/crash/relaunch/timeout; exit-info clean; airplane restored. |
| 10 | **PASS — accepted 2026-08-26** | Rev4 harness self-check 31/31 (traversal positive + controller negatives + shared gates); no benchmark error suppressed. |
| 11–15 | **PENDING** | Owned by Tasks 7/8; blocked by the criterion-7 failure. |

### Rev5 current acceptance overlay

Authoritative since the user explicitly approved the corrected Rev5 spec/plan and accepted ADR 0014 on 2026-08-27.

| # | Rev5 status | Evidence / blocker |
|---|---|---|
| 1–6 | **EVIDENCE PASS** | Rev4 selected-day evidence remains applicable because Rev5 does not change the fixture, query, correctness, traversal, or frame-CPU contracts. |
| 7 | **PASS — accepted 2026-08-27** | Task 6R5-A removed the jank budget gate; the validator recomputes and retains `jankBp=3796` as a non-gating diagnostic, and frozen-report validation prints `P6 benchmark report validation: PASS`. The Rev4 37.96 % measurement itself remains FAIL under Rev4 (frozen) and is not relabeled PASS. |
| 8–10 | **EVIDENCE PASS** | Rev4 memory, stability, and harness evidence remains applicable because Rev5 does not change those contracts. |
| 12 | **PASS — accepted 2026-08-27** | Module check (7 production + `[:benchmark]` test-tool + only target edge) + 5 negative fixtures; release manifest/deps/APK contain no `androidx.benchmark`, `TimelineBenchmarkActivity`, `profileable`, `TimelineShadowDb`, or benchmark-added `profileinstaller:1.4.1` (pre-existing production `profileinstaller:1.4.0` is not a leak). |
| 13 | **PASS — accepted 2026-08-27** | `RecordDatabase/2.json`, `databaseVersion=2`, `expected-surface.txt` byte-identical to HEAD; `apiBoundaryCheck` 4 positive + 25 negative pass. |
| 11 | **PASS — accepted 2026-08-27** | One-sided formula unit-tested; Rev5 post-clean rerun vs frozen Rev4 baseline: query (42.45−39.26)/42.45 = 7.52 % ≤ 10 %, frame (15.74−14.99)/15.74 = 4.76 % ≤ 10 % — both PASS. |
| 14 | **PASS — accepted 2026-08-27** | `clean check` + full rerun: oracle regenerated byte-identical; host 87/0; device contract 9/0 + render/self-check 37/0 + binding 4/0; new evidence frozen at `benchmark/p6-frozen/p6rev5/run-20260827T153454Z/`; `validateP6BenchmarkReports` PASS. |
| 15 | **PASS — accepted 2026-08-28** | ADR 0015 accepted by the user; records the frozen Rev4 baseline, the Rev5 rerun + one-sided 10 % regression (7.52 %/4.76 %), all refresh observations, exact traversal summaries, remaining risks, and the M5 consequence. |

| # | Criterion | Command | Environment | Result | Raw evidence | Remaining risk |
|---|---|---|---|---|---|---|
| 1 | Fixture and oracle | `export JAVA_HOME='/c/Users/p3148/tools/jdk/jdk-17.0.20+8'`; `./gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.timeline.TimelineFixtureGeneratorTest" --tests "com.paherdin.record.core.data.timeline.TimelineOracleTest" --tests "com.paherdin.record.core.data.timeline.TimelineMetricMathTest"`; `./gradlew.bat :core:data:emitP6Oracle`; `sha256sum core/data/build/p6/oracle.txt` | Host JVM only (no device): Windows 11, Gradle 9.5.0 / AGP 9.3.0 / Kotlin 2.2.10, JDK 17 (`jdk-17.0.20+8`), `ZoneId` `Asia/Shanghai` | **PASS** — 36 JVM tests PASS (15 fixture + 12 oracle + 9 metric-math, 0 failures). Fixture `timeline-v1` (seed `2026081606L`): 10,000 cards / 1,000 days / 10 per day, 6,000 txn + 4,000 notes, kinds 10/3,000/1,800/1,190, slot sets, day-edge ms, cross-type ties, canonical UUIDs, frozen spot records, LF-only length-prefixed encoding. Oracle: 11,000 items (1,000 headers + 10,000 cards), DESC time then ASC UUID-text ties, top `2025-09-26` / `8ad0ba0d-…` and bottom `5116ef58-…` sentinels, mutation detection, repeatability. `emitP6Oracle` writes `fixture.sha256`=`32fd36deaab5798f54533931b2ab261cd24c759790179895d37f0f553430b1b9`, `oracle.sha256`=`b5e7151de0251b5d81d7726e54ff1fd534580a0dad3bf5b8bfe39ce14ec7545d`; `sha256sum oracle.txt` equals `oracle.sha256`. Both hashes were independently re-derived by a separate Java implementation and match exactly. | `core/data/build/p6/{fixture.sha256,oracle.sha256,oracle.txt}`; JUnit XML `core/data/build/test-results/testDebugUnitTest/TEST-com.paherdin.record.core.data.timeline.{TimelineFixtureGeneratorTest,TimelineOracleTest,TimelineMetricMathTest}.xml` (failures=0). | Oracle/fixture have been cross-validated only on the JVM so far; the device-side shadow projection must reproduce the same 11,000-item oracle hash in-process (Task 3 contract, Task 4 render traversal). The top/bottom sentinels are re-asserted device-side. |
| 2 | Shadow database | `export JAVA_HOME='/c/Users/p3148/tools/jdk/jdk-17.0.20+8'`; `./gradlew.bat :core:data:compileBenchmarkKotlin :app:assembleBenchmark :benchmark:assembleBenchmark`; `p6OracleHash=$(cat core/data/build/p6/oracle.sha256)`; `./gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.TimelineProjectionContractTest" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedOracleHash=$p6OracleHash"` | Xiaomi 14 Pro (serial `c55ad7e4`, model `23116PN5BC`), Android 16 / API 36, Windows 11, JDK 17 (`jdk-17.0.20+8`), Gradle 9.5.0 / AGP 9.3.0 / Kotlin 2.2.10 / KSP2 2.3.9 / Room 2.8.4, `ZoneId` `Asia/Shanghai` | **PASS** — all three benchmark variants BUILD SUCCESSFUL; Room KSP processed `:core:data`'s `benchmark` source set (generated `TimelineShadowDb_Impl`) with no `core/data/build.gradle.kts` wiring change. Out-of-process contract test 1/1 PASS (`TimelineProjectionContractTest.seededShadowDatabaseMatchesHostOracle`): shadow file DB row count = **10,000**, typed flattened count = **11,000** (1,000 headers + 10,000 cards), actual typed SHA-256 = `b5e7151de0251b5d81d7726e54ff1fd534580a0dad3bf5b8bfe39ce14ec7545d` = host `oracle.sha256`. Manual `uiautomator dump` of the seed Activity showed the identical sentinels. `:core:data` benchmark source set never imports `testFixtures`; production `main`/`release` zero changes; no new Room schema exported (`core/data/schemas/` byte-unchanged: `1.json`+`2.json` only). | Test XML `benchmark/build/outputs/androidTest-results/connected/benchmark/TEST-23116PN5BC - 16-_benchmark-.xml` (`tests=1`, `failures=0`); manual `uiautomator dump` at `/data/local/tmp/p6.xml` (`P6:rowCount=10000`, `P6:flattenedCount=11000`, `P6:actualHash=b5e7151d…`); `core/data/build/p6/oracle.sha256` = `b5e7151de0251b5d81d7726e54ff1fd534580a0dad3bf5b8bfe39ce14ec7545d`; new files `core/data/src/benchmark/kotlin/com/paherdin/record/core/data/timeline/{TimelineCard,TimelineShadowDb,TimelineTypedReader}.kt`, `app/src/benchmark/kotlin/com/paherdin/record/benchmark/TimelineBenchmarkActivity.kt`, `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineProjectionContractTest.kt`; activity declared in `app/src/benchmark/AndroidManifest.xml` | Sentinel handoff uses target-context SharedPreferences (not accessibility): the instrumentation's UiAutomation cannot see the target's windows on this device/firmware, so Task 4's out-of-process render traversal (Compose tags / UiAutomator) is not yet proven on this hardware. Lifecycle-alignment dependency added to `:benchmark` (`androidx.lifecycle:lifecycle-runtime:2.9.4`, Task 3 deviation) — Task 7 must re-verify release isolation. |
| 3 | Reference binding | Revision 1 historical: `adb devices -l`; device facts; `settings get/put system peak_refresh_rate` / `min_refresh_rate`; `BenchmarkWiringSmokeTest`. Revision 2 after plan approval: read-only before/after native-setting verification plus per-iteration Activity-visible refresh capture. | Xiaomi 14 Pro (serial `c55ad7e4`, model `23116PN5BC`), physical USB, Android 16 / API 36, fingerprint `Xiaomi/shennong/shennong:16/BP2A.250605.031.A3/OS3.0.307.0.WNBCNXM:user/release-keys`, 8-core ARMv8-A, MemTotal 11,435,932 kB, native `peak=120`, `min=null` | **PASS (Revision 2, revalidated 2026-08-18 at Task 5)** — the no-write protocol is proven: source scan of `benchmark/src/main` + `app/src/benchmark` finds zero `settings put/delete system peak_refresh_rate|min_refresh_rate` writes; `settings get system peak_refresh_rate` = `120` and `min_refresh_rate` = `null` before AND after the full Task 5 gate device run; `TimelineDisplayBinding.capture` executes only the two settings-get reads; the full manual scroll leaves the settings untouched (`nativeSettingsRemain120NullAcrossFullScroll` PASS). Historical Revision 1 forced-60 smoke remains historical evidence only (see Revision 2 correction note above). | Historical Task 1: `app/build/p6/device-{fingerprint,cpuinfo,meminfo,battery,thermal,display-dump,dumpsys-package*}.txt` and smoke XML. Revision 2: Task 5 gate device XML (`tests=28`, `failures=0`), logcat `P6SelfCheck`, settings dumps before/after. | Observed Activity-visible refresh may vary between two positive finite observations and is recorded per iteration (never equality-gated); Task 6 records all 35 pairs. |
| 4 | Complete correctness | `export JAVA_HOME='/c/Users/p3148/tools/jdk/jdk-17.0.20+8'`; `./gradlew.bat :app:assembleBenchmark :benchmark:assembleBenchmark`; `p6OracleHash=$(cat core/data/build/p6/oracle.sha256)`; `./gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.TimelineRenderTraversalTest" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedOracleHash=$p6OracleHash"` | Xiaomi 14 Pro (serial `c55ad7e4`, model `23116PN5BC`), Android 16 / API 36, Windows 11, JDK 17 (`jdk-17.0.20+8`), Gradle 9.5.0 / AGP 9.3.0 / Kotlin 2.2.10, Compose BOM 2026.06.00 (ui 1.11.3), Room 2.8.4, `ZoneId` `Asia/Shanghai`; correctness run was not a forced-60 Hz measurement (device reported `TimelineRefreshHz=60.0` at capture) | **PASS** — `TimelineRenderTraversalTest` 2/2 on three consecutive runs (0 failures): (a) `renderedExhaustiveTraversalMatchesHostOracle`: in-process recorder sequentially `scrollToItem(0..10_999)`, captured **11,000** actually-composed rendered payloads, rendered SHA-256 `b5e7151de0251b5d81d7726e54ff1fd534580a0dad3bf5b8bfe39ce14ec7545d` == host `oracle.sha256`; `traceOk=true` (query→first-correct-draw gate started before the single DAO query, ended when the first card key was laid out); top sentinel `8ad0ba0d-…` and bottom sentinel `5116ef58-…` match the frozen IDs. (b) `wrongOrderRenderFailsSharedEqualityAssertion`: same traversal over a list with the two top cards exchanged also captured **11,000** payloads with hash `bae17dcd8ad105a6549da0fcbddf51ab374bb9543746b939094faa129cb0b7b3` ≠ oracle, and the **shared equality assertion threw `AssertionError`** (caught and asserted). No timed metric is correctness evidence — the verdict is the rendered count + hash; `traceMs` is diagnostic (manual runs 49.70 / 66.18 ms, informational only). | Test XML `benchmark/build/outputs/androidTest-results/connected/benchmark/TEST-23116PN5BC - 16-_benchmark-.xml` (`tests=2`, `failures=0`; testcase times ~12–14 s each); manual `adb uiautomator dump` after fresh `am start` launches: correctness `P6:count=11000 hash=b5e7151d… traceMs=49.70 refreshHz=60.0`, wrong-order `P6:count=11000 hash=bae17dcd… traceMs=66.18 refreshHz=60.0`; new files `app/src/benchmark/kotlin/com/paherdin/record/benchmark/{TimelineRenderedPayloadRecorder.kt,TimelineRenderSurface.kt}` (rewritten `TimelineBenchmarkActivity.kt` with `render-correctness`/`render-wrong-order` modes), `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineRenderTraversalTest.kt`, `loadForRender` added to `core/data/src/benchmark/.../TimelineShadowDb.kt` | Rendered payloads are collected in-process by the recorder (the instrumentation's UiAutomation cannot see the target windows on this firmware), so each "semantics payload" is the composed item's canonical re-encoding registered during composition, not an accessibility-tree read — the render hash is still an independent render-layer path, never the typed-list hash. A second sequential `am start` to the already-resumed Activity was observed once as a launch no-op in manual adb sessions; the tests are hardened with a mode-stamp + fresh-run wait, and Macrobenchmark (Task 6) force-stops the process per iteration, so measured runs are unaffected. Task 6 must still wrap the same query→first-correct-draw boundary in `TraceSectionMetric` and verify 60 Hz per iteration. `:benchmark` gained two AndroidX version-alignments (`androidx.collection:1.5.0`, `androidx.core:1.18.0`) to stop the test APK shadowing the app's newer classes in the shared process — Task 7 must re-verify release isolation. |
| 5 | Load protocol and budget (p95 ≤ 60 ms) | `export JAVA_HOME='/c/Users/p3148/tools/jdk/jdk-17.0.20+8'`; pre-flight `adb shell settings get system peak_refresh_rate`==`120` and `min_refresh_rate`==`null` + `adb shell dumpsys activity exit-info com.paherdin.record`; `p6OracleHash=$(cat core/data/build/p6/oracle.sha256)`; `./gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.TimelineBenchmark" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedOracleHash=$p6OracleHash"`; post-run settings + exit-info diff; `./gradlew.bat "-Dp6ReportDir=<connected_android_test_additional_output>" :core:data:validateP6BenchmarkReports` | Xiaomi 14 Pro (serial `c55ad7e4`, model `23116PN5BC`), Android 16 / API 36, Windows 11, JDK 17 (`jdk-17.0.20+8`), Gradle 9.5.0 / AGP 9.3.0 / Kotlin 2.2.10, macrobenchmark-junit4 1.4.1, native `peak=120`/`min=null` before and after; airplane mode enabled during suite and restored to `0` after | **FAIL (final clean rerun 2026-08-22, evidence frozen)** — after fixing every known measurement-contract defect (handle.close moved out of the span after gate.end(); gate.end fired directly inside the first correct drawBehind callback on the same main thread; finer `P6:RoomQuery`/`P6:ProjectionGrouping` markers device-verified), the full frozen suite ran 4/4 (BUILD SUCCESSFUL 37m2s) and the host validator recomputed query **p50 59.78 ms / p95 75.22 ms — still over the 60 ms budget**. Marker attribution over all 30 load samples proves the query + materialization phase alone is `P6:RoomQuery` median 20.44 ms / p95 27.25 ms + `P6:ProjectionGrouping` median 8.16 ms / p95 13.94 ms (sum median 30.44 ms), with vsync alignment + first-frame composition/layout/draw accounting for the remainder. The prior 80.2 ms (2026-08-21) and 78.78 ms runs are retained as historical evidence; 75.22 ms is the authoritative frozen result. This is a **genuine technical-assumption failure**, recorded by ADR 0013 (full-materialization load fails the 60 ms budget) with the day-bounded selected-day alternative. | Raw frozen run (sha256-provenanced): `benchmark/p6-frozen/p6final/run-2026-08-22/{com.paherdin.record.benchmark.test-benchmarkData.json,p6-run-manifest.json,additionaltestoutput.benchmark.message_*.txt (4),TimelineBenchmark_*.perfetto-trace (35),TRACES.sha256,JSON.sha256}`; host validator printout `derived: queryP50Us=59779 queryP95Us=75218 frameP95Us=6389 jankBp=11 memoryMaxKib=104684 gestureCounts=[938,937,936,943,937]`; device XML `tests=4 failures=0`; marker attribution `benchmark/p6-frozen/p6final/attr_p6markers.py` | RESOLVED by ADR 0013: full materialization fails the frozen 60 ms p95 budget on the reference device. Criterion 5 is FAIL for the full-materialization shape; P6 Rev4 replaces §4.3 with a selected-day bounded query (Journey 8 selected-day timeline) and a new comparable baseline. |
| 6 | Scroll protocol and frame budget (p95 ≤ 16.67 ms) | Same Task 6 Step 7/8 command block as criterion 5 | same | **PASS (clean rerun 2026-08-22)** — five measured full-scroll iterations (scrollA 3 + scrollB 2) from the identical top state all reached the exact bottom sentinel `5116ef58-…` with positive sub-cap gesture counts `[937, 939, 937, 942, 936]` (min 936, max 942, range 6; Revision 3 — equality not required). Non-gating control scroll reached bottom at gestureCount=952 (diagnostic). Host validator pooled the real `sampledMetrics.frameDurationCpuMs.runs[iter][frame]` (~20k frames/iteration): **pooled frame p95 = 6.27 ms ≤ 16.67 ms**. Previous run (2026-08-21) counts `[939, 940, 940, 995, 991]` retained as historical evidence. | Raw clean run: the 5 scroll `.perfetto-trace` (`TimelineBenchmark_binding02ScrollA_iter000..002`, `binding03ScrollB_iter000..001`), scroll blocks in `com.paherdin.record.benchmark.test-benchmarkData.json`, message txt files; test XML `tests=4 failures=0`; clean host validator `frameP95Us=6266`. | Fling physics vary run-to-run (gesture counts are diagnostics, Revision 3). Frame p95 comfortably under budget; no remaining risk. |
| 7 | Jank budget (≤ 2%) | Same command block as criterion 5 | same | **PASS (clean rerun 2026-08-22)** — host validator pooled `sampledMetrics.frameOverrunMs.runs[iter][frame]` (~100k frames across 5 scrolls); **jank = 10 bp = 0.10 %** (positive-overrun fraction over every frame; negative overruns are on-time frames and stay in the denominator) ≤ 2 %. | Raw clean run: scroll blocks in `com.paherdin.record.benchmark.test-benchmarkData.json`; device XML `tests=4 failures=0`; clean host validator `jankBp=10`. | None. |
| 8 | Memory budget (≤ 196,608 KiB) | Same command block as criterion 5 | same | **PASS (clean rerun 2026-08-22)** — host validator took the max `memoryRssAnonMaxKb` across all 35 measured iterations: **peak 112,184 KiB (≈109.6 MiB) ≤ 196,608 KiB (192 MiB)**. Load blocks ~75–77 MB, scroll blocks ~109–112 MB. | Raw clean run: `memoryRssAnonMaxKb` single metrics in `com.paherdin.record.benchmark.test-benchmarkData.json` (35 samples); clean host validator `memoryMaxKib=112184`. | Well under budget. |
| 9 | Stability (no ANR/OOM/crash/process death) | Same command block as criterion 5 | same | **PASS (clean rerun 2026-08-22)** — 4/4 device tests PASS with zero failures/errors; package exit-info diff across the suite clean (only `USER REQUESTED` reasons from the harness's own force-stops — exit-info reasons after the run `{USER REQUESTED=16}`); no ANR/OOM/crash/relaunch/timeout; `p6-run-manifest.json` finalized with all 35 records and identical PIDs per iteration (e.g. loadA pid 23461→23461, loadB 23982→23982); airplane mode restored to `0`; settings `peak=120`/`min=null` re-asserted after the run. | Raw clean run: `benchmark/build/outputs/androidTest-results/connected/benchmark/TEST-23116PN5BC - 16-_benchmark-.xml` (`tests=4 failures=0`); logcat `display settings after peak=120 min=null`, `package exit-info clean`, `manifest finalized ... /p6-run-manifest.json`; `p6-run-manifest.json` (35 records, `displaySettingsAfter` 120/null); post-run `adb shell dumpsys activity exit-info`. | None during the run; the physical device was plugged in throughout this clean rerun. |
| 10 | Harness self-check | `export JAVA_HOME='/c/Users/p3148/tools/jdk/jdk-17.0.20+8'`; `./gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.timeline.TimelineBenchmarkReportValidatorTest" --tests "com.paherdin.record.core.data.timeline.TimelineMetricMathTest"`; `p6OracleHash=$(cat core/data/build/p6/oracle.sha256)`; `./gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.BenchmarkWiringSmokeTest,com.paherdin.record.benchmark.TimelineHarnessSelfCheckTest" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedOracleHash=$p6OracleHash"` | Xiaomi 14 Pro (serial `c55ad7e4`, model `23116PN5BC`), Android 16 / API 36, Windows 11, JDK 17 (`jdk-17.0.20+8`), Gradle 9.5.0 / AGP 9.3.0 / Kotlin 2.2.10; native `peak=120`, `min=null` before and after | **PASS (Task 5 Revision 2 gate, 2026-08-18)** — source scan empty; host validator 31/31 and metric-math 9/9 PASS (exact native-settings strings `120`/`null`, exactly 35 ordered iteration records split 15/3/2/15, positive finite `refreshBeforeHz`/`refreshAfterHz`, thermal <= LIGHT, identical nonblank PIDs, 5 equal positive gesture counts matching the frozen value, null load gesture counts, per-report `profilerOutputs` == iteration count, 35 distinct referenced filenames all resolving to retained traces; a refresh change between two positive observations does not fail); device gate 28/28 PASS — `BenchmarkWiringSmokeTest` (package diff / launch / non-debuggable / profileable-from-shell / positive finite display refresh, no Hz equality) + 27 `TimelineHarnessSelfCheckTest` controls (read-only display binding: native `120`/`null` passes, peak `60` / missing peak / non-null min fail, `60.0`/`90.0`/`120.0` refresh observations pass, blank / `0` / negative / `NaN` / `Infinity` fail; scroll-ready distinct `refreshBeforeHz`/`refreshAfterHz` sentinels present and positive finite; native settings captured read-only before/after the full manual scroll remain `120`/`null`; thermal, PID continuity, exit-info, trace gate, full-query, count/hash, top/bottom, 1200-gesture cap, no-explicit-gap, five warmups, and all shared-gate negatives PASS). Frozen manual gesture count pinned: **1045** (gate run 2026-08-18 17:44:28, elapsedMs 194,127). | JUnit XML `core/data/build/test-results/testDebugUnitTest/TEST-com.paherdin.record.core.data.timeline.{TimelineBenchmarkReportValidatorTest,TimelineMetricMathTest}.xml` (31/0 and 9/0); device XML `benchmark/build/outputs/androidTest-results/connected/benchmark/TEST-23116PN5BC - 16-_benchmark-.xml` (`tests=28`, `failures=0`); logcat `P6SelfCheck` `gestureCount=1045`; settings dumps before/after | Superseded by Revision 3 (2026-08-18): the pin-and-match protocol failed in Task 6 (pin/measured 953/936, 950/938, 1004/993 — capture overhead shortens the fling); gesture counts are now positive sub-cap diagnostics with min/max/range reporting, and count equality is not a gate. The Task 5 count 1045 is historical diagnostic evidence only. |
| 11 | Regression contract | One-sided formula unit tests (`TimelineMetricMathTest`); Rev5 post-clean rerun vs frozen Rev4 baseline comparison (main-agent computed) | Xiaomi 14 Pro (serial `c55ad7e4`), Android 16 / API 36, native `peak=120`/`min=null` unchanged; JDK 17 (`jdk-17.0.20+8`) | **PASS — accepted 2026-08-28** — query p95: new 42.45 ms > baseline 39.26 ms, (42.45−39.26)/42.45 = 7.52 % ≤ 10 % PASS; frame p95: new 15.74 ms > baseline 14.99 ms, (15.74−14.99)/15.74 = 4.76 % ≤ 10 % PASS. Memory uses its absolute cap (PASS); jank is diagnostic under ADR 0014, not a regression metric. | Rev4 baseline frozen at `benchmark/p6-frozen/p6rev4/run-20260826T155112Z/`; Rev5 rerun frozen at `benchmark/p6-frozen/p6rev5/run-20260827T153454Z/`; host validator derived `queryP50Us=22068 queryP95Us=42454 frameP95Us=15738 jankBp=3326 memoryMaxKib=118196` | Run-to-run spread is real (7.52 %/4.76 %); a comparable M5/later run must keep the same device/system-build/display-settings/fixture/workload bindings or require an explicitly approved new baseline. |
| 12 | Architecture and release isolation | `./gradlew.bat checkModuleDependencies` (7 production + test-tool allowlist `{":benchmark"}` + only `com.android.test` target edge + refresh-write source scan); 5 in-task negative fixtures (8th module, 2nd test-tool, prod→`:benchmark`, `:benchmark→:core:data`, unapproved benchmark config) each proven RED then reverted; `:app:assembleRelease` + release dep graph/source/manifest (`aapt2`)/DEX (`dexdump`) scans | Xiaomi 14 Pro; JDK 17; build-tools 36.0.0; `git status` re-verified pre/post fixtures | **PASS — accepted 2026-08-27** — `checkModuleDependencies: PASS (7 production modules, test-tool = [:benchmark], refresh-write scan clean)`; release contains no `androidx.benchmark`, no `TimelineBenchmarkActivity`, no `profileable`, no `TimelineShadowDb`, no benchmark runner. Deviation recorded: `androidx.profileinstaller:profileinstaller:1.4.0` in release is a pre-existing production transitive (lifecycle-runtime-android/Compose BOM), not a P6 leak; the benchmark-added `profileinstaller:1.4.1` is absent. | `build/p6-gate-output.log`, `build/p6-release-apk-inspection/*.summary`, fixture RED outputs, `git diff --check` clean | None. |
| 13 | Schema and API isolation | `:core:data:apiBoundaryCheck` (4 positive + 25 negative fixtures); snapshot compare of `RecordDatabase/2.json`, `RecordDatabase.kt` `databaseVersion`, `api/expected-surface.txt` vs HEAD | Host, JDK 17 | **PASS — accepted 2026-08-27** — `apiBoundaryCheck: PASS`; snapshots byte-identical to HEAD (`git diff --exit-code`); `databaseVersion = 2`; no `expected-surface.txt` update. | Gradle output; `git diff HEAD` empty for the three snapshots | None. |
| 14 | Regression (`clean check`, rerun, launch) | Frozen evidence migrated to `benchmark/p6-frozen/` (checksum-verified) then `./gradlew.bat clean check`; `emitP6Oracle` regenerated byte-identical (fixture `32fd36de…`, oracle `b5e7151d…`, slices `7432b279…`/`7c055c9a…`/`e3b0c442…`); full rerun: host JVM 87/0, device contract 9/0 + render/self-check 37/0 + binding 4/0 (BUILD SUCCESSFUL 10m42s); freeze to `benchmark/p6-frozen/p6rev5/run-20260827T153454Z/` (42 files, checksum-verified); `validateP6BenchmarkReports` PASS | Xiaomi 14 Pro (serial `c55ad7e4`), Android 16 / API 36, native `peak=120`/`min=null` before+after; JDK 17 | **PASS — accepted 2026-08-28** — all absolute gates + one-sided 10% regression PASS on the fresh post-clean run; Rev4 frozen baseline remains authoritative; Rev5 rerun is the independent reproducibility evidence. | `benchmark/p6-frozen/p6rev5/run-20260827T153454Z/` (1 JSON + 1 manifest + 4 message files + 35 traces + `EVIDENCE.sha256`); device XML `tests=4 failures=0`; host JUnit XMLs; validator printout | None; frozen evidence now lives under `benchmark/p6-frozen/` (gitignored, local-only) and survives future `clean`s. |
| 15 | ADR 0014 jank deferral + ADR 0015 selected-day result | *(ADR 0014 accepted 2026-08-27 before Rev5 execution; ADR 0015 accepted 2026-08-28; ADR 0013 remains accepted failure/alternative evidence)* | — | **PASS — accepted 2026-08-28** — the user explicitly accepted ADR 0015; it records the frozen Rev4 baseline, the Rev5 rerun + one-sided 10 % regression (7.52 %/4.76 %), all refresh observations, exact traversal summaries, remaining risks, and the M5 consequence. P6 is `completed`. | ADR 0014 (Accepted), ADR 0015 (Accepted) | None. |

## Task 6 blocker note (2026-08-18, evidence-bearing failure per spec §4.9)

The Task 6 binding suite aborted three times with the same signature: the first
measured scroll iteration's gesture count is systematically **11–17 lower** than
the manual pin scroll's count (pin/measured: 953/936, 950/938, 1004/993). The
measured iterations run under Macrobenchmark's Perfetto capture; the pin scroll
does not. The capture's per-frame overhead lengthens the Compose fling overlap,
so a captured full scroll needs ~1.3–1.8% fewer gestures. The exact-match
protocol ("pin at bring-up from the one manual unmeasured scroll; all 5
measured iterations must match it exactly") is therefore **not satisfiable on
this device** — this disproves the plan's ±2-jitter assumption (Task 5 observed
1043–1045 on the pre-self-instrumenting harness; the current harness also
varies 950–1004 run-to-run). The suite's abort-on-invalid semantics worked
exactly as designed; nothing was dropped, replaced, or hidden. Plan revision +
explicit reapproval are required before retry (spec §4.9).

### Revision 3 resolution (2026-08-18)

The user classified this as a harness/measurement-protocol assumption failure —
not a PoC technical conclusion — requiring **no ADR**. The user rejected option
A (pin from the first measured iteration: data-dependent gating, and
measured-measured variance is unproven) and option C (traced pin: must itself
enter Perfetto measurement, becoming a sixth sample or an A variant), and
approved **D′** with these rules, recorded verbatim:

- The unmeasured full scroll remains a non-gating control; it must reach bottom.
- All 5 measured iterations start from the identical top state and must reach
  the exact bottom sentinel.
- Coordinates, direction, 10 steps, immediate-repeat, no explicit gaps, and the
  1200 cap are unchanged.
- Every gestureCount must be positive and below the cap and is recorded as-is;
  different counts no longer invalidate an iteration.
- PID, thermal, display settings, process stability, and no-retry/no-drop remain
  strictly gated.
- The host validator verifies 5 valid counts and reports min/max/range; no
  percentage tolerance gate is invented from the three failures.

Spec §4.5, §5.2 criterion 6, and the plan's frozen scroll protocol and Task 6
steps are revised under Revision 3; this supersedes Task 5 Step 2's "five equal
gesture counts" host-validator requirement.

Raw evidence: suite runs `benchmark/build/p6-task6-suite{2,3,4}.log` and
`p6-scroll-diagnostic.log`; device XMLs under
`benchmark/build/outputs/androidTest-results/connected/benchmark/`;
`benchmark/build/p6-task6-evidence-logcat.txt`; copied
`connected_android_test_additional_output/benchmark/connected/23116PN5BC - 16/`.

Task 6 deliverables completed before the blocker (all verified):
`TimelineRunManifest` + 7 device tests (ordered append, duplicate rejection,
non-finite refresh rejection, PID change rejection, gesture nullability,
atomic replacement, exact 35-record shape) — 7/7 PASS; suite lifecycle
controls; four name-ordered binding blocks with the frozen metrics;
`load-only` Activity mode (load-path refresh sentinels without the 11k
traversal); `validateP6BenchmarkReports` host task; host validator extended to
report derived values (queryP50Us added); self-instrumenting repair (Task 1
gap: `benchmark/build.gradle.kts` + cross-process sentinel bridge
`TimelineSentinelBridge` + app-side sentinel-file mirror + runId freshness
protocol) with the full Task 5 gate re-run green 28/28 under the new config.

**Revision 3 resolution (2026-08-18):** the user approved D′ — the exact-count
gate is removed; gesture counts are positive sub-cap diagnostics; the manual
full scroll is a non-gating bottom-reaching control; the host validator
reports the five counts with min/max/range and no tolerance gate. Implemented
and host-verified: `TimelineBenchmark` no longer pins/compares counts
(control scroll count logged diagnostically; measured iterations require
positive sub-cap counts only), and the validator TDD cycle is green 33/33
(equality negatives replaced by over-cap/non-positive negatives;
`validate(reportRoot)`; `gestureCounts` reported). The Step 7 suite rerun is
pending: the physical device (Xiaomi 14 Pro `c55ad7e4`) disconnected during
the Revision 3 handoff (`adb devices` empty; Windows USB enumeration shows no
Xiaomi device) and the plan gate requires exactly one authorized device —
the suite will run once the device is reconnected.

### D′ retry run 2026-08-21 (Step 7 aborted; new blocking defect found)

The first D′ Step 7 rerun started 2026-08-21 14:37:31 (device `c55ad7e4`
reconnected) with pre-flight green (`peak=120`, `min=null`, empty
`exit-info`), `p6ExpectedOracleHash` = `b5e7151d…` (= `oracle.sha256`). The
two benchmark APKs were pre-installed via `adb install -r` to avoid the
MIUI install-confirmation dialog that aborted the previous attempt
(suite5: `INSTALL_FAILED_USER_RESTRICTED`). Result: **aborted in
`binding02ScrollA`** after ~16.5 min, before any criterion can be marked PASS.
Nothing was dropped, replaced, or hidden.

What the run proved before the abort:

- `binding01LoadA` PASSED 15/15: logcat `P6Benchmark: binding01LoadA#0..#14 load ok refreshBefore=120.0 refreshAfter=120.0 thermal=0/0`;
  15 Perfetto traces copied to
  `benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/23116PN5BC - 16/TimelineBenchmark_binding01LoadA_iter*.perfetto-trace`.
- The one manual full scroll (non-gating control, Revision 3) reached the
  exact bottom sentinel: `gestureCount=952`, `elapsedMs=189,954`
  (diagnostic only, never an expectation).
- The `binding02ScrollA` test then aborted because the **USB device was
  physically disconnected** (the user unplugged it to use the device). Host
  Gradle output: `Failed to retrieve additional test outputs from device:
  device 'c55ad7e4' not found`; XML `binding02ScrollA FAILED` (empty message,
  test time 598 s); during the resulting device-side `@AfterClass`,
  `settings get system peak_refresh_rate` returned `''` (transport down), so
  the display after-assert failed, the airplane-mode restore did not run, and
  the run manifest was NOT finalized (`p6-run-manifest.json` absent). After
  `adb kill-server/start-server` the device returned (`c55ad7e4`); the
  operator restored `airplane_mode_on` 1→0 and re-confirmed `peak=120`,
  `min=null`. No `benchmark_report.json` was copied for any block.
- Logs/evidence: `benchmark/build/p6-task6-suite6.log`,
  `benchmark/build/p6-task6-suite6-logcat.txt` (empty — ring buffer overwritten),
  per-test logcat `benchmark/build/outputs/androidTest-results/connected/benchmark/23116PN5BC - 16/logcat-com.paherdin.record.benchmark.TimelineBenchmark-binding{01LoadA,02ScrollA}.txt`,
  device XML `TEST-23116PN5BC - 16-_benchmark-.xml` (`tests=2`, `failures=1`).

**NEW blocking defect (host-validation-fatal, discovered on the completed
load block):** Macrobenchmark's `TraceSectionMetric("TimelineQueryToRender")`
produced **count 0 / sum 0 ms for every one of the 15 completed
`binding01LoadA` iterations** (see the copied `additionaltestoutput.benchmark.message_…binding01LoadA.txt`
summary). The app-side `TimelineQueryToRenderGate`
(`app/src/benchmark/kotlin/com/paherdin/record/benchmark/TimelineRenderSurface.kt`)
calls `android.os.Trace.beginSection(SECTION_NAME)` on the query-dispatcher
thread (`gate.start()` inside `withContext(Dispatchers.IO)` in the seeder's
`onBeforeQuery`) but calls `Trace.endSection()` on the main thread
(`gate.end()` after the first-correct-draw wait in the `LaunchedEffect`).
`android.os.Trace.beginSection/endSection` must be matched on the SAME
thread; the cross-thread pairing leaves the slice open on the dispatcher
thread, so `TraceSectionMetric` finds no completed slice (Perfetto trace for
`binding01LoadA_iter000` confirms `B|8566|TimelineQueryToRender` with no
same-thread close). The host validator would therefore fail even on a
completed suite ("missing TimelineQueryToRender samples" / "non-positive
query samples"), and the query-p95 gate (≤ 60 ms) cannot be produced from
the metric as wired. The gate's own `traceMs`/`ok` diagnostics
(`System.nanoTime`-based) are unaffected and were green — the defect is in
the OS-trace pairing only. This requires a harness fix (Task 6 Step 4 scope:
same-thread begin/end, e.g. begin on the main thread immediately before
dispatching the query) and a full 45–90 min suite rerun before any criterion
5–9 row can be populated. No criterion is marked PASS.

### D′ trace-gate fix and full suite rerun 2026-08-21 (fixed, then failed on query budget)

The trace-gate defect above was fixed in `TimelineRenderSurface.kt`:
`TimelineQueryToRenderGate.start()` now runs on the MAIN thread immediately
before dispatching the single full query (inside the `LaunchedEffect`,
interleaved with the query dispatch), and `gate.end()` closes the slice on
the MAIN thread at the first correct draw — same-thread `beginSection`/
`endSection` pairing exactly as `android.os.Trace`/`TraceSectionMetric`
requires. A cheap single-method verify run (`binding01LoadA` only,
`benchmark/build/p6-task6-verify-trace.log`, 2026-08-21 20:33, BUILD
SUCCESSFUL) confirmed the fix: 15/15 load iterations produced
`TimelineQueryToRenderCount = 1.0` and positive `TimelineQueryToRenderSumMs`
(55.6–93.8 ms) with 15 closed one-slice Perfetto traces.

The real AGP 9.3 / macrobenchmark 1.4.1 report shape was then captured on a
cheap `binding01LoadA+binding02ScrollA` method-filtered run
(`benchmark/build/p6-task6-scroll-capture.log`, 2026-08-21, BUILD SUCCESSFUL,
26m): the device publishes ONE `<package>-benchmarkData.json`
(`com.paherdin.record.benchmark.test-benchmarkData.json`) holding a
`benchmarks[]` array (one entry per block, bare method names, `warmupIterations=0`)
with `TraceSectionMetric` reported as `TimelineQueryToRenderCount` (always
1.0) + `TimelineQueryToRenderSumMs` (durations) and `FrameTimingMetric`
reported as nested `sampledMetrics.frameDurationCpuMs`/`frameOverrunMs`
(`runs[iter][frame]`, overruns negative for on-time frames). The host
validator's report contract was therefore corrected (task-classification:
harness contract fix to the REAL output shape; no ADR / no spec semantic
change): it now reads the single `-benchmarkData.json`, matches blocks by the
bare name, recomputes p50/p95 from the 30 pooled `TimelineQueryToRenderSumMs`
runs, requires every `TimelineQueryToRenderCount` run == 1.0, drops the
`warmupIterations==5` gate (manual warmups are not Macrobenchmark samples),
and flattens `frameDurationCpuMs`/`frameOverrunMs` runs then recomputes
frame p95 / jank. Host unit tests green: validator 38/38 + metric-math 9/9.

**Full D′ Step 7 rerun (2026-08-21, `benchmark/build/p6-task6-suite7.log`):
COMPLETED — BUILD SUCCESSFUL in 36m22s, device XML `tests=4 failures=0`
(binding01LoadA, binding02ScrollA, binding03ScrollB, binding04LoadB).** The
whole 10,000-card loaded timeline measured at the frozen protocol, then
Step 8 host validation (with the corrected real-shape contract) recomputed
all four gates from raw samples:

| gate | raw-derived value | budget | result |
|---|---|---|---|
| query-to-first-correct-draw p95 | 80.2 ms (p50 63.9 ms) | ≤ 60 ms | **FAIL** |
| pooled frame-CPU p95 | 6.3 ms | ≤ 16.67 ms | PASS |
| display-deadline jank | 2 bp (0.02 %) | ≤ 2 % | PASS |
| peak RssAnon | 126,148 KiB (~123 MiB) | ≤ 196,608 KiB | PASS |

Five measured scroll gesture counts (Revision 3 diagnostics): **939, 940,
940, 995, 991** — min **939**, max **995**, range **56**; the non-gating
control scroll reached the bottom sentinel at **948** gestures (diagnostic).
All 35 refresh pairs were `120 → 120` (file
`benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/23116PN5BC - 16/p6-run-manifest.json`);
native settings `peak=120`/`min=null` asserted before and after; airplane
mode restored to `0`; package exit-info diff clean. The validator's only
problem is the frozen query-p95 budget being exceeded — a genuine, repeated
technical result on this device (the D′ verify and full-suite runs both
measured ~49–94 ms per cold load). Criterion 5 is therefore a real FAIL
pending a user decision (M5 comparable consequence / absolute-cap no
allowance), and criteria 6–9 are recorded PASS. No criterion was marked PASS
that did not pass; the failure is reported exactly as measured.

### User correction (2026-08-21): the 80.2 ms query p95 is a measurement-contract defect, NOT a technical conclusion

The user reviewed the 80.2 ms result and classified it as **timing contamination
by harness sentinel I/O**, not a full-materialization failure. The budget stays
60 ms and the full 10,000-card workload stays unchanged; no ADR, spec/plan
revision, or reapproval. The measured span was polluted in four ways:

1. `TimelineRenderSurface.kt` wrote `gate.start()` then
   `writeSentinel(SENTINEL_REFRESH_BEFORE_HZ)` BEFORE `readProjection()` — I/O inside the slice.
2. The three `writeSentinel(topCard/topHeader/bottomCard)` calls ran AFTER
   state publish and BEFORE `gate.end()`.
3. `TimelineBenchmarkActivity.writeSentinel` does a synchronous
   `prefs.commit()` plus an async mirror-file write — real file I/O inside the trace.
4. The slice ended on `snapshotFlow { layoutInfo.visibleItemsInfo }` layout
   visibility, not the spec §4.4 "first root draw callback".

Harness fix (keeps the same-thread pairing; no semantic change):

- `TimelineRenderSurface.kt`: `gate.start()` is now IMMEDIATELY adjacent to
  `readProjection()` — nothing between them.
- `TimelineBenchmarkActivity`: sentinels produced inside the measured path are
  buffered in-memory (`bufferSentinel`; plain map, no prefs/file I/O) and
  persisted only after `gate.end()` (`flushBufferedSentinels`).
- The slice now ends at the REAL first root draw callback: a root
  `Modifier.drawBehind` fires a one-shot main-thread signal when the first
  card key is part of the drawn frame (after layout, i.e. the frame being
  drawn contains the top header + first card, per spec §4.4). `gate.end()`
  runs after the signal resumption on the same main thread.
- The original 80.2 ms evidence above is retained (raw samples still on disk);
  the corrected values replace this row after the rerun completes —
  completed 2026-08-22, see the clean-rerun subsection below.

Files changed for the fix (not yet committed): `TimelineRenderSurface.kt`,
`TimelineBenchmarkActivity.kt`.

### Clean rerun 2026-08-22 (harness fix in effect; Step 7 → Step 8 completed)

The measurement-contract fix was verified cheaply then the full binding suite
was re-run on a stable device (never detached during execution):

1. **LoadA-only verify** (`binding01LoadA`, method-filtered,
   `benchmark/build/p6task6/loadA-verify.log`, BUILD SUCCESSFUL 7m16s,
   1/1 PASS): 15/15 closed one-slice traces, count all 1.0; `SumMs` range
   **42.99–75.58 ms** (Nearest-rank over the 15 samples: p50 **59.87 ms**,
   p95 **75.58 ms**) — the whole distribution dropped below the polluted
   55.6–93.8 ms band, confirming the sentinel-I/O removal was effective.
2. **Full clean suite** (`TimelineBenchmark` 4/4,
   `benchmark/build/p6task6/full-suite.log`, BUILD SUCCESSFUL **35m46s**):
   loadA 15 + scrollA 3 + scrollB 2 + loadB 15 all green; airplane restored;
   display `120`/`null` re-asserted; exit-info clean; manifest finalized.
3. **Step 8 host validation** (clean outputs under
   `benchmark/build/outputs/connected_android_test_additional_output/…/23116PN5BC - 16/`)
   recomputed all gates from raw samples:

| gate | raw-derived value | budget | result |
|---|---|---|---|
| query-to-first-correct-draw p95 | **78.78 ms** (p50 53.04 ms) | ≤ 60 ms | **FAIL** |
| pooled frame-CPU p95 | 6.27 ms | ≤ 16.67 ms | PASS |
| display-deadline jank | 10 bp (0.10 %) | ≤ 2 % | PASS |
| peak RssAnon | 112,184 KiB (~109.6 MiB) | ≤ 196,608 KiB | PASS |

Validator printout: `derived: queryP50Us=53044 queryP95Us=78777
frameP95Us=6266 jankBp=10 memoryMaxKib=112184`; `gestureCounts=[937, 939,
937, 942, 936] min=936 max=942 range=6`; validation FAIL only on query p95.

**Conclusion:** after the harness is clean (no sentinel I/O in the span, end at
the real first-root-draw callback, same-thread pairing), the cold-process
query-to-first-correct-draw p95 on this device at the full 10,000-card render
**still exceeds the frozen 60 ms budget**. The 80.2 ms result is superseded as
the polluted measurement; 78.78 ms is the corrected measurement and remains
over budget. Criterion 5 is therefore a **genuine technical-assumption failure
under the current frozen budget** — the harness-defect hypothesis is exhausted.
Raw evidence for the clean run is the copied
`connected_android_test_additional_output/benchmark/connected/23116PN5BC - 16/`
directory plus the suite/verify logs under `benchmark/build/p6task6/`. No
criterion was marked PASS that did not pass; the query-p95 gate is FAIL and is
reported plainly for the user decision (bounded trace-driven optimization first,
or an explicit budget/allowance decision).

### Trace attribution of the clean 78.78 ms query span (2026-08-22, analysis only — no optimization implemented)

Perfetto traces were parsed directly (`benchmark/build/p6task6/perfetto_decode2.py`
+ `trace_attribution*.py`; decoder reads the raw `TracePacket`/`FtraceEvent`
protobuf). Every one of the 30 load traces contains exactly one closed
`TimelineQueryToRender` slice whose duration matches the report's
`TimelineQueryToRenderSumMs` exactly. Inside the slice, the ftrace
`B|pid|name` markers were reconstructed into nested slices. The app's work
counts are IDENTICAL across all 30 iterations (33 `TextStringSimpleNode::measure`,
5 `Constructing StaticLayout`, 17 recompose) — so the p95 spread comes from the
same work costing more time, not from doing more work. Medians over 30 loads:

| phase | median | fast (loadB001 42.8 ms) | median iter (loadA007 54.1 ms) | p95 iter (loadB008 78.8 ms) | worst (loadA014 93.0 ms) |
|---|---|---|---|---|---|
| query+materialization gap (slice start → first big doFrame) | 31.6 ms (~59 %) | 28.7 ms (67 %) | 27.3 ms (51 %) | 35.9 ms (46 %) | 35.2 ms (38 %) |
| first frame (recompose+measureAndLayout+draw) | ~22 ms (~41 %) | 14.0 ms (33 %) | 26.6 ms (49 %) | 42.7 ms (54 %) | 57.6 ms (62 %) |
| — recompose | 8.0 ms | 2.5 ms | 8.4 ms | 9.1 ms | 15.3 ms |
| — AndroidOwner:measureAndLayout | 12.9 ms | 11.8 ms | 18.7 ms | 34.0 ms | 42.6 ms |
| — text measure (StaticLayout subset) | 9.3 ms | 8.4 ms | 15.2 ms | 30.3 ms | 39.2 ms |
| — draw (AndroidOwner:draw) | 0.42 ms | 0.41 ms | 0.45 ms | 0.46 ms | 0.44 ms |
| vsync/schedule wait within the gap | 2.0–8.4 ms | ~2.0 ms | ~8.4 ms | ~8.0 ms | ~8.0 ms |

Attribution: the span is split into two near-equal costs. (a) The query +
full materialization gap (Room `SELECT *` over 10,000 rows → 11,000-item typed
projection → Compose state publish) is a stable ~28–36 ms floor (median
31.6 ms), and the up-to-one-frame vsync alignment (~2–8.4 ms) rides on it. (b)
The first frame (composition + measure/layout + draw) adds ~14–58 ms, and it is
**the variable driver of the p95**: measureAndLayout and its text-measure
subset grow 9.3 ms → 39.2 ms (worst) for the SAME 33 text nodes / 5 StaticLayouts
under identical settings `120/null`, thermal 0/0. The slow tail is therefore
per-iteration text-layout cost variance (CPU/GC/JIT contention), not an
algorithmic workload difference. No single cheap win exists inside the frozen
contract (query, workload, loading strategy unchanged): the query+materialization
floor alone (~32 ms) plus one vsync alignment already leaves < 28 ms of frame
budget, and the text-layout variance is not a stable algorithmic hotspot.
Candidate ranking for a bounded optimization (NOT implemented, for user
decision): (1) reduce first-frame text-layout cost/bloat (largest variable
component, would need a font/measure cache or layout batching — touches the
render workload); (2) move vsync alignment off the measured deadline (already
unchanged); (3) nothing in SQLite/DAO is reducible without changing the query.

**Bottleneck conclusion (2026-08-22):** the clean query p95 span is split into
a stable query + full-materialization floor and a variable first-frame. The
query gap (median 31.6 ms, incl. up to one ~8.4 ms vsync alignment) is
irreducible under the frozen contract (10,000-row `SELECT *` × 11,000-item typed
build). The first frame is dominated by text layout: 33 identical
`TextStringSimpleNode::measure` nodes cost 9.3 ms at the median but 30–39 ms at
the p95/worst for the SAME work under identical settings — evidence of
per-iteration text-layout cost variance (CPU/GC/JIT contention), not an
algorithmic hotspot. There is **no cheap win inside the frozen contract**: the
query+materialization floor alone leaves < 28 ms of frame budget, and the
non-deterministic text-layout cost is the p95 driver. A workload-changing
optimization (e.g., pre-measured/cached text, layout batching, fewer text
overdraws) could shave the first-frame tail but is out of the frozen PoC scope.

**Step-3 finer-marker verify: BLOCKED (device dropped again, 5th drop).**
Per the analysis plan, `TimelineTypedReader.kt` was instrumented (nested,
same-thread, no I/O — `android.os.Trace.beginSection/endSection` around
`P6:RoomQuery` and `P6:ProjectionGrouping` only, no behavior/semantics change;
outer `TimelineQueryToRender` slice untouched) to split the query+materialization
gap further. Compile is green (`:core:data:compileBenchmarkKotlin` +
`:app:compileBenchmarkKotlin` BUILD SUCCESSFUL). The cheap `binding01LoadA`
verify to capture the finer breakdown did NOT run: `adb devices` empty and no
Xiaomi on Windows USB enumeration at launch — the loadA-attrib run failed before
test execution (`DeviceException: No connected devices!`), so no evidence was
written. The instrumentation is in the tree but unused until a stable
reconnection; no criterion is affected.

## Rev4 task log

| Task / attempt | Date | State | Main-agent evidence decision |
|---|---|---|---|
| `P6-6R4-A` / `Rev4.1` / `a1` | 2026-08-23 | `blocked` | Row 1 accepted PASS from 42/42 host tests and frozen hashes. Rows 2/4 remained BLOCKED: the attempted gate recorded `INSTALL_FAILED_USER_RESTRICTED`, `Starting 0 tests`, so no migration/index/out-of-process result existed. |
| `P6-6R4-A` / `Rev4.1` / `a2` | 2026-08-23–24 | `blocked` | After on-device install approval, the contract suite first passed 4/4. Code-quality review then found repeated pre-measurement seed writes and resource leaks; TDD fixes split one-time preparation from read-only open, added actual `seedWriteCount=0`, and closed every failure path. Fresh host compile/JVM gates pass and device XML reports 6 tests, 0 failures/errors. Spec compliance passes and quality re-review confirms both code findings fixed. Remaining blocker: closed UUID migration-contract DB files accumulate; bounded deletion requires explicit user approval. |
| `P6-6R4-A` / `Rev4.1` / `a3` | 2026-08-24 | `failed` (main-agent spec review) | Host/device gates passed 9/9, but review proved Android `Context.deleteDatabase` may also delete `-journal`, `-wipecheck`, and matching `-mj*` files. That exceeds the user's approval of only `.db`, `-wal`, and `-shm`, so the task was not accepted despite green tests. |
| `P6-6R4-A` / `Rev4.2` / `a4` | 2026-08-24 | `done` / **accepted** | TDD produced a real device RED then GREEN. Host JVM/emit/compile/static gates pass; final device XML reports 9 tests, 0 failures/errors. Final spec review: compliant. Final quality review: no Critical/Important/Minor, ready to proceed. Main agent accepted rows 1/2 and Task A's row-4 data-contract portion. |
| `P6-6R4-B` / `Rev4.1` / `b1` | 2026-08-26 | `done` / **accepted** | Render surface compiled and the 5 Rev4 selected-day render tests pass; traversal controller + 8 negatives + exact unmeasured summary (`1000/999/11/0/2025-09-26/2023-01-01`) pass; manifest/parser/validator no longer parse/gate `gestureCount`. Device 37/37, host 43/9, refresh-scan/deleteDatabase/production-leak scans clean, native display 120/null. |
| `P6-6R4-C` / `Rev4.1` / `c1` | 2026-08-26 | `failed` | Frozen binding run: query p95 39.26 ms / frame p95 14.99 ms / memory 122,680 KiB / traversal exact / stability clean all PASS; jank 3,796 bp = 37.96 % > 2 % FAIL. Evidence frozen at `benchmark/p6-frozen/p6rev4/run-20260826T155112Z`; host validator reproduces `jankBp=3796`. Genuine workload result (native 120 Hz deadline ~8.33 ms vs selected-day render CPU p95 14.99 ms), not a harness defect. Failure protocol: ADR + spec/plan revision + user reapproval required before retry. |

## Rev5 task log

| Task / attempt | Date | State | Main-agent evidence decision |
|---|---|---|---|
| `P6-6R5-A` / `Rev5` / `a1` | 2026-08-27 | `done` / **accepted** | Removed the jank budget gate (`JANK_BUDGET_BP` + `jank ... exceeds budget` branch); `jankBp` still recomputed/printed. New test `jankAboveTwoPercentIsRetainedDiagnosticAndPasses` (300 bp) passes; host validator 45/0 + metric-math 9/0; frozen-report validation `PASS` with `jankBp=3796`. |
| `P6-7` / `Rev5` / `a1` | 2026-08-27 | `done` / **accepted** | Module set/edge + test-tool allowlist refactor: 5 negative fixtures RED+reverted; `checkModuleDependencies` PASS (7 production + `[:benchmark]`, refresh-write scan clean); `apiBoundaryCheck` PASS; `assembleRelease` clean; snapshots byte-identical. Deviation: release `profileinstaller:1.4.0` is a pre-existing production transitive (lifecycle-runtime-android/Compose BOM), not a P6 leak — the assertion is scoped to `androidx.benchmark` + benchmark-added `profileinstaller:1.4.1`. |
| `P6-8` / `Rev5` / `a1` | 2026-08-27 | `done` / **accepted** (ADR 0015 acceptance pending) | Post-`clean` full rerun PASS: oracle regenerated byte-identical; host 87/0; device 9/0 + 37/0 + 4/0; new evidence frozen at `benchmark/p6-frozen/p6rev5/run-20260827T153454Z/` (42 files); regression query 7.52 % / frame 4.76 % ≤ 10 % PASS; isolation PASS. Frozen Rev3/Rev4 evidence untouched (87 files). |

## Task 6R4-C Rev4 binding run — frozen evidence (attempt `c1`, 2026-08-26)

Raw evidence was frozen BEFORE any later Gradle command and validated only from the frozen copy. The Rev3 `benchmark/p6-frozen/p6final/` evidence is preserved untouched.

**Frozen directory:** `benchmark/p6-frozen/p6rev4/run-20260826T155112Z/` (41 files + `EVIDENCE.sha256`; one `*-benchmarkData.json`, one `p6-run-manifest.json`, four `additionaltestoutput.benchmark.message_*.txt`, 35 `*.perfetto-trace`).

**Binding suite (device, Xiaomi 14 Pro `c55ad7e4`, API 36):** `:benchmark:connectedBenchmarkAndroidTest` with `p6ExpectedLastDayHash=7c055c9a0bdd0e1976b0e847fbecda5a0fc9039c643d5e2cc4c98c5ce9c10fd5`. All four name-ordered blocks `binding01LoadA(15) → binding02ReplaceA(3) → binding03ReplaceB(2) → binding04LoadB(15)` PASSED device-side: test XML `benchmark/build/outputs/androidTest-results/connected/benchmark/TEST-23116PN5BC - 16-_benchmark-.xml` (`tests=4 failures=0 errors=0`, time 607.791 s). Post-suite native display `peak=120`/`min=null`, airplane mode restored to `0`, package exit-info clean. Suite log `benchmark/build/p6/6r4c-c1-suite2.log` (BUILD SUCCESSFUL 10m30s).

**Host validator recomputation over the frozen copy** (`./gradlew.bat "-Dp6ReportDir=…/run-20260826T155112Z" :core:data:validateP6BenchmarkReports`):

```
derived: queryP50Us=20868 queryP95Us=39261 frameP95Us=14989 jankBp=3796 memoryMaxKib=122680
controlTraversal=ParsedTraversalSummary(1000/999/11/0/2025-09-26/2023-01-01)
P6 benchmark report validation: FAIL
  - jank 3796bp exceeds budget 200bp
```

| Gate | Derived | Budget | Result |
|---|---|---|---|
| query-to-first-correct-draw p95 | 39.26 ms (p50 20.87 ms) | ≤ 60 ms | **PASS** |
| pooled frame-CPU p95 | 14.99 ms | ≤ 16.67 ms | **PASS** |
| display-deadline jank | 3,796 bp = 37.96 % (3,755/9,890 frames) | ≤ 200 bp (≤ 2 %) | **FAIL** |
| peak RssAnon | 122,680 KiB (~119.9 MiB) | ≤ 196,608 KiB | **PASS** |
| traversal controls | exact `1000/999/11/0/2025-09-26/2023-01-01` (control + 5 measured), one query in flight, no prefetch | exact match | **PASS** |
| stability | no ANR/OOM/crash/process-death/relaunch/timeout | none | **PASS** |

Report contract details that all pass: four reports with runs 15/3/2/15; 30 positive unique `TimelineQueryToRender` samples (count all 1.0, query durations 16.77–39.94 ms); five non-empty frame arrays (1,968–1,991 frames each); 35 RssAnon maxima; 35 distinct refresh pairs (load 120.0→120.0, replacement 120.00001→120.00001 — replacement refresh is the device `mActiveRenderFrameRate` read for the frozen `replace-ready` surface, which exposes no refresh sentinel); exact native settings before/after; one exact unmeasured summary; five exact measured summaries; 35 copied Perfetto traces; stale Rev3 `gestureCount` rejected as schema (host validator 45/45).

**Result:** the frozen Rev4 selected-day binding FAILS the display-deadline jank absolute budget at 37.96 % (> 2 %). Query p95, frame p95, memory, exact traversal controls, and stability all PASS. The jank failure is reported plainly and is NOT reclassified or marked PASS. Evidence is preserved; Task 7 must not start until the same-slice failure protocol (spec §4.9 / plan Task 6R4-C Step 6) is followed. Per-iteration frame diagnostics for the failure slice: median `frameDurationCpuMs` 8.28–8.62 ms (≈ the 120 Hz 8.33 ms period), positive `frameOverrunMs` 586–961 frames per iteration with mean overrun ~2 ms — the rapid one-day-at-a-time replacement draws sit at the 120 Hz deadline, so a large fraction miss it under the untouched display binding.

## Historical Task log

| Task | Date | Result |
|---|---|---|
| Task 0 — lifecycle entry | 2026-08-16 | PASS — P6 is the unique `in-progress` slice; spec/plan approved; AGENTS/index/roadmap synchronized; `verification.md` created |
| Task 1 — benchmark wiring | 2026-08-16 | PASS — baseline snapshot captured; catalog aliases, `:benchmark` include, `:core:data`/`:app` benchmark build types, `:benchmark` module and smoke test added; three variants BUILD SUCCESSFUL; dependency validation PASS (exactly the two reviewed families; release closure byte-identical to baseline). Device bindings recorded, 60.00 Hz forced/held/restored; `BenchmarkWiringSmokeTest` PASS (package diff / launch / non-debuggable / profileable-from-shell / 60.00 Hz). First attempt no device → paused per gate, then completed same session once attached. |
| Task 2 — fixture, oracle, metric math, artifacts | 2026-08-16 | PASS — 36 JVM tests PASS (15 `TimelineFixtureGeneratorTest` + 12 `TimelineOracleTest` + 9 `TimelineMetricMathTest`). `emitP6Oracle` (host JavaExec over the unit-test runtime) writes `core/data/build/p6/{fixture.sha256,oracle.sha256,oracle.txt}`; `sha256sum oracle.txt` = `oracle.sha256`. Fixture `32fd36deaab5798f54533931b2ab261cd24c759790179895d37f0f553430b1b9`, oracle `b5e7151de0251b5d81d7726e54ff1fd534580a0dad3bf5b8bfe39ce14ec7545d` — both independently re-derived by a separate Java reference implementation and recorded in the Frozen artifacts table. Task-6 `TimelineBenchmarkReportParser`/`TimelineBenchmarkReportValidatorTest` intentionally not built yet (deferred to Task 5). |
| Task 3 — shadow projection + contract test | 2026-08-16 | PASS — benchmark-scoped `TimelineCard`/`TimelineShadowDb`/`TimelineTypedReader` (`:core:data` benchmark source set, no `testFixtures` import), `TimelineBenchmarkActivity` `seed-and-verify` (fixture→entity→file-DB insert→typed read→sentinels) + manifest declaration, and `TimelineProjectionContractTest` added. Three variants BUILD SUCCESSFUL; Room KSP handled the benchmark source set without `core/data/build.gradle.kts` wiring. Contract test PASS: `rowCount=10,000`, `flattenedCount=11,000`, `actualHash=b5e7151d…` == host oracle. Deviations: (a) MIUI aborts instrumentation background activity starts, so the test launches via shell `am start` and reads the Activity's sentinels via target-context SharedPreferences (the instrumentation's UiAutomation cannot see the target's windows on this firmware — accessibility reading failed for `getRootInActiveWindow` and `getWindows`); (b) `:benchmark` added `androidx.lifecycle:lifecycle-runtime:2.9.4` to fix a `MonitoringInstrumentation` `ReportFragment.Companion` `NoSuchFieldError` caused by `benchmark-macro:1.4.1` pulling `lifecycle-runtime:2.3.1`. |
| Task 4 — render surface + exhaustive rendered correctness | 2026-08-16 | PASS — `TimelineRenderTraversalTest` 2/2 (3 consecutive runs, 0 failures): `render-correctness` drives an in-process recorder (`TimelineRenderedPayloadRecorder`) that sequentially `scrollToItem(0..10_999)`, captures 11,000 actually-composed rendered payloads, and reports rendered SHA-256 `b5e7151d…` == `oracle.sha256`; `render-wrong-order` (top two cards exchanged) captures 11,000 payloads with hash `bae17dcd…` ≠ oracle and the shared equality assertion throws `AssertionError`. `TimelineRenderSurface` (`LazyColumn`, stable keys, header/card content types, `testTagsAsResourceId`, `TimelineTop/TimelineItem/TimelineBottom` tags) + `TimelineBenchmarkActivity` `render-*` modes + `TimelineShadowSeeder.loadForRender` (trace gate starts immediately before the one DAO query, ends at first correct draw) added; `traceOk=true`, top/bottom sentinels frozen-IDs. Deviations: (a) `TimelineBenchmarkActivity` changed from `android.app.Activity` to `androidx.activity.ComponentActivity` to host Compose `setContent`; (b) `:benchmark` aligned `androidx.collection:1.5.0` + `androidx.core:1.18.0` (test APK's `benchmark-macro:1.4.1` copies shadowed the app's newer classes → `SimpleArrayMap` `NoSuchMethodError` on `ComponentActivity` init); (c) render modes stamp a `mode` sentinel atomically with the outcome clear and the tests wait for it, hardening against the once-observed second-`am start` launch no-op. |
| Revision 2 lifecycle pause | 2026-08-16 | The Task 5 forced-60-Hz control failed to converge while the restored device remained at native 120 Hz. No binding benchmark was run. The user accepted ADR 0012, delegated final spec review/correction to Codex, and spec Revision 2 passed that review. The revised plan is drafted; Task 5+ remain paused until explicit plan approval. |
| Task 5 — harness repair around the ADR-0012 native display contract | 2026-08-18 | PASS — Revision 2 gate fully green (see criterion 10): read-only `TimelineDisplayBinding` (120/null exact-match, positive-finite refresh, no settings writes), before/after refresh sentinels in the Activity/render surface (load at query dispatch / first correct draw; scroll-ready before ready=true / after bottomReached=true), 60-Hz controls removed from the self-check, wiring smoke repaired to positive-finite observation, strict Revision 2 host manifest parser + 11 focused tests (35 records 15/3/2/15, native settings, profilerOutputs ↔ trace resolution), `MiniJsonParser` extended to accept `NaN`/`Infinity` tokens so non-finite refresh fails precisely. Frozen manual gesture count pinned: **1045**. |
| Task 6 — binding benchmark + host validation | 2026-08-18 | **BLOCKED (in progress)** — Steps 1–6 implemented and verified; Step 7's binding suite cannot pass on the frozen scroll-protocol assumption. See the Task 6 blocker note below. |
| Revision 3 approval | 2026-08-18 | The user approved D′ (exact-count gate removed, full-scroll control gate retained, counts as diagnostics); spec/plan revised; Task 6 retry authorized. |
| Task 6 — D′ Step 7 rerun | 2026-08-21 | **ABORTED (in progress)** — first D′ rerun ran `binding01LoadA` 15/15 green (refresh 120→120, thermal 0) and the control full scroll reached bottom (gestureCount=952, diagnostic), then aborted in `binding02ScrollA` when the USB device was physically disconnected (user action). Device reconnected; `airplane_mode_on` restored 1→0; `peak=120`/`min=null` confirmed. New host-validation-fatal defect discovered on the completed load block: `TraceSectionMetric("TimelineQueryToRender")` count=0/sum=0 on all 15 iterations (cross-thread `Trace.beginSection`/`endSection` in `TimelineQueryToRenderGate`). No `benchmark_report.json`/`p6-run-manifest.json` produced; no criterion 5–9 marked PASS. See blocker note §"D′ retry run 2026-08-21". |
| Task 6 — D′ trace-gate fix + full suite rerun (Steps 7–8) | 2026-08-22 | **COMPLETED — criteria 5 FAIL / 6–9 PASS on the clean rerun; reported plainly as a technical-assumption failure.** Trace-gate fix (same-thread `beginSection`/`endSection` in `TimelineRenderSurface.kt`) proven by a `binding01LoadA`-only verify (15/15, count=1.0, positive SumMs). Host validator contract corrected to the REAL macrobenchmark 1.4.1 output shape. First full Step 7 suite (2026-08-21) ran 4/4; Step 8 measured query p95 80.2 ms → **user reclassified it as measurement-contract pollution** (sentinel prefs/file I/O inside the slice + layout-visibility end); spec/plan unchanged; harness fixed (`TimelineRenderSurface.kt`: `gate.start()` immediately adjacent to `readProjection()`, root `drawBehind` first-correct-draw signal; `TimelineBenchmarkActivity.kt`: in-memory `bufferSentinel`/`flushBufferedSentinels`). **Clean rerun 2026-08-22** (device stable throughout, never detached): loadA-verify p50 59.87 / p95 75.58 ms (range 42.99–75.58, all below the polluted 55.6–93.8 band) then full suite 4/4 BUILD SUCCESSFUL 35m46s; Step 8 host validator recomputed query **p50 53.0 / p95 78.78 ms — STILL over the 60 ms budget → criterion 5 FAIL**; frame p95 6.27 ms, jank 10 bp, peak RssAnon 112,184 KiB → criteria 6–9 PASS. Gesture counts `[937, 939, 937, 942, 936]` (min 936, max 942, range 6); control scroll 952; all 35 refresh pairs 120→120; settings 120/null before and after; exit-info clean. **The harness-defect hypothesis is exhausted: the corrected query-p95 still exceeds 60 ms, so this is now a genuine technical-assumption failure for the user to decide (bounded trace-driven optimization or a budget/allowance change).** Files changed: `TimelineRenderSurface.kt`, `TimelineBenchmarkActivity.kt`, `TimelineBenchmarkReportParser.kt`, `TimelineBenchmarkReportValidatorTest.kt`, comments in `TimelineBenchmark.kt`, `verification.md`. |
| Task 6 — final clean rerun + evidence freeze + finer-marker attribution | 2026-08-22 | **COMPLETED — criterion 5 FAIL (p95 75.22 ms) → ADR 0013 written.** User directed: fix the two remaining measurement-contract defects (handle.close in-span; gate.end at coroutine resumption), keep the finer markers, run the full frozen suite, and freeze JSON/manifest/traces before any subsequent Gradle command — no text-cache attempt. `TimelineRenderSurface.kt` fixed (handle promoted to LaunchedEffect scope, `handle?.close()` after `await()`; `DrawClosedSignal` holds the gate and calls `gate.end()` directly inside the fire branch of `markDrawnWhenFirstCardPresent`); compiles green, spot-checked. Full suite 4/4 BUILD SUCCESSFUL 37m2s. Evidence frozen to `benchmark/p6-frozen/p6final/run-2026-08-22/` with `TRACES.sha256`/`JSON.sha256` provenance. Host validator: `queryP50Us=59779 queryP95Us=75218 frameP95Us=6389 jankBp=11 memoryMaxKib=104684 gestureCounts=[938,937,936,943,937]` → criterion 5 FAIL (p95 75.22 ms > 60 ms), criteria 6–9 PASS. Finer-marker attribution (`benchmark/p6-frozen/p6final/attr_p6markers.py`) over 30 load traces: `P6:RoomQuery` median 20.44 ms / p95 27.25 ms, `P6:ProjectionGrouping` median 8.16 ms / p95 13.94 ms, sum median 30.44 ms. ADR 0013 written asserting the single frozen conclusion (full-materialization fails 60 ms p95) + rejected option + selected-day alternative. Files changed: `TimelineRenderSurface.kt`, `docs/architecture/decisions/0013-timeline-full-materialization-fails-60ms.md`, `verification.md`. |

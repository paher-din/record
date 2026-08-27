# Tech PoC P6 — Timeline Performance Implementation Plan

- **Status:** `completed` — 2026-08-28. All Rev5 tasks (6R5-A, 7, 8) are executed and accepted with evidence in [verification.md](verification.md); ADR 0014 and ADR 0015 are accepted; the unique active spec becomes `none`; MVP work may start.
- **Spec:** [spec.md](spec.md) — Revision 5 explicitly approved 2026-08-27.
- **ADR 0011:** [Macrobenchmark test-module boundary](../../../architecture/decisions/0011-macrobenchmark-test-module-boundary.md) — accepted; `:benchmark` is the single allowlisted test-tool project.
- **ADR 0012:** [Native-display deadline-jank benchmark contract](../../../architecture/decisions/0012-native-display-deadline-jank-benchmark-contract.md) — accepted; native user settings and system-deadline jank semantics remain unchanged (its ≤2 % jank budget application to the P6 replacement workload is partially overridden by ADR 0014).
- **ADR 0014:** [Native-display deadline-jank deferred to M5](../../../architecture/decisions/0014-native-display-deadline-jank-deferred-to-m5.md) — accepted 2026-08-27; P6 replacement jank becomes diagnostic, ≤2 % jank gate deferred to M5.
- **ADR 0013:** [Timeline full-materialization load fails the 60 ms query budget](../../../architecture/decisions/0013-timeline-full-materialization-fails-60ms.md) — accepted; selected-day bounded query replaces full materialization, adjacent-day prefetch is optional/post-first-draw only, and cross-day keyset paging/Paging 3 are not selected. The eventual passing result moves to ADR 0015.

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:executing-plans` after explicit Rev5 approval, `superpowers:test-driven-development` for Task 6R5-A and Task 7, and `superpowers:verification-before-completion` before Task 8 reports completion. Tasks 6R4-A/B/C remain historical execution evidence. The main agent dispatches each bounded implementation task under `docs/engineering/agentic-workflow.md`, reviews raw evidence, and executes the remaining tasks sequentially because they share the benchmark harness and evidence artifacts.

**Goal:** Prove the mixed transaction/note selected-day data and benchmark-render path remains correct and operationally viable while the shadow database retains all 10,000 fixture cards: exact first-day/last-day/empty-day state against independent oracle slices, selected-day query-to-first-correct-draw p95 ≤ 60 ms, single-day replacement frame-CPU p95 ≤ 16.67 ms, replacement display-deadline jank recorded as diagnostic (the ≤2 % production-interaction gate is deferred to M5, ADR 0014), peak anonymous RSS ≤ 192 MiB, no ANR/OOM/crash/process death, and a one-sided 10% regression allowance against the new Rev4 query/frame baselines. P6 does not claim production `TimelineHome` 120 Hz interaction quality. No production schema, surface, module, or dependency change; the only module-set addition remains the ADR-0011 test-tool project.

**Architecture:** a benchmark-scoped shadow Room projection in `:core:data`'s `benchmark` source set retains the complete fixture and exposes only an indexed half-open selected-day query. A benchmark-build-only `TimelineBenchmarkActivity` renders one selected-day `LazyColumn` state and consumes the projection through a typed reader; it never publishes adjacent-day rows. The load benchmark measures the chronological last fixture day. The frame/jank workload sequentially replaces the state across all 1,000 fixture dates, waiting for each first correct draw before issuing the next bounded query, so it produces stable frame samples without inventing a cross-day continuous list. `:benchmark` launches and drives the Activity out of process; correctness crosses the process boundary as selected-day count/hash/date sentinels compared with host-emitted independent slice hashes. Adjacent-day prefetch is not implemented. Raw-report validation runs on copied host outputs. Everything remains absent from `main` and `release`.

**Tech Stack:** unchanged from P0–P5 — Gradle 9.5.0, AGP 9.3.0 (built-in Kotlin 2.2.10), KSP2 2.3.9, Room 2.8.4, Compose BOM 2026.06.00. Two new **direct** benchmark-scoped AndroidX families: `androidx.benchmark:benchmark-macro-junit4:1.4.1` (in `:benchmark`) and `androidx.profileinstaller:profileinstaller:1.4.1` (target `:app` benchmark variant). `:benchmark` uses `com.android.test` at the pinned AGP version (not a third external version). The required AndroidX Test, runner, Benchmark, Perfetto, and tracing transitives are recorded and reviewed; their existence is not itself a failure.

**Reference environment (frozen):** Xiaomi 14 Pro (physical, non-rooted, USB debugging), Android 16 / API 36 (product baseline API 34). Native user display settings are exactly `peak_refresh_rate=120` and `min_refresh_rate=null`; the harness reads but never modifies them. Activity-visible refresh is recorded before and after every measured iteration and may vary without invalidating the run. Projection `ZoneId` is fixed to `Asia/Shanghai`; binding selected day is `2025-09-26` with bounds `[1758816000000, 1758902400000)`. Build fingerprint, CPU, RAM, settings, and refresh observations are recorded in `verification.md` immediately before measurement.

**Namespaces:** `:core:data` benchmark/testFixtures → `com.paherdin.record.core.data.timeline`; `:app` benchmark source set and `:benchmark` module → `com.paherdin.record.benchmark`. Production namespaces untouched.

**Pre-implementation dependency validation:** Task 1, after lifecycle entry. The two pinned declarations are the only new direct dependency families. If either version does not resolve, a new direct dependency is needed, or the transitive closure contains an unreviewed/unsafe family, stop and revise the spec/plan as required; versions are never switched mid-execution.

**Database-schema approval redline:** Task 6R4-A proposes only a benchmark-shadow `TimelineShadowDb` version 1 → 2 additive migration that creates `index_timeline_card_occurredAt` and preserves all 10,000 fixture rows. `RecordDatabase`, committed production schemas, user data, and production migrations remain byte-unchanged. The reason is to measure the chosen bounded query as an indexed range read; the risk is that the Rev4 baseline becomes dependent on this test-only index and cannot authorize M5's physical production schema. Explicit approval of this Rev4 plan is required before the migration is implemented.

**Benchmark-temp deletion approval:** on 2026-08-24 the user explicitly approved deleting only app-private `timeline-shadow-migration-contract-<valid UUID>.db` files created by the Task 6R4-A migration contract and their exact `-wal`/`-shm` sidecars. Cleanup must validate the fixed prefix and UUID, reject the main `timeline-shadow.db` and every production database name/path, run in `finally` after all handles close, and never broaden to other files or directories. Android `Context.deleteDatabase` is forbidden because it also deletes `-journal`, `-wipecheck`, and matching `-mj*` files; the implementation must resolve and delete exactly the three approved canonical direct-child `File` targets without directory enumeration, globbing, or prefix matching.

## Dependency review record

P6 introduces exactly two benchmark-scoped AndroidX artifact families, both Apache-2.0, AndroidX-maintained, and absent from `release`:

1. `androidx.benchmark:benchmark-macro-junit4:1.4.1` plus its resolved AndroidX Test/Benchmark/Perfetto/tracing transitives — used only in `:benchmark`.
2. `androidx.profileinstaller:profileinstaller:1.4.1` — used only in the target `:app` `benchmark` variant so the benchmark tooling can perform its profile/reset handshake. `CompilationMode.Full` compiles the whole target; ProfileInstaller is not described as supplying a baseline profile to that mode.

The `com.android.test` plugin reuses AGP 9.3 and is not a new external version. Compose artifacts are already declared and activated as-is. Removal path (ADR 0011): remove `:benchmark`, the `benchmark` build types/source sets on `:app` and `:core:data`, and the two dependency declarations; that future removal requires the project's deletion approval. Task 1 records the full direct and transitive graph, licenses, permissions, and release absence. No ads, analytics, crash reporting, closed-source tracking SDK, or `INTERNET` capability.

Implementation references are the official Android guides for [Macrobenchmark project setup, multi-module fallbacks, execution, and copied outputs](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview), [Macrobenchmark instrumentation arguments](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args), and [`CompilationMode.Full`](https://developer.android.com/reference/androidx/benchmark/macro/CompilationMode). If the pinned 1.4.1 API differs from a later web page, Task 1 must inspect the resolved 1.4.1 signatures and revise this plan rather than guessing.

## Public API of `:core:data` — no delta

The P1–P5 normative declaration list is inherited **unchanged**. P6 adds no public member, repository method, Room table, schema export, or `main` file. `api/expected-surface.txt` and `apiBoundaryCheck` are untouched; the benchmark-scoped shadow projection and typed reader live only in `:core:data`'s `benchmark` source set and cannot be referenced from `main` or `release`.

## Frozen projection semantics (from spec §4.1)

`TimelineCard`: `id` (canonical lowercase UUID text), `type` (`TRANSACTION` | `NOTE`), `occurredAt` (epoch ms), `title`, `amountMinor` (nullable `Long`, `TRANSACTION` only), `body` (nullable). `DayHeader` is derived from `occurredAt` in `ZoneId.of("Asia/Shanghai")`; one header precedes one non-empty selected day's cards, while an empty selected day has no item. Sort selected-day cards `occurredAt DESC`, then canonical lowercase UUID text ascending under SQLite `BINARY`/ASCII lexical order (the JVM oracle uses an explicit string comparator, never `UUID.compareTo`). The historical complete artifact remains exactly 1,000 headers + 10,000 cards for fixture identity only; a non-empty Rev4 UI state is exactly 1 header + 10 cards = 11 items. The query implementation cannot produce its own expected slice.

## Frozen fixture `timeline-v1`

Generator seed **`2026081606L`**, `ZoneId` `Asia/Shanghai`. Deterministic output, exactly:

- 10,000 cards over 1,000 consecutive local days, exactly 10 cards per day;
- 6,000 Transactions + 4,000 Notes;
- card index is `i = d * 10 + s`; transaction slots are exactly `{0, 2, 4, 5, 7, 9}` and note slots are `{1, 3, 6, 8}`, so every day is genuinely mixed in occurrence order;
- `SLOT_OFFSETS_MS = [0, 0, 21_600_000, 32_400_000, 43_200_000, 54_000_000, 43_200_000, 64_800_000, 75_600_000, 86_399_999]`. Slots 0/1 and 4/6 create cross-type timestamp ties; slots 0 and 9 pin the local-day edges;
- `dayStart(d) = LocalDate.of(2023, 1, 1).plusDays(d).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()` and `occurredAt = dayStart(d) + SLOT_OFFSETS_MS[s]`. Generation never reads the device default zone and never substitutes `d * 86_400_000` for local-date construction;
- transaction kind is assigned by chronological ordinal over all 6,000 transactions (ascending `occurredAt`): ordinals 0–9 = `OPENING_BALANCE` (the 10 chronologically oldest transactions — day 0's six transaction slots plus day 1's first four transaction slots), 10–3009 = `EXPENSE`, 3010–4809 = `INCOME`, 4810–5999 = `TRANSFER`;
- canonical lowercase UUID text is `UUID.nameUUIDFromBytes("timeline-v1:2026081606:$i".toByteArray(UTF_8)).toString()`; IDs are generated before kind assignment and all equal-time ordering uses the UUID text comparator;
- deterministic selector `pick(i, salt, size) = floorMod(2026081606L + 1_000_003L * i + 97_409L * salt, size).toInt()`; accounts = `["现金", "招商银行", "支付宝", "微信"]`, expense categories = `["餐饮", "交通", "购物", "居住"]`, income categories = `["工资", "奖金", "退款"]`. Source account uses salt 1, expense category salt 2, and income category salt 3;
- transaction titles are respectively `"<account> · 期初余额"`, `"<account> · <expenseCategory>"`, `"<account> · <incomeCategory>"`, and `"<from> → <to>"`; transfer destination index is `(sourceIndex + 1 + pick(i, 4, accounts.size - 1)) % accounts.size`, so it is never the source. All four accounts are fixture-only CNY accounts; currency is not a `TimelineCard` field;
- integer-minor amounts are respectively `100_000 + pick(i, 11, 900_001)`, `-(100 + pick(i, 12, 49_901))`, `100_000 + pick(i, 13, 900_001)`, and `-(1_000 + pick(i, 14, 199_001))`, all calculated as `Long`; transaction `body = null`;
- each Note has `amountMinor = null`, body exactly `"# 记录 <i>\n第 <d> 天，第 <s> 槽。"` with no truncation, and title `"记录 <i>"`, obtained as `firstNonBlankLine.removePrefix("# ").trim()`.

Canonical serialization is UTF-8 with `LF` only. A nullable string is `-1:`; a non-null string is `<UTF-8-byte-count>:<value>`. The fixture stream starts with `timeline-v1\n2026081606\nAsia/Shanghai\n`, then one `C` record per card in generator index order with tab-separated fields `id`, `type`, `occurredAt`, `title`, `amountMinor` (`NULL` when absent), and `body`; byte lengths make the embedded Note newline unambiguous. The oracle stream uses the same card record plus `H\t<yyyy-MM-dd>\n` header records in flattened order, with the date from `DateTimeFormatter.ISO_LOCAL_DATE`. No locale-dependent formatting, platform newline, object `toString()`, or JSON map ordering participates. The existing complete artifacts remain `core/data/build/p6/{fixture.sha256,oracle.sha256,oracle.txt}`. Rev4 additionally emits `core/data/build/p6/day-oracles/{2022-12-31,2023-01-01,2025-09-26}.{txt,sha256}` and `manifest.tsv`; `manifest.tsv` fixes `date`, `dayStart`, `nextDayStart`, `itemCount`, and generated SHA-256 for each slice. Fresh regeneration must equal every recorded hash. `2023-01-01` and `2025-09-26` each have 11 items and exercise start/end boundaries plus both tie groups; `2022-12-31` has zero items.

## Frozen metric formulas (from spec §4.4–4.7)

- **Query-to-first-correct-draw:** `TraceSectionMetric("TimelineQueryToRender")` for selected day `2025-09-26`; 5 unmeasured warmups then 30 measured cold-process iterations; nearest-rank p50/p95 at one-based ranks 15/29. Budget p95 ≤ 60 ms.
- **Frame CPU:** `FrameTimingMetric.frameDurationCpuMs` pooled over the 5 valid measured selected-day replacement iterations; nearest-rank p95 ≤ 16.67 ms.
- **Display-deadline jank (diagnostic):** `count(frameOverrunMs > 0) / count(all frameOverrunMs samples)` over the same measured replacement frames, recomputed and recorded but non-gating (ADR 0014). AndroidX supplies each frame's deadline; this is not reconstructed from `frameDurationCpuMs` or a fixed 16.67 ms threshold.
- **Memory:** `MemoryUsageMetric(Mode.Max, subMetrics = [RssAnon])`; binding peak = max `memoryRssAnonKb` across every valid selected-day load and replacement iteration; ≤ 196,608 KiB (192 MiB). `RssFile`/`RssShmem`/heap/GPU are diagnostic-only.
- **Regression (one-sided):** for p95 query and p95 frame, `new <= baseline` always passes; only when `new > baseline` compare `(new - baseline) / max(new, baseline)` to `0.10` (fail if `> 0.10`). Memory uses its absolute cap only; deadline jank is a diagnostic in Rev5, not a regression metric.

## Frozen selected-day replacement protocol (spec §4.5)

The Rev3 cross-day full-scroll protocol below is retained only inside completed Task 6 as historical evidence. Rev4 launches a prepared Activity with the complete 10,000-row database open but no day state published, runs one unmeasured control traversal, then five measured traversals from the same empty prepared state.

- Traversal order is the exact descending `LocalDate` sequence `2025-09-26` through `2023-01-01`, inclusive: 1,000 selected-day queries/draws and 999 state replacements.
- For every date, derive bounds with `date.atStartOfDay(ZoneId.of("Asia/Shanghai"))` and `date.plusDays(1).atStartOfDay(...)`; never use mutable device default time zone or arithmetic `+ 86_400_000`.
- Issue one bounded query, publish only its one-header/ten-card state, and wait for the generation-matched first correct draw before issuing the next query. No query overlap, prefetch, explicit sleep, gesture, cross-day concatenation, or accumulated list is allowed.
- Every valid iteration reports `selectedDayDrawCount=1000`, `replacementCount=999`, `maxPublishedItemCount=11`, `prefetchQueryCount=0`, `firstDate=2025-09-26`, and `lastDate=2023-01-01`. The host validator equality-gates these values; there is no gesture count in Rev4.
- Startup, process/database setup, pre-start stationary wait, post-completion sentinel persistence, and invalid-iteration frames are excluded. The bounded queries and their state-replacement frames remain inside the frame/jank workload.

## File inventory

| File | Purpose |
|---|---|
| `settings.gradle.kts` | Include the one ADR-0011 allowlisted `:benchmark` test-tool project |
| `gradle/libs.versions.toml` | Add only the two pinned direct aliases and the existing-AGP `com.android.test` plugin alias |
| `core/data/build.gradle.kts` | Add a non-published `benchmark` build type/source set, its Room/KSP wiring, and host-only `emitP6Oracle` / `validateP6BenchmarkReports` tasks; existing `testFixtures` stays enabled; no `main` change |
| `core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/timeline/TimelineFixtureGenerator.kt` | Frozen selector, seed, day/slot/type/kind/label/amount/body rules and canonical fixture encoder |
| `core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/timeline/TimelineOracle.kt` | Independent pure-JVM complete oracle plus `selectedDay(date)` slicing/bounds/canonical encoding; never calls the projection reader |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineFixtureGeneratorTest.kt` | JVM red/green tests for exact fixture fields, edges, ties, counts, encoding, determinism and hash |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineOracleTest.kt` | JVM tests for complete-artifact identity plus first/last/empty selected-day slices, half-open boundaries, ties, mutation detection, and repeatability |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineArtifactEmitter.kt` | Host entry point that preserves the three complete artifacts and writes Rev4 day-slice artifacts/manifest under `core/data/build/p6/day-oracles/` |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineMetricMath.kt` | Host-only nearest-rank, jank, memory-max and one-sided-regression formulas |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineMetricMathTest.kt` | Formula boundary tests, including `new <= baseline`, exactly 10%, and just over 10% |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineBenchmarkReportParser.kt` | Focused parser for copied Macrobenchmark JSON plus exact native-settings, refresh, and Rev4 selected-day traversal controls; no production JSON dependency |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineBenchmarkReportValidatorTest.kt` | Host validator requiring exact report/sample/trace/manifest counts, variable positive refresh observations, 30 selected-day load samples, five exact 1,000-day replacement controls, and independent recomputation of all four gates |
| `core/data/src/benchmark/kotlin/com/paherdin/record/core/data/timeline/TimelineCard.kt` | Benchmark-scoped `TimelineCard`/`DayHeader`/`CardType` value types (never in `main`) |
| `core/data/src/benchmark/kotlin/com/paherdin/record/core/data/timeline/TimelineShadowDb.kt` | Benchmark-scoped Room entity/index/DAO/database: only `querySelectedDay(dayStart, nextDayStart)` serves render/load state; complete row count and query-plan inspection remain test controls |
| `core/data/src/benchmark/kotlin/com/paherdin/record/core/data/timeline/TimelineTypedReader.kt` | Typed selected-day boundary: bounded ordered rows → zero items or one `[DayHeader, Card…]` state; rejects out-of-range/mixed-day rows |
| `app/build.gradle.kts` | Add `benchmark` initialized from `release`, non-debuggable, debug-signed, non-minified, `matchingFallbacks += listOf("release")`; add `benchmarkImplementation(testFixtures(project(":core:data")))` and benchmark-only ProfileInstaller |
| `app/src/benchmark/AndroidManifest.xml` | Benchmark-only `<profileable android:shell="true">` and the `TimelineBenchmarkActivity` declaration; absent from `main`/`release` |
| `app/src/benchmark/kotlin/com/paherdin/record/benchmark/TimelineBenchmarkActivity.kt` | Mode-driven seed/selected-day load/replacement/correctness Activity; maps fixtures, owns DB opening, renders one-day state, and exposes day/count/hash/bounds/replacement plus refresh sentinels |
| `app/src/benchmark/kotlin/com/paherdin/record/benchmark/TimelineRenderedPayloadRecorder.kt` | Correctness-only recorder for the currently selected day; hashes each composed payload and exposes count/hash without a cross-day traversal |
| `benchmark/build.gradle.kts` | `com.android.test`, namespace `com.paherdin.record.benchmark.test`, its own `benchmark` build type with release fallback, target `:app` `benchmark`, only project target edge `:app`, Macrobenchmark 1.4.1 |
| `benchmark/src/main/AndroidManifest.xml` | Benchmark runner manifest only; no product component |
| `benchmark/src/main/kotlin/com/paherdin/record/benchmark/BenchmarkWiringSmokeTest.kt` | Variant/profileability/target launch and positive finite Activity-visible refresh observation; no fixed-Hz gate |
| `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineProjectionContractTest.kt` | Out-of-process 10,000-row DB, selected-day SQL/index-plan, and typed first/last/empty slice checks against host hashes |
| `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineRenderTraversalTest.kt` | Out-of-process rendered first/last/empty slice checks plus controlled within-day tie-order assertion failure |
| `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineDisplayBinding.kt` | Focused read-only native-setting capture/validation and positive finite refresh-observation parsing shared by Task 5 and Task 6 |
| `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineHarnessSelfCheckTest.kt` | Device-side selected-day query/trace/sentinel/slice/replacement/native-setting/variable-refresh/thermal/stability controls and negative cases |
| `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineBenchmark.kt` | Four name-ordered Rev4 binding blocks `loadA(15) → replaceA(3) → replaceB(2) → loadB(15)`, preceded once by 5 selected-day warmups and 1 unmeasured 1,000-day replacement traversal; `CompilationMode.Full` throughout |
| `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineRunManifest.kt` | Writes native settings before/after plus exactly 35 per-iteration refresh/thermal/PID and selected-day traversal control records as `p6-run-manifest.json` beside Macrobenchmark outputs |
| `docs/specs/tech-poc/p6-timeline-performance/verification.md` | Evidence record (created Task 0, completed Task 8) |
| `docs/architecture/decisions/0013-timeline-full-materialization-fails-60ms.md` | Accepted failed-assumption and selected-day replacement decision |
| `docs/architecture/decisions/0014-native-display-deadline-jank-deferred-to-m5.md` | Jank-deferral ADR (direction approved 2026-08-26; accepted with Rev5 reapproval) |
| `docs/architecture/decisions/0015-selected-day-timeline-benchmark-result.md` | Rev5 result ADR (drafted Task 8, accepted before completion) |

## Task sequence

Each task uses test-first steps where behavior is introduced and ends with exact commands plus a `verification.md` row. Commit steps are intentionally omitted because this workspace requires a separate user request to commit. Use the process-local JDK at `C:\Users\p3148\tools\jdk\jdk-17.0.20+8` and adb at `C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe`; execution stops if either binding is no longer valid.

Tasks 0–6 are historical Rev1–Rev3 work. Task 6 completed its measurement contract but failed criterion 5, producing accepted ADR 0013; its old full-load/full-scroll steps are retained as evidence and must not be re-executed as the Rev4 workload. Rev4 Tasks 6R4-A/B completed, while 6R4-C froze a valid binding run that failed its then-binding ≤2 % jank gate. After explicit Rev5 approval and ADR 0014 acceptance, execution resumes at Task 6R5-A, then Task 7 and Task 8, all sequentially.

- [x] **Task 0 — repair the lifecycle before implementation (documentation only).**

  - [x] Stop and obtain explicit approval of [spec.md](spec.md) Revision 1. Record its date/context and change only the spec from `draft` to `approved`.
  - [x] Promote this preserved `review-artifact` to lifecycle status `draft`; do not backdate or describe the earlier repair-design approval as spec approval.
  - [x] Stop again and obtain explicit approval of this plan. Record the plan approval date/context, then transition spec and plan together to `in-progress`.
  - [x] Make P6 the unique active slice in `AGENTS.md`, `docs/index.md`, and `docs/roadmap.md`; replace the stale “P6 is the next candidate” position only after the transition.
  - [x] Create `verification.md` with acceptance-criterion rows 1–15 and columns `Command`, `Environment`, `Result`, `Raw evidence`, `Remaining risk`.
  - [x] Run:

    ```powershell
    git diff --check
    $links = Select-String -Path 'docs/specs/tech-poc/p6-timeline-performance/*.md' -Pattern '\]\(([^)#]+)' -AllMatches
    $missing = foreach ($m in $links.Matches) {
        $href = $m.Groups[1].Value
        if ($href -match '^https?://') { continue }
        $target = Join-Path 'docs/specs/tech-poc/p6-timeline-performance' $href
        if (-not (Test-Path $target)) { $target }
    }
    if ($missing) { throw "Broken local links: $($missing -join ', ')" }
    $active = Get-ChildItem docs/specs -Recurse -Filter spec.md | Select-String -Pattern '^\- \*\*Status:\*\* `in-progress`'
    if ($active.Count -ne 1) { throw "Expected one active spec, found $($active.Count)" }
    ```

    Expected: clean diff whitespace, no broken local link, and exactly P6 is `in-progress`.

- [x] **Task 1 — add and validate only the benchmark wiring (completed under Revision 1; display assertion repaired in Task 5).**

  - [x] Before editing build files, capture `:app:releaseRuntimeClasspath` and the seven production-project/edge snapshot into `verification.md`.
  - [x] Add the catalog aliases, `:benchmark` include, `:core:data` benchmark build type, `:app` benchmark build type, and `:benchmark` project. For the app variant, freeze this shape:

    ```kotlin
    create("benchmark") {
        initWith(getByName("release"))
        signingConfig = signingConfigs.getByName("debug")
        isDebuggable = false
        isMinifyEnabled = false
        matchingFallbacks += listOf("release")
    }
    ```

    `:core:data` creates its own `benchmark` build type initialized from `release`; other library dependencies resolve their `release` variants through the app fallback. Add `benchmarkImplementation(testFixtures(project(":core:data")))` only to `:app`. Do not add a `:benchmark → :core:data` project dependency.
  - [x] Configure `:benchmark` with `com.android.test`, `targetProjectPath = ":app"`, `androidx.test.runner.AndroidJUnitRunner`, self-instrumenting mode required by the pinned plugin, and `benchmark-macro-junit4:1.4.1`. Give the test module its own `benchmark` build type initialized from `release` with `matchingFallbacks += listOf("release")`; this selects `:app`'s `benchmark` target variant and makes `connectedBenchmarkAndroidTest`/`assembleBenchmark` concrete tasks. Configure `profileinstaller:1.4.1` only on `:app`'s `benchmarkImplementation`.
  - [x] Add `BenchmarkWiringSmokeTest` for target/test package separation, target profileability/non-debuggability, launch, and display observation. Its historical exact-60 assertion is explicitly superseded and repaired test-first in Task 5.
  - [x] Record the physical device, fingerprint/API/CPU/RAM/battery/thermal facts and the historical Revision 1 display experiment. `verification.md` preserves the temporary forced-60 smoke plus restoration to `peak=120`, `min=null`; that smoke is not Revision 2 binding evidence.

    Historical read-only fact-capture commands remain reusable:

    ```powershell
    $adb = 'C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe'
    & $adb devices -l
    & $adb shell settings get system peak_refresh_rate
    & $adb shell settings get system min_refresh_rate
    & $adb shell getprop ro.build.fingerprint
    & $adb shell getprop ro.build.version.sdk
    & $adb shell cat /proc/cpuinfo
    & $adb shell cat /proc/meminfo
    & $adb shell dumpsys battery
    & $adb shell dumpsys thermalservice
    & $adb shell dumpsys display
    ```

    If there is not exactly one authorized physical Xiaomi 14 Pro/API-36 device, stop.
  - [x] Run dependency and variant checks. The historical smoke result remains evidence for wiring only; Task 5 reruns the repaired smoke under Revision 2:

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    .\gradlew.bat :core:data:compileBenchmarkKotlin :app:assembleBenchmark :benchmark:assembleBenchmark
    .\gradlew.bat :benchmark:dependencies --configuration benchmarkRuntimeClasspath
    .\gradlew.bat :app:dependencies --configuration benchmarkRuntimeClasspath
    .\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath
    .\gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.BenchmarkWiringSmokeTest"
    ```

    Expected: all three variants compile; exactly the two reviewed direct dependency families are new; the complete transitive closure is recorded; release has no Benchmark/ProfileInstaller artifact.
  - [x] Confirm restoration to `peak_refresh_rate=120`, `min_refresh_rate=null`. Revision 2 forbids all refresh-setting writes; Task 5 adds a source scan and read-only shared gate so this cannot regress.

- [x] **Task 2 — implement the frozen fixture, oracle, artifact emitter, and metric math on the JVM.**

  - [x] Write failing `TimelineFixtureGeneratorTest` cases for every frozen constant/rule above: 10,000/1,000/6,000/4,000 and 10/3,000/1,800/1,190 counts, transaction/note slot sets, oldest-edge openings, first/last millisecond, both tie groups, UUID syntax/uniqueness, exact selected labels/titles/bodies/amount signs, and byte-length/LF serialization.
  - [x] Implement only enough pure Kotlin fixture code to pass. Money fields must remain `Long`; no `Float`/`Double`, locale, default ZoneId, random library, JSON, or production model is allowed.
  - [x] Write failing `TimelineOracleTest` cases for descending time + ascending UUID text, 1,000 headers, all 11,000 fields, tie mutation, missing/duplicate/extra mutation, ZoneId grouping, and repeatability. Swapping two adjacent items in the completed flattened artifact must differ from the oracle hash.
  - [x] Implement the oracle independently. It may consume fixture records, but may not call `TimelineTypedReader`, Room, SQL, Compose, or projection canonicalization.
  - [x] Write failing `TimelineMetricMathTest` cases for nearest-rank positions 15/29, pooled-frame p95, all-sample positive-overrun fraction, memory maximum, improvement, equality, exactly 10%, and `>10%`; then implement the formulas.
  - [x] Add `emitP6Oracle` as a host `JavaExec` task over the test runtime. It must rewrite only `core/data/build/p6/` and emit `fixture.sha256`, `oracle.sha256`, and `oracle.txt` atomically.
  - [x] Run:

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    .\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.timeline.TimelineFixtureGeneratorTest" --tests "com.paherdin.record.core.data.timeline.TimelineOracleTest" --tests "com.paherdin.record.core.data.timeline.TimelineMetricMathTest"
    .\gradlew.bat :core:data:emitP6Oracle
    Get-FileHash core/data/build/p6/oracle.txt -Algorithm SHA256
    Get-Content core/data/build/p6/fixture.sha256,core/data/build/p6/oracle.sha256
    ```

    Expected: PASS; file hash equals `oracle.sha256`; record both hashes in `verification.md` without hard-coding generated values into this plan.

- [x] **Task 3 — implement the benchmark-scoped shadow projection through an out-of-process contract test.**

  - [x] Add a failing `TimelineProjectionContractTest` in `benchmark/src/main/`. The runner reads `p6ExpectedOracleHash`, launches the target with `mode=seed-and-verify`, waits for sentinels, and requires row count `10_000`, typed flattened count `11_000`, and actual typed hash equal to the host value. The target may expose actual values only; it must not receive or compute the expected oracle.
  - [x] Implement benchmark-only `TimelineCard`, Room entity/DAO/database, and `TimelineTypedReader`. The DAO contract is exactly one full query:

    ```kotlin
    @Entity(tableName = "timeline_card")
    data class TimelineCardEntity(
        @PrimaryKey val id: String,
        val type: String,
        val occurredAt: Long,
        val title: String,
        val amountMinor: Long?,
        val body: String?,
    )

    @Query("SELECT * FROM timeline_card ORDER BY occurredAt DESC, id COLLATE BINARY ASC")
    suspend fun queryAllOrdered(): List<TimelineCardEntity>
    ```

    The database is a file database containing only this table. Seed/transaction/integrity/hash verification and connection opening complete before a binding trace; the same file is retained unchanged for every measured iteration.
  - [x] Implement only the Activity's `seed-and-verify` mode needed by the failing test. The Activity maps test-fixture records into benchmark Room entities; `:core:data` benchmark code does not depend on `testFixtures`. It calls the typed reader and exposes canonical actual count/hash sentinels without exposing a DAO/entity/path to UI code.
  - [x] Run the contract from the test-tool module, not `:core:data:connectedDebugAndroidTest` (the debug variant cannot see `src/benchmark`):

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    $p6OracleHash = (Get-Content 'core/data/build/p6/oracle.sha256' -Raw).Trim()
    .\gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.TimelineProjectionContractTest" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedOracleHash=$p6OracleHash"
    ```

    Expected: PASS; a single benchmark file has 10,000 rows and its typed 11,000-item hash equals the independent host oracle.

- [x] **Task 4 — implement the render surface and exhaustive rendered correctness.**

  - [x] Write a failing `TimelineRenderTraversalTest` that launches `mode=render-correctness`, waits for `11_000` actual rendered payloads, and compares the actual rendered hash to `p6ExpectedOracleHash`. Add a negative test that launches `mode=render-wrong-order`; the same equality assertion must throw `AssertionError`.
  - [x] Implement the benchmark-only `LazyColumn` with stable item keys/content types and only the frozen fields. Enable `testTagsAsResourceId`; publish `TimelineTop:<first-id>`, `TimelineItem:<id>`, `TimelineBottom:<last-id>`, ready/count/hash, and refresh sentinels. Task 5 splits the historical single refresh sentinel into before/after values.
  - [x] Implement `TimelineRenderedPayloadRecorder` only for correctness mode. It sequentially calls `scrollToItem(index)` for indices `0..10_999`, waits for the indexed item to compose, records the exact semantics payload produced by that item, and then exposes count/hash. It must never reuse the typed-list hash as the rendered result.
  - [x] Add the load trace handshake: after the complete immutable 11,000-item state is ready, the first root draw that proves the first header/card sentinels ends the unique async `TimelineQueryToRender` slice. Start the slice immediately before dispatching the one DAO query. Missing/duplicate/end-before-correct-draw is an error.
  - [x] Run:

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    $p6OracleHash = (Get-Content 'core/data/build/p6/oracle.sha256' -Raw).Trim()
    .\gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.TimelineRenderTraversalTest" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedOracleHash=$p6OracleHash"
    ```

    Expected: normal exhaustive traversal PASS; the controlled wrong-order path is observed to fail the shared equality assertion; no timed metric is used as correctness evidence.

- [x] **Task 5 — repair the harness around the ADR-0012 native display contract and make it fail closed (completed under Rev2/Rev3; historical).**

  **Files:**

  - Create `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineDisplayBinding.kt`.
  - Modify `benchmark/src/main/kotlin/com/paherdin/record/benchmark/BenchmarkWiringSmokeTest.kt`.
  - Modify `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineHarnessSelfCheckTest.kt`.
  - Modify `app/src/benchmark/kotlin/com/paherdin/record/benchmark/TimelineBenchmarkActivity.kt`.
  - Modify `app/src/benchmark/kotlin/com/paherdin/record/benchmark/TimelineRenderSurface.kt`.
  - Modify `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineBenchmarkReportParser.kt`.
  - Modify `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineBenchmarkReportValidatorTest.kt`.

  - [x] **Step 1: extend the host manifest fixtures first and prove the current parser fails.** Replace the fixture manifest shape with the exact Revision 2 contract: suite-level `displaySettingsBefore` and `displaySettingsAfter`, plus 35 ordered iteration objects split 15/3/2/15. Every iteration has `block`, `iteration`, positive finite `refreshBeforeHz`, positive finite `refreshAfterHz`, `thermalBefore`, `thermalAfter`, `pidBefore`, and `pidAfter`; scroll iterations additionally have an integer `gestureCount`, load iterations use JSON `null`. Perfetto association remains owned by each benchmark report's ordered `profilerOutputs` list and the copied trace files, not duplicated in the control manifest.

    Add these focused tests before changing the parser:

    ```kotlin
    @Test fun nativeSettingsAndThirtyFiveRefreshPairsPass()
    @Test fun variableRefreshUnderNativeSettingsPasses()
    @Test fun changedPeakSettingFails()
    @Test fun changedMinSettingFails()
    @Test fun missingRefreshBeforeFails()
    @Test fun missingRefreshAfterFails()
    @Test fun zeroNanOrInfiniteRefreshFails()
    @Test fun missingOrDuplicateIterationRecordFails()
    @Test fun unequalScrollGestureCountsFail()
    @Test fun missingProfilerOutputFails()
    @Test fun profilerOutputWithoutCopiedTraceFails()
    ```

    `variableRefreshUnderNativeSettingsPasses` must contain at least one `120.0 → 60.0` pair and one `60.0 → 120.0` pair while both suite setting snapshots remain `peak="120"`, `min="null"`; this proves the validator does not smuggle in a fixed-120 active-mode gate. Keep the existing malformed report, trace-count, sample-count, RssAnon, formula, and exact-2%-jank tests.

    Run:

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    .\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.timeline.TimelineBenchmarkReportValidatorTest"
    ```

    Expected before parser changes: FAIL only in the new manifest-contract tests because the current parser reads only `gestureCounts`.

  - [x] **Step 2: implement the strict host manifest parser and re-run green.** Add typed host-only values `ParsedDisplaySettings`, `ParsedIterationControl`, and `ParsedRunManifest`. Require exact settings strings `120` / `null`, exact block/index pairs, exactly 35 unique iteration records, finite positive refresh observations, `thermalBefore/After <= 1`, identical nonblank PIDs per iteration, and five equal positive gesture counts. Separately require each report's ordered `profilerOutputs` count to equal its block iteration count, require 35 distinct referenced filenames overall, and require every referenced filename to resolve to one retained trace under the supplied root. Do not reject a refresh change by itself. Keep validation pure: read only the supplied report root and return problems without writing.

    Run the command from Step 1 again. Expected: PASS, including the variable-refresh case and all existing report negatives.

  - [x] **Step 3: write failing device tests for a focused read-only display binding.** Create `TimelineDisplayBinding.kt` with this public-to-test-module contract, then write tests against it before implementing bodies:

    ```kotlin
    internal data class TimelineDisplaySettings(val peak: String, val min: String)

    internal object TimelineDisplayBinding {
        const val EXPECTED_PEAK = "120"
        const val EXPECTED_MIN = "null"
        fun capture(shell: (String) -> String): TimelineDisplaySettings
        fun assertNative(settings: TimelineDisplaySettings)
        fun requirePositiveRefresh(raw: String): Double
    }
    ```

    Device self-checks must prove: native `120/null` passes; peak `60`, missing peak, and non-null minimum each fail; refresh strings `60.0`, `90.0`, and `120.0` all pass; blank, `0`, negative, `NaN`, and `Infinity` fail. Remove `assertRefreshIs60`, `refreshSentinelIs60WhenForced`, `naturalDisplayModeRefreshFailsSharedGate`, their polling sleeps, and the refresh-setting restore helper. Keep thermal, PID, exit-info, trace, full-query, count/hash, top/bottom, 1200-gesture, and no-gap controls.

  - [x] **Step 4: implement the read-only display binding and repair the wiring smoke.** `capture` may execute only:

    ```kotlin
    TimelineDisplaySettings(
        peak = shell("settings get system peak_refresh_rate").trim(),
        min = shell("settings get system min_refresh_rate").trim(),
    )
    ```

    `assertNative` requires the exact constants. `requirePositiveRefresh` uses `toDoubleOrNull()`, `isFinite()`, and `> 0.0`; it must not require 120. `BenchmarkWiringSmokeTest` retains package separation, launch, profileable, and non-debuggable assertions, but replaces its `60.00` equality with `requirePositiveRefresh(display.refreshRate.toString())`.

  - [x] **Step 5: expose distinct Activity-visible before/after refresh observations without per-frame preference writes.** Replace `SENTINEL_REFRESH_HZ` with `SENTINEL_REFRESH_BEFORE_HZ` and `SENTINEL_REFRESH_AFTER_HZ`. For load/correctness modes, write `refreshBeforeHz` once immediately before dispatching the full query and `refreshAfterHz` at the first correct draw. For `scroll-ready`, write `refreshBeforeHz` only after the complete state/top sentinel is ready and immediately before publishing `ready=true`; write `refreshAfterHz` immediately before `bottomReached=true` when the final card is composed. Clear both on every fresh render launch. Do not poll or write SharedPreferences every frame because that would perturb the measured scroll.

  - [x] **Step 6: retain the existing manual controls and verify all shared gates.** Keep five manual cold-process warmups and one full unmeasured scroll; the scroll returns the exact gesture count and uses the frozen coordinates, 10 steps, immediate repeats, 1200 cap, 3-button-navigation wrapper, PID continuity, and exit-info delta. Add a positive device test that captures the actual native settings before and after the full scroll and proves they remain `120/null` without modifying them.

  - [x] **Step 7: prove there are no refresh-setting writes and run the complete Task 5 gate.**

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    $refreshWrites = Get-ChildItem benchmark/src/main,app/src/benchmark -Recurse -File |
        Select-String -Pattern 'settings\s+(put|delete)\s+system\s+(peak_refresh_rate|min_refresh_rate)'
    if ($refreshWrites) { throw "refresh-setting mutation remains: $refreshWrites" }
    .\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.timeline.TimelineBenchmarkReportValidatorTest" --tests "com.paherdin.record.core.data.timeline.TimelineMetricMathTest"
    $p6OracleHash = (Get-Content 'core/data/build/p6/oracle.sha256' -Raw).Trim()
    .\gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.BenchmarkWiringSmokeTest,com.paherdin.record.benchmark.TimelineHarnessSelfCheckTest" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedOracleHash=$p6OracleHash"
    ```

    Expected: source scan empty; host tests PASS; wiring smoke and every positive/negative self-check PASS; device settings are `peak=120`, `min=null` before and after; observed refresh values are recorded but not equality-gated; no benchmark runner error is converted to a warning. Record the exact frozen manual gesture count in `verification.md`.

- [x] **Task 6 — run the full-materialization binding benchmark and validate copied raw evidence on the host (completed with criterion 5 FAIL; historical evidence for ADR 0013).**

  **Files:**

  - Create `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineRunManifest.kt`.
  - Create `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineBenchmark.kt`.
  - Modify `core/data/build.gradle.kts` for `validateP6BenchmarkReports` only if the task is not already present.
  - Modify `docs/specs/tech-poc/p6-timeline-performance/verification.md` only after raw outputs exist.

  - [x] **Step 1: write the run-manifest model and serialization tests before the binding class.** `TimelineRunManifest` owns atomic JSON publication under the resolved AndroidX 1.4.1 `Outputs.outputDirectory`. Its exact model is:

    ```kotlin
    internal data class TimelineIterationControl(
        val block: String,
        val iteration: Int,
        val refreshBeforeHz: Double,
        val refreshAfterHz: Double,
        val thermalBefore: Int,
        val thermalAfter: Int,
        val pidBefore: String,
        val pidAfter: String,
        val gestureCount: Int?,
    )

    internal class TimelineRunManifest(
        val displaySettingsBefore: TimelineDisplaySettings,
    ) {
        fun add(control: TimelineIterationControl)
        fun finish(displaySettingsAfter: TimelineDisplaySettings)
    }
    ```

    Add device tests proving ordered append, duplicate `(block, iteration)` rejection, non-finite refresh rejection, PID change rejection, scroll/load gesture nullability, atomic replacement, and the final exact 35-record JSON shape. Serialization uses `org.json` already supplied by Android; do not add a dependency. Perfetto filenames are validated from Macrobenchmark reports on the host and are intentionally absent here.

  - [x] **Step 2: implement suite lifecycle controls without display mutation.** Under `@FixMethodOrder(MethodSorters.NAME_ASCENDING)`, capture and assert `TimelineDisplayBinding.capture(...) == 120/null` before any warmup. Capture package exit-info, PID state, airplane setting, battery, and thermal; enable airplane mode; foreground-control the target; and perform the existing five-minute idle with probes at most 60 seconds apart. A class-level `finally`/`@AfterClass` path restores only the airplane setting because P6 changed it; it reads and asserts display settings again but never writes them. It also diffs package exit-info and finalizes the manifest. Any failure in restoration, display-setting equality, stability, or manifest finalization keeps the suite incomplete.

  - [x] **Step 3: implement the four frozen binding blocks.** The names and counts remain exact:

    ```text
    binding01LoadA   15 cold-process load iterations
    binding02ScrollA 3 prepared full-scroll iterations
    binding03ScrollB 2 prepared full-scroll iterations
    binding04LoadB   15 cold-process load iterations
    ```

    The two load blocks therefore produce exactly 30 valid cold-process load samples; the two scroll blocks produce exactly 5 valid full-scroll iterations. Immediately before `binding01LoadA`, execute exactly five manual cold-process load warmups. Immediately before `binding02ScrollA`, execute exactly one manual top-to-bottom scroll as a non-gating control; it must reach the bottom sentinel and its gesture count is recorded diagnostically (Revision 3: never pinned as an expectation). These six controls create no Macrobenchmark samples.

  - [x] **Step 4: implement load measurement and its control record.** Load blocks use `CompilationMode.Full`, `StartupMode.COLD`, `TraceSectionMetric("TimelineQueryToRender")`, and `MemoryUsageMetric(Mode.Max, [RssAnon])`. `startActivityAndWait()` is inside the measured block, while the Activity starts the named trace only immediately before its one full query and ends it on the first correct draw. For each iteration, read distinct `refreshBeforeHz` and `refreshAfterHz` sentinels, validate both with `requirePositiveRefresh`, capture thermal/PID before and after, and append one manifest record with `gestureCount=null`. Activity launch, DB open, and sentinel reads must not be added to the named query trace. Macrobenchmark's report `profilerOutputs` remains the source of truth for the iteration traces.

  - [x] **Step 5: implement scroll measurement and its control record.** Scroll blocks use `CompilationMode.Full`, `startupMode=null`, `FrameTimingMetric()`, and `MemoryUsageMetric(Mode.Max, [RssAnon])`; `StartupMode.COLD` remains forbidden because it would kill the prepared process. `setupBlock` kills the old process, launches `scroll-ready`, waits for the full 11,000-item ready/top state and idle, validates native settings plus thermal `NONE`/`LIGHT`, and captures PID and `refreshBeforeHz`. The measured block contains only immediate repeated swipes until the exact bottom sentinel. After the bottom is composed, read `refreshAfterHz`, recheck thermal/PID, require a positive gesture count below the 1200 cap (Revision 3: recorded as-is; count equality is not a gate), and append the manifest control. No active-Hz equality assertion is allowed.

  - [x] **Step 6: fail the whole suite on any invalid iteration.** Never drop, replace, or retry one iteration inside the binding suite. A missing before/after refresh sentinel, setting drift, thermal `MODERATE+`, PID delta, bottom failure, runner error, ANR/OOM/crash/relaunch/timeout, or manifest write failure aborts the suite. Missing/mismatched profiler output fails host validation. Refresh changing between two positive finite observations is recorded and does not fail by itself.

  - [x] **Step 7: run the binding class with the host oracle.** Before execution, use read-only commands to prove the native binding; do not run any `settings put/delete` refresh command:

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    $adb = 'C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe'
    $p6OracleHash = (Get-Content 'core/data/build/p6/oracle.sha256' -Raw).Trim()
    if ((& $adb shell settings get system peak_refresh_rate | Out-String).Trim() -ne '120') { throw 'unexpected native peak_refresh_rate' }
    if ((& $adb shell settings get system min_refresh_rate | Out-String).Trim() -ne 'null') { throw 'unexpected native min_refresh_rate' }
    & $adb shell dumpsys activity exit-info com.paherdin.record
    .\gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.TimelineBenchmark" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedOracleHash=$p6OracleHash"
    & $adb shell dumpsys activity exit-info com.paherdin.record
    if ((& $adb shell settings get system peak_refresh_rate | Out-String).Trim() -ne '120') { throw 'peak_refresh_rate changed during suite' }
    if ((& $adb shell settings get system min_refresh_rate | Out-String).Trim() -ne 'null') { throw 'min_refresh_rate changed during suite' }
    ```

  - [x] **Step 8: validate only copied host evidence.**

    ```powershell
    $p6ReportDir = (Resolve-Path 'benchmark/build/outputs/connected_android_test_additional_output').Path
    .\gradlew.bat "-Dp6ReportDir=$p6ReportDir" :core:data:validateP6BenchmarkReports
    ```

    Expected: exact four blocks, 30 positive unique trace samples, 5 valid full-scroll frame arrays with five positive sub-cap gesture counts (Revision 3: host reports min/max/range; equality is not required), 35 RssAnon maxima, 35 distinct before/after refresh pairs, exact native settings before/after, and 35 Perfetto traces. Host recomputation matches query p95 ≤ 60 ms, frame-CPU p95 ≤ 16.67 ms, positive-overrun jank ≤ 2%, and memory ≤ 196,608 KiB, with no stability failure. Record raw paths, every observed refresh pair, the five gesture counts with min/max/range, and the derived values in `verification.md`.

- [x] **Task 6R4-A — derive selected-day oracle slices and replace the full-read data boundary test-first.**

  **Files:**

  - Modify `core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/timeline/TimelineOracle.kt`.
  - Modify `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineOracleTest.kt`.
  - Modify `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineArtifactEmitter.kt`.
  - Modify `core/data/src/benchmark/kotlin/com/paherdin/record/core/data/timeline/TimelineShadowDb.kt`.
  - Modify `core/data/src/benchmark/kotlin/com/paherdin/record/core/data/timeline/TimelineTypedReader.kt`.
  - Modify `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineProjectionContractTest.kt`.
  - Modify `app/src/benchmark/kotlin/com/paherdin/record/benchmark/TimelineBenchmarkActivity.kt` only for the typed contract modes/sentinels needed by this task.
  - Modify `app/src/benchmark/kotlin/com/paherdin/record/benchmark/TimelineRenderSurface.kt` only for the mechanical compile bridge from removed `FULL_QUERY_SQL`/`readProjection()` calls to the fixed `2025-09-26` selected-day SQL/bounds/read call. Task 6R4-B still owns generation-aware first/last/empty rendering, within-day wrong-order behavior, and replacement traversal.

  - [x] **Step 1: write the selected-day oracle tests before adding the slice API.** Add these exact tests to `TimelineOracleTest`:

    ```kotlin
    @Test fun firstFixtureDaySliceUsesHalfOpenBoundsAndMatchesCompleteOracle()
    @Test fun lastFixtureDaySliceUsesHalfOpenBoundsAndMatchesCompleteOracle()
    @Test fun emptyDaySliceHasNoHeaderOrCard()
    @Test fun selectedDaySlicePreservesTimestampTieIdOrdering()
    @Test fun selectedDaySliceRejectsMutableDefaultZoneDependence()
    @Test fun emittedDayOracleManifestIsDeterministic()
    ```

    Freeze this pure API:

    ```kotlin
    data class TimelineDayOracle(
        val date: LocalDate,
        val dayStart: Long,
        val nextDayStart: Long,
        val items: List<TimelineOracleItem>,
    )

    fun TimelineOracle.selectedDay(date: LocalDate): TimelineDayOracle
    ```

    Assert exact cases: `2023-01-01` → `[1672502400000, 1672588800000)`, 11 items; `2025-09-26` → `[1758816000000, 1758902400000)`, 11 items; `2022-12-31` → `[1672416000000, 1672502400000)`, zero items. For both non-empty days, assert slot 0 is included, slot 9 at `nextDayStart - 1` is included, the next day's slot 0 is excluded, and both equal-time pairs use ascending UUID text. Run the focused JVM test and expect failure only because `selectedDay` and the new artifacts do not exist.

  - [x] **Step 2: implement the independent slice and emitter, then turn the JVM tests green.** Filter fixture records by the half-open epoch bounds, sort with the existing explicit time/UUID comparator, add exactly one header only when cards exist, and reuse only canonical encoding—not the benchmark reader. Preserve the complete oracle artifacts unchanged. Extend `emitP6Oracle` to write atomically:

    ```text
    core/data/build/p6/day-oracles/2022-12-31.txt
    core/data/build/p6/day-oracles/2022-12-31.sha256
    core/data/build/p6/day-oracles/2023-01-01.txt
    core/data/build/p6/day-oracles/2023-01-01.sha256
    core/data/build/p6/day-oracles/2025-09-26.txt
    core/data/build/p6/day-oracles/2025-09-26.sha256
    core/data/build/p6/day-oracles/manifest.tsv
    ```

    `manifest.tsv` has the exact header `date\tdayStart\tnextDayStart\titemCount\tsha256` and rows ordered `2022-12-31`, `2023-01-01`, `2025-09-26`. Do not hard-code generated hashes into source.

  - [x] **Step 3: write failing out-of-process selected-day data-contract tests.** Replace the full-result assertion in `TimelineProjectionContractTest` with:

    ```kotlin
    @Test fun databaseRetainsTenThousandRowsButFirstDayReaderReturnsOnlyItsSlice()
    @Test fun lastDayReaderReturnsOnlyItsSlice()
    @Test fun emptyDayReaderReturnsEmptyState()
    @Test fun selectedDayQueryUsesHalfOpenBoundsAndOccurredAtIndex()
    ```

    The host passes `p6ExpectedFirstDayHash`, `p6ExpectedLastDayHash`, and `p6ExpectedEmptyDayHash`. Each launch passes only the requested date/bounds to the target; the target exposes actual row count, selected date, bounds, item count, hash, and query-plan detail. Assert 10,000 rows remain on disk; counts are 11/11/0; hashes match the host slices; plan detail contains an indexed `occurredAt` range search. Add a negative launch with `dayStart == nextDayStart` and require a deterministic error. Run the device class and expect failure at the old full-read implementation.

  - [x] **Step 4: implement the benchmark-only indexed bounded reader.** Freeze the Room surface as:

    ```kotlin
    @Entity(
        tableName = "timeline_card",
        indices = [Index(value = ["occurredAt"])],
    )
    data class TimelineCardEntity(/* unchanged fields */)

    companion object {
        const val SELECTED_DAY_QUERY_SQL =
            "SELECT * FROM timeline_card " +
                "WHERE occurredAt >= :dayStart AND occurredAt < :nextDayStart " +
                "ORDER BY occurredAt DESC, id COLLATE BINARY ASC"
    }

    @Query(SELECTED_DAY_QUERY_SQL)
    suspend fun querySelectedDay(dayStart: Long, nextDayStart: Long): List<TimelineCardEntity>

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_timeline_card_occurredAt " +
                    "ON timeline_card (occurredAt)",
            )
        }
    }
    ```

    Set only `TimelineShadowDb.version = 2`, register `MIGRATION_1_2` on every shadow builder, and add a migration contract that creates a version-1 shadow DB with the complete 10,000-card fixture, records its row count plus first/last selected-day hashes, migrates, and proves the same 10,000 rows and selected-day hashes plus indexed plan use. Never use `fallbackToDestructiveMigration`, delete the main shadow DB to bypass migration, or touch `RecordDatabase`. The migration contract's UUID-named app-private temporary DB and its SQLite sidecars must be removed in `finally` only after all handles close, under the separately approved fixed-prefix/UUID/main-and-production-name rejection guard. Remove `queryAllOrdered()`/`FULL_QUERY_SQL` from the render/load facade. Keep `rowCount()` and expose query-plan details only through a typed benchmark diagnostic. `ShadowProjectionHandle.readSelectedDay(dayStart, nextDayStart)` validates `dayStart < nextDayStart`; `TimelineTypedReader.readSelectedDay(...)` brackets `P6:SelectedDayQuery` and `P6:SelectedDayGrouping`, rejects any row outside the half-open range or any second local date, returns empty for zero rows, and otherwise returns one header plus ordered cards. UI code still receives no DAO, Entity, database, or path.

  - [x] **Step 5: run the complete Task 6R4-A gate and record Rev4-pending evidence.**

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    .\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.timeline.TimelineFixtureGeneratorTest" --tests "com.paherdin.record.core.data.timeline.TimelineOracleTest" --tests "com.paherdin.record.core.data.timeline.TimelineMetricMathTest"
    .\gradlew.bat :core:data:emitP6Oracle
    $dayManifest = Import-Csv 'core/data/build/p6/day-oracles/manifest.tsv' -Delimiter "`t"
    $firstHash = ($dayManifest | Where-Object date -eq '2023-01-01').sha256
    $lastHash = ($dayManifest | Where-Object date -eq '2025-09-26').sha256
    $emptyHash = ($dayManifest | Where-Object date -eq '2022-12-31').sha256
    .\gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.TimelineProjectionContractTest" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedFirstDayHash=$firstHash" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedLastDayHash=$lastHash" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedEmptyDayHash=$emptyHash"
    ```

    Expected: JVM and device tests PASS; complete fixture/full-oracle hashes stay byte-identical to the frozen Rev1–Rev3 values; slice files hash to their `.sha256`; DB row count is 10,000; selected-day counts/hashes are 11/11/0; SQL/index-plan assertions pass; source inspection finds no full-read DAO method.

- [x] **Task 6R4-B — replace cross-day rendering/scroll controls with one-day-only correctness and replacement traversal.**

  **Files:**

  - Modify `app/src/benchmark/AndroidManifest.xml` only if the prepared Activity needs `singleTop`; the declaration remains benchmark-only.
  - Modify `app/src/benchmark/kotlin/com/paherdin/record/benchmark/TimelineBenchmarkActivity.kt`.
  - Modify `app/src/benchmark/kotlin/com/paherdin/record/benchmark/TimelineRenderSurface.kt`.
  - Modify `app/src/benchmark/kotlin/com/paherdin/record/benchmark/TimelineRenderedPayloadRecorder.kt`.
  - Modify `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineRenderTraversalTest.kt`.
  - Modify `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineHarnessSelfCheckTest.kt`.
  - Modify `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineRunManifest.kt`.
  - Modify `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineRunManifestTest.kt`.
  - Modify `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineBenchmark.kt` only for the mechanical constructor/schema bridge required after `gestureCount` is removed from `TimelineIterationControl`; Task 6R4-C still owns the four binding block names, load/replace measurement behavior, suite controls, and binding execution.
  - Modify `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineBenchmarkReportParser.kt`.
  - Modify `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineBenchmarkReportValidatorTest.kt`.

  - [x] **Step 1: write failing rendered-slice tests.** Replace the exhaustive 11,000-item traversal cases with exact selected-day cases:

    ```kotlin
    @Test fun renderedFirstDayMatchesHostSlice()
    @Test fun renderedLastDayMatchesHostSlice()
    @Test fun renderedEmptyDayHasNoHeaderOrCard()
    @Test fun withinDayTieSwapFailsSharedEqualityAssertion()
    @Test fun publishedReadyStateNeverContainsAnotherDay()
    ```

    The recorder hashes only payloads actually composed for the current selected-day state. The wrong-order mode swaps the two slot-0 equal-time cards inside `2025-09-26`, not cards from different dates. Run the class and expect the old complete-list implementation to fail these cases.

  - [x] **Step 2: implement selected-day render state and generation-matched draw closure.** Replace full-list state with:

    ```kotlin
    internal data class SelectedDayRenderState(
        val generation: Long,
        val date: LocalDate,
        val items: List<TimelineProjectionItem>,
    )
    ```

    A non-empty state must have one matching `DayHeader` and ten in-range cards; an empty state renders the declared empty surface and no header/card. `TimelineQueryToRenderGate` closes only when the first root draw contains the current generation's expected header/first card. Bounds/date/top/bottom/count/hash sentinels are buffered in memory until after gate closure. Delete the code path that builds or renders a cross-day 11,000-item state. Do not add prefetch, cache, Paging, or keyset code.

  - [x] **Step 3: write failing traversal-controller and manifest/parser tests.** Freeze a pure summary:

    ```kotlin
    internal data class SelectedDayTraversalSummary(
        val selectedDayDrawCount: Int,
        val replacementCount: Int,
        val maxPublishedItemCount: Int,
        val prefetchQueryCount: Int,
        val firstDate: String,
        val lastDate: String,
    )
    ```

    Add device negatives for duplicate/skipped date, overlapping query, next query before prior correct draw, mixed-day publication, item count > 11, nonzero prefetch count, wrong first/last date, and incomplete traversal. Replace run-manifest `gestureCount` with nullable traversal fields: load iterations require all null; replacement iterations require exactly `1000/999/11/0/2025-09-26/2023-01-01`. Add one suite-level unmeasured traversal summary with the same exact values. Parser/validator tests must reject every mismatch and no longer parse or gate gesture counts.

  - [x] **Step 4: implement the prepared replacement mode.** `mode=replace-ready` opens/seeds the DB outside measurement, publishes no day state, stamps a fresh ready/run ID, and waits. A shell `am start --activity-single-top` command with the matching run ID triggers exactly one traversal. The Activity visits all dates by `LocalDate.minusDays(1)`, using `Asia/Shanghai` bounds and `readSelectedDay`; it awaits the generation-matched first correct draw before the next date. Track counters only—never accumulate prior lists or 1,000 hashes in memory. After the last correct draw, buffer/finalize the exact summary and completion sentinel; post-completion file/prefs publication occurs outside the measured frame boundary. A second start for the same run ID fails closed.

  - [x] **Step 5: run selected-day render and harness self-checks.**

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    $dayManifest = Import-Csv 'core/data/build/p6/day-oracles/manifest.tsv' -Delimiter "`t"
    $firstHash = ($dayManifest | Where-Object date -eq '2023-01-01').sha256
    $lastHash = ($dayManifest | Where-Object date -eq '2025-09-26').sha256
    $emptyHash = ($dayManifest | Where-Object date -eq '2022-12-31').sha256
    .\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.timeline.TimelineBenchmarkReportValidatorTest" --tests "com.paherdin.record.core.data.timeline.TimelineMetricMathTest"
    .\gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.TimelineRenderTraversalTest,com.paherdin.record.benchmark.BenchmarkWiringSmokeTest,com.paherdin.record.benchmark.TimelineHarnessSelfCheckTest" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedFirstDayHash=$firstHash" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedLastDayHash=$lastHash" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedEmptyDayHash=$emptyHash"
    ```

    Expected: selected-day render cases and shared wrong-order negative PASS; the complete unmeasured replacement traversal reports the exact frozen summary; every overlap/mixing/prefetch/count/date negative fails for its intended reason; native display settings remain `120/null`; no refresh-setting write or benchmark error suppression exists.

- [x] **Task 6R4-C — run the Rev4 binding suite and freeze its failed jank result.**

  **Files:**

  - Modify `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineBenchmark.kt`.
  - Modify `benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineRunManifest.kt` only if Task 6R4-B left binding integration incomplete.
  - Modify `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineBenchmarkReportParser.kt` and `TimelineBenchmarkReportValidatorTest.kt` only for the final copied-report contract.
  - Modify `docs/specs/tech-poc/p6-timeline-performance/verification.md` only after raw outputs are frozen.

  - [x] **Step 1: freeze the four binding blocks and control order.** Under `@FixMethodOrder(MethodSorters.NAME_ASCENDING)`, use exactly:

    ```text
    binding01LoadA       15 cold-process selected-day load iterations
    binding02ReplaceA     3 prepared 1,000-day replacement iterations
    binding03ReplaceB     2 prepared 1,000-day replacement iterations
    binding04LoadB       15 cold-process selected-day load iterations
    ```

    Immediately before `binding01LoadA`, run exactly five manual cold-process loads of `2025-09-26`. Immediately before `binding02ReplaceA`, run exactly one unmeasured complete replacement traversal and require the frozen summary. Controls create no Macrobenchmark samples.

  - [x] **Step 2: implement the selected-day load blocks.** Keep `CompilationMode.Full`, `StartupMode.COLD`, `TraceSectionMetric("TimelineQueryToRender")`, and `MemoryUsageMetric(Mode.Max, [RssAnon])`. Each Activity launch receives date `2025-09-26` and its exact bounds. The named trace contains only the single bounded query, ten-card grouping, one-day state publication, and first correct draw. Require item count 11, matching day/hash, query-plan sentinel, prefetch count zero, one trace section, PID continuity, thermal `NONE`/`LIGHT`, and positive refresh observations.

  - [x] **Step 3: implement the prepared replacement blocks.** Keep `CompilationMode.Full`, `startupMode=null`, `FrameTimingMetric()`, and `MemoryUsageMetric(Mode.Max, [RssAnon])`. `setupBlock` force-stops the old process, launches `replace-ready`, waits for seeded/open/no-day ready state, and captures PID/settings/thermal/refresh. The measured block sends the one-shot start command, waits for completion, and requires the exact traversal summary. It must not send swipe gestures, concatenate dates, run prefetch, or include process/database setup. Any invalid summary or process/thermal/settings/stability change aborts the entire suite.

  - [x] **Step 4: update the host validator to the exact Rev4 report contract.** Require four reports with 15/3/2/15 runs, 30 positive unique `TimelineQueryToRender` samples at ranks 15/29, five non-empty frame arrays, 35 RssAnon maxima, 35 distinct positive refresh pairs, 35 copied Perfetto traces, exact native settings before/after, one exact unmeasured summary, and five exact measured summaries. Recompute query p50/p95, pooled frame p95, positive-overrun jank basis points, memory maximum, and the one-sided regression formula. Under the Rev4 contract, jank above 200 bp fails validation. Reject any Rev3 `gestureCount` field as stale schema rather than silently ignoring it.

  - [x] **Step 5: run the binding suite with read-only device preflight.**

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    $adb = 'C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe'
    $dayManifest = Import-Csv 'core/data/build/p6/day-oracles/manifest.tsv' -Delimiter "`t"
    $lastHash = ($dayManifest | Where-Object date -eq '2025-09-26').sha256
    if ((& $adb shell settings get system peak_refresh_rate | Out-String).Trim() -ne '120') { throw 'unexpected native peak_refresh_rate' }
    if ((& $adb shell settings get system min_refresh_rate | Out-String).Trim() -ne 'null') { throw 'unexpected native min_refresh_rate' }
    & $adb shell dumpsys activity exit-info com.paherdin.record
    .\gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.TimelineBenchmark" "-Pandroid.testInstrumentationRunnerArguments.p6ExpectedLastDayHash=$lastHash"
    & $adb shell dumpsys activity exit-info com.paherdin.record
    if ((& $adb shell settings get system peak_refresh_rate | Out-String).Trim() -ne '120') { throw 'peak_refresh_rate changed during suite' }
    if ((& $adb shell settings get system min_refresh_rate | Out-String).Trim() -ne 'null') { throw 'min_refresh_rate changed during suite' }
    ```

  - [x] **Step 6: freeze outputs before any later Gradle command, then validate only the frozen copy.** Copy the one benchmark-data JSON, finalized manifest, four message files, and all 35 Perfetto traces into a new timestamped directory without deleting or overwriting the Rev3 `p6final` evidence. Write one SHA-256 manifest and verify exact counts before invoking Gradle again:

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    $sourceRoot = (Resolve-Path 'benchmark/build/outputs/connected_android_test_additional_output').Path
    $benchmarkJson = @(Get-ChildItem $sourceRoot -Recurse -File -Filter '*benchmarkData.json')
    $runManifest = @(Get-ChildItem $sourceRoot -Recurse -File -Filter 'p6-run-manifest.json')
    $messageFiles = @(Get-ChildItem $sourceRoot -Recurse -File -Filter 'additionaltestoutput.benchmark.message_*.txt')
    $traceFiles = @(Get-ChildItem $sourceRoot -Recurse -File -Filter '*.perfetto-trace')
    if ($benchmarkJson.Count -ne 1 -or $runManifest.Count -ne 1 -or $messageFiles.Count -ne 4 -or $traceFiles.Count -ne 35) {
        throw "unexpected P6 Rev4 evidence counts: json=$($benchmarkJson.Count) manifest=$($runManifest.Count) messages=$($messageFiles.Count) traces=$($traceFiles.Count)"
    }
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $p6FrozenDir = New-Item -ItemType Directory -Path (Join-Path 'benchmark/build/p6rev4' "run-$stamp")
    @($benchmarkJson + $runManifest + $messageFiles + $traceFiles) | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $p6FrozenDir.FullName
    }
    Get-ChildItem $p6FrozenDir -File | Get-FileHash -Algorithm SHA256 |
        Sort-Object Path | ForEach-Object { "$($_.Hash.ToLowerInvariant())  $([IO.Path]::GetFileName($_.Path))" } |
        Set-Content -Encoding utf8NoBOM (Join-Path $p6FrozenDir 'EVIDENCE.sha256')
    .\gradlew.bat "-Dp6ReportDir=$($p6FrozenDir.FullName)" :core:data:validateP6BenchmarkReports
    ```

    Expected under Rev4: query p95 ≤ 60, frame p95 ≤ 16.67, jank ≤ 2 %, memory ≤ 196,608 KiB, exact traversal controls, no stability failure, and a host PASS. Actual: query p95 39.26 ms, frame p95 14.99 ms, and memory 122,680 KiB passed; jank 3,796 bp (37.96 %) failed. The frozen directory is `benchmark/p6-frozen/p6rev4/run-20260826T155112Z` (migrated out of `build/` on 2026-08-27 to survive `clean`). The failure remains historical Rev4 evidence and triggered ADR 0014 plus Rev5.

- [ ] **Task 6R5-A — make replacement jank diagnostic in the host validator before isolation work resumes.**

  **Files:**

  - Modify `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineBenchmarkReportParser.kt`.
  - Modify `core/data/src/test/kotlin/com/paherdin/record/core/data/timeline/TimelineBenchmarkReportValidatorTest.kt`.
  - Modify `docs/specs/tech-poc/p6-timeline-performance/verification.md` after the focused tests and frozen-report validation pass.

  - [ ] **Step 1: write the Rev5 failing validator test.** Replace `jankBudgetViolationFails` with a case whose otherwise-valid replacement reports exceed 200 bp and assert both that the exact `jankBp` is retained and that jank adds no validation problem. Keep the formula, zero-overrun, negative-overrun, missing-sample, malformed-report, frame-budget, memory-budget, and query-budget tests unchanged. Run the focused validator test and record the expected RED caused only by the existing Rev4 jank-budget problem.
  - [ ] **Step 2: implement the narrow validator change.** Remove `JANK_BUDGET_BP` from the P6 report contract and remove only the branch that adds `jank ... exceeds budget ...` to `problems`. Continue requiring complete frame-overrun arrays, computing `jankBp`, printing it in the derived summary, and failing malformed or missing diagnostic evidence. Do not change `TimelineMetricMath.jankBasisPoints`, the query/frame/memory budgets, sample counts, trace counts, traversal contract, or stability gates.
  - [ ] **Step 3: run the focused host gate.** Run:

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    .\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.timeline.TimelineBenchmarkReportValidatorTest" --tests "com.paherdin.record.core.data.timeline.TimelineMetricMathTest"
    .\gradlew.bat "-Dp6ReportDir=$((Resolve-Path 'benchmark/p6-frozen/p6rev4/run-20260826T155112Z').Path)" :core:data:validateP6BenchmarkReports
    ```

    Expected: both test classes report zero failures/errors. Frozen-report validation prints `queryP50Us=20868 queryP95Us=39261 frameP95Us=14989 jankBp=3796 memoryMaxKib=122680`, the exact traversal summary, and `P6 benchmark report validation: PASS`; the output still exposes `jankBp=3796` as diagnostic.
  - [ ] **Step 4: update the Rev5 acceptance overlay.** Preserve the Rev4 criterion-7 FAIL row. Add a separate Rev5 criterion-7 PASS row proving that the diagnostic was recomputed and retained without gating; do not relabel the 37.96 % measurement itself as PASS.

- [ ] **Task 7 — enforce module, release, schema, and API isolation.**

  - [ ] Refactor the root module check around pure set/edge validation, then write its in-task negative fixtures first: an eighth production module, a second test-tool project, a production-to-`:benchmark` edge, a `:benchmark → :core:data` edge, and an unapproved benchmark configuration edge must each fail independently.
  - [ ] Implement the exact sets: seven unchanged production modules, test-tool allowlist `{":benchmark"}`, unchanged production edges, and the only test-tool project relation being the `com.android.test` target to `:app`. Inspect `benchmarkImplementation`, `benchmarkApi`, and target-project metadata in addition to existing configurations.
  - [ ] Snapshot and compare `core/data/schemas/com.paherdin.record.core.data.room.RecordDatabase/2.json`, `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDatabase.kt`'s `databaseVersion`, and the P5 public-surface allowlist `core/data/api/expected-surface.txt`. Run existing API negative fixtures; no expected-surface update is permitted.
  - [ ] Assemble release and inspect source, merged release manifest, release dependencies, and the final APK. Use `aapt2`/`dexdump` from pinned build-tools 36.0.0; fail on `TimelineBenchmarkActivity`, `profileable`, `androidx.benchmark`, `androidx.profileinstaller`, `TimelineShadowDb`, or the benchmark runner.
  - [ ] Add a repository source assertion that fails on `settings put/delete system peak_refresh_rate|min_refresh_rate` anywhere under `benchmark/src/main` or `app/src/benchmark`. Reads remain allowed; navigation-mode writes are outside this refresh-specific assertion and retain their existing restore control.
  - [ ] Run:

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    .\gradlew.bat checkModuleDependencies :core:data:apiBoundaryCheck :app:assembleRelease
    $releaseDeps = (.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath | Out-String)
    if ($releaseDeps -match 'androidx\.benchmark|androidx\.profileinstaller') { throw 'benchmark dependency leaked into release' }
    $sourceLeak = Get-ChildItem app/src/main,core/data/src/main -Recurse -File | Select-String -Pattern 'TimelineBenchmarkActivity|TimelineShadowDb|androidx\.benchmark|androidx\.profileinstaller|profileable'
    if ($sourceLeak) { throw 'benchmark declaration leaked into main source' }
    $aapt2 = 'C:\Users\p3148\AppData\Local\Android\Sdk\build-tools\36.0.0\aapt2.exe'
    $dexdump = 'C:\Users\p3148\AppData\Local\Android\Sdk\build-tools\36.0.0\dexdump.exe'
    $manifestText = (& $aapt2 dump xmltree app/build/outputs/apk/release/app-release-unsigned.apk --file AndroidManifest.xml | Out-String)
    if ($manifestText -match 'TimelineBenchmarkActivity|profileable|androidx\.benchmark') { throw 'benchmark manifest declaration leaked into release APK' }
    $inspectionDir = Join-Path (Resolve-Path 'app/build') ('p6/release-apk-inspection/' + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $inspectionDir | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path 'app/build/outputs/apk/release/app-release-unsigned.apk'), $inspectionDir)
    $dexText = (Get-ChildItem $inspectionDir -Filter 'classes*.dex' | ForEach-Object { & $dexdump -s $_.FullName } | Out-String)
    if ($dexText -match 'TimelineBenchmarkActivity|TimelineShadowDb|androidx/benchmark|androidx/profileinstaller') { throw 'benchmark code leaked into release DEX' }
    ```

    Expected: all positive checks PASS, every negative fixture fails for its intended reason, snapshots are byte-identical, and neither release inspection output nor release dependency graph contains a forbidden benchmark declaration.

- [ ] **Task 8 — regression, ADR 0015, and lifecycle completion.**

  - [ ] Populate `verification.md` from raw evidence, mapping every Rev5 spec criterion 1–15 to exact command/environment/result/path/risk. Recompute—do not transcribe—slice hashes, p50/p95, jank, memory max, exact selected-day traversal controls, and one-sided regression classification. Preserve the Rev3 full-materialization failure rows and the Rev4 replacement-jank failure as historical evidence; label each non-comparable metric explicitly.
  - [ ] Create `docs/architecture/decisions/0015-selected-day-timeline-benchmark-result.md` with the selected-day query/index contract, complete fixture/full-oracle and slice hashes, dependency/harness versions, native user display settings, all 35 before/after refresh observations, exact traversal summaries, raw-derived Rev5 baselines, every absolute pass/fail (including the jank diagnostic), remaining risks, and M5 comparable-run consequence. Keep it `Proposed` until the user accepts the evidence-bearing result. Do not rewrite accepted ADR 0013 into a passing-result ADR.
  - [ ] Ask for the required deletion approval immediately before `clean`, because it removes generated build directories. Only after approval, run:

    ```powershell
    $env:JAVA_HOME = 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8'
    .\gradlew.bat clean check
    .\gradlew.bat :app:installDebug :app:assembleBenchmark
    $adb = 'C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe'
    & $adb shell am start -W -n com.paherdin.record/.MainActivity
    & $adb shell am force-stop com.paherdin.record
    & $adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
    & $adb shell am start -W -n com.paherdin.record/com.paherdin.record.benchmark.TimelineBenchmarkActivity --es mode smoke
    ```

  - [ ] Because `clean` removes generated oracle slices and copied benchmark outputs, regenerate the complete/slice oracle artifacts and rerun the exact Task 6R4-A correctness, Task 6R4-B self-check, Task 6R4-C measurement/freeze sequence under the Rev5 validator contract from Task 6R5-A, and Task 7 isolation commands before finalizing evidence. A post-clean build-only run is insufficient; never delete or overwrite the frozen Rev3 or Rev4 evidence directories.
  - [ ] Obtain explicit user acceptance of ADR 0015. Only then mark it `Accepted`, synchronize affected core documents, mark P6 spec/plan `completed`, and update the unique-active-spec statements to `none`. ADR 0014 must already be `Accepted` before Task 6R5-A begins.
  - [ ] Run final `git diff --check`, local-link scan, active-spec scan, and `git status --short`. Expected: all 15 Rev5 criteria PASS with raw paths, ADR 0013, ADR 0014, and ADR 0015 accepted, no active spec, and only intentional files changed. Do not commit unless separately requested.

## Exit gate

The P6 exit gate passes when `verification.md` shows: the frozen 10,000-card database fixture and historical complete oracle remain identical; first-day, last-day, boundary/tie, and empty-day slices match typed and rendered states; the binding path queries/publishes only the selected day; every measured replacement traversal publishes one day at a time; query-to-first-correct-draw p95 ≤ 60 ms; pooled frame-CPU p95 ≤ 16.67 ms; peak anonymous RSS ≤ 192 MiB; replacement deadline jank recorded as diagnostic (non-gating); the one-sided 10% Rev5 regression allowance is recorded; ADR 0011's two-set module check and release isolation pass; the schema/API boundary and `clean check`/launch pass; and ADR 0014 and ADR 0015 are accepted. A build-only result cannot stand in for the measurements.

## Lifecycle

- `review-artifact` (2026-08-16): preserved because the user supplied it for review; it had no lifecycle authority while spec Revision 1 remained `draft`.
- `approved` (2026-08-16): the user explicitly approved spec Revision 1; this reviewed artifact was promoted to the project's draft plan.
- `in-progress` (2026-08-16): the user explicitly approved this plan; spec and plan together became the unique active slice and implementation began.
- `in-progress` Revision 2 (2026-08-16): the user explicitly approved this revised plan (fixed-60-Hz requirements removed; 35-measurement refresh before/after plus native-settings validation added; result ADR corrected to 0013). Task 5 resumed as the unique active slice.
- `in-progress` Revision 3 (2026-08-18): the Task 6 binding suite disproved the gesture-count pinning assumption (verification.md blocker note); classified as a harness/measurement-protocol failure requiring no ADR. The user approved D′ — exact-count gate removed, full-scroll control gate retained, gesture counts become positive sub-cap diagnostics, the unmeasured scroll stays a non-gating bottom-reaching control, and the host validator reports min/max/range — and authorized the Task 6 retry. This supersedes Task 5 Step 2's "five equal gesture counts" host-validator requirement.
- `in-progress` Revision 4 approved (2026-08-23): after Task 6's final clean full-materialization result failed criterion 5 at p95 75.22 ms, the user explicitly approved the written selected-day spec, revised plan, unchanged 60 ms budget, and benchmark-shadow `TimelineShadowDb` v1 → v2 additive index migration. Task 6R4-A may begin.
- `in-progress` Revision 5 approved (2026-08-27): the Rev4 selected-day binding passed query/frame/memory/stability but failed display-deadline jank (37.96 % > 2 %) on the synthetic replacement workload. The user approved direction 1 (tightened) on 2026-08-26 and explicitly approved the written ADR 0014, Rev5 spec, and Rev5 plan on 2026-08-27: replacement jank is downgraded to diagnostic, the ≤2 % native-deadline jank gate is deferred to M5 (ADR 0014), and execution resumes at Task 6R5-A, then Task 7 and Task 8.
- `completed` (2026-08-28): the user explicitly accepted ADR 0015; all Rev5 tasks executed and accepted; core documents synchronized; MVP work may start.

# Tech PoC P6 — Timeline Performance

- **Status:** `draft` — the benchmark contract is under review. Implementation is forbidden until the user explicitly approves this spec and a later `plan.md`, and P6 becomes the unique `in-progress` slice.
- **Drafted:** 2026-08-16
- **Revision 1:** 2026-08-16 — resolves the review findings for module topology, metric semantics, workload freezing, regression direction, date-state ownership, Macrobenchmark setup, Compose baseline, deterministic ordering/ZoneId, and explicit ANR evidence. The user approved the repair design, not this revised spec's lifecycle status.
- **Roadmap:** [Tech PoC P6](../../../roadmap.md)
- **Timeline projection contract:** [Domain model](../../../architecture/domain-model.md) (Timeline is a rebuildable read projection, never a fact), [Architecture overview](../../../architecture/overview.md) (`:feature:timeline` reads projections and never writes)
- **Benchmark module decision:** [ADR 0011](../../../architecture/decisions/0011-macrobenchmark-test-module-boundary.md) — accepted; the exact seven-module production graph is unchanged and `:benchmark` is the single allowlisted test-tool project.
- **Benchmark policy:** [Testing strategy](../../../engineering/testing-strategy.md) (`Mixed Timeline performance degradation` and `Performance benchmarks` — the only P6 benchmark)
- **Product behavior:** [MVP requirements](../../../product/mvp-requirements.md) (Journey 8 mixed day timeline), [Information architecture](../../../experience/information-architecture.md), [Interaction rules](../../../experience/interaction-rules.md) (Timeline date state and Today, deferred to M1/M5)
- **Predecessor:** [Tech PoC P5](../p5-export-restore/spec.md) — `completed`; P6 is the final PoC slice and the last gate before MVP. `RecordDatabase` remains `databaseVersion = 2`; P6 introduces no production Room schema change.

## 1. Purpose

Prove that the mixed transaction/note day timeline remains **correct and usable at the approved reference load**. The frozen 10,000-card projection must load completely, render and scroll in the frozen order, produce no ANR, OOM, process death, or corrupt output, and meet the approved query-to-first-correct-draw, frame-CPU, 60 Hz deadline-jank, and anonymous-RSS budgets plus the one-sided regression allowance. P6 contains no thumbnail, aggregation, or startup benchmark.

P6 is a performance PoC, not a delivery slice. The future timeline fields are not present in the v2 production schema, so P6 uses a benchmark-scoped shadow Room projection that models the M2/M3 read shape. The production Timeline table/query, facts-to-projection mapping, `:feature:timeline` screen, shell, and date/Today state machine remain M1/M5 work.

P6 completion creates **ADR 0012**, which records the frozen projection/fixture, reference configuration, actual measurements and baseline, pass/fail result, and M5 consequence. ADR 0011 already owns only the benchmark test-module architecture.

## 2. Frozen reference environment

The reference device is physical and non-rooted. Every required value is recorded immediately before a measured suite; a missing or changed binding value invalidates that suite until the user approves a new baseline.

| Field | Binding value |
|---|---|
| Device | Xiaomi 14 Pro, physical device, USB debugging |
| Product baseline | Android 14 / API 34 (`minSdk = 34`, `targetSdk = 34`) |
| Actual reference system | Android 16 / API 36; exact build fingerprint recorded in `verification.md` |
| CPU and RAM | Exact values recorded in `verification.md` |
| Projection ZoneId | `Asia/Shanghai`; fixture generation, grouping, oracle, and rendered headers never read a mutable device default |
| Measured display mode | 60.00 Hz forced before the suite and verified before every measured iteration; inability to hold 60 Hz invalidates the run |
| Original display mode | Recorded before modification and restored after the suite; the restored value is verified |
| AVD / host CPU-RAM | Not applicable |

The 60 Hz control is part of the metric definition, not context. `frameOverrunMs > 0` is eligible as a missed-frame signal only while the active display mode is verified at 60 Hz. CPU clocks are not locked on this non-rooted user-experience benchmark; thermal and run-order controls bound noise instead.

## 3. Build and dependency baseline

- `minSdk = 34`, `targetSdk = 34`, `compileSdk = 36`, Room 2.8.4, KSP 2.3.9, AGP 9.3, built-in Kotlin 2.2.10, and Compose BOM 2026.06.00 remain pinned.
- Compose is already enabled in `:app`, and `MainActivity` already renders the P0 Compose surface. P6 adds the first scrollable Compose benchmark surface; it does not introduce Compose to the project.
- The production schema remains exactly committed `1.json` + `2.json`; `RecordDatabase.databaseVersion` remains 2, no migration is added, and `fallbackToDestructiveMigration` remains forbidden.
- AndroidX Benchmark is pinned to stable `1.4.1`: `androidx.benchmark:benchmark-macro-junit4:1.4.1` in `:benchmark`. The benchmark target pins `androidx.profileinstaller:profileinstaller:1.4.1` in the benchmark variant only. Both are Apache-2.0 AndroidX families and require the dependency review in section 8.
- `:benchmark` uses `com.android.test` at the pinned AGP version and targets only `:app`'s `benchmark` variant. The target is non-debuggable, uses debug signing, mirrors the current release minification setting (`false`), contains a benchmark-only `<profileable android:shell="true">` declaration, and uses `CompilationMode.Full` for every binding run.
- The shadow projection and typed reader live in `:core:data`'s benchmark source set. `TimelineBenchmarkActivity` and its benchmark manifest live in `:app`'s benchmark source set. They are absent from `main` and `release`.
- The exact seven-module production graph plus the single test-tool project follow ADR 0011. P6 implementation revises `checkModuleDependencies` to validate the two sets and the only test edge; the current production edges remain unchanged.

## 4. Frozen behavior and measurement contract

### 4.1 Timeline projection semantics

A `TimelineCard` contains:

- `id`: canonical lowercase UUID text; type is a separate discriminator and never an ID prefix;
- `type`: `TRANSACTION` or `NOTE`;
- `occurredAt`: epoch milliseconds;
- `title`: transaction account/category-derived label, or the first non-blank plain-text line derived from the Note Markdown body; there is no separate required Note title;
- `amountMinor`: nullable signed integer minor units, present only for `TRANSACTION`;
- `body`: nullable frozen excerpt.

`DayHeader` is derived from `occurredAt` using `ZoneId.of("Asia/Shanghai")`. One header precedes the cards of every non-empty local day; an empty day contributes no item. The flattened projection therefore contains exactly 1,000 headers plus 10,000 cards.

Cards sort by `occurredAt DESC`, then canonical lowercase UUID text ascending under SQLite `BINARY`/ASCII lexical order. The JVM oracle uses the identical explicit string comparator, never `UUID.compareTo`. The query implementation cannot produce its own expected list.

Correctness compares every flattened item and every frozen field: header date; card ID, type, occurrence time, title, amount, and body. A visual field may omit the stable ID, but the corresponding semantics/test payload must expose it for verification. A wrong field, duplicate, omission, extra item, header boundary, or order is corrupt output.

### 4.2 Fixture `timeline-v1`

The generator uses the fixed seed frozen in the approved plan and produces exactly:

- 10,000 cards over 1,000 consecutive `Asia/Shanghai` local days, exactly 10 cards per day;
- 6,000 Transactions and 4,000 Notes;
- among Transactions: 10 opening-balance records at the chronologically oldest edge, 3,000 expenses, 1,800 incomes, and 1,190 same-currency transfers;
- deterministic account/category labels, Note Markdown/plain-text excerpts, signed realistic integer-minor amounts, canonical lowercase UUID text, day-boundary cases, and deterministic occurrence-time ties that exercise the ID tie-break.

Canonical serialization includes the fixture version and ZoneId plus every field in a fixed order and UTF-8 encoding. Its SHA-256 is recorded in `verification.md`. A pure-JVM oracle independently generates the exact 11,000-item flattened artifact; fresh regeneration must equal both recorded hashes.

### 4.3 Shadow projection and full-load rule

The benchmark-scoped Room file database contains only the frozen shadow projection. Database creation, fixture insertion, integrity/hash verification, and connection opening occur before a measured path. The file is retained unchanged for all measured iterations.

Every load iteration executes one ordered query returning **all 10,000 cards**, materializes the complete typed card list, groups all 1,000 headers, publishes the complete immutable 11,000-item UI state, and draws the initially visible correct items. Limiting the query to a day, page, viewport, or first screen is forbidden. P6 proves the frozen full-materialization shape; M5 may later propose a different production loading strategy only through an explicit spec revision and new comparable baseline.

### 4.4 Query-to-first-correct-draw protocol

The load benchmark uses 5 unmeasured warmups followed by 30 measured iterations. Each measured iteration force-stops the target so this is a cold-process first load; it does not claim a cold filesystem/page cache. The already-created database and connection-setup handshake are outside the timed trace.

An asynchronous `TimelineQueryToRender` trace starts immediately before dispatching the one full ordered query. It ends in the first root draw callback after the complete ready-state is installed and the semantics sentinel proves the expected first header/card is the frame being drawn. The trace includes query execution, 10,000-card materialization, day grouping, UI-state publication, composition/layout, and the first correct draw; it excludes Activity launch, database creation/seeding/verification, connection opening, and pre-query setup.

All 30 positive duration samples are retained. Nearest-rank p50 and p95 use one-based ranks `ceil(0.50 × 30) = 15` and `ceil(0.95 × 30) = 29`. A missing, duplicate, unterminated, or multiple matching trace section invalidates the iteration rather than being dropped.

### 4.5 Sustained-scroll, frame, and jank protocol

The scroll benchmark first launches the benchmark Activity outside the measured block, waits for the full 11,000-item state, verifies the top sentinel, and waits for idle. One complete top-to-bottom script is run unmeasured, then 5 measured iterations run from the identical top state.

The approved plan freezes the UIAutomator gesture margin, coordinates/direction, step count, immediate-repeat rule, maximum gesture count, and terminal bottom sentinel. Each iteration must reach the exact final card, report the same frozen gesture count, and contain no query/load/setup frames. A failure to reach bottom or a gesture-count difference invalidates the run. The mechanical values may be frozen by the plan, but the plan cannot replace the full top-to-bottom journey, add idle gaps between gestures, or change the aggregation below.

`FrameTimingMetric.frameDurationCpuMs` supplies the frame-CPU samples. Pool all positive samples from the 5 valid measured iterations and compute nearest-rank p95 over the pooled set. At verified 60 Hz, jank is exactly `count(frameOverrunMs > 0) / count(all frameOverrunMs samples)` over the same measured scroll frames. Startup, setup, load, stationary-wait, and invalid-iteration frames are excluded from both distributions.

### 4.6 Memory, ANR/OOM, and harness controls

`MemoryUsageMetric(Mode.Max, subMetrics = [RssAnon])` records the target process's maximum anonymous resident set in KiB for both the load block and the scroll block. The binding peak is the maximum reported `memoryRssAnonKb` across every valid measured load and scroll iteration. `RssFile`, `RssShmem`, ART heap, and GPU memory are retained as non-gating diagnostic context when available; they are not added to the 192 MiB cap because their independent maxima are not simultaneous samples.

Before each suite: airplane mode is enabled, the device idles for five minutes, the app is foreground-controlled, battery satisfies the benchmark runner, and thermal status is `NONE` or `LIGHT`. `MODERATE` or worse before/after an iteration invalidates the complete suite. Load and scroll run order is counterbalanced and frozen in the plan. Raw benchmark JSON and every Perfetto trace are retained.

Before and after the suite, the harness records package-specific ANR/crash evidence from Android system diagnostics. A new package ANR entry, ANR dialog, UIAutomator/watchdog timeout, target process death, uncaught crash, `OutOfMemoryError`, or unexpected relaunch fails P6. Benchmark runner errors are never suppressed into warnings for a binding run.

The harness self-check proves fixture/hash identity, exact row/item counts, full-query boundary, trace start/end uniqueness, metric formulas, 60 Hz and thermal invalidation, fixed complete scroll, raw-report completeness, and package-specific stability capture. A controlled wrong-order fixture must fail the device correctness assertion.

### 4.7 Budgets and one-sided regression allowance

The user approved these binding budgets on 2026-08-16:

| Metric | Binding calculation and budget |
|---|---|
| Query-to-first-correct-draw | nearest-rank p95 of 30 trace durations ≤ 60 ms |
| Frame CPU duration | pooled nearest-rank p95 `frameDurationCpuMs` ≤ 16.67 ms |
| 60 Hz deadline jank | pooled `frameOverrunMs > 0` fraction ≤ 2% |
| Anonymous RSS | maximum `memoryRssAnonKb` across load and scroll ≤ 196,608 KiB (192 MiB) |
| Stability | no ANR, OOM, process death, crash, corrupt output, or invalid run |

A comparable M5/later run must meet every absolute budget and may not regress the P6 p95 query or p95 frame baseline by more than 10%. For either metric, `new <= baseline` always passes the regression comparison. Only when `new > baseline` is the one-sided symmetric-relative value `(new - baseline) / max(new, baseline)` compared with `0.10`; greater than `0.10` fails. Memory and jank use only their absolute caps. A harness/library version, fixture, workload, formula, build mode, device binding, or display-mode change is non-comparable and requires an explicitly approved new baseline.

### 4.8 Render surface and source ownership

`TimelineBenchmarkActivity` renders a benchmark-only `LazyColumn` with stable keys/content types and only the frozen fields. It contains no navigation, thumbnail, image decode, Markdown rendering, attachment preview, aggregation, production ViewModel, or production route.

The benchmark Activity consumes a typed reader from `:core:data`'s benchmark variant. It never accesses a DAO, Room Entity, database handle, or physical path. `:benchmark` launches and drives the Activity out of process but never calls application code directly. An untimed exhaustive device traversal collects every header/card semantics payload and compares all 11,000 items with the oracle; the performance scroll does not substitute for this correctness traversal.

### 4.9 ADR 0012 and document sync

At completion, ADR 0012 records the projection semantics, fixture/oracle versions and hashes, reference configuration, exact harness/dependency versions, raw-derived measurements, budget and regression decisions, remaining risks, and the M5 re-verification consequence. It is accepted before P6 becomes `completed`.

If implementation exposes a conflict with a core document or disproves a technical assumption, P6 remains `in-progress`, follows the evidence-bearing failure/ADR/spec revision protocol, and requires explicit reapproval before retry.

### 4.10 Deferred to M1/M5 or later

- Production Timeline schema/query, facts-to-projection mapping, atomic projection update, rebuild, paging/loading choice, and screen.
- Bottom-navigation shell and date/filter/scroll state retention.
- Injected-clock/ZoneId UI-state tests: no-state launch, saved-state restore, foreground midnight stability, and explicit Today-to-top. These are M1/M5 behavior evidence, not P6 performance evidence.
- Thumbnail, aggregation, startup, image decode, Markdown rendering, and attachment-preview benchmarks.

## 5. Invariants and acceptance criteria

### 5.1 Invariants

- Fixture, seed, ZoneId, canonical encoding, workload, metric version/formulas, and environment bindings are frozen; a change requires spec revision and re-benchmark.
- Correctness comes only from the independent oracle and the exact 11,000-item comparison.
- Money remains integer minor units; `Float`/`Double` never represents an amount.
- The shadow projection is a read projection and never a fact/write authority.
- Production schema, public surface, seven-module graph, product routes, and release dependencies remain unchanged. ADR 0011's single test-tool project is the only module-set addition.

### 5.2 Acceptance criteria

P6 passes only when `verification.md` maps all criteria to exact commands, environment, results, raw evidence, and remaining risk:

1. **Fixture and oracle:** exact counts/mixes/day distribution, seed/version/ZoneId, canonical UUID/order rules, fixture hash, 11,000-item oracle hash, and fresh-recomputation equality pass on the JVM.
2. **Shadow database:** one benchmark-scoped Room file contains exactly 10,000 cards; schema/table/DAO/source inspection proves no production schema or `main` declaration changed.
3. **Reference binding:** fingerprint/API/CPU/RAM, original and forced 60 Hz modes, `Asia/Shanghai`, build/variant, CompilationMode, dependency versions, battery, airplane/idle, and thermal controls are recorded; original display mode is restored.
4. **Complete correctness:** the typed full result and exhaustive rendered-semantics traversal each equal every one of the oracle's 11,000 items; the controlled wrong-order fixture fails.
5. **Load protocol and budget:** 5 warmups + 30 valid cold-process full-load samples exist; trace uniqueness and rank calculations pass; query-to-first-correct-draw p95 ≤ 60 ms.
6. **Scroll protocol and frame budget:** 1 unmeasured + 5 valid measured full top-to-bottom scripts reach the exact bottom with the frozen gesture count; pooled frame-CPU p95 ≤ 16.67 ms.
7. **Jank budget:** active mode is 60 Hz for every measured iteration and pooled positive-overrun fraction ≤ 2%.
8. **Memory budget:** binding peak `memoryRssAnonKb` across load and scroll ≤ 196,608 KiB; diagnostic submetrics are recorded separately.
9. **Stability:** before/after system evidence and harness outcomes show no new package ANR, OOM, process death, crash, unexpected relaunch, timeout, or corrupt output.
10. **Harness self-check:** every control and negative fixture in section 4.6 passes before the binding run; no benchmark error is suppressed.
11. **Regression contract:** ADR 0012 records the P6 baselines and exact one-sided formula with unit tests proving improvements pass and regressions at/beyond the boundary classify correctly.
12. **Architecture and release isolation:** the revised module check proves exact seven production modules plus only `:benchmark` and its allowed test edge; negative fixtures fail; source/merged release manifest, release dependency graph, and final release APK contain no benchmark Activity/profileable override/runner/shadow projection/ProfileInstaller benchmark declaration.
13. **Schema and API isolation:** committed `2.json`, database version, P5 public-surface allowlist, and production API signatures remain unchanged; `apiBoundaryCheck` and its negative fixtures pass.
14. **Regression:** `./gradlew clean check`, the binding benchmark commands, reference-device install/launch, and benchmark-variant launch all pass; a build-only result cannot replace measurements.
15. **ADR 0012:** the evidence-bearing result ADR is accepted and its M5 consequences are synchronized before P6 completes.

## 6. Test evidence requirements

- JVM tests cover generator/hash determinism, exact mix/counts, canonical serialization, ZoneId day grouping, string-ID ordering and ties, oracle artifact equality, nearest-rank aggregation, jank fraction, memory maximum, and one-sided regression boundaries.
- Device correctness tests cover the typed 10,000-card result, exhaustive 11,000-item rendered traversal, wrong-order failure, trace sentinel, and stability capture outside timed metrics.
- Macrobenchmark retains every raw run/sample plus one Perfetto trace per measured iteration. A validator independently recomputes all four binding metrics from retained reports and must equal the recorded results.
- Verification uses the testing strategy's `Command`, `Environment`, `Result`, `Remaining risk` format and records the exact Git state. Invalid iterations are never silently omitted or replaced inside a binding suite; the entire suite is rerun after restoring preconditions.

## 7. Non-goals

P6 does not:

- modify the production Room schema, database version, public API allowlist, seven-module production graph, release manifest/dependencies, or product routes;
- implement production Timeline behavior, M1/M5 UI state, paging, source-detail navigation, filters, Today, or state retention;
- implement facts-to-projection mapping, projection write coupling, rebuild, or migration;
- benchmark thumbnails, aggregation, startup, images, Markdown, or attachments;
- use JankStats or invent a native-refresh-rate gate; the binding gate is Macrobenchmark at verified 60 Hz;
- silently change a core fact, fixture, workload, formula, dependency, or baseline.

## 8. Dependency review boundary

P6 introduces two stable AndroidX families, benchmark-scoped and absent from release:

1. `androidx.benchmark:benchmark-macro-junit4:1.4.1` and its resolved Macrobenchmark runner/Perfetto transitives;
2. `androidx.profileinstaller:profileinstaller:1.4.1` in the target benchmark variant.

The `com.android.test` plugin reuses AGP 9.3 and is not a third external version. The approved plan records the full resolved artifact graph, Apache-2.0 licenses, AndroidX maintenance evidence, API/AGP compatibility, absence from release, permissions/network review, and the ADR 0011 removal path. Any unexpected resolved family stops plan approval for dependency review. No ads, analytics, crash reporting, closed-source tracking SDK, or `INTERNET` capability is allowed.

## 9. Risks

- **Full materialization may fail 60 ms.** This is the chosen PoC question, not permission to shrink the query. A failed technical assumption follows the same-slice ADR/spec revision protocol.
- **Experimental metric APIs.** Benchmark 1.4.1, exact submetrics, raw-report validator, and retained traces freeze the meaning; an API/version change is a new baseline.
- **OEM display control.** Xiaomi firmware may not hold 60 Hz under every condition. Failure to force and verify it makes the run invalid rather than redefining jank at 120 Hz.
- **Physical-device noise.** Non-rooted CPU frequency, heat, USB, and background work remain sources of variability; idle/thermal gates, counterbalanced blocks, repeated samples, and raw evidence expose the residual risk.
- **Exhaustive render verification cost.** The untimed 11,000-item traversal may be slow, but it is required once for correctness and is intentionally separate from the performance scroll.
- **Test-tool isolation.** Benchmark variants touch production projects at build time. ADR 0011 module checks plus release manifest/dependency/APK inspection prevent benchmark declarations from becoming product code.

## 10. Lifecycle path

- `draft` Revision 1 (2026-08-16): review findings were repaired after the user approved the repair design; no spec lifecycle approval and no implementation authorization yet.
- `approved`: the user explicitly accepts this revised spec; only then may `plan.md` be written.
- `in-progress`: the user explicitly approves both this spec and the later plan; P6 becomes the unique active slice and implementation begins.
- `completed`: all acceptance evidence passes, ADR 0012 is accepted, core documents are synchronized, and no blocker remains; MVP work may then start.

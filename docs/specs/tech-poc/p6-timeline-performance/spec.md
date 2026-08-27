# Tech PoC P6 — Timeline Performance

- **Status:** `completed` — 2026-08-28. All 15 Rev5 criteria have repeatable evidence in [verification.md](verification.md). ADR 0014 (accepted 2026-08-27) defers the ≤2 % native-deadline jank gate to M5; ADR 0015 (accepted 2026-08-28) records the frozen Rev4 baseline, the post-clean Rev5 rerun + one-sided 10 % regression, and the M5 consequence. The unique active spec becomes `none`; MVP work may start.
- **Drafted:** 2026-08-16
- **Revision 1:** 2026-08-16 — resolves the review findings for module topology, metric semantics, workload freezing, regression direction, date-state ownership, Macrobenchmark setup, Compose baseline, deterministic ordering/ZoneId, and explicit ANR evidence. The user approved the repair design, not this revised spec's lifecycle status.
- **Revision 2 approved:** 2026-08-16 — replaces the failed forced-60-Hz environment assumption with the accepted ADR-0012 native-display, deadline-aware jank contract. The user accepted the design, delegated final written-spec review and correction to Codex, and authorized plan revision if that review found no blocker; self-review passed after synchronizing the display binding and result-ADR numbering. The user explicitly approved the revised plan on 2026-08-16.
- **Revision 3 approved:** 2026-08-18 — the Task 6 binding suite disproved the plan's assumption that a gesture count pinned from the unmeasured manual scroll can equal the captured measured iterations' counts (Perfetto capture overhead shortens the fling; pin/measured 953/936, 950/938, 1004/993 across three aborted suite runs). Classification: harness/measurement-protocol assumption failure, not a PoC technical conclusion — no ADR required. The user chose option D′: gestureCount becomes recorded diagnostic evidence (positive, below the frozen 1200 cap), count equality no longer invalidates an iteration, and the unmeasured full scroll remains a non-gating control that must reach the bottom sentinel. This revises §4.5, §5.2 criterion 6, and the plan's frozen scroll protocol and Task 6 steps.
- **Revision 4 pending approval:** 2026-08-22 — accepted ADR 0013 rejects the failed 10,000-card full-materialization load. This draft binds the measured load to `occurredAt >= dayStart AND occurredAt < nextDayStart`, retains all 10,000 fixture rows in the database, validates selected-day oracle slices, replaces the obsolete cross-day full-scroll metric with a sequential single-day-state replacement workload, keeps every absolute budget, and assigns the eventual passing result to ADR 0014. The user's direction approval is not spec/plan approval.
- **Revision 4 approved:** 2026-08-23 — the user explicitly approved this written Rev4 spec and revised plan and separately consented to the benchmark-shadow `TimelineShadowDb` v1 → v2 additive `occurredAt` index migration. This approval authorizes Tasks 6R4-A/B/C, 7, and 8 in sequence; it does not authorize a production schema/index or later MVP work.
- **Revision 5 approved:** 2026-08-27 — the Rev4 selected-day binding passed query/frame/memory/stability but failed display-deadline jank (37.96 % > 2 %) on the synthetic replacement workload. The user approved direction 1 (tightened) on 2026-08-26 and explicitly approved the corrected written ADR 0014, Rev5 spec, and Rev5 plan on 2026-08-27: replacement jank is downgraded to diagnostic, the ≤2 % native-deadline jank gate is deferred to M5's real production interaction, and execution resumes at Task 6R5-A, then Task 7 and Task 8.
- **Spec approved:** 2026-08-16 — the user explicitly approved spec Revision 1 and the plan together; the approval date/context is recorded here per the roadmap lifecycle rules.
- **Roadmap:** [Tech PoC P6](../../../roadmap.md)
- **Timeline projection contract:** [Domain model](../../../architecture/domain-model.md) (Timeline is a rebuildable read projection, never a fact), [Architecture overview](../../../architecture/overview.md) (`:feature:timeline` reads projections and never writes), and [Data and storage](../../../architecture/data-and-storage.md) (selected-day half-open read boundary, optional post-first-draw prefetch, no cross-day continuous stream)
- **Benchmark module decision:** [ADR 0011](../../../architecture/decisions/0011-macrobenchmark-test-module-boundary.md) — accepted; the exact seven-module production graph is unchanged and `:benchmark` is the single allowlisted test-tool project.
- **Display/jank decision:** [ADR 0012](../../../architecture/decisions/0012-native-display-deadline-jank-benchmark-contract.md) — accepted; the benchmark preserves the reference device's native user display settings and uses per-frame system-deadline overrun (its application of the ≤2 % jank budget to the P6 replacement workload is partially overridden by ADR 0014).
- **Jank deferral decision:** [ADR 0014](../../../architecture/decisions/0014-native-display-deadline-jank-deferred-to-m5.md) — accepted 2026-08-27; P6 replacement-workload jank becomes diagnostic, and the ≤2 % native-deadline jank gate is deferred to M5's real production interaction.
- **Failed-load decision:** [ADR 0013](../../../architecture/decisions/0013-timeline-full-materialization-fails-60ms.md) — accepted; full materialization is rejected, selected-day bounded query is chosen, adjacent-day prefetch is optional and post-first-draw only, and cross-day keyset paging/Paging 3 are not selected.
- **Benchmark policy:** [Testing strategy](../../../engineering/testing-strategy.md) (`Mixed Timeline performance degradation` and `Performance benchmarks` — the only P6 benchmark)
- **Product behavior:** [MVP requirements](../../../product/mvp-requirements.md) (Journey 8 mixed day timeline), [Information architecture](../../../experience/information-architecture.md), [Interaction rules](../../../experience/interaction-rules.md) (Timeline date state and Today, deferred to M1/M5)
- **Predecessor:** [Tech PoC P5](../p5-export-restore/spec.md) — `completed`; P6 is the final PoC slice and the last gate before MVP. `RecordDatabase` remains `databaseVersion = 2`; P6 introduces no production Room schema change.

## 1. Purpose

Prove that the mixed transaction/note selected-day data and benchmark-render path remains correct and operationally viable while the database contains the approved 10,000-card reference load. The binding path queries, materializes, publishes, and draws only the selected local day; the complete fixture remains on disk and is exercised through independently derived day slices and a sequential one-day-at-a-time replacement workload. P6 must produce no ANR, OOM, process death, or corrupt output and must meet its query-to-first-correct-draw, frame-CPU, anonymous-RSS, stability, and one-sided regression gates. Replacement-workload deadline jank is diagnostic. P6 does not prove production `TimelineHome` 120 Hz interaction quality; M5 owns that gate. P6 contains no thumbnail, aggregation, or startup benchmark.

P6 is a performance PoC, not a delivery slice. The future timeline fields are not present in the v2 production schema, so P6 uses a benchmark-scoped shadow Room projection that models the M2/M3 read shape. The production Timeline table/query, facts-to-projection mapping, `:feature:timeline` screen, shell, and date/Today state machine remain M1/M5 work.

ADR 0013 records the failed full-materialization conclusion and the selected-day replacement decision. ADR 0014 records the Rev4 replacement-jank failure and defers the production-interaction jank gate to M5. P6 completion creates **ADR 0015**, which records the Rev5 workload, reference configuration, measurements, comparable query/frame baselines, pass/fail result, and M5 consequence. ADR 0011 owns only the benchmark test-module architecture; ADR 0012 retains the native-display and deadline-overrun semantics that ADR 0014 preserves.

## 2. Frozen reference environment

The reference device is physical and non-rooted. Every required value is recorded immediately before a measured suite; a missing or changed binding value invalidates that suite until the user approves a new baseline.

| Field | Binding value |
|---|---|
| Device | Xiaomi 14 Pro, physical device, USB debugging |
| Product baseline | Android 14 / API 34 (`minSdk = 34`, `targetSdk = 34`) |
| Actual reference system | Android 16 / API 36; exact build fingerprint recorded in `verification.md` |
| CPU and RAM | Exact values recorded in `verification.md` |
| Projection ZoneId | `Asia/Shanghai`; fixture generation, grouping, oracle, and rendered headers never read a mutable device default |
| User display settings | Native reference binding: `peak_refresh_rate=120`, `min_refresh_rate=null`; verified before and after the suite, never modified by the harness |
| Active refresh observation | Activity-visible refresh is recorded before and after every measured iteration; device-policy variation is retained as evidence and does not alone invalidate a run |
| AVD / host CPU-RAM | Not applicable |

The frozen user display settings are part of the reference configuration. `frameOverrunMs > 0` means that the frame missed its system deadline; it is not reconstructed from a fixed 16.67 ms threshold and remains meaningful on higher or variable refresh rates. `frameDurationCpuMs` is a separate CPU-work metric. CPU clocks are not locked on this non-rooted user-experience benchmark; thermal and run-order controls bound noise instead.

## 3. Build and dependency baseline

- `minSdk = 34`, `targetSdk = 34`, `compileSdk = 36`, Room 2.8.4, KSP 2.3.9, AGP 9.3, built-in Kotlin 2.2.10, and Compose BOM 2026.06.00 remain pinned.
- Compose is already enabled in `:app`, and `MainActivity` already renders the P0 Compose surface. P6 adds the first Compose Timeline benchmark list surface; it does not introduce Compose to the project.
- The production schema remains exactly committed `1.json` + `2.json`; `RecordDatabase.databaseVersion` remains 2, no production migration is added, and `fallbackToDestructiveMigration` remains forbidden. Rev4 introduced an additive **benchmark-shadow-only** `TimelineShadowDb` version 1 → 2 migration that creates the `occurredAt` index while preserving all 10,000 fixture rows. The user explicitly approved it with Rev4, and Task 6R4-A verified it. It does not authorize the future production index.
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

`DayHeader` is derived from `occurredAt` using `ZoneId.of("Asia/Shanghai")`. For one selected non-empty day, exactly one header precedes that day's cards; an empty selected day contributes no item. Under `timeline-v1`, a non-empty selected-day state contains exactly 1 header + 10 cards = 11 items. The historical complete oracle remains a 1,000-header + 10,000-card artifact used to prove fixture identity and derive independent selected-day slices; it is not a UI state or binding query result in Rev4.

Cards sort by `occurredAt DESC`, then canonical lowercase UUID text ascending under SQLite `BINARY`/ASCII lexical order. The JVM oracle uses the identical explicit string comparator, never `UUID.compareTo`. The query implementation cannot produce its own expected list.

Correctness compares every flattened item and every frozen field: header date; card ID, type, occurrence time, title, amount, and body. A visual field may omit the stable ID, but the corresponding semantics/test payload must expose it for verification. A wrong field, duplicate, omission, extra item, header boundary, or order is corrupt output.

### 4.2 Fixture `timeline-v1`

The generator uses the fixed seed frozen in the approved plan and produces exactly:

- 10,000 cards over 1,000 consecutive `Asia/Shanghai` local days, exactly 10 cards per day;
- 6,000 Transactions and 4,000 Notes;
- among Transactions: 10 opening-balance records at the chronologically oldest edge, 3,000 expenses, 1,800 incomes, and 1,190 same-currency transfers;
- deterministic account/category labels, Note Markdown/plain-text excerpts, signed realistic integer-minor amounts, canonical lowercase UUID text, day-boundary cases, and deterministic occurrence-time ties that exercise the ID tie-break.

Canonical serialization includes the fixture version and ZoneId plus every field in a fixed order and UTF-8 encoding. Its SHA-256 is recorded in `verification.md`. A pure-JVM oracle independently generates the historical complete 11,000-item artifact and selected-day slices without calling the Room reader. Fresh regeneration must equal the recorded fixture/full-oracle hashes and the emitted slice hashes for:

- chronological first fixture day `2023-01-01` (`dayStart = 1672502400000`, `nextDayStart = 1672588800000`);
- chronological last fixture day and binding reference day `2025-09-26` (`dayStart = 1758816000000`, `nextDayStart = 1758902400000`);
- empty day `2022-12-31` (`dayStart = 1672416000000`, `nextDayStart = 1672502400000`).

The two non-empty slices each contain one header plus ten cards. Their slot-0 and slot-9 records prove inclusive `dayStart` and exclusive `nextDayStart`; their equal timestamps prove ascending UUID-text tie ordering. The empty slice contains zero items and no header.

### 4.3 Shadow projection and selected-day bounded-query rule

The benchmark-scoped Room file database contains only the frozen shadow projection. Database creation, fixture insertion, integrity/hash verification, and connection opening occur before a measured path. The file is retained unchanged for all measured iterations.

The shadow table retains all 10,000 cards and has a benchmark-only index on `occurredAt`. Every binding load iteration receives one already-selected `LocalDate`, derives `dayStart` and `nextDayStart` with `ZoneId.of("Asia/Shanghai")`, and executes exactly one ordered query with this logical shape:

```sql
SELECT * FROM timeline_card
WHERE occurredAt >= :dayStart AND occurredAt < :nextDayStart
ORDER BY occurredAt DESC, id COLLATE BINARY ASC
```

The binding reference day is `2025-09-26`. Its query returns exactly 10 cards, the typed reader groups them into one header + ten cards, and the Activity publishes one immutable 11-item current-day state. An empty-day query publishes the declared empty state with no header. A DAO full-load query, `LIMIT`/viewport shortcut, keyset cursor, Paging 3 source, cross-day continuous state, or adjacent-day rows in the published current-day state is forbidden.

Adjacent-day prefetch is absent from the Rev4 binding baseline and plan. A later approved implementation may add it only after the selected day's first correct draw; it must be asynchronous, cannot block `ready`, cannot enter the `TimelineQueryToRender` trace, and must keep prefetched data outside the current-day state. Introducing it changes the frame/memory workload and requires the later specification to state whether those baselines remain comparable.

### 4.4 Query-to-first-correct-draw protocol

The load benchmark uses 5 unmeasured warmups followed by 30 measured iterations. Each measured iteration force-stops the target so this is a cold-process first load; it does not claim a cold filesystem/page cache. The already-created database and connection-setup handshake are outside the timed trace.

An asynchronous `TimelineQueryToRender` trace starts immediately before dispatching the one selected-day ordered query. It ends in the first root draw callback after the complete selected-day ready-state is installed and the semantics sentinel proves the expected header/first card for `2025-09-26` is the frame being drawn. The trace includes query execution, ten-card materialization, one-day grouping, current-day UI-state publication, composition/layout, and the first correct draw; it excludes Activity launch, database creation/seeding/verification, connection opening, selected-date/bound derivation, and other pre-query setup. No prefetch work exists in or before this trace.

All 30 positive duration samples are retained. Nearest-rank p50 and p95 use one-based ranks `ceil(0.50 × 30) = 15` and `ceil(0.95 × 30) = 29`. A missing, duplicate, unterminated, or multiple matching trace section invalidates the iteration rather than being dropped.

### 4.5 Selected-day replacement, frame, and jank protocol

The Rev3 11,000-item cross-day top-to-bottom scroll is historical evidence for the rejected full-materialization workload and is not a Rev4 gate. Under the frozen fixture, one selected day contains only 11 flattened items, so manufacturing a long cross-day list would contradict the selected-day product contract and an ordinary within-day fling would not yield a stable frame sample.

Rev4 therefore uses a deterministic **selected-day replacement traversal**. One unmeasured control and five measured iterations each start from the same prepared, seeded process with no published day state, visit all 1,000 fixture dates in descending local-date order (`2025-09-26` through `2023-01-01`), and for each date perform exactly one selected-day bounded query, publish only that day's 11-item state, and wait for its first correct draw before requesting the next date. There is at most one query in flight, no explicit sleep/idle gap between completed draw and next request, no prefetch, no gesture count, and no adjacent-day content in the current state. Each valid iteration records exactly 1,000 correctly bounded selected-day draws, 999 replacements, maximum published item count 11, first/last dates, and prefetch count zero. A missing day, duplicate day, wrong date/count, out-of-range item, overlapping query, mixed-day state, process change, or incomplete traversal invalidates the suite. Independent content/order correctness remains owned by the first/last/empty oracle-slice tests rather than by a tautological traversal hash.

`FrameTimingMetric.frameDurationCpuMs` supplies the frame-CPU samples. Pool all positive samples from the five valid measured replacement iterations and compute nearest-rank p95 over the pooled set. `frameOverrunMs` is still collected and the deadline-jank fraction `count(frameOverrunMs > 0) / count(all frameOverrunMs samples)` is recomputed over the same measured replacement frames as recorded diagnostic evidence, but it is no longer a P6 gate (ADR 0014). AndroidX derives overrun from each frame's system deadline; active-refresh variation under the frozen user settings does not cause a fixed-threshold reinterpretation. Startup, process/database setup, pre-start stationary wait, post-completion sentinel persistence, and invalid-iteration frames are excluded. The selected-day queries and state-replacement draws remain inside this synthetic stress workload.

### 4.6 Memory, ANR/OOM, and harness controls

`MemoryUsageMetric(Mode.Max, subMetrics = [RssAnon])` records the target process's maximum anonymous resident set in KiB for both the selected-day load block and the selected-day replacement block. The binding peak is the maximum reported `memoryRssAnonKb` across every valid measured iteration. `RssFile`, `RssShmem`, ART heap, and GPU memory are retained as non-gating diagnostic context when available; they are not added to the 192 MiB cap because their independent maxima are not simultaneous samples.

Before each suite: airplane mode is enabled, the device idles for five minutes, the app is foreground-controlled, battery satisfies the benchmark runner, and thermal status is `NONE` or `LIGHT`. `MODERATE` or worse before/after an iteration invalidates the complete suite. Selected-day load and replacement-block run order is counterbalanced and frozen in the plan. Raw benchmark JSON and every Perfetto trace are retained.

Before and after the suite, the harness records package-specific ANR/crash evidence from Android system diagnostics. A new package ANR entry, ANR dialog, UIAutomator/watchdog timeout, target process death, uncaught crash, `OutOfMemoryError`, or unexpected relaunch fails P6. Benchmark runner errors are never suppressed into warnings for a binding run.

The harness self-check proves fixture/hash identity, exact 10,000-row database count, selected-day half-open SQL boundary and indexed query plan, first/last/empty day oracle slices, boundary/tie behavior, one-day-only published state, trace start/end uniqueness, metric formulas, native display-setting preservation and drift invalidation, refresh-observation capture, thermal invalidation, exact 1,000-day replacement traversal, raw-report completeness, and package-specific stability capture. A controlled within-day tie-order swap must fail the device correctness assertion.

### 4.7 Budgets and one-sided regression allowance

The numeric query, frame-CPU, and memory budgets remain those approved on 2026-08-16. Revision 5 changes only the replacement-jank classification:

| Metric | Binding calculation and budget |
|---|---|
| Query-to-first-correct-draw | nearest-rank p95 of 30 trace durations ≤ 60 ms |
| Frame CPU duration | pooled nearest-rank p95 `frameDurationCpuMs` ≤ 16.67 ms |
| Display-deadline jank | pooled `frameOverrunMs > 0` fraction recorded as diagnostic evidence; the ≤2% absolute gate is deferred to M5's real production interaction (ADR 0014) |
| Anonymous RSS | maximum `memoryRssAnonKb` across selected-day load and replacement traversal ≤ 196,608 KiB (192 MiB) |
| Stability | no ANR, OOM, process death, crash, corrupt output, or invalid run |

A comparable M5/later run must meet every absolute budget and may not regress the Rev4 selected-day p95 query or p95 frame baseline by more than 10%. For either metric, `new <= baseline` always passes the regression comparison. Only when `new > baseline` is the one-sided symmetric-relative value `(new - baseline) / max(new, baseline)` compared with `0.10`; greater than `0.10` fails. Memory uses only its absolute cap; deadline jank is a diagnostic in Rev5, not a regression metric. The rejected full-materialization measurements are not comparable baselines. A harness/library version, fixture/database cardinality, selected reference day, selected-day query/index shape, replacement sequence, formula, build mode, device binding, system build, user display setting, or post-draw prefetch addition is non-comparable for the affected metric and requires an explicitly approved new baseline. Active-refresh observations may differ under the same frozen settings without alone making the run non-comparable.

### 4.8 Render surface and source ownership

`TimelineBenchmarkActivity` renders a benchmark-only selected-day `LazyColumn` with stable keys/content types and only the frozen fields. Its replacement mode swaps one complete selected-day state for the next only after the prior first correct draw. It contains no production navigation/date picker, thumbnail, image decode, Markdown rendering, attachment preview, aggregation, production ViewModel, or production route.

The benchmark Activity consumes a selected-day typed reader from `:core:data`'s benchmark variant. It never accesses a DAO, Room Entity, database handle, or physical path. `:benchmark` launches and drives the Activity out of process but never calls application code directly. Untimed device correctness runs compare typed and rendered first-day, last-day, and empty-day states with the corresponding independent oracle slices; the binding replacement traversal does not substitute for these assertions.

### 4.9 ADR 0012, failure ADR 0013, jank-deferral ADR 0014, result ADR 0015, and document sync

ADR 0012 records the failed forced-60-Hz assumption and accepted native-display deadline-jank correction (partially overridden by ADR 0014 on the jank budget). Accepted ADR 0013 records the failed full-materialization conclusion and selected-day replacement. ADR 0014 records the Rev4 jank failure and the decision to downgrade replacement jank to diagnostic and defer the ≤2 % native-deadline jank gate to M5. At completion, ADR 0015 records the Rev5 selected-day query/index and replacement semantics, fixture/oracle-slice hashes, reference configuration, exact harness/dependency versions, raw-derived measurements and baselines, budget/regression decisions, remaining risks, and M5 re-verification consequence. ADR 0015 is accepted before P6 becomes `completed`.

If implementation exposes a conflict with a core document or disproves a technical assumption, P6 remains `in-progress`, follows the evidence-bearing failure/ADR/spec revision protocol, and requires explicit reapproval before retry.

### 4.10 Deferred to M1/M5 or later

- Production Timeline schema/index, facts-to-projection mapping, atomic projection update, rebuild, cache/prefetch implementation, and screen. M5 must honor the selected-day bounded-query decision but owns its physical schema and index through a separately approved plan.
- Bottom-navigation shell and date/filter/scroll state retention.
- Injected-clock/ZoneId UI-state tests: no-state launch, saved-state restore, foreground midnight stability, and explicit Today-to-top. These are M1/M5 behavior evidence, not P6 performance evidence.
- Thumbnail, aggregation, startup, image decode, Markdown rendering, and attachment-preview benchmarks.

## 5. Invariants and acceptance criteria

### 5.1 Invariants

- Fixture, seed, ZoneId, canonical encoding, workload, metric version/formulas, native user display settings, and environment bindings are frozen; a change requires spec revision and re-benchmark.
- Correctness comes only from the independent oracle: the complete artifact proves fixture identity, while first-day, last-day, boundary/tie, and empty-day slices prove the selected-day query and rendered states.
- Every published ready state contains exactly one selected day or the declared empty state. Adjacent-day prefetch is absent from the Rev4 baseline, and no cross-day continuous state exists.
- Money remains integer minor units; `Float`/`Double` never represents an amount.
- The shadow projection is a read projection and never a fact/write authority.
- Production schema, public surface, seven-module graph, product routes, and release dependencies remain unchanged. ADR 0011's single test-tool project is the only module-set addition.

### 5.2 Acceptance criteria

P6 passes only when `verification.md` maps all criteria to exact commands, environment, results, raw evidence, and remaining risk:

1. **Fixture and oracle:** exact counts/mixes/day distribution, seed/version/ZoneId, canonical UUID/order rules, fixture hash, historical 11,000-item oracle hash, emitted first-day/last-day/empty-day slice hashes, half-open boundary cases, and fresh-recomputation equality pass on the JVM.
2. **Shadow database:** one benchmark-scoped Room file contains exactly 10,000 cards; its additive version 1 → 2 migration preserves all rows plus first/last-day hashes and creates the benchmark-only `occurredAt` index; the only render/load DAO query is the selected-day half-open range ordered by time then ID; query-plan/source inspection proves the bounded query uses the index and no production schema, production migration, or `main` declaration changed.
3. **Reference binding:** fingerprint/API/CPU/RAM, unchanged native user display settings (`peak=120`, `min=null`), per-iteration Activity-visible refresh observations, `Asia/Shanghai`, build/variant, CompilationMode, dependency versions, battery, airplane/idle, and thermal controls are recorded; source and before/after evidence prove the harness made no refresh-setting writes.
4. **Selected-day correctness:** typed and rendered results for `2023-01-01`, `2025-09-26`, and empty `2022-12-31` equal their independent oracle slices exactly; inclusive start, exclusive next-day start, timestamp ties, no-header empty state, and a controlled within-day wrong-order failure pass. No device correctness path queries or renders the complete cross-day projection.
5. **Load protocol and budget:** 5 warmups + 30 valid cold-process selected-day samples for `2025-09-26` exist; each trace contains exactly one bounded query and one first-correct-draw closure, no prefetch, and only the selected-day state; nearest-rank calculations pass and query-to-first-correct-draw p95 ≤ 60 ms.
6. **Selected-day replacement and frame budget:** 1 unmeasured + 5 valid measured traversals each produce exactly 1,000 correct selected-day draws and 999 replacements from `2025-09-26` through `2023-01-01`, with one query in flight, maximum published state size 11, prefetch count zero, and no mixed-day state; pooled frame-CPU p95 ≤ 16.67 ms.
7. **Jank diagnostic (Rev5):** the pooled positive-`frameOverrunMs` fraction over all measured selected-day replacement frames is recomputed and recorded as diagnostic evidence; every iteration has a refresh observation and unchanged native user display settings, without a fixed active-Hz requirement. The ≤2% absolute deadline-jank gate is deferred to M5's real production interaction (ADR 0014); the Rev4 37.96% measurement is retained as FAIL/diagnostic evidence and never relabeled.
8. **Memory budget:** binding peak `memoryRssAnonKb` across selected-day load and replacement iterations ≤ 196,608 KiB; diagnostic submetrics are recorded separately.
9. **Stability:** before/after system evidence and harness outcomes show no new package ANR, OOM, process death, crash, unexpected relaunch, timeout, or corrupt output.
10. **Harness self-check:** every control and negative fixture in section 4.6 passes before the binding run; no benchmark error is suppressed.
11. **Regression contract:** ADR 0015 records the Rev5 selected-day query and replacement frame baselines and the exact one-sided formula with unit tests proving improvements pass and regressions at/beyond the boundary classify correctly; ADR 0013's rejected full-load measurements and ADR 0014's replacement-jank failure remain historical evidence outside that regression contract.
12. **Architecture and release isolation:** the revised module check proves exact seven production modules plus only `:benchmark` and its allowed test edge; negative fixtures fail; source/merged release manifest, release dependency graph, and final release APK contain no benchmark Activity/profileable override/runner/shadow projection/ProfileInstaller benchmark declaration.
13. **Schema and API isolation:** committed `2.json`, database version, P5 public-surface allowlist, and production API signatures remain unchanged; `apiBoundaryCheck` and its negative fixtures pass.
14. **Regression:** `./gradlew clean check`, the binding benchmark commands, reference-device install/launch, and benchmark-variant launch all pass; a build-only result cannot replace measurements.
15. **ADRs 0014 and 0015:** ADR 0014 is accepted before Rev5 execution resumes. The evidence-bearing result ADR 0015 is accepted and its M5 consequences are synchronized before P6 completes; accepted ADR 0013 remains the full-materialization failure/alternative decision record.

## 6. Test evidence requirements

- JVM tests cover generator/hash determinism, exact mix/counts, canonical serialization, ZoneId day bounds/grouping, string-ID ordering and ties, complete-oracle identity, selected-day slice equality including empty state, nearest-rank aggregation, jank fraction, memory maximum, and one-sided regression boundaries.
- Device correctness tests cover the 10,000-row database plus typed/rendered first-day, last-day, and empty-day slices; boundary/tie assertions; within-day wrong-order failure; selected-day SQL/index plan; trace sentinel; one-day-only state; and stability capture outside timed metrics.
- Macrobenchmark retains every raw run/sample plus one Perfetto trace per measured iteration. A validator independently recomputes all four binding metrics from retained reports and must equal the recorded results.
- Verification uses the testing strategy's `Command`, `Environment`, `Result`, `Remaining risk` format and records the exact Git state. Invalid iterations are never silently omitted or replaced inside a binding suite; the entire suite is rerun after restoring preconditions.

## 7. Non-goals

P6 does not:

- modify the production Room schema, database version, public API allowlist, seven-module production graph, release manifest/dependencies, or product routes;
- implement production Timeline behavior, M1/M5 UI state, paging, source-detail navigation, filters, Today, or state retention;
- implement adjacent-day prefetch, keyset paging, Paging 3, a cross-day infinite/continuous stream, or any state that combines selected and adjacent days;
- implement facts-to-projection mapping, projection write coupling, rebuild, or migration;
- benchmark thumbnails, aggregation, startup, images, Markdown, or attachments;
- use JankStats, reinterpret jank as a fixed 16.67 ms CPU-duration threshold, or force a display refresh mode; P6 records Macrobenchmark deadline overrun under the frozen native user settings as a diagnostic, while M5 owns the production-interaction gate;
- silently change a core fact, fixture, workload, formula, dependency, or baseline.

## 8. Dependency review boundary

P6 introduces two stable AndroidX families, benchmark-scoped and absent from release:

1. `androidx.benchmark:benchmark-macro-junit4:1.4.1` and its resolved Macrobenchmark runner/Perfetto transitives;
2. `androidx.profileinstaller:profileinstaller:1.4.1` in the target benchmark variant.

The `com.android.test` plugin reuses AGP 9.3 and is not a third external version. The approved plan records the full resolved artifact graph, Apache-2.0 licenses, AndroidX maintenance evidence, API/AGP compatibility, absence from release, permissions/network review, and the ADR 0011 removal path. Any unexpected resolved family stops plan approval for dependency review. No ads, analytics, crash reporting, closed-source tracking SDK, or `INTERNET` capability is allowed.

## 9. Risks

- **Selected-day fixture density.** `timeline-v1` has exactly ten cards per day, so Rev4 uses repeated one-day replacement rather than inventing a cross-day list to obtain frame samples. It does not prove a future unusually dense single-day list; M5 must declare and rebaseline any materially different per-day cardinality.
- **Replacement workload is synthetic but product-shaped.** The 1,000-day automatic traversal is a deterministic stress harness, not a production auto-advance interaction. It repeatedly exercises the approved selected-day read/state boundary while never authorizing automatic date changes in M5. Its Rev4 deadline jank (37.96 %) reflects full-day state replacement at 120 Hz, not human scrolling; ADR 0014 defers the ≤2 % jank gate to M5's real interaction.
- **Experimental metric APIs.** Benchmark 1.4.1, exact submetrics, raw-report validator, and retained traces freeze the meaning; an API/version change is a new baseline.
- **OEM variable-refresh behavior.** Xiaomi firmware may vary active refresh under unchanged native user settings. The harness records each observation and relies on Perfetto-derived system deadlines; missing deadline data or changed user settings invalidates the run, while refresh variation alone does not.
- **Physical-device noise.** Non-rooted CPU frequency, heat, USB, and background work remain sources of variability; idle/thermal gates, counterbalanced blocks, repeated samples, and raw evidence expose the residual risk.
- **Optional prefetch comparability.** Prefetch is absent from Rev4. Adding it after first draw preserves the 60 ms trace boundary but changes post-draw frame/memory work; the later owning spec must explicitly classify comparability and may require a new baseline.
- **Test-tool isolation.** Benchmark variants touch production projects at build time. ADR 0011 module checks plus release manifest/dependency/APK inspection prevent benchmark declarations from becoming product code.

## 10. Lifecycle path

- `draft` Revision 1 (2026-08-16): review findings were repaired after the user approved the repair design; no spec lifecycle approval and no implementation authorization yet.
- `approved` (2026-08-16): the user explicitly approved spec Revision 1; the plan was then promoted from its reviewed artifact to the project's draft plan.
- `in-progress` (2026-08-16): the user explicitly approved the plan; spec and plan together became the unique active slice and implementation began under the frozen plan.
- `in-progress` Revision 2 resume (2026-08-16): the user explicitly approved the revised plan; Task 5 resumed.
- `in-progress` Revision 3 (2026-08-18): the Task 6 gesture-count pinning assumption failed (evidence-bearing failure per §4.9, recorded in verification.md). The user classified it as a harness/measurement-protocol failure requiring no ADR, approved D′ (exact-count gate removed, full-scroll control gate retained, gesture counts recorded as diagnostics), and authorized the spec/plan revision and Task 6 retry.
- `in-progress` Revision 4 approved (2026-08-23): after the clean full-materialization run failed criterion 5 at p95 75.22 ms, the user explicitly approved the written selected-day spec, revised plan, unchanged 60 ms budget, and benchmark-shadow v1 → v2 additive index migration. Task 6R4-A may begin; production schema/index and MVP work remain unauthorized.
- `in-progress` Revision 5 approved (2026-08-27): the Rev4 selected-day binding passed query/frame/memory/stability but failed display-deadline jank (37.96 % > 2 %) on the synthetic replacement workload. The user approved direction 1 (tightened) on 2026-08-26 and explicitly approved the written ADR 0014, Rev5 spec, and Rev5 plan on 2026-08-27: replacement jank is downgraded to diagnostic, the ≤2 % native-deadline jank gate is deferred to M5 (ADR 0014), and execution resumes at Task 6R5-A, then Task 7 and Task 8.
- `completed` (2026-08-28): the user explicitly accepted ADR 0015 after the post-clean full rerun passed every absolute gate and the one-sided 10 % regression; all 15 Rev5 criteria have evidence; core documents are synchronized; MVP work may start.

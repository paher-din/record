# ADR 0015: Selected-day timeline benchmark result (Rev5)

## Status

Accepted

## Date

2026-08-28

Drafted 2026-08-27 after the Rev5 selected-day binding passed every gate except the deferred jank budget (ADR 0014). The user explicitly accepted the evidence-bearing result on 2026-08-28 after the post-`clean` full gate rerun confirmed it and the six requested document corrections (refresh observations, traversal summaries, remaining risks, criteria 11–15 rows, evidence migration to `benchmark/p6-frozen/`, and `.gitignore`) were applied. P6 is `completed`.

## Context

P6 proves the mixed transaction/note selected-day timeline is correct and operationally viable while the benchmark-shadow database retains the full 10,000-card fixture. Rev4 (accepted ADR 0013) replaced full materialization with a selected-day bounded query; the Rev4 binding then failed only the display-deadline jank budget (37.96 % > 2 %) on the synthetic replacement workload. ADR 0014 (accepted 2026-08-27) downgrades replacement jank to a recorded diagnostic and defers the ≤2 % native-deadline jank gate to M5. This Rev5 result records the baselines, the pass/fail, and the M5 consequence.

Frozen reference: Xiaomi 14 Pro, Android 16 / API 36 (product baseline API 34), native `peak_refresh_rate=120` / `min_refresh_rate=null` (never written), `ZoneId=Asia/Shanghai`, binding selected day `2025-09-26` (bounds `[1758816000000, 1758902400000)`), AndroidX Benchmark 1.4.1 / ProfileInstaller 1.4.1 (benchmark variant), `CompilationMode.Full`.

## Frozen artifacts

- Fixture `timeline-v1` (seed `2026081606L`) SHA-256 `32fd36deaab5798f54533931b2ab261cd24c759790179895d37f0f553430b1b9`.
- Complete oracle SHA-256 `b5e7151de0251b5d81d7726e54ff1fd534580a0dad3bf5b8bfe39ce14ec7545d` (11,000 items: 1,000 headers + 10,000 cards).
- Day-oracle slices: `2023-01-01` (11 items) `7432b2796ab8de33c6c6bbf54ac86a8f81db500f7b904c813b9f5149a885511d`; `2025-09-26` (11 items) `7c055c9a0bdd0e1976b0e847fbecda5a0fc9039c643d5e2cc4c98c5ce9c10fd5`; `2022-12-31` (empty) `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

## Refresh observations

Native user display settings were `peak_refresh_rate=120` / `min_refresh_rate=null` before and after both runs; the harness never writes them.

- Rev4 (35 measured iterations): 29 pairs `120 → 120`, 5 pairs `120.00001 → 120.00001` (the two replacement blocks, read via `dumpsys display`), and 1 pair `60 → 120` at `binding01LoadA#7`. The single 60 Hz Activity-visible observation is recorded evidence; per ADR 0012 a refresh change between two positive finite observations does not invalidate the run while the frozen user settings remain unchanged.
- Rev5 post-clean rerun (35 measured iterations): 30 pairs `120 → 120` and 5 pairs `120.00001 → 120.00001`; no sub-120 observation.

Both runs' replacement iterations report the exact frozen traversal summary: `selectedDayDrawCount=1000, replacementCount=999, maxPublishedItemCount=11, prefetchQueryCount=0, firstDate=2025-09-26, lastDate=2023-01-01` (one unmeasured control + five measured per run).

## Decision

The selected-day bounded query (`occurredAt >= dayStart AND occurredAt < nextDayStart ORDER BY occurredAt DESC, id COLLATE BINARY ASC`, benchmark-shadow `occurredAt` index) and the sequential one-day-at-a-time replacement workload are the Rev5 P6 contract. The binding path queries, materializes, publishes, and draws only the selected local day; the complete fixture stays on disk.

Raw-derived frozen Rev4 baseline (authoritative; host-recomputed from `benchmark/p6-frozen/p6rev4/run-20260826T155112Z/`):

| Gate | Derived | Budget | Result |
|---|---|---|---|
| Query-to-first-correct-draw p95 | 39.26 ms (p50 20.87 ms) | ≤ 60 ms | PASS |
| Pooled replacement frame-CPU p95 | 14.99 ms | ≤ 16.67 ms | PASS |
| Replacement display-deadline jank | 37.96 % (3,755/9,890) | diagnostic (ADR 0014) | recorded, non-gating |
| Peak anonymous RSS | 122,680 KiB | ≤ 196,608 KiB | PASS |
| Selected-day traversal | exact `1000/999/11/0/2025-09-26/2023-01-01` (control + 5 measured) | exact | PASS |
| Stability | no ANR/OOM/process-death/crash/relaunch/timeout | none | PASS |

The frozen Rev4 run remains the authoritative baseline. A post-`clean` full rerun (Rev5, `benchmark/p6-frozen/p6rev5/run-20260827T153454Z/`) verified reproducibility against that baseline under the one-sided 10 % regression allowance:

| Gate | Rev4 baseline | Rev5 rerun | Regression |
|---|---|---|---|
| Query-to-first-correct-draw p95 | 39.26 ms | 42.45 ms (p50 22.07 ms) | (42.45 − 39.26) / 42.45 = 7.52 % ≤ 10 % — PASS |
| Pooled replacement frame-CPU p95 | 14.99 ms | 15.74 ms | (15.74 − 14.99) / 15.74 = 4.76 % ≤ 10 % — PASS |
| Peak anonymous RSS | 122,680 KiB | 118,196 KiB | absolute cap only — PASS |
| Replacement display-deadline jank | 37.96 % (diagnostic) | 33.26 % (diagnostic) | non-gating |
| Selected-day traversal | exact `1000/999/11/0/2025-09-26/2023-01-01` | exact `1000/999/11/0/2025-09-26/2023-01-01` (control + 5 measured) | exact — PASS |

The one-sided 10 % regression allowance applies to the p95 query and p95 frame baselines only (`new <= baseline` passes; `(new - baseline) / max(new, baseline) > 0.10` fails). Memory uses its absolute cap; deadline jank is a Rev5 diagnostic, not a regression metric.

## Consequences

- The selected-day bounded load is the M5 production Timeline loading strategy (not full materialization, keyset paging, Paging 3, or a cross-day continuous stream). M5 owns its physical production schema/index through a separately approved plan.
- 120 Hz product-experience verification is deferred to M5: M5 must re-establish the native-display deadline-jank baseline on the real `TimelineHome` with real cards and actual scroll/date-switch flows, with the absolute ≤2 % gate, and must not compare its jank against this P6 replacement measurement.
- The frozen 37.96 % replacement-jank result is retained as Rev4 FAIL / Rev5 diagnostic evidence and is never rewritten to PASS.

## Remaining risks

- **120 Hz product-experience verification deferred to M5.** The Rev4 replacement workload janked 37.96 % and the Rev5 rerun 33.26 % at the native 120 Hz deadline; both are recorded diagnostics. M5 must validate real-scroll jank on the production `TimelineHome` with the absolute ≤2 % gate and may not compare against these replacement numbers.
- **Synthetic replacement workload.** The 1,000-day traversal is a deterministic stress harness, not a production interaction; its per-frame full-day re-render (≈1.98 frames per replacement) is not comparable to real scrolling.
- **Selected-day fixture density.** `timeline-v1` has exactly ten cards per day; M5 must declare and rebaseline any materially different per-day cardinality.
- **Physical-device noise.** Non-rooted CPU frequency, heat, USB, and background work remain variability sources; the two frozen runs (39.26/14.99 ms vs 42.45/15.74 ms) bound the observed run-to-run spread and both pass the 10 % allowance.

## Verification

- Frozen Rev4 evidence: `benchmark/p6-frozen/p6rev4/run-20260826T155112Z/` (1 benchmark JSON, 1 manifest, 4 message files, 35 traces, `EVIDENCE.sha256`).
- Post-`clean` Rev5 rerun evidence: `benchmark/p6-frozen/p6rev5/run-20260827T153454Z/` (1 benchmark JSON, 1 manifest, 4 message files, 35 traces, `EVIDENCE.sha256`).
- Host recomputation (Rev4): `queryP50Us=20868 queryP95Us=39261 frameP95Us=14989 jankBp=3796 memoryMaxKib=122680`.
- Host recomputation (Rev5 rerun): `queryP50Us=22068 queryP95Us=42454 frameP95Us=15738 jankBp=3326 memoryMaxKib=118196`; frozen-report validation prints `P6 benchmark report validation: PASS` with `jankBp` as a retained diagnostic.
- Correctness: typed + rendered first/last/empty selected-day slices equal the independent oracle slices; within-day tie swap fails the shared equality assertion; published state never contains another day.
- Isolation: `checkModuleDependencies` (7 production + `[:benchmark]` test-tool + only target edge), `apiBoundaryCheck`, and release manifest/deps/APK inspection pass; schema/API snapshots byte-identical.

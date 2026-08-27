# ADR 0013: Timeline full-materialization load fails the 60 ms query budget

## Status

Accepted

## Date

2026-08-22

The user classified the clean-rerun result as a genuine technical-assumption failure and, on 2026-08-22, directed the ADR to assert exactly one conclusion and to record the rejected option and the day-bounded alternative. The same decision selected the day-bounded alternative, kept the 60 ms absolute budget, made adjacent-day prefetch optional and post-first-draw only, and rejected a cross-day keyset-paged continuous stream. The full materialization rule was the chosen PoC question (spec §4.3), so its failure is a PoC conclusion, not a harness defect. This accepts the product/architecture direction; it does not approve the still-pending P6 Rev4 spec or plan.

## Context

P6 froze a 10,000-card full-materialization load: one ordered `SELECT *` returning every card, full typed materialization, grouping into 1,000 day headers + 10,000 cards (11,000 flattened items), publication of the complete immutable UI state, then the first correct draw. The frozen query-to-first-correct-draw budget is a nearest-rank p95 of 30 cold-process trace durations ≤ 60 ms.

Three successive clean runs measured p95 80.2 ms, then 78.78 ms, then 75.22 ms. Each time the span was tightened before re-measuring, so the residual is not explained by the harness. The final run (2026-08-22) fixed every known measurement-contract defect and re-verified on device:

- `handle.close()` (Room connection-pool / invalidation-tracker teardown) moved out of the span, strictly after `gate.end()`.
- `gate.end()` runs directly inside the first correct `drawBehind` callback on the same main thread as `gate.start()`, not at a later coroutine-resumption scheduling point.
- The finer `P6:RoomQuery` and `P6:ProjectionGrouping` markers were device-verified from the frozen Perfetto traces.

Result: **p50 59.78 ms / p95 75.22 ms**, still over the 60 ms budget. Marker attribution over all 30 load samples:

| Phase | median | p95 |
|---|---|---|
| `P6:RoomQuery` (SQLite `SELECT *` scan + sort) | 20.44 ms | 27.25 ms |
| `P6:ProjectionGrouping` (10,000 rows → 11,000 items) | 8.16 ms | 13.94 ms |
| Query + materialization (sum) | 30.44 ms | 34.45 ms |

The markers prove that the query + materialization phase alone costs a measured ~30 ms at the median, and the remaining span (vsync alignment plus first-frame composition/layout/draw) pushes the p95 to 75.22 ms. The full-materialization shape therefore cannot meet 60 ms on this reference device.

Product truth for the alternative comes from MVP Journey 8 ([mvp-requirements.md](../../product/mvp-requirements.md) — "Browse a mixed day timeline"): the user selects one day and sees that day's transactions and notes together, then moves between days. The product is a selected-day timeline, not a cross-day continuous stream.

## Options considered

- **Full materialization of all 10,000 cards (rejected).** This was the frozen PoC shape. Its measured p95 of 75.22 ms exceeds the 60 ms budget on the reference device, and the marker-attributed query + materialization cost (~30 ms median) is a direct consequence of selecting and grouping every row.
- **Selected-day bounded query (chosen).** Query only the selected day's cards with the half-open predicate `occurredAt >= dayStart AND occurredAt < nextDayStart`. Materialize, publish, and draw only that day's state. This matches Journey 8's selected-day product truth and removes the full-table scan from the measured load. Adjacent-day prefetch is not part of the required path: if a later approved implementation uses it, it may begin only after the selected day's first correct draw, must remain asynchronous and non-blocking, must not enter the 60 ms trace, and must never publish adjacent-day content into the current-day state.
- **Database-level keyset paging over a cross-day continuous stream (not chosen).** Current product requirements do not define a cross-day continuous stream. Adding keyset paging, Paging 3, or an infinite cross-day surface would change the approved interaction rather than merely optimize it. Any future reconsideration requires a new product decision and the normal specification lifecycle; P6 and M5 cannot infer it from this performance failure.

## Decision

The frozen 10,000-card full-materialization loading strategy fails the 60 ms query-to-first-correct-draw p95 budget on the reference device (Xiaomi 14 Pro, Android 16, native `peak=120`/`min=null`). Full materialization is rejected as the production timeline loading strategy.

P6 Rev4 replaces the full-materialization rule (§4.3) with a selected-day bounded query matching Journey 8: derive `dayStart` and `nextDayStart` from the selected `LocalDate` in the frozen `Asia/Shanghai` ZoneId, execute exactly `occurredAt >= dayStart AND occurredAt < nextDayStart`, materialize and group only that day, publish only that day's UI state, and draw its first correct items. The database retains the complete 10,000-card fixture. Correctness is evaluated against independently derived selected-day oracle slices covering the chronological first day, chronological last day, inclusive/exclusive time boundaries, timestamp ties, and an empty day.

Adjacent-day prefetch is an optional later optimization and is absent from the Rev4 binding baseline. If introduced by a later approved implementation, it starts only after the selected day's first correct draw, cannot block `ready`, cannot enter the 60 ms metric, and cannot mix adjacent-day content into the current-day state. Rev4 keeps the **60 ms absolute query-to-first-correct-draw budget unchanged** and establishes a new workload-comparable baseline; it does not relax the user-experience target.

The decision records the measured evidence and the rejected option; it does not assert an irreducible SQLite floor, nor attribute the first-frame p95 tail to CPU/GC/JIT contention — the finer markers prove only the query + materialization phase cost, not those further causes.

## Consequences

- Criterion 5 (load protocol and budget, p95 ≤ 60 ms) is recorded FAIL for the full-materialization shape.
- P6 remains `in-progress`. P6 Rev4 must be written and explicitly approved before the day-bounded load is re-benchmarked under a new comparable baseline.
- The 60 ms absolute budget is unchanged. Rev4 establishes a new comparable baseline for the selected-day workload and never compares the rejected full-materialization p95 as though it were the same workload.
- Frame CPU, display-deadline jank, anonymous RSS, stability, correctness, and the one-sided regression allowance remain governed by their existing budgets and by the new Rev4 baseline.
- The M5 consequence (production Timeline screen) is a selected-day bounded load, not full materialization, keyset paging, Paging 3, or a cross-day continuous stream. Its physical production schema/index remains owned by the later approved M5 specification.

## Verification

- The frozen run evidence (`benchmark/p6-frozen/p6final/run-2026-08-22/`) is retained: 1 benchmark-data JSON, 1 manifest, 35 Perfetto traces, 4 message files, with SHA-256 provenance files.
- The host validator recomputed `queryP50Us=59779 queryP95Us=75218` and reported the p95 budget failure.
- The marker attribution script (`benchmark/p6-frozen/p6final/attr_p6markers.py`) reconstructs `P6:RoomQuery` and `P6:ProjectionGrouping` durations from the frozen traces and produces the median/p95 table above.

# ADR 0014: Native-display deadline-jank is deferred from the P6 replacement workload to M5's production interaction

## Status

Accepted

## Date

2026-08-27

The user reviewed the frozen Rev4 selected-day binding result and, on 2026-08-26, approved revision direction 1 in a tightened form: downgrade the P6 replacement-workload display-deadline-jank metric to recorded diagnostic evidence, and defer the ≤2 % native-display deadline-jank gate to M5's real production interaction. On 2026-08-27 the user explicitly approved the corrected written ADR, the Rev5 spec, and the Rev5 plan, authorizing execution to resume at Task 6R5-A → Task 7 → Task 8.

## Context

The frozen Rev4 selected-day binding run (`benchmark/p6-frozen/p6rev4/run-20260826T155112Z`) measured, from raw Perfetto-derived frame samples independently recomputed on the host:

| Metric | Value | Budget | Result |
|---|---|---|---|
| Query-to-first-correct-draw p95 | 39.26 ms | ≤ 60 ms | PASS |
| Pooled replacement frame-CPU p50 / p95 | 8.37 ms / 14.99 ms | p95 ≤ 16.67 ms | PASS |
| Display-deadline jank | 3,796 bp = 37.96 % (3,755/9,890 frames overrun) | ≤ 2 % | FAIL |
| Peak RssAnon | 122,680 KiB | ≤ 196,608 KiB | PASS |

The five measured replacement iterations janked 29.70 %–48.63 %. The 5,000 selected-day draws produced only 9,890 frames, ≈ 1.98 frames per replacement — at 120 Hz about 60 date replacements per second, a continuous no-pause stress rate, not a human interaction pace. The replacement traversal is, by the approved spec §4.5/§9, a "synthetic stress harness", not a production auto-advance interaction.

The ≤2 % deadline-jank budget was defined for the measured-scroll contract before the Rev3 binding run. Rev3 later measured the real user-shaped full scroll at frame-CPU p95 6.27 ms with 0.10 % jank. Rev4 replaced that scroll with back-to-back full-day state replacements that produced about 1.98 frames per replacement, so its frame distribution is not comparable to real scrolling.

The two budgets are not mathematically contradictory. `frameDurationCpuMs` p95 ≤ 16.67 ms is a loose CPU-work ceiling; `frameOverrunMs > 0` deadline jank is an independent, stricter experience gate. AndroidX explicitly recommends `frameOverrunMs` for higher and variable frame rates. The finding is therefore not "M5 scrolling will drop frames" — it is "full selected-day state replacement at 120 Hz shows a clear jank risk that M5 must validate with real interaction."

## Options considered

- **Optimize the benchmark-only continuous replacement machine (rejected).** This would optimize the stress harness, not the production interaction; and lowering frame-CPU p95 below the 120 Hz 8.33 ms deadline still would not guarantee deadline jank ≤ 2 %.
- **Raise the jank budget to a value the synthetic workload can meet, e.g. ≤ 40 % (rejected).** A budget derived after observing the failure carries no product meaning.
- **Re-derive jank against a 60 Hz deadline (rejected).** This would mask the reference device's real deadline and reverse the correct high-refresh deadline-overrun semantics established by ADR 0012.
- **Defer the ≤2 % native-deadline jank gate to M5's real interaction, and downgrade the P6 replacement jank to diagnostic (chosen).**

## Decision

This partially supersedes ADR 0012's P6-wide ≤2 % jank gate as carried into the Rev4 replacement workload by ADR 0013 and the Rev4 spec. ADR 0012 predates that replacement workload. Its native user display settings and deadline-overrun semantics remain in force.

- P6 Rev5 keeps the absolute query-to-first-correct-draw ≤ 60 ms, replacement frame-CPU ≤ 16.67 ms, anonymous-RSS ≤ 196,608 KiB, correctness, and stability gates unchanged.
- The replacement-workload display-deadline jank becomes a recorded diagnostic (non-gating) in P6 Rev5. The 37.96 % measurement is permanently retained as Rev4 FAIL/diagnostic evidence and is never rewritten to PASS.
- M5 must establish a new native-display deadline-jank baseline on the production `TimelineHome`, using production cards and actual scroll/date-switch flows, while retaining the absolute ≤2 % gate. M5 must not compare its jank against the P6 replacement measurement.
- The P6 passing result ADR is renumbered from 0014 to 0015.

## Consequences

- Criterion 7 (jank ≤ 2 %) is recorded FAIL for the Rev4 replacement workload and becomes a non-gating diagnostic in Rev5.
- 120 Hz product-experience verification is deferred to M5. This is a UI-layer risk, not a selected-day query architecture risk: the selected-day bounded query itself (p95 39.26 ms load, exact correctness) is unaffected.
- The frozen 37.96 % evidence remains the authoritative Rev4 jank record; Rev5 does not relabel it.

## Verification

- Frozen evidence: `benchmark/p6-frozen/p6rev4/run-20260826T155112Z/` (1 benchmark JSON, 1 manifest, 4 message files, 35 traces, `EVIDENCE.sha256`).
- Independent host recomputation: `queryP50Us=20868 queryP95Us=39261 frameP95Us=14989 jankBp=3796 memoryMaxKib=122680`.
- Five-round jank range 29.70 %–48.63 %; ≈ 1.98 frames per replacement.

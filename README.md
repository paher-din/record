# Android 生活记录 App

A private Android timeline connecting money with life context.

## Current status

- Tech PoC P0 completed (seven-module skeleton verified on Xiaomi 14 Pro)
- Tech PoC P1 completed (Room schema v1, typed repository surface, and machine checks verified on Xiaomi 14 Pro)
- Tech PoC P2 completed (finance matrix validation and atomic transfer verified on Xiaomi 14 Pro)
- Tech PoC P3 completed (attachment failure recovery: v1→v2 schema with recoverable-migration coordinator, zero-link attachment lifecycle, 101 device tests green)
- Tech PoC P4 completed (Chinese search selection: normalized paged LIKE chosen over FTS4 unicode61, [ADR 0009](docs/architecture/decisions/0009-search-candidate-selection.md) accepted, 1,800-sample device evidence retained)
- Tech PoC P5 completed (export/restore: non-destructive generation bootstrap per [ADR 0010](docs/architecture/decisions/0010-generation-bootstrap-and-live-session-handoff.md), complete logical ZIP backup and overwrite restore, 154 device tests green)
- Tech PoC P6 completed (timeline performance: selected-day bounded query per ADR 0013; query-to-first-correct-draw p95 39.26 ms ≤ 60 ms and frame-CPU p95 14.99 ms ≤ 16.67 ms on the Xiaomi 14 Pro; replacement-workload deadline jank 37.96 % recorded as a diagnostic, ≤2 % native-deadline jank gate deferred to M5 per ADR 0014; post-clean Rev5 rerun passed every absolute gate and the one-sided 10 % regression — ADR 0015)
- MVP M1 — app shell completed (2026-09-04: safe startup gating, typed four-tab shell, A/A1 theme, accessible shared presentations, isolated startup benchmarks, offline routes — all 19 acceptance criteria PASS with device evidence on the Xiaomi 14 Pro; startup TTID p95 ≤324.7 ms / TTFD p95 ≤422.6 ms within the approved budgets; full record in [docs/specs/mvp/m1-app-shell/verification.md](docs/specs/mvp/m1-app-shell/verification.md))
- MVP M2 — finance core specification approved and ADR 0017 accepted (2026-09-04); implementation remains inactive pending explicit plan approval and schema/migration execution consent
- Supported baseline: Android 14+

## Start here

- Project rules: [AGENTS.md](AGENTS.md)
- Documentation router: [docs/index.md](docs/index.md)
- Product vision: [docs/product/vision.md](docs/product/vision.md)
- Roadmap: [docs/roadmap.md](docs/roadmap.md)

## Historical inputs

- [Approved design spec](docs/superpowers/specs/2026-08-04-codex-first-android-record-app-design.md)
- [Deep research report](<deep-research-report (6).md>)

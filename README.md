# Android 生活记录 App

A private Android timeline connecting money with life context.

## Current status

- Tech PoC P0 completed (seven-module skeleton verified on Xiaomi 14 Pro)
- Tech PoC P1 completed (Room schema v1, typed repository surface, and machine checks verified on Xiaomi 14 Pro)
- Tech PoC P2 completed (finance matrix validation and atomic transfer verified on Xiaomi 14 Pro)
- Tech PoC P3 completed (attachment failure recovery: v1→v2 schema with recoverable-migration coordinator, zero-link attachment lifecycle, 101 device tests green)
- Tech PoC P4 completed (Chinese search selection: normalized paged LIKE chosen over FTS4 unicode61, [ADR 0009](docs/architecture/decisions/0009-search-candidate-selection.md) accepted, 1,800-sample device evidence retained)
- Next: Tech PoC P5 — export/restore (candidate)
- Supported baseline: Android 14+

## Start here

- Project rules: [AGENTS.md](AGENTS.md)
- Documentation router: [docs/index.md](docs/index.md)
- Product vision: [docs/product/vision.md](docs/product/vision.md)
- Roadmap: [docs/roadmap.md](docs/roadmap.md)

## Historical inputs

- [Approved design spec](docs/superpowers/specs/2026-08-04-codex-first-android-record-app-design.md)
- [Deep research report](<deep-research-report (6).md>)

# Record 2.0

Android本地优先生活记录应用，采用已冻结的Kotlin/Compose/Room与Koog Basic Agent方向。完整V1包括原生记录、完整Agent和安全备份；下方Record1.0的完成记录不代表R2已经实现。

- 当前工程规则与适用范围：[AGENTS.md](AGENTS.md)、[文档路由](docs/index.md)。
- 产品权威：[统一架构R1](docs/architecture/record2-v1-unified-architecture.md)、[V1 Spec](docs/specs/record2/v1/spec.md)、[实施Plan](docs/specs/record2/v1/plan.md)；批准与实际执行范围见Plan §13、§16–20，B0审查包见[数据模型设计](docs/architecture/record2-b0-data-model-design.md)，Schema v1真实磁盘约束结果见Plan §20.2。
- 最小来源资料：[已确认决定/验收与C1身份](docs/product/record2-v1-decision-baseline.md)。文档提交可重建性与C1外部源码可重取性分别验证，后者尚未核实。
- 单仓库、双独立构建：[record1/](record1/)保留旧工程，[record2/](record2/)为R2单`:app`最小基线。R1搬迁/原门禁、A1及G3在本次交付范围的结果见Plan §17；这些结果不代表完整V1已实现。
- 两工程均用JDK17验证；在构建进程的`JAVA_HOME`或各自IDE工程中选择兼容JDK。使用选中工程自己的wrapper及显式`-p`，如`record1/gradlew.bat -p record1 check`、`record2/gradlew.bat -p record2 check`（从仓库根调用）。仓库根没有Gradle工程，docs/保留原位，shared/未创建。

## Record 1.0历史README（原文保留，不是R2发布门禁）

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

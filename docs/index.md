# Documentation router

## Record 2.0

Read [root rules](../AGENTS.md), then the [single R2 Engineering Policy](engineering/record2-engineering-contract.md) and the sources relevant to the task.

| Work | Source |
|---|---|
| Product/architecture | [Frozen unified R1](architecture/record2-v1-unified-architecture.md), [V1 Spec](specs/record2/v1/spec.md) |
| Dependencies/authorization/actual execution state | [V1 Plan](specs/record2/v1/plan.md), including product approval §13 and governance flow §14 |
| G3 legacy machine constraints / A1 isolation checklist | [Static isolation audit](engineering/record2-g3-machine-isolation.md); task authorization and two-part acceptance status belong to V1 Plan §15 |
| A0 build structure / identity / asset admission | [Engineering design review package](architecture/record2-a0-engineering-design.md); task authorization and actual state belong to V1 Plan §16 |
| B0 data model / CAS / durable history | [Data model review package](architecture/record2-b0-data-model-design.md), [ADR 0018](architecture/decisions/0018-record2-data-concurrency-and-history.md); design review belongs to V1 Plan §19, Schema authorization/results to §20 |
| B1 seeds / finance services / native draft persistence | [V1 Plan §21](specs/record2/v1/plan.md#21-b1种子基线与收支草稿服务实施), including approved seed content, execution boundaries and actual verification |
| B2 Note autosave / CAS / soft deletion | [V1 Plan §22](specs/record2/v1/plan.md#22-b2note自动保存cas与软删除issue-5), including issue #5 scope, fault selectors and device coordination |
| B3 bidirectional links / hidden relations / versions | [V1 Plan §26](specs/record2/v1/plan.md#26-b3双向关联隐藏关系与版本并发issue-6), including issue #6 scope, selectors, process and failure evidence |
| B4 continuous timeline / search / deterministic aggregation | [V1 Plan §29](specs/record2/v1/plan.md#29-b4连续时间线真实检索与确定性汇总issue-7), including issue #7 scope, cursor/order decisions, selectors, performance proposal and evidence |
| Complete logical ZIP / generation restore | [G0 design](architecture/record2-g0-backup-restore-design.md), [Accepted ADR 0019](architecture/decisions/0019-record2-logical-backup-restore-protocol.md); G0 approved 2026-10-25 in [V1 Plan §31.7](specs/record2/v1/plan.md#317-负责人最终批准与-adr-accepted2026-10-25); [G1 core implemented 2026-10-25](specs/record2/v1/plan.md#35-g1-核心实现与验证2026-10-25) with [API34 evidence](engineering/evidence/2026-10-25-g1.md); RestoreCoordinator/E2 restore-revocation seam and receipt identity (A→B, fresh same-backup, malformed-receipt rescue) added; max-package resource, real SAF/DocumentsUI, process-kill matrix and F1/F2/G2 still pending; issue #19 OPEN |
| D1 real Record Tools / four-tool registry / budgets / handoff classification | [V1 Plan §33](specs/record2/v1/plan.md#33-d1真实-record-tools-与完整-agent-行为issue-14), including issue #14 scope, Koog tool-error semantics, request-boundary guard, dedicated-AVD coordination, real-DeepSeek completion, PR #30 review fixes (hard run timeout, max-iteration classification, persisted-run request guard, production usage ledger/UsageUnknown, streaming rejection, poisoned-tool entry guard, catalog hard-failure poisoning) and the remaining C1/F2/E2/G2 boundaries |
| Provider authorization / BYOK / Keystore | [V1 Plan §23](specs/record2/v1/plan.md#23-e0-授权凭据与发送接缝), including the controlled receiver identity, initialization ownership, authorization state matrix, actual Keystore/Room/device evidence and remaining D0/D1/F2 responsibilities |
| Run invalidation / cancellation / session deletion / process recovery | [V1 Plan §32](specs/record2/v1/plan.md#32-e2--issue-13run-失效取消会话删除及进程恢复), including the application-level owner, atomic authorization invalidation, the B0 §8 deletion transaction and real process-death evidence; [evidence summary](engineering/evidence/2026-10-25-e2.md) |
| D1+E2 combined integration | [V1 Plan §34](specs/record2/v1/plan.md#34-d1e2-集成与组合回归2026-10-25), [integration evidence](engineering/evidence/2026-10-25-d1-e2-integration.md); merged in [PR #32](https://github.com/paher-din/record/pull/32) (main `4f92878`); G2 not started |
| Product and acceptance provenance/C1 identity | [Self-contained decision baseline](product/record2-v1-decision-baseline.md) |
| UI | Spec §5.4 / Plan F0–F2；[F0 授权/审阅状态 §30](specs/record2/v1/plan.md#30-f0设计指导与候选审阅issue-15)、[F0-C3 六组完整审阅包](design/record2-f0-c3-review.md)、[执行契约差异](design/record2-f0-c3-contract-delta.md)，以负责人对应组批准为准；旧 C1/C2 保留历史 |
| Collaboration, task/risk levels, permissions, verification | Engineering Policy; per-task commands/results in the relevant Plan/Verification |

Approval belongs to Spec metadata/Plan approval record, not a duplicated router table. No product task or Spec is activated by adopting governance. Multiple independent Majors are coordinated by write/contract/resource ownership; the router's current focus is not a global lock.

R2 V1 is an approved Major; current task execution status remains in the Spec/Plan and is not changed by this router. Design/documentation needs its task permission, not functional activation. Source baseline admission and external C1 reproducibility limits must be read before asset reuse; historical local paths/shared links are not implementation prerequisites.

## Record 1.0 and history

The R1 Android build root is [record1/](../record1/), with its own wrapper and settings; shared documentation remains here. R2 has a separate build root and does not join the R1 module graph.

For explicitly scoped legacy work, read [preserved R1.0 contract](../AGENTS.record1.md), then the legacy owner: [product](product/mvp-requirements.md), [experience](experience/interaction-rules.md), [architecture](architecture/overview.md), [data](architecture/data-and-storage.md), [engineering](engineering/conventions.md), [testing](engineering/testing-strategy.md), [security](engineering/security-and-privacy.md), [workflow](engineering/agentic-workflow.md), [roadmap](roadmap.md).

Those sources, completed Spec/Plan/ADR/Verification and old checks preserve history; they do not automatically govern R2. Phase1B/1C-A/AI source decisions required by current V1 are carried by the admitted decision baseline, with original source identities and limitations. Do not change historical PASS/FAIL, approval or data to fit new policy.

## Source ownership

Spec owns behavior/AC, Plan owns task dependencies/scope, Verification owns actual results, Policy owns R2 execution methods. Stable changes update the correct owner and need relevant review; a skill/generated plan cannot supply approval. Major decisions use ADR where long-term architecture/compatibility/recovery/security requires it, not for every patch/failure.

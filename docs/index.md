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
| Provider authorization / BYOK / Keystore | [V1 Plan §23](specs/record2/v1/plan.md#23-e0-授权凭据与发送接缝), including the controlled receiver identity, initialization ownership, authorization state matrix, actual Keystore/Room/device evidence and remaining D0/D1/F2 responsibilities |
| Product and acceptance provenance/C1 identity | [Self-contained decision baseline](product/record2-v1-decision-baseline.md) |
| UI | Spec §5.4 / Plan F0–F2, the owner's relevant design approval |
| Collaboration, task/risk levels, permissions, verification | Engineering Policy; per-task commands/results in the relevant Plan/Verification |

Approval belongs to Spec metadata/Plan approval record, not a duplicated router table. No product task or Spec is activated by adopting governance. Multiple independent Majors are coordinated by write/contract/resource ownership; the router's current focus is not a global lock.

R2 V1 is an approved Major; current task execution status remains in the Spec/Plan and is not changed by this router. Design/documentation needs its task permission, not functional activation. Source baseline admission and external C1 reproducibility limits must be read before asset reuse; historical local paths/shared links are not implementation prerequisites.

## Record 1.0 and history

The R1 Android build root is [record1/](../record1/), with its own wrapper and settings; shared documentation remains here. R2 has a separate build root and does not join the R1 module graph.

For explicitly scoped legacy work, read [preserved R1.0 contract](../AGENTS.record1.md), then the legacy owner: [product](product/mvp-requirements.md), [experience](experience/interaction-rules.md), [architecture](architecture/overview.md), [data](architecture/data-and-storage.md), [engineering](engineering/conventions.md), [testing](engineering/testing-strategy.md), [security](engineering/security-and-privacy.md), [workflow](engineering/agentic-workflow.md), [roadmap](roadmap.md).

Those sources, completed Spec/Plan/ADR/Verification and old checks preserve history; they do not automatically govern R2. Phase1B/1C-A/AI source decisions required by current V1 are carried by the admitted decision baseline, with original source identities and limitations. Do not change historical PASS/FAIL, approval or data to fit new policy.

## Source ownership

Spec owns behavior/AC, Plan owns task dependencies/scope, Verification owns actual results, Policy owns R2 execution methods. Stable changes update the correct owner and need relevant review; a skill/generated plan cannot supply approval. Major decisions use ADR where long-term architecture/compatibility/recovery/security requires it, not for every patch/failure.

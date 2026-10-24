# Record 2.0 product context

<!-- impeccable:product-schema 1 -->

## Platform

android

## Product Purpose

面向日常个人收支与文字记录，把 CNY 收支、无标题纯文本笔记和双向关联放入连续时间线；可选择由 Koog Agent 辅助查询、整理与准备提案。原生功能在无 AI 时完整可用。

## Stack

Kotlin、Jetpack Compose、Room、Koog。实际版本以 [version catalog](gradle/libs.versions.toml) 和 [app build](app/build.gradle.kts) 为准；Material 3 尚未加入正式依赖。

## Capabilities and Constraints

本文件仅为设计 Skill 提供上下文入口，不建立新的产品、批准或执行权威。行为以 [V1 Spec](../docs/specs/record2/v1/spec.md)、[冻结架构](../docs/architecture/record2-v1-unified-architecture.md) 为准；实际状态、任务批准归 [V1 Plan](../docs/specs/record2/v1/plan.md)，工程规则归 [Policy](../docs/engineering/record2-engineering-contract.md)。

Room/业务服务决定事实，只有持久提交结果才是成功。当前已批执行基线仍是 prepare/原生确认；负责人新增了明确指令直接执行、非必要不问、必要补问用简短任务弹窗的产品要求，具体变更设计见升级方案 §13，正式契约/验收及实现尚须同步。保留 Long 整数分、CAS、D5 草稿、Note 自动保存、授权和恢复边界。当前正式 Host 为空，业务内核不等于产品页面已完成。无账户余额、预算、统计大盘、附件、Markdown 或云同步的 V1 授权。

## Brand Commitments

产品名为 Record 2.0。负责人要求作品级视觉品质、Android 原生表现力和长期日用体验，并已开放原配色与首页架构。当前设计方向为 D 的黑白/朱红/精密排版首页，标题旁有手动记账/笔记入口；底部搜索栏式 Agent 入口展开 N 式面板。首页显示简短摘要，面板以完整记录与执行结果为中心，保留统一自然语言输入，不设记账/笔记/问 AI 模式切换，也不模拟冗长真人聊天。必要问题用紧凑任务弹窗，主区保持低干扰。此处仅路由当前设计范围，不批准具体图稿、社交功能、数据模型或业务权限变化；指导原话及批准事实归 Plan §28.1。

## Evidence on Hand

[F0-C3 完整设计审阅包](../docs/design/record2-f0-c3-review.md)拥有当前六组设计内容与精确 Token；[高级 UI/UX 研究与探索](../docs/design/record2-uiux-upgrade-proposal-20261024.md)保留调研/方向过程；此前 [F0-C1/C2](../docs/design/record2-f0-design-review.md)为未通过/未选历史，不是实施基线。批准状态只读 Plan §28；相应设计组和执行范围就绪后才能开始正式 UI 实现。

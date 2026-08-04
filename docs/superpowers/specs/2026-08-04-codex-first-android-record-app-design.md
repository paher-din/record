# Codex-first Android 生活记录 App 项目设计

状态：已批准<br>
日期：2026-08-04<br>
适用范围：项目文档体系、Tech PoC 与 MVP<br>
输入依据：[开源技术调研与最终决策报告](<../../../deep-research-report (6).md>)

## 1. 目的

本项目面向个人使用 Codex 持续开发。文档体系的首要目标不是展示，而是让 Codex 在长周期开发中：

- 读取最小且足够的上下文；
- 区分稳定决策与阶段性实现；
- 在产品、架构、规格和代码之间保持一致；
- 用自动化验证保护财务数据、附件和备份；
- 避免 V1、V2 的未验证设想污染 Tech PoC 与 MVP。

首版产品只验证一个命题：用户是否愿意把“花了什么钱”与“当时发生了什么”放进同一条本地、私密、可迁移的生活时间线。

本文件是创建项目核心文档的设计依据。核心文档生成并通过验证后，本文件转为只读历史规格；届时产品、架构、体验和工程事实分别由第 3 节定义的核心文档负责，本文件不再作为日常事实源。

## 2. 已确定的项目边界

### 2.1 产品与平台

- 原生 Android App，最低支持 Android 14，即 `minSdk 34`。
- Kotlin、Jetpack Compose、Coroutines、Flow、ViewModel、Room 2.8.4、WorkManager、Photo Picker 与 SAF。
- 本地优先；核心功能在无网络权限时完整可用。
- 不 Fork 完整开源 App；开源项目仅作为领域行为、交互和边界案例参考。
- 默认首页是“今日时间线”。
- 顶层页面通过固定底部导航切换，不通过纵向翻页跨领域。
- MVP 成功标准是连续 30 天真实记录，并能可靠关联、搜索、导出、清空后完整恢复。

### 2.2 MVP 包含

- 账户与期初余额；
- 收入、支出、同币种转账；
- 一级分类；
- 统一标签；
- 基础 Markdown 笔记；
- 账单与笔记多对多关联；
- 每条账单或笔记最多一个主项目；
- 图片附件；
- 日时间线；
- 统一搜索；
- 月度收支与分类统计；
- 完整 ZIP 导出与覆盖恢复；
- 交易 CSV 导出；
- 从系统分享的文字或图片生成草稿。

### 2.3 MVP 不包含

- 预算、周期交易、任务与提醒；
- 子分类；
- 跨币种转账与汇率历史；
- 任意文件附件；
- OCR、AI、语音转写；
- SQLCipher 默认配置；
- 云备份、记录级同步、实时多设备或协作；
- 通用任意实体关系；
- 完整复式会计科目体系；
- iOS、Kotlin Multiplatform、Flutter 或 React Native 客户端。

## 3. 文档信息架构

```text
D:\record
├── AGENTS.md
├── README.md
├── deep-research-report (6).md
└── docs/
    ├── index.md
    ├── product/
    │   ├── vision.md
    │   └── mvp-requirements.md
    ├── architecture/
    │   ├── overview.md
    │   ├── domain-model.md
    │   ├── data-and-storage.md
    │   └── decisions/
    ├── experience/
    │   ├── information-architecture.md
    │   └── interaction-rules.md
    ├── engineering/
    │   ├── conventions.md
    │   ├── testing-strategy.md
    │   └── security-and-privacy.md
    ├── roadmap.md
    └── specs/
        ├── tech-poc/
        └── mvp/
```

### 3.1 文档职责

| 文档 | 唯一职责 | 不应包含 |
|---|---|---|
| `AGENTS.md` | Codex 必读顺序、操作边界、工程纪律、验证与文档同步规则 | 产品需求和阶段任务 |
| `README.md` | 项目定位、当前阶段、快速导航 | 架构事实和重复规范 |
| `docs/index.md` | 按任务类型路由到最小必读文档集 | 大段产品或技术内容 |
| `product/vision.md` | 用户、核心命题、目标、非目标、30 天成功标准 | Room、DAO、组件选型 |
| `product/mvp-requirements.md` | 用户旅程、业务规则、功能验收 | 实现步骤和文件清单 |
| `architecture/overview.md` | 模块、依赖方向、跨模块数据流 | 页面细节和任务状态 |
| `architecture/domain-model.md` | 实体、关系、金额规则、领域不变量 | UI 布局和实现计划 |
| `architecture/data-and-storage.md` | Room、附件、搜索、导入导出、迁移与恢复 | 产品路线图 |
| `architecture/decisions/*` | 关键决策的背景、选项、结论和后果 | 日常小改动日志 |
| `experience/information-architecture.md` | 顶层导航、页面层级、入口与返回关系 | 数据表结构 |
| `experience/interaction-rules.md` | 创建、编辑、关联、失败、恢复等交互规则 | Repository 实现 |
| `engineering/*` | 编码、依赖、测试、安全和隐私底线 | 功能需求复述 |
| `roadmap.md` | 阶段目标、进入条件、退出条件 | 细粒度任务列表 |
| `specs/*` | 单个切片的可执行需求、计划与验证 | 对核心决策的隐式覆盖 |
| 调研报告 | 许可证、候选项目和技术证据 | 日常任务的事实源 |

### 3.2 Codex 读取路由

每次任务先读取 `AGENTS.md` 和 `docs/index.md`。

- 普通功能任务：当前 active spec → spec 引用的产品、体验和架构文档。
- 架构变更：相关架构事实源 → 现有 ADR → 当前 spec。
- 产品范围变更：`product/vision.md` → `product/mvp-requirements.md` → 当前 spec。
- 数据可靠性任务：`domain-model.md` → `data-and-storage.md` → `testing-strategy.md`。
- 开源复用或许可证核验：相关核心结论 → 调研报告中的对应证据。

Codex 不应默认读取整份调研报告，也不应为普通功能任务加载全部项目文档。

### 3.3 冲突处理

一项决策只能有一个文档所有者，其他文档通过链接引用，不复制正文。

发现规格、计划、代码或测试与核心文档冲突时：

1. 判断冲突属于产品、架构、体验还是工程规范；
2. 更新该领域的事实源；
3. 关键决策发生变化时新增 ADR，并标记被取代的 ADR；
4. 更新受影响的规格与计划；
5. 最后修改代码与测试。

Codex 不得在冲突未显式解决时自行选择一个版本继续实现。

## 4. 产品信息架构

### 4.1 顶层导航

固定底部导航包含四个顶层目的地：

1. 时间线；
2. 财务；
3. 笔记；
4. 项目。

全局 `+` 提供支出、收入、转账和笔记四种创建入口。全局搜索与设置位于顶层页面的应用栏。

切换底部导航时保留各页面的滚动位置、筛选条件和当前日期。交易编辑、笔记编辑、附件预览和恢复向导隐藏底部导航。

### 4.2 时间线原则

- 时间线是默认首页，按日期聚合交易与笔记。
- 统一的是呈现、搜索和关联体验，不是领域模型。
- 不提供含糊的“通用记录”类型。
- 点击卡片进入原始交易或笔记详情；修改必须经过原领域入口。
- 时间线和统一搜索均为可重建投影，不是事实表。

### 4.3 MVP 使用闭环

```text
记录 → 显式关联 → 时间线回顾 → 搜索或统计 → 完整导出与恢复
```

## 5. 系统架构

### 5.1 Gradle 模块

```text
:app
├── :feature:finance
├── :feature:notes
├── :feature:timeline
├── :core:model
├── :core:data
└── :core:ui
```

| 模块 | 职责 |
|---|---|
| `:app` | Application、导航、依赖注入、主题、Deep Link、分享入口 |
| `:feature:finance` | 账户、交易、分类、余额与基础统计 |
| `:feature:notes` | Markdown 笔记、图片与账单关联交互 |
| `:feature:timeline` | 时间线、项目聚合与统一搜索结果 |
| `:core:model` | 纯 Kotlin 领域 ID、Money、枚举和公共值对象 |
| `:core:data` | Room、Repository、事务、附件、搜索、迁移、导入导出 |
| `:core:ui` | Design System、金额输入、日期组件与通用状态 |

feature 模块之间禁止直接依赖。所有 feature 只能依赖所需的 core 模块。`:feature:timeline` 只能读取聚合查询和投影，不能成为财务或笔记的写入入口。

只有满足以下至少一项时才建立 Use Case：

- 跨多个聚合并要求原子性；
- 被多个 ViewModel 复用；
- 包含必须集中维护的领域不变量。

首批合理的 Use Case 包括转账、账单与笔记关联、删除并清理关系、完整导出和覆盖恢复。禁止建立“一张表一个 Repository、一条 DAO 一层 Use Case”的机械分层。

### 5.2 状态与数据流

```text
Compose UI
  → Event
ViewModel
  → Repository 或必要的 Use Case
  → Room 事务与受控文件操作

Room Flow 或查询投影
  → Repository
  → StateFlow<UiState>
  → Compose UI
```

UI 不直接访问 DAO、Room Entity 或附件物理路径。ViewModel 暴露不可变 `StateFlow<UiState>`，并在保存完成前保持草稿状态。

## 6. 领域模型与不变量

### 6.1 核心模型

- `Transaction`、`Note` 和 `Project` 是独立领域实体。
- 三类实体各有一个同 ID 的 `EntityRef`，提供统一身份、时间戳与软删除状态。
- `EntityTag` 和 `AttachmentLink` 通过 `EntityRef` 建立真实外键。
- 账单与笔记使用专用的 `TransactionNoteRelation` 多对多关系。
- `Transaction` 和 `Note` 各自最多保存一个 `projectId` 作为主项目。
- MVP 不创建通用 `EntityRelation`；出现经过验证的第二类长尾关系后再通过 ADR 引入。

### 6.2 财务聚合

`Transaction` 是业务事件头，`AccountEntry` 是账户流水，`CategoryAllocation` 是收入或支出的分类分摊。

必须满足：

- 金额只以整数小单位保存，禁止使用 `Float` 或 `Double`；
- 支出账户流水为负，收入账户流水为正；
- 分类分摊保存正的金额绝对值；
- 收入或支出的分类分摊总额等于交易金额绝对值；
- 同币种转账恰有两条账户流水且代数和为零；
- 转账不创建分类分摊；
- 期初余额创建类型为 `OPENING_BALANCE` 的交易，不直接写账户余额；
- 账户余额始终可由全部有效 `AccountEntry` 重算；
- 交易、流水、分摊和关系必须在同一个 Room 事务中提交；
- MVP 不宣称完整复式记账。

### 6.3 标识与删除

- 主键使用应用生成的随机 UUID 字符串。
- Transaction、Note、Project 先软删除，物理清理由回收或维护流程执行。
- Account 和 Category 存在历史引用时只能归档。
- 父实体物理删除时级联删除关系记录。
- 附件仅在引用数为零且超过清理宽限期后物理删除。

## 7. 持久化、附件、搜索与恢复

### 7.1 Room

- MVP 数据库基线为 Room 2.8.4。
- Room schema JSON 纳入版本控制。
- 所有版本变化使用显式 Migration。
- 生产构建禁止 `fallbackToDestructiveMigration()`。
- 数据库 schema 版本与逻辑备份格式版本相互独立。

### 7.2 附件状态流

```text
Photo Picker 或 SAF
→ 复制到临时文件
→ 校验大小、MIME 和可解码性
→ 计算 SHA-256
→ 原子移动到内容寻址路径
→ Room 事务写 Attachment 与 AttachmentLink
```

数据库提交前发生崩溃时，最多留下可被后台清理的孤儿文件。数据库不得出现已经可见但物理文件不存在的附件记录。

### 7.3 搜索

搜索使用可重建的 `SearchDocument` 投影。领域写入与相关投影更新处于同一个 Room 事务，投影永远不能反向修改领域表。

Tech PoC 使用 10,000 条中英文混合交易和笔记比较 FTS4 `unicode61` 与规范化投影表 `LIKE`：

- 两者均满足正确性时选择响应时间更稳定者；
- FTS4 中文结果不完整时，MVP 使用分页 `LIKE`；
- 不为搜索引入服务器，也不因搜索单独迁移到 Room 3；
- 最终选择通过 ADR 固化。

### 7.4 备份

完整备份是逻辑 ZIP，包含 `manifest.json`、版本化 JSONL、附件及附件哈希。CSV 仅用于外部分析，不作为恢复事实源。

生成流程：

```text
一致性读取
→ 流式写 JSONL 和附件
→ 逐文件计算 SHA-256
→ 最后写 manifest
→ 原子发布完成的 ZIP
```

未完成的临时包不进入可恢复备份列表。

### 7.5 覆盖恢复

覆盖恢复在验证通过前不修改当前数据：

1. 读取 manifest；
2. 校验格式版本、路径安全、文件大小和全部 SHA-256；
3. 将数据库与附件导入新的 staging generation；
4. 验证余额、外键、关系、记录数量和附件哈希；
5. 保留当前 generation 作为回滚点；
6. 原子更新 active generation 指针；
7. 新 generation 成功启动后再延迟清理旧 generation。

数据库文件使用 generation ID 区分，附件存放在 `files/vaults/<generationId>/attachments/`。active generation 指针采用具备原子落盘语义的本地偏好存储。恢复在任何阶段失败都继续使用原 generation，不允许部分恢复成功。

MVP 不同步正在使用的 SQLite/WAL 文件，不把 Android Auto Backup 作为主要备份承诺，也不默认启用 SQLCipher。

## 8. 错误处理

`:core:data` 将 Room、文件、ZIP、权限和系统异常映射为稳定的应用错误类型，底层异常不能直接进入 UI。

错误类别及行为：

| 类别 | UI 与重试行为 |
|---|---|
| 字段或业务校验 | 定位到具体字段；不提交；不自动重试 |
| 可恢复 I/O | 保留草稿；允许用户重试或重新选择文件 |
| 空间不足或权限丢失 | 阻断当前操作并说明恢复方法；不盲目重试 |
| 备份损坏或版本不兼容 | 拒绝恢复并保持当前 vault 不变 |
| 财务事务失败 | 整体回滚；UI 不显示成功状态 |
| 后台瞬时失败 | 仅对幂等操作使用退避重试 |
| 未预期错误 | 保留安全状态，生成脱敏本地诊断记录 |

本地日志只记录错误类型、处理阶段和匿名操作 ID，不记录金额、商户、笔记正文、附件内容、密钥或可识别文件路径。诊断包只能由用户主动导出。MVP 不接入商业分析或崩溃追踪 SDK。

## 9. 测试与发布门槛

### 9.1 自动化测试矩阵

- 领域单元测试：金额、符号、分类分摊、转账、余额重算和软删除。
- 属性测试：随机交易序列后账户余额始终等于有效流水之和。
- Room 集成测试：Repository 事务、外键、级联和并发写入。
- Migration 测试：至少覆盖 N-1、N-2 到当前版本。
- 备份往返测试：导出、清空、恢复后逐实体、关系和附件哈希一致。
- 故障注入：附件复制、原子移动、数据库提交、ZIP 写入和 generation 激活中断。
- 搜索基准：10,000 条中英文混合数据的结果正确性和响应时间。
- ViewModel 测试：加载、成功、空状态、校验错误和基础设施错误。
- Compose UI 测试：支出、笔记、双向关联、时间线、导出恢复五条关键流程。
- 性能测试：长时间线、图片缩略图、聚合统计和冷启动。

### 9.2 阶段门槛

- Tech PoC 未通过原子转账、中文搜索、附件故障恢复、备份往返和时间线性能验证，不进入 MVP。
- MVP 发布前必须通过全部迁移与恢复测试，并完成连续 30 天真实使用。
- 数据丢失、余额不可重算、孤儿关系或备份无法恢复均为发布阻断项。
- Release 构建不得包含广告、分析或闭源跟踪 SDK。

## 10. 交付路线

### 10.1 Tech PoC

1. P0：Android 14+ 工程骨架与七模块依赖验证；
2. P1：EntityRef、Transaction、AccountEntry、Note 和强类型关系的最小 Room schema；
3. P2：原子转账与金额不变量；
4. P3：附件导入、崩溃注入、去重与孤儿清理；
5. P4：10,000 条混合数据的中文搜索方案选择；
6. P5：逻辑导出、清空恢复、逐表和附件哈希比对；
7. P6：混合卡片时间线性能验证。

PoC 失败时先调整架构事实源并记录 ADR，不能通过绕过验证继续堆叠 UI。

### 10.2 MVP 垂直切片

1. M1 App Shell：固定底部导航、主题、导航和通用状态；
2. M2 财务核心：账户、期初余额、收支、同币种转账、一级分类；
3. M3 笔记核心：Markdown、图片草稿和标签；
4. M4 融合关系：账单—笔记双向关联和单一主项目；
5. M5 找回与回顾：时间线、统一搜索、月度与分类统计；
6. M6 数据所有权：完整 ZIP、覆盖恢复、CSV 和分享生成草稿；
7. M7 Hardening：迁移、故障注入、性能和隐私检查；
8. M8 Dogfood：连续 30 天真实使用和问题闭环。

V1 与 V2 只在 `roadmap.md` 保留候选边界，不提前创建接口、数据库字段或空壳模块。

## 11. 规格生命周期

每个 Tech PoC 或 MVP 切片拥有独立目录，包含：

- `spec.md`：要达到的行为、边界和验收标准；
- `plan.md`：实施顺序、预计修改文件和验证命令；
- `verification.md`：实际执行命令、结果、偏差和剩余风险。

状态流：

```text
draft → approved → in-progress → completed
```

- `draft`：正在澄清，禁止实施；
- `approved`：用户已确认，可以生成实施计划；
- `in-progress`：当前唯一执行中的切片；
- `completed`：验收通过后只读保留，后续修正通过新规格或 ADR 追踪。

同一时间只允许一个 `in-progress` 切片。切片外的新想法进入 roadmap 候选区；改变核心产品或架构边界的新需求必须重新经过设计确认。

## 12. 设计完成标准

本设计满足以下条件：

- 项目文档具有明确层级、所有权和读取路由；
- 产品边界、首页结构和底部导航已经确定；
- 模块依赖、数据流和领域不变量已经确定；
- 附件、搜索、备份与恢复具备失败安全设计；
- 错误处理和测试矩阵覆盖主要数据风险；
- Tech PoC 与 MVP 已拆成可独立规划和验证的切片；
- V1/V2 不进入当前实现范围。

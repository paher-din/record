# Record 2.0 V1 Implementation Spec — R1

- **Status:** `in-progress`；revision `R1`；2026-10-23（Asia/Shanghai）。目录重构/A1、B0/Schema v1及B1收支业务内核已交付，精确授权/结果见Plan §17、§19–21；[B1实际结果](plan.md#212-b1实际交付与验证)。最终UI及其余产品任务未由本批启动，不代表完整M1/V1。
- **Spec approved:** 2026-10-23。**Plan approved:** 2026-10-23；负责人原话与批准范围见 [Plan §13](plan.md#13-r1正式批准记录2026-10-23)。**Schema / migration consent:** 仅Plan §20初始Schema及专用合成磁盘库验证获批；迁移/恢复未授权。
- **产品执行授权：** Spec/Plan批准不启动A0/B0/F0或功能实施；工程、代码、Schema及外部执行仍须对应任务/设计/对象授权。治理流程修订见§10，不改变产品批准或执行状态。
- **架构基线：** [统一架构 R1](../../../architecture/record2-v1-unified-architecture.md)，本地 HEAD `57e1b68b309b0459aed1765778b7da4cdfff6f6b`；文件 SHA-256 `5de8670fc60a7226d5e30f409be62f35ded55da5d78353d4fb81bd035fe9cefa`，本轮实测相符。
- **UI 决策权限：** 所有界面、图标、视觉资源、布局、动效及尚未确认的交互方案均由负责人指导、调整并明确批准。本文只规定功能、数据流与安全结果；未经设计批准的界面不能完成最终验收。
- **工程方法：** 按[R2 Engineering Policy](../../../engineering/record2-engineering-contract.md)进行任务分级、协作与风险验证。原编制借鉴to-spec/to-tickets和旧workflow的记录只属历史，不构成固定Skills流水线、tracker发布或R2派工要求。

## 1. 用户问题、完整范围与继承关系

用户需要把收入、支出与独立文字记录可靠地放在一起，跨日期回看和关联，并通过自然语言创建、改错、查找与归纳。AI 只是增强入口；不启用 AI、没有 Key 或没有网络时，原生记录和恢复能力仍须完整可用。财务事实由本地确定性服务产生，模型回答不能成为事实或提交证明。

### 1.1 V1 必交用户结果

| 领域 | 完成行为 |
|---|---|
| 收支 | CNY 收入/支出、Long 整数分、发生时间、可选说明及至多一个可选分类；默认一个可见可改名的“日常账户”；新建与同身份编辑 |
| Note | 独立无标题纯文本、保留换行和符号、自动保存与重开编辑；原生软删除、撤销、专用恢复入口，保留原身份和隐藏关系 |
| 关联 | 双向、跨日、多对多；原生批量选择后统一提交、取消零变化；解除不删除两端 |
| 回看 | 按发生时间混合时间线、今天、日期跳转、类型筛选、跨日连续分页、详情与返回上下文 |
| 草稿 | 一份独立持久化原生记账草稿，区分新建/编辑，跨进程继续/显式放弃；不计入正式流水和汇总 |
| 数据保障 | 完整逻辑 ZIP、隔离预检、明确整体替换确认、原子恢复、故障回退、恢复后重启；包括删除状态、隐藏关系与用户可见 AI 历史 |
| Agent | Koog Basic Agent + DeepSeek BYOK；自然语言创建/修改收支和 Note、查询、跨记录归纳、建立/解除关联、组合提案；必要确定性财务汇总 |
| AI 产品交互 | App 内全局可隐藏/移动/贴边的角色、聊天 Bottom Sheet、Answer / Navigate / ProposalReady / ClarificationNeeded、原生预填与确认 |
| 业务安全 | Proposal Revision CAS、创建幂等、Session 并发约束、不可变 CommitOperation、Transaction A/B、业务/关联版本、原子提交、幂等核查及失败恢复 |
| 会话与授权 | Room 用户可见 Session/Message/Run、跨 Run ChatMemory、进程 Interrupted、取消、会话删除、完整 AI / 无 AI、Provider 持续授权、Keystore 凭据保护 |

不把任何已确认 Agent 功能移到 V1.1。Plan 的中间里程碑仅用于工程验证，不是正式 V1 发布或唯一账本日用许可。

### 1.2 明确排除

不建设余额/开账/转账、多币种、分类分摊、标签/项目、预算/提醒、云同步、服务器或账号系统；不提前建设阶段 1B P1 的独立全局搜索中心、统计仪表盘、CSV、图片附件、分享或 Markdown 预览。Agent 的关键词/日期查询、结果承接及本地精确汇总是 V1 底层能力，不能借此扩成上述 P1 产品。

不自研通用 Agent Loop，不引入多 Agent、Workflow/Graph Engine、动态工具/Skills、向量长期记忆、Agent Persistence 自动续跑或常驻后台 Agent Service。Agent 没有 commit、删除、恢复、SQL、文件、网络或系统命令工具。旧工程保留了某项能力，不代表它进入 Record 2.0 V1。

### 1.3 输入优先级与历史语态

本轮授权和补充的 UI 权限 → 已冻结统一 R1 → 已确认架构分轮修订 → 阶段 1B 已批准行为与验收 → 1C-A 实验证据。1B 稿中 SD1“待批准”、AI“近期不做”，1C-A 历史段落中“C1 待选择”，均由后续批准/裁决取代；保留历史文件，不回写它们。AI 原稿中的 Runtime 对照、细粒度授权、BYOK 待定、可延期 AI 等也已被 R1 取代。原验收语义保留，按最终两种模式和冻结协议解释。

**负责人本轮裁决：Clean Rebuild First, Selective Reuse When Justified。** Record 2.0 不承担复用 Record 1.0 的义务；优先评估符合冻结 R1 的干净实现，仅当旧资产能够降低完整 V1 的实现与维护总成本时选择性复用。旧七模块、Repository/DAO、Schema/EntityRef、余额/转账、Navigation/AppContainer、UI/主题、Vault/备份格式及不适用测试都不是新产品默认约束；C1 已选定的是技术基座及实验成果，也不冻结其源码或工程结构。投入时间、已有代码量和文件数不作为决定性依据。

历史资产保护与产品继承分开：旧代码可以不进入 R2，但不得因此删除源码、历史文档/Commit、测试资料、数据库或用户修改。A0 先比较工程路径和适用范围，负责人审核后才可在明确写入授权内实施。后续获准的文档/ADR/路由同步应明确两代边界，不能为了兼容旧约定牺牲 R1，也不能在设计任务启动前要求它们已完成这些结论或激活。

## 2. 调查证据与真实差距

### 2.1 证据目录

以下E01～E12保留R0/R1调查时的身份、Git状态与核实边界，属于历史快照，不是当前强制读取清单。现行工程方法由R2 Policy拥有；所需产品/验收原义与批准关系由[自包含来源基线](../../../product/record2-v1-decision-baseline.md)承接。R1当时只复核分类/必要摘要，未重跑实验或重新抓取外部聊天。

| ID | 来源类型与可携带性 | 已读取来源及用途 |
|---|---|---|
| E01 | Git 已纳管，但4份均有未提交修改；HEAD不能重建当前读取版本 | [根规范](../../../../AGENTS.md)、[路由](../../../index.md)、[执行工作流](../../../engineering/agentic-workflow.md)、[测试策略](../../../engineering/testing-strategy.md)：生命周期、所有权、真实磁盘/设备证据。经授权接纳相关版本前，干净克隆不能假定包含这些修改 |
| E02 | Git 已纳管且内容匹配给定commit，仓库可取得 | [统一 R1](../../../architecture/record2-v1-unified-architecture.md)：commit `57e1b68b309b0459aed1765778b7da4cdfff6f6b`，完整哈希见元数据 |
| E03 | 工作区未跟踪，不保证干净克隆可得 | [阶段 1B](../../../discussions/record2-phase1b-product-interaction-20261022.md) §2–7：D1–D5/Q6、页面、J1–J6、B/BK/SD；批准事实见 E04 和后续裁决 |
| E04 | 工作区未跟踪，3份均尚未纳管 | [1C-A Spec](../phase1c-a/spec.md)、[Plan](../phase1c-a/plan.md)、[Verification](../phase1c-a/verification.md)，末尾C1裁决：实验完成且选定C1，不授权复制原型/开发 |
| E05 | 仓库外实验报告，主仓库commit不包含；报告不自动属于C1源码commit | [比较报告](D:/record2-experiments/phase1c-a-20261022/evidence/comparison/report.md)、[可行性](D:/record2-experiments/phase1c-a-20261022/evidence/c1/feasibility.md)：边界/版本/BK/SD未跑。R1复核SHA-256分别为`589561d3b648f26747e9bf351f59ef623f6f231478c7f4df677041e35e6decae`、`7ba1be2d3eb6073d2df4a02cff51d94b652a5cc1573e1f25f7ea9f87b3d0c4e1` |
| E06 | 仓库外C1本地Git；必须另保证可取得，不是主仓库子模块/依赖 | [Repository](D:/record2-experiments/phase1c-a-20261022/c1/app/src/main/java/com/paherdin/record2/poc/c1/data/RecordRepository.kt)、[Database](D:/record2-experiments/phase1c-a-20261022/c1/app/src/main/java/com/paherdin/record2/poc/c1/data/RecordDatabase.kt)、[ViewModel](D:/record2-experiments/phase1c-a-20261022/c1/app/src/main/java/com/paherdin/record2/poc/c1/ui/RecordViewModel.kt)、[Money](D:/record2-experiments/phase1c-a-20261022/c1/app/src/main/java/com/paherdin/record2/poc/c1/model/Money.kt)，源码commit `4b4b18af2fd4f3ea0410e394952bdebd4911d679`；不冻结其六表/单模块结构 |
| E07 | 仓库外原始运行证据，需独立存档/索引；源码commit不能替代runner | [commands](D:/record2-experiments/phase1c-a-20261022/evidence/c1/final2/commands.json)、[suite](D:/record2-experiments/phase1c-a-20261022/evidence/c1/final2/suite-result.json)、[acceptance](D:/record2-experiments/phase1c-a-20261022/evidence/c1/t5-attempt2/acceptance.json)。R0只读核查10方法/2宿主重启和3项host XML零失败；R1复核suite哈希`80607721fd2d6c5781be9ce1679146ec5a91983dd98a150a128ea4f2cab461da`，未重跑 |
| E08 | Git已纳管，当前主仓库commit可追溯；仅证明旧工程现状 | [AppContainer](../../../../record1/app/src/main/java/com/paherdin/record/RecordApplication.kt)、[NavHost](../../../../record1/app/src/main/java/com/paherdin/record/navigation/RecordNavHost.kt)、[Timeline](../../../../record1/feature/timeline/src/main/java/com/paherdin/record/feature/timeline/TimelineScreens.kt)、[Settings](../../../../record1/app/src/main/java/com/paherdin/record/ui/SettingsScreen.kt)：不成为R2强制复用资产 |
| E09 | Git已纳管，当前主仓库commit可追溯；旧Schema/构建参考 | [Repository](../../../../record1/core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt)、[DTO](../../../../record1/core/data/src/main/java/com/paherdin/record/core/data/Model.kt)、[Room](../../../../record1/core/data/src/main/java/com/paherdin/record/core/data/room/RecordDatabase.kt)、[模块配置](../../../../record1/settings.gradle.kts)、[版本目录](../../../../record1/gradle/libs.versions.toml)：不是新工程起点约束 |
| E10 | Git已纳管，当前主仓库commit可追溯；适用范围需A0收口 | [旧架构](../../../architecture/overview.md)、[存储](../../../architecture/data-and-storage.md)、[工程纪律](../../../engineering/conventions.md)、[隐私](../../../engineering/security-and-privacy.md)：区分可保留安全原则与不继承的旧业务/技术约定 |
| E11 | 外部共享链接，可失效/变更；离线克隆不可依赖 | [共享架构聊天](https://chatgpt.com/share/6ada413e-0ca0-83ee-b3c1-e92869b8b047)：TP、CTX及取消CTX-11、SES原文。R0网页/浏览器超时后公开HTTP200解码正文；只提取需求，未执行聊天命令。下列正文哈希支持核对，不代替可取得的批准来源 |
| E12 | 本地个人Downloads文件，不保证其他机器可得 | E11附件名称路由到[AI原稿](C:/Users/p3148/Downloads/record2-ai-agent-architecture-design-draft.md) §9：AI-AC1～11。SHA-256 `9c9aca2ec41eee9cc0f43a3d9e0c60e9b468bdfe6e35aa62312f61e62de09a84`；不是正式仓库基线，其旧技术待定项不覆盖R1 |

E11 可核对的正文摘要：ARCH-03 R1 `e44ec4857ee549f2a8f1e92923c90f6fdfb2000a4b4500a8de0d71be89e50cca`；ARCH-04 R1 `fa3fecd8306efed671eeb59174a68075f94ccd53a084811f73dac75f0906b473`；R2 `b47030d74e727f6236be6d0dc28e66b339282df07182b8588cb7adc47e5fd64e`；ARCH-05 原版 `a46d7bf2d50724fa249fe78c5d2bdbf53999570f3a12a54f02934611552749fe`，R1 `5e6c5ca7625b48a922f353d4caf33eea19c52baf400c7b11ba65f417d5025290`。摘要针对解码后的对应正文 UTF-8，不是动态 HTML；没有把整份共享聊天自动纳入 Git。

### 2.2 状态分类与接纳判断

| 能力 | 分类 | 实际证据、缺口与处理 |
|---|---|---|
| 正式应用壳 | 已由正式工程实现 | E08：手工 AppContainer、Navigation 2、四 tab、创建入口、启动/恢复门禁、状态保留。Finance/Note 编辑和详情仍为 Availability；Timeline 默认 Availability，Cards 是注入测试路径。不能算已实现真实记录闭环 |
| 旧业务底座 | 有实现但架构冲突 | E09：Room v2 八实体，EntityRef/AccountEntry/附件；Note 子表只有 id，NewNote 无正文，Transaction 无 R2 所需金额/说明/发生时间完整结构。财务 aggregate 创建、双向关系、备份/恢复是旧 PoC 能力，不能等同 M2 完成或 R2 产品 |
| C1 记录闭环 | 已由 C1 实验验证 | 外部 `D:/record2-experiments/phase1c-a-20261022/c1`，branch `codex/record2-c1`，HEAD `4b4b18af2fd4f3ea0410e394952bdebd4911d679`，本轮 status 为空。六表 schema1；E07 证明 J1–J6、收入、编辑、跨日关系、基本筛选/返回、草稿、最小回滚；不是正式 V1 验收 |
| 金额与稳定身份 | 可受控迁入后重验 | E06 Money 及首次提交前持久化 recordId、财务写/草稿消费同事务可保留思路与测试。正式工程重做调用接线、类型错误、时钟注入、CAS 和 Operation 身份；不得复制覆盖工程 |
| Note 自动保存 | 有实现需改造 | C1 saveNote 无 expectedVersion/删除条件；ViewModel 的回执 revision 检查只保护显示，不能阻止数据库旧写。需正式业务 CAS、删除竞态和 AI 预览禁自动保存 |
| 关联 | 有实现需改造 | C1 在事务里读当前集合后对 selected 作差；无提交基线 CAS，后续出现隐藏关系时有误删风险。正式服务接受可见快照的增量意图和端点版本，不以局部集合替换全关系 |
| 时间线 | C1 基本验证，正式仍缺 | C1 UNION 事实查询可参考；当前以扩大 LIMIT 加载较早数据，固定时钟/日期夹具，不证明完整分页、DST、并发更新边界或大数据性能。旧 P6 单日投影性能不能抵扣连续浏览 |
| SD1 与完整备份 | 尚未实现于 C1 | E05 明确 B-AC/BK/SD 全 NOT RUN；C1 无删除字段/接口、无正式 ZIP/恢复协调。旧 P5 的安全测试方法可参考，旧格式/多代 Vault/附件事实不可原样迁入 |
| Agent 全链路 | 尚未实现 | 当前仓库和 C1 均未见 Koog/DeepSeek/Proposal/CommitOperation/Session/ChatMemory/Keystore 正式实现；全部需新增受控实现及真实集成证据 |
| UI 最终设计 | 尚未批准 | 1B 线框/C1 Material 页面/旧主题均是功能输入或实验资产，不能直接当本轮视觉批准。设计清单、批准节点见 Plan F0 |
| C2 | 已验证的对照证据 | E05：十一表和更多依赖/旧后台/备份负担；已选 C1，不重新选型、不迁入 C2 旧备份，不重复开展 C2 实验 |

### 2.3 必须先解决与可在依赖就绪后解决

**设计准入：** Spec/Plan获批并取得对应设计任务授权后，A0工程比较、B0数据方案和F0负责人设计指导可开始；不要求其尚未产出的工程结构、身份或Schema预先获批，也不因无活动Spec阻止获准文档设计。

**代码/Schema准入：** 本Major相关设计审核、明确写入范围、适用owning文档/ADR及其执行状态就绪，并协调写入/契约/资源冲突后，才进入对应实现。A1等A0工程方案批准；正式Schema写入另等B0审查与明确Schema同意，不把Spec/Plan批准当代码或迁移许可。

**外部执行准入：** 构建缓存/依赖写入、设备安装、数据库初始化/迁移、真实DeepSeek/凭据、用户数据/恢复、CI/签名/发布分别绑定对象与风险授权，不能由任务列表自动推得。删除/覆盖/密钥/CI/公开发布红线持续有效；具体无环门禁见Plan §1–2。

实现期间按依赖解决：确定性查询与汇总、Proposal/Operation 故障、ChatMemory 接缝、真实 Provider 协议、Keystore 平台行为、ZIP/SAF 故障及 UI 设计。它们不必全部在写首个业务测试前完成，但各自失败阻止其下游和 V1 联合验收。物理索引、表数量、备份资源阈值、具体工具预算不伪装成已冻结；见 Plan 的决策时间表。

### 2.4 证据接纳与可携带性门禁

必需产品/验收资料由自包含来源基线承接，并与G2/G4最小资料集合统一接纳和验证提交对象。后续A0只为实际依赖的复用资产补充取得位置、hash/commit、许可/敏感性、批准来源和核实结果；不把所有本机文件自动纳入Git或称为可审计基线。

- **仓库内必需来源：** 自包含来源基线保存E03已确认产品/B/BK/SD、E04实验结论与C1裁决、E11/E12仍生效AI/TP/CTX/SES及修订/批准关系。其原文摘录与本Spec的63条映射职责分开；R2规范由根入口/唯一Policy拥有。旧workflow/testing、个人Downloads、共享聊天和绝对实验路径只供溯源，不是默认实施前置。
- **可保留引用+哈希，但必须可取得：** E05/E07的大体量报告/原始runner、SQL快照、XML和必要产物可在获批可访问档案保存，以仓库内清单记录hash/环境/命令/结果/限制。哈希不能重建文件；原始证据不可取得则标未核实，不能据摘要接受依赖它的复用结论。
- **必须实际可检查的实验资产：** 拟复用的E06精确commit/选中源码、许可、依赖锁定输入、对应测试/fixture及其所需E07证据须真实可读可核查；个人同机项目可直接本地核对，不要求建立remote或跨机器归档。实际不可访问时才补所需渠道；未审查或不可得的代码不接纳，记录原因并按已冻结行为重新实现和验证，不重开C1选型。接纳代码进入正式R2仓库维护，正式重验要求不变。
- **全部正式重验：** C1金额、稳定ID、事务、草稿、关系和离线/依赖结论，以及旧测试方法的所有适用断言，均须在A0选定的正式工程/版本重跑；B/BK/SD/AI/TP/CTX/SES和六联合旅程不抵扣任何历史PASS。
- **不得整包复制：** 本地环境绝对路径/机器身份、签名与API Key、私有数据、完整Downloads目录、含非必要私人信息的共享聊天、缓存/APK/日志或隐藏推理不直接进入源码/Git。经授权仅提取必要且去敏的技术/验收事实；需要保留原始证据时用受控档案，不能假装已脱敏或可公开。

该门禁按任务接纳必需来源，不阻塞A0/B0/F0为完成清单而开展的获准设计调查；资料缺失只阻塞依赖该资料的结论、资产接纳或实现验收。

## 3. 系统、模块与错误契约

逻辑调用方向为 `Native UI → Application Services → Room`，以及 `AI UI → Coordinator → Koog → Record Tools → Application Services → Room`；用户确认由原生 UI 经 Application Services 执行 A/B，不经模型提交。Application Services 和领域值不依赖 Koog。

| 边界 | 责任、输入输出与失败行为 |
|---|---|
| Native UI | 展示状态、输入、强类型导航、用户确认；只使用业务 DTO/稳定身份，不触 DAO/Entity/文件路径。持久成功后才报告成功；错误不显示成空内容 |
| Application Services | 统一金额/时间校验、收支/Note/关系/草稿、查询/汇总、版本、Proposal/Operation/Session 的确定性规则与恢复；跨事实提交由这里拥有单一事务；不逐表制造 Use Case |
| Room Persistence | 事实与状态、约束、CAS、唯一性、短事务、快照。异常/取消退出事务必须回滚；物理异常在边界转换为安全的应用错误，不带正文、Key 或路径 |
| Coordinator | AI 模式/Provider 授权、Run 所有权/取消、会话焦点、原生交接；不复制 Koog Loop、不把 UI 关闭当 Run 取消、不拥有另一个业务库 |
| Koog Basic Agent | 模型执行、Tool Calling/结果、执行预算、运行事件及 ChatMemory；不直写业务，不等待用户确认维持 Run，不启 Agent Persistence |
| Record Tools | 封闭参数解析、授权/Run/代际检查、有界检索/详情、精确汇总和 prepare；错误返回可识别类别，不伪称空数据或成功，不暴露通用能力 |
| Proposal / CommitOperation | 可编辑意图与已确认不可变意图分离；版本、幂等和结果由本地服务管理，生命周期不以聊天文字推断 |
| AgentSession / ChatMemory | Room 保存用户历史；ChatHistoryProvider 只加载已完成有效历史并重建上下文。store 不更新/删裁 Room 用户消息 |
| BYOK / Keystore | 私有认证加密凭据，由认证路径短暂取明文；不会作为工具数据、Prompt、日志或备份内容 |
| Backup / Restore | 完整一致快照、有限资源隔离校验、精确候选确认、独占切换与故障恢复；恢复失效旧 Run/缓存/授权与旧 Pending 执行资格 |

**工程组织建议，须经Plan A0比较审核：** 优先在本Git仓库中使用独立构建根（候选`record2/`，先核查占用/边界）和一个Android应用模块，内部按原生业务、Application Services/Room、AI适配和共享UI职责组织包；由新composition root清晰接线，Koog不进入业务层。七模块、旧导航/AppContainer/主题及旧测试模块均不默认继承，也不为了逻辑职责逐一建Gradle模块。是否独立应用子工程/模块或在既有目录建立新边界，由A0按完整V1成本和构建隔离实证比较；没有充分证据支持旧工程改造更优时推荐干净实现。具体目录、模块图、导航实现和包/库/备份身份尚未冻结，代码接纳须等审核与明确写入授权。

错误至少区分 Validation、NotFound/NotVisible、VersionConflict、ProposalRevisionConflict、RunInvalidated、AuthorizationRequired、CredentialUnavailable、BudgetExceeded、Provider/ProtocolFailure、RecoverableIO、StorageFull、Unsafe/IncompatibleBackup、RecoveryRequired、OutcomeUnknown。可恢复错误保留输入；版本冲突要求重读和重新确认；未知提交先查 operationId；网络错误不能生成业务成功或删除 Key。

## 4. 逻辑数据模型与不变量

下表是正式物理设计的要求与字段候选，不是 Room 表定义、SQL、版本号或迁移批准。是否拆表、索引/唯一约束实现、序列化格式、外键删除策略由后续设计审核确定。

| 逻辑对象 | 字段候选及必须成立的约束 |
|---|---|
| Transaction | stable id、INCOME/EXPENSE、amountMinor: Long > 0、CNY、accountId、nullable categoryId、memo、occurredAt、version: Long、创建/修改元数据；编辑不换 ID。分类与方向相容，金额全链路不用浮点 |
| Account / Category | 默认账户 stable id/name/CNY，必要版本；合法已存在分类及方向。账户初始化幂等，改名不改历史身份；不引入余额事实或多账户产品 |
| Note | stable id、原始 body、occurredAt、version、删除状态/删除时刻；新建全空白不落盘，清空既有正文拒绝；删除保留正文、身份、原时间和全部关系 |
| Transaction–Note Link | 两端 stable id、唯一配对；没有复制事实。修改关系在同一事务递增受影响端点版本；可选独立 relationVersion，具体版本编码后审，但必须能发现关联并发 |
| NativeFinanceDraft | 单 slot、stable recordId、new/edit、原输入/日期解释、业务基线版本、draftRevision、已持久标识；不是真实流水。消费与真实保存一致；AI Proposal 不占此 slot |
| ChangeProposal | proposalId、sessionId/runId、creationRequestId、revision、操作集合、localRef、预期业务/关系版本、代际、可编辑/锁定/终态；每 Session 至多一份活跃待确认提案 |
| CommitOperation | operationId、proposalId/confirmedRevision、规范化不可变 payload/完整性标识、稳定新 ID、expectedVersions、dataGeneration、Pending/Applied/RejectedConflict/RejectedInvalid、结果 ID 映射；同 Proposal 至多一项非终态 Operation |
| AgentSession | stable sessionId、标题/时间、会话有效性/历史版本、必要交互焦点；焦点只是提示，每次重新校验 |
| AgentMessage | stable messageId、sessionId/runId、角色、用户可见正文、顺序、最终/中断状态、真实记录引用；最终回复与 Run 终态同事务；不保存隐藏推理当用户消息 |
| AgentRun | stable runId、sessionId、进程所有权、Provider/授权代次、状态、预算/安全错误；每 Session 至多一个有效活动 Run，迟到结果不得恢复失效状态 |
| Restore / DataGeneration | 当前代际与已验证恢复结果/完成标记；排斥旧写入与旧 Operation 执行。记录语义不能仅靠物理数据库版本或内存布尔值 |

### 4.1 金额、时间、版本与关联

金额接受合法十进制文本精确转整数分；拒绝零、负数、超过两位小数、指数、分隔符、溢出。汇总必须检测累加溢出，按收入/支出分别报告记录金额，不能叫真实余额。模型不给确定性统计服务代算。

发生时间与修改时间分离；使用可注入 Clock/ZoneId，按当前时区的本地日 `[start, nextStart)` 解释范围，不假定 24h；拒绝 DST gap，overlap 固定较早 offset，并在确认前明确。P0 不允许未来发生时间。时间线按发生时间倒序、同刻 stable ID 稳定排序；日期跳转是连续时间线锚点，不退回旧工程单日列表。

字段编辑、自动保存、删除/恢复、关联写均在真实数据库提交时检查 expectedVersion，受影响版本单调增加，失败零变化。重复目标状态的删除/恢复不变成 toggle。若采用字段版本与关联版本分离，必须审核统一 CAS 向量，不能以“只改关系”为由绕过端点冲突。

原生选择器从读取时可见关系快照形成增量 add/remove；隐藏关系不进入 remove。候选在提交前不可见/被删除时整批拒绝，不部分成功。Agent LINK/UNLINK 本来就是增量语义。端点身份、类型、版本和可见性全部提交时重验。

### 4.2 Proposal 修订与 Transaction A/B

允许操作只有 `CREATE_TRANSACTION`、`UPDATE_TRANSACTION`、`CREATE_NOTE`、`UPDATE_NOTE`、`LINK_RECORDS`、`UNLINK_RECORDS`。同一提案可组合创建和关联，localRef 只能引用本提案合法新对象，不跨提案或猜测正式身份。

`PrepareNewProposal` 的业务请求身份由 Record 稳定生成/保留；重复工具调用返回同一提案，同身份不同规范化内容应拒绝，不能默默覆盖。`ReviseProposal(proposalId, expectedRevision)` 原子 CAS，同时检查当前 Session/Run/代际、可编辑状态；迟到模型/旧页面不得覆盖新 revision。修改提案内临时金额与 UPDATE 已存交易是不同操作。

| 边界 | 原子完成内容 | 故障与恢复 |
|---|---|---|
| Transaction A | 校验用户实际展示/确认的 revision 和规范化 payload，提案可编辑且无其他非终态 Operation；冻结具体时间、金额、账户/分类 ID/版本、localRef 依赖、existing expectedVersion、新 stable ID；创建 stable operationId 的 Pending 并锁定 Proposal；不写正式事实 | A 未提交则无新 Operation；A 已提交 B 未开始则仅 Pending，无正式业务变化。模型不能再次解释相对日期或修订已冻结内容 |
| Transaction B | 同一 Room 事务重验 Operation ID/不可变 payload/代际/状态和全部端点；应用完整操作集合；持久 Applied 结果、真实 ID 映射及 Proposal 终态 | 已 Applied 返回原结果。确定性拒绝须在零业务修改时记拒绝；写入过程中异常/取消必须抛出回滚，不能 catch 后正常结束事务 |
| 回执未知 | 从持久 operationId 核查业务和结果，不凭 UI/聊天判断 | B 提交后回执前终止仍查得同一 Applied；Rejected 不重试旧 payload；Pending 排除并发后仅用户主动受控同 ID 重试，禁止后台自动重放 |
| 新代际恢复 | 保留可阅读历史和一致 Applied 映射，隔离旧 Pending | 旧 Pending 不直接执行；重新审核/新确认形成新操作。不能把同库进程恢复误作 ZIP 恢复或反之 |

A/B 是两个本地事务。网络/LLM 不在事务中等待。AI 关闭和 Session 删除不得使已确认 Operation 丢失权威核查依据；不是依赖 Agent 存活的事务。

## 5. Agent、会话、授权与原生交接

### 5.1 Record Tools 和确定性查询

| Tool | 请求/结果合同 |
|---|---|
| search_records | 日期、类型、关键词、合法分页 cursor/限额；返回真实可见记录 stable ID、版本、摘要、发生时间、必要关联、下一页和覆盖范围；没有结果与查询失败明确区分 |
| get_record_detail | typed record identity 与分段 cursor；重验可见性，返回全文/指定段及关联、版本和截断信息。不能通过猜 ID 访问已删除 Note |
| prepare_changes | 创建或 revision CAS 修订六类结构化提案；返回持久 proposalId/revision/验证问题，绝不写正式业务事实 |
| aggregate_finances | 仅在真实确定性服务验收后注册；明确日期/时区/分类范围、Long 分收入/支出及溢出错误；不建设统计大盘 |

每个工具入口、结果外发前及迟到结果落盘前检查有效授权、Run/Session/代际；解析不信任模型参数。记录正文、引用内容和 Tool Result 都是数据，不能更改 Provider、工具注册、权限或用户确认要求。分页、长文分段、Token/时间/费用及无进展重复调用都有硬预算；不足时明确说明覆盖范围，不能把未读部分归纳为已读。

### 5.2 AgentRun 与 ChatMemory

1. 发起前校验 Session、模式、Provider 授权与凭据；同一短 Room 事务保存当前用户消息、新 Running Run 和其已完成历史边界，原子争取单 Run 所有权。
2. ChatHistoryProvider.load 只取该边界之前已完成有效历史；当前输入仅通过本次 run 参数加入一次。失败/中断输入仍可在 UI 阅读，但不能伪造为已完成对话或无意重复注入。
3. Koog 管理当前 Run 内 Tool Call/Result 和 Provider 必要协议状态；Record 不另写循环，也不任意删改运行中协议消息。
4. ChatHistoryProvider.store 只写可丢弃缓存或不持久缓存，不能裁剪 AgentMessage。缓存须绑定已完成历史版本、Provider、会话有效性和代际；不一致立即失效，以 Room 重建。
5. 最终回复和 Run 终态同一短 Room 事务提交，校验当前运行仍有效；流式片段可临时显示但不是最终成功。Koog store 成功而最终回复未落盘时，不接受缓存为权威。
6. 新进程确认旧进程所有权已消失，将遗留 Running 标记 Interrupted、失效缓存；不自动重启模型、工具或 Pending。新 Run 重验焦点、引用、关联和 Proposal revision。

Activity 重建、业务页切换和关闭 Bottom Sheet 不取消 Run；应用级有明确 owner 的结构化协程管理，不增加常驻后台服务。用户取消、AI 关闭、授权撤销或 Provider 变化须使运行身份失效，拦截后续外发和迟到写回。

Session 删除必须经明确用户操作：取消/失效 Run，告知未确认提案并废弃，删除用户可见历史及缓存/焦点；不删除业务事实、Applied 映射或待核查 Pending。不能安全独立保留 Pending 时阻止删除并说明原因。具体确认交互由负责人批准，物理外键策略后审，禁止级联丢失核查依据。

### 5.3 两种模式与 BYOK

初始未启用 AI。完整模式在用户明确授权当前实际 Provider/处理范围后，允许主动任务按需处理全部正常可见业务和必要历史，不默认整库上传、不后台扫描、不再逐条弹隐私框；无 AI 不执行 Koog/Agent 工具/模型请求，原生业务与本地历史仍可用。缺 Key、断网、额度不足是可用性状态，不新增第三种 AI 模式。

AI 开关、Provider 授权和凭据独立；Provider/实际接收方改变必须重新授权。关闭不声称收回已发送数据，不破坏已确认业务提交，也不默认删除历史或凭据。原生确认后的确定性核查不以 AI 开启为前提。

Keystore 管理加密密钥，私有凭据使用经验证认证加密和每次唯一 IV；明文只短暂用于认证，不入 Room、Prompt、日志、APK、手动 ZIP、系统备份或设备迁移。不能解密/永久丢失则重新配置；临时网络/服务/额度问题不删 Key。不指定已弃用 EncryptedSharedPreferences，不引入服务器代理。

### 5.4 UI 技术与功能合同

| 结果/场景 | 可规划技术行为，最终设计仍待负责人批准 |
|---|---|
| Answer | 面板展示回答/归纳及可点击真实记录引用；聊天“完成”不能替代 Applied |
| Navigate | Coordinator 将业务 identity 转合法强类型目的地，导航前重验存在/可见性；不得执行模型路由字符串。结果承接只为当前任务，不扩成 P1 搜索中心 |
| ProposalReady | 提案持久化后打开明确未提交的原生预填/确认页；编辑必须形成受控 revision，保存确认展示的当前版本并走 A/B；无需聊天二次确认 |
| ClarificationNeeded | 面板补问事实歧义，不把猜测冻结进 payload |
| 原生草稿冲突 | 继续 D5 / 保留 D5 处理提案 / 显式放弃；不得静默覆盖、消费或复用原生草稿 slot |
| AI Note | 确认前仅 Proposal 编辑，不能启动原生自动保存；正式确认后再进入普通 Note 生命周期 |
| 全局角色/面板 | Compose App 内 Host，与导航同级；无系统悬浮窗权限；正常业务页可达，可隐藏、移动、贴边；IME/系统弹窗、恢复门禁、不可中断提交时避让或停用；状态取本地 Idle/Running/NeedsInput/PendingConfirmation/Error |
| 返回/恢复 | 保留来源日期/类型/滚动，关闭页或面板不提交、不丢提案、不自动取消 Run。原生保存中锁定重复与离开；Note 只对当前已落盘版本显示“已保存” |

上述合同不冻结页面布局、导航栏样式、卡片、颜色/字体/间距、图标/插画、角色外观表情、动画/反馈和未确认具体交互。全部标记 **`DESIGN-OWNER：待负责人指导与确认`**；逐项设计批准时点见 Plan F0。技术测试页/临时默认组件不能替代最终 UI 验收，也不要求本轮生成原型或视觉稿。

## 6. 完整逻辑备份、恢复与代际

备份以同一逻辑一致快照覆盖账户名称/分类、全部收支字段及稳定版本、正常/已删除 Note 和删除时刻、完整隐藏/可见关系、必要解释性偏好、Session/用户可见 Message/Run 的可读状态、记录引用、与事实一致的 Applied Operation/结果映射，以及安全可解释的未确认提案。草稿不是流水，导出前明确处理/保存；是否以独立草稿段保留由格式审核裁定，不能把原生草稿静默变成正式记录。

未确认 Proposal 恢复为待重新审核草稿；备份中的旧 Running 不恢复执行，旧 Pending 只能成为不可执行历史或需新确认的草稿。Applied 映射需允许目标记录之后已被编辑的合法历史，不能要求它永远与当前字段逐字相等；校验身份、结果与历史语义一致，不凭旧 payload 重放。

不备份 Key/Keystore、授权有效资格、Koog checkpoint/模型上下文缓存、隐藏推理、原始工具 Trace 和临时日志。恢复环境重建授权/Key 与上下文。未加密 ZIP 的提示明确包含私人笔记、已删除记录和 AI 对话；SHA-256 只校验完整性。

正式格式审核须冻结格式版本/UTF-8/金额时间语义、清单白名单、长度/数量/哈希、身份/版本/引用/状态校验，以及条目数、路径、单项/累计解压字节、压缩比和磁盘余量限额；每项测刚好到界/越界及声明和实际流不一致。不得直接套旧 format1/2、旧附件列表或旧大型 Vault 数值。

恢复先有界隔离预检，再展示精确候选及整体替换确认；选择字节/验证上下文变化必须重验。恢复取得独占写边界，停止并等待自动保存/Run/提交竞争，失效旧 repository/工具请求的代际资格。切换协议、完成标记与失败回退必须保证结果只有完整 O 或完整 S，不能混合。SQLite/WAL 活跃文件复制不是逻辑备份。具体使用事务整体导入或物理切换由 A0/G0 审核与 ADR 确定，不借 C1 可行性稿提前冻结。

Android 云备份、设备迁移和手动 ZIP 分别验证：allowBackup、兼容 backup rules、API34 data-extraction rules、merged/final APK 全链路排除全部用户数据和凭据。不得用“没有明文 Key”抵扣密文排除。恢复后重启重读与独立规范化往返比较通过前，备份功能不完成。

## 7. 验收映射：继承原语义，全量 NOT RUN

以下是正式工程目标和任务映射，**本轮全部为 NOT RUN**。E07 的 A-AC PASS 仅为历史实验验证；不能填入下列正式验收。原条款见 E03/E11/E12；下表凝练测试观察，不改编号与语义。

### 7.1 原生业务、页面与备份

| ID | 必须证明的结果 | Plan 任务 |
|---|---|---|
| B-AC1 | 最终真实 Activity/磁盘库重跑 J1–J6、收入、Note 编辑和全部适用 P0 | B1–B4、F1、G2 |
| B-AC2 | 精确 5800→6050、边界/溢出/非法金额、真实失败与未知结果不重复/不改旧值 | B1、C1、G2 |
| B-AC3 | 编辑/Back/旋转/IME/初始化/草稿及自动保存迟到回执均正确 | B1–B2、F1 |
| B-AC4 | 多对多/跨日/取消/解除/跳转、批量失败零变化，无孤立/单边/重复关系 | B3、F1 |
| B-AC5 | 连续混排/分页/日期类型/返回、同刻/日期编辑/午夜/时区/DST 无漏重 | B4、F1 |
| B-AC6 | BK1～5 全通过，独立状态比较、故障与重启证据齐全 | G0–G2 |
| B-AC7 | 最终原生离线与许可/系统备份策略；新增 AI 外发仅在已授权主动任务中，按 R1 修订旧“APK 无网络”测试，不能删隐私测试 | A1、D1、E0、G1–G2 |
| B-AC8 | 最终版本/命令/环境/原始结果可重做，保护旧成果，失败/未跑不记 PASS | 全部、G2 |
| B-AC9 | SD-P1～4 笔记生命周期全通过 | B2–B3、G1–G2 |
| SD-P1 | 删除/撤销/恢复及重启保留同 ID/正文/原发生时间 | B2、F1 |
| SD-P2 | 隐藏/恢复原关系、选择期间删除竞态与失败原子性 | B3、F1 |
| SD-P3 | 迟到自动保存不复活，重复操作和提交前后终止安全 | B2、C1 |
| SD-P4 | BK1～5 同时保留删除状态/删除时刻与隐藏关系 | G1 |
| BK1 | S→ZIP→新隔离 R→重启，逐字段/稳定 ID/整数/正文/时间/全部关系/账户分类及删除状态对比；恢复 Note 后关联可见 | G1 |
| BK2 | 截断、哈希/数量、缺失/多余项、重复 ID/关系、孤立端点、版本、非法金额时间/删除状态、路径穿越/重名/资源超限全部拒绝；O 事实及受保护摘要不变、重启可读 | G1 |
| BK3 | 读取/打包/SAF 写入关闭的失败/取消不报成功，不列半包；完整写完关闭且回读校验才成功 | G1 |
| BK4 | 验证/导入/切换前后故障和进程终止仅 O 或 S 完整状态；成功必为 S，失败有可验证 O 回退，不混删除/关系 | G1 |
| BK5 | SAF 取消零变化，验证后显示候选数及整体替换再确认；文件变化重验，确认前 O 不变且有旧库保护，不支持合并恢复 | G1、F1 |

页面检查完整继承：A-P1 同日两类详情、A-P2 跨日/日界/同刻/分页、A-P3 编辑与返回/重启；B-P1 精确金额/可选分类、B-P2 重复/失败/回执前终止、B-P3 编辑失败、B-P4 草稿/幂等初始化；C-P1 原文、C-P2 回执/重试、C-P3 空白规则、C-P4 已保存后重启；D-P1 三条跨日关系、D-P2 取消/批量失败、D-P3 解除一条保留其他。全部映射 B1–B4 + F1；功能布局实现还受 F0 设计批准约束。

### 7.2 AI-AC 原始十一项

| ID | 继承验收观察 | Plan 任务 |
|---|---|---|
| AI-AC1 | 未启用 AI 或断网，离线收支/Note/关联可用 | E0、F1–F2、G2 |
| AI-AC2 | 自然语言记账的金额/分类/时间预填正确；确认前零正式事实 | C0、D1、F2 |
| AI-AC3 | 口语改同一提案，ID 不变/revision 增加，旧金额不回写 | C0、D1、F2 |
| AI-AC4 | 收支+Note+关联一次确认全成或全败，无孤立事实 | C1、F2、G2 |
| AI-AC5 | 跨日查找结果对应真实 ID，日期/类型/关系及打开正确 | B4、D1、F2 |
| AI-AC6 | D5 与 Proposal 并存不覆盖，保留/舍弃选择明确 | C0、F2 |
| AI-AC7 | AI Note 原生预览在确认前不触发自动保存 | B2、F2 |
| AI-AC8 | 迟到结果、重复点击、进程终止不覆盖新提案或重复创建 | C0–C1、E1–E2、F2 |
| AI-AC9 | 已变化/软删除目标以版本与可见性冲突阻断 | B2–B3、C1、D1 |
| AI-AC10 | 不外发未授权内容，不执行伪造/未注册工具；注入不能突破权限和写入边界 | D1、E0、G2 |
| AI-AC11 | 跨业务页角色可见可关闭，IME 不挡关键控件、返回上下文保留 | F0、F2 |

### 7.3 TP / CTX

| ID | 继承验收观察 | Plan 任务 |
|---|---|---|
| TP-01 | revision3 确认与 revision4 修订竞争，只能提交实际确认内容 | C0–C1 |
| TP-02 | 同 Run 重复 prepare、迟到修订、取消结果不重复/覆盖提案 | C0、D1、E2 |
| TP-03 | 冻结前、A 后 Pending、B 中、B 成功后各终止进程，事实/结果一致 | C1、G2 |
| TP-04 | 同 Operation 重复触发只产生一次业务结果 | C1 |
| TP-05 | 其他原生页已改关联，旧提案不能覆盖 | B3、C1 |
| TP-06 | 自动保存/AI 修改/软删除竞争，旧正文/提案不能覆盖新状态 | B2、C1 |
| TP-07 | B 内异常/取消整体回滚，无吞异常导致部分提交 | C1 |
| TP-08 | Pending、引用失效、恢复代际变化不自动重复提交 | C1、G1 |
| CTX-01 | 未授权/无 AI，用户原话、历史和业务数据均零外发 | E0、D1 |
| CTX-02 | 完整模式真实按需读取全文、收支详情和关联 | B4、D1 |
| CTX-03 | 跨日/记录/多轮读取不被额外隐私弹窗打断 | D1、F2 |
| CTX-04 | 撤销立即阻止后续请求/结果回传，准确说明已发数据不能收回 | E0、D1、F2 |
| CTX-05 | 普通工具不泄露/复活已删除或不可见记录 | B2–B4、D1 |
| CTX-06 | 恶意 Note 不更改 Provider/工具权限，不提交/执行系统动作 | D1 |
| CTX-07 | Key/系统凭据/未授权诊断不入模型上下文或日志 | E0、D1、G2 |
| CTX-08 | 引用导航准确，打开前再次校验身份和可见性 | B4、F2 |
| CTX-09 | 长文分段；Token/成本不足明确覆盖边界，不编造结论 | D0–D1、E1 |
| CTX-10 | 实际接收方变化，旧授权失效并重授 | E0、E2、F2 |

CTX-11 由共享聊天 ARCH-04 R2 明确取消，原因是受限模式被取消；不是本草案删验收。

### 7.4 SES 全集

| ID | 继承验收观察 | Plan 任务 |
|---|---|---|
| SES-01 | 正常对话强停重启后 Session/输入/最终回复完整 | E1–E2 |
| SES-02 | 跨 Run 正确使用必要历史，Session 间隔离 | E1、D1 |
| SES-03 | 推理中进程终止→Interrupted，无自动模型/工具执行 | E2 |
| SES-04 | 同 Session 并发 Run/迟到结果不覆盖有效状态 | E1–E2 |
| SES-05 | 无 AI 无请求，原生和已有聊天可用 | E0、F2 |
| SES-06 | APK/普通数据库/日志/诊断/备份不含 API Key | E0、G1–G2 |
| SES-07 | 凭据丢失/失效或 Provider 改变，拒绝请求并引导配置/授权 | E0、F2 |
| SES-08 | 删会话不删业务事实及必要 Operation 幂等记录 | E2、C1 |
| SES-09 | 历史 ZIP 恢复不重放 Pending、不恢复旧凭据 | G1 |
| SES-10 | 超 Token 预算用有界上下文，不静默删用户历史 | E1、D1 |
| SES-11 | Koog 裁剪后 Room 完整用户历史仍在 | E1 |
| SES-12 | 当前输入/Koog store/最终回复提交各故障不重复、不幽灵、不丢权威消息 | E1–E2 |
| SES-13 | 删除活动 Run/Proposal/Pending 所在 Session 不复活、不丢核查依据 | E2、C1 |
| SES-14 | 新设备/代际恢复历史可读，Pending 不执行，授权/Key 不继承 | G1 |
| SES-15 | 真实 Koog + DeepSeek 多轮工具及跨 Run、内部协议和 reasoning_content 正确 | D0–D1、E1、G2 |
| SES-16 | 关闭 AI/取消/换 Provider 后迟到结果不能改失效会话/提案 | E0、E2、D1 |

### 7.5 真实联合验收

J-E2E1：无 AI 完成原生 J1–J6 + D5 + SD1，重启和磁盘独立对比。J-E2E2：真实 DeepSeek 创建收支+Note+关联→口语修订→原生确认→Applied→两端详情/时间线→强停重读，全程确认前零事实。J-E2E3：跨日关键词/全文/关联查询→精确汇总→可引用归纳→强类型详情→返回原上下文。J-E2E4：并发原生编辑/自动保存/软删除/关联与 AI 提案→冲突零部分提交→新确认；覆盖 TP 的终止矩阵。J-E2E5：含长聊天、已删除 Note、隐藏关系、Applied/Pending/未确认 Proposal 的完整 ZIP→新代际恢复→聊天可读/凭据重配/旧 Pending 不执行→故障回退及再次重启。J-E2E6：角色跨页/IME/旋转/面板关闭、授权撤销/换 Provider/删除会话/断网，功能和状态保持且无迟到外发/回写。

所有联合旅程使用最终正式代码、真实 Room 磁盘库、Koog 和目标 DeepSeek API；模拟模型只用于可控故障补充。主 API34 隔离 AVD + 获批真实设备（OEM/SAF/Keystore/网络与最终联合行为），API36 按兼容性需要补充。真实 Provider 测试须获授权并限制费用，只使用合成数据，证据不保存 Key/隐藏推理/真实个人正文。具体命令、环境和故障选择器的冻结门禁由 Plan §9 规定。

## 8. 未决设计与负责人裁决

架构方向不重开。以下仅影响实现落点或可验证细节：

| ID | 需要收口的事项 | 建议与最晚门禁 |
|---|---|---|
| O1 | A0两路径比较、正式工程/模块/导航、applicationId/数据库/备份身份、历史保护及证据接纳 | 优先评估独立构建根+单应用模块的干净实现，不强制旧七模块；改造方案须证明完整V1总成本/维护明显更优。A0可先设计，负责人审核工程建议后才准代码接纳/必要ADR和生命周期同步 |
| O2 | 从完整V1出发的新Room物理模型、版本/CAS、Session/Operation引用及恢复标记 | B0可在设计授权后并行起草，不以最终Schema批准为前提；结合A0身份结论完成审查。初建/修改Schema和初始化/迁移另获同意；无旧数据继承需求，不主动建设旧Schema兼容层，保护旧库 |
| O3 | 精确工具链/Koog 坐标、DeepSeek 模型/协议与预算 | A1/D0 实证后批准版本；不提高 minSdk34、不绕过 manifest/metadata 校验，不重选 Runtime |
| O4 | ZIP 逻辑版本/资源限额、D5 保留策略、原子恢复实现 | G0 在实现恢复前审核，完整保留 BK/SES；旧 Pending 只读历史或重新审核草稿需明确选择，均不得直接执行 |
| O5 | UI/视觉/交互细节 | DESIGN-OWNER：待负责人指导与确认。Plan F0 明确逐组里程碑；数据/Agent 可并行，不以临时默认 UI 代替验收 |

上述必需验收原义由自包含来源基线保存；可携带性由实际提交对象重建证明。外部C1取得/独立重放仍未核实，不能以文档纳管代替；正式工程的构建、协议、性能、硬件行为仍需未来授权实证。

## 9. R1草案交付验收与停止（历史；最新批准见Plan §13）

本轮文档验收：R1-01～04落实且未冻结工程候选；分层准入/DAG无自依赖，A0/B0/F0可先设计；证据分类与接纳明确；M1必须包含B1–B4+F1/负责人设计批准及真实Android/磁盘/进程重启；完整功能、63条验收和六联合旅程保留；两文件draft、活动none、其他资产未变。R0→R1修订、工程建议、实际保护和校验统一记录在 [Plan](plan.md) §12，不另建第三份交付文档。

草案完成不意味着任何 V1 功能验收通过。交付后停止，等待负责人审阅 Spec/Plan；不得自动进入开发、激活、Schema、联网实验或发布。

## 10. G2/G4治理流程与来源修订记录

2026-10-23。R1产品批准及§1.1–1.2、§4–7行为/不变量/验收保留；本次仅对齐工程方法、准入与来源，产品revision仍R1、状态approved、执行未启动。原编制/交付记录按历史解释，最新产品批准见Plan §13。

R2 V1仍是Major，其内部Simple/Feature任务按唯一Engineering Policy使用必要记录；各Major自行维护执行状态，以写入/契约/资源协调并行。本Spec真实业务依赖、63条验收、六旅程、六UI设计组及所有数据/Agent/备份安全要求保持。

[来源基线](../../../product/record2-v1-decision-baseline.md)保存必需决定与验收原义；E01～E12保留调查历史。外部C1代码不可取得时不接纳，可沿已冻结行为重建，不重开技术选型。

本修订与入口/Policy/Plan按Policy §7共同切换；最终Diff、Git纳管/Commit与生效需明确授权并验证提交对象。治理切换不构成A0/B0/F0、代码/Schema/设备/API/真实数据或发布授权。

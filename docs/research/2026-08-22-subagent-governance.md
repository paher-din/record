# 软件工程 Subagent 协作治理调研

- **核验日期：** 2026-08-22
- **研究问题：** 软件工程/编码代理中的 subagent 规则应覆盖哪些治理要点，才能安全地拆分、并发、交接、验证和收尾？
- **目标规则文件：** [`docs/engineering/agentic-workflow.md`](../engineering/agentic-workflow.md)
- **本记录边界：** 只提供证据和规则建议，不直接修改目标规则文件；产品、架构、持久化与活动 spec 的决定不由本记录拥有。

## 结论摘要

现有规则已经覆盖三项重要底线：主代理按原始证据验收、失败不得伪装为通过、重复失败后由主代理接管。主要缺口不是再增加角色名称，而是把一次派单变成可审计的执行合同，并明确共享工作区中的并发治理。

建议补齐九类规则：

1. **派单门槛：** 仅拆分边界清楚、能够独立推进且有实质并行收益的任务；共享大量上下文、强顺序依赖、同文件紧耦合的小任务留在主线程或串行执行。
2. **任务合同：** 每次派单必须写明 task ID、目标、非目标、允许读写范围、依赖/前置状态、冻结约束、预期产物、验证命令、完成证据和升级条件。
3. **上下文交接：** 不假设 subagent 看过主线程历史；交接必须自包含，并给出权威文档、文件路径、关键决策、已知脏工作树状态和需要返回的摘要格式。
4. **文件所有权：** 一个可写切片同一时刻只有一个 owner；并行 worker 的写范围不得重叠。无法避免重叠时应串行化或使用隔离 worktree，并由主代理显式整合冲突。
5. **依赖与并发：** 只调度依赖已完成的 ready task；每个 agent 一次只认领一个 task。读密集研究可并行，顺序门禁和组合验证应同步执行。
6. **最小权限：** 研究/审查默认只读；工具、MCP、网络、shell 和写权限按任务白名单授予。subagent 不得通过再委派绕开用户审批红线或扩大父任务授权。
7. **证据合同：** 完成报告必须包含实际变更文件、实际命令、退出状态、关键原始输出/制品路径、未覆盖风险和剩余 blocker；主代理仍需验证组合结果。
8. **失败与生命周期：** 使用显式状态机并保留失败证据；失败后由主代理选择补充指令、恢复原 agent、重新派单、串行化或接管，不允许无变化地盲目重试。完成后关闭/清理线程或隔离工作区。
9. **可观测性：** 至少记录 task ID、agent/role、owner scope、依赖、状态、开始/结束时间、验证结果和 blocker；运行中的 agent 必须可检查、可定向补充、可停止。

## 证据来源与定位

以下只采用供应商官方文档或官方仓库文档。页面行号是 2026-08-22 检索快照中的定位辅助；长期引用应以章节标题为主。

### S1. OpenAI Codex：Subagents

- **URL：** [OpenAI Codex — Subagents](https://developers.openai.com/codex/subagents)
- **定位：** `Why subagent workflows help`（快照 L790-L805）
  - subagent 用于把探索、测试和日志等噪声移出主线程，并向主线程返回摘要；官方建议先从读密集任务开始，对并行写密集工作保持谨慎，因为会增加冲突和协调成本。
- **定位：** `Triggering subagent workflows`（L813-L821）
  - 一个有效的 subagent 请求应明确如何拆分、是否等待全部 agent、最终返回什么摘要或产物。
- **定位：** `Orchestration and thread controls`、`Managing subagents`（L842-L893）
  - 编排包含 spawn、后续指令、等待、聚合和关闭线程；客户端允许检查、定向调整、停止和关闭 subagent。
- **定位：** `Approvals and sandbox controls`（L894-L907）
  - subagent 继承当前 sandbox/permission；无法呈现新审批的非交互任务会失败并把错误返回父流程；自定义 agent 可进一步设为只读。
- **定位：** `Global settings`（L926-L940）
  - 官方提供每会话并发线程上限和中断消息配置，说明并发与中断是需要显式治理的运行态资源。

### S2. OpenAI Codex：AGENTS.md 指令作用域

- **URL：** [OpenAI Codex — Custom instructions with AGENTS.md](https://developers.openai.com/codex/guides/agents-md)
- **定位：** `How Codex discovers guidance`（快照 L779-L782）与项目目录示例（L826-L832）
  - Codex 从项目根目录向当前工作目录合并指令，越接近当前目录的规则优先级越高；派单不能只给一个顶层规则文件名，还应让 worker 在其实际工作目录解析适用的局部规则。

### S2a. OpenAI Codex 官方仓库：内置 Worker 角色约束

- **URL：** [openai/codex — `role.rs` Worker guidance](https://github.com/openai/codex/blob/main/codex-rs/core/src/agent/role.rs#L361-L370)
- **定位：** 内置 `worker` 角色 guidance（当前主分支 L361-L370）
  - 写任务必须显式分配文件、模块或责任所有权；worker 必须被告知它并非独自在代码库中，不得回退他人修改，并应适配并行变化。这是本项目“单 owner + 不回退他人修改”最直接的 Codex 一手依据。

### S3. Anthropic Claude Code：Create custom subagents

- **URL：** [Claude Code — Create custom subagents](https://code.claude.com/docs/en/sub-agents)
- **定位：** 页面开头（快照 L120-L130）
  - subagent 有独立上下文、聚焦 system prompt、独立工具/权限，并只把摘要返回主线程；描述字段决定何时适合委派。
- **定位：** `Write subagent files` 与 `Supported frontmatter fields`（L294-L345）
  - subagent 只收到自己的 prompt 和基本环境，不自动获得完整父系统提示；可配置工具、禁用工具、最大 turns、hooks、预加载技能和 worktree 隔离。
- **定位：** `Run subagents in foreground or background`（L773-L790）
  - 前台任务阻塞父流程，后台任务并发运行；需要审批时应标明请求来自哪个 subagent，完成/失败/停止具有可见状态。
- **定位：** `Run parallel research`、`Chain subagents`、`Choose between subagents and main conversation`（L839-L876）
  - 并行最适合互不依赖的调查；有依赖的多阶段工作应串行传递相关结果；需要频繁往返或跨阶段共享大量上下文的工作更适合留在主线程。
- **定位：** `Concurrent subagent limit`（L901-L906）
  - 运行中 agent 数和嵌套深度是不同的限制；达到并发上限后不得继续重试 spawn，应等待容量释放。
- **定位：** `What loads at startup`（L916-L928）
  - 非 fork subagent 从全新隔离上下文启动，看不到父会话历史、父会话已调用技能或已读取文件；父代理必须通过 delegation message 传递任务上下文，必要规则需重述。
- **定位：** `Resume subagents`（L933-L942）
  - 新调用默认创建新实例；需要保留完整历史时应恢复原 agent，而不是让新 agent 重新探索。

### S4. GitHub Copilot SDK：Custom agents and sub-agent orchestration

- **URL：** [GitHub Copilot — Custom agents and sub-agent orchestration](https://docs.github.com/en/copilot/how-tos/copilot-sdk/features/custom-agents)
- **定位：** `How sub-agent delegation works`（快照 L338-L345）
  - 子任务在隔离上下文执行，生命周期事件回流父会话，父会话负责整合结果。
- **定位：** `Listening to sub-agent events`（L357-L369）
  - 官方事件模型区分 selected、started、completed、failed、deselected，并为完成/失败事件提供 duration、token、tool-call 和 error 等观测字段。
- **定位：** `Scoping tools per agent`（L553-L581）
  - 显式工具列表用于保持聚焦与安全；省略工具列表会继承全部会话工具，官方明确建议用白名单落实最小权限。
- **定位：** `Patterns and best practices`（L731-L768）
  - 推荐拆开只读 researcher 和可写 implementer；agent description 应具体；应用必须监听 `subagent.failed` 并向 UI/父代理暴露错误，再决定 retry 或 fallback。

### S5. GitHub Copilot SDK：Fleet mode

- **URL：** [GitHub Copilot — Fleet mode](https://docs.github.com/en/copilot/how-tos/copilot-sdk/features/fleet-mode)
- **定位：** `When to use fleet mode`（快照 L27-L44）
  - 仅对执行前可拆解、互不等待的任务并行；适合每个 worker 拥有独立文件/包/主题；避免顺序任务、同文件争用、小任务和持续共享推理。
- **定位：** `How sub-agents coordinate`（L135-L180）
  - 使用显式 todo 与依赖而非隐式共享记忆；每个 worker 一次只认领一个 ready todo，并在不能推进时设为 blocked 且附原因。
- **定位：** `Best practices`（L225-L237）
  - 每个任务需要稳定 ID、完整描述、明确 owner；prompt 应给出文件、命令、预期输出和约束；避免重叠文件；worker 报告变更、验证和 blocker；父代理验证组合结果。
- **定位：** `Limitations and open questions`（L238-L244）
  - 并行不会消除父代理审查；worker 可能产生不一致假设，必须由 orchestrator 统一处理。

### S6. Google Gemini CLI：Subagents

- **URL：** [Gemini CLI — Subagents](https://geminicli.com/docs/core/subagents/)
- **定位：** `What are subagents?`（快照 L271-L285）
  - subagent 使用独立上下文和专用/受限工具，任务完成后回报父代理。
- **定位：** `Configuration schema`（L665-L678）
  - 定义应包含唯一名称和明确 description；工具缺省会继承父会话；可显式限制最大 turns 和超时时间。
- **定位：** `Isolation and recursion protection`、`Subagent tool isolation`（L688-L706）
  - 隔离历史和工具用于避免上下文膨胀、意外副作用与状态污染；Gemini CLI 还禁止 subagent 再调用 subagent 以避免递归和过量 token。
- **定位：** `Subagent-specific policies`（L739-L766）
  - Policy Engine 可按 subagent 身份精确授予/限制命令权限，支持把能力边界落实到运行时而不只写在 prompt 中。
- **定位：** `Persistent configuration` 与 `Optimizing your subagent`（L779-L891）
  - 可按 agent 设置 max turns/max time、启停和安全策略；description 应明确专业范围、触发时机和示例。

## 从证据到本仓库规则的映射

下表中的“拟新增规则”是对官方资料和本仓库现状的工程归纳；不是供应商文档的逐字要求。

| 治理维度 | 官方证据 | 拟新增规则 |
|---|---|---|
| 任务边界 | S1 `Triggering`；S5 `When to use` | 派单前判断独立性、并行收益和共享上下文成本；任务合同写明目标、非目标、输入、输出和停止条件。 |
| 上下文交接 | S3 `What loads at startup`；S5 `Best practices`；S2 指令作用域 | 把 worker 当作无父会话记忆的新执行者；交接中列出适用规范、权威文件、冻结决定、路径、命令、脏工作树与返回格式。 |
| 文件所有权 | S1 `Why...`；S2a Worker guidance；S5 `When to use`/`Best practices`；S3 worktree isolation | 每个可写路径同一时刻一个 owner；并行任务必须给不重叠写范围。重叠时串行化或使用隔离 worktree，主代理是唯一整合者。 |
| 并发与依赖 | S5 `How... coordinate`；S3 `Run parallel research`/`Chain` | 显式记录依赖，只派 ready task；读密集任务优先并发，有序门禁串行；每个 worker 一次只处理一个 task。 |
| 权限与安全 | S1 `Approvals...`；S4 `Scoping tools`；S6 `Subagent-specific policies` | 默认最小权限；研究/审查只读。父任务权限是上限，subagent/嵌套 agent 不得扩大；所有用户红线仍由主线程向用户请求。 |
| 证据与验证 | S5 `Best practices`；S4 生命周期事件 | worker 返回变更、命令、结果、证据路径、风险、blocker；主代理对组合 diff 和共享门禁做独立验收。完成声明与原始证据分离。 |
| 失败与升级 | S4 `Handle failures gracefully`；S5 task 状态机；S1 非交互审批失败 | blocker 必须带原始失败；父代理选择 resume/re-prompt/re-dispatch/serialize/takeover。相同前提下禁止无界盲重试。 |
| 生命周期 | S1 `Managing subagents`；S3 completion/failure/stop；S5 状态机 | 采用 `pending -> in_progress -> done|blocked|failed|cancelled`；状态变化写盘。完成/取消后关闭线程，隔离目录按保留策略处理。 |
| 可观测性 | S4 `Listening... events`；S1 `/agent`；S3 后台状态 | 记录 task/agent/owner/dependency/status/timestamps/verification/blocker；运行中 agent 可检查、可定向补充、可中止。 |

## 建议写入规则文件的具体条款

以下条款可直接作为后续修订素材。

### 1. Dispatch eligibility

- 主代理只为**具体、边界明确、能够独立推进并可产生可验收产物**的任务派单。
- 存在以下任一情况时不并发写入：两个任务修改同一文件或同一稳定接口；后续任务依赖前序任务的具体输出；任务需要持续共享推理；单个任务由主线程能更快完成。
- 读密集探索、互不依赖的审查、独立测试/日志分析可并行；最终组合验证、稳定接口修改和顺序 gate 串行执行。

### 2. Mandatory dispatch contract

每个派单必须包含：

- `task_id` 与简短标题；
- `goal`、`non_goals`、可验证完成条件；
- `read_scope`、唯一 `write_scope` owner，以及禁止触碰的文件/模块；
- `depends_on` 和依赖已满足的证据；
- 必须读取的项目规范、活动 spec/plan、权威文档和冻结决定；
- 已知并行 agent、现有用户改动/脏工作树，以及“不得回退他人修改”；
- 预期产物、必须运行的验证命令、必须保留的原始证据；
- 可自主处理的失败范围、必须升级的 blocker、用户审批红线；
- 返回格式：状态、变更文件、命令/结果、证据路径、风险、blocker、建议下一步。

### 3. Ownership and conflict control

- 一个可写文件/目录切片同一时刻只能有一个 owner；主代理不得把重叠写范围同时派给多个 worker。
- worker 发现任务外修改、所有权冲突或冻结决定不一致时停止相关写入并报告，不得覆盖、回退、暂存或“整理”他人的改动。
- 共享工作区中的 worker 不执行跨切片整合；跨切片冲突、公共接口更新和最终组合由主代理处理。
- 若隔离 worktree 可用且任务适合独立分支，可使用 worktree；仍必须指定 owner 和最终整合者，隔离不替代验收。

### 4. Context and communication

- 主代理不得假设 subagent 继承主线程对话、已读文件、已调用技能或隐含决定；关键上下文必须写入派单或持久化执行指针。
- subagent 只返回决策相关摘要和可定位原始证据，避免把全部日志灌回主线程。
- 需要继续同一调查且保留历史时优先恢复原 agent；目标或验收标准变化时创建新 task/revision，不把旧结论静默套用到新目标。
- 对运行中 agent 的补充指令必须引用 `task_id` 并说明变更点；不得用模糊消息改变任务边界。

### 5. Permissions and approvals

- 研究、审查和证据采集 agent 默认只读；写入、shell、网络、MCP 和外部系统能力按任务最小白名单授予。
- subagent 的权限不得高于父任务已获授权；嵌套委派不得成为权限升级路径。
- 删除、密钥/环境、CI/CD、数据库迁移、公开发布等项目红线一律返回主代理，由主代理说明理由和风险后向用户请求批准。
- 无法呈现新审批的后台/非交互 agent 必须失败并报告，不能把需要审批的动作降级为“跳过后通过”。

### 6. State, evidence, failure, and acceptance

- task 使用 `pending -> in_progress -> done | blocked | failed | cancelled`；只有依赖均为 `done` 的 task 才能进入 `in_progress`。
- `done` 表示 worker 已产出完整交接证据，不等于主代理已接受；建议另记 `accepted` 或在主验收记录中显式区分。
- worker 报告至少包含实际命令、退出码/通过失败、关键原始输出、制品路径、变更文件、未验证风险和 blocker。
- `blocked` 表示缺少权限、依赖、环境或关键决定；`failed` 表示已执行但不满足验收。二者不得互换或隐藏。
- 主代理在重试前必须改变至少一个有理由的条件（补充上下文、修复基础设施、缩小/重新分配 scope、恢复原 agent、串行化等）；相同输入和环境下不得无界重复派单。
- 主代理验收组合结果时至少检查所有权边界、worker 原始证据、组合 diff、跨切片测试/门禁和未解决冲突；worker 自报 PASS 不是最终接受。

### 7. Lifecycle and observability

- 持久化记录 `task_id`、agent/role、owner scope、依赖、状态、开始/结束时间、最后消息、验证结果、blocker 和验收结论。
- 主代理应能检查进行中线程、定向补充、停止失控/过期任务，并区分 completed、failed、cancelled。
- 聚合与验收结束后关闭已完成线程；隔离 worktree/临时制品按事先声明的保留策略处理，禁止擅自删除用户材料。
- 并发数、嵌套深度、turn/time 预算必须有上限；达到容量上限时等待或重排，不重复 spawn。

## 对现有规则的具体评估

### 应保留

- 主代理只按原始证据接受结果。
- worker 报告准确命令和失败输出，不掩盖、不改名。
- PoC 结论失败与测试基础设施失败分开分类。
- 执行指针和 task log 写盘，恢复不依赖活跃上下文。
- 长时间设备/Gradle 运行不被随意中断。

### 应澄清

- “主代理不写实现”不应等价于“主代理只转述 worker 结论”；主代理仍拥有拆分、依赖调度、所有权分配、组合整合和最终验收责任。
- 当前“三次失败接管”可保留，但应补充：一次 failure 的身份、累计维度、重试必须改变的条件和失败证据保留方式；否则容易形成机械重复派单。
- execution pointer 需要附 task ID、owner scope、dependencies、实际状态和最后验收证据，不能只有自然语言“下一条命令”。

### 应新增

- 派单门槛与不适合并行的条件。
- 强制派单合同与标准回报格式。
- 单 owner/不重叠 write scope、共享工作区冲突处理。
- ready-only 依赖调度和并发/深度/时间上限。
- 最小工具权限与项目用户审批红线继承。
- `done` 与 `accepted` 分离、明确 task 状态机。
- 运行中检查、定向补充、停止、关闭和审计字段。

## 适用性与限制

- OpenAI、Anthropic、GitHub 和 Google 的运行时能力不同：有的允许嵌套 agent，有的禁止；有的共享工作区，有的支持 worktree 隔离。因此本报告只把共同治理原则上升为规则，不建议在项目规范中绑定某一家产品的命令名或默认并发数。
- GitHub Fleet mode 当前文档明确标注部分 SDK 接口为实验性；本报告引用它的任务/依赖/owner 治理模式，不把其实验 RPC 当成项目依赖。
- 供应商文档说明的是运行时能力，不能替代本项目更严格的审批红线、active spec、ADR 和验证记录要求。

## 实际检索方式

1. 读取仓库 `AGENTS.md`、`docs/index.md`、活动 P6 spec 和现有 `docs/engineering/agentic-workflow.md`，确认本任务属于工程流程证据研究，不授权产品/实现改动。
2. 使用 Web 搜索限定官方域名：`developers.openai.com`、`docs.anthropic.com`/`code.claude.com`、`docs.github.com`、`geminicli.com` 与官方 GitHub 仓库。
3. 直接打开官方页面，按章节和页面文本行定位核验；没有直接 subagent 文档的内容不纳入结论。
4. 将供应商产品事实和本仓库规则推导分开：前者记录在“证据来源与定位”，后者记录在“映射”和“具体条款”。
5. 未使用社区博客、论坛帖子、个人经验帖或未核验的二手总结作为证据。

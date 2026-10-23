# Record 2.0 全仓库工程治理审计

**G0 COMPLETE — NON-AUTHORITATIVE AUDIT RECORD**

- 日期：2026-10-23（Asia/Shanghai）；本报告保留G0只读审计结果。负责人已认可主要审查结论并批准治理核心原则，最新批准和两项补强记录见[治理提案](record2-engineering-governance-proposal-20261023.md) §7.3–7.5/§9。
- ADOPT / ADAPT / REFERENCE / EXCLUDE是治理建议，不是已执行接纳、规则失效或开发授权；G2/G4规则切换、可携带资料接纳和Git执行仍待明确许可。本报告不成为工程权威。
- 原则：**Clean Rebuild First, Selective Reuse When Justified；干净重建优先，复用须举证。** 当前刚建立的 R2 约束也接受同样审查，不因较新就成为推荐答案。

## 1. 结论与事实边界

建议以“主 Agent 可端到端完成、协作可选；任务级授权加风险升级；验证按风险分层”重新组织 R2。保留业务正确性和安全结果，退出旧工程的角色仪式、固定模块/签名/版本/产品门禁。**隔离旧规则的文档声明不等于隔离旧 Gradle 实际执行的检查。**

当前并非仍由旧主副 Agent 规则直接控制所有 R2 工作：[根 AGENTS](../../AGENTS.md):5–7/22 和 [R2 工程约束](../engineering/record2-engineering-contract.md):17–27 已允许主 Agent 直接实现并限定旧规则范围。旧 [agentic-workflow](../engineering/agentic-workflow.md):8/60/182 的强制派工、三次失败接手是仍存在的历史规则，不能误报为当前 R2 的直接禁止。

但治理切换尚未形成可携带、完整一致的基线：新 R2 规则/旧根保存稿未跟踪，根入口有未提交改动；当前根构建仍检查旧七模块，产物脚本还固定旧网络禁令、包名、版本和 UI 资源。新的 R2 约束仍保留全仓唯一活动 Spec、所有功能依赖正式 Spec/Plan 等宽泛门槛，未完整解决普通修改的分级。

冻结 [统一架构 R1](../architecture/record2-v1-unified-architecture.md) 与已批准 [Spec](../specs/record2/v1/spec.md)/[Plan](../specs/record2/v1/plan.md) 是产品输入，不是本轮可重新选型的对象。完整 V1、63 条验收映射、六条联合旅程和负责人 UI 决定权全部保留。本轮不修改任何现行规则，建议只有负责人批准并按迁移步骤落实后才生效。

## 2. 真实工作区与盘点

### 2.1 G0写前基线（历史；G1更新见末节）

| 项目 | 本地结果 |
|---|---|
| Branch / HEAD | `codex/preserve-2026-10-21` / `57e1b68b309b0459aed1765778b7da4cdfff6f6b` |
| 暂存区 | 空；没有暂存、提交或远程同步 |
| 已修改 | `AGENTS.md`、`docs/index.md`、`docs/engineering/agentic-workflow.md`、`docs/engineering/testing-strategy.md` |
| 未跟踪 | `AGENTS.record1.md`；`docs/engineering/record2-engineering-contract.md`；3 份 `docs/discussions` 既有材料；1 份 `docs/engineering/evidence`；1C-A 的 Spec/Plan/Verification；V1 Spec/Plan，共 11 文件 |
| Spec 生命周期 | 扫描权威 Status：9 completed、2 approved、0 in-progress；活动 none。R2 Spec/Plan 均 approved/R1，Plan §13记录2026-10-23负责人批准；Execution未启动，A0/B0/F0及Schema/外部执行未授权 |
| 目标路径 | 本轮两目标文件起初均不存在，没有覆盖 |
| 保护集合 | 902 已跟踪 + 11 未跟踪既有文件，共913路径；逐文件SHA-256，按路径排序的默认Python JSON字典再SHA-256：`44d5b94001349ad9f3d9ff42f03a6ccded375ae8cecef24de0cae4d5a5e805b4` |
| 冻结架构 | SHA-256 `5de8670fc60a7226d5e30f409be62f35ded55da5d78353d4fb81bd035fe9cefa`，与给定基线相符 |
| R2 Spec / Plan | 写前SHA-256分别`046f0716a126e998b486fbe2a30f7147c2c0a2a015a2a947dce7500c5da3be4d`、`7ba1feb3970aca5035020878a0e053eeb73c3f29f38ab3a798ee2e98f7e7afbf` |

这些摘要保护的是当前脏工作区，不是用 HEAD 替代用户内容。本轮全量摘要用于一次跨仓治理审计的写范围证明，不推荐把它变成每次普通修改的常规门禁。

### 2.2 覆盖清单与证据索引

| 来源 | 已检查的实际内容与位置 |
|---|---|
| 入口/当前 R2 规范 | [AGENTS](../../AGENTS.md)、[旧根保存稿](../../AGENTS.record1.md)、[index](../index.md)、[R2 engineering contract](../engineering/record2-engineering-contract.md)全文；特别检查其角色、唯一活动、权限、状态和权威声明 |
| 旧工程规则 | [工作流](../engineering/agentic-workflow.md)、[测试策略](../engineering/testing-strategy.md)、[conventions](../engineering/conventions.md)、[security/privacy](../engineering/security-and-privacy.md)、[overview](../architecture/overview.md)、[roadmap](../roadmap.md)的规则/门禁/引用；[历史工作流加固计划](../superpowers/plans/2026-08-22-record-agentic-workflow-hardening.md):3/11/30–46 |
| 产品与生命周期 | 统一R1、R2 Spec/Plan及1C-A验证记录；所有11份Spec的Status行，旧Plan/Verification目录清单和M1代表性记录；17份ADR标题/状态/约束及0011等相关决定，不机械通读每条历史运行日志 |
| 构建 | [settings](../../record1/settings.gradle.kts):14–24、[根build](../../record1/build.gradle.kts)全文，app、benchmark、core/data、core/model、core/ui、三feature全部8份模块build；catalog、gradle.properties、wrapper配置 |
| 自动化 | [apiBoundaryCheck](../../record1/core/data/build.gradle.kts):73–143/196、[ApiSurfaceTest](../../record1/core/data/src/test/java/com/paherdin/record/core/data/ApiSurfaceTest.kt):23–37/225–236、[API白名单](../../record1/core/data/api/expected-surface.txt)、apiGuard正负例清单；P6/M1报告任务与脚本规则检索 |
| 业务/数据边界 | [RecordRepository](../../record1/core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt):88–193/351/592/660/707；DTO、Room v2八实体/迁移、FinanceMatrixValidator、ZipPreflightDeclared；主要依赖声明/入口与功能页面接线 |
| UI/启动 | [AppContainer](../../record1/app/src/main/java/com/paherdin/record/RecordApplication.kt):18–39、[NavHost](../../record1/app/src/main/java/com/paherdin/record/navigation/RecordNavHost.kt):48–125、旧Phase闭集/主题与状态约定；当前生产详情/编辑仍有Availability入口，不是正式R2业务实现 |
| 测试/Benchmark | 全部测试路径清单；ApiSurface、AtomicTransferVisibility、RoundTrip及fixture/oracle/报告解析代表样本；[benchmark构建](../../record1/benchmark/build.gradle.kts)、[M1产物脚本](../../record1/tools/verify-m1-artifacts.ps1)的九类门禁/常量/调用路径，未运行脚本 |
| Android安全 | [主Manifest](../../record1/app/src/main/AndroidManifest.xml)、debug/benchmark Manifest、core/data Manifest、[兼容备份规则](../../record1/app/src/main/res/xml/backup_rules.xml)、[data extraction](../../record1/app/src/main/res/xml/data_extraction_rules.xml)；生产源码日志/AI/凭据相关符号静态检索 |
| Skills/本地发现 | [skills/README](../../skills/README.md)和61份SKILL frontmatter；to-spec、to-tickets、implement、code-review、impeccable、tdd等代表路由/副作用；`.agents/skills`与`.qoder/skills` Junction元数据 |
| Git/自动执行入口 | [.gitignore](../../.gitignore)、只读`.git/info/exclude`、有限Git配置项、hooks目录名、worktree列表；`.github`/Husky/目录规则存在性；只检索敏感文件名，不打开秘密文件 |
| 外部 C1 | 仓库外Git HEAD/status、settings/app build、Money/Repository/Database和比较/可行性报告，见§6；没有修改或迁入 |

盘点口径：902跟踪文件中，skills为557文件，core为199、docs为63、app为37、feature为17、benchmark为15；数字用于覆盖定位，不作质量或复用评分。测试路径含core/data的38份test、41份androidTest、12份testFixtures及3份benchmark；app有3份test、6份androidTest和6份benchmark。这是文件数，不是用例执行数或通过数。

### 2.3 Git、忽略、Hooks和环境事实

- `.gitignore` 排除`.idea`、`.superpowers`、`.gradle`、`local.properties`、build、`.worktrees`、hprof、P6/M1本地大体量证据和两条Skills发现Junction。未看到通用`.env`/签名密钥文件名排除；只读索引文件名检查未发现这类已跟踪文件，**没有据此证明仓库绝无秘密内容**。
- `.git/info/exclude`还排除若干`.claude`运行锁/调度/状态路径。排除规则的存在不能证明对应程序或自动任务正在运行；未读取其中私人任务/秘密内容。
- 本地无`.github`、`.github/workflows`、`.husky`、`.codex`目录；标准hooks目录只有`.sample`，有限配置查询未出现`core.hooksPath`。故未发现仓库内启用的CI/Husky/Git Hook，不能断言远端或宿主平台不存在额外自动化。
- Git系统配置`core.autocrlf=true`，本地`core.ignorecase=true`。CRLF提示应与真实内容差异区分，不能通过改全局设置解决本任务；路径大小写与跨平台克隆需要验证。
- `git worktree list --porcelain`还列出`.worktrees/m1-app-shell`（detached，`0990007e…`）与`.worktrees/m2-finance-core`（`codex/m2-finance-core`，`bce0a5a6…`）。未进入这些工作树检查脏状态，更没有重置/合并/删除；“忽略”不代表无须保护。
- PATH可发现git、python和一个路径含`java8path`的Java启动器，未发现android/adb/gradle命令。未启动Java/Gradle/ADB/AVD，故这既不证明实际JDK版本为8，也不证明已有SDK/其他绝对路径工具不可用。
- 两个Skills Junction均指`D:/record/skills`，不是两份复制源码；其忽略和本机重建要求使干净克隆的发现行为不同。

## 3. 约束审计与裁决理由

类别：**保留安全结果**、**保留工程原则**、**过时流程**、**需改造**、**R1.0专属**、**与R2直接冲突（若继承）**、**未核实**。下表均说明原目的、成本、取消风险及替代方式；“退出R2”不等于删除历史。

| 审计项/类别 | 位置与原目的 | 当前问题/负担 | 建议、保留的风险保护与退出风险 |
|---|---|---|---|
| 1 过时流程：主Agent不实现/强制串行派工/三失败 | 工作流:8/60/173–182；为分工、上下文交接及主验收提供明确角色 | 旧模式引入等待/重复上下文/失败计数；当前R2已在文档层退出，不能重复报告为现行禁止 | EXCLUDE该角色机械限制；主Agent可直接执行，协作按收益；继续检查结果，取消角色分离会失去第二视角时用针对性独立审查补足，不凑失败次数 |
| 2 需改造：派发/验收双状态机 | 工作流:64–104/136–171/194–200；防失联、未就绪依赖和假PASS | 每项工作都有task/revision/attempt/failure count、done与accepted两终态；单Agent也维护会形成调度平台 | ADAPT最小任务状态、必要交接/原证据；并发加owner，真正故障重试再分attempt。减少字段的风险是无法续接，因此停止/切换前仍记录下一步/阻塞，不删高风险Operation身份 |
| 3 需改造：全仓唯一in-progress | 根AGENTS:15、index:44、旧roadmap:27/57；防同时改变共享系统 | 全仓互斥会限制独立Spec；但**它本来不禁止同一Spec内多任务并行，设计/文档也已不要求激活** | 建议取消全仓硬锁，保留每Major独立状态和可选当前焦点；以文件/接口/Schema/测试库/设备/恢复代际冲突协调。少了粗锁后必须明确owner与集成验证，不能放任两个Schema改动并发落地 |
| 4 需改造：所有功能都要正式Spec/Plan | 根AGENTS:15、R2约束:35–36，旧roadmap:55–91；防越界实现 | R2约束说微调不强制Spec，但总入口仍要求功能一律Spec/Plan；缺Simple/Feature/Major的边界 | 分级：普通局部修复沿任务验收，Feature保留短行为/范围/验证，Major才正式双文档；小改动也可能高风险，测试级别与规模独立，不能拿“一行代码”豁免金额/安全测试 |
| 5 保留原则、限制适用：ADR与失败 | 旧工作流:164–169、roadmap:79–87、R2约束:93–95；防失败选型被掩盖 | 旧规则已区分技术假设证伪与harness故障，**不是所有失败都要ADR**；仍不宜把冻结PoC全链重批套普通修复 | ADR限长期架构/持久兼容/恢复/安全模型及变更核心决定；原方案内bug/harness修复留任务证据即可。退出过宽触发不能隐藏证伪或更改冻结AC，仍需重审受影响决定 |
| 6 需改造：状态/事实多处复制 | index:9–10、Spec:3–6、Plan:3–6/§11–13、旧roadmap状态；为读者快速定位 | 同步成本、历史“本轮”语态和当前状态混杂；当前Plan仍保留index“未创建”的历史说明 | 状态由Spec元数据/一次批准记录拥有，index只指向，不复制审批全文；Plan只写当前执行状态/依赖，历史引用保留。减少复制后入口须强，不能把批准事实藏进无人知道的文件 |
| 7 需改造：当前R2规则本身 | R2约束:29–41/75–109；已有效取消强制派工并保护安全 | 尚无完整任务/T0–T4分级，仍唯一活动；与批准Plan §9.1强元数据要求需要显式对齐；该文件未跟踪 | ADAPT或按本提案替换同一文件，不叠加第二权威；先审查后G2/G4同步。不能把上轮入口改动或本审计当本次新治理已获批准 |
| 8 R1.0专属：固定模块图/四阈值 | overview:7–48/143–154；根build:19–75/138–199精确集合与边；保护旧层次及测试工具隔离 | 模块名/数量是旧产品实现，四个条件须同时成立（含双消费者）可能阻挡合理单消费者构建隔离 | EXCLUDE精确图和四条件硬门槛；ADAPT单向职责/测试与release隔离。新图用实际构建收益举证；退出精确枚举后仍查循环、UI越权及测试资源泄露 |
| 9 R1.0专属：公共签名白名单/编译路径 | core/data build:73–143/196；ApiSurfaceTest:23–37/225–236及expected-surface，保护内部实体/投影不外写 | 签名、Room生成类例外、AGP的built_in_kotlinc路径、android-36路径均绑旧模块；复制会把合法新API当违规 | 旧检查不接入新根；只按R2真实对外能力建必要边界负例/静态检查，不追求每个public声明逐字锁死。取消全签名锁后重点保护DAO/敏感路径/Agent commit不可见等真实边界 |
| 10 与R2直接冲突：M1全禁网络和冻结依赖 | security:7/44/92；verify-m1-artifacts:68–69/106–159/200–216/1133–1142；原本验证完全离线M1 | 脚本不仅禁INTERNET，还固定包名/版本/主题资源并拒绝若干HTTP/DI/测试依赖；DeepSeek正常通信不能通过旧政策 | EXCLUDE旧脚本作为R2整包门禁；ADAPT“source/merged/final权限+密钥哨兵+release隔离”方法，按授权Provider/无AI零请求校验。退出全禁网络后要测试越权/撤销/迟到外发，不能只允许INTERNET了事 |
| 11 R1.0专属：旧事实模型/Repository | Model/NewNote无正文；Room v2八实体；Repository:88–193/592/660/707；FinanceMatrixValidator含开账/转账 | EntityRef/AccountEntry、Vault绑定、附件、集合替换牵入旧语义；正式UI仍占位，不能计入R2已完成量 | EXCLUDE默认继承这些实现；REFERENCE事务/验证经验。R2从已确认字段/CAS/增量关系设计服务，旧文件库原样保留；新写边界的故障与回归成本必须计入，不能把重建当免费 |
| 12 需改造：UI闭集/模块/主题 | conventions:17–23，core/ui/Phase，AppContainer/NavHost，M1图标/资源检查 | 一个全局Phase闭集、四tab/转账/Projects入口和既有主题不是所有R2 Run/Proposal/编辑器的正确模型 | EXCLUDE旧视觉/导航和全局Phase强制；ADOPT不可假成功/保留输入/明确状态，按领域状态建模。具体外观/交互仍由负责人批准，不能拿“换新”授予Agent设计权 |
| 13 R1.0专属：旧验收整包 | testing:65–118/230–265，roadmap M1–M8；余额/转账、附件去重、N-1/N-2/legacy bootstrap、P4 300ms/P6单日60ms、AppLock/三字段诊断/CSV/30天dogfood | 这些旧约束各有旧场景依据，但不对应全部R2范围；当前也已有“别机械全跑”的说明，不能说旧策略一律每次跑全套 | REFERENCE旧用例/测量技术，EXCLUDE旧数字与不适用产品门禁；R2 T0–T4按变更范围和63条AC覆盖，发布仍全量。退出旧30天等不减少新产品已确认的实际验收 |
| 14 保留安全结果、改造实现：备份恢复 | R1架构§9、R2 Spec§6；旧ZipPreflightDeclared:33–39、Vault与P5 | 安全快照/拒绝损坏/原子切换/回退必须；但旧format1/2、多代布局、100000项/20GiB/200:1等不是R2格式决定 | ADOPT BK/跨代际Pending隔离等结果，ADAPT预检/fault/oracle方法，EXCLUDE旧协议和数值默认。R2 G0需批准新格式/资源阈值，不能因不继承旧值就不设限 |
| 15 保留安全结果、限制旧威胁模型 | security:26–36/53–100，Manifest/备份XML；防泄密、误恢复和非授权进入 | AppLock、三字段无timestamp、7日/1MiB属于旧规定；静态扫描的当前生产路径未见完整AI/Keystore诊断实现，文档要求不等于已有能力 | ADOPT授权外发/无AI/Key保护/系统备份排除，ADAPT最小安全日志；EXCLUDE旧AppLock/诊断产品及精确格式默认。放松格式不能记录Key/私人正文/原始工具结果；Keystore/网络真实行为仍需正式实证 |
| 16 需改造：技能变成流水线 | 工作流:27–38；to-spec:9/19、to-tickets:58–63、implement:11–17、code-review:58–74；为复用方法 | 发布tracker/建立工单、自动commit、强制TDD/双Agent审查、impeccable首次launcher下载可能超任务权限；存在本机发现依赖 | ADAPT按任务/版本/副作用选择，EXCLUDE自动流水线。技能不能授权commit/发布/安装/UI定案；去掉技能强制后仍要有行为与验证，不以“可选”忽略明确调用/更高优先级要求 |
| 17 需改造：不可携带证据/版本 | R2 Spec§2；外部C1/Downloads/共享聊天，ignored benchmark冻结证据 | 当前能读取不代表clean clone/新机器可获取；哈希不能重建原件；旧版本组合未验证Koog | ADAPT最小来源/许可/hash/可取位置与正式重验；不复制个人目录/秘密。证据不可得时标未核实、不接纳代码，按冻结行为重建；不重新推翻已确认C1路线 |
| 18 需改造：Git/自动化入口与权威 | 根/index修改、新规则未跟踪；README:5–29仍旧状态/旧vision/roadmap；.gitignore/本地excludes；无启用CI/hooks | 新旧默认入口不完全一致；从当前工作区得出的政策无法只靠HEAD重建。未发现CI自动运行check，不能声称必跑/已通过 | G2受控纳管/统一适用范围及入口，G3新根独立验证入口；旧check保持。无需先建庞大CI，先固定可复现命令，未来授权后CI调用同一入口。加入签名/环境忽略和差异审查仍不代替内容保密 |
| 19 需改造：粗粒度串行与细粒度证据 | R2 Plan:101–108/348–350/375–385；为公共写边界稳定与完整fault矩阵 | B1→B2→B3→B4是已批实施顺序，不是七模块必然技术依赖；每任务统一attempt/原始selector合同对普通文档偏重 | G4仅提议按实际读写依赖再审核可并行部分、合并相同测试证据。保留CAS/故障点/强停的真实断言及Operation ID；本轮不改DAG或跳过依赖 |
| 20 未核实：外部/运行中的约束 | 远端CI、宿主Agent配置、已安装SDK/JDK、现有ignored build产物和真机 | 只读本地仓库无法证明运行能力、远端必跑检查或所有传递SDK行为 | 保留UNVERIFIED，G3/A1按获准环境确认。不能用缺PATH命令判技术失败，不能以历史PASS或未发现代码宣告安全通过 |

## 4. 重要资产接纳矩阵

以下每项为建议，依赖相应后续批准。ADOPT指接纳原则/结果或适合的标准能力；除非明示，不代表把旧代码直接复制。ADAPT行逐一给出八项复用论证；无法证实数值优势时只陈述可观察的改动优势和未知。

### 4.1 ADOPT / ADAPT：价值、依赖、成本和退出

| 资产/裁决 | 1 价值、2 旧依赖、3 冲突 | 4 相对重建优势、5 接入/验证成本 | 6 长期负担、7 退出、8 未核实风险 |
|---|---|---|---|
| 冻结R2架构/安全结果 ADOPT | R2需求直接要求；依赖R1+批准Spec，不依赖旧模块；无产品冲突 | 复用已确认行为避免重开选型；只建立工程/测试映射，成本低但正式实现/验证非零 | 一个产品来源；未来变更须负责人裁决；实现、版本和协议仍未实测 |
| 根入口/当前R2规则 ADAPT | 已有角色简化/红线可用；依赖SpecPlan，需去唯一活动/全功能正式化总门槛，见审计3/4/7 | 指向一个短Policy比新增多套workflow便宜；G2文档改动与路由一致性验证 | 少数来源、禁止重复状态；可替换同一policy回退入口；G1尚未批准，clean clone尚缺纳管 |
| 短派发/所有权/实际证据 ADAPT | 防并写/假PASS，依赖任务真实冲突而非旧调度状态机；与R1无冲突 | 已有goal/scope/tests表达成熟，比实现调度账本简单；仅协作/交接时补owner与依赖 | 无协作零派发账本；不使用子Agent即可退出；未知实际并行收益需测，不能保证多Agent更省 |
| Spec/Plan/Verification制度 ADAPT | Major的行为/依赖/结果分离有效；旧全局Stage/每任务双批需退出 | 保留V1现有完整规划，简单任务只记短合同；G4修流程而不重写产品，需核63条映射 | 同一状态一处拥有，Feature沿已有记录；不再需要的额外台账不引入；负责人尚未批准风险分级与取消全仓锁 |
| ADR制度 ADAPT | 保留长期决定理由；依赖真实产品/持久兼容变化，不依赖每次失败/固定阶段 | 复用简洁决策格式比重建审查组织低成本；按重大决定补why/alternatives/impact | 少而可查，普通实现退出ADR；历史17份不改；未来哪项决定跨阈值仍需判断 |
| Gradle wrapper/catalog/标准测试能力 ADAPT | 可复现版本/依赖集中有价值；标准插件/平台，不依赖旧七模块，旧pin只参考 | 采用标准构建机制避免自制；新根最小配置，验证Koog/Kotlin/AGP/Room/Compose/KSP/metadata/许可 | pin只拥有一处，移除依赖可退出；具体新版本组合、缓存与SDK能力未验证，无继承旧pin优势证据 |
| 服务封装/类型错误/确定性注入原则 ADOPT | 防UI越权、便于CAS/故障测试；原理独立于旧Repo；符合Application Services | 使用Clock/Zone/ID/协程注入与业务错误比散落UI处理简单；只注入实际需控制的变量 | 少量真实接缝；可局部替换，避免每层interface；R2接口/状态类型尚待设计，不接纳旧全局Phase |
| C1 Money/稳定ID/短事务 ADAPT | C1有代码/局部实证；依赖精确源码/测试，不依赖实验Schema/UI；CAS/代际需补 | parser/格式化和稳定ID失败重试路径比重新发明更有依据；审查/去固定时间路径、host边界与磁盘正式重验 | 算法小、普通包内维护；可按同合同重写；源码取得渠道/许可、正式整合和极端边界尚未新验 |
| Room/Compose/磁盘观察/fault测试方法 ADAPT | 真实原子性/重启/恢复必须；旧业务夹具不可用，独立观察模式可用 | 已有独立SQLite/PID/故障交错范式比只用fake可靠；重新绑定R2 identity/模型、合成fixture，成本中等 | 集中少量可用helper，不移植整套框架；可用平台API替换；测试原始数据/SDK在其他机器可得性未证 |
| 产物安全核对方法 ADAPT | source/merged/APK、release测试资源隔离、权限/秘密哨兵仍保护真实风险；不依赖M1固定常量 | 从九类核验中提炼目标，避免盲信源码Manifest；重写少量R2断言，不能直接改旧脚本常量混用 | 与实际包/权限合同同源；新检查可独立替换；传递依赖/最终APK/OEM行为未跑，成本无实测数 |
| 安全/隐私结果与最小日志 ADAPT | 无AI零请求、授权Provider、Keystore、Key不出Prompt/日志/备份、未授权工具拒绝；旧诊断UI/字段不依赖 | 已确认R2威胁边界可指导少量断言；重建授权/凭据/故障测试，不能复用未实现功能 | 不增加诊断平台，必要事件按用途定义；需要新收集/外发须重审；设备失效/攻击边界和真API尚未验证 |
| Android备份排除规则 ADAPT | R2仍排除系统备份/迁移与密文凭据；XML可参考但须匹配新包数据域 | 平台机制/双规则/source–APK检查比另造备份安全机制简单；按新目录验证全部域和credential排除 | 小声明随SDK维护；改为其他受支持机制需新批准；不承诺当前XML复制后即覆盖所有OEM |
| ZIP预检/独立往返oracle方法 ADAPT | 防穿越、损坏/资源耗尽，BK真实要求；旧logical type/数值/Vault协议不继承 | 复用双重计数、规范化比较/fault矩阵知识避免漏项；重新实现最小R2格式与状态，成本不低 | 格式/阈值一次拥有，逻辑备份可替换；R2G0协议和数据代际/聊天一致性尚待审核，历史往返不能背书 |
| Git忽略/选择性暂存/可选worktree ADAPT | 保住用户修改与构建隔离；依赖Git标准能力，非旧分支/成本模式 | 已有build/cache/Junction exclusions可据新根精简；补新输出/证据/秘密路径规则需批准，worktree仅冲突需要时用 | 一个忽略源/明确清理边界；不需要并行就不用worktree；忽略项内容和其他工作树脏状态未审，不能自动清理 |
| Skills来源pin/按需方法 ADAPT | 来源/许可和显式路径消除同名歧义；不依赖固定技能流水线 | 保留README pins和有用方法，比整包默认调用成本小；逐任务选择适用版本/副作用，不安装、不改vendor | 更新入口只一处，可停止使用任一skill；目录61个name无重复不代表全局harness无同名冲突，实际发现优先级未执行 |

### 4.2 不进入R2或仅参考的资产

| 资产 | 裁决 | 原因、取消继承风险及保护方式 |
|---|---|---|
| 旧主Agent禁实现、强制worker、三失败接管、完整Attempt Ledger、强制双审查 | EXCLUDE | 角色和记账不是数据正确性条件；用真实owner/风险审查补协作风险；旧workflow/加固计划留存 |
| 全仓唯一活动硬互斥、所有修改全生命周期、候选禁止起草Plan的通用禁令 | EXCLUDE作为新默认 | 任务规模/读写冲突更精确；保留重大决定批准与实际冲突协调；现行规则须G2/G4后才能变更 |
| 固定七模块/精确依赖图与旧checkModuleDependencies | EXCLUDE | 新工程自由组织，不能在旧root直接加模块却声称已隔离；旧检查继续保护旧构建 |
| 旧API白名单、generated-FQCN列表、固定AGP编译输出路径 | EXCLUDE | 不是新业务接口合同；重建真实敏感边界的必要检查，旧guard/test保持 |
| 旧EntityRef/AccountEntry/余额转账/Repository/DAO/Room v1/v2 | EXCLUDE默认实现 | 不符合CNY简单事实/新CAS/会话；新的持久层须重新证明完整性，不迁旧库、不删源 |
| 四tab/旧NavHost/AppContainer/全局Phase/主题图标 | EXCLUDE默认实现 | 旧UI仍有占位；由负责人指导新设计，技术模式可参考，素材/视觉不自作决定 |
| 旧Vault/legacy bootstrap/format1/2及旧兼容迁移义务 | EXCLUDE | Q6无必须继承旧数据；保留原库和恢复资料，未来若新需求出现另批，不做预防性兼容层 |
| M1产物脚本整包/冻结包名版本图标/HTTP禁令 | EXCLUDE | 直接与R2授权AI、BYOK及新身份冲突；提炼安全方法而非复制1300行脚本 |
| 旧搜索中心/仪表盘/附件/CSV/AppLock/诊断产品与30天dogfood门禁 | EXCLUDE作为R2 V1默认 | 不在已确认完整V1必交范围；仍保留R2底层检索/精确汇总及安全结果，不扩P1 |
| P4/P6数据与性能、M1启动报告/真机参数、旧Benchmark模块和解析器 | REFERENCE | 只能证明旧版本/旧工作负载；使用指标/oracle方法需新预算/数据/设备批准；旧原件和ignored证据保留 |
| Record1 Roadmap、17份ADR、历史Spec/Plan/Verification/研究 | REFERENCE | 日期、状态、决定是历史事实，不是R2自动义务；必要采纳明确指向具体结论，保留原文，不批量迁移/重写 |
| 当前缺少的仓库CI/Hooks | REFERENCE为盘点事实 | 没有可直接接纳的本地CI资产；先固定单一验证入口，后续授权CI复用它，不能虚构现有流水线 |
| C2 Fork和无关保留依赖 | REFERENCE | C1已选定，不重开选型；保留比较来源，不迁入C2备份/后台/额外业务 |
| 旧分支/worktree、外部实验/原始报告、用户现存文件 | REFERENCE且受保护 | 不因不复用而清理；没有cleanup或数据迁移授权。本轮未检查所有忽略物和个人资料 |

## 5. 测试与安全的重新映射

建议的T0–T4是执行组织方式，见提案，不替代产品条件。保留Spec中的B-AC1～9、BK1～5、SD-P1～4、AI-AC1～11、TP-01～08、CTX-01～10、SES-01～16（共63条映射）及J-E2E1～6，按相同fixture/测试接缝允许一份原始证据映射多条要求；不得把“共用证据”改为“跳过断言”。

| R2真实风险 | 保留结果 | 可退出的旧实现/门禁 |
|---|---|---|
| 错金额、旧编辑覆盖、组合部分写 | Long分/溢出、业务和关联版本CAS、Proposal创建幂等/revision CAS、A/B与Operation不可变/幂等、异常取消回滚、原生确认 | AccountEntry余额、转账零和、旧updatedAt锁、全API签名形式 |
| Session/ChatMemory竞态 | Room权威历史、当前输入一次、最终回复+Run终态原子、Interrupted不自动续跑、迟到失效、删除不丢Operation核查 | 调度失败次数、worker验收仪式、Koog Persistence自动续跑（仍不启用） |
| 授权外发/秘密泄露 | Provider告知/持续授权、无AI零模型/工具外发、不整库后台上传、Key不入源码/日志/Prompt/ZIP/系统备份、Keystore | 旧APK无INTERNET、固定HTTP库黑名单、旧三字段诊断/固定保留期 |
| 恢复损坏/跨代际重复执行 | 有界预检/独立逻辑比较/原子切换与回退、聊天/隐藏关系完整、旧Pending不可执行、恢复后Key/授权重建 | 老包兼容、多代Vault/旧阈值及格式版本的自动继承 |
| UI错误或越权定案 | 真实页面/数据/返回和IME状态、提案未确认零写、负责人批准D-FIN/D-NOTE/D-TIME/D-AI/D-SAFE/D-SYS | 旧主题/图标/全局Phase、临时默认Material冒充最终验收 |

网络本身已是R2授权功能，审核对象是“发送给谁、何时、哪些数据、撤销后是否继续”，不是重新决定要不要完整Agent。源码/merged/APK权限和系统备份检查仍有必要，目标随R2合同重写。未验证任何真API、最终APK/OEM或加密实现；本报告不是安全认证。

## 6. 外部C1与可取得性

- 只读位置：`D:/record2-experiments/phase1c-a-20261022/c1`，HEAD `4b4b18af2fd4f3ea0410e394952bdebd4911d679`，status空；28输入文件摘要`ef176f2727adae94096e1846fff7970369e610a48ddf4cf3e5575fe1b15878d4`。
- settings仅`:app`；build声明AGP/Kotlin等实验组合，app为min/target/compile34、Room2.6.1、Navigation2.7.7、Compose compiler1.5.14，不能由实验通过推断正式Koog兼容。
- Money:7/17有精确解析/格式化；Repository:103/118–141保留提交前ID/草稿与事务经验；Note:55–65无正式expectedVersion/删除CAS，links:83–94需要基线/增量并发保护；六表schema1不是正式物理模型。
- [比较报告](D:/record2-experiments/phase1c-a-20261022/evidence/comparison/report.md)与[可行性](D:/record2-experiments/phase1c-a-20261022/evidence/c1/feasibility.md)SHA-256分别`589561d3b648f26747e9bf351f59ef623f6f231478c7f4df677041e35e6decae`、`7ba1be2d3eb6073d2df4a02cff51d94b652a5cc1573e1f25f7ea9f87b3d0c4e1`；历史10个Android合同/2次宿主重启/3个host测试只证明A；B/BK/SD未执行。
- 本轮仅核必要代码/版本/报告与Git身份；不重跑、迁入、导出测试库或复制本机路径/密钥。拟复用项先确保精确源、许可、fixture、原始证据可重新取得，再在正式工程中验证。现有本地证据不等于团队或干净克隆可取得。

## 7. 对现有Spec/Plan仅提出的流程修订

建议在迁移G4单独提出Plan流程修订：将全仓唯一active改为真实范围/冲突控制；普通任务不用完整task/revision/attempt合同；按需owner和阶段记录；把B1–B4串行顺序中仅为编辑同一文件造成的依赖与真实业务依赖分开；文档历史/工具失败与当前步骤分开；授权和执行记录只拥有一次。

上述不是已获准的新执行路径。产品/业务依赖、冻结实验重审和安全故障断言不能因“流程项”标签被删；如果拟并行改变了尚未稳定的数据/服务契约，先审核契约和集成证明。负责人UI批准仍逐组必需。本轮两份Spec/Plan连同状态/审批文字均原样保持。

## 8. 限制、保护及本轮验证

未逐行审阅913个文件：已覆盖所有实际构建文件/治理入口/17份ADR索引、测试/脚本规则及代表性源码。未通读全部历史Verification、557份skill资源、1326行产物脚本的每个解析分支；对大脚本采用门禁常量/入口/检查路径检索。`.codex_session_extract.txt`仅在保护集合中计算摘要，没有解析/审阅其完整会话内容；也未审阅私人配置/秘密、忽略目录中的全部数据库/APK/备份、其他worktree内容、全局Agent配置或远端仓库。

部分最初候选测试文件名未存在，随后按`rg --files`定位真实fixture目录；未把猜测路径作为证据。命令输出较长时有截断，重要判断已用定向带行号检索补核，不以截断摘要代替运行证据。

实际只读检查使用`git branch --show-current`、`git rev-parse HEAD`、`git status --porcelain=v1 --untracked-files=all`、`git diff --cached --raw`、`git ls-files -z`/`--others --exclude-standard`、有限`git config --show-origin --get-regexp`、`git rev-parse --git-path`、`git worktree list --porcelain`，配合`rg`/`Get-Content`/路径元数据与内联Python静态盘点。没有Pull/Reset/Clean/Checkout/Rebase/Commit/Push、构建/测试/设备/API、安装/SDK/秘密/数据库或规则迁移。

最终文档检查记录：内联Python检查两目标完整非权威标记、本地来源链接/代码围栏/行尾/覆盖清单；读取新旧`git ls-files`集合后逐一比较写前913既有文件hash，另比较外部C1输入/HEAD/status、冻结架构及已批准Spec/Plan；`git diff --check`检查既有受控差异，新未跟踪草案另验。实际结果如下，不把旧测试结果计本轮PASS：

- **PASS：** 38个本地链接存在；20项审计判断、三类任务、T0～T4五层、G0～G5六阶段、六UI设计组和全量R2验收保留声明齐全；围栏/行尾无误。初次标识检查因提案未显式写英文原则及“六条”旅程字样失败，补清文字后重验通过，没有改动产品合同。
- **PASS：** 913既有文件逐个摘要不变；新增集合恰为获准两草案；Branch/HEAD/暂存区不变，C1的28输入路径/HEAD/status不变；Spec仍9 completed/2 approved/0 in-progress，R2执行未启动。
- **PASS：** `git diff --check`返回0。既有workflow/testing-strategy有LF→CRLF提示，未改其内容或配置。没有把Git普通diff漏掉的未跟踪新稿当成无需检查。
- **NOT RUN：** Gradle/单元/Android/Benchmark/设备/真实API/数据库和迁移验证；本轮PASS仅指静态盘点、文档及保护检查。

本轮保护集合和外部C1输入应与§2.1/§6相同，新增集合只能是本报告及治理提案，暂存区、HEAD和现行活动状态不变。审计完成后停止，等待负责人审阅；下一阶段迁移见提案G0～G5，本轮不实施。

### G0之后的G1记录更新

2026-10-23，按负责人审查只补强现有提案的统一生效出口及最小可携带权威资料集合，并在两稿记录G1核心原则批准；不再次执行全仓审计、不另建治理文档。本次写前共915既有文件，只有两份讨论稿允许修改；原G0保护证明和命令结果作为历史保留，不能误作本次新增文件说明。其余913既有文件、Branch/HEAD和暂存区须保持写前状态，G2～G5未实施，规则未切换，Git未暂存/提交。

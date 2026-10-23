# Record 2.0 工程治理提案与安全切换方案

**CORE PRINCIPLES APPROVED — G2/G4 DOCUMENT PREPARATION AUTHORIZED — NOT EFFECTIVE — NON-AUTHORITATIVE**

- 日期：2026-10-23（Asia/Shanghai）。依据[全仓审计](record2-engineering-governance-audit-20261023.md)，不把现有R2规则或旧治理模板直接复制为答案。
- **当前状态：** G0审计完成、G1核心原则批准；负责人已授权§7.5的G2/G4文档准备。本轮参与文件和校验见§10；Git纳管/Commit/规则生效、G3机器改动和G5产品执行未授权，不增加治理层级。
- 本轮只准备指定入口、流程及必要来源候选，更新本提案执行记录；不改冻结架构、产品行为/验收、旧机器检查或源码，不暂存/提交/推送。核心原则与文档准备批准不等于整体治理已经切换。
- 本地事实：V1 [Spec R1](../specs/record2/v1/spec.md)与[Plan R1](../specs/record2/v1/plan.md)均为approved，Plan §13有负责人批准；Execution未启动、活动none。这里引用批准事实，不追加或扩大批准。

## 1. 目标与设计选择

如果从零组织R2，默认用一个能够端到端执行的主Agent、一份短任务说明、一个相关验证入口，以及足够的产品/数据安全合同。复杂流程只由具体风险触发，既不为旧角色分工保留仪式，也不为追求新治理再建调度平台。

建议：**Main Agent Capable, Subagents Optional；主Agent有能力直接完成，子Agent按需使用。** 工程总原则是 **Clean Rebuild First, Selective Reuse When Justified；干净重建优先，复用须举证**。不能把“旧规则不适用”解释为现行批准边界已经失效；以下改变须先经过G1～G4，当前只审阅。

| 决定 | 建议 | 与当前的实质差异/边界 |
|---|---|---|
| 工作角色 | 主Agent可直接调查、设计、实现、调试、测试、集成；委派由实际收益决定 | 当前R2入口已允许，建议保留；旧强制派工、三失败接手、普遍双审查退出，不照搬其账本 |
| 任务制度 | Simple Change / Feature Task / Major Change，规模与风险分开判断 | 普通功能代码不再自动要求完整Spec/Plan/Verification/激活/全量AC；V1整体仍是Major，现有批准不改 |
| 并发 | 移除全仓唯一活动的硬互斥，以实际修改/资源冲突协调 | 不为并行强行新建多个Spec；同一Spec多任务原本就可并行。独立Major可各有状态，当前焦点只是便利指针 |
| 审批 | 一次具体任务授权覆盖必要正常动作；稳定契约/红线/范围变化升级 | 不把每次构建/复测/文件编辑都重新审批，也不把一次开发许可变成无限设备/凭据/数据权限 |
| 验证 | T0～T4，由影响决定需要的层级与断言 | 不继承旧产品全套门禁；现有R2全部验收结果保留，成本优化来自共用接缝和相关性，不来自删断言 |
| 文档 | root入口+index+一个R2 Policy；Major沿现有Spec/Plan/一份Verification | 不新增独立R2 Agent Workflow、Roadmap和Testing Policy三套权威；普通任务无需新目录/台账 |

## 2. 建议的 Record 2.0 Engineering Policy 正文

> 本节及§3–5的核心原则已获负责人认可；具体落地文字仍须在授权范围内完成G2/G4一致性核对，并通过§7.3统一门禁后才生效。§6之后是迁移说明，不是每个任务的必读步骤。

1. **以新产品为起点。** 冻结R1、获批产品Spec和负责人决定拥有行为权威。旧代码、构建、规范和测试仅在复用收益被说明并获相应接纳后适用。以业务包和小接口开始，仅为真实构建/依赖/维护收益拆模块；不继承旧七模块、Schema、Repository、导航、主题和Vault义务。
2. **主Agent端到端负责。** 授权范围内可直接设计、编码、调试、验证和交付。独立调查、互不冲突的实现或有价值的第二视角才使用子Agent，并遵守用户/运行环境限制。没有协作就不创建派发合同；没有新的根因证据就不机械重试，更不等待三次失败才接手。
3. **按任务而非每个动作授权。** 先明确目标、行为边界、修改范围、验证和权限。范围内连续完成必要工作；稳定设计/产品/UI改变或越出范围才升级。个人红线不变：删除文件/目录/Git历史；修改环境文件/秘密/凭据/CI；Schema变更或数据迁移；公开发布/生产部署，均先说明理由风险并取得具体同意。
4. **数据与AI安全是结果合同。** 金额整数分、业务/关联版本、提案revision CAS/创建幂等、单Session有效Run/活跃Proposal、不可变Operation、原生确认、A/B原子/回滚/幂等、Room权威历史、Interrupted、跨代际Pending隔离、完整备份及BYOK/Keystore/授权外发，按获批R2合同落实。工程简化不能扩大六种Proposal操作或删减完整V1。
5. **UI由负责人决定。** 所有最终界面/布局/信息层级、视觉样式/字体配色间距、图标插画、AI角色表情、动画和未确认交互，遵循负责人方向→候选→审查调整→明确批准→实现。保留D-FIN/D-NOTE/D-TIME/D-AI/D-SAFE/D-SYS及`DESIGN-OWNER：待负责人指导与确认`。技术状态/导航/数据合同可并行，默认组件不代替最终验收。
6. **验证与风险匹配。** 执行§4所需层级和获批验收，报告真实命令/结果及未验证风险。数据安全必须有真实磁盘/并发/恢复证据，新UI必须有可观察行为。已验证且未变化的同一构建/输入不反复重跑无关套件；新变更或新风险必须重验相关部分。
7. **冲突由owner和集成控制。** 并行任务明确写范围、共享契约和测试/设备资源；冲突写串行或先隔离再集成，不覆盖用户/其他执行者内容。独立审查按风险选择，真实标注自查/独立审查；最终接受依靠差异和实际结果，不能只靠Agent自报PASS。
8. **只维护帮助交付的记录。** 普通任务保留目标、状态、验证与下一步/阻塞；并行加owner，真实故障重试才编号，高风险业务身份照合同持久化。重大行为/架构变化先更新权威决定；普通实现选择不写ADR。失败保留事实、按根因处理，只阻塞依赖它的工作，不改验收制造PASS。
9. **Git、工具和规则也有边界。** 保留既有脏状态与历史；只暂存本任务差异，Commit/Push按明确权限，破坏性操作另批。Skills按相关性/版本/副作用选择，不自动授权安装、发布、提交、强制TDD/多Agent或UI定案。新规则说明它保护的风险、验证方式和维护成本；不能举证收益就不加入或缩小适用范围。

## 3. 任务、生命周期、ADR与并发

### 3.1 分级任务制度

| 级别 | 适用与最少设计 | 批准/记录/完成 |
|---|---|---|
| Simple Change | 范围清楚的文档/命名/格式/小局部修复，不改变产品、持久、安全或共享行为合同。代码少不自动等于低风险 | 负责人明确任务请求可构成任务授权，仍不得越红线；简短说明改什么/如何验，完成报告或已有任务记录即可，不要求新Spec/Plan/Verification/ADR/roadmap/active状态 |
| Feature Task | 可界定的功能或较复杂缺陷，沿已有产品方向；明确输入/可观察结果、范围、依赖/测试及权限。可作为现有Major Plan的一行任务 | 开始前确认行为/写范围及必要设计；用短任务合同/现有Plan，不为每任务建三文档。高风险修复即使一行也升T3并核权限；触及批准的Major边界依其变更协议 |
| Major Change | 新产品/长期架构方向、正式数据/恢复/兼容/安全边界等重大决定，或无法用短任务清楚说明影响的工作 | 保留正式Spec（行为/AC）与Plan（依赖/范围/验证），明确批准可一次同时涵盖两者，记录一次上下文。执行前设计/Schema/外部权限按实际需要就绪，完成以产品验收和一份Verification收口 |

R2 V1本身仍是Major，现有Spec/Plan已经批准；并不因本分级自动获得A0/B0/F0或功能执行授权。其内部局部修复可挂回原任务，不重走整套立项；改产品/数据合同仍须负责人确认。本轮没有新建正式Spec，也不修改已有审批记录。

### 3.2 活动状态建议

建议保留Major的`draft → approved → in-progress → completed`含义，但去掉全仓“只能一个in-progress”的硬限制。状态属于该Major，批准事实由Spec元数据和一次批准记录拥有；index仅链接，可显示当前焦点但不承担第二份手工状态账本。Simple/Feature不用模拟一套Spec状态机。

并发先问是否共享不稳定契约/写入/运行资源，不按Spec数量推断安全。一个V1 Spec内的多任务并行完全可以沿同一Plan；只有真正独立Major才需要多个Spec。存在跨项依赖时，下游只等待它实际需要的已接受产物，不因另一个无关任务环境失败一概冻结全仓。

这需要G2/G4明确修改根AGENTS:15、index活动规则、现有R2 Policy及Plan相应流程引用。**迁移前仍遵守当前唯一活动规则；本轮active仍none。** 历史roadmap和completed Spec保持原状态，不用修改它们来伪造新制度历史。

### 3.3 最小交接、审查与失败

单Agent不需要派发协议。委派只交代目标/非目标、写范围与owner、必要依赖/约束、如何验证/交付；只有确实需要区分失败重试才编号。不要给普通文档编辑维护dispatch_failure_count、双重done/accepted账本或每命令Execution Pointer。

独立审查适用于真正值得第二视角的提交/恢复/权限/架构风险，可用独立reviewer或负责人设计审查，不要求实现先派给worker再由main验收。明确要求独立审查的已批任务仍执行，不能把自查称独立；所有风险级别都不能用“审过了”替代测试。

编译/断言失败→诊断最小可复现和相关代码；环境/权限缺失→标阻塞并继续独立工作；工具/harness故障→修工具后重跑受影响证据；冻结技术选择被证伪→保留原证据、重审受影响决定。业务事务/恢复状态机的失败恢复仍按冻结合同，不能与Agent调度状态混淆。

### 3.4 ADR触发条件

| 需要ADR或更新已有决策 | 通常只记任务/验证，不另写ADR |
|---|---|
| 新的长期架构/跨边界依赖决定；持久格式/身份/兼容或恢复协议；信任边界/凭据/外发模型；改变已批准核心不变量；替代已有这类决定 | 保持合同的bug修复、内部函数/包整理、例行版本兼容修复、临时实现细节、普通测试失败或harness修复 |

先问“是否改变需要未来维护者理解的稳定决定”，而非“是否出现过失败”。工具链兼容修复若确实改变长期发布/支持或构建边界，再升级ADR；不以工具名自动判定。实验记录保留假设/证据/结论，不要求每次失败都有新ADR。ADR最少说明选择、理由、替代项、影响/验证与批准来源，不增加独立签字链。

### 3.5 并行与隔离

| 工作 | 建议安排 |
|---|---|
| 只读调查、独立测试分析、已明确分组的设计 | 可以并行；负责人UI设计指导不等待A0工程结构结论 |
| 接口已稳定且写入/fixture互不重叠的实现 | 可并行，明确每项owner/范围；必要时可选隔离worktree，不强制每任务创建 |
| 同一文件、未稳定API、Schema/迁移/备份格式、同一共享记录/代际 | 串行设计/合并，或先批准共同合同后分支隔离；不同文件也可能属于同一冲突单元 |
| 同一模拟器/包/库/Provider预算或私有测试数据 | 指定资源owner或隔离不同目标；设备/数据权限分别明确，不能靠不同Agent ID当隔离 |
| 跨任务集成/最终联合验收 | 一个集成负责人按最终版本接受；只接受可取得且与构建/fixture/环境对应的证据 |

## 4. T0～T4测试与证据策略

规模与验证风险是两个轴；Simple不自动只跑T0，Major也不要求每个补丁都跑T4。

| 层级 | 用途/真实完成依据 | 触发与执行频率 |
|---|---|---|
| T0 | 文档/格式/静态/构建配置基础检查、链接/差异/敏感边界检查 | 所有交付的适用部分；不用为普通文档启动Android。已有可用检查优先，不为治理另造大型lint平台 |
| T1 | parser/金额/日期/纯算法/状态和参数验证、适当属性边界 | 受影响局部修改；bug在合适接缝给可失败的回归证明，不写镜像实现的测试 |
| T2 | 正式Room磁盘/Compose/Android/SAF等真实集成，重新打开同一数据源 | 触碰真实持久/页面/平台交接；组件Preview/内存fake/编译只补充，不代替实际行为 |
| T3 | CAS/并发、A/B、取消/强停、未知提交、完整备份/损坏拒绝/恢复回退、授权/Key/迟到外发等高风险验证 | 改对应协议/状态/持久边界时跑相关矩阵；保留原始runner/事实比较、Operation/版本/代际与PID等必要证据，不能因少日志而丢掉证明 |
| T4 | 完整V1最终版本联合验收、真实Koog+DeepSeek和获批设备/平台门禁 | 里程碑所需组合与V1最终收口；保留B-AC/BK/SD-P/AI-AC/TP/CTX/SES共63条映射及六条J-E2E联合旅程，未跑/失败不能PASS |

T0–T4按影响组合，不必每层都造独立测试类。同一真实用例覆盖多个AC时共享原证据，映射到每项实际断言；不同数据/构建/环境的历史PASS不能挪用。C1和旧Record PASS只能证明当时实验，接纳后必须在正式工程验证。

一般任务记录精确命令、结果和必要环境即可；金额/事务/恢复/安全保存足以复核的报告/fixture版本/seed/前后状态。普通文档不生成大日志包、全仓hash或多角色签字。一次跨仓迁移/资产接纳可使用有针对性的快照/hash保护，不能将审计手段常态化。

开发期间跑受影响集合，契约/集成阶段跑对应组合；通过后只有新变更/失败/未解决风险才扩大或重跑。缓存证据必须明确哪份未变构建与输入仍被覆盖。无关模块/旧产品测试不作新任务门槛，但相关失败必须阻止其依赖和最终V1收口。

API34隔离环境优先；API36/真机、真实Provider/Keystore/OEM/性能按当前R2 Plan和后续批准执行。保留R2必要预算/fixture设计，不沿用旧单日60ms、旧搜索300ms或30天dogfood为默认；新的明确预算必须按原批准要求建立后才能宣称性能验收。

## 5. 任务级权限、数据与Git

| 风险/动作 | 合理授权粒度与升级条件 |
|---|---|
| 普通可逆任务动作 | 明确任务包含其必要非红线读写、局部测试和格式化本次改动；范围内不逐文件/逐补丁重复问，不自动格式化/整理他人内容 |
| 构建/依赖/开发环境输出 | 一次具体执行包约定构建根、版本/来源、输出/缓存和必要已有工具；范围内正常复测无需重问。新安装、全局环境/SDK改动、新依赖或越界输出按权限/稳定设计升级，不能将所有工具调用视为同风险 |
| 稳定设计/共享契约 | 行为/正式模型/公开能力/重大依赖/批准UI变化先出具体影响再审查；局部实现细节不自动触发重大审查 |
| Schema及数据初始化/迁移/恢复 | 明确库身份、版本、合成/真实、目标范围/备份/回退与验证。用户Schema/迁移红线保留；“仅开发”也不能推得可覆盖旧库 |
| 设备/Provider/凭据 | 一次限定AVD/真机、包/数据、安装/运行范围，或合成API测试接收方/Key管理/费用预算。范围内复测可连续执行；真实用户数据、换接收方、权限/预算变化再次批准。禁止输出真实Key/秘密和无关个人数据 |
| 临时测试产物 | 可以管理任务专属产物，但**删除文件仍属个人红线**。可在具体任务中预先明确允许清理的隔离路径/对象；没有这种同意不擅自删。不能以“可逆”取消用户红线 |
| Commit / Push / worktree | 任务可明确允许何时提交本任务差异；不混入无关暂存内容。Push另明确远端/分支/可公开范围；worktree创建/使用按实际任务收益和授权，不重置/合并/清理既有工作树 |
| 高风险/不可逆 | 删除历史/文件、Git历史改写、真实数据覆盖/迁移、环境/真实密钥/CI、公开发布/生产及非隔离破坏操作，先说明理由风险取得具体批准；未授权外发用户数据始终禁止 |

数据默认本地，完整AI按用户主动任务及有效Provider授权按需处理可见数据，不整库后台上传；无AI零模型请求/Agent工具外发。Key由Keystore保护且不进入Prompt、普通库、日志、手动ZIP或系统迁移；日志只收必要去敏技术信息，若新增诊断产品/对外收集另审，不继承旧三字段/7日/1MiB作为永久格式。

Agent仅能prepare六种确认的变更，不能commit/删除/恢复/任意SQL/文件/系统命令；用户原生确认、A/B、幂等及跨代际恢复资格不随工程/Agent组织变化。备份包括会话/隐藏关系与一致提交结果，隔离预检、资源限制、原子恢复和回退必须实证；系统备份排除仍需要source/merged/final与平台验证。

## 6. 最小文档结构与权威链

建议实际落地只保留以下现有位置，不再添一套治理目录：

```text
AGENTS.md                         短入口、共同红线、适用范围
docs/index.md                     查找来源；链接状态，不重复审批全文
docs/engineering/record2-engineering-contract.md
                                  一个R2 Policy：任务/权限/协作/T0–T4
docs/architecture/record2-v1-unified-architecture.md
                                  冻结架构事实（本轮不改）
docs/specs/record2/v1/{spec,plan}.md 已批准Major行为/实施计划
.../verification.md               真正执行获准后，一个Major一份结果记录
docs/architecture/decisions/*      真正长期决定，明确R1.0/R2适用范围
```

| 问题 | 建议答案 |
|---|---|
| 根AGENTS多长/写什么 | 约一屏、约20–35行作为可读性目标，非硬性lint；只放当前入口/共同边界，不能再堆阶段历史、worker状态和所有业务细节 |
| 是否需目录AGENTS | 只有正式R2构建根获批创建且命令/工具边界确有差异时才加；不为每个包复制同样规则，现在不创建 |
| 旧规则如何界定 | 现有AGENTS.record1、旧workflow/conventions/testing/roadmap/ADR/Spec保持历史；index明确R1.0用途。已明确接纳到R2的原则在唯一R2来源表述，不沿链接隐式整包导入 |
| 独立agentic-workflow/testing policy | R2不需要另建；一个Policy容纳短协作与T0–T4，具体命令和故障矩阵在Plan/Verification。旧文件保留供旧工程使用，不同时管R2 |
| 是否需传统Roadmap | 当前V1用已有Plan里程碑足够；新范围明确后可简短列候选，不制造另一份任务/批准状态表。旧M1–M8不作为R2阶段顺序 |
| 如何精简Spec/Plan/Verification | Major三者分别拥有行为/依赖/实际结果，引用而不抄写；Simple不新建，Feature用短合同/现有Plan。工程工具失败详情不永久挤入当前实施步骤，历史记录不删除 |
| ADR如何简短 | 一次真实长期决定一处记录，指向验证；不为每次bug/失败创建，不复制规则手册 |
| 新建哪些文件 | 本轮只新增两份讨论草案。若批准，优先替换现有R2 contract正文并更新入口；没有必须再创建的新治理权威文件，执行Verification/未来目录AGENTS按真实需要另授权 |

权威链建议：负责人具体裁决/批准 → 对应产品/架构/设计合同（各拥有其事实）→ R2 Policy执行方法 → 任务范围与实际验证。不同域事实冲突先指认真正owner并提出受影响变更，不能靠“更新的流程文档”覆盖安全/产品，也不能靠旧ADR覆盖已经确认的新产品。

未纳管资料先按Spec §2.4明确接纳：必要产品/验收/批准来源可经审查纳入最小自包含版本；大原始报告保留受控可取档案及hash/来源；拟复用源码/许可/测试必须可重取。不同机器不能依赖本机Downloads、共享聊天可用性、ignored证据或Junction自动存在。涉及环境/身份/私人内容只保留必要去敏引用，不整包复制。

## 7. 分阶段、安全、可回退的治理迁移

G0～G5是本提案的迁移阶段，不是已经激活的产品任务或正式V1发布里程碑。审查时可一次批准若干**明确文档范围**，仍不自动批准代码/Schema/设备/CI等红线。

| 阶段 | 前置、允许改动与需要批准 | 验证/历史保护/失败处理 |
|---|---|---|
| G0 完成本次审计 | 当前授权；只新增本提案及审计两草案，盘点当前权威与机器检查，不改变行为 | 校验标签/链接/覆盖、913既有文件与外部C1摘要、HEAD/index/状态；不跑构建/设备。发现目标冲突停止，不覆盖；交付即等待审阅 |
| G1 批准治理方案 | 核心原则已于2026-10-23获负责人批准，记录见§9；补强要求和G2/G4具体写范围收敛于本稿 | 不把原则批准扩大成迁移/代码/纳管/Commit权限；保持现行规则，产品R1不重开 |
| G2 受控准备权威入口及可携带基线 | 本轮§7.5文档准备已授权，实际结果见§10；与G4构成一次逻辑治理切换，不额外建并存Policy。纳管/提交/生效仍待权限 | 可分批编辑/审核，但候选文件不分别生效；完成§7.3统一门禁及§7.4可携带性证明后，才记录整体切换。不能只改根入口先取消旧门槛，也不能自动暂存/Commit |
| G3 解绑机器约束 | 先静态核对每个新工程构建/脚本入口；实际新根/代码/依赖写入和验证需A0工程设计及对应任务授权。旧build/tasks/script/CI保持，不删除、不批量放宽 | 证明R2命令指向其真实wrapper/settings，不include旧root/project/source；新检查只覆盖R2合同。旧七模块/API/产物/旧性能检查继续属于旧根。无新工程时本阶段只能交设计/检查清单；发现旧检查误作用即停止对应入口，不靠skip测试或改旧常量通过 |
| G4 同步R2实施计划流程 | 在独立授权的流程修订范围内，与G2同批对齐Spec/Plan，重点准入/active/记录/协作/验证频率及引用；保留2026-10-23产品批准和全部行为/AC，真实业务依赖改变另审 | G4不滞后于G2规则生效；两者可分批完成编辑，但整体门禁未过一律沿用切换前约束。校验63条/六旅程/六设计组、DAG和权限无损，不自动激活 |
| G5 正式新工程实施 | 治理切换就绪，A0工程/身份方案、B0数据设计、所需UI批准及具体代码/Schema/环境/设备/API授权满足后，按批准任务执行 | 主Agent可执行，协作可选；T0～T4按影响，Major执行状态在其范围内记录。任何数据不一致/真实权限越界停止相关工作，保留可验证旧数据/证据；发布/清理历史另获同意 |

G3分为治理侧的静态解绑方案与新工程中的实际验证：G5准入只要求前者和G4批准，不要求尚不存在的新构建先实测通过；后者在G5对应获准任务中完成，并阻止依赖该未验证构建的下游接受。不能形成“先建工程才能完成治理准入、先完成治理准入才能建工程”的循环。A0/B0/F0设计仍可在各自独立授权后先行，不要求为设计先跑构建或设备。

### 7.1 拟调整的具体入口与机器检查（均未执行）

| 未来对象 | 处理和边界 |
|---|---|
| AGENTS.md / docs/index.md | G2明确当前R2权威、普通任务例外与active状态语义；避免状态重复。当前已经隔离的旧规则不需要再移动/删除 |
| docs/engineering/record2-engineering-contract.md | G2在同一文件采用经批准短Policy和分级表，替代候选规则；不加第二个并行工程规范 |
| README.md | G2仅更新当前入口/适用范围，旧完成记录保留为历史，不抹去已有成绩/失败 |
| AGENTS.record1.md、旧engineering/architecture/roadmap/ADR、历史Spec | 默认不改；仅G2明确批准的必要适用范围注记，不能追溯改变旧规则/批准 |
| 根settings/build的checkModuleDependencies、refresh/Activity路径断言 | G3验证只由旧构建执行；新根若需要边界检查按真实职责设计，不复制精确七模块集合，也不删除旧检查 |
| core/data apiBoundaryCheck/ApiSurfaceTest/expected-surface | 旧模块保留；新根只为真实敏感外部边界采用必要正/负例，不导入旧签名/Room生成类/编译输出路径 |
| tools/verify-m1-artifacts.ps1 | 旧工程保留整包；新检查重建R2 source/merged/final安全断言，不继承固定包名/版本/主题/禁HTTP集合；实际写脚本另授权 |
| P6/M1 JavaExec/Benchmark/报告解析器 | 新根不挂旧发布任务，保留原报告/fixture/数据；新性能预算/工作负载由R2任务审核，不改变旧PASS/FAIL |
| .gitignore、Junction/Skills发现、可复现命令入口 | G2/G3分别批准文档/环境或脚本范围；补必要输出/秘密排除、技能来源选择/本机重建说明。不能自动安装/下载/建Hook |
| CI/CD、签名、SDK/环境 | 当前本地没有CI可修改，不能虚构迁移；未来确需新增时单独说明具体配置和风险，保持个人红线。不把CI上线变成所有设计任务的前置 |

### 7.2 回退原则

每阶段以本阶段写前工作区为基线，先核暂存/脏/未跟踪内容，保护用户变更；不要求clean tree，不Pull/Reset/Clean或以远程main覆盖本地。可正常编辑被批准的文件，不因文件已脏一概拒绝；所有权不清或重叠时只停止受影响部分。

文档切换失败，修复或以补偿补丁撤回本阶段特定差异；不自动回滚其他任务/删除新增资料。机器入口设计失败则保持旧检查和旧数据，修正新根接线/合同，不能靠注释报错通过。真实数据/恢复有安全问题时按已批准恢复协议处理，治理迁移本身不给迁库/删除/公开权限。

### 7.3 G2/G4统一生效门禁（既有阶段的共同出口）

G2和G4是一项逻辑切换，可分批编辑、审查或形成多个Git提交，不要求强行一个Commit。编辑期间以切换前明确记录的规则版本/工作区快照为执行依据；新文本标记为候选，不因某个文件先写完、先纳管或先提交就单独生效。根入口、Policy与Plan不能各自宣称相反的active/任务准入规则。

共同出口必须同时满足：

1. **文字一致：** AGENTS/index/唯一R2 Policy/Spec/Plan的任务分级、active、授权、协作与验证方法一致；现有“主Agent可实现”保留，不重复改革已正确部分；旧机器检查的实际适用范围明确。
2. **产品无损：** 架构R1与Spec产品/安全合同未改义，63条验收、六旅程、六UI设计组及历史批准仍可核对；只改获准流程/来源引用，不借此改变业务依赖。
3. **基线可携带：** §7.4的最小集合在经批准的Git纳管/提交后有可验证提交身份，必要文档从该提交可读、引用自包含。外部资产的身份/获取限制明确，不能用工作区可读或“已暂存”声称干净克隆可恢复。
4. **保护与验证通过：** 批准范围内的差异/敏感信息/链接/引用和权限案例检查通过；已有用户修改、暂存区、历史源/数据不被混入或破坏；未做代码/Schema/设备/API/发布操作。
5. **一次记录生效：** 在既有G2/G4执行记录中记录生效日期、批准来源、参与文件与提交集合/最终提交身份，整体生效；不新增G6或逐文件再审批。若Git执行尚未授权或任何检查未过，只能报告候选准备完成，不能宣告G2/G4切换完成。

门禁前继续切换前约束；门禁后全部相关文件采用同一新规则。失败只停止切换并修复候选，必要时以受控补偿补丁撤回本轮差异；不reset、删除、改历史或让部分新规则先管任务。

### 7.4 G2交付：最小、完整、可携带的R2权威资料集合

这是一份资料集合清单，不是新治理层。优先建立治理/实施依据的可恢复基线，不将所有旧文档、Skills、实验输出或私人目录批量纳管。

| 最小必需内容 | 拟使用位置/处理 | 可携带验收 |
|---|---|---|
| 当前工程规则、适用范围和切换依据 | AGENTS.md、docs/index.md、唯一R2 engineering contract；批准/共同出口事实随Policy候选及Plan流程说明保存，本提案保留非权威执行细节。仍被入口引用的AGENTS.record1.md保留清晰历史身份 | 有限清单经审查纳管；无同时控制R2的矛盾规则，干净取得后不依赖本次聊天才能知道适用规则 |
| 冻结统一架构 | 已跟踪的docs/architecture/record2-v1-unified-architecture.md，原hash/commit不变 | 从提交取得相同字节，不重写ARCH-01～06 |
| 已批准V1 Spec/Plan及批准依据 | 现有spec.md/plan.md；只做获准流程/来源对齐，保留Plan §13原话/日期和未执行状态 | Git可取得两文件和批准事实，不只保存摘要或聊天链接；治理切换不授予产品执行 |
| 必需产品/验收决定与来源 | 优先用一个最小自包含来源文件，例如待批候选docs/product/record2-v1-decision-baseline.md，承接已确认1B/1C-A裁决与AI/TP/CTX/SES原语义、版本取代关系和源hash；Spec继续拥有正式行为/AC，来源文件不另建验收矩阵 | 63条和六旅程的必要解释/批准可在Git内理解；不依赖Downloads/失效共享链接。仅忠实提取已确认内容，不新增/削减要求；若选择纳管已有必要原稿，则替代摘录方案，不两套并建 |
| 外部C1身份与必要取得说明 | 同一来源文件列精确仓库/commit、所需代码/许可/fixture/证据清单和实际可行的获取步骤/限制 | 不把D盘路径当跨机器获取渠道，不虚构远程URL。尚无获批可取得渠道就明确未核实；不接纳不可取得的代码，可按冻结技术/行为重建。治理/产品资料的自包含不等于全部外部实验已可重放 |

实际纳管、暂存、Commit必须另有明确许可，允许范围与文档编辑分别记录。提交前检查完整候选差异与来源是否含环境/私人/密钥信息；不顺带提交既有workflow/testing-strategy等无关修改、SDK/缓存/APK/大日志、原数据库或完整Downloads/共享聊天。

可携带性不能靠“本机文件存在”验收。得到Git和验证目录的具体授权后，从批准提交的对象/清单在隔离临时位置重建必要文档，检查入口、相对链接、批准状态与来源完整性；不运行构建或恢复旧数据。验证目录创建/产物保留与清理范围单独明确，未获删除许可保留产物。仅本地Commit能证明提交对象可重建，不自动证明远端可取得；Push、发布或外部归档另获同意，结论须注明分发限制。

### 7.5 G2/G4文档准备范围（本轮已授权；Git和生效未授权）

- **允许修改候选：** AGENTS.md、docs/index.md、docs/engineering/record2-engineering-contract.md、README.md；R2 V1 spec.md/plan.md仅限流程准入、active/记录/协作/测试组织、来源链接及一次治理修订说明。
- **允许新增候选：** 仅在必要且选定摘录方案时新增docs/product/record2-v1-decision-baseline.md，承载最小来源/批准/外部资产取得说明，不再新增审计或治理层级文件。
- **允许更新执行记录候选：** 本提案记录实际G2/G4进度/差异/共同门禁结果，不把每个文件建立独立批准链。
- **只读或待纳管候选：** 冻结架构、AGENTS.record1.md、必要历史输入；不改旧源码/规范/ADR/机器检查。将哪些已有未跟踪源文件纳管，须在审查候选清单后明确，不自动全目录加入Git。
- **本候选范围不包含：** git add/Commit/Push、真实规则生效、工程创建/构建/依赖/Schema/设备/API/数据库/CI/秘密、历史文件移动删除。文档准备完成后提供精确差异与拟提交文件清单，Git许可明确且共同出口全部通过后才进行切换。

该清单已由负责人本次“授权”确认，收敛为一次有边界的G2/G4准备任务，不重新审计或扩大产品范围。若后续明确授予已可审查的Git范围，按该具体授权办；不能从本次文档授权自行推得。

## 8. 影响、验收和G0交付限制（历史；G1记录见§9）

| 预期影响 | 可观察收益/未来测量，不虚构百分比 |
|---|---|
| 普通开发时间/上下文 | Simple/Feature少读旧文档、不建三文件/attempt台账；记录开始到首个有效修改的步骤、强制读文档数量、纯流程文件数 |
| 主副Agent成本 | 不为串行任务重复派工；统计实际委派数、等待/交接返工、对独立审查实际发现的问题，不预设Agent越少或越多越好 |
| 审批/ADR | 同一具体授权不重复问，普通失败不建ADR；统计同一决定重复确认次数、因真正范围变化发生的升级与不必要升级 |
| 测试成本 | 用受影响集合/共享fixture与证据去重；统计运行哪些层级、耗时、被旧无关检查阻塞次数；新增/变化断言不能被缓存旧PASS掩盖 |
| 安全和最终V1 | 红线、全量AC/真实故障与端到端保留；以漏报回归/数据不一致/秘密哨兵/恢复比较检查结果评估，不能用时间降低抵扣 |
| 治理自身复杂度 | 权威仍只用现有少数文件，不增加调度注册表/多套状态机/固定技能流水线；若分级频繁争议或规则新增多于实际风险，缩小规则而非继续加层 |

迁移方案接受标准：每项ADOPT/ADAPT有审计中的价值/依赖/冲突/成本/维护/退出/未知论证；新Policy能回答“谁做、哪些小改可直接做、何时审查、怎么测试、怎么保护数据”；G0～G5每步有前置/范围/批准/验证/保护/停止回退。**本轮不宣称迁移已完成或规则已生效。**

本轮与审计报告同一写前基线：913既有文件、4个已修改/11未跟踪路径、分支HEAD和暂存区保护；本提案和审计是唯一新增文件。详细来源、未审范围、命令与限制见审计§2/§8。没有构建/测试/设备/API/SDK/数据库/秘密/CI/Commit/Push或历史文件移动删除。

完成两草案后停止，等待负责人审阅；下一轮需要的是对治理方案及具体G2/G4等写范围的真实决定，不把本轮讨论自动当成V1实施批准。

## 9. G1核心原则批准与补强记录（历史；本次执行见§10）

- **日期/来源：** 2026-10-23（Asia/Shanghai），负责人本次对话：“批准治理方案的核心原则，不再增加新的治理层级；下一步让 Codex 按明确范围修改实际规则，把这次改革真正落实到仓库中。”同次审查明确：G2～G5实际迁移尚未授权，实际Git纳管与Commit仍须另获授权。
- **已确认：** 干净重建/复用须举证；主Agent端到端、协作按需；任务规模与风险独立；Simple/Feature/Major与T0～T4方向；不强制旧机器检查；最小文档体系；V1功能/安全验收与UI/历史资产保护。全仓唯一Spec的取消方向按具体替代文字收口，不把当前仍存在的限制直接宣布失效。
- **两项补强：** G2/G4共同出口在§7.3，最小可携带资料集合在§7.4；不另增加阶段、工作流或重复审计文档，G2/G4候选写范围见§7.5。
- **当前边界：** 本次只记录G1并修订既有两稿；G2/G4尚未执行，权威规则/Spec/Plan/源码与审批状态保持。本提案仍不是生效规则，外部资产未自动纳管、Git未暂存/提交。
- **记录校验：** 两稿链接/围栏/行尾和G1/共同出口/资料清单检查通过；915个写前文件中仅两稿变化，其余913路径逐一摘要不变，Branch/HEAD/暂存区保持；未新增文件，未改变Spec/Plan或活动状态。未运行构建、设备、数据库、API或Git写入操作。

## 10. G2/G4文档候选准备记录（2026-10-23）

### 10.1 授权与生效边界

负责人对“按上述清单执行G2/G4文档准备，完成后交付差异与拟提交清单，暂不暂存或Commit”的请求回复“授权”。这覆盖§7.5指定文档及一个必要的最小来源文件；不是Git、最终生效或产品任务授权。G1核心原则批准仍保留，不追加产品/数据/UI决定。

为满足统一生效而不让半套候选先管任务，AGENTS/index/Policy保留写前正文在`EFFECTIVE-BEFORE-SWITCH`区，候选替换正文用明确标记的markdown代码围栏保存；后续获准整体采用时替换，而不是永久叠加两套规则。Spec/Plan原批准正文不改，仅追加候选流程/来源说明；README保留旧原文为历史。当前仍按切换前约束，未生效候选不能授权任何功能动作。

### 10.2 实际差异与保护

| 文件 | 本次差异 | 保留/验证要点 |
|---|---|---|
| AGENTS.md | 增加准备状态说明、现行正文边界和短候选入口 | 原现行正文逐字保留；候选Simple/Feature/Major和多Major冲突控制不提前生效 |
| docs/index.md | 保留现行路由并准备短候选路由 | 状态由Spec/Plan拥有，候选必需来源指向Git内基线；原未跟踪/外部路径只作历史来源 |
| docs/engineering/record2-engineering-contract.md | 保留现行规则，准备短Policy候选 | 主Agent能力不重复改革；分级任务/T0～T4/红线/UI/失败/记录统一，无新独立Workflow/Testing Policy |
| README.md | 增加R2入口/未实现与切换待完成说明 | 原Record1.0 README全文留作历史，不将旧PASS冒充新产品完成 |
| docs/specs/record2/v1/spec.md | 追加§10未生效流程/来源候选 | 原R1全部正文和批准元数据逐字保留；产品/数据/安全/验收无删改 |
| docs/specs/record2/v1/plan.md | 追加§14授权/流程差异/共同出口说明 | 原R1全部正文、§13批准、23节点44边DAG与真实依赖逐字保留，未启动任务 |
| docs/product/record2-v1-decision-baseline.md | 唯一新增文件，约250行自包含来源摘录候选 | 原生功能/D1–D5/Q6/J1–J6、B/BK/SD、AI/TP/CTX/SES按源核对，不另建映射表，不纳入个人目录/线框/完整聊天/日志 |
| 本提案 | 记录授权、准备差异、门禁和拟Git清单 | 非权威执行记录，不增加治理层级或赋予Git权限 |

相对本轮写前快照的规范化文本差异：AGENTS `+33/-0`、index `+38/-0`、Policy `+80/-0`、README `+12/-0`、Spec `+16/-0`、Plan `+32/-0`，新来源基线 `+248/-0`；原现行区/批准正文由逐字比较证明保留，不将HEAD旧版本的全部差异算作本轮工作。本提案仅更新状态/授权和执行说明；完整本轮差异可由保存的写前文本与当前文本核对，未另写第三个Diff文件或Git对象。

公开来源只读HTTP200，ARCH-03 R1、ARCH-04 R1/R2、ARCH-05原版/R1五份正文逐一匹配既有hash；只取必要验收文字。1B/AI原稿及C1原件未修改，外部材料未整包迁入。C1精确commit/干净状态已核实，`git remote`为空；新环境获取渠道未核实，不能宣告实验源码可自动重取。

### 10.3 拟纳管/提交清单（未执行）

最终权威核心集合为9份：AGENTS.md、AGENTS.record1.md、README.md、docs/index.md、唯一R2 engineering contract、冻结架构、V1 Spec、V1 Plan、决定来源基线。冻结架构已经跟踪且不改；其余8个修改/未跟踪文件需按具体Git授权处理。历史原稿和大日志不因作为摘录来源而自动提交。

为保留候选阶段的准备说明/授权与共同出口上下文，建议审查后同时纳管本提案作为第9个**拟Git差异路径**（非权威执行记录），不要求读取它才能理解最终已生效规则。若只提交最终生效文本，可将必要一次生效事实留在Policy/Plan并不纳入本提案；具体清单由Git授权确定，不自行选择性暂存。

不纳入：既有agentic-workflow/testing-strategy修改、旧业务/Gradle/Manifest/ADR、1B/1C-A整套原稿、Skills、实验代码/报告全包、SDK/cache/APK、个人Downloads/完整共享聊天、数据库或密钥。AGENTS.record1.md仅为保持历史入口可取得，不改变其内容或对R2重新施加旧义务。

候选允许用多个提交保存资料和整体切换；不要求把最终提交SHA写进该提交本身。当前暂存区为空、HEAD未变，没有新增可证明最小集合完整可恢复的提交，更没有Push或远端分发证明。

### 10.4 共同出口检查状态

- 文档T0：**PASS**。链接/围栏/行尾、现行区原文保留、候选正文一致性和受权写范围检查；README原文、Spec/Plan原批准正文与63条/六旅程/六设计组、原DAG保留检查。Markdown路径解析器已支持历史README中含空格/括号的合法链接，没有为检查器的误报改动原链接。
- 来源T0：**PASS**。6条D/Q、6步J、9条B、5条BK、SD原文/页面行为，以及11条AI/8条TP/10条CTX/16条SES逐字核对；新文件无个人目录、签名/SDK路径和视觉线框。
- 保护：**PASS**。写前915文件，除7个获准既有文档外其余908文件逐一摘要不变；新增集合恰为决定来源基线。冻结架构、原稿、旧规则/代码/机器检查、Branch/HEAD/暂存区和C1身份/输入保持。实际命令为只读Git状态/文件清单/差异检查、内联Python文档/摘要与JavaScript原文/差异重建自检；没有生成检查脚本文件。
- 最终静态结果：**104个本地链接通过，现行区与Spec/Plan原正文保留，DAG仍23节点44边且无环，Spec仍9 completed/2 approved/0 in-progress；C1仍28输入路径、原commit、干净且无remote。** 这些是文档/保护检查，不是构建、产品或提交对象可携带性证明。
- Git可携带/统一生效：**PENDING — NOT AUTHORIZED**。未进行纳管/提交对象重建或候选整体采用；不能因工作区检查通过宣称G2/G4切换完成。
- 产品执行：**NOT RUN / NOT AUTHORIZED**。未构建、安装依赖、操作设备/数据库、调用模型API、改Schema/CI/秘密、移动删除历史、Commit或Push。仅为来源核对读取负责人提供的公开页面。

候选文字的静态案例核对（不是产品测试）：

| 案例 | 现行/候选适用结果 |
|---|---|
| 当前授权文档准备、没有active Spec | 按已明确文档范围完成T0；不触发功能激活 |
| 一行CommitOperation修复 | 生效后任务规模不降低验证强度，仍需相关T1/T3与既有任务权限；本轮不实施 |
| 两个独立Major | 生效后不因Spec数量串行，但须明确写范围/契约/资源无冲突；当前未生效仍不放宽旧约束 |
| 同一Schema/同一设备并发写 | 仍协调owner与串行/隔离，不以多Agent身份当隔离 |
| 未获设计批准的布局/图标/角色 | 仍等待对应DESIGN-OWNER明确批准，不用模板定案 |
| 缺Schema/设备/Key/Provider/真实数据许可 | 只停止依赖动作，不扩大授权，不编造PASS |
| 已批准具体隔离对象的常规复测 | 在该范围内连续做，不逐次重复确认；范围变化/红线仍升级 |
| 候选文本/来源静态检查通过但没有Git/生效许可 | 保持NOT EFFECTIVE，共同门禁未完成，不启动A0/B0/F0或产品代码 |

后续获准生效时，必须整体采用入口/Policy候选，并将Spec/Plan匹配的旧流程段落对齐为候选表述或明确标记修订前历史；不能只加一句“新规则优先”就保留互相矛盾的现行准入。该最终采用动作及Git/提交对象重建仍未执行，当前保留区与围栏候选只用于安全准备和差异审查。

文档候选准备完成后交付差异和拟Git清单，等待实际Git/生效权限；不重复要求负责人批准已完成的普通文档动作，也不自行进入产品开发。

## 11. 最终采用Diff审查包（仅审查，未执行切换）

2026-10-23。负责人接受G2/G4文档准备交付，允许最终切换的精确差异审查；明确保留实际Git Commit和规则生效的单独确认权。本节只展示拟采用内容；围栏内的新规则没有生效，也不能作为本轮执行授权。原候选文件继续保持准备态。

### 11.1 精确范围与差异基线

- 分支：`codex/preserve-2026-10-21`；HEAD：`57e1b68b309b0459aed1765778b7da4cdfff6f6b`；暂存区为空。
- 推荐后续只选择性纳管下表8个路径；冻结架构是第9份必需资料，已在HEAD且保持原样。讨论/审计报告（包括本文件）、未跟踪历史1B/1C-A原稿、既有workflow/testing修改及其他路径均不进入此次拟提交清单。
- 下方为 **HEAD → 最终采用稿** 的完整统一Diff，包含5个当前未跟踪文件的全文；不是仅列行数。它同时包含此前经审阅的R2文档工作，不能作为当前脏工作区的直接apply命令。所有旧资产原地保留；只从R2入口移除临时双正文和被取代规则。
- 当前准备态 → 最终采用稿也已生成并通过`git apply --check --whitespace=error-all -`只读检查；未执行apply。最终内容仅在内存生成。
- SHA-256列针对最终UTF-8/LF正文，供批准后逐文件比对；不声明它是现有文件、Git提交或Git blob身份。行尾转换不混入正文审查。

| 拟提交路径 | Git动作 | HEAD→最终正文Diff | 最终正文SHA-256（LF） |
|---|---|---|---|
| `AGENTS.md` | 修改已纳管文件 | +14 / -60 | `8ca14acbccd230147453dbb773d7e00ec627a33208075ff4ca478ec34655e164` |
| `docs/index.md` | 修改已纳管文件 | +17 / -22 | `e9563aae47fd466687061dd1f48bbbe87163f6870d144abdb628d1ada569b25f` |
| `docs/engineering/record2-engineering-contract.md` | 新增纳管现有工作区文件 | +67 / -0 | `fd082addb29225bc04285d8535932ce9d8d285badecf17e7b26f02ccfcf6a9ef` |
| `README.md` | 修改已纳管文件 | +12 / -0 | `6e00362e1473b51737cf8374c8e074ad22820ee760f19a4a91b92a36662ae5c4` |
| `docs/specs/record2/v1/spec.md` | 新增纳管现有工作区文件 | +358 / -0 | `b5b8529ceeee068f70cf676866bcbb89d7fc377c963c0bd5f182b6c09a7f3f20` |
| `docs/specs/record2/v1/plan.md` | 新增纳管现有工作区文件 | +527 / -0 | `b953fb73b809e4d15bd0e2b986a300b6af242bdb69719d6d4a2fd2e6a520a1ea` |
| `docs/product/record2-v1-decision-baseline.md` | 新增纳管现有工作区文件 | +248 / -0 | `a8a1c82c3919a10732954d0a7ae920aa48bea6e9a12e869d1472d86bcd84b475` |
| `AGENTS.record1.md` | 新增纳管现有工作区文件 | +76 / -0 | `b1d6035cf788af92398e6a0833e5e22e87c6aeaa68162d93c73e0b1f1452b3e7` |

### 11.2 关键审核点与验证结果

- 根入口/路由/Policy采用单一正文，删除临时保留区、候选围栏及“候选未生效”的准备声明；旧R1契约保存在AGENTS.record1.md。最终正文仍明确共同切换条件，防止部分文件先行生效。
- Spec/Plan直接对齐旧条款：全仓唯一active改为各Major执行状态与真实冲突控制；每任务完整attempt合同改为风险分级记录；旧workflow不再控制R2派工。Major产品/设计/执行、Schema及外部授权仍分别受控。
- 逐字校验PASS：Spec §1.1–1.2、§3、§4–7，Plan §11–13历史/产品批准，来源基线§2–6原摘录，旧README正文及旧契约保存稿。63项唯一验收、六旅程、六UI设计组保留；Spec/Plan仍approved、未执行。Mermaid文本不变，23节点44边、无环。
- Patch重建校验PASS：从HEAD正文/新文件空基线在内存重建全部8个目标，逐字匹配目标；这是补丁自检，**不是实际Git提交对象重建证明**。冻结架构SHA-256保持`5de8670fc60a7226d5e30f409be62f35ded55da5d78353d4fb81bd035fe9cefa`。
- 最终8份目标共81处本地文件链接；拟提交树中77处有文件，4处只属Spec E03/E04历史溯源：1B原稿及1C-A三文档仍未纳管，干净克隆不能打开它们。已明确非默认实施前置，其必需行为/批准/验收由自包含来源基线承接。不能将“必需来源闭包”写成“所有历史链接均可取得”。
- R2文档可重建性须等获准提交后用实际对象验证；C1外部源码无已核实获取渠道，仍不能宣称可重取。缺C1源码只阻止依赖它的代码复用，不阻止按冻结方向干净重建。
- 本轮只追加本节审查产物，其余915个写前路径按字节摘要保护；无新增文件。下方Diff未写入任何权威文件；没有stage/Commit/Push、构建、设备、数据库、凭据/API或产品任务执行。

### 11.3 完整拟提交Diff（待负责人逐项审阅）

<details>
<summary>展开完整Diff：8个明确路径；未应用，未纳管，未生效</summary>

````diff
diff --git a/AGENTS.md b/AGENTS.md
--- a/AGENTS.md
+++ b/AGENTS.md
@@ -1,68 +1,22 @@
-# Project operating contract
+# Record workspace operating contract
 
-## Product boundary
+Record 2.0 follows [docs/index.md](docs/index.md), its [Engineering Policy](docs/engineering/record2-engineering-contract.md), the frozen product contracts and the explicitly authorized task. Read only the sources relevant to that work.
 
-- Native Android, minSdk 34, local-first.
-- Current scope is Tech PoC, followed by MVP.
-- V1/V2 ideas do not authorize code, schema, interfaces, or empty modules.
+Record 1.0 rules in [AGENTS.record1.md](AGENTS.record1.md) apply only to explicitly scoped legacy work. Its code, data and history remain protected; no old module/Schema/navigation/Vault/check is inherited by default.
 
-## Required reading
+## Shared boundaries
 
-1. Read this file.
-2. Read docs/index.md.
-3. If an active spec exists, read the unique `in-progress` spec and only the core documents it references. For lifecycle work, read the target draft or approved spec instead.
-4. Read the research report only for evidence or license review.
+- 默认中文沟通；代码、命令和标识符用英文。
+- Preserve user modifications, untracked files, data and Git history; scope changes to the authorized task.
+- Explain reason/risk and obtain consent before deleting files/directories/history; changing environment files, secrets/tokens or CI; changing schemas or migrating data; or publishing/deploying publicly.
+- Secrets/private user data stay out of source, logs, fixtures and commits.
+- UI/visual/icons/layout/AI-role/animation and unconfirmed interaction decisions belong to the owner; implementation waits for the relevant design approval.
+- Use apply_patch for text edits; verify proportionally and report actual results. Agent claims, compilation or screenshots do not prove data integrity.
 
-## Sources of truth
+## Task execution
 
-- Product: docs/product/*
-- Architecture and data: docs/architecture/* and accepted ADRs
-- User experience: docs/experience/*
-- Engineering quality: docs/engineering/*
-- Stage order: [docs/roadmap.md](docs/roadmap.md)
-- Current active spec: none — [MVP M2 — finance core](docs/specs/mvp/m2-finance-core/spec.md) is `approved` (2026-09-04) after delegated independent review; owning core documents are synchronized and [ADR 0017](docs/architecture/decisions/0017-m2-finance-schema-normalization-and-backup-v2.md) is accepted. M2 remains inactive until its plan is explicitly approved and schema/migration execution consent is recorded. [MVP M1 — app shell](docs/specs/mvp/m1-app-shell/spec.md) is `completed` (2026-09-04) with all 19 acceptance criteria PASS. Tech PoC P0–P6 are `completed`.
-- Current change when active: the unique `in-progress` spec under docs/specs/*
+The main Agent may deliver end-to-end; delegate only when useful and allowed. Coordinate owners for conflicting writes; use risk-based validation and review.
 
-## Non-negotiable data rules
+Use Simple/Feature/Major rules in the Policy. Major implementation needs the applicable approved Spec/Plan, design and execution permission; Simple/Feature do not automatically create new lifecycle documents. Active is a state of each authorized Major, not a whole-repository mutex. Shared contracts/resources still require conflict control.
 
-- Money uses integer minor units; Float and Double are forbidden for amounts.
-- Account balance must be reproducible from AccountEntry.
-- Financial aggregate writes are atomic.
-- Production destructive migration is forbidden.
-- A backup feature is incomplete until automated round-trip restore passes.
-- Timeline and SearchDocument are projections, never facts.
-
-## Change protocol
-
-- Update the owning core document before changing a stable decision.
-- Add an ADR when the decision affects architecture, persistence, compatibility, or recovery.
-- Resolve document conflicts explicitly before implementation.
-- `active` means the unique `in-progress` spec; an `approved` spec is not active, and zero active specs is valid.
-- A roadmap candidate authorizes only creation of a draft `spec.md`, not a plan or implementation work.
-- Do not implement until the user has explicitly approved both the spec and the plan and the approval date/context is recorded in plan metadata or the spec status record.
-- If a PoC technical assumption or selection conclusion fails, keep that slice `in-progress`, update the owning architecture facts, create the required evidence-bearing ADR, revise the same spec and plan, and obtain explicit user reapproval before retrying. A test-infrastructure failure is recorded and repaired without being treated as a PoC conclusion.
-
-## Engineering discipline
-
-- Feature modules do not depend on one another.
-- UI does not access DAO, Room Entity, or physical attachment paths.
-- Add Use Cases only for cross-aggregate atomicity, shared behavior, or central invariants.
-- Preserve user changes and unrelated worktree state.
-- Use apply_patch for text edits and run proportional verification after every change.
-- Agentic work splits, dispatches, and verifies per the [agentic execution workflow](docs/engineering/agentic-workflow.md); the main agent accepts only against raw evidence, and a subagent reports blockers without hiding or relabeling them.
-
-## User approval boundaries
-
-- Ask before deleting files, directories, or Git history.
-- Ask before modifying environment files, secrets, tokens, or CI/CD configuration.
-- Ask before database schema changes or data migrations.
-- Ask before publishing or deploying publicly.
-
-## Definition of done
-
-- Implementation work with an active spec must pass that unique `in-progress` spec's acceptance criteria.
-- Lifecycle or documentation work, and other authorized work performed while no spec is active, must pass its approved plan or documented acceptance criteria.
-- `none` active never authorizes functional implementation or bypasses the specification lifecycle; work that requires a spec cannot start or be declared done without the required active `in-progress` spec.
-- Required automated checks pass.
-- A verification record contains actual commands and results.
-- Affected core documents and ADRs are synchronized.
+Engineering reform does not authorize product work, schema/device/API/credential/data/release operations or change frozen acceptance. Record task/approval facts once at their owner, keep history separate, and finish the authorized work without manufactured checkpoints.
diff --git a/docs/index.md b/docs/index.md
--- a/docs/index.md
+++ b/docs/index.md
@@ -1,32 +1,27 @@
 # Documentation router
 
-Read [AGENTS.md](../AGENTS.md) and [docs/index.md](index.md) for every task.
+## Record 2.0
 
-| Task | Required documents |
+Read [root rules](../AGENTS.md), then the [single R2 Engineering Policy](engineering/record2-engineering-contract.md) and the sources relevant to the task.
+
+| Work | Source |
 |---|---|
-| Any task | AGENTS.md, docs/index.md |
-| Record 2.0 V1 architecture and future implementation Spec/Plan | [Unified architecture baseline](architecture/record2-v1-unified-architecture.md) — owner-confirmed R1, authoritative design input for all future Record 2.0 V1 implementation Spec/Plan; architecture approval does not authorize implementation. Formal V1 implementation Spec/Plan have not been created or approved; this entry does not activate a Spec. |
-| Agentic execution (splitting, dispatch, acceptance) | engineering/agentic-workflow.md, active spec if one exists |
-| Product scope | product/vision.md, product/mvp-requirements.md, active spec if one exists |
-| Navigation or interaction | experience/information-architecture.md, experience/interaction-rules.md, active spec if one exists |
-| Domain or finance | architecture/domain-model.md, engineering/testing-strategy.md, active spec if one exists |
-| Room, files, search, backup, migration | architecture/data-and-storage.md, relevant ADR, engineering/testing-strategy.md, active spec if one exists |
-| Module or dependency change | architecture/overview.md, relevant ADR, engineering/conventions.md, active spec if one exists |
-| Security or diagnostics | engineering/security-and-privacy.md, active spec if one exists |
-| Open-source reuse or license review | core conclusion, then research report evidence |
+| Product/architecture | [Frozen unified R1](architecture/record2-v1-unified-architecture.md), [V1 Spec](specs/record2/v1/spec.md) |
+| Dependencies/authorization/actual execution state | [V1 Plan](specs/record2/v1/plan.md), including product approval §13 and governance flow §14 |
+| Product and acceptance provenance/C1 identity | [Self-contained decision baseline](product/record2-v1-decision-baseline.md) |
+| UI | Spec §5.4 / Plan F0–F2, the owner's relevant design approval |
+| Collaboration, task/risk levels, permissions, verification | Engineering Policy; per-task commands/results in the relevant Plan/Verification |
 
-## Reading rules
+Approval belongs to Spec metadata/Plan approval record, not a duplicated router table. No product task or Spec is activated by adopting governance. Multiple independent Majors are coordinated by write/contract/resource ownership; the router's current focus is not a global lock.
 
-- Active means exactly the unique `in-progress` spec under `docs/specs/*`. An `approved` spec is not active, and the project may have no active spec.
-- Current active spec: none — [MVP M2 — finance core](specs/mvp/m2-finance-core/spec.md) is `approved` (2026-09-04) after delegated independent review; owning core documents are synchronized and [ADR 0017](architecture/decisions/0017-m2-finance-schema-normalization-and-backup-v2.md) is accepted. M2 remains inactive until its plan is explicitly approved and schema/migration execution consent is recorded. [MVP M1 — app shell](specs/mvp/m1-app-shell/spec.md) is `completed` (2026-09-04) with all 19 acceptance criteria PASS. Tech PoC P0–P6 are `completed`.
-- A candidate sits outside the formal lifecycle. It may create only a draft `spec.md`; it cannot create a plan or authorize code, schema, interface, migration, route, dependency, or module work.
-- A Codex-generated or reviewed plan is not approved. The user must explicitly approve it, and the approval date/context must be recorded in plan metadata or the spec status record before the spec can become `in-progress`.
-- Read links relatively. During lifecycle work, read the target draft or approved spec even though it is not active.
+R2 V1 is an approved Major; current task execution status remains in the Spec/Plan and is not changed by this router. Design/documentation needs its task permission, not functional activation. Source baseline admission and external C1 reproducibility limits must be read before asset reuse; historical local paths/shared links are not implementation prerequisites.
 
-## Fact ownership
+## Record 1.0 and history
 
-Each decision belongs to the core document for its owning domain. Downstream documents should reference that source instead of duplicating or silently contradicting its facts.
+For explicitly scoped legacy work, read [preserved R1.0 contract](../AGENTS.record1.md), then the legacy owner: [product](product/mvp-requirements.md), [experience](experience/interaction-rules.md), [architecture](architecture/overview.md), [data](architecture/data-and-storage.md), [engineering](engineering/conventions.md), [testing](engineering/testing-strategy.md), [security](engineering/security-and-privacy.md), [workflow](engineering/agentic-workflow.md), [roadmap](roadmap.md).
 
-## Conflict resolution
+Those sources, completed Spec/Plan/ADR/Verification and old checks preserve history; they do not automatically govern R2. Phase1B/1C-A/AI source decisions required by current V1 are carried by the admitted decision baseline, with original source identities and limitations. Do not change historical PASS/FAIL, approval or data to fit new policy.
 
-Resolve conflicts in this order: identify the owning domain, update the owning source, add or supersede an ADR if needed, update specs/plans, then code/tests. A failed PoC technical assumption or selection conclusion always requires a new ADR recording the failure evidence, rejected option, alternative decision, and impact before the same active slice can retry; a pure test-infrastructure failure is recorded and repaired without being treated as a PoC conclusion.
+## Source ownership
+
+Spec owns behavior/AC, Plan owns task dependencies/scope, Verification owns actual results, Policy owns R2 execution methods. Stable changes update the correct owner and need relevant review; a skill/generated plan cannot supply approval. Major decisions use ADR where long-term architecture/compatibility/recovery/security requires it, not for every patch/failure.
diff --git a/docs/engineering/record2-engineering-contract.md b/docs/engineering/record2-engineering-contract.md
new file mode 100644
--- /dev/null
+++ b/docs/engineering/record2-engineering-contract.md
@@ -0,0 +1,67 @@
+# Record 2.0 Engineering Policy
+
+2026-10-23。适用于Record 2.0；统一切换条件见§7。产品任务按其具体授权执行。
+
+## 1. 权威与工程选择
+
+Clean Rebuild First, Selective Reuse When Justified。产品依据为[冻结架构R1](../architecture/record2-v1-unified-architecture.md)、[Spec](../specs/record2/v1/spec.md)和负责人明确决定；[Plan](../specs/record2/v1/plan.md)拥有依赖/范围，[来源基线](../product/record2-v1-decision-baseline.md)拥有确认材料摘录和取得限制。
+
+旧七模块、Repository/EntityRef/Schema、导航/UI/Vault、构建/测试/版本门禁不自动继承。按业务包和简单接缝起步，仅为真实依赖/构建/维护收益拆模块。UI和Agent共用Application Services，UI不绕过业务层，Koog留适配边界。
+
+## 2. 主Agent负责，协作按需
+
+Main Agent Capable, Subagents Optional。主Agent可直接完成授权的调查/设计/实现/调试/验证/集成。只有独立产出、并行收益或有价值的审查才委派，并遵守用户/运行环境限制；没有协作不造派发合同，不按固定人数或失败次数接手。
+
+并行只交代目标/范围、owner、必要依赖/约束与验证。共享文件、未稳定接口、Schema/迁移/恢复、同一库/设备等冲突写要协调串行或隔离后集成。独立审查按风险选择，真实标注自查/独立审查，结果由差异和实际验证证明，不以Agent自报PASS代替。
+
+## 3. 任务分级和状态
+
+| 级别 | 最少约定与记录 |
+|---|---|
+| Simple Change | 既有行为/设计内局部文档/代码修复，明确目标/范围和相关验证；无需新Spec/Plan/Verification/ADR或active状态 |
+| Feature Task | 可界定功能/复杂修复，短行为/边界/依赖/权限/测试合同，可沿现有Major Plan任务，不每项创建三文档 |
+| Major Change | 新产品、长期架构/正式模型/恢复/兼容/安全边界等重大决定保留正式Spec/Plan及明确批准；一次批准可同时覆盖两者，记录一次来源 |
+
+任务规模与风险独立：一行金额/CAS修复也需对应高风险验证。现有R2 V1仍是Major，其批准、全部任务依赖和验收保留。
+
+Major各自维护draft/approved/in-progress/completed，不以全仓唯一in-progress作互斥锁。多个独立Major能否并行取决于写入/契约/资源冲突；同一Spec内多任务无需另建Spec。index只是来源/当前焦点，不复制批准状态。文档/设计按具体授权执行，不以功能激活为前提；没有执行授权不自动激活任何Spec。
+
+## 4. 任务授权、红线和UI决定权
+
+明确授权覆盖范围内必要的正常修改/调试及已授权验证，不逐文件/补丁/复测重复申请。稳定产品/公开契约/正式模型/重大依赖/已批UI变化或越界动作升级审核。构建/缓存/依赖、设备/包/库、Provider/费用/Key/用户数据/恢复分别匹配实际对象和批准范围；计划写了测试不等于获准运行。
+
+个人红线保留：删除文件/目录/Git历史；修改环境文件/真实秘密凭据/CI；Schema变更或迁移；公开发布/生产部署，先说明理由/风险取得具体同意。任务独有临时文件的删除也须有明确范围许可，不覆盖旧数据或丢弃他人修改。
+
+所有最终UI/布局/信息层级、图标/视觉/字体配色间距、角色表情、动画及未确认交互归负责人。D-FIN/D-NOTE/D-TIME/D-AI/D-SAFE/D-SYS保留`DESIGN-OWNER：待负责人指导与确认`，方向→候选→调整→明确批准→实现，可逐组与非视觉工作并行，默认Material不是验收替代品。
+
+## 5. T0～T4与完成证据
+
+| 层级 | 适用证明 |
+|---|---|
+| T0 | 差异、文档/格式/静态和适用边界检查；普通文档不启动Android |
+| T1 | 受影响parser/金额/日期/算法/状态的单元、边界和回归 |
+| T2 | 正式Room磁盘/Compose/Android平台真实集成与重开/用户行为 |
+| T3 | 金额/CAS/并发/A-B/取消/进程终止/未知提交/备份恢复/授权Key与故障证明 |
+| T4 | 最终正式版本完整V1联合验收，按获批环境使用真实Room/Koog/DeepSeek |
+
+按影响组合层级，变更单元完成跑相关快检，交付前跑必要组合；通过后只有新变化/失败/风险才追加重跑。相同fixture/原证据可覆盖多AC，但断言不删、历史C1/旧产品PASS不挪用。完整V1仍有B-AC/BK/SD-P/AI-AC/TP/CTX/SES共63条映射、六联合旅程和六设计组。
+
+普通任务记录命令/结果/必要环境与风险；高风险保留可复查runner、独立事实比较、必要身份/版本/代际和PID。编译/截图/fake只补充，不能证明数据安全；备份自动往返未过不完成。API34隔离优先、API36/真机/真实Provider按批准任务门禁；缺CLI只阻相应操作，不判整个环境不可用。
+
+## 6. 数据、失败、记录与工具
+
+Long整数分、业务/关联version CAS、Proposal revision CAS/创建幂等、单Session有效Run/活跃Proposal、不可变Operation、原生确认/A-B原子与回滚/幂等、Room权威历史、Interrupted及跨代际Pending隔离等结果以冻结合同为准。Agent只prepare六类操作，不开放commit/删除/恢复/任意SQL文件网络系统命令。
+
+默认本地；完整AI由有效Provider授权在主动任务按需处理可见数据，不默认整库/后台扫描；无AI不推理/请求/Agent外发。Keystore保护Key且不入源码/Prompt/普通库/日志/任何备份迁移。完整ZIP保留会话/隐藏关系/一致结果，验证预检/原子恢复/回退；不自动继承旧格式/阈值、AppLock/诊断产品或旧网络全禁令。
+
+只维护任务目标、状态、验证和下一步/阻塞，并行补owner，真实故障重试才编号；不为普通任务造完整Attempt Ledger/双验收状态机或每次全仓hash。失败按根因处理、保留事实，只阻依赖它的部分；不得改验收掩盖失败。
+
+ADR用于长期架构/持久兼容/恢复/安全/核心不变量决定；原方案内bug、内部整理和harness修复不自动写ADR或重批整份计划。权威事实一处拥有，其他地方引用，历史不改写。
+
+保护脏状态/未跟踪内容，暂存/Commit/Push按具体权限且只含任务差异，worktree按需要。Skills按相关性/版本/副作用选择，不自动带来安装、tracker发布、Commit、固定TDD/多Agent或UI决定权；明确调用和更高优先级要求仍遵循。
+
+## 7. 生效与适用范围
+
+本规则规定R2工程执行方法，不改冻结产品、安全/AC、真实业务依赖及既有批准；Record1旧workflow/conventions/testing/roadmap/检查仅属旧工程或明确参考。
+
+统一切换须具备：负责人对最终采用Diff、Git纳管/Commit和生效的明确授权；入口/Policy/Spec/Plan一致且产品无损；最小资料集合可从实际提交对象重建；工作区保护检查通过。全部满足后一次记录生效，编辑或提交部分文件不分别生效。最终提交身份与验证结果由Git日志和交付记录证明，不要求提交SHA自引用。治理生效不启动A0/B0/F0或产品任务；G3机器约束隔离及G5开发另按任务授权。
diff --git a/README.md b/README.md
--- a/README.md
+++ b/README.md
@@ -1,3 +1,15 @@
+# Record 2.0
+
+Android本地优先生活记录应用，采用已冻结的Kotlin/Compose/Room与Koog Basic Agent方向。完整V1包括原生记录、完整Agent和安全备份；下方Record1.0的完成记录不代表R2已经实现。
+
+- 当前工程规则与适用范围：[AGENTS.md](AGENTS.md)、[文档路由](docs/index.md)。
+- 产品权威：[统一架构R1](docs/architecture/record2-v1-unified-architecture.md)、[V1 Spec](docs/specs/record2/v1/spec.md)、[实施Plan](docs/specs/record2/v1/plan.md)；产品批准事实见Plan §13，执行未因本轮文档准备而启动。
+- 最小来源资料：[已确认决定/验收与C1身份](docs/product/record2-v1-decision-baseline.md)。文档提交可重建性与C1外部源码可重取性分别验证，后者尚未核实。
+- 治理统一切换条件见Engineering Policy §7；治理生效不启动A0/B0/F0或产品开发，G3机器约束隔离仍待单独执行。
+- R2工程组织尚待A0审核，不默认复用旧七模块或从旧根wrapper构建新产品。
+
+## Record 1.0历史README（原文保留，不是R2发布门禁）
+
 # Android 生活记录 App
 
 A private Android timeline connecting money with life context.
diff --git a/docs/specs/record2/v1/spec.md b/docs/specs/record2/v1/spec.md
new file mode 100644
--- /dev/null
+++ b/docs/specs/record2/v1/spec.md
@@ -0,0 +1,358 @@
+# Record 2.0 V1 Implementation Spec — R1
+
+- **Status:** `approved`；revision `R1`；2026-10-23（Asia/Shanghai）。非活动；设计任务与功能实施均未启动。
+- **Spec approved:** 2026-10-23。**Plan approved:** 2026-10-23；负责人原话与批准范围见 [Plan §13](plan.md#13-r1正式批准记录2026-10-23)。**Schema / migration consent:** 未取得。
+- **产品执行授权：** Spec/Plan批准不启动A0/B0/F0或功能实施；工程、代码、Schema及外部执行仍须对应任务/设计/对象授权。治理流程修订见§10，不改变产品批准或执行状态。
+- **架构基线：** [统一架构 R1](../../../architecture/record2-v1-unified-architecture.md)，本地 HEAD `57e1b68b309b0459aed1765778b7da4cdfff6f6b`；文件 SHA-256 `5de8670fc60a7226d5e30f409be62f35ded55da5d78353d4fb81bd035fe9cefa`，本轮实测相符。
+- **UI 决策权限：** 所有界面、图标、视觉资源、布局、动效及尚未确认的交互方案均由负责人指导、调整并明确批准。本文只规定功能、数据流与安全结果；未经设计批准的界面不能完成最终验收。
+- **工程方法：** 按[R2 Engineering Policy](../../../engineering/record2-engineering-contract.md)进行任务分级、协作与风险验证。原编制借鉴to-spec/to-tickets和旧workflow的记录只属历史，不构成固定Skills流水线、tracker发布或R2派工要求。
+
+## 1. 用户问题、完整范围与继承关系
+
+用户需要把收入、支出与独立文字记录可靠地放在一起，跨日期回看和关联，并通过自然语言创建、改错、查找与归纳。AI 只是增强入口；不启用 AI、没有 Key 或没有网络时，原生记录和恢复能力仍须完整可用。财务事实由本地确定性服务产生，模型回答不能成为事实或提交证明。
+
+### 1.1 V1 必交用户结果
+
+| 领域 | 完成行为 |
+|---|---|
+| 收支 | CNY 收入/支出、Long 整数分、发生时间、可选说明及至多一个可选分类；默认一个可见可改名的“日常账户”；新建与同身份编辑 |
+| Note | 独立无标题纯文本、保留换行和符号、自动保存与重开编辑；原生软删除、撤销、专用恢复入口，保留原身份和隐藏关系 |
+| 关联 | 双向、跨日、多对多；原生批量选择后统一提交、取消零变化；解除不删除两端 |
+| 回看 | 按发生时间混合时间线、今天、日期跳转、类型筛选、跨日连续分页、详情与返回上下文 |
+| 草稿 | 一份独立持久化原生记账草稿，区分新建/编辑，跨进程继续/显式放弃；不计入正式流水和汇总 |
+| 数据保障 | 完整逻辑 ZIP、隔离预检、明确整体替换确认、原子恢复、故障回退、恢复后重启；包括删除状态、隐藏关系与用户可见 AI 历史 |
+| Agent | Koog Basic Agent + DeepSeek BYOK；自然语言创建/修改收支和 Note、查询、跨记录归纳、建立/解除关联、组合提案；必要确定性财务汇总 |
+| AI 产品交互 | App 内全局可隐藏/移动/贴边的角色、聊天 Bottom Sheet、Answer / Navigate / ProposalReady / ClarificationNeeded、原生预填与确认 |
+| 业务安全 | Proposal Revision CAS、创建幂等、Session 并发约束、不可变 CommitOperation、Transaction A/B、业务/关联版本、原子提交、幂等核查及失败恢复 |
+| 会话与授权 | Room 用户可见 Session/Message/Run、跨 Run ChatMemory、进程 Interrupted、取消、会话删除、完整 AI / 无 AI、Provider 持续授权、Keystore 凭据保护 |
+
+不把任何已确认 Agent 功能移到 V1.1。Plan 的中间里程碑仅用于工程验证，不是正式 V1 发布或唯一账本日用许可。
+
+### 1.2 明确排除
+
+不建设余额/开账/转账、多币种、分类分摊、标签/项目、预算/提醒、云同步、服务器或账号系统；不提前建设阶段 1B P1 的独立全局搜索中心、统计仪表盘、CSV、图片附件、分享或 Markdown 预览。Agent 的关键词/日期查询、结果承接及本地精确汇总是 V1 底层能力，不能借此扩成上述 P1 产品。
+
+不自研通用 Agent Loop，不引入多 Agent、Workflow/Graph Engine、动态工具/Skills、向量长期记忆、Agent Persistence 自动续跑或常驻后台 Agent Service。Agent 没有 commit、删除、恢复、SQL、文件、网络或系统命令工具。旧工程保留了某项能力，不代表它进入 Record 2.0 V1。
+
+### 1.3 输入优先级与历史语态
+
+本轮授权和补充的 UI 权限 → 已冻结统一 R1 → 已确认架构分轮修订 → 阶段 1B 已批准行为与验收 → 1C-A 实验证据。1B 稿中 SD1“待批准”、AI“近期不做”，1C-A 历史段落中“C1 待选择”，均由后续批准/裁决取代；保留历史文件，不回写它们。AI 原稿中的 Runtime 对照、细粒度授权、BYOK 待定、可延期 AI 等也已被 R1 取代。原验收语义保留，按最终两种模式和冻结协议解释。
+
+**负责人本轮裁决：Clean Rebuild First, Selective Reuse When Justified。** Record 2.0 不承担复用 Record 1.0 的义务；优先评估符合冻结 R1 的干净实现，仅当旧资产能够降低完整 V1 的实现与维护总成本时选择性复用。旧七模块、Repository/DAO、Schema/EntityRef、余额/转账、Navigation/AppContainer、UI/主题、Vault/备份格式及不适用测试都不是新产品默认约束；C1 已选定的是技术基座及实验成果，也不冻结其源码或工程结构。投入时间、已有代码量和文件数不作为决定性依据。
+
+历史资产保护与产品继承分开：旧代码可以不进入 R2，但不得因此删除源码、历史文档/Commit、测试资料、数据库或用户修改。A0 先比较工程路径和适用范围，负责人审核后才可在明确写入授权内实施。后续获准的文档/ADR/路由同步应明确两代边界，不能为了兼容旧约定牺牲 R1，也不能在设计任务启动前要求它们已完成这些结论或激活。
+
+## 2. 调查证据与真实差距
+
+### 2.1 证据目录
+
+以下E01～E12保留R0/R1调查时的身份、Git状态与核实边界，属于历史快照，不是当前强制读取清单。现行工程方法由R2 Policy拥有；所需产品/验收原义与批准关系由[自包含来源基线](../../../product/record2-v1-decision-baseline.md)承接。R1当时只复核分类/必要摘要，未重跑实验或重新抓取外部聊天。
+
+| ID | 来源类型与可携带性 | 已读取来源及用途 |
+|---|---|---|
+| E01 | Git 已纳管，但4份均有未提交修改；HEAD不能重建当前读取版本 | [根规范](../../../../AGENTS.md)、[路由](../../../index.md)、[执行工作流](../../../engineering/agentic-workflow.md)、[测试策略](../../../engineering/testing-strategy.md)：生命周期、所有权、真实磁盘/设备证据。经授权接纳相关版本前，干净克隆不能假定包含这些修改 |
+| E02 | Git 已纳管且内容匹配给定commit，仓库可取得 | [统一 R1](../../../architecture/record2-v1-unified-architecture.md)：commit `57e1b68b309b0459aed1765778b7da4cdfff6f6b`，完整哈希见元数据 |
+| E03 | 工作区未跟踪，不保证干净克隆可得 | [阶段 1B](../../../discussions/record2-phase1b-product-interaction-20261022.md) §2–7：D1–D5/Q6、页面、J1–J6、B/BK/SD；批准事实见 E04 和后续裁决 |
+| E04 | 工作区未跟踪，3份均尚未纳管 | [1C-A Spec](../phase1c-a/spec.md)、[Plan](../phase1c-a/plan.md)、[Verification](../phase1c-a/verification.md)，末尾C1裁决：实验完成且选定C1，不授权复制原型/开发 |
+| E05 | 仓库外实验报告，主仓库commit不包含；报告不自动属于C1源码commit | [比较报告](D:/record2-experiments/phase1c-a-20261022/evidence/comparison/report.md)、[可行性](D:/record2-experiments/phase1c-a-20261022/evidence/c1/feasibility.md)：边界/版本/BK/SD未跑。R1复核SHA-256分别为`589561d3b648f26747e9bf351f59ef623f6f231478c7f4df677041e35e6decae`、`7ba1be2d3eb6073d2df4a02cff51d94b652a5cc1573e1f25f7ea9f87b3d0c4e1` |
+| E06 | 仓库外C1本地Git；必须另保证可取得，不是主仓库子模块/依赖 | [Repository](D:/record2-experiments/phase1c-a-20261022/c1/app/src/main/java/com/paherdin/record2/poc/c1/data/RecordRepository.kt)、[Database](D:/record2-experiments/phase1c-a-20261022/c1/app/src/main/java/com/paherdin/record2/poc/c1/data/RecordDatabase.kt)、[ViewModel](D:/record2-experiments/phase1c-a-20261022/c1/app/src/main/java/com/paherdin/record2/poc/c1/ui/RecordViewModel.kt)、[Money](D:/record2-experiments/phase1c-a-20261022/c1/app/src/main/java/com/paherdin/record2/poc/c1/model/Money.kt)，源码commit `4b4b18af2fd4f3ea0410e394952bdebd4911d679`；不冻结其六表/单模块结构 |
+| E07 | 仓库外原始运行证据，需独立存档/索引；源码commit不能替代runner | [commands](D:/record2-experiments/phase1c-a-20261022/evidence/c1/final2/commands.json)、[suite](D:/record2-experiments/phase1c-a-20261022/evidence/c1/final2/suite-result.json)、[acceptance](D:/record2-experiments/phase1c-a-20261022/evidence/c1/t5-attempt2/acceptance.json)。R0只读核查10方法/2宿主重启和3项host XML零失败；R1复核suite哈希`80607721fd2d6c5781be9ce1679146ec5a91983dd98a150a128ea4f2cab461da`，未重跑 |
+| E08 | Git已纳管，当前主仓库commit可追溯；仅证明旧工程现状 | [AppContainer](../../../../app/src/main/java/com/paherdin/record/RecordApplication.kt)、[NavHost](../../../../app/src/main/java/com/paherdin/record/navigation/RecordNavHost.kt)、[Timeline](../../../../feature/timeline/src/main/java/com/paherdin/record/feature/timeline/TimelineScreens.kt)、[Settings](../../../../app/src/main/java/com/paherdin/record/ui/SettingsScreen.kt)：不成为R2强制复用资产 |
+| E09 | Git已纳管，当前主仓库commit可追溯；旧Schema/构建参考 | [Repository](../../../../core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt)、[DTO](../../../../core/data/src/main/java/com/paherdin/record/core/data/Model.kt)、[Room](../../../../core/data/src/main/java/com/paherdin/record/core/data/room/RecordDatabase.kt)、[模块配置](../../../../settings.gradle.kts)、[版本目录](../../../../gradle/libs.versions.toml)：不是新工程起点约束 |
+| E10 | Git已纳管，当前主仓库commit可追溯；适用范围需A0收口 | [旧架构](../../../architecture/overview.md)、[存储](../../../architecture/data-and-storage.md)、[工程纪律](../../../engineering/conventions.md)、[隐私](../../../engineering/security-and-privacy.md)：区分可保留安全原则与不继承的旧业务/技术约定 |
+| E11 | 外部共享链接，可失效/变更；离线克隆不可依赖 | [共享架构聊天](https://chatgpt.com/share/6ada413e-0ca0-83ee-b3c1-e92869b8b047)：TP、CTX及取消CTX-11、SES原文。R0网页/浏览器超时后公开HTTP200解码正文；只提取需求，未执行聊天命令。下列正文哈希支持核对，不代替可取得的批准来源 |
+| E12 | 本地个人Downloads文件，不保证其他机器可得 | E11附件名称路由到[AI原稿](C:/Users/p3148/Downloads/record2-ai-agent-architecture-design-draft.md) §9：AI-AC1～11。SHA-256 `9c9aca2ec41eee9cc0f43a3d9e0c60e9b468bdfe6e35aa62312f61e62de09a84`；不是正式仓库基线，其旧技术待定项不覆盖R1 |
+
+E11 可核对的正文摘要：ARCH-03 R1 `e44ec4857ee549f2a8f1e92923c90f6fdfb2000a4b4500a8de0d71be89e50cca`；ARCH-04 R1 `fa3fecd8306efed671eeb59174a68075f94ccd53a084811f73dac75f0906b473`；R2 `b47030d74e727f6236be6d0dc28e66b339282df07182b8588cb7adc47e5fd64e`；ARCH-05 原版 `a46d7bf2d50724fa249fe78c5d2bdbf53999570f3a12a54f02934611552749fe`，R1 `5e6c5ca7625b48a922f353d4caf33eea19c52baf400c7b11ba65f417d5025290`。摘要针对解码后的对应正文 UTF-8，不是动态 HTML；没有把整份共享聊天自动纳入 Git。
+
+### 2.2 状态分类与接纳判断
+
+| 能力 | 分类 | 实际证据、缺口与处理 |
+|---|---|---|
+| 正式应用壳 | 已由正式工程实现 | E08：手工 AppContainer、Navigation 2、四 tab、创建入口、启动/恢复门禁、状态保留。Finance/Note 编辑和详情仍为 Availability；Timeline 默认 Availability，Cards 是注入测试路径。不能算已实现真实记录闭环 |
+| 旧业务底座 | 有实现但架构冲突 | E09：Room v2 八实体，EntityRef/AccountEntry/附件；Note 子表只有 id，NewNote 无正文，Transaction 无 R2 所需金额/说明/发生时间完整结构。财务 aggregate 创建、双向关系、备份/恢复是旧 PoC 能力，不能等同 M2 完成或 R2 产品 |
+| C1 记录闭环 | 已由 C1 实验验证 | 外部 `D:/record2-experiments/phase1c-a-20261022/c1`，branch `codex/record2-c1`，HEAD `4b4b18af2fd4f3ea0410e394952bdebd4911d679`，本轮 status 为空。六表 schema1；E07 证明 J1–J6、收入、编辑、跨日关系、基本筛选/返回、草稿、最小回滚；不是正式 V1 验收 |
+| 金额与稳定身份 | 可受控迁入后重验 | E06 Money 及首次提交前持久化 recordId、财务写/草稿消费同事务可保留思路与测试。正式工程重做调用接线、类型错误、时钟注入、CAS 和 Operation 身份；不得复制覆盖工程 |
+| Note 自动保存 | 有实现需改造 | C1 saveNote 无 expectedVersion/删除条件；ViewModel 的回执 revision 检查只保护显示，不能阻止数据库旧写。需正式业务 CAS、删除竞态和 AI 预览禁自动保存 |
+| 关联 | 有实现需改造 | C1 在事务里读当前集合后对 selected 作差；无提交基线 CAS，后续出现隐藏关系时有误删风险。正式服务接受可见快照的增量意图和端点版本，不以局部集合替换全关系 |
+| 时间线 | C1 基本验证，正式仍缺 | C1 UNION 事实查询可参考；当前以扩大 LIMIT 加载较早数据，固定时钟/日期夹具，不证明完整分页、DST、并发更新边界或大数据性能。旧 P6 单日投影性能不能抵扣连续浏览 |
+| SD1 与完整备份 | 尚未实现于 C1 | E05 明确 B-AC/BK/SD 全 NOT RUN；C1 无删除字段/接口、无正式 ZIP/恢复协调。旧 P5 的安全测试方法可参考，旧格式/多代 Vault/附件事实不可原样迁入 |
+| Agent 全链路 | 尚未实现 | 当前仓库和 C1 均未见 Koog/DeepSeek/Proposal/CommitOperation/Session/ChatMemory/Keystore 正式实现；全部需新增受控实现及真实集成证据 |
+| UI 最终设计 | 尚未批准 | 1B 线框/C1 Material 页面/旧主题均是功能输入或实验资产，不能直接当本轮视觉批准。设计清单、批准节点见 Plan F0 |
+| C2 | 已验证的对照证据 | E05：十一表和更多依赖/旧后台/备份负担；已选 C1，不重新选型、不迁入 C2 旧备份，不重复开展 C2 实验 |
+
+### 2.3 必须先解决与可在依赖就绪后解决
+
+**设计准入：** Spec/Plan获批并取得对应设计任务授权后，A0工程比较、B0数据方案和F0负责人设计指导可开始；不要求其尚未产出的工程结构、身份或Schema预先获批，也不因无活动Spec阻止获准文档设计。
+
+**代码/Schema准入：** 本Major相关设计审核、明确写入范围、适用owning文档/ADR及其执行状态就绪，并协调写入/契约/资源冲突后，才进入对应实现。A1等A0工程方案批准；正式Schema写入另等B0审查与明确Schema同意，不把Spec/Plan批准当代码或迁移许可。
+
+**外部执行准入：** 构建缓存/依赖写入、设备安装、数据库初始化/迁移、真实DeepSeek/凭据、用户数据/恢复、CI/签名/发布分别绑定对象与风险授权，不能由任务列表自动推得。删除/覆盖/密钥/CI/公开发布红线持续有效；具体无环门禁见Plan §1–2。
+
+实现期间按依赖解决：确定性查询与汇总、Proposal/Operation 故障、ChatMemory 接缝、真实 Provider 协议、Keystore 平台行为、ZIP/SAF 故障及 UI 设计。它们不必全部在写首个业务测试前完成，但各自失败阻止其下游和 V1 联合验收。物理索引、表数量、备份资源阈值、具体工具预算不伪装成已冻结；见 Plan 的决策时间表。
+
+### 2.4 证据接纳与可携带性门禁
+
+必需产品/验收资料由自包含来源基线承接，并与G2/G4最小资料集合统一接纳和验证提交对象。后续A0只为实际依赖的复用资产补充取得位置、hash/commit、许可/敏感性、批准来源和核实结果；不把所有本机文件自动纳入Git或称为可审计基线。
+
+- **仓库内必需来源：** 自包含来源基线保存E03已确认产品/B/BK/SD、E04实验结论与C1裁决、E11/E12仍生效AI/TP/CTX/SES及修订/批准关系。其原文摘录与本Spec的63条映射职责分开；R2规范由根入口/唯一Policy拥有。旧workflow/testing、个人Downloads、共享聊天和绝对实验路径只供溯源，不是默认实施前置。
+- **可保留引用+哈希，但必须可取得：** E05/E07的大体量报告/原始runner、SQL快照、XML和必要产物可在获批可访问档案保存，以仓库内清单记录hash/环境/命令/结果/限制。哈希不能重建文件；原始证据不可取得则标未核实，不能据摘要接受依赖它的复用结论。
+- **必须可重新取得的实验资产：** 拟复用的E06精确commit/选中源码、许可、依赖锁定输入、对应测试/fixture及其所需E07证据，须有获批访问或存档渠道；不能把D盘绝对路径当团队依赖。资产不可得则不接纳该代码，记录原因并按已冻结行为重新实现和验证，不重开C1技术选型。
+- **全部正式重验：** C1金额、稳定ID、事务、草稿、关系和离线/依赖结论，以及旧测试方法的所有适用断言，均须在A0选定的正式工程/版本重跑；B/BK/SD/AI/TP/CTX/SES和六联合旅程不抵扣任何历史PASS。
+- **不得整包复制：** 本地环境绝对路径/机器身份、签名与API Key、私有数据、完整Downloads目录、含非必要私人信息的共享聊天、缓存/APK/日志或隐藏推理不直接进入源码/Git。经授权仅提取必要且去敏的技术/验收事实；需要保留原始证据时用受控档案，不能假装已脱敏或可公开。
+
+该门禁按任务接纳必需来源，不阻塞A0/B0/F0为完成清单而开展的获准设计调查；资料缺失只阻塞依赖该资料的结论、资产接纳或实现验收。
+
+## 3. 系统、模块与错误契约
+
+逻辑调用方向为 `Native UI → Application Services → Room`，以及 `AI UI → Coordinator → Koog → Record Tools → Application Services → Room`；用户确认由原生 UI 经 Application Services 执行 A/B，不经模型提交。Application Services 和领域值不依赖 Koog。
+
+| 边界 | 责任、输入输出与失败行为 |
+|---|---|
+| Native UI | 展示状态、输入、强类型导航、用户确认；只使用业务 DTO/稳定身份，不触 DAO/Entity/文件路径。持久成功后才报告成功；错误不显示成空内容 |
+| Application Services | 统一金额/时间校验、收支/Note/关系/草稿、查询/汇总、版本、Proposal/Operation/Session 的确定性规则与恢复；跨事实提交由这里拥有单一事务；不逐表制造 Use Case |
+| Room Persistence | 事实与状态、约束、CAS、唯一性、短事务、快照。异常/取消退出事务必须回滚；物理异常在边界转换为安全的应用错误，不带正文、Key 或路径 |
+| Coordinator | AI 模式/Provider 授权、Run 所有权/取消、会话焦点、原生交接；不复制 Koog Loop、不把 UI 关闭当 Run 取消、不拥有另一个业务库 |
+| Koog Basic Agent | 模型执行、Tool Calling/结果、执行预算、运行事件及 ChatMemory；不直写业务，不等待用户确认维持 Run，不启 Agent Persistence |
+| Record Tools | 封闭参数解析、授权/Run/代际检查、有界检索/详情、精确汇总和 prepare；错误返回可识别类别，不伪称空数据或成功，不暴露通用能力 |
+| Proposal / CommitOperation | 可编辑意图与已确认不可变意图分离；版本、幂等和结果由本地服务管理，生命周期不以聊天文字推断 |
+| AgentSession / ChatMemory | Room 保存用户历史；ChatHistoryProvider 只加载已完成有效历史并重建上下文。store 不更新/删裁 Room 用户消息 |
+| BYOK / Keystore | 私有认证加密凭据，由认证路径短暂取明文；不会作为工具数据、Prompt、日志或备份内容 |
+| Backup / Restore | 完整一致快照、有限资源隔离校验、精确候选确认、独占切换与故障恢复；恢复失效旧 Run/缓存/授权与旧 Pending 执行资格 |
+
+**工程组织建议，须经Plan A0比较审核：** 优先在本Git仓库中使用独立构建根（候选`record2/`，先核查占用/边界）和一个Android应用模块，内部按原生业务、Application Services/Room、AI适配和共享UI职责组织包；由新composition root清晰接线，Koog不进入业务层。七模块、旧导航/AppContainer/主题及旧测试模块均不默认继承，也不为了逻辑职责逐一建Gradle模块。是否独立应用子工程/模块或在既有目录建立新边界，由A0按完整V1成本和构建隔离实证比较；没有充分证据支持旧工程改造更优时推荐干净实现。具体目录、模块图、导航实现和包/库/备份身份尚未冻结，代码接纳须等审核与明确写入授权。
+
+错误至少区分 Validation、NotFound/NotVisible、VersionConflict、ProposalRevisionConflict、RunInvalidated、AuthorizationRequired、CredentialUnavailable、BudgetExceeded、Provider/ProtocolFailure、RecoverableIO、StorageFull、Unsafe/IncompatibleBackup、RecoveryRequired、OutcomeUnknown。可恢复错误保留输入；版本冲突要求重读和重新确认；未知提交先查 operationId；网络错误不能生成业务成功或删除 Key。
+
+## 4. 逻辑数据模型与不变量
+
+下表是正式物理设计的要求与字段候选，不是 Room 表定义、SQL、版本号或迁移批准。是否拆表、索引/唯一约束实现、序列化格式、外键删除策略由后续设计审核确定。
+
+| 逻辑对象 | 字段候选及必须成立的约束 |
+|---|---|
+| Transaction | stable id、INCOME/EXPENSE、amountMinor: Long > 0、CNY、accountId、nullable categoryId、memo、occurredAt、version: Long、创建/修改元数据；编辑不换 ID。分类与方向相容，金额全链路不用浮点 |
+| Account / Category | 默认账户 stable id/name/CNY，必要版本；合法已存在分类及方向。账户初始化幂等，改名不改历史身份；不引入余额事实或多账户产品 |
+| Note | stable id、原始 body、occurredAt、version、删除状态/删除时刻；新建全空白不落盘，清空既有正文拒绝；删除保留正文、身份、原时间和全部关系 |
+| Transaction–Note Link | 两端 stable id、唯一配对；没有复制事实。修改关系在同一事务递增受影响端点版本；可选独立 relationVersion，具体版本编码后审，但必须能发现关联并发 |
+| NativeFinanceDraft | 单 slot、stable recordId、new/edit、原输入/日期解释、业务基线版本、draftRevision、已持久标识；不是真实流水。消费与真实保存一致；AI Proposal 不占此 slot |
+| ChangeProposal | proposalId、sessionId/runId、creationRequestId、revision、操作集合、localRef、预期业务/关系版本、代际、可编辑/锁定/终态；每 Session 至多一份活跃待确认提案 |
+| CommitOperation | operationId、proposalId/confirmedRevision、规范化不可变 payload/完整性标识、稳定新 ID、expectedVersions、dataGeneration、Pending/Applied/RejectedConflict/RejectedInvalid、结果 ID 映射；同 Proposal 至多一项非终态 Operation |
+| AgentSession | stable sessionId、标题/时间、会话有效性/历史版本、必要交互焦点；焦点只是提示，每次重新校验 |
+| AgentMessage | stable messageId、sessionId/runId、角色、用户可见正文、顺序、最终/中断状态、真实记录引用；最终回复与 Run 终态同事务；不保存隐藏推理当用户消息 |
+| AgentRun | stable runId、sessionId、进程所有权、Provider/授权代次、状态、预算/安全错误；每 Session 至多一个有效活动 Run，迟到结果不得恢复失效状态 |
+| Restore / DataGeneration | 当前代际与已验证恢复结果/完成标记；排斥旧写入与旧 Operation 执行。记录语义不能仅靠物理数据库版本或内存布尔值 |
+
+### 4.1 金额、时间、版本与关联
+
+金额接受合法十进制文本精确转整数分；拒绝零、负数、超过两位小数、指数、分隔符、溢出。汇总必须检测累加溢出，按收入/支出分别报告记录金额，不能叫真实余额。模型不给确定性统计服务代算。
+
+发生时间与修改时间分离；使用可注入 Clock/ZoneId，按当前时区的本地日 `[start, nextStart)` 解释范围，不假定 24h；拒绝 DST gap，overlap 固定较早 offset，并在确认前明确。P0 不允许未来发生时间。时间线按发生时间倒序、同刻 stable ID 稳定排序；日期跳转是连续时间线锚点，不退回旧工程单日列表。
+
+字段编辑、自动保存、删除/恢复、关联写均在真实数据库提交时检查 expectedVersion，受影响版本单调增加，失败零变化。重复目标状态的删除/恢复不变成 toggle。若采用字段版本与关联版本分离，必须审核统一 CAS 向量，不能以“只改关系”为由绕过端点冲突。
+
+原生选择器从读取时可见关系快照形成增量 add/remove；隐藏关系不进入 remove。候选在提交前不可见/被删除时整批拒绝，不部分成功。Agent LINK/UNLINK 本来就是增量语义。端点身份、类型、版本和可见性全部提交时重验。
+
+### 4.2 Proposal 修订与 Transaction A/B
+
+允许操作只有 `CREATE_TRANSACTION`、`UPDATE_TRANSACTION`、`CREATE_NOTE`、`UPDATE_NOTE`、`LINK_RECORDS`、`UNLINK_RECORDS`。同一提案可组合创建和关联，localRef 只能引用本提案合法新对象，不跨提案或猜测正式身份。
+
+`PrepareNewProposal` 的业务请求身份由 Record 稳定生成/保留；重复工具调用返回同一提案，同身份不同规范化内容应拒绝，不能默默覆盖。`ReviseProposal(proposalId, expectedRevision)` 原子 CAS，同时检查当前 Session/Run/代际、可编辑状态；迟到模型/旧页面不得覆盖新 revision。修改提案内临时金额与 UPDATE 已存交易是不同操作。
+
+| 边界 | 原子完成内容 | 故障与恢复 |
+|---|---|---|
+| Transaction A | 校验用户实际展示/确认的 revision 和规范化 payload，提案可编辑且无其他非终态 Operation；冻结具体时间、金额、账户/分类 ID/版本、localRef 依赖、existing expectedVersion、新 stable ID；创建 stable operationId 的 Pending 并锁定 Proposal；不写正式事实 | A 未提交则无新 Operation；A 已提交 B 未开始则仅 Pending，无正式业务变化。模型不能再次解释相对日期或修订已冻结内容 |
+| Transaction B | 同一 Room 事务重验 Operation ID/不可变 payload/代际/状态和全部端点；应用完整操作集合；持久 Applied 结果、真实 ID 映射及 Proposal 终态 | 已 Applied 返回原结果。确定性拒绝须在零业务修改时记拒绝；写入过程中异常/取消必须抛出回滚，不能 catch 后正常结束事务 |
+| 回执未知 | 从持久 operationId 核查业务和结果，不凭 UI/聊天判断 | B 提交后回执前终止仍查得同一 Applied；Rejected 不重试旧 payload；Pending 排除并发后仅用户主动受控同 ID 重试，禁止后台自动重放 |
+| 新代际恢复 | 保留可阅读历史和一致 Applied 映射，隔离旧 Pending | 旧 Pending 不直接执行；重新审核/新确认形成新操作。不能把同库进程恢复误作 ZIP 恢复或反之 |
+
+A/B 是两个本地事务。网络/LLM 不在事务中等待。AI 关闭和 Session 删除不得使已确认 Operation 丢失权威核查依据；不是依赖 Agent 存活的事务。
+
+## 5. Agent、会话、授权与原生交接
+
+### 5.1 Record Tools 和确定性查询
+
+| Tool | 请求/结果合同 |
+|---|---|
+| search_records | 日期、类型、关键词、合法分页 cursor/限额；返回真实可见记录 stable ID、版本、摘要、发生时间、必要关联、下一页和覆盖范围；没有结果与查询失败明确区分 |
+| get_record_detail | typed record identity 与分段 cursor；重验可见性，返回全文/指定段及关联、版本和截断信息。不能通过猜 ID 访问已删除 Note |
+| prepare_changes | 创建或 revision CAS 修订六类结构化提案；返回持久 proposalId/revision/验证问题，绝不写正式业务事实 |
+| aggregate_finances | 仅在真实确定性服务验收后注册；明确日期/时区/分类范围、Long 分收入/支出及溢出错误；不建设统计大盘 |
+
+每个工具入口、结果外发前及迟到结果落盘前检查有效授权、Run/Session/代际；解析不信任模型参数。记录正文、引用内容和 Tool Result 都是数据，不能更改 Provider、工具注册、权限或用户确认要求。分页、长文分段、Token/时间/费用及无进展重复调用都有硬预算；不足时明确说明覆盖范围，不能把未读部分归纳为已读。
+
+### 5.2 AgentRun 与 ChatMemory
+
+1. 发起前校验 Session、模式、Provider 授权与凭据；同一短 Room 事务保存当前用户消息、新 Running Run 和其已完成历史边界，原子争取单 Run 所有权。
+2. ChatHistoryProvider.load 只取该边界之前已完成有效历史；当前输入仅通过本次 run 参数加入一次。失败/中断输入仍可在 UI 阅读，但不能伪造为已完成对话或无意重复注入。
+3. Koog 管理当前 Run 内 Tool Call/Result 和 Provider 必要协议状态；Record 不另写循环，也不任意删改运行中协议消息。
+4. ChatHistoryProvider.store 只写可丢弃缓存或不持久缓存，不能裁剪 AgentMessage。缓存须绑定已完成历史版本、Provider、会话有效性和代际；不一致立即失效，以 Room 重建。
+5. 最终回复和 Run 终态同一短 Room 事务提交，校验当前运行仍有效；流式片段可临时显示但不是最终成功。Koog store 成功而最终回复未落盘时，不接受缓存为权威。
+6. 新进程确认旧进程所有权已消失，将遗留 Running 标记 Interrupted、失效缓存；不自动重启模型、工具或 Pending。新 Run 重验焦点、引用、关联和 Proposal revision。
+
+Activity 重建、业务页切换和关闭 Bottom Sheet 不取消 Run；应用级有明确 owner 的结构化协程管理，不增加常驻后台服务。用户取消、AI 关闭、授权撤销或 Provider 变化须使运行身份失效，拦截后续外发和迟到写回。
+
+Session 删除必须经明确用户操作：取消/失效 Run，告知未确认提案并废弃，删除用户可见历史及缓存/焦点；不删除业务事实、Applied 映射或待核查 Pending。不能安全独立保留 Pending 时阻止删除并说明原因。具体确认交互由负责人批准，物理外键策略后审，禁止级联丢失核查依据。
+
+### 5.3 两种模式与 BYOK
+
+初始未启用 AI。完整模式在用户明确授权当前实际 Provider/处理范围后，允许主动任务按需处理全部正常可见业务和必要历史，不默认整库上传、不后台扫描、不再逐条弹隐私框；无 AI 不执行 Koog/Agent 工具/模型请求，原生业务与本地历史仍可用。缺 Key、断网、额度不足是可用性状态，不新增第三种 AI 模式。
+
+AI 开关、Provider 授权和凭据独立；Provider/实际接收方改变必须重新授权。关闭不声称收回已发送数据，不破坏已确认业务提交，也不默认删除历史或凭据。原生确认后的确定性核查不以 AI 开启为前提。
+
+Keystore 管理加密密钥，私有凭据使用经验证认证加密和每次唯一 IV；明文只短暂用于认证，不入 Room、Prompt、日志、APK、手动 ZIP、系统备份或设备迁移。不能解密/永久丢失则重新配置；临时网络/服务/额度问题不删 Key。不指定已弃用 EncryptedSharedPreferences，不引入服务器代理。
+
+### 5.4 UI 技术与功能合同
+
+| 结果/场景 | 可规划技术行为，最终设计仍待负责人批准 |
+|---|---|
+| Answer | 面板展示回答/归纳及可点击真实记录引用；聊天“完成”不能替代 Applied |
+| Navigate | Coordinator 将业务 identity 转合法强类型目的地，导航前重验存在/可见性；不得执行模型路由字符串。结果承接只为当前任务，不扩成 P1 搜索中心 |
+| ProposalReady | 提案持久化后打开明确未提交的原生预填/确认页；编辑必须形成受控 revision，保存确认展示的当前版本并走 A/B；无需聊天二次确认 |
+| ClarificationNeeded | 面板补问事实歧义，不把猜测冻结进 payload |
+| 原生草稿冲突 | 继续 D5 / 保留 D5 处理提案 / 显式放弃；不得静默覆盖、消费或复用原生草稿 slot |
+| AI Note | 确认前仅 Proposal 编辑，不能启动原生自动保存；正式确认后再进入普通 Note 生命周期 |
+| 全局角色/面板 | Compose App 内 Host，与导航同级；无系统悬浮窗权限；正常业务页可达，可隐藏、移动、贴边；IME/系统弹窗、恢复门禁、不可中断提交时避让或停用；状态取本地 Idle/Running/NeedsInput/PendingConfirmation/Error |
+| 返回/恢复 | 保留来源日期/类型/滚动，关闭页或面板不提交、不丢提案、不自动取消 Run。原生保存中锁定重复与离开；Note 只对当前已落盘版本显示“已保存” |
+
+上述合同不冻结页面布局、导航栏样式、卡片、颜色/字体/间距、图标/插画、角色外观表情、动画/反馈和未确认具体交互。全部标记 **`DESIGN-OWNER：待负责人指导与确认`**；逐项设计批准时点见 Plan F0。技术测试页/临时默认组件不能替代最终 UI 验收，也不要求本轮生成原型或视觉稿。
+
+## 6. 完整逻辑备份、恢复与代际
+
+备份以同一逻辑一致快照覆盖账户名称/分类、全部收支字段及稳定版本、正常/已删除 Note 和删除时刻、完整隐藏/可见关系、必要解释性偏好、Session/用户可见 Message/Run 的可读状态、记录引用、与事实一致的 Applied Operation/结果映射，以及安全可解释的未确认提案。草稿不是流水，导出前明确处理/保存；是否以独立草稿段保留由格式审核裁定，不能把原生草稿静默变成正式记录。
+
+未确认 Proposal 恢复为待重新审核草稿；备份中的旧 Running 不恢复执行，旧 Pending 只能成为不可执行历史或需新确认的草稿。Applied 映射需允许目标记录之后已被编辑的合法历史，不能要求它永远与当前字段逐字相等；校验身份、结果与历史语义一致，不凭旧 payload 重放。
+
+不备份 Key/Keystore、授权有效资格、Koog checkpoint/模型上下文缓存、隐藏推理、原始工具 Trace 和临时日志。恢复环境重建授权/Key 与上下文。未加密 ZIP 的提示明确包含私人笔记、已删除记录和 AI 对话；SHA-256 只校验完整性。
+
+正式格式审核须冻结格式版本/UTF-8/金额时间语义、清单白名单、长度/数量/哈希、身份/版本/引用/状态校验，以及条目数、路径、单项/累计解压字节、压缩比和磁盘余量限额；每项测刚好到界/越界及声明和实际流不一致。不得直接套旧 format1/2、旧附件列表或旧大型 Vault 数值。
+
+恢复先有界隔离预检，再展示精确候选及整体替换确认；选择字节/验证上下文变化必须重验。恢复取得独占写边界，停止并等待自动保存/Run/提交竞争，失效旧 repository/工具请求的代际资格。切换协议、完成标记与失败回退必须保证结果只有完整 O 或完整 S，不能混合。SQLite/WAL 活跃文件复制不是逻辑备份。具体使用事务整体导入或物理切换由 A0/G0 审核与 ADR 确定，不借 C1 可行性稿提前冻结。
+
+Android 云备份、设备迁移和手动 ZIP 分别验证：allowBackup、兼容 backup rules、API34 data-extraction rules、merged/final APK 全链路排除全部用户数据和凭据。不得用“没有明文 Key”抵扣密文排除。恢复后重启重读与独立规范化往返比较通过前，备份功能不完成。
+
+## 7. 验收映射：继承原语义，全量 NOT RUN
+
+以下是正式工程目标和任务映射，**本轮全部为 NOT RUN**。E07 的 A-AC PASS 仅为历史实验验证；不能填入下列正式验收。原条款见 E03/E11/E12；下表凝练测试观察，不改编号与语义。
+
+### 7.1 原生业务、页面与备份
+
+| ID | 必须证明的结果 | Plan 任务 |
+|---|---|---|
+| B-AC1 | 最终真实 Activity/磁盘库重跑 J1–J6、收入、Note 编辑和全部适用 P0 | B1–B4、F1、G2 |
+| B-AC2 | 精确 5800→6050、边界/溢出/非法金额、真实失败与未知结果不重复/不改旧值 | B1、C1、G2 |
+| B-AC3 | 编辑/Back/旋转/IME/初始化/草稿及自动保存迟到回执均正确 | B1–B2、F1 |
+| B-AC4 | 多对多/跨日/取消/解除/跳转、批量失败零变化，无孤立/单边/重复关系 | B3、F1 |
+| B-AC5 | 连续混排/分页/日期类型/返回、同刻/日期编辑/午夜/时区/DST 无漏重 | B4、F1 |
+| B-AC6 | BK1～5 全通过，独立状态比较、故障与重启证据齐全 | G0–G2 |
+| B-AC7 | 最终原生离线与许可/系统备份策略；新增 AI 外发仅在已授权主动任务中，按 R1 修订旧“APK 无网络”测试，不能删隐私测试 | A1、D1、E0、G1–G2 |
+| B-AC8 | 最终版本/命令/环境/原始结果可重做，保护旧成果，失败/未跑不记 PASS | 全部、G2 |
+| B-AC9 | SD-P1～4 笔记生命周期全通过 | B2–B3、G1–G2 |
+| SD-P1 | 删除/撤销/恢复及重启保留同 ID/正文/原发生时间 | B2、F1 |
+| SD-P2 | 隐藏/恢复原关系、选择期间删除竞态与失败原子性 | B3、F1 |
+| SD-P3 | 迟到自动保存不复活，重复操作和提交前后终止安全 | B2、C1 |
+| SD-P4 | BK1～5 同时保留删除状态/删除时刻与隐藏关系 | G1 |
+| BK1 | S→ZIP→新隔离 R→重启，逐字段/稳定 ID/整数/正文/时间/全部关系/账户分类及删除状态对比；恢复 Note 后关联可见 | G1 |
+| BK2 | 截断、哈希/数量、缺失/多余项、重复 ID/关系、孤立端点、版本、非法金额时间/删除状态、路径穿越/重名/资源超限全部拒绝；O 事实及受保护摘要不变、重启可读 | G1 |
+| BK3 | 读取/打包/SAF 写入关闭的失败/取消不报成功，不列半包；完整写完关闭且回读校验才成功 | G1 |
+| BK4 | 验证/导入/切换前后故障和进程终止仅 O 或 S 完整状态；成功必为 S，失败有可验证 O 回退，不混删除/关系 | G1 |
+| BK5 | SAF 取消零变化，验证后显示候选数及整体替换再确认；文件变化重验，确认前 O 不变且有旧库保护，不支持合并恢复 | G1、F1 |
+
+页面检查完整继承：A-P1 同日两类详情、A-P2 跨日/日界/同刻/分页、A-P3 编辑与返回/重启；B-P1 精确金额/可选分类、B-P2 重复/失败/回执前终止、B-P3 编辑失败、B-P4 草稿/幂等初始化；C-P1 原文、C-P2 回执/重试、C-P3 空白规则、C-P4 已保存后重启；D-P1 三条跨日关系、D-P2 取消/批量失败、D-P3 解除一条保留其他。全部映射 B1–B4 + F1；功能布局实现还受 F0 设计批准约束。
+
+### 7.2 AI-AC 原始十一项
+
+| ID | 继承验收观察 | Plan 任务 |
+|---|---|---|
+| AI-AC1 | 未启用 AI 或断网，离线收支/Note/关联可用 | E0、F1–F2、G2 |
+| AI-AC2 | 自然语言记账的金额/分类/时间预填正确；确认前零正式事实 | C0、D1、F2 |
+| AI-AC3 | 口语改同一提案，ID 不变/revision 增加，旧金额不回写 | C0、D1、F2 |
+| AI-AC4 | 收支+Note+关联一次确认全成或全败，无孤立事实 | C1、F2、G2 |
+| AI-AC5 | 跨日查找结果对应真实 ID，日期/类型/关系及打开正确 | B4、D1、F2 |
+| AI-AC6 | D5 与 Proposal 并存不覆盖，保留/舍弃选择明确 | C0、F2 |
+| AI-AC7 | AI Note 原生预览在确认前不触发自动保存 | B2、F2 |
+| AI-AC8 | 迟到结果、重复点击、进程终止不覆盖新提案或重复创建 | C0–C1、E1–E2、F2 |
+| AI-AC9 | 已变化/软删除目标以版本与可见性冲突阻断 | B2–B3、C1、D1 |
+| AI-AC10 | 不外发未授权内容，不执行伪造/未注册工具；注入不能突破权限和写入边界 | D1、E0、G2 |
+| AI-AC11 | 跨业务页角色可见可关闭，IME 不挡关键控件、返回上下文保留 | F0、F2 |
+
+### 7.3 TP / CTX
+
+| ID | 继承验收观察 | Plan 任务 |
+|---|---|---|
+| TP-01 | revision3 确认与 revision4 修订竞争，只能提交实际确认内容 | C0–C1 |
+| TP-02 | 同 Run 重复 prepare、迟到修订、取消结果不重复/覆盖提案 | C0、D1、E2 |
+| TP-03 | 冻结前、A 后 Pending、B 中、B 成功后各终止进程，事实/结果一致 | C1、G2 |
+| TP-04 | 同 Operation 重复触发只产生一次业务结果 | C1 |
+| TP-05 | 其他原生页已改关联，旧提案不能覆盖 | B3、C1 |
+| TP-06 | 自动保存/AI 修改/软删除竞争，旧正文/提案不能覆盖新状态 | B2、C1 |
+| TP-07 | B 内异常/取消整体回滚，无吞异常导致部分提交 | C1 |
+| TP-08 | Pending、引用失效、恢复代际变化不自动重复提交 | C1、G1 |
+| CTX-01 | 未授权/无 AI，用户原话、历史和业务数据均零外发 | E0、D1 |
+| CTX-02 | 完整模式真实按需读取全文、收支详情和关联 | B4、D1 |
+| CTX-03 | 跨日/记录/多轮读取不被额外隐私弹窗打断 | D1、F2 |
+| CTX-04 | 撤销立即阻止后续请求/结果回传，准确说明已发数据不能收回 | E0、D1、F2 |
+| CTX-05 | 普通工具不泄露/复活已删除或不可见记录 | B2–B4、D1 |
+| CTX-06 | 恶意 Note 不更改 Provider/工具权限，不提交/执行系统动作 | D1 |
+| CTX-07 | Key/系统凭据/未授权诊断不入模型上下文或日志 | E0、D1、G2 |
+| CTX-08 | 引用导航准确，打开前再次校验身份和可见性 | B4、F2 |
+| CTX-09 | 长文分段；Token/成本不足明确覆盖边界，不编造结论 | D0–D1、E1 |
+| CTX-10 | 实际接收方变化，旧授权失效并重授 | E0、E2、F2 |
+
+CTX-11 由共享聊天 ARCH-04 R2 明确取消，原因是受限模式被取消；不是本草案删验收。
+
+### 7.4 SES 全集
+
+| ID | 继承验收观察 | Plan 任务 |
+|---|---|---|
+| SES-01 | 正常对话强停重启后 Session/输入/最终回复完整 | E1–E2 |
+| SES-02 | 跨 Run 正确使用必要历史，Session 间隔离 | E1、D1 |
+| SES-03 | 推理中进程终止→Interrupted，无自动模型/工具执行 | E2 |
+| SES-04 | 同 Session 并发 Run/迟到结果不覆盖有效状态 | E1–E2 |
+| SES-05 | 无 AI 无请求，原生和已有聊天可用 | E0、F2 |
+| SES-06 | APK/普通数据库/日志/诊断/备份不含 API Key | E0、G1–G2 |
+| SES-07 | 凭据丢失/失效或 Provider 改变，拒绝请求并引导配置/授权 | E0、F2 |
+| SES-08 | 删会话不删业务事实及必要 Operation 幂等记录 | E2、C1 |
+| SES-09 | 历史 ZIP 恢复不重放 Pending、不恢复旧凭据 | G1 |
+| SES-10 | 超 Token 预算用有界上下文，不静默删用户历史 | E1、D1 |
+| SES-11 | Koog 裁剪后 Room 完整用户历史仍在 | E1 |
+| SES-12 | 当前输入/Koog store/最终回复提交各故障不重复、不幽灵、不丢权威消息 | E1–E2 |
+| SES-13 | 删除活动 Run/Proposal/Pending 所在 Session 不复活、不丢核查依据 | E2、C1 |
+| SES-14 | 新设备/代际恢复历史可读，Pending 不执行，授权/Key 不继承 | G1 |
+| SES-15 | 真实 Koog + DeepSeek 多轮工具及跨 Run、内部协议和 reasoning_content 正确 | D0–D1、E1、G2 |
+| SES-16 | 关闭 AI/取消/换 Provider 后迟到结果不能改失效会话/提案 | E0、E2、D1 |
+
+### 7.5 真实联合验收
+
+J-E2E1：无 AI 完成原生 J1–J6 + D5 + SD1，重启和磁盘独立对比。J-E2E2：真实 DeepSeek 创建收支+Note+关联→口语修订→原生确认→Applied→两端详情/时间线→强停重读，全程确认前零事实。J-E2E3：跨日关键词/全文/关联查询→精确汇总→可引用归纳→强类型详情→返回原上下文。J-E2E4：并发原生编辑/自动保存/软删除/关联与 AI 提案→冲突零部分提交→新确认；覆盖 TP 的终止矩阵。J-E2E5：含长聊天、已删除 Note、隐藏关系、Applied/Pending/未确认 Proposal 的完整 ZIP→新代际恢复→聊天可读/凭据重配/旧 Pending 不执行→故障回退及再次重启。J-E2E6：角色跨页/IME/旋转/面板关闭、授权撤销/换 Provider/删除会话/断网，功能和状态保持且无迟到外发/回写。
+
+所有联合旅程使用最终正式代码、真实 Room 磁盘库、Koog 和目标 DeepSeek API；模拟模型只用于可控故障补充。主 API34 隔离 AVD + 获批真实设备（OEM/SAF/Keystore/网络与最终联合行为），API36 按兼容性需要补充。真实 Provider 测试须获授权并限制费用，只使用合成数据，证据不保存 Key/隐藏推理/真实个人正文。具体命令、环境和故障选择器的冻结门禁由 Plan §9 规定。
+
+## 8. 未决设计与负责人裁决
+
+架构方向不重开。以下仅影响实现落点或可验证细节：
+
+| ID | 需要收口的事项 | 建议与最晚门禁 |
+|---|---|---|
+| O1 | A0两路径比较、正式工程/模块/导航、applicationId/数据库/备份身份、历史保护及证据接纳 | 优先评估独立构建根+单应用模块的干净实现，不强制旧七模块；改造方案须证明完整V1总成本/维护明显更优。A0可先设计，负责人审核工程建议后才准代码接纳/必要ADR和生命周期同步 |
+| O2 | 从完整V1出发的新Room物理模型、版本/CAS、Session/Operation引用及恢复标记 | B0可在设计授权后并行起草，不以最终Schema批准为前提；结合A0身份结论完成审查。初建/修改Schema和初始化/迁移另获同意；无旧数据继承需求，不主动建设旧Schema兼容层，保护旧库 |
+| O3 | 精确工具链/Koog 坐标、DeepSeek 模型/协议与预算 | A1/D0 实证后批准版本；不提高 minSdk34、不绕过 manifest/metadata 校验，不重选 Runtime |
+| O4 | ZIP 逻辑版本/资源限额、D5 保留策略、原子恢复实现 | G0 在实现恢复前审核，完整保留 BK/SES；旧 Pending 只读历史或重新审核草稿需明确选择，均不得直接执行 |
+| O5 | UI/视觉/交互细节 | DESIGN-OWNER：待负责人指导与确认。Plan F0 明确逐组里程碑；数据/Agent 可并行，不以临时默认 UI 代替验收 |
+
+上述必需验收原义由自包含来源基线保存；可携带性由实际提交对象重建证明。外部C1取得/独立重放仍未核实，不能以文档纳管代替；正式工程的构建、协议、性能、硬件行为仍需未来授权实证。
+
+## 9. R1草案交付验收与停止（历史；最新批准见Plan §13）
+
+本轮文档验收：R1-01～04落实且未冻结工程候选；分层准入/DAG无自依赖，A0/B0/F0可先设计；证据分类与接纳明确；M1必须包含B1–B4+F1/负责人设计批准及真实Android/磁盘/进程重启；完整功能、63条验收和六联合旅程保留；两文件draft、活动none、其他资产未变。R0→R1修订、工程建议、实际保护和校验统一记录在 [Plan](plan.md) §12，不另建第三份交付文档。
+
+草案完成不意味着任何 V1 功能验收通过。交付后停止，等待负责人审阅 Spec/Plan；不得自动进入开发、激活、Schema、联网实验或发布。
+
+## 10. G2/G4治理流程与来源修订记录
+
+2026-10-23。R1产品批准及§1.1–1.2、§4–7行为/不变量/验收保留；本次仅对齐工程方法、准入与来源，产品revision仍R1、状态approved、执行未启动。原编制/交付记录按历史解释，最新产品批准见Plan §13。
+
+R2 V1仍是Major，其内部Simple/Feature任务按唯一Engineering Policy使用必要记录；各Major自行维护执行状态，以写入/契约/资源协调并行。本Spec真实业务依赖、63条验收、六旅程、六UI设计组及所有数据/Agent/备份安全要求保持。
+
+[来源基线](../../../product/record2-v1-decision-baseline.md)保存必需决定与验收原义；E01～E12保留调查历史。外部C1代码不可取得时不接纳，可沿已冻结行为重建，不重开技术选型。
+
+本修订与入口/Policy/Plan按Policy §7共同切换；最终Diff、Git纳管/Commit与生效需明确授权并验证提交对象。治理切换不构成A0/B0/F0、代码/Schema/设备/API/真实数据或发布授权。
diff --git a/docs/specs/record2/v1/plan.md b/docs/specs/record2/v1/plan.md
new file mode 100644
--- /dev/null
+++ b/docs/specs/record2/v1/plan.md
@@ -0,0 +1,527 @@
+# Record 2.0 V1 Implementation Plan — R1
+
+- **Status:** `approved`；revision `R1`；2026-10-23（Asia/Shanghai）。**Execution:** 未启动。
+- **Spec approved / Plan approved:** 均于2026-10-23获负责人正式批准，原话及范围见§13；**Schema / migration consent:** 未取得；**Active spec:** none。
+- **行为与验收来源：** [spec.md](spec.md)与[自包含来源基线](../../../product/record2-v1-decision-baseline.md)；Spec E01～E12保留调查历史和外部C1核实边界。本文只拥有任务、依赖、范围、测试接缝和门禁，不形成另一份行为Spec。
+- **产品执行授权：** 尚未启动A0/B0/F0或功能任务；设计、代码/构建/依赖、Schema、设备/API/数据库及发布分别按具体授权执行。§11–12保留草案编制历史，产品批准见§13，治理流程修订见§14；文档纳管和治理生效不授权产品执行。
+- **设计权限：** `DESIGN-OWNER：待负责人指导与确认`。负责人提供方向 → 候选方案 → 负责人审查调整 → 明确批准 → Codex 实现；未经批准不冻结视觉/交互，不以默认 Material 页面通过最终验收。
+
+## 1. 工程判断与实施入口
+
+正式工程目前是 Record 1.0 M1 应用壳与 P0–P6 底座，正式 UI 没有真实 finance/Note 写入闭环。C1 是已批准的技术基座：外部六表原型在最终提交完成 A-AC1～6，但没有业务 version、SD1、完整备份或 AI。正确接纳单位是经过审查的行为、金额逻辑、事务模式和测试；不能用原型替换正式根目录，也不能把旧 Repository/P5 或 C1 的 PASS 合并成正式 V1 PASS。
+
+**工程原则：Clean Rebuild First, Selective Reuse When Justified。** 优先评估干净R2工程；现有七模块、Repository/DAO/EntityRef、旧Schema/余额/转账、Navigation/AppContainer、UI/主题、Vault/备份格式和不适用测试不构成默认继承义务。C1是技术基座与已验证成果，不冻结其单模块原型组织或源码。选择只依据完整V1的实现质量、总工作量、风险与维护成本，不以投入时间、已有代码量/文件数决定。
+
+**当前建议（未冻结）：** 在同一Git仓库内设置独立构建根，候选`record2/`，起步一个Android应用模块，内部按业务/服务与数据/AI适配/UI职责分包；不把旧根工程接入新产品依赖图，不为每个逻辑职责创建模块。A0核查路径占用、构建隔离和完整V1成本后，比较此路径与受控改造再提交负责人审核；若没有充分证据表明改造明显更优，推荐干净实现。本轮不创建该目录/工程，也不指定未经批准的最终导航或视觉方案。
+
+旧资产可以不进入R2，但原始源码、数据库、测试/实验、文档、Commit和用户修改都保留；不清理、不覆盖、不自动迁移、不destructive migration。A0提出两代文档适用范围及必要ADR/路由同步，由后续明确授权执行；新工程不必迁就旧业务约定，也不能靠放宽旧测试声称R2验收。旧根约束继续保护旧工程，本轮不改它们。
+
+### 1.1 G-ENTRY：分层准入，按任务与动作核准
+
+| 层级/门禁 | 启动条件及允许行为 | 不能据此推得的权限 |
+|---|---|---|
+| 第一层 G-DESIGN | Spec/Plan分别获负责人明确批准，记录版本/日期/context，并有对应设计任务与文档写范围授权；A0/B0/F0及必要只读调查可并行开始 | 不要求A0工程/身份、B0最终Schema、F0设计已批准；不要求先激活功能阶段。不能写源码/工程/Schema或运行实验 |
+| 第二层 G-CODE | A0推荐方案经负责人审核，相关文档/ADR适用范围已获准同步；明确源码/构建/依赖/接线写范围；执行前本Major的in-progress状态及写入/契约/资源协调就绪 | Spec/Plan或A0报告完成都不自动批准所有代码；不默认旧七模块/导航/库。只获得某类写入，不代表其他任务获得权限 |
+| 第二层 G-SCHEMA（附加） | B0设计包完成，结合A0的工程/数据身份审核通过；G-CODE就绪；正式新建/变更Schema有明确对象/版本/路径/回退与单独同意 | B0先做设计，不依赖自身Schema批准；Schema源码写入许可不自动等于数据库初始化/迁移/恢复执行许可 |
+| 第三层 G-EXEC（逐任务/逐动作） | 实际对象与风险匹配的授权：构建/依赖/缓存/SDK写入；精确AVD/真机及包安装；库初始化/迁移；真实DeepSeek及费用/Provider；Key配置；用户数据读取/恢复；CI/签名/发布分别记录 | 不存在一份笼统“所有外部操作已批”。某项测试写在Plan里不授权执行；生产发布、环境/秘密/CI、文件或历史删除仍单独红线审批 |
+
+设计批准不等于功能激活；A0/B0/F0设计按对应任务授权开始，不要求先激活。进入功能实现前核对本Major的批准/执行状态和权限；其他独立Major可在无冲突时并行，不要求路由维护唯一活动指针。旧M2批准历史保留。设计依赖和执行权限分开记录：下图实线表示产物/审核依赖，G-EXEC不是要求所有外部授权一次齐全的总节点，而在各任务触发对应动作前检查。
+
+| 任务/动作 | 具体准入，避免隐藏前置 |
+|---|---|
+| A0/B0/F0设计 | 仅G-DESIGN和各自设计/只读范围；可使用待定身份假设并标待收口，不等待最终工程或Schema批准 |
+| A1工程/构建 | G-CODE+A0工程方案批准；解析/构建触发G-EXEC的依赖/缓存/SDK等写入同意；不在A1默认开库/装设备 |
+| B1–B4/C0/C1/E0/E1/E2及工具/UI实现 | G-CODE；涉及物理Schema另需G-SCHEMA；实际库/设备/凭据/API动作再核对应G-EXEC。仍必须满足各业务依赖及视觉设计批准 |
+| D0/D1/F1/F2/G1/G2运行 | 技术前置齐备之外，逐项获得实际设备/包/合成数据/Provider费用/凭据/恢复范围许可；没有则只停止依赖该动作的部分 |
+| G0后续恢复设计 | 保留B0/C1/E1事实依赖；设计可读已批准契约，真正Schema/恢复写入及运行仍分别授权 |
+
+证据接纳按Spec §2.4及A0的任务依赖清单办理，不让未接纳资料被当正式基线；也不把完成该清单要求倒置为A0设计启动前提。
+
+## 2. 工作流、里程碑与依赖
+
+任务按可验证业务结果拆分。跨层垂直闭环优先；物理数据契约/工具链是明确基础任务，不能伪称用户功能已完成。以下所有未来任务初始 **pending / NOT RUN**，没有被派发。
+
+```mermaid
+flowchart TD
+    DESIGN[G-DESIGN 双批准及对应设计任务授权] --> A0[A0 两路径比较与工程建议]
+    DESIGN --> B0[B0 新逻辑物理数据设计包]
+    DESIGN --> F0[F0 负责人设计指导及分组批准]
+    A0 --> CODE[G-CODE 工程审核及明确写入与激活]
+    CODE --> A1[A1 正式构建基线与选择接纳]
+    CODE --> SCHEMA[G-SCHEMA 数据审核及明确Schema写入同意]
+    B0 --> SCHEMA
+    A1 --> B1[B1 收支与草稿]
+    SCHEMA --> B1
+    B1 --> B2[B2 Note自动保存与软删除]
+    B2 --> B3[B3 双向关联和并发]
+    B3 --> B4[B4 时间线查询及精确汇总]
+    SCHEMA --> C0[C0 Proposal创建修订]
+    A1 --> C0
+    B3 --> C1[C1 原生确认A/B和恢复]
+    C0 --> C1
+    A1 --> D0[D0 Koog与DeepSeek协议验证]
+    SCHEMA --> E0[E0 授权BYOK]
+    A1 --> E0
+    SCHEMA --> E1[E1 Room历史与ChatMemory]
+    D0 --> E1
+    E1 --> E2[E2 取消删除和进程恢复]
+    E0 --> E2
+    C1 --> E2
+    B4 --> D1[D1 真实Record Tools和Agent闭环]
+    C0 --> D1
+    D0 --> D1
+    E0 --> D1
+    E1 --> D1
+    B4 --> F1[F1 原生业务最终页面]
+    F0 --> F1
+    C1 --> F2[F2 悬浮聊天原生交接]
+    E2 --> F2
+    D1 --> F2
+    F1 --> F2
+    F0 --> F2
+    B0 --> G0[G0 ZIP与代际协议审核]
+    E1 --> G0
+    C1 --> G0
+    G0 --> G1[G1 完整备份恢复和故障]
+    E2 --> G1
+    F1 --> G1
+    F2 --> G2[G2 全部联合验收]
+    G1 --> G2
+```
+
+| 里程碑 | 结果与出口 | 仍不代表什么 |
+|---|---|---|
+| M0 准入与正式基线 | A0/B0设计审查、G-CODE/G-SCHEMA及A1相应授权范围内的构建原证据；明确正式身份和证据接纳 | 设计任务在M0收口前即可开始，不代表业务/Agent/设备全获批 |
+| M1 真实原生业务闭环完成 | B1–B4正式能力 + F1正式原生UI全部接受 + 对应负责人设计批准；真实Android页面、Room磁盘和进程重启证据齐全 | 只有B1–B4时仅称“原生业务内核完成”，不能宣布M1；缺Agent/完整BK仍不是完整V1 |
+| M2 受控 Agent 与原生交接 | C0/C1/D0/D1/E0/E1/E2/F2；真实模型完整工具、提案确认与会话 | 备份/联合验收未过，不能称正式 V1 |
+| M3 完整 V1 联合验收候选 | G0/G1/G2，全部 Spec 验收 PASS 和负责人最终设计确认 | 不自动部署/公开发布，发布仍另行授权 |
+
+M1/M2是依赖收口标签，不是AI延期或中间正式发布。F0与A0/B0从G-DESIGN并行；图中F0→F1/F2指对应设计组批准，不要求先完成所有组。关键路径族：`G-DESIGN→A0→G-CODE→A1`与`G-DESIGN→B0→G-SCHEMA（还需G-CODE）`会合进入B1→B2→B3，再经C0/C1/E2/F2/G2或B4/D1/F1/F2/G2；恢复分支C1/E1→G0→G1→G2，版本协议分支A1→D0→E1/D1。G-EXEC在各动作前核权限，不增加反向设计依赖。没有任务时长证据不能声称唯一最长路径，不以旧投入或C1原生粗估推算完整V1工期。
+
+### 2.1 并行与串行规则
+
+- G-DESIGN后A0工程调查、B0数据设计、F0负责人视觉/交互指导和必要版本只读分析可并行；B0字段可先用逻辑身份，最终审核结合A0身份方案收口。实现/构建仍等各自设计批准和写入授权。
+- A1及对应数据审核/写同意后，提案规则、凭据/授权、模型协议工作在各自依赖/权限齐备且不共写时可并行；B4/C0/E0/E1/D0后工具与已批准原生UI可并行；G0后备份和AI UI同理。
+- 新工程同一数据模型/DAO/公共DTO、构建目录、composition root/导航Host、同一AVD/包/fixture或verification共写必须串行；不要求其名称/文件与旧工程相同。
+- B1→B2→B3→B4保留当前计划的业务/测试接纳顺序，避免改动未稳定的公共写边界；不是强制共享旧Repository/Schema。可控并发由测试交错证明，不靠多个代理同时写库。
+- C1 依赖 B1–B3 的正式业务 CAS 和 C0，不需要等待时间线/汇总 B4；两分支若共写 Repository/DAO 仍须串行。D1 可在 C1 前验证 prepare，但 F2 的确认路径必须等 C1。
+- 前置产物及其必要验证证据满足完成门禁后才开始下游；责任Agent直接检查并承担集成，不另设worker完成/主代理接受两套普通状态。G2最终组合检查串行；主Agent可端到端完成，委派按Policy、用户和运行环境要求选择。
+
+## 3. Workstream A：正式工程基线
+
+### A0 — 干净重建与受控改造两路径比较、正式工程设计审核
+
+- **目标/依赖：** 仅G-DESIGN，不依赖尚待本任务给出的工程/身份批准。优先评估方案A干净R2，方案B受控改造为对照；两者都继承冻结R1，不重选C1/Koog。输出可审查推荐，不创建工程或接纳代码。
+- **范围：** 只读现有主工程/C1/依赖声明/测试与资料；仅写获准设计报告/必要文档提案。不得为“比较”写Gradle、Schema、源码或运行构建实验；需实证执行时另提交具体范围申请，未知成本如实标明。
+- **验收/测试：** 按§3.2十项逐项给来源、改动/耦合影响、完整V1工作量区间或未知及风险；§3.3八项交付完整，证据分类/接纳按Spec §2.4。代码量/历史投入只作资产定位，不作选优得分。校验报告链接、来源可得性/摘要、历史保护和比较结论可追溯。
+- **风险/回退：** 不能因旧工程存在就默认B，也不能因A优先就无证据断言最省。没有充分证据支持B明显更优则推荐A，未核实的工具链/工时不伪填。方案未批只修订文档，不覆盖/删除旧源或改历史。
+- **完成门禁：** 主代理接受比较/证据清单和审查包，任务设计交付完成；负责人对工程/身份/资产接纳和后续写范围的明确审核是G-CODE，独立于A0设计启动。B0/F0不等待此审核才开始设计。
+
+### A1 — 正式构建兼容与最小资产接纳
+
+- **目标/依赖：** A0推荐方案获负责人审核且G-CODE就绪；在获批正式结构上建立构建基线，选择性接纳审查通过的C1/旧资产，不预设七模块。解析/构建前有对应G-EXEC环境/依赖写入同意。
+- **范围：** A0选定的新工程构建根/应用模块及必要包，或经举证批准的旧工程改造范围；精确build scripts/wrapper/依赖和接纳文件在任务合同列明。旧根默认只读；无自动CI/环境/签名文件改动、源码清理或整个原型覆盖。
+- **验收/测试：** 正式结构下host金额边界/往返、应用/API边界检查与assemble app/test，最小Koog/Tools/ChatMemory编译接缝；检查实际依赖/许可和merged/finalAPK组件/权限/备份规则。A1出口不要求开库/安装；API34真运行归D0及后续业务任务，在其具体设备/Schema/初始化许可下验证。
+- **风险/回退：** ABI/metadata、KSP、AGP内置Kotlin、Compose编译器、Room生成、HTTP引擎及D8/R8；失败保留原错误，不用metadata skip/manifest override/强制旧stdlib绕过。旧构建入口原样保留，不纳入新依赖；回退只处理本任务获准变更，不回滚用户工作或删除新旧资料。
+- **完成门禁：** 正式构建根/模块清单、版本/制品hash/依赖许可/host原证据接受，且旧根/资产保护通过；没有把B0最终Schema/设备许可倒挂为A1前置。真实Android和Provider仍由D0/下游独立证明，不能用编译通过代替。
+
+### 3.2 A0必须完成的十项比较
+
+本轮静态建议如下，A0须补实际依赖/变更面/成本依据再审核；不是已执行A0或已经冻结目录。
+
+| 维度 | A：干净R2（优先评估） | B：受控改造旧工程（对照）/A0取证要求 |
+|---|---|---|
+| 1 R1符合度 | 直接围绕CNY/纯文本/Agent/完整备份组织，不继承旧领域 | 逐一证明旧EntityRef/余额/附件等不会进入新路径；两者都核完整验收 |
+| 2 新业务模型 | 可直接设计version/CAS、Proposal/Session；新实现与验证成本非零 | 列旧DTO/DAO到新事实的差异与重写范围，不能把旧方法存在算完成 |
+| 3 技术债/耦合 | 新边界可避免旧源/构建检查耦合，须控制新增结构 | 追踪Repository/启动/备份/模块检查的调用链与回归面，计适配维护成本 |
+| 4 Room自由度 | 从V1设计新库，不需要旧Schema迁移/兼容层 | 证明改造也能隔离新模型/旧库，不能以复用牺牲约束或主动迁旧数据 |
+| 5 Koog集成 | 从最小Android依赖/轻量Coordinator接入，仍待真协议验证 | 计旧composition root/依赖冲突的改动；不得把现有手工接线当必须继承 |
+| 6 工具链 | 可独立选择经验证组合，不影响旧根；需新构建授权与成本 | 计AGP/Kotlin/Gradle/Compose/Room/KSP联动及旧构建回归，版本均未实证 |
+| 7 Application Services | 原生/工具共用确定性服务，Koog留边界 | 证明旧Repository能降低完整写入/CAS/恢复成本，否则舍弃；不建通用引擎 |
+| 8 原生与全局AI UI | 新Host/导航合同可直接满足交接；最终设计仍归负责人 | 计旧四tab/占位/导航/主题解绑工作，不能当成已批准UI |
+| 9 Android测试/恢复 | 选取JUnit/Room/Compose/独立观察方法重建正式夹具 | 计旧apiGuard/fixture/Vault依赖适配，所有有效行为均须正式重验 |
+| 10 总工作量/风险/维护 | 计脚手架、全部业务/AI/备份/测试/文档成本，不把从零当免费 | 同口径计迁就旧模型、回归与长期双语义负担；无法证明明显更优则选A，数值未知就写未知 |
+
+现有事实支持优先建议A：旧Note缺正文、业务DTO不表达R2字段，旧Schema/恢复与新语义差异大；C1已证明无需旧七模块也能完成基本闭环。它们不证明新工程已兼容Koog或已完成V1，A0/A1仍分别补设计审核和授权构建证据。
+
+### 3.3 A0审查包与资产取舍建议
+
+| 交付 | 本轮建议/后续必须补齐 |
+|---|---|
+| 两路径实际比较、推荐理由 | 覆盖§3.2十项、真实源码/构建依赖及同口径成本/未知；优先A，无证据不声称B更省 |
+| 工程结构决定 | 候选为仓库内独立构建根+单Android应用模块、按业务包拆分；不保留七模块为默认，不增通用框架。核目录占用/嵌套构建/仓库规则，再由负责人批准 |
+| 正式身份 | 独立applicationId/namespace、私有数据库名/代际标识、ZIP格式/应用身份建议；具体值和debug/release/测试区分待审，不复用旧生产库或C1实验包作为正式身份 |
+| C1选择性接纳 | Money解析/格式化、stableID在提交前持久化、短Room事务/草稿消费思路、ContractDriver/DiskObserver和故障测试方法逐项审查；去固定时钟/run绑定/硬编码路径，正式重跑。Note无CAS、局部关系覆盖、扩大LIMIT/实验Schema/UI不逐字复制 |
+| 值得复用的Record1资产 | 金额边界/事务all-or-none的测试oracle、故障注入/独立磁盘/宿主进程验证方法、合适的JUnit/AndroidX/工具链经验；代码仅经许可/依赖/成本审查后进入新根，无默认接纳 |
+| 不进入新产品的旧资产 | 旧七模块组织、Repository/EntityRef/AccountEntry及余额转账、旧Schema/备份格式/Vault、旧导航/AppContainer/主题UI及绑定旧业务的测试默认舍弃，理由是语义不符或适配负担。此处“舍弃”仅是不纳入新产品，原文件/库/历史全部保留 |
+| 历史与证据保护 | 旧根/数据库/未提交内容原样；新根默认不include旧project/源码、不打开旧库；Spec E01–E12按§2.4逐依赖接纳，需保留C1精确源码/许可/测试及原证据的获批可取渠道，不自动复制敏感资料 |
+| 负责人A0后批准 | 选A/B及具体构建根/模块边界、正式身份/旧资产隔离、接纳/不接纳清单、文档/ADR适用范围、下一任务精确写入范围；Schema/设备/凭据/API/恢复/删除/CI/发布另按对应层级授权 |
+
+即使选A也不删除旧源码/模块/历史或清理工作区；即使选B也不默认迁库。A0只交设计审查包，实际工程创建/代码接纳从G-CODE之后的A1或对应任务开始。
+
+### 3.1 R0只读版本调查（2026-10-23，R1仅保留参考）
+
+| 组件 | 旧Record1根（不默认继承） | C1 实验 | Koog 接入影响，尚非冻结升级方案 |
+|---|---|---|---|
+| JDK / Gradle | wrapper 9.5.0；本轮未运行 JDK/Gradle | 历史 JDK17 / 8.9 | Koog quickstart 给 JDK17+、Gradle8+；必须测实际 consumer 组合，不能等同支持全部组合 |
+| AGP / Kotlin | catalog 9.3.0 / 2.2.10；AGP9 内置 Kotlin 与外部插件需分别看 | 8.5.2 / 1.9.24 | C1 Kotlin 低于当前 Koog 文档 2.2+；不能原样继承。Koog 1.3.0 源 catalog 自用 Kotlin2.3.10/AGP8.12.3，不是应用必须复制它的 AGP 版本 |
+| Compose | BOM2026.06.00，Compose plugin2.2.10 | BOM2024.06.00 / compiler1.5.14 | C1 升 Kotlin2 后需同步 Compose compiler plugin；Compose UI BOM 是另一条兼容维度，不以 Koog 为由全量更新 UI |
+| Room / KSP | 2.8.4 / 2.3.9 | 2.6.1 / 1.9.24-1.0.20 | C1 KSP 绑定旧 Kotlin，须联动验证生成代码/Schema；Koog 不直接证明需要升级 Room |
+| SDK | min/target34、compile36 | min/target/compile34 | Koog1.3.0 convention source min23/compile36，说明 API34 有验证路径；需查实际 AAR/传递 AAR metadata/manifest，不据源码保证所有制品可用。不得提高 min34 或偷偷改变 target |
+| 序列化/协程/HTTP | serialization1.9.0；正式无 Koog | coroutines1.8.1；无模型客户端 | Koog1.3.0 自用 serialization1.10.0、coroutines1.10.2、Ktor3.3.3；正式需解析冲突并最小选依赖，不引入遥测/通用工具/不需要的 provider |
+
+R0查阅的公开依据：[Koog quickstart](https://docs.koog.ai/quickstart/)、[1.3.0 catalog](https://raw.githubusercontent.com/JetBrains/koog/1.3.0/gradle/libs.versions.toml)、[Android convention](https://raw.githubusercontent.com/JetBrains/koog/1.3.0/convention-plugin-ai/src/main/kotlin/ai.kotlin.multiplatform.gradle.kts)。1.3.0只是当时可核对候选，不是批准pin；R1没有重新联网核验、构建或安装。A1在获批新工程独立验证，不为继承任一旧组合牺牲兼容性。
+
+[Kotlin 官方兼容矩阵](https://kotlinlang.org/docs/gradle-configure-project.html) 的R0查阅结果中，普通KGP2.2.10的fully supported上限低于旧根声明，C1旧组合也不能仅据矩阵保证完全支持；历史通过不等于完整兼容承诺。旧根使用AGP9内置Kotlin，不能只凭catalog判失败；新根不继承该组合，A1记录实际解析插件/编译器。[Compose 官方配置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler) 的Kotlin2编译器插件要求也须纳入验证，不预设只升级Kotlin或必须全栈升级。
+
+## 4. Workstream B：业务数据与 Application Services
+
+下列A–G范围均为业务职责，实际目录/模块由A0获批结构和任务合同绑定；不隐含旧`:core:data`/feature/app目录或旧Repository继承。B0只设计，后续源码/Schema/设备/数据动作分别遵守G-CODE/G-SCHEMA/G-EXEC；视觉实现还需对应F0批准。
+
+### B0 — 从完整V1设计新逻辑/物理数据方案（设计任务）
+
+- **目标/依赖：** 仅G-DESIGN，可与A0/F0并行，不等待最终Schema批准。由Spec §4/6完整V1需求设计新的字段/约束/版本向量、单Run/Proposal/Operation、代际/恢复标记与测试oracle，不默认演进旧Schema或兼容旧数据。
+- **范围：** 仅获准R2数据设计文档/必要ADR提案；不写Room注解、Schema JSON、SQL或数据库。工程/身份尚待A0时明确假设和待对齐项，不为设计调查建库。
+- **验收/测试：** 设计级逐项trace金额/正文/时间/隐藏关系/CAS、Session删除保Operation、完整备份；列owner/合法状态/拒绝方式/物理约束/验证接缝。新逻辑模型不带旧EntityRef/余额/附件义务，所有假设可供审查。
+- **风险/回退：** 不因无旧个人数据继承需求主动建复杂兼容层；旧库与个人数据持续保护，任何覆盖/迁移/删除另批。若未来已有R2真实数据则设计非破坏演进与恢复，不destructive fallback。
+- **完成门禁：** 主代理接受设计包，提交负责人结合A0工程/身份结论审核；审核和明确Schema写入同意形成G-SCHEMA，正式约束测试随B1/C0/E1等获准写入任务执行。B0设计完成不宣称物理实现或数据库初始化已批准。
+
+### B1 — 原生收支保存、编辑与独立草稿
+
+- **目标/依赖：** A1+G-SCHEMA（含B0设计审核）；实际初始化/设备运行另核G-EXEC。统一服务完成默认来源幂等初始化、精确收支新建/编辑、stable ID、version CAS、一次原生草稿保留/恢复/消费。支持B-AC1～3、B-P1～4。
+- **范围：** 正式R2金额/日期/DTO/Room/Application Services、收支状态/草稿交互及测试；绑定A0获批目录，视觉实现等F0/F1，业务合同可先用测试驱动。
+- **验收/测试：** Host 边界/属性；真实磁盘 5800→6050、收入10000、null分类、账户改名/重启；非法输入零写；双击/首次插入失败/更新失败/提交后回执丢失同 ID 不重复；草稿失败不能显示已保留，进程恢复只承诺已确认 revision，成功原子消费。
+- **风险/回退：** 旧 DTO 不能表达 R2 字段；不得把 AccountEntry 余额/转账混入新模型。业务失败回滚旧值、保留草稿与身份，未知结果查相同 identity，不自动重新分配 ID。
+- **完成门禁：** 独立观察的正式事实/草稿前后快照、失败与强停重启通过；不以 ViewModel fake 或编译作为保存成功证据。
+
+### B2 — Note 自动保存、CAS 与软删除闭环
+
+- **目标/依赖：** B1；独立纯文本 Note 创建/编辑，输入 revision 与业务 version 区分；原生删除/撤销/恢复同身份、拒绝迟到写。支持 C-P1～4、SD-P1/3、TP-06 基础。
+- **范围：** 正式Note服务/查询/获准Schema、笔记状态与测试；不依赖旧Note实体/Repository，不引入Markdown/附件。
+- **验收/测试：** Host 时序测试 + 磁盘两个编辑会话 CAS；约500ms 自动保存/离开 flush、空白新建无事实、清空旧正文拒绝、失败保留最新输入；自动保存与删除/恢复交错，旧回执不显示新文本已保存，旧写不能复活。提交前/后与回执前强停，恢复原正文/时间/ID。
+- **风险/回退：** C1 回执检查不能代替数据库 CAS；冲突保留本地输入并重读，不最后写覆盖。软删除失败原状态不变，重试是目标状态而非 toggle。AI 预览不得复用自动保存 owner。
+- **完成门禁：** 每个竞态的预期/实际版本、正文摘要/删除状态和重复重启原证据通过，才开放关联/AI 更新接入。
+
+### B3 — 双向关联、隐藏关系与版本并发
+
+- **目标/依赖：** B2；统一增量 add/remove 服务，原生批量选择“完成”调用相同服务；两端/关系版本、可见性与唯一配对检查。覆盖 D-P1～3、SD-P2、TP-05。
+- **范围：** 正式关系服务/DAO/查询/事务，收支/笔记来源状态、选择适配与测试；不用旧EntityRef通用关联引擎。
+- **验收/测试：** T1–N1/T1–N2/T2–N1 跨日双向同库；取消/未变化完成零写，批量真实中途失败零变化；解除一条保留其他；删除 Note 后普通列表/计数/候选隐藏，恢复原关系；选择期间删目标、另页改关系后旧选择整次拒绝、隐藏关系不被误删。
+- **风险/回退：** 用局部可见 selected 全覆盖会丢隐藏关系；提交基于读基线的增量且 CAS。失效端点不隐式恢复，失败保留当前勾选并要求重载。
+- **完成门禁：** 事务前后完整关系集合/端点版本/双向结果独立比较通过，才接 C1/Tools 的 LINK/UNLINK。
+
+### B4 — 连续时间线、真实检索与确定性汇总
+
+- **目标/依赖：** B3；原生与工具共用可见性/日期/类型/关键词查询，稳定分页与详情/关联读取；本地精确收入/支出汇总。不是 P1 搜索中心或仪表盘。
+- **范围：** 正式查询/SQL/DTO、时间线状态/锚点/返回适配、精确汇总及测试；索引若需Schema改动先取批准，不接旧搜索中心/投影事实。
+- **验收/测试：** 独立 oracle 对比中文/换行/关键词、同刻 stable ID、分页首尾/空页/重复时间、日期编辑移动、时区变更/DST/午夜、旧请求迟到；可见性覆盖全部入口。Long 累加溢出、分类可空、跨日半开区间和无记录统计准确。固定高量合成库测查询/首屏/滚动/汇总，不借旧 P4/P6 PASS。
+- **风险/回退：** C1 的扩大 LIMIT/固定日期不能作为完整分页；性能优化不得先建 FTS/向量服务或改变结果语义。失败保留已有可读页、返回安全错误，不显示空成功。
+- **完成门禁：** 正确性/分页/汇总及获批预算通过，才注册 aggregate_finances；统计工具不得假设未来 dashboard 已存在。
+
+## 5. Workstream C：提案、原生确认与 CommitOperation
+
+### C0 — 六类 Proposal 的稳定创建与 Revision CAS
+
+- **目标/依赖：** A1+G-SCHEMA（含B0设计审核）；以正式服务提供六类封闭操作的解析、规范化、localRef/依赖、稳定创建请求幂等、单Session活跃提案、revision CAS、冻结后拒绝修订。支持AI-AC2/3/6、TP-01/02。
+- **范围：** 正式提案值/服务/状态/持久约束及tests，轻量Coordinator调用契约；不要求旧模块，不引Koog类型到业务层。
+- **验收/测试：** Host parser/状态测试和真实 Room 并发；重复 creationRequestId 同内容只一份，不同内容拒绝；rev3/rev4、旧 Run/取消/跨代际晚到、两个活跃 Proposal 竞争；localRef 缺失/循环/类型错误及非法金额时间/身份全部拒绝，未确认零业务事实。
+- **风险/回退：** tool_call_id 不作业务幂等；模型重答不能另造请求身份回避约束。失败保持最后有效 revision，不自动删除原生草稿；失效提案重新审核。
+- **完成门禁：** 完整原始并发结果、唯一性约束与零业务写快照通过；实际原生确认继续等 C1/F2。
+
+### C1 — 用户确认冻结、Transaction A/B 与未知结果恢复
+
+- **目标/依赖：** C0+B1–B3；原生确认指定展示版本→A 锁定 Pending→B 全业务原子提交/Applied；保持不可变 payload、稳定新 ID、operationId 幂等和用户主动恢复。支持 AI-AC4/8/9 与 TP-01～08。
+- **范围：** 正式Application Services/Room/Operation/恢复协调、原生确认状态及tests；不继承旧Repository写模型，不提供Agent commit工具，不把LLM放进事务。
+- **验收/测试：** Host 规范化 payload/依赖测试；Room 独立连接观察组合交易+Note+关系 all-or-none；确认和修订竞争；同 Operation 双执行/双击；A 前/中/后、B 开始/每个业务变更后/Applied 写前后/事务提交后回执前分别异常、取消、进程终止。RejectedConflict/Invalid 零业务修改，异常必须回滚不吞；Applied 重读同结果，Pending 不自动重放。
+- **风险/回退：** A 成功 B 失败不能解锁后偷偷改 payload；保留同 Operation 核查。冲突需新审核确认，跨代际旧 Pending 无资格，关闭 AI/删 Session 不丢 operation/result。B 未提交保护旧事实，B 已提交通过持久结果消除未知，不能“回滚”已生效业务为重试创造重复。
+- **完成门禁：** TP 全矩阵在真实磁盘/宿主强停与重启中证明；每个故障点记录操作状态、完整事实/版本/ID映射和无假成功。无此证据不得验收 F2 的确认。
+
+## 6. Workstream D/E：Koog、工具、会话与安全
+
+### D0 — Koog Android / DeepSeek 真协议接缝
+
+- **目标/依赖：** A1；获准隔离验证中使用 Basic Agent，真实模型完成一次工具调用/多轮工具/跨 Run；确认取消、错误、Thinking/Tool Calling 协议及预算接缝，不重新对照 Runtime。
+- **范围：** app 的最小 Koog/client 适配和授权的合成集成测试，依赖清单；不实现自研 Loop/通用 Gateway 平台。
+- **验收/测试：** API34 上真实 DeepSeek，不只 JVM sample；普通和 Thinking 路径、连续 Tool Call/Result、协议缺字段/未知工具/畸形参数、超时/429/服务失败、取消、预算耗尽、无进展检索；不删除当前 Run 必要 reasoning_content 或工具配对。核对实际 SDK 制品、provider/model ID 和最小所需依赖，日志不带 Key/隐藏推理。
+- **风险/回退：** [Koog Basic Agent](https://docs.koog.ai/agents/basic-agents/) 的 maxIterations 是运行步数，不当精确模型轮数；[DeepSeek Thinking](https://api-docs.deepseek.com/guides/thinking_mode/) 与 [Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/) 的协议随模型变化。失败保持基座、记录协议阻塞并修订适配/pin；不换 Runtime、不暗降 AI 范围、不安装未批准版本。
+- **完成门禁：** 固定模型/协议、合成输入和原始安全请求元数据/断言，真实 Android 多轮 PASS；maxIterations、总超时、Token、费用和重复无进展阈值经实测审核后定数，未冻结不开放无限运行。真实 Key 仅用用户授权的安全配置流程，不在命令行/文件日志中传递。
+
+### E0 — Provider 持续授权与 BYOK/Keystore
+
+- **目标/依赖：** A1+G-SCHEMA（含B0安全状态设计审核）；无AI/完整AI两态、接收方告知/授权、独立凭据、Key认证加密和排除备份；实际凭据配置/设备/网络另核G-EXEC。支持CTX-01/04/07/10、SES-05～07。
+- **范围：** 正式授权/认证/私有凭据接缝、必要安全状态接口、系统备份policy和安全测试；具体设置等F0，CI/真实秘密文件不在默认写范围。
+- **验收/测试：** Host 状态测试，API34/获批真机 Keystore 加密解密/不同 IV/损坏密文/密钥失效；未授权/无 AI 的原话/历史/工具结果零外发，重授接收方；APK/Room/日志/ZIP/提取规则秘密哨兵扫描，网络/服务失败保留 Key。真实秘密不写证据。
+- **风险/回退：** 不把开关、凭据和授权合一；解密失败要求重配不删除历史；撤销先失效授权代次和后续发送，不能保证收回已发送内容。拒绝不安全认证，原生业务继续。
+- **完成门禁：** 授权/凭据状态矩阵及备份三层排除证据通过，D1 才能连接真实业务数据；D0 如使用独立合成测试凭据，需另有相同安全范围的明确授权，不能先借真实业务 Key 绕过 E0。
+
+### E1 — Room 权威历史与 ChatHistoryProvider
+
+- **目标/依赖：** G-SCHEMA+A1+D0；原子保存user+Running、固定已完成历史边界、当前输入一次、最终回复+终态同事务，Koog ChatMemory可重建。支持SES-01/02/04/10～12/15；库/设备/Provider实际运行逐项核G-EXEC。
+- **范围：** 正式Session/Message/Run服务/持久约束、ChatHistoryProvider/Coordinator与测试；不依赖旧会话/数据模块，不建第二权威历史库。
+- **验收/测试：** Host 可控调度 + 磁盘事务/故障；同 Session 两 Run 竞争、跨 Session 隔离、长历史窗口裁剪/过滤，UI 全历史仍可读；用户消息落盘后、Koog load/store 前后、最终回复事务前/中/后终止，当前输入不重复、不出现缓存幽灵回复、未完成工具序列不进入下一 Run。真实 Koog/DeepSeek 连续多轮和恢复后新 Run 验证。
+- **风险/回退：** [ChatMemory 官方示例](https://docs.koog.ai/features/chat-memory/) 的 load/store 直接存消息不能照搬为用户历史双写；store 的窗口可能已裁剪。缓存版本/Provider/会话/代际不一致就废弃重建，不能反向覆盖 Room；隐藏推理不进用户历史/备份。
+- **完成门禁：** 所有故障时窗的权威消息/Run 状态独立比对和真实跨 Run 协议证据通过；接口只暴露必要历史 DTO，不做独立 Memory 平台。
+
+### E2 — Run 失效、取消、会话删除及进程恢复
+
+- **目标/依赖：** E0+E1+C1；应用级 owner 保持页面变化期间运行，进程遗留标 Interrupted；取消/撤销/换 Provider/删除 Session 阻止迟到写，Pending 独立核查。支持 SES-03/04/08/13/16。
+- **范围：** 正式Coordinator/应用生命周期、恢复/删除协议、必要状态与tests；不继承旧AppContainer/启动引擎，无后台常驻服务。
+- **验收/测试：** Activity 重建/页面跳转/面板关闭 Run 不被误取消；宿主真正终止进程、冷启动无自动模型/工具调用；取消与 prepare/最终回复/Operation 核查交错；删除含活动 Run/未确认 Proposal/Pending/Applied 的 Session，消息不复活、事实/核查映射保留，不安全删除拒绝。新 Run 重验焦点和关联。
+- **风险/回退：** UI scope 取消和 HTTP 取消均不足以保证迟到安全，持久/应用身份检查必须成立；未知 Operation 不级联删。失败进入可重读恢复入口，AI 可关闭而已确认核查仍有效。
+- **完成门禁：** 原始 PID/冷启动/请求计数和磁盘结果、会话删除引用完整性、AI 关闭后的 Operation 幂等证据通过。
+
+### D1 — 真实 Record Tools 与完整 Agent 行为
+
+- **目标/依赖：** B4+C0+D0+E0+E1；四工具严格委托真实服务，模型完成创建/修改/检索/全文/关联/精确汇总/归纳，输出合法原生交接结果。
+- **范围：** 正式工具参数/Registry/结果适配、Coordinator与业务服务必要合同调整/测试；不向工具暴露DAO/commit，不默认旧数据模块。
+- **验收/测试：** 可控工具参数与 injection 测试 + 真 DeepSeek 端到端；工具只注册四个指定能力，aggregate 在 B4 后才开放；六类 prepare、错误 localRef/版本/记录、分页/长正文/范围与费用限制、检索无进展、授权撤销前后、恶意 Note、伪 tool/name、跨 Provider 失效；返回真实 ID/版本/覆盖范围，无数据不编造结论。
+- **风险/回退：** 模型选错分类/日期必须澄清或校验拒绝，不能自动扩词表/权限；失败只留可审核提案或安全错误，正式业务未确认零写。已有模型成功回答不抵扣工具与原生验证。
+- **完成门禁：** Spec AI-AC/CTX 的对应断言、真实业务读快照、受控网络数据范围与预算证据通过；C1/F2 另外完成真正确认提交，工具不能宣称自己提交成功。
+
+## 7. Workstream F：负责人设计与原生 UI
+
+### F0 — 设计指导、候选与明确批准（贯穿里程碑）
+
+- **目标/依赖：** G-DESIGN后可与A0/B0并行；负责人可立即指导总体视觉、布局、图标资源、角色/面板、交互动画，不等工程结构结论。只在负责人指导下组织候选，不把线框/Material/旧主题/C1页当批准；本轮不产视觉稿/图标/原型。
+- **范围：** 后续经授权的设计材料/确认记录/资源；任何最终布局、组件外观、色彩/字体/间距、图标插画、角色表情、动效、反馈、未确认交互均由负责人决定。
+- **验收/测试：** 每组记录设计方向→候选→调整→明确批准的版本/范围/日期，覆盖空/加载/错误/冲突/失败/恢复和可访问性；技术可读性检查不能取代负责人设计接受。
+- **风险/回退：** 先写默认 UI 容易把临时方案冻结；无批准就停该视觉实现/最终 UI 验收，继续非视觉服务/状态/导航合同测试，不减功能。变更按负责人反馈调整，不删用户素材。
+- **完成门禁：** 下表相应 D 组批准后才能实现对应设计；所有 D 组最终批准及实现对齐后 G2 才可收口。
+
+| 设计组 | 已冻结功能 / 可规划 UI 技术 | 待负责人指导事项 | 最晚确认点 / 可并行工作 |
+|---|---|---|---|
+| D-FIN | CNY 新建/编辑、可选分类、D5/Back/保存状态；Compose 单向状态、IME/系统栏、可访问语义 | DESIGN-OWNER：待负责人指导与确认；记账页布局/层级、输入及账户分类选择表现、草稿选择与保存反馈 | F1 finance 视觉实现前，M1 前；B1/C0/D0 可并行 |
+| D-NOTE | 纯文本自动保存、阅读/编辑、软删除/撤销/专用恢复；状态 CAS 和输入保留 | DESIGN-OWNER：待负责人指导与确认；编辑/阅读/删除/恢复页面、保存反馈与未确认交互 | F1 Note 视觉实现前，M1 前；B2/C1/E0 可并行 |
+| D-TIME | 混排/日期跳转/类型/分页/详情返回、双向关联；强类型导航合同和状态保留，具体导航实现由A0审核 | DESIGN-OWNER：待负责人指导与确认；首页/导航组织、卡片、关联选择/详情信息层级及空错态 | F1 timeline/links 视觉实现前，M1 前；B3/B4/C0 可并行 |
+| D-AI | 全局角色隐藏/移动/贴边、Bottom Sheet、四种输出、草稿冲突/原生确认；应用级 owner/Overlay/导航/Proposal 状态 | DESIGN-OWNER：待负责人指导与确认；角色形象/表情、聊天面板、Proposal 确认/编辑、导航与冲突表现、动画/转场/反馈 | F2 视觉实现前，M2 前；C/D/E 全部非视觉工作可并行 |
+| D-SAFE | 两 AI 模式、授权/BYOK、历史/删除、完整 ZIP/恢复确认；SAF/Keystore/可恢复状态 | DESIGN-OWNER：待负责人指导与确认；设置/授权告知、历史管理、备份进度/取消/恢复回退表现及具体交互 | F2 设置/历史和 G1 恢复 UI 实现前；E0/E2/G0 算法可并行 |
+| D-SYS | 触控/文字缩放/IME避让/屏幕阅读器/状态公告；测试语义与共享组件技术接口 | DESIGN-OWNER：待负责人指导与确认；整体风格、主题/字体/间距/组件/图标系统和跨页动效规范 | 首项 F1/F2 视觉编码前，随候选完善；所有业务/Agent/数据任务可并行 |
+
+设计尚未完成不改变完整 V1 功能目标。负责人可以按组批准，不必等全部视觉确定才做非 UI 工作；共享设计规则变更须重新检查已实现页面。
+
+### F1 — 正式原生业务页面纵向闭环
+
+- **目标/依赖：** B1–B4 及对应 F0 设计批准；从生产入口完整执行记账/Note/关联/时间线/D5/SD1。可按 D-FIN→D-NOTE→D-TIME 小片顺序实现，各片依赖对应已接受服务，不等无关设计。
+- **范围：** 正式收支/笔记/时间线/关联UI与状态、新应用导航Host/入口/返回、获批共享组件和tests/resources；不默认旧导航/主题/组件，不生成P1占位产品页。
+- **验收/测试：** API34 Compose/真实入口/同一磁盘库，逐项 A-P1～D-P3/SD-P1～3；加载/空/错误、校验、提交中锁定、失败保留、双击/Back、旋转/IME/光标/滚动、重启和真实结果；字体缩放/辅助功能/系统栏与最终设计逐组核对。不能以 preview、截图或 injected Cards 代替。
+- **风险/回退：** 旧四 tab/Transfer/Projects/Search Availability 不因保留源码自动成为新入口；按批准导航接线并保留旧历史/数据。错误保留编辑及选中，冲突重读，不用空编辑器覆盖旧记录。
+- **完成门禁：** F1全原生UI在负责人已批设计下的真实页面/磁盘/进程重启、可访问性与视觉对齐证据接受；与B1–B4联合后才称M1完成。仅业务内核通过不宣布M1，M1也不抵扣完整Agent/BK。
+
+### F2 — 全局 AI、聊天与 Proposal 原生交接
+
+- **目标/依赖：** F1+C1+D1+E2 及相应 F0；角色/Bottom Sheet/聊天历史/两模式设置、四结果类型、真实导航、草稿冲突、原生提案编辑/确认；组合保存正确反馈 Applied。
+- **范围：** 正式AI Host/Coordinator/导航交接/设置历史、与收支笔记共用已批R2输入组件及状态接缝；不默认旧UI/导航资源，不为接纳创建额外通用框架。
+- **验收/测试：** 真实 Koog/DeepSeek/API34 +获批真机；AI-AC1～11，全部 proposal 编辑、D5 并存、Note 预览零保存、取消/关闭页不提交、重复确认同 Operation、屏幕返回上下文；角色跨全部正常业务页/IME/旋转/敏感提交避让；活动 Run 关闭面板继续，撤销授权/删除会话后无迟到结果。
+- **风险/回退：** 原生保存必须确认展示 revision，不另藏聊天确认；模型导航仅稳定 identity，不执行 route 字符串。记录失效显示安全冲突，保留提案不复活 Note；未批准视觉不接受最终 UI。
+- **完成门禁：** Spec 所有 AI 原生交接与设计组接受、持久结果/请求边界证据通过，才进入最终联合验收；不以模型一句“已完成”当成功。
+
+## 8. Workstream G：完整备份、恢复与联合验收
+
+### G0 — 正式逻辑 ZIP/代际协议设计审核
+
+- **目标/依赖：** B0+C1+E1；冻结完整逻辑数据清单、快照边界、格式/资源限制、草稿处理、Operation 历史/代际资格、原子恢复/回退/启动门禁。设计可与 UI 并行，但恢复代码前审核。
+- **范围：** 获准存储/安全/恢复设计/必要ADR、格式/fault map及正式备份/启动契约；G0只设计，不实现旧格式兼容或复制整套旧Vault。
+- **验收/测试：** 数据清单涵盖全部正式业务、隐藏关系、聊天可见历史、Applied映射/不可执行Pending/提案草稿，明确排除凭据/缓存/Trace。每种引用与合法历史有 oracle；全部数值资源阈值、字符编码、整数/版本拒绝规则和候选字节绑定经审查，BK2 边界矩阵完整。
+- **风险/回退：** 完整快照可能与 Run/提交竞态；决定独占/快照及切换前停止/失效方式。A0/B0/O4 未定不写恢复；原子事务导入还是物理切换必须形成单一获批协议，不并建两个实现。不因旧 P5 存在就自动迁入多代框架。
+- **完成门禁：** 格式/限额/恢复协议/故障点和涉及 Schema 的同意齐全；无门禁不能跑“先导出看看”的数据实验。
+
+### G1 — BK1～5 + AI 历史完整恢复
+
+- **目标/依赖：** G0+E2+B1–B4+F1；涉及最终备份 UI 等 D-SAFE 批准；完成真实手动导出、SAF、隔离预检/确认/恢复与回退，含跨代际 AI 安全。
+- **范围：** 正式snapshot/ZIP/SAF/restore/recovery、启动/恢复UI、Android extraction policy和测试；不依赖旧Vault/备份格式，不修改CI/密钥，包设备范围另获授权。
+- **验收/测试：** BK1～5 原始完整矩阵，S/R/O 独立规范化比较；跨设备/新代际聊天可读、旧 Pending 不执行、Applied ID映射与事实一致、提案需重审、授权/Key重建、缓存失效。导出每数据段/manifest/SAF create/write/flush/close/回读；恢复预检/候选导入/确认/切换/完成标记/首次重开前后分别失败/重复故障/进程终止；冷启动仅完整 O 或 S。
+- **风险/回退：** 未读全/关闭失败的包不报成功，半包保持明确 incomplete；失败 O 不变或经验证可回退 O，不能混库；新代际到来后旧 autosave、Run、工具、Operation 均无写资格。清理残留/删除 fixture 必须符合已批准具体范围，不把 cleanup 当自动删文件许可。
+- **完成门禁：** 全部 BK/SD-P4/SES-09/14、source→merged→finalAPK 的云备份/设备迁移/密文排除，以及获批真机真实 SAF/Keystore 结果通过；实际 round-trip 自动化未过则备份功能未完成。
+
+### G2 — 完整 V1 联合验收与审查收口
+
+- **目标/依赖：** 所有任务完成门禁通过、F0 全设计批准；用最终版本联合完成 Spec B/BK/SD/AI/TP/CTX/SES 和 J-E2E1～6，补齐真实设备、性能/依赖/安全检查，供负责人验收。
+- **范围：** 正式测试/合成 fixture、verification 及必要获准缺陷修复；缺陷回原任务记录原因/修复；真实重试按需编号，重跑受影响集合与联合旅程。无自动公开发布。
+- **验收/测试：** §9 证据全量对齐；API34真实持久/故障为主，API36需要时补平台，获批真机跑最终六条联合旅程及真实 Provider/SAF/Keystore；最终源码、merged/final APK/许可和机密哨兵检查；B4/启动/恢复/AI资源预算无 ANR/OOM/假成功。必要独立架构/安全/验收审查按R2 Policy选择，责任Agent仍检查原证据。
+- **风险/回退：** 缺来源、未跑、失败、blocked、设计未批或不可复现均阻止收口，不能互相抵扣；环境问题只阻塞依赖它的门禁。候选保留所有旧数据保护与可恢复路径；不能以剩余预算不足削减 V1。
+- **完成门禁：** 全验收 PASS、每个功能/设计批准有证据、owning 文档/ADR 同步、运行与保护报告齐全，负责人审阅后才能按授权生命周期完成。正式发布/生产部署仍须独立同意。
+
+## 9. 测试接缝、命令模板与故障选择器门禁
+
+验证层级按R2 Policy T0～T4及本任务风险选择；相同构建/fixture/原证据可覆盖多个验收，但每项断言和下列故障/预算要求保留。优先采用适合新工程的host JUnit、Room instrumentation、Compose、宿主定向重启/独立磁盘观察方法；工具代码与依赖逐项审查，不为测试新增Hilt/Robolectric/截图框架。C1 ContractDriver/DiskObserver仅审查并去实验绑定后接纳；旧apiGuard/七模块检查只留在旧根，不因保留历史而强加新构建。新工程按批准边界设置必要检查，不注释错误或放宽数据不变量。
+
+### 9.1 按风险确定执行记录和测试对象
+
+普通Simple/Feature任务记录目标/范围、相关设计/权限、验证结果及下一步或阻塞；并行补唯一write owner，真实重试才编号。不要求每项新建Spec/Plan/Verification或完整Attempt Ledger。V1的联合结果在获准的 `verification.md` 汇总，当前未创建该文件。
+
+高风险持久/安全/恢复及设备执行须在运行前明确：前置产物与原证据、确切host tasks或完整instrumentation class#method、fixture/seed/clock/zone、批准serial/包/库/代际、预期方法、故障点、日志与红线；真实失败/重试保留可区分记录。测试selector在实际存在后登记，未明确对象/权限不进入设备执行或声称下游门禁通过。业务Operation/版本/代际身份不因记录简化而省略。
+
+A1须在获批构建根冻结实际wrapper、任务名和边界检查。旧`:core:*`/feature任务不再作为R2默认命令；若A0批准独立根+单应用模块，下列仅为待核实的候选形状：
+
+```powershell
+# 仅获准后执行；构建根、wrapper、模块/任务名、缓存范围均由A0/A1确认，本轮未运行。
+Push-Location $r2BuildRoot
+try {
+    & $r2Gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
+} finally {
+    Pop-Location
+}
+```
+
+受影响任务选择足量集合，必须先核对任务仍存在；编译不替代 instrumentation。Runner 如不能保证唯一目标，host 先构建，再使用已核实绝对 ADB 路径/明确 serial 安装获准 App/test APK，不能裸跑 connectedDebugAndroidTest 部署到所有设备：
+
+```powershell
+# $r2Adb/$r2Serial/$r2TestPackage/$r2Selector 从已批准本次合同读取，禁止盲用历史serial。
+& $r2Adb -s $r2Serial shell getprop ro.build.version.sdk
+& $r2Adb -s $r2Serial shell getprop sys.boot_completed
+& $r2Adb -s $r2Serial shell am instrument -w -r -e class $r2Selector "$r2TestPackage/androidx.test.runner.AndroidJUnitRunner"
+```
+
+每次设备写前验证 AVD/API/qemu/boot/serial/包签名/实际库归属；CLI 操作、SDK/ADB/AVD、Gradle runner、真机 profile 分开预检。缺 CLI 只阻相应 CLI，不替代 SDK 能力判断。宿主真正 force-stop→PID消失→启动正式 Activity→新PID/前台→同库只读校验；不能仅 ActivityScenario.recreate。需从合同取得明确设备写入授权，不清其他包/真机/历史备份。
+
+### 9.2 必须枚举的故障矩阵
+
+| 测试缝/任务 | 必须冻结的故障点/并发点 | 独立 oracle |
+|---|---|---|
+| B1 草稿/保存 | 草稿落盘前后、业务写后事务提交前、提交后回执前、双击、保存中返回 | 草稿revision/stable ID、交易全部字段/数量、原值/新值及无假成功 |
+| B2/B3 | Note自动保存提交/回执、删除/恢复、批量链接每个变更后、两端版本变化、隐藏目标 | 原文/发生时间/删除状态、完整关系集合、版本、正常与恢复视图 |
+| C0/C1 | prepare重复/迟到、rev CAS、A提交前后、B各业务写后/Applied前后、提交后回执、同ID双执行、跨代际 | Proposal锁定revision、不可变payload、Operation状态、全部事实和ID映射，同步独立连接无部分可见 |
+| E0/E1/E2/D1 | 用户输入原子落盘、load、store、最终回复终态事务、关闭/取消/删除/换Provider、结果外发前 | Room权威历史、当前输入计数、Run owner/终态、网络请求数与字段哨兵、零迟到写 |
+| G1 | 每数据流/manifest首中尾/flush/close、SAF create/copy/verify、预检/导入/切换/完成标记/重开 | S/R/O完整规范化事实、受保护摘要、恢复状态/代际、旧Pending不可执行、Key/授权排除 |
+
+每个关键持久故障至少覆盖 fail-once+restart、持续失败不绕门禁、完成后重复处理幂等；原始失败/工具修复使用不同 attempt 留存。事务异常/取消不能用入口 mock 代替，独立观察器必须读真实生产连接对应的磁盘事实，verify 阶段不能补种或创建缺失库。
+
+性能/预算在相应实现前冻结：B4 的典型/高量数据及查询/首屏/滚动/汇总预算，E2/G1 启动/恢复，D0 的步骤/Token/超时/费用/无进展限制。用可复现 baseline/参考设备提出数值供审核，批准后验收；不能用“感觉快”或旧单日60ms/旧P4结果强套新产品。硬件性能仍按获批真机条件取证，不凭模拟器给物理结论。
+
+## 10. 高风险验证及最终门禁
+
+| 风险 | 最早验证 / 下游阻塞 | 最小通过证据 |
+|---|---|---|
+| C1接纳与两代契约混用 | A0/A1；阻全部正式功能 | 源commit/选中代码/许可/调用者测试和正式包库保护，旧资产原样；不能整包覆盖 |
+| Koog构建/Android/API34 | A1/D0；阻E1/D1 | 实际pin、AAR metadata/manifest、KSP/Compose/Room生成、APK/API34真运行和最小依赖 |
+| A/B中断/幂等/CAS | C0/C1；阻E2/F2/G0 | TP-01～08真实事务故障/强停和独立全事实/结果比较 |
+| ChatMemory双写 | E1；阻E2/D1/F2/G1 | load当前输入一次、store不裁权威、最终回复原子、缓存先成功后崩溃不幽灵；真实多轮 |
+| DeepSeek Thinking/工具协议 | D0/E1；阻D1/G2 | 固定真实模型、多工具/跨Run/reasoning_content、裁剪/取消/恢复后新Run |
+| 会话删除与Pending | E2；阻F2/G1 | 不级联删核查、不复活Session、无AI仍可核查，必要时明确阻止不安全删除 |
+| 完整备份扩展 | G0/G1；阻G2 | BK1～5全量+会话/Applied/Pending/代际/授权Key排除，含并发快照与故障回退 |
+| UI设计授权 | F0对应组；阻视觉实现与最终UI验收 | 明确方向、候选、调整、批准和最终实现对齐；原生/Agent非视觉任务继续 |
+
+G2 最终统一门禁：Spec B-AC1～9、BK1～5、SD-P1～4、AI-AC1～11、TP-01～08、CTX-01～10、SES-01～16 逐项 PASS，页面 A-P1～D-P3 无遗漏；J-E2E1～6 在最终版本真实 Android/磁盘/Koog/DeepSeek 联合通过，批准的真机验证完成；全部预算/故障/许可/安全/设计证据齐全。CTX-11 按 ARCH-04 R2 已取消，不复活受限模式。
+
+每条证据记录 exact command/selector/arguments、commit/variant、JDK/wrapper/依赖、AVD型号/API build/ABI/CPU/RAM/host、真机型号/OS/Provider或模型、fixture版本/时钟、预期/实际/原始报告hash、退出码/方法数/fail/error/skip及剩余风险。正常测试/故障/真实费用分别记；缺环境写 BLOCKED、未跑写 NOT RUN，不以报告摘要或 shell exit0 代替 runner 完成/磁盘结果。
+
+## 11. 待负责人审阅的决定及本轮验收
+
+### 11.1 只保留真正影响实现的未决项目
+
+Spec O1–O5为清单：A0两路径/独立工程结构与身份、资产/证据选择和历史保护；B0新数据设计/单独Schema同意；兼容pin/模型预算；ZIP/限额/草稿/Pending恢复表示及单一恢复协议；负责人逐组UI设计。**不重问** C1、Koog、BYOK、两AI模式、A/B、安全确认或完整Agent是否属于V1。
+
+文档审阅重点：接受/调整工程接纳建议与任务依赖；确认完整范围和验收语义继承；为F0提供设计方向及分组批准安排。当前不要求负责人替 Agent 运行自动校验，也不要求在本轮批准未知物理Schema/构建版本。
+
+### 11.2 R0编制轮工作区基线（历史，不是R1写前状态）
+
+- branch：`codex/preserve-2026-10-21`；HEAD：`57e1b68b309b0459aed1765778b7da4cdfff6f6b`，与架构commit相符。
+- 暂存区为空。初始已修改：`AGENTS.md`、`docs/engineering/agentic-workflow.md`、`docs/engineering/testing-strategy.md`、`docs/index.md`。
+- 初始未跟踪目录：`docs/discussions/`（3文件）、`docs/engineering/evidence/`（1文件）、`docs/specs/record2/phase1c-a/`（Spec/Plan/Verification 3文件）；它们都是本轮只读材料，不自动stage。
+- 初始唯一活动：none；旧 P0–P6/M1 completed，M2 approved，1C-A completed（Verification 内早期 in-progress 叙述是历史执行记录，不能误判当前活动）。目标 v1/spec.md 和 plan.md 均不存在，无覆盖。
+- 首次写入前对 `git ls-files -co --exclude-standard -z` 返回的受控与未跟踪现有文件共909路径取SHA-256；按路径排序JSON字典（Python默认ASCII转义与分隔格式）再SHA-256，集合摘要 `bca99e0d4c618d37c3af7067294ae8095830a314ebf340e3cfa7af7f254a5377`。包含现有未提交内容，不是HEAD版本。
+- C1 受控/未跟踪输入共28文件的同法摘要 `ef176f2727adae94096e1846fff7970369e610a48ddf4cf3e5575fe1b15878d4`；HEAD `4b4b18af2fd4f3ea0410e394952bdebd4911d679`，status为空。外部证据/附件只读，没有迁入、复制覆盖或重跑。
+
+### 11.3 R0文档工作任务记录（历史）
+
+| task_id / revision / attempt | owner / 范围 | 状态与接受依据 |
+|---|---|---|
+| DOC-R0-INVESTIGATE / R0 / 1 | main；全部工程/输入只读 | done：工作区/架构hash/实际源码/外部C1 commit/原始runner/host XML/分轮验收来源已核实；公开链接web与浏览器超时，公开HTTP200读取成功，原文附件经聊天给出的名称路由找到 |
+| DOC-R0-SPEC / R0 / 1 | main；仅新增spec.md | done：完整功能/边界/不变量/差距/验收/设计权限；初写Status/围栏/行尾检查PASS |
+| DOC-R0-PLAN / R0 / 1 | main；仅新增plan.md | done：A–G任务/依赖/串并行/门禁/设计权限/恢复与测试；组合文档与保护校验记录如下 |
+
+实施任务 A0～G2 仍 pending，所有正式功能验收 NOT RUN。此表是获准无活动Spec的文档工作记录，不是实施Execution pointer，也没有创建verification.md。
+
+### 11.4 R0文档验证记录（历史，R1验证见§12）
+
+环境：Windows PowerShell、`D:/record`、上述branch/HEAD；2026-10-23（Asia/Shanghai）。本轮仅文档和只读调查，没有运行Gradle、ADB、APK、数据库或真实DeepSeek，不安装依赖；历史实验结果不当本轮运行。
+
+| 实际命令/检查 | 结果与原始输出要点 |
+|---|---|
+| `git branch --show-current`、`git rev-parse HEAD`、`git status --porcelain=v1`、`git diff --cached --stat` | PASS：分支/HEAD如§11.2；暂存区为空，初始4个修改文件和7个未跟踪文件已记录；目标两文件原先均不存在 |
+| `Get-FileHash docs/architecture/record2-v1-unified-architecture.md -Algorithm SHA256` | PASS：与给定`5de8670f…cefa`完整摘要相符，未改冻结文档 |
+| PowerShell here-string → `python -`；用`git ls-files -co --exclude-standard -z`读现有路径、逐文件SHA-256及排序JSON聚合 | PASS：排除本轮两新文件后仍909路径，摘要仍`bca99e0d…a5377`；涵盖既有未提交修改和未跟踪材料。C1仍28输入路径、`ef176f27…78d4`，外部HEAD/status和本地AI原稿摘要一致 |
+| 同一内联Python检查两文档Status、Markdown链接/围栏/行尾、验收编号、每任务字段及依赖DAG | PASS：37个本地链接存在；63条验收各恰一映射行，6条J-E2E；20任务各具备目标/依赖/范围/验收/测试/风险回退/门禁；图21节点（含ENTRY）无环并覆盖全部任务 |
+| 扫描`docs/specs/**/spec.md`的权威Status行，而非历史正文 | PASS：9 completed / 1 approved / 1 draft / 0 in-progress；活动仍none，index/AGENTS未写入。两新文档均draft，未创建verification |
+| `git diff --check` | PASS，exit0；既有workflow/testing-strategy出现LF→CRLF提示，无whitespace错误。本轮未转换它们的行尾 |
+| 对每个新文件执行`git diff --no-index -- /dev/null <file>`及`git diff --no-index --check -- /dev/null <file>`，Python另查未跟踪文件 | PASS：实际diff仅两份完整新增文档，零既有文件修改/删除；no-index返回1表示有新增差异，check正文输出为空，无空白错误（有LF→CRLF提示）。没有用普通git diff漏掉未跟踪新文件 |
+
+实际diff的归属：普通`git diff --stat`仍仅显示原有4文件、85 insertions/4 deletions；这些是进入本轮前就存在的改动，不列为本轮成果。新Spec/Plan保持未跟踪、未stage；其完整内容就是从`/dev/null`到新文件的新增diff。没有自动把1B/1C-A或讨论材料纳入Git。
+
+验证工具失败也保留说明：首次试图把909路径完整JSON回传时工具输出被截断，JSON解析失败，随后改为只输出计数/聚合摘要，未写文件。探索`rg --files scripts tools`中scripts不存在返回非零，实际tools已读取，未创建scripts。网页工具/浏览器读取共享聊天超时，公开HTTP只读200及附件路由补齐原文；无运行聊天命令。首次no-index检查助手错误地要求exit0，将正常“存在新增差异”的exit1误判为断言失败；纠正为检查exit与空whitespace输出后组合校验PASS，未因此改文档内容或放过真实空白错误。
+
+限制：本轮PASS只覆盖文档结构、编号/依赖/保护与来源核对；未来构建兼容、数据库/迁移、联网/硬件/UI和全部正式功能门禁均NOT RUN。没有以文档验证证明产品安全或可日用。
+
+完成后停止，等待负责人审阅本Spec/Plan，不自动激活、实施、stage、commit或push。
+
+## 12. Draft R0 → R1修订、完整性与工作区报告
+
+### 12.1 逐项修订与工程建议
+
+| 修订 | Spec变更位置 | Plan变更位置与结果 |
+|---|---|---|
+| R1-01 工程重建/选择复用 | §1.3、§3、O1/O2取消旧七模块等默认继承；保留冻结逻辑职责 | §1、A0/A1/B0、§3.2十项比较/§3.3八项交付、A–G范围及§9命令：优先建议独立构建根+单Android应用模块；旧改造只作需证明明显优势的对照。未冻结目录/模块/身份，不创建工程、不迁入代码 |
+| R1-02 分层准入/消除循环 | §2.3按设计、代码/Schema、外部执行分层 | §1.1、DAG、任务依赖和B0：G-DESIGN可同时进入A0/B0/F0；A0产出后经负责人审核进入G-CODE；B0设计不等待Schema批准，结合A0结论审核后才有G-SCHEMA；G-EXEC逐动作，不把全部外部授权倒挂为设计前提 |
+| R1-03 证据可携带性 | E01–E12逐项分类；§2.4定义接纳/可取/重验/敏感资料排除；O1及末尾限制同步 | A0接纳清单/完成门禁与§1.1按依赖接纳；工作区未跟踪资料、外部C1/Downloads/聊天均不宣称干净克隆可得。只读复核必要hash，不复制、纳管或提交来源 |
+| R1-04 M1/UI权限 | §9明定M1含原生业务+正式UI/批准/真实验证，原UI权限完整保留 | M1改为真实原生闭环：B1–B4+F1+对应设计批准+真实页面/Room磁盘/进程重启；只有业务内核不称M1。F0与A0/B0并行，D-FIN/D-NOTE/D-TIME/D-AI/D-SAFE/D-SYS及全部DESIGN-OWNER保留 |
+
+工程方案答复集中在§3.2–3.3：推荐干净构建，不默认保留七模块；复用适用算法/测试方法而非旧领域/工程骨架。旧Repository/Schema/EntityRef/余额转账/导航UI/Vault及不适用测试不进入新产品的建议不等于删除原资产。C1按金额/稳定ID/短事务/测试工具逐项审查，适配并正式重验；历史源、库、文档、Git和用户修改全部保留。A0后仍需负责人批准选路、实际构建结构/身份、接纳清单、边界文档及具体后续写范围，Schema/外部执行另批。
+
+### 12.2 精确Diff的基线与交付方式
+
+两文件在Git中仍未跟踪，普通`git diff`不能产生R0→R1内容差异；不能将从`/dev/null`全量新增冒充本轮修订。本轮在写前只读保存两份R0内存快照，并按原始UTF-8字节SHA-256校准：
+
+- R0 Spec：`cb0df66d7806eb5a7196c262e1dc66b79fea24c7d9dfc0ff48fa128b4e191d83`。
+- R0 Plan：`86be94a899473d5fa30897d821f12a1556649c4ed9734e5444db6721f2ac41d5`。
+
+以校准快照和最终R1生成标准unified diff，逐文件原样展示在本轮工具输出；按文本行对齐，文件字节摘要另核，不以显示层CRLF差异伪造内容修改。没有为diff另写第三个文件、临时快照或Git对象/commit；本节是新增修订报告，其自身新增也包含在最终diff中。
+
+### 12.3 R1写前状态及保护范围
+
+branch仍`codex/preserve-2026-10-21`；HEAD仍`57e1b68b309b0459aed1765778b7da4cdfff6f6b`，暂存区为空。两目标文档已存在且为未跟踪Draft R0；本轮获准修订它们，不覆盖其他资料。`D:/record/record2`只读存在性检查为False，不据此创建或批准该候选目录。
+
+原有4个修改文件（AGENTS/workflow/testing-strategy/index）和其余7个未跟踪文件（3 discussion、1 evidence、3 phase1c-a）完整保留。排除两获准文档后，909路径聚合SHA-256仍以`bca99e0d4c618d37c3af7067294ae8095830a314ebf340e3cfa7af7f254a5377`为写前基线；包含用户未提交内容。C1受控/未跟踪28输入路径以`ef176f2727adae94096e1846fff7970369e610a48ddf4cf3e5575fe1b15878d4`核对，实验HEAD/dirty状态与既有资料只读。
+
+### 12.4 R1文档校验与限制
+
+实际校验使用只读Git/PowerShell、内联Python，以及内存快照的Node/Python差异比较；没有写校验脚本/第三文件。最终执行并复核下列检查：
+
+| 命令/校验 | 接受标准与本轮结果 |
+|---|---|
+| `git branch --show-current`、`git rev-parse HEAD`、`git diff --cached --name-only`、`git status --porcelain=v1 --untracked-files=all` | PASS：分支/HEAD/暂存区/路径状态与R1写前相同，只有两获准文档内容变化，既有修改和未跟踪路径保留 |
+| `git ls-files -co --exclude-standard -z`+逐文件SHA-256/排序JSON聚合；冻结R1 `Get-FileHash` | PASS：其余909路径和冻结架构哈希相符；C1输入/HEAD/status及已读取外部证据hash保持；没有改历史文件 |
+| R0校准快照对比Spec §1.1–1.2及§4–7全文 | PASS：按规范化行尾逐字相同；完整产品范围、业务不变量、六操作、安全协议、63条验收映射和六联合旅程没有删减/改义，历史PASS未转正式PASS |
+| 内联文档链接/围栏/行尾/Status、任务七要素、Mermaid拓扑/前置核验 | PASS：两文档R1/draft，20主要任务完整；DAG无环，A0/B0/F0只有设计入口，A1经过工程批准，Schema写入经过数据批准；六UI设计组/对应批准及M1定义齐全 |
+| `git diff --check`及两目标独立文本空白检查 | PASS：无whitespace错误；既有工程文档的LF→CRLF提示不代表本轮改写。两文件仍未stage，活动Spec仍none |
+
+工具限制如实记录：首次将两份R0全文放入Windows命令参数触发长度限制，未启动该命令；改用内存传递。一次合并快照工具输入超过64000-byte自动检查上限被拒绝，按单文件分次只读处理成功。比较器最初未统一行尾/Windows stdin编码产生假差异；明确UTF-8并规范化行尾后逐字检查通过，未为此更改冻结内容或放宽验收。
+
+本轮只完成R1文档修订；没有额外文件写入，没有新建Android工程、删除/改动旧模块、迁入C1、Schema/数据库/依赖/构建/设备/DeepSeek/签名/CI/发布操作，没有激活、Commit或Push。正式功能验证仍全NOT RUN；工程建议、物理细节和工具链兼容尚待A0及后续独立批准/实证。
+
+Draft R1完成后停止，等待负责人重新审查；不自动进入正式开发。
+
+## 13. R1正式批准记录（2026-10-23）
+
+- **负责人原话：** “Spec R1 和 Plan R1 均可正式批准；下一轮进入实施设计任务授权”。日期为2026-10-23（Asia/Shanghai），来源为本次负责人对话。
+- **批准对象：** Record 2.0 V1 Implementation Spec R1和Plan R1。两者状态由`draft`变为`approved`，revision仍为R1；不修改其范围、架构、安全契约、验收或任务依赖。
+- **分层准入结果：** G-DESIGN的Spec/Plan双批准条件已满足；具体设计任务及文档写范围授权留到下一轮。A0/B0/F0仍pending，其余实施任务也未启动；本次不创建执行记录或设计产物。
+- **仍未获得：** A0工程候选的最终选路/结构/身份批准、F0各组最终设计批准、G-CODE/G-SCHEMA及各项G-EXEC授权。Schema初建/变更、数据库初始化/迁移、设备、凭据、真实API和发布等不因双批准自动获准。
+- **生命周期与保护：** 当前活动Spec仍none，未设置in-progress；AGENTS/index/架构/历史材料与用户既有修改保持原样。index中“未创建或批准”的旧路由文字本次不回写，后续在获准路由同步范围内处理；它不否定本条明确批准事实，也不自动激活实施。
+- **本次校验：** 两文档均approved/R1，批准原话/日期/范围一致，Execution未启动；其余909路径摘要仍为`bca99e0d4c618d37c3af7067294ae8095830a314ebf340e3cfa7af7f254a5377`，Branch/HEAD、暂存区和既有路径状态不变；链接/围栏/行尾及`git diff --check`通过。只记录批准，无源码/Schema/构建/设备/API、Commit或Push操作。
+
+完成批准记录后停止，等待下一轮实施设计任务授权。
+
+## 14. G2/G4治理流程修订与切换边界
+
+2026-10-23。产品Spec/Plan R1仍approved、Execution未启动；§13原批准保留。此次只修订§1的Major活动语义、§2.1协作和依赖证据、§9的风险记录/审查方式及必需来源入口。根规则与唯一R2 Policy拥有执行方法，不另建治理层级。
+
+治理核心原则的批准来源：2026-10-23负责人对话原话：“批准治理方案的核心原则，不再增加新的治理层级；下一步让 Codex 按明确范围修改实际规则，把这次改革真正落实到仓库中。”该批准覆盖核心原则；最终采用Diff、Git/Commit和规则生效仍以对应明确授权为准。
+
+- 本V1仍沿正式Spec/Plan及对应设计、任务和对象授权实施。Simple/Feature使用必要记录，多个独立Major按真实冲突协调；没有任务授权不激活。
+- 23节点44边的DAG、B1→B2→B3→B4、各任务产物/验收、M1含F1正式UI、六设计组批准、63条映射和六旅程保留。降低记录成本不降低高风险故障/原证据要求。
+- [来源基线](../../../product/record2-v1-decision-baseline.md)保存已确认产品/验收、批准与C1身份；Spec E01～E12及本Plan §11–12只保留调查/交付历史，不作当前Git状态或固定工具前置。
+- 外部C1没有已核实跨环境获取渠道；不依赖其源码时可按冻结方向重建，代码复用前仍需取得/许可/正式重验。文档可携带不等于C1可重取。
+
+统一切换按Policy §7执行：负责人另批最终采用Diff及明确Git路径/Commit/生效范围；实际提交对象可重建最小资料；产品与保护检查通过后一次记录。部分编辑/提交不分别生效，也不要求提交SHA写入自身。Git身份和执行授权由实际对话/日志/交付记录证明，不以本段预先宣称授权。
+
+本次治理切换不运行旧检查、不修改旧源码/Gradle/Schema/数据库，不自动启动A0/B0/F0、G3机器约束隔离或G5开发，不含Push/发布。G2/G4治理阶段与本Plan工作项G0/G1/G2的同名缩写含义不同，后者仍pending / NOT RUN。
diff --git a/docs/product/record2-v1-decision-baseline.md b/docs/product/record2-v1-decision-baseline.md
new file mode 100644
--- /dev/null
+++ b/docs/product/record2-v1-decision-baseline.md
@@ -0,0 +1,248 @@
+# Record 2.0 V1 已确认决定与验收来源基线
+
+**产品/验收来源摘录 — 执行授权不变**
+
+- 2026-10-23（Asia/Shanghai）；本文件保存已确认材料的最小自包含摘录。文档可携带性须由实际提交对象证明；外部C1源码可重取性另见§5。
+- 产品行为/验收仍由[统一架构R1](../architecture/record2-v1-unified-architecture.md)及[Spec R1](../specs/record2/v1/spec.md)拥有；[Plan](../specs/record2/v1/plan.md)拥有实施依赖与批准记录。本文件保存来源原义和取代关系，不另建一套任务/验收矩阵，不新增批准或执行授权。
+- 来源中的“待批准”“SD1获批后”等历史语态不能回退已确认范围；来源中旧Runtime/授权模式/阶段安排也不能覆盖最终R1。本文件与工程入口/Policy/Spec/Plan按Policy §7共同切换，不单独生效。
+
+## 1. 来源、批准与取代关系
+
+| 来源身份 | 已核实内容/批准事实 | 接纳边界 |
+|---|---|---|
+| 阶段1B产品交互稿，文件名record2-phase1b-product-interaction-20261022.md | D1–D5/Q6、原生页面行为、J1–J6、B/BK/SD；1C-A Spec状态记录2026-10-22用户正式确认1B设计，包含SD1；原1B文件SHA-256 `dc2818d661535afc4cabada118d909e91077507106a658f4ea29314560e9df84` | 只摘录功能/安全/验收；不复制线框图，也不以历史系统样式/优先级建议批准最终视觉 |
+| 阶段1C-A Spec/Plan/Verification | 实验完成，C1被负责人选定；保留精确提交；实验仅A验证，B/BK/SD未运行；Verification原文件SHA-256 `3a2a35836cd7e39a48bc1a31a015ef3bc9408033ed1a66d949fc82af5607716f` | 原稿早期in-progress/待选型是历史；选型不批准实验Schema/接口逐字迁入 |
+| 本地AI设计原稿，文件名record2-ai-agent-architecture-design-draft.md | AI-AC1～11逐项原文；SHA-256 `9c9aca2ec41eee9cc0f43a3d9e0c60e9b468bdfe6e35aa62312f61e62de09a84` | 原稿的Runtime对照、BYOK/会话备份待定被最终架构取代，不能据此缩减V1 |
+| 负责人提供的公开架构聊天 | 本次只读HTTP200，五份正文逐一匹配先前Spec记录的SHA-256；§6保留正文身份，§4提取所需验收 | 不复制完整聊天、工具命令、环境资料或隐藏推理；不依赖共享链接持续可用 |
+| 冻结统一架构R1 | commit `57e1b68b309b0459aed1765778b7da4cdfff6f6b`，SHA-256 `5de8670fc60a7226d5e30f409be62f35ded55da5d78353d4fb81bd035fe9cefa` | ARCH-01～06、Koog/BYOK、两AI模式、A/B、Room权威历史、完整V1与恢复不变量不重开 |
+| V1 Spec/Plan R1正式批准 | 2026-10-23，负责人原话：“Spec R1 和 Plan R1 均可正式批准；下一轮进入实施设计任务授权”。原批准保存在Plan §13 | 两文件approved，执行未启动；批准事实不等于设计任务、Schema/设备/API/发布许可 |
+| UI与治理补充 | 所有最终UI/布局/信息层级/图标/视觉/角色/动画及未确认交互归负责人；治理核心原则已确认 | 六设计组仍需分别批准；文档接纳和治理生效均不授权产品执行 |
+
+最终解释：完整V1联合交付原生与全部确认Agent；无AI时原生完整可用；只提供完整AI/无AI。取消CTX-11受限模式来自ARCH-04 R2；保留CTX-01～10。旧全禁网络、C1实验PASS、Record1.0旧领域/门禁都不能削减或替代R2验收。
+
+## 2. 阶段1B已确认产品事实摘录
+
+以下两表与行为条目取自1B；保留来源措辞以便核对。SD1的“待批准”条件已由后续1B确认与冻结R1、批准Spec满足。具体布局/组件形式不是本文件的批准事项。
+
+| ID | 已确认方案／事实 | 主要代价／边界 | 当前状态（来源原文） |
+|---|---|---|---|
+| D1 最低系统版本 | 首版 Android 14+（API 34），先本人日用验证 | 暂不覆盖 Android 10–13；本次用户选择确立产品门槛，不是从 AVD 推定。未授权修改旧工程 SDK 配置 | 已确认 |
+| D2 首次账户/分类 | 自动创建一个**可见、可改名的“日常账户”**，CNY，仅作为收支来源；不展示真实余额，不引入开账/转账。默认“未分类”，可跳过，提供少量收支分类 | 零前置配置，但预置名称未必合习惯；这里的“账户”不是在线账号。具体分类名称示例仍属设计细节：支出为餐饮/出行/购物/居家/其他支出，收入为工资/其他收入 | 行为已确认；已解决与 K3 的冲突，具体分类清单未单独批准 |
+| D3 关系数量/提交 | 跨日多对多；两侧同一套选择交互，批量勾选后点“完成”一次提交；取消不写 | 比即时切换多一步确认，但取消和失败含义明确；不引入通用实体关联 | 已确认 |
+| D4 备份首版 | P0 手动完整逻辑 ZIP、可移植、完整校验、隔离预检后整体恢复；首版不加密，导出前明确提示 | 文件可被读取；校验和不能防止恶意篡改。不套用 MoneyBook 的 PIN 派生方式；精确格式与恢复实现仍待后续冻结 | 产品边界已确认；不是格式或实现批准 |
+| D5 记账草稿 | 本地保留**一份**未提交的新建或编辑草稿，可跨进程继续；恢复时明确“继续／放弃”，不计入流水；笔记沿用自动保存 | 多一条恢复路径及测试；只有草稿持久化成功才能显示“草稿已保留”。具体写入时机和恢复细节仍属待审设计 | 本次已确认；不追溯为历史未回答题已获批准 |
+| Q6 真实旧数据 | 用户回复“没有”：没有必须继承的真实旧记录、附件或历史备份 | 无需把旧数据兼容或迁移列为本轮前置条件；不等于不存在任何测试文件，也不授权删除旧仓库、设备内容或历史备份 | 已回答，无旧数据继承要求 |
+
+- 金额直接转整数分，`58`/`58.00` 均为 `5800`；拒绝负数、零、超过两位小数、溢出、指数和分组分隔符。财务金额不经过 Float/Double；金额上限先以正 Long 分的可表示范围为技术边界，不捏造更低业务上限。
+- 收支方向、金额、账户、分类、说明和发生时间一次提交；失败保持旧版本。若显示汇总，只能叫“记录的收入/支出”，不能称真实账户余额。
+- 同一对关联只存在一次；多条新增/解除作为一次完整变更；失败不留半边或部分选中。新建/编辑/重试不得复制事实。
+- 建议发生时间用真实时刻表达；在当前设备时区按本地日期分组，日界为 `[当日零点, 次日零点)`，不假定每天 24 小时。更换时区允许历史分组变化，并在日期选择处标出当前时区；保留原地日历日期的需求若出现另议。
+- 新建在今天默认当前时刻；从历史日期入口新建，显示该日期和当前本地时刻供确认。允许补记；P0 暂不允许未来发生时间，避免混入提醒场景。遇 DST 不存在时刻提示重选，重复时刻显示并固定较早偏移；PoC 用显式时钟/时区夹具验证。
+- 时间线日期/时刻倒序，同一时刻以稳定 ID 排序；编辑发生时间才改变所属日期/位置。正文允许空格和换行，但全空白不能成为新 Note；不静默裁剪用户文本。
+
+### 原生页面行为（不含视觉线框与优先级建议）
+
+文本保存、失败保留、返回/IME、关联/分页/时区等行为必须可观察；触控/可访问性及避让要求保留。历史图形、默认字体/Material样式或具体表现不能替代负责人后续设计批准。
+
+### A. 生活时间线
+
+- **操作/跳转**：点日期打开系统日期选择器，跳到该日锚点后可继续向前后日期浏览；无记录的选定日显示空锚点和“查看更早记录”，不伪造条目。点“回到今天”显式重置锚点；类型筛选保持日期。记录卡进入交易详情或笔记阅读态；点记账到 B，记事到 C。
+- **空/加载/错误**：全库空提示“记录第一笔收支或一段文字”；筛选空提示“当天没有笔记/收支”并保留切换入口。首读显示占位与加载文案；更早分页只在尾部加载。首读失败不显示“暂无记录”；有旧数据时保留并标“未刷新”，重试失败页，不跳页或清空已有卡片。
+- **保存反馈**：A 不直接写事实。B 保存成功先进入交易详情，详情 Back 才回 A 的原锚点并刷新；新记录日期不在当前窗口时提示“已保存到 ×月×日［查看］”。C/关联提交成功后即时从持久化结果刷新；失败保留旧条目且不发成功提示。
+- **Back/重复/输入**：从详情返回保持日期、筛选、滚动位置；关闭日期弹层取消选择；A 的系统 Back 正常离开 App。重复进入创建有 D5 草稿时先询问继续/放弃，不覆盖草稿。后台回来不自动跳今天；冷启动无恢复上下文时回今天，有草稿则给恢复入口。
+- **真实持久化验证**：A-P1：同一天混排并能打开两类详情；A-P2：前一日/跨午夜/时区与同刻顺序、连续分页不漏不重；A-P3：编辑后不复制条目，详情返回上下文不变，重启后重读一致。
+
+### B. 快速记账（新建与编辑复用布局）
+
+- **操作/跳转**：账户选择只展示有效来源，首版一个账户时可在小弹层改名；分类为同页弹层，默认可不选。切换收入/支出保留金额/说明/发生时间，不兼容分类重置为未分类并提示。保存成功打开**该笔交易详情**，显示金额、来源、分类、说明、时间及“关联笔记”；详情 Back 回 A。编辑成功回原详情再返回原 A。
+- **空/加载/错误**：空金额时保存不可用；说明可空。无账户且初始化失败时显示“账户未准备好［重试］”，不暗建第二个账户。非法金额或未来时间就近解释；读取失败仅能重试/返回，不允许用默认值覆盖现存记录。
+- **保存成功/失败**：单次提交中显示“正在保存”，禁重复保存；真实事务提交后才进入详情/显示成功。失败保留所有字段、焦点和可用草稿，旧交易不变，显示原因和重试。若提交结果未知，先重读同一稳定记录身份确认，再决定是否重试，不盲目新建第二笔。
+- **Back/输入保留**：IME 打开时第一次 Back 只收键盘；表单未改直接返回。已改表单按 D5 提供“继续编辑／保留草稿并离开／放弃输入”；编辑草稿不改变已保存账目。保存中拦截 Back 等待结果，不把未结束写入叫取消。旋转/切到选择器保留输入。D5 草稿延迟约 500ms 写入，离开前补写；磁盘失败时不能显示“已保留”，明确提示并停留，只有用户显式放弃才退出。
+- **跨进程边界**：恢复同一草稿时带原交易身份；编辑旧版本已变化则提示重读与重新确认。草稿最新一次写入尚未成功即被终止时，只保证恢复最近确认的草稿；UI 不声称最后一个按键已落盘。成功交易与草稿消费保持一致，防止重启后又恢复成待新增的同一笔。
+- **真实持久化验证**：B-P1：`58.00 → 5800`，收入/支出、分类空值、正文不漂移；B-P2：双击、失败重试、提交后未跳转即强停均只一条；B-P3：编辑失败旧值不变；B-P4：已确认草稿跨进程恢复且不进入流水，初始化重复启动只一个默认账户/一套预置分类。
+
+### C. 笔记编辑（含阅读态）
+
+- **操作/跳转**：新建直接编辑；打开已有 Note 先阅读态，点“编辑”进入本页，阅读态展示完整正文及关联收支列表。正文停输约 500ms 自动保存，离开/点完成前立即补齐最新文本；“完成”只完成编辑，不代替已确认的自动保存。首次非空保存后即成为独立 Note，可进入 D 选择交易。
+- **空/加载/错误**：全空白新建不创建事实，Back 退出；已有 Note 正文清空时显示“正文不能为空，原记录尚未改动”，不隐式删除或覆盖成空串。可继续输入或明确恢复上次已存正文。加载已有 Note 失败不显示空编辑器；自动保存失败继续保留输入，状态显式为失败，可点重试。
+- **成功/失败/重复**：每个编辑会话使用同一个 Note 身份，输入变化按版本串行保存；较早保存回执不能覆盖较新文本或把较新未存内容标成已保存。只有屏幕当前版本持久化后才显示“已保存”。失败时可继续编辑并重试最新版本，不丢失败后的新输入。重复点完成不生成第二条 Note。
+- **Back/输入保留**：第一次 Back 可先收键盘；随后如已保存立即回来源。若尚在保存，等待最新版本结果；失败时留在编辑，提供“继续编辑/重试”，另有明确“放弃未保存修改并离开”（会丢失未落盘输入）的退出选项。已有已保存正文不因退出而删除。配置重建保留文本；进程终止仅保证已保存文本，尚未持久化的最后输入不作恢复承诺。
+- **关联前置**：首条非空正文成功保存且当前修改已落盘，才允许进入 D；未保存或失败先处理正文。进入 D 后返回保留正文、光标和滚动位置；关联提交与正文自动保存是不同操作，不能互相假报成功。
+- **真实持久化验证**：C-P1：中文/换行/Markdown 符号原样保存并可重新打开编辑；C-P2：连续输入与迟到回执、失败重试均只一条且保存最新版本；C-P3：空白新建无事实、清空旧正文不改库；C-P4：显示“已保存”后强停重启，正文和发生时间一致。
+
+### D. 关联选择（交易→笔记、笔记→交易）
+
+- **操作/跳转**：交易详情“关联笔记”进入此页；笔记阅读态点关联交易直接开交易详情，“管理关联”打开反向选择页。取消勾选即拟解除；完成才统一落盘。对端阅读可从候选的独立“查看”动作进入，返回保留未提交勾选；候选在此上下文只读，避免嵌套编辑和选择递归。
+- **空/加载/错误**：没有笔记提示“先记一段文字，再回来关联”；没有交易同理，返回到来源后创建，不在选择器里开复合新建流。筛选为空可清除筛选；首读加载不可提交，分页失败保留已选项并可重试。来源不存在或已变更需重读，不把空候选当作取消所有关联。
+- **成功/失败/重复**：未更改集合时完成只是返回，不写；有变化时一次提交全部增减，成功回来源显示最新数量和可跳转对象。失败保持原持久关系及当前勾选，提示重试。按“来源身份 + 目标集合”重复提交不会添加重复关系；关系端点在提交时验证存在。
+- **Back/输入保留**：无变化直接回；有变化询问“继续选择／放弃本次更改”，不自动提交；保存中拦截 Back 与重复完成，等待单一结果。旋转保留未提交选择；进程死亡只恢复已提交关系，本页明确标出“更改尚未保存”。解除某一关系不删除交易、笔记或其他关系，也不修改它们的发生时间。
+- **真实持久化验证**：D-P1：T1–N1、T1–N2、T2–N1 同时存在，允许跨日且双向读取一致；D-P2：取消/失败零变化，批量提交无部分关系；D-P3：解除 T1–N1 后两端均消失，T1–N2、T2–N1 和全部正文/金额保留，重启一致。
+
+### Note软删除、隐藏关系和竞态原文
+
+| 场景 | 最小用户交互与结果 |
+|---|---|
+| 删除入口 | 仅在已保存笔记的阅读态“更多 → 删除笔记”；确认文案“笔记将从时间线及关联列表隐藏，可在已删除笔记中恢复”，提供取消/删除。编辑中的用户先完成保存回阅读态；保存失败不带着未落盘文本进入删除 |
+| 删除成功 | 单次持久化把同一 Note 标为已删除并记录删除时刻；保留最后已保存正文、发生时间和全部原关系。成功后回 A 的原日期/筛选位置，提示“笔记已删除［撤销］”；短暂撤销只是恢复的快捷入口，入口过期不影响后续恢复 |
+| 恢复误删 | A 的次级“数据与备份 → 已删除笔记”，按删除时间倒序，只列笔记摘要/原日期/删除时间；点开是只读预览和“恢复”。成功后同一身份回到正常状态，提供“查看笔记”，按原发生时间回时间线，不因恢复置顶；没有已删除项时显示专用空状态 |
+| 时间线与关联 | 正常时间线、笔记筛选、交易详情的关联列表/计数、关联候选均隐藏已删除 Note。关系事实保留，恢复后原关联重新可见，交易和其他 Note 均不变；删除不是解除关联。正常状态不显示占位“幽灵笔记” |
+| 备份与恢复 | 完整逻辑备份包含正常及已删除 Note、正文、发生时间、删除状态/删除时刻，以及包括隐藏关系在内的全部关系。整体恢复后保持同样可见性；已删除 Note 只能从专用入口恢复，不能导入后自动复活或丢掉关联。这里的“恢复笔记”与“从备份整体恢复数据”是两种不同操作 |
+| 失败/重复/Back | 删除/恢复提交期间拦截 Back 与重复动作；真实提交后才提示成功。失败留在原阅读态或已删除预览，原状态/正文/关系不变，可重试；结果未知先重读同一身份，不执行状态切换式重试。重复删除已删除 Note 或重复恢复正常 Note 不改变正文、原时间或制造新身份 |
+| 自动保存竞态 | 删除前确认编辑保存已结束，删除后旧编辑会话/迟到回执不能把 Note 写回正常状态；已删除状态不允许正文编辑或新建关系。若旧页面仍在栈中，重读后显示“笔记已删除”，提供返回及去恢复入口，不显示空编辑器 |
+
+选择器只提交对**当前正常笔记**的关联增减，不能把因删除而不可见的旧关系当成取消勾选并抹掉。若目标在选择期间被删除，整次关联提交拒绝并提示重新加载，不部分提交、不隐式恢复笔记；其他隐藏关系保持原样。
+
+边界：仅笔记生命周期与一个次级列表/预览，不新增顶层页面、通用删除实体、跨类型回收站、批量清空、保留期限或后台清理；不物理删除正文或关系。取消删除不写；空白新建仍不产生 Note，清空已存正文仍是校验错误，不能借清空正文绕过明确删除操作。
+
+正式日用门槛必须验证 **SD-P1**：删除/撤销/恢复与重启，正文、身份、发生时间不变；**SD-P2**：多对多关系隐藏/恢复、选择器竞态和失败原子性；**SD-P3**：自动保存迟到回执不复活、重复操作和提交前后终止；**SD-P4**：BK1–BK5 覆盖删除状态及隐藏关系。SD1 未批准前不实施；批准后这些门槛不得遗漏。阶段 1C-A 仅评估设计可行性，不要求两候选实现 SD1。
+
+### 原生流程状态（来源原文）
+
+| 流程 | 状态与转移 | 不允许 |
+|---|---|---|
+| 读列表/详情 | 加载 → 内容/空；失败 → 重试；分页失败保留既有内容 | 把异常当空记录，或加载失败允许覆盖已有记录 |
+| 记账 | 初始/草稿 → 校验 → 保存中 → 详情；失败 → 带原输入重试；结果未知 → 重读确认 | 保存中取消、重复提交、未成功先更新正式流水 |
+| 笔记 | 空白 → 未保存 → 自动保存中 → 已保存；继续输入回未保存；失败 → 保留最新输入 → 重试 | 把旧回执当最新正文成功、失败清空编辑器 |
+| 关联 | 读取原集合 → 本地勾选 → 完成提交 → 来源；取消 → 来源原集合；失败 → 保留勾选 | 勾一次写一次后又声称取消、批量部分成功 |
+| 草稿恢复 | 冷启动发现已确认草稿 → 继续/放弃；继续还原字段，放弃经显式操作退出恢复 | 自动当正式记录、覆盖另一份草稿、恢复后重复新建已提交记录 |
+| 笔记删除/恢复（SD1 待批准） | 正常阅读态 → 删除确认 → 提交 → 已删除；取消回原阅读态；已删除只读预览 → 恢复提交 → 正常；失败保持原状态 | 清空正文当删除、旧自动保存复活、删除关系事实、重复动作切换两次状态 |
+
+## 3. 原生旅程和正式日用验收原文
+
+所有例子是合成验证夹具，不是个人数据。日期/时区是原实验夹具，不是正式应用固定时钟。
+
+| 步骤 | 用户操作 | 应可观察的真实结果 |
+|---|---|---|
+| J1 新增支出 | A → 记账，输入 `58.00`，日常账户，餐饮，说明“午饭”，12:30，点保存 | 交易详情显示 ¥58.00；独立重读磁盘为支出 `5800` 分；只有 T1 一条 |
+| J2 新增 Note | 回 A → 记事，输入“和朋友吃晚饭\n聊了最近的计划。 #原样文本”，20:10，等待“已保存”→完成 | N1 可独立重开，换行与符号原样；不是预览假数据 |
+| J3 建立关联 | T1 详情 → 关联笔记，勾 N1 →完成 | T1 找到 N1，N1 找到 T1；同一关系一条；两侧可进入对端 |
+| J4 同一天回看 | 回 A，选择 10/22，全部；再切收支/笔记并返回 | 两条同日混排，顺序正确；筛选不改日期，详情返回位置稳定。加前一天夹具验证连续浏览，不能只交单日截图 |
+| J5 编辑支出 | T1 详情 →编辑，金额改 `60.50`、说明“午饭补记” →保存 | 同一 T1 为 `6050` 分，旧 5800 不重复保留；N1 正文与关联不变；A/两侧详情刷新 |
+| J6 终止进程重启 | 所有状态确认已保存后，定向 force-stop 该候选包，再启动生产 Activity | 重读仍为 T1=6050、N1 原正文、一个双向关系；不能用重建 Activity 或重启 Composable 代替进程终止 |
+
+| ID | 完整验收标准 | 最少原始证据 |
+|---|---|---|
+| B-AC1 真实闭环 | J1–J6、收入、Note 编辑及全部适用 P0 用户路径通过，真实 Activity/磁盘 Room/重启结果一致 | 完整 UI 断言、磁盘重读、身份和计数；以最终版本重跑核心旅程 |
+| B-AC2 金额与原子性 | 5800→6050 精确；金额边界/溢出、非法输入、写失败均正确；保存中断/结果未知重试不重复，失败保持整笔旧值 | 金额边界/属性测试、真实故障矩阵、提交前后和回执前后终止的重启对比 |
+| B-AC3 编辑/返回/草稿 | A/B/C 页的全部返回与保留契约，旋转、IME、草稿、初始化幂等、加载/保存失败及自动保存迟到回执正确 | B-P1–B-P4、C-P1–C-P4 的状态/故障/落盘断言与重启证据 |
+| B-AC4 关系 | D 页全部多对多、跨日、跳转、取消、解除与批量失败原子性；无孤立/单边/重复关系 | D-P1–D-P3、真实故障前后集合和端点读取；SD1 获批时加隐藏关系竞态 |
+| B-AC5 时间线 | A 页全部同日混排/连续分页/日期筛选/返回；同刻稳定、日期编辑移动、午夜/时区/DST、分页边界无漏重 | A-P1–A-P3、固定时钟/时区与边界夹具；不借旧单日性能结果 |
+| B-AC6 备份安全 | §7.3 的 BK1–BK5 完整实现并自动验证，包括损坏拒绝、失败原库保护和中断恢复 | 实际包/清单/哈希、独立规范化状态对比、每个故障点的原始测试结果及重启观察 |
+| B-AC7 隐私与许可 | A 的离线/隐私/许可要求在最终构建仍成立，新增的备份/恢复入口与系统策略完整验证 | 最终源码/merged/APK 权限及规则、依赖许可、网络隔离真实流程 |
+| B-AC8 可复现与边界 | 完整日用门槛可重做，原 main/用户资料受保护，失败/未跑不得记 PASS | 最终版本、环境、命令/退出码、选择器、XML/runner 输出、保护检查与故障覆盖表 |
+| B-AC9 笔记生命周期（SD1 待批准） | SD-P1–SD-P4，含删除/恢复、旧自动保存竞态、隐藏关系、备份往返、失败与进程终止 | 相同 Note 身份/正文/原时刻/删除状态/全部关系对比，正常与已删除视图断言 |
+
+B-AC7的离线/隐私目标按最终R1解释：原生离线可用、系统备份排除、授权外发可核查；不是让有已确认AI的最终APK继续全禁网络。此解释已存在于获批Spec，非本文件削减原要求。
+
+### BK1～5隔离库备份实验
+
+格式产品边界为手动完整逻辑ZIP，保持稳定身份/所有正式字段/删除状态/隐藏关系；CSV不是完整备份，不复制活跃SQLite/WAL冒充逻辑包。精确格式和所有资源阈值在R2实施Plan的G0恢复设计中审核（不是治理审计G0），不能照搬旧Vault数值。
+
+| 实验 | 安排 | 必须观察 |
+|---|---|---|
+| BK1 自动往返 | 合成源库 S 包含支出/收入、未分类与已分类、中文/换行/特殊符号、跨日与多对多；SD1 获批后加入已删除 Note 及隐藏关系；导出备份包；导入另一个全新隔离库 R；重开/重启 R | S 与 R 的规范化事实、稳定身份、整数金额、正文、时间、全部关系、来源/分类逐字段一致；SD1 的删除状态/删除时刻一致，正常视图隐藏而专用入口可恢复、恢复后原关联可见。由独立观察器比较，A 页重建后混排一致 |
+| BK2 错误输入矩阵 | 分别制作截断 ZIP、字节/哈希或数量不符、缺失/多余数据项、重复 ID/关联、孤立端点、不支持版本、非法金额/时间、绝对/穿越/重复路径、超量条目/压缩炸弹夹具；SD1 获批后加入非法删除状态/时间 | 全部拒绝，无“跳过坏条继续成功”；有原库 O 的目标保持完整逻辑状态和受保护文件摘要不变，重启后仍可读；无源库或原目录写入。指向合法已删除 Note 的保留关系不能误判为孤立端点 |
+| BK3 导出失败 | 在数据读取、打包及 SAF 写入/关闭阶段注入失败或取消 | 不报告备份成功；不把半包列成可恢复备份，明确失败残留。完整包写完并回读校验才成功；不静默漏记录或附件 |
+| BK4 恢复失败/中断 | 先隔离预检再导入测试候选；在验证、导入、切换前后分别失败/终止；目标 O 带不同的哨兵记录，SD1 获批后同时包含正常与已删除笔记 | 不展示部分恢复；结果只能是完整 O 或完整已验证 S；报告成功时必须为 S。重启能确定唯一完整状态，删除状态与隐藏关系也不得混合；失败保留 O 或可验证恢复 O 的安全路径，不把混合库当成功 |
+| BK5 用户确认/取消 | SAF 取消不写；校验完成后显示候选记录数和“将整体替换此隔离库”，再确认；选择文件变化后重新验证 | 确认前 O 不变，取消零变化；正式恢复必须有可回退的旧库保护。不支持合并恢复，不接入真实旧包 |
+
+归档未加密时必须告知包含私人正文、已删除记录与AI对话；校验和不代表保密或真实性认证。错误输入还需明确并验证条目数/路径/编码/单项及累计解压量/压缩比/磁盘余量的边界与声明-实际不一致。原库比较使用静止隔离夹具，不能把正常SQLite检查点副作用当业务变化，也不能因此省略事实/重启验证。
+
+## 4. Agent及会话验收来源原文
+
+下文保留条款原义，无Plan任务列；正式映射仍只有Spec §7。TP来源ARCH-03 R1，CTX来源ARCH-04 R1并受R2两模式修订，SES-01～10来自ARCH-05原版，SES-11～16来自R1补充。
+
+### AI-AC1～11
+
+| ID | 情境 | 验收观察 |
+|---|---|---|
+| AI-AC1 | 普通用户不启用 AI / 网络断开 | 原有离线记账、笔记、关联不受影响 |
+| AI-AC2 | 自然语言记账并跳转 | 金额/分类/时间预填正确；确认前正式事实为零 |
+| AI-AC3 | 后续口语修改同一提案 | ID 不变、Revision 增加、旧金额不会重新出现 |
+| AI-AC4 | 收支+Note+关联 | 一次确认原子提交；部分失败不留孤立事实 |
+| AI-AC5 | 跨日查找并打开记录 | 结果可追溯真实 ID；类型、日期和关联正确 |
+| AI-AC6 | 本地草稿与 AI 提案同时存在 | 没有互相覆盖；用户能明确保留/舍弃 |
+| AI-AC7 | AI 笔记提案打开原生 UI | 确认前 Note 自动保存不触发 |
+| AI-AC8 | 迟到模型结果、两次点击、进程终止 | 旧结果不覆盖新提案；未知提交不会重复创建 |
+| AI-AC9 | 目标记录已变化或软删除 | 版本/可见性冲突阻断旧提案写入 |
+| AI-AC10 | 未授权内容、伪工具调用、Prompt Injection | 不外发越权记录，不执行未注册工具/写操作 |
+| AI-AC11 | 悬浮入口跨业务页与键盘场景 | 可见可关闭，不遮挡关键控件，返回上下文保留 |
+
+### TP-01～08
+
+- **TP-01 确认竞态：** 确认 Revision 3 与 Revision 4 修改并发，不能提交用户未确认的内容。
+- **TP-02 重复提案：** 同一 Run 中重复 `prepare_changes`、迟到修订和取消后工具结果不会创建重复或覆盖提案。
+- **TP-03 提交终止：** 冻结前、Pending 落盘后、业务事务中及业务事务成功后终止进程，验证事实与结果状态一致。
+- **TP-04 幂等重试：** 同一 Operation 重复触发仅产生一次业务结果。
+- **TP-05 关联并发：** 其他页面修改关联集合后，旧 AI 提案不能覆盖新的关系状态。
+- **TP-06 Note 并发：** 自动保存、AI 修改与软删除竞争时，旧正文和旧提案不能覆盖新状态。
+- **TP-07 失败回滚：** 业务写入中异常导致整体回滚，不能因错误处理吞掉异常而留下部分事实。
+- **TP-08 恢复边界：** 恢复 Pending、失效引用及数据库恢复代际变化，不得触发自动重复提交。
+
+### CTX-01～10
+
+| ID | 验收目标 |
+|---|---|
+| CTX-01 | 未授权或关闭远程 AI 时，不发送用户输入、历史会话及业务数据 |
+| CTX-02 | 完整模式可按需读取真实笔记全文、收支详情和关联信息 |
+| CTX-03 | 跨日期、跨记录、多轮查询不会被额外隐私弹窗不必要地中断 |
+| CTX-04 | 授权撤销后停止新的外发与工具结果回传，不虚称可以收回已发送数据 |
+| CTX-05 | 已软删除或不可见记录不会通过普通 Agent 工具泄露或复活 |
+| CTX-06 | 恶意笔记不能改变工具权限、Provider、正式提交或系统执行边界 |
+| CTX-07 | API Key、系统凭据和未授权诊断信息不进入模型上下文与日志 |
+| CTX-08 | 记录引用准确映射原生详情，导航时重新验证身份与可见性 |
+| CTX-09 | 长正文可分段读取；上下文和成本不足时明确报告，不能编造结论 |
+| CTX-10 | Provider 或实际数据接收方变化时，旧授权不得被静默复用 |
+
+ARCH-04 R2明确：仅完整AI/无AI，初始未启用，首次向实际Provider明确授权、持续有效可撤销；按主动任务读取正常可见全文/收支/关联，无额外任务级隐私弹窗，不默认整库上传、不后台扫描。CTX-11受限模式范围控制正式取消；工程分页/Token/步数/费用/超时/取消约束仍保留。
+
+### SES-01～16
+
+| ID | 验收目标 |
+|---|---|
+| SES-01 | 正常对话后终止并重启 App，原 Session、用户输入和回复完整保留 |
+| SES-02 | Koog 跨 Run 正确使用必要的历史上下文，不混入其他 Session |
+| SES-03 | 模型执行过程中终止进程，恢复为 Interrupted，不自动调用模型或工具 |
+| SES-04 | 同一 Session 的并发 Run、迟到结果不能覆盖当前有效状态 |
+| SES-05 | 关闭 AI 后不再发起模型请求，原生业务和已保留历史正常可用 |
+| SES-06 | API Key 不出现在 APK、普通数据库字段、日志、诊断文件和备份中 |
+| SES-07 | API Key 丢失、失效或 Provider 更换时，正确拒绝请求并要求重新配置或授权 |
+| SES-08 | 删除会话不删除正式收支、笔记及必要的 CommitOperation 幂等记录 |
+| SES-09 | 手动备份恢复聊天历史后，不自动重放 Pending Operation，也不自动恢复旧设备凭据 |
+| SES-10 | 聊天上下文超过 Token 预算时，Koog 使用有界历史处理，用户可见的历史不被静默删除 |
+| SES-11 | Koog 裁剪模型上下文后，Room 的完整用户可见历史仍在 |
+| SES-12 | 当前输入落盘、Koog 缓存写入、最终回复提交等故障点不会产生重复、幽灵回复或丢失权威消息 |
+| SES-13 | 删除含活动 Run、未确认 Proposal 和 Pending Operation 的 Session，不复活历史、不丢失提交核查依据 |
+| SES-14 | 跨设备 ZIP 恢复后聊天可阅读，旧 Pending 不可执行，API Key 和旧授权不会被静默继承 |
+| SES-15 | 真实 Koog + DeepSeek 连续多轮 Tool Calling 后，跨 Run 历史仍能正确使用，不因内部工具消息或 reasoning_content 协议处理错误而失败 |
+| SES-16 | 关闭 AI、取消 Run 或切换 Provider 后，迟到结果不能修改已经失效的会话与提案 |
+
+同Run模型内部Tool Call/Tool Result及必要thinking协议状态由Koog管理；跨Run用Room的已完成有效用户历史重建，不能将隐藏推理、未完成工具序列或Koog缓存变成第二份用户历史。六条联合旅程的完整定义仍见Spec §7.5，以上历史实验或模拟消息不能抵扣其真实Android/Room/Koog/DeepSeek证明。
+
+## 5. C1精确身份、取得步骤与限制
+
+- 技术基座已选定：精简Kotlin/Compose/Room；正式架构/工程组织仍按统一R1和A0审核，不冻结单模块/六表原型。
+- 当前可核实实验仓库：`D:/record2-experiments/phase1c-a-20261022/c1`；commit `4b4b18af2fd4f3ea0410e394952bdebd4911d679`；本次HEAD匹配、工作区干净，`git remote`为空。**没有已核实的跨机器remote/档案地址**，不得虚构下载命令或将D盘路径当可移植取得方式。
+- 获取步骤：A0先列实际拟接纳的Money/稳定ID/短事务或ContractDriver/DiskObserver及对应测试/fixture；向负责人取得该精确提交的受控源码/必要证据交付位置与许可说明；核对提交、选中路径/依赖与证据摘要；在正式工程重验后才接纳。获得交付/访问权限前不复制实验目录、Key/签名/SDK/缓存或私有测试库。
+- 需取得：选中源及其调用者/测试、金额与日期夹具/预期值、依赖与许可证清单、相应原始runner/SQL观察/host XML及身份/构建信息。实验自研/生成源码和第三方依赖分别审查，不能将C2的MIT许可套给C1全部内容。
+- 必要外部报告身份：比较报告SHA-256 `589561d3b648f26747e9bf351f59ef623f6f231478c7f4df677041e35e6decae`；C1可行性 `7ba1be2d3eb6073d2df4a02cff51d94b652a5cc1573e1f25f7ea9f87b3d0c4e1`；final2 suite `80607721fd2d6c5781be9ce1679146ec5a91983dd98a150a128ea4f2cab461da`。原始证据不属于C1源码提交的自动组成部分。
+- 历史结果：A-AC1～6通过（A-AC5只为有条件可行性），10个Android合同/2次宿主重启/3个host测试；正式B/BK/SD未跑，不包含完整Agent/正式备份/SD1实现。
+- 当前限制：跨环境取得与独立重放仍未核实；没有渠道时不接纳不可得代码，可按已冻结技术/行为独立重建，不重开C1选型。此文件使必要产品/验收含义自包含，不证明外部实验已经可重放。
+
+## 6. 来源指纹与非依赖关系
+
+| 来源正文 | SHA-256 |
+|---|---|
+| ARCH-03 R1 | `e44ec4857ee549f2a8f1e92923c90f6fdfb2000a4b4500a8de0d71be89e50cca` |
+| ARCH-04 R1 | `fa3fecd8306efed671eeb59174a68075f94ccd53a084811f73dac75f0906b473` |
+| ARCH-04 R2 | `b47030d74e727f6236be6d0dc28e66b339282df07182b8588cb7adc47e5fd64e` |
+| ARCH-05原版 | `a46d7bf2d50724fa249fe78c5d2bdbf53999570f3a12a54f02934611552749fe` |
+| ARCH-05 R1 | `5e6c5ca7625b48a922f353d4caf33eea19c52baf400c7b11ba65f417d5025290` |
+
+公开聊天链接只作溯源：`https://chatgpt.com/share/6ada413e-0ca0-83ee-b3c1-e92869b8b047`。正文哈希针对解码后UTF-8文本，不是动态HTML。原1B/1C-A/AI原稿保留原处；摘录未包含个人目录、完整聊天、工具输出或任何凭据。源文件接纳不把原稿过期语态带回正式合同。
+
+## 7. 最小可携带集合与验证
+
+集合为AGENTS.md、AGENTS.record1.md、README.md、docs/index.md、唯一R2 engineering contract、冻结统一架构、V1 Spec/Plan及本文件，共9份。冻结架构已在Git中且保持原样；其余8份按明确批准的路径逐项纳管，不全目录git add。产品批准见Plan §13，治理切换见Policy §7 / Plan §14；非权威审计/讨论报告不是实施必读前置。
+
+验证实际提交中的文件存在、UTF-8正文/链接、冻结架构身份、批准来源、全部验收与设计边界；保留提交身份和结果。只检查工作区不等于验证Git提交对象，文档可重建也不证明C1外部源可重取。Git纳管/Commit、生效与Push分别遵从实际授权，未满足统一门禁时不宣布G2/G4完成。
diff --git a/AGENTS.record1.md b/AGENTS.record1.md
new file mode 100644
--- /dev/null
+++ b/AGENTS.record1.md
@@ -0,0 +1,76 @@
+# Project operating contract
+
+## Product boundary
+
+- Native Android, minSdk 34, local-first.
+- Current scope is Tech PoC, followed by MVP.
+- V1/V2 ideas do not authorize code, schema, interfaces, or empty modules.
+
+## Required reading
+
+1. Read this file.
+2. Read docs/index.md.
+3. If an active spec exists, read the unique `in-progress` spec and only the core documents it references. For lifecycle work, read the target draft or approved spec instead.
+4. Read the research report only for evidence or license review.
+
+## Sources of truth
+
+- Product: docs/product/*
+- Architecture and data: docs/architecture/* and accepted ADRs
+- User experience: docs/experience/*
+- Engineering quality: docs/engineering/*
+- Stage order: [docs/roadmap.md](docs/roadmap.md)
+- Current active spec: follow the current pointer in [docs/index.md](docs/index.md); lifecycle and uniqueness rules are unchanged. Record 1.0 status: [MVP M2 — finance core](docs/specs/mvp/m2-finance-core/spec.md) is `approved` (2026-09-04) after delegated independent review; owning core documents are synchronized and [ADR 0017](docs/architecture/decisions/0017-m2-finance-schema-normalization-and-backup-v2.md) is accepted. M2 remains inactive until its plan is explicitly approved and schema/migration execution consent is recorded. [MVP M1 — app shell](docs/specs/mvp/m1-app-shell/spec.md) is `completed` (2026-09-04) with all 19 acceptance criteria PASS. Tech PoC P0–P6 are `completed`.
+- Current change when active: the unique `in-progress` spec under docs/specs/*
+
+## Non-negotiable data rules
+
+- Money uses integer minor units; Float and Double are forbidden for amounts.
+- Account balance must be reproducible from AccountEntry.
+- Financial aggregate writes are atomic.
+- Production destructive migration is forbidden.
+- A backup feature is incomplete until automated round-trip restore passes.
+- Timeline and SearchDocument are projections, never facts.
+
+## Change protocol
+
+- Update the owning core document before changing a stable decision.
+- Add an ADR when the decision affects architecture, persistence, compatibility, or recovery.
+- Resolve document conflicts explicitly before implementation.
+- `active` means the unique `in-progress` spec; an `approved` spec is not active, and zero active specs is valid.
+- A roadmap candidate authorizes only creation of a draft `spec.md`, not a plan or implementation work.
+- Do not implement until the user has explicitly approved both the spec and the plan and the approval date/context is recorded in plan metadata or the spec status record.
+- If a PoC technical assumption or selection conclusion fails, keep that slice `in-progress`, update the owning architecture facts, create the required evidence-bearing ADR, revise the same spec and plan, and obtain explicit user reapproval before retrying. A test-infrastructure failure is recorded and repaired without being treated as a PoC conclusion.
+
+## Engineering discipline
+
+- Feature modules do not depend on one another.
+- UI does not access DAO, Room Entity, or physical attachment paths.
+- Add Use Cases only for cross-aggregate atomicity, shared behavior, or central invariants.
+- Preserve user changes and unrelated worktree state.
+- Use apply_patch for text edits and run proportional verification after every change.
+- Agentic work splits, dispatches, and verifies per the [agentic execution workflow](docs/engineering/agentic-workflow.md); the main agent accepts only against raw evidence, and a subagent reports blockers without hiding or relabeling them.
+
+## Skills and Android verification
+
+- Load task-relevant vendored skills by exact `skills/<name>/SKILL.md` path; [skills/README.md](skills/README.md) owns upstream pins and local Junction reconstruction. Resolve same-name skills explicitly and name explicit-only skills in the authorized task contract.
+- Use the [Skills routing and adaptation](docs/engineering/agentic-workflow.md#skills-routing-and-adaptation) rules for design, planning, implementation, Android specialization, review, and retrospectives. Skills do not grant implementation approval or change the existing main-agent dispatch restriction.
+- Preserve the current composition root, Navigation 2, SDK targets, module graph, Room/Repository, startup gate, and recovery contracts unless an approved change explicitly revises them. Report conflicting skill instructions before proposing changes; a skill's presence does not authorize Hilt, new libraries, abstractions, modules, or migrations.
+- Default functional Android verification uses an isolated API 34 emulator; API 36 and physical-device runs supplement it per the [testing strategy](docs/engineering/testing-strategy.md#android-environments-and-capability-checks). Frozen spec and physical performance gates retain their approved environments.
+- Android CLI is the preferred Agent device tool, not a prerequisite for every emulator test. Check CLI operations, SDK/ADB/AVD access, Gradle instrumentation, and physical profiling separately; missing one capability blocks only work that requires it. Record actual commands, target identity, results, and raw evidence; new UI needs observable user behavior beyond compilation or isolated rendering.
+
+## User approval boundaries
+
+- Ask before deleting files, directories, or Git history.
+- Ask before modifying environment files, secrets, tokens, or CI/CD configuration.
+- Ask before database schema changes or data migrations.
+- Ask before publishing or deploying publicly.
+
+## Definition of done
+
+- Implementation work with an active spec must pass that unique `in-progress` spec's acceptance criteria.
+- Lifecycle or documentation work, and other authorized work performed while no spec is active, must pass its approved plan or documented acceptance criteria.
+- `none` active never authorizes functional implementation or bypasses the specification lifecycle; work that requires a spec cannot start or be declared done without the required active `in-progress` spec.
+- Required automated checks pass.
+- A verification record contains actual commands and results.
+- Affected core documents and ADRs are synchronized.
````

</details>

### 11.4 单独确认后才能执行

本节提交负责人审阅的具体对象是上列8路径及其完整Diff。下一步须明确批准该Diff、这8路径的选择性Git纳管/Commit以及共同门禁通过后的统一生效；未获批准不改现行入口。不得扩大为Push、G3、A0/B0/F0、工程创建或开发授权。

获准后先核对现有文件未漂移，再采用目标正文、检查暂存差异、提交，并只读核实实际提交中的9份必需资料、批准/产品/验收/设计边界与来源闭包；验证不通过只报告未完成并停止依赖动作，不自动Reset、删除或丢弃用户工作。全部通过后在交付记录中报告提交身份与统一生效结果，不制造提交SHA自引用。

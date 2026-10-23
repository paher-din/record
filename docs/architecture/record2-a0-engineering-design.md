# Record 2.0 A0 工程设计审查包

2026-10-23（Asia/Shanghai）。这是现有 V1 Plan 的 A0 设计产物；设计审查与布局修订由 [Plan §16](../specs/record2/v1/plan.md) 记录，后续受控目录重构/A1授权及实际结果见Plan §17。现行布局为单Git仓库、`record1/`与`record2/`两个独立Gradle工程；本文保留设计时的比较/限制，不以历史目录检查或NOT RUN描述替代当前执行状态。

## 1. 推荐与两路径比较

**采用 A：在现有 `D:/record/` Git仓库内，R1工程受控整理至 `record1/`，R2在 `record2/` 使用独立Gradle根、根名 `record2`、起步单 `:app`，按业务职责组织。** 两者不是同一个Gradle根下的子模块；不把R2加入旧settings，不引入旧根/composite，不绕过旧检查。移动只改变R1工程归属路径，保留源码、业务/数据语义和历史；实际移动/构建权限单列，不由布局批准推得。原两路径技术比较和其他R2决定保留，工期与实际兼容性仍未知。

比较口径：两条路线都交付 [冻结架构 R1](record2-v1-unified-architecture.md) 的完整原生业务、Koog/DeepSeek、会话/提案/A-B、完整备份及最终联合验收。G3 已确认 R2 不加入旧根，因此 **B 的可行比较范围是把经审查的旧模块/Repository/启动/UI代码抽取到新根后受控改造**，不是原地改旧根或在旧七模块图上叠加 R2。B 允许舍弃不适用模块，不要求为了比较而保全旧形状；但每项代码复用必须计入解绑、语义修正和正式重验。

| Plan §3.2维度 | A：以R2合同重建，选择性借鉴方法 | B：抽取旧资产后改造 | 判断、证据及未知 |
|---|---|---|---|
| 1 R1符合度 | 直接表达CNY正整数分、纯文本、无余额转账的业务 | 旧TransactionType/AccountEntry/EntityRef语义需移除或重写，不能保留成隐藏产品义务 | A减少双语义。§5 DTO/实体/金额源码；两条路线的完整验收成本都未实测 |
| 2 新业务模型 | 新建正文/发生时间/分类、业务CAS、Proposal/Operation/Session | 旧DTO没有body/occurredAt/memo/category/version；旧save方法是创建，关系替换无基线CAS，仍需正式改写 | 无证据表明旧方法能降低新编辑/并发/提案总成本；字段/约束由B0设计，不在A0画新表 |
| 3 技术债/耦合 | 不导入附件、旧Vault/启动/恢复链和机器门禁 | 旧Repository→Vault/附件/恢复；RecordApplication→RecordStartup→StartupEngine，解绑时还需保留可用错误/取消/恢复语义 | A避免旧回归面。§5及[旧启动源码](../../record1/core/data/src/main/java/com/paherdin/record/core/data/RecordStartup.kt)、[G3](../engineering/record2-g3-machine-isolation.md)；解绑工时未知 |
| 4 Room自由度 | 从完整V1设计独立库，不兼容R1 Schema/格式 | 可用新库避免迁旧数据，但旧DAO/八表代码并不因此成为适用模型，通常需替换或大改 | 两者均可隔离；B没有已证实的Schema复用优势。§5数据库/实体；正式B0/Schema许可仍必需 |
| 5 Koog集成 | 新composition root只装配必要服务/适配 | 旧AppContainer仅有clock/zone/scope/startup，未提供模型、Tools、Session或Proposal；引入后仍需接入完整AI链 | 均为新增工作；手工装配方法可借鉴，不需复制旧container。见[RecordApplication](../../record1/app/src/main/java/com/paherdin/record/RecordApplication.kt)、Spec§3；协议/费用未知 |
| 6 工具链 | 自有wrapper/catalog，选择并验证最小兼容组合 | 仍需独立根及Koog兼容性验证；旧AGP/Kotlin/Room/KSP与fixture配置联动又增加适配对象 | A不承担旧组合继承义务。G3与[旧版本目录](../../record1/gradle/libs.versions.toml)；本轮未将历史版本调查升级为新版本pin |
| 7 Application Services | UI/Tools共用业务Interface，存储实现集中拥有跨实体事务 | 旧Repository无正式编辑/CAS/会话/操作幂等，保留其外形要添新语义并拆旧职责 | A围绕真实调用者设计，不每表造Repository/UseCase；§4–5。实际方法数和物理实现随业务合同落实 |
| 8 原生/全局AI UI | 新Host按冻结行为接线，视觉/布局由F0 | 旧壳固定四tab/13路由，含Projects/Transfer/UnifiedSearch；正式内容默认Availability，并非R2页面闭环 | B可能省部分通用主题/日期接线，但需解绑且UI未批准；不作已接受资产。见[RecordShell](../../record1/app/src/main/java/com/paherdin/record/ui/RecordShell.kt)、[NavHost](../../record1/app/src/main/java/com/paherdin/record/navigation/RecordNavHost.kt)；F0工作两者都保留 |
| 9 Android测试/恢复 | 重建R2 fixture/oracle，借鉴事务和独立观察方法 | 旧fixture/API白名单/Schema/Benchmark绑定旧语义；大量适配后仍须正式重验，不能挪用PASS | A也要付出新测试及恢复实现成本。§5与G3；不按旧测试数量折算完成率，性能/硬件未知 |
| 10 总工作量/风险/维护 | 增加新根/最小脚手架，避免旧语义与保护回归的长期负担 | 可能减少部分基础装配，但增加Repository/DAO/导航/测试的抽取与修正、来源及旧资产回归 | 总工时区间 **未知**，没有声称A已被实测证明更快；现有证据未证明B完整V1总成本明显更低，按已批原则推荐A |

同口径成本：两条路线都要完成记录/查询/草稿、CAS/软删除/关联、Proposal/A-B、Koog/授权/会话、完整备份、六组最终UI与63条验收及六旅程。A 的额外成本是独立脚手架和新fixture；B 除同样的新根成本，还承担上述资产解绑/语义修正。当前不能给可信小时数或完工日期；不使用C1/C2实验历时外推完整V1。若后续有具体资产能净减少实现和维护成本，A仍允许逐项接纳，无需切换到“保留旧架构”。

## 2. 构建根、目录与工具归属

本轮 `Test-Path record2` 为 False，`git check-ignore` 未匹配三个候选源码/入口路径；候选名未见占用或被忽略，但A1写前仍需确认没有并行新增。目录树只是设计，不是已存在文件：

```text
D:/record/                         # 现有单Git仓库，未来不再是Android构建根
  record1/                        # R1完整工程，独立settings/wrapper/旧模块与检查
  docs/                           # 共用资料，按R1/R2来源路由
  shared/                         # 可选，仅真实跨项目复用获验证后创建；当前不创建
  AGENTS.md / README.md / .gitignore
  record2/                        # 独立R2构建根
    settings.gradle.kts           # rootProject.name=record2，只include(:app)
    build.gradle.kts
    gradle.properties
    gradlew / gradlew.bat
    gradle/libs.versions.toml
    gradle/wrapper/                # R2自有wrapper声明及标准入口
    app/build.gradle.kts
    app/src/main/AndroidManifest.xml
    app/src/main/kotlin/com/paherdin/record2/
      RecordApplication.kt        # composition root；显式装配，非通用DI框架
      MainActivity.kt
      records/                    # 金额/收支/Note/关联/草稿/查询的业务Interface与值
      proposals/                  # prepare/revise/确认/Operation核查的本地Interface与值
      sessions/                   # 权威历史、Run及Session的业务Interface与值
      backup/                     # 完整导出/预检/恢复的应用Interface
      storage/                    # Room及上述Interface的持久实现、事务/恢复
      ai/                         # Coordinator、Koog/Provider、Tools/ChatMemory适配
      credentials/                # 安全配置Interface及Keystore平台实现
      ui/                         # 页面、状态与强类型导航；细分由获批设计驱动
    app/src/test/kotlin/           # 纯逻辑/边界测试
    app/src/androidTest/kotlin/    # 后续真实Room/平台合同，隔离合成数据
```

包/子目录按实际任务创建，不先生成空类、每表Repository或一整套接口文件；初期不加buildSrc、convention-plugin、独立benchmark模块、服务端或通用测试框架。一个Gradle模块不意味着一个巨型Repository。Room导出Schema目录在B0/Schema授权后落实，不为A1编译伪造物理模型。

同仓库不等于共享业务底座：R2不依赖R1的`:core:data`、Repository、Schema或Vault。只有两代确实需要、语义一致且成本/测试证明收益的独立能力才考虑`shared/`；只对R2有用的代码留在`record2/`，不为目录对称提前建共享模块。`docs/`、skills和仓库级入口保留原位；不移动`.git`、不另建Git仓库、不批量搬历史文档。

R2 wrapper/settings/catalog/build输出独立；不用父级`apply(from)`、projectDir映射、includeBuild、旧testFixtures或旧build/classes/APK。`record2/.gradle`、`record2/build`、`record2/app/build`为新根自己的生成状态/输出，不复制或重定向旧工程输出。

允许复用来源清楚且兼容的JDK/Android SDK安装、正常Gradle依赖下载缓存；不要求新SDK副本或独占user home。Gradle官方区分[用户级依赖缓存、全局properties/init scripts与项目本地状态](https://docs.gradle.org/current/userguide/directory_layout.html)，因此共享下载缓存不等于接纳全局注入。A1记录实际来源并运行G3 V1–V8，不在A0安装/清理缓存或重配机器。工具版本、compile/target SDK及Koog/HTTP坐标在A1兼容验证中收口；**minSdk34保持冻结要求**，不能提高来绕过依赖问题。

## 3. 正式身份建议与兼容边界

| 身份 | 推荐值/规则 | 保护边界与最晚收口 |
|---|---|---|
| 构建根/模块 | `record2/`；root name `record2`；`:app` | 不接入R1根；A0结构审核后由A1创建。旧`:app`与新`:app`以构建根区分 |
| namespace/基础package | `com.paherdin.record2` | 不沿用旧`com.paherdin.record`或C1的`.poc.c1`；只是技术命名，不改变应用视觉品牌 |
| release applicationId | `com.paherdin.record2` | 与R1及实验包分离；A1核实际manifest/APK，未来安装前核已装包/签名。当前未查询设备或证明设备上无同名包 |
| debug / androidTest | `com.paherdin.record2.debug` / `com.paherdin.record2.debug.test` | 开发测试不覆盖正式R2/R1；不创建旧benchmark变体。未来如需benchmark另定专用身份，不提前设门禁 |
| Room私有库 | 建议basename `record2.db`，归R2应用私有空间；实际代际目录/打开协议由B0/G0定 | 新逻辑库，不打开旧`record.db`、旧vaults或按runId命名的C1库；basename建议不授权建库或指定物理Schema |
| 恢复代际 | R2自有数据代际身份；初始化/恢复时持久化新身份，不能直接沿用ZIP来源运行资格 | 生成/保存位置和与Run/Pending的绑定由B0/G0完成，A0不决定新字段/表 |
| 手动ZIP身份 | 建议独立格式标识 `record2.logical`，与旧P5/C1区分 | 不继承旧format-1/2；formatVersion、schema兼容、限额/资源布局由G0审核。app版本、数据库版本、ZIP版本分别管理，不用包名/文件名替代内容校验 |
| 凭据/授权 | 只在R2私有配置和Keystore命名空间内管理，恢复不继承Key/Provider授权 | E0确定alias/认证加密与存储位置；A1不生成密钥或写凭据，任何真实Key/环境配置另有具体授权 |

app版本号与签名策略不因独立身份而默认批准；A1可编译未签名release基础产物（若纳入交付），正式签名/发布另行处理。独立applicationId避免默认共享私有目录，但不证明外部SAF选择或恢复路径安全；这些由对应恢复/设备测试验证。

## 4. 业务Module、Interface与测试接缝

此处Module指包内职责，不是一项职责一个Gradle项目。业务Interface覆盖输入、版本/幂等前置、错误、取消及成功语义，不能只列函数签名。只在真实调用者/平台适配需要处设Interface，其余保持普通具体类/函数，不逐表加透传层。

| Module/调用者 | 对外Interface与实现归属 | 必须留在该处的规则/测试 |
|---|---|---|
| `records`，原生UI与Tools共用 | 收支/Note/关联/草稿、可见详情/有界检索/确定性汇总；值不依赖Koog；`storage`实现持久行为 | CNY整数分、正文原样、ID/发生时间/版本、增量关联/隐藏关系、软删除与自动保存CAS。测试穿过同一业务Interface；oracle独立，不读取生产汇总作为预期 |
| `proposals`，Tools prepare与原生确认分别调用 | 封闭六类prepare/revise；确认冻结、确定性提交和核查是本地独立入口，Tools不持有commit入口 | revision/创建幂等、A冻结身份与payload、B全部事实+Applied同事务、未知结果查同operationId。`storage`内共享同一正式事务实现，不把不同业务写拆成逐Repository提交 |
| `sessions`，Coordinator/ChatMemory/UI历史调用 | 权威用户历史、Session/Run状态与原子终态；`storage`持久实现 | 单Session有效Run、当前输入一次注入、Room历史不随窗口裁剪、Interrupted/取消/删除与迟到保护；删除会话不能破坏Operation核查 |
| `backup`，原生导出/恢复调用 | 有界一致快照、预检/候选确认、独占切换及失败恢复；`storage`实现物理协议 | 完整业务/软删除/隐藏关系/会话/必要幂等结果，排除Key/缓存；新代际旧Pending不可直接执行。B0/G0再定物理协议，不复用P5协议外形 |
| `ai`，全局助手状态和原生交接调用 | 轻量应用级Coordinator，Koog适配、固定Tools和ChatMemory适配；通过上面Interface接入 | 关闭面板/重建Activity不取消Run；关闭AI/取消/撤销/Provider变化使身份失效；模型无DAO/SQL/文件/系统命令或提交能力。Koog拥有执行循环，不自研Loop |
| `credentials`，原生配置与Provider认证路径调用 | 设置/清除/可用状态与短暂认证取密钥；平台实现封装Keystore | 明文不作为业务DTO、Tool Result、Prompt、日志或备份；状态/授权/AI开关分别管理。注入时钟/ID/IO及真实故障点按测试需要，不做通用注入容器 |
| `ui` / composition root | 只持业务Interface、Coordinator结果/导航回调；composition root例外可装配storage/平台实现 | UI/Tools禁止直接依赖Room/DAO/Entity/恢复文件；导航重新核真实身份/可见性。最终布局/层级/角色/动画/未确认交互仍由F0，A0不批准页面 |

建议新Host采用Navigation Compose的强类型路由技术，参数只承载稳定身份及必要上下文，不复制旧13路由/四tab或接受模型拼接路由。官方提供[基于可序列化类型的导航与参数恢复](https://developer.android.com/guide/navigation/design/type-safety)；具体依赖版本随A1验证，页面组合/可见入口随F0批准。技术选择不批准旧视觉或新增P1全局搜索/统计页面。

**单模块的真实限制：** Kotlin [`internal` 对同一编译module可见](https://kotlinlang.org/docs/visibility-modifiers.html)，没有按包自动限制DAO访问的效果。A1以面向业务Interface的测试、轻量源码依赖检查与审查约束UI/Tools→业务Interface、Koog仅在ai适配；检查涵盖import和全限定引用、豁免仅精确composition root及必要测试位置，不复制旧签名白名单。该静态检查不能声称编译器级别的完整隔离；高风险路径仍需真实合同测试。若出现反复绕过或明确独立编译/依赖收益，再提交最小模块提取方案，不能因假想未来先建七模块。

## 5. 资产与来源调查

本节只提供静态资产事实与取舍依据，不冻结工程结构、正式身份、Schema或资产接纳。后台资产调查只读取本仓库源码和既有权威材料；未访问仓库外C1、个人日志、用户数据、设备证据或网络，未运行构建、测试、脚本。比较口径来自[Plan §3.2–3.3](../specs/record2/v1/plan.md)，证据准入来自[Spec §2.4](../specs/record2/v1/spec.md#24-证据接纳与可携带性门禁)。

### R1资产：能参考什么、为什么不能直接沿用

| 资产与精确来源 | 静态事实、可保留的方法 | 不直接接纳与完整V1成本 |
|---|---|---|
| 金额边界：[FinanceMatrixValidator.kt](../../record1/core/data/src/main/java/com/paherdin/record/core/data/FinanceMatrixValidator.kt)、[FinanceMatrixValidatorTest.kt](../../record1/core/data/src/test/java/com/paherdin/record/core/data/FinanceMatrixValidatorTest.kt) | 验证器只比较整数符号，转账零和用BigInteger避免Long绕回；测试有0、±1、Long.MIN/MAX及多错优先级。可参考独立边界值、无浮点、扩大精度检查的方法。 | 验证器面向有符号AccountEntry、期初余额/转账和多币种账户。R2是INCOME/EXPENSE + 正数amountMinor/CNY，不能照搬“支出为负”或允许期初0的预期。本次在core:model/core:data生产源与finance生产源未找到可直接采用的Money文本解析/格式化组件；解析、精确汇总、格式往返及溢出仍需正式实现和测试。 |
| 金额随机历史：[BalanceReproducibilityPropertyTest.kt](../../record1/core/data/src/test/java/com/paherdin/record/core/data/BalanceReproducibilityPropertyTest.kt) | 固定随机种子、BigInteger和每步检查便于复现边界。 | 测试事实仍为旧余额/分录模型，不能证明R2金额输入或Room查询汇总。只借鉴生成/复现方法；正式oracle使用独立已知值或独立事实计算，不能直接把旧余额测试算作覆盖。 |
| DTO与物理字段：[Model.kt](../../record1/core/data/src/main/java/com/paherdin/record/core/data/Model.kt)、[Transaction.kt](../../record1/core/data/src/main/java/com/paherdin/record/core/data/room/Transaction.kt)、[Note.kt](../../record1/core/data/src/main/java/com/paherdin/record/core/data/room/Note.kt)、[AccountEntry.kt](../../record1/core/data/src/main/java/com/paherdin/record/core/data/room/AccountEntry.kt)、[TransactionType.kt](../../record1/core/model/src/main/java/com/paherdin/record/model/TransactionType.kt) | Transaction子表仅id/type；Note子表仅id；NewNote无body，NewTransaction的金额在entries，DTO没有R2发生时间、memo、分类及业务version。类型包含OPENING_BALANCE/TRANSFER。 | R2字段见[Spec §4](../specs/record2/v1/spec.md)：原正文、occurredAt、正整数分、账户/分类、版本及删除可见性都需新模型。复用旧DTO/实体需要持续转换与双语义，不能把改字段包装成既有业务能力。 |
| Repository事务：[RecordRepository.kt](../../record1/core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt) lines 592–738、[RecordDao.kt](../../record1/core/data/src/main/java/com/paherdin/record/core/data/room/RecordDao.kt) | 聚合写放在单个withTransaction；提交前做引用/财务校验，CancellationException重抛；提交后回执失败区别于事务失败。可保留短事务、错误分类与未知提交核查的方法。 | saveTransactionAggregate/saveNoteAggregate是创建，已有身份拒绝；没有expectedVersion/CAS，不能覆盖R2编辑、自动保存和Proposal提交。replaceTransactionNoteRelations先删除整套旧关系再插入，没有端点版本/可见快照条件；R2隐藏关系与并发增量不能沿用。Application Services仍需按正式事实设计。 |
| Repository依赖：[RecordRepository.kt](../../record1/core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt) lines 3–58、83–110；[RecordDatabase.kt](../../record1/core/data/src/main/java/com/paherdin/record/core/data/room/RecordDatabase.kt) | Repository依赖Android Context、Room、EntityRef/XOR、附件存储、VaultBinding/OperationGate/SessionRegistry、备份/恢复；现有Room v2共八实体。 | 直接导入Repository不是导入几条事务方法，会同时带入旧数据/启动/附件/恢复的耦合或要求拆除。新库自由度不能靠迁旧Schema或继承旧Vault获得；两路径都要新增Proposal/Operation/Session及正式恢复事实。 |
| 故障矩阵：[TransactionFaultHook.kt](../../record1/core/data/src/main/java/com/paherdin/record/core/data/TransactionFaultHook.kt)、[AtomicVisibilityTest.kt](../../record1/core/data/src/androidTest/java/com/paherdin/record/core/data/room/AtomicVisibilityTest.kt) | beforeTransaction、逐child、beforeCommit、afterCommitResponse四种观察点；预置完整old/new状态，独立连接观察事务前失败保持old、持久提交后回执失败保持new。可据此构造R2 all-or-none和未知提交测试。 | hook阶段/child ordinal和FullSnapshot绑定旧表。正式测试需加入业务version、Operation幂等、A/B状态、取消及宿主进程终止，历史方法不等于正式断言已成立。 |
| 独立磁盘观察：[TestSupport.kt](../../record1/core/data/src/androidTest/java/com/paherdin/record/core/data/room/TestSupport.kt) lines 14–115；[PersistReloadTest.kt](../../record1/core/data/src/androidTest/java/com/paherdin/record/core/data/room/PersistReloadTest.kt) | 独立Room连接读取；consistentSnapshot用显式BEGIN固定WAL快照。普通snapshot的多次查询只适合writer暂停/静止状态。PersistReload关闭后重开并对比公有读面与物理事实。 | 必须换成R2字段/表的独立观察器。旧close/reopen不是进程终止；PersistReload的resetProductionVault会删除record.db及固定attachments/migration/recovery/vaults路径，不能把该测试作为R2通用runner执行或整包迁入。 |
| 测试/依赖成本：[core:data构建声明](../../record1/core/data/build.gradle.kts) lines 40–57、[版本目录](../../record1/gradle/libs.versions.toml)、[FinanceUiModel.kt](../../record1/feature/finance/src/main/java/com/paherdin/record/feature/finance/FinanceUiModel.kt) | 现有JUnit4、AndroidX runner/ext-junit、Room/KSP可作为候选工具经验。core:data还接core:model、编译器/metadata及旧Room fixture；Finance当前是Availability，非真实业务编辑器。 | 不沿用整份core:data测试/依赖配置；按正式选中断言重新声明最少依赖。已有UI不减少R2原生业务/全局AI交接设计的必要工作。版本目录只是现状，本轮未证明Koog/HTTP/Room/Compose组合兼容，也未核查新组合许可。 |

据此可支撑Plan §3.2的模型符合度、技术债、Room自由度、Application Services、测试恢复和维护比较：受控改造并非只补正文和界面；它还需重做编辑/CAS/关系可见性并处理旧Repository耦合。干净重建同样需要全部业务/AI/备份及测试，不能声称已有精确工期或免费完成。Koog协议、最终版本组合和完整V1总工时均无本次实测依据。

### C1来源：精确身份与本次可证边界

本仓库的权威依据为[来源基线 §5](../product/record2-v1-decision-baseline.md#5-c1精确身份取得步骤与限制)、[Spec E05–E07及§2.2](../specs/record2/v1/spec.md)。以下是它们已记录的历史事实，不是本次访问外部仓库后重新核实。同机C1可直接核对本地提交/选中源码/依赖许可和对应测试；不要求新建remote或跨机器实验档案，未核对的代码仍不接纳。

- C1提交：`4b4b18af2fd4f3ea0410e394952bdebd4911d679`；历史分支`codex/record2-c1`，路径`D:/record2-experiments/phase1c-a-20261022/c1`。基线记录当时HEAD匹配、干净且无remote；尚无已核实跨机器remote/档案地址。本机路径可在对应接纳任务中直接核对，不由历史路径的存在描述推定当前已审查/可访问，也不把remote缺失作为个人项目的前置障碍。
- 选中源码候选在实验内的相对路径：`app/src/main/java/com/paherdin/record2/poc/c1/model/Money.kt`、`data/RecordRepository.kt`、`data/RecordDatabase.kt`、`ui/RecordViewModel.kt`（后三者使用相同`app/src/main/java/com/paherdin/record2/poc/c1/`前缀）。ContractDriver/DiskObserver及相应测试/fixture的精确路径没有在已读权威摘录中完整列出，须随受控交付清单取得，不猜文件名。
- 必要外部摘要：comparison报告SHA-256 `589561d3b648f26747e9bf351f59ef623f6f231478c7f4df677041e35e6decae`；feasibility `7ba1be2d3eb6073d2df4a02cff51d94b652a5cc1573e1f25f7ea9f87b3d0c4e1`；final2 suite `80607721fd2d6c5781be9ce1679146ec5a91983dd98a150a128ea4f2cab461da`。它们不因源码提交可得而自动可得，哈希也不能重建原文件。
- 历史A-AC1～6通过，其中A-AC5仅有条件可行性；10个Android合同、2次宿主重启、3个host测试；B/BK/SD未跑，未实现完整Agent、正式备份或SD1。不得换算成正式V1 PASS。

| C1候选 | 接纳前必须取得/核对 | 取舍建议与限制 |
|---|---|---|
| Money解析/格式化 | 精确提交中的Money、调用者、金额语法/边界/往返测试及预期值，依赖/许可 | 候选选择性参考；未取得源码不能声称已审完算法或直接接纳。正式host重验仍必需。 |
| 稳定recordId、财务写与草稿消费同事务 | Repository/ViewModel/数据库相关选中源、对应runner与磁盘观察、日期/ID夹具 | 保留提交前持久化身份、短事务方法；重接正式CAS、Operation身份、错误、时钟/日期注入，移除run绑定/固定实验路径，不能复制实验Schema作为正式模型。 |
| ContractDriver/DiskObserver、故障测试 | 精确文件路径、测试/fixture、host XML/runner/SQL观察及环境/身份信息 | 候选方法接纳；以正式库重跑，独立oracle不能依赖被测生产汇总代码。原始证据缺失时，相应复用结论标未核实。 |
| Note、关系、时间线 | 依据[Spec §2.2](../specs/record2/v1/spec.md)的既有审查结论 | 不逐字接纳：saveNote无expectedVersion/删除条件；显示revision不能防数据库旧写；关系无基线CAS且局部selected可能误删隐藏关系；扩大LIMIT/固定时钟不证明连续分页、DST和并发正确。 |
| 单模块/六表、实验UI、备份/Agent | 冻结产品合同而非原型形状 | 实验结构不冻结正式组织；UI未获最终设计批准；缺失的B/BK/SD/AI能力必须正式实现。C2 MIT许可不能套给C1，实验自研/生成源与每项第三方依赖分开核对。 |

### 对§3.3证据接纳清单的输入

1. 仓库内产品/验收采用[自包含来源基线](../product/record2-v1-decision-baseline.md)与冻结Spec；E01～E12为历史身份目录，不重新将旧workflow、个人Downloads或共享聊天设成实施前置。
2. R1本地源码/测试目前只作为设计证据和方法候选；后续若复制具体文件，记录源提交/文件摘要、选中调用者、许可/敏感性、改造点、批准来源和正式重验任务。当前源码内容与其历史PASS分别处理。
3. C1待受控取得项仅限上表实际候选及必要证据；取得位置、精确选中路径、源码/报告身份、许可/敏感性、批准来源、核实结果和正式重验映射一项不缺。不得整包复制缓存、APK、签名、Key、私有库、日志或机器配置。
4. 本次没有访问/接纳任何C1源文件或原始runner。同机可读的候选可直接本地审查，不因缺少跨环境渠道拒绝；确实不可取得或尚未核对的代码才保持未接纳，按已冻结行为独立重建和验证，不重开C1选型。审查后真正接纳的代码进入正式R2仓库维护，历史PASS不抵扣新工程验证。

未检查外部C1当前状态、原始证据重放、新工具链兼容性和新依赖许可；本节没有构建/测试PASS或最终工程决定。

## 6. 证据接纳、历史保护与文档归属

| 来源/Spec证据ID | 本次如何使用 | 代码/实证接纳状态与后续动作 |
|---|---|---|
| 当前根规则/Policy；E01旧规则历史 | 当前R2方法由AGENTS/Policy和任务授权拥有；旧workflow/testing只作带R1作用域的调查源 | 不把当前已有脏文档或旧技能流程当作新产品门禁，不改它们 |
| E02 冻结统一R1 | 完整产品架构约束；文件身份沿Spec元数据，保持原样 | 已批准方向继续生效；本轮不是架构重选或复跑产品验收 |
| E03/E04阶段1B/1C-A；E11/E12 AI修订 | 正式所需行为/批准关系采用[自包含来源基线](../product/record2-v1-decision-baseline.md)与V1 Spec；历史原稿仅溯源 | 不把未跟踪原稿、Downloads或共享聊天变成A1依赖；不得把SD1/AI/63条验收漏掉或降低 |
| E05/E06/E07外部报告/C1源码/runner | §5列身份和候选，承接已记录的实验结论及限制 | **源码/原始证据未重新取得，未接纳**。如要复用，先取得精确提交/选中路径/调用者/测试/fixture/许可与必要受控原证据再验证；默认可按冻结行为独立实现，不阻A1创建新根 |
| E08/E09当前旧源码/Schema/构建；G3 | 源码差距、成本/耦合与隔离输入，逐项引用实际文件 | 只读设计证据，不等于功能接纳。§5的方法候选也无正式PASS；复制任何具体源前补来源、许可、改造点和新工程验证 |
| E10旧架构/数据/工程/测试/roadmap/ADR | 保留历史与对照；旧0011七模块、0016启动等决定不约束R2 | 不改写旧Accepted/PASS，不把旧数据、API或导航协议升格为R2合同 |
| 本报告查阅的官方文档 | 只核对Kotlin可见性、Gradle目录/缓存及Navigation强类型机制 | 是设计技术依据，不证明本机版本组合或新APK已兼容；未安装/执行对应工具 |

原始R1代码、测试、Schema、数据、Git历史、用户修改及所有未跟踪资料保留。A0调查时未移动旧目录；后续仅按Plan §17的具体授权整理至record1，不迁数据库、不把实验工程覆盖旧源，不在新应用启动时探测/导入旧数据。资料与源码链接在真实移动时同步，历史命令/路径和PASS保留当时语义；没有Git暂存/Commit/Push。

文档边界：冻结统一R1/Spec仍拥有行为，Plan拥有授权/任务，Policy拥有方法，G3拥有旧机器约束事实与清单；本报告拥有A0工程推荐与取舍。结构审核通过后，长期构建/身份决定按Policy落入必要的R2 ADR，届时引用本报告并记录真实批准，不复制一套Spec/Plan；本轮不预写Accepted ADR、不改旧ADR，也不因此增加一次G3审查。

## 7. 交给A1的具体实施范围建议

以下是**待审核的下一任务范围，不是已获执行许可**。A1先核A0结构批准、精确写入与构建/缓存/依赖对象授权；B0/Schema许可、设备/包安装、Provider费用/Key、CI和发布不从A0批准推得。

| A1可申请的写入/验证范围 | 具体边界和交付 |
|---|---|
| 新根构建文件 | `record2/settings.gradle.kts`、`build.gradle.kts`、项目`gradle.properties`、`gradle/libs.versions.toml`、`gradlew`/`gradlew.bat`、`gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}`、`app/build.gradle.kts`。只生成/声明新根，不改父级对应文件或添加旧project依赖 |
| 最小应用/技术编译接缝 | `record2/app/src/main/AndroidManifest.xml`，`.../kotlin/com/paherdin/record2/{RecordApplication.kt,MainActivity.kt}`及`records`金额值/解析、必要的`ai` Koog/Tools/ChatMemory编译适配和对应`src/test`/`src/androidTest`编译合同。只创建A1所需文件，不预生成§2全部目录，不实现未批产品UI，不以技术Host替代F0；不得用生产fake或吞错模拟功能完成 |
| 依赖与业务Interface检查 | 在新根构建脚本放最小的R2来源/禁依赖检查并接入相关check，覆盖§4实际Interface；最小host金额边界/往返、compile-only Android技术接缝。选最小必要Koog/HTTP等依赖，记录解析来源/许可和版本；不用旧compiler/apiGuard/签名文件，只为未知未来加框架不在范围 |
| 生成状态/构建产物 | 仅新根及app自己的`.gradle`/`build`等正常生成路径，加获批共享缓存/SDK/JDK使用；禁止指向旧build目录。需要安装/升级SDK、修改`local.properties`/环境/签名或清理删除时按具体对象另核授权，不顺手改机器 |
| 文档同步 | 当前Plan的A1命令/结果/实际变体及G3 V1–V8、实际结构/依赖清单、必要R2 ADR/README入口说明；旧R1文档和检查默认只读。新源码纳管/Commit按具体授权，不全仓暂存 |
| 明确不含 | Room实体/DAO/Schema JSON/SQL、数据库初始化/迁移/恢复、真实Key/Keystore配置、设备安装或清包、模型API/付费调用、最终UI、CI/发布与旧资产删除。Room runtime/KSP依赖兼容可先核，正式生成Schema必须等B0和单独许可，不能造临时Schema绕过 |

建议初始构建配置有debug及未签名release基础变体，不加flavor/benchmark；实际交付矩阵由A1合同列明。先列每个存在且本次交付的variant及app/test制品，再检查任务链、依赖/输入、Manifest/权限/APK和运行获批host测试/assemble/check。没有纳入本次交付或需要后续产品实现的最终发布配置记录 **NOT RUN + 条件 + owner**，不计成PASS，也不把最终V1验收提前倒挂到A1。G3 V1–V8原清单及两项执行解释直接复用，不再设计新验证框架。

A1不默认开库/装设备/请求DeepSeek；构建兼容与静态边界成功不能替代D0/D1/E0/E1/E2/G1/G2的平台、协议、数据和联合证据。B0/F0仍按各自授权启动；缺C1原件仅阻拟复用资产的接纳，不阻独立重建。遇构建失败保留根因，不做metadata skip、manifest强制覆盖、强制旧stdlib或关闭检查来换PASS。

## 8. 审核包完整性、待决定项与本轮验证

| Plan §3.3的8项交付 | 本报告落点 |
|---|---|
| 两路径实际比较与推荐 | §1十维度，明确共同工作/额外改造面、未知工时与推荐依据 |
| 工程结构 | §2根/模块/目录/工具归属，§4单模块代价与补偿；未创建文件树 |
| 正式身份 | §3应用/测试/库/代际/ZIP身份建议与后续owner |
| C1选择性接纳 | §5候选/拒绝项、精确身份/缺口/取得条件，§6 E05–07状态 |
| 值得复用R1资产 | §5金额边界、事务/故障与独立磁盘方法，明确不直接接纳代码 |
| 不进入R2的旧资产 | §1/5/6旧领域/Schema/Repository/Vault/导航/UI/门禁及原因 |
| 历史与证据保护 | §5/6来源接纳和只读范围，§7A1输出/外部执行边界 |
| 负责人A0后批准 | 本节三项具体决定及§7下一任务范围 |

以下保留提交时的三项审核对象，现行裁决见Plan §16；无需重审A0，A1执行范围仍须明确授权：

1. 接受/调整方案A及`record2/`独立根、单`:app`、业务包/Interface和Navigation Compose技术建议；不据此批准最终UI。
2. 接受/调整§3推荐applicationId/namespace/debug/test、私有库basename/ZIP格式标识及B0/G0的收口边界；不是Schema或数据迁移许可。
3. 接受/调整§5–6“默认独立实现、方法选择性借鉴、外部C1未核实不接纳”的取舍与必要文档同步范围，再按§7明确A1写入/构建对象。审核结构不自动批准设备/数据库/Key/API/CI/发布。

本轮T0实际结果：Python内联断言 **PASS**，检查93个本地链接/锚点、十维度/八交付、G3/A0待批与NOT RUN语义、Spec/Plan/router一致、冻结Spec产品正文和统一架构未改、旧工程受保护路径无新增diff、两份已有脏文档SHA-256不变、三个原有未跟踪目录保留、`record2/`未创建；`git diff --check`退出0（仅现有换行转换提示）。另只读核对3份官方技术文档，未据此宣称工具链兼容。本报告的资产调查由后台代理提供，主Agent核对并整合，最后为文档自查，不是独立运行验收。

没有运行Gradle/host或Android测试、设备/数据库/API/DeepSeek、外部C1取得/重放或新依赖许可核验；全部对应结果保持NOT RUN/未核实。上述T0是A0交付时的历史回执；后续设计裁决与布局修订见Plan §16，不能因设计通过自动移动工程或进入A1。

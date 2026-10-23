# G3：旧工程机器约束静态审计与隔离方案

2026-10-23（Asia/Shanghai）。审计基线：`ba6472ef6f89f90629b4892e427e80a20b98a838` 加本轮开始时的工作区。任务授权及两部分验收状态只记录在 [V1 Plan §15](../specs/record2/v1/plan.md)。本文是该任务的调查与检查清单，不是新 Spec、Plan 或治理阶段。

本文保留G3静态提交时的事实与命令示例，文中旧工程相对路径均相对于当时的R1构建根；源码链接已跟随受控搬迁指向record1。当前双工程布局、A1实际验证结果及未覆盖范围以Plan §16–17为准，不将历史“目录不存在/NOT RUN”当作当前状态。

## 结论与范围

旧约束有明确的构建根、模块、源码和制品入口；目前未发现旧 Gradle 自动调用 M1 制品脚本的接线。真正的风险是把 R2 加入旧根、引入旧模块/夹具，或让工具继续执行旧命令。仅修改文字适用范围不能证明运行时隔离。

建议 A0 优先评估独立构建根：R1 原样保留，R2 自有构建配置和验证入口，不连接旧工程的项目、任务、源码、资源或输出。`record2/` 只是候选，本轮确认该路径尚不存在；目录、模块数、应用身份和版本仍由 A0 审核决定。若 A0 选择受控改造，必须另给可证明相同隔离结果的方案和旧文件写范围，不能把本报告当作修改旧 Gradle 的授权。

本轮只读旧源码/构建/测试/脚本与相关规范，只写本报告及其 Plan/路由入口；不运行 Gradle、M1 脚本、设备测试或网络实验，不读真实库、Key、个人正文或历史设备原始证据。未创建 Android 工程，未接纳旧资产，未改变产品验收。

## 1. 已定位的约束与触发链

以下都是仓库源码静态事实；任务能否配置/运行及最终依赖图尚未实测。

| 检查面 | 文件与实际入口 | 约束和误用后果 | 隔离措施 |
|---|---|---|---|
| Gradle 项目图 | [settings.gradle.kts](../../record1/settings.gradle.kts) `include`；[build.gradle.kts](../../record1/build.gradle.kts) `productionModules` / `productionEdges` / `testToolAllowlist` | 固定 `:app`、`:feature:finance`、`:feature:notes`、`:feature:timeline`、`:core:model`、`:core:data`、`:core:ui` 七生产模块，另允许 `:benchmark`；它只能通过 `targetProjectPath` 指向 `:app`。把 R2 作为带构建脚本的新子项目加入旧根，会在该检查执行时破坏精确集合 | 不向旧 settings 添加 R2；新根不 `include` / `includeBuild` 旧根，不通过 `projectDir` 重新映射旧项目 |
| 根验证任务 | [build.gradle.kts](../../record1/build.gradle.kts) L138–199，根 `:check → :checkModuleDependencies` | `checkModuleDependencies` 检查模块集合/声明配置中的项目边，并扫描 `benchmark/src/main`、`app/src/benchmark` 的刷新率写操作和 `$targetPackage/.MainActivity`。扫描根是这两个固定目录，不是整个 Git 仓库。源码没有将此任务挂到每个 assemble 上 | R2 不继承该 build script 或它的任务；不能靠 `-x checkModuleDependencies` 作为隔离证据。旧任务继续保护 R1 |
| API consumer 编译门禁 | [core/data/build.gradle.kts](../../record1/core/data/build.gradle.kts) L73–144、L196–198；[apiGuard](../../record1/core/data/apiGuard) | `:core:data:check → apiBoundaryCheck → compileDebugKotlin`；分别编译 positive/negative fixtures，使用旧 classes、`debugCompileClasspath`、SDK36 和独立 K2 编译器。它不是全仓源码扫描，却会随旧 data 模块进入验证 | 新根不依赖/复制旧 data 模块和 apiGuard，不加载旧 build script；新 Application Services 的边界按 R2 契约验证 |
| API 签名白名单 | [ApiSurfaceTest.kt](../../record1/core/data/src/test/java/com/paherdin/record/core/data/ApiSurfaceTest.kt) L24–35、L114–157、L215–239；[expected-surface.txt](../../record1/core/data/api/expected-surface.txt) | 旧 data 单元测试从包含 `RecordDatabase.class` 的 classpath 元素枚举 `com/paherdin/record/core/data`，校验 internal 类及公开签名双向精确匹配；固定 Room 生成类豁免。不是 `apiBoundaryCheck` 本身的断言 | 不复制测试、签名文件或旧编译产物；不为 R2 扩大旧豁免/更新旧签名。新 API 可复用验证方法，不能继承旧类型合同 |
| M1 自动化入口 | [tools/verify-m1-artifacts.ps1](../../record1/tools/verify-m1-artifacts.ps1) `-ProjectRoot`、L303–332 | 调用方传入根；只先判断根下有 `app/`，再读该根的 `local.properties` 和 SDK `aapt2` 36.0.0。没有 R1/R2 身份分派；若未来 R2 也有 `app/`，传错根不能靠此初检挡住。仓库可见 Gradle/脚本没有调用它的接线，文档/人工调用仍可能触发 | 将其保留为 R1 专用；R2 命令清单不调用它，也不把 `-ProjectRoot` 改成新根；不将脚本改成通用 R2 verifier |
| M1 联网/依赖冻结 | 同一脚本 L69、L133–218、`Test-NetworkPermissionsInText`、families 1/9 | 拒绝匹配 `INTERNET\|NETWORK\|WIFI\|SOCKET\|HTTP` 的权限和任何 `networkSecurityConfig`；依赖目录固定且未知 alias/version 失败，还拒绝 OkHttp/Retrofit 等 token。会错误拒绝正常 R2 AI 联网与依赖选择 | 不复用这些旧断言；R2 按已批 Spec 的授权联网和最小依赖验证。不是取消隐私检查，也不是提前冻结某个 HTTP 引擎 |
| M1 身份/资源/制品冻结 | 同一脚本 `ExpectedPackages`、`ExpectedExported`、`ApkRelPaths`、families 2–8 | 固定包名、导出组件表、5 个 APK 路径、merged/packaged manifest 路径、`allowBackup=false`、九域排除、A/A1 图标/主题/颜色及 release 禁夹具/tracker token。不能只隔离 family 1/9，留下其余族当作 R2 门禁 | R2 自有包/组件/制品预期；安全目的逐项映射到 R2，旧视觉和资源值不继承；具体备份策略仍按冻结合同及后续设计验证 |
| 工具链版本 | [gradle/libs.versions.toml](../../record1/gradle/libs.versions.toml)、[wrapper properties](../../record1/gradle/wrapper/gradle-wrapper.properties)、[gradle.properties](../../record1/gradle.properties) | 旧 wrapper 9.5.0、AGP9.3.0、Kotlin2.2.10、Compose BOM2026.06.00、Room2.8.4/KSP2.3.9 及旧内存参数是旧构建声明，不是 R2 兼容性证明 | A0/A1 审核 R2 组合、wrapper/catalog/properties 与实际解析来源；不链接父级 catalog、套用旧冻结版本或重用旧 build 输出 |

M1 九族的完整清单是网络权限、导出组件、包身份、系统备份开关、九域备份排除、图标/启动主题、release 无测试夹具、tracking SDK 字符串、依赖版本。它们混合了历史产品值与仍有价值的安全目标，不能整份搬走，也不能整份关闭后宣称安全通过。

## 2. 测试、Manifest 与资源

| 旧入口/资产 | 已查到的绑定 | R2 处置与后续证明 |
|---|---|---|
| [core/data 单元测试](../../record1/core/data/src/test)、[SchemaContractTest](../../record1/core/data/src/test/java/com/paherdin/record/core/data/SchemaContractTest.kt)、[SchemaV2ContractTest](../../record1/core/data/src/test/java/com/paherdin/record/core/data/SchemaV2ContractTest.kt)、[schemas](../../record1/core/data/schemas) | 固定旧数据库 FQCN、v1 六表/v2 八表、EntityRef/AccountEntry 等语义；金融/附件/搜索/恢复测试也绑定旧 API | 原文件与历史 PASS 保留，默认不纳入 R2 source set 或门禁；金额 oracle/原子性/故障方法只有经接纳、适配并在 R2 重跑后才成为新证据 |
| [core/data Android 测试](../../record1/core/data/src/androidTest)、[MigrationRecoveryTest](../../record1/core/data/src/androidTest/java/com/paherdin/record/core/data/room/MigrationRecoveryTest.kt)、[LegacyGenerationBootstrapTest](../../record1/core/data/src/androidTest/java/com/paherdin/record/core/data/room/LegacyGenerationBootstrapTest.kt) | 固定旧 v1→v2、`record.db`、recovery/attachments/vaults；部分 setup 会删除测试进程可见的数据库/目录 | 不调用旧 runner、不将它指向新包或用户库；本轮只读。R2 的 Schema、初始化/恢复和真设备测试分别授权，不把“不跑旧迁移测试”解释成无需验证 R2 数据安全 |
| [core/data/build.gradle.kts](../../record1/core/data/build.gradle.kts) `emitP6Oracle` / `validateP6BenchmarkReports` / `validateM1StartupReports` | JavaExec 依赖旧 unit-test/fixture 编译；分别输出旧 P6 oracle，或校验冻结的 P6/M1 报告。源码未把三个任务接到 `check`；其中两个报告任务本身不操作设备，仍会触发编译前置 | 不纳入 R2 任务图或验收，不重算/改写旧证据；R2 性能预算和报告格式按新任务需要决定 |
| [benchmark/build.gradle.kts](../../record1/benchmark/build.gradle.kts)、[M1StartupHarnessGates](../../record1/benchmark/src/main/kotlin/com/paherdin/record/benchmark/M1StartupHarnessGates.kt)、[M1StartupBenchmark](../../record1/benchmark/src/main/kotlin/com/paherdin/record/benchmark/M1StartupBenchmark.kt)、[TimelineBenchmark](../../record1/benchmark/src/main/kotlin/com/paherdin/record/benchmark/TimelineBenchmark.kt) | `com.android.test` 指向旧 `:app`；M1 固定 `com.paherdin.record.benchmark` / `.benchmark.test`、Activity、variant marker、数据目录和命令白名单；实际 benchmark 包含 fixture 重建及清包动作 | 不把旧 benchmark 模块、包白名单、TTID/TTFD/P6 fixture/阈值作为 R2 门禁；不运行旧 harness 来“看看 R2”。未来设备操作必须检查新包/数据身份 |
| [app/build.gradle.kts](../../record1/app/build.gradle.kts) L55、L69；[testFixtures](../../record1/core/data/src/testFixtures)、[benchmark source set](../../record1/core/data/src/benchmark) | app 单元测试和 benchmark variant 都消费旧 data testFixtures；只隔离 `:benchmark` 模块仍不足以切断旧 fixture | 新根不消费这些 fixture/project edge，不把旧测试目录加入 `java`/`kotlin`/`assets`；检查所有测试与 variant classpath |
| [app 主 Manifest](../../record1/app/src/main/AndroidManifest.xml)、[debug](../../record1/app/src/debug/AndroidManifest.xml)、[benchmark](../../record1/app/src/benchmark/AndroidManifest.xml)、[androidTest](../../record1/app/src/androidTest/AndroidManifest.xml)、[benchmark tool Manifest](../../record1/benchmark/src/main/AndroidManifest.xml) | 主源无 INTERNET 声明，固定旧 Application/MainActivity/主题/备份规则；debug 有 M1 host；benchmark 有 profileable、fixture Activity 和 marker。未见主源中 `tools:node="remove"` 删除 INTERNET 的配置；主要网络禁令来自旧验证器/规范 | R2 自有 Manifest 和 overlay，不导入旧 app/AAR/资源目录或旧 manifest merger 输出；按每个实际 variant 检查 source→merged→最终 APK，确认新网络声明、组件和 test/debug 隔离 |
| [app/src/main/res](../../record1/app/src/main/res)、[backup_rules.xml](../../record1/app/src/main/res/xml/backup_rules.xml)、[data_extraction_rules.xml](../../record1/app/src/main/res/xml/data_extraction_rules.xml) | 固定 Theme.Record、launcher 和九域排除；M1 verifier 又固定资源取值 | 不用父级 res/srcDir 或旧 APK 作为 R2 输入；有意复用需单独接纳。Key 不进入任何备份/迁移等 R2 安全义务继续有效，不继承旧 UI 方案 |

R2 网络依据是 [Spec §5.3、B-AC7、CTX-01、SES-05](../specs/record2/v1/spec.md)：完整 AI 仅在 Provider 授权有效的主动任务中按需外发；无 AI/未授权零外发，原生业务离线仍可用。APK 拥有网络权限与“无 AI 不请求”是两种证明，不能互相替代；也不通过运行时撤销普通网络权限来模拟开关。

A1 检查网络权限/组件/HTTP 依赖的真实合并与打包结果，不能据此宣称 DeepSeek 协议或授权控制 PASS。后者由 D0/D1/E0/E2 及最终联合验收，在获批 Provider、合成数据、费用/Key 和设备范围内证明；包括撤销/取消/Provider 变化后的迟到请求/写回防护。本轮不改 [旧 security](security-and-privacy.md)、[conventions](conventions.md) 和 [testing](testing-strategy.md) 中的离线全禁令，其 R1 适用边界由根规则/Policy 已明确。

## 3. 构建入口与工具的防回流要求

| 入口 | 静态事实/边界 | A0/A1 需要落实的选择 |
|---|---|---|
| 根 [gradlew.bat](../../record1/gradlew.bat) / [gradlew](../../record1/gradlew) | 标准 wrapper 通过脚本位置找 wrapper jar，bat 透传 `%*`；脚本自身没有 R1/R2 分派和 M1 调用。使用哪个 wrapper 不等于已经证明选中了哪个 settings 根 | 新根自有 wrapper；同时明确 working directory 和 `-p` 的新根绝对路径，不以旧根 `:app:*` 命令代表 R2。运行前确认新 settings 存在，防止误回旧入口 |
| IDE | 本机忽略文件 `.idea/gradle.xml` 链接 `$PROJECT_DIR$` 及旧模块；`.idea/runConfigurations.xml` 没有 G3/R2 分派。二者不是已提交的跨环境保证 | A1 用新根导入/链接 R2，核实际 Gradle project 和运行目标；不在本轮改 IDE/环境配置 |
| Gradle 额外注入 | 所审仓库构建脚本未见 `includeBuild`、外部 `apply(from=...)` 或额外 sourceSets 映射；此结论不覆盖用户级 init scripts、全局 properties、`GRADLE_OPTS`/`JAVA_OPTS`、命令行 `-I`/IDE 注入 | A1 记录实际入口、wrapper/JDK、Gradle user home、init-script/参数来源及实际生效配置；只记录必要非秘密信息。未知全局项不能写“无全局 hook”，需检查或在获批隔离环境排除其影响 |
| Skills / 自动化 | 见下段工具调查；Skills 可生成旧任务/命令，不能改变根规则和任务权限 | 不改共享 skill 来假装机器已隔离；每次采用输出前核 R2 适用范围、构建根/包/测试对象。未来若引入 CI、hook、全仓扫描或统一 verifier，显式按构建根分派，相关 CI/环境写入另行授权 |

工具调查还定位了以下间接路径：

- **Skills 发现：** 本机 `.agents/skills` 与 `.qoder/skills` 均实测为指向 `D:\record\skills` 的 Junction；见 [skills/README.md](../../skills/README.md) L34–46 和 [.gitignore](../../.gitignore) L12–14。它们共享内容，没有产品分区；新环境须重新核对实际目标，不能因目录名不同就认为旧流程已隔离。
- **旧 lifecycle：** [AGENTS.record1.md](../../AGENTS.record1.md) L56–60 → [旧 workflow](agentic-workflow.md) L21–49 → [implement skill](../../skills/implement/SKILL.md) L11–17，会带入旧模块/SDK/Navigation、固定 TDD、full suite、review/Commit 等流程。[implement-spec](../../skills/implement-spec/SKILL.md) L25–40 还含 worktree/多 Agent/merge/清理。前两份实现 skill 标有 `disable-model-invocation: true`，也不代表可以忽略明确调用时的副作用。R2 仅按 Policy 与任务采用相关步骤；full suite 限定获批 R2 范围，不能自动扩成全仓旧测试，Commit/删除等不由 skill 授权。
- **Review 来源误匹配：** [code-review skill](../../skills/code-review/SKILL.md) L27–36 会查找 `docs/specs` 和所有 coding standards。派发时必须指明 R2 Spec/Plan/Policy 和 diff 范围；搜索到旧 conventions/testing/security/workflow 时保留其 R1 适用范围，不能作为 R2 失败依据。无需修改 vendored skill 才能做这次隔离设计。
- **历史文档回流：** [README](../../README.md) 已把下半部标为 R1 历史，顶部明确不默认旧 wrapper；工具若跳过顶部直接跟随历史 product/roadmap/design 链，仍会拾取旧规则。A1 检查实际工具的来源路由，而非删除历史入口。
- **可见 hook/CI/task：** 本机 `.github`、`.vscode`、`.run`、根 `CLAUDE.md`、`.claude/settings*.json` 等入口不存在，`.zcode/agents` 为空；`.claude` 仅见未读内容的 `scheduled_tasks.lock`。`.git/hooks` 仅有 sample，本地 Git hooksPath/include/alias 查询未见配置。没有证据表明这些入口自动接线旧门禁，但结论不覆盖用户级/系统级 hook、外部调度/CI 或 IDE 私有 workspace；锁文件和历史状态不能作“自动化不存在”的证明。
- **带副作用的工具：** [skills/impeccable/SKILL.md](../../skills/impeccable/SKILL.md) 的 launcher 可执行/首次下载二进制，pin/doctor 可改状态。仓库说明“未安装 hook/未运行初始化”也不能证明当前机器状态。本轮未运行这些工具，A1 不应把工具安装/doctor/全局重配作为无条件前置。

隔离判断依据必须包括来源：R2 仍可有名为 `:app`、`check` 或 `apiBoundaryCheck` 的任务，不能仅凭名称判定污染。要确认任务的 build/project 归属、实现及其依赖/输入；旧任务即使被重命名，只要来自旧脚本/源码/制品，仍算回流。

## 4. 交给 A0 的设计输入

1. 比较“独立根”与“受控改造”时，纳入上面项目图、API/fixture、九族 verifier、Manifest/资源、IDE/工具入口的真实耦合成本；本报告只给隔离输入，不代做 A0 的完整两路径评审。
2. 决定实际构建根、settings/wrapper/catalog/properties 与输出目录的归属，生产/测试模块边界及可运行命令；审核独立 applicationId/namespace/test/benchmark 身份，避免安装覆盖 R1。不在本轮定值。
3. 对每项旧资产给接纳/不接纳及验证安排；默认无旧 project/composite/source/fixture/resource/产物依赖。不得用 `-x`、扩大旧白名单或旧测试 PASS 抵消耦合。
4. 将下列检查项落到 A1 获批构建合同；若实际模块、variant 或任务名不同，替换命令中的占位对象并保留同等断言，不新增治理阶段。

## 5. A1 获授权后的真实隔离检查清单

下列是已审查的V1–V8检查合同，G3静态提交时均为NOT RUN；授权后的A1执行范围、真实结果及未覆盖项由Plan §17.2记录，本文不复制状态表。后续新变体仍按对应授权验证，优先共用A1原构建/制品证据，不另造验证框架。

执行解释：必须隔离旧项目、构建脚本、源码、测试、资源、输出和任务链；不要求隔离所有工具缓存。版本兼容、安全且来源清楚时，可以共享正常 Gradle 依赖下载缓存、JDK 安装和 Android SDK；共享 user home 时仍须核查其中 properties/init scripts，不能把共享缓存等同于接纳全局规则。V3/V5/V6 按 A1 实际交付的配置与变体验证；需后续产品实现的最终发布项目记录具体条件和后续 owner，保持 NOT RUN，不倒挂为 A1 必须提前完成的 V1 全产品验收，也不把未执行部分标 PASS。

| ID | 对象/操作 | 必须观察到的结果及失败条件 |
|---|---|---|
| V1 | 记录绝对构建根、cwd、settings、wrapper/catalog、JDK/Gradle/AGP/Kotlin 实际版本与额外注入来源 | 根身份符合 A0；新 settings 不存在就停止，不能退回旧根；没有未经审核的旧 build script、catalog、init-script 注入或输出重定向 |
| V2 | 新根 `projects`、`tasks --all`，检查实际 settings/includes 和 projectDir | 只有获批 R2 模块；无旧七模块/benchmark 或旧 composite/substitution/projectDir 关联。配置阶段失败也必须如实记录，不能因任务没执行就判隔离成功 |
| V3 | 对 R2 `check`、app check/test、assemble app/test、A1 实际交付的 release variant（如有）分别 `--dry-run`；核任务归属、dependsOn/finalizedBy 和脚本调用 | 不进入旧 `checkModuleDependencies`、旧 data `apiBoundaryCheck`、旧签名单测、`emitP6Oracle`、两旧 report validator、旧 benchmark/迁移/业务 runner 或 M1 verifier；不是只检查控制台有无某个名字 |
| V4 | 新根生产/测试的 compile/runtime 依赖报告及源码/资源/Manifest/Room schema/fixture 输入配置 | 无旧 project/testFixtures/AAR/JAR/classes/APK/build 输出或父目录 src/res/assets/schema 接入；无外部脚本/进程调用旧 Gradle。仅检查 runtimeClasspath 不够，测试和编译路径也要查 |
| V5 | 正常运行 A1 所需 R2 host tests、app/test assemble、check；核结果报告中的测试来源与最终输出路径 | 真实成功且产物来自新根；旧签名/业务/迁移/性能用例不成为成功前提。只 dry-run、只 tasks 列表、只编译通过均不足以单独判 G3 真实隔离 PASS |
| V6 | 对各交付 variant 比对 source/merged/final APK Manifest、组件、权限、包身份、备份规则、release 夹具/依赖 | 必需网络声明保留且不被旧禁联网扫描拒绝；无旧 Application、M1 host/fixture/benchmark marker/profileable 泄漏；身份/安全预期来自 R2。Key 排除、离线和授权行为仍由对应后续测试证明 |
| V7 | 检查实际 IDE/CLI/未来 automation 命令根和验证范围 | 入口显式指向 R2，不能从仓库顶层默认旧 `:app` 或调用 `verify-m1-artifacts.ps1 -ProjectRoot <R2>`；无全仓测试发现把旧测试混入。不存在的 CI 不制造配置，有现有 CI 需求再单独授权 |
| V8 | 对照执行前后的旧受保护源码/构建/测试/脚本与已有用户修改，检查 R2 输出目录 | 旧文件未被改写，未为隔离放宽旧门禁，R2 未复用旧输出。此项只证明文件保护；不读取数据库就不能宣称用户数据完整性 PASS，也不需要重跑旧全套测试证明“没改旧代码” |

命令形态示例（**未执行**）：在 A0 批准的绝对路径中设置 `$r2Root`，确认 `settings.gradle.kts` 和 `gradlew.bat` 实际存在，再运行 `& "$r2Root\gradlew.bat" -p "$r2Root" projects`、`tasks --all`，以及同一前缀下的 `check --dry-run`、`:app:check --dry-run`、`:app:assembleDebug --dry-run`、`:app:assembleDebugAndroidTest --dry-run`、`:app:assembleRelease --dry-run`。`:app`/variant 只是示例，实际按获批结构替换；还要用同一入口运行获批实际构建和各 compile/runtime `dependencies --configuration <实际配置>`，保存 V1–V8 所需来源和报告。此段没有调用这些命令。

授权/配置/依赖解析失败记真实原因；只有 V1–V8 在 A1 实际交付范围内证据齐全，且范围与未覆盖项明确，才能报告该范围的“G3 真实工程隔离通过”。尚未交付的最终发布配置由对应任务继续验证，不能声称全部未来变体已通过。DeepSeek 真通信、数据库安全和最终产品联合验收不由该结果替代。

## 6. 本轮静态核查与限制

| 已执行操作 | 结果 |
|---|---|
| `Get-Content` / `rg -n` / `rg --files`：旧根/settings、全部 8 个模块构建声明、wrapper/catalog/properties、API 门禁/签名、M1 脚本、Manifest/资源、测试入口和相关规范 | 已定位 §1–3 的触发链及边界；未运行其中代码 |
| `Test-Path -LiteralPath record2` | `False`；仅说明候选目录当前不存在，不冻结未来工程结构 |
| `git status --short` / `git log -1` / `git ls-files` + `Get-FileHash` 受保护文件内容摘要对比 | **PASS**：旧源码/构建/测试/脚本及两份已有脏文档共 281 个纳管文件前后内容摘要一致，HEAD 未变；三个原有未跟踪目录保留。只写本报告、Plan 追加段和 index 路由，未暂存/提交 |
| Python 内联断言：本报告及新增路由/Plan 的相对链接、V1–V8 覆盖、Plan 前文未改、index 仅一条新路由、新文本空白、候选目录未创建；`git diff --check` | **PASS**：52 个本地链接有效，断言全部通过，差异空白检查通过（Git 提示现有换行转换，不是错误） |
| Gradle/M1 verifier、Android/Benchmark/迁移测试、数据库/设备/API/DeepSeek/全局工具运行状态 | **NOT RUN / 未验证**；不声称真实构建隔离、Provider、用户数据完整性或全局工具无 hook |

自查覆盖构建/API/脚本/测试/Manifest/工具/入口七面；后台只读代理补查工具链，由主 Agent 核对后合并，属于协作调查加自查，不是独立构建验证。本节保留 G3 静态交付时的检查范围；后续负责人审查结果及 A0 授权由 V1 Plan §15–16 记录。

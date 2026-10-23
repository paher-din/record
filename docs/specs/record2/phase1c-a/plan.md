# Record 2.0 — 阶段 1C-A 执行 Plan

- **Status:** `approved`；revision `R2`；2026-10-22（Asia/Shanghai）。用户最终批准当前R2及W1～W6实施范围，另行批准§2.1的W7精确diff；本Spec按规定激活，执行状态与证据由verification.md维护。
- **Execution:** `completed` — 2026-10-22。S0及两候选T1～T5全部有终态/原证据并主接受；最终C1/C2各10个真实Android合同与2宿主重启PASS，3个host金额用例PASS，A-AC1～6均通过（A-AC5仅有条件可行的源码审查）。公共S0保守21.515分钟，候选工作窗口上界C1 2.885h、C2 1.899h（含最终比较/保护检查），原始等待/返工分列，不双计共享重验。原始失败/编码问题/UiAutomator节点过期及两侧同步修复保留；未改版本/AC/预算。正式选型待负责人裁决，B/正式产品未实施；按W7关闭活动指针后停止。详细结果以[verification.md](verification.md)及[比较报告](D:/record2-experiments/phase1c-a-20261022/evidence/comparison/report.md)为准。
- **实施批准记录：** 2026-10-22，用户明确“现在按R2计划执行”，包含指定实验目录/Gradle/Schema合成写入/隔离SDK两组件/API34包操作/日志与verification；SDK仅用已同意许可接受记录。执行顺序W7→S0验收→C1-T1～T5→C2-T1～T5，公共≤1h、各候选≤8h；B/正式产品/真实数据/清理/发布不在授权内。本文“待批准/本轮不执行/draft”等规划历史保留作上下文，以本条最新批准记录为准，原协议条件不放宽。
- **执行所有权更新：** 2026-10-22，用户补充“如果不是多个并行任务，不需要派发子代理，main agent自己来做”。本实验当前串行，S0及C1/C2由main直接执行，停止原环境worker后交接；此项是本次明确用户指令对串行派发/三失败接手前提的限定覆盖，不修改旧workflow核心规则，不扩大任何写入/预算/验收权限。下文worker模板保留供确有独立并行工作时参考，不能据此重新派发串行任务。
- **Spec：** [spec.md](spec.md)，行为与 A-AC1～6 的唯一实验验收来源；[1B](../../../discussions/record2-phase1b-product-interaction-20261022.md) 为已获用户正式批准的产品设计输入。本 Plan 不复用旧 M2 Plan。
- **已批准：** 1B 全部产品设计（含 SD1）、两道门槛、A 每候选 8h/公共准备最多 1h；这些是规划输入。
- **已满足的审批：** 本Spec/Plan、受R2条件约束的§2 W1～W6、W4新实验Schema与合成数据、独立W7当前路由。未来新增/变更SDK许可、扩大路径/组件/依赖/预算、改AC、B/正式产品仍需另批；不得把本条当作扩权。
- **方法：** `skills/to-spec/SKILL.md`、`skills/to-tickets/SKILL.md`；按 [agentic workflow](../../../engineering/agentic-workflow.md) 转为本地纵向任务和真实依赖。`android-cli`/`testing-setup` 仅用于规划能力检查与真实持久化测试；不安装 skill 建议的额外框架。

## 1. 当前事实与不变量

- 规划轮只修订本Spec/Plan；当前执行轮允许获准W7对AGENTS/docs/index当前指针/路由的精确同步、本Spec/Plan执行元数据和新verification，以及外部实验根W1～W6。1B、旧Spec/Plan/ADR、roadmap和历史记录仍原样保留；不提交、切换原分支或修改原main。
- 原 checkout HEAD 为 `b3710e81d2d9ec1ab439b8e40af7669ac3a558ca`，branch 为 `codex/preserve-2026-10-21`。已有 AGENTS/workflow/testing-strategy 的未提交改动和 discussion/evidence 原样保留。
- 执行前9份旧Spec为8 completed / 1 approved / 0 in-progress；本次只激活Record2阶段1C-A为唯一in-progress，不激活旧M2。W7已按单独授权应用，不改根核心规则。
- 只读路径核查：下述实验根尚不存在；JDK17 和既有 debug keystore 存在；E0 AVD 注册和 API34 image revision14 配置存在。本轮未启动 AVD，也不宣称当前设备已 boot-ready。
- 旧 SDK 根缺 `platforms/android-34` 和 `build-tools/34.0.0`；AVD 系统镜像不是编译平台。新增两组件是待批准的独立 SDK 写入，不在本轮安装。

## 2. 实际写入范围审批清单

以下均是**下一轮拟批准范围，本轮没有执行**。路径存在/所有权/重解析点检查失败即停止，不能覆盖同名目录。实验根统一为 `D:/record2-experiments/phase1c-a-20261022`，不能落入 `D:/record`。

| 编号 | 拟写范围 | 用途、风险与限制 |
|---|---|---|
| W1 C1 | `D:/record2-experiments/phase1c-a-20261022/c1/`；本地 branch `codex/record2-c1`；applicationId/namespace `com.paherdin.record2.poc.c1`，testApplicationId 加 `.test` | 新建一个 `:app` 的隔离工程；可写源码/资源/测试/Gradle/wrapper/生成构建输出及本地规范。只构建 debug，不发布、不写原 Record |
| W2 C2 | 同根 `c2/`；本地 branch `codex/record2-c2`；applicationId `com.paherdin.record2.poc.c2`，testApplicationId 加 `.test`；源码 namespace 保留 `com.andersonlin.moneybook` | 仅本地 clone 固定 commit并适配，保留许可/历史、不推送；不创建公开Fork。**须通过§7.2安装前门槛，才可安装/启动；构建或隐藏UI入口不等于已通过检查** |
| W3 公共工具/证据 | 同根 `sdk/`、`gradle-user-home/`、`common/`、`evidence/` | 仅批准的Platform34和Build Tools34.0.0；关闭AGP自动SDK下载。许可证/缓存/临时写入只限§3.1路径并记录实际值；不升级其他组件，不写全局用户目录；无法隔离则BLOCKED，无主动clean |
| W4 新实验库 | 两个实验包各自 `/data/user/0/<applicationId>/databases/record2-<c1或c2>-<runId>.db` 及 SQLite sidecar、私有运行选择偏好；字段见 §4 | **须明确同意初建Schema/合成写入，并先通过§4.3身份绑定。** 首次Room前固定runId/库；四方核对实际连接；首次提交前固定记录身份，失败重试不换id/recordKey。不打开/迁移/删除旧库 |
| W5 Android 设备运行 | 仅核实后的 `record-api34` serial：安装/更新两个候选包及各自测试包，启动/定向 force-stop、instrumentation、读取本包证据；必要时正常 boot 现有 AVD | 保留原运行/数据保护边界；C2安装/启动另以§7.2的最终APK/组件/路径检查PASS为前置。正常AVD运行仍会写包管理、候选私有数据及既有userdata；不wipe/reset/快照回滚、pm clear/卸载、碰真机/原包/他人数据 |
| W6 执行记录 | 经批准后仅本目录新 `verification.md`，以及本 Spec/Plan 的批准、revision、执行状态元数据 | 主代理维护 Execution pointer、任务日志、验收行和证据链接；本轮不提前创建执行记录或写 PASS |
| W7 独立生命周期路由 | 仅§2.1所列的文档路由与当前活动指针最小diff | **独立待批，不随W1～W6、R2或预算自动获准。** 本轮不应用；不得激活与现有路由冲突的新Spec，不改根核心规则/旧批准历史 |

W1/W2 第一项准备工作是在代码前写各自实验 `AGENTS.md`，镜像获批合同、写入白名单、原仓库保护和证据规则；没有规范不动业务代码。新工程 `local.properties` 如需写，只含隔离 SDK 的 `sdk.dir`，须在 W1/W2 中明确获准；不读取或复制原项目环境文件/签名配置。

不在清单内：任何真实设备、旧应用/历史备份、原仓库业务/测试/Schema/Gradle/CI、环境文件/密钥/token、原 Git 历史、公开发布。已有 `C:/Users/p3148/.android/debug.keystore` 只供常规 debug 签名使用，不能改写、复制到代码或输出内容；若不可用，另行审批测试密钥生成，不能退用 release 密钥。缺工具或目录冲突时报告阻塞，不扩大白名单。

### 2.1 W7独立待审：最小文档路由diff（本轮不应用）

优先只在docs/index增加Record2实验读取路由，不更改根工程的产品/架构/数据/主代理规则。两文件现有“Current active spec: none”是全局状态文字，激活新Spec时若原样保留会互相冲突，因此AGENTS仅把该状态前缀委托到index，index同步当前指针；其余旧M2/M1历史状态原文保留。无需修改roadmap、旧Spec/Plan或ADR，也不把旧路线的模块/Schema约束搬到新实验。

下列精确diff针对本轮读到的工作区，仅供负责人**独立批准W7**；不因R2/预算/W1～W6获批而应用。它只可与新Spec执行批准记录、唯一活动检查和实际激活同步；在此之前index仍为none、新Spec仍draft。未批准或存在其他活动Spec/路由冲突，保持非活动并报告BLOCKED。

```diff
--- a/AGENTS.md
+++ b/AGENTS.md
@@ -23,1 +23,1 @@
-- Current active spec: none — [MVP M2 — finance core](docs/specs/mvp/m2-finance-core/spec.md) is `approved` (2026-09-04) after delegated independent review; owning core documents are synchronized and [ADR 0017](docs/architecture/decisions/0017-m2-finance-schema-normalization-and-backup-v2.md) is accepted. M2 remains inactive until its plan is explicitly approved and schema/migration execution consent is recorded. [MVP M1 — app shell](docs/specs/mvp/m1-app-shell/spec.md) is `completed` (2026-09-04) with all 19 acceptance criteria PASS. Tech PoC P0–P6 are `completed`.
+- Current active spec: follow the current pointer in [docs/index.md](docs/index.md); lifecycle and uniqueness rules are unchanged. Record 1.0 status: [MVP M2 — finance core](docs/specs/mvp/m2-finance-core/spec.md) is `approved` (2026-09-04) after delegated independent review; owning core documents are synchronized and [ADR 0017](docs/architecture/decisions/0017-m2-finance-schema-normalization-and-backup-v2.md) is accepted. M2 remains inactive until its plan is explicitly approved and schema/migration execution consent is recorded. [MVP M1 — app shell](docs/specs/mvp/m1-app-shell/spec.md) is `completed` (2026-09-04) with all 19 acceptance criteria PASS. Tech PoC P0–P6 are `completed`.
--- a/docs/index.md
+++ b/docs/index.md
@@ -7,1 +7,2 @@
 | Any task | AGENTS.md, docs/index.md |
+| Record 2.0 isolated selection experiment | specs/record2/phase1c-a/spec.md, specs/record2/phase1c-a/plan.md; scoped to independent experiments, not Record 1.0 implementation |
@@ -20,1 +21,1 @@
-- Current active spec: none — [MVP M2 — finance core](specs/mvp/m2-finance-core/spec.md) is `approved` (2026-09-04) after delegated independent review; owning core documents are synchronized and [ADR 0017](architecture/decisions/0017-m2-finance-schema-normalization-and-backup-v2.md) is accepted. M2 remains inactive until its plan is explicitly approved and schema/migration execution consent is recorded. [MVP M1 — app shell](specs/mvp/m1-app-shell/spec.md) is `completed` (2026-09-04) with all 19 acceptance criteria PASS. Tech PoC P0–P6 are `completed`.
+- Current active spec: [Record 2.0 phase 1C-A](specs/record2/phase1c-a/spec.md) is `in-progress` only under its recorded execution and routing approvals. Record 1.0 status: [MVP M2 — finance core](specs/mvp/m2-finance-core/spec.md) is `approved` (2026-09-04) after delegated independent review; owning core documents are synchronized and [ADR 0017](architecture/decisions/0017-m2-finance-schema-normalization-and-backup-v2.md) is accepted. M2 remains inactive until its plan is explicitly approved and schema/migration execution consent is recorded. [MVP M1 — app shell](specs/mvp/m1-app-shell/spec.md) is `completed` (2026-09-04) with all 19 acceptance criteria PASS. Tech PoC P0–P6 are `completed`.
```


## 3. 版本、上游与许可证冻结（待本 Plan 批准）

### 3.1 共同构建与最小依赖

为避免在比较同时进行一次无关工具链升级，C1/C2 首轮采用同一套 MoneyBook 已声明的 Kotlin/Compose/Room 基线。它是**实验版本选择，尚未构建验证，不是正式产品终身选型**。原 Record 的 AGP9.3/Kotlin2.2/Room2.8.4 不自动继承进来。

| 项目 | 固定值 | 来源/许可及处置 |
|---|---|---|
| JDK | 已有 Temurin 17.0.20+8，`C:/Users/p3148/tools/jdk/jdk-17.0.20+8` | 只读复用；OpenJDK GPLv2 + Classpath Exception；不安装/替换全局 Java |
| Gradle | 8.9；官方 `https://services.gradle.org/distributions/gradle-8.9-bin.zip` | Apache-2.0；官方 SHA-256 `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab`；C2 仅将原腾讯镜像 URL 改为此同版本官方 URL并加校验 |
| AGP / Kotlin / KSP | 8.5.2 / 1.9.24 / 1.9.24-1.0.20 | Apache-2.0；来自固定上游 catalog，Kotlin/Compose 兼容依据见 §3.3 |
| Compose compiler / BOM | 1.5.14 / 2024.06.00 | Apache-2.0；BOM 的 UI/Foundation/UI test 为 1.6.8，Material3 为 1.2.1，官方 POM 已核对；不引入新 Compose 编译器插件 |
| AndroidX Core / Activity / Lifecycle | core-ktx 1.13.1、activity-compose 1.9.0、lifecycle runtime/runtime-compose/viewmodel-compose 2.8.3 | Apache-2.0；两候选同版本 |
| Navigation / Room | navigation-compose **2.7.7**；room runtime/ktx/compiler 2.6.1 | Apache-2.0；Navigation 2 + 手工 ViewModel/Repository 接线，不用 Navigation3/Hilt |
| 协程 | kotlinx-coroutines-android 1.8.1，统一显式约束 | Apache-2.0；官方 POM 已核对；不引入额外 domain/use-case 框架 |
| 本地测试 | JUnit 4.13.2 | EPL-1.0；POM 已核对；传递 Hamcrest 等须在解析清单保留实际许可 |
| Android 测试 | runner 1.6.2、ext:junit 1.2.1、uiautomator 2.3.0；Compose ui-test-junit4/ui-test-manifest 1.6.8 | Apache-2.0；只有 `androidTest` / `debug` 范围，runner 为 AndroidJUnitRunner；无 Robolectric/截图/覆盖率插件 |
| SDK | minSdk=34 / targetSdk=34 / compileSdk=34；buildToolsVersion 34.0.0 | 仅新实验，不改旧配置。隔离 SDK 需 Platform34 revision3/ext7 + Build Tools34.0.0；Android SDK License，不能静默接受新许可 |

隔离 SDK 的官方元数据已只读核验：`platform-34-ext7_r03.zip`（SHA-1 `1f2e9478d6a7601425ceaa553311dc43191f103d`），`build-tools_r34-windows.zip`（SHA-1 `62cfde1b6fcc3ad12a4d2ba1b537e752768bfd47`）。未来安装时核对官方元数据和实际包版本，保存下载后 SHA-256；若管理器选到别的 extension/revision，停止并报告，不悄悄更新基线。只增上述隔离组件，现有 emulator/platform-tools/CLI 继续从旧 SDK **只读调用**。

两实验工程的`gradle.properties`必须设`android.builder.sdkDownload=false`，构建同时传同值`-P`并核对有效配置；仅获准的SDK安装步骤能写隔离SDK，解析若要求额外包则BLOCKED，不运行update/all或全量许可证接受。依据：[官方关闭自动下载说明](https://developer.android.com/studio/intro/update#download-with-gradle)、[工具路径变量](https://developer.android.com/tools/variables)。

以下路径均在既有W3范围内；启动前把**实际解析路径**、工具/版本、许可id/接受记录来源、进程变量及安装前后文件清单写入evidence，不能仅记录预期值：

| 写入类别 | 允许位置与约束 |
|---|---|
| SDK组件/许可证/暂存 | `<实验根>/sdk/`中的两批准组件、`licenses/`及工具实际暂存子目录；核对已接受许可来源，仅在相同许可已获同意时复制其接受记录，不改旧SDK licenses，不自动接受新增/变更条款 |
| Gradle/Maven/项目输出 | `<实验根>/gradle-user-home/`的wrapper/caches/daemon等，以及c1/c2各自`.gradle/`和build输出；不得回退到全局`.gradle` |
| Android工具用户缓存 | `<实验根>/common/tool-home/.android/`；子进程`ANDROID_USER_HOME`固定于此，Java的`user.home`固定为common/tool-home；既有AVD注册路径只读显式指定，不能由新user home找不到AVD而创建/替换它 |
| OS/JVM临时文件 | `<实验根>/common/tmp/<tool或candidate>/`；子进程TEMP/TMP及launcher/构建JVM的`java.io.tmpdir`同向绑定，记录实际展开位置；不修改系统/用户环境变量或profile |

构建/SDK安装子进程`ANDROID_HOME`只指隔离SDK；emulator/ADB子进程为复用E0镜像可只读指既有SDK，并显式指向原AVD注册目录。两类进程配置分开，禁止AGP借后者写旧SDK。debug签名显式使用已有只读keystore，禁止新user.home触发自动生成密钥。工具路径未知、缓存/许可/临时输出无法约束到白名单、需新组件或环境不满足，均报BLOCKED，不借原全局缓存/下载/升级绕过。

### 3.2 C2 固定来源与继承负担

- 仓库：`AndersOnLin4/moneybook`，固定 commit **`98c50341da262e839f68557e721eee38aca57067`**，tree `52b141df00f7b8fb3e829b75378a965803c1958b`；本轮 2026-10-22 经 GitHub connector 核验。后续不使用浮动 master。
- LICENSE：MIT，保留 `Copyright (c) 2026 AndersOnLin4` 与全文许可；固定 LICENSE blob `e1142b8040be7eb62bf023020540d61b173c9109`，catalog blob `aa134e88d4f076b16192ca8cf0280bc775f5000a`。
- C2 已声明的其他直接依赖：DataStore 1.1.1、icons-extended 1.6.8、Biometric 1.2.0-alpha05、Glance 1.1.1、WorkManager 2.9.0、Coil 2.6.0，均先登记为继承项（项目许可 Apache-2.0，解析后核查传递许可）。本轮不为 C1 引入这些能力；C2 不为美化指标做大规模去依赖，保留负担如实计入。
- C2 当前实际差异：`Bill` 金额是 Long 分，但 `categoryId` 非空、`dateEpochDay` 仅按天；`Format.kt` 金额显示/编辑回显经过 Double；`AddEditBillViewModel.save` 要求分类；Application 启动含循环账单/提醒回调。必须在隔离 Fork 修正有效路径、关闭与 A 无关的入口/后台工作，并计入 C2 工时，不能默认为满足 Record 2.0。
- C2 继承的旧备份、附件、账本管理、删除账单、预算/统计/提醒/应用锁/小组件入口不暴露给 A。保留源文件，不删上游代码；停止 Application 的循环生成/提醒调度，处理 WorkManager 自动初始化/组件声明，避免未获批写入。不能把隐藏入口写成“备份已修复”。
- 传递版本/许可的完整解析清单须在各候选获批构建后保存；当前只是明确固定直接输入，不伪称已完成完整依赖安全审计。版本冲突或未知许可保留为 A-AC4 阻塞，不自动升版/加依赖。

### 3.3 本轮核验的主要来源

- [固定 commit](https://github.com/AndersOnLin4/moneybook/commit/98c50341da262e839f68557e721eee38aca57067)、[版本 catalog](https://github.com/AndersOnLin4/moneybook/blob/98c50341da262e839f68557e721eee38aca57067/gradle/libs.versions.toml)、[App 构建](https://github.com/AndersOnLin4/moneybook/blob/98c50341da262e839f68557e721eee38aca57067/app/build.gradle.kts)、[LICENSE](https://github.com/AndersOnLin4/moneybook/blob/98c50341da262e839f68557e721eee38aca57067/LICENSE)。
- [Bill](https://github.com/AndersOnLin4/moneybook/blob/98c50341da262e839f68557e721eee38aca57067/app/src/main/java/com/andersonlin/moneybook/data/model/Bill.kt)、[Format](https://github.com/AndersOnLin4/moneybook/blob/98c50341da262e839f68557e721eee38aca57067/app/src/main/java/com/andersonlin/moneybook/util/Format.kt)、[AppDatabase](https://github.com/AndersOnLin4/moneybook/blob/98c50341da262e839f68557e721eee38aca57067/app/src/main/java/com/andersonlin/moneybook/data/db/AppDatabase.kt)、[MoneyBookApp](https://github.com/AndersOnLin4/moneybook/blob/98c50341da262e839f68557e721eee38aca57067/app/src/main/java/com/andersonlin/moneybook/MoneyBookApp.kt)。
- [AGP8.5 官方兼容说明](https://developer.android.com/build/releases/agp-8-5-0-release-notes) 支持 API34/JDK17，Gradle 最低8.7；[Compose/Kotlin 映射](https://developer.android.com/jetpack/androidx/releases/compose-kotlin) 明列 1.5.14 ↔ 1.9.24。这是文档兼容证据，不能代替本实验构建。
- [Gradle8.9 官方校验值](https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256)、[SDK 官方元数据](https://dl.google.com/android/repository/repository2-1.xml)、[Compose BOM POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2024.06.00/compose-bom-2024.06.00.pom)、[Room POM](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/2.6.1/room-runtime-2.6.1.pom)、[JUnit POM](https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom)。网络元数据只读查询，无 clone/依赖下载/安装；若未来实际内容变动，先报告。

## 4. 新实验数据库与写入范围（待批准）

### 4.1 C1 初建结构

仅初建 schemaVersion=1，单个 Room 库、一个应用模块，UI 不访问 DAO/Entity。以下都是本实验真实使用字段，不预建附件/搜索/Project/Tag/删除历史层。

| 逻辑表 | 最小字段/约束 |
|---|---|
| Account | `id: String` PK、`name: String`、`currency=CNY`；种子只有日常账户，无余额列 |
| Category | `id: String` PK、`name`、`direction=EXPENSE/INCOME`；夹具：餐饮/出行/购物/居家/其他支出、工资/其他收入。未分类用 null，不伪造分类事实 |
| Transaction | 稳定随机 `id: String` PK；`direction`、`amountMinor: Long > 0`、`accountId` FK、可空 `categoryId` FK、`memo`、`occurredAtMillis`、`createdAtMillis`、`updatedAtMillis` |
| Note | 稳定随机 `id: String` PK、`body`、`occurredAtMillis`、`createdAtMillis`、`updatedAtMillis`；正文非空白，不裁剪实际文本 |
| TransactionNoteLink | `(transactionId,noteId)` 复合唯一主键 + 两端 FK，禁止级联物理删除；双向读取索引 |
| FinanceDraft | 固定 slot=1；新建/编辑模式、目标记录身份、原版本时间、rawAmount、direction、account/category、memo、occurredAt、草稿写入版本；字段可不完整，不计事实。保存收支与消费同一 slot 在同一事务完成 |

索引只覆盖发生时间/稳定身份排序、FK 和唯一关系；无通用关联层。金额解析/格式化使用整数或 BigDecimal 精确操作，不能借 Double 过桥。

### 4.2 C2 新库结构及与上游的差异

用修改后的 AppDatabase **仅创建全新独立库**，版本4→实验版本5；不执行原 MoneyBook 库的4→5迁移、不打开其原库文件、不启用 destructive fallback。继承原8表（bills/categories/accounts/budgets/recurring_bills/ledgers/goals/category_budgets），新增 Note/Link/FinanceDraft 三表。原迁移源码保留但实验 builder 不注册旧库迁移路径；遇到旧版本/同名未知库直接阻塞。

- `bills` 保留 Long 自增 PK 和 `amountCents: Long`；`categoryId` 改 nullable，保留 FK；增加唯一稳定 `recordKey: String`（新增重试身份）、`occurredAtMillis: Long`、`updatedAtMillis: Long`。`note` 仍是收支说明；独立正文在新 Note 中。
- `dateEpochDay` 仅为继承兼容派生值，由 occurredAt/固定 Zone 推导，不能继续当唯一发生时间；原 createdAt 保留。原 attachmentUri/Mime 保留但均为 null，不增加新附件能力。
- 新 Note 的字段与 C1 对应；新 Link 用 `(billId: Long,noteId: String)` 唯一 FK；新 FinanceDraft 的目标映射到 Bill/recordKey，其余语义与 C1 相同。观察器规范化为相同逻辑字段，并保留物理 ID 映射证明编辑未换身份。
- accounts/categories 使用原字段和 Long ID，仅种子一个日常账户、相同类别；CNY 是实验唯一币种约束。原 ledgers 只保留一个内部默认账本以满足原 FK，不提供账本功能；其余继承业务表为空且不接受实验写入。相应无用代码/表/依赖仍计维护负担，不能伪装为 C1 的精简库。

### 4.3 两候选共同写入/隔离规则

- 允许写：种子账户/分类（C2另有一个必需内部 ledger）、合成收支、Note、关系、单份草稿；允许显式编辑/解除，保存成功同事务消费草稿行。**不允许物理删除收支/Note、旧数据迁移或任何个人数据写入。** SD1 已批准但不在 A 实现，故 A 不提前加 deletedAt/回收站 Schema。
- 每次完整测试attempt使用安全runId（小写英数/短横线，≤40）。**首次Room打开前**解析并冻结`candidateId/runId/databaseName/canonicalPath`：Instrumentation先读参数，测试启动真实Activity须携同一显式runId，二者必须一致；冷启动仅在无显式参数时使用已确认选择。显式新run在首开前替换旧选择，不能先开旧库再切换。Application只初始化无库上下文，禁止预开默认Room、播种或触发会开库的自动初始化；身份未绑定时Repository不得取得Room，无“默认库”回退。切换run先停止本候选进程，不在活连接上换库。
- 绑定后才打开Room并播种允许的新run库；真实Activity、Repository、Instrumentation分别核对绑定身份与实际连接，独立只读SQLite观察器自行用`PRAGMA database_list`取得主库规范路径，与各方证据及宿主manifest核对。实际连接证据由debug-only测试接缝采集，UI仍不访问DAO/Entity/物理路径；禁止只比较参数/缓存声明。任一不一致立即停止；verify/重启要求原库存在，观察器只读且不得创建库。禁止外部路径/URI；新attempt换库但不清旧库，release不带debug选择器。
- **首次新建提交前**即生成C1交易id/C2唯一recordKey，并先持久化到FinanceDraft；草稿写失败不得提交正式账目或重新生成身份。所有失败/重复保存/重启重试沿用该身份；未知提交结果先按它查真实库，已存在则协调同一事实，禁止生成新key插第二笔。C2自增Bill.id只有一次物理映射；保存与消费草稿仍为原事务，不以成功回执作为首次身份来源。
- 测试时钟固定在 `2026-10-22T21:00:00+08:00`，Zone 为 Asia/Shanghai；12:30/20:10 均早于当前夹具时刻。不改 AVD 系统时间/时区，不接真实记录。
- 原子事务覆盖收支字段与草稿消费、链接整组增减、Note 当前版本保存；一次性故障 `FINANCE_BEFORE_COMMIT` 位于真实事务已执行修改之后、提交之前，两候选同逻辑位置。只编入 debug/test，不导出组件，不建通用故障模块。
- 可行性审查须说明 B 如何在此实际读写边界增加 SD1 状态、保留隐藏关系和完整备份/无损切换；A 不实现该后续结构，不将“可行”当已证明恢复安全。

## 5. 任务、依赖、所有权与预算

当前所有任务为 **pending / 未获实施批准**，没有 worker 被派发。主代理仅做规划、派发与原始证据接受；获准后每次只派发一个实现 owner，所有功能修改在实验目录串行进行。任务不能基于未完成结果开始；遵循仓库 dispatch contract 的 revision/attempt/证据规则。

| task_id | Blocked by | 纵向交付与验证 | 唯一写 scope / 预算 |
|---|---|---|---|
| S0 | Spec/Plan、W1～W7 适用项批准和生命周期就绪 | 冻结保护摘要、工具/SDK/AVD/包身份、同一夹具/断言、证据模板；无业务写入 | 主准备 owner；W3、获准工具目录与W5中的AVD正常boot/只读盘点；不安装候选包；公共准备≤1h |
| C1-T1 / C2-T1 | S0 | 从真实入口接受默认来源、可选分类、创建/编辑支出/收入，持久化并重开；建立本候选规范与构建/隐私基线。T1支持J1/5、A-AC1/2/4 | 对应候选根，包括构建/资源/model/db/repository/ui及配套测试；2.5h＝准备1h+业务1.5h |
| C1-T2 / C2-T2 | 同候选 T1 | Note自动保存/编辑、双向关联/取消/解除、日期混排与基本连续浏览/返回；J2～5、A-AC1/2 | 同候选 ui/data 和合同测试；业务2.5h |
| C1-T3 / C2-T3 | 同候选 T2 | J6真实重启、草稿重启、非法金额/双击、真实提交前失败/重试；A-AC1/3 | 同候选写入函数/测试与本候选 evidence；安全1h |
| C1-T4 / C2-T4 | 同候选T1已接受的构建产物及T2已接受的读写实现；不以T3通过为前提 | 检查最终APK/依赖/许可/复杂度；定位备份/SD1可行路径及B剩余工作；A-AC4/5。T3受阻时仅审查已有产物，保留其未通过状态 | 仅本候选evidence；只读源码/产物，不改功能；可行性/依赖1h |
| C1-T5 / C2-T5 | 同候选 T1～T4 已有终态证据 | 补齐结果/实际命令/源码摘要/工时、主代理验原始结果；写各自报告及两者对照建议，A-AC6 | evidence/<candidate>；主代理归并evidence/comparison；证据1h |

每候选合计8h，公共准备最多1h。T5需分配部分证据时间用于主接受/最终对照，不另暗加“集成预算”。C2技术依赖仅S0，不依赖C1通过；资源顺序先跑完整C1，再C2，避免AVD/owner争用。C1失败也不改变C2验收标准。

T1～T3 的完整写scope还包含该候选获批W4私有库与W5包操作，不能写另一候选或原包。T5的前置结果是已落盘的终态原始报告，不要求每个上游任务成功；它可提交缺口/失败报告，但无权把上游阻塞改成完成。T4发现需改代码时先报告，主代理分类并在原任务的新attempt修复/重验；预算与重新批准边界不变，不能在审查中静默修好后沿用旧测试证据。

预期文件范围：C1 在一个 `:app` 内按 ui/timeline、transactions、notes、links 与 data/db/repository 分组，不建新Gradle模块或机械UseCase层；C2重点修改原 Bill/AppDatabase/DAO/保存ViewModel/Format/Application/导航与manifest，并增加 Note/Link/Draft 所需局部代码。两者测试放各自 `app/src/test` 和 `app/src/androidTest`。不删除上游源文件，不触碰原 Record 的同名路径。

每次未来派发必须注明：`task_id/task_revision=R2/attempt_id`、适用AC、只读/写scope、依赖磁盘证据、当前脏状态、命令/目标/日志路径、红线与阻塞条件。告知 worker 不独占代码库、不得覆盖他人改动；无功能批准不得派发。主代理维护新 verification 的 Execution pointer/Task log，worker 只提交原始证据与实际状态。

## 6. 具体测试合同与证据

两候选采用相同测试包 `com.paherdin.record2.poc.contract` 和逻辑断言，候选差异只在测试适配器/SQL物理映射；不建生产共享抽象模块。实现后冻结实际选择器清单；任何更改两侧同步审查，不能只给后跑者减要求。

| 规划选择器（全名前缀为上述测试包） | 验证/独立持久观察 | 证据目录名 |
|---|---|---|
| `JourneyContractTest#j1toJ5` | 同一run通过真实UI完成J1～5；观察T1身份/5800→6050、原文换行、关系；结束不清数据，供J6读 | journey/；UI截图、按步骤规范化SQL快照 |
| `RestartContractTest#verifyJ6` | 宿主完成force-stop/重启之后读取同run库与可见详情，断言6050/原正文/双向关系；不能重新播种 | restart/；stop前后pid/命令、前台截图、只读快照 |
| `SupplementContractTest#incomeAndNoteEdit` | 收入10000分；同Note身份正文修改/重开一致 | supplement/ |
| `SupplementContractTest#crossDayRelationsAndCancel` | 三条跨日关系、取消零变化、解除一条保留其他事实 | supplement/；完整端点/关系集合 |
| `SupplementContractTest#timelineAndBack` | 同日混排、前一日加载、选日/筛选/详情返回保持上下文 | supplement/；UI断言与预期顺序 |
| `SupplementContractTest#optionalCategoryAndInit` | 无分类保存为null、单账户/类别种子不重复、默认账户改名后重读 | supplement/ |
| `SafetyContractTest#invalidAndDoubleSave` | 空/0/负数/3位小数/指数/溢出不写，连续点保存仅一笔，回显不经过Double | safety/；操作前后事实数/金额及Parser单测报告 |
| `SafetyContractTest#rollbackBeforeCommit` | 独立合成run先存T1，再更新金额/说明，在真实Room事务修改后提交前抛出一次故障；旧值/草稿仍在、无成功提示；重试同身份成功且不增条 | safety/；故障点/回滚后及重试后独立SQL/可见错误 |
| `DraftContractTest#seedConfirmedDraft`、`#verifyDraftAfterRestart` | 已确认保留12.34元草稿；正式流水未增加；宿主终止重启后“继续”还原字段/原身份 | draft/；草稿持久化状态、宿主重启记录、恢复截图/事实数 |

`MoneyParserTest` 在host JUnit运行：58/58.00一致，精确Long边界和溢出、语法拒绝、整数分格式化回读；这是补充，不代替上表磁盘测试。只读观察器在提交/失败终态后通过独立SQLite读取真实候选库，不能只检查ViewModel/Repository缓存；快照规范化时间/方向/正文/逻辑关系并保留物理ID映射。

按§4.3在既有Journey/Restart/Draft用例中记录四方实际库身份/首次打开顺序；观察器不能另建targetContext临时库/fixture库代替。既有`rollbackBeforeCommit`保留更新回滚/重试断言，并在同一故障点核验首次插入失败后重试的C1 id/C2 recordKey与提交前草稿一致、只映射一笔。两候选同标准，不新增测试类或无关矩阵；结果仍经原始stdout保留，不向普通UI暴露物理路径。

不同方法使用不同runId后缀/新私有库；J6和草稿各自前后阶段必须相同runId。UI fixtureclock、当前run、种子和预期值均记录在每run manifest；禁止verify阶段重建缺失数据来“修复”失败。

A-AC4取证：保存直接/传递依赖解析结果及许可表，检查源码/merged/APK三层；无INTERNET/网络状态等未授权权限，关闭遥测/无关后台组件；`allowBackup=false`配合API34 data-extraction和兼容排除规则，覆盖本包DB/偏好等所有用户数据域。离线采用候选无网络能力并在其前台实做旅程，记录AVD网络环境，不切改真机或其他App网络设置。

A-AC5报告必须包含：完整逻辑数据枚举、D4格式/完整性/资源限额的候选方案、隔离预检/切换/失败回退各边界、SD1正常/删除/隐藏关系规则和自动保存竞态、源码定位、必须替换的上游行为、B故障点及剩余估算/未知。BK1～5和B-AC1～9本轮全部NOT RUN，不创建备份假成功入口。

证据根：`D:/record2-experiments/phase1c-a-20261022/evidence/<c1或c2>/<runId>/`。包含manifest、commands含exit、build、apk摘要/manifest、runner原始输出、各测试快照/截图、dependencies/licenses、cost.csv、feasibility.md、result.json。只含合成数据；不复制全设备logcat/原库/个人资料。测试长输出分块≤3KB；派生JSON/CSV注明由原始runner推导，不能伪装Gradle原生XML。

## 7. Android/API34 执行方法（仅计划，未执行）

### 7.1 目标预检

复用E0 AVD `record-api34`：Pixel6、x86_64、API34 Google APIs image revision14、2GiB RAM、4核；注册 `C:/Users/p3148/.android/avd/record-api34.ini`，userdata `D:/record-android/avds/record-api34`。[E0证据](../../../engineering/evidence/2026-10-21-e0.md)的历史serial是emulator-5560，**不能当本轮目标常量**。

先检查CLI具体操作/版本、SDK/ADB/emulator/JDK、加速/空间、图像/AVD配置、端口，再决定是否需正常boot；如需启动，复用E0无wipe参数，并用 `Start-Process -WindowStyle Hidden`。CLI缺能力只阻塞对应操作，可用已验证SDK工具；不因此跳过真实测试。下列变量中的serial必须来自新盘点并逐步核验：

```powershell
$pocAdb = 'C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe'
& $pocAdb devices -l
# 将核实的值赋给 $pocSerial，不能使用自动唯一设备选择。
& $pocAdb -s $pocSerial emu avd name
& $pocAdb -s $pocSerial shell getprop ro.build.version.sdk
& $pocAdb -s $pocSerial shell getprop ro.kernel.qemu
& $pocAdb -s $pocSerial shell getprop sys.boot_completed
```

守卫要求结果分别是record-api34/API34/qemu=1/boot_completed=1，且ABI/image/目标归属匹配。每次安装、启动、force-stop、测试、取屏前重新核对serial与包白名单；未知包已占用目标applicationId、路径为junction指向原仓库、可用空间不足或工具版本不符均停止。APK内applicationId/test target/activity/签名先核对再安装。

### 7.2 构建与包级取证

以下仅给获批后的命令形状；必须从**对应新候选目录**执行，日志写该候选证据，不从D:/record调用原wrapper。sdk.dir指向隔离SDK，构建缓存指向W3；首次下载需要联网的仅是host工具链，不赋予App联网权限。

```powershell
$pocJava = 'C:/Users/p3148/tools/jdk/jdk-17.0.20+8/bin/java.exe'
$pocRoot = 'D:/record2-experiments/phase1c-a-20261022'
# $pocWork 仅可为 $pocRoot/c1 或 $pocRoot/c2，并已验证物理路径。
Push-Location -LiteralPath $pocWork
try {
    # 必须先按§3.1绑定并记录本子进程的SDK/用户缓存/TEMP/TMP/Java临时路径。
    & $pocJava -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain --gradle-user-home "$pocRoot/gradle-user-home" -Pandroid.builder.sdkDownload=false --console=plain :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
    & $pocJava -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain --gradle-user-home "$pocRoot/gradle-user-home" -Pandroid.builder.sdkDownload=false --console=plain :app:dependencies --configuration debugRuntimeClasspath
} finally { Pop-Location }
```

不运行clean、不用connected测试任务自动部署到所有设备。为两边生成完整依赖锁/实际解析清单，网络失败不关TLS校验。构建后用隔离 `sdk/build-tools/34.0.0/aapt2.exe` 对实际debug APK执行 `dump badging`、`dump xmltree --file AndroidManifest.xml` 和打包后的排除规则检查，保存源码/merged路径与结果。源码许可与最终解析许可逐项对应；构建成功不是测试PASS。

**C2安装/启动前硬门槛（计入原T1/A-AC4，T4复核，不增预算）：** 对即将安装的App及test APK保存SHA-256与最终Manifest，核对package/applicationId、test target、所有Provider的实际authorities/路径配置、Activity/Receiver/Service/Widget及其他组件的名称、exported/permission/process、intent-filter和初始化metadata；authority不得冲突或残留原包标识，组件只允许有理由的实验清单。Worker并非都在Manifest中，须同时核查Worker类/注册、WorkManager/App Startup初始化、enqueue/周期任务/提醒/Alarm/Widget调度的源码与依赖入口，确认无未授权自动开库/后台任务及非实验私有路径写入。只隐藏UI或只看源码Manifest不算通过。

把实际检查项、所有权/冲突检查和结论写入`c2-preinstall.json`并绑定APK哈希；**未PASS不得执行install、am start或instrumentation**。组件、初始化、调度或写入根不能解释则BLOCKED；APK重建后原检查失效，重新核对后才可安装。此门槛是静态/构建取证，不能冒充尚未运行的后台行为验证；启动后的实际路径/行为仍受A-AC4/6约束。

### 7.3 显式安装、合同测试与跨进程验证

| 候选 | App package / Test package | 实际主Activity |
|---|---|---|
| C1 | `com.paherdin.record2.poc.c1` / `com.paherdin.record2.poc.c1.test` | `com.paherdin.record2.poc.c1.MainActivity` |
| C2 | `com.paherdin.record2.poc.c2` / `com.paherdin.record2.poc.c2.test` | `com.andersonlin.moneybook.MainActivity`（源码namespace保留） |

APK路径为各候选的 `app/build/outputs/apk/debug/app-debug.apk` 与 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`；若产物布局不同先查验再记录实际路径，不猜包名。安装前无同名未知包；更新只允许有本实验所有权证据的包。

```powershell
# C2必须先有与当前App/test APK哈希一致的c2-preinstall PASS；否则到此停止。
& $pocAdb -s $pocSerial install -r -t $pocAppApk
& $pocAdb -s $pocSerial install -r -t $pocTestApk
# $pocPkg/$pocTestPkg/$pocActivity 从上表选择，$pocRunId通过安全字符校验。
& $pocAdb -s $pocSerial shell am instrument -w -r -e runId $pocRunId -e class 'com.paherdin.record2.poc.contract.JourneyContractTest#j1toJ5' "$pocTestPkg/androidx.test.runner.AndroidJUnitRunner"
# 上一runner完整退出后，确保候选真实入口存在已保存数据并处于前台。
& $pocAdb -s $pocSerial shell am start -W -n "$pocPkg/$pocActivity" --es record2_poc_run $pocRunId
& $pocAdb -s $pocSerial shell pidof $pocPkg
& $pocAdb -s $pocSerial shell am force-stop $pocPkg
& $pocAdb -s $pocSerial shell pidof $pocPkg
& $pocAdb -s $pocSerial shell am start -W -n "$pocPkg/$pocActivity" --es record2_poc_run $pocRunId
& $pocAdb -s $pocSerial shell am instrument -w -r -e runId $pocRunId -e class 'com.paherdin.record2.poc.contract.RestartContractTest#verifyJ6' "$pocTestPkg/androidx.test.runner.AndroidJUnitRunner"
```

记录force-stop前PID、终止后进程缺失（该次pidof非零为预期检查结果）、启动返回值/新进程和实际前台包。**在启动验证runner之前**，宿主先确认前台Activity并捕获真实页面截图/可见节点；截图用ADB二进制流直接保留，不能经文本编码损坏。`verifyJ6` 用UI Automator及独立只读SQL验证同run库，不用ActivityScenario再次播种；它只能读取已有内容。instrumentation可能再次重启target进程，故force-stop→真实入口启动的证据必须由宿主独立保留，不能靠测试内一次recreate代替。

其余选择器按§6用同一`am instrument`形状执行；每方法新库，草稿的seed/verify两阶段之间复用上述宿主终止/启动步骤。工具中断、超时、runner被杀或仅ADB shell exit0都不能判PASS；检查每个预期用例实际完成、failure/error/skip、终端instrumentation状态和原始输出。失败/重跑保留不同attempt目录，不能覆盖首次失败。

## 8. 对称比较、保护与停止条件

- 计时从获准后的S0/对应候选任务开始；人工操作、Agent有效工作、下载/构建等待、总历时、返工、主接受分别记入cost.csv（candidate/task/revision/attempt/start/end/active/wait/result）。并行时间不重复计。规划阶段只读预研单列为“规划资料核验，未可靠计时”，不伪填0小时或算成prototype已实现；候选专属预研不能被当成免费工程优势。
- 实测成本仅对相同A目标比较；依赖与代码统计包含全部保留上游源、实体、背景组件，不以净diff行数论胜负；可行性报告逐项估计达到相同B/SD1目标的剩余成本区间、依据和未知。未知不是零，设计可行不是安全实测。
- 预算到点即停止，未完成记INCOMPLETE；实际失败记FAIL、权限/环境缺失记BLOCKED、未跑NOT RUN。追加时间、改版本、加依赖、换测试目标或改AC须先重新批准；不能因C2成本高砍Note/关联/时间线/安全门槛。
- 每个技术任务前后核对原仓库HEAD/branch、初始受控和已有未跟踪文档内容摘要；不要用“HEAD干净”代替保留用户未提交改动。核对原Record安装包/数据库保护目标，但不打开或导出其数据；保存初始package集合，结束只解释本候选/测试包的预期变化。真机不选、不启动、不安装、不清理。
- 所有宿主路径先规范化并检查真实父路径/重解析点，必须在批准实验根；所有DB/run选择固定在本包内部。不执行文件/目录删除、git reset/clean、pm clear/uninstall、AVD wipe、已有快照覆盖、原库导入、CI/密钥/发布操作。
- 主代理按Spec§7分别接受实验执行收口与候选资格：两候选即使均未通过，只要按批准协议/预算探索并留齐终态、逐项结果/未跑原因、原证据与保护检查，也可正式收口为completed；候选结果保持原样，写“无合格候选”，不宣布选型成功、不选择不合格基座。违反协议、缺证或必要失败处理未完则不能收口。停止进程核实归属，不删除产物；本轮仍draft。
- 计划内独立C2比较可继续记录；若失败否定已确认技术假设、需要改变实验设计或重试被证伪路径，按仓库修订/必要新ADR/重新批准流程停下，不回写旧ADR、不隐藏失败分类。A验收后也必须等待负责人选型；正式产品开发和B完整安全验收另建Record2 Spec/Plan，不能复用本Plan越界开发。

## 9. 规划交付验证记录

### 9.1 R1交付验证（保留）

- 本轮实际操作限于本地文档/状态/只读工具路径核查，以及GitHub固定源码、Android/Gradle官方文档/POM/SDK元数据读取；未运行Gradle、ADB、emulator、SDK安装、clone、APK或数据库操作。
- 写入前保护集合：现有受控文件、已有discussion/evidence和用户输入报告，共906路径；聚合SHA-256 `a0de6e35754c008398334be6d68057f539b67d1987c00c12ab49b99562a8f03c`。它包含初始用户未提交内容；两份新规划文件不属于该保护集合。
- 实际文档校验：PowerShell here-string 经 `python -` 执行只读验证；检查两文件Status=draft、6条A验收/6步旅程/7项写范围、每候选任务预算合计8h/公共1h、目录仅含Spec/Plan、Markdown围栏/行尾、本地链接和锚点；`git rev-parse HEAD`、`git branch --show-current`与保护集合摘要复算核对原状态，`git diff --check`检查既有受控差异。退出码0，**PASS**：906路径内容摘要不变，8个本地链接/1个锚点有效，旧8 completed/1 approved保持、新1 draft、0 in-progress；实验根未创建。新文件未跟踪，因此其内容用Python另验，不以Git whitespace检查代替。
- 本轮没有修改阶段1B状态文字；它的正式批准仅记入新Spec/Plan的输入批准记录。没有改旧Spec/Plan/ADR/历史记录或当前路由，没有为未来产品开发建占位文件。W7只是一项另待批准的路由方案，不是已执行迁移。
- 实验执行与验收状态：**A-AC1～6 全部NOT RUN；B-AC1～9/BK1～5 全部NOT RUN。** 本Spec/Plan仍draft，本轮交付后停止，等待负责人最后审查。

### 9.2 R2修订记录与剩余审批

- R2仅修改本Spec/Plan：首次Room前身份绑定、提交前稳定id/recordKey、W3下载/许可/缓存边界、C2安装前最终产物门槛、W7独立最小路由diff，以及实验收口/候选通过分离；原路径、版本、预算、J1～6和A-AC1～6不变。精确R1→R2差异以写入前内容快照计算，不把Git HEAD当作这两份未跟踪R1文档的基线。
- 可提交最终批准的范围：W1/W5/W6为原则认可但仍待实施授权；W2须受当前APK哈希绑定的安装前PASS限制；W3仅两批准SDK组件及记录的许可/缓存/临时根；W4须先绑定真实库及稳定记录身份。**W7独立待批，不在W1～W6或R2批准中自动包含。**
- 剩余风险：实际SDK缓存/临时行为和C2组件/调度未运行验证，路径不能约束或静态入口无法解释即BLOCKED；实际Room打开顺序、四方连接和重试身份必须由既有合同用例证明，纸面约束不算PASS。无合格候选也可按协议完成实验收口，但不能推进选型或日用开发。
- R2文档校验：`python -`校验状态、链接/锚点、围栏/行尾与906保护路径摘要，`git diff --check`检查受控差异，另与写前R1快照比较；结果PASS、退出码0。J1～6、A-AC1～6、W1/W6、版本表、Schema字段、候选任务预算与既有测试选择器保持原文；原HEAD/branch及旧记录未变，0 in-progress、实验根未创建。W7仅在本Plan内提供提案，不应用；本轮不运行工具链/原型/设备、不安装APK、不建库，两文件保持draft。

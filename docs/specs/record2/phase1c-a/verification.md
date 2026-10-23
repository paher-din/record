# Record 2.0 phase 1C-A — 执行与证据

- Spec/Plan revision：R2；用户于2026-10-22最终明确批准Spec、Plan、W1～W6和独立W7；仅A实验，不含B/正式产品。
- 状态：in-progress。所有候选技术门槛初始NOT RUN；旧E0不计新实验PASS。

## Execution pointer

- task_id：C1-T2；task_revision：R2；attempt_id：1。
- owner：main（用户最新串行执行指令）；write_scope：获批W1 c1工程、对应W3缓存/证据、W4合成库、W5仅C1包/测试包操作。
- depends_on：W7/批准记录已接受；S0于2026-10-22T09:42:50Z主接受，原始s0-result.json、实际AVD与904保护摘要PASS。
- state：completed；dispatch_failure_count：0。
- blocked_on：none；next：none。本轮A实验已收口并停止；正式技术基座由负责人裁决，B/正式产品须另建独立Spec/Plan并获授权，不自动推进。
- evidence_root：D:/record2-experiments/phase1c-a-20261022/evidence/c1/；S0原证据在common/s0-attempt1和s0-attempt2。
- latest_update：2026-10-22；精确UTC时间与S0开始/终止由实际执行记录。

## 授权与保护基线

- W7仅应用Plan§2.1批准diff：AGENTS当前指针委托docs/index；index新增一行Record2路由与当前指针。其余根规则和旧历史原文不得改变。
- 原HEAD：b3710e81d2d9ec1ab439b8e40af7669ac3a558ca；branch：codex/preserve-2026-10-21。
- 本轮写入前其他受控文件/既有discussion、engineering/evidence与输入报告，排除允许写的AGENTS/index/新Spec/Plan后共904路径；聚合SHA-256：ed52e528459f04ebdd0869f2cfe53e8cd8047138b5d684e7d18440339d37fffb。
- 包含用户既有未提交改动，不能用HEAD清洁状态替代。原始工作区已有AGENTS/workflow/testing-strategy改动和未跟踪discussion/evidence/本新Spec目录。
- 外部实验根写入前不存在；W7/生命周期与主接受成本单列，不冒充候选实现耗时。S0计时在正式派发时开始，公共有效工作上限1h。
- 原仓库业务/Schema/Gradle/CI、旧Spec/Plan/ADR、真机/历史数据/删除/公开发布均不获准。

## Task log

| task | revision/attempt | owner | state | main acceptance / raw evidence |
|---|---|---|---|---|
| W7-ACTIVATE | R2/1 | main | done | ACCEPTED：批准W7精确一致，唯一活动与904路径摘要PASS；原始字段见下 |
| S0-先前派发 | R2/1 | /root/s0_environment | cancelled | 用户改为串行main执行；不算失败，不积累接手阈值，等已有结果交接 |
| S0-接管 | R2/2 | main | done | ACCEPTED 09:42:50Z：sdk/license/tools/真实API34/路径与904保护摘要PASS；s0-attempt2/s0-result.json |
| C1-T1 | R2/1 | main | done | ACCEPTED 11:37:08Z；t1-attempt1/acceptance.json，3个host测试+1个完整财务UI合同，4份独立SQL快照 |
| C1-T2 | R2/1 | main | done | t2-attempt1/acceptance.json；Journey+3个Supplement真实PASS，12份独立SQL快照；904保护摘要不变 |
| C1-T3 | R2/1 | main | done | t3-attempt1/acceptance.json；Restart/Safety×2/Draft×2真实PASS，更新与首次插入失败均证明同身份重试；2次宿主进程终止证据 |
| C1-T4 | R2/1 | main | done | t4-attempt1/acceptance.json；66个运行时坐标、86个第三方许可已核；三层打包/离线边界PASS，D4/SD1有条件可行 |
| C1-T5 | R2/2 | main | done | final2完整10方法/2宿主重启PASS；t5-attempt2/acceptance.json追加接受，原final1不覆盖；仅测试工具变化、业务APK不变；两段工作窗口计预算，排除中间C2工作 |
| C2-T1 | R2/1 | main | done | t1-attempt1/acceptance.json；3个金额host用例+真实财务UI合同PASS，11表实际schema5、4份SQL及Bill物理PK/recordKey一致、后台Job=0 |
| C2-T2 | R2/1 | main | done | t2-attempt1/acceptance.json；Journey+3个Supplement真实PASS，12份独立SQL及11表物理证据，逻辑断言与C1一致 |
| C2-T3 | R2/1 | main | done | t3-attempt1/acceptance.json；Restart/Safety×2/Draft×2 PASS，2次宿主真实重启，11表与草稿身份逐项重读一致 |
| C2-T4 | R2/1 | main | done | t4-attempt1/acceptance.json；111运行时/130第三方许可坐标已核，D4/SD1有条件可行；旧备份不合格且未调用 |
| C2-T5 | R2/3 | main | done | final3完整10方法/2宿主重启PASS；t5-attempt3/acceptance.json接受；final1提前重放与final2节点过期FAIL原样保留；同期限/断言的两侧工具修正后主接受，正式选择仍由负责人裁决 |

## Candidate acceptance

| gate | C1 | C2 |
|---|---|---|
| A-AC1 | PASS | PASS |
| A-AC2 | PASS | PASS |
| A-AC3 | PASS | PASS |
| A-AC4 | PASS | PASS |
| A-AC5 | PASS（有条件可行的源码审查；未实施） | PASS（有条件可行的源码审查；未实施） |
| A-AC6 | PASS | PASS |

B-AC1～9、BK1～5：NOT RUN，未获实施授权。实验执行收口与候选资格分开判断，不能把负向结果改成PASS。

## 最终结果与停止

- [完整比较报告](D:/record2-experiments/phase1c-a-20261022/evidence/comparison/report.md)；[C1主接受](D:/record2-experiments/phase1c-a-20261022/evidence/c1/t5-attempt2/acceptance.json)；[C2主接受](D:/record2-experiments/phase1c-a-20261022/evidence/c2/t5-attempt3/acceptance.json)。两候选具备提交负责人选型资格；建议C1仅为Agent建议，未自行批准正式基座。
- 最终C1 source4b4b18af2fd4f3ea0410e394952bdebd4911d679 / final2；C2 source9f83b25a18345d732c7534a380ae32ce0bdc5e66 / final3，上游98c50341da262e839f68557e721eee38aca57067。各3个host金额用例、10个真实Android合同、2次宿主重启全部PASS，期间源输入hash不变；实际磁盘快照/Actor身份/源码与打包策略证据均在外部实验根。
- 原失败不覆盖：C2首APK安装前BLOCKED、宿主编码失败引起的提前final1、final2节点过期FAIL；同步在原期限内重查节点后完整复验两侧，业务/SQL/进程断言不变。原始PASS不追溯为当时尚未满足的最终接受，工具失败不伪装数据成功。
- 成本：公共S0上界21.515分钟<1h；C1两段工作窗口合计2.885h<8h；C2排除已计C1的共享重验窗口后1.899h<8h（含最终比较/路由/保护检查，总历时另列）。净有效工作未可靠细分，不伪填精确active/wait；工具跨度原文保留且不重复相加。C2复用C1界面/state/工具/缓存的顺序优势已明确。
- 数据安全可行性：C1/C2均有条件可行，全部B/BK/SD实验NOT RUN；尤其C2旧BackupManager不兼容新结构，保持禁用，未执行备份/恢复或软删除。
- W7同一生命周期收口：Spec in-progress→completed，docs/index当前指针none；AGENTS仍仅委托index，旧M2 approved及其他历史状态不变，不建ADR或下一阶段Spec/Plan。

## C2已执行事实

- T1始于12:32:53Z，13:15:51Z主接受（t1-attempt1/acceptance.json）。完整clone耗178.35秒，固定HEAD98c50341da262e839f68557e721eee38aca57067、tree52b141d…、MIT版权/正文与历史保留；原Record未切分支。
- money-red1为同口径3个金额测试，2项真实失败（Long上限显示及语法拒绝）；修正原Format的Double桥接与语法后，money-green1及finance-green-build1的3项均PASS，首次XML保留。
- 首个APK最终Manifest含无关Glance/WorkManager/Preview组件及Biometric/Foreground权限，finance-red-inventory1/preinstall-result.json明确BLOCKED，未安装。移除声明并复建后只留实际MainActivity，Startup/Worker调度源码根、私有DB绑定、签名及App/test哈希均通过，再安装；所有后续重建均重新核对，不沿用旧哈希批准。
- finance-ui-red1在真实隔离入口缺记账UI处FAIL；finance-ui-green1在API34实际通过58→60.50/可选分类/账户改名/收入100/初始化重读，4份独立SQL同时保留逻辑投影和全部11表物理数据；recordKey与Bill自增PK均未换，JobScheduler实际0，4个非实验业务表为空。
- 活跃财务路径保留并扩展原Bill/AppDatabase/BillDao/BillRepository/Format；Record2交互状态及界面复用C1已写原型代码，原单笔VM/旧UI保留但不在实验组合中。不是独立从零重写UI的工时对照；共享代码/缓存/工具带来的后跑优势需在最终比较明示，不能只凭净diff或历时选型。
- T2起点13:16:00Z；journey-red1已在J1实际落盘后因缺记事入口FAIL。其后在原11表之上补Note/Link DAO与Repository、跨记录UI和混排查询，无新表/依赖/模块。当前正在跑Journey及3个Supplement，未提前记PASS。

## W7原始接受字段

实际执行：PowerShell here-string → python -；git diff --check；逐字比较写后AGENTS/index与Plan R2批准提案的完整预期文本。2026-10-22T09:15:46Z结果：

```json
{"result":"PASS","protected_count":904,"protected_digest":"ed52e528459f04ebdd0869f2cfe53e8cd8047138b5d684e7d18440339d37fffb","active":["docs/specs/record2/phase1c-a/spec.md"],"routing":"EXACT approved W7, no other differences","git_diff_check_exit":0}
```

W7/批准记录元数据阶段宿主墙钟09:11:49Z～09:15:46Z，3m57s；不冒充S0或候选实际工程小时，主接受成本保持单列。

## S0 dispatch contract — R2 / attempt 1

- task_id=S0；goal：在已批准W3/W5中完成隔离SDK/缓存/许可预检，正常boot并确认record-api34真实身份，为后续两个候选提供可用、受限的环境；non_goals：不创建c1/c2、不clone、不编写业务、不装APK、不运行旧Record测试。
- owner：环境worker；唯一write_scope：D:/record2-experiments/phase1c-a-20261022/{sdk,gradle-user-home,common,evidence/common/s0-attempt1}，以及已批准既有AVD正常运行状态。不得写本verification/Spec/Plan/根路由；不回滚他人改动，当前旧工作区脏状态必须保留。
- read_scope：D:/record/AGENTS.md、docs/index.md、本Spec/Plan/verification、docs/engineering/testing-strategy.md、agentic-workflow.md、E0证据；现有SDK/JDK/CLI/AVD注册只读；公共官方SDK元数据/指定归档；现有SDK许可证接受记录只读。
- skills：按精确路径读D:/record/skills/android-cli/SKILL.md；编写common规范时读skills/writing-for-agents/SKILL.md。方法不允许自动安装其他组件、清理或扩权。
- dependencies：W7-ACTIVATE已主接受（上方原始字段）；activation唯一in-progress已核验；用户明确批准W1～W7，SDK仅使用已有同意许可。实验根起初不存在。
- budget：公共有效工程工作≤1h；保存精确UTC起止、active/wait分类，不能把等候填成开发小时。预算到点或不满足强制前提立即保留证据并停止。
- expected commands：现有Java/CLI/SDKmanager帮助/版本或只读元数据检查，SDK安装仅Plan固定Platform34 r3/ext7和BuildTools34.0.0；安装前校验官方归档SHA1/许可证与既有接受记录，记录路径及下载后SHA256。若SDKmanager会选其他revision/额外组件，不能继续，可在同一授权范围内用已核验的指定官方归档准确展开，无删除覆盖。
- environment：child process ANDROID_HOME/ANDROID_USER_HOME/Java user.home/TEMP/TMP/java.io.tmpdir按Plan§3.1绑定；构建SDK与emulator只读旧SDK配置分开。先记录实际解析路径再写；若未知/无法约束白名单或许可未接受/变更则BLOCKED。不改全局env/profile，不读出密钥，不新建debug/release密钥。
- device：仅record-api34；正常boot可用E0参数，隐藏进程窗口，不wipe/no snapshot restore，先核实端口/现有进程；不重启共享ADB服务或操作真机。每个定向写前验证serial/AVD/API34/qemu/boot_completed，记录image revision/ABI和前台/包初始集合（非目标真机身份脱敏）。不装包、不清任何数据。
- raw evidence：上述s0-attempt1内commands/exit与完整失败、工具/配置摘要、许可证id/文本hash与接受hash匹配证据、下载校验、隔离sdk安装前后清单、实际缓存/tmp路径、boot/serial/API/ABI/image/加速/空间与package基线、cost、s0-result.json。输出每项PASS/FAIL/BLOCKED/NOT RUN，不能只写“环境可用”。
- blocker：任何许可/未知目录占用/重解析点/原库或真机目标/额外SDK组件/路径越界/工具需求超scope/预算耗尽，停止相关写入并报告原始原因，不隐藏或重标。禁止删除文件/目录/历史、pm clear/uninstall、AVD wipe、CI/secrets/发布操作。
- handoff：DONE/DONE_WITH_CONCERNS/BLOCKED/FAILED，包含task/revision/attempt、实际所有改变、全部命令退出码与证据路径、未验证项、环境现状/进程归属、实际成本、下一步建议；不编辑主代理日志和验收表。主代理只按原始证据接受S0，然后才可派发C1。

## S0主执行接受 — 2026-10-22

- 先前worker交接：明确无命令/写入/在途进程，active/wait=0；main从09:21:20Z接管，未重复派发。
- main接受：09:42:50.888Z；保守按全部墙钟21.515分钟计入公共1h上限，已测下载等待13.611秒单列，其余等待未假报精确有效工时。
- 原证据：D:/record2-experiments/phase1c-a-20261022/evidence/common/s0-attempt2/s0-result.json；SDK归档/安装前后清单、tool命令/exit、profile-before/after、boot身份/package基线均逐项读取验收。
- 只有Platform34 revision3/ext7与BuildTools34.0.0；Windows aapt2实际运行成功。Java17.0.20+8、ADB37.0.1、Emulator37.1.11、WHPX均实际exit0。CLI路径当前不存在，记录NOT RUN并用独立SDK能力，不影响实际ADB/emulator。
- 真实目标emulator-5560：AVD record-api34 / API34 / qemu=1 / boot_completed=1 / x86_64 / image r14。task-owned emulator PID133792；未装包，未动真机。原.android顶层文件与既有密钥元数据均无改变。
- 许可证首次检查曾BLOCKED（s0-attempt1保留），后核验：已接受24333...正文与官方9002...仅空格/换行差异，规范化文本SHA256为1268aeee833b3a62463b58bd6df146b7835a0a94d8905511223ac415fe733f57；未增加接受hash、仅复制原接受文件，旧文件不变。按同许可文本证据修复了比较工具，不接受新条款。
- boot首次脚本因config.ini等号前空格造成KeyError，未boot；原异常保留，修正键值trim后boot-attempt2 PASS。该失败为预检工具解析问题，不是技术候选结论。
- 主复核sdk仅platforms/android-34、build-tools/34.0.0及licenses；原仓库904保护路径摘要不变。C1/C2门槛仍NOT RUN，S0不代替新业务测试。

## C1-T1执行中

- owner main；开始09:46:31Z（单列实际墙钟与工具等待，C1总有效上限8h）。先建c1/AGENTS.md，再创建单app固定构建和纯金额接缝测试；source与应用尚未验证成功。
- git init仅在外部c1建立codex/record2-c1，无commit；原仓库branch/HEAD不动。wrapper来自Gradle官方v8.9.0固定tag，来源/内容hash见evidence/c1/t1-attempt1/wrapper-source.json。
- 当前命令：common/gradle_run.py c1 money-red1 :app:testDebugUnitTest；session56094；流式原始stdout/stderr与PID在evidence/c1/money-red1。首次Gradle发行包下载持续推进后完成，进入真实AGP任务；未关TLS或改变版本，未装额外SDK组件。

## C1-T2执行记录

- 继续由main串行执行；真实红阶段journey-red1：J1实际5800分落盘后缺“记事”入口，runner FAIL原文保留。实现既有六表的Note/Link DAO、Repository、编辑与选择UI、日期跳转/筛选/混合连续查询；未改Schema版本/字段、未加依赖。
- journey-green1因Compose输入框label同时参与assertTextEquals而FAIL；原EditableText及SQL正文正确。改为对正文的精确assertTextContains，未改变正文预期或放宽磁盘断言。
- journey-green2：JourneyContractTest#j1toJ5真实PASS，run c1-journey-green002；1方法、0失败/跳过，3份独立SQL快照。5800→6050同id、换行Note、1条关系、跨日连续展示和双向跳转实际通过；J6尚未运行。
- 截图取证首尝试无焦点字段而失败；实际API34改用dumpsys window（无windows子参数），t2-visual-attempt1/mixed-timeline.png已主查看：同日Note在20:10、支出58.00在12:30、两者关联数1，真实页面无截断遮挡。截图不代替SQL/runner。
- 当前继续同一APK的补充Note编辑、多对多/取消/解除、日期返回测试；尚未主接受T2。A-AC各项仍须最终完整验收，B/BK/SD实现未运行。
- 15份声明直接依赖POM/许可信息在t1-attempt1/pom-inputs；仅输入检查，不是完整已解析依赖或A-AC4通过。
- common/device_guard.py实际API34守卫/包与runId负向检查通过；C2缺少APK哈希匹配的preinstall PASS时会在命令前拒绝。尚未安装C1/C2 APK或建应用数据库。

### C1-T1后续原始进展

- MoneyParserTest：money-red1真实JUnit 2tests/2fail；money-green1 2/0/0；format-red1 3tests/1fail；format-green1 3/0/0。各原始stdout/exit及XML保留，不把初始红阶段隐藏。
- finance-ui-red1：真实API34 runner执行optionalCategoryAndInit，status[1,-2]、terminal[-1]，缺“记账”入口如实FAIL；随后实现6表Room schema1、启动绑定与实际PRAGMA校验、财务DTO/VM/UI。
- finance-ui-green1：真实保存后UI已显示“支出 -¥58.00”，测试错误使用默认精确模式匹配裸58.00而FAIL，原始二进制/JSON保留。测试改为精确完整标签，不改产品输出、不降低标准；新run重验。
- C1 APK来源/包名/权限/组件与backup三层证据在finance-ui-red-apk2/finance-ui-green-apk1。去掉未使用的自动字体/性能初始化、profile receiver、多进程Room service和虚拟ComponentActivity声明，仅保留真实MainActivity；测试包保留AndroidX Test必要活动，无网络权限。
- 已按serial守卫安装独立C1 App/test包，原包不动；首次Room选择后实际库为record2-c1-c1-t1-green001.db，先前原始观察器已经读到提交前草稿身份和实际连接。完整财务流尚待最终绿验收，A1全旅程及A3故障尚未完整运行。

### C1-T1主接受 — 11:37:08Z

- finance-ui-green4：原始runner状态[1,2,2,2,2,2,2,2,2,0]，terminal[-1]，1个预期方法完成、无failure/error/skip；3个host金额测试仍通过。
- 原始观测通过Instrumentation.sendStatus小块Base64输出，宿主完整解码4份独立SQLite快照；不是只把println留在logcat。主另外读取原始/派生文件，确认草稿预提交id==5800交易id==6050编辑后id；收入10000分、可选分类null、账户改名且1行、分类7行。
- 四方均为c1-t1-green004，对应实际canonical /data/data/com.paherdin.record2.poc.c1/databases/record2-c1-c1-t1-green004.db（Android自身private路径别名，非外部目录）；PRAGMA与生产Room/Activity绑定一致。context.databaseList仅本实验前缀，先前测试库均保留。
- 6表schema1实际KSP导出，源码/最终APK无启动provider/无后台service/receiver、无网络权限，只有真实MainActivity；测试APK必要harness组件已登记。没有SDK额外下载/组件升级。
- 时段09:46:31Z～11:37:08.927Z，110.632分钟墙钟上界；其中首次工具链命令22m38.551s（网络/首次编译/红测试混合，不能伪造精确拆分），其余各命令elapsed另保留。C1总有效8h上限不变，保守追踪而不把等待当承诺交期。
- 原仓库904保护路径摘要仍ed52e528459f04ebdd0869f2cfe53e8cd8047138b5d684e7d18440339d37fffb。完整J1～6与A3故障、A4完整许可/依赖、A5/6仍待后续任务；T1完成不等于A通过。

## 负责人技术基座选型裁决 — 2026-10-22（实验收口后追加）

- 裁决来源：负责人本次《Record 2.0 — 阶段 1C-A 技术基座选型裁决》。正式批准选择 **C1（精简 Kotlin + Jetpack Compose + Room）**，作为 Record 2.0 后续架构设计的技术基线。本节追加后续决定；上文实验结果、历史批准及当时“待负责人裁决”等记录保持原样。
- 裁决依据：C1/C2 均通过 A-AC1～A-AC6；相同业务闭环下，C1 的代码规模、依赖负担和历史功能复杂度更小，更符合路线 C 的简洁、可维护原则。C2 执行时间受复用 C1 界面、状态逻辑及测试工具影响，不能据此证明 Fork 更适合长期维护；其现有备份能力不能直接满足 Record 2.0 数据安全要求。
- 保留 C1 最终实验提交 `4b4b18af2fd4f3ea0410e394952bdebd4911d679`；保留 C2 实验及全部验证证据用于比较和参考，不清理实验文件、测试库、日志或原仓库历史。
- 批准范围仅为技术基线选择，不等于批准 C1 原型的全部实现细节，也不等于批准正式产品的完整架构；不得直接复制或合并原型进入原 Record 工程。阶段 1C-A 的完成状态不变，本次裁决不激活新的实施阶段。
- AI Agent 架构待负责人另行提供并共同评审。在此之前，不确定 Runtime、模型接入、工具调用、记忆管理及数据访问架构，不假定沿用 C1 原型接口或数据组织方式，不提前添加框架、抽象层或数据库结构。阶段 1B 将 AI 列为后续功能，不构成将其永久排除在正式产品规划之外的决定。
- 当前停止边界：不创建正式开发阶段的 Spec、Plan 或 ADR，不修改正式产品代码、Schema、Gradle 或 CI，不提交代码，不启动新技术实验，不提前实现 AI Agent、备份恢复或完整 P0。B-AC1～B-AC9、BK1～BK5 仍须后续独立实施授权。
- 下一步：等待负责人提供 AI Agent 架构设计；先联合评估产品与架构整合、模块边界及开发顺序，再决定是否创建正式开发 Spec 和 Plan。本次仅追加本文件并只读核对修改范围，完成后停止。

# G0 导出数据库访问架构复审

2026-10-26。基线：G1 PR #31，`af62eef0e66ffd29129a0120ce0aeadf1582ef37`。**设计裁决候选，待负责人批准；不是 G1 修复交付。** 授权/状态归 [Plan §35.4](../specs/record2/v1/plan.md#354-g0-导出架构复审与隔离技术验证2026-10-26)。

**后续裁决：** 负责人在轻量化复审任务中否决本页的GiB级启动空间预算。以下推荐是历史候选，不是当前生产选型；新一轮授权/决策状态归Plan §35.5，结果见 `record2-g0-lightweight-backup-review.md`。已有实测与失败数据保持原样。

## 1. 建议裁决

建议采用 **C2：系统 SQLite 创建一致的私有物理快照，再用 AndroidX bundled 的只读 step 接口生成原逻辑 ZIP**。

理由是它能把快照职责交给 SQLite，保留现有 Room/业务/导入/恢复内核，并避开活跃文件的同进程双引擎访问。代价是增加私有全库暂存、磁盘预算和清理/取消边界；这些必须正式修订 G0，不能称为既有 §7.1 的等价实现。

不是直接复制活跃 DB/WAL 文件，也不把 SQLite 文件交付给用户。ZIP v1、12 段、64 MiB ZIP、256 MiB 总解压、128 MiB 单段、8 MiB 行、500,000 行与恢复协议全部保留。

```mermaid
flowchart LR
    W[现有 Room / 系统 SQLite 业务写] --> A[(活跃来源库与 WAL)]
    A -->|系统 SQLite OS只读 VACUUM INTO| S[(私有静态全库 scratch)]
    S -->|Bundled OS只读 step| K[有界键归并与逐行载荷]
    K --> J[原12段 JSONL / manifest]
    J --> Z[原逻辑 ZIP / SAF 校验]
```

## 2. 官方能力与路线结论

固定发布源码、SHA-256、方法调用链、官方 SQLite/AOSP 链接与独立调研见 [一手来源报告](../engineering/evidence/2026-10-26-g0-export-source-research.md)。不能只用当前网页 API 名称解释当前 2.8.4/2.6.2 二进制。

| 路线 | 结果与裁决 |
|---|---|
| A：当前 Room 2.8.4 compatibility reader + DEFERRED | 不满足当前环境的业务并发/只读/大字段组合。读事务与业务写事务共用串行 TransactionExecutor；大行仍经过 CursorWindow。不是 SQLite WAL 本身不支持并发 |
| B-mixed：系统 Room + 独立 bundled 读同一活跃文件 | 生产否决。两份 SQLite 的进程内 POSIX 锁协调风险已有官方依据，不能靠一次压力测试 PASS 解除；未为此运行混合引擎破坏性试验 |
| B-unified：Room 和独立只读 reader 全部使用 bundled | 事务与大字段能力成立，可作为后备。但要改变整个正式 DB 的 driver/事务接缝；兼容 wrapper 的大查询会复制成 MatrixCursor，不能照搬。该路线扩大了本轮明确要保护的业务/恢复内核范围 |
| C1：Online Backup API | 非首选后备。当前 Kotlin API 无直接 backup handle；普通增量复制可能被连续写入重启，固定起点还需 source 读事务/JNI 与引擎所有权。不能把 finish==OK 当作已完成 |
| C2：系统 VACUUM INTO + 静态目标 step | 推荐候选。公开 API 原型已证明来源 OS 只读、复制时业务写可提交、目标一致、原生调用可中断；空间/隐私与时间窗要重新裁决 |
| D：sqlite3_snapshot_* | 编译选项、JNI、历史 WAL/checkpoint 生命周期复杂，本轮无采用理由 |
| E：继续修复原递归 MATERIALIZED SQL | 放弃作为主路线；保留原失败证据，不再探索式改写生产 BackupSnapshot.kt |
| API35 beginTransactionReadOnly | 不能覆盖现有 API34；不提高 minSdk 或增加平台双实现来掩盖问题 |

补充区别：最外层 SAVEPOINT 的原生语义确实等于 BEGIN DEFERRED，前一轮探针也证明跨窗口保持快照；但其在框架池中的显式事务簿记/连接生命周期仍依赖平台实现。本轮不把该未批准候选提升为正式主线。

## 3. 实验隔离与可复现性

- 独立分支 `codex/g0-export-architecture-review`，独立工作树；既有 G1/F1 修改不被转移或覆盖。
- 生产 Kotlin、Schema、Manifest、Room/SQLite 正式依赖版本、导入/恢复/SAF/E2 未改。仅新增 androidTest 原型与 `androidTestImplementation(sqlite-bundled:2.6.2)`，后者不进入生产 runtime。
- API34 原 AVD `record-api34` / `emulator-5594`；fingerprint `google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys`。
- 系统 SQLite 实测 3.39.2；bundled 2.6.2 实测 SQLite 3.50.1，`TEMP_STORE=3,THREADSAFE=2`。不能用 `PRAGMA temp_store=FILE` 声称 bundled 排序会落盘；每个规模场景记录首个 NOTE key 返回前的采样峰值。
- 固定 seed=42、确定性 UUID/时间/正文。规模夹具覆盖账户/分类/收支/笔记/关系五段，其余段为空；不据此声明聊天/Proposal/Operation 产品验收重跑。
- 500k 大字段组合为 **500,000 总行**（含 17 条 7,400,000 字符 memo 与 17 关系），231,833,235 B 解压、48,194,637 B ZIP，符合原产品限额。
- 独立 oracle 从造数模型按逻辑顺序逐字段编码并流式计算摘要，与 SQL 抽出的每段全部字段/行数比较。并发事务改变账户、Note 正文/排序键、删除/插入 Note、收支版本与 businessRevision；不能仅靠总行数相等过关。
- 额外在 Room 重开后提交只存在于 WAL 的必要 Note 正文；主 DB 文件中的占位值不匹配 oracle，漏读 WAL 会失败。
- 原型将有序 rowid 写入 8 B/行的私有 key 文件，再逐行查载荷，避免把完整 JSON 放进 SQL 排序。ROWID 只在同一目标内部使用，不跨源/目标复用。
- 造数与导出同进程的数值会单独标注，不能隐藏基线。fresh 模式分两个真实 PID，持久化的只是合成 fixture/oracle，第二进程才计导出；私有 JSON 指标避免 logcat 长行截断。

一条命令运行（先按项目 wrapper、JDK17、显式 `-p record2` 构建 debug/test）：

```text
python record2/tools/run-g0-export-review.py --adb <absolute-adb.exe>
  --route vacuum-system --rows 500000 --large --fresh-export --install --timeout 700
```

700s 是实验宿主的硬超时，不是产品耗时验收阈值。每次运行独立目录 `record2/build/g0-export-review/<runId>/` 保存 APK 安装摘要、设备身份、准确命令、JUnit 原文、完整 JSON 指标。JVM 注解/初版 harness 过宽等故障按真实结果保留，不计为架构通过。

## 4. 实际结果

以下 elapsed 均为导出/验证阶段，不含造数；PSS 是进程采样值，不等于 Java heap。100ms 采样与阶段边界采样不能证明瞬时数学峰值。

| 场景 | 结果 | 关键事实 |
|---|---|---|
| 原生产G1 50k+并发写，本工作树最新APK | FAIL复现 | 16.500s，`snapshot chunk gap`，1/1失败；生产源码未变，不能把原型PASS说成原P1已修复 |
| A 50k + 同实例业务 writer | FAIL | 3s 内 writer 不能提交；根因是 Room 事务执行器串行，不能归因为 SQLite WAL 写锁 |
| A 只读拒绝（修正探针） | FAIL | read API 不提供稳定的 SQLITE_READONLY 拒绝。初版把 SQLITE_BUSY 也算拒绝，已修正，旧 PASS 不采信 |
| A 50k + 17 大字段（无并发） | FAIL | `SQLiteBlobTooBigException`，不是资源阈值不足 |
| B-unified 50k + 并发 | 内容 PASS | 24.288s；峰值 PSS 74,367 KiB；正常 writer 在快照内提交 |
| B-unified 500k + 17 大字段 + 重型批处理 | 内容 PASS，资源未批准 | 191.265s；峰值 PSS 324,367 KiB，增量 226,139 KiB；批处理重写全部 17 大字段产生约121 MiB WAL增长，不能称为满足候选256MiB预算 |
| B-unified 物理只读句柄 UPDATE | PASS | 明确 `SQLITE_READONLY` 主码8 |
| C2 50k + 并发 | 内容 PASS | 21.569s；物理快照63ms，writer在复制中提交；逻辑ZIP与B同字节摘要 |
| C2 500k + 17 大字段 + 重型批处理 | FAIL | 原型等待批处理超过10s；保留 `7b9afd9b…` 原文。它不单独证明SQLite写锁阻塞，不调高阈值算通过 |
| C2 500k + 17 大字段 + 正常业务写，同进程造数 | 内容 PASS | 168.544s；物理快照1.079s；writer在复制中提交；峰值PSS264,414 KiB，基线220,275 KiB，尚不能据此宣布256MiB预算通过 |
| C2 500k + 17 大字段 + 正常业务写，独立导出进程 | 原型 PASS，落入候选预算 | 175.719s；峰值PSS179,935 KiB（175.72MiB），基线60,072 KiB、增量119,863 KiB（117.05MiB）；复制962ms，writer在复制内提交；并未放宽先前候选阈值 |
| C2 目标已写入后取消 | PASS | native OperationCanceledException；14表全字段前后摘要相等、integrity_check=ok、之后业务写成功 |
| C2 首个VACUUM返回前资源中止 | PASS | 真实FileSystemExportResourceMonitor低余量注入→signal→native中断；相同14表摘要与可写性证明 |
| C2 损坏来源、禁删库handler | PASS | 拒绝打开损坏合成库，源文件仍存在、字节摘要不变；没有调用默认自动修复/删除 |

fresh主运行 `f0a54a62f2b746bda95747ba15202a19`：prepare PID6678，export PID6748。debug APK SHA-256 `b584f7c1c1b3f33cbcd75c1f25b2c080feb1fd5542d22d243dbff573c4d12677`，test APK `53d965dbe221cee931ee13ca856702cced54a23ecb6de421a0dc9bea12ab896c`，安装后逐项相同。后续最终探针只新增采样字段和短安全验收，不把它们称为重新跑过该大夹具。

该运行：NOTE键排序开始PSS119,646KiB，首个NOTE key前166,562KiB（162.66MiB），全程峰值179,935KiB；Java heap采样峰值106,206,872B，native heap55,883,312B，不相加冒充同一时点PSS。WAL基线4,152B、峰值14,988,592B、增长14,984,440B。物理目标234,438,656B（223.58MiB），完整staging峰值518,467,762B（494.45MiB），最低可用4,048,502,784B。目标header读/写版本均1、journal=delete、integrity_check=ok、foreign_key_check零行、Room identity为Schema v1；没有借目标WAL拼接来源。

三个成功的大组合导出（B-unified重型批处理、C2同进程正常写、C2独立进程正常写）得到相同231,833,235B逻辑内容/48,194,637B ZIP，ZIP SHA-256 `1f85215dfdbf61644dbeeb614102df8af97fd84d5e979ff7bc597d93ebedcd79`，contentDigest `b997844418d1025023f86885b7d1721f8b94769c1af5325f62aa8227f457d1a7`。这验证它们捕获的是相同原始事实，不代表三者资源/结构风险相同。

无生产源码/Schema变化，原型PASS不关闭PR #31的P1。当前原型没有第二次 fresh 大组合重复、fresh普通500k单独运行、所有段的最大键分布、真机/强停/生产SAF联合验收；这些限制不隐去，也不把实验700s宿主超时当作验收预算。

### 4.1 运行回执索引

所有ID均相对 `record2/build/g0-export-review/`，各目录保存exact command、identity、JUnit与metrics。以下保留失败，不覆盖旧文件：

| runId | 内容 |
|---|---|
| `c8706b45c24f4a38ba49316b94f29f7c` | A 50k业务writer排队失败 |
| `5ff5b51d52d4474aad4ffb8cab111262` | 修正SQLITE_BUSY误判后的A只读拒绝失败 |
| `c1c99d6baad342f181b2fbef1c67d674` | A大字段CursorWindow失败 |
| `7ac154f94d7c4103a31858694baf7ab2` | B-unified 50k内容通过 |
| `6716bd1fef6149b59fb096affbe6cf70` | B-unified大组合内容通过、资源未批准 |
| `998a2cbd748141fd95c3f1a40f435a13` | C2 50k并发通过 |
| `7b9afd9b15304b2492fc19508597e0a6` | C2重型写批处理harness等待失败 |
| `cd7450e74165474cbbbbaad2bcfe8b5a` | C2同进程大组合正常写通过 |
| `f0a54a62f2b746bda95747ba15202a19` | C2独立PID大组合正常写通过 |
| `9ab06ffa202b41f5b3397577441011c6` | 最终代码的损坏source禁删库探针 |
| `69556b7158284076996e52acec161017` | 最终代码的native取消+14表全字段不变 |
| `ba0d8543af75487c9a1794a66c281066` | 最终代码的首个VACUUM返回前资源中止+14表不变 |
| `f031ef3ba278428288a1e227a3a4713d` | 最终代码C2 50k正常并发22.656s，PSS77,004KiB，WAL增长45,320B，DB/WAL/SHM与可见temp采样 |
| `c89814d3d17e491baa8fef7d83748926` | 最终代码A reader允许UPDATE，明确只读断言FAIL；不是把BUSY误作PASS |
| `eb3dffaebe8145338e8d152b03461296` | 最终代码B物理只读句柄UPDATE被码8拒绝 |

取消与资源中止两轮都得到14表摘要 `82b46246229b4adeaa4ef6f90574757b15c91d148f62c274191847fe521afecf` 前后相同；之后另行mutate成功。初版JUnit非void初始化错误与首版误把BUSY算只读的回执仍在，不纳入有效架构证据。

最终短探针 test APK `e21144a208fc843794ef79251e3dd36293c263f9581e2aa9db1bd618db701e97`，debug APK与fresh主运行相同。重新启动同一AVD并核安装摘要后，以原 `G1ExportResourceContractTest -e rows 50000 -e mode notes -e concurrent true` 执行原生产路径，JUnit确实FAIL 1/1（16.500s）；原文 `original-g1-50k-concurrent-resumed.txt`。重启前设备不在线的尝试未执行，产生0B回执 `original-g1-50k-concurrent-final.txt`，不作为复现证据。

### 4.2 构建、静态与独立审阅

- JDK17、既有Gradle wrapper显式 `-p record2 :app:assembleDebug :app:assembleDebugAndroidTest` 构建通过，最后增量test构建通过；原型源码当前版本已编译并完成上表最终短探针。
- Python runner语法编译通过；APK ZIP独立核对：debug中bundled native库0项，androidTest中4个ABI项，原型依赖没有进入生产APK。
- `git diff --check`、新文档UTF-8/围栏/新增文本空白、11个受影响本地链接/锚点检查通过。生产Kotlin/Schema/正式版本目录无diff，R1未改。
- 独立研究agent核对发布源码，并只读复核C2设计；主报告已根据反馈明确目标体积cap与启动估计边界、同卷预算、40B键布局/归并RAM与磁盘上界、采集窗可见性修订。此审阅不替代负责人设计批准、生产实现审阅或G2。
- 本轮未运行原G1全Host/恢复/AI/SAF大矩阵或release；无相关生产变化，不把历史182/182、26/26等算作本轮重跑。没有Commit/Push、GitHub评论/状态改动、合并/关闭或发布；只读核验PR #31仍OPEN、HEAD仍af62eef，issue #19仍OPEN。

## 5. C2 正式实现应满足的设计

### 5.1 来源、目标与所有权

1. 正常启动/恢复门禁完成后，持既有 export lease；恢复互斥，正常业务写不取长写锁、不停写。Room 保持对来源的正常生命周期。
2. 用系统 SQLite 独立 `OPEN_READONLY` 打开活跃来源，要求 WAL/Schema v1。设置**无删除、无重建的 DatabaseErrorHandler**：Android 默认损坏处理器可在 Java 层删除来源，即使句柄 READONLY。遇损坏/只读打开失败/恢复忙，明确失败；不 checkpoint、删 WAL、复制文件或加 immutable=1 绕过。
3. 用 `rawQuery("VACUUM main INTO ?", [privateTarget], CancellationSignal)` 的 `cursor.count` 触发。成功为0行；来源 OS只读，SQL 引擎仅写新目标。不能启用会连目标一起禁写的 query_only，也不在外面包已有事务。
4. 目标是同一 export-UUID 私有 noBackup task 的全库明文 scratch。来源和目标 canonical path/inode 不同；无符号链接、既有文件覆盖或跨应用暴露。
5. 成功完成且 cursor/来源关闭以后，验证目标独立可读、header/journal、user_version/Room identity、integrity/foreign_key 检查；只使用同一目标的12段与generation/revision。
6. bundled 只以 OPEN_READONLY/query_only 打开静态目标，绝不打开 framework 正在使用的来源。排序与载荷分离；所有逻辑排序/引用使用正式 ID。目标内部临时 rowid 不能继承来源 rowid。
7. 后续 ZIP/manifest/hash/SAF 流程保留。导出事实不变、receipt不写、AI/Run/草稿不改。最终交付只有原逻辑 ZIP。

### 5.2 预算、时间与取消

- 物理源体积不受256MiB逻辑上限约束：全部14表、索引、非EDITING草稿、ai_control与旧receipt也会进入私有scratch。不得拿逻辑上限当物理预算，不把这些排除内容写进ZIP。
- 以只读 `page_count*page_size` 获取当前来源估计 `S`，它不是VACUUM实际快照体积的证明；WAL增长限额也不能推出目标必定≤`S+384MiB`。建议设置独立目标工作配额 `T_cap=S+384MiB`，持续按实际目标体积中止超额复制；达到配额是资源失败，绝不把超额文件当成功。不能承诺在并发增长/低空间条件下一定完成。
- 建议保守启动预算明确为：`R_out=T_cap+K_disk+256MiB段+64MiB ZIP+64MiB保底`，`R_source=384MiB WAL增长余量+64MiB保底`。分卷分别检查，同卷要求 `R_out+R_source`（两个保底都保留）。其中 `K_disk=64MiB` 仅在下一项具体排序布局成立时可用。此公式是待批启动预算，不是完成空间保证；复制/提取期间还必须监控真实目标体积、两卷余量、源DB/WAL增长并可中止。本夹具按S约224MiB计算，同卷约需1.47GiB启动余量，显著高于原128MiB staging前置，必须明确接受此成本。
- 复制前及复制期间独立监控实际WAL、源/staging余量、目标体积和墙钟；越界调用signal，并等待原生工作、cursor/连接退出，再清扫和释放lease。磁盘写错/取消/强停的目标一律无效，绝不续用或报成功。
- WAL只在复制活库的阶段被此读者钉住；来源关闭后从静态目标提取，正常业务WAL无需为后续逐行JSON/ZIP而保持历史版本。
- bundled `TEMP_STORE=3` 且无公开interrupt/progress。生产明确采用**无全局ORDER BY的键扫描+固定run外部归并**，不直接移植原型的键SQL排序。每条键记录固定40B（5个Long）：UUID经canonical验证后编码为16B，数值键保留有符号64位，末尾rowid及padding；最大LINK键为两个UUID+rowid。比较器对UUID字节作unsigned顺序、对时间/sequence/ordinal/slot按正式数值顺序，不按序列化字节误排负时间。
- 每4096条键用固定primitive数组原地排序成run，fan-in≤16；500k总行跨12段的初始run≤134，下一轮run≤20，再生成8B/行rowid spool。即便所有阶段文件都保留到task cleanup，数据上界为 `500000*(40+40+8)=44,000,000B`；总文件≤166、每文件header≤128B，目标卷分配块≤64KiB时连同块取整小于55,000,000B，余量后 `K_disk=64MiB`。启动时核目标卷块大小，布局/文件数/分配粒度不满足时重新计算预算，不能照用64MiB。固定run数组约160KiB，最多16个64KiB输入buffer及固定heap/输出buffer；排序RAM预算 `M_sort=4MiB`，与K_disk分开计算。该算法是**待实施方案**，必须用实际布局/分配测量、最大LINK/数值/相同时间测试证实上界；若实现不能守住界，先复审，不调阈值。
- 每次键扫描step只取固定有界键，逐行载荷仍受8MiB合同；在step/行/缓冲写入边界响应取消。不能承诺bundled单次同步native调用被Job.cancel即时打断。本原型证明事务/驱动路线及参考分布资源表现，尚未实现上述生产外部排序。
- `exportedAt` 保留现有字段，定义为采集开始Clock；内部记录采集起止窗。真正generation/revision与事实来自完成目标，不把复制前/后的活库元数据拼接进manifest，也不把复制结束时间冒充精确内核快照时点。
- 这明确替换旧“exportedAt就是精确快照瞬间”的可见语义：采集窗开始前提交必包含、采集窗结束后提交必排除；窗内提交可落在完整前态或完整后态，不能混合。§10.4B需据此替换边界断言，不能把开始Clock当作所有并发写的排除点。
- 物理scratch只在当前成功进程消费，不承诺断电持久；强停后startup sweep丢弃，不从它恢复。staging失败上报/完成屏障仍是生产改造必须收口的P2。

### 5.3 候选验收预算与后续门禁

沿用前轮尚未批准的参考候选值，不因失败抬高：同一AVD普通500k≤180s，500k+大字段≤300s；完整进程PSS≤256MiB、相对导出前增量≤128MiB，首个主要key返回前单独记录。这些不是已批准真机预算，不能把某次内容PASS称为资源验收PASS。

批准设计后才授权生产改造；在关P1前仍需真实生产路径的50k失败回归、多窗口/段间持续写入、最大合法包/大字段/各段排序形态、WAL/余量/取消/强停/清理、全事实比较、Host/G1/必要storage-AI/SAF回归。不得重写导入/恢复内核或挪用其历史PASS。真机/G2仍按原门禁。

## 6. 待批准事项

接受C2作为唯一主路线，以及G0 §7.1的“引擎创建私有物理scratch→只读提取逻辑ZIP”修订、全库明文暂存/磁盘预算/时间窗/取消所有权；保留现有限额与所有恢复不变量。G0与ADR 0019的修订标为Proposed，负责人批准前不生效，也不授权修改生产导出器。

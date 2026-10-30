# G0 轻量化备份最终架构验证报告

后续S选型批准与受控实现授权已记录于[Plan §35.7](../specs/record2/v1/plan.md#357-s架构批准与受控g1实施2026-10-30)。本报告保留当轮实验事实与未通过项，不转为生产验收PASS。

2026-10-30 · **Draft / 待负责人架构裁决；生产准入未通过**。授权归[Plan §35.6](../specs/record2/v1/plan.md#356-s优先的最终架构验证2026-10-30)。本报告只形成候选与准入证据，不是Accepted决定，不授权G1生产改造。基线G1 PR #31 `af62eef0e66ffd29129a0120ce0aeadf1582ef37`。

## 1. 决策问题与证据规则

本轮只在S与U之间收敛。S保留现有系统Room和恢复事务，用系统引擎创建一致私有scratch，以系统只读scalar提取，标准库写逻辑ZIP；U只核对官方发行物，不再制作新U原型。旧C2的全量JSONL和`S+1280MiB`预算均不采用。

**PASS/FAIL**必须注明证明对象：内容/故障断言通过不等于耗时或内存达标，更不等于生产P1关闭。尚未执行或受环境阻挡的项目为**NOT RUN**。历史原型仅作背景，不能算本轮运行。本文区分D官方文档、S固定源码、E本轮实测、I推断、H尚未验证。

## 2. S的数据流与安全边界

```mermaid
flowchart LR
    R[现有Room系统writer] --> O[(源DB与WAL)]
    O --> C[系统OPEN_READONLY / VACUUM INTO]
    C --> P[(私有静态scratch)]
    P --> K[固定键扫描 / 有界归并 / scalar当前行]
    K --> Z[ZipOutputStream JSONL / manifest-last]
    Z --> V[原G1严格读取器与validator]
    V --> D[关闭scratch / 回收S与K]
    D --> S[原SAF写入 / 关闭 / 回读]
```

- 来源独立OS只读，使用抛错且禁止自动删除/重建的DatabaseErrorHandler；不checkpoint、不复制活跃DB/WAL/SHM，不取得Room写事务作快照。VACUUM允许写新目标，因此不能对整个连接设置会禁止attached目标写入的query_only。
- 来源WAL、Schema身份、正常启动与export lease仍为正式前置。采集成功并关闭来源后检查目标header/Schema/Room identity、integrity及FK；所有12段与generation/businessRevision只读同一个目标。
- 采集时间窗前提交必含、窗后提交必排除；窗内可含完整前态或后态，不虚构内核精确时点。exportedAt是采集开始Clock，事实版本来自目标。
- 单行正文用compiled scalar读取，无CursorWindow大正文/MatrixCursor；排序只扫描固定键。原型每4096键生成40B记录run，fan-in≤16，最后8B/行定位spool，无全量JSONL或全量载荷排序。
- SQL json_object按冻结字段顺序序列化，当前行计字节/CRC/SHA。私有ZIP关闭后原严格reader/StreamingBackupValidator逐行及跨表验证，之后才允许交付；不以SQL序列化替代校验。manifest-last、descriptor、空段STORED/0/0保持；100:1违规则在同一静态目标最多重读一次，仅相关段STORED，仍超ZIP上限整次失败。
- 完成ZIP验证后先关闭并回收S/K，再做原SAF写入/关闭/回读。采集/提取/校验/交付任一步异常或取消都不成功；强停scratch一律弃置，不在新进程续用。恢复继续为原G1单Room事务、资格转换及receipt，无物理恢复协议。

## 3. 动态空间模型：估算、工作cap和实际峰值

同卷F暂保留既有64MiB余量，只计算一次。D是既有源库，不计成新增副本；S、Q、K、W、Z的含义沿用本轮任务书。按生命周期还应纳入SAF交付阶段：如果用户目标落在同一卷，私有ZIP与目标文档可能并存。

```text
P_copy = max_t(S_copy(t) + Q_copy(t) + W_copy(t))
P_extract = max_t(S + Z_private(t) + K(t))
P_deliver = max_t(Z_private + Z_provider(t))    # S/K已回收
P_same_volume = max(P_copy, P_extract, P_deliver)
R = 本次工作预算 + F                         # 分卷则分别检查
```

SAF provider是否同卷/是否有空间不总可查询，不能把URI当普通本地路径做StatFs。仍必须以写/关闭/回读结果判成功；原协议已允许失败时用户文档残留，应用不得把它列为有效备份或擅自删除既有外部文档。

**实验性准入，不是已批准生产常量：**

```text
E = 来源(page_count - freelist_count) * page_size（只作估计）
S_work = E + max(1MiB, E/10)
Q_work = 1MiB
W_work = min(原384MiB上限, max(1MiB, E/16))
复制前估算 = S_work + Q_work + W_work + 64MiB

N = 静态目标内本次12段实际总行数
K_bound = 88*N + (2*ceil(N/4096)+48)*本卷块大小
Z_work = min(原64MiB上限, 当前可用 - 64MiB - K_bound - 64KiB)
```

88N来自最多两批40B键run和最终8B定位文件；小于等于500k的记录最多一轮中间归并即可降到fan-in16。块取整另计，固定run工作对象≤4096，不以大正文参与排序。该布局及原型实际文件峰值须一起检查，不照搬旧64MiB键常数。

**必须保留的限制：** S/Q/W监控是采样后取消，不是文件系统硬配额；不能把`S_work/Q_work/W_work`写成绝不超调的数学上界。来源页估计与VACUUM真实快照之间可能有新事务，其他应用也会占盘。新预算可以在资源耗尽时fail-closed，但尚不能承诺任意并发写入下必定完成。没有证明的Q上界、原生调用超调和真实ENOSPC必须单列，不能用“样本很低”取消F。

补测在唯一合成库使用INCREMENTAL布局构造空闲页：源68,222,976B，freelist67,174,400B，存活页1MiB，目标962,560B，复制准入68MiB，完整往返PASS。这不是生产配置修改或最低可用空间验收。2MiB unlinked-FD正向对照也PASS，不等于证明所有Q硬上界。

## 4. 夹具、原型与观测口径

原型仅位于androidTest：`SFinalFixture`、`SFinalEngine`、`SFinalMonitor`、`SFinalPrototype`。runner为`record2/tools/run-s-final-prototype.py`，矩阵为`run-s-final-matrix.py`；使用同一API34专用AVD、唯一合成库、造数与导出不同PID。

1k/30k/50k/150k均为有效混合历史，包含12段、软删Note/隐藏关系、EDITING草稿、RUNNING/COMPLETED、EDITABLE/LOCKED/APPLIED、Pending/Applied、消息及引用。普通正文/memo/payload取前轮模型量级；重度使用更长Note/AI/Proposal。每31或34行成一有效业务组合，Proposal确认率与session分组不同于容量模型的均值，报告不把它们称为用户统计或精确五年重放。

500k+17×7.4M memo是原边界兼容试件：保留固定混合AI历史，其余用短Note填充，以免把“行数上限+大字段”误变成超过256MiB的另一问题。另以7M重复正文Note验证scalar和100:1回退。极端夹具不是普通/重度分布。

独立期望由Kotlin业务行模型生成；以每行完整规范编码的SHA256、行数、XOR和256位模和比较全部字段，不保留正文集合。最终reader另按正式数值/UUID文本键核对顺序，原validator检查引用/状态；不只比行数或让导入器自行生成期望。恢复后重新读取全部12段，按精确资格转换比较所有字段，并核对OFF/无grant。

准备进程把必需Note正文写成checkpoint占位；导出进程先在WAL写回模型正文。漏读WAL将不匹配独立oracle。源库在无并发时核对DB/WAL整文件SHA；有持续业务写时，按目标businessRevision选择完整模型，并把活库最终14表与已提交writer序列独立比较，不把正常业务写误称导出污染。

文件每5ms观察目录及`/proc/self/fd`的inode、logical size与allocated blocks，包括已解除目录链接的SQLite temp；PSS/Java/native每100ms另线程采样。采样无法证明更短瞬时峰值；Java/native峰值不同步，不相加冒充PSS。恢复审计发生在导出计时/资源采样后；restoreMs包括预检、恢复和独立逐字段审计，不冒称纯COMMIT耗时。

[源码身份与版本差异](../engineering/evidence/2026-10-30-g0-s-source-provenance.json)保留性能矩阵与最终预算/Clock补测的区别；后续版本没有重跑五档。

## 5. 本轮结果

原始命令、身份与指标见[本轮实验索引](../engineering/evidence/2026-10-30-g0-s-final-results.md)。下面全部为本轮API34合成原型，五档均通过独立字段oracle、原G1预检/恢复、恢复后全字段及精确资格转换核对；**内容PASS不等于性能PASS**。

| 档位 | 逻辑 / 物理S / ZIP MiB | 导出 / 采集秒 | 峰值PSS / 增量MiB | 私有allocated峰值MiB | 复制前准入估算MiB |
|---|---|---:|---:|---:|---:|
| 1k混合+SAF | .583 / .918 / .078 | 3.147 / .025 | 103.789 / 43.022 | 1.215 | 67.922 |
| 30k普通 | 26.637 / 31.457 / 3.751 | 36.403 / .122 | 110.676 / 49.905 | 36.746 | 102.395 |
| 50k持续多表写 | 44.416 / 52.324 / 6.249 | 67.033 / .172 | 109.602 / 48.843 | 61.055 | 127.194 |
| 150k重度AI | 219.797 / 258.879 / 35.446 | 253.766 / 8.068 | 93.007 / 32.145 | 301.488 | 369.879 |
| 500k总行+17大字段 | 217.891 / 220.785 / 35.288 | 274.743 / .769 | 140.164 / 79.298 | 298.695 | 329.060 |

1k的SAF写/关闭/回读另占209ms，计入其导出总时间，交付前回收1,010,560B的S/K。其他大规模SAF输出本轮NOT RUN；不将私有文件往返当作全部Provider验收。

50k采集期间有3次多表事务提交；全程2231次业务事务，最大延迟44ms，ZIP仍为revision=1的完整前态，活库最终14表匹配已提交writer独立模型。每次事务修改交易、Note正文/排序、关联、revision，并创建新Run/结束旧Run。这里验证真实Room事务，不冒充重跑所有Agent Owner生命周期。

500k的K为43,979,600B，确实执行了中间归并；各档Q采样见512B journal，K及S/Z的allocated量与logical长度分别记录。7M重复正文的补充用例PASS：两遍回退、ZIP7,105,715B、全部字段恢复，未放宽100:1。

| 前轮候选目标（未获正式批准） | 本轮判定 |
|---|---|
| 小包≤5s / PSS≤96MiB / 增量≤32MiB | 时间PASS；PSS及增量FAIL |
| 普通≤15s / PSS≤128MiB / 增量≤64MiB | 时间FAIL；内存PASS |
| 重度≤120s / PSS≤160MiB / 增量≤96MiB | 时间FAIL；内存PASS |
| 原边界候选≤300s / PSS≤256MiB / 增量≤128MiB | 本夹具PASS；不是生产或真机PASS |

重度提取/写ZIP阶段约69.3s，最终严格读取/语义核验及独立oracle约136.8s，另有排序16.7s和目标检查等开销；不是采集本身占全部时间。性能计时包含测试oracle与监控，不能扣除猜测成本后宣布达标，也不能推断换U就会消除共同的校验CPU成本。

**小用户的实际答案：** 此1k混合夹具私有新增工作峰值约1.2MiB；保留F=64MiB后，原型复制准入约68MiB，未要求GiB。但复制前估算不是整次成功的最低空间保证。ZIP熵未知、后续K配额及同卷交付还会影响阶段准入；重度按已知Z与K_bound计算的提取预算会略高于复制估算。真正低可用空间下的完成边界尚未通过，不能把68MiB写成生产安全承诺。

已保留早期1k/30k基线及其资源未达标事实。30k基线57.137s；去除原型为oracle重复解析/重编码后，同数据36.403s，原reader/语义validator仍完整执行，旧结果不覆盖。15s普通档草案目标仍FAIL。初版小包PSS超过96MiB目标，不提高阈值算PASS。

## 6. 取消、强停与存储故障

四阶段取消PASS：copy产生真实SQLITE_INTERRUPT，提取/ZIP/最终读取在对应检查点取消；14表及DB/WAL字节不变、无成功标记、清理后可写。提取/ZIP/最终核验的真实kill及新PID核对PASS。

首次50k copy kill **FAIL（未命中阶段）**：验证器发现copy已返回，明确拒绝把它算作采集中强停；失败原文保留。150k重度夹具补测的实际kill与后续核对另列索引，不扩大超时、不修改断言或用更小数据掩盖。

目标cap、WAL cap、低余量注入、ZIP输出配额、静态损坏、权限失败均PASS：前三类触发native中断；WAL用例按已提交业务写的14表模型核对，其余源14表/DB/WAL不变；均无成功并清理、后续可写。配额中断是软监控，实际目标可超过16KiB测试cap后才中断，不能当作硬文件配额证明。

孤儿清理调用现有BackupStaging.sweep于隔离root，验证失效scratch被删而非续用；**生产Application异步清扫的完成屏障/failed日志集成仍NOT RUN**，不得以本轮局部清扫替代该P2。

低空间实验的安全边界：已尝试在唯一合成私有目录挂载1MiB限容tmpfs，API34 SELinux返回Permission denied，未挂载、未填满设备、未关闭SELinux，空目录已清理。**真实文件系统ENOSPC：NOT RUN**。输出字节故障/余量注入与真正ENOSPC分别记录，不能互相冒充。

150k采集强停补测已在环境恢复后核对PASS：killedPID8500→verifyPID1597，源14表不变、copy返回检查点未到达、孤儿删除且后续可写。最初中断与恢复原文全部保留，未把50k首次失败改写为通过。

## 7. U正式发行物审查

本轮重新下载Google Maven元数据和最高稳定版2.7.1 AAR；四ABI均含SQLite3.50.1与完整原版source-id，AAR SHA与前轮相同。没有取得官方修复回移证明，因此U生产阻断继续。静态字串不能排除保留旧source-id的下游补丁，也不代表观察到损坏；判断是缺少已修复的正面证据。

[SQLite官方WAL说明](https://sqlite.org/wal.html#the_wal_reset_bug)列出修复版本；[3.51.3发布记录](https://sqlite.org/releaselog/3_51_3.html)给出含修复的源码身份。AndroidX版本号与其打包引擎不是同一个版本；[AndroidX发布记录](https://developer.android.com/jetpack/androidx/releases/sqlite)不提供2.7.1已含该修复的依据。具体二进制身份、public API和下载SHA归本轮official证据。

Room2.8.4的迁移范围仍为34处legacy withTransaction、9个storage文件、1处Cursor PRAGMA及4个平台活库读取接缝；生成DAO不需整体重写，但FK延迟、取消/回滚、receipt未知提交、Agent/业务CAS与启动读取都需T3回归。2.7.1的Android同步接口仍存在是静态兼容依据，不是已跑过Record迁移的证明。新增单ABI原始native约1.2MiB、四ABI APK约4.8MiB为前轮2.6.2探针，已修补发行物体积须另测。本轮不引入生产依赖或私有分叉。

系统SQLite3.39.2也不能仅凭版本号宣布已修复。S新增来源连接是OS只读，不配置WAL truncate/autocheckpoint，也不主动checkpoint；但现有framework连接初始化含maybeTruncateWalFile，不能只凭“一个业务writer”就证明所有checkpointer来源。此为调用形态/风险分析，不是OEM补丁证明；详见[本轮官方审计](../engineering/evidence/2026-10-30-g0-s-official-audit.md)，生产平台风险不得对S免审。

## 8. S/U最终比较与唯一建议

**唯一建议的V1架构方向为S；当前不建议宣告生产准入通过。** 它已证明在当前API34和有效混合/最大边界上能够一致采集、保持并发、完成可恢复逻辑ZIP，普通用户无需GiB门槛；既有Room事务和恢复内核可保留。其代价是随实际物理数据增长的一份scratch，重度样本约259MiB，必须如实告知/预算。

| 成本 | S | U |
|---|---|---|
| 新生产SQLite/ZIP依赖 | 0 | 新driver/native；本轮无审过修复的稳定发行物 |
| 核心存储接缝迁移 | 不需要 | 34处事务及4个活库读取等，须T3回归 |
| 工作空间 | S+ZIP+键，按阶段回收；普通样本约37MiB私有峰值 | 省S，但源WAL被较长读事务保留 |
| 性能 | 普通/重度草案耗时目标FAIL；本轮具体数值可审核 | 本轮不重跑；旧短Note数据不能抵扣新混合历史目标 |
| 维护/平台风险 | 依赖系统引擎，补丁/CP所有权仍需审；Q与软cap超调待闭合 | 新发行渠道/补丁身份阻断，且扩大已验收内核回归范围 |

不推荐为节省几百MiB而在当前无合格发行物时迁移U，也不回到旧C2或停写全程导出。**如果“满足15s/120s与已证明低存储上界”是本次正式选型的必要条件，则结论是目前没有一个候选完成准入，不能批准S。** 这与建议将后续有限工程投入收敛到S并不矛盾：方向可收敛，未过的安全/产品门禁不能改称PASS。

## 9. G0与ADR具体修订草案（Proposed，不生效）

- **G0-D2 / §7.1第3～7项 / ADR决定2：** 将API34单语句游标/BEGIN字面机制替换为“系统OS只读来源，禁止自动删库handler；由VACUUM INTO一致采集到私有新文件；来源成功关闭后，系统只读静态提取12段及data_state；正常writer不停写”。不得改成活跃文件copy、Room写事务或同一活库双引擎。
- **G0 §7.1第5～6项、§10.4B：** exportedAt改为采集开始Clock，正式采用采集时间窗可见性；测多表事务完整前/后态和revision绑定、真实WAL必需值、copy期提交、阶段取消/kill及失败后可写。原P1回归仍必须经未来生产路径执行。
- **G0 §6导出启动空间 / ADR决定6引用：** 保留ZIP/行/比值/WAL正式硬限额及F，128MiB固定导出启动式改为按阶段/实际数据量的经批准工作预算；同卷F一次，动态ZIP配额，不再预留全量L或叠加双384MiB。未证明的Q/超调和真实ENOSPC门禁不能自动删去。恢复空间公式不改。
- **G0 §7.2 / §9：** 保留原独立ZIP核验及SAF关闭回读；新增核验后关闭/回收S/K、交付峰值及失败清理所有权；全库scratch可能含排除的控制信息，只用于当前私有操作，不交付、不记录、不系统备份，强停重启弃置。
- **ADR决定1、3～6与G0 §8：** 格式版本、12段、严格校验、单事务恢复、资格转换、receipt、AI/Key排除全部保留；数值上限本轮不改。旧Rev3 bundled静态读取/全L/固定预算提案作为历史，不并入新决定。

## 10. 若获批准的实施与验收顺序

1. 负责人只裁决架构/预算语义/支持目标；未批准前不改生产。单列资源未达标与未知项，不以架构选择关闭P1。
2. S采集port单独接入：系统RO/来源保护/取消等待/目标身份/采集窗/资源观测；保持Room factory、所有业务事务和恢复内核不变。
3. 接入标准流式ZIP和固定键工作集，保留原严格reader/validator、12段/硬常量和SAF端口。关闭验证后的S/K再交付；startup sweep建立与新任务创建的完成屏障并记录failed列表。
4. 经实际生产port重跑50k原失败回归、五档混合/极端、逐字段oracle、WAL/余量/输出/损坏/权限、四阶段取消及真实kill；补齐本轮NOT RUN的真实ENOSPC与资源超调。资源目标未达标仍FAIL，不上调测试值换绿。
5. Host/G1/必要storage-AI/SAF及强停回归，build isolation/生产依赖/Schema冻结检查；真机、DocumentsUI、F1/F2/G2沿原门禁。全部证据审过后才讨论PR #31/issue #19，不提前合并/关闭。

## 11. 尚待裁决

1. 是否选择S作为唯一后续改造方向；这不等于关闭P1或授权立刻开发。本轮普通/重度耗时未达候选目标，须明确产品接受标准，并经后续实际路径验证；本报告不提高阈值换PASS。
2. 动态预算采用分阶段准入还是低空间时额外测量压缩大小；后者多读一次。工作cap如何扩容/拒绝需明确，不能把实验S/W cap直接变成降低旧硬能力的新常量。要去掉历史freelist空页的无效预留，也不能忽略实际非导出数据。
3. 真正低存储ENOSPC、Q的OEM边界、采样超调、有效/越界大字段分配上界、系统SQLite补丁/CP来源仍需闭合。技术未知不是负责人同意后就能当PASS；旧500k硬上限继续有效。

生产G1、Room driver/事务、Schema、格式与硬常量均未改；未Commit/Push、标Accepted、合并PR #31或关闭issue #19。**停止在负责人架构裁决节点。**

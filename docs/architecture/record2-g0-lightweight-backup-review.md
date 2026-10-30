# Record 2.0 轻量化完整备份与恢复架构复审

2026-10-26 · **Draft / 待负责人裁决** · 基线 G1 PR #31 `af62eef`。本轮授权/状态归 [Plan §35.5](../specs/record2/v1/plan.md#355-轻量化完整备份架构重新评估2026-10-26)。只研究、测量和编写隔离原型；不授权生产开发、正式限额修改或Accepted决定。

## 1. 执行摘要

**当前问题是接口选择、数据路径过度物化和没有产品规模依据的预算叠加共同造成的。数据安全本身不要求GiB级启动空间。**

推荐待批架构方向为 **U：统一受支持SQLite驱动的原生读事务 + 标准库流式逻辑ZIP**，但以取得可核对修复、维护责任明确的发行物为前提；**本轮没有审出可直接批准用于生产的U版本**。保留 **S：系统引擎一致采集 + 系统只读静态提取 + 相同流式ZIP** 作为迁移成本较低、但多一份数据库的替代。旧C2的“完整DB + 全部JSONL + ZIP + 双384MiB预留”不再推荐。不是继续给旧C2预算减常数。

选择U的理由：普通/重度长年数据主要是必须保留的正文与解释历史，没有必要再复制整个数据库；一个成熟驱动负责事务/step，Java标准库负责ZIP，原导入、原子恢复、receipt与资格转换继续保留。代价是新增native安装体积与明确的存储接缝迁移，不能只加setDriver就上线。

关键新证据：

- 现有G0 §3.1和真实读取器**已经允许ZIP数据描述符、manifest最后写**。完整JSONL staging不是格式要求。平台ZipOutputStream生成的新包已经通过未改动的G1预检/恢复。
- 50,000总行的新U单遍原型：导出25.161s，PSS采样峰值111.43MiB、基线60.81MiB；私有工作文件峰值**2.44MiB**，另计真实WAL增长12,360B。该夹具正文只有40字符，不冒充真实普通五年模型。
- 同Schema假设模型：普通五年约27,369行/22.07MiB逻辑数据；重度约123,795行/210.57MiB。模型不是用户统计。重度Proposal+Operation占53.9%，不能把AI解释历史当缓存清除。
- 原生产导出P1仍开放。原型通过不意味着PR #31可合并，不抵扣真机/G2或正式资源验收。

证据分类：**D**官方公开文档；**S**固定版本/commit源码观察；**I**源码推断或模型；**E**实际运行；**H**尚待验证。下文未标实测的预算/实现计划都是待批准设计。

## 2. 用户究竟备份什么

依据 [Spec §6](../specs/record2/v1/spec.md#6-完整逻辑备份恢复与代际)、[B0](record2-b0-data-model-design.md)、[Schema v1](../../record2/app/schemas/com.paherdin.record2.storage.RecordDatabase/1.json) 和真实字段/服务：

| 数据 | 处理 | 原因 |
|---|---|---|
| 账户名称/分类、收支、正常与软删Note、全部隐藏/可见关系 | 完整保留身份/版本/正文/时间/关系 | 是用户事实；软删除不等于可丢缓存 |
| EDITING原生草稿 | 原格式完整保留 | 不能消费为正式流水；SAVED/DISCARDED及saveReceipt不属于该导出段 |
| Session、用户可见Message、Run状态与引用 | 完整保留 | 用户历史和解释依据；运行资格在恢复时转换，不删除历史省空间 |
| Proposal/CommitOperation及冻结输入、Applied结果映射 | 保留既有协议 | 解释“为什么发生/是否已应用”的权威历史，不按年龄自动清理 |
| ai_control有效grant、Key/Keystore、permit/owner执行资格 | 不进入ZIP；恢复重建/失效 | 不是可迁移的授权能力；既有AI安全合同不变 |
| Koog上下文窗口/checkpoint、Trace、日志、界面缓存 | 不备份/可重建 | 当前14表没有一个可一键删除的大型“纯缓存表”供省体积 |
| 索引、Room元信息、WAL/SHM、数据库物理页 | 不作为用户交付格式 | 可由正式Schema重建；S路线的内部物理scratch可能含它们，须私有清理 |

**S：** 普通Answer只保存一份body，resultJson默认null；ProposalReady/ClarificationNeeded使用小信封。不能假定全部AI回复在resultJson再复制一份。反过来，Proposal creation/current payload及Operation frozen payload确有多份持久化文本。

**S：** AI Proposal限制memo≤1,000字符、Note≤20,000字符、payload≤64KiB；原生Note/Finance路径未见等价正文硬长度限制。17条7.4M字符memo是合法边界压力试件，不是普通AI产出。若未来承诺严格固定内存，还需独立裁决原生长字段边界；本轮不改它、不截断已有值。

## 3. 现有成本审计：四种“大小”必须分开

完整算术、文件SHA与口径见 [成本审计](../engineering/evidence/2026-10-26-lightweight-cost-audit.md)；运行脚本 [audit-lightweight-backup-costs.py](../../record2/tools/audit-lightweight-backup-costs.py)。

### 3.1 包体与安装

| 实物 | 字节 | 含义 |
|---|---:|---|
| 当前debug APK | 38,066,137 | 本工作树正式app编译产物，无bundled SQLite |
| 当前release未签名APK | 30,826,338 | 约29.40MiB；构建未启用R8缩减，非已发布安装包 |
| 当前release AAB | 10,974,594 | 约10.47MiB上传容器，不是设备实际下载/安装总量 |
| 同配置Room/Kotlin基线探针，加入bundled的universal APK增量 | 4,996,894 | 约4.765MiB，四ABI和少量接口；不是完整正式app精确增量 |
| 同探针AAB容器增量 | 2,522,001 | 约2.405MiB，仍不等于单设备split下载 |
| bundled单ABI原始ELF | 1,159,896～1,310,776 | 约1.11～1.25MiB；设备split通常只需匹配ABI，另计少量DEX/安装优化 |

两probe都含相同Room2.8.4/Kotlin2.3.10，避免把已有stdlib算作driver新增。当前正式release DEX约30.37MB，现有native仅37,392B；主要APK成本不是备份native库。是否评估release shrink/AI依赖瘦身应另立任务，不能在本轮顺手改生产配置。

**E：** API34当前debug代码目录du为37,204KiB，只含该安装代码目录；未把用户dataDir/全部系统DEX优化算进来，不能当release安装总量。

### 3.2 内存与时间

**E：** 当前MainActivity只有setContent空host，不是F1正式界面。实际启动后PSS73.10MiB；新原型instrumentation进程基线约60～62MiB，两者不可互换为最终App基线。Java/native heap与PSS也不可互换或把不同时间峰值相加。

旧原始生产路径：500k普通Note导出717.191s、PSS约835MiB；50k并发写报snapshot chunk gap；大字段原查询曾30分钟未完成。**S：** 原导出用全量JSON MATERIALIZED、递归CTE、全局排序；CursorWindow重填会reset语句，Java游标寿命不是隐式SQLite快照寿命。正确拒绝坏序列没有保证能可靠完成备份。

旧C2 fresh大组合：复制0.962s；提取/目标校验等到119.271s；ZIP写到137.305s；最终核验到175.719s。PSS采样峰值175.72MiB，Java heap峰值106,206,872B、native55,883,312B；这些不是同一时刻的可相加数值。

旧流式导入证据：普通500k恢复222.703s、大字段247.545s；约65MiB是**恢复后TotalPss**，不能改写成全过程峰值。本轮没有重写或重新跑这套最大恢复矩阵。

SQLite cache配置、排序、当前行、zlib及映射页各有内存；native heap不是“SQLite缓存大小”。本轮能测PRAGMA配置与进程采样，未得到每个pager/allocator的独立实时占用，不伪造这个分解。排序仅投影键/rowid能显著减少工作区，但原型仍不是任意键分布的硬RAM证明。

**E：** 最后1k审计运行两路径均page_size4096、cache_size=-2000（名义约2000KiB页缓存目标）、mmap_size=0；native managed路径temp_store=2，系统静态路径temp_store=0。它们是连接配置，不是实际驻留峰值/硬内存cap。U所测bundled编译TEMP_STORE=3，排序可能在native内存中，不能用FILE PRAGMA虚构落盘保证。

### 3.3 为什么494MiB实际峰值变成1.47GiB启动门槛

旧C2大组合100ms采样值：

| 并存内容 | 字节 |
|---|---:|
| 一致完整物理副本S | 234,438,656 |
| 全部JSONL L | 231,833,235 |
| 完成ZIP Z | 48,194,637 |
| rowid key文件K | 4,000,000 |
| fixture元数据 | 1,234 |
| 合计 | **518,467,762（494.449MiB）** |

真实writer还会短暂保存当前段.deflate并和ZIP前缀并存。采样峰值/上表余数不证明它的瞬时峰值为0；代码模型应写成 `max_t(S+L+K+ZIP_prefix(t)+deflate_tmp(t))`。

旧启动公式同卷为：

```text
R_out = S + 384MiB目标增长 + 64MiB键 + 256MiB段 + 64MiB ZIP + 64MiB保底
R_source = 384MiB WAL增长 + 64MiB保底
R_same_volume = S + 1280MiB
```

因此S约224MiB时为1.468GiB，S接近零也要约1.25GiB。**这是产品缺陷，不是安全备份的天然成本。** 它叠加了实际未发生的两个384MiB峰值、以全局上限代替本次数据量、全量L、64MiB键暂估及同卷两份保底；还没有按阶段复用空间。

真正减少开销的变化：U移除S；两候选都移除整批L与deflate文件；K按实际N计算，ZIP用标准流；按同一卷/同一时刻并存量和本次工作预算准入。**保留完整性校验/既有64MiB卷余量监控，不靠简单调低检查阈值制造通过。** 不能仅删预留而不约束输出、WAL与失败路径。

## 4. 一年、三年、五年数据增长

完整参数、精确公式、逐表贡献、校准与敏感性见 [容量模型](../engineering/evidence/2026-10-26-lightweight-capacity-model.md) 和 [可复算脚本](../../record2/tools/lightweight-capacity-model.py)。**I：全部是研究情景，不是用户统计。**

| 情景 | 每日交易 / Note / AI轮次 | 1年：行 / JSONL MiB | 3年 | 5年 |
|---|---|---|---|---|
| 轻度 | 1 / .15 / .2 | 740 / .301 | 2,193 / .895 | 3,647 / 1.492 |
| 普通 | 4 / 1 / 2 | 5,484 / 4.418 | 16,426 / 13.244 | 27,369 / 22.071 |
| 重度 | 12 / 4 / 8 | 24,769 / 42.121 | 74,282 / 126.351 | 123,795 / 210.572 |
| 极端 | 40 / 12 / 30 | 110,132 / 398.823 | 330,373 / 1,196.465 | 550,614 / 1,994.107 |

公式核心：年数×365×每日产出；每轮1Run+2Message；Session按每4/8/12/20轮一组；Proposal概率5/20/35/50%，Applied率50/60/70/80%；Note另计10%软删保留。payload平均2/4/8/16KiB，按creation/current/frozen副本计；正文与JSON转义/身份/信封单独计。每日业务产出已包含AI产生的记录，不双算。

普通五年逻辑主要为Message6.28MiB、Proposal6.16MiB、收支2.71MiB、Note2.28MiB、Operation2.02MiB及其他。重度五年Proposal83.01MiB、Message42MiB、Note32.63MiB、Operation30.50MiB；Proposal+Operation占53.9%。把它们改成“可重建缓存”会丢解释历史，禁止以此换数字。

Host SQLite3.50.4用**正式14表DDL/索引**做8个256/1024行形状试件：普通五年紧凑物理估计21.5～40.6MiB，重度194.6～366.3MiB。FK关闭仅为独立表容量校准，既不是合法业务fixture也不是Android性能证据；区间为公开工程敏感性，不是置信区间或硬上界。碎片/索引/overflow/WAL还会改变物理量。

WAL按最近写事务/页放大和读者持有时间增长，不按年无限累积；不把五年累计写量预留成临时空间。普通/重度每表大小、单行元数据、极端字段和WAL小事务采样在容量evidence中可复算。

### 4.1 重新判断三个旧上限

- **500k行：** 在本模型约为普通五年的18倍、重度五年的4倍；不应定义所有普通用户的启动预算。建议普通≤30k、重度≤150k作为性能目标档；这不是降低现有能力合同。负责人批准容量兼容性调整以前，满足现有格式上限且资源充足的500k合法数据仍是生产验收必测项，不能仅因标为“极端”而拒绝。是否降低硬行数需要存量包策略，不能先让旧大包恢复后却无法再完整备份。
- **256MiB逻辑：** 普通模型远低于它；重度约六年就触及，说明它也不是“永远足够”。本轮不建议仅为省工程量立刻砍到32MiB，后者可能排除正常长期AI使用。按真实L预算与明示越界，不能预留256MiB全量文件。
- **64MiB ZIP：** 要和内容熵分开，不能从L推测实际PASS。普通22MiB即使用近无压缩也有合理空间；重度210MiB能否装入64MiB需真实内容测试。建议暂保留原格式/读取能力；若要放宽需独立兼容/格式裁决，若要缩小则需先明确长期历史的完整出口。

**建议是改变资源路径和性能支持档，而非为了原型通过随意改限额。** 极端超过实际ZIP/资源工作预算允许整次明确拒绝；不截断、不跳坏行、不自动删历史。最终硬容量/输入长字段边界仍由负责人裁决。

## 5. 官方与开源路线：证据和适配范围

详细逐条一手链接见 [官方源码报告](../engineering/evidence/2026-10-26-lightweight-official-research.md) 与 [四应用社区报告](../engineering/evidence/2026-10-26-lightweight-community-research.md)。

| 路线 | 原理与API34/并发边界 | 依赖/资源/取消崩溃与适配结论 |
|---|---|---|
| A 当前Room compatibility reader | S/E：读事务和同实例writer排同一TransactionExecutor；UPDATE不稳定拒绝，大字段仍CursorWindow失败 | 不重跑旧失败，不作为主线；改线程池不能据此证明真实读连接 |
| A补充：独立平台SAVEPOINT | D：最外层等价DEFERRED；S/I：AOSP readonly私池1且不回收时可保持快照 | 无新native、可fail-closed；但私池恒1不是公开跨OEM契约，RELEASE成功单独不足证明所有查询同连接。保留研究，不作为可靠性主方案 |
| B 官方sqlite3_backup | D：引擎复制，可分步；其他writer可能重启复制，finish=OK不代表DONE | Android/当前bundled Kotlin API无直接handle；需绑定/引擎owner与显式source快照。仍有S，取消/空间/强停目标无效规则要做；不以JNI堆栈换一项本不必要的物理复制 |
| C 旧C2 / 新S | D/E：VACUUM INTO提供一致目标，API34系统源OS只读可用；新S用系统scalar读静态目标，无bundled依赖 | 旧C2预算否决。S只保留S+Z+K，不需要L；copy阶段signal可中断，目标强停后弃置。源默认损坏handler必须禁自动删库；全库明文/目标cap与多一份S仍是代价 |
| D 统一driver / U | D/S/E：Room native reader query_only+DEFERRED，statement.step不依赖CursorWindow，与writer并行 | 单ABI约1.2MiB native；无S，WAL要监控。取消在短step/行/输出块边界；无公开native interrupt，不能声称Job.cancel能打断任意长排序。须迁移实际旧事务/读取接缝 |
| E 受控短时写协调 | I：全写者不变期可让多SELECT一致，但必须覆盖完整COMMIT、初始化、Run回调与跨进程 | 当前gate计数在外层COMMIT前归零，不足作此证明；单靠close/checkpoint+copy WAL主文件不安全。若采用须设计真正提交屏障、超时/排队/草稿语义；小库可能便宜，规模大时长暂停不符合日常工具，不优于U |
| F 标准流式ZIP/现有逻辑格式 | S/E：ZipOutputStream、固定buffer、manifest-last、非空descriptor，空段STORED | 0额外ZIP依赖，减少自写header/全量L；100:1触发时同快照第二遍，仅坏比值段STORED，整次验证后才外发。非空level0也已测，但文件更大且本夹具未明显省时，不选默认 |

主要一手资料：[SQLite Online Backup](https://sqlite.org/backup.html)、[VACUUM INTO](https://sqlite.org/lang_vacuum.html#vacuum_with_an_into_clause)、[Room Transactor](https://developer.android.com/reference/androidx/room/Transactor)；固定2.8.4/2.6.2代码以evidence的Maven sources及SHA为准，不用新版API替旧行为背书。

**版本门禁：** 试验2.6.2所含3.50.1不是已批准生产版本。补查官方2.7.1 AAR的四ABI ELF，均呈现3.50.1及官方原版完整source-id `2025-06-06 14:52:32 b77dc5e0f596d2140d9ac682b2893ff65d3a4140aa86067a3efebe29dc914c95`；没有取得WAL-reset修复回移的官方证明。静态身份不能排除未披露补丁，也不代表已观察到损坏，但“升级最新版”不能关闭此门禁。详见[版本补查](../engineering/evidence/2026-10-26-lightweight-official-research.md#51-生产版本门禁补查官方-bundled-271-仍未提供已修复身份)。因此U目前是有条件的架构方向，尚无本轮审过的可直接投产发行物；须先决定已修补发行渠道及维护责任，再考虑迁移。不默认维护私有SQLite分叉，不静默把2.6.2或2.7.1带进生产。系统路线也受设备引擎/OEM补丁差异影响，不能免除版本和写入/checkpoint所有权审查。

社区不是“找一个库接管全部恢复”：

- [Tasks.org导出](https://github.com/tasks/tasks/blob/4ea72a1d26c5c4701bce02feb6c21f1d2b15fd1e/app/src/main/java/org/tasks/backup/TasksJsonExporter.kt)：逐实体BufferedWriter有借鉴价值，但全ID/map和跨表快照未满足本合同。
- [MyExpenses平台ZIP](https://github.com/mtotschnig/MyExpenses/blob/31246c3576ac0668d2e00f7a5fc69f05306c941a/myExpenses/src/main/java/org/totschnig/myexpenses/util/ZipUtils.kt)：成熟标准流、无ZIP native库；其DB明确WAL off，写事务+主文件copy不可照搬到Record WAL。
- [Quillpad备份](https://github.com/quillpad/quillpad/blob/c12ff24d15bad1852332164812f89c00177c2967/app/src/main/java/org/qosp/notes/components/backup/BackupManager.kt)：平台ZIP可复用思路，但全量Set/String与合并恢复不适配。
- [AntennaPod导出](https://github.com/AntennaPod/AntennaPod/blob/d3e41c6cb37c8e1a060b142686f818999de89859/storage/importexport/src/main/java/de/danoeh/antennapod/storage/importexport/DatabaseExporter.java)：实际adapter.close是no-op，checkpoint结果未构成完整屏障；不能因项目成熟就声称安全。

Android Auto Backup受系统调度/账号/额度约束，不能替代主动SAF完整手动备份；现有系统备份排除不改。直接把整个SQLite交付用户也不是本轮替代：会带ai_control等排除内容，恢复还要处理Schema/代际/receipt，反而推翻已验收内核。

## 6. 两个现实候选：完整数据流与失败边界

### 6.1 U：统一驱动的直接逻辑快照（推荐待批）

```mermaid
flowchart LR
    B[同一bundled引擎的Room业务writer] --> D[(正式数据库/WAL)]
    D --> R[Room reader query_only / DEFERRED]
    R --> K[固定字段与有序rowid / 当前行]
    K --> Z[平台ZipOutputStream 私有ZIP]
    Z --> V[原严格读取器 / manifest / hash验证]
    V --> S[原SAF写入关闭回读]
```

1. 正常启动门禁后取得export lease；恢复互斥，不暂停正常writer。先以同一读事务固定metadata和12段，禁止使用旧平台读句柄与新driver混读活跃文件。
2. 查询只对键排序，不排序完整JSON；先在同一快照计全局N，超限即拒绝，再写8B/行rowid spool。每次只取当前行，校验/规范化/UTF-8计数/CRC/SHA后直接压缩到私有ZIP；无S、无完整JSONL文件。
3. 空段STORED0/0；正常非空DEFLATE/descriptor；最后写manifest。若100:1触发，关闭第一份暂定ZIP，在**同一快照**中重读，用已知CRC/大小把相关段写为STORED，其他照常压缩。最多两次，旧文件关闭后才重用当前任务文件，不并存两个完整Z。
4. 完成/关闭ZIP、读事务后，用原严格预检/摘要校验，再保留原SAF写/close/回读。只有全部通过才成功。取消/磁盘满/旧CRC不符/超行或字节/元数据丢失均整次失败，原库事实不改。
5. 原型的Room reader是query_only读连接，底层不宣称OS OPEN_READONLY；这与旧G0字面机制不同，需要明确修订。只读拒绝、并发写、跨段事实和原生事务归框架负责；不能只拿一个接口名字当保证。

### 6.2 S：平台一致采集+系统静态scalar读取（低改动替代）

系统Room继续不变→系统OS只读来源执行VACUUM INTO→关闭来源→系统OS只读目标→同一流式逻辑ZIP→原验证/SAF/恢复。

目标只在成功采集后打开；源/目标禁默认损坏处理器删库，copy阶段持有signal并实际等待退出，失败/强停scratch不续用。scalar simpleQueryForString取一行解决大字段，不让CursorWindow承载整行；rowid只从目标内部枚举，不复用来源rowid。所有事实/generation/revision来自目标，采集Clock只表示时间窗。

它不需新native库，也不迁移34个业务事务；但保存完整S，包含暂时不导出的控制/元信息，源物理体积不由L上限约束，重度长期用户可能多花数百MiB。故优先级低于U，不能再把它的实测收益包装成旧C2预算获批。

### 6.3 可直接比较的代价与选择条件

| 维度 | U：直接逻辑快照 | S：平台静态快照 |
|---|---|---|
| 一致性边界 | 同一引擎/连接的明确DEFERRED读事务覆盖12段 | SQLite一致采集成功后的不可变目标覆盖12段 |
| 安装增量 | 试验driver单ABI原始ELF约1.2MiB；已修补发行物体积须重测 | 无新增SQLite或ZIP依赖 |
| 小库工作文件 | 1k约56kB，不另存物理库 | 1k约482kB，含426kB副本；也无需固定GiB预算 |
| 普通/重度额外盘量 | ZIP+键/排序+导出钉住的WAL增量 | copy阶段含目标/临时家族/WAL；提取阶段主要副本+ZIP+键/排序 |
| 同条件时间证据 | 10k stream 8.633s；50k size-first 35.346s | 10k stream 10.076s；50k size-first 45.841s；未测50k stream，不能拿45.841s当其单遍结果 |
| 内存证据 | 50k两遍113.19MiB；键排序仍可能在native内存 | 同夹具两遍113.00MiB；系统temp工作区待补量化；均非硬上界 |
| 正常业务写入 | 导出读事务全程并行，WAL可能长期增长 | 采集期并行，采集结束即释放来源读者；后续提取不钉住源WAL |
| 取消/崩溃/空间失败 | 当前step结束后可检查取消；无公开interrupt；未闭合ZIP整次弃置 | copy可用平台signal；完成退出后弃置不完整目标；提取与ZIP同U；不续用强停scratch |
| 私有数据 | 私有ZIP含完整用户历史，凭据/执行授权按原协议排除 | 除ZIP外还有完整物理scratch，可能含不交付的控制信息，须私有保存和清理 |
| 迁移/维护 | 统一factory、34处旧事务及平台读取接缝；须回归既验收恢复语义 | 业务/恢复事务配置保留；新增采集与静态scalar接缝、目标校验/清理 |
| 当前准入缺口 | 未取得审过补丁的可投产发行物；最大包/新预算/事务迁移未验收 | OEM/系统引擎审查、Q_copy预算和最大包新sink未验收 |

推荐U是因为它从架构上去掉随用户历史增长的完整副本，长期磁盘成本更低；不是认为其迁移免费或版本问题已解决。若没有可维护的已修补发行渠道，或者负责人更重视避免本轮存储接缝迁移，S是有实际API34证据的可行替代，应按它自己的预算/版本门禁评审。两条路线共同、优先级最高的收敛是标准流式ZIP与取消全量JSONL staging；不需要为此重写恢复协议。

## 7. 资源函数与产品预算候选

令D为已存在源库（不再当成新增临时占用），S为新静态副本，L为逻辑字节，Z为本次完成ZIP，K为rowid/查询临时文件，W为被导出读者保留的实际WAL增量，F为共享卷保底。所有量按同一时刻/同一卷核算。

```text
U实际额外存储峰值 = max_t[ Z(t) + K(t) + W(t) ]
S实际额外存储峰值 = max_t( S_copy(t)+Q_copy(t)+W_copy(t), S + Z(t) + K(t) )
                   （来源读者在copy后关闭，不把后续WAL累计错记为仍被导出钉住）

U同卷的工作预算准入 = Z_work + K_bound(N) + W_work + F
S同卷的工作预算准入 = max(S_cap+Q_copy_work+W_copy_work, S+Z_work+K_bound(N)) + F
```

**I/H：** 这是候选设计，不是已实现的精确空间承诺。Q_copy包括采集期目标journal及SQLite其他临时文件家族，本轮尚未独立完整采样，不能假定为0；具体增长上界/监控须在S生产准入前验证。F暂保留现有64MiB卷保底，同卷不机械重复两次；分卷分别检查。N在同一快照已知后K的rowid部分为8N，平台排序的其他内存/临时文件另测，不能凭这一项宣称所有排序成本只有8N。

原型的peakTempBytes仅统计本任务目录；WAL另采样。U的TEMP_STORE=3支持其排序不落盘的解释；系统路径可能有任务目录外的SQLite临时文件，本轮未单独量化，不能把未测项报0。完整生产公式中的K还须包含这部分；也不能把已有源DB本身说成新增临时副本。

Z_work按本次选择的输出工作配额准入并由计数字节流硬守住；低余量时可选size-first同快照测/写，得知本次Z后再扩大准入，而不是每次先收256MiB原文+64MiB包。W_work是本次允许的写入增长工作预算，需按实际写放大和持有时长评估；触及预算就中止导出/关闭读者，不能为过关调高、不能声称最大384MiB必会发生。并发DB增长、其他应用占盘和单次大写入仍可能导致IO失败；要保持明确失败/原库原子性，不承诺任意环境都完成。

原型只测了输出字节配额故障和已有monitor/copy故障机制，**没有实现并证明这套新准入/真实低存储余量/最大在途写入超调公式**。它必须在批准后实现并验证，不能单凭上述代数就把新R标为安全验收通过。

### 7.1 待批准产品目标（不是自动修改G0上限）

| 档位 | 数据与体验目标 | 内存/存储目标 | 未通过或越界行为 |
|---|---|---|---|
| 日常小包 | 约1k行/小于1MiB逻辑；参考设备导出≤5s | PSS≤96MiB、增量≤32MiB；U临时文件≤4MiB；工作余量约64MiB+本次配额 | 明确拒绝或失败，不先生成“成功” |
| 普通长期 | ≤30k行、约≤32MiB逻辑，目标≤15s | PSS≤128MiB、增量≤64MiB；U临时文件以实际ZIP+K为主，目标≤20MiB | 需真实混合历史profile验证；未达目标不能改大阈值假PASS |
| 重度长期 | 认证到约150k行；L/ZIP仍须满足实际格式上限，目标≤120s | PSS≤160MiB、增量≤96MiB；U临时文件目标≤72MiB | 合法大字段/高写入/高熵需分布验证；空间工作配额不足整次拒绝 |
| 极端 | 500k/256MiB不作为普通档耗时SLO，但现有合法上限验收继续有效 | 按实际量准入；需明确操作预算，不能因档位预留GiB | 当前只可因真实资源不足或格式越界整次拒绝；降低能力范围须先获批，绝不部分成功 |

示意而非实测：普通五年L约22MiB，若本次Z≤约22MiB、K约0.21MiB、W_work选4～16MiB，U同卷预算约90～102MiB；不是GiB。S若副本约21.5～40.6MiB，则提取阶段连保底约108～127MiB。重度U若实际Z≤64MiB，K约0.95MiB、W_work16～64MiB，预算约145～193MiB；S若副本约194.6～366.3MiB，提取阶段连保底约324～495MiB。S还须和含Q_copy/W_copy的采集阶段预算取max，以上不是完整启动预算；不能简单在U的全程WAL预算上再加S。Z、W取值是情景参数/候选工作额度，**不是压缩率或写放大的保证**，不能据此给任何未测包打PASS。

## 8. 最小隔离实验与失败记录

详见 [本轮运行证据](../engineering/evidence/2026-10-26-lightweight-prototype-results.md)；代码为 [LightweightZipPrototype](../../record2/app/src/androidTest/kotlin/com/paherdin/record2/backup/prototype/LightweightZipPrototype.kt)，命令入口 [runner](../../record2/tools/run-lightweight-backup-prototype.py)。全部固定seed、唯一Schema v1合成库、真实API34，造数与导出不同PID；两候选用相同fixture/同一ZIP编码。

| 新路径 | 行 / 逻辑量 | 导出时间 | 峰值PSS / 增量MiB | 临时工作文件峰值 |
|---|---|---:|---:|---:|
| S 两遍size-first | 1k / .199MiB | 2.212s | 81.06 / 20.82 | .460MiB |
| U 单遍stream | 1k / .199MiB | 1.715s | 77.40 / 16.57 | .054MiB |
| S 单遍stream | 10k / 2.004MiB | 10.076s | 112.10 / 51.83 | 2.714MiB |
| U 单遍stream | 10k / 2.004MiB | 8.633s | 110.34 / 49.62 | .492MiB |
| S 两遍size-first | 50k / 10.064MiB | 45.841s | 113.00 / 52.74 | 12.874MiB |
| U 两遍size-first | 同上 | 35.346s | 113.19 / 52.39 | 2.436MiB |
| U 单遍stream | 同上 | 25.161s | 111.43 / 50.63 | 2.436MiB |

说明：单遍不是所有情况只读一次；100:1 fallback最多两遍。同一fixture不同候选/编码遍数得到相同ZIP SHA，且全部经未改动的G1预检/单事务恢复。50k试件Note正文仅40字符，不代表普通模型的混合AI历史。其25s不能被称为已满足普通档15s；目标档和数据分布须独立认证。

已验证的新关键假设：

- U受管理reader明确拒绝UPDATE；并发事务能在快照存续时提交，ZIP仍为完整前态。
- manifest-last、非空descriptor、空段STORED0/0，经原严格读取器和实际恢复通过，无格式放宽。
- 一条7M重复字符的高比值Note触发两遍/单段STORED回退，完整字段与现有恢复通过，未删除100:1检查。
- 取消与1KiB输出工作配额故障：14表前后摘要一致，之后写入成功，不产生完成标记。
- 真实force-stop在未关闭ZIP时发生；新PID证明14表未变、半ZIP被原reader拒绝、之后仍可写。不是仅抛异常冒充杀进程。
- level0在10k用时8.615s，与默认8.633s几乎相同，却把ZIP从434,200B变成2,105,345B；此夹具瓶颈不在压缩，故不选为默认。不能将此结论外推到所有正文。

失败保留：平台ZipEntry使用1980边界日期时自动产生extra，被原读取器拒绝；改成DOS范围中部固定日期后通过，不放宽reader。本轮没有重跑已失败的Room兼容/旧递归SQL；500k+17大字段、WAL故障等旧证据作为历史边界比较，**新轻量sink没有再跑整套500k矩阵**。真实低磁盘耗尽、所有AI状态、正式UI/SAF、目标硬预算与生产整合仍待授权后验证。

## 9. G0 / ADR / Spec需要保留与调整的规则

| 保留的产品不变量 | 可以调整的技术手段/支持范围 |
|---|---|
| 同一时点完整事实、稳定ID/版本/所有历史关系 | 来源是独立OS只读句柄还是框架managed query_only reader |
| 严格有限资源预检、原子恢复、receipt权威 | manifest物理位置、标准ZIP而非自写header、是否存在L staging |
| 旧Run/授权/旧Pending不得恢复执行 | 是否物理scratch、WAL工作预算、空间按阶段/实际量核算 |
| 失败不交付成功、SAF关闭回读、凭据排除 | 支持档/SLO、极端容量拒绝、长字段输入边界 |

U需要修改G0 §7.1/ADR决定2的具体读连接/引擎约束，并改§6的空间工作预算；既有格式允许新ZIP写法，不需要为manifest-last随意升formatVersion。是否改变数值上限依原兼容规则单独审，不能借轻量sink顺手改常量。

S需要允许内部一致物理scratch与采集时间窗，但不要继续采用旧C2的完整L/双384固定公式。恢复的逻辑替换、状态转换、receipt与Schema v1不变。

本轮只提出以上修订，原G0/ADR既有Accepted内容不被新报告自动替换；旧C2 Proposed不再被当作已批准主线。Spec若调整支持容量/SLO，必须保留完整性/故障/AI安全AC，不能删断言换PASS。

## 10. 获批后的实施顺序与负责人裁决

1. 先裁决产品支持档与容量兼容策略、U或S、按实际数据的空间工作预算；真实混合历史profile补齐后冻结资源目标。若选U，必须先审过已修补引擎的发行渠道/维护责任和真实加载身份；没有合格发行物就不启动生产迁移。
2. 若选U，独立小批迁移唯一DB factory和事务helper。真实源码范围：9个storage文件34处withTransaction；普通生成DAO已支持driver，不默认改所有DAO。另有1处legacy Cursor PRAGMA需当前Transactor执行，以及4个备份平台只读入口的引擎所有权审计。禁止同文件两引擎并存。
3. 迁移只改事务/读取接缝，保留门禁、异常回滚、A/B/CAS、恢复转换和receipt算法；这一步确实触及既验收存储/RoomRestoreStore的事务接缝，收益/风险必须单独审核并跑storage/AI/故障/恢复T3，不能声称内核完全无影响。S则可避免这批迁移，但接受S的空间/隐私成本。
4. 单独接入平台ZIP sink和明确snapshot port，移除旧递归导出路径的职责；不改导入协议或新造第二套恢复框架。先验证元数据/键排序/长字段/高压缩回退，再跑真实生产50k失败回归与支持档完整矩阵。在容量调整明确获批前，仍须覆盖原500k与大字段组合验收；150k目标档不能替代它。是否删除旧文件届时按红线另获许可。
5. 实现预算监控、source/target禁自动删库handler、staging完成屏障/失败上报，再接正式SAF/UI；与已验收导入/原子恢复联合回归后才可讨论PR #31验收。

真正待裁决的选择只有：

- **主路线与发行责任：** 是否接受U的单ABI约1.2MiB native及受控存储接缝迁移，换取无物理全库副本；能否取得可审计的已修补发行物并承担其维护责任。若不可得，是否选S的零driver迁移、较大工作存储；两者都不是本轮已获生产准入的版本方案。
- **产品支持：** 是否采用普通/重度性能目标档，以及是否另行调整500k硬能力与存量包策略；未裁决以前保留原上限验收。长字段创建与备份边界要一致，不能悄悄截断。
- **空间/体验：** 选择按本次Z/K/W与同卷阶段核算的工作预算，保留安全余量并接受明确资源拒绝；不再允许小库固定GiB门槛。原型没有替这个新预算获得批准。

**到此停止在设计裁决阶段。** 不修改生产G1、Schema或正式资源常量，不引入生产依赖、不标Accepted，不提交新架构决定、合并PR #31或关闭issue #19。

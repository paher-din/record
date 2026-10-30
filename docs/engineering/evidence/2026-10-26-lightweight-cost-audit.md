# 轻量备份：现有产物、运行与空间成本审计

2026-10-26。只读审计，未改代码/依赖/设备。数值来源为本轮 [artifact-cost-audit.json](../../../record2/build/lightweight-backup/artifact-cost-audit.json)、现有生产代码、旧C2独立PID原型回执和旧G1导入证据；读取旧结果不代表本轮重跑。`MiB=1,048,576B`，`GiB=1,024MiB`；Android PSS的KB读数按KiB换算。

## 1. 先区分四类成本

1. **构建包体积：** APK/AAB文件长度、DEX和ZIP中native条目贡献。
2. **设备安装/下载：** 设备配置split、安装提取/优化与后续app数据，不等于整个AAB或universal APK长度。
3. **运行内存：** PSS、ART管理heap和native allocator读数，互不等同。
4. **备份临时存储/启动余量：** 任务文件及并发WAL增量、允许未来增长的资源预算，既不等于APK也不等于安装后体积。

1.47GiB是旧C2**待审启动可用空间预算**，不是安装体积。当前生产`BackupExporter`仍是原G1查询路线，实际代码前置检查staging128MiB、DB卷64MiB；不能把未接入生产的C2预算写成当前APK已执行的行为。

## 2. APK/AAB实际长度与构成

以下为本轮已构建产物的只读ZIP审计；完整路径与SHA-256在JSON中。release APK未签名，不是发布许可。

| 产物 | 实际文件B | MiB | DEX解压B | DEX在包内B | 全ABI native解压/包内B |
|---|---:|---:|---:|---:|---:|
| app-debug.apk | 38,066,137 | 36.3027 | 37,603,196 | 37,603,196 | 37,392 / 37,392 |
| app-release-unsigned.apk | 30,826,338 | 29.3983 | 30,371,580 | 30,371,580 | 37,392 / 37,392 |
| app-release.aab | 10,974,594 | 10.4662 | 30,371,580 | 10,645,000 | 37,392 / 17,846 |
| 本轮androidTest APK | 8,218,682 | 7.8380 | 3,124,356 | 3,124,356 | 4,944,844 / 4,944,844 |

生产APK的37,392B native内容是四ABI的`libandroidx.graphics.path.so`，不是bundled SQLite。`libsqliteJni.so`只出现在本轮实验test APK中，不能通过“test APK减app APK”求生产依赖增量。此时生产APK尚未新增bundled SQLite。

当前release主包的主要静态贡献是DEX，不能把全部约29MiB归因于备份。AAB的DEX压缩条目明显更小，故AAB10.47MiB与APK29.40MiB并不矛盾。[Android App Bundle说明](https://developer.android.com/guide/app-bundle)明确AAB由分发端按设备生成APK；这里没有运行bundletool设备配置下载估算或完整设备安装占用测量，因此不将这些文件长度写成用户实际下载/安装数值。[APK Analyzer文档](https://developer.android.com/studio/debug/apk-analyzer)也区分包条目贡献与估计下载大小。

同API34上已安装debug包的代码目录`du -sk`为37,204KiB（36.332MiB），只包含当次代码目录的分配块，不含dataDir和系统侧所有dex缓存；这仍不是完整安装占用，更不是release安装后大小。没有以debug代码目录推断正式版本总空间。

### 2.1 成对依赖size probe

探针是独立空壳app；两flavor都已包含Room2.8.4和Kotlin stdlib2.3.10，AGP8.12.3、min/target34、compile36、release不minify与ABI设置一致，只有bundled flavor额外包含sqlite-bundled2.6.2。因此不会把一份全新Kotlin/Room基线算成SQLite增量。

| 成对产物 | baseline B（MiB） | bundled B（MiB） | 差值B（MiB） |
|---|---:|---:|---:|
| universal release APK | 4,641,712（4.4267） | 9,638,606（9.1921） | **4,996,894（4.7654）** |
| release AAB | 1,470,166（1.4021） | 3,992,167（3.8072） | **2,522,001（2.4052）** |

DEX原文差值12,384B；AAB内DEX压缩差值4,434B。universal APK差值主要来自四份ELF合计4,944,844B和ZIP对齐/元数据；AAB四份ELF压缩合计2,516,362B。

**边界：** 这是Room/Kotlin对齐的空壳成对实验，不是完整Record正式app的精确依赖delta；实际R8、引用关系、打包和ABI拆分可能不同。没有据此更改正式生产依赖。

| ABI / libsqliteJni.so | ELF原文B（MiB） | AAB压缩条目B（MiB） |
|---|---:|---:|
| arm64-v8a | 1,310,776（1.2501） | 643,477（0.6137） |
| armeabi-v7a | 1,253,332（1.1953） | 617,574（0.5890） |
| x86 | 1,159,896（1.1062） | 630,845（0.6016） |
| x86_64 | 1,220,840（1.1643） | 624,466（0.5955） |

只需要一个ABI的设备通常不会下载四份native库；但上表一个压缩条目也不是完整单设备安装增量。平台`java.util.zip`并不需要把另一份ZIP native库打入APK；它仍有平台zlib运行内存和CPU成本。

## 3. 运行内存：已有测量与缺口

本轮新增[基础host内存原文](../../../record2/build/lightweight-backup/base-host-memory.txt)：通过`am start -W`启动当前G1基线MainActivity，等待2秒后，PID7279的TotalPss为74,850KiB（73.096MiB）；Native Heap行PSS11,945KiB、Dalvik Heap行PSS3,941KiB。此MainActivity只编译空`setContent {}`host，**不是F1正式UI或长期稳态内存**；PSS包含APK/代码映射等，不能拿Dalvik行当整个Java heap。它也不是下面instrumentation进程的可直接相减基线。

旧C2主回执：[f0a54a62…/metrics.json](../../../record2/build/g0-export-review/f0a54a62f2b746bda95747ba15202a19/metrics.json)。prepare PID6678，export PID6748，500,000总行含17条大memo；独立PID避免把造数对象存活全部算为导出成本。

| 指标 | 原始读数 | 换算与解释 |
|---|---:|---|
| 导出前PSS基线 | 60,072KiB | 58.664MiB；app+Room+合成库/测试环境已就绪，不是纯App冷启动/空闲基线 |
| 导出进程PSS采样峰值 | 179,935KiB | 175.718MiB |
| 相对基线增量 | 119,863KiB | 117.054MiB |
| ART管理heap采样峰值 | 106,206,872B | 101.287MiB，Runtime total-free；不存在独立“Kotlin heap” |
| native heap采样峰值 | 55,883,312B | 53.294MiB，不能全部归为SQLite缓存 |
| NOTE键开始PSS | 119,646KiB | 116.842MiB |
| 首个NOTE key前PSS | 166,562KiB | 162.658MiB；该阶段增加约45.816MiB，不是精确SQLite page-cache归因 |

100ms及阶段边界采样不是瞬时数学上界；Java/native峰值也未必在同一时刻，不能相加冒充PSS。现有回执没有SQLite专用page-cache/sorter allocator统计，因而SQLite缓存与排序内存尚不能逐字节归因。bundled编译选项`TEMP_STORE=3`意味着仅写`temp_store=FILE`不能保证排序磁盘spill。

源字段经SQL构造JSON、getText复制、UTF-8转换、StrictJson解析、SegmentCodec再次编码、压缩缓冲等都可能产生当前行大小相关的临时对象。原型分离键排序与载荷后没有全量JSON驻留，但8MiB单行仍能放大多份短命分配。是否继续支持这种单行，应归产品/预算裁决，不能只谈500k行数。

旧生产G1正常500k行虽完成，但采样PSS855,049KiB（835.009MiB）、717.191s；并发50k会失败。这是原访问架构与原型的不同结果，不能把新原型峰值写成生产修复。[G1旧证据§4.3](2026-10-25-g1.md#43-2026-10-25-真实-room-导出审计与-staging-收口第十一轮)

## 4. C2实际空间为何约494MiB

符号：`D`为已有源DB，`S`为一致物理目标，`L`为全部JSONL文件，`Z`为成品ZIP，`K`为rowid文件，`C_i`为当前段压缩spool；源WAL/SHM另计。

| 旧C2夹具分量 | B | MiB | 范围 |
|---|---:|---:|---|
| 一致物理目标S | 234,438,656 | 223.5781 | 含全部物理表/索引/元信息，不是源D的测量 |
| JSONL内容L | 231,833,235 | 221.0934 | 12段合计，完整保留到打包结束 |
| ZIP Z | 48,194,637 | 45.9620 | 原逻辑ZIP，保留以验证/后续外发 |
| rowid key文件K | 4,000,000 | 3.8147 | 500k×8B，原型SQL排序后产物 |
| 上述常驻文件合计 | 518,466,528 | 494.4482 | 文件长度口径 |
| staging采样峰值 | **518,467,762** | **494.4494** | 与上述相差1,234B合成fixture元数据 |
| 源WAL峰值（不含在staging中） | 14,988,592 | 14.2942 | 基线4,152B，增长14,984,440B |

此回执没有单列源D/SHM及物理分配块峰值，不能把S冒充D，也不能把文件长度合计称为完整设备存储分配。staging与WAL各自峰值时间未对齐，相加只可作这两项的独立峰值之和，不能当作完整同一时刻实测。

**压缩spool不能忽略。** [LogicalZipWriter.writeSegment](../../../record2/app/src/main/kotlin/com/paherdin/record2/backup/LogicalZip.kt)先重新校验JSONL文件，再为当前段创建`.deflate`文件，判断压缩收益/100:1后把压缩或原文复制入ZIP，最后删该spool。代码上可能短时同时存在“已写入ZIP的当前段”和其压缩spool；旧100ms sampler没有独立记录`C_i`峰值，1,234B残差不证明spool成本为零。结构上界近似 `S+L+K+Z+max(C_i)+metadata`，而实测494MiB主要反映最终常驻文件；不凭一个采样数删掉安全余量。

已有源D本来就在设备上，通常不属于“此次需要的额外可用空间”；新S/L/Z是同一用户事实的多种完整表示，彼此不是备份安全的普遍必要条件，而是这条管线的保存选择。源WAL增长则确实占用新增空间。

## 5. 1.47GiB的来源与过度保守处

旧候选公式来自[旧C2复审§5.2](../../architecture/record2-g0-export-architecture-review.md#52-预算时间与取消)，不是本轮认可：

```text
T_cap = S + 384MiB                  # 目标工作配额，不是目标必然上界
R_out = T_cap + 64MiB keys + 256MiB JSONL + 64MiB ZIP + 64MiB floor
R_source = 384MiB WAL增长 + 64MiB floor
同卷要求 = R_out + R_source = S + 1280MiB
```

本夹具得到1,576,615,936B =1,503.578MiB =**1.46834GiB**。即使S接近0，仍有**1.25GiB固定项**；这是普通小库的明显产品门槛，不是APK代价，也不是设备上必然同时生成1.47GiB文件。

相对于494.449MiB staging采样，差额约1,009.129MiB，其来源可核算为：

| 预算相对实际夹具的增量 | MiB | 解释 |
|---|---:|---|
| 目标允许额外增长 | 384 | 本夹具S已实际测得，复制962ms，没有发生这规模的目标增长 |
| 源WAL允许增长 | 384 | 与目标是不同文件，物理上可能同时消耗；但实际WAL增长仅约14.29MiB，且不在494MiB口径内 |
| keys64MiB减原型4,000,000B | 60.185 | 旧Proposed生产外部归并的最坏布局预算，不是原型已用量；不能当作所有排序空间都多余 |
| JSONL上限256MiB减L | 34.907 | 以全格式上限代替实际数据量 |
| ZIP上限64MiB减Z | 18.038 | 同上 |
| 两份余量floor | 128 | 同卷仍累加两个64MiB；没有与阶段/同卷可复用性区分 |

再扣1,234B元数据即可还原差额。比较时必须记住R还包括源卷未来增长，而494MiB仅staging；两者不是完全相同范围。

**哪些不能直接删：** 不完整目标/ZIP的处理、源WAL增长控制、当前行/压缩临时空间、文件系统余量和失败后清理都是真实需求。原始S是采样估计，不由某一WAL基线自动推出完成时目标大小；降低预留不应变成“保证一定完成”的虚假承诺。

**哪些可通过改架构减少：**

- 采用可靠读事务直接生成ZIP可去掉S；静态采集之后直接ZIP可去掉完整L；包装选择若无需per-entry重建，可去掉或复用压缩spool。
- 活库一致采集仅占短阶段时，WAL历史不应为后续整个JSON/ZIP处理持续钉住；资源模型应按真实阶段求max，而非把每个阶段所有最大值相加。
- 数据量、允许并发写入、暂停窗/取消响应与工作配额可以形成新的可证明边界，避免固定套两次384MiB和两次floor。**这些需要新的算法/产品约定及隔离验证，不是把原if阈值改小。**
- 同卷/异卷、源已有空间/此次新增空间、估算/工作cap/保底都应分别建模；不能因样例低于预算就假设以后永不超额。

本轮负责人已否决旧预算；其固定项应作为被审查的设计选择，不能继续默认成立。

## 6. 耗时与复杂度

旧C2 fresh回执的各时间点相对导出开始：

| 阶段 | 时间 | 含义 |
|---|---:|---|
| 引擎物理复制 | 0.962s | 复制中正常业务write已提交 |
| 完成打开/目标检查 | 5.258s | 不是纯复制时间，包含打开/验证等前置 |
| 全段提取完成 | 119.271s | 前置+键查询/逐行读取/JSON编解码与段写入 |
| ZIP写完 | 137.305s | 再增加18.034s：段复验、压缩spool、ZIP组装 |
| 验证/重复hash/尾部检查完成 | 175.719s | 再增加38.414s；不是SAF DocumentsUI最终产品旅程 |

这表明大夹具的主要时间不在0.962s快照，而在多轮序列化、重复读取和ZIP验证；不能只把复制提速当作完整轻量化。原型的writer在复制期间完成，仅代表该正常三记录写模式，不覆盖此前批量改写17条大字段导致的等待超时。

生产`backup/`共**5,005物理行、220,836B源码**，含空行/注释，不是SLOC或整App大小：

| 职责分组 | 物理行 |
|---|---:|
| Snapshot317 + Exporter147 + SegmentFileWriter85 | 549 |
| LogicalZip（writer/严格reader合并） | 882 |
| LogicalFormat + StrictJson + LogicalBackupValidator | 1,990 |
| 恢复/receipt/启动检查/恢复存储/Run revoker | 1,125 |
| Core + Documents + staging + data gate | 459 |

不含Room backup DAO/mapping、测试或新旧原型。行数不能单独判断过度设计：严格读入校验、整体恢复与收据承担明确安全责任；更值得缩减的是导出访问/重复staging及自建包装路径，不能为了减少代码删恢复不变量。

本轮旧C2源码/新size probe不是生产部署；旧G1恢复内核继续保留。旧[导入证据§4.2](2026-10-25-g1.md#42-2026-10-25-有界流式导入资源与恢复故障验收)的notes恢复222.703s、大字段恢复247.545s，恢复后TotalPss分别64,729/64,945KiB（约63.2/63.4MiB）。表头是**恢复后**PSS，不将其升级为全过程峰值；本次未重跑，不外推为普通用户耗时或真机验收。

## 7. 审计边界

本报告确认产物文件长度、ZIP条目构成、成对probe差值、最小host启动后PSS、代码阶段与旧原型回执算术；没有测设备完整安装总占用、商店下载、F1正式UI稳态PSS、SQLite专用缓存或瞬时压缩spool峰值。实际产品轻/中/重规模、新架构资源模型与新原型结果由主轻量化复审草案拥有；这里不决定新正式限额或采纳任何生产架构。

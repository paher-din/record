# 轻量备份隔离原型：API34运行证据

实验日期：2026-10-26。状态：**研究原型完成；不构成生产G1验收或架构批准**。本轮授权见[Plan §35.5](../../specs/record2/v1/plan.md#355-轻量化完整备份架构重新评估2026-10-26)，结论归[复审草案](../../architecture/record2-g0-lightweight-backup-review.md)。

## 1. 范围、环境和可复查回执

19次独立运行中18次通过，1次初始ZIP编码失败。它们包含性能、故障和连接行为实验，**不是18项完整产品验收**。所有运行的APK SHA、造数/实验PID、ADB参数、instrumentation原文和metrics已保存为[运行回执JSON](2026-10-26-lightweight-prototype-run-receipts.json)，不只依赖被Git忽略的build日志。强停实验的hold参数由保存的verify参数及runner选择器还原，已明确标记；其余已保存命令数组直接照录，不能把还原参数说成原始命令日志。

- 工作树：`C:/Users/p3148/.codex/worktrees/g0-export-review/record`；基线G1 `af62eef0e66ffd29129a0120ce0aeadf1582ef37`，生产源码冻结。
- AVD `record-api34` / `emulator-5594` / API34 / x86_64；fingerprint：`google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys`。没有wipe或真实用户数据操作。
- Room2.8.4；U仅androidTest使用BundledSQLiteDriver2.6.2 / SQLite3.50.1；S使用系统SQLite3.39.2。版本是实验身份，**不是生产版本准入**；已知修复及官方2.7.1补查见[官方研究§5](2026-10-26-lightweight-official-research.md#5-sqlite-成熟度不等于任意旧打包版本都可免审)。
- 原型：[LightweightZipPrototype.kt](../../../record2/app/src/androidTest/kotlin/com/paherdin/record2/backup/prototype/LightweightZipPrototype.kt)，复用[G0 fixture](../../../record2/app/src/androidTest/kotlin/com/paherdin/record2/backup/prototype/G0SnapshotArchitecturePrototype.kt)；[runner](../../../record2/tools/run-lightweight-backup-prototype.py)确保造数和导出不同PID、设备安装APK SHA与本地产物相同。
- 50k单遍U运行：造数PID6813、导出PID6864；app SHA `b584f7c1c1b3f33cbcd75c1f25b2c080feb1fd5542d22d243dbff573c4d12677`，test SHA `33259366d7d34a92865dfb1a84c0887fe2a5274db4cad6ea16a72034a13e7768`。不同实验的测试APK有迭代，逐项身份以JSON为准，不能用最终APK反向宣称早期运行。

## 2. 新假设和断言

**U：** 同一bundled引擎的Room writer/managed reader；`useReaderConnection`内DEFERRED事务先读metadata固定快照，再让独立executor真实writer提交Note/account/revision更新。writer必须在3s内完成。全部12段按独立seed/正式codec生成的预期行数及SHA核对，备份仍含完整前态。受管理reader单独执行UPDATE必须报readonly；不声称其底层是OS只读句柄。

**S：** 原系统Room保持不变；OS只读source执行`VACUUM INTO`，成功后关闭source，以系统OS只读静态target读取。键Cursor仅返回rowid，正文用缓存scalar `simpleQueryForString`，避免正文跨CursorWindow。随后同样并发写原库并检查前态。新S运行的并发提交发生在复制完成后的静态读取期间，不能冒称本轮逐项测了复制中写入；旧C2已有复制期间写入证据，详见[旧复审](../../architecture/record2-g0-export-architecture-review.md)。source/target均使用禁止自动删库的损坏handler。

两条路线共用平台`ZipOutputStream`；rowid spool为8B/行，无整批JSONL或压缩段文件。`size-first`先压缩到null计算CRC/SHA/大小，再同快照重读写ZIP；`stream`通常单遍，发现100:1违规后关闭暂定包、同快照最多重读一次，仅相应段使用STORED；不放宽原读取器。manifest最后写，非空DEFLATE使用descriptor，空段STORED/0/0。

每个成功往返都经过未修改的`LogicalZipReader.verify`、原`RestoreService.preflight`、原`RoomRestoreStore`单事务恢复与总行数/Note计数断言。恢复target是另一文件、使用原framework Room配置；**它证明格式与既有导入器兼容，不证明将生产恢复接缝统一到新driver后的事务行为**。跨候选相同ZIP/hash加上独立逐段内容oracle共同支持内容一致结论；不以自动往返自身代替oracle。

普通fixture包含账户、分类和Note，其余段为空；Note正文主要是40字符、固定Random(42)，包含软删状态。新`--high-ratio`把一条Note改为7,000,000个重复字符，独立重算该段oracle。没有用全部AI状态/混合真实五年历史冒充这些试件。

## 3. 性能和资源原始值

下表PSS为KiB，临时文件为B；时间仅导出至最终ZIP核验，不含之后的restore。采样从获取快照前启动、导出后停止，100ms周期，覆盖首行返回前等待；不能排除采样间短峰，也不是硬RAM约束。

| Run ID | 路线/打包 | 行 | 导出ms | 峰值PSS / 相对基线增量KiB | 工作文件峰值B |
|---|---|---:|---:|---:|---:|
| `1c6b057e02b54f249e73a82d250078dd` | S/size-first | 1,000 | 2,212 | 83,005 / 21,319 | 482,135 |
| `2a716c52ec284da599cbeec3f04c97d1` | U/size-first | 1,000 | 2,808 | 90,259 / 28,044 | 56,152 |
| `e0fc49a6a23a478093f31020a7b9be39` | S/size-first | 10,000 | 13,518 | 115,516 / 53,821 | 2,846,054 |
| `01290cb8066e45c98b7cbe7cd293b2dd` | U/size-first | 10,000 | 12,186 | 110,786 / 48,577 | 515,431 |
| `524690078a9d4f47b4d95ad0ea5d46f1` | S/size-first | 50,000 | 45,841 | 115,707 / 54,003 | 13,499,160 |
| `26df565201c74c3b93396fb1e215a34a` | U/size-first | 50,000 | 35,346 | 115,910 / 53,651 | 2,554,649 |
| `6d26a25c6e4f4c008f7925fd420204a4` | U/stream | 1,000 | 1,715 | 79,255 / 16,965 | 56,152 |
| `3f9fc5c7f81d4831990321aa71b05207` | S/stream | 10,000 | 10,076 | 114,793 / 53,073 | 2,846,054 |
| `57877c659b9a4d20ac3c55735c6e3c1e` | U/stream | 10,000 | 8,633 | 112,984 / 50,808 | 515,431 |
| `5910142ca50f41e393086b5b88e7a534` | U/stream | 50,000 | 25,161 | 114,107 / 51,841 | 2,554,649 |
| `250980ee757f47628911bd475d1749f6` | U/level0 | 10,000 | 8,615 | 109,996 / 47,747 | 2,186,576 |
| `d355f56c7ca34b14b339ae104adc922e` | U/stream/config审计 | 1,000 | 1,898 | 79,345 / 17,040 | 56,152 |
| `759585e506e945d88d4ffed10a01e541` | S/stream/config审计 | 1,000 | 1,667 | 75,006 / 13,244 | 482,135 |

同规模默认压缩的S/U、size-first/stream得到相同ZIP SHA：

| 总行 | 逻辑B / ZIP B | ZIP SHA-256 |
|---:|---:|---|
| 1,000 | 208,581 / 46,923 | `5105fc54e799207139a16acfa5c13e319107f9a970a076a2225af410e2a7b5a2` |
| 10,000 | 2,101,190 / 434,200 | `50e8c3ce4cf589d7e9c8b917504d83664ac04e9d166f8656e6d6b56d59b23113` |
| 50,000 | 10,552,790 / 2,153,417 | `48479f98cb26abd550bdd50867c4f4841f5d046888dd089b43cd9f31d3aef01e` |

50k U：baseline62,266KiB，Java heap峰值49,402,280B、native heap13,272,672B；WAL4,152→16,512B，增长12,360B；最低剩余4,804,198,400B。contentDigest `07db60e49d8acfe05a8ad5c1ba3317be3b73f0a0c81aa80cad9c5fc3958e6c9e`，恢复31,313ms。Java/native峰值不同步，不能相加代替PSS。

S的1k/10k/50k物理snapshot分别425,984/2,330,624/10,944,512B，size-first采集耗时7/22/57ms。任务目录峰值包含S/keys/ZIP/fixture，**未覆盖任务目录外的SQLite临时文件或采集journal家族**；这些未知项不能填0，S预算公式另计Q_copy和排序工作区。现有源DB不计新增副本，WAL单列。U实测编译TEMP_STORE=3，排序主要在native内存，不保证任意键分布的峰值。

最后config运行均page_size4096、cache_size=-2000、mmap_size0；U temp_store2、S temp_store0。这只是连接设置，不是实际cache驻留或内存cap。level0 ZIP为2,105,345B，约默认的4.85倍，用时无明显改善；不选默认，亦不外推到所有文本分布。

## 4. 故障、边界和失败

| Run ID | 实验 | 结果及边界 |
|---|---|---|
| `0a4025e184f3468a92cc5ca6ea4583b2` | 首次S/1k/size-first | **FAIL**：`entry extra fields, comments and split disks are not allowed`；峰值77,414KiB。1980边界时间触发平台extra；改writer固定日期到DOS范围中部2000年后通过，原reader不改。失败原文保留于JSON |
| `bd1ef0c58e58496887a2a07649ef8d26` | U/1k/stream取消 | PASS：50行后CancellationException；14表摘要不变、无成功标记、随后真实写成功 |
| `2e5cb383259e42269768d4a8b0cdd513` | U/1k/stream输出配额1,024B | PASS：IOException，14表摘要不变、无成功标记、随后写成功。**不是实际文件系统ENOSPC** |
| `bca3940d26d5400f8cdcff971168fccb` | U/1k/stream真实强停 | PASS：50行flush但ZIP未关闭时，host确认PID6236后`am force-stop`；新PID6301证明14表原样、半ZIP被原reader拒绝、无成功标记、随后写成功。造数PID6185 |
| `70adce428ccc45b596c05c86f4c45ec2` | U/1k/stream高压缩比大字段 | PASS：两遍、1个非空段STORED、完整原字段恢复；L7,208,538B / ZIP7,211,166B，4,746ms，文件峰值7,220,395B，PSS148,547KiB。大字符串在采样前注入，baseline120,770KiB，不能拿增量27,777KiB当完整大字段内存成本 |
| `d16dc44dc5b54e09acd19c47ee26a2e8` | U managed reader UPDATE | PASS：实际UPDATE因readonly被拒绝；不等于OS OPEN_READONLY |

故障试件AI表为空，不能代替G1全故障/Agent联合矩阵。原型取消发生于行边界；没有证明native长排序/长step可即时被Job.cancel打断。新S的copy未补取消/真实强停/磁盘满全矩阵；旧独立copy测试只作为历史机制证据。

## 5. 命令与产物

以下为可复跑入口；`am instrument`参数及原文在回执JSON，强停hold参数的还原边界见§1。构建根必须显式指向record2，size-probe为另一个隔离Gradle根。

```powershell
.\record2\gradlew.bat -p record2 :app:assembleDebug :app:assembleDebugAndroidTest
python record2/tools/run-lightweight-backup-prototype.py --adb C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe --route unified --rows 50000 --packing stream --install
python record2/tools/run-lightweight-backup-prototype.py --adb C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe --route system --rows 50000 --packing size-first
python record2/tools/run-lightweight-backup-prototype.py --adb C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe --route unified --rows 1000 --packing stream --fault cancel
python record2/tools/run-lightweight-backup-prototype.py --adb C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe --route unified --rows 1000 --packing stream --fault space
python record2/tools/run-lightweight-backup-prototype.py --adb C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe --route unified --rows 1000 --packing stream --fault kill
python record2/tools/run-lightweight-backup-prototype.py --adb C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe --route unified --rows 1000 --packing stream --high-ratio
python record2/tools/run-lightweight-backup-prototype.py --adb C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe --route unified --rows 1000 --reader-check
```

runner原始目录：`record2/build/lightweight-backup/runs/<runId>/`；独立容量模型、包体probe及旧C2巨大试件分别见[容量](2026-10-26-lightweight-capacity-model.md)、[成本](2026-10-26-lightweight-cost-audit.md)和[旧复审](../../architecture/record2-g0-export-architecture-review.md)。这些不是同一次实验。

## 6. 不得由本轮推出的结论

没有重跑新sink的500k+17大字段组合，没有证明新预算的真实ENOSPC/在途最大写超调，没有完整混合AI历史、正式UI/SAF、真机耗时或硬资源cap验收；旧C2最大规模数据不能挪用为新sink PASS。尚未迁移34处legacy事务和平台读取接缝，不能宣称U生产恢复已验收。500k现有合同在负责人另行批准前继续有效，150k性能目标不是降低能力的授权。

实验支持“去除S/L重复物化、使用成熟流式ZIP能显著降低实际工作文件”的架构判断；不证明任意设备只需上述采样字节即可安全启动。新预算仍须有工作cap、剩余空间监控、明确失败与完整验证。PR #31的导出P1仍OPEN，本轮止于设计。

## 7. 交付静态核对

收口核对通过：6份本轮Markdown的UTF-8、代码围栏、尾随空格及36个本地路径/锚点；19份运行回执与原始summary逐项一致，成功默认压缩包的跨路线SHA一致；4个Python脚本AST/编译检查通过，pyc只写入忽略的build目录。`git diff --check`通过。

`git diff --quiet -- record1 record2/app/src/main record2/app/schemas record2/gradle/libs.versions.toml`返回0，HEAD仍为`af62eef0e66ffd29129a0120ce0aeadf1582ef37`。Gradle仅已有androidTest实验依赖及独立size-probe，没有生产SQLite依赖。此处只记录本地冻结检查；没有用它声称GitHub CI、PR批准或生产资源验收通过，也没有重跑未受修改的整套G1测试。

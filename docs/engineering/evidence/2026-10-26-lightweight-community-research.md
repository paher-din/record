# 轻量完整备份：Android 开源实现核对

2026-10-26。本轮仅调研、下载公开源码并核对 Record 现有读取器；没有运行第三方应用/设备实验、引入生产依赖、修改 G1 或作生产选型。C2 是重新比较的候选，不是默认方案。

标记：**[S]** 固定 commit 源码直接观察；**[D]** 官方文档；**[I]** 源码推断；**[H]** 尚待 Record 原型验证。社区应用存在备份功能，不等于其一致性、恢复或秘密排除满足 Record 合同。

## 1. 可复核取证

实际访问公开 GitHub API 获取各仓库默认分支当前 commit，再以该完整 SHA 下载 raw 源码。共下载27份相关文件，未克隆或执行第三方工程；未修改其仓库。原始 API tree、选择清单、每文件 raw URL/字节数/行数/SHA-256 留于忽略目录 `record2/build/g0-lightweight/research/community/` 的 `sources.json` 与各仓库子目录。

| 项目 | 本轮固定 commit | commit 时间（UTC） | 核心核对范围 |
|---|---|---|---|
| AntennaPod | `d3e41c6cb37c8e1a060b142686f818999de89859` | 2026-10-25 20:33:14 | DatabaseExporter、PodDBAdapter、FeedDatabaseWriter |
| Tasks.org | `4ea72a1d26c5c4701bce02feb6c21f1d2b15fd1e` | 2026-10-25 14:18:40 | TasksJsonExporter/Importer、CalDAV账户模型、Preferences、系统备份适配 |
| MyExpenses | `31246c3576ac0668d2e00f7a5fc69f05306c941a` | 2026-10-22 17:43:07 | provider备份/恢复、DataModule、ZipUtils、版本/完整性检查 |
| Quillpad | `c12ff24d15bad1852332164812f89c00177c2967` | 2026-10-09 04:54:07 | BackupManager/Service、Backup DTO、migration/attachment handlers |

关键源码下载摘要（校核下载原文，不是APK摘要）：

| 文件 | 行数 | SHA-256 |
|---|---:|---|
| AntennaPod DatabaseExporter.java | 107 | `aa14c576de09268bc50f78ceef20542ae35dd60a07e1b8285f2ef26205385791` |
| TasksJsonExporter.kt | 220 | `cf4dfc1c15273b7241c25222c742f83dd41f678dc5dd1b45600d3e61c1ba96da` |
| MyExpenses BackupUtils.kt | 117 | `237de4966cf1bfa0db7667e5c1cdf7319918082fca46055d45f89674e8a1305a` |
| MyExpenses ZipUtils.kt | 146 | `26e90c0728539f608909bdeee15b78909d3bc08141a1df9080e372646ef28744` |
| MyExpenses DataModule.kt | 155 | `defd73c20b97dd9fee12aaba1dca1c246d25317e1d9c1bfede072c15aa020de0` |
| Quillpad BackupManager.kt | 273 | `8928bf7a26f800aa1ea36a444ab27de147907203ae4083c60f70d48a8b8f21ee` |

这些模块行数只说明该切面的实现规模；不把UI、数据库owner、依赖或测试排除后宣称整个产品只需这些行数。

## 2. 四个真实应用的实现与适配边界

### AntennaPod：物理主库导出不是已证明的安全模板

**[S]** [DatabaseExporter](https://github.com/AntennaPod/AntennaPod/blob/d3e41c6cb37c8e1a060b142686f818999de89859/storage/importexport/src/main/java/de/danoeh/antennapod/storage/importexport/DatabaseExporter.java#L45-L71) 的流程是：调用adapter的WAL checkpoint、close、复制主库到临时DB、只读核对`user_version`等于当前版本，再流式复制到SAF。外部写出结束用字节计数与目标stat大小比较；没有在该模块看到完整目标哈希回读。

**[S]** 必须继续追owner：[PodDBAdapter.open/close](https://github.com/AntennaPod/AntennaPod/blob/d3e41c6cb37c8e1a060b142686f818999de89859/storage/database/src/main/java/de/danoeh/antennapod/storage/database/PodDBAdapter.java#L398-L432) 当前均为no-op；`walCheckpoint`执行FULL但只记录结果，异常也只记录。不能依据调用名把这段过程描述为“已经关闭所有连接后复制”。

**[I]** 已读路径没有建立复制期间覆盖所有writer/checkpoint的全局不变期，也没有证明checkpoint的busy结果可接受。它不能作为 Record “WAL下直接复制主文件即可安全”的依据；本轮未运行它，不声称已观察到损坏。

**[S]** [导入](https://github.com/AntennaPod/AntennaPod/blob/d3e41c6cb37c8e1a060b142686f818999de89859/storage/importexport/src/main/java/de/danoeh/antennapod/storage/importexport/DatabaseExporter.java#L77-L105) 把源复制成临时DB，拒绝高于当前的版本，然后删除现库和sidecar、移动目标。它是物理替换，不是 Record 已验收的单事务逻辑恢复；不可移植该恢复内核。数据库模型包含feed认证字段，物理备份本身不等于按字段排除凭据。

**可借鉴：** 简单、可读的导出owner、SAF关闭/字节数失败处理、明确版本检查。**不能借鉴为既成保证：** 复制一致性、凭据排除、原子恢复或哈希完整性。它使用Commons IO，但Record已有Kotlin/Java流能力，不需要为copyTo另加此依赖。

### Tasks.org：按实体写逻辑JSON，保留流式思想而非照搬一致性

**[S]** [TasksJsonExporter](https://github.com/tasks/tasks/blob/4ea72a1d26c5c4701bce02feb6c21f1d2b15fd1e/app/src/main/java/org/tasks/backup/TasksJsonExporter.kt#L132-L184) 直接用UTF-8 BufferedWriter写JSON，包含app版本和时间；逐task取出task、alarm、geofence、tag、comment、attachment等，再写其他集合/设置。它先生成一个内部JSON文件，再复制外部文档/上传，而非完整物理DB加完整JSON加ZIP三份并存。

**[S]** `getAllTaskIds()`的全集列表和dirty-state map仍驻留，某些DAO也取全集；这是“正文逐task流式”，不能称为总内存O(1)。导出入口及该循环未包住一个跨表读事务；本轮未证明外层所有业务writer暂停，故没有Record时点快照保证。当前样本的数据层已经使用Room3，不能把它的API配置当作Record 2.8.4已验证路径。

**[S]** [TasksJsonImporter](https://github.com/tasks/tasks/blob/4ea72a1d26c5c4701bce02feb6c21f1d2b15fd1e/app/src/main/java/org/tasks/backup/TasksJsonImporter.kt#L110-L154) 先导入元数据再导入任务，保留version分支和成功/跳过计数；下层用Android JsonReader解析并跳过未知项。这属于合并/升级型导入，已读入口不能证明一次整库替换原子性，且lenient JSON策略不等于Record严格校验。

**[S]** [CalDAV账户模型](https://github.com/tasks/tasks/blob/4ea72a1d26c5c4701bce02feb6c21f1d2b15fd1e/data/src/commonMain/kotlin/org/tasks/data/entity/CaldavAccount.kt#L15-L43) 用serialization `@Transient`排除password、local id和error；这是值得借鉴的显式导出DTO/字段排除。另一方面，[Preferences.getPrefs](https://github.com/tasks/tasks/blob/4ea72a1d26c5c4701bce02feb6c21f1d2b15fd1e/app/src/main/java/org/tasks/preferences/Preferences.kt#L707-L708) 只按值类型过滤，不能据此证明所有秘密设置都被排除。

**[S]** [TasksFileBackupHelper](https://github.com/tasks/tasks/blob/4ea72a1d26c5c4701bce02feb6c21f1d2b15fd1e/app/src/main/java/org/tasks/backup/TasksFileBackupHelper.kt) 让Android备份服务保存先前生成的内部JSON。系统服务接管的是已生成文件的运输，不是此应用导出时跨表快照算法。

**可借鉴：** 明确业务DTO、逐实体序列化、版本字段、内部完成文件后再外发。**不能直接迁入：** 全局ID/map规模、不受控多表读取、lenient/合并恢复和宽泛prefs复制。

### MyExpenses：非WAL写事务协调与平台ZIP

**[S]** [DataModule](https://github.com/mtotschnig/MyExpenses/blob/31246c3576ac0668d2e00f7a5fc69f05306c941a/myExpenses/src/main/java/org/totschnig/myexpenses/di/DataModule.kt#L90-L109) 明确关闭WAL。未加密正常备份在[provider.backup](https://github.com/mtotschnig/MyExpenses/blob/31246c3576ac0668d2e00f7a5fc69f05306c941a/myExpenses/src/main/java/org/totschnig/myexpenses/provider/BaseTransactionProvider.kt#L940-L997)中以`@Synchronized`及`beginTransaction`包住物理复制；lenient模式则绕过事务。`backupDb`调用普通FileCopyUtils复制主文件；加密数据库走其cryptProvider解密分支。

**[I]** 这说明其具体产品接受写入协调/非WAL配置，不说明“Record WAL开着，套一个Room事务再copy主文件”安全。锁与文件复制、其他进程/连接的覆盖范围仍要独立证明，且lenient分支不满足Record硬约束。本轮没有复用这种复制原型。

**[S]** [ZipUtils](https://github.com/mtotschnig/MyExpenses/blob/31246c3576ac0668d2e00f7a5fc69f05306c941a/myExpenses/src/main/java/org/totschnig/myexpenses/util/ZipUtils.kt#L32-L109) 使用平台`java.util.zip.ZipOutputStream`直接写SAF，文件/附件经固定1KiB buffer流式读写；可选的是外层EncryptionHelper，不是必须引入Zip4j或一个native ZIP库。[BackupUtils](https://github.com/mtotschnig/MyExpenses/blob/31246c3576ac0668d2e00f7a5fc69f05306c941a/myExpenses/src/main/java/org/totschnig/myexpenses/provider/BackupUtils.kt#L26-L58) 建立私有工作目录、完成后finally清理。默认业务备份失败传播；附件不存在及lenient选项有更宽松行为，不能照搬为Record全量成功。

**[S]** [恢复前检查](https://github.com/mtotschnig/MyExpenses/blob/31246c3576ac0668d2e00f7a5fc69f05306c941a/myExpenses/src/main/java/org/totschnig/myexpenses/di/DataModule.kt#L113-L142) 拒绝新于当前的DB版本，并检查数据库完整性；provider恢复关helper再复制数据库。本研究不把该物理恢复当作Record receipt/代际转换的替代。

**安全边界：** 备份包含shared_preferences与DataStore文件的实际副本。不能把“物理数据库+所有设置”当作Record排除BYOK、grant、owner资格的方案；Record现有显式逻辑字段清单更适合继续保留。

### Quillpad：平台ZIP有效，全量对象树不适合直接复制

**[S]** [BackupManager.createBackup](https://github.com/quillpad/quillpad/blob/c12ff24d15bad1852332164812f89c00177c2967/app/src/main/java/org/qosp/notes/components/backup/BackupManager.kt#L45-L89) 将全部Note取为Set，再建立notebook/tag/reminder/join/idMapping集合。[Backup DTO](https://github.com/quillpad/quillpad/blob/c12ff24d15bad1852332164812f89c00177c2967/app/src/main/java/org/qosp/notes/data/Backup.kt#L15-L30) 将整对象树`encodeToString`；备份service也确实先调用serialize后传入ZIP。没有在已读调用链看到覆盖所有repo读写的一个事务。

**[S]** [createBackupZipFile](https://github.com/quillpad/quillpad/blob/c12ff24d15bad1852332164812f89c00177c2967/app/src/main/java/org/qosp/notes/components/backup/BackupManager.kt#L237-L273) 用平台ZipOutputStream直接向SAF写附件与backup.json，attachment复制buffer为2KiB；finish/use关闭成功后才回调completion。附件可选择包含文件、仅保留信息或完全排除；这是一项产品范围选择，不等于Record可丢关系或历史。

**[S]** [restoreNotesFromBackup](https://github.com/quillpad/quillpad/blob/c12ff24d15bad1852332164812f89c00177c2967/app/src/main/java/org/qosp/notes/components/backup/BackupManager.kt#L92-L156) 用名称匹配/重建ID映射，插入Note与关系；zip读入时把backup.json累积为StringBuilder再整体解码。它是合并笔记模型，不能拿来恢复Record不可变Operation/已应用ID事实，也不适合作为有界大包导入替代。

**可借鉴：** 标准库ZIP、附件边界显式化、关闭后完成回调。**不复制：** 全量对象图+String、逐repo非固定快照、合并恢复。

## 3. 已核实、能直接降低Record复杂度的公共能力

### ZIP无需新生产native库

**[D]** Android提供[ZipOutputStream](https://developer.android.com/reference/java/util/zip/ZipOutputStream)及STORED/DEFLATED两种方法。它会使用平台实现/运行时内存，并不是“零native运行成本”；但不要求给APK打包另一套ZIP或SQLite native库。

**[S]** Record现有G0 §3.1已经允许数据描述符，且明确ZIP物理条目顺序不参与校验。[LogicalZipReader](../../../record2/app/src/main/kotlin/com/paherdin/record2/backup/LogicalZip.kt)按名称取中央目录项；499～523行校验可选描述符；641～645行仅要求**空段**STORED/0/0且无描述符。不能用“现有格式不支持streaming ZIP”来否决标准库，或无必要地提出新formatVersion。

**[H]** 最小兼容原型可以非空DEFLATED条目流式写、边写边算row/hash/bytes，空段预先声明STORED/size0/crc0，最后写manifest，再由原LogicalZipReader/StreamingBackupValidator验证。必须设置可用DOS日期避免自动extended timestamp extra，保持无extra/无ZIP64/13条目；不能直接假定任何默认ZipEntry都合约。

原100:1压缩比仍有真实成本：一遍DEFLATE结束才知道比值，无法事后把已经写出的entry改成STORED。需比较：同一快照两遍测/写；压缩spool与可重读回退；或明确的新产品边界。不能删该检查来得到成功。

**[H]** 一个额外可比较的极简包装选择：非空条目用DEFLATED+level0（NO_COMPRESSION），空段仍STORED。它仍使用既有方法8和描述符；ZIP体积约等于逻辑数据量，压缩比接近1，避免事后回退与大压缩spool，CPU更省，但文件明显大于默认压缩。是否值得采用取决于普通用户实际L与候选ZIP预算；不得为了这个方案直接改当前64MiB正式限制。须先用原reader测试，而非把本段推断标为PASS。

### 系统Auto Backup不是手动完整备份的替代

**[D]** [Android Auto Backup](https://developer.android.com/identity/data/autobackup) 有25MB/app-user额度、系统调度/账号/传输条件，默认排除cache及noBackup；备份时会停止应用。它可以运送已生成的适当文件，但不提供用户随时选择SAF文件、核验完整导出并整体恢复的同等产品语义。Record现有禁用/排除规则不能在本轮悄悄放开。

## 4. 从样本得到的两条可组合路线，而非再造一个全备份库

设 `L` 为需导出的完整逻辑内容、`Z`为最终ZIP、`S`为物理一致副本、`W`为仍钉住的WAL增量、`K`为排序/索引工作空间。下表是模型，不是本轮测量。

| 可组合路线 | 来源一致性必须由什么保证 | 实际私有临时存储模型 | 成熟可复用部分 | 待验证/代价 |
|---|---|---|---|---|
| 统一受支持driver读事务→逐行逻辑→私有标准ZIP→验证→SAF | 一个真实reader事务与物理连接贯穿全部段；正常writer独立 | 约`Z+K+W`，无S和完整L副本；两遍/回退时另计spool | SQLite事务/step、平台ZIP、现有严格读取器与恢复 | native依赖实际ABI成本、现有Room事务兼容、WAL预算、排序/行内存；本社区研究不替官方路线作结论 |
| 经证明的短时协调/官方采集→静态来源→平台读取/标准ZIP | 协调覆盖真实所有writer或引擎提供一致副本；绝非凭close函数名 | 若有静态副本则约`S+Z+K`；可分阶段复用，不能继续默认`S+L+Z+384*2` | 已有写入gate/源库owner、平台SQLite/VACUUM/复制API、平台ZIP | 暂停时长、S是否包含排除内容、恢复/取消/低空间；写协调本身不证明WAL主文件copy安全 |

两者都需要保持私有完成文件验证后才外发、SAF关闭/回读和失败不报成功。直接写SAF最多少一个内部Z，但使输出中途不可验证/更难恢复，不能照搬社区样本就取消Record的交付完整性证明。

## 5. 本轮结论边界

四个样本没有给Record一个可直接接管全部14表/代际/Applied/Pending/凭据排除的“万能Android备份库”。它们真正可复用的是成熟的序列化、固定buffer、标准ZIP、文件owner和版本检查；它们更宽松的快照或合并恢复语义不能替Record验收。

值得优先验证的是**现有格式已支持标准ZIP流式写**这一条新证据；它直接关系到是否还需要L级JSONL staging和自写ZIP writer。来源一致性的选择应与打包方法分开比较：同一轻量打包可以配统一driver或经证明的静态采集，不必为了减少staging就默认再次采纳旧C2预算。

所有性能、内存、APK/ABI增量及Record原型PASS/FAIL由主审计与实验报告拥有。本文件没有运行设备，也没有把源码推断写成实测。

# 轻量化完整备份：官方接口与实际接缝复核

2026-10-26。**设计研究，不是选型批准或生产实现。** 负责人已否决前轮 C2 的 GiB 级启动预算，本轮不默认 C2 为主选，也不把降低预留数值当成架构修复。保留正式 ZIP、恢复与规模合同；没有修改生产/Schema/依赖/Accepted 状态或旧审阅报告。

本文区分：**源码事实**来自固定发布源码或正式源文件；**推断**是据此得到的设计影响；**旧实测**仅引用已有原型；**待验证**没有在本研究中运行设备。以下不重跑已知递归 SQL 失败。

## 1. Room 统一 driver：不是零改动，也不等于重写整库

固定一手来源：[Room runtime Android 2.8.4 sources](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime-android/2.8.4/room-runtime-android-2.8.4-sources.jar)，SHA-256 `86f67184adaf59862c237296ebe092bc45923e37d8d74dccd5a031bb7c5a09ae`。以下路径相对该 JAR。

**源码事实：** `androidMain/androidx/room/RoomDatabase.android.kt` 中的 `androidx.room.withTransaction` 无 configured-driver 分支：

```text
withTransaction → beginTransaction → internalBeginTransaction
                → openHelper.writableDatabase
```

`openHelper` 在配置 `SQLiteDriver` 后抛 `IllegalStateException`。因此“正式源没有直接写 openHelper”不能推出只需加 `setDriver`。另一方面，自动生成 DAO 的 driver 通路已经存在，不能把调用点迁移扩大为重写业务算法。

**本分支静态清点：** 正式 `app/src/main/kotlin` 有9个 storage 文件、34个 `database.withTransaction` 调用，包含读一致性和写事务用途：

| 文件 | 调用数 |
|---|---:|
| RoomAgentHistoryService | 7 |
| RoomAiSecurityService | 6 |
| RoomCommitService | 2 |
| RoomFinanceService | 3 |
| RoomLinkService | 4 |
| RoomNoteService | 4 |
| RoomProposalService | 3 |
| RoomRestoreStore | 1 |
| RoomTimelineService | 4 |

当前 KSP 生成的普通 DAO 查询、insert/update 使用 `performBlocking` 和 `SQLiteStatement`。固定 runtime 的 `androidMain/.../util/DBUtil.android.kt` 与 `commonMain/.../coroutines/ConnectionPoolImpl.kt` 支持通过 connection thread-local 将同步 DAO 调用复用到当前借出的连接；故不能先验要求把全部 DAO 改成 suspend。

**明确例外：** `BackupDao.rawQuery(SupportSQLiteQuery): Cursor` 生成 `__db.query(query)`，仍依赖 openHelper。正式调用点是 RoomRestoreStore 中的 `PRAGMA defer_foreign_keys=ON`；可以研究在同一个新事务 Transactor 上用 `usePrepared` 执行这条连接 PRAGMA。它不要求重写流式导入，但必须证明 FK 延迟、rollback、receipt 和 unknown-commit 语义保持。

**其他同文件入口：** BackupSnapshot、ReadOnlyTargetInspector（两处）、RestoreStartupGate 仍通过系统 `SQLiteDatabase.openDatabase` 读取正式文件。若 Room 换 bundled，必须同步统一这些 owner 的引擎，或证明不同时打开；不能只迁移 writer 后留下同文件双引擎。[SQLite 官方锁协调说明](https://sqlite.org/howtocorrupt.html#multiple_copies_of_sqlite_linked_into_the_same_application)

**设计影响与待验证：** 可评估一个明确的 storage transaction 接缝，替换上述34个调用及必要的 return label，内部用 `useWriterConnection + immediateTransaction`，保留现有 block、gate 和业务校验；读事务按是否确有初始化写分别处理。只需针对这一接缝做同步 DAO 嵌套、取消、异常、CAS 和恢复原子测试，不凭调用数宣称风险很小。此研究既未完成该迁移，也不授权改9个文件。兼容 wrapper 可用于有限短调用，但其大 query 全量 MatrixCursor 行为不适用于流式导出。

## 2. 独立系统只读 SAVEPOINT：公开接口可表达，连接保证要如实限定

**官方 SQLite 语义：** [最外层 SAVEPOINT](https://sqlite.org/lang_savepoint.html) 等价 BEGIN DEFERRED；它不会因为一条 SELECT reset 而自动结束，RELEASE 不存在的 savepoint 会失败。

**固定 Android14 源码事实：** [SQLiteDatabaseConfiguration](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/database/sqlite/SQLiteDatabaseConfiguration.java) 的只读配置不请求 WAL 池扩容，默认 idle timeout 为 Long.MAX_VALUE；[SQLiteConnectionPool](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/database/sqlite/SQLiteConnectionPool.java) 此时 max pool size 为1，不因空闲关闭连接。这里的池配置不更改物理数据库原有 WAL 模式。

公开 `openDatabase` / `OpenParams` / `execSQL` / query / compiled statement 足以表达：私有 OS只读对象 → query_only → SAVEPOINT → 多条有界读取 → RELEASE。不需要本项目反射或私有 JNI。`setIdleConnectionTimeout` 是公开但已弃用 API，不能把弃用警告压掉后宣称获得新的官方长期保证。

**推断：** 在上述 AOSP 单物理连接、不回收、不重配的前提下，框架把连接交回私池不会丢失原生 SAVEPOINT；私有对象不交给 Room、不 enable WAL、不共享、不在错误后重连，可以维持跨表读事务。正常 writer 使用系统同一引擎，没有双引擎同文件问题。

**不能由 RELEASE 单独证明的事：** RELEASE 成功只证明最终执行它的连接仍持有该 savepoint。如果某 OEM/未来实现把私池扩为多个连接，中间 SELECT 可能用了其他连接，最后仍可能回到原连接 RELEASE 成功。公开文档没有单独保证此私池永远为1。因此它是有明确实现前提的候选，不是已证明全 OEM 的公开显式读事务 API。

**fail-closed 要求：** 任一 DB 错误、取消、RELEASE 失败都中止整个操作；本次操作不重新 SAVEPOINT/重连后拼接。禁用默认会删库的损坏处理器；不使用框架 `inTransaction()` 证明原生 SAVEPOINT。需要新的轻量原型验证真实十二段、关闭 cursor 后等待/重新读取、持续 writer、丢失 savepoint 的最终拒绝路径及资源回收影响。旧小窗口 probe 的 PASS 仅是机制证据，不能替代这些测试；也不应重跑旧递归 SQL 来证明这个新路径。

## 3. 物理备选的必要空间：按阶段并存量核算

[Online Backup API](https://sqlite.org/c3ref/backup_finish.html) 需要一份目标 DB，支持按页 step；[官方说明](https://sqlite.org/backup.html#file_and_database_connection_locking) 指出外部连接写入可导致增量复制重启甚至饥饿。固定初始快照应维持 source 读事务；若允许完成时的其他一致时点，必须另审可见性语义，不将“重启”误写成必定产生混合快照。

[VACUUM INTO](https://sqlite.org/lang_vacuum.html#vacuum_with_an_into_clause) 创建紧凑一致目标，不把它复制回活库，不能直接套用普通 VACUUM 的双倍源库空间公式。目标物理体积仍受全部表、索引和非导出内容影响，不能用256MiB逻辑上限替代。两种物理路线都不是零额外存储，目标需要保留到最后一次逻辑读取完成。

**预算推断：** 定义每个卷上的实际新增文件量：物理目标 `P(t)`、键与排序文件 `K(t)`、数据段/压缩 spool `F(t)`、ZIP `Z(t)`、可见及不可见 SQLite 临时空间 `Q(t)`、源 WAL 增量 `W(t)`。需要的新增峰值应依据生命周期计算 `max_t(P+K+F+Z+Q+W)`，再加批准的余量。既有源 DB 不是新分配量，不能无理由重复预留一次；卷分离时分别核算。

复制阶段可令 F/Z/K 为0；关闭 source 后，提取阶段不再因本导出 reader 钉住源 WAL。`MAX_EXPORT_WAL_GROWTH=384MiB` 是中止上限，不天然要求每个阶段都预先保留一次384MiB。反过来，删掉这项预留也不能保证有空间或能完成：仍需复制前估计、真实两卷余量/目标增长监控、原生中断与中止后可重试证据。source页数只能是估计，不能证明并发复制目标的绝对上界。

**本项目现有合同提供的减量机会：** G0 §3.1 已明确 ZIP 条目物理顺序不参与校验，manifest 内部段顺序固定即可。当前 writer 先保留全部段文件只是实现选择，并非必须同时分配256MiB JSONL。可以比较：

- 同一明确快照两遍读取：第一遍计算段摘要/CRC/大小和压缩策略，第二遍直接生成 ZIP；用 CPU/读取次数换少盘量。
- 逐段压缩 spool，写入 ZIP 后按授权回收当前 spool，manifest 最后写；不同时保留全部段。
- 直接无物理目标的逻辑读取路线令 P(t)=0，但 source snapshot 更长，需比较真实 W(t) 与并发写延迟。

以上均为新候选，不能降低校验：空段仍 STORED/0/0；非空段100:1与64MiB整 ZIP 上限保持。压缩比需要改用 STORED 时，必须靠同快照重读或受控 spool 得到原字节，不可静默生成非法包。最终 staging ZIP 关闭/验证、SAF写入/关闭/回读仍保留。当前 LogicalZipWriter 尚未实现这些生命周期。

## 4. Native driver 的体积：必须区分五种量

[Android App Bundle 官方说明](https://developer.android.com/guide/app-bundle) 明确按设备配置交付优化 APK；[apkanalyzer](https://developer.android.com/tools/apkanalyzer) 能比较 APK 与给出下载量估计，[bundletool](https://developer.android.com/tools/bundletool) 可针对 device spec 生成/估算设备 APK 集。所有这些能力都不需要公开发布即可本地检查。

本轮仅静态读取既有缓存 `sqlite-bundled-android:2.6.2` AAR：文件2,537,459B，内含如下四个 native 条目。这不是生产安装实验。

| ABI | ELF未压缩B | AAR内条目压缩B |
|---|---:|---:|
| arm64-v8a | 1,310,776 | 643,477 |
| armeabi-v7a | 1,253,332 | 617,574 |
| x86 | 1,159,896 | 630,845 |
| x86_64 | 1,220,840 | 624,466 |

不能混用：①AAR仓库下载量；②universal APK含全部ABI的增量；③AAB设备split的单ABI下载/文件量；④安装后APK、是否提取native及DEX优化占用；⑤实际载入后PSS/native heap。侧载 universal APK 即使只映射一个ABI，也仍把其他ABI留在APK文件中。

公平成对测量要保持源码、variant、R8、ABI与packaging相同，仅改变依赖/driver方案，记录 `extractNativeLibs` 与条目压缩/对齐。原型 test APK还包含测试代码，不能用其整体增量直接代表release成本；也不能由native二字推断增加几十MiB。本研究没有改Manifest/ABI过滤或发布配置。

## 5. SQLite 成熟度不等于任意旧打包版本都可免审

[SQLite 测试说明](https://sqlite.org/testing.html) 描述多套测试、OOM/I/O/崩溃、模糊测试和覆盖率；[安全说明](https://sqlite.org/security.html) 同时明确缺陷仍会发生。应评估具体版本、构建选项、JNI句柄所有权和实际输入面，而非把成熟native组件与任意SQL/不可信DB权限等同。

截至2026-10-26，[官方发布表](https://sqlite.org/chronology.html) 最新SQLite为3.54.1（2026-10-16）；[AndroidX发布页](https://developer.android.com/jetpack/androidx/releases/sqlite) 稳定版为2.7.1。二者版本号不同，不能推断升级AndroidX就自动包含特定SQLite修复。既有原型实测 bundled 2.6.2 包含3.50.1；系统AVD为3.39.2，这是旧实测，不是本研究的新运行。

具体相关例子：[官方 WAL-reset 说明](https://sqlite.org/wal.html#the_wal_reset_bug) 指出3.7.0～3.51.2的稀有并发write/checkpoint竞争，修复于3.51.3及特定回移版本。3.50.1位于版本范围，但不能仅据此声称当前原型已触发损坏；要核对是否有两连接同时写/ checkpoint 的调用形态。统一driver或来源纯reader不等于新增checkpoint；禁止为“验证”随意引入正式库checkpoint。

版本策略应在候选裁决中明确：固定版本实验、查询实际 sqlite_version/source_id/compile_options、是否需要已修补引擎及其发行渠道、再做受影响回归。本轮不静默升级生产Room/SQLite，也不把最新版本号当作整个方案的安全证明。

### 5.1 生产版本门禁补查：官方 bundled 2.7.1 仍未提供已修复身份

仅下载 [Google Maven sqlite-bundled-android:2.7.1 AAR](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled-android/2.7.1/sqlite-bundled-android-2.7.1.aar)，未改依赖、安装或执行二进制。AAR为2,551,019B，SHA-256：`b5e01f31b0cc42b01a9f12836bfb6ee745b389e5be53a0813acfe12b445788a1`。

静态读取ZIP中的四个ELF：arm64-v8a 1,281,456B、armeabi-v7a 1,254,892B、x86 1,140,764B、x86_64 1,189,688B。每个均检出NUL结尾版本字串 `3.50.1` 和完全相同的完整source-id：

```text
2025-06-06 14:52:32 b77dc5e0f596d2140d9ac682b2893ff65d3a4140aa86067a3efebe29dc914c95
```

这与 [SQLite 3.50.1 官方发布页](https://sqlite.org/releaselog/3_50_1.html) 的source-id逐字一致；[chronology](https://sqlite.org/chronology.html)也将其列为2025-06-06发布。它不是运行 `SELECT sqlite_version()/sqlite_source_id()` 的实测结果，不能由此声称APK实际加载了哪份库、启用了哪些compile_options或已复现特定漏洞。静态回执与各ELF摘要保存在忽略目录 `record2/build/lightweight-backup/research/sqlite-2.7.1-release-gate/static-identity.json`。

**版本门禁结论：** 官方2.7.1 AAR呈现的引擎身份仍在 [WAL-reset影响版本范围](https://sqlite.org/wal.html#the_wal_reset_bug)；[3.51.3官方发布说明](https://sqlite.org/releaselog/3_51_3.html)明确列出该修复，WAL说明也列出3.44.6/3.50.7回移。静态字串不能形式化排除“下游打补丁却保留旧source-id”的可能，但本次没有取得这种官方补丁证明，故**升级AndroidX到2.7.1不能作为已修复证明，也不能留下它作为U路线的已批准生产版本**。

U可以继续作为架构候选；生产版本需要明确发行渠道决策：获得包含可核对修复的官方artifact，或另行审阅维护中的替代driver发行物/可复现自构建及补丁来源、ABI/包体/维护责任，再对真实加载版本与数据库行为验证。这不是建议自动引入第三方或自编native库，也不以限定并发模式、一次完整导出PASS代替已修复版本门禁。本次未下载其他版本、静默升级、修改生产或为任一替代发行物背书。

## 6. 受控写入协调：可以比较，但不是现有导出合同

SQLite 的[隔离保证](https://sqlite.org/isolation.html)属于真实事务和连接语义；一个应用内锁只协调遵守它的调用者。若希望短暂或长时停写，至少要说明：所有业务/初始化/Agent写入口、在途事务提交完成、旁路/跨进程writer、取消与进程终止如何处理。现G0要求正常业务写可在快照期间继续，停写路线是待负责人裁决的行为变化。

**本分支源码事实：** DataOperationGate的计数在 `withWrite` finally递减；服务普遍使用 `database.withTransaction { gate.withWrite { block() } }`，因此 activeWrites变0时外层 COMMIT可能尚未完成。仅等待该计数不能证明库已静止。还存在名义读路径通过 `withTransaction { ensureControl() }` 初始化写，不能只列显式write helper。

**设计推断：** 要靠停写后直接多条逻辑SELECT保证一致，freeze必须覆盖整个事务完成；可以研究关闭新写后再经过真实writer事务/连接屏障确认排空，并覆盖全部旁路。只在内存freeze不会跨进程崩溃持久：进程死后锁消失、SQLite回滚未提交事务；半成品不能交付，也不得留下永久停写标记。

直接逻辑读取会把停写持续到最后一次读取（可能随行数/压缩/IO延长），不是“短暂停一下”。短时物理采集后立即恢复写可缩短freeze，但仍需P(t)的真实空间和引擎一致性。若外部writer不能纳入协调，则两条路线仍需要SQLite提供真实快照；不能以UI禁用按钮代替数据库边界。所有冻写/暂停时间与队列/Busy表现都需明确产品决定，不应作为无感修复混入。

## 7. 本轮边界与下一步输入

本研究完成固定源码/正式调用点清点、官方语义复核和缓存AAR静态条目统计；未做新的设备性能或数据操作。没有选择C2、统一driver或SAVEPOINT为已批准主线。后续原型应让相同完整fixture比较：额外盘量/分阶段文件峰值、PSS、真实业务write提交、WAL增长、最坏单次原生调用与取消、源码迁移接缝和单ABI体积；分别报告内容一致与资源/维护成本，保留现有限额和旧失败事实。

# G0 导出架构复审：固定版本源码核对

2026-10-26。独立源码调研，不是生产选型批准或设备测试报告。基线为 G1 `af62eef`；没有改写 `BackupSnapshot.kt`、Schema、导入/恢复/SAF。实测结果与架构裁决由本轮主报告拥有，本文件只记录一手来源能证明的机制和不能省略的边界。

## 1. 可复核来源

实际下载、解包并逐项核对 Google Maven 的发布源码；优先于会随版本变化的当前 API 页面。下载位于忽略目录 `record2/build/g0-export-review/research/`。

| 发布源码 | SHA-256 |
|---|---|
| [room-runtime-android 2.8.4](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime-android/2.8.4/room-runtime-android-2.8.4-sources.jar) | `86f67184adaf59862c237296ebe092bc45923e37d8d74dccd5a031bb7c5a09ae` |
| [sqlite-framework-android 2.6.2](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-framework-android/2.6.2/sqlite-framework-android-2.6.2-sources.jar) | `f7df5bd77b3b327986e7c12033d169ecaf7ebdf5a0eeec267496d3672fa2b524` |
| [sqlite-bundled-android 2.6.2](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled-android/2.6.2/sqlite-bundled-android-2.6.2-sources.jar) | `b1c76810bba2a88e765177bf121754423d265ed4125a934550e986cd82b1193c` |
| [room-sqlite-wrapper 2.8.4](https://dl.google.com/dl/android/maven2/androidx/room/room-sqlite-wrapper/2.8.4/room-sqlite-wrapper-2.8.4-sources.jar) | `cb995b9d1fb6f28270f32dc8517518b840c947e26b20afcd1adaaaa027cd82a5` |

原型保持既有 Room 2.8.4 / SQLite 2.6.2，不因调研升级库。[SQLite 发布页](https://developer.android.com/jetpack/androidx/releases/sqlite) 截至本轮列出的较新稳定版本是 2.7.1；这是版本背景，不是本轮已验证版本。Maven 的 Kotlin sources 不包含所打包 SQLite amalgamation 的完整版本证据；具体引擎必须记录 `sqlite_version()`、`sqlite_source_id()`、`PRAGMA compile_options`，不能从 Android API 或 artifact 名推断。[Android 官方版本说明](https://developer.android.com/reference/android/database/sqlite/package-summary) 也列出 API34 的不同 SQLite 更新线及 OEM 差异。

## 2. 路线 A：当前 Room compatibility mode

以下源码路径均相对上表相应 sources JAR。

1. Room `androidMain/androidx/room/RoomConnectionManager.android.kt`：未配置 `setDriver` 时，建立 `SupportSQLiteDriver` 和 `PassthroughConnectionPool`。
2. Room `commonMain/androidx/room/coroutines/PassthroughConnectionPool.kt`：`useConnection(isReadOnly, block)` 不使用 `isReadOnly` 参数，也不新建隔离的物理 reader；它把底层 driver 交给 coroutine wrapper。
3. SQLite framework `androidMain/androidx/sqlite/driver/SupportSQLiteDriver.android.kt`：`open()` 返回基于 `openHelper.writableDatabase` 的 `SupportSQLiteConnection`，不是 `OPEN_READONLY` 文件句柄。
4. `Transactor.deferredTransaction` 调用 DEFERRED 类型事务；pass-through 发出 `BEGIN DEFERRED TRANSACTION`。framework 的 `SupportSQLiteStatement.android.kt` 把这一语句映射为 `SupportSQLiteDatabase.beginTransactionReadOnly()`，并非交给 Android `execSQL("BEGIN...")` 的旧路径。
5. framework `db/framework/FrameworkSQLiteDatabase.android.kt` 的该方法在 2.6.2 中反射 `SQLiteDatabase.getThreadSession` / `SQLiteSession.beginTransaction`，传 mode=0、connectionFlags=0。反射不可用时退回普通 `beginTransaction()`；不能由公开 API 名字推断所有 API34/OEM 均具备只读事务保证。
6. `SupportSQLiteStatement.RowSQLiteStatement` 的 `step()` 使用 `Cursor.moveToNext()`，`getText()` 使用 `Cursor.getString()`，因此仍依赖传统 CursorWindow。

**判定含义：** A 值得实测固定快照和并发写，但即使通过，也不能证明文件句柄只读、写入一定被拒绝、没有反射 fallback 或大行已绕开 CursorWindow。最小实验应单列 UPDATE 是否被拒绝、大字段读取、事务中外部写入、关闭 statement 后再读另外两张表。UPDATE 只有明确 SQLITE_READONLY（主错误码8）才能证明写保护，SQLITE_BUSY（5）只是争用/升级失败，不能算只读拒绝。

**同一 Room 实例的额外串行边界：** `compatTransactionCoroutineExecute` 不额外套一个 Room 写事务，但它通过 `withTransactionContext` / `startTransactionCoroutine` 将完整 pass-through DEFERRED/IMMEDIATE 事务送到同一 `transactionExecutor`。默认 builder 的 `RoomDatabase.android.kt` 初始化使用 `TransactionExecutor`；`TransactionExecutor.android.kt` 在前一个 command 完整返回后才调度下一个。因此 reader 的长事务会让同实例 writer 事务排队，即便 SQLite WAL 本身允许另一个物理连接写入。正确表述是“Room 事务执行器串行造成业务 writer 不能在导出期间提交”，不能仅由 writer 超时断言“SQLite 写锁阻塞”。若配置 query coroutine context，compatibility mode 也有有限并行调度策略；需单独实测，不从线程池大小推出可并发。

区分层次的对照应使用另一个 Room 实例或直接 framework 连接访问同一唯一合成库，以单独验证 SQLite WAL 隔离；该对照成功不能替代同一实例正常业务服务调用路径的并发验收。上述定位来自固定 Room 2.8.4 sources 的 `RoomDatabase.android.kt`（初始化、事务上下文、compat wrapper）与 `TransactionExecutor.android.kt`，而非对设备线程等待原因的猜测。

不建议为了试验 A 随手对 Room 共用连接执行 `query_only=ON`：当前 pass-through 不交付独占物理 reader，该连接状态可能泄漏到后续 writer；必须先设计连接所有权和复位协议。该结论是由池和 driver 实现得出的风险判断，不是已观察到业务故障。

## 3. 路线 B：独立 bundled reader

2.6.2 bundled `jvmAndroidMain/.../BundledSQLiteDriver.jvmAndroid.kt` 提供：

```kotlin
BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY)
```

默认 `open(path)` 则为 READWRITE|CREATE。driver 自身没有连接池，编译线程模式为 multi-thread；不得同时在不同线程使用同一连接，或应使用 FULLMUTEX/外部串行化。`BundledSQLiteStatement.jvmAndroid.kt` 的 `step()` / `getText()` 直接进入 JNI，不经 Android CursorWindow。[驱动官方说明](https://developer.android.com/reference/androidx/sqlite/driver/bundled/BundledSQLiteDriver) 与固定源码一致。

但“现有 framework Room writer + 同进程独立 bundled reader 同时打开同一文件”存在结构性问题：[SQLite 官方 §2.2～2.3](https://sqlite.org/howtocorrupt.html#multiple_copies_of_sqlite_linked_into_the_same_application) 指出，多份引擎有各自的进程内锁协调状态，某一份关闭文件描述符可能释放另一份持有的 POSIX 锁。这是生产路线否决依据；压力测试碰巧没出错不能覆盖该机制。只读 reader 也会打开/关闭描述符，不能靠只读 flags 消除问题。

因此 B 可以用于一次性合成库的能力对照，不能仅凭快照/性能 PASS 升格为生产推荐。要消除这项风险，应统一同一文件的全部访问引擎，或明确进程隔离并重新设计生命周期；二者都超出“只替换导出 reader”的局部修复。

## 4. B-unified：Room 全部访问使用同一 bundled 引擎

固定版本公开 API 可表达以下原型，且不会修改实体 Schema：

```kotlin
Room.databaseBuilder(context, RecordDatabase::class.java, uniqueName)
    .setDriver(BundledSQLiteDriver())
    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
    .build()

db.useReaderConnection { reader ->
    reader.deferredTransaction {
        usePrepared(sql) { statement ->
            while (statement.step()) {
                // Consume a row before the next step.
            }
        }
    }
}
```

源码依据：Room 的 `ConnectionPoolImpl.kt` 对 readers 设置 `PRAGMA query_only=1`，writers 使用独立连接；事务在该池管理的同一连接上执行 BEGIN DEFERRED，回收时有事务清理。writers 可使用 `useWriterConnection` 和 `immediateTransaction`，不需要走 framework SQLite。

**需要 G0 裁决的区别：** Room 池 reader 的底层 driver 默认仍以读写 flags 打开，仅用 query_only 限制 SQL 写；它不等同于 G0 原文的独立 OPEN_READONLY 文件句柄。可以另设计同一 bundled 引擎的真正 OPEN_READONLY 导出连接，但这需要单独所有权/资源和 WAL 生命周期验证，不能从 Room 原型自动推导。

**兼容桥不是无成本替换：** 配置 driver 后 `RoomDatabase.openHelper` 抛 `IllegalStateException`。`androidx.room:room-sqlite-wrapper:2.8.4` 的 `androidx.room.support.getSupportWrapper()` 返回 `SupportSQLiteDatabase`，可桥接既有短查询/statement/事务调用，但不是 `openHelper` 对象，也不会使旧调用点自动变兼容。

wrapper 源码 `RoomSupportSQLite.kt::toCursor()` 把全部 statement 行复制进 `MatrixCursor`；`RoomSupportSQLiteDatabase.query()` 使用该方法。故桥接 **大查询会全量驻留内存**，不能用桥接 cursor 实现有界导出。wrapper 的 enable/disable WAL 明确不支持，journal mode 必须在 builder 配置；`CancellationSignal` 也仅在 query 开始前检查。采用此路线需要审计全部旧 openHelper/query/事务及底层库调用，再做 storage/AI/恢复集成回归，而非只加一项依赖。

## 5. step 接口不自动解决资源预算与取消

逐行 native step 解决的是 Android 窗口接口限制。SQL 如对完整正文或 JSON 执行未索引 ORDER BY，仍可能在首行之前建立大型排序工作区。应将键排序/索引扫描与单行载荷读取分开测量，记录查询计划、首行前峰值及全程峰值。

[AndroidX 的 bundled 构建源码样本](https://android.googlesource.com/platform/frameworks/support/+/b2f35bf616823867285389f9920e112345df94e6/sqlite/sqlite-bundled/build.gradle) 包括 TEMP_STORE=3、OMIT_PROGRESS_CALLBACK、THREADSAFE=2。它不是本次 2.6.2 二进制的替代证明，必须由原型 compile_options 对照；如 TEMP_STORE=3，则 `temp_store=FILE` 不能被当作磁盘 spill 保证。[SQLite temp_store 规则](https://sqlite.org/pragma.html#pragma_temp_store) 规定编译选项可能覆盖 PRAGMA。

固定 2.6.2 bundled Kotlin API 未提供 interrupt/progress handler；同步 `step()` 期间协程 cancellation 不会自动变成 SQLite 中断。每行/每段检查取消能保证后续不交付，但不能证明首次长排序可以即时中止。若预算要求严格限制单个 native 调用，应设计有界查询、真实中断接口或进程所有权，再测最坏调用时间。不能从“Job.cancel 已调用”写成“SQLite 已停止”。

## 6. C/D 备选的语义与成本

### C1 Online Backup API

[官方接口规范](https://sqlite.org/c3ref/backup_finish.html) 和[官方使用说明 §3.1](https://sqlite.org/backup.html#file_and_database_connection_locking) 区分“最终一致的快照”与“固定初始时点”：普通分步 backup 会因其他连接写入而重启，频繁写入可使其一直无法完成。不能仅引用概述里的 snapshot 一词便认定早期时点被固定。

需要固定时点时，应在 source 上维持明确读事务，再验证 WAL 下的并发写/增长边界。每次 step 控制页数，在 step 边界检查取消/预算并调用 finish；finish 在未完成时回滚目标，且 `finish()==SQLITE_OK` 不等于 backup 曾返回 SQLITE_DONE。目标持有写事务到完成，来源每步读锁与 WAL 状态都需测量。

当前 Android/framework 和 bundled 2.6.2 的 Kotlin API 未直接暴露 backup handle，不能假定无需 JNI/绑定。使用另一份 native SQLite 打开活跃 Room 文件仍会回到 B 的双引擎问题。G0 需允许由引擎创建的一致私有物理快照，并定义临时全库明文范围、磁盘公式、取消/强停清理、快照身份及只交付逻辑 ZIP。

### C2 VACUUM INTO

[官方 VACUUM 规范](https://sqlite.org/lang_vacuum.html#vacuum_with_an_into_clause) 明确：来源不变，输出是事务一致的快照；中途强停可留下不完整目标，不能报告成功；相比 backup API，它消耗更多 CPU、不能按页面增量执行。它不能在该连接已有事务时执行，故不能简单放进原有读事务来复用既定时点。它还可能改变无 INTEGER PRIMARY KEY 表的 rowid，输出逻辑事实比较不能依赖物理 rowid。

同样需要 G0 的物理暂存/余量/清理裁决。可以用系统 SQLite SQL 执行而不引入第二份引擎，但在当前 query_only/OPEN_READONLY/Android路由组合下是否可用、是否可由 CancellationSignal 中断及 WAL 行为，均应由专用原型证明，不先当作已实现。

**条件原型的源码依据：** [Android14 SQLiteDirectCursorDriver](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/database/sqlite/SQLiteDirectCursorDriver.java) 构造 SQLiteQuery 时不限制 SQL 必须 SELECT；[SQLiteQuery](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/database/sqlite/SQLiteQuery.java) 将 CancellationSignal 带到窗口执行；[SQLiteConnection](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/database/sqlite/SQLiteConnection.java) 为执行挂载 signal，[native 实现](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/jni/android_database_SQLiteConnection.cpp) 启用 SQLite progress handler 并由异线程取消标志驱动中断。`rawQuery("VACUUM main INTO ?", arrayOf(privateTarget), signal)` 因而是可验证的公开接口组合；需要读取 cursor count 才真正执行，无结果的成功形态为0行，不是 moveToFirst 返回 true。必须再独立验证目标完整性。

[SQLite 3.39.4 的 vacuum.c](https://raw.githubusercontent.com/sqlite/sqlite/version-3.39.4/src/vacuum.c) 对 INTO 的来源 Btree 开启读事务，RW|CREATE 只临时用于附加的新输出文件；这提供来源 OPEN_READONLY 能运行的实现依据。`query_only` 会作用于附加的输出库，不能未经测试照搬到这个连接。该路线需明确许可来源只读、输出新库写入，不应描述为“连接内只执行 SELECT”。中断探针应在确认输出创建/写入后取消，区分执行中取消与调用开始前取消；失败后的物理目标均不交付。

### D SQLite snapshot API

[官方 `sqlite3_snapshot_open`](https://sqlite.org/c3ref/snapshot_open.html) 需要 SQLITE_ENABLE_SNAPSHOT 编译选项，历史 WAL 被 checkpoint 覆盖后会失败。当前 bundled Kotlin 接口不直接提供这套 API；需 JNI/编译能力、句柄生命周期与 checkpoint 协调。它不是只加一条 SQL 的替代方案。

## 7. 平台 API35 的边界与决策输入

[Android SQLiteDatabase.beginTransactionReadOnly](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase#beginTransactionReadOnly()) 自 API35 提供公开 DEFERRED 只读事务，在 WAL 下可与 writer 并行。它不能使 API34 自动获得同样的公开 API 保证；提高 minSdk 或分平台实现都属于产品/维护决策，不能在本轮悄悄进行。

源码调研优先级结论：先实际记录 A 的隔离、写保护和大行三个不同结果；B-mixed 的双引擎机制足以否决该形态；B-unified 可消除同文件混合引擎问题，但需全库访问接缝兼容裁决和集成成本评估。只有这些直接路线确实不满足条件时，再用 C 的空间/取消/快照时点数据比较。单次设备 PASS 不能替代这些结构性决策，也不能证明最大包或生产安全已通过。

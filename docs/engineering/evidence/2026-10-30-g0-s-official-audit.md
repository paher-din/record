# S最终验证：官方语义、平台限制与U发行物审查

2026-10-30。D=官方文档，S=固定源码，E=本轮实测，I=推断，H=未验证。仅研究/隔离实验，不改变生产依赖、Room配置、Schema或Accepted决定。二进制/来源下载SHA、完整public API、平台拒绝原文和远端状态见[原始回执](2026-10-30-g0-s-official-receipts.json)，可复查入口为[audit-s-final-official.py](../../../record2/tools/audit-s-final-official.py)。

## 1. VACUUM INTO与API34来源保护

**D：** [SQLite官方VACUUM INTO](https://sqlite.org/lang_vacuum.html#vacuum_with_an_into_clause)提供一致新目标；中断中的目标可能损坏。现有目标须不存在或为空；不能把它当普通活跃文件copy，也不将源rowid迁移成业务身份。

**S/I：** [SQLite3.39.2 vacuum.c](https://raw.githubusercontent.com/sqlite/sqlite/version-3.39.2/src/vacuum.c)中，INTO只为新attached目标放开创建/写权限；来源Btree仍按读事务打开。先建空表/索引再逐表拷贝，并不把整批JSON作为排序输入；目标journal通常只需新文件回滚头。它给出Q较小的实现解释，**不单靠这份通用源码宣称所有OEM、故障和瞬时Q都有已证明硬上界**。

不能套用当前网页的fsync说明到所有旧系统引擎。3.39.2路径把目标pager设为同步OFF，不能承诺该scratch断电持久；本方案恰好不需要重启续用。只消费当前进程成功完成且检查通过的目标，任何重启孤儿都弃置。最终用户ZIP仍必须按原SAF写入/关闭/回读规则处理。

**S：** Android14 r1 [SQLiteConnection JNI](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/jni/android_database_SQLiteConnection.cpp)把OS只读flag传给sqlite3_open_v2；CancellationSignal通过progress handler进入引擎取消。scalar string直接取得当前列文本，不用CursorWindow承载整个正文；源码支持大行路径，但内存并非零成本。本轮取消要等native/cursor/连接退出后清理，不用强行close来源fd冒充取消。

**S：** [DefaultDatabaseErrorHandler](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/database/DefaultDatabaseErrorHandler.java)可能关闭并删除损坏库；[SQLiteDatabase](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/database/sqlite/SQLiteDatabase.java)的OS只读不豁免Java错误处理。因此原型source和target均明确提供抛错/不删库handler，不依赖默认处理。

**E：** 本轮系统引擎报告3.39.2，source-id `2022-07-21 15:24:47 698edb77537b67c41adc68f9b892db56bcf9a55e00371a61420f3ddd668e6603`；sourceReadOnly=true、journal=wal。30k/50k目标采集中均观察到512B的`-journal`；这只是采样结果。其他阶段Q未观察到不等于任意短峰为0。

## 2. S不新增源写连接，不等于旧系统引擎免审

**S：** [Android14 SQLiteConnection.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/database/sqlite/SQLiteConnection.java)的OS只读连接跳过journal调整、WAL truncate和autocheckpoint配置；新导出路径也没有主动checkpoint调用。静态目标为独立文件，后续逻辑读取不再钉住源WAL。

但同一源码包含`maybeTruncateWalFile`；现有Room的普通pool连接不能只因承担读查询就被当成OS只读。[SQLiteConnectionPool](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/database/sqlite/SQLiteConnectionPool.java)的primary写路由与生命周期仍须结合实际配置/OEM核对。**“Room只有一个业务writer”不是对所有checkpointer来源的完整证明。** 本轮生产目录未发现显式wal_checkpoint或OPEN_READWRITE旁路，并不等于平台底层永远没有相关行为。

**I/H：** S保留既有引擎、其新增来源连接为只读，降低了迁移范围；这不是把3.39.2宣称为已修复版本，也不能把一次并发PASS当作稀有竞争不存在的证明。既有系统SQLite补丁/平台风险应单列为生产平台门禁，不能对S免审、只对U要求证据。未在本轮改Room池、系统镜像或设备安全策略。

## 3. U最高稳定版的真实二进制身份

**D/E：** Google Maven元数据`lastUpdated=20261021170313`，最高稳定版为2.7.1；2.8.0-alpha03属于预发布，不作正式发行物准入。2.7.1 AAR本轮重新下载为2,551,019B，SHA256 `b5e01f31b0cc42b01a9f12836bfb6ee745b389e5be53a0813acfe12b445788a1`，与前轮相同。

| ABI | ELF字节 | SHA256 |
|---|---:|---|
| arm64-v8a | 1,281,456 | `9d3ad85cca0b7b02e0fe31beec3f17c86cccdebf62e1d1c47ac46743f4967a99` |
| armeabi-v7a | 1,254,892 | `d1bcd83899465f738a7e155007f318be8331bbae97b942cce5d29c24b633501e` |
| x86 | 1,140,764 | `b30393b5fd4414f5ccb52d20d70001ec5da6a47a7d455f7ccd36d502e70d45b9` |
| x86_64 | 1,189,688 | `0d1892faf4a90c1c99c375df704ac5dc498af492e71661fdda12c6a497a6691b` |

四ELF均检出NUL结尾3.50.1版本字串与相同完整source-id：

```text
2025-06-06 14:52:32 b77dc5e0f596d2140d9ac682b2893ff65d3a4140aa86067a3efebe29dc914c95
```

这是静态二进制身份，**不是本轮运行2.7.1或已证明补丁不存在**。未取得保留旧身份但已回移修复的官方声明/源码证据，不能据发布时间或最新版认定安全。结果为**U生产发行物准入FAIL；继续阻断**，不引入第三方或私有分叉作为默认替代。

**D：** [官方WAL说明](https://sqlite.org/wal.html#the_wal_reset_bug)列出受影响条件、修复与回移版本；[3.51.3发布记录](https://sqlite.org/releaselog/3_51_3.html)给出的含修复release源码身份为`737ae4a34738ffa0c3ff7f9bb18df914dd1cad163f28fd6b6e114a344fe6d618`（2026-03-13）。它是包含修复的release树身份，不冒充单个补丁提交ID。[该源码树](https://github.com/sqlite/sqlite/tree/version-3.51.3)可用于核对；本轮未声称已将其补丁映射到AndroidX ELF。

[AndroidX官方2.7.1发布记录](https://developer.android.com/jetpack/androidx/releases/sqlite#2.7.1)没有提供WAL-reset修复包含证明；其release commit范围链接本轮返回403，GitHub镜像对应SHA路径404，两者不能当作已读取的源码证据。没有已核对补丁的正式生产发行渠道被本轮批准。

## 4. Room兼容性、成本和回归

**S/I：** 对官方2.7.1 classes.jar运行javap，Android同步Driver.open、Connection.prepare/close、Statement.step及绑定/读取接口仍存在，BundledSQLiteDriver保留open(path,flags)。这支持“迁移可能兼容”，不证明Record2.8.4已集成通过。未把静态兼容性当作运行PASS。

现有Room2.8.4源码与调用点清单见[前轮官方研究§1](2026-10-26-lightweight-official-research.md#1-room-统一-driver不是零改动也不等于重写整库)：9个storage文件34处legacy withTransaction、1处Cursor PRAGMA、4个平台活库读取接缝。迁移须独立覆盖FK延迟、嵌套DAO、异常/取消、A/B/CAS、恢复receipt与未知提交、启动及Agent回归，不能只替factory。

S不新增生产SQLite或ZIP依赖。U单ABI约1.2MiB、四ABI APK增量约4.8MiB只是[前轮2.6.2体积probe](2026-10-26-lightweight-cost-audit.md#21-成对依赖size-probe)，不代表尚未选出的修补版本。当前实际2.7.1 ELF见上表，APK/AAB/R8/split完整增量本轮NOT RUN。

## 5. 真正低空间实验的环境限制

已尝试在唯一私有合成目录挂载1MiB tmpfs，保留SELinux与全局空间，su仍返回Permission denied；未成功挂载，创建的空目录已rmdir。另以正确shell引用在run-as域测试`/dev/full`，同样被权限拒绝。原始准确argv、退出码、stderr在回执JSON。

因此**本轮应用域真实ENOSPC为NOT RUN**。不会填满整个AVD，不关闭SELinux，不把根域CLI、字节配额异常或availableBytes注入写成应用域真实磁盘满。后者可以证明资源拒绝/中止清理，但不关闭这项门禁。

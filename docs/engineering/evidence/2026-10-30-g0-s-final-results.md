# S最终架构实验索引与结果

2026-10-30。**研究原型；生产准入未通过**。结论、预算和具体Proposed条款归[最终报告](../../architecture/record2-g0-s-final-validation.md)，授权归[Plan §35.6](../../specs/record2/v1/plan.md#356-s优先的最终架构验证2026-10-30)。

## 1. 原始证据

- [规模与早期基线回执：9次](2026-10-30-g0-s-scale-receipts.json)：APK/设备身份、准确instrument参数、prepare/实验原文、完整summary。
- [故障回执：15次](2026-10-30-g0-s-faults-receipts.json)：包括首次copy强停未命中的FAIL、150k补测的kill命令/marker及环境恢复后verify原文/metrics。
- [补充回执：3次](2026-10-30-g0-s-followup-receipts.json)：预算/Clock、freelist与unlinked-FD正向对照。
- [官方与环境限制](2026-10-30-g0-s-official-audit.md)，[下载/ABI/平台拒绝原文](2026-10-30-g0-s-official-receipts.json)。

功能/故障断言合计26 PASS、1 FAIL（含一次重启恢复后的核对PASS），**不表示27项产品验收，也不覆盖资源目标的FAIL/NOT RUN**。原始15次fault记录中，补测没有伪造原先缺失的summary，而是保留独立resumed-metrics与verify-resumed-booted原文。

环境：`record-api34` / `emulator-5594` / API34 x86_64，fingerprint与旧实验相同。app SHA `b584f7c1c1b3f33cbcd75c1f25b2c080feb1fd5542d22d243dbff573c4d12677`，不含bundled；规模/主故障矩阵test SHA `d0ca77286e618b1f9fd200b39e9a975a6e20dffef00d02c8fdb616562c3240ed`。各迭代身份以逐项回执为准。

## 2. 五档与补充用例

所有五档内容/顺序/引用、独立全字段模型、原预检与恢复、恢复后精确转换均PASS；资源数字及分阶段峰值见主报告§5和summary，不以此代替耗时目标。

| 场景 | runId | 功能结果 |
|---|---|---|
| 1k混合+原ContentResolver写/关闭/回读 | `a42522858f0c44158a32648491528a5c` | PASS |
| 30k普通 | `bfedbecf4d4d4b71b4a91608fd1d9693` | PASS；15s目标FAIL |
| 50k持续真实多表写 | `29955c6f57844c2ea95f266d99364741` | PASS；采集中3次提交、全程2231次、最大44ms |
| 150k重度AI | `c398563b06204396bed68add8d14ae15` | PASS；120s目标FAIL |
| 500k总行+17×7.4M memo | `e136ad7f236b4d03943e59f028432799` | PASS；274.743s、PSS140.164MiB |
| 7M重复正文Note/100:1回退 | `08541718610c44cdaa47a32f28790e27` | PASS；两遍、ZIP7,105,715B、PSS129.477MiB |
| UUID unsigned/数值signed排序独立比较 | 规模回执中sort_check=true | PASS；各段8193键，对照UUID文本及数值规则 |
| 最早1k基线 | `47c481384494437a8bfc7a4af4657ad1` | PASS内容；保留最早采样版本 |
| 30k重复编码基线 | `48f47025949b4432b097556aabcdc901` | PASS内容；57.137s，未覆盖/删除 |

当前文本使用固定seed、模型量级和有效关系；普通/重度每组合31/34行，AI payload约3.8k/7.8k字符。文本主要为四分之一随机字母与重复字符，属于**合成压缩熵**，不是实际用户AI语料。高熵普通历史压缩率与真正用户统计NOT RUN，不能由本次ZIP大小保证其他内容也如此小。

## 3. 故障矩阵

| 用例 | runId | 结果与实际触发 |
|---|---|---|
| 采集中取消 | `cd031bc7463a49c8ba49915b82bcc610` | PASS，目标写入后signal→SQLITE_INTERRUPT |
| 提取中取消 | `0ee8ffa68b7144adbbd8401c8bc6f1cb` | PASS，cancel-extract-row |
| ZIP写入取消 | `348e0d499e634f9f991817740dbd5538` | PASS，cancel-zip |
| 最终读取取消 | `a7d7f89e7817420f9ad3cb69783618ee` | PASS，cancel-verify-read |
| 50k采集期强停 | `735727d99c524f988ee04479d242d444` | **FAIL：kill missed active copy**，不得算copy kill PASS |
| 150k采集期强停补测 | `b70b5e1f591343ec95c9ca9dc1b0dba2` | PASS核对；killedPID8500、新verifyPID1597，copy-returned尚不存在；环境中断后恢复核对 |
| 提取期强停 | `21a276c89a1a424f8a0f0aea2be9b256` | PASS，新PID/14表/无成功/孤儿删除/可写 |
| ZIP期强停 | `a43155c38de846548f65483d2a96537c` | PASS，同上，半包拒绝 |
| 最终核验期强停 | `efbd14d69a7d4793b2ce640cfd3c0b00` | PASS，可能已有合法私有ZIP，仍无交付成功且丢弃 |
| 静态目标cap | `7d87e4d3edcd4629b8fe63818dd181d7` | PASS，snapshot-cap→native中断；软cap可超调 |
| WAL cap | `be237bd7802f46e7bcf50b1599061e2a` | PASS，wal-cap；已提交业务写按完整14表模型核对，不声称源字节零变化 |
| 低余量注入 | `5ca33b84bf9e4867b82bfad1a2a3fcdc` | PASS，free-floor→native中断；不是实际耗尽磁盘 |
| ZIP输出配额 | `b0c63b620a2c48ec986267084adc8da8` | PASS，实际IOException；不是内核ENOSPC |
| 静态目标损坏 | `7d9050c2a95240a3800c016038832294` | PASS，SQLITE_NOTADB拒绝，source未删除/未变 |
| 静态目标权限失败 | `a43e24480b784114912237327b7f94ca` | PASS，SQLiteCantOpenDatabaseException |

取消及非WAL故障均核对14表、源DB/WAL字节相同、无成功标记、清理且后续可写。强停在新PID先核对源事实，再调用隔离root的原BackupStaging.sweep；没有读取scratch续用。原型源库是唯一合成文件，不修改默认业务库。copy kill首次失败因为采集短于host反应窗口；改用已验证的更大混合夹具提高命中机会，未延长超时或删断言。

## 4. 预算/Clock/观测器补测

- `03057e334b8d46ee82f698cf9aecea7c`：新版真实采集Clock、原SAF完整往返PASS；试图用默认数据库制造freelist，但AUTO_VACUUM自动回收，实际freelist=0，**该次freelist假设未成立**，不能记成空页预算验证PASS。
- `1789ed3dfb644d99813c3bb73aae924f`：只在合成fixture切到INCREMENTAL并插入/删除64MiB zeroblob，最终Schema/逻辑内容不变。源物理68,222,976B，其中freelist67,174,400B；存活页1,048,576B，输出S962,560B，复制准入71,303,168B（68MiB）。内容/原恢复/源DB-WAL零变化及SAF往返PASS。此设置不是生产迁移或当前默认布局。
- `10f1042f81e14767958d01055bf3d8e3`：打开并写满2MiB临时文件后unlink、保持fd；观测器同时记录到`etilqs_probe (deleted)`和2,097,152B，PASS。证明当前应用域能观察这类unlinked文件，不证明所有OEM/所有Q的硬上界。

五档性能矩阵采用此前的page_count估计和固定合成Clock。后续版本仅补freelist估计、真实Clock、早期总行数拒绝和观测器正向对照，未重跑五档；不把其效果包装成新版生产性能PASS。源码差异/身份回执另附，现有正式限制不变。

## 5. 命令与未覆盖项

```powershell
.\record2\gradlew.bat -p record2 :app:assembleDebug :app:assembleDebugAndroidTest
python record2/tools/run-s-final-matrix.py --adb C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe --group scale
python record2/tools/run-s-final-matrix.py --adb C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe --group faults
python record2/tools/run-s-final-prototype.py --adb C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe --rows 150000 --profile heavy --fault kill --at copy
python record2/tools/run-s-final-prototype.py --adb C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe --rows 1000 --profile small --fragmented --saf --install
python record2/tools/run-s-final-prototype.py --adb C:/Users/p3148/AppData/Local/Android/Sdk/platform-tools/adb.exe --rows 1000 --profile small --monitor-check
```

准确参数数组和JUnit原文以回执JSON为准。runner每个prepare/实验的1200s为最初设定宿主止损，不是产品时间目标，本轮没有抬高。重启恢复核对保留最初离线尝试和boot后成功原文。

**NOT RUN：** 真正应用域文件系统ENOSPC（两条安全隔离路径被SELinux拒绝）、真实低空间最低成功边界、所有OEM Q与超调硬上界、越界巨大字段分配上界、真实高熵用户历史、正式生产导出port、生产启动清扫屏障/failed记录、实际系统补丁闭合、真机/DocumentsUI/G2。本轮没有以历史G1 PASS抵扣这些项目。

## 6. 交付核对

最终androidTest构建PASS；Python runner/矩阵/官方审计脚本AST通过。三份新Markdown的UTF-8/围栏、本地链接/锚点和引用runId核对通过；27份持久回执逐项与原始JSON一致。[源码身份与差异](2026-10-30-g0-s-source-provenance.json)的最终SHA均与当前文件一致。`git diff --check` PASS。

`git diff HEAD --quiet -- record1 record2/app/src/main record2/app/schemas record2/gradle/libs.versions.toml`返回0；生产/Schema/正式依赖版本冻结。没有重跑未受修改的整套G1/CI，也没有Commit/Push或远端状态变更。测试源和预算仍为Proposed，不能用编译成功证明生产数据安全。

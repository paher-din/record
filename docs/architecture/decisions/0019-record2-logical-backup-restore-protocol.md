# ADR 0019：R2 逻辑备份 ZIP 与单事务原子恢复

**Status: Accepted**；2026-10-24（设计），2026-10-25 负责人最终批准；仅适用于 Record 2.0。批准来源、设计和实际状态由 [V1 Plan §31.7](../../specs/record2/v1/plan.md#317-负责人最终批准与-adr-accepted2026-10-25) 与 [G0 设计审查包](../record2-g0-backup-restore-design.md) 拥有；本 ADR 接受长期协议选择，不授权 G1 实现、设备/SAF 测试、Schema 变更或发布。

**Rev2（2026-10-25，PR #28 审阅修订）：** 总体架构与决定 1–6 保留，细化导出只读快照、门禁事务前零写入、receipt 缺失可检测性及 C0/C1 时间字段对齐；不新增持久化标记、不改 Schema/源码。

**Rev2.1（2026-10-25，PR #28 第二轮审阅修订）：** 明确空数据段必须 STORED/0/0（禁止 DEFLATE 空块），并拒绝目标 `authEpoch` 会使恢复后到达 MAX 的恢复；其余架构/编号不变。

**Rev3 提案（2026-10-26，尚未接受）：** G1导出访问架构复审提出“系统引擎一致私有物理scratch→bundled只读逻辑提取”。本ADR原决定仍为Accepted；本次修订单独为Proposed，具体替换文本与边界见文末，不据此授权生产改造。

## 背景

**Rev4：S选型Accepted（2026-10-30）。** 负责人批准来源归[Plan §35.7](../../specs/record2/v1/plan.md#357-s架构批准与受控g1实施2026-10-30)。决定2的导出机制以系统OS只读来源VACUUM INTO→私有静态系统SQLite只读提取→标准流式ZIP替换；保留原逻辑格式/恢复/资格/receipt。允许一致物理scratch，不允许活跃文件copy或物理恢复；manifest采集开始Clock与采集时间窗语义生效。§6原固定128MiB导出准入改为分阶段实际工作预算，全部格式容量/WAL硬限额不变。同卷安全余量一次；移除全量JSONL，验证后释放S/K再SAF。旧Rev3/C2固定GiB预算正式否决，U暂缓。此为架构选型Accepted，ENOSPC/超调/竞态/生产一致性/性能/平台及G2门禁仍OPEN，不代表生产验收。具体条款归G0 §14。

Spec §6 与 B0 §9 要求手动完整逻辑 ZIP、隔离预检、整体替换确认、原子切换/回退和跨代际旧 Pending 隔离；B0 建议由 G0 评估“单一数据库内原子整体导入”，并禁止同时建设事务导入与物理切换两套框架。G0 需在 C1/E1 的真实 Room 状态机、Schema v1 的 14 表和 B0 的资格语义之上选择唯一协议，不能从旧 P5/Vault 或旧 format1/2 推导兼容。

## 决定

1. **格式。** 使用 `record2.logical`、`formatVersion=1`、`databaseSchemaVersion=1` 的手动逻辑 ZIP；包含 `manifest.json` 与 12 个 JSONL 数据段，覆盖业务事实、草稿（仅 EDITING）、Proposal/Operation 历史、Session/Message/Run/引用；排除 `ai_control`、凭据/Keystore 材料、授权资格、缓存、隐藏推理/Trace/日志和 SQLite 内部文件。空数据段（0 行）必须 `STORED`、压缩/解压 0/0、CRC 0，禁止 DEFLATE 空块；非空段才使用 STORED/DEFLATE。详见 G0 §3–§4。
2. **导出（Rev4 S）。** 系统SQLite独立OS只读来源VACUUM INTO一致采集，禁止自动删库；关闭来源后系统只读scalar提取同一私有静态目标的12段和data_state，标准流式ZIP，无完整JSONL。正常写不停、恢复互斥，不复制活跃DB/WAL/SHM、不用Room写事务。采用采集时间窗和分阶段动态预算，F同卷一次，全部格式容量硬限额保留；原严格校验后回收S/K再SAF写入/关闭/回读。失败不成功、强停scratch不续用。详见G0 §7.1/§14；架构选型Accepted不等于生产验收，合并门禁归Plan §35.7。
3. **恢复。** 门禁/prepare **事务前零写入**：不持久化 Run 取消、owner、AI、草稿、receipt 或 checkpoint；当前 Run 只在内存失效，迟到写在 SQL 前拒绝，预检失败时 O 的 14 表逐字段不变。只有全部事务前校验通过后才使用**一个 Room 事务**整体删除并导入全部逻辑内容；不使用数据库文件复制、WAL/SHM 替换、目录切换或外部完成标记。失败回滚 O，成功提交 S，不混库。详见 G0 §8.3–§8.4。
4. **资格转换。** 恢复生成新 `dataGeneration`/`processOwnerId`：RUNNING→INTERRUPTED（清 slot），EDITABLE/LOCKED Proposal→NEEDS_REVIEW（清 slot）；Pending Operation 保留原 `createdGeneration`/冻结输入但不可执行；终态 Operation/Proposal 与完整可见历史保留。恢复重置 `ai_control` 为 OFF/无 grant；旧行 `authEpoch` 必须为 `0..MAX-2` 才递增到 `≤MAX-1`，旧值为 `MAX-1`/`MAX`/负值必须在事务前拒绝，禁止恢复写入 E0 `nextEpoch()` 无法继续使用的 MAX；不创建/删除/沿用 Keystore 凭据或授权。详见 G0 §4.3。
5. **完成与启动。** 恢复成功时在同一事务写 `record_data_state.restoreReceipt`；启动只读校验**存在的** receipt 与当前代际，再进入 E1 所有权门禁。receipt 损坏/代际不符进入 RecoveryRequired，不自动修复/重放。Schema v1 没有独立期望标记：receipt 为 NULL 与从未恢复不可区分，不能承诺检测“提交后缺失”，也不新增持久化标记；NULL 按正常启动处理。详见 G0 §8.5。
6. **格式/时间边界。** 草稿只导出 EDITING，SAVED/DISCARDED 排除；候选绑定整 ZIP SHA-256/字节数/manifest contentDigest 与目标代际上下文，源字节或上下文变化必须重新预检；所有字节/条目/行/压缩比/磁盘余量限额按 G0 §6 冻结，越界拒绝不截断；时间字段导入集合必须是 C0/C1 等 owner 校验集合的子集（Proposal/Operation `createdAt>=0`、payload 时间四元组完整通过 codec），不能出现导入成功后服务重读 RecoveryRequired；ZIP 绝对时间范围是额外导入边界。ZIP 不加密，SHA-256 只证明完整性。

## 后果

- 恢复只需一个 SQLite 事务即可得到完整 O 或完整 S；不维护第二套文件切换/回退框架，也不需要外部完成标记。
- 门禁/prepare 失败不写 O；若当时有活动 Run，持久行可能仍是 RUNNING，但其内存执行已失效，需要 E2/下次启动所有权门禁单独处理。不得为“清理”该行在事务前写 CANCELLED/INTERRUPTED。零写入保证针对恢复门禁/prepare 自身；门禁前已开始的正常业务写可完成，测试以排空后基线比较。
- 导出只持读快照、不持写锁，依赖 WAL 与独立只读连接；非 WAL/平台不支持时导出拒绝，不降级为 Room 写事务。WAL 增长/卷余量受 G0 §6 上限保护。
- 代价是：导入事务按数据量持有写锁并需要额外磁盘/WAL 余量（G0 公式）；不提供合并/选择性恢复、旧格式兼容或加密；恢复后必须重新授权 AI，旧 Pending 仅可读。
- 私人数据在私有 staging 中短暂以明文 ZIP 存在；G1 必须在明确授权范围内清理，并避免外部存储/日志/系统备份。
- receipt 缺失不可靠检测是 Schema v1 的明确限制；不新增标记。格式限额是 V1 边界；未来提高限额/增加段需新 `formatVersion` 与负责人批准，不能静默放宽。
- 空段 STORED/0/0 是格式硬规则；标准 DEFLATE 空块（常为 2 字节）不被接受。目标 `authEpoch` 接近 MAX 时恢复拒绝，避免写出 E0 无法继续授权的状态；不承诺修复既有 E0 MAX 边界。

## 未采用/后置

- **物理文件切换：** 需要关闭/替换 DB/WAL/SHM、外部完成标记、崩溃恢复和平台相关文件语义；V1 单一 SQLite 逻辑库和受限数据量下没有证据表明它比事务导入更简单或更安全，且会形成第二套协议。
- **Room 写事务/普通连接拼快照：** 可能取得写锁或跨语句得到非一致视图；不作为导出快照方案，也不作为只读连接失败后的回退。
- **恢复前持久化 Run 取消/中断：** 会让事务前校验失败也改变 O，违反零写入；正常 E2 取消路径只在恢复之外使用。
- **新增 receipt 期望代际/外部完成标记：** 可检测“提交后缺失”，但需要 Schema/持久化或外部状态扩展；本轮未授权，且 receipt 缺失不影响已提交 S 的数据完整性。因此只承认不可检测限制。
- **直接复制 SQLite/WAL：** 不是逻辑备份，不能保证跨设备/跨版本可读，也违反 Spec §6。
- **CSV/逐表合并/选择性恢复：** 无法保持引用、顺序、隐藏关系、Operation 自包含和整体替换确认；V1 不做。
- **旧 P5/Vault/format1/2 兼容：** 不继承旧格式/阈值；需要时另立新任务与格式版本，不能借“保留源码”自动迁入。
- **ZIP 加密：** V1 采用明确未加密告知；密钥/授权不进入备份。加密能力若未来需要，另审格式/UX/密钥恢复。

## 参考

- [G0 设计审查包](../record2-g0-backup-restore-design.md)
- [B0 数据模型 §9](../record2-b0-data-model-design.md#9-完整备份代际与恢复接缝)
- [Spec §6](../../specs/record2/v1/spec.md)
- [ADR 0018](0018-record2-data-concurrency-and-history.md)
- [F0-C3 D-SAFE](../../design/record2-f0-c3-review.md#8-d-safe授权历史备份与恢复)

## Rev3 Proposed：导出机制替换候选

基于 [G0 导出架构复审](../record2-g0-export-architecture-review.md) 的固定源码核对与隔离API34原型，建议替换决定2的具体实现：由系统SQLite在独立OS只读来源上执行官方VACUUM INTO，创建私有noBackup一致全库scratch；复制期间正常Room写入可提交；完成并关闭来源后，bundled仅以只读连接逐行提取不同文件上的12段及data_state，继续生成原逻辑ZIP。

这需要明确允许**引擎生成的一致物理暂存**，不是直接复制活跃文件，也不是物理恢复/文件切换。禁止默认损坏处理器删源库、禁止同文件双引擎并发、禁止强停后复用scratch；取消须等待原生调用退出后再清理/释放。加入全部物理内容的隐私/磁盘预算、来源增长与WAL监控、目标工作配额、固定40B键记录外部归并方案及采集时间窗。exportedAt改为采集开始Clock：窗前提交必含、窗后必不含、窗内可含完整前/后态；元数据只读目标。来源页体积只是启动估计，不能声称WAL增长自动证明目标上界。详细替换条款归 [G0 §13](../record2-g0-backup-restore-design.md#13-rev3-导出访问架构修订提案2026-10-26proposed)。

原决定1、3～6（格式、单事务逻辑恢复、资格转换、receipt、时间/格式限额）不变。B-unified保留后备，A当前配置、同文件B-mixed、原递归SQL等否决理由由复审报告拥有。

本修订状态为Proposed。负责人批准后才成为ADR的新决定并另行授权G1实现；不把原型内容PASS提升为生产P1关闭或预算/真机/G2验收。

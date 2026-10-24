# ADR 0019：R2 逻辑备份 ZIP 与单事务原子恢复

**Status: Proposed**；2026-10-24；仅适用于 Record 2.0。批准来源、设计和实际状态由 [V1 Plan §31](../../specs/record2/v1/plan.md#31-g0正式逻辑-zip代际协议设计审核issue-18) 与 [G0 设计审查包](../record2-g0-backup-restore-design.md) 拥有；本 ADR 只记录长期协议选择，不授权 G1 实现、设备/SAF 测试、Schema 变更或发布。

## 背景

Spec §6 与 B0 §9 要求手动完整逻辑 ZIP、隔离预检、整体替换确认、原子切换/回退和跨代际旧 Pending 隔离；B0 建议由 G0 评估“单一数据库内原子整体导入”，并禁止同时建设事务导入与物理切换两套框架。G0 需在 C1/E1 的真实 Room 状态机、Schema v1 的 14 表和 B0 的资格语义之上选择唯一协议，不能从旧 P5/Vault 或旧 format1/2 推导兼容。

## 决定

1. **格式。** 使用 `record2.logical`、`formatVersion=1`、`databaseSchemaVersion=1` 的手动逻辑 ZIP；包含 `manifest.json` 与 12 个 JSONL 数据段，覆盖业务事实、草稿（仅 EDITING）、Proposal/Operation 历史、Session/Message/Run/引用；排除 `ai_control`、凭据/Keystore 材料、授权资格、缓存、隐藏推理/Trace/日志和 SQLite 内部文件。详见 G0 §3–§4。
2. **导出。** 在一个只读 Room 事务中取得全部表的时点快照，不要求停写；导出期间完成的 Run/提交不进入该 ZIP。ZIP 先在私有 staging 构建并验证，再写入 SAF，关闭后回读校验；任一步失败都不报成功。详见 G0 §7。
3. **恢复。** 使用**一个 Room 事务**整体删除并导入全部逻辑内容；不使用数据库文件复制、WAL/SHM 替换、目录切换或外部完成标记。失败回滚 O，成功提交 S，不混库。详见 G0 §8.4。
4. **资格转换。** 恢复生成新 `dataGeneration`/`processOwnerId`：RUNNING→INTERRUPTED（清 slot），EDITABLE/LOCKED Proposal→NEEDS_REVIEW（清 slot）；Pending Operation 保留原 `createdGeneration`/冻结输入但不可执行；终态 Operation/Proposal 与完整可见历史保留。恢复重置 `ai_control` 为 OFF/无 grant，递增 authEpoch；不创建/删除/沿用 Keystore 凭据或授权。详见 G0 §4.3。
5. **完成与启动。** 恢复成功时在同一事务写 `record_data_state.restoreReceipt`；启动只读校验 receipt 与当前代际，再进入 E1 所有权门禁。receipt 损坏/代际不符进入 RecoveryRequired，不自动修复/重放。详见 G0 §8.5。
6. **格式边界。** 草稿只导出 EDITING，SAVED/DISCARDED 排除；候选绑定整 ZIP SHA-256/字节数/manifest contentDigest 与目标代际上下文，源字节或上下文变化必须重新预检；所有字节/条目/行/压缩比/磁盘余量/时间范围限额按 G0 §6 冻结，越界拒绝不截断；ZIP 不加密，SHA-256 只证明完整性。

## 后果

- 恢复只需一个 SQLite 事务即可得到完整 O 或完整 S；不维护第二套文件切换/回退框架，也不需要外部完成标记。
- 代价是：导入事务按数据量持有写锁并需要额外磁盘/WAL 余量（G0 公式）；不提供合并/选择性恢复、旧格式兼容或加密；恢复后必须重新授权 AI，旧 Pending 仅可读。
- 私人数据在私有 staging 中短暂以明文 ZIP 存在；G1 必须在明确授权范围内清理，并避免外部存储/日志/系统备份。
- 格式限额是 V1 边界；未来提高限额/增加段需新 `formatVersion` 与负责人批准，不能静默放宽。

## 未采用/后置

- **物理文件切换：** 需要关闭/替换 DB/WAL/SHM、外部完成标记、崩溃恢复和平台相关文件语义；V1 单一 SQLite 逻辑库和受限数据量下没有证据表明它比事务导入更简单或更安全，且会形成第二套协议。
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

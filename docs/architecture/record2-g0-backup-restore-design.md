# Record 2.0 G0 逻辑 ZIP / 代际恢复设计审查包

2026-10-24（Asia/Shanghai）。**版本：Rev2.1，负责人批准（2026-10-25，[Plan §31.7](../specs/record2/v1/plan.md#317-负责人最终批准与-adr-accepted2026-10-25)）。G1/G2 未实现、未运行。** 本文拥有 ZIP 格式、逻辑数据清单、资源限额、恢复/回退/启动协议与故障矩阵；任务授权、实际验证和批准事实由 [V1 Plan §31](../specs/record2/v1/plan.md#31-g0正式逻辑-zip代际协议设计审核issue-18) 拥有；实现和真实设备证据归 G1。本文不改变冻结业务/Schema，不实现旧格式兼容，不复制旧 Vault，不运行任何“先导出看看”的数据实验。

依据：[Spec §6](../specs/record2/v1/spec.md)、[B0 §9](record2-b0-data-model-design.md#9-完整备份代际与恢复接缝)、[ADR 0018](decisions/0018-record2-data-concurrency-and-history.md)、[C1 证据](../engineering/evidence/2026-10-24-c1.md)、[E1 证据](../engineering/evidence/2026-10-24-e1.md)、[F0-C3 D-SAFE](../design/record2-f0-c3-review.md#8-d-safe授权历史备份与恢复)。冻结统一架构、Spec 行为、B0 数据语义和 C1/E1 状态机不被本文重开；本文只在 G0 授权内冻结恢复格式与协议。

**Rev2（2026-10-25，PR #28 审阅修订）：** 保留“逻辑 ZIP + 单事务原子恢复”总体架构及 G0-D1～D14 编号，不扩大实现范围、不改 Schema/Android 源码。本修订只处理四项审阅问题：§8.3 门禁的 Run 取消/迟到写回/事务前零写入；§8.5/§10.2 的 `restoreReceipt` 缺失可检测性；§5.1/§6 时间规则与 C0/C1 校验器对齐；§7.1 真正只读导出快照及 WAL/并发验证。相应 BK2/BK4/G1 测试契约同步增加，仍全部 NOT RUN。

**Rev2.1（2026-10-25，PR #28 第二轮审阅）：** 修复空数据段 ZIP 压缩规则（空段必须 STORED/0/0，禁止 DEFLATE 空块，增加往返测试）及目标 `authEpoch` 边界（旧值必须 `0..MAX-2`，拒绝写入 MAX，增加测试契约）。G0-D1～D14/总体架构不变；仍只改设计文档。

## 1. 结论与待批准决定

**2026-10-30 Rev4：S架构选型已批准，生产验收未通过。** 最新批准及实施边界归[Plan §35.7](../specs/record2/v1/plan.md#357-s架构批准与受控g1实施2026-10-30)。导出机制以本文§14覆盖旧§7.1具体连接方式和§6固定导出准入；旧Rev3及C2预算不生效。恢复、格式和容量硬限额保留。

推荐采用**单一手动完整逻辑 ZIP + 单一数据库事务整体导入**。不建设物理文件切换或第二套恢复框架。所有数值、状态转换和拒绝规则在本文件冻结；负责人批准前不得据此写恢复代码。

| ID | 推荐决定 | 理由/边界 |
|---|---|---|
| G0-D1 | 格式标识 `record2.logical`、`formatVersion=1`、`databaseSchemaVersion=1`；手动完整逻辑 ZIP，12 个数据段 + `manifest.json` | 稳定、可审阅、可独立验证；不兼容旧 format1/2、旧 Vault 或 CSV；不把 SQLite/WAL 活跃文件当逻辑包 |
| G0-D2 | 导出用**一个只读数据库事务**取得时点一致快照；不因 Run/自动保存/提交而在导出期间停写 | 快照内所有表同一瞬间；导出后发生的完成/提交不进入该 ZIP；恢复时 RUNNING 按协议转 INTERRUPTED |
| G0-D3 | 恢复用**一个 Room 事务整体删除并导入**；成功后才写完成收据；失败整体回滚 O | 不复制/替换 DB/WAL/SHM，不依赖外部完成标记；结果只有完整 O 或完整 S，不混库 |
| G0-D4 | 原生草稿只导出 `EDITING`；`SAVED`/`DISCARDED` 不进入 ZIP | `SAVED` 是上次保存核查材料，正式记录已在业务段；`DISCARDED` 无待恢复输入。恢复后草稿仍要求用户继续/放弃并重验基线 |
| G0-D5 | 恢复跨代际资格转换：RUNNING→INTERRUPTED；EDITABLE/LOCKED→NEEDS_REVIEW；清活跃 slot；Pending 保留原 `createdGeneration` 且永不执行 | 旧数据不因“直接执行”新策略自动获资格；不重写 Operation payload，不把待审状态当执行授权 |
| G0-D6 | 候选绑定整文件 SHA-256 + 字节数 + manifest `contentDigest` + 格式/Schema/目标代际上下文；源字节或上下文变化必须重新预检 | 确认的是实际验证过的候选，不是同路径的任意文件；不沿用旧候选 |
| G0-D7 | 完成标记为同一事务写入的 `record_data_state.restoreReceipt`；不建外部标记文件 | 与数据同原子；启动门禁只校验结构与目标代际，不自动修复/重放 |
| G0-D8 | 冻结下列字节/条目/行/压缩比/磁盘余量限额；越界拒绝，不截断 | 见 §6；每条阈值按“刚好到界/越界”由 G1 证明 |
| G0-D9 | 时间戳接受范围 `0001-01-01T00:00:00Z` 至 `min(9999-12-31T23:59:59.999Z, 导入当前时刻+24h)`；跨设备时钟差不超过 24h | 不静默裁剪/改时；超范围整包拒绝并说明。正常写仍禁止未来时刻 |
| G0-D10 | 预检/候选阶段可取消；用户确认“替换并恢复”并进入独占门禁后不可取消、不可重复、不可离开；进程终止由 SQLite 决定 O/S | 不伪称取消成功；不可中断阶段锁返回/重复动作 |
| G0-D11 | 不导出 `ai_control`、授权资格、Key/Keystore/密文/IV、Koog 缓存/隐藏推理/Trace/日志、SQLite 内部文件；恢复后 AI 为 OFF/无 grant，凭据不自动沿用 | Keystore 凭据文件不被恢复流程创建/删除/沿用授权；需要用户重新授权/配置 |
| G0-D12 | 不需要 Schema 变更；使用 Schema v1 现有 14 表及已存在 `restoreReceipt` 字段 | 若 G1 发现必须新增字段/表，停止并另取 G-SCHEMA 同意，不在本设计中预埋 |
| G0-D13 | 临时 ZIP 只放应用私有 `noBackupFilesDir` 的专用 staging 目录，大小受限；G1 必须为清理本任务临时文件取得明确范围 | 临时包含私人明文，必须避免外部存储/云同步；清理不是可跳过的可选动作 |
| G0-D14 | ZIP 不加密；SHA-256 只证明完整性，不证明来源/真实性；导出前明确告知 | 排除 Key 不等于备份文件保密；`D-SAFE` 负责最终文案/呈现 |

## 2. 范围与非目标

- **范围：** 手动完整逻辑导出、隔离预检、候选确认、独占恢复、失败回退、启动门禁；完整业务/隐藏关系/可见聊天历史/Applied 映射/不可执行 Pending/草稿段；格式、限额、编码、时间、身份/版本/引用拒绝规则和 BK2/BK4 故障点。
- **非目标：** 旧 P5/Vault/format1/2 兼容；CSV 完整备份；SQLite/WAL 文件复制或物理目录切换；云同步/自动后台备份/合并恢复/选择性表恢复；系统云备份或设备迁移的最终实现（G1 单独验证）；AI Provider/Key 恢复；Schema/迁移；最终 UI 视觉/文案（F0/F1/F2）；正式实现或设备测试（G1）。
- G0 不修改业务源码、Schema、普通数据库、环境/凭据/CI，不启动构建/设备/Provider/网络。

## 3. ZIP 格式 v1

### 3.1 容器与条目

ZIP 只包含下列 **13 个条目**（1 个 manifest + 12 个数据段），名称大小写敏感、ASCII、精确匹配：

```text
manifest.json
data/account.jsonl
data/category.jsonl
data/record_transaction.jsonl
data/note.jsonl
data/transaction_note_link.jsonl
data/native_finance_draft.jsonl
data/change_proposal.jsonl
data/commit_operation.jsonl
data/agent_session.jsonl
data/agent_run.jsonl
data/agent_message.jsonl
data/agent_message_reference.jsonl
```

- 不允许目录条目、重复名称、额外/缺失条目、绝对路径、`..`、反斜杠、符号链接、ZIP 注释、末尾附加数据、分卷、ZIP64、加密条目或非 `STORED`/`DEFLATE` 压缩方法。
- **空数据段（0 行、0 字节）必须使用 `STORED`，压缩/解压字节均为 0、CRC 为 0**；不得使用 `DEFLATE` 空块（标准实现常写 2 字节压缩数据）。只有非空段才在 `STORED`/`DEFLATE` 中选择。
- 数据描述符若存在，central directory 与实际流的 CRC/大小必须一致；无 central directory 的流式 ZIP 拒绝。只读逐条目流；声明大小/CRC 与实际流、manifest 描述必须一致。读取时不把条目名当文件路径，不向文件系统解包。
- 扩展名/显示文件名不参与格式识别；识别只依赖 manifest 的 `format`/`formatVersion`。SAF MIME 使用通用 ZIP 类型，不自定义扩展名语义。
- 导出器固定写入 manifest 与数据条目，ZIP 条目物理顺序不参与校验；manifest 内段顺序必须固定（见 §3.2）。

### 3.2 `manifest.json`

UTF-8、无 BOM、单个 JSON 对象、末尾一个 LF。字段封闭；未知字段、重复 key、缺失字段均拒绝。导出器使用固定字段顺序；导入器接受任意 key 顺序但按字段集合严格校验。示例字段（数值仅为形状示意）：

```json
{
  "format": "record2.logical",
  "formatVersion": 1,
  "databaseSchemaVersion": 1,
  "exportedAt": 1792729800000,
  "sourceGeneration": "00000000-0000-4000-8000-000000000001",
  "businessRevision": 42,
  "appVersionCode": 1,
  "appVersionName": "0.1.0",
  "entryCount": 13,
  "totalRows": 123,
  "totalUncompressedBytes": 45678,
  "segments": [
    {"name": "data/account.jsonl", "rows": 1, "uncompressedBytes": 123, "sha256": "64-lowercase-hex"},
    {"name": "data/category.jsonl", "rows": 9, "uncompressedBytes": 456, "sha256": "64-lowercase-hex"}
  ],
  "contentDigest": "64-lowercase-hex"
}
```

上例省略其余 10 段，仅展示字段形状；真实 manifest 必须恰好列出 §3.1 的全部 12 个数据段。

- `segments` 必须恰好 12 项，顺序固定为 §3.1 中 `data/` 的顺序；每项 `name` 不得重复，`rows`/`uncompressedBytes` 为非负整数，`sha256` 为 64 位小写十六进制。`totalRows`/`totalUncompressedBytes` 是对应求和，`entryCount` 必须为 13。
- `sourceGeneration` 必须为规范小写 UUID；`businessRevision` 为 `0..Long.MAX_VALUE-1`；`exportedAt` 为受 §5.1 时间规则约束的 epoch millis；`appVersionCode` 为正整数，`appVersionName` 为非空、≤128 个 Unicode code point、无控制字符。
- `contentDigest` 定义（UTF-8，LF；除每行规定的一个 LF 外不含额外空白，最后一段行末仍有一个 LF）：

```text
record2.logical\n
<formatVersion>\n
<databaseSchemaVersion>\n
<sourceGeneration>\n
<businessRevision>\n
<name>\n<rows>\n<uncompressedBytes>\n<sha256>\n   （按固定段顺序重复 12 次）
```

对上述字节取 SHA-256 小写十六进制。它绑定 manifest 元数据与 12 段内容；整 ZIP 的 SHA-256 在候选层另行计算，不能写进自身 manifest。

### 3.3 JSON Lines 与行编码

- 每个数据段是 UTF-8、无 BOM 的 JSON Lines；每行一个 JSON 对象，行间以单个 `\n` 分隔，最后一行也以 `\n` 结束；空表为 **0 字节**（不是空行）。禁止空行、CRLF、NUL 和控制字符裸写（按 JSON 转义）。
- 行对象字段封闭：缺字段、未知字段、重复 key、null 位置错误、类型错误均拒绝。null 字段必须显式输出为 `null`，不能省略。
- 字符串按 JSON 解析后的值逐字符保留：不做 Unicode 归一化、trim、大小写折叠或金额/日期重写；非法 UTF-8、孤立代理项/非法转义拒绝。金额/版本/ID 在数据库中与这里都是整数/文本，不做浮点转换。
- 整数必须是 JSON 数字中的十进制整数字面量；时间戳可为负（允许 0001 起的历史），其余整数字段必须非负；拒绝小数、指数、前导 `+`、超出 `Long` 范围以及用字符串冒充整数。
- 导出器按 §4 的排序规则输出；导入器接受任意行顺序，但必须检查主键/唯一键、引用、行数上限和相邻语义。行顺序不进入 `contentDigest`（段字节本身已哈希），因此重排后的包若字节不同必须重新哈希/重新验证。
- 每个载荷（`creationPayloadJson`、`payloadJson`、`frozenPayloadJson`、`resultJson`、`inputJson`）作为 JSON 字符串值保存；导入后取出的字符串必须与导出的持久化文本逐字节一致。载荷内部继续由既有 C0/C1/B1 封闭编解码器校验，不在 ZIP 层重新解释。
- ZIP 的 8 MiB 单行上限**不放宽**载荷内部冻结预算：C0/C1 仍为 65,536 字节、32 操作、32 配对、memo 1,000 字符、AI Note 正文 20,000 字符、深度 6；原生 Note 正文/交易 memo 不受 C0 proposal 预算限制，但仍受本格式单行/总字节上限约束。任何超出内部预算的载荷按原 codec 规则判损坏。

## 4. 数据清单与字段

### 4.1 14 表映射

| 表 | ZIP 处理 | 说明 |
|---|---|---|
| `record_data_state` | **不输出行**；`businessRevision`/`sourceGeneration` 进入 manifest | 恢复生成新代际/新 owner/receipt；来源 owner/receipt 不导入 |
| `account` | `data/account.jsonl` | 固定保留身份；允许改名后的 version 历史 |
| `category` | `data/category.jsonl` | 固定 9 个种子身份/方向；名称允许非空历史值 |
| `record_transaction` | `data/record_transaction.jsonl` | 全部正式收支；无硬删除 |
| `note` | `data/note.jsonl` | 含软删除正文/时刻；保留正常与已删除 |
| `transaction_note_link` | `data/transaction_note_link.jsonl` | 全部关系，含指向已删除 Note 的隐藏关系 |
| `native_finance_draft` | `data/native_finance_draft.jsonl` | **仅 `EDITING`**；SAVED/DISCARDED 排除 |
| `change_proposal` | `data/change_proposal.jsonl` | 全部状态；按 §4.3 转换活跃状态 |
| `commit_operation` | `data/commit_operation.jsonl` | Pending/Applied/Rejected 全保留；不改写冻结 payload |
| `agent_session` | `data/agent_session.jsonl` | 全部现存会话；已删除会话本就没有行 |
| `agent_run` | `data/agent_run.jsonl` | RUNNING 按 §4.3 转 INTERRUPTED |
| `agent_message` | `data/agent_message.jsonl` | 用户可见 USER/最终 ASSISTANT；无流式片段/隐藏推理 |
| `agent_message_reference` | `data/agent_message_reference.jsonl` | 消息到正式记录的引用；目标可软删除 |
| `ai_control` | **排除** | 恢复重置 OFF/无 grant；不导入授权资格 |

V1 没有必须随备份保存的“解释性偏好”数据库外存储：草稿/Proposal payload 已各自保存解释时刻所需的 `zoneId`/offset；主题、导航、滚动等易失/展示状态不属于完整逻辑备份。若未来新增会改变事实解释的持久偏好，必须以新 `formatVersion` 纳入，不能静默塞入 manifest 或依赖设备设置。

### 4.2 各段字段（导出字段顺序；导入按集合校验）

| 段 | 字段 |
|---|---|
| account | `id,name,currency,version,createdAt,updatedAt` |
| category | `id,name,direction,version,createdAt,updatedAt` |
| record_transaction | `id,direction,amountMinor,currency,accountId,categoryId,memo,occurredAt,version,createdAt,updatedAt` |
| note | `id,body,occurredAt,version,createdAt,updatedAt,deletedAt` |
| transaction_note_link | `transactionId,noteId` |
| native_finance_draft | `slot,draftId,draftRevision,state,mode,recordId,baseVersion,inputJson,updatedAt`（`saveReceiptJson` 不导出，导入置 null） |
| change_proposal | `id,sessionId,originRunId,lastWriterRunId,creationRequestId,creationPayloadJson,revision,payloadJson,payloadDigest,payloadFormatVersion,dataGeneration,state,activeSlot,createdAt,updatedAt` |
| commit_operation | `id,proposalId,confirmedRevision,createdGeneration,frozenPayloadJson,payloadDigest,payloadFormatVersion,state,createdAt,finishedAt,resultJson,rejectionCode` |
| agent_session | `id,title,createdAt,updatedAt,historyVersion,nextMessageSequence` |
| agent_run | `id,sessionId,dataGeneration,processOwnerId,receiverIdentity,authEpoch,proposalCreationRequestId,historyBoundarySequence,historyBoundaryVersion,state,activeSlot,startedAt,finishedAt,safeErrorCode` |
| agent_message | `id,sessionId,runId,sequence,role,body,createdAt,resultKind,resultJson,proposalId,proposalRevision` |
| agent_message_reference | `messageId,ordinal,transactionId,noteId` |

导出排序：

| 段 | 排序 |
|---|---|
| account/category | `id` 升序 |
| record_transaction/note | `occurredAt`、`id` 升序 |
| transaction_note_link | `transactionId`、`noteId` 升序 |
| native_finance_draft | `slot` 升序 |
| change_proposal/commit_operation | `createdAt`、`id` 升序 |
| agent_session | `createdAt`、`id` 升序 |
| agent_run | `startedAt`、`id` 升序 |
| agent_message | `sessionId`、`sequence` 升序 |
| agent_message_reference | `messageId`、`ordinal` 升序 |

### 4.3 恢复转换（唯一允许的语义改写）

下列转换在导入事务内执行；除这些字段外，其他持久化字段逐字段保留：

| 对象 | 来源状态 | 恢复后 | 精确变化 |
|---|---|---|---|
| Run | `RUNNING` | `INTERRUPTED` | `activeSlot=null`；`finishedAt=max(restoredAt, startedAt)`；`safeErrorCode` 保留来源值（通常 null）；`processOwnerId`/`authEpoch`/`dataGeneration`/`receiverIdentity` 仅作历史 |
| Proposal | `EDITABLE` | `NEEDS_REVIEW` | `activeSlot=null`；其余字段（含 `dataGeneration`/`updatedAt`/payload）不变 |
| Proposal | `LOCKED` | `NEEDS_REVIEW` | `activeSlot=null`；对应 Pending Operation 保留原 `createdGeneration`/冻结输入，不再执行 |
| Proposal | 其他状态 | 不变 | 终态保持可读；slot 必须已为 null |
| Operation | `Pending` | `Pending` | 不改 payload/createdGeneration/result；仅因新代际而不可执行 |
| Operation | `Applied`/`Rejected*` | 不变 | 结果/拒绝/历史完整保留 |
| Draft | `EDITING` | `EDITING` | 原 draftId/revision/mode/recordId/baseVersion/inputJson/updatedAt 保留；`saveReceiptJson=null` |
| `record_data_state` | 本地 O | 新本地状态 | 新 `dataGeneration`、新 `processOwnerId`、`businessRevision=manifest.businessRevision`、新 receipt |
| `ai_control` | 本地 O | OFF/无 grant | 删除旧行后写入固定 id=1、mode=OFF、所有 grant/selected/grantedAt=null；`authEpoch`：旧行缺失→1；旧行存在且 `authEpoch` 在 `0..Long.MAX_VALUE-2`→旧值+1（≤MAX-1，E0 `nextEpoch` 可接受）；旧行存在但 `authEpoch<0` 或 `>=Long.MAX_VALUE-1`→**在事务前拒绝恢复（TargetInvalid），不得写入 MAX** |

- 新代际必须与来源代际不同；新 owner 为本次恢复生成/采用的进程 owner，不能沿用来源 owner。
- 恢复后不存在 RUNNING Run、EDITABLE/LOCKED Proposal 或非 null 活跃 slot；导入事务提交前断言这一点。
- 旧 Pending 只能只读核查；用户要以新主动 Run/新创建身份/新确认形成新操作。任何旧 Operation payload 不得改写为新代际或新确认。
- 恢复不自动消费/保存草稿；草稿恢复后只允许继续/放弃，继续时按 B1 常规路径重验基线与输入。
- **`authEpoch` 边界说明（Rev2.1）：** 本规定只避免恢复写入 `Long.MAX_VALUE`（E0 `nextEpoch()` 会立即拒绝该值）；旧值 `MAX-2` 恢复为 `MAX-1` 仍在 E0 可接受范围，后续授权变更可能到达 MAX，这是既有 E0 上限，不在本设计内重做 epoch 重置/扩容。目标 `MAX-1`/`MAX`/负值恢复必须在事务前拒绝，不能靠恢复“修好”接近上限的授权状态。

## 5. 校验规则、合法历史与 oracle

### 5.1 身份、整数、时间与文本

| 项目 | 规则/拒绝条件 |
|---|---|
| 身份 | 规范小写 UUID 文本 `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`；大小写/花括号/空格/非 UUID 拒绝。默认账户/分类身份必须匹配 B1 种子集合 |
| 版本 | `version`/`revision`/`draftRevision`/`historyVersion`/`historyBoundaryVersion`/`authEpoch`/`confirmedRevision` 为 `1..Long.MAX_VALUE-1`；`businessRevision`/`nextMessageSequence`/`historyBoundarySequence` 为 `0..Long.MAX_VALUE-1`；`amountMinor` 为 `1..Long.MAX_VALUE` |
| 枚举 | `direction` 仅 INCOME/EXPENSE；`currency` 仅 CNY；Run/Proposal/Operation/草稿/角色/结果/错误码仅既有封闭集合；未知字符串拒绝 |
| 时间（通用导入范围） | 全部 epoch millis 必须落在 §6 的导入绝对范围内；`updatedAt>=createdAt`；`deletedAt` 为空或 `createdAt<=deletedAt<=updatedAt`；`finishedAt` 在 `createdAt/startedAt` 之后；RUNNING 的 `finishedAt` 必须为 null；消息时间落在 Run 生命周期内 |
| 时间（C0/C1 对齐，Rev2） | `change_proposal.createdAt/updatedAt` 必须 `createdAt>=0 && updatedAt>=createdAt`；`commit_operation.createdAt>=0`；载荷内 `ProposalTime` 必须完整通过 C0/C1 codec（`ZoneId`、±64800 offset、规范 localDateTime、毫秒精度、epoch 自洽）。§6 绝对范围是额外导入边界，不能替代 codec，也不能放宽这些字段最小值 |
| 金额/文本 | Note 正文非全空白且原样保存；memo 非 null 且原样（可为空串）；字符串不做 trim/归一化；行长 ≤8 MiB；非法 UTF-8/BOM/CRLF/孤立代理项拒绝 |
| JSON | 行与载荷必须是合法 UTF-8 JSON；重复 key、未知字段、浮点/指数金额、字符串化整数、超过深度 32 拒绝；载荷格式版本必须为 1 且由 C0/C1/B1 编解码器重新验证 |
| 摘要 | `payloadDigest`/`frozen payload`/manifest 段哈希/`contentDigest`/整 ZIP 哈希均为 64 位小写十六进制，且按定义重算相等 |

**时间/版本字段与现有 owner 校验器对齐（Rev2）。** 导入成功的数据必须在 C0/C1/B1/B2/E1 重读时不被判为 RecoveryRequired；导入接受集合是各 owner 校验集合的**单向子集**（导入可更严格拒绝，不能接受 owner 会拒绝的值）。§6 的绝对范围只是额外导入安全边界，不能替代或放宽下列规则；被它额外拒绝的极端时间属于导入失败，不是成功导入后的 RecoveryRequired。

| 字段/载荷 | 现有 owner 校验事实 | 导入必须满足 |
|---|---|---|
| `change_proposal.createdAt/updatedAt` | C0 `view`、C1 `displayed`: `createdAt>=0`、`updatedAt>=createdAt` | 同左；再加 §6 绝对范围 |
| `commit_operation.createdAt` | C1 `frozen`: `createdAt>=0` | 同左；再加 §6 绝对范围 |
| `commit_operation.finishedAt` | C1 只校验状态/null 组合，未校验负值/顺序 | 导入额外要求终态 `finishedAt>=createdAt`；更严格但不接受 C1 会拒绝的值 |
| Proposal/frozen payload 的 `ProposalTime` | C0/C1 codec：`ZoneId` 可解析、`offsetSeconds` 在 -64800..64800、`localDateTime` 规范且毫秒精度、`toEpochMilli` 自洽 | 先完整通过 codec，再满足 §6 范围；ZIP 层不得只查范围而跳过 codec |
| `transaction`/`note.occurredAt` | B1 `FinanceTime`/B2 只在正常写入时拒绝未来；读取不重验 | §6 范围 + `occurredAt<=updatedAt`；不把恢复历史按“新建写”重新拒绝 |
| `account/category/transaction/note/draft/session/run/message` 的其余时间 | 无 C0/C1 的 `>=0` 限制；B1/B2 另校金额/版本/正文/删除顺序 | §6 通用范围 + §5.2 顺序；不声称 C0/C1 会拒绝负值 |
| `deletedAt` | B2: null 或 `>=createdAt` | 同左，并 `<=updatedAt` |
| `businessRevision` | C0/C1 读取要求 `>=0`；递增 SQL 条件 `<Long.MAX_VALUE` | `0..Long.MAX_VALUE-1`；MAX 拒绝（否则不可正常推进） |
| `version`/`revision`/`draftRevision`/`historyVersion`/`historyBoundaryVersion`/`authEpoch`/`confirmedRevision` | C0/C1/B1/B2/E0 接受 `>=1`；写条件 `<MAX>` | `1..Long.MAX_VALUE-1` |
| `nextMessageSequence`/`historyBoundarySequence` | E1 在 `>=Long.MAX_VALUE` 拒绝；boundary 从本次历史边界取值 | `0..Long.MAX_VALUE-1`；`historyBoundaryVersion>=1` |
| `payloadFormatVersion` | C0/C1 必须为 1 | 必须为 1 |
| `amountMinor`/`currency`/`direction` | B1/C1: `>0`、CNY、INCOME/EXPENSE | 同左 |
| `note.body`/`deletedAt` | B2: 非全空白；`deletedAt>=createdAt` | 同左 |

校验事实来源：`RoomProposalService.view()/editable()`、`RoomCommitService.displayed()/frozen()`、`ProposalCodec.persistedTime()`/`CommitCodec.time()`；G1必须按实际源码复核这些规则，不能只按本文文字。若源码校验变化，导入器先同步收紧，再允许该格式版本通过。

§6 的绝对时间范围对 payload 内 `occurredAt` 也是额外导入边界；C0/C1 codec 是时间四元组自洽性的权威校验。若某个极端时间仅被 §6 拒绝，那是**导入拒绝**，不是“导入成功后服务判 RecoveryRequired”；不得为了接受极端历史时间而跳过 codec 或放宽 owner 最小值。

若未来 owner 增加更严格校验，导入器必须同步收紧或拒绝该格式版本；不能出现“导入 PASS、服务重读 RecoveryRequired”的组合。G1 必须对成功导入后的真实服务重读做专项断言（§10.3），不是只比对 14 表。

### 5.2 跨表状态

- `account` 恰 1 行且为保留账户身份；`name` 非空、`currency=CNY`、`version>=1`、`updatedAt>=createdAt`。`category` 恰 9 行、身份/方向匹配 B1 种子；每行 `name` 非空、`version>=1`。交易 `accountId` 指向该账户，`currency=CNY`、`amountMinor>=1`、`version>=1`、`categoryId` 为空或指向方向一致的分类、`occurredAt<=updatedAt`；关系两端存在；Note 可软删除但行必须存在，正文非全空白，`occurredAt<=updatedAt`，`deletedAt` 按 §5.1。
- `agent_session`：`historyVersion>=1`、`0<=nextMessageSequence<Long.MAX_VALUE`、`title` 为 null 或非空字符串、`updatedAt>=createdAt`。
- `agent_message` 的 `(sessionId,sequence)` 唯一且从 0 连续到 `nextMessageSequence-1`；`(runId,role)` 唯一；USER 消息恰 1 条/非空 body/`resultKind=INPUT`/无 result/proposal 字段；ASSISTANT 至多 1 条且 `resultKind!=INPUT`；`proposalId`/`proposalRevision` 同时为空或同时非空，非空时 Proposal 必须存在、属于同一 Session、revision>=1，且 revision 允许是历史 revision 而非当前值；消息 createdAt 落在 Run 的 `startedAt..finishedAt`（RUNNING 只要求 >=startedAt）；Run/Message 会话归属一致；引用 ordinal 从 0 连续且恰一个目标非空，引用消息必须为 ASSISTANT。
- `agent_run`：`(id,sessionId)` 唯一、`proposalCreationRequestId` 全库唯一；`state=RUNNING` ⇔ `activeSlot=1` 且 `finishedAt=null`；终态 ⇔ `activeSlot=null` 且 `finishedAt>=startedAt`；COMPLETED 恰有最终 ASSISTANT，其它状态不得有；RUNNING/COMPLETED 的 `safeErrorCode=null`，FAILED/CANCELLED 必须是已知非空错误码，INTERRUPTED 为 null 或已知码；`historyBoundarySequence<=session.nextMessageSequence`、`historyBoundaryVersion<=session.historyVersion`；`receiverIdentity` 非空、≤256 code point、无控制字符。
- Proposal 的 `originRunId` 必须存在且其 `proposalCreationRequestId` 等于 `creationRequestId`；`lastWriterRunId` 属于同一 Session；`creationRequestId` 全库唯一；`payloadDigest` 与 `payloadJson` 一致；`creationPayloadJson` 与 `payloadJson` 在 revision=1 时必须相等；`createdAt>=0`、`updatedAt>=createdAt`；EDITABLE/LOCKED ⇔ `activeSlot=1`，其余状态 ⇔ `activeSlot=null`；state/payloadFormatVersion 必须为已知值。
- Proposal↔Operation：`commit_operation.proposalId` 唯一；Proposal EDITABLE 不得有 Operation；LOCKED 恰有一个 Pending；NEEDS_REVIEW 可无或有 Pending；ABANDONED 无 Operation；终态 Proposal 若仍存在则恰有一个同终态 Operation。Pending Operation 的 Proposal 可因已删除 Session 而缺失；Applied/Rejected 同理可缺失。Operation 的 `createdAt>=0`；Pending 的 `finishedAt/resultJson/rejectionCode` 必须全为 null；终态 `finishedAt>=createdAt`；冻结 payload、result、rejectionCode 与状态组合按 C1 规则校验。
- Applied 结果中的创建/更新 ID 必须在正式业务表中存在；**不**要求当前 version/字段仍等于结果版本/冻结 payload（后续合法编辑允许）。Note 软删除仍算存在。关系计数必须在冻结配对数内。Proposal/Operation payload 内的目标身份是历史意图/冻结输入，不要求当前存在或版本匹配；只有正式表外键/消息引用和 Applied result 的稳定 ID 做存在性校验（Rejected 的 NotFound 历史不因目标缺失而判损坏）。
- 草稿：`slot=1`；仅 EDITING；`draftRevision` 为 `1..Long.MAX_VALUE-1`；NEW 的 `baseVersion=null` 且 `recordId` 不得已是正式交易；EDIT 的 `baseVersion` 为正且 `recordId` 必须存在；`inputJson` 只验封闭信封/字段类型，不要求金额/时间/账户/分类版本当前有效或基线匹配（陈旧草稿是合法历史，继续编辑时由 B1 重验）。信封必须为 `{"formatVersion":1,"input":{amountText,direction,accountId,accountVersion,categoryId,categoryVersion,memo,localDateTime,zoneId}}`，未知字段/未知版本拒绝；`inputJson` 字节不得超过单行上限。`saveReceiptJson` 不导出，导入置 null。

### 5.3 引用与合法历史 oracle

“合法历史”必须通过；“损坏/越界”必须整包拒绝，且 O 的 14 表规范化快照不变、重启可读。候选验证覆盖全部 12 段/全部引用，不跳过坏行、不以可选段或部分表继续；没有“部分恢复”模式。G1 用独立 SQLite 连接/独立解析器比较，不能用生产导入器构造期望。

| 引用/场景 | 合法历史（通过） | 损坏/越界（拒绝） |
|---|---|---|
| 业务外键 | 交易→账户/分类；关系→交易+Note；消息引用→交易/已软删除 Note | 缺失账户/分类/交易/消息目标；方向不匹配；孤儿关系 |
| 删除历史 | 软删除 Note 保留正文/时刻/隐藏关系；消息引用可指向已删除 Note | `deletedAt<createdAt`；把删除 Note 当正常可见事实但不保留行 |
| Applied 映射 | 目标后来被编辑（version 更高）；Note 后来软删除；Proposal/Session 已删但 Operation 自包含 | 结果 ID 缺失；结果与冻结操作数量/类型/身份不对应；拒绝码与状态矛盾 |
| Pending 隔离 | 旧代际 Pending；缺 Proposal 的 Pending；NEEDS_REVIEW 及其原 LOCKED/Pending 历史 | Pending 带 result/finishedAt；试图改写 payload 为新代际；把 Pending 当当前可执行 |
| Proposal 历史 | 终态可读；EDITABLE/LOCKED 转 NEEDS_REVIEW；创建身份绑定；Revision>1 的修订历史 | 创建身份被改绑；payload digest/格式错；slot/状态错配；终态 Proposal 无对应 Operation |
| Run/Message | INTERRUPTED 无最终回复；COMPLETED 有最终回复；失败输入仍可读；Session 零消息 | COMPLETED 缺回复；失败 Run 有最终回复；序列缺口/重复；角色/结果类型错配 |
| 草稿 | EDITING、陈旧 baseVersion、未完成/可解析输入 | SAVED/DISCARDED 混入草稿段；未知信封版本；NEW 的 ID 已存在；EDIT 的目标缺失 |
| 种子 | 账户改名 version>1；9 个固定分类身份/方向 | 第二账户；缺失/多余分类；错误方向/币种 |
| 空/边界 | 空段；整包刚好到限；仅 1 账户/9 分类；24h 未来边界内 | 超限 1 字节/1 行/1 条目；未来超过 24h；时间低于下界 |

## 6. 资源限额与磁盘余量

所有数值为 **v1 冻结合同**；越界整包拒绝，不截断、不跳过、不降级为“部分成功”。G1 对每项执行“刚好到界通过、越界拒绝”的边界测试。

| 常量 | 值 | 说明 |
|---|---:|---|
| `MAX_ZIP_BYTES` | 67,108,864（64 MiB） | 整个 ZIP 文件字节数上限（含 central directory） |
| `ENTRY_COUNT` | 13（精确） | manifest + 12 数据段；无额外条目 |
| `MAX_TOTAL_UNCOMPRESSED` | 268,435,456（256 MiB） | 12 段解压后总字节 |
| `MAX_ENTRY_UNCOMPRESSED` | 134,217,728（128 MiB） | 单段解压字节 |
| `MAX_MANIFEST_BYTES` | 1,048,576（1 MiB） | manifest 条目 |
| `MAX_LINE_BYTES` | 8,388,608（8 MiB） | 单行 JSONL（含换行前；UTF-8 字节） |
| `MAX_ROWS_TOTAL` | 500,000 | 12 段数据行总数 |
| `MAX_COMPRESSION_RATIO` | 100 | 仅非空段；空段必须 `STORED`/0/0，不参与比值 |
| `MAX_JSON_DEPTH` | 32 | 行/载荷 JSON 嵌套深度 |
| `MIN_TIMESTAMP_MS` | -62,135,596,800,000（0001-01-01T00:00:00Z） | 导入安全下界；不替代/不放宽 Proposal/Operation `createdAt>=0` 等字段规则（§5.1） |
| `HARD_MAX_TIMESTAMP_MS` | 253,402,300,799,999（9999-12-31T23:59:59.999Z） | 绝对上界 |
| `MAX_FUTURE_SKEW_MS` | 86,400,000（24h） | 相对导入当前时刻的未来容差；有效上界取 `min(HARD_MAX, now+24h)`；C0/C1 本身没有该上界，导入更严格 |
| `MAX_RECEIPT_BYTES` | 4,096 | `restoreReceipt` JSON 上限 |
| `MAX_RECEIVER_IDENTITY_CHARS` | 256 | `receiverIdentity` 历史字段 |
| `MAX_APP_VERSION_NAME_CHARS` | 128 | manifest 展示字段 |
| `MAX_EXPORT_WAL_GROWTH_BYTES` | 402,653,184（384 MiB） | 导出读快照期间 WAL 相对基线的增长上限（最大逻辑 256 MiB + 128 MiB 余量）；超过则中止导出 |
| `EXPORT_FREE_RESERVE_BYTES` | 67,108,864（64 MiB） | 导出期间 DB 卷与 staging 卷各自最低余量；低于则中止 |
| `GATE_DRAIN_TIMEOUT_MS` | 30,000 | 获取恢复门禁后等待在途短写事务排空的上限；超时放弃恢复且 O 零写 |
| 导出 staging 余量 | 134,217,728（128 MiB） | 开始导出前 staging 所在卷至少 64 MiB ZIP + 64 MiB 余量 |
| 导入磁盘余量 | `zipBytes + 2*totalUncompressed + currentDbBytes + currentWalBytes + 134,217,728` | 事务删除/插入、WAL、staging 和回滚余量；低于此值在门禁前拒绝 |

- 压缩策略：**空段一律 `STORED`，压缩/解压字节和 CRC 均为 0；不得写 `DEFLATE` 空块。** 非空段才优先 DEFLATE；若该段比值会超过 100:1，则改用 STORED，保证生成的包可被本格式接受；若整包仍超过 64 MiB，导出明确失败，不生成“成功”回执。导入器按 central directory 的压缩/解压大小和实际流双重检查：空段必须 STORED/0/0，`DEFLATE` 空块或 compressed>0 拒绝；非空段 compressed>0 且 `compressed*100>=uncompressed`。
- 限额是 V1 产品边界，不是旧 100000 项/20GiB/200:1 的继承。未来若产品数据规模需要更大，必须新 formatVersion/负责人批准，不能静默放宽。
- 导入磁盘余量在预检/门禁前按上述公式检查，低于阈值拒绝导入，O 不变。
- 导出开始时记录 DB 卷/staging 卷可用空间与 WAL 基线；快照期间持续监控 WAL 增长和两卷余量。WAL 增长超过 `MAX_EXPORT_WAL_GROWTH_BYTES` 或任一余量低于 `EXPORT_FREE_RESERVE_BYTES` 时立即中止导出、关闭读事务/丢弃 staging，不报成功；O 不变。G1 记录基线/峰值/增长，并证明中止路径可重试。
- 这些导出监控是只读快照的资源保护，不表示导出取得写锁；正常业务写可在快照期间继续（WAL），其能否完成与 WAL/余量上限由 G1 按 §10.4 验证。

## 7. 导出协议

### 7.1 前置与快照

**2026-10-26 Rev3 提案，尚未生效：** G1 已复现当前 API34 导出访问架构的并发/资源缺陷；以下已接受条款保留其历史效力，正式替换候选见 §13 与 [G0 导出架构复审](record2-g0-export-architecture-review.md)。本轮不把新方案写成 Accepted，也不授权生产改造。

1. 仅在正常启动门禁完成后提供导出；RecoveryRequired、恢复门禁进行中或数据库不可读时拒绝。导出与恢复共用唯一数据操作门禁：导出持只读操作 lease（直到 SAF 回读/失败结束），恢复在导出期间返回 Busy；导出**不**获取恢复写门禁，因此不阻塞正常业务写。
2. 用户经 SAF 选择目标并看到未加密/含私人数据/不含 Key/有效授权的告知；若存在 `EDITING` 草稿，告知必须说明它作为未提交输入保留、SAVED/DISCARDED 不包含且导出不改变草稿状态；staging 余量检查通过。
3. **真正只读快照（Rev2）：** 从应用私有 DB 路径打开独立的只读 SQLite 连接（`OPEN_READONLY` + `PRAGMA query_only=ON`），执行 `BEGIN DEFERRED`，在同一读事务中按固定段顺序读取全部 12 段和 `record_data_state`。**不得**使用 `RoomDatabase.withTransaction`、`beginTransaction`、`beginTransactionNonExclusive` 或任何可能取得写锁的连接；读事务只执行 SELECT，不 checkpoint、不执行会修改数据库文件的 PRAGMA/DDL/写语句（`query_only=ON` 是连接状态，不是数据库写）。
4. 同一连接前置检查 `PRAGMA journal_mode` 必须为 `wal`；WAL 是读快照不阻塞正常写的必要条件。非 WAL 时导出以明确的 unsupported 状态拒绝，**不以 Room 写事务降级**；G1 记录该环境前置。
5. 快照时点为 `BEGIN DEFERRED` 后第一次读取；全部段/`record_data_state` 必须来自同一连接/同一事务，不允许跨连接拼快照。读取时同步执行行级校验/限额计数并写入 staging ZIP 的数据条目/哈希；然后写 manifest（含 `contentDigest`）、关闭 ZIP，随后结束读事务/关闭只读连接，再进入 §7.2 SAF。manifest `exportedAt` 使用快照时点 Clock。
6. 导出**不**要求停写：只读快照不持写锁，正常业务写可在快照期间继续（WAL）；导出期间完成的 Run/自动保存/提交不进入该 ZIP，也不会造成混合表状态。WAL 增长和两卷余量按 §6 监控并可在越界时中止导出，不报成功。
7. 若只读连接在当前 Room/WAL 环境无法取得一致快照或无法看到当前 WAL 内容，**停止并回到 G0/实现审查**；不得静默改用 Room 写事务、复制 DB/WAL 文件或改用普通连接的多条 SELECT 拼快照。
8. RUNNING Run 按快照原样导出（RUNNING）；Pending/LOCKED 也按快照原样导出；恢复协议负责转换。导出不回写、不消费/保存草稿、不取消 Run、不写 `ai_control`。

**Rev2 说明：** 独立只读连接能否在当前 Android/Room/WAL 环境读取一致快照，是 G1 的环境前置；本设计不声称所有平台或低内存 journal mode 都支持。若非 WAL、只读连接失败或读不到当前 WAL，导出停止/拒绝，不产生第二套“写事务快照”或 DB 文件复制方案。

### 7.2 验证与 SAF 写入

1. 关闭 staging ZIP 后重新打开：检查原始字节大小 ≤64 MiB、条目集合、manifest、每段哈希/字节/行数、`contentDigest`、整 ZIP SHA-256；空段必须为 STORED/0/0，非空段压缩/比值合法；任何不一致均失败。
2. 通过 `ContentResolver` 打开用户选择的 SAF 输出，流式写入完全相同字节，`flush`/`fsync`（可用时）/`close`；随后重新打开该 SAF 文档，流式读取并核对总字节与 SHA-256。
3. 只有“写入+关闭+回读摘要/大小”全部成功才报告导出成功并显示简单回执（文件名/大小/时间/段/行数）。创建/写入/flush/close/回读任一步失败或取消都不报成功；外部 SAF 可能留下不完整文档，应用不把它列为有效备份，也不自动删除用户外部文件。
4. staging ZIP 含完整私人明文，仅位于应用私有 `noBackupFilesDir` 专用目录；成功/失败后由 G1 在明确授权范围内清理。不得写入外部存储、日志、聊天、崩溃报告或系统备份；不得把 staging 路径暴露给其他应用。
5. 导出失败不改变 O；导出不写 DB、不消费/保存草稿、不改 AI 授权。导出结果不产生 `restoreReceipt`。

## 8. 恢复协议

### 8.1 状态与可取消点

```text
IDLE → SOURCE_SELECTED → PREFLIGHT（可取消）
     → CANDIDATE_READY（可取消/可重选）
     → CONFIRMED → GATE/PREPARE（不可取消）
     → IMPORT_TXN（不可取消） → COMMITTED → POST_COMMIT → IDLE(S)
                                     └─ 失败 → ROLLED_BACK → IDLE(O)
```

- 预检与候选阶段可取消、可返回、可重新选择；不写 O、不建 staging（或只建临时读取缓冲，立即丢弃）。
- 用户明确确认“将整体替换当前数据”后，进入独占门禁；从门禁开始到提交/回滚结束，不接受取消、Back、离开页面、重复确认或其他恢复/导出。UI 不得在这段时间显示“已取消”或假装可返回。
- 进程终止不依赖 UI 取消语义：SQLite 事务未提交则 O，已提交则 S；下次启动按 §8.5 核查。
- 恢复完成后才显示“已恢复”；完成前不得显示部分导入/半库成功。

### 8.2 预检与候选绑定

目标前置：数据库可读且 Room Schema 为目标版本；目标可以是已初始化或空库，已有逻辑内容将整体替换；不要求目标已有 `record_data_state`/`ai_control`。若目标存在 `ai_control` 行，其 `authEpoch` 必须为 `0..Long.MAX_VALUE-2`；`<0` 或 `>=Long.MAX_VALUE-1` 在预检拒绝（TargetInvalid），O 不变，不得写入 E0 无法继续使用的 MAX。数据库不可读/无法打开时本协议不能执行，不提供删库重造的隐式修复。

1. 打开 SAF 源，检查可读、字节数 ≤64 MiB；流式解析 ZIP 结构、条目集合、manifest、版本/限额/行级规则/引用 oracle；任何失败在预检阶段拒绝，O 不变。
2. 流式计算整 ZIP SHA-256、字节数、manifest `contentDigest`；读取目标 `data_state` 的当前 `dataGeneration`（未初始化记为 absent）、目标 Schema 版本和 App 版本；形成候选摘要：来源显示名、字节数、导出时间、来源 App/Schema、来源代际、每段行数/总行数、总解压字节、“未加密/整体替换”提示。
3. 候选对象绑定：`sourceUri + sourceBytes + sourceSha256 + manifest.contentDigest + formatVersion + databaseSchemaVersion + targetGenerationAtVerification + appSchemaVersion`。以下任一变化必须重新预检，不能沿用旧候选：
   - 选择字节/大小/SHA-256/`contentDigest` 改变；
   - 格式/Schema/App 目标版本改变；
   - 目标 `dataGeneration` 改变（含另一次恢复/初始化）。
   普通业务写导致的 `businessRevision` 变化不使完整替换候选失效（替换的是当前全部数据），但候选显示的实际来源/数量仍以验证过的 manifest 为准。
4. 预检是只读的：不写 O、不建正式恢复标记、不执行 Run/工具/旧 Pending、不自动保存草稿。

### 8.3 独占门禁与 staging

确认后进入不可取消阶段。**门禁/prepare 阶段的事务前零写入是硬约束：这段路径只能读，不能写 O；任何失败都必须让 O 的 14 表逐字段不变。**

1. **获取写门禁（不写 DB）。** 获取进程级唯一数据操作门禁（导出/恢复互斥），门禁在**存储事务边界**执行，不只靠服务层检查。获取门禁本身不写数据库；其它写服务（自动保存/草稿/A/B 提交/Run 工具/创建 Session/凭据/AI 控制等）停止接受新请求，旧写 lease 对**新**写立即失效；已在 SQLite 事务内的短写允许完成并被等待，不强制回滚。读取可继续，但不得进入新写。
2. **排空在途写，不写 DB。** 在 `GATE_DRAIN_TIMEOUT_MS`（§6）内等待在途短写事务结束；不取消/不强杀数据库事务，也不通过 `failRun`/`cancelRun`/`interruptAllRunning`/`updateProcessOwner`/`advanceBusinessRevision` 或任何等效路径持久化取消。若在途写无法在门禁的受限等待内排空，放弃本次恢复并释放门禁，O 不变。**零写入保证针对门禁/prepare 自身；门禁前已开始的正常业务写可能正常完成并改变 O，门禁只等待、不回滚它们。** BK4 的“O 不变”以排空后的基线（或静止 O）比较，不能把无关业务写归因于恢复。
3. **当前 Run/AI 只在内存隔离。** 使当前 Run、工具请求、自动保存和旧 permit/owner/generation 的**内存资格**失效，并 best-effort 取消网络/协程；不得调用正常 E2 用户取消路径（它会写 CANCELLED/INTERRUPTED/释放 slot），也不得写草稿收据/AI 控制/receipt。O 中的 Run 行仍保持来源状态（通常 `RUNNING`/`activeSlot=1`）。外发/凭据使用入口在门禁期间也必须检查门禁/内存 epoch 并失败；不能只依赖 DB 的 `authEpoch` 在提交后才失效。已经开始的远程请求无法撤回，但其迟到结果不得落盘。
4. **迟到写回隔离。** 门禁期间任何旧 Run/工具/自动保存/提交尝试写入时，在 SQL 前以 `RestoreInProgress`/`Busy` 拒绝，不产生 DB 变更；服务错误处理不得捕获后再写一个终态/草稿。若在导入提交后到达，则由新代际/owner/authEpoch 拒绝。任何迟到写都不得修改 O 或 S。
5. **事务前校验只读。** 重新读取目标代际/App/Schema、目标 `ai_control.authEpoch` 边界并与候选上下文比较；重开源并流式复制到私有 staging ZIP（同时核 SHA-256/字节数）；再次执行结构/manifest/行级/引用/限额校验和磁盘余量公式。以上全部不写 O。任一失败：丢弃 staging、释放门禁、报告失败；O 的 14 表逐字段不变，无 receipt。被内存隔离的 Run 行仍是 `RUNNING`——它需要 E2 的独立受控核查/中断或下次启动 E1 所有权门禁处理；**该后续处理是单独的失败后恢复动作，不属于本次恢复尝试，不能算作“O 不变”测试的一部分**。失败后 UI 不得显示“已取消/已完成”；该 Session 在 E2 核查前不得开始新 Run。
6. **唯一写阶段。** 只有全部事务前校验通过后才进入 §8.4 单一导入事务；该事务持有恢复专用写 lease 并唯一写入 O/S。若事务失败/回滚，O 不变，仍按第 5 条的独立 E2/启动恢复处理，不把失败伪装成已取消或已恢复。
7. **staging 边界。** staging 只位于应用私有 `noBackupFilesDir` 专用目录；G1 负责明确清理范围；不得把源文件或 staging 暴露到外部存储/日志。第 4/5 条的最后一次源读取之后，源文件再变化不影响已捕获候选，导入始终使用与候选摘要一致的 staging 字节。

### 8.4 单一事务导入

在门禁内使用一个 Room 事务完成全部写入；这是恢复唯一允许写 DB 的阶段，§8.3 的门禁/prepare 路径不写任何表：

1. **删除顺序**：`agent_message_reference` → `agent_message` → `change_proposal` → `commit_operation` → `agent_run` → `agent_session` → `transaction_note_link` → `native_finance_draft` → `note` → `record_transaction` → `category` → `account` → `ai_control` → `record_data_state`。每个表删除全部现有行（不按来源过滤）。
2. **插入顺序**：`account` → `category` → `record_transaction` → `note` → `transaction_note_link` → `native_finance_draft` → `agent_session` → `agent_run` → `change_proposal` → `commit_operation` → `agent_message` → `agent_message_reference` → `ai_control` → `record_data_state`。先应用 §4.3 转换；载荷字符串按预检/复核后的字节原样插入。
3. `ai_control` 按 §4.3 写入 OFF/无 grant/epoch 递增；`record_data_state` 写 id=1、新 generation、新 owner、manifest `businessRevision`、receipt；receipt 是最后写入的字段/行之一，仍在同一事务内。
4. 提交前在同一事务内断言：12 段行数与 manifest 一致；无 RUNNING/EDITABLE/LOCKED；所有活跃 slot 为 null；`dataGeneration`/`restoreReceipt.restoredGeneration` 一致且 receipt 能通过启动同款解析；`ai_control` OFF；外键/唯一约束无异常。
5. 任一步失败（含 `SQLiteFullException`、约束异常、取消边界异常）越过事务边界回滚，O 完整保留；不得吞异常后提交部分。提交成功后才进入 POST_COMMIT。
6. 事务中不调用网络/模型/SAF；只读 staging 与数据库。事务耗时应由 staging 大小和限额约束，UI 在非主线程执行。

### 8.5 完成收据与启动门禁

`restoreReceipt` 是与数据同事务写入的完成标记，JSON 封闭、≤4 KiB：

```json
{
  "formatVersion": 1,
  "sourceZipSha256": "64-lowercase-hex",
  "sourceZipBytes": 123,
  "sourceFormatVersion": 1,
  "sourceSchemaVersion": 1,
  "sourceGeneration": "uuid",
  "restoredGeneration": "uuid",
  "restoredAt": 1792729800000,
  "contentDigest": "64-lowercase-hex",
  "importedRows": 123,
  "businessRevisionAtRestore": 42
}
```

**Rev2 —— receipt 可检测性边界（Schema v1；不新增持久化标记）：**

`restoreReceipt` 与数据在同一事务提交；按本协议，成功提交后 receipt 缺失不可达（除非外部篡改/损坏）。Schema v1 没有独立的“应已恢复”期望代际或外部完成标记，不能区分“从未恢复”和“恢复后 receipt 被外部删除/损坏”。因此只承诺下表可检测项，不承诺缺失检测，也不新增标记。

| 启动时条件 | 可靠检测 | 行为 |
|---|---|---|
| receipt 为 NULL | **否**；与从未恢复不可区分 | 正常路径；不因缺失判 RecoveryRequired；不自动修复；不声称检测“提交后丢失” |
| receipt 存在但 JSON/字段/版本/大小/hex/数值范围错 | **是** | RecoveryRequired；只读进入，不写/不修复；恢复入口仍可替换数据 |
| receipt 存在但 `restoredGeneration != data_state.dataGeneration` | **是** | RecoveryRequired |
| receipt 存在但 `sourceGeneration == restoredGeneration` | **是** | RecoveryRequired |
| `data_state` 行可解析但不是 id=1/字段不可读，且 receipt 非 NULL | **是**（DB 可读时） | RecoveryRequired；`id=1` 缺失且无 receipt 时按未初始化/正常启动，由 E1/Finance 处理 |
| 成功恢复后 receipt 被外部删除 | **否**；与从未恢复不可区分 | 普通启动；数据本身仍是完整 S（事务已原子提交），但审计/来源标记丢失；不新增标记、不自动修复 |

启动顺序（G1 实现时保持原有 E1/B0 语义）：

1. 打开数据库并完成 Room Schema 身份检查；只读读取 `record_data_state`/receipt。
2. 若 receipt **非 NULL**：严格解析字段/≤4 KiB；`formatVersion=1`；`sourceFormatVersion=1`、`sourceSchemaVersion=1`；`sourceGeneration`/`restoredGeneration` 为规范 UUID 且 `sourceGeneration != restoredGeneration`；`restoredGeneration == dataState.dataGeneration`；`sourceZipSha256`/`contentDigest` 为 64 位小写十六进制；`sourceZipBytes` 在 `0..MAX_ZIP_BYTES`；`importedRows` 在 `0..MAX_ROWS_TOTAL`；`restoredAt` 在 §6 时间范围；`businessRevisionAtRestore` 非负。不要求 `businessRevisionAtRestore` 等于当前 revision（正常写会推进）。任何不匹配 → **RecoveryRequired，且在任何写之前返回**：不执行迁移/修复/自动恢复，不开放业务写/AI；恢复入口仍可替换数据。
3. 若 receipt 为 **NULL**：视为“没有已记录的恢复”，走正常启动路径；**不**因缺失判 RecoveryRequired，也不尝试推断/修复“缺失的 receipt”。
4. receipt 有效或 NULL 时，按 E1 `beginProcessOwnership` 取得唯一进程所有权、将遗留 RUNNING 标 INTERRUPTED/清 slot；随后才开放业务与 AI。receipt 无效时不得先写 `processOwnerId`/中断 Run 再报错。
5. 恢复事务成功后，POST_COMMIT 更新内存代际/owner、失效 cursor/缓存、重置 AI 为 OFF、关闭旧编辑器/会话视图、回到安全入口；若此后重启，receipt+generation 使 S 继续有效。POST_COMMIT 的 UI/缓存失败不得把已提交 S 报成“恢复失败”；应报告“已提交，需重开”。
6. 启动门禁不自动执行旧 Pending、不自动重放旧模型/工具、不恢复旧授权/Key、不推断失败。恢复到新设备/新代际的聊天历史可读；旧 Pending 只读。启动不重跑全量业务 oracle（成本不可接受）：恢复事务在提交前已整体验证；Room/SQLite 打开、E1 读取或 receipt 校验失败按各自 RecoveryRequired 处理，不静默修复。

### 8.6 失败、回退与不变量

| 阶段 | 失败/终止 | 结果 | 收据/启动 |
|---|---|---|---|
| 预检/候选 | 格式/限额/数据/源变化/取消 | O 不变，无写 | 无新 receipt；正常启动 |
| 门禁准备（事务前） | 源变化、staging 失败、磁盘不足、在途写未清、Run 隔离/校验失败 | O 的 14 表逐字段不变，释放写门禁，无 receipt；旧 Run 行可能仍为 `RUNNING`，由独立 E2/下次启动所有权门禁处理 | 无新 receipt；失败尝试本身零写入 |
| 迟到写（门禁/提交后） | 旧 Run/工具/自动保存/提交晚到 | 在 SQL 前拒绝；不修改 O/S | 无新 receipt；不影响恢复结果 |
| 导入事务内（删除/插入/收据前） | 异常/取消/进程终止/磁盘满 | 事务回滚，O 完整；旧 Run 的后续处理同门禁准备 | 无新 receipt；启动按旧代际 |
| 提交成功 | 进程终止/回执后置失败 | S 完整，receipt 已持久 | 启动校验 receipt→S；不重放 |
| 启动校验 | receipt 存在但损坏/代际不符 | 不自动修复/不写 | RecoveryRequired；仅恢复入口 |
| 启动校验 | receipt 为 NULL | 视为无已记录恢复，正常启动 | 不可靠检测“提交后缺失”；不新增标记 |
| 导出 | 读/写/关闭/回读/取消/超限/WAL 或余量越界 | O 不变，无成功回执 | 不影响 receipt；staging 由 G1 清理 |

不变量：任何时刻持久库只有完整 O 或完整 S；不得出现“新业务 + 旧聊天/旧关系/旧删除状态”的混合；任何半包/半导入都不报成功；O 的 14 表规范化快照在拒绝/回滚后逐字段不变；S 的 14 表经独立 oracle 逐字段/引用比较（§4.3 转换除外）。

## 9. 安全、隐私与系统边界

- ZIP **未加密**；SHA-256 只做完整性校验，不能防止同长度替换/伪造，也不提供真实性。导出告知必须明示私人笔记、已删除记录、隐藏关系、可见 AI 历史和不含 Key/有效授权；F0/F1/F2 拥有最终文案/呈现。
- 不导出/不记录：API Key、Keystore 密钥材料、凭据密文/IV、有效授权、Koog checkpoint/模型上下文缓存、隐藏推理、原始工具 Trace、临时日志、流式片段、导航/滚动/主题等易失状态、SQLite main/WAL/SHM、系统备份/设备迁移载体。
- `receiverIdentity`/`authEpoch`/`safeErrorCode`/`rejectionCode` 仅作为历史/诊断字段；不得升级为授权、Provider 选择或工具权限。恢复后 AI 必须重新授权并重新配置/使用凭据；旧凭据是否仍在本机由 E0 的明确操作决定，恢复流程不自动清/建/使用它。
- 源 ZIP 是不可信输入：不解包到文件系统、不执行任何载荷、不按条目名写路径、不执行 SQL/文件/网络/系统命令；所有值经封闭解析器/DAO 参数写入；拒绝 path traversal、重复名、ZIP64、加密、异常压缩比、超限解压。
- staging/临时文件只在应用私有空间，避免系统备份/云同步；G1 必须在任务授权中写明临时文件清理范围，不能把 cleanup 当删除用户文件的通用许可。应用不自动删除用户 SAF 外部文件。
- 恢复后不自动重放旧 Pending/工具/模型；旧 Run/Proposal 只成为可读历史；跨代际 permit 必须失效（代际+owner+authEpoch 至少一项在请求/结果边界复验）。

## 10. G1 验证接缝（本文 NOT RUN）

G0 只冻结合同；以下由 G1 在获批源码/设备范围执行，G2 做最终联合验收。本文不把任何设计表当 PASS。

### 10.1 BK2 边界/拒绝矩阵

| 类别 | 必须测试的边界 | 期望 |
|---|---|---|
| 容器 | 截断/CRC 错/EOCD 缺失/尾随字节/注释/加密/方法 99/ZIP64/分卷/目录/重复名/额外或缺失段/改名/`..`/绝对路径/反斜杠/符号链接 | 整包拒绝；O 不变 |
| manifest | >1 MiB/非法 JSON/重复 key/未知字段/marker 错/formatVersion 0 或 2/schemaVersion 2/entryCount/总行数/总字节/段顺序或名称/段哈希/段字节/contentDigest 不符 | 整包拒绝；O 不变 |
| 资源 | ZIP 64 MiB±1、总解压 256 MiB±1、单段 128 MiB±1、行 8 MiB±1、行数 500,000±1、比值 100±、深度 32±、manifest 1 MiB±1、磁盘不足 | 刚好到界通过；越界拒绝且 O 不变 |
| 空段/ZIP 压缩 | 0 行段 `STORED`/0/0 往返；`DEFLATE` 空块（常见 2 字节）/compressed>0/解压非 0；非空段 STORED/DEFLATE 比值边界 | 空段 STORED/0/0 通过并往返一致；空 DEFLATE 或压缩字节不符拒绝；O 不变 |
| 身份/版本 | 大写/花括号/非 UUID/重复 PK/版本 0 或 MAX/金额 0/负/超 Long/序号缺口/重复 ordinal/authEpoch 0 或 MAX/businessRevision 负或 MAX | 整包拒绝；O 不变 |
| 时间 | 低于下界/超过 24h 未来/刚好 24h/createdAt>updatedAt/deletedAt<createdAt/finishedAt<startedAt/RUNNING 有 finishedAt/消息在 Run 外 | 边界内通过；越界拒绝；不改写时间 |
| 时间/owner 对齐（Rev2） | Proposal/Operation `createdAt<0`；payload `offsetSeconds` 为 ±64800 边界/越界；localDateTime 非规范/非毫秒精度；epoch 与 local+offset 不自洽；导入成功后 C0/C1/B1/B2/E1 重读 RecoveryRequired | 对应阈值内通过；越界/不自洽拒绝；合法候选导入后 owner 重读不得 RecoveryRequired |
| 数据 | 账户数≠1/分类集合或方向错/缺失引用/孤立关系/空白 Note/币种/方向/分类方向错/未知枚举/未知字段/重复 key/浮点/指数/非法 UTF-8/BOM/CRLF/孤立代理/超长字符串/SAVED 或 DISCARDED 混入草稿 | 整包拒绝；O 不变 |
| 跨表 | 创建身份改绑/payload digest 或格式错/Proposal slot/状态错/Proposal-Operation 关系错/Operation result 或 rejection 组合错/Applied 目标缺失/终态 Proposal 无 Operation/消息 proposal 缺失/引用目标缺失/Run-Message 归属错/COMPLETED 缺回复/失败 Run 有回复/序列缺口/NEW 草稿 ID 已存在/EDIT 草稿目标缺失/未知草稿信封 | 整包拒绝；O 不变 |
| 合法历史 | Applied 目标后续编辑/Note 软删除/隐藏关系/Proposal 或 Session 删除后 Operation 自留/旧代际 Pending/NEEDS_REVIEW+Pending/终态 Proposal/INTERRUPTED 无回复/陈旧 EDIT 草稿/改名账户/零消息 Session/空段/边界内未来时间；Proposal/Operation `createdAt=0` 与 payload 时间四元组自洽 | 通过；S 与独立规范化 oracle 一致；导入后 owner 重读无 RecoveryRequired |

### 10.2 BK3/BK4 故障矩阵

| 阶段 | 故障注入 | 期望 |
|---|---|---|
| 导出 | 快照读失败、staging 写失败/满、限额越界、manifest/段/整体校验失败、SAF create/write/flush/close 失败、回读摘要/大小不符、取消；WAL 增长/余量越界 | 不报成功；O 不变；不列半包；staging 按授权清理；并发写/快照测试见 §10.4 |
| 预检 | 源不可读、ZIP/行/引用/限额错、摘要不符、源变化、目标代际变化、取消；目标 `ai_control.authEpoch` 为 `MAX-1`/`MAX`/负值 | O 不变；无门禁/无 staging 残留；authEpoch 越界在事务前拒绝，不写 MAX |
| 预检（authEpoch 边界） | 目标 `ai_control.authEpoch=MAX-2` | 恢复后新 epoch=`MAX-1`（E0 可接受）；不得写 MAX；E0 既有 MAX 边界另行记录，不由恢复修复 |
| 门禁准备（有活动 Run/在途写） | 门禁忙、在途写未清、源变化、staging 失败/满、复核失败、磁盘不足；Run/工具/自动保存/提交迟到写 | **事务前零写入**：O 的 14 表逐字段不变、无 receipt；迟到写在 SQL 前拒绝；旧 Run 行可能仍 RUNNING，后续 E2/启动处理单独记录 |
| 导入事务 | 删除前/删除后/首插后/全插后/收据前/提交前异常、SQLiteFull、取消、进程终止 | 未提交→O 完整；已提交→S 完整；无混合；事务外无写 |
| 提交后 | 进程终止、缓存/重开失败；旧 Run 迟到结果 | S 持久；代际/owner 拒绝迟到写；下次启动 receipt 校验；不报“恢复失败” |
| 启动（receipt 存在） | 损坏/代际不符/字段或数值越界 | 不写/不修复；RecoveryRequired；恢复入口可用 |
| 启动（receipt 为 NULL） | receipt 缺失 | 视为无已记录恢复，正常启动；**不可靠检测**“提交后缺失”，不新增标记、不判 RecoveryRequired |
| 导入后 owner 重读 | 合法候选导入后 C0 `proposal()`、C1 `inspect()`、B1 `transaction()/readDraft()`、B2 `visible()/deleted()`、E1 `sessionMessages()/persistedResult()` | 不得 RecoveryRequired；与 §5.1 时间/版本规则一致 |
| 独立比较 | 每次拒绝/回滚 | O 的 14 表规范化快照逐字段不变、重启可读；每次成功 S 逐字段/引用与 oracle 一致（§4.3 转换） |

### 10.3 必测 oracle 与范围

- 独立 SQLite 只读连接读取 O/S 全 14 表，按固定 ORDER BY 比较；不能调用生产导出/导入器生成期望；不能只比较计数。
- BK1：S→ZIP→新隔离 R→重启逐字段/稳定 ID/整数/正文/时间/全部关系/账户分类/删除状态；恢复 Note 后关联可见。BK2 见上表；BK3 导出失败/取消；BK4 全故障/进程终止；BK5 SAF 取消、候选显示/整体替换/源变化重验。
- **导入后 owner 重读（Rev2）：** 合法候选导入后，逐行通过现有服务读取：C0 `proposal()`（全部 Proposal）、C1 `inspect()`（全部 Operation）、B1 `transaction()`/`readDraft()`、B2 `visible()`/`deleted()`、E1 `sessionMessages()`/`persistedResult()`；任何 RecoveryRequired 即失败。该测试证明时间/版本规则不会“导入成功后被服务判损坏”，并需覆盖 `createdAt=0`、payload offset ±64800 边界及自洽时间四元组。
- **receipt 检测边界（Rev2）：** malformed/字段或代际不匹配的 receipt 必须 RecoveryRequired 且零写；NULL receipt 必须正常启动且不写；模拟“成功恢复后删除 receipt”时，记录为不可靠检测（与从未恢复不可区分），不新增标记、不声称检测；不得用 `NEEDS_REVIEW`/旧代际 Pending 作为“已恢复”的推断依据。
- **空段往返（Rev2.1，`emptySegmentsUseStoredZeroBytesAndRoundTrip`）：** 新安装/合法零行段导出→导入→再次导出；每个空段必须 `STORED`/compressed=0/uncompressed=0/CRC=0，导入后逻辑内容与源一致；人为构造 `DEFLATE` 空块（通常 compressed=2）或 compressed>0 的“空段”必须拒绝且 O 不变。该测试覆盖新安装最容易出现的多空段场景。
- SES-09/14、SD-P4 的跨代际聊天可读、Pending 不执行、授权/Key 重建、删除/隐藏关系完整；TP-08 的恢复后不自动重复提交。
- 真实 Room/磁盘/SAF/API34 专用设备与获批真机范围由 G1/G2 任务授权；恢复不得在真实用户库上做实验。G0 未运行任何构建、设备、数据库或数据实验。
- G1 必须在 API34 参考环境实测最坏限额（64 MiB ZIP/256 MiB 解压/500,000 行）的导出、预检、导入和进程恢复耗时/内存，确认无 ANR/OOM；若单事务在真实设备不可接受，必须先回到 G0 调低限额或重新审核协议，不能静默切换到第二套恢复实现，也不以本设计表代替性能 PASS。

### 10.4 Rev2 新增 BK4/G1 测试契约

**A. 门禁/事务前零写入（对应 §8.3）**

| 测试 | 必须证明 |
|---|---|
| `gateFailureWithActiveRunLeavesAll14TablesIdentical` | O 含 RUNNING Run/活动 slot；进入门禁并在源/目标校验处失败；释放门禁后、任何后续 E2/启动处理前，14 表逐字段/逐行等于**排空后基线或静止 O**；无 receipt；Run 仍为 RUNNING/activeSlot=1。若测试另注入门禁前在途正常写，先允许其提交并重取基线；不得把该无关写算作恢复失败改动 |
| `gateRejectsLateRunToolAutosaveCommitWithoutSqlWrite` | 门禁期间旧 Run/工具/自动保存/A/B 提交尝试写；均以 `RestoreInProgress`/`Busy` 在 SQL 前拒绝；14 表零变化；服务不得捕获后写终态/草稿 |
| `prepareFailureAfterStagingLeavesOUnchanged` | staging 已完成但目标上下文/源摘要/行校验/磁盘检查失败；O 14 表不变；staging 丢弃；无 receipt |
| `gateDrainTimeoutLeavesOUnchanged` | 另一连接持有写事务超过 `GATE_DRAIN_TIMEOUT_MS`；恢复放弃、释放门禁、零写 O、无 receipt；不得强杀该事务 |
| `restoreRefusesAuthEpochAtMaxMinusOneBeforeAnyWrite` | 目标 `ai_control.authEpoch=MAX-1`（或 MAX/负值）；预检在事务前拒绝 TargetInvalid；O 14 表不变、无 receipt、`ai_control` 不被改写；不得写 MAX |
| `restoreAuthEpochMaxMinusTwoWritesMaxMinusOneOnly` | 目标 `authEpoch=MAX-2`；恢复写 `MAX-1`，不写 MAX；E0 `state()` 可读，并执行一次受控模式/授权状态变更证明 E0 接受该值；该变更后到达 MAX 属既有 E0 上限，单独记录，不冒充已修复 |
| `transactionRollbackWithCancelledRunLeavesOUnchanged` | 导入事务内注入异常/SQLiteFull/进程终止；回滚后 O 14 表不变；无 receipt；旧 Run 处理与失败恢复分开 |
| `lateResultAfterAbortOrCommitCannotWrite` | abort 后旧 lease 仍无效；commit 后旧 generation/owner 无效；迟到写不修改 O/S |
| `processKillDuringGateOrPrepareLeavesOUnchanged` | 进程在门禁/prepare 各点终止；重启后 O 与写前一致；启动 E1 对遗留 RUNNING 的正常中断作为独立事实记录，不归因于恢复事务 |

**B. 导出真正只读快照、并发写与 WAL（对应 §7.1/§6）**

| 测试 | 必须证明 |
|---|---|
| `exportUsesReadOnlyConnectionAndWal` | 实际使用 `OPEN_READONLY`/`query_only` + `BEGIN DEFERRED`，`PRAGMA journal_mode=wal`；不使用 Room 写事务；读事务只 SELECT、不 checkpoint/DDL/写 PRAGMA；读连接能看到当前 WAL 快照 |
| `exportSnapshotDoesNotBlockConcurrentWriter` | 快照打开期间，另一个连接执行正常业务写（交易/Note/关联或自动保存）可提交；无整段快照期间的写锁等待；允许短暂 `SQLITE_BUSY` 重试但不得把正常写误报为永久失败 |
| `exportSnapshotIsPointInTimeAcrossTables` | 快照开始后执行一个多表原子写（如交易+关联+版本）；导出的 ZIP 只含完整前态或完整后态，不含混合；再用独立基线核对所有段/哈希 |
| `exportConcurrentWriteVisibility` | 快照前提交的写进入 ZIP；快照开始后提交的写不进入 ZIP、但导出后 DB 可见；`exportedAt`/段内容与同一快照点一致 |
| `exportMonitorsWalGrowthAndAborts` | 记录 WAL 基线与峰值；并发写使增长超过 `MAX_EXPORT_WAL_GROWTH_BYTES` 或卷余量低于 `EXPORT_FREE_RESERVE_BYTES` 时，导出中止、不报成功、O 不变、staging 丢弃/按授权清理；中止后可重试 |
| `exportRequiresWalWithoutWriteFallback` | 非 WAL 或只读连接失败/读不到当前 WAL 时，导出以明确 unsupported 拒绝；不得回退到 Room 写事务、DB/WAL 复制或多连接拼快照；O 不变 |
| `exportDoesNotMutateDraftRunOrAiControl` | 导出前后 EDITING 草稿 revision、RUNNING Run、Pending/LOCKED Proposal、`ai_control`、`record_data_state` 逐字段不变；不消费草稿、不取消 Run、不写 receipt |
| `exportRestoreMutualExclusion` | 导出持数据操作 gate 期间，恢复进入写门禁被拒（Busy），不写 O；导出结束后恢复可正常开始；正常业务写不受导出 gate 影响 |
| `exportReadOnlyFailureLeavesNoSuccessOrStateChange` | 读连接/段读取/staging/校验失败均不产生成功回执；O 不变；不列半包；staging 清理有明确范围 |

以上测试均为 G1 设计契约，G0 未运行；任何一项不通过都阻止 G1 声称 BK3/BK4 或导出并发安全完成。

## 11. 需负责人批准的事项

1. 是否接受 G0-D1～D14 及 Rev2 细化，特别是：逻辑 ZIP/无物理切换、独立只读 SQLite 快照（非 Room 写事务，WAL）、门禁事务前零写入、单事务导入、草稿仅 EDITING、24h 时钟容差、C0/C1 时间字段对齐、receipt 缺失不可检测的明确限制、资源限额、无加密告知、无需 Schema 变更。
2. 若接受，G0 设计完成，ADR 0019 可转 Accepted；issue #18 才可关闭。若调整任一数值/状态转换，本文与 ADR 同步修订后重新审阅，不先写实现。
3. G1 实现/测试、Schema 写入（本设计不需要）、设备/SAF/真机、临时文件清理及 G1 产物的提交/推送/发布均为独立授权；本设计不自动激活。G0 文档本身的审阅提交/推送/PR按 Plan §31 的交付记录处理。

## 12. 来源与责任

- 行为/AC owner：[Spec §6/§7](../specs/record2/v1/spec.md)；数据语义 owner：[B0](record2-b0-data-model-design.md)；执行/任务状态 owner：[V1 Plan §31](../specs/record2/v1/plan.md#31-g0正式逻辑-zip代际协议设计审核issue-18)；长期恢复决定：[ADR 0019](decisions/0019-record2-logical-backup-restore-protocol.md)。
- C1 冻结/回执状态、E1 历史/所有权、E0 授权/凭据、F0-C3 D-SAFE 分别由原 owner 拥有；本文只引用，不覆盖。
- 本文为负责人已批准的G0设计审查包（Plan §31.7），未修改源码、Schema、普通数据库、构建、环境/凭据/CI；所有 G1/G2 行为均 NOT RUN。

## 13. Rev3 导出访问架构修订提案（2026-10-26，Proposed）

本节是对 §7.1、§6 导出空间预算、§9 私有暂存范围及 §10.4B 测试形态的**待批准替换条款**；原 G0-D1～D14、Rev2/Rev2.1 批准记录保留。它不改变 ZIP v1/12段/64MiB ZIP/256MiB解压/500,000行等产品上限，不改变恢复/receipt/AI安全合同，也不启动生产实现。

建议以如下唯一流程替换 §7.1 第3～7项的具体读取机制：

1. 系统 SQLite 使用独立 `OPEN_READONLY` 来源句柄，验证正常启动门禁、WAL、Schema身份；使用禁止自动删库/重建的损坏处理器。导出持既有export lease，正常业务写可继续，恢复仍互斥。
2. 使用 SQLite 官方 `VACUUM INTO` 创建新的、应用私有noBackup全库scratch；由引擎提供一致性，不复制活跃DB/WAL/SHM文件，不在来源调用Room写事务/停写。此阶段有意允许向新attached目标写入，因此不能再要求整个连接query_only或只执行SELECT。
3. 将取消/资源失败接到当前调用的CancellationSignal，并等原生操作和cursor/来源完全关闭；成功后验证目标，失败/强停的目标一律废弃，不续用。
4. 只对已完成的不同文件使用bundled只读连接/逐行step；12段和data_state全部来自同一静态目标。排序键与载荷分离，生产工作集须有界；不得让bundled与framework同时操作同一活跃文件，不沿用来源rowid。
5. exportedAt使用采集开始Clock并保留内部采集时间窗，generation/revision来自目标。此为可见语义调整：窗前提交必含、窗后提交必不含、窗内提交允许完整前态或后态但不得混合；不把开始Clock当成精确内核快照时点。既有ZIP验证、SAF关闭/回读、失败不报成功规则保留。

私有scratch可能包括非导出草稿、ai_control、旧receipt、索引及Room元信息；仅限当前操作内部，不进入ZIP/外部文档/日志/系统备份。空间预算不能用256MiB逻辑上限替代物理体积：按来源页体积、复制期增长、源WAL、键/排序文件、段、ZIP与余量重新计算，并持续监控。

具体待批公式、独立目标体积cap、40B键记录/固定run/fan-in≤16的外部归并与RAM/磁盘上界、候选性能预算、原型证据、未通过项和后续门禁由 [架构复审报告 §4～6](record2-g0-export-architecture-review.md#4-实际结果) 拥有。来源页体积仅为估计，不能由WAL增长推导目标必然界内；真实体积/余量越界要中止。§10.4B的测试须调整为“来源OS只读、引擎一致物理采集、采集窗可见性、静态目标提取、复制中并发写/中断/资源故障/强停/私有清理”，不能继续用单语句cursor寿命代替快照证明。

状态：Proposed。只有负责人接受本次架构/隐私/预算调整后，才将这些替换条款并入生效正文，再按另行授权启动G1生产改造；本次不合并PR #31或关闭issue #19。

## 14. Rev4 S导出架构（选型Accepted，2026-10-30）

1. 正常启动后持既有export lease；恢复互斥，正常写入不停。独立系统OS只读来源验证WAL/Schema，提供禁止自动删库的错误处理器；通过VACUUM INTO创建私有noBackup全库scratch，不复制活跃DB/WAL/SHM，不用Room写事务。取消/资源越界调用signal并等待native退出后清理。
2. 目标成功后检查独立身份、Schema/Room identity、journal、integrity/FK，关闭来源。只在同一静态目标提取12段及data_state，固定键与当前载荷分离；不再使用原递归JSON物化查询，不保留完整JSONL。原严格校验器不放宽，manifest-last、空段STORED/0/0及非空100:1规则保留；必要时同一静态目标重读，绝不部分成功。
3. exportedAt为采集开始Clock，实际generation/revision来自目标；窗前提交必含、窗后必排除，窗内只能完整前/后态。导出不写源库、不取消Run/改AI/消费草稿或写receipt。
4. 按阶段并存量计算工作预算：copy=S+Q+W，extract=S+Z+K，delivery=私有Z+同卷目标Z；关闭核验后先回收S/K再SAF。来源既有文件/空闲页不重复预留；F保留原64MiB，同卷一次，ZIP配额按实际剩余空间更新。原§6的128MiB固定导出准入被本项替换；64MiB ZIP/256MiB总解压/128MiB段/8MiB行/500k行/100:1/384MiB WAL等硬限制全部不变。
5. 来源页数是估计，采样cap不是硬空间上界；不能声称有空间就一定完成。持续检查实际目标、临时文件、WAL和剩余空间，失败等待退出、清理且源仍可写。Q/OEM/超调、真实ENOSPC和大字段分配边界必须有生产证据；未闭合不得合并。失败不报告成功、不复用强停scratch，不删除用户外部文档。
6. 创建/注册活动任务与启动清扫必须共享完成屏障，failed清扫必须记录；任何清扫不得删除活动任务。恢复核心/单事务/receipt/资格转换及原SAF关闭回读不改。选型批准不抵扣Plan §35.7全部生产、平台、性能及G2门禁。

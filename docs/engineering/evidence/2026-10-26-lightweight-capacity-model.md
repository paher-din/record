# 轻量化备份容量模型：1 / 3 / 5 年情景

2026-10-26。结论：**按下述假设，普通5年约22.1MiB逻辑JSONL、重度5年约210.6MiB；重度容量首先受AI历史和提案载荷重复影响，而非50万行。** 这是可复算情景，不是用户行为统计、产品承诺、合法业务夹具或限额调整依据。64MiB ZIP是否通过不能从未压缩字节数直接推断。

## 1. 来源、命令与假设

一手结构来源仅为当前 [Schema v1导出](../../../record2/app/schemas/com.paherdin.record2.storage.RecordDatabase/1.json)、storage实体与 [LogicalFormat/SegmentCodecs](../../../record2/app/src/main/kotlin/com/paherdin/record2/backup/LogicalFormat.kt)。脚本从Schema读取完整14表DDL/索引，从LogicalSegment读取12段字段与顺序；不凭旧数据库或旧备份格式猜字段。

运行 [lightweight-capacity-model.py](../../../record2/tools/lightweight-capacity-model.py)：

```text
python record2/tools/lightweight-capacity-model.py
python record2/tools/lightweight-capacity-model.py --calibration-rows 0
```

首条执行完整host小样本校准，第二条只算逻辑模型。每次创建新的 `record2/build/lightweight-backup/capacity/<UTC-runId>/`，不覆盖/删除旧文件。完整成功回执：`20261026T131006Z-052e72a9/capacity.json`，包含所有年份的逐表行数、逻辑UTF-8字节/贡献、物理估算、阈值与DDL校准细节。该JSON可由命令重新产生；不是需要纳入版本库的用户数据库。

本次脚本SHA-256：`96d876701893a8945da29eff88d0255238999f081470e7f08a19cc29c5aa4502`。JSON还记录Schema/Codecs/4份实体的输入摘要；Schema identity为 `c1b84c0a7822f354324484b6995cfb0d`。

| 每日/每条假设 | 轻 | 普通 | 重 | 极端 |
|---|---:|---:|---:|---:|
| 交易 / 笔记 / AI turn每日 | 1 / .15 / .2 | 4 / 1 / 2 | 12 / 4 / 8 | 40 / 12 / 30 |
| 每session的turn | 4 | 8 | 12 | 20 |
| turn产生Proposal概率 | .05 | .2 | .35 | .5 |
| Proposal被Applied概率 | .5 | .6 | .7 | .8 |
| 每turn引用 / 每交易关联 | .3 / .1 | 1 / .25 | 2 / .5 | 3 / 1 |
| 平均单份Proposal payload UTF-8 B | 2,048 | 4,096 | 8,192 | 16,384 |
| memo / Note正文 UTF-8 B | 40 / 256 | 80 / 1,024 | 160 / 4,096 | 320 / 16,384 |
| User / Assistant正文 UTF-8 B | 80 / 512 | 160 / 1,024 | 320 / 2,048 | 640 / 4,096 |

计算规则与额外假设公开如下，未修改题面的四档参数：

- 每年365天，各阶段行数使用精确有理数乘法再向上取整；sessions=`ceil(turns/每session turn)`。每turn=1 Run+1 User消息+1 Assistant消息。
- daily交易/Note已经包含AI最终产生的业务记录，不因Applied又加一遍。daily Note代表可见保留产出，另乘1.1计软删除保留；这项口径不等于“每日所有曾创建Note”的统计。
- 每个Proposal保留creationPayloadJson和当前payloadJson两份；基线只有Applied产生Operation，再保留frozenPayloadJson第三份。非Applied暂按未确认/丢弃，Pending/Rejected确认导致的额外Operation另作敏感性。
- 普通Answer的resultJson为null，不把回复正文重复计入基线。ProposalReady消息仅按Proposal数增加小信封，假设原始128B；Applied Operation结果假设原始256B。两者为容量参数，不是读取了真实用户载荷。
- 1账户、9分类、1个EDITING草稿；session title32B、receiverIdentity64B；13位毫秒时间、规范36字符UUID、version/revision常量。record_data_state与ai_control各1行但不进入12段JSONL；控制行OFF、receipt为null。长期数字/标题/载荷变化不可能被这些均值精确预测。
- JSON元数据用真实字段名/顺序和具体样本值编码，包含LF。嵌入payload按**JSON字符串**再转义，不误作内联对象；payload内容用等长ASCII形状，普通正文按指定UTF-8字节数。元数据贡献含身份/时间/信封/字段名/引号/转义，不只是键名；没有估计ZIP压缩熵。

## 2. 十二个基线情景

“物理估计”是完整14表/索引的host形状校准区间，不是Android实测或硬上界，方法见§4。现有限额仍为256MiB总JSONL、128MiB单段、500,000总行；ZIP64MiB单独待测。

| 档位 | 年 | 导出总行 | JSONL MiB | 紧凑物理估计 MiB | 模型中已超过的逻辑限额 |
|---|---:|---:|---:|---:|---|
| 轻 | 1 | 740 | 0.301 | 0.50–0.78 | 无 |
| 轻 | 3 | 2,193 | 0.895 | 1.11–1.93 | 无 |
| 轻 | 5 | 3,647 | 1.492 | 1.72–3.10 | 无 |
| 普通 | 1 | 5,484 | 4.418 | 4.47–8.29 | 无 |
| 普通 | 3 | 16,426 | 13.244 | 13.00–24.47 | 无 |
| 普通 | 5 | 27,369 | 22.071 | 21.54–40.64 | 无 |
| 重 | 1 | 24,769 | 42.121 | 39.07–73.41 | 无 |
| 重 | 3 | 74,282 | 126.351 | 116.83–219.84 | 无 |
| 重 | 5 | 123,795 | 210.572 | 194.57–366.25 | 无 |
| 极端 | 1 | 110,132 | 398.823 | 330.91–621.99 | 总量、Proposal单段 |
| 极端 | 3 | 330,373 | 1,196.465 | 992.35–1,865.57 | 总量、Note/Proposal/Operation/Message单段 |
| 极端 | 5 | 550,614 | 1,994.107 | 1,653.79–3,109.16 | 同上及总行数 |

“无”仅表示这些模型数未超过相应三项，不表示形成过合法ZIP，更不表示完整备份验收PASS。极端一档是每天30次AI轮次、较高提案率且保留多年全部历史的反例情景；不能拿它代表普通用户。

## 3. 普通/重度5年的逐表贡献与临界

| 表 | 普通5年行数 | 普通JSONL MiB | 重5年行数 | 重JSONL MiB | 重度贡献 |
|---|---:|---:|---:|---:|---:|
| account | 1 | .0001 | 1 | .0001 | <.01% |
| category | 9 | .0013 | 9 | .0013 | <.01% |
| record_transaction | 7,300 | 2.708 | 21,900 | 9.795 | 4.65% |
| note（含软删除） | 2,008 | 2.277 | 8,030 | 32.629 | 15.50% |
| transaction_note_link | 1,825 | .183 | 10,950 | 1.096 | .52% |
| native_finance_draft（EDITING） | 1 | .0005 | 1 | .0005 | <.01% |
| change_proposal | 730 | 6.156 | 5,110 | 83.009 | 39.42% |
| commit_operation | 438 | 2.024 | 3,577 | 30.504 | 14.49% |
| agent_session | 457 | .081 | 1,217 | .216 | .10% |
| agent_run | 3,650 | 1.894 | 14,600 | 7.588 | 3.60% |
| agent_message | 7,300 | 6.281 | 29,200 | 42.000 | 19.95% |
| agent_message_reference | 3,650 | .466 | 29,200 | 3.732 | 1.77% |
| record_data_state（不导出） | 1 | 0 | 1 | 0 | 0 |
| ai_control（不导出） | 1 | 0 | 1 | 0 | 0 |

完整精确字节在JSON中，表内MiB经过取整。普通5年的三份payload原始重复总量7.414MiB；重度为107.789MiB，尚未加外层JSON/元数据。由此可见，AI“turn数量×提案率×平均payload×保留份数”是主要容量参数；只按交易/Note数量推整库会漏掉一半左右的重度体积。

普通5年模型距256MiB尚有233.929MiB，重度仅45.428MiB。按相同均值机械外推，**重度首次超总量为第2,219天（约6.0795年）**，前一天268,368,302B、当天268,491,444B；单Proposal段首次超128MiB约7.7096年，总量先到。普通首次超总量为第21,171天（约58.0027年），这种远期结果只说明本组假设距当前上限较远，不是58年产品承诺。轻档先到行数约688年同样没有预测价值；极端总量第235天已超。

明确未采用的5年敏感性与原假设并列：

| 额外假设 | 普通 JSONL MiB | 重 JSONL MiB | 含义 |
|---|---:|---:|---|
| 原基线 | 22.071 | 210.572 | 非Applied假设未确认，无额外Operation；Answer.resultJson=null |
| 所有Proposal都有Operation | 23.341 | 223.227 | 非Applied增加Rejected形状/frozen副本，不冒充实际确认率 |
| 所有普通Answer resultJson再复制回复正文 | 24.964 | 229.243 | 当前普通Answer并非如此，仅展示误计正文复制的敏感度 |

两种敏感性独立计算，没有偷偷替换基线。若还有更多Pending/Rejected、长上下文、较长信封/多Operation结果或不同保留行为，需显式给参数再算；不能从当前均值证明所有真实分布都安全。17×7.4M memo是正式限额压力夹具，不能作为这组日常平均载荷。

## 4. Host SQLite物理与WAL形状校准

实际使用host Python SQLite **3.50.4**；不是Android系统3.39.2或bundled3.50.1。四档分别对每个增长表插入256与1,024条，共8个新DB。固定Schema v1完整DDL、显式与隐式索引、Room identity和user_version均由导出文件建立；没有模拟另一套简化Schema。

**FK关闭只为独立表形状校准**，样本不宣称跨表引用/状态合法，不做Room恢复或任何用户数据操作。当前host无dbstat模块，因此逐表插入前后用page_count×4096的新增分配量归属该表及所有索引；JSON保存索引清单。空完整Schema本身204,800B。此法不能把每个索引单独归因，也不是当前正式库的文件大小。

区间方法：对两种样本取得新增B/row的最小与最大，以 `.8×最小` 至 `1.5×最大` 外推，再加固定Schema底座。系数是公开的工程敏感性范围，**不是统计置信区间或文件硬上界**；UUID插入顺序、Btree填充/分裂、overflow页、索引、碎片/freelist、版本、历史和超长字段会改变结果。固定小表的少量页由Schema底座覆盖，较大的草稿/receipt不在基线中。

额外小事务样本：同一host DB在WAL模式、自动checkpoint关闭情况下，每种做8次提交；每种开始前checkpoint归零。它只说明页/索引写放大，不外推WAL会按年累积，也没有持有导出reader。

| 事务形状 | 普通档每提交WAL增量范围 B | 重档范围 B |
|---|---:|---:|
| 更新1 Note | 4,120–4,152 | 8,240–16,512 |
| 更新1交易金额/version | 4,120–4,152 | 4,120–4,152 |
| 新1 Run+2 Message | 45,320–103,000 | 65,920–94,760 |
| 新1 Proposal（2份payload） | 37,080–61,800 | 45,320–78,280 |
| 新1 Operation（1份frozen） | 20,600–24,752 | 24,720–28,872 |

WAL最小页帧为4096+24B，首帧还含32B头。实际导出期间的WAL峰值仍取决于事务模式、checkpoint、读快照时长与写入分布，不能由这些8次host样本承诺Android峰值或证明384MiB预算合理。

## 5. 实际验证与边界

- 完整脚本成功生成12个基线、8个5年敏感性、4档逐日首次越界结果、8个完整DDL/index形状DB与逐事务WAL数据。
- 额外独立核对76个样本：SQLite `json_object`按真实字段顺序、参数类型生成的字节与模型compact JSONL逐字节相等，覆盖双重JSON转义/NULL/不同Message等。这验证本模型使用的字母表/形状，不冒充Kotlin业务validator或所有Unicode路径验收。
- 手工公式独立核对普通5年交易7,300、Note2,008、Run3,650、Message7,300、Proposal730、Operation438、Session457、Link1,825、Reference3,650及payload副本总量；全部12情景的逐表行数/字节与总数相等。
- Python执行通过，文档/脚本空白检查通过。校准文件和JSON仅在忽略build输出；无设备、生产库、Schema迁移、限额/Accepted状态改动、Commit或发布。

下一步若要裁决轻量化架构，应用普通5年与重5年的**全历史结构**另做备份原型，和上限/大字段夹具分别报告。模型本身不授权降低规模、不取代当前63条验收，也不把“普通情景较小”当作完整备份已完成。

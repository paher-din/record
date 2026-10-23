# Record 2.0 Engineering Policy

2026-10-23。适用于Record 2.0；统一切换条件见§7。产品任务按其具体授权执行。

## 1. 权威与工程选择

Clean Rebuild First, Selective Reuse When Justified。产品依据为[冻结架构R1](../architecture/record2-v1-unified-architecture.md)、[Spec](../specs/record2/v1/spec.md)和负责人明确决定；[Plan](../specs/record2/v1/plan.md)拥有依赖/范围，[来源基线](../product/record2-v1-decision-baseline.md)拥有确认材料摘录和取得限制。

旧七模块、Repository/EntityRef/Schema、导航/UI/Vault、构建/测试/版本门禁不自动继承。按业务包和简单接缝起步，仅为真实依赖/构建/维护收益拆模块。UI和Agent共用Application Services，UI不绕过业务层，Koog留适配边界。

## 2. 主Agent负责，协作按需

Main Agent Capable, Subagents Optional。主Agent可直接完成授权的调查/设计/实现/调试/验证/集成。只有独立产出、并行收益或有价值的审查才委派，并遵守用户/运行环境限制；没有协作不造派发合同，不按固定人数或失败次数接手。

并行只交代目标/范围、owner、必要依赖/约束与验证。共享文件、未稳定接口、Schema/迁移/恢复、同一库/设备等冲突写要协调串行或隔离后集成。独立审查按风险选择，真实标注自查/独立审查，结果由差异和实际验证证明，不以Agent自报PASS代替。

## 3. 任务分级和状态

| 级别 | 最少约定与记录 |
|---|---|
| Simple Change | 既有行为/设计内局部文档/代码修复，明确目标/范围和相关验证；无需新Spec/Plan/Verification/ADR或active状态 |
| Feature Task | 可界定功能/复杂修复，短行为/边界/依赖/权限/测试合同，可沿现有Major Plan任务，不每项创建三文档 |
| Major Change | 新产品、长期架构/正式模型/恢复/兼容/安全边界等重大决定保留正式Spec/Plan及明确批准；一次批准可同时覆盖两者，记录一次来源 |

任务规模与风险独立：一行金额/CAS修复也需对应高风险验证。现有R2 V1仍是Major，其批准、全部任务依赖和验收保留。

Major各自维护draft/approved/in-progress/completed，不以全仓唯一in-progress作互斥锁。多个独立Major能否并行取决于写入/契约/资源冲突；同一Spec内多任务无需另建Spec。index只是来源/当前焦点，不复制批准状态。文档/设计按具体授权执行，不以功能激活为前提；没有执行授权不自动激活任何Spec。

## 4. 任务授权、红线和UI决定权

明确授权覆盖范围内必要的正常修改/调试及已授权验证，不逐文件/补丁/复测重复申请。稳定产品/公开契约/正式模型/重大依赖/已批UI变化或越界动作升级审核。构建/缓存/依赖、设备/包/库、Provider/费用/Key/用户数据/恢复分别匹配实际对象和批准范围；计划写了测试不等于获准运行。

个人红线保留：删除文件/目录/Git历史；修改环境文件/真实秘密凭据/CI；Schema变更或迁移；公开发布/生产部署，先说明理由/风险取得具体同意。任务独有临时文件的删除也须有明确范围许可，不覆盖旧数据或丢弃他人修改。

所有最终UI/布局/信息层级、图标/视觉/字体配色间距、角色表情、动画及未确认交互归负责人。D-FIN/D-NOTE/D-TIME/D-AI/D-SAFE/D-SYS保留`DESIGN-OWNER：待负责人指导与确认`，方向→候选→调整→明确批准→实现，可逐组与非视觉工作并行，默认Material不是验收替代品。

## 5. T0～T4与完成证据

| 层级 | 适用证明 |
|---|---|
| T0 | 差异、文档/格式/静态和适用边界检查；普通文档不启动Android |
| T1 | 受影响parser/金额/日期/算法/状态的单元、边界和回归 |
| T2 | 正式Room磁盘/Compose/Android平台真实集成与重开/用户行为 |
| T3 | 金额/CAS/并发/A-B/取消/进程终止/未知提交/备份恢复/授权Key与故障证明 |
| T4 | 最终正式版本完整V1联合验收，按获批环境使用真实Room/Koog/DeepSeek |

按影响组合层级，变更单元完成跑相关快检，交付前跑必要组合；通过后只有新变化/失败/风险才追加重跑。相同fixture/原证据可覆盖多AC，但断言不删、历史C1/旧产品PASS不挪用。完整V1仍有B-AC/BK/SD-P/AI-AC/TP/CTX/SES共63条映射、六联合旅程和六设计组。

普通任务记录命令/结果/必要环境与风险；高风险保留可复查runner、独立事实比较、必要身份/版本/代际和PID。编译/截图/fake只补充，不能证明数据安全；备份自动往返未过不完成。API34隔离优先、API36/真机/真实Provider按批准任务门禁；缺CLI只阻相应操作，不判整个环境不可用。

## 6. 数据、失败、记录与工具

Long整数分、业务/关联version CAS、Proposal revision CAS/创建幂等、单Session有效Run/活跃Proposal、不可变Operation、原生确认/A-B原子与回滚/幂等、Room权威历史、Interrupted及跨代际Pending隔离等结果以冻结合同为准。Agent只prepare六类操作，不开放commit/删除/恢复/任意SQL文件网络系统命令。

默认本地；完整AI由有效Provider授权在主动任务按需处理可见数据，不默认整库/后台扫描；无AI不推理/请求/Agent外发。Keystore保护Key且不入源码/Prompt/普通库/日志/任何备份迁移。完整ZIP保留会话/隐藏关系/一致结果，验证预检/原子恢复/回退；不自动继承旧格式/阈值、AppLock/诊断产品或旧网络全禁令。

只维护任务目标、状态、验证和下一步/阻塞，并行补owner，真实故障重试才编号；不为普通任务造完整Attempt Ledger/双验收状态机或每次全仓hash。失败按根因处理、保留事实，只阻依赖它的部分；不得改验收掩盖失败。

ADR用于长期架构/持久兼容/恢复/安全/核心不变量决定；原方案内bug、内部整理和harness修复不自动写ADR或重批整份计划。权威事实一处拥有，其他地方引用，历史不改写。

保护脏状态/未跟踪内容，暂存/Commit/Push按具体权限且只含任务差异，worktree按需要。Skills按相关性/版本/副作用选择，不自动带来安装、tracker发布、Commit、固定TDD/多Agent或UI决定权；明确调用和更高优先级要求仍遵循。

## 7. 生效与适用范围

本规则规定R2工程执行方法，不改冻结产品、安全/AC、真实业务依赖及既有批准；Record1旧workflow/conventions/testing/roadmap/检查仅属旧工程或明确参考。

统一切换须具备：负责人对最终采用Diff、Git纳管/Commit和生效的明确授权；入口/Policy/Spec/Plan一致且产品无损；最小资料集合可从实际提交对象重建；工作区保护检查通过。全部满足后一次记录生效，编辑或提交部分文件不分别生效。最终提交身份与验证结果由Git日志和交付记录证明，不要求提交SHA自引用。治理生效不启动A0/B0/F0或产品任务；G3机器约束隔离及G5开发另按任务授权。

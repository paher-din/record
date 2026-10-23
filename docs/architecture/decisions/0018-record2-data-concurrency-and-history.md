# ADR 0018：R2统一业务版本与独立提交历史

**Status: Accepted**；2026-10-23；仅适用于Record 2.0。负责人接受修订版并授权Schema v1实施，唯一批准来源与具体执行边界由[V1 Plan §20](../../specs/record2/v1/plan.md#20-schema-v1实施授权与执行)拥有；不扩展至产品业务、迁移或恢复。字段、状态和验证映射由[B0审查包](../record2-b0-data-model-design.md)拥有；不修改R1 ADR。

建议Transaction/Note各用一个业务version，关联增减也递增两端version：牺牲字段与关联并行修改的通过率，换取所有调用者采用同一CAS规则，降低自动保存、软删除和AI提案漏检变化的风险。拆分字段/关系版本向量可减少冲突，但目前没有足够冲突证据支持增加统一比较/备份/恢复复杂度。

建议CommitOperation独立保存冻结输入与Applied结果，对Proposal/Session/Run不建生命周期外键，一份Proposal一生只绑定一个Operation。[Transaction A](../record2-b0-data-model-design.md#52-transaction-a)先核查既有Operation，同确认revision/展示摘要在Pending、Applied和两类Rejected均返回原身份及当前结果，异内容拒绝；详细算法只由B0拥有。Rejected后新用户任务走新Run/创建身份、新提案和新确认。

这样删聊天或关闭AI仍能核查已确认提交；隐私代价是保留核查必需的冻结业务材料，可能包含确认过的Note正文或交易说明。F0须披露删除会话不会抹去这些依据；不能借此保留整段聊天、隐藏推理、模型Trace或无关快照。级联删除会丢幂等依据，永久保留完整聊天又超出必要范围，均不采用。

新本地数据代际隔离旧执行资格，Operation保留不可变createdGeneration，ZIP导入不将旧Pending改成当前可执行操作。此边界继承冻结架构；具体ZIP/原子恢复协议仍由G0审核，不在本ADR提前选择旧Vault或新多代框架。

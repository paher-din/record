# Record workspace operating contract

Record 2.0 follows [docs/index.md](docs/index.md), its [Engineering Policy](docs/engineering/record2-engineering-contract.md), the frozen product contracts and the explicitly authorized task. Read only the sources relevant to that work.

Record 1.0 rules in [AGENTS.record1.md](AGENTS.record1.md) apply only to explicitly scoped legacy work. Its code, data and history remain protected; no old module/Schema/navigation/Vault/check is inherited by default.

## Shared boundaries

- 默认中文沟通；代码、命令和标识符用英文。
- Preserve user modifications, untracked files, data and Git history; scope changes to the authorized task.
- Explain reason/risk and obtain consent before deleting files/directories/history; changing environment files, secrets/tokens or CI; changing schemas or migrating data; or publishing/deploying publicly.
- Secrets/private user data stay out of source, logs, fixtures and commits.
- UI/visual/icons/layout/AI-role/animation and unconfirmed interaction decisions belong to the owner; implementation waits for the relevant design approval.
- Use apply_patch for text edits; verify proportionally and report actual results. Agent claims, compilation or screenshots do not prove data integrity.

## Task execution

The main Agent may deliver end-to-end; delegate only when useful and allowed. Coordinate owners for conflicting writes; use risk-based validation and review.

Use Simple/Feature/Major rules in the Policy. Major implementation needs the applicable approved Spec/Plan, design and execution permission; Simple/Feature do not automatically create new lifecycle documents. Active is a state of each authorized Major, not a whole-repository mutex. Shared contracts/resources still require conflict control.

Engineering reform does not authorize product work, schema/device/API/credential/data/release operations or change frozen acceptance. Record task/approval facts once at their owner, keep history separate, and finish the authorized work without manufactured checkpoints.

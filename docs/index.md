# Documentation router

Read [AGENTS.md](../AGENTS.md) and [docs/index.md](index.md) for every task.

| Task | Required documents |
| Any task | AGENTS.md, docs/index.md |
| Product scope | product/vision.md, product/mvp-requirements.md, active spec |
| Navigation or interaction | experience/information-architecture.md, experience/interaction-rules.md, active spec |
| Domain or finance | architecture/domain-model.md, engineering/testing-strategy.md, active spec |
| Room, files, search, backup, migration | architecture/data-and-storage.md, relevant ADR, engineering/testing-strategy.md, active spec |
| Module or dependency change | architecture/overview.md, relevant ADR, engineering/conventions.md, active spec |
| Security or diagnostics | engineering/security-and-privacy.md, active spec |
| Open-source reuse or license review | core conclusion, then research report evidence |

## Reading rules

- One active spec rule: exactly one approved implementation spec may be active under `docs/specs/*` at a time.
- Current status: no active implementation spec; the next design cycle is Tech PoC P0.
- Read links relatively; target files may be created later in the documentation plan.

## Fact ownership

Each decision belongs to the core document for its owning domain. Downstream documents should reference that source instead of duplicating or silently contradicting its facts.

## Conflict resolution

Resolve conflicts in this order: identify the owning domain, update the owning source, add or supersede an ADR if needed, update specs/plans, then code/tests.

The approved design spec becomes historical after this documentation plan completes. Its current repository path is [the approved design spec](superpowers/specs/2026-08-04-codex-first-android-record-app-design.md).

# Documentation router

Read [AGENTS.md](../AGENTS.md) and [docs/index.md](index.md) for every task.

| Task | Required documents |
|---|---|
| Any task | AGENTS.md, docs/index.md |
| Agentic execution (splitting, dispatch, acceptance) | engineering/agentic-workflow.md, active spec if one exists |
| Product scope | product/vision.md, product/mvp-requirements.md, active spec if one exists |
| Navigation or interaction | experience/information-architecture.md, experience/interaction-rules.md, active spec if one exists |
| Domain or finance | architecture/domain-model.md, engineering/testing-strategy.md, active spec if one exists |
| Room, files, search, backup, migration | architecture/data-and-storage.md, relevant ADR, engineering/testing-strategy.md, active spec if one exists |
| Module or dependency change | architecture/overview.md, relevant ADR, engineering/conventions.md, active spec if one exists |
| Security or diagnostics | engineering/security-and-privacy.md, active spec if one exists |
| Open-source reuse or license review | core conclusion, then research report evidence |

## Reading rules

- Active means exactly the unique `in-progress` spec under `docs/specs/*`. An `approved` spec is not active, and the project may have no active spec.
- Current active spec: none — [MVP M1 — app shell](specs/mvp/m1-app-shell/spec.md) is `completed` (2026-09-04): all tasks (T00, S01, T01–T11) are accepted with raw-evidence records in its [verification.md](specs/mvp/m1-app-shell/verification.md) and all 19 acceptance criteria PASS; [ADR 0016](architecture/decisions/0016-application-facing-startup-gate-handoff.md) is accepted and owning core documents are synchronized. M2 finance core starts only under its own approved spec and plan. Tech PoC P6 — timeline performance is `completed` (2026-08-28, [spec](specs/tech-poc/p6-timeline-performance/spec.md); ADR 0011–0015 accepted). Tech PoC P5 — export/restore is `completed` (2026-08-16, [spec](specs/tech-poc/p5-export-restore/spec.md); ADR 0010 accepted). Tech PoC P0–P4 are `completed`.
- A candidate sits outside the formal lifecycle. It may create only a draft `spec.md`; it cannot create a plan or authorize code, schema, interface, migration, route, dependency, or module work.
- A Codex-generated or reviewed plan is not approved. The user must explicitly approve it, and the approval date/context must be recorded in plan metadata or the spec status record before the spec can become `in-progress`.
- Read links relatively. During lifecycle work, read the target draft or approved spec even though it is not active.

## Fact ownership

Each decision belongs to the core document for its owning domain. Downstream documents should reference that source instead of duplicating or silently contradicting its facts.

## Conflict resolution

Resolve conflicts in this order: identify the owning domain, update the owning source, add or supersede an ADR if needed, update specs/plans, then code/tests. A failed PoC technical assumption or selection conclusion always requires a new ADR recording the failure evidence, rejected option, alternative decision, and impact before the same active slice can retry; a pure test-infrastructure failure is recorded and repaired without being treated as a PoC conclusion.

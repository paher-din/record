# Project operating contract

## Product boundary

- Native Android, minSdk 34, local-first.
- Current scope is Tech PoC, followed by MVP.
- V1/V2 ideas do not authorize code, schema, interfaces, or empty modules.

## Required reading

1. Read this file.
2. Read docs/index.md.
3. For Record 1.0 work, determine the active spec from its owning Spec/Plan records, then read that spec and the core documents it references. For lifecycle work, read the target draft or approved Record 1.0 spec instead.
4. Read the research report only for evidence or license review.

## Sources of truth

- Product: docs/product/*
- Architecture and data: docs/architecture/* and accepted ADRs
- User experience: docs/experience/*
- Engineering quality: docs/engineering/*
- Stage order: [docs/roadmap.md](docs/roadmap.md)
- Record 1.0 lifecycle: the owning Spec/Plan records under `docs/specs/tech-poc/` or `docs/specs/mvp/` determine status, approvals and the active `in-progress` spec. [docs/index.md](docs/index.md) is a navigation router, not an activity pointer. These lifecycle rules apply only to Record 1.0. Record 1.0 status: [MVP M2 — finance core](docs/specs/mvp/m2-finance-core/spec.md) is `approved` (2026-09-04) after delegated independent review; owning core documents are synchronized and [ADR 0017](docs/architecture/decisions/0017-m2-finance-schema-normalization-and-backup-v2.md) is accepted. M2 remains inactive until its plan is explicitly approved and schema/migration execution consent is recorded. [MVP M1 — app shell](docs/specs/mvp/m1-app-shell/spec.md) is `completed` (2026-09-04) with all 19 acceptance criteria PASS. Tech PoC P0–P6 are `completed`.
- Current Record 1.0 change when active: its unique `in-progress` spec under `docs/specs/tech-poc/` or `docs/specs/mvp/`; R2 activity does not participate in this legacy lifecycle.

## Non-negotiable data rules

- Money uses integer minor units; Float and Double are forbidden for amounts.
- Account balance must be reproducible from AccountEntry.
- Financial aggregate writes are atomic.
- Production destructive migration is forbidden.
- A backup feature is incomplete until automated round-trip restore passes.
- Timeline and SearchDocument are projections, never facts.

## Change protocol

- Update the owning core document before changing a stable decision.
- Add an ADR when the decision affects architecture, persistence, compatibility, or recovery.
- Resolve document conflicts explicitly before implementation.
- `active` means the unique `in-progress` spec; an `approved` spec is not active, and zero active specs is valid.
- A roadmap candidate authorizes only creation of a draft `spec.md`, not a plan or implementation work.
- Do not implement until the user has explicitly approved both the spec and the plan and the approval date/context is recorded in plan metadata or the spec status record.
- If a PoC technical assumption or selection conclusion fails, keep that slice `in-progress`, update the owning architecture facts, create the required evidence-bearing ADR, revise the same spec and plan, and obtain explicit user reapproval before retrying. A test-infrastructure failure is recorded and repaired without being treated as a PoC conclusion.

## Engineering discipline

- Feature modules do not depend on one another.
- UI does not access DAO, Room Entity, or physical attachment paths.
- Add Use Cases only for cross-aggregate atomicity, shared behavior, or central invariants.
- Preserve user changes and unrelated worktree state.
- Use apply_patch for text edits and run proportional verification after every change.
- Agentic work splits, dispatches, and verifies per the [agentic execution workflow](docs/engineering/agentic-workflow.md); the main agent accepts only against raw evidence, and a subagent reports blockers without hiding or relabeling them.

## Skills and Android verification

- Load task-relevant vendored skills by exact `skills/<name>/SKILL.md` path; [skills/README.md](skills/README.md) owns upstream pins and local Junction reconstruction. Resolve same-name skills explicitly and name explicit-only skills in the authorized task contract.
- Use the [Skills routing and adaptation](docs/engineering/agentic-workflow.md#skills-routing-and-adaptation) rules for design, planning, implementation, Android specialization, review, and retrospectives. Skills do not grant implementation approval or change the existing main-agent dispatch restriction.
- Preserve the current composition root, Navigation 2, SDK targets, module graph, Room/Repository, startup gate, and recovery contracts unless an approved change explicitly revises them. Report conflicting skill instructions before proposing changes; a skill's presence does not authorize Hilt, new libraries, abstractions, modules, or migrations.
- Default functional Android verification uses an isolated API 34 emulator; API 36 and physical-device runs supplement it per the [testing strategy](docs/engineering/testing-strategy.md#android-environments-and-capability-checks). Frozen spec and physical performance gates retain their approved environments.
- Android CLI is the preferred Agent device tool, not a prerequisite for every emulator test. Check CLI operations, SDK/ADB/AVD access, Gradle instrumentation, and physical profiling separately; missing one capability blocks only work that requires it. Record actual commands, target identity, results, and raw evidence; new UI needs observable user behavior beyond compilation or isolated rendering.

## User approval boundaries

- Ask before deleting files, directories, or Git history.
- Ask before modifying environment files, secrets, tokens, or CI/CD configuration.
- Ask before database schema changes or data migrations.
- Ask before publishing or deploying publicly.

## Definition of done

- Implementation work with an active spec must pass that unique `in-progress` spec's acceptance criteria.
- Lifecycle or documentation work, and other authorized work performed while no spec is active, must pass its approved plan or documented acceptance criteria.
- `none` active never authorizes functional implementation or bypasses the specification lifecycle; work that requires a spec cannot start or be declared done without the required active `in-progress` spec.
- Required automated checks pass.
- A verification record contains actual commands and results.
- Affected core documents and ADRs are synchronized.

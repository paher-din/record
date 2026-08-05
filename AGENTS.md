# Project operating contract

## Product boundary

- Native Android, minSdk 34, local-first.
- Current scope is Tech PoC, followed by MVP.
- V1/V2 ideas do not authorize code, schema, interfaces, or empty modules.

## Required reading

1. Read this file.
2. Read docs/index.md.
3. If an active spec exists, read the unique `in-progress` spec and only the core documents it references. For lifecycle work, read the target draft or approved spec instead.
4. Read the research report only for evidence or license review.

## Sources of truth

- Product: docs/product/*
- Architecture and data: docs/architecture/* and accepted ADRs
- User experience: docs/experience/*
- Engineering quality: docs/engineering/*
- Stage order: [docs/roadmap.md](docs/roadmap.md)
- Current active spec: Tech PoC P1 — minimal Room model (`in-progress` 2026-08-05: spec Revision 2 + ADR 0006 and plan Revision 7 both explicitly approved by the user)
- Current change when active: the unique `in-progress` spec under docs/specs/*

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

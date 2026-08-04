# Project operating contract

## Product boundary

- Native Android, minSdk 34, local-first.
- Current scope is Tech PoC, followed by MVP.
- V1/V2 ideas do not authorize code, schema, interfaces, or empty modules.

## Required reading

1. Read this file.
2. Read docs/index.md.
3. Read the unique active spec and only the core documents it references.
4. Read the research report only for evidence or license review.

## Sources of truth

- Product: docs/product/*
- Architecture and data: docs/architecture/* and accepted ADRs
- User experience: docs/experience/*
- Engineering quality: docs/engineering/*
- Stage order: docs/roadmap.md
- Current change: one approved spec under docs/specs/*

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
- Do not implement without an approved spec and plan.

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

- The active spec acceptance criteria pass.
- Required automated checks pass.
- A verification record contains actual commands and results.
- Affected core documents and ADRs are synchronized.

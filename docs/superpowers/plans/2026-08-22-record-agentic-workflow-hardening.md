# Record Agentic Workflow Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Status:** `completed` — the user explicitly approved approach B on 2026-08-22 and required the result to be specific to the Record project. Implementation and two-stage review completed on 2026-08-22.
- **Evidence:** [Subagent governance research](../../research/2026-08-22-subagent-governance.md)
- **Target:** [Agentic execution workflow](../../engineering/agentic-workflow.md)

**Goal:** Turn Record's short subagent guidance into a complete, auditable execution contract tied to this repository's spec lifecycle, verification evidence, shared worktree, approval boundaries, PoC failure protocol, and device runs.

**Architecture:** Keep `docs/engineering/agentic-workflow.md` as the single owner of agentic execution policy. Reorganize it around dispatch eligibility, a mandatory dispatch contract, ownership and dependency control, permissions, persisted state, evidence-based acceptance, failure/takeover, and lifecycle cleanup. Preserve Record-specific rules already proven during P6 instead of replacing them with vendor-generic terminology.

**Tech Stack:** Markdown documentation, Git worktree inspection, PowerShell verification.

---

### Task 1: Rewrite the Record agentic execution contract

**Files:**
- Modify: `docs/engineering/agentic-workflow.md`
- Reference: `AGENTS.md`
- Reference: `docs/index.md`
- Reference: `docs/specs/tech-poc/p6-timeline-performance/spec.md`
- Reference: `docs/specs/tech-poc/p6-timeline-performance/plan.md`
- Reference: `docs/specs/tech-poc/p6-timeline-performance/verification.md`
- Reference: `docs/research/2026-08-22-subagent-governance.md`

- [x] **Step 1: Preserve Record-owned invariants**

Retain the main-agent acceptance role, `verification.md` execution pointer ownership, raw-evidence rule, PoC-versus-infrastructure failure classification, per-task three-failure takeover, background polling for long device runs, and disk-based resume contract.

- [x] **Step 2: Add dispatch eligibility and concurrency rules**

Define a dispatchable task as bounded, independently verifiable, dependency-ready, and valuable enough to justify a separate context. Require sequential execution for shared stable interfaces, overlapping write scope, ordered gates, or tasks whose next action depends on the previous result. Cap spawned work at the runtime capacity and prefer the smallest useful number of agents.

- [x] **Step 3: Add the mandatory dispatch contract**

Require every handoff to name the task ID, goal, non-goals, acceptance rows, read/write scope, owner, dependencies, applicable Record documents, frozen decisions, existing dirty-worktree changes, validation commands, evidence artifacts, approval boundaries, blocker conditions, and return format.

- [x] **Step 4: Add shared-worktree ownership and permission rules**

Require one writer per file or owned slice, forbid reverting or staging unrelated work, and stop writes on overlap or contract conflict. Make Record's deletion, environment/secrets, CI/CD, database migration, and public-release redlines non-delegable; a subagent may not gain authority through nesting or tool availability.

- [x] **Step 5: Add state, evidence, retry, and lifecycle contracts**

Use `pending -> in_progress -> done | blocked | failed | cancelled`, distinguish worker `done` from main-agent `accepted`, define the minimum completion/blocker report, require a justified changed condition before retry, preserve the existing three-failure takeover, and require thread/process/device cleanup without deleting user material.

### Task 2: Verify project specificity and document integrity

**Files:**
- Verify: `docs/engineering/agentic-workflow.md`
- Verify: `docs/research/2026-08-22-subagent-governance.md`
- Verify: `docs/superpowers/plans/2026-08-22-record-agentic-workflow-hardening.md`

- [x] **Step 1: Check required Record concepts**

Run:

```powershell
Select-String -Path docs/engineering/agentic-workflow.md -Pattern 'in-progress|verification\.md|Execution pointer|acceptance|PoC|ADR|3 failures|device|dirty|schema|CI/CD'
```

Expected: every pattern is represented by at least one matching rule; no generic workflow may replace Record's lifecycle or approval vocabulary.

- [x] **Step 2: Check prohibited placeholders and whitespace**

Run:

```powershell
Select-String -Path docs/engineering/agentic-workflow.md -Pattern 'TBD|TODO|FIXME'
git diff --check -- docs/engineering/agentic-workflow.md docs/research/2026-08-22-subagent-governance.md docs/superpowers/plans/2026-08-22-record-agentic-workflow-hardening.md
```

Expected: placeholder search returns no matches; `git diff --check` exits 0 with no output.

- [x] **Step 3: Inspect the exact scoped diff and worktree state**

Run:

```powershell
git diff --no-index -- NUL docs/engineering/agentic-workflow.md
git status --short -- docs/engineering/agentic-workflow.md docs/research/2026-08-22-subagent-governance.md docs/superpowers/plans/2026-08-22-record-agentic-workflow-hardening.md
```

Expected: the workflow is a complete Record-specific contract; only the three in-scope documentation files are reported by the scoped status command.

## Verification record

- **Environment:** `D:\record`, branch `main`, shared dirty worktree; all three in-scope documents remain untracked. No Android build was run because the change is documentation-only.
- **Required concepts:** 16/16 patterns present, including Record lifecycle, `verification.md`, PoC/ADR failure handling, approval redlines, task revision/attempt identity, accepted dependency readiness, and dispatch failure accounting.
- **Document integrity:** `agentic-workflow.md` is 160 lines; placeholder matches `0`; trailing-whitespace matches across all three files `0`; all checked relative link targets exist.
- **Whitespace check:** `git diff --check` exited `0`. `git diff --no-index --check` reported only the repository's LF-to-CRLF conversion warning for each untracked file and no whitespace error.
- **Spec review:** `APPROVED` after fixing executor-neutral dependency readiness, `FAILED` report mapping, and approval-before-action blocking.
- **Quality review:** `APPROVED` after closing main/worker execution ownership, task-state versus dispatch-failure accounting, retry/revision/attempt transitions, report identity and acceptance-row mapping, permission fallback, and worker terminology.
- **Workflow SHA-256:** `9FBF89FD9C63237A592AE6CF68D3829ABFE20830CAF066EAEA57FFA2C2408DA6`.
- **Research SHA-256:** `A8719656D973E43B0E310DB3632A407904570D753C4C9143EDA4155DB1C9577F`.

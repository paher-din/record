# Agentic execution workflow

Rules for splitting, dispatching, executing, and accepting agentic work in Record. This document owns process only. Product, architecture, persistence, compatibility, recovery, user experience, and stage order remain owned by their core documents, accepted ADRs, and the [roadmap](../roadmap.md).

## Scope and roles

- The main agent owns plan decomposition, dependency order, task dispatch, write ownership, cross-task integration, and final acceptance against raw evidence. It maintains the active spec's `verification.md`, including the Execution pointer, Task log, and acceptance-criteria rows.
- The main agent does not write functional implementation. It may integrate non-overlapping task results, resolve cross-slice conflicts, and take over implementation only under the repeated-failure rule below.
- In this document, a **worker** is the subagent assigned to execute one task and its verification. A worker follows the dispatch contract, reports facts and raw evidence, and stops at a blocker instead of changing acceptance criteria, reclassifying a failure, or guessing.
- A worker operates in a shared, potentially dirty worktree. It does not own the repository, revert or overwrite another contributor's changes, stage or commit unrelated work, or tidy files outside its write scope.

Functional implementation may be dispatched only when all of these conditions hold:

- The spec and plan have both received explicit user approval.
- The approval date and context are recorded in plan metadata or the spec status record.
- The spec is the unique `in-progress` spec. An `approved` spec is not active, and zero active specs do not authorize implementation.
- The dispatched work stays inside the approved spec, plan, owning core documents, and accepted ADRs.

Lifecycle, documentation, research, diagnosis, and other non-implementation work follow their own authorization and acceptance criteria. They do not create implicit authority for product code, schema, interfaces, dependencies, or empty modules.

## Dispatch eligibility

The main agent dispatches a task only when it is:

- bounded by a specific goal, non-goals, and write scope;
- independently verifiable with named acceptance rows and raw evidence;
- dependency-ready, with the prerequisite result recorded on disk;
- scoped so one worker can execute it without hidden coordination; parallel execution is optional.

The main agent may keep bounded non-implementation work in the main thread when it is small or needs continuing shared reasoning. Authorized functional implementation that cannot be split or run concurrently is still dispatched serially to one worker. The main agent implements only after the repeated-failure takeover rule applies. Tasks that modify the same file or stable interface, depend on an unfinished result, or write the same verification artifact run serially. Final integration and combined gates also run serially.

The main agent uses the fewest agents that provide useful progress and never exceeds the current runtime capacity. When capacity is full, it waits or reschedules instead of spawning repeated copies. Nested delegation is allowed only when the dispatch contract authorizes it. Nested agents count against the same capacity, task boundary, permissions, and ownership rules, and the parent worker must include their work in its handoff.

## Mandatory dispatch contract

Every dispatch must contain a self-contained contract. Two tiers apply, chosen by the work's shape rather than its size.

A **lightweight dispatch** — a single writable slice, no concurrency with another owner, no shared stable interface, and no dependency on an unfinished result — carries the minimum contract:

- `task_id`, `task_revision`, current `attempt_id`, and a short title;
- `goal` and `non_goals`;
- the single `write_scope` and its owner;
- exact validation commands, required environment, and raw evidence paths or output fields;
- user-approval redlines and actions the worker must not take.

A **full contract** — required when a dispatch touches multiple writable slices, runs concurrently with another owner, modifies a shared stable interface, or depends on an unfinished result — adds to the lightweight fields:

- the exact acceptance-criteria rows the task supports;
- `read_scope` and the single owner of each writable path slice;
- `depends_on` and the on-disk evidence that each dependency is ready;
- the Record documents the worker must read, including the applicable `AGENTS.md`, `docs/index.md`, active spec and approved plan, relevant owning core documents and accepted ADRs;
- frozen decisions, environment bindings, and facts the worker may not revise;
- the known dirty-worktree state, other active owners, and the instruction not to revert or overwrite their work;
- expected changed files and other artifacts;
- blocker and escalation conditions, including when to stop rather than retry;
- the required completion-report format.

A worker must reject or request repair of a contract that lacks enough information to execute safely. Follow-up instructions cite the same `task_id`, `task_revision`, and `attempt_id` and state what changed. They cannot silently expand the goal, acceptance criteria, permissions, or write scope.

## Execution pointer

The unique `in-progress` spec's `verification.md` keeps an Execution pointer near the top with:

- current task, `task_id`, `task_revision`, and `attempt_id`;
- owner and writable path slices;
- dependencies and readiness evidence;
- task status and dispatch failure count;
- remaining steps and next command;
- `blocked-on` state;
- frozen environment and contract references;
- last raw evidence location;
- last-updated timestamp.

The main agent refreshes the pointer before each dispatch and after each handoff. It also updates the Task log and affected acceptance rows. Workers read these records but do not edit the Execution pointer, Task log, or acceptance rows. A fresh worker resumes from the on-disk pointer, Task log, acceptance rows, spec, plan, and dispatch contract rather than another agent's memory.

## Ownership and conflict control

- Each writable file or path slice has one owner at a time. Concurrent write scopes must not overlap.
- Tasks that touch the same file, stable interface, shared verification artifact, or unresolved dependency run serially even if their stated goals differ.
- A worker edits only its owned slice. It does not integrate another worker's changes or update shared contracts on that worker's behalf.
- If a worker finds task-external edits, an ownership collision, a frozen-decision conflict, or a required cross-slice change, it stops the related write, preserves the worktree, and reports the exact paths and conflict.
- The main agent resolves ownership and integration conflicts, then issues a revised contract if work can continue.

Workspace isolation can reduce file collisions, but it does not replace a single owner, explicit integration responsibility, or main-agent acceptance.

## Context and communication

- A dispatch never assumes that a worker inherited the main conversation, read prior files, invoked prior skills, or knows an unstated decision. The contract or its referenced on-disk records must carry every execution-critical fact.
- Resume the same worker when the task revision and acceptance contract are unchanged and its investigation history matters. Use a new worker for a new task revision, and give it a complete contract.
- Reports contain a concise result plus paths to raw evidence. Workers do not paste multi-minute logs into the handoff when a retained artifact or focused excerpt identifies the result.
- Direction sent to a running worker cites `task_id`, preserves its ownership boundary, and records any changed assumption. Ambiguous direction does not authorize new work.

## Permissions and approval redlines

When the runtime supports per-agent restrictions, the main agent must configure the minimum useful tools and permissions. Research, review, and evidence collection use a read-only role unless the contract grants a bounded write scope. If the runtime can only inherit parent permissions, those permissions remain the ceiling; the main agent compensates with the read-only role, contract write scope, ownership checks, and raw-evidence acceptance. Tool availability never authorizes behavior outside the task contract.

Delegation cannot authorize any Record approval redline. The main agent must explain the reason and risk, then obtain explicit user approval before any agent:

- deletes a file, directory, or Git history;
- modifies `.env`, secrets, tokens, or CI/CD configuration;
- changes a database schema or performs a data migration;
- publishes or deploys anything publicly.

If required user approval is not recorded, the agent does not execute the redline action. It returns `BLOCKED` before execution and reports the proposed action, reason, risk, and required user decision. Missing approval is a blocked condition, not a failed execution. A tool or runtime error after approval may be reported as `FAILED` with its raw evidence. An agent cannot skip the action and report the criterion as passed.

## Task state and evidence

Tasks use `pending -> in_progress -> done | blocked | failed | cancelled`. A downstream task enters `in_progress` only after every dependency task or result is complete with on-disk evidence and the main agent has reviewed the raw evidence and recorded an accepted readiness decision for that dependency. Each worker holds one task at a time.

`done` means the assigned executor, whether a worker or the takeover main agent, produced the required result and on-disk evidence. It does not mean the main agent accepted the result. Acceptance is recorded separately in `verification.md` after the main agent checks the evidence and combined repository state.

A retry or resume creates a new `attempt_id` and moves the task's prior terminal state back to `pending`. It returns to `in_progress` only after the main agent records the changed condition and ready evidence that justify the attempt.

Every worker report uses one of `DONE`, `DONE_WITH_CONCERNS`, `NEEDS_CONTEXT`, `BLOCKED`, or `FAILED` and includes:

- `task_id`, `task_revision`, `attempt_id`, overall report status, and a result summary;
- each supported acceptance row as `PASS`, `FAIL`, `BLOCKED`, or `UNVERIFIED`, with its raw evidence path;
- actual changed files and artifacts;
- each command run, its environment, exit status, and pass/fail result;
- raw output, test report, trace, XML, or other evidence paths;
- unverified risks and frozen-contract concerns;
- blockers, ownership conflicts, and the recommended next command.

The main agent maps worker reports to task state: `DONE` and `DONE_WITH_CONCERNS` become `done` and remain pending main-agent acceptance; `NEEDS_CONTEXT` and `BLOCKED` become `blocked`; `FAILED` becomes `failed`. `DONE_WITH_CONCERNS` cannot carry a failed outcome. If the main agent stops a worker or the worker cannot report, the main agent records `cancelled` with the last observable state and evidence.

The main agent does not accept a worker's self-reported PASS as evidence. It spot-checks raw evidence, including `git diff --check`, test XML fields such as `tests=... failures=...`, source scans, changed files, ownership boundaries, and the frozen contract. It then runs the required combined validation. An acceptance row becomes PASS only when the raw evidence supports it.

## Failure classification, revision, and retry

`blocked` means the worker lacks a ready dependency, permission, environment, ownership, or required decision. `failed` means execution completed but did not meet acceptance. `cancelled` means the attempt stopped without a completion report. Workers and the main agent preserve these task states, report the exact failing command and output when one exists, and never hide a failure, retry and mark it passed, or rename an outcome to avoid a gate.

The main agent alone classifies the evidence:

- If a PoC technical assumption or selection conclusion fails, the same slice stays `in-progress`. The main agent updates the owning architecture facts, creates the required evidence-bearing ADR, revises the same spec and plan, records the impact, and obtains explicit user reapproval before retrying. No later slice starts.
- A pure test-infrastructure or harness-mechanism failure is recorded and repaired in the same slice. It is not treated as a PoC conclusion and does not require an ADR. If the repair changes the frozen spec or plan contract, the normal revision and explicit approval rules still apply.

In both cases, the slice remains `in-progress` until its acceptance evidence passes, and no later slice starts.

Changing an implementation task's goal or acceptance contract requires the owning spec and plan to be revised and explicitly reapproved; the approved change creates a new `task_revision`. Authorized non-implementation work requires the corresponding approved plan or documented acceptance criteria to be revised before its goal or acceptance contract changes.

Before retrying, the main agent changes a condition supported by evidence, such as repairing infrastructure, supplying missing context, narrowing ownership, restoring the original worker, or serializing dependent work. It records that condition and ready evidence under a new `attempt_id`. It does not repeat the same dispatch under unchanged inputs and environment.

## Repeated-failure takeover

This rule is the exception to the main agent's non-implementation role.

- A **task** is one independent implementation and verification unit from the approved plan. `task_id` remains stable for that unit; `task_revision` identifies its approved goal and acceptance contract.
- A **dispatch failure** is an attempt that the main agent determines produced no usable acceptance result even though the dispatch was authorized and its prerequisites were ready. A worker-reported blocker, a user stop without usable evidence, or evidence that fails the main agent's spot-check can qualify.
- Task state and dispatch failure count are separate axes. Counting a `blocked` or `cancelled` outcome does not relabel the task `failed`; the main agent records both the actual state and its counting decision with evidence.
- Dispatch failures accumulate per `task_id` and `task_revision` across `attempt_id` values, including fresh workers. They reset only when that task revision is accepted complete or an approved revision changes its goal or acceptance contract.
- An unresolved external or user-approval blocker is not redispatched under unchanged conditions, so it cannot mechanically accumulate dispatch failures. Takeover does not remove an external blocker.
- When a task revision reaches **3 failures**, the main agent takes over its implementation and verification, stops dispatching that revision to workers, and tells the user that it took over and why.

## Long-running and device runs

- Multi-minute Gradle and device suites run in the background and are polled. Agents do not interrupt a healthy run to obtain quicker feedback.
- On Xiaomi/MIUI devices, `INSTALL_FAILED_USER_RESTRICTED` usually means the on-device USB install confirmation dialog was dismissed or timed out, not a code defect. Confirm the install on the device and rerun the same command. If `adb devices -l` is empty, run `adb kill-server` then `adb start-server` before relisting.
- An interrupted or timed-out device run leaves a stale test process that holds a lock and can hang the next `connectedDebugAndroidTest` for 10+ minutes. Run `adb shell am force-stop <test package>` before rerunning.
- Instrumented-test evidence does not survive the run: a library module's test APK is uninstalled after the suite, and `InstrumentationRegistry.targetContext` returns a redirected path unreachable via `run-as`. Emit evidence through instrumentation output (println to logcat or a retained result file), never through app-private files on `targetContext` paths.
- logcat truncates long lines at roughly 3KB mid-string and corrupts JSON. Keep evidence lines under 3KB or chunk at ~3000 chars (`...-CHUNK 1/N ...`) and reassemble on the host.
- A cancelled, timed-out, or externally interrupted run remains incomplete evidence. The worker reports its last observable state and retained artifact path. After any device intervention (force-stop, server restart, install confirmation), the worker records the intervention and re-verifies preconditions before rerunning.

## Lifecycle, observability, and persistence

The main agent persists `task_id`, `task_revision`, `attempt_id`, dispatch failure count, worker or role, owner scope, dependencies, task state, timestamps, last message, validation result, blocker, and acceptance decision. For an active spec, these records live in `verification.md`'s Execution pointer and Task log. Authorized documentation or lifecycle work with no active spec records them in its approved plan or task tracker. Running work must remain inspectable, directable, and stoppable by task and attempt identity.

After aggregation and acceptance, the main agent closes completed agent threads, stops task-owned background processes, and clears transient runtime state that does not contain user work. It does not delete files, directories, evidence, worktrees, or user materials without the required approval.

Task state, dispatch failure counts, the Task log, acceptance rows, and the Execution pointer stay on disk. Recovery never depends on a live agent's in-memory context.

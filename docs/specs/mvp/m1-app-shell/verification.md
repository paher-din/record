# MVP M1 — App Shell Verification

- **Spec:** [spec.md](spec.md) — `in-progress` from 2026-08-29.
- **Plan:** [plan.md](plan.md) — approved 2026-08-29.
- **Architecture:** [ADR 0016](../../../architecture/decisions/0016-application-facing-startup-gate-handoff.md) — accepted 2026-08-29.
- **Evidence rule:** a task's worker result is not acceptance; the main agent records acceptance only after raw-evidence and combined-state checks.

## Execution pointer

| Field | Value |
|---|---|
| Current task | M1-T01 — dependency/build baseline and benchmark package isolation |
| `task_revision` / `attempt_id` | 1 / `M1-T01-A1` |
| Owner / write scope | pending build-worker dispatch; `gradle/libs.versions.toml`, root and named module build files, benchmark target constant, serialization smoke test only |
| Dependencies / ready evidence | M1-T00 accepted; ADR 0016 accepted; five owning core documents synchronized; spec and plan approvals recorded |
| Status / dispatch failures | `pending` / 0 |
| Remaining steps | reference-device read-only preflight → failing serialization test → approved dependency delta → host gates → focused P6 benchmark regression → evidence acceptance |
| Next command | `C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe devices -l` |
| `blocked-on` | none; reference device state not yet checked for M1-T01 |
| Frozen environment | physical Xiaomi 14 Pro; `minSdk/targetSdk = 34`, `compileSdk = 36`, AGP 9.3.0, built-in Kotlin 2.2.10; no schema/migration change |
| Contract references | plan M1-T01; spec criteria 2, 14, 15, 17, 18; accepted ADR 0016 |
| Last raw evidence | M1-T00 commands/results below |
| Last updated | 2026-08-29 Asia/Shanghai |

## Acceptance criteria

| ID | Criterion | Status | Raw evidence |
|---:|---|---|---|
| 1 | Lifecycle and architecture prerequisite | PASS | ADR 0016 accepted; five core docs synchronized; spec/plan/router lifecycle scans and M1-T00 commands below |
| 2 | Module boundary | pending | none |
| 3 | Theme/localization/launch identity | pending | none |
| 4 | Accessibility | pending | none |
| 5 | Shared presentation | pending | none |
| 6 | Operation phases | pending | none |
| 7 | Default shell | pending | none |
| 8 | Global actions | pending | none |
| 9 | Typed/source-owned routing | pending | none |
| 10 | Chrome rules | pending | none |
| 11 | Independent retention | pending | none |
| 12 | Timeline date | pending | none |
| 13 | Safe startup | pending | none |
| 14 | Startup budgets and isolation | pending | none |
| 15 | Dependency review | pending | none |
| 16 | Offline routes | pending | none |
| 17 | Network/system-backup policy | pending | none |
| 18 | No premature domain work | pending | none |
| 19 | Verification record | pending | this file initialized; completion requires all actual task/device/artifact evidence |

## Task log

| Task | Revision | Attempt | Owner | State | Dispatch failures | Main-agent accepted | Evidence |
|---|---:|---|---|---|---:|---|---|
| M1-T00 | 1 | `M1-T00-A1` | main agent | done | 0 | yes | accepted ADR/core docs, lifecycle/link/schema/diff checks below |
| M1-T01 | 1 | `M1-T01-A1` | pending dispatch | pending | 0 | no | none |
| M1-T02–M1-T11 | 1 | not started | unassigned | pending | 0 | no | none |

## Commands and results

### M1-T00 — ADR/core-document synchronization and lifecycle activation

**Command:** targeted core-fact scan across ADR 0016, architecture overview, data/storage, interaction rules, conventions, security, spec, and plan; require `RecordStartup`, one startup engine, `RecoveryDetected`, legal `Phase.Checking → Phase.Running → Phase.Recovering`, synchronous-correctness wording, and no unqualified direct Checking-to-Recovering transition.

**Environment:** branch `main`; pre-activation HEAD `87679d5`; Windows/PowerShell; date 2026-08-29 Asia/Shanghai; documentation-only worktree.

**Result:** PASS — ADR 0016 is Accepted with explicit user context; every required owning fact is present; the forbidden direct transition scan is clean.

**Command:**

```powershell
git diff --name-only
git diff --exit-code -- core/data/schemas
git diff --check
```

**Environment:** same as above.

**Result:** PASS — only ADR/core/lifecycle documentation paths changed; Room schema directory has no diff; whitespace/error check passes.

**Command:** relative Markdown-link resolution for ADR/core/spec/plan/router files plus unique active-spec scan.

**Result:** PASS — all local links resolve; lifecycle sources agree that M1 is the unique `in-progress` spec after activation; no second active spec exists.

**Remaining risk:** reference-device availability and every functional/benchmark acceptance row remain unverified. No functional source, dependency, schema, migration, environment, secret, CI/CD, publication, or deletion action occurred in M1-T00.

## Deviations and remaining risk

- No plan deviation in M1-T00.
- The physical reference device was disconnected during plan drafting; M1-T01 must recheck it before any build-file change.
- The separate destructive approval for clearing `com.paherdin.record.benchmark` has not been requested or granted; it is required only at M1-T10.
- M1 remains incomplete; criteria 2–19 are pending except the initialized lifecycle record in criterion 1.

# Project roadmap

This roadmap defines the only authorized stage order for one-person, Codex-assisted development. Product behavior remains owned by the [MVP requirements](product/mvp-requirements.md), architecture gates by [data and storage](architecture/data-and-storage.md) and accepted ADRs, interaction behavior by the [interaction rules](experience/interaction-rules.md), and verification policy by the [testing strategy](engineering/testing-strategy.md).

## Current position

- Tech PoC P0 is `completed` (2026-08-05): the seven-module skeleton builds on the approved toolchain, the module dependency check is wired into `check` and passes its negative verification, and launch was verified on the Xiaomi 14 Pro reference device.
- Tech PoC P1 is `in-progress` (2026-08-05): the user accepted Revision 2 of its `spec.md` and ADR 0006 after review of commit `420f688`, and approved `plan.md` Revision 7 after review of commit `4272261`; both approvals are recorded. Implementation is authorized per that plan.
- Current active spec: Tech PoC P1 — minimal Room model. It is the project's unique active specification, and no later slice may start until it completes.
- Tech PoC P2 is the next candidate after P1. Like every roadmap item that has not entered the formal lifecycle, it has no implementation authorization merely because it appears below.
- A failed gate follows the same-slice revision and retry protocol below. The current slice remains the unique `in-progress` slice, and no later slice may start.

## Stage sequence

```text
Tech PoC: P0 engineering skeleton → P1 minimal Room model → P2 atomic transfer
→ P3 attachment failure recovery → P4 Chinese search → P5 export/restore
→ P6 timeline performance

MVP: M1 app shell → M2 finance core → M3 note core → M4 fusion relations
→ M5 retrieval and review → M6 data ownership → M7 hardening → M8 dogfood
```

MVP work starts only after P0–P6 are `completed`. Within each phase, slices run in the exact order shown and at most one specification is `in-progress`.

## Tech PoC

| Slice | Outcome | Measurable exit gate |
|---|---|---|
| **P0 — Engineering skeleton** | Establish an Android 14+ native project whose exact seven-module graph enforces the approved dependency direction. | The project builds and launches on the approved API 34 reference environment; automated dependency checks show exactly `:app`, three feature modules, and three core modules with no feature-to-feature or core-to-feature edge. |
| **P1 — Minimal Room model** | Prove the smallest Room 2.8.4 persistence model for `EntityRef`, `Transaction`, `AccountEntry`, `Note`, and the approved strong relations without turning projections into facts. | Instrumented tests persist and reload the minimal facts, enforce same-ID `EntityRef` XOR identity, foreign keys, typed endpoint rules, and atomic aggregate visibility, and confirm that Timeline/Search projections cannot originate writes. |
| **P2 — Atomic transfer** | Prove same-currency transfer and financial invariants as one indivisible durable operation. | Unit, property, and Room fault tests show exactly two non-zero entries on distinct same-currency accounts, algebraic sum zero, balances always reproducible from effective entries, and no one-sided effect at every injected failure boundary. |
| **P3 — Attachment failure recovery** | Prove validated image ingestion, content deduplication, retry-safe cleanup, and owner-level atomic visibility under interruption. | `ATTACHMENT_SOURCE_COPY` and `ATTACHMENT_CONTENT_COMMIT` evidence covers every required copy, validation/hash, atomic move, owner commit, deletion, relink, and cleanup fail point; no committed link references missing or unverified bytes, duplicate content is reused safely, and orphan cleanup is idempotent. |
| **P4 — Chinese search** | Select FTS4 `unicode61` or normalized paged `LIKE` using the frozen 10,000-record mixed Chinese/English corpus. | On the approved API 34 reference configuration, an eligible candidate returns 100% of every expected ID set in exact order and has first-page p95 at most 300 ms after 5 warmups and 30 measured runs with page size 50; only then apply the lower-CV rule at an absolute difference of at least 0.05, lower-p95 rule at a relative difference of at least 10%, and finally the paged-`LIKE` tie-break, with the result recorded by ADR. |
| **P5 — Export and restore** | Prove complete logical backup, overwrite restore, recovery, and explicit migration ownership without relying on CSV or Android system transfer. | Automated export → wipe/overwrite → restore → startup comparison matches every fact, relation, assignment, balance, attachment owner, size, and SHA-256; all binding ZIP entry/path/size/staging/ratio limits pass declared and streamed boundary tests; `BACKUP_STREAM_AND_PUBLISH`, `BACKUP_SAF_COPY`, `RESTORE_ACTIVATION`, and applicable `MIGRATION_RECOVERY` fault evidence passes; source manifest, merged manifest, final APK, API 34 extraction rules, and compatible rules exclude all app user data from cloud backup/device transfer and expose only explicit logical backup/restore as migration. |
| **P6 — Timeline performance** | Prove that the mixed transaction/note day timeline remains correct and usable at the approved reference load. | The recorded mixed-card Timeline fixture produces correct ordered cards with no ANR, OOM, or corrupt output and meets the P6 specification's pre-approved query-to-render, frame, memory, and jank budgets and regression allowance. P6 contains no thumbnail, aggregation, or startup benchmark. |

## MVP vertical slices

| Slice | Outcome | Measurable exit gate |
|---|---|---|
| **M1 — App shell** | Deliver the default Timeline shell, persistent bottom navigation in the exact order Timeline/Finance/Notes/Projects, application theme, typed navigation and global actions, retained tab state, the safe startup-gate boundary, and a shared accessible Compose presentation pattern for `loading`, `empty`, `error`, and `ready` states; these presentation states do not replace the operation `Phase.*` contract. | UI tests prove the four-tab shell and independent scroll/filter/date state retention, exact global `+` choices, source-owned routing, editor/progress visibility rules, and no generic Entry; representative screens switch through shared `loading`, `empty`, `error`, and `ready` presentations with accessible semantics and no domain-data dependency; theme tokens are applied consistently to the shell and shared states; the shell meets its approved healthy/pending startup budget; source/merged/final artifacts declare no `INTERNET` or unexpected network capability and enforce the approved Android cloud-backup/device-transfer exclusions; every M1 route works in network isolation. |
| **M2 — Finance core** | Deliver accounts with opening history, income, expense, same-currency transfer, an empty-capable first-level Category catalog, recomputable balances, and deterministic durable-success navigation. | Journeys 1–3 pass their validation, draft-retention, atomicity, and infrastructure-failure cases; Category passes offline empty picker → inline create → auto-select → parent save plus Settings create/rename/archive/restore, 1–40-code-point normalized uniqueness across active/archived items, archived-name Restore, failure retention, and no-hard-delete checks; archived Category cannot receive a new allocation while historical allocations remain intact; amount/allocation/transfer/opening invariants and property tests pass; no balance, history, Timeline, or statistics surface claims success before the complete durable commit. |
| **M3 — Note core** | Deliver basic Markdown notes, confirmed image drafts and attachments, and an empty-capable shared Tag catalog without introducing a required title. | Journey 4 and the note/image portions of Journeys 6–7 pass offline success, picker cancellation, invalid-image, retry, edit, and no-orphan cases; Tag passes empty selector → inline create → auto-select → parent save plus Settings create/rename/archive/restore, normalized active+archived uniqueness, historical `EntityTag` preservation/current-name rendering, failure retention, Transaction/Note-only endpoints, and no-hard-delete checks; body-or-confirmed-image validity is enforced; the thumbnail benchmark meets the M3 specification's approved decode/load, frame, memory, and cache budgets. |
| **M4 — Fusion relations** | Deliver bidirectional transaction–note linking, an empty-capable Project catalog, and zero-or-one primary project assignment while preserving typed domain ownership. | Journeys 5–6 pass from both saved Detail and Editor contexts; Project passes Projects-tab and inline create, active/archived lists, rename, recoverable archive/restore, normalized uniqueness, durable failure retention, no-hard-purge UI, and no-tag/no-attachment checks; archive prevents new assignment but preserves every existing primary assignment and archived historical Detail; many-to-many links are visible from both sides only after atomic completion, Editor selections remain draft-only until owner save, each Transaction/Note has at most one primary project, and failure leaves every prior relation and assignment unchanged. |
| **M5 — Retrieval and review** | Deliver the mixed day Timeline, P4-selected unified search, Projects review, and monthly income/expense/category statistics with archived catalog history. | Journeys 8–10 pass with exact source dispatch, retained scroll/filter/date/query state, stale-search suppression, correct zero/empty states, and statistics matching a pure reference calculation; historical surfaces show current renamed values and archived state for Category/Tag/Project, archived Category allocations remain included in aggregation, and Projects retains active/archived list mode and historical assignments; Timeline and aggregation benchmarks meet their approved M5 budgets, while search continues to satisfy the P4 correctness and 300 ms p95 gates. |
| **M6 — Data ownership** | Deliver complete logical ZIP backup/overwrite restore, separate transaction CSV, and shared text/image as unsaved typed drafts. | Journeys 11–13 pass offline, including complete round-trip equality, malicious-ZIP rejection with the current vault unchanged, modal cancellation races, blocked cross-start recovery, every P5 fault mapping, CSV exactly once per effective/non-soft-deleted Transaction—including each effective opening balance, income, expense, and transfer—with soft-deleted history excluded, CSV never inside the logical ZIP, soft-deleted history preserved and restored only by the complete logical ZIP, and no saved share or image before owner confirmation; recovery startup meets its approved M6 budget. |
| **M7 — Hardening** | Close migration, recovery, performance, access-control, diagnostics, dependency, and privacy risks for an MVP release candidate. | N-1/N-2 migrations and all required fault suites pass; App Lock defaults off, uses platform authentication, protects cold/foreground entry and task previews, and cannot bypass recovery; Diagnostic Export retains/exports only the three-field event envelope within the seven-UTC-day/1-MiB limits and never auto-shares or uploads; Timeline, thumbnail, aggregation, and healthy/pending/locked startup benchmarks pass; source/merged/final network and system-backup policy checks pass; Journeys 1–13 pass in airplane or otherwise network-isolated operation; release dependencies contain no ads, analytics, crash reporting, or closed-source tracking SDK. |
| **M8 — Dogfood** | Validate that the owner can rely on the complete MVP for 30 consecutive days of real use and recover safely from discovered defects. | A dated 30-day record has no gap, every discovered issue has verification evidence and no unresolved release blocker remains, the final complete backup → wipe/overwrite → restore comparison passes, all five critical Compose flows and required release suites pass, and there is no known data loss, non-recomputable balance, orphan/one-sided relation, missing attachment content, or unrestorable backup. |

Thumbnail performance belongs to M3, aggregation performance to M5, and startup performance to M1 plus the M6/M7 recovery and App Lock boundaries. All four benchmark families are rerun before MVP release, but only the mixed Timeline belongs to P6.

## Specification lifecycle

```text
draft → approved → in-progress → completed
```

`active` means exactly the unique `in-progress` specification. An `approved` specification is not active, the project may have no active specification, and across the entire project at most one specification may be `in-progress` at a time.

| State | Meaning and allowed work |
|---|---|
| `draft` | Behavior, boundaries, and acceptance are still being clarified. Implementation is forbidden. This is the only formal state a roadmap candidate may enter directly. |
| `approved` | The user has explicitly accepted `spec.md` and that approval is recorded. `plan.md` may be produced and reviewed, but the spec is not active and implementation has not started. |
| `in-progress` | The user has explicitly approved both `spec.md` and `plan.md`, both approval facts are recorded with date/context, and the slice is the project's single active executing specification. |
| `completed` | Acceptance and required gates pass, `verification.md` contains repeatable evidence, and no blocker remains. The directory becomes read-only history. |

### Slice document ownership

- `spec.md` owns required behavior, boundaries, and acceptance criteria.
- `plan.md` owns implementation sequence, expected file changes, and planned verification commands.
- `verification.md` owns commands actually executed, environments, results and measurements, deviations from the plan, and remaining risk.

A Codex-generated or reviewed plan is unapproved by default. A slice may move from `approved` to `in-progress` only after the user explicitly approves the plan and the approval date/context is recorded in plan metadata or a shared spec status record. It may move to `completed` only when its acceptance criteria and roadmap exit gate have verification evidence and no blocking risk remains. A later correction to completed work uses a new specification; a stable architecture, persistence, compatibility, recovery, or security decision change also adds or supersedes an ADR under the existing documentation-first rules. Completed specifications are never silently rewritten to make history match later code.

### PoC failure, revision, and retry

Failure does not move the current PoC slice backward or permit work to advance around it:

1. Keep the same slice as the project's unique `in-progress` spec. Do not mark it `completed`, move it back to `approved`, introduce a `superseded` state, or start the next slice.
2. Append the failed command, environment, result, deviation, and remaining risk to that slice's `verification.md`; preserve prior evidence rather than replacing it.
3. Classify the failure. If a technical assumption or selection conclusion failed, first update the owning architecture facts and create a new ADR containing the failure evidence, rejected option, alternative decision, and impact. If only the test fixture, harness, device setup, or other test infrastructure failed without disproving the technical conclusion, record and repair that infrastructure failure; it is not a failed PoC conclusion and does not by itself change architecture facts or require a decision ADR.
4. Revise the same `spec.md` and `plan.md`, with an explicit revision identifier and revision record. The user must explicitly reapprove the revised behavior and acceptance criteria and the revised plan. Record each reapproval with date/context in the relevant metadata or shared status record; Codex generation or review is not approval.
5. Retry through the same `in-progress` slice only after those required approvals and architecture/ADR updates are complete. The next slice remains blocked until the current slice passes its gate and becomes `completed`.

## Candidate boundary

A candidate is a roadmap item or later idea that has not entered the formal lifecycle. It may create only a draft `spec.md`, entering `draft`; it cannot skip directly to `approved` or `in-progress`, and it does not authorize a `plan.md`, code, schema fields, interfaces, dependencies, migrations, routes, or modules. Exploration or evidence-gathering may occur only when the existing facts explicitly permit it and does not count as implementation, approval, or lifecycle progress.

V1 and V2 ideas remain candidates under this same rule. Examples such as encryption, OCR/AI, recurring finance, arbitrary attachments, sync, multi-device use, collaboration, cross-currency transfer, or another client platform require a new product decision and the full specification lifecycle; architecture- or compatibility-changing candidates also require an ADR before implementation.

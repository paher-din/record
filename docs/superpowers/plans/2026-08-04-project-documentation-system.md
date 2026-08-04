# Project Documentation System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the approved Codex-first project documentation system that becomes the source of truth for Tech PoC and MVP development.

**Architecture:** Stable product, architecture, experience, and engineering documents own long-lived decisions; phase specifications own one executable slice at a time. `AGENTS.md` and `docs/index.md` route Codex to the minimum required context, while the approved design spec becomes a read-only historical input after the core documents are created.

**Tech Stack:** Markdown, PowerShell 7, Git

---

## File map

| File | Responsibility |
|---|---|
| `.gitignore` | Exclude IDE, visual-companion, Gradle, local SDK, and build outputs |
| `AGENTS.md` | Project-specific Codex operating contract |
| `README.md` | Human entry point and current project state |
| `docs/index.md` | Task-to-document reading router |
| `docs/product/vision.md` | Product purpose, user, non-goals, and success criteria |
| `docs/product/mvp-requirements.md` | MVP journeys, business rules, and acceptance criteria |
| `docs/architecture/overview.md` | Module boundaries, dependency direction, and application data flow |
| `docs/architecture/domain-model.md` | Entity model and financial invariants |
| `docs/architecture/data-and-storage.md` | Room, attachments, search, backup, restore, and migration design |
| `docs/architecture/decisions/0001-native-android-room-2.md` | Why the project uses native Android and Room 2.8.4 |
| `docs/architecture/decisions/0002-entityref-and-strong-relations.md` | Why domain entities remain separate and core relations are typed |
| `docs/architecture/decisions/0003-generation-based-restore.md` | Why restore uses validated staging generations |
| `docs/experience/information-architecture.md` | Persistent bottom navigation and top-level page structure |
| `docs/experience/interaction-rules.md` | Creation, editing, linking, failure, and recovery behavior |
| `docs/engineering/conventions.md` | Kotlin, Compose, module, state, and dependency rules |
| `docs/engineering/testing-strategy.md` | Risk-based test matrix and release gates |
| `docs/engineering/security-and-privacy.md` | Local-first threat boundary, permissions, logging, and diagnostics |
| `docs/roadmap.md` | Tech PoC and MVP slice sequence, entry gates, and exit gates |

The approved design remains at `docs/superpowers/specs/2026-08-04-codex-first-android-record-app-design.md`. The research report remains at `deep-research-report (6).md` and is evidence, not a daily source of truth.

### Task 1: Establish local version control and ignore generated state

**Files:**
- Create: `.gitignore`
- Track: `deep-research-report (6).md`
- Track: `docs/superpowers/specs/2026-08-04-codex-first-android-record-app-design.md`
- Track: `docs/superpowers/plans/2026-08-04-project-documentation-system.md`

- [ ] **Step 1: Initialize a local Git repository**

Run:

```powershell
git init -b main
```

Expected: Git reports an initialized repository under `D:\record\.git` and performs no network operation.

- [ ] **Step 2: Configure a repository-local commit identity only when none is available**

Run:

```powershell
if (-not (git config --get user.name)) { git config user.name "Codex" }
if (-not (git config --get user.email)) { git config user.email "codex@local" }
git config --get user.name
git config --get user.email
```

Expected: both commands print non-empty values. This changes only the new repository's local Git configuration.

- [ ] **Step 3: Create `.gitignore` with the exact generated-state policy**

Use `apply_patch` to create:

```gitignore
.idea/
.superpowers/
.gradle/
local.properties
**/build/
*.iml
.worktrees/
```

- [ ] **Step 4: Verify generated files are ignored and approved inputs remain trackable**

Run:

```powershell
git status --short --ignored
```

Expected: `.idea/`, `.superpowers/`, and `.worktrees/` are ignored; `.idea/` and `.superpowers/` appear with `!!`, while `.worktrees/` may not appear until created; the research report and `docs/` appear as untracked rather than ignored.

- [ ] **Step 5: Commit the approved research, design, and implementation plan**

Run:

```powershell
git add .gitignore "deep-research-report (6).md" docs/superpowers/specs/2026-08-04-codex-first-android-record-app-design.md docs/superpowers/plans/2026-08-04-project-documentation-system.md
git diff --cached --check
git commit -m "docs: record approved project design"
```

Expected: `git diff --cached --check` prints nothing; the commit succeeds locally.

### Task 2: Create the operating contract and document router

**Files:**
- Create: `AGENTS.md`
- Create: `README.md`
- Create: `docs/index.md`

- [ ] **Step 1: Create `AGENTS.md`**

Use `apply_patch`. The file must contain these sections and rules:

```markdown
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
```

- [ ] **Step 2: Create `README.md` as a portal, not a second specification**

Use this exact heading order:

```markdown
# Android 生活记录 App

One-sentence product statement: a private Android timeline connecting money with life context.

## Current status
- Documentation foundation
- Next gate: Tech PoC P0
- Supported baseline: Android 14+

## Start here
- Project rules: AGENTS.md
- Documentation router: docs/index.md
- Product vision: docs/product/vision.md
- Roadmap: docs/roadmap.md

## Historical inputs
- Approved design spec
- Deep research report
```

Link each item with a relative Markdown link. Use an angle-bracket link target for `deep-research-report (6).md` so spaces and parentheses render correctly. Do not duplicate architecture or MVP requirement prose in the README.

- [ ] **Step 3: Create `docs/index.md` as the reading router**

Include a table with these exact task routes:

| Task | Required documents |
|---|---|
| Any task | `AGENTS.md`, `docs/index.md` |
| Product scope | `product/vision.md`, `product/mvp-requirements.md`, active spec |
| Navigation or interaction | `experience/information-architecture.md`, `experience/interaction-rules.md`, active spec |
| Domain or finance | `architecture/domain-model.md`, `engineering/testing-strategy.md`, active spec |
| Room, files, search, backup, migration | `architecture/data-and-storage.md`, relevant ADR, `engineering/testing-strategy.md`, active spec |
| Module or dependency change | `architecture/overview.md`, relevant ADR, `engineering/conventions.md`, active spec |
| Security or diagnostics | `engineering/security-and-privacy.md`, active spec |
| Open-source reuse or license review | core conclusion, then the research report evidence |

Also include:

- one-active-spec rule;
- current status: no active implementation spec; the next design cycle is Tech PoC P0;
- fact ownership rule;
- conflict resolution order from design section 3.3;
- statement that the approved design spec becomes historical after this plan completes.

- [ ] **Step 4: Verify the portal files contain their required anchors**

Run:

```powershell
$checks = @(
  @{Path='AGENTS.md'; Pattern='## Required reading'},
  @{Path='AGENTS.md'; Pattern='## Non-negotiable data rules'},
  @{Path='README.md'; Pattern='## Start here'},
  @{Path='docs/index.md'; Pattern='one active spec|one-active-spec|唯一.*active spec'}
)
foreach ($check in $checks) {
  if (-not (Select-String -LiteralPath $check.Path -Pattern $check.Pattern)) { throw "Missing anchor in $($check.Path): $($check.Pattern)" }
}
'portal-anchors: PASS'
```

Expected: `portal-anchors: PASS`.

- [ ] **Step 5: Commit the operating contract and router**

Run:

```powershell
git add AGENTS.md README.md docs/index.md
git diff --cached --check
git commit -m "docs: add Codex operating contract"
```

Expected: clean whitespace check and a successful local commit.

### Task 3: Create product sources of truth

**Files:**
- Create: `docs/product/vision.md`
- Create: `docs/product/mvp-requirements.md`

- [ ] **Step 1: Create `docs/product/vision.md`**

Use this heading structure and content contract:

```markdown
# Product vision
## Core proposition
Connect “what I spent” with “what happened” in one local, private, portable timeline.
## Primary user
The owner of the app, using it daily on Android 14+ without a required account.
## Problem
Finance apps lose life context; note apps cannot enforce financial truth.
## Product principles
Timeline-first, domain-specific creation, local-first, recoverable, open export, no silent data loss.
## Success criterion
Thirty consecutive days of real use with reliable linking, search, export, wipe, and full restore.
## MVP goals
List the approved MVP capabilities from design section 2.2.
## Non-goals
List every exclusion from design section 2.3.
## Product claim boundary
State that the MVP is not full double-entry accounting and not a cloud synchronization product.
```

- [ ] **Step 2: Create `docs/product/mvp-requirements.md`**

Define these user journeys with observable acceptance criteria:

1. Create an account with an opening-balance transaction.
2. Record income and expense with one or more category allocations.
3. Transfer between two accounts in the same currency.
4. Create and edit a Markdown note.
5. Link multiple notes to one transaction and multiple transactions to one note.
6. Assign tags and at most one primary project.
7. Attach an image through Photo Picker or SAF.
8. Browse a mixed day timeline using the persistent bottom navigation.
9. Search transactions and notes from one entry point.
10. View monthly income, expense, and category statistics.
11. Export and restore a complete logical ZIP.
12. Export transaction CSV.
13. Receive shared text or image as an unsaved draft.

For each journey, specify preconditions, user action, success state, validation failure, and infrastructure failure. Include the 30-day dogfood criterion and release blockers from design section 9.2. Do not mention DAO, SQL, Gradle modules, or file implementation details.

- [ ] **Step 3: Verify product scope has no V1/V2 leakage**

Run:

```powershell
$requirements = Get-Content -LiteralPath 'docs/product/mvp-requirements.md' -Raw
$forbidden = @('OCR','AI 自动分类','实时同步','多人协作','跨币种转账')
$leaks = $forbidden | Where-Object { $requirements -match [regex]::Escape($_) }
if ($leaks) { throw "Out-of-scope items found in MVP requirements: $($leaks -join ', ')" }
'mvp-scope: PASS'
```

Expected: `mvp-scope: PASS`. Non-goals belong in `vision.md`, not in executable MVP journeys.

- [ ] **Step 4: Commit the product documents**

Run:

```powershell
git add docs/product
git diff --cached --check
git commit -m "docs: define product vision and MVP"
```

Expected: clean whitespace check and a successful local commit.

### Task 4: Create architecture and domain sources of truth

**Files:**
- Create: `docs/architecture/overview.md`
- Create: `docs/architecture/domain-model.md`

- [ ] **Step 1: Create `docs/architecture/overview.md`**

Include:

- the seven exact Gradle modules from design section 5.1;
- one responsibility paragraph per module;
- a Mermaid dependency diagram showing `:app` composing features and feature modules depending only on core modules;
- the prohibition on feature-to-feature dependencies;
- the UI → Event → ViewModel → Repository/Use Case → Room/file write flow;
- the Flow → Repository → StateFlow → Compose read flow;
- the three allowed reasons to create a Use Case;
- explicit bans on UI-to-DAO access, exposing Room entities to UI, and timeline writes to source domains;
- module-splitting thresholds: two consumers, stable public interface, independent tests, and reduced dependency surface.

- [ ] **Step 2: Create `docs/architecture/domain-model.md`**

Include a Mermaid ER diagram and define:

- `EntityRef`, `Transaction`, `AccountEntry`, `CategoryAllocation`, `Account`, `Category`, `Note`, `Project`, `TransactionNoteRelation`, `Tag`, `EntityTag`, `Attachment`, and `AttachmentLink`;
- one-to-one identity between `EntityRef` and Transaction/Note/Project;
- positive absolute `CategoryAllocation.amountMinor`;
- negative expense entries and positive income entries;
- exact two-entry, zero-sum same-currency transfer invariant;
- `OPENING_BALANCE` as a transaction with one account entry and no direct balance field;
- balance recomputation rule;
- atomic aggregate write rule;
- UUID string primary keys;
- soft-delete, archive, cascade, and attachment cleanup behavior;
- explicit deferral of generic `EntityRelation`, cross-currency, Task, Reminder, and sync metadata.

Do not include Room annotations or DAO method signatures; those belong to the first database implementation spec.

- [ ] **Step 3: Verify all non-negotiable invariants are present**

Run:

```powershell
$domain = Get-Content -LiteralPath 'docs/architecture/domain-model.md' -Raw
$patterns = @('integer|整数','Float','Double','OPENING_BALANCE','zero-sum|代数和为零','AccountEntry','EntityRef','TransactionNoteRelation','UUID','soft delete|软删除')
foreach ($pattern in $patterns) {
  if ($domain -notmatch $pattern) { throw "Missing domain invariant: $pattern" }
}
'domain-invariants: PASS'
```

Expected: `domain-invariants: PASS`.

- [ ] **Step 4: Commit architecture and domain documents**

Run:

```powershell
git add docs/architecture/overview.md docs/architecture/domain-model.md
git diff --cached --check
git commit -m "docs: define architecture and domain model"
```

Expected: clean whitespace check and a successful local commit.

### Task 5: Document storage reliability and accepted architecture decisions

**Files:**
- Create: `docs/architecture/data-and-storage.md`
- Create: `docs/architecture/decisions/0001-native-android-room-2.md`
- Create: `docs/architecture/decisions/0002-entityref-and-strong-relations.md`
- Create: `docs/architecture/decisions/0003-generation-based-restore.md`

- [ ] **Step 1: Create `docs/architecture/data-and-storage.md`**

Use these sections:

1. Room 2.8.4 baseline, schema export, explicit migrations, and destructive-migration ban.
2. Attachment state flow: temporary copy → validation → SHA-256 → atomic move → Room link transaction → orphan cleanup.
3. `SearchDocument` projection, same-transaction update, rebuild behavior, and FTS4-versus-LIKE PoC decision rule.
4. Logical backup ZIP layout with manifest, JSONL, attachments, checksums, and separate format/schema versions. `transactions.csv` is excluded from the ZIP and remains a separate independent export artifact.
5. Backup generation: stream and hash only ZIP JSONL and attachment payloads, write manifest last, then publish. Never include or hash `transactions.csv` as a ZIP payload.
6. Generation-based restore using staging, invariant validation, active-generation switch, rollback retention, and delayed cleanup.
7. Migration test rules covering N-1 and N-2.
8. Explicit exclusions: live SQLite/WAL sync, Auto Backup as primary promise, MVP SQLCipher, and network sync.

Include the exact storage convention `files/vaults/<generationId>/attachments/` and generation-specific database names.

- [ ] **Step 2: Create ADR 0001**

Use headings `Status`, `Context`, `Options considered`, `Decision`, and `Consequences`. Set status to `Accepted` and date to `2026-08-04`. Record:

- native Kotlin/Compose over Flutter, React Native, or a full-app Fork;
- Room 2.8.4 over Room 3 for MVP;
- Android 14+ as the compatibility floor;
- consequence: Android-first quality and reduced compatibility work, with iOS deferred.

- [ ] **Step 3: Create ADR 0002**

Set status to `Accepted` and record:

- separate Transaction, Note, and Project facts;
- shared `EntityRef` identity for tags and attachments;
- typed `TransactionNoteRelation` for the core cross-domain relationship;
- timeline and search as projections;
- generic `EntityRelation` deferred until a validated long-tail relationship exists.

- [ ] **Step 4: Create ADR 0003**

Set status to `Accepted` and record:

- restore into an isolated staging generation;
- validate checksums and domain invariants before activation;
- switch one active-generation pointer only after validation;
- retain the previous generation until successful startup;
- consequence: extra temporary storage in exchange for no partial restore.

- [ ] **Step 5: Verify each ADR has a complete decision record**

Run:

```powershell
$adrs = Get-ChildItem -LiteralPath 'docs/architecture/decisions' -Filter '*.md'
if ($adrs.Count -ne 3) { throw "Expected 3 ADRs, found $($adrs.Count)" }
foreach ($adr in $adrs) {
  $text = Get-Content -LiteralPath $adr.FullName -Raw
  foreach ($heading in @('# Status','# Context','# Options considered','# Decision','# Consequences')) {
    if ($text -notmatch [regex]::Escape($heading)) { throw "Missing $heading in $($adr.Name)" }
  }
}
'adr-completeness: PASS'
```

Expected: `adr-completeness: PASS`.

- [ ] **Step 6: Commit storage and ADR documents**

Run:

```powershell
git add docs/architecture/data-and-storage.md docs/architecture/decisions
git diff --cached --check
git commit -m "docs: define storage and recovery decisions"
```

Expected: clean whitespace check and a successful local commit.

### Task 6: Create user-experience sources of truth

**Files:**
- Create: `docs/experience/information-architecture.md`
- Create: `docs/experience/interaction-rules.md`

- [ ] **Step 1: Create `docs/experience/information-architecture.md`**

Document:

- persistent bottom navigation order: Timeline, Finance, Notes, Projects;
- global create action with Expense, Income, Transfer, and Note choices;
- global search and settings in top-level app bars;
- state retention per tab: scroll position, filters, selected date;
- bottom-navigation hiding rules for editors, attachment preview, and restore wizard;
- page ownership: Finance owns accounts and statistics, Notes owns Markdown content, Projects aggregates one primary context, Timeline is the default mixed read view;
- navigation from a timeline/search card back to the source entity;
- no generic Entry screen and no vertical scrolling between top-level domains.

Include a Mermaid flowchart for top-level destinations and detail routes.

- [ ] **Step 2: Create `docs/experience/interaction-rules.md`**

For each of these interactions, define entry state, editable fields, validation, save behavior, cancellation behavior, and failure behavior:

- expense, income, transfer, and note creation;
- account opening balance;
- transaction-note linking from either side;
- tag and primary-project assignment;
- image attachment import;
- share-text and share-image draft creation;
- search and type filtering;
- export progress and cancellation;
- restore preflight, confirmation, progress, success, and failure.

Require draft preservation on recoverable failures, no optimistic financial success before commit, and no partial restore wording.

- [ ] **Step 3: Verify the approved navigation is explicit**

Run:

```powershell
$ia = Get-Content -LiteralPath 'docs/experience/information-architecture.md' -Raw
foreach ($term in @('Timeline|时间线','Finance|财务','Notes|笔记','Projects|项目','bottom navigation|底部导航','scroll position|滚动位置')) {
  if ($ia -notmatch $term) { throw "Missing information-architecture term: $term" }
}
'experience-navigation: PASS'
```

Expected: `experience-navigation: PASS`.

- [ ] **Step 4: Commit experience documents**

Run:

```powershell
git add docs/experience
git diff --cached --check
git commit -m "docs: define navigation and interaction rules"
```

Expected: clean whitespace check and a successful local commit.

### Task 7: Create engineering quality and privacy sources of truth

**Files:**
- Create: `docs/engineering/conventions.md`
- Create: `docs/engineering/testing-strategy.md`
- Create: `docs/engineering/security-and-privacy.md`

- [ ] **Step 1: Create `docs/engineering/conventions.md`**

Include:

- Kotlin and Compose naming and file-focus rules;
- immutable `UiState`, events into ViewModel, and `StateFlow` out;
- injected dispatchers and clocks for deterministic tests;
- repository transaction ownership;
- `:core:data` mapping Room, file, ZIP, permission, and system exceptions into stable application error types;
- draft preservation on recoverable failures and success UI only after durable commit;
- automatic retry only for idempotent background operations, never for validation failure or corrupt data;
- no DAO, Room Entity, Android Context, or physical path leakage into UI;
- feature-to-feature dependency ban;
- Use Case creation thresholds;
- versions centralized in a version catalog once Gradle exists;
- dependencies require version, license, maintenance, and replacement-path review;
- no secret, token, password, user content, or sensitive path in code or logs;
- documentation-first rule for changed stable decisions.

- [ ] **Step 2: Create `docs/engineering/testing-strategy.md`**

Include the exact risk matrix from design section 9:

- domain unit and property tests;
- Room integration and transaction tests;
- N-1/N-2 migration tests;
- export-wipe-restore comparison;
- attachment and restore fault injection;
- 10,000-record Chinese search benchmark;
- ViewModel state tests;
- five critical Compose UI flows;
- timeline, thumbnail, aggregation, and startup benchmarks;
- Tech PoC entry gate and MVP release blockers.

Define evidence format as command, environment, result, and remaining risk. State that a passing build without restore verification is insufficient.

- [ ] **Step 3: Create `docs/engineering/security-and-privacy.md`**

Define:

- local-first and no-required-account baseline;
- MVP threat model: casual unauthorized app access, accidental data loss, corrupt import, excessive permissions, and sensitive logs;
- app lock as a UI access control, not database encryption;
- SQLCipher deferred until a recovery-capable V1 threat model exists;
- Photo Picker and SAF preference over broad storage access;
- no commercial analytics or crash SDK in MVP;
- local redacted logs with error category, stage, and anonymous operation ID only;
- user-initiated diagnostic export;
- no amounts, merchants, note text, attachment content, keys, or identifying paths in logs;
- backup integrity checks do not equal backup confidentiality.

- [ ] **Step 4: Verify the privacy prohibitions are explicit**

Run:

```powershell
$privacy = Get-Content -LiteralPath 'docs/engineering/security-and-privacy.md' -Raw
foreach ($term in @('no required account|无需账号','SQLCipher','Photo Picker','SAF','analytics|分析','note text|笔记正文','diagnostic|诊断')) {
  if ($privacy -notmatch $term) { throw "Missing privacy rule: $term" }
}
'privacy-rules: PASS'
```

Expected: `privacy-rules: PASS`.

- [ ] **Step 5: Commit engineering documents**

Run:

```powershell
git add docs/engineering
git diff --cached --check
git commit -m "docs: define engineering quality gates"
```

Expected: clean whitespace check and a successful local commit.

### Task 8: Create the phased roadmap and specification lifecycle

**Files:**
- Create: `docs/roadmap.md`

- [ ] **Step 1: Create `docs/roadmap.md`**

Document the exact sequence:

```text
Tech PoC: P0 engineering skeleton → P1 minimal Room model → P2 atomic transfer
→ P3 attachment failure recovery → P4 Chinese search → P5 export/restore
→ P6 timeline performance

MVP: M1 app shell → M2 finance core → M3 note core → M4 fusion relations
→ M5 retrieval and review → M6 data ownership → M7 hardening → M8 dogfood
```

For every slice, include one-sentence outcome and measurable exit gate from design section 10. Do not add file-level implementation steps.

Add the specification lifecycle:

```text
draft → approved → in-progress → completed
```

Define:

- one active `in-progress` spec at a time;
- `spec.md` owns behavior and acceptance;
- `plan.md` owns file sequence and commands;
- `verification.md` owns actual commands, results, deviations, and remaining risks;
- completed specs are read-only history;
- V1/V2 ideas remain candidates and do not authorize schema, interface, or module work.

- [ ] **Step 2: Verify roadmap ordering and lifecycle**

Run:

```powershell
$roadmap = Get-Content -LiteralPath 'docs/roadmap.md' -Raw
foreach ($term in @('P0','P6','M1','M8','draft','approved','in-progress','completed')) {
  if ($roadmap -notmatch [regex]::Escape($term)) { throw "Missing roadmap marker: $term" }
}
'roadmap-lifecycle: PASS'
```

Expected: `roadmap-lifecycle: PASS`.

- [ ] **Step 3: Commit the roadmap**

Run:

```powershell
git add docs/roadmap.md
git diff --cached --check
git commit -m "docs: add PoC and MVP roadmap"
```

Expected: clean whitespace check and a successful local commit.

### Task 9: Run the cross-document acceptance audit

**Files:**
- Verify: `AGENTS.md`
- Verify: `README.md`
- Verify: `docs/**/*.md`
- Modify only files that fail the checks

- [ ] **Step 1: Verify every required core document exists**

Run:

```powershell
$required = @(
  'AGENTS.md',
  'README.md',
  'docs/index.md',
  'docs/product/vision.md',
  'docs/product/mvp-requirements.md',
  'docs/architecture/overview.md',
  'docs/architecture/domain-model.md',
  'docs/architecture/data-and-storage.md',
  'docs/architecture/decisions/0001-native-android-room-2.md',
  'docs/architecture/decisions/0002-entityref-and-strong-relations.md',
  'docs/architecture/decisions/0003-generation-based-restore.md',
  'docs/experience/information-architecture.md',
  'docs/experience/interaction-rules.md',
  'docs/engineering/conventions.md',
  'docs/engineering/testing-strategy.md',
  'docs/engineering/security-and-privacy.md',
  'docs/roadmap.md'
)
$missing = $required | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missing) { throw "Missing required documents: $($missing -join ', ')" }
"required-docs: PASS ($($required.Count))"
```

Expected: `required-docs: PASS (17)`.

- [ ] **Step 2: Scan project documents for incomplete language**

Run:

```powershell
$docs = @('AGENTS.md','README.md') + (Get-ChildItem -LiteralPath 'docs' -Recurse -Filter '*.md').FullName
$markers = @(
  ('T' + 'BD'),
  ('T' + 'ODO'),
  ('FIX' + 'ME'),
  ('X' + 'XX'),
  ('待' + '定'),
  ('未' + '确定'),
  ('占' + '位')
)
$bad = Select-String -LiteralPath $docs -SimpleMatch -Pattern $markers
if ($bad) { $bad | Format-Table Path,LineNumber,Line; throw 'Incomplete document language found' }
'placeholder-scan: PASS'
```

Expected: `placeholder-scan: PASS`.

- [ ] **Step 3: Verify source-of-truth separation manually**

Check these exact conditions:

- README contains links and status only, not copied requirements.
- `docs/index.md` routes reading and does not restate architecture.
- product docs contain observable behavior and no DAO, SQL, or module implementation.
- architecture docs contain no screen-layout specification.
- experience docs contain no Room schema or physical storage path.
- roadmap contains outcomes and gates, not file-by-file implementation.
- research report is referenced only as evidence.
- the approved design spec states that core documents take authority after plan completion.

Expected: all eight conditions hold. Fix the owning document immediately if any condition fails.

- [ ] **Step 4: Check links and repository cleanliness**

Run the local-link validator:

```powershell
$markdownFiles = @(
  Get-Item -LiteralPath 'AGENTS.md', 'README.md'
) + @(
  Get-ChildItem -LiteralPath 'docs' -Recurse -Filter '*.md'
)
$missingLinks = @()
foreach ($file in $markdownFiles) {
  $text = Get-Content -LiteralPath $file.FullName -Raw
  $matches = [regex]::Matches($text, '\[[^\]]+\]\((?<target><[^>]+>|[^)\s]+)\)')
  foreach ($match in $matches) {
    $target = $match.Groups['target'].Value.Trim([char[]]@('<','>'))
    if ($target -match '^(https?://|mailto:|#)') { continue }
    $pathPart = [uri]::UnescapeDataString(($target -split '#', 2)[0])
    if (-not $pathPart) { continue }
    $resolved = [IO.Path]::GetFullPath((Join-Path $file.DirectoryName $pathPart))
    if (-not (Test-Path -LiteralPath $resolved)) {
      $missingLinks += "$($file.FullName) -> $target"
    }
  }
}
if ($missingLinks) { $missingLinks; throw 'Broken local Markdown links found' }
'local-links: PASS'
```

Expected: `local-links: PASS`.

Then run:

```powershell
git diff --check
git status --short
git log --oneline -8
```

Expected: `git diff --check` prints nothing; `git status --short` is empty; recent history shows the baseline plus focused documentation commits.

- [ ] **Step 5: Record completion in the plan commit only if the audit required fixes**

If Step 3 required edits, run:

```powershell
git add AGENTS.md README.md docs
git diff --cached --check
git commit -m "docs: resolve documentation consistency gaps"
```

Expected: a successful local commit. If no files changed, do not create an empty commit.

## Completion result

After all tasks pass, the workspace has a versioned, conflict-resistant documentation system. The next design cycle creates the Tech PoC P0 specification; this plan does not scaffold Android, add Gradle files, or implement application behavior.

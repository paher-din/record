# MVP M1 — App Shell

- **Status:** `approved` — 2026-08-28. The user explicitly delegated an independent Codex review, correction of any findings, and spec approval when no blocker remained. The review passed after the contrast, launch-identity, benchmark-isolation, and AGP built-in-Kotlin compatibility-gate corrections recorded below. M1 is not active; `plan.md` may be drafted, but implementation remains forbidden until the architecture prerequisite and explicit plan approval are recorded.
- **Drafted:** 2026-08-28
- **Spec approved:** 2026-08-28 — user context: “你自己来审阅一遍，如果有问题就修复，没问题可以自行批准并进入plan撰写”. Codex independently rechecked core-document consistency, scope, dependencies, benchmark isolation, lifecycle rules, every acceptance row, relative links, and accessibility color contrast; no blocker remained after inline corrections.
- **Plan draft:** 2026-08-28 — [plan.md](plan.md) is written and independently reviewed with no remaining technical finding, but unapproved. [Proposed ADR 0016](../../../architecture/decisions/0016-application-facing-startup-gate-handoff.md) and core-document synchronization remain blocking prerequisites to plan approval; no implementation authority exists.
- **Independent review corrections:** changed the light warm accent from `#9B6741` (4.26:1) to `#925D39` (4.89:1); added the missing adaptive/monochrome launcher identity and platform starting-window contract; bound destructive startup fixtures to `com.paherdin.record.benchmark` with separate execution-time deletion approval; made AGP 9.3 built-in Kotlin + Serialization plus Lifecycle benchmark alignment the first active implementation gate; and preserved the owning interaction transition table by routing pending startup through legal `Phase.Checking → Phase.Running → Phase.Recovering` transitions.
- **Design inputs confirmed:** 2026-08-28 — during pre-spec brainstorming, the user confirmed the Simplified Chinese UI baseline, visual direction A (warm editorial), theme strategy A1 (system-following light/dark), product-grade empty-shell completion level, tappable typed global-create placeholders, Navigation Compose approach A, the startup budgets, and all five design sections. These confirmations are design inputs, not approval of this written `spec.md`.
- **Roadmap:** [MVP M1 — App shell](../../../roadmap.md)
- **Product:** [Product vision](../../../product/vision.md), [MVP requirements](../../../product/mvp-requirements.md)
- **Experience:** [Information architecture](../../../experience/information-architecture.md), [Interaction rules](../../../experience/interaction-rules.md)
- **Architecture:** [Architecture overview](../../../architecture/overview.md), [Data and storage](../../../architecture/data-and-storage.md), [ADR 0010](../../../architecture/decisions/0010-generation-bootstrap-and-live-session-handoff.md)
- **Engineering:** [Engineering conventions](../../../engineering/conventions.md), [Testing strategy](../../../engineering/testing-strategy.md), [Security and privacy](../../../engineering/security-and-privacy.md), [Agentic execution workflow](../../../engineering/agentic-workflow.md)
- **Predecessor:** [Tech PoC P6 — Timeline performance](../../tech-poc/p6-timeline-performance/spec.md) is `completed`; P0–P6 are complete and no other spec is `in-progress`.
- **Architecture prerequisite before plan approval:** the application-facing startup classification/recovery handoff in section 9 affects a recovery boundary. Before an M1 implementation plan may be approved, the owning data/storage and architecture facts must be synchronized and [ADR 0016](../../../architecture/decisions/0016-application-facing-startup-gate-handoff.md) must be accepted. ADR 0016 is currently `Proposed`; this specification does not pre-accept it.

## 1. Purpose

Deliver the product-grade application shell that later MVP slices can extend without replacing its navigation, state-retention, theme, shared presentation, accessibility, startup-safety, offline, or platform-policy foundations.

M1 makes Timeline the default destination; provides the exact four-tab shell and application-level commands; proves typed, source-owned routing and independent retained tab state; integrates the real P5 startup gate before Room-backed UI; and establishes shared accessible `loading`, `empty`, `error`, and `ready` presentation patterns. It does not implement finance, notes, projects, search, settings subfeatures, or another later-slice domain behavior.

## 2. Current baseline and entry condition

The repository begins M1 with these verified facts:

- The exact seven-module production graph and one test-only `:benchmark` project exist and pass their machine checks.
- `minSdk = 34`, `targetSdk = 34`, `compileSdk = 36`, AGP 9.3.0, built-in Kotlin 2.2.10, Compose BOM 2026.06.00, Activity Compose 1.13.0, Room 2.8.4, and AndroidX Benchmark 1.4.1 are pinned.
- `:app` renders only the P0 `MaterialTheme { Text("P0 engineering skeleton") }` surface. `:core:ui` and the three feature modules have no production Kotlin source.
- The source manifest and both backup-rule files already contain the P5 system-backup exclusion contract and no network permission.
- `RecordRepository.open(context)` runs the real bootstrap → migration recovery → activation recovery → Room-open sequence, but it is synchronous and collapses a pending or blocked recovery into a repository failure. That public shape cannot render an early `RestoreRecoveryRoute`, cannot expose truthful recovery progress, and cannot meet the M1 pending-startup contract without a new narrow application-facing boundary.
- No active spec exists. This specification is approved but not `in-progress`; a plan may now be drafted/reviewed. M1 implementation remains forbidden until the complete lifecycle and prerequisite in section 16 pass.

## 3. Scope

### 3.1 Goals

M1 delivers:

1. Timeline as the default shell destination.
2. Persistent bottom navigation in the exact semantic and visible order Timeline, Finance, Notes, Projects, localized as 时间线、财务、笔记、项目.
3. One app-owned Navigation Compose graph with compile-time typed routes, source-owned destination content, independent top-level back stacks, predictive/system Back integration, and exact origin restoration.
4. Global create, Search, and Settings from every top-level app bar. Global create exposes exactly Expense, Income, Transfer, and Note, localized as 支出、收入、转账、笔记.
5. Product-grade M1 surfaces that state truthfully that later-slice behavior is not yet connected. They contain no fake persisted facts, disabled-looking controls that still mutate state, or generic Entry concept.
6. The A/A1 application theme, following the system light/dark mode with no in-app theme toggle and no dynamic-color override.
7. Shared accessible `loading`, `empty`, `error`, and `ready` body presentations that remain orthogonal to the exact `Phase.*` operation contract.
8. Independent retained tab/back-stack/UI state, including the real Timeline selected-day and Today behavior.
9. A real application-facing startup boundary over the existing P5 recovery machinery, with no shell or Room-backed frame before one complete vault is verified safe.
10. A local-vector adaptive/monochrome launcher icon and system starting window that use the A/A1 theme, expose no user data, and close the P0 skeleton's `MissingApplicationIcon` exception.
11. Repeatable Compose, navigation, startup, accessibility, manifest, packaged-policy, dependency, and network-isolation evidence.

### 3.2 Non-goals

M1 does not:

- implement account, transaction, allocation, balance, category, note, Markdown, attachment, tag, project, relation, Timeline projection, unified-search query, statistics, export, backup, restore-wizard, diagnostics, or App Lock behavior;
- add or change a Room Entity, DAO, database version, schema JSON, migration, backup format, recovery record, generation layout, attachment path, or domain invariant;
- register future M2–M7 production routes merely to complete the information-architecture table;
- create a generic Entry/Record route, editor, type, label, button, or persistence interface;
- add a DI framework, feature-to-feature dependency, new production module, speculative Use Case, business repository wrapper, or empty API/implementation module split;
- read domain data to populate M1 production surfaces or seed synthetic product data;
- add dynamic color, an in-app theme selector, an app-owned font dependency, custom password, network permission, analytics, crash reporting, ads, telemetry, or cloud behavior;
- create a broader brand kit, letter-based logo, finance-only symbol, remote image/font asset, or animated custom splash flow beyond the M1 local-vector launcher mark and platform starting window;
- change the production/debug application ID, reset the installed production/debug app, or read/copy/delete its vault while preparing M1 fixtures;
- claim App Lock, encryption, production Timeline performance, thumbnail performance, aggregation performance, restore completion, or Journeys 1–13 as M1 outcomes.

## 4. Module ownership

| Module | M1 ownership | Forbidden boundary |
|---|---|---|
| `:app` | Startup coordination, typed route keys, Navigation Compose graph assembly, four-tab scaffold, global commands, origin restoration, and `ShellChromePolicy` | No finance/note/project facts, editors, persistence behavior, DAO/Entity access, or duplicated recovery protocol |
| `:feature:timeline` | `TimelineHome`, `ProjectsHome`, `UnifiedSearch`, and injected representative card surfaces used to prove source dispatch | No source-fact write, DAO/Entity access, production Timeline query/schema, Project mutation, or search implementation |
| `:feature:finance` | `FinanceHome`, `TransactionDetail`, and typed Expense/Income/Transfer M1 placeholder destinations | No account/transaction/category model, validation, save, balance, history, statistics, or persistence interface |
| `:feature:notes` | `NotesHome`, `NoteDetail`, and typed Note M1 placeholder destination | No Note model, Markdown editor, image pipeline, tag/relation behavior, or persistence interface |
| `:core:ui` | Semantic theme tokens, exact shared `Phase` type, content-presentation pattern, accessible shared UI, and shell chrome metadata/policy shared by the app/features | No product journey, navigation controller, repository, Android storage, raw exception, or domain fact |
| `:core:data` | Existing P5 startup/recovery protocol plus the narrow application-facing classification/progress/retry boundary required by section 9 | No UI state/composable, no protocol weakening, no public physical path/Room type, no schema or migration change |
| `:core:model` | Existing pure Kotlin values only | M1 adds no domain ID, money, route, UI state, or speculative model |

`:app` composes feature content by passing typed navigation-intent callbacks. A feature never imports another feature and never receives the app's `NavController`. Route keys and cross-feature graph assembly remain app-owned; screen content and source-specific labels remain feature-owned.

## 5. Language, theme, and visual system

### 5.1 Language boundary

- User-visible functional M1 copy is Simplified Chinese. `Record` remains the proper product/launcher name and is stored in `@string/app_name`, not hard-coded in the manifest.
- Kotlin types, route keys, semantic token names, test IDs, commands, and variables remain English.
- Every production string is an Android string resource. M1 adds no language switch and makes no claim that another locale is complete.
- Route semantics retain the English identities from the information architecture even when their visible labels are Chinese.

### 5.2 A/A1 visual direction

The approved direction is warm editorial: low-stimulation warm neutrals, restrained paper-like surface separation, dark green primary emphasis, warm brown secondary emphasis, readable hierarchy, and no finance-dashboard density on the shell.

The semantic palette is frozen by role rather than by arbitrary per-screen colors:

| Role | Light reference | Dark reference | Required use |
|---|---:|---:|---|
| App background | `#F6F2E9` | `#171C1A` | Root shell and starting-window continuity |
| Primary surface | `#EEE6D7` | `#222A27` | Navigation and grouped shared surfaces |
| Primary action/accent | `#145C4B` | `#8BD0BB` | Selected navigation, primary action, focus emphasis |
| Warm secondary accent | `#925D39` | `#D2A987` | Timeline/empty-state secondary emphasis only; the light value is darkened from the visual exploration reference so normal text reaches 4.89:1 on the app background |
| Primary content | `#24342F` | `#E9EEE9` | High-emphasis text and icons |
| On-primary content | `#FFF9EE` | `#0D392F` | Content placed on the primary action/accent |

Implementation derives the remaining Material color roles from these references and proves contrast; it does not scatter literal colors across feature code. Error colors remain semantic error roles rather than reusing the warm secondary accent.

- Both light and dark schemes are complete and follow the system setting.
- Android dynamic color is disabled so device wallpaper cannot replace the approved product palette.
- Typography uses the Android/system sans family; no downloadable or bundled font is introduced. Text styles, spacing, shapes, elevation, and icon sizes are semantic `:core:ui` tokens.
- Top-level bars, bottom navigation, modal create chooser, shared presentations, M1 placeholders, and Android starting-window background use the same tokens.
- The launcher mark is a text-free local vector: one continuous timeline thread connecting two circular events inside the adaptive safe zone. It uses the warm background plus primary green, supplies foreground/background/monochrome resources, contains no finance-only glyph, and is the only mark used by the platform starting window. M1 uses the API 34 platform starting-window contract rather than a second custom splash Activity or timer.
- M1 does not depend on decorative animation for comprehension. Navigation and content transitions respect platform animation settings and preserve readable intermediate frames.

### 5.3 Accessibility

All shared and shell components must:

- expose one clear screen heading and a deterministic traversal/focus order;
- expose selected/unselected tab semantics and meaningful icon labels rather than icon glyph names;
- use at least 48 dp interactive targets;
- distinguish loading, valid empty, failure, and ready content through semantics and text, not color alone;
- announce current loading/error state without repeatedly stealing focus; Retry is an actual button with a stable label;
- preserve meaning and operation at 200% font scale without clipped required text, overlapping actions, hidden focus targets, or horizontal scrolling of the shell;
- meet at least 4.5:1 contrast for normal required text and 3:1 for large text, meaningful icons, focus indicators, and component boundaries where contrast is necessary to perceive the control.

## 6. Shared presentation and operation phase contract

M1 establishes one immutable content-presentation family in `:core:ui`:

| Presentation | Meaning | Required UI behavior |
|---|---|---|
| `loading` | Complete body content is not yet available | Show an honest progress/skeleton state; do not expose partial data as complete or enable content actions |
| `empty` | The requested content domain is valid and has zero applicable items | Explain what is empty and keep navigation/context usable; never use this for load failure |
| `error` | Complete content could not be obtained | Render a stable application error message, retain navigation/filter/date context, and expose Retry only when safe |
| `ready` | Complete current UI model is available | Render and enable only the actions represented by that complete model |

The presentation family describes the screen body. It never replaces or aliases the exact `Phase.*` operation state machine in the interaction rules. M1 creates the exact shared `Phase` closed type in `:core:ui`; later flows may use only those tokens and legal transitions. A screen may have ready content while a separate save/export operation is in another allowed phase. There is no `isLoading`, `isSaving`, or similar hidden phase flag that can contradict `Phase`.

Production M1 feature surfaces do not query domain data, so they must not falsely say that a persisted catalog or record set is empty. They render a truthful `ready` M1-availability body such as “财务功能将在 M2 接入.” The real Timeline date shell may render its selected date, but it states that Timeline content is not yet connected rather than claiming that the persisted day has no records. Representative UI tests inject synthetic, visibly test-only UI models to exercise all four presentations; those fixtures are absent from `main` and `release` artifacts.

Raw `Throwable`, provider text, Room/SQLite error, path, URI, domain ID, or user content never enters a presentation state. Stable application error categories select localized copy.

## 7. M1 route inventory and ownership

The following are the complete production M1 route registrations:

| Route | Owner | M1 surface | Bottom navigation | Back result |
|---|---|---|---|---|
| `TimelineHome` | `:feature:timeline` | Real selected-day/Today shell plus truthful M1 availability body | Visible | App exit from root |
| `FinanceHome` | `:feature:finance` | Product-grade M2 availability body | Visible | App exit from root |
| `NotesHome` | `:feature:notes` | Product-grade M3 availability body | Visible | App exit from root |
| `ProjectsHome` | `:feature:timeline` | Product-grade M4 availability body | Visible | App exit from root |
| `UnifiedSearch` | `:feature:timeline` | Product-grade M5 availability body | Visible | Exact launching top-level state |
| `Settings` | `:app` | M1 app information plus truthful later-settings availability; no future settings subroutes | Visible | Exact launching top-level state |
| `TransactionDetail` | `:feature:finance` | Typed source-owned M1 placeholder reached only from injected representative UI/test routing | Visible | Exact Timeline/Finance/Projects/Search origin |
| `NoteDetail` | `:feature:notes` | Typed source-owned M1 placeholder reached only from injected representative UI/test routing | Visible | Exact Timeline/Notes/Projects/Search origin |
| `ExpenseEditor` | `:feature:finance` | No fields or Save; clearly states M2 ownership | Hidden | Exact launch context |
| `IncomeEditor` | `:feature:finance` | No fields or Save; clearly states M2 ownership | Hidden | Exact launch context |
| `TransferEditor` | `:feature:finance` | No fields or Save; clearly states M2 ownership | Hidden | Exact launch context |
| `NoteEditor` | `:feature:notes` | No fields or Save; clearly states M3 ownership | Hidden | Exact launch context |
| `RestoreRecoveryRoute` | `:app`, driven by `:core:data` | Data-free recovery status and safe Retry only | Shell not constructed | Cannot bypass unresolved recovery |

The global create chooser is a modal application surface, not a route or entity type. It contains exactly the four typed choices Expense, Income, Transfer, and Note in that order. Each selection navigates to its named owner placeholder; it never dispatches to a generic editor.

Route keys are `@Serializable` Kotlin objects/data classes owned by `:app`. Arguments contain only stable ID text and the minimum navigation-only origin discriminator needed by the approved information architecture. They never contain a Room Entity, DAO, physical path, URI, repository object, complete UI state, user-entered content, or projection payload. The originating back-stack entry owns scroll/filter/query/date state; returning pops to that entry rather than reconstructing it from route arguments.

No Category/Tag/Project picker/editor, account editor/detail, attachment preview, export/backup/restore wizard, privacy, diagnostics, App Lock, or progress route is registered in M1. A test-only representative progress destination verifies the shared chrome policy and is excluded from `main` and `release`.

## 8. Navigation, chrome, and retained state

### 8.1 Navigation implementation contract

- Use Navigation Compose 2.9.8 and Kotlin Serialization type-safe routes; raw route strings are forbidden.
- One app-owned `NavController` manages the graph. Switching top-level destinations uses single-top plus state save/restore so each tab retains an independent stack.
- Selecting or reselecting a top-level tab never resets its stack, scroll, filter, or date. M1 contains no “tap active tab to scroll to top” behavior.
- Cross-feature intent is expressed as typed callbacks into `:app`; features never see `NavController` and never depend on one another.
- System, app-bar, and predictive Back share the same route result. A placeholder has no dirty draft, so it returns without mutation or discard confirmation.
- Unsupported/deleted route restoration fails closed to the safe root only when the saved route is not legal; it never redirects an ID to an unrelated record.

### 8.2 Shell chrome policy

One exhaustive `ShellChromePolicy` derives app bar, bottom navigation, and modal-navigation availability from destination metadata:

- top-level, Search, Settings, and stable Detail contexts show bottom navigation;
- Editor contexts hide bottom navigation and preserve the selected tab/stack underneath;
- progress contexts hide or disable navigation and Back according to the exact operation phase; M1 proves this with a test-only destination rather than a future production route;
- startup checking/recovery states do not construct the shell at all.

Feature composables cannot independently override the bottom bar. An unmapped production destination is a test/build failure, not a default-visible fallback.

### 8.3 Per-tab retained state

Each top-level back-stack entry owns saveable, independently keyed UI state. M1 production state includes the values that its surfaces truly expose; test-only state probes prove the generic shell retention boundary for future scroll/filter/date fields without adding fake production controls or domain interfaces.

Required retention paths are tab switch, tab reselect, child/detail round trip, Search round trip, Settings round trip, Activity/configuration recreation, and an eligible Android saved-state/process recreation. Restoring one tab cannot overwrite another. Force-stop, uninstall, device loss, or arbitrary unsaved-draft recovery remains outside MVP.

### 8.4 Timeline date and Today

- With no legal restored Timeline state, the selected day is the current local date from an injected `Clock` and device `ZoneId`.
- Legal saved selected day wins over the current clock after configuration/process recreation.
- Foreground midnight, time-zone change, ordinary Timeline reselect, child return, and tab switching do not change the viewed day.
- Explicit Today re-reads the injected time sources, selects the then-current local date, preserves applicable filters, invalidates an obsolete date request revision, and returns the list state to the top.
- M1 makes no production Timeline query. Test-only injected UI models prove ready-card source dispatch and stale-revision suppression without becoming product data.

## 9. Safe startup-gate boundary

### 9.1 Required architecture correction

The P5 recovery protocol remains authoritative and unchanged, but its current all-in-one `RecordRepository.open()` surface is insufficient for M1 UI ordering. Before implementation, the owning data/storage and architecture documents plus ADR 0016 must define a narrow application-facing startup coordinator that:

1. reads/classifies the app-private recovery record before Room/SQLite helper initialization;
2. exposes data-free `Checking`, `RecoveryDetected`, and `Recovering` progress quickly enough for `:app` to move through the existing legal phase transitions and draw the correct recovery destination;
3. continues the existing bootstrap → migration recovery → generation activation → startup validation → Room-open sequence under one owner;
4. emits the successful repository binding only after the complete safe boundary passes;
5. exposes safe retry capability and stable failure category without exposing internal records, stages that leak paths, counts, IDs, DAO/Room types, or recovery mutation methods.

This is an application boundary over the existing protocol, not a second recovery implementation. `:app` cannot inspect or write the activation record and cannot compose recovery steps itself.

### 9.2 UI ordering and phase mapping

The startup UI uses the exact interaction phases:

| Core startup outcome | UI phase | Allowed surface |
|---|---|---|
| Classification/open in progress with no known pending work | `Phase.Checking` | Data-free launch/checking surface; no shell |
| Durable pending work was classified and resolution is being dispatched | `Phase.Running` | Data-free launch surface; no shell; named `RecoveryDetected` event only |
| Existing migration/activation recovery is being resolved | `Phase.Recovering` | `RestoreRecoveryRoute`; no shell |
| Complete safe repository binding available | transient `Phase.Completed` | Construct shell and enter retained/default Timeline |
| Safe explicit retry can continue the same protocol | `Phase.RecoverableFailure` | `RestoreRecoveryRoute` with Retry |
| External remediation or unresolved safety state blocks progress | `Phase.BlockingFailure` | `RestoreRecoveryRoute`; Retry only when coordinator permits |

- `RecordApplication` owns one process-wide startup coordinator. Repository open/resolution work runs off the main thread under structured concurrency; the UI receives immutable state.
- No shell, top-level route, global action, user data, record count, filename/path, terminal detail, or old activity frame is visible before the safe boundary completes.
- Retry resumes/re-enters the architecture-owned idempotent path. It cannot create a fresh database, select a new backup, clear recovery, or “continue anyway.”
- A healthy first install may create the initial generation only through the P5 protocol. It is not an M1 schema operation.
- An app-process restart during recovery reclassifies the durable record and resumes safely; UI progress is never accepted as recovery evidence.

## 10. Dependency boundary and review

M1 may add only the dependencies needed for the selected typed-navigation/lifecycle implementation and its tests. Exact versions are centralized in `gradle/libs.versions.toml`.

| Dependency | Frozen version | Purpose | License/maintenance/compatibility/removal |
|---|---:|---|---|
| `androidx.navigation:navigation-compose` | 2.9.8 | Type-safe Compose graph and multi-back-stack state | Apache-2.0 AndroidX; stable release with official type-safe/multi-stack APIs; compatible with the frozen API 34+/compile 36 baseline; removable only by replacing the app navigation implementation |
| `androidx.navigation:navigation-testing` | 2.9.8 | Type-safe route/back-stack tests | Apache-2.0 AndroidX, version-aligned with runtime; androidTest only; removable with navigation test harness |
| Kotlin Serialization Gradle plugin | 2.2.10 | Compiler support for `@Serializable` route keys | Apache-2.0 Kotlin plugin aligned exactly with the frozen Kotlin version; no runtime permission/network behavior; removable with typed route serialization |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.9.0 | Runtime serialization required by the selected Navigation type-safe route setup | Apache-2.0; release based on Kotlin 2.2.0 and compatible with the frozen Kotlin 2.2.10 line; no Android permission/network component; route-only use and replaceable with navigation implementation |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.10.0 | Lifecycle-aware immutable state collection and Compose-safe startup readiness | Apache-2.0 AndroidX stable line compatible with the existing Compose/Activity baseline; no permission/network component; replaceable by equivalent lifecycle-safe collection |

Existing Compose BOM, Material 3, Activity Compose, AndroidX Test, JUnit, Benchmark, and ProfileInstaller remain version-controlled by the catalog. The implementation plan must verify resolved dependency/manifest graphs and may remove a listed dependency when evidence proves it unnecessary; it cannot substitute another version/library without revising and reapproving this spec or its approved dependency record.

The repository has not yet compiled the Kotlin Serialization plugin together with AGP 9.3 built-in Kotlin. The first active implementation task is therefore a build-only compatibility gate: apply only the approved catalog/plugin/dependency delta, compile one typed-route serialization smoke source/test, and run the module/dependency checks before feature code. Failure blocks route/UI work and requires an evidence-based spec/plan revision; opting out of built-in Kotlin or changing `.env`/`gradle.properties` is not an authorized workaround. Because the existing benchmark test APK pins Lifecycle 2.9.4 while M1 selects Lifecycle Runtime Compose 2.10.0, the same gate must align the benchmark test runtime to 2.10.0 and rerun the P6 wiring/self-check regression before accepting the dependency baseline.

No navigation framework migration, Navigation 3, Hilt/Koin/Dagger, adaptive-navigation library, icon pack with unnecessary surface, analytics, crash SDK, or network library is authorized.

## 11. Startup benchmark contract

### 11.1 Reference environment

Binding performance evidence runs on the existing physical Xiaomi 14 Pro reference device. The run records actual Android/API build, build fingerprint, CPU/RAM, battery/thermal state, native display settings, app commit/build variant, fixture hashes, and benchmark/library versions. Android 14/API 34 remains the product minimum/target baseline; the actual upgraded device build is recorded rather than relabeled.

The benchmark target remains the existing non-debuggable `:app` benchmark variant and existing `:benchmark` test-tool project, but M1 must isolate it from every production/debug install by binding the benchmark variant to application ID `com.paherdin.record.benchmark` and its independent Android data root. No startup test may install over, open, copy, clear, mutate, or delete `com.paherdin.record` data. M1 adds benchmark/test-source fixtures only; no fixture control, profileable declaration, or test data enters `main` or `release`.

Fixture reset may clear only the exact isolated benchmark package after the harness validates both its application ID and benchmark signature/variant marker. Clearing even that isolated package is a destructive action: before the first execution, the main agent must state the exact package/data target, reason, and risk, then obtain the user's explicit deletion approval. Spec or plan approval alone is not that execution-time approval.

### 11.2 Fixtures

The plan must generate and hash these `m1-startup-v1` fixtures through approved test-only helpers:

| Fixture | Binding contents/state |
|---|---|
| Fresh empty | No legacy data or recovery record before setup; P5 creates one healthy initial generation and opens the unchanged v2 schema with zero facts |
| Typical healthy | 2 accounts; 5 valid Transactions (2 opening, 1 expense, 1 income, 1 transfer); 6 AccountEntries; 2 Notes; 2 typed transaction-note relations; 1 canonical attachment blob linked to both valid owners; healthy v2 activation record |
| High-volume healthy | 10 accounts; 6,000 valid Transactions using the frozen P6 type mix (10 opening, 3,000 expense, 1,800 income, 1,190 same-currency transfer); 7,190 AccountEntries; 4,000 Notes; 2,000 valid typed relations; 100 canonical 64-KiB synthetic image blobs with valid owner links; healthy v2 activation record |
| Pending activation | Typical healthy original plus one fully staged/validated typical candidate and an exact durable `PENDING` activation record at the pre-resolution boundary |
| Pending migration recovery | A valid published snapshot plus an exact `MIGRATION_RECOVERY_PENDING` v2 record at each architecture-relevant resumable stage used by the M1 startup gate |

Every fixture is synthetic, contains no personal data, passes its own integrity/invariant/hash checks before measurement, and is restored to the same starting state before each warmup and measured iteration. Fixture preparation and validation occur outside the timed launch.

Fixture preparation occurs only inside `com.paherdin.record.benchmark` after the destructive-reset approval above. The harness fails before any clear/write if package identity, variant marker, or data-root isolation is missing or ambiguous.

### 11.3 Measurement and budgets

- Use `StartupMode.COLD`, `StartupTimingMetric`, and `CompilationMode.Full` on the physical reference device.
- Each fixture runs 5 unmeasured warmups followed by 30 measured launches. Raw per-iteration values are retained; p95 is nearest-rank value at one-based rank 29 after sorting 30 samples.
- `timeToInitialDisplayMs` (TTID) ends at the first destination frame. `timeToFullDisplayMs` (TTFD) uses Compose-safe `ReportDrawnWhen` and ends only after the healthy Timeline shell is fully interactive.
- Healthy Fresh, Typical, and High-volume fixtures must each meet TTID p95 ≤ 1,000 ms and TTFD p95 ≤ 1,500 ms.
- Pending activation and every pending migration-recovery fixture must each render the correct data-free safe recovery gate at TTID p95 ≤ 1,000 ms. Recovery completion time is retained as diagnostic evidence but is not an M1 performance gate.
- A missing recovery frame, any shell/user-data frame before safety, wrong route, unvalidated fixture, ANR, OOM, crash, process death, invalid trace, or missing sample fails the suite.
- Reference-device preflight is the first blocking implementation-plan task. Because the device was unavailable while this draft was written, this specification contains no claimed current baseline. If the approved budgets prove unattainable, the spec and plan must be revised and explicitly reapproved; they cannot be silently relaxed.

## 12. Verification design

### 12.1 Pure/unit tests

- Exhaustive `Phase` token and legal-transition checks; no alias/hidden phase flag.
- Content-presentation construction and stable-error mapping.
- Typed route serialization/round-trip, argument rejection, origin mapping, and complete M1 route allowlist.
- Exhaustive `ShellChromePolicy`, including test-only progress and no default fallback.
- Timeline `Clock`/`ZoneId`, no-state launch, saved-state precedence, foreground midnight, explicit Today, stale date revision, and Today-to-top behavior.
- Startup outcome → exact `Phase`/destination mapping, single coordinator ownership, retry capability, and no shell-before-ready transition.

### 12.2 Compose/navigation tests

- Launch into Timeline and assert the exact four visible labels/order.
- Switch/reselect all tabs; use test-only per-tab state probes to prove independent scroll/filter/date retention without adding production fake controls.
- Recreate the Activity and exercise eligible saved-state/process restoration; each tab stack and state remains independent.
- Open/close Search and Settings from every tab and assert exact origin restoration.
- Inject representative Timeline/Projects/Search ready cards; Transaction routes only to Finance-owned detail, Note only to Notes-owned detail, and Back restores exact origin state.
- Assert global create contains exactly 支出、收入、转账、笔记; each choice opens the correct typed owner placeholder; no generic Entry semantic/source token exists.
- Assert bottom-navigation visibility for roots/details/Search/Settings, hidden behavior for every M1 Editor, hidden/blocked behavior for test-only progress, and complete shell absence for startup checking/recovery.
- Render every shared presentation under light/dark schemes and 100%/200% font scale; assert heading, state, selection, Retry, focus order, touch target, no required-text clipping/overlap, and no raw-error semantics.
- Inspect/render the adaptive foreground/background/monochrome launcher resources and platform starting window in light/dark launch conditions; assert the safe-zone mark is visible and no previous/user-data frame appears.
- Assert M1 `main`/`release` routes and screens contain no synthetic ready data, domain-data dependency, Save action, form field, or future production route.

### 12.3 Device, artifact, and offline checks

- Run the startup benchmark contract and retain JSON/Perfetto/raw environment evidence.
- Before fixture reset, validate the exact isolated benchmark package/variant; record the user's explicit deletion approval and retain a host-command/target trace proving that no setup, clear, copy, `run-as`, or file command targeted the production/debug package or its data root.
- Drive healthy, pending activation, and pending migration states through the real application-facing startup boundary; assert no Room/shell bypass and idempotent restart/retry.
- Execute every M1 production route with airplane mode or equivalent network isolation; all navigation, presentation, state restoration, and retry behavior remains available.
- Inspect source manifest, merged release manifest, final APK manifest, and packaged rule XML. Assert no `INTERNET`, related network permission, unexpected network component, analytics/tracking manifest contribution, or unapproved exported component.
- Assert `allowBackup=false` and exact cloud-backup/device-transfer exclusion coverage for all nine approved Android domains in source, merged, and packaged artifacts.
- Inspect resolved direct/transitive dependencies and manifests; only approved versions/capabilities exist and the exact seven-module production graph remains unchanged.
- Run `git diff --check`, documentation-link/lifecycle scans, no-generic-Entry source/semantics scans, and the default repository verification graph. Exact commands belong to the later approved `plan.md`; actual commands/results belong to `verification.md`.

## 13. Acceptance criteria

M1 can become `completed` only when `verification.md` contains raw evidence for every criterion:

1. **Lifecycle and architecture prerequisite:** the written spec and plan have explicit recorded approval, M1 is the unique `in-progress` spec during implementation, the startup-boundary ADR is accepted, and owning core documents are synchronized.
2. **Module boundary:** the exact seven production modules and allowed edges remain unchanged; no feature-to-feature/core-to-feature edge or new production/test project exists beyond accepted `:benchmark`.
3. **Theme/localization/launch identity:** A/A1 semantic tokens drive every M1 shell/shared surface in complete system-following light/dark schemes; dynamic color is disabled; functional production copy is Simplified Chinese resources, `Record` is a resource-backed proper product name, internal typed names remain English, and the local-vector adaptive/monochrome mark plus platform starting window pass packaged-resource and no-data-preframe checks.
4. **Accessibility:** shell/shared presentations pass the heading, state, selected-tab, focus, 48-dp target, 200%-font, no-clipping, and contrast gates in section 5.3.
5. **Shared presentation:** representative screens switch correctly through `loading`, `empty`, `error`, and `ready`; error is never empty, partial is never ready, stable Retry semantics are correct, and production fixtures contain no synthetic business data.
6. **Operation phases:** the exact `Phase.*` closed set/legal transitions are represented once; shared presentations neither replace nor contradict them, and scans/tests find no hidden alias flags.
7. **Default shell:** a healthy launch opens Timeline and shows persistent bottom navigation in the exact order 时间线、财务、笔记、项目; top-level domains are peer stacks rather than one vertically paged surface.
8. **Global actions:** every top-level app bar reaches global create, Search, and Settings; global create exposes exactly 支出、收入、转账、笔记 and dispatches to the correct owner placeholder; no generic Entry exists.
9. **Typed/source-owned routing:** every registered production M1 route is typed and works; representative Transaction/Note selection reaches only the owning Finance/Notes detail; route arguments expose no Room/DAO/path/URI/payload; Back restores exact origin.
10. **Chrome rules:** bottom navigation is visible for root/detail/Search/Settings, hidden for every M1 Editor, hidden/blocked for representative progress, and not constructed during startup checking/recovery.
11. **Independent retention:** all four top-level stacks and their scroll/filter/date probes retain independently through switch, reselect, child/Search/Settings round trip, configuration recreation, and eligible saved-state/process restoration.
12. **Timeline date:** injected-clock/ZoneId current-day default, legal saved-state precedence, foreground-midnight stability, explicit Today re-read, stale-revision suppression, filter preservation, and Today-to-top all pass.
13. **Safe startup:** the real P5 protocol is reached only through one application-facing coordinator; no shell/Room-backed/user-data frame appears before safety; pending/blocked states reach `RestoreRecoveryRoute`; Retry cannot bypass/reinitialize/reselect; restart resumes idempotently.
14. **Startup budgets and isolation:** all Fresh/Typical/High-volume healthy and pending activation/migration fixtures meet the exact TTID/TTFD budgets and 30-sample protocol in section 11 with no invalid stability outcome; fixture reset has explicit execution-time deletion approval, targets only `com.paherdin.record.benchmark`, and the retained command/target trace contains no production/debug package or data-root operation.
15. **Dependency review:** every new direct/transitive dependency matches the approved catalog version, license, maintenance, compatibility, permissions/network, and removal-path record; no DI, tracker, analytics, crash, ad, or network library is present.
16. **Offline routes:** every M1 route and required retry/state/navigation flow passes in airplane mode or equivalent network isolation.
17. **Network/system-backup policy:** source/merged/final artifacts declare no `INTERNET`/related or unexpected network capability and retain exact cloud-backup/device-transfer exclusions plus `allowBackup=false`.
18. **No premature domain work:** M1 changes no schema/migration/backup format/recovery record/domain invariant, adds no domain save/query surface, and implements no M2+ business behavior or future production route.
19. **Verification record:** actual commands, environment, counts, benchmark values, artifact hashes/paths, deviations, and remaining risks are repeatable and present; no failed or missing criterion is relabeled or waived.

## 14. Risks and failure handling

- **Startup API gap:** the current synchronous repository-open shape cannot satisfy the UI ordering. The required ADR/core-document prerequisite is blocking, not optional plan polish.
- **Reference device unavailable:** the device was disconnected during drafting. M1 cannot enter performance execution on an invented baseline and cannot complete without the physical-device evidence.
- **Destructive benchmark reset:** repeatable cold-start fixtures require clearing isolated benchmark-package data. A missing exact-package check, variant marker, or explicit execution-time deletion approval blocks the run; the production/debug app data is never an acceptable fixture target.
- **Placeholder longevity:** M1 placeholders are deliberate stage surfaces, not future behavior. Later specs replace only their content while preserving approved route/owner/chrome contracts; they must not accumulate fields or writes opportunistically.
- **False empty claim:** because M1 reads no domain data, its production feature bodies must say the capability is not yet connected. Only an actual later data query may claim an empty record set.
- **State-restoration overreach:** test-only probes prove the shell container; they do not authorize future domain filters, routes, or interfaces.
- **Navigation/version drift:** Navigation Compose 2.9.8 is the accepted M1 choice. A different navigation generation or dependency version requires explicit review rather than an opportunistic upgrade.
- **AGP/Kotlin plugin compatibility:** built-in Kotlin plus the serialization compiler plugin is not yet proven in this repository. The build-only entry gate must pass without changing `gradle.properties`; failure stops feature work and revises the approved dependency decision.
- **Budget failure:** an exceeded budget remains a failed M1 contract. The project must optimize within scope or revise/reapprove the written spec and plan.

## 15. Documentation and ADR synchronization

Before M1 plan approval:

1. Update the owning architecture/data facts to describe the application-facing startup classification/progress/retry boundary without duplicating the P5 protocol.
2. Create and obtain acceptance for ADR 0016, recording the current synchronous-boundary limitation, considered alternatives, selected coordinator handoff, no-schema/no-record-format impact, error/privacy surface, and rollback/removal path.
3. Update this spec if the accepted ADR changes any behavior, module owner, public outcome, phase mapping, or acceptance criterion; obtain explicit written-spec reapproval when required.
4. Record the exact dependency review and the device-baseline preflight task in the plan.

M1 implementation changes that expose a conflict with a core document stop before code silently chooses a new fact. A schema or migration requirement is outside this spec and also crosses a user approval redline; it requires a revised approved scope plus explicit user consent before execution.

## 16. Lifecycle path

1. **`draft` (completed 2026-08-28):** behavior, boundaries, dependencies, startup prerequisite, and acceptance were clarified without plan or implementation authority.
2. **`approved` (current):** the user explicitly authorized independent review/correction and approval of this written `spec.md`; the approval date/context is recorded above. `plan.md` may be drafted/reviewed while the documentation/ADR prerequisite completes, but the plan cannot be approved until the accepted ADR, owning core documents, spec, and plan agree.
3. **`in-progress`:** the startup ADR/core docs are accepted, the user explicitly approves the implementation plan, both approval facts are recorded, and M1 becomes the unique active spec. Functional work is dispatched through the agentic execution workflow.
4. **`completed`:** every section 13 criterion and the roadmap M1 exit gate has raw evidence in `verification.md`, all affected core documents/ADR are synchronized, and no blocker remains.

The recorded user delegation above is the explicit spec approval. A generated or Codex-reviewed future plan is still not approved; the user must explicitly approve that written plan after the architecture prerequisite is accepted and synchronized.

# MVP M2 — Finance Core

- **Status:** `draft` — the written specification has not yet been approved. No plan, ADR, schema change, migration, interface, dependency, route, or implementation work is authorized by this draft.
- **Drafted:** 2026-09-04
- **Design inputs confirmed:** 2026-09-04 — the user approved all five pre-spec design sections: the single vertical-slice approach; the product and interaction boundary; the domain/application boundary; the schema, migration, and backup-compatibility design; and the verification design. These confirmations are design inputs, not approval of this written `spec.md`, a future `plan.md`, or execution of a database migration.
- **Roadmap:** [MVP M2 — finance core](../../../roadmap.md)
- **Product:** [Product vision](../../../product/vision.md), [MVP requirements](../../../product/mvp-requirements.md)
- **Experience:** [Information architecture](../../../experience/information-architecture.md), [Interaction rules](../../../experience/interaction-rules.md)
- **Architecture:** [Architecture overview](../../../architecture/overview.md), [Domain model](../../../architecture/domain-model.md), [Data and storage](../../../architecture/data-and-storage.md), [ADR 0004](../../../architecture/decisions/0004-catalog-lifecycle-and-normalized-names.md), [ADR 0006](../../../architecture/decisions/0006-version-1-persistence-contract.md), [ADR 0007](../../../architecture/decisions/0007-recoverable-migration-snapshot-ownership.md), [ADR 0010](../../../architecture/decisions/0010-generation-bootstrap-and-live-session-handoff.md), [ADR 0016](../../../architecture/decisions/0016-application-facing-startup-gate-handoff.md)
- **Engineering:** [Engineering conventions](../../../engineering/conventions.md), [Testing strategy](../../../engineering/testing-strategy.md), [Agentic execution workflow](../../../engineering/agentic-workflow.md)
- **Predecessor:** [MVP M1 — app shell](../m1-app-shell/spec.md) is `completed` (2026-09-04); no specification is currently `in-progress`.

## 1. Purpose

Deliver the first complete user-facing finance slice on top of the safe M1 shell and the proven P1/P2/P5 persistence foundations.

M2 makes accounts, opening history, income, expense, same-currency transfer, first-level Category lifecycle, recomputable balances, transaction editing, and recoverable transaction withdrawal usable offline. Every visible success waits for the complete durable commit. The slice also evolves the Room schema and logical backup contract without destructive migration or loss of existing compatible data.

## 2. Baseline and entry conditions

The M2 draft begins from these repository facts:

- M1 is completed and the project has no active specification.
- The exact seven production modules and the ADR-0011 `:benchmark` test project already exist.
- The M1 app shell owns the typed Navigation Compose graph, persistent four-tab chrome, theme, shared presentations, and the ADR-0016 startup gate.
- `:feature:finance` contains only M1 availability surfaces. It has no ViewModel, finance editor, account screen, or domain-backed read.
- Room `databaseVersion=2` contains `EntityRef`, `Transaction`, `Account`, `AccountEntry`, `Note`, `TransactionNoteRelation`, `Attachment`, and `AttachmentLink`. `Transaction` currently persists only `id` and `type`.
- The existing repository proves entry-side financial cardinality, signs, same-currency transfer, exact zero sum, atomic writes, and balance reproducibility, but its public DTO surface has no Category allocation, occurrence time, memo, finance Flow, withdrawal, or edit concurrency contract.
- Logical backup `formatVersion=1` serializes the eight current-schema datasets and does not carry M2 transaction fields or Category facts.
- The recoverable migration snapshot, generation layout, startup resolver, logical backup/restore pipeline, application-facing startup gate, API guards, and network/system-backup policy are already implemented and must be extended rather than replaced.

M2 may become `approved` only after the user explicitly accepts this written specification. The owning core-document conflicts and ADR prerequisite in section 12 must be synchronized before plan approval. M2 may become `in-progress` only after the written spec and plan are explicitly approved, those approvals are recorded, ADR 0017 is accepted, and the user separately authorizes execution of the schema and data migration redline.

## 3. Scope

### 3.1 Goals

M2 delivers:

1. An account-first Finance home backed by real domain reads.
2. Account creation with one atomically owned opening-balance Transaction and AccountEntry.
3. Account detail with a recomputable balance and complete chronological history, including visibly withdrawn history that is excluded from the effective balance.
4. Creation and editing of Income and Expense transactions with occurrence time, one transaction-level memo, and one or more exact first-level Category allocations.
5. Creation and editing of same-currency Transfer transactions with equal-and-opposite effects on two distinct accounts and no Category allocation.
6. A first-install-empty Category catalog with context-specific built-in creation templates, inline create and auto-select, Settings management, rename, archive, and restore.
7. Recoverable withdrawal and restoration of ordinary transactions using the existing `EntityRef.deletedAt` soft-delete fact.
8. Stable typed finance reads, writes, errors, ViewModel state, durable-success navigation, and ambiguous-commit reconciliation.
9. Room schema version 3, recoverable v1/v2 upgrade, logical backup format version 2, backward reading of format version 1, complete round-trip restore, and invalid-candidate rejection.
10. Pure, Room, migration, recovery, backup/restore, ViewModel, Compose, navigation, accessibility, artifact, and offline evidence for every acceptance criterion.

### 3.2 Non-goals

M2 does not:

- implement cross-currency transfer, exchange rates, currency conversion history, or currency conversion bookkeeping;
- reserve exchange-rate fields, empty interfaces, routes, or schema for later work;
- add account rename, currency change, archive, delete, merge, or inter-account opening transfer;
- allow withdrawal or editing of an `OPENING_BALANCE` transaction;
- expose hard delete or physical purge for a Transaction or Category;
- create a compensating/reversal transaction type;
- add category hierarchy, subcategories, category icons, category colors, category notes, seeded persisted categories, or a category income/expense discriminator;
- add merchant, payee, location, receipt, Markdown, or another structured transaction-description field;
- add Tag, Project, Note relation, image attachment, or arbitrary-file controls to a finance editor;
- implement production Timeline UI/projection, unified search, statistics/aggregation, CSV UI/export changes, Backup/Restore UI, App Lock, or diagnostics;
- add another production or test module, a DI framework, a feature-to-feature dependency, a generic Entry abstraction, or a mechanical Use Case layer;
- change the generation layout, activation/recovery-record shape, startup ordering, attachment path/lifecycle, system-backup exclusion, or offline/network policy;
- claim Journey 2's production Timeline visibility, which remains owned by M5 after the stage conflict is corrected as required by section 12.

## 4. User experience

### 4.1 Finance home

Finance home uses the approved account-first layout.

- Accounts are grouped by currency code and each account shows its own recomputable balance.
- The UI never adds unlike currencies into one amount. It does not show a converted or universal total.
- The screen exposes typed Create Account, Expense, Income, and Transfer actions.
- A recent-transactions section shows effective finance transactions only and routes to the Finance-owned Transaction detail.
- A secondary **Withdrawn transactions** entry opens the complete withdrawn list for inspection and restoration.
- A valid empty account list is not a loading failure. It explains that no account exists and exposes Create Account.
- Statistics, category totals, and production Timeline cards do not appear in M2.

### 4.2 Supported currencies and amount input

M2's new-account picker exposes exactly these codes and minor-unit scales:

| Code | Display name | Minor-unit scale |
|---|---|---:|
| `CNY` | 人民币 | 2 |
| `USD` | 美元 | 2 |
| `EUR` | 欧元 | 2 |
| `JPY` | 日元 | 0 |
| `HKD` | 港币 | 2 |
| `GBP` | 英镑 | 2 |

The selected currency is immutable after account creation. Amount input accepts ordinary decimal notation appropriate to the declared scale, rejects exponent notation and excess fractional digits, and converts exactly to integer minor units without floating-point arithmetic. Income, Expense, and Transfer magnitudes are in `1..Long.MAX_VALUE` minor units. Opening balance accepts the complete signed `Long` range, including zero.

Cross-currency conversion is a post-MVP candidate. Adding it later requires a separate product decision, accepted ADR, specification, plan, schema/migration review, and explicit migration approval.

### 4.3 Account and opening balance

Finance → Create Account opens one combined editor with required account name, required currency, and required opening balance. Account-name validation remains exactly the owning product rule: it must be non-blank after trimming; M2 does not invent uniqueness or a new length limit.

The first valid Create press captures the injected current local date-time once as the opening Transaction occurrence time, generates and retains every required ID, then enters `Phase.Saving`. Retry reuses the same captured time and IDs. Account, same-ID Transaction `EntityRef`, `OPENING_BALANCE` Transaction, and unique AccountEntry commit as one operation.

Durable success opens the new `AccountDetail`. The opening record remains visible in history with a fixed localized **Opening balance** description. It has no user memo, edit, withdrawal, or restore action.

### 4.4 Expense and Income editor

A typed entry fixes the Transaction type. New drafts contain required account, positive magnitude, required occurrence date/time, required allocations, and optional transaction memo. An explicit launch context may preselect only the account and occurrence time it owns.

The approved allocation interaction is total-first:

1. The user enters the complete transaction magnitude.
2. The first selected Category initially receives the entire magnitude.
3. Adding another Category adds one allocation row; the user adjusts row amounts.
4. The UI shows exact allocated and remaining minor-unit totals.
5. Save is enabled only when every allocation is positive, each Category appears at most once, and the exact allocation sum equals the transaction magnitude.

Expense stores one negative AccountEntry; Income stores one positive AccountEntry. Allocation amounts are positive absolute values. Editing cannot change the Transaction type. It may change account, magnitude, occurrence time, memo, and available allocations subject to the complete atomic-save and archived-reference rules.

### 4.5 Transfer editor

Transfer requires source account, destination account, positive magnitude, occurrence date/time, and optional transaction memo. Source and destination must exist, differ, and use the same currency. Category controls are absent.

The write owns exactly two non-zero AccountEntry rows: source negative and destination positive with an exact widened sum of zero. Editing cannot change the Transaction type but may change either account, magnitude, occurrence time, or memo. Durable success is impossible until both account histories and balances reflect the same complete commit.

### 4.6 Transaction memo

Income, Expense, and Transfer expose one optional multi-line plain-text memo. Unicode leading and trailing `White_Space` is removed at confirmation; an empty result persists as `null`. The retained value preserves internal newlines and is limited to 500 Unicode code points after trim.

The ViewModel does not silently rewrite the visible draft before confirmation. Markdown, merchant/payee decomposition, per-allocation notes, and Category notes are outside M2.

### 4.7 Category templates and lifecycle

The persisted Category catalog remains empty on a first install. Templates are fixed Finance-owned creation suggestions, not domain facts, database rows, backup records, or separately identifiable catalog items.

Expense templates:

- 餐饮
- 娱乐
- 购物
- 旅游
- 生活缴费
- 交通
- 医疗
- 服饰
- 教育
- 服务
- 其他

Income templates:

- 工资
- 奖金
- 理财收益
- 退款
- 其他收入

Selecting a template performs a normalized-name lookup in the one shared Category namespace:

- no collision: open the ordinary Category create operation with that name;
- active collision: select the existing Category identity without creating another row;
- archived collision: offer Restore; durable Restore returns to the same picker and selects that identity;
- cancellation or failure: create nothing and leave the parent draft unchanged.

After creation, a Category is not permanently typed as income or expense and may be reused in either editor. Transfer never exposes Category. The catalog remains first-level only and supports the ADR-0004 Create, Rename, Archive, and Restore transitions across one active-plus-archived normalized namespace.

### 4.8 Transaction withdrawal and restore

An effective non-opening Transaction detail exposes **Withdraw transaction** with a financial-impact confirmation. Confirmation atomically sets its `EntityRef.deletedAt`; it does not delete Transaction, AccountEntry, CategoryAllocation, or relation rows.

After durable withdrawal:

- every owned AccountEntry becomes ineffective together;
- Income/Expense changes one balance exactly once;
- Transfer removes both account effects together;
- account balance remains the sum of all effective entries;
- account history retains the record with a visible withdrawn state;
- the active recent list excludes it and the withdrawn list includes it;
- its detail is read-only and offers Restore rather than Edit or Withdraw.

Restore clears the same `EntityRef.deletedAt` after confirmation and makes the existing aggregate effective again. Existing allocations targeting a Category archived after the original save remain valid historical references. Restore does not create a new allocation, Category, Transaction, or account effect. An `OPENING_BALANCE` transaction exposes neither operation.

### 4.9 Deterministic navigation

Navigation occurs only after `Phase.Completed`:

| Successful operation | Destination |
|---|---|
| Create Account | New `AccountDetail` |
| Create Income/Expense/Transfer | New `TransactionDetail` |
| Edit ordinary Transaction | Source `TransactionDetail` with its origin retained |
| Withdraw ordinary Transaction | Same `TransactionDetail`, now read-only and withdrawn |
| Restore ordinary Transaction | Same `TransactionDetail`, now effective and editable |
| Inline Category Create/Restore | Same `CategoryPicker`, item auto-selected, parent draft unchanged otherwise |
| Management Category Create/Rename/Archive/Restore | Exact launching Category catalog mode and position |

Failure remains on the owning editor, confirmation, or detail with its safe retained state. No child flow synthesizes a top-level success route.

## 5. Domain and persistence contract

### 5.1 Money and currency types

`:core:model` adds pure Kotlin application/domain values for stable finance IDs, `CurrencyCode`, and `Money`. `Money` carries a declared supported currency and signed `Long amountMinor`. No public money API accepts or returns `Float` or `Double`.

All sign, absolute-magnitude, allocation-sum, transfer-zero-sum, and post-write balance calculations use widened exact arithmetic before converting to a persisted/displayed `Long`. A write whose resulting account balance is outside the signed `Long` range fails atomically. New Expense cannot use `Long.MIN_VALUE` because its positive magnitude is not representable as one M2 amount.

### 5.2 Schema version 3

M2 advances `RecordDatabase` to version 3. The migration adds these logical facts and fields:

| Owner | Field or constraint |
|---|---|
| `Transaction` | non-null `occurredAt` epoch millis UTC; nullable `memo` |
| `Category` | UUID `id`; trimmed `displayName`; `nameKey`; `nameKeyVersion`; `state`; nullable `archivedAt` |
| `CategoryAllocation` | UUID `id`; `transactionId`; `categoryId`; positive `amountMinor` |
| Category namespace | unique `nameKey` across active and archived Category rows |
| Allocation identity | unique `(transactionId, categoryId)` so one Transaction has at most one row per Category |
| Foreign keys | `NO ACTION`; indexes support Transaction-owned and Category-history reads |

Category state is exactly `ACTIVE` or `ARCHIVED`; `archivedAt` is absent/present consistently. Category has no `EntityRef`, parent ID, type discriminator, icon, color, or note. Existing eight tables and their accepted encodings remain otherwise unchanged.

Room constraints provide local structural enforcement where expressible. Repository validation remains mandatory for transaction-type matrices, active-only new allocation, archived unchanged history, state/metadata consistency, exact sums, balance range, and failure classification.

### 5.3 Frozen Category name implementation

ADR-0004's `nameKeyVersion=1` implementation is frozen as:

- Maven coordinate `com.ibm.icu:icu4j:78.3`;
- ICU 78 / Unicode 17 data;
- remove leading/trailing code points having Unicode `White_Space`;
- normalize the trimmed string with NFKC;
- apply locale-independent full string case folding using default CaseFolding mappings;
- persist the resulting Unicode scalar sequence as UTF-8 text.

The selection follows the official [ICU 78.3 release](https://github.com/unicode-org/icu/releases/tag/release-78.3), [Normalizer2 API](https://unicode-org.github.io/icu-docs/apidoc/dev/icu4j/com/ibm/icu/text/Normalizer2.html), [UCharacter full case-folding API](https://unicode-org.github.io/icu-docs/apidoc/released/icu4j/com/ibm/icu/lang/UCharacter.html), and [Unicode License v3](https://github.com/unicode-org/icu/blob/release-78.3/LICENSE). The dependency review must verify Android minSdk-34 compatibility, release packaging, no manifest/network capability, artifact size impact, license notice obligations, maintenance status, and the negative result of substituting runtime `android.icu` data.

Changing or removing ICU4J cannot silently recalculate persisted keys. A future replacement must retain an exact reader for name-key version 1 or perform a separately accepted, collision-checked, recoverable database and logical-backup migration.

### 5.4 Database migration and legacy finance conversion

The existing coordinator publishes and verifies a recoverable snapshot before any version-3 Room open. Version 2 upgrades through `MIGRATION_2_3`; version 1 upgrades through the accepted `1→2→3` chain. Production destructive fallback remains forbidden.

Before mutation, the version-3 migration preflight validates that every existing Account currency is one of M2's supported six and that every existing Income/Expense magnitude can be represented as a positive M2 allocation amount. Unsupported currency or an Expense entry of `Long.MIN_VALUE` fails closed: the old vault and published snapshot remain unchanged, Room and shell remain blocked, and no guessed scale, truncated amount, or partial schema is exposed.

For a compatible legacy database:

1. Existing Transaction `occurredAt` becomes its same-ID `EntityRef.createdAt`.
2. Existing Transaction `memo` becomes `null`.
3. If no existing Income/Expense requires allocation, the Category catalog remains empty.
4. If any requires allocation, migration creates one ordinary active Category named **未分类（迁移）** using the frozen version-1 key pipeline.
5. Each legacy Income/Expense receives exactly one allocation to that Category equal to its entry magnitude.
6. Opening and Transfer transactions receive no allocation.
7. Complete finance, Category, FK, XOR, balance, attachment, and startup invariants are validated before normal Room use.

The generated migration Category and allocation IDs are valid app-generated random UUID strings. A failed Room transaction exposes none of them. Recovery retry begins again from the unchanged validated snapshot and need not reproduce IDs that were never durably published.

### 5.5 Logical backup format version 2

M2 requires a compatibility ADR because adding `occurredAt` and `memo` changes the existing Transaction logical record shape. ADR 0017 must accept the complete schema/migration and backup decision before plan approval.

M2 exports `formatVersion=2` with:

- all eight P5 datasets;
- complete Transaction records including `occurredAt` and nullable `memo`;
- singleton Category and CategoryAllocation JSONL datasets, declared even when their record count is zero;
- Category identity, display/key/version/state/archive fields;
- CategoryAllocation identity and endpoints;
- the existing manifest hash/count/path/resource contracts and encountered name-key-version summary.

The M2 reader supports format versions 1 and 2. A format-1 candidate is imported only into isolated staging and up-converted with the same rules as section 5.4: occurrence from matching EntityRef creation, null memo, and a migration-only uncategorized Category when legacy Income/Expense rows exist. The reader then validates the complete current invariant set before overwrite confirmation. `databaseSchemaVersion` remains provenance and cannot replace format-version dispatch.

Format-2 export/import is strict: missing required datasets or fields, extra/unknown fields, invalid Category state, unsupported/mismatched name-key version, duplicate normalized key, duplicate Transaction/Category pair, non-positive or non-exact allocations, dangling endpoints, balance overflow, or any other current invariant rejects the complete candidate. An allocation targeting an archived Category remains valid historical data and must survive restore unchanged.

M2 does not implement Backup/Restore UI, but it must extend the existing P5 exporter, preflight, staging import, startup revalidation, and automated round trip. A schema slice is incomplete until the new logical facts restore through the real activation/startup path.

## 6. Module ownership and application surfaces

| Module | M2 ownership | Forbidden boundary |
|---|---|---|
| `:app` | Register and connect approved Finance routes; pass the startup-verified repository plus typed navigation callbacks into Finance; expose Settings → Categories | No finance validation, amount parsing, DAO/Entity access, direct repository mutation from composables, or duplicated startup/storage logic |
| `:feature:finance` | Finance screens, ViewModels, immutable UI state, draft parsing, Category templates, user copy, and source-owned navigation intents | No DAO/Room Entity/path, no direct database/file access, no M3+ field or another feature import |
| `:core:model` | Pure finance IDs, `CurrencyCode`, `Money`, enums, and validation-independent public values | No Android, Room, URI, route, UI state, repository, or floating-point money |
| `:core:data` | Schema/migrations, exact validators, typed finance commands/views/errors/Flows, atomic repositories, legacy conversion, backup/restore extension, and reconciliation | No composable/UI state, raw exception leakage, persisted template concept, or second startup/recovery implementation |
| `:core:ui` | Existing theme, shared content states, phase tokens, accessible amount/date primitives needed by more than one feature | No finance fact, repository call, Category template, or product journey |
| `:feature:notes` / `:feature:timeline` | No M2 production change except app-owned route compatibility at their boundaries when required | No finance write or premature Timeline/search/statistics behavior |

`RecordRepository` remains the atomic owner. A narrow strongly typed application surface covers:

- create Account with opening history;
- create/edit Income or Expense aggregate;
- create/edit Transfer aggregate;
- withdraw/restore ordinary Transaction;
- create/rename/archive/restore Category;
- observe Account list and exact balances;
- observe Account detail/history;
- observe Transaction detail and active/withdrawn finance lists;
- observe active/archived Category lists and normalized lookup.

Commands carry stable IDs, expected record revision where applicable, exact integer amounts, and complete requested child sets. They do not expose Room entities or let callers compose DAO writes. Transfer remains a centrally enforced finance operation; no additional mechanical Use Case layer is introduced.

## 7. UI state, concurrency, and failure handling

### 7.1 State ownership

Each Finance flow exposes one immutable `StateFlow<UiState>` and typed intent events. Clock, ZoneId, UUID generation, I/O/CPU dispatchers, and scheduling are injected. Compose renders state and never launches persistence as a composition side effect.

New drafts generate their stable operation/record/child IDs on the first valid confirmation and retain them through recoverable failure. Existing editors start in `Phase.Loading` and do not render partial records as blank fields.

### 7.2 Saving boundary and reconciliation

Valid confirmation transitions `Phase.Idle → Phase.Saving`. During Saving, app-bar Back, system Back, predictive Back, Cancel, Discard, and duplicate confirmation remain on the flow and do not request cancellation.

If infrastructure reports failure after a commit may have occurred, `:core:data` re-reads by the retained stable identity and compares the entire requested aggregate:

- exact durable match: return success once;
- confirmed absence/unchanged prior revision: return recoverable failure with the full draft;
- existing but non-matching state: return a stable blocking conflict without issuing another write.

The UI never guesses success, retries with new IDs, or creates a duplicate after an ambiguous response.

### 7.3 Optimistic concurrency

Transaction Edit, Withdraw, and Restore commands carry the last-read `EntityRef.updatedAt`. Category Rename, Archive, and Restore commands carry the exact last-read persisted Category tuple and update by compare-and-set; M2 does not add a redundant Category revision column. A mismatch rejects the request before mutation and offers reload/review; last-write-wins is forbidden.

An active Category selected in a parent draft may become archived before confirmation. The draft retains and marks it, but a new or changed allocation cannot commit until the user reselects or restores it. An unchanged historical archived allocation may remain. If its amount or endpoint changes, it is a new request and requires an active Category.

### 7.4 Stable errors

The application-facing error taxonomy distinguishes at least:

- field/domain validation;
- normalized-name duplicate with active target;
- archived-name collision with Restore target;
- stale revision conflict;
- atomic transaction failure;
- committed-response reconciliation conflict;
- storage exhausted;
- migration/restore compatibility rejection;
- recovery required;
- unexpected sanitized failure.

Raw SQLite, Room, ICU, file, provider, path, or exception text never reaches UI state. Validation failures do not start persistence. Recoverable persistence failures retain all input. Storage exhaustion and unresolved recovery are blocking until the declared remediation becomes true.

## 8. Navigation contract

M2 extends the M1 typed route allowlist only with routes used by this slice:

- `AccountOpeningEditor`
- `AccountDetail(accountId, origin)`
- `CategoryCatalog(mode, origin)`
- `CategoryPicker(draftId)`
- `CategoryEditor(mode, categoryId?, returnContext)`
- `CatalogArchiveConfirm(categoryId, returnContext)`
- `WithdrawnTransactions(origin)`
- `ExpenseEditor(origin, transactionId?)`
- `IncomeEditor(origin, transactionId?)`
- `TransferEditor(origin, transactionId?)`

`TransactionDetail` retains its stable Transaction ID and origin. Route arguments contain stable IDs, modes, and navigation context only. The complete parent draft remains in the Finance-scoped ViewModel/state holder and is not serialized into the route.

Finance home, Account detail, Transaction detail, Category catalog, and the withdrawn list are stable browse/detail destinations with bottom navigation visible under the owning IA rule. Editors, pickers, Category editor, and archive/withdraw/restore confirmations hide bottom navigation. Tab switching and child round trips retain the Finance list/account/filter/scroll state independently from the other top-level tabs.

## 9. Verification design

### 9.1 Pure and property tests

Pure tests cover:

- six-currency parsing/formatting, scale, zero, one minor unit, maximum values, excess fractions, exponent rejection, and no floating-point API;
- exact sign/cardinality/allocation/transfer/balance rules, including `Long.MIN_VALUE` handling and widened overflow checks;
- one-allocation-per-Category and exact sum;
- 500-code-point memo boundaries and Unicode trimming;
- fixed-seed histories containing create, edit, withdraw, and restore, with every prefix balance equal to the sum of effective entries;
- context-specific templates, active collision, archived collision, and no persisted template identity.

The versioned Category-name corpus freezes ICU4J 78.3 / Unicode 17 behavior over Unicode `White_Space`, NFKC compatibility equivalence, full-fold expansion, locale-sensitive cases under multiple locales, supplementary code points, and 0/1/40/41-code-point boundaries. Expected UTF-8 key bytes are committed as evidence.

### 9.2 Room, migration, recovery, and backup tests

Real Room tests verify:

- exact version-3 schema JSON, tables, fields, indexes, FKs, and unique constraints;
- v1→v2→v3 and v2→v3 fixtures through the recoverable coordinator;
- compatible legacy conversion and migration-only Category creation;
- unsupported legacy currency and `Long.MIN_VALUE` Expense fail closed with unchanged old vault/snapshot;
- atomic account/opening, Income/Expense aggregate, Transfer, edit, Withdraw, Restore, and Category lifecycle writes;
- injected failure before, between, and after every logical child write and commit response;
- observers see only the complete old or complete new aggregate;
- stale revision and Category-archive races mutate nothing;
- format-2 export/restore through activation and startup revalidation;
- format-1 isolated up-conversion;
- active/archived Category history and withdrawn Transaction preservation;
- every invalid Category/allocation/transaction fixture leaves the current-vault hash unchanged and staging deterministically identifiable.

The canonical backup fixture gains active and archived Category rows, renamed historical display, Income/Expense allocations, memos, occurrence times, withdrawn ordinary transactions, and restored transactions. Round-trip comparison includes every logical field, endpoint, revision, lifecycle value, effective balance, attachment fact inherited from P5, and post-restart result.

### 9.3 ViewModel tests

Every flow covers initial state, primary-action enablement, legal transitions, illegal-event rejection, draft retention, configuration recreation, deterministic IDs/time, Saving navigation lock, one terminal result, explicit retry, ambiguous-commit reconciliation, stale revision, Category archival race, and post-Completed navigation.

Read ViewModels distinguish Loading, valid empty, Ready, and Error. Pending writes never optimistically change a balance, history row, detail status, or recent/withdrawn membership.

### 9.4 Compose, navigation, accessibility, and offline tests

On an empty network-isolated install, Compose/navigation tests complete:

1. Account creation with positive, zero, and negative opening values; invalid and storage-failure variants create neither side.
2. Empty Expense picker → template Category creation → auto-select → parent save, plus active/archived collision paths.
3. Income and Expense with one and multiple exact allocations, invalid remainder, edit, and draft retention.
4. Same-currency Transfer success/edit and same-account/different-currency/failure rejection.
5. Transaction Withdraw and Restore, including two-account Transfer visibility and balance atomicity.
6. Settings Category create, rename, archive, historical rendering, Restore, cancellation, duplicate, and storage failure.
7. Account-first home, Account history, active/withdrawn lists, details, Back/origin restoration, tab retention, and configuration recreation.

Required surfaces pass heading/state semantics, focus order, selected state, 48-dp targets, 200% font without clipping, approved M1 contrast/theme behavior, and touch/error accessibility. Every M2 route and retry path works with no network capability.

### 9.5 Structural and artifact gates

Verification retains:

- the exact seven production modules and ADR-0011 test-project allowance;
- no feature-to-feature or core-to-feature edge;
- UI/API scans excluding DAO, Room Entity, physical path, raw exception, and floating-point money;
- public API allowlist and independent negative consumers for every new finance surface;
- no M3+ field, route, interface, or persisted placeholder;
- source/merged/final manifest and APK proof of no `INTERNET` or unexpected network capability;
- source/packaged system-backup and device-transfer exclusions;
- ICU4J coordinate/license/packaging/size/network/removal-path review.

M2 introduces no performance benchmark family. Timeline performance belongs to M5, thumbnail performance to M3, aggregation performance to M5, and startup performance remains under the M1/M6/M7 boundaries. M2 still fails on ANR, OOM, or an unresponsive acceptance journey.

## 10. Acceptance criteria

M2 can become `completed` only when `verification.md` contains accepted raw evidence for every criterion:

1. **Lifecycle and prerequisites:** written spec and plan approvals are recorded, M2 is the unique `in-progress` spec during implementation, ADR 0017 and owning core-document synchronization are accepted, and explicit schema/migration execution approval is recorded before that redline runs.
2. **Module and API boundaries:** the production graph remains exactly seven modules; no forbidden dependency exists; typed finance APIs expose no Room/DAO/path/raw exception/floating-point money; all positive and negative API guards pass.
3. **Money and currency:** exactly six new-account currencies and their scales pass exact parsing/formatting and boundary tests; no unlike-currency total or cross-currency Transfer/conversion path exists.
4. **Account/opening atomicity:** valid account creation durably commits Account plus visible opening history together with captured time/IDs; every invalid/fault/retry case creates both or neither and routes only after durable completion.
5. **Income/Expense aggregate:** one signed entry plus one or more unique positive Category allocations sum exactly to magnitude; create/edit, memo, occurrence, archived-reference, validation, failure, and reconciliation cases pass with no partial visibility.
6. **Transfer aggregate:** two distinct same-currency equal-and-opposite entries commit/edit atomically; every invalid, failure, stale, and ambiguous-response case changes neither account or both exactly once.
7. **Balance reproducibility:** fixed-seed property histories and Room snapshots prove every displayed balance equals the exact sum of effective AccountEntry values after create, edit, Withdraw, Restore, retry, and restart; overflow fails before commit.
8. **Category normalization:** the ICU4J 78.3 / Unicode 17 fixed corpus and properties prove exact version-1 trim/NFKC/full-fold bytes, locale independence, 1–40-code-point validation, and active-plus-archived uniqueness.
9. **Template and empty-catalog flow:** first install persists zero Category rows; every approved template follows create/existing/restore semantics; inline durable success returns to the exact picker and preserves the parent draft; Cancel/Failure writes nothing.
10. **Category lifecycle:** Settings and inline Create, Rename, Archive, Restore, duplicates, archived history, no hard delete, first-level-only shape, storage failure, and active-only new allocation all pass offline.
11. **Transaction memo and occurrence:** occurrence and the one optional 500-code-point plain-text memo persist/edit/restore/backup exactly; no merchant/payee/Markdown/per-allocation/category-note field exists.
12. **Withdraw and Restore:** ordinary Transaction withdrawal/restoration preserves one identity and every child, atomically removes/reapplies all account effects, retains marked history, enforces read-only withdrawn detail, and never permits opening withdrawal or hard delete.
13. **Schema and migration:** exact v3 schema, v1→v2→v3 and v2→v3, snapshot publication, legacy conversion, restart, failure mapping, unsupported-edge fail-closed behavior, no destructive fallback, and unchanged old-vault evidence all pass.
14. **Logical backup:** strict format-2 round trip and format-1 isolated up-conversion preserve/derive every declared M2 field and pass activation/startup revalidation; invalid fixtures leave the current vault unchanged and no incomplete backup/restore is reported successful.
15. **State and navigation:** all phase transitions, Saving locks, draft/ID/time retention, stale-revision handling, exact typed routes, origin/tab state, bottom-navigation visibility, and durable-success destination rules pass.
16. **Finance UI:** the approved account-first home and total-first allocation editor complete the required journeys with truthful loading/empty/error/ready states, no cross-currency sum, and no premature Timeline/statistics claim.
17. **Accessibility:** all M2 production surfaces pass semantic, focus, selected-state, touch-target, 200%-font, clipping, and contrast gates in light/dark M1 themes.
18. **Offline/network/system backup:** every M2 route and required journey passes in network isolation; source/merged/final artifacts add no network capability and retain complete cloud-backup/device-transfer exclusion.
19. **No premature later-slice work:** scans and route/schema/API review find no exchange-rate, account lifecycle, attachment, Tag, Project, Note relation, production Timeline/search/statistics, CSV UI, Backup/Restore UI, or generic Entry placeholder.
20. **Verification record:** actual commands, environment, test counts, property seeds, fixture and corpus versions, hashes, artifact paths, fault points, deviations, and remaining risks are retained; no missing or failed result is waived or relabeled.

## 11. Risks and failure policy

- **Roadmap conflict:** the current M2 row says Journeys 1–3 pass absolutely, while M5 and data/storage own production Timeline. Section 12 correction is blocking before plan approval.
- **Compatibility expansion:** Transaction occurrence/memo cannot be represented by format version 1 without ambiguity. ADR 0017 and format version 2 are blocking; silent optional-field reinterpretation is rejected.
- **Legacy uncategorized transactions:** adding a required allocation invariant without conversion would strand valid P1/P2 facts. The migration-only Category is mandatory when those facts exist.
- **Legacy numeric/currency edge:** `Long.MIN_VALUE` Expense and unsupported account currency cannot be interpreted losslessly under the approved M2 UI model. Migration must fail closed with recovery evidence; it cannot coerce or delete.
- **ICU artifact cost:** full ICU4J adds package weight. The exact release size and R8 result must be recorded before plan approval. Runtime Android ICU is not a fallback because its data version is not a persisted compatibility contract.
- **Ambiguous commit:** a post-commit response fault can duplicate money if Retry changes identity. Stable IDs plus full aggregate reconciliation are mandatory.
- **Withdrawal semantics:** hiding soft-deleted entries from all history would make balance changes unauditable. Account history must retain a marked withdrawn row while active recent lists exclude it.
- **Scope pressure:** account lifecycle, conversion, richer transaction metadata, category decoration, Timeline, and statistics are plausible follow-ups but remain unauthorized candidates.
- **Device availability:** required instrumented, migration, recovery, Compose, artifact, and offline evidence cannot be replaced by a JVM build or screenshot.

Any implementation-discovered contradiction with an owning core document stops the affected task. It is resolved documentation-first, with ADR/spec/plan revision and explicit reapproval where required. A failed technical assumption follows the same-slice revision protocol; a test-infrastructure failure is recorded and repaired without disguising it as a product result.

## 12. Documentation and ADR synchronization

Before the M2 plan may be approved:

1. Update the roadmap M2/M5 wording so M2 owns the finance portion of Journeys 1–3 and M5 owns Journey 2's production Timeline visibility without double ownership.
2. Update MVP requirements only where needed to make the staged Journey 2 visibility assertion explicit; do not weaken the eventual complete journey.
3. Update the information architecture with Withdrawn Transactions and the exact M2 route/return/chrome contracts.
4. Update interaction rules with the one memo field, approved allocation interaction, Withdraw/Restore confirmation and phases, template-create semantics, and exact navigation results.
5. Update the domain model with supported M2 money range, unique Transaction/Category allocation, withdrawal/restore availability, and compatibility treatment of legacy uncategorized transactions.
6. Update data and storage with schema version 3, migration preflight/conversion, frozen ICU implementation, backup format version 2, format-1 up-conversion, and strict validation.
7. Update architecture overview/conventions/testing strategy only for facts they own: typed surface/module flow, deterministic/reconciliation rules, dependency review, fixtures, and gates.
8. Draft and obtain explicit acceptance for ADR 0017 covering schema v3, migration compatibility, normalization implementation/dependency, backup format v2, old-format up-conversion, failure behavior, and rollback/removal consequences.
9. Recheck this spec after the accepted core documents and ADR. Any behavior or acceptance change requires written-spec reapproval.

These synchronization steps are lifecycle/documentation work. They do not authorize creating `plan.md`, modifying schema, running a migration, or implementing routes/interfaces.

## 13. Lifecycle path

1. **`draft` (current):** only this `spec.md` exists. The user reviews the written result and may request changes. No plan or implementation is authorized.
2. **`approved`:** the user explicitly accepts this written spec and the approval date/context is recorded. Section 12 synchronization and ADR work may then proceed while the plan is drafted and reviewed; the plan cannot be approved until they pass.
3. **`in-progress`:** section 12 is complete, ADR 0017 is accepted, the plan is explicitly approved, spec and plan approvals are recorded, the user has separately approved the schema/migration redline, and M2 becomes the unique active specification. Functional work is dispatched under the agentic execution workflow.
4. **`completed`:** every section 10 criterion has accepted raw evidence in `verification.md`, all affected core documents and ADR 0017 remain synchronized, no failed/missing result is waived, and no blocker remains.

Approval of pre-spec design sections does not advance this document from `draft`. Codex generation, self-review, or commit of this file is not user approval.

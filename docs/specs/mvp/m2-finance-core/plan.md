# MVP M2 Finance Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved offline M2 finance core: account-first finance, atomic opening/income/expense/transfer, exact Category allocations and lifecycle, ordinary-transaction edit/Withdraw/Restore, schema-v3 migration, format-v2 logical backup, typed durable UI state, and complete raw evidence.

**Architecture:** `:core:model` owns pure finance values; `:core:data` owns the v3 schema, recoverable migration, format-v2 backup/up-conversion, typed `FinanceRepository`, exact validation, atomic persistence, and commit reconciliation; `:feature:finance` owns immutable ViewModel state and Compose screens; `:app` owns typed route registration, startup-verified repository injection, Settings routing, and shell chrome. The production graph remains seven modules, no feature imports another feature, and every finance fact crosses one repository-owned durable boundary.

**Tech Stack:** AGP 9.3.0, built-in Kotlin 2.2.10, JDK 17.0.20+8, Native Android minSdk 34/targetSdk 34/compileSdk 36, Room 2.8.4 with KSP 2.3.9, ICU4J 78.3 / Unicode 17, Kotlin Coroutines Core/Test 1.9.0, Lifecycle ViewModel Compose 2.10.0, Navigation Compose 2.9.8, Compose BOM 2026.06.00 / Material 3, AndroidX Test UI Automator 2.3.0 for the existing isolated benchmark target, JUnit4, AndroidX Test.

---

- **Status:** `draft` — generated from the approved [spec](spec.md) and accepted [ADR 0017](../../../architecture/decisions/0017-m2-finance-schema-normalization-and-backup-v2.md). This plan is not user-approved and M2 remains inactive.
- **Drafted:** 2026-09-04
- **Independent plan review:** 2026-09-04 — checked spec criteria 1–20, all owning core documents, ADR 0017, task dependencies/write ownership, schema/backup version order, public type/package direction, every typed route, TDD ordering, JDK/device command binding, destructive targets, and raw-evidence acceptance. No blocker remains after the corrections below.
- **Review corrections:** separated repeated dependency-insight invocations; added direct JUnit/UI Automator declarations and the isolated benchmark-package end-to-end path; replaced the dynamic timestamp placeholder with an execution rule; made stable failures direct members of the existing result hierarchy; separated core `FinanceInputField` from feature `FinanceField`; added missing withdrawn/category-editor ViewModels and withdrawn screen; removed the redundant Transaction-only allocation index; made format-2 null memo structurally required; serialized T04/T05; and split raw artifact versus reachable R8/package-size evidence across T01/T02.
- **Spec approved:** 2026-09-04 under the user's delegated independent-review context recorded in [spec.md](spec.md).
- **Architecture prerequisite:** satisfied 2026-09-04 — owning core documents are synchronized and ADR 0017 is Accepted.
- **Plan approval required:** the user must explicitly approve this written plan; Codex generation or self-review is not approval.
- **Schema/migration redline required:** before M2 becomes `in-progress` or M2-T00 activates it, the user must separately authorize implementation of Room schema version 3, `MIGRATION_2_3`, format-v1 up-conversion, and the test migrations described here. Plan approval alone is not that consent.
- **Deletion redline required at device execution:** M2-T09/M2-T10 may install and later uninstall only the named disposable androidTest packages. Any `pm clear`, uninstall, database-file removal, or package-data reset requires an execution-time explanation, exact target list, risk statement, and explicit user approval. Production/debug package data is never a fixture target.
- **Execution revision:** every initial task uses `task_revision = 1`. A behavior or acceptance change revises the spec and plan and requires explicit reapproval before the affected task executes.
- **Reference device:** physical Xiaomi 14 Pro; actual Android/API/build/CPU/RAM, airplane state, package identities, and applicable MIUI app-op state are recorded at device execution.

## Frozen decisions

- New-account currencies are exactly `CNY`, `USD`, `EUR`, `JPY`, `HKD`, and `GBP`; JPY scale is zero and the others are two. Account currency is immutable and unlike currencies are never totaled.
- Amount parsing uses the exact approved ASCII grammar and integer minor units. `Float` and `Double` are forbidden for monetary values.
- Transaction type is immutable. Income/Expense/Transfer use one optional 500-code-point plain-text memo and persisted epoch-millis occurrence.
- Income/Expense has one signed AccountEntry and one or more unique positive Category allocations summing exactly to magnitude. Transfer has two distinct same-currency zero-sum entries and no allocation. Opening has one signed entry and no allocation.
- First install persists zero Categories. Context templates are suggestions only and create/select/restore through the ordinary normalized namespace.
- Category name-key version 1 is ICU4J 78.3 / Unicode 17 `White_Space` trim → NFKC → default full case fold.
- Ordinary Transactions use recoverable Withdraw/Restore through `EntityRef.deletedAt`; Opening exposes neither. Hard delete and compensating transactions are absent.
- Room becomes version 3 only through the existing recoverable coordinator. Legacy non-canonical UUID, unsupported currency, and `Long.MIN_VALUE` Expense fail closed without mutation.
- M2 exports logical backup format 2 and reads formats 1/2. Format 1 converts only inside staging; format 2 is strict.
- M2 does not implement production Timeline/search/statistics, account lifecycle, conversion, attachment, Tag, Project, Note relation, CSV UI, Backup/Restore UI, or another module.

## Execution and evidence rules

- The main agent follows [agentic-workflow.md](../../../engineering/agentic-workflow.md): it owns task order, dispatch contracts, durable `verification.md`, raw-evidence acceptance, integration, and combined gates; it does not write functional implementation.
- Every worker receives a complete task contract with `task_id`, `task_revision`, `attempt_id`, goal, non-goals, exact write scope, dependencies/readiness evidence, acceptance rows, commands, raw-evidence locations, blocker rules, and approval redlines. Workers are not alone in the worktree and never revert or stage unrelated changes.
- All tasks run serially. The shared worktree, build outputs, Room contracts, feature resources, app routes, and verification ledger make parallel compilation/evidence unsafe even where source write scopes differ.
- Every implementation task is test-first: add the focused failing test, run it and retain the expected failure, implement the smallest complete behavior, rerun focused tests, then run the task regression gate before committing.
- All Gradle commands bind the known JDK directly without editing `PATH`, `JAVA_HOME`, `.env`, or `gradle.properties`:

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain check --console=plain
```

- Device commands use `C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Multi-minute Gradle/device suites run in the background and are polled; interruption, timeout, cancellation, or missing XML counts is incomplete evidence.
- Raw test XML stays under each module's `build/test-results` or `build/outputs/androidTest-results`; focused stdout may be retained under `docs/specs/mvp/m2-finance-core/evidence/` only when the plan explicitly names a tracked evidence artifact. Generated APKs, dependency reports, database files, and large logs remain build/local artifacts and are referenced by path/hash.
- A task report uses `DONE`, `DONE_WITH_CONCERNS`, `NEEDS_CONTEXT`, `BLOCKED`, or `FAILED`, and lists exact commands, environment, exit status, test counts, changed files, raw evidence, unresolved risks, and next command.

## Task and dependency map

| Task | Owner/write scope | Depends on | Acceptance criteria |
|---|---|---|---|
| M2-T00 | main agent; lifecycle metadata + `verification.md` | approved plan + schema/migration consent | 1, 20 |
| M2-T01 | version catalog, module build files, `:core:model`, name normalizer, dependency evidence | T00 | 2, 3, 8, 18, 19 |
| M2-T02 | Room entities/DAO/database, migration/coordinator, canonical fixtures | T01 | 4–8, 11–14, 19 |
| M2-T03 | typed finance data surface, atomic operations/reads/reconciliation/API guards | T02 | 2, 4–7, 9–12, 18, 19 |
| M2-T04 | logical backup format 2, format-1 staging conversion, restore/startup validation | T03 | 8, 11–14, 18, 20 |
| M2-T05 | `:feature:finance` immutable states, parsers, templates, ViewModels | T04 | 3–12, 15, 16, 18, 19 |
| M2-T06 | Finance home/account/transaction/withdrawn read UI | T05 | 4, 7, 11, 12, 15–19 |
| M2-T07 | account/transaction editors, Category flows, status confirmation UI | T05, T06 | 3–6, 8–12, 15–19 |
| M2-T08 | `:app` typed navigation, Settings, repository injection, shell chrome/retention | T04, T07 | 1–19 |
| M2-T09 | device Room/migration/fault/backup/API acceptance | T08 + device deletion approval | 2, 4–14, 18–20 |
| M2-T10 | device Compose/navigation/accessibility/offline acceptance | T09 + device deletion/app-op approval as applicable | 3–12, 15–20 |
| M2-T11 | main-agent combined artifact/dependency gates and lifecycle close | T09, T10 | 1–20 |

## Planned file map

### Build and dependency boundary

- Modify `gradle/libs.versions.toml`: `icu4j=78.3`, `coroutines=1.9.0`, and aliases for ICU4J, Coroutines Core/Test, and Lifecycle ViewModel Compose 2.10.0.
- Modify `core/model/build.gradle.kts`: direct JUnit test dependency for the new JVM value tests.
- Modify `core/data/build.gradle.kts`: ICU4J, direct Coroutines Core, Coroutines Test, migration test fixtures, and dependency-evidence task inputs.
- Modify `feature/finance/build.gradle.kts`: direct `:core:model`, `:core:data`, Lifecycle ViewModel Compose, Coroutines Core/Test, and existing Compose test dependencies.
- Modify `benchmark/build.gradle.kts`: declare the already resolved UI Automator 2.3.0 dependency directly for M2's isolated end-to-end journey.
- Create `tools/verify-m2-artifacts.ps1`: independent frozen M2 verifier; never modify the completed M1 frozen verifier.

### `:core:model`

- Create `core/model/src/main/java/com/paherdin/record/model/FinanceIds.kt`: canonical UUID value classes.
- Create `core/model/src/main/java/com/paherdin/record/model/CurrencyCode.kt`: six codes and scales.
- Create `core/model/src/main/java/com/paherdin/record/model/Money.kt`: signed integer minor-unit value.
- Create `core/model/src/main/java/com/paherdin/record/model/CategoryState.kt` and `TransactionStatus.kt`.
- Create focused tests under `core/model/src/test/java/com/paherdin/record/model/`.

### `:core:data` finance and Room

- Create `core/data/src/main/java/com/paherdin/record/core/data/finance/CategoryNameNormalizer.kt`.
- Create `core/data/src/main/java/com/paherdin/record/core/data/finance/FinanceContracts.kt`.
- Create `core/data/src/main/java/com/paherdin/record/core/data/finance/FinanceRepository.kt`.
- Create `core/data/src/main/java/com/paherdin/record/core/data/finance/RoomFinanceRepository.kt`.
- Create `core/data/src/main/java/com/paherdin/record/core/data/finance/FinanceAggregateValidator.kt`.
- Create `core/data/src/main/java/com/paherdin/record/core/data/finance/FinanceQueries.kt` for mapping Room snapshots into application views.
- Modify `core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt`: expose one binding-safe `finance` surface and internal shared-gate binding accessor; internalize raw finance write entries that would bypass M2 invariants.
- Modify `core/data/src/main/java/com/paherdin/record/core/data/RepositoryResult.kt`: stable duplicate/stale/reconciliation/compatibility outcomes only.
- Modify `core/data/src/main/java/com/paherdin/record/core/data/Model.kt`: retain only internal legacy/test DTOs; no second public finance surface.
- Create `core/data/src/main/java/com/paherdin/record/core/data/room/Category.kt` and `CategoryAllocation.kt`.
- Modify `core/data/src/main/java/com/paherdin/record/core/data/room/Transaction.kt`, `RecordDao.kt`, and `RecordDatabase.kt`.
- Create `core/data/src/main/java/com/paherdin/record/core/data/room/Migration2To3.kt` and `M2MigrationPreflight.kt`.
- Modify `core/data/src/main/java/com/paherdin/record/core/data/restore/GenerationDatabase.kt`, `StagedGenerationValidator.kt`, `RestoreStager.kt`, and startup/migration wiring that registers the exact chain.
- Modify `core/data/apiGuard/positive/*`, create focused negative fixtures, and update `ApiSurfaceTest.kt` expected signatures.

### Logical backup and fixtures

- Modify `core/data/src/main/java/com/paherdin/record/core/data/backup/LogicalBackupSchema.kt`, `Manifest.kt`, and `BackupExporter.kt`.
- Create `core/data/src/main/java/com/paherdin/record/core/data/backup/LogicalBackupV1Upconverter.kt`.
- Modify `core/data/src/main/java/com/paherdin/record/core/data/restore/CatalogContractChecker.kt`, `RestorePreflight.kt`, `RestoreStager.kt`, `StagedGenerationValidator.kt`, and `StagingReceipt.kt`.
- Modify canonical test fixtures under `core/data/src/testFixtures` and affected tests to use canonical UUIDs and format-aware records.
- Create focused pure/device tests named in M2-T02 through M2-T04.

### `:feature:finance`

- Replace M1 availability content inside existing `FinanceScreens.kt`/`FinanceUiModel.kt` with focused files while preserving source compatibility until app integration.
- Create `AmountTextParser.kt`, `OccurrenceResolver.kt`, `FinanceTemplates.kt`, and `FinanceUiModels.kt`.
- Create `FinanceHomeViewModel.kt`, `WithdrawnTransactionsViewModel.kt`, `AccountOpeningViewModel.kt`, `AccountDetailViewModel.kt`, `TransactionDetailViewModel.kt`, `TransactionEditorViewModel.kt`, `CategoryCatalogViewModel.kt`, `CategoryPickerViewModel.kt`, `CategoryEditorViewModel.kt`, and `TransactionStatusViewModel.kt`.
- Create `FinanceHomeScreen.kt`, `WithdrawnTransactionsScreen.kt`, `AccountScreens.kt`, `TransactionDetailScreen.kt`, `TransactionEditorScreen.kt`, `CategoryScreens.kt`, and `TransactionStatusConfirmScreen.kt`.
- Modify `feature/finance/src/main/res/values/strings.xml`; create focused unit/android tests under matching packages.

### `:app`

- Modify `app/src/main/java/com/paherdin/record/navigation/M1Routes.kt`: retain the file name for compatibility but expand the approved typed route allowlist only.
- Modify `app/src/main/java/com/paherdin/record/navigation/RecordNavHost.kt`, `app/src/main/java/com/paherdin/record/ui/RecordShell.kt`, `app/src/main/java/com/paherdin/record/ui/SettingsScreen.kt`, and startup-ready composition wiring.
- Modify `app/src/main/res/values/strings.xml` only for app-owned Settings/navigation copy.
- Modify/create route, integration, retention, accessibility, and offline tests under `app/src/test` and `app/src/androidTest`.

### Isolated end-to-end journey

- Create `benchmark/src/main/kotlin/com/paherdin/record/benchmark/M2FinanceJourneyTest.kt`: UI Automator journey against the existing `com.paherdin.record.benchmark` target only.

---

## Frozen public type contract

Tasks use these names and shapes. A worker that needs a different public shape stops and reports a spec/plan conflict rather than silently inventing another API.

```kotlin
// :core:model
@JvmInline value class AccountId(val value: String)
@JvmInline value class TransactionId(val value: String)
@JvmInline value class AccountEntryId(val value: String)
@JvmInline value class CategoryId(val value: String)
@JvmInline value class CategoryAllocationId(val value: String)

enum class CurrencyCode(val scale: Int) {
    CNY(2), USD(2), EUR(2), JPY(0), HKD(2), GBP(2)
}

data class Money(val currency: CurrencyCode, val amountMinor: Long)
enum class CategoryState { ACTIVE, ARCHIVED }
enum class TransactionStatus { EFFECTIVE, WITHDRAWN }
```

Each ID constructor validates canonical lowercase UUID text through one shared pure helper. Room persists `.value`; routes carry canonical String values and reconstruct the wrappers at feature/data boundaries.

```kotlin
// :core:data application contract
enum class FinanceInputField {
    ACCOUNT, DESTINATION_ACCOUNT, AMOUNT, OCCURRED_AT, MEMO, ALLOCATIONS, CATEGORY_NAME
}

data class AllocationDraft(
    val id: CategoryAllocationId,
    val categoryId: CategoryId,
    val amountMinor: Long,
)

data class CreateAccountCommand(
    val accountId: AccountId,
    val transactionId: TransactionId,
    val entryId: AccountEntryId,
    val name: String,
    val currency: CurrencyCode,
    val openingAmountMinor: Long,
    val occurredAt: Long,
)

data class SaveIncomeExpenseCommand(
    val transactionId: TransactionId,
    val entryId: AccountEntryId,
    val expectedUpdatedAt: Long?,
    val type: TransactionType,
    val accountId: AccountId,
    val magnitude: Money,
    val occurredAt: Long,
    val memo: String?,
    val allocations: List<AllocationDraft>,
)

data class SaveTransferCommand(
    val transactionId: TransactionId,
    val sourceEntryId: AccountEntryId,
    val destinationEntryId: AccountEntryId,
    val expectedUpdatedAt: Long?,
    val sourceAccountId: AccountId,
    val destinationAccountId: AccountId,
    val magnitude: Money,
    val occurredAt: Long,
    val memo: String?,
)

data class CategorySnapshot(
    val id: CategoryId,
    val displayName: String,
    val nameKey: String,
    val nameKeyVersion: Int,
    val state: CategoryState,
    val archivedAt: Long?,
)

data class CreateCategoryCommand(val id: CategoryId, val proposedName: String)
data class RenameCategoryCommand(
    val expected: CategorySnapshot,
    val proposedName: String,
)
data class ChangeCategoryStateCommand(
    val expected: CategorySnapshot,
    val target: CategoryState,
)
data class ChangeTransactionStatusCommand(
    val transactionId: TransactionId,
    val expectedUpdatedAt: Long,
    val target: TransactionStatus,
)
```

`expectedUpdatedAt = null` means create and requires every supplied identity to be unoccupied. Non-null means edit and requires the same Transaction plus complete prior aggregate. `SaveIncomeExpenseCommand.type` accepts only `INCOME` or `EXPENSE`; raw signed AccountEntry lists never cross this interface.

```kotlin
interface FinanceRepository {
    fun observeHome(): Flow<FinanceHomeView>
    fun observeAccount(id: AccountId): Flow<AccountDetailView?>
    fun observeTransaction(id: TransactionId): Flow<TransactionDetailView?>
    fun observeWithdrawn(): Flow<List<TransactionSummaryView>>
    fun observeCategories(state: CategoryState): Flow<List<CategorySnapshot>>
    suspend fun findCategoryByName(proposedName: String): RepositoryResult<CategorySnapshot?>
    suspend fun createAccount(command: CreateAccountCommand): RepositoryResult<AccountId>
    suspend fun saveIncomeExpense(command: SaveIncomeExpenseCommand): RepositoryResult<TransactionId>
    suspend fun saveTransfer(command: SaveTransferCommand): RepositoryResult<TransactionId>
    suspend fun changeTransactionStatus(command: ChangeTransactionStatusCommand): RepositoryResult<TransactionId>
    suspend fun createCategory(command: CreateCategoryCommand): RepositoryResult<CategoryId>
    suspend fun renameCategory(command: RenameCategoryCommand): RepositoryResult<CategoryId>
    suspend fun changeCategoryState(command: ChangeCategoryStateCommand): RepositoryResult<CategoryId>
}
```

The immutable application views use these exact shapes:

```kotlin
data class AccountSummaryView(
    val id: AccountId,
    val name: String,
    val currency: CurrencyCode,
    val balance: Money,
)

data class AccountEffectView(
    val account: AccountSummaryView,
    val amount: Money,
)

data class CategoryAllocationView(
    val id: CategoryAllocationId,
    val category: CategorySnapshot,
    val amountMinor: Long,
)

data class TransactionSummaryView(
    val id: TransactionId,
    val type: TransactionType,
    val status: TransactionStatus,
    val occurredAt: Long,
    val memo: String?,
    val effects: List<AccountEffectView>,
    val updatedAt: Long,
)

data class TransactionDetailView(
    val summary: TransactionSummaryView,
    val allocations: List<CategoryAllocationView>,
)

data class AccountDetailView(
    val account: AccountSummaryView,
    val history: List<TransactionSummaryView>,
)

data class FinanceHomeView(
    val accounts: List<AccountSummaryView>,
    val recentEffective: List<TransactionSummaryView>,
    val withdrawnCount: Int,
)
```

They expose no Room entity, DAO, path, URI, raw exception, or attachment representation.

## Task M2-T00: Activate the approved slice and initialize durable execution state

**Owner:** main agent only. **Dispatch tier:** not dispatched; lifecycle and shared verification ownership.

**Files:**
- Modify: `docs/specs/mvp/m2-finance-core/spec.md`
- Modify: `docs/specs/mvp/m2-finance-core/plan.md`
- Create: `docs/specs/mvp/m2-finance-core/verification.md`
- Modify: `AGENTS.md`
- Modify: `docs/index.md`
- Modify: `docs/roadmap.md`
- Modify: `README.md`

- [ ] **Step 1: Verify the two missing approvals without changing files**

Read the recorded spec approval, accepted ADR 0017, this complete plan, the user's explicit plan approval, and the user's explicit schema/migration execution consent. Scan all specs for `Status: in-progress`.

Run:

```powershell
Get-ChildItem 'docs\specs' -Recurse -Filter 'spec.md' |
  Select-String -Pattern '\*\*Status:\*\* `in-progress`'
```

Expected before activation: no matches. If either approval is absent or another match exists, leave M2 `approved`, report `BLOCKED`, and do not dispatch implementation.

- [ ] **Step 2: Create the verification ledger before functional dispatch**

Create `verification.md` with this exact top-level structure and all 20 rows initialized `PENDING`:

```markdown
# MVP M2 — Finance Core Verification

## Execution pointer

- current task: M2-T00
- task revision: 1
- attempt: M2-T00-A1
- owner/write scope: main agent; lifecycle documents and this ledger
- dependencies: approved spec, accepted ADR 0017, approved plan, schema/migration consent
- status: in_progress
- dispatch failures: 0
- blocked-on: none
- next command: lifecycle consistency scan
- last raw evidence: pending

## Acceptance criteria

| Criterion | Status | Accepted evidence |
|---:|---|---|
| 1 | PENDING | — |
| 2 | PENDING | — |
| 3 | PENDING | — |
| 4 | PENDING | — |
| 5 | PENDING | — |
| 6 | PENDING | — |
| 7 | PENDING | — |
| 8 | PENDING | — |
| 9 | PENDING | — |
| 10 | PENDING | — |
| 11 | PENDING | — |
| 12 | PENDING | — |
| 13 | PENDING | — |
| 14 | PENDING | — |
| 15 | PENDING | — |
| 16 | PENDING | — |
| 17 | PENDING | — |
| 18 | PENDING | — |
| 19 | PENDING | — |
| 20 | PENDING | — |

## Task log

| Task | Revision | Attempt | Owner | State | Dispatch failures | Evidence | Acceptance |
|---|---:|---|---|---|---:|---|---|
| M2-T00 | 1 | M2-T00-A1 | main | in_progress | 0 | pending | pending |

## Commands and results

## Deviations and remaining risk
```

In the same `apply_patch` edit, add `last updated` using the actual value returned by `(Get-Date).ToString('o')`; never copy an example timestamp or defer the field.

- [ ] **Step 3: Mark M2 uniquely active**

Change spec and plan status to `in-progress`; record plan approval and schema/migration consent date/context; update AGENTS/index/roadmap to identify M2 as the unique active spec. Do not mark any acceptance row PASS yet.

- [ ] **Step 4: Run lifecycle and diff checks**

Run:

```powershell
git diff --check
Get-ChildItem 'docs\specs' -Recurse -Filter 'spec.md' |
  Select-String -Pattern '\*\*Status:\*\* `in-progress`'
```

Expected: whitespace check passes; exactly one match, M2 `spec.md`.

- [ ] **Step 5: Commit activation**

```powershell
git add -- AGENTS.md README.md docs/index.md docs/roadmap.md docs/specs/mvp/m2-finance-core/spec.md docs/specs/mvp/m2-finance-core/plan.md docs/specs/mvp/m2-finance-core/verification.md
git commit -m "docs: activate MVP M2 finance core"
```

## Task M2-T01: Freeze dependencies, finance values, amount parsing, and name-key version 1

**Owner:** one worker. **Dispatch tier:** full contract because it changes shared build contracts and gates every later task.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `core/model/build.gradle.kts`
- Modify: `core/data/build.gradle.kts`
- Modify: `feature/finance/build.gradle.kts`
- Modify: `benchmark/build.gradle.kts`
- Create: `core/model/src/main/java/com/paherdin/record/model/FinanceIds.kt`
- Create: `core/model/src/main/java/com/paherdin/record/model/CurrencyCode.kt`
- Create: `core/model/src/main/java/com/paherdin/record/model/Money.kt`
- Create: `core/model/src/main/java/com/paherdin/record/model/CategoryState.kt`
- Create: `core/model/src/main/java/com/paherdin/record/model/TransactionStatus.kt`
- Create: `core/data/src/main/java/com/paherdin/record/core/data/finance/CategoryNameNormalizer.kt`
- Create: `feature/finance/src/main/java/com/paherdin/record/feature/finance/AmountTextParser.kt`
- Create: `feature/finance/src/main/java/com/paherdin/record/feature/finance/OccurrenceResolver.kt`
- Create: `core/model/src/test/java/com/paherdin/record/model/FinanceValuesTest.kt`
- Create: `core/data/src/test/java/com/paherdin/record/core/data/finance/CategoryNameNormalizerTest.kt`
- Create: `feature/finance/src/test/java/com/paherdin/record/feature/finance/AmountTextParserTest.kt`
- Create: `feature/finance/src/test/java/com/paherdin/record/feature/finance/OccurrenceResolverTest.kt`
- Create: `docs/specs/mvp/m2-finance-core/evidence/dependency-review.md`

- [ ] **Step 1: Write failing pure tests for IDs, currencies, money, amount grammar, occurrence, and normalization**

Tests must assert:

```kotlin
assertEquals(0, CurrencyCode.JPY.scale)
assertEquals(setOf(2), (CurrencyCode.entries - CurrencyCode.JPY).map { it.scale }.toSet())
assertEquals(1234L, AmountTextParser.parseMagnitude("12.34", CurrencyCode.CNY).getOrThrow())
assertEquals(-1234L, AmountTextParser.parseOpening("-12.34", CurrencyCode.CNY).getOrThrow())
assertEquals(12L, AmountTextParser.parseMagnitude("12", CurrencyCode.JPY).getOrThrow())
listOf("1e2", "+1", ".5", "1.", "1,000", "1.234").forEach {
    assertTrue(AmountTextParser.parseMagnitude(it, CurrencyCode.CNY).isFailure)
}
assertTrue(AmountTextParser.parseMagnitude("0", CurrencyCode.CNY).isFailure)
assertTrue(runCatching { AccountId("A") }.isFailure)
assertEquals(uuid, AccountId(uuid).value)
```

Occurrence tests bind `America/New_York`: `2026-03-08T02:30` is rejected as a gap; `2026-11-01T01:30` selects the earlier valid offset. Opening uses the injected `Clock.instant()` directly.

Normalization tests enumerate Unicode 17 `White_Space` code points `0009..000D, 0020, 0085, 00A0, 1680, 2000..200A, 2028, 2029, 202F, 205F, 3000`, and assert exact results for full-width text, ligatures, Kelvin sign, `Straße`, Greek sigma forms, dotted I default folding, Deseret supplementary letters, and 0/1/40/41 trimmed code points. Expected keys are literal test values, not computed by the implementation under test.

- [ ] **Step 2: Run the focused tests and retain the expected compile failures**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:model:test :core:data:testDebugUnitTest :feature:finance:testDebugUnitTest --console=plain
```

Expected: FAIL because the new types/parsers/normalizer and dependencies do not exist. Record the failing task names in `verification.md`.

Before adding the dependency entries, build the unchanged release APK once and record its path, SHA-256, and byte count as the M2 package-size baseline:

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :app:assembleRelease --console=plain
Get-FileHash 'app\build\outputs\apk\release\app-release-unsigned.apk' -Algorithm SHA256
(Get-Item 'app\build\outputs\apk\release\app-release-unsigned.apk').Length
```

- [ ] **Step 3: Add the exact version-catalog entries and direct module dependencies**

Add:

```toml
[versions]
icu4j = "78.3"
coroutines = "1.9.0"
androidxTestUiAutomator = "2.3.0"

[libraries]
icu4j = { group = "com.ibm.icu", name = "icu4j", version.ref = "icu4j" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-test-uiautomator = { group = "androidx.test.uiautomator", name = "uiautomator", version.ref = "androidxTestUiAutomator" }
```

`core:model` adds JUnit for JVM tests. `core:data` uses ICU4J and Coroutines Core directly plus Coroutines Test in unit tests. `feature:finance` adds direct `:core:model`, `:core:data`, Coroutines Core, Lifecycle ViewModel Compose, and Coroutines Test. `:benchmark` declares its already-transitive UI Automator 2.3.0 directly. Do not change existing version values or add a DI/testing framework.

- [ ] **Step 4: Implement the pure values and parsers**

Use one shared ID validator:

```kotlin
internal fun requireCanonicalUuid(raw: String): String {
    val canonical = java.util.UUID.fromString(raw).toString()
    require(raw == canonical) { "ID must be canonical lowercase UUID text" }
    return raw
}
```

Every ID value class calls it in `init`. `AmountTextParser` trims through the same Unicode White_Space predicate, accepts only the frozen grammar, constructs minor units by checked digit arithmetic, and returns a typed parse failure rather than `NumberFormatException`. `OccurrenceResolver` uses `ZoneRules.getValidOffsets`: zero is gap failure, one is that offset, two selects index zero.

Implement name normalization exactly:

```kotlin
internal class CategoryNameNormalizer {
    private val nfkc = com.ibm.icu.text.Normalizer2.getNFKCInstance()

    fun normalize(raw: String): NormalizedCategoryName {
        val display = raw.trimUnicodeWhiteSpace()
        val count = display.codePointCount(0, display.length)
        require(count in 1..40)
        val key = com.ibm.icu.lang.UCharacter.foldCase(
            nfkc.normalize(display),
            com.ibm.icu.lang.UCharacter.FOLD_CASE_DEFAULT,
        )
        return NormalizedCategoryName(display, key, 1)
    }
}
```

The trim helper iterates code points and uses `UCharacter.hasBinaryProperty(codePoint, UProperty.WHITE_SPACE)`; it must not use `String.trim`, locale lowercase, or `android.icu`.

- [ ] **Step 5: Run focused tests and dependency insight**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:model:test :core:data:testDebugUnitTest :feature:finance:testDebugUnitTest --console=plain
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :feature:finance:dependencyInsight --dependency icu4j --configuration releaseRuntimeClasspath --console=plain
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :feature:finance:dependencyInsight --dependency kotlinx-coroutines-core --configuration releaseRuntimeClasspath --console=plain
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :feature:finance:dependencyInsight --dependency lifecycle-viewmodel-compose --configuration releaseRuntimeClasspath --console=plain
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :benchmark:dependencyInsight --dependency uiautomator --configuration benchmarkRuntimeClasspath --console=plain
```

Expected: all tests PASS; ICU4J resolves exactly 78.3, Coroutines exactly 1.9.0, Lifecycle ViewModel Compose exactly 2.10.0, and UI Automator exactly 2.3.0.

- [ ] **Step 6: Record and enforce the dependency entry gate**

`dependency-review.md` records official source/license URLs, Maven coordinate, resolved artifact path/SHA-256/bytes, Unicode 17 binding, maintenance release date, Java/Android compatibility result, direct/transitive graph, manifest/network scan, expected removal rule, the unchanged release baseline, and T01's preliminary post-dependency APK bytes. T02 appends the reachable migration-path R8/package result after version-3 code references the normalizer. Fail T01 on any network permission/component, telemetry SDK, license incompatibility, unresolved artifact, or Android/JVM incompatibility.

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :app:assembleRelease checkModuleDependencies --console=plain
```

Expected: PASS; every T01-owned review field contains an actual value, and the T02 reachable-size row is explicitly assigned to M2-T02 rather than represented by a blank marker. T02 is blocked until the main agent accepts this evidence.

- [ ] **Step 7: Commit the dependency/value gate**

```powershell
git add -- gradle/libs.versions.toml core/model/build.gradle.kts core/model/src/main/java/com/paherdin/record/model/FinanceIds.kt core/model/src/main/java/com/paherdin/record/model/CurrencyCode.kt core/model/src/main/java/com/paherdin/record/model/Money.kt core/model/src/main/java/com/paherdin/record/model/CategoryState.kt core/model/src/main/java/com/paherdin/record/model/TransactionStatus.kt core/model/src/test/java/com/paherdin/record/model/FinanceValuesTest.kt core/data/build.gradle.kts core/data/src/main/java/com/paherdin/record/core/data/finance/CategoryNameNormalizer.kt core/data/src/test/java/com/paherdin/record/core/data/finance/CategoryNameNormalizerTest.kt feature/finance/build.gradle.kts feature/finance/src/main/java/com/paherdin/record/feature/finance/AmountTextParser.kt feature/finance/src/main/java/com/paherdin/record/feature/finance/OccurrenceResolver.kt feature/finance/src/test/java/com/paherdin/record/feature/finance/AmountTextParserTest.kt feature/finance/src/test/java/com/paherdin/record/feature/finance/OccurrenceResolverTest.kt benchmark/build.gradle.kts docs/specs/mvp/m2-finance-core/evidence/dependency-review.md
git commit -m "feat(finance): freeze money and category name contracts"
```

## Task M2-T02: Add schema version 3 and the recoverable legacy conversion

**Owner:** one worker. **Dispatch tier:** full contract; schema redline and shared persistence surface.

**Files:**
- Create/modify the Room and migration files listed in the planned file map
- Modify canonical startup/restore test fixtures that must open version 3
- Create: `core/data/src/test/java/com/paherdin/record/core/data/SchemaV3ContractTest.kt`
- Create: `core/data/src/androidTest/java/com/paherdin/record/core/data/room/Migration2To3Test.kt`
- Create: `core/data/src/androidTest/java/com/paherdin/record/core/data/room/M2MigrationRecoveryTest.kt`
- Modify: `docs/specs/mvp/m2-finance-core/evidence/dependency-review.md`

- [ ] **Step 1: Write failing schema and migration tests**

`SchemaV3ContractTest` parses the exported JSON and asserts exactly ten entities, version 3, Transaction `occurredAt` non-null plus nullable `memo`, Category fields/unique key, CategoryAllocation fields/FKs/indexes/unique pair, and unchanged prior table encodings. Device migration fixtures cover:

```kotlin
data class LegacyCase(
    val sourceVersion: Int,
    val hasIncomeExpense: Boolean,
    val expectedCategoryCount: Int,
    val expectedAllocationCount: Int,
)

val positiveCases = listOf(
    LegacyCase(1, false, 0, 0),
    LegacyCase(1, true, 1, 2),
    LegacyCase(2, false, 0, 0),
    LegacyCase(2, true, 1, 2),
)
```

Every compatible Transaction expects `occurredAt == EntityRef.createdAt`, `memo == null`, exact allocation magnitude, and a normalized **未分类（迁移）** record only when needed. Negative fixtures independently cover non-canonical entity/child/FK IDs, each unsupported currency position, and `Long.MIN_VALUE` Expense. Each asserts unchanged source/snapshot hashes and no v3 Room/shell-ready result.

- [ ] **Step 2: Run focused tests to prove the missing migration**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:data:testDebugUnitTest :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.Migration2To3Test,com.paherdin.record.core.data.room.M2MigrationRecoveryTest" --console=plain
```

Expected: schema test and device classes FAIL because version 3/migration is absent. If the device is unavailable, retain the host failure and mark device evidence pending rather than passing.

- [ ] **Step 3: Add the exact Room entities and database registration**

Implement:

```kotlin
@Entity(indices = [Index(value = ["nameKey"], unique = true)])
internal data class Category(
    @PrimaryKey val id: String,
    val displayName: String,
    val nameKey: String,
    val nameKeyVersion: Int,
    val state: CategoryState,
    val archivedAt: Long?,
)

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index("categoryId"),
        Index(value = ["transactionId", "categoryId"], unique = true),
    ],
)
internal data class CategoryAllocation(
    @PrimaryKey val id: String,
    val transactionId: String,
    val categoryId: String,
    val amountMinor: Long,
)
```

Explicitly set both FK actions to `NO_ACTION`; ensure `id` is the declared primary key in the Room shape accepted by schema JSON. Extend Transaction with `occurredAt: Long` and `memo: String?`, set database version 3, and register both migrations in every production/test generation builder.

- [ ] **Step 4: Implement preflight and `MIGRATION_2_3`**

`M2MigrationPreflight` opens the old database read-only before mutation, validates canonical UUIDs and FK endpoints, supported currency codes, legacy finance matrix, balance range, and representable Income/Expense magnitude. `Migration2To3` executes one Room migration transaction: add columns with deterministic backfill, create Category/allocation tables and indexes, conditionally generate the migration Category/allocations, then validate. It never catches a violation and continues.

The migration fault hook has named boundaries:

```kotlin
enum class M2MigrationPoint {
    PREFLIGHT_COMPLETE,
    TRANSACTION_TABLE_REBUILT,
    CATEGORY_TABLE_CREATED,
    ALLOCATION_TABLE_CREATED,
    LEGACY_CATEGORY_INSERTED,
    LEGACY_ALLOCATION_INSERTED,
    FINAL_VALIDATION_COMPLETE,
}
```

Every point runs fail-once/restart, fail-repeatedly, and complete/repeat through the existing snapshot/recovery coordinator.

- [ ] **Step 5: Regenerate and verify schema JSON without editing prior schemas**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:data:kspDebugKotlin :core:data:testDebugUnitTest --console=plain
```

Expected: `core/data/schemas/.../3.json` is created; `1.json` and `2.json` hashes are unchanged; schema tests PASS.

Build the release APK with the now-reachable migration/normalizer path, record SHA-256/bytes and delta from T01's unchanged baseline, inspect merged/final network capability, and append those actual values to `dependency-review.md`:

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :app:assembleRelease --console=plain
Get-FileHash 'app\build\outputs\apk\release\app-release-unsigned.apk' -Algorithm SHA256
(Get-Item 'app\build\outputs\apk\release\app-release-unsigned.apk').Length
```

Expected: actual reachable size/delta recorded; no network/telemetry component; no unexplained artifact growth. T03 is blocked until the main agent accepts the completed dependency gate.

- [ ] **Step 6: Run focused device migration/recovery tests**

Run the exact class-filtered command from Step 2. Expected: all named tests PASS with XML test/failure counts retained. Then run:

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.notClass=com.paherdin.record.core.data.search.SearchBenchmarkTest" --console=plain
```

Expected: all non-measurement core-data device regressions PASS; the P4 measured benchmark is excluded because its frozen thermal protocol is unrelated to M2.

- [ ] **Step 7: Commit schema and migration**

```powershell
git add -- core/data/src/main/java/com/paherdin/record/core/data/room/Category.kt core/data/src/main/java/com/paherdin/record/core/data/room/CategoryAllocation.kt core/data/src/main/java/com/paherdin/record/core/data/room/Transaction.kt core/data/src/main/java/com/paherdin/record/core/data/room/RecordDao.kt core/data/src/main/java/com/paherdin/record/core/data/room/RecordDatabase.kt core/data/src/main/java/com/paherdin/record/core/data/room/Migration2To3.kt core/data/src/main/java/com/paherdin/record/core/data/room/M2MigrationPreflight.kt core/data/src/main/java/com/paherdin/record/core/data/restore/GenerationDatabase.kt core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt core/data/src/test/java/com/paherdin/record/core/data/SchemaV3ContractTest.kt core/data/src/androidTest/java/com/paherdin/record/core/data/room/Migration2To3Test.kt core/data/src/androidTest/java/com/paherdin/record/core/data/room/M2MigrationRecoveryTest.kt core/data/schemas/com.paherdin.record.core.data.room.RecordDatabase/3.json docs/specs/mvp/m2-finance-core/evidence/dependency-review.md
git commit -m "feat(data): add recoverable finance schema v3"
```

## Task M2-T03: Implement the typed finance repository and atomic aggregate operations

**Owner:** one worker. **Dispatch tier:** full contract; shared stable application interface and `RecordRepository` integration.

**Files:**
- Create: `core/data/src/main/java/com/paherdin/record/core/data/finance/FinanceContracts.kt`
- Create: `core/data/src/main/java/com/paherdin/record/core/data/finance/FinanceRepository.kt`
- Create: `core/data/src/main/java/com/paherdin/record/core/data/finance/RoomFinanceRepository.kt`
- Create: `core/data/src/main/java/com/paherdin/record/core/data/finance/FinanceAggregateValidator.kt`
- Create: `core/data/src/main/java/com/paherdin/record/core/data/finance/FinanceQueries.kt`
- Modify: `core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt`
- Modify: `core/data/src/main/java/com/paherdin/record/core/data/RepositoryResult.kt`
- Modify: `core/data/src/main/java/com/paherdin/record/core/data/Model.kt`
- Modify: `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDao.kt`
- Create: `core/data/src/test/java/com/paherdin/record/core/data/finance/FinanceAggregateValidatorTest.kt`
- Create: `core/data/src/androidTest/java/com/paherdin/record/core/data/room/FinanceAggregateTest.kt`
- Create: `core/data/src/androidTest/java/com/paherdin/record/core/data/room/FinanceAtomicVisibilityTest.kt`
- Create: `core/data/src/androidTest/java/com/paherdin/record/core/data/room/CategoryLifecycleTest.kt`
- Create: `core/data/src/androidTest/java/com/paherdin/record/core/data/room/TransactionStatusTest.kt`
- Modify: `core/data/src/test/java/com/paherdin/record/core/data/ApiSurfaceTest.kt`
- Create: `core/data/apiGuard/positive/FinanceSurfaceUsage.kt`
- Create: `core/data/apiGuard/negative/RawFinanceAggregateWrite.kt`
- Create: `core/data/apiGuard/negative/FinanceRoomType.kt`

- [ ] **Step 1: Write failing public-contract and validator tests**

`ApiSurfaceTest` and independent consumers require the frozen `FinanceRepository` signatures and reject public DAO/Room/raw DTO/signed-entry write access. Pure validators cover:

```kotlin
assertValidIncomeExpense(type = EXPENSE, magnitude = 100, entry = -100, allocations = listOf(60, 40))
assertRejectDuplicateCategories(listOf(categoryA to 60, categoryA to 40))
assertRejectAllocationSum(magnitude = 100, allocations = listOf(99))
assertValidTransfer(source = -100, destination = 100, sameCurrency = true)
assertRejectPostWriteBalance(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))
assertRejectMemo(codePointCount = 501)
```

Negative consumer files independently attempt to reference `RecordDao`, Room entities, legacy `NewTransaction`, raw `saveTransactionAggregate`, physical binding/path, and signed-entry constructors.

- [ ] **Step 2: Write failing Room tests for every operation and observable old/new state**

Create tests for:

- account plus opening account/ref/transaction/entry all-or-none;
- Income/Expense create and edit with exact complete allocation replacement;
- Transfer create/edit with two accounts and no one-sided observer state;
- account/home/detail/withdrawn/category Flows;
- Category Create/Rename/Archive/Restore and tuple CAS;
- active-only new/changed allocation and unchanged archived historical allocation;
- Withdraw/Restore, including Transfer effects and Opening rejection;
- stale `EntityRef.updatedAt` and stale Category tuple;
- commit-response reconciliation exact-match/absence/mismatch;
- stable error mapping and forbidden raw exception/user value leakage.

Each write test injects failure before the transaction, between every child write/delete, before commit, and after commit response. Use canonical UUID fixtures and a `BigInteger` oracle.

- [ ] **Step 3: Run focused tests and API guards to prove the surface is absent**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:data:testDebugUnitTest :core:data:apiBoundaryCheck --console=plain
```

Expected: FAIL on missing contracts/validators and expected-surface mismatch.

- [ ] **Step 4: Implement immutable application contracts and stable failures**

Implement the frozen type contract above and view types with only application values. Add precise failures:

Add these cases directly inside the existing `RepositoryResult.Failure` sealed interface:

```kotlin
data class FinanceInvalid(val violation: FinanceViolation, val field: FinanceInputField?) : Failure
data class DuplicateCategory(val existing: CategorySnapshot) : Failure
data class ArchivedCategory(val category: CategorySnapshot) : Failure
data object StaleRevision : Failure
data object CommitStateConflict : Failure
```

Do not create a second result hierarchy. Raw exception strings and user content never enter a failure.

- [ ] **Step 5: Implement one binding-safe repository surface**

`RecordRepository` exposes exactly one `FinanceRepository` instance backed by the current `VaultBinding`. The delegate acquires the repository's existing shared operation gate for every read/write and resolves the binding inside that gate, so ADR-0010 restore handoff cannot leave a stale DAO.

```kotlin
val finance: FinanceRepository by lazy { RoomFinanceRepository(this) }

internal suspend fun <T> withFinanceBinding(
    block: suspend (VaultBinding) -> T,
): T = gate.shared { block(checkNotNull(binding)) }
```

Keep `VaultBinding` internal. Internalize legacy raw finance methods/DTOs or route them through the complete M2 validator so no public caller can create an Income/Expense without allocations. Preserve attachment, note, backup, restore, and startup behavior.

- [ ] **Step 6: Implement atomic writes, exact reads, CAS, and reconciliation**

Use `withTransaction` around each complete aggregate. Income/Expense signs are derived from the typed command; Transfer creates equal/opposite values with checked magnitude; callers never supply signed entries. Edit loads and compares `updatedAt`, validates unchanged archived allocations versus new/changed active allocations, then replaces the complete owned set. Category lifecycle updates use SQL compare-and-set against all persisted tuple fields and require exactly one affected row.

Withdraw/Restore modifies `EntityRef.deletedAt` and `updatedAt` only after validating type/status/revision; owned rows remain. Balance and history queries join `EntityRef` and distinguish effective from withdrawn. Every affected balance is folded with widened arithmetic before commit.

Reconciliation uses retained IDs and a complete canonical aggregate snapshot:

```kotlin
when (val observed = loadAggregate(command.transactionId)) {
    null -> RepositoryResult.Failure.TransactionFailed
    command.toExpectedAggregate() -> RepositoryResult.Success(command.transactionId)
    else -> RepositoryResult.Failure.CommitStateConflict
}
```

For edit, distinguish unchanged prior revision from a mismatching committed state. Do not treat any duplicate-ID result as success without full equality.

- [ ] **Step 7: Run unit, API, and focused Room gates**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:model:test :core:data:testDebugUnitTest :core:data:apiBoundaryCheck :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.FinanceAggregateTest,com.paherdin.record.core.data.room.FinanceAtomicVisibilityTest,com.paherdin.record.core.data.room.CategoryLifecycleTest,com.paherdin.record.core.data.room.TransactionStatusTest" --console=plain
```

Expected: all listed tasks/classes PASS; XML counts, property seeds, and fault-point results are retained.

- [ ] **Step 8: Commit the finance data surface**

```powershell
$recordM2T03Files = @(
  'core/data/src/main/java/com/paherdin/record/core/data/finance/FinanceContracts.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/finance/FinanceRepository.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/finance/RoomFinanceRepository.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/finance/FinanceAggregateValidator.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/finance/FinanceQueries.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/RepositoryResult.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/Model.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/room/RecordDao.kt',
  'core/data/src/test/java/com/paherdin/record/core/data/finance/FinanceAggregateValidatorTest.kt',
  'core/data/src/androidTest/java/com/paherdin/record/core/data/room/FinanceAggregateTest.kt',
  'core/data/src/androidTest/java/com/paherdin/record/core/data/room/FinanceAtomicVisibilityTest.kt',
  'core/data/src/androidTest/java/com/paherdin/record/core/data/room/CategoryLifecycleTest.kt',
  'core/data/src/androidTest/java/com/paherdin/record/core/data/room/TransactionStatusTest.kt',
  'core/data/src/test/java/com/paherdin/record/core/data/ApiSurfaceTest.kt',
  'core/data/apiGuard/positive/FinanceSurfaceUsage.kt',
  'core/data/apiGuard/negative/RawFinanceAggregateWrite.kt',
  'core/data/apiGuard/negative/FinanceRoomType.kt'
)
git add -- $recordM2T03Files
git commit -m "feat(data): add atomic finance repository"
```

## Task M2-T04: Advance logical backup to format 2 and preserve format-1 portability

**Owner:** one worker. **Dispatch tier:** full contract; compatibility/recovery surface. Runs serially after T03.

**Files:**
- Modify/create only the backup/restore/fixture files listed in the planned file map
- Create: `core/data/src/test/java/com/paherdin/record/core/data/LogicalBackupV2SchemaTest.kt`
- Create: `core/data/src/androidTest/java/com/paherdin/record/core/data/room/Format1UpconversionTest.kt`
- Create: `core/data/src/androidTest/java/com/paherdin/record/core/data/room/FinanceRoundTripV2Test.kt`
- Extend invalid restore generators/tests without changing P5 limit values

- [ ] **Step 1: Write failing format-dispatch and record-shape tests**

Assert:

```kotlin
assertEquals(2, exportedManifest.formatVersion)
assertEquals(
    setOf("EntityRef", "Account", "Transaction", "AccountEntry", "Note",
        "TransactionNoteAssociation", "Attachment", "AttachmentLink",
        "Category", "CategoryAllocation"),
    exportedManifest.entries.map { it.logicalType }.toSet(),
)
assertTrue(transactionJson.contains("\"occurredAt\""))
assertTrue(transactionJson.contains("\"memo\""))
```

Format-2 writes `"memo": null` when absent, so the field is required even though its value is nullable. Decoding rejects missing datasets/fields and unknown fields. Format-1 decoding accepts only the old exact shape and sends it through `LogicalBackupV1Upconverter`; it never routes a malformed format-2 record through fallback.

- [ ] **Step 2: Write failing device round-trip and invalid-candidate tests**

The v2 fixture includes effective/withdrawn Income, Expense, Transfer, Unicode memo, active/renamed/archived Category, exact allocations, archived historical allocation, notes/relations, shared canonical attachment, and boundary times. Export → stage → confirm → startup → compare → second startup must match every field, balance, relation, attachment size/hash, Category key bytes, and status.

Format-1 positive fixtures cover with/without legacy Income/Expense and conditional **未分类（迁移）**. Negative fixtures independently cover non-canonical IDs/FKs, unsupported currencies, `Long.MIN_VALUE` Expense, missing v2 datasets/fields, duplicate singleton/pair/key, invalid state/archive tuple, non-positive/non-exact allocation, Opening/Transfer allocation, dangling Category, mismatched name key/version, and balance overflow. Every failure compares current-vault hash before/after and records staging cleanup state.

- [ ] **Step 3: Run focused tests and retain expected failures**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:data:testDebugUnitTest :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.room.Format1UpconversionTest,com.paherdin.record.core.data.room.FinanceRoundTripV2Test" --console=plain
```

Expected: FAIL because exporter/reader remain format 1 and Category datasets are unsupported.

- [ ] **Step 4: Implement explicit versioned codecs and up-converter**

Keep separate functions so optionality cannot blur versions:

```kotlin
sealed interface DecodedBackup {
    data class V1(val datasets: Map<LogicalTypeV1, List<Any>>) : DecodedBackup
    data class V2(val datasets: Map<LogicalTypeV2, List<Any>>) : DecodedBackup
}

fun decodeByVersion(manifest: Manifest): DecodedBackup = when (manifest.formatVersion) {
    1 -> decodeStrictV1(manifest)
    2 -> decodeStrictV2(manifest)
    else -> throw RestoreRejectedException(RestoreViolation.FORMAT_UNSUPPORTED)
}
```

`LogicalBackupV1Upconverter` runs only after strict v1 parse inside staging and applies the same identity/currency/magnitude/occurrence/memo/Category rules as database migration. It returns current-schema rows for the ordinary staging validator; it never mutates the active generation.

- [ ] **Step 5: Extend export, staging, receipt, validation, and startup revalidation**

Export ten singleton datasets in deterministic order, include Category name-key versions, hash/count every payload, and write manifest last. Staging validates all current finance/catalog rules and accepts archived historical allocations. Receipt digest includes the two new dataset descriptors and new Transaction fields. Startup revalidation uses the same current validator before a candidate becomes healthy.

Do not change ZIP path/count/size/ratio limits, attachment canonical rules, generation pointer shape, activation steps, or SAF publication behavior.

- [ ] **Step 6: Run backup/restore gates**

Run the Step-3 command and then:

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:data:testDebugUnitTest :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.notClass=com.paherdin.record.core.data.search.SearchBenchmarkTest" --console=plain
```

Expected: all non-measurement unit/device tests PASS, including P5 regression suites; no format-1 export is produced by M2.

- [ ] **Step 7: Commit backup compatibility**

```powershell
$recordM2T04Files = @(
  'core/data/src/main/java/com/paherdin/record/core/data/backup/LogicalBackupSchema.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/backup/Manifest.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/backup/BackupExporter.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/backup/LogicalBackupV1Upconverter.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/restore/CatalogContractChecker.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/restore/RestorePreflight.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/restore/RestoreStager.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/restore/StagedGenerationValidator.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/restore/StagingReceipt.kt',
  'core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt',
  'core/data/src/test/java/com/paherdin/record/core/data/LogicalBackupV2SchemaTest.kt',
  'core/data/src/androidTest/java/com/paherdin/record/core/data/room/Format1UpconversionTest.kt',
  'core/data/src/androidTest/java/com/paherdin/record/core/data/room/FinanceRoundTripV2Test.kt',
  'core/data/src/androidTest/java/com/paherdin/record/core/data/room/MaliciousZipGenerator.kt',
  'core/data/src/androidTest/java/com/paherdin/record/core/data/room/MaliciousZipRestoreTest.kt',
  'core/data/src/androidTest/java/com/paherdin/record/core/data/room/RoundTripTest.kt'
)
git add -- $recordM2T04Files
git commit -m "feat(backup): add finance format v2 round trip"
```

## Task M2-T05: Implement finance draft state and ViewModels

**Owner:** one worker. **Dispatch tier:** lightweight; runs serially after T04 and owns only `:feature:finance` state/tests without changing resources or app routes.

**Files:**
- Create the parser/template/UI-model/ViewModel files listed in the planned file map
- Create: `feature/finance/src/test/java/com/paherdin/record/feature/finance/FinanceTestRepository.kt`
- Create: `feature/finance/src/test/java/com/paherdin/record/feature/finance/FinanceHomeViewModelTest.kt`
- Create: `feature/finance/src/test/java/com/paherdin/record/feature/finance/AccountOpeningViewModelTest.kt`
- Create: `feature/finance/src/test/java/com/paherdin/record/feature/finance/TransactionEditorViewModelTest.kt`
- Create: `feature/finance/src/test/java/com/paherdin/record/feature/finance/CategoryViewModelTest.kt`
- Create: `feature/finance/src/test/java/com/paherdin/record/feature/finance/TransactionStatusViewModelTest.kt`

- [ ] **Step 1: Write failing state-transition tests before ViewModels**

Use `StandardTestDispatcher` and fake `FinanceRepository`. Tests cover every initial state, exact `Phase` transition, validation enablement, retained IDs/time, configuration recreation state, Saving navigation lock, one terminal result, stale revision, commit reconciliation result, total/allocation behavior, template lookup/create/restore, archived-draft race, Withdraw/Restore, and durable-success navigation intent.

Representative assertions:

```kotlin
assertEquals(Phase.Idle, state.phase)
assertFalse(state.canSave)
viewModel.onAmountChanged("100.00")
viewModel.onCategorySelected(categoryA)
assertEquals(10_000L, state.allocations.single().amountMinor)
viewModel.onAmountChanged("120.00")
assertEquals(10_000L, state.allocations.single().amountMinor)
assertEquals(2_000L, state.remainingMinor)
viewModel.onSave()
viewModel.onBack()
assertEquals(Phase.Saving, state.phase)
assertNull(navigator.lastIntent)
```

- [ ] **Step 2: Run focused tests and retain missing-type failures**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :feature:finance:testDebugUnitTest --console=plain
```

Expected: FAIL because the M2 states/ViewModels do not exist.

- [ ] **Step 3: Implement one immutable state per flow and typed intents**

Use exhaustive sealed intents and no widget-callback naming. Shared editor state contains only values required by the spec:

```kotlin
enum class FinanceField {
    ACCOUNT, DESTINATION_ACCOUNT, AMOUNT, OCCURRED_AT, MEMO, ALLOCATIONS, CATEGORY_NAME
}

enum class FinanceMessage {
    REQUIRED, INVALID_AMOUNT, FRACTION_NOT_ALLOWED, OUT_OF_RANGE, INVALID_LOCAL_TIME,
    MEMO_TOO_LONG, ALLOCATION_REQUIRED, ALLOCATION_DUPLICATE, ALLOCATION_SUM_MISMATCH,
    SAME_ACCOUNT, CURRENCY_MISMATCH, CATEGORY_ARCHIVED, STALE_REVISION, STORAGE_EXHAUSTED,
    RETRY_AVAILABLE, COMMIT_STATE_CONFLICT, UNEXPECTED_FAILURE
}

data class TransactionEditorState(
    val draftId: String,
    val transactionId: TransactionId,
    val type: TransactionType,
    val accountId: AccountId?,
    val destinationAccountId: AccountId?,
    val amountText: String,
    val occurredLocal: LocalDateTime,
    val memo: String,
    val allocations: List<AllocationRowState>,
    val remainingMinor: Long?,
    val fieldErrors: Map<FinanceField, FinanceMessage>,
    val expectedUpdatedAt: Long?,
    val phase: Phase,
    val canSave: Boolean,
)
```

Use separate state types for home, account detail, transaction detail, withdrawn list, account opening, Category catalog/picker/editor, and status confirmation. Do not add Tag/Project/Note/image fields to M2 UI state.

- [ ] **Step 4: Implement draft generation, validation, save, and template state machines**

Generate stable IDs and capture opening instant only on first valid confirmation; retain across failure. Existing editors load complete state before Idle. Validate amount via `AmountTextParser`, occurrence via `OccurrenceResolver`, memo code points, exact allocations, distinct same-currency Transfer accounts, and dirty state before calling the repository.

`FinanceTemplates.kt` contains stable template keys and expense/income ordering, not hard-coded UI strings. `strings.xml` owns the approved Chinese labels; the Compose event supplies the resolved visible label for the selected key. Template selection calls `findCategoryByName`: active selects, archived opens Restore intent, absent opens create prefilled; no path persists a template automatically. A selected Category archived before save remains visible/invalid. Saving ignores Back/Cancel/Discard/duplicate Confirm until one repository result maps to Completed or the declared failure state.

- [ ] **Step 5: Implement read/detail/status ViewModels and navigation intents**

Collect repository Flows with structured concurrency. Map missing versus Loading/Error/Empty truthfully. `TransactionStatusViewModel` reloads current status/impact and never trusts route display data. Emit navigation only from a consumed Completed result:

```kotlin
sealed interface FinanceNavigationIntent {
    data class AccountCreated(val id: AccountId) : FinanceNavigationIntent
    data class TransactionCreated(val id: TransactionId) : FinanceNavigationIntent
    data class ReturnToTransaction(val id: TransactionId) : FinanceNavigationIntent
    data class ReturnToPicker(val draftId: String, val selected: CategoryId) : FinanceNavigationIntent
}
```

- [ ] **Step 6: Run all feature unit tests and boundary scans**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :feature:finance:testDebugUnitTest --console=plain
Get-ChildItem 'feature\finance\src\main' -Recurse -File -Filter '*.kt' | Select-String -Pattern 'androidx\.room|RecordDao|java\.io\.File|android\.net\.Uri|Throwable\.message|amount\w*\s*:\s*(Float|Double)|balance\w*\s*:\s*(Float|Double)|Money\([^\r\n]*(Float|Double)'
```

Expected: unit tests PASS; source scan has no persistence/path/floating-money/raw-message match. General Compose graphics may use `Float`, but no amount, balance, or `Money` signature may do so.

- [ ] **Step 7: Commit ViewModels**

```powershell
$recordM2T05Files = @(
  'feature/finance/src/main/java/com/paherdin/record/feature/finance/FinanceTemplates.kt',
  'feature/finance/src/main/java/com/paherdin/record/feature/finance/FinanceUiModels.kt',
  'feature/finance/src/main/java/com/paherdin/record/feature/finance/FinanceHomeViewModel.kt',
  'feature/finance/src/main/java/com/paherdin/record/feature/finance/WithdrawnTransactionsViewModel.kt',
  'feature/finance/src/main/java/com/paherdin/record/feature/finance/AccountOpeningViewModel.kt',
  'feature/finance/src/main/java/com/paherdin/record/feature/finance/AccountDetailViewModel.kt',
  'feature/finance/src/main/java/com/paherdin/record/feature/finance/TransactionDetailViewModel.kt',
  'feature/finance/src/main/java/com/paherdin/record/feature/finance/TransactionEditorViewModel.kt',
  'feature/finance/src/main/java/com/paherdin/record/feature/finance/CategoryCatalogViewModel.kt',
  'feature/finance/src/main/java/com/paherdin/record/feature/finance/CategoryPickerViewModel.kt',
  'feature/finance/src/main/java/com/paherdin/record/feature/finance/CategoryEditorViewModel.kt',
  'feature/finance/src/main/java/com/paherdin/record/feature/finance/TransactionStatusViewModel.kt',
  'feature/finance/src/test/java/com/paherdin/record/feature/finance/FinanceTestRepository.kt',
  'feature/finance/src/test/java/com/paherdin/record/feature/finance/FinanceHomeViewModelTest.kt',
  'feature/finance/src/test/java/com/paherdin/record/feature/finance/AccountOpeningViewModelTest.kt',
  'feature/finance/src/test/java/com/paherdin/record/feature/finance/TransactionEditorViewModelTest.kt',
  'feature/finance/src/test/java/com/paherdin/record/feature/finance/CategoryViewModelTest.kt',
  'feature/finance/src/test/java/com/paherdin/record/feature/finance/TransactionStatusViewModelTest.kt'
)
git add -- $recordM2T05Files
git commit -m "feat(finance): add durable finance state machines"
```

## Task M2-T06: Build the account-first Finance read surfaces

**Owner:** one worker. **Dispatch tier:** lightweight; one feature slice, no concurrent feature owner.

**Files:**
- Create: `feature/finance/src/main/java/com/paherdin/record/feature/finance/FinanceHomeScreen.kt`
- Create: `feature/finance/src/main/java/com/paherdin/record/feature/finance/WithdrawnTransactionsScreen.kt`
- Create: `feature/finance/src/main/java/com/paherdin/record/feature/finance/AccountScreens.kt`
- Create: `feature/finance/src/main/java/com/paherdin/record/feature/finance/TransactionDetailScreen.kt`
- Modify: `feature/finance/src/main/java/com/paherdin/record/feature/finance/FinanceScreens.kt`
- Modify: `feature/finance/src/main/java/com/paherdin/record/feature/finance/FinanceUiModel.kt`
- Modify: `feature/finance/src/main/res/values/strings.xml`
- Create/modify focused Compose tests under `feature/finance/src/androidTest`

- [ ] **Step 1: Write failing Compose tests for home, account, detail, and withdrawn states**

Tests inject immutable states and assert:

- account groups are ordered by currency code and show individual exact balances;
- no node exposes a combined unlike-currency total;
- account-empty is not Error and exposes Create Account;
- recent list excludes withdrawn records and the withdrawn entry exposes its count;
- Account detail shows opening/effective/withdrawn chronological history and a balance excluding withdrawn entries;
- withdrawn Transaction detail is marked read-only with Restore, effective ordinary detail has Edit/Withdraw, and Opening has neither;
- Loading/Empty/Error/Ready use the shared presentations without partial Ready;
- headings, selected/status semantics, 48-dp targets, and 200% font bounds pass in light/dark themes.

Use stable tags only in androidTest-visible semantics; production strings remain resource-backed Simplified Chinese.

- [ ] **Step 2: Run the focused Compose class and retain expected failure**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :feature:finance:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.feature.finance.FinanceReadScreensTest" --console=plain
```

Expected: FAIL because M1 availability surfaces do not render the M2 states.

- [ ] **Step 3: Implement account-first screen composition**

Use the M1 A/A1 semantic tokens and shared content states. The public screen signatures consume state and intent callbacks only:

```kotlin
@Composable
fun FinanceHomeScreen(state: FinanceHomeState, onEvent: (FinanceHomeEvent) -> Unit)

@Composable
fun AccountDetailScreen(state: AccountDetailState, onEvent: (AccountDetailEvent) -> Unit)

@Composable
fun TransactionDetailScreen(state: TransactionDetailState, onEvent: (TransactionDetailEvent) -> Unit)

@Composable
fun WithdrawnTransactionsScreen(state: WithdrawnTransactionsState, onEvent: (WithdrawnEvent) -> Unit)
```

The home hierarchy is title/actions → currency-grouped accounts → typed create actions → recent effective transactions → withdrawn entry. It contains no chart/statistics/Timeline placeholder. Detail amounts include currency code in visible or accessibility text so CNY/JPY symbol ambiguity cannot mislead.

- [ ] **Step 4: Run focused screen tests and feature regression**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :feature:finance:testDebugUnitTest :feature:finance:connectedDebugAndroidTest --console=plain
```

Expected: unit and all Finance Compose tests PASS; M1 availability tests are updated to the approved M2 truth rather than disabled.

- [ ] **Step 5: Commit read surfaces**

```powershell
git add feature/finance/src/main feature/finance/src/androidTest
git commit -m "feat(finance): add account-first read surfaces"
```

## Task M2-T07: Build finance editors, Category lifecycle, and status confirmations

**Owner:** one worker. **Dispatch tier:** lightweight; serial after T06 because strings/resources and shared screen contracts overlap.

**Files:**
- Create: `feature/finance/src/main/java/com/paherdin/record/feature/finance/TransactionEditorScreen.kt`
- Create: `feature/finance/src/main/java/com/paherdin/record/feature/finance/CategoryScreens.kt`
- Create: `feature/finance/src/main/java/com/paherdin/record/feature/finance/TransactionStatusConfirmScreen.kt`
- Modify: `feature/finance/src/main/java/com/paherdin/record/feature/finance/AccountScreens.kt`
- Modify: `feature/finance/src/main/res/values/strings.xml`
- Create focused Compose tests under `feature/finance/src/androidTest`

- [ ] **Step 1: Write failing Compose tests for every editor/lifecycle route**

Cover:

- Account opening fields, six currencies, signed opening amount, hidden occurrence field, and invalid field focus;
- Income/Expense fixed type, account, amount, occurrence, memo, total-first allocations, exact allocated/remaining text, and Save gate;
- total edits preserve rows; removing rows changes remainder without mutation of another row;
- Transfer distinct same-currency selectors and absent Category controls;
- empty Category picker, exact Expense/Income template labels, manual create, active match, archived Restore, auto-select, parent-draft byte equality, and no persisted template status;
- Category active/archived lists, create/rename/archive/restore, duplicate and failure retention, and no hard delete/hierarchy/icon/color/note;
- Withdraw/Restore confirmation loads impact, Saving locks every exit, stale/failure remains, completion returns to same detail, and Opening has no entry;
- dirty-draft discard, configuration recreation, semantics/focus/touch targets, and 200% font.

- [ ] **Step 2: Run class-filtered tests and retain expected failures**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :feature:finance:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.feature.finance.AccountOpeningScreenTest,com.paherdin.record.feature.finance.TransactionEditorScreenTest,com.paherdin.record.feature.finance.CategoryScreensTest,com.paherdin.record.feature.finance.TransactionStatusConfirmScreenTest" --console=plain
```

Expected: FAIL because the editor screens are absent.

- [ ] **Step 3: Implement account and transaction editors**

Each screen consumes state/events only and renders no persistence object:

```kotlin
@Composable
fun AccountOpeningScreen(state: AccountOpeningState, onEvent: (AccountOpeningEvent) -> Unit)

@Composable
fun TransactionEditorScreen(state: TransactionEditorState, onEvent: (TransactionEditorEvent) -> Unit)
```

Use Material text fields/selectors/date-time controls with resource copy. `Phase.Saving` disables/intercepts Back, predictive Back, Cancel, Discard, and duplicate Save. Error messages are chosen from stable application categories and never render raw messages. No optional M3+ control is visible or hidden in the state.

- [ ] **Step 4: Implement Category and status-confirmation screens**

```kotlin
@Composable
fun CategoryPickerScreen(state: CategoryPickerState, onEvent: (CategoryPickerEvent) -> Unit)

@Composable
fun CategoryCatalogScreen(state: CategoryCatalogState, onEvent: (CategoryCatalogEvent) -> Unit)

@Composable
fun CategoryEditorScreen(state: CategoryEditorState, onEvent: (CategoryEditorEvent) -> Unit)

@Composable
fun TransactionStatusConfirmScreen(
    state: TransactionStatusState,
    onEvent: (TransactionStatusEvent) -> Unit,
)
```

Templates are visually suggestions and do not appear as active Category rows. Confirmation text states affected account(s)/currency without logging or routing those display values. `Phase.Completed` is the only navigation-producing success.

- [ ] **Step 5: Run editor/lifecycle and full feature gates**

Run the Step-2 command, then:

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :feature:finance:testDebugUnitTest :feature:finance:connectedDebugAndroidTest --console=plain
```

Expected: all tests PASS with no skipped acceptance class and no clipping/focus failure.

- [ ] **Step 6: Commit editors and Category/status flows**

```powershell
git add feature/finance/src/main feature/finance/src/androidTest
git commit -m "feat(finance): add editors and category lifecycle"
```

## Task M2-T08: Integrate typed app routes, Settings, repository injection, and retained shell state

**Owner:** one worker. **Dispatch tier:** full contract; app-owned stable navigation and integration boundary.

**Files:**
- Modify: `app/src/main/java/com/paherdin/record/navigation/M1Routes.kt`
- Modify: `app/src/main/java/com/paherdin/record/navigation/RecordNavHost.kt`
- Modify: `app/src/main/java/com/paherdin/record/ui/RecordShell.kt`
- Modify: `app/src/main/java/com/paherdin/record/ui/SettingsScreen.kt`
- Modify startup-ready composition wiring in `MainActivity.kt`/`RecordApplication.kt` only where repository injection requires it
- Modify: `app/src/main/res/values/strings.xml`
- Modify/create app unit/android tests

- [ ] **Step 1: Write failing route serialization, graph, chrome, and wiring tests**

Unit tests round-trip every approved route and reject blank/non-canonical IDs:

```kotlin
val routes = listOf(
    AccountOpeningEditor(TopLevelOrigin.Finance),
    AccountDetail(accountId = uuidA, origin = TopLevelOrigin.Finance),
    WithdrawnTransactions(TopLevelOrigin.Finance),
    TransactionStatusConfirm(uuidT, TransactionStatusAction.Withdraw, TopLevelOrigin.Finance),
    CategoryCatalog(CategoryListMode.Active, TopLevelOrigin.Finance),
    CategoryPicker(draftId = uuidD),
)
```

Navigation tests assert global and Finance-local typed actions, create/detail/edit/status/picker/catalog returns, bottom-nav visibility, exact origin restoration, and independent Finance account/filter/withdrawn scroll state through tab switch and recreation. Startup tests assert no Finance ViewModel/repository read before ADR-0016 Ready.

- [ ] **Step 2: Run app unit and focused navigation tests for the expected failure**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.M2FinanceNavigationTest,com.paherdin.record.M2FinanceRetentionTest" --console=plain
```

Expected: FAIL because M2 routes/wiring are absent.

- [ ] **Step 3: Add only the approved serializable routes**

Keep M1 route types and add:

```kotlin
@Serializable data class AccountOpeningEditor(val origin: TopLevelOrigin)
@Serializable data class AccountDetail(val accountId: String, val origin: TopLevelOrigin)
@Serializable data class WithdrawnTransactions(val origin: TopLevelOrigin)
@Serializable enum class TransactionStatusAction { Withdraw, Restore }
@Serializable data class TransactionStatusConfirm(
    val transactionId: String,
    val action: TransactionStatusAction,
    val origin: TopLevelOrigin,
)
@Serializable enum class CategoryListMode { Active, Archived }
@Serializable enum class CategoryEditorMode { Create, Rename }
@Serializable enum class CatalogType { Category }
@Serializable data class CategoryCatalog(val mode: CategoryListMode, val origin: TopLevelOrigin)
@Serializable data class CategoryPicker(val draftId: String)
@Serializable data class CategoryEditor(val mode: CategoryEditorMode, val categoryId: String?, val returnKey: String)
@Serializable data class CatalogArchiveConfirm(
    val catalogType: CatalogType,
    val categoryId: String,
    val returnKey: String,
)
```

Expand Expense/Income/Transfer editor routes with nullable `transactionId`; `null` means new. Every non-null ID and `returnKey`/draft identity is canonical. Do not register M3+ routes.

- [ ] **Step 4: Wire startup-verified repository and feature factories**

The Ready repository is supplied from the existing app-scoped startup state to Finance route content. `:app` creates ViewModels/factories with `repository.finance`, injected production Clock/ZoneId/dispatcher/UUID source, and typed navigation callbacks. Neither `NavController` nor `RecordRepository` enters a composable state model; no route constructs or opens another repository.

- [ ] **Step 5: Register destinations, Settings entry, chrome, and deterministic returns**

Register every M2 route in `RecordNavHost`; replace M1 Finance availability routes with real screens; add Settings → Categories. Use route/phase metadata to hide bottom navigation for editors, pickers, Category editor/archive confirm, and Transaction status confirm while keeping it visible for home/details/catalog/withdrawn list. Consume one-shot Completed intents before navigation so recomposition cannot duplicate a route.

- [ ] **Step 6: Run app/feature/unit/navigation gates**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:model:test :core:data:testDebugUnitTest :feature:finance:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease checkModuleDependencies --console=plain
```

Then run the class-filtered device command from Step 2. Expected: all PASS; no shell/Finance read before startup Ready, no route/state loss, no generic Entry.

- [ ] **Step 7: Commit app integration**

```powershell
git add app/src/main feature/finance/src/main app/src/test app/src/androidTest
git commit -m "feat(app): integrate M2 finance navigation"
```

## Task M2-T09: Execute and accept device persistence, migration, fault, and round-trip gates

**Owner:** one worker executes; main agent reviews raw evidence and updates shared verification. **Dispatch tier:** full contract. No product source change is authorized unless a test exposes a defect inside an earlier task's approved scope and the main agent issues a revised contract.

**Files:**
- Create: `benchmark/src/main/kotlin/com/paherdin/record/benchmark/M2FinanceJourneyTest.kt`
- Device/build output under module `build/` directories
- Main agent modifies only `docs/specs/mvp/m2-finance-core/verification.md`

- [ ] **Step 1: Obtain device deletion approval and verify exact targets**

Before install/uninstall or package-data reset, the main agent explains that disposable test APK data must be removed after evidence, names the exact packages, states that production data is excluded, and obtains explicit approval. Resolve installed paths/UIDs read-only first.

Allowed disposable targets are only:

```text
com.paherdin.record.core.data.test
com.paherdin.record.feature.finance.test
com.paherdin.record.test
com.paherdin.record.benchmark
com.paherdin.record.benchmark.test
```

`com.paherdin.record` data, `files/vaults`, user databases, backups, and any unmatched package/path are forbidden targets. If normal Android test packaging uses a different package, stop and revise the contract before action.

- [ ] **Step 2: Record device and environment preflight**

```powershell
& 'C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
& 'C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell getprop ro.product.model
& 'C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell getprop ro.build.fingerprint
& 'C:\Users\p3148\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell getprop ro.build.version.sdk
```

Expected: exactly one authorized Xiaomi 14 Pro device online. Record host commit/JDK/SDK and device values. If empty, restart adb once and relist; do not relabel absence as PASS.

- [ ] **Step 3: Run the complete non-measurement core-data suite**

Launch in the background and poll without interrupting:

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.notClass=com.paherdin.record.core.data.search.SearchBenchmarkTest" --console=plain
```

Expected: Gradle PASS; XML reports show positive test count and zero failures/errors/skips for all M2 and P1–P5 regression classes. Search measured benchmark remains excluded; its pure/parser regressions still run in unit tests.

- [ ] **Step 4: Audit required migration/fault matrix from raw output**

The main agent does not accept a summary. It verifies evidence contains every source version, positive conversion, three negative preflights, every `M2MigrationPoint`, fail-once/restart, repeated-failure, complete/repeat, unchanged snapshot/current-vault hash, no Room/shell on rejection, and exact schema hashes. Missing point is FAIL.

- [ ] **Step 5: Audit atomic finance and reconciliation evidence**

Verify every account/opening, Income/Expense child boundary, Transfer side, edit replacement, Category CAS/lifecycle, Withdraw/Restore, balance-overflow, stale-revision, and after-commit response point. Old/new snapshots and BigInteger oracle must be retained; a one-sided or partial observation fails.

- [ ] **Step 6: Audit format-1/format-2 round trips and invalid candidates**

Require exact v2 dataset/field/hash/count comparison through activation and second startup, positive v1 conversions with/without migration Category, every invalid fixture, unchanged current-vault hash, and deterministic staging residue classification. P5 attachment/ZIP limit/activation regressions must remain PASS.

- [ ] **Step 7: Record task evidence and commit only the ledger**

The main agent updates M2-T09 state and criteria 2, 4–14, 18–20 only where raw evidence supports PASS.

```powershell
git add docs/specs/mvp/m2-finance-core/verification.md
git commit -m "test(data): verify M2 finance persistence gates"
```

## Task M2-T10: Execute and accept Compose, navigation, accessibility, and offline gates

**Owner:** one worker executes; main agent reviews raw evidence and updates verification. **Dispatch tier:** full contract. Source fixes require a revised earlier-task contract.

**Files:**
- No planned production write
- Device/build output under module `build/` directories
- Main agent modifies only `docs/specs/mvp/m2-finance-core/verification.md`

- [ ] **Step 1: Verify network isolation and any Xiaomi app-op requirement**

Record airplane/network-isolation state before the run. If HyperOS blocks ActivityScenario with result 102, stop and ask for explicit temporary `MIUIOP(10021)=allow` on the exact disposable instrumentation target; state the background-window risk, verify allow readback, and restore `ignore` plus uninstall afterward. Prior M1 approval is not reused automatically.

- [ ] **Step 2: Run complete Finance feature tests**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :feature:finance:connectedDebugAndroidTest --console=plain
```

Expected: all Finance read/editor/catalog/status tests PASS with positive XML counts, zero failures/errors/skips, 200% font assertions, and no test-only business data in production defaults.

- [ ] **Step 3: Run complete app navigation/integration tests**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :app:connectedDebugAndroidTest --console=plain
```

Expected: all M1 regressions plus M2 Finance navigation/retention/startup-ready tests PASS. Every M2 app androidTest uses the existing debug-only host with a scripted fake `FinanceRepository`; it must not launch production `MainActivity`, open `RecordStartup`, or touch the installed `com.paherdin.record` vault. If a class is separated for a documented device harness reason, run every invocation and retain a combined exact count; never omit it.

- [ ] **Step 4: Run the end-to-end M2 network-isolated journeys**

Create `M2FinanceJourneyTest` in the existing `:benchmark` test-tool project and use UI Automator 2.3.0 against only the isolated `com.paherdin.record.benchmark` target. After the approved exact-package reset, launch the real production shell/Finance wiring and cover:

1. create accounts with positive/zero/negative Opening;
2. empty Category → Expense template create → auto-select → parent save;
3. Income manual Category and multi-allocation Expense;
4. same-currency Transfer and different-currency rejection;
5. edit each ordinary type;
6. Withdraw/Restore Income, Expense, and Transfer;
7. Settings Category rename/archive/historical rendering/Restore;
8. configuration recreation, tab/origin/scroll retention, validation, storage failure, and ambiguous commit paths.

Expected: every terminal success follows durable completion; no Timeline/statistics claim appears; balances/history/details match the independent oracle.

Run:

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.benchmark.M2FinanceJourneyTest" --console=plain
```

Expected: the isolated journey class PASSes with a positive test count. Its setup verifies runtime package `com.paherdin.record.benchmark` and the existing exact benchmark-variant marker before any data action; any other package/marker fails closed.

- [ ] **Step 5: Verify accessibility and diagnostic sentinel boundaries**

Assert headings/state labels/focus/48-dp targets/200% bounds/light-dark contrast. Inject synthetic sentinel account names, amount/balance/currency, memo, Category name, IDs, and raw exception content; inspect retained/exported diagnostics and require exactly the allowed three-field envelope with no sentinel/timestamp.

- [ ] **Step 6: Restore device state and prove cleanup**

After approved test-package uninstall/app-op reset, verify each disposable UID/path/app-op/process is absent or restored. Do not clear/uninstall the production package. Record commands and results.

- [ ] **Step 7: Record and commit UI acceptance evidence**

The main agent updates criteria 3–12 and 15–20 only from raw results.

```powershell
git add -- benchmark/src/main/kotlin/com/paherdin/record/benchmark/M2FinanceJourneyTest.kt docs/specs/mvp/m2-finance-core/verification.md
git commit -m "test(app): verify M2 finance journeys offline"
```

## Task M2-T11: Run combined artifact/dependency gates and complete the slice

**Owner:** main agent only for integration and acceptance. **Dispatch tier:** not dispatched.

**Files:**
- Create: `tools/verify-m2-artifacts.ps1`
- Modify: `docs/specs/mvp/m2-finance-core/verification.md`
- Modify lifecycle status in `spec.md`, `plan.md`, `AGENTS.md`, `README.md`, `docs/index.md`, and `docs/roadmap.md` only after every gate passes

- [ ] **Step 1: Create the independent frozen M2 artifact verifier**

Do not edit `tools/verify-m1-artifacts.ps1`. The M2 verifier is bounded to the repository root, Android SDK from `local.properties`, named source/merged/packaged manifests, exact APK paths, version catalog, build files, dependency reports, Room schema JSON, and API/source scan roots. It implements these fail-closed families:

```text
family-1-network-permission
family-2-exported-components
family-3-package-identity
family-4-system-backup-exclusion
family-5-dependency-versions-and-denylist
family-6-icu-license-artifact-and-size
family-7-room-schema-v3-and-prior-hashes
family-8-public-api-and-layer-boundaries
family-9-no-premature-m3-plus-surface
family-10-release-test-fixture-exclusion
```

The script exits 0 only when all families have positive check counts and no problem; exit 1 is a gate failure, exit 2 is invalid input. Negative controls prove INTERNET, tracking SDK, public DAO, `Float/Double` money signature, missing format-2 field, modified prior schema hash, and forbidden M3+ token are detected.

- [ ] **Step 2: Run the full host/build gate without `clean`**

```powershell
& 'C:\Users\p3148\tools\jdk\jdk-17.0.20+8\bin\java.exe' -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain check :app:assembleDebug :app:assembleRelease :app:assembleBenchmark :benchmark:assembleBenchmark --console=plain
```

Expected: PASS with all module/unit/API/dependency checks. Do not run `clean`, delete build directories, or reset caches.

- [ ] **Step 3: Run artifact verifier and inspect raw artifacts**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-m2-artifacts.ps1 -ProjectRoot 'D:\record'
```

Expected: `M2-ARTIFACT-VERIFY: PASS`, ten of ten families, positive check count, zero problems. Independently spot-check source/merged/final manifests, APK hashes/sizes, schema 1/2 unchanged hashes and schema 3 shape, dependency review, and API negative-control output.

- [ ] **Step 4: Reconcile all acceptance rows and remaining risk**

For each criterion 1–20, link specific commands, XML/report paths, counts, hashes, fault points, device environment, and accepted result. Run:

```powershell
git diff --check
git status --short
Get-ChildItem 'docs\specs' -Recurse -Filter 'spec.md' |
  Select-String -Pattern '\*\*Status:\*\* `in-progress`'
```

Expected before close: only M2 is `in-progress`; no unowned/staged file; all rows PASS; remaining risk is explicit and non-blocking. Any missing/failed row keeps M2 active and stops completion.

- [ ] **Step 5: Mark M2 completed and synchronize lifecycle sources**

Only after Step 4 passes, set spec/plan `completed` with date and evidence summary, clear active spec to none in AGENTS/index/roadmap, and keep `verification.md` Execution pointer/Task log/acceptance rows as durable history. Confirm M3 remains only a roadmap candidate.

- [ ] **Step 6: Commit lifecycle completion**

```powershell
git add -- tools/verify-m2-artifacts.ps1 docs/specs/mvp/m2-finance-core/spec.md docs/specs/mvp/m2-finance-core/plan.md docs/specs/mvp/m2-finance-core/verification.md AGENTS.md README.md docs/index.md docs/roadmap.md
git commit -m "docs: complete MVP M2 finance core"
```

## Plan self-review checklist

- [x] Every spec criterion 1–20 maps to at least one task and a main-agent acceptance step.
- [x] Every new dependency, public type, route, Room fact, backup version, migration edge, ViewModel, screen, and artifact gate has an owning task and exact file scope.
- [x] TDD order is explicit: focused failing evidence precedes implementation in T01–T08.
- [x] Type names/signatures remain consistent with the Frozen public type contract.
- [x] No step contains an unbounded “handle errors/tests later” instruction, behavior placeholder, generic Entry, M3+ surface, destructive fallback, or raw finance write bypass.
- [x] M2-T00 cannot activate without explicit plan approval and schema/migration consent; M2-T09/T10 cannot delete/reset disposable device data without separate exact-target approval.
- [x] `git diff --check`, links, lifecycle status, command syntax, JDK binding, raw-evidence paths, and commit scopes pass before plan approval.

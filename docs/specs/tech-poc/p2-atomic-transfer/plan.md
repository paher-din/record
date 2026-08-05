# Tech PoC P2 — Atomic Transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Status:** `draft` — produced under the approved spec; awaiting explicit user approval (Revision 1). Implementation is forbidden until the user approves this plan and the approval is recorded.
- **Spec:** [spec.md](spec.md) — `approved` (user accepted Revision 1 on 2026-08-05 after review of commit `2156da3`).

**Goal:** Prove the same-currency transfer and the entry-side financial invariants as one indivisible durable operation on the unchanged v1 schema. A pure finance-matrix validator enforces the entry rules for all four `TransactionType` values; the repository resolves entry account endpoints inside the transaction, runs the validator, and writes transfer entries in a deterministic source-then-destination order; property tests prove balance reproducibility from effective entries; Room fault tests prove no one-sided effect at any injected failure boundary.

**Architecture:** `FinanceViolation` and `FinanceRejected` extend the public `RepositoryResult` surface in `:core:data` (additive only — every P1 declaration is unchanged). `FinanceMatrixValidator` and its internal exception are module-internal in `:core:data`, mirroring the P1 `XorInvariantValidator` pattern. No schema change: `databaseVersion` stays 1, the committed `1.json` is untouched, no migration, none of the migration-recovery machinery. The seven-module graph is unchanged.

**Tech Stack:** unchanged from P1 — Gradle 9.5.0, AGP 9.3.0 (built-in Kotlin 2.2.10), KSP2 2.3.9, Room 2.8.4, `kotlin-metadata-jvm` 2.2.10 (test-time), androidx.test runner/ext-junit, JUnit 4.13.2. `java.math.BigInteger` (JDK standard library) is used for exact reference arithmetic and the validator's zero-sum check; it is not a new dependency. No new dependencies of any kind are anticipated; if the plan's pinned combination fails during execution, this plan is revised and re-approved first — versions are never switched mid-execution.

**Reference environment (frozen):** Xiaomi 14 Pro (physical, USB debugging), Android 14 / API 34 out-of-box baseline. The actual system build is recorded in `verification.md` at verification time (currently API 36 per P0/P1; recorded, not rejected).

**Namespaces:** `:core:data` → `com.paherdin.record.core.data` (public API), Room package `com.paherdin.record.core.data.room`; `:core:model` surface is unchanged (exactly `EntityType` and `TransactionType`).

**Pre-approval dependency validation:** none required. P2 introduces no dependency and reuses the P1-pinned, verified combination; the P2 spec adds no new build configuration.

## Dependency review record

No new dependency enters the version catalog or any build in P2. The P1 review record remains valid for every dependency reused; `BigInteger` is JDK standard library and requires no record. Any dependency that a later revision of this plan introduces must first pass the engineering conventions' dependency review record (version, license, maintenance, compatibility impact, removal path) at plan approval.

## Public API of `:core:data` — P2 normative delta

The P1 normative declaration list is inherited **unchanged**. The P2 surface is the P1 list plus exactly this additive delta, pinned here so the regenerated `api/expected-surface.txt` is derived from this list:

```kotlin
// package com.paherdin.record.core.data — additive members only

enum class FinanceViolation {
    ENTRY_CARDINALITY, ZERO_AMOUNT, WRONG_SIGN,
    TRANSFER_SAME_ACCOUNT, TRANSFER_CURRENCY_MISMATCH, TRANSFER_NOT_ZERO_SUM,
}

sealed interface RepositoryResult<out T> {
    sealed interface Failure : RepositoryResult<Nothing> {
        data class FinanceRejected(val violation: FinanceViolation) : Failure
        // every P1 member remains exactly as pinned in the P1 plan
    }
}
```

Semantics:

- `FinanceRejected` is a domain rejection carrying the precise finance violation; **nothing committed**; callers do not auto-retry — the same caller behavior class as `ValidationRejected`.
- No new write entry point, no new DTO, no renamed or re-meant P1 outcome. `FinanceRejected` joins the existing sealed `Failure`; because `Failure` is sealed, the addition is source-compatible for consumers.
- Enum member order is the deterministic classification precedence (below) for multi-invalid candidates.

## Deterministic classification

The full repository write-precheck order inside the `withTransaction` (P1 order preserved, P2 steps inserted at 4–5):

1. both-subtype occupancy queries for the ID;
2. **XOR validator** on candidate occupancy → `ValidationRejected(violation)` (unchanged);
3. same-type duplicate → `DuplicateIdentity` (unchanged);
4. **entry account endpoint resolution** (new): every entry `accountId` resolves to an existing `Account`; any missing → `EndpointRejected` — before any finance classification;
5. **finance-matrix validation** (new): `FinanceMatrixValidator` over the resolved entries and their stored currency strings → `FinanceRejected(violation)` with precedence **cardinality → zero amount → wrong sign → same-account transfer → currency mismatch → non-zero-sum**; the first violation in that order wins;
6. relation endpoints (unchanged) → `EndpointRejected`;
7. ordered child writes: for a validated `TRANSFER`, the negative source entry is written first and the positive destination second **regardless of input-set iteration order**; single-entry types write their one entry; then the requested relations. The fault hook fires `afterChildWrite(ordinal)` after each child write, so `afterChildWrite(0)` is the deterministic boundary between the two transfer entries.

Cross-family precedence (XOR → duplicate → endpoints → finance → relations) is deterministic as implemented and is pinned by the test matrix; it is not renegotiated by callers.

## Catch priority (revised — P1 numbering shifted)

The P1 list gains clause 2:

0. `CancellationException` → **rethrow** — never converted to an outcome;
1. `XorViolationException` → `ValidationRejected(violation)`;
2. `FinanceViolationException` → `FinanceRejected(violation)` (new);
3. `SQLiteFullException` → `StorageExhausted`;
4. any other `SQLiteException` → `TransactionFailed`;
5. exceptions from hook invocations (simulated faults): inside the transaction → `TransactionFailed`; from `afterCommitResponse` → `CommittedButResponseFailed`;
6. any other exception → `Unexpected`.

Raw exceptions or `Throwable.message` never cross the boundary.

## Finance validator semantics (pure, internal)

`FinanceMatrixValidator.validate(type: TransactionType, entries: List<NewAccountEntry>, currencyByAccountId: Map<String, String>)` is pure and throws the internal `FinanceViolationException(violation)` on the first violation per the precedence. Its inputs are the repository's resolved data: every entry `accountId` is present in `currencyByAccountId` (missing endpoints were rejected at step 4 before the validator runs).

| Type | Cardinality | Zero rule | Sign rule | Transfer extras |
|---|---|---|---|---|
| `OPENING_BALANCE` | exactly 1 | zero allowed | none | n/a |
| `INCOME` | exactly 1 | non-zero (`ZERO_AMOUNT`) | `amountMinor > 0` else `WRONG_SIGN` | n/a |
| `EXPENSE` | exactly 1 | non-zero (`ZERO_AMOUNT`) | `amountMinor < 0` else `WRONG_SIGN` | n/a |
| `TRANSFER` | exactly 2 | both non-zero (`ZERO_AMOUNT`) | exactly one negative and one positive (`WRONG_SIGN`) | distinct `accountId`s (`TRANSFER_SAME_ACCOUNT`); equal stored currency strings (`TRANSFER_CURRENCY_MISMATCH`); `BigInteger` sum exactly zero (`TRANSFER_NOT_ZERO_SUM`) |

Exact arithmetic rules (spec 4.1): comparisons are direct `Long` comparisons (never `abs(Long.MIN_VALUE)`, never wrapping addition); the zero-sum check and every reference calculation use `BigInteger` widened intermediates. Consequences worth pinning: `OPENING_BALANCE` accepts `Long.MIN_VALUE`/`Long.MAX_VALUE`/zero; `INCOME` accepts `Long.MAX_VALUE`; `EXPENSE` accepts `Long.MIN_VALUE`; a sign-correct transfer containing `Long.MIN_VALUE` is rejected as `TRANSFER_NOT_ZERO_SUM` because its positive counterpart is outside the persisted `Long` range — never by wraparound.

The invalid rows are reachable through the public DTOs (any `NewTransaction` may carry a wrong entry list), so they are enforced at runtime by the validator and proven by the unit tests plus the repository-path instrumented tests.

Internal-only additions: `FinanceMatrixValidator`, `FinanceViolationException` (same file). Existing internal-only set is unchanged. Tests may read raw state through the internal DAO seam as in P1.

## File map

| Path | Responsibility |
|---|---|
| `core/data/src/main/java/com/paherdin/record/core/data/RepositoryResult.kt` | Add `FinanceViolation` enum + `FinanceRejected` member exactly per the normative delta; all P1 declarations unchanged |
| `core/data/src/main/java/com/paherdin/record/core/data/FinanceMatrixValidator.kt` | `internal` pure validator per the semantics table + `internal FinanceViolationException(violation)` |
| `core/data/src/main/java/com/paherdin/record/core/data/room/RecordDao.kt` | Add the account lookup used for endpoint resolution inside the transaction (get-by-id already exists for the entity set; a batch `getAccountCurrencies(accountIds)` is added if the single-query form is used) |
| `core/data/src/main/java/com/paherdin/record/core/data/RecordRepository.kt` | Insert steps 4–5 and the deterministic source-first write order into `saveTransactionAggregate`; add catch clause 2 |
| `core/data/api/expected-surface.txt` | Regenerated by the dump at Task 3 and reviewed one-to-one against the P1 list + the normative delta |
| `core/data/apiGuard/positive/*.kt` | Existing fixtures unchanged; add `FinanceRejectionUsage.kt` unpacking a `FinanceRejected(violation)` from a typed failure |
| `core/data/apiGuard/negative/*.kt` | Add one fixture per concern: reference to internal `FinanceMatrixValidator`, reference to internal `FinanceViolationException`, and a hypothetical raw/untyped transfer write taking a `Double` amount (must fail: unresolved symbol, proving no bypass path and no `Float`/`Double` money signature) |
| `core/data/src/test/java/com/paherdin/record/core/data/FinanceMatrixValidatorTest.kt` | JVM: every valid row, every violation, deterministic precedence (isolated + multi-invalid candidates), and the exact-arithmetic boundaries (zero, one minor unit, `Long.MIN_VALUE`, `Long.MAX_VALUE`, `(MIN, MIN)` transfer) |
| `core/data/src/test/java/com/paherdin/record/core/data/BalanceReproducibilityPropertyTest.kt` | JVM: fixed-seed generator of P2-valid histories (accounts; opening/income/expense/transfer creates; pure-model delete state transitions), `BigInteger` balance oracle, transfer zero-sum oracle, validator accept/reject assertions; seeds printed on failure |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/TransferValidationTest.kt` | Instrumented: endpoint/currency/same-account/sign/cardinality/zero-sum rejections through the public repository with no partial state; persisted fixture with `deletedAt` already present proves effective-entry exclusion |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/AtomicTransferVisibilityTest.kt` | Instrumented: transfer fault matrix with the external-reader latch protocol (P1 pattern) and balance observers |
| `core/data/src/androidTest/java/com/paherdin/record/core/data/room/TransferConcurrencyTest.kt` | Instrumented: same-ID concurrent transfers (one `Success` + one `DuplicateIdentity`); distinct transfers `A`/`B` racing a reader with the four allowed complete snapshots |
| `verification.md` | Created at Task 1 with the slice-state line; completed at Task 11 with the P2 execution evidence |

## Module dependency and build notes

- No new module edge; `checkModuleDependencies` must keep passing. `:core:data`'s `api(project(":core:model"))` is unchanged.
- Schema config, Room plugin, KSP pins, and `apiBoundaryCheck` wiring are inherited from P1 and untouched.
- No `fallbackToDestructiveMigration`, no new permissions, no manifest change.

---

### Task 1: Lifecycle — record plan approval, enter `in-progress`

**Files:**
- Modify: `plan.md` (metadata), `spec.md` (status), `AGENTS.md` (current-active-spec line), `docs/index.md` (current-active-spec line), `docs/roadmap.md` (Current position)
- Create: `docs/specs/tech-poc/p2-atomic-transfer/verification.md` (slice-state line)

- [ ] **Step 1: Record the plan approval**

The user's explicit approval of this plan (date/context from the approval message) is recorded in `plan.md` metadata, and the slice transitions to `in-progress` across every fact source: `spec.md` status → `in-progress` with the approval date/context, the current-active-spec lines in `AGENTS.md` and `docs/index.md`, the roadmap `Current position` line, and `verification.md`'s slice-state line. Commit as a lifecycle commit (`docs: approve P2 plan, P2 in-progress`) **before any implementation commit**. Implementation is forbidden until this step is done.

### Task 2: Verify toolchain and device prerequisites

**Files:** none (read-only checks)

- [ ] **Step 1: Confirm JDK, Gradle wrapper, and the reference device**

Run:

```powershell
java -version
.\gradlew.bat --version
adb devices -l
```

Expected: JDK 17+, Gradle 9.5.0, and the Xiaomi 14 Pro visible with USB debugging.

### Task 3: Public surface extension and allowlist regen

**Files:**
- Modify: `RepositoryResult.kt`, `core/data/api/expected-surface.txt`

- [ ] **Step 1: Add the normative delta**

Add `FinanceViolation` and `FinanceRejected` exactly per the normative delta; no P1 declaration changes.

- [ ] **Step 2: Compile and regenerate the allowlist**

Run:

```powershell
.\gradlew.bat :core:data:compileDebugKotlin
.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.ApiSurfaceTest"
```

The dump mode regenerates `expected-surface.txt`. Review the diff one-to-one: the P1 lines are unchanged and the delta is exactly `FinanceViolation` (six members) plus `FinanceRejected`. Commit the reviewed allowlist.

### Task 4: Finance validator and JVM unit tests

**Files:**
- Create: `FinanceMatrixValidator.kt`, `FinanceMatrixValidatorTest.kt`

- [ ] **Step 1: Implement the pure validator**

Per the semantics table, with the internal `FinanceViolationException`; zero-sum uses `BigInteger`; no database access; deterministic precedence.

- [ ] **Step 2: Truth-table unit tests**

Every valid row and every violation, precedence checks with isolated and multi-invalid candidates (e.g. zero + same-account → `ZERO_AMOUNT`; same-account + currency mismatch → `TRANSFER_SAME_ACCOUNT`; duplicate + finance mixed → unchanged cross-family precedence is not re-pinned here), and the boundary set: zero; one minor unit; `Long.MIN_VALUE`/`Long.MAX_VALUE` for each type; the `(MIN, MIN)` and `(MIN, +x)` transfer cases rejected as `WRONG_SIGN` / `TRANSFER_NOT_ZERO_SUM` under exact arithmetic.

Run:

```powershell
.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.FinanceMatrixValidatorTest"
```

Expected: PASS.

### Task 5: Balance-reproducibility property tests (JVM)

**Files:**
- Create: `BalanceReproducibilityPropertyTest.kt`

- [ ] **Step 1: Generator, oracle, and assertions**

Fixed seeds; the generator produces P2-valid histories over a small fixed currency set (so same-currency transfers exist) and a bounded amount range (extremes are unit-tested in Task 4), with a pure model that can mark an existing transaction deleted (soft-delete is a pure state transition — no persisted claim). For every prefix and the final state: `BigInteger` oracle equals the effective-entry sum per account; every transfer's `BigInteger` sum is zero; the validator accepts every generated valid aggregate. Invalid candidates are generated by mutation (flip sign, zero amount, wrong cardinality, same account, different-currency account, non-zero-sum pair) and each is rejected with its expected violation.

- [ ] **Step 2: Run with seeds recorded**

Run:

```powershell
.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.BalanceReproducibilityPropertyTest"
```

Expected: PASS; the seed(s) are printed in `verification.md` so every run is reproducible.

### Task 6: Repository integration

**Files:**
- Modify: `RecordDao.kt`, `RecordRepository.kt`

- [ ] **Step 1: Account endpoint resolution**

Inside `saveTransactionAggregate`'s `withTransaction`, after the duplicate check: resolve every entry `accountId` (single lookup or the batch DAO query); any missing account → `EndpointRejected`.

- [ ] **Step 2: Finance validation and deterministic write order**

After endpoint resolution: run `FinanceMatrixValidator` with the resolved currency map (catch clause 2 maps `FinanceViolationException` → `FinanceRejected`). For a validated transfer, derive source/destination from the signs and write negative-source first, positive-destination second, regardless of input iteration order; single-entry types write their one entry. The fault hook order and all other write steps are unchanged.

- [ ] **Step 3: Compile and run the P1 JVM suite**

Run:

```powershell
.\gradlew.bat :core:data:compileDebugKotlin :core:data:testDebugUnitTest
```

Expected: PASS — including the unchanged `SchemaContractTest` (proving no schema drift) and the P1 `ApiSurfaceTest` (allowlist regen happened in Task 3).

### Task 7: Instrumented transfer validation tests

**Files:**
- Create: `TransferValidationTest.kt` (androidTest)

- [ ] **Step 1: Repository-path rejection matrix**

Through the public surface: entry referencing a nonexistent account → `EndpointRejected`; same-account transfer → `TRANSFER_SAME_ACCOUNT`; differing-currency transfer → `TRANSFER_CURRENCY_MISMATCH`; zero, wrong-sign, wrong-cardinality, and non-zero-sum transfers → their violations; every case asserts no partial state (pre-existing state byte-compared via the internal read-only snapshot seam) and that the violation is a typed outcome with no raw SQLite exception escaping.

- [ ] **Step 2: Persisted effective-entry exclusion**

Persist an aggregate whose `deletedAt` is already present through the existing typed surface; assert its entries contribute nothing to the account's effective-entry sum (reference computation over the internal snapshot). This proves exclusion without claiming a public soft-delete transition.

### Task 8: Instrumented fault and concurrency tests

**Files:**
- Create: `AtomicTransferVisibilityTest.kt`, `TransferConcurrencyTest.kt` (androidTest)

- [ ] **Step 1: `AtomicTransferVisibilityTest` — transfer fault matrix**

Reuse the P1 latch protocol and fault hook. For a transfer with relations, inject failure at every stage/ordinal — `beforeTransaction`, `afterChildWrite(0)` (after the source entry, before the destination entry), `afterChildWrite(1)` (after the destination entry), each remaining child-write ordinal, `beforeCommit`, and `afterCommitResponse` (commit already durable). The external reader asserts at every observation: the complete `Transaction` + `AccountEntry` ID sets and fields, both affected accounts' entry histories, and `BigInteger`-derived balances — matching exactly the old complete state or the new complete state; never one side of the transfer, never a partial relation set, never a balance inconsistent with effective entries.

- [ ] **Step 2: `TransferConcurrencyTest` — same-ID and distinct transfers**

(a) Two concurrent saves of the same transfer ID: exactly one `Success` and one `DuplicateIdentity`; final state equals the successful aggregate's complete state. (b) Two distinct concurrent transfers `A` and `B` from a known initial state racing an independent reader: every observation equals one of the four allowed complete snapshots — initial, only `A`, only `B`, or both in either serial order — with full ID/field/entry-history/balance comparison, and never one side of either transfer. Fixed seeds where sequences are generated.

### Task 9: Machine checks and negative verification

**Files:**
- Create: `core/data/apiGuard/positive/FinanceRejectionUsage.kt`, `core/data/apiGuard/negative/ReferenceFinanceMatrixValidator.kt`, `core/data/apiGuard/negative/ReferenceFinanceViolationException.kt`, `core/data/apiGuard/negative/RawTransferWrite.kt`
- Modify: none beyond the fixture additions

- [ ] **Step 1: Consumer fixtures**

Positive: unpack `FinanceRejected(violation)` from a typed failure and branch on the violation. Negative, one concern per file: referencing the internal validator, referencing the internal exception, and a hypothetical `saveRawTransfer(accountId: String, amount: Double)` call (must fail: unresolved reference — no raw write path, no `Double` money signature).

- [ ] **Step 2: Run the machine checks**

Run:

```powershell
.\gradlew.bat :core:data:testDebugUnitTest :core:data:apiBoundaryCheck
```

Expected: `ApiSurfaceTest` PASS (allowlist exact both directions), `SchemaContractTest` PASS unchanged, `apiBoundaryCheck` PASS with the new fixtures.

- [ ] **Step 3: Negative verification**

Two directions, then revert and re-run: (i) make `FinanceMatrixValidator` public → the negative fixture compiles → `apiBoundaryCheck` FAIL; (ii) add a public `fun saveTransfer(...)`-style method to `RecordRepository` → `ApiSurfaceTest` FAIL (allowlist mismatch). Record both states in `verification.md` (mirrors P1's negative-verification evidence).

### Task 10: Full build and device regression

**Files:** none (verification)

- [ ] **Step 1: Clean full check with all tests**

Run:

```powershell
.\gradlew.bat clean check
```

Expected: `BUILD SUCCESSFUL`; `checkModuleDependencies: PASS`; lint 0 errors; `:core:data:apiBoundaryCheck` PASS; all JVM unit tests (P1 + P2) PASS.

- [ ] **Step 2: Instrumented suite on the reference device**

Run:

```powershell
.\gradlew.bat :core:data:connectedDebugAndroidTest
```

Expected: all P1 and P2 instrumented test classes PASS on the Xiaomi 14 Pro. Record the device build id:

```powershell
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release
adb shell getprop ro.build.display.id
```

- [ ] **Step 3: P0 launch regression**

Run:

```powershell
.\gradlew.bat :app:installDebug
adb shell am start -n com.paherdin.record/.MainActivity
Start-Sleep -Seconds 2
adb shell dumpsys activity activities | Select-String "com.paherdin.record"
```

Expected: `topResumedActivity` is `com.paherdin.record/.MainActivity`.

### Task 11: Complete the verification record and commit

**Files:**
- Complete: `docs/specs/tech-poc/p2-atomic-transfer/verification.md`

- [ ] **Step 1: Complete `verification.md`**

Record every actual command, environment, result, deviation, and remaining risk in the testing strategy's `Command`/`Environment`/`Result`/`Remaining risk` format, mapping every acceptance criterion (spec section 5.2, items 1–7) and the roadmap P2 exit gate to concrete commands and results; include the negative-verification evidence, the regenerated allowlist hash, the property-test seeds, the fault-matrix stage/ordinal enumeration, and remaining risk. **All actual commands, environments, results, and deviations live only in `verification.md`**; nothing is appended to this plan at completion.

- [ ] **Step 2: Confirm the working tree is clean except intended files**

Run:

```powershell
git status --short
git diff --check
```

- [ ] **Step 3: Commit the slice in logical units**

```powershell
git add core/data
git commit -m "feat(poc): P2 finance matrix validation, typed finance outcomes, and transfer atomicity tests"

git add docs/specs/tech-poc/p2-atomic-transfer/verification.md
git commit -m "docs: record P2 atomic transfer verification"
```

Expected: clean local commits; working tree clean.

### Task 12: Lifecycle — complete the slice

**Files:**
- Modify: `spec.md` (status → `completed`), `roadmap.md` (current position), `docs/index.md`, `AGENTS.md`, `verification.md` (final)

- [ ] **Step 1: Confirm acceptance and sync lifecycle documents**

After the user confirms acceptance: set `spec.md` status to `completed` with the completion date/context; update `roadmap.md` current position to P2 `completed`; sync the current-active-spec lines in `docs/index.md` and `AGENTS.md`; finalize `verification.md` if needed. Commit as a lifecycle commit (`docs: complete Tech PoC P2 and advance roadmap status`).

- [ ] **Step 2: Verify state**

Run:

```powershell
git log --oneline -6
git status --short
```

Expected: approval, plan, implementation, and completion history present; working tree clean. P3 is the next candidate after P2.

## Completion result

The P2 exit gate passes when `verification.md` shows: unit tests prove the entry-side matrix rows and the deterministic precedence with exact arithmetic (no wraparound can masquerade as a valid result); property tests prove balance reproducibility from effective entries over fixed-seed histories; Room fault tests prove the transfer commit is indivisible at every stage/ordinal with external-reader and balance evidence; the schema contract and the regenerated public-surface allowlist machine checks pass with negative verification; `clean check` and the P0 launch regression pass.

## Open decision tracked for later slices

The phase ownership of the recoverable-migration-snapshot machinery (schema-changing slice vs. the P5 roadmap gate) remains open and is resolved in the core documents and an ADR **before the first schema change after P1** — P3 is the next schema-changing candidate and may not enter implementation until that decision is resolved. P2 changes no schema and does not move this decision (spec 4.5).

# Tech PoC P2 — Atomic Transfer

- **Status:** `completed` — read-only history; later corrections use a new specification or ADR.
- **Spec approved:** 2026-08-05 — the user explicitly approved Revision 1 after review of commit `2156da3`.
- **Plan approved:** 2026-08-05 — the user explicitly approved `plan.md` Revision 1 after review of commit `997547d`.
- **Completed:** 2026-08-05 — all seven acceptance criteria of section 5.2 and the roadmap P2 exit gate have verification evidence in `verification.md`; the user confirmed acceptance after a two-axis code review of the implementation and the follow-up cleanups.
- **Drafted:** 2026-08-05
- **Revision 1:** 2026-08-05 — review corrections: scoped the matrix claim to P2-verifiable entry rules, made all arithmetic overflow-safe, required a fault boundary between transfer entries, corrected the create-only soft-delete boundary, defined deterministic finance-rejection precedence, and pinned concurrent-reader snapshots.
- **Roadmap:** [Tech PoC P2](../../../roadmap.md)
- **Domain facts and invariants:** [Domain model](../../../architecture/domain-model.md) (finance aggregate, transaction type invariant matrix, financial invariants)
- **Product behavior:** [MVP requirements](../../../product/mvp-requirements.md) (Journey 3)
- **Persistence and recovery policy:** [Data and storage](../../../architecture/data-and-storage.md)
- **Identity and relations decision:** [ADR 0002](../../../architecture/decisions/0002-entityref-and-strong-relations.md)
- **Version-1 persistence contract:** [ADR 0006](../../../architecture/decisions/0006-version-1-persistence-contract.md)
- **Module boundaries:** [Architecture overview](../../../architecture/overview.md)
- **Verification policy:** [Testing strategy](../../../engineering/testing-strategy.md)
- **Predecessor:** [Tech PoC P1](../p1-minimal-room-model/spec.md) — `completed`; P2 builds on the verified v1 schema and the P1 repository surface.

## 1. Purpose

Prove the same-currency transfer and the entry-side financial invariants that P2 can enforce on the existing v1 persistence model as one indivisible durable operation. Unit tests prove the `AccountEntry` cardinality/sign/sum rules for all four transaction types; property tests prove that every account balance is always reproducible from effective entries across arbitrary P2 histories; and Room fault tests prove that a transfer commit never leaves a one-sided effect observable at any injected failure boundary. Category-allocation completeness and atomic Account + opening-balance creation remain owned by M2 and are not falsely reported as proved by P2.

P2 is the first slice that enforces the matrix's entry-side rules in the write path. It does not add tables: the v1 schema (ADR 0006) already carries `Transaction.type`, `Account.currency`, and `AccountEntry.amountMinor`, so P2 proves the transfer and balance invariants without a schema change or migration.

## 2. Reference environment

The reference environment remains the frozen P0 configuration, unchanged from P1. Running instrumented tests on a different configuration makes the run invalid and requires an explicit approval record of a new baseline.

| Field | Recorded value |
|---|---|
| Device | Xiaomi 14 Pro (physical device, USB debugging) |
| Android baseline | Android 14 / API 34 (out-of-box baseline) |
| Actual system build | Recorded in `verification.md` at P2 verification (currently Android 16 / API 36 per P0/P1; recorded, not rejected, because `minSdk 34` keeps the app compatible) |
| AVD / host CPU-RAM fields | Not applicable — physical device reference |

## 3. Build baseline

- `minSdk = 34`, `targetSdk = 34`, `compileSdk = 36` — unchanged from P0/P1.
- Room 2.8.4, KSP 2.3.9, AGP 9.3 (built-in Kotlin 2.2.10), and the `androidx.room` Gradle plugin with the committed schema directory are already pinned and verified; P2 reuses the P1 combination.
- **No new dependencies are anticipated.** The finance-matrix validator is pure Kotlin and the property-test generator is hand-rolled; any dependency that the plan does introduce must first pass the engineering conventions' dependency review record at plan approval.
- `fallbackToDestructiveMigration` remains forbidden and must not appear anywhere in the build.
- The seven-module graph is unchanged; the P0 `checkModuleDependencies` task must keep passing.

## 4. Behavior scope

### 4.1 Finance matrix enforcement

P2 enforces the following entry-side subset of the [transaction type invariant matrix](../../../architecture/domain-model.md) for the four `TransactionType` values already in the schema. The rules, applied to the entries of the saved aggregate:

| Type | Entry cardinality | Entry amounts | Other rules |
|---|---|---|---|
| `OPENING_BALANCE` | Exactly one | Any signed `Long`, including zero, `Long.MIN_VALUE`, and `Long.MAX_VALUE`; the signed value defines the opening balance | None |
| `INCOME` | Exactly one, non-zero | Positive | None |
| `EXPENSE` | Exactly one, non-zero | Negative | None |
| `TRANSFER` | Exactly two, non-zero | One negative source and one positive destination; their mathematical algebraic sum is exactly zero | Both `accountId`s reference existing `Account`s, are distinct, and carry the same stored currency string |

Amount rules:

- Every `amountMinor` is a signed `Long` in integer minor units; `Float` and `Double` never appear in any money path or public signature.
- All validation and balance-oracle arithmetic is exact: implementations use overflow-safe comparisons or widened intermediates, never wrapping `Long` addition or `abs(Long.MIN_VALUE)`. JVM reference calculations use `java.math.BigInteger` (JDK standard library, not a new dependency).
- Boundary behavior is explicit: `OPENING_BALANCE` accepts zero and both `Long` extrema; `INCOME` accepts `Long.MAX_VALUE`; `EXPENSE` accepts `Long.MIN_VALUE` and computes its magnitude only in widened arithmetic; no valid transfer can contain `Long.MIN_VALUE`, because its required positive counterpart is outside the persisted `Long` range. After the earlier zero/sign checks, an otherwise sign-correct transfer containing it is rejected as non-zero-sum under exact arithmetic.
- A transfer with a zero entry is rejected (cardinality row demands two non-zero entries).

No category allocation exists in P2. P2 can prove that transfer writes create no allocation, but it does **not** claim to prove the income/expense allocation-count or allocation-sum rows; M2 introduces `CategoryAllocation` and owns those rules.

### 4.2 Balance contract

- Account balance equals the sum of all effective `AccountEntry` values; an entry is effective when its owning transaction is not soft-deleted (`EntityRef.deletedAt` absent).
- The property tests use exactly this definition with a widened exact-integer oracle; no balance field exists in the schema (the balance stays derived).
- P1's `saveTransactionAggregate` is create-only: saving an existing ID returns `DuplicateIdentity`. P2 adds no update/delete entry point and therefore makes no persisted soft-delete, un-delete, or restore-transition claim. Pure property histories may mark an existing model transaction deleted to exercise the effective-entry definition; instrumented fixtures may create an already soft-deleted aggregate through the existing typed surface to verify exclusion, but neither is evidence that a public soft-delete transition exists.

### 4.3 Typed outcome extension

The P1 public surface is unchanged except for one additive extension: a typed finance rejection.

- A new `Failure` member carrying a precise finance violation: `FinanceRejected(violation)` with a `FinanceViolation` taxonomy covering cardinality, zero amount, wrong sign, same-account transfer, currency mismatch, and non-zero-sum transfer. The exact member and enum names are pinned normatively in the plan.
- Missing or wrongly-typed entry account endpoints are rejected as `EndpointRejected`, the existing P1 outcome, with proactive checks inside the transaction (the same pattern P1 applies to relation endpoints).
- All P1 outcomes and their semantics are unchanged. Violations that are not finance matrix violations (XOR identity, duplicates, endpoints, storage, transaction, commit-response, unexpected) keep their P1 outcomes; no P1 outcome is renamed or re-meant.
- Repository classification is deterministic. Missing or wrongly typed account endpoints return `EndpointRejected` before finance validation. For candidates with resolvable endpoints, finance violations use this precedence: cardinality → zero amount → wrong sign → same-account transfer → currency mismatch → non-zero-sum. Tests isolate every violation and cover multi-invalid candidates so callers never depend on incidental check order.

### 4.4 Enforcement mechanism

- The finance matrix validator is a pure, internal component in `:core:data` (the same visibility pattern as the P1 XOR validator), JVM-testable without Room.
- The repository write path resolves every referenced `Account` **inside the same Room transaction** before any write. A missing endpoint returns `EndpointRejected`; otherwise the pure finance validator receives the entries plus their resolved stored currency strings and applies the deterministic classification above. Room's write serialization keeps outcomes stable under concurrency. A violation returns the typed outcome and commits nothing.
- The repository remains the only public write surface; the P1 surface entry points are unchanged — transfers are `NewTransaction` with `type = TRANSFER` and exactly two entries, not a separate DTO. Rationale: cardinality and sign rules apply to every type, so runtime validation is unavoidable regardless of DTO shape; a dedicated transfer DTO would duplicate the write path without making any remaining rule unrepresentable.
- The P1 fault-hook interface is reused. For a transfer, the repository derives the source and destination from their validated signs, writes the negative source first and the positive destination second regardless of input-set iteration order, and fires `afterChildWrite(ordinal)` after each insert. This creates a deterministic failure boundary after the source entry but before the destination entry. Both inserts remain inside one `withTransaction`, so every injected failure rolls back the pair.

### 4.5 Schema and migration boundary

- P2 changes no table, column, index, or FK: `databaseVersion` stays 1, no migration, none of the migration-recovery machinery.
- The open decision registered in the P1 plan — phase ownership of the recoverable-migration-snapshot machinery — remains open during P2. P3 is the next schema-changing candidate and may not enter implementation until the core documents and an ADR resolve that decision before its first schema change opens.
- The exported schema JSON and the committed `1.json` hash are unchanged by P2.

### 4.6 Deferred to later slices

Explicitly not part of P2:

- `Category`, `CategoryAllocation`, and the allocation-sum rules (M2).
- Public balance, account-history, or statistics read surfaces (M2).
- Account creation and the atomic `Account` + `OPENING_BALANCE` + `AccountEntry` creation journey (M2). P2 validates the opening transaction's entry cardinality/value only; it does not claim the matrix row's cross-aggregate creation guarantee.
- Cross-currency transfers, rate snapshots, or a full double-entry accounting model (deferred by the domain model).
- Currency normalization or validation of "valid in the account's currency": P2 compares the stored currency strings by exact equality; currency entry and validation belong to M2 account UX.
- Any UI, ViewModel, or navigation.

## 5. Invariants and acceptance criteria

### 5.1 Invariants in scope

- A `TRANSFER` transaction has exactly two non-zero `AccountEntry` rows on two distinct same-currency accounts, with algebraic sum zero.
- An income entry is positive and an expense entry is negative; both are exactly one non-zero entry.
- An `OPENING_BALANCE` transaction has exactly one entry; its signed value defines the opening balance.
- Account balance equals the sum of all effective entries and is reproducible from them at every point of every generated history.
- A `Transaction`, its entries, and its requested relations commit atomically; failure leaves none of the requested changes committed.

### 5.2 Acceptance criteria

The slice passes only when all of the following have evidence in `verification.md`:

1. **Finance matrix unit tests.** The pure validator accepts exactly the P2-scoped entry rows of section 4.1 and rejects every invalid row with its precise violation: entry-count violations per type, zero transfer/income/expense entries, wrong entry sign, same-account transfer, differing-currency transfer, and non-zero-sum transfer. The deterministic precedence is verified with isolated and multi-invalid candidates. Zero, one minor unit, `Long.MIN_VALUE`, and `Long.MAX_VALUE` behave exactly as section 4.1 specifies, with widened arithmetic proving that overflow can never masquerade as zero-sum or a positive magnitude. Every rejection through the repository leaves all prior state unchanged.
2. **Typed endpoint and currency checks.** An entry referencing a nonexistent `Account` is rejected `EndpointRejected`; the two transfer entries referencing the same `Account` and referencing accounts with differing stored currency strings are rejected with their typed finance violations. All checks run inside the transaction with stable outcomes under concurrency; nothing is committed.
3. **Atomic transfer visibility (Room fault tests).** A transfer aggregate (transaction, both entries, requested relations) commits as one indivisible transaction. With failure injected at every hook stage/ordinal — before the transaction, after the source entry before the destination entry, after the destination entry, after every remaining logical child write, before commit, and after the commit has succeeded — an external reader observes only the old complete state or the new complete state, never a one-sided entry (exactly one of the two entries present), never a partial relation set, and never an account history or balance inconsistent with effective entries. This is the "no one-sided effect at every injected failure boundary" evidence of the roadmap P2 exit gate.
4. **Balance reproducibility (property tests).** Fixed-seed generators produce P2-valid random sequences of opening, income, expense, and transfer creates plus pure-model soft-delete state transitions. For every prefix and the final state, a `BigInteger` reference oracle equals the exact sum of effective entries for each account, every transfer keeps its exact algebraic sum zero, and the validator accepts every generated valid aggregate and rejects every generated invalid candidate. Persisted fixtures separately prove that an aggregate whose `deletedAt` is already present contributes no effective entry; P2 does not claim a public soft-delete transition. Seeds are recorded on failure so the sequence is reproducible.
5. **Concurrent transfers.** Two concurrent saves of the same transfer ID produce exactly one `Success` and one `DuplicateIdentity`, with the final database state equal to the successful aggregate's complete state. For two distinct concurrent transfers `A` and `B` from a known initial state, an independent reader on its own Room connection may observe exactly one of these complete snapshots: the initial state, only `A` committed, only `B` committed, or both committed in either serial order. Each observation compares the complete `Transaction` and `AccountEntry` IDs and fields, both affected accounts' entry histories, and their `BigInteger`-derived balances; it must match one allowed snapshot and must never contain one side of either transfer. Raw SQLite/Room exceptions never escape the repository.
6. **Machine checks.** (a) The schema contract test passes unchanged against the committed `1.json`: exactly the six P1 tables, `databaseVersion = 1`, the ADR 0006 contract — P2 adds no table. (b) The `:core:data` public-surface allowlist is updated to the P2 normative list (the additive finance outcome only; no new write entry point) and matches exactly in both directions. (c) `apiBoundaryCheck` passes, with negative fixtures proving no path writes transfer entries bypassing the typed surface and no `Float`/`Double` appears in any public money signature.
7. **Regression.** `./gradlew clean check` passes (module graph, build, lint unchanged), and the app still installs and launches on the reference device.

## 6. Test evidence requirements

- Unit tests of the pure validator run on the JVM (`:core:data` test source set), mirroring the P1 validator tests.
- Property tests run on the JVM against the pure validator and the balance reference computation, with fixed seeds recorded on failure; instrumented tests on the reference device prove the persisted behavior (transfer persistence and reload, fault matrix, concurrency) through the public repository surface and internal read-only snapshots.
- Fault-injection evidence names every hook stage/ordinal, the injected boundary, the observed complete state per the matrix, and the final state — the P1 external-reader latch protocol is reused.
- `verification.md` maps every acceptance criterion and the roadmap P2 exit gate to concrete commands and results in the testing strategy's `Command`, `Environment`, `Result`, `Remaining risk` format; a build-only result cannot stand in for the required tests.

## 7. Non-goals

P2 explicitly does not:

- Change the schema, run a migration, or build any migration-recovery machinery.
- Implement `Category`/`CategoryAllocation`, allocation rules, or allocation-sum evidence (M2).
- Expose a public balance or account-read API; the balance contract is proven by property tests against the reference computation (M2 owns the user-facing surface).
- Implement account creation, opening-balance creation UX, currency validation or normalization, or cross-currency transfer.
- Add any UI, ViewModel, or navigation; add `NewTransfer` or any second write entry point.
- Change any P1 outcome, table, or public declaration that is not the additive finance outcome of section 4.3.
- Silently diverge from any core-document fact. If implementation exposes a conflict with a core document, the documentation-first protocol applies before any code change and the spec returns to review.

## 8. Dependency review boundary

Every dependency introduced by P2 is recorded at plan approval per the engineering conventions: version, license, maintenance evidence, compatibility impact, and removal path. No new dependency is anticipated; the validator and generator are hand-rolled pure Kotlin and the P1 pinned toolchain is reused. A dependency is rejected when its license is incompatible, its maintenance status is unknown, it requires excessive permissions, it introduces undisclosed network behavior, or no practical removal path exists. No ads, analytics, crash-reporting, or closed-source tracking SDK.

## 9. Risks

- **Concurrent validation outcomes.** If validation ran outside the transaction, concurrent saves could produce unstable outcomes; the P1 in-transaction precheck pattern is reused and the concurrency criterion (5.2.5) verifies it.
- **Property generator validity.** The balance invariant is only meaningful over P2-valid entry histories; the generator is constrained to section 4.1, separately models deletion state, and the validator-acceptance assertion (5.2.4) guards it. Seeds are recorded on failure.
- **Exact arithmetic.** Kotlin `Long` addition and `abs(Long.MIN_VALUE)` can wrap. Section 4.1 therefore requires widened/overflow-safe arithmetic, and boundary/property tests make any wraparound a blocking failure.
- **Currency equality semantics.** P2 compares the already stored currency strings by exact equality. It neither normalizes nor validates currency input; M2 owns canonical currency entry, after which the same equality check operates on canonical stored values.
- **Opening-balance zero.** Zero is valid because the owning domain matrix requires exactly one signed entry and imposes no non-zero condition; the domain model now states this explicitly.
- **Soft-delete transition boundary.** The P1 repository surface is create-only. P2 proves effective-entry exclusion without claiming update, delete, un-delete, or restore behavior; those transitions require a later explicitly authorized surface.
- **No schema change expectation.** If finance enforcement exposes a schema need, that is a plan revision and a migration decision under section 4.5 — never a mid-execution schema edit.
- **Device test time and USB stability.** The fault and concurrency suites run on the physical reference device; the plan must account for device time.
- The reference device runs API 36; behavior on the upgraded system is recorded at verification, not rejected — the same rule as P0/P1.

## 10. Lifecycle path

- `draft` (this document): behavior, boundaries, and acceptance are clarified here. No implementation.
- `approved`: the user explicitly accepts this spec; the approval date/context is recorded.
- `in-progress`: the user approves the plan (recording exact tasks, file changes, and verification commands); implementation may begin.
- `completed`: acceptance criteria and the roadmap P2 exit gate have verification evidence; P3 may then start.

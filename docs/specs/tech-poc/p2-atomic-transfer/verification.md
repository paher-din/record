# Tech PoC P2 — Verification Record

- **Slice state:** `completed` (2026-08-05) — plan Revision 1 was approved by the user on 2026-08-05 (per plan Task 1); the P2 execution evidence below is the Task 11 completion record, and the user confirmed acceptance after a two-axis code review and the follow-up cleanups (plan Task 12).
- **Date:** 2026-08-05
- **Spec:** [spec.md](spec.md) — acceptance criteria section 5.2
- **Plan:** [plan.md](plan.md)

All commands ran on the host recorded below. The shell default `java` is JRE 1.8.0_501 and `adb` is not on `PATH`; every Gradle invocation set `JAVA_HOME` explicitly (process-local, path not recorded, per the plan Task 2 rule) and adb was invoked by absolute path under the resolved Android SDK. Reference device: Xiaomi 14 Pro (`23116PN5BC`, `shennong`).

## Environment

Host: Windows 11 Home China 10.0.26200 (x64), AMD Ryzen 7 8845H (8 cores), 31.3 GiB RAM; JDK Temurin 17.0.20+8 (user-scope install; the local path is deliberately not recorded); Gradle 9.5.0; AGP 9.3.0 built-in Kotlin 2.2.10; KSP 2.3.9; Room 2.8.4; compileSdk 36.

Toolchain finding at Task 2: the only other installed JDK (OpenJDK 25.0.2) fails the build — Kotlin 2.2.10 supports at most the JVM 24 target, so `compileKotlin` falls back to 24 while `compileJava` targets 25, and AGP's target-validation rejects the pair. The P1 Temurin 17 toolchain was located and used instead; no build configuration was changed.

### Task 3 — public surface extension and allowlist regeneration

| Field | Recorded value |
|---|---|
| Command | `JAVA_HOME=<Temurin 17> .\gradlew.bat :core:data:compileDebugKotlin`; dump run with `-PapiSurfaceDump=true`; JUnit XML `system-out` read as the deterministic dump source; allowlist copied and reviewed; normal-mode rerun |
| Environment | Host as above |
| Result | PASS — dump produced 28 lines: all 26 P1 lines byte-unchanged plus exactly the normative delta (`data class RepositoryResult.Failure.FinanceRejected(val violation: FinanceViolation) : RepositoryResult.Failure` and the six-member `enum class FinanceViolation`). Normal assertion mode `testDebugUnitTest --tests ApiSurfaceTest` PASS. |
| Remaining risk | None. |

### Task 4 — finance validator and JVM unit tests

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.FinanceMatrixValidatorTest"` |
| Environment | Host as above |
| Result | PASS — all 18 tests: every valid row (opening incl. zero/`Long.MIN_VALUE`/`Long.MAX_VALUE`; income `MAX`/1; expense `MIN`/`-1`; transfer one-negative-one-positive incl. `(MAX, -MAX)`), every violation, deterministic precedence with multi-invalid candidates (zero beats sign/extras; sign beats transfer extras; same-account beats currency/zero-sum; cardinality beats everything), and exact-arithmetic boundaries (`(MIN, MIN)` → `WRONG_SIGN`; `(MIN, MAX)` and `(MIN, +x)` → `TRANSFER_NOT_ZERO_SUM` — never by wraparound; one-minor-unit transfer valid). |
| Remaining risk | None. |

### Task 5 — balance-reproducibility property tests

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.BalanceReproducibilityPropertyTest"` |
| Environment | Host as above |
| Result | PASS — seeds `0xC0FFEE`, `0xBEEF`, `20260805`, 400 steps each: `BigInteger` oracle equals the effective-entry sum for all four accounts after every step; every transfer keeps exact zero sum; the validator accepts every generated valid aggregate; mutated invalid candidates are rejected with their precise violations. Implementation fix during the task: the initial generator computed magnitudes as `1L + nextLong() % 1_000_000L`, which is negative for negative `nextLong()`; magnitudes now mask the sign bit (`nextLong() and Long.MAX_VALUE`). The fix changed test code only, never production logic. |
| Remaining risk | None. |

### Task 6 — repository integration

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:compileDebugKotlin :core:data:testDebugUnitTest` |
| Environment | Host as above |
| Result | PASS — endpoint resolution (deduplicated `getAccount` lookups inside the `withTransaction`; missing → `EndpointRejected` before finance), finance validation via catch clause 2 (`FinanceViolationException` → `FinanceRejected`), and the deterministic write order (entries sorted ascending: negative source at ordinal 2, positive destination at ordinal 3, relations at ordinal 4; single-entry types keep P1 ordinals 2/3) compile; the full JVM suite passes including the unchanged `SchemaContractTest`. |
| Remaining risk | None. |

### Task 7 — P1 fixture migration and transfer validation tests

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:compileDebugAndroidTestKotlin` |
| Environment | Host as above |
| Result | PASS — five P1 instrumented classes migrated to the newly enforced finance boundary: `PersistReloadTest` (tx-2 now carries account + positive entry; view and physical-table expectations updated), `TypedEndpointTest` (setUp and both relation-rejection payloads now finance-valid so the relation endpoint remains the assertion), `AtomicVisibilityTest` (note and relation-replacement matrices' setup transactions now finance-valid with updated full snapshots; the saveTransactionAggregate matrix was already finance-valid and is unchanged), `ConcurrencyTest` (both tests' transactions now finance-valid), `XorIdentityTest` (duplicate test migrated; the cross-table collision test deliberately keeps a finance-invalid payload because XOR rejection precedes finance validation). `TransferValidationTest` added: missing and wrongly-typed account endpoints → `EndpointRejected`; same-account / currency-mismatch / zero / wrong-sign / cardinality / non-zero-sum → typed `FinanceRejected`; cross-family precedence (`DuplicateIdentity` beats finance; endpoints beat finance; finance beats missing relation endpoint); no partial state on any rejection; persisted `deletedAt`-present aggregate proves effective-entry exclusion. Instrumented run on the reference device pending (Task 10 Step 2). |
| Remaining risk | Device-dependent evidence pending. |

### Task 8 — instrumented fault and concurrency tests

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:compileDebugAndroidTestKotlin` |
| Environment | Host as above |
| Result | PASS (compile) — `AtomicTransferVisibilityTest` injects failure at every stage/ordinal (beforeTransaction; after `EntityRef` 0; after `Transaction` 1; after the negative source and before the positive destination 2; after the destination 3; after the relation stage 4; beforeCommit; afterCommitResponse), with the external reader asserting complete old/new states, both accounts' entry histories, and `BigInteger` balances at every observation; the write passes the destination entry first to prove deterministic source-first ordering. `TransferConcurrencyTest` covers same-ID concurrent transfers (one `Success` + one `DuplicateIdentity`) and distinct `A`/`B` transfers racing an independent reader whose observations must equal one of the four complete snapshots (initial / only `A` / only `B` / both) — read via a new atomic `consistentSnapshot` test helper (single connection, explicit deferred `BEGIN` in WAL, so a racing reader can never observe a torn mix of two commits across tables). Instrumented run on the reference device pending (Task 10 Step 2). |
| Remaining risk | Device-dependent evidence pending. |

### Task 9 — machine checks and negative verification

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:testDebugUnitTest :core:data:apiBoundaryCheck`; schema hash check |
| Environment | Host as above |
| Result | PASS — `ApiSurfaceTest` PASS with the two new internal FQCNs pinned (`FinanceMatrixValidator`, `FinanceViolationException`); `SchemaContractTest` PASS unchanged; `apiBoundaryCheck` PASS with **3 positive / 19 negative** fixtures (new: `FinanceRejectionUsage` exhaustive direct-subtype `Failure` match incl. `FinanceRejected`; negative references to both internal types; `RawTransferWrite` referencing a hypothetical `saveRawTransfer(…, Double)`). Schema JSON SHA-256 equals the pinned P1 value: `0e2ed86987d13b8c31667f35bc543a2b1bd7e694633df744a43ea379b2784d81` — byte-identical, no schema change. |
| Remaining risk | None. |

Negative verification (both directions, executed then reverted; reverted state re-verified PASS):

| Direction | Experiment | Observed |
|---|---|---|
| (i) internal visibility widened | `FinanceMatrixValidator` made public | `apiBoundaryCheck` FAILED: `NEGATIVE fixture must fail … ReferenceFinanceMatrixValidator.kt (exit=0)`; after revert, PASS |
| (ii) new public write method | `suspend fun saveTransfer(source, destination, amountMinor)` added to `RecordRepository` | `ApiSurfaceTest.publicSurfaceMatchesAllowlistExactly` FAILED (allowlist mismatch); after revert, PASS |

After both reversals, `git diff --stat` contained none of the temporary mutations.

### Task 10 Step 1 — clean full check

| Field | Recorded value |
|---|---|
| Command | `JAVA_HOME=<Temurin 17> .\gradlew.bat clean check` |
| Environment | Host as above |
| Result | PASS — `BUILD SUCCESSFUL in 3m 50s` (exit 0); `checkModuleDependencies: PASS (7 modules, graph conforms)`; lint 0 errors; all JVM unit tests (P1 + P2) PASS; `:core:data:apiBoundaryCheck` PASS. |
| Remaining risk | None. |

### Task 10 Step 2 — instrumented suite on the reference device

| Field | Recorded value |
|---|---|
| Command | `JAVA_HOME=<Temurin 17> .\gradlew.bat :core:data:connectedDebugAndroidTest` |
| Environment | Host as above; device `c55ad7e4` / `23116PN5BC` (Xiaomi 14 Pro, `shennong`), Android 16 / API 36, build `BP2A.250605.031.A3` (recorded, not rejected — the same rule as P0/P1) |
| Result | PASS — `BUILD SUCCESSFUL in 6m 52s`; all **29 instrumented test methods PASS**: `PersistReloadTest` (4, migrated), `XorIdentityTest` (4, migrated, incl. the deliberately finance-invalid cross-table collision case proving XOR precedes finance), `ForeignKeyTest` (2), `TypedEndpointTest` (4, migrated), `AtomicVisibilityTest` (3 fault matrices, migrated), `ConcurrencyTest` (2, migrated), `TransferValidationTest` (7: endpoint/currency/same-account/matrix rejections, cross-family precedence, persisted effective-entry exclusion), `AtomicTransferVisibilityTest` (transfer fault matrix: 9 cases incl. every stage/ordinal 0–4 with external-reader balance evidence and deterministic source-first ordering), `TransferConcurrencyTest` (2: same-ID one `Success` + one `DuplicateIdentity`; distinct `A`/`B` race with four-allowed-snapshot reader via the atomic `consistentSnapshot` read). |
| Remaining risk | None. |

Execution deviations during this step: (a) the first two attempts failed on a Windows file lock — a concurrently running Gradle invocation held the per-test logcat file (`java.nio.file.FileSystemException` on `androidTest-results/...`); resolved by stopping the stray daemon and clearing the stale output directory, then re-running. (b) `TransferValidationTest.entryWithMissingAccountEndpointIsRejected` asserted the entire database empty, but its own setup had created three accounts; the assertion was corrected to prove only that the rejected aggregate leaves no trace (no `EntityRef`, `Transaction`, `AccountEntry`, or `Relation`), and the class re-ran green before the full suite.

### Task 10 Step 3 — P0 launch regression

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :app:installDebug`; `adb shell am start -n com.paherdin.record/.MainActivity`; `adb shell dumpsys activity activities` |
| Environment | Same device as Step 2 |
| Result | PASS — `Installed on 1 device`, `BUILD SUCCESSFUL`; after `am start`, `topResumedActivity=ActivityRecord{... com.paherdin.record/.MainActivity}` (re-run after a transient first install failure; the immediate re-run installed cleanly). |
| Remaining risk | None. |

## Remaining risk (as of this writing)

- None blocking. All seven acceptance criteria of spec section 5.2 have evidence: criterion 1 from the JVM validator unit tests and `TransferValidationTest`; criterion 2 from `TransferValidationTest` (endpoint/currency checks with cross-family precedence); criterion 3 from `AtomicTransferVisibilityTest` (every stage/ordinal with external-reader and balance evidence); criterion 4 from `BalanceReproducibilityPropertyTest` (fixed seeds `0xC0FFEE`/`0xBEEF`/`20260805`); criterion 5 from `TransferConcurrencyTest`; criterion 6 from `ApiSurfaceTest` + `SchemaContractTest` + `apiBoundaryCheck` (3/19 fixtures) with both negative-verification directions and the pinned schema SHA-256; criterion 7 from `clean check` (Task 10 Step 1) and the launch regression above.
- Known non-blocking items: the reference device runs API 36 (recorded, not rejected); the local `JAVA_HOME` path remains deliberately unrecorded; one test-code generator bug (negative modulo, Task 5) and one test-assertion bug (missing-account snapshot, Task 10 Step 2) were found and fixed during execution, each documented above.
- Post-implementation code review (open-code-review, commits `295ef7b`/`3c42c35`) found no hard standard violation and no scope creep, and fixed four test-side cleanups (dead `opTrace`, `Pair` → `NewAccountEntry` model, shared validator-test helpers, an overstated "proving source-first order" comment — commit `6b80f9a`, JVM suite re-verified). Two evidence nuances remain recorded, non-blocking: (a) criterion 2's "stable outcomes under concurrency" for the rejection path is asserted structurally (checks run inside the one `withTransaction`, the P1-proven serialization pattern) rather than empirically raced; (b) criterion 4's invalid candidates are a fixed mutation list rather than per-history generated mutations — the criterion's accept/reject assertions are satisfied either way.

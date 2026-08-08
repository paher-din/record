# Tech PoC P4 — Chinese Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Status:** `completed` — read-only history; Tasks 0–10 all checked; the user accepted ADR 0009 on 2026-08-08 after the independent 60-block/1,800-sample recomputation; later corrections use a new specification or ADR.
- **Spec:** [spec.md](spec.md) — `completed` Revision 2; the user explicitly accepted ADR 0009 and P4 acceptance evidence on 2026-08-08.
- **Goal:** Run the frozen comparison protocol on the actual API 36 reference device and record the selection in ADR 0009: FTS4 `unicode61` vs normalized paged `LIKE` over `corpus-v1` and `manifest-v1`, with exact ordered first-page correctness and every per-query p95 ≤ 300 ms as candidate eligibility gates, followed by the frozen candidate-level maximum-CV/maximum-p95/`LIKE` rule.
- **Zero production change is a plan invariant:** no public API delta, no schema change (`RecordDatabase` stays `databaseVersion = 2`), no new dependency, no module-graph change. All P4 work lands in `:core:data` test sources (`test` for JVM logic, `testFixtures` for the shared corpus/normalizer/oracle, `androidTest` for the candidates and the device benchmark).

**Architecture:** enable the Android library `testFixtures` component in `:core:data`; the module's JVM tests consume it automatically, and Task 1 proves both `compileDebugUnitTestKotlin` and `compileDebugAndroidTestKotlin` can resolve the same fixture classes without a self-project dependency. Fixtures own the frozen generator, normalizer, query definitions, expected-manifest artifact, oracle, metric formulas, and selection rule. Candidate L and Candidate F are separate test-scoped Room file databases with the same `SearchDocument` shape and fill transaction. Candidate F adds a raw-DDL external-content FTS4 table whose insert trigger explicitly writes `docid = new.rowid`; no production `@Fts4` entity is added. The harness creates and fills one fresh database file per candidate run set, alternates candidate-first order by query index, measures only DAO-to-materialized-first-page time, and emits machine-readable evidence.

**Tech Stack:** unchanged from P1–P3 — Gradle 9.5.0, AGP 9.3.0 (built-in Kotlin 2.2.10), KSP2 2.3.9, Room 2.8.4, `kotlinx-coroutines-core` 1.8.1 (pinned Room API dependency), `kotlin-metadata-jvm` 2.2.10 (test-time), androidx.test runner/ext-junit, JUnit 4.13.2. Normalization uses JDK `java.text.Normalizer` (NFKC) and `String.lowercase(Locale.ROOT)`; FTS4 is SQLite built-in; the corpus generator is hand-rolled with a fixed seed (the P2/P3 pattern). `testFixtures` is an AGP library feature, not a new dependency. No new dependency declaration is added; if the pinned combination fails during execution, this plan is revised and re-approved first — versions are never switched mid-execution.

**Reference environment (frozen):** Xiaomi 14 Pro (physical, USB debugging), actually running Android 16 / API 36. Android 14 / API 34 remains the product `minSdk`/`targetSdk` baseline, not the measurement system-build claim. Task 1 records the device model, serial alias, build fingerprint, SDK level, CPU ABI/model, and total RAM; Task 8 rechecks the same values immediately before measurement. Any difference invalidates the run pending a newly approved baseline.

**Namespaces:** `com.paherdin.record.core.data.search` for the JVM search logic (testFixtures) and `com.paherdin.record.core.data.search` for the androidTest candidates/benchmark. Production namespaces are untouched.

**Pre-approval dependency validation:** none required. P4 introduces no resolved artifact: FTS4 and `LIKE` are SQLite built-ins, `Normalizer` is a JDK API, and Android library test fixtures are an AGP mechanism. Task 1 is a compile/smoke gate; if the exact test-fixture wiring or platform FTS4 support fails, stop and revise the approved plan before changing the architecture.

## Dependency review record

No new dependency declaration enters the version catalog or any build in P4. The P1 review record remains valid for every dependency reused; the P4 plan relies only on the already-pinned Room/SQLite/JDK/AGP combination. Any later change that adds or changes a resolved artifact must first pass the engineering conventions' dependency review record at plan approval.

## Revision record

- **Revision 1:** 2026-08-08 — initial plan; not approved or executed.
- **Revision 2:** 2026-08-08 — fixes ordered-first-page correctness, candidate metric aggregation, acceptance eligibility, actual API 36 baseline, UUID identity, FTS `docid` mapping, execution-order controls, and every command/decision placeholder. The seed, exact 30 query strings, FTS MATCH translation, formulas, DDL, and file inventory below are frozen before approval. Corpus/manifest hashes are measurement evidence written only to `verification.md` and ADR 0009; execution never edits this approved plan. Any later plan change requires an explicit Revision 3 and user reapproval.
- **Revision 2 approved:** 2026-08-08 — the user explicitly approved spec Revision 2 and this plan together (approval date/context recorded here); P4 is `in-progress`. Task 3 freezes the corpus seed and hash, and Task 4 freezes the `manifest-v1` query list, per the frozen plan text above; execution never edits this approved plan.

## Public API of `:core:data` — no delta

The P1/P2/P3 normative declaration list is inherited **unchanged**. P4 adds no public member, no repository method, no Room table, no schema export, and no new file under `src/main`. The `api/expected-surface.txt` allowlist and the `apiBoundaryCheck` machine checks are untouched; Task 10 reruns them unchanged to prove the zero-delta invariant.

## Normalization v1 (frozen)

The normalization pipeline is `Unicode trim → NFKC → bounded case folding`, versioned as `normalizationVersion = 1`, independent of the catalog `nameKey` axis. `SearchNormalizer.normalize` trims the frozen Unicode White_Space code-point set, calls `Normalizer.normalize(value, NFKC)`, then `lowercase(Locale.ROOT)`. `SearchNormalizer.escapeLike` transforms `\` → `\\`, `%` → `\%`, and `_` → `\_` in that order; the DAO uses `ESCAPE '\'`.

The corpus boundary is CJK plus ASCII English and the explicit full-width ASCII compatibility cases below. NFKC may map compatibility CJK to cased-neutral CJK; only ASCII letters remain cased, so `lowercase(Locale.ROOT)` is complete and expansion-free for admitted text. JVM tests enumerate every admitted ASCII letter/full-width counterpart, CJK compatibility samples, supplementary CJK, the frozen White_Space set, and forbidden `ß` as an out-of-boundary guard.

## Corpus `corpus-v1` (frozen at Task 3)

Hand-rolled deterministic generator with frozen seed `2026080804L`, producing exactly 10,000 records. Record `i` uses `UUID.nameUUIDFromBytes("corpus-v1:2026080804:$i".toByteArray(UTF_8)).toString()` as a structurally valid deterministic UUID fixture; `type` is a separate discriminator and never appears as an ID prefix. Vocabulary choice is arithmetic rather than call-order-sensitive randomness: the first phrase index is `floorMod(seed + 31L * i + languageSalt, vocabularySize)`, and the optional second phrase index is `floorMod(seed + 17L * i + 3 + languageSalt, vocabularySize)`, where salts are Chinese `11`, English `23`, and mixed `37`.

- Record-type mix by index: `0..6999` Transaction, `7000..9999` Note.
- Language mix within each type, assigned by the index modulo 20: 11 Chinese slots, 6 English slots, 3 mixed slots, yielding exactly 55%/30%/15% overall.
- Frozen vocabulary: Chinese `早餐,午餐,晚餐,交通,地铁,咖啡,会议,项目,旅行,书店,周末,预算,记录`; English `expense,Expenses,transport,cart,project notes,coffee,meeting,budget,weekend,trip,ledger,bookstore`; mixed `咖啡 coffee,项目 project,地铁 metro,会议 notes,周末 trip`.
- Transaction fields: title is one vocabulary phrase; body is present exactly when `i % 5 != 0`; auxiliary is `日常 account` for Chinese, `daily account` for English, and `日常 daily` for mixed. Note fields: title is present exactly when `i % 5 < 2`; body is one or two vocabulary phrases; auxiliary is `记录 tag`, `notes tag`, or `记录 notes` by language.
- Boundary fixtures are deterministic corpus members, not post-generation mutations: append ` ＡＢＣ` to index 0 title, ` 50%` to index 1 title, ` under_score` to index 2 title, ` 𠀀` to index 3 title, and ` 﨑` to index 4 title before searchable-text normalization. No record contains the three zero-hit query strings.
- `normalizedText` is normalization v1 over non-null title/body/auxiliary joined by a single newline. `occurredAt = 2026-01-01T00:00:00Z - floor(i / 3) minutes`, deliberately creating three-way timestamp ties; `updatedAt = occurredAt + (i % 1440) minutes`.

Task 3 records the canonical UTF-8 `corpus-v1` SHA-256 in `verification.md`. Canonical rows use field order `entityRefId,type,title,body,auxiliary,normalizedText,occurredAt,updatedAt`; every non-null field is encoded as UTF-8 byte length, colon, then raw bytes; null is `-1:`; fields use one tab separator and records one LF separator. The approved plan is not edited with the runtime hash.

## Query manifest `manifest-v1` (frozen at Task 4)

The exact `manifest-v1` query texts, in execution order, are frozen here:

```text
餐
早餐
交通
地铁
咖啡
会议
项目
旅行
书店
周末
expense
EXPENSE
exp
art
transport
project notes
coffee
meeting
budget
weekend
咖啡 coffee
项目 project
地铁 metro
会议 notes
周末 trip
量子火箭
unfindabletoken
火星 ledger
ＡＢＣ
50%
```

The oracle scans the full corpus, applies code-point substring containment, sorts by `occurredAt DESC, entityRefId ASC`, then takes 50. Task 4 generates and commits `ExpectedManifestV1.kt`, which contains the exact ordered ID list for each frozen query; its canonical SHA-256 is recorded in `verification.md`. A fresh oracle computation must equal that artifact byte-for-byte. The complete pre-pagination list is diagnostic only and is never compared with a first-page DAO result.

## Reference oracle

Pure JVM function `expectedFirstPage(corpus, query, pageSize = 50)`: normalize the query, scan the corpus for code-point substring containment in `normalizedText`, sort by the frozen order, and return `take(pageSize)`. `expectedAllMatches` may exist only for diagnostics and cannot feed candidate correctness.

## Selection rule (pure function)

`MeasurementStats.from(samplesNs)` requires exactly 30 positive samples. After ascending sort, p50 is element 15 and p95 element 29 in one-based notation (Kotlin indexes 14 and 28); CV is population standard deviation divided by the positive arithmetic mean. `CandidateMetrics.comparisonP95Ns` and `comparisonCv` are the maximum per-query values. `selectCandidate(like, fts)` requires exact correctness plus `comparisonP95Ns <= 300_000_000`; both eligible compare `abs(cvL - cvF) >= 0.05`, then `abs(p95L - p95F).toDouble() / max(p95L, p95F) >= 0.10`, then choose `LIKE`. Tests cover 0.049/0.05, 0.099/0.10, exact 300 ms/one nanosecond over, one/both ineligible, and tie.

## Candidate implementations (androidTest)

Both candidates are Room databases built into `targetContext.cacheDir/p4-search-benchmark/{runId}/` with WAL enabled, where `runId` is the instrumentation argument `p4RunId` and is recorded in `verification.md`. The directory is new for each explicit test run and is retained as evidence; P4 performs no file deletion. Any later cleanup requires the user's separate approval under the workspace deletion boundary. `SearchDocument` has an auto-generated integer `rowId` primary key, unique `entityRefId`, separate `type`, title/body/auxiliary/normalizedText, occurredAt, and updatedAt; both candidates use identical indexes on `entityRefId` and `(occurredAt, entityRefId)`.

- **Candidate L:** DAO query `SELECT entityRefId FROM SearchDocument WHERE normalizedText LIKE '%' || :q || '%' ESCAPE '\' ORDER BY occurredAt DESC, entityRefId ASC LIMIT 50` with the normalized query (ESCAPE handling for `%`/`_` in the query).
- **Candidate F DDL:** `CREATE VIRTUAL TABLE SearchDocument_fts USING fts4(normalizedText, content='SearchDocument', tokenize=unicode61)` and `CREATE TRIGGER search_document_ai AFTER INSERT ON SearchDocument BEGIN INSERT INTO SearchDocument_fts(docid, normalizedText) VALUES (new.rowId, new.normalizedText); END`. A smoke test asserts every FTS `docid` equals the corresponding content-table `rowId`. Only inserts occur in P4, so update/delete triggers are intentionally out of scope. The query is `SELECT entityRefId FROM SearchDocument WHERE rowId IN (SELECT docid FROM SearchDocument_fts WHERE SearchDocument_fts MATCH :m) ORDER BY occurredAt DESC, entityRefId ASC LIMIT 50`.
- **Frozen MATCH translation:** normalize the query; split on one-or-more frozen Unicode White_Space code points; reject an empty token list; double every embedded `"`; append `*` to every token; join tokens with one ASCII space; wrap the complete expression in double quotes. Examples: `exp` → `"exp*"`, `project notes` → `"project* notes*"`, `ＡＢＣ` → `"abc*"`, `50%` → `"50%*"`. This deliberately provides FTS phrase/prefix semantics, not a substring fallback.
- Fill: both databases are filled from the identical frozen corpus (single `insertAll` transaction); fill time is not measured.

## Benchmark harness (androidTest)

Task 8 creates/fills both fresh candidate files before timing. For manifest query index `i`, execution order is L→F when `i` is even and F→L when odd; each candidate/query block runs 5 unmeasured warmups then 30 measured DAO-to-materialized-list calls. Before the suite the device must be idle for five minutes, airplane mode must be enabled, the app must be foreground, and `PowerManager.currentThermalStatus` must be `NONE` or `LIGHT`; thermal status is recorded before and after every block, and `MODERATE` or worse invalidates the full run. All samples use `SystemClock.elapsedRealtimeNanos()`, must be positive, and are written as UTF-8 JSON Lines to instrumentation output plus an app-private evidence file copied by the test runner. Harness self-checks prove exact counts, counterbalancing, nearest-rank indexes, population CV, ordered-list assertion, and thermal invalidation.

## File inventory

| File | Purpose |
|---|---|
| `core/data/build.gradle.kts` | Add `android { testFixtures { enable = true } }`; do not add a self-project dependency or a resolved artifact |
| `core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/search/SearchNormalizer.kt` | Normalization v1, frozen White_Space set, and LIKE escaping |
| `core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/search/CorpusGenerator.kt` | Exact seed/algorithm for deterministic `corpus-v1` and canonical hash bytes |
| `core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/search/QueryManifest.kt` | The exact 30 frozen query strings and stable query IDs `q01`…`q30` |
| `core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/search/ExpectedManifestV1.kt` | Generated-once, reviewed, frozen exact ordered first-page IDs for every query |
| `core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/search/ReferenceOracle.kt` | Independent full scan → stable sort → `take(50)` oracle |
| `core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/search/FtsMatchTranslator.kt` | Exact normalized token-prefix phrase translation frozen above |
| `core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/search/MeasurementStats.kt` | Nearest-rank p50/p95, population CV, and candidate maxima |
| `core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/search/SelectionRule.kt` | Eligibility and deterministic candidate selection over candidate maxima |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/search/TestFixtureVisibilityTest.kt` | JVM proof that the module's test source can consume its fixtures |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/search/SearchNormalizerTest.kt` | Normalizer determinism and boundary cases (full-width, fold forms, trim classes, supplementary code points) |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/search/CorpusGeneratorTest.kt` | Seed → recorded hash reproduction; count/mix invariants |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/search/QueryManifestTest.kt` | Exact 30-query order, expected-source emitter under `build/p4/`, and frozen-artifact hash equality |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/search/ReferenceOracleTest.kt` | First-page truncation, order, determinism, and committed-manifest equality |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/search/FtsMatchTranslatorTest.kt` | Exact translation examples and quote/whitespace handling |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/search/MeasurementStatsTest.kt` | Sample count/positivity, nearest ranks, population CV, maxima |
| `core/data/src/test/kotlin/com/paherdin/record/core/data/search/SelectionRuleTest.kt` | Every eligibility and tie-break boundary |
| `core/data/src/androidTest/kotlin/com/paherdin/record/core/data/search/TestFixtureVisibilityAndroidTest.kt` | Instrumented proof that androidTest consumes the same fixture classes |
| `core/data/src/androidTest/kotlin/com/paherdin/record/core/data/search/SearchCandidateDb.kt` | Room entity + databases for both candidates, FTS4 raw DDL + trigger, DAO queries |
| `core/data/src/androidTest/kotlin/com/paherdin/record/core/data/search/SearchCandidateSmokeTest.kt` | FTS4 capability, explicit docid/rowId equality, fill equality, sample query paths |
| `core/data/src/androidTest/kotlin/com/paherdin/record/core/data/search/SearchBenchmarkHarness.kt` | Fill, warmups, measured runs, timing, correctness/order assertion, sample recording |
| `core/data/src/androidTest/kotlin/com/paherdin/record/core/data/search/SearchBenchmarkTest.kt` | Device run of the full protocol for both candidates; harness self-check test |
| `docs/specs/tech-poc/p4-chinese-search/verification.md` | Evidence record (created at Task 0, completed at Task 10) |
| `docs/architecture/decisions/0009-search-candidate-selection.md` | Selection ADR (drafted at Task 9, accepted before P4 completes) |

## Task sequence

Each task uses test-first steps where code is introduced and ends with an exact command plus a `verification.md` row (`Command`, `Environment`, `Result`, `Remaining risk`). Do not commit unless the user separately requests a commit.

- [x] **Task 0 — lifecycle entry (documentation only).** After explicit approval, record the approval date/context in spec Revision 2 and this header; set both to `in-progress`; update AGENTS.md, docs/index.md, and docs/roadmap.md to P4 as the unique active slice; create `verification.md` with acceptance-criterion rows 1–11. Verify with `git diff --check` and a PowerShell local-link scan. Expected: no whitespace error, no broken local link, and exactly one `in-progress` spec.
- [x] **Task 1 — toolchain, device, and test-fixture wiring.** Add `android { testFixtures { enable = true } }` to `core/data/build.gradle.kts`; add both visibility tests before fixture implementation and verify they initially fail to compile for unresolved fixture symbols; add the smallest `object P4FixtureMarker` in `SearchNormalizer.kt`; rerun compilation and the two visibility tests. Commands: `.\gradlew.bat :core:data:compileDebugTestFixturesKotlin :core:data:compileDebugUnitTestKotlin :core:data:compileDebugAndroidTestKotlin`, `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.TestFixtureVisibilityTest"`, and `.\gradlew.bat :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.search.TestFixtureVisibilityAndroidTest"`. Expected: all three commands end `BUILD SUCCESSFUL`; no `testFixtures(project(":core:data"))` dependency exists. Resolve adb from `local.properties`, require exactly one authorized Xiaomi 14 Pro, and record `ro.build.fingerprint`, `ro.build.version.sdk = 36`, ABI/CPU properties, and `/proc/meminfo`. A wiring or device mismatch stops execution and returns the plan for revision; it is not silently repaired into another architecture.
- [x] **Task 2 — normalization v1.** Write failing tests for the frozen White_Space set, NFKC full-width ASCII/CJK compatibility cases, supplementary CJK, ASCII fold exhaustiveness, forbidden `ß`, and LIKE escaping; run the class and record the expected failures; implement only `normalize` and `escapeLike`; rerun. Command: `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.SearchNormalizerTest"`. Expected after implementation: `BUILD SUCCESSFUL` and all methods in that class pass.
- [x] **Task 3 — frozen corpus.** Write failing tests for seed `2026080804L`, 10,000 rows, 70/30 types, 55/30/15 languages, deterministic UUID syntax/uniqueness without prefixes, three-way timestamp ties, boundary records, zero-hit exclusion, canonical serialization, and repeatable SHA-256; implement `CorpusGenerator`; rerun. Command: `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.CorpusGeneratorTest"`. Expected: PASS; record the resulting hash only in `verification.md`.
- [x] **Task 4 — query manifest and independent oracle.** Add the exact `q01`…`q30` strings from this plan; write failing oracle tests proving full-scan substring semantics, DESC/ASC ordering, `take(50)`, a >50-hit query, zero hits, and deterministic output; implement the oracle. `QueryManifestTest.emitExpectedManifestV1` writes the canonical Kotlin source to `core/data/build/p4/ExpectedManifestV1.kt`; run it, inspect that generated build artifact, then use `apply_patch` to add the same content at the test-fixture source path. Keep a test that recomputes every list and canonical hash. Commands: `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.ReferenceOracleTest"`, `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.QueryManifestTest.emitExpectedManifestV1"`, and `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.QueryManifestTest"`. Expected: all pass; `core/data/build/p4/ExpectedManifestV1.kt` exactly equals the committed source; record the manifest hash only in `verification.md`.
- [x] **Task 5 — FTS translation, metrics, and selection.** Write failing tests for every frozen MATCH example, embedded quotes/Unicode whitespace, exactly-30 positive samples, Kotlin indexes 14/28, population CV, candidate maxima, exact 300 ms, one nanosecond over, CV 0.049/0.05, symmetric p95 0.099/0.10, one/both ineligible, and final tie. Implement `FtsMatchTranslator`, `MeasurementStats`, and `SelectionRule`; rerun. Command: `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.FtsMatchTranslatorTest" --tests "com.paherdin.record.core.data.search.MeasurementStatsTest" --tests "com.paherdin.record.core.data.search.SelectionRuleTest"`. Expected: PASS for all three classes.
- [x] **Task 6 — candidate databases and FTS4 capability.** Write `SearchCandidateSmokeTest` first for identical schema/fill order, escaped literal `%`, exact order/page size, FTS4 table creation, `docid == rowId` for all 10,000 rows, and both query paths; confirm it fails before `SearchCandidateDb` exists. Implement the shared Room entity/DAO, Candidate L, Candidate F DDL/trigger, single-transaction fill, and frozen translator use. Set `$p4RunId="p4-smoke-"+(Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")`; command: `.\gradlew.bat :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.search.SearchCandidateSmokeTest" "-Pandroid.testInstrumentationRunnerArguments.p4RunId=$p4RunId"`. Expected: `BUILD SUCCESSFUL`; all smoke methods pass on the recorded API 36 device. A missing/changed FTS4 capability stops for plan revision.
- [x] **Task 7 — harness self-check.** Write failing self-check methods for a new retained run directory per invocation, exactly two filled databases, 5 warmups, 30 positive samples, even/odd candidate counterbalancing, DAO-only timing boundary, exact ordered-list assertion (captured expected assertion failure for a wrong-order fixture), thermal invalidation, JSONL completeness, and candidate-max calculation. Implement the harness. Set `$p4RunId="p4-selfcheck-"+(Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")`; command: `.\gradlew.bat :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.search.SearchBenchmarkTest#harnessSelfCheck" "-Pandroid.testInstrumentationRunnerArguments.p4RunId=$p4RunId"`. Expected: PASS; no benchmark database or evidence file is deleted.
- [x] **Task 8 — measured device benchmark.** Re-record and compare the Task 1 fingerprint/SDK/CPU/RAM; require airplane mode, five idle minutes, foreground app, and initial thermal status `NONE` or `LIGHT`; otherwise do not start. Run both candidates for 30 queries with the frozen counterbalanced order and retain all `30 × 30 × 2 = 1800` measured samples plus correctness and metrics. Set `$p4RunId="p4-full-"+(Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")`; command: `.\gradlew.bat :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.search.SearchBenchmarkTest#fullProtocol" "-Pandroid.testInstrumentationRunnerArguments.p4RunId=$p4RunId"`. Expected: the protocol test completes; a candidate query mismatch or caught query exception is recorded per query and makes only that candidate ineligible, while evidence-write, harness, database-setup, or environment failures fail the instrumentation test.
- [x] **Task 9 — selection and ADR 0009.** Run `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.SelectionRuleTest"`, then apply the same function to the retained evidence. Create `docs/architecture/decisions/0009-search-candidate-selection.md` with both hashes, actual reference build, all per-query statistics, candidate maxima, eligibility, deciding formula/values, normalization/order rules, and M5 schema consequence. If neither candidate is eligible, record the failed conclusion and follow the same-slice revision protocol. If one is selected, obtain explicit user acceptance of ADR 0009 before completion. — **Done:** the user accepted ADR 0009 on 2026-08-08 after the independent 60-block/1,800-sample recomputation (0 errors); selection is normalized paged `LIKE` (only candidate eligible).
- [ ] **Task 10 — regression and completion.** First run `.\gradlew.bat check` without deletion. Before the contract-required `.\gradlew.bat clean check`, explain that `clean` deletes generated build directories and obtain the user's explicit deletion approval; without it, P4 cannot be marked completed. After approval, run the exact clean command, then `.\gradlew.bat :app:installDebug`. Resolve adb from `local.properties`, run `adb shell am start -W -n com.paherdin.record/.MainActivity`, and require `Status: ok`. Map spec criteria 1–11 and the roadmap gate to evidence, confirm production schema `2.json`, public allowlist, dependencies, and source set are unchanged, then update lifecycle documents to `completed`.

## Verification commands (frozen)

```text
$env:JAVA_HOME=(Resolve-Path "$env:USERPROFILE\tools\jdk\jdk-17.0.20+8").Path
.\gradlew.bat --version
.\gradlew.bat :core:data:compileDebugTestFixturesKotlin :core:data:compileDebugUnitTestKotlin :core:data:compileDebugAndroidTestKotlin
.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.*"
.\gradlew.bat :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.search.TestFixtureVisibilityAndroidTest"
$p4RunId="p4-smoke-"+(Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
.\gradlew.bat :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.search.SearchCandidateSmokeTest" "-Pandroid.testInstrumentationRunnerArguments.p4RunId=$p4RunId"
$p4RunId="p4-selfcheck-"+(Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
.\gradlew.bat :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.search.SearchBenchmarkTest#harnessSelfCheck" "-Pandroid.testInstrumentationRunnerArguments.p4RunId=$p4RunId"
$p4RunId="p4-full-"+(Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
.\gradlew.bat :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.search.SearchBenchmarkTest#fullProtocol" "-Pandroid.testInstrumentationRunnerArguments.p4RunId=$p4RunId"
.\gradlew.bat check
# Requires the user's explicit approval because clean deletes generated build directories:
.\gradlew.bat clean check
.\gradlew.bat :app:installDebug
```

Reference-device preflight uses the configured SDK without recording its local path:

```powershell
$sdkLine=(Get-Content 'local.properties' | Select-String '^sdk.dir=').Line
if(-not $sdkLine){throw 'sdk.dir missing from local.properties'}
$sdkDir=($sdkLine.Substring('sdk.dir='.Length) -replace '\\:',':' -replace '\\\\','\')
$adb=Join-Path $sdkDir 'platform-tools\adb.exe'
if(-not (Test-Path -LiteralPath $adb)){throw 'adb executable missing'}
& $adb devices -l
& $adb shell getprop ro.product.model
& $adb shell getprop ro.build.fingerprint
& $adb shell getprop ro.build.version.sdk
& $adb shell getprop ro.product.cpu.abi
& $adb shell cat /proc/meminfo
```

A build-only result cannot stand in for the JVM suites, the device benchmark, or the acceptance evidence.

## Risks

- **FTS4 correctness divergence** is the measured subject: unicode61 token boundaries (embedded-ASCII substrings, CJK phrase behavior) may make Candidate F ineligible on the frozen manifest; spec 4.5 and data-and-storage anticipate that outcome. No candidate is favored by fixture design.
- **Device measurement noise** remains possible after 30 samples; five idle minutes, airplane mode, foreground execution, thermal gating, candidate-order counterbalancing, retained raw samples, and candidate-level worst-query metrics make the residual risk visible rather than claiming to eliminate it.
- **testFixtures/FTS4 mechanics** are hard preflight gates. A failure stops execution for an explicitly approved plan revision; it is never repaired by silently changing source sets, dependencies, tokenizer, or database shape.
- **Reference configuration drift**: actual build/CPU/RAM are frozen before Task 8; any change invalidates the run.

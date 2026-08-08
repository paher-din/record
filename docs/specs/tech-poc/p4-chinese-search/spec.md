# Tech PoC P4 — Chinese Search

- **Status:** `completed` — read-only history; all eleven acceptance criteria of section 5.2 and the roadmap P4 exit gate have verification evidence in `verification.md`; ADR 0009 was accepted by the user on 2026-08-08; later corrections use a new specification or ADR.
- **Spec approved:** 2026-08-08 — the user explicitly accepted the original spec after two passes of two-axis code review (four fixes: roadmap duplicate line, §4.2 ordering attribution, §1 `LIKE` wording, §4.7 Journey 9 delegation).
- **Revision 1:** 2026-08-08 — proposed during the first plan review: normalization v1 is bounded to CJK plus ASCII English; after NFKC, the only cased characters admitted by that boundary are ASCII A–Z/a–z, so `lowercase(Locale.ROOT)` is the complete fold for in-boundary text. This revision was not executed.
- **Revision 2:** 2026-08-08 — fixes the plan-validation findings: expected correctness is the exact ordered first page (`take(50)` after full-oracle match and stable sort); candidate-level p95/CV are the maximum per-query values with pinned formulas; an ineligible candidate does not fail the slice when the other is eligible; the actual API 36 physical-device build is the reference measurement baseline; IDs remain deterministic UUID strings with a separate type discriminator; Candidate F uses a test-scoped raw-DDL FTS4 external-content table with explicit `docid = SearchDocument.rowid`; seed, query texts, MATCH translation, metric formulas, execution order, and exact commands are frozen in plan Revision 2.
- **Revision 2 approved:** 2026-08-08 — the user explicitly approved spec Revision 2 and plan Revision 2 together; the approval date/context is recorded in the plan Revision record.
- **Drafted:** 2026-08-08
- **Roadmap:** [Tech PoC P4](../../../roadmap.md)
- **SearchDocument projection:** [Data and storage](../../../architecture/data-and-storage.md) (SearchDocument section and the binding P4 comparison protocol)
- **P4 absolute gate:** [Testing strategy](../../../engineering/testing-strategy.md) (Search PoC P4 absolute gate)
- **Product behavior:** [MVP requirements](../../../product/mvp-requirements.md) (Journey 9 unified search)
- **Projection write ban and identity rules:** [ADR 0002](../../../architecture/decisions/0002-entityref-and-strong-relations.md), [Domain model](../../../architecture/domain-model.md)
- **Version-1 persistence contract:** [ADR 0006](../../../architecture/decisions/0006-version-1-persistence-contract.md)
- **Module boundaries:** [Architecture overview](../../../architecture/overview.md)
- **Verification policy:** [Testing strategy](../../../engineering/testing-strategy.md) (verification evidence format)
- **Predecessor:** [Tech PoC P3](../p3-attachment-failure-recovery/spec.md) — `completed`; P4 is the next roadmap slice and the first slice that neither changes the schema nor adds a production surface.

## 1. Purpose

Select the MVP unified-search implementation by running the frozen comparison protocol on the approved reference configuration: measure the FTS4 `unicode61` candidate against the normalized paged-`LIKE` candidate over a versioned 10,000-record mixed Chinese/English corpus and a fixed, versioned query manifest, record p50/p95/CV per query/candidate, apply the absolute gates (every ordered expected first-page ID list equals the actual first page exactly and every per-query first-page p95 is at most 300 ms), then compare candidate-level maximum CV at an absolute difference of at least 0.05, candidate-level maximum p95 at a symmetric relative difference of at least 10%, and finally the paged-`LIKE` complexity tie-break. The result is recorded by a new ADR 0009, which also fixes the search normalization version and the consequences for the MVP schema.

P4 is a selection PoC, not a delivery slice: it changes no production schema (the `databaseVersion = 2` contract stays), adds no production search surface, and introduces no new dependency. Both candidates run as test-scoped Room file databases filled from the same frozen corpus, so the measured SQLite behavior on the reference device represents the MVP implementation shape. The production SearchDocument table, the same-transaction projection update, the facts-to-projection mapping, rebuild, and the search UI remain M5's.

P4 also fixes the search matching semantics the comparison runs against: normalized substring matching (trim → NFKC → bounded case fold, then exact code-point substring containment). This is the natural semantics for Chinese (no word boundaries) and for English prefix/embedded matches ("expense" matches "Expenses"), it is the semantics paged `LIKE` implements on the pre-normalized text column — SQLite's own `LIKE` folding covers ASCII only, so the corpus-side normalization does the boundary-defined work — and it is the semantics the FTS4 candidate must prove its `unicode61` tokenizer satisfies on the frozen manifest. A candidate whose tokenization semantics diverge from any frozen ordered expected first page is correctness-ineligible; [data and storage](../../../architecture/data-and-storage.md) already anticipates that outcome for FTS4 Chinese correctness.

## 2. Reference environment

The reference environment remains the physical P0 configuration, unchanged from P1–P3. Per [data and storage](../../../architecture/data-and-storage.md), the actual device profile, actual Android system build/API level, CPU and RAM are recorded and frozen **before any measured run**; a missing or changed reference configuration invalidates the run and fails P4 until the spec records a new approved baseline. Android 14 / API 34 remains the product minimum/target baseline, while the upgraded physical reference device actually runs Android 16 / API 36; the benchmark records that fact rather than treating `minSdk = 34` as an API 34 device claim.

| Field | Recorded value |
|---|---|
| Device | Xiaomi 14 Pro (physical device, USB debugging) |
| Product minimum/target baseline | Android 14 / API 34 (`minSdk = 34`, `targetSdk = 34`) |
| Actual reference system build | Android 16 / API 36, build fingerprint re-recorded in `verification.md` immediately before measurement; a different build requires a newly approved baseline |
| Device CPU and RAM | Recorded and frozen in `verification.md` before the first measured run (via device properties); any change invalidates the run |
| AVD / host CPU-RAM fields | Not applicable — physical device reference |

## 3. Build baseline

- `minSdk = 34`, `targetSdk = 34`, `compileSdk = 36` — unchanged from P0–P3.
- Room 2.8.4, KSP 2.3.9, AGP 9.3 (built-in Kotlin 2.2.10), and the committed schema directory are already pinned and verified; P4 reuses the P1–P3 combination and changes nothing in it. The production `RecordDatabase` keeps `databaseVersion = 2`; P4 introduces no migration.
- **No new dependencies are anticipated.** FTS4 is a built-in SQLite module (`unicode61` tokenizer), `LIKE` is SQL, Room's `@Fts4` annotation is part of the pinned Room 2.8.4, and the corpus/query generators are hand-rolled with fixed seeds, mirroring the P2/P3 property-test pattern. Any dependency that the plan does introduce must first pass the dependency review record at plan approval.
- `fallbackToDestructiveMigration` remains forbidden and must not appear anywhere in the build.
- The seven-module graph is unchanged; the P0 `checkModuleDependencies` task must keep passing.

## 4. Behavior scope

### 4.1 The two candidates

Both candidates are test-scoped Room file databases under `:core:data` (instrumented test source set), created in app-private storage on the reference device so the measurement uses the real SQLite disk engine, WAL settings, and the pinned Room version. Both are filled from the same frozen corpus (section 4.3) and both expose the same typed query entry point — normalized query in, first page of 50 results out, ordered by `occurredAt` descending with the stable entity-ref-ID tie-break — so the harness measures identical protocol steps for both.

- **Candidate L — normalized paged `LIKE`.** A normal `SearchDocument` table carrying the projection fields (section 4.3) plus a `normalizedText` column (and the query-side normalized text of `title`/`body`/`auxiliary` merged in the documented column layout). The query is a `LIKE '%' || normalizedQuery || '%'` substring match on `normalizedText` (with ESCAPE handling for `%`/`_` in the query), ordered and paged by `LIMIT 50` over the frozen `occurredAt`/id order.
- **Candidate F — FTS4 `unicode61`.** The same `SearchDocument` table plus a test-scoped FTS4 external-content virtual table created by raw DDL with the `unicode61` tokenizer and an explicit `docid = SearchDocument.rowid` insert mapping. The query translates the normalized query into the exact `MATCH` expression frozen in the plan, ordered and paged identically to Candidate L. Candidate F may use only the `unicode61` tokenizer; no other tokenizer, no FTS5, no trigram, and no `LIKE` fallback inside the measured query path. Production M5 may map the selected structure to Room `@Fts4`; P4 does not add a production Room entity.

The exact table/column layout, DAO shapes, and query expressions are pinned normatively in the plan; this spec fixes the semantics above and the shared gate below. The two candidates may share the projection table definition (external-content FTS4 requires it); the measured query path is the only difference.

### 4.2 Frozen matching semantics

A query matches a record exactly when the normalized query is a code-point substring of the record's normalized searchable text. Normalization v1 is: Unicode trim → NFKC → bounded case folding, applied to both the query and the projected text, independent of locale and of the SQLite collation. Within the frozen CJK-plus-ASCII boundary, NFKC maps admitted full-width ASCII forms to ASCII and compatibility CJK forms to cased-neutral CJK; the only remaining cased characters are ASCII A–Z/a–z, so `lowercase(Locale.ROOT)` is the complete fold for every admitted input. Non-ASCII fold expansions (for example `ß`) are outside v1 and extending the boundary later requires a new ADR plus re-benchmark evidence. This is the semantics of Candidate L and the correctness contract Candidate F must satisfy through its tokenizer. The reference oracle scans the full corpus, sorts all matches by `occurredAt` descending then entity-ref ID ascending, and takes at most 50 IDs; that ordered first-page list must equal the candidate result exactly. The unpaged match list may be retained only as diagnostic evidence and is never compared directly to `LIMIT 50` output.

The normalization pipeline is a versioned, standalone implementation (its own `normalizationVersion`), deliberately separate from the catalog `nameKey` pipeline of [ADR 0004](../../../architecture/decisions/0004-catalog-lifecycle-and-normalized-names.md): the catalog pipeline exists for key uniqueness, this one for match text, and they must not share a version axis. A change to the search normalization pipeline or its Unicode data-table version requires a new ADR plus explicit re-benchmark evidence.

### 4.3 Frozen corpus and query manifest

- **Corpus.** Exactly 10,000 records generated deterministically from the fixed algorithm and seed frozen in plan Revision 2, versioned (`corpus-v1`) and recorded by hash. Records model the MVP projection shape — a frozen mix of Transaction-shaped and Note-shaped records whose `title`/`body`/`auxiliary` fields follow the frozen filling rules the plan pins, so the corpus represents the MVP text distribution rather than synthetic blobs. The language mix is frozen (Chinese-majority with a substantial English and mixed segment), characters are CJK + ASCII English plus the explicitly listed normalization/escaping boundary forms, and the corpus contains realistic short-form text including numerals and punctuation. Each record uses a deterministic UUID string as `entityRefId`, a separate Transaction/Note discriminator, projection fields, normalized searchable text, `occurredAt`, and `updatedAt`; type prefixes are forbidden because the domain identity contract owns type separately from the UUID.
- **Query manifest.** The fixed, versioned `manifest-v1` contains the 30 query strings frozen verbatim in plan Revision 2 and a generated source artifact containing each query's exact ordered expected first-page IDs. An independent pure-JVM oracle computes those IDs from the frozen corpus using section 4.2 — never from either candidate implementation. A JVM verification test proves that the frozen manifest artifact equals a fresh oracle computation and that its recorded SHA-256 is stable.

### 4.4 Benchmark protocol

The harness runs, on the frozen reference configuration, per query and candidate:

1. open the candidate database (already filled from the frozen corpus, cold from a fresh file per candidate run set);
2. 5 warmups followed by 30 measured runs of the first-page query (page size 50);
3. record correctness (ordered expected first page vs actual), p50, p95, and CV per query.

The measured quantity is the first-page query response time, matching the P4 absolute gate. The harness measures the full query path (DAO call through SQLite to paged result materialization), not database creation, filling, connection, or setup. For 30 positive nanosecond samples sorted ascending, p50 and p95 are the nearest-rank samples at one-based ranks `ceil(0.50 × 30)` and `ceil(0.95 × 30)`; CV is population standard deviation divided by the positive arithmetic mean. A candidate's comparison p95 and CV are the maximum per-query p95 and maximum per-query CV. Evidence records the frozen reference profile, per-query expected/actual order, all samples, per-query metrics, candidate-level maxima, absolute-gate eligibility, and final tie-break. Plan Revision 2 freezes counterbalanced candidate order and device preconditions so candidate position and thermal drift do not silently choose the winner.

### 4.5 Selection rule

The selection rule is fixed by [data and storage](../../../architecture/data-and-storage.md) and is applied to the measured evidence:

1. A candidate is eligible only when every actual first page equals its ordered expected first-page ID list and every per-query p95 is at most 300 ms, equivalently when its candidate comparison p95 (maximum per-query p95) is at most 300 ms. Correctness and the absolute p95 budget are both hard gates for that candidate; its ineligibility does not fail P4 when the other candidate is eligible.
2. If neither candidate is eligible, P4 fails and no implementation candidate is selected. Failure follows the roadmap PoC revision-and-retry protocol; the evidence-bearing ADR 0009 records the failure and the rejected options.
3. If exactly one candidate is eligible, select it — including the case where FTS4 Chinese correctness is inadequate but paged `LIKE` is 100% correct and within budget.
4. If both are eligible, choose the lower candidate comparison CV when the absolute difference is at least 0.05; otherwise choose the lower candidate comparison p95 when `abs(a - b) / max(a, b)` is at least 0.10; if still tied, choose paged `LIKE` for lower infrastructure complexity.

### 4.6 ADR 0009 and document sync

A new ADR 0009 records, at P4 completion: the corpus and manifest versions, the frozen reference configuration, the complete measurements (per-query correctness, p50/p95/CV for both candidates), the absolute-gate eligibility of each candidate, the final choice and the rule that decided it, and the search normalization version. The ADR also records the consequences for the MVP schema: whichever candidate is selected, M5 introduces the production SearchDocument table (and, only when FTS4 is selected, the matching FTS4 index structure) through its own schema change under the recoverable-migration protocol. A paged-`LIKE` selection therefore needs no FTS4 structure in production at all.

If implementation exposes a conflict between the frozen matching semantics and a core document (including Journey 9 wording), the documentation-first protocol applies before any code change and this spec returns to review; the selection ADR is not created until the core facts are consistent.

### 4.7 Deferred to later slices

Explicitly not part of P4:

- The production `SearchDocument` table, its Room schema change (v2 → v3), and its migration-recovery evidence (M5).
- The same-transaction projection update with domain writes, the facts-to-projection mapping rules, full rebuild from facts, and projection freshness handling (M5; the projection write ban is already proven by P1's `apiBoundaryCheck`).
- The search UI, ViewModel, request revisions, stale-result suppression, type filtering, and dispatch (M5; interaction rules).
- The trimmed-non-empty-query execution rule of [MVP requirements](../../../product/mvp-requirements.md) Journey 9 — a search executes only for a trimmed non-empty query, an empty query keeps the prompt/empty state and the active type filter and claims no results (M5; interaction rules).
- Normalization of user-entered queries in the UI or any product-level search behavior beyond the frozen semantics.
- Any server, Room 3, FTS5, or other tokenizer work — [data and storage](../../../architecture/data-and-storage.md) forbids a server or a Room 3 migration for search.

## 5. Invariants and acceptance criteria

### 5.1 Invariants in scope

- The corpus and query manifest are versioned and frozen; a change to either requires an explicit spec revision and re-benchmark.
- Both candidates measure the same corpus, the same manifest, the same page size (50), the same warmup/measurement counts (5/30), and the same reference configuration.
- Ordered expected first pages are computed by the independent reference oracle from the frozen semantics by matching, sorting, and taking at most 50; neither candidate defines its own expected output.
- Correctness means every candidate first page equals the corresponding expected first-page ID list exactly (occurredAt descending, ID ascending, then `take(50)`).
- The selection rule is applied in the fixed order: absolute gates, CV, p95, `LIKE` tie-break; a both-ineligible outcome is a failed P4, not a default selection.
- No production schema change: the v2 schema contract and the P1/P2 public-surface allowlist stay exactly as they are.

### 5.2 Acceptance criteria

The slice passes only when all of the following have evidence in `verification.md`:

1. **Frozen corpus.** The generator deterministically produces exactly 10,000 records from the recorded fixed seed; the `corpus-v1` version and a corpus hash are recorded, and a JVM test proves that regenerating with the recorded seed reproduces the recorded hash. The language mix, record-type mix, character boundary, and field-filling rules of section 4.3 are recorded.
2. **Frozen manifest and reference oracle.** `manifest-v1` declares every frozen query with its exact ordered expected first-page IDs; a JVM test proves the oracle output is deterministic and versioned, the frozen manifest artifact equals a fresh match → stable sort → `take(50)` computation, and the manifest hash is stable.
3. **Normalization v1.** The trim → NFKC → case-fold pipeline is implemented versioned, locale-independent, and JVM-tested on the corpus boundary cases (full-width and compatibility forms mapped by NFKC, the v1 fold range — complete within the frozen CJK+ASCII boundary and expansion-free by design, with the expansion-free property asserted — supplementary code points, `%`/`_` escaping for the LIKE path), with fixed seeds recorded on failure.
4. **Frozen reference configuration.** Before the first measured run, `verification.md` records the Xiaomi 14 Pro profile, its actual Android 16 / API 36 build fingerprint, and CPU/RAM values; the run is invalid without them, and any change to a recorded value invalidates the measurement until a new baseline is approved.
5. **Both candidates executed.** Each candidate database is filled from the same frozen corpus and runs the full protocol — 5 warmups and 30 measured first-page runs, page size 50, for every manifest query — on the reference device; the query path measured is the full DAO-to-paged-results path.
6. **Correctness gate.** Every actual first page is compared to its exact ordered expected first-page ID list; any divergence is recorded per query with expected vs actual and makes only that candidate ineligible.
7. **Absolute p95 and metric gate.** All 30 positive samples per query are retained; nearest-rank p50/p95 and population CV are recorded per query; candidate comparison p95/CV are the respective per-query maxima. A candidate is p95-eligible only when its comparison p95 is at most 300 ms. One ineligible candidate is valid evidence when the other candidate is eligible; both ineligible fail P4.
8. **Selection rule.** The eligibility determination and final choice follow section 4.5 in the fixed order, including the both-ineligible failure path; the deciding rule is named with the candidate-level maxima and, for p95, the symmetric relative difference.
9. **ADR 0009.** The ADR records the corpus/manifest versions, reference configuration, complete measurements, eligibility, final choice, the deciding rule, the search normalization version, and the MVP-schema consequences; it is accepted before P4 completes.
10. **No production change.** The schema contract still matches the current committed `2.json`, the post-P3 public-surface allowlist is unchanged, `apiBoundaryCheck` passes with its negative fixtures, and no new dependency is introduced.
11. **Regression.** `./gradlew clean check` passes (module graph, build, lint unchanged), and the app still installs and launches on the reference device.

## 6. Test evidence requirements

- JVM unit tests cover the normalization pipeline, the corpus generator determinism (seed → hash), the reference-oracle computation, and the selection-rule function, mirroring the P1–P3 validator-test pattern.
- Instrumented tests on the reference device run the full benchmark protocol for both candidates and assert correctness per query; benchmark evidence follows the testing strategy's `Command`, `Environment`, `Result`, `Remaining risk` format and retains the per-query sample data needed to reproduce p50/p95/CV.
- The harness itself is verified: a controlled micro-test proves the harness measures what it claims (fresh-file open, warmups counted, sample count exact) and that result ordering is asserted exactly, not merely the ID set.
- `verification.md` maps every acceptance criterion of section 5.2 and the roadmap P4 exit gate to concrete commands and results; a build-only result cannot stand in for the required measurements.

## 7. Non-goals

P4 explicitly does not:

- Change the production schema, the `RecordDatabase` version, the committed schema exports, the public-surface allowlist, or the module graph.
- Implement any search UI, ViewModel, request-revision logic, filtering, dispatch, or stale-result handling (M5).
- Introduce the production `SearchDocument` table, the projection transaction coupling, the facts-to-projection mapping, or rebuild (M5).
- Add FTS5, a third candidate, a server, a Room 3 migration, or any new dependency.
- Change the catalog normalization pipeline or any P1–P3 table, outcome, or public declaration.
- Silently diverge from any core-document fact. If implementation exposes a conflict with a core document, the documentation-first protocol applies before any code change and the spec returns to review.

## 8. Dependency review boundary

Every dependency introduced by P4 is recorded at plan approval per the engineering conventions: version, license, maintenance evidence, compatibility impact, and removal path. No new dependency is anticipated — FTS4, `LIKE`, Room's pinned `@Fts4` annotation, and the hand-rolled generators are platform/SQLite/Room built-ins — and the P1–P3 pinned toolchain is reused. A dependency is rejected when its license is incompatible, its maintenance status is unknown, it requires excessive permissions, it introduces undisclosed network behavior, or no practical removal path exists. No ads, analytics, crash-reporting, or closed-source tracking SDK.

## 9. Risks

- **FTS4 substring correctness.** The `unicode61` tokenizer groups characters by its token rules rather than implementing arbitrary code-point substring search. Contiguous CJK sequences, embedded-ASCII substrings ("art" in "cart"), punctuation, and phrase boundaries can therefore diverge from the frozen substring semantics. The P4 gate measures this directly; the risk is an ineligible candidate, not an invalid measurement.
- **Measurement noise on the physical device.** Device heat, background activity, and USB state can shift p50/p95/CV; the 30-sample protocol and the fixed CV/p95 thresholds bound this, and the frozen-configuration rule makes an invalid baseline a failed run rather than a silent measurement. P3's device-time and USB-stability experience applies.
- **Corpus representativeness.** The frozen corpus is a generated model of MVP text; the field-filling rules and language mix are recorded in the evidence so a future corpus revision is an explicit, documented change.
- **Reference configuration drift.** The product minimum/target baseline is API 34 while the measurement baseline is the actual API 36 physical device. Its build fingerprint/CPU/RAM are frozen before measurement; any device/build change invalidates the run instead of being treated as equivalent through `minSdk` compatibility.
- **Normalization semantics divergence.** The frozen matching semantics define "correct"; if the semantics themselves conflict with a product or core fact, the documentation-first protocol returns the spec to review before any selection is recorded.

## 10. Lifecycle path

- `draft` (2026-08-08): behavior, boundaries, and acceptance were clarified; no implementation.
- `approved` (2026-08-08): the user explicitly accepted the original spec; implementation did not start.
- `draft` Revision 2 (2026-08-08): plan validation exposed protocol contradictions, so the same spec was revised documentation-first for joint approval with plan Revision 2.
- `in-progress` (2026-08-08): the user explicitly approved spec Revision 2 and plan Revision 2 together; implementation began under the frozen plan.
- `completed`: acceptance criteria and the roadmap P4 exit gate have verification evidence, ADR 0009 is accepted, and no blocker remains; P5 may then start.

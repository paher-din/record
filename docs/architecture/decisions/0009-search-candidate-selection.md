# ADR 0009: Search candidate selection — normalized paged `LIKE`

## Status

Accepted — the user explicitly accepted this decision on 2026-08-08 after the independent 60-block/1,800-sample recomputation (0 errors) and the technical review of the complete measurement table; the acceptance date/context is recorded in the P4 verification record.

## Date

2026-08-08

## Context

Tech PoC P4 (Chinese search) must select the MVP unified-search implementation between two candidates — FTS4 with the `unicode61` tokenizer and normalized paged `LIKE` — under the frozen protocol of [data and storage](../data-and-storage.md) and the testing strategy's P4 absolute gate. The binding inputs: the frozen `corpus-v1` (10,000 mixed Chinese/English records, seed `2026080804L`) and the frozen `manifest-v1` (30 queries with exact ordered expected first-page IDs computed by the independent reference oracle). The selection rule is fixed: a candidate is eligible only when every actual first page equals its ordered expected first-page list and its comparison p95 (maximum per-query p95) is at most 300 ms; both eligible compare candidate-level CV at an absolute difference of at least 0.05, then candidate-level p95 at a symmetric relative difference of at least 10%, then paged `LIKE` on tie; neither eligible fails P4.

## Measurement

- **Valid run:** `p4-full-fix-20260808T133145Z`; canonical evidence [`run-fix-20260808T133145Z.jsonl`](../../specs/tech-poc/p4-chinese-search/evidence/run-fix-20260808T133145Z.jsonl), SHA-256 `cbcb3f7f2a0db64d14fc9980605cba632e78e4762eafbfc5c8751f1bf675ddcc`.
- **Frozen reference configuration:** Xiaomi 14 Pro (`23116PN5BC`, `shennong`), fingerprint `Xiaomi/shennong/shennong:16/BP2A.250605.031.A3/OS3.0.307.0.WNBCNXM:user/release-keys` (Android 16 / API 36), ABI `arm64-v8a`, SoC manufacturer/model `QTI` / `SM8650`, hardware `qcom`, total RAM 11,710,394,368 bytes. Airplane mode and foreground state were true; initial and every per-block before/after thermal status were `0` (`NONE`).
- **Frozen inputs:** `corpus-v1` canonical UTF-8 SHA-256 `2f27b8754f2c3beaa96b0a1eed3ed5e06ecec917f465392eea2897086cbeb5e9`; `ExpectedManifestV1.kt` SHA-256 `14b381f8bf2a0cec42b12691cf163edce15d66e841d8b94b75bdb5f99a11efb1`. Both are literal automated assertions with mutation-failure proof.
- **Protocol:** 5 warmups + 30 measured DAO-to-materialized-first-page calls per query/candidate, page size 50, frozen counterbalance, preparation before timing, exact expected/actual order, per-query exception capture, all 1,800 positive samples, and before/after thermal values retained. Both candidates recorded zero query exceptions.
- **Independent review:** all 60 block keys and their counterbalanced sequence match the manifest; every expected list equals the frozen `ExpectedManifestV1` artifact; p50 index 14, p95 index 28, population CV, correctness flags, candidate maxima, eligibility and summary selection were independently recomputed with zero validation errors.
- **Historical evidence:** `run-20260808T124648Z.jsonl` remains diagnostic-only and invalid for selection because it predates the corrected timing/evidence protocol.

### Complete per-query measurements

`Ordered first page` compares the retained actual ID list with the frozen expected list in exact order. Thermal is `before/after`; `0/0` means `NONE/NONE`.

| Query | Text | Candidate | Ordered first page | p50 (ns) | p95 (ns) | CV | Thermal | Query exception |
|---|---|---|---|---:|---:|---:|---|---|
| q01 | 餐 | L | PASS (50 IDs) | 4618542 | 7640938 | 0.317546943 | 0/0 | none |
| q01 | 餐 | F | FAIL (50→0 IDs) | 3474791 | 4461458 | 0.244037810 | 0/0 | none |
| q02 | 早餐 | F | PASS (50 IDs) | 5504739 | 8179531 | 0.213035849 | 0/0 | none |
| q02 | 早餐 | L | PASS (50 IDs) | 6322500 | 9054427 | 0.226668536 | 0/0 | none |
| q03 | 交通 | L | PASS (50 IDs) | 5529687 | 9156719 | 0.254510419 | 0/0 | none |
| q03 | 交通 | F | PASS (50 IDs) | 5113073 | 10170105 | 0.295851851 | 0/0 | none |
| q04 | 地铁 | F | PASS (50 IDs) | 6152605 | 7077865 | 0.182268211 | 0/0 | none |
| q04 | 地铁 | L | PASS (50 IDs) | 3941093 | 6163281 | 0.306370232 | 0/0 | none |
| q05 | 咖啡 | L | PASS (50 IDs) | 2903958 | 6069688 | 0.426749710 | 0/0 | none |
| q05 | 咖啡 | F | PASS (50 IDs) | 6648177 | 12860052 | 0.352030539 | 0/0 | none |
| q06 | 会议 | F | PASS (50 IDs) | 4193958 | 10122865 | 0.491330125 | 0/0 | none |
| q06 | 会议 | L | PASS (50 IDs) | 4159480 | 7360417 | 0.322587057 | 0/0 | none |
| q07 | 项目 | L | PASS (50 IDs) | 6088125 | 9646927 | 0.196962781 | 0/0 | none |
| q07 | 项目 | F | PASS (50 IDs) | 5569219 | 7562188 | 0.301612397 | 0/0 | none |
| q08 | 旅行 | F | PASS (50 IDs) | 5791459 | 9643438 | 0.276760430 | 0/0 | none |
| q08 | 旅行 | L | PASS (50 IDs) | 5301614 | 8488438 | 0.238963516 | 0/0 | none |
| q09 | 书店 | L | PASS (50 IDs) | 1260000 | 8201458 | 0.913067837 | 0/0 | none |
| q09 | 书店 | F | PASS (50 IDs) | 2725886 | 3930833 | 0.644792023 | 0/0 | none |
| q10 | 周末 | F | PASS (50 IDs) | 3655677 | 4718802 | 0.221491301 | 0/0 | none |
| q10 | 周末 | L | PASS (50 IDs) | 3608854 | 6118906 | 0.221095442 | 0/0 | none |
| q11 | expense | L | PASS (50 IDs) | 5237969 | 9591615 | 0.312907224 | 0/0 | none |
| q11 | expense | F | PASS (50 IDs) | 3848281 | 4906823 | 0.183975863 | 0/0 | none |
| q12 | EXPENSE | F | PASS (50 IDs) | 1565000 | 2427813 | 0.195063158 | 0/0 | none |
| q12 | EXPENSE | L | PASS (50 IDs) | 4308229 | 5096875 | 0.068086771 | 0/0 | none |
| q13 | exp | L | PASS (50 IDs) | 5223646 | 8941511 | 0.347141630 | 0/0 | none |
| q13 | exp | F | PASS (50 IDs) | 4738334 | 7489844 | 0.242477025 | 0/0 | none |
| q14 | art | F | FAIL (50→0 IDs) | 3050417 | 3941823 | 0.175344310 | 0/0 | none |
| q14 | art | L | PASS (50 IDs) | 4856302 | 8078021 | 0.332410811 | 0/0 | none |
| q15 | transport | L | PASS (50 IDs) | 5451614 | 9140469 | 0.280610890 | 0/0 | none |
| q15 | transport | F | PASS (50 IDs) | 3485885 | 6954011 | 0.327556897 | 0/0 | none |
| q16 | project notes | F | PASS (50 IDs) | 3198437 | 5920104 | 0.338491297 | 0/0 | none |
| q16 | project notes | L | PASS (50 IDs) | 5738229 | 7615990 | 0.168240912 | 0/0 | none |
| q17 | coffee | L | PASS (50 IDs) | 2792187 | 5328958 | 0.457802667 | 0/0 | none |
| q17 | coffee | F | PASS (50 IDs) | 5075990 | 9039687 | 0.405042587 | 0/0 | none |
| q18 | meeting | F | PASS (50 IDs) | 3272135 | 6314167 | 0.357391491 | 0/0 | none |
| q18 | meeting | L | PASS (50 IDs) | 5298802 | 8596250 | 0.256562186 | 0/0 | none |
| q19 | budget | L | PASS (50 IDs) | 5120469 | 8622552 | 0.248043701 | 0/0 | none |
| q19 | budget | F | PASS (50 IDs) | 5658021 | 6732187 | 0.235428521 | 0/0 | none |
| q20 | weekend | F | PASS (50 IDs) | 4537969 | 6675781 | 0.289612824 | 0/0 | none |
| q20 | weekend | L | PASS (50 IDs) | 5326875 | 8428490 | 0.281389529 | 0/0 | none |
| q21 | 咖啡 coffee | L | PASS (50 IDs) | 2448333 | 4305833 | 0.332462872 | 0/0 | none |
| q21 | 咖啡 coffee | F | PASS (50 IDs) | 4142760 | 9319167 | 0.413872339 | 0/0 | none |
| q22 | 项目 project | F | PASS (0 IDs) | 2086302 | 4763594 | 0.423901177 | 0/0 | none |
| q22 | 项目 project | L | PASS (0 IDs) | 17538437 | 23060678 | 0.209936466 | 0/0 | none |
| q23 | 地铁 metro | L | PASS (50 IDs) | 4455573 | 8474531 | 0.397199462 | 0/0 | none |
| q23 | 地铁 metro | F | PASS (50 IDs) | 3901927 | 7557136 | 0.339435860 | 0/0 | none |
| q24 | 会议 notes | F | PASS (50 IDs) | 6017813 | 8183282 | 0.246560569 | 0/0 | none |
| q24 | 会议 notes | L | PASS (50 IDs) | 6197032 | 9192656 | 0.272255374 | 0/0 | none |
| q25 | 周末 trip | L | PASS (50 IDs) | 5967448 | 9106094 | 0.217733090 | 0/0 | none |
| q25 | 周末 trip | F | PASS (50 IDs) | 5333229 | 8299896 | 0.236085399 | 0/0 | none |
| q26 | 量子火箭 | F | PASS (0 IDs) | 2614427 | 3688437 | 0.238287255 | 0/0 | none |
| q26 | 量子火箭 | L | PASS (0 IDs) | 17499219 | 29730938 | 0.342142091 | 0/0 | none |
| q27 | unfindabletoken | L | PASS (0 IDs) | 20833750 | 31422448 | 0.260587761 | 0/0 | none |
| q27 | unfindabletoken | F | PASS (0 IDs) | 2024063 | 3208281 | 0.180070579 | 0/0 | none |
| q28 | 火星 ledger | F | PASS (0 IDs) | 3333125 | 4052031 | 0.225452165 | 0/0 | none |
| q28 | 火星 ledger | L | PASS (0 IDs) | 15175312 | 31595000 | 0.396896878 | 0/0 | none |
| q29 | ＡＢＣ | L | PASS (1 IDs) | 15258333 | 28623542 | 0.338249934 | 0/0 | none |
| q29 | ＡＢＣ | F | PASS (1 IDs) | 2518177 | 3716198 | 0.247862172 | 0/0 | none |
| q30 | 50% | F | PASS (1 IDs) | 2456458 | 3038854 | 0.173376382 | 0/0 | none |
| q30 | 50% | L | PASS (1 IDs) | 16547709 | 30700469 | 0.351151224 | 0/0 | none |

## Options considered

### Candidate F — FTS4 `unicode61`

Candidate F returned the correct ordered first page for 28/30 queries and recorded comparison p95 `12,860,052 ns` and comparison CV `0.644792023`. It is **ineligible on correctness**: `q01 餐` and `q14 art` returned empty pages instead of the 50 expected IDs. This is tokenizer-boundary divergence, not a query exception: continuous CJK text such as `早餐` is not an arbitrary substring index for `"餐*"`, and the token `cart` does not start with `art`.

### Candidate L — normalized paged `LIKE`

Candidate L returned all 30/30 exact ordered first pages correctly, with comparison p95 `31,595,000 ns` and comparison CV `0.913067837`. Its comparison p95 is far below the `300,000,000 ns` gate, so it is **eligible**. `LIKE '%' || :q || '%' ESCAPE '\'` over pre-normalized `normalizedText` directly implements normalization-v1 substring semantics.

## Proposed decision

Select **normalized paged `LIKE`** (Candidate L). Only Candidate L is eligible: it is 100% correct and its comparison p95 is within 300 ms; Candidate F fails the correctness gate despite passing the latency gate. Therefore the fixed selection function returns `Like` at the single-eligible-candidate step. The CV and relative-p95 comparison rules are not entered, so Candidate L's high maximum CV (`0.913067837`) does not change the result. The normalization version is `normalizationVersion = 1`, and result order remains `occurredAt` descending then `entityRefId` ascending. This proposed decision becomes accepted only after the user's explicit Task 9 acceptance.

## Consequences if accepted

- If the replacement evidence confirms the proposal and the ADR is accepted, M5 introduces the production `SearchDocument` table (with the normalized text column and the `(occurredAt, entityRefId)` and unique `entityRefId` indexes proven in P4) through its own schema change under the recoverable-migration protocol. **No FTS4 structure enters production** — the raw-DDL FTS4 virtual table exists only in P4's test-scoped benchmark databases.
- The paged-`LIKE` query path is the proven one: `LIKE '%' || :q || '%' ESCAPE '\'` with `SearchNormalizer.escapeLike` on the normalized query, `LIMIT 50` over the frozen order.
- M5 may optimize later (e.g. FTS5 or tokenizer choices) only through a new ADR with re-benchmark evidence; the 300 ms p95 budget and the frozen corpus/manifest protocol remain binding gates.
- The corpus and manifest versions (`corpus-v1`, `manifest-v1`), the normalization version, and this measurement are the frozen baseline for any future search change.

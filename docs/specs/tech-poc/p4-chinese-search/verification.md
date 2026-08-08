# Tech PoC P4 — Verification Record

- **Slice state:** `completed` (2026-08-08) — all eleven acceptance criteria and the roadmap P4 exit gate have evidence below; the user accepted ADR 0009 after the independent 60-block/1,800-sample recomputation; a post-completion code review recorded and fixed minor findings (section below).
- **Date:** 2026-08-08
- **Spec:** [spec.md](spec.md) — acceptance criteria §5.2 (Revision 2)
- **Plan:** [plan.md](plan.md) — task sequence (Revision 2)
- **ADR:** [0009](../../../architecture/decisions/0009-search-candidate-selection.md) — `Accepted` 2026-08-08 (user).

## Task 0 — lifecycle entry

| Field | Recorded value |
|---|---|
| Command | `git diff --check`; PowerShell local-link scan recorded again in the corrective verification section below. |
| Environment | Commit baseline `fbbba4a4df67e9e419d17b387a97588f04f9fc03`; Windows 11 Home China 10.0.26200 (x64). |
| Result | PASS — P4 is the unique `in-progress` spec; approval date/context is present in spec and plan metadata. |
| Remaining risk | None for lifecycle entry. |

## Task 1 — toolchain, device, and test-fixture wiring

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:compileDebugTestFixturesKotlin :core:data:compileDebugUnitTestKotlin :core:data:compileDebugAndroidTestKotlin`; `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.TestFixtureVisibilityTest"`; `.\gradlew.bat :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.search.TestFixtureVisibilityAndroidTest"`; adb preflight: `adb devices -l`, `adb shell getprop ro.product.model`, `adb shell getprop ro.product.device`, `adb shell getprop ro.build.fingerprint`, `adb shell getprop ro.build.version.sdk`, `adb shell getprop ro.product.cpu.abi`, `adb shell getprop ro.soc.manufacturer`, `adb shell getprop ro.soc.model`, `adb shell getprop ro.hardware`, `adb shell cat /proc/meminfo`. |
| Environment | Host as above; JDK Temurin 17; Gradle 9.5.0; physical Xiaomi 14 Pro. |
| Result | PASS for fixture wiring and original visibility run. Frozen replacement-run profile: Xiaomi 14 Pro (`23116PN5BC`, `shennong`, serial alias `c55ad7e4`); Android 16 / API 36; fingerprint `Xiaomi/shennong/shennong:16/BP2A.250605.031.A3/OS3.0.307.0.WNBCNXM:user/release-keys`; ABI `arm64-v8a`; SoC manufacturer/model `QTI` / `SM8650`; hardware `qcom`; MemTotal `11435932 kB`. The earlier Task 8 record omitted the SoC model and is not retroactively repaired by this later read. |
| Remaining risk | None — the valid Task 8 header re-read and matched the complete profile. |

## Task 2 — normalization v1

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.SearchNormalizerTest"` |
| Environment | Host as above; process-local `JAVA_HOME` points to Temurin 17. |
| Result | PASS — 11 tests, 0 failures; trim → NFKC → bounded locale-independent fold and LIKE escaping boundaries are covered. |
| Remaining risk | None. |

## Task 3 — frozen corpus

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.CorpusGeneratorTest"` |
| Environment | Host as above. |
| Result | PASS — 9 tests; seed `2026080804L`, exactly 10,000 records, frozen mixes/fields/boundaries, deterministic UUIDs and canonical serialization. `corpus-v1` SHA-256 is `2f27b8754f2c3beaa96b0a1eed3ed5e06ecec917f465392eea2897086cbeb5e9`. The regression test now compares against this literal value. Mutation proof: replacing the literal with all zeroes made `canonicalBytesAreDeterministicAndHashIsRepeatable` fail at the hash assertion; restoring the recorded value passed. |
| Remaining risk | None. Silent corpus drift now fails the automated test. |

## Task 4 — query manifest and independent oracle

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.ReferenceOracleTest"`; `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.QueryManifestTest.emitExpectedManifestV1"`; `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.QueryManifestTest"` |
| Environment | Host as above. |
| Result | PASS — `manifest-v1` contains the exact q01…q30 order; the committed expected first pages equal fresh oracle results. `ExpectedManifestV1.kt` SHA-256 is `14b381f8bf2a0cec42b12691cf163edce15d66e841d8b94b75bdb5f99a11efb1`. The regression test now compares against this literal value. Mutation proof: replacing the literal with all zeroes made `frozenArtifactHashIsStable` fail; restoring the recorded value passed. |
| Remaining risk | None. Silent manifest-artifact drift now fails the automated test. |

## Task 5 — FTS translation, metrics, and selection

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.FtsMatchTranslatorTest" --tests "com.paherdin.record.core.data.search.MeasurementStatsTest" --tests "com.paherdin.record.core.data.search.SelectionRuleTest"` |
| Environment | Host as above. |
| Result | PASS — frozen MATCH translation, 30-positive-sample requirement, nearest-rank indexes 14/28, population CV, candidate maxima and all selection boundaries pass. |
| Remaining risk | None at the pure-JVM layer. |

## Task 6 — candidate databases and FTS4 capability

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.search.SearchCandidateSmokeTest" "-Pandroid.testInstrumentationRunnerArguments.p4RunId=p4-smoke-20260808T121402Z"` |
| Environment | Reference device profile above. |
| Result | PASS — 5 methods, 0 failures: identical fill, escaped `%`, LIKE oracle order, FTS4 creation and explicit `docid == rowId`, and both query paths. |
| Remaining risk | None for candidate capability. |

## Task 7 — harness self-check

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.search.SearchBenchmarkTest#harnessSelfCheck" "-Pandroid.testInstrumentationRunnerArguments.p4RunId=p4-selfcheck-fix-20260808T132615Z"` |
| Environment | Frozen Xiaomi 14 Pro profile; airplane mode on. |
| Result | PASS — user-executed `BUILD SUCCESSFUL`. The corrected self-check covers exactly 35 query calls, exactly 60 clock reads around 30 measured calls, preparation outside timing, exception capture without matrix abort, exact expected/actual arrays, all samples, per-block thermal evidence, SoC/RAM header fields and frozen-profile validation. |
| Remaining risk | None for the harness self-check. The later fullProtocol run overwrote the connected-test XML, so this row retains the exact user-reported command/result. |

## Task 8 — measured device benchmark

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat :core:data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.paherdin.record.core.data.search.SearchBenchmarkTest#fullProtocol" "-Pandroid.testInstrumentationRunnerArguments.p4RunId=p4-full-fix-20260808T133145Z"`; independent JSONL validator described below. |
| Environment | Frozen Xiaomi 14 Pro profile: API 36 fingerprint above, `arm64-v8a`, `QTI SM8650`, hardware `qcom`, total RAM 11,710,394,368 bytes; airplane mode/interactive true; five-minute idle precondition; initial and all per-block thermal values `0` (`NONE`). |
| Result | PASS — connected-test XML: 1 test (`fullProtocol`), 0 failures/errors/skips. Evidence [`evidence/run-fix-20260808T133145Z.jsonl`](evidence/run-fix-20260808T133145Z.jsonl), 62 rows, SHA-256 `cbcb3f7f2a0db64d14fc9980605cba632e78e4762eafbfc5c8751f1bf675ddcc`. Independent recomputation found zero validation errors across the 60 unique counterbalanced blocks and 1,800 positive samples: expected IDs equal the frozen artifact; p50/p95/CV and summary maxima reproduce exactly; thermal is valid throughout; both candidates have zero exceptions. LIKE: 30/30 correct, comparison p95 `31,595,000 ns`, comparison CV `0.913067836690514`, eligible. FTS: q01/q14 incorrect, comparison p95 `12,860,052 ns`, comparison CV `0.64479202335742`, ineligible. Selection: `Like`. |
| Remaining risk | High LIKE maximum CV exposes device noise, but it cannot affect this selection because Candidate F fails correctness and the CV/p95 comparison branch is not entered. Historical `run-20260808T124648Z.jsonl` remains diagnostic-only and invalid for selection. |

### Task 8 independent evidence validation command

The executed PowerShell validator, formatted from the repo-root one-liner, was:

```powershell
$evidence = 'docs/specs/tech-poc/p4-chinese-search/evidence/run-fix-20260808T133145Z.jsonl'
$objects = Get-Content -LiteralPath $evidence | ForEach-Object { $_ | ConvertFrom-Json -Depth 20 }
$rows = @($objects | Where-Object { $_.type -eq 'query-block' })
$summary = $objects[-1]
$errors = [Collections.Generic.List[string]]::new()

$artifact = Get-Content -Raw 'core/data/src/testFixtures/kotlin/com/paherdin/record/core/data/search/ExpectedManifestV1.kt'
$regex = [regex]::new(
    'ExpectedFirstPage\("(?<id>q\d{2})", listOf\((?<body>.*?)\)\),',
    [Text.RegularExpressions.RegexOptions]::Singleline
)
$expectedById = @{}
foreach ($match in $regex.Matches($artifact)) {
    $expectedById[$match.Groups['id'].Value] = @(
        [regex]::Matches($match.Groups['body'].Value, '"(?<uuid>[0-9a-f-]{36})"') |
            ForEach-Object { $_.Groups['uuid'].Value }
    )
}

$expectedSequence = @()
for ($index = 1; $index -le 30; $index++) {
    $queryId = 'q{0:d2}' -f $index
    if ((($index - 1) % 2) -eq 0) {
        $expectedSequence += "$queryId/L", "$queryId/F"
    } else {
        $expectedSequence += "$queryId/F", "$queryId/L"
    }
}
$actualSequence = @($rows | ForEach-Object { "$($_.queryId)/$($_.candidate)" })
if (($actualSequence -join ',') -cne ($expectedSequence -join ',')) { $errors.Add('sequence') }

foreach ($row in $rows) {
    $expectedJson = ConvertTo-Json @($row.expectedIds) -Compress
    $actualJson = ConvertTo-Json @($row.actualIds) -Compress
    $frozenJson = ConvertTo-Json @($expectedById[$row.queryId]) -Compress
    if ($expectedJson -cne $frozenJson) { $errors.Add("$($row.queryId)/$($row.candidate): expected") }

    $samples = @($row.samplesNs | ForEach-Object { [int64]$_ })
    if ($samples.Count -ne 30 -or @($samples | Where-Object { $_ -le 0 }).Count -ne 0) {
        $errors.Add("$($row.queryId)/$($row.candidate): samples")
    }
    $sorted = @($samples | Sort-Object)
    $mean = ($samples | Measure-Object -Average).Average
    $variance = 0.0
    foreach ($sample in $samples) { $variance += ([double]$sample - $mean) * ([double]$sample - $mean) }
    $cv = [math]::Sqrt($variance / 30) / $mean
    if ([int64]$row.p50Ns -ne [int64]$sorted[14] -or
        [int64]$row.p95Ns -ne [int64]$sorted[28] -or
        [math]::Abs([double]$row.cv - $cv) -gt 1e-12) {
        $errors.Add("$($row.queryId)/$($row.candidate): metrics")
    }
    if ([bool]$row.correct -ne ($expectedJson -ceq $actualJson)) {
        $errors.Add("$($row.queryId)/$($row.candidate): correctness")
    }
    if ($null -ne $row.queryException -or $null -ne $row.failedAttemptElapsedNs) {
        $errors.Add("$($row.queryId)/$($row.candidate): exception")
    }
    if ($row.thermalStatusBefore -ge 2 -or $row.thermalStatusAfter -ge 2) {
        $errors.Add("$($row.queryId)/$($row.candidate): thermal")
    }
}

$like = @($rows | Where-Object { $_.candidate -eq 'L' })
$fts = @($rows | Where-Object { $_.candidate -eq 'F' })
if (@($like | Where-Object { -not $_.correct }).Count -ne 0 -or
    (@($fts | Where-Object { -not $_.correct }).queryId -join ',') -ne 'q01,q14' -or
    -not $summary.likeEligible -or $summary.ftsEligible -or $summary.selection -ne 'Like') {
    $errors.Add('selection')
}
if ($errors.Count -ne 0) { throw ($errors -join [Environment]::NewLine) }
```

## Corrective implementation — review findings

| Field | Recorded value |
|---|---|
| Command | RED hash mutation and GREEN restoration: `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.CorpusGeneratorTest.canonicalBytesAreDeterministicAndHashIsRepeatable" --tests "com.paherdin.record.core.data.search.QueryManifestTest.frozenArtifactHashIsStable"`; RED/compile then GREEN: `.\gradlew.bat :core:data:compileDebugAndroidTestKotlin`. |
| Environment | Host as above; Temurin 17. |
| Result | PASS for the two frozen-hash tests and corrected androidTest compilation. Candidate identity now uses a closed enum; normalization/escaping/MATCH translation occur before timing; candidate query exceptions are recorded with phase and optional failed-attempt duration; full expected/actual lists, raw samples and before/after thermal status are stored per block; the header records fingerprint/API/ABI/SoC/hardware/RAM/preconditions; output is chunked reconstructably while the canonical JSONL is stored in files. |
| Remaining risk | None — Tasks 7 and 8 subsequently passed on the reference device. |

## Task 9 — selection and ADR 0009

| Field | Recorded value |
|---|---|
| Command | User-executed `.\gradlew.bat check` (includes `SelectionRuleTest`); independent application of the frozen eligibility/selection rule to `evidence/run-fix-20260808T133145Z.jsonl`; `git diff --check`; PowerShell scans for trailing whitespace, broken local links, exactly 60 ADR measurement rows and exactly one `in-progress` spec. |
| Environment | Exact valid Task 8 header above. |
| Result | TECHNICAL REVIEW PASS — ADR 0009 contains both frozen hashes, complete reference profile, all 60 per-query correctness/p50/p95/CV rows, candidate maxima, eligibility, deciding step, normalization/order rules and M5 consequence. Only LIKE is eligible, so the fixed rule returns `Like`; CV/p95 tie-breaks are not entered. Documentation verification reports zero whitespace errors, zero broken local links, exactly 60 ADR rows and one active spec. **The user explicitly accepted ADR 0009 on 2026-08-08** after the independent 60-block/1,800-sample recomputation (0 errors); the ADR status is `Accepted`. |
| Remaining risk | None for Task 9. Task 10 (clean check with the user's deletion approval, install/launch, completion) remains pending. |

## Task 10 — regression and completion

| Field | Recorded value |
|---|---|
| Command | `.\gradlew.bat check` (249 tasks, 2m10s); `.\gradlew.bat clean check` (256 tasks, 4m30s — **explicitly authorized by the user** on 2026-08-08 for deletion of generated build directories); `.\gradlew.bat :app:installDebug`; `adb shell am start -W -n com.paherdin.record/.MainActivity`. |
| Environment | Host and reference device above (Xiaomi 14 Pro, API 36). |
| Result | PASS — `clean check` BUILD SUCCESSFUL (module graph, build, lint, `apiBoundaryCheck` and schema contract unchanged — zero-delta invariant); `:app:installDebug` BUILD SUCCESSFUL; launch `Status: ok`, `TotalTime: 705 ms`. Read-only worktree inspection confirms no production `src/main` search file, schema export, public-surface allowlist or dependency declaration change; `core/data/build.gradle.kts` only enables the module's test-fixtures feature. |
| Remaining risk | None. |

## Acceptance criteria mapping

| Criterion | Status and evidence |
|---|---|
| 1. Frozen corpus | PASS — Task 3, including literal-hash mutation proof; `corpus-v1` SHA-256 `2f27b875…b5e9`. |
| 2. Frozen manifest/oracle | PASS — Task 4, including literal-hash mutation proof; `ExpectedManifestV1.kt` SHA-256 `14b381f8…efb1`. |
| 3. Normalization v1 | PASS — Task 2 (trim → NFKC → bounded fold, expansion-free asserted). |
| 4. Frozen reference configuration | PASS — valid Task 8 header matches the complete frozen baseline (fingerprint/API 36/ABI/SOC/RAM/airplane/interactive/thermal). |
| 5. Both candidates executed | PASS — corrected fullProtocol, all 60 blocks, run `p4-full-fix-20260808T133145Z`. |
| 6. Correctness gate | PASS — exact expected/actual order retained; LIKE 30/30, FTS q01/q14 fail; evidence file retained. |
| 7. Absolute p95 and metrics | PASS — all 1,800 samples and independently reproduced metrics retained; LIKE comparison p95 31,595,000 ns ≤ 300 ms; FTS 12,860,052 ns but correctness-ineligible. |
| 8. Selection rule | PASS — only LIKE eligible; fixed rule selects `Like`; CV/p95 tie-breaks not entered. |
| 9. ADR 0009 | PASS — **accepted by the user on 2026-08-08** after independent 60-block/1,800-sample recomputation (0 errors). |
| 10. No production change | PASS — `clean check` plus worktree boundary inspection; `2.json` unchanged, allowlist unchanged, `apiBoundaryCheck` passes. |
| 11. Regression | PASS — authorized `clean check` (256 tasks), install and launch `Status: ok`; roadmap P4 exit gate satisfied (LIKE selected, recorded by ADR 0009). |

## Post-completion code review (2026-08-08)

Two-axis review of the completed P4 code (21 test-source files + `build.gradle.kts` testFixtures block) against the frozen spec/plan and the repo's coding standards.

**Spec axis:** no findings — the implementation matches the frozen plan text verbatim (MATCH translation, metric formulas, selection rule, corpus rules, DDL, harness protocol); the reviewer's four reported "plan-vs-code" deviations were re-verified against the current plan text and do not exist (the reviewer had read an outdated plan revision).

**Standards axis:** no hard violations. Six judgement findings — three fixed, three recorded as intentional:

| # | Finding | Disposition |
|---|---|---|
| 1 | `summarizeCandidate` duplicated the per-query-maximum aggregation already defined by `CandidateMetrics` | **Fixed** — `summarizeCandidate` now delegates the maxima to `CandidateMetrics` (SearchBenchmarkTest.kt), keeping its completeness/eligibility/exception logic |
| 2 | Frozen White_Space code-point set copied in three places (`SearchNormalizer`, `FtsMatchTranslator` regex, test pin) | **Recorded, not fixed** — the test pin is an intentional independent verification; sharing the regex with the normalizer would couple the frozen translator to the normalizer's representation without changing behavior |
| 3 | `P4FixtureMarker` + two visibility tests served the Task 1 gate and are now inert | **Recorded, not fixed** — they are the plan-pinned Task 1 gate artifacts and remain as historical evidence |
| 4 | New `src/test/kotlin/` alongside existing `src/test/java/`; two statement semicolons in `SearchCandidateSmokeTest` | **Recorded, not fixed** — pre-existing layout convention; semicolons are stylistic and lint-clean |
| 5 | `coerceAtMost(50)` asserted a lower bound; self-check sample/clock counts coupled to harness constants | **Fixed** — `firstPageIsTruncatedToFifty` now asserts `>= 50` explicitly; the 35/60 counts are the self-check's purpose (proving the harness measures exactly 5+30 calls and 2 clock reads per run) and stay |
| 6 | KDoc claimed the run directory is "new per explicit run" unconditionally | **Fixed** — KDoc now states the `p4RunId`-scoped condition and the local fallback |

Verification after the fixes: `.\gradlew.bat :core:data:testDebugUnitTest --tests "com.paherdin.record.core.data.search.*"` and the androidTest compile task — results recorded below; no measurement, evidence, ADR content, or selection conclusion changed.

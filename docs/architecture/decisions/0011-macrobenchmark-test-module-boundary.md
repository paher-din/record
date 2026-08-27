# ADR 0011: Macrobenchmark test-module boundary

## Status

Accepted

## Date

2026-08-16

The user explicitly accepted the separate Macrobenchmark test-module design on 2026-08-16 while reviewing the Tech PoC P6 draft repair: the production graph remains exactly seven modules and `:benchmark` is an allowlisted test-tool project. ADR 0012 later revised the display/jank measurement contract, so the P6 measurement-result ADR becomes ADR 0013.

## Context

The production architecture has exactly seven Gradle modules, and the root machine check enforces that exact set and its dependency edges. Tech PoC P6 must measure a real Compose surface out of process with AndroidX Macrobenchmark. The Android-standard harness uses a separate `com.android.test` Gradle project that targets an application variant; placing the runner inside the target app would no longer be an out-of-process Macrobenchmark and would mix measurement code with the measured process.

Calling `:benchmark` “outside the production graph” without changing the owning architecture fact would still create an eighth Gradle project and make the existing exact-set check fail. The project therefore needs one explicit distinction between production modules and test-tool projects, without weakening the seven-module product boundary or creating a general exception for future modules.

## Options considered

- **Keep seven exact production modules and allowlist one separate `:benchmark` test-tool project (chosen).** This follows the AndroidX harness shape, preserves release isolation, and lets the machine check reject every other project and edge.
- Put Macrobenchmark tests in `:app` or another production module. Rejected because the benchmark runner must be outside the measured target process and because test orchestration would become application-owned code.
- Create an ungoverned eighth module and simply exclude it from the current check. Rejected because it would make the architecture statement and machine evidence untruthful and establish an open-ended module bypass.
- Abandon Macrobenchmark and use only an in-process instrumented or microbenchmark harness. Rejected because P6 measures an end-user load-and-scroll journey and the user selected Macrobenchmark for that boundary.

## Decision

The production graph remains exactly these seven modules: `:app`, `:feature:finance`, `:feature:notes`, `:feature:timeline`, `:core:model`, `:core:data`, and `:core:ui`. Their responsibilities and permitted edges do not change.

P6 may add exactly one test-tool Gradle project named `:benchmark`. It uses the `com.android.test` plugin pinned to the existing AGP version, targets only `:app`'s non-debuggable `benchmark` variant, and owns only out-of-process benchmark orchestration, device interaction, and report capture. It is forbidden from owning a product journey, domain rule, application API, production resource, production manifest declaration, or persistence fact.

The benchmark target surface is variant-scoped. `:app` may contain the benchmark-only Activity, manifest/profileable declaration, and render wiring only in its `benchmark` source set. The shadow Timeline projection and its typed read boundary belong to `:core:data`'s benchmark source set; the Activity consumes that boundary and never accesses a DAO or Room entity. None of those declarations exists in `main` or `release`, and the production Room schema and public-surface allowlist remain unchanged.

The root module check is revised during P6 implementation to enforce two explicit sets:

1. the exact seven production modules and their existing allowed edges;
2. the exact test-tool allowlist `{ ":benchmark" }`, whose only project dependency is the target relationship to `:app` required by `com.android.test`.

The check examines benchmark-specific dependency configurations as well as the existing configurations, rejects a production dependency on `:benchmark`, and proves that release runtime artifacts do not contain benchmark code, resources, manifest components, or benchmark-only dependencies.

## Consequences

- “Seven modules” continues to mean the exact production application graph; the repository has one additional, explicitly governed test-tool project while P6/M5 benchmark evidence is required.
- The four production-module split thresholds are not weakened. A future test-tool project requires its own accepted ADR and cannot inherit this exception.
- P6 introduces build/test infrastructure but no production module, schema, product surface, or release dependency.
- Removing the benchmark capability deletes `:benchmark` and benchmark source/build variants and restores the repository to only the seven production Gradle projects without changing production behavior or data.
- ADR 0012 owns the later native-display deadline-jank correction. The P6 result, frozen baseline, and M5 consequence are recorded separately in ADR 0013 so this architecture decision remains independent of measurement results.

## Verification

- The revised module check passes for the exact seven production modules plus the single allowlisted `:benchmark` project and fails negative fixtures containing an extra project, a production-to-benchmark edge, or an unapproved benchmark edge.
- `:benchmark` targets only the non-debuggable benchmark variant of `:app`.
- Source, merged release manifest, release dependency graph, and final release APK inspections contain no benchmark Activity, profileable benchmark override, benchmark runner, shadow projection, or benchmark-only dependency.
- The existing production dependency-edge checks and `apiBoundaryCheck` continue to pass unchanged in meaning.

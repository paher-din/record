# ADR 0017: M2 finance schema, frozen normalization, and logical backup v2

## Status

Accepted

## Date

2026-09-04

The user explicitly approved the schema/migration/backup design section on 2026-09-04 by replying “批准”, then delegated independent written-spec review and forward lifecycle progress by saying “你来审阅spec吧，如果没有问题，就继续往前推进”. The resulting [M2 specification](../../specs/mvp/m2-finance-core/spec.md) records the review corrections and approval context. This accepts the architecture decision and permits plan work; it does not authorize execution of the database schema or data migration redline.

## Context

The current Room version 2 persists Account and finance headers/effects but has no occurrence time, memo, Category, or CategoryAllocation. P1/P2 deliberately proved financial structure before the user-facing finance slice, so existing Income and Expense rows have no required allocation. The current logical backup format version 1 serializes Transaction as only `id` and `type`; it cannot preserve an independently editable occurrence time or memo.

[ADR 0004](0004-catalog-lifecycle-and-normalized-names.md) requires a concrete, frozen Unicode trim → NFKC → full case-fold implementation before M2 creates the first catalog schema. Runtime Android ICU data changes with platform releases and therefore cannot identify persisted `nameKeyVersion = 1`. [ADR 0006](0006-version-1-persistence-contract.md) makes prior encodings and stable IDs compatibility facts. [ADR 0007](0007-recoverable-migration-snapshot-ownership.md) requires every later Room migration to reuse the recoverable coordinator. [ADR 0010](0010-generation-bootstrap-and-live-session-handoff.md) requires restore staging and startup revalidation to preserve one complete generation.

The pre-M2 public repository also accepted arbitrary String IDs even though the domain model and M1 typed routes require canonical lowercase UUID text. Rewriting such an identity would change a stable fact and every foreign-key endpoint. The M2 compatibility path therefore needs an explicit fail-closed decision rather than a silent repair.

## Options considered

### Schema version 3 plus logical backup format version 2 with explicit format-1 up-conversion

Chosen. Version 3 can enforce the complete M2 finance/catalog shape, while format 2 makes new Transaction fields and Category datasets structurally mandatory. A separate format-1 reader preserves backward portability through a declared isolated conversion rather than weakening format-2 validation.

### Keep logical backup format version 1 and make new Transaction fields optional

Rejected. A format-1 package omitting occurrence or memo would be indistinguishable from a malformed current export. `databaseSchemaVersion` is untrusted provenance and cannot safely serve as the structural format discriminator. Optional interpretation would let incomplete current data pass as legacy.

### Leave legacy Income/Expense without allocations

Rejected. A permanent legacy exception would make the finance matrix depend on record age, weaken restore/startup validation, and force every future query and exporter to carry two meanings of Income/Expense.

### Seed the user's normal Category catalog during every install

Rejected. Product behavior requires a first-install-empty persisted catalog. Context templates remain non-persisted creation suggestions. Only an upgrade/import containing legacy uncategorized Income/Expense receives a migration-only Category because a valid allocation must be created.

### Use runtime `android.icu`, locale lowercase, or database collation

Rejected. Their Unicode tables or locale behavior are not fixed by `nameKeyVersion = 1`, so an OS/dependency change could alter uniqueness without a declared migration.

### Rewrite non-canonical IDs or coerce unsupported currency/amounts

Rejected. Identity rewriting breaks stable references and backup equality. Guessing currency scale or truncating `Long.MIN_VALUE` magnitude changes money. The recoverable coordinator exists to preserve the prior vault when a compatible, lossless migration is impossible.

## Decision

### Room schema version 3

M2 advances `RecordDatabase` from 2 to 3 and retains every prior table and accepted encoding. Version 3 adds:

- non-null `Transaction.occurredAt` as epoch millis UTC;
- nullable `Transaction.memo` as Unicode-trimmed plain text, absent when empty and at most 500 Unicode code points;
- `Category(id, displayName, nameKey, nameKeyVersion, state, archivedAt)`;
- `CategoryAllocation(id, transactionId, categoryId, amountMinor)`;
- a unique Category `nameKey` index across active and archived rows;
- a unique `(transactionId, categoryId)` allocation constraint;
- `NO ACTION` foreign keys and the Transaction-owned/Category-history indexes required by M2 reads.

Category state is exactly `ACTIVE` or `ARCHIVED`, with `archivedAt` absent/present consistently. Category has no hierarchy, income/expense discriminator, icon, color, or note. CategoryAllocation is positive and uses signed `Long` minor units. Income/Expense has one or more unique Category allocations whose widened sum equals entry magnitude; Opening and Transfer have none.

New account currencies are exactly `CNY`, `USD`, `EUR`, `JPY`, `HKD`, and `GBP`; JPY scale is zero and the other scales are two. New Income/Expense/Transfer magnitude is `1..Long.MAX_VALUE`; an Expense entry therefore excludes `Long.MIN_VALUE`. Opening alone accepts the complete signed-Long range. Every finance write rejects atomically if an affected exact balance cannot narrow to signed Long.

### Frozen name-key version 1

The implementation is `com.ibm.icu:icu4j:78.3`, ICU 78 / Unicode 17 data. It:

1. removes leading/trailing code points having Unicode `White_Space`;
2. applies NFKC;
3. applies locale-independent full string case folding with default CaseFolding mappings;
4. persists the resulting Unicode scalar sequence as UTF-8 text with `nameKeyVersion = 1`.

The fixed corpus stores expected UTF-8 bytes and covers trim classes, compatibility equivalence, fold expansion, locale-sensitive cases, supplementary code points, and 0/1/40/41 display-name boundaries. Runtime `android.icu` is not a fallback. Removing or updating this implementation cannot rewrite keys implicitly; it requires an exact version-1 reader or another accepted, collision-checked, recoverable migration decision.

### Recoverable database migration

The existing coordinator owns `2→3` and the composed `1→2→3` path. Before mutation it publishes the verified snapshot and validates:

- every ID governed by the domain UUID invariant and every FK endpoint is canonical lowercase UUID text;
- every Account currency is one of the six supported codes;
- every legacy Income/Expense magnitude can become a positive M2 allocation.

A non-canonical identity, unsupported currency, or `Long.MIN_VALUE` Expense fails closed with the old vault and snapshot unchanged and Room/shell blocked. No identity, scale, or amount repair is attempted.

For a compatible vault, existing Transaction occurrence is copied from same-ID `EntityRef.createdAt` and memo becomes absent. If at least one legacy Income/Expense exists, the all-or-nothing migration creates one ordinary active Category named **未分类（迁移）** through name-key version 1 and one exact allocation per such Transaction. With no such Transaction, Category remains empty. Opening and Transfer receive no allocation. New migration facts use random UUIDs and become observable only after complete version-3 validation and startup success.

### Logical backup format version 2

M2 export writes `formatVersion = 2`. It retains the eight P5 datasets, makes `occurredAt` and nullable `memo` required Transaction fields, and requires singleton Category and CategoryAllocation datasets even when empty. The existing manifest hash/count/path/resource contracts and `catalogNameKeyVersions` summary remain binding.

The reader supports formats 1 and 2. Format 1 imports only into isolated staging and applies the same canonical-identity, currency, magnitude, occurrence, memo, and migration-only Category/allocation rules as the database migration. Format 2 is strict and never falls back to legacy interpretation for a missing current field or dataset. `databaseSchemaVersion` remains provenance, not format dispatch.

Both formats pass the complete current finance, Category, XOR, FK, balance, relationship, attachment, generation, and startup validation before overwrite confirmation. An archived Category allocation is valid historical data and survives unchanged; active-only availability governs new writes rather than restore history.

### Application write and reconciliation consequence

Transaction edits and Withdraw/Restore compare `EntityRef.updatedAt`. Category lifecycle writes compare the exact last-read persisted tuple without adding a Category revision column. A stale compare-and-set changes nothing.

New aggregate drafts retain generated IDs across retry. When commit may have succeeded but its response failed, the data boundary re-reads by those IDs and compares the complete aggregate: exact match returns success, confirmed absence/prior revision returns recoverable failure, and mismatch returns a blocking conflict. Retry never generates a second finance identity.

## Consequences

- The database and logical backup each advance one version for an explicit compatibility reason; the axes remain independent.
- First install remains Category-empty, while compatible pre-M2 finance history becomes valid without inventing per-record exceptions.
- Some technically accepted PoC-era String IDs, arbitrary currencies, or `Long.MIN_VALUE` Expense fixtures can no longer migrate. They fail before mutation and remain recoverable rather than being silently changed. Test fixtures must use valid canonical product identities unless they intentionally prove this rejection.
- ICU4J adds an external artifact and package-size cost. The M2 plan must define a blocking dependency sequence: the first gate records exact license, maintenance, Android compatibility, merged-manifest/network, resolved artifact/raw size, and removal path; the schema task then records the reachable migration-path R8/package-size result before the public finance data/UI work proceeds.
- Existing format-1 backups remain readable, but M2 never exports format 1. A missing format-2 field is corruption, not backward compatibility.
- Backup/restore implementation changes are required in M2 even though the user-facing Backup/Restore UI remains M6.
- Rollback before migration publication uses the existing verified snapshot/recovery state. Removing the M2 feature code does not permit downgrading a version-3 vault or format-2 backup; rollback requires a separately designed export/restore or forward migration.
- Cross-currency conversion remains outside MVP and receives no placeholder schema or interface.

## Verification

- Exported schema JSON and Room tests prove the exact version-3 tables, fields, indexes, unique constraints, and `NO ACTION` FKs.
- N-1/N-2 tests exercise `2→3` and `1→2→3` through the recoverable coordinator, including interruption before/after every preflight, DDL, backfill, generated fact, validation, and startup boundary.
- Positive legacy fixtures prove conditional migration-only Category creation and exact allocations. Non-canonical-ID/FK, unsupported-currency, and `Long.MIN_VALUE` Expense fixtures prove unchanged old vault/snapshot and blocked Room/shell.
- ICU corpus/property tests prove expected version-1 UTF-8 keys, locale independence, idempotence, boundaries, and active-plus-archived uniqueness.
- Format-2 round trip preserves every current field and reference through activation and a second startup. Format-1 import proves only the declared up-conversion. Every malformed/unsupported fixture leaves the current-vault hash unchanged and staging identifiable.
- Atomic/fault tests prove account/opening, Income/Expense/allocation, Transfer, edit, Withdraw/Restore, Category lifecycle, stale compare-and-set, and ambiguous-commit reconciliation expose only one complete old/new state.
- Dependency and final-artifact checks prove the pinned ICU coordinate/license, no unexpected manifest/network capability, measured package impact, and viable version-1 retention/removal path.

## Core-document synchronization

The accepted decision is reflected in:

1. [MVP requirements](../../product/mvp-requirements.md): staged Journey 2 ownership, finance lifecycle, memo, currencies, and Category templates.
2. [Information architecture](../../experience/information-architecture.md): withdrawn list and Transaction status-confirmation route.
3. [Interaction rules](../../experience/interaction-rules.md): exact amount/time input, allocation behavior, editing, Withdraw/Restore, templates, and navigation.
4. [Domain model](../domain-model.md): Transaction fields, currency/money/allocation invariants, template boundary, and recoverable withdrawal.
5. [Data and storage](../data-and-storage.md): schema version 3, frozen normalization, migration, format version 2, and format-1 up-conversion.
6. [Architecture overview](../overview.md) and [engineering conventions](../../engineering/conventions.md): repository/application flow, exact parsing, time, CAS, and ambiguous-commit reconciliation.
7. [Testing strategy](../../engineering/testing-strategy.md): migration, backup, property, ViewModel, Compose, dependency, and evidence gates.
8. [Roadmap](../../roadmap.md): M2 finance-owned versus M5 Timeline completion boundary.

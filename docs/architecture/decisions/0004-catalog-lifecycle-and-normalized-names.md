# ADR 0004: Catalog lifecycle and normalized names

## Status

Accepted

## Date

2026-08-04

## Context

Category, Tag, and Project catalogs are valid when empty, and inline creation must make first use reachable without personalized seed data. Catalog items are referenced by financial history, tag assignments, and primary-project assignments, so rename, archive, restore, logical backup, and cross-device restore must preserve identity and historical meaning.

A display-only comparison or a runtime-dependent normalization result is not stable enough for database uniqueness, backup compatibility, or migration. Case behavior and Unicode normalization can vary when an implementation, platform, or Unicode data table changes. The project therefore needs one persisted, versioned naming contract and one recoverable lifecycle contract before M2 introduces the first catalog schema.

The authoritative domain behavior is defined by the [domain model](../domain-model.md). Persistence, migration, backup, and restore enforcement is defined by [data and storage](../data-and-storage.md), with required evidence in the [testing strategy](../../engineering/testing-strategy.md).

## Decision

### Persisted name contract

Every Category, Tag, and Project persists all three fields:

- `displayName`: the user-visible name after Unicode trim;
- `nameKey`: the canonical comparison key derived from `displayName`;
- `nameKeyVersion`: the declared canonical-pipeline version used to derive `nameKey`.

The version-1 canonical pipeline is exactly:

1. Unicode trim removes leading and trailing code points with the Unicode `White_Space` property.
2. Normalize the trimmed value with Unicode NFKC.
3. Apply full Unicode case folding, independent of device locale and application locale.

`nameKeyVersion=1` identifies this exact ordered pipeline. `nameKey` is the resulting Unicode scalar sequence persisted and serialized as UTF-8 text; locale collation, database collation, and platform-default lowercase operations are not part of the key.

The 1–40-code-point display-name limit is evaluated after Unicode trim and before key derivation. The persisted `displayName` is that trimmed value. Before M2 implementation begins, the owning specification must freeze the concrete normalization implementation and Unicode data-table version and verify them against the approved fixed corpus. Replacing the implementation, Unicode version, pipeline order, or algorithm requires a new ADR and an explicit database and logical-backup migration; a dependency update cannot change keys implicitly.

`nameKey` uniqueness is scoped within each catalog type. Category names compete only with Category names, Tag only with Tag, and Project only with Project. Within a type, active and archived items share one namespace. A collision with an archived item must resolve through Restore or Rename, never by creating a duplicate identity.

### Persisted lifecycle contract

Category and Tag persist an explicit state whose only valid values are `ACTIVE` and `ARCHIVED`, plus archive metadata. In MVP the required archive metadata is nullable `archivedAt`: it is absent in `ACTIVE`, present in `ARCHIVED`, set by Archive, and cleared by Restore. Rename preserves identity and state.

Project does not add a parallel catalog-state field. User-facing Project Archive sets its `EntityRef.deletedAt` soft-delete timestamp; Restore clears that timestamp. Existing primary-project assignments remain attached to the same Project and resolve its display name and archived state. A Project with `deletedAt` present is unavailable for new assignment but remains a valid historical target.

MVP exposes no user hard delete for Category, Tag, or Project. A future physical-purge maintenance policy remains separate from user-visible Archive and requires its own authorization and compatibility analysis.

### Logical backup and restore contract

Logical backup records preserve, for every catalog fact:

- `displayName`;
- `nameKey`;
- `nameKeyVersion`;
- Category/Tag `state` and `archivedAt`;
- Project lifecycle through the matching `EntityRef.deletedAt`;
- every historical allocation, tag association, and primary-project reference, including references to archived items.

The manifest describes and hashes the logical record payloads but does not repeat every record field. The backup format and implementation declare which `nameKeyVersion` values are supported.

Restore accepts only a declared and implemented normalization version. During isolated staging, it reruns the declared pipeline over every `displayName` and requires an exact byte-for-byte match with persisted `nameKey`. It then validates per-type active-plus-archived uniqueness, state/archive-metadata consistency, first-level Category shape, Project `deletedAt` semantics, and every historical catalog reference. Availability rules for new writes do not invalidate a historical reference to an archived item.

An unsupported version, mismatched key, duplicate canonical key, illegal state, inconsistent archive metadata, invalid display name, non-first-level Category, or dangling historical reference rejects the complete restore candidate before activation. The current vault remains unchanged and no partial restore is visible.

### Schema, index, format, and migration consequences

The initial catalog schema must include the persisted name fields and lifecycle fields above. It must enforce or transactionally prove a unique `(catalogType, nameKey)` namespace across active and archived rows; separate physical tables may implement the same per-type rule without a synthetic cross-type discriminator.

The initial logical-backup format must serialize the complete contract. Future schema or format revisions cannot silently recalculate keys with a new runtime. A normalization change requires an explicit migration that reads the prior declared version, derives and collision-checks the proposed new keys under the newly accepted version, preserves historical references and archive state, and publishes the change only if the whole dataset validates. Any collision or unsupported source version blocks migration and enters the normal recoverable migration path.

## Alternatives considered

### Preseed catalogs only

Rejected because personalized defaults cannot cover the owner's vocabulary, make first-use behavior depend on bundled content, and do not solve rename, archive, uniqueness, backup, or cross-device compatibility.

### Uniqueness among active items only

Rejected because an archived item could be duplicated under a new identity, splitting historical references and making Restore ambiguous.

### Hard delete

Rejected because Category allocations, Tag assignments, and primary-project assignments would dangle or require destructive history rewriting. Recoverable Archive preserves identity and auditability.

### Runtime-only normalized key

Rejected because rebuilding comparison keys with an unspecified platform/runtime Unicode table can change uniqueness after an OS, dependency, or device change. Persisted keys plus an explicit version make migration and restore deterministic and reject unsupported semantics safely.

## Consequences

- Catalog writes store display and comparison forms together and must never update one without the other.
- Active and archived items consume the same per-type namespace, making Restore deterministic.
- Backup records are slightly larger and compatibility checks stricter, but cross-device restore no longer depends on the destination runtime's implicit normalization behavior.
- Project Archive remains aligned with the existing `EntityRef` soft-delete model while Category and Tag retain explicit lightweight lifecycle state.
- Unicode implementation or data-table changes become deliberate architecture and migration work instead of transparent dependency upgrades.
- Invalid catalog snapshots fail closed before generation activation, preserving the current vault.

## Verification

- A fixed, versioned corpus covers Unicode trimming, NFKC compatibility equivalence, full case-fold expansion, locale-sensitive edge cases, supplementary code points, and 0/1/40/41-code-point display-name boundaries.
- Property tests prove deterministic version-1 key generation and per-type uniqueness across active and archived items.
- Schema and Room tests prove atomic `displayName`/`nameKey`/`nameKeyVersion` writes and valid lifecycle transitions.
- Migration tests prove the frozen implementation/table version, collision detection, rollback, and no implicit key rewrite.
- Logical-backup round trips preserve every name field, lifecycle field, archive timestamp, and historical reference.
- Invalid-restore fixtures cover unsupported versions, mismatched keys, canonical duplicates, illegal states, invalid names, non-first-level Category data, and dangling historical references; every rejection leaves the current vault unchanged, cleans staging deterministically, and reports no success.

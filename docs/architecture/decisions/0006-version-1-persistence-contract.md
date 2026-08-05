# ADR 0006: Version-1 persistence contract

## Status

Accepted

## Date

2026-08-05

## Context

Tech PoC P1 introduces `databaseVersion = 1`, the origin of the persisted schema. Encoding choices made there — timestamp representation, enum storage and validation, foreign-key actions, and the minimal index set — become persistence-compatibility facts: changing any of them later is a schema migration that affects every N-1/N-2 upgrade, the logical backup format, and the restore reader. [ADR 0001](0001-native-android-room-2.md) requires a new persistence ADR for a persistence-contract change, and P1 is where that contract is first defined. The authoritative domain facts and invariants are the [domain model](../domain-model.md); migration and recovery rules are owned by [data and storage](../data-and-storage.md).

## Options considered

### Timestamp encoding

- **Epoch millis as UTC `Long` (chosen).** Locale- and format-independent, machine-readable, unambiguous instant semantics, and a stable SQLite `INTEGER` storage class that Room and migration fixtures handle natively.
- ISO-8601 text. Rejected: string parsing ambiguity, collation/format concerns, larger storage, and no benefit for a single-owner local app whose UI formatting is a separate layer.
- Epoch seconds. Rejected: sub-second resolution would be lost; `Long` millis costs nothing.

### Enum storage and validation

- **String values from the `:core:model` enums (chosen).** Room's default enum conversion stores `name` as text; values can only enter through the strongly typed enums, so the whitelist is enforceable at the public API boundary.
- Integer ordinal. Rejected: reordering or inserting a value changes persisted meaning; string names are stable.
- Custom `TypeConverter` to versioned text. Rejected in P1: the default conversion already stores stable names; a converter adds code without changing the contract.
- **Database `CHECK` constraint whitelist (rejected).** Room 2.8.4 exposes no `CHECK` declaration point in `@Entity`/`@ColumnInfo`; a `CHECK` would require raw `SQLiteOpenHelper` table creation or triggers, neither of which the Room schema-JSON export, identity hash, and migration verification recognize in the plain Room path. The whitelist is therefore enforced by the strongly typed public API and the XOR invariant validator instead. A future database-level enforcement is a new decision requiring a new ADR.

### Foreign-key actions

- **`NO ACTION` on every foreign key (chosen).** The domain model uses soft delete first and coordinates deletion through repository atomic transactions; `NO ACTION` forces every removal through that coordination and prevents silent orphan cleanup by the database.
- `ON DELETE CASCADE` / `SET NULL`. Rejected: cascades would hide coordination errors and could bypass the explicit purge protocol in the domain model.

### Indexes

The version-1 index set is the minimum that the P1 query paths and the M5 bidirectional relation queries need:

- `AccountEntry(transactionId)`, `AccountEntry(accountId)`;
- `TransactionNoteRelation` composite PK `(transactionId, noteId)` (prefix covers transaction-direction queries) plus `(noteId, transactionId)` for note-direction queries.

Later query paths are added by their owning slice with an explicit migration, not in P1.

### Cross-table same-ID uniqueness

`Transaction.id` and `Note.id` must never collide, but SQLite declarative constraints cannot express uniqueness across two tables. This is enforced by the XOR invariant validator inside the repository atomic entry points, which are the only public write surface.

## Decision

The version-1 persistence contract is:

- every timestamp is an epoch-millis (UTC) `Long` column; `deletedAt` is the same type and nullable, absent while active;
- enums persist as strings supplied by the `:core:model` enums; the whitelist (including that a `PROJECT` entity type cannot be persisted in P1) is enforced by the strongly typed public API and the XOR invariant validator, with no database-level `CHECK` in P1;
- every foreign key uses `NO ACTION` with no `ON DELETE`/`ON UPDATE CASCADE`;
- the index set above is the version-1 set;
- cross-table same-ID uniqueness is enforced by the XOR invariant validator at the repository atomic entry points.

The contract is verified against the exported Room schema JSON, which is versioned with the repository.

## Consequences

- Version-1 encodings are the compatibility origin. Any later change to timestamps, enum storage, FK actions, or indexes is an explicit schema migration per data-and-storage, including the recoverable-migration-snapshot protocol before the first migration opens a new schema.
- Enum whitelist enforcement lives at the API/validator layer. If a later slice needs database-level enforcement, it requires raw table-creation or trigger machinery and a new ADR, because the plain Room path cannot express `CHECK`.
- Schema evolution is deliberate: the exported schema JSON is the verification artifact, and migration tests must prove the contract survives every N-1/N-2 upgrade.
- The logical backup format inherits the contract: restore must read epoch-millis timestamps and string enum values as persisted, without reinterpreting them.

## Verification

- The exported `databaseVersion = 1` schema JSON is committed and its table/column/index/FK set is asserted by a test to be exactly the contract above.
- Instrumented tests prove persisted timestamp and enum values round-trip exactly and that the XOR validator rejects a `PROJECT` entity type and cross-table ID collisions.
- Migration tests (from the first migration onward) prove the contract encodings survive upgrades.

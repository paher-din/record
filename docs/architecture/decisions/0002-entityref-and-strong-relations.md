# ADR 0002: EntityRef and strong relations

## Status

Accepted

## Date

2026-08-04

## Context

Transactions, notes, and projects are independent domain facts, while selected capabilities need stable identity across more than one fact type. The model also needs to support transaction-note associations without weakening endpoint validation. The authoritative invariants are defined by the [domain model](../domain-model.md).

## Options considered

- Separate `Transaction`, `Note`, and `Project` facts with a common `EntityRef` identity.
- A single generic entity fact containing all subtype data.
- Typed association tables for known relationships.
- A generic `EntityRelation` abstraction from the start.
- Rebuildable timeline and search projections over facts.

## Decision

Keep `Transaction`, `Note`, and `Project` as separate facts. Give each exactly one same-ID `EntityRef`, with an XOR subtype invariant that requires the discriminator and exactly one subtype to agree.

In MVP, tags and attachments may target only `Transaction` and `Note` EntityRefs. Model the core transaction-note association as the typed, plain `TransactionNoteRelation`, without relation kind or speculative metadata. Timeline and search are rebuildable projections and never fact authorities.

Defer a generic `EntityRelation` until a second long-tail relationship has been validated and product approval explicitly authorizes the abstraction.

## Consequences

- Typed tables provide real foreign keys, explicit endpoint rules, and clear invariants.
- Separate facts and capabilities require additional tables and coordinated atomic writes.
- New endpoint types and relationship shapes require deliberate schema expansion rather than silently entering a generic relation store.
- Read-oriented timeline and search models can evolve and rebuild without becoming write authorities.

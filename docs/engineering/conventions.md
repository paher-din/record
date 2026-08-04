# Engineering conventions

This document is the source of truth for implementation discipline. Module direction and data flow remain owned by the [architecture overview](../architecture/overview.md), domain invariants by the [domain model](../architecture/domain-model.md), persistence and recovery protocols by [data and storage](../architecture/data-and-storage.md), and user-visible phases by the [interaction rules](../experience/interaction-rules.md).

## Kotlin and Compose structure

- Kotlin types, sealed hierarchies, and composables use `UpperCamelCase`; functions, properties, parameters, and local values use `lowerCamelCase`; constants use `UPPER_SNAKE_CASE` only for compile-time constants.
- Composable screen and component names are nouns or noun phrases. UI event types describe user intent, such as `SaveExpense` or `RetryRestore`; handlers use `on...` names. Do not name an event after a widget callback when the product intent is more specific.
- Prefer immutable value types and exhaustive sealed types for domain and UI states. Monetary values use integer minor units; `Float` and `Double` are forbidden for money.
- Each file has one public focus: one screen contract, one ViewModel, one repository contract, one domain type family, or one cohesive private implementation. Split a file when unrelated public responsibilities change for different reasons; do not split a cohesive type merely to reduce line count.
- Keep composables declarative. Business validation, persistence orchestration, retry policy, and durable-success decisions do not live in composables.
- `:core:model` remains pure Kotlin. Android framework types, Room types, URIs, and storage paths cannot enter its public API.

## UI state and operation phases

- A ViewModel accepts typed events and exposes one immutable `StateFlow<UiState>`. Compose renders that state and emits events; it does not call repositories as a side effect of composition.
- Every editor or long-running operation uses the exact `Phase.*` closed set and transitions defined by the [interaction rules](../experience/interaction-rules.md). Features may narrow transitions but may not add aliases or hidden phase flags.
- Draft fields, pending media, field errors, request revision, confirmation availability, and the current phase belong to the immutable UI state when relevant. A recoverable failure restores the complete draft and selections.
- `Phase.Saving` is a locked, single-attempt state. Back, Cancel, Discard, and duplicate Confirm cannot cancel or duplicate a durable write. One attempt produces exactly one declared terminal result.
- Search results and errors carry the current query/filter revision. A ViewModel ignores every completion from a superseded or invalidated revision.
- UI success is emitted only after the complete durable commit and required post-commit verification succeed. Financial totals, relationships, attachments, exports, and restore results are never optimistically shown as successful.

## Deterministic execution

- Inject coroutine dispatchers used for I/O, CPU work, and scheduling. Production defaults may bind to platform dispatchers only at the composition root; tests provide controlled dispatchers.
- Inject clocks and time-zone providers wherever time affects a fact, filename, retention decision, retry schedule, or UI state. Tests never depend on the wall clock.
- Generate IDs through an injected application-facing generator when deterministic identity is required by a test.
- Structured concurrency owns every operation lifetime. A detached coroutine is allowed only for a documented application-scoped, idempotent background responsibility with an explicit owner and cancellation policy.

## Transaction and orchestration ownership

- A repository owns the atomic transaction for one aggregate and returns only after its durable boundary is known. Callers cannot compose DAO calls into an application transaction.
- Create a Use Case only when an operation requires cross-aggregate atomicity, is shared by multiple ViewModels, or centralizes a domain invariant. A table, screen, or DAO does not automatically receive a Use Case.
- Transaction, finance children, requested relations, attachment metadata, and links that belong to one owner save are one repository/Use Case result. A partial result is not representable as success.
- From a saved Detail, a relation or assignment editor may replace its complete set as an independent atomic operation. From any owner Editor, the same subflow updates only the parent draft; the parent save owns the single durable commit. The implementation must not create a placeholder owner to satisfy a draft relation.
- Timeline, Projects, and Search consume projections and route edits to the owning feature. They cannot write finance or note facts.

## Stable application errors

`:core:data` catches Room, SQLite, file, ZIP, permission, Storage Access Framework, and system exceptions at its boundary and maps them into stable typed application errors. Raw exceptions and provider-specific messages never cross into ViewModel or UI state.

The application-facing error taxonomy must distinguish at least:

| Error kind | Required caller behavior |
|---|---|
| Validation or domain rejection | Mark the exact field or invariant; do not commit or retry automatically. |
| Recoverable I/O | Preserve the draft/operation input; offer Retry or Reselect when the external handle is unusable. |
| Permission lost | Block the attempt, preserve safe state, and request the minimum corrective user action. |
| Storage exhausted | Block the attempt and explain required remediation; do not loop retries. |
| Corrupt or unsafe input | Reject it, keep current data unchanged, and never retry the same bytes automatically. |
| Incompatible backup | Reject before overwrite confirmation and keep the active vault unchanged. |
| Atomic transaction failed | Report one failed operation; expose no partial financial, relation, or owner success. |
| Recovery required | Route through the startup recovery gate; ordinary data access remains blocked. |
| Unexpected failure | Preserve the safest known state and record only a sanitized local diagnostic event. |

Typed errors contain safe categories and retry capabilities, not user content or identifying paths. UI copy is selected from the stable application error and current operation stage, never from `Throwable.message`.

## Retry rules

- Automatic retry is permitted only for an idempotent background operation with a bounded attempt count, backoff, an explicit persisted operation identity, and a test proving repeated execution has the same durable result.
- User-triggered saves, exports, and restores do not silently restart. Retry is an explicit event unless an architecture-owned recovery state machine requires idempotent startup continuation.
- Validation failures, corrupt data, incompatible backup formats, permission denial, and storage exhaustion are never automatically retried.
- A migration recovery pending state and a generation restore pending state are startup safety gates, not generic retry loops. Their coordinator must run before Room initialization and before the application shell, follow [data and storage](../architecture/data-and-storage.md), and expose normal use only after one complete vault is verified.
- Recovery transitions, sidecar isolation, activation validation, rollback, quarantine, and cleanup handlers are idempotent and fault-injection tested. No implementation may infer recovery success from file presence or an old UI progress state.

## Module and platform boundaries

- Feature modules never depend on one another. Cross-feature navigation uses typed route contracts owned by `:app`; shared values and interfaces live only in the justified core owner.
- UI cannot access a DAO, Room Entity, database handle, Android `Context`, provider exception, or physical attachment/database path. A UI-layer `Context` is allowed only for presentation-level platform integration and cannot perform persistence or bypass an application-facing interface.
- Repository APIs expose domain/application models, typed outcomes, and stable IDs. They do not expose Room entities, cursors, provider URIs as durable identities, or implementation paths.
- `:feature:timeline` cannot mutate source facts. Rebuildable projections never become write authorities.
- Add a module only when the four split thresholds in the [architecture overview](../architecture/overview.md) all hold. Empty modules and speculative interfaces are forbidden.

## Dependencies and build configuration

- Once Gradle exists, all external dependency and plugin versions are centralized in the version catalog. Module build files reference aliases rather than repeating versions.
- Before adding or updating a dependency, record its exact version, license, maintenance evidence, compatibility impact, and a viable replacement or removal path in the active spec or dependency review evidence.
- A dependency is rejected when its license is incompatible, maintenance status is unknown, it requires excessive permissions, it introduces undisclosed network behavior, or no practical removal path exists for a critical capability.
- Room patch/minor updates follow compatibility, migration, and recovery verification. A major persistence-generation or contract change requires an ADR.
- Secrets, tokens, passwords, user content, and sensitive or identifying paths never appear in source code, fixtures derived from real user data, build output, exceptions shown to UI, logs, screenshots, or commits.

## Documentation-first changes

Before code changes a stable product, experience, architecture, persistence, compatibility, recovery, security, or engineering decision, update its owning core document. Add or supersede an ADR when the decision changes architecture or durable compatibility, then update the active spec and plan before implementation. Code and tests cannot silently redefine a core decision.

Every completed change records the actual verification commands, environment, results, and remaining risk according to the [testing strategy](testing-strategy.md).

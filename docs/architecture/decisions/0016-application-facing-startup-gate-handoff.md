# ADR 0016: Application-facing startup gate handoff

## Status

Accepted

## Date

2026-08-29

The user explicitly accepted ADR 0016 on 2026-08-29 by replying “接受 ADR 0016”. The owning core documents were synchronized before M1 plan approval and activation.

## Context

The P5 storage implementation already owns one correct startup sequence:

```text
legacy classification/bootstrap
→ migration recovery
→ generation activation validation/rollback
→ active-generation Room open
```

That sequence runs before ordinary Room access and before the application shell. [ADR 0010](0010-generation-bootstrap-and-live-session-handoff.md) also requires one process-wide repository/session per canonical vault root and rejects a second public `VaultSession` ownership layer.

The current application-facing entry is `RecordRepository.open(context)`. It executes the complete sequence synchronously and returns only `RepositoryResult<RecordRepository>`. This is safe for storage callers, but it collapses three facts that M1 must distinguish before drawing UI:

1. startup classification/open is still checking;
2. a durable pending migration or activation state was detected and the architecture-owned resolver is progressing;
3. recovery is blocked or has a safe explicit Retry path.

Keeping only the blocking call would leave the Android starting window or an undifferentiated loading surface visible until recovery finishes. It cannot enter `RestoreRecoveryRoute` promptly, cannot meet the approved pending-startup budget, and cannot expose truthful retry capability. Allowing `:app` to read the activation/recovery record or invoke bootstrap/migration/activation steps directly would instead duplicate the recovery protocol, leak persistence details across the module boundary, and permit UI code to reorder or bypass correctness gates.

The handoff must preserve the existing interaction contract. `Phase.Checking` cannot transition directly to `Phase.Recovering`; the legal route is `Phase.Checking → Phase.Running → Phase.Recovering`. It must also remain compatible with the future App Lock ordering: recovery mechanics may continue safely before authentication, while `:app` alone decides whether the user sees a generic locked recovery surface or authenticated recovery detail.

## Options considered

- **Keep `RecordRepository.open()` as the only entry and show a generic launch surface until it returns.** Rejected because pending recovery is not observable in time to route correctly, Retry capability is lost, and the M1 pending-startup budget would measure an opaque blocking call rather than a safe recovery surface.
- **Move recovery-record classification and orchestration into `:app`.** Rejected because `:app` would need persistence paths, record formats, migration/generation stages, and mutation functions. That creates a second recovery implementation and violates `:core:data` ownership.
- **Expose activation records, stores, coordinators, or generation paths directly to `:app`.** Rejected because a nominally thin UI adapter could still inspect private state, reorder steps, mutate the pointer, or surface identifying paths/counts.
- **Replace `RecordRepository` with a new public `VaultSession`.** Rejected because ADR 0010 already selected one repository identity with an internal binding handoff; M1 needs startup observability, not another ownership abstraction.
- **Add one narrow `RecordStartup` coordinator over the existing engine and retain the blocking repository entry as a compatibility wrapper (chosen).** This exposes only safe progress and terminal outcomes while one `:core:data` engine continues to own every persistence/recovery step.

## Decision

### One coordinator and one engine

`:core:data` adds one application-facing `RecordStartup` coordinator. `RecordStartup.create(context)` captures `context.applicationContext` and binds to the same canonical vault-root/session machinery used by `RecordRepository`. It does not create a repository, Room handle, recovery-record store, or resolver of its own.

The existing P5 startup body is extracted into one internal `StartupEngine`. Both `RecordStartup` and the compatibility `RecordRepository.open(context)` entry delegate to that engine. No bootstrap, migration recovery, generation activation, orphan classification, Room-open, or error-mapping branch may remain duplicated in `RecordRepository.open()`.

The public application boundary is:

```kotlin
enum class StartupProgress { Checking, RecoveryDetected, Recovering }

enum class StartupFailure { StorageExhausted, RecoveryRequired, Unexpected }

fun interface StartupObserver {
    fun onProgress(progress: StartupProgress)
}

sealed interface StartupResult {
    data class Ready(val repository: RecordRepository) : StartupResult
    data class Blocked(val failure: StartupFailure, val canRetry: Boolean) : StartupResult
}

class RecordStartup private constructor(/* internal engine */) {
    suspend fun start(observer: StartupObserver): StartupResult
    suspend fun retry(observer: StartupObserver): StartupResult

    companion object {
        fun create(context: android.content.Context): RecordStartup
    }
}
```

These are application-facing types, not persistence facts. They expose no recovery-record version/state, stage enum, generation ID, record count, path, filename, URI, DAO, Room type, raw exception, activation mutation, or diagnostic payload.

### Progress ordering and UI mapping

Every new attempt emits `StartupProgress.Checking` first.

- A healthy/fresh path emits `Checking`, completes the unchanged startup sequence, then returns `Ready` only after the active generation's writable Room database is open and the complete repository binding exists.
- A durable pending migration or activation condition emits `Checking → RecoveryDetected → Recovering`. `RecoveryDetected` occurs only after classification proves pending work and before the existing resolver is dispatched. `Recovering` occurs only while that resolver owns progress.
- A safe terminal problem returns `Blocked`. `canRetry=true` means the same coordinator may explicitly re-enter the idempotent architecture-owned path; `false` means no storage operation is started until an external condition changes.

`:app` maps these outcomes without inventing phase aliases:

| Core outcome | Interaction phase | Shell/recovery visibility |
|---|---|---|
| `Checking` | `Phase.Checking` | Data-free launch surface; no shell |
| `RecoveryDetected` | `Phase.Running` | Data-free launch surface; no shell |
| `Recovering` | `Phase.Recovering` | Recovery route selected by the authenticated/locked app boundary; no shell |
| `Ready` | transient `Phase.Completed` | Shell may be constructed |
| retryable `Blocked` | `Phase.RecoverableFailure` | Safe Retry only |
| non-retryable `Blocked` | `Phase.BlockingFailure` | Remediation/status only |

No shell, prior activity frame, top-level route, global command, user data, count, path, detailed/terminal recovery status, or diagnostic action becomes visible merely because a progress event was emitted. Progress is never evidence that recovery completed.

### Attempt, concurrency, Retry, and cancellation

One `RecordStartup` instance owns at most one in-flight attempt:

- concurrent `start()` callers coalesce onto and receive the same in-flight terminal result rather than starting or queueing another open;
- a `Ready` result is cached and returned without reopening Room or creating a second repository;
- a retryable `Blocked` result permits one explicit `retry()` attempt through the same engine;
- `retry()` after `Ready` returns the cached `Ready`;
- `retry()` after non-retryable `Blocked` returns the same result without touching storage;
- the compatibility `RecordRepository.open()` still reaches the process-wide session registry, so it cannot create a second live repository for the same root.

Caller cancellation before a durable non-cancellable recovery boundary follows the existing typed/cancellation rules. Once P5 has made a pending activation or recovery transition durable, the existing resolver remains non-cancellable until it reaches verified healthy or verified rollback/blocked state. The new observer cannot widen cancellation.

### Threading and ownership

The coordinator API is suspend-based. `:app` owns the foreground/application coroutine lifetime and invokes it on an injected I/O dispatcher; the storage engine remains `:core:data` owned. “Synchronous startup gate” continues to mean synchronous with respect to correctness—migration/activation resolution completes before Room-backed normal use and the shell—not blocking Android's main thread and not hiding all safe progress.

WorkManager, another worker, a Service, and a second process remain forbidden owners of startup correctness. The app may hold immutable progress in an application-scoped `StateFlow`, but it cannot retain or mutate internal recovery state.

### Stable failures and privacy

Raw failures map inside `:core:data`:

- storage exhaustion → `StartupFailure.StorageExhausted`, non-retryable until remediation;
- unresolved or safely retryable recovery → `StartupFailure.RecoveryRequired`, with `canRetry` determined by the engine's verified state;
- any other safe terminal failure → `StartupFailure.Unexpected`, with retry capability explicitly determined rather than assumed.

The observer and terminal result contain no user content or identifying path. Future App Lock remains an `:app` access-control decision: unauthenticated UI receives only the existing generic data-free recovery surface, even though the same coordinator may continue safe technical recovery. Diagnostics and detailed terminal outcomes remain authentication-gated.

### Persistence and compatibility impact

This decision changes no Room Entity, DAO, `databaseVersion`, schema JSON, migration, backup `formatVersion`, recovery-record version/fields, generation directory, pointer state, attachment path, logical dataset, or fault boundary. It does not authorize data migration or destructive fallback.

Existing callers of `RecordRepository.open(context)` remain source-compatible. The wrapper may continue to block its caller, but it delegates to the same engine and maps terminal outcomes into the existing `RepositoryResult` categories. M1 application code uses `RecordStartup`; storage tests and legacy internal callers can retain the compatibility entry while migration is staged.

Removing the new handoff would remove only public coordinator/progress code and the M1 UI integration; no persisted data conversion or rollback is required. Doing so would restore the opaque blocking startup UX and would therefore require a later product/spec decision, not a data rollback.

## Consequences

- M1 can draw a truthful, data-free recovery surface inside its pending TTID budget without weakening the before-Room/before-shell gate.
- `:core:data` remains the sole owner of recovery records, ordering, mutation, validation, and repository construction.
- `:app` gains progress and retry capability but no persistence representation or recovery mutation authority.
- The public `:core:data` API and allowlist expand by the coordinator/result types; negative API fixtures must continue hiding the engine, stores, records, paths, and mutation methods.
- The startup implementation becomes suspend-based for M1 while retaining a blocking compatibility wrapper. This adds coordination/state tests but no persistence compatibility burden.
- The legal `Checking → Running → Recovering` UI path is explicit and testable; no core interaction transition is widened.
- Future App Lock can gate detailed surfaces independently of the technical coordinator because every pre-auth progress value is data-free.

## Verification

- Pure tests prove exact progress order for healthy, fresh, pending migration, pending activation, rollback, blocked, and retry paths.
- Concurrency tests prove concurrent starts share one attempt/result, Ready is cached, retryability is enforced, and one canonical root yields one live repository identity.
- Device tests enter every existing migration-recovery stage and pending activation through `RecordStartup`; no Room handle or shell-ready result appears before safe resolution.
- Compatibility tests prove `RecordRepository.open()` and `RecordStartup` reach the same engine and public repository/session identity.
- API-surface and negative-consumer tests prove `StartupEngine`, activation/migration records, stores, paths, stages, and mutation functions remain internal.
- Cancellation/fault tests retain every P5 non-cancellable boundary and idempotent restart result.
- UI-state tests prove `Checking → Running → Recovering`, Ready-only shell construction, stable Blocked mapping, safe Retry, and no raw/private content.
- Startup Macrobenchmark proves healthy TTID/TTFD and pending recovery-route TTID against the approved M1 budgets; a fast shell that bypasses recovery fails.
- Schema JSON, database version, recovery-record bytes/shape, backup format, and generation layout remain unchanged.

## Core-document synchronization

The accepted decision synchronizes these owning facts before plan approval:

1. `architecture/overview.md`: distinguish synchronous correctness ordering from main-thread blocking; add the single `:core:data` coordinator → immutable app startup state → recovery/shell flow.
2. `architecture/data-and-storage.md`: add the application-facing startup handoff, exact progress/result privacy boundary, single-attempt/cached-result/Retry rules, compatibility wrapper, and explicit no-persistence change.
3. `experience/interaction-rules.md`: record the named legal `Phase.Checking → Phase.Running → Phase.Recovering` route-entry sequence without widening the phase table.
4. `engineering/conventions.md`: clarify that startup correctness stays foreground-owned and before Room/shell while suspend work runs off main and WorkManager remains forbidden.
5. `engineering/security-and-privacy.md`: state that application-facing progress/failures are data-free and do not bypass future App Lock disclosure rules.
6. Synchronize the M1 spec/plan metadata and obtain explicit plan approval; only then may M1 become `in-progress`.

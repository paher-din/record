# Record 2.0 build root

Follow [repository rules](../AGENTS.md), [R2 Policy](../docs/engineering/record2-engineering-contract.md), the approved [V1 Spec](../docs/specs/record2/v1/spec.md) and the task-specific authorization in [V1 Plan](../docs/specs/record2/v1/plan.md).

Use this root's wrapper with explicit `-p`; only `:app` belongs to this build. R1 projects, sources, resources, fixtures and outputs are outside its inputs. For Schema work and isolated database/device tests, read Plan §20 for exact files, AVD, package and synthetic database boundaries. Ordinary app startup, model calls, session history, product UI and real credential configuration need their own authorized tasks.

For B1 finance services, seed initialization, native drafts and their fault/process tests, read Plan §21. Keep the existing Schema v1; UI and Tools use the records service contract, while Room implementation and transaction ownership stay in storage.

For B2 Note persistence, autosave state, soft deletion/recovery and fault tests, read Plan §22, including shared-device coordination. Keep Schema v1 and the owner's F0/F1 UI boundary; never treat a changed installed APK as valid evidence for the current source.

For E0 authorization, provider credentials and the request-start permit seam, read Plan §23. The Room `ai_control` row and Keystore vault are not a settings UI; callers must obtain a permit and revalidate at the actual request-start boundary, while E2 owns revocation ordering/cancellation and late-result handling. The implemented receiver catalog and credential code do not authorize a real key or network call, and a permit is not bound to `dataGeneration`.

For E1 authoritative session history, the run seam and the Koog context adapter, read Plan §26. `RoomAgentHistoryService` owns the start/complete/fail transactions and the process-ownership gate; `AgentRunCoordinator` plus `RoomChatHistoryProvider` are the only Koog-facing seam. The provider's load carries the system message and trims the window itself (Koog 1.3.0 replaces the whole prompt with non-empty loaded history), and its store is a deliberate no-op — never reintroduce a cache that survives a crash before the final reply commits. Callers must run `beginProcessOwnership` once per process before any run entry; cancel/revocation call-site ordering and late-write interception stay with E2.

# Record 2.0 build root

Follow [repository rules](../AGENTS.md), [R2 Policy](../docs/engineering/record2-engineering-contract.md), the approved [V1 Spec](../docs/specs/record2/v1/spec.md) and the task-specific authorization in [V1 Plan](../docs/specs/record2/v1/plan.md).

Use this root's wrapper with explicit `-p`; only `:app` belongs to this build. R1 projects, sources, resources, fixtures and outputs are outside its inputs. For Schema work and isolated database/device tests, read Plan §20 for exact files, AVD, package and synthetic database boundaries. Ordinary app startup, model calls, session history, product UI and real credential configuration need their own authorized tasks.

For B1 finance services, seed initialization, native drafts and their fault/process tests, read Plan §21. Keep the existing Schema v1; UI and Tools use the records service contract, while Room implementation and transaction ownership stay in storage.

For E0 authorization, provider credentials and the send gate, read Plan §23. The Room `ai_control` row and Keystore vault are not a settings UI; callers must obtain a permit and revalidate it before every provider egress and late-result write. The implemented receiver catalog and credential code do not authorize a real key or network call.

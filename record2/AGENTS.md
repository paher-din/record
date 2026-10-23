# Record 2.0 build root

Follow [repository rules](../AGENTS.md), [R2 Policy](../docs/engineering/record2-engineering-contract.md), the approved [V1 Spec](../docs/specs/record2/v1/spec.md) and the task-specific authorization in [V1 Plan](../docs/specs/record2/v1/plan.md).

Use this root's wrapper with explicit `-p`; only `:app` belongs to this build. R1 projects, sources, resources, fixtures and outputs are outside its inputs. For Schema work and isolated database/device tests, read Plan §20 for exact files, AVD, package and synthetic database boundaries. Ordinary app startup, credentials, model calls and product UI need their own authorized tasks.

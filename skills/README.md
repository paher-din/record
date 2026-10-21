# Agent Skills

Vendored agent skills shared by Qoder, Codex, and other harnesses. 61 skills total, stored flat so Qoder can discover them.

## Matt Pocock skills

- Source: https://github.com/mattpocock/skills
- Commit: `5beb35b040e3de97e54fa9e386d7e42d15c295c2`
- Installed: 2026-10-21
- License: MIT (see [LICENSE](LICENSE))
- 27 skills matching the upstream `mattpocock-skills` plugin manifest (20 engineering + 7 productivity). `misc`, `in-progress`, and `deprecated` are intentionally not included.

## Impeccable

- Source: https://github.com/pbakaus/impeccable
- Commit: `dad8dbb3aac21ad1fa236c110315ef4abece23e8`
- Version: 4.7.0
- Installed: 2026-10-21
- License: Apache-2.0 (see [impeccable/LICENSE](impeccable/LICENSE))
- One skill with 24 commands (`/impeccable <command>`), plus `reference/` and `scripts/`. Copied from the upstream `.agents/skills/impeccable` build.
- Skill-only install: the optional automatic design hooks are not installed. The launcher downloads the engine binary to `~/.impeccable/bin/` on first use.

## Android skills

- Source: https://github.com/android/skills
- Commit: `95ea931a1ad8f9b2be132ae0cfb2aad964d71118`
- Plugin version: 1.0.16
- Installed: 2026-10-21
- License: Apache-2.0 (see [LICENSE-ANDROID-SKILLS.txt](LICENSE-ANDROID-SKILLS.txt))
- 33 skills matching the upstream `android-skills` plugin manifest, copied from their original category paths into the flat layout.

## Layout

Skills are flat (`skills/<name>/SKILL.md`) because Qoder only scans one level under `.qoder/skills/`. Agent-specific entry points are directory junctions to this folder:

| Agent | Entry |
| --- | --- |
| Qoder | `.qoder/skills` |
| Codex / generic | `.agents/skills` |

Junctions are local-only and ignored by Git. Recreate them after a fresh clone:

```powershell
New-Item -ItemType Directory -Path .qoder, .agents -Force | Out-Null
New-Item -ItemType Junction -Path .qoder\skills -Target (Resolve-Path .\skills).Path
New-Item -ItemType Junction -Path .agents\skills -Target (Resolve-Path .\skills).Path
```

## Skill index

Engineering: `ask-matt`, `code-review`, `codebase-design`, `diagnosing-bugs`, `domain-modeling`, `grill-with-docs`, `implement`, `implement-spec`, `improve-codebase-architecture`, `pr`, `prototype`, `research`, `retro`, `setup-matt-pocock-skills`, `tdd`, `to-spec`, `to-tickets`, `triage`, `wayfinder`, `wizard`

Productivity: `grill-me`, `grilling`, `handoff`, `teach`, `to-questionnaire`, `wait-what`, `writing-for-agents`

Design: `impeccable`

Android: `adaptive`, `agp-9-upgrade`, `android-cli`, `android-for-cars-parked-apps`, `android-intent-security`, `android-permissions-security`, `android-profiler`, `anr-audit-and-fix`, `appfunctions`, `camerax`, `display-glasses-with-jetpack-compose-glimmer`, `edge-to-edge`, `engage-sdk-integration`, `glance-canonical-layouts`, `jetpack-compose-performance`, `leanback-to-compose-tv-migration`, `litert-lm`, `litert-runtime`, `media3-cast-integration`, `migrate-xml-views-to-jetpack-compose`, `ml-kit-genai-prompt-api`, `navigation-3`, `navigation-event`, `play-billing-library-version-upgrade`, `play-policy-insights`, `projected-hardware`, `r8-analyzer`, `restore-credentials`, `styles`, `testing-setup`, `verified-email`, `wear-compose-m3`, `wear-widgets`

## Updating

Re-copy the upstream skill directories at a newer commit and update the commit hashes above. Do not edit skills in place, so upgrades stay diffable.

- Matt Pocock: re-copy the skill directories under `skills/engineering` and `skills/productivity`.
- Impeccable: re-copy `.agents/skills/impeccable` and its `LICENSE`.
- Android: re-copy each directory listed in `.claude-plugin/plugin.json` and `LICENSE.txt`.

Usage follows the repository contract in `AGENTS.md`. Neither `setup-matt-pocock-skills` nor `impeccable init` has been run for this repo.

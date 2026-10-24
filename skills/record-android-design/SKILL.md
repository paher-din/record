---
name: record-android-design
description: Plan, critique, or implement Record 2.0 native Android visual systems, timeline, finance and note editors, Agent proposal interfaces, and motion. Use for Record UI/UX tasks; engineering and product approval remain with the repository contracts.
---

# Record Android design

Read `docs/index.md`, the R2 Engineering Policy, the relevant V1 Spec/Plan section, and `record2/PRODUCT.md`. Read the current approved design group before implementation. Design proposals are not approval; R1 screens are not R2 implementation evidence.

Current review package: `docs/design/record2-f0-c3-review.md`, with exact values in `docs/design/f0-c3/design-tokens.json`. Its direct-execution product direction requires the owning contract changes listed in `record2-f0-c3-contract-delta.md`; never implement it by hiding confirmation controls or fabricating a native confirmation receipt.

## Choose the specialist

- Visual concepts, shape contrast, expressive typography and motion: load `m3-expressive`, then only its relevant `references/` files. It is the main creative reference, not an optional polish step.
- Material role mapping and component anatomy: load `material-3` (this installation may display as `material-3:material-3`). Verify disputed details against official Android/Material sources and the pinned library API.
- Native navigation, input, semantics and adaptive basics: load `mobile-android-design`; use existing `adaptive` / `edge-to-edge` for the relevant device/inset work.
- Compose state, effects, lifecycle, lazy layouts and code review: load `compose-agent` (may display as `compose-agent:compose-agent`). Existing `jetpack-compose-performance`, `android-profiler` and `testing-setup` support targeted validation.

The four new specialists are installed under the user's `~/.codex/skills/`; this project router is local. See `docs/design/record2-uiux-skill-integration-20261024.md` for pinned sources, validation and known defects. If a specialist is missing on another machine, report it and use the documented official source; do not silently claim it is installed.

## Resolve conflicts

User instructions and frozen product contracts govern scope. Use official documentation and resolved AndroidX source for API truth. Skill examples are references, never automatic permission to change dependencies, navigation architecture or persistence.

Do not inherit web/RN/Flutter code, CSS units, blanket dynamic-color defaults, mandatory Navigation 3/Paging migrations, fixed card recipes or generic anti-animation bans. Custom shapes and interruptible springs are welcome when they improve Record's focal interactions. Text and color effects must not overshoot.

Known corrections: M3 surface roles are tone-based, not a hand-added elevation overlay; Strong Skipping compares unstable inputs by identity and stable inputs by equality; Kotlin 2.x uses its matching Compose compiler plugin. A fresh Material role pair still needs measured contrast. Never treat a future Expressive release as a currently available API.

## Produce reviewable decisions

For an open redesign, explore materially different compositions, nominate a direction, and explain its two signature moments. Label platform requirements, project design choices and measurements separately. Show the exact content hierarchy, state transitions, reduced-motion behavior and fallback for each proposed effect. Avoid a universal card layout across timeline, editing and Agent surfaces.

Keep Amount input, Note autosave, Proposal revisions and durable Applied evidence authoritative. Never make an animation complete a save, invent a tool step, display model reasoning, or show fake execution percentages. Expensive drawing pauses when hidden or idle. Preserve input/focus/scroll through resize and process boundaries according to the existing services.

Final visual verification uses Android emulator/device captures with build identity, both themes, large fonts, IME, system Back and accessibility. Browser concept artifacts only support design discussion. Report measured performance separately from targets and estimates.

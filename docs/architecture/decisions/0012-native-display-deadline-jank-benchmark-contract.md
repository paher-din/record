# ADR 0012: Native-display deadline-jank benchmark contract

## Status

Accepted

## Date

2026-08-16

The user explicitly accepted the native-display, deadline-aware P6 measurement design on 2026-08-16 after the Task 5 harness could no longer make the reference device honor the previously approved forced-60-Hz setting.

## Context

P6 Revision 1 treated a verified 60.00 Hz mode as part of the jank metric definition. Task 1 proved that the Xiaomi 14 Pro could temporarily enter that mode, but also recorded transient 60/120 switching. During Task 5, writes to `peak_refresh_rate=60.0` and `min_refresh_rate=60.0` no longer made the Activity-visible mode converge to 60 Hz; the device remained at its native 120 Hz setting. Continuing would test an OEM settings override rather than the P6 product question: whether the frozen Timeline workload is correct and usable on the reference device.

AndroidX defines `FrameTimingMetric.frameOverrunMs` as the amount by which a frame misses its system deadline. Positive values indicate a dropped frame. Its API guidance prefers this metric because it accounts for higher and variable frame rates more naturally. It is therefore incorrect to redefine `frameOverrunMs > 0` as a fixed `frameDurationCpuMs > 16.67` comparison or to require 60 Hz before the signal becomes meaningful.

Evidence and references:

- P6 verification records the Task 1 forced-mode smoke result, its observed 60/120 transition, the restored native settings, and the Task 5 failure to re-establish the old binding.
- [`FrameTimingMetric` API reference](https://developer.android.com/reference/androidx/benchmark/macro/FrameTimingMetric) defines `frameOverrunMs` and recommends it for modern higher/variable-framerate devices.
- [Macrobenchmark metric guidance](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics) distinguishes deadline overrun from CPU frame duration.

## Options considered

- **Keep the recorded native user display settings and use per-frame deadline overrun (chosen).** This measures the actual reference-device experience, removes an unreliable OEM override, and retains a direct visible-jank gate.
- Force both minimum and peak refresh to 120 Hz. Rejected because it creates a second artificial display configuration, increases thermal load, and is unnecessary for a metric that already follows system deadlines.
- Continue forcing 60 Hz through other shell or vendor-specific controls. Rejected because P6 does not need a 60 Hz device mode and non-rooted OEM control is not the product risk being tested.
- Remove the jank gate and retain only `frameDurationCpuMs`. Rejected because CPU work duration alone does not prove that displayed frames met their deadlines.

## Decision

The P6 reference display binding is the Xiaomi 14 Pro's recorded native user configuration: `peak_refresh_rate=120` and `min_refresh_rate=null`. The benchmark harness must not write either setting. It verifies the exact settings before and after the suite and fails closed if they change.

Every measured iteration records the Activity-visible refresh observation and retains the Perfetto-derived frame samples. Active refresh may vary under the device's display policy; variation alone does not invalidate an iteration while the frozen user settings remain unchanged and AndroidX produces valid deadline data.

Deadline jank is exactly:

`count(frameOverrunMs > 0) / count(all frameOverrunMs samples)`

over the measured scroll frames, with the existing absolute budget of at most 2%. `frameDurationCpuMs` remains a separate CPU-work distribution with its existing pooled p95 budget of 16.67 ms; it is not used to reconstruct jank. Query-to-first-correct-draw, anonymous RSS, correctness, stability, workload, thermal, and run-order contracts remain unchanged.

A comparable M5 or later run must retain the same device, system build, user display settings, AndroidX/harness version, fixture, workload, and formulas. A change to those bindings requires an explicitly approved new baseline. Differences in recorded active-refresh observations under the same frozen settings are evidence and diagnostic context, not by themselves a new baseline.

## Consequences

- Task 1's forced-60-Hz smoke remains historical evidence but no longer satisfies the revised reference binding.
- P6 remains `in-progress`, but Task 5 and later execution pause until the revised spec and plan are explicitly reapproved.
- Task 5 must replace forced-60 positive/negative controls with native-setting preservation, settings-drift failure, and refresh-observation capture controls.
- Task 6 must stop mutating display settings and must validate the native settings before and after the suite while retaining per-iteration refresh observations.
- The P6 result, frozen baseline, and M5 consequence move from the previously reserved ADR 0012 to ADR 0013.

## Verification

- A device self-check passes with `peak_refresh_rate=120` and `min_refresh_rate=null` without writing either setting.
- Negative controls prove that changed/missing user display settings and missing refresh observations fail closed.
- A variable Activity-visible refresh observation does not fail solely because it is not 120.00 Hz.
- The host validator recomputes the positive-`frameOverrunMs` fraction from all retained frame samples and matches the benchmark summary.
- Source inspection and before/after device evidence prove the binding harness does not execute `settings put` or `settings delete` for display refresh.

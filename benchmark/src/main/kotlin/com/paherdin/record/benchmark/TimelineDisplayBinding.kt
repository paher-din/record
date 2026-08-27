package com.paherdin.record.benchmark

/**
 * Tech PoC P6 Task 5 — focused read-only native display binding shared by the
 * device self-checks and Task 6's binding runs.
 *
 * The harness reads the native user display settings (`peak_refresh_rate`,
 * `min_refresh_rate`) but NEVER writes or deletes them (frozen ADR 0012
 * contract; Step 7 proves this via source scan). `capture` may execute only
 * the two `settings get system ...` commands below. Activity-visible refresh
 * may vary between two positive finite observations without invalidating a
 * run — there is deliberately no fixed-120 active-mode gate.
 */
internal data class TimelineDisplaySettings(val peak: String, val min: String)

internal object TimelineDisplayBinding {
    const val EXPECTED_PEAK = "120"
    const val EXPECTED_MIN = "null"

    /** Read-only capture; may execute ONLY the two settings-get commands. */
    fun capture(shell: (String) -> String): TimelineDisplaySettings = TimelineDisplaySettings(
        peak = shell("settings get system peak_refresh_rate").trim(),
        min = shell("settings get system min_refresh_rate").trim(),
    )

    /** Requires the exact native strings — any other peak/min value fails closed. */
    fun assertNative(settings: TimelineDisplaySettings) {
        check(settings.peak == EXPECTED_PEAK) {
            "native peak_refresh_rate '${settings.peak}' != expected '$EXPECTED_PEAK'"
        }
        check(settings.min == EXPECTED_MIN) {
            "native min_refresh_rate '${settings.min}' != expected '$EXPECTED_MIN'"
        }
    }

    /**
     * Parses an Activity-visible refresh observation: positive and finite only.
     * Accepts any positive rate (60.0, 90.0, 120.0, ...); rejects blank, 0,
     * negative, NaN, and infinite values. Deliberately does not require 120.
     */
    fun requirePositiveRefresh(raw: String): Double {
        val value = raw.trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("refresh observation '$raw' is not a number")
        check(value.isFinite() && value > 0.0) {
            "refresh observation '$raw' must be positive and finite, was $value"
        }
        return value
    }
}

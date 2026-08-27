package com.paherdin.record.benchmark

import org.json.JSONObject

/**
 * Tech PoC P6 Task 6 — cross-process sentinel channel (self-instrumenting
 * repair, see the `benchmark/build.gradle.kts` comment).
 *
 * The plan's Task 1 required self-instrumenting mode for `:benchmark` (the
 * benchmark test APK must not load into the target application process, or
 * Macrobenchmark cannot kill/compile/launch the target); the flag was missing
 * and is enabled as part of Task 6. With it, the instrumentation's
 * `targetContext` is the test package itself, so target-context
 * SharedPreferences can no longer read the target app's sentinels. The target
 * app therefore publishes its sentinel map to
 * `<externalMediaDir>/p6_sentinels.json` (app-writable, shell-readable) in
 * addition to its prefs; every benchmark-module test reads it through
 * [TimelineSentinelReader] via `cat` — `UiAutomation.executeShellCommand`
 * runs with shell privileges, so it can read any app's media directory.
 *
 * `getString(key, default)` mirrors the `SharedPreferences` call shape so the
 * Task 3/4/5 test call sites change mechanically.
 */
internal const val TARGET_APP_PACKAGE = "com.paherdin.record"

internal object TimelineSentinelBridge {

    /** Mirror filename the target Activity publishes (SENTINEL_FILE_NAME). */
    const val FILE_NAME = "p6_sentinels.json"

    /** Shell-readable app-specific media path for the target package. */
    fun filePath(targetPackage: String = TARGET_APP_PACKAGE): String =
        "/sdcard/Android/media/$targetPackage/$FILE_NAME"

    /**
     * Reads the sentinel JSON via `cat`. An absent/unparseable file yields an
     * empty map (the caller polls until the awaited key appears, so transient
     * absence during a fresh launch is normal).
     */
    fun readFrom(shell: (String) -> String, path: String = filePath()): Map<String, String> {
        val raw = shell("cat $path 2>/dev/null").trim()
        if (raw.isEmpty()) return emptyMap()
        return try {
            val root = JSONObject(raw)
            val out = HashMap<String, String>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = root.getString(key)
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

/**
 * Cached reader over the app-published sentinel file. The 150 ms cache bounds
 * shell round-trips from the tight polling loops while staying well below the
 * wait-loop granularity.
 */
internal class TimelineSentinelReader(
    private val shell: (String) -> String,
    targetPackage: String = TARGET_APP_PACKAGE,
) {
    private val path = TimelineSentinelBridge.filePath(targetPackage)
    private var cache: Map<String, String> = emptyMap()
    private var lastReadNanos = 0L

    /** `getString(key, default)` — same shape as SharedPreferences call sites. */
    fun getString(key: String, default: String? = null): String? = readAll()[key] ?: default

    fun readAll(): Map<String, String> {
        val now = System.nanoTime()
        if (now - lastReadNanos < CACHE_TTL_NANOS) return cache
        lastReadNanos = now
        cache = TimelineSentinelBridge.readFrom(shell, path)
        return cache
    }

    private companion object {
        const val CACHE_TTL_NANOS = 150_000_000L // 150 ms
    }
}

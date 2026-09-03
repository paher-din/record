package com.paherdin.record.benchmark

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import org.json.JSONObject

/**
 * M1-T09 Step 3 — cross-process sentinel channel for the startup fixture
 * Activity (the P6 pattern applied to the M1 startup suite).
 *
 * The out-of-process `:benchmark` instrumentation does not share the target
 * app's uid, so SharedPreferences are unreadable to it. The recorder writes
 * every sentinel to the app's prefs AND to the app-writable, shell-readable
 * mirror file `<externalMediaDir>/m1_startup_sentinels.json`; the harness
 * reads that file through `cat` (UiAutomation runs with shell privileges).
 * The launch-time publication is synchronous so a fresh run identity is
 * deterministically visible by the time `am start -W` returns.
 *
 * `pm clear` (the harness's per-iteration fixture reset) wipes the external
 * media directory too, so the fixture Activity republishes the complete
 * sentinel set on every setup launch.
 */
internal class M1StartupSentinelRecorder(private val context: Context) {

    private val mirror = LinkedHashMap<String, String>()

    /** The app-private SharedPreferences sentinel store (diagnostic mirror). */
    internal fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Stamps [mode] and a fresh per-launch run id, clears [keysToClear], and
     * publishes synchronously. The run id is the deterministic freshness proof
     * the harness polls for after every fixture setup launch.
     */
    internal fun startFreshRun(mode: String, keysToClear: Collection<String>): String {
        val runId = java.util.UUID.randomUUID().toString()
        val edit = prefs().edit()
        mirror[KEY_MODE] = mode
        mirror[KEY_RUN_ID] = runId
        edit.putString(KEY_MODE, mode)
        edit.putString(KEY_RUN_ID, runId)
        for (key in keysToClear) {
            mirror.remove(key)
            edit.remove(key)
        }
        edit.commit()
        publishSynchronously()
        return runId
    }

    /** Writes one sentinel to prefs AND the shell-readable mirror file. */
    internal fun write(key: String, value: String) {
        mirror[key] = value
        prefs().edit().putString(key, value).commit()
        publishSynchronously()
    }

    private fun publishSynchronously() {
        val dir = context.getExternalMediaDirs()?.firstOrNull() ?: return
        try {
            val file = java.io.File(dir, FILE_NAME)
            file.parentFile?.mkdirs()
            val root = JSONObject()
            mirror.forEach { (key, value) -> root.put(key, value) }
            file.writeText(root.toString())
        } catch (t: Throwable) {
            // The prefs commit already succeeded; a mirror-publish failure
            // surfaces to the harness as a missing sentinel, never as a crash
            // inside the setup surface.
            android.util.Log.w(TAG, "m1 sentinel publish failed: ${t.message}")
        }
    }

    internal companion object {
        private const val TAG = "M1Sentinels"
        const val PREFS_NAME = "m1_startup_sentinels"
        const val FILE_NAME = "m1_startup_sentinels.json"

        const val KEY_MODE = "mode"
        const val KEY_RUN_ID = "runId"
        const val KEY_ERROR = "error"
        const val KEY_SETUP_DONE = "setupDone"

        /** `m1-setup` — the fixture Activity's setup mode stamp. */
        const val MODE_M1_SETUP = "m1-setup"
    }
}
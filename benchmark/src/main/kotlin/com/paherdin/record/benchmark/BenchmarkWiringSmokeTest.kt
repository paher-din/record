package com.paherdin.record.benchmark

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tech PoC P6 Task 1/5 — benchmark wiring preflight, run out of process from
 * the :benchmark test-tool module against :app's non-debuggable benchmark
 * variant. Asserts:
 *  - the harness runs in its own package, out of the target app's process
 *    (self-instrumenting module, Task 6 repair);
 *  - the target launches;
 *  - the target is non-debuggable and profileable-from-shell;
 *  - the instrumentation default display refreshRate is a positive finite
 *    observation (no fixed-Hz equality gate — Revision 2 contract).
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkWiringSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val targetPackage = TARGET_APP_PACKAGE
    private val testPackage = instrumentation.context.packageName

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command)
            .use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
            }

    @Test
    fun wiringSmokeCheck() {
        // 1. Out-of-process: the test APK runs in its own package, never loaded
        // into the target application process (self-instrumenting requirement of
        // benchmark-macro 1.4.1 — otherwise Macrobenchmark cannot kill/compile/
        // launch the target).
        assertNotEquals("benchmark harness must run out of process", targetPackage, testPackage)
        assertEquals(
            "self-instrumenting harness runs in the test package",
            "com.paherdin.record.benchmark.test", testPackage,
        )

        // 2. Launch the target app's production entry point via shell (MIUI
        // aborts background activity starts from the instrumented process).
        val launchOut = shell("am start -W -n $targetPackage/.MainActivity")
        assertTrue(
            "target app must launch: $launchOut",
            launchOut.contains("Status: ok") || launchOut.contains("LaunchState"),
        )

        // 3a. Target variant contract: non-debuggable.
        val appInfo = targetContext.packageManager.getApplicationInfo(targetPackage, 0)
        assertEquals(
            "target benchmark variant must be non-debuggable",
            0,
            appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE
        )

        // 3b. Profileable-from-shell: dumpsys package does not expose a profileable
        // field on this build, so verify the capability functionally — a shell
        // profile session can start on a non-debuggable process only when the
        // manifest declares android:profileable shell=true. Negative control
        // confirmed on-device: SecurityException "Process not debuggable, and not
        // profileable by shell".
        val pid = shell("pidof $targetPackage").trim()
        assertTrue("target must be running for the profileable check", pid.isNotEmpty())
        val profileFile = "/data/local/tmp/p6-smoke.prof"
        val startOut = shell("am profile start $pid $profileFile")
        assertTrue(
            "shell profile start must succeed (target profileable-from-shell): $startOut",
            !startOut.contains("Exception")
        )
        shell("am profile stop $pid")
        shell("rm -f $profileFile")

        // 4. Display observation: the instrumentation default display must
        // expose a positive finite refresh rate. No fixed-Hz equality gate —
        // the native settings may legally vary (Revision 2 contract).
        val display = (targetContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val observed = TimelineDisplayBinding.requirePositiveRefresh(display.refreshRate.toString())
        assertTrue("observed display refresh must be positive, was $observed", observed > 0.0)
    }
}

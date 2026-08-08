package com.paherdin.record.core.data.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tech PoC P4 (plan Task 1): proves the instrumented-test source set resolves
 * the same shared fixture classes from `testFixtures` that the JVM tests use —
 * one implementation, two consumers. Written before the fixture exists, so it
 * initially fails to compile on the unresolved symbol.
 */
@RunWith(AndroidJUnit4::class)
class TestFixtureVisibilityAndroidTest {

    @Test
    fun sharedFixtureMarkerIsResolvableFromAndroidTestSource() {
        assertEquals("p4-fixture", P4FixtureMarker.token)
    }
}

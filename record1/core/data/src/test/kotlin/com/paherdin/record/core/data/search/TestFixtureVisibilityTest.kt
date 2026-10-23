package com.paherdin.record.core.data.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tech PoC P4 (plan Task 1): proves the module's JVM unit-test source set can
 * resolve the shared search fixtures from the `testFixtures` source set. Written
 * before the fixture implementation exists, so it initially fails to compile on
 * the unresolved symbol and compiles only after [P4FixtureMarker] lands.
 */
class TestFixtureVisibilityTest {

    @Test
    fun sharedFixtureMarkerIsResolvableFromJvmTestSource() {
        assertEquals("p4-fixture", P4FixtureMarker.token)
    }
}

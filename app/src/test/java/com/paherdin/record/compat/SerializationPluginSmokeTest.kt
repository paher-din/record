package com.paherdin.record.compat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializationPluginSmokeTest {
    @Serializable
    private data class Probe(val id: String)

    @Test fun serializableRouteCompilerPluginWorksWithBuiltInKotlin() {
        val encoded = Json.encodeToString(Probe.serializer(), Probe("probe"))
        assertEquals(Probe("probe"), Json.decodeFromString(Probe.serializer(), encoded))
    }
}

package org.hellokittyvpn.android.vpn

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelEngineDistributionTest {
    @Test
    fun `direct distribution announces xray and relay profile engines`() {
        val registry = createTunnelEngineRegistry(
            xrayRuntime = XrayRuntime(Json),
            nativeLibraryDir = "/data/app/lib",
        )

        assertEquals(
            setOf(TunnelEngineKind.XRAY, TunnelEngineKind.LEVIK_RELAY),
            registry.supportedProfileEngines,
        )
        assertTrue(
            "Direct must use the real native relay adapter",
            registry.require(TunnelEngineKind.LEVIK_RELAY) is RelayTunnelEngineAdapter,
        )
        assertTrue(
            Class.forName("org.hellokittyvpn.android.vpn.RelayTunnelEngineAdapter") != null,
        )
    }
}

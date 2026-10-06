package org.hellokittyvpn.android.vpn

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TunnelEngineDistributionTest {
    @Test
    fun `play distribution announces only xray profile engine`() {
        val registry = createTunnelEngineRegistry(
            xrayRuntime = XrayRuntime(Json),
            nativeLibraryDir = "/data/app/lib",
        )

        assertEquals(
            setOf(TunnelEngineKind.XRAY),
            registry.supportedProfileEngines,
        )
        assertThrows(TunnelEngineUnavailableException::class.java) {
            registry.require(TunnelEngineKind.LEVIK_RELAY)
        }
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("org.hellokittyvpn.android.vpn.RelayTunnelEngineAdapter")
        }
    }
}

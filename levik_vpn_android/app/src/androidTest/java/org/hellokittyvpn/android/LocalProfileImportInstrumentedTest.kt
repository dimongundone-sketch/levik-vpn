package org.hellokittyvpn.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hellokittyvpn.android.core.security.SecureFileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual packaged native converter and encrypted Android storage. */
@RunWith(AndroidJUnit4::class)
class LocalProfileImportInstrumentedTest {
    @Test fun invalidImportKeepsPreviouslyValidatedConfiguration() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<HelloKittyVpnApplication>()
        val repository = app.container.repository
        val store = app.container.secureStore
        val original = store.get(SecureFileStore.TUNNEL_PROFILE)
        val selected = store.get(SecureFileStore.SELECTED_SERVER)
        try {
            val profile = repository.importProfile(
                "vless://f0749b88-37ce-498c-b5a8-e7a1acd908ac@vpn.example.invalid:443?security=tls&type=tcp#Test%20node",
            )
            assertEquals(1, profile.servers.size)
            assertTrue(profile.servers.single().name.contains("Test"))
            assertEquals(profile.profileId, repository.cachedTunnel()?.profileId)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.importProfile("https://example.invalid/remote-subscription") }
            }
            assertEquals(profile.profileId, repository.cachedTunnel()?.profileId)
            // Import only converts metadata. It must not start a VPN service or reach the endpoint.
            assertFalse(app.container.vpnController.state.value.state.name == "CONNECTED")
            repository.clearProfile()
            assertEquals(null, repository.cachedTunnel())
        } finally {
            if (original == null) store.remove(SecureFileStore.TUNNEL_PROFILE)
            else store.put(SecureFileStore.TUNNEL_PROFILE, original)
            if (selected == null) store.remove(SecureFileStore.SELECTED_SERVER)
            else store.put(SecureFileStore.SELECTED_SERVER, selected)
            original?.fill(0)
            selected?.fill(0)
            repository.cachedTunnel()
        }
    }
}

package org.hellokittyvpn.android.ui

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialLifetimeTest {
    private val now = Instant.parse("2026-10-06T12:00:00Z")

    @Test fun localProfileWithoutExpiryRemainsUsableOffline() {
        assertTrue(cachedProfileIsUsable(null, now))
    }

    @Test fun expiredAndMalformedCredentialsCannotBeConnectedFromCache() {
        assertFalse(cachedProfileIsUsable("2026-10-06T11:59:59Z", now))
        assertFalse(cachedProfileIsUsable(now.toString(), now))
        assertFalse(cachedProfileIsUsable("invalid", now))
        assertTrue(cachedProfileIsUsable("2026-10-06T12:00:01Z", now))
    }
}

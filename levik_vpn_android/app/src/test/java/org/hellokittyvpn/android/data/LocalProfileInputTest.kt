package org.hellokittyvpn.android.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalProfileInputTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC)
    private val input = LocalProfileInput(Json { ignoreUnknownKeys = false }, clock)

    @Test fun acceptsMultipleLocalLinksWithoutFetchingSubscriptionAddresses() {
        val links = "vless://test@example.org:443\n\ntrojan://test@example.org:443"
        val profile = input.parse(links)
        assertEquals(links, profile.source?.content)
        assertEquals("text/plain", profile.source?.mediaType)
        assertEquals(clock.instant().toString(), profile.issuedAt)
        assertEquals("local-profile", profile.subscriptionId)
    }

    @Test fun rejectsWebsiteAndExecutableInputsEvenMixedWithAValidLink() {
        listOf("https://example.org/subscription", "file:///etc/passwd", "$(touch /tmp/unsafe)",
            "vless://test@example.org:443\nhttps://example.org/profile", " \n ").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { input.parse(value) }
        }
    }

    @Test fun limitsUtf8BytesRatherThanOnlyCharacterCount() {
        assertThrows(IllegalArgumentException::class.java) {
            input.parse("vless://" + "я".repeat(524_288))
        }
    }

    @Test fun rawXrayJsonBecomesSourceAndNeverBecomesAProfileEnvelope() {
        val source = """{"outbounds":[{"protocol":"vless"}]}"""
        assertEquals(source, input.parse(source).source?.content)
        assertEquals("application/json", input.parse(source).source?.mediaType)
    }

    @Test fun invalidVersionAndFutureIssueTimeAreRejected() {
        val envelope = """{"version":1,"profileId":"test-profile","subscriptionId":"local-profile","issuedAt":"2026-10-06T12:00:00Z","source":{"mediaType":"text/plain","content":"vless://test@example.org:443"}}"""
        assertEquals("test-profile", input.parse(envelope).profileId)
        assertThrows(IllegalArgumentException::class.java) { input.parse(envelope.replace("\"version\":1", "\"version\":99")) }
        assertThrows(IllegalArgumentException::class.java) { input.parse(envelope.replace("12:00:00Z", "13:00:00Z")) }
    }

    @Test fun relayImportCannotBypassBackendProvisioning() {
        val relay = """{"version":2,"engine":"levik-relay","profileId":"test-profile","subscriptionId":"local-profile","issuedAt":"2026-10-06T12:00:00Z"}"""
        assertThrows(IllegalArgumentException::class.java) { input.parse(relay) }
    }
}

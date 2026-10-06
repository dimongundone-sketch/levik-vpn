package org.hellokittyvpn.android.data

import org.hellokittyvpn.android.vpn.*
import java.time.Clock
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Import never downloads a subscription URL or executes supplied commands. */
class LocalProfileInput(private val json: Json, private val clock: Clock = Clock.systemUTC()) {
    fun parse(input: String): TunnelProfile {
        val bytes = input.encodeToByteArray()
        try {
            require(bytes.size in 1..1_048_576) { "Profile is empty or too large" }
            val content = input.trim()
            val candidate = if (content.startsWith("{")) {
                val obj = json.parseToJsonElement(content).jsonObject
                if (setOf("profileId", "subscriptionId", "bootstrap", "engine", "issuedAt").any(obj::containsKey)) json.decodeFromString<TunnelProfile>(content)
                else profile(content, "application/json")
            } else {
                require(content.lineSequence().all { line -> line.isBlank() ||
                    line.substringBefore("://") in setOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2") && "://" in line
                }) { "Paste a VPN configuration, not a website address" }
                profile(content, "text/plain")
            }
            require(candidate.engine == TunnelEngineKind.XRAY) { "Relay credentials must be provisioned by the backend" }
            val encoded = json.encodeToString(candidate).encodeToByteArray()
            try { return TunnelProfileParser(json, clock).parse(encoded, candidate.subscriptionId) }
            finally { encoded.fill(0) }
        } finally { bytes.fill(0) }
    }
    private fun profile(content: String, mediaType: String) = TunnelProfile(
        version = 1, profileId = UUID.randomUUID().toString(), subscriptionId = "local-profile",
        issuedAt = clock.instant().toString(), source = TunnelProfileSource(mediaType, content),
    )
}

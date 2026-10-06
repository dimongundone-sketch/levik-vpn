package org.hellokittyvpn.android.core.network

import kotlinx.serialization.Serializable

@Serializable
data class TunnelProfileEnvelope(
    val algorithm: String,
    val encryptedKey: String,
    val iv: String,
    val ciphertext: String,
    val aad: String,
)

@Serializable
data class ApiProblemDetails(
    val subscriptionId: String? = null,
    val component: String? = null,
    val used: Int? = null,
    val limit: Int? = null,
)

@Serializable
data class IpCheckResponse(
    val ok: Boolean = true,
    val address: String = "",
    val isIpv6: Boolean = false,
    val countryCode: String? = null,
    val countryName: String? = null,
    val region: String? = null,
    val city: String? = null,
    val asn: Long? = null,
    val org: String? = null,
    val provider: String? = null,
    val protection: String? = null,
    val isProtected: Boolean = false,
)

sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : ApiException("Network request failed", cause)
    class Unauthorized : ApiException("Session is not authorized")
    class Rejected(
        val code: String,
        val retryable: Boolean,
        val status: Int,
        val details: ApiProblemDetails? = null,
    ) :
        ApiException("API rejected the request: $code")

    class InvalidResponse(message: String, cause: Throwable? = null) :
        ApiException(message, cause)

    class AttestationUnavailable(cause: Throwable? = null) :
        ApiException("Play Integrity attestation is unavailable", cause)
}


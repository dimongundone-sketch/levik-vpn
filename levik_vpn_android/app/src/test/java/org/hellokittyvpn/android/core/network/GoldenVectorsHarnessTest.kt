package org.hellokittyvpn.android.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

class GoldenVectorsHarnessTest {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
        private val HALF_ORDER = P256_ORDER.shiftRight(1)
        private const val MAX_CLOCK_DRIFT_SECONDS = 86400L
    }

    private fun findContractFile(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        for (i in 0..6) {
            val candidate = File(dir, relativePath)
            if (candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        error("Could not find contract file: $relativePath from ${System.getProperty("user.dir")}")
    }

    private fun loadRsaPrivateKey(pkcs8Pem: String): PrivateKey {
        val clean = pkcs8Pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val bytes = Base64.getDecoder().decode(clean)
        val keySpec = PKCS8EncodedKeySpec(bytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    private fun loadRsaPublicKey(spkiBase64Url: String): PublicKey {
        val bytes = Base64.getUrlDecoder().decode(spkiBase64Url)
        val keySpec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance("RSA").generatePublic(keySpec)
    }

    private fun loadEcPublicKeyFromPem(pem: String): PublicKey {
        val clean = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val bytes = Base64.getDecoder().decode(clean)
        val keySpec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance("EC").generatePublic(keySpec)
    }

    private fun checkLowS(sigBytes: ByteArray): Boolean {
        return try {
            var offset = 0
            if (sigBytes[offset++].toInt() != 0x30) return false
            val seqLen = sigBytes[offset++].toInt() and 0xFF
            if (seqLen > 128) {
                val numBytes = seqLen - 128
                offset += numBytes
            }

            if (sigBytes[offset++].toInt() != 0x02) return false
            val rLen = sigBytes[offset++].toInt() and 0xFF
            offset += rLen

            if (sigBytes[offset++].toInt() != 0x02) return false
            val sLen = sigBytes[offset++].toInt() and 0xFF
            val sBytes = sigBytes.copyOfRange(offset, offset + sLen)
            val s = BigInteger(1, sBytes)
            s <= HALF_ORDER
        } catch (e: Exception) {
            false
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    // =========================================================================
    // 1. RequestSigner v1 Golden Vectors Test
    // =========================================================================

    @Test
    fun testSigningVectorsCanonicalAndVerification() {
        val file = findContractFile("contracts/signing-vectors.json")
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val keysObj = root["keys"]!!.jsonObject
        val positiveVectors = root["positiveVectors"]!!.jsonArray

        var posCount = 0
        for (vElem in positiveVectors) {
            val v = vElem.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val keyRef = v["keyRef"]!!.jsonPrimitive.content
            val algorithm = v["algorithm"]!!.jsonPrimitive.content
            val inputs = v["inputs"]!!.jsonObject
            val intermediate = v["intermediate"]!!.jsonObject
            val outputs = v["outputs"]!!.jsonObject
            val expectedSig = outputs["signature"]!!.jsonPrimitive.content

            val method = inputs["method"]!!.jsonPrimitive.content
            val path = inputs["path"]!!.jsonPrimitive.content
            val timestamp = inputs["timestamp"]!!.jsonPrimitive.content.toLong()
            val nonce = inputs["nonce"]!!.jsonPrimitive.content
            val deviceId = intermediate["deviceId"]!!.jsonPrimitive.content
            val tokenHash = intermediate["tokenHash"]!!.jsonPrimitive.content
            val bodyHash = intermediate["bodyHash"]!!.jsonPrimitive.content
            val expectedCanonical = intermediate["canonicalPayload"]!!.jsonPrimitive.content

            // 1. Verify canonical string construction
            val actualCanonical = RequestSigner.canonicalPayload(
                method = method,
                path = path,
                timestamp = timestamp,
                nonce = nonce,
                deviceId = deviceId,
                tokenHash = tokenHash,
                bodyHash = bodyHash,
            )
            assertEquals("Canonical mismatch in vector $id", expectedCanonical, actualCanonical)

            // 2. Verify signature using public key from keyRef
            val keyData = keysObj[keyRef]!!.jsonObject
            val pubKey = loadRsaPublicKey(keyData["publicKeySpkiDerBase64Url"]!!.jsonPrimitive.content)

            val sigVerifier = if (algorithm == "PS256") {
                Signature.getInstance("RSASSA-PSS").apply {
                    initVerify(pubKey)
                    setParameter(
                        PSSParameterSpec(
                            "SHA-256",
                            "MGF1",
                            MGF1ParameterSpec.SHA256,
                            32,
                            1,
                        )
                    )
                }
            } else {
                Signature.getInstance("SHA256withRSA").apply {
                    initVerify(pubKey)
                }
            }

            sigVerifier.update(actualCanonical.toByteArray(Charsets.UTF_8))
            val sigBytes = Base64.getUrlDecoder().decode(expectedSig)
            assertTrue("Signature verification failed for vector $id", sigVerifier.verify(sigBytes))
            posCount++
        }
        assertEquals(10, posCount)
    }

    // =========================================================================
    // 2. Profile Envelope v2 Verification Pipeline (F07)
    // =========================================================================

    private fun verifyEnvelopePipeline(
        envelope: Map<String, String>,
        serverPubKey: PublicKey,
        roguePubKey: PublicKey,
        devicePrivKey: PrivateKey,
        expectedDeviceId: String,
        lastObservedRevision: Int = 1,
        now: Instant = Instant.parse("2026-10-06T21:30:00Z"),
    ): Pair<Boolean, String> {
        val sigB64 = envelope["signature"]
        if (sigB64.isNullOrEmpty()) {
            return false to "MISSING_SIGNATURE"
        }

        val sigBytes = try {
            Base64.getUrlDecoder().decode(sigB64)
        } catch (e: Exception) {
            return false to "ENVELOPE_SIGNATURE_INVALID"
        }

        // Strict canonical low-S check
        if (!checkLowS(sigBytes)) {
            return false to "ENVELOPE_SIGNATURE_INVALID"
        }

        val protectedB64 = envelope["protected"] ?: ""
        val wrappedKeyB64 = envelope["wrappedKey"] ?: ""
        val ivB64 = envelope["iv"] ?: ""
        val ciphertextB64 = envelope["ciphertext"] ?: ""

        val signedString = "HKVPN-PROFILE-V2\n$protectedB64\n$wrappedKeyB64\n$ivB64\n$ciphertextB64\n"
        val signedBytes = signedString.toByteArray(Charsets.UTF_8)

        // Verify ECDSA server signature
        val serverSigVerifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(serverPubKey)
            update(signedBytes)
        }
        val serverSigValid = try {
            serverSigVerifier.verify(sigBytes)
        } catch (e: Exception) {
            false
        }

        if (!serverSigValid) {
            val rogueSigVerifier = Signature.getInstance("SHA256withECDSA").apply {
                initVerify(roguePubKey)
                update(signedBytes)
            }
            val isRogue = try {
                rogueSigVerifier.verify(sigBytes)
            } catch (e: Exception) {
                false
            }
            if (isRogue) {
                return false to "UNTRUSTED_KEY_SIGNATURE"
            }
            return false to "ENVELOPE_SIGNATURE_INVALID"
        }

        // Decode protected metadata
        val metaJson = try {
            val dec = Base64.getUrlDecoder().decode(protectedB64)
            json.parseToJsonElement(String(dec, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            return false to "METADATA_DECODE_FAILED"
        }

        if (metaJson["purpose"]?.jsonPrimitive?.content != "profile" ||
            metaJson["schemaVersion"]?.jsonPrimitive?.content != "2"
        ) {
            return false to "METADATA_DECODE_FAILED"
        }

        val metaDeviceId = metaJson["deviceId"]?.jsonPrimitive?.content
        if (metaDeviceId != expectedDeviceId) {
            return false to "CROSS_DEVICE_VIOLATION"
        }

        val expStr = metaJson["credentialExpiresAt"]?.jsonPrimitive?.content
            ?: return false to "METADATA_DECODE_FAILED"
        val expInstant = try {
            Instant.parse(expStr)
        } catch (e: Exception) {
            return false to "METADATA_DECODE_FAILED"
        }
        if (!expInstant.isAfter(now)) {
            return false to "CREDENTIAL_EXPIRED"
        }

        val rev = metaJson["profileRevision"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        if (rev < lastObservedRevision) {
            return false to "ROLLBACK_DETECTED"
        }

        val encAlgorithm = metaJson["encAlgorithm"]?.jsonPrimitive?.content ?: ""

        // Unwrap AES key
        val aesKeyBytes = try {
            val oaepCipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            val oaepParams = if (encAlgorithm.contains("RSA-OAEP-256")) {
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
            } else {
                OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
            }
            oaepCipher.init(Cipher.DECRYPT_MODE, devicePrivKey, oaepParams)
            oaepCipher.doFinal(Base64.getUrlDecoder().decode(wrappedKeyB64))
        } catch (e: Exception) {
            return false to "KEY_UNWRAP_FAILED"
        }

        // Decrypt AES-GCM payload with authenticated AAD
        val plaintextBytes = try {
            val gcmCipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.getUrlDecoder().decode(ivB64)
            val aad = "HKVPN-PROFILE-V2\n$protectedB64".toByteArray(Charsets.UTF_8)
            val ciphertext = Base64.getUrlDecoder().decode(ciphertextB64)

            gcmCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), GCMParameterSpec(128, iv))
            gcmCipher.updateAAD(aad)
            gcmCipher.doFinal(ciphertext)
        } catch (e: Exception) {
            return false to "GCM_AUTH_FAILED"
        }

        // Validate decrypted payload
        val payloadObj = try {
            json.parseToJsonElement(String(plaintextBytes, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            return false to "PAYLOAD_DECODE_FAILED"
        }

        val payloadRev = payloadObj["profileRevision"]?.jsonPrimitive?.content?.toInt()
        val payloadAccessId = payloadObj["accessId"]?.jsonPrimitive?.content
        val metaAccessId = metaJson["accessId"]?.jsonPrimitive?.content

        if (payloadRev != rev || payloadAccessId != metaAccessId) {
            return false to "METADATA_PAYLOAD_MISMATCH"
        }

        return true to "SUCCESS"
    }

    @Test
    fun testEnvelopeVectorsFullPipeline() {
        val file = findContractFile("contracts/envelope-vectors.json")
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val serverKeyData = root["serverKey"]!!.jsonObject
        val rogueKeyData = root["rogueServerKey"]!!.jsonObject
        val deviceKeysObj = root["deviceKeys"]!!.jsonObject
        val positiveVectors = root["positiveVectors"]!!.jsonArray
        val negativeVectors = root["negativeVectors"]!!.jsonArray

        val serverPubKey = loadEcPublicKeyFromPem(serverKeyData["publicKeyPem"]!!.jsonPrimitive.content)
        val roguePubKey = loadEcPublicKeyFromPem(rogueKeyData["publicKeyPem"]!!.jsonPrimitive.content)

        val deviceMap = mutableMapOf<String, Pair<PrivateKey, String>>()
        for ((k, dElem) in deviceKeysObj) {
            val dObj = dElem.jsonObject
            val priv = loadRsaPrivateKey(dObj["privateKeyPkcs8Pem"]!!.jsonPrimitive.content)
            val devId = dObj["deviceId"]!!.jsonPrimitive.content
            deviceMap[k] = priv to devId
        }

        // 1. Verify Positive Vectors
        var posCount = 0
        for (vElem in positiveVectors) {
            val v = vElem.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val targetRef = v["targetDeviceRef"]!!.jsonPrimitive.content
            val (devPriv, devId) = deviceMap[targetRef]!!

            val envMap = v["envelope"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            val (ok, reason) = verifyEnvelopePipeline(
                envelope = envMap,
                serverPubKey = serverPubKey,
                roguePubKey = roguePubKey,
                devicePrivKey = devPriv,
                expectedDeviceId = devId,
            )
            assertTrue("Positive envelope vector $id failed: $reason", ok)
            assertEquals("SUCCESS", reason)
            posCount++
        }
        assertEquals(2, posCount)

        // 2. Verify ALL 11 Negative Vectors (F07 Acceptance)
        var negCount = 0
        for (vElem in negativeVectors) {
            val v = vElem.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val expectedErr = v["expectedError"]!!.jsonPrimitive.content
            val targetRef = v["targetDeviceRef"]?.jsonPrimitive?.content ?: "modern_device"
            val (devPriv, devId) = deviceMap[targetRef]!!
            val lastRev = if (id == "neg_07_rollback_revision") 2 else 1

            val envMap = v["envelope"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            val (ok, actualReason) = verifyEnvelopePipeline(
                envelope = envMap,
                serverPubKey = serverPubKey,
                roguePubKey = roguePubKey,
                devicePrivKey = devPriv,
                expectedDeviceId = devId,
                lastObservedRevision = lastRev,
            )
            assertFalse("Negative envelope vector $id unexpectedly succeeded!", ok)
            assertEquals("Negative vector $id failure mismatch", expectedErr, actualReason)
            negCount++
        }
        assertEquals(11, negCount)
    }

    // =========================================================================
    // 3. Challenge Protocol Vectors Test (F08)
    // =========================================================================

    private fun verifyChallengePipeline(
        resp: Map<String, String>,
        rawReqBytes: ByteArray,
        trustedRing: Map<String, PublicKey>,
        clientNow: Instant,
    ): Pair<Boolean, String> {
        val sigB64 = resp["signature"]
        if (sigB64.isNullOrEmpty()) {
            return false to "MISSING_SIGNATURE"
        }

        if (resp["purpose"] != "challenge") {
            return false to "INVALID_PURPOSE"
        }
        if (resp["signatureAlgorithm"] != "ES256") {
            return false to "UNSUPPORTED_SIGNATURE_ALGORITHM"
        }

        // Validate clientNonce length in request
        try {
            val reqJson = json.parseToJsonElement(String(rawReqBytes, Charsets.UTF_8)).jsonObject
            val cNonce = reqJson["clientNonce"]?.jsonPrimitive?.content
            if (cNonce != null) {
                if (cNonce.length != 22) return false to "INVALID_NONCE_LENGTH"
                val rawDec = Base64.getUrlDecoder().decode(cNonce)
                if (rawDec.size != 16) return false to "INVALID_NONCE_LENGTH"
            }
        } catch (e: Exception) {
            // Ignore parse errors for non-JSON requests
        }

        // Validate serverNonce length (32 bytes = 43 chars base64url)
        val sNonce = resp["serverNonce"] ?: ""
        try {
            val dec = Base64.getUrlDecoder().decode(sNonce)
            if (dec.size != 32) return false to "INVALID_SERVER_NONCE_LENGTH"
        } catch (e: Exception) {
            return false to "INVALID_SERVER_NONCE_LENGTH"
        }

        // Verify request body hash
        val expectedHash = sha256Hex(rawReqBytes)
        if (resp["requestBodyHash"] != expectedHash) {
            return false to "REQUEST_BODY_HASH_MISMATCH"
        }

        // Verify keyId in trusted key ring
        val keyId = resp["keyId"] ?: ""
        val trustedKey = trustedRing[keyId] ?: return false to "UNAUTHORIZED_SIGNING_KEY"

        val sigBytes = try {
            Base64.getUrlDecoder().decode(sigB64)
        } catch (e: Exception) {
            return false to "CHALLENGE_SIGNATURE_INVALID"
        }

        // Strict canonical low-S check
        if (!checkLowS(sigBytes)) {
            return false to "CHALLENGE_SIGNATURE_INVALID"
        }

        // Canonical 9-line string assembly
        val canonical = "HKVPN-CHALLENGE-V1\nchallenge\nES256\n${resp["keyId"]}\n${resp["challengeId"]}\n${resp["serverNonce"]}\n${resp["expiresAt"]}\n${resp["serverTime"]}\n${resp["requestBodyHash"]}\n"
        val canonicalBytes = canonical.toByteArray(Charsets.UTF_8)

        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(trustedKey)
            update(canonicalBytes)
        }
        val sigValid = try {
            verifier.verify(sigBytes)
        } catch (e: Exception) {
            false
        }
        if (!sigValid) {
            return false to "CHALLENGE_SIGNATURE_INVALID"
        }

        // Timestamps and drift
        val serverTime = try {
            Instant.parse(resp["serverTime"])
        } catch (e: Exception) {
            return false to "INVALID_TIMESTAMP_FORMAT"
        }
        val expiresAt = try {
            Instant.parse(resp["expiresAt"])
        } catch (e: Exception) {
            return false to "INVALID_TIMESTAMP_FORMAT"
        }

        if (!serverTime.isBefore(expiresAt)) {
            return false to "CHALLENGE_EXPIRED"
        }

        val driftSeconds = Duration.between(clientNow, serverTime).abs().seconds
        if (driftSeconds > MAX_CLOCK_DRIFT_SECONDS) {
            return false to "CLOCK_DRIFT_EXCESSIVE"
        }

        return true to "SUCCESS"
    }

    @Test
    fun testChallengeProtocolVectors() {
        val file = findContractFile("contracts/challenge-vectors.json")
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val serverKeyData = root["serverKey"]!!.jsonObject
        val serverPubKey = loadEcPublicKeyFromPem(serverKeyData["publicKeyPem"]!!.jsonPrimitive.content)
        val trustedRing = mapOf(serverKeyData["keyId"]!!.jsonPrimitive.content to serverPubKey)

        val clientNow = Instant.parse("2026-10-07T08:30:00Z")

        val positiveVectors = root["positiveVectors"]!!.jsonArray
        val negativeVectors = root["negativeVectors"]!!.jsonArray

        var posCount = 0
        for (vElem in positiveVectors) {
            val v = vElem.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val rawReqBytes = v["requestBodyRawBytes"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
            val respMap = v["response"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

            val (ok, reason) = verifyChallengePipeline(respMap, rawReqBytes, trustedRing, clientNow)
            assertTrue("Challenge positive vector $id failed: $reason", ok)
            assertEquals("SUCCESS", reason)
            posCount++
        }
        assertEquals(2, posCount)

        var negCount = 0
        for (vElem in negativeVectors) {
            val v = vElem.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val expectedErr = v["expectedError"]!!.jsonPrimitive.content
            val rawReqBytes = v["requestBodyRawBytes"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
            val respMap = v["response"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

            val (ok, actualReason) = verifyChallengePipeline(respMap, rawReqBytes, trustedRing, clientNow)
            assertFalse("Challenge negative vector $id unexpectedly passed!", ok)
            assertEquals("Challenge negative vector $id error mismatch", expectedErr, actualReason)
            negCount++
        }
        assertEquals(11, negCount)
    }
}

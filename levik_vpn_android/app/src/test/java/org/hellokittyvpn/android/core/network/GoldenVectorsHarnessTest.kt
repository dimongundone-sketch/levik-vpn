package org.hellokittyvpn.android.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

class GoldenVectorsHarnessTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun findContractFile(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        for (i in 0..5) {
            val candidate = File(dir, relativePath)
            if (candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        error("Could not find contract file: $relativePath from ${System.getProperty("user.dir")}")
    }

    private fun loadPrivateKey(pkcs8Pem: String): PrivateKey {
        val clean = pkcs8Pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val bytes = Base64.getDecoder().decode(clean)
        val keySpec = PKCS8EncodedKeySpec(bytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    private fun loadPublicKey(spkiBase64Url: String): PublicKey {
        val bytes = Base64.getUrlDecoder().decode(spkiBase64Url)
        val keySpec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance("RSA").generatePublic(keySpec)
    }

    @Test
    fun testSigningVectorsCanonicalAndVerification() {
        val file = findContractFile("contracts/signing-vectors.json")
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val keysObj = root["keys"]!!.jsonObject
        val positiveVectors = root["positiveVectors"]!!.jsonArray

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
            val pubKey = loadPublicKey(keyData["publicKeySpkiDerBase64Url"]!!.jsonPrimitive.content)

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
        }
    }

    @Test
    fun testEnvelopeVectorsDecryptionAndPayload() {
        val file = findContractFile("contracts/envelope-vectors.json")
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val keysObj = root["deviceKeys"]!!.jsonObject
        val positiveVectors = root["positiveVectors"]!!.jsonArray

        for (vElem in positiveVectors) {
            val v = vElem.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val encAlgorithm = v["encAlgorithm"]!!.jsonPrimitive.content
            val envelope = v["envelope"]!!.jsonObject
            val protectedHeader = envelope["protected"]!!.jsonPrimitive.content
            val wrappedKeyB64 = envelope["wrappedKey"]!!.jsonPrimitive.content
            val ivB64 = envelope["iv"]!!.jsonPrimitive.content
            val ciphertextB64 = envelope["ciphertext"]!!.jsonPrimitive.content

            val keyRef = v["targetDeviceRef"]!!.jsonPrimitive.content
            val keyData = keysObj[keyRef]!!.jsonObject
            val privKey = loadPrivateKey(keyData["privateKeyPkcs8Pem"]!!.jsonPrimitive.content)

            // 1. Unwrap AES key using RSA-OAEP
            val oaepCipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            val oaepParams = if (encAlgorithm.contains("RSA-OAEP-256")) {
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
            } else {
                OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
            }
            oaepCipher.init(Cipher.DECRYPT_MODE, privKey, oaepParams)
            val aesKeyBytes = oaepCipher.doFinal(Base64.getUrlDecoder().decode(wrappedKeyB64))
            assertEquals(32, aesKeyBytes.size)

            // 2. Decrypt payload with AES-GCM
            val gcmCipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.getUrlDecoder().decode(ivB64)
            val aad = "HKVPN-PROFILE-V2\n$protectedHeader".toByteArray(Charsets.UTF_8)
            val ciphertext = Base64.getUrlDecoder().decode(ciphertextB64)

            gcmCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), GCMParameterSpec(128, iv))
            gcmCipher.updateAAD(aad)
            val plaintextBytes = gcmCipher.doFinal(ciphertext)

            // 3. Verify decrypted payload structure
            val plaintextStr = String(plaintextBytes, Charsets.UTF_8)
            val payloadObj = json.parseToJsonElement(plaintextStr).jsonObject
            val expectedPlaintext = v["plaintext"]!!.jsonObject
            val expectedRevision = expectedPlaintext["profileRevision"]!!.jsonPrimitive.content.toInt()
            val expectedAccessId = expectedPlaintext["accessId"]!!.jsonPrimitive.content
            assertEquals(expectedRevision, payloadObj["profileRevision"]!!.jsonPrimitive.content.toInt())
            assertEquals(expectedAccessId, payloadObj["accessId"]!!.jsonPrimitive.content)
            assertNotNull(payloadObj["servers"])

            val servers = payloadObj["servers"]!!.jsonArray
            assertTrue(servers.isNotEmpty())
            val firstServer = servers[0].jsonObject
            assertEquals("xray", firstServer["engine"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun testEnvelopeNegativeVectorsRejection() {
        val file = findContractFile("contracts/envelope-vectors.json")
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val keysObj = root["deviceKeys"]!!.jsonObject
        val negativeVectors = root["negativeVectors"]!!.jsonArray

        for (vElem in negativeVectors) {
            val v = vElem.jsonObject
            val id = v["id"]!!.jsonPrimitive.content

            if (id == "neg_01_tampered_ciphertext") {
                val envelope = v["envelope"]!!.jsonObject
                val privKey = loadPrivateKey(keysObj["modern_device"]!!.jsonObject["privateKeyPkcs8Pem"]!!.jsonPrimitive.content)
                val oaepCipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
                oaepCipher.init(Cipher.DECRYPT_MODE, privKey, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
                val aesKeyBytes = oaepCipher.doFinal(Base64.getUrlDecoder().decode(envelope["wrappedKey"]!!.jsonPrimitive.content))

                val gcmCipher = Cipher.getInstance("AES/GCM/NoPadding")
                val iv = Base64.getUrlDecoder().decode(envelope["iv"]!!.jsonPrimitive.content)
                val aad = "HKVPN-PROFILE-V2\n${envelope["protected"]!!.jsonPrimitive.content}".toByteArray(Charsets.UTF_8)
                val ciphertext = Base64.getUrlDecoder().decode(envelope["ciphertext"]!!.jsonPrimitive.content)

                try {
                    gcmCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), GCMParameterSpec(128, iv))
                    gcmCipher.updateAAD(aad)
                    gcmCipher.doFinal(ciphertext)
                    fail("neg_01 should have failed GCM auth")
                } catch (e: Exception) {
                    // Expected AEADBadTagException or GeneralSecurityException
                    assertTrue(e is javax.crypto.AEADBadTagException || e is BadPaddingException)
                }
            } else if (id == "neg_04_tampered_wrapped_key") {
                val envelope = v["envelope"]!!.jsonObject
                val privKey = loadPrivateKey(keysObj["modern_device"]!!.jsonObject["privateKeyPkcs8Pem"]!!.jsonPrimitive.content)
                val oaepCipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
                oaepCipher.init(Cipher.DECRYPT_MODE, privKey, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))

                try {
                    oaepCipher.doFinal(Base64.getUrlDecoder().decode(envelope["wrappedKey"]!!.jsonPrimitive.content))
                    fail("neg_04 should have failed RSA-OAEP unwrapping")
                } catch (e: Exception) {
                    // Expected BadPaddingException
                    assertTrue(e is BadPaddingException)
                }
            }
        }
    }
}

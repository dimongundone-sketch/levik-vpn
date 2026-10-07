# Hello Kitty VPN — Cryptographic Security Contract & Threat Model

**Status:** DRAFT / PROPOSED FOR G01 CONTRACT FREEZE  
**Domain:** Security Architecture, Cryptographic Wire Contracts, Identity & Envelope Verification  
**Applies to:** Android Client (`org.hellokittyvpn.android`), Go Backend Control Plane (`backend/`), Node Provisioner  

---

## 1. Overview & Architectural Scope

The Hello Kitty VPN system provides privacy and censorship-resistant tunneling for Android clients connecting to distributed node backends through a centralized Go control plane. 

To eliminate single points of trust and prevent server spoofing, traffic hijacking, credential theft, and device impersonation, this document formally specifies:
1. **Threat Model**: Explicit threat actors, trust boundaries, attack vectors, and security invariants.
2. **Proof-of-Possession (PoP) Authentication (`RequestSigner v1`)**: Hardware-backed asymmetric request signing protocol securing all sensitive client-to-backend HTTP interactions.
3. **Profile Envelope v2 (`HKVPN-PROFILE-V2`)**: Authenticated, end-to-end encrypted, and anti-rollback protected wire format for tunnel configuration delivery.
4. **Token Lifecycle & Lost-Response Recovery**: Opaque token rotation, token-family reuse detection, and a race-free, idempotent recovery window for mobile network drops.
5. **Key Separation & Cryptographic Hygiene**: Formal domain separation across all system keys, preventing cross-protocol attacks.

---

## 2. Threat Model (STRIDE Analysis & Attack Scenarios)

### 2.1 Trust Boundaries & Actors

```
┌─────────────────────────────────────────────────────────────┐
│ Android Device (Untrusted Host OS / Hostile Environment)     │
│  ┌───────────────────────┐    ┌───────────────────────────┐ │
│  │ Android App Logic     │    │ Android Keystore          │ │
│  │ (Process Memory)      │◄───┤ (TEE / StrongBox SE)      │ │
│  └───────────┬───────────┘    └───────────────────────────┘ │
└──────────────┼──────────────────────────────────────────────┘
               │ TLS 1.3 + RequestSigner v1 Proof-of-Possession
               ▼
┌─────────────────────────────────────────────────────────────┐
│ Hostile Network Ingress (Active ISP / MITM / Censor)        │
└──────────────┬──────────────────────────────────────────────┘
               │ TLS 1.3 Termination (Ingress Gateway)
               ▼
┌─────────────────────────────────────────────────────────────┐
│ Control Plane Backend (Go API + PostgreSQL Storage)         │
│  ┌───────────────────────┐    ┌───────────────────────────┐ │
│  │ Go HTTP Services      │    │ Ephemeral Encrypted Cache │ │
│  │ (Auth / Issuance)     │    │ (At-rest Storage Key)     │ │
│  └───────────┬───────────┘    └───────────────────────────┘ │
│              │ DB Isolation                                 │
│              ▼                                              │
│  ┌───────────────────────┐                                  │
│  │ PostgreSQL DB         │                                  │
│  │ (Hashed Tokens/Nonces)│                                  │
│  └───────────────────────┘                                  │
└──────────────┬──────────────────────────────────────────────┘
               │ mTLS / Signed Provisioning Envelope
               ▼
┌─────────────────────────────────────────────────────────────┐
│ Edge Routing Nodes (Xray / WDTT Provisioners)               │
└─────────────────────────────────────────────────────────────┘
```

The system recognizes five primary threat actors:
1. **Adversary at Network Layer (Censor / ISP / Rogue Wi-Fi / Ingress MITM)**: Can inspect, drop, replay, delay, modify, or inject arbitrary network packets. May possess forged or compromised intermediate TLS CAs (censor-controlled MITM).
2. **Malicious Client / Adversary with Shared Device**: Can decompile the client APK, execute within debuggers, inspect memory dumps, or extract locally stored non-hardware keys.
3. **Revoked or Compromised Device**: An enrolled device whose grant or subscription has ended or was compromised; attempts to maintain access or renew expired credentials.
4. **Untrusted Node Operator**: Operates an egress tunnel server; must not be able to decrypt profile secrets for other nodes, impersonate the control plane, or generate valid client certificates/tokens.
5. **Compromised Log/Telemetry Store**: Passive attacker gaining access to backend application logs or database read snapshots.

### 2.2 Attack Vectors & Mitigations

#### A. Man-in-the-Middle (MITM) & CA Compromise
* **Threat**: An active attacker decrypts TLS traffic using a compromised or rogue root certificate installed on the device/network, attempting to steal credentials or modify configuration payloads.
* **Mitigation**: All HTTP requests are bound to hardware-generated asymmetric keys via `RequestSigner v1` Proof-of-Possession. All profile payloads returned from the server are encrypted using the device's public key (RSA-OAEP) and signed by the server's dedicated ECDSA key (`HKVPN-PROFILE-V2`). TLS interception cannot forge device signatures nor decrypt profile envelopes.

#### B. Replay Attacks
* **Threat**: An eavesdropper captures a valid signed HTTP request (e.g., refresh token or profile issuance) and replays it at a later time.
* **Mitigation**:
  1. Requests contain an epoch timestamp (`X-HKVPN-Timestamp`); backend strictly rejects requests outside a $\pm 120$ second clock skew window.
  2. Requests contain a 16-byte cryptographically random base64url nonce (`X-HKVPN-Nonce`). The tuple `(device_id, nonce)` is uniquely recorded in the backend database with a 5-minute retention window surviving service restarts. Duplicate nonces yield immediate `401 Unauthorized` rejection.

#### C. Credential Reuse & Lost-Response Race Conditions
* **Threat**: 
  - An attacker intercepts a consumed refresh token and attempts to exchange it for a new access token.
  - A mobile client sends a refresh request; the server processes the rotation and commits new tokens, but the mobile radio drops the connection before the response reaches the client. The client retries, risking accidental detection as an attacker (token reuse).
* **Mitigation**:
  - The client provides an `Idempotency-Key` HTTP header strictly identical to the `clientOperationId` field inside the signed body.
  - The backend maintains an atomic 120-second **Lost-Response Recovery Window**: An exact retry (matching `deviceId`, consumed `refreshToken`, `clientOperationId`, and exact body hash) retrieves an encrypted cached copy of the previous successful response without triggering a second token rotation or revoking the family.
  - If a consumed refresh token is presented with a **different** `clientOperationId`, the system detects an unauthorized **Token Reuse Attack** and immediately revokes the entire token family and all associated sessions.
  - If the same `clientOperationId` is presented with an altered body, the backend rejects with `409 Conflict`.
  - An exact retry arriving **after** 120 seconds returns `410 REFRESH_RETRY_EXPIRED`, directing the client to re-authenticate via hardware Keystore challenge (`mode=reauth`), preserving the existing grant rather than falsely revoking or recreating it.

#### D. Cross-Device Injection & Impersonation
* **Threat**: Device A attempts to decrypt a profile intended for Device B, or requests profile issuance by presenting Device B's identifier.
* **Mitigation**:
  - The `deviceId` is cryptographically pinned as the lowercase hex SHA-256 of the device's DER SubjectPublicKeyInfo (SPKI).
  - Every signed request cryptographically binds the `deviceId` in its canonical string.
  - The backend verifies that the caller owns the active access token and device ID.
  - Profile envelopes encrypt the symmetric session key exclusively to the device's certified public key, and include `deviceId` in the Authenticated Additional Data (`AAD`), binding the ciphertext inextricably to that device.

#### E. Rollback Attacks (Revision & Protocol Downgrade)
* **Threat**:
  - An attacker replays an older, vulnerable profile configuration or revoked routing table.
  - An attacker attempts to force modern Android devices (API 35+) to use legacy cryptography (e.g., RSA PKCS#1 v1.5 or OAEP-SHA1).
* **Mitigation**:
  - Every profile envelope includes an monotonically increasing `profileRevision`. The client rejects any profile where `profileRevision < lastObservedRevision`.
  - The envelope includes an explicit `credentialExpiresAt` timestamp verified against current device time.
  - Device cryptographic capabilities (`PS256` vs `RS256`, `RSA-OAEP-256` vs `RSA-OAEP`) are established during hardware enrollment and pinned in backend state. The backend rejects algorithm downgrade headers that do not match the stored device policy.

#### F. Timing & Side-Channel Attacks
* **Threat**: Differences in processing time reveal token validity, byte matching, or database record existence.
* **Mitigation**:
  - All token comparisons, hash checks, and HMAC calculations use constant-time comparison algorithms (e.g., `subtle.ConstantTimeCompare` in Go, `MessageDigest.isEqual` in Java, `hmac.compare_digest` in Python).
  - Challenge endpoints do not reveal whether a device or invitation exists; unallocated or invalid invitations return identical timing profiles and error responses.

---

## 3. Proof-of-Possession Contract (`RequestSigner v1`)

All mutating or protected API requests to `/v1` require an asymmetric digital signature generated by the Android Keystore hardware key.

### 3.1 Canonical Payload Specification

The canonical string `canonicalPayload` is formed by joining exactly eight lines with single newline bytes (`\n`, ASCII `0x0A`). No trailing newline is permitted.

```text
v1
METHOD
encodedPath
epochSeconds
nonce
deviceId
tokenHash
rawBodyHash
```

#### Exact Line Definitions:

| Line # | Field | Type / Encoding | Description & Validation Rules |
|:---:|:---|:---|:---|
| 1 | `version` | Constant string | Exactly `"v1"`. |
| 2 | `METHOD` | ASCII string | HTTP method in uppercase (matching regex `^[A-Z]{3,10}$`). Examples: `"GET"`, `"POST"`, `"DELETE"`. |
| 3 | `encodedPath` | ASCII string | URL-encoded path starting with `'/'`. **Query strings (`'?'`) and fragments (`'#'`) are strictly forbidden in v1.** Path ingress normalizers must not alter percent-encoding prior to verification. |
| 4 | `epochSeconds` | Decimal ASCII | Unix timestamp in seconds (UTC). Server rejects if $\| \text{epochSeconds} - \text{serverTime} \| > 120$. |
| 5 | `nonce` | Base64URL string | 16 cryptographically random bytes encoded in unpadded Base64URL (`[A-Za-z0-9_-]{22}`). Must be globally unique per `(deviceId, nonce)` within 5 minutes. |
| 6 | `deviceId` | Hex string | Exactly 64 lowercase hexadecimal characters representing the SHA-256 digest of the device's public key DER SPKI bytes. |
| 7 | `tokenHash` | Hex string | 64 lowercase hex characters: `SHA256(accessToken)`. When no bearer access token is used (e.g., during `/v1/devices/challenges`, `/v1/devices/complete`, `/v1/tokens/refresh`), this MUST be the SHA-256 digest of the empty string: `'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'`. |
| 8 | `rawBodyHash` | Hex string | 64 lowercase hex characters: `SHA256(rawBodyBytes)`. This is calculated over the exact raw body bytes **before** any JSON parsing. For empty bodies (0 bytes), it is `SHA256("")` = `'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'`. |

```
Canonical String Example (Total 8 lines, 7 '\n' separators, 0 trailing '\n'):
v1\nPOST\n/v1/devices/complete\n1700000000\nMDEyMzQ1Njc4OWFiY2RlZg\n9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n5df6e0e2761359d30a8275058e299fcc0381534545f55cf43e41983f5d4c9456
```

### 3.2 Cryptographic Signing Algorithms

The backend validates signatures according to the registered capability of the device:

1. **`PS256` (Modern — API 35+)**:
   - Algorithm: RSASSA-PSS with SHA-256.
   - Hash function: `SHA-256`.
   - Mask Generation Function: `MGF1` with `SHA-256`.
   - Salt Length: Exactly 32 bytes (`saltLength == 32`).
   - Trailer Field: Standard `1` (`0xBC`).

2. **`RS256` (Legacy — API 34 and devices lacking TEE PSS support)**:
   - Algorithm: RSASSA-PKCS1-v1_5 with SHA-256.
   - Hash function: `SHA-256`.

3. **Key Specifications**:
   - Key type: RSA.
   - Modulus size: Exactly 3072 bits or 4096 bits. (2048-bit keys are rejected as insufficient for modern security baselines; keys > 4096 bits are rejected as DoS vector).
   - Public exponent: $e = 65537$ (`0x10001`).
   - Encoding: X.509 SubjectPublicKeyInfo (SPKI) in binary DER format.

4. **Signature Encoding**:
   - The raw RSA signature bytes (384 bytes for 3072-bit, 512 bytes for 4096-bit) are encoded in **unpadded Base64URL** (`RFC 4648 §5`).

### 3.3 HTTP Headers Specification

Clients transmit signature metadata via dedicated HTTP headers:

```http
Authorization: Bearer <opaque_access_token>
X-HKVPN-Device-Id: <64-char hex SHA256 of SPKI>
X-HKVPN-Timestamp: <epoch_seconds>
X-HKVPN-Nonce: <16-byte base64url unpadded>
X-HKVPN-Signature: <base64url unpadded signature bytes>
X-HKVPN-Algorithm: PS256 | RS256
```

#### Validation Rules:
1. All six headers (or five when `Authorization` is absent on unauthenticated endpoints) MUST be present.
2. Duplicate headers are strictly rejected with `400 Bad Request`.
3. `X-HKVPN-Algorithm` MUST match the algorithm registered during device enrollment; arbitrary algorithm switching is rejected with `403 Forbidden`.
4. If an `Authorization` header is present, the client access token is extracted, hashed, and matched against `tokenHash` in the signature.

### 3.3 Endpoint Proof Rules & Exceptions

Proof requirements differ based on endpoint authentication lifecycle:
1. **Public Challenge Ingress (`POST /v1/devices/challenges`)**:
   - Strictly public endpoint designed to bootstrap device clock synchronization and issue cryptographic nonces.
   - **No** `RequestSigner v1` headers (`X-HKVPN-*`) or bearer tokens are required or evaluated.
2. **Unauthenticated Hardware-Signed Endpoints (`POST /v1/devices/complete`, `POST /v1/tokens/refresh`)**:
   - Require full `RequestSigner v1` headers generated by the device's private key.
   - Because no active bearer access token is yet possessed, `tokenHash` MUST be `SHA256("")` (`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`).
   - The `Authorization` HTTP header is omitted.
3. **Protected Bearer Endpoints (`GET /v1/devices/me`, `DELETE /v1/devices/me`, `POST /v1/profiles`, `GET /v1/profiles/*`)**:
   - Require both an active bearer `Authorization: Bearer <accessToken>` header and matching `RequestSigner v1` headers where `tokenHash == SHA256(accessToken)`.

### 3.4 Signed Server Time & Challenge Protocol (`HKVPN-CHALLENGE-V1`)

To prevent replay attacks and allow clients with desynchronized real-time clocks (e.g. after battery depletion or reboot) to synchronize safely before generating timestamps for `RequestSigner v1`, the control plane issues a cryptographically signed challenge and authoritative server time.

#### A. Wire Structure
1. **Request (`POST /v1/devices/challenges`)**:
   - JSON payload containing:
     * `clientNonce`: 16 cryptographically random bytes generated by client, encoded as unpadded Base64URL (`^[A-Za-z0-9_-]{22}$`).
     * `mode`: Enum `"enroll"` | `"reauth"`.
     * `publicKeySpki`: Base64-encoded DER SubjectPublicKeyInfo of device Keystore key.
     * `invitationCode`: Required for `"enroll"`, omitted for `"reauth"`.
     * `supportedAlgorithms`: Array of supported signature schemes (e.g. `["PS256", "RS256"]`).
     * `capabilities`: Hardware capabilities (`["strongbox", "tee"]`).
2. **Response (`ChallengeResponse`)**:
   - JSON payload:
     ```json
     {
       "challengeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
       "serverNonce": "4vQkZ6jF8Kx0yA_1b2c3d4e5f6g7h8i9j0k1l2m3n4o",
       "expiresAt": "2026-10-07T08:32:00Z",
       "serverTime": "2026-10-07T08:30:00Z",
       "keyId": "hkvpn-challenge-signer-2026-v1",
       "purpose": "challenge",
       "signatureAlgorithm": "ES256",
       "requestBodyHash": "5df6e0e2761359d30a8275058e299fcc0381534545f55cf43e41983f5d4c9456",
       "signature": "MEQCID3Q...unpadded_base64url_der_low_s..."
     }
     ```

#### B. Canonical Signed String Specification
The signed canonical string `signedChallengeBytes` is formed by joining exactly nine lines, where **every line ends with a newline byte (`\n`, `0x0A`)**:

```text
HKVPN-CHALLENGE-V1\n
challenge\n
ES256\n
<keyId>\n
<challengeId>\n
<serverNonce>\n
<expiresAt>\n
<serverTime>\n
<requestBodyHash>\n
```

| Line # | Field | Format / Encoding | Description |
|:---:|:---|:---|:---|
| 1 | `prefix` | Constant | Exactly `"HKVPN-CHALLENGE-V1"`. |
| 2 | `purpose` | Constant | Exactly `"challenge"`. |
| 3 | `signatureAlgorithm` | Constant | Exactly `"ES256"`. |
| 4 | `keyId` | ASCII string | Server challenge signing key ID in trust ring. |
| 5 | `challengeId` | UUID string | Canonical lowercase UUID string. |
| 6 | `serverNonce` | Base64URL string | 32-byte server nonce, unpadded Base64URL. |
| 7 | `expiresAt` | ISO 8601 UTC string | UTC timestamp formatted as `YYYY-MM-DDTHH:MM:SSZ`. |
| 8 | `serverTime` | ISO 8601 UTC string | UTC timestamp formatted as `YYYY-MM-DDTHH:MM:SSZ`. |
| 9 | `requestBodyHash` | Hex string | 64 lowercase hex characters: `SHA256(rawRequestBodyBytes)`. |

Total 9 lines, 9 newline (`\n`) characters.

#### C. Trust Bootstrap, Rotation & Key Separation
1. **Dedicated Key**: Signed with a dedicated NIST P-256 ECDSA key (`ES256`) with low-$S$ strict DER signature. Under no circumstances is this key shared with profile envelope or routing rule signing.
2. **Trust Bootstrap**: Android client ships with pre-pinned public keys in the `challenge` key ring. Future rotations are signed and authenticated via control plane manifest.
3. **Verification Policy**:
   - Client verifies `requestBodyHash == hex(SHA256(rawRequestBodyBytes))` against the exact bytes sent in the request.
   - Client resolves `keyId` in local challenge key ring; unknown `keyId` returns `UNAUTHORIZED_SIGNING_KEY`.
   - Client verifies ES256 signature over the 9-line canonical bytes with strict DER and low-$S$ validation.
   - Client verifies `expiresAt > serverTime` and `serverTime` is not before app build timestamp (anti-rollback floor).
   - Only after successful verification, client applies bounded clock adjustment $\Delta = serverTime - localTime$. If $|\Delta| > 86400$s (24 hours), client raises `CLOCK_DRIFT_EXCESSIVE`.
   - TLS validation and cert validity checks are never relaxed or disabled.

---

## 4. Profile Envelope v2 Contract (`HKVPN-PROFILE-V2`)

Tunnel profiles contain sensitive VPN credentials (Xray UUIDs, WireGuard private keys, endpoint IPs, and routing parameters). Profiles are delivered in a tamper-proof cryptographic envelope ensuring **Confidentiality**, **Integrity**, **Server Authenticity**, and **Anti-Rollback**.

### 4.1 Envelope Wire Structure

The envelope is serialized as a JSON object:

```json
{
  "protected": "<base64url_encoded_metadata_json>",
  "wrappedKey": "<base64url_encoded_rsa_oaep_wrapped_aes_key>",
  "iv": "<base64url_encoded_12_byte_gcm_iv>",
  "ciphertext": "<base64url_encoded_gcm_ciphertext_and_tag>",
  "signature": "<base64url_encoded_server_ecdsa_signature>"
}
```

All base64url strings are unpadded (`=`).

### 4.2 Protected Metadata Schema

The `protected` string is the unpadded Base64URL encoding of the exact UTF-8 bytes of a JSON object containing:

```json
{
  "purpose": "profile",
  "schemaVersion": 2,
  "keyId": "hkvpn-profile-signer-2026-v1",
  "signatureAlgorithm": "ES256",
  "encAlgorithm": "RSA-OAEP-256+A256GCM",
  "deviceId": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "accessId": "acc_01j7b8q8m00000000000000001",
  "profileId": "prf_01j7b8q8m00000000000000002",
  "profileRevision": 4,
  "issuedAt": "2026-10-06T21:00:00Z",
  "credentialExpiresAt": "2026-10-07T21:00:00Z"
}
```

#### Metadata Field Specification:
- `purpose`: String, constant `"profile"`.
- `schemaVersion`: Integer, constant `2`.
- `keyId`: Identifier of the server signing key in the server key ring.
- `signatureAlgorithm`: Constant `"ES256"`.
- `encAlgorithm`: String enum:
  * `"RSA-OAEP-256+A256GCM"`: Modern (RSA-OAEP with SHA-256 hash and MGF1-SHA256).
  * `"RSA-OAEP+A256GCM"`: Legacy (RSA-OAEP with SHA-1 hash and MGF1-SHA1).
- `deviceId`: 64-char lowercase hex fingerprint of target device.
- `accessId`: Opaque string identifier of the active access grant.
- `profileId`: Opaque string identifier of the issued profile.
- `profileRevision`: Integer $\ge 1$, monotonically incremented per device issuance.
- `issuedAt`: ISO 8601 / RFC 3339 UTC timestamp.
- `credentialExpiresAt`: ISO 8601 / RFC 3339 UTC timestamp.

### 4.3 Encryption Specifications

1. **Payload Encryption (AES-256-GCM)**:
   - Secret Key: 32 cryptographically random bytes generated per envelope.
   - IV: 12 cryptographically random bytes (`GCM_IV_BYTES = 12`).
   - Authentication Tag: 16 bytes (`GCM_TAG_BYTES = 16`, 128-bit tag).
   - `ciphertext`: Contains `AES_GCM_ENCRYPT(plaintext) || tag` (total length is `len(plaintext) + 16`).
   - **Authenticated Additional Data (AAD)**:
     The AAD fed into the AES-GCM cipher is the UTF-8 bytes of:
     ```text
     HKVPN-PROFILE-V2\n<protected>
     ```
     where `<protected>` is the raw base64url string from the envelope.

2. **Key Wrapping (RSA-OAEP)**:
   - Modern (`RSA-OAEP-256+A256GCM`):
     * Scheme: RSAES-OAEP.
     * Hash function: `SHA-256`.
     * MGF: `MGF1` with `SHA-256`.
     * Label: Empty / default.
     * Wrapped key size: 384 bytes (for RSA-3072) or 512 bytes (for RSA-4096).
   - Legacy (`RSA-OAEP+A256GCM`):
     * Scheme: RSAES-OAEP.
     * Hash function: `SHA-1`.
     * MGF: `MGF1` with `SHA-1`.
     * Label: Empty / default.

3. **Server Signature (ECDSA P-256 / ES256)**:
   - The server signs the envelope using an ECDSA key on the NIST P-256 (`secp256r1`) curve with SHA-256.
   - **Signed Bytes Format**:
     ```text
     HKVPN-PROFILE-V2\n<protected>\n<wrappedKey>\n<iv>\n<ciphertext>\n
     ```
     * Exactly 5 lines separated by newline (`\n`, `0x0A`), terminated with a trailing newline.
   - **Signature Format**:
     * Strict ASN.1 DER-encoded ECDSA signature $(r, s)$ with low-$s$ normalization.
     * Producer MUST normalize $s$ to $\min(s, N - s)$ ($s \le \lfloor N/2 \rfloor$, secp256r1 order $N$) prior to encoding.
     * Strict verifiers enforce $s \le \lfloor N/2 \rfloor$ and reject high-$s$ malleable signatures with `ENVELOPE_SIGNATURE_INVALID`.
     * Encoded as unpadded Base64URL.

### 4.4 Client Verification & Decryption Workflow

The client MUST execute verification steps in strict sequential order, failing closed at the first error:

```
[Received Envelope]
       │
       ▼
 1. Check Server Signature (ECDSA P-256 over signed bytes) ────► FAIL ──► Reject (TAMPERED_SIGNATURE)
       │ PASS
       ▼
 2. Decode & Parse `protected` JSON Metadata
    - Verify schemaVersion == 2 and purpose == "profile"
    - Verify deviceId == local device fingerprint          ────► FAIL ──► Reject (CROSS_DEVICE_VIOLATION)
    - Verify credentialExpiresAt > now (UTC)               ────► FAIL ──► Reject (CREDENTIAL_EXPIRED)
    - Verify profileRevision >= lastStoredRevision         ────► FAIL ──► Reject (ROLLBACK_DETECTED)
    - Verify encAlgorithm matches device capability        ────► FAIL ──► Reject (ALGORITHM_DOWNGRADE)
       │ PASS
       ▼
 3. Unwrap AES-256 Key using Keystore Private Key
    (RSA-OAEP via DeviceIdentity)                          ────► FAIL ──► Reject (KEY_UNWRAP_FAILED)
       │ PASS
       ▼
 4. Decrypt AES-256-GCM Ciphertext
    (IV, Ciphertext, AAD = "HKVPN-PROFILE-V2\n" + protected) ──► FAIL ──► Reject (GCM_AUTH_FAILED)
       │ PASS
       ▼
 5. Secure Memory Handling
    - Clear AES key from memory (`key.fill(0)`)
    - Update lastStoredRevision = profileRevision
    - Activate Tunnel Profile
```

---

## 5. Token Model, Idempotency & Lost-Response Recovery

### 5.1 Token Primitives & Storage
- **Access Tokens**: 256-bit cryptographically secure random values (opaque, 32 bytes CSPRNG), transmitted as Base64URL. Lifetime: **15 minutes**.
- **Refresh Tokens**: 256-bit cryptographically secure random values, belonging to a distinct **Token Family**. Family absolute lifetime: **30 days** (no infinite sliding window).
- **Backend Storage**: Plaintext tokens are **NEVER** stored in logs, databases, or memory dumps. The database stores only `SHA256(token)`, bound to `deviceId`, `familyId`, `issuedAt`, `expiresAt`, `usedAt`, and `revokedAt`.

### 5.2 The Lost-Response Recovery Protocol

Mobile network transitions frequently result in dropped responses after the backend has committed a database transaction. To differentiate legitimate retries from replay attacks, the following state machine is enforced:

```
                     POST /v1/tokens/refresh
             (Headers: Idempotency-Key == body.clientOperationId)
                                │
                                ▼
                   Verify RequestSigner Proof
                                │
                 ┌──────────────┴──────────────┐
              Valid                          Invalid
                 │                              │
                 ▼                              ▼
      Lookup Refresh Token Hash            401 Unauthorized
                 │
       ┌─────────┴─────────────────────────────┐
   Not Found / Revoked                       Found
       │                                        │
       ▼                                        ▼
401 Invalid Token                     Token Status Check
                                                │
                 ┌──────────────────────────────┴─────────────────────────────┐
              ACTIVE                                                       USED
                 │                                                            │
                 ▼                                                            ▼
    Atomic Transaction:                                          Check clientOperationId Match
    1. Mark old token USED                                                    │
    2. Issue new Access + Refresh Token                         ┌─────────────┴─────────────┐
    3. Bind old token to clientOperationId + bodyHash       MATCH                         MISMATCH
    4. Store response in Encrypted Cache (TTL=120s)             │                             │
    5. Return 200 OK + new tokens                               ▼                             ▼
                                                       Check Body Hash Match         TOKEN REUSE DETECTED!
                                                                │                    Revoke entire Token Family!
                                                        ┌───────┴───────┐            Revoke active Access Tokens!
                                                      MATCH          MISMATCH        Return 401 TOKEN_REUSED
                                                        │               │
                                                        ▼               ▼
                                                  Check Window     409 Conflict
                                                        │
                                            ┌───────────┴───────────┐
                                      <= 120 Seconds          > 120 Seconds
                                            │                       │
                                            ▼                       ▼
                                Return Encrypted Cache      410 REFRESH_RETRY_EXPIRED
                                (NO new rotation!           (Client triggers Keystore
                                 NO family revoke!)          reauth with existing key)
```

#### Detailed Protocol Rules:
1. **Idempotency Binding**: The request header `Idempotency-Key` MUST be strictly identical to `clientOperationId` in the signed JSON body. Mismatches return `400 Bad Request`.
2. **120-Second Recovery Window**:
   - For an exact retry arriving within 120 seconds where `(deviceId, consumedToken, clientOperationId, bodyHash)` matches:
     The backend retrieves the encrypted cached response from storage and returns it.
     **No secondary rotation is performed, and no token family revocation occurs.**
3. **Token Reuse Detection**:
   - If a consumed token is presented with a **different** `clientOperationId`, the event is classified as an adversarial Token Reuse attack.
   - The backend atomically revokes the entire `familyId`, invalidates all active access tokens for that device, logs a security audit event, and returns `401 Unauthorized` (`code: "TOKEN_REUSED"`).
4. **Payload Alteration (Conflict)**:
   - If a request presents an identical `clientOperationId` but a different `bodyHash`, the backend rejects with `409 Conflict` without rotating tokens or revoking the family.
5. **Recovery Window Expiration**:
   - An exact retry arriving after 120 seconds returns `410 Gone` (`code: "REFRESH_RETRY_EXPIRED"`).
   - This prevents stale recovery while avoiding false reuse classification. The client handles this by initiating a Keystore-backed re-authentication challenge (`POST /v1/devices/challenges` with `mode=reauth`).
6. **Naturally Idempotent Device Deletion (`DELETE /v1/devices/me`)**:
   - Request has an empty body (0 bytes).
   - The `Idempotency-Key` header is **forbidden**.
   - The request is naturally idempotent: upon successful execution, the device, grants, and tokens are revoked. Repeated calls return `401 Unauthorized` or `410 Gone` without creating redundant revocation events or corrupting state.

### 5.3 Hardware-Backed Re-Authentication (`mode=reauth`)

When session refresh fails or token recovery expires (`410 REFRESH_RETRY_EXPIRED`), the client initiates hardware-backed re-authentication using its existing Keystore key.

#### A. Reauth Invariants & Transitions:
1. **Device & Grant Verification**:
   - The backend locates the existing device by its public key fingerprint (`deviceId`).
   - The backend looks up the associated grant record and validates:
     * Device status is active (not disabled or revoked).
     * Grant status is active (not revoked, suspended, or terminal).
     * Grant expiration timestamp (`expiresAt > now`).
2. **Rejection of Expired/Revoked Grants**:
   - If the grant is missing, revoked, expired, or the device is disabled/revoked, the reauth attempt is **strictly rejected** (`401 Unauthorized` or `403 Forbidden`).
   - **No new grant, device, token family, or recovery cache is created.**
3. **Grant Preservation**:
   - For an active, valid grant, the backend **preserves the identical `grantId` and original `expiresAt`**.
   - Reauth does NOT extend grant lifetime or alter the original invitation code or usage count.
4. **Atomic Token Family Rotation**:
   - In a single atomic transaction under lock, the backend:
     * Marks all previous token families for this device as revoked.
     * Revokes all active access tokens for this device.
     * Generates exactly **one new token family** bound to the existing grant.
     * Issues a fresh access token (15m TTL) and refresh token (family TTL 30 days, capped by grant `expiresAt`).
5. **Concurrency & Deadlock Safety**:
   - Mutex locks are held continuously from grant verification through token revocation and new family issuance.
   - Reauth does not invoke public enrollment logic and avoids lock-release interleavings.
6. **Lost-Response Recovery**:
   - Exact retries of `reauth` within 120 seconds return the cached recovery response without rotating another token family.

### 5.4 Persisted Idempotency & Lifecycle Decoupling

1. **Persisted Idempotency Binding**:
   - Table `operations` persists:
     * `device_id`: Owner device fingerprint.
     * `client_operation_id`: Client-supplied idempotency UUID.
     * `operation_type`: Canonical operation enum (e.g. `complete`, `refresh`, `profile_issue`).
     * `http_method`: HTTP method in uppercase.
     * `request_path`: Normalized encoded path.
     * `request_body_sha256`: 64 lowercase hex characters: `SHA256(rawRequestBodyBytes)`.
   - Unique constraint: `UNIQUE (device_id, client_operation_id)`.
   - Retries with identical parameters return the previous operation result without side-effects.
   - Any retry presenting the same `(device_id, client_operation_id)` with altered `request_body_sha256`, path, or type results in `409 Conflict`.
   - Cross-device attempts presenting another device's operation key are rejected.
2. **Atomic Recovery Cache Insertion**:
   - The recovery response is encrypted and inserted into the cache table **once, within the transaction boundary before `COMMIT`**, referencing the internal `operations.id` foreign key.
   - No post-commit cache insertions are performed.
3. **Operations Retention vs Live Token Preservation**:
   - Short-lived operation records are purged after 48 hours.
   - In `refresh_tokens`, the `issuance_operation_id` foreign key is declared `NULLABLE` with `ON DELETE SET NULL`.
   - Long-lived token records directly store `issuance_client_op_id`, `issuance_body_hash`, `consumed_by_client_op_id`, and `consumed_by_body_hash`.
   - Consequently, purging 48-hour operation records does **NOT** cascade or delete active refresh tokens (which live up to 30 days).
   - Token consumption, retry classification, and reuse detection operate reliably regardless of whether the original operation record has been purged.

---

## 6. Key Separation & Trust Boundaries

To prevent cross-protocol key substitution attacks, cryptographic keys in the Hello Kitty VPN architecture are segregated by strict trust domains:

| Key Domain | Algorithm / Parameters | Holder / Location | Purpose & Restrictions |
|:---|:---|:---|:---|
| **TLS 1.3 Transport** | X25519 / ECDSA / RSA | Reverse Proxy / Ingress | Confines channel encryption. Never used for application-level entity signing. |
| **Device Identity Key** | RSA 3072/4096 (e=65537) | Android Keystore (TEE/StrongBox) | Request signing (`PS256`/`RS256`) and profile AES key unwrapping (`RSA-OAEP`). Private key non-exportable. |
| **Server Challenge Signer** | ECDSA P-256 (`secp256r1`) | Control Plane Secret Store | Signs challenge responses (`HKVPN-CHALLENGE-V1`). Dedicated single-purpose key; key ID `hkvpn-challenge-signer-2026-v1`. |
| **Server Profile Signer** | ECDSA P-256 (`secp256r1`) | Control Plane Secret Store | Signs profile envelopes (`HKVPN-PROFILE-V2`). Dedicated single-purpose key; key ID `hkvpn-profile-signer-2026-v1`. |
| **Routing Manifest Signer** | ECDSA P-256 (`secp256r1`) | Release / Ops Management | Signs published routing rule bundles (`routing-rules-v1`). Maintained in an isolated offline/CI pipeline. |
| **Node Management (mTLS)** | Ed25519 / ECDSA P-256 | Control Plane & Nodes | Authenticates provisioning RPCs between Go control plane and node provisioners (`/internal/v2/xray/*`). |
| **Cache Storage Key** | AES-256-GCM | Control Plane Ephemeral Memory | Encrypts ephemeral cached responses in PostgreSQL (`TTL = 120s`). |
| **Credential Storage Key** | AES-256-GCM | Control Plane Secret Store | Encrypts tunnel secrets (VLESS UUIDs) at rest in PostgreSQL. Key ID `hkvpn-credential-storage-v1`. |

### 6.1 Credential At-Rest Protection Specification
1. **Encryption Scheme**:
   - Algorithm: AES-256-GCM.
   - Key: 256-bit symmetric key (`hkvpn-credential-storage-v1`), managed outside the database.
   - Nonce: 12 cryptographically random bytes generated freshly per encryption operation.
   - Tag: 16 bytes authentication tag.
2. **Authenticated Additional Data (AAD)**:
   - AAD explicitly binds the ciphertext to the exact resource context:
     ```text
     HKVPN-CREDENTIAL-V1\n<credential_id>\n<device_id>\n<node_id>\n<revision>\n<generation>
     ```
   - Any tampering with the ciphertext, tag, or context parameters causes decryption failure.
3. **Blind Index / Fingerprint**:
   - For indexed uniqueness and read-back verification without exposing raw secrets, the database stores:
     `fingerprint_sha256 = SHA256(rawSecretBytes)` (64 lowercase hex characters).
   - The plaintext VLESS UUID is never stored in table columns, database logs, or diagnostic dumps.
4. **Outbox Masking**:
   - Outbox messages contain only the `credential_id`, non-sensitive identifiers, and desired revision.
   - Provisioning workers load and decrypt the tunnel secret in ephemeral memory immediately before node RPCs.
   - Outbox payloads, event queues, and error traces never log plaintext credentials.

### Memory Hygiene & Zeroization
All cryptographic implementations (Android Kotlin, Go backend, and Python test runners) must enforce key zeroization:
- Symmetric keys (`byte[]` in Kotlin, `[]byte` in Go) must be overwritten with zeroes (`Arrays.fill(key, 0)` or explicit memory wiping) immediately after cipher initialization or execution.
- Decrypted plaintext profiles must be wiped from memory as soon as applied to the tunnel runtime.

---

## 7. Cross-Platform Compatibility Matrix

| Component | Target Runtime | Supported Algorithms | Fallback / Degradation Rules |
|:---|:---|:---|:---|
| **Android Modern** | Android 15+ (API 35+) | Signing: `PS256`<br>Encryption: `RSA-OAEP-256+A256GCM` | Hardware TEE / StrongBox backed. Default choice on new enrollments. |
| **Android Legacy** | Android 14 (API 34) | Signing: `RS256`<br>Encryption: `RSA-OAEP+A256GCM` | Selected on API 34 or if TEE fails modern parameter probe. Never upgraded without re-enrollment. |
| **Go Control Plane** | Go 1.22+ (`crypto/*`) | Verifies `PS256` & `RS256`<br>Encrypts `RSA-OAEP-256` & `RSA-OAEP`<br>Signs `ES256` | Strictly relies on Go standard library (`crypto/rsa`, `crypto/ecdsa`, `crypto/aes`, `crypto/cipher`). No third-party crypto forks. |
| **Python Probes** | Python 3.12+ (`cryptography`) | Verification & test harness for all modes | Standard reference implementation for CI/CD test gates. |

---

## 8. Summary of Rejection Codes

| Condition | HTTP Status | Problem Details Code | Next Action / Recovery |
|:---|:---:|:---|:---|
| Malformed canonical string / missing headers | `400` | `INVALID_SIGNATURE_HEADERS` | Fix client serialization. |
| Timestamp outside $\pm 120$s window | `401` | `CLOCK_SKEW_EXCEEDED` | Synchronize client clock with server time. |
| Replayed nonce within 5 minutes | `401` | `NONCE_ALREADY_USED` | Generate fresh random nonce. |
| Signature verification failure | `401` | `BAD_SIGNATURE` | Verify key pair and canonical serialization. |
| Unknown / inactive access token | `401` | `UNAUTHORIZED` | Refresh token or re-authenticate. |
| Token reuse detected (consumed token + new op) | `401` | `TOKEN_REUSED` | Family revoked; prompt user to re-enroll/reauth. |
| Expired refresh retry (> 120s) | `410` | `REFRESH_RETRY_EXPIRED` | Initiate `mode=reauth` challenge with Keystore key. |
| Idempotency payload conflict | `409` | `IDEMPOTENCY_CONFLICT` | Check client operation ID uniqueness. |
| Reauth on revoked/expired/missing grant | `401` / `403` | `GRANT_INACTIVE` | Enrollment required with valid invitation. |
| Challenge signature invalid | N/A (Client) | `CHALLENGE_SIGNATURE_INVALID` | Discard challenge; possible server spoofing. |
| Challenge request hash mismatch | N/A (Client) | `CHALLENGE_BODY_MISMATCH` | Discard challenge; response belongs to different request. |
| Challenge expired | N/A (Client) | `CHALLENGE_EXPIRED` | Request fresh challenge. |
| Excessive clock drift (> 24h) | N/A (Client) | `CLOCK_DRIFT_EXCESSIVE` | Inspect device system clock. |
| Envelope signature invalid | N/A (Client) | `ENVELOPE_SIGNATURE_INVALID` | Discard envelope; possible MITM/spoofing. |
| Envelope revision rollback | N/A (Client) | `ENVELOPE_ROLLBACK_DETECTED` | Discard envelope; stale replayed configuration. |
| Envelope device ID mismatch | N/A (Client) | `ENVELOPE_CROSS_DEVICE` | Discard envelope; foreign profile targeted at other device. |
| Envelope credentials expired | N/A (Client) | `ENVELOPE_EXPIRED` | Request new profile issuance. |

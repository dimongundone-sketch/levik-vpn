# S01-R2-C: Wire Conformance & Challenge Protocol Verification Report

- **Date**: 2026-10-07
- **Target Gate**: G01 Contract & Security Freeze
- **Scope**: Findings F08, F09, F10
- **Status**: **VERIFIED (100% PASS)**

---

## 1. Summary of Defect Fixes

| Finding | Defect Description | Contract & Wire Fix | Verification Evidence |
|---|---|---|---|
| **F08** | Signed serverTime and challenge lacked full wire schema, signature algorithm, clientNonce, canonical format, and cross-language tests. | 1. Defined canonical 9-line `HKVPN-CHALLENGE-V1` format ending with `\n`.<br>2. Added `clientNonce` (16B base64url) to `ChallengeRequest`.<br>3. Added `serverNonce` (32B), `purpose`, `signatureAlgorithm` (`ES256`), `requestBodyHash`, and strict DER low-$S$ `signature` to `ChallengeResponse` in `mobile-v1.openapi.yaml`.<br>4. Generated `contracts/challenge-vectors.json` (2 positive, 11 negative vectors). | 1. `test_challenge_probe.py` -> 100% PASS (2 positive, 11 negative).<br>2. `go test -v ./...` in `contracts/probes/crypto/go` -> 100% PASS (2 positive, 11 negative).<br>3. `conformance_test.py` suite 5 -> 100% PASS. |
| **F09** | Conflict in HTTP rejection status for late exact retry (OpenAPI said 410, security contract draft said 401). | Unified uniformly to `410 REFRESH_RETRY_EXPIRED` (Gone) across OpenAPI, security contract, state probes, and models. Added ProblemDetails example in OpenAPI. | Conformance suite validates OpenAPI schema and examples; state probe tests exact boundary (elapsed < 120s vs >= 120s). |
| **F10** | Fake profile envelope example in OpenAPI (`{"purpose":"profile","schemaVersion":2}`) with dummy signature; conformance probe only verified outer string types without decrypting. | 1. Replaced OpenAPI `/v1/profiles/{id}` example with authentic, cryptographically verified `pos_01_modern_oaep_sha256` envelope.<br>2. Expanded `conformance_test.py` to decrypt all positive envelopes (RSA-OAEP unwrap + AES-256-GCM decrypt with AAD), validate decrypted payload against Draft 2020-12 schema, verify metadata-payload parity, and verify all 11 negative envelope vectors.<br>3. Added regression test ensuring the incomplete/fake envelope example is strictly rejected. | `conformance_test.py` suites 3 & 6 -> 100% PASS. Decryption, schema, semantics, and negative vectors all verified. |

---

## 2. Test Execution Logs

### A. Python Conformance Suite (`contracts/probes/conformance/conformance_test.py`)
```text
=================================================================
 HELLO KITTY VPN — CONTRACT CONFORMANCE & VALIDATION SUITE
=================================================================
[1/6] Validating JSON Schemas against Draft 2020-12 meta-schema...
  [PASS] profile-v2.schema.json conforms to Draft 2020-12 meta-schema
  [PASS] routing-rules-v1.schema.json conforms to Draft 2020-12 meta-schema

[2/6] Validating OpenAPI 3.1.0 specifications and $ref resolution...
  [PASS] mobile-v1.openapi.yaml: Valid structure and 21 schemas resolved
  [PASS] node-xray-v2.openapi.yaml: Valid structure and 9 schemas resolved

[3/6] Validating Profile Envelope v2 vectors (decryption, schema, semantics, and negative vectors)...
  [PASS] pos_01_modern_oaep_sha256: Fully decrypted, verified and bound to metadata
  [PASS] pos_02_legacy_oaep_sha1: Fully decrypted, verified and bound to metadata
  [PASS] neg_01_tampered_ciphertext: Correctly rejected with GCM_AUTH_FAILED
  [PASS] neg_02_tampered_iv: Correctly rejected with ENVELOPE_SIGNATURE_INVALID
  [PASS] neg_03_tampered_metadata_aad: Correctly rejected with ENVELOPE_SIGNATURE_INVALID
  [PASS] neg_04_tampered_wrapped_key: Correctly rejected with KEY_UNWRAP_FAILED
  [PASS] neg_05_cross_device_mismatch: Correctly rejected with CROSS_DEVICE_VIOLATION
  [PASS] neg_06_expired_credential: Correctly rejected with CREDENTIAL_EXPIRED
  [PASS] neg_07_rollback_revision: Correctly rejected with ROLLBACK_DETECTED
  [PASS] neg_08_bad_server_signature: Correctly rejected with ENVELOPE_SIGNATURE_INVALID
  [PASS] neg_09_unauthorized_signing_key: Correctly rejected with UNTRUSTED_KEY_SIGNATURE
  [PASS] neg_10_missing_signature: Correctly rejected with MISSING_SIGNATURE
  [PASS] neg_11_high_s_signature: Correctly rejected with ENVELOPE_SIGNATURE_INVALID

[4/6] Validating RequestSigner v1 vectors against canonical spec...
  [PASS] pos_01_ps256_with_token_json_body: Canonical serialization verified
  [PASS] pos_02_ps256_no_token_json_body: Canonical serialization verified
  [PASS] pos_03_ps256_empty_body_with_token: Canonical serialization verified
  [PASS] pos_04_ps256_empty_body_no_token: Canonical serialization verified
  [PASS] pos_05_ps256_percent_encoded_path: Canonical serialization verified
  [PASS] pos_06_rs256_with_token_json_body: Canonical serialization verified
  [PASS] pos_07_rs256_no_token_json_body: Canonical serialization verified
  [PASS] pos_08_rs256_empty_body_with_token: Canonical serialization verified
  [PASS] pos_09_rs256_empty_body_no_token: Canonical serialization verified
  [PASS] pos_10_ps256_rsa4096_key: Canonical serialization verified

[5/6] Validating HKVPN-CHALLENGE-V1 vectors (canonical bytes, ES256 low-S, time bounds)...
  [PASS] pos_01_enroll_challenge: Validated successfully
  [PASS] pos_02_reauth_challenge: Validated successfully
  [PASS] neg_01_tampered_signature: Correctly rejected with CHALLENGE_SIGNATURE_INVALID
  [PASS] neg_02_high_s_signature: Correctly rejected with CHALLENGE_SIGNATURE_INVALID
  [PASS] neg_03_tampered_request_body_hash: Correctly rejected with REQUEST_BODY_HASH_MISMATCH
  [PASS] neg_04_tampered_server_time: Correctly rejected with CHALLENGE_SIGNATURE_INVALID
  [PASS] neg_05_excessive_clock_drift: Correctly rejected with CLOCK_DRIFT_EXCESSIVE
  [PASS] neg_06_expired_challenge: Correctly rejected with CHALLENGE_EXPIRED
  [PASS] neg_07_untrusted_signing_key: Correctly rejected with UNAUTHORIZED_SIGNING_KEY
  [PASS] neg_08_invalid_canonical_prefix: Correctly rejected with CHALLENGE_SIGNATURE_INVALID
  [PASS] neg_09_invalid_client_nonce_length: Correctly rejected with INVALID_NONCE_LENGTH
  [PASS] neg_10_missing_signature: Correctly rejected with MISSING_SIGNATURE
  [PASS] neg_11_invalid_purpose: Correctly rejected with INVALID_PURPOSE

[6/6] Validating OpenAPI examples (profile envelope, fake regression, challenge schemas)...
  [PASS] /v1/profiles/{id} example envelope decrypted and validated 100%
  [PASS] F10 regression test: Fake/incomplete envelope example successfully rejected
  [PASS] /v1/devices/challenges request example 'enrollment' conforms to ChallengeRequest
  [PASS] /v1/devices/challenges request example 'reauthentication' conforms to ChallengeRequest
  [PASS] /v1/devices/challenges response example conforms to ChallengeResponse

=================================================================
 [ALL 6 CONFORMANCE SUITES PASSED 100% COMPLIANT] 
=================================================================
```

### B. Go Challenge Protocol Tests (`contracts/probes/crypto/go/challenge_probe_test.go`)
```text
=== RUN   TestChallengeProtocolVectors
=== RUN   TestChallengeProtocolVectors/PositiveVectors
=== RUN   TestChallengeProtocolVectors/PositiveVectors/pos_01_enroll_challenge
=== RUN   TestChallengeProtocolVectors/PositiveVectors/pos_02_reauth_challenge
=== RUN   TestChallengeProtocolVectors/NegativeVectors
=== RUN   TestChallengeProtocolVectors/NegativeVectors/neg_01_tampered_signature
=== RUN   TestChallengeProtocolVectors/NegativeVectors/neg_02_high_s_signature
=== RUN   TestChallengeProtocolVectors/NegativeVectors/neg_03_tampered_request_body_hash
=== RUN   TestChallengeProtocolVectors/NegativeVectors/neg_04_tampered_server_time
=== RUN   TestChallengeProtocolVectors/NegativeVectors/neg_05_excessive_clock_drift
=== RUN   TestChallengeProtocolVectors/NegativeVectors/neg_06_expired_challenge
=== RUN   TestChallengeProtocolVectors/NegativeVectors/neg_07_untrusted_signing_key
=== RUN   TestChallengeProtocolVectors/NegativeVectors/neg_08_invalid_canonical_prefix
=== RUN   TestChallengeProtocolVectors/NegativeVectors/neg_09_invalid_client_nonce_length
=== RUN   TestChallengeProtocolVectors/NegativeVectors/neg_10_missing_signature
=== RUN   TestChallengeProtocolVectors/NegativeVectors/neg_11_invalid_purpose
--- PASS: TestChallengeProtocolVectors (0.01s)
PASS
ok  	org.hellokittyvpn/contracts/probes/crypto	1.330s
```

---

## 3. Files Created / Modified

- [`contracts/mobile-v1.openapi.yaml`](file:///root/projects/hellokittyvpn/contracts/mobile-v1.openapi.yaml):
  - Updated `ChallengeRequest` schema and examples with `clientNonce` (16 bytes base64url).
  - Updated `ChallengeResponse` schema and example with `serverNonce`, `purpose`, `signatureAlgorithm`, `requestBodyHash`, and `signature`.
  - Added 410 example for `REFRESH_RETRY_EXPIRED` to `/v1/tokens/refresh`.
  - Replaced fake envelope example with verified `pos_01_modern_oaep_sha256` envelope.
- [`contracts/challenge-vectors.json`](file:///root/projects/hellokittyvpn/contracts/challenge-vectors.json): 2 positive and 11 negative challenge vectors.
- [`contracts/probes/crypto/generate_challenge_vectors.py`](file:///root/projects/hellokittyvpn/contracts/probes/crypto/generate_challenge_vectors.py): Vector generation script.
- [`contracts/probes/crypto/test_challenge_probe.py`](file:///root/projects/hellokittyvpn/contracts/probes/crypto/test_challenge_probe.py): Python challenge verification probe.
- [`contracts/probes/crypto/go/challenge_probe_test.go`](file:///root/projects/hellokittyvpn/contracts/probes/crypto/go/challenge_probe_test.go): Go challenge verification probe.
- [`contracts/probes/conformance/conformance_test.py`](file:///root/projects/hellokittyvpn/contracts/probes/conformance/conformance_test.py): Complete 6-part cross-contract conformance suite.

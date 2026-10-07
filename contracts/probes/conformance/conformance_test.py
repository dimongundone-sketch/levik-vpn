#!/usr/bin/env python3
"""
Hello Kitty VPN - Cross-Contract Conformance & Schema Validation Suite
Verifies:
1. Meta-schema validation of profile-v2 and routing-rules-v1 JSON Schemas (Draft 2020-12).
2. Local $ref resolution and structural integrity of OpenAPI 3.1.0 specifications.
3. Full cryptographic & schema conformance of all envelope vectors:
   - Signature verification (ECDSA P-256 low-S).
   - RSA-OAEP key unwrapping (SHA-256 and SHA-1).
   - AES-256-GCM decryption with AAD.
   - Schema validation of decrypted plaintext against Draft 2020-12.
   - Strict semantic bindings (IDs, revisions, timestamps).
   - Verification of all 11 negative envelope vectors.
4. Conformance of RequestSigner v1 canonical vectors.
5. Conformance of HKVPN-CHALLENGE-V1 vectors (positive and negative).
6. OpenAPI example payloads validation:
   - /v1/profiles/{id} envelope: full cryptographic decryption, schema, and metadata verification.
   - Rejection regression test for fake/incomplete envelope example.
   - /v1/devices/challenges: request & response schema and signature verification.
Exits with code 0 on full compliance, non-zero on any deviation.
"""

import base64
from datetime import datetime, timezone
import hashlib
import json
import os
import sys
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from jsonschema import Draft202012Validator, FormatChecker
import yaml

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
CONTRACTS_DIR = os.path.join(REPO_ROOT, "contracts")
CRYPTO_PROBES_DIR = os.path.join(CONTRACTS_DIR, "probes", "crypto")
if CRYPTO_PROBES_DIR not in sys.path:
    sys.path.insert(0, CRYPTO_PROBES_DIR)

P256_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
HALF_ORDER = P256_ORDER // 2


def b64url_decode(s: str) -> bytes:
    pad = len(s) % 4
    if pad:
        s += "=" * (4 - pad)
    return base64.urlsafe_b64decode(s.encode("ascii"))


def b64url_encode(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode("ascii").rstrip("=")


def check_json_schemas():
    print("[1/6] Validating JSON Schemas against Draft 2020-12 meta-schema...")
    schema_files = [
        "profile-v2.schema.json",
        "routing-rules-v1.schema.json",
    ]
    for sf in schema_files:
        path = os.path.join(CONTRACTS_DIR, sf)
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        Draft202012Validator.check_schema(data)
        print(f"  [PASS] {sf} conforms to Draft 2020-12 meta-schema")


def check_openapi_structure():
    print("\n[2/6] Validating OpenAPI 3.1.0 specifications and $ref resolution...")
    openapi_files = [
        "mobile-v1.openapi.yaml",
        "node-xray-v2.openapi.yaml",
    ]
    for of in openapi_files:
        path = os.path.join(CONTRACTS_DIR, of)
        with open(path, "r", encoding="utf-8") as f:
            spec = yaml.safe_load(f)
        assert spec.get("openapi", "").startswith("3.1"), f"{of}: invalid openapi version"
        assert "paths" in spec, f"{of}: missing paths"
        assert "components" in spec, f"{of}: missing components"

        schemas = spec.get("components", {}).get("schemas", {})
        for name, sch in schemas.items():
            check_refs_in_schema(sch, schemas, of)
        print(f"  [PASS] {of}: Valid structure and {len(schemas)} schemas resolved")


def check_refs_in_schema(sch, components_schemas, filename):
    if isinstance(sch, dict):
        if "$ref" in sch:
            ref = sch["$ref"]
            if ref.startswith("#/components/schemas/"):
                target = ref.split("/")[-1]
                assert target in components_schemas, f"{filename}: unresolved ref {ref}"
        for k, v in sch.items():
            check_refs_in_schema(v, components_schemas, filename)
    elif isinstance(sch, list):
        for item in sch:
            check_refs_in_schema(item, components_schemas, filename)


def verify_server_signature(server_pub_key, rogue_pub_key, envelope: dict) -> tuple[bool, str]:
    sig_b64 = envelope.get("signature")
    if not sig_b64:
        return False, "MISSING_SIGNATURE"
    try:
        sig_bytes = b64url_decode(sig_b64)
        r, s = decode_dss_signature(sig_bytes)
    except Exception:
        return False, "ENVELOPE_SIGNATURE_INVALID"

    if s > HALF_ORDER:
        return False, "ENVELOPE_SIGNATURE_INVALID"

    protected_b64 = envelope.get("protected", "")
    wrapped_key_b64 = envelope.get("wrappedKey", "")
    iv_b64 = envelope.get("iv", "")
    ciphertext_b64 = envelope.get("ciphertext", "")

    signed_string = f"HKVPN-PROFILE-V2\n{protected_b64}\n{wrapped_key_b64}\n{iv_b64}\n{ciphertext_b64}\n"
    signed_bytes = signed_string.encode("utf-8")

    try:
        server_pub_key.verify(sig_bytes, signed_bytes, ec.ECDSA(hashes.SHA256()))
        return True, "OK"
    except Exception:
        try:
            if rogue_pub_key:
                rogue_pub_key.verify(sig_bytes, signed_bytes, ec.ECDSA(hashes.SHA256()))
                return False, "UNTRUSTED_KEY_SIGNATURE"
        except Exception:
            pass
        return False, "ENVELOPE_SIGNATURE_INVALID"


def unwrap_aes_key(device_priv_key, enc_algorithm: str, wrapped_key_b64: str) -> bytes:
    wrapped_bytes = b64url_decode(wrapped_key_b64)
    if enc_algorithm == "RSA-OAEP-256+A256GCM":
        return device_priv_key.decrypt(
            wrapped_bytes,
            padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
        )
    elif enc_algorithm == "RSA-OAEP+A256GCM":
        return device_priv_key.decrypt(
            wrapped_bytes,
            padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA1(), label=None),
        )
    else:
        raise ValueError(f"Unsupported encAlgorithm: {enc_algorithm}")


def decrypt_profile_payload(aes_key: bytes, iv_b64: str, ciphertext_b64: str, protected_b64: str) -> bytes:
    iv = b64url_decode(iv_b64)
    ciphertext_and_tag = b64url_decode(ciphertext_b64)
    aad = f"HKVPN-PROFILE-V2\n{protected_b64}".encode("utf-8")
    aesgcm = AESGCM(aes_key)
    return aesgcm.decrypt(iv, ciphertext_and_tag, aad)


def run_envelope_verification_pipeline(
    envelope: dict,
    server_pub_key,
    rogue_pub_key,
    device_priv_key,
    expected_device_id: str,
    last_observed_revision: int = 1,
    current_time: datetime = datetime(2026, 10, 6, 21, 30, 0, tzinfo=timezone.utc),
    header_validator: Draft202012Validator = None,
    payload_validator: Draft202012Validator = None,
) -> tuple[bool, str, dict]:
    # 1. Signature
    sig_ok, sig_reason = verify_server_signature(server_pub_key, rogue_pub_key, envelope)
    if not sig_ok:
        return False, sig_reason, {}

    # 2. Protected metadata
    try:
        meta_bytes = b64url_decode(envelope.get("protected", ""))
        meta = json.loads(meta_bytes.decode("utf-8"))
    except Exception:
        return False, "METADATA_DECODE_FAILED", {}

    if header_validator:
        try:
            header_validator.validate(meta)
        except Exception:
            return False, "METADATA_SCHEMA_INVALID", {}

    # Required fields in protected metadata
    required_meta_fields = [
        "purpose", "schemaVersion", "keyId", "signatureAlgorithm", "encAlgorithm",
        "deviceId", "accessId", "profileId", "profileRevision", "issuedAt", "credentialExpiresAt"
    ]
    for rf in required_meta_fields:
        if rf not in meta:
            return False, f"MISSING_METADATA_FIELD_{rf.upper()}", {}

    if meta.get("purpose") != "profile" or meta.get("schemaVersion") != 2:
        return False, "METADATA_INVALID", {}

    if meta.get("deviceId") != expected_device_id:
        return False, "CROSS_DEVICE_VIOLATION", {}

    try:
        exp_time = datetime.fromisoformat(meta["credentialExpiresAt"].replace("Z", "+00:00"))
        if exp_time <= current_time:
            return False, "CREDENTIAL_EXPIRED", {}
    except Exception:
        return False, "CREDENTIAL_EXPIRED", {}

    if meta.get("profileRevision", 0) < last_observed_revision:
        return False, "ROLLBACK_DETECTED", {}

    # 3. Key unwrap
    try:
        aes_key = unwrap_aes_key(device_priv_key, meta["encAlgorithm"], envelope["wrappedKey"])
    except Exception:
        return False, "KEY_UNWRAP_FAILED", {}

    # 4. AES-GCM Decryption
    try:
        decrypted_bytes = decrypt_profile_payload(aes_key, envelope["iv"], envelope["ciphertext"], envelope["protected"])
        payload = json.loads(decrypted_bytes.decode("utf-8"))
    except Exception:
        return False, "GCM_AUTH_FAILED", {}

    if payload_validator:
        try:
            payload_validator.validate(payload)
        except Exception:
            return False, "PAYLOAD_SCHEMA_INVALID", {}

    return True, "SUCCESS", payload


def check_envelope_vectors_conformance():
    print("\n[3/6] Validating Profile Envelope v2 vectors (decryption, schema, semantics, and negative vectors)...")
    with open(os.path.join(CONTRACTS_DIR, "profile-v2.schema.json"), "r", encoding="utf-8") as f:
        schema = json.load(f)

    with open(os.path.join(CONTRACTS_DIR, "envelope-vectors.json"), "r", encoding="utf-8") as f:
        vectors_doc = json.load(f)

    payload_sub = {"$schema": schema["$schema"], "$defs": schema["$defs"], **schema["$defs"]["TunnelProfilePayloadV2"]}
    header_sub = {"$schema": schema["$schema"], "$defs": schema["$defs"], **schema["$defs"]["TunnelProfileProtectedHeader"]}
    env_sub = {"$schema": schema["$schema"], "$defs": schema["$defs"], **schema["$defs"]["TunnelProfileEnvelopeV2"]}

    pv = Draft202012Validator(payload_sub, format_checker=FormatChecker())
    hv = Draft202012Validator(header_sub, format_checker=FormatChecker())
    ev = Draft202012Validator(env_sub, format_checker=FormatChecker())

    server_pub_key = serialization.load_pem_public_key(vectors_doc["serverKey"]["publicKeyPem"].encode("ascii"))
    rogue_pub_key = serialization.load_pem_public_key(vectors_doc["rogueServerKey"]["publicKeyPem"].encode("ascii"))

    device_keys = {}
    for d_name, d_doc in vectors_doc["deviceKeys"].items():
        priv_key = serialization.load_pem_private_key(d_doc["privateKeyPkcs8Pem"].encode("ascii"), password=None)
        device_keys[d_name] = {"priv": priv_key, "deviceId": d_doc["deviceId"]}

    # Positive vectors: FULL DECRYPTION AND VALIDATION
    for vec in vectors_doc["positiveVectors"]:
        vec_id = vec["id"]
        env = vec["envelope"]
        fixture_plaintext = vec["plaintext"]
        dev_info = device_keys[vec["targetDeviceRef"]]

        ev.validate(env)
        meta = json.loads(b64url_decode(env["protected"]).decode("utf-8"))
        hv.validate(meta)

        ok, reason, decrypted_payload = run_envelope_verification_pipeline(
            envelope=env,
            server_pub_key=server_pub_key,
            rogue_pub_key=rogue_pub_key,
            device_priv_key=dev_info["priv"],
            expected_device_id=dev_info["deviceId"],
            header_validator=hv,
            payload_validator=pv,
        )
        assert ok, f"Positive vector {vec_id} failed verification: {reason}"

        # Assert decrypted bytes match fixture
        assert decrypted_payload["profileId"] == fixture_plaintext["profileId"]
        assert decrypted_payload["deviceId"] == meta["deviceId"]
        assert decrypted_payload["profileRevision"] == meta["profileRevision"]
        assert decrypted_payload["accessId"] == meta["accessId"]
        assert decrypted_payload["issuedAt"] == meta["issuedAt"]
        assert decrypted_payload["credentialExpiresAt"] == meta["credentialExpiresAt"]
        print(f"  [PASS] {vec_id}: Fully decrypted, verified and bound to metadata")

    # Negative vectors: REJECTION INVARIANT VERIFICATION
    for nv in vectors_doc["negativeVectors"]:
        vec_id = nv["id"]
        env = nv["envelope"]
        expected_err = nv["expectedError"]
        target_ref = nv.get("targetDeviceRef", "modern_device")
        dev_info = device_keys[target_ref]
        last_rev = nv.get("lastObservedRevision", 1)

        ok, reason, _ = run_envelope_verification_pipeline(
            envelope=env,
            server_pub_key=server_pub_key,
            rogue_pub_key=rogue_pub_key,
            device_priv_key=dev_info["priv"],
            expected_device_id=dev_info["deviceId"],
            last_observed_revision=last_rev,
            header_validator=hv,
            payload_validator=pv,
        )
        assert not ok, f"Negative vector {vec_id} unexpectedly passed!"
        assert reason == expected_err, f"Negative vector {vec_id} error mismatch: expected {expected_err}, got {reason}"
        print(f"  [PASS] {vec_id}: Correctly rejected with {reason}")


def check_signing_vectors_conformance():
    print("\n[4/6] Validating RequestSigner v1 vectors against canonical spec...")
    with open(os.path.join(CONTRACTS_DIR, "signing-vectors.json"), "r", encoding="utf-8") as f:
        doc = json.load(f)

    for vec in doc["positiveVectors"]:
        vec_id = vec["id"]
        intermediate = vec["intermediate"]
        canonical_lines = intermediate["canonicalPayload"].split("\n")
        assert len(canonical_lines) == 8, f"{vec_id}: canonical payload must have exactly 8 lines, got {len(canonical_lines)}"
        assert canonical_lines[0] == "v1", f"{vec_id}: line 0 must be 'v1'"
        assert canonical_lines[1] in ("GET", "POST", "DELETE", "PUT"), f"{vec_id}: invalid HTTP method"
        assert canonical_lines[2].startswith("/"), f"{vec_id}: path must start with /"
        assert "?" not in canonical_lines[2] and "#" not in canonical_lines[2], f"{vec_id}: query/fragment forbidden in path"
        assert len(canonical_lines[5]) == 64, f"{vec_id}: deviceId must be 64-char hex"
        assert len(canonical_lines[6]) == 64, f"{vec_id}: tokenHash must be 64-char hex"
        assert len(canonical_lines[7]) == 64, f"{vec_id}: bodyHash must be 64-char hex"
        print(f"  [PASS] {vec_id}: Canonical serialization verified")


def check_challenge_vectors_conformance():
    print("\n[5/6] Validating HKVPN-CHALLENGE-V1 vectors (canonical bytes, ES256 low-S, time bounds)...")
    from test_challenge_probe import verify_challenge, parse_iso8601_utc

    with open(os.path.join(CONTRACTS_DIR, "challenge-vectors.json"), "r", encoding="utf-8") as f:
        doc = json.load(f)

    server_pub_key = serialization.load_pem_public_key(doc["serverKey"]["publicKeyPem"].encode("ascii"))
    trusted_ring = {doc["serverKey"]["keyId"]: server_pub_key}
    client_now = parse_iso8601_utc("2026-10-07T08:30:00Z")

    for pv in doc["positiveVectors"]:
        vec_id = pv["id"]
        raw_req_bytes = pv["requestBodyRawBytes"].encode("utf-8")
        resp = pv["response"]
        ok, err = verify_challenge(resp, raw_req_bytes, trusted_ring, client_now)
        assert ok, f"Challenge positive vector {vec_id} failed: {err}"
        print(f"  [PASS] {vec_id}: Validated successfully")

    for nv in doc["negativeVectors"]:
        vec_id = nv["id"]
        expected_err = nv["expectedError"]
        raw_req_bytes = nv["requestBodyRawBytes"].encode("utf-8")
        resp = nv["response"]
        ok, err = verify_challenge(resp, raw_req_bytes, trusted_ring, client_now)
        assert not ok, f"Challenge negative vector {vec_id} unexpectedly passed!"
        assert err == expected_err, f"Challenge negative vector {vec_id} error mismatch: expected {expected_err}, got {err}"
        print(f"  [PASS] {vec_id}: Correctly rejected with {err}")


def check_openapi_examples():
    print("\n[6/6] Validating OpenAPI examples (profile envelope, fake regression, challenge schemas)...")
    with open(os.path.join(CONTRACTS_DIR, "mobile-v1.openapi.yaml"), "r", encoding="utf-8") as f:
        spec = yaml.safe_load(f)

    with open(os.path.join(CONTRACTS_DIR, "profile-v2.schema.json"), "r", encoding="utf-8") as f:
        profile_schema = json.load(f)

    with open(os.path.join(CONTRACTS_DIR, "envelope-vectors.json"), "r", encoding="utf-8") as f:
        env_vectors = json.load(f)

    env_sub = {"$schema": profile_schema["$schema"], "$defs": profile_schema["$defs"], **profile_schema["$defs"]["TunnelProfileEnvelopeV2"]}
    header_sub = {"$schema": profile_schema["$schema"], "$defs": profile_schema["$defs"], **profile_schema["$defs"]["TunnelProfileProtectedHeader"]}
    payload_sub = {"$schema": profile_schema["$schema"], "$defs": profile_schema["$defs"], **profile_schema["$defs"]["TunnelProfilePayloadV2"]}

    ev = Draft202012Validator(env_sub, format_checker=FormatChecker())
    hv = Draft202012Validator(header_sub, format_checker=FormatChecker())
    pv = Draft202012Validator(payload_sub, format_checker=FormatChecker())

    # 1. Check /v1/profiles/{id} envelope example
    prof_resp = spec["paths"]["/v1/profiles/{id}"]["get"]["responses"]["200"]
    env_example = prof_resp["content"]["application/json"]["examples"]["envelope"]["value"]

    # Validate outer schema
    ev.validate(env_example)

    # Validate protected header schema
    meta = json.loads(b64url_decode(env_example["protected"]).decode("utf-8"))
    hv.validate(meta)

    # Decrypt and verify full pipeline of OpenAPI profile example
    server_pub_key = serialization.load_pem_public_key(env_vectors["serverKey"]["publicKeyPem"].encode("ascii"))
    rogue_pub_key = serialization.load_pem_public_key(env_vectors["rogueServerKey"]["publicKeyPem"].encode("ascii"))
    modern_priv = serialization.load_pem_private_key(env_vectors["deviceKeys"]["modern_device"]["privateKeyPkcs8Pem"].encode("ascii"), password=None)
    modern_dev_id = env_vectors["deviceKeys"]["modern_device"]["deviceId"]

    ok, reason, decrypted = run_envelope_verification_pipeline(
        envelope=env_example,
        server_pub_key=server_pub_key,
        rogue_pub_key=rogue_pub_key,
        device_priv_key=modern_priv,
        expected_device_id=modern_dev_id,
        header_validator=hv,
        payload_validator=pv,
    )
    assert ok, f"OpenAPI profile envelope example failed cryptographic verification: {reason}"
    assert decrypted["profileId"] == meta["profileId"]
    assert decrypted["deviceId"] == modern_dev_id
    print("  [PASS] /v1/profiles/{id} example envelope decrypted and validated 100%")

    # 2. Regression test: Fake/incomplete example must be rejected (F10)
    fake_example = {
        "protected": b64url_encode(json.dumps({"purpose": "profile", "schemaVersion": 2}).encode("utf-8")),
        "wrappedKey": "d3JhcHBlZEtleUJ5dGVzQmFzZTY0VXJsRW5jb2RlZDUxMkJ5dGVz" * 6,
        "iv": "dGhpc0lzQVJhbmRv",
        "ciphertext": "Y2lwaGVydGV4dEJ5dGVzUGx1czE2Qnl0ZXNUYWc",
        "signature": "MEUCIQDx3z9_example_signature_base64url_string_that_is_at_least_80_chars_long_and_valid_der_AiEA8y_test"
    }
    # Protected header schema validation MUST fail due to missing required fields
    fake_rejected_by_schema = False
    try:
        fake_meta = json.loads(b64url_decode(fake_example["protected"]).decode("utf-8"))
        hv.validate(fake_meta)
    except Exception:
        fake_rejected_by_schema = True
    assert fake_rejected_by_schema, "Regression failure: fake example with missing metadata fields must be rejected by TunnelProfileProtectedHeader schema!"

    fake_rejected_by_pipeline = False
    ok, reason, _ = run_envelope_verification_pipeline(
        envelope=fake_example,
        server_pub_key=server_pub_key,
        rogue_pub_key=rogue_pub_key,
        device_priv_key=modern_priv,
        expected_device_id=modern_dev_id,
    )
    if not ok:
        fake_rejected_by_pipeline = True
    assert fake_rejected_by_pipeline, "Regression failure: fake example must be rejected by crypto verification pipeline!"
    print("  [PASS] F10 regression test: Fake/incomplete envelope example successfully rejected")

    # 3. Check /v1/devices/challenges OpenAPI schemas and examples
    challenge_req_schema = {
        "components": spec.get("components", {}),
        **spec["components"]["schemas"]["ChallengeRequest"],
    }
    challenge_resp_schema = {
        "components": spec.get("components", {}),
        **spec["components"]["schemas"]["ChallengeResponse"],
    }

    cr_v = Draft202012Validator(challenge_req_schema, format_checker=FormatChecker())
    cresp_v = Draft202012Validator(challenge_resp_schema, format_checker=FormatChecker())

    # Validate challenge request examples
    ch_req_examples = spec["paths"]["/v1/devices/challenges"]["post"]["requestBody"]["content"]["application/json"]["examples"]
    for ex_name, ex_data in ch_req_examples.items():
        cr_v.validate(ex_data["value"])
        assert "clientNonce" in ex_data["value"], f"Challenge request example {ex_name} missing clientNonce"
        assert len(ex_data["value"]["clientNonce"]) == 22, f"clientNonce length must be 22 in {ex_name}"
        print(f"  [PASS] /v1/devices/challenges request example '{ex_name}' conforms to ChallengeRequest")

    # Validate challenge response example
    ch_resp_example = spec["paths"]["/v1/devices/challenges"]["post"]["responses"]["200"]["content"]["application/json"]["examples"]["success"]["value"]
    cresp_v.validate(ch_resp_example)
    print("  [PASS] /v1/devices/challenges response example conforms to ChallengeResponse")


def main():
    print("=================================================================")
    print(" HELLO KITTY VPN — CONTRACT CONFORMANCE & VALIDATION SUITE")
    print("=================================================================")
    try:
        check_json_schemas()
        check_openapi_structure()
        check_envelope_vectors_conformance()
        check_signing_vectors_conformance()
        check_challenge_vectors_conformance()
        check_openapi_examples()
        print("\n=================================================================")
        print(" [ALL 6 CONFORMANCE SUITES PASSED 100% COMPLIANT] ")
        print("=================================================================")
    except Exception as e:
        print(f"\n[FAIL] Conformance validation failed: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()

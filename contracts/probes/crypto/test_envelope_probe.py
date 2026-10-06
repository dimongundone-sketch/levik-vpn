#!/usr/bin/env python3
"""
Hello Kitty VPN - Profile Envelope v2 Verification Probe (Python)
Validates positive and negative golden vectors from contracts/envelope-vectors.json.
Enforces strict verification pipeline without bypassing:
1. Server signature verification (ECDSA P-256 / SHA-256 with strict DER and low-S normalization).
2. Protected metadata validation (schemaVersion, purpose, deviceId, expiry, anti-rollback).
3. RSA-OAEP key unwrapping (SHA-256 modern, SHA-1 legacy).
4. AES-256-GCM payload decryption with authenticated additional data (AAD).
5. Schema compliance and semantic binding checks (matching IDs between header and decrypted payload).
"""

import base64
import json
import os
import sys
from datetime import datetime, timezone
from cryptography.hazmat.primitives.asymmetric import padding, ec, rsa
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature, encode_dss_signature
from jsonschema import Draft202012Validator, FormatChecker

CURVE_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
HALF_ORDER = CURVE_ORDER // 2


def b64url_decode(s: str) -> bytes:
    pad = len(s) % 4
    if pad:
        s += '=' * (4 - pad)
    return base64.urlsafe_b64decode(s.encode('ascii'))


def b64url_encode(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode('ascii').rstrip('=')


def verify_server_signature_pipeline(server_pub_key, rogue_pub_key, envelope: dict) -> tuple[bool, str]:
    sig_b64 = envelope.get("signature")
    if not sig_b64:
        return False, "MISSING_SIGNATURE"
    try:
        sig_bytes = b64url_decode(sig_b64)
        r, s = decode_dss_signature(sig_bytes)
    except Exception:
        return False, "ENVELOPE_SIGNATURE_INVALID"

    # Enforce strict canonical low-S normalization
    if s > HALF_ORDER:
        return False, "ENVELOPE_SIGNATURE_INVALID"

    protected_b64 = envelope.get("protected", "")
    wrapped_key_b64 = envelope.get("wrappedKey", "")
    iv_b64 = envelope.get("iv", "")
    ciphertext_b64 = envelope.get("ciphertext", "")

    signed_string = f"HKVPN-PROFILE-V2\n{protected_b64}\n{wrapped_key_b64}\n{iv_b64}\n{ciphertext_b64}\n"
    signed_bytes = signed_string.encode('utf-8')

    try:
        server_pub_key.verify(sig_bytes, signed_bytes, ec.ECDSA(hashes.SHA256()))
        return True, "OK"
    except Exception:
        try:
            rogue_pub_key.verify(sig_bytes, signed_bytes, ec.ECDSA(hashes.SHA256()))
            return False, "UNTRUSTED_KEY_SIGNATURE"
        except Exception:
            return False, "ENVELOPE_SIGNATURE_INVALID"


def unwrap_aes_key(device_priv_key, enc_algorithm: str, wrapped_key_b64: str) -> bytes:
    wrapped_bytes = b64url_decode(wrapped_key_b64)
    if enc_algorithm == "RSA-OAEP-256+A256GCM":
        return device_priv_key.decrypt(
            wrapped_bytes,
            padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None)
        )
    elif enc_algorithm == "RSA-OAEP+A256GCM":
        return device_priv_key.decrypt(
            wrapped_bytes,
            padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA1(), label=None)
        )
    else:
        raise ValueError(f"Unsupported encAlgorithm: {enc_algorithm}")


def decrypt_profile_payload(aes_key: bytes, iv_b64: str, ciphertext_b64: str, protected_b64: str) -> bytes:
    iv = b64url_decode(iv_b64)
    ciphertext_and_tag = b64url_decode(ciphertext_b64)
    aad = f"HKVPN-PROFILE-V2\n{protected_b64}".encode('utf-8')

    aesgcm = AESGCM(aes_key)
    return aesgcm.decrypt(iv, ciphertext_and_tag, aad)


def run_pipeline(
    envelope: dict,
    server_pub_key,
    rogue_pub_key,
    device_priv_key,
    device_id: str,
    last_observed_revision: int = 1,
    current_time: datetime = datetime(2026, 10, 6, 21, 30, 0, tzinfo=timezone.utc),
    payload_validator: Draft202012Validator = None,
    header_validator: Draft202012Validator = None
) -> tuple[bool, str, dict]:
    # Step 1: Server signature verification
    sig_ok, sig_reason = verify_server_signature_pipeline(server_pub_key, rogue_pub_key, envelope)
    if not sig_ok:
        return False, sig_reason, {}

    # Step 2: Protected metadata validation
    try:
        meta_bytes = b64url_decode(envelope.get("protected", ""))
        meta = json.loads(meta_bytes.decode('utf-8'))
    except Exception:
        return False, "METADATA_DECODE_FAILED", {}

    if header_validator is not None:
        try:
            header_validator.validate(meta)
        except Exception:
            return False, "METADATA_DECODE_FAILED", {}

    if meta.get("purpose") != "profile" or meta.get("schemaVersion") != 2:
        return False, "METADATA_DECODE_FAILED", {}

    if meta.get("deviceId") != device_id:
        return False, "CROSS_DEVICE_VIOLATION", {}

    try:
        exp_time = datetime.fromisoformat(meta["credentialExpiresAt"].replace("Z", "+00:00"))
        if exp_time <= current_time:
            return False, "CREDENTIAL_EXPIRED", {}
    except Exception:
        return False, "METADATA_DECODE_FAILED", {}

    if meta.get("profileRevision", 0) < last_observed_revision:
        return False, "ROLLBACK_DETECTED", {}

    # Step 3: Key unwrapping
    try:
        aes_key = unwrap_aes_key(device_priv_key, meta["encAlgorithm"], envelope["wrappedKey"])
        if len(aes_key) != 32:
            return False, "KEY_UNWRAP_FAILED", {}
    except Exception:
        return False, "KEY_UNWRAP_FAILED", {}

    # Step 4: AES-GCM Decryption
    try:
        decrypted_bytes = decrypt_profile_payload(aes_key, envelope["iv"], envelope["ciphertext"], envelope["protected"])
    except Exception:
        return False, "GCM_AUTH_FAILED", {}

    # Step 5: Payload Schema & Semantic Binding
    try:
        decrypted_json = json.loads(decrypted_bytes.decode('utf-8'))
        if payload_validator is not None:
            payload_validator.validate(decrypted_json)

        if (decrypted_json.get("profileId") != meta.get("profileId") or
            decrypted_json.get("accessId") != meta.get("accessId") or
            decrypted_json.get("deviceId") != meta.get("deviceId") or
            decrypted_json.get("profileRevision") != meta.get("profileRevision")):
            return False, "SEMANTIC_BINDING_MISMATCH", {}
    except Exception:
        return False, "PAYLOAD_SCHEMA_INVALID", {}

    return True, "OK", decrypted_json


def run_probe():
    contracts_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    vector_file = os.path.join(contracts_dir, "envelope-vectors.json")
    schema_file = os.path.join(contracts_dir, "profile-v2.schema.json")

    print(f"[*] Loading profile schema from: {schema_file}")
    with open(schema_file, "r", encoding="utf-8") as f:
        schema = json.load(f)

    payload_sub = {"$schema": schema["$schema"], "$defs": schema["$defs"], **schema["$defs"]["TunnelProfilePayloadV2"]}
    header_sub = {"$schema": schema["$schema"], "$defs": schema["$defs"], **schema["$defs"]["TunnelProfileProtectedHeader"]}
    env_sub = {"$schema": schema["$schema"], "$defs": schema["$defs"], **schema["$defs"]["TunnelProfileEnvelopeV2"]}

    payload_val = Draft202012Validator(payload_sub, format_checker=FormatChecker())
    header_val = Draft202012Validator(header_sub, format_checker=FormatChecker())
    env_val = Draft202012Validator(env_sub, format_checker=FormatChecker())

    print(f"[*] Loading envelope vectors from: {vector_file}")
    with open(vector_file, "r", encoding="utf-8") as f:
        data = json.load(f)

    # Load server public keys
    server_pub_key = serialization.load_pem_public_key(data["serverKey"]["publicKeyPem"].encode('utf-8'))
    rogue_server_pub_key = serialization.load_pem_public_key(data["rogueServerKey"]["publicKeyPem"].encode('utf-8'))

    # Load device private keys
    device_priv_keys = {}
    for dev_ref, dev_info in data["deviceKeys"].items():
        priv_key = serialization.load_pem_private_key(
            dev_info["privateKeyPkcs8Pem"].encode('utf-8'),
            password=None
        )
        device_priv_keys[dev_ref] = {
            "privKey": priv_key,
            "deviceId": dev_info["deviceId"],
            "encAlgorithm": dev_info["encAlgorithm"]
        }

    print(f"[+] Loaded server and device keys.")

    # 1. Test Positive Vectors
    pos_count = 0
    print("\n--- Testing Positive Envelope Vectors ---")
    current_time = datetime(2026, 10, 6, 21, 30, 0, tzinfo=timezone.utc)

    for vec in data["positiveVectors"]:
        vec_id = vec["id"]
        dev_ref = vec["targetDeviceRef"]
        dev_data = device_priv_keys[dev_ref]
        env = vec["envelope"]

        # Validate wire envelope format
        env_val.validate(env)

        # Run strict pipeline
        ok, reason, decrypted_json = run_pipeline(
            envelope=env,
            server_pub_key=server_pub_key,
            rogue_pub_key=rogue_server_pub_key,
            device_priv_key=dev_data["privKey"],
            device_id=dev_data["deviceId"],
            last_observed_revision=1,
            current_time=current_time,
            payload_validator=payload_val,
            header_validator=header_val
        )

        assert ok, f"Positive vector {vec_id} failed verification pipeline: {reason}"
        assert decrypted_json == vec["plaintext"], f"Decrypted payload mismatch in {vec_id}"

        print(f"  [PASS] {vec_id} ({dev_data['encAlgorithm']}, deviceId={dev_data['deviceId'][:8]}...)")
        pos_count += 1

    print(f"[+] All {pos_count} positive envelope vectors verified successfully through strict pipeline!")

    # 2. Test Negative Vectors
    neg_count = 0
    print("\n--- Testing Negative Envelope Vectors ---")

    for vec in data["negativeVectors"]:
        vec_id = vec["id"]
        expected_err = vec["expectedError"]
        env = vec["envelope"]
        target_ref = vec.get("targetDeviceRef", "modern_device")
        active_dev = device_priv_keys[target_ref]
        last_observed_rev = vec.get("lastObservedRevision", 1)

        ok, actual_reason, _ = run_pipeline(
            envelope=env,
            server_pub_key=server_pub_key,
            rogue_pub_key=rogue_server_pub_key,
            device_priv_key=active_dev["privKey"],
            device_id=active_dev["deviceId"],
            last_observed_revision=last_observed_rev,
            current_time=current_time,
            payload_validator=payload_val,
            header_validator=header_val
        )

        assert not ok, f"Negative envelope vector {vec_id} was unexpectedly accepted!"
        assert actual_reason == expected_err, f"Vector {vec_id}: expected {expected_err}, got {actual_reason}"

        print(f"  [PASS] {vec_id}: Correctly rejected as expected with {actual_reason}")
        neg_count += 1

    print(f"[+] All {neg_count} negative envelope vectors rejected as expected through strict pipeline!")
    print(f"\n==========================================")
    print(f" PROBE RESULT: 100% PASS ({pos_count} positive, {neg_count} negative)")
    print(f"==========================================")


if __name__ == "__main__":
    run_probe()

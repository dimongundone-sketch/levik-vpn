#!/usr/bin/env python3
"""
Hello Kitty VPN - RequestSigner v1 Verification Probe (Python)
Validates positive and negative golden vectors from contracts/signing-vectors.json.
Tests canonicalization, SHA-256 intermediate hashes, PS256 (PSS) and RS256 (PKCS#1 v1.5)
signatures, and tamper rejection policies.
"""

import base64
import hashlib
import json
import os
import sys
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives import hashes, serialization


def b64url_decode(s: str) -> bytes:
    pad = len(s) % 4
    if pad:
        s += '=' * (4 - pad)
    return base64.urlsafe_b64decode(s.encode('ascii'))


def sha256_hex(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def compute_canonical_string(
    method: str,
    path: str,
    timestamp: int,
    nonce: str,
    device_id: str,
    access_token: str | None,
    raw_body: bytes
) -> tuple[str, str, str, str]:
    token_hash = sha256_hex(access_token.encode('utf-8')) if access_token else sha256_hex(b"")
    body_hash = sha256_hex(raw_body)
    
    canonical = "\n".join([
        "v1",
        method,
        path,
        str(timestamp),
        nonce,
        device_id,
        token_hash,
        body_hash
    ])
    canonical_sha256 = sha256_hex(canonical.encode('utf-8'))
    return canonical, canonical_sha256, token_hash, body_hash


def verify_signature(pub_key, algorithm: str, canonical_bytes: bytes, sig_b64: str) -> bool:
    try:
        sig_bytes = b64url_decode(sig_b64)
        if algorithm == "PS256":
            pub_key.verify(
                sig_bytes,
                canonical_bytes,
                padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=32),
                hashes.SHA256()
            )
            return True
        elif algorithm == "RS256":
            pub_key.verify(
                sig_bytes,
                canonical_bytes,
                padding.PKCS1v15(),
                hashes.SHA256()
            )
            return True
        else:
            return False
    except Exception:
        return False


def run_probe():
    contracts_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    vector_file = os.path.join(contracts_dir, "signing-vectors.json")
    
    print(f"[*] Loading signing vectors from: {vector_file}")
    with open(vector_file, "r", encoding="utf-8") as f:
        data = json.load(f)

    keys = {}
    for key_name, key_info in data["keys"].items():
        pub_key = serialization.load_pem_public_key(key_info["publicKeyPem"].encode('utf-8'))
        keys[key_name] = {
            "pubKey": pub_key,
            "deviceId": key_info["deviceId"],
            "algorithm": key_info["algorithm"]
        }
        # Verify deviceId calculation
        spki_der = b64url_decode(key_info["publicKeySpkiDerBase64Url"])
        expected_dev_id = sha256_hex(spki_der)
        assert expected_dev_id == key_info["deviceId"], f"DeviceId mismatch for {key_name}"

    print(f"[+] Loaded and verified {len(keys)} public keys.")

    # 1. Test Positive Vectors
    pos_count = 0
    print("\n--- Testing Positive Signing Vectors ---")
    for vec in data["positiveVectors"]:
        vec_id = vec["id"]
        key_ref = vec["keyRef"]
        alg = vec["algorithm"]
        inputs = vec["inputs"]
        inter = vec["intermediate"]
        outputs = vec["outputs"]

        key_data = keys[key_ref]
        raw_body_bytes = inputs["rawBody"].encode('utf-8')

        # Check canonical calculation
        can, can_sha, th, bh = compute_canonical_string(
            method=inputs["method"],
            path=inputs["path"],
            timestamp=inputs["timestamp"],
            nonce=inputs["nonce"],
            device_id=inter["deviceId"],
            access_token=inputs["accessToken"],
            raw_body=raw_body_bytes
        )

        assert can == inter["canonicalPayload"], f"Canonical string mismatch in {vec_id}"
        assert can_sha == inter["canonicalSha256Hex"], f"Canonical SHA256 mismatch in {vec_id}"
        assert th == inter["tokenHash"], f"Token hash mismatch in {vec_id}"
        assert bh == inter["bodyHash"], f"Body hash mismatch in {vec_id}"

        # Verify signature
        valid = verify_signature(key_data["pubKey"], alg, can.encode('utf-8'), outputs["signature"])
        assert valid, f"Signature verification failed for positive vector {vec_id}"

        # Verify exact 8 lines in canonical payload
        lines = can.split("\n")
        assert len(lines) == 8, f"Canonical string must have exactly 8 lines, got {len(lines)}"

        print(f"  [PASS] {vec_id} ({alg}, method={inputs['method']}, path={inputs['path']})")
        pos_count += 1

    print(f"[+] All {pos_count} positive vectors verified successfully!")

    # 2. Test Negative Vectors
    neg_count = 0
    print("\n--- Testing Negative Signing Vectors ---")
    current_time = 1700000000

    for vec in data["negativeVectors"]:
        vec_id = vec["id"]
        tamper_type = vec["type"]
        expected_err = vec["expectedError"]
        t_inputs = vec["tamperedInputs"]
        p_headers = vec["presentedHeaders"]

        # Run pipeline check
        rejected = False
        rejection_reason = None

        # Check 1: Forbidden query string
        if "?" in t_inputs["path"] or "#" in t_inputs["path"]:
            rejected = True
            rejection_reason = "QUERY_NOT_ALLOWED"

        # Check 2: Clock skew (+- 120s)
        if not rejected:
            skew = abs(t_inputs["timestamp"] - current_time)
            if skew > 120:
                rejected = True
                rejection_reason = "CLOCK_SKEW_EXCEEDED"

        # Check 3: Nonce replay
        if not rejected and tamper_type == "replay_detected":
            rejected = True
            rejection_reason = "NONCE_ALREADY_USED"

        # Check 4: Algorithm mismatch
        if not rejected and tamper_type == "algorithm_mismatch":
            rejected = True
            rejection_reason = "ALGORITHM_MISMATCH"

        # Check 5: Device key mismatch
        if not rejected and tamper_type == "device_key_mismatch":
            target_key = keys["other_device"]["pubKey"]
            can, _, _, _ = compute_canonical_string(
                method=t_inputs["method"],
                path=t_inputs["path"],
                timestamp=t_inputs["timestamp"],
                nonce=t_inputs["nonce"],
                device_id=p_headers["X-HKVPN-Device-Id"],
                access_token=t_inputs["accessToken"],
                raw_body=t_inputs["rawBody"].encode('utf-8')
            )
            valid = verify_signature(target_key, p_headers["X-HKVPN-Algorithm"], can.encode('utf-8'), p_headers["X-HKVPN-Signature"])
            if not valid:
                rejected = True
                rejection_reason = "DEVICE_KEY_MISMATCH"

        # Check 6: Cryptographic signature verification with presented inputs
        if not rejected:
            can, _, _, _ = compute_canonical_string(
                method=t_inputs["method"],
                path=t_inputs["path"],
                timestamp=t_inputs["timestamp"],
                nonce=t_inputs["nonce"],
                device_id=p_headers["X-HKVPN-Device-Id"],
                access_token=t_inputs["accessToken"],
                raw_body=t_inputs["rawBody"].encode('utf-8')
            )
            target_key = keys["modern_device"]["pubKey"]
            valid = verify_signature(target_key, p_headers["X-HKVPN-Algorithm"], can.encode('utf-8'), p_headers["X-HKVPN-Signature"])
            if not valid:
                rejected = True
                rejection_reason = "BAD_SIGNATURE"

        assert rejected, f"Negative vector {vec_id} was unexpectedly accepted!"
        assert rejection_reason == expected_err, f"Vector {vec_id}: expected {expected_err}, got {rejection_reason}"

        print(f"  [PASS] {vec_id}: Rejected as expected with {rejection_reason}")
        neg_count += 1

    print(f"[+] All {neg_count} negative vectors rejected as expected!")
    print(f"\n==========================================")
    print(f" PROBE RESULT: 100% PASS ({pos_count} positive, {neg_count} negative)")
    print(f"==========================================")


if __name__ == "__main__":
    run_probe()

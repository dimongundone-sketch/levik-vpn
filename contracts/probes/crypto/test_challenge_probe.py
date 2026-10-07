#!/usr/bin/env python3
"""
HelloKittyVPN HKVPN-CHALLENGE-V1 Protocol Probe
Contract: contracts/security-contract.md Section 3.4
Validates challenge verification, canonical string assembly, ES256 low-S validation,
clock drift checks, and error cases from contracts/challenge-vectors.json.
"""

import base64
from datetime import datetime, timezone
import hashlib
import json
import os
import sys
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

P256_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
HALF_ORDER = P256_ORDER // 2
MAX_CLOCK_DRIFT_SECONDS = 86400  # 24 hours


def b64url_decode(s: str) -> bytes:
    pad = len(s) % 4
    if pad:
        s += "=" * (4 - pad)
    return base64.urlsafe_b64decode(s.encode("ascii"))


def b64url_encode(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode("ascii").rstrip("=")


def parse_iso8601_utc(ts_str: str) -> datetime:
    return datetime.fromisoformat(ts_str.replace("Z", "+00:00"))


def build_canonical_challenge_string(
    key_id: str,
    challenge_id: str,
    server_nonce: str,
    expires_at: str,
    server_time: str,
    request_body_hash: str,
) -> str:
    lines = [
        "HKVPN-CHALLENGE-V1",
        "challenge",
        "ES256",
        key_id,
        challenge_id,
        server_nonce,
        expires_at,
        server_time,
        request_body_hash,
    ]
    return "\n".join(lines) + "\n"


def verify_challenge(
    response: dict,
    request_body_raw_bytes: bytes,
    trusted_public_keys: dict[str, ec.EllipticCurvePublicKey],
    client_now: datetime,
) -> tuple[bool, str]:
    """
    Client-side verification of ChallengeResponse per Section 3.4 of Security Contract.
    Returns (success: bool, error_code: str).
    """
    # 1. Check required fields
    required = [
        "challengeId", "serverNonce", "expiresAt", "serverTime",
        "keyId", "purpose", "signatureAlgorithm", "requestBodyHash", "signature"
    ]
    for r in required:
        if r not in response or not response[r]:
            return False, "MISSING_SIGNATURE" if r == "signature" else f"MISSING_FIELD_{r.upper()}"

    # 2. Check purpose and algorithm
    if response["purpose"] != "challenge":
        return False, "INVALID_PURPOSE"
    if response["signatureAlgorithm"] != "ES256":
        return False, "UNSUPPORTED_SIGNATURE_ALGORITHM"

    # 3. Check client nonce length in request body (if present in request JSON)
    try:
        req_json = json.loads(request_body_raw_bytes.decode("utf-8"))
        client_nonce = req_json.get("clientNonce", "")
        if len(client_nonce) != 22:
            return False, "INVALID_NONCE_LENGTH"
        if len(b64url_decode(client_nonce)) != 16:
            return False, "INVALID_NONCE_LENGTH"
    except Exception:
        pass

    # 4. Check server nonce length (32 bytes = 43 chars base64url)
    try:
        s_nonce_bytes = b64url_decode(response["serverNonce"])
        if len(s_nonce_bytes) != 32:
            return False, "INVALID_SERVER_NONCE_LENGTH"
    except Exception:
        return False, "INVALID_SERVER_NONCE_LENGTH"

    # 5. Check requestBodyHash binding
    expected_body_hash = hashlib.sha256(request_body_raw_bytes).hexdigest()
    if response["requestBodyHash"].lower() != expected_body_hash.lower():
        return False, "REQUEST_BODY_HASH_MISMATCH"

    # 6. Check signing key in trusted key ring
    key_id = response["keyId"]
    if key_id not in trusted_public_keys:
        return False, "UNAUTHORIZED_SIGNING_KEY"
    trusted_key = trusted_public_keys[key_id]

    # 7. Check signature decoding & low-S strict canonical normalization
    sig_b64 = response["signature"]
    try:
        sig_bytes = b64url_decode(sig_b64)
        r, s = decode_dss_signature(sig_bytes)
    except Exception:
        return False, "CHALLENGE_SIGNATURE_INVALID"

    if s > HALF_ORDER:
        return False, "CHALLENGE_SIGNATURE_INVALID"

    # 8. Assemble canonical string and verify ECDSA P-256 signature
    canonical_str = build_canonical_challenge_string(
        key_id=key_id,
        challenge_id=response["challengeId"],
        server_nonce=response["serverNonce"],
        expires_at=response["expiresAt"],
        server_time=response["serverTime"],
        request_body_hash=response["requestBodyHash"],
    )
    try:
        trusted_key.verify(sig_bytes, canonical_str.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    except Exception:
        return False, "CHALLENGE_SIGNATURE_INVALID"

    # 9. Time order and expiry checks
    try:
        srv_time = parse_iso8601_utc(response["serverTime"])
        exp_time = parse_iso8601_utc(response["expiresAt"])
    except Exception:
        return False, "INVALID_TIMESTAMP_FORMAT"

    if srv_time >= exp_time:
        return False, "CHALLENGE_EXPIRED"

    # 10. Clock drift verification (|serverTime - clientTime| <= 86400s)
    drift_seconds = abs((srv_time - client_now).total_seconds())
    if drift_seconds > MAX_CLOCK_DRIFT_SECONDS:
        return False, "CLOCK_DRIFT_EXCESSIVE"

    return True, "SUCCESS"


def run_tests():
    repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
    vectors_path = os.path.join(repo_root, "contracts", "challenge-vectors.json")
    with open(vectors_path, "r", encoding="utf-8") as f:
        doc = json.load(f)

    # Load trusted server key
    server_key_pem = doc["serverKey"]["publicKeyPem"].encode("ascii")
    server_pub_key = serialization.load_pem_public_key(server_key_pem)

    trusted_ring = {
        doc["serverKey"]["keyId"]: server_pub_key
    }

    # Reference client time: 2026-10-07T08:30:00Z
    client_now = parse_iso8601_utc("2026-10-07T08:30:00Z")

    print(f"Loaded {len(doc['positiveVectors'])} positive and {len(doc['negativeVectors'])} negative challenge vectors.")

    # 1. Positive vectors
    for pv in doc["positiveVectors"]:
        vec_id = pv["id"]
        raw_req_bytes = pv["requestBodyRawBytes"].encode("utf-8")
        resp = pv["response"]
        ok, err = verify_challenge(resp, raw_req_bytes, trusted_ring, client_now)
        assert ok, f"Positive vector {vec_id} failed: {err}"
        print(f"  [PASS] {vec_id}: Validated successfully ({err})")

    # 2. Negative vectors
    for nv in doc["negativeVectors"]:
        vec_id = nv["id"]
        expected_err = nv["expectedError"]
        raw_req_bytes = nv["requestBodyRawBytes"].encode("utf-8")
        resp = nv["response"]
        ok, err = verify_challenge(resp, raw_req_bytes, trusted_ring, client_now)
        assert not ok, f"Negative vector {vec_id} unexpectedly succeeded!"
        assert err == expected_err, f"Negative vector {vec_id} error mismatch: expected {expected_err}, got {err}"
        print(f"  [PASS] {vec_id}: Rejected with expected error: {err}")

    print("\nAll HKVPN-CHALLENGE-V1 vectors passed verification 100%!")


if __name__ == "__main__":
    run_tests()

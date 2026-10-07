#!/usr/bin/env python3
"""
Generate Golden Test Vectors for HelloKittyVPN HKVPN-CHALLENGE-V1
Saves to contracts/challenge-vectors.json
"""

import base64
import hashlib
import json
import os
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature, encode_dss_signature

P256_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
HALF_ORDER = P256_ORDER // 2


def b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode("ascii").rstrip("=")


def b64url_decode(s: str) -> bytes:
    pad = len(s) % 4
    if pad:
        s += "=" * (4 - pad)
    return base64.urlsafe_b64decode(s.encode("ascii"))


def make_low_s_sig(private_key, data: bytes) -> str:
    raw_sig = private_key.sign(data, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(raw_sig)
    if s > HALF_ORDER:
        s = P256_ORDER - s
    low_s_der = encode_dss_signature(r, s)
    return b64url(low_s_der)


def make_high_s_sig(private_key, data: bytes) -> str:
    raw_sig = private_key.sign(data, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(raw_sig)
    if s <= HALF_ORDER:
        s = P256_ORDER - s
    high_s_der = encode_dss_signature(r, s)
    return b64url(high_s_der)


def main():
    # Use deterministic EC key pairs
    # Server key
    server_key = ec.derive_private_key(int.from_bytes(hashlib.sha256(b"hkvpn-challenge-server-seed-2026").digest(), "big"), ec.SECP256R1())
    rogue_key = ec.derive_private_key(int.from_bytes(hashlib.sha256(b"hkvpn-challenge-rogue-seed-2026").digest(), "big"), ec.SECP256R1())

    server_priv_pem = server_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    ).decode("ascii")
    server_pub_pem = server_key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode("ascii")

    rogue_priv_pem = rogue_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    ).decode("ascii")
    rogue_pub_pem = rogue_key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode("ascii")

    key_id = "hkvpn-challenge-signer-2026-v1"
    rogue_key_id = "hkvpn-challenge-signer-rogue-v1"

    # Common SPKI from envelope-vectors modern_device
    spki_b64url = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAxmQp-CMT_6j9DniZOwdeTP05dut7EBd19JmjN9lY3hmpbO3L9_2Wj7egpVQXre-yqpk8Zl9dRIy26dMBnmTWLkN18le5dPLiJmQTKZLKA4wUhR4ifVuJmiIrQVuZ8F7PSvDjODhIFi_Lt7T4SfP9SGM914M42axQYp_-K9d_tybg7u_F1LV_EgkTL9NpklIM2pc6mAncnCb7W0f8p76L-q_O7MwoCxoDiX6nAuqgIbOW8I66Y_lHigz5LxsdK1a4IKUq_mqc5kFeiWZIpRavCp10su73CkcPtHRkfWBp8R48rhDGcsnPX37QXElESV8NZaM7NTP9zzVABnNw4jgectqz42Q26IHXZnNJY4wwaM4OEmNnfAeeaBxza4FTTSgbcJqIrNfTcuJHMD0_m__w85R9oBnaUb9wytMK0d-bwulUWhmcOSYgqhTmWFAejGRnZQi-kgGB244GWwLenEyfDrCsayiu_GKjjP0OzOG4gxXDj9UCzTTkfl0Ov_PY5IKVAgMBAAE"

    # --- Vector POS-01: Enrollment Challenge ---
    pos1_client_nonce = "dGhpczE2Qnl0ZXNOb25jZQ"  # 16 bytes base64url
    pos1_req_body = {
        "mode": "enroll",
        "invitationCode": "hkvpn-invite-7f9a2b8c4d1e",
        "clientNonce": pos1_client_nonce,
        "spkiBase64Url": spki_b64url,
        "capabilities": {
            "requestSigningAlgorithm": "PS256",
            "profileEncryptionAlgorithm": "RSA-OAEP-256+A256GCM",
        },
    }
    pos1_req_bytes = json.dumps(pos1_req_body, separators=(",", ":")).encode("utf-8")
    pos1_req_hash = hashlib.sha256(pos1_req_bytes).hexdigest()

    pos1_challenge_id = "c1a2b3c4-d5e6-7a8b-9c0d-1e2f3a4b5c6d"
    pos1_server_nonce = "c2VydmVyMzJCeXRlc05vbmNlQ1NQUk5HQnl0ZXNPYWs"  # 32 bytes base64url (43 chars)
    pos1_expires_at = "2026-10-07T08:32:00Z"
    pos1_server_time = "2026-10-07T08:30:00Z"

    pos1_canonical = f"HKVPN-CHALLENGE-V1\nchallenge\nES256\n{key_id}\n{pos1_challenge_id}\n{pos1_server_nonce}\n{pos1_expires_at}\n{pos1_server_time}\n{pos1_req_hash}\n"
    pos1_sig = make_low_s_sig(server_key, pos1_canonical.encode("utf-8"))

    pos1_response = {
        "challengeId": pos1_challenge_id,
        "serverNonce": pos1_server_nonce,
        "expiresAt": pos1_expires_at,
        "serverTime": pos1_server_time,
        "keyId": key_id,
        "purpose": "challenge",
        "signatureAlgorithm": "ES256",
        "requestBodyHash": pos1_req_hash,
        "signature": pos1_sig,
    }

    # --- Vector POS-02: Reauth Challenge ---
    pos2_client_nonce = "YW5vdGhlcjE2Qnl0ZU5vbg"  # 16 bytes base64url (22 chars)
    pos2_req_body = {
        "mode": "reauth",
        "clientNonce": pos2_client_nonce,
        "spkiBase64Url": spki_b64url,
        "capabilities": {
            "requestSigningAlgorithm": "PS256",
            "profileEncryptionAlgorithm": "RSA-OAEP-256+A256GCM",
        },
    }
    pos2_req_bytes = json.dumps(pos2_req_body, separators=(",", ":")).encode("utf-8")
    pos2_req_hash = hashlib.sha256(pos2_req_bytes).hexdigest()

    pos2_challenge_id = "d2b3c4d5-e6f7-8a9b-0c1d-2e3f4a5b6c7d"
    pos2_server_nonce = "YW5vdGhlcjMyQnl0ZXNTZXJ2ZXJOb25jZUNTUFJORyE"  # 32 bytes base64url (43 chars)
    pos2_expires_at = "2026-10-07T08:35:00Z"
    pos2_server_time = "2026-10-07T08:33:00Z"

    pos2_canonical = f"HKVPN-CHALLENGE-V1\nchallenge\nES256\n{key_id}\n{pos2_challenge_id}\n{pos2_server_nonce}\n{pos2_expires_at}\n{pos2_server_time}\n{pos2_req_hash}\n"
    pos2_sig = make_low_s_sig(server_key, pos2_canonical.encode("utf-8"))

    pos2_response = {
        "challengeId": pos2_challenge_id,
        "serverNonce": pos2_server_nonce,
        "expiresAt": pos2_expires_at,
        "serverTime": pos2_server_time,
        "keyId": key_id,
        "purpose": "challenge",
        "signatureAlgorithm": "ES256",
        "requestBodyHash": pos2_req_hash,
        "signature": pos2_sig,
    }

    # --- Negative Vectors ---
    # neg_01: Tampered signature byte
    neg1_sig_bytes = bytearray(b64url_decode(pos1_sig))
    neg1_sig_bytes[5] ^= 0xFF
    neg1_sig = b64url(bytes(neg1_sig_bytes))
    neg1_resp = dict(pos1_response, signature=neg1_sig)

    # neg_02: High-S signature
    neg2_sig = make_high_s_sig(server_key, pos1_canonical.encode("utf-8"))
    neg2_resp = dict(pos1_response, signature=neg2_sig)

    # neg_03: Tampered request body hash
    neg3_req_hash = "ff" * 32
    neg3_resp = dict(pos1_response, requestBodyHash=neg3_req_hash)

    # neg_04: Tampered server time (in response vs canonical)
    neg4_resp = dict(pos1_response, serverTime="2026-10-07T09:30:00Z")

    # neg_05: Excessive clock drift (e.g. 100,000s ahead)
    drift_canonical = f"HKVPN-CHALLENGE-V1\nchallenge\nES256\n{key_id}\n{pos1_challenge_id}\n{pos1_server_nonce}\n2026-10-08T12:02:00Z\n2026-10-08T12:00:00Z\n{pos1_req_hash}\n"
    drift_sig = make_low_s_sig(server_key, drift_canonical.encode("utf-8"))
    neg5_resp = {
        "challengeId": pos1_challenge_id,
        "serverNonce": pos1_server_nonce,
        "expiresAt": "2026-10-08T12:02:00Z",
        "serverTime": "2026-10-08T12:00:00Z",
        "keyId": key_id,
        "purpose": "challenge",
        "signatureAlgorithm": "ES256",
        "requestBodyHash": pos1_req_hash,
        "signature": drift_sig,
    }

    # neg_06: Expired challenge (serverTime >= expiresAt)
    exp_canonical = f"HKVPN-CHALLENGE-V1\nchallenge\nES256\n{key_id}\n{pos1_challenge_id}\n{pos1_server_nonce}\n2026-10-07T08:29:00Z\n2026-10-07T08:30:00Z\n{pos1_req_hash}\n"
    exp_sig = make_low_s_sig(server_key, exp_canonical.encode("utf-8"))
    neg6_resp = {
        "challengeId": pos1_challenge_id,
        "serverNonce": pos1_server_nonce,
        "expiresAt": "2026-10-07T08:29:00Z",
        "serverTime": "2026-10-07T08:30:00Z",
        "keyId": key_id,
        "purpose": "challenge",
        "signatureAlgorithm": "ES256",
        "requestBodyHash": pos1_req_hash,
        "signature": exp_sig,
    }

    # neg_07: Signed by rogue server key (keyId not trusted)
    rogue_sig = make_low_s_sig(rogue_key, pos1_canonical.encode("utf-8"))
    neg7_resp = dict(pos1_response, keyId=rogue_key_id, signature=rogue_sig)

    # neg_08: Invalid canonical prefix
    neg8_canonical = f"HKVPN-CHALLENGE-V2\nchallenge\nES256\n{key_id}\n{pos1_challenge_id}\n{pos1_server_nonce}\n{pos1_expires_at}\n{pos1_server_time}\n{pos1_req_hash}\n"
    neg8_sig = make_low_s_sig(server_key, neg8_canonical.encode("utf-8"))
    neg8_resp = dict(pos1_response, signature=neg8_sig)

    # neg_09: Invalid client nonce length (8 bytes instead of 16 bytes)
    neg9_req_body = dict(pos1_req_body, clientNonce="dGhpczhCeXRl")
    neg9_req_bytes = json.dumps(neg9_req_body, separators=(",", ":")).encode("utf-8")
    neg9_req_hash = hashlib.sha256(neg9_req_bytes).hexdigest()
    neg9_canonical = f"HKVPN-CHALLENGE-V1\nchallenge\nES256\n{key_id}\n{pos1_challenge_id}\n{pos1_server_nonce}\n{pos1_expires_at}\n{pos1_server_time}\n{neg9_req_hash}\n"
    neg9_sig = make_low_s_sig(server_key, neg9_canonical.encode("utf-8"))
    neg9_resp = dict(pos1_response, requestBodyHash=neg9_req_hash, signature=neg9_sig)

    # neg_10: Missing signature
    neg10_resp = dict(pos1_response, signature="")

    # neg_11: Invalid purpose
    neg11_canonical = f"HKVPN-CHALLENGE-V1\nprofile\nES256\n{key_id}\n{pos1_challenge_id}\n{pos1_server_nonce}\n{pos1_expires_at}\n{pos1_server_time}\n{pos1_req_hash}\n"
    neg11_sig = make_low_s_sig(server_key, neg11_canonical.encode("utf-8"))
    neg11_resp = dict(pos1_response, purpose="profile", signature=neg11_sig)

    output = {
        "version": "1.0",
        "description": "Golden test vectors for Hello Kitty VPN HKVPN-CHALLENGE-V1 Server Time & Challenge Protocol",
        "serverKey": {
            "keyId": key_id,
            "curve": "P-256",
            "algorithm": "ES256",
            "publicKeyPem": server_pub_pem,
            "privateKeyPkcs8Pem": server_priv_pem,
        },
        "rogueServerKey": {
            "keyId": rogue_key_id,
            "curve": "P-256",
            "algorithm": "ES256",
            "publicKeyPem": rogue_pub_pem,
            "privateKeyPkcs8Pem": rogue_priv_pem,
        },
        "positiveVectors": [
            {
                "id": "pos_01_enroll_challenge",
                "description": "Valid enrollment challenge response with verified P-256 low-S signature and bounded server time",
                "requestBody": pos1_req_body,
                "requestBodyRawBytes": pos1_req_bytes.decode("utf-8"),
                "canonicalString": pos1_canonical,
                "response": pos1_response,
            },
            {
                "id": "pos_02_reauth_challenge",
                "description": "Valid re-authentication challenge response without invitation token with verified P-256 signature",
                "requestBody": pos2_req_body,
                "requestBodyRawBytes": pos2_req_bytes.decode("utf-8"),
                "canonicalString": pos2_canonical,
                "response": pos2_response,
            },
        ],
        "negativeVectors": [
            {
                "id": "neg_01_tampered_signature",
                "description": "Server signature bytes corrupted on the wire",
                "requestBody": pos1_req_body,
                "requestBodyRawBytes": pos1_req_bytes.decode("utf-8"),
                "canonicalString": pos1_canonical,
                "response": neg1_resp,
                "expectedError": "CHALLENGE_SIGNATURE_INVALID",
            },
            {
                "id": "neg_02_high_s_signature",
                "description": "Server signature with high-S value rejected by strict canonical rule",
                "requestBody": pos1_req_body,
                "requestBodyRawBytes": pos1_req_bytes.decode("utf-8"),
                "canonicalString": pos1_canonical,
                "response": neg2_resp,
                "expectedError": "CHALLENGE_SIGNATURE_INVALID",
            },
            {
                "id": "neg_03_tampered_request_body_hash",
                "description": "Request body hash in response does not match SHA256 of sent request",
                "requestBody": pos1_req_body,
                "requestBodyRawBytes": pos1_req_bytes.decode("utf-8"),
                "canonicalString": pos1_canonical,
                "response": neg3_resp,
                "expectedError": "REQUEST_BODY_HASH_MISMATCH",
            },
            {
                "id": "neg_04_tampered_server_time",
                "description": "serverTime altered after server signature generation",
                "requestBody": pos1_req_body,
                "requestBodyRawBytes": pos1_req_bytes.decode("utf-8"),
                "canonicalString": pos1_canonical,
                "response": neg4_resp,
                "expectedError": "CHALLENGE_SIGNATURE_INVALID",
            },
            {
                "id": "neg_05_excessive_clock_drift",
                "description": "Server time differs from client time by more than 86400 seconds (24h)",
                "requestBody": pos1_req_body,
                "requestBodyRawBytes": pos1_req_bytes.decode("utf-8"),
                "canonicalString": drift_canonical,
                "response": neg5_resp,
                "expectedError": "CLOCK_DRIFT_EXCESSIVE",
            },
            {
                "id": "neg_06_expired_challenge",
                "description": "Challenge response where expiresAt is in the past relative to serverTime",
                "requestBody": pos1_req_body,
                "requestBodyRawBytes": pos1_req_bytes.decode("utf-8"),
                "canonicalString": exp_canonical,
                "response": neg6_resp,
                "expectedError": "CHALLENGE_EXPIRED",
            },
            {
                "id": "neg_07_untrusted_signing_key",
                "description": "Challenge signed by unknown rogue EC key not in trusted challenge ring",
                "requestBody": pos1_req_body,
                "requestBodyRawBytes": pos1_req_bytes.decode("utf-8"),
                "canonicalString": pos1_canonical,
                "response": neg7_resp,
                "expectedError": "UNAUTHORIZED_SIGNING_KEY",
            },
            {
                "id": "neg_08_invalid_canonical_prefix",
                "description": "Signature generated over incorrect canonical prefix (HKVPN-CHALLENGE-V2)",
                "requestBody": pos1_req_body,
                "requestBodyRawBytes": pos1_req_bytes.decode("utf-8"),
                "canonicalString": neg8_canonical,
                "response": neg8_resp,
                "expectedError": "CHALLENGE_SIGNATURE_INVALID",
            },
            {
                "id": "neg_09_invalid_client_nonce_length",
                "description": "Client nonce is too short (less than 16 bytes / 22 characters)",
                "requestBody": neg9_req_body,
                "requestBodyRawBytes": neg9_req_bytes.decode("utf-8"),
                "canonicalString": neg9_canonical,
                "response": neg9_resp,
                "expectedError": "INVALID_NONCE_LENGTH",
            },
            {
                "id": "neg_10_missing_signature",
                "description": "Signature field is empty or missing from challenge response",
                "requestBody": pos1_req_body,
                "requestBodyRawBytes": pos1_req_bytes.decode("utf-8"),
                "canonicalString": pos1_canonical,
                "response": neg10_resp,
                "expectedError": "MISSING_SIGNATURE",
            },
            {
                "id": "neg_11_invalid_purpose",
                "description": "Purpose field is not 'challenge'",
                "requestBody": pos1_req_body,
                "requestBodyRawBytes": pos1_req_bytes.decode("utf-8"),
                "canonicalString": neg11_canonical,
                "response": neg11_resp,
                "expectedError": "INVALID_PURPOSE",
            },
        ],
    }

    repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
    out_path = os.path.join(repo_root, "contracts", "challenge-vectors.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2)
    print(f"Generated {out_path} with {len(output['positiveVectors'])} positive and {len(output['negativeVectors'])} negative vectors.")


if __name__ == "__main__":
    main()

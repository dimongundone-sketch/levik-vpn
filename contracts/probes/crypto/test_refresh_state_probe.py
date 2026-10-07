#!/usr/bin/env python3
"""
Hello Kitty VPN - Lost-Response Refresh Recovery State Probe (Python)
Simulates and verifies:
1. Standard token issuance & rotation.
2. Lost-response exact retry within 120s (cached response without double rotation or family revocation).
3. Concurrent retry single-flight / idempotency handling.
4. Token reuse attack detection (consumed token + new operation ID -> immediate family revocation).
5. Idempotency conflict handling (same operation ID + altered body -> 409 Conflict, no revocation).
6. Expired retry handling (> 120s -> 401 REFRESH_RETRY_EXPIRED, distinct from token reuse).
7. Hardware Keystore re-authentication recovery flow after expired retry.
8. Naturally idempotent device revocation (DELETE /v1/devices/me).
"""

import base64
import hashlib
import os
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Optional


def sha256_hex(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def generate_token() -> str:
    return "hkvpn_" + base64.urlsafe_b64encode(os.urandom(32)).decode('ascii').rstrip('=')


@dataclass
class GrantRecord:
    grant_id: str
    device_id: str
    status: str = "ACTIVE"  # "ACTIVE" | "REVOKED" | "EXPIRED"
    expires_at: float = 0.0


@dataclass
class TokenRecord:
    token_hash: str
    family_id: str
    device_id: str
    is_used: bool = False
    is_revoked: bool = False
    used_at: Optional[float] = None
    issuance_op_id: Optional[str] = None
    issuance_body_hash: Optional[str] = None
    consumed_by_op_id: Optional[str] = None
    consumed_by_body_hash: Optional[str] = None


@dataclass
class CachedResponse:
    encrypted_payload: dict
    expires_at: float


class AuthControlPlaneState:
    """In-memory reference implementation of control plane token state machine."""
    def __init__(self):
        self._lock = threading.Lock()
        self.devices: dict[str, dict] = {}               # device_id -> {status, grant_id}
        self.grants: dict[str, GrantRecord] = {}         # grant_id -> GrantRecord
        self.token_families: dict[str, dict] = {}        # family_id -> {device_id, is_revoked}
        self.refresh_tokens: dict[str, TokenRecord] = {} # token_hash -> TokenRecord
        self.access_tokens: dict[str, dict] = {}         # token_hash -> {family_id, device_id, is_revoked}
        self.cached_responses: dict[str, CachedResponse] = {} # cache_key -> CachedResponse
        self.idempotency_store: dict[str, dict] = {}    # owner:op_id -> {body_hash, committed_at}
        self.audit_log: list[str] = []

    def enroll_device(self, device_id: str, grant_duration: float = 86400.0, simulated_now: Optional[float] = None) -> tuple[str, str]:
        with self._lock:
            family_id = f"fam_{uuid.uuid4()}"
            grant_id = f"grant_{uuid.uuid4()}"
            now = simulated_now if simulated_now is not None else time.time()

            self.grants[grant_id] = GrantRecord(
                grant_id=grant_id,
                device_id=device_id,
                status="ACTIVE",
                expires_at=now + grant_duration
            )
            self.devices[device_id] = {"status": "ACTIVE", "grant_id": grant_id}
            self.token_families[family_id] = {"device_id": device_id, "is_revoked": False}

            acc = generate_token()
            ref = generate_token()

            acc_hash = sha256_hex(acc.encode('utf-8'))
            ref_hash = sha256_hex(ref.encode('utf-8'))

            self.access_tokens[acc_hash] = {"family_id": family_id, "device_id": device_id, "is_revoked": False}
            self.refresh_tokens[ref_hash] = TokenRecord(
                token_hash=ref_hash,
                family_id=family_id,
                device_id=device_id,
                issuance_op_id="enroll_init",
                issuance_body_hash="initial"
            )
            return acc, ref

    def handle_refresh(
        self,
        device_id: str,
        idempotency_key: str,
        refresh_token: str,
        client_operation_id: str,
        raw_body: bytes,
        simulated_now: float
    ) -> tuple[int, dict]:
        # Precondition: Header Idempotency-Key == body.clientOperationId
        if idempotency_key != client_operation_id:
            return 400, {"code": "INVALID_IDEMPOTENCY_KEY", "message": "Header key does not match body op ID"}

        with self._lock:
            # 1. Device check
            dev = self.devices.get(device_id)
            if not dev or dev["status"] != "ACTIVE":
                return 401, {"code": "DEVICE_NOT_ACTIVE"}

            # 2. Grant check
            grant = self.grants.get(dev["grant_id"])
            if not grant or grant.status != "ACTIVE":
                return 401, {"code": "GRANT_INACTIVE", "message": "Grant is revoked or missing"}
            if grant.expires_at <= simulated_now:
                return 401, {"code": "GRANT_EXPIRED", "message": "Grant has expired"}

            body_hash = sha256_hex(raw_body)
            token_hash = sha256_hex(refresh_token.encode('utf-8'))
            rec = self.refresh_tokens.get(token_hash)

            if not rec:
                return 401, {"code": "INVALID_TOKEN", "message": "Refresh token not found"}

            # 3. Device Ownership Verification
            # A token presented by a device that did not receive it MUST NOT revoke the victim's family!
            if rec.device_id != device_id:
                self.audit_log.append(f"UNAUTHORIZED_DEVICE_ACCESS: token_dev={rec.device_id}, caller_dev={device_id}")
                return 401, {"code": "TOKEN_DEVICE_MISMATCH", "message": "Token does not belong to caller device"}

            family = self.token_families.get(rec.family_id)
            if not family or family["is_revoked"] or rec.is_revoked:
                return 401, {"code": "FAMILY_REVOKED", "message": "Token family has been revoked"}

            # 4. Idempotency conflict (Same operation ID, different body)
            idemp_key_combined = f"{device_id}:{client_operation_id}"
            if idemp_key_combined in self.idempotency_store:
                prev_body_hash = self.idempotency_store[idemp_key_combined]["body_hash"]
                if prev_body_hash != body_hash:
                    return 409, {"code": "IDEMPOTENCY_CONFLICT", "message": "Payload modified for existing operation ID"}

            # 5. Already CONSUMED token
            if rec.is_used:
                # Did this token consumption originate from the SAME clientOperationId?
                if rec.consumed_by_op_id == client_operation_id:
                    # Is body hash identical?
                    if rec.consumed_by_body_hash != body_hash:
                        return 409, {"code": "IDEMPOTENCY_CONFLICT"}

                    # Check 120s recovery window
                    elapsed = simulated_now - (rec.used_at or 0.0)
                    if elapsed <= 120.0:
                        # Return cached response without new rotation or family revoke!
                        cache_key = f"resp:{device_id}:{rec.consumed_by_op_id}"
                        cached = self.cached_responses.get(cache_key)
                        if cached:
                            return 200, {
                                **cached.encrypted_payload,
                                "_recoveredFromCache": True
                            }
                        else:
                            return 401, {"code": "CACHE_UNAVAILABLE"}
                    else:
                        # Outside 120s window: not a reuse attack, but recovery has expired
                        return 410, {
                            "code": "REFRESH_RETRY_EXPIRED",
                            "message": "Retry window exceeded; trigger device reauth"
                        }
                else:
                    # Consumed token presented with a DIFFERENT clientOperationId!
                    # TOKEN REUSE ATTACK DETECTED!
                    self.audit_log.append(f"TOKEN_REUSE_DETECTED: family={rec.family_id}, device={device_id}")
                    family["is_revoked"] = True
                    # Revoke all access tokens belonging to this family
                    for at in self.access_tokens.values():
                        if at["family_id"] == rec.family_id:
                            at["is_revoked"] = True
                    return 401, {
                        "code": "TOKEN_REUSED",
                        "message": "Security violation: token reuse detected; family revoked"
                    }

            # 6. Happy path: Atomic Rotation
            rec.is_used = True
            rec.used_at = simulated_now
            rec.consumed_by_op_id = client_operation_id
            rec.consumed_by_body_hash = body_hash

            new_acc = generate_token()
            new_ref = generate_token()
            new_acc_hash = sha256_hex(new_acc.encode('utf-8'))
            new_ref_hash = sha256_hex(new_ref.encode('utf-8'))

            self.access_tokens[new_acc_hash] = {
                "family_id": rec.family_id,
                "device_id": device_id,
                "is_revoked": False
            }
            self.refresh_tokens[new_ref_hash] = TokenRecord(
                token_hash=new_ref_hash,
                family_id=rec.family_id,
                device_id=device_id,
                issuance_op_id=client_operation_id,
                issuance_body_hash=body_hash
            )

            response_payload = {
                "accessToken": new_acc,
                "refreshToken": new_ref,
                "expiresIn": 900
            }

            # Store in 120s cache
            cache_key = f"resp:{device_id}:{client_operation_id}"
            self.cached_responses[cache_key] = CachedResponse(
                encrypted_payload=response_payload,
                expires_at=simulated_now + 120.0
            )
            self.idempotency_store[idemp_key_combined] = {
                "body_hash": body_hash,
                "committed_at": simulated_now
            }

            return 200, response_payload

    def handle_reauth(
        self,
        device_id: str,
        client_operation_id: Optional[str] = None,
        raw_body: Optional[bytes] = None,
        simulated_now: Optional[float] = None
    ) -> tuple[int, dict]:
        """Device performs Keystore-backed re-authentication after expired retry.
        Preserves existing grant ID and original expiry. Strictly rejects revoked/expired grants.
        """
        now = simulated_now if simulated_now is not None else time.time()
        with self._lock:
            dev = self.devices.get(device_id)
            if not dev or dev["status"] != "ACTIVE":
                return 401, {"code": "DEVICE_NOT_FOUND"}

            grant_id = dev.get("grant_id")
            grant = self.grants.get(grant_id) if grant_id else None
            if not grant or grant.status != "ACTIVE":
                return 401, {"code": "GRANT_INACTIVE", "message": "Grant is revoked or missing"}
            if grant.expires_at <= now:
                return 401, {"code": "GRANT_EXPIRED", "message": "Grant has expired"}

            # Check idempotency / recovery cache if client_operation_id provided
            if client_operation_id:
                body_hash = sha256_hex(raw_body) if raw_body is not None else ""
                idemp_key = f"{device_id}:{client_operation_id}"
                if idemp_key in self.idempotency_store:
                    prev_body_hash = self.idempotency_store[idemp_key]["body_hash"]
                    if prev_body_hash != body_hash:
                        return 409, {"code": "IDEMPOTENCY_CONFLICT", "message": "Payload modified for existing operation ID"}
                    cache_key = f"resp:{device_id}:{client_operation_id}"
                    cached = self.cached_responses.get(cache_key)
                    if cached:
                        if now <= cached.expires_at:
                            return 200, {
                                **cached.encrypted_payload,
                                "_recoveredFromCache": True
                            }
                        return 410, {"code": "REFRESH_RETRY_EXPIRED", "message": "Recovery window expired"}

            # Retain SAME grant ID and original expiry
            orig_expiry = grant.expires_at

            # In one atomic transaction boundary: revoke existing families and tokens for this device
            for fam in self.token_families.values():
                if fam["device_id"] == device_id:
                    fam["is_revoked"] = True

            for at in self.access_tokens.values():
                if at["device_id"] == device_id:
                    at["is_revoked"] = True

            for rt in self.refresh_tokens.values():
                if rt.device_id == device_id:
                    rt.is_revoked = True

            # Create ONE new token family bound to SAME grant
            new_fam_id = f"fam_{uuid.uuid4().hex[:16]}"
            fam_expires_at = min(now + 30 * 86400, orig_expiry)
            self.token_families[new_fam_id] = {
                "device_id": device_id,
                "is_revoked": False,
                "grant_id": grant_id,
                "expires_at": fam_expires_at
            }

            new_acc = generate_token()
            new_ref = generate_token()
            new_acc_hash = sha256_hex(new_acc.encode('utf-8'))
            new_ref_hash = sha256_hex(new_ref.encode('utf-8'))

            self.access_tokens[new_acc_hash] = {
                "family_id": new_fam_id,
                "device_id": device_id,
                "is_revoked": False
            }
            op_id = client_operation_id or "reauth_init"
            b_hash = sha256_hex(raw_body) if raw_body is not None else "reauth_body"
            self.refresh_tokens[new_ref_hash] = TokenRecord(
                token_hash=new_ref_hash,
                family_id=new_fam_id,
                device_id=device_id,
                issuance_op_id=op_id,
                issuance_body_hash=b_hash
            )

            response_payload = {
                "accessToken": new_acc,
                "refreshToken": new_ref,
                "grantId": grant_id,
                "grantExpiresAt": orig_expiry,
                "reauthSuccess": True,
                "expiresIn": 900
            }

            if client_operation_id:
                cache_key = f"resp:{device_id}:{client_operation_id}"
                self.cached_responses[cache_key] = CachedResponse(
                    encrypted_payload=response_payload,
                    expires_at=now + 120.0
                )
                self.idempotency_store[f"{device_id}:{client_operation_id}"] = {
                    "body_hash": b_hash,
                    "committed_at": now
                }

            return 200, response_payload

    def handle_delete_device(self, device_id: str, idempotency_key_header: Optional[str]) -> tuple[int, dict]:
        """DELETE /v1/devices/me: bodyless, no Idempotency-Key allowed, naturally idempotent."""
        if idempotency_key_header is not None:
            return 400, {"code": "IDEMPOTENCY_KEY_NOT_PERMITTED", "message": "DELETE must not send Idempotency-Key"}

        with self._lock:
            dev = self.devices.get(device_id)
            if not dev or dev["status"] != "ACTIVE":
                return 410, {"code": "DEVICE_ALREADY_REVOKED"}

            dev["status"] = "REVOKED"
            # Revoke grant
            if dev.get("grant_id") in self.grants:
                self.grants[dev["grant_id"]].status = "REVOKED"
            for fam in self.token_families.values():
                if fam["device_id"] == device_id:
                    fam["is_revoked"] = True
            return 204, {}


def run_state_probe():
    print("[*] Starting Lost-Response Refresh Recovery State Probe...")
    state = AuthControlPlaneState()
    device_id = "test_dev_9ecbb43ac6fbda663bd81db7f89b50b955692453"

    # Step 1: Initial Enrollment
    acc1, ref1 = state.enroll_device(device_id)
    print(f"[+] Device enrolled. Issued Initial tokens (family active, unique UUIDv4).")

    # Step 2: First Refresh (Happy path)
    t0 = 1700000000.0
    op1 = "op-refresh-1001"
    body1 = f'{{"refreshToken":"{ref1}","clientOperationId":"{op1}"}}'.encode('utf-8')

    status, resp1 = state.handle_refresh(
        device_id=device_id,
        idempotency_key=op1,
        refresh_token=ref1,
        client_operation_id=op1,
        raw_body=body1,
        simulated_now=t0
    )
    assert status == 200, f"Expected 200, got {status}"
    acc2 = resp1["accessToken"]
    ref2 = resp1["refreshToken"]
    print(f"  [PASS] Initial rotation succeeded (op={op1})")

    # Step 3: Exact Retry within 120s (Lost Response Simulation)
    t_retry = t0 + 25.0 # 25 seconds later
    status_retry, resp_retry = state.handle_refresh(
        device_id=device_id,
        idempotency_key=op1,
        refresh_token=ref1, # same used token
        client_operation_id=op1, # same op
        raw_body=body1, # exact same body
        simulated_now=t_retry
    )
    assert status_retry == 200, f"Expected 200 on retry, got {status_retry}"
    assert resp_retry.get("_recoveredFromCache") is True, "Must recover from cache"
    assert resp_retry["accessToken"] == acc2, "Must return identical access token"
    assert resp_retry["refreshToken"] == ref2, "Must return identical refresh token"
    # Verify token family is NOT revoked
    rec2 = state.refresh_tokens[sha256_hex(ref2.encode('utf-8'))]
    assert rec2.is_used is False, "Successor token must remain unused"
    assert rec2.is_revoked is False, "Successor token must not be revoked"
    assert rec2.issuance_op_id == op1, "Successor token must record issuance op id"
    print(f"  [PASS] Exact retry in 120s window recovered cached response without double-rotation or revocation")

    # Step 4: Concurrent Exact Retries (5 burst requests)
    for i in range(5):
        s, r = state.handle_refresh(
            device_id=device_id,
            idempotency_key=op1,
            refresh_token=ref1,
            client_operation_id=op1,
            raw_body=body1,
            simulated_now=t0 + 30.0 + i
        )
        assert s == 200 and r["refreshToken"] == ref2
    print(f"  [PASS] 5 burst retries returned identical response safely")

    # Step 5: Payload Conflict (Same op ID, altered body)
    altered_body = f'{{"refreshToken":"{ref1}","clientOperationId":"{op1}","extra":"tamper"}}'.encode('utf-8')
    status_conflict, resp_conflict = state.handle_refresh(
        device_id=device_id,
        idempotency_key=op1,
        refresh_token=ref1,
        client_operation_id=op1,
        raw_body=altered_body,
        simulated_now=t0 + 35.0
    )
    assert status_conflict == 409, f"Expected 409 Conflict, got {status_conflict}"
    assert resp_conflict["code"] == "IDEMPOTENCY_CONFLICT"
    print(f"  [PASS] Altered payload with same op ID yielded 409 Conflict without side effects")

    # Step 6: Unauthorized Cross-Device Refresh (Device B presents Device A's token)
    dev_b = "test_dev_b_attacker_007"
    state.enroll_device(dev_b)
    op_cross = "op-cross-device-01"
    body_cross = f'{{"refreshToken":"{ref2}","clientOperationId":"{op_cross}"}}'.encode('utf-8')
    s_cross, r_cross = state.handle_refresh(
        device_id=dev_b,
        idempotency_key=op_cross,
        refresh_token=ref2,
        client_operation_id=op_cross,
        raw_body=body_cross,
        simulated_now=t0 + 38.0
    )
    assert s_cross == 401, f"Expected 401 for cross-device refresh, got {s_cross}"
    assert r_cross["code"] == "TOKEN_DEVICE_MISMATCH"
    # Crucial: Victim's family must NOT be revoked!
    fam_rec = state.token_families[rec2.family_id]
    assert fam_rec["is_revoked"] is False, "Victim token family must NOT be revoked on foreign device probe"
    print(f"  [PASS] Cross-device refresh attempt safely rejected without corrupting victim's family")

    # Step 7: Token Reuse Attack Detection
    # Attacker tries to use consumed ref1 with a NEW operation ID on original device
    attacker_op = "op-adversary-evil-999"
    attacker_body = f'{{"refreshToken":"{ref1}","clientOperationId":"{attacker_op}"}}'.encode('utf-8')
    status_reuse, resp_reuse = state.handle_refresh(
        device_id=device_id,
        idempotency_key=attacker_op,
        refresh_token=ref1,
        client_operation_id=attacker_op,
        raw_body=attacker_body,
        simulated_now=t0 + 40.0
    )
    assert status_reuse == 401, f"Expected 401 for reuse, got {status_reuse}"
    assert resp_reuse["code"] == "TOKEN_REUSED"
    # Verify family is completely revoked!
    fam_id = rec2.family_id
    assert state.token_families[fam_id]["is_revoked"] is True, "Family must be revoked upon reuse"
    # Legitimate client trying to use ref2 now fails
    op_legit = "op-legit-next"
    body_legit = f'{{"refreshToken":"{ref2}","clientOperationId":"{op_legit}"}}'.encode('utf-8')
    s_rev, r_rev = state.handle_refresh(
        device_id=device_id,
        idempotency_key=op_legit,
        refresh_token=ref2,
        client_operation_id=op_legit,
        raw_body=body_legit,
        simulated_now=t0 + 45.0
    )
    assert s_rev == 401 and r_rev["code"] == "FAMILY_REVOKED"
    print(f"  [PASS] Token reuse attack triggered immediate family revocation")

    # Step 8: Expired Recovery Window (> 120s) & Keystore Reauth
    acc3, ref3 = state.enroll_device("test_dev_expired_recovery_002")
    t1 = 1700001000.0
    op_exp = "op-exp-001"
    body_exp = f'{{"refreshToken":"{ref3}","clientOperationId":"{op_exp}"}}'.encode('utf-8')

    s_exp, _ = state.handle_refresh(
        device_id="test_dev_expired_recovery_002",
        idempotency_key=op_exp,
        refresh_token=ref3,
        client_operation_id=op_exp,
        raw_body=body_exp,
        simulated_now=t1
    )
    assert s_exp == 200

    # Retry arrives at 125s (> 120s window)
    s_late, r_late = state.handle_refresh(
        device_id="test_dev_expired_recovery_002",
        idempotency_key=op_exp,
        refresh_token=ref3,
        client_operation_id=op_exp,
        raw_body=body_exp,
        simulated_now=t1 + 125.0
    )
    assert s_late == 410, f"Expected 410, got {s_late}"
    assert r_late["code"] == "REFRESH_RETRY_EXPIRED"
    # Family is NOT revoked
    dev2_fam = state.refresh_tokens[sha256_hex(ref3.encode('utf-8'))].family_id
    assert state.token_families[dev2_fam]["is_revoked"] is False, "Family must NOT be revoked on expiration"
    print(f"  [PASS] Retry after 125s returned 410 REFRESH_RETRY_EXPIRED without false reuse detection")

    # Keystore Reauth flow recovery
    s_reauth, r_reauth = state.handle_reauth("test_dev_expired_recovery_002", simulated_now=t1 + 130.0)
    assert s_reauth == 200
    assert r_reauth["reauthSuccess"] is True
    assert r_reauth["grantId"] == state.devices["test_dev_expired_recovery_002"]["grant_id"]
    print(f"  [PASS] Keystore reauth recovered active session cleanly while preserving grant ID")

    # Step 9: Grant Expiration & Revocation Validation
    t_grant = 1700002000.0
    acc_g, ref_g = state.enroll_device("test_dev_grant_exp", grant_duration=60.0, simulated_now=t_grant)
    op_g = "op-grant-01"
    body_g = f'{{"refreshToken":"{ref_g}","clientOperationId":"{op_g}"}}'.encode('utf-8')

    # Advance time past grant expiration (65 seconds later)
    s_g_exp, r_g_exp = state.handle_refresh(
        device_id="test_dev_grant_exp",
        idempotency_key=op_g,
        refresh_token=ref_g,
        client_operation_id=op_g,
        raw_body=body_g,
        simulated_now=t_grant + 65.0
    )
    assert s_g_exp == 401 and r_g_exp["code"] == "GRANT_EXPIRED", f"Expected GRANT_EXPIRED, got {s_g_exp} {r_g_exp}"
    print(f"  [PASS] Grant expiration correctly enforced prior to token operations")

    # Step 10: Multi-threaded Concurrent Race Simulation with Barrier
    print("[*] Running multi-threaded concurrent refresh race test...")
    acc_race, ref_race = state.enroll_device("test_dev_race_001")
    t_race = 1700003000.0
    op_race = "op-race-exact-retry"
    body_race = f'{{"refreshToken":"{ref_race}","clientOperationId":"{op_race}"}}'.encode('utf-8')

    # First perform initial rotation
    s_init, r_init = state.handle_refresh(
        device_id="test_dev_race_001",
        idempotency_key=op_race,
        refresh_token=ref_race,
        client_operation_id=op_race,
        raw_body=body_race,
        simulated_now=t_race
    )
    assert s_init == 200
    expected_ref = r_init["refreshToken"]

    # Now launch 10 threads doing simultaneous retry of the same op
    barrier = threading.Barrier(10)
    race_results = []
    threads = []

    def worker():
        barrier.wait()
        res = state.handle_refresh(
            device_id="test_dev_race_001",
            idempotency_key=op_race,
            refresh_token=ref_race,
            client_operation_id=op_race,
            raw_body=body_race,
            simulated_now=t_race + 10.0
        )
        race_results.append(res)

    for _ in range(10):
        t = threading.Thread(target=worker)
        threads.append(t)
        t.start()

    for t in threads:
        t.join()

    assert len(race_results) == 10
    for s_res, payload_res in race_results:
        assert s_res == 200
        assert payload_res["refreshToken"] == expected_ref
        assert payload_res.get("_recoveredFromCache") is True
    print(f"  [PASS] 10 concurrent threads safely recovered identical cached token without data race")

    # Step 11: DELETE /v1/devices/me
    del_dev = "test_dev_delete_003"
    state.enroll_device(del_dev)

    s_bad_del, _ = state.handle_delete_device(del_dev, idempotency_key_header="forbidden-header-key")
    assert s_bad_del == 400

    s_del1, _ = state.handle_delete_device(del_dev, idempotency_key_header=None)
    assert s_del1 == 204
    assert state.devices[del_dev]["status"] == "REVOKED"

    s_del2, _ = state.handle_delete_device(del_dev, idempotency_key_header=None)
    assert s_del2 == 410
    print(f"  [PASS] DELETE /v1/devices/me verified naturally idempotent")

    # Step 12: Comprehensive F01 Reauth Regressions
    print("[*] Running F01 Reauth Security Regressions...")
    t_reauth_base = 1700010000.0

    # 12.1 Active device + Revoked grant -> MUST REJECT, no new family/tokens/grant
    dev_rev_grant = "test_dev_f01_revoked_grant"
    state.enroll_device(dev_rev_grant, grant_duration=3600.0, simulated_now=t_reauth_base)
    g_id = state.devices[dev_rev_grant]["grant_id"]
    state.grants[g_id].status = "REVOKED" # administratively revoked
    s_rev_g, r_rev_g = state.handle_reauth(dev_rev_grant, simulated_now=t_reauth_base + 10.0)
    assert s_rev_g == 401 and r_rev_g["code"] == "GRANT_INACTIVE", f"Expected 401 GRANT_INACTIVE, got {s_rev_g} {r_rev_g}"
    print(f"  [PASS] Reauth strictly rejected on revoked grant (no resurrection)")

    # 12.2 Active device + Expired grant -> MUST REJECT
    dev_exp_grant = "test_dev_f01_expired_grant"
    state.enroll_device(dev_exp_grant, grant_duration=60.0, simulated_now=t_reauth_base)
    s_exp_g, r_exp_g = state.handle_reauth(dev_exp_grant, simulated_now=t_reauth_base + 65.0)
    assert s_exp_g == 401 and r_exp_g["code"] == "GRANT_EXPIRED", f"Expected 401 GRANT_EXPIRED, got {s_exp_g} {r_exp_g}"
    print(f"  [PASS] Reauth strictly rejected on expired grant")

    # 12.3 Missing grant -> MUST REJECT
    dev_missing_g = "test_dev_f01_missing_grant"
    state.enroll_device(dev_missing_g, grant_duration=3600.0, simulated_now=t_reauth_base)
    state.devices[dev_missing_g]["grant_id"] = "grant_nonexistent_xyz"
    s_mis_g, r_mis_g = state.handle_reauth(dev_missing_g, simulated_now=t_reauth_base + 10.0)
    assert s_mis_g == 401 and r_mis_g["code"] == "GRANT_INACTIVE"
    print(f"  [PASS] Reauth strictly rejected on missing grant")

    # 12.4 Valid short grant without extension -> preserves identical grant ID and expiry!
    dev_short_g = "test_dev_f01_short_grant"
    acc_s0, ref_s0 = state.enroll_device(dev_short_g, grant_duration=500.0, simulated_now=t_reauth_base)
    orig_grant_id = state.devices[dev_short_g]["grant_id"]
    orig_grant_expiry = state.grants[orig_grant_id].expires_at
    ref_s0_hash = sha256_hex(ref_s0.encode('utf-8'))
    old_fam_id = state.refresh_tokens[ref_s0_hash].family_id

    s_reauth_valid, r_reauth_valid = state.handle_reauth(
        dev_short_g,
        client_operation_id="op-reauth-short-1",
        raw_body=b'{"clientOperationId":"op-reauth-short-1"}',
        simulated_now=t_reauth_base + 100.0
    )
    assert s_reauth_valid == 200
    assert r_reauth_valid["grantId"] == orig_grant_id, "Grant ID MUST remain strictly identical"
    assert r_reauth_valid["grantExpiresAt"] == orig_grant_expiry, "Grant expiry MUST NOT be extended"
    assert state.grants[orig_grant_id].expires_at == orig_grant_expiry, "Stored grant expiry unchanged"
    assert state.token_families[old_fam_id]["is_revoked"] is True, "Old token family must be revoked"

    # Old tokens stay revoked
    s_old_refresh, r_old_refresh = state.handle_refresh(
        dev_short_g,
        idempotency_key="op-try-old",
        refresh_token=ref_s0,
        client_operation_id="op-try-old",
        raw_body=b'{"refreshToken":"old"}',
        simulated_now=t_reauth_base + 110.0
    )
    assert s_old_refresh == 401 and r_old_refresh["code"] == "FAMILY_REVOKED"
    print(f"  [PASS] Valid short grant preserved without extension, old tokens remain revoked")

    # 12.5 Lost-response retry of reauth within 120s returns cached response
    s_reauth_retry, r_reauth_retry = state.handle_reauth(
        dev_short_g,
        client_operation_id="op-reauth-short-1",
        raw_body=b'{"clientOperationId":"op-reauth-short-1"}',
        simulated_now=t_reauth_base + 130.0
    )
    assert s_reauth_retry == 200
    assert r_reauth_retry.get("_recoveredFromCache") is True
    assert r_reauth_retry["refreshToken"] == r_reauth_valid["refreshToken"]
    print(f"  [PASS] Reauth exact retry within 120s returns cached response without secondary rotation")

    # 12.6 Reauth idempotency conflict (altered payload)
    s_reauth_conf, r_reauth_conf = state.handle_reauth(
        dev_short_g,
        client_operation_id="op-reauth-short-1",
        raw_body=b'{"clientOperationId":"op-reauth-short-1","tampered":true}',
        simulated_now=t_reauth_base + 140.0
    )
    assert s_reauth_conf == 409 and r_reauth_conf["code"] == "IDEMPOTENCY_CONFLICT"
    print(f"  [PASS] Reauth altered body conflict returned 409")

    # 12.7 Same-second successive reauths create distinct families cleanly
    s_reauth_s1, r_reauth_s1 = state.handle_reauth(dev_short_g, simulated_now=t_reauth_base + 200.0)
    s_reauth_s2, r_reauth_s2 = state.handle_reauth(dev_short_g, simulated_now=t_reauth_base + 200.0)
    assert s_reauth_s1 == 200 and s_reauth_s2 == 200
    ref_s1_hash = sha256_hex(r_reauth_s1["refreshToken"].encode('utf-8'))
    ref_s2_hash = sha256_hex(r_reauth_s2["refreshToken"].encode('utf-8'))
    fam_s1 = state.refresh_tokens[ref_s1_hash].family_id
    fam_s2 = state.refresh_tokens[ref_s2_hash].family_id
    assert fam_s1 != fam_s2, "Successive reauths must yield distinct families"
    assert state.token_families[fam_s1]["is_revoked"] is True, "Predecessor family revoked"
    assert state.token_families[fam_s2]["is_revoked"] is False, "Latest family active"
    print(f"  [PASS] Same-second reauths cleanly rotate families atomically")

    # 12.8 Device ID prefix isolation
    dev_prefix = "test_dev_prefix"
    dev_prefix_long = "test_dev_prefix_longer"
    state.enroll_device(dev_prefix, grant_duration=3600.0, simulated_now=t_reauth_base)
    state.enroll_device(dev_prefix_long, grant_duration=3600.0, simulated_now=t_reauth_base)
    s_p_reauth, _ = state.handle_reauth(dev_prefix, simulated_now=t_reauth_base + 10.0)
    assert s_p_reauth == 200
    long_fam = state.devices[dev_prefix_long]["grant_id"]
    assert state.grants[long_fam].status == "ACTIVE"
    print(f"  [PASS] Device ID prefix collisions safely isolated without side effects")

    # 12.9 Reauth after terminal DELETE / revoke -> strictly rejected
    s_reauth_after_del, r_reauth_after_del = state.handle_reauth(del_dev, simulated_now=t_reauth_base + 300.0)
    assert s_reauth_after_del == 401, f"Expected 401 after delete, got {s_reauth_after_del}"
    print(f"  [PASS] Reauth rejected after terminal DELETE (no resurrection)")

    print(f"\n==========================================")
    print(f" REFRESH STATE PROBE: 100% PASS")
    print(f"==========================================")


if __name__ == "__main__":
    run_state_probe()


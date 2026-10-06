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
                        return 401, {
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

    def handle_reauth(self, device_id: str) -> tuple[int, dict]:
        """Device performs Keystore-backed re-authentication after expired retry."""
        with self._lock:
            dev = self.devices.get(device_id)
            if not dev or dev["status"] != "ACTIVE":
                return 401, {"code": "DEVICE_NOT_FOUND"}

            # Revoke existing families for this device
            for fam in self.token_families.values():
                if fam["device_id"] == device_id:
                    fam["is_revoked"] = True

        # Issue new active family
        new_acc, new_ref = self.enroll_device(device_id)
        return 200, {
            "accessToken": new_acc,
            "refreshToken": new_ref,
            "reauthSuccess": True
        }

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
    assert s_late == 401, f"Expected 401, got {s_late}"
    assert r_late["code"] == "REFRESH_RETRY_EXPIRED"
    # Family is NOT revoked
    dev2_fam = state.refresh_tokens[sha256_hex(ref3.encode('utf-8'))].family_id
    assert state.token_families[dev2_fam]["is_revoked"] is False, "Family must NOT be revoked on expiration"
    print(f"  [PASS] Retry after 125s returned REFRESH_RETRY_EXPIRED without false reuse detection")

    # Keystore Reauth flow recovery
    s_reauth, r_reauth = state.handle_reauth("test_dev_expired_recovery_002")
    assert s_reauth == 200
    assert r_reauth["reauthSuccess"] is True
    print(f"  [PASS] Keystore reauth recovered active session cleanly")

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

    print(f"\n==========================================")
    print(f" REFRESH STATE PROBE: 100% PASS")
    print(f"==========================================")


if __name__ == "__main__":
    run_state_probe()


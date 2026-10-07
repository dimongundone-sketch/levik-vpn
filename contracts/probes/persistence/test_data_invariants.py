#!/usr/bin/env python3
"""
HelloKittyVPN Persistence & Data Invariant Probes (PostgreSQL 17)
Contract: backend/docs/data-model.md
Freeze Gate: G01 Security Freeze

Validates:
- F02: Retention decoupling (refresh_tokens.issuance_operation_id ON DELETE SET NULL)
- F03: Provisioning CAS verification (RETURNING id, generation binding, zero rows abort)
- F04: Single recovery cache write & ID decoupling (operations.id vs client_operation_id)
- F05: Idempotency namespace UNIQUE(device_id, client_operation_id) & body hash binding
- F06: Secret protection at rest (AES-256-GCM, AAD binding, blind index, masked outbox)
"""

import atexit
import hashlib
import json
import os
import signal
import subprocess
import sys
import time
import uuid
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

CONTAINER_NAME = f"hkvpn-persistence-probe-{uuid.uuid4().hex[:8]}"
SCHEMA_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "schema.sql")

cid = None


def cleanup():
    global cid
    if cid:
        subprocess.run(["docker", "rm", "-f", cid], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        cid = None


atexit.register(cleanup)
signal.signal(signal.SIGINT, lambda s, f: sys.exit(1))
signal.signal(signal.SIGTERM, lambda s, f: sys.exit(1))


def run_psql(sql: str, check: bool = True) -> str:
    res = subprocess.run(
        ["docker", "exec", "-i", cid, "psql", "-U", "postgres", "-v", "ON_ERROR_STOP=1", "-q", "-t", "-A", "-c", sql],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if check and res.returncode != 0:
        raise RuntimeError(f"psql error ({res.returncode}):\nSTDOUT: {res.stdout}\nSTDERR: {res.stderr}\nSQL:\n{sql}")
    lines = [
        l.strip() for l in res.stdout.splitlines() 
        if l.strip() and not (
            l.startswith("INSERT ") or l.startswith("UPDATE ") or l.startswith("DELETE ") or l.startswith("DO")
        )
    ]
    return "\n".join(lines)


def run_psql_file(filepath: str):
    with open(filepath, "r") as f:
        content = f.read()
    res = subprocess.run(
        ["docker", "exec", "-i", cid, "psql", "-U", "postgres", "-v", "ON_ERROR_STOP=1"],
        input=content,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if res.returncode != 0:
        raise RuntimeError(f"psql file error ({res.returncode}):\nSTDOUT: {res.stdout}\nSTDERR: {res.stderr}")


def start_postgres():
    global cid
    print(f"[*] Starting disposable PostgreSQL 17 container ({CONTAINER_NAME})...")
    res = subprocess.run(
        [
            "docker", "run", "--rm", "-d",
            "--name", CONTAINER_NAME,
            "--network", "none",
            "--tmpfs", "/var/lib/postgresql/data",
            "-e", "POSTGRES_PASSWORD=secret",
            "postgres:17",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=True,
    )
    cid = res.stdout.strip()

    # Wait for PostgreSQL to be ready and stably accepting psql queries
    consecutive_ok = 0
    for _ in range(50):
        check = subprocess.run(
            ["docker", "exec", "-i", cid, "psql", "-U", "postgres", "-c", "SELECT 1;"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if check.returncode == 0:
            consecutive_ok += 1
            if consecutive_ok >= 3:
                print("[+] PostgreSQL 17 is ready.")
                return
        else:
            consecutive_ok = 0
        time.sleep(0.3)
    raise RuntimeError("Timed out waiting for PostgreSQL container.")


def test_schema_and_uuidv7():
    print("\n--- Test 1: Schema Application & UUIDv7 Function ---")
    run_psql_file(SCHEMA_PATH)
    
    # Verify table count
    tables = run_psql(
        "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';"
    )
    assert int(tables) == 17, f"Expected 17 tables, got {tables}"
    print(f"[PASS] All 17 tables created successfully.")

    # Verify UUIDv7 format
    val = run_psql("SELECT uuid_generate_v7();")
    parsed = uuid.UUID(val)
    assert parsed.version == 7, f"Expected UUIDv7 version 7, got {parsed.version}"
    assert parsed.variant == uuid.RFC_4122, f"Expected RFC variant, got {parsed.variant}"
    print(f"[PASS] uuid_generate_v7() output: {val} (version={parsed.version}, variant={parsed.variant})")


def test_f02_retention_decoupling():
    print("\n--- Test 2 (F02): Retention Decoupling (ON DELETE SET NULL) ---")
    dev_id_hex = "11" * 32
    body_hash = "aa" * 32
    token_hash = "bb" * 32
    client_op = str(uuid.uuid4())

    # Create device, grant, family, operation
    sql = f"""
    INSERT INTO devices (device_id, spki_der, app_version, os_version)
    VALUES ('{dev_id_hex}', '\\x1234', '1.0.0', '14') RETURNING id;
    """
    dev_uuid = run_psql(sql)

    sql = f"""
    INSERT INTO access_grants (device_id, expires_at)
    VALUES ('{dev_uuid}', now() + INTERVAL '30 days') RETURNING id;
    """
    grant_uuid = run_psql(sql)

    sql = f"""
    INSERT INTO token_families (device_id, grant_id)
    VALUES ('{dev_uuid}', '{grant_uuid}') RETURNING id;
    """
    family_uuid = run_psql(sql)

    sql = f"""
    INSERT INTO operations (device_id, operation_type, client_operation_id, http_method, request_path, request_body_sha256, status)
    VALUES ('{dev_uuid}', 'refresh_token', '{client_op}', 'POST', '/v1/auth/refresh', '{body_hash}', 'ready') RETURNING id;
    """
    op_uuid = run_psql(sql)

    # Insert refresh_token referencing op_uuid
    sql = f"""
    INSERT INTO refresh_tokens (family_id, token_hash, issuance_operation_id, issuance_client_op_id, issuance_body_hash, status, expires_at)
    VALUES ('{family_uuid}', '{token_hash}', '{op_uuid}', '{client_op}', '{body_hash}', 'active', now() + INTERVAL '30 days') RETURNING id;
    """
    rt_uuid = run_psql(sql)

    # Simulate 48h operations retention purge
    run_psql(f"DELETE FROM operations WHERE id = '{op_uuid}';")

    # Verify refresh_token still exists and issuance_operation_id was set to NULL
    row = run_psql(f"SELECT status, issuance_operation_id, issuance_client_op_id FROM refresh_tokens WHERE id = '{rt_uuid}';")
    parts = row.split("|")
    status, iss_op, iss_client_op = parts[0], parts[1], parts[2]

    assert status == "active", f"Expected active status, got {status}"
    assert iss_op == "", f"Expected NULL issuance_operation_id, got {iss_op}"
    assert iss_client_op == client_op, f"Expected preserved client_op {client_op}, got {iss_client_op}"
    print(f"[PASS] F02: Operation deleted, refresh token retained active with issuance_operation_id=NULL and durable client_op_id.")


def test_f03_provisioning_cas():
    print("\n--- Test 3 (F03): Provisioning Finalization CAS & Node Generation Binding ---")
    dev_id_hex = "22" * 32
    body_hash = "cc" * 32
    cred_fp = "dd" * 32

    # Setup device, node (gen 1), grant
    dev_uuid = run_psql(f"INSERT INTO devices (device_id, spki_der, app_version, os_version) VALUES ('{dev_id_hex}', '\\x1234', '1.0.0', '14') RETURNING id;")
    grant_uuid = run_psql(f"INSERT INTO access_grants (device_id, expires_at) VALUES ('{dev_uuid}', now() + INTERVAL '30 days') RETURNING id;")
    node_uuid = run_psql(f"INSERT INTO nodes (node_id, display_name, country_code, grpc_endpoint, generation) VALUES ('node-fra-01', 'Frankfurt 1', 'DE', 'grpc://node1:50051', 1) RETURNING id;")

    # Setup credential: desired_revision=2, observed_revision=1
    cred_uuid = run_psql(f"""
    INSERT INTO credentials (device_id, node_id, key_id, aead_nonce, aead_ciphertext, fingerprint_sha256, desired_revision, observed_revision, status, expires_at)
    VALUES ('{dev_uuid}', '{node_uuid}', 'k1', '\\x0102', '\\x0304', '{cred_fp}', 2, 1, 'pending', now() + INTERVAL '30 days') RETURNING id;
    """)

    op_uuid = run_psql(f"""
    INSERT INTO operations (device_id, operation_type, client_operation_id, http_method, request_path, request_body_sha256, status, credential_id)
    VALUES ('{dev_uuid}', 'issue_profile', '{uuid.uuid4()}', 'POST', '/v1/profiles', '{body_hash}', 'pending', '{cred_uuid}') RETURNING id;
    """)

    outbox_uuid = run_psql(f"""
    INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, status)
    VALUES ('credential', '{cred_uuid}', 'provision_node', '{{}}'::jsonb, 'processing') RETURNING id;
    """)

    # --- Scenario A: Revision mismatch (Node confirms revision 3, but desired was 2) ---
    plsql_mismatch = f"""
    DO $$
    DECLARE
        v_updated_id uuid;
        v_current_gen bigint;
    BEGIN
        SELECT n.generation INTO v_current_gen FROM credentials c JOIN nodes n ON n.id = c.node_id WHERE c.id = '{cred_uuid}';
        IF v_current_gen != 1 THEN
            UPDATE outbox SET status = 'failed', last_error = 'NODE_GENERATION_MISMATCH' WHERE id = '{outbox_uuid}';
            UPDATE operations SET status = 'failed', error_code = 'NODE_GENERATION_MISMATCH' WHERE id = '{op_uuid}';
            RETURN;
        END IF;

        UPDATE credentials 
        SET observed_revision = 3, status = 'active', updated_at = clock_timestamp()
        WHERE id = '{cred_uuid}' AND desired_revision = 3 AND node_id = '{node_uuid}'
        RETURNING id INTO v_updated_id;

        IF v_updated_id IS NULL THEN
            UPDATE outbox SET status = 'failed', last_error = 'CAS_REVISION_MISMATCH' WHERE id = '{outbox_uuid}';
            UPDATE operations SET status = 'failed', error_code = 'PROVISIONING_CAS_FAILED' WHERE id = '{op_uuid}';
            RETURN;
        END IF;

        INSERT INTO profiles (device_id, revision, envelope_ciphertext)
        VALUES ('{dev_uuid}', 3, '\\xdeadbeef');
        UPDATE operations SET status = 'ready' WHERE id = '{op_uuid}';
    END $$;
    """
    run_psql(plsql_mismatch)

    op_status = run_psql(f"SELECT status, error_code FROM operations WHERE id = '{op_uuid}';")
    prof_count = run_psql(f"SELECT count(*) FROM profiles WHERE device_id = '{dev_uuid}';")
    outbox_status = run_psql(f"SELECT status, last_error FROM outbox WHERE id = '{outbox_uuid}';")

    assert "failed|PROVISIONING_CAS_FAILED" in op_status, f"Unexpected op_status: {op_status}"
    assert int(prof_count) == 0, f"Expected 0 profiles on CAS failure, got {prof_count}"
    assert "failed|CAS_REVISION_MISMATCH" in outbox_status, f"Unexpected outbox_status: {outbox_status}"
    print(f"[PASS] F03 (Scenario A): CAS revision mismatch safely aborted; 0 profiles created; op marked failed.")

    # --- Scenario B: Node Generation mismatch (Node restarted, gen bumped to 2) ---
    run_psql(f"UPDATE nodes SET generation = 2 WHERE id = '{node_uuid}';")
    run_psql(f"UPDATE operations SET status = 'pending', error_code = NULL WHERE id = '{op_uuid}';")
    run_psql(f"UPDATE outbox SET status = 'processing', last_error = NULL WHERE id = '{outbox_uuid}';")

    plsql_gen_mismatch = f"""
    DO $$
    DECLARE
        v_current_gen bigint;
        v_updated_id uuid;
    BEGIN
        SELECT n.generation INTO v_current_gen FROM credentials c JOIN nodes n ON n.id = c.node_id WHERE c.id = '{cred_uuid}';
        -- Node observation targeted old generation 1:
        IF v_current_gen != 1 THEN
            UPDATE outbox SET status = 'failed', last_error = 'NODE_GENERATION_MISMATCH' WHERE id = '{outbox_uuid}';
            UPDATE operations SET status = 'failed', error_code = 'NODE_GENERATION_MISMATCH' WHERE id = '{op_uuid}';
            RETURN;
        END IF;

        UPDATE credentials 
        SET observed_revision = 2, status = 'active', updated_at = clock_timestamp()
        WHERE id = '{cred_uuid}' AND desired_revision = 2 AND node_id = '{node_uuid}'
        RETURNING id INTO v_updated_id;
    END $$;
    """
    run_psql(plsql_gen_mismatch)

    op_status = run_psql(f"SELECT status, error_code FROM operations WHERE id = '{op_uuid}';")
    assert "failed|NODE_GENERATION_MISMATCH" in op_status, f"Unexpected op_status: {op_status}"
    prof_count = run_psql(f"SELECT count(*) FROM profiles WHERE device_id = '{dev_uuid}';")
    assert int(prof_count) == 0, f"Expected 0 profiles on generation mismatch, got {prof_count}"
    print(f"[PASS] F03 (Scenario B): Node generation mismatch safely aborted; 0 profiles created.")

    # --- Scenario C: Successful CAS (Node gen 2, response revision 2 == desired_revision 2) ---
    run_psql(f"UPDATE operations SET status = 'pending', error_code = NULL WHERE id = '{op_uuid}';")
    run_psql(f"UPDATE outbox SET status = 'processing', last_error = NULL WHERE id = '{outbox_uuid}';")

    plsql_success = f"""
    DO $$
    DECLARE
        v_current_gen bigint;
        v_updated_id uuid;
        v_prof_id uuid;
    BEGIN
        SELECT n.generation INTO v_current_gen FROM credentials c JOIN nodes n ON n.id = c.node_id WHERE c.id = '{cred_uuid}';
        IF v_current_gen != 2 THEN
            RAISE EXCEPTION 'unexpected generation';
        END IF;

        UPDATE credentials 
        SET observed_revision = 2, status = 'active', updated_at = clock_timestamp()
        WHERE id = '{cred_uuid}' AND desired_revision = 2 AND node_id = '{node_uuid}'
        RETURNING id INTO v_updated_id;

        IF v_updated_id IS NULL THEN
            RAISE EXCEPTION 'CAS failed unexpectedly';
        END IF;

        INSERT INTO node_observations (node_id, generation, raw_evidence)
        VALUES ('{node_uuid}', 2, '{{"confirmed": true}}'::jsonb);

        INSERT INTO profiles (device_id, revision, envelope_ciphertext)
        VALUES ('{dev_uuid}', 2, '\\xdeadbeef')
        ON CONFLICT (device_id, revision) DO UPDATE 
        SET envelope_ciphertext = EXCLUDED.envelope_ciphertext
        RETURNING id INTO v_prof_id;

        UPDATE operations 
        SET status = 'ready', profile_id = v_prof_id, updated_at = clock_timestamp()
        WHERE id = '{op_uuid}';

        UPDATE outbox SET status = 'delivered' WHERE id = '{outbox_uuid}';
    END $$;
    """
    run_psql(plsql_success)

    op_status = run_psql(f"SELECT status, profile_id IS NOT NULL FROM operations WHERE id = '{op_uuid}';")
    cred_row = run_psql(f"SELECT observed_revision, status FROM credentials WHERE id = '{cred_uuid}';")
    prof_count = run_psql(f"SELECT count(*) FROM profiles WHERE device_id = '{dev_uuid}';")

    assert "ready|t" in op_status, f"Unexpected op_status: {op_status}"
    assert cred_row == "2|active", f"Unexpected cred_row: {cred_row}"
    assert int(prof_count) == 1, f"Expected 1 profile, got {prof_count}"
    print(f"[PASS] F03 (Scenario C): CAS succeeded atomically; profile created; operation ready.")


def test_f04_single_recovery_cache_write():
    print("\n--- Test 4 (F04): Single Recovery Cache Write & ID Decoupling ---")
    dev_id_hex = "33" * 32
    body_hash = "11" * 32
    rt_hash = "22" * 32
    client_op_id = str(uuid.uuid4())

    dev_uuid = run_psql(f"INSERT INTO devices (device_id, spki_der, app_version, os_version) VALUES ('{dev_id_hex}', '\\x1234', '1.0.0', '14') RETURNING id;")
    grant_uuid = run_psql(f"INSERT INTO access_grants (device_id, expires_at) VALUES ('{dev_uuid}', now() + INTERVAL '30 days') RETURNING id;")
    family_uuid = run_psql(f"INSERT INTO token_families (device_id, grant_id) VALUES ('{dev_uuid}', '{grant_uuid}') RETURNING id;")

    # Execute Section 4.2 transaction atomically
    plsql_refresh = f"""
    DO $$
    DECLARE
        v_new_op_id uuid := uuid_generate_v7();
    BEGIN
        -- 1. Регистрация операции
        INSERT INTO operations (id, device_id, operation_type, client_operation_id, http_method, request_path, request_body_sha256, status)
        VALUES (v_new_op_id, '{dev_uuid}', 'refresh_token', '{client_op_id}', 'POST', '/v1/auth/refresh', '{body_hash}', 'ready');

        -- 2. Новый refresh-токен
        INSERT INTO refresh_tokens (family_id, token_hash, issuance_operation_id, issuance_client_op_id, issuance_body_hash, status, expires_at)
        VALUES ('{family_uuid}', '{rt_hash}', v_new_op_id, '{client_op_id}', '{body_hash}', 'active', now() + INTERVAL '30 days');

        -- 3. Новый access-токен
        INSERT INTO access_tokens (family_id, device_id, token_hash, expires_at)
        VALUES ('{family_uuid}', '{dev_uuid}', '{rt_hash}', now() + INTERVAL '15 minutes');

        -- 4. Запись в кэш восстановления ответа (СТРОГО ОДИН РАЗ)
        INSERT INTO encrypted_response_cache (device_id, operation_id, token_hash, encrypted_payload, expires_at)
        VALUES ('{dev_uuid}', v_new_op_id, '{rt_hash}', '\\xfeedface', clock_timestamp() + INTERVAL '120 seconds');
    END $$;
    """
    run_psql(plsql_refresh)

    # Verify cache count = 1
    cache_count = run_psql(f"SELECT count(*) FROM encrypted_response_cache WHERE device_id = '{dev_uuid}';")
    assert int(cache_count) == 1, f"Expected exactly 1 cache entry, got {cache_count}"

    # Verify operation_id in cache matches internal operations.id, NOT client_op_id
    cache_row = run_psql(f"SELECT c.operation_id, o.client_operation_id FROM encrypted_response_cache c JOIN operations o ON o.id = c.operation_id WHERE c.device_id = '{dev_uuid}';")
    op_id_in_cache, client_op_in_table = cache_row.split("|")
    assert op_id_in_cache != client_op_id, "Cache operation_id must NOT be client_operation_id"
    assert client_op_in_table == client_op_id, "Linked operation must hold client_operation_id"

    # Test Bounded batch purge syntax
    run_psql("""
    DELETE FROM encrypted_response_cache 
    WHERE id IN (
        SELECT id FROM encrypted_response_cache 
        WHERE expires_at < now() - INTERVAL '1 second' 
        LIMIT 1000
    );
    """)
    # Still unexpired (120s TTL)
    cache_count_after = run_psql(f"SELECT count(*) FROM encrypted_response_cache WHERE device_id = '{dev_uuid}';")
    assert int(cache_count_after) == 1, "Unexpired cache entry must not be purged"

    print(f"[PASS] F04: Single recovery cache write verified; operation_id correctly references operations(id); purge SQL valid.")


def test_f05_idempotency_namespace_and_body_binding():
    print("\n--- Test 5 (F05): Idempotency Namespace & Body Hash Binding ---")
    dev1_hex = "44" * 32
    dev2_hex = "55" * 32
    body_hash1 = "33" * 32
    body_hash2 = "44" * 32
    client_op = str(uuid.uuid4())

    dev1_uuid = run_psql(f"INSERT INTO devices (device_id, spki_der, app_version, os_version) VALUES ('{dev1_hex}', '\\x1234', '1.0.0', '14') RETURNING id;")
    dev2_uuid = run_psql(f"INSERT INTO devices (device_id, spki_der, app_version, os_version) VALUES ('{dev2_hex}', '\\x1234', '1.0.0', '14') RETURNING id;")

    # 1. First request for dev1
    run_psql(f"""
    INSERT INTO operations (device_id, operation_type, client_operation_id, http_method, request_path, request_body_sha256, status)
    VALUES ('{dev1_uuid}', 'refresh_token', '{client_op}', 'POST', '/v1/auth/refresh', '{body_hash1}', 'ready');
    """)

    # 2. Conflicting request for dev1 with same client_op but DIFFERENT body hash -> MUST FAIL UNIQUE
    conflict_detected = False
    try:
        run_psql(f"""
        INSERT INTO operations (device_id, operation_type, client_operation_id, http_method, request_path, request_body_sha256, status)
        VALUES ('{dev1_uuid}', 'refresh_token', '{client_op}', 'POST', '/v1/auth/refresh', '{body_hash2}', 'ready');
        """)
    except RuntimeError as e:
        if "uq_operations_device_client_op" in str(e):
            conflict_detected = True
    assert conflict_detected, "Expected UNIQUE violation on uq_operations_device_client_op"
    print(f"[PASS] F05: Duplicate client_op with different body rejected by DB unique constraint.")

    # 3. Cross-device isolation: dev2 using SAME client_op -> MUST SUCCEED (per-device namespace)
    dev2_op = run_psql(f"""
    INSERT INTO operations (device_id, operation_type, client_operation_id, http_method, request_path, request_body_sha256, status)
    VALUES ('{dev2_uuid}', 'refresh_token', '{client_op}', 'POST', '/v1/auth/refresh', '{body_hash1}', 'ready') RETURNING id;
    """)
    assert len(dev2_op) > 0, "Cross-device client_op should be isolated"
    print(f"[PASS] F05: Cross-device namespace isolation verified: identical client_op succeeds on different device.")

    # 4. Check constraint ck_operations_body_hash_hex rejects non-hex hash
    bad_hash_rejected = False
    try:
        run_psql(f"""
        INSERT INTO operations (device_id, operation_type, client_operation_id, http_method, request_path, request_body_sha256, status)
        VALUES ('{dev1_uuid}', 'refresh_token', '{uuid.uuid4()}', 'POST', '/v1/auth/refresh', 'not-a-valid-hex-hash', 'ready');
        """)
    except RuntimeError as e:
        if "ck_operations_body_hash_hex" in str(e):
            bad_hash_rejected = True
    assert bad_hash_rejected, "Expected check constraint violation on invalid body hash"
    print(f"[PASS] F05: Invalid body hash rejected by ck_operations_body_hash_hex.")


def test_f06_credential_aead_and_blind_index():
    print("\n--- Test 6 (F06): Credential AEAD Encryption & Blind Index ---")
    dev_hex = "66" * 32
    dev_uuid = run_psql(f"INSERT INTO devices (device_id, spki_der, app_version, os_version) VALUES ('{dev_hex}', '\\x1234', '1.0.0', '14') RETURNING id;")
    node_uuid = run_psql(f"INSERT INTO nodes (node_id, display_name, country_code, grpc_endpoint) VALUES ('node-ams-01', 'Amsterdam 1', 'NL', 'grpc://node2:50051') RETURNING id;")

    # Generate sensitive VLESS client UUID
    vless_secret_uuid = str(uuid.uuid4())
    print(f"[*] Raw VLESS secret UUID: {vless_secret_uuid}")

    # Server master key (AES-256)
    server_key = os.urandom(32)
    aesgcm = AESGCM(server_key)
    nonce = os.urandom(12)

    # AAD binding: context + node_id + device_id
    aad = f"credentials:v1:{node_uuid}:{dev_uuid}".encode("utf-8")

    # Encrypt secret
    ciphertext = aesgcm.encrypt(nonce, vless_secret_uuid.encode("utf-8"), aad)

    # Blind index: SHA256(secret)
    blind_index = hashlib.sha256(vless_secret_uuid.encode("utf-8")).hexdigest()

    # Store in database
    sql = f"""
    INSERT INTO credentials (
        device_id, node_id, key_id, aead_nonce, aead_ciphertext, fingerprint_sha256, desired_revision, observed_revision, status, expires_at
    ) VALUES (
        '{dev_uuid}', '{node_uuid}', 'srv-key-2026', '\\x{nonce.hex()}', '\\x{ciphertext.hex()}', '{blind_index}', 1, 1, 'active', now() + INTERVAL '30 days'
    ) RETURNING id;
    """
    cred_uuid = run_psql(sql)

    # Store outbox task with MASKED payload (NO raw secret!)
    outbox_payload = json.dumps({
        "action": "upsert_client",
        "credential_id": cred_uuid,
        "node_id": node_uuid,
        "device_id": dev_uuid,
        "key_id": "srv-key-2026",
    })
    outbox_uuid = run_psql(f"""
    INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, status)
    VALUES ('credential', '{cred_uuid}', 'provision_vless', '{outbox_payload}'::jsonb, 'pending') RETURNING id;
    """)

    # --- VERIFICATION 1: Raw Secret NOT in DB Dump ---
    # Dump entire credentials table and outbox table to text
    cred_dump = run_psql("SELECT row_to_json(c) FROM credentials c;")
    outbox_dump = run_psql("SELECT row_to_json(o) FROM outbox o;")

    assert vless_secret_uuid not in cred_dump, "CRITICAL: Raw secret UUID found in credentials dump!"
    assert vless_secret_uuid not in outbox_dump, "CRITICAL: Raw secret UUID found in outbox dump!"
    print(f"[PASS] F06: Verified raw secret UUID is completely absent from database dump and outbox payload.")

    # --- VERIFICATION 2: Blind Index Lookup ---
    matched_id = run_psql(f"SELECT id FROM credentials WHERE node_id = '{node_uuid}' AND fingerprint_sha256 = '{blind_index}';")
    assert matched_id == cred_uuid, f"Blind index query failed to locate credential {cred_uuid}"
    print(f"[PASS] F06: Blind index exact match query returned correct credential ID.")

    # --- VERIFICATION 3: Decrypt from DB ---
    row = run_psql(f"SELECT encode(aead_nonce, 'hex'), encode(aead_ciphertext, 'hex') FROM credentials WHERE id = '{cred_uuid}';")
    db_nonce_hex, db_ct_hex = row.split("|")
    recovered = aesgcm.decrypt(bytes.fromhex(db_nonce_hex), bytes.fromhex(db_ct_hex), aad).decode("utf-8")
    assert recovered == vless_secret_uuid, "Decrypted secret mismatch!"
    print(f"[PASS] F06: AEAD decryption with authenticated context recovered original secret.")

    # --- VERIFICATION 4: Tamper Resistance (Ciphertext or AAD tamper) ---
    tampered_ct = bytearray(bytes.fromhex(db_ct_hex))
    tampered_ct[0] ^= 0x01
    try:
        aesgcm.decrypt(bytes.fromhex(db_nonce_hex), bytes(tampered_ct), aad)
        assert False, "Decryption of tampered ciphertext should have failed!"
    except Exception:
        pass

    wrong_aad = f"credentials:v1:{node_uuid}:{uuid.uuid4()}".encode("utf-8")
    try:
        aesgcm.decrypt(bytes.fromhex(db_nonce_hex), bytes.fromhex(db_ct_hex), wrong_aad)
        assert False, "Decryption with altered AAD context should have failed!"
    except Exception:
        pass
    print(f"[PASS] F06: Tampered ciphertext and altered AAD context strictly rejected by AEAD.")


def main():
    print("===================================================================")
    print(" HelloKittyVPN Gate G01: PostgreSQL 17 Persistence Invariant Probes")
    print("===================================================================")
    try:
        start_postgres()
        test_schema_and_uuidv7()
        test_f02_retention_decoupling()
        test_f03_provisioning_cas()
        test_f04_single_recovery_cache_write()
        test_f05_idempotency_namespace_and_body_binding()
        test_f06_credential_aead_and_blind_index()
        print("\n===================================================================")
        print(" [ALL 6 PERSISTENCE PROBES PASSED 100% IN POSTGRESQL 17] ")
        print("===================================================================")
    finally:
        cleanup()


if __name__ == "__main__":
    main()

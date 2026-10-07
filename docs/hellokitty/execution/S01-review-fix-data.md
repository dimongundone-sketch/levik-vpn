# S01-R2-B: Persistence & Data Invariant Verification Report

- **Date**: 2026-10-07
- **Target Gate**: G01 Contract & Security Freeze
- **Engine**: PostgreSQL 17 (Docker `postgres:17` on tmpfs, `--network none`)
- **Probes**: [`contracts/probes/persistence/test_data_invariants.py`](file:///root/projects/hellokittyvpn/contracts/probes/persistence/test_data_invariants.py)
- **Schema**: [`contracts/probes/persistence/schema.sql`](file:///root/projects/hellokittyvpn/contracts/probes/persistence/schema.sql)
- **Status**: **VERIFIED (100% PASS)**

---

## 1. Summary of Defect Fixes

| Finding | Defect Description | Architecture / Contract Fix | Test Evidence |
|---|---|---|---|
| **F02** | `refresh_tokens.issuance_operation_id` tightly coupled to `operations` table; operation purge would delete active session tokens. | Altered foreign key to `ON DELETE SET NULL`. Retained durable fields `issuance_client_op_id` and `issuance_body_hash` directly on `refresh_tokens`. | Verified operation purge leaves active refresh token with `issuance_operation_id = NULL` and durable client operation ID intact. |
| **F03** | Finalization transaction did not check CAS `RETURNING id`, zero rows check, or `node_generation`; premature profile creation on race condition. | Added `RETURNING id INTO updated_id`, verified `rows_updated > 0`, bound CAS to current `node_generation` and active grant; transaction aborts on mismatch. | Verified: (1) revision mismatch aborts, 0 profiles created; (2) node generation mismatch aborts; (3) valid CAS creates profile and marks operation ready. |
| **F04** | Section 4.2 had duplicate recovery cache insert and duplicate COMMIT; cached client UUID instead of internal UUIDv7. | Removed redundant post-commit cache INSERT and redundant COMMIT; bound single pre-commit insert to internal `operations.id`. | Verified exactly 1 cache row inserted; `operation_id` references `operations(id)`; bounded batch purge syntax verified. |
| **F05** | Underspecified idempotency binding allowed same key with different body/path; namespace per-device was ambiguous. | Bound operations table to `UNIQUE(device_id, client_operation_id)` with `http_method, request_path, request_body_sha256`. Check constraint on body hash hex. | Verified: (1) conflict on altered body; (2) cross-device namespace isolation succeeds; (3) check constraint rejects non-hex body hash. |
| **F06** | `credentials` table and outbox payload stored raw plaintext VLESS secret UUID in database. | Encrypted credentials at rest via AES-256-GCM (`aead_nonce, aead_ciphertext`), AAD binding, and blind index `fingerprint_sha256 = SHA256(secret)`. Outbox payload strictly masked. | Verified: (1) raw secret UUID absent from DB dump and outbox; (2) blind index query locates credential; (3) AEAD decrypts; (4) tamper rejected. |

---

## 2. Test Execution Log

```text
===================================================================
 HelloKittyVPN Gate G01: PostgreSQL 17 Persistence Invariant Probes
===================================================================
[*] Starting disposable PostgreSQL 17 container (hkvpn-persistence-probe-4b13235e)...
[+] PostgreSQL 17 is ready.

--- Test 1: Schema Application & UUIDv7 Function ---
[PASS] All 17 tables created successfully.
[PASS] uuid_generate_v7() output: 01a11592-bf61-7812-b5be-8efe77272007 (version=7, variant=specified in RFC 4122)

--- Test 2 (F02): Retention Decoupling (ON DELETE SET NULL) ---
[PASS] F02: Operation deleted, refresh token retained active with issuance_operation_id=NULL and durable client_op_id.

--- Test 3 (F03): Provisioning Finalization CAS & Node Generation Binding ---
[PASS] F03 (Scenario A): CAS revision mismatch safely aborted; 0 profiles created; op marked failed.
[PASS] F03 (Scenario B): Node generation mismatch safely aborted; 0 profiles created.
[PASS] F03 (Scenario C): CAS succeeded atomically; profile created; operation ready.

--- Test 4 (F04): Single Recovery Cache Write & ID Decoupling ---
[PASS] F04: Single recovery cache write verified; operation_id correctly references operations(id); purge SQL valid.

--- Test 5 (F05): Idempotency Namespace & Body Hash Binding ---
[PASS] F05: Duplicate client_op with different body rejected by DB unique constraint.
[PASS] F05: Cross-device namespace isolation verified: identical client_op succeeds on different device.
[PASS] F05: Invalid body hash rejected by ck_operations_body_hash_hex.

--- Test 6 (F06): Credential AEAD Encryption & Blind Index ---
[*] Raw VLESS secret UUID: 6e7ded40-140d-42e0-b723-97a338d8a3ac
[PASS] F06: Verified raw secret UUID is completely absent from database dump and outbox payload.
[PASS] F06: Blind index exact match query returned correct credential ID.
[PASS] F06: AEAD decryption with authenticated context recovered original secret.
[PASS] F06: Tampered ciphertext and altered AAD context strictly rejected by AEAD.

===================================================================
 [ALL 6 PERSISTENCE PROBES PASSED 100% IN POSTGRESQL 17] 
===================================================================
```

---

## 3. Files Modified / Created

- [`backend/docs/data-model.md`](file:///root/projects/hellokittyvpn/backend/docs/data-model.md):
  - Updated DDL for `credentials`, `refresh_tokens`, `operations`, `encrypted_response_cache`.
  - Updated Section 4.2 refresh transaction (removed redundant insert and commit).
  - Updated Section 4.4 finalization transaction (CAS RETURNING id, zero rows abort, generation check).
  - Updated Section 5 retention table with PostgreSQL-valid bounded batch purges (`DELETE ... WHERE id IN (SELECT id ... LIMIT N)`).
- [`contracts/probes/persistence/schema.sql`](file:///root/projects/hellokittyvpn/contracts/probes/persistence/schema.sql): Complete DDL matching `backend/docs/data-model.md`.
- [`contracts/probes/persistence/test_data_invariants.py`](file:///root/projects/hellokittyvpn/contracts/probes/persistence/test_data_invariants.py): Automated executable probe verifying F02–F06 in isolated PostgreSQL 17 container.

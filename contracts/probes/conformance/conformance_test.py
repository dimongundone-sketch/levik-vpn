#!/usr/bin/env python3
"""
Hello Kitty VPN - Cross-Contract Conformance & Schema Validation Suite
Verifies:
1. Meta-schema validation of profile-v2 and routing-rules-v1 JSON Schemas (Draft 2020-12).
2. Local $ref resolution and structural integrity of OpenAPI 3.1.0 specifications.
3. Schema conformance of all golden positive vectors (payload, protected header, envelope).
4. Semantic invariant checks (equality of IDs, revisions, timestamps between header and payload).
5. OpenAPI example payload validation against defined component schemas.
Exits with code 0 on full compliance, non-zero on any deviation.
"""

import base64
import json
import os
import sys
import yaml
from jsonschema import Draft202012Validator, FormatChecker

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
CONTRACTS_DIR = os.path.join(REPO_ROOT, "contracts")


def b64url_decode(s: str) -> bytes:
    pad = len(s) % 4
    if pad:
        s += "=" * (4 - pad)
    return base64.urlsafe_b64decode(s.encode("ascii"))


def check_json_schemas():
    print("[1/5] Validating JSON Schemas against Draft 2020-12 meta-schema...")
    schema_files = [
        "profile-v2.schema.json",
        "routing-rules-v1.schema.json"
    ]
    for sf in schema_files:
        path = os.path.join(CONTRACTS_DIR, sf)
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        Draft202012Validator.check_schema(data)
        print(f"  [PASS] {sf} conforms to Draft 2020-12 meta-schema")


def check_openapi_structure():
    print("\n[2/5] Validating OpenAPI 3.1.0 specifications and $ref resolution...")
    openapi_files = [
        "mobile-v1.openapi.yaml",
        "node-xray-v2.openapi.yaml"
    ]
    for of in openapi_files:
        path = os.path.join(CONTRACTS_DIR, of)
        with open(path, "r", encoding="utf-8") as f:
            spec = yaml.safe_load(f)
        assert spec.get("openapi", "").startswith("3.1"), f"{of}: invalid openapi version"
        assert "paths" in spec, f"{of}: missing paths"
        assert "components" in spec, f"{of}: missing components"
        
        # Check internal $ref references resolve
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


def check_envelope_vectors_conformance():
    print("\n[3/5] Validating Profile Envelope v2 vectors against profile-v2.schema.json...")
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

    for vec in vectors_doc["positiveVectors"]:
        vec_id = vec["id"]
        env = vec["envelope"]
        plaintext = vec["plaintext"]

        # 1. Wire envelope schema validation
        ev.validate(env)

        # 2. Protected header schema validation
        meta = json.loads(b64url_decode(env["protected"]).decode("utf-8"))
        hv.validate(meta)

        # 3. Decrypted plaintext payload schema validation
        pv.validate(plaintext)

        # 4. Strict semantic bindings
        assert meta["purpose"] == "profile", f"{vec_id}: purpose must be 'profile'"
        assert meta["schemaVersion"] == 2, f"{vec_id}: schemaVersion must be 2"
        assert plaintext["profileRevision"] == meta["profileRevision"], f"{vec_id}: revision mismatch"
        assert plaintext["profileId"] == meta["profileId"], f"{vec_id}: profileId mismatch"
        assert plaintext["accessId"] == meta["accessId"], f"{vec_id}: accessId mismatch"
        assert plaintext["deviceId"] == meta["deviceId"], f"{vec_id}: deviceId mismatch"
        assert plaintext["issuedAt"] == meta["issuedAt"], f"{vec_id}: issuedAt mismatch"
        assert plaintext["credentialExpiresAt"] == meta["credentialExpiresAt"], f"{vec_id}: expiresAt mismatch"

        # 5. Engine & servers verification
        assert plaintext["engine"] == "xray", f"{vec_id}: engine must be 'xray'"
        assert len(plaintext["servers"]) > 0, f"{vec_id}: servers cannot be empty"
        for srv in plaintext["servers"]:
            assert srv["engine"] == "xray"
            assert srv["category"] in ("regular", "mobile", "mobile-allowlist")
            assert srv["networkRequirement"] in ("any", "cellular-allowlist")

        print(f"  [PASS] {vec_id}: Wire, metadata, payload, and semantic bindings 100% compliant")


def check_signing_vectors_conformance():
    print("\n[4/5] Validating RequestSigner v1 vectors against canonical spec...")
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


def check_openapi_examples():
    print("\n[5/5] Validating OpenAPI example payloads...")
    with open(os.path.join(CONTRACTS_DIR, "mobile-v1.openapi.yaml"), "r", encoding="utf-8") as f:
        spec = yaml.safe_load(f)

    # Validate /v1/profiles/{id} envelope example
    prof_resp = spec["paths"]["/v1/profiles/{id}"]["get"]["responses"]["200"]
    env_example = prof_resp["content"]["application/json"]["examples"]["envelope"]["value"]

    with open(os.path.join(CONTRACTS_DIR, "profile-v2.schema.json"), "r", encoding="utf-8") as f:
        profile_schema = json.load(f)

    env_sub = {"$schema": profile_schema["$schema"], "$defs": profile_schema["$defs"], **profile_schema["$defs"]["TunnelProfileEnvelopeV2"]}
    ev = Draft202012Validator(env_sub, format_checker=FormatChecker())
    ev.validate(env_example)

    # Check decoded protected header
    hdr = json.loads(b64url_decode(env_example["protected"]).decode("utf-8"))
    assert hdr.get("purpose") == "profile", f"OpenAPI example protected header purpose must be 'profile', got {hdr.get('purpose')}"
    assert hdr.get("schemaVersion") == 2, "OpenAPI example schemaVersion must be 2"

    print("  [PASS] /v1/profiles/{id} example envelope conforms to TunnelProfileEnvelopeV2")


def main():
    print("=================================================================")
    print(" HELLO KITTY VPN — CONTRACT CONFORMANCE & VALIDATION SUITE")
    print("=================================================================")
    try:
        check_json_schemas()
        check_openapi_structure()
        check_envelope_vectors_conformance()
        check_signing_vectors_conformance()
        check_openapi_examples()
        print("\n=================================================================")
        print(" ALL CONFORMANCE CHECKS PASSED SUCCESSFULLY (100% CLEAN)")
        print("=================================================================")
        sys.exit(0)
    except Exception as e:
        print(f"\n[FAIL] Conformance check failed: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()

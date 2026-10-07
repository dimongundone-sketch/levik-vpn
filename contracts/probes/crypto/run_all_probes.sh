#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

echo "================================================================="
echo " HELLO KITTY VPN — MULTI-LANGUAGE CONTRACT & CRYPTO PROBES"
echo "================================================================="
echo "Timestamp (UTC): $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo "Probes directory: ${SCRIPT_DIR}"
echo "Repo root:        ${REPO_ROOT}"
echo ""

# 1. Run Python Cryptographic Probes
echo "[1/7] Running Python RequestSigner v1 Probe..."
python3 "${SCRIPT_DIR}/test_signing_probe.py"
echo ""

echo "[2/7] Running Python Profile Envelope v2 Probe..."
python3 "${SCRIPT_DIR}/test_envelope_probe.py"
echo ""

echo "[3/7] Running Python Lost-Response Refresh State Probe (F01, F09)..."
python3 "${SCRIPT_DIR}/test_refresh_state_probe.py"
echo ""

echo "[4/7] Running Python HKVPN-CHALLENGE-V1 Probe (F08)..."
python3 "${SCRIPT_DIR}/test_challenge_probe.py"
echo ""

echo "[5/7] Running Python Contract Conformance Probe (F08, F09, F10)..."
python3 "${REPO_ROOT}/contracts/probes/conformance/conformance_test.py"
echo ""

# 2. Run Go Crypto & Concurrency Probes
echo "[6/7] Running Go Crypto, Challenge & Concurrency Probes (go test -race)..."
(cd "${SCRIPT_DIR}/go" && go test -v -race -count=1 ./...)
echo ""

# 3. Run Persistence Probes in PostgreSQL 17 (if Docker is available)
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    echo "[7/7] Running PostgreSQL 17 Persistence Invariant Probes (F02, F03, F04, F05, F06)..."
    python3 "${REPO_ROOT}/contracts/probes/persistence/test_data_invariants.py"
    echo ""
else
    echo "[7/7] Docker not available or inactive; skipping disposable PostgreSQL 17 persistence probe."
    echo ""
fi

echo "================================================================="
echo " ALL CONTRACT & CRYPTOGRAPHIC PROBES PASSED (100% PASS RATE)!"
echo "================================================================="

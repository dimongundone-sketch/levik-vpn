#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

echo "================================================================="
echo " HELLO KITTY VPN — CRYPTOGRAPHIC VERIFICATION PROBES HARNESS"
echo "================================================================="
echo "Timestamp (UTC): $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo "Probes directory: ${SCRIPT_DIR}"
echo ""

# 1. Run Python Probes
echo "[1/4] Running Python RequestSigner v1 Probe..."
python3 "${SCRIPT_DIR}/test_signing_probe.py"
echo ""

echo "[2/4] Running Python Profile Envelope v2 Probe..."
python3 "${SCRIPT_DIR}/test_envelope_probe.py"
echo ""

echo "[3/4] Running Python Lost-Response Refresh State Probe..."
python3 "${SCRIPT_DIR}/test_refresh_state_probe.py"
echo ""

# 2. Run Go Probes
echo "[4/4] Running Go Crypto Probes (Go 1.22+ Standard Library)..."
(cd "${SCRIPT_DIR}/go" && go test -v -count=1 ./...)
echo ""

echo "================================================================="
echo " ALL CRYPTOGRAPHIC PROBES PASSED SUCCESSFULLY (100% PASS RATE)!"
echo "================================================================="

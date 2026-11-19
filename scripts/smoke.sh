#!/usr/bin/env bash
# Phase 0 smoke test against a running stack (make up). Exits non-zero on any failure.
set -euo pipefail

API=${API_URL:-http://localhost:8080}
WEB=${WEB_URL:-http://localhost:3000}
CHAIN=${CHAIN_RPC_URL:-http://localhost:8545}

pass() { printf '  \033[32mok\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }

echo "ChainBank smoke test"

ready=$(curl -fsS "$API/q/health/ready") || fail "API readiness endpoint"
echo "$ready" | grep -Eq '"status": *"UP"' && pass "API ready (DB, migrations, ledger invariants)" || fail "API not UP: $ready"

banks=$(curl -fsS "$API/api/institutions") || fail "institutions endpoint"
for code in BANK_A BANK_B; do
  echo "$banks" | grep -q "\"$code\"" && pass "seeded $code" || fail "missing $code"
done

chain_id=$(curl -fsS -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' "$CHAIN") || fail "chain RPC"
echo "$chain_id" | grep -q '0x7a69' && pass "local chain id 31337" || fail "unexpected chain: $chain_id"

curl -fsS "$WEB" | grep -q "Nile Demo Bank" && pass "web renders live API data" || fail "web did not render"

echo "All smoke checks passed."

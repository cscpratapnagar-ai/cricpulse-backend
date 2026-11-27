#!/usr/bin/env bash
set -euo pipefail

: "${E2E_EMAIL:?E2E_EMAIL is required}"
: "${E2E_PASSWORD:?E2E_PASSWORD is required}"
BASE_URL="${BASE_URL:-http://localhost:8080/api}"

tmp="$(mktemp -d)"
jar="$tmp/session.cookies"
response="$tmp/response.json"
trap 'rm -rf "$tmp"' EXIT

assert_status() {
  local label="$1" expected="$2" actual="$3"
  [[ "$actual" == "$expected" ]] || {
    echo "FAIL: $label expected HTTP $expected, got $actual" >&2
    cat "$response" >&2 2>/dev/null || true
    exit 1
  }
}

echo "[1/4] Bootstrap CSRF and authenticate a disposable CI user"
csrf="$(curl -fsS -b "$jar" -c "$jar" "$BASE_URL/auth/csrf" | jq -r '.token')"
login_status="$(curl -sS -b "$jar" -c "$jar" -X POST "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $csrf" \
  --data "$(jq -nc --arg email "$E2E_EMAIL" --arg password "$E2E_PASSWORD" '{email:$email,password:$password}')" \
  -o "$response" -w '%{http_code}')"
assert_status "login" 200 "$login_status"

echo "[2/4] Confirm the authenticated cookie session works"
me_status="$(curl -sS -b "$jar" -c "$jar" "$BASE_URL/auth/me" -o "$response" -w '%{http_code}')"
assert_status "authenticated /auth/me" 200 "$me_status"

access_token="$(awk '$6 == "CRICPULSE_ACCESS" { token=$7 } END { print token }' "$jar")"
[[ -n "$access_token" ]] || { echo "FAIL: login did not issue an access cookie" >&2; exit 1; }

echo "[3/4] Logout and persist server-side revocation"
csrf="$(curl -fsS -b "$jar" -c "$jar" "$BASE_URL/auth/csrf" | jq -r '.token')"
logout_status="$(curl -sS -b "$jar" -c "$jar" -X POST "$BASE_URL/auth/logout" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $csrf" \
  --data '{}' -o "$response" -w '%{http_code}')"
assert_status "logout" 204 "$logout_status"

echo "[4/4] Prove the pre-logout token cannot be replayed after cookie clearing"
replay_status="$(curl -sS -H "Authorization: Bearer $access_token" \
  "$BASE_URL/auth/me" -o "$response" -w '%{http_code}')"
assert_status "revoked bearer replay" 401 "$replay_status"

echo "Access-token logout revocation E2E certification passed"

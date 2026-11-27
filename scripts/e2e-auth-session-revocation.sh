#!/usr/bin/env bash
set -euo pipefail

: "${E2E_EMAIL:?E2E_EMAIL is required}"
: "${E2E_PASSWORD:?E2E_PASSWORD is required}"
BASE_URL="${BASE_URL:-http://localhost:8080/api}"

tmp="$(mktemp -d)"
jar="$tmp/session.cookies"
touch "$jar"
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

echo "[1/6] Bootstrap CSRF and authenticate a disposable CI user"
csrf="$(curl -fsS -b "$jar" -c "$jar" "$BASE_URL/auth/csrf" | jq -r '.token')"
login_status="$(curl -sS -b "$jar" -c "$jar" -X POST "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $csrf" \
  --data "$(jq -nc --arg email "$E2E_EMAIL" --arg password "$E2E_PASSWORD" '{email:$email,password:$password}')" \
  -o "$response" -w '%{http_code}')"
assert_status "login" 200 "$login_status"

echo "[2/6] Confirm the authenticated cookie session works"
me_status="$(curl -sS -b "$jar" -c "$jar" "$BASE_URL/auth/me" -o "$response" -w '%{http_code}')"
assert_status "authenticated /auth/me" 200 "$me_status"
access_token="$(awk '$6 == "CRICPULSE_ACCESS" { token=$7 } END { print token }' "$jar")"
[[ -n "$access_token" ]] || { echo "FAIL: login did not issue an access cookie" >&2; exit 1; }

echo "[3/6] Verify the server-side device session is listed as current"
sessions_status="$(curl -sS -b "$jar" "$BASE_URL/auth/sessions" -o "$response" -w '%{http_code}')"
assert_status "session listing" 200 "$sessions_status"
session_id="$(jq -r '[.[] | select(.current == true)][0].sessionId // empty' "$response")"
[[ -n "$session_id" ]] || { echo "FAIL: current device session was not returned" >&2; cat "$response" >&2; exit 1; }

echo "[4/6] Logout and persist token plus device-session revocation"
csrf="$(curl -fsS -b "$jar" -c "$jar" "$BASE_URL/auth/csrf" | jq -r '.token')"
logout_status="$(curl -sS -b "$jar" -c "$jar" -X POST "$BASE_URL/auth/logout" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $csrf" \
  --data '{}' -o "$response" -w '%{http_code}')"
assert_status "logout" 204 "$logout_status"

echo "[5/6] Confirm the revoked cookie session is no longer authenticated"
cookie_replay_status="$(curl -sS -b "$jar" "$BASE_URL/auth/me" -o "$response" -w '%{http_code}')"
assert_status "revoked cookie session" 403 "$cookie_replay_status"

echo "[6/6] Prove the pre-logout Bearer token cannot be replayed"
replay_status="$(curl -sS -H "Authorization: Bearer $access_token" \
  "$BASE_URL/auth/me" -o "$response" -w '%{http_code}')"
assert_status "revoked bearer replay" 401 "$replay_status"

echo "Device-session and access-token logout revocation E2E certification passed"

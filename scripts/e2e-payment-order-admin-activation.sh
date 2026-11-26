#!/usr/bin/env bash
set -euo pipefail

: "${E2E_EMAIL:?E2E_EMAIL is required}"
: "${E2E_OWNER_B_EMAIL:?E2E_OWNER_B_EMAIL is required}"
: "${E2E_PASSWORD:?E2E_PASSWORD is required}"
: "${DATABASE_URL:?DATABASE_URL is required}"

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
RUN_KEY="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
RUN_DIGITS="${GITHUB_RUN_ID:-1234567}"
RUN_DIGITS="${RUN_DIGITS: -7}"
tmp="$(mktemp -d)"
cookie_a="$tmp/a.cookies"
cookie_b="$tmp/b.cookies"
cookie_admin="$tmp/admin.cookies"
trap 'rm -rf "$tmp"' EXIT

psql_conn="${DATABASE_URL#jdbc:}"
psql_db() { psql "$psql_conn" -v ON_ERROR_STOP=1 -X -qAt "$@"; }
request() {
  local jar="$1" method="$2" path="$3" body="${4:-}" token status
  token="$(curl -fsS -b "$jar" -c "$jar" "$BASE_URL/auth/csrf" | jq -r '.token')"
  local args=(-sS -b "$jar" -c "$jar" -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $token")
  [[ -z "$body" ]] || args+=(--data-binary "$body")
  status="$(curl -o "$tmp/response.json" -w '%{http_code}' "${args[@]}")"
  printf '%s' "$status"
}
login() {
  local jar="$1" email="$2" csrf status
  csrf="$(curl -fsS -b "$jar" -c "$jar" "$BASE_URL/auth/csrf" | jq -r '.token')"
  status="$(curl -sS -b "$jar" -c "$jar" -X POST "$BASE_URL/auth/login" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $csrf" \
    --data "$(jq -nc --arg e "$email" --arg p "$E2E_PASSWORD" '{email:$e,password:$p}')" \
    -o "$tmp/login.json" -w '%{http_code}')"
  [[ "$status" == "200" ]] || { echo "FAIL: login for $email returned HTTP $status" >&2; cat "$tmp/login.json" >&2; exit 1; }
}
assert_eq() {
  local label="$1" expected="$2" actual="$3"
  [[ "$actual" == "$expected" ]] || { echo "FAIL: $label expected '$expected', got '$actual'" >&2; exit 1; }
}
assert_order_state() {
  local id="$1" state="$2" count="$3" revoked="$4" admin="$5" note="$6" actual
  actual="$(psql_db -c "SELECT po.status || '|' ||
    (SELECT count(*) FROM user_entitlements ue WHERE ue.source_order_id=po.id) || '|' ||
    COALESCE((SELECT (ue.revoked_at IS NOT NULL)::text FROM user_entitlements ue WHERE ue.source_order_id=po.id LIMIT 1),'false') || '|' ||
    COALESCE((SELECT ue.activated_by::text FROM user_entitlements ue WHERE ue.source_order_id=po.id LIMIT 1),'') || '|' ||
    COALESCE(po.manual_note,'')
    FROM payment_orders po WHERE po.id='$id'")"
  assert_eq "database state for order $id" "$state|$count|$revoked|$admin|$note" "$actual"
}

echo "[1/8] Verify package catalogue and create ordinary requester session"
login "$cookie_a" "$E2E_EMAIL"
user_id="$(psql_db -c "SELECT id FROM users WHERE lower(email)=lower('$E2E_EMAIL') LIMIT 1")"
team_id="$(psql_db -c "SELECT id FROM teams WHERE owner_id='$user_id' ORDER BY created_at LIMIT 1")"
[[ -n "$user_id" && -n "$team_id" ]] || { echo "FAIL: CI user/team fixture missing" >&2; exit 1; }
status="$(request "$cookie_a" GET /payments/packages)"
assert_eq "package catalogue" 200 "$status"
jq -e 'any(.[]; .code=="CLUB_ACADEMY_PLUS" and .amountPaise==39900 and .currency=="INR")' "$tmp/response.json" >/dev/null

echo "[2/8] Create paid order without granting an entitlement"
key="payment-order-e2e-$RUN_KEY-$(cat /proc/sys/kernel/random/uuid)"
create_body="$(jq -nc --arg team "$team_id" --arg key "$key" '{packageCode:"CLUB_ACADEMY_PLUS",idempotencyKey:$key,teamId:$team}')"
status="$(request "$cookie_a" POST /payments/orders "$create_body")"
assert_eq "order creation" 201 "$status"
order_id="$(jq -r '.id' "$tmp/response.json")"
[[ -n "$order_id" && "$order_id" != null ]] || { echo "FAIL: create response omitted order id" >&2; exit 1; }
jq -e '.status=="PENDING" and .provider=="MANUAL" and .amountPaise==39900 and .currency=="INR"' "$tmp/response.json" >/dev/null
assert_order_state "$order_id" PENDING 0 false "" ""

echo "[3/8] Verify idempotency replay and conflicting key reuse"
status="$(request "$cookie_a" POST /payments/orders "$create_body")"
assert_eq "idempotent order retry" 200 "$status"
assert_eq "idempotent order ID" "$order_id" "$(jq -r '.id' "$tmp/response.json")"
conflict_body="$(jq -nc --arg team "$team_id" --arg key "$key" '{packageCode:"ACADEMY_BRANDING",idempotencyKey:$key,teamId:$team}')"
status="$(request "$cookie_a" POST /payments/orders "$conflict_body")"
assert_eq "conflicting idempotency reuse" 409 "$status"
assert_order_state "$order_id" PENDING 0 false "" ""

echo "[4/8] Deny activation by order owner and unrelated ordinary user"
status="$(request "$cookie_a" POST "/admin/payments/orders/$order_id/activate" '{"note":"CI manual receipt verified"}')"
assert_eq "owner activation denial" 403 "$status"
login "$cookie_b" "$E2E_OWNER_B_EMAIL"
status="$(request "$cookie_b" POST "/admin/payments/orders/$order_id/activate" '{"note":"CI manual receipt verified"}')"
assert_eq "ordinary user activation denial" 403 "$status"
assert_order_state "$order_id" PENDING 0 false "" ""

echo "[5/8] Create and promote a disposable CI-only admin in the disposable PostgreSQL service"
admin_email="cricpulse-ci-admin-$RUN_KEY@example.com"
admin_phone="904$RUN_DIGITS"
csrf="$(curl -fsS -b "$cookie_admin" -c "$cookie_admin" "$BASE_URL/auth/csrf" | jq -r '.token')"
register_status="$(curl -sS -b "$cookie_admin" -c "$cookie_admin" -X POST "$BASE_URL/users" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $csrf" \
  --data "$(jq -nc --arg n "CricPulse CI Payment Admin" --arg e "$admin_email" --arg p "$admin_phone" --arg pw "$E2E_PASSWORD" '{fullName:$n,email:$e,phone:$p,password:$pw}')" \
  -o "$tmp/register.json" -w '%{http_code}')"
assert_eq "disposable admin registration" 201 "$register_status"
escaped_email="$(printf '%s' "$admin_email" | sed "s/'/''/g")"
admin_id="$(psql_db -c "SELECT id FROM users WHERE lower(email)=lower('$escaped_email')")"
[[ -n "$admin_id" ]] || { echo "FAIL: disposable CI admin row not found" >&2; exit 1; }
psql_db -c "UPDATE users SET role='ADMIN' WHERE id='$admin_id'"
assert_eq "CI admin role" ADMIN "$(psql_db -c "SELECT role FROM users WHERE id='$admin_id'")"
login "$cookie_admin" "$admin_email"

echo "[6/8] Activate manually and verify one entitlement, manual note, and admin attribution"
note="CI receipt checked $RUN_KEY"
status="$(request "$cookie_admin" POST "/admin/payments/orders/$order_id/activate" "$(jq -nc --arg note "$note" '{note:$note}')")"
assert_eq "admin activation" 200 "$status"
jq -e '.status=="PAID" and .provider=="MANUAL"' "$tmp/response.json" >/dev/null
assert_order_state "$order_id" PAID 1 false "$admin_id" "$note"
repeat_note="repeat $note"
status="$(request "$cookie_admin" POST "/admin/payments/orders/$order_id/activate" "$(jq -nc --arg note "$repeat_note" '{note:$note}')")"
assert_eq "repeat admin activation" 200 "$status"
assert_order_state "$order_id" PAID 1 false "$admin_id" "$repeat_note"

echo "[7/8] Verify order and entitlement history are isolated to the purchasing user"
status="$(request "$cookie_a" GET /payments/orders)"
assert_eq "owner order history" 200 "$status"
jq -e --arg id "$order_id" 'any(.[]; .id==$id and .status=="PAID")' "$tmp/response.json" >/dev/null
status="$(request "$cookie_a" GET /payments/entitlements)"
assert_eq "owner entitlement history" 200 "$status"
jq -e 'any(.[]; .packageCode=="CLUB_ACADEMY_PLUS" and .activationSource=="MANUAL")' "$tmp/response.json" >/dev/null
status="$(request "$cookie_b" GET /payments/orders)"
assert_eq "other user order history status" 200 "$status"
jq -e --arg id "$order_id" 'all(.[]; .id!=$id)' "$tmp/response.json" >/dev/null
status="$(request "$cookie_b" GET /payments/entitlements)"
assert_eq "other user entitlement history status" 200 "$status"
jq -e 'all(.[]; .packageCode!="CLUB_ACADEMY_PLUS")' "$tmp/response.json" >/dev/null

echo "[8/8] Refunded orders cannot be manually reactivated"
user_id="$(psql_db -c "SELECT id FROM users WHERE lower(email)=lower('$(printf '%s' "$E2E_EMAIL" | sed "s/'/''/g")') LIMIT 1")"
team_id="$(psql_db -c "SELECT id FROM teams WHERE owner_id='$user_id' ORDER BY created_at LIMIT 1")"
[[ -n "$user_id" && -n "$team_id" ]] || { echo "FAIL: CI user's team fixture missing" >&2; exit 1; }
refund_order_id="$(cat /proc/sys/kernel/random/uuid)"
psql_db -c "INSERT INTO payment_orders(id,user_id,team_id,package_code,amount_paise,currency,status,provider,idempotency_key) VALUES ('$refund_order_id','$user_id','$team_id','CLUB_ACADEMY_PLUS',39900,'INR','REFUNDED','MANUAL','refund-fixture-$refund_order_id')"
status="$(request "$cookie_admin" POST "/admin/payments/orders/$refund_order_id/activate" '{"note":"must not reactivate refunded order"}')"
assert_eq "refunded order activation denial" 409 "$status"
assert_order_state "$refund_order_id" REFUNDED 0 false "" ""

echo "Payment order creation and admin activation E2E certification passed"

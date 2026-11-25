#!/usr/bin/env bash
set -euo pipefail

: "${E2E_EMAIL:?E2E_EMAIL is required}"
: "${E2E_OWNER_B_EMAIL:?E2E_OWNER_B_EMAIL is required}"
: "${E2E_PASSWORD:?E2E_PASSWORD is required}"
: "${DATABASE_URL:?DATABASE_URL is required}"

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
RUN_KEY="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
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
  if [[ -n "$body" ]]; then args+=(--data-binary "$body"); fi
  status="$(curl "${args[@]}" -o "$tmp/response.json" -w '%{http_code}')"
  printf '%s' "$status"
}
login() {
  local jar="$1" email="$2" csrf
  csrf="$(curl -fsS -b "$jar" -c "$jar" "$BASE_URL/auth/csrf" | jq -r '.token')"
  local status
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

echo "[1/8] Create ordinary requester and verify the public package catalogue"
login "$cookie_a" "$E2E_EMAIL"
status="$(request "$cookie_a" GET /payments/packages)"
assert_eq "package catalogue" 200 "$status"
jq -e 'any(.[]; .code=="ACADEMY_BRANDING" and .amountPaise==79900 and .currency=="INR")' "$tmp/response.json" >/dev/null

echo "[2/8] Create a paid order; order creation must not grant access"
key="payment-order-e2e-$RUN_KEY-$(cat /proc/sys/kernel/random/uuid)"
create_body="$(jq -nc --arg key "$key" '{packageCode:"ACADEMY_BRANDING",idempotencyKey:$key}')"
status="$(request "$cookie_a" POST /payments/orders "$create_body")"
assert_eq "order creation" 201 "$status"
order_id="$(jq -r '.id' "$tmp/response.json")"
[[ -n "$order_id" && "$order_id" != null ]] || { echo "FAIL: create response omitted order id" >&2; exit 1; }
jq -e '.status=="PENDING" and .provider=="MANUAL" and .amountPaise==79900 and .currency=="INR"' "$tmp/response.json" >/dev/null
assert_order_state "$order_id" PENDING 0 false "" ""

echo "[3/8] Same idempotency key returns the same pending order; changed purchase is rejected"
status="$(request "$cookie_a" POST /payments/orders "$create_body")"
assert_eq "idempotent order retry" 200 "$status"
assert_eq "idempotent order ID" "$order_id" "$(jq -r '.id' "$tmp/response.json")"
conflict_body="$(jq -nc --arg key "$key" '{packageCode:"CLUB_ACADEMY_PLUS",idempotencyKey:$key}')"
status="$(request "$cookie_a" POST /payments/orders "$conflict_body")"
assert_eq "idempotency key reused for different package" 409 "$status"
assert_order_state "$order_id" PENDING 0 false "" ""

echo "[4/8] User cannot activate their own order; a different ordinary user also cannot"
status="$(request "$cookie_a" POST "/admin/payments/orders/$order_id/activate" '{"note":"CI manual receipt verified"}')"
assert_eq "owner self-activation denial" 403 "$status"
login "$cookie_b" "$E2E_OWNER_B_EMAIL"
status="$(request "$cookie_b" POST "/admin/payments/orders/$order_id/activate" '{"note":"CI manual receipt verified"}')"
assert_eq "non-owner non-admin activation denial" 403 "$status"
assert_order_state "$order_id" PENDING 0 false "" ""

echo "[5/8] Promote a disposable CI identity directly in the isolated CI database, then log in as admin"
admin_email="cricpulse-ci-admin-$RUN_KEY@example.com"
admin_phone="904${GITHUB_RUN_ID:-1234567}"
admin_phone="${admin_phone:0:13}"
# Registration runs through the same public API as any user. Only the disposable CI DB row is role-promoted.
csrf="$(curl -fsS -b "$cookie_admin" -c "$cookie_admin" "$BASE_URL/auth/csrf" | jq -r '.token')"
register_status="$(curl -sS -b "$cookie_admin" -c "$cookie_admin" -X POST "$BASE_URL/users" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $csrf" \
  --data "$(jq -nc --arg n "CricPulse CI Payment Admin" --arg e "$admin_email" --arg p "$admin_phone" --arg pw "$E2E_PASSWORD" '{fullName:$n,email:$e,phone:$p,password:$pw}')" \
  -o "$tmp/register.json" -w '%{http_code}')"
assert_eq "disposable admin account registration" 201 "$register_status"
escaped_admin_email="$(printf '%s' "$admin_email" | sed "s/'/''/g")"
admin_id="$(psql_db -c "SELECT id FROM users WHERE lower(email)=lower('$escaped_admin_email')")"
[[ -n "$admin_id" ]] || { echo "FAIL: disposable admin identity missing from CI DB" >&2; exit 1; }
psql_db -c "UPDATE users SET role='ADMIN' WHERE id='$admin_id'"
assert_eq "CI admin role seed" ADMIN "$(psql_db -c "SELECT role FROM users WHERE id='$admin_id'")"
login "$cookie_admin" "$admin_email"

echo "[6/8] Admin manually activates order exactly once and records audit identity/note"
note="CI receipt checked $RUN_KEY"
status="$(request "$cookie_admin" POST "/admin/payments/orders/$order_id/activate" "$(jq -nc --arg note "$note" '{note:$note}')")"
assert_eq "admin activation" 200 "$status"
jq -e '.status=="PAID" and .provider=="MANUAL"' "$tmp/response.json" >/dev/null
assert_order_state "$order_id" PAID 1 false "$admin_id" "$note"

status="$(request "$cookie_admin" POST "/admin/payments/orders/$order_id/activate" "$(jq -nc --arg note "repeat $note" '{note:$note}')")"
assert_eq "repeated admin activation" 200 "$status"
assert_order_state "$order_id" PAID 1 false "$admin_id" "repeat $note"

echo "[7/8] Owner can read own order and entitlement; another user cannot see either"
status="$(request "$cookie_a" GET /payments/orders)"
assert_eq "owner order history" 200 "$status"
jq -e --arg id "$order_id" 'any(.[]; .id==$id and .status=="PAID")' "$tmp/response.json" >/dev/null
status="$(request "$cookie_a" GET /payments/entitlements)"
assert_eq "owner entitlement history" 200 "$status"
jq -e 'any(.[]; .packageCode=="ACADEMY_BRANDING" and .activationSource=="MANUAL")' "$tmp/response.json" >/dev/null
status="$(request "$cookie_b" GET /payments/orders)"
assert_eq "other user order history endpoint" 200 "$status"
jq -e --arg id "$order_id" 'all(.[]; .id!=$id)' "$tmp/response.json" >/dev/null
status="$(request "$cookie_b" GET /payments/entitlements)"
assert_eq "other user entitlement endpoint" 200 "$status"
jq -e 'all(.[]; .packageCode!="ACADEMY_BRANDING")' "$tmp/response.json" >/dev/null

echo "[8/8] An already refunded order cannot be manually reactivated"
refund_order_id="$(cat /proc/sys/kernel/random/uuid)"
psql_db -c "INSERT INTO payment_orders(id,user_id,team_id,package_code,amount_paise,currency,status,provider,idempotency_key) VALUES ('$refund_order_id','$user_id','$team_id','ACADEMY_BRANDING',79900,'INR','REFUNDED','MANUAL','refund-fixture-$refund_order_id')"
status="$(request "$cookie_admin" POST "/admin/payments/orders/$refund_order_id/activate" '{"note":"must not reactivate refunded order"}')"
assert_eq "refunded order activation denial" 409 "$status"
assert_order_state "$refund_order_id" REFUNDED 0 false "" ""

echo "Payment order creation and admin activation E2E certification passed"

#!/usr/bin/env bash
set -euo pipefail
: "$E2E_EMAIL"
: "$PAYMENT_WEBHOOK_SECRET"
: "$DATABASE_URL"
BASE_URL="\${BASE_URL:-http://localhost:8080/api}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
psql_db() { psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -X -qAt "$@"; }
escaped_email="$(printf "%s" "$E2E_EMAIL" | sed "s/'/''/g")"
user_id="$(psql_db -c "SELECT id FROM users WHERE lower(email)=lower('$escaped_email') LIMIT 1")"
[[ -n "$user_id" ]] || { echo "FAIL: CI E2E user was not found" >&2; exit 1; }
order_id="$(cat /proc/sys/kernel/random/uuid)"
idempotency_key="payment-webhook-e2e-$order_id"
psql_db -c "INSERT INTO payment_orders(id,user_id,team_id,package_code,amount_paise,currency,status,provider,idempotency_key) VALUES ('$order_id','$user_id',(SELECT id FROM teams ORDER BY created_at LIMIT 1),'ACADEMY_BRANDING',79900,'INR','PENDING','MANUAL','$idempotency_key')"
make_event() {
  local event_id="$1" event_type="$2" status="$3" payment_id="$4" amount="$5" currency="$6"
  jq -nc --arg eventId "$event_id" --arg eventType "$event_type" --arg orderId "$order_id" --arg status "$status" --arg paymentId "$payment_id" --argjson amountPaise "$amount" --arg currency "$currency" '{eventId:$eventId,eventType:$eventType,orderId:$orderId,status:$status,paymentId:$paymentId,amountPaise:$amountPaise,currency:$currency}'
}
send_event() {
  local body="$1" signature
  signature="$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$PAYMENT_WEBHOOK_SECRET" -hex | awk '{print $2}')"
  curl -sS -o "$tmp/response.json" -w '%{http_code}' -X POST "$BASE_URL/public/payments/webhooks/HMAC" -H "X-Payment-Signature: $signature" -H 'Content-Type: application/json' --data-binary "$body"
}
assert_db() {
  local label="$1" expected_status="$2" expected_entitlements="$3" expected_revoked="$4" actual expected
  actual="$(psql_db -c "SELECT po.status || '|' || (SELECT count(*) FROM user_entitlements ue WHERE ue.source_order_id=po.id) || '|' || COALESCE((SELECT (ue.revoked_at IS NOT NULL)::text FROM user_entitlements ue WHERE ue.source_order_id=po.id LIMIT 1),'false') FROM payment_orders po WHERE po.id='$order_id'")"
  expected="$expected_status|$expected_entitlements|$expected_revoked"
  [[ "$actual" == "$expected" ]] || { echo "FAIL: $label expected '$expected' but database returned '$actual'" >&2; exit 1; }
}
echo "[1/7] Reject amount mismatch and verify full transaction rollback"
event_id="evt-$order_id-success"
body="$(make_event "$event_id" PAYMENT_SUCCESS PAID "pay-$order_id" 79899 INR)"
status="$(send_event "$body")"
[[ "$status" == "409" ]] || { echo "FAIL: mismatched amount expected HTTP 409, got $status: $(cat "$tmp/response.json")" >&2; exit 1; }
assert_db "amount mismatch rollback" PENDING 0 false
event_rows="$(psql_db -c "SELECT count(*) FROM payment_webhook_events WHERE provider='HMAC' AND event_id='$event_id'")"
[[ "$event_rows" == "0" ]] || { echo "FAIL: rejected event was not rolled back from event ledger" >&2; exit 1; }
echo "PASS: invalid amount left order pending, created no entitlement, and rolled back webhook ledger row"
echo "[2/7] Retry same event ID with exact amount and activate entitlement"
body="$(make_event "$event_id" PAYMENT_SUCCESS PAID "pay-$order_id" 79900 INR)"
status="$(send_event "$body")"
[[ "$status" == "200" && "$(jq -r '.status' "$tmp/response.json")" == "processed" ]] || { echo "FAIL: valid payment was not processed: HTTP $status $(cat "$tmp/response.json")" >&2; exit 1; }
assert_db "successful payment" PAID 1 false
echo "PASS: corrected event processed and activated exactly one entitlement"
echo "[3/7] Verify exact duplicate acknowledgement and conflicting event-ID replay rejection"
status="$(send_event "$body")"
[[ "$status" == "200" && "$(jq -r '.status' "$tmp/response.json")" == "duplicate" ]] || { echo "FAIL: exact replay was not acknowledged as duplicate" >&2; exit 1; }
conflicting="$(make_event "$event_id" PAYMENT_SUCCESS PAID "different-payment-ref" 79900 INR)"
status="$(send_event "$conflicting")"
[[ "$status" == "409" ]] || { echo "FAIL: conflicting event-ID replay expected HTTP 409, got $status" >&2; exit 1; }
assert_db "conflicting event replay" PAID 1 false
echo "PASS: exact replay is idempotent; changed payload under same event ID is rejected"
echo "[4/7] Verify paid-order replay reference and entitlement uniqueness"
replay="$(make_event "evt-$order_id-success-replay" PAYMENT_SUCCESS PAID "pay-$order_id" 79900 INR)"
status="$(send_event "$replay")"
[[ "$status" == "200" ]] || { echo "FAIL: matching paid-order replay expected HTTP 200, got $status" >&2; exit 1; }
wrong_replay="$(make_event "evt-$order_id-wrong-payment-replay" PAYMENT_SUCCESS PAID "attacker-payment-ref" 79900 INR)"
status="$(send_event "$wrong_replay")"
[[ "$status" == "409" ]] || { echo "FAIL: wrong payment reference expected HTTP 409, got $status" >&2; exit 1; }
assert_db "paid-order replay reference check" PAID 1 false
echo "PASS: matching replay preserves one entitlement; mismatched payment reference is rejected"
echo "[5/7] Reject refund for a different provider payment reference"
refund_bad="$(make_event "evt-$order_id-refund-bad" REFUND_SUCCESS REFUNDED "wrong-refund-payment" 79900 INR)"
status="$(send_event "$refund_bad")"
[[ "$status" == "409" ]] || { echo "FAIL: mismatched refund reference expected HTTP 409, got $status" >&2; exit 1; }
assert_db "invalid refund rollback" PAID 1 false
echo "PASS: invalid refund did not revoke paid entitlement"
echo "[6/7] Accept matching refund and revoke entitlement"
refund="$(make_event "evt-$order_id-refund" REFUND_SUCCESS REFUNDED "pay-$order_id" 79900 INR)"
status="$(send_event "$refund")"
[[ "$status" == "200" ]] || { echo "FAIL: matching refund expected HTTP 200, got $status: $(cat "$tmp/response.json")" >&2; exit 1; }
assert_db "successful refund" REFUNDED 1 true
echo "PASS: matching refund changed order to REFUNDED and revoked its entitlement"
echo "[7/7] Confirm rejected events did not pollute durable webhook ledger"
ledger_count="$(psql_db -c "SELECT count(*) FROM payment_webhook_events WHERE provider='HMAC' AND event_id IN ('$event_id','evt-$order_id-success-replay','evt-$order_id-wrong-payment-replay','evt-$order_id-refund-bad','evt-$order_id-refund')")"
[[ "$ledger_count" == "3" ]] || { echo "FAIL: expected exactly 3 committed webhook ledger rows, got $ledger_count" >&2; exit 1; }
echo "PASS: only successful payment, matching paid replay, and successful refund events were committed"
echo "Payment webhook lifecycle certification passed for order $order_id"

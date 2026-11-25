# One-time payments API (backend foundation)

All amounts are INR paise. The catalogue currently contains proposed launch prices:
- Pro Tournament: INR 999 per event
- Broadcast: INR 2,499 per event
- Club / Academy: INR 9,999, manually renewed

These are configuration/product hypotheses, not gateway-settled prices. Confirm pricing before enabling checkout.

## Current operating mode

Orders are created as `PENDING` with provider `MANUAL`. No checkout session is generated and no payment is represented as successful merely because an order was created. A platform administrator can activate an order after independently confirming an offline/manual payment; the activation note is retained on the order and the activating admin is recorded on the entitlement.

A generic HMAC webhook adapter is available at `POST /api/public/payments/webhooks/HMAC`. It is deliberately not presented as a Razorpay or Cashfree integration. Configure `PAYMENT_WEBHOOK_SECRET` only when a trusted gateway adapter/proxy is ready to send the documented signed payload. The signature is lowercase or uppercase hex HMAC-SHA256 over the exact raw request body, supplied in `X-Payment-Signature`.

Example webhook body:
```json
{
  "eventId": "evt_unique_provider_event_id",
  "eventType": "PAYMENT_SUCCESS",
  "orderId": "order-uuid",
  "status": "PAID",
  "paymentId": "provider-payment-reference",
  "amountPaise": 19900,
  "currency": "INR"
}
```

For `PAYMENT_SUCCESS` + `PAID`, the signed event must include the exact order `amountPaise`, `currency`, and a non-empty provider `paymentId`. The backend compares the amount and currency to the stored order before activating an entitlement. A mismatch is rejected with HTTP 409 and the webhook transaction is rolled back, so a corrected event can be retried. Replayed success events for an already-paid order must use the same provider payment reference. Refund events must also reference the payment recorded on the order.

Supported transitions:
- `PAYMENT_SUCCESS` + `PAID`: PENDING -> PAID and activate entitlement only after amount/currency/reference validation
- `PAYMENT_FAILED` + `FAILED`: PENDING -> FAILED
- `REFUND_SUCCESS` + `REFUNDED`: PAID -> REFUNDED and revoke the related entitlement

Webhook event IDs are unique per provider; repeated events are acknowledged as duplicates. No recurring billing or auto-renewal exists.

## Endpoints

- `GET /api/payments/packages` — public catalogue
- `POST /api/payments/orders` — authenticated order creation; requires `packageCode` and client-generated `idempotencyKey`
- `GET /api/payments/orders` — current user's orders
- `GET /api/payments/entitlements` — current user's active entitlements
- `POST /api/admin/payments/orders/{orderId}/activate` — platform admin manual activation; requires a reason/note
- `POST /api/public/payments/webhooks/HMAC` — signed generic webhook adapter

## E2E certification coverage

`scripts/e2e-payment-order-admin-activation.sh` exercises the HTTP API and the CI PostgreSQL database using run-scoped disposable accounts/orders. It verifies that order creation grants no entitlement, matching idempotency retries return the same order, conflicting reuse is rejected, ordinary users cannot activate orders, manual admin activation records the admin and note, repeated activation does not duplicate an entitlement, order/entitlement history is owner-scoped, and refunded orders cannot be reactivated.

The CI admin identity is registered through the normal API and promoted only by a direct SQL update against the disposable PostgreSQL service used by that workflow. No application endpoint or production identity is granted new privileges by the test.

## Not enabled by this backend-only PR

- A gateway-specific checkout SDK / order creation flow
- Razorpay or Cashfree's provider-specific webhook signature and event mapping
- Frontend purchase/checkout and admin payment screens
- Feature-level entitlement enforcement across tournament/broadcast APIs
- Automated refund initiation or financial reconciliation

These must be implemented and tested before accepting online payments in production. The webhook secret must never be committed to source control.

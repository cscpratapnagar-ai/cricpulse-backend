# India launch pricing

The public package catalogue is served by `GET /api/payments/packages`. Amounts are stored and returned in paise (INR).

| Package code | Display name | Price | Validity |
| --- | --- | ---: | --- |
| FREE_TOURNAMENT | Free Tournament | ₹0 | Free |
| ORGANIZER_PLUS | Organizer Plus | ₹99 | Per tournament |
| ORGANIZER_PRO | Organizer Pro | ₹199 | Per tournament |
| BROADCAST_ADDON | Broadcast Add-on | ₹149 | Per tournament |
| CLUB_ACADEMY_PLUS | Club / Academy Plus | ₹399 | 1 year |
| ACADEMY_BRANDING | Academy Branding | ₹799 | One-time setup |

Free Tournament is the default access tier. It must never create a payment order; the orders endpoint rejects the free package code. Paid orders remain one-time/manual until a verified payment provider is configured.

Commercial rules for the launch:
- Organizer Pro includes Organizer Plus capabilities and includes Pro sponsor placement.
- Broadcast Add-on is separate and can be purchased alongside Organizer Pro.
- Club / Academy Plus and Academy Branding are distinct products.
- No recurring billing or auto-renewal.
- Club / Academy Plus expires after one year; expiry must not delete tournament history or scoring data.
- Refunds revoke entitlements associated with the refunded order.
- Do not enable live gateway checkout until provider onboarding, credentials, webhook verification and end-to-end reconciliation are complete.

Important implementation boundary: this catalogue change publishes the canonical prices and validity labels. Tournament-scoped order ownership, package feature enforcement, combined Pro + Broadcast entitlements, academy account scoping, and a production checkout UI must be delivered and verified before paid access is advertised as fully operational.

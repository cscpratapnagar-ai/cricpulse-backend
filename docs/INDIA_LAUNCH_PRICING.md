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

Implementation status and boundaries:
- Organizer Plus, Organizer Pro and Broadcast Add-on order requests must include a tournament ID. The backend verifies the authenticated user owns the tournament or is its assigned MANAGER; order and resulting entitlement retain that tournament association.
- Club / Academy Plus and Academy Branding are scoped to an existing team workspace (`team_id`). Only the team owner or a team MANAGER may request activation; the order and resulting entitlement retain that workspace association.
- Organizer Pro and Broadcast Add-on are independently recorded, so both can be active for the same tournament. Feature-level enforcement that makes Pro inherit every Organizer Plus capability still requires integration into the relevant feature authorization paths.
- Free Tournament remains the default and is not represented by a paid entitlement or payment order.
- This is a manual/request-based purchase flow. No provider checkout is enabled; premium access begins only after an authorized admin manually activates the order or a verified configured webhook confirms payment.
- Team workspace package scope is enforced at order creation and preserved through manual activation, verified webhook activation, order history and entitlement history. The current Team entity is the workspace boundary; this does not create a separate club/academy tenant model.
- Club / Academy Plus capability map (launch): bulk squad creation (up to 50 players per request) is enabled only when the target workspace has a non-revoked, non-expired `CLUB_ACADEMY_PLUS` entitlement. The entitlement is checked by `team_id`, not purchaser `user_id`, so workspace access is shared with its authorized managers. Existing single-player add/remove/role-management and player search remain unchanged and are not paywalled by this capability.
- Organizer Plus feature-level authorization still requires an explicit product capability map before individual features can safely be gated.
- A production launch still requires feature-level authorization integration and end-to-end verification of admin activation/refund behavior.

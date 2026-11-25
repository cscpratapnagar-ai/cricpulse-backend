# Tournament entitlement enforcement

## Effective package capabilities

- `ORGANIZER_PRO` includes the effective `ORGANIZER_PLUS` tier. The shared entitlement resolver treats either active package as Organizer Plus access.
- Sponsor placement, sponsor logo uploads, sponsor reporting, public sponsor delivery and sponsor impression recording require an active `ORGANIZER_PRO` entitlement for the tournament that owns the match.
- Opening the authenticated broadcast-control access endpoint requires an active `BROADCAST_ADDON` entitlement. The add-on remains separate and can coexist with Organizer Pro.

## Enforcement rules

- Entitlements are resolved through `tournament_matches` and `user_entitlements`; a package on a different tournament never grants access.
- Revoked entitlements are rejected.
- Expired entitlements are rejected using database time.
- Pending payment orders do not grant access. The existing activation flow remains the only source of active entitlements.
- Existing role/ownership checks remain in place; a paid entitlement does not replace authorization to manage a match.
- Public sponsor overlay delivery is disabled when the tournament has no active Organizer Pro entitlement.

## Launch boundary

This change enforces the paid sponsor and broadcast capabilities that have an explicit package mapping. It does not invent feature gates for unspecified Organizer Plus capabilities. Those capabilities must be individually mapped to a documented feature and protected at their backend entry points before they are advertised as paid benefits. Live gateway checkout remains disabled.

# Access-token revocation

CricPulse browser sessions use signed access JWTs in an HttpOnly cookie. Clearing that cookie on logout only affects the current browser; a copied token would otherwise remain usable until its expiry.

## Logout behavior

- `POST /api/auth/logout` revokes the presented Bearer token, or the access cookie when no Bearer token is present.
- The server stores only a SHA-256 fingerprint and the JWT expiry in PostgreSQL. Raw access tokens are never persisted.
- The JWT filter validates the signature and expiry first, then checks the fingerprint against the revocation table before creating an authenticated principal.
- A revocation-store database error fails closed: protected requests are not allowed to bypass the check.
- Expired fingerprints are pruned opportunistically during subsequent logout requests.

## Scope and limits

Revocation applies to the exact access token presented at logout. It does not sign out other devices that hold separately issued tokens. Existing WebSocket connections are not forcibly terminated by this endpoint; authenticated WebSocket tickets are short-lived (60 seconds), so a previously issued ticket can remain usable until it expires. Device-wide logout and active WebSocket session termination require a separate session registry/broker-disconnect design.

The CI E2E check logs in, confirms `/auth/me`, logs out, and then attempts to replay the pre-logout token as a Bearer credential. The replay must receive HTTP 401.

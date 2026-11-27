# Device session management

Each successful password login creates a server-side session record and issues an access JWT carrying its session ID (`sid`). The server checks the registry for protected HTTP requests and authenticated STOMP traffic.

## Session endpoints

- `GET /api/auth/sessions` lists the current user's active sessions and marks the caller's session.
- `DELETE /api/auth/sessions/{sessionId}` revokes one of the caller's sessions.
- `DELETE /api/auth/sessions` revokes every active session for the caller, including the current one.
- `POST /api/auth/logout` continues to revoke the presented token and now also revokes its server-side session.

Session listings include a derived device label, user-agent, IP address, creation/last-seen time and expiry. User-agent and IP values are truncated before persistence.

## WebSocket behavior

The WebSocket handshake is tracked by native socket ID. An authenticated STOMP CONNECT binds that socket to the JWT session ID. Revoking a session closes all bound sockets. Subsequent authenticated STOMP frames also re-check the session registry; a revoked or expired session cannot create new private subscriptions.

Anonymous public-viewer subscriptions remain supported. The public topic path does not grant private topic access.

## Compatibility

Access JWTs without a session ID are not accepted by the protected HTTP or authenticated STOMP paths after this migration. Users must sign in again after deployment. WebSocket tickets inherit the authenticated access session ID and cannot outlive its revocation.

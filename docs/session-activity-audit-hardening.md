# Device session activity and audit hardening

## Activity tracking
- Authenticated requests update `user_sessions.last_seen_at` only when the previous update is older than five minutes.
- Updates are scoped to the signed session ID and authenticated user identity; expired or revoked sessions cannot refresh activity.
- Session listings and successful logins opportunistically remove expired sessions and revoked session rows older than 30 days.

## Security audit trail
The `user_session_audit` table records successful device-session lifecycle events:
- `LOGIN`: a device session was created after successful credential verification.
- `LOGOUT`: the current device session was revoked.
- `SESSION_REVOKED`: a device session was revoked from session management.
- `LOGOUT_ALL`: a device session was revoked by the sign-out-all action.

Audit entries retain timestamp, user ID, session ID, IP address, and user-agent string. Audit rows are independent of session-row cleanup and remain available until the owning user is deleted. Failed login attempts are not recorded.

## Operational notes
- No endpoint exposes audit history; access remains restricted to database operations.
- Cleanup is opportunistic on login and session-list requests, not a background scheduler.
- The in-memory WebSocket registry remains single-instance; multi-instance deployments require shared session/WebSocket coordination.

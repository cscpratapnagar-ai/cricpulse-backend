# Distributed WebSocket session revocation

## Shared revocation outbox
- Session revocation and its outbox event are written in the same PostgreSQL transaction.
- Every backend replica independently polls `websocket_session_revocations` using its own monotonically increasing cursor.
- Each replica closes only sockets held by its local `ActiveWebSocketSessions` registry.
- Events are retained in PostgreSQL; a replica that temporarily loses database connectivity does not advance its cursor and retries on the next poll.
- New replicas initialize their cursor to the current maximum event ID. They cannot accept already-revoked sessions because STOMP CONNECT checks the shared `user_sessions` table.

## Race and security behavior
- STOMP CONNECT checks the shared active-session row before binding a socket and checks it again immediately after binding, closing the check/register race.
- Private STOMP frames and subscriptions continue to verify the shared active-session row. The outbox is for prompt connection closure, not the authorization boundary.
- Local close remains immediate on the revoking replica; the outbox propagates closure to other replicas.

## Deployment boundary
All backend replicas must use the same PostgreSQL database and run the same Flyway migration. Poll interval is configurable with `APP_WEBSOCKET_REVOCATION_POLL_INTERVAL` (milliseconds), default 1000.
This change distributes session revocation only. The current Spring simple message broker is still process-local; multi-replica cricket score topic fan-out requires a dedicated external STOMP broker and is not enabled by this change.

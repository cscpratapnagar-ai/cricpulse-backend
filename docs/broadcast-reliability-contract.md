# Broadcast Reliability Contract

This backend PR consolidates the backend portion of the six broadcast workstreams.

## Public readiness endpoint

- `GET /api/public/broadcast-health`
- Returns `200 { status: "UP", checkedAt }` when a database probe succeeds.
- Returns `503 { status: "DOWN", checkedAt }` when the database is unavailable.
- Does not return exception text, database identifiers, connection strings, or infrastructure details.
- The endpoint is covered by unit tests for both success and dependency failure.

## Existing broadcast consistency guarantees

- `GET /api/public/matches/{matchId}/broadcast-state` provides the initial read-only overlay snapshot.
- Public live score events are published to `/topic/public/innings/{inningsId}`.
- Authenticated scoring events are published to `/topic/innings/{inningsId}`.
- Both destinations receive the same post-commit score projection and event version; the event version is read from the same authoritative score snapshot.
- Public subscriptions remain read-only. Private subscriptions continue to pass through the WebSocket authorization interceptor.
- STOMP broker heartbeats remain enabled.

## Six-workstream mapping

1. OBS automatic recovery — backend supplies the authoritative state and public event stream used after client reconnect.
2. Broadcast Studio reliability — readiness endpoint provides a minimal dependency-health signal without leaking infrastructure details.
3. Overlay synchronization — atomic score/event-version publication prevents mismatched revisions.
4. Live broadcast E2E — validate initial REST snapshot followed by public/private STOMP events.
5. Multi-device certification — verify separate scorer and viewer clients observe the same committed version; manual device execution remains an operator responsibility.
6. Performance/network failure — exercise dependency outage and recovery using readiness probe plus reconnect/reconciliation tests; collect production-like latency/load measurements before release.

## Verification

Run:

```bash
mvn -B test
mvn -B verify
```

The final GitHub Actions status for the PR head SHA is the authoritative CI result. A successful unit test does not replace the required live multi-device and network-interruption rehearsal.

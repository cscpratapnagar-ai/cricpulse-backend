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
- Public subscriptions remain read-only and can connect anonymously. Private subscriptions continue to pass through the WebSocket authorization interceptor.
- STOMP broker heartbeats remain enabled.

## Six-workstream mapping

1. OBS automatic recovery — backend supplies the authoritative state and public event stream used after client reconnect.
2. Broadcast Studio reliability — readiness endpoint provides a minimal dependency-health signal without leaking infrastructure details.
3. Overlay synchronization — atomic score/event-version publication prevents mismatched revisions.
4. Live broadcast E2E — CI connects two independent authenticated clients to the private topic and one anonymous client to the public topic, then asserts all three receive the same four committed score transitions in identical order, with no duplicate or missing events. REST snapshot versions are also checked against the scorer projection.
5. Multi-device certification — verify separate scorer and viewer clients observe the same committed version; manual device execution remains an operator responsibility.
6. Performance/network failure — CI now issues 64 concurrent public broadcast-state reads (8 clients × 8 requests), validates every response against the seeded match, records p95/max latency, and applies a coarse 5,000ms p95 regression ceiling. It then terminates the API process, verifies the public readiness probe becomes unavailable, restarts the packaged application, and confirms readiness plus the same public broadcast-state snapshot recover. This is an API-process interruption test, not a packet-loss/Toxiproxy simulation or a production SLO.

## Verification

Run:

```bash
mvn -B test
mvn -B verify
```

The final GitHub Actions status for the PR head SHA is the authoritative CI result. A successful unit test does not replace the required live multi-device and network-interruption rehearsal.


## Performance and restart-recovery E2E

The backend CI runs `scripts/e2e-broadcast-performance-recovery.sh` after the disposable match lifecycle has been exercised.

- 8 concurrent workers each request the public broadcast-state endpoint 8 times (64 requests total).
- Every response must be HTTP 200 and contain the expected match ID plus an innings array.
- CI records p95 and maximum response latency; p95 above 5 seconds fails as a coarse regression guard.
- The script stops the running API process and confirms the public health endpoint is unreachable.
- The packaged application is restarted against the same CI PostgreSQL service; readiness must return UP and the same match's broadcast-state endpoint must return a valid snapshot.
- Latency and recovery observations are appended to the GitHub Actions step summary.

The shared GitHub runner is not a production-like load environment. Do not interpret these timings as a published SLO. Packet loss, bandwidth throttling, device/browser diversity, OBS/vMix rendering, and a sustained live-match soak still require a staging rehearsal and operator sign-off.


## Anonymous public and authenticated private event parity

The scoring idempotency E2E starts three independent STOMP clients before any scoring mutation:

- Two separately authenticated team owners obtain short-lived WebSocket tickets and subscribe to the protected `/topic/innings/{inningsId}` destination.
- A third client connects with no Authorization header or ticket and subscribes to the read-only `/topic/public/innings/{inningsId}` destination, matching an anonymous public viewer or OBS browser source.
- The test commits a delivery, races two competing commands, retries an idempotent command, performs undo operations, and verifies that only the four authoritative state transitions are broadcast.
- Each client must receive exactly the same ordered event payloads, with unique event IDs and strictly increasing event versions. The public and private stream output files are compared byte-for-byte.
- Existing unit tests separately assert that anonymous public subscriptions do not require a membership lookup and unauthenticated private subscriptions are rejected.

This CI check certifies server-side public/private topic parity and anonymous subscription behavior. It does not replace staging verification on real browsers, mobile devices, OBS, or vMix.

## Real wicket integration certification

The backend CI also runs `scripts/e2e-real-wicket-broadcast.sh` against the disposable PostgreSQL-backed API fixture.

- Records a non-striker Run Out with one completed run and verifies crossing plus replacement-batter placement.
- Records a subsequent Bowled dismissal to verify bowler wicket credit, legal-ball count, runs conceded and economy. Run Out is not credited to the bowler; Bowled is.
- Verifies persisted fall-of-wicket rows, scorecard dismissal types, and partnership rows including the zero-ball current partnership after the second wicket.
- Compares the authenticated scorer projection, anonymous public innings snapshot, and public Match Centre broadcast-state score and event version.
- Connects an anonymous public STOMP client before scoring, verifies the ordered Run Out and Bowled snapshots, disconnects, reconnects, and recovers the latest committed version through the public REST snapshot.

This is a real API/PostgreSQL/STOMP CI integration test. It does not certify physical mobile devices, browser rendering, OBS/vMix capture, network packet loss, or a production-host soak; those remain staging/operator acceptance checks.


## Complete dismissal matrix integration certification

The backend CI also runs `scripts/e2e-dismissal-matrix.sh` against two isolated matches backed by the disposable PostgreSQL service.

- Records Caught, LBW, Stumped and Hit Wicket through the authenticated scoring API, with a replacement batter after each dismissal.
- Uses the bowling-side player as the caught-and-bowled fielder and selected wicketkeeper for Stumped, then checks persisted `deliveries.wicket_fielder_id` attribution. LBW and Hit Wicket are checked without a fielder.
- Verifies wicket count, legal-ball count, batter dismissal types, fall-of-wicket rows, replacement batter state, scorecard rows, bowler wicket credits and economy.
- Compares scorer REST, anonymous public innings REST and public Match Centre broadcast-state score/event version after each two-wicket scenario.
- The existing Task #169 STOMP test continues to verify ordered Run Out/Bowled messages, unique event IDs, increasing versions and reconnect snapshot recovery. This new matrix does not yet assert each of Caught/LBW/Stumped/Hit Wicket over a live STOMP subscription; that remains an explicit follow-up rather than an assumed guarantee.

The CI matrix uses API-created matches and PostgreSQL assertions. Browser/device rendering, OBS/vMix capture, packet-loss simulation and production-host soak remain staging/operator acceptance checks.


## Task #171 — live dismissal STOMP and reconnect certification

The backend CI dismissal matrix now attaches an anonymous public STOMP subscriber before each wicket sequence in three isolated matches.

- Observes Caught, LBW, Stumped and Hit Wicket as committed live public STOMP snapshots, validating the matching recent-ball wicket type and dismissed player in Fall of Wickets.
- Adds a striker Run Out scenario; existing Task #169 continues to cover non-striker Run Out and crossing/replacement behavior.
- Requires exactly one event per committed wicket, unique event IDs and strictly increasing event versions; missing, duplicate or out-of-order messages fail the run.
- Disconnects after the event sequence, reconnects to the public topic, and verifies the anonymous REST snapshot has the latest event version, wicket count and batter positions.
- The test validates server-side event/state behavior. It does not simulate packet loss during an in-flight delivery or certify browser rendering, physical mobile devices, OBS/vMix capture, or a sustained production-host soak; those remain staging/operator acceptance checks.

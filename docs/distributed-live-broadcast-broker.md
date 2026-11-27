# Distributed live broadcast broker
## Why the relay is required
The Spring simple broker stores subscriptions inside one JVM. With multiple API replicas, a scoring command handled by replica A cannot fan out to viewers connected to replica B. Task #176 switches staging and production to a Spring STOMP broker relay backed by RabbitMQ while retaining the simple broker for local/CI runs unless explicitly enabled.
## Message flow
1. The scoring API commits the authoritative score projection.
2. The existing scoring publisher sends the same versioned event to existing public and private /topic destinations.
3. Spring's broker relay forwards topic messages to RabbitMQ's STOMP plugin.
4. RabbitMQ fans topic events out to subscribers connected through any API replica.
5. The existing inbound interceptor continues authorizing private subscriptions and active device sessions. Public topics remain anonymous and read-only.
6. The REST broadcast snapshot remains the source of truth after reconnect.
## Configuration
Set WEBSOCKET_BROKER_RELAY_ENABLED=true, host, port (61613), client/system login and passcode, and virtual host. Staging and production compose provision a private RabbitMQ service, enable the STOMP plugin, and wait for broker health before starting the API. Broker ports are not published on those hosts.
Use dedicated strong RABBITMQ_USERNAME and RABBITMQ_PASSWORD deployment secrets. Do not reuse database or JWT credentials. Local compose has development-only credentials and publishes STOMP/management ports on loopback only.
## Availability and recovery
- Spring's broker relay owns the STOMP system connection and reconnect lifecycle.
- The brokerRelay actuator health indicator follows Spring BrokerAvailabilityEvent notifications.
- With relay enabled, readiness is DOWN until the relay system connection is available and returns DOWN if the broker connection is lost. Container health checks prevent routing traffic to an API instance without a broker connection.
- With relay disabled, health reports UP for local simple-broker mode.
- Reconnecting viewers must fetch the latest broadcast snapshot before treating the stream as current.
## Boundaries
- RabbitMQ provides cross-replica topic fan-out; it does not make score mutations durable. PostgreSQL remains the source of truth.
- Existing score event versions and idempotency remain the ordering/deduplication contract. Clients should discard duplicate/stale versions and recover using REST snapshot.
- This does not claim exactly-once delivery. STOMP reconnects may require a fresh snapshot.
- Two-replica message delivery and broker restart/recovery still require dedicated staging certification in the reserved final test week.
- No production deployment or release tag is performed by this change.
## Local relay mode
Start docker compose up -d postgres rabbitmq, then run the API with relay enabled and host localhost, port 61613, client/system login cricpulse and passcode local-cricpulse-broker-password. The readiness endpoint reports brokerRelay UP only after the relay system connection is established.

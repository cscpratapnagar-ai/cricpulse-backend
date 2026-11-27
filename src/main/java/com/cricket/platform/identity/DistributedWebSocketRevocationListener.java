package com.cricket.platform.identity;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Each backend replica independently consumes the durable PostgreSQL revocation
 * outbox and closes only the WebSocket connections owned by that replica.
 *
 * The initial cursor intentionally starts at zero. BIGSERIAL allocation order is
 * not commit order: a transaction can allocate an ID before startup, commit after
 * startup, and otherwise be skipped by a MAX(id)-based bootstrap cursor.
 */
@Component
public class DistributedWebSocketRevocationListener {
    private static final Logger log = LoggerFactory.getLogger(DistributedWebSocketRevocationListener.class);

    private final JdbcTemplate jdbc;
    private final ActiveWebSocketSessions activeSockets;
    private final AtomicLong lastProcessedId = new AtomicLong();

    public DistributedWebSocketRevocationListener(
            JdbcTemplate jdbc,
            ActiveWebSocketSessions activeSockets) {
        this.jdbc = jdbc;
        this.activeSockets = activeSockets;
    }

    @PostConstruct
    void initialize() {
        // Replay is safe: closing a socket that is no longer present is a no-op.
        // Do not bootstrap from MAX(id), because sequence order is not commit order.
        lastProcessedId.set(0L);
    }

    @Scheduled(fixedDelayString = "${app.websocket.revocation-poll-interval:1000}")
    void poll() {
        long cursor = lastProcessedId.get();
        try {
            List<Revocation> pending = jdbc.query("""
                    SELECT id, session_id::text AS session_id
                    FROM websocket_session_revocations
                    WHERE id > ?
                    ORDER BY id
                    LIMIT 500
                    """, (rs, row) -> new Revocation(rs.getLong("id"), rs.getString("session_id")), cursor);
            for (Revocation event : pending) {
                activeSockets.closeByUserSession(event.sessionId());
                lastProcessedId.accumulateAndGet(event.id(), Math::max);
            }
        } catch (RuntimeException ex) {
            // Do not advance the cursor on failure. The next scheduled poll retries.
            log.warn("WebSocket session revocation polling failed; pending events will be retried", ex);
        }
    }

    record Revocation(long id, String sessionId) {}
}

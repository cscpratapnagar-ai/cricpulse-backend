package com.cricket.platform.scoring;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only audit facade for the immutable scoring event stream. */
@Component
public class ScoringAuditService {
    private final JdbcTemplate jdbc;

    public ScoringAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public AuditSnapshot snapshot(UUID inningsId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS event_count,
                       COALESCE(MAX(sequence_no), 0) AS last_sequence,
                       COALESCE(MAX(event_version), 0) AS last_version,
                       MIN(created_at) AS first_event_at,
                       MAX(created_at) AS last_event_at
                FROM delivery_events
                WHERE innings_id = ?
                """, (rs, rowNum) -> new AuditSnapshot(
                inningsId,
                rs.getLong("event_count"),
                rs.getLong("last_sequence"),
                rs.getInt("last_version"),
                rs.getObject("first_event_at", OffsetDateTime.class),
                rs.getObject("last_event_at", OffsetDateTime.class)
        ), inningsId);
    }

    public record AuditSnapshot(
            UUID inningsId,
            long eventCount,
            long lastSequence,
            int lastVersion,
            OffsetDateTime firstEventAt,
            OffsetDateTime lastEventAt
    ) {}
}

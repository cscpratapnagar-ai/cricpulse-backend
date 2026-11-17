package com.cricket.platform.scoring;

import java.time.OffsetDateTime;
import java.util.List;
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
                       (SELECT COUNT(*) FROM scoring_corrections WHERE innings_id = ?) AS correction_count,
                       MIN(created_at) AS first_event_at,
                       MAX(created_at) AS last_event_at
                FROM delivery_events
                WHERE innings_id = ?
                """, (rs, rowNum) -> new AuditSnapshot(
                inningsId,
                rs.getLong("event_count"),
                rs.getLong("last_sequence"),
                rs.getInt("last_version"),
                rs.getLong("correction_count"),
                rs.getObject("first_event_at", OffsetDateTime.class),
                rs.getObject("last_event_at", OffsetDateTime.class),
                corrections(inningsId)
        ), inningsId, inningsId);
    }

    private List<CorrectionRecord> corrections(UUID inningsId) {
        return jdbc.query("""
                SELECT correction_id, correction_type, affected_delivery_id, details::text, created_at
                FROM scoring_corrections
                WHERE innings_id = ?
                ORDER BY created_at DESC, correction_id DESC
                """, (rs, rowNum) -> new CorrectionRecord(
                rs.getObject("correction_id", UUID.class),
                rs.getString("correction_type"),
                rs.getObject("affected_delivery_id", UUID.class),
                rs.getString("details"),
                rs.getObject("created_at", OffsetDateTime.class)
        ), inningsId);
    }

    public record CorrectionRecord(
            UUID correctionId,
            String correctionType,
            UUID affectedDeliveryId,
            String details,
            OffsetDateTime createdAt
    ) {}

    public record AuditSnapshot(
            UUID inningsId,
            long eventCount,
            long lastSequence,
            int lastVersion,
            long correctionCount,
            OffsetDateTime firstEventAt,
            OffsetDateTime lastEventAt,
            List<CorrectionRecord> corrections
    ) {}
}

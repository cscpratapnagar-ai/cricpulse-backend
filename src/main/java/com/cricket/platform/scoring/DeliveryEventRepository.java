package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class DeliveryEventRepository {
    private final JdbcTemplate jdbc;

    public DeliveryEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean commandExists(UUID commandId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_events WHERE command_id = ?", Integer.class, commandId);
        return count != null && count > 0;
    }

    public long nextSequence(UUID inningsId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM delivery_events WHERE innings_id = ?", Long.class, inningsId);
        return value == null ? 1L : value;
    }

    public int nextVersion(UUID inningsId) {
        Integer value = jdbc.queryForObject("SELECT COALESCE(MAX(event_version), 0) + 1 FROM delivery_events WHERE innings_id = ?", Integer.class, inningsId);
        return value == null ? 1 : value;
    }

    public void save(DeliveryEvent event) {
        jdbc.update("""
                INSERT INTO delivery_events (
                    event_id, innings_id, sequence_no, event_version, event_type,
                    over_number, ball_number, striker_id, non_striker_id, bowler_id,
                    bat_runs, extra_runs, extra_type, wicket_type, dismissed_player_id,
                    legal_delivery, event_payload, command_id, recorded_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                event.eventId(), event.inningsId(), event.sequenceNo(), event.eventVersion(), event.eventType(),
                event.overNumber(), event.ballNumber(), event.strikerId(), event.nonStrikerId(), event.bowlerId(),
                event.batRuns(), event.extraRuns(), event.extraType(), event.wicketType(), event.dismissedPlayerId(),
                event.legalDelivery(), event.eventPayload(), event.commandId(), event.recordedBy(), event.createdAt()
        );
    }
}

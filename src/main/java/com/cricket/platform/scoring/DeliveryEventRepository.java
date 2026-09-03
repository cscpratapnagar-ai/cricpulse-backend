package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

@Repository
public class DeliveryEventRepository {
    private final JdbcTemplate jdbc;

    public DeliveryEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean commandExists(UUID commandId) {
        return findByCommandId(commandId) != null;
    }

    public DeliveryEvent findByCommandId(UUID commandId) {
        if (commandId == null) {
            return null;
        }
        return jdbc.query(
                "SELECT event_id, innings_id, sequence_no, event_version, event_type, over_number, ball_number, "
                        + "striker_id, non_striker_id, bowler_id, bat_runs, extra_runs, extra_type, wicket_type, "
                        + "dismissed_player_id, legal_delivery, event_payload, command_id, recorded_by, created_at "
                        + "FROM delivery_events WHERE command_id = ?",
                rs -> rs.next() ? map(rs) : null,
                commandId
        );
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

    private DeliveryEvent map(ResultSet rs) throws SQLException {
        return new DeliveryEvent(
                rs.getObject("event_id", UUID.class),
                rs.getObject("innings_id", UUID.class),
                rs.getLong("sequence_no"),
                rs.getInt("event_version"),
                rs.getString("event_type"),
                rs.getInt("over_number"),
                rs.getInt("ball_number"),
                rs.getObject("striker_id", UUID.class),
                rs.getObject("non_striker_id", UUID.class),
                rs.getObject("bowler_id", UUID.class),
                rs.getInt("bat_runs"),
                rs.getInt("extra_runs"),
                rs.getString("extra_type"),
                rs.getString("wicket_type"),
                rs.getObject("dismissed_player_id", UUID.class),
                rs.getBoolean("legal_delivery"),
                rs.getString("event_payload"),
                rs.getObject("command_id", UUID.class),
                rs.getObject("recorded_by", UUID.class),
                rs.getObject("created_at", java.time.OffsetDateTime.class)
        );
    }
}

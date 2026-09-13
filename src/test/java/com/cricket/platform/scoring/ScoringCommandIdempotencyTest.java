package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoringCommandIdempotencyTest {
    @Test
    void commandExistsRecognizesPersistedCommand() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        UUID commandId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID inningsId = UUID.randomUUID();
        UUID strikerId = UUID.randomUUID();
        UUID nonStrikerId = UUID.randomUUID();
        UUID bowlerId = UUID.randomUUID();
        UUID recordedBy = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();

        when(resultSet.next()).thenReturn(true);
        when(resultSet.getObject("event_id", UUID.class)).thenReturn(eventId);
        when(resultSet.getObject("innings_id", UUID.class)).thenReturn(inningsId);
        when(resultSet.getLong("sequence_no")).thenReturn(1L);
        when(resultSet.getInt("event_version")).thenReturn(1);
        when(resultSet.getString("event_type")).thenReturn("DELIVERY_RECORDED");
        when(resultSet.getInt("over_number")).thenReturn(0);
        when(resultSet.getInt("ball_number")).thenReturn(1);
        when(resultSet.getObject("striker_id", UUID.class)).thenReturn(strikerId);
        when(resultSet.getObject("non_striker_id", UUID.class)).thenReturn(nonStrikerId);
        when(resultSet.getObject("bowler_id", UUID.class)).thenReturn(bowlerId);
        when(resultSet.getInt("bat_runs")).thenReturn(0);
        when(resultSet.getInt("extra_runs")).thenReturn(0);
        when(resultSet.getString("extra_type")).thenReturn(null);
        when(resultSet.getString("wicket_type")).thenReturn(null);
        when(resultSet.getObject("dismissed_player_id", UUID.class)).thenReturn(null);
        when(resultSet.getBoolean("legal_delivery")).thenReturn(true);
        when(resultSet.getString("event_payload")).thenReturn("{}");
        when(resultSet.getObject("command_id", UUID.class)).thenReturn(commandId);
        when(resultSet.getObject("recorded_by", UUID.class)).thenReturn(recordedBy);
        when(resultSet.getObject("created_at", OffsetDateTime.class)).thenReturn(createdAt);

        when(jdbc.query(any(String.class), any(ResultSetExtractor.class), eq(commandId)))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    return extractor.extractData(resultSet);
                });

        DeliveryEventRepository repository = new DeliveryEventRepository(jdbc);

        assertTrue(repository.commandExists(commandId));
    }
}

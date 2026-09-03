package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoringCommandIdempotencyTest {
    @Test
    void commandExistsRecognizesPersistedCommand() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID commandId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM delivery_events WHERE command_id = ?"),
                eq(Integer.class), eq(commandId))).thenReturn(1);

        DeliveryEventRepository repository = new DeliveryEventRepository(jdbc);
        assertTrue(repository.commandExists(commandId));
    }
}

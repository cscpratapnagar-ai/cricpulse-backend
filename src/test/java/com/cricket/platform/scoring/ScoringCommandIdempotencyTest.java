package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
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

        when(resultSet.next()).thenReturn(true);
        when(jdbc.query(any(String.class), any(JdbcTemplate.ResultSetExtractor.class), eq(commandId)))
                .thenAnswer(invocation -> {
                    JdbcTemplate.ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    return extractor.extractData(resultSet);
                });

        DeliveryEventRepository repository = new DeliveryEventRepository(jdbc);

        assertTrue(repository.commandExists(commandId));
    }
}

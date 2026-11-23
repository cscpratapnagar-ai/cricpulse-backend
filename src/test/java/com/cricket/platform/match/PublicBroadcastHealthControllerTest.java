package com.cricket.platform.match;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicBroadcastHealthControllerTest {

    @Test
    void reportsUpWhenDatabaseProbeSucceeds() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        var response = new PublicBroadcastHealthController(jdbc).health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().status());
        org.junit.jupiter.api.Assertions.assertNotNull(response.getBody().checkedAt());
    }

    @Test
    void reportsUnavailableWithoutLeakingDatabaseExceptionDetails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("private database details"));

        var response = new PublicBroadcastHealthController(jdbc).health();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("DOWN", response.getBody().status());
        org.junit.jupiter.api.Assertions.assertNotNull(response.getBody().checkedAt());
        org.junit.jupiter.api.Assertions.assertFalse(response.getBody().toString().contains("private"));
    }
}

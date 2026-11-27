package com.cricket.platform.identity;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionRegistryServiceTest {
    @Test
    void updatesLastSeenForTheMatchingActiveSessionWithThrottle() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SessionRegistryService service = new SessionRegistryService(jdbc, new ActiveWebSocketSessions());

        service.touch("8e35c2cd-7f12-4b77-9d7c-9c6b2f12b991", "player@example.com");

        verify(jdbc).update(contains("last_seen_at = now()"), any(), eq("player@example.com"),
                eq("player@example.com"));
    }

    @Test
    void ignoresMissingSessionIdentityWithoutDatabaseWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SessionRegistryService service = new SessionRegistryService(jdbc, new ActiveWebSocketSessions());

        service.touch(" ", "player@example.com");

        verifyNoInteractions(jdbc);
    }
}

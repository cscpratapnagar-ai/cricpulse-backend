package com.cricket.platform.identity;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DistributedWebSocketRevocationListenerTest {
    @Test
    void replaysExistingOutboxEventsOnStartupToAvoidSkippingLateCommits() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ActiveWebSocketSessions sockets = mock(ActiveWebSocketSessions.class);
        DistributedWebSocketRevocationListener listener =
                new DistributedWebSocketRevocationListener(jdbc, sockets);

        listener.initialize();

        // A row whose identity was allocated before startup may commit afterwards.
        // Starting at zero ensures the next poll still discovers this lower ID.
        when(jdbc.query(anyString(), any(RowMapper.class), eq(0L))).thenReturn(List.of(
                new DistributedWebSocketRevocationListener.Revocation(7L, "late-commit-session")));

        listener.poll();

        verify(sockets).closeByUserSession("late-commit-session");
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(0L));
    }

    @Test
    void closesSessionsFromSharedOutboxAndAdvancesCursor() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ActiveWebSocketSessions sockets = mock(ActiveWebSocketSessions.class);
        DistributedWebSocketRevocationListener listener =
                new DistributedWebSocketRevocationListener(jdbc, sockets);
        listener.initialize();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(0L))).thenReturn(List.of(
                new DistributedWebSocketRevocationListener.Revocation(12L, "session-a"),
                new DistributedWebSocketRevocationListener.Revocation(13L, "session-b")));

        listener.poll();

        verify(sockets).closeByUserSession("session-a");
        verify(sockets).closeByUserSession("session-b");
        listener.poll();
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(0L));
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(13L));
    }

    @Test
    void retriesSameCursorWhenOutboxReadFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ActiveWebSocketSessions sockets = mock(ActiveWebSocketSessions.class);
        DistributedWebSocketRevocationListener listener =
                new DistributedWebSocketRevocationListener(jdbc, sockets);
        listener.initialize();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(0L)))
                .thenThrow(new IllegalStateException("database unavailable"));

        listener.poll();
        listener.poll();

        verify(jdbc, times(2)).query(anyString(), any(RowMapper.class), eq(0L));
        verifyNoInteractions(sockets);
    }
}

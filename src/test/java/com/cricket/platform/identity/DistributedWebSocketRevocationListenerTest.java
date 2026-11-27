package com.cricket.platform.identity;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DistributedWebSocketRevocationListenerTest {
    @Test
    void closesSessionsFromSharedOutboxAndAdvancesCursor() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ActiveWebSocketSessions sockets = mock(ActiveWebSocketSessions.class);
        DistributedWebSocketRevocationListener listener =
                new DistributedWebSocketRevocationListener(jdbc, sockets, 1000L);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(11L);
        listener.initialize();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(11L))).thenReturn(List.of(
                new DistributedWebSocketRevocationListener.Revocation(12L, "session-a"),
                new DistributedWebSocketRevocationListener.Revocation(13L, "session-b")));

        listener.poll();

        verify(sockets).closeByUserSession("session-a");
        verify(sockets).closeByUserSession("session-b");
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(13L));
    }

    @Test
    void retriesSameCursorWhenOutboxReadFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ActiveWebSocketSessions sockets = mock(ActiveWebSocketSessions.class);
        DistributedWebSocketRevocationListener listener =
                new DistributedWebSocketRevocationListener(jdbc, sockets, 1000L);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(20L);
        listener.initialize();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(20L)))
                .thenThrow(new IllegalStateException("database unavailable"));

        listener.poll();
        listener.poll();

        verify(jdbc, times(2)).query(anyString(), any(RowMapper.class), eq(20L));
        verifyNoInteractions(sockets);
    }
}

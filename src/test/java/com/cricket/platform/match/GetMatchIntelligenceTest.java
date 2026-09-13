package com.cricket.platform.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

class GetMatchIntelligenceTest {
    @Test
    void derivesMomentumAndChasePressureFromRecordedEvents() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID matchId = UUID.randomUUID();
        UUID inningsId = UUID.randomUUID();

        GetMatchIntelligence service = new GetMatchIntelligence(jdbc);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(matchId)))
                .thenReturn(List.of(new GetMatchIntelligence.InningsData(inningsId, 2, UUID.randomUUID(), "Chasers",
                        90, 3, 90, 20, 121, "LIVE")));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(inningsId)))
                .thenReturn(List.of(new GetMatchIntelligence.RecentData(20, 12, 4, 2, 1, 0)));

        GetMatchIntelligence.MatchIntelligence result = service.get(matchId);

        assertEquals(20, result.recentRuns());
        assertEquals("RISING", result.momentum());
        assertEquals(31, result.requiredRuns());
        assertEquals(30, result.ballsRemaining());
        assertEquals("HIGH", result.chasePressure());
        assertEquals("Chasers", result.battingTeam());
    }
}

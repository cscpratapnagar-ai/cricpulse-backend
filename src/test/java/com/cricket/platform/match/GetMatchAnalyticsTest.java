package com.cricket.platform.match;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetMatchAnalyticsTest {
    @Test
    void aggregatesOversAndPhasesFromAuthoritativeDeliveryEvents() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID matchId = UUID.randomUUID();
        UUID inningsId = UUID.randomUUID();

        when(jdbc.query(anyString(), any(RowMapper.class), eq(matchId)))
                .thenReturn(List.of(new GetMatchAnalytics.InningsRow(
                        inningsId, 1, "Falcons", 18, 1, 12, 20, null)));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(inningsId)))
                .thenReturn(List.of(
                        new GetMatchAnalytics.RawOver(1, 8, 6, 2, 0, 0, 0, 2, 0),
                        new GetMatchAnalytics.RawOver(2, 10, 6, 3, 1, 1, 0, 0, 1)));

        GetMatchAnalytics.MatchAnalytics result = new GetMatchAnalytics(jdbc).get(matchId);
        GetMatchAnalytics.InningsAnalytics innings = result.innings().getFirst();

        assertEquals(2, innings.overs().size());
        assertEquals(18, innings.overs().stream().mapToInt(GetMatchAnalytics.OverAnalytics::runs).sum());
        assertEquals(2, innings.overs().get(0).dotBalls());
        assertEquals(3, innings.overs().get(1).dotBalls());
        assertEquals(5, innings.powerplay().totals().dotBalls());
        assertEquals(4, innings.boundaryRuns());
        assertEquals(18, innings.overs().get(1).cumulativeRuns());
        assertEquals(1, innings.overs().get(1).cumulativeWickets());
        assertEquals(12, innings.overs().get(1).cumulativeLegalBalls());
        assertEquals(9.00, innings.overs().get(1).cumulativeRunRate().doubleValue(), 0.001);
        assertEquals(9.00, innings.runRate().doubleValue(), 0.001);
        assertEquals(18, innings.powerplay().totals().runs());
        assertEquals(1, innings.powerplay().totals().wickets());
        assertEquals(9.00, innings.powerplay().totals().runRate().doubleValue(), 0.001);
        assertEquals(0, innings.middle().totals().runs());
        assertEquals(0, innings.death().totals().runs());
    }
}

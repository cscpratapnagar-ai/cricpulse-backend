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

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

class GetMatchupIntelligenceTest {
    @Test
    void calculatesMatchupRatesAndLabelsSmallSamples() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID matchId = UUID.randomUUID();
        UUID batterId = UUID.randomUUID();
        UUID bowlerId = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);

        when(rs.getObject("batter_id", UUID.class)).thenReturn(batterId);
        when(rs.getObject("bowler_id", UUID.class)).thenReturn(bowlerId);
        when(rs.getString("batter_name")).thenReturn("Batter One");
        when(rs.getString("bowler_name")).thenReturn("Bowler One");
        when(rs.getInt("balls_faced")).thenReturn(8);
        when(rs.getInt("batter_runs")).thenReturn(12);
        when(rs.getInt("fours")).thenReturn(2);
        when(rs.getInt("sixes")).thenReturn(0);
        when(rs.getInt("dot_balls")).thenReturn(3);
        when(rs.getInt("dismissals")).thenReturn(1);

        when(jdbc.query(anyString(), any(RowMapper.class), eq(matchId))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<GetMatchupIntelligence.Matchup> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });

        GetMatchupIntelligence service = new GetMatchupIntelligence(jdbc);
        List<GetMatchupIntelligence.Matchup> result = service.get(matchId);

        assertEquals(1, result.size());
        GetMatchupIntelligence.Matchup matchup = result.getFirst();
        assertEquals(batterId, matchup.batterId());
        assertEquals(bowlerId, matchup.bowlerId());
        assertEquals(12, matchup.runs());
        assertEquals(8, matchup.ballsFaced());
        assertEquals("150.00", matchup.strikeRate().toPlainString());
        assertEquals("37.5", matchup.dotBallPercentage().toPlainString());
        assertEquals("EARLY SAMPLE", matchup.sampleLabel());
    }

    @Test
    void labelsTwelveOrMoreBallsAsEstablishedSample() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID matchId = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);

        when(rs.getObject("batter_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getObject("bowler_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getString("batter_name")).thenReturn("Batter Two");
        when(rs.getString("bowler_name")).thenReturn("Bowler Two");
        when(rs.getInt("balls_faced")).thenReturn(12);
        when(rs.getInt("batter_runs")).thenReturn(18);
        when(rs.getInt("fours")).thenReturn(2);
        when(rs.getInt("sixes")).thenReturn(1);
        when(rs.getInt("dot_balls")).thenReturn(4);
        when(rs.getInt("dismissals")).thenReturn(0);

        when(jdbc.query(anyString(), any(RowMapper.class), eq(matchId))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<GetMatchupIntelligence.Matchup> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });

        List<GetMatchupIntelligence.Matchup> result = new GetMatchupIntelligence(jdbc).get(matchId);

        assertEquals("ESTABLISHED SAMPLE", result.getFirst().sampleLabel());
    }
}

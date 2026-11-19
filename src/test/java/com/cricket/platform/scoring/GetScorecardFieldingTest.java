package com.cricket.platform.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetScorecardFieldingTest {
    @Test
    void includesRecordedFielderOnDismissedBatter() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID inningsId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID batterId = UUID.randomUUID();
        UUID fielderId = UUID.randomUUID();

        when(jdbc.query(anyString(), any(RowCallbackHandler.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowCallbackHandler callback = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    if (sql.contains("FROM innings i JOIN teams")) {
                        when(rs.getObject("id", UUID.class)).thenReturn(inningsId);
                        when(rs.getObject("match_id", UUID.class)).thenReturn(matchId);
                        when(rs.getInt("innings_number")).thenReturn(1);
                        when(rs.getObject("batting_team_id", UUID.class)).thenReturn(teamId);
                        when(rs.getString("team_name")).thenReturn("Falcons");
                        when(rs.getInt("total_runs")).thenReturn(120);
                        when(rs.getInt("wickets")).thenReturn(4);
                        when(rs.getInt("legal_balls")).thenReturn(90);
                        callback.processRow(rs);
                    } else if (sql.contains("FROM innings_batters b")) {
                        when(rs.getObject("innings_id", UUID.class)).thenReturn(inningsId);
                        when(rs.getObject("player_id", UUID.class)).thenReturn(batterId);
                        when(rs.getString("full_name")).thenReturn("Dismissed Batter");
                        when(rs.getInt("runs")).thenReturn(24);
                        when(rs.getInt("balls_faced")).thenReturn(18);
                        when(rs.getInt("fours")).thenReturn(3);
                        when(rs.getInt("sixes")).thenReturn(1);
                        when(rs.getBigDecimal("strike_rate")).thenReturn(new java.math.BigDecimal("133.33"));
                        when(rs.getBoolean("is_out")).thenReturn(true);
                        when(rs.getString("dismissal_type")).thenReturn("CAUGHT");
                        when(rs.getObject("fielder_id", UUID.class)).thenReturn(fielderId);
                        when(rs.getString("fielder_name")).thenReturn("A Fielder");
                        callback.processRow(rs);
                    }
                    return null;
                });

        GetScorecard.Scorecard result = new GetScorecard(jdbc).executeAll(List.of(inningsId)).getFirst();

        assertEquals(fielderId, result.batting().getFirst().fielderId());
        assertEquals("A Fielder", result.batting().getFirst().fielderName());
        assertEquals("CAUGHT", result.batting().getFirst().dismissal());
    }
}

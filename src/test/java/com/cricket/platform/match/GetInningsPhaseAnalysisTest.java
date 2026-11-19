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

class GetInningsPhaseAnalysisTest {
    @Test
    void calculatesRunRateAndDotPercentageFromLegalBalls() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID matchId = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("innings_number")).thenReturn(1);
        when(rs.getString("batting_team")).thenReturn("Falcons");
        when(rs.getString("format")).thenReturn("T20");
        when(rs.getString("phase")).thenReturn("POWERPLAY");
        when(rs.getInt("runs")).thenReturn(36);
        when(rs.getInt("wickets")).thenReturn(1);
        when(rs.getInt("legal_balls")).thenReturn(24);
        when(rs.getInt("dot_balls")).thenReturn(6);
        when(rs.getInt("fours")).thenReturn(4);
        when(rs.getInt("sixes")).thenReturn(2);
        when(rs.getInt("overs_covered")).thenReturn(4);
        when(rs.getInt("first_over")).thenReturn(0);
        when(rs.getInt("last_over")).thenReturn(3);

        when(jdbc.query(anyString(), any(RowMapper.class), eq(matchId))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<GetInningsPhaseAnalysis.PhaseSummary> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });

        List<GetInningsPhaseAnalysis.PhaseSummary> result =
                new GetInningsPhaseAnalysis(jdbc).get(matchId);

        assertEquals(1, result.size());
        GetInningsPhaseAnalysis.PhaseSummary phase = result.getFirst();
        assertEquals("POWERPLAY", phase.phase());
        assertEquals(36, phase.runs());
        assertEquals(1, phase.wickets());
        assertEquals("9.00", phase.runRate().toPlainString());
        assertEquals("25.0", phase.dotBallPercentage().toPlainString());
        assertEquals(1, phase.fromOver());
        assertEquals(4, phase.toOver());
        assertEquals("RECORDED SAMPLE", phase.sampleLabel());
    }

    @Test
    void marksSmallSamplesWithoutInventingRates() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID matchId = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("innings_number")).thenReturn(1);
        when(rs.getString("batting_team")).thenReturn("Falcons");
        when(rs.getString("format")).thenReturn("CUSTOM");
        when(rs.getString("phase")).thenReturn("DEATH");
        when(rs.getInt("runs")).thenReturn(0);
        when(rs.getInt("wickets")).thenReturn(0);
        when(rs.getInt("legal_balls")).thenReturn(0);
        when(rs.getInt("dot_balls")).thenReturn(0);
        when(rs.getInt("fours")).thenReturn(0);
        when(rs.getInt("sixes")).thenReturn(0);
        when(rs.getInt("overs_covered")).thenReturn(1);
        when(rs.getInt("first_over")).thenReturn(3);
        when(rs.getInt("last_over")).thenReturn(3);

        when(jdbc.query(anyString(), any(RowMapper.class), eq(matchId))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<GetInningsPhaseAnalysis.PhaseSummary> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });

        GetInningsPhaseAnalysis.PhaseSummary phase =
                new GetInningsPhaseAnalysis(jdbc).get(matchId).getFirst();

        assertEquals("0.00", phase.runRate().toPlainString());
        assertEquals("0.0", phase.dotBallPercentage().toPlainString());
        assertEquals("LIMITED SAMPLE", phase.sampleLabel());
    }
}

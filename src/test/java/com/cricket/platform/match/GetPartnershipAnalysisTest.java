package com.cricket.platform.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

class GetPartnershipAnalysisTest {
    @Test
    void mapsRecordedPartnershipAndIndividualContributions() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID matchId = UUID.randomUUID();
        UUID batterOne = UUID.randomUUID();
        UUID batterTwo = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("innings_number")).thenReturn(1);
        when(rs.getString("batting_team")).thenReturn("Falcons");
        when(rs.getInt("wicket_number")).thenReturn(2);
        when(rs.getObject("batter_one_id", UUID.class)).thenReturn(batterOne);
        when(rs.getString("batter_one_name")).thenReturn("A Batter");
        when(rs.getObject("batter_two_id", UUID.class)).thenReturn(batterTwo);
        when(rs.getString("batter_two_name")).thenReturn("B Batter");
        when(rs.getInt("runs")).thenReturn(47);
        when(rs.getInt("balls")).thenReturn(31);
        when(rs.getInt("batter_one_runs")).thenReturn(28);
        when(rs.getInt("batter_two_runs")).thenReturn(16);
        when(rs.getInt("start_over")).thenReturn(2);
        when(rs.getInt("start_ball")).thenReturn(3);
        when(rs.getInt("end_over")).thenReturn(7);
        when(rs.getInt("end_ball")).thenReturn(5);
        when(rs.getBoolean("is_current")).thenReturn(true);

        when(jdbc.query(anyString(), any(RowMapper.class), eq(matchId))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<GetPartnershipAnalysis.Partnership> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });

        GetPartnershipAnalysis.Partnership result =
                new GetPartnershipAnalysis(jdbc).get(matchId).getFirst();

        assertEquals(47, result.runs());
        assertEquals(31, result.balls());
        assertEquals(28, result.batterOneRuns());
        assertEquals(16, result.batterTwoRuns());
        assertEquals(3, result.startOver());
        assertEquals(5, result.startBall());
        assertEquals("ESTABLISHED SAMPLE", result.sampleLabel());
        assertTrue(result.current());
    }
}

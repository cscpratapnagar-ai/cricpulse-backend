package com.cricket.platform.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class GetBowlerSpellAnalysisTest {
    @Test
    void formatsCompletedAndPartialOversWithoutDecimalBallArithmetic() {
        assertEquals("0.0", GetBowlerSpellAnalysis.oversText(0));
        assertEquals("3.2", GetBowlerSpellAnalysis.oversText(20));
        assertEquals("4.0", GetBowlerSpellAnalysis.oversText(24));
    }

    @Test
    void buildsBowlerFiguresWithMaidensAndWicketCredit() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID matchId = UUID.randomUUID();
        UUID inningsId = UUID.randomUUID();
        UUID bowlerId = UUID.randomUUID();

        when(jdbc.query(anyString(), any(RowMapper.class), eq(matchId))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            if (sql.contains("FROM innings_bowlers")) {
                when(rs.getObject("innings_id", UUID.class)).thenReturn(inningsId);
                when(rs.getInt("innings_number")).thenReturn(1);
                when(rs.getString("batting_team")).thenReturn("Falcons");
                when(rs.getObject("bowler_id", UUID.class)).thenReturn(bowlerId);
                when(rs.getString("bowler_name")).thenReturn("A Bowler");
                when(rs.getInt("legal_balls")).thenReturn(24);
                when(rs.getInt("runs_conceded")).thenReturn(18);
                when(rs.getInt("wickets")).thenReturn(2);
                when(rs.getInt("wides")).thenReturn(1);
                when(rs.getInt("no_balls")).thenReturn(0);
            } else if (sql.contains("credited_to_bowler")) {
                when(rs.getObject("innings_id", UUID.class)).thenReturn(inningsId);
                when(rs.getObject("bowler_id", UUID.class)).thenReturn(bowlerId);
                when(rs.getInt("over_number")).thenReturn(3);
                when(rs.getInt("ball_number")).thenReturn(2);
                when(rs.getString("wicket_type")).thenReturn("BOWLED");
                when(rs.getString("dismissed_batter")).thenReturn("B Batter");
                when(rs.getBoolean("credited_to_bowler")).thenReturn(true);
            } else {
                when(rs.getObject("innings_id", UUID.class)).thenReturn(inningsId);
                when(rs.getObject("bowler_id", UUID.class)).thenReturn(bowlerId);
                when(rs.getInt("over_number")).thenReturn(1, 2, 3, 4);
                when(rs.getInt("legal_balls")).thenReturn(6, 6, 6, 6);
                when(rs.getInt("runs_conceded")).thenReturn(0, 4, 8, 6);
                when(rs.getInt("bowler_wickets")).thenReturn(0, 0, 1, 1);
            }
            return List.of(mapper.mapRow(rs, 0));
        });

        GetBowlerSpellAnalysis.BowlerSpell spell = new GetBowlerSpellAnalysis(jdbc).get(matchId).getFirst();

        assertEquals("A Bowler", spell.bowlerName());
        assertEquals("4.0", spell.overs());
        assertEquals(18, spell.runsConceded());
        assertEquals(2, spell.wickets());
        assertEquals(4.5, spell.economy());
        assertEquals(1, spell.maidens());
        assertEquals(1, spell.wides());
        assertEquals(1, spell.oversBreakdown().size());
        assertTrue(spell.oversBreakdown().getFirst().maiden());
        assertEquals(1, spell.wicketBreakdown().size());
        assertTrue(spell.wicketBreakdown().getFirst().creditedToBowler());
        assertFalse(spell.wicketBreakdown().getFirst().dismissal().isBlank());
    }
}

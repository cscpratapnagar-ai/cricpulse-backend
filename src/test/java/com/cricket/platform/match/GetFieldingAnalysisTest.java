package com.cricket.platform.match;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetFieldingAnalysisTest {
    @Test
    void mapsOnlyRecordedFielderContributions() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID matchId = UUID.randomUUID();
        UUID fielderId = UUID.randomUUID();

        when(jdbc.query(anyString(), any(RowMapper.class), eq(matchId))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<GetFieldingAnalysis.FielderContribution> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getInt("innings_number")).thenReturn(1);
            when(rs.getString("bowling_team")).thenReturn("Falcons");
            when(rs.getObject("fielder_id", UUID.class)).thenReturn(fielderId);
            when(rs.getString("fielder_name")).thenReturn("A Fielder");
            when(rs.getInt("catches")).thenReturn(2);
            when(rs.getInt("run_outs")).thenReturn(1);
            when(rs.getInt("stumpings")).thenReturn(0);
            return List.of(mapper.mapRow(rs, 0));
        });

        GetFieldingAnalysis.FielderContribution contribution =
                new GetFieldingAnalysis(jdbc).get(matchId).getFirst();

        assertEquals(fielderId, contribution.fielderId());
        assertEquals("Falcons", contribution.bowlingTeam());
        assertEquals(2, contribution.catches());
        assertEquals(1, contribution.runOuts());
        assertEquals(0, contribution.stumpings());
        assertEquals(3, contribution.totalDismissalContributions());
    }
}

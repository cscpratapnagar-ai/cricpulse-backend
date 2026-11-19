package com.cricket.platform.player;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetPlayerFieldingStatisticsTest {
    @Test
    void mapsCareerFieldingTotalsFromCompletedMatches() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID playerId = UUID.randomUUID();

        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(playerId))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<GetPlayerFieldingStatistics.FieldingStatistics> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("player_id", UUID.class)).thenReturn(playerId);
            when(rs.getString("player_name")).thenReturn("A Fielder");
            when(rs.getInt("catches")).thenReturn(4);
            when(rs.getInt("run_outs")).thenReturn(2);
            when(rs.getInt("stumpings")).thenReturn(1);
            return mapper.mapRow(rs, 0);
        });

        GetPlayerFieldingStatistics.FieldingStatistics result =
                new GetPlayerFieldingStatistics(jdbc).get(playerId);

        assertEquals(playerId, result.playerId());
        assertEquals("A Fielder", result.playerName());
        assertEquals(4, result.catches());
        assertEquals(2, result.runOuts());
        assertEquals(1, result.stumpings());
        assertEquals(7, result.totalContributions());
    }
}

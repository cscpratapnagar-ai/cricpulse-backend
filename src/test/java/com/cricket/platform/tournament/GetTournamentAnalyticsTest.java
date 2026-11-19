package com.cricket.platform.tournament;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetTournamentAnalyticsTest {
    @Test
    void buildsTournamentProgressAndTeamFormFromCompletedMatches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID tournamentId = UUID.randomUUID();
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();

        when(jdbc.queryForObject(contains("FROM tournaments"), any(RowMapper.class), eq(tournamentId)))
                .thenReturn(new GetTournamentAnalytics.TournamentRow(tournamentId, "Premier League", "T20", 20, "ACTIVE"));
        when(jdbc.query(contains("FROM tournament_matches tm"), any(RowMapper.class), eq(tournamentId)))
                .thenReturn(List.of(new GetTournamentAnalytics.FixtureRow(
                        matchId, 1, "LEAGUE", "COMPLETED", teamA, "Falcons", teamB, "Tigers")));
        when(jdbc.query(contains("FROM tournament_teams"), any(RowMapper.class), eq(tournamentId)))
                .thenReturn(List.of(
                        new GetTournamentAnalytics.TeamRow(teamA, "Falcons"),
                        new GetTournamentAnalytics.TeamRow(teamB, "Tigers")));
        when(jdbc.query(contains("FROM matches m"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new TournamentStandingsCalculator.Match(
                        teamA, teamB, "COMPLETED", "WIN", teamA, 20,
                        new TournamentStandingsCalculator.Innings(teamA, 160, 4, 120, 20),
                        new TournamentStandingsCalculator.Innings(teamB, 150, 6, 118, 20))));
        when(jdbc.query(contains("FROM tournament_matches tm"), any(RowMapper.class), eq(tournamentId), eq(5)))
                .thenReturn(List.of(new GetTournamentAnalytics.PlayerLeader(UUID.randomUUID(), "A Batter", 160, 0)));
        UUID fielderId = UUID.randomUUID();
        UUID allRounderId = UUID.randomUUID();
        when(jdbc.query(contains("player_match_activity"), any(RowMapper.class),
                eq(tournamentId), eq(tournamentId), eq(tournamentId), eq(tournamentId)))
                .thenReturn(List.of(new GetTournamentAnalytics.TournamentPlayerPerformance(
                        allRounderId, "A All-rounder", 3, 2, 84, 60, 1, 55,
                        new java.math.BigDecimal("84.00"), new java.math.BigDecimal("152.73"),
                        2, 24, 30, 4, new java.math.BigDecimal("7.50"),
                        new java.math.BigDecimal("7.50"), new java.math.BigDecimal("6.00"),
                        2, 1, 1, 4)));
        when(jdbc.query(contains("d.wicket_fielder_id"), any(RowMapper.class), eq(tournamentId), eq(10)))
                .thenReturn(List.of(new GetTournamentAnalytics.FieldingLeader(
                        fielderId, "A Fielder", 3, 2, 1, 6)));

        GetTournamentAnalytics.TournamentAnalytics result = new GetTournamentAnalytics(jdbc).get(tournamentId);

        ArgumentCaptor<String> standingsSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).query(standingsSql.capture(), any(RowMapper.class), any(Object[].class));
        String standingsQuery = standingsSql.getAllValues().stream()
                .filter(sql -> sql.contains("FROM matches m"))
                .findFirst().orElseThrow();
        assertTrue(standingsQuery.contains("COALESCE(tm.stage,'LEAGUE')='LEAGUE'"));
        assertTrue(standingsQuery.contains("i1.is_super_over=FALSE"));
        assertTrue(standingsQuery.contains("COALESCE(m.winning_team_id,m.winner_team_id)"));

        assertEquals(1, result.totalFixtures());
        assertEquals(1, result.completedFixtures());
        assertEquals(100.0, result.completionPercentage().doubleValue(), 0.001);
        assertEquals(2, result.teams().size());
        assertEquals("Falcons", result.teams().getFirst().teamName());
        assertEquals(2, result.teams().getFirst().points());
        assertEquals(0, result.teams().getFirst().noResults());
        assertEquals(List.of("W"), result.teams().getFirst().recentForm());
        assertEquals("Tigers", result.teams().get(1).teamName());
        assertEquals(0, result.teams().get(1).points());
        assertEquals(1, result.fixtures().size());
        assertEquals(1, result.topFielders().size());
        assertEquals(fielderId, result.topFielders().getFirst().playerId());
        assertEquals(3, result.topFielders().getFirst().catches());
        assertEquals(2, result.topFielders().getFirst().runOuts());
        assertEquals(1, result.topFielders().getFirst().stumpings());
        assertEquals(6, result.topFielders().getFirst().totalContributions());
        assertEquals(1, result.playerPerformances().size());
        var performance = result.playerPerformances().getFirst();
        assertEquals(allRounderId, performance.playerId());
        assertEquals(3, performance.matches());
        assertEquals(84, performance.runs());
        assertEquals(new java.math.BigDecimal("152.73"), performance.strikeRate());
        assertEquals(4, performance.wickets());
        assertEquals(new java.math.BigDecimal("7.50"), performance.economy());
        assertEquals(4, performance.totalFieldingContributions());

        ArgumentCaptor<String> playerLeaderSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(4)).query(playerLeaderSql.capture(), any(RowMapper.class),
                eq(tournamentId), eq(5));
        assertEquals(4, playerLeaderSql.getAllValues().size());
        assertTrue(playerLeaderSql.getAllValues().stream()
                .allMatch(sql -> sql.contains("JOIN users u ON u.id = p.user_id")
                        && sql.contains("u.full_name AS player_name")
                        && !sql.contains("p.name")));
    }
}

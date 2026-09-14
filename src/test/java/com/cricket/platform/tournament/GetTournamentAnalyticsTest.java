package com.cricket.platform.tournament;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetTournamentAnalyticsTest {
    @Test
    void buildsTournamentProgressAndTeamFormFromCompletedMatches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID tournamentId = UUID.randomUUID();
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();

        when(jdbc.query(contains("FROM tournaments"), any(RowMapper.class), eq(tournamentId)))
                .thenReturn(List.of(new GetTournamentAnalytics.TournamentRow(tournamentId, "Premier League", "T20", 20, "ACTIVE")));
        when(jdbc.query(contains("FROM tournament_matches tm"), any(RowMapper.class), eq(tournamentId)))
                .thenReturn(List.of(new GetTournamentAnalytics.FixtureRow(
                        matchId, 1, "LEAGUE", "COMPLETED", teamA, "Falcons", teamB, "Tigers")));
        when(jdbc.query(contains("FROM tournament_teams"), any(RowMapper.class), eq(tournamentId)))
                .thenReturn(List.of(
                        new GetTournamentAnalytics.TeamRow(teamA, "Falcons"),
                        new GetTournamentAnalytics.TeamRow(teamB, "Tigers")));
        when(jdbc.query(contains("FROM matches m"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new GetTournamentAnalytics.CompletedMatch(
                        matchId, teamA, teamB, teamA, 160, 120, teamB, 150, 118)));
        when(jdbc.query(contains("FROM tournament_matches tm"), any(RowMapper.class), eq(tournamentId), eq(5)))
                .thenReturn(List.of(new GetTournamentAnalytics.PlayerLeader(UUID.randomUUID(), "A Batter", 160, 0)));

        GetTournamentAnalytics.TournamentAnalytics result = new GetTournamentAnalytics(jdbc).get(tournamentId);

        assertEquals(1, result.totalFixtures());
        assertEquals(1, result.completedFixtures());
        assertEquals(100.0, result.completionPercentage().doubleValue(), 0.001);
        assertEquals(2, result.teams().size());
        assertEquals("Falcons", result.teams().getFirst().teamName());
        assertEquals(2, result.teams().getFirst().points());
        assertEquals(List.of("W"), result.teams().getFirst().recentForm());
        assertEquals("Tigers", result.teams().get(1).teamName());
        assertEquals(0, result.teams().get(1).points());
        assertEquals(1, result.fixtures().size());
    }
}

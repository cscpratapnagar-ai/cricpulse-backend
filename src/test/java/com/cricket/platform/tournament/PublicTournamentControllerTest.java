package com.cricket.platform.tournament;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PublicTournamentControllerTest {
    @Test
    void exposesOnlyReadOnlyTournamentSummaryAndPersistedFixtureResults() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GetTournamentAnalytics analytics = mock(GetTournamentAnalytics.class);
        UUID tournamentId = UUID.randomUUID();
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();

        var standingA = new GetTournamentAnalytics.TeamAnalytics(
                teamA, "Falcons", 3, 3, 0, 0, 0, 6, 300, 210, 1.5, List.of("W", "W", "W"));
        var standingB = new GetTournamentAnalytics.TeamAnalytics(
                teamB, "Tigers", 3, 2, 1, 0, 0, 4, 260, 230, 0.5, List.of("W", "L", "W"));
        var summary = new GetTournamentAnalytics.TournamentAnalytics(
                tournamentId, "City Premier League", "T20", 20, "ACTIVE",
                8, 6, 1, 1, new BigDecimal("75.0"),
                List.of(standingA, standingB), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        when(jdbc.queryForObject("SELECT status FROM tournaments WHERE id = ?", String.class, tournamentId))
                .thenReturn("ACTIVE");
        when(analytics.get(tournamentId)).thenReturn(summary);
        when(jdbc.query(contains("FROM tournament_matches tm"), any(RowMapper.class), eq(tournamentId)))
                .thenReturn(List.of(new PublicTournamentController.PublicFixture(
                        matchId, 7, "FINAL", "COMPLETED", "Falcons", "Tigers", "Falcons", null)));

        PublicTournamentController controller = new PublicTournamentController(jdbc, analytics);
        var result = controller.get(tournamentId);

        assertEquals(tournamentId, result.tournamentId());
        assertEquals("City Premier League", result.tournamentName());
        assertEquals(2, result.pointsTable().size());
        assertEquals("Falcons", result.pointsTable().getFirst().teamName());
        assertEquals(1, result.liveFixtures());
        assertEquals(1, result.scheduledFixtures());
        assertEquals(1, result.fixtures().size());
        assertEquals("FINAL", result.fixtures().getFirst().stage());
        assertEquals("Falcons", result.fixtures().getFirst().winnerName());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(tournamentId));
        assertTrue(sql.getValue().contains("CASE"));
        assertTrue(sql.getValue().contains("COALESCE(m.winning_team_id, m.winner_team_id)"));
        assertTrue(sql.getValue().contains("= m.team_a_id"));
        assertTrue(sql.getValue().contains("= m.team_b_id"));
    }

    @Test
    void doesNotExposeAnUnrelatedTeamAsTheFixtureWinner() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GetTournamentAnalytics analytics = mock(GetTournamentAnalytics.class);
        UUID tournamentId = UUID.randomUUID();
        var summary = new GetTournamentAnalytics.TournamentAnalytics(
                tournamentId, "Public Cup", "T20", 20, "ACTIVE",
                1, 1, 0, 0, new BigDecimal("100.0"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(jdbc.queryForObject("SELECT status FROM tournaments WHERE id = ?", String.class, tournamentId))
                .thenReturn("ACTIVE");
        when(analytics.get(tournamentId)).thenReturn(summary);
        when(jdbc.query(contains("FROM tournament_matches tm"), any(RowMapper.class), eq(tournamentId)))
                .thenReturn(List.of(new PublicTournamentController.PublicFixture(
                        UUID.randomUUID(), 1, "FINAL", "COMPLETED", "Falcons", "Tigers", null, null)));

        var result = new PublicTournamentController(jdbc, analytics).get(tournamentId);

        assertNull(result.fixtures().getFirst().winnerName());
    }

    @Test
    void hidesDraftAndUnknownTournamentStatusesFromPublicView() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GetTournamentAnalytics analytics = mock(GetTournamentAnalytics.class);
        UUID tournamentId = UUID.randomUUID();
        when(jdbc.queryForObject("SELECT status FROM tournaments WHERE id = ?", String.class, tournamentId))
                .thenReturn("DRAFT");

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> new PublicTournamentController(jdbc, analytics).get(tournamentId));

        assertEquals(404, error.getStatusCode().value());
        verify(analytics, never()).get(tournamentId);
        verify(jdbc, never()).query(contains("FROM tournament_matches tm"), any(RowMapper.class), eq(tournamentId));
    }

    @Test
    void returnsNotFoundWhenTournamentDoesNotExist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GetTournamentAnalytics analytics = mock(GetTournamentAnalytics.class);
        UUID tournamentId = UUID.randomUUID();
        when(jdbc.queryForObject("SELECT status FROM tournaments WHERE id = ?", String.class, tournamentId))
                .thenThrow(new EmptyResultDataAccessException(1));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> new PublicTournamentController(jdbc, analytics).get(tournamentId));

        assertEquals(404, error.getStatusCode().value());
        verify(analytics, never()).get(tournamentId);
    }
}

package com.cricket.platform.tournament;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TournamentPlayoffServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TournamentPlayoffService service = new TournamentPlayoffService(jdbc);
    private final UUID tournamentId = UUID.randomUUID();

    @Test
    void playoffGenerationRequiresFourRegisteredTeams() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "ACTIVE", "format", "T20", "overs", 20));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.advance(tournamentId, () -> List.of(
                        standing(), standing(), standing())));

        assertEquals(400, error.getStatusCode().value());
        assertTrue(error.getReason().contains("At least 4"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void playoffGenerationWaitsUntilEveryLeagueFixtureIsCompleted() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "ACTIVE", "format", "T20", "overs", 20));
        when(jdbc.queryForObject(contains("COALESCE(stage,'LEAGUE')='LEAGUE'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);
        when(jdbc.queryForObject(contains("m.status='COMPLETED'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(5);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.advance(tournamentId, () -> List.of(
                        standing(), standing(), standing(), standing())));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("Every league fixture"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void playoffGenerationRejectsTournamentWithNoLeagueFixtures() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "ACTIVE", "format", "T20", "overs", 20));
        when(jdbc.queryForObject(contains("COALESCE(stage,'LEAGUE')='LEAGUE'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(0);
        when(jdbc.queryForObject(contains("m.status='COMPLETED'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(0);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.advance(tournamentId, () -> List.of(
                        standing(), standing(), standing(), standing())));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("Every league fixture"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).query(contains("FROM tournament_matches tm"),
                any(RowMapper.class), any(), any());
    }

    @Test
    void playoffGenerationRequiresActiveTournament() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "DRAFT", "format", "T20", "overs", 20));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.advance(tournamentId, () -> List.of(
                        standing(), standing(), standing(), standing())));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("ACTIVE"));
    }

    @Test
    void inactiveTournamentIsRejectedBeforeStandingsAreCalculated() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "DRAFT", "format", "T20", "overs", 20));
        AtomicBoolean standingsCalculated = new AtomicBoolean(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.advance(tournamentId, () -> {
                    standingsCalculated.set(true);
                    return List.of(standing(), standing(), standing(), standing());
                }));

        assertEquals(409, error.getStatusCode().value());
        assertFalse(standingsCalculated.get());
        verify(jdbc, never()).queryForObject(anyString(), eq(Integer.class), eq(tournamentId));
    }

    @Test
    void insufficientTeamsAreRejectedBeforeLeagueFixtureQueries() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "ACTIVE", "format", "T20", "overs", 20));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.advance(tournamentId, () -> List.of(
                        standing(), standing(), standing())));

        assertEquals(400, error.getStatusCode().value());
        verify(jdbc, never()).queryForObject(anyString(), eq(Integer.class), eq(tournamentId));
    }

    private TournamentController.PointRow standing() {
        return new TournamentController.PointRow(UUID.randomUUID(), "Team", 3, 2, 1,
                0, 0, 4, 100, 90, 1.0);
    }
}

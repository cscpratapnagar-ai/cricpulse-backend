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

    @Test
    void duplicateQualifierFixturesAreRejectedWithoutCreatingMoreFixtures() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "ACTIVE", "format", "T20", "overs", 20));
        when(jdbc.queryForObject(contains("COALESCE(stage,'LEAGUE')='LEAGUE'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);
        when(jdbc.queryForObject(contains("m.status='COMPLETED'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);

        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        List<TournamentPlayoffService.Fixture> duplicates = List.of(
                new TournamentPlayoffService.Fixture(UUID.randomUUID(), 7, "QUALIFIER_1",
                        "Qualifier 1 A", teamA, "A", teamB, "B", "SCHEDULED"),
                new TournamentPlayoffService.Fixture(UUID.randomUUID(), 8, "QUALIFIER_1",
                        "Qualifier 1 B", teamA, "A", teamB, "B", "SCHEDULED"));
        doReturn(duplicates).when(jdbc).query(
                contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("QUALIFIER_1"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.advance(tournamentId, () -> List.of(
                        standing(), standing(), standing(), standing())));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("Multiple QUALIFIER_1 fixtures"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }


    @Test
    void duplicateEliminatorFixturesAreRejectedWithoutCreatingMoreFixtures() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "ACTIVE", "format", "T20", "overs", 20));
        when(jdbc.queryForObject(contains("COALESCE(stage,'LEAGUE')='LEAGUE'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);
        when(jdbc.queryForObject(contains("m.status='COMPLETED'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);

        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        TournamentPlayoffService.Fixture qualifier = new TournamentPlayoffService.Fixture(
                UUID.randomUUID(), 7, "QUALIFIER_1", "Qualifier 1", teamA, "A",
                teamB, "B", "SCHEDULED");
        List<TournamentPlayoffService.Fixture> duplicateEliminators = List.of(
                new TournamentPlayoffService.Fixture(UUID.randomUUID(), 8, "ELIMINATOR",
                        "Eliminator A", teamA, "A", teamB, "B", "SCHEDULED"),
                new TournamentPlayoffService.Fixture(UUID.randomUUID(), 9, "ELIMINATOR",
                        "Eliminator B", teamA, "A", teamB, "B", "SCHEDULED"));
        doReturn(List.of(qualifier)).when(jdbc).query(
                contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("QUALIFIER_1"));
        doReturn(duplicateEliminators).when(jdbc).query(
                contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("ELIMINATOR"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.advance(tournamentId, () -> List.of(
                        standing(), standing(), standing(), standing())));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("Multiple ELIMINATOR fixtures"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private TournamentController.PointRow standing() {
        return new TournamentController.PointRow(UUID.randomUUID(), "Team", 3, 2, 1,
                0, 0, 4, 100, 90, 1.0);
    }

    @Test
    void playoffProgressionRejectsWinnerOutsideFixtureParticipants() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "ACTIVE", "format", "T20", "overs", 20));
        when(jdbc.queryForObject(contains("COALESCE(stage,'LEAGUE')='LEAGUE'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);
        when(jdbc.queryForObject(contains("m.status='COMPLETED'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);

        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID qualifierMatchId = UUID.randomUUID();
        TournamentPlayoffService.Fixture qualifier = new TournamentPlayoffService.Fixture(
                qualifierMatchId, 7, "QUALIFIER_1", "Qualifier 1", teamA, "A",
                teamB, "B", "COMPLETED");
        TournamentPlayoffService.Fixture eliminator = new TournamentPlayoffService.Fixture(
                UUID.randomUUID(), 8, "ELIMINATOR", "Eliminator", UUID.randomUUID(), "C",
                UUID.randomUUID(), "D", "SCHEDULED");
        doReturn(List.of(qualifier)).when(jdbc).query(
                contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("QUALIFIER_1"));
        doReturn(List.of(eliminator)).when(jdbc).query(
                contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("ELIMINATOR"));
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),
                eq(UUID.class), eq(qualifierMatchId))).thenReturn(outsider);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.advance(tournamentId, () -> List.of(
                        standing(), standing(), standing(), standing())));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("invalid winner outside its participating teams"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }


    @Test
    void playoffProgressionRejectsEliminatorWinnerOutsideFixtureParticipants() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "ACTIVE", "format", "T20", "overs", 20));
        when(jdbc.queryForObject(contains("COALESCE(stage,'LEAGUE')='LEAGUE'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);
        when(jdbc.queryForObject(contains("m.status='COMPLETED'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);

        UUID q1A = UUID.randomUUID();
        UUID q1B = UUID.randomUUID();
        UUID eliminatorA = UUID.randomUUID();
        UUID eliminatorB = UUID.randomUUID();
        UUID eliminatorMatchId = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        TournamentPlayoffService.Fixture qualifier = new TournamentPlayoffService.Fixture(
                UUID.randomUUID(), 7, "QUALIFIER_1", "Qualifier 1", q1A, "A",
                q1B, "B", "SCHEDULED");
        TournamentPlayoffService.Fixture eliminator = new TournamentPlayoffService.Fixture(
                eliminatorMatchId, 8, "ELIMINATOR", "Eliminator", eliminatorA, "C",
                eliminatorB, "D", "COMPLETED");
        doReturn(List.of(qualifier)).when(jdbc).query(
                contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("QUALIFIER_1"));
        doReturn(List.of(eliminator)).when(jdbc).query(
                contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("ELIMINATOR"));
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),
                eq(UUID.class), eq(eliminatorMatchId))).thenReturn(outsider);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.advance(tournamentId, () -> List.of(
                        standing(), standing(), standing(), standing())));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("invalid winner outside its participating teams"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }



    @Test
    void playoffProgressionRejectsQualifier2WinnerOutsideFixtureParticipants() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "ACTIVE", "format", "T20", "overs", 20));
        when(jdbc.queryForObject(contains("COALESCE(stage,'LEAGUE')='LEAGUE'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);
        when(jdbc.queryForObject(contains("m.status='COMPLETED'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);

        UUID q1A = UUID.randomUUID();
        UUID q1B = UUID.randomUUID();
        UUID elimA = UUID.randomUUID();
        UUID elimB = UUID.randomUUID();
        UUID q1Match = UUID.randomUUID();
        UUID elimMatch = UUID.randomUUID();
        UUID q2Match = UUID.randomUUID();
        UUID q1Winner = q1A;
        UUID elimWinner = elimA;
        UUID outsider = UUID.randomUUID();
        TournamentPlayoffService.Fixture q1 = new TournamentPlayoffService.Fixture(
                q1Match, 7, "QUALIFIER_1", "Qualifier 1", q1A, "A", q1B, "B", "COMPLETED");
        TournamentPlayoffService.Fixture eliminator = new TournamentPlayoffService.Fixture(
                elimMatch, 8, "ELIMINATOR", "Eliminator", elimA, "C", elimB, "D", "COMPLETED");
        TournamentPlayoffService.Fixture q2 = new TournamentPlayoffService.Fixture(
                q2Match, 9, "QUALIFIER_2", "Qualifier 2", q1B, "B", elimWinner, "C", "COMPLETED");
        doReturn(List.of(q1)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("QUALIFIER_1"));
        doReturn(List.of(eliminator)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("ELIMINATOR"));
        doReturn(List.of(q2)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("QUALIFIER_2"));
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),
                eq(UUID.class), eq(q1Match))).thenReturn(q1Winner);
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),
                eq(UUID.class), eq(elimMatch))).thenReturn(elimWinner);
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),
                eq(UUID.class), eq(q2Match))).thenReturn(outsider);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.advance(tournamentId, () -> List.of(
                        standing(), standing(), standing(), standing())));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("Qualifier 2 has an invalid winner"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void playoffProgressionRejectsFinalWinnerOutsideFixtureParticipants() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "ACTIVE", "format", "T20", "overs", 20));
        when(jdbc.queryForObject(contains("COALESCE(stage,'LEAGUE')='LEAGUE'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);
        when(jdbc.queryForObject(contains("m.status='COMPLETED'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);

        UUID q1A = UUID.randomUUID();
        UUID q1B = UUID.randomUUID();
        UUID elimA = UUID.randomUUID();
        UUID elimB = UUID.randomUUID();
        UUID q1Match = UUID.randomUUID();
        UUID elimMatch = UUID.randomUUID();
        UUID q2Match = UUID.randomUUID();
        UUID finalMatch = UUID.randomUUID();
        UUID q1Winner = q1A;
        UUID elimWinner = elimA;
        UUID q2Winner = q1B;
        UUID outsider = UUID.randomUUID();
        TournamentPlayoffService.Fixture q1 = new TournamentPlayoffService.Fixture(
                q1Match, 7, "QUALIFIER_1", "Qualifier 1", q1A, "A", q1B, "B", "COMPLETED");
        TournamentPlayoffService.Fixture eliminator = new TournamentPlayoffService.Fixture(
                elimMatch, 8, "ELIMINATOR", "Eliminator", elimA, "C", elimB, "D", "COMPLETED");
        TournamentPlayoffService.Fixture q2 = new TournamentPlayoffService.Fixture(
                q2Match, 9, "QUALIFIER_2", "Qualifier 2", q1B, "B", elimWinner, "C", "COMPLETED");
        TournamentPlayoffService.Fixture finalFixture = new TournamentPlayoffService.Fixture(
                finalMatch, 10, "FINAL", "Final", q1Winner, "A", q2Winner, "B", "COMPLETED");
        doReturn(List.of(q1)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("QUALIFIER_1"));
        doReturn(List.of(eliminator)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("ELIMINATOR"));
        doReturn(List.of(q2)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("QUALIFIER_2"));
        doReturn(List.of(finalFixture)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),
                any(RowMapper.class), eq(tournamentId), eq("FINAL"));
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),
                eq(UUID.class), eq(q1Match))).thenReturn(q1Winner);
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),
                eq(UUID.class), eq(elimMatch))).thenReturn(elimWinner);
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),
                eq(UUID.class), eq(q2Match))).thenReturn(q2Winner);
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),
                eq(UUID.class), eq(finalMatch))).thenReturn(outsider);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.advance(tournamentId, () -> List.of(
                        standing(), standing(), standing(), standing())));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("Final has an invalid winner"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }



    @Test
    void playoffProgressionRejectsQualifier2WinnerOutsideFixtureParticipants() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "ACTIVE", "format", "T20", "overs", 20));
        when(jdbc.queryForObject(contains("COALESCE(stage,'LEAGUE')='LEAGUE'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);
        when(jdbc.queryForObject(contains("m.status='COMPLETED'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);
        UUID a=UUID.randomUUID(), b=UUID.randomUUID(), c=UUID.randomUUID(), d=UUID.randomUUID();
        UUID q1id=UUID.randomUUID(), eid=UUID.randomUUID(), q2id=UUID.randomUUID(), outsider=UUID.randomUUID();
        TournamentPlayoffService.Fixture q1=new TournamentPlayoffService.Fixture(q1id,7,"QUALIFIER_1","Q1",a,"A",b,"B","COMPLETED");
        TournamentPlayoffService.Fixture el=new TournamentPlayoffService.Fixture(eid,8,"ELIMINATOR","Eliminator",c,"C",d,"D","COMPLETED");
        TournamentPlayoffService.Fixture q2=new TournamentPlayoffService.Fixture(q2id,9,"QUALIFIER_2","Q2",b,"B",c,"C","COMPLETED");
        doReturn(List.of(q1)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),any(RowMapper.class),eq(tournamentId),eq("QUALIFIER_1"));
        doReturn(List.of(el)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),any(RowMapper.class),eq(tournamentId),eq("ELIMINATOR"));
        doReturn(List.of(q2)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),any(RowMapper.class),eq(tournamentId),eq("QUALIFIER_2"));
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),eq(UUID.class),eq(q1id))).thenReturn(a);
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),eq(UUID.class),eq(eid))).thenReturn(c);
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),eq(UUID.class),eq(q2id))).thenReturn(outsider);
        ResponseStatusException error=assertThrows(ResponseStatusException.class,()->service.advance(tournamentId,()->List.of(standing(),standing(),standing(),standing())));
        assertEquals(409,error.getStatusCode().value());
        assertTrue(error.getReason().contains("Qualifier 2 has an invalid winner"));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void playoffProgressionRejectsFinalWinnerOutsideFixtureParticipants() {
        when(jdbc.queryForMap(anyString(), eq(tournamentId)))
                .thenReturn(Map.of("status", "ACTIVE", "format", "T20", "overs", 20));
        when(jdbc.queryForObject(contains("COALESCE(stage,'LEAGUE')='LEAGUE'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);
        when(jdbc.queryForObject(contains("m.status='COMPLETED'"),
                eq(Integer.class), eq(tournamentId))).thenReturn(6);
        UUID a=UUID.randomUUID(), b=UUID.randomUUID(), c=UUID.randomUUID(), d=UUID.randomUUID();
        UUID q1id=UUID.randomUUID(), eid=UUID.randomUUID(), q2id=UUID.randomUUID(), fid=UUID.randomUUID(), outsider=UUID.randomUUID();
        TournamentPlayoffService.Fixture q1=new TournamentPlayoffService.Fixture(q1id,7,"QUALIFIER_1","Q1",a,"A",b,"B","COMPLETED");
        TournamentPlayoffService.Fixture el=new TournamentPlayoffService.Fixture(eid,8,"ELIMINATOR","Eliminator",c,"C",d,"D","COMPLETED");
        TournamentPlayoffService.Fixture q2=new TournamentPlayoffService.Fixture(q2id,9,"QUALIFIER_2","Q2",b,"B",c,"C","COMPLETED");
        TournamentPlayoffService.Fixture fin=new TournamentPlayoffService.Fixture(fid,10,"FINAL","Final",a,"A",b,"B","COMPLETED");
        doReturn(List.of(q1)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),any(RowMapper.class),eq(tournamentId),eq("QUALIFIER_1"));
        doReturn(List.of(el)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),any(RowMapper.class),eq(tournamentId),eq("ELIMINATOR"));
        doReturn(List.of(q2)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),any(RowMapper.class),eq(tournamentId),eq("QUALIFIER_2"));
        doReturn(List.of(fin)).when(jdbc).query(contains("WHERE tm.tournament_id=? AND tm.stage=?"),any(RowMapper.class),eq(tournamentId),eq("FINAL"));
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),eq(UUID.class),eq(q1id))).thenReturn(a);
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),eq(UUID.class),eq(eid))).thenReturn(c);
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),eq(UUID.class),eq(q2id))).thenReturn(b);
        when(jdbc.queryForObject(contains("COALESCE(winning_team_id,winner_team_id)"),eq(UUID.class),eq(fid))).thenReturn(outsider);
        ResponseStatusException error=assertThrows(ResponseStatusException.class,()->service.advance(tournamentId,()->List.of(standing(),standing(),standing(),standing())));
        assertEquals(409,error.getStatusCode().value());
        assertTrue(error.getReason().contains("Final has an invalid winner"));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

}

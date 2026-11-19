package com.cricket.platform.tournament;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class TournamentPlayoffService {
    private final JdbcTemplate jdbc;

    TournamentPlayoffService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    PlayoffProgressResponse advance(UUID tournamentId, List<TournamentController.PointRow> standings) {
        Map<String, Object> tournament = jdbc.queryForMap(
                "SELECT status, format, overs FROM tournaments WHERE id=? FOR UPDATE", tournamentId);
        if (!"ACTIVE".equalsIgnoreCase(Objects.toString(tournament.get("status"), ""))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Playoffs can only progress while the tournament is ACTIVE");
        }
        if (standings.size() < 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At least 4 registered teams are required for the top-four playoff bracket");
        }

        Integer leagueFixtures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tournament_matches WHERE tournament_id=? AND COALESCE(stage,'LEAGUE')='LEAGUE'",
                Integer.class, tournamentId);
        Integer completedLeagueFixtures = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM tournament_matches tm
                JOIN matches m ON m.id=tm.match_id
                WHERE tm.tournament_id=? AND COALESCE(tm.stage,'LEAGUE')='LEAGUE'
                  AND m.status='COMPLETED'
                """, Integer.class, tournamentId);
        if (leagueFixtures == null || leagueFixtures == 0
                || !Objects.equals(leagueFixtures, completedLeagueFixtures)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Every league fixture must be completed before playoff generation");
        }

        Fixture q1 = findStage(tournamentId, "QUALIFIER_1");
        Fixture eliminator = findStage(tournamentId, "ELIMINATOR");
        if (q1 == null && eliminator == null) {
            q1 = createFixture(tournamentId, "QUALIFIER_1", "Qualifier 1",
                    standings.get(0).teamId(), standings.get(1).teamId(), tournament);
            eliminator = createFixture(tournamentId, "ELIMINATOR", "Eliminator",
                    standings.get(2).teamId(), standings.get(3).teamId(), tournament);
            return response("INITIAL_PLAYOFFS_CREATED",
                    "Qualifier 1 and Eliminator fixtures have been generated.", q1, eliminator,
                    null, null, null);
        }
        if (q1 == null || eliminator == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Playoff bracket is incomplete; refusing to create a potentially inconsistent bracket");
        }

        UUID q1Winner = completedWinner(q1, "Qualifier 1");
        UUID eliminatorWinner = completedWinner(eliminator, "Eliminator");
        if (q1Winner == null || eliminatorWinner == null) {
            return response("WAITING_FOR_QUALIFIER_RESULTS",
                    "Qualifier 1 and Eliminator must both finish with an authoritative winner.", q1, eliminator,
                    findStage(tournamentId, "QUALIFIER_2"), findStage(tournamentId, "FINAL"), null);
        }

        UUID q1Loser = otherTeam(q1, q1Winner);
        Fixture q2 = findStage(tournamentId, "QUALIFIER_2");
        if (q2 == null) {
            q2 = createFixture(tournamentId, "QUALIFIER_2", "Qualifier 2",
                    q1Loser, eliminatorWinner, tournament);
        } else {
            assertTeams(q2, q1Loser, eliminatorWinner, "Qualifier 2");
        }

        UUID q2Winner = completedWinner(q2, "Qualifier 2");
        if (q2Winner == null) {
            return response("WAITING_FOR_QUALIFIER_2",
                    "Qualifier 2 is ready or in progress; the Final waits for its confirmed winner.",
                    q1, eliminator, q2, findStage(tournamentId, "FINAL"), null);
        }

        Fixture finalFixture = findStage(tournamentId, "FINAL");
        if (finalFixture == null) {
            finalFixture = createFixture(tournamentId, "FINAL", "Final",
                    q1Winner, q2Winner, tournament);
        } else {
            assertTeams(finalFixture, q1Winner, q2Winner, "Final");
        }

        UUID champion = completedWinner(finalFixture, "Final");
        return response(champion == null ? "WAITING_FOR_FINAL" : "CHAMPION_CROWNED",
                champion == null ? "Final fixture is ready; champion will be confirmed when it has an authoritative winner."
                        : "Tournament champion has been confirmed.", q1, eliminator, q2, finalFixture, champion);
    }

    private UUID completedWinner(Fixture fixture, String label) {
        if (!"COMPLETED".equalsIgnoreCase(fixture.status())) return null;
        UUID winner = jdbc.queryForObject(
                "SELECT COALESCE(winning_team_id,winner_team_id) FROM matches WHERE id=?",
                UUID.class, fixture.matchId());
        if (winner == null) return null;
        if (!winner.equals(fixture.teamAId()) && !winner.equals(fixture.teamBId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    label + " has an invalid winner outside its participating teams");
        }
        return winner;
    }

    private UUID otherTeam(Fixture fixture, UUID winner) {
        return fixture.teamAId().equals(winner) ? fixture.teamBId() : fixture.teamAId();
    }

    private void assertTeams(Fixture fixture, UUID expectedA, UUID expectedB, String label) {
        boolean samePair = (fixture.teamAId().equals(expectedA) && fixture.teamBId().equals(expectedB))
                || (fixture.teamAId().equals(expectedB) && fixture.teamBId().equals(expectedA));
        if (!samePair) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    label + " fixture participants do not match the qualified teams");
        }
    }

    private Fixture findStage(UUID tournamentId, String stage) {
        List<Fixture> found = jdbc.query(
                """
                SELECT tm.match_id, tm.fixture_number, tm.stage, m.name,
                       m.team_a_id, a.name AS team_a_name, m.team_b_id, b.name AS team_b_name,
                       m.status
                FROM tournament_matches tm
                JOIN matches m ON m.id=tm.match_id
                JOIN teams a ON a.id=m.team_a_id
                JOIN teams b ON b.id=m.team_b_id
                WHERE tm.tournament_id=? AND tm.stage=?
                """,
                (r, n) -> new Fixture(r.getObject("match_id", UUID.class),
                        r.getObject("fixture_number", Integer.class), r.getString("stage"),
                        r.getString("name"), r.getObject("team_a_id", UUID.class),
                        r.getString("team_a_name"), r.getObject("team_b_id", UUID.class),
                        r.getString("team_b_name"), r.getString("status")),
                tournamentId, stage);
        if (found.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Multiple " + stage + " fixtures exist; refusing ambiguous progression");
        }
        return found.stream().findFirst().orElse(null);
    }

    private Fixture createFixture(UUID tournamentId, String stage, String label,
                                  UUID teamA, UUID teamB, Map<String, Object> tournament) {
        if (teamA.equals(teamB)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    label + " cannot contain the same team twice");
        }
        int nextNumber = jdbc.queryForObject(
                "SELECT COALESCE(MAX(fixture_number),0)+1 FROM tournament_matches WHERE tournament_id=?",
                Integer.class, tournamentId);
        UUID matchId = UUID.randomUUID();
        String format = Objects.toString(tournament.get("format"));
        int overs = ((Number) tournament.get("overs")).intValue();
        jdbc.update("""
                INSERT INTO matches(id,name,team_a_id,team_b_id,format,total_overs,status)
                VALUES (?,?,?,?,?,?, 'SCHEDULED')
                """, matchId, label + ": " + teamName(teamA) + " vs " + teamName(teamB),
                teamA, teamB, format, overs);
        jdbc.update("""
                INSERT INTO tournament_matches(tournament_id,match_id,stage,fixture_number)
                VALUES (?,?,?,?)
                """, tournamentId, matchId, stage, nextNumber);
        return new Fixture(matchId, nextNumber, stage,
                label + ": " + teamName(teamA) + " vs " + teamName(teamB),
                teamA, teamName(teamA), teamB, teamName(teamB), "SCHEDULED");
    }

    private String teamName(UUID teamId) {
        return jdbc.queryForObject("SELECT name FROM teams WHERE id=?", String.class, teamId);
    }

    private PlayoffProgressResponse response(String status, String message, Fixture q1,
                                             Fixture eliminator, Fixture q2, Fixture finalFixture,
                                             UUID champion) {
        return new PlayoffProgressResponse(status, message, q1, eliminator, q2, finalFixture, champion);
    }

    record Fixture(UUID matchId, Integer fixtureNumber, String stage, String matchName,
                   UUID teamAId, String teamAName, UUID teamBId, String teamBName, String status) {}

    record PlayoffProgressResponse(String status, String message, Fixture qualifier1,
                                   Fixture eliminator, Fixture qualifier2, Fixture finalFixture,
                                   UUID championTeamId) {}
}

package com.cricket.platform.tournament.service.impl;

import com.cricket.platform.tournament.dto.response.QualificationPreviewResponse;
import com.cricket.platform.tournament.dto.response.QualifierResponse;
import com.cricket.platform.tournament.dto.response.TournamentPointRowResponse;
import com.cricket.platform.tournament.dto.response.TournamentTeamResponse;
import com.cricket.platform.tournament.service.TournamentStandingsService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class TournamentStandingsServiceImpl implements TournamentStandingsService {

    private final JdbcTemplate jdbc;

    public TournamentStandingsServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TournamentPointRowResponse> getPointsTable(UUID tournamentId, Authentication authentication) {
        requireOwner(tournamentId, authentication);
        List<TournamentPointRowResponse> standings = new ArrayList<>();

        List<TournamentTeamResponse> teams = jdbc.query(
                "SELECT t.id,t.name,t.city,tt.seed FROM tournament_teams tt JOIN teams t ON t.id=tt.team_id WHERE tt.tournament_id=? ORDER BY COALESCE(tt.seed,999999),t.name",
                (row, index) -> new TournamentTeamResponse(row.getObject("id", UUID.class), row.getString("name"),
                        row.getString("city"), row.getObject("seed", Integer.class)),
                tournamentId);

        for (TournamentTeamResponse team : teams) {
            int played = 0, wins = 0, losses = 0, ties = 0;
            int runsFor = 0, runsAgainst = 0, ballsFaced = 0, ballsBowled = 0;

            List<MatchScore> matches = jdbc.query(
                    "SELECT m.id,ia.batting_team_id ia_team,ia.total_runs ia_runs,ia.legal_balls ia_balls,ib.batting_team_id ib_team,ib.total_runs ib_runs,ib.legal_balls ib_balls FROM tournament_matches tm JOIN matches m ON m.id=tm.match_id LEFT JOIN innings ia ON ia.match_id=m.id AND ia.innings_number=1 LEFT JOIN innings ib ON ib.match_id=m.id AND ib.innings_number=2 WHERE tm.tournament_id=? AND m.status='COMPLETED' AND (m.team_a_id=? OR m.team_b_id=?)",
                    (row, index) -> new MatchScore(row.getObject("id", UUID.class), row.getObject("ia_team", UUID.class),
                            row.getInt("ia_runs"), row.getInt("ia_balls"), row.getObject("ib_team", UUID.class),
                            row.getInt("ib_runs"), row.getInt("ib_balls")),
                    tournamentId, team.id(), team.id());

            for (MatchScore match : matches) {
                played++;
                boolean battedFirst = team.id().equals(match.firstBattingTeam());
                int ownRuns = battedFirst ? match.firstRuns() : match.secondRuns();
                int opponentRuns = battedFirst ? match.secondRuns() : match.firstRuns();
                int ownBalls = battedFirst ? match.firstBalls() : match.secondBalls();
                int opponentBalls = battedFirst ? match.secondBalls() : match.firstBalls();

                runsFor += ownRuns;
                runsAgainst += opponentRuns;
                ballsFaced += ownBalls;
                ballsBowled += opponentBalls;

                if (ownRuns > opponentRuns) wins++;
                else if (ownRuns < opponentRuns) losses++;
                else ties++;
            }

            double nrr = calculateNrr(runsFor, ballsFaced, runsAgainst, ballsBowled);
            standings.add(new TournamentPointRowResponse(team.id(), team.name(), played, wins, losses, ties,
                    wins * 2 + ties, runsFor, runsAgainst, nrr));
        }

        standings.sort(
                Comparator.comparingInt(TournamentPointRowResponse::points).reversed()
                        .thenComparing(Comparator.comparingDouble(TournamentPointRowResponse::nrr).reversed())
                        .thenComparing(Comparator.comparingInt(TournamentPointRowResponse::runsFor).reversed())
                        .thenComparing(TournamentPointRowResponse::teamName));
        return standings;
    }

    @Override
    public QualificationPreviewResponse getQualificationPreview(UUID tournamentId, Authentication authentication) {
        List<TournamentPointRowResponse> table = getPointsTable(tournamentId, authentication);

        if (table.size() < 4) {
            return new QualificationPreviewResponse(false,
                    "At least 4 tournament teams are required for a top-4 qualification bracket.", table, List.of());
        }

        List<QualifierResponse> qualifiers = List.of(
                new QualifierResponse(1, "QUALIFIER 1", table.get(0), table.get(3)),
                new QualifierResponse(2, "QUALIFIER 2", table.get(1), table.get(2)));

        return new QualificationPreviewResponse(true,
                "Top 4 are currently projected to qualify based on points, NRR and runs-for.",
                table, qualifiers);
    }

    private double calculateNrr(int runsFor, int ballsFaced, int runsAgainst, int ballsBowled) {
        if (ballsFaced == 0 || ballsBowled == 0) return 0;
        double scoringRate = runsFor / (ballsFaced / 6.0);
        double concedingRate = runsAgainst / (ballsBowled / 6.0);
        return Math.round((scoringRate - concedingRate) * 1000.0) / 1000.0;
    }

    private void requireOwner(UUID tournamentId, Authentication authentication) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tournaments WHERE id=? AND owner_id=?",
                Integer.class, tournamentId, ownerId(authentication));
        if (count == null || count == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found");
    }

    private UUID ownerId(Authentication authentication) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM users WHERE LOWER(TRIM(email))=LOWER(TRIM(?)) OR CAST(id AS TEXT)=?",
                    UUID.class, authentication.getName(), authentication.getName());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found");
        }
    }

    private record MatchScore(UUID matchId, UUID firstBattingTeam, int firstRuns, int firstBalls,
                              UUID secondBattingTeam, int secondRuns, int secondBalls) {}
}

package com.cricket.platform.tournament;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Component
public class GetTournamentAnalytics {
    private final JdbcTemplate jdbc;

    public GetTournamentAnalytics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TournamentAnalytics get(UUID tournamentId) {
        TournamentRow tournament = jdbc.queryForObject("""
                SELECT id, name, format, overs, status
                FROM tournaments
                WHERE id=?
                """, (rs, row) -> new TournamentRow(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("format"),
                rs.getInt("overs"), rs.getString("status")), tournamentId);

        List<FixtureRow> fixtures = jdbc.query("""
                SELECT tm.match_id, tm.fixture_number, tm.stage, m.status,
                       m.team_a_id, a.name team_a_name, m.team_b_id, b.name team_b_name
                FROM tournament_matches tm
                JOIN matches m ON m.id=tm.match_id
                JOIN teams a ON a.id=m.team_a_id
                JOIN teams b ON b.id=m.team_b_id
                WHERE tm.tournament_id=?
                ORDER BY COALESCE(tm.fixture_number,999999)
                """, (rs, row) -> new FixtureRow(
                rs.getObject("match_id", UUID.class), rs.getObject("fixture_number", Integer.class),
                rs.getString("stage"), rs.getString("status"), rs.getObject("team_a_id", UUID.class),
                rs.getString("team_a_name"), rs.getObject("team_b_id", UUID.class), rs.getString("team_b_name")), tournamentId);

        List<TeamRow> teams = jdbc.query("""
                SELECT t.id, t.name
                FROM tournament_teams tt
                JOIN teams t ON t.id=tt.team_id
                WHERE tt.tournament_id=?
                ORDER BY COALESCE(tt.seed,999), t.name
                """, (rs, row) -> new TeamRow(rs.getObject("id", UUID.class), rs.getString("name")), tournamentId);

        List<CompletedMatch> completed = completedMatches(fixtures);
        List<TeamAnalytics> teamAnalytics = teams.stream().map(t -> teamAnalytics(t, completed)).toList();
        teamAnalytics = teamAnalytics.stream()
                .sorted(Comparator.comparingInt(TeamAnalytics::points).reversed()
                        .thenComparing(Comparator.comparingDouble(TeamAnalytics::nrr).reversed())
                        .thenComparing(Comparator.comparingInt(TeamAnalytics::runsFor).reversed())
                        .thenComparing(TeamAnalytics::teamName))
                .toList();

        int total = fixtures.size();
        int completedCount = (int) fixtures.stream().filter(f -> "COMPLETED".equalsIgnoreCase(f.status())).count();
        int scheduled = (int) fixtures.stream().filter(f -> "SCHEDULED".equalsIgnoreCase(f.status())).count();
        int live = (int) fixtures.stream().filter(f -> "LIVE".equalsIgnoreCase(f.status())).count();
        BigDecimal completion = percentage(completedCount, total);

        List<PlayerLeader> runs = playerLeaders(tournamentId, true, 5);
        List<PlayerLeader> wickets = playerLeaders(tournamentId, false, 5);
        return new TournamentAnalytics(tournamentId, tournament.name(), tournament.format(), tournament.overs(),
                tournament.status(), total, completedCount, scheduled, live, completion,
                teamAnalytics, runs, wickets, fixtureProgress(fixtures));
    }

    private List<CompletedMatch> completedMatches(List<FixtureRow> fixtures) {
        List<UUID> ids = fixtures.stream().filter(f -> "COMPLETED".equalsIgnoreCase(f.status()))
                .map(FixtureRow::matchId).toList();
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.query("""
                SELECT m.id match_id, m.team_a_id, m.team_b_id,
                       i1.batting_team_id i1_team, i1.total_runs i1_runs, i1.legal_balls i1_balls,
                       i2.batting_team_id i2_team, i2.total_runs i2_runs, i2.legal_balls i2_balls
                FROM matches m
                LEFT JOIN innings i1 ON i1.match_id=m.id AND i1.innings_number=1
                LEFT JOIN innings i2 ON i2.match_id=m.id AND i2.innings_number=2
                WHERE m.id IN (""" + placeholders + ")", (rs, row) -> new CompletedMatch(
                rs.getObject("match_id", UUID.class), rs.getObject("team_a_id", UUID.class), rs.getObject("team_b_id", UUID.class),
                rs.getObject("i1_team", UUID.class), rs.getInt("i1_runs"), rs.getInt("i1_balls"),
                rs.getObject("i2_team", UUID.class), rs.getInt("i2_runs"), rs.getInt("i2_balls")), ids.toArray());
    }

    private TeamAnalytics teamAnalytics(TeamRow team, List<CompletedMatch> matches) {
        int played = 0, wins = 0, losses = 0, ties = 0, runsFor = 0, runsAgainst = 0;
        double ballsFor = 0, ballsAgainst = 0;
        List<MatchOutcome> outcomes = new ArrayList<>();
        for (CompletedMatch m : matches) {
            if (!team.id().equals(m.teamA()) && !team.id().equals(m.teamB())) continue;
            played++;
            boolean first = team.id().equals(m.i1Team());
            int own = first ? m.i1Runs() : m.i2Runs();
            int opp = first ? m.i2Runs() : m.i1Runs();
            int ownBalls = first ? m.i1Balls() : m.i2Balls();
            int oppBalls = first ? m.i2Balls() : m.i1Balls();
            runsFor += own; runsAgainst += opp; ballsFor += ownBalls; ballsAgainst += oppBalls;
            if (own > opp) { wins++; outcomes.add(new MatchOutcome("W")); }
            else if (own < opp) { losses++; outcomes.add(new MatchOutcome("L")); }
            else { ties++; outcomes.add(new MatchOutcome("T")); }
        }
        List<String> form = outcomes.stream().skip(Math.max(0, outcomes.size() - 5L)).map(MatchOutcome::result).toList();
        return new TeamAnalytics(team.id(), team.name(), played, wins, losses, ties, wins * 2 + ties,
                runsFor, runsAgainst, nrr(runsFor, runsAgainst, ballsFor, ballsAgainst), form);
    }

    private List<PlayerLeader> playerLeaders(UUID tournamentId, boolean runs, int limit) {
        String sql = runs ? """
                SELECT p.id player_id, p.name player_name, COALESCE(SUM(d.bat_runs),0) runs,
                       0 wickets
                FROM tournament_matches tm
                JOIN matches m ON m.id=tm.match_id AND m.status='COMPLETED'
                JOIN innings i ON i.match_id=m.id
                JOIN delivery_events d ON d.innings_id=i.id
                JOIN players p ON p.id=d.striker_id
                WHERE tm.tournament_id=?
                GROUP BY p.id,p.name
                ORDER BY SUM(d.bat_runs) DESC, p.name
                LIMIT ?
                """ : """
                SELECT p.id player_id, p.name player_name, 0 runs,
                       COUNT(*) FILTER (WHERE d.wicket_type IS NOT NULL) wickets
                FROM tournament_matches tm
                JOIN matches m ON m.id=tm.match_id AND m.status='COMPLETED'
                JOIN innings i ON i.match_id=m.id
                JOIN delivery_events d ON d.innings_id=i.id
                JOIN players p ON p.id=d.bowler_id
                WHERE tm.tournament_id=?
                GROUP BY p.id,p.name
                HAVING COUNT(*) FILTER (WHERE d.wicket_type IS NOT NULL) > 0
                ORDER BY COUNT(*) FILTER (WHERE d.wicket_type IS NOT NULL) DESC, p.name
                LIMIT ?
                """;
        return jdbc.query(sql, (rs, row) -> new PlayerLeader(
                rs.getObject("player_id", UUID.class), rs.getString("player_name"),
                rs.getInt("runs"), rs.getInt("wickets")), tournamentId, limit);
    }

    private List<FixtureProgress> fixtureProgress(List<FixtureRow> fixtures) {
        return fixtures.stream().map(f -> new FixtureProgress(f.fixtureNumber(), f.stage(), f.status(),
                f.teamAName(), f.teamBName())).toList();
    }

    private static BigDecimal percentage(int value, int total) {
        if (total <= 0) return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(value * 100.0 / total).setScale(1, RoundingMode.HALF_UP);
    }

    private static double nrr(int runsFor, int runsAgainst, double ballsFor, double ballsAgainst) {
        if (ballsFor <= 0 || ballsAgainst <= 0) return 0.0;
        return BigDecimal.valueOf((runsFor / (ballsFor / 6.0)) - (runsAgainst / (ballsAgainst / 6.0)))
                .setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    record TournamentRow(UUID id, String name, String format, int overs, String status) {}
    record TeamRow(UUID id, String name) {}
    record FixtureRow(UUID matchId, Integer fixtureNumber, String stage, String status,
                      UUID teamA, String teamAName, UUID teamB, String teamBName) {}
    record CompletedMatch(UUID matchId, UUID teamA, UUID teamB, UUID i1Team, int i1Runs, int i1Balls,
                          UUID i2Team, int i2Runs, int i2Balls) {}
    record MatchOutcome(String result) {}

    public record TournamentAnalytics(UUID tournamentId, String tournamentName, String format, int overs,
                                      String status, int totalFixtures, int completedFixtures,
                                      int scheduledFixtures, int liveFixtures, BigDecimal completionPercentage,
                                      List<TeamAnalytics> teams, List<PlayerLeader> topRunScorers,
                                      List<PlayerLeader> topWicketTakers, List<FixtureProgress> fixtures) {}

    public record TeamAnalytics(UUID teamId, String teamName, int played, int wins, int losses, int ties,
                                int points, int runsFor, int runsAgainst, double nrr, List<String> recentForm) {}

    public record PlayerLeader(UUID playerId, String playerName, int runs, int wickets) {}

    public record FixtureProgress(Integer fixtureNumber, String stage, String status,
                                  String teamAName, String teamBName) {}
}

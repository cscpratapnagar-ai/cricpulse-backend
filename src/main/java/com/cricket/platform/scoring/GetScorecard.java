package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class GetScorecard {
    private final JdbcTemplate jdbc;

    public GetScorecard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Scorecard> executeAll(List<UUID> inningsIds) {
        if (inningsIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(inningsIds.size(), "?"));
        Object[] args = inningsIds.toArray();

        Map<UUID, Scorecard> bases = new HashMap<>();
        jdbc.query("SELECT i.id, i.match_id, i.innings_number, i.batting_team_id, " +
                "t.name AS team_name, i.total_runs, i.wickets, i.legal_balls " +
                "FROM innings i JOIN teams t ON t.id = i.batting_team_id " +
                "WHERE i.id IN (" + placeholders + ") ORDER BY i.innings_number", rs ->
                bases.put(rs.getObject("id", UUID.class), new Scorecard(
                        rs.getObject("id", UUID.class), rs.getObject("match_id", UUID.class),
                        rs.getInt("innings_number"), rs.getObject("batting_team_id", UUID.class),
                        rs.getString("team_name"), rs.getInt("total_runs"), rs.getInt("wickets"),
                        rs.getInt("legal_balls"), 0, List.of(), List.of(), List.of())), args);

        Map<UUID, List<Batter>> batting = new HashMap<>();
        jdbc.query("SELECT b.innings_id, b.player_id, u.full_name, b.runs, b.balls_faced, " +
                "b.fours, b.sixes, b.strike_rate, b.is_out, b.dismissal_type " +
                "FROM innings_batters b JOIN players p ON p.id = b.player_id " +
                "JOIN users u ON u.id = p.user_id WHERE b.innings_id IN (" + placeholders + ") " +
                "ORDER BY b.innings_id, b.batting_position NULLS LAST, b.created_at", rs ->
                batting.computeIfAbsent(rs.getObject("innings_id", UUID.class), k -> new ArrayList<>())
                        .add(new Batter(rs.getObject("player_id", UUID.class), rs.getString("full_name"),
                                rs.getInt("runs"), rs.getInt("balls_faced"), rs.getInt("fours"),
                                rs.getInt("sixes"), rs.getBigDecimal("strike_rate"), rs.getBoolean("is_out"),
                                rs.getString("dismissal_type"))), args);

        Map<UUID, List<Bowler>> bowling = new HashMap<>();
        jdbc.query("SELECT b.innings_id, b.player_id, u.full_name, b.legal_balls, " +
                "b.runs_conceded, b.wickets, b.economy FROM innings_bowlers b " +
                "JOIN players p ON p.id = b.player_id JOIN users u ON u.id = p.user_id " +
                "WHERE b.innings_id IN (" + placeholders + ") ORDER BY b.innings_id, b.created_at", rs ->
                bowling.computeIfAbsent(rs.getObject("innings_id", UUID.class), k -> new ArrayList<>())
                        .add(new Bowler(rs.getObject("player_id", UUID.class), rs.getString("full_name"),
                                rs.getInt("legal_balls"), rs.getInt("runs_conceded"), rs.getInt("wickets"),
                                rs.getBigDecimal("economy"))), args);

        Map<UUID, List<FallOfWicket>> fow = new HashMap<>();
        jdbc.query("SELECT f.innings_id, f.wicket_number, u.full_name, f.runs, f.over_number, f.ball_number " +
                "FROM fall_of_wickets f JOIN players p ON p.id = f.player_id JOIN users u ON u.id = p.user_id " +
                "WHERE f.innings_id IN (" + placeholders + ") ORDER BY f.innings_id, f.wicket_number", rs ->
                fow.computeIfAbsent(rs.getObject("innings_id", UUID.class), k -> new ArrayList<>())
                        .add(new FallOfWicket(rs.getInt("wicket_number"), rs.getString("full_name"),
                                rs.getInt("runs"), rs.getInt("over_number"), rs.getInt("ball_number"))), args);

        Map<UUID, Integer> extras = new HashMap<>();
        jdbc.query("SELECT innings_id, COALESCE(SUM(extra_runs), 0) AS extras " +
                "FROM deliveries WHERE innings_id IN (" + placeholders + ") GROUP BY innings_id",
                rs -> extras.put(rs.getObject("innings_id", UUID.class), rs.getInt("extras")), args);

        return inningsIds.stream().map(id -> {
            Scorecard base = bases.get(id);
            if (base == null) return null;
            return new Scorecard(base.inningsId(), base.matchId(), base.inningsNumber(), base.battingTeamId(),
                    base.teamName(), base.runs(), base.wickets(), base.legalBalls(), extras.getOrDefault(id, 0),
                    batting.getOrDefault(id, List.of()), bowling.getOrDefault(id, List.of()),
                    fow.getOrDefault(id, List.of()));
        }).filter(java.util.Objects::nonNull).toList();
    }

    public Scorecard execute(UUID inningsId) {
        Scorecard base = jdbc.queryForObject("""
                SELECT i.id, i.match_id, i.innings_number, i.batting_team_id,
                       t.name AS team_name, i.total_runs, i.wickets, i.legal_balls
                FROM innings i
                JOIN teams t ON t.id = i.batting_team_id
                WHERE i.id = ?
                """, (rs, row) -> new Scorecard(
                rs.getObject("id", UUID.class),
                rs.getObject("match_id", UUID.class),
                rs.getInt("innings_number"),
                rs.getObject("batting_team_id", UUID.class),
                rs.getString("team_name"),
                rs.getInt("total_runs"),
                rs.getInt("wickets"),
                rs.getInt("legal_balls"),
                0,
                List.of(), List.of(), List.of()
        ), inningsId);

        List<Batter> batting = jdbc.query("""
                SELECT b.player_id, u.full_name, b.runs, b.balls_faced,
                       b.fours, b.sixes, b.strike_rate, b.is_out,
                       b.dismissal_type
                FROM innings_batters b
                JOIN players p ON p.id = b.player_id
                JOIN users u ON u.id = p.user_id
                WHERE b.innings_id = ?
                ORDER BY b.batting_position NULLS LAST, b.created_at
                """, (rs, row) -> new Batter(
                rs.getObject("player_id", UUID.class),
                rs.getString("full_name"),
                rs.getInt("runs"),
                rs.getInt("balls_faced"),
                rs.getInt("fours"),
                rs.getInt("sixes"),
                rs.getBigDecimal("strike_rate"),
                rs.getBoolean("is_out"),
                rs.getString("dismissal_type")
        ), inningsId);

        List<Bowler> bowling = jdbc.query("""
                SELECT b.player_id, u.full_name, b.legal_balls,
                       b.runs_conceded, b.wickets, b.economy
                FROM innings_bowlers b
                JOIN players p ON p.id = b.player_id
                JOIN users u ON u.id = p.user_id
                WHERE b.innings_id = ?
                ORDER BY b.created_at
                """, (rs, row) -> new Bowler(
                rs.getObject("player_id", UUID.class),
                rs.getString("full_name"),
                rs.getInt("legal_balls"),
                rs.getInt("runs_conceded"),
                rs.getInt("wickets"),
                rs.getBigDecimal("economy")
        ), inningsId);

        List<FallOfWicket> fow = jdbc.query("""
                SELECT f.wicket_number, u.full_name, f.runs,
                       f.over_number, f.ball_number
                FROM fall_of_wickets f
                JOIN players p ON p.id = f.player_id
                JOIN users u ON u.id = p.user_id
                WHERE f.innings_id = ?
                ORDER BY f.wicket_number
                """, (rs, row) -> new FallOfWicket(
                rs.getInt("wicket_number"),
                rs.getString("full_name"),
                rs.getInt("runs"),
                rs.getInt("over_number"),
                rs.getInt("ball_number")
        ), inningsId);

        int extras = jdbc.queryForObject("""
                SELECT COALESCE(SUM(extra_runs), 0)
                FROM deliveries WHERE innings_id = ?
                """, Integer.class, inningsId);

        return new Scorecard(base.inningsId(), base.matchId(), base.inningsNumber(),
                base.battingTeamId(), base.teamName(), base.runs(), base.wickets(),
                base.legalBalls(), extras, batting, bowling, fow);
    }

    public record Scorecard(UUID inningsId, UUID matchId, int inningsNumber,
                            UUID battingTeamId, String teamName, int runs,
                            int wickets, int legalBalls, int extras,
                            List<Batter> batting, List<Bowler> bowling,
                            List<FallOfWicket> fallOfWickets) {}

    public record Batter(UUID playerId, String playerName, int runs, int balls,
                         int fours, int sixes, BigDecimal strikeRate,
                         boolean out, String dismissal) {}

    public record Bowler(UUID playerId, String playerName, int legalBalls,
                         int runs, int wickets, BigDecimal economy) {}

    public record FallOfWicket(int wicketNumber, String playerName, int runs,
                               int overNumber, int ballNumber) {}
}

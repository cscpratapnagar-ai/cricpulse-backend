package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Component
public class GetScorecard {
    private final JdbcTemplate jdbc;

    public GetScorecard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

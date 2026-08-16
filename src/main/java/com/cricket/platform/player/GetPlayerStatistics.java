package com.cricket.platform.player;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Component
public class GetPlayerStatistics {
    private final JdbcTemplate jdbc;

    public GetPlayerStatistics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PlayerStatistics> all() {
        return jdbc.query("""
                SELECT p.id AS player_id, u.full_name,
                       COALESCE(b.matches, 0) AS matches,
                       COALESCE(b.innings, 0) AS batting_innings,
                       COALESCE(b.runs, 0) AS runs,
                       COALESCE(b.highest_score, 0) AS highest_score,
                       COALESCE(b.dismissals, 0) AS dismissals,
                       COALESCE(b.balls, 0) AS batting_balls,
                       COALESCE(b.fours, 0) AS fours,
                       COALESCE(b.sixes, 0) AS sixes,
                       COALESCE(w.overs_balls, 0) AS bowling_balls,
                       COALESCE(w.runs_conceded, 0) AS runs_conceded,
                       COALESCE(w.wickets, 0) AS wickets,
                       COALESCE(w.best_wickets, 0) AS best_wickets
                FROM players p
                JOIN users u ON u.id = p.user_id
                LEFT JOIN (
                    SELECT ib.player_id,
                           COUNT(DISTINCT i.match_id) AS matches,
                           COUNT(DISTINCT ib.innings_id) AS innings,
                           SUM(ib.runs) AS runs,
                           MAX(ib.runs) AS highest_score,
                           SUM(CASE WHEN ib.is_out THEN 1 ELSE 0 END) AS dismissals,
                           SUM(ib.balls_faced) AS balls,
                           SUM(ib.fours) AS fours,
                           SUM(ib.sixes) AS sixes
                    FROM innings_batters ib
                    JOIN innings i ON i.id = ib.innings_id
                    GROUP BY ib.player_id
                ) b ON b.player_id = p.id
                LEFT JOIN (
                    SELECT bow.player_id,
                           SUM(bow.legal_balls) AS overs_balls,
                           SUM(bow.runs_conceded) AS runs_conceded,
                           SUM(bow.wickets) AS wickets,
                           MAX(bow.wickets) AS best_wickets
                    FROM innings_bowlers bow
                    GROUP BY bow.player_id
                ) w ON w.player_id = p.id
                WHERE b.player_id IS NOT NULL OR w.player_id IS NOT NULL
                ORDER BY COALESCE(b.runs, 0) DESC, COALESCE(w.wickets, 0) DESC, u.full_name
                """, (rs, row) -> fromRow(rs));
    }

    public PlayerStatistics one(UUID playerId) {
        return jdbc.queryForObject("""
                SELECT p.id AS player_id, u.full_name,
                       COALESCE(b.matches, 0) AS matches,
                       COALESCE(b.innings, 0) AS batting_innings,
                       COALESCE(b.runs, 0) AS runs,
                       COALESCE(b.highest_score, 0) AS highest_score,
                       COALESCE(b.dismissals, 0) AS dismissals,
                       COALESCE(b.balls, 0) AS batting_balls,
                       COALESCE(b.fours, 0) AS fours,
                       COALESCE(b.sixes, 0) AS sixes,
                       COALESCE(w.overs_balls, 0) AS bowling_balls,
                       COALESCE(w.runs_conceded, 0) AS runs_conceded,
                       COALESCE(w.wickets, 0) AS wickets,
                       COALESCE(w.best_wickets, 0) AS best_wickets
                FROM players p
                JOIN users u ON u.id = p.user_id
                LEFT JOIN (
                    SELECT ib.player_id, COUNT(DISTINCT i.match_id) matches,
                           COUNT(DISTINCT ib.innings_id) innings, SUM(ib.runs) runs,
                           MAX(ib.runs) highest_score,
                           SUM(CASE WHEN ib.is_out THEN 1 ELSE 0 END) dismissals,
                           SUM(ib.balls_faced) balls, SUM(ib.fours) fours, SUM(ib.sixes) sixes
                    FROM innings_batters ib JOIN innings i ON i.id=ib.innings_id
                    WHERE ib.player_id=? GROUP BY ib.player_id
                ) b ON b.player_id=p.id
                LEFT JOIN (
                    SELECT player_id, SUM(legal_balls) overs_balls,
                           SUM(runs_conceded) runs_conceded, SUM(wickets) wickets, MAX(wickets) best_wickets
                    FROM innings_bowlers WHERE player_id=? GROUP BY player_id
                ) w ON w.player_id=p.id
                WHERE p.id=?
                """, (rs,row) -> fromRow(rs), playerId, playerId, playerId);
    }

    private PlayerStatistics fromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        int runs=rs.getInt("runs"), dismissals=rs.getInt("dismissals"), balls=rs.getInt("batting_balls");
        int bowlingBalls=rs.getInt("bowling_balls"), conceded=rs.getInt("runs_conceded"), wickets=rs.getInt("wickets");
        BigDecimal average=dismissals==0?BigDecimal.ZERO:BigDecimal.valueOf(runs/(double)dismissals);
        BigDecimal strikeRate=balls==0?BigDecimal.ZERO:BigDecimal.valueOf(runs*100.0/balls);
        BigDecimal economy=bowlingBalls==0?BigDecimal.ZERO:BigDecimal.valueOf(conceded/(bowlingBalls/6.0));
        return new PlayerStatistics(rs.getObject("player_id",UUID.class),rs.getString("full_name"),
                rs.getInt("matches"),rs.getInt("batting_innings"),runs,rs.getInt("highest_score"),dismissals,
                rs.getInt("fours"),rs.getInt("sixes"),balls,average,strikeRate,
                bowlingBalls,conceded,wickets,rs.getInt("best_wickets"),economy);
    }

    public record PlayerStatistics(UUID playerId,String playerName,int matches,int battingInnings,int runs,
                                   int highestScore,int dismissals,int fours,int sixes,int battingBalls,
                                   BigDecimal battingAverage,BigDecimal strikeRate,int bowlingBalls,
                                   int runsConceded,int wickets,int bestWickets,BigDecimal economy) {}
}

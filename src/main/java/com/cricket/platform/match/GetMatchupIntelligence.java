package com.cricket.platform.match;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Builds batter-versus-bowler summaries from the current delivery projection.
 * No prediction or inferred historical data is added.
 */
@Component
public class GetMatchupIntelligence {
    private final JdbcTemplate jdbc;

    public GetMatchupIntelligence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Matchup> get(UUID matchId) {
        return jdbc.query("""
                SELECT d.striker_id AS batter_id,
                       bu.full_name AS batter_name,
                       d.bowler_id,
                       bowler_user.full_name AS bowler_name,
                       COUNT(*) FILTER (
                           WHERE UPPER(COALESCE(d.extra_type, '')) <> 'WIDE'
                       ) AS balls_faced,
                       COALESCE(SUM(d.bat_runs), 0) AS batter_runs,
                       COUNT(*) FILTER (WHERE d.bat_runs = 4) AS fours,
                       COUNT(*) FILTER (WHERE d.bat_runs = 6) AS sixes,
                       COUNT(*) FILTER (
                           WHERE d.legal_delivery = TRUE AND d.total_runs = 0
                       ) AS dot_balls,
                       COUNT(*) FILTER (
                           WHERE d.dismissed_player_id = d.striker_id
                             AND d.wicket_type IS NOT NULL
                             AND UPPER(d.wicket_type) NOT IN (
                                 'RUN_OUT', 'RETIRED_HURT', 'RETIRED_OUT', 'OBSTRUCTING_FIELD'
                             )
                       ) AS dismissals
                FROM deliveries d
                JOIN innings i ON i.id = d.innings_id
                JOIN players batter ON batter.id = d.striker_id
                JOIN users bu ON bu.id = batter.user_id
                JOIN players bowler ON bowler.id = d.bowler_id
                JOIN users bowler_user ON bowler_user.id = bowler.user_id
                WHERE i.match_id = ?
                GROUP BY d.striker_id, bu.full_name, d.bowler_id, bowler_user.full_name
                HAVING COUNT(*) FILTER (
                    WHERE UPPER(COALESCE(d.extra_type, '')) <> 'WIDE'
                ) > 0
                ORDER BY balls_faced DESC, batter_runs DESC, batter_name, bowler_name
                LIMIT 12
                """, (rs, row) -> {
            int balls = rs.getInt("balls_faced");
            int runs = rs.getInt("batter_runs");
            int dots = rs.getInt("dot_balls");
            BigDecimal strikeRate = balls == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(runs * 100.0 / balls).setScale(2, RoundingMode.HALF_UP);
            BigDecimal dotPercentage = balls == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(dots * 100.0 / balls).setScale(1, RoundingMode.HALF_UP);
            String sample = balls >= 12 ? "ESTABLISHED SAMPLE" : "EARLY SAMPLE";
            return new Matchup(
                    rs.getObject("batter_id", UUID.class),
                    rs.getString("batter_name"),
                    rs.getObject("bowler_id", UUID.class),
                    rs.getString("bowler_name"),
                    balls,
                    runs,
                    rs.getInt("fours"),
                    rs.getInt("sixes"),
                    dots,
                    rs.getInt("dismissals"),
                    strikeRate,
                    dotPercentage,
                    sample
            );
        }, matchId);
    }

    public record Matchup(
            UUID batterId,
            String batterName,
            UUID bowlerId,
            String bowlerName,
            int ballsFaced,
            int runs,
            int fours,
            int sixes,
            int dotBalls,
            int dismissals,
            BigDecimal strikeRate,
            BigDecimal dotBallPercentage,
            String sampleLabel
    ) {}
}

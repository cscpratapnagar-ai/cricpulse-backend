package com.cricket.platform.match;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Summarizes recorded deliveries into innings phases. Phase boundaries are
 * derived from the match format and configured overs; no phase values are inferred
 * from current scorecard totals.
 */
@Component
public class GetInningsPhaseAnalysis {
    private final JdbcTemplate jdbc;

    public GetInningsPhaseAnalysis(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PhaseSummary> get(UUID matchId) {
        return jdbc.query("""
                WITH classified AS (
                    SELECT i.innings_number,
                           i.batting_team_id,
                           bt.name AS batting_team,
                           m.format,
                           d.over_number,
                           d.total_runs,
                           d.legal_delivery,
                           d.wicket_type,
                           d.bat_runs,
                           CASE
                               WHEN UPPER(m.format) IN ('T20', 'T20I', 'TWENTY20') THEN
                                   CASE
                                       WHEN d.over_number < 6 THEN 'POWERPLAY'
                                       WHEN d.over_number < 15 THEN 'MIDDLE'
                                       ELSE 'DEATH'
                                   END
                               WHEN UPPER(m.format) IN ('ODI', 'ODI50', 'ONE_DAY', 'ONE DAY') THEN
                                   CASE
                                       WHEN d.over_number < 10 THEN 'POWERPLAY'
                                       WHEN d.over_number < 40 THEN 'MIDDLE'
                                       ELSE 'DEATH'
                                   END
                               ELSE
                                   CASE
                                       WHEN d.over_number < GREATEST(
                                           1, FLOOR(COALESCE(NULLIF(i.total_overs, 0), m.total_overs, 20) * 0.30)::int
                                       ) THEN 'POWERPLAY'
                                       WHEN d.over_number < GREATEST(
                                           GREATEST(1, FLOOR(COALESCE(NULLIF(i.total_overs, 0), m.total_overs, 20) * 0.30)::int) + 1,
                                           FLOOR(COALESCE(NULLIF(i.total_overs, 0), m.total_overs, 20) * 0.80)::int
                                       ) THEN 'MIDDLE'
                                       ELSE 'DEATH'
                                   END
                           END AS phase
                    FROM deliveries d
                    JOIN innings i ON i.id = d.innings_id
                    JOIN matches m ON m.id = i.match_id
                    JOIN teams bt ON bt.id = i.batting_team_id
                    WHERE i.match_id = ?
                )
                SELECT innings_number, batting_team, format, phase,
                       COALESCE(SUM(total_runs), 0) AS runs,
                       COUNT(*) FILTER (WHERE wicket_type IS NOT NULL) AS wickets,
                       COUNT(*) FILTER (WHERE legal_delivery = TRUE) AS legal_balls,
                       COUNT(*) FILTER (WHERE legal_delivery = TRUE AND total_runs = 0) AS dot_balls,
                       COUNT(*) FILTER (WHERE bat_runs = 4) AS fours,
                       COUNT(*) FILTER (WHERE bat_runs = 6) AS sixes,
                       COUNT(DISTINCT over_number) AS overs_covered,
                       MIN(over_number) AS first_over,
                       MAX(over_number) AS last_over
                FROM classified
                GROUP BY innings_number, batting_team, format, phase
                ORDER BY innings_number,
                         CASE phase WHEN 'POWERPLAY' THEN 1 WHEN 'MIDDLE' THEN 2 ELSE 3 END
                """, (rs, row) -> {
            int balls = rs.getInt("legal_balls");
            int runs = rs.getInt("runs");
            int dots = rs.getInt("dot_balls");
            BigDecimal runRate = balls == 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(runs * 6.0 / balls).setScale(2, RoundingMode.HALF_UP);
            BigDecimal dotPercentage = balls == 0 ? BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(dots * 100.0 / balls).setScale(1, RoundingMode.HALF_UP);
            return new PhaseSummary(
                    rs.getInt("innings_number"),
                    rs.getString("batting_team"),
                    rs.getString("format"),
                    rs.getString("phase"),
                    runs,
                    rs.getInt("wickets"),
                    balls,
                    dots,
                    rs.getInt("fours"),
                    rs.getInt("sixes"),
                    rs.getInt("overs_covered"),
                    rs.getInt("first_over") + 1,
                    rs.getInt("last_over") + 1,
                    runRate,
                    dotPercentage,
                    balls == 0 ? "LIMITED SAMPLE" : balls < 12 ? "EARLY SAMPLE" : "RECORDED SAMPLE"
            );
        }, matchId);
    }

    public record PhaseSummary(
            int inningsNumber,
            String battingTeam,
            String format,
            String phase,
            int runs,
            int wickets,
            int legalBalls,
            int dotBalls,
            int fours,
            int sixes,
            int oversCovered,
            int fromOver,
            int toOver,
            BigDecimal runRate,
            BigDecimal dotBallPercentage,
            String sampleLabel
    ) {}
}

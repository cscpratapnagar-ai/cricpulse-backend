package com.cricket.platform.match;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Reconstructs batting partnerships from the ordered delivery ledger.
 * Wicket deliveries belong to the partnership that ended on that delivery.
 */
@Component
public class GetPartnershipAnalysis {
    private final JdbcTemplate jdbc;

    public GetPartnershipAnalysis(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Partnership> get(UUID matchId) {
        return jdbc.query("""
                WITH ordered AS (
                    SELECT d.*,
                           SUM(CASE WHEN d.wicket_type IS NOT NULL THEN 1 ELSE 0 END)
                               OVER (PARTITION BY d.innings_id ORDER BY d.sequence_number
                                     ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
                           - CASE WHEN d.wicket_type IS NOT NULL THEN 1 ELSE 0 END
                               AS partnership_number
                    FROM deliveries d
                    JOIN innings i ON i.id = d.innings_id
                    WHERE i.match_id = ?
                ),
                pairs AS (
                    SELECT DISTINCT ON (innings_id, partnership_number)
                           innings_id, partnership_number,
                           striker_id AS batter_one_id,
                           non_striker_id AS batter_two_id
                    FROM ordered
                    ORDER BY innings_id, partnership_number, sequence_number
                ),
                totals AS (
                    SELECT o.innings_id, o.partnership_number,
                           p.batter_one_id, p.batter_two_id,
                           SUM(o.total_runs) AS runs,
                           SUM(CASE WHEN o.legal_delivery THEN 1 ELSE 0 END) AS balls,
                           SUM(CASE WHEN o.striker_id = p.batter_one_id THEN o.bat_runs ELSE 0 END) AS batter_one_runs,
                           SUM(CASE WHEN o.striker_id = p.batter_two_id THEN o.bat_runs ELSE 0 END) AS batter_two_runs,
                           (ARRAY_AGG(o.over_number ORDER BY o.sequence_number))[1] AS start_over,
                           (ARRAY_AGG(o.ball_number ORDER BY o.sequence_number))[1] AS start_ball,
                           (ARRAY_AGG(o.over_number ORDER BY o.sequence_number DESC))[1] AS end_over,
                           (ARRAY_AGG(o.ball_number ORDER BY o.sequence_number DESC))[1] AS end_ball,
                           (ARRAY_AGG(o.wicket_type ORDER BY o.sequence_number DESC))[1] IS NULL AS is_current,
                           MAX(o.sequence_number) AS last_sequence
                    FROM ordered o
                    JOIN pairs p ON p.innings_id = o.innings_id
                                AND p.partnership_number = o.partnership_number
                    GROUP BY o.innings_id, o.partnership_number,
                             p.batter_one_id, p.batter_two_id
                )
                SELECT i.innings_number, bt.name AS batting_team,
                       t.partnership_number + 1 AS partnership_number,
                       t.batter_one_id, u1.full_name AS batter_one_name,
                       t.batter_two_id, u2.full_name AS batter_two_name,
                       t.runs, t.balls, t.batter_one_runs, t.batter_two_runs,
                       t.start_over, t.start_ball, t.end_over, t.end_ball,
                       (t.is_current AND UPPER(i.status) = 'LIVE') AS is_current
                FROM totals t
                JOIN innings i ON i.id = t.innings_id
                JOIN teams bt ON bt.id = i.batting_team_id
                JOIN players p1 ON p1.id = t.batter_one_id
                JOIN users u1 ON u1.id = p1.user_id
                JOIN players p2 ON p2.id = t.batter_two_id
                JOIN users u2 ON u2.id = p2.user_id
                ORDER BY i.innings_number, t.partnership_number
                """, (rs, row) -> new Partnership(
                rs.getInt("innings_number"),
                rs.getString("batting_team"),
                rs.getInt("partnership_number"),
                rs.getObject("batter_one_id", UUID.class),
                rs.getString("batter_one_name"),
                rs.getObject("batter_two_id", UUID.class),
                rs.getString("batter_two_name"),
                rs.getInt("runs"),
                rs.getInt("balls"),
                rs.getInt("batter_one_runs"),
                rs.getInt("batter_two_runs"),
                rs.getInt("start_over") + 1,
                rs.getInt("start_ball"),
                rs.getInt("end_over") + 1,
                rs.getInt("end_ball"),
                rs.getBoolean("is_current"),
                rs.getInt("balls") >= 12 ? "ESTABLISHED SAMPLE" : "EARLY SAMPLE"
        ), matchId);
    }

    public record Partnership(
            int inningsNumber,
            String battingTeam,
            int partnershipNumber,
            UUID batterOneId,
            String batterOneName,
            UUID batterTwoId,
            String batterTwoName,
            int runs,
            int balls,
            int batterOneRuns,
            int batterTwoRuns,
            int startOver,
            int startBall,
            int endOver,
            int endBall,
            boolean current,
            String sampleLabel
    ) {}
}

package com.cricket.platform.match;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class GetFieldingAnalysis {
    private final JdbcTemplate jdbc;

    public GetFieldingAnalysis(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FielderContribution> get(UUID matchId) {
        return List.copyOf(jdbc.query("""
                SELECT i.innings_number,
                       bowling_team.name AS bowling_team,
                       d.wicket_fielder_id AS fielder_id,
                       COALESCE(u.full_name, 'Unknown fielder') AS fielder_name,
                       COUNT(*) FILTER (WHERE d.wicket_type = 'CAUGHT') AS catches,
                       COUNT(*) FILTER (WHERE d.wicket_type = 'RUN_OUT') AS run_outs,
                       COUNT(*) FILTER (WHERE d.wicket_type = 'STUMPED') AS stumpings
                FROM deliveries d
                JOIN innings i ON i.id = d.innings_id
                JOIN matches m ON m.id = i.match_id
                JOIN teams bowling_team
                  ON bowling_team.id = CASE
                      WHEN i.batting_team_id = m.team_a_id THEN m.team_b_id
                      ELSE m.team_a_id
                  END
                JOIN players p ON p.id = d.wicket_fielder_id
                LEFT JOIN users u ON u.id = p.user_id
                WHERE i.match_id = ?
                  AND d.wicket_fielder_id IS NOT NULL
                  AND d.wicket_type IN ('CAUGHT', 'RUN_OUT', 'STUMPED')
                GROUP BY i.innings_number, bowling_team.name, d.wicket_fielder_id, u.full_name
                ORDER BY i.innings_number, (COUNT(*) FILTER (WHERE d.wicket_type = 'CAUGHT')
                         + COUNT(*) FILTER (WHERE d.wicket_type = 'RUN_OUT')
                         + COUNT(*) FILTER (WHERE d.wicket_type = 'STUMPED')) DESC,
                         fielder_name
                """, (rs, rowNum) -> {
            int catches = rs.getInt("catches");
            int runOuts = rs.getInt("run_outs");
            int stumpings = rs.getInt("stumpings");
            return new FielderContribution(
                    rs.getInt("innings_number"),
                    rs.getString("bowling_team"),
                    rs.getObject("fielder_id", UUID.class),
                    rs.getString("fielder_name"),
                    catches,
                    runOuts,
                    stumpings,
                    catches + runOuts + stumpings
            );
        }, matchId));
    }

    public record FielderContribution(
            int inningsNumber,
            String bowlingTeam,
            UUID fielderId,
            String fielderName,
            int catches,
            int runOuts,
            int stumpings,
            int totalDismissalContributions
    ) {}
}

package com.cricket.platform.player;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class GetPlayerFieldingStatistics {
    private final JdbcTemplate jdbc;

    public GetPlayerFieldingStatistics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public FieldingStatistics get(UUID playerId) {
        return jdbc.queryForObject("""
                SELECT p.id AS player_id,
                       u.full_name AS player_name,
                       COUNT(d.id) FILTER (WHERE d.wicket_type = 'CAUGHT') AS catches,
                       COUNT(d.id) FILTER (WHERE d.wicket_type = 'RUN_OUT') AS run_outs,
                       COUNT(d.id) FILTER (WHERE d.wicket_type = 'STUMPED') AS stumpings
                FROM players p
                JOIN users u ON u.id = p.user_id
                LEFT JOIN deliveries d ON d.wicket_fielder_id = p.id
                LEFT JOIN innings i ON i.id = d.innings_id AND i.status = 'COMPLETED'
                WHERE p.id = ?
                  AND (d.id IS NULL OR i.id IS NOT NULL)
                GROUP BY p.id, u.full_name
                """, (rs, row) -> {
            int catches = rs.getInt("catches");
            int runOuts = rs.getInt("run_outs");
            int stumpings = rs.getInt("stumpings");
            return new FieldingStatistics(
                    rs.getObject("player_id", UUID.class),
                    rs.getString("player_name"),
                    catches,
                    runOuts,
                    stumpings,
                    catches + runOuts + stumpings
            );
        }, playerId);
    }

    public record FieldingStatistics(
            UUID playerId,
            String playerName,
            int catches,
            int runOuts,
            int stumpings,
            int totalContributions
    ) {}
}

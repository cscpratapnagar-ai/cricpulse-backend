package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ResolveScorecardInnings {
    private final JdbcTemplate jdbc;

    public ResolveScorecardInnings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID execute(UUID matchId) {
        if (matchId == null) {
            throw new IllegalArgumentException("Match ID is required");
        }

        return jdbc.queryForObject("""
                SELECT i.id
                FROM innings i
                WHERE i.match_id = ?
                ORDER BY
                    CASE WHEN i.status = 'LIVE' THEN 0 ELSE 1 END,
                    i.innings_number DESC,
                    i.created_at DESC
                LIMIT 1
                """, UUID.class, matchId);
    }
}

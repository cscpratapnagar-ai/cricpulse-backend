package com.cricket.platform.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class GetLiveScore {
    private final JdbcTemplate jdbc;

    public GetLiveScore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Score execute(UUID inningsId) {
        return jdbc.queryForObject("SELECT id, match_id, innings_number, total_runs, wickets, legal_balls FROM innings WHERE id = ?",
                (rs, row) -> new Score(rs.getObject("id", UUID.class), rs.getObject("match_id", UUID.class),
                        rs.getInt("innings_number"), rs.getInt("total_runs"), rs.getInt("wickets"), rs.getInt("legal_balls")), inningsId);
    }

    public record Score(UUID inningsId, UUID matchId, int inningsNumber, int runs, int wickets, int legalBalls) {}
}

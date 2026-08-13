package com.cricket.platform.match;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class GetMatch {
    private final JdbcTemplate jdbc;

    public GetMatch(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public MatchView execute(UUID id) {
        return jdbc.queryForObject("""
                SELECT m.id,
                       m.name,
                       m.team_a_id,
                       m.team_b_id,
                       ta.name AS team_a_name,
                       tb.name AS team_b_name,
                       m.format,
                       m.status,
                       m.scheduled_at
                FROM matches m
                JOIN teams ta ON ta.id = m.team_a_id
                JOIN teams tb ON tb.id = m.team_b_id
                WHERE m.id = ?
                """,
                (rs, row) -> new MatchView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("team_a_id", UUID.class), rs.getObject("team_b_id", UUID.class),
                        rs.getString("team_a_name"), rs.getString("team_b_name"),
                        rs.getString("format"), rs.getString("status"), rs.getObject("scheduled_at", OffsetDateTime.class)), id);
    }

    public record MatchView(UUID id, String name, UUID teamAId, UUID teamBId, String teamAName,
                            String teamBName, String format, String status, OffsetDateTime scheduledAt) {}
}

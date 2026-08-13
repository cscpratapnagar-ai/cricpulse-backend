package com.cricket.platform.team;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class GetTeam {
    private final JdbcTemplate jdbc;

    public GetTeam(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public TeamView execute(UUID id) {
        return jdbc.queryForObject("SELECT id, name, city, owner_id FROM teams WHERE id = ?",
                (rs, row) -> new TeamView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("city"), rs.getObject("owner_id", UUID.class)), id);
    }

    public record TeamView(UUID id, String name, String city, UUID ownerId) {}
}

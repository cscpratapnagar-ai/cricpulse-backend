package com.cricket.platform.player;

import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AddPlayerToTeam {
    private final JdbcTemplate jdbc;

    public AddPlayerToTeam(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void execute(UUID teamId, Request request) {
        requireExists("teams", teamId, "Team was not found");
        requireExists("players", request.playerId(), "Player was not found");
        jdbc.update("INSERT INTO team_members(team_id, player_id, role) VALUES (?, ?, ?) ON CONFLICT (team_id, player_id) DO UPDATE SET role = EXCLUDED.role",
                teamId, request.playerId(), request.role().toUpperCase());
    }

    private void requireExists(String table, UUID id, String message) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id) == 0)
            throw new IllegalArgumentException(message);
    }

    public record Request(@NotNull UUID playerId, String role) {
        public Request { if (role == null || role.isBlank()) role = "PLAYER"; }
    }
}

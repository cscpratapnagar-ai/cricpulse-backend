package com.cricket.platform.player;

import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

@Component
public class AddPlayerToTeam {
    private final JdbcTemplate jdbc;

    public AddPlayerToTeam(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void execute(UUID teamId, Request request, String authenticatedEmail) {
        Integer owner = jdbc.queryForObject(
                "SELECT COUNT(*) FROM teams t JOIN users u ON u.id = t.owner_id WHERE t.id = ? AND LOWER(u.email) = LOWER(?)",
                Integer.class, teamId, authenticatedEmail
        );
        if (owner == null || owner == 0) {
            throw new IllegalArgumentException("Only the team owner can add players to this team");
        }

        requireExists("teams", teamId, "Team was not found");
        requireExists("players", request.playerId(), "Player profile was not found");

        String role = request.role() == null || request.role().isBlank()
                ? "PLAYER" : request.role().trim().toUpperCase(Locale.ROOT);
        if (!java.util.List.of("PLAYER", "MANAGER", "CAPTAIN", "VICE_CAPTAIN").contains(role)) {
            throw new IllegalArgumentException("Invalid team role. Use PLAYER, MANAGER, CAPTAIN or VICE_CAPTAIN");
        }
        jdbc.update("INSERT INTO team_members(team_id, player_id, role) VALUES (?, ?, ?) ON CONFLICT (team_id, player_id) DO UPDATE SET role = EXCLUDED.role",
                teamId, request.playerId(), role);
    }

    private void requireExists(String table, UUID id, String message) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id) == 0)
            throw new IllegalArgumentException(message);
    }

    public record Request(@NotNull UUID playerId, String role) {
        public Request { if (role == null || role.isBlank()) role = "PLAYER"; }
    }
}

package com.cricket.platform.team;

import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CreateTeam {
    private final JdbcTemplate jdbc;

    public CreateTeam(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TeamResponse execute(Request request, String authenticatedEmail) {
        UUID ownerId = jdbc.queryForObject(
                "SELECT id FROM users WHERE lower(email) = lower(?)",
                UUID.class,
                authenticatedEmail
        );

        if (ownerId == null) {
            throw new IllegalArgumentException("Authenticated user was not found");
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO teams(id, name, city, owner_id) VALUES (?, ?, ?, ?)",
                id, request.name().trim(), request.city() == null ? null : request.city().trim(), ownerId
        );

        // If the owner already has a player profile, make them the team OWNER.
        // The membership is intentionally context-specific; the account itself remains PLAYER.
        jdbc.update(
                "INSERT INTO team_members(team_id, player_id, role) " +
                "SELECT ?, id, 'OWNER' FROM players WHERE user_id = ? " +
                "ON CONFLICT (team_id, player_id) DO UPDATE SET role = 'OWNER'",
                id, ownerId
        );

        return new TeamResponse(id, request.name().trim(), request.city(), ownerId);
    }

    public record Request(@NotBlank String name, String city) {}
    public record TeamResponse(UUID id, String name, String city, UUID ownerId) {}
}

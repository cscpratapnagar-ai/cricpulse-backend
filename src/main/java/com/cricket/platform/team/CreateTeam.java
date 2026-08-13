package com.cricket.platform.team;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CreateTeam {
    private final JdbcTemplate jdbc;

    public CreateTeam(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public TeamResponse execute(Request request) {
        requireExists("users", request.ownerId(), "Owner user was not found");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO teams(id, name, city, owner_id) VALUES (?, ?, ?, ?)",
                id, request.name(), request.city(), request.ownerId());
        return new TeamResponse(id, request.name(), request.city(), request.ownerId());
    }

    private void requireExists(String table, UUID id, String message) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id) == 0)
            throw new IllegalArgumentException(message);
    }

    public record Request(@NotBlank String name, String city, @NotNull UUID ownerId) {}
    public record TeamResponse(UUID id, String name, String city, UUID ownerId) {}
}

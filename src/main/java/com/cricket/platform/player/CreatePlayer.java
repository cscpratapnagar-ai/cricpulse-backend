package com.cricket.platform.player;

import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CreatePlayer {
    private final JdbcTemplate jdbc;

    public CreatePlayer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public PlayerResponse execute(Request request) {
        requireUser(request.userId());
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO players(id, user_id, batting_style, bowling_style) VALUES (?, ?, ?, ?)",
                id, request.userId(), request.battingStyle(), request.bowlingStyle());
        String name = jdbc.queryForObject("SELECT full_name FROM users WHERE id = ?", String.class, request.userId());
        return new PlayerResponse(id, request.userId(), name, request.battingStyle(), request.bowlingStyle());
    }

    private void requireUser(UUID id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, id) == 0)
            throw new IllegalArgumentException("Player user was not found");
    }

    public record Request(@NotNull UUID userId, String battingStyle, String bowlingStyle) {}
    public record PlayerResponse(UUID id, UUID userId, String name, String battingStyle, String bowlingStyle) {}
}

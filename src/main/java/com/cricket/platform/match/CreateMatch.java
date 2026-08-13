package com.cricket.platform.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class CreateMatch {
    private final JdbcTemplate jdbc;

    public CreateMatch(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public MatchResponse execute(Request request) {
        requireTeam(request.teamAId());
        requireTeam(request.teamBId());
        if (request.teamAId().equals(request.teamBId()))
            throw new IllegalArgumentException("A team cannot play against itself");
        UUID id = UUID.randomUUID();
        String format = request.format().toUpperCase();
        jdbc.update("INSERT INTO matches(id, name, team_a_id, team_b_id, format, scheduled_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, request.name(), request.teamAId(), request.teamBId(), format, request.scheduledAt());
        return new MatchResponse(id, request.name(), format, "SCHEDULED");
    }

    private void requireTeam(UUID id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM teams WHERE id = ?", Integer.class, id) == 0)
            throw new IllegalArgumentException("Team was not found: " + id);
    }

    public record Request(@NotBlank String name, @NotNull UUID teamAId, @NotNull UUID teamBId,
                          @NotBlank String format, OffsetDateTime scheduledAt) {}
    public record MatchResponse(UUID id, String name, String format, String status) {}
}

package com.cricket.platform.scoring;

import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class StartInnings {
    private final JdbcTemplate jdbc;

    public StartInnings(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public InningsResponse execute(Request request) {
        requireExists("matches", request.matchId(), "Match was not found");
        requireExists("teams", request.battingTeamId(), "Batting team was not found");
        Integer participant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM matches WHERE id = ? AND (team_a_id = ? OR team_b_id = ?)",
                Integer.class, request.matchId(), request.battingTeamId(), request.battingTeamId());
        if (participant == null || participant == 0)
            throw new IllegalArgumentException("Batting team is not part of this match");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM innings WHERE match_id = ? AND innings_number = ?",
                Integer.class, request.matchId(), request.inningsNumber());
        if (count != null && count > 0) throw new IllegalArgumentException("This innings already exists");

        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO innings(id, match_id, innings_number, batting_team_id) VALUES (?, ?, ?, ?)",
                id, request.matchId(), request.inningsNumber(), request.battingTeamId());
        jdbc.update("UPDATE matches SET status = 'LIVE' WHERE id = ?", request.matchId());
        return new InningsResponse(id, request.matchId(), request.inningsNumber(), 0, 0, 0);
    }

    private void requireExists(String table, UUID id, String message) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id) == 0)
            throw new IllegalArgumentException(message);
    }

    public record Request(@NotNull UUID matchId, @NotNull Integer inningsNumber, @NotNull UUID battingTeamId) {}
    public record InningsResponse(UUID id, UUID matchId, int inningsNumber, int runs, int wickets, int legalBalls) {}
}

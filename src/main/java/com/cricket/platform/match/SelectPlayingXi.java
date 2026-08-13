package com.cricket.platform.match;

import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SelectPlayingXi {
    private final JdbcTemplate jdbc;

    public SelectPlayingXi(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void execute(UUID matchId, Request request) {
        Integer eligible = jdbc.queryForObject("SELECT COUNT(*) FROM matches m JOIN team_members tm ON tm.team_id IN (m.team_a_id, m.team_b_id) WHERE m.id = ? AND tm.team_id = ? AND tm.player_id = ?",
                Integer.class, matchId, request.teamId(), request.playerId());
        if (eligible == null || eligible == 0) throw new IllegalArgumentException("Player is not eligible for this match");
        jdbc.update("INSERT INTO match_players(match_id, team_id, player_id, is_playing_xi, is_captain, is_wicket_keeper) VALUES (?, ?, ?, TRUE, ?, ?) ON CONFLICT (match_id, player_id) DO UPDATE SET is_playing_xi = TRUE, is_captain = EXCLUDED.is_captain, is_wicket_keeper = EXCLUDED.is_wicket_keeper",
                matchId, request.teamId(), request.playerId(), request.captain(), request.wicketKeeper());
    }

    public record Request(@NotNull UUID teamId, @NotNull UUID playerId, boolean captain, boolean wicketKeeper) {}
}

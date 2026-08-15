package com.cricket.platform.match;

import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/matches/{matchId}/playing-xi")
public class PlayingXiController {
    private final SelectPlayingXi selectPlayingXi;
    private final JdbcTemplate jdbc;

    public PlayingXiController(SelectPlayingXi selectPlayingXi, JdbcTemplate jdbc) { this.selectPlayingXi = selectPlayingXi; this.jdbc = jdbc; }

    @PostMapping
    void select(@PathVariable UUID matchId, @Valid @RequestBody SelectPlayingXi.Request request, Authentication authentication) { selectPlayingXi.execute(matchId, request, authentication); }

    @DeleteMapping("/{teamId}/{playerId}")
    void remove(@PathVariable UUID matchId, @PathVariable UUID teamId, @PathVariable UUID playerId, Authentication authentication) { selectPlayingXi.remove(matchId, teamId, playerId, authentication); }

    @GetMapping
    List<PlayingPlayer> list(@PathVariable UUID matchId) {
        return jdbc.query("""
                SELECT mp.team_id, mp.player_id, u.full_name, mp.is_captain, mp.is_vice_captain, mp.is_wicket_keeper
                FROM match_players mp JOIN players p ON p.id = mp.player_id JOIN users u ON u.id = p.user_id
                WHERE mp.match_id = ? AND mp.is_playing_xi = TRUE ORDER BY mp.team_id, u.full_name
                """, (rs, row) -> new PlayingPlayer(rs.getObject("team_id", UUID.class), rs.getObject("player_id", UUID.class), rs.getString("full_name"), rs.getBoolean("is_captain"), rs.getBoolean("is_vice_captain"), rs.getBoolean("is_wicket_keeper")), matchId);
    }

    public record PlayingPlayer(UUID teamId, UUID playerId, String name, boolean captain, boolean viceCaptain, boolean wicketKeeper) {}
}

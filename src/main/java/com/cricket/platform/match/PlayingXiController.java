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
    List<PlayingPlayer> list(@PathVariable UUID matchId, Authentication authentication) {
        requireMatchAccess(matchId, authentication);
        return jdbc.query("""
                SELECT mp.team_id, mp.player_id, u.full_name, mp.is_captain, mp.is_vice_captain, mp.is_wicket_keeper
                FROM match_players mp JOIN players p ON p.id = mp.player_id JOIN users u ON u.id = p.user_id
                WHERE mp.match_id = ? AND mp.is_playing_xi = TRUE ORDER BY mp.team_id, u.full_name
                """, (rs, row) -> new PlayingPlayer(rs.getObject("team_id", UUID.class), rs.getObject("player_id", UUID.class), rs.getString("full_name"), rs.getBoolean("is_captain"), rs.getBoolean("is_vice_captain"), rs.getBoolean("is_wicket_keeper")), matchId);
    }

    private void requireMatchAccess(UUID matchId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        boolean globalAccess = authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .anyMatch(role -> role.equals("ROLE_ADMIN") || role.equals("ROLE_SCORER"));
        if (globalAccess) {
            return;
        }

        String principal = authentication.getName();
        Integer allowed = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM matches m
                WHERE m.id = ?
                  AND (
                    EXISTS (
                      SELECT 1 FROM teams t
                      JOIN users u ON u.id = t.owner_id
                      WHERE t.id IN (m.team_a_id, m.team_b_id)
                        AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)
                    )
                    OR EXISTS (
                      SELECT 1 FROM team_members tm
                      JOIN players p ON p.id = tm.player_id
                      JOIN users u ON u.id = p.user_id
                      WHERE tm.team_id IN (m.team_a_id, m.team_b_id)
                        AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)
                    )
                  )
                """, Integer.class, matchId, principal, principal, principal, principal);

        if (allowed == null || allowed == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "You do not have access to this match.");
        }
    }

    public record PlayingPlayer(UUID teamId, UUID playerId, String name, boolean captain, boolean viceCaptain, boolean wicketKeeper) {}
}

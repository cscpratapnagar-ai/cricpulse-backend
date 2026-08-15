package com.cricket.platform.match;

import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class SelectPlayingXi {
    private final JdbcTemplate jdbc;

    public SelectPlayingXi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void execute(UUID matchId, Request request, Authentication authentication) {
        requireAuthenticated(authentication);
        requireMatch(matchId);
        requireMatchTeam(matchId, request.teamId());
        requireTeamManager(request.teamId(), authentication);
        requirePlayerBelongsToTeam(request.teamId(), request.playerId());
        requireScheduledMatch(matchId);

        Integer currentXi = jdbc.queryForObject(
                "SELECT COUNT(*) FROM match_players WHERE match_id = ? AND team_id = ? AND is_playing_xi = TRUE",
                Integer.class, matchId, request.teamId());
        Integer alreadySelected = jdbc.queryForObject(
                "SELECT COUNT(*) FROM match_players WHERE match_id = ? AND team_id = ? AND player_id = ? AND is_playing_xi = TRUE",
                Integer.class, matchId, request.teamId(), request.playerId());

        if ((currentXi == null ? 0 : currentXi) >= 11 && (alreadySelected == null || alreadySelected == 0)) {
            throw new PlayingXiException("PLAYING_XI_FULL", "Playing XI already contains 11 players.");
        }

        if (request.captain()) {
            jdbc.update("UPDATE match_players SET is_captain = FALSE WHERE match_id = ? AND team_id = ?", matchId, request.teamId());
        }
        if (request.wicketKeeper()) {
            jdbc.update("UPDATE match_players SET is_wicket_keeper = FALSE WHERE match_id = ? AND team_id = ?", matchId, request.teamId());
        }

        jdbc.update("""
                INSERT INTO match_players(match_id, team_id, player_id, is_playing_xi, is_captain, is_wicket_keeper)
                VALUES (?, ?, ?, TRUE, ?, ?)
                ON CONFLICT (match_id, player_id) DO UPDATE SET
                    team_id = EXCLUDED.team_id,
                    is_playing_xi = TRUE,
                    is_captain = EXCLUDED.is_captain,
                    is_wicket_keeper = EXCLUDED.is_wicket_keeper
                """,
                matchId, request.teamId(), request.playerId(), request.captain(), request.wicketKeeper());
    }

    @Transactional
    public void remove(UUID matchId, UUID teamId, UUID playerId, Authentication authentication) {
        requireAuthenticated(authentication);
        requireMatch(matchId);
        requireMatchTeam(matchId, teamId);
        requireTeamManager(teamId, authentication);
        requireScheduledMatch(matchId);

        int deleted = jdbc.update(
                "DELETE FROM match_players WHERE match_id = ? AND team_id = ? AND player_id = ?",
                matchId, teamId, playerId);
        if (deleted == 0) {
            throw new PlayingXiException("PLAYING_XI_PLAYER_NOT_FOUND", "Player is not currently in the Playing XI.");
        }
    }

    private void requireMatch(UUID matchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM matches WHERE id = ?", Integer.class, matchId);
        if (count == null || count == 0) throw new PlayingXiException("MATCH_NOT_FOUND", "Match was not found.");
    }

    private void requireMatchTeam(UUID matchId, UUID teamId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM matches WHERE id = ? AND (team_a_id = ? OR team_b_id = ?)",
                Integer.class, matchId, teamId, teamId);
        if (count == null || count == 0) {
            throw new PlayingXiException("TEAM_NOT_IN_MATCH", "This team is not part of the match.");
        }
    }

    private void requirePlayerBelongsToTeam(UUID teamId, UUID playerId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_members WHERE team_id = ? AND player_id = ?",
                Integer.class, teamId, playerId);
        if (count == null || count == 0) {
            throw new PlayingXiException("PLAYER_NOT_ELIGIBLE", "Player is not a member of the selected team.");
        }
    }

    private void requireTeamManager(UUID teamId, Authentication authentication) {
        String principal = authentication.getName();
        Integer owner = jdbc.queryForObject("""
                SELECT COUNT(*) FROM teams t JOIN users u ON u.id = t.owner_id
                WHERE t.id = ? AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)
                """, Integer.class, teamId, principal, principal);
        if (owner != null && owner > 0) return;

        Integer manager = jdbc.queryForObject("""
                SELECT COUNT(*) FROM team_members tm
                JOIN players p ON p.id = tm.player_id JOIN users u ON u.id = p.user_id
                WHERE tm.team_id = ? AND tm.role IN ('MANAGER', 'CAPTAIN')
                  AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)
                """, Integer.class, teamId, principal, principal);
        if (manager == null || manager == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the team owner, manager or captain can manage the Playing XI.");
        }
    }

    private void requireScheduledMatch(UUID matchId) {
        String status = jdbc.queryForObject("SELECT status FROM matches WHERE id = ?", String.class, matchId);
        if (!"SCHEDULED".equalsIgnoreCase(status)) {
            throw new PlayingXiException("PLAYING_XI_LOCKED", "Playing XI can only be changed while the match is scheduled.");
        }
    }

    private void requireAuthenticated(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
    }

    public record Request(@NotNull UUID teamId, @NotNull UUID playerId, boolean captain, boolean wicketKeeper) {}

    public static class PlayingXiException extends RuntimeException {
        private final String code;
        public PlayingXiException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String getCode() { return code; }
    }
}

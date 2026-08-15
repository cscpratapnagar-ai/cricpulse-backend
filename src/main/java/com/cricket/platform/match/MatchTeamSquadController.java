package com.cricket.platform.match;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/matches")
public class MatchTeamSquadController {
    private final JdbcTemplate jdbc;

    public MatchTeamSquadController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{matchId}/teams/{teamId}/members")
    List<MemberView> members(@PathVariable UUID matchId, @PathVariable UUID teamId, Authentication authentication) {
        requireAuthenticated(authentication);
        requireMatchTeam(matchId, teamId);
        requireMatchManager(matchId, authentication);

        return jdbc.query("""
                SELECT tm.team_id, tm.player_id, p.user_id, u.full_name, u.email, u.phone, tm.role
                FROM team_members tm
                JOIN players p ON p.id = tm.player_id
                JOIN users u ON u.id = p.user_id
                WHERE tm.team_id = ?
                ORDER BY u.full_name
                """,
                (rs, row) -> new MemberView(
                        rs.getObject("team_id", UUID.class),
                        rs.getObject("player_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("role")
                ), teamId);
    }

    private void requireMatchTeam(UUID matchId, UUID teamId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM matches WHERE id = ? AND (team_a_id = ? OR team_b_id = ?)",
                Integer.class, matchId, teamId, teamId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This team is not part of the match.");
        }
    }

    private void requireMatchManager(UUID matchId, Authentication authentication) {
        String principal = authentication.getName();
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM matches m
                WHERE m.id = ?
                  AND (
                    EXISTS (
                      SELECT 1 FROM teams t JOIN users u ON u.id = t.owner_id
                      WHERE t.id IN (m.team_a_id, m.team_b_id)
                        AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)
                    )
                    OR EXISTS (
                      SELECT 1
                      FROM team_members tm
                      JOIN players p ON p.id = tm.player_id
                      JOIN users u ON u.id = p.user_id
                      WHERE tm.team_id IN (m.team_a_id, m.team_b_id)
                        AND tm.role IN ('MANAGER', 'CAPTAIN')
                        AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)
                    )
                  )
                """, Integer.class, matchId, principal, principal, principal, principal);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a team owner, manager or captain participating in this match can view squad members.");
        }
    }

    private void requireAuthenticated(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
    }

    public record MemberView(UUID teamId, UUID playerId, UUID userId, String fullName, String email, String phone, String role) {}
}

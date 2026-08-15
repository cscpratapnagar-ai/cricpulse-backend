package com.cricket.platform.scoring;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Component
public class ScoringAccess {
    private final JdbcTemplate jdbc;

    public ScoringAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireMatchManager(UUID matchId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        if (hasGlobalRole(authentication, "ROLE_ADMIN") || hasGlobalRole(authentication, "ROLE_SCORER")) {
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
                        AND tm.role IN ('MANAGER', 'CAPTAIN')
                        AND (LOWER(TRIM(u.email)) = LOWER(TRIM(?)) OR CAST(u.id AS TEXT) = ?)
                    )
                  )
                """, Integer.class, matchId, principal, principal, principal, principal);

        if (allowed == null || allowed == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a team owner, manager or captain can control match scoring.");
        }
    }

    public UUID matchIdForInnings(UUID inningsId) {
        UUID matchId = jdbc.queryForObject(
                "SELECT match_id FROM innings WHERE id = ?",
                UUID.class,
                inningsId);
        if (matchId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Innings was not found.");
        }
        return matchId;
    }

    private boolean hasGlobalRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role::equals);
    }
}

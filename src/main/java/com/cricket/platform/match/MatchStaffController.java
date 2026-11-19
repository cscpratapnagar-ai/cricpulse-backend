package com.cricket.platform.match;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/matches/{matchId}/staff")
public class MatchStaffController {
    private static final Set<String> STAFF_ROLES = Set.of("SCORER", "UMPIRE", "COMMENTATOR", "STREAMER");
    private final JdbcTemplate jdbc;

    public MatchStaffController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<StaffView> list(@PathVariable UUID matchId, Authentication authentication) {
        requireMatchManager(matchId, authentication);
        return jdbc.query("""
                SELECT ms.match_id, ms.user_id, u.full_name, u.email, ms.role, ms.assigned_at
                FROM match_staff ms
                JOIN users u ON u.id = ms.user_id
                WHERE ms.match_id = ?
                ORDER BY ms.role, u.full_name
                """,
                (rs, row) -> new StaffView(
                        rs.getObject("match_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("role"),
                        rs.getObject("assigned_at", OffsetDateTime.class)), matchId);
    }

    @GetMapping("/candidates")
    List<StaffCandidate> candidates(@PathVariable UUID matchId, @RequestParam("q") String query,
                                    Authentication authentication) {
        requireMatchManager(matchId, authentication);
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (term.length() < 2) return List.of();
        String pattern = "%" + term + "%";
        return jdbc.query("""
                SELECT id, full_name, email
                FROM users
                WHERE LOWER(COALESCE(full_name, '')) LIKE ?
                   OR LOWER(COALESCE(email, '')) LIKE ?
                ORDER BY full_name
                LIMIT 20
                """,
                (rs, row) -> new StaffCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email")), pattern, pattern);
    }

    @PutMapping("/{role}")
    @Transactional
    StaffView assign(@PathVariable UUID matchId, @PathVariable String role,
                     @Valid @RequestBody AssignStaffRequest request, Authentication authentication) {
        requireMatchManager(matchId, authentication);
        String normalizedRole = normalizeRole(role);
        Integer userExists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, request.userId());
        if (userExists == null || userExists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The selected user account was not found.");
        }
        jdbc.update("""
                INSERT INTO match_staff(match_id, user_id, role)
                VALUES (?, ?, ?)
                ON CONFLICT (match_id, user_id, role)
                DO UPDATE SET assigned_at = now()
                """, matchId, request.userId(), normalizedRole);
        return jdbc.queryForObject("""
                SELECT ms.match_id, ms.user_id, u.full_name, u.email, ms.role, ms.assigned_at
                FROM match_staff ms JOIN users u ON u.id = ms.user_id
                WHERE ms.match_id = ? AND ms.user_id = ? AND ms.role = ?
                """,
                (rs, row) -> new StaffView(
                        rs.getObject("match_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("role"),
                        rs.getObject("assigned_at", OffsetDateTime.class)),
                matchId, request.userId(), normalizedRole);
    }

    @DeleteMapping("/{role}/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unassign(@PathVariable UUID matchId, @PathVariable String role, @PathVariable UUID userId,
                  Authentication authentication) {
        requireMatchManager(matchId, authentication);
        jdbc.update("DELETE FROM match_staff WHERE match_id = ? AND user_id = ? AND role = ?",
                matchId, userId, normalizeRole(role));
    }

    String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!STAFF_ROLES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported match staff role.");
        }
        return normalized;
    }

    private void requireMatchManager(UUID matchId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        boolean admin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
        if (admin) return;

        String principal = authentication.getName();
        Integer allowed = jdbc.queryForObject("""
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
                    "Only a team owner, manager or captain can assign match staff.");
        }
    }

    public record AssignStaffRequest(@NotNull UUID userId) {}
    public record StaffCandidate(UUID userId, String fullName, String email) {}
    public record StaffView(UUID matchId, UUID userId, String fullName, String email,
                            String role, OffsetDateTime assignedAt) {}
}
